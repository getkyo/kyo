package kyo.kernel.proto

import kyo.Const
import kyo.Tag
import org.scalatest.freespec.AnyFreeSpec
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

    def carrier(ex: Throwable): Option[EffectTrace] =
        ex.getSuppressed.collectFirst { case t: EffectTrace => t }

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

end EffectTraceTest
