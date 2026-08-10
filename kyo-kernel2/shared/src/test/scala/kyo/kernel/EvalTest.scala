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
        val r = Kyo.Handled(resumeAsk(41))(ask.map(_ + 1), Arrow[Int])
        assert((r: Int < Any).eval == 42)
    }

    "region exits run innermost first" in {
        val log   = scala.collection.mutable.ListBuffer[String]()
        val inner = (Kyo.Handled(resumeAsk(41))(ask.map(_ + 1), Arrow[Int]): Int < Any).map(_ * 10)
        val outer = (Kyo.Handled(resumeSay("s", log))(inner, Arrow[Int]): Int < Any).map(_ + 1000)
        assert(outer.eval == 1420)
    }

    "the innermost handler of a tag answers" in {
        val inner = Kyo.Handled(resumeAsk(1))(ask.map(_ + 1), Arrow[Int])
        val outer = Kyo.Handled(resumeAsk(41))(inner, Arrow[Int])
        assert((outer: Int < Any).eval == 2)
    }

    "an effectful answer resolves through the region's own collection" in {
        val log = scala.collection.mutable.ListBuffer[String]()
        val askClauseSays =
            new Handler.Resume[Const[Unit], Const[Int], Ask, Say](Tag[Ask]):
                def apply[X](input: Unit): Int < Say = say("c").map(_ => 41)
        val program: Int < (Ask & Say) = ask.map(_ + 1)
        val askRegion                  = Kyo.Handled(askClauseSays)(program, Arrow[Int])
        val sayRegion                  = Kyo.Handled(resumeSay("s", log))(askRegion, Arrow[Int])
        assert((sayRegion: Int < Any).eval == 42)
        assert(log.toList == List("s"))
    }

    "a clause runs outside its own region" in {
        val log = scala.collection.mutable.ListBuffer[String]()
        val askClauseSays =
            new Handler.Resume[Const[Unit], Const[Int], Ask, Say](Tag[Ask]):
                def apply[X](input: Unit): Int < Say = say("c").map(_ => 41)
        val program: Int < (Ask & Say) = say("m").map(_ => ask).map(_ + 1)
        val sayInner                   = Kyo.Handled(resumeSay("inner", log))(program, Arrow[Int])
        val askRegion                  = Kyo.Handled(askClauseSays)(sayInner, Arrow[Int])
        val sayOuter                   = Kyo.Handled(resumeSay("outer", log))(askRegion, Arrow[Int])
        assert((sayOuter: Int < Any).eval == 42)
        assert(log.toList == List("inner", "outer"))
    }

    "drives deep recursion within a region in bounded stack" in {
        def loop(n: Int): Int < Ask =
            if n == 0 then 0 else ask.map(_ => loop(n - 1))
        val r = Kyo.Handled(resumeAsk(1))(loop(100000), Arrow[Int])
        assert((r: Int < Any).eval == 0)
    }

    "rejects a suspension no region handles" in {
        val program: Int < (Ask & Say) = say("x").map(_ => ask)
        val r                          = Kyo.Handled(resumeAsk(41))(program, Arrow[Int])
        intercept[IllegalStateException]((r: Int < Say).asInstanceOf[Int < Any].eval)
    }

end EvalTest
