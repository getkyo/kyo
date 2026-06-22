package kyo.internal.engine

import kyo.*

private[kyo] object IdStrategyEngine:
    // Returns a thunk that allocates the next id. The Sequential strategies hold a per-endpoint counter
    // shared across fibers and read from Sync-only callbacks; it is built under the propagated AllowUnsafe
    // (the caller supplies the capability) rather than a summoned one.
    def mkNextId(strategy: JsonRpcIdStrategy)(using Frame, AllowUnsafe): () => JsonRpcId < Sync =
        strategy match
            case JsonRpcIdStrategy.SequentialLong =>
                val counter = AtomicLong.Unsafe.init(0L)
                () => Sync.Unsafe.defer(JsonRpcId(counter.incrementAndGet()))
            case JsonRpcIdStrategy.SequentialInt =>
                val counter = AtomicInt.Unsafe.init(0)
                () => Sync.Unsafe.defer(JsonRpcId(counter.incrementAndGet().toLong))
            case JsonRpcIdStrategy.Custom(next) => next
end IdStrategyEngine
