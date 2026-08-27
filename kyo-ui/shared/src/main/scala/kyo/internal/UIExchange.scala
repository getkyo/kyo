package kyo.internal

import kyo.*

/** Reactive change notification. Transport-agnostic; each backend renders in its own format.
  *
  * `previous` is the tree this region last rendered, `Absent` on its first render. A backend that can address
  * a nested node uses it to send only the parts that moved (see [[UIDiff]]); one that cannot ignores it and
  * re-sends the region.
  */
private[kyo] trait UIExchange:
    def onChange(path: Seq[String], previous: Maybe[UI], ui: UI)(using Frame): Unit < Async
end UIExchange
