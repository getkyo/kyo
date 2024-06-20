package kyo2.kernel

import kyo2.Test
import kyo2.kernel.*

class PendingTest extends Test:

    "lift" in {
        val x: Int < Any = 5
        assert(x.eval == 5)
    }

    "map" in {
        val x: Int < Any    = 5
        val y: String < Any = x.map(_.toString)
        assert(y.eval == "5")
    }

    "flatMap" in {
        val x: Int < Any    = 5
        val y: String < Any = x.flatMap(i => (i * 2).toString)
        assert(y.eval == "10")
    }

    "handle chaining with for-comprehension" in {
        val result =
            for
                x <- 5: Int < Any
                y <- 3: Int < Any
            yield x + y

        assert(result.eval == 8)
    }

    "flatten" in {
        val x: Int < Any < Any = (10: Int < Any): Int < Any < Any
        val y: Int < Any       = x.flatten
        assert(y.eval == 10)
    }

    "unit" in {
        val x: Int < Any  = 5
        val y: Unit < Any = x.unit
        assert(y.eval == (()))
    }

    "andThen" in {
        val x: Unit < Any   = ()
        val y: String < Any = x.andThen("result")
        assert(y.eval == "result")
    }

    "repeat" in {
        var counter       = 0
        val x: Unit < Any = Effect.defer { counter += 1 }
        x.repeat(3).eval
        assert(counter == 3)
    }

    "eval" in {
        val x: Int < Any = 10
        assert(x.eval == 10)
    }

    "eval should not compile for specific effects" in {
        trait CustomEffect extends Effect[Const[Unit], Const[Unit]]
        assertDoesNotCompile("val x: Int < CustomEffect = 5; x.eval")
    }

end PendingTest
