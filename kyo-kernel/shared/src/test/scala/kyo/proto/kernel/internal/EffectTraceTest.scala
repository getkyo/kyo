package kyo.proto.kernel.internal

import kyo.Const
import kyo.Maybe
import kyo.Tag
import kyo.proto.kernel.<
import kyo.proto.kernel.ArrowEffect
import org.scalatest.freespec.AnyFreeSpec
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

    "nested evals accumulate their regions" in {
        val inner = runAsk(ask.map(_ => (throw Boom()): Int))(1)

        val outer: Int < Any = runSay(say("x").map { _ =>
            val r: Int = eval(inner)
            r
        })
        val ex    = intercept[Boom](eval(outer))
        val trace = ex.getStackTrace
        assert(trace.exists(_.getClassName == Tag[Ask].show))
        assert(trace.exists(_.getClassName == Tag[Say].show))

        assert(ex.getSuppressed.count(_.isInstanceOf[EffectTrace]) == 1)
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

    "a throw with no region standing synthesizes nothing and travels on the physical trace" in {

        val v: Int < Any = runAsk(ask)(1).map(_ => (throw Boom()): Int).map(_ + 1)
        val ex           = intercept[Boom](eval(v))
        assert(carrier(ex).isEmpty || carrier(ex).get.elements.isEmpty)

        assert(!kyo.internal.Platform.isJVM || ex.getStackTrace.exists(_.getFileName == "EffectTraceTest.scala"))
    }

    "the carrier renders the frames as a message" in {
        val v  = runAsk(ask.map(_ => (throw Boom()): Int))(1)
        val ex = intercept[Boom](eval(v))
        assert(carrier(ex).get.getMessage.startsWith("effect trace:"))
    }
end EffectTraceTest
