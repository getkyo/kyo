package kyo

import kyo.internal.engine.IdStrategyEngine

class JsonRpcHandlerIdStrategyTest extends JsonRpcTest:

    given CanEqual[Any, Any] = CanEqual.canEqualAny
    // Unsafe: test scaffolding; mkNextId builds its per-endpoint counter under a propagated AllowUnsafe
    given AllowUnsafe = AllowUnsafe.embrace.danger

    "SequentialLong allocates monotonically increasing JsonRpcId.Num starting at 1" in {
        val next = IdStrategyEngine.mkNextId(JsonRpcIdStrategy.SequentialLong)
        for
            a <- next()
            b <- next()
            c <- next()
        yield
            assert(a == JsonRpcId.Num(1L))
            assert(b == JsonRpcId.Num(2L))
            assert(c == JsonRpcId.Num(3L))
        end for
    }

    "SequentialInt allocates monotonically increasing JsonRpcId.Num starting at 1" in {
        val next = IdStrategyEngine.mkNextId(JsonRpcIdStrategy.SequentialInt)
        for
            a <- next()
            b <- next()
        yield
            assert(a == JsonRpcId.Num(1L))
            assert(b == JsonRpcId.Num(2L))
        end for
    }

    "Custom forwards verbatim to the supplied next function" in {
        // Unsafe: AtomicLong.Unsafe.init for in-test counter outside effect context
        val counter = AtomicLong.Unsafe.init(99L)(using AllowUnsafe.embrace.danger)
        val custom: () => JsonRpcId < Sync =
            () => Sync.Unsafe.defer(JsonRpcId.Num(counter.incrementAndGet()))
        val next = IdStrategyEngine.mkNextId(JsonRpcIdStrategy.Custom(custom))
        for
            a <- next()
            b <- next()
        yield
            assert(a == JsonRpcId.Num(100L))
            assert(b == JsonRpcId.Num(101L))
        end for
    }

    "Custom with constant-returning function returns the same id repeatedly" in {
        val custom: () => JsonRpcId < Sync = () => Sync.defer(JsonRpcId.Str("static"))
        val next                           = IdStrategyEngine.mkNextId(JsonRpcIdStrategy.Custom(custom))
        for
            a <- next()
            b <- next()
        yield
            assert(a == JsonRpcId.Str("static"))
            assert(b == JsonRpcId.Str("static"))
        end for
    }

end JsonRpcHandlerIdStrategyTest
