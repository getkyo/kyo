package kyo

import kyo.Maybe.Absent
import kyo.Maybe.Present

class JsonRpcHandlerCancellationPolicyTest extends JsonRpcTest:

    case class EchoReq(text: String) derives Schema, CanEqual
    case class EchoResp(text: String) derives Schema, CanEqual

    // Inline reconstruction of the cancellation policies (expectReplyForCancelledRequest=true variant).
    private case class CancelByIdParams(id: JsonRpcId) derives Schema, CanEqual
    private case class CancelWithReasonParams(requestId: JsonRpcId, reason: Maybe[String]) derives Schema, CanEqual

    private val cancelByIdEncoder: JsonRpcCancellationPolicy.ParamsEncoder =
        (id, _) =>
            f ?=>
                Sync.defer(Structure.encode(CancelByIdParams(id)))(using f)

    private val cancelByIdDecoder: JsonRpcCancellationPolicy.ParamsDecoder =
        sv =>
            f ?=>
                Sync.defer {
                    Structure.decode[CancelByIdParams](sv)(using summon[Schema[CancelByIdParams]], f) match
                        case Result.Success(p) => Present(p.id)
                        case _                 => Absent
                }(using f)

    private val cancelWithReasonEncoder: JsonRpcCancellationPolicy.ParamsEncoder =
        (id, reason) =>
            f ?=>
                Sync.defer(Structure.encode(CancelWithReasonParams(id, reason)))(using f)

    private val cancelWithReasonDecoder: JsonRpcCancellationPolicy.ParamsDecoder =
        sv =>
            f ?=>
                Sync.defer {
                    Structure.decode[CancelWithReasonParams](sv)(using summon[Schema[CancelWithReasonParams]], f) match
                        case Result.Success(p) => Present(p.requestId)
                        case _                 => Absent
                }(using f)

    // cancelMethod="$/cancelRequest", expectReply=true (server still replies after cancel)
    private val cancellationWithReply = JsonRpcCancellationPolicy(
        cancelMethod = "$/cancelRequest",
        encodeParams = cancelByIdEncoder,
        decodeParams = cancelByIdDecoder,
        expectReplyForCancelledRequest = true,
        cancelledError = Present(JsonRpcCustomError(-32800, "Request cancelled")(using Frame.internal)),
        protectedMethods = Set.empty
    )

    // cancelMethod="notifications/cancelled", expectReply=false (handler interrupted, no reply)
    private val cancellationWithoutReply = JsonRpcCancellationPolicy(
        cancelMethod = "notifications/cancelled",
        encodeParams = cancelWithReasonEncoder,
        decodeParams = cancelWithReasonDecoder,
        expectReplyForCancelledRequest = false,
        cancelledError = Absent,
        protectedMethods = Set("initialize")
    )

    private val expectReplyConfig = JsonRpcHandler.Config(cancellation = Present(cancellationWithReply))
    private val noReplyConfig     = JsonRpcHandler.Config(cancellation = Present(cancellationWithoutReply))
    private val noPolicy          = JsonRpcHandler.Config(cancellation = Absent)

    private class CapturingTransport(inner: JsonRpcTransport) extends JsonRpcTransport:
        // Unsafe: AtomicRef.Unsafe.init for thread-safe envelope accumulation outside effect context
        val sent = AtomicRef.Unsafe.init(List.empty[JsonRpcEnvelope])(using AllowUnsafe.embrace.danger)

        def send(env: JsonRpcEnvelope)(using Frame): Unit < (Async & Abort[Closed | JsonRpcError]) =
            Sync.defer(discard(sent.getAndUpdate(env :: _)(using AllowUnsafe.embrace.danger))).andThen(inner.send(env))

        def incoming(using Frame): Stream[JsonRpcEnvelope, Async & Abort[Closed]] =
            inner.incoming

        def close(using Frame): Unit < Async =
            inner.close

        def sentList: List[JsonRpcEnvelope] = sent.get()(using AllowUnsafe.embrace.danger).reverse
    end CapturingTransport

    // GatedTransport holds responses until gateP is completed, then forwards them.
    // gateP acts as a latch (replaces a one-shot countdown latch): complete it to open the gate.
    private class GatedTransport(
        inner: JsonRpcTransport,
        gateP: Fiber.Promise[Unit, Abort[Closed]]
    ) extends JsonRpcTransport:
        // Unsafe: AtomicRef.Unsafe.init for thread-safe envelope accumulation outside effect context
        val sent = AtomicRef.Unsafe.init(List.empty[JsonRpcEnvelope])(using AllowUnsafe.embrace.danger)

        def send(env: JsonRpcEnvelope)(using Frame): Unit < (Async & Abort[Closed | JsonRpcError]) =
            env match
                case _: JsonRpcResponse =>
                    Sync.defer(discard(sent.getAndUpdate(env :: _)(using AllowUnsafe.embrace.danger))).andThen {
                        gateP.get.andThen(inner.send(env))
                    }
                case _ =>
                    Sync.defer(discard(sent.getAndUpdate(env :: _)(using AllowUnsafe.embrace.danger))).andThen(inner.send(env))

        def incoming(using Frame): Stream[JsonRpcEnvelope, Async & Abort[Closed]] =
            inner.incoming

        def close(using Frame): Unit < Async =
            inner.close

        def sentList: List[JsonRpcEnvelope] = sent.get()(using AllowUnsafe.embrace.danger).reverse
    end GatedTransport

    "cancellation with expectReply: handler observes cancelled and caller gets -32800" in {
        // Unsafe: AtomicRef.Unsafe.init for id capture across fibers
        val capturedId = AtomicRef.Unsafe.init[Maybe[JsonRpcId]](Absent)(using AllowUnsafe.embrace.danger)
        val echoOnB    = JsonRpcRoute.request[EchoReq, EchoResp]("echo") {
            (req, ctx) =>
                ctx.cancelled.get.andThen(EchoResp(req.text))
        }
        JsonRpcTransport.inMemory.map { (ta, tb) =>
            val capA = new CapturingTransport(ta)
            JsonRpcHandler.init(capA, Seq.empty, expectReplyConfig).map { endpointA =>
                JsonRpcHandler.init(tb, Seq(echoOnB), expectReplyConfig).map { _ =>
                    val idEncoder = JsonRpcExtrasEncoder(id =>
                        Sync.defer { capturedId.set(Present(id))(using AllowUnsafe.embrace.danger); Absent }
                    )
                    Fiber.initUnscoped(
                        Abort.run[JsonRpcError | Closed](
                            endpointA.call[EchoReq, EchoResp]("echo", EchoReq("hello"), idEncoder)
                        )
                    ).map { callFib =>
                        assertEventually(Sync.defer(capturedId.get()(using AllowUnsafe.embrace.danger).isDefined)).andThen {
                            Sync.defer(capturedId.get()(using AllowUnsafe.embrace.danger)).map {
                                case Present(id) =>
                                    endpointA.cancel(id, Absent).andThen {
                                        callFib.get.map {
                                            case Result.Failure(e: JsonRpcError) =>
                                                assert(e.code == -32800, s"expected -32800, got ${e.code}")
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

    "cancellation with expectReply: a reply IS still sent on the transport" in {
        // Unsafe: AtomicRef.Unsafe.init for id capture across fibers
        val capturedId = AtomicRef.Unsafe.init[Maybe[JsonRpcId]](Absent)(using AllowUnsafe.embrace.danger)
        val echoOnB    = JsonRpcRoute.request[EchoReq, EchoResp]("echo") {
            (req, ctx) =>
                ctx.cancelled.get.andThen(EchoResp(req.text))
        }
        JsonRpcTransport.inMemory.map { (ta, tb) =>
            val capB = new CapturingTransport(tb)
            JsonRpcHandler.init(ta, Seq.empty, expectReplyConfig).map { endpointA =>
                JsonRpcHandler.init(capB, Seq(echoOnB), expectReplyConfig).map { _ =>
                    val idEncoder = JsonRpcExtrasEncoder(id =>
                        Sync.defer { capturedId.set(Present(id))(using AllowUnsafe.embrace.danger); Absent }
                    )
                    Fiber.initUnscoped(
                        Abort.run[JsonRpcError | Closed](
                            endpointA.call[EchoReq, EchoResp]("echo", EchoReq("hello"), idEncoder)
                        )
                    ).map { callFib =>
                        assertEventually(Sync.defer(capturedId.get()(using AllowUnsafe.embrace.danger).isDefined)).andThen {
                            Sync.defer(capturedId.get()(using AllowUnsafe.embrace.danger)).map {
                                case Present(id) =>
                                    endpointA.cancel(id, Absent).andThen {
                                        callFib.get.andThen {
                                            assertEventually(Sync.defer {
                                                capB.sentList.exists {
                                                    case JsonRpcResponse(rid, _, _, _) => rid == id
                                                    case _                             => false
                                                }
                                            }).andThen(succeed)
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

    "cancellation without expectReply: no reply is sent on the transport" in {
        // Unsafe: AtomicRef.Unsafe.init for id capture across fibers
        val capturedId = AtomicRef.Unsafe.init[Maybe[JsonRpcId]](Absent)(using AllowUnsafe.embrace.danger)
        // handlerProceeded fires once B's handler fiber has settled after the cancel, the point after which
        // (expectReply=false) the engine sends no reply. Awaiting it is a deterministic latch for "the cancel
        // reached the suppression path", replacing a fixed sleep before asserting no reply was written.
        // handlerEntered gates the cancel on the handler running inside that finalizer: a cancel that lands
        // before the handler body starts interrupts the fiber before the finalizer is registered, so
        // handlerProceeded would never fire.
        Latch.init(1).map { handlerEntered =>
            Fiber.Promise.init[Unit, Abort[Closed]].map { handlerProceeded =>
                val echoOnB = JsonRpcRoute.request[EchoReq, EchoResp]("echo") {
                    (_, ctx) =>
                        // A no-expectReply cancel interrupts the handler, so completing the latch after
                        // ctx.cancelled.get would never run. Complete it in an ensure finalizer, which fires on
                        // both normal completion and interruption; the latch then signals "the handler fiber has
                        // settled", after which the engine sends no reply.
                        Sync.ensure(handlerProceeded.completeUnitDiscard) {
                            handlerEntered.release.andThen(ctx.cancelled.get).andThen(EchoResp("done"))
                        }
                }
                JsonRpcTransport.inMemory.map { (ta, tb) =>
                    val capB = new CapturingTransport(tb)
                    JsonRpcHandler.init(ta, Seq.empty, noReplyConfig).map { endpointA =>
                        JsonRpcHandler.init(capB, Seq(echoOnB), noReplyConfig).map { _ =>
                            val idEncoder = JsonRpcExtrasEncoder(id =>
                                Sync.defer { capturedId.set(Present(id))(using AllowUnsafe.embrace.danger); Absent }
                            )
                            Fiber.initUnscoped(
                                Abort.run[JsonRpcError | Closed](
                                    endpointA.call[EchoReq, EchoResp]("echo", EchoReq("x"), idEncoder)
                                )
                            ).map { callFib =>
                                handlerEntered.await.andThen {
                                    Sync.defer(capturedId.get()(using AllowUnsafe.embrace.danger)).map {
                                        case Present(id) =>
                                            endpointA.cancel(id, Absent).andThen {
                                                callFib.get.andThen(handlerProceeded.get).andThen {
                                                    Sync.defer {
                                                        val noReply = capB.sentList.forall {
                                                            case JsonRpcResponse(rid, _, _, _) => rid != id
                                                            case _                             => true
                                                        }
                                                        assert(
                                                            noReply,
                                                            "policy without expectReply should not send a reply for cancelled request"
                                                        )
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
        }
    }

    "cancellation without expectReply race: cancel while reply queued in writer channel suppresses the reply" in {
        // Unsafe: AtomicRef.Unsafe.init for id capture across fibers
        val capturedId = AtomicRef.Unsafe.init[Maybe[JsonRpcId]](Absent)(using AllowUnsafe.embrace.danger)
        // Unsafe: Fiber.Promise used as a gate (replaces CountDownLatch(1));
        // complete it with completeUnitDiscard to open the gate
        Fiber.Promise.init[Unit, Abort[Closed]].map { sendGate =>
            JsonRpcTransport.inMemory.map { (ta, tb) =>
                val gatedTb = new GatedTransport(tb, sendGate)
                val echoOnB = JsonRpcRoute.request[EchoReq, EchoResp]("echo") {
                    (req, _) => EchoResp(req.text)
                }
                JsonRpcHandler.init(ta, Seq.empty, noReplyConfig).map { endpointA =>
                    JsonRpcHandler.init(gatedTb, Seq(echoOnB), noReplyConfig).map { _ =>
                        val idEncoder = JsonRpcExtrasEncoder(id =>
                            Sync.defer { capturedId.set(Present(id))(using AllowUnsafe.embrace.danger); Absent }
                        )
                        Fiber.initUnscoped(
                            Abort.run[JsonRpcError | Closed](
                                endpointA.call[EchoReq, EchoResp]("echo", EchoReq("x"), idEncoder)
                            )
                        ).map { callFib =>
                            assertEventually(Sync.defer(capturedId.get()(using AllowUnsafe.embrace.danger).isDefined)).andThen {
                                Sync.defer(capturedId.get()(using AllowUnsafe.embrace.danger)).map {
                                    case Present(id) =>
                                        // Wait for response to reach GatedTransport.send (before the gate blocks it)
                                        assertEventually(Sync.defer {
                                            gatedTb.sentList.exists {
                                                case JsonRpcResponse(rid, _, _, _) => rid == id
                                                case _                             => false
                                            }
                                        }).andThen {
                                            // Fire cancel while reply is stuck behind the gate
                                            endpointA.cancel(id, Absent).andThen {
                                                // Open gate: writer now checks suppress flag; should drop the reply
                                                sendGate.completeUnitDiscard.andThen {
                                                    // The cancel fires the call's abortSignal, so awaiting the call
                                                    // fiber resolves deterministically; it must resolve to a failure.
                                                    callFib.get.map { result =>
                                                        assert(result.isFailure, "call should fail after cancel")
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
    }

    "cancellation with expectReply: sends $/cancelRequest notification and call fails with -32800" in {
        // Unsafe: AtomicRef.Unsafe.init for id capture across fibers
        val capturedId = AtomicRef.Unsafe.init[Maybe[JsonRpcId]](Absent)(using AllowUnsafe.embrace.danger)
        val echoOnB    = JsonRpcRoute.request[EchoReq, EchoResp]("echo") {
            (_, ctx) => ctx.cancelled.get.andThen(EchoResp("cancelled"))
        }
        JsonRpcTransport.inMemory.map { (ta, tb) =>
            val capA = new CapturingTransport(ta)
            JsonRpcHandler.init(capA, Seq.empty, expectReplyConfig).map { endpointA =>
                JsonRpcHandler.init(tb, Seq(echoOnB), expectReplyConfig).map { _ =>
                    val idEncoder = JsonRpcExtrasEncoder(id =>
                        Sync.defer { capturedId.set(Present(id))(using AllowUnsafe.embrace.danger); Absent }
                    )
                    Fiber.initUnscoped(
                        Abort.run[JsonRpcError | Closed](
                            endpointA.call[EchoReq, EchoResp]("echo", EchoReq("hello"), idEncoder)
                        )
                    ).map { callFib =>
                        assertEventually(Sync.defer(capturedId.get()(using AllowUnsafe.embrace.danger).isDefined)).andThen {
                            Sync.defer(capturedId.get()(using AllowUnsafe.embrace.danger)).map {
                                case Present(id) =>
                                    endpointA.cancel(id, Absent).andThen {
                                        callFib.get.map {
                                            case Result.Failure(e: JsonRpcError) =>
                                                assert(e.code == -32800)
                                                assertEventually(Sync.defer {
                                                    capA.sentList.exists {
                                                        case JsonRpcNotification(m, _, _) => m == "$/cancelRequest"
                                                        case _                            => false
                                                    }
                                                })
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

    "cancellation without expectReply: sends notifications/cancelled with requestId and reason, call fails" in {
        // Unsafe: AtomicRef.Unsafe.init for id capture across fibers
        val capturedId = AtomicRef.Unsafe.init[Maybe[JsonRpcId]](Absent)(using AllowUnsafe.embrace.danger)
        val echoOnB    = JsonRpcRoute.request[EchoReq, EchoResp]("echo") {
            (_, ctx) => ctx.cancelled.get.andThen(EchoResp("cancelled"))
        }
        JsonRpcTransport.inMemory.map { (ta, tb) =>
            val capA = new CapturingTransport(ta)
            JsonRpcHandler.init(capA, Seq.empty, noReplyConfig).map { endpointA =>
                JsonRpcHandler.init(tb, Seq(echoOnB), noReplyConfig).map { _ =>
                    val idEncoder = JsonRpcExtrasEncoder(id =>
                        Sync.defer { capturedId.set(Present(id))(using AllowUnsafe.embrace.danger); Absent }
                    )
                    Fiber.initUnscoped(
                        Abort.run[JsonRpcError | Closed](
                            endpointA.call[EchoReq, EchoResp]("echo", EchoReq("x"), idEncoder)
                        )
                    ).map { callFib =>
                        assertEventually(Sync.defer(capturedId.get()(using AllowUnsafe.embrace.danger).isDefined)).andThen {
                            Sync.defer(capturedId.get()(using AllowUnsafe.embrace.danger)).map {
                                case Present(id) =>
                                    endpointA.cancel(id, Present("user requested")).andThen {
                                        callFib.get.map {
                                            case Result.Failure(e: JsonRpcError) =>
                                                assertEventually(Sync.defer {
                                                    capA.sentList.exists {
                                                        case JsonRpcNotification(m, _, _) => m == "notifications/cancelled"
                                                        case _                            => false
                                                    }
                                                })
                                            case other => fail(s"expected failure, got $other")
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

    "cancel for protected method sends no notification and does not abort call" in {
        // Unsafe: AtomicRef.Unsafe.init for id capture across fibers
        val capturedId = AtomicRef.Unsafe.init[Maybe[JsonRpcId]](Absent)(using AllowUnsafe.embrace.danger)
        val initOnB    = JsonRpcRoute.request[EchoReq, EchoResp]("initialize") {
            (req, ctx) => ctx.cancelled.get.andThen(EchoResp("initialized"))
        }
        JsonRpcTransport.inMemory.map { (ta, tb) =>
            val capA = new CapturingTransport(ta)
            JsonRpcHandler.init(capA, Seq.empty, noReplyConfig).map { endpointA =>
                JsonRpcHandler.init(tb, Seq(initOnB), noReplyConfig).map { _ =>
                    val idEncoder = JsonRpcExtrasEncoder(id =>
                        Sync.defer { capturedId.set(Present(id))(using AllowUnsafe.embrace.danger); Absent }
                    )
                    Fiber.initUnscoped(
                        Abort.run[JsonRpcError | Closed](
                            endpointA.call[EchoReq, EchoResp]("initialize", EchoReq("init"), idEncoder)
                        )
                    ).map { callFib =>
                        assertEventually(Sync.defer(capturedId.get()(using AllowUnsafe.embrace.danger).isDefined)).andThen {
                            Sync.defer(capturedId.get()(using AllowUnsafe.embrace.danger)).map {
                                case Present(id) =>
                                    // cancel refuses a protected method synchronously (a logged no-op that enqueues
                                    // nothing), so once endpointA.cancel returns the decision is final: assert no
                                    // cancel notification was sent and the handler (blocked on its never-firing
                                    // cancelled latch) leaves the call pending. No sleep is needed.
                                    endpointA.cancel(id, Absent).andThen {
                                        Sync.defer {
                                            val noCancelSent = capA.sentList.forall {
                                                case JsonRpcNotification(m, _, _) => m != "notifications/cancelled"
                                                case _                            => true
                                            }
                                            assert(noCancelSent, "cancel notification should not be sent for protected method")
                                        }.andThen {
                                            callFib.done.map { isDone =>
                                                assert(!isDone, "call should still be pending after refused cancel")
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

    "cancel for already-completed call returns unit without sending a cancel notification" in {
        // Unsafe: AtomicRef.Unsafe.init for id capture across fibers
        val capturedId = AtomicRef.Unsafe.init[Maybe[JsonRpcId]](Absent)(using AllowUnsafe.embrace.danger)
        val echoOnB    = JsonRpcRoute.request[EchoReq, EchoResp]("echo") {
            (req, _) => EchoResp(req.text)
        }
        JsonRpcTransport.inMemory.map { (ta, tb) =>
            val capA = new CapturingTransport(ta)
            JsonRpcHandler.init(capA, Seq.empty, expectReplyConfig).map { endpointA =>
                JsonRpcHandler.init(tb, Seq(echoOnB), expectReplyConfig).map { _ =>
                    val idEncoder = JsonRpcExtrasEncoder(id =>
                        Sync.defer { capturedId.set(Present(id))(using AllowUnsafe.embrace.danger); Absent }
                    )
                    endpointA.call[EchoReq, EchoResp]("echo", EchoReq("hello"), idEncoder).andThen {
                        endpointA.awaitDrain.andThen {
                            Sync.defer(capturedId.get()(using AllowUnsafe.embrace.danger)).map {
                                case Present(id) =>
                                    val countBefore = Sync.defer {
                                        capA.sentList.count {
                                            case _: JsonRpcNotification => true
                                            case _                      => false
                                        }
                                    }
                                    countBefore.map { before =>
                                        endpointA.cancel(id, Absent).andThen {
                                            Sync.defer {
                                                val after = capA.sentList.count {
                                                    case _: JsonRpcNotification => true
                                                    case _                      => false
                                                }
                                                assert(after == before, "cancel for completed call should not send notification")
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

    "inbound cancel for absent handler id is silently dropped without error" in {
        val echoOnB = JsonRpcRoute.request[EchoReq, EchoResp]("echo") {
            (req, _) => EchoResp(req.text)
        }
        JsonRpcTransport.inMemory.map { (ta, tb) =>
            JsonRpcHandler.init(ta, Seq.empty, noReplyConfig).map { endpointA =>
                JsonRpcHandler.init(tb, Seq(echoOnB), noReplyConfig).map { _ =>
                    // Inject a cancel notification for a non-existent id directly into B's incoming stream
                    val fakeId      = JsonRpcId.Num(99999L)
                    val cancelNotif = JsonRpcNotification(
                        "notifications/cancelled",
                        Present(Structure.Value.Record(Chunk(
                            "requestId" -> Structure.encode(fakeId),
                            "reason"    -> Structure.Value.Null
                        ))),
                        Absent
                    )
                    Abort.run[Closed](ta.send(cancelNotif)).andThen {
                        // The echo call is the sentinel: B's single reader processes the injected cancel for the
                        // absent id before this later request (FIFO). A correct echo response proves the cancel was
                        // silently dropped and B stayed healthy, with no sleep needed.
                        endpointA.call[EchoReq, EchoResp]("echo", EchoReq("still alive")).map { resp =>
                            assert(resp == EchoResp("still alive"))
                        }
                    }
                }
            }
        }
    }

    "timeout with expectReply policy sends $/cancelRequest and caller fails with -32800" in {
        // Driven on a controlled clock: the timeout fires only once the request has reached the handler (so there is an issued
        // request to cancel) and the call has armed its requestTimeout. The writer fiber writes the cancel notification after
        // the timeout path enqueues it, so its arrival is awaited through a promise the transport completes, not by polling.
        val entered      = Fiber.Promise.Unsafe.init[Unit, Any]()(using AllowUnsafe.embrace.danger).safe
        val neverReturns = JsonRpcRoute.request[EchoReq, EchoResp]("echo") {
            (_, ctx) => entered.completeUnitDiscard.andThen(ctx.cancelled.get).andThen(EchoResp("cancelled"))
        }
        Fiber.Promise.init[Unit, Any].map { cancelSent =>
            Clock.withTimeControl { control =>
                JsonRpcTransport.inMemory.map { (ta, tb) =>
                    val capA = new CapturingTransport(ta):
                        override def send(env: JsonRpcEnvelope)(using Frame): Unit < (Async & Abort[Closed | JsonRpcError]) =
                            super.send(env).andThen {
                                env match
                                    case JsonRpcNotification("$/cancelRequest", _, _) => cancelSent.completeUnitDiscard
                                    case _                                            => Kyo.unit
                            }
                    val timeoutExpectReplyConfig = JsonRpcHandler.Config(
                        cancellation = Present(cancellationWithReply),
                        requestTimeout = 150.millis
                    )
                    JsonRpcHandler.init(capA, Seq.empty, timeoutExpectReplyConfig).map { endpointA =>
                        JsonRpcHandler.init(tb, Seq(neverReturns), expectReplyConfig).map { _ =>
                            Fiber.initUnscoped(
                                Abort.run[JsonRpcError | Closed](endpointA.call[EchoReq, EchoResp]("echo", EchoReq("x")))
                            ).map { callFib =>
                                entered.get.andThen(control.awaitPendingSleepers(1)).andThen(control.advance(300.millis)).andThen {
                                    callFib.get.map {
                                        case Result.Failure(e: JsonRpcError) =>
                                            assert(e.code == -32800)
                                            cancelSent.get.andThen {
                                                Sync.defer {
                                                    assert(capA.sentList.exists {
                                                        case JsonRpcNotification(m, _, _) => m == "$/cancelRequest"
                                                        case _                            => false
                                                    })
                                                }
                                            }
                                        case other => fail(s"expected -32800, got $other")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    "timeout with cancellation=Absent sends no cancel notification; call fails locally" in {
        // The handler blocks on a never-completed gate so the requestTimeout is the only exit. Clock.withTimeControl
        // drives it deterministically: Async.timeout routes through Clock.sleep, so advancing the fake clock past
        // requestTimeout fires the timeout arm. Once the call fiber resolves the timeout path has fully run. `entered`
        // completes when the request reaches the handler, so the timeout fires for a request that was issued, the case
        // where a policy would send a cancel.
        val entered      = Fiber.Promise.Unsafe.init[Unit, Any]()(using AllowUnsafe.embrace.danger).safe
        val neverReturns = JsonRpcRoute.request[EchoReq, EchoResp]("echo") {
            (_, _) =>
                entered.completeUnitDiscard.andThen(Fiber.Promise.init[Unit, Any].map { gate => gate.get.andThen(EchoResp("never")) })
        }
        Clock.withTimeControl { control =>
            JsonRpcTransport.inMemory.map { (ta, tb) =>
                val capA            = new CapturingTransport(ta)
                val timeoutNoPolicy = JsonRpcHandler.Config(
                    cancellation = Absent,
                    requestTimeout = 150.millis
                )
                JsonRpcHandler.init(capA, Seq.empty, timeoutNoPolicy).map { endpointA =>
                    JsonRpcHandler.init(tb, Seq(neverReturns)).map { _ =>
                        Fiber.initUnscoped(
                            Abort.run[JsonRpcError | Closed](
                                endpointA.call[EchoReq, EchoResp]("echo", EchoReq("x"))
                            )
                        ).map { callFib =>
                            // Advance only once the request has reached the handler and the call has armed its requestTimeout on
                            // the controlled clock.
                            entered.get.andThen(control.awaitPendingSleepers(1)).andThen(control.advance(300.millis)).andThen {
                                callFib.get.map {
                                    case Result.Failure(_: JsonRpcError) =>
                                        Sync.defer {
                                            val noCancelSent = capA.sentList.forall {
                                                case JsonRpcNotification(_, _, _) => false
                                                case _                            => true
                                            }
                                            assert(noCancelSent, "no cancel notification should be sent when cancellation is Absent")
                                        }
                                    case other => fail(s"expected JsonRpcError failure, got $other")
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    "handler aborts with ContentModified on cancel: wire response carries -32801 verbatim" in {
        // Unsafe: AtomicRef.Unsafe.init for id capture across fibers
        val capturedId = AtomicRef.Unsafe.init[Maybe[JsonRpcId]](Absent)(using AllowUnsafe.embrace.danger)
        val echoOnB    = JsonRpcRoute.request[EchoReq, EchoResp]("echo") {
            (_, ctx) =>
                ctx.cancelled.get.andThen {
                    Abort.fail[JsonRpcError](JsonRpcCustomError(-32801, "Content modified"))
                }
        }
        JsonRpcTransport.inMemory.map { (ta, tb) =>
            JsonRpcHandler.init(ta, Seq.empty, expectReplyConfig).map { endpointA =>
                JsonRpcHandler.init(tb, Seq(echoOnB), expectReplyConfig).map { _ =>
                    val idEncoder = JsonRpcExtrasEncoder(id =>
                        Sync.defer { capturedId.set(Present(id))(using AllowUnsafe.embrace.danger); Absent }
                    )
                    Fiber.initUnscoped(
                        Abort.run[JsonRpcError | Closed](
                            endpointA.call[EchoReq, EchoResp]("echo", EchoReq("x"), idEncoder)
                        )
                    ).map { callFib =>
                        assertEventually(Sync.defer(capturedId.get()(using AllowUnsafe.embrace.danger).isDefined)).andThen {
                            Sync.defer(capturedId.get()(using AllowUnsafe.embrace.danger)).map {
                                case Present(id) =>
                                    endpointA.cancel(id, Absent).andThen {
                                        callFib.get.map {
                                            case Result.Failure(e: JsonRpcError) =>
                                                assert(e.code == -32801, s"expected ContentModified -32801, got ${e.code}")
                                            case other => fail(s"expected ContentModified, got $other")
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

    "cancel notification carries extras from original call" in {
        // Unsafe: AtomicRef.Unsafe.init for id capture across fibers
        val capturedId = AtomicRef.Unsafe.init[Maybe[JsonRpcId]](Absent)(using AllowUnsafe.embrace.danger)
        val echoOnB    = JsonRpcRoute.request[EchoReq, EchoResp]("echo") {
            (_, ctx) => ctx.cancelled.get.andThen(EchoResp("done"))
        }
        val sessionExtras = Structure.Value.Record(Chunk("session" -> Structure.Value.Str("s1")))
        JsonRpcTransport.inMemory.map { (ta, tb) =>
            val capA = new CapturingTransport(ta)
            JsonRpcHandler.init(capA, Seq.empty, expectReplyConfig).map { endpointA =>
                JsonRpcHandler.init(tb, Seq(echoOnB), expectReplyConfig).map { _ =>
                    val idEncoder = JsonRpcExtrasEncoder(id =>
                        Sync.defer { capturedId.set(Present(id))(using AllowUnsafe.embrace.danger); Present(sessionExtras) }
                    )
                    Fiber.initUnscoped(
                        Abort.run[JsonRpcError | Closed](
                            endpointA.call[EchoReq, EchoResp]("echo", EchoReq("x"), idEncoder)
                        )
                    ).map { callFib =>
                        assertEventually(Sync.defer(capturedId.get()(using AllowUnsafe.embrace.danger).isDefined)).andThen {
                            Sync.defer(capturedId.get()(using AllowUnsafe.embrace.danger)).map {
                                case Present(id) =>
                                    endpointA.cancel(id, Absent).andThen {
                                        callFib.get.andThen {
                                            assertEventually(Sync.defer {
                                                capA.sentList.exists {
                                                    case JsonRpcNotification(m, _, _) => m == "$/cancelRequest"
                                                    case _                            => false
                                                }
                                            }).andThen {
                                                Sync.defer {
                                                    val cancelNotif = capA.sentList.collectFirst {
                                                        case n @ JsonRpcNotification(m, _, _)
                                                            if m == "$/cancelRequest" => n
                                                    }
                                                    assert(cancelNotif.isDefined, "cancel notification not found")
                                                    assert(
                                                        cancelNotif.get.extras == Present(sessionExtras),
                                                        s"expected extras to match, got ${cancelNotif.get.extras}"
                                                    )
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

    "cancel-during-encode race: encoded request still sent but caller observes abort immediately" in {
        // The idSignal fires inside the encode callback. endpoint.cancel can find callerRegistry[id]
        // while encoding is still in progress (e.g. if we slow down the transport.send step).
        // The caller should observe Abort.fail(cancelled) via the abortSignal race.
        // Unsafe: AtomicRef.Unsafe.init for id capture across fibers
        val capturedId = AtomicRef.Unsafe.init[Maybe[JsonRpcId]](Absent)(using AllowUnsafe.embrace.danger)
        // Unsafe: AtomicBoolean.Unsafe.init for cancel-fired flag across fibers
        val cancelFired = AtomicBoolean.Unsafe.init(false)(using AllowUnsafe.embrace.danger)
        val echoOnB     = JsonRpcRoute.request[EchoReq, EchoResp]("echo") {
            (_, ctx) => ctx.cancelled.get.andThen(EchoResp("cancelled"))
        }
        JsonRpcTransport.inMemory.map { (ta, tb) =>
            val capA = new CapturingTransport(ta)
            JsonRpcHandler.init(capA, Seq.empty, expectReplyConfig).map { endpointA =>
                JsonRpcHandler.init(tb, Seq(echoOnB), expectReplyConfig).map { _ =>
                    val idEncoder = JsonRpcExtrasEncoder(id =>
                        Sync.defer {
                            capturedId.set(Present(id))(using AllowUnsafe.embrace.danger)
                            Absent
                        }
                    )
                    Fiber.initUnscoped(
                        Abort.run[JsonRpcError | Closed](
                            endpointA.call[EchoReq, EchoResp]("echo", EchoReq("x"), idEncoder)
                        )
                    ).map { callFib =>
                        assertEventually(Sync.defer(capturedId.get()(using AllowUnsafe.embrace.danger).isDefined)).andThen {
                            Sync.defer(capturedId.get()(using AllowUnsafe.embrace.danger)).map {
                                case Present(id) =>
                                    endpointA.cancel(id, Absent).andThen {
                                        Sync.defer(discard(cancelFired.set(true)(using AllowUnsafe.embrace.danger))).andThen {
                                            callFib.get.map {
                                                case Result.Failure(e: JsonRpcError) =>
                                                    assert(e.code == -32800)
                                                case other => fail(s"expected -32800, got $other")
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

    "inbound cancel for a protected method leaves the handler running" in {
        // The policy protects "initialize". A peer-originated cancel for an in-flight initialize must be ignored: its
        // ctx.cancelled stays incomplete and the handler is not interrupted. A.cancel refuses protected methods locally, so
        // the cancel is written to the wire directly. The echo call issued after it is the barrier: B's single reader
        // handles the cancel before it dispatches the later request.
        for
            entered  <- Latch.init(1)
            captured <- AtomicRef.init[Maybe[Fiber.Promise[Unit, Sync]]](Absent)
            initOnB = JsonRpcRoute.request[EchoReq, EchoResp]("initialize") { (_, ctx) =>
                captured.set(Present(ctx.cancelled)).andThen(entered.release).andThen(ctx.cancelled.get).andThen(EchoResp("cancelled"))
            }
            echoOnB = JsonRpcRoute.request[EchoReq, EchoResp]("echo") { (req, _) => EchoResp(req.text) }
            (ta, tb) <- JsonRpcTransport.inMemory
            a        <- JsonRpcHandler.init(ta, Seq.empty, noReplyConfig)
            _        <- JsonRpcHandler.init(tb, Seq(initOnB, echoOnB), noReplyConfig)
            idRef    <- AtomicRef.init[Maybe[JsonRpcId]](Absent)
            idEncoder = JsonRpcExtrasEncoder(id => idRef.set(Present(id)).andThen(Absent))
            _  <- Fiber.initUnscoped(Abort.run[JsonRpcError | Closed](a.call[EchoReq, EchoResp]("initialize", EchoReq("init"), idEncoder)))
            _  <- entered.await
            id <- idRef.get.map(_.getOrElse(JsonRpcId(-1L)))
            cancelNotif = JsonRpcNotification(
                "notifications/cancelled",
                Present(Structure.encode(CancelWithReasonParams(id, Absent))),
                Absent
            )
            _       <- Abort.run[Closed](ta.send(cancelNotif))
            echoed  <- a.call[EchoReq, EchoResp]("echo", EchoReq("after cancel"))
            promise <- captured.get
            done    <- promise match
                case Present(p) => p.done
                case Absent     => Kyo.lift(true)
        yield
            assert(echoed == EchoResp("after cancel"))
            assert(!done, "a peer cancel for a protected method completed the handler's ctx.cancelled")
        end for
    }

    "an inbound request cancelled without a reply leaves no in-flight entry behind" in {
        for
            settled <- Latch.init(1)
            echoOnB = JsonRpcRoute.request[EchoReq, EchoResp]("echo") { (_, ctx) =>
                Sync.ensure(settled.release)(ctx.cancelled.get.andThen(EchoResp("done")))
            }
            (ta, tb) <- JsonRpcTransport.inMemory
            a        <- JsonRpcHandler.init(ta, Seq.empty, noReplyConfig)
            b        <- JsonRpcHandler.init(tb, Seq(echoOnB), noReplyConfig)
            idRef    <- AtomicRef.init[Maybe[JsonRpcId]](Absent)
            idEncoder = JsonRpcExtrasEncoder(id => idRef.set(Present(id)).andThen(Absent))
            callFib <- Fiber.initUnscoped(Abort.run[JsonRpcError | Closed](a.call[EchoReq, EchoResp]("echo", EchoReq("x"), idEncoder)))
            _       <- assertEventually(idRef.get.map(_.isDefined))
            id      <- idRef.get.map(_.getOrElse(JsonRpcId(-1L)))
            _       <- a.cancel(id, Absent)
            _       <- callFib.get
            _       <- settled.await
            impl = b.unsafe.asInstanceOf[internal.engine.JsonRpcEndpointImpl]
            _ <- assertEventually(Sync.defer(impl.inbound.registry.size == 0))
        yield succeed
        end for
    }

    "custom policy decoder routes through decodeParams" in {
        val decoder: Structure.Value => Frame ?=> Maybe[JsonRpcId] < Sync = sv =>
            f ?=>
                Sync.defer {
                    sv match
                        case Structure.Value.Record(fields) =>
                            fields.iterator.collectFirst {
                                case ("target", Structure.Value.Str(s)) => JsonRpcId.Str(s)
                            } match
                                case Some(id) => Present(id)
                                case None     => Absent
                        case _ => Absent
                }(using f)
        val policy = JsonRpcCancellationPolicy(
            "x.cancel",
            cancellationWithReply.encodeParams,
            decoder,
            false,
            Absent,
            Set.empty
        )
        val params = Present(Structure.Value.Record(Chunk("target" -> Structure.Value.Str("abc"))))
        internal.engine.CancellationEngine.extractCancelIdForTest(policy, params).map { result =>
            assert(result == Present(JsonRpcId.Str("abc")))
        }
    }

end JsonRpcHandlerCancellationPolicyTest
