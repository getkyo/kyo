package kyo.kernel2

import Arrow.internal.*
import java.util.ArrayDeque
import kyo.Frame
import kyo.Tag
import kyo.kernel2.internal.*
import scala.annotation.nowarn
import scala.annotation.tailrec

sealed abstract class Arrow[-A, +B, -S]:

    def apply[S2 <: S](v: A < S2)(using Safepoint): B < (S & S2)

    def map[C, S2](f: Safepoint ?=> B => C < S2): Arrow[A, C, S & S2] =
        andThen(Arrow.init(f))

    def andThen[C, S2](other: Arrow[B, C, S2]): Arrow[A, C, S & S2] =
        if this eq Arrow._identity then
            other.asInstanceOf[Arrow[A, C, S & S2]]
        else
            AndThen(this, other)
end Arrow

object Arrow:

    private[Arrow] val _identity: Arrow[Any, Any, Any] = init(identity(_))

    def identity[A]: Arrow[A, A, Any] = _identity.asInstanceOf[Arrow[A, A, Any]]

    def const[A](v: A)(using Frame): Arrow[Any, A, Any] = init(_ => v)

    def defer[A, S](v: => A < S)(using Frame): Arrow[Any, A, S] = init(_ => v)

    @nowarn("msg=anonymous")
    inline def init[A](using inline frame: Frame)[B, S](inline f: Safepoint ?=> A => B < S): Arrow[A, B, S] =
        new Lift[A, B, S]:
            def _frame                     = frame
            def run(v: A)(using Safepoint) = f(v)

    @nowarn("msg=anonymous")
    inline def loop[A, B, S](inline f: Arrow[A, B, S] => Safepoint ?=> A => B < S)(using inline frame: Frame): Arrow[A, B, S] =
        new Lift[A, B, S]:
            def _frame                     = frame
            def run(v: A)(using Safepoint) = f(this)(v)

    object internal:

        sealed trait IsPure
        case object IsPure extends IsPure

        abstract class Lift[A, B, S] extends Arrow[A, B, S]:
            def _frame: Frame
            def apply[S2 <: S](v: A < S2)(using safepoint: Safepoint): B < (S & S2) =
                v.reduce(
                    pending = _.andThen(this),
                    done = value =>
                        if !safepoint.enter(_frame, value) then
                            defer(run(value))
                        else
                            run(value)
                        end if
                )

            def run(v: A)(using Safepoint): B < S

            override def toString = s"Lift(${_frame.show})"
        end Lift

        case class AndThen[A, B, C, S](a: Arrow[A, B, S], b: Arrow[B, C, S]) extends Arrow[A, C, S]:
            def apply[S2 <: S](v: A < S2)(using Safepoint): C < (S & S2) = b(a(v))

            override def toString = s"AndThen($a, $b)"
        end AndThen

        abstract class Suspend[I[_], O[_], E <: ArrowEffect[I, O], A] extends Arrow[Any, O[A], E]:
            def _frame: Frame
            def _tag: Tag[E]
            def _input: I[A]

            def apply[S2 <: E](v: Any < S2)(using Safepoint): O[A] < (E & S2) = this

            override def toString = s"Suspend(tag=${_tag.showTpe}, input=$_input, frame=${_frame.position})"
        end Suspend

        case class Chain[A, B, S](array: IArray[Arrow[Any, Any, Any]]) extends Arrow[A, B, S]:
            def apply[S2 <: S](v: A < S2)(using Safepoint) =
                Chain.unsafeEval(v, array)

            override def toString = s"Chain(${array.mkString(", ")})"
        end Chain

        object Chain:
            val emptyArray = new Array[Arrow[Any, Any, Any]](0)
            def unsafeEval[A, B, S](v: A < S, array: IArray[Arrow[Any, Any, Any]]): B < S =
                def loop(v: Any < Any, idx: Int): Any < Any =
                    if idx == array.length then
                        v
                    else
                        array(idx)(v).reduce(
                            pending = kyo =>
                                val left = array.length - idx
                                if left == 1 then
                                    kyo
                                else
                                    val newArray = new Array[Arrow[Any, Any, Any]](left)
                                    newArray(0) = kyo
                                    System.arraycopy(array, idx + 1, newArray, 1, left - 1)
                                    Chain(IArray.unsafeFromArray(newArray))
                                end if
                            ,
                            done = loop(_, idx + 1)
                        )
                loop(v, 0).asInstanceOf[B < S]
            end unsafeEval
        end Chain

        opaque type Stack = ArrayDeque[Arrow[Any, Any, Any]]

        object Stack:
            def acquire(): Stack = new ArrayDeque[Arrow[Any, Any, Any]]
            extension (self: Stack)
                def load(v: Any < Any): Any < Any =
                    v match
                        case Chain(array) =>
                            def loop(idx: Int): Unit =
                                if idx < array.length then
                                    self.push(array(idx))
                                    loop(idx + 1)
                            loop(1)
                            array(0)
                        case AndThen(a, b) =>
                            self.push(b.asInstanceOf[Arrow[Any, Any, Any]])
                            load(a)
                        case _ =>
                            v
                def dumpAndRelease(): IArray[Arrow[Any, Any, Any]] =
                    IArray.unsafeFromArray(self.toArray(Chain.emptyArray))
            end extension
        end Stack

    end internal
end Arrow
