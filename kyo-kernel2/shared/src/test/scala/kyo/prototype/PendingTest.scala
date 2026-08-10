package kyo.prototype

import kyo.test.Test

class PendingTest extends Test[Any]:

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
        val inner: Int < Any         = (1: Int < Any).map(_ + 1)
        val outer: (Int < Any) < Any = inner
        assert(outer.eval.eval == 2)
    }

    "a computation as a value survives mapping" in {
        val inner: Int < Any = (1: Int < Any).map(_ + 1)
        val v: Int < Any     = (inner: (Int < Any) < Any).map(c => c.map(_ * 10))
        assert(v.eval == 20)
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

    "evalPartial pauses at the stop check and the remainder resumes" in {
        def loop(n: Int): Int < Any =
            if n == 0 then 0 else (n: Int < Any).map(_ => loop(n - 1))
        var calls = 0
        val stop = () =>
            calls += 1
            calls > 1
        val v      = loop(Safepoint.Period * 4)
        val paused = v.evalPartial(stop)
        assert(calls >= 2)
        assert(paused.eval == 0)
    }
end PendingTest
