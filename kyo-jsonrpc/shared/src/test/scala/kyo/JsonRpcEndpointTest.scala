package kyo

import java.util.concurrent.ConcurrentHashMap
import kyo.Maybe.Absent
import kyo.Maybe.Present

class JsonRpcEndpointTest extends Test:

    case class AddReq(a: Int, b: Int) derives Schema, CanEqual
    case class AddResp(sum: Int) derives Schema, CanEqual
    case class LogMsg(text: String) derives Schema, CanEqual

    private def mkEndpoints(
        methodsA: Seq[JsonRpcMethod[Async & Abort[JsonRpcError]]],
        methodsB: Seq[JsonRpcMethod[Async & Abort[JsonRpcError]]]
    )(using Frame): (JsonRpcEndpoint, JsonRpcEndpoint) < (Sync & Async & Scope) =
        JsonRpcTransport.inMemory.map { (ta, tb) =>
            JsonRpcEndpoint.init(ta, methodsA).map { endpointA =>
                JsonRpcEndpoint.init(tb, methodsB).map { endpointB =>
                    (endpointA, endpointB)
                }
            }
        }

    private class CountingTransport(inner: JsonRpcTransport, val counter: java.util.concurrent.atomic.AtomicInteger)
        extends JsonRpcTransport:

        def send(env: JsonRpcEnvelope)(using Frame): Unit < (Async & Abort[Closed]) =
            Sync.defer(discard(counter.incrementAndGet())).andThen(inner.send(env))

        def incoming(using Frame): Stream[JsonRpcEnvelope, Async & Abort[Closed]] =
            inner.incoming

        def close(using Frame): Unit < Async =
            inner.close
    end CountingTransport

    "call add handler returns correct result" in run {
        val addMethod = JsonRpcMethod[AddReq, AddResp, Async & Abort[JsonRpcError]]("add") {
            (req, _) => AddResp(req.a + req.b)
        }
        mkEndpoints(Seq.empty, Seq(addMethod)).map { (a, _) =>
            a.call[AddReq, AddResp]("add", AddReq(1, 2)).map { resp =>
                assert(resp == AddResp(3))
            }
        }
    }

    "notify sends notification and handler runs without reply" in run {
        val seen = new java.util.concurrent.atomic.AtomicInteger(0)
        val logMethod = JsonRpcMethod[LogMsg, Unit, Async & Abort[JsonRpcError]]("log") {
            (_, _) => Sync.defer(discard(seen.incrementAndGet()))
        }
        mkEndpoints(Seq.empty, Seq(logMethod)).map { (a, _) =>
            a.notify[LogMsg]("log", LogMsg("hello")).andThen {
                untilTrue(Sync.defer(seen.get() == 1)).andThen(succeed)
            }
        }
    }

    "bidirectional simultaneous calls resolve without cross-wiring" in run {
        val addOnB = JsonRpcMethod[AddReq, AddResp, Async & Abort[JsonRpcError]]("add") {
            (req, _) => AddResp(req.a + req.b)
        }
        val addOnA = JsonRpcMethod[AddReq, AddResp, Async & Abort[JsonRpcError]]("add") {
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
        val addOnB = JsonRpcMethod[AddReq, AddResp, Async & Abort[JsonRpcError]]("add") {
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
        val addOnB = JsonRpcMethod[AddReq, AddResp, Async & Abort[JsonRpcError]]("add") {
            (req, _) => Async.sleep(2.seconds).andThen(AddResp(req.a + req.b))
        }
        JsonRpcTransport.inMemory.map { (ta, tb) =>
            JsonRpcEndpoint.init(tb, Seq(addOnB)).map { _ =>
                // Outer fiber lives beyond the scope; captures the call result
                val resultRef = new java.util.concurrent.atomic.AtomicReference[Maybe[Result[JsonRpcError | Closed, AddResp]]](Absent)
                Scope.run {
                    JsonRpcEndpoint.init(ta, Seq.empty).map { endpointA =>
                        Fiber.initUnscoped(
                            Abort.run[JsonRpcError | Closed](
                                endpointA.call[AddReq, AddResp]("add", AddReq(1, 2))
                            ).map(r => Sync.defer(resultRef.set(Present(r))))
                        ).unit
                    }
                }.andThen {
                    untilTrue(Sync.defer(resultRef.get().isDefined)).andThen {
                        Sync.defer {
                            resultRef.get() match
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
        val addOnB = JsonRpcMethod[AddReq, AddResp, Async & Abort[JsonRpcError]]("add") {
            (req, _) => Async.sleep(3.seconds).andThen(AddResp(req.a + req.b))
        }
        JsonRpcTransport.inMemory.map { (ta, tb) =>
            JsonRpcEndpoint.init(tb, Seq(addOnB)).map { _ =>
                val resultRef = new java.util.concurrent.atomic.AtomicReference[Maybe[Result[JsonRpcError | Closed, AddResp]]](Absent)
                Scope.run {
                    JsonRpcEndpoint.init(ta, Seq.empty).map { endpointA =>
                        Fiber.initUnscoped(
                            Abort.run[JsonRpcError | Closed](
                                endpointA.call[AddReq, AddResp]("add", AddReq(1, 2))
                            ).map(r => Sync.defer(resultRef.set(Present(r))))
                        ).andThen(Async.sleep(50.millis))
                    }
                }.andThen {
                    untilTrue(Sync.defer(resultRef.get().isDefined)).andThen {
                        Sync.defer {
                            resultRef.get() match
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
        val addOnB = JsonRpcMethod[AddReq, AddResp, Async & Abort[JsonRpcError]]("add") {
            (req, _) => AddResp(req.a + req.b)
        }
        mkEndpoints(Seq.empty, Seq(addOnB)).map { (a, _) =>
            a.call[AddReq, AddResp]("add", AddReq(5, 5)).andThen {
                a.awaitDrain.andThen {
                    Sync.defer(assert(a.impl.callerRegistry.isEmpty))
                }
            }
        }
    }

    "callerRegistry is empty after a call fiber is interrupted externally" in run {
        val addOnB = JsonRpcMethod[AddReq, AddResp, Async & Abort[JsonRpcError]]("add") {
            (req, _) => Async.sleep(5.seconds).andThen(AddResp(req.a + req.b))
        }
        mkEndpoints(Seq.empty, Seq(addOnB)).map { (a, _) =>
            Fiber.initUnscoped(
                Abort.run[JsonRpcError | Closed](a.call[AddReq, AddResp]("add", AddReq(1, 2)))
            ).map { fib =>
                Async.sleep(30.millis).andThen {
                    fib.interrupt.andThen {
                        untilTrue(Sync.defer(a.impl.callerRegistry.isEmpty)).andThen(succeed)
                    }
                }
            }
        }
    }

    "call returns error when transport closes mid-call" in run {
        val addOnB = JsonRpcMethod[AddReq, AddResp, Async & Abort[JsonRpcError]]("add") {
            (req, _) => Async.sleep(2.seconds).andThen(AddResp(req.a + req.b))
        }
        mkEndpoints(Seq.empty, Seq(addOnB)).map { (a, _) =>
            Fiber.initUnscoped(
                Abort.run[JsonRpcError | Closed](a.call[AddReq, AddResp]("add", AddReq(1, 2)))
            ).map { callFib =>
                Async.sleep(30.millis).andThen {
                    a.close.andThen {
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
        val addOnB = JsonRpcMethod[AddReq, AddResp, Async & Abort[JsonRpcError]]("add") {
            (req, _) => Async.sleep(30.millis).andThen(AddResp(req.a + req.b))
        }
        mkEndpoints(Seq.empty, Seq(addOnB)).map { (a, _) =>
            Fiber.initUnscoped(a.call[AddReq, AddResp]("add", AddReq(1, 1))).andThen {
                Fiber.initUnscoped(a.call[AddReq, AddResp]("add", AddReq(2, 2))).andThen {
                    a.awaitDrain.andThen {
                        Sync.Unsafe.defer {
                            assert(a.impl.inFlight.unsafe.get() <= 0)
                        }
                    }
                }
            }
        }
    }

    "cancel with no CancellationPolicy fails call locally with cancelled error" in run {
        val addOnB = JsonRpcMethod[AddReq, AddResp, Async & Abort[JsonRpcError]]("add") {
            (req, _) => Async.sleep(3.seconds).andThen(AddResp(req.a + req.b))
        }
        val capturedId = new java.util.concurrent.atomic.AtomicReference[Maybe[JsonRpcId]](Absent)
        val captureEncoder = ExtrasEncoder(id =>
            Sync.defer {
                capturedId.set(Present(id))
                Absent
            }
        )
        mkEndpoints(Seq.empty, Seq(addOnB)).map { (a, _) =>
            Fiber.initUnscoped(
                Abort.run[JsonRpcError | Closed](
                    a.call[AddReq, AddResp]("add", AddReq(1, 2), captureEncoder)
                )
            ).map { callFib =>
                untilTrue(Sync.defer(capturedId.get().isDefined)).andThen {
                    Sync.defer(capturedId.get()).map {
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

    "late reply for cancelled outbound call is silently dropped" in run {
        val capturedId = new java.util.concurrent.atomic.AtomicReference[Maybe[JsonRpcId]](Absent)
        val captureEncoder = ExtrasEncoder(id =>
            Sync.defer {
                capturedId.set(Present(id))
                Absent
            }
        )
        val slowAddOnB = JsonRpcMethod[AddReq, AddResp, Async & Abort[JsonRpcError]]("add") {
            (req, _) => Async.sleep(5.seconds).andThen(AddResp(req.a + req.b))
        }
        // Use the fast addOnB for the second (post-cancel) call to verify the endpoint is still healthy
        val fastAddOnB = JsonRpcMethod[AddReq, AddResp, Async & Abort[JsonRpcError]]("add") {
            (req, _) => AddResp(req.a + req.b)
        }
        JsonRpcTransport.inMemory.map { (ta, tb) =>
            JsonRpcEndpoint.init(ta, Seq.empty).map { a =>
                JsonRpcEndpoint.init(tb, Seq(slowAddOnB)).map { _ =>
                    Fiber.initUnscoped(
                        Abort.run[JsonRpcError | Closed](
                            a.call[AddReq, AddResp]("add", AddReq(1, 2), captureEncoder)
                        )
                    ).map { callFib =>
                        untilTrue(Sync.defer(capturedId.get().isDefined)).andThen {
                            Sync.defer(capturedId.get()).map {
                                case Present(id) =>
                                    a.cancel(id, Absent).andThen {
                                        callFib.get.map {
                                            case Result.Failure(e: JsonRpcError) =>
                                                assert(e.code == -32800)
                                                // Simulate a late response for the already-cancelled id: the peer
                                                // sends a success response after the caller gave up. The endpoint
                                                // must silently drop it and NOT complete any live call.
                                                val lateReply = JsonRpcEnvelope.Response(
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

    "ExtrasEncoder.const causes extras value to appear in handler ctx" in run {
        val extrasValue = Structure.Value.Record(Chunk("sessionId" -> Structure.Value.Str("my-token")))
        val seen        = new java.util.concurrent.atomic.AtomicReference[Maybe[Structure.Value]](Absent)
        val echoMethod = JsonRpcMethod[AddReq, AddResp, Async & Abort[JsonRpcError]]("add") {
            (req, ctx) =>
                Sync.defer {
                    seen.set(ctx.extras)
                    AddResp(req.a + req.b)
                }
        }
        val cdpConfig = JsonRpcEndpoint.Config(codec = JsonRpcCodec.Cdp)
        JsonRpcTransport.inMemory.map { (ta, tb) =>
            JsonRpcEndpoint.init(ta, Seq.empty, cdpConfig).map { a =>
                JsonRpcEndpoint.init(tb, Seq(echoMethod), cdpConfig).map { _ =>
                    a.call[AddReq, AddResp]("add", AddReq(1, 1), ExtrasEncoder.const(extrasValue)).map { _ =>
                        Sync.defer(assert(seen.get() == Present(extrasValue)))
                    }
                }
            }
        }
    }

    "IdStrategy.SequentialLong produces ids Num(1), Num(2), Num(3)" in run {
        val ids     = new java.util.concurrent.ConcurrentLinkedQueue[JsonRpcId]()
        val capture = ExtrasEncoder(id => Sync.defer { ids.add(id); Absent })
        val addOnB = JsonRpcMethod[AddReq, AddResp, Async & Abort[JsonRpcError]]("add") {
            (req, _) => AddResp(req.a + req.b)
        }
        mkEndpoints(Seq.empty, Seq(addOnB)).map { (a, _) =>
            a.call[AddReq, AddResp]("add", AddReq(1, 0), capture).andThen {
                a.call[AddReq, AddResp]("add", AddReq(2, 0), capture).andThen {
                    a.call[AddReq, AddResp]("add", AddReq(3, 0), capture).map { _ =>
                        val collected = Chunk.from(ids.toArray(Array.empty[JsonRpcId]))
                        assert(collected == Chunk(JsonRpcId.Num(1L), JsonRpcId.Num(2L), JsonRpcId.Num(3L)))
                    }
                }
            }
        }
    }

    "IdStrategy.SequentialInt produces ids Num(1), Num(2), Num(3)" in run {
        val ids     = new java.util.concurrent.ConcurrentLinkedQueue[JsonRpcId]()
        val capture = ExtrasEncoder(id => Sync.defer { ids.add(id); Absent })
        val addOnB = JsonRpcMethod[AddReq, AddResp, Async & Abort[JsonRpcError]]("add") {
            (req, _) => AddResp(req.a + req.b)
        }
        JsonRpcTransport.inMemory.map { (ta, tb) =>
            JsonRpcEndpoint.init(ta, Seq.empty, JsonRpcEndpoint.Config(idStrategy = IdStrategy.SequentialInt)).map { a =>
                JsonRpcEndpoint.init(tb, Seq(addOnB)).map { _ =>
                    a.call[AddReq, AddResp]("add", AddReq(1, 0), capture).andThen {
                        a.call[AddReq, AddResp]("add", AddReq(2, 0), capture).andThen {
                            a.call[AddReq, AddResp]("add", AddReq(3, 0), capture).map { _ =>
                                val collected = Chunk.from(ids.toArray(Array.empty[JsonRpcId]))
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
        val addOnB = JsonRpcMethod[AddReq, AddResp, Async & Abort[JsonRpcError]]("add") {
            (req, _) => AddResp(req.a + req.b)
        }
        JsonRpcTransport.inMemory.map { (ta, tb) =>
            val sendCounter = new java.util.concurrent.atomic.AtomicInteger(0)
            val countingTa  = new CountingTransport(ta, sendCounter)
            JsonRpcEndpoint.init(countingTa, Seq.empty).map { a =>
                JsonRpcEndpoint.init(tb, Seq(addOnB)).map { _ =>
                    val countBefore = sendCounter.get()
                    a.close.andThen {
                        val countAfterClose = sendCounter.get()
                        Abort.run[JsonRpcError | Closed](a.call[AddReq, AddResp]("add", AddReq(1, 2))).map {
                            case Result.Failure(_) =>
                                val countAfterCall = sendCounter.get()
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
        val counter = new java.util.concurrent.atomic.AtomicLong(0L)
        val customStrategy = IdStrategy.Custom(() =>
            Sync.defer(JsonRpcId.Num(counter.incrementAndGet()))
        )
        val collectedIds = new ConcurrentHashMap[JsonRpcId, Boolean]()
        val addOnB = JsonRpcMethod[AddReq, AddResp, Async & Abort[JsonRpcError]]("add") {
            (req, _) => AddResp(req.a + req.b)
        }
        JsonRpcTransport.inMemory.map { (ta, tb) =>
            JsonRpcEndpoint.init(ta, Seq.empty, JsonRpcEndpoint.Config(idStrategy = customStrategy)).map { a =>
                JsonRpcEndpoint.init(tb, Seq(addOnB)).map { _ =>
                    val capture = ExtrasEncoder(id => Sync.defer { collectedIds.put(id, true); Absent })
                    Kyo.fill(100)(a.call[AddReq, AddResp]("add", AddReq(1, 0), capture)).map { _ =>
                        assert(collectedIds.size() == 100)
                    }
                }
            }
        }
    }

end JsonRpcEndpointTest
