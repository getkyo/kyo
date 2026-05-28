package kyo

enum IdStrategy derives CanEqual:
    case SequentialLong
    case SequentialInt
    case Custom(next: () => JsonRpcId < Sync)
end IdStrategy

object IdStrategy:
    private[kyo] def mkNextId(strategy: IdStrategy)(using Frame): () => JsonRpcId < Sync =
        strategy match
            case SequentialLong =>
                // Unsafe: id-counter init mirrors Exchange's internal counter pattern
                val counter = AtomicLong.Unsafe.init(0L)(using AllowUnsafe.embrace.danger)
                () => Sync.Unsafe.defer(JsonRpcId.Num(counter.incrementAndGet()))
            case SequentialInt =>
                // Unsafe: id-counter init mirrors Exchange's internal counter pattern
                val counter = AtomicInt.Unsafe.init(0)(using AllowUnsafe.embrace.danger)
                () => Sync.Unsafe.defer(JsonRpcId.Num(counter.incrementAndGet().toLong))
            case Custom(next) => next
end IdStrategy
