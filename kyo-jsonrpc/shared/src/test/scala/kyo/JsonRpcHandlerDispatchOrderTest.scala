package kyo

/** The order in which a handler runs the routes for what one peer sends.
  *
  * A peer's notifications carry state changes whose order matters (an LSP `didChange` edit, a streamed text delta before the
  * `turn/completed` that ends it, a page-to-host message), so they are handled one at a time in the order they arrive, and a request
  * starts only once every notification that arrived before it has been handled. Requests do not wait for each other, and a
  * notification does not wait for a request that arrived before it.
  */
class JsonRpcHandlerDispatchOrderTest extends JsonRpcTest:

    case class Note(n: Int) derives Schema, CanEqual
    case class Probe(tag: String) derives Schema, CanEqual
    case class Seen(value: Boolean) derives Schema, CanEqual
    case class CancelParams(id: JsonRpcId) derives Schema, CanEqual

    /** A cancel that interrupts the peer's handler and expects no reply. */
    private val interruptOnCancel = JsonRpcHandler.Config(cancellation =
        Present(JsonRpcCancellationPolicy(
            cancelMethod = "cancel",
            encodeParams = (id, _) => f ?=> Sync.defer(Structure.encode(CancelParams(id)))(using f),
            decodeParams = sv =>
                f ?=>
                    Sync.defer {
                        Structure.decode[CancelParams](sv)(using summon[Schema[CancelParams]], f) match
                            case Result.Success(p) => Present(p.id)
                            case _                 => Absent
                    }(using f),
            expectReplyForCancelledRequest = false,
            cancelledError = Absent,
            protectedMethods = Set.empty
        ))
    )

    private def mkEndpoints(
        methodsB: Seq[JsonRpcRoute[?, ?, ?]]
    )(using Frame): (JsonRpcHandler, JsonRpcHandler) < (Sync & Async & Scope) =
        JsonRpcTransport.inMemory.map { (ta, tb) =>
            JsonRpcHandler.init(ta, Seq.empty).map { a =>
                JsonRpcHandler.init(tb, methodsB).map(b => (a, b))
            }
        }

    /** Returns once the peer has read every message `a` sent before it: a call to a method the peer does not know is answered
      * directly by the peer's reader, so its reply proves the reader got past everything sent earlier.
      */
    private def flush(a: JsonRpcHandler)(using Frame): Unit < Async =
        Abort.run[JsonRpcError | Closed](a.call[Probe, Seen]("no-such-method", Probe("flush"))).unit

    "notifications reach their handlers in the order they were sent" in {
        val count = 100
        for
            first <- Latch.init(1)
            seen  <- AtomicRef.init(Chunk.empty[Int])
            note = JsonRpcRoute.notification[Note]("note") { (note, _) =>
                (if note.n == 1 then first.await else Kyo.unit).andThen(seen.updateAndGet(_ :+ note.n).unit)
            }
            received <- mkEndpoints(Seq(note)).map { (a, _) =>
                Kyo.foreachDiscard(1 to count)(n => a.notify[Note]("note", Note(n)))
                    .andThen(flush(a))
                    .andThen(first.release)
                    .andThen(assertEventually(seen.get.map(_.size == count)))
                    .andThen(seen.get)
            }
        yield assert(received == Chunk.from(1 to count), s"expected notifications 1 to $count in order, got $received")
        end for
    }

    "a request starts only after the notifications sent before it have been handled" in {
        for
            gate    <- Latch.init(1)
            handled <- AtomicBoolean.init(false)
            note = JsonRpcRoute.notification[Note]("note") { (_, _) =>
                gate.await.andThen(handled.set(true))
            }
            probe = JsonRpcRoute.request[Probe, Seen]("probe") { (_, _) =>
                handled.get.map(Seen(_))
            }
            result <- mkEndpoints(Seq(note, probe)).map { (a, _) =>
                a.notify[Note]("note", Note(1)).andThen {
                    Fiber.initUnscoped(a.call[Probe, Seen]("probe", Probe("after-note"))).map { call =>
                        flush(a).andThen(gate.release).andThen(call.get)
                    }
                }
            }
        yield assert(result == Seen(true), "the request ran before the notification sent ahead of it was handled")
        end for
    }

    "requests run concurrently with each other" in {
        for
            bothStarted <- Latch.init(2)
            slow = JsonRpcRoute.request[Probe, Seen]("slow") { (_, _) =>
                bothStarted.release.andThen(bothStarted.await).andThen(Seen(true))
            }
            results <- mkEndpoints(Seq(slow)).map { (a, _) =>
                Async.zip(
                    a.call[Probe, Seen]("slow", Probe("one")),
                    a.call[Probe, Seen]("slow", Probe("two"))
                )
            }
        yield assert(results == (Seen(true), Seen(true)))
        end for
    }

    "a notification does not wait for a request sent before it" in {
        for
            release <- Latch.init(1)
            noted   <- Latch.init(1)
            slow = JsonRpcRoute.request[Probe, Seen]("slow") { (_, _) =>
                release.await.andThen(Seen(true))
            }
            note = JsonRpcRoute.notification[Note]("note")((_, _) => noted.release)
            result <- mkEndpoints(Seq(slow, note)).map { (a, _) =>
                Fiber.initUnscoped(a.call[Probe, Seen]("slow", Probe("held"))).map { call =>
                    a.notify[Note]("note", Note(1))
                        .andThen(noted.await)
                        .andThen(release.release)
                        .andThen(call.get)
                }
            }
        yield assert(result == Seen(true))
        end for
    }

    "cancelling a request that waits on an earlier notification leaves that notification's handler running" in {
        for
            gate       <- Latch.init(1)
            finished   <- Latch.init(1)
            completed  <- AtomicBoolean.init(false)
            capturedId <- AtomicRef.init[Maybe[JsonRpcId]](Absent)
            note = JsonRpcRoute.notification[Note]("note") { (_, _) =>
                Sync.ensure(finished.release)(gate.await.andThen(completed.set(true)))
            }
            probe = JsonRpcRoute.request[Probe, Seen]("probe")((_, _) => Seen(true))
            outcome <- JsonRpcTransport.inMemory.map { (ta, tb) =>
                JsonRpcHandler.init(ta, Seq.empty, interruptOnCancel).map { a =>
                    JsonRpcHandler.init(tb, Seq(note, probe), interruptOnCancel).map { _ =>
                        val captureId = JsonRpcExtrasEncoder(id => capturedId.set(Present(id)).andThen(Absent: Maybe[Structure.Value]))
                        a.notify[Note]("note", Note(1)).andThen {
                            Fiber.initUnscoped(Abort.run[JsonRpcError | Closed](a.call[Probe, Seen](
                                "probe",
                                Probe("cancelled"),
                                captureId
                            )))
                                .map { call =>
                                    flush(a).andThen(capturedId.get).map {
                                        case Present(id) =>
                                            // The second flush proves the peer has read the cancel, which interrupts the waiting
                                            // request inline, before the notification is let go.
                                            a.cancel(id).andThen(call.getResult).andThen(flush(a)).andThen(gate.release)
                                                .andThen(finished.await).andThen(completed.get)
                                        case Absent => fail("the probe request's id was not captured")
                                    }
                                }
                        }
                    }
                }
            }
        yield assert(outcome, "the notification's handler was interrupted by the cancel of a request waiting on it")
        end for
    }

    "a notification whose handler fails does not stop the ones after it" in {
        for
            seen <- AtomicRef.init(Chunk.empty[Int])
            note = JsonRpcRoute.notification[Note]("note") { (note, _) =>
                if note.n == 1 then Abort.panic(new IllegalStateException("handler for notification 1 failed"))
                else seen.updateAndGet(_ :+ note.n).unit
            }
            received <- mkEndpoints(Seq(note)).map { (a, _) =>
                Kyo.foreachDiscard(1 to 3)(n => a.notify[Note]("note", Note(n)))
                    .andThen(assertEventually(seen.get.map(_.size == 2)))
                    .andThen(seen.get)
            }
        yield assert(received == Chunk(2, 3))
        end for
    }

end JsonRpcHandlerDispatchOrderTest
