package kyo.proto.kernel.internal

import kyo.Const
import kyo.Maybe
import kyo.Tag
import kyo.proto.Arrow
import kyo.proto.Loop
import kyo.proto.kernel.<
import kyo.proto.kernel.ArrowEffect
import kyo.proto.kernel.Effect
import org.scalatest.freespec.AnyFreeSpec
import scala.annotation.tailrec
import scala.util.control.NoStackTrace

class EffectTraceTest extends AnyFreeSpec:
    private def eval[A](v: A < Any): A = v.eval

    sealed trait Ask extends ArrowEffect[Const[Unit], Const[Int]]
    def ask: Int < Ask = ArrowEffect.suspend[Any](Tag[Ask], ())
    def runAsk[A, S](v: A < (Ask & S))(answer: Int): A < S =
        ArrowEffect.handleCont(Tag[Ask], v)([C] => (_, cont) => cont(answer))

    sealed trait Say extends ArrowEffect[Const[String], Const[Unit]]
    def say(s: String): Unit < Say = ArrowEffect.suspend[Any](Tag[Say], s)
    def runSay[A, S](v: A < (Say & S)): A < S =
        ArrowEffect.handleCont(Tag[Say], v)([C] => (_, cont) => cont(()))

    final class Boom extends RuntimeException("boom")

    private def carrier(ex: Throwable): Maybe[EffectTrace] =
        Maybe.fromOption(ex.getSuppressed.collectFirst { case c: EffectTrace => c })

    "a throw inside a region carries the region's label" in {
        val v  = runAsk(ask.map(_ => (throw Boom()): Int))(1)
        val ex = intercept[Boom](eval(v))
        val c  = carrier(ex)
        assert(c.nonEmpty)
        assert(ex.getMessage == "boom")
        assert(c.get.getMessage.contains(Tag[Ask].show))
        assert(ex.getStackTrace.exists(e => e.getClassName == Tag[Ask].show && e.getMethodName == "handle"))
    }

    "regions splice innermost first" in {
        val inner = runAsk(ask.map(_ => (throw Boom()): Int))(1)
        val outer = runSay(inner: Int < (Say & Any))
        val ex    = intercept[Boom](eval(outer))
        val trace = ex.getStackTrace
        val ask0  = trace.indexWhere(_.getClassName == Tag[Ask].show)
        val say0  = trace.indexWhere(_.getClassName == Tag[Say].show)
        assert(ask0 >= 0 && say0 >= 0 && ask0 < say0)
    }

    "the continuation a region holds contributes its frames" in {

        val v  = runAsk(ask.map(_ => (throw Boom()): Int))(1).map(_ + 1)
        val ex = intercept[Boom](eval(runSay(v: Int < Say)))
        assert(ex.getStackTrace.exists(e => e.getFileName == "EffectTraceTest.scala"))
    }

    "nested evals accumulate their regions innermost first" in {
        val inner = runAsk(ask.map(_ => (throw Boom()): Int))(1)

        val outer: Int < Any = runSay(say("x").map { _ =>
            val r: Int = eval(inner)
            r
        })
        val ex    = intercept[Boom](eval(outer))
        val trace = ex.getStackTrace
        assert(trace.exists(_.getClassName == Tag[Ask].show))
        assert(trace.exists(_.getClassName == Tag[Say].show))
        val regions = trace.filter(_.getMethodName == "handle").map(_.getClassName).toList
        assert(regions.indexOf(Tag[Ask].show) < regions.indexOf(Tag[Say].show))
        assert(ex.getSuppressed.count(_.isInstanceOf[EffectTrace]) == 1)
    }

    "a fused region names the body, then the region" in {
        val fused: Int < Any =
            ArrowEffect.handleLoopWith[Const[Unit], Const[Int], Ask, Int, Int, Any, Any](Tag[Ask], innerStep(ask))(
                [C] => _ => Loop.continue((), 1: Int < Any),
                a => a
            )((_: Int) + 1)
        val ex  = intercept[Boom](eval(fused))
        val els = carrier(ex).get.elements.toList
        assert(els.exists(_.getMethodName == "innerStep"))
        assert(els.exists(_.getMethodName == "handle"))
        assert(els.indexWhere(_.getMethodName == "innerStep") < els.indexWhere(_.getMethodName == "handle"))
    }

    "a recovery clause inspects the enriched exception" in {
        var sawCarrier = false
        val v = ArrowEffect.handleCont(Tag[Ask], ask.map(_ => (throw Boom()): Int))(
            [C] => (_, cont) => cont(1),
            a => a,
            ex =>
                sawCarrier = ex.getSuppressed.exists(_.isInstanceOf[EffectTrace]) &&
                    ex.getStackTrace.exists(_.getClassName == Tag[Ask].show)
                Maybe(-1)
        )
        assert(eval(v) == -1)
        assert(sawCarrier)
    }

    "a failure born in a recovery is described from the regions under it" in {
        final class Second extends RuntimeException("second")
        val inner: Int < Say = ArrowEffect.handleCont(Tag[Ask], ask.map(_ => (throw Boom()): Int))(
            [C] => (_, cont) => cont(1),
            a => a,
            _ => throw Second()
        )
        var enriched = Maybe.empty[Boolean]
        val checked: Int < Any = ArrowEffect.handleCont(Tag[Say], inner)(
            [C] => (_, cont) => cont(()),
            a => a,
            ex =>
                enriched = Maybe(ex.getMessage == "second" && ex.getStackTrace.exists(_.getClassName == Tag[Say].show))
                Maybe(-7)
        )
        assert(eval(checked) == -7)
        assert(enriched == Maybe(true))
    }

    "NoStackTrace keeps its carrier and skips the splice" in {
        final class Silent extends RuntimeException("silent") with NoStackTrace
        val v  = runAsk(ask.map(_ => (throw Silent()): Int))(1)
        val ex = intercept[Silent](eval(v))
        val c  = carrier(ex)
        assert(c.nonEmpty)
        assert(c.get.elements.nonEmpty)
        assert(ex.getStackTrace.isEmpty)
    }

    "a fatal error passes through untouched" in {
        val v  = runAsk(ask.map(_ => (throw new InterruptedException("stop")): Int))(1)
        val ex = intercept[InterruptedException](eval(v))
        assert(carrier(ex).isEmpty)
    }

    "a suppression-disabled exception travels untouched" in {

        object Shared extends RuntimeException("shared", null, false, false)
        val v  = runAsk(ask.map(_ => (throw Shared): Int))(1)
        val ex = intercept[Shared.type](eval(v))
        assert(carrier(ex).isEmpty)
        assert(ex.getStackTrace.isEmpty)
    }

    "a chain past the cap reports the drop" in {
        def wrap(n: Int, v: Int < Ask): Int < Ask =
            if n == 0 then v
            else wrap(n - 1, ArrowEffect.handleCont(Tag[Say], v: Int < (Say & Ask))([C] => (_, cont) => cont(())))
        val v  = runAsk(wrap(100, ask.map(_ => (throw Boom()): Int)))(1)
        val ex = intercept[Boom](eval(v))
        val c  = carrier(ex)
        assert(c.nonEmpty)
        assert(c.get.dropped > 0)
        assert(c.get.getMessage.contains("more not walked"))
    }

    "a throw with no region standing still carries the frames of the steps it was in" in {
        val v: Int < Any = runAsk(ask)(1).map(_ => (throw Boom()): Int).map(_ + 1)
        val ex           = intercept[Boom](eval(v))
        assert(carrier(ex).nonEmpty)
        assert(carrier(ex).get.elements.forall(_.getFileName == "EffectTraceTest.scala"))
        assert(carrier(ex).get.elements.forall(_.getClassName.startsWith("map @ ")))
        assert(!kyo.internal.Platform.isJVM || ex.getStackTrace.exists(_.getFileName == "EffectTraceTest.scala"))
    }

    "the carrier renders the frames as a message" in {
        val v  = runAsk(ask.map(_ => (throw Boom()): Int))(1)
        val ex = intercept[Boom](eval(v))
        assert(carrier(ex).get.getMessage.startsWith("effect trace:"))
    }
    private def methods(ex: Throwable): List[String] =
        carrier(ex).toList.flatMap(_.elements.iterator.map(_.getMethodName))

    private def classes(ex: Throwable): List[String] =
        carrier(ex).toList.flatMap(_.elements.iterator.map(_.getClassName))

    def innerStep(v: Int < Ask): Int < Ask = v.map(_ => throw new Boom)
    def outerStep(v: Int < Ask): Int < Ask = innerStep(v).map(_ + 1)

    def stepA(v: Int < Ask): Int < Ask = v.map(_ + 1)
    def stepB(v: Int < Ask): Int < Ask = v.map(_ + 2)

    def deepChain(depth: Int): Int < Ask =
        @tailrec def loop(i: Int, acc: Int < Ask): Int < Ask =
            if i == 0 then acc
            else loop(i - 1, if i % 2 == 0 then stepA(acc) else stepB(acc))
        loop(depth, innerStep(ask))
    end deepChain

    inline def askWith[B, S](inline f: Int => B < S): B < (Ask & S) = ArrowEffect.suspendWith[Any](Tag[Ask], ())(f)

    "a throw in a handler clause carries the suspension and its region" in {
        val boom = new RuntimeException("boom")
        val ex = intercept[RuntimeException] {
            eval(ArrowEffect.handleLoop(Tag[Ask], ask.map(_ + 1))([C] => _ => throw boom, a => a))
        }
        assert(ex eq boom)
        val t = carrier(ex)
        assert(t.nonEmpty)
        val msg = t.get.getMessage
        assert(msg.contains("EffectTraceTest.scala"))
        assert(msg.contains("ask"))
        assert(msg.contains("handle"))
    }

    "a throw in a continuation frame with no region standing travels on the physical trace" in {
        def deep(i: Int): Int < Any =
            if i == 0 then 0 else (0: Int < Any).map(_ => deep(i - 1))
        var site = 0
        def boomAt(v: Int < Any): Int < Any =
            v.map { _ =>
                site = summon[kyo.Frame].position.lineNumber; (throw new RuntimeException("late")): Int
            }
        val ex = intercept[RuntimeException](eval(boomAt(deep(10000))))
        assert(carrier(ex).forall(_.elements.isEmpty))
        val top = ex.getStackTrace.head
        assert(!kyo.internal.Platform.isJVM || (top.getFileName == "EffectTraceTest.scala" && top.getLineNumber == site))
    }

    "an unhandled suspension arrives enriched" in {
        val ex = intercept[Throwable](eval(ask.asInstanceOf[Int < Any]))
        assert(ex.getMessage.contains("unhandled suspension"))
        val t = carrier(ex)
        assert(t.nonEmpty)
        assert(t.get.getMessage.contains("ask"))
    }

    "the effect frames of a throw inside a mapped step" - {
        "are carried through an eval" in {
            val ex = intercept[Boom](eval(runAsk(outerStep(ask))(1)))
            assert(methods(ex).contains("innerStep"))
            assert(methods(ex).contains("outerStep"))
        }

        "name the call site's callee and the enclosing definition" in {
            val ex  = intercept[Boom](eval(runAsk(outerStep(ask))(1)))
            val els = carrier(ex).get.elements.toList
            val inner = els.find(_.getMethodName == "innerStep") match
                case Some(e) => e
                case None    => fail("no element for innerStep")
            assert(inner.getClassName == s"map @ ${classOf[EffectTraceTest].getName}")
            assert(inner.getFileName == "EffectTraceTest.scala")
            assert(inner.getLineNumber > 0)
        }

        "run innermost first" in {
            val ex = intercept[Boom](eval(runAsk(outerStep(ask))(1)))
            val ms = methods(ex)
            assert(ms.indexOf("innerStep") < ms.indexOf("outerStep"))
        }

        "skip the internal frame placeholder" in {
            val ex = intercept[Boom](eval(runAsk(outerStep(ask))(1)))
            assert(carrier(ex).get.elements.forall(_.getFileName != "<internal>"))
        }

        "a fused suspension carries the operation's own frame" in {
            def fusedStep: Int < Ask                 = askWith(_ => throw new Boom)
            def aroundFused(v: Int < Ask): Int < Ask = v.map(_ + 1)
            val ex                                   = intercept[Boom](eval(runAsk(aroundFused(fusedStep))(1)))
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
        val ex                               = intercept[Boom](eval(runAsk(around(ask))(1)))
        assert(methods(ex).contains("around"))
        assert(methods(ex).contains("thrower"))
    }

    "a throw after a budget rescue with no region standing travels on the physical trace" in {
        def boomHere: Int < Any = (0: Int < Any).map(_ => (throw new Boom): Int)
        def deep(i: Int): Int < Any =
            if i == 0 then boomHere else (0: Int < Any).map(_ => deep(i - 1))
        val ex = intercept[Boom](eval(deep(600)))
        assert(carrier(ex).forall(_.elements.isEmpty))
        assert(!kyo.internal.Platform.isJVM || ex.getStackTrace.exists(_.getMethodName.contains("boomHere")))
    }

    "region nesting" - {
        def useAsk: Int < (Ask & Say) = outerStep(ask).map(v => say("x").map(_ => v))

        "names each region exactly once" in {
            val ex = intercept[Boom](eval(runSay(runAsk(useAsk)(1))))
            assert(classes(ex).count(_.endsWith("Ask")) == 1)
            assert(classes(ex).count(_.endsWith("Say")) == 1)
        }

        "a throw under an emitting clause walks without looping" in {
            val v: Int < Any =
                runSay(
                    ArrowEffect.handleLoop(Tag[Ask], innerStep(ask))(
                        [C] => _ => say("e").map(_ => Loop.continue((), 1: Int < Any)),
                        a => a
                    )
                )
            val ex = intercept[Boom](eval(v))
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
            val ex = intercept[Boom](eval(r))
            assert(classes(ex).count(_.endsWith("Ask")) == 1)
        }
    }

    "a fatal error keeps its original stack trace" in {
        val fatal                    = new StackOverflowError("fatal")
        val before                   = fatal.getStackTrace
        def fatalStep: Int < Ask     = ask.map(_ => throw fatal)
        var caught: Throwable | Null = null
        try kyo.discard(eval(runAsk(fatalStep)(1)))
        catch case ex: Throwable => caught = ex
        assert(caught eq fatal)
        assert(fatal.getSuppressed.isEmpty)
        assert(fatal.getStackTrace.sameElements(before))
    }

    "the cap" - {
        "stops the walk at exactly the cap and records what it did not reach" in {
            val ex = intercept[Boom](eval(runAsk(deepChain(200))(1)))
            assert(carrier(ex).get.elements.length == 64)
            assert(carrier(ex).get.dropped > 0)
        }

        "bounds a chain far deeper than the Java stack" in {
            val ex = intercept[Boom](eval(runAsk(deepChain(1000000))(1)))
            assert(carrier(ex).get.elements.length == 64)
            assert(carrier(ex).get.dropped > 0)
        }
    }

    "a failure of the walk itself leaves the original failure travelling" in {
        val unreadable =
            new Kyo.SuspendArrow[Const[Unit], Const[Int], Ask, Any, Int, Any]:
                def tag            = Tag[Ask]
                def input          = ()
                override def frame = throw new IllegalStateException("frame read failed")
                def cont           = Arrow.id[Int]
        val ex = intercept[Throwable](eval(unreadable.asInstanceOf[Int < Any]))
        assert(carrier(ex).toList.flatMap(_.elements.toList).isEmpty)
    }

    "a second crossing rewrites the spliced trace rather than duplicating it" in {
        def rethrown: Int < Any = Effect.defer {
            val crossed: Int = eval(runAsk(outerStep(ask))(1))
            crossed
        }
        val ex = intercept[Boom](eval(rethrown))
        assert(ex.getStackTrace.count(_.getMethodName == "innerStep") == 1)
        assert(ex.getStackTrace.count(_.getMethodName == "outerStep") == 1)
        assert(ex.getSuppressed.count(_.isInstanceOf[EffectTrace]) == 1)
    }

end EffectTraceTest
