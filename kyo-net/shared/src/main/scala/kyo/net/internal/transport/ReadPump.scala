package kyo.net.internal.transport

import kyo.*
import kyo.net.NetConnectionClosedException
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
    closeFn: TeardownCause => Unit
) extends IOPromise[Closed, ReadOutcome]:

    // Promise.Unsafe[A, S] is an opaque alias over IOPromise[Any, A < S] (kyo.Fiber.scala): a
    // structurally different type from the invariant IOPromise[Closed, ReadOutcome] this class
    // extends, even though both erase to the same runtime object. The alias is transparent only
    // inside kyo.Fiber.Promise's own defining scope, not here, so the only accessor that recovers a
    // Promise.Unsafe from an IOPromise, Fiber.Promise.Unsafe.fromIOPromise, does not apply: it
    // requires the promise's value type to already be `A < S`, and kyo.scheduler.IOPromise is
    // invariant in that parameter, so IOPromise[Closed, ReadOutcome] does not conform. The cast is
    // the only remaining way to obtain the view, and it is safe since both sides describe the same
    // object.
    private val self: Promise.Unsafe[ReadOutcome, Abort[Closed]] =
        this.asInstanceOf[Promise.Unsafe[ReadOutcome, Abort[Closed]]]

    /** Start the pump. Registers for the first read. */
    def start()(using AllowUnsafe, Frame): Unit =
        driver.awaitRead(handle, self)
    end start

    /** Callback when the driver completes the promise with a typed [[ReadOutcome]]. */
    override protected def onComplete(): Unit =
        // Unsafe: IOPromise.onComplete is the scheduler callback boundary; it runs synchronously off the promise and has no AllowUnsafe in scope.
        // This callback provides AllowUnsafe/Frame for the offerToChannel/requestNextRead calls below.
        import AllowUnsafe.embrace.danger
        given Frame = Frame.internal
        this.poll() match
            case Present(Result.Success(outcome)) =>
                outcome match
                    case ReadOutcome.Bytes(span)   => offerToChannel(span)
                    case ReadOutcome.WouldBlock    => requestNextRead() // EAGAIN: re-arm, NOT EOF
                    case ReadOutcome.PeerFin       => teardown(TeardownCause.PeerFin)
                    case ReadOutcome.LocalShutdown => teardown(TeardownCause.LocalShutdown)
                    case ReadOutcome.CleanClose    => teardown(TeardownCause.CleanClose)
                    case ReadOutcome.Failed(cause) =>
                        Log.live.unsafe.debug(s"ReadPump read failed on ${driver.handleLabel(handle)}")
                        teardown(TeardownCause.Failed(NetConnectionClosedException("read", cause)))
                end match
            case Present(Result.Failure(_)) =>
                teardown(TeardownCause.ChannelClosed)
            case Present(Result.Panic(t)) =>
                Log.live.unsafe.error(s"ReadPump error on ${driver.handleLabel(handle)}", t)
                teardown(TeardownCause.Failed(NetConnectionClosedException("read", t)))
        end match
    end onComplete

    private def offerToChannel(bytes: Span[Byte])(using AllowUnsafe, Frame): Unit =
        val offerResult = channel.offer(bytes)
        offerResult match
            case Result.Success(true) =>
                // Channel accepted, request next read
                requestNextRead()
            case Result.Success(false) =>
                // Channel full: backpressure. Park on the put; when the channel accepts, the callback requests the next read. A STARTTLS upgrade
                // can close the inbound channel while these bytes are still parked in the put (they carry the peer's first TLS flight): on that
                // Closed failure the driver hook salvages them for the handshake instead of losing them (default no-op for an ordinary close).
                val putFiber = channel.putFiber(bytes)
                putFiber.onComplete { result =>
                    // Unsafe: this is the channel putFiber completion callback boundary; it runs off the fiber and has no AllowUnsafe in scope.
                    import AllowUnsafe.embrace.danger
                    given Frame = Frame.internal
                    result match
                        case Result.Success(_)         => requestNextRead()
                        case Result.Failure(_: Closed) => driver.onInboundClosedDuringRead(handle, bytes)
                        case Result.Panic(t)           => teardown(TeardownCause.Failed(NetConnectionClosedException("read", t)))
                    end match
                }
            case Result.Failure(_: Closed) =>
                // The inbound channel is closed: normally these bytes are discarded with the teardown. But a STARTTLS upgrade closes inbound while
                // this read may carry the peer's first TLS flight off the socket; the driver hook salvages those bytes for the handshake instead of
                // losing them (default no-op for an ordinary close). See IoDriver.onInboundClosedDuringRead.
                driver.onInboundClosedDuringRead(handle, bytes)
                teardown(TeardownCause.ChannelClosed)
        end match
    end offerToChannel

    private def requestNextRead()(using AllowUnsafe, Frame): Unit =
        // Always re-arm. A re-arm that races a STARTTLS upgrade on the same handle is intercepted at the driver's read
        // layer, not gated here: on NIO the selector carrier stashes the read into the handle's upgrade salvage while
        // `upgrading && !handshakeReading` (NioIoDriver.dispatchReadPlain), and on the pollers the poll carrier rejects the
        // stray read registration while `upgradeActive && !handshakeReading` (PollerIoDriver). Either way the pump's bytes are
        // salvaged or its promise failed for teardown, so the re-arm can neither steal the read from the handshake nor drop
        // the peer's first TLS flight.
        discard(becomeAvailable())
        driver.awaitRead(handle, self)
    end requestNextRead

    private def teardown(cause: TeardownCause)(using AllowUnsafe, Frame): Unit =
        // Thread the cause to closeFn so the connection records it on its own close-cause state, never dropped.
        // Tear the connection down on EOF/error. closeFn marks the inbound channel closing-for-writes via closeAwaitEmpty (NOT close): a consumer
        // that has not drained the bytes the ReadPump staged ahead of EOF can still take them, then sees the channel FullyClosed once empty, so
        // those bytes are not dropped (the kyo-pod ContainerItTest execStream case: a stderr frame buffered alongside stdout). The handle teardown
        // is intentionally NOT gated on that drain: closeFn reclaims the socket fd as soon as the outbound side flushes, even when the consumer
        // abandons the inbound channel (a pooled connection with no reader). Gating it on the drain leaked the fd: an abandoned inbound channel
        // never empties, so the close never fired and the peer-FIN'd socket lingered in CLOSE_WAIT until process exit.
        closeFn(cause)
    end teardown

end ReadPump
