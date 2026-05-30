package kyo

import kyo.internal.engine.IdStrategyEngine

class JsonRpcHandlerIdStrategyTest extends JsonRpcTest:

    given CanEqual[Any, Any] = CanEqual.canEqualAny

    "SequentialLong allocates monotonically increasing JsonRpcEnvelope.Id.Num starting at 1" in run {
        val next = IdStrategyEngine.mkNextId(JsonRpcHandler.IdStrategy.SequentialLong)
        for
            a <- next()
            b <- next()
            c <- next()
        yield
            assert(a == JsonRpcEnvelope.Id.Num(1L))
            assert(b == JsonRpcEnvelope.Id.Num(2L))
            assert(c == JsonRpcEnvelope.Id.Num(3L))
        end for
    }

    "SequentialInt allocates monotonically increasing JsonRpcEnvelope.Id.Num starting at 1" in run {
        val next = IdStrategyEngine.mkNextId(JsonRpcHandler.IdStrategy.SequentialInt)
        for
            a <- next()
            b <- next()
        yield
            assert(a == JsonRpcEnvelope.Id.Num(1L))
            assert(b == JsonRpcEnvelope.Id.Num(2L))
        end for
    }

    "Custom forwards verbatim to the supplied next function" in run {
        // Unsafe: AtomicLong.Unsafe.init for in-test counter outside effect context
        val counter = AtomicLong.Unsafe.init(99L)(using AllowUnsafe.embrace.danger)
        val custom: () => JsonRpcEnvelope.Id < Sync =
            () => Sync.Unsafe.defer(JsonRpcEnvelope.Id.Num(counter.incrementAndGet()))
        val next = IdStrategyEngine.mkNextId(JsonRpcHandler.IdStrategy.Custom(custom))
        for
            a <- next()
            b <- next()
        yield
            assert(a == JsonRpcEnvelope.Id.Num(100L))
            assert(b == JsonRpcEnvelope.Id.Num(101L))
        end for
    }

    "Custom with constant-returning function returns the same id repeatedly" in run {
        val custom: () => JsonRpcEnvelope.Id < Sync = () => Sync.defer(JsonRpcEnvelope.Id.Str("static"))
        val next                                    = IdStrategyEngine.mkNextId(JsonRpcHandler.IdStrategy.Custom(custom))
        for
            a <- next()
            b <- next()
        yield
            assert(a == JsonRpcEnvelope.Id.Str("static"))
            assert(b == JsonRpcEnvelope.Id.Str("static"))
        end for
    }

end JsonRpcHandlerIdStrategyTest
