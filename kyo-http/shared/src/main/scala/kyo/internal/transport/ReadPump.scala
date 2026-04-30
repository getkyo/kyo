package kyo.internal.transport

import kyo.*
import kyo.scheduler.IOPromise

/** Pump that receives completed reads from the driver and forwards bytes to a channel.
  *
  * ## Lifecycle
  *
  *   1. `start()` registers with driver via `awaitRead`
  *   2. Driver completes this promise with bytes (or EOF/error)
  *   3. `onComplete` fires, offers bytes to channel
  *   4. If channel accepts: re-register with driver
  *   5. If channel full: wait for channel drain, then re-register
  *   6. On EOF or error: close channel
  *
  * ## Backpressure
  *
  * When the inbound channel is full, the pump stops requesting reads from the driver. This causes the kernel's receive buffer to fill,
  * which triggers TCP flow control (stop ACKing, sender slows down). When the channel drains, the pump re-registers for reads.
  *
  * ## Promise reuse
  *
  * The pump extends `IOPromise` and passes itself to `awaitRead`. This avoids per-read allocation. After each completion, `becomeAvailable`
  * resets the promise state for reuse.
  */
final private[kyo] class ReadPump[Handle](
    handle: Handle,
    driver: IoDriver[Handle],
    channel: Channel.Unsafe[Span[Byte]],
    closeFn: () => Unit
) extends IOPromise[Closed, Span[Byte]]:

    private val self: Promise.Unsafe[Span[Byte], Abort[Closed]] =
        this.asInstanceOf[Promise.Unsafe[Span[Byte], Abort[Closed]]]

    /** Start the pump. Registers for the first read. */
    def start()(using AllowUnsafe, Frame): Unit =
        driver.awaitRead(handle, self)
    end start

    /** Callback when driver completes the promise with read result. */
    override protected def onComplete(): Unit =
        import AllowUnsafe.embrace.danger
        given Frame = Frame.internal
        poll() match
            case Present(Result.Success(bytes)) =>
                if bytes.isEmpty then
                    teardown() // EOF
                else
                    offerToChannel(bytes)
            case Present(Result.Failure(_)) =>
                teardown()
            case Present(Result.Panic(t)) =>
                Log.live.unsafe.error(s"ReadPump error on ${driver.handleLabel(handle)}", t)
                teardown()
            case Absent =>
                teardown()
        end match
    end onComplete

    private def offerToChannel(bytes: Span[Byte])(using AllowUnsafe, Frame): Unit =
        channel.offer(bytes) match
            case Result.Success(true) =>
                // Channel accepted, request next read
                requestNextRead()
            case Result.Success(false) =>
                // Channel full — backpressure
                // Wait for channel to accept, then request next read
                channel.putFiber(bytes).onComplete(backpressureCallback)
            case Result.Failure(_: Closed) =>
                // Channel closed
                teardown()
            case Result.Panic(t) =>
                teardown()
        end match
    end offerToChannel

    private val backpressureCallback: Result[Closed, Any] => Unit = result =>
        import AllowUnsafe.embrace.danger
        given Frame = Frame.internal
        result match
            case Result.Success(_)         => requestNextRead()
            case Result.Failure(_: Closed) => () // channel closed, teardown already happened or will happen
            case Result.Panic(_)           => teardown()
        end match

    private def requestNextRead()(using AllowUnsafe, Frame): Unit =
        if becomeAvailable() then
            driver.awaitRead(handle, self)
        else
            teardown()
        end if
    end requestNextRead

    private def teardown()(using AllowUnsafe, Frame): Unit =
        discard(channel.close())
        closeFn()
    end teardown

end ReadPump
