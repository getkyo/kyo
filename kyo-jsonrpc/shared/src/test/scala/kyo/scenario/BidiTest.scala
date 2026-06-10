package kyo.scenario

import kyo.*
import kyo.Maybe.Absent
import kyo.Maybe.Present

class BidiTest extends JsonRpcTest:

    case class AddReq(a: Int, b: Int) derives Schema, CanEqual
    case class AddResp(sum: Int) derives Schema, CanEqual
    case class EchoReq(text: String) derives Schema, CanEqual
    case class EchoResp(text: String) derives Schema, CanEqual
    case class WorkReq(name: String) derives Schema, CanEqual
    case class WorkResp(done: Boolean) derives Schema, CanEqual

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

    "both endpoints register methods; simultaneous A.call(B) and B.call(A) resolve without id collision" in {
        val addOnB = JsonRpcRoute.request[AddReq, AddResp]("add") {
            (req, _) => AddResp(req.a + req.b)
        }
        val echoOnA = JsonRpcRoute.request[EchoReq, EchoResp]("echo") {
            (req, _) => EchoResp(req.text.toUpperCase)
        }

        JsonRpcTransport.inMemory.map { (ta, tb) =>
            JsonRpcHandler.init(ta, Seq(echoOnA)).map { endpointA =>
                JsonRpcHandler.init(tb, Seq(addOnB)).map { endpointB =>
                    Async.zip[JsonRpcError | Closed, AddResp, EchoResp, Any](
                        endpointA.call[AddReq, AddResp]("add", AddReq(5, 3)),
                        endpointB.call[EchoReq, EchoResp]("echo", EchoReq("hello"))
                    ).map { (addResult, echoResult) =>
                        assert(addResult == AddResp(8), s"unexpected add result: $addResult")
                        assert(echoResult == EchoResp("HELLO"), s"unexpected echo result: $echoResult")
                    }
                }
            }
        }
    }

    "bidi cancel with expectReply: A cancels call to B; B handler observes cancelled; reply carries -32800; response IS on transport" in {
        // Unsafe: AtomicRef.Unsafe.init for id capture across fibers
        val capturedId = AtomicRef.Unsafe.init[Maybe[JsonRpcId]](Absent)(using AllowUnsafe.embrace.danger)

        val echoOnB = JsonRpcRoute.request[EchoReq, EchoResp]("echo") {
            (req, ctx) =>
                ctx.cancelled.get.andThen(EchoResp(req.text))
        }

        // cancelMethod="$/cancelRequest", expectReply=true
        case class CancelByIdParams(id: JsonRpcId) derives Schema, CanEqual
        val expectReplyConfig = JsonRpcHandler.Config(cancellation =
            Present(JsonRpcCancellationPolicy(
                cancelMethod = "$/cancelRequest",
                encodeParams = (id, _) => f ?=> Sync.defer(Structure.encode(CancelByIdParams(id)))(using f),
                decodeParams = sv =>
                    f ?=>
                        Sync.defer {
                            Structure.decode[CancelByIdParams](sv)(using summon[Schema[CancelByIdParams]], f) match
                                case Result.Success(p) => Present(p.id)
                                case _                 => Absent
                        }(using f),
                expectReplyForCancelledRequest = true,
                cancelledError = Present(JsonRpcCustomError(-32800, "Request cancelled")(using Frame.internal)),
                protectedMethods = Set.empty
            ))
        )

        JsonRpcTransport.inMemory.map { (ta, tb) =>
            val capB = new CapturingTransport(tb)
            JsonRpcHandler.init(ta, Seq.empty, expectReplyConfig).map { endpointA =>
                JsonRpcHandler.init(capB, Seq(echoOnB), expectReplyConfig).map { _ =>
                    val idEncoder =
                        JsonRpcExtrasEncoder(id =>
                            Sync.defer { capturedId.set(Present(id))(using AllowUnsafe.embrace.danger); Absent }
                        )
                    Fiber.initUnscoped(
                        Abort.run[JsonRpcError | Closed](
                            endpointA.call[EchoReq, EchoResp]("echo", EchoReq("test"), idEncoder)
                        )
                    ).map { callFib =>
                        assertEventually(Sync.defer(capturedId.get()(using AllowUnsafe.embrace.danger).isDefined)).andThen {
                            Sync.defer(capturedId.get()(using AllowUnsafe.embrace.danger)).map {
                                case Present(id) =>
                                    endpointA.cancel(id, Absent).andThen {
                                        callFib.get.map {
                                            case Result.Failure(e: JsonRpcError) =>
                                                assert(e.code == -32800, s"expected -32800, got ${e.code}")
                                                assertEventually(Sync.defer {
                                                    capB.sentList.exists {
                                                        case JsonRpcResponse(rid, _, _, _) => rid == id
                                                        case _                             => false
                                                    }
                                                }).andThen(succeed)
                                            case other => fail(s"expected -32800, got $other")
                                        }
                                    }
                                case Absent => fail("id not captured")
                            }
                        }
                    }
                }
            }
        }
    }

    "bidi cancel without expectReply: A cancels call to B; B handler is interrupted; NO response frame on transport" in {
        // Unsafe: AtomicRef.Unsafe.init for id and promise capture across fibers
        val capturedId   = AtomicRef.Unsafe.init[Maybe[JsonRpcId]](Absent)(using AllowUnsafe.embrace.danger)
        val handlerReady = AtomicRef.Unsafe.init[Maybe[Fiber.Promise[Unit, Any]]](Absent)(using AllowUnsafe.embrace.danger)
        val handlerDone  = AtomicRef.Unsafe.init[Maybe[Fiber.Promise[Unit, Any]]](Absent)(using AllowUnsafe.embrace.danger)

        val echoOnB = JsonRpcRoute.request[EchoReq, EchoResp]("echo") {
            (req, _) =>
                Fiber.Promise.init[Unit, Any].map { holdP =>
                    Fiber.Promise.init[Unit, Any].map { doneP =>
                        Sync.defer {
                            handlerReady.set(Present(holdP))(using AllowUnsafe.embrace.danger)
                            handlerDone.set(Present(doneP))(using AllowUnsafe.embrace.danger)
                        }.andThen(
                            Sync.ensure(
                                Sync.defer {
                                    // Unsafe: Promise.Unsafe.completeUnitDiscard from Sync.ensure finalizer; signals test that handler fiber has exited.
                                    doneP.unsafe.completeUnitDiscard()(using AllowUnsafe.embrace.danger)
                                }
                            )(holdP.get.andThen(EchoResp(req.text)))
                        )
                    }
                }
        }

        // cancelMethod="notifications/cancelled", expectReply=false
        case class CancelWithReasonParams(requestId: JsonRpcId, reason: Maybe[String]) derives Schema, CanEqual
        val noReplyConfig = JsonRpcHandler.Config(cancellation =
            Present(JsonRpcCancellationPolicy(
                cancelMethod = "notifications/cancelled",
                encodeParams = (id, reason) => f ?=> Sync.defer(Structure.encode(CancelWithReasonParams(id, reason)))(using f),
                decodeParams = sv =>
                    f ?=>
                        Sync.defer {
                            Structure.decode[CancelWithReasonParams](sv)(using summon[Schema[CancelWithReasonParams]], f) match
                                case Result.Success(p) => Present(p.requestId)
                                case _                 => Absent
                        }(using f),
                expectReplyForCancelledRequest = false,
                cancelledError = Absent,
                protectedMethods = Set("initialize")
            ))
        )

        JsonRpcTransport.inMemory.map { (ta, tb) =>
            val capB = new CapturingTransport(tb)
            JsonRpcHandler.init(ta, Seq.empty, noReplyConfig).map { endpointA =>
                JsonRpcHandler.init(capB, Seq(echoOnB), noReplyConfig).map { _ =>
                    val idEncoder =
                        JsonRpcExtrasEncoder(id =>
                            Sync.defer { capturedId.set(Present(id))(using AllowUnsafe.embrace.danger); Absent }
                        )
                    Fiber.initUnscoped(
                        Abort.run[JsonRpcError | Closed](
                            endpointA.call[EchoReq, EchoResp]("echo", EchoReq("cancel-test"), idEncoder)
                        )
                    ).map { callFib =>
                        assertEventually(Sync.defer(capturedId.get()(using AllowUnsafe.embrace.danger).isDefined && handlerReady.get()(using
                            AllowUnsafe.embrace.danger
                        ).isDefined)).andThen {
                            Sync.defer(capturedId.get()(using AllowUnsafe.embrace.danger)).map {
                                case Present(id) =>
                                    endpointA.cancel(id, Absent).andThen {
                                        callFib.get.andThen {
                                            assertEventually(Sync.defer(handlerDone.get()(using
                                                AllowUnsafe.embrace.danger
                                            ).isDefined)).andThen {
                                                Sync.defer(handlerDone.get()(using AllowUnsafe.embrace.danger)).map {
                                                    case Present(doneP) =>
                                                        assertEventually(doneP.done).andThen {
                                                            Sync.defer {
                                                                val noReply = capB.sentList.forall {
                                                                    case JsonRpcResponse(rid, _, _, _) => rid != id
                                                                    case _                             => true
                                                                }
                                                                assert(
                                                                    noReply,
                                                                    "cancel without expectReply must produce no response frame on transport"
                                                                )
                                                            }
                                                        }
                                                    case Absent => fail("handlerDone promise not set")
                                                }
                                            }
                                        }
                                    }
                                case Absent => fail("id not captured")
                            }
                        }
                    }
                }
            }
        }
    }

    "progress round-trip via callWithProgress: handler emits 3 values; caller observes them; result arrives" in {
        // Unsafe: AtomicRef.Unsafe.init for progress value accumulation across fibers
        val progressValues = AtomicRef.Unsafe.init(List.empty[Structure.Value])(using AllowUnsafe.embrace.danger)

        val workOnB = JsonRpcRoute.request[WorkReq, WorkResp]("work") {
            (_, ctx) =>
                val v1 = Structure.Value.Record(Chunk("kind" -> Structure.Value.Str("begin")))
                val v2 = Structure.Value.Record(Chunk("kind" -> Structure.Value.Str("report"), "message" -> Structure.Value.Str("halfway")))
                val v3 = Structure.Value.Record(Chunk("kind" -> Structure.Value.Str("end")))
                Abort.run[Closed](ctx.progress(v1)).andThen {
                    Abort.run[Closed](ctx.progress(v2)).andThen {
                        Abort.run[Closed](ctx.progress(v3)).andThen {
                            WorkResp(true)
                        }
                    }
                }
        }

        // progressMethod="$/progress", workDoneToken style, non-monotonic
        val expectReplyConfig = JsonRpcHandler.Config(progress =
            Present(JsonRpcProgressPolicy(
                progressMethod = "$/progress",
                extractInboundToken = p => JsonRpcProgressPolicy.field(p, "token"),
                extractRequestToken = p => JsonRpcProgressPolicy.field(p, "workDoneToken"),
                stampOutboundToken = (p, t) => JsonRpcProgressPolicy.merge(p, Structure.Value.Record(Chunk("workDoneToken" -> t))),
                encodeProgressParams = (t, v) => Structure.Value.Record(Chunk("token" -> t, "value" -> v)),
                extractProgressValue = p => JsonRpcProgressPolicy.field(p, "value"),
                enforceMonotonic = false
            ))
        )

        JsonRpcTransport.inMemory.map { (ta, tb) =>
            JsonRpcHandler.init(ta, Seq.empty, expectReplyConfig).map { endpointA =>
                JsonRpcHandler.init(tb, Seq(workOnB), expectReplyConfig).map { _ =>
                    endpointA.callWithProgress[WorkReq, WorkResp]("work", WorkReq("task1")).map { pending =>
                        pending.progress.run.map { collected =>
                            pending.result.map { finalResp =>
                                assert(finalResp == WorkResp(true), s"unexpected result: $finalResp")
                                assert(collected.size == 3, s"expected 3 progress values, got ${collected.size}")
                            }
                        }
                    }
                }
            }
        }
    }

end BidiTest
