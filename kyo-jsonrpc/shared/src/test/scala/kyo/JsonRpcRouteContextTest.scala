package kyo

import kyo.Maybe.Absent
import kyo.Maybe.Present

class JsonRpcRouteContextTest extends JsonRpcTest:

    given CanEqual[Any, Any] = CanEqual.canEqualAny

    "progress with a Present sink invokes the captured callback" in run {
        // Unsafe: AtomicRef.Unsafe.init for thread-safe capture outside effect context
        val captured = AtomicRef.Unsafe.init(List.empty[Structure.Value])(using AllowUnsafe.embrace.danger)
        val sink: Structure.Value => Unit < (Async & Abort[Closed]) =
            v =>
                Sync.defer {
                    captured.updateAndGet(_ :+ v)(using AllowUnsafe.embrace.danger)
                    ()
                }
        for
            promise <- Fiber.Promise.init[Unit, Sync]
            ctx = JsonRpcRoute.Context.forTest(promise, Present(JsonRpcId.Num(1L)), Absent, Present(sink))
            _ <- ctx.progress(Structure.Value.Str("p"))
            seen = captured.get()(using AllowUnsafe.embrace.danger)
        yield assert(seen == List(Structure.Value.Str("p")))
        end for
    }

    "progress with an Absent sink is a no-op" in run {
        for
            promise <- Fiber.Promise.init[Unit, Sync]
            ctx = JsonRpcRoute.Context.forTest(promise, Absent, Absent, Absent)
            _ <- ctx.progress(Structure.Value.Str("p"))
        yield succeed
    }

    "extras and requestId are surfaced verbatim from forTest" in run {
        val extras = Structure.Value.Str("opaque")
        for
            promise <- Fiber.Promise.init[Unit, Sync]
            ctx = JsonRpcRoute.Context.forTest(promise, Present(JsonRpcId.Str("rid")), Present(extras), Absent)
        yield
            assert(ctx.requestId == Present(JsonRpcId.Str("rid")))
            assert(ctx.extras == Present(extras))
        end for
    }

    "cancelled Promise is constructible and not yet completed at forTest exit" in run {
        for
            promise <- Fiber.Promise.init[Unit, Sync]
            ctx = JsonRpcRoute.Context.forTest(promise, Absent, Absent, Absent)
            done <- ctx.cancelled.done
        yield assert(!done)
    }

end JsonRpcRouteContextTest
