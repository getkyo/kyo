package kyo2

import java.util.ArrayDeque
import java.util.Arrays
import java.util.HashMap
import kyo.Tag
import scala.annotation.tailrec
import scala.annotation.targetName
import scala.collection.immutable
import scala.collection.mutable
import scala.quoted.Type
import scala.util.control.NonFatal
import scala.util.hashing.Hashing.Default

package object kernel:

    type Id[A]    = A
    type Const[A] = [U] =>> A

    enum Mode derives CanEqual:
        case Debugging, Development, Staging, Production

    private object internal:

        inline def maxStackDepth  = 300
        inline def maxTraceFrames = 32

        type IX[_]
        type OX[_]
        type EX <: Effect[IX, OX]

        sealed trait Defer extends Effect[Const[Unit], Const[Unit]]

        sealed abstract class Kyo[+T, -S]

        abstract class Suspend[I[_], O[_], E <: Effect[I, O], A, U, S]
            extends Kyo[U, S]:
            def tag: Tag[E]
            def input: I[A]
            def frame: Frame

            def apply(v: O[A], values: Values)(using Runtime): U < S

            final override def toString =
                val parsed = frame.parse
                s"Kyo(${tag.show}, Input($input), ${parsed.position}, ${parsed.snippetShort})"
        end Suspend

        abstract class KyoDefer[T, S] extends Suspend[Const[Unit], Const[Unit], Defer, Any, T, S]:
            final def tag   = Tag[Defer]
            final def input = ()

        class Values(values: immutable.Map[Tag[Any], AnyRef]) extends AnyVal:
            inline def getOrElse[A, E <: RuntimeEffect[A], B >: A](tag: Tag[E], inline default: => B): B =
                if !values.contains(tag.erased) then default
                else values(tag.erased).asInstanceOf[B]

            inline def get[A, E <: RuntimeEffect[A]](tag: Tag[E]): A =
                getOrElse(tag, bug(s"Missing value for runtime effect '${tag}'. Values: $values"))

            inline def set[A, E <: RuntimeEffect[A]](tag: Tag[E], value: A): Values =
                Values(values.updated(tag.asInstanceOf[Tag[Any]], value.asInstanceOf[AnyRef]))
        end Values

        object Values:
            inline def empty: Values = Values(immutable.Map.empty)
    end internal
end kernel
