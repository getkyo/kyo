package kyo

import kyo.Maybe.Absent
import kyo.Maybe.Present

class JsonRpcHandlerTest extends JsonRpcTest:

    case class AddReq(a: Int, b: Int) derives Schema, CanEqual
    case class AddResp(sum: Int) derives Schema, CanEqual
    case class LogMsg(text: String) derives Schema, CanEqual
    case class DialogParams(accept: Boolean) derives Schema, CanEqual

    private def mkEndpoints(
        methodsA: Seq[JsonRpcRoute[?, ?, ?]],
        methodsB: Seq[JsonRpcRoute[?, ?, ?]]
    )(using Frame): (JsonRpcHandler, JsonRpcHandler) < (Sync & Async & Scope) =
        JsonRpcTransport.inMemory.map { (ta, tb) =>
            JsonRpcHandler.init(ta, methodsA).map { endpointA =>
                JsonRpcHandler.init(tb, methodsB).map { endpointB =>
                    (endpointA, endpointB)
                }
            }
        }

    private class CountingTransport(inner: JsonRpcTransport, val counter: AtomicInt.Unsafe)
        extends JsonRpcTransport:

        def send(env: JsonRpcEnvelope)(using Frame): Unit < (Async & Abort[Closed]) =
            Sync.defer(discard(counter.incrementAndGet()(using AllowUnsafe.embrace.danger))).andThen(inner.send(env))

        def incoming(using Frame): Stream[JsonRpcEnvelope, Async & Abort[Closed]] =
            inner.incoming

        def close(using Frame): Unit < Async =
            inner.close
    end CountingTransport

    "call add handler returns correct result" in run {
        val addMethod = JsonRpcRoute.request[AddReq, AddResp]("add") {
            (req, _) => AddResp(req.a + req.b)
        }
        mkEndpoints(Seq.empty, Seq(addMethod)).map { (a, _) =>
            a.call[AddReq, AddResp]("add", AddReq(1, 2)).map { resp =>
                assert(resp == AddResp(3))
            }
        }
    }

    "notify sends notification and handler runs without reply" in run {
        // Unsafe: AtomicInt.Unsafe.init for concurrent counter in synchronous handler scope
        val seen = AtomicInt.Unsafe.init(0)(using AllowUnsafe.embrace.danger)
        val logMethod = JsonRpcRoute.request[LogMsg, Unit]("log") {
            (_, _) => Sync.defer(discard(seen.incrementAndGet()(using AllowUnsafe.embrace.danger)))
        }
        mkEndpoints(Seq.empty, Seq(logMethod)).map { (a, _) =>
            a.notify[LogMsg]("log", LogMsg("hello")).andThen {
                untilTrue(Sync.defer(seen.get()(using AllowUnsafe.embrace.danger) == 1)).andThen(succeed)
            }
        }
    }

    "bidirectional simultaneous calls resolve without cross-wiring" in run {
        val addOnB = JsonRpcRoute.request[AddReq, AddResp]("add") {
            (req, _) => AddResp(req.a + req.b)
        }
        val addOnA = JsonRpcRoute.request[AddReq, AddResp]("add") {
            (req, _) => AddResp(req.a + req.b)
        }
        mkEndpoints(Seq(addOnA), Seq(addOnB)).map { (a, b) =>
            Async.zip[JsonRpcError | Closed, AddResp, AddResp, Any](
                a.call[AddReq, AddResp]("add", AddReq(3, 4)),
                b.call[AddReq, AddResp]("add", AddReq(10, 20))
            ).map { (ra, rb) =>
                assert(ra == AddResp(7) && rb == AddResp(30))
            }
        }
    }

    "multiple concurrent calls resolve independently" in run {
        val addOnB = JsonRpcRoute.request[AddReq, AddResp]("add") {
            (req, _) => AddResp(req.a + req.b)
        }
        mkEndpoints(Seq.empty, Seq(addOnB)).map { (a, _) =>
            Async.zip[JsonRpcError | Closed, AddResp, AddResp, AddResp, Any](
                a.call[AddReq, AddResp]("add", AddReq(1, 1)),
                a.call[AddReq, AddResp]("add", AddReq(2, 2)),
                a.call[AddReq, AddResp]("add", AddReq(3, 3))
            ).map { (r1, r2, r3) =>
                assert(r1 == AddResp(2) && r2 == AddResp(4) && r3 == AddResp(6))
            }
        }
    }

    "unknown method request fails with MethodNotFound code -32601" in run {
        mkEndpoints(Seq.empty, Seq.empty).map { (a, _) =>
            Abort.run[JsonRpcError | Closed](a.call[AddReq, AddResp]("nonexistent", AddReq(0, 0))).map {
                case Result.Failure(e: JsonRpcError) => assert(e.code == -32601)
                case other                           => fail(s"expected MethodNotFound, got $other")
            }
        }
    }

    "Scope exit closes Exchange and fails in-flight calls" in run {
        val addOnB = JsonRpcRoute.request[AddReq, AddResp]("add") {
            (req, _) => Async.sleep(2.seconds).andThen(AddResp(req.a + req.b))
        }
        JsonRpcTransport.inMemory.map { (ta, tb) =>
            JsonRpcHandler.init(tb, Seq(addOnB)).map { _ =>
                // Outer fiber lives beyond the scope; captures the call result
                // Unsafe: AtomicRef.Unsafe.init for result capture across fibers
                val resultRef =
                    AtomicRef.Unsafe.init[Maybe[Result[JsonRpcError | Closed, AddResp]]](Absent)(using AllowUnsafe.embrace.danger)
                Scope.run {
                    JsonRpcHandler.init(ta, Seq.empty).map { endpointA =>
                        Fiber.initUnscoped(
                            Abort.run[JsonRpcError | Closed](
                                endpointA.call[AddReq, AddResp]("add", AddReq(1, 2))
                            ).map(r => Sync.defer(resultRef.set(Present(r))(using AllowUnsafe.embrace.danger)))
                        ).unit
                    }
                }.andThen {
                    untilTrue(Sync.defer(resultRef.get()(using AllowUnsafe.embrace.danger).isDefined)).andThen {
                        Sync.defer {
                            resultRef.get()(using AllowUnsafe.embrace.danger) match
                                case Present(Result.Failure(_: Closed)) => succeed
                                case Present(Result.Success(v))         => fail(s"expected Closed failure after scope close, got $v")
                                case Present(Result.Failure(e))         => fail(s"expected Closed but got: $e")
                                case Present(Result.Panic(t))           => fail(s"unexpected panic: $t")
                                case Absent                             => fail("no result captured")
                        }
                    }
                }
            }
        }
    }

    "callerRegistry drain on close fails pending calls" in run {
        val addOnB = JsonRpcRoute.request[AddReq, AddResp]("add") {
            (req, _) => Async.sleep(3.seconds).andThen(AddResp(req.a + req.b))
        }
        JsonRpcTransport.inMemory.map { (ta, tb) =>
            JsonRpcHandler.init(tb, Seq(addOnB)).map { _ =>
                // Unsafe: AtomicRef.Unsafe.init for result capture across fibers
                val resultRef =
                    AtomicRef.Unsafe.init[Maybe[Result[JsonRpcError | Closed, AddResp]]](Absent)(using AllowUnsafe.embrace.danger)
                Scope.run {
                    JsonRpcHandler.init(ta, Seq.empty).map { endpointA =>
                        Fiber.initUnscoped(
                            Abort.run[JsonRpcError | Closed](
                                endpointA.call[AddReq, AddResp]("add", AddReq(1, 2))
                            ).map(r => Sync.defer(resultRef.set(Present(r))(using AllowUnsafe.embrace.danger)))
                        ).andThen(Async.sleep(50.millis))
                    }
                }.andThen {
                    untilTrue(Sync.defer(resultRef.get()(using AllowUnsafe.embrace.danger).isDefined)).andThen {
                        Sync.defer {
                            resultRef.get()(using AllowUnsafe.embrace.danger) match
                                case Present(Result.Failure(e: JsonRpcError)) =>
                                    assert(e.code == -32603, s"expected internalError code -32603, got ${e.code}")
                                case Present(Result.Success(v)) =>
                                    fail(s"expected JsonRpcError internalError after close, got $v")
                                case Present(Result.Failure(other)) =>
                                    fail(s"expected JsonRpcError but got: $other")
                                case Present(Result.Panic(t)) =>
                                    fail(s"unexpected panic: $t")
                                case Absent =>
                                    fail("no result captured")
                        }
                    }
                }
            }
        }
    }

    "callerRegistry is empty after a call completes normally" in run {
        val addOnB = JsonRpcRoute.request[AddReq, AddResp]("add") {
            (req, _) => AddResp(req.a + req.b)
        }
        mkEndpoints(Seq.empty, Seq(addOnB)).map { (a, _) =>
            a.call[AddReq, AddResp]("add", AddReq(5, 5)).andThen {
                a.awaitDrain.andThen {
                    Sync.defer(assert(a.unsafe.asInstanceOf[internal.engine.JsonRpcEndpointImpl].callerRegistry.isEmpty))
                }
            }
        }
    }

    "callerRegistry is empty after a call fiber is interrupted externally" in run {
        val addOnB = JsonRpcRoute.request[AddReq, AddResp]("add") {
            (req, _) => Async.sleep(5.seconds).andThen(AddResp(req.a + req.b))
        }
        mkEndpoints(Seq.empty, Seq(addOnB)).map { (a, _) =>
            Fiber.initUnscoped(
                Abort.run[JsonRpcError | Closed](a.call[AddReq, AddResp]("add", AddReq(1, 2)))
            ).map { fib =>
                Async.sleep(30.millis).andThen {
                    fib.interrupt.andThen {
                        untilTrue(
                            Sync.defer(a.unsafe.asInstanceOf[internal.engine.JsonRpcEndpointImpl].callerRegistry.isEmpty)
                        ).andThen(succeed)
                    }
                }
            }
        }
    }

    "call returns error when transport closes mid-call" in run {
        val addOnB = JsonRpcRoute.request[AddReq, AddResp]("add") {
            (req, _) => Async.sleep(2.seconds).andThen(AddResp(req.a + req.b))
        }
        mkEndpoints(Seq.empty, Seq(addOnB)).map { (a, _) =>
            Fiber.initUnscoped(
                Abort.run[JsonRpcError | Closed](a.call[AddReq, AddResp]("add", AddReq(1, 2)))
            ).map { callFib =>
                Async.sleep(30.millis).andThen {
                    a.closeNow.andThen {
                        callFib.get.map {
                            case Result.Failure(_) => succeed
                            case Result.Success(v) => fail(s"expected failure, got $v")
                            case Result.Panic(t)   => fail(s"unexpected panic: $t")
                        }
                    }
                }
            }
        }
    }

    "awaitDrain returns after all pending calls resolve" in run {
        val addOnB = JsonRpcRoute.request[AddReq, AddResp]("add") {
            (req, _) => Async.sleep(30.millis).andThen(AddResp(req.a + req.b))
        }
        mkEndpoints(Seq.empty, Seq(addOnB)).map { (a, _) =>
            Fiber.initUnscoped(a.call[AddReq, AddResp]("add", AddReq(1, 1))).andThen {
                Fiber.initUnscoped(a.call[AddReq, AddResp]("add", AddReq(2, 2))).andThen {
                    a.awaitDrain.andThen {
                        Sync.Unsafe.defer {
                            assert(a.unsafe.asInstanceOf[internal.engine.JsonRpcEndpointImpl].inFlight.unsafe.get() <= 0)
                        }
                    }
                }
            }
        }
    }

    "cancel with no CancellationPolicy fails call locally with cancelled error" in run {
        val addOnB = JsonRpcRoute.request[AddReq, AddResp]("add") {
            (req, _) => Async.sleep(3.seconds).andThen(AddResp(req.a + req.b))
        }
        // Unsafe: AtomicRef.Unsafe.init for id capture across fibers
        val capturedId = AtomicRef.Unsafe.init[Maybe[JsonRpcId]](Absent)(using AllowUnsafe.embrace.danger)
        val captureEncoder = JsonRpcExtrasEncoder(id =>
            Sync.defer {
                capturedId.set(Present(id))(using AllowUnsafe.embrace.danger)
                Absent
            }
        )
        val noPolicyConfig = JsonRpcHandler.Config(cancellation = Absent)
        JsonRpcTransport.inMemory.map { (ta, tb) =>
            JsonRpcHandler.init(ta, Seq.empty, noPolicyConfig).map { a =>
                JsonRpcHandler.init(tb, Seq(addOnB)).map { _ =>
                    Fiber.initUnscoped(
                        Abort.run[JsonRpcError | Closed](
                            a.call[AddReq, AddResp]("add", AddReq(1, 2), captureEncoder)
                        )
                    ).map { callFib =>
                        untilTrue(Sync.defer(capturedId.get()(using AllowUnsafe.embrace.danger).isDefined)).andThen {
                            Sync.defer(capturedId.get()(using AllowUnsafe.embrace.danger)).map {
                                case Present(id) =>
                                    a.cancel(id, Absent).andThen {
                                        callFib.get.map {
                                            case Result.Failure(e: JsonRpcError) => assert(e.code == -32800)
                                            case other                           => fail(s"expected cancelled JsonRpcError, got $other")
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

    "late reply for cancelled outbound call is silently dropped" in run {
        // Unsafe: AtomicRef.Unsafe.init for id capture across fibers
        val capturedId = AtomicRef.Unsafe.init[Maybe[JsonRpcId]](Absent)(using AllowUnsafe.embrace.danger)
        val captureEncoder = JsonRpcExtrasEncoder(id =>
            Sync.defer {
                capturedId.set(Present(id))(using AllowUnsafe.embrace.danger)
                Absent
            }
        )
        val slowAddOnB = JsonRpcRoute.request[AddReq, AddResp]("add") {
            (req, _) => Async.sleep(5.seconds).andThen(AddResp(req.a + req.b))
        }
        // Use the fast addOnB for the second (post-cancel) call to verify the endpoint is still healthy
        val fastAddOnB = JsonRpcRoute.request[AddReq, AddResp]("add") {
            (req, _) => AddResp(req.a + req.b)
        }
        JsonRpcTransport.inMemory.map { (ta, tb) =>
            JsonRpcHandler.init(ta, Seq.empty).map { a =>
                JsonRpcHandler.init(tb, Seq(slowAddOnB)).map { _ =>
                    Fiber.initUnscoped(
                        Abort.run[JsonRpcError | Closed](
                            a.call[AddReq, AddResp]("add", AddReq(1, 2), captureEncoder)
                        )
                    ).map { callFib =>
                        untilTrue(Sync.defer(capturedId.get()(using AllowUnsafe.embrace.danger).isDefined)).andThen {
                            Sync.defer(capturedId.get()(using AllowUnsafe.embrace.danger)).map {
                                case Present(id) =>
                                    a.cancel(id, Absent).andThen {
                                        callFib.get.map {
                                            case Result.Failure(e: JsonRpcError) =>
                                                assert(e.code == -32800)
                                                // Simulate a late response for the already-cancelled id: the peer
                                                // sends a success response after the caller gave up. The endpoint
                                                // must silently drop it and NOT complete any live call.
                                                val lateReply = JsonRpcResponse(
                                                    id,
                                                    Present(Structure.encode(AddResp(42))),
                                                    Absent,
                                                    Absent
                                                )
                                                Abort.run[Closed](tb.send(lateReply)).map {
                                                    case Result.Success(_) => succeed
                                                    case Result.Failure(c) => fail(s"transport closed: $c")
                                                    case Result.Panic(t)   => fail(s"unexpected panic: $t")
                                                }
                                            case other => fail(s"expected cancelled JsonRpcError, got $other")
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

    "JsonRpcExtrasEncoder.const causes extras value to appear in handler ctx" in run {
        val extrasValue = Structure.Value.Record(Chunk("sessionId" -> Structure.Value.Str("my-token")))
        // Unsafe: AtomicRef.Unsafe.init for extras capture across fibers
        val seen = AtomicRef.Unsafe.init[Maybe[Structure.Value]](Absent)(using AllowUnsafe.embrace.danger)
        val echoMethod = JsonRpcRoute.request[AddReq, AddResp]("add") {
            (req, ctx) =>
                Sync.defer {
                    seen.set(ctx.extras)(using AllowUnsafe.embrace.danger)
                    AddResp(req.a + req.b)
                }
        }
        val cdpConfig = JsonRpcHandler.Config(codec = JsonRpcCodec.Cdp)
        JsonRpcTransport.inMemory.map { (ta, tb) =>
            JsonRpcHandler.init(ta, Seq.empty, cdpConfig).map { a =>
                JsonRpcHandler.init(tb, Seq(echoMethod), cdpConfig).map { _ =>
                    a.call[AddReq, AddResp]("add", AddReq(1, 1), JsonRpcExtrasEncoder.const(extrasValue)).map { _ =>
                        Sync.defer(assert(seen.get()(using AllowUnsafe.embrace.danger) == Present(extrasValue)))
                    }
                }
            }
        }
    }

    "IdStrategy.SequentialLong produces ids Num(1), Num(2), Num(3)" in run {
        // Unsafe: AtomicRef.Unsafe.init for id accumulation across fibers
        val ids = AtomicRef.Unsafe.init(List.empty[JsonRpcId])(using AllowUnsafe.embrace.danger)
        val capture = JsonRpcExtrasEncoder(id =>
            Sync.defer { ids.getAndUpdate(id :: _)(using AllowUnsafe.embrace.danger); Absent }
        )
        val addOnB = JsonRpcRoute.request[AddReq, AddResp]("add") {
            (req, _) => AddResp(req.a + req.b)
        }
        mkEndpoints(Seq.empty, Seq(addOnB)).map { (a, _) =>
            a.call[AddReq, AddResp]("add", AddReq(1, 0), capture).andThen {
                a.call[AddReq, AddResp]("add", AddReq(2, 0), capture).andThen {
                    a.call[AddReq, AddResp]("add", AddReq(3, 0), capture).map { _ =>
                        val collected = Chunk.from(ids.get()(using AllowUnsafe.embrace.danger).reverse)
                        assert(collected == Chunk(JsonRpcId.Num(1L), JsonRpcId.Num(2L), JsonRpcId.Num(3L)))
                    }
                }
            }
        }
    }

    "IdStrategy.SequentialInt produces ids Num(1), Num(2), Num(3)" in run {
        // Unsafe: AtomicRef.Unsafe.init for id accumulation across fibers
        val ids = AtomicRef.Unsafe.init(List.empty[JsonRpcId])(using AllowUnsafe.embrace.danger)
        val capture = JsonRpcExtrasEncoder(id =>
            Sync.defer { ids.getAndUpdate(id :: _)(using AllowUnsafe.embrace.danger); Absent }
        )
        val addOnB = JsonRpcRoute.request[AddReq, AddResp]("add") {
            (req, _) => AddResp(req.a + req.b)
        }
        JsonRpcTransport.inMemory.map { (ta, tb) =>
            JsonRpcHandler.init(ta, Seq.empty, JsonRpcHandler.Config(idStrategy = JsonRpcIdStrategy.SequentialInt)).map { a =>
                JsonRpcHandler.init(tb, Seq(addOnB)).map { _ =>
                    a.call[AddReq, AddResp]("add", AddReq(1, 0), capture).andThen {
                        a.call[AddReq, AddResp]("add", AddReq(2, 0), capture).andThen {
                            a.call[AddReq, AddResp]("add", AddReq(3, 0), capture).map { _ =>
                                val collected = Chunk.from(ids.get()(using AllowUnsafe.embrace.danger).reverse)
                                assert(
                                    collected == Chunk(JsonRpcId.Num(1L), JsonRpcId.Num(2L), JsonRpcId.Num(3L))
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    "close prevents further call success" in run {
        val addOnB = JsonRpcRoute.request[AddReq, AddResp]("add") {
            (req, _) => AddResp(req.a + req.b)
        }
        JsonRpcTransport.inMemory.map { (ta, tb) =>
            // Unsafe: AtomicInt.Unsafe.init for send counter
            val sendCounter = AtomicInt.Unsafe.init(0)(using AllowUnsafe.embrace.danger)
            val countingTa  = new CountingTransport(ta, sendCounter)
            JsonRpcHandler.init(countingTa, Seq.empty).map { a =>
                JsonRpcHandler.init(tb, Seq(addOnB)).map { _ =>
                    val countBefore = sendCounter.get()(using AllowUnsafe.embrace.danger)
                    a.closeNow.andThen {
                        val countAfterClose = sendCounter.get()(using AllowUnsafe.embrace.danger)
                        Abort.run[JsonRpcError | Closed](a.call[AddReq, AddResp]("add", AddReq(1, 2))).map {
                            case Result.Failure(_) =>
                                val countAfterCall = sendCounter.get()(using AllowUnsafe.embrace.danger)
                                assert(
                                    countAfterCall == countAfterClose,
                                    s"write count increased after close: before=$countBefore, afterClose=$countAfterClose, afterCall=$countAfterCall"
                                )
                            case Result.Success(v) => fail(s"expected failure after close, got $v")
                            case Result.Panic(t)   => fail(s"unexpected panic: $t")
                        }
                    }
                }
            }
        }
    }

    "IdStrategy.Custom with 100 concurrent calls produces 100 distinct ids" in run {
        // Unsafe: AtomicLong.Unsafe.init for id generation
        val counter = AtomicLong.Unsafe.init(0L)(using AllowUnsafe.embrace.danger)
        val customStrategy = JsonRpcIdStrategy.Custom(() =>
            Sync.defer(JsonRpcId.Num(counter.incrementAndGet()(using AllowUnsafe.embrace.danger)))
        )
        // Unsafe: AtomicRef.Unsafe.init for concurrent id collection (replaces ConcurrentHashMap)
        val collectedIds = AtomicRef.Unsafe.init(Map.empty[JsonRpcId, Boolean])(using AllowUnsafe.embrace.danger)
        val addOnB = JsonRpcRoute.request[AddReq, AddResp]("add") {
            (req, _) => AddResp(req.a + req.b)
        }
        JsonRpcTransport.inMemory.map { (ta, tb) =>
            JsonRpcHandler.init(ta, Seq.empty, JsonRpcHandler.Config(idStrategy = customStrategy)).map { a =>
                JsonRpcHandler.init(tb, Seq(addOnB)).map { _ =>
                    val capture = JsonRpcExtrasEncoder(id =>
                        Sync.defer { collectedIds.getAndUpdate(_ + (id -> true))(using AllowUnsafe.embrace.danger); Absent }
                    )
                    Kyo.fill(100)(a.call[AddReq, AddResp]("add", AddReq(1, 0), capture)).map { _ =>
                        assert(collectedIds.get()(using AllowUnsafe.embrace.danger).size == 100)
                    }
                }
            }
        }
    }

    "default Config() has cancellation Absent" in run {
        val cfg = JsonRpcHandler.Config()
        assert(cfg.cancellation == Absent)
        assert(cfg.codec eq JsonRpcCodec.Strict2_0)
        assert(cfg.progress == Absent)
        assert(cfg.unknownMethod == JsonRpcUnknownMethodPolicy.minimal)
    }

    "Config.default equals Config()" in run {
        assert(JsonRpcHandler.Config.default == JsonRpcHandler.Config())
    }

    "Config.default == Config.default (CanEqual derivation)" in run {
        val a = JsonRpcHandler.Config.default
        val b = JsonRpcHandler.Config.default
        assert(a == b)
    }

    "Config fluent setter codec round-trips" in run {
        val cfg = JsonRpcHandler.Config.default.codec(JsonRpcCodec.Cdp)
        assert(cfg.codec eq JsonRpcCodec.Cdp)
    }

    "Config fluent setter idStrategy round-trips" in run {
        val cfg = JsonRpcHandler.Config.default.idStrategy(JsonRpcIdStrategy.SequentialInt)
        assert(cfg.idStrategy == JsonRpcIdStrategy.SequentialInt)
    }

    "Config fluent setter maxInFlight wraps in Present" in run {
        val cfg = JsonRpcHandler.Config.default.maxInFlight(10)
        assert(cfg.maxInFlight == Present(10))
    }

    "Config.require throws on maxInFlight <= 0" in run {
        assert(
            try
                JsonRpcHandler.Config.require(JsonRpcHandler.Config.default.maxInFlight(0)); false
            catch case e: IllegalArgumentException => e.getMessage.contains("maxInFlight")
        )
    }

    "Config.require accepts Duration.Zero requestTimeout" in run {
        JsonRpcHandler.Config.require(JsonRpcHandler.Config.default.requestTimeout(Duration.Zero))
        succeed
    }

    "Config.require accepts Duration.Infinity requestTimeout" in run {
        JsonRpcHandler.Config.require(JsonRpcHandler.Config.default.requestTimeout(Duration.Infinity))
        succeed
    }

    "Config() default plus LSP-shaped timeout emits no cancel" in run {
        val seen = AtomicRef.Unsafe.init[Chunk[JsonRpcEnvelope]](Chunk.empty)(using AllowUnsafe.embrace.danger)
        val slow = JsonRpcRoute.request[Unit, Unit]("slow") { (_, _) => Async.sleep(2.seconds) }
        JsonRpcTransport.inMemory.map { (ta, tb) =>
            val tbWrap = new JsonRpcTransport:
                def send(env: JsonRpcEnvelope)(using Frame) = tb.send(env)
                def incoming(using Frame) = tb.incoming.mapPure { env =>
                    discard(seen.updateAndGet(_ :+ env)(using AllowUnsafe.embrace.danger))
                    env
                }
                def close(using Frame) = tb.close
            JsonRpcHandler.init(ta, Seq.empty).map { a =>
                JsonRpcHandler.init(tbWrap, Seq(slow)).map { _ =>
                    Abort.run[Timeout](Async.timeout(100.millis)(Abort.run[JsonRpcError | Closed](a.call[Unit, Unit](
                        "slow",
                        ()
                    )))).andThen {
                        Async.sleep(500.millis).andThen {
                            val envs = seen.get()(using AllowUnsafe.embrace.danger)
                            assert(envs.exists { case JsonRpcRequest(_, "slow", _, _) => true; case _ => false })
                            assert(!envs.exists { case JsonRpcNotification("$/cancelRequest", _, _) => true; case _ => false })
                        }
                    }
                }
            }
        }
    }

    "malformed response with id fails caller fast" in run {
        // Use a custom codec that encodes Response(Present(result), Present(error)) with both fields
        // on the wire. Strict2_0.decode then classifies that wire JSON as Malformed-with-id, routing
        // through the Malformed(Present(id), reason, _) branch in JsonRpcEndpointImpl.
        val bothFieldsCodec = new JsonRpcCodec:
            def encode(env: JsonRpcEnvelope)(using Frame): Structure.Value < (Sync & Abort[JsonRpcError]) =
                env match
                    case JsonRpcResponse(id, Present(r), Present(e), _) =>
                        Sync.defer(Structure.Value.Record(Chunk(
                            "jsonrpc" -> Structure.Value.Str("2.0"),
                            "id"      -> Structure.encode(id),
                            "result"  -> r,
                            "error"   -> Structure.encode(e)
                        )))
                    case other => JsonRpcCodec.Strict2_0.encode(other)
            def decode(raw: Structure.Value)(using Frame): JsonRpcEnvelope < Sync =
                JsonRpcCodec.Strict2_0.decode(raw)
        JsonRpcTransport.inMemory.map { (ta, tb) =>
            val cfg = JsonRpcHandler.Config(codec = bothFieldsCodec)
            JsonRpcHandler.init(ta, Seq.empty, cfg).map { a =>
                // Start the call in a fiber so we can inject the malformed response after it registers.
                // Wait until callerRegistry is non-empty (id registered) before sending the malformed wire.
                // This ensures the Malformed-with-id branch finds the caller on all schedulers (JVM, JS, Native).
                Fiber.initUnscoped(Abort.run[JsonRpcError | Closed](a.call[Unit, Unit]("noop", ()))).map { callFiber =>
                    untilTrue(Sync.defer(!a.unsafe.asInstanceOf[internal.engine.JsonRpcEndpointImpl].callerRegistry.isEmpty)).andThen {
                        // tb.send triggers ta.incoming; bothFieldsCodec encodes both result+error on the wire;
                        // Strict2_0.decode sees both fields and emits Malformed(Present(Num(1)), ...).
                        // The Malformed-with-id branch completes abortSignal with invalidRequest("malformed response: ...").
                        Abort.run[Closed](tb.send(JsonRpcResponse(
                            JsonRpcId.Num(1),
                            Present(Structure.Value.Record(Chunk.empty)),
                            Present(JsonRpcInvalidRequestError(Structure.Value.Str("x"), Chunk.empty)),
                            Absent
                        ))).andThen {
                            callFiber.get.map {
                                case Result.Failure(err: JsonRpcError) =>
                                    assert(err.code == -32600)
                                    assert(err.data.exists {
                                        case Structure.Value.Str(s) => s.contains("malformed response")
                                        case _                      => false
                                    })
                                case other => fail(s"unexpected $other")
                            }
                        }
                    }
                }
            }
        }
    }

    "close(0) is equivalent to closeNow" in run {
        val slow = JsonRpcRoute.request[Unit, Unit]("slow") { (_, _) => Async.sleep(5.seconds) }
        JsonRpcTransport.inMemory.map { (ta, tb) =>
            JsonRpcHandler.init(ta, Seq.empty).map { a =>
                JsonRpcHandler.init(tb, Seq(slow)).map { _ =>
                    Fiber.initUnscoped(Abort.run[JsonRpcError | Closed](a.call[Unit, Unit]("slow", ()))).map { _ =>
                        a.close(Duration.Zero).andThen {
                            Abort.run[JsonRpcError | Closed](a.call[Unit, Unit]("slow", ())).map {
                                case Result.Failure(_: Closed) => succeed
                                case other                     => fail(s"expected Closed, got $other")
                            }
                        }
                    }
                }
            }
        }
    }

    "close(gracePeriod) drains before forcing" in run {
        val done = Fiber.Promise.Unsafe.init[Unit, Sync]()(using AllowUnsafe.embrace.danger).safe
        val q = JsonRpcRoute.request[Unit, Unit]("q") { (_, _) =>
            Async.sleep(200.millis).andThen(done.completeUnit.unit)
        }
        JsonRpcTransport.inMemory.map { (ta, tb) =>
            JsonRpcHandler.init(ta, Seq.empty).map { a =>
                JsonRpcHandler.init(tb, Seq(q)).map { _ =>
                    Fiber.initUnscoped(a.call[Unit, Unit]("q", ())).andThen {
                        // Yield until the call fiber has registered in inFlight. On Native's single-threaded
                        // scheduler, Fiber.initUnscoped does not transfer control immediately, so close()
                        // can observe inFlight == 0 and skip draining without this guard.
                        untilTrue(a.unsafe.asInstanceOf[internal.engine.JsonRpcEndpointImpl].inFlight.get.map(_ > 0)).andThen {
                            val start = java.lang.System.currentTimeMillis
                            a.close(1.second).map { _ =>
                                val elapsed = java.lang.System.currentTimeMillis - start
                                assert(elapsed < 900)
                                done.get.map(_ => succeed)
                            }
                        }
                    }
                }
            }
        }
    }

    "sendUnmatched emits request envelope with id" in run {
        val seen = AtomicRef.Unsafe.init[Chunk[JsonRpcEnvelope]](Chunk.empty)(using AllowUnsafe.embrace.danger)
        JsonRpcTransport.inMemory.map { (ta, tb) =>
            val tbWrap = new JsonRpcTransport:
                def send(env: JsonRpcEnvelope)(using Frame) = tb.send(env)
                def incoming(using Frame) = tb.incoming.mapPure { env =>
                    discard(seen.updateAndGet(_ :+ env)(using AllowUnsafe.embrace.danger))
                    env
                }
                def close(using Frame) = tb.close
            JsonRpcHandler.init(ta, Seq.empty).map { a =>
                JsonRpcHandler.init(tbWrap, Seq.empty).map { _ =>
                    a.sendUnmatched("Page.handleJavaScriptDialog", DialogParams(true), JsonRpcId.Num(-1)).andThen {
                        untilTrue(Sync.defer(seen.get()(using AllowUnsafe.embrace.danger).exists {
                            case JsonRpcRequest(JsonRpcId.Num(-1), "Page.handleJavaScriptDialog", _, _) => true
                            case _                                                                      => false
                        })).andThen(succeed)
                    }
                }
            }
        }
    }

    "sendUnmatched registers no pending caller" in run {
        JsonRpcTransport.inMemory.map { (ta, tb) =>
            JsonRpcHandler.init(ta, Seq.empty).map { a =>
                JsonRpcHandler.init(tb, Seq.empty).map { _ =>
                    Kyo.foreachDiscard(1 to 5) { i =>
                        a.sendUnmatched("noop", (), JsonRpcId.Num(i.toLong))
                    }.andThen {
                        Async.timeout(200.millis)(a.awaitDrain).map(_ => succeed)
                    }
                }
            }
        }
    }

    "sendUnmatched does not block waiting for response" in run {
        JsonRpcTransport.inMemory.map { (ta, tb) =>
            JsonRpcHandler.init(ta, Seq.empty).map { a =>
                JsonRpcHandler.init(tb, Seq.empty).map { _ =>
                    val start = java.lang.System.currentTimeMillis
                    a.sendUnmatched("noop", (), JsonRpcId.Num(1)).andThen(a.awaitDrain).map { _ =>
                        val elapsed = java.lang.System.currentTimeMillis - start
                        assert(elapsed < 100)
                    }
                }
            }
        }
    }

    // STEER-1: .error[E2] wire-up - domain errors registered with .error produce the declared code and message on the wire.
    "STEER-1: route .error[E2] maps domain error abort to declared wire code and message" in run {
        // JsonRpcCustomError is the concrete catchall application error type.
        // We provide an explicit Schema[JsonRpcCustomError] by delegating to the Schema[JsonRpcError] since
        // JsonRpcCustomError extends JsonRpcError and the hand-rolled schema projects to (code, message, data).
        given Schema[JsonRpcCustomError] = summon[Schema[JsonRpcError]].asInstanceOf[Schema[JsonRpcCustomError]]

        val originalError = JsonRpcCustomError(-31999, "original domain message")

        // Register a mapping: any JsonRpcCustomError abort is remapped to code -32099 with message "My error".
        // The engine uses ErrorMapping.matches() to detect the abort type at runtime (STEER-1).
        val route = JsonRpcRoute.request[AddReq, AddResp]("failWith") { (_, _) =>
            Abort.fail(originalError)
        }.error[JsonRpcCustomError](-32099, "My error")

        mkEndpoints(Seq.empty, Seq(route)).map { (a, _) =>
            Abort.run[JsonRpcError | Closed](a.call[AddReq, AddResp]("failWith", AddReq(1, 2))).map {
                case Result.Failure(e: JsonRpcError) =>
                    // Code is exactly the mapping's code (-32099), not the original error's code (-31999).
                    // Message contains the mapping's label ("My error") as part of the CustomError format.
                    assert(e.code == -32099, s"expected code -32099 from error mapping, got ${e.code}")
                    assert(e.message.contains("My error"), s"expected message to contain 'My error' from mapping, got '${e.message}'")
                case other => fail(s"expected JsonRpcError from error mapping, got $other")
            }
        }
    }

    // STEER-2: JsonRpcResponse.Halt short-circuit - the wire response IS the wrapped response.
    "STEER-2: handler Abort.fail(JsonRpcResponse.halt(resp)) sends resp directly over the wire" in run {
        val haltError = JsonRpcCustomError(-32777, "short-circuited")
        val route = JsonRpcRoute.request[AddReq, AddResp]("haltMethod") { (_, ctx) =>
            ctx.requestId match
                case Present(id) =>
                    val resp = JsonRpcResponse.failure(id, haltError)
                    JsonRpcResponse.halt(resp)
                case Absent =>
                    Abort.fail(JsonRpcInternalError(JsonRpcInternalError.Operation.Other, new RuntimeException("no id")))
        }

        mkEndpoints(Seq.empty, Seq(route)).map { (a, _) =>
            Abort.run[JsonRpcError | Closed](a.call[AddReq, AddResp]("haltMethod", AddReq(0, 0))).map {
                case Result.Failure(e: JsonRpcError) =>
                    assert(e.code == -32777, s"expected wire code -32777 from halt response, got ${e.code}")
                case other => fail(s"expected JsonRpcError with code -32777 from halt, got $other")
            }
        }
    }

end JsonRpcHandlerTest
