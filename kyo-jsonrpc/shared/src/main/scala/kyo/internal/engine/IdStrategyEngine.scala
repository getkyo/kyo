package kyo.internal.engine

import kyo.*

private[kyo] object IdStrategyEngine:
    def mkNextId(strategy: JsonRpcEndpoint.IdStrategy)(using Frame): () => JsonRpcEnvelope.Id < Sync =
        strategy match
            case JsonRpcEndpoint.IdStrategy.SequentialLong =>
                // Unsafe: id-counter init mirrors Exchange's internal counter pattern
                // AtomicX Unsafe init follows kyo Exchange pending-map precedent; no safe equivalent in AtomicX public API
                val counter = AtomicLong.Unsafe.init(0L)(using AllowUnsafe.embrace.danger)
                // unsafe deferred block bridges unsafe ops (AtomicX, Promise, Channel, Fiber) from Sync-only context
                () => Sync.Unsafe.defer(JsonRpcEnvelope.Id.Num(counter.incrementAndGet()))
            case JsonRpcEndpoint.IdStrategy.SequentialInt =>
                // Unsafe: id-counter init mirrors Exchange's internal counter pattern
                // AtomicX Unsafe init follows kyo Exchange pending-map precedent; no safe equivalent in AtomicX public API
                val counter = AtomicInt.Unsafe.init(0)(using AllowUnsafe.embrace.danger)
                // unsafe deferred block bridges unsafe ops (AtomicX, Promise, Channel, Fiber) from Sync-only context
                () => Sync.Unsafe.defer(JsonRpcEnvelope.Id.Num(counter.incrementAndGet().toLong))
            case JsonRpcEndpoint.IdStrategy.Custom(next) => next
end IdStrategyEngine
