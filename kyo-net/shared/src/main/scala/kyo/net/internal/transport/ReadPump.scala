package kyo.net.internal.transport

import kyo.*
import kyo.scheduler.IOPromise

/** Pump that receives completed reads from the driver and forwards bytes to a channel.
  *
  * #### Lifecycle
  *
  *   1. `start()` registers with driver via `awaitRead`
  *   2. Driver completes this promise with bytes (or EOF/error)
  *   3. `onComplete` fires, offers bytes to channel
  *   4. If channel accepts: re-register with driver
  *   5. If channel full: wait for channel drain, then re-register
  *   6. On EOF or error: close channel
  *
  * #### Backpressure
  *
  * When the inbound channel is full, the pump stops requesting reads from the driver. This causes the kernel's receive buffer to fill,
  * which triggers TCP flow control (stop ACKing, sender slows down). When the channel drains, the pump re-registers for reads.
  *
  * #### Promise reuse
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

    private val self: Promise.Unsafe[ReadOutcome, Abort[Closed]] =
        this.asInstanceOf[Promise.Unsafe[ReadOutcome, Abort[Closed]]]

    /** Start the pump. Registers for the first read. */
    def start()(using AllowUnsafe, Frame): Unit =
        driver.awaitRead(handle, self)
    end start

    /** Callback when the driver completes the promise with a typed [[ReadOutcome]]. */
    override protected def onComplete(): Unit =
        // Unsafe: IOPromise.onComplete is the scheduler callback boundary; it runs synchronously off the promise and has no AllowUnsafe in scope.
        // The outer IOPromise type is Span[Byte] (the channel element), but the driver completes with ReadOutcome. The cast makes poll() return
        // the actual runtime type; it is erased-safe since IOPromise type params are not reified.
        import AllowUnsafe.embrace.danger
        given Frame = Frame.internal
        this.asInstanceOf[kyo.scheduler.IOPromise[Closed, ReadOutcome]].poll() match
            case Present(Result.Success(outcome)) =>
                outcome match
                    case ReadOutcome.Bytes(span)   => offerToChannel(span)
                    case ReadOutcome.WouldBlock    => requestNextRead() // EAGAIN: re-arm, NOT EOF
                    case ReadOutcome.PeerFin       => teardown()        // orderly peer EOF
                    case ReadOutcome.LocalShutdown => teardown()        // our own shutdown(SHUT_RD)
                    case ReadOutcome.CleanClose    => teardown()        // TLS close_notify consumed
                    case ReadOutcome.Failed(cause) =>
                        Log.live.unsafe.debug(s"ReadPump read failed on ${driver.handleLabel(handle)}: $cause")
                        teardown()
                end match
            case Present(Result.Failure(_)) =>
                teardown()
            case Present(Result.Panic(t)) =>
                Log.live.unsafe.error(s"ReadPump error on ${driver.handleLabel(handle)}", t)
                teardown()
        end match
    end onComplete

    private def offerToChannel(bytes: Span[Byte])(using AllowUnsafe, Frame): Unit =
        channel.offer(bytes) match
            case Result.Success(true) =>
                // Channel accepted, request next read
                requestNextRead()
            case Result.Success(false) =>
                // Channel full: backpressure. Park on the put; when the channel accepts, the callback requests the next read.
                val putFiber = channel.putFiber(bytes)
                putFiber.onComplete(backpressureCallback)
            case Result.Failure(_: Closed) =>
                // Channel closed
                teardown()
        end match
    end offerToChannel

    private val backpressureCallback: Result[Closed, Any] => Unit = result =>
        // Unsafe: this is the channel putFiber completion callback boundary; it runs off the fiber and has no AllowUnsafe in scope.
        import AllowUnsafe.embrace.danger
        given Frame = Frame.internal
        result match
            case Result.Success(_)         => requestNextRead()
            case Result.Failure(_: Closed) => () // channel closed, teardown already happened or will happen
            case Result.Panic(_)           => teardown()
        end match

    private def requestNextRead()(using AllowUnsafe, Frame): Unit =
        // Always re-arm. A STARTTLS upgrade needs no pump-gating flag: detachForUpgrade (the
        // Established -> Upgrading transition) fails the pump's in-flight read and closes its
        // channels, so the pump tears down rather than re-arming; and on NIO the read-arm owner cell
        // is a per-arm ReadArmCell whose orphan guarantee is object identity: each armRead allocates a
        // fresh ReadArmCell, so even a re-arm that races the handshake holds a distinct cell object and
        // cannot match the handshake's CAS on the current owner slot.
        discard(becomeAvailable())
        driver.awaitRead(handle, self)
    end requestNextRead

    private def teardown()(using AllowUnsafe, Frame): Unit =
        // Tear the connection down on EOF/error. closeFn marks the inbound channel closing-for-writes via closeAwaitEmpty (NOT close): a consumer
        // that has not drained the bytes the ReadPump staged ahead of EOF can still take them, then sees the channel FullyClosed once empty, so
        // those bytes are not dropped (the kyo-pod ContainerItTest execStream case: a stderr frame buffered alongside stdout). The handle teardown
        // is intentionally NOT gated on that drain: closeFn reclaims the socket fd as soon as the outbound side flushes, even when the consumer
        // abandons the inbound channel (a pooled connection with no reader). Gating it on the drain leaked the fd: an abandoned inbound channel
        // never empties, so the close never fired and the peer-FIN'd socket lingered in CLOSE_WAIT until process exit.
        closeFn()
    end teardown

end ReadPump
