package kyo.kernel.internal

import kyo.Arrow
import kyo.Const
import kyo.Kyo
import kyo.Loop
import kyo.Maybe
import kyo.Tag
import kyo.discard
import kyo.kernel.<
import kyo.kernel.ArrowEffect
import kyo.kernel.Bracket
import kyo.kernel.ContextEffect
import kyo.kernel.Effect
import kyo.kernel.Isolate
import kyo.kernel.Region
import kyo.kernel.internal.Pending.Park
import org.scalatest.freespec.AnyFreeSpec
import scala.annotation.tailrec
import scala.collection.mutable.ListBuffer

class EvalTest extends AnyFreeSpec:

    sealed trait Ask extends ArrowEffect[Const[Unit], Const[Int]]
    def ask: Int < Ask = ArrowEffect.suspend[Any](Tag[Ask], ())

    inline def askWith[B, S](inline f: Int => B < S): B < (Ask & S) = ArrowEffect.suspendWith[Any](Tag[Ask], ())(f)

    sealed trait Say extends ArrowEffect[Const[String], Const[Unit]]
    def say(s: String): Unit < Say = ArrowEffect.suspend[Any](Tag[Say], s)

    def answerAsk[A, S](value: Int)(v: A < (Ask & S)): A < S =
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

    "values and map" - {
        "a settled value evaluates to itself" in {
            assert((42: Int < Any).eval == 42)
        }

        "map runs strictly on a settled value" in {
            var ran = false
            val v = (1: Int < Any).map { n =>
                ran = true
                n + 1
            }
            assert(ran)
            assert(v.eval == 2)
        }

        "map composes" in {
            assert((1: Int < Any).map(_ + 1).map(_ * 10).eval == 20)
        }

        "a long map tower evaluates in bounded stack" in {
            @tailrec def tower(v: Int < Any, n: Int): Int < Any =
                if n == 0 then v else tower(v.map(_ + 1), n - 1)
            assert(tower(0, 1000000).eval == 1000000)
        }

        "deep recursion through map pays rescues only" in {
            def loop(i: Int): Int < Any =
                if i == 0 then 0 else (0: Int < Any).map(_ => loop(i - 1))
            assert(loop(1000000).eval == 0)
        }

        "a computation held as a value round trips through the box" in {
            val inner: Int < Ask         = ask.map(_ + 1)
            val outer: (Int < Ask) < Any = Kyo.lift(inner)
            assert(answerAsk(41)(outer.eval).eval == 42)
        }

        "double nesting round trips one level per eval" in {
            val inner: Int < Ask                 = ask.map(_ + 1)
            val twice: ((Int < Ask) < Any) < Any = Kyo.lift(Kyo.lift(inner))
            assert(answerAsk(41)(twice.eval.eval).eval == 42)
        }

        "a pending value does not lift into a nested computation implicitly" in {
            assertTypeError("val x: (Int < Any) < Any = (1: Int < Any).map(_ + 1)")
        }

        "an eval inside a map evaluates its argument rather than nesting it" in {
            val ex = intercept[Throwable](answerAsk(1)(ask.map(_ => ask.asInstanceOf[Int < Any].eval)).eval)
            assert(ex.getMessage.contains("unhandled suspension"))
        }

        "map receives a computation held as a value unopened" in {
            val inner: Int < Ask    = ask
            var received: Int < Ask = 0
            val r: Int < Any = Kyo.lift(inner).map { c =>
                received = c
                7
            }
            assert(r.eval == 7)
            assert(answerAsk(41)(received.map(_ + 1)).eval == 42)
        }
    }

    "handleCont" - {
        "answers with the continuation in hand" in {
            val body         = ask.map(a => ask.map(b => a + b))
            val r: Int < Any = ArrowEffect.handleCont(Tag[Ask], body)([C] => (_, cont) => cont(10), a => a)
            assert(r.eval == 20)
        }

        "the captured continuation is multi-shot" in {
            val body = ask.map(_ * 2)
            val r: Int < Any = ArrowEffect.handleCont(Tag[Ask], body)(
                [C] => (_, cont) => cont(1).map(x => cont(2).map(y => x + y)),
                a => a
            )
            assert(r.eval == 6)
        }

        "can end the computation without resuming" in {
            var reached = false
            val v = ask.map { a =>
                reached = true
                a + 1
            }
            val r: Int < Any = ArrowEffect.handleCont(Tag[Ask], v)([C] => (_, _) => -1, a => a)
            assert(r.eval == -1)
            assert(!reached)
        }

        "a settled input applies done strictly" in {
            val r: Int < Any = ArrowEffect.handleCont(Tag[Ask], 41: Int < Ask)([C] => (_, cont) => cont(0), a => a + 1)
            assert(r.eval == 42)
        }

        "done applies to the settled result" in {
            val r: Int < Any = ArrowEffect.handleCont(Tag[Ask], ask.map(_ + 1))([C] => (_, cont) => cont(41), a => a * 10)
            assert(r.eval == 420)
        }

        "deep sequential operations are stack safe" in {
            def loop(n: Int): Int < Ask =
                if n == 0 then 0 else ask.map(_ => loop(n - 1))
            val r: Int < Any = ArrowEffect.handleCont(Tag[Ask], loop(100000))([C] => (_, cont) => cont(1), a => a)
            assert(r.eval == 0)
        }

        "a capture crossing an inner region resumes it without re-running its body" in {
            var runs = 0
            val inner: Int < Say = answerAsk(1)(ask.map { a =>
                runs += 1
                say("x").map(_ => a + 1)
            })
            val r: Int < Any = ArrowEffect.handleCont(Tag[Say], inner)([C] => (_, cont) => cont(()), a => a)
            assert(r.eval == 2)
            assert(runs == 1)
        }

        "each shot of a multi-shot capture resumes from capture-time state" in {
            val inner: Int < Say = ArrowEffect.handleLoopState(
                Tag[Ask],
                0,
                ask.map(a => say("x").map(_ => ask.map(b => a * 10 + b)))
            )([C] => (s, _) => Loop.continue(s + 1, s), (_, a) => a)
            val r: Int < Any = ArrowEffect.handleCont(Tag[Say], inner)(
                [C] => (_, cont) => cont(()).map(r1 => cont(()).map(r2 => r1 * 100 + r2)),
                a => a
            )
            assert(r.eval == 101)
        }
    }

    "handleLoop" - {
        "answers every operation in place" in {
            def loop(i: Int): Int < Ask =
                if i < 3 then ask.map(a => loop(i + a)) else i
            assert(answerAsk(1)(loop(0)).eval == 3)
        }

        "Loop.done stops the region and bypasses done" in {
            var reached = false
            val v = ask.map { a =>
                reached = true
                a + 1
            }
            val r: Int < Any = ArrowEffect.handleLoop(Tag[Ask], v)([C] => _ => Loop.done(-1), a => a * 10)
            assert(r.eval == -1)
            assert(!reached)
        }

        "done sees the settled result" in {
            val r: Int < Any = ArrowEffect.handleLoop(Tag[Ask], ask.map(_ + 1))([C] => _ => Loop.continue(41), a => a * 10)
            assert(r.eval == 420)
        }

        "a settled input applies done strictly" in {
            val r: Int < Any = ArrowEffect.handleLoop(Tag[Ask], 41: Int < Ask)([C] => _ => Loop.continue(0), a => a + 1)
            assert(r.eval == 42)
        }

        "deep sequential operations are stack safe" in {
            def loop(n: Int): Int < Ask =
                if n == 0 then 0 else ask.map(_ => loop(n - 1))
            assert(answerAsk(1)(loop(100000)).eval == 0)
        }

        "the innermost region of a tag answers" in {
            val inner: Int < Any = answerAsk(1)(ask)
            val outer: Int < Any = answerAsk(2)(inner: Int < Ask)
            assert(outer.eval == 1)
        }

        "a map after the region applies to the result" in {
            assert(answerAsk(41)(ask.map(_ + 1)).map(_ * 10).eval == 420)
        }

        "a foreign operation crosses the region in place" in {
            val log                     = ListBuffer[String]()
            val body: Int < (Ask & Say) = say("a").map(_ => ask).map(_ + 1)
            assert(recordSay("outer", log)(answerAsk(41)(body)).eval == 42)
            assert(log.toList == List("outer"))
        }

        "a clause that suspends before its outcome runs outside its region" in {
            val log                        = ListBuffer[String]()
            val program: Int < (Ask & Say) = say("m").map(_ => ask).map(_ + 1)
            val sayInner: Int < Ask        = recordSay("inner", log)(program)
            val askScope: Int < Say = ArrowEffect.handleLoop(Tag[Ask], sayInner)(
                [C] => _ => say("c").map(_ => Loop.continue(41)),
                a => a
            )
            assert(recordSay("outer", log)(askScope).eval == 42)
            assert(log.toList == List("inner", "outer"))
        }

        "an effectful answer runs under this handler with the interior parked" in {
            val log                        = ListBuffer[String]()
            val program: Int < (Ask & Say) = say("m").map(_ => ask).map(_ + 1)
            val sayInner: Int < Ask        = recordSay("inner", log)(program)
            val askScope: Int < Say = ArrowEffect.handleLoop(Tag[Ask], sayInner)(
                [C] => _ => Loop.continue(say("c").map(_ => 41)),
                a => a
            )
            assert(recordSay("outer", log)(askScope).eval == 42)
            assert(log.toList == List("inner", "outer"))
        }

        "an effectful answer's own-tag re-raise is answered by the successor state" in {
            var clauseRuns = 0
            val r: Int < Any = ArrowEffect.handleLoopState(Tag[Ask], 0, ask.map(_ + 1))(
                [C] =>
                    (phase, _) =>
                        clauseRuns += 1
                        if phase == 0 then Loop.continue(1, ask.map(_ + 100)) else Loop.done(-2)
                ,
                (_, a) => a
            )
            assert(r.eval == -2)
            assert(clauseRuns == 2)
        }

        "a clause suspending and then answering effectfully runs the answer under this handler" in {
            val log                        = ListBuffer[String]()
            val program: Int < (Ask & Say) = say("m").map(_ => ask).map(_ + 1)
            val sayInner: Int < Ask        = recordSay("inner", log)(program)
            val askScope: Int < Say = ArrowEffect.handleLoop(Tag[Ask], sayInner)(
                [C] => _ => say("pre").map(_ => Loop.continue(say("c").map(_ => 41))),
                a => a
            )
            assert(recordSay("outer", log)(askScope).eval == 42)
            assert(log.toList == List("inner", "outer", "outer"))
        }

        "an effectful answer's own-tag re-raise is answered by this handler" in {
            var clauseRuns = 0
            val r: Int < Any = ArrowEffect.handleLoop(Tag[Ask], ask.map(_ + 1))(
                [C] =>
                    _ =>
                        clauseRuns += 1
                        if clauseRuns == 1 then Loop.continue(ask.map(_ + 100)) else Loop.continue(1)
                ,
                a => a
            )
            assert(r.eval == 102)
            assert(clauseRuns == 2)
        }

        "a clause suspending and then answering with an own-tag re-raise is answered by this handler" in {
            var clauseRuns = 0
            val log        = ListBuffer[String]()
            val handled: Int < Say = ArrowEffect.handleLoop(Tag[Ask], ask.map(_ + 1))(
                [C] =>
                    _ =>
                        clauseRuns += 1
                        if clauseRuns == 1 then say("pre").map(_ => Loop.continue(ask.map(_ + 100)))
                        else Loop.continue(1)
                ,
                a => a
            )
            assert(recordSay("outer", log)(handled).eval == 102)
            assert(clauseRuns == 2)
            assert(log.toList == List("outer"))
        }

        "an effectful answer's remainder runs inside the interior region" in {
            val log                        = ListBuffer[String]()
            val program: Int < (Ask & Say) = ask.map(a => say("after").map(_ => a + 1))
            val sayInner: Int < Ask        = recordSay("inner", log)(program)
            val askScope: Int < Say = ArrowEffect.handleLoop(Tag[Ask], sayInner)(
                [C] => _ => Loop.continue(say("c").map(_ => 41)),
                a => a
            )
            assert(recordSay("outer", log)(askScope).eval == 42)
            assert(log.toList == List("outer", "inner"))
        }

        "an effectful answer's remainder raises the interior's effect with no outer handler for it" in {
            val log                        = ListBuffer[String]()
            val program: Int < (Ask & Say) = ask.map(a => say("after").map(_ => a + 1))
            val sayInner: Int < Ask        = recordSay("inner", log)(program)

            val pendingAnswer: Int < Any = answerAsk(0)(ask.map(_ => 41))
            val askScope: Int < Any = ArrowEffect.handleLoop(Tag[Ask], sayInner)(
                [C] => _ => Loop.continue(pendingAnswer),
                a => a
            )
            assert(askScope.eval == 42)
            assert(log.toList == List("inner"))
        }

        "a clause suspending and then answering effectfully keeps the remainder inside the interior region" in {
            val log                        = ListBuffer[String]()
            val program: Int < (Ask & Say) = ask.map(a => say("after").map(_ => a + 1))
            val sayInner: Int < Ask        = recordSay("inner", log)(program)
            val askScope: Int < Say = ArrowEffect.handleLoop(Tag[Ask], sayInner)(
                [C] => _ => say("pre").map(_ => Loop.continue(say("c").map(_ => 41))),
                a => a
            )
            assert(recordSay("outer", log)(askScope).eval == 42)
            assert(log.toList == List("outer", "outer", "inner"))
        }

        "an effectful answer's fused remainder runs inside the interior region" in {
            val log                        = ListBuffer[String]()
            val program: Int < (Ask & Say) = askWith(a => say("after").map(_ => a + 1))
            val sayInner: Int < Ask        = recordSay("inner", log)(program)
            val askScope: Int < Say = ArrowEffect.handleLoop(Tag[Ask], sayInner)(
                [C] => _ => Loop.continue(say("c").map(_ => 41)),
                a => a
            )
            assert(recordSay("outer", log)(askScope).eval == 42)
            assert(log.toList == List("outer", "inner"))
        }

        "an effectful answer's fused remainder raises the interior's effect with no outer handler for it" in {
            val log                        = ListBuffer[String]()
            val program: Int < (Ask & Say) = askWith(a => say("after").map(_ => a + 1))
            val sayInner: Int < Ask        = recordSay("inner", log)(program)
            val pendingAnswer: Int < Any   = answerAsk(0)(ask.map(_ => 41))
            val askScope: Int < Any = ArrowEffect.handleLoop(Tag[Ask], sayInner)(
                [C] => _ => Loop.continue(pendingAnswer),
                a => a
            )
            assert(askScope.eval == 42)
            assert(log.toList == List("inner"))
        }

        "a clause suspending and then answering effectfully keeps the fused remainder inside the interior region" in {
            val log                        = ListBuffer[String]()
            val program: Int < (Ask & Say) = askWith(a => say("after").map(_ => a + 1))
            val sayInner: Int < Ask        = recordSay("inner", log)(program)
            val askScope: Int < Say = ArrowEffect.handleLoop(Tag[Ask], sayInner)(
                [C] => _ => say("pre").map(_ => Loop.continue(say("c").map(_ => 41))),
                a => a
            )
            assert(recordSay("outer", log)(askScope).eval == 42)
            assert(log.toList == List("outer", "outer", "inner"))
        }

        "a computation held as a value crosses a handler as a value" in {
            val payload: Int < Say   = say("p").map(_ => 7)
            val v: (Int < Say) < Ask = ask.map(_ => Kyo.lift(payload))
            val handled: (Int < Say) < Any =
                ArrowEffect.handleLoop(Tag[Ask], v)([C] => _ => Loop.continue(0), a => Kyo.lift(a))
            var seen = ""
            val r: Int < Any = ArrowEffect.handleLoop(Tag[Say], handled.eval)(
                [C] =>
                    s =>
                        seen = s
                        Loop.continue(())
                ,
                a => a
            )
            assert(r.eval == 7)
            assert(seen == "p")
        }

        "an answer that is a computation held as a value stays a value" in {
            sealed trait Give extends ArrowEffect[Const[Unit], Const[Int < Ask]]
            val give: (Int < Ask) < Give = ArrowEffect.suspend[Any](Tag[Give], ())
            val inner: Int < Ask         = ask.map(_ + 1)
            val body: Int < (Give & Ask) = give.map(c => c)
            val r: Int < Any = answerAsk(41)(
                ArrowEffect.handleLoop(Tag[Give], body)([C] => _ => Loop.continue(Kyo.lift(inner)), a => a)
            )
            assert(r.eval == 42)
        }
    }

    "handleLoopState" - {
        "threads state through operations" in {
            val v = ask.map(a => ask.map(b => a * 10 + b))
            val r: Int < Any = ArrowEffect.handleLoopState(Tag[Ask], 1, v)(
                [C] => (s, _) => Loop.continue(s + 1, s),
                (_, a) => a
            )
            assert(r.eval == 12)
        }

        "done observes the final state" in {
            val v = ask.map(a => ask.map(b => a + b))
            val r: (Int, Int) < Any = ArrowEffect.handleLoopState(Tag[Ask], 10, v)(
                [C] => (s, _) => Loop.continue(s + 1, s),
                (s, a) => (s, a)
            )
            assert(r.eval == (12, 21))
        }

        "Loop.done bypasses done" in {
            val v = ask.map(a => ask.map(b => a + b))
            val r: String < Any = ArrowEffect.handleLoopState(Tag[Ask], 0, v)(
                [C] => (s, _) => if s == 1 then Loop.done("stopped") else Loop.continue(s + 1, 1),
                (s, a) => s"done $a"
            )
            assert(r.eval == "stopped")
        }

        "state survives a foreign crossing" in {
            val body: Int < (Ask & Say) = ask.map(a => say("x").map(_ => ask.map(b => a * 10 + b)))
            val inner: Int < Say = ArrowEffect.handleLoopState(Tag[Ask], 1, body)(
                [C] => (s, _) => Loop.continue(s + 1, s),
                (_, a) => a
            )
            val r: Int < Any = ArrowEffect.handleLoop(Tag[Say], inner)([C] => _ => Loop.continue(()), a => a)
            assert(r.eval == 12)
        }

        "a stateful clause that suspends threads its state through the park" in {
            val log = ListBuffer[String]()
            val v   = ask.map(a => ask.map(b => a * 10 + b))
            val handled: Int < Say = ArrowEffect.handleLoopState(Tag[Ask], 1, v)(
                [C] => (s, _) => say(s"state $s").map(_ => Loop.continue(s + 1, s)),
                (_, a) => a
            )
            assert(recordSay("outer", log)(handled).eval == 12)
            assert(log.size == 2)
        }

        "a stateful effectful answer's remainder runs inside the interior region" in {
            val log                        = ListBuffer[String]()
            val program: Int < (Ask & Say) = ask.map(a => say("after").map(_ => a + 1))
            val sayInner: Int < Ask        = recordSay("inner", log)(program)
            val askScope: Int < Say = ArrowEffect.handleLoopState(Tag[Ask], 40, sayInner)(
                [C] => (s, _) => Loop.continue(s + 1, say("c").map(_ => s + 1)),
                (_, a) => a
            )
            assert(recordSay("outer", log)(askScope).eval == 42)
            assert(log.toList == List("outer", "inner"))
        }

        "a stateful effectful answer's fused remainder runs inside the interior region" in {
            val log                        = ListBuffer[String]()
            val program: Int < (Ask & Say) = askWith(a => say("after").map(_ => a + 1))
            val sayInner: Int < Ask        = recordSay("inner", log)(program)
            val askScope: Int < Say = ArrowEffect.handleLoopState(Tag[Ask], 40, sayInner)(
                [C] => (s, _) => Loop.continue(s + 1, say("c").map(_ => s + 1)),
                (_, a) => a
            )
            assert(recordSay("outer", log)(askScope).eval == 42)
            assert(log.toList == List("outer", "inner"))
        }
    }

    "a captured continuation is a value" - {
        def stateful(body: Int < (Ask & Say)): Int < Say =
            ArrowEffect.handleLoopState(Tag[Ask], 0, body)([C] => (s, _) => Loop.continue(s + 1, s), (_, a) => a)

        "resumes after its region completed, in a fresh evaluation, each shot from capture-time state" in {
            var stored: Maybe[Unit => Int < Say] = Maybe.empty
            val inner: Int < Say                 = stateful(ask.map(a => say("x").map(_ => ask.map(b => a * 10 + b))))
            val r: Int < Any = ArrowEffect.handleCont(Tag[Say], inner)(
                [C] =>
                    (_, cont) =>
                        stored = Maybe(Region.leak(cont)(_))
                        -1
                ,
                a => a
            )
            assert(r.eval == -1)
            val k             = stored.get
            def resume(): Int = ArrowEffect.handleCont(Tag[Say], k(()))([C] => (_, cont) => cont(()), a => a).eval
            assert(resume() == 1)
            assert(resume() == 1)
        }

        "resumes under a later region of the same tag, which answers the remainder" in {
            var stored: Maybe[Int => Int < Ask] = Maybe.empty
            val body: Int < Ask                 = ask.map(a => ask.map(b => a * 10 + b))
            val first: Int < Any = ArrowEffect.handleCont(Tag[Ask], body)(
                [C] =>
                    (_, cont) =>
                        stored = Maybe(Region.leak(cont)(_))
                        -1
                ,
                a => a
            )
            assert(first.eval == -1)
            val k = stored.get
            assert(answerAsk(7)(k(1)).eval == 17)
            assert(answerAsk(8)(k(2)).eval == 28)
        }

        "a shot evaluated inside the clause leaves the region intact for the next" in {
            val inner: Int < Say = stateful(ask.map(a => say("x").map(_ => ask.map(b => a * 10 + b))))
            val r: Int < Any = ArrowEffect.handleCont(Tag[Say], inner)(
                [C] =>
                    (_, cont) =>
                        val now = Region.discharge(ArrowEffect.handleCont(Tag[Say], cont(()))([C] => (_, c2) => c2(()), a => a)).eval
                        cont(()).map(later => now * 100 + later)
                ,
                a => a
            )
            assert(r.eval == 101)
        }
    }

    private def requestStop(): Unit =
        discard(Safepoint.get())
        discard(Safepoint.stop(Thread.currentThread()))
        Safepoint.deadline(java.lang.System.currentTimeMillis() - 1)
    end requestStop

    "partial evaluation and parking" - {
        "parks on a pending stop and the parked value resumes to the same answer" in {
            var afterRan = false
            val body: Int < Ask =
                ask.map { a =>
                    requestStop()
                    Effect.defer {
                        afterRan = true
                        ask.map(b => a + b)
                    }
                }
            val parked = Eval.partial(answerAsk(21)(body))
            assert(parked.isInstanceOf[Park[?, ?]])
            assert(!afterRan)
            assert(parked.asInstanceOf[Park[?, ?]].entries.regions == 1)
            assert(parked.eval == 42)
            assert(afterRan)

            assert(parked.eval == 42)
        }

        "a clause that requests a stop and re-raises its operation parks in front of the re-raise" in {
            var clauseRuns = 0
            val handled: Int < Any = ArrowEffect.handleCont(Tag[Ask], ask.map(_ + 1))(
                [C] =>
                    (input, cont) =>
                        clauseRuns += 1
                        if clauseRuns > 8 then throw new IllegalStateException("re-dispatched without parking")
                        if clauseRuns == 1 then
                            requestStop()
                            ArrowEffect.suspendWith[C](Tag[Ask], input)(r => cont(r))
                        else cont(41)
                        end if
                ,
                a => a
            )
            val parked = Eval.partial(handled)
            assert(clauseRuns == 1)
            assert(parked.isInstanceOf[Park[?, ?]])
            assert(parked.eval == 42)
            assert(clauseRuns == 2)
        }

        "a stop alone, with no slice deadline, parks in front of a re-raise" in {
            // the scheduler's join clause requests a stop and nothing else, so the stop has to be
            // honored by itself on every platform, not only where a slice deadline backs it
            var clauseRuns = 0
            val handled: Int < Any = ArrowEffect.handleCont(Tag[Ask], ask.map(_ + 1))(
                [C] =>
                    (input, cont) =>
                        clauseRuns += 1
                        if clauseRuns > 8 then throw new IllegalStateException("re-dispatched without parking")
                        if clauseRuns == 1 then
                            discard(Safepoint.get())
                            discard(Safepoint.stop(Thread.currentThread()))
                            ArrowEffect.suspendWith[C](Tag[Ask], input)(r => cont(r))
                        else cont(41)
                        end if
                ,
                a => a
            )
            val parked = Eval.partial(handled)
            assert(clauseRuns == 1)
            assert(parked.isInstanceOf[Park[?, ?]])
            assert(parked.eval == 42)
            assert(clauseRuns == 2)
        }

        "a stop already pending returns the input before the slice starts" in {
            var ran = false
            val input: Int < Any = Effect.defer {
                ran = true
                42
            }
            requestStop()
            val back = Eval.partial(input)
            assert(!ran)
            assert(back.asInstanceOf[AnyRef] eq input.asInstanceOf[AnyRef])

            assert(Eval.partial(back).evalNow == Maybe(42))
            assert(ran)
        }

        // #1820. `ensureMap` makes a value and the obligation it creates one step, for a resource open the moment
        // the acquire returns. That obligation sits in the cont, waiting on a value that has already arrived, so a
        // walk descending into values alone steps past it and what the acquire produced is released by nobody.
        "a release waiting on a value that already arrived is found on abandonment" in {
            var applied = Maybe.empty[String]
            val v: Int < Any =
                Effect.defer {
                    requestStop()
                    Effect.defer("token")
                }.ensureMap { token =>
                    applied = Maybe(token)
                    1
                }
            val parked = Eval.partial(v)
            assert(applied.isEmpty, "the premise is that the stop parked before the ensure applied")
            // The budgeted entry point, the one a fiber abandonment uses. The unbudgeted `release` never steps a
            // deferral, so it cannot reach a release waiting one step further in.
            Eval.release(parked, new RuntimeException("abandoned"), Tag[Ask])([C] => (_: Unit) => ())
            assert(applied == Maybe("token"), s"the release never ran, it saw $applied")
        }

        // An `Ensure` that does not settle its debt by being applied. `Scope.acquireRelease`'s registers against a
        // finalizer that outlives the fiber, so applying it is the whole obligation; `Bracket`'s builds the `Cell`
        // that owns the release and returns the region holding it, so applying and discarding drops the obligation.
        "a release the abandoned Ensure installs rather than registers is still run" in {
            var released = Maybe.empty[Int]
            val v: Int < Any =
                Bracket(Effect.defer {
                    requestStop()
                    Effect.defer(7)
                })(a => Effect.defer(a + 1)) { (a, _) =>
                    released = Maybe(a)
                }
            val parked = Eval.partial(v)
            assert(released.isEmpty, "the premise is that the stop parked before the bracket installed its region")
            Eval.release(parked, new RuntimeException("abandoned"), Tag[Ask])([C] => (_: Unit) => ())
            assert(released == Maybe(7), s"the release never ran for what the acquire produced, it saw $released")
        }

        "a stateful region parked mid-loop resumes at the parked state" in {
            val body: Int < Ask =
                ask.map { a =>
                    requestStop()
                    Effect.defer(ask.map(b => a * 10 + b))
                }
            val handled: Int < Any = ArrowEffect.handleLoopState(Tag[Ask], 1, body)(
                [C] => (s, _) => Loop.continue(s + 1, s),
                (_, a) => a
            )
            val parked = Eval.partial(handled)
            assert(parked.isInstanceOf[Park[?, ?]])

            assert(parked.asInstanceOf[Park[?, ?]].entries.state(0).asInstanceOf[Int] == 2)

            assert(parked.eval == 12)
        }

        "a resumed region restores its parked binding" in {
            sealed trait Cfg extends ContextEffect[Int]
            def read: Int < Cfg = ContextEffect.suspend(Tag[Cfg])
            val body: (Int, Int) < Cfg =
                read.map { r1 =>
                    requestStop()
                    Effect.defer(read.map(r2 => (r1, r2)))
                }
            val handled: (Int, Int) < Any =
                ContextEffect.handleInheritable(Tag[Cfg], 11, _ + 1)(body)
            val parked = Eval.partial(handled)
            assert(parked.isInstanceOf[Park[?, ?]])

            val resumed = ContextEffect.handleInheritable(Tag[Cfg], 100)(parked)
            assert(resumed.eval == (11, 11))
        }

        "a nested eval inside a slice runs unarmed and completes despite the pending stop" in {
            var nested = 0
            val body: Int < Any =
                Effect.defer {
                    requestStop()
                    nested = (Effect.defer(Effect.defer(41)): Int < Any).eval
                    Effect.defer(nested + 1)
                }
            val parked = Eval.partial(body)

            assert(nested == 41)
            assert(parked.isInstanceOf[Pending[?, ?]])
            assert(!parked.isInstanceOf[Park[?, ?]])
            assert(parked.eval == 42)
        }

        "a throw during a slice still consumes the stop at the boundary" in {
            val body: Int < Any = Effect.defer {
                requestStop()
                Effect.defer((throw Boom): Int)
            }
            val parked = Eval.partial(body)

            assert(parked.isInstanceOf[Pending[?, ?]])
            val ex = intercept[RuntimeException](parked.eval)
            assert(ex eq Boom)

            var ran = false
            val next: Int < Any = Effect.defer {
                ran = true
                3
            }
            assert(Eval.partial(next).evalNow == Maybe(3))
            assert(ran)
        }

        "a parked value owes its regions' releases innermost first" in {
            val log = ListBuffer[String]()
            sealed trait CfgA extends ContextEffect[Int]
            sealed trait CfgB extends ContextEffect[Int]
            def readA: Int < CfgA = ContextEffect.suspend(Tag[CfgA])
            val body: Int < (CfgA & CfgB) =
                readA.map { c =>
                    requestStop()
                    Effect.defer(readA.map(_ + c))
                }
            val inner: Int < CfgB =
                ContextEffect.handle(Tag[CfgA])(
                    _.getOrElse(1),
                    fork = (parent: Int) => parent,
                    join = (parent: Int, _: Int, _: Int) => parent,
                    release = (_: Int, _: Throwable) => discard(log += "inner")
                )(body)
            val outer: Int < Any =
                ContextEffect.handle(Tag[CfgB])(
                    _.getOrElse(2),
                    fork = (parent: Int) => parent,
                    join = (parent: Int, _: Int, _: Int) => parent,
                    release = (_: Int, _: Throwable) => discard(log += "outer")
                )(inner)
            val parked = Eval.partial(outer)
            assert(parked.isInstanceOf[Park[?, ?]])
            assert(parked.asInstanceOf[Park[?, ?]].entries.regions == 2)
            Eval.release(parked, Boom)
            assert(log.toList == List("inner", "outer"))
        }

        "a discarded region value releases its interior before its own extent" in {
            val log = ListBuffer[String]()
            sealed trait CfgA extends ContextEffect[Int]
            sealed trait CfgB extends ContextEffect[Int]
            def readA: Int < CfgA         = ContextEffect.suspend(Tag[CfgA])
            val body: Int < (CfgA & CfgB) = readA.map(a => readA.map(_ + a))
            val inner: Int < CfgB =
                ContextEffect.handle(Tag[CfgA])(
                    _.getOrElse(1),
                    fork = (parent: Int) => parent,
                    join = (parent: Int, _: Int, _: Int) => parent,
                    release = (_: Int, _: Throwable) => discard(log += "inner")
                )(body)
            val outer: Int < Any =
                ContextEffect.handle(Tag[CfgB])(
                    _.getOrElse(2),
                    fork = (parent: Int) => parent,
                    join = (parent: Int, _: Int, _: Int) => parent,
                    release = (_: Int, _: Throwable) => discard(log += "outer")
                )(inner)
            Eval.release(outer, Boom)
            assert(log.toList == List("inner", "outer"))

            log.clear()
            Eval.release(outer.map(_ + 1), Boom)
            assert(log.toList == List("inner", "outer"))
        }

        "abandoning a park with no outstanding releases is a no-op" in {
            val v: Int < Any = Effect.defer {
                requestStop()
                1
            }.map(_ + 41)
            val p = Eval.partial(v)
            assert(p.isInstanceOf[Pending[?, ?]])
            Eval.release(p, Boom)
            assert(p.eval == 42)
        }

        "an abandoned region value derives its state once and releases that state" in {
            var derives = 0
            val log     = ListBuffer[String]()
            sealed trait Cfg extends ContextEffect[Int]
            val region: Int < Any =
                ContextEffect.handle(Tag[Cfg])(
                    derive = (_: Maybe[Int]) =>
                        derives += 1
                        derives
                    ,
                    fork = (s: Int) => s,
                    join = (p: Int, _: Int, _: Int) => p,
                    release = (s: Int, _: Throwable) => discard(log += s"release $s")
                )(ContextEffect.suspend(Tag[Cfg]))
            Eval.release(region, Boom)
            assert(derives == 1)
            assert(log.toList == List("release 1"))
            discard(region.toString)
            assert(derives == 1)
        }

        "a double abandonment reaches a raw hook twice and a bracket once" in {
            val log     = ListBuffer[String]()
            var bracket = 0
            sealed trait Cfg extends ContextEffect[Int]
            def hooked[A, S](value: Int)(v: A < (Cfg & S)): A < S =
                ContextEffect.handle(Tag[Cfg])(
                    derive = (_: Maybe[Int]) => value,
                    fork = (s: Int) => s,
                    join = (p: Int, _: Int, _: Int) => p,
                    release = (s: Int, _: Throwable) => discard(log += s"release cfg $s")
                )(v)
            val v: Int < Any =
                Bracket(Effect.defer(1)) { a =>
                    hooked(a)(Effect.defer {
                        requestStop()
                        Effect.defer(a)
                    })
                }((_, _) => bracket += 1)
            val p = Eval.partial(v)
            assert(p.evalNow.isEmpty)
            Eval.release(p, Boom)
            Eval.release(p, Boom)
            assert(bracket == 1)
            assert(log.toList == List("release cfg 1", "release cfg 1"))
        }

        "release of a settled value or an obligation-free computation owes nothing" in {
            Eval.release(42: Int < Any, Boom)
            var ran = false
            val v: Int < Ask = ask.map { a =>
                ran = true; a
            }
            Eval.release(v, Boom)
            assert(!ran)
        }

        "a context binding owes its release through the public surface" in {
            val log = ListBuffer[Int]()
            sealed trait Cfg extends ContextEffect[Int]
            def read: Int < Cfg = ContextEffect.suspend(Tag[Cfg])
            val body: Int < Cfg =
                read.map { c =>
                    requestStop()
                    Effect.defer(read.map(_ + c))
                }
            val handled: Int < Any =
                ContextEffect.handle(Tag[Cfg])(
                    _.getOrElse(7),
                    fork = (parent: Int) => parent,
                    join = (parent: Int, _: Int, _: Int) => parent,
                    release = (state: Int, _: Throwable) => discard(log += state)
                )(body)
            val parked = Eval.partial(handled)
            assert(parked.isInstanceOf[Park[?, ?]])
            Eval.release(parked, Boom)
            assert(log.toList == List(7))
        }

        "a failure unwinding past a context binding runs its release" in {
            val log = ListBuffer[Int]()
            sealed trait Cfg extends ContextEffect[Int]
            val body: Int < Cfg = ContextEffect.suspend(Tag[Cfg]).map(_ => (throw Boom): Int)
            val handled: Int < Any =
                ContextEffect.handle(Tag[Cfg])(
                    _.getOrElse(7),
                    fork = (parent: Int) => parent,
                    join = (parent: Int, _: Int, _: Int) => parent,
                    release = (state: Int, _: Throwable) => discard(log += state)
                )(body)
            val ex = intercept[RuntimeException](handled.eval)
            assert(ex eq Boom)
            assert(log.toList == List(7))
        }

        "a binding is not released when an inner region recovers the failure" in {
            val log = ListBuffer[Int]()
            sealed trait Cfg extends ContextEffect[Int]
            val body: Int < (Ask & Cfg) = ask.map(_ => (throw Boom): Int)
            val inner: Int < Cfg = ArrowEffect.handleCont[Const[Unit], Const[Int], Ask, Int, Int, Cfg, Any](Tag[Ask], body)(
                [C] => (_, cont) => cont(0),
                a => a,
                _ => Maybe(9)
            )
            val handled: Int < Any =
                ContextEffect.handle(Tag[Cfg])(
                    _.getOrElse(7),
                    fork = (parent: Int) => parent,
                    join = (parent: Int, _: Int, _: Int) => parent,
                    release = (state: Int, _: Throwable) => discard(log += state)
                )(inner)
            assert(handled.eval == 9)
            assert(log.isEmpty)
        }

        "a throwing release does not starve the ones after it" in {
            val log   = ListBuffer[String]()
            val cause = new RuntimeException("cause")
            object Bad        extends RuntimeException("bad", null, false, false)
            sealed trait CfgA extends ContextEffect[Int]
            sealed trait CfgB extends ContextEffect[Int]
            def readA: Int < CfgA = ContextEffect.suspend(Tag[CfgA])
            val body: Int < (CfgA & CfgB) =
                readA.map { c =>
                    requestStop()
                    Effect.defer(readA.map(_ + c))
                }
            val inner: Int < CfgB =
                ContextEffect.handle(Tag[CfgA])(
                    _.getOrElse(1),
                    fork = (parent: Int) => parent,
                    join = (parent: Int, _: Int, _: Int) => parent,
                    release = (_: Int, _: Throwable) => throw Bad
                )(body)
            val handled: Int < Any =
                ContextEffect.handle(Tag[CfgB])(
                    _.getOrElse(2),
                    fork = (parent: Int) => parent,
                    join = (parent: Int, _: Int, _: Int) => parent,
                    release = (_: Int, _: Throwable) => discard(log += "outer")
                )(inner)
            val parked = Eval.partial(handled)
            assert(parked.isInstanceOf[Park[?, ?]])
            Eval.release(parked, cause)
            assert(log.toList == List("outer"))
            assert(cause.getSuppressed.exists(_ eq Bad))
        }

        "a release rethrowing the signal itself does not self-suppress" in {
            val log   = ListBuffer[String]()
            val cause = new RuntimeException("cause")
            sealed trait CfgA extends ContextEffect[Int]
            sealed trait CfgB extends ContextEffect[Int]
            def readA: Int < CfgA = ContextEffect.suspend(Tag[CfgA])
            val body: Int < (CfgA & CfgB) =
                readA.map { c =>
                    requestStop()
                    Effect.defer(readA.map(_ + c))
                }
            val inner: Int < CfgB =
                ContextEffect.handle(Tag[CfgA])(
                    _.getOrElse(1),
                    fork = (parent: Int) => parent,
                    join = (parent: Int, _: Int, _: Int) => parent,
                    release = (_: Int, ex: Throwable) => throw ex
                )(body)
            val handled: Int < Any =
                ContextEffect.handle(Tag[CfgB])(
                    _.getOrElse(2),
                    fork = (parent: Int) => parent,
                    join = (parent: Int, _: Int, _: Int) => parent,
                    release = (_: Int, _: Throwable) => discard(log += "outer")
                )(inner)
            val parked = Eval.partial(handled)
            assert(parked.isInstanceOf[Park[?, ?]])
            Eval.release(parked, cause)
            assert(log.toList == List("outer"))
            assert(cause.getSuppressed.isEmpty)
        }

        "chain onto a parked value composes" in {
            val body: Int < Ask =
                ask.map { a =>
                    requestStop()
                    Effect.defer(ask.map(b => a + b))
                }
            val parked = Eval.partial(answerAsk(21)(body))
            assert(parked.isInstanceOf[Park[?, ?]])
            assert(parked.map(_ * 10).eval == 420)
        }
    }

    "an unhandled operation is a bug" in {
        val ex = intercept[Throwable](ask.asInstanceOf[Int < Any].eval)
        assert(ex.getMessage.contains("unhandled suspension"))
    }

    "a nested eval shares the thread's stack and sees none of the outer regions" in {
        val inner = answerAsk(5)(ask)
        val outer = answerAsk(1)(ask.map(a => a + inner.eval))
        assert(outer.eval == 6)
        val leak = intercept[Throwable](answerAsk(1)(ask.map(_ => Eval[Int, Any](ask.asInstanceOf[Int < Any]))).eval)
        assert(leak.getMessage.contains("unhandled suspension"))
        assert(answerAsk(41)(ask.map(_ + 1)).eval == 42)
    }

    "an eval cleans its stack after a throw" in {
        def boom: Int < Any = (0: Int < Any).map(_ => throw new RuntimeException("boom"))
        intercept[RuntimeException](answerAsk(1)(ask.map(_ => boom)).eval)
        assert(answerAsk(41)(ask.map(_ + 1)).eval == 42)
    }

    private object Boom extends RuntimeException("boom", null, false, false)

    "the exit law" - {
        sealed trait Count extends ContextEffect[Int]
        def read: Int < Count = ContextEffect.suspend(Tag[Count])

        "a context region's exit reverts its own binding to the enclosing one" in {
            val inner: Int < Count = ContextEffect.handleInheritable(Tag[Count], 99)(read)
            val r                  = inner.map(a => read.map(b => (a, b)))
            assert(ContextEffect.handleInheritable(Tag[Count], 10)(r).eval == (99, 10))
        }

        "a context region's exit removes a binding that had no enclosing one" in {
            val inner: Int < Any = ContextEffect.handleInheritable(Tag[Count], 99)(read)

            val r: Int < Any = inner.map(a => ContextEffect.suspend(Tag[Count], -1).map(b => a * 1000 + b))
            assert(r.eval == 98999)
        }

    }

    "an unanswered default is taken exactly once" in {
        sealed trait Count extends ContextEffect[Int]

        var evals = 0
        val r: Int < Any = ContextEffect.suspend(
            Tag[Count], {
                evals += 1
                42
            }
        )
        assert(r.eval == 42)
        assert(evals == 1)
    }

    "regions that fail and recover in sequence cost no stack" in {
        def recovering(to: Int): Int < Any =
            val body: Int < Ask = ask.map(_ => (throw Boom): Int)
            ArrowEffect.handleCont(Tag[Ask], body)([C] => (_, cont) => cont(0), a => a, _ => Maybe(to))
        def go(i: Int): Int < Any =
            if i == 0 then 0
            else recovering(i).map(_ => go(i - 1))
        assert(go(10000).eval == 0)
    }

    "a region recovering across a foreign crossing leaves the budget where it found it" in {
        val samples = ListBuffer.empty[Safepoint.State]
        def sample(): Unit =
            val slot = Safepoint.get()

            val d = Safepoint.save(slot)
            Safepoint.restore(slot, d)
            samples += d
        end sample

        def crossing(to: Int): Int < Ask =
            val body: Int < (Say & Ask) = ask.map(_ => (throw Boom): Int)
            ArrowEffect.handleCont[Const[String], Const[Unit], Say, Int, Int, Ask, Any](Tag[Say], body)(
                [C] => (_, cont) => cont(()),
                a => a,
                _ => Maybe(to)
            )
        end crossing

        def go(i: Int): Int < Ask =
            if i == 0 then (0: Int < Ask)
            else
                crossing(i).map { _ =>
                    sample()
                    go(i - 1)
                }
        assert(answerAsk(0)(go(100)).eval == 0)
        assert(samples.size == 100)

        assert(samples.forall(_.equals(samples.head)))
    }

    "a recover that fails itself is the failure the enclosing region sees" in {
        object Inner extends RuntimeException("inner", null, false, false)
        var seen = Maybe.empty[Throwable]

        val body: Int < (Say & Ask) = Effect.defer((throw Boom): Int < (Say & Ask))
        val inner: Int < Ask = ArrowEffect.handleCont[Const[String], Const[Unit], Say, Int, Int, Ask, Any](Tag[Say], body)(
            [C] => (_, cont) => cont(()),
            a => a,
            _ => throw Inner
        )
        val outer: Int < Any = ArrowEffect.handleCont(Tag[Ask], inner)(
            [C] => (_, cont) => cont(0),
            a => a,
            ex =>
                seen = Maybe(ex)
                Maybe(7)
        )
        assert(outer.eval == 7)

        assert(seen.exists(_ eq Inner))
    }

    "owed dumps" - {
        "a dropped capture's regions release when the answering region exits" in {
            val log = ListBuffer[String]()
            sealed trait Cfg extends ContextEffect[Int]
            val body: Int < Ask =
                ContextEffect.handle(Tag[Cfg])(
                    _.getOrElse(7),
                    fork = (parent: Int) => parent,
                    join = (parent: Int, _: Int, _: Int) => parent,
                    release = (_: Int, _: Throwable) => discard(log += "released")
                )(ask.map(x => x))
            val dropped: Int < Any = ArrowEffect.handleCont(Tag[Ask], body)([C] => (_, _) => -1, b => b)
            val after: Int < Any = dropped.map { r =>
                discard(log += s"after $r")
                r
            }
            assert(after.eval == -1)
            assert(log.toList == List("released", "after -1"))
        }

        "sibling dumps drain newest first at the owner's exit" in {
            val log = ListBuffer[String]()
            sealed trait CfgA extends ContextEffect[Int]
            sealed trait CfgB extends ContextEffect[Int]
            def scoped[E <: ContextEffect[Int]](tag: Tag[E], name: String)(v: Int < (Ask & E)): Int < Ask =
                ContextEffect.handle(tag)(
                    (_: Maybe[Int]).getOrElse(0),
                    fork = (parent: Int) => parent,
                    join = (parent: Int, _: Int, _: Int) => parent,
                    release = (_: Int, _: Throwable) => discard(log += name)
                )(v)
            val body: Int < Ask = scoped(Tag[CfgA], "a")(ask.map(x => x))
            var first           = true
            val handled: Int < Any = ArrowEffect.handleCont(Tag[Ask], body)(
                [C] =>
                    (_, _) =>
                        if first then
                            first = false
                            scoped(Tag[CfgB], "b")(ask.map(x => x))
                        else -1,
                b => b
            )
            assert(handled.eval == -1)
            assert(log.toList == List("b", "a"))
        }

        "a raw release hook fires once for a region resumed from a dump and then unwound" in {
            val log = ListBuffer[Int]()
            sealed trait Cfg extends ContextEffect[Int]
            val body: Int < Ask =
                ContextEffect.handle(Tag[Cfg])(
                    _.getOrElse(7),
                    fork = (parent: Int) => parent,
                    join = (parent: Int, _: Int, _: Int) => parent,
                    release = (state: Int, _: Throwable) => discard(log += state)
                )(ask.map(_ => (throw Boom): Int))
            val outer: Int < Any = ArrowEffect.handleCont(Tag[Ask], body)([C] => (_, cont) => cont(1), b => b)
            val ex               = intercept[RuntimeException](outer.eval)
            assert(ex eq Boom)
            assert(log.toList == List(7))
        }

        "a pooled stack reused by a later eval carries no stale obligations" in {
            sealed trait CfgA extends ContextEffect[Int]
            sealed trait CfgB extends ContextEffect[Int]
            var drops = 0
            val body: Int < Ask =
                ContextEffect.handle(Tag[CfgA])(
                    _.getOrElse(1),
                    fork = (parent: Int) => parent,
                    join = (parent: Int, _: Int, _: Int) => parent,
                    release = (_: Int, _: Throwable) => drops += 1
                )(ask.map(x => x))
            val dropped: Int < Any = ArrowEffect.handleCont(Tag[Ask], body)([C] => (_, _) => -1, b => b)
            assert(dropped.eval == -1)
            assert(drops == 1)
            var completions = 0
            var releases    = 0
            val clean: Int < Any =
                ContextEffect.handle(Tag[CfgB])(
                    _.getOrElse(2),
                    fork = (parent: Int) => parent,
                    join = (parent: Int, _: Int, _: Int) => parent,
                    done = (_: Int) => completions += 1,
                    release = (_: Int, _: Throwable) => releases += 1
                )(Effect.defer(5))
            assert(clean.eval == 5)
            assert(completions == 1)
            assert(releases == 0)
            assert(drops == 1)
        }

        "a clause reads the outer binding, not a dumped one" in {
            sealed trait Cfg extends ContextEffect[Int]
            def read: Int < Cfg = ContextEffect.suspend(Tag[Cfg])
            val body: Int < (Ask & Cfg) =
                (ContextEffect.handleInheritable(Tag[Cfg], 1)(ask.map(x => x)): Int < Ask)
            val handled: Int < Cfg = ArrowEffect.handleCont(Tag[Ask], body)(
                [C] => (_, cont) => read.map(c => cont(c)),
                b => b
            )
            val outer: Int < Any = ContextEffect.handleInheritable(Tag[Cfg], 100)(handled)
            assert(outer.eval == 100)
        }
    }

    sealed trait VarE extends ArrowEffect[Const[Int => Int], Const[Int]]

    def varOp(f: Int => Int): Int < VarE = ArrowEffect.suspend[Any](Tag[VarE], f)

    def runVar[A, S](init: Int)(v: A < (VarE & S)): A < S =
        ArrowEffect.handleLoopState(Tag[VarE], init, v)(
            [C] =>
                (state, f) =>
                    val v2 = f(state)
                    Loop.continue(v2, v2)
            ,
            (_, a) => a
        )

    def trailing(depth: Int, runs: Array[Int]): Int < Ask =
        var r: Int < Ask = ask
        for i <- 0 until depth do
            val j = i
            r = r.map { x =>
                runs(j) += 1
                x + 1
            }
        end for
        r
    end trailing

    "values and map, ported" - {
        "evaluates andThen and unit" in {
            assert((1: Int < Any).andThen(2: Int < Any).eval == 2)
            assert((1: Int < Any).unit.eval == ())
        }

        "evalNow is present only for settled values" in {
            assert((1: Int < Any).evalNow.contains(1))
            assert(ask.evalNow.isEmpty)
        }

        "a deep map tower over a suspension evaluates in bounded stack" in {
            @tailrec def tower(v: Int < Ask, n: Int): Int < Ask =
                if n == 0 then v else tower(v.map(_ + 1), n - 1)
            assert(answerAsk(1)(tower(ask, 100000)).eval == 100001)
        }

        "a nested computation stays data until flattened" in {
            val nested: (Int < Ask) < Any = Kyo.lift(ask.map(_ + 1))
            assert(answerAsk(1)(nested.flatten).eval == 2)
        }
    }

    "handleLoop, ported" - {
        "regions exit innermost first" in {
            val log   = ListBuffer[String]()
            val inner = answerAsk(41)(ask.map(_ + 1)).map(_ * 10)
            val outer = recordSay("s", log)(inner).map(_ + 1000)
            assert(outer.eval == 1420)
        }

        "an effectful answer built by a deferred block resolves on the settled outcome path" in {
            val r = ArrowEffect.handleLoop(Tag[Ask], ask.map(_ + 1))([C] => _ => Loop.continue(Effect.defer(7)))
            assert(r.eval == 8)
        }

        "a deferred clause outcome resolves before the region continues" in {
            def loop(i: Int): Int < Ask =
                if i < 3 then ask.map(a => loop(i + a)) else i
            val r = ArrowEffect.handleLoop(Tag[Ask], loop(0))([C] => _ => Effect.defer(Loop.continue(1)))
            assert(r.eval == 3)
        }

        "a clause that suspends before a done runs outside its region" in {
            var reached = false
            val log     = ListBuffer[String]()
            val program: Int < Ask = ask.map { a =>
                reached = true
                a + 1
            }
            val askScope: Int < Say =
                ArrowEffect.handleLoop(Tag[Ask], program)([C] => _ => say("pre").map(_ => Loop.done(-1)), a => a)
            assert(recordSay("s", log)(askScope).eval == -1)
            assert(!reached)
            assert(log.toList == List("s"))
        }

        "a done climbs past an inner region without running its remainder" in {
            val log                        = ListBuffer[String]()
            var innerExit                  = false
            val program: Int < (Ask & Say) = say("m").map(_ => ask).map(_ + 1)
            val mapped = recordSay("s", log)(program).map { v =>
                innerExit = true
                v
            }
            val r = ArrowEffect.handleLoop(Tag[Ask], mapped)([C] => _ => Loop.done(-1), a => a)
            assert(r.eval == -1)
            assert(!innerExit)
            assert(log.toList == List("s"))
        }

        "a done fired while a clause outcome settles climbs to its own region" in {
            var reached = false
            val program: Int < Ask = ask.map { a =>
                reached = true
                a + 1
            }
            val askScope =
                ArrowEffect.handleLoop(Tag[Ask], program)([C] => _ => say("pre").map(_ => Loop.continue(41)))
            val r = ArrowEffect.handleLoop(Tag[Say], askScope)([C] => _ => Loop.done(-9))
            assert(r.eval == -9)
            assert(!reached)
        }
    }

    "handleLoopState, ported" - {
        "composes a state update with a done" in {
            def go(n: Int): Int < Ask =
                if n == 0 then 0 else ask.map(_ => go(n - 1))
            val r = ArrowEffect.handleLoopState(Tag[Ask], 3, go(5))(
                [C] => (remaining, _) => if remaining > 0 then Loop.continue(remaining - 1, 1) else Loop.done(-1),
                (_, a) => a
            )
            assert(r.eval == -1)
        }

        "state threads through updates" in {
            val program = varOp(_ => 10).map(_ => varOp(_ + 5)).map(a => varOp(identity).map(b => a + b))
            assert(runVar(0)(program).eval == 30)
        }

        "state updates survive an inner region's exit" in {
            val log                       = ListBuffer[String]()
            val inner: Int < (VarE & Say) = varOp(_ => 7).map(_ => say("x")).map(_ => 1)
            val innerScope                = recordSay("s", log)(inner)
            val program                   = innerScope.map(_ => varOp(identity))
            assert(runVar(0)(program).eval == 7)
            assert(log.toList == List("s"))
        }

        "a done fired while a stateful clause outcome settles climbs to its own region" in {
            var reached = false
            val program: Int < Ask = ask.map { a =>
                reached = true
                a + 1
            }
            val askScope = ArrowEffect.handleLoopState(Tag[Ask], 0, program)(
                [C] => (n, _) => say("pre").map(_ => Loop.continue(n + 1, n))
            )
            val r = ArrowEffect.handleLoop(Tag[Say], askScope)([C] => _ => Loop.done(-9))
            assert(r.eval == -9)
            assert(!reached)
        }
    }

    "clause scope, ported" - {
        def innerProgram: Int < (Ask & Say) = say("m").map(_ => ask).map(_ + 1)

        "a stateful clause's suspension is answered outside its scope" in {
            val log      = ListBuffer[String]()
            val sayInner = recordSay("inner", log)(innerProgram)
            val askScope = ArrowEffect.handleLoopState(Tag[Ask], 0, sayInner)(
                [C] => (n, _) => say("c").map(_ => Loop.continue(n + 1, 41))
            )
            val sayOuter = recordSay("outer", log)(askScope)
            assert(sayOuter.eval == 42)
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
            assert(sayOuter.eval == 42)
            assert(log.toList == List("inner", "outer"))
        }

        "a clause's suspension before done is answered outside its scope" in {
            val log      = ListBuffer[String]()
            val sayInner = recordSay("inner", log)(innerProgram)
            val askScope =
                ArrowEffect.handleLoop(Tag[Ask], sayInner)([C] => _ => say("c").map(_ => Loop.done(-1)), a => a)
            val sayOuter = recordSay("outer", log)(askScope)
            assert(sayOuter.eval == -1)
            assert(log.toList == List("inner", "outer"))
        }

        "a clause's own-tag suspension before its outcome is answered by the successor" in {
            var clauseRuns = 0
            val askScope: Int < Ask = ArrowEffect.handleLoop(Tag[Ask], ask.map(_ + 1))(
                [C] =>
                    _ =>
                        clauseRuns += 1
                        if clauseRuns > 3 then throw new IllegalStateException("clause answered its own suspension")
                        ask.map(x => Loop.continue(x + 100))
            )
            val outerAsk = ArrowEffect.handleLoop(Tag[Ask], askScope)([C] => _ => Loop.continue(5))
            assert(outerAsk.eval == 106)
            assert(clauseRuns == 1)
        }

        "a clause does not see handlers inside its own scope" in {
            val log      = ListBuffer[String]()
            val sayInner = recordSay("inner", log)(innerProgram)
            val askScope =
                ArrowEffect.handleLoop(Tag[Ask], sayInner)([C] => _ => Loop.continue(say("c").map(_ => 41)), a => a)
            val ex = intercept[Throwable](askScope.asInstanceOf[Int < Any].eval)
            assert(ex.getMessage.contains("unhandled suspension"))
            assert(log.toList == List("inner"))
        }

        "a leaked clause effect cannot observe the region's inner state" in {
            val program: Int < (Ask & VarE) = varOp(_ => 7).map(_ => ask)
            val varInner                    = runVar(0)(program)
            val askScope = ArrowEffect.handleLoop(Tag[Ask], varInner)(
                [C] => _ => varOp(identity).map(v => Loop.continue(v)),
                a => a
            )
            val ex = intercept[Throwable](askScope.asInstanceOf[Int < Any].eval)
            assert(ex.getMessage.contains("unhandled suspension"))
        }
    }

    "a captured continuation is a value, ported" - {
        "a continuation folded from the eval stack runs every pending map exactly once" in {
            for depth <- List(8, 64) do
                val runs = new Array[Int](depth)
                val r: Int < Any =
                    ArrowEffect.handleCont(Tag[Ask], trailing(depth, runs))([C] => (_, cont) => cont(0), a => a)
                assert(r.eval == depth)
                assert(runs.forall(_ == 1))
        }

        "a continuation applied twice replays trailing maps twice at any depth" in {
            for depth <- List(8, 64) do
                val runs = new Array[Int](depth)
                val r: Int < Any = ArrowEffect.handleCont(Tag[Ask], trailing(depth, runs))(
                    [C] => (_, cont) => cont(0).map(a => cont(10).map(b => a + b)),
                    a => a
                )
                assert(r.eval == depth + (10 + depth))
                assert(runs.forall(_ == 2))
        }

        "stays valid after its eval completes, replaying the trailing maps once per shot" in {
            for depth <- List(8, 64) do
                val runs                                = new Array[Int](depth)
                var stored: Maybe[Arrow[Int, Int, Ask]] = Maybe.empty
                val first: Int < Any = ArrowEffect.handleCont(Tag[Ask], trailing(depth, runs))(
                    [C] =>
                        (_, cont) =>
                            stored = Maybe(Region.leak(cont))
                            -1
                    ,
                    a => a
                )
                assert(first.eval == -1)
                assert(runs.forall(_ == 0))
                val k = stored.get
                assert(answerAsk(0)(k(100)).eval == 100 + depth)
                assert(runs.forall(_ == 1))
                assert(answerAsk(0)(k(200)).eval == 200 + depth)
                assert(runs.forall(_ == 2))
        }
    }

    "top level, ported" - {
        "an operation no region in the row handles is a bug" in {
            val program: Int < (Ask & Say) = say("x").map(_ => ask)
            val r                          = answerAsk(41)(program)
            val ex                         = intercept[Throwable](r.asInstanceOf[Int < Any].eval)
            assert(ex.getMessage.contains("unhandled suspension"))
        }

        "a throw inside a region leaves no findable handler behind" in {
            def stateful[A](v: A < Ask): A < Any =
                ArrowEffect.handleLoopState(Tag[Ask], 0, v)(
                    [C] => (st, _) => Loop.continue(st + 1, st),
                    (_, a) => a
                )
            intercept[RuntimeException](stateful(ask.map(_ => (throw new RuntimeException("boom")): Int)).eval)
            val ex = intercept[Throwable](ask.asInstanceOf[Int < Any].eval)
            assert(ex.getMessage.contains("unhandled suspension"))
            assert(stateful(ask.map(a => ask.map(b => a * 10 + b))).eval == 1)
        }

        "a throw escaping a root eval leaves the safepoint depth unchanged" in {
            val slot = Safepoint.get()
            def depth() =
                val d = Safepoint.save(slot)
                Safepoint.restore(slot, d)
                d
            end depth
            val before = depth()
            var caught = 0
            var i      = 0
            while i < 50 do
                try discard(answerAsk(1)(ask.map(v => if v > 0 then throw new RuntimeException("boom") else v)).eval)
                catch case _: RuntimeException => caught += 1
                i += 1
            end while
            assert(caught == 50)
            assert(depth().equals(before))
        }

        "the budget rescues rather than overflowing" in {
            def loop(n: Int): Int < Any =
                if n == 0 then 0 else (n: Int < Any).map(_ => loop(n - 1))
            assert(loop(Safepoint.period() * 4).eval == 0)
        }
    }

    "partial evaluation, ported" - {
        "partial completes when nothing stops" in {
            assert(Eval.partial(answerAsk(21)(ask.map(_ * 2))).evalNow == Maybe(42))
        }

        "a computation held as a value passes through a parked slice intact" in {
            val payload: Int < Any = (3: Int < Any).map(_ + 4)
            val v: (Int < Any) < Any =
                Effect.defer {
                    requestStop()
                    ()
                }.map(_ => Kyo.lift(payload))
            val parked = Eval.partial(v)
            assert(parked.evalNow.isEmpty)
            val out = parked.eval
            assert(out.asInstanceOf[AnyRef] eq payload.asInstanceOf[AnyRef])
            assert(out.eval == 7)
        }
    }

    // A release reports the first operation under what it is tearing down, which is how a fiber's interrupt reaches
    // the join it would have awaited. These pin what it reports; that it also releases is BracketTest's.
    "release reports the first operation" - {
        val walked = new RuntimeException("walked")

        sealed trait AskSub extends Ask
        def askSub: Int < AskSub = ArrowEffect.suspend[Any](Tag[AskSub], ())

        "through a region and a handed-in deferral" in {
            val inner: Int < (Ask & Say) = ask.map(a => a)
            val idle: Int < Ask          = ArrowEffect.handleCont(Tag[Say], inner)([C] => (_, k) => k(()), a => a)
            val deferred: Int < Ask      = Effect.defer(idle, Arrow.id)
            var seen                     = 0
            Eval.release(deferred, walked, Tag[Ask])([C] => _ => seen += 1)
            assert(seen == 1)
        }

        "a foreign operation standing first is not reported" in {
            var seen = 0
            Eval.release(ask.map(_ + 1), walked, Tag[Say])([C] => _ => seen += 1)
            assert(seen == 0)
        }

        "queries in the dispatch direction" in {
            var seen = 0
            Eval.release(ask, walked, Tag[AskSub])([C] => _ => seen += 1)
            Eval.release(askSub, walked, Tag[Ask])([C] => _ => seen += 10)
            assert(seen == 1)
        }

        "a settled value reports nothing" in {
            var seen = 0
            Eval.release(42: Int < Ask, walked, Tag[Ask])([C] => _ => seen += 1)
            assert(seen == 0)
        }

        "sees through a context region" in {
            sealed trait Cfg extends ContextEffect[Int]
            val region: Int < Ask = ContextEffect.handleInheritable(Tag[Cfg], 1)(ask.map(_ + 1))
            var seen              = 0
            Eval.release(region, walked, Tag[Ask])([C] => _ => seen += 1)
            assert(seen == 1)
        }

        "sees through a parked slice" in {
            val body: Int < Ask =
                ask.map { a =>
                    discard(Safepoint.stop(Thread.currentThread()))
                    Safepoint.deadline(java.lang.System.currentTimeMillis() - 1)
                    Effect.defer(ask.map(b => a + b), Arrow.id)
                }
            val handled = ArrowEffect.handleCont(Tag[Ask], body)([C] => (_, k) => k(21), a => a)
            val parked  = Eval.partial(handled)
            var seen    = 0
            Eval.release(parked, walked, Tag[Ask])([C] => _ => seen += 1)
            assert(seen == 1)
        }

        "reads the input of a mapped suspension through its root" in {
            var seen = ""
            Eval.release(say("root").map(_ => 1).map(_ + 1), walked, Tag[Say])([X] => input => seen = input)
            assert(seen == "root")
        }

        "reads through the deferrals a map chain composes to the operation under them" in {
            var seen = ""
            Eval.release(say("shown").map(_ => 1).map(_ + 1), walked, Tag[Say])([X] => input => seen = input)
            assert(seen == "shown")
        }

        "reports nothing under an isolate capture" in {
            var seen = 0
            Eval.release(Isolate.internal.Contextual.run(ask), walked, Tag[Ask])([C] => _ => seen += 1)
            assert(seen == 0)
        }

        "peels a stateless and a stateful region node" in {
            var seen = ""
            val inner: Int < (Ask & Say) =
                ArrowEffect.handleLoopState(Tag[Ask], 0, say("deep").map(_ => ask))(
                    [X] => (state, _) => Loop.continue(state + 1, state),
                    (_, a) => a
                )
            val outer: Int < Say = ArrowEffect.handleCont(Tag[Ask], inner)([X] => (_, cont) => cont(1), a => a)
            Eval.release(outer, walked, Tag[Say])([X] => input => seen = input)
            assert(seen == "deep")
        }

        // An operation under a deferral exists only once the deferral has run, so reading it costs running
        // that body. Bounded, so the shape of a computation cannot decide how much of it a teardown runs.
        "runs a deferral to reach the operation behind it" in {
            var seen  = ""
            var built = false
            val v = Effect.defer {
                built = true
                say("hidden").map(_ => 1)
            }
            Eval.release(v, walked, Tag[Say])([X] => input => seen = input)
            assert(built)
            assert(seen == "hidden")
        }

        "stops at its budget rather than running a chain of deferrals to the end" in {
            var seen  = ""
            var built = 0
            def nest(n: Int): Int < Say =
                if n == 0 then say("deep").map(_ => 1)
                else
                    Effect.defer {
                        built += 1
                        nest(n - 1)
                    }
            Eval.release(nest(64), walked, Tag[Say])([X] => input => seen = input)
            assert(seen == "", s"reported through a chain past the budget: $seen")
            assert(built <= 16, s"ran $built deferrals")
        }
    }

end EvalTest
