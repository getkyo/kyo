package kyo.scenario

import kyo.*
import kyo.Maybe.Absent
import kyo.Maybe.Present

class WsStyleTest extends JsonRpcTestBase:

    case class CmdReq(cmd: String) derives Schema, CanEqual
    case class CmdResp(result: String) derives Schema, CanEqual
    case class EventMsg(event: String) derives Schema, CanEqual
    case class PingReq(n: Int) derives Schema, CanEqual
    case class PingResp(n: Int) derives Schema, CanEqual

    private class CapturingTransport(inner: JsonRpcTransport) extends JsonRpcTransport:
        // Unsafe: AtomicRef.Unsafe.init for thread-safe envelope accumulation outside effect context
        val sent = AtomicRef.Unsafe.init(List.empty[JsonRpcEnvelope])(using AllowUnsafe.embrace.danger)

        def send(env: JsonRpcEnvelope)(using Frame): Unit < (Async & Abort[Closed]) =
            Sync.defer(discard(sent.getAndUpdate(env :: _)(using AllowUnsafe.embrace.danger))).andThen(inner.send(env))

        def incoming(using Frame): Stream[JsonRpcEnvelope, Async & Abort[Closed]] =
            inner.incoming

        def close(using Frame): Unit < Async =
            inner.close

        def sentList: List[JsonRpcEnvelope] = sent.get()(using AllowUnsafe.embrace.danger).reverse
    end CapturingTransport

    "WebSocket-style: B interleaves unsolicited notifications back to A; A's handler fires without cross-wiring" in run {
        // Unsafe: AtomicRef.Unsafe.init for event accumulation across fibers
        val receivedEvents = AtomicRef.Unsafe.init(List.empty[String])(using AllowUnsafe.embrace.danger)
        // Unsafe: AtomicInt.Unsafe.init as countdown latch (init=2, decrements to 0)
        val notifLatch = AtomicInt.Unsafe.init(2)(using AllowUnsafe.embrace.danger)

        val eventOnA = JsonRpcMethod[EventMsg, Unit, Async & Abort[JsonRpcError]]("server/event") {
            (msg, _) =>
                Sync.defer {
                    discard(receivedEvents.getAndUpdate(msg.event :: _)(using AllowUnsafe.embrace.danger))
                    discard(notifLatch.decrementAndGet()(using AllowUnsafe.embrace.danger))
                }
        }

        val cmdOnB = JsonRpcMethod[CmdReq, CmdResp, Async & Abort[JsonRpcError]]("execute") {
            (req, _) => CmdResp(s"executed:${req.cmd}")
        }

        JsonRpcTransport.inMemory.map { (ta, tb) =>
            JsonRpcEndpoint.init(ta, Seq(eventOnA)).map { endpointA =>
                JsonRpcEndpoint.init(tb, Seq(cmdOnB)).map { endpointB =>
                    endpointA.call[CmdReq, CmdResp]("execute", CmdReq("start")).map { resp1 =>
                        assert(resp1 == CmdResp("executed:start"))
                        endpointB.notify[EventMsg]("server/event", EventMsg("alpha")).andThen {
                            endpointB.notify[EventMsg]("server/event", EventMsg("beta")).andThen {
                                endpointA.call[CmdReq, CmdResp]("execute", CmdReq("stop")).map { resp2 =>
                                    assert(resp2 == CmdResp("executed:stop"))
                                    untilTrue(Sync.defer(notifLatch.get()(using AllowUnsafe.embrace.danger) == 0)).andThen {
                                        Sync.defer {
                                            val events = receivedEvents.get()(using AllowUnsafe.embrace.danger).sorted
                                            assert(events == List("alpha", "beta"), s"expected [alpha, beta], got $events")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    "CDP-shape maxInFlight=8: 9th call parks until one of the first 8 is manually completed" in run {
        // Unsafe: AtomicRef.Unsafe.init for promise accumulation across fibers
        val entryPromises = AtomicRef.Unsafe.init(List.empty[Fiber.Promise[Unit, Any]])(using AllowUnsafe.embrace.danger)
        // Unsafe: AtomicRef.Unsafe.init for first-slot promise capture
        val slot1 = AtomicRef.Unsafe.init[Maybe[Fiber.Promise[Unit, Any]]](Absent)(using AllowUnsafe.embrace.danger)
        // Unsafe: AtomicRef.Unsafe.init for remaining slot promises
        val slotRest = AtomicRef.Unsafe.init(List.empty[Fiber.Promise[Unit, Any]])(using AllowUnsafe.embrace.danger)

        val pingOnB = JsonRpcMethod[PingReq, PingResp, Async & Abort[JsonRpcError]]("ping") { (req, _) =>
            Fiber.Promise.init[Unit, Any].map { entryP =>
                Fiber.Promise.init[Unit, Any].map { holdP =>
                    Sync.defer {
                        if req.n == 1 then slot1.set(Present(holdP))(using AllowUnsafe.embrace.danger)
                        else discard(slotRest.getAndUpdate(holdP :: _)(using AllowUnsafe.embrace.danger))
                        discard(entryPromises.getAndUpdate(entryP :: _)(using AllowUnsafe.embrace.danger))
                        // Unsafe: Promise.Unsafe.completeUnitDiscard from synchronous handler scope; promise constructed locally and signals test entry-latch only.
                        entryP.unsafe.completeUnitDiscard()(using AllowUnsafe.embrace.danger)
                    }.andThen(holdP.get.andThen(PingResp(req.n)))
                }
            }
        }

        val cdpConfig = JsonRpcEndpoint.Config(
            codec = JsonRpcCodec.Cdp,
            cancellation = Absent,
            progress = Absent,
            maxInFlight = Present(8)
        )

        JsonRpcTransport.inMemory.map { (ta, tb) =>
            JsonRpcEndpoint.init(ta, Seq.empty, cdpConfig).map { endpointA =>
                JsonRpcEndpoint.init(tb, Seq(pingOnB), cdpConfig).map { _ =>
                    Kyo.foreach(Chunk.from(1 to 8)) { n =>
                        Fiber.initUnscoped(
                            Abort.run[JsonRpcError | Closed](endpointA.call[PingReq, PingResp]("ping", PingReq(n)))
                        )
                    }.map { _ =>
                        untilTrue(Sync.defer(entryPromises.get()(using AllowUnsafe.embrace.danger).size == 8)).andThen {
                            Fiber.initUnscoped(
                                Abort.run[JsonRpcError | Closed](endpointA.call[PingReq, PingResp]("ping", PingReq(9)))
                            ).map { fib9 =>
                                fib9.done.map { isDone =>
                                    assert(!isDone, "9th handler must not have entered yet")
                                }.andThen {
                                    Sync.defer {
                                        slot1.get()(using AllowUnsafe.embrace.danger) match
                                            case Present(p) =>
                                                // Unsafe: Promise.Unsafe.completeUnitDiscard from synchronous test scope; no parking required and the promise is constructed locally just above.
                                                p.unsafe.completeUnitDiscard()(using AllowUnsafe.embrace.danger)
                                            case Absent => ()
                                    }.andThen {
                                        untilTrue(Sync.defer(entryPromises.get()(using AllowUnsafe.embrace.danger).size == 9)).andThen {
                                            Sync.defer {
                                                slotRest.get()(using AllowUnsafe.embrace.danger).foreach { p =>
                                                    // Unsafe: Promise.Unsafe.completeUnitDiscard from synchronous test scope; no parking required and the promise is constructed locally just above.
                                                    p.unsafe.completeUnitDiscard()(using AllowUnsafe.embrace.danger)
                                                }
                                            }.andThen {
                                                untilTrue(fib9.done).andThen {
                                                    fib9.get.map {
                                                        case Result.Success(r) => assert(r == PingResp(9))
                                                        case other             => fail(s"9th call failed: $other")
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    "CDP-shape extras: ExtrasEncoder.const with sessionId; B receives extras with sessionId at top level" in run {
        // Unsafe: AtomicRef.Unsafe.init for extras capture across fibers
        val capturedExtras = AtomicRef.Unsafe.init[Maybe[Structure.Value]](Absent)(using AllowUnsafe.embrace.danger)

        val cmdOnB = JsonRpcMethod[CmdReq, CmdResp, Async & Abort[JsonRpcError]]("execute") {
            (req, ctx) =>
                Sync.defer(capturedExtras.set(ctx.extras)(using AllowUnsafe.embrace.danger)).andThen(CmdResp(req.cmd))
        }

        val sessionExtras = Structure.Value.Record(Chunk("sessionId" -> Structure.Value.Str("s1")))
        val cdpConfig = JsonRpcEndpoint.Config(
            codec = JsonRpcCodec.Cdp,
            cancellation = Absent,
            progress = Absent
        )

        JsonRpcTransport.inMemory.map { (ta, tb) =>
            JsonRpcEndpoint.init(ta, Seq.empty, cdpConfig).map { endpointA =>
                JsonRpcEndpoint.init(tb, Seq(cmdOnB), cdpConfig).map { _ =>
                    endpointA.call[CmdReq, CmdResp](
                        "execute",
                        CmdReq("doWork"),
                        ExtrasEncoder.const(sessionExtras)
                    ).map { resp =>
                        assert(resp == CmdResp("doWork"))
                        Sync.defer {
                            capturedExtras.get()(using AllowUnsafe.embrace.danger) match
                                case Present(extras) =>
                                    extras match
                                        case Structure.Value.Record(fields) =>
                                            val sessionId = fields.iterator
                                                .collectFirst { case ("sessionId", v) => v }
                                            assert(
                                                sessionId == Some(Structure.Value.Str("s1")),
                                                s"expected sessionId=s1 in extras, got $fields"
                                            )
                                        case other => fail(s"expected Record extras, got $other")
                                case Absent => fail("extras not captured in handler")
                        }
                    }
                }
            }
        }
    }

end WsStyleTest
