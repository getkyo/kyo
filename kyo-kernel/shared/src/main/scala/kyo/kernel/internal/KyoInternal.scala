package kyo.kernel.internal

import kyo.Frame

/** The core representation of suspended computations in Kyo's effect system.
  *
  * A value of type `Kyo[A, S]` is either a [[Pending]] node, the reification of one combinator (a deferral, a suspension, a region entry,
  * a parked slice or a stack snapshot), or an [[kyo.kernel.Arrow]], the reification of a continuation. The evaluator runs values built
  * from these two families; each carries the frame it was created at for traces.
  *
  * The two families are otherwise unrelated: the nodes are `Pending`'s subclasses in `PendingInternal`, while a continuation is a
  * first-class `Arrow`. This trait is what lets one position hold either, which is why it is the supertype of both.
  *
  * @tparam A
  *   The type of value this computation will eventually produce
  * @tparam S
  *   The type-level set of effects this computation may perform
  */
trait Kyo[+A, -S]:
    /** The stack frame where this value was created */
    def frame: Frame
