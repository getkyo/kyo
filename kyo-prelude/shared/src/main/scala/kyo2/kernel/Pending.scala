package kyo2.kernel

import internal.*
import kyo.*
import kyo2.Maybe
import scala.annotation.tailrec
import scala.language.implicitConversions
import scala.util.NotGiven

// TODO Constructor should be private but there's an issue with inlining
case class <[+A, -S](private val curr: A | Kyo[A, S]) extends AnyVal

object `<`:

    implicit private[kyo2] inline def apply[A, S](p: Kyo[A, S]): A < S = new <(p)

    implicit inline def lift[A, S](v: A)(using inline ng: NotGiven[A <:< (Any < Nothing)]): A < S = <(v)

    extension [A, S](inline v: A < S)

        inline def unit(using inline frame: Frame): Unit < S =
            map(_ => ())

        inline def andThen[B, S2](inline f: => B < S2)(using inline ev: A => Unit, inline frame: Frame): B < (S & S2) =
            map(_ => f)

        inline def flatMap[B, S2](inline f: A => B < S2)(using inline frame: Frame): B < (S & S2) =
            map(v => f(v))

        inline def pipe[B, S2](inline f: A < S => B < S2)(using inline frame: Frame): B < S2 =
            f(v)

        inline def repeat(i: Int)(using inline ev: A => Unit, inline frame: Frame): Unit < S =
            if i <= 0 then () else andThen(repeat(i - 1))

        inline def map[B, S2](inline f: Safepoint ?=> A => B < S2)(
            using inline _frame: Frame
        )(using Safepoint): B < (S & S2) =
            def mapLoop(v: A < S)(using Safepoint): B < (S & S2) =
                v match
                    case <(kyo: KyoSuspend[IX, OX, EX, Any, A, S] @unchecked) =>
                        new KyoContinue[IX, OX, EX, Any, B, S & S2](kyo):
                            def frame = _frame
                            def apply(v: OX[Any], context: Context)(using Safepoint) =
                                mapLoop(kyo(v, context))
                    case <(v) =>
                        val value = v.asInstanceOf[A]
                        Safepoint.handle(value)(
                            suspend = mapLoop(value),
                            continue = f(value)
                        )
            mapLoop(v)
        end map

        inline def evalNow: Maybe[A] =
            v match
                case <(kyo: Kyo[?, ?]) => Maybe.empty
                case <(v)              => Maybe(v.asInstanceOf[A])

        private[kyo2] inline def evalPartial(interceptor: Safepoint.Interceptor)(using frame: Frame, safepoint: Safepoint): A < S =
            @tailrec def partialEvalLoop(kyo: A < S)(using Safepoint): A < S =
                if !interceptor.enter(frame, ()) then kyo
                else
                    kyo match
                        case <(kyo: KyoSuspend[Const[Unit], Const[Unit], Defer, Any, A, S] @unchecked)
                            if kyo.tag =:= Tag[Defer] =>
                            partialEvalLoop(kyo((), Context.empty))
                        case kyo =>
                            kyo
            end partialEvalLoop
            Safepoint.immediate(interceptor)(partialEvalLoop(v))
        end evalPartial

    end extension

    extension [A, S, S2](inline kyo: A < S < S2)
        inline def flatten(using inline frame: Frame): A < (S & S2) =
            kyo.map(identity)

    extension [A](inline v: A < Any)
        inline def eval(using inline frame: Frame): A =
            @tailrec def evalLoop(kyo: A < Any)(using Safepoint): A =
                kyo match
                    case <(kyo: KyoSuspend[Const[Unit], Const[Unit], Defer, Any, A, Any] @unchecked)
                        if kyo.tag =:= Tag[Defer] =>
                        evalLoop(kyo((), Context.empty))
                    case <(kyo: Kyo[A, Any] @unchecked) =>
                        kyo2.bug.failTag(kyo, Tag[Any])
                    case <(v) =>
                        v.asInstanceOf[A]
                end match
            end evalLoop
            Safepoint.eval(evalLoop(v))
        end eval
    end extension
end `<`
