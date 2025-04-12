package kyo.kernel3

import <.internal.*
import kyo.Const
import kyo.Frame
import kyo.Tag
import kyo.kernel.internal.WeakFlat
import kyo.kernel3.internal.Safepoint
import scala.annotation.nowarn
import scala.compiletime.erasedValue
import scala.language.implicitConversions
import scala.util.NotGiven

sealed abstract class Effect

object Effect:

    def defer[A, S](v: => A < S)(using frame: Frame): A < S =
        new SuspendDefer[A, S]:
            def _frame     = frame
            def run: A < S = v
end Effect

trait ArrowEffect[I[_], O[_]]

object ArrowEffect:

    @nowarn("msg=anonymous")
    inline def suspend[Input[_], Output[_], Effect <: ArrowEffect[Input, Output], A](
        inline tag: Tag[Effect],
        inline input: Input[A]
    )(using inline frame: Frame): Output[A] < Effect =
        new Suspend[Output[A], Effect]:
            type I[A] = Input[A]
            type O[A] = Output[A]
            type E    = Effect
            type V    = A
            def _tag   = tag
            def _input = input
            def _frame = frame
            def cont   = Arrow[Output[A]]
        end new
    end suspend

    @nowarn("msg=anonymous")
    inline def suspendWith[Input[_], Output[_], Effect <: ArrowEffect[Input, Output], A, B, S](
        inline tag: Tag[Effect],
        inline input: Input[A]
    )(
        inline f: Output[A] => B < S
    )(using inline frame: Frame): B < (Effect & S) =
        new Suspend[B, Effect & S]:
            type I[A] = Input[A]
            type O[A] = Output[A]
            type E    = Effect
            type V    = A
            def _tag   = tag
            def _input = input
            def _frame = frame
            def cont   = Arrow.init(f)
        end new
    end suspendWith
end ArrowEffect

opaque type <[+A, -S] = A | Kyo[A, S & Defer]

object `<`:

    implicit def lift[A: WeakFlat, S](v: A): A < S =
        if v.isInstanceOf[Suspend[?, ?]] || v.isInstanceOf[Box[?]] then
            return Box(v)
        v
    end lift

    extension [A, S](self: A < S)

        private[kernel3] inline def reduce[B](inline done: A => B, inline pending: Suspend[A, S] => B): B =
            self match
                case self: Suspend[A, S] @unchecked =>
                    pending(self)
                case _ =>
                    done(unsafeUnwrap)

        private inline def unsafeUnwrap: A =
            self match
                case self: Box[A] @unchecked => self.v
                case _                       => self.asInstanceOf[A]

        inline def map[B, S2](inline f: Safepoint ?=> A => B < S2)(using Safepoint): B < (S & S2) =
            andThen(Arrow.init(f))

        def andThen[B, S2](arrow: Arrow[A, B, S2])(using Safepoint): B < (S & S2) =
            arrow(self)

    end extension

    object internal:

        implicit inline def toPending[A, S](v: Kyo[A, S]): A < S = v

        sealed abstract class Kyo[+A, -S]

        case class Box[+A](v: A) extends Kyo[A, Any]

        sealed abstract class Suspend[+A, -S] extends Kyo[A, S]:
            self =>
            type I[_]
            type O[_]
            type E <: ArrowEffect[I, O]
            type V
            def _tag: Tag[E]
            def _input: I[V]
            def _frame: Frame
            def cont: Arrow[O[V], A, S]

            def andThen[B, S2](next: Arrow[A, B, S2])(using frame: Frame) =
                new Suspend[B, S & S2]:
                    type I[A] = self.I[A]
                    type O[A] = self.O[A]
                    type E    = self.E
                    type V    = self.V
                    val _tag   = self._tag
                    val _input = self._input
                    def _frame = frame
                    val cont   = self.cont.andThenInternal(next)
        end Suspend

        abstract class Handle[A, S] extends Kyo[A, S]:
            type I[_]
            type O[_]
            type E <: ArrowEffect[I, O]
            def _tag: Tag[E]
            def v: A < (S & E)
            def run[V](input: I[V], cont: O[V] => A < (S & E)): A < (S & E)

        trait Defer extends ArrowEffect[Const[Unit], Const[Unit]]

        sealed abstract class SuspendDefer[+A, -S] extends Suspend[A, S]:
            type I = Const[Unit]
            type O = Const[Unit]
            type E = Defer
            type V = Any
            def _tag   = Tag[Defer]
            def _input = ()
            def cont   = Arrow.init(_ => run)
            def run: A < S
        end SuspendDefer

    end internal
end `<`

import Arrow.internal.*

opaque type Arrow[-A, +B, -S] = ArrowImpl[A, B, S] | Chain[A, B, S]

object Arrow:

    extension [A, B, S](self: Arrow[A, B, S])
        def apply[S2 <: S](v: A < S2)(using Safepoint): B < S2 =
            self match
                case self: ArrowImpl[A, B, S] @unchecked =>
                    self(v)
                case _ =>
                    Chain.eval(v, self.asInstanceOf[Chain[A, B, S]])

        private[kernel3] def andThenInternal[C, S2](next: Arrow[B, C, S2]): Arrow[A, C, S & S2] =
            if Chain.empty.equals(self) then
                next.asInstanceOf[Arrow[A, C, S & S2]]
            else
                AndThen(self, next)
    end extension

    def apply[A]: Arrow[A, A, Any] = Chain.empty.asInstanceOf[Arrow[A, A, Any]]

    @nowarn("msg=anonymous")
    inline def init[A, B, S](inline f: Safepoint ?=> A => B < S)(using inline frame: Frame): Arrow[A, B, S] =
        new Lift[A, B, S]:
            def _frame = frame
            def run(v: A)(using Safepoint) =
                f(v)

    @nowarn("msg=anonymous")
    inline def initLoop[A, B, S](inline f: Safepoint ?=> (Arrow[A, B, S], A) => B < S)(using inline frame: Frame): Arrow[A, B, S] =
        new Lift[A, B, S]:
            def _frame = frame
            def run(v: A)(using Safepoint) =
                f(this, v)

    object internal:

        sealed abstract class ArrowImpl[-A, +B, -S]:
            def apply[S2 <: S](v: A < S2)(using Safepoint): B < S2

        sealed abstract class Lift[-A, +B, -S] extends ArrowImpl[A, B, S]:
            def _frame: Frame
            def apply[S2 <: S](v: A < S2)(using safepoint: Safepoint): B < S2 =
                v.reduce(
                    pending = _.andThen(this),
                    done = value =>
                        if !safepoint.enter(_frame, value) then
                            Effect.defer(run(value))
                        else
                            val r = run(value)
                            safepoint.exit()
                            r
                )

            def run(v: A)(using Safepoint): B < S
        end Lift

        class AndThen[-A, B, +C, -S](a: Arrow[A, B, S], b: Arrow[B, C, S]) extends ArrowImpl[A, C, S]:
            def apply[S2 <: S](v: A < S2)(using Safepoint) = b(a(v))

        opaque type Chain[-A, +B, -S] = Array[ArrowImpl[Any, Any, Any]]

        object Chain:
            val empty: Arrow[Any, Any, Any] = new Array[ArrowImpl[Any, Any, Any]](0)

            def eval[A, B, S](v: A < S, self: Chain[A, B, S]): B < S =
                val array = self.asInstanceOf[Array[ArrowImpl[Any, Any, Any]]]
                def loop(v: Any, idx: Int): Any =
                    if idx == array.length then
                        v
                    else
                        array(idx)(v).reduce(
                            pending = kyo =>
                                val contSize = array.length - idx - 1
                                if contSize == 0 then
                                    kyo
                                else if contSize == 1 then
                                    kyo.andThen(array(idx + 1))
                                else
                                    val newArray = new Array[ArrowImpl[Any, Any, Any]](contSize)
                                    System.arraycopy(array, idx + 1, newArray, 1, contSize)
                                    kyo.andThen(newArray)
                                end if
                            ,
                            done = r => loop(r, idx + 1)
                        )
                loop(v, 0).asInstanceOf[B < S]
            end eval
        end Chain
    end internal
end Arrow
