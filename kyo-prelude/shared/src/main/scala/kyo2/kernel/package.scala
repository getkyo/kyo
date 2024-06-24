package kyo2

import kyo.Tag
import scala.collection.immutable

package object kernel:

    type Id[A]    = A
    type Const[A] = [U] =>> A

    enum Mode derives CanEqual:
        case Development, Staging, Production

    private object internal:

        inline def maxStackDepth  = 300
        inline def maxTraceFrames = 32

        type IX[_]
        type OX[_]
        type EX <: Effect[IX, OX]

        sealed trait Defer extends Effect[Const[Unit], Const[Unit]]

        sealed abstract class Kyo[+T, -S]

        abstract class KyoSuspend[I[_], O[_], E <: Effect[I, O], A, U, S]
            extends Kyo[U, S]:
            def tag: Tag[E]
            def input: I[A]
            def frame: Frame

            def apply(v: O[A], context: Context)(using Safepoint): U < S

            final override def toString =
                val parsed = frame.parse
                s"Kyo(${tag.show}, Input($input), ${parsed.position}, ${parsed.snippetShort})"
        end KyoSuspend

        abstract class KyoContinue[I[_], O[_], E <: Effect[I, O], A, U, S](kyo: KyoSuspend[I, O, E, A, ?, ?])
            extends KyoSuspend[I, O, E, A, U, S]:
            val tag   = kyo.tag
            val input = kyo.input
        end KyoContinue

        abstract class KyoDefer[T, S] extends KyoSuspend[Const[Unit], Const[Unit], Defer, Any, T, S]:
            final def tag   = Tag[Defer]
            final def input = ()

        class Context(context: immutable.Map[Tag[Any], AnyRef]) extends AnyVal:
            inline def contains[A, E <: ContextEffect[A]](tag: Tag[E]): Boolean =
                context.contains(tag.erased)

            inline def getOrElse[A, E <: ContextEffect[A], B >: A](tag: Tag[E], inline default: => B): B =
                if !contains(tag) then default
                else context(tag.erased).asInstanceOf[B]

            inline def get[A, E <: ContextEffect[A]](tag: Tag[E]): A =
                getOrElse(tag, bug(s"Missing value for context effect '${tag}'. Values: $context"))

            inline def set[A, E <: ContextEffect[A]](tag: Tag[E], value: A): Context =
                Context(context.updated(tag.asInstanceOf[Tag[Any]], value.asInstanceOf[AnyRef]))
        end Context

        object Context:
            inline def empty: Context = Context(immutable.Map.empty)
    end internal
end kernel
