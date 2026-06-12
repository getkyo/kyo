package kyo.net.internal.transport

import kyo.*
import kyo.scheduler.IOPromise

/** Pump that drains an outbound channel and writes bytes to the socket via the driver.
  *
  * #### Lifecycle
  *
  *   1. `start()` registers as taker on the outbound channel
  *   2. Channel delivers a span, `onComplete` fires
  *   3. Pump calls `driver.write(data)`
  *   4. If Done: re-register on channel for next span
  *   5. If Partial: call `driver.awaitWritable`, retry remaining bytes when writable
  *   6. If Error: teardown
  *
  * #### Two modes
  *
  *   - **Normal mode**: waiting for channel to produce data
  *   - **Awaiting writable mode**: partial write happened, waiting for socket to become writable
  *
  * The pump switches between modes based on write results.
  *
  * #### Promise reuse
  *
  * The pump extends `IOPromise[Closed, Span[Byte]]` for channel takes. A separate `writablePromise` (also reused) handles writable
  * notifications.
  */
final private[kyo] class WritePump[Handle](
    handle: Handle,
    driver: IoDriver[Handle],
    channel: Channel.Unsafe[Span[Byte]],
    closeFn: () => Unit,
    private val log: Log.Unsafe = Log.live.unsafe
) extends IOPromise[Closed, Span[Byte]]:

    private val selfForTake: Fiber.Promise.Unsafe[Span[Byte], Abort[Closed]] =
        this.asInstanceOf[Fiber.Promise.Unsafe[Span[Byte], Abort[Closed]]]

    // Separate promise for awaitWritable, reused across partial write retries
    private val writablePromise: WritablePromise[Handle] = new WritablePromise(this)

    // Pending bytes from a partial write, to retry when writable
    private var pendingWrite: Span[Byte] = Span.empty[Byte]

    // Offset into pendingWrite: the unsent region is [pendingWriteOffset, pendingWrite.size)
    private var pendingWriteOffset: Int = 0

    // True when waiting for writable notification (not channel take)
    private var awaitingWritable: Boolean = false

    // True after first partial write: writablePromise starts in Pending state
    // and does not need reset before first use
    private var writablePromiseUsed: Boolean = false

    /** Start the pump. Registers as taker on the channel. */
    def start()(using AllowUnsafe, Frame): Unit =
        log.info(s"WritePump starting on ${driver.handleLabel(handle)}")
        channel.reuseTake(selfForTake)

    /** Callback when channel produces a span (normal mode) or promise completes after channel take. */
    override protected def onComplete(): Unit =
        // Unsafe: IOPromise.onComplete is the scheduler callback boundary; it runs synchronously off the promise and has no AllowUnsafe in scope.
        import AllowUnsafe.embrace.danger
        given Frame = Frame.internal

        poll() match
            case Present(Result.Success(bytes)) =>
                // No per-take log on the steady write path: the abstract Log.Unsafe materializes a Function0 per call even when the
                // level gates the string. The success-path ReadPump has no per-op log either. Warn/error paths below keep their logs.
                doWrite(bytes, 0)
            case Present(Result.Failure(_: Closed)) =>
                teardown()
            case Present(Result.Panic(t)) =>
                log.error(s"WritePump take panic on ${driver.handleLabel(handle)}", t)
                teardown()
        end match
    end onComplete

    /** Called by writablePromise when socket becomes writable. */
    private[internal] def onWritable()(using AllowUnsafe, Frame): Unit =
        awaitingWritable = false
        val remaining = pendingWrite
        val off       = pendingWriteOffset
        pendingWrite = Span.empty[Byte]
        pendingWriteOffset = 0
        doWrite(remaining, off)
    end onWritable

    /** Called by writablePromise on error. */
    private[internal] def onWritableError()(using AllowUnsafe, Frame): Unit =
        awaitingWritable = false
        teardown()
    end onWritableError

    private def doWrite(data: Span[Byte], offset: Int)(using AllowUnsafe, Frame): Unit =
        driver.write(handle, data, offset) match
            case WriteResult.Done =>
                requestNextTake()
            case WriteResult.Partial(remaining, newOffset) =>
                pendingWrite = remaining
                pendingWriteOffset = newOffset
                awaitingWritable = true
                if !writablePromiseUsed then
                    writablePromiseUsed = true // First use: promise is already in Pending state
                else
                    discard(writablePromise.reset())
                end if
                driver.awaitWritable(handle, writablePromise.self)
            case WriteResult.Error =>
                log.debug(s"WritePump write error on ${driver.handleLabel(handle)}")
                teardown()
        end match
    end doWrite

    private def requestNextTake()(using AllowUnsafe, Frame): Unit =
        discard(becomeAvailable())
        channel.reuseTake(selfForTake)
    end requestNextTake

    private def teardown()(using AllowUnsafe, Frame): Unit =
        discard(channel.close())
        closeFn()
    end teardown

end WritePump

/** Helper promise for awaiting writable notifications. Delegates completion to the parent WritePump. */
final private class WritablePromise[Handle](pump: WritePump[Handle]) extends IOPromise[Closed, Unit]:

    val self: Promise.Unsafe[Unit, Abort[Closed]] =
        this.asInstanceOf[Promise.Unsafe[Unit, Abort[Closed]]]

    /** Reset the promise for reuse. Exposes protected becomeAvailable. */
    def reset(): Boolean = becomeAvailable()

    override protected def onComplete(): Unit =
        // Unsafe: IOPromise.onComplete is the scheduler callback boundary; it runs synchronously off the promise and has no AllowUnsafe in scope.
        import AllowUnsafe.embrace.danger
        given Frame = Frame.internal
        poll() match
            case Present(Result.Success(_)) =>
                pump.onWritable()
            case Present(Result.Failure(_)) | Present(Result.Panic(_)) =>
                pump.onWritableError()
        end match
    end onComplete

end WritablePromise
