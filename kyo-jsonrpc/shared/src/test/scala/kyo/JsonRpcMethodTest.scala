package kyo

import kyo.Abort
import kyo.Async
import kyo.Chunk
import kyo.Closed
import kyo.Maybe
import kyo.Maybe.Absent
import kyo.Maybe.Present
import kyo.Result
import kyo.Structure
import kyo.Sync

class JsonRpcMethodTest extends Test:

    given CanEqual[Any, Any] = CanEqual.canEqualAny

    private def makeCtx(
        requestId: Maybe[JsonRpcId],
        extras: Maybe[Structure.Value],
        progressSink: Maybe[Structure.Value => Unit < (Async & Abort[Closed])]
    ): HandlerCtx < Sync =
        Fiber.Promise.init[Unit, Sync].map: p =>
            HandlerCtx.forTest(p, requestId, extras, progressSink)

    "handler returns Out and result is encoded as Structure.Value" in run {
        makeCtx(Absent, Absent, Absent).flatMap: ctx =>
            val method = JsonRpcMethod[Int, String, Async & Abort[JsonRpcError]]("greet") {
                (n, _) => s"$n done"
            }
            val params = Structure.encode[Int](42)
            Abort.run[JsonRpcError](method.handle(params, ctx)).map: result =>
                assert(result == Result.Success(Structure.Value.Str("42 done")))
    }

    "handler Abort.fail propagates the failure without transformation" in run {
        makeCtx(Absent, Absent, Absent).flatMap: ctx =>
            val method = JsonRpcMethod[Int, String, Async & Abort[JsonRpcError]]("fail") {
                (_, _) => Abort.fail(JsonRpcError.InvalidParams)
            }
            val params = Structure.encode[Int](1)
            Abort.run[JsonRpcError](method.handle(params, ctx)).map: result =>
                assert(result == Result.Failure(JsonRpcError.InvalidParams))
    }

    "handler panic produces InternalError with panic message in data" in run {
        makeCtx(Absent, Absent, Absent).flatMap: ctx =>
            val method = JsonRpcMethod[Int, String, Async & Abort[JsonRpcError]]("boom") {
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
            val method = JsonRpcMethod[Int, String, Async & Abort[JsonRpcError]]("typed") {
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
            val method = JsonRpcMethod.notification[Int, Async & Abort[JsonRpcError]]("ping") {
                (_, _) => ()
            }
            assert(method.kind == JsonRpcMethod.Kind.Notification)
            val params = Structure.encode[Int](1)
            Abort.run[JsonRpcError](method.handle(params, ctx)).map: result =>
                assert(result == Result.Success(Structure.Value.Null))
    }

    "HandlerCtx.extras is forwarded verbatim to the handler" in run {
        val extrasValue = Structure.Value.Record(Chunk("k" -> Structure.Value.Str("v")))
        makeCtx(Absent, Present(extrasValue), Absent).flatMap: ctx =>
            var observed: Maybe[Structure.Value] = Absent
            val method = JsonRpcMethod[Int, Unit, Async & Abort[JsonRpcError]]("obs") {
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

    "no-ctx overload produces identical encoded output as ctx overload ignoring ctx" in run {
        makeCtx(Absent, Absent, Absent).flatMap: ctx =>
            def handler(n: Int): String < (Async & Abort[JsonRpcError]) = s"result $n"
            val withCtx    = JsonRpcMethod[Int, String, Async & Abort[JsonRpcError]]("m")((n, _) => handler(n))
            val withoutCtx = JsonRpcMethod[Int, String, Async & Abort[JsonRpcError]]("m")(handler)
            val params     = Structure.encode[Int](7)
            for
                r1 <- Abort.run[JsonRpcError](withCtx.handle(params, ctx))
                r2 <- Abort.run[JsonRpcError](withoutCtx.handle(params, ctx))
            yield assert(r1 == r2)
            end for
    }

end JsonRpcMethodTest
