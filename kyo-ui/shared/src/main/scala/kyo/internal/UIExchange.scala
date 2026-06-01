package kyo.internal

import kyo.*

/** Reactive change notification. Transport-agnostic; each backend renders in its own format. */
private[kyo] trait UIExchange:
    def onChange(path: Seq[String], ui: UI)(using Frame): Unit < Async
end UIExchange
