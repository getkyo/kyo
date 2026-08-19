package kyo.kernel.internal

import kyo.Arrow
import kyo.Const
import kyo.Tag
import kyo.discard
import kyo.kernel.*
import org.scalatest.freespec.AnyFreeSpec
import scala.annotation.tailrec
import scala.util.control.NoStackTrace

class EffectTraceTest extends AnyFreeSpec:

    sealed trait Ask extends ArrowEffect[Const[Unit], Const[Int]]
    def ask: Int < Ask = ArrowEffect.suspend[Any](Tag[Ask], ())

    sealed trait Say extends ArrowEffect[Const[String], Const[Unit]]
    def say(s: String): Unit < Say = ArrowEffect.suspend[Any](Tag[Say], s)

    def answerAsk[A](value: Int)(v: A < Ask): A < Any =
        ArrowEffect.handleLoop(Tag[Ask], v)([C] => _ => Loop.continue(value), a => a)

    def answerSay[A](v: A < Say): A < Any =
        ArrowEffect.handleLoop(Tag[Say], v)([C] => _ => Loop.continue(()), a => a)

    // row-generic variants, so a region can be handled while another effect stays open
    def answerAskIn[A, S](value: Int)(v: A < (Ask & S)): A < S =
        ArrowEffect.handleLoop(Tag[Ask], v)([C] => _ => Loop.continue(value), a => a)

    def dropSay[A, S](v: A < (Say & S)): A < S =
        ArrowEffect.handleLoop(Tag[Say], v)([C] => _ => Loop.continue(()), a => a)

    def carrier(ex: Throwable): Option[EffectTrace] =
        ex.getSuppressed.collectFirst { case t: EffectTrace => t }

    def methods(ex: Throwable): List[String] =
        carrier(ex).toList.flatMap(_.elements.iterator.map(_.getMethodName))

    def classes(ex: Throwable): List[String] =
        carrier(ex).toList.flatMap(_.elements.iterator.map(_.getClassName))

    class Boom extends RuntimeException("boom")

    def innerStep(v: Int < Ask): Int < Ask = v.map(_ => throw new Boom)
    def outerStep(v: Int < Ask): Int < Ask = innerStep(v).map(_ + 1)

    def stepA(v: Int < Ask): Int < Ask = v.map(_ + 1)
    def stepB(v: Int < Ask): Int < Ask = v.map(_ + 2)

    // alternating sites so consecutive frames differ: a run of one frame collapses to one
    // element, which is what a loop over a single map site produces
    def deepChain(depth: Int): Int < Ask =
        @tailrec def loop(i: Int, acc: Int < Ask): Int < Ask =
            if i == 0 then acc
            else loop(i - 1, if i % 2 == 0 then stepA(acc) else stepB(acc))
        loop(depth, innerStep(ask))
    end deepChain

    "a throw in a handler clause carries the suspension and its region" in {
        val boom = new RuntimeException("boom")
        val ex = intercept[RuntimeException] {
            Eval(ArrowEffect.handleLoop(Tag[Ask], ask.map(_ + 1))([C] => _ => throw boom, a => a))
        }
        assert(ex eq boom)
        val t = carrier(ex)
        assert(t.nonEmpty)
        val msg = t.get.getMessage
        assert(msg.contains("EffectTraceTest.scala"))
        assert(msg.contains("ask"))
        assert(msg.contains("handle"))
        assert(ex.getStackTrace.head.getFileName == "EffectTraceTest.scala")
    }

    "a throw in a continuation frame names its site" in {
        def deep(i: Int): Int < Any =
            if i == 0 then 0 else (0: Int < Any).map(_ => deep(i - 1))
        def boomAt(v: Int < Any): Int < Any = v.map(_ => (throw new RuntimeException("late")): Int)
        val ex                              = intercept[RuntimeException](Eval(boomAt(deep(10000))))
        val t                               = carrier(ex)
        assert(t.nonEmpty)
        assert(t.get.getMessage.contains("boomAt"))
    }

    "an unhandled suspension arrives enriched" in {
        val ex = intercept[Throwable](Eval(ask.asInstanceOf[Int < Any]))
        assert(ex.getMessage.contains("unhandled suspension"))
        val t = carrier(ex)
        assert(t.nonEmpty)
        assert(t.get.getMessage.contains("ask"))
    }

    "nested drives accumulate their regions innermost first" in {
        def innerBoom: Int =
            Eval(answerAsk(1)(ask.map(_ => (throw new RuntimeException("x")): Int)))
        val outer: Int < Any = answerSay(say("s").map(_ => innerBoom))
        val ex               = intercept[RuntimeException](Eval(outer))
        val t                = carrier(ex)
        assert(t.nonEmpty)
        val regions = t.get.elements.filter(_.getMethodName == "handle").map(_.getClassName)
        assert(regions.exists(_.contains("Ask")))
        assert(regions.exists(_.contains("Say")))
        assert(regions.indexWhere(_.contains("Ask")) < regions.indexWhere(_.contains("Say")))
    }

    "NoStackTrace keeps its carrier and its empty stack" in {
        class Silent extends Exception with NoStackTrace
        val ex = intercept[Silent] {
            Eval(ArrowEffect.handleLoop(Tag[Ask], ask)([C] => _ => throw new Silent, a => a))
        }
        assert(carrier(ex).nonEmpty)
        assert(ex.getStackTrace.isEmpty)
    }

    "a chain past the cap reports the drop" in {
        def deep(i: Int): Int < Any =
            if i == 0 then (0: Int < Any).map(_ => (throw new RuntimeException("deep")): Int)
            else (0: Int < Any).map(_ => deep(i - 1))
        def tower(v: Int < Any, n: Int): Int < Any =
            if n == 0 then v
            else if n % 2 == 0 then tower(v.map(_ + 1), n - 1)
            else tower(v.map(_ + 2), n - 1)
        val ex = intercept[RuntimeException](Eval(tower(deep(10000), 100)))
        val t  = carrier(ex)
        assert(t.nonEmpty)
        assert(t.get.dropped > 0)
        assert(t.get.getMessage.contains("more not walked"))
    }

    "a fatal error passes through untouched" in {
        val ex = intercept[StackOverflowError] {
            Eval(ArrowEffect.handleLoop(Tag[Ask], ask)([C] => _ => throw new StackOverflowError, a => a))
        }
        assert(carrier(ex).isEmpty)
        assert(ex.getSuppressed.isEmpty)
    }

    "the effect frames of a throw inside a mapped step" - {

        "are carried through a drive" in {
            val ex = intercept[Boom](Eval(answerAsk(1)(outerStep(ask))))
            assert(methods(ex).contains("innerStep"))
            assert(methods(ex).contains("outerStep"))
            assert(methods(ex).contains("ask"))
        }

        "name the call site's callee and the enclosing definition" in {
            val ex  = intercept[Boom](Eval(answerAsk(1)(outerStep(ask))))
            val els = carrier(ex).get.elements.toList
            val inner = els.find(_.getMethodName == "innerStep") match
                case Some(e) => e
                case None    => fail("no element for innerStep")
            assert(inner.getClassName == s"map @ ${classOf[EffectTraceTest].getName}")
            assert(inner.getFileName == "EffectTraceTest.scala")
            assert(inner.getLineNumber > 0)
        }

        "run innermost first" in {
            val ex = intercept[Boom](Eval(answerAsk(1)(outerStep(ask))))
            val ms = methods(ex)
            assert(ms.indexOf("ask") < ms.indexOf("innerStep"))
            assert(ms.indexOf("innerStep") < ms.indexOf("outerStep"))
        }

        "skip the internal frame placeholder" in {
            val ex = intercept[Boom](Eval(answerAsk(1)(outerStep(ask))))
            assert(carrier(ex).get.elements.forall(_.getFileName != "<internal>"))
        }
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

        val ex = intercept[Boom](Eval(answerAsk(1)(around(ask))))
        assert(!raw.exists(_.getMethodName == "around"))
        assert(methods(ex).contains("around"))
        assert(methods(ex).contains("thrower"))
    }

    "a Defer bounce" - {

        "carries the deferred site" in {
            def deferred: Int < Any = Effect.defer[Int, Any](throw new Boom)
            val ex                  = intercept[Boom](Eval(deferred))
            assert(methods(ex).contains("deferred"))
            assert(classes(ex).exists(_.startsWith("defer @ ")))
        }

        "carries the steps after a budget rescue" in {
            def boomLater(v: Int): Int < Any = Effect.defer[Int, Any](throw new Boom)
            def rescued: Int < Any =
                @tailrec def loop(i: Int, acc: Int < Any): Int < Any =
                    if i == 0 then acc else loop(i - 1, acc.map(_ + 1))
                loop(600, boomLater(0))
            end rescued
            val ex = intercept[Boom](Eval(rescued))
            assert(methods(ex).contains("boomLater") || methods(ex).contains("rescued"))
        }
    }

    "region nesting" - {

        def useAsk: Int < (Ask & Say) = outerStep(ask).map(v => say("x").map(_ => v))

        "appears as one element per handler tag, innermost first" in {
            val ex = intercept[Boom](Eval(dropSay(answerAskIn(1)(useAsk))))
            val cs = classes(ex)
            assert(cs.exists(_.endsWith("Ask")))
            assert(cs.exists(_.endsWith("Say")))
            assert(cs.indexWhere(_.endsWith("Ask")) < cs.indexWhere(_.endsWith("Say")))
        }

        "names each region exactly once" in {
            val ex = intercept[Boom](Eval(dropSay(answerAskIn(1)(useAsk))))
            assert(classes(ex).count(_.endsWith("Ask")) == 1)
            assert(classes(ex).count(_.endsWith("Say")) == 1)
        }
    }

    "the synthesized frames lead the spliced trace" in {
        val ex = intercept[Boom](Eval(answerAsk(1)(outerStep(ask))))
        val es = ex.getStackTrace
        val cs = carrier(ex).get.elements
        assert(es.length >= cs.length)
        assert(es.take(cs.length).sameElements(cs))
    }

    "a fatal error keeps its original stack trace" in {
        val fatal                    = new StackOverflowError("fatal")
        val before                   = fatal.getStackTrace
        def fatalStep: Int < Ask     = ask.map(_ => throw fatal)
        var caught: Throwable | Null = null
        try discard(Eval(answerAsk(1)(fatalStep)))
        catch case ex: Throwable => caught = ex
        assert(caught eq fatal)
        assert(fatal.getSuppressed.isEmpty)
        assert(fatal.getStackTrace.sameElements(before))
    }

    "the cap" - {

        "stops the walk at exactly the cap and records what it did not reach" in {
            val ex = intercept[Boom](Eval(answerAsk(1)(deepChain(200))))
            assert(carrier(ex).get.elements.length == 64)
            assert(carrier(ex).get.dropped > 0)
        }

        "bounds a chain far deeper than the Java stack" in {
            val ex = intercept[Boom](Eval(answerAsk(1)(deepChain(1000000))))
            assert(carrier(ex).get.elements.length == 64)
            assert(carrier(ex).get.dropped > 0)
        }
    }

    // "a failure of the walk itself leaves the original failure travelling" in {
    //     // a node whose frame cannot be read: describing a failure must never replace the failure
    //     // being described
    //     val unreadable =
    //         new Arrow.Suspend[Const[Unit], Const[Int], Ask, Int, Ask]:
    //             def tag          = Tag[Ask]
    //             def input        = ()
    //             def frame        = throw new IllegalStateException("frame read failed")
    //             def cont(v: Int) = v
    //     val ex = intercept[Boom](Eval(answerAsk(1)((unreadable: Int < Ask).map(_ => throw new Boom))))
    //     assert(ex.getMessage == "boom")
    // }

    "the carrier renders the frames as a message" in {
        val ex  = intercept[Boom](Eval(answerAsk(1)(outerStep(ask))))
        val msg = carrier(ex).get.getMessage
        assert(msg.startsWith("effect trace:"))
        assert(msg.contains("innerStep"))
        assert(msg.contains("outerStep"))
    }

    // Effect.catching is not in this kernel yet. These are the previous kernel's cases that
    // depend on it, kept as the specification for the port.
    //
    // "the effect frames of a throw are carried through a catching guard" in {
    //     val ex = intercept[Boom](Eval(answerAsk(1)(Effect.catching(outerStep(ask))(e => throw e))))
    //     assert(methods(ex).contains("innerStep"))
    //     assert(methods(ex).contains("outerStep"))
    //     assert(classes(ex).exists(_.startsWith("catching @ ")))
    // }
    //
    // "a second crossing rewrites the spliced trace rather than duplicating it" in {
    //     def rethrown: Int < Any = Effect.catching(Eval(answerAsk(1)(outerStep(ask))))(e => throw e)
    //     val ex                  = intercept[Boom](Eval(rethrown))
    //     assert(ex.getStackTrace.count(_.getMethodName == "innerStep") == 1)
    //     assert(ex.getStackTrace.count(_.getMethodName == "outerStep") == 1)
    //     assert(ex.getSuppressed.count(_.isInstanceOf[EffectTrace]) == 1)
    // }

end EffectTraceTest
