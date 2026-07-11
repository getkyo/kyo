package kyo.internal

import kyo.*

/** Reactive change notification. Transport-agnostic; each backend renders in its own format. A
  * DomRegion carries the rendered UI (a Replace); a PropRegion carries a backend node's bound prop
  * value (a SetProp); a StructuralRegion carries a backend node's Schema-serialized structural data
  * snapshot (a ReplaceSubtree). The discriminated region (ReactiveUI.Region) is built by subscribeScoped.
  */
private[kyo] trait UIExchange:
    def onChange(region: ReactiveUI.Region, value: Any)(using Frame): Unit < Async
end UIExchange
