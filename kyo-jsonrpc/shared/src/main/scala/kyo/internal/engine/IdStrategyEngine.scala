package kyo.internal.engine

import kyo.*

private[kyo] object IdStrategyEngine:
    def mkNextId(strategy: JsonRpcHandler.IdStrategy)(using Frame): () => JsonRpcId < Sync =
        strategy match
            case JsonRpcHandler.IdStrategy.SequentialLong =>
                // Unsafe: id-counter init mirrors Exchange's internal counter pattern
                // AtomicX Unsafe init follows kyo Exchange pending-map precedent; no safe equivalent in AtomicX public API
                val counter = AtomicLong.Unsafe.init(0L)(using AllowUnsafe.embrace.danger)
                // unsafe deferred block bridges unsafe ops (AtomicX, Promise, Channel, Fiber) from Sync-only context
                () => Sync.Unsafe.defer(JsonRpcId(counter.incrementAndGet()))
            case JsonRpcHandler.IdStrategy.SequentialInt =>
                // Unsafe: id-counter init mirrors Exchange's internal counter pattern
                // AtomicX Unsafe init follows kyo Exchange pending-map precedent; no safe equivalent in AtomicX public API
                val counter = AtomicInt.Unsafe.init(0)(using AllowUnsafe.embrace.danger)
                // unsafe deferred block bridges unsafe ops (AtomicX, Promise, Channel, Fiber) from Sync-only context
                () => Sync.Unsafe.defer(JsonRpcId(counter.incrementAndGet().toLong))
            case JsonRpcHandler.IdStrategy.Custom(next) => next
end IdStrategyEngine
