package kyo.net.internal

import java.io.InputStream
import java.io.IOException
import java.io.OutputStream
import kyo.*
import kyo.net.Connection as NetConnection

/** Driverless stdio connection for the NIO floor: bridges process stdin/stdout to a [[kyo.net.Connection]] with two pump fibers.
  *
  * stdin/stdout are not selectable channels, so the NIO transport cannot register them with its `Selector` the way it registers sockets.
  * Instead of a driver, [[open]] builds the connection like `Connection.inMemory` (two channels, a close flag, a closing promise) and starts
  * two dedicated fibers over the `java.lang.System.in`/`java.lang.System.out` streams captured at open time (capture-at-open makes the
  * connection testable via `System.setIn`/`System.setOut`):
  *
  *   - The read pump loops a blocking `in.read` and puts each read's bytes on the inbound channel. The carrier parks in the read and the
  *     scheduler's `BlockingMonitor` drains its queue to other workers, the same sanctioned-blocking pattern as `SystemResolver`'s DNS call
  *     and the posix `BlockingReaderDriver`, so no carrier is permanently starved. EOF (or a read failure, which for a byte stream over fd 0
  *     also ends the inbound side) closes the connection and exits; a put failing `Closed` (the connection closed locally) exits.
  *   - The write pump loops taking from the outbound channel and writes + flushes each span to `out`. A local close marks the outbound
  *     closing-for-writes while takes keep draining the queued spans, so every span accepted before the close is written and flushed before
  *     the pump's take fails `Closed` and it exits (the flush-on-close contract).
  *
  * `close()` closes both channels (await-empty, preserving buffered inbound bytes for the consumer and the outbound flush above), fires the
  * closing promise, and never touches fds 0/1: the process owns them. Shared limitation with the posix `BlockingReaderDriver`: a read pump
  * parked in `in.read` when the connection closes stays parked until the next stdin byte or EOF, which is inherent to a blocking read of a
  * process-lifetime fd.
  */
private[kyo] object NioStdioConnection:

    /** Build the stdio connection over the CURRENT `java.lang.System.in`/`java.lang.System.out` and start its pump fibers. The caller
      * ([[NioTransport.stdio]]) guarantees single ownership through its claim CAS.
      */
    def open(channelCapacity: Int, readBufferSize: Int)(using AllowUnsafe, Frame): NetConnection =
        val in: InputStream   = java.lang.System.in
        val out: OutputStream = java.lang.System.out
        val inboundCh         = Channel.Unsafe.init[Span[Byte]](channelCapacity)
        val outboundCh        = Channel.Unsafe.init[Span[Byte]](channelCapacity)
        val closedFlag        = AtomicBoolean.Unsafe.init(false)
        val closingPromise    = Promise.Unsafe.init[Unit, Any]()

        def closeConnection()(using AllowUnsafe, Frame): Unit =
            // Close both channels; there is no driver to cancel, and fds 0/1 are never touched (the process owns them). Idempotent via the CAS.
            if closedFlag.compareAndSet(false, true) then
                closingPromise.completeDiscard(Result.succeed(()))
                // closeAwaitEmpty, not close: buffered inbound bytes stay takeable by the consumer, and the write pump keeps draining the
                // queued outbound spans (writing + flushing each) until its take fails Closed, so no accepted byte is discarded by the close.
                discard(inboundCh.closeAwaitEmpty())
                discard(outboundCh.closeAwaitEmpty())

        // Read pump: blocking in.read on a dedicated carrier (the BlockingMonitor compensates the parked carrier), bytes onto the inbound
        // channel. n < 0 is EOF: close the connection and exit. Abort[Closed] from the put (the connection closed locally while the pump
        // was reading) exits through the Abort.run below.
        discard(Fiber.Unsafe.init {
            val buf = new Array[Byte](readBufferSize)
            Abort.run[Closed] {
                Loop.foreach {
                    Sync.defer(readOnce(in, buf)).map { n =>
                        if n < 0 then Sync.defer(closeConnection()).andThen(Loop.done)
                        else if n == 0 then Loop.continue
                        else inboundCh.safe.put(Span.fromUnsafe(java.util.Arrays.copyOf(buf, n))).andThen(Loop.continue)
                    }
                }
            }.unit
        })

        // Write pump: take from the outbound channel, write + flush to out. Closed from the take (local close, after the queued spans
        // drained) exits; a write failure closes the connection (nothing more can be delivered) and exits.
        discard(Fiber.Unsafe.init {
            Abort.run[Closed] {
                Loop.foreach {
                    outboundCh.safe.take.map { span =>
                        Sync.defer {
                            try
                                out.write(span.toArrayUnsafe)
                                out.flush()
                                Loop.continue
                            catch
                                case _: IOException =>
                                    closeConnection()
                                    Loop.done
                        }
                    }
                }
            }.unit
        })

        new NetConnection:
            def inbound: Channel.Unsafe[Span[Byte]]                                    = inboundCh
            def outbound: Channel.Unsafe[Span[Byte]]                                   = outboundCh
            def isOpen(using AllowUnsafe): Boolean                                     = !closedFlag.get()
            def close()(using AllowUnsafe, Frame): Unit                                = closeConnection()
            private[kyo] def onClosing: Fiber.Unsafe[Unit, Any]                        = closingPromise
            def detachForUpgrade()(using AllowUnsafe, Frame): Maybe[Chunk[Span[Byte]]] = Absent // not upgradable: no driver or socket
            private[net] def start()(using AllowUnsafe, Frame): Boolean                = true   // pumps already started at open
            // Plaintext, driverless connection: no peer certificate to hash and no close_notify exchange to observe.
            def serverCertificateHash: Maybe[Span[Byte]] = Absent
            def closeReason: NetConnection.CloseReason   = NetConnection.CloseReason.Active
        end new
    end open

    /** One blocking stdin read: the byte count, 0 for a zero-length read, or -1 on EOF. A read failure (the stream was closed or broke
      * underneath the pump) also returns -1: for a byte stream over fd 0 a failed read and EOF both mean the inbound side has ended.
      */
    private def readOnce(in: InputStream, buf: Array[Byte]): Int =
        try in.read(buf)
        catch case _: IOException => -1

end NioStdioConnection
