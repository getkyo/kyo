package kyo.kernel.internal

import kyo.Frame
import kyo.Maybe
import kyo.Tag
import kyo.kernel.*
import org.scalatest.freespec.AnyFreeSpec
import scala.annotation.tailrec

class EvalTest extends AnyFreeSpec:

    given Frame = Frame.internal

    type Const[A] = [B] =>> A

    sealed trait Ask  extends ArrowEffect[Const[Unit], Const[Int]]
    sealed trait Say  extends ArrowEffect[Const[String], Const[Unit]]
    sealed trait VarE extends ArrowEffect[Const[Int => Int], Const[Int]]

    def ask: Int < Ask                   = ArrowEffect.suspend[Any](Tag[Ask], ())
    def say(s: String): Unit < Say       = ArrowEffect.suspend[Any](Tag[Say], s)
    def varOp(f: Int => Int): Int < VarE = ArrowEffect.suspend[Any](Tag[VarE], f)

    def answerAsk[A, S](value: Int)(v: A < (Ask & S)): A < S =
        ArrowEffect.handleLoop(Tag[Ask], v)([X] => _ => Loop.continue(value))

    def recordSay[A, S](name: String, log: scala.collection.mutable.ListBuffer[String])(v: A < (Say & S)): A < S =
        ArrowEffect.handleLoop(Tag[Say], v)(
            [X] =>
                _ =>
                    log += name
                    Loop.continue(())
        )

    def runVar[A, S](init: Int)(v: A < (VarE & S)): A < S =
        ArrowEffect.handleLoop(Tag[VarE], init, v)(
            [X] =>
                (f, state) =>
                    val v2 = f(state)
                    Loop.continue(v2, v2)
        )

    "a scope answers through its handler" in {
        assert(answerAsk(41)(ask.map(_ + 1)).eval == 42)
    }

    "scope exits run innermost first" in {
        val log   = scala.collection.mutable.ListBuffer[String]()
        val inner = answerAsk(41)(ask.map(_ + 1)).map(_ * 10)
        val outer = recordSay("s", log)(inner).map(_ + 1000)
        assert(outer.eval == 1420)
    }

    "the innermost handler of a tag answers" in {
        val inner = answerAsk(1)(ask.map(_ + 1))
        assert(answerAsk(41)(inner.asInstanceOf[Int < Ask]).eval == 2)
    }

    "an effectful answer resolves through the outer scope" in {
        val log = scala.collection.mutable.ListBuffer[String]()
        val askScope =
            ArrowEffect.handleLoop(Tag[Ask], ask.map(_ + 1))([X] => _ => Loop.continue(say("c").map(_ => 41)))
        assert(recordSay("s", log)(askScope).eval == 42)
        assert(log.toList == List("s"))
    }

    "a clause runs outside its own scope" in {
        val log                        = scala.collection.mutable.ListBuffer[String]()
        val program: Int < (Ask & Say) = say("m").map(_ => ask).map(_ + 1)
        val sayInner                   = recordSay("inner", log)(program)
        val askScope =
            ArrowEffect.handleLoop(Tag[Ask], sayInner)([X] => _ => Loop.continue(say("c").map(_ => 41)))
        val sayOuter = recordSay("outer", log)(askScope)
        assert(sayOuter.eval == 42)
        assert(log.toList == List("inner", "outer"))
    }

    "drives deep recursion within a scope in bounded stack" in {
        def loop(n: Int): Int < Ask =
            if n == 0 then 0 else ask.map(_ => loop(n - 1))
        assert(answerAsk(1)(loop(100000)).eval == 0)
    }

    "done ends its scope at the operation" in {
        var reached = false
        val program: Int < Ask = ask.map { a =>
            reached = true
            a + 1
        }
        val r = ArrowEffect.handleLoop(Tag[Ask], program)([X] => _ => Loop.done(-1))
        assert(r.eval == -1)
        assert(!reached)
    }

    "a done climbs past an inner scope without running its remainder" in {
        val log                        = scala.collection.mutable.ListBuffer[String]()
        var innerExit                  = false
        val program: Int < (Ask & Say) = say("m").map(_ => ask).map(_ + 1)
        val mapped = recordSay("s", log)(program).map { v =>
            innerExit = true
            v
        }
        val r = ArrowEffect.handleLoop(Tag[Ask], mapped)([X] => _ => Loop.done(-1))
        assert(r.eval == -1)
        assert(!innerExit)
        assert(log.toList == List("s"))
    }

    "a continue answer raising the effect is answered by the successor, which may done" in {
        val r = ArrowEffect.handleLoop(Tag[Ask], 0, ask.map(_ + 1))(
            [X] =>
                (_, phase) =>
                    if phase == 0 then Loop.continue(1, ask.map(_ + 100))
                    else Loop.done(-2)
        )
        assert(r.eval == -2)
    }

    "state threads through updates" in {
        val program = varOp(_ => 10).map(_ => varOp(_ + 5)).map(a => varOp(identity).map(b => a + b))
        assert(runVar(0)(program).eval == 30)
    }

    "state updates survive an inner scope's exit" in {
        val log                       = scala.collection.mutable.ListBuffer[String]()
        val inner: Int < (VarE & Say) = varOp(_ => 7).map(_ => say("x")).map(_ => 1)
        val innerScope                = recordSay("s", log)(inner)
        val program                   = innerScope.map(_ => varOp(identity))
        assert(runVar(0)(program).eval == 7)
        assert(log.toList == List("s"))
    }

    "a stateful handler composes state and done" in {
        def go(n: Int): Int < Ask =
            if n == 0 then 0 else ask.map(_ => go(n - 1))
        val r = ArrowEffect.handleLoop(Tag[Ask], 3, go(5))(
            [X] =>
                (_, remaining) =>
                    if remaining > 0 then Loop.continue(remaining - 1, 1)
                    else Loop.done(-1)
        )
        assert(r.eval == -1)
    }

    "rejects a suspension no scope handles" in {
        val program: Int < (Ask & Say) = say("x").map(_ => ask)
        val r                          = answerAsk(41)(program)
        intercept[IllegalStateException](r.asInstanceOf[Int < Any].eval)
    }

    "a clause may suspend before producing its outcome" in {
        val log = scala.collection.mutable.ListBuffer[String]()
        val askScope =
            ArrowEffect.handleLoop(Tag[Ask], ask.map(_ + 1))([X] => _ => say("pre").map(_ => Loop.continue(41)))
        assert(recordSay("s", log)(askScope).eval == 42)
        assert(log.toList == List("s"))
    }

    "a clause may suspend before producing a done" in {
        var reached = false
        val log     = scala.collection.mutable.ListBuffer[String]()
        val program: Int < Ask = ask.map { a =>
            reached = true
            a + 1
        }
        val askScope =
            ArrowEffect.handleLoop(Tag[Ask], program)([X] => _ => say("pre").map(_ => Loop.done(-1)))
        assert(recordSay("s", log)(askScope).eval == -1)
        assert(!reached)
        assert(log.toList == List("s"))
    }

    "a done fired while a clause outcome settles climbs to its own scope" in {
        var reached = false
        val program: Int < Ask = ask.map { a =>
            reached = true
            a + 1
        }
        val askScope =
            ArrowEffect.handleLoop(Tag[Ask], program)([X] => _ => say("pre").map(_ => Loop.continue(41)))
        val r = ArrowEffect.handleLoop(Tag[Say], askScope)([X] => _ => Loop.done(-9))
        assert(r.eval == -9)
        assert(!reached)
    }

    "a done fired while a stateful clause outcome settles climbs to its own scope" in {
        var reached = false
        val program: Int < Ask = ask.map { a =>
            reached = true
            a + 1
        }
        val askScope = ArrowEffect.handleLoop(Tag[Ask], 0, program)(
            [X] => (_, n) => say("pre").map(_ => Loop.continue(n + 1, n))
        )
        val r = ArrowEffect.handleLoop(Tag[Say], askScope)([X] => _ => Loop.done(-9))
        assert(r.eval == -9)
        assert(!reached)
    }

    "a clause does not see handlers inside its own scope" in {
        val log                        = scala.collection.mutable.ListBuffer[String]()
        val program: Int < (Ask & Say) = say("m").map(_ => ask).map(_ + 1)
        val sayInner                   = recordSay("inner", log)(program)
        val askScope =
            ArrowEffect.handleLoop(Tag[Ask], sayInner)([X] => _ => Loop.continue(say("c").map(_ => 41)))
        intercept[IllegalStateException](askScope.asInstanceOf[Int < Any].eval)
        assert(log.toList == List("inner"))
    }

    "Eval.partial settles a deferred computation" in {
        def loop(n: Int): Int < Any =
            if n == 0 then 0
            else (0: Int < Any).map(_ => loop(n - 1))
        assert(Eval.partial(loop(100000)).evalNow == Maybe(0))
    }

    "a pending stop request parks the evaluation before it starts" in {
        def loop(n: Int): Int < Any =
            if n == 0 then 0
            else (0: Int < Any).map(_ => loop(n - 1))
        val v = loop(100000)
        assert(Safepoint.stop(Thread.currentThread()))
        val parked = Eval.partial(v)
        assert(parked.evalNow.isEmpty)
        assert(Eval.partial(parked).evalNow == Maybe(0))
    }

    "a stop request arriving mid-evaluation parks between defers" in {
        def burn(n: Int): Int < Any =
            if n == 0 then 0 else (0: Int < Any).map(_ => burn(n - 1))
        val v = burn(Safepoint.Period * 2).map { _ =>
            assert(Safepoint.stop(Thread.currentThread()))
            burn(Safepoint.Period * 4)
        }
        val out = Eval.partial(v)
        assert(out.evalNow.isEmpty)
        assert(out.eval == 0)
    }

    "Eval.partial evaluates scopes" in {
        val r = answerAsk(41)(ask.map(_ + 1))
        assert(Eval.partial(r).evalNow == Maybe(42))
    }

    "Eval.partial parks at an unhandled suspension with a resumable residual" in {
        val program: Int < (Ask & Say) = say("x").map(_ => ask).map(_ + 1)
        val r                          = answerAsk(41)(program)
        val residual                   = Eval.partial(r)
        assert(residual.evalNow.isEmpty)
        val finished = ArrowEffect.handle(Tag[Say], residual)([X] => (_, cont) => cont(()))
        assert(finished.eval == 42)
    }

    "a settled computation passes through a handler strictly" in {
        assert(answerAsk(41)(42: Int < Ask).evalNow == Maybe(42))
    }

    "a residual parked with a non-initial state resumes from it" in {
        // the counter reaches 57 before the foreign suspension parks the
        // evaluation; the residual must carry 57, not the initial 50
        val program: Int < (VarE & Say) =
            varOp(_ + 7).map(_ => say("park")).map(_ => varOp(_ + 1))
        val residual = Eval.partial(runVar(50)(program))
        assert(residual.evalNow.isEmpty)
        val finished = ArrowEffect.handle(Tag[Say], residual)([X] => (_, cont) => cont(()))
        assert(finished.eval == 58)
    }

    "each shot of a multi-shot continuation resumes from the capture-time state" in {
        // the Ask suspension crosses the counter region at state 2; both
        // shots must resume the counter from 2, so a leak from the first
        // shot into the second would surface as 8 instead of 5
        val program: Int < (VarE & Ask) =
            varOp(_ + 2).map(_ => ask).map(b => varOp(_ + 3).map(s => s * 10 + b))
        val v = ArrowEffect.handle(Tag[Ask], runVar(0)(program))(
            [X] => (_, cont) => cont(0).map(r1 => cont(1).map(r2 => r1 * 1000 + r2))
        )
        assert(v.eval == 50051)
    }

    "a clause that suspends threads its new state into the resumed region" in {
        val v: (Int, Int) < (Say & Any) =
            ArrowEffect.handleLoop(Tag[Ask], 0, ask.map(a => ask.map(b => (a, b))))(
                [X] => (_, state) => say("s").map(_ => Loop.continue(state + 1, state))
            )
        val r = ArrowEffect.handle(Tag[Say], v)([X] => (_, cont) => cont(()))
        assert(r.eval == (0, 1))
    }

    "a stateful handler answering under an unrelated inner region threads state" in {
        val inner: Int < VarE =
            ArrowEffect.handleLoop(Tag[Say], varOp(_ + 1).map(_ => say("x")).map(_ => varOp(_ + 1)))(
                [X] => _ => Loop.continue(())
            )
        assert(runVar(10)(inner).eval == 12)
    }

    "answers across a deep stack of unrelated scopes" in {
        val depth = 32
        val program: Int < (Ask & Say) =
            ask.map(a => ask.map(b => ask.map(c => say("done").map(_ => a + b + c))))
        def wrapSay(n: Int, v: Int < (Ask & Say)): Int < (Ask & Say) =
            if n == 0 then v
            else wrapSay(n - 1, ArrowEffect.handleLoop(Tag[Say], v)([X] => _ => Loop.continue(())))
        val handled: Int < Say = answerAsk(14)(wrapSay(depth, program))
        assert(ArrowEffect.handleLoop(Tag[Say], handled)([X] => _ => Loop.continue(())).eval == 42)
    }

    "enters deeply nested scopes in bounded stack" in {
        val depth = 1000000
        @tailrec def wrap(n: Int, v: Int < Ask): Int < Ask =
            if n == 0 then v
            else wrap(n - 1, answerAsk(1)(v))
        val nested = wrap(depth, ask.map(_ => 0))
        try assert(answerAsk(1)(nested).eval == 0)
        catch case e: StackOverflowError => fail(s"stack overflow entering $depth nested scopes")
    }

    "opens a scope per recursion step in bounded stack" in {
        val depth = 1000000
        def go(n: Int): Int < Any =
            if n == 0 then 0
            else answerAsk(1)(ask.map(_ => go(n - 1)))
        try assert(go(depth).eval == 0)
        catch case e: StackOverflowError => fail(s"stack overflow opening a scope per step at depth $depth")
    }

    "settles chained re-raised answers in bounded stack" in {
        val depth = 1000000
        val r = ArrowEffect.handleLoop(Tag[Ask], depth, ask)(
            [X] =>
                (_, n) =>
                    if n == 0 then Loop.continue(0, 0)
                    else Loop.continue(n - 1, ask.map(_ + 1))
        )
        try assert(r.eval == depth)
        catch case e: StackOverflowError => fail(s"stack overflow settling $depth chained re-raised answers")
    }

end EvalTest
