package kyo.kernel.internal

import kyo.Frame

/** The core representation of suspended computations in Kyo's effect system.
  *
  * A value of type `Kyo[A, S]` is either a [[Pending]] node (the reification of one combinator) or an [[kyo.kernel.Arrow]] (the reification
  * of a continuation). The evaluator runs values built from these two families; each carries the frame it was created at, for traces.
  *
  * This trait is the supertype of both, which is what lets one position hold either.
  *
  * @tparam A
  *   The type of value this computation will eventually produce
  * @tparam S
  *   The type-level set of effects this computation may perform
  */
trait Kyo[+A, -S]:
    def frame: Frame
