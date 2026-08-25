package kyo.kernel.internal

import kyo.Const
import kyo.Tag
import kyo.kernel.*
import org.scalatest.freespec.AnyFreeSpec

/** The cases that assert the shape of the *physical* stack trace, rather than the reconstructed effect frames.
  *
  * Kept out of the shared corpus because those assertions are not portable: Scala.js mangles method names and shapes `getStackTrace` around
  * source maps, so a platform-independent expectation would have to be weakened to the point of proving nothing.
  */
class EffectTracePhysicalTest extends AnyFreeSpec:

    sealed trait Ask extends ArrowEffect[Const[Unit], Const[Int]]
    def ask: Int < Ask = ArrowEffect.suspend[Any](Tag[Ask], ())

    def answerAsk[A, S](value: Int)(v: A < (Ask & S)): A < S =
        ArrowEffect.handleLoop(Tag[Ask], v)([C] => _ => Loop.continue(value: Int < Any), a => a)

    def carrier(ex: Throwable): Option[EffectTrace] =
        ex.getSuppressed.collectFirst { case t: EffectTrace => t }

    class Boom extends RuntimeException("boom")

    def innerStep(v: Int < Ask): Int < Ask = v.map(_ => throw new Boom)
    def outerStep(v: Int < Ask): Int < Ask = innerStep(v).map(_ + 1)

    "the synthesized frames lead the spliced trace" in {
        val ex = intercept[Boom](Eval(answerAsk(1)(outerStep(ask))))
        val es = ex.getStackTrace
        val cs = carrier(ex).get.elements
        assert(cs.nonEmpty)
        assert(es.length >= cs.length)
        assert(es.take(cs.length).sameElements(cs))
    }

    "the spliced trace leads with the file the effect frames name" in {
        val ex = intercept[RuntimeException] {
            Eval(ArrowEffect.handleLoop(Tag[Ask], ask.map(_ + 1))([C] => _ => throw new Boom, a => a))
        }
        assert(ex.getStackTrace.head.getFileName == "EffectTracePhysicalTest.scala")
    }

    // the point of the case: the effect trace names what the physical trace cannot, because the
    // physical stack has already unwound past the suspension boundary by the time the throw is caught
    "a suspension boundary the physical stack cannot cross" in {
        var raw: Array[StackTraceElement] = Array.empty
        def thrower(v: Int < Ask): Int < Ask =
            v.map { _ =>
                val ex = new Boom
                raw = ex.getStackTrace
                throw ex
            }
        def around(v: Int < Ask): Int < Ask = thrower(v).map(_ + 1)

        val ex = intercept[Boom](Eval(answerAsk(1)(around(ask))))
        assert(!raw.exists(_.getMethodName == "around"))
        val methods = carrier(ex).toList.flatMap(_.elements.iterator.map(_.getMethodName))
        assert(methods.contains("around"))
        assert(methods.contains("thrower"))
    }

end EffectTracePhysicalTest
