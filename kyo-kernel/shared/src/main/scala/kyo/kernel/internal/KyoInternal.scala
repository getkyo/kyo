package kyo.kernel.internal

import kyo.Frame

/** The core representation of suspended computations in Kyo's effect system.
  *
  * A value of type Kyo[A, S] is either a [[Pending]] node, the reification of one combinator (a deferral, a suspension, a region entry, a
  * parked slice or a stack snapshot), or an [[kyo.kernel.Arrow]], the reification of a continuation. The evaluator drives values built
  * from these two families; each carries the frame it was created at for traces.
  *
  * @tparam A
  *   The type of value this computation will eventually produce
  * @tparam S
  *   The type-level set of effects this computation may perform
  */
// Diverges from main: main's Kyo is the sealed base of the suspension ADT (KyoSuspend, KyoContinue,
// KyoDefer, Nested); here the nodes live in PendingInternal as Pending's subclasses and the
// continuation is a first-class Arrow, so Kyo is the supertype of both.
trait Kyo[+A, -S]:
    /** The stack frame where this value was created */
    def frame: Frame
