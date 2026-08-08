package kyo.kernel2.internal

import kyo.Frame
import kyo.Tag
import kyo.kernel2.<
import kyo.kernel2.ArrowEffect

/** A handler's data: the effect tag and the handle function at the types the public [[ArrowEffect]] surface established.
  *
  * Handlers are not chain elements: handling is a structural fold (ArrowEffect.handle*) that acts on operations of the handler's effect
  * where they surface and rotates around foreign suspensions, and the fold's re-entry step carries the handler in the continuation. The
  * fun format additionally registers in the threaded [[Handlers]] parameter so its operations are answered locally at the point they
  * surface, with no continuation built.
  */
sealed abstract private[kyo] class Handler[I[_], O[_], E <: ArrowEffect[I, O]]:
    def effectTag: Tag[E]
    def frame: Frame
    final private[kyo] def erasedTag: Tag[Any] = effectTag.erased
end Handler

private[kyo] object Handler:

    /** A fun-format handler: answers each operation in place. The only handler kind carried by [[Handlers]]: every other format has to
      * receive the built-up continuation, so it lives in its handle loop and its rotate steps, never in the parameter.
      */
    final class Resume[I[_], O[_], E <: ArrowEffect[I, O], A, S, S2](
        val effectTag: Tag[E],
        val handle: [C] => I[C] => O[C] < (E & S & S2),
        val frame: Frame
    ) extends Handler[I, O, E]

end Handler
