package kyo.kernel

import kyo.Arrow
import kyo.Const
import kyo.Maybe
import kyo.Tag
import kyo.kernel.internal.Eval
import org.scalatest.freespec.AnyFreeSpec
import scala.annotation.tailrec
import scala.collection.mutable.ListBuffer

class ArrowEffectTest extends AnyFreeSpec:

    sealed trait Ask extends ArrowEffect[Const[Unit], Const[Int]]
    def ask: Int < Ask = ArrowEffect.suspend[Any](Tag[Ask], ())

    sealed trait AskSub extends Ask
    def askSub: Int < Ask = ArrowEffect.suspend[Any](Tag[AskSub].asInstanceOf[Tag[Ask]], ())

    sealed trait Say extends ArrowEffect[Const[String], Const[Unit]]
    def say(s: String): Unit < Say = ArrowEffect.suspend[Any](Tag[Say], s)

    // an effect whose answers are themselves pending computations, for the boxed-answer lane
    sealed trait AskBoxed extends ArrowEffect[Const[Unit], [X] =>> Int < Say]
    def askBoxed: (Int < Say) < AskBoxed = ArrowEffect.suspend[Any](Tag[AskBoxed], ())

    // holds a computation as a value: the generic parameter routes through
    // the runtime lift, which boxes pending values; the direct ascription is
    // rejected by the lift discipline
    def box[A](v: A): A < Any = v

    private val Period = 512

    "handleLoop" - {
        "answers every operation in place" in {
            def loop(i: Int): Int < Ask =
                if i < 3 then ask.map(a => loop(i + a)) else i
            val r: Int < Any = ArrowEffect.handleLoop(Tag[Ask], loop(0))([C] => _ => Loop.continue(1: Int < Any), a => a)
            assert(Eval(r) == 3)
        }

        "Loop.done stops the region" in {
            var reached = false
            val v = ask.map { a =>
                reached = true
                a + 1
            }
            val r: Int < Any = ArrowEffect.handleLoop(Tag[Ask], v)([C] => _ => Loop.done(-1), a => a)
            assert(Eval(r) == -1)
            assert(!reached)
        }

        "done sees the settled result" in {
            val r: Int < Any = ArrowEffect.handleLoop(Tag[Ask], ask.map(_ + 1))([C] => _ => Loop.continue(41: Int < Any), a => a * 10)
            assert(Eval(r) == 420)
        }

        "a settled input applies done strictly" in {
            var ran = false
            val r: Int < Any = ArrowEffect.handleLoop(Tag[Ask], 41: Int < Ask)(
                [C] => _ => Loop.continue(0: Int < Any),
                a =>
                    ran = true
                    a + 1
            )
            assert(ran)
            assert(Eval(r) == 42)
        }

        "deep sequential operations are stack safe" in {
            def loop(n: Int): Int < Ask =
                if n == 0 then 0 else ask.map(_ => loop(n - 1))
            val r: Int < Any = ArrowEffect.handleLoop(Tag[Ask], loop(100000))([C] => _ => Loop.continue(1: Int < Any), a => a)
            assert(Eval(r) == 0)
        }

        "the innermost region of a tag answers" in {
            val inner: Int < Any = ArrowEffect.handleLoop(Tag[Ask], ask)([C] => _ => Loop.continue(1: Int < Any), a => a)
            val outer: Int < Any = ArrowEffect.handleLoop(Tag[Ask], inner: Int < Ask)([C] => _ => Loop.continue(2: Int < Any), a => a)
            assert(Eval(outer) == 1)
        }

        "a clause answers effectfully" in {
            var seen = List.empty[String]
            val handled: Int < Say = ArrowEffect.handleLoop(Tag[Ask], ask.map(_ + 1))(
                [C] => _ => say("consult").map(_ => Loop.continue(41: Int < Any)),
                a => a
            )
            val r: Int < Any = ArrowEffect.handleLoop(Tag[Say], handled)(
                [C] =>
                    s =>
                        seen = s :: seen
                        Loop.continue((): Unit < Any)
                ,
                a => a
            )
            assert(Eval(r) == 42)
            assert(seen == List("consult"))
        }

        "a clause ends the region effectfully" in {
            var reached   = false
            var completed = false
            var seen      = List.empty[String]
            val body = ask.map { a =>
                reached = true
                a + 1
            }
            val handled: Int < Say = ArrowEffect.handleLoop(Tag[Ask], body)(
                [C] => _ => say("stop").map(_ => Loop.done(-1)),
                a =>
                    completed = true
                    a
            )
            val r: Int < Any = ArrowEffect.handleLoop(Tag[Say], handled)(
                [C] =>
                    s =>
                        seen = s :: seen
                        Loop.continue((): Unit < Any)
                ,
                a => a
            )
            assert(Eval(r) == -1)
            assert(!reached)
            assert(!completed)
            assert(seen == List("stop"))
        }

        "every clause suspension re-arms the region" in {
            var seen = List.empty[String]
            val v    = ask.map(a => ask.map(b => a * 10 + b))
            val handled: Int < Say = ArrowEffect.handleLoop(Tag[Ask], v)(
                [C] => _ => say("x").map(_ => Loop.continue(1: Int < Any)),
                a => a
            )
            val r: Int < Any = ArrowEffect.handleLoop(Tag[Say], handled)(
                [C] =>
                    s =>
                        seen = s :: seen
                        Loop.continue((): Unit < Any)
                ,
                a => a
            )
            assert(Eval(r) == 11)
            assert(seen == List("x", "x"))
        }

        "a clause suspension resolves outside the region" in {
            var interiorSeen = List.empty[String]
            var outerSeen    = List.empty[String]
            val body: Int < Ask =
                ArrowEffect.handleLoop(Tag[Say], ask.map(_ + 1))(
                    [C] =>
                        s =>
                            interiorSeen = s :: interiorSeen
                            Loop.continue((): Unit < Any)
                    ,
                    a => a
                )
            val handled: Int < Say = ArrowEffect.handleLoop(Tag[Ask], body)(
                [C] => _ => say("clause").map(_ => Loop.continue(41: Int < Any)),
                a => a
            )
            val r: Int < Any = ArrowEffect.handleLoop(Tag[Say], handled)(
                [C] =>
                    s =>
                        outerSeen = s :: outerSeen
                        Loop.continue((): Unit < Any)
                ,
                a => a
            )
            assert(Eval(r) == 42)
            assert(interiorSeen.isEmpty)
            assert(outerSeen == List("clause"))
        }

        "a clause answer survives its own deep evaluation" in {
            def deep(i: Int): Int < Any =
                if i == 0 then 41 else (0: Int < Any).map(_ => deep(i - 1))
            val r: Int < Any = ArrowEffect.handleLoop(Tag[Ask], ask.map(_ + 1))(
                [C] => _ => deep(10000).map(n => Loop.continue(n: Int < Any)),
                a => a
            )
            assert(Eval(r) == 42)
        }

        "a clause suspends on its own effect per operation" in {
            var answered        = 0
            val body: Int < Ask = ask.map(a => ask.map(b => a * 10 + b))
            val doubled: Int < Ask = ArrowEffect.handleLoop(Tag[Ask], body)(
                [C] => _ => ask.map(v => Loop.continue(v * 2: Int < Any)),
                a => a
            )
            val r: Int < Any = ArrowEffect.handleLoop(Tag[Ask], doubled)(
                [C] =>
                    _ =>
                        answered += 1
                        Loop.continue(3: Int < Any)
                ,
                a => a
            )
            assert(Eval(r) == 66)
            assert(answered == 2)
        }

        "a suspended clause dispatch is multi-shot" in {
            val body: Int < Ask = ask.map(_ + 1)
            val handled: Int < Say = ArrowEffect.handleLoop(Tag[Ask], body)(
                [C] => _ => say("s").map(_ => Loop.continue(10: Int < Any)),
                a => a
            )
            val r: Int < Any = ArrowEffect.handleCont(Tag[Say], handled)(
                [C] => (_, cont) => cont(()).map(x => cont(()).map(y => x * 100 + y)),
                a => a
            )
            assert(Eval(r) == 1111)
        }

        "a foreign operation crosses the region in place" in {
            var order                   = List.empty[String]
            val body: Int < (Ask & Say) = say("a").map(_ => ask).map(i => i + 1)
            val inner: Int < Say        = ArrowEffect.handleLoop(Tag[Ask], body)([C] => _ => Loop.continue(41: Int < Any), a => a)
            val r: Int < Any = ArrowEffect.handleLoop(Tag[Say], inner)(
                [C] =>
                    s =>
                        order = s :: order
                        Loop.continue((): Unit < Any)
                ,
                a => a
            )
            assert(Eval(r) == 42)
            assert(order == List("a"))
        }

        "an effectful answer resolves through an outer handler" in {
            val v: Int < Ask = ask.map(_ + 1)
            val looped: Int < Say =
                ArrowEffect.handleLoop(Tag[Ask], v)([C] => _ => Loop.continue(say("fetch").map(_ => 41)), a => a)
            val r: Int < Any = ArrowEffect.handleCont(Tag[Say], looped)([C] => (_, cont) => cont(()), a => a)
            assert(Eval(r) == 42)
        }

        "a clause may suspend before producing its outcome" in {
            var logged = 0
            val looped: Int < Say = ArrowEffect.handleLoop(Tag[Ask], ask.map(_ + 1))(
                [C] =>
                    _ =>
                        say("pre").map { _ =>
                            logged += 1
                            Loop.continue(41: Int < Any)
                    },
                a => a
            )
            val r: Int < Any = ArrowEffect.handleCont(Tag[Say], looped)([C] => (_, cont) => cont(()), a => a)
            assert(Eval(r) == 42)
            assert(logged == 1)
        }

        "done climbs past an inner handler without running its remainder" in {
            var innerExit = false
            var reached   = false
            val program: Int < (Ask & Say) = say("m").map(_ => ask).map { a =>
                reached = true
                a + 1
            }
            val sayHandled: Int < Ask =
                ArrowEffect.handleCont(Tag[Say], program)([C] => (_, cont) => cont(()), a => a).map { v =>
                    innerExit = true
                    v
                }
            val r: Int < Any = ArrowEffect.handleLoop(Tag[Ask], sayHandled)([C] => _ => Loop.done(-1), a => a)
            assert(Eval(r) == -1)
            assert(!reached)
            assert(!innerExit)
        }

        "handles nested per recursion step in bounded stack" in {
            def go(n: Int): Int < Any =
                if n == 0 then 0
                else ArrowEffect.handleLoop(Tag[Ask], ask.map(_ => go(n - 1)))([C] => _ => Loop.continue(1: Int < Any), a => a)
            assert(Eval(go(100000)) == 0)
        }

        "context effects arise from answering handlers" in {
            def provide[A, S](value: Int)(v: A < (Ask & S)): A < S =
                ArrowEffect.handleLoop(Tag[Ask], v)([C] => _ => Loop.continue(value: Int < Any), a => a)
            assert(Eval(provide(42)(ask.map(_ + 1))) == 43)
            assert(Eval(provide(1)(provide(2)(ask))) == 2)
        }

        "stays in force across a foreign crossing captured by an outer handle" in {
            val v: Int < (Ask & Say) = say("x").map(_ => ask).map(_ + 1)
            val looped: Int < Say    = ArrowEffect.handleLoop(Tag[Ask], v)([C] => _ => Loop.continue(41: Int < Any), a => a)
            val r: Int < Any         = ArrowEffect.handleCont(Tag[Say], looped)([C] => (_, cont) => cont(()), a => a)
            assert(Eval(r) == 42)
        }

        "the innermost done wins under nested same-tag handlers" in {
            var outerReached     = false
            val inner: Int < Ask = ArrowEffect.handleLoop(Tag[Ask], ask.map(_ + 1))([C] => _ => Loop.done(-1), a => a)
            val outer: Int < Any = ArrowEffect.handleLoop(Tag[Ask], inner)(
                [C] =>
                    _ =>
                        outerReached = true
                        Loop.done(-2)
                ,
                a => a
            )
            assert(Eval(outer) == -1)
            assert(!outerReached)
        }
    }

    "handle" - {
        "answers with the continuation in hand" in {
            val body         = ask.map(a => ask.map(b => a + b))
            val r: Int < Any = ArrowEffect.handleCont(Tag[Ask], body)([C] => (_, cont) => cont(10), a => a)
            assert(Eval(r) == 20)
        }

        "the captured continuation is multi-shot" in {
            val body = ask.map(_ * 2)
            val r: Int < Any = ArrowEffect.handleCont(Tag[Ask], body)(
                [C] => (_, cont) => cont(1).map(x => cont(2).map(y => x + y)),
                a => a
            )
            assert(Eval(r) == 6)
        }

        "can end the computation without resuming" in {
            var reached = false
            val v = ask.map { a =>
                reached = true
                a + 1
            }
            val r: Int < Any = ArrowEffect.handleCont(Tag[Ask], v)([C] => (_, _) => -1, a => a)
            assert(Eval(r) == -1)
            assert(!reached)
        }

        "a settled input applies done strictly" in {
            var ran = false
            val r: Int < Any = ArrowEffect.handleCont(Tag[Ask], 41: Int < Ask)(
                [C] => (_, cont) => cont(0),
                a =>
                    ran = true
                    a + 1
            )
            assert(ran)
            assert(Eval(r) == 42)
        }

        "done applies to the settled result" in {
            val r: Int < Any = ArrowEffect.handleCont(Tag[Ask], ask.map(_ + 1))([C] => (_, cont) => cont(41), a => a * 10)
            assert(Eval(r) == 420)
        }

        "deep sequential operations are stack safe" in {
            def loop(n: Int): Int < Ask =
                if n == 0 then 0 else ask.map(_ => loop(n - 1))
            val r: Int < Any = ArrowEffect.handleCont(Tag[Ask], loop(100000))([C] => (_, cont) => cont(1), a => a)
            assert(Eval(r) == 0)
        }

        "answers a single operation" in {
            val v            = ask.map(_ + 1)
            val r: Int < Any = ArrowEffect.handleCont(Tag[Ask], v)([C] => (_, cont) => cont(41), a => a)
            assert(Eval(r) == 42)
        }

        "lazy: the handled computation is a value and answers at eval" in {
            var ran = false
            val v = ask.map { a =>
                ran = true
                a + 1
            }
            val r: Int < Any = ArrowEffect.handleCont(Tag[Ask], v)([C] => (_, cont) => cont(41), a => a)
            assert(!ran)
            assert(Eval(r) == 42)
            assert(ran)
        }

        "a long map tower on a pending suspension handles in bounded stack" in {
            @tailrec def tower(v: Int < Ask, n: Int): Int < Ask =
                if n == 0 then v else tower(v.map(_ + 1), n - 1)
            val v            = tower(ask, 1000000)
            val r: Int < Any = ArrowEffect.handleCont(Tag[Ask], v)([C] => (_, cont) => cont(0), a => a)
            assert(Eval(r) == 1000000)
        }

        "stays in force across a foreign crossing with a trailing transform" in {
            val v: Int < (Ask & Say) = say("x").map(_ => ask).map(_ + 1)
            val r: Int < Any = ArrowEffect.handleCont(
                Tag[Say],
                ArrowEffect.handleCont(Tag[Ask], v)([C] => (_, cont) => cont(41), a => a)
            )([C] => (_, cont) => cont(()), a => a)
            assert(Eval(r) == 42)
        }

        "stays in force across a budget bounce with a trailing transform" in {
            def burn(n: Int): Int < Any =
                if n == 0 then
                    val v: Int < Ask = (0: Int < Any).map(_ => ask).map(_ + 1)
                    ArrowEffect.handleCont(Tag[Ask], v)([C] => (_, cont) => cont(41), a => a)
                else ((): Unit < Any).map(_ => burn(n - 1))
            assert(Eval(burn(512)) == 42)
        }

        "handler state threads through resumptions" in {
            var count = 0
            val v     = ask.map(a => ask.map(b => a + b))
            val r: Int < Any = ArrowEffect.handleCont(Tag[Ask], v)(
                [C] =>
                    (_, cont) =>
                        count += 1
                        cont(count * 10)
                ,
                a => a
            )
            assert(Eval(r) == 30)
            assert(count == 2)
        }

        "a foreign operation passes through and keeps the handler attached" in {
            val v: Int < (Ask & Say)  = ask.map(a => say(a.toString).map(_ => ask.map(b => a + b)))
            val handledAsk: Int < Say = ArrowEffect.handleCont(Tag[Ask], v)([C] => (_, cont) => cont(21), a => a)
            val r: Int < Any          = ArrowEffect.handleCont(Tag[Say], handledAsk)([C] => (_, cont) => cont(()), a => a)
            assert(Eval(r) == 42)
        }

        "nested handlers answer their own operations" in {
            val v: Int < (Ask & Say) =
                ask.map { a =>
                    say(a.toString).map(_ => a * 2)
                }
            val r: Int < Any = ArrowEffect.handleCont(
                Tag[Say],
                ArrowEffect.handleCont(Tag[Ask], v)([C] => (_, cont) => cont(21), a => a)
            )([C] => (s, cont) => cont(()), a => a)
            assert(Eval(r) == 42)
        }

        "answers operations of a subtype effect" in {
            val v: Int < Ask = askSub.map(_ + 1)
            val r: Int < Any = ArrowEffect.handleCont(Tag[Ask], v)([C] => (_, cont) => cont(41), a => a)
            assert(Eval(r) == 42)
        }

        // Waiting on partial evaluation, which lands with the Bracket and Park work
        // (see reviews/BRACKET-PARK-DESIGN.md).
        //
        // "installed after a partial evaluation answers the parked operation" in {
        //     val v            = ask.map(_ + 1)
        //     val parked       = Eval.partial(v)
        //     val r: Int < Any = ArrowEffect.handleCont(Tag[Ask], parked)([C] => (_, cont) => cont(41), a => a)
        //     assert(Eval(r) == 42)
        // }

        "a map chained after the region applies to the result" in {
            val v            = ask.map(_ + 1)
            val r: Int < Any = ArrowEffect.handleCont(Tag[Ask], v)([C] => (_, cont) => cont(41), a => a).map(_ * 10)
            assert(Eval(r) == 420)
        }

        "a map chained after the region runs outside the scope" in {
            val v                = ask.map(_ + 1)
            val r: Int < Ask     = ArrowEffect.handleCont(Tag[Ask], v)([C] => (_, cont) => cont(41), a => a).map(a => ask.map(_ + a))
            val outer: Int < Any = ArrowEffect.handleCont(Tag[Ask], r)([C] => (_, cont) => cont(1000), a => a)
            assert(Eval(outer) == 1042)
        }

        "a map chained after a settled pass-through applies strictly" in {
            var ran = false
            val r: Int < Any = ArrowEffect.handleCont(Tag[Ask], box(42))([C] => (_, cont) => cont(0), a => a).map { a =>
                ran = true
                a + 1
            }
            assert(ran)
            assert(Eval(r) == 43)
        }
    }

    "handleLoopState" - {
        "threads state through operations" in {
            val v = ask.map(a => ask.map(b => a * 10 + b))
            val r: Int < Any = ArrowEffect.handleLoopState(Tag[Ask], 1, v)(
                [C] => (s, _) => Loop.continue(s + 1, s: Int < Any),
                (_, a) => a
            )
            assert(Eval(r) == 12)
        }

        "done observes the final state" in {
            val v = ask.map(a => ask.map(b => a + b))
            val r: (Int, Int) < Any = ArrowEffect.handleLoopState(Tag[Ask], 10, v)(
                [C] => (s, _) => Loop.continue(s + 1, s: Int < Any),
                (s, a) => (s, a)
            )
            assert(Eval(r) == (12, 21))
        }

        "Loop.done bypasses done" in {
            val v = ask.map(a => ask.map(b => a + b))
            val r: String < Any = ArrowEffect.handleLoopState(Tag[Ask], 0, v)(
                [C] => (s, _) => if s == 1 then Loop.done("stopped") else Loop.continue(s + 1, 1: Int < Any),
                (s, a) => s"done $a"
            )
            assert(Eval(r) == "stopped")
        }

        "a stateful clause answers effectfully" in {
            var seen = List.empty[String]
            val v    = ask.map(a => ask.map(b => a * 10 + b))
            val handled: Int < Say = ArrowEffect.handleLoopState(Tag[Ask], 1, v)(
                [C] => (s, _) => say(s"state $s").map(_ => Loop.continue(s + 1, s: Int < Any)),
                (_, a) => a
            )
            val r: Int < Any = ArrowEffect.handleLoop(Tag[Say], handled)(
                [C] =>
                    s =>
                        seen = s :: seen
                        Loop.continue((): Unit < Any)
                ,
                a => a
            )
            assert(Eval(r) == 12)
            assert(seen == List("state 2", "state 1"))
        }

        "state survives a foreign crossing" in {
            val body: Int < (Ask & Say) = ask.map(a => say("x").map(_ => ask.map(b => a * 10 + b)))
            val inner: Int < Say = ArrowEffect.handleLoopState(Tag[Ask], 1, body)(
                [C] => (s, _) => Loop.continue(s + 1, s: Int < Any),
                (_, a) => a
            )
            val r: Int < Any = ArrowEffect.handleLoop(Tag[Say], inner)([C] => _ => Loop.continue((): Unit < Any), a => a)
            assert(Eval(r) == 12)
        }

        "a settled input applies done strictly with the initial state" in {
            val r: Int < Any = ArrowEffect.handleLoopState(Tag[Ask], 7, 35: Int < Ask)(
                [C] => (s, _) => Loop.continue(s, 0: Int < Any),
                (s, a) => s + a
            )
            assert(Eval(r) == 42)
        }

        "state composes with done" in {
            def go(n: Int): Int < Ask =
                if n == 0 then 0 else ask.map(_ => go(n - 1))
            val r: Int < Any = ArrowEffect.handleLoopState(Tag[Ask], 3, go(5))(
                [C] => (remaining, _) => if remaining > 0 then Loop.continue(remaining - 1, 1: Int < Any) else Loop.done(-1),
                (_, a) => a
            )
            assert(Eval(r) == -1)
        }

        "state survives an inner handler's exit" in {
            val program: Int < (Ask & Say) = ask.map(a => say("x").map(_ => a))
            val sayHandled: Int < Ask      = ArrowEffect.handleCont(Tag[Say], program)([C] => (_, cont) => cont(()), a => a)
            val v: Int < Ask               = sayHandled.map(a => ask.map(b => a * 10 + b))
            val r: Int < Any = ArrowEffect.handleLoopState(Tag[Ask], 1, v)(
                [C] => (state, _) => Loop.continue(state + 1, state: Int < Any),
                (_, a) => a
            )
            assert(Eval(r) == 12)
        }

        "done sees the final answer" in {
            val v = ask.map(a => ask.map(b => a + b))
            val r: Int < Any = ArrowEffect.handleLoopState(Tag[Ask], 10, v)(
                [C] => (s, _) => Loop.continue(s + 1, s: Int < Any),
                (_, a) => a * 2
            )
            assert(Eval(r) == 42)
        }

        "done may be effectful" in {
            val r: Int < Say = ArrowEffect.handleLoopState(Tag[Ask], 5, ask)(
                [C] => (s, _) => Loop.continue(s, s: Int < Any),
                (s, a) => say("bye").map(_ => s + a)
            )
            val out: Int < Any = ArrowEffect.handleCont(Tag[Say], r)([C] => (_, cont) => cont(()), a => a)
            assert(Eval(out) == 10)
        }

        "the overload without done completes with the result and discards the state" in {
            val v = ask.map(a => ask.map(b => a + b))
            val r = ArrowEffect.handleLoopState(Tag[Ask], 10, v)([C] => (s, _) => Loop.continue(s + 1, s: Int < Any))
            assert(Eval(r) == 21)
        }

        // Waiting on partial evaluation, which lands with the Bracket and Park work
        // (see reviews/BRACKET-PARK-DESIGN.md).
        //
        // "a parked stateful region resumes with its state and done" in {
        //     val v: Int < (Ask & Say) = ask.map(_ => say("x")).map(_ => ask)
        //     val handled: Int < Say = ArrowEffect.handleLoopState(Tag[Ask], 10, v)(
        //         [C] => (s, _) => Loop.continue(s + 1, s: Int < Any),
        //         (_, a) => a * 2
        //     )
        //     val parked = Eval.partial(handled)
        //     assert(parked.evalNow.isEmpty)
        //     val out: Int < Any = ArrowEffect.handleCont(Tag[Say], parked)([C] => (_, cont) => cont(()), a => a)
        //     assert(Eval(out) == 22)
        // }
    }

    "handleWith" - {
        "applies the continuation to the region result" in {
            val r: Int < Any = ArrowEffect.handleContWith(Tag[Ask], ask.map(_ + 1))(
                [C] => (_, cont) => cont(20),
                a => a * 2
            )(b => b + 100)
            assert(Eval(r) == 142)
        }

        "applies the continuation to a settled input" in {
            val r: Int < Any = ArrowEffect.handleContWith(Tag[Ask], 5: Int < Ask)(
                [C] => (_, cont) => cont(0),
                a => a + 1
            )(b => b * 10)
            assert(Eval(r) == 60)
        }

        "the continuation can suspend on an outer effect" in {
            var seen = ""
            val inner: Int < Say =
                ArrowEffect.handleContWith(Tag[Ask], ask)([C] => (_, cont) => cont(1), a => a)(b => say("s").map(_ => b + 1))
            val r: Int < Any = ArrowEffect.handleLoop(Tag[Say], inner)(
                [C] =>
                    s =>
                        seen = s
                        Loop.continue((): Unit < Any)
                ,
                a => a
            )
            assert(Eval(r) == 2)
            assert(seen == "s")
        }

        "deep sequential operations stay stack safe through the continuation" in {
            def loop(n: Int): Int < Ask =
                if n == 0 then 0 else ask.map(_ => loop(n - 1))
            val r: Int < Any = ArrowEffect.handleContWith(Tag[Ask], loop(100000))([C] => (_, cont) => cont(1), a => a)(b => b + 7)
            assert(Eval(r) == 7)
        }
    }

    "handleLoopWith" - {
        "applies the continuation to the region result" in {
            val r: Int < Any = ArrowEffect.handleLoopWith(Tag[Ask], ask.map(_ + 1))(
                [C] => _ => Loop.continue(41: Int < Any),
                a => a * 10
            )(b => b + 1)
            assert(Eval(r) == 421)
        }

        "applies the continuation to a settled input" in {
            val r: Int < Any = ArrowEffect.handleLoopWith(Tag[Ask], 41: Int < Ask)(
                [C] => _ => Loop.continue(0: Int < Any),
                a => a + 1
            )(b => b * 10)
            assert(Eval(r) == 420)
        }

        "Loop.done flows through the continuation" in {
            val r: Int < Any = ArrowEffect.handleLoopWith(Tag[Ask], ask.map(_ + 1))(
                [C] => _ => Loop.done(-1),
                a => a
            )(b => b * 2)
            assert(Eval(r) == -2)
        }
    }

    "handleLoopStateWith" - {
        "applies the continuation with the final state observed" in {
            val v = ask.map(a => ask.map(b => a * 10 + b))
            val r: Int < Any = ArrowEffect.handleLoopStateWith(Tag[Ask], 1, v)(
                [C] => (s, _) => Loop.continue(s + 1, s: Int < Any),
                (s, a) => s * 100 + a
            )(b => b + 1)
            assert(Eval(r) == 313)
        }

        "applies the continuation to a settled input" in {
            val r: Int < Any = ArrowEffect.handleLoopStateWith(Tag[Ask], 7, 35: Int < Ask)(
                [C] => (s, _) => Loop.continue(s, 0: Int < Any),
                (s, a) => s + a
            )(b => b * 2)
            assert(Eval(r) == 84)
        }
    }

    "suspendWith" - {
        "suspends and continues in one node" in {
            val v: Int < Ask = ArrowEffect.suspendWith[Any](Tag[Ask], ())(_ + 1)
            val r: Int < Any = ArrowEffect.handleLoop(Tag[Ask], v)([C] => _ => Loop.continue(41: Int < Any), a => a)
            assert(Eval(r) == 42)
        }

        "deep recursion is stack safe" in {
            def loop(i: Int): Int < Ask =
                if i > 100000 then i
                else ArrowEffect.suspendWith[Any](Tag[Ask], ())(a => loop(i + a))
            val r: Int < Any = ArrowEffect.handleLoop(Tag[Ask], loop(0))([C] => _ => Loop.continue(1: Int < Any), a => a)
            assert(Eval(r) == 100001)
        }

        "maps chain onto the node" in {
            val v            = ArrowEffect.suspendWith[Any](Tag[Ask], ())(_ + 1).map(_ * 2)
            val r: Int < Any = ArrowEffect.handleCont(Tag[Ask], v)([C] => (_, cont) => cont(20), a => a)
            assert(Eval(r) == 42)
        }

        "an effectful continuation suspends again" in {
            val v            = ArrowEffect.suspendWith[Any](Tag[Ask], ())(a => ask.map(b => a + b))
            val r: Int < Any = ArrowEffect.handleCont(Tag[Ask], v)([C] => (_, cont) => cont(21), a => a)
            assert(Eval(r) == 42)
        }

        "a long map tower on the node evaluates in bounded stack" in {
            @tailrec def tower(v: Int < Ask, n: Int): Int < Ask =
                if n == 0 then v else tower(v.map(_ + 1), n - 1)
            val v            = tower(ArrowEffect.suspendWith[Any](Tag[Ask], ())(_ + 1), 1000000)
            val r: Int < Any = ArrowEffect.handleCont(Tag[Ask], v)([C] => (_, cont) => cont(0), a => a)
            assert(Eval(r) == 1000001)
        }
    }

    "a handle capture crossing an inner region" in {
        val inner: Int < Say = ArrowEffect.handleLoop(
            Tag[Ask],
            ask.map(a => say("x").map(_ => ask.map(b => a + b)))
        )([C] => _ => Loop.continue(1: Int < Any), a => a)
        val r: Int < Any = ArrowEffect.handleCont(Tag[Say], inner)([C] => (_, cont) => cont(()), a => a)
        assert(Eval(r) == 2)
    }

    "a crossed region resumes without re-running its body" in {
        var runs = 0
        val inner: Int < Say = ArrowEffect.handleLoop(
            Tag[Ask],
            ask.map { a =>
                runs += 1
                if runs > 1 then throw new IllegalStateException("body re-ran")
                say("x").map(_ => a + 1)
            }
        )([C] => _ => Loop.continue(1: Int < Any), a => a)
        val r: Int < Any = ArrowEffect.handleCont(Tag[Say], inner)([C] => (_, cont) => cont(()), a => a)
        assert(Eval(r) == 2)
        assert(runs == 1)
    }

    "a crossed stateful region resumes with its in-flight state" in {
        val inner: Int < Say = ArrowEffect.handleLoopState(
            Tag[Ask],
            10,
            ask.map(a => say("x").map(_ => ask.map(b => a * 100 + b)))
        )([C] => (s, _) => Loop.continue(s + 1, s: Int < Any), (_, a) => a)
        val r: Int < Any = ArrowEffect.handleCont(Tag[Say], inner)([C] => (_, cont) => cont(()), a => a)
        assert(Eval(r) == 1011)
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
        assert(Eval(r) == 101)
    }

    "a map after the region applies to the result" in {
        val r: Int < Any = ArrowEffect.handleLoop(Tag[Ask], ask.map(_ + 1))([C] => _ => Loop.continue(41: Int < Any), a => a)
        assert(Eval(r.map(_ * 10)) == 420)
    }

    "an unresumed handler skips trailing maps at any depth" in {
        for depth <- List(4, 64) do
            val runs         = new Array[Int](depth)
            var v: Int < Ask = ask
            for i <- 0 until depth do
                val j = i
                v = v.map { x =>
                    runs(j) += 1
                    x + 1
                }
            end for
            val r: Int < Any = ArrowEffect.handleCont(Tag[Ask], v)([C] => (_, _) => 999, a => a * 2)
            assert(Eval(r) == 1998)
            assert(runs.forall(_ == 0))
    }

    "state survives dumping above a live region" in {
        def tower(v: Int < Ask, n: Int): Int < Ask =
            if n == 0 then v else tower(v.map(_ + 1), n - 1)
        val body = ask.map(a => tower(ask.map(b => a + b), 64))
        val r: Int < Any = ArrowEffect.handleLoopState(Tag[Ask], 10, body)(
            [C] => (st, _) => Loop.continue(st + 1, st: Int < Any),
            (st, a) => a * 1000 + st
        )
        assert(Eval(r) == 85012)
    }

    "park" - {

        "a clause parks by returning and resumes by rewrapping the continuation" in {
            var ready: Maybe[Int]                  = Maybe.Absent
            var stash: Maybe[Arrow[Int, Int, Ask]] = Maybe.Absent
            var polls                              = 0
            def boundary(v: Int < Ask): Int < Any =
                ArrowEffect.handleCont(Tag[Ask], v)(
                    [C] =>
                        (_, cont) =>
                            polls += 1
                            ready match
                                case Maybe.Present(r) =>
                                    ready = Maybe.Absent
                                    cont(r)
                                case Maybe.Absent =>
                                    stash = Maybe(cont)
                                    -1
                            end match
                    ,
                    a => a
                )
            val body = ask.map(a => ask.map(b => ask.map(c => a * 100 + b * 10 + c)))

            ready = Maybe(1)
            assert(Eval(boundary(body)) == -1)
            assert(polls == 2)

            assert(Eval(boundary(stash.get.apply(2))) == -1)
            assert(polls == 3)

            assert(Eval(boundary(stash.get.apply(3))) == 123)
            assert(polls == 3)
        }

        "a park preserves standing sibling regions" in {
            var seen                               = List.empty[String]
            var stash: Maybe[Arrow[Int, Int, Ask]] = Maybe.Absent
            def boundary(v: Int < Ask): Int < Any =
                ArrowEffect.handleCont(Tag[Ask], v)(
                    [C] =>
                        (_, cont) =>
                            stash = Maybe(cont)
                            -1
                    ,
                    a => a
                )
            val body: Int < (Ask & Say) = say("before").map(_ => ask.map(a => say("after").map(_ => a + 1)))
            val inner: Int < Ask = ArrowEffect.handleLoop(Tag[Say], body)(
                [C] =>
                    s =>
                        seen = s :: seen
                        Loop.continue((): Unit < Any)
                ,
                a => a
            )
            assert(Eval(boundary(inner)) == -1)
            assert(seen == List("before"))
            assert(Eval(boundary(stash.get.apply(41))) == 42)
            assert(seen == List("after", "before"))
        }

        "a union tag subsumes both effects the way regions are found" in {
            assert(Tag[Ask].erased <:< Tag[Ask | Say].erased)
            assert(Tag[Say].erased <:< Tag[Ask | Say].erased)
            assert(!(Tag[Ask | Say].erased <:< Tag[Ask].erased))
            assert(!(Tag[Say].erased <:< Tag[Ask].erased))
        }
    }

    // Answering only the first operation of a tag, with no handler kind of its own. The region's currency
    // carries both outcomes, so the clause's answer is an ordinary settled value of it, and a settled value
    // is what ends a region: the entry pops, and the continuation the clause kept still carries the effect
    // for someone else to answer.
    enum First derives CanEqual:
        case Done(value: Int)
        case Standing(cont: Arrow[Int, First, Ask])

    "answering only the first operation" - {

        def firstOf(v: Int < Ask): First < Any =
            ArrowEffect.handleCont(Tag[Ask], v.map(a => First.Done(a): First))(
                [C] => (_, cont) => First.Standing(cont),
                a => a
            )

        "answers the first and leaves the rest unhandled" in {
            val v = ask.map(a => ask.map(b => a * 10 + b))
            Eval(firstOf(v)) match
                case First.Standing(cont) =>
                    // annotated: `cont(4)` with no expected type resolves to Arrow's two-argument apply,
                    // since a raw value inhabits the pending type's first arm
                    val rest: First < Ask = cont(4)
                    assert(Eval(ArrowEffect.handleCont(Tag[Ask], rest)([X] => (_, k) => k(2), a => a)) == First.Done(42))
                case other => fail(s"expected a standing operation, got $other")
            end match
        }

        "the clause may end the computation without resuming" in {
            var reached = false
            val v = ask.map { a =>
                reached = true
                a + 1
            }
            val r = ArrowEffect.handleCont(Tag[Ask], v.map(a => First.Done(a): First))([C] => (_, _) => First.Done(-1), a => a)
            assert(Eval(r) == First.Done(-1))
            assert(!reached)
        }

        "the continuation is resumable more than once" in {
            var runs = 0
            val v = ask.map { a =>
                runs += 1
                a * 10
            }
            Eval(firstOf(v)) match
                case First.Standing(cont) =>
                    val one: First < Ask = cont(1)
                    val two: First < Ask = cont(2)
                    assert(Eval(ArrowEffect.handleCont(Tag[Ask], one)([X] => (_, k) => k(0), a => a)) == First.Done(10))
                    assert(Eval(ArrowEffect.handleCont(Tag[Ask], two)([X] => (_, k) => k(0), a => a)) == First.Done(20))
                    assert(runs == 2)
                case other => fail(s"expected a standing operation, got $other")
            end match
        }

        // the clause answers at the region's own currency, so an operation it raises comes back to the same
        // region rather than to the one outside. This is where the shape parts company with a handler whose
        // clause sits outside the region it serves, and a clause that raises its own tag unguarded never ends
        "an operation the clause raises re-enters the same region" in {
            var clauseRuns = 0
            val r: First < Any = ArrowEffect.handleCont(Tag[Ask], ask.map(a => First.Done(a): First))(
                [C] =>
                    (_, cont) =>
                        clauseRuns += 1
                        // the re-entry is dispatched here too, and what it receives is the continuation of
                        // the clause's own operation, so resuming it delivers the answer to `extra`
                        if clauseRuns == 1 then ask.map(extra => First.Done(extra * 10): First)
                        else cont(7)
                ,
                a => a
            )
            assert(Eval(r) == First.Done(70))
            assert(clauseRuns == 2)
        }

        // which is why the effectful half of a first-operation clause belongs after the region: by then the
        // entry has popped, so the raise reaches whoever is installed outside
        "an operation raised after the region reaches the outer handler" in {
            var outer = 0
            val captured: First < Ask =
                firstOf(ask.map(_ + 1)).map:
                    case First.Standing(_) => ask.map(extra => First.Done(extra * 10): First)
                    case done              => done
            val out = ArrowEffect.handleCont(Tag[Ask], captured)(
                [X] =>
                    (_, k) =>
                        outer += 1
                        k(4)
                ,
                a => a
            )
            assert(Eval(out) == First.Done(40))
            assert(outer == 1)
        }

        "a settled body takes the done clause" in {
            assert(Eval(firstOf(41)) == First.Done(41))
        }

        "deep sequential operations are stack safe" in {
            def loop(n: Int): Int < Ask =
                if n == 0 then 0 else ask.map(_ => loop(n - 1))
            Eval(firstOf(loop(100000))) match
                case First.Standing(cont) =>
                    val rest: First < Ask = cont(1)
                    assert(Eval(ArrowEffect.handleCont(Tag[Ask], rest)([X] => (_, k) => k(1), a => a)) == First.Done(0))
                case other => fail(s"expected a standing operation, got $other")
            end match
        }
    }

    // A first-operation region needs a clause that receives the continuation and
    // ends the region with its own result type. handleCont keeps the region
    // installed and handleLoopState answers with a value, so the old helper
    // (built on a stateful handleLoop whose clause received the continuation)
    // has no primitive to stand on here.
    /*
    "handleFirst" - {
        def handleFirst[I[_], O[_], E <: ArrowEffect[I, O], A, B, S, S2](effectTag: Tag[E], v: A < (E & S))(
            handle: [X] => (I[X], O[X] => A < (E & S)) => B < S2,
            done: A => B < S2
        ): B < (S & S2) =
            ArrowEffect.handleLoop[I, O, E, A, B, S, S2, Unit](effectTag, (), v)(
                done = (_, a) => done(a),
                handle = [X] => (input, _, cont) => handle[X](input, cont).map(Loop.done(_))
            )

        "answers the first operation and leaves the rest unhandled" in {
            var answered = 0
            val v        = ask.map(a => ask.map(b => a * 10 + b))
            val first = handleFirst(Tag[Ask], v)(
                [X] =>
                    (_, cont) =>
                        answered += 1
                        cont(4)
                ,
                identity
            )
            val r = ArrowEffect.handleCont(Tag[Ask], first)([X] => (_, cont) => cont(2), a => a)
            assert(Eval(r) == 42)
            assert(answered == 1)
        }

        "the clause may end the computation without resuming" in {
            var reached = false
            val v = ask.map { a =>
                reached = true
                a + 1
            }
            val r = handleFirst(Tag[Ask], v)([X] => (_, _) => -1, identity)
            assert(Eval(r) == -1)
            assert(!reached)
        }

        "the continuation is handed out as a value and resumed later" in {
            val v = ask.map(a => ask.map(b => a * 10 + b))
            val r = handleFirst(Tag[Ask], v)(
                [X] => (_, cont) => (1, () => cont(4)),
                a => (0, () => (a: Int < Ask))
            )
            val (answered, rest) = Eval(r)
            assert(answered == 1)
            assert(Eval(ArrowEffect.handleCont(Tag[Ask], rest())([X] => (_, cont) => cont(2), a => a)) == 42)
        }

        "the continuation is multi-shot" in {
            var runs = 0
            val v = ask.map { a =>
                runs += 1
                a * 10
            }
            val first = handleFirst(Tag[Ask], v)(
                [X] => (_, cont) => cont(1).map(a => cont(2).map(b => a + b)),
                identity
            )
            assert(Eval(ArrowEffect.handleCont(Tag[Ask], first)([X] => (_, cont) => cont(0), a => a)) == 30)
            assert(runs == 2)
        }

        "an operation raised by the clause reaches the outer handler" in {
            var outerAnswered = 0
            val first = handleFirst(Tag[Ask], ask.map(_ + 1))(
                [X] => (_, cont) => ask.map(extra => cont(extra * 10)),
                identity
            )
            val r = ArrowEffect.handleCont(Tag[Ask], first)(
                [X] =>
                    (_, cont) =>
                        outerAnswered += 1
                        cont(4)
                ,
                a => a
            )
            assert(Eval(r) == 41)
            assert(outerAnswered == 1)
        }

        "a settled input applies the done clause strictly" in {
            val v: Int < Ask = 41
            val r            = handleFirst(Tag[Ask], v)([X] => (_, cont) => cont(0), _ + 1)
            assert(r.evalNow == Maybe(42))
        }

        "the done clause may suspend" in {
            val v: Int < (Ask & Say) = say("x").map(_ => 41)
            val first                = handleFirst(Tag[Ask], v)([X] => (_, _) => -1, a => say("done").map(_ => a + 1))
            val r                    = ArrowEffect.handleCont(Tag[Say], first)([X] => (_, cont) => cont(()), a => a)
            assert(Eval(r) == 42)
        }

        "maps chained after the region apply to both clauses" in {
            def firstOf(v: Int < (Ask & Say)) =
                handleFirst(Tag[Ask], v)([X] => (_, _) => 1, _ => 2).map(_ * 10).map(_ + 1)
            val answered = ArrowEffect.handleCont(Tag[Say], firstOf(say("x").map(_ => ask)))([X] => (_, cont) => cont(()), a => a)
            val settled  = ArrowEffect.handleCont(Tag[Say], firstOf(say("x").map(_ => 41)))([X] => (_, cont) => cont(()), a => a)
            assert(Eval(answered) == 11)
            assert(Eval(settled) == 21)
        }

        "the done clause runs when the computation settles without the operation" in {
            val v: Int < (Ask & Say) = say("x").map(_ => 41)
            val first                = handleFirst(Tag[Ask], v)([X] => (_, _) => -1, _ + 1)
            val r                    = ArrowEffect.handleCont(Tag[Say], first)([X] => (_, cont) => cont(()), a => a)
            assert(Eval(r) == 42)
        }

        "the handler stays installed until the operation arrives after a foreign crossing" in {
            val v: Int < (Ask & Say) = say("x").map(_ => ask.map(_ + 1))
            val first                = handleFirst(Tag[Ask], v)([X] => (_, cont) => cont(41), identity)
            val sayHandled           = ArrowEffect.handleCont(Tag[Say], first)([X] => (_, cont) => cont(()), a => a)
            assert(Eval(ArrowEffect.handleCont(Tag[Ask], sayHandled)([X] => (_, cont) => cont(0), a => a)) == 42)
        }

        "the continuation re-enters the regions the operation was raised under" in {
            var exits                    = 0
            val inner: Int < (Ask & Say) = say("x").map(_ => ask.map(_ + 1))
            val region = ArrowEffect.handleCont(Tag[Say], inner)([X] => (_, cont) => cont(()), a => a).map { a =>
                exits += 1
                a
            }
            val first = handleFirst(Tag[Ask], region)([X] => (_, cont) => cont(41), identity)
            assert(Eval(ArrowEffect.handleCont(Tag[Ask], first)([X] => (_, cont) => cont(0), a => a)) == 42)
            assert(exits == 1)
        }

        "a parked region answers the operation after it resumes" in {
            val v: Int < (Ask & Say) = say("x").map(_ => ask.map(_ + 1))
            val first                = handleFirst(Tag[Ask], v)([X] => (_, cont) => cont(41), identity)
            val parked               = Eval.partial(first)
            assert(parked.evalNow.isEmpty)
            val sayHandled = ArrowEffect.handleCont(Tag[Say], parked)([X] => (_, cont) => cont(()), a => a)
            assert(Eval(ArrowEffect.handleCont(Tag[Ask], sayHandled)([X] => (_, cont) => cont(0), a => a)) == 42)
        }

        "the innermost handleFirst wins under nested same-tag handlers" in {
            var outerAnswered = 0
            val inner         = handleFirst(Tag[Ask], ask.map(_ + 1))([X] => (_, cont) => cont(10), identity)
            val outer = handleFirst(Tag[Ask], inner: Int < Ask)(
                [X] =>
                    (_, cont) =>
                        outerAnswered += 1
                        cont(100)
                ,
                identity
            )
            assert(Eval(ArrowEffect.handleCont(Tag[Ask], outer)([X] => (_, cont) => cont(0), a => a)) == 11)
            assert(outerAnswered == 0)
        }

        "the done clause receives a computation held as a value unboxed" in {
            val payload: Int < Say           = say("p").map(_ => 7)
            val v: (Int < Say) < (Ask & Say) = say("x").map(_ => box(payload))
            var seen: AnyRef                 = null
            val first = handleFirst(Tag[Ask], v)(
                [X] => (_, _) => box(payload),
                a =>
                    seen = a.asInstanceOf[AnyRef]
                    box(a)
            )
            val boxed = Eval(ArrowEffect.handleCont(Tag[Say], first)([X] => (_, cont) => cont(()), a => box(a)))
            assert(seen eq payload.asInstanceOf[AnyRef])
            assert(Eval(ArrowEffect.handleCont(Tag[Say], boxed)([X] => (_, cont) => cont(()), a => a)) == 7)
        }

        "deep sequential operations are stack safe" in {
            def loop(n: Int): Int < Ask =
                if n == 0 then 0 else ask.map(_ => loop(n - 1))
            val first = handleFirst(Tag[Ask], loop(100000))([X] => (_, cont) => cont(1), identity)
            assert(Eval(ArrowEffect.handleCont(Tag[Ask], first)([X] => (_, cont) => cont(1), a => a)) == 0)
        }
    }
     */

    // Parked with the removal of ArrowEffect.dispatchFirst: the operation
    // inspection returns with the IOTask integration design. Restore then.
    /*
    "dispatchFirst" - {
        "runs the clause on the standing operation" in {
            var seen = ""
            ArrowEffect.dispatchFirst(Tag[Say], say("hello").map(_ => 1))([X] => input => seen = input)
            assert(seen == "hello")
        }

        "reads the input of a mapped suspension through its root" in {
            var seen = ""
            ArrowEffect.dispatchFirst(Tag[Say], say("root").map(_ => 1).map(_ + 1))([X] => input => seen = input)
            assert(seen == "root")
        }

        "peels a region node to reach the operation" in {
            var seen   = ""
            val region = ArrowEffect.handleCont(Tag[Ask], say("under").map(_ => ask))([X] => (_, cont) => cont(1), a => a)
            ArrowEffect.dispatchFirst(Tag[Say], region)([X] => input => seen = input)
            assert(seen == "under")
        }

        "peels a stateless and a stateful region node" in {
            var seen = ""
            val inner =
                ArrowEffect.handleLoopState(Tag[Ask], 0, say("deep").map(_ => ask))([X] => (state, _) => Loop.continue(state + 1, state: Int < Any), (_, a) => a)
            val outer = ArrowEffect.handleCont(Tag[Ask], inner: Int < (Ask & Say))([X] => (_, cont) => cont(1), a => a)
            ArrowEffect.dispatchFirst(Tag[Say], outer)([X] => input => seen = input)
            assert(seen == "deep")
        }

        "does nothing when the standing operation has another tag" in {
            var ran = false
            ArrowEffect.dispatchFirst(Tag[Ask], say("x").map(_ => 1))([X] => _ => ran = true)
            assert(!ran)
        }

        "stops at a deferred step without running its body" in {
            var ran   = false
            var built = false
            val v = Effect.defer {
                built = true
                say("hidden").map(_ => 1)
            }
            ArrowEffect.dispatchFirst(Tag[Say], v)([X] => _ => ran = true)
            assert(!ran)
            assert(!built)
        }

        "stops at a settled value" in {
            var ran          = false
            val v: Int < Say = 42
            ArrowEffect.dispatchFirst(Tag[Say], v)([X] => _ => ran = true)
            assert(!ran)
        }

        "leaves the computation as it was" in {
            var seen                 = 0
            val v: Int < (Ask & Say) = say("x").map(_ => ask.map(_ + 1))
            ArrowEffect.dispatchFirst(Tag[Say], v)([X] => _ => seen += 1)
            assert(seen == 1)
            val sayHandled = ArrowEffect.handleCont(Tag[Say], v)([X] => (_, cont) => cont(()), a => a)
            assert(Eval(ArrowEffect.handleCont(Tag[Ask], sayHandled)([X] => (_, cont) => cont(41), a => a)) == 42)
            assert(seen == 1)
        }
    }
     */

    "handleCatching" - {
        "answers operations when nothing fails" in {
            val v = ask.map(a => ask.map(b => a + b))
            val r = ArrowEffect.handleCatching(Tag[Ask], v)([X] => (_, cont) => cont(21))(_ => -1)
            assert(Eval(r) == 42)
        }

        "recovers a throw raised while the computation is built" in {
            val r = ArrowEffect.handleCatching(Tag[Ask], (throw new RuntimeException("boom")): Int < Ask)(
                [X] => (_, cont) => cont(0)
            )(_ => -1)
            assert(Eval(r) == -1)
        }

        "recovers a throw in the computation" in {
            val v = ask.map(_ => (throw new RuntimeException("boom")): Int)
            val r = ArrowEffect.handleCatching(Tag[Ask], v)([X] => (_, cont) => cont(41))(_ => -1)
            assert(Eval(r) == -1)
        }

        "recovers a throw raised after a resumption" in {
            val v = ask.map(a => ask.map(b => if a + b > 0 then throw new RuntimeException("boom") else 0))
            val r = ArrowEffect.handleCatching(Tag[Ask], v)([X] => (_, cont) => cont(21))(_ => -1)
            assert(Eval(r) == -1)
        }

        "recovers a throw in the handler" in {
            val v = ask.map(_ + 1)
            val r = ArrowEffect.handleCatching(Tag[Ask], v)(
                [X] => (_, _) => (throw new RuntimeException("boom")): Int < Ask
            )(_ => -1)
            assert(Eval(r) == -1)
        }

        "recovers a throw raised after a foreign crossing" in {
            val v: Int < (Ask & Say) = say("x").map(_ => ask).map(_ => (throw new RuntimeException("boom")): Int)
            val caught               = ArrowEffect.handleCatching(Tag[Ask], v)([X] => (_, cont) => cont(41))(_ => -1)
            val r                    = ArrowEffect.handleCont(Tag[Say], caught)([X] => (_, cont) => cont(()), a => a)
            assert(Eval(r) == -1)
        }

        "recovers a throw raised after an inner region's exit" in {
            val region       = ArrowEffect.handleCont(Tag[Say], say("x").map(_ => 1))([X] => (_, cont) => cont(()), a => a)
            val v: Int < Ask = ask.map(_ => region.map(_ => (throw new RuntimeException("boom")): Int))
            val r            = ArrowEffect.handleCatching(Tag[Ask], v)([X] => (_, cont) => cont(41))(_ => -1)
            assert(Eval(r) == -1)
        }

        "the recovered value is the region's result" in {
            val v = ask.map(_ => (throw new RuntimeException("boom")): Int)
            val r = ArrowEffect.handleCatching(Tag[Ask], v)([X] => (_, cont) => cont(0))(_ => 21).map(_ * 2)
            assert(Eval(r) == 42)
        }

        "a throw after the region is not recovered" in {
            val r = ArrowEffect
                .handleCatching(Tag[Ask], ask.map(_ + 1))([X] => (_, cont) => cont(41))(_ => -1)
                .map(_ => (throw new RuntimeException("boom")): Int)
            intercept[RuntimeException] {
                val _ = Eval(r)
            }
        }

        // the previous kernel evaluated a region as it was built, so a nested region's throw escaped at its
        // own definition site, before the recovering region existed. Here a region is a value and nothing
        // runs until the eval reaches it, so the throw happens inside the scope
        "a throw inside a region nested in the computation is recovered" in {
            val region = ArrowEffect.handleLoop(Tag[Say], say("x").map(_ => (throw new RuntimeException("boom")): Int))(
                [X] => _ => Loop.continue((): Unit < Any),
                a => a
            )
            val v: Int < Ask = ask.map(_ => region)
            val r            = ArrowEffect.handleCatching(Tag[Ask], v)([X] => (_, cont) => cont(0))(_ => -1)
            assert(Eval(r) == -1)
        }

        "a fatal error in the computation is not recovered" in {
            val v = ask.map(_ => (throw new InterruptedException("fatal")): Int)
            val r = ArrowEffect.handleCatching(Tag[Ask], v)([X] => (_, cont) => cont(0))(_ => -1)
            intercept[InterruptedException] {
                val _ = Eval(r)
            }
        }

        "a fatal error in the handler is not recovered" in {
            val r = ArrowEffect.handleCatching(Tag[Ask], ask.map(_ + 1))(
                [X] => (_, _) => (throw new InterruptedException("fatal")): Int < Ask
            )(_ => -1)
            intercept[InterruptedException] {
                val _ = Eval(r)
            }
        }

        "an inner handler keeps answering its own operations" in {
            val inner: Int < (Ask & Say) = say("x").map(_ => ask.map(_ + 1))
            val sayHandled: Int < Ask    = ArrowEffect.handleCont(Tag[Say], inner)([X] => (_, cont) => cont(()), a => a)
            val r                        = ArrowEffect.handleCatching(Tag[Ask], sayHandled)([X] => (_, cont) => cont(41))(_ => -1)
            assert(Eval(r) == 42)
        }

        "the done clause takes the region's result" in {
            val v = ask.map(_ + 1)
            val r = ArrowEffect.handleCatching(Tag[Ask], v)([X] => (_, cont) => cont(41), a => a * 2)(_ => -1)
            assert(Eval(r) == 84)
        }

        // the recovery is the handler entry, and a region that completes pops it before the done clause
        // runs. The previous kernel recovered here, its recovery being a try around the whole traversal
        // rather than a position on a stack
        "a throw in the done clause is not recovered" in {
            val v = ask.map(_ + 1)
            val r = ArrowEffect.handleCatching(Tag[Ask], v)(
                [X] => (_, cont) => cont(41),
                _ => (throw new RuntimeException("boom")): Int
            )(_ => -1)
            intercept[RuntimeException] {
                val _ = Eval(r)
            }
        }

        "the recovery answers at the region's row, so the done clause is not reached" in {
            var doneRan = false
            val v       = ask.map(_ => (throw new RuntimeException("boom")): Int)
            val r = ArrowEffect.handleCatching(Tag[Ask], v)(
                [X] => (_, cont) => cont(41),
                a =>
                    doneRan = true; a
            )(_ => -1)
            assert(Eval(r) == -1)
            assert(!doneRan)
        }
    }

    // Parked with the removal of ArrowEffect.handlePartial: the partial
    // handler returns with the IOTask integration design. Restore then.
    /*
    "handlePartial" - {
        "answers operations while the clause allows" in {
            val v = ask.map(a => ask.map(b => a + b))
            val r = ArrowEffect.handlePartial(Tag[Ask], v)([X] => (_, cont) => Maybe(cont(21)))
            assert(Eval(ArrowEffect.handleCont(Tag[Ask], r)([X] => (_, cont) => cont(0), a => a)) == 42)
        }

        "parks at the first refused operation and re-enters" in {
            var answered = 0
            val v        = ask.map(a => ask.map(b => a + b))
            val first = ArrowEffect.handlePartial(Tag[Ask], v)(
                [X] =>
                    (_, cont) =>
                        if answered == 0 then
                            answered += 1
                            Maybe(cont(21))
                        else Maybe.Absent
            )
            val second = ArrowEffect.handlePartial(Tag[Ask], first)([X] => (_, cont) => Maybe(cont(21)))
            assert(Eval(ArrowEffect.handleCont(Tag[Ask], second)([X] => (_, cont) => cont(0), a => a)) == 42)
            assert(answered == 1)
        }

        "parks and an answering handler finishes the remainder" in {
            val v      = ask.map(a => ask.map(b => a + b))
            val parked = ArrowEffect.handlePartial(Tag[Ask], v)([X] => (_, _) => Maybe.Absent)
            val r      = ArrowEffect.handleLoop(Tag[Ask], parked)([X] => _ => Loop.continue(21: Int < Any), a => a)
            assert(Eval(r) == 42)
        }

        "an operation after a foreign crossing is not answered by an earlier handlePartial call" in {
            var answered             = 0
            val v: Int < (Ask & Say) = ask.map(a => say("x").map(_ => ask.map(b => a + b)))
            val first = ArrowEffect.handlePartial(Tag[Ask], v)(
                [X] =>
                    (_, cont) =>
                        answered += 1
                        Maybe(cont(21))
            )
            assert(answered == 1)
            val handledSay = ArrowEffect.handleCont(Tag[Say], first)([X] => (_, cont) => cont(()), a => a)
            val r          = ArrowEffect.handleCont(Tag[Ask], handledSay)([X] => (_, cont) => cont(21), a => a)
            assert(Eval(r) == 42)
            assert(answered == 1)
        }

        "parks at a region node without evaluating it" in {
            val region = ArrowEffect.handleLoop(Tag[Ask], ask.map(_ + 1))([X] => _ => Loop.continue(41: Int < Any), a => a)
            val parked = ArrowEffect.handlePartial(Tag[Ask], region)([X] => (_, cont) => Maybe(cont(0)))
            assert(parked.evalNow.isEmpty)
            assert(Eval(ArrowEffect.handleCont(Tag[Ask], parked)([X] => (_, cont) => cont(0), a => a)) == 42)
        }

        "parks at a first region node without evaluating it" in {
            val region = handleFirst(Tag[Ask], ask.map(_ + 1))([X] => (_, cont) => cont(41), identity)
            val parked = ArrowEffect.handlePartial(Tag[Ask], region)([X] => (_, cont) => Maybe(cont(0)))
            assert(parked.evalNow.isEmpty)
            assert(Eval(ArrowEffect.handleCont(Tag[Ask], parked)([X] => (_, cont) => cont(0), a => a)) == 42)
        }

        "parks at a stateful region node without evaluating it" in {
            val region =
                ArrowEffect.handleLoopState(Tag[Ask], 10, ask.map(a => ask.map(b => a * 100 + b)))(
                    [X] => (state, _) => Loop.continue(state + 1, state: Int < Any),
                    (_, a) => a
                )
            val parked = ArrowEffect.handlePartial(Tag[Ask], region)([X] => (_, cont) => Maybe(cont(0)))
            assert(parked.evalNow.isEmpty)
            assert(Eval(ArrowEffect.handleCont(Tag[Ask], parked)([X] => (_, cont) => cont(0), a => a)) == 1011)
        }

        "answers operations leading into a stateful region and leaves it intact" in {
            val v: Int < Ask = ask.map { outer =>
                ArrowEffect.handleLoopState(Tag[Ask], 5, ask.map(a => ask.map(b => outer * 10000 + a * 100 + b)))(
                    [X] => (state, _) => Loop.continue(state + 1, state: Int < Any),
                    (_, a) => a
                )
            }
            val parked = ArrowEffect.handlePartial(Tag[Ask], v)([X] => (_, cont) => Maybe(cont(3)))
            assert(parked.evalNow.isEmpty)
            assert(Eval(ArrowEffect.handleCont(Tag[Ask], parked)([X] => (_, cont) => cont(0), a => a)) == 30506)
        }

        "passes a boxed computation through intact" in {
            val payload: Int < Say   = say("p").map(_ => 7)
            val v: (Int < Say) < Ask = ask.map(_ => box(payload))
            val r                    = ArrowEffect.handlePartial(Tag[Ask], v)([X] => (_, cont) => Maybe(cont(0)))
            val boxed                = Eval(ArrowEffect.handleCont(Tag[Ask], r)([X] => (_, cont) => cont(0), a => box(a)))
            assert(Eval(ArrowEffect.handleCont(Tag[Say], boxed)([X] => (_, cont) => cont(()), a => a)) == 7)
        }

        "parks at a pending stop request without answering" in {
            var answered = 0
            def burn(n: Int): Int < Any =
                if n == 0 then 0 else (0: Int < Any).map(_ => burn(n - 1))
            val v = burn(Period * 2).map(_ => ask)
            assert(Safepoint.stop(Thread.currentThread()))
            val parked = ArrowEffect.handlePartial(Tag[Ask], v)(
                [X] =>
                    (_, cont) =>
                        answered += 1
                        Maybe(cont(21))
            )
            assert(answered == 0)
            assert(Eval(ArrowEffect.handleCont(Tag[Ask], parked)([X] => (_, cont) => cont(21), a => a)) == 21)
        }
    }
     */

    "eval throws on an unhandled suspension" in {
        val ex = intercept[kyo.bug.KyoBugException] {
            Eval(ask.asInstanceOf[Int < Any])
        }
        assert(ex.getMessage.contains("unhandled suspension"))
    }

    "contracts" - {
        "a clause raising a foreign effect is answered by the outer handler across the region" in {
            val v: Int < (Ask & Say) = ask.map(a => say("x").map(_ => ask.map(b => a + b)))
            val sayHandled: Int < Ask =
                ArrowEffect.handleCont(Tag[Say], v)([C] => (_, cont) => ask.map(extra => cont(()).map(_ + extra)), a => a)
            val r: Int < Any = ArrowEffect.handleCont(Tag[Ask], sayHandled)([C] => (_, cont) => cont(10), a => a)
            assert(Eval(r) == 30)
        }

        "a continuation is a value: invoking it twice runs the rest twice" in {
            var runs = 0
            val v = ask.map { a =>
                runs += 1
                a * 10
            }
            val r: Int < Any = ArrowEffect.handleCont(Tag[Ask], v)(
                [C] => (_, cont) => cont(1).map(a => cont(2).map(b => a + b)),
                a => a
            )
            assert(Eval(r) == 30)
            assert(runs == 2)
        }

        "a handler may run another handle inside its answer" in {
            val inner: Int < Say = say("s").map(_ => 5)
            val r: Int < Say = ArrowEffect.handleCont(Tag[Ask], ask.map(_ + 1))(
                [C] =>
                    (_, cont) =>
                        val answered: Int < Any = ArrowEffect.handleCont(Tag[Say], inner)([D] => (_, c) => c(()), a => a)
                        answered.map(a => cont(a))
                ,
                a => a
            )
            val out: Int < Any = ArrowEffect.handleCont(Tag[Say], r)([C] => (_, cont) => cont(()), a => a)
            assert(Eval(out) == 6)
        }

        "a computation held as a value passes through a handler untouched" in {
            val payload: Int < Any   = (1: Int < Any).map(_ + 1)
            val v: (Int < Any) < Ask = ask.map(_ => box(payload))
            val r: (Int < Any) < Any = ArrowEffect.handleCont(Tag[Ask], v)([C] => (_, cont) => cont(0), a => box(a))
            assert(Eval(Eval(r)) == 2)
        }

        "a throw in the handler surfaces at eval" in {
            val r: Int < Any = ArrowEffect.handleCont(Tag[Ask], ask.map(_ + 1))(
                [C] => (_, _) => (throw new RuntimeException("boom")): Int < Ask,
                a => a
            )
            intercept[RuntimeException] {
                val _ = Eval(r)
            }
        }

        "a throw in a map surfaces at construction on the settled path" in {
            intercept[RuntimeException] {
                val _ = (1: Int < Any).map(_ => (throw new RuntimeException("boom")): Int)
            }
        }
    }

    "nested box" - {
        "a pending computation held as a value double-boxes and unboxes one level per eval" in {
            val inner: Int < Say                 = say("x").map(_ => 1)
            val once: (Int < Say) < Any          = box(inner)
            val twice: ((Int < Say) < Any) < Any = box(once)
            val back: (Int < Say) < Any          = Eval(twice)
            val r: Int < Any                     = ArrowEffect.handleCont(Tag[Say], Eval(back))([C] => (_, cont) => cont(()), a => a)
            assert(Eval(r) == 1)
        }

        "mapping over a double-boxed computation sees the once-boxed value" in {
            val inner: Int < Say                 = say("x").map(_ => 1)
            val twice: ((Int < Say) < Any) < Any = box(box(inner))
            def widen[A, S](v: A < S): A < S     = v
            val unbox = (once: (Int < Say) < Any) =>
                Eval(ArrowEffect.handleCont(Tag[Say], Eval(once))([C] => (_, cont) => cont(()), a => a))
            val r: Int < Any = widen(twice).map(once => unbox(once))
            assert(Eval(r) == 1)
        }

        "a pending computation held as a value crosses a handler boxed" in {
            val payload: Int < Say         = say("p").map(_ => 7)
            val v: (Int < Say) < Ask       = ask.map(_ => box(payload))
            val handled: (Int < Say) < Any = ArrowEffect.handleCont(Tag[Ask], v)([C] => (_, cont) => cont(0), a => box(a))
            val r: Int < Any               = ArrowEffect.handleCont(Tag[Say], Eval(handled))([C] => (_, cont) => cont(()), a => a)
            assert(Eval(r) == 7)
        }

        "a stateful handler passes a pending computation value through intact" in {
            val payload: Int < Say   = say("p").map(_ => 7)
            val v: (Int < Say) < Ask = ask.map(_ => box(payload))
            val r: (Int < Say) < Any = ArrowEffect.handleLoopState(Tag[Ask], 0, v)(
                [C] => (state, _) => Loop.continue(state + 1, 0: Int < Any),
                (_, a) => box(a)
            )
            val boxed          = Eval(r)
            val out: Int < Any = ArrowEffect.handleCont(Tag[Say], boxed)([C] => (_, cont) => cont(()), a => a)
            assert(Eval(out) == 7)
        }
    }

    "a handler stepping a rescue at the exact budget boundary floats it outward" in {
        def nest(n: Int): Int < Any =
            if n == 0 then
                ArrowEffect.handleCont(Tag[Ask], ask.map(_ + 1))([C] => (_, cont) => cont(41), a => a)
            else (0: Int < Any).map(_ => nest(n - 1))
        assert(Eval(nest(Period)) == 42)
    }

    "coverage" - {
        "the innermost handle wins under nested same-tag handlers" in {
            var outerCount       = 0
            val inner: Int < Ask = ArrowEffect.handleCont(Tag[Ask], ask.map(_ + 1))([C] => (_, cont) => cont(10), a => a)
            val outer: Int < Any = ArrowEffect.handleCont(Tag[Ask], inner)(
                [C] =>
                    (_, cont) =>
                        outerCount += 1
                        cont(100)
                ,
                a => a
            )
            assert(Eval(outer) == 11)
            assert(outerCount == 0)
        }

        "settled inputs pass through strictly" in {
            val v: Int < Ask = 42
            assert(ArrowEffect.handleCont(Tag[Ask], v)([C] => (_, cont) => cont(0), a => a).evalNow == Maybe(42))
            assert(ArrowEffect.handleLoop(Tag[Ask], v)([C] => _ => Loop.continue(0: Int < Any), a => a).evalNow == Maybe(42))
            assert(ArrowEffect.handleLoopState(Tag[Ask], 7, v)(
                [C] => (s, _) => Loop.continue(s, 0: Int < Any),
                (_, a) => a
            ).evalNow == Maybe(42))
        }

        "a map chained after a parked handler runs after the handler completes" in {
            var order                = List.empty[String]
            val v: Int < (Ask & Say) = say("x").map(_ => ask.map(_ + 1))
            val handled: Int < Say = ArrowEffect.handleCont(Tag[Ask], v)(
                [C] =>
                    (_, cont) =>
                        order :+= "answer"
                        cont(41)
                ,
                a => a
            )
            val chained = handled.map { r =>
                order :+= "after"
                r
            }
            val r: Int < Any = ArrowEffect.handleCont(Tag[Say], chained)([C] => (_, cont) => cont(()), a => a)
            assert(Eval(r) == 42)
            assert(order == List("answer", "after"))
        }

        "evaluation recovers after a thrown handler" in {
            intercept[RuntimeException] {
                val failing: Int < Any = ArrowEffect.handleCont(Tag[Ask], ask.map(_ + 1))(
                    [C] => (_, _) => (throw new RuntimeException("boom")): Int < Ask,
                    a => a
                )
                val _ = Eval(failing)
            }
            val r: Int < Any = ArrowEffect.handleCont(Tag[Ask], ask.map(_ + 1))([C] => (_, cont) => cont(41), a => a)
            assert(Eval(r) == 42)
        }
    }

    // The answer fast path hands the clause call to a method the call site generated and reports the
    // outcome through a per-stack cell. The cell is interpreter mutability, and the kernel's invariant is
    // that everything escaping the eval is a complete value, correct under multi-shot; these pins are the
    // hostile axes of that concession, not construction arguments.
    "the answer fast path" - {

        "a capture across the fast path is multi-shot, including across threads" in {
            var kref: Arrow[Unit, Int, Any] = null
            val body: Int < (Ask & Say) =
                ask.map(a => ask.map(b => say("x").andThen(ask.map(c => a + b + c))))
            val region: Int < Say =
                ArrowEffect.handleLoopState(Tag[Ask], 0, body)(
                    [C] => (n, _) => Loop.continue(n + 1, n: Int < Any),
                    (n, a) => n * 1000 + a
                )
            val r0 = Eval(ArrowEffect.handleCont(Tag[Say], region)(
                [C] =>
                    (_, cont) =>
                        kref = cont.asInstanceOf[Arrow[Unit, Int, Any]]
                        cont(())
                ,
                a => a
            ))
            assert(r0 == 3003)
            // two replays on this thread: each must run the captured region independently
            assert(Eval(kref(())) == 3003)
            assert(Eval(kref(())) == 3003)
            // and one on another thread: the capture is a complete value, not a view of this eval
            @volatile var tr = 0
            val t            = new Thread(() => tr = Eval(kref(())))
            t.start()
            t.join()
            assert(tr == 3003)
        }

        "a clause keeps only the values it was given" in {
            val seen = ListBuffer[(Int, String)]()
            def run(): Int =
                Eval(ArrowEffect.handleLoopState(Tag[Ask], 0, ask.map(a => ask.map(b => ask.map(c => a + b + c))))(
                    [C] =>
                        (n, i) =>
                            seen += ((n, i.toString))
                            Loop.continue(n + 1, n: Int < Any)
                    ,
                    (n, a) => n * 1000 + a
                ))
            assert(run() == 3003)
            val snapshot = seen.toList
            assert(snapshot == List((0, "()"), (1, "()"), (2, "()")))
            // a second eval must not disturb what the first clause stored: the arguments were plain
            // values, not aliases of live kernel state
            assert(run() == 3003)
            assert(seen.toList.take(3) == snapshot)
            assert(seen.toList.drop(3) == snapshot)
        }

        "a clause can run a full eval of its own mid-loop" in {
            def innerRun(): Int =
                Eval(ArrowEffect.handleLoopState(Tag[Ask], 100, ask.map(a => ask.map(b => a + b)))(
                    [C] => (n, _) => Loop.continue(n + 1, n: Int < Any),
                    (n, a) => n + a
                ))
            val r = Eval(ArrowEffect.handleLoopState(Tag[Ask], 0, ask.map(a => ask.map(b => a + b)))(
                [C] =>
                    (n, _) =>
                        val i = innerRun()
                        Loop.continue(n + 1, (n + i): Int < Any)
                ,
                (n, a) => n * 100000 + a
            ))
            // inner: answers 100 and 101, state 102, sum 201, done 102 + 201; outer answers 0 and 1
            // shifted by it
            assert(r == 200607)
        }

        "a throw after settled answers recovers with every commit already made" in {
            val states = ListBuffer[Int]()
            case class Boom() extends RuntimeException
            def loop(i: Int): Int < Ask =
                if i > 5 then i else ask.map(a => if a == 2 then throw Boom() else loop(i + 1))
            val region: Int < Any =
                ArrowEffect.handleLoopState(Tag[Ask], 0, loop(0))(
                    [C] =>
                        (n, _) =>
                            states += n
                            Loop.continue(n + 1, n: Int < Any)
                    ,
                    (n, a) => a
                )
            val r = Eval(Effect.catching(region)(_ => -1))
            assert(r == -1)
            // the throw happened applying the continuation after the third answer: all three clause
            // runs, each with the state the previous commit produced, are visible
            assert(states.toList == List(0, 1, 2))
        }

        "a park taken mid answer loop resumes in a fresh full eval" in {
            def countdown(i: Int): Int < Ask =
                if i == 0 then 0 else ask.map(a => countdown(i - a))
            val region: Int < Any =
                ArrowEffect.handleLoopState(Tag[Ask], 0, countdown(100))(
                    [C] =>
                        (n, _) =>
                            if n == 10 then kyo.discard(internal.Safepoint.stop(Thread.currentThread()))
                            Loop.continue(n + 1, 1: Int < Any)
                    ,
                    (n, a) => n + a
                )
            val first = Eval.partial(region)
            assert(first.evalNow == Maybe.Absent)
            assert(Eval(first) == 100)
        }
    }

    "the cont answer fast path" - {

        "a clause may apply its continuation twice in one answer" in {
            val r = Eval(ArrowEffect.handleCont(Tag[Ask], ask.map(a => a * 10))(
                [C] => (_, cont) => cont(1).map(x => cont(2).map(y => x * 1000 + y)),
                a => a
            ))
            assert(r == 10020)
        }

        "a mid-loop continuation applied twice replays the tail independently" in {
            def loop(i: Int): Int < Ask =
                if i > 3 then i else ask.map(a => loop(i + a))
            var count = 0
            val r = Eval(ArrowEffect.handleCont(Tag[Ask], loop(0))(
                [C] =>
                    (_, cont) =>
                        count += 1
                        if count == 2 then cont(1).map(x => cont(1).map(y => x + y))
                        else cont(1)
                ,
                a => a
            ))
            // the second answer's clause runs the tail twice; each replay re-enters the clause for
            // the remaining suspensions, so the region completes at 4 + 4 after six clause runs
            assert(r == 8)
            assert(count == 6)
        }

        "a hoarded fast-path continuation replays after the region finished" in {
            var kref: Arrow[Int, Int, Any] = null
            def loop(i: Int): Int < Ask =
                if i > 3 then i else ask.map(a => loop(i + a))
            val r0 = Eval(ArrowEffect.handleCont(Tag[Ask], loop(0))(
                [C] =>
                    (_, cont) =>
                        kref = cont.asInstanceOf[Arrow[Int, Int, Any]]
                        cont(1)
                ,
                a => a
            ))
            assert(r0 == 4)
            // the last capture is the settled tail: a complete value, replayable twice and on
            // another thread, never a view of the answers loop it was handed out from
            assert(Eval(kref(1)) == 4)
            assert(Eval(kref(1)) == 4)
            @volatile var tr = 0
            val t            = new Thread(() => tr = Eval(kref(1)))
            t.start()
            t.join()
            assert(tr == 4)
        }

        "a clause can run a full eval of its own mid-loop" in {
            def innerRun(): Int =
                Eval(ArrowEffect.handleCont(Tag[Ask], ask.map(a => ask.map(b => a + b)))(
                    [C] => (_, cont) => cont(7),
                    a => a
                ))
            def loop(i: Int): Int < Ask =
                if i > 3 then i else ask.map(a => loop(i + a))
            val r = Eval(ArrowEffect.handleCont(Tag[Ask], loop(0))(
                [C] => (_, cont) => cont(innerRun() / 14),
                a => a
            ))
            assert(r == 4)
        }

        "a throw while applying the continuation recovers after the answers made" in {
            val seen = ListBuffer[Int]()
            case class Boom() extends RuntimeException
            def loop(i: Int): Int < Ask =
                if i > 5 then i else ask.map(a => if i == 2 then throw Boom() else loop(i + a))
            val region: Int < Any =
                ArrowEffect.handleCont(Tag[Ask], loop(0))(
                    [C] =>
                        (_, cont) =>
                            seen += seen.size
                            cont(1)
                    ,
                    a => a
                )
            assert(Eval(Effect.catching(region)(_ => -1)) == -1)
            // three answers ran before the third continuation application threw
            assert(seen.toList == List(0, 1, 2))
        }

        "a park taken mid answer loop resumes in a fresh full eval" in {
            def countdown(i: Int): Int < Ask =
                if i == 0 then 0 else ask.map(a => countdown(i - a))
            var n = 0
            val region: Int < Any =
                ArrowEffect.handleCont(Tag[Ask], countdown(100))(
                    [C] =>
                        (_, cont) =>
                            n += 1
                            if n == 10 then kyo.discard(internal.Safepoint.stop(Thread.currentThread()))
                            cont(1)
                    ,
                    a => a
                )
            val first = Eval.partial(region)
            assert(first.evalNow == Maybe.Absent)
            assert(Eval(first) == 0)
            assert(n == 100)
        }
    }

    "safety audit pins" - {

        "a deferred payload that throws mid answer loop cannot commit another dispatch's state or continuation" in {
            case class Boom() extends RuntimeException
            // Say's stateful region holds an Int state, Ask's holds a String state; the body forces
            // a deferred payload that throws, with a recovery inside both regions. Any cross-typed
            // state or a foreign continuation answering the failure breaks the assertions
            val body: Int < (Ask & Say) =
                say("s").map(_ => ask.map(a => Effect.defer((throw Boom()): Int).map(_ + a)))
            val recovered: Int < (Ask & Say) = Effect.catching(body)(_ => -1)
            val askRegion: Int < Say = ArrowEffect.handleLoopState(Tag[Ask], "s0", recovered)(
                [C] => (s, _) => Loop.continue(s + "+", 1: Int < Any),
                (s, a) => if s == "s0+" then a else -100
            )
            val sayRegion: Int < Any = ArrowEffect.handleLoopState(Tag[Say], 100, askRegion)(
                [C] => (n, _) => Loop.continue(n + 1, (): Unit < Any),
                (n, a) => n * 1000 + a
            )
            assert(Eval(sayRegion) == 100999)
        }

        "a clause that suspends on the outer effect keeps its state lane across the shared cell" in {
            // both regions are stateful and every dispatch travels through the one per-stack Out
            // cell; the inner clause suspends on the outer effect, so each inner dispatch bails
            // mid-flight, the outer dispatch commits its own state through the same cell, and the
            // inner region rebuilds from the clause outcome. Each lane must see exactly its own
            // sequence of states, never the other's
            var outerSaw        = List.empty[String]
            val body: Int < Ask = ask.map(a => ask.map(b => ask.map(c => a * 100 + b * 10 + c)))
            val askRegion: Int < Say = ArrowEffect.handleLoopState(Tag[Ask], "s", body)(
                [C] => (s, _) => say(s).map(_ => Loop.continue(s + "+", s.length: Int < Any)),
                (s, a) => if s == "s+++" then a else -1000
            )
            val sayRegion: Int < Any = ArrowEffect.handleLoopState(Tag[Say], 0, askRegion)(
                [C] =>
                    (n, msg) =>
                        outerSaw = outerSaw :+ msg; Loop.continue(n + 1, (): Unit < Any)
                ,
                (n, a) => n * 1000 + a
            )
            // inner answers are the state's length at each dispatch: 1, 2, 3; outer counts three
            assert(Eval(sayRegion) == 3123)
            assert(outerSaw == List("s", "s+", "s++"))
        }

        "a clause throw meets the same scopes on every dispatch path" in {
            case class Boom() extends RuntimeException
            // a Catching standing between the suspension and the handler: whether it answers the
            // clause's throw must not depend on which handler family dispatched the clause
            val body: Int < Ask = Effect.catching(ask.map(_ + 1))(_ => -1)
            def viaLoop: Int < Any =
                ArrowEffect.handleLoop(Tag[Ask], body)([C] => _ => throw Boom(), a => a)
            def viaCont: Int < Any =
                ArrowEffect.handleCont(Tag[Ask], body)([C] => (_, _) => throw Boom(), a => a)
            val l = Eval(Effect.catching(viaLoop)(_ => -2))
            val c = Eval(Effect.catching(viaCont)(_ => -2))
            assert(l == c)
        }

        "a clause throw meets the same scopes on the fast and general cont paths" in {
            case class Boom() extends RuntimeException
            def run(body: Int < Ask): Int =
                Eval(Effect.catching(
                    ArrowEffect.handleCont(Tag[Ask], body)([C] => (_, _) => throw Boom(), a => a)
                )(_ => -2))
            // a bare suspension takes the pos gate; a mapped one takes the general dump path. The
            // clause's failure surface must not depend on which one dispatched it
            assert(run(ask) == run(ask.map(_ + 1)))
        }

        "a throwing release in a nested eval does not disarm the enclosing slice" in {
            // a nested full eval clears the slot's armed bit on entry (save installs a fresh
            // state) and owes it back through the restore in its finally; a boundary drain that
            // throws must not skip that restore. The disarm is observable through strict
            // construction: with the armed bit lost, a pending stop stops draining the budget,
            // so an eager chain runs past the stop instead of reifying at it. The body sits in
            // a deferred payload so the whole scenario runs inside the armed slice
            val inner: Int < Ask =
                Effect.bracket(Effect.defer(1))(_ => throw new IllegalStateException("release"))(_ => ask.map(_ + 1))
            val dropped: Int < Any =
                ArrowEffect.handleCont(Tag[Ask], inner)([C] => (_, _) => -1, a => a)
            var built = 0
            val outer: Int < Any =
                Effect.defer {
                    try kyo.discard(Eval(dropped))
                    catch case _: IllegalStateException => ()
                    0
                }.map { z =>
                    var acc: Int < Any = z
                    var i              = 0
                    while i < 100 do
                        acc = acc.map { x =>
                            built += 1
                            if built == 50 then kyo.discard(internal.Safepoint.stop(Thread.currentThread()))
                            x + 1
                        }
                        i += 1
                    end while
                    acc
                }
            val p = Eval.partial(outer)
            assert(p.evalNow.isEmpty)
            // the armed bit came back from the nested eval, so the stop reified construction
            // within a step or two of where it lodged
            assert(built >= 50 && built <= 52, s"built=$built")
            assert(Eval(p) == 100)
            assert(built == 100)
        }

        "a throwing release on the completing path leaves the caller's safepoint state intact" in {
            // the clause drops the continuation, so the bracket's release is owed by the drain at
            // the eval's boundary; its throw must not skip the safepoint restore
            val v: Int < Ask =
                Effect.bracket(Effect.defer(1))(_ => throw new IllegalStateException("release"))(_ => ask.map(_ + 1))
            val dropped: Int < Any =
                ArrowEffect.handleCont(Tag[Ask], v)([C] => (_, _) => -1, a => a)
            val slot = internal.Safepoint.get()
            // put the slot in a state distinct from a fresh one, so a skipped restore is visible
            kyo.discard(internal.Safepoint.enter(slot))
            kyo.discard(internal.Safepoint.enter(slot))
            try
                val before = internal.Safepoint.save(slot)
                internal.Safepoint.restore(slot, before)
                intercept[IllegalStateException](kyo.discard(Eval(dropped)))
                val after = internal.Safepoint.save(slot)
                internal.Safepoint.restore(slot, after)
                assert(after.equals(before))
            finally
                internal.Safepoint.exit(slot)
                internal.Safepoint.exit(slot)
            end try
        }

        "a throwing deferred payload after an answer does not re-run the consumed continuation" in {
            case class Boom() extends RuntimeException
            var contRuns = 0
            val body: Int < Ask =
                ask.map { a =>
                    contRuns += 1
                    Effect.defer((throw Boom()): Int).map(_ + a)
                }
            val region: Int < Any = ArrowEffect.handleLoop(Tag[Ask], body)(
                [C] => _ => Loop.continue(1: Int < Any),
                a => a
            )
            assert(Eval(Effect.catching(region)(_ => -1)) == -1)
            // the answers loop consumed the continuation before the payload threw; whatever the
            // exception lane pushed back must be discarded by the unwind, never re-executed
            assert(contRuns == 1)
        }

        "a stop arriving during eager construction inside a slice reifies and parks the chain" in {
            // a mapped step builds a long strict chain while the slice runs; a stop lodged midway
            // must make the remaining construction reify from that point and the slice park, with
            // the completed prefix preserved
            var built = 0
            val v: Int < Any =
                Effect.defer(0).map { z =>
                    var acc: Int < Any = z
                    var i              = 0
                    while i < 100 do
                        acc = acc.map { x =>
                            built += 1
                            if built == 50 then kyo.discard(internal.Safepoint.stop(Thread.currentThread()))
                            x + 1
                        }
                        i += 1
                    end while
                    acc
                }
            val p = Eval.partial(v)
            assert(p.evalNow.isEmpty)
            // the fiftieth step lodged the stop; construction reified within a step or two of it
            assert(built >= 50 && built <= 52, s"built=$built")
            assert(Eval(p) == 100)
            assert(built == 100)
        }

        "a boxed answer crosses the answers loop unopened" in {
            val payload: Int < Say = say("p").map(_ => 7)
            val region: (Int < Say) < Any =
                ArrowEffect.handleLoop(Tag[AskBoxed], askBoxed.map(v => box(v)))(
                    [C] => _ => Loop.continue(box(payload): (Int < Say) < Any),
                    a => box(a)
                )
            val got: Int < Say = Eval(region)
            // exactly the lift's one level is stripped: the region result IS the boxed payload
            assert(got.asInstanceOf[AnyRef] eq payload.asInstanceOf[AnyRef])
            val r = Eval(ArrowEffect.handleCont(Tag[Say], got)([C] => (_, cont) => cont(()), a => a))
            assert(r == 7)
        }
    }

end ArrowEffectTest
