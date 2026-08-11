package kyo.kernel

import kyo.Frame
import kyo.Tag
import org.scalatest.freespec.AnyFreeSpec

class EvalTest extends AnyFreeSpec:

    given Frame = Frame.internal

    type Const[A] = [B] =>> A

    sealed trait Ask  extends ArrowEffect[Const[Unit], Const[Int]]
    sealed trait Say  extends ArrowEffect[Const[String], Const[Unit]]
    sealed trait VarE extends ArrowEffect[Const[Int => Int], Const[Int]]

    def ask: Int < Ask                   = ArrowEffect.suspend[Const[Unit], Const[Int], Ask, Any](Tag[Ask], ())
    def say(s: String): Unit < Say       = ArrowEffect.suspend[Const[String], Const[Unit], Say, Any](Tag[Say], s)
    def varOp(f: Int => Int): Int < VarE = ArrowEffect.suspend[Const[Int => Int], Const[Int], VarE, Any](Tag[VarE], f)

    def loopAsk(value: Int): Handler.Loop[Const[Unit], Const[Int], Ask, Nothing, Any] =
        new Handler.Loop[Const[Unit], Const[Int], Ask, Nothing, Any](Tag[Ask]):
            def apply[X](input: Unit) = Loop.continue(value)

    def loopSay(
        name: String,
        log: scala.collection.mutable.ListBuffer[String]
    ): Handler.Loop[Const[String], Const[Unit], Say, Nothing, Any] =
        new Handler.Loop[Const[String], Const[Unit], Say, Nothing, Any](Tag[Say]):
            def apply[X](input: String) =
                log += name
                Loop.continue(())

    final class VarHandler(value: Int) extends Handler.LoopState[Const[Int => Int], Const[Int], VarE, Nothing, Any](Tag[VarE]):
        def apply[X](f: Int => Int) =
            val v2 = f(value)
            Loop.continue(if v2 == value then this else new VarHandler(v2), v2)
    end VarHandler

    "a scope answers through its handler" in {
        val r = new Kyo.Handled(ask.map(_ + 1), loopAsk(41), Arrow[Int])
        assert((r: Int < Any).eval == 42)
    }

    "scope exits run innermost first" in {
        val log   = scala.collection.mutable.ListBuffer[String]()
        val inner = (new Kyo.Handled(ask.map(_ + 1), loopAsk(41), Arrow[Int]): Int < Any).map(_ * 10)
        val outer = (new Kyo.Handled(inner, loopSay("s", log), Arrow[Int]): Int < Any).map(_ + 1000)
        assert(outer.eval == 1420)
    }

    "the innermost handler of a tag answers" in {
        val inner = new Kyo.Handled(ask.map(_ + 1), loopAsk(1), Arrow[Int])
        val outer = new Kyo.Handled(inner, loopAsk(41), Arrow[Int])
        assert((outer: Int < Any).eval == 2)
    }

    "an effectful answer resolves through the scope's own collection" in {
        val log = scala.collection.mutable.ListBuffer[String]()
        val askClauseSays =
            new Handler.Loop[Const[Unit], Const[Int], Ask, Nothing, Say](Tag[Ask]):
                def apply[X](input: Unit) = Loop.continue(say("c").map(_ => 41))
        val askScope = new Kyo.Handled(ask.map(_ + 1), askClauseSays, Arrow[Int])
        val sayScope = new Kyo.Handled(askScope, loopSay("s", log), Arrow[Int])
        assert((sayScope: Int < Any).eval == 42)
        assert(log.toList == List("s"))
    }

    "a clause runs outside its own scope" in {
        val log = scala.collection.mutable.ListBuffer[String]()
        val askClauseSays =
            new Handler.Loop[Const[Unit], Const[Int], Ask, Nothing, Say](Tag[Ask]):
                def apply[X](input: Unit) = Loop.continue(say("c").map(_ => 41))
        val program: Int < (Ask & Say) = say("m").map(_ => ask).map(_ + 1)
        val sayInner =
            new Kyo.Handled[Const[String], Const[Unit], Say, Int, Int, Ask](program, loopSay("inner", log), Arrow[Int])
        val askScope = new Kyo.Handled(sayInner, askClauseSays, Arrow[Int])
        val sayOuter = new Kyo.Handled(askScope, loopSay("outer", log), Arrow[Int])
        assert((sayOuter: Int < Any).eval == 42)
        assert(log.toList == List("inner", "outer"))
    }

    "drives deep recursion within a scope in bounded stack" in {
        def loop(n: Int): Int < Ask =
            if n == 0 then 0 else ask.map(_ => loop(n - 1))
        val r = new Kyo.Handled(loop(100000), loopAsk(1), Arrow[Int])
        assert((r: Int < Any).eval == 0)
    }

    "done ends its scope at the operation" in {
        var reached = false
        val failAsk =
            new Handler.Loop[Const[Unit], Const[Int], Ask, Int, Any](Tag[Ask]):
                def apply[X](input: Unit) = Loop.done(-1)
        val program: Int < Ask = ask.map { a =>
            reached = true
            a + 1
        }
        val r = new Kyo.Handled(program, failAsk, Arrow[Int])
        assert((r: Int < Any).eval == -1)
        assert(!reached)
    }

    "a done climbs past an inner scope without running its remainder" in {
        val log       = scala.collection.mutable.ListBuffer[String]()
        var innerExit = false
        val failAsk =
            new Handler.Loop[Const[Unit], Const[Int], Ask, Int, Any](Tag[Ask]):
                def apply[X](input: Unit) = Loop.done(-1)
        val program: Int < (Ask & Say) = say("m").map(_ => ask).map(_ + 1)
        val sayInner =
            new Kyo.Handled[Const[String], Const[Unit], Say, Int, Int, Ask](program, loopSay("s", log), Arrow[Int])
        val mapped = (sayInner: Int < Ask).map { v =>
            innerExit = true
            v
        }
        val r = new Kyo.Handled(mapped, failAsk, Arrow[Int])
        assert((r: Int < Any).eval == -1)
        assert(!innerExit)
        assert(log.toList == List("s"))
    }

    "a continue answer raising the effect is answered by the successor, which may done" in {
        final class TwoPhase(phase: Int) extends Handler.LoopState[Const[Unit], Const[Int], Ask, Int, Any](Tag[Ask]):
            def apply[X](input: Unit) =
                if phase == 0 then Loop.continue(new TwoPhase(1), ask.map(_ + 100))
                else Loop.done(-2)
        end TwoPhase
        val r = new Kyo.Handled(ask.map(_ + 1), new TwoPhase(0), Arrow[Int])
        assert((r: Int < Any).eval == -2)
    }

    "state threads through updates" in {
        val program = varOp(_ => 10).map(_ => varOp(_ + 5)).map(a => varOp(identity).map(b => a + b))
        val r       = new Kyo.Handled(program, new VarHandler(0), Arrow[Int])
        assert((r: Int < Any).eval == 30)
    }

    "state updates survive an inner scope's exit" in {
        val log                       = scala.collection.mutable.ListBuffer[String]()
        val inner: Int < (VarE & Say) = varOp(_ => 7).map(_ => say("x")).map(_ => 1)
        val innerScope =
            new Kyo.Handled[Const[String], Const[Unit], Say, Int, Int, VarE](inner, loopSay("s", log), Arrow[Int])
        val program = (innerScope: Int < VarE).map(_ => varOp(identity))
        val r       = new Kyo.Handled(program, new VarHandler(0), Arrow[Int])
        assert((r: Int < Any).eval == 7)
        assert(log.toList == List("x").map(_ => "s"))
    }

    "a stateful handler composes state and done" in {
        final class Budget(remaining: Int) extends Handler.LoopState[Const[Unit], Const[Int], Ask, Int, Any](Tag[Ask]):
            def apply[X](input: Unit) =
                if remaining > 0 then Loop.continue(new Budget(remaining - 1), 1)
                else Loop.done(-1)
        end Budget
        def go(n: Int): Int < Ask =
            if n == 0 then 0 else ask.map(_ => go(n - 1))
        val r = new Kyo.Handled(go(5), new Budget(3), Arrow[Int])
        assert((r: Int < Any).eval == -1)
    }

    "rejects a suspension no scope handles" in {
        val program: Int < (Ask & Say) = say("x").map(_ => ask)
        val r =
            new Kyo.Handled[Const[Unit], Const[Int], Ask, Int, Int, Say](program, loopAsk(41), Arrow[Int])
        intercept[IllegalStateException]((r: Int < Say).asInstanceOf[Int < Any].eval)
    }

    "a clause may suspend before producing its outcome" in {
        val log = scala.collection.mutable.ListBuffer[String]()
        val askClause =
            new Handler.Loop[Const[Unit], Const[Int], Ask, Nothing, Say](Tag[Ask]):
                def apply[X](input: Unit) = say("pre").map(_ => Loop.continue(41))
        val askScope = new Kyo.Handled(ask.map(_ + 1), askClause, Arrow[Int])
        val sayScope = new Kyo.Handled(askScope, loopSay("s", log), Arrow[Int])
        assert((sayScope: Int < Any).eval == 42)
        assert(log.toList == List("s"))
    }

    "a clause may suspend before producing a done" in {
        var reached = false
        val log     = scala.collection.mutable.ListBuffer[String]()
        val askClause =
            new Handler.Loop[Const[Unit], Const[Int], Ask, Int, Say](Tag[Ask]):
                def apply[X](input: Unit) = say("pre").map(_ => Loop.done(-1))
        val program: Int < Ask = ask.map { a =>
            reached = true
            a + 1
        }
        val askScope = new Kyo.Handled(program, askClause, Arrow[Int])
        val sayScope = new Kyo.Handled(askScope, loopSay("s", log), Arrow[Int])
        assert((sayScope: Int < Any).eval == -1)
        assert(!reached)
        assert(log.toList == List("s"))
    }

    "a done fired while a clause outcome settles climbs to its own scope" in {
        var reached = false
        val failSay =
            new Handler.Loop[Const[String], Const[Unit], Say, Int, Any](Tag[Say]):
                def apply[X](input: String) = Loop.done(-9)
        val askClause =
            new Handler.Loop[Const[Unit], Const[Int], Ask, Nothing, Say](Tag[Ask]):
                def apply[X](input: Unit) = say("pre").map(_ => Loop.continue(41))
        val program: Int < Ask = ask.map { a =>
            reached = true
            a + 1
        }
        val askScope = new Kyo.Handled(program, askClause, Arrow[Int])
        val r        = new Kyo.Handled(askScope, failSay, Arrow[Int])
        assert((r: Int < Any).eval == -9)
        assert(!reached)
    }

    "a stateful clause may suspend before producing its outcome" in {
        val log = scala.collection.mutable.ListBuffer[String]()
        final class Counter(n: Int) extends Handler.LoopState[Const[Unit], Const[Int], Ask, Nothing, Say](Tag[Ask]):
            def apply[X](input: Unit) = say("pre").map(_ => Loop.continue(new Counter(n + 1), n))
        val program: Int < Ask = ask.map(a => ask.map(b => a * 10 + b))
        val askScope           = new Kyo.Handled(program, new Counter(1), Arrow[Int])
        val sayScope           = new Kyo.Handled(askScope, loopSay("s", log), Arrow[Int])
        assert((sayScope: Int < Any).eval == 12)
        assert(log.toList == List("s", "s"))
    }

    "a done fired while a stateful clause outcome settles climbs to its own scope" in {
        var reached = false
        val failSay =
            new Handler.Loop[Const[String], Const[Unit], Say, Int, Any](Tag[Say]):
                def apply[X](input: String) = Loop.done(-9)
        final class Pre(n: Int) extends Handler.LoopState[Const[Unit], Const[Int], Ask, Nothing, Say](Tag[Ask]):
            def apply[X](input: Unit) = say("pre").map(_ => Loop.continue(new Pre(n + 1), n))
        val program: Int < Ask = ask.map { a =>
            reached = true
            a + 1
        }
        val askScope = new Kyo.Handled(program, new Pre(0), Arrow[Int])
        val r        = new Kyo.Handled(askScope, failSay, Arrow[Int])
        assert((r: Int < Any).eval == -9)
        assert(!reached)
    }

    "a clause does not see handlers inside its own scope" in {
        val log = scala.collection.mutable.ListBuffer[String]()
        val askClauseSays =
            new Handler.Loop[Const[Unit], Const[Int], Ask, Nothing, Say](Tag[Ask]):
                def apply[X](input: Unit) = Loop.continue(say("c").map(_ => 41))
        val program: Int < (Ask & Say) = say("m").map(_ => ask).map(_ + 1)
        val sayInner =
            new Kyo.Handled[Const[String], Const[Unit], Say, Int, Int, Ask](program, loopSay("inner", log), Arrow[Int])
        val askScope = new Kyo.Handled(sayInner, askClauseSays, Arrow[Int])
        intercept[IllegalStateException]((askScope: Int < Any).eval)
        assert(log.toList == List("inner"))
    }

    "evalPartial settles a deferred computation" in {
        def loop(n: Int): Int < Any =
            if n == 0 then 0
            else (0: Int < Any).map(_ => loop(n - 1))
        assert(loop(100000).evalPartial(() => false).asInstanceOf[Int] == 0)
    }

    "evalPartial with an immediate stop returns the computation unchanged" in {
        def loop(n: Int): Int < Any =
            if n == 0 then 0
            else (0: Int < Any).map(_ => loop(n - 1))
        val v = loop(100000)
        assert(v.evalPartial(() => true).asInstanceOf[AnyRef] eq v.asInstanceOf[AnyRef])
    }

    "evalPartial stops between defers leaving the rest evaluable" in {
        def loop(n: Int): Int < Any =
            if n == 0 then 0
            else (0: Int < Any).map(_ => loop(n - 1))
        var checks = 0
        val out = loop(100000).evalPartial { () =>
            checks += 1
            checks > 3
        }
        assert(out.asInstanceOf[Any].isInstanceOf[Kyo.Defer[?, ?, ?]])
        assert(out.eval == 0)
    }

    "evalPartial does not evaluate scopes" in {
        val r = new Kyo.Handled(ask.map(_ + 1), loopAsk(41), Arrow[Int])
        assert((r: Int < Any).evalPartial(() => false).asInstanceOf[AnyRef] eq r)
    }

    "enters deeply nested scopes in bounded stack" in {
        val depth = 1000000
        val nested = (1 to depth).foldLeft(0: Int < Any) { (acc, _) =>
            new Kyo.Handled(acc, loopAsk(1), Arrow[Int])
        }
        try assert(nested.eval == 0)
        catch case e: StackOverflowError => fail(s"stack overflow entering $depth nested scopes")
    }

    "opens a scope per recursion step in bounded stack" in {
        val depth = 1000000
        def go(n: Int): Int < Any =
            if n == 0 then 0
            else new Kyo.Handled(ask.map(_ => go(n - 1)), loopAsk(1), Arrow[Int])
        try assert(go(depth).eval == 0)
        catch case e: StackOverflowError => fail(s"stack overflow opening a scope per step at depth $depth")
    }

    "settles chained re-raised answers in bounded stack" in {
        val depth = 1000000
        final class Chain(n: Int) extends Handler.LoopState[Const[Unit], Const[Int], Ask, Nothing, Any](Tag[Ask]):
            def apply[X](input: Unit) =
                if n == 0 then Loop.continue(this, 0)
                else Loop.continue(new Chain(n - 1), ask.map(_ + 1))
        end Chain
        val r = new Kyo.Handled(ask, new Chain(depth), Arrow[Int])
        try assert((r: Int < Any).eval == depth)
        catch case e: StackOverflowError => fail(s"stack overflow settling $depth chained re-raised answers")
    }

end EvalTest
