package kyo

import kyo.kernel.*
import org.scalatest.freespec.AnyFreeSpec

class ArrowTest extends AnyFreeSpec:

    given Frame = Frame.internal

    def inc: Arrow.Transform[Int, Int, Any] =
        new Arrow.Transform[Int, Int, Any]:
            def frame = Frame.internal
            def apply[C, S2](v: Int < S2, next: Arrow[Int, C, S2]) =
                v.map(i => next(i + 1))

    "identity returns its input" in {
        assert(Arrow[Int](42).eval == 42)
    }

    "applies a transform to a plain value" in {
        assert(inc(41).eval == 42)
    }

    "chain composes in order" in {
        val double =
            new Arrow.Transform[Int, Int, Any]:
                def frame = Frame.internal
                def apply[C, S2](v: Int < S2, next: Arrow[Int, C, S2]) =
                    v.map(i => next(i * 2))
        assert(inc.chain(double)(20).eval == 42)
        assert(double.chain(inc)(20).eval == 41)
    }

    "chain with identity returns the other arrow" in {
        val f = inc
        assert(Arrow[Int].chain(f) eq f)
        assert(f.chain(Arrow[Int]) eq f)
    }

    "step exposes the first transform and the rest" in {
        val first  = inc
        val second = inc
        val step   = first.chain(second).step
        assert(step.head eq first)
        assert(step.tail eq second)
    }

    "toString renders identity and transform frames" in {
        assert(Arrow[Int].toString == "Arrow(identity)")
        assert(inc.toString.startsWith("Arrow("))
    }

    "a composed arrow renders only its first transform" in {
        val first  = inc
        val second = inc
        assert(first.chain(second).toString == first.toString)
    }

end ArrowTest
