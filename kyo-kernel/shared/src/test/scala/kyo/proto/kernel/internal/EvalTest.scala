package kyo.proto.kernel.internal

import kyo.Const
import kyo.Maybe
import kyo.Tag
import kyo.discard
import kyo.proto.Arrow
import kyo.proto.Loop
import kyo.proto.kernel.<
import kyo.proto.kernel.ArrowEffect
import kyo.proto.kernel.Effect
import org.scalatest.freespec.AnyFreeSpec
import scala.annotation.tailrec

class EvalTest extends AnyFreeSpec:

    private def eval[A, S](v: A < S): A =
        Nested.unnest[A](Eval(v))

    sealed trait Ask extends ArrowEffect[Const[Unit], Const[Int]]
    def ask: Int < Ask = ArrowEffect.suspend[Any](Tag[Ask], ())

    inline def askWith[B, S](inline f: Int => B < S): B < (Ask & S) = ArrowEffect.suspendWith[Any](Tag[Ask], ())(f)

    sealed trait Say extends ArrowEffect[Const[String], Const[Unit]]
    def say(s: String): Unit < Say = ArrowEffect.suspend[Any](Tag[Say], s)

    def answerAsk[A, S](value: Int)(v: A < (Ask & S)): A < S =
        ArrowEffect.handleLoop(Tag[Ask], v)([C] => _ => Loop.continue((), value: Int < Any), a => a)

    def recordSay[A, S](name: String, log: collection.mutable.ListBuffer[String])(v: A < (Say & S)): A < S =
        ArrowEffect.handleLoop(Tag[Say], v)(
            [C] =>
                _ =>
                    log += name
                    Loop.continue((), (): Unit < Any)
            ,
            a => a
        )

    def box[A](v: A): A < Any = v

    "values and map" - {
        "a settled value evaluates to itself" in {
            assert(eval(42: Int < Any) == 42)
        }

        "map runs strictly on a settled value" in {
            var ran = false
            val v = (1: Int < Any).map { n =>
                ran = true
                n + 1
            }
            assert(ran)
            assert(eval(v) == 2)
        }

        "map composes" in {
            assert(eval((1: Int < Any).map(_ + 1).map(_ * 10)) == 20)
        }

        "a long map tower evaluates in bounded stack" in {
            @tailrec def tower(v: Int < Any, n: Int): Int < Any =
                if n == 0 then v else tower(v.map(_ + 1), n - 1)
            assert(eval(tower(0, 1000000)) == 1000000)
        }

        "deep recursion through map pays rescues only" in {
            def loop(i: Int): Int < Any =
                if i == 0 then 0 else (0: Int < Any).map(_ => loop(i - 1))
            assert(eval(loop(1000000)) == 0)
        }

        "a computation held as a value round trips through the box" in {
            val inner: Int < Ask         = ask.map(_ + 1)
            val outer: (Int < Ask) < Any = box(inner)
            assert(eval(answerAsk(41)(eval(outer))) == 42)
        }

        "double nesting round trips one level per eval" in {
            val inner: Int < Ask                 = ask.map(_ + 1)
            val twice: ((Int < Ask) < Any) < Any = box(box(inner))
            assert(eval(answerAsk(41)(eval(eval(twice)))) == 42)
        }

        "a pending value does not lift into a nested computation implicitly" in {
            assertTypeError("val x: (Int < Any) < Any = (1: Int < Any).map(_ + 1)")
        }

        "an eval inside a map evaluates its argument rather than nesting it" in {

            assertTypeError("eval(answerAsk(1)(ask.map(_ => eval(ask.asInstanceOf[Int < Any]))))")
            val ex = intercept[Throwable](eval(answerAsk(1)(ask.map(_ => Eval[Int, Any](ask.asInstanceOf[Int < Any])))))
            assert(ex.getMessage.contains("unhandled suspension"))
        }

        "map receives a computation held as a value unopened" in {
            val inner: Int < Ask    = ask
            var received: Int < Ask = 0
            val r: Int < Any = box(inner).map { c =>
                received = c
                7
            }
            assert(eval(r) == 7)
            assert(eval(answerAsk(41)(received.map(_ + 1))) == 42)
        }
    }

    "handleCont" - {
        "answers with the continuation in hand" in {
            val body         = ask.map(a => ask.map(b => a + b))
            val r: Int < Any = ArrowEffect.handleCont(Tag[Ask], body)([C] => (_, cont) => cont(10), a => a)
            assert(eval(r) == 20)
        }

        "the captured continuation is multi-shot" in {
            val body = ask.map(_ * 2)
            val r: Int < Any = ArrowEffect.handleCont(Tag[Ask], body)(
                [C] => (_, cont) => cont(1).map(x => cont(2).map(y => x + y)),
                a => a
            )
            assert(eval(r) == 6)
        }

        "can end the computation without resuming" in {
            var reached = false
            val v = ask.map { a =>
                reached = true
                a + 1
            }
            val r: Int < Any = ArrowEffect.handleCont(Tag[Ask], v)([C] => (_, _) => -1, a => a)
            assert(eval(r) == -1)
            assert(!reached)
        }

        "a settled input applies done strictly" in {
            val r: Int < Any = ArrowEffect.handleCont(Tag[Ask], 41: Int < Ask)([C] => (_, cont) => cont(0), a => a + 1)
            assert(eval(r) == 42)
        }

        "done applies to the settled result" in {
            val r: Int < Any = ArrowEffect.handleCont(Tag[Ask], ask.map(_ + 1))([C] => (_, cont) => cont(41), a => a * 10)
            assert(eval(r) == 420)
        }

        "deep sequential operations are stack safe" in {
            def loop(n: Int): Int < Ask =
                if n == 0 then 0 else ask.map(_ => loop(n - 1))
            val r: Int < Any = ArrowEffect.handleCont(Tag[Ask], loop(100000))([C] => (_, cont) => cont(1), a => a)
            assert(eval(r) == 0)
        }

        "a capture crossing an inner region resumes it without re-running its body" in {
            var runs = 0
            val inner: Int < Say = answerAsk(1)(ask.map { a =>
                runs += 1
                say("x").map(_ => a + 1)
            })
            val r: Int < Any = ArrowEffect.handleCont(Tag[Say], inner)([C] => (_, cont) => cont(()), a => a)
            assert(eval(r) == 2)
            assert(runs == 1)
        }

        "each shot of a multi-shot capture resumes from capture-time state" in {
            val inner: Int < Say = ArrowEffect.handleLoopState(
                Tag[Ask],
                0,
                ask.map(a => say("x").map(_ => ask.map(b => a * 10 + b)))
            )([C] => (s, _) => Loop.continue(s + 1, s: Int < Any), (_, a) => a)
            val r: Int < Any = ArrowEffect.handleCont(Tag[Say], inner)(
                [C] => (_, cont) => cont(()).map(r1 => cont(()).map(r2 => r1 * 100 + r2)),
                a => a
            )
            assert(eval(r) == 101)
        }
    }

    "handleLoop" - {
        "answers every operation in place" in {
            def loop(i: Int): Int < Ask =
                if i < 3 then ask.map(a => loop(i + a)) else i
            assert(eval(answerAsk(1)(loop(0))) == 3)
        }

        "Loop.done stops the region and bypasses done" in {
            var reached = false
            val v = ask.map { a =>
                reached = true
                a + 1
            }
            val r: Int < Any = ArrowEffect.handleLoop(Tag[Ask], v)([C] => _ => Loop.done(-1), a => a * 10)
            assert(eval(r) == -1)
            assert(!reached)
        }

        "done sees the settled result" in {
            val r: Int < Any = ArrowEffect.handleLoop(Tag[Ask], ask.map(_ + 1))([C] => _ => Loop.continue((), 41: Int < Any), a => a * 10)
            assert(eval(r) == 420)
        }

        "a settled input applies done strictly" in {
            val r: Int < Any = ArrowEffect.handleLoop(Tag[Ask], 41: Int < Ask)([C] => _ => Loop.continue((), 0: Int < Any), a => a + 1)
            assert(eval(r) == 42)
        }

        "deep sequential operations are stack safe" in {
            def loop(n: Int): Int < Ask =
                if n == 0 then 0 else ask.map(_ => loop(n - 1))
            assert(eval(answerAsk(1)(loop(100000))) == 0)
        }

        "the innermost region of a tag answers" in {
            val inner: Int < Any = answerAsk(1)(ask)
            val outer: Int < Any = answerAsk(2)(inner: Int < Ask)
            assert(eval(outer) == 1)
        }

        "a map after the region applies to the result" in {
            assert(eval(answerAsk(41)(ask.map(_ + 1)).map(_ * 10)) == 420)
        }

        "a foreign operation crosses the region in place" in {
            val log                     = collection.mutable.ListBuffer[String]()
            val body: Int < (Ask & Say) = say("a").map(_ => ask).map(_ + 1)
            assert(eval(recordSay("outer", log)(answerAsk(41)(body))) == 42)
            assert(log.toList == List("outer"))
        }

        "a clause that suspends before its outcome runs outside its region" in {
            val log                        = collection.mutable.ListBuffer[String]()
            val program: Int < (Ask & Say) = say("m").map(_ => ask).map(_ + 1)
            val sayInner: Int < Ask        = recordSay("inner", log)(program)
            val askScope: Int < Say = ArrowEffect.handleLoop(Tag[Ask], sayInner)(
                [C] => _ => say("c").map(_ => Loop.continue((), 41: Int < Any)),
                a => a
            )
            assert(eval(recordSay("outer", log)(askScope)) == 42)
            assert(log.toList == List("inner", "outer"))
        }

        "an effectful answer runs under this handler with the interior parked" in {
            val log                        = collection.mutable.ListBuffer[String]()
            val program: Int < (Ask & Say) = say("m").map(_ => ask).map(_ + 1)
            val sayInner: Int < Ask        = recordSay("inner", log)(program)
            val askScope: Int < Say = ArrowEffect.handleLoop(Tag[Ask], sayInner)(
                [C] => _ => Loop.continue((), say("c").map(_ => 41)),
                a => a
            )
            assert(eval(recordSay("outer", log)(askScope)) == 42)
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
            assert(eval(r) == -2)
            assert(clauseRuns == 2)
        }

        "a clause suspending and then answering effectfully runs the answer under this handler" in {
            val log                        = collection.mutable.ListBuffer[String]()
            val program: Int < (Ask & Say) = say("m").map(_ => ask).map(_ + 1)
            val sayInner: Int < Ask        = recordSay("inner", log)(program)
            val askScope: Int < Say = ArrowEffect.handleLoop(Tag[Ask], sayInner)(
                [C] => _ => say("pre").map(_ => Loop.continue((), say("c").map(_ => 41))),
                a => a
            )
            assert(eval(recordSay("outer", log)(askScope)) == 42)
            assert(log.toList == List("inner", "outer", "outer"))
        }

        "an effectful answer's own-tag re-raise is answered by this handler" in {
            var clauseRuns = 0
            val r: Int < Any = ArrowEffect.handleLoop(Tag[Ask], ask.map(_ + 1))(
                [C] =>
                    _ =>
                        clauseRuns += 1
                        if clauseRuns == 1 then Loop.continue((), ask.map(_ + 100)) else Loop.continue((), 1: Int < Any)
                ,
                a => a
            )
            assert(eval(r) == 102)
            assert(clauseRuns == 2)
        }

        "a clause suspending and then answering with an own-tag re-raise is answered by this handler" in {
            var clauseRuns = 0
            val log        = collection.mutable.ListBuffer[String]()
            val handled: Int < Say = ArrowEffect.handleLoop(Tag[Ask], ask.map(_ + 1))(
                [C] =>
                    _ =>
                        clauseRuns += 1
                        if clauseRuns == 1 then say("pre").map(_ => Loop.continue((), ask.map(_ + 100)))
                        else Loop.continue((), 1: Int < Any)
                ,
                a => a
            )
            assert(eval(recordSay("outer", log)(handled)) == 102)
            assert(clauseRuns == 2)
            assert(log.toList == List("outer"))
        }

        "an effectful answer's remainder runs inside the interior region" in {
            val log                        = collection.mutable.ListBuffer[String]()
            val program: Int < (Ask & Say) = ask.map(a => say("after").map(_ => a + 1))
            val sayInner: Int < Ask        = recordSay("inner", log)(program)
            val askScope: Int < Say = ArrowEffect.handleLoop(Tag[Ask], sayInner)(
                [C] => _ => Loop.continue((), say("c").map(_ => 41)),
                a => a
            )
            assert(eval(recordSay("outer", log)(askScope)) == 42)
            assert(log.toList == List("outer", "inner"))
        }

        "an effectful answer's remainder raises the interior's effect with no outer handler for it" in {
            val log                        = collection.mutable.ListBuffer[String]()
            val program: Int < (Ask & Say) = ask.map(a => say("after").map(_ => a + 1))
            val sayInner: Int < Ask        = recordSay("inner", log)(program)

            val pendingAnswer: Int < Any = answerAsk(0)(ask.map(_ => 41))
            val askScope: Int < Any = ArrowEffect.handleLoop(Tag[Ask], sayInner)(
                [C] => _ => Loop.continue((), pendingAnswer),
                a => a
            )
            assert(eval(askScope) == 42)
            assert(log.toList == List("inner"))
        }

        "a clause suspending and then answering effectfully keeps the remainder inside the interior region" in {
            val log                        = collection.mutable.ListBuffer[String]()
            val program: Int < (Ask & Say) = ask.map(a => say("after").map(_ => a + 1))
            val sayInner: Int < Ask        = recordSay("inner", log)(program)
            val askScope: Int < Say = ArrowEffect.handleLoop(Tag[Ask], sayInner)(
                [C] => _ => say("pre").map(_ => Loop.continue((), say("c").map(_ => 41))),
                a => a
            )
            assert(eval(recordSay("outer", log)(askScope)) == 42)
            assert(log.toList == List("outer", "outer", "inner"))
        }

        "an effectful answer's fused remainder runs inside the interior region" in {
            val log                        = collection.mutable.ListBuffer[String]()
            val program: Int < (Ask & Say) = askWith(a => say("after").map(_ => a + 1))
            val sayInner: Int < Ask        = recordSay("inner", log)(program)
            val askScope: Int < Say = ArrowEffect.handleLoop(Tag[Ask], sayInner)(
                [C] => _ => Loop.continue((), say("c").map(_ => 41)),
                a => a
            )
            assert(eval(recordSay("outer", log)(askScope)) == 42)
            assert(log.toList == List("outer", "inner"))
        }

        "an effectful answer's fused remainder raises the interior's effect with no outer handler for it" in {
            val log                        = collection.mutable.ListBuffer[String]()
            val program: Int < (Ask & Say) = askWith(a => say("after").map(_ => a + 1))
            val sayInner: Int < Ask        = recordSay("inner", log)(program)
            val pendingAnswer: Int < Any   = answerAsk(0)(ask.map(_ => 41))
            val askScope: Int < Any = ArrowEffect.handleLoop(Tag[Ask], sayInner)(
                [C] => _ => Loop.continue((), pendingAnswer),
                a => a
            )
            assert(eval(askScope) == 42)
            assert(log.toList == List("inner"))
        }

        "a clause suspending and then answering effectfully keeps the fused remainder inside the interior region" in {
            val log                        = collection.mutable.ListBuffer[String]()
            val program: Int < (Ask & Say) = askWith(a => say("after").map(_ => a + 1))
            val sayInner: Int < Ask        = recordSay("inner", log)(program)
            val askScope: Int < Say = ArrowEffect.handleLoop(Tag[Ask], sayInner)(
                [C] => _ => say("pre").map(_ => Loop.continue((), say("c").map(_ => 41))),
                a => a
            )
            assert(eval(recordSay("outer", log)(askScope)) == 42)
            assert(log.toList == List("outer", "outer", "inner"))
        }

        "a computation held as a value crosses a handler as a value" in {
            val payload: Int < Say   = say("p").map(_ => 7)
            val v: (Int < Say) < Ask = ask.map(_ => box(payload))
            val handled: (Int < Say) < Any =
                ArrowEffect.handleLoop(Tag[Ask], v)([C] => _ => Loop.continue((), 0: Int < Any), a => box(a))
            var seen = ""
            val r: Int < Any = ArrowEffect.handleLoop(Tag[Say], eval(handled))(
                [C] =>
                    s =>
                        seen = s
                        Loop.continue((), (): Unit < Any)
                ,
                a => a
            )
            assert(eval(r) == 7)
            assert(seen == "p")
        }

        "an answer that is a computation held as a value stays a value" in {
            sealed trait Give extends ArrowEffect[Const[Unit], Const[Int < Ask]]
            val give: (Int < Ask) < Give = ArrowEffect.suspend[Any](Tag[Give], ())
            val inner: Int < Ask         = ask.map(_ + 1)
            val body: Int < (Give & Ask) = give.map(c => c)
            val r: Int < Any = answerAsk(41)(
                ArrowEffect.handleLoop(Tag[Give], body)([C] => _ => Loop.continue((), box(inner)), a => a)
            )
            assert(eval(r) == 42)
        }
    }

    "handleLoopState" - {
        "threads state through operations" in {
            val v = ask.map(a => ask.map(b => a * 10 + b))
            val r: Int < Any = ArrowEffect.handleLoopState(Tag[Ask], 1, v)(
                [C] => (s, _) => Loop.continue(s + 1, s: Int < Any),
                (_, a) => a
            )
            assert(eval(r) == 12)
        }

        "done observes the final state" in {
            val v = ask.map(a => ask.map(b => a + b))
            val r: (Int, Int) < Any = ArrowEffect.handleLoopState(Tag[Ask], 10, v)(
                [C] => (s, _) => Loop.continue(s + 1, s: Int < Any),
                (s, a) => (s, a)
            )
            assert(eval(r) == (12, 21))
        }

        "Loop.done bypasses done" in {
            val v = ask.map(a => ask.map(b => a + b))
            val r: String < Any = ArrowEffect.handleLoopState(Tag[Ask], 0, v)(
                [C] => (s, _) => if s == 1 then Loop.done("stopped") else Loop.continue(s + 1, 1: Int < Any),
                (s, a) => s"done $a"
            )
            assert(eval(r) == "stopped")
        }

        "state survives a foreign crossing" in {
            val body: Int < (Ask & Say) = ask.map(a => say("x").map(_ => ask.map(b => a * 10 + b)))
            val inner: Int < Say = ArrowEffect.handleLoopState(Tag[Ask], 1, body)(
                [C] => (s, _) => Loop.continue(s + 1, s: Int < Any),
                (_, a) => a
            )
            val r: Int < Any = ArrowEffect.handleLoop(Tag[Say], inner)([C] => _ => Loop.continue((), (): Unit < Any), a => a)
            assert(eval(r) == 12)
        }

        "a stateful clause that suspends threads its state through the park" in {
            val log = collection.mutable.ListBuffer[String]()
            val v   = ask.map(a => ask.map(b => a * 10 + b))
            val handled: Int < Say = ArrowEffect.handleLoopState(Tag[Ask], 1, v)(
                [C] => (s, _) => say(s"state $s").map(_ => Loop.continue(s + 1, s: Int < Any)),
                (_, a) => a
            )
            assert(eval(recordSay("outer", log)(handled)) == 12)
            assert(log.size == 2)
        }

        "a stateful effectful answer's remainder runs inside the interior region" in {
            val log                        = collection.mutable.ListBuffer[String]()
            val program: Int < (Ask & Say) = ask.map(a => say("after").map(_ => a + 1))
            val sayInner: Int < Ask        = recordSay("inner", log)(program)
            val askScope: Int < Say = ArrowEffect.handleLoopState(Tag[Ask], 40, sayInner)(
                [C] => (s, _) => Loop.continue(s + 1, say("c").map(_ => s + 1)),
                (_, a) => a
            )
            assert(eval(recordSay("outer", log)(askScope)) == 42)
            assert(log.toList == List("outer", "inner"))
        }

        "a stateful effectful answer's fused remainder runs inside the interior region" in {
            val log                        = collection.mutable.ListBuffer[String]()
            val program: Int < (Ask & Say) = askWith(a => say("after").map(_ => a + 1))
            val sayInner: Int < Ask        = recordSay("inner", log)(program)
            val askScope: Int < Say = ArrowEffect.handleLoopState(Tag[Ask], 40, sayInner)(
                [C] => (s, _) => Loop.continue(s + 1, say("c").map(_ => s + 1)),
                (_, a) => a
            )
            assert(eval(recordSay("outer", log)(askScope)) == 42)
            assert(log.toList == List("outer", "inner"))
        }
    }

    "a captured continuation is a value" - {
        def stateful(body: Int < (Ask & Say)): Int < Say =
            ArrowEffect.handleLoopState(Tag[Ask], 0, body)([C] => (s, _) => Loop.continue(s + 1, s: Int < Any), (_, a) => a)

        "resumes after its region completed, in a fresh evaluation, each shot from capture-time state" in {
            var stored: Maybe[Unit => Int < Say] = Maybe.empty
            val inner: Int < Say                 = stateful(ask.map(a => say("x").map(_ => ask.map(b => a * 10 + b))))
            val r: Int < Any = ArrowEffect.handleCont(Tag[Say], inner)(
                [C] =>
                    (_, cont) =>
                        stored = Maybe(cont(_))
                        -1
                ,
                a => a
            )
            assert(eval(r) == -1)
            val k             = stored.get
            def resume(): Int = eval(ArrowEffect.handleCont(Tag[Say], k(()))([C] => (_, cont) => cont(()), a => a))
            assert(resume() == 1)
            assert(resume() == 1)
        }

        "resumes under a later region of the same tag, which answers the remainder" in {
            var stored: Maybe[Int => Int < Ask] = Maybe.empty
            val body: Int < Ask                 = ask.map(a => ask.map(b => a * 10 + b))
            val first: Int < Any = ArrowEffect.handleCont(Tag[Ask], body)(
                [C] =>
                    (_, cont) =>
                        stored = Maybe(cont(_))
                        -1
                ,
                a => a
            )
            assert(eval(first) == -1)
            val k = stored.get
            assert(eval(answerAsk(7)(k(1))) == 17)
            assert(eval(answerAsk(8)(k(2))) == 28)
        }

        "a shot evaluated inside the clause leaves the region intact for the next" in {
            val inner: Int < Say = stateful(ask.map(a => say("x").map(_ => ask.map(b => a * 10 + b))))
            val r: Int < Any = ArrowEffect.handleCont(Tag[Say], inner)(
                [C] =>
                    (_, cont) =>
                        val now = eval(ArrowEffect.handleCont(Tag[Say], cont(()))([C] => (_, c2) => c2(()), a => a))
                        cont(()).map(later => now * 100 + later)
                ,
                a => a
            )
            assert(eval(r) == 101)
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
            assert(parked.isInstanceOf[Kyo.Park[?, ?]])
            assert(!afterRan)
            assert(parked.asInstanceOf[Kyo.Park[?, ?]].entries.length == 3)
            assert(eval(parked) == 42)
            assert(afterRan)

            assert(eval(parked) == 42)
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

            assert(Nested.unnest[Int](Eval.partial(back)) == 42)
            assert(ran)
        }

        "a stateful region parked mid-loop resumes at the parked state" in {
            val body: Int < Ask =
                ask.map { a =>
                    requestStop()
                    Effect.defer(ask.map(b => a * 10 + b))
                }
            val handled: Int < Any = ArrowEffect.handleLoopState(Tag[Ask], 1, body)(
                [C] => (s, _) => Loop.continue(s + 1, s: Int < Any),
                (_, a) => a
            )
            val parked = Eval.partial(handled)
            assert(parked.isInstanceOf[Kyo.Park[?, ?]])

            assert(parked.asInstanceOf[Kyo.Park[?, ?]].entries(1).asInstanceOf[Int] == 2)

            assert(eval(parked) == 12)
        }

        "a resumed region restores its parked binding" in {
            sealed trait Cfg extends kyo.proto.kernel.ContextEffect[Int]
            def read: Int < Cfg = kyo.proto.kernel.ContextEffect.suspend(Tag[Cfg])
            val body: (Int, Int) < Cfg =
                read.map { r1 =>
                    requestStop()
                    Effect.defer(read.map(r2 => (r1, r2)))
                }
            val handled: (Int, Int) < Any =
                kyo.proto.kernel.ContextEffect.handle(Tag[Cfg])(outer => outer.map(_ + 1).getOrElse(11))(body)
            val parked = Eval.partial(handled)
            assert(parked.isInstanceOf[Kyo.Park[?, ?]])

            val resumed = kyo.proto.kernel.ContextEffect.handle(Tag[Cfg], 100)(parked)
            assert(eval(resumed) == (11, 11))
        }

        "a nested eval inside a slice runs unarmed and completes despite the pending stop" in {
            var nested = 0
            val body: Int < Any =
                Effect.defer {
                    requestStop()
                    nested = Nested.unnest[Int](Eval(Effect.defer(Effect.defer(41)): Int < Any))
                    Effect.defer(nested + 1)
                }
            val parked = Eval.partial(body)

            assert(nested == 41)
            assert(parked.isInstanceOf[Pending[?, ?]])
            assert(!parked.isInstanceOf[Kyo.Park[?, ?]])
            assert(eval(parked) == 42)
        }

        "a throw during a slice still consumes the stop at the boundary" in {
            val body: Int < Any = Effect.defer {
                requestStop()
                Effect.defer((throw Boom): Int)
            }
            val parked = Eval.partial(body)

            assert(parked.isInstanceOf[Pending[?, ?]])
            val ex = intercept[RuntimeException](eval(parked))
            assert(ex eq Boom)

            var ran = false
            val next: Int < Any = Effect.defer {
                ran = true
                3
            }
            assert(Nested.unnest[Int](Eval.partial(next)) == 3)
            assert(ran)
        }

        // pending the release stance: Eval.release is commented out in the eval
        // "a parked value owes its regions' releases innermost first" in {
        //     val log = collection.mutable.ListBuffer[String]()
        //     val innerHandler = new Handler.HandlerCont[Const[Unit], Const[Int], Ask, Int, Int, Say]:
        //         def tag = Tag[Ask]
        //         override def release(state: Unit, ex: Throwable) =
        //             log += "inner"
        //             ()
        //         def done(state: Unit, v: Int) = v
        //         def answer[X](input: Unit, next: Arrow[Int, Int, Ask & Say]): Int < (Ask & Say) =
        //             next(1, Arrow.id)
        //     val outerHandler = new Handler.HandlerCont[Const[String], Const[Unit], Say, Int, Int, Any]:
        //         def tag = Tag[Say]
        //         override def release(state: Unit, ex: Throwable) =
        //             log += "outer"
        //             ()
        //         def done(state: Unit, v: Int) = v
        //         def answer[X](input: String, next: Arrow[Unit, Int, Say]): Int < Say =
        //             next((), Arrow.id)
        //     val body: Int < (Ask & Say) =
        //         ask.map { a =>
        //             requestStop()
        //             Effect.defer(ask.map(_ + a))
        //         }
        //     val inner: Int < Say = Kyo.handle[Ask, Int, Int, Say, Unit](body, innerHandler, ())
        //     val outer: Int < Any = Kyo.handle[Say, Int, Int, Any, Unit](inner, outerHandler, ())
        //     val parked           = Eval.partial(outer)
        //     assert(parked.isInstanceOf[Kyo.Park[?, ?]])
        //     assert(parked.asInstanceOf[Kyo.Park[?, ?]].entries.length == 6)
        //     discard(eval(Eval.release(parked)))
        //     assert(log.toList == List("inner", "outer"))
        // }

        "chain onto a parked value composes" in {
            val body: Int < Ask =
                ask.map { a =>
                    requestStop()
                    Effect.defer(ask.map(b => a + b))
                }
            val parked = Eval.partial(answerAsk(21)(body))
            assert(parked.isInstanceOf[Kyo.Park[?, ?]])
            assert(eval(parked.map(_ * 10)) == 420)
        }
    }

    "an unhandled operation is a bug" in {
        val ex = intercept[Throwable](eval(ask.asInstanceOf[Int < Any]))
        assert(ex.getMessage.contains("unhandled suspension"))
    }

    "a nested eval shares the thread's stack and sees none of the outer regions" in {
        val inner = answerAsk(5)(ask)
        val outer = answerAsk(1)(ask.map(a => a + eval(inner)))
        assert(eval(outer) == 6)
        val leak = intercept[Throwable](eval(answerAsk(1)(ask.map(_ => Eval[Int, Any](ask.asInstanceOf[Int < Any])))))
        assert(leak.getMessage.contains("unhandled suspension"))
        assert(eval(answerAsk(41)(ask.map(_ + 1))) == 42)
    }

    "an eval cleans its stack after a throw" in {
        def boom: Int < Any = (0: Int < Any).map(_ => throw new RuntimeException("boom"))
        intercept[RuntimeException](eval(answerAsk(1)(ask.map(_ => boom))))
        assert(eval(answerAsk(41)(ask.map(_ + 1))) == 42)
    }

    private object Boom extends RuntimeException("boom", null, false, false)

    "the exit law" - {
        sealed trait Count extends kyo.proto.kernel.ContextEffect[Int]
        def read: Int < Count = kyo.proto.kernel.ContextEffect.suspend(Tag[Count])

        "a context region's exit reverts its own binding to the enclosing one" in {
            val inner: Int < Count = kyo.proto.kernel.ContextEffect.handle(Tag[Count], 99)(read)
            val r                  = inner.map(a => read.map(b => (a, b)))
            assert(eval(kyo.proto.kernel.ContextEffect.handle(Tag[Count], 10)(r)) == (99, 10))
        }

        "a context region's exit removes a binding that had no enclosing one" in {
            val inner: Int < Any = kyo.proto.kernel.ContextEffect.handle(Tag[Count], 99)(read)

            val r: Int < Any = inner.map(a => kyo.proto.kernel.ContextEffect.suspend(Tag[Count], -1).map(b => a * 1000 + b))
            assert(eval(r) == 98999)
        }

    }

    "an unanswered default is taken exactly once" in {
        sealed trait Count extends kyo.proto.kernel.ContextEffect[Int]

        var evals = 0
        val r: Int < Any = kyo.proto.kernel.ContextEffect.suspend(
            Tag[Count], {
                evals += 1
                42
            }
        )
        assert(eval(r) == 42)
        assert(evals == 1)
    }

    "regions that fail and recover in sequence cost no stack" in {
        def recovering(to: Int): Int < Any =
            val body: Int < Ask = ask.map(_ => (throw Boom): Int)
            ArrowEffect.handleCont(Tag[Ask], body)([C] => (_, cont) => cont(0), a => a, _ => Maybe(to))
        def go(i: Int): Int < Any =
            if i == 0 then 0
            else recovering(i).map(_ => go(i - 1))
        assert(eval(go(10000)) == 0)
    }

    "a region recovering across a foreign crossing leaves the budget where it found it" in {
        val samples = collection.mutable.ListBuffer.empty[Safepoint.State]
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
        assert(eval(answerAsk(0)(go(100))) == 0)
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
        assert(eval(outer) == 7)

        assert(seen.exists(_ eq Inner))
    }

end EvalTest
