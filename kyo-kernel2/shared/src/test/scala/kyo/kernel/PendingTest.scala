package kyo.kernel

import kyo.kernel.internal.*
import org.scalatest.freespec.AnyFreeSpec
import scala.annotation.tailrec

class PendingTest extends AnyFreeSpec:

    "a settled value lifts and evaluates" in {
        val v: Int < Any = 42
        assert(v.eval == 42)
    }

    "map on a settled value runs eagerly" in {
        var ran = false
        val v = (1: Int < Any).map { n =>
            ran = true
            n + 1
        }
        assert(ran)
        assert(v.eval == 2)
    }

    "map composes" in {
        val v: Int < Any = (1: Int < Any).map(_ + 1).map(_ * 10)
        assert(v.eval == 20)
    }

    "flatMap and andThen" in {
        val v = (1: Int < Any).flatMap(n => (n + 1: Int < Any)).andThen(10: Int < Any)
        assert(v.eval == 10)
    }

    "a computation as a value round-trips through the nested box" in {
        def box[A](v: A): A < Any    = v
        val inner: Int < Any         = (1: Int < Any).map(_ + 1)
        val outer: (Int < Any) < Any = box(inner)
        assert(outer.eval.eval == 2)
    }

    "a computation as a value survives mapping" in {
        def box[A](v: A): A < Any = v
        val inner: Int < Any      = (1: Int < Any).map(_ + 1)
        val v: Int < Any          = box(inner).map(c => c.map(_ * 10))
        assert(v.eval == 20)
    }

    "a pending value does not lift into a nested computation implicitly" in {
        assertTypeError("val x: (Int < Any) < Any = (1: Int < Any).map(_ + 1)")
    }

    "a kyo module does not lift into a computation" in {
        assertTypeError("val x: ArrowEffect.type < Any = ArrowEffect")
    }

    "deep map chains evaluate" in {
        def chain(n: Int, v: Int < Any): Int < Any =
            if n == 0 then v else chain(n - 1, v.map(_ + 1))
        assert(chain(10000, 0).eval == 10000)
    }

    "deep nested computations evaluate" in {
        def loop(n: Int): Int < Any =
            if n == 0 then 0 else (n: Int < Any).map(_ => loop(n - 1))
        assert(loop(100000).eval == 0)
    }

    "construction past the safepoint budget rescues instead of overflowing" in {
        def loop(n: Int): Int < Any =
            if n == 0 then 0 else (n: Int < Any).map(_ => loop(n - 1))
        val v = loop(Safepoint.Period * 4)
        assert(v.eval == 0)
    }

    "a long map tower on a rescued computation evaluates in bounded stack" in {
        def loop(n: Int): Int < Any =
            if n == 0 then 0 else (n: Int < Any).map(_ => loop(n - 1))
        @tailrec def tower(v: Int < Any, n: Int): Int < Any =
            if n == 0 then v else tower(v.map(_ + 1), n - 1)
        val v = tower(loop(Safepoint.Period * 4), 1000000)
        assert(v.eval == 1000000)
    }

    "depth leaked by throwing maps resets at the eval loop" in {
        def loop(n: Int): Int < Any =
            if n == 0 then 0 else (n: Int < Any).map(_ => loop(n - 1))
        def leaky(): Unit =
            try
                val _ = (1: Int < Any).map(_ => (throw new IllegalStateException("leak")): Int)
                ()
            catch
                case _: IllegalStateException => ()
        val v =
            loop(Safepoint.Period * 2).map { z =>
                var i = 0
                while i < Safepoint.Period * 2 do
                    leaky()
                    i += 1
                z
            }.map(_ + 1)
        assert(v.eval == 1)
    }

end PendingTest
