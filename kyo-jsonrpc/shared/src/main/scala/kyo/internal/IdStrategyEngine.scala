// kyo-jsonrpc/shared/src/main/scala/kyo/internal/IdStrategyEngine.scala
package kyo.internal

import kyo.*

private[kyo] object IdStrategyEngine:
    def mkNextId(strategy: IdStrategy)(using Frame): () => JsonRpcId < Sync =
        strategy match
            case IdStrategy.SequentialLong =>
                // Unsafe: id-counter init mirrors Exchange's internal counter pattern
                // flow-allow: AtomicX Unsafe init follows kyo Exchange pending-map precedent; no safe equivalent in AtomicX public API
                val counter = AtomicLong.Unsafe.init(0L)(using AllowUnsafe.embrace.danger)
                // flow-allow: unsafe deferred block bridges unsafe ops (AtomicX, Promise, Channel, Fiber) from Sync-only context
                () => Sync.Unsafe.defer(JsonRpcId.Num(counter.incrementAndGet()))
            case IdStrategy.SequentialInt =>
                // Unsafe: id-counter init mirrors Exchange's internal counter pattern
                // flow-allow: AtomicX Unsafe init follows kyo Exchange pending-map precedent; no safe equivalent in AtomicX public API
                val counter = AtomicInt.Unsafe.init(0)(using AllowUnsafe.embrace.danger)
                // flow-allow: unsafe deferred block bridges unsafe ops (AtomicX, Promise, Channel, Fiber) from Sync-only context
                () => Sync.Unsafe.defer(JsonRpcId.Num(counter.incrementAndGet().toLong))
            case IdStrategy.Custom(next) => next
end IdStrategyEngine
