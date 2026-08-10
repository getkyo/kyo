package kyo.kernel

import kyo.Frame
import kyo.Tag
import org.scalatest.freespec.AnyFreeSpec

class EvalTest extends AnyFreeSpec:

    given Frame = Frame.internal

    type Const[A] = [B] =>> A

    sealed trait Ask extends ArrowEffect[Const[Unit], Const[Int]]
    sealed trait Say extends ArrowEffect[Const[String], Const[Unit]]

    def ask: Int < Ask             = ArrowEffect.suspend[Const[Unit], Const[Int], Ask, Any](Tag[Ask], ())
    def say(s: String): Unit < Say = ArrowEffect.suspend[Const[String], Const[Unit], Say, Any](Tag[Say], s)

    def resumeAsk(value: Int): Handler.Resume[Const[Unit], Const[Int], Ask, Any] =
        new Handler.Resume[Const[Unit], Const[Int], Ask, Any](Tag[Ask]):
            def apply[X](input: Unit): Int < Any = value

    def resumeSay(name: String, log: scala.collection.mutable.ListBuffer[String]): Handler.Resume[Const[String], Const[Unit], Say, Any] =
        new Handler.Resume[Const[String], Const[Unit], Say, Any](Tag[Say]):
            def apply[X](input: String): Unit < Any =
                log += name
                ()

    "a region answers through its handler" in {
        val r = new Kyo.Handled(ask.map(_ + 1), resumeAsk(41), Arrow[Int])
        assert((r: Int < Any).eval == 42)
    }

    "region exits run innermost first" in {
        val log   = scala.collection.mutable.ListBuffer[String]()
        val inner = (new Kyo.Handled(ask.map(_ + 1), resumeAsk(41), Arrow[Int]): Int < Any).map(_ * 10)
        val outer = (new Kyo.Handled(inner, resumeSay("s", log), Arrow[Int]): Int < Any).map(_ + 1000)
        assert(outer.eval == 1420)
    }

    "the innermost handler of a tag answers" in {
        val inner = new Kyo.Handled(ask.map(_ + 1), resumeAsk(1), Arrow[Int])
        val outer = new Kyo.Handled(inner, resumeAsk(41), Arrow[Int])
        assert((outer: Int < Any).eval == 2)
    }

    "an effectful answer resolves through the region's own collection" in {
        val log = scala.collection.mutable.ListBuffer[String]()
        val askClauseSays =
            new Handler.Resume[Const[Unit], Const[Int], Ask, Say](Tag[Ask]):
                def apply[X](input: Unit): Int < Say = say("c").map(_ => 41)
        val askRegion = new Kyo.Handled(ask.map(_ + 1), askClauseSays, Arrow[Int])
        val sayRegion = new Kyo.Handled(askRegion, resumeSay("s", log), Arrow[Int])
        assert((sayRegion: Int < Any).eval == 42)
        assert(log.toList == List("s"))
    }

    "a clause runs outside its own region" in {
        val log = scala.collection.mutable.ListBuffer[String]()
        val askClauseSays =
            new Handler.Resume[Const[Unit], Const[Int], Ask, Say](Tag[Ask]):
                def apply[X](input: Unit): Int < Say = say("c").map(_ => 41)
        val program: Int < (Ask & Say) = say("m").map(_ => ask).map(_ + 1)
        val sayInner =
            new Kyo.Handled[Const[String], Const[Unit], Say, Int, Int, Ask](program, resumeSay("inner", log), Arrow[Int])
        val askRegion = new Kyo.Handled(sayInner, askClauseSays, Arrow[Int])
        val sayOuter  = new Kyo.Handled(askRegion, resumeSay("outer", log), Arrow[Int])
        assert((sayOuter: Int < Any).eval == 42)
        assert(log.toList == List("inner", "outer"))
    }

    "drives deep recursion within a region in bounded stack" in {
        def loop(n: Int): Int < Ask =
            if n == 0 then 0 else ask.map(_ => loop(n - 1))
        val r = new Kyo.Handled(loop(100000), resumeAsk(1), Arrow[Int])
        assert((r: Int < Any).eval == 0)
    }

    "a stop handler ends its region at the operation" in {
        var reached = false
        val stopAsk =
            new Handler.Stop[Const[Unit], Const[Int], Ask, Int, Any](Tag[Ask]):
                def apply[X](input: Unit): Int < (Ask & Any) = -1
        val program: Int < Ask = ask.map { a =>
            reached = true
            a + 1
        }
        val r = new Kyo.Handled(program, stopAsk, Arrow[Int])
        assert((r: Int < Any).eval == -1)
        assert(!reached)
    }

    "a stop clause raising the region's effect stops again" in {
        var calls = 0
        val stopAsk =
            new Handler.Stop[Const[Unit], Const[Int], Ask, Int, Any](Tag[Ask]):
                def apply[X](input: Unit): Int < (Ask & Any) =
                    calls += 1
                    if calls == 1 then ask.map(_ => -1) else -2
        val r = new Kyo.Handled(ask.map(_ + 1), stopAsk, Arrow[Int])
        assert((r: Int < Any).eval == -2)
        assert(calls == 2)
    }

    "a halt climbs past an inner region without running its remainder" in {
        val log       = scala.collection.mutable.ListBuffer[String]()
        var innerExit = false
        val stopAsk =
            new Handler.Stop[Const[Unit], Const[Int], Ask, Int, Any](Tag[Ask]):
                def apply[X](input: Unit): Int < (Ask & Any) = -1
        val program: Int < (Ask & Say) = say("m").map(_ => ask).map(_ + 1)
        val sayInner =
            new Kyo.Handled[Const[String], Const[Unit], Say, Int, Int, Ask](program, resumeSay("s", log), Arrow[Int])
        val mapped = (sayInner: Int < Ask).map { v =>
            innerExit = true
            v
        }
        val r = new Kyo.Handled(mapped, stopAsk, Arrow[Int])
        assert((r: Int < Any).eval == -1)
        assert(!innerExit)
        assert(log.toList == List("s"))
    }

    "rejects a suspension no region handles" in {
        val program: Int < (Ask & Say) = say("x").map(_ => ask)
        val r =
            new Kyo.Handled[Const[Unit], Const[Int], Ask, Int, Int, Say](program, resumeAsk(41), Arrow[Int])
        intercept[IllegalStateException]((r: Int < Say).asInstanceOf[Int < Any].eval)
    }

end EvalTest
