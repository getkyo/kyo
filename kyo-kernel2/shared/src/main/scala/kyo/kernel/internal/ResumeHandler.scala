package kyo.kernel.internal

import kyo.Frame
import kyo.Tag
import kyo.kernel.<
import kyo.kernel.ArrowEffect

/** A fun-format handler: the effect tag and the handle function at the types the public [[ArrowEffect]] surface established.
  *
  * This is the only handler kind that exists as data: it is carried by [[Handlers]] so its operations are answered locally at the point
  * they surface, with no continuation built. Every other format has to receive the built-up continuation, so it lives in its handle
  * loop and its rotate steps, never as a value.
  */
final private[kyo] class ResumeHandler[I[_], O[_], E <: ArrowEffect[I, O], S](
    val effectTag: Tag[E],
    val handle: [C] => I[C] => O[C] < (E & S),
    val frame: Frame
):
    private[kyo] def erasedTag: Tag[Any] = effectTag.erased

    /** The local answer, at the drive's currency. The input recovery is justified by the tag match that selected this handler; inside
      * the handle function everything is at its public types.
      */
    private[kyo] def answer(input: Any): Any < Any =
        handle[Any](input.asInstanceOf[I[Any]]).asInstanceOf[Any < Any]
end ResumeHandler
