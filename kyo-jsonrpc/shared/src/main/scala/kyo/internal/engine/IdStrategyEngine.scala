package kyo.internal.engine

import kyo.*

private[kyo] object IdStrategyEngine:
    def mkNextId(strategy: JsonRpcIdStrategy)(using Frame): () => JsonRpcId < Sync =
        strategy match
            case JsonRpcIdStrategy.SequentialLong =>
                // Unsafe: per-endpoint counter shared across fibers; AtomicLong is read from Sync-only callbacks
                val counter = AtomicLong.Unsafe.init(0L)(using AllowUnsafe.embrace.danger)
                () => Sync.Unsafe.defer(JsonRpcId(counter.incrementAndGet()))
            case JsonRpcIdStrategy.SequentialInt =>
                // Unsafe: per-endpoint counter shared across fibers; AtomicInt is read from Sync-only callbacks
                val counter = AtomicInt.Unsafe.init(0)(using AllowUnsafe.embrace.danger)
                () => Sync.Unsafe.defer(JsonRpcId(counter.incrementAndGet().toLong))
            case JsonRpcIdStrategy.Custom(next) => next
end IdStrategyEngine
