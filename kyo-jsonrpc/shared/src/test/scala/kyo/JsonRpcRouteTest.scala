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
        requestId: Maybe[JsonRpcEnvelope.Id],
        extras: Maybe[Structure.Value],
        progressSink: Maybe[Structure.Value => Unit < (Async & Abort[Closed])]
    ): JsonRpcRoute.Context < Sync =
        Fiber.Promise.init[Unit, Sync].map: p =>
            JsonRpcRoute.Context.forTest(p, requestId, extras, progressSink)

    "handler returns Out and result is encoded as Structure.Value" in run {
        makeCtx(Absent, Absent, Absent).flatMap: ctx =>
            val method = JsonRpcRoute[Int, String, Async & Abort[JsonRpcError]]("greet") {
                (n, _) => s"$n done"
            }
            val params = Structure.encode[Int](42)
            Abort.run[JsonRpcError](method.handle(params, ctx)).map: result =>
                assert(result == Result.Success(Structure.Value.Str("42 done")))
    }

    "handler Abort.fail propagates the failure without transformation" in run {
        makeCtx(Absent, Absent, Absent).flatMap: ctx =>
            val method = JsonRpcRoute[Int, String, Async & Abort[JsonRpcError]]("fail") {
                (_, _) => Abort.fail(JsonRpcError.InvalidParams)
            }
            val params = Structure.encode[Int](1)
            Abort.run[JsonRpcError](method.handle(params, ctx)).map: result =>
                assert(result == Result.Failure(JsonRpcError.InvalidParams))
    }

    "handler panic produces InternalError with panic message in data" in run {
        makeCtx(Absent, Absent, Absent).flatMap: ctx =>
            val method = JsonRpcRoute[Int, String, Async & Abort[JsonRpcError]]("boom") {
                (_, _) => Sync.defer(throw new RuntimeException("boom"))
            }
            val params = Structure.encode[Int](1)
            Abort.run[JsonRpcError](method.handle(params, ctx)).map: result =>
                result match
                    case Result.Failure(err) =>
                        assert(err.code == -32603)
                        assert(err.message == "Internal error")
                        assert(err.data == Present(Structure.Value.Str("boom")))
                    case other =>
                        fail(s"Expected Failure, got: $other")
    }

    "params decode failure produces invalidParams before the handler body runs" in run {
        makeCtx(Absent, Absent, Absent).flatMap: ctx =>
            var handlerCalled = false
            val method = JsonRpcRoute[Int, String, Async & Abort[JsonRpcError]]("typed") {
                (_, _) =>
                    handlerCalled = true
                    "ok"
            }
            val badParams = Structure.Value.Str("not an int")
            Abort.run[JsonRpcError](method.handle(badParams, ctx)).map: result =>
                result match
                    case Result.Failure(err) =>
                        assert(err.code == -32602)
                        assert(!handlerCalled)
                    case other =>
                        fail(s"Expected Failure, got: $other")
    }

    "notification method has Kind.Notification and handle returns Structure.Value.Null" in run {
        makeCtx(Absent, Absent, Absent).flatMap: ctx =>
            val method = JsonRpcRoute.notification[Int, Async & Abort[JsonRpcError]]("ping") {
                (_, _) => ()
            }
            assert(method.kind == JsonRpcRoute.Kind.Notification)
            val params = Structure.encode[Int](1)
            Abort.run[JsonRpcError](method.handle(params, ctx)).map: result =>
                assert(result == Result.Success(Structure.Value.Null))
    }

    "JsonRpcRoute.Context.extras is forwarded verbatim to the handler" in run {
        val extrasValue = Structure.Value.Record(Chunk("k" -> Structure.Value.Str("v")))
        makeCtx(Absent, Present(extrasValue), Absent).flatMap: ctx =>
            var observed: Maybe[Structure.Value] = Absent
            val method = JsonRpcRoute[Int, Unit, Async & Abort[JsonRpcError]]("obs") {
                (_, c) => observed = c.extras
            }
            val params = Structure.encode[Int](1)
            Abort.run[JsonRpcError](method.handle(params, ctx)).map: _ =>
                assert(observed == Present(extrasValue))
    }

    "ctx.progress with progressSink = Absent is a no-op" in run {
        makeCtx(Absent, Absent, Absent).flatMap: ctx =>
            Abort.run[Closed](ctx.progress(Structure.Value.Null)).map: result =>
                assert(result == Result.Success(()))
    }

    "two routes with same logic produce identical encoded output" in run {
        makeCtx(Absent, Absent, Absent).flatMap: ctx =>
            def handler(n: Int): String < (Async & Abort[JsonRpcError]) = s"result $n"
            val route1 = JsonRpcRoute[Int, String, Async & Abort[JsonRpcError]]("m")((n, _) => handler(n))
            val route2 = JsonRpcRoute[Int, String, Async & Abort[JsonRpcError]]("m")((n, _) => handler(n))
            val params = Structure.encode[Int](7)
            for
                r1 <- Abort.run[JsonRpcError](route1.handle(params, ctx))
                r2 <- Abort.run[JsonRpcError](route2.handle(params, ctx))
            yield assert(r1 == r2)
            end for
    }

    "dispatch known request returns Present" in run {
        val addM = JsonRpcRoute[AddReq, AddResp, Async & Abort[JsonRpcError]]("add") { (req, _) => AddResp(req.a + req.b) }
        Sync.defer(Structure.encode(AddReq(2, 3))).map { params =>
            val ctx =
                JsonRpcRoute.Context.forTest(
                    Fiber.Promise.Unsafe.init[Unit, Sync]()(using AllowUnsafe.embrace.danger).safe,
                    Absent,
                    Absent,
                    Absent
                )
            JsonRpcRoute.dispatch[Async & Abort[JsonRpcError]]("add", Seq(addM), params, ctx) match
                case Present(comp) =>
                    comp.map { sv =>
                        Structure.decode[AddResp](sv) match
                            case Result.Success(r) => assert(r == AddResp(5))
                            case other             => fail(s"decode failed $other")
                    }
                case Absent => fail("expected Present")
            end match
        }
    }

    "dispatch unknown name returns Absent" in run {
        val addM = JsonRpcRoute[AddReq, AddResp, Async & Abort[JsonRpcError]]("add") { (r, _) => AddResp(r.a + r.b) }
        val ctx = JsonRpcRoute.Context.forTest(
            Fiber.Promise.Unsafe.init[Unit, Sync]()(using AllowUnsafe.embrace.danger).safe,
            Absent,
            Absent,
            Absent
        )
        val result = JsonRpcRoute.dispatch[Async & Abort[JsonRpcError]]("missing", Seq(addM), Structure.Value.Null, ctx)
        assert(result == Absent)
    }

    "dispatch known notification returns Present Null" in run {
        val counter = AtomicInt.Unsafe.init(0)(using AllowUnsafe.embrace.danger)
        val logM = JsonRpcRoute.notification[LogMsg, Async & Abort[JsonRpcError]]("log") {
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
            JsonRpcRoute.dispatch[Async & Abort[JsonRpcError]]("log", Seq(logM), params, ctx) match
                case Present(comp) =>
                    comp.map { sv =>
                        assert(sv == Structure.Value.Null)
                        assert(counter.get()(using AllowUnsafe.embrace.danger) == 1)
                    }
                case Absent => fail("expected Present")
            end match
        }
    }

    "dispatch propagates handler Abort" in run {
        val m = JsonRpcRoute[Unit, Unit, Async & Abort[JsonRpcError]]("err") { (_, _) => Abort.fail(JsonRpcError.invalidParams("bad")) }
        Sync.defer(Structure.encode(())).map { params =>
            val ctx =
                JsonRpcRoute.Context.forTest(
                    Fiber.Promise.Unsafe.init[Unit, Sync]()(using AllowUnsafe.embrace.danger).safe,
                    Absent,
                    Absent,
                    Absent
                )
            JsonRpcRoute.dispatch[Async & Abort[JsonRpcError]]("err", Seq(m), params, ctx) match
                case Present(comp) =>
                    Abort.run[JsonRpcError](comp).map {
                        case Result.Failure(err) => assert(err.code == JsonRpcError.InvalidParams.code)
                        case other               => fail(s"expected Failure, got $other")
                    }
                case Absent => fail("expected Present")
            end match
        }
    }

end JsonRpcRouteTest
