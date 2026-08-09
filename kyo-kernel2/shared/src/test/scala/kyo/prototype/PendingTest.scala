package kyo.prototype

import kyo.test.Test

class PendingTest extends Test[Any]:

    "a settled value lifts and evaluates" in {
        val v: Int < Any = 42
        assert(v.eval == 42)
    }

    "map is lazy and composes" in {
        var ran = false
        val v: Int < Any = (1: Int < Any).map { n =>
            ran = true; n + 1
        }.map(_ * 10)
        assert(!ran)
        assert(v.eval == 20)
        assert(ran)
    }

    "a computation as a value round-trips through the nested box" in {
        val inner: Int < Any         = (1: Int < Any).map(_ + 1)
        val outer: (Int < Any) < Any = inner
        assert(outer.eval.eval == 2)
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

    "evalPartial pauses on the stop check and resumes with the remainder" in {
        var steps = 0
        def step(v: Int): Int < Any = (v: Int < Any).map { n =>
            steps += 1; n + 1
        }
        val v     = step(0).map(step).map(step)
        var calls = 0
        val stop = () =>
            calls += 1; calls > 2
        val paused = v.evalPartial(stop)
        assert(steps < 3)
        assert(paused.eval == 3)
        assert(steps == 3)
    }
end PendingTest
