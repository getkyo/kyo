package kyo.kernel3

import <.internal.*
import kyo.Const
import kyo.Frame
import kyo.Tag
import kyo.bug
import kyo.kernel.internal.WeakFlat
import kyo.kernel3.internal.Safepoint
import scala.annotation.nowarn
import scala.annotation.tailrec
import scala.compiletime.erasedValue
import scala.language.implicitConversions
import scala.util.NotGiven

opaque type <[+A, -S] = A | Kyo[A, Defer & S]

object `<`:

    implicit inline def lift[A, S](v: A)(using inline flat: WeakFlat[A]): A < S =
        _lift(v)

    private def _lift[A, S](v: A): A < S =
        if v.isInstanceOf[Kyo[?, ?]] then
            return Box(v)
        v
    end _lift

    extension [A, S](self: A < S)

        inline def map[B, S2](inline f: Safepoint ?=> A => B < S2)(
            using
            inline frame: Frame,
            safepoint: Safepoint
        ): B < (S & S2) =
            Arrow.init(f)(self)

        def eval(using S =:= Any)(using Safepoint): A =
            @tailrec def loop(v: A < S): A =
                v match
                    case kyo: Suspend[?, ?, ?, ?, A, S] @unchecked =>
                        if kyo._tag =!= Tag[Defer] then
                            bug("")
                        loop(kyo.cont(null))
                    case _ =>
                        v.unsafeUnwrap
            loop(self)
        end eval

        // private[kernel3] inline def reduce(
        //     inline pending: Suspend[?, ?, ?, ?, A, S] => A
        // ): A =
        //     self match
        //         case self: Suspend[?, ?, ?, ?, A, S] @unchecked =>
        //             pending(self)
        //         case _ =>
        //             self.unsafeUnwrap

        // private[kernel3] inline def reduce[B](
        //     inline done: A => B,
        //     inline pending: Suspend[?, ?, ?, ?, A, S] => B
        // ): B =
        //     self match
        //         case self: Suspend[?, ?, ?, ?, A, S] @unchecked =>
        //             pending(self)
        //         case _ =>
        //             done(self.unsafeUnwrap)

        def unsafeUnwrap: A =
            self match
                case self: Box[A] @unchecked => self.v
                case _                       => self.asInstanceOf[A]

    end extension

    object internal:

        implicit inline def toPending[A, S](v: Kyo[A, Defer & S]): A < S = v

        sealed abstract class Kyo[+A, -S]

        case class Box[+A](v: A) extends Kyo[A, Any]

        abstract class Suspend[I[_], O[_], E <: ArrowEffect[I, O], V, +A, -S] extends Kyo[A, S]:
            self =>
            def _tag: Tag[E]
            def _input: I[V]
            def cont: Arrow[O[V], A, S]

            @nowarn("msg=anonymous")
            inline def updateCont[B, S2](inline f: Arrow[O[V], A, S] => Arrow[O[V], B, S2]): Kyo[B, S2] =
                new Suspend[I, O, E, V, B, S2]:
                    val _tag   = self._tag
                    val _input = self._input
                    val cont   = f(self.cont)
                end new
            end updateCont

            def andThen[B, S2](next: Arrow[A, B, S2]): Kyo[B, S & S2] =
                updateCont(_.andThenInternal(next))
        end Suspend

        abstract class SuspendIdentity[I[_], O[_], E <: ArrowEffect[I, O], V] extends Suspend[I, O, E, V, O[V], E]:
            def cont = Arrow[O[V]]

        trait Defer extends ArrowEffect[Const[Any], Const[Any]]

        class SuspendDefer[+A, -S](val cont: Arrow[Any, A, S]) extends Suspend[Const[Any], Const[Any], Defer, Any, A, S]:
            def _tag   = Tag[Defer]
            def _input = ()
        end SuspendDefer

    end internal
end `<`
