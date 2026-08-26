package kyo.kernel.internal

import kyo.Arrow
import kyo.Const
import kyo.Tag
import kyo.discard
import kyo.kernel.*
import scala.annotation.tailrec
import scala.util.control.NoStackTrace

class EffectTraceTest extends kyo.test.Test[Any]:

    sealed trait Ask extends ArrowEffect[Const[Unit], Const[Int]]
    def ask: Int < Ask = ArrowEffect.suspend[Any](Tag[Ask], ())

    // the fused node: the operation carries its own continuation
    inline def askWith[B, S](inline f: Int => B < S): B < (Ask & S) =
        ArrowEffect.suspendWith[Any](Tag[Ask], ())(f)

    sealed trait Say extends ArrowEffect[Const[String], Const[Unit]]
    def say(s: String): Unit < Say = ArrowEffect.suspend[Any](Tag[Say], s)

    // row-generic, so a region can be handled while another effect stays open
    def answerAsk[A, S](value: Int)(v: A < (Ask & S)): A < S =
        ArrowEffect.handleLoop(Tag[Ask], v)([C] => _ => Loop.continue(value))

    def dropSay[A, S](v: A < (Say & S)): A < S =
        ArrowEffect.handleLoop(Tag[Say], v)([C] => _ => Loop.continue(()))

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
        assert(ex.getMessage.contains("Unexpected pending effect"))
        val t = carrier(ex)
        assert(t.nonEmpty)
        assert(t.get.getMessage.contains("ask"))
    }

    "nested evals accumulate their regions innermost first" in {
        def innerBoom: Int =
            Eval(answerAsk(1)(ask.map(_ => (throw new RuntimeException("x")): Int)))
        val outer: Int < Any = dropSay(say("s").map(_ => innerBoom))
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

        // `ask` is answered before the map body runs, so by the time the throw happens the
        // suspension is behind the eval rather than ahead of it: a map over a suspension mints a
        // deferral node here rather than fusing into the operation. The fused form below is where
        // the operation's own frame is pinned.
        "are carried through an eval" in {
            val ex = intercept[Boom](Eval(answerAsk(1)(outerStep(ask))))
            assert(methods(ex).contains("innerStep"))
            assert(methods(ex).contains("outerStep"))
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
            assert(ms.indexOf("innerStep") < ms.indexOf("outerStep"))
        }

        "skip the internal frame placeholder" in {
            val ex = intercept[Boom](Eval(answerAsk(1)(outerStep(ask))))
            assert(carrier(ex).get.elements.forall(_.getFileName != "<internal>"))
        }

        "a fused suspension carries the operation's own frame" in {
            def fusedStep: Int < Ask                 = askWith(_ => throw new Boom)
            def aroundFused(v: Int < Ask): Int < Ask = v.map(_ + 1)
            val ex                                   = intercept[Boom](Eval(answerAsk(1)(aroundFused(fusedStep))))
            val ms                                   = methods(ex)
            assert(ms.contains("fusedStep"))
            assert(ms.contains("aroundFused"))
            assert(ms.indexOf("fusedStep") < ms.indexOf("aroundFused"))
            assert(classes(ex).exists(_.startsWith("askWith @ ")))
        }
    }

    "a suspension boundary the physical stack cannot cross" in {
        def thrower(v: Int < Ask): Int < Ask = v.map(_ => throw new Boom)
        def around(v: Int < Ask): Int < Ask  = thrower(v).map(_ + 1)
        val ex                               = intercept[Boom](Eval(answerAsk(1)(around(ask))))
        assert(methods(ex).contains("around"))
        assert(methods(ex).contains("thrower"))
    }

    "a deferred block" - {

        "carries the steps after a budget rescue" in {
            def boomHere: Int < Any = (0: Int < Any).map(_ => (throw new Boom): Int)
            def deep(i: Int): Int < Any =
                if i == 0 then boomHere else (0: Int < Any).map(_ => deep(i - 1))
            val ex = intercept[Boom](Eval(deep(600)))
            assert(methods(ex).contains("deep") || methods(ex).contains("boomHere"))
        }

        // Known limit, recorded rather than guarded: a throw from the body of `Effect.defer` happens
        // while the eval reads the node's payload, which is the one path into user code the attach
        // sites do not cover. Guarding it would put a try region on the deferral arm, the hottest
        // arm of the eval, to describe a failure on a surface that carries no frame of its own
        // (Kyo.Defer declares no `frame`). The exception propagates correctly; it arrives without
        // effect frames.
        //
        // "carries the deferred site" in {
        //     def deferred: Int < Any = Effect.defer[Int, Any](throw new Boom)
        //     val ex                  = intercept[Boom](Eval(deferred))
        //     assert(methods(ex).contains("deferred"))
        //     assert(classes(ex).exists(_.startsWith("defer @ ")))
        // }
    }

    "region nesting" - {

        def useAsk: Int < (Ask & Say) = outerStep(ask).map(v => say("x").map(_ => v))

        "appears as one element per handler tag, innermost first" in {
            val ex = intercept[Boom](Eval(dropSay(answerAsk(1)(useAsk))))
            val cs = classes(ex)
            assert(cs.exists(_.endsWith("Ask")))
            assert(cs.exists(_.endsWith("Say")))
            assert(cs.indexWhere(_.endsWith("Ask")) < cs.indexWhere(_.endsWith("Say")))
        }

        "names each region exactly once" in {
            val ex = intercept[Boom](Eval(dropSay(answerAsk(1)(useAsk))))
            assert(classes(ex).count(_.endsWith("Ask")) == 1)
            assert(classes(ex).count(_.endsWith("Say")) == 1)
        }

        "a fused region names the body, then the region" in {
            val fused: Int < Any =
                ArrowEffect.handleLoopWith[Const[Unit], Const[Int], Ask, Int, Int, Any, Any](Tag[Ask], innerStep(ask))(
                    [C] => _ => Loop.continue(1),
                    a => a
                )((_: Int) + 1)
            val ex  = intercept[Boom](Eval(fused))
            val els = carrier(ex).get.elements.toList
            assert(els.exists(_.getMethodName == "innerStep"))
            assert(els.exists(_.getMethodName == "handle"))
            assert(els.indexWhere(_.getMethodName == "innerStep") < els.indexWhere(_.getMethodName == "handle"))
        }

        // A clause that suspends produces a self-referential adapter node, which the walk reaches in
        // its arrow role. The assertion that matters most is that the case terminates at all: walked
        // in the node role it would re-enqueue itself forever, inside a catch, with an exception in
        // flight.
        //
        // Accepted limit, recorded rather than papered over: the region body's own frames do not
        // appear here. The clause's answer is settled, so the eval resumes the captured
        // continuation strictly inside `map` rather than through a delivery site, and by the time
        // the throw reaches an attach site those frames have already been consumed. The region this
        // clause serves is absent for a second reason: the eval pops that handler for the clause's
        // duration, which is the clause-scope semantics. What survives is the clause's own frame and
        // the region that answered the clause.
        "a throw under an emitting clause walks without looping" in {
            val v: Int < Any =
                dropSay(
                    ArrowEffect.handleLoop(Tag[Ask], innerStep(ask))(
                        [C] => _ => say("e").map(_ => Loop.continue(1: Int < Any)),
                        a => a
                    )
                )
            val ex = intercept[Boom](Eval(v))
            assert(carrier(ex).nonEmpty)
            assert(classes(ex).exists(_.endsWith("Say")))
            assert(carrier(ex).get.elements.forall(_.getFileName != "<internal>"))
        }

        "a throw in the second application of a multi-shot capture names each region once" in {
            val r: Int < Any =
                ArrowEffect.handleCont(Tag[Ask], innerStep(ask))(
                    [C] => (_, cont) => cont(1).map(_ => cont(2)),
                    a => a
                )
            val ex = intercept[Boom](Eval(r))
            assert(classes(ex).count(_.endsWith("Ask")) == 1)
        }
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

    "a failure of the walk itself leaves the original failure travelling" in {
        // a node whose frame cannot be read: describing a failure must never replace the
        // failure being described
        val unreadable =
            new Kyo.Suspend[Const[Unit], Const[Int], Ask, Any, Int, Any]:
                def tag   = Tag[Ask]
                def input = ()
                def frame = throw new IllegalStateException("frame read failed")
                def cont  = Arrow.id[Int]
        val ex = intercept[Throwable](Eval(unreadable.asInstanceOf[Int < Any]))
        assert(ex.getMessage.contains("Unexpected pending effect"))
        assert(carrier(ex).toList.flatMap(_.elements.toList).isEmpty)
    }

    "the carrier renders the frames as a message" in {
        val ex  = intercept[Boom](Eval(answerAsk(1)(outerStep(ask))))
        val msg = carrier(ex).get.getMessage
        assert(msg.startsWith("effect trace:"))
        assert(msg.contains("innerStep"))
        assert(msg.contains("outerStep"))
    }

    "the effect frames of a throw are carried through a catching guard" in {
        val ex = intercept[Boom](Eval(answerAsk(1)(Effect.catching(outerStep(ask))(e => throw e))))
        assert(methods(ex).contains("innerStep"))
        assert(methods(ex).contains("outerStep"))
        assert(classes(ex).exists(_.startsWith("catching @ ")))
    }

    "a second crossing rewrites the spliced trace rather than duplicating it" in {
        def rethrown: Int < Any = Effect.catching {
            val crossed: Int = Eval(answerAsk(1)(outerStep(ask)))
            crossed
        }(e => throw e)
        val ex = intercept[Boom](Eval(rethrown))
        assert(ex.getStackTrace.count(_.getMethodName == "innerStep") == 1)
        assert(ex.getStackTrace.count(_.getMethodName == "outerStep") == 1)
        assert(ex.getSuppressed.count(_.isInstanceOf[EffectTrace]) == 1)
    }

    // the concurrent-attach race on a shared exception instance lives in the jvm-native
    // EffectTraceThreadingTest: it needs real threads

end EffectTraceTest
