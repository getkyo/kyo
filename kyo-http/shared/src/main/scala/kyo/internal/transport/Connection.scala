package kyo.internal.transport

import kyo.*
import kyo.scheduler.IOPromise

/** A TCP connection bundling handle, channels, and pumps.
  *
  * ## Architecture
  *
  * ```
  * Socket ←→ Driver ←→ Pumps ←→ Channels ←→ User code
  *                      ↑
  *                 Connection owns these
  * ```
  *
  * ## Lifecycle
  *
  *   1. `init()` creates channels and pumps
  *   2. `start()` starts the pumps (begins I/O)
  *   3. User reads from `inbound`, writes to `outbound`
  *   4. `close()` stops pumps and closes handle
  *
  * ## Backpressure
  *
  * Channel capacity controls backpressure. When inbound channel fills, ReadPump stops requesting reads, causing TCP flow control. When
  * outbound channel fills, writers block until WritePump drains data.
  */
final private[kyo] class Connection[Handle] private (
    val handle: Handle,
    private val driver: IoDriver[Handle],
    val inbound: Channel.Unsafe[Span[Byte]],
    val outbound: Channel.Unsafe[Span[Byte]],
    private val readPump: ReadPump[Handle],
    private val writePump: WritePump[Handle],
    private val closedFlag: java.util.concurrent.atomic.AtomicBoolean,
    private val closeFn: () => Unit
):

    /** Start the connection. Begins pumping data between socket and channels. */
    def start()(using AllowUnsafe, Frame): Unit =
        Log.live.unsafe.info(s"Connection starting")
        readPump.start()
        writePump.start()
    end start

    /** Check if connection is still open. */
    def isOpen(using AllowUnsafe): Boolean =
        !closedFlag.get()

    /** Close the connection. Closes channels and handle. Idempotent. */
    def close()(using AllowUnsafe, Frame): Unit = closeFn()

end Connection

private[kyo] object Connection:

    /** Create a connection. Does not start pumps — call `start()` after creation. */
    def init[Handle](
        handle: Handle,
        driver: IoDriver[Handle],
        channelCapacity: Int
    )(using AllowUnsafe, Frame): Connection[Handle] =
        val inbound    = Channel.Unsafe.init[Span[Byte]](channelCapacity)
        val outbound   = Channel.Unsafe.init[Span[Byte]](channelCapacity)
        val closedFlag = new java.util.concurrent.atomic.AtomicBoolean(false)

        val closeFn: () => Unit = () =>
            if closedFlag.compareAndSet(false, true) then
                discard(inbound.close())
                discard(outbound.close())
                driver.cancel(handle)
                driver.closeHandle(handle)

        val readPump  = new ReadPump(handle, driver, inbound, closeFn)
        val writePump = new WritePump(handle, driver, outbound, closeFn)

        new Connection(handle, driver, inbound, outbound, readPump, writePump, closedFlag, closeFn)
    end init

end Connection
