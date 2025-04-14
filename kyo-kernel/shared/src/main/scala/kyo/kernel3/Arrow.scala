package kyo.kernel3

import <.internal.*
import Arrow.internal.*
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


opaque type ~>[-A, +B <: (Any < Nothing)] = Any

opaque type Arrow[-A, +B, -S] = AbstractArrow[A, B, S] | Chain[A, B, S]

object Arrow:

    extension [A, B, S](self: Arrow[A, B, S])

        inline def apply[S2 <: S](v: A < S2)(using Safepoint): B < S2 =
            self match
                case self: AbstractArrow[A, B, S] @unchecked =>
                    self(v)
                case _ =>
                    Chain.eval(v, self)

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
    inline def loop[A, B, S](inline f: Safepoint ?=> (Arrow[A, B, S], A) => B < S)(using inline frame: Frame): Arrow[A, B, S] =
        new Lift[A, B, S]:
            def _frame = frame
            def run(v: A)(using Safepoint) =
                f(this, v)

    object internal:

        sealed abstract class AbstractArrow[-A, +B, -S]:
            def apply[S2 <: S](v: A < S2)(using Safepoint): B < S2

        sealed abstract class Lift[-A, +B, -S] extends AbstractArrow[A, B, S]:
            def _frame: Frame
            final def apply[S2 <: S](v: A < S2)(using safepoint: Safepoint): B < S2 =
                v match
                    case kyo: Suspend[?, ?, ?, ?, A, S2] @unchecked =>
                        kyo.andThen(this)
                    case _ =>
                        val value = v.unsafeUnwrap
                        if !safepoint.enter(_frame, value) then
                            return Effect.defer(value, this)(using _frame)
                        val r = run(value)
                        safepoint.exit()
                        r
            end apply

            def run(v: A)(using Safepoint): B < S

            final override def toString = s"Lift(${_frame.show})"
        end Lift

        final class AndThen[-A, B, +C, -S](a: Arrow[A, B, S], b: Arrow[B, C, S]) extends AbstractArrow[A, C, S]:
            def apply[S2 <: S](v: A < S2)(using Safepoint) = b(a(v))

            override def toString = s"AndThen($a, $b)"
        end AndThen

        opaque type Chain[-A, +B, -S] = Array[AbstractArrow[Any, Any, Any]]

        object Chain:
            val empty: Arrow[Any, Any, Any] = new Array[AbstractArrow[Any, Any, Any]](0)

            def eval[A, B, S](v: A < S, self: Arrow[A, B, S]): B < S =
                val array = self.asInstanceOf[Array[AbstractArrow[Any, Any, Any]]]
                @tailrec
                def loop(v: Any, idx: Int): Any =
                    if idx == array.length then
                        v
                    else
                        array(idx)(v) match
                            case kyo: Suspend[?, ?, ?, ?, ?, ?] =>
                                val contSize = array.length - idx - 1
                                if contSize == 0 then
                                    kyo
                                else if contSize == 1 then
                                    kyo.andThen(array(idx + 1))
                                else
                                    val newArray = new Array[AbstractArrow[Any, Any, Any]](contSize)
                                    System.arraycopy(array, idx + 1, newArray, 1, contSize)
                                    kyo.andThen(newArray)
                                end if
                            case kyo =>
                                loop(kyo.unsafeUnwrap, idx + 1)
                loop(v, 0).asInstanceOf[B < S]
            end eval
        end Chain
    end internal
end Arrow
