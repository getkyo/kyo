package kyo.internal.engine

import kyo.*

private[kyo] object IdStrategyEngine:
    def mkNextId(strategy: JsonRpcIdStrategy)(using Frame): () => JsonRpcId < Sync =
        strategy match
            case JsonRpcIdStrategy.SequentialLong =>
                // Unsafe: id-counter init mirrors Exchange's internal counter pattern
                // AtomicX Unsafe init follows kyo Exchange pending-map precedent; no safe equivalent in AtomicX public API
                val counter = AtomicLong.Unsafe.init(0L)(using AllowUnsafe.embrace.danger)
                // unsafe deferred block bridges unsafe ops (AtomicX, Promise, Channel, Fiber) from Sync-only context
                () => Sync.Unsafe.defer(JsonRpcId(counter.incrementAndGet()))
            case JsonRpcIdStrategy.SequentialInt =>
                // Unsafe: id-counter init mirrors Exchange's internal counter pattern
                // AtomicX Unsafe init follows kyo Exchange pending-map precedent; no safe equivalent in AtomicX public API
                val counter = AtomicInt.Unsafe.init(0)(using AllowUnsafe.embrace.danger)
                // unsafe deferred block bridges unsafe ops (AtomicX, Promise, Channel, Fiber) from Sync-only context
                () => Sync.Unsafe.defer(JsonRpcId(counter.incrementAndGet().toLong))
            case JsonRpcIdStrategy.Custom(next) => next
end IdStrategyEngine
