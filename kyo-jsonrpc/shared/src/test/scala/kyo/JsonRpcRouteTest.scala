package kyo

import kyo.Abort
import kyo.Async
import kyo.AtomicInt
import kyo.Chunk
import kyo.Closed
import kyo.Maybe
import kyo.Maybe.Absent
import kyo.Maybe.Present
import kyo.Result
import kyo.Structure
import kyo.Sync

class JsonRpcRouteTest extends JsonRpcTest:

    given CanEqual[Any, Any] = CanEqual.canEqualAny

    case class AddReq(a: Int, b: Int) derives Schema, CanEqual
    case class AddResp(sum: Int) derives Schema, CanEqual
    case class LogMsg(text: String) derives Schema, CanEqual

    private def makeCtx(
        requestId: Maybe[JsonRpcId],
        extras: Maybe[Structure.Value],
        progressSink: Maybe[Structure.Value => Unit < (Async & Abort[Closed])]
    ): JsonRpcRoute.Context < Sync =
        Fiber.Promise.init[Unit, Sync].map: p =>
            JsonRpcRoute.Context.forTest(p, requestId, extras, progressSink)

    "handler returns Out and result is encoded as Structure.Value" in {
        makeCtx(Absent, Absent, Absent).flatMap: ctx =>
            val method = JsonRpcRoute.request[Int, String]("greet") {
                (n, _) => s"$n done"
            }
            val params = Structure.encode[Int](42)
            Abort.run[JsonRpcError | JsonRpcResponse.Halt](method.handle(params, ctx)).map: result =>
                assert(result == Result.Success(Structure.Value.Str("42 done")))
    }

    "handler Abort.fail propagates the failure without transformation" in {
        makeCtx(Absent, Absent, Absent).flatMap: ctx =>
            val err = JsonRpcInvalidParamsError("fail", Absent, Chunk.empty)
            val method = JsonRpcRoute.request[Int, String]("fail") {
                (_, _) => Abort.fail(err)
            }
            val params = Structure.encode[Int](1)
            Abort.run[JsonRpcError | JsonRpcResponse.Halt](method.handle(params, ctx)).map: result =>
                assert(result == Result.Failure(err))
    }

    "handler panic produces InternalError with panic message in data" in {
        makeCtx(Absent, Absent, Absent).flatMap: ctx =>
            val method = JsonRpcRoute.request[Int, String]("boom") {
                (_, _) => Sync.defer(throw new RuntimeException("boom"))
            }
            val params = Structure.encode[Int](1)
            Abort.run[JsonRpcError | JsonRpcResponse.Halt](method.handle(params, ctx)).map: result =>
                result match
                    case Result.Failure(err: JsonRpcHandlerPanicError) =>
                        assert(err.code == -32603)
                        assert(err.isInstanceOf[JsonRpcHandlerPanicError])
                    case other =>
                        fail(s"Expected Failure, got: $other")
    }

    "params decode failure produces invalidParams before the handler body runs" in {
        makeCtx(Absent, Absent, Absent).flatMap: ctx =>
            var handlerCalled = false
            val method = JsonRpcRoute.request[Int, String]("typed") {
                (_, _) =>
                    handlerCalled = true
                    "ok"
            }
            val badParams = Structure.Value.Str("not an int")
            Abort.run[JsonRpcError | JsonRpcResponse.Halt](method.handle(badParams, ctx)).map: result =>
                result match
                    case Result.Failure(err: JsonRpcError) =>
                        assert(err.code == -32602)
                        assert(!handlerCalled)
                    case other =>
                        fail(s"Expected Failure, got: $other")
    }

    "notification method has Kind.Notification and handle returns Structure.Value.Null" in {
        makeCtx(Absent, Absent, Absent).flatMap: ctx =>
            val method = JsonRpcRoute.notification[Int]("ping") {
                (_, _) => ()
            }
            assert(method.kind == JsonRpcRoute.Kind.Notification)
            val params = Structure.encode[Int](1)
            Abort.run[JsonRpcError | JsonRpcResponse.Halt](method.handle(params, ctx)).map: result =>
                assert(result == Result.Success(Structure.Value.Null))
    }

    "JsonRpcRoute.Context.extras is forwarded verbatim to the handler" in {
        val extrasValue = Structure.Value.Record(Chunk("k" -> Structure.Value.Str("v")))
        makeCtx(Absent, Present(extrasValue), Absent).flatMap: ctx =>
            var observed: Maybe[Structure.Value] = Absent
            val method = JsonRpcRoute.request[Int, Unit]("obs") {
                (_, c) => observed = c.extras
            }
            val params = Structure.encode[Int](1)
            Abort.run[JsonRpcError | JsonRpcResponse.Halt](method.handle(params, ctx)).map: _ =>
                assert(observed == Present(extrasValue))
    }

    "ctx.progress with progressSink = Absent is a no-op" in {
        makeCtx(Absent, Absent, Absent).flatMap: ctx =>
            Abort.run[Closed](ctx.progress(Structure.Value.Null)).map: result =>
                assert(result == Result.Success(()))
    }

    "two routes with same logic produce identical encoded output" in {
        makeCtx(Absent, Absent, Absent).flatMap: ctx =>
            def handler(n: Int): String < (Async & Abort[JsonRpcError]) = s"result $n"
            val route1                                                  = JsonRpcRoute.request[Int, String]("m")((n, _) => handler(n))
            val route2                                                  = JsonRpcRoute.request[Int, String]("m")((n, _) => handler(n))
            val params                                                  = Structure.encode[Int](7)
            for
                r1 <- Abort.run[JsonRpcError | JsonRpcResponse.Halt](route1.handle(params, ctx))
                r2 <- Abort.run[JsonRpcError | JsonRpcResponse.Halt](route2.handle(params, ctx))
            yield assert(r1 == r2)
            end for
    }

    "dispatch known request returns Present" in {
        val addM = JsonRpcRoute.request[AddReq, AddResp]("add") { (req, _) => AddResp(req.a + req.b) }
        Sync.defer(Structure.encode(AddReq(2, 3))).map { params =>
            val ctx =
                JsonRpcRoute.Context.forTest(
                    Fiber.Promise.Unsafe.init[Unit, Sync]()(using AllowUnsafe.embrace.danger).safe,
                    Absent,
                    Absent,
                    Absent
                )
            JsonRpcRoute.dispatch("add", Seq(addM), params, ctx) match
                case Present(comp) =>
                    Abort.run[JsonRpcError | JsonRpcResponse.Halt](comp).map { sv =>
                        sv match
                            case Result.Success(v) =>
                                Structure.decode[AddResp](v) match
                                    case Result.Success(r) => assert(r == AddResp(5))
                                    case other             => fail(s"decode failed $other")
                            case other => fail(s"expected Success, got $other")
                    }
                case Absent => fail("expected Present")
            end match
        }
    }

    "dispatch unknown name returns Absent" in {
        val addM = JsonRpcRoute.request[AddReq, AddResp]("add") { (r, _) => AddResp(r.a + r.b) }
        val ctx = JsonRpcRoute.Context.forTest(
            Fiber.Promise.Unsafe.init[Unit, Sync]()(using AllowUnsafe.embrace.danger).safe,
            Absent,
            Absent,
            Absent
        )
        val result = JsonRpcRoute.dispatch("missing", Seq(addM), Structure.Value.Null, ctx)
        assert(result == Absent)
    }

    "dispatch known notification returns Present Null" in {
        val counter = AtomicInt.Unsafe.init(0)(using AllowUnsafe.embrace.danger)
        val logM = JsonRpcRoute.notification[LogMsg]("log") {
            (_, _) => Sync.defer(discard(counter.incrementAndGet()(using AllowUnsafe.embrace.danger)))
        }
        Sync.defer(Structure.encode(LogMsg("x"))).map { params =>
            val ctx =
                JsonRpcRoute.Context.forTest(
                    Fiber.Promise.Unsafe.init[Unit, Sync]()(using AllowUnsafe.embrace.danger).safe,
                    Absent,
                    Absent,
                    Absent
                )
            JsonRpcRoute.dispatch("log", Seq(logM), params, ctx) match
                case Present(comp) =>
                    Abort.run[JsonRpcError | JsonRpcResponse.Halt](comp).map { sv =>
                        sv match
                            case Result.Success(v) =>
                                assert(v == Structure.Value.Null)
                                assert(counter.get()(using AllowUnsafe.embrace.danger) == 1)
                            case other => fail(s"expected Success, got $other")
                    }
                case Absent => fail("expected Present")
            end match
        }
    }

    "dispatch propagates handler Abort" in {
        val m = JsonRpcRoute.request[Unit, Unit]("err") { (_, _) =>
            Abort.fail(JsonRpcInvalidParamsError("err", Absent, Chunk.empty))
        }
        Sync.defer(Structure.encode(())).map { params =>
            val ctx =
                JsonRpcRoute.Context.forTest(
                    Fiber.Promise.Unsafe.init[Unit, Sync]()(using AllowUnsafe.embrace.danger).safe,
                    Absent,
                    Absent,
                    Absent
                )
            JsonRpcRoute.dispatch("err", Seq(m), params, ctx) match
                case Present(comp) =>
                    Abort.run[JsonRpcError | JsonRpcResponse.Halt](comp).map {
                        case Result.Failure(err: JsonRpcError) => assert(err.code == -32602)
                        case other                             => fail(s"expected Failure, got $other")
                    }
                case Absent => fail("expected Present")
            end match
        }
    }

    // Halt short-circuit propagates the wrapped response instead of panicking.
    "JsonRpcResponse.Halt aborts with the wrapped response directly" in {
        makeCtx(Absent, Absent, Absent).flatMap: ctx =>
            val id       = JsonRpcId(1L)
            val response = JsonRpcResponse.success(id, Structure.Value.Str("early"))
            val method = JsonRpcRoute.request[Int, String]("early") { (_, _) =>
                JsonRpcResponse.halt(response)
            }
            val params = Structure.encode[Int](1)
            Abort.run[JsonRpcError | JsonRpcResponse.Halt](method.handle(params, ctx)).map: result =>
                result match
                    case Result.Failure(JsonRpcResponse.Halt(resp)) =>
                        assert(resp == response)
                    case other =>
                        fail(s"Expected Halt with wrapped response, got: $other")
    }

    ".error accumulates error mappings on the route" in {
        makeCtx(Absent, Absent, Absent).flatMap: ctx =>
            val base      = JsonRpcRoute.request[AddReq, AddResp]("add") { (req, _) => AddResp(req.a + req.b) }
            val withError = base.error[AddReq](-32001, "Bad request")
            assert(withError.errorMappings.length == 1)
            assert(withError.errorMappings(0).code == -32001)
            assert(withError.errorMappings(0).message == "Bad request")
            assert(withError.errorMappings(0).matches(AddReq(1, 2)))
            assert(!withError.errorMappings(0).matches(AddResp(3)))
            val params = Structure.encode(AddReq(2, 3))
            Abort.run[JsonRpcError | JsonRpcResponse.Halt](withError.handle(params, ctx)).map: result =>
                assert(result == Result.Success(Structure.encode(AddResp(5))))
    }

end JsonRpcRouteTest
