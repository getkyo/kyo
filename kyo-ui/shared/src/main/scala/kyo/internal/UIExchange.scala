package kyo.internal

import kyo.*

/** Reactive change notification. Transport-agnostic; each backend renders in its own format. */
private[kyo] trait UIExchange:
    def onChange(
        region: ReactiveRegion,
        path: Seq[String],
        contentContext: ReactiveRegion.RegionIdentity,
        parentContext: ReactiveRegion.ParentContext,
        ui: UI
    )(using Frame): Unit < Async
end UIExchange
