package kyo

import kyo.Maybe.Absent
import kyo.Maybe.Present

/** Close semantics of a live handler: what a close writes, what it waits for, and what it tells the handlers it stops. */
class JsonRpcHandlerCloseTest extends JsonRpcTest:

    case class Req(n: Int) derives Schema, CanEqual
    case class Resp(n: Int) derives Schema, CanEqual
    case class Note(text: String) derives Schema, CanEqual

    /** Holds every `send` shut until `gate` is released, recording each envelope as the send is entered. */
    private class GatedTransport(inner: JsonRpcTransport, gate: Latch) extends JsonRpcTransport:
        // Unsafe: AtomicRef.Unsafe.init for thread-safe envelope capture across fibers
        private val entered = AtomicRef.Unsafe.init(Chunk.empty[JsonRpcEnvelope])(using AllowUnsafe.embrace.danger)
        def send(env: JsonRpcEnvelope)(using Frame): Unit < (Async & Abort[Closed | JsonRpcError]) =
            Sync.defer(discard(entered.updateAndGet(_.append(env))(using AllowUnsafe.embrace.danger)))
                .andThen(gate.await)
                .andThen(inner.send(env))
        def incoming(using Frame): Stream[JsonRpcEnvelope, Async & Abort[Closed]] = inner.incoming
        def close(using Frame): Unit < Async                                      = inner.close
        def enteredCount(using Frame): Int < Sync = Sync.defer(entered.get()(using AllowUnsafe.embrace.danger).size)
    end GatedTransport

    "closeNow writes notifications that were accepted before the close" in {
        // `notify` returns once a notification is accepted for delivery. B's outbound sends are gated, so when closeNow
        // runs the first notification is parked inside the transport send and the second is queued behind it. The gate
        // opens only after the close has stopped accepting new notifications, and both must still reach the peer. Each
        // notification runs its own handler fiber on the peer, so the order they reach the route is not asserted.
        for
            gate     <- Latch.init(1)
            arrived  <- Latch.init(2)
            received <- AtomicRef.init(Chunk.empty[Note])
            onNote = JsonRpcRoute.notification[Note]("note") { (note, _) =>
                if note == probe then Kyo.unit
                else received.updateAndGet(_.append(note)).andThen(arrived.release)
            }
            (ta, tb) <- JsonRpcTransport.inMemory
            _        <- JsonRpcHandler.init(ta, onNote)
            gated = new GatedTransport(tb, gate)
            b        <- JsonRpcHandler.initUnscoped(gated, Seq.empty)
            _        <- b.notify("note", Note("first"))
            _        <- b.notify("note", Note("second"))
            closeFib <- Fiber.initUnscoped(b.closeNow)
            _        <- closeStarted(b)
            _        <- gate.release
            _        <- closeFib.get
            _        <- arrived.await
            notes    <- received.get
        yield assert(notes.size == 2 && notes.toSet == Set(Note("first"), Note("second")), s"expected both notifications, got $notes")
    }

    "closeNow writes replies produced before the close" in {
        // Three requests are answered by B while B's outbound sends are gated: the first reply is parked inside the
        // transport send and the others are queued behind it. closeNow runs only after every handler has settled, and the
        // gate opens only after the close has stopped accepting new output, so all three replies were produced before the
        // close and the caller must receive all of them.
        val n = 3
        for
            gate <- Latch.init(1)
            route = JsonRpcRoute.request[Req, Resp]("echo") { (req, _) => Resp(req.n) }
            (ta, tb) <- JsonRpcTransport.inMemory
            a        <- JsonRpcHandler.init(ta, Seq.empty)
            gated = new GatedTransport(tb, gate)
            b <- JsonRpcHandler.initUnscoped(gated, Seq(route))
            impl = b.unsafe.asInstanceOf[internal.engine.JsonRpcEndpointImpl]
            calls <- Fiber.initUnscoped(Async.foreach(1 to n, n)(i => Abort.run[JsonRpcError | Closed](a.call[Req, Resp]("echo", Req(i)))))
            // Barrier: the first reply is parked in the gated send and the other replies are settled behind it.
            _        <- assertEventually(gated.enteredCount.map(_ == 1))
            _        <- assertEventually(Sync.defer(repliesQueued(impl, n - 1)))
            closeFib <- Fiber.initUnscoped(b.closeNow)
            _        <- closeStarted(b)
            _        <- gate.release
            _        <- closeFib.get
            results  <- calls.get
        yield
            val delivered = results.collect { case Result.Success(r) => r }
            assert(delivered.toSet == (1 to n).map(Resp(_)).toSet, s"expected every reply delivered, got $results")
        end for
    }

    "close(gracePeriod) waits for an in-flight inbound handler" in {
        Clock.withTimeControl { control =>
            for
                entered     <- Latch.init(1)
                gate        <- Latch.init(1)
                completed   <- AtomicBoolean.init(false)
                interrupted <- Fiber.Promise.init[Unit, Any]
                route = JsonRpcRoute.request[Req, Resp]("slow") { (req, _) =>
                    Sync.ensure(completed.get.map(done => if done then Kyo.unit else interrupted.completeUnitDiscard)) {
                        entered.release.andThen(gate.await).andThen(completed.set(true)).andThen(Resp(req.n))
                    }
                }
                (ta, tb) <- JsonRpcTransport.inMemory
                a        <- JsonRpcHandler.init(ta, Seq.empty)
                b        <- JsonRpcHandler.initUnscoped(tb, Seq(route))
                callFib  <- Fiber.initUnscoped(Abort.run[JsonRpcError | Closed](a.call[Req, Resp]("slow", Req(7))))
                _        <- entered.await
                closeFib <- Fiber.initUnscoped(b.close(10.seconds))
                // Either close interrupts the running handler without waiting, or it arms its grace timer and waits.
                first <- Async.race(
                    interrupted.get.andThen("interrupted"),
                    control.awaitPendingSleepers(1).andThen("waiting")
                )
                _      <- gate.release
                result <- callFib.get
                _      <- closeFib.get
                done   <- completed.get
            yield
                assert(first == "waiting", "close(gracePeriod) interrupted an in-flight inbound handler instead of waiting for it")
                assert(done, "the handler did not run to completion")
                assert(result == Result.Success(Resp(7)), s"expected the handler's reply, got $result")
        }
    }

    "close completes ctx.cancelled of inbound handlers it interrupts" in {
        for
            entered  <- Latch.init(1)
            captured <- AtomicRef.init[Maybe[Fiber.Promise[Unit, Sync]]](Absent)
            route = JsonRpcRoute.request[Req, Resp]("never") { (_, ctx) =>
                captured.set(Present(ctx.cancelled)).andThen(entered.release).andThen(Async.never[Resp])
            }
            (ta, tb) <- JsonRpcTransport.inMemory
            a        <- JsonRpcHandler.init(ta, Seq.empty)
            b        <- JsonRpcHandler.initUnscoped(tb, Seq(route))
            _        <- Fiber.initUnscoped(Abort.run[JsonRpcError | Closed](a.call[Req, Resp]("never", Req(1))))
            _        <- entered.await
            _        <- b.closeNow
            promise  <- captured.get
            done     <- promise match
                case Present(p) => p.done
                case Absent     => Kyo.lift(false)
        yield assert(done, "closing the handler interrupted the request without completing its ctx.cancelled")
    }

    "a close that lands after a request is registered but before its handler fiber is attached still stops the handler" in {
        // The progress policy's request-token extractor runs while the request's context is built: after the request is registered
        // and before its handler fiber is forked and attached. Running the close of the inbound registry from there, the step a
        // handler close takes, places the close exactly in that window on every run. A close that reaches only fibers already
        // attached would leave this handler running forever and its work counted.
        for
            handlerRef <- AtomicRef.init[Maybe[JsonRpcHandler]](Absent)
            closedAt   <- AtomicBoolean.init(false)
            policy = JsonRpcProgressPolicy(
                progressMethod = "$/progress",
                extractInboundToken = p => JsonRpcProgressPolicy.field(p, "token"),
                extractRequestToken = _ =>
                    handlerRef.get.map {
                        case Present(h) =>
                            // Unsafe: the inbound registry close a handler close performs, run inside the window under test
                            Sync.Unsafe.defer(engine(h).inbound.registry.closeAll()).andThen(closedAt.set(true))
                        case Absent => Kyo.unit
                    }.andThen(Absent),
                stampOutboundToken = (p, _) => p,
                encodeProgressParams = (t, v) => Structure.Value.Record(Chunk("token" -> t, "value" -> v)),
                extractProgressValue = p => JsonRpcProgressPolicy.field(p, "value"),
                enforceMonotonic = false
            )
            route = JsonRpcRoute.request[Req, Resp]("never")((_, _) => Async.never[Resp])
            (ta, tb) <- JsonRpcTransport.inMemory
            b        <- JsonRpcHandler.initUnscoped(tb, Seq(route), JsonRpcHandler.Config(progress = Present(policy)))
            _        <- handlerRef.set(Present(b))
            _        <- Abort.run[Closed](ta.send(JsonRpcRequest(JsonRpcId.Num(1L), "never", Present(Structure.encode(Req(1))), Absent)))
            _        <- assertEventually(closedAt.get)
            // Unsafe: diagnostic reads of the engine's in-flight table and work count, used as a test barrier
            _ <- assertEventually(Sync.Unsafe.defer(registered(engine(b)) == 0 && engine(b).work.count() == 0))
            _ <- b.closeNow
        yield succeed
        end for
    }

    private val probe = Note("probe")

    // Returns once the handler refuses new outbound notifications, which a close does before it writes what is queued.
    // A probe accepted before that point is harmless: the peer route ignores it, and a peer without the route drops it.
    private def closeStarted(handler: JsonRpcHandler)(using Frame, kyo.test.AssertScope): Unit < Async =
        assertEventually(Abort.run[Closed](handler.notify("note", probe)).map(_.isFailure))

    // True once exactly `n` inbound requests have settled with a reply that is waiting to be written.
    private def repliesQueued(impl: internal.engine.JsonRpcEndpointImpl, n: Int): Boolean =
        import AllowUnsafe.embrace.danger
        val entries = impl.inbound.registry.requestsSnapshot
        entries.size == n && entries.forall(_.isReplyPending())
    end repliesQueued

    private def registered(impl: internal.engine.JsonRpcEndpointImpl): Int = impl.inbound.registry.size

    private def engine(handler: JsonRpcHandler): internal.engine.JsonRpcEndpointImpl =
        handler.unsafe.asInstanceOf[internal.engine.JsonRpcEndpointImpl]

end JsonRpcHandlerCloseTest
