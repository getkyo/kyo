package kyo

import kyo.kernel.*
import org.scalatest.freespec.AnyFreeSpec

class ArrowTest extends AnyFreeSpec:

    def inc(using _frame: Frame): Arrow.Transform[Int, Int, Any] =
        new Arrow.Transform[Int, Int, Any]:
            def frame = _frame
            def apply[C, S2](v: Int < S2, next: Arrow[Int, C, S2]) =
                v.map(i => next(i + 1))

    "identity returns its input" in {
        assert(Arrow[Int](42).eval == 42)
    }

    "applies a transform to a plain value" in {
        assert(inc(41).eval == 42)
    }

    "chain composes in order" in {
        def double(using _frame: Frame) =
            new Arrow.Transform[Int, Int, Any]:
                def frame = _frame
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
        assert(inc.toString.contains("ArrowTest.scala"))
    }

    "a composed arrow renders its shape and frame info" in {
        val step = inc.chain(inc)
        assert(step.toString.startsWith("Arrow.Step("))
        assert(step.toString.contains("ArrowTest.scala"))
        // the proto composes with `Chain` where the old kernel had `AndThen`
        val chained = inc.chain(inc).chain(inc)
        assert(chained.toString.startsWith("Arrow.Chain("))
        assert(chained.toString.contains("Arrow.Step("))
    }

end ArrowTest
