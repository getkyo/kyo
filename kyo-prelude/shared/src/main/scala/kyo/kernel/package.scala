package kyo

import kyo.Tag

package object kernel:

    type Id[A]    = A
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
                val parsed = frame.parse
                s"Kyo(${tag.show}, Input($input), ${parsed.position}, ${parsed.snippetShort})"
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
