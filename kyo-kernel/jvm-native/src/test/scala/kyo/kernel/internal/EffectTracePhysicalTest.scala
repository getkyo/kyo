package kyo.kernel.internal

import kyo.Const
import kyo.Loop
import kyo.Maybe
import kyo.Tag
import kyo.kernel.<
import kyo.kernel.ArrowEffect
import org.scalatest.freespec.AnyFreeSpec

class EffectTracePhysicalTest extends AnyFreeSpec:

    sealed trait Ask extends ArrowEffect[Const[Unit], Const[Int]]
    def ask: Int < Ask = ArrowEffect.suspend[Any](Tag[Ask], ())

    def answerAsk[A, S](value: Int)(v: A < (Ask & S)): A < S =
        ArrowEffect.handleLoop(Tag[Ask], v)([C] => _ => Loop.continue(value), a => a)

    private def carrier(ex: Throwable): Maybe[EffectTrace] =
        Maybe.fromOption(ex.getSuppressed.collectFirst { case c: EffectTrace => c })

    class Boom extends RuntimeException("boom")

    def innerStep(v: Int < Ask): Int < Ask = v.map(_ => throw new Boom)
    def outerStep(v: Int < Ask): Int < Ask = innerStep(v).map(_ + 1)

    "the synthesized frames lead the spliced trace" in {
        val ex = intercept[Boom](answerAsk(1)(outerStep(ask)).eval)
        val es = ex.getStackTrace
        val cs = carrier(ex).get.elements
        assert(cs.nonEmpty)
        assert(es.length >= cs.length)
        assert(es.take(cs.length).sameElements(cs))
    }

    "the spliced trace leads with the file the effect frames name" in {
        val ex = intercept[RuntimeException] {
            ArrowEffect.handleLoop(Tag[Ask], ask.map(_ + 1))([C] => _ => throw new Boom, a => a).eval
        }
        assert(ex.getStackTrace.head.getFileName == "EffectTracePhysicalTest.scala")
    }

    "a suspension boundary the physical stack cannot cross" in {
        var raw: Array[StackTraceElement] = Array.empty
        def thrower(v: Int < Ask): Int < Ask =
            v.map { _ =>
                val ex = new Boom
                raw = ex.getStackTrace
                throw ex
            }
        def around(v: Int < Ask): Int < Ask = thrower(v).map(_ + 1)

        val ex = intercept[Boom](answerAsk(1)(around(ask)).eval)
        assert(!raw.exists(_.getMethodName == "around"))
        val methods = carrier(ex).toList.flatMap(_.elements.iterator.map(_.getMethodName))
        assert(methods.contains("around"))
        assert(methods.contains("thrower"))
    }

end EffectTracePhysicalTest
