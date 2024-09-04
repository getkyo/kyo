package kyo.kernel

import internal.*
import kyo.*
import kyo.Maybe
import scala.annotation.nowarn
import scala.annotation.tailrec
import scala.language.implicitConversions

opaque type <[+A, -S] = A | Kyo[A, S]

object `<`:

    implicit private[kernel] inline def fromKyo[A, S](v: Kyo[A, S]): A < S       = v
    implicit inline def lift[A, S](v: A)(using inline flat: Flat.Weak[A]): A < S = v

    implicit inline def liftPureFunction1[A1, B](inline f: A1 => B)(
        using inline flat: Flat.Weak[B]
    ): A1 => B < Any =
        a1 => f(a1)

    implicit inline def liftPureFunction2[A1, A2, B](inline f: (A1, A2) => B)(
        using inline flat: Flat.Weak[B]
    ): (A1, A2) => B < Any =
        (a1, a2) => f(a1, a2)

    implicit inline def liftPureFunction3[A1, A2, A3, B](inline f: (A1, A2, A3) => B)(
        using inline flat: Flat.Weak[B]
    ): (A1, A2, A3) => B < Any =
        (a1, a2, a3) => f(a1, a2, a3)

    implicit inline def liftPureFunction4[A1, A2, A3, A4, B](inline f: (A1, A2, A3, A4) => B)(
        using inline flat: Flat.Weak[B]
    ): (A1, A2, A3, A4) => B < Any =
        (a1, a2, a3, a4) => f(a1, a2, a3, a4)

    extension [A, S](inline v: A < S)

        inline def andThen[B, S2](inline f: Safepoint ?=> B < S2)(
            using
            inline ev: A => Unit,
            inline frame: Frame,
            inline flatA: Flat.Weak[A],
            inline flatB: Flat.Weak[B]
        ): B < (S & S2) =
            map(_ => f)

        inline def flatMap[B, S2](inline f: Safepoint ?=> A => B < S2)(
            using
            inline frame: Frame,
            inline flatA: Flat.Weak[A],
            inline flatB: Flat.Weak[B]
        ): B < (S & S2) =
            map(v => f(v))

        inline def pipe[B](f: (=> A < S) => B)(
            using inline flat: Flat.Weak[A]
        ): B =
            f(v)

        inline def map[B, S2](inline f: Safepoint ?=> A => B < S2)(
            using
            inline _frame: Frame,
            inline flatA: Flat.Weak[A],
            inline flatB: Flat.Weak[B],
            inline safepoint: Safepoint
        ): B < (S & S2) =
            @nowarn("msg=anonymous") def mapLoop(v: A < S)(using Safepoint): B < (S & S2) =
                v match
                    case kyo: KyoSuspend[IX, OX, EX, Any, A, S] @unchecked =>
                        new KyoContinue[IX, OX, EX, Any, B, S & S2](kyo):
                            def frame = _frame
                            def apply(v: OX[Any], context: Context)(using Safepoint) =
                                mapLoop(kyo(v, context))
                    case v =>
                        val value = v.asInstanceOf[A]
                        Safepoint.handle(value)(
                            suspend = mapLoop(value),
                            continue = f(value)
                        )
            mapLoop(v)
        end map

        inline def evalNow(using inline flat: Flat[A]): Maybe[A] =
            v match
                case kyo: Kyo[?, ?] => Maybe.empty
                case v              => Maybe(v.asInstanceOf[A])

        inline def unit(
            using
            inline _frame: Frame,
            inline flat: Flat.Weak[A],
            inline safepoint: Safepoint
        ): Unit < S =
            @nowarn("msg=anonymous") def unitLoop(v: A < S)(using Safepoint): Unit < S =
                v match
                    case kyo: KyoSuspend[IX, OX, EX, Any, A, S] @unchecked =>
                        new KyoContinue[IX, OX, EX, Any, Unit, S](kyo):
                            def frame = _frame
                            def apply(v: OX[Any], context: Context)(using Safepoint) =
                                unitLoop(kyo(v, context))
                    case v =>
                        ()
            unitLoop(v)
        end unit

    end extension

    // TODO Compiler crash if inlined
    extension [A, S](v: A < S)
        def repeat(i: Int)(using ev: A => Unit, frame: Frame, flat: Flat.Weak[A]): Unit < S =
            if i <= 0 then () else v.andThen(repeat(i - 1))

    // TODO Compiler crash if inlined
    extension [A, S, S2](v: A < S < S2)
        def flatten(using _frame: Frame): A < (S & S2) =
            def flattenLoop(v: A < S < S2)(using Safepoint): A < (S & S2) =
                v match
                    case kyo: KyoSuspend[IX, OX, EX, Any, A < S, S2] @unchecked =>
                        new KyoContinue[IX, OX, EX, Any, A, S & S2](kyo):
                            def frame = _frame
                            def apply(v: OX[Any], context: Context)(using Safepoint) =
                                flattenLoop(kyo(v, context))
                    case v =>
                        v.asInstanceOf[A]
            flattenLoop(v)

    extension [A](inline v: A < Any)
        inline def eval(using inline frame: Frame, inline flat: Flat[A]): A =
            @tailrec def evalLoop(kyo: A < Any)(using Safepoint): A =
                kyo match
                    case kyo: KyoSuspend[Const[Unit], Const[Unit], Defer, Any, A, Any] @unchecked
                        if kyo.tag =:= Tag[Defer] =>
                        evalLoop(kyo((), Context.empty))
                    case kyo: Kyo[A, Any] @unchecked =>
                        bug.failTag(kyo, Tag[Any])
                    case v =>
                        v.asInstanceOf[A]
                end match
            end evalLoop
            Safepoint.eval(evalLoop(v))
        end eval
    end extension
end `<`
