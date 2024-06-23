package kyo2.kernel

import internal.*
import kyo.*
import scala.annotation.tailrec
import scala.language.implicitConversions
import scala.util.NotGiven

case class <[+A, -S](private val curr: A | Kyo[A, S]) extends AnyVal

object `<`:

    implicit private[kyo2] inline def apply[A, S](p: Kyo[A, S]): A < S = new <(p)

    implicit inline def lift[A, S](v: A)(using inline ng: NotGiven[A <:< (Any < Nothing)]): A < S = <(v)

    extension [A, S](inline v: A < S)

        inline def unit: Unit < S =
            map(_ => ())

        inline def andThen[U, S2](inline f: => U < S2)(using inline ev: A => Unit): U < (S & S2) =
            map(_ => f)

        inline def flatMap[U, S2](inline f: A => U < S2): U < (S & S2) =
            map(v => f(v))

        inline def pipe[U, S2](inline f: A < S => U < S2): U < S2 =
            f(v)

        inline def repeat(i: Int)(using ev: A => Unit): Unit < S =
            if i <= 0 then () else andThen(repeat(i - 1))

        inline def map[U, S2](inline f: Safepoint ?=> A => U < S2)(
            using inline _frame: Frame
        )(using Safepoint): U < (S & S2) =
            def mapLoop(v: A < S)(using Safepoint): U < (S & S2) =
                v match
                    case <(kyo: KyoSuspend[IX, OX, EX, Any, A, S] @unchecked) =>
                        new KyoContinue[IX, OX, EX, Any, U, S & S2](kyo):
                            def frame = _frame
                            def apply(v: OX[Any], values: Context)(using Safepoint) =
                                mapLoop(kyo(v, values))
                    case <(v) =>
                        val value = v.asInstanceOf[A]
                        Safepoint.handle(
                            suspend = mapLoop(value),
                            continue = f(value)
                        )
            mapLoop(v)
        end map
    end extension

    extension [A, S, S2](inline kyo: A < S < S2)
        inline def flatten: A < (S & S2) =
            kyo.map(identity)

    extension [T](inline v: T < Any)
        inline def eval: T =
            @tailrec def evalLoop(kyo: T < Any)(using Safepoint): T =
                kyo match
                    case <(kyo: KyoSuspend[Const[Unit], Const[Unit], Defer, Any, T, Any] @unchecked)
                        if kyo.tag =:= Tag[Defer] =>
                        evalLoop(kyo((), Context.empty))
                    case <(kyo: Kyo[T, Any] @unchecked) =>
                        kyo2.bug.failTag(kyo, Tag[Any])
                    case <(v) =>
                        v.asInstanceOf[T]
                end match
            end evalLoop
            Safepoint.eval(evalLoop(v))
        end eval
    end extension
end `<`
