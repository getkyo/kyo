package kyo

package object kernel:

    /** Identity type constructor.
      *
      * Id is a simple type alias that returns its input type unchanged. It is commonly used with [[ArrowEffect]] when an effect needs to
      * preserve the exact type it operates on without modification.
      *
      * @tparam A
      *   The type to pass through unchanged
      */
    type Id[A] = A

    /** Constant type constructor.
      *
      * Const ignores its second type parameter and always returns the first type. It is commonly used with [[ArrowEffect]] when an effect
      * only needs to work with a fixed type regardless of what type it's applied to.
      *
      * @tparam A
      *   The constant type to return
      */
    type Const[A] = [B] =>> A

    enum Mode derives CanEqual:
        case Development, Staging, Production

    private object internal:

        inline def maxStackDepth  = 512
        inline def maxTraceFrames = 16

        type IX[_]
        type OX[_]
        type EX <: ArrowEffect[IX, OX]

        sealed trait Defer extends ArrowEffect[Const[Unit], Const[Unit]]

        sealed abstract class Kyo[+A, -S]

        abstract class KyoSuspend[I[_], O[_], E <: ArrowEffect[I, O], A, B, S]
            extends Kyo[B, S]:
            def tag: Tag[E]
            def input: I[A]
            def frame: Frame

            def apply(v: O[A], context: Context)(using Safepoint): B < S

            final override def toString =
                s"Kyo(${tag.show}, Input($input), ${frame.position.show}, ${frame.snippetShort})"
        end KyoSuspend

        abstract class KyoContinue[I[_], O[_], E <: ArrowEffect[I, O], A, B, S](kyo: KyoSuspend[I, O, E, A, ?, ?])
            extends KyoSuspend[I, O, E, A, B, S]:
            val tag   = kyo.tag
            val input = kyo.input
        end KyoContinue

        abstract class KyoDefer[A, S] extends KyoSuspend[Const[Unit], Const[Unit], Defer, Any, A, S]:
            final def tag   = Tag[Defer]
            final def input = ()
        end KyoDefer

    end internal
end kernel
