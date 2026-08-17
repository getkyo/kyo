package kyo.kernel.internal

import kyo.Arrow
import kyo.Arrow.Bind
import kyo.Arrow.Identity
import kyo.Arrow.Transform
import kyo.Const
import kyo.Maybe
import kyo.Tag
import kyo.discard
import kyo.kernel.*
import org.scalatest.freespec.AnyFreeSpec
import scala.annotation.tailrec
import scala.collection.mutable.ListBuffer

class EvalTest extends AnyFreeSpec:

    sealed trait Ask extends ArrowEffect[Const[Unit], Const[Int]]
    def ask: Int < Ask = ArrowEffect.suspend[Any](Tag[Ask], ())

    def answerAsk[A](value: Int)(v: A < Ask): A < Any =
        ArrowEffect.handleLoop(Tag[Ask], v)([C] => _ => Loop.continue(value), a => a)

    sealed trait Say  extends ArrowEffect[Const[String], Const[Unit]]
    sealed trait VarE extends ArrowEffect[Const[Int => Int], Const[Int]]

    def say(s: String): Unit < Say       = ArrowEffect.suspend[Any](Tag[Say], s)
    def varOp(f: Int => Int): Int < VarE = ArrowEffect.suspend[Any](Tag[VarE], f)

    // row-generic, so a region can be handled while other effects stay open
    def answerAskIn[A, S](value: Int)(v: A < (Ask & S)): A < S =
        ArrowEffect.handleLoop(Tag[Ask], v)([C] => _ => Loop.continue(value), a => a)

    def recordSay[A, S](name: String, log: ListBuffer[String])(v: A < (Say & S)): A < S =
        ArrowEffect.handleLoop(Tag[Say], v)(
            [C] =>
                _ =>
                    log += name
                    Loop.continue(())
            ,
            a => a
        )

    def runVar[A, S](init: Int)(v: A < (VarE & S)): A < S =
        ArrowEffect.handleLoopState(Tag[VarE], init, v)(
            [C] =>
                (state, f) =>
                    val v2 = f(state)
                    Loop.continue(v2, v2)
            ,
            (_, a) => a
        )

    def box[A](v: A): A < Any = v

    private val Period = 512

    class EvalBoom extends RuntimeException("boom")

    "a settled value evaluates to itself" in {
        assert(Eval(42: Int < Any) == 42)
    }

    "a map chain runs strictly" in {
        assert(Eval((1: Int < Any).map(_ + 1).map(_ * 3)) == 6)
    }

    "a long map tower evaluates in bounded stack" in {
        @tailrec def tower(v: Int < Any, n: Int): Int < Any =
            if n == 0 then v else tower(v.map(_ + 1), n - 1)
        assert(Eval(tower(0, 1000000)) == 1000000)
    }

    "a deep map tower over a suspension evaluates in bounded stack" in {
        @tailrec def tower(v: Int < Ask, n: Int): Int < Ask =
            if n == 0 then v else tower(v.map(_ + 1), n - 1)
        assert(Eval(answerAsk(1)(tower(ask, 100000))) == 100001)
    }

    "a continuation folded from the drive stack runs every pending map exactly once" in {
        val runs = new Array[Int](2)
        val body: Int < Ask =
            ask.map(a => ask.map(b => a * 10 + b))
                .map { v =>
                    runs(0) += 1; v + 1
                }
                .map { v =>
                    runs(1) += 1; v * 2
                }
        assert(Eval(answerAsk(3)(body)) == 68)
        assert(runs.toList == List(1, 1))
    }

    "deep recursion through map pays rescues only" in {
        def loop(i: Int): Int < Any =
            if i == 0 then 0 else (0: Int < Any).map(_ => loop(i - 1))
        assert(Eval(loop(1000000)) == 0)
    }

    "a nested eval shares the stack safely" in {
        val inner = answerAsk(5)(ask)
        val outer = answerAsk(1)(ask.map(a => a + Eval(inner)))
        assert(Eval(outer) == 6)
    }

    "an unhandled suspension reports a bug" in {
        val ex = intercept[Throwable](Eval(ask.asInstanceOf[Int < Any]))
        assert(ex.getMessage.contains("unhandled suspension"))
    }

    "a drive cleans its stack after a throw" in {
        def boom: Int < Any = (0: Int < Any).map(_ => throw new RuntimeException("boom"))
        intercept[RuntimeException](Eval(answerAsk(1)(ask.map(_ => boom))))
        assert(Eval(answerAsk(41)(ask.map(_ + 1))) == 42)
    }

    "a deferred continuation replays each trailing map exactly once" in {
        val runs = new Array[Int](64)
        val defer: Int < Any =
            new Transform[Any, Int, Any]:
                def frame = kyo.Frame.internal
                def apply[C, S2](v: Any < S2, next: Arrow[Int, C, S2]): C < S2 =
                    Bind(7, next)
        var r: Int < Any = defer
        for i <- 0 until 64 do
            val j = i
            r = r.map { x =>
                runs(j) += 1
                x + 1
            }
        end for
        assert(Eval(r) == 71)
        assert(runs.forall(_ == 1))
    }

    "a continuation applied twice replays trailing maps twice at any depth" in {
        for depth <- List(8, 64) do
            val runs = new Array[Int](depth)
            val both: Int < Any =
                new Transform[Any, Int, Any]:
                    def frame = kyo.Frame.internal
                    def apply[C, S2](v: Any < S2, next: Arrow[Int, C, S2]): C < S2 =
                        Eval(Identity(3, next.asInstanceOf[Arrow[Any, C, Any]]).asInstanceOf[C < Any])
                        Bind(10, next)
            var r: Int < Any = both
            for i <- 0 until depth do
                val j = i
                r = r.map { x =>
                    runs(j) += 1
                    x + 1
                }
            end for
            assert(Eval(r) == 10 + depth)
            assert(runs.forall(_ == 2))
    }

    "a reified continuation stays valid after its drive completes" in {
        for depth <- List(8, 64) do
            val runs                        = new Array[Int](depth)
            var stash: Arrow[Any, Any, Any] = null
            val node: Int < Any =
                new Transform[Any, Int, Any]:
                    def frame = kyo.Frame.internal
                    def apply[C, S2](v: Any < S2, next: Arrow[Int, C, S2]): C < S2 =
                        stash = next.asInstanceOf[Arrow[Any, Any, Any]]
                        Bind(0, next)
            var r: Int < Any = node
            for i <- 0 until depth do
                val j = i
                r = r.map { x =>
                    runs(j) += 1
                    x + 1
                }
            end for
            assert(Eval(r) == depth)
            assert(runs.forall(_ == 1))
            assert(Eval(Identity(100, stash).asInstanceOf[Int < Any]) == 100 + depth)
            assert(runs.forall(_ == 2))
    }

    "a throw inside a region leaves no findable handler behind" in {
        def stateful[A](v: A < Ask): A < Any =
            ArrowEffect.handleLoopState(Tag[Ask], 0, v)(
                [C] => (st, _) => Loop.continue(st + 1, st),
                (_, a) => a
            )
        intercept[RuntimeException](Eval(stateful(ask.map(_ => (throw new RuntimeException("boom")): Int))))
        val ex = intercept[Throwable](Eval(ask.asInstanceOf[Int < Any]))
        assert(ex.getMessage.contains("unhandled suspension"))
        assert(Eval(stateful(ask.map(a => ask.map(b => a * 10 + b)))) == 1)
    }

    "partial evaluation" - {

        "a preemption stop reifies and resumes with handler state" in {
            def countdown(i: Int): Int < Ask =
                if i == 0 then 0 else ask.map(a => countdown(i - a))
            val counted: Int < Any = ArrowEffect.handleLoopState(Tag[Ask], 0, countdown(100))(
                [C] =>
                    (n, _) =>
                        if n == 10 then discard(Safepoint.stop(Thread.currentThread()))
                        Loop.continue(n + 1, 1)
                ,
                (n, a) => n + a
            )
            val first = Eval.partial(counted)
            assert(first.evalNow == Maybe.Absent)
            assert(Eval.partial(first).evalNow == Maybe(100))
        }

        "the stop function ends the slice" in {
            var steps = 0
            val stop = () =>
                steps += 1; steps > 50
            def countdown(i: Int): Int < Ask =
                if i == 0 then 0 else ask.map(a => countdown(i - a))
            val first = Eval.partial(answerAsk(1)(countdown(1000)), stop)
            assert(first.evalNow == Maybe.Absent)
            assert(Eval.partial(first).evalNow == Maybe(0))
        }

        "partial completes when nothing stops" in {
            assert(Eval.partial(answerAsk(21)(ask.map(_ * 2))).evalNow == Maybe(42))
        }

        "a stop delivered between slices short-circuits" in {
            val v: Int < Any = answerAsk(41)(ask.map(_ + 1))
            discard(Safepoint.stop(Thread.currentThread()))
            val r = Eval.partial(v)
            assert(r.asInstanceOf[AnyRef] eq v.asInstanceOf[AnyRef])
            assert(Eval.partial(r).evalNow == Maybe(42))
        }

        // A throw escaping a root eval skipped `Safepoint.exit`, leaking one unit of depth per throw
        // on a slot that is per thread, so every later unrelated computation on that thread paid for
        // it. The symptom is not a wrong answer: `enter` returning false is the ordinary
        // budget-exhausted path, so an affected thread simply parks and allocates more, forever, with
        // nothing to point at. `partial` already bracketed its boundary and `apply` did not, and that
        // asymmetry was the whole bug. Measured before the fix at exactly one of 512 lost per throw.
        "a throw escaping a root eval leaves the safepoint depth unchanged" in {
            val slot = Safepoint.get()
            // `save` resets as it reads, so reading it back is a save/restore pair
            def depth() =
                val d = Safepoint.save(slot)
                Safepoint.restore(slot, d)
                d
            end depth
            val before = depth()
            var caught = 0
            var i      = 0
            while i < 50 do
                try discard(Eval(answerAsk(1)(ask.map(v => if v > 0 then throw new RuntimeException("boom") else v))))
                catch case _: RuntimeException => caught += 1
                i += 1
            end while
            assert(caught == 50)
            // `equals` rather than `==`: `State` is opaque and carries no `CanEqual`, and a cast to
            // its underlying Int would be a new cast for a test's convenience
            assert(depth().equals(before))
        }
    }

    "drives andThen and unit" in {
        assert(Eval((1: Int < Any).andThen(2: Int < Any)) == 2)
        assert(Eval((1: Int < Any).unit) == ())
    }

    "evalNow is Present only for settled values" in {
        assert((1: Int < Any).evalNow.contains(1))
        assert(ask.evalNow.isEmpty)
    }

    "a scope answers through its handler" in {
        assert(Eval(answerAsk(41)(ask.map(_ + 1))) == 42)
    }

    "an effectful answer on the settled outcome path" in {
        val r = ArrowEffect.handleLoop(Tag[Ask], ask.map(_ + 1))([C] => _ => Loop.continue(Effect.defer(7)), a => a)
        assert(Eval(r) == 8)
    }

    "a pending clause outcome resolves before the region continues" in {
        def loop(i: Int): Int < Ask =
            if i < 3 then ask.map(a => loop(i + a)) else i
        val r = ArrowEffect.handleLoop(Tag[Ask], loop(0))([C] => _ => Effect.defer(Loop.continue(1)), a => a)
        assert(Eval(r) == 3)
    }

    "a nested computation stays data until flattened" in {
        val nested: (Int < Ask) < Any = box(ask.map(_ + 1))
        val r                         = ArrowEffect.handleLoop(Tag[Ask], nested.flatten)([C] => _ => Loop.continue(1), a => a)
        assert(Eval(r) == 2)
    }

    "scope exits run innermost first" in {
        val log   = ListBuffer[String]()
        val inner = answerAskIn(41)(ask.map(_ + 1)).map(_ * 10)
        val outer = recordSay("s", log)(inner).map(_ + 1000)
        assert(Eval(outer) == 1420)
    }

    "the innermost handler of a tag answers" in {
        val inner = answerAsk(1)(ask.map(_ + 1))
        assert(Eval(answerAsk(41)(inner.asInstanceOf[Int < Ask])) == 2)
    }

    "an effectful answer resolves through the outer scope" in {
        val log = ListBuffer[String]()
        val askScope =
            ArrowEffect.handleLoop(Tag[Ask], ask.map(_ + 1))([C] => _ => Loop.continue(say("c").map(_ => 41)), a => a)
        assert(Eval(recordSay("s", log)(askScope)) == 42)
        assert(log.toList == List("s"))
    }

    "a clause runs outside its own scope" in {
        val log                        = ListBuffer[String]()
        val program: Int < (Ask & Say) = say("m").map(_ => ask).map(_ + 1)
        val sayInner                   = recordSay("inner", log)(program)
        val askScope =
            ArrowEffect.handleLoop(Tag[Ask], sayInner)([C] => _ => Loop.continue(say("c").map(_ => 41)), a => a)
        val sayOuter = recordSay("outer", log)(askScope)
        assert(Eval(sayOuter) == 42)
        assert(log.toList == List("inner", "outer"))
    }

    "drives deep recursion within a scope in bounded stack" in {
        def loop(n: Int): Int < Ask =
            if n == 0 then 0 else ask.map(_ => loop(n - 1))
        assert(Eval(answerAsk(1)(loop(100000))) == 0)
    }

    "done ends its scope at the operation" in {
        var reached = false
        val program: Int < Ask = ask.map { a =>
            reached = true
            a + 1
        }
        val r = ArrowEffect.handleLoop(Tag[Ask], program)([C] => _ => Loop.done(-1), a => a)
        assert(Eval(r) == -1)
        assert(!reached)
    }

    "a done climbs past an inner scope without running its remainder" in {
        val log                        = ListBuffer[String]()
        var innerExit                  = false
        val program: Int < (Ask & Say) = say("m").map(_ => ask).map(_ + 1)
        val mapped = recordSay("s", log)(program).map { v =>
            innerExit = true
            v
        }
        val r = ArrowEffect.handleLoop(Tag[Ask], mapped)([C] => _ => Loop.done(-1), a => a)
        assert(Eval(r) == -1)
        assert(!innerExit)
        assert(log.toList == List("s"))
    }

    "state threads through updates" in {
        val program = varOp(_ => 10).map(_ => varOp(_ + 5)).map(a => varOp(identity).map(b => a + b))
        assert(Eval(runVar(0)(program)) == 30)
    }

    "state updates survive an inner scope's exit" in {
        val log                       = ListBuffer[String]()
        val inner: Int < (VarE & Say) = varOp(_ => 7).map(_ => say("x")).map(_ => 1)
        val innerScope                = recordSay("s", log)(inner)
        val program                   = innerScope.map(_ => varOp(identity))
        assert(Eval(runVar(0)(program)) == 7)
        assert(log.toList == List("s"))
    }

    "a stateful handler composes state and done" in {
        def go(n: Int): Int < Ask =
            if n == 0 then 0 else ask.map(_ => go(n - 1))
        val r = ArrowEffect.handleLoopState(Tag[Ask], 3, go(5))(
            [C] => (remaining, _) => if remaining > 0 then Loop.continue(remaining - 1, 1) else Loop.done(-1),
            (_, a) => a
        )
        assert(Eval(r) == -1)
    }

    "rejects a suspension no scope handles" in {
        val program: Int < (Ask & Say) = say("x").map(_ => ask)
        val r                          = answerAskIn(41)(program)
        val ex                         = intercept[Throwable](Eval(r.asInstanceOf[Int < Any]))
        assert(ex.getMessage.contains("unhandled suspension"))
    }

    "a clause may suspend before producing its outcome" in {
        val log = ListBuffer[String]()
        val askScope =
            ArrowEffect.handleLoop(Tag[Ask], ask.map(_ + 1))([C] => _ => say("pre").map(_ => Loop.continue(41)), a => a)
        assert(Eval(recordSay("s", log)(askScope)) == 42)
        assert(log.toList == List("s"))
    }

    "a clause may suspend before producing a done" in {
        var reached = false
        val log     = ListBuffer[String]()
        val program: Int < Ask = ask.map { a =>
            reached = true
            a + 1
        }
        val askScope =
            ArrowEffect.handleLoop(Tag[Ask], program)([C] => _ => say("pre").map(_ => Loop.done(-1)), a => a)
        assert(Eval(recordSay("s", log)(askScope)) == -1)
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
            ArrowEffect.handleLoop(Tag[Ask], program)([C] => _ => say("pre").map(_ => Loop.continue(41)), a => a)
        val r = ArrowEffect.handleLoop(Tag[Say], askScope)([C] => _ => Loop.done(-9), a => a)
        assert(Eval(r) == -9)
        assert(!reached)
    }

    "a done fired while a stateful clause outcome settles climbs to its own scope" in {
        var reached = false
        val program: Int < Ask = ask.map { a =>
            reached = true
            a + 1
        }
        val askScope = ArrowEffect.handleLoopState(Tag[Ask], 0, program)(
            [C] => (n, _) => say("pre").map(_ => Loop.continue(n + 1, n)),
            (_, a) => a
        )
        val r = ArrowEffect.handleLoop(Tag[Say], askScope)([C] => _ => Loop.done(-9), a => a)
        assert(Eval(r) == -9)
        assert(!reached)
    }

    "a clause does not see handlers inside its own scope" in {
        val log                        = ListBuffer[String]()
        val program: Int < (Ask & Say) = say("m").map(_ => ask).map(_ + 1)
        val sayInner                   = recordSay("inner", log)(program)
        val askScope =
            ArrowEffect.handleLoop(Tag[Ask], sayInner)([C] => _ => Loop.continue(say("c").map(_ => 41)), a => a)
        val ex = intercept[Throwable](Eval(askScope.asInstanceOf[Int < Any]))
        assert(ex.getMessage.contains("unhandled suspension"))
        assert(log.toList == List("inner"))
    }

    // the clause-scope contract, across every clause kind and outcome shape. A handler's clause is
    // the handler's own code: its effects belong to the handlers OUTSIDE the region, never to the
    // ones the region's body installed inside it.
    "clause scope" - {

        val innerProgram: Int < (Ask & Say) = say("m").map(_ => ask).map(_ + 1)

        "a stateful clause's suspension is answered outside its scope" in {
            val log      = ListBuffer[String]()
            val sayInner = recordSay("inner", log)(innerProgram)
            val askScope = ArrowEffect.handleLoopState(Tag[Ask], 0, sayInner)(
                [C] => (n, _) => say("c").map(_ => Loop.continue(n + 1, 41)),
                (_, a) => a
            )
            val sayOuter = recordSay("outer", log)(askScope)
            assert(Eval(sayOuter) == 42)
            assert(log.toList == List("inner", "outer"))
        }

        "a handleCont clause's suspension is answered outside its scope" in {
            val log                         = ListBuffer[String]()
            val sayInner: Int < (Ask & Say) = recordSay("inner", log)(innerProgram)
            val askScope = ArrowEffect.handleCont(Tag[Ask], sayInner)(
                [C] => (_, cont) => say("c").map(_ => cont(41)),
                a => a
            )
            val sayOuter = recordSay("outer", log)(askScope)
            assert(Eval(sayOuter) == 42)
            assert(log.toList == List("inner", "outer"))
        }

        "a clause's suspension before done is answered outside its scope" in {
            val log      = ListBuffer[String]()
            val sayInner = recordSay("inner", log)(innerProgram)
            val askScope =
                ArrowEffect.handleLoop(Tag[Ask], sayInner)([C] => _ => say("c").map(_ => Loop.done(-1)), a => a)
            val sayOuter = recordSay("outer", log)(askScope)
            assert(Eval(sayOuter) == -1)
            assert(log.toList == List("inner", "outer"))
        }

        "a clause's effectful answer is answered outside its scope" in {
            // the clause returns an effectful VALUE as the answer, not a suspension before the outcome
            val log      = ListBuffer[String]()
            val sayInner = recordSay("inner", log)(innerProgram)
            val askScope =
                ArrowEffect.handleLoop(Tag[Ask], sayInner)([C] => _ => Loop.continue(say("c").map(_ => 41)), a => a)
            val sayOuter = recordSay("outer", log)(askScope)
            assert(Eval(sayOuter) == 42)
            assert(log.toList == List("inner", "outer"))
        }

        "a clause's re-raise of its own tag is answered by the successor, not by itself" in {
            // the Ask clause re-raises Ask. Answered OUTSIDE it reaches the successor. Answered
            // INSIDE, the region's stack still holds this very handler, so the re-raise comes back
            // to the same clause, which re-raises again: the leak is a livelock. The counter bounds
            // it so a regression fails instead of hanging.
            var clauseRuns = 0
            val askScope = ArrowEffect.handleLoop(Tag[Ask], ask.map(_ + 1))(
                [C] =>
                    _ =>
                        clauseRuns += 1
                        if clauseRuns > 3 then throw new IllegalStateException("clause answered its own re-raise")
                        Loop.continue(ask.map(x => x + 100))
                ,
                a => a
            )
            val outerAsk = ArrowEffect.handleLoop(Tag[Ask], askScope)([C] => _ => Loop.continue(5), a => a)
            // askScope's clause re-raises ask, answered by outerAsk with 5, plus 100 -> 105, plus 1
            assert(Eval(outerAsk) == 106)
            assert(clauseRuns == 1)
        }

        "a leaked clause effect cannot observe the region's inner state" in {
            // if the clause's varOp were answered by the runVar INSIDE the region it would read the
            // region's state; answered outside, there is no VarE handler and it must fail
            val program: Int < (Ask & VarE) = varOp(_ => 7).map(_ => ask)
            val varInner                    = runVar(0)(program)
            val askScope = ArrowEffect.handleLoop(Tag[Ask], varInner)(
                [C] => _ => varOp(identity).map(v => Loop.continue(v)),
                a => a
            )
            val ex = intercept[Throwable](Eval(askScope.asInstanceOf[Int < Any]))
            assert(ex.getMessage.contains("unhandled suspension"))
        }

        "the region body's handlers are intact after the clause returns" in {
            // the clause's say must go outside, and the body's later say must STILL find sayInner
            val log                        = ListBuffer[String]()
            val program: Int < (Ask & Say) = ask.map(a => say("after").map(_ => a + 1))
            val sayInner                   = recordSay("inner", log)(program)
            val askScope =
                ArrowEffect.handleLoop(Tag[Ask], sayInner)([C] => _ => Loop.continue(say("c").map(_ => 41)), a => a)
            val sayOuter = recordSay("outer", log)(askScope)
            assert(Eval(sayOuter) == 42)
            assert(log.toList == List("outer", "inner"))
        }
    }

end EvalTest
