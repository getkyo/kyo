package kyo.internal.transport

import kyo.*
import kyo.scheduler.IOPromise

/** Pump that drains an outbound channel and writes bytes to the socket via the driver.
  *
  * ## Lifecycle
  *
  *   1. `start()` registers as taker on the outbound channel
  *   2. Channel delivers a span, `onComplete` fires
  *   3. Pump calls `driver.write(data)`
  *   4. If Done: re-register on channel for next span
  *   5. If Partial: call `driver.awaitWritable`, retry remaining bytes when writable
  *   6. If Error: teardown
  *
  * ## Two modes
  *
  *   - **Normal mode**: waiting for channel to produce data
  *   - **Awaiting writable mode**: partial write happened, waiting for socket to become writable
  *
  * The pump switches between modes based on write results.
  *
  * ## Promise reuse
  *
  * The pump extends `IOPromise[Closed, Span[Byte]]` for channel takes. A separate `writablePromise` (also reused) handles writable
  * notifications.
  */
final private[kyo] class WritePump[Handle](
    handle: Handle,
    driver: IoDriver[Handle],
    channel: Channel.Unsafe[Span[Byte]],
    closeFn: () => Unit
) extends IOPromise[Closed, Span[Byte]]:

    private val selfForTake: Fiber.Promise.Unsafe[Span[Byte], Abort[Closed]] =
        this.asInstanceOf[Fiber.Promise.Unsafe[Span[Byte], Abort[Closed]]]

    // Separate promise for awaitWritable, reused across partial write retries
    private val writablePromise: WritablePromise[Handle] = new WritablePromise(this)

    // Pending bytes from a partial write, to retry when writable
    private var pendingWrite: Span[Byte] = Span.empty[Byte]

    // True when waiting for writable notification (not channel take)
    private var awaitingWritable: Boolean = false

    // True after first partial write — writablePromise starts in Pending state
    // and does not need reset before first use
    private var writablePromiseUsed: Boolean = false

    /** Start the pump. Registers as taker on the channel. */
    def start()(using AllowUnsafe, Frame): Unit =
        Log.live.unsafe.info(s"WritePump starting on ${driver.handleLabel(handle)}")
        channel.reuseTake(selfForTake)

    /** Callback when channel produces a span (normal mode) or promise completes after channel take. */
    override protected def onComplete(): Unit =
        import AllowUnsafe.embrace.danger
        given Frame = Frame.internal

        if awaitingWritable then
            // This shouldn't happen — writable notifications go through writablePromise
            Log.live.unsafe.warn(s"WritePump onComplete while awaitingWritable on ${driver.handleLabel(handle)}")
        else
            poll() match
                case Present(Result.Success(bytes)) =>
                    Log.live.unsafe.info(s"WritePump got ${bytes.size} bytes to write on ${driver.handleLabel(handle)}")
                    doWrite(bytes)
                case Present(Result.Failure(_: Closed)) =>
                    teardown()
                case Present(Result.Panic(t)) =>
                    Log.live.unsafe.error(s"WritePump take panic on ${driver.handleLabel(handle)}", t)
                    teardown()
                case Absent =>
                    Log.live.unsafe.warn(s"WritePump onComplete with no result on ${driver.handleLabel(handle)}")
                    teardown()
            end match
        end if
    end onComplete

    /** Called by writablePromise when socket becomes writable. */
    private[internal] def onWritable()(using AllowUnsafe, Frame): Unit =
        awaitingWritable = false
        val remaining = pendingWrite
        pendingWrite = Span.empty[Byte]
        doWrite(remaining)
    end onWritable

    /** Called by writablePromise on error. */
    private[internal] def onWritableError()(using AllowUnsafe, Frame): Unit =
        awaitingWritable = false
        teardown()
    end onWritableError

    private def doWrite(data: Span[Byte])(using AllowUnsafe, Frame): Unit =
        driver.write(handle, data) match
            case WriteResult.Done =>
                requestNextTake()
            case WriteResult.Partial(remaining) =>
                pendingWrite = remaining
                awaitingWritable = true
                val ready =
                    if !writablePromiseUsed then
                        writablePromiseUsed = true
                        true // First use — promise is already in Pending state
                    else
                        writablePromise.reset()
                if ready then
                    driver.awaitWritable(handle, writablePromise.self)
                else
                    Log.live.unsafe.warn(s"WritePump writablePromise reset failed on ${driver.handleLabel(handle)}")
                    teardown()
                end if
            case WriteResult.Error =>
                Log.live.unsafe.debug(s"WritePump write error on ${driver.handleLabel(handle)}")
                teardown()
        end match
    end doWrite

    private def requestNextTake()(using AllowUnsafe, Frame): Unit =
        if becomeAvailable() then
            channel.reuseTake(selfForTake)
        else
            Log.live.unsafe.warn(s"WritePump becomeAvailable failed on ${driver.handleLabel(handle)}")
            teardown()
        end if
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
        import AllowUnsafe.embrace.danger
        given Frame = Frame.internal
        poll() match
            case Present(Result.Success(_)) =>
                pump.onWritable()
            case Present(Result.Failure(_)) | Present(Result.Panic(_)) | Absent =>
                pump.onWritableError()
        end match
    end onComplete

end WritablePromise
