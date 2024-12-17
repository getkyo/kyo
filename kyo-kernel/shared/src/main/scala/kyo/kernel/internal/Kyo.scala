package kyo.kernel.internal

import kyo.<
import kyo.Const
import kyo.Frame
import kyo.Tag
import kyo.kernel.ArrowEffect

sealed private[kernel] trait Defer extends ArrowEffect[Const[Unit], Const[Unit]]

sealed abstract private[kernel] class Kyo[+A, -S]

abstract private[kernel] class KyoSuspend[I[_], O[_], E <: ArrowEffect[I, O], A, B, S]
    extends Kyo[B, S]:
    def tag: Tag[E]
    def input: I[A]
    def frame: Frame

    def apply(v: O[A], context: Context)(using Safepoint): B < S

    final override def toString =
        s"Kyo(${tag.show}, Input($input), ${frame.position.show}, ${frame.snippetShort})"
end KyoSuspend

abstract private[kernel] class KyoContinue[I[_], O[_], E <: ArrowEffect[I, O], A, B, S](kyo: KyoSuspend[I, O, E, A, ?, ?])
    extends KyoSuspend[I, O, E, A, B, S]:
    val tag   = kyo.tag
    val input = kyo.input
end KyoContinue

abstract private[kernel] class KyoDefer[A, S] extends KyoSuspend[Const[Unit], Const[Unit], Defer, Any, A, S]:
    final def tag   = Tag[Defer]
    final def input = ()
end KyoDefer
