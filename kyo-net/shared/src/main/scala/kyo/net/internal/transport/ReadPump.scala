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
    closeFn: () => Unit,
    grace: Duration,
    clock: Clock
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
                    case ReadOutcome.PeerFin       => teardown()
                    case ReadOutcome.LocalShutdown => teardown()
                    case ReadOutcome.CleanClose    => teardown()
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
        val offerResult = channel.offer(bytes)
        offerResult match
            case Result.Success(true) =>
                // Channel accepted, request next read
                requestNextRead()
            case Result.Success(false) =>
                // Channel full: backpressure. Park on the put; when the channel accepts (the consumer drained a span), the callback requests the
                // next read. A STARTTLS upgrade can close the inbound channel while these bytes are still parked in the put (they carry the peer's
                // first TLS flight): on that Closed failure the driver hook salvages them for the handshake instead of losing them (a no-op for an
                // ordinary close).
                //
                // While parked no read is armed, so a peer FIN here is otherwise unobservable and an abandoned consumer would leak the socket in
                // CLOSE_WAIT. The BackpressureGrace episode armed below reclaims such a connection on a grace of no progress; see its scaladoc.
                val episode  = new BackpressureGrace
                val putFiber = channel.putFiber(bytes)
                putFiber.onComplete { result =>
                    // Unsafe: this is the channel putFiber completion callback boundary; it runs off the fiber and has no AllowUnsafe in scope.
                    import AllowUnsafe.embrace.danger
                    given Frame = Frame.internal
                    episode.progressed()
                    result match
                        case Result.Success(_)         => requestNextRead()
                        case Result.Failure(_: Closed) => driver.onInboundClosedDuringRead(handle, bytes)
                        case Result.Panic(_)           => teardown()
                    end match
                }
                episode.arm()
            case Result.Failure(_: Closed) =>
                // The inbound channel is closed: normally these bytes are discarded with the teardown. But a STARTTLS upgrade closes inbound while
                // this read may carry the peer's first TLS flight off the socket; the driver hook salvages those bytes for the handshake instead of
                // losing them (default no-op for an ordinary close). See IoDriver.onInboundClosedDuringRead.
                driver.onInboundClosedDuringRead(handle, bytes)
                teardown()
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

    private def teardown()(using AllowUnsafe, Frame): Unit =
        // Tear the connection down on EOF/error. closeFn marks the inbound channel closing-for-writes via closeAwaitEmpty (NOT close): a consumer
        // that has not drained the bytes the ReadPump staged ahead of EOF can still take them, then sees the channel FullyClosed once empty, so
        // those bytes are not dropped (the kyo-pod ContainerItTest execStream case: a stderr frame buffered alongside stdout). The handle teardown
        // is intentionally NOT gated on that drain: closeFn reclaims the socket fd as soon as the outbound side flushes, even when the consumer
        // abandons the inbound channel (a pooled connection with no reader). Gating it on the drain leaked the fd: an abandoned inbound channel
        // never empties, so the close never fired and the peer-FIN'd socket lingered in CLOSE_WAIT until process exit.
        closeFn()
    end teardown

    /** One backpressure episode: the pump is parked on a full inbound channel with no read armed. A grace timer is armed on park; on each expiry
      * it asks the driver whether the peer has closed ([[IoDriver.isPeerClosed]]) and reclaims the connection only when the peer is gone AND no
      * consumer progress happened for a full grace window, re-arming the timer otherwise (the peer is still there, or the consumer is merely idle).
      * The abandonment evidence is the progress silence, confirmed against the peer-close state; slow-but-live consumers are never reclaimed,
      * because any drained span completes the put ([[progressed]]) and settles the episode.
      *
      * The `settled` flag is a one-shot gate: `progressed` (consumer drained / channel closed) and a reclaiming expiry race to win it; the loser
      * no-ops. A FRESH episode is allocated per park, so progress resets the grace for the next park.
      *
      * Teardown always goes through `closeFn`, never `Connection.close()`: a `close()` on an Upgrading connection routes to the upgrade's abandon
      * hook, so a stray grace expiry surviving into a STARTTLS window would kill a live upgrade, whereas `closeFn` is a structural no-op on the
      * Upgrading state (and the detach path fails the parked put Closed, which settles the episode first anyway).
      */
    final private class BackpressureGrace:
        import AllowUnsafe.embrace.danger

        // false until the episode settles, by progress/close ([[progressed]]) or by a reclaiming expiry. The CAS winner acts; a non-reclaiming
        // expiry (peer not yet closed) does NOT settle, it re-arms.
        private val settled = AtomicBoolean.Unsafe.init(false)
        // The current grace timer fiber, published by [[armTimer]] and read by [[progressed]] to interrupt it. @volatile for the cross-carrier read.
        @volatile private var timer: Maybe[Fiber.Unsafe[Unit, Any]] = Absent

        // Interrupt the grace timer fiber. Panic so its onComplete guard (settled) short-circuits rather than reclaiming.
        private def disarm(t: Fiber.Unsafe[Unit, Any])(using Frame): Unit =
            discard(t.interruptDiscard(Result.Panic(Closed("ReadPump", summon[Frame], "grace disarmed by progress"))))

        /** Arm the first grace timer on park. `Duration.Infinity` disables reclamation (no timer). */
        def arm()(using Frame): Unit =
            if grace.isFinite then armTimer()
        end arm

        /** Consumer made progress (the parked put completed) or the channel closed: settle the episode so a running grace timer cannot reclaim,
          * and interrupt it. Idempotent via the gate; a no-op if a reclaiming expiry already fired, in which case the caller's re-arm hits the
          * closing handle and fails Closed, re-entering the connection's own teardown.
          */
        def progressed()(using Frame): Unit =
            if settled.compareAndSet(false, true) then
                timer.foreach(disarm)
        end progressed

        private def armTimer()(using Frame): Unit =
            val t = clock.unsafe.sleep(grace)
            timer = Present(t)
            t.onComplete { _ =>
                import AllowUnsafe.embrace.danger
                given Frame = Frame.internal
                onExpiry()
            }
            // Publish-then-recheck: if progressed() settled the gate between arm and publish, its interrupt saw the previous timer, so cancel this
            // just-armed one rather than let it fire against a connection the consumer is actively draining.
            if settled.get() then disarm(t)
        end armTimer

        private def onExpiry()(using Frame): Unit =
            // Reclaim only if still parked and the peer has actually closed; the CAS gate makes that exactly-once against a concurrent progress. A
            // closed/upgrading handle reads as peer-closed via a local shutdown, but isPeerClosed's isClosing skip and closeFn's idempotent CAS make
            // that at worst a no-op. Otherwise (consumer idle or peer still live) re-arm and keep waiting.
            if !settled.get() then
                if driver.isPeerClosed(handle) then
                    if settled.compareAndSet(false, true) then
                        Log.live.unsafe.debug(
                            s"ReadPump peer-close grace (${grace.show}) elapsed with no progress on a closed peer ${driver.handleLabel(handle)}; reclaiming"
                        )
                        closeFn()
                    end if
                else armTimer()
                end if
        end onExpiry
    end BackpressureGrace

end ReadPump
