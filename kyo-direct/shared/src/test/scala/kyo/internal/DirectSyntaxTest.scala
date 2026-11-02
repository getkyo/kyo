package kyo.internal

import kyo.*

/** The marker and boundary annotations a front end uses to plant `.now` and lowered computations through its own inline methods. */
class DirectSyntaxTest extends kyo.test.Test[Any]:
    import DirectSyntaxTest.*

    "a method carrying NowMarker is sequenced like .now" in {
        val value = direct {
            val n = mark(Var.get[Int])
            n + mark(Var.update[Int](x => x + 1))
        }
        assert(Var.runTuple(1)(value).eval == (2, 3))
    }

    "a method carrying Boundary wraps a computation that is a value" in {
        val value = direct {
            val computation = lowered(Var.get[Int].map(_ + 1))
            computation.now + 1
        }
        assert(Var.run(1)(value).eval == 3)
    }

    "a boundary as a statement drops its effects and is rejected" in {
        typeCheckFailure("""
            direct {
                lowered(Var.get[Int])
                1
            }
        """)("Effectful computations must explicitly use either .now or .later")
    }
end DirectSyntaxTest

object DirectSyntaxTest:
    @NowMarker
    inline def mark[A, S](inline value: A < S): A = throw new IllegalStateException("not lowered")

    @Boundary
    inline def lowered[A](inline value: A): A = value
end DirectSyntaxTest
