package kyo

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kyo.Maybe.Absent
import kyo.Maybe.Present

class CancellationPolicyTest extends Test:

    case class EchoReq(text: String) derives Schema, CanEqual
    case class EchoResp(text: String) derives Schema, CanEqual

    private val lspConfig = JsonRpcEndpoint.Config(cancellation = Present(CancellationPolicy.lsp))
    private val mcpConfig = JsonRpcEndpoint.Config(cancellation = Present(CancellationPolicy.mcp))
    private val noPolicy  = JsonRpcEndpoint.Config(cancellation = Absent)

    private class CapturingTransport(inner: JsonRpcTransport) extends JsonRpcTransport:
        val sent = new java.util.concurrent.ConcurrentLinkedQueue[JsonRpcEnvelope]()

        def send(env: JsonRpcEnvelope)(using Frame): Unit < (Async & Abort[Closed]) =
            Sync.defer(discard(sent.add(env))).andThen(inner.send(env))

        def incoming(using Frame): Stream[JsonRpcEnvelope, Async & Abort[Closed]] =
            inner.incoming

        def close(using Frame): Unit < Async =
            inner.close
    end CapturingTransport

    private class GatedTransport(
        inner: JsonRpcTransport,
        gate: java.util.concurrent.CountDownLatch
    ) extends JsonRpcTransport:
        val sent = new java.util.concurrent.ConcurrentLinkedQueue[JsonRpcEnvelope]()

        def send(env: JsonRpcEnvelope)(using Frame): Unit < (Async & Abort[Closed]) =
            env match
                case _: JsonRpcEnvelope.Response =>
                    Sync.defer(discard(sent.add(env))).andThen {
                        Sync.defer(discard(gate.await())).andThen(inner.send(env))
                    }
                case _ =>
                    Sync.defer(discard(sent.add(env))).andThen(inner.send(env))

        def incoming(using Frame): Stream[JsonRpcEnvelope, Async & Abort[Closed]] =
            inner.incoming

        def close(using Frame): Unit < Async =
            inner.close
    end GatedTransport

    "LSP inbound cancel: handler observes cancelled and caller gets -32800" in run {
        val capturedId = new AtomicReference[Maybe[JsonRpcId]](Absent)
        val echoOnB = JsonRpcMethod[EchoReq, EchoResp, Async & Abort[JsonRpcError]]("echo") {
            (req, ctx) =>
                ctx.cancelled.get.andThen(EchoResp(req.text))
        }
        JsonRpcTransport.inMemory.map { (ta, tb) =>
            val capA = new CapturingTransport(ta)
            JsonRpcEndpoint.init(capA, Seq.empty, lspConfig).map { endpointA =>
                JsonRpcEndpoint.init(tb, Seq(echoOnB), lspConfig).map { _ =>
                    val idEncoder = ExtrasEncoder(id =>
                        Sync.defer { capturedId.set(Present(id)); Absent }
                    )
                    Fiber.initUnscoped(
                        Abort.run[JsonRpcError | Closed](
                            endpointA.call[EchoReq, EchoResp]("echo", EchoReq("hello"), idEncoder)
                        )
                    ).map { callFib =>
                        untilTrue(Sync.defer(capturedId.get().isDefined)).andThen {
                            Sync.defer(capturedId.get()).map {
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

    "LSP inbound cancel: a reply IS still sent on the transport" in run {
        val capturedId = new AtomicReference[Maybe[JsonRpcId]](Absent)
        val echoOnB = JsonRpcMethod[EchoReq, EchoResp, Async & Abort[JsonRpcError]]("echo") {
            (req, ctx) =>
                ctx.cancelled.get.andThen(EchoResp(req.text))
        }
        JsonRpcTransport.inMemory.map { (ta, tb) =>
            val capB = new CapturingTransport(tb)
            JsonRpcEndpoint.init(ta, Seq.empty, lspConfig).map { endpointA =>
                JsonRpcEndpoint.init(capB, Seq(echoOnB), lspConfig).map { _ =>
                    val idEncoder = ExtrasEncoder(id =>
                        Sync.defer { capturedId.set(Present(id)); Absent }
                    )
                    Fiber.initUnscoped(
                        Abort.run[JsonRpcError | Closed](
                            endpointA.call[EchoReq, EchoResp]("echo", EchoReq("hello"), idEncoder)
                        )
                    ).map { callFib =>
                        untilTrue(Sync.defer(capturedId.get().isDefined)).andThen {
                            Sync.defer(capturedId.get()).map {
                                case Present(id) =>
                                    endpointA.cancel(id, Absent).andThen {
                                        callFib.get.andThen {
                                            untilTrue(Sync.defer {
                                                import scala.jdk.CollectionConverters.*
                                                capB.sent.asScala.exists {
                                                    case JsonRpcEnvelope.Response(rid, _, _, _) => rid == id
                                                    case _                                      => false
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

    "MCP inbound cancel: no reply is sent on the transport" in run {
        val capturedId = new AtomicReference[Maybe[JsonRpcId]](Absent)
        val echoOnB = JsonRpcMethod[EchoReq, EchoResp, Async & Abort[JsonRpcError]]("echo") {
            (_, ctx) =>
                ctx.cancelled.get.andThen(EchoResp("done"))
        }
        JsonRpcTransport.inMemory.map { (ta, tb) =>
            val capB = new CapturingTransport(tb)
            JsonRpcEndpoint.init(ta, Seq.empty, mcpConfig).map { endpointA =>
                JsonRpcEndpoint.init(capB, Seq(echoOnB), mcpConfig).map { _ =>
                    val idEncoder = ExtrasEncoder(id =>
                        Sync.defer { capturedId.set(Present(id)); Absent }
                    )
                    Fiber.initUnscoped(
                        Abort.run[JsonRpcError | Closed](
                            endpointA.call[EchoReq, EchoResp]("echo", EchoReq("x"), idEncoder)
                        )
                    ).map { callFib =>
                        untilTrue(Sync.defer(capturedId.get().isDefined)).andThen {
                            Sync.defer(capturedId.get()).map {
                                case Present(id) =>
                                    endpointA.cancel(id, Absent).andThen {
                                        Async.sleep(100.millis).andThen {
                                            Sync.defer {
                                                import scala.jdk.CollectionConverters.*
                                                val noReply = capB.sent.asScala.forall {
                                                    case JsonRpcEnvelope.Response(rid, _, _, _) => rid != id
                                                    case _                                      => true
                                                }
                                                assert(noReply, "MCP should not send a reply for cancelled request")
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

    "MCP inbound cancel race: cancel while reply queued in writer channel suppresses the reply" in run {
        val capturedId = new AtomicReference[Maybe[JsonRpcId]](Absent)
        val sendLatch  = new java.util.concurrent.CountDownLatch(1)
        JsonRpcTransport.inMemory.map { (ta, tb) =>
            val gatedTb = new GatedTransport(tb, sendLatch)
            val echoOnB = JsonRpcMethod[EchoReq, EchoResp, Async & Abort[JsonRpcError]]("echo") {
                (req, _) => EchoResp(req.text)
            }
            JsonRpcEndpoint.init(ta, Seq.empty, mcpConfig).map { endpointA =>
                JsonRpcEndpoint.init(gatedTb, Seq(echoOnB), mcpConfig).map { _ =>
                    val idEncoder = ExtrasEncoder(id =>
                        Sync.defer { capturedId.set(Present(id)); Absent }
                    )
                    Fiber.initUnscoped(
                        Abort.run[JsonRpcError | Closed](
                            endpointA.call[EchoReq, EchoResp]("echo", EchoReq("x"), idEncoder)
                        )
                    ).map { callFib =>
                        untilTrue(Sync.defer(capturedId.get().isDefined)).andThen {
                            Sync.defer(capturedId.get()).map {
                                case Present(id) =>
                                    // Wait for response to reach GatedTransport.send (before the latch blocks it)
                                    untilTrue(Sync.defer {
                                        import scala.jdk.CollectionConverters.*
                                        gatedTb.sent.asScala.exists {
                                            case JsonRpcEnvelope.Response(rid, _, _, _) => rid == id
                                            case _                                      => false
                                        }
                                    }).andThen {
                                        // Fire cancel while reply is stuck behind the latch
                                        endpointA.cancel(id, Absent).andThen {
                                            // Release latch: writer now checks suppress flag; should drop the reply
                                            Sync.defer(discard(sendLatch.countDown())).andThen {
                                                Async.sleep(150.millis).andThen {
                                                    // The call should have failed (cancel fires abortSignal)
                                                    callFib.done.map { isDone =>
                                                        assert(isDone, "call should be done after cancel")
                                                    }
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

    "LSP outbound cancel: sends $/cancelRequest notification and call fails with -32800" in run {
        val capturedId = new AtomicReference[Maybe[JsonRpcId]](Absent)
        val echoOnB = JsonRpcMethod[EchoReq, EchoResp, Async & Abort[JsonRpcError]]("echo") {
            (_, ctx) => ctx.cancelled.get.andThen(EchoResp("cancelled"))
        }
        JsonRpcTransport.inMemory.map { (ta, tb) =>
            val capA = new CapturingTransport(ta)
            JsonRpcEndpoint.init(capA, Seq.empty, lspConfig).map { endpointA =>
                JsonRpcEndpoint.init(tb, Seq(echoOnB), lspConfig).map { _ =>
                    val idEncoder = ExtrasEncoder(id =>
                        Sync.defer { capturedId.set(Present(id)); Absent }
                    )
                    Fiber.initUnscoped(
                        Abort.run[JsonRpcError | Closed](
                            endpointA.call[EchoReq, EchoResp]("echo", EchoReq("hello"), idEncoder)
                        )
                    ).map { callFib =>
                        untilTrue(Sync.defer(capturedId.get().isDefined)).andThen {
                            Sync.defer(capturedId.get()).map {
                                case Present(id) =>
                                    endpointA.cancel(id, Absent).andThen {
                                        callFib.get.map {
                                            case Result.Failure(e: JsonRpcError) =>
                                                assert(e.code == -32800)
                                                Sync.defer {
                                                    import scala.jdk.CollectionConverters.*
                                                    val cancelSent = capA.sent.asScala.exists {
                                                        case JsonRpcEnvelope.Notification(m, _, _) => m == "$/cancelRequest"
                                                        case _                                     => false
                                                    }
                                                    assert(cancelSent, "$/cancelRequest notification not found on transport")
                                                }
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

    "MCP outbound cancel: sends notifications/cancelled with requestId and reason, call fails" in run {
        val capturedId = new AtomicReference[Maybe[JsonRpcId]](Absent)
        val echoOnB = JsonRpcMethod[EchoReq, EchoResp, Async & Abort[JsonRpcError]]("echo") {
            (_, ctx) => ctx.cancelled.get.andThen(EchoResp("cancelled"))
        }
        JsonRpcTransport.inMemory.map { (ta, tb) =>
            val capA = new CapturingTransport(ta)
            JsonRpcEndpoint.init(capA, Seq.empty, mcpConfig).map { endpointA =>
                JsonRpcEndpoint.init(tb, Seq(echoOnB), mcpConfig).map { _ =>
                    val idEncoder = ExtrasEncoder(id =>
                        Sync.defer { capturedId.set(Present(id)); Absent }
                    )
                    Fiber.initUnscoped(
                        Abort.run[JsonRpcError | Closed](
                            endpointA.call[EchoReq, EchoResp]("echo", EchoReq("x"), idEncoder)
                        )
                    ).map { callFib =>
                        untilTrue(Sync.defer(capturedId.get().isDefined)).andThen {
                            Sync.defer(capturedId.get()).map {
                                case Present(id) =>
                                    endpointA.cancel(id, Present("user requested")).andThen {
                                        callFib.get.map {
                                            case Result.Failure(e: JsonRpcError) =>
                                                Sync.defer {
                                                    import scala.jdk.CollectionConverters.*
                                                    val cancelNotif = capA.sent.asScala.collectFirst {
                                                        case n @ JsonRpcEnvelope.Notification(m, _, _)
                                                            if m == "notifications/cancelled" => n
                                                    }
                                                    assert(cancelNotif.isDefined, "notifications/cancelled not found on transport")
                                                }
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

    "cancel for protected method (MCP initialize) sends no notification and does not abort call" in run {
        val capturedId = new AtomicReference[Maybe[JsonRpcId]](Absent)
        val initOnB = JsonRpcMethod[EchoReq, EchoResp, Async & Abort[JsonRpcError]]("initialize") {
            (req, ctx) => ctx.cancelled.get.andThen(EchoResp("initialized"))
        }
        JsonRpcTransport.inMemory.map { (ta, tb) =>
            val capA = new CapturingTransport(ta)
            JsonRpcEndpoint.init(capA, Seq.empty, mcpConfig).map { endpointA =>
                JsonRpcEndpoint.init(tb, Seq(initOnB), mcpConfig).map { _ =>
                    val idEncoder = ExtrasEncoder(id =>
                        Sync.defer { capturedId.set(Present(id)); Absent }
                    )
                    Fiber.initUnscoped(
                        Abort.run[JsonRpcError | Closed](
                            endpointA.call[EchoReq, EchoResp]("initialize", EchoReq("init"), idEncoder)
                        )
                    ).map { callFib =>
                        untilTrue(Sync.defer(capturedId.get().isDefined)).andThen {
                            Sync.defer(capturedId.get()).map {
                                case Present(id) =>
                                    endpointA.cancel(id, Absent).andThen {
                                        Async.sleep(50.millis).andThen {
                                            Sync.defer {
                                                import scala.jdk.CollectionConverters.*
                                                val noCancelSent = capA.sent.asScala.forall {
                                                    case JsonRpcEnvelope.Notification(m, _, _) => m != "notifications/cancelled"
                                                    case _                                     => true
                                                }
                                                assert(noCancelSent, "cancel notification should not be sent for protected method")
                                            }.andThen {
                                                callFib.done.map { isDone =>
                                                    assert(!isDone, "call should still be pending after refused cancel")
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

    "cancel for already-completed call returns unit without sending a cancel notification" in run {
        val capturedId = new AtomicReference[Maybe[JsonRpcId]](Absent)
        val echoOnB = JsonRpcMethod[EchoReq, EchoResp, Async & Abort[JsonRpcError]]("echo") {
            (req, _) => EchoResp(req.text)
        }
        JsonRpcTransport.inMemory.map { (ta, tb) =>
            val capA = new CapturingTransport(ta)
            JsonRpcEndpoint.init(capA, Seq.empty, lspConfig).map { endpointA =>
                JsonRpcEndpoint.init(tb, Seq(echoOnB), lspConfig).map { _ =>
                    val idEncoder = ExtrasEncoder(id =>
                        Sync.defer { capturedId.set(Present(id)); Absent }
                    )
                    endpointA.call[EchoReq, EchoResp]("echo", EchoReq("hello"), idEncoder).andThen {
                        endpointA.awaitDrain.andThen {
                            Sync.defer(capturedId.get()).map {
                                case Present(id) =>
                                    val countBefore = Sync.defer {
                                        import scala.jdk.CollectionConverters.*
                                        capA.sent.asScala.count {
                                            case _: JsonRpcEnvelope.Notification => true
                                            case _                               => false
                                        }
                                    }
                                    countBefore.map { before =>
                                        endpointA.cancel(id, Absent).andThen {
                                            Sync.defer {
                                                import scala.jdk.CollectionConverters.*
                                                val after = capA.sent.asScala.count {
                                                    case _: JsonRpcEnvelope.Notification => true
                                                    case _                               => false
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

    "inbound cancel for absent handler id is silently dropped without error" in run {
        val echoOnB = JsonRpcMethod[EchoReq, EchoResp, Async & Abort[JsonRpcError]]("echo") {
            (req, _) => EchoResp(req.text)
        }
        JsonRpcTransport.inMemory.map { (ta, tb) =>
            JsonRpcEndpoint.init(ta, Seq.empty, mcpConfig).map { endpointA =>
                JsonRpcEndpoint.init(tb, Seq(echoOnB), mcpConfig).map { _ =>
                    // Inject a cancel notification for a non-existent id directly into B's incoming stream
                    val fakeId = JsonRpcId.Num(99999L)
                    val cancelNotif = JsonRpcEnvelope.Notification(
                        "notifications/cancelled",
                        Present(Structure.Value.Record(Chunk(
                            "requestId" -> Structure.encode(fakeId),
                            "reason"    -> Structure.Value.Null
                        ))),
                        Absent
                    )
                    Abort.run[Closed](ta.send(cancelNotif)).andThen {
                        Async.sleep(50.millis).andThen {
                            // Verify endpoint B still works normally
                            endpointA.call[EchoReq, EchoResp]("echo", EchoReq("still alive")).map { resp =>
                                assert(resp == EchoResp("still alive"))
                            }
                        }
                    }
                }
            }
        }
    }

    "timeout with LSP policy sends $/cancelRequest and caller fails with -32800" in run {
        val capturedId = new AtomicReference[Maybe[JsonRpcId]](Absent)
        val neverReturns = JsonRpcMethod[EchoReq, EchoResp, Async & Abort[JsonRpcError]]("echo") {
            (_, ctx) => ctx.cancelled.get.andThen(EchoResp("cancelled"))
        }
        JsonRpcTransport.inMemory.map { (ta, tb) =>
            val capA = new CapturingTransport(ta)
            val timeoutLspConfig = JsonRpcEndpoint.Config(
                cancellation = Present(CancellationPolicy.lsp),
                requestTimeout = 150.millis
            )
            JsonRpcEndpoint.init(capA, Seq.empty, timeoutLspConfig).map { endpointA =>
                JsonRpcEndpoint.init(tb, Seq(neverReturns), lspConfig).map { _ =>
                    Abort.run[JsonRpcError | Closed](
                        endpointA.call[EchoReq, EchoResp]("echo", EchoReq("x"))
                    ).map {
                        case Result.Failure(e: JsonRpcError) =>
                            assert(e.code == -32800)
                            untilTrue(Sync.defer {
                                import scala.jdk.CollectionConverters.*
                                capA.sent.asScala.exists {
                                    case JsonRpcEnvelope.Notification(m, _, _) => m == "$/cancelRequest"
                                    case _                                     => false
                                }
                            }).andThen(succeed)
                        case other => fail(s"expected -32800, got $other")
                    }
                }
            }
        }
    }

    "timeout with cancellation=Absent sends no cancel notification; call fails locally" in run {
        val neverReturns = JsonRpcMethod[EchoReq, EchoResp, Async & Abort[JsonRpcError]]("echo") {
            (_, _) => Async.sleep(10.seconds).andThen(EchoResp("never"))
        }
        JsonRpcTransport.inMemory.map { (ta, tb) =>
            val capA = new CapturingTransport(ta)
            val timeoutNoPolicy = JsonRpcEndpoint.Config(
                cancellation = Absent,
                requestTimeout = 150.millis
            )
            JsonRpcEndpoint.init(capA, Seq.empty, timeoutNoPolicy).map { endpointA =>
                JsonRpcEndpoint.init(tb, Seq(neverReturns)).map { _ =>
                    Abort.run[JsonRpcError | Closed](
                        endpointA.call[EchoReq, EchoResp]("echo", EchoReq("x"))
                    ).map {
                        case Result.Failure(_: JsonRpcError) =>
                            Sync.defer {
                                import scala.jdk.CollectionConverters.*
                                val noCancelSent = capA.sent.asScala.forall {
                                    case JsonRpcEnvelope.Notification(_, _, _) => false
                                    case _                                     => true
                                }
                                assert(noCancelSent, "no cancel notification should be sent when cancellation is Absent")
                            }
                        case other => fail(s"expected JsonRpcError failure, got $other")
                    }
                }
            }
        }
    }

    "handler aborts with ContentModified on cancel: wire response carries -32801 verbatim" in run {
        val capturedId = new AtomicReference[Maybe[JsonRpcId]](Absent)
        val echoOnB = JsonRpcMethod[EchoReq, EchoResp, Async & Abort[JsonRpcError]]("echo") {
            (_, ctx) =>
                ctx.cancelled.get.andThen {
                    Abort.fail[JsonRpcError](JsonRpcError.ContentModified)
                }
        }
        JsonRpcTransport.inMemory.map { (ta, tb) =>
            JsonRpcEndpoint.init(ta, Seq.empty, lspConfig).map { endpointA =>
                JsonRpcEndpoint.init(tb, Seq(echoOnB), lspConfig).map { _ =>
                    val idEncoder = ExtrasEncoder(id =>
                        Sync.defer { capturedId.set(Present(id)); Absent }
                    )
                    Fiber.initUnscoped(
                        Abort.run[JsonRpcError | Closed](
                            endpointA.call[EchoReq, EchoResp]("echo", EchoReq("x"), idEncoder)
                        )
                    ).map { callFib =>
                        untilTrue(Sync.defer(capturedId.get().isDefined)).andThen {
                            Sync.defer(capturedId.get()).map {
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

    "cancel notification carries extras from original call (C1 extras propagation)" in run {
        val capturedId = new AtomicReference[Maybe[JsonRpcId]](Absent)
        val echoOnB = JsonRpcMethod[EchoReq, EchoResp, Async & Abort[JsonRpcError]]("echo") {
            (_, ctx) => ctx.cancelled.get.andThen(EchoResp("done"))
        }
        val sessionExtras = Structure.Value.Record(Chunk("session" -> Structure.Value.Str("s1")))
        JsonRpcTransport.inMemory.map { (ta, tb) =>
            val capA = new CapturingTransport(ta)
            JsonRpcEndpoint.init(capA, Seq.empty, lspConfig).map { endpointA =>
                JsonRpcEndpoint.init(tb, Seq(echoOnB), lspConfig).map { _ =>
                    val idEncoder = ExtrasEncoder(id =>
                        Sync.defer { capturedId.set(Present(id)); Present(sessionExtras) }
                    )
                    Fiber.initUnscoped(
                        Abort.run[JsonRpcError | Closed](
                            endpointA.call[EchoReq, EchoResp]("echo", EchoReq("x"), idEncoder)
                        )
                    ).map { callFib =>
                        untilTrue(Sync.defer(capturedId.get().isDefined)).andThen {
                            Sync.defer(capturedId.get()).map {
                                case Present(id) =>
                                    endpointA.cancel(id, Absent).andThen {
                                        callFib.get.andThen {
                                            Sync.defer {
                                                import scala.jdk.CollectionConverters.*
                                                val cancelNotif = capA.sent.asScala.collectFirst {
                                                    case n @ JsonRpcEnvelope.Notification(m, _, _)
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
                                case Absent => fail("id not captured")
                            }
                        }
                    }
                }
            }
        }
    }

    "cancel-during-encode race: encoded request still sent but caller observes abort immediately" in run {
        // Test 64 (H2 cancel-during-encode race):
        // The idSignal fires inside the encode callback. endpoint.cancel can find callerRegistry[id]
        // while encoding is still in progress (e.g. if we slow down the transport.send step).
        // The caller should observe Abort.fail(cancelled) via the abortSignal race.
        val capturedId  = new AtomicReference[Maybe[JsonRpcId]](Absent)
        val cancelFired = new java.util.concurrent.atomic.AtomicBoolean(false)
        val echoOnB = JsonRpcMethod[EchoReq, EchoResp, Async & Abort[JsonRpcError]]("echo") {
            (_, ctx) => ctx.cancelled.get.andThen(EchoResp("cancelled"))
        }
        JsonRpcTransport.inMemory.map { (ta, tb) =>
            val capA = new CapturingTransport(ta)
            JsonRpcEndpoint.init(capA, Seq.empty, lspConfig).map { endpointA =>
                JsonRpcEndpoint.init(tb, Seq(echoOnB), lspConfig).map { _ =>
                    val idEncoder = ExtrasEncoder(id =>
                        Sync.defer {
                            capturedId.set(Present(id))
                            Absent
                        }
                    )
                    Fiber.initUnscoped(
                        Abort.run[JsonRpcError | Closed](
                            endpointA.call[EchoReq, EchoResp]("echo", EchoReq("x"), idEncoder)
                        )
                    ).map { callFib =>
                        untilTrue(Sync.defer(capturedId.get().isDefined)).andThen {
                            Sync.defer(capturedId.get()).map {
                                case Present(id) =>
                                    endpointA.cancel(id, Absent).andThen {
                                        Sync.defer(discard(cancelFired.set(true))).andThen {
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

end CancellationPolicyTest
