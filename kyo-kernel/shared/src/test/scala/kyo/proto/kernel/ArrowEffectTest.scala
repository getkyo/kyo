package kyo.proto.kernel

import kyo.Const
import kyo.Maybe
import kyo.Tag
import kyo.proto.Arrow
import kyo.proto.Loop
import kyo.proto.kernel.internal.Eval
import kyo.proto.kernel.internal.Nested
import kyo.proto.kernel.internal.Pending
import kyo.proto.kernel.internal.Safepoint
import org.scalatest.freespec.AnyFreeSpec
import scala.annotation.tailrec

class ArrowEffectTest extends AnyFreeSpec:

    private def eval[A](v: A < Any): A = v.eval

    sealed trait Ask extends ArrowEffect[Const[Unit], Const[Int]]
    def ask: Int < Ask = ArrowEffect.suspend[Any](Tag[Ask], ())

    sealed trait AskSub extends Ask

    def askSub: Int < Ask = ArrowEffect.suspend[Any](Tag[AskSub].asInstanceOf[Tag[Ask]], ())

    sealed trait Say extends ArrowEffect[Const[String], Const[Unit]]
    def say(s: String): Unit < Say = ArrowEffect.suspend[Any](Tag[Say], s)

    def box[A](v: A): A < Any = v

    private val Period = Safepoint.period()

    "handleLoop" - {
        "answers every operation in place" in {
            def loop(i: Int): Int < Ask =
                if i < 3 then ask.map(a => loop(i + a)) else i
            val r: Int < Any = ArrowEffect.handleLoop(Tag[Ask], loop(0))([C] => _ => Loop.continue((), 1: Int < Any), a => a)
            assert(eval(r) == 3)
        }

        "Loop.done stops the region" in {
            var reached = false
            val v = ask.map { a =>
                reached = true
                a + 1
            }
            val r: Int < Any = ArrowEffect.handleLoop(Tag[Ask], v)([C] => _ => Loop.done(-1), a => a)
            assert(eval(r) == -1)
            assert(!reached)
        }

        "done sees the settled result" in {
            val r: Int < Any = ArrowEffect.handleLoop(Tag[Ask], ask.map(_ + 1))([C] => _ => Loop.continue((), 41: Int < Any), a => a * 10)
            assert(eval(r) == 420)
        }

        "a settled input applies done strictly" in {
            var ran = false
            val r: Int < Any = ArrowEffect.handleLoop(Tag[Ask], 41: Int < Ask)(
                [C] => _ => Loop.continue((), 0: Int < Any),
                a =>
                    ran = true
                    a + 1
            )
            assert(ran)
            assert(eval(r) == 42)
        }

        "deep sequential operations are stack safe" in {
            def loop(n: Int): Int < Ask =
                if n == 0 then 0 else ask.map(_ => loop(n - 1))
            val r: Int < Any = ArrowEffect.handleLoop(Tag[Ask], loop(100000))([C] => _ => Loop.continue((), 1: Int < Any), a => a)
            assert(eval(r) == 0)
        }

        "the innermost region of a tag answers" in {
            val inner: Int < Any = ArrowEffect.handleLoop(Tag[Ask], ask)([C] => _ => Loop.continue((), 1: Int < Any), a => a)
            val outer: Int < Any = ArrowEffect.handleLoop(Tag[Ask], inner: Int < Ask)([C] => _ => Loop.continue((), 2: Int < Any), a => a)
            assert(eval(outer) == 1)
        }

        "a clause answers effectfully" in {
            var seen = List.empty[String]
            val handled: Int < Say = ArrowEffect.handleLoop(Tag[Ask], ask.map(_ + 1))(
                [C] => _ => say("consult").map(_ => Loop.continue((), 41: Int < Any)),
                a => a
            )
            val r: Int < Any = ArrowEffect.handleLoop(Tag[Say], handled)(
                [C] =>
                    s =>
                        seen = s :: seen
                        Loop.continue((), (): Unit < Any)
                ,
                a => a
            )
            assert(eval(r) == 42)
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
                        Loop.continue((), (): Unit < Any)
                ,
                a => a
            )
            assert(eval(r) == -1)
            assert(!reached)
            assert(!completed)
            assert(seen == List("stop"))
        }

        "every clause suspension re-arms the region" in {
            var seen = List.empty[String]
            val v    = ask.map(a => ask.map(b => a * 10 + b))
            val handled: Int < Say = ArrowEffect.handleLoop(Tag[Ask], v)(
                [C] => _ => say("x").map(_ => Loop.continue((), 1: Int < Any)),
                a => a
            )
            val r: Int < Any = ArrowEffect.handleLoop(Tag[Say], handled)(
                [C] =>
                    s =>
                        seen = s :: seen
                        Loop.continue((), (): Unit < Any)
                ,
                a => a
            )
            assert(eval(r) == 11)
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
                            Loop.continue((), (): Unit < Any)
                    ,
                    a => a
                )
            val handled: Int < Say = ArrowEffect.handleLoop(Tag[Ask], body)(
                [C] => _ => say("clause").map(_ => Loop.continue((), 41: Int < Any)),
                a => a
            )
            val r: Int < Any = ArrowEffect.handleLoop(Tag[Say], handled)(
                [C] =>
                    s =>
                        outerSeen = s :: outerSeen
                        Loop.continue((), (): Unit < Any)
                ,
                a => a
            )
            assert(eval(r) == 42)
            assert(interiorSeen.isEmpty)
            assert(outerSeen == List("clause"))
        }

        "a clause answer survives its own deep evaluation" in {
            def deep(i: Int): Int < Any =
                if i == 0 then 41 else (0: Int < Any).map(_ => deep(i - 1))
            val r: Int < Any = ArrowEffect.handleLoop(Tag[Ask], ask.map(_ + 1))(
                [C] => _ => deep(10000).map(n => Loop.continue((), n: Int < Any)),
                a => a
            )
            assert(eval(r) == 42)
        }

        "a clause suspends on its own effect per operation" in {
            var answered        = 0
            val body: Int < Ask = ask.map(a => ask.map(b => a * 10 + b))
            val doubled: Int < Ask = ArrowEffect.handleLoop(Tag[Ask], body)(
                [C] => _ => ask.map(v => Loop.continue((), v * 2: Int < Any)),
                a => a
            )
            val r: Int < Any = ArrowEffect.handleLoop(Tag[Ask], doubled)(
                [C] =>
                    _ =>
                        answered += 1
                        Loop.continue((), 3: Int < Any)
                ,
                a => a
            )
            assert(eval(r) == 66)
            assert(answered == 2)
        }

        "a suspended clause dispatch is multi-shot" in {
            val body: Int < Ask = ask.map(_ + 1)
            val handled: Int < Say = ArrowEffect.handleLoop(Tag[Ask], body)(
                [C] => _ => say("s").map(_ => Loop.continue((), 10: Int < Any)),
                a => a
            )
            val r: Int < Any = ArrowEffect.handleCont(Tag[Say], handled)(
                [C] => (_, cont) => cont(()).map(x => cont(()).map(y => x * 100 + y)),
                a => a
            )
            assert(eval(r) == 1111)
        }

        "a foreign operation crosses the region in place" in {
            var order                   = List.empty[String]
            val body: Int < (Ask & Say) = say("a").map(_ => ask).map(i => i + 1)
            val inner: Int < Say        = ArrowEffect.handleLoop(Tag[Ask], body)([C] => _ => Loop.continue((), 41: Int < Any), a => a)
            val r: Int < Any = ArrowEffect.handleLoop(Tag[Say], inner)(
                [C] =>
                    s =>
                        order = s :: order
                        Loop.continue((), (): Unit < Any)
                ,
                a => a
            )
            assert(eval(r) == 42)
            assert(order == List("a"))
        }

        "an effectful answer resolves through an outer handler" in {
            val v: Int < Ask = ask.map(_ + 1)
            val looped: Int < Say =
                ArrowEffect.handleLoop(Tag[Ask], v)([C] => _ => Loop.continue((), say("fetch").map(_ => 41)), a => a)
            val r: Int < Any = ArrowEffect.handleCont(Tag[Say], looped)([C] => (_, cont) => cont(()), a => a)
            assert(eval(r) == 42)
        }

        "a clause may suspend before producing its outcome" in {
            var logged = 0
            val looped: Int < Say = ArrowEffect.handleLoop(Tag[Ask], ask.map(_ + 1))(
                [C] =>
                    _ =>
                        say("pre").map { _ =>
                            logged += 1
                            Loop.continue((), 41: Int < Any)
                    },
                a => a
            )
            val r: Int < Any = ArrowEffect.handleCont(Tag[Say], looped)([C] => (_, cont) => cont(()), a => a)
            assert(eval(r) == 42)
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
            assert(eval(r) == -1)
            assert(!reached)
            assert(!innerExit)
        }

        "handles nested per recursion step in bounded stack" in {
            def go(n: Int): Int < Any =
                if n == 0 then 0
                else ArrowEffect.handleLoop(Tag[Ask], ask.map(_ => go(n - 1)))([C] => _ => Loop.continue((), 1: Int < Any), a => a)
            assert(eval(go(100000)) == 0)
        }

        "context effects arise from answering handlers" in {
            def provide[A, S](value: Int)(v: A < (Ask & S)): A < S =
                ArrowEffect.handleLoop(Tag[Ask], v)([C] => _ => Loop.continue((), value: Int < Any), a => a)
            assert(eval(provide(42)(ask.map(_ + 1))) == 43)
            assert(eval(provide(1)(provide(2)(ask))) == 2)
        }

        "stays in force across a foreign crossing captured by an outer handle" in {
            val v: Int < (Ask & Say) = say("x").map(_ => ask).map(_ + 1)
            val looped: Int < Say    = ArrowEffect.handleLoop(Tag[Ask], v)([C] => _ => Loop.continue((), 41: Int < Any), a => a)
            val r: Int < Any         = ArrowEffect.handleCont(Tag[Say], looped)([C] => (_, cont) => cont(()), a => a)
            assert(eval(r) == 42)
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
            assert(eval(outer) == -1)
            assert(!outerReached)
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
            var ran = false
            val r: Int < Any = ArrowEffect.handleCont(Tag[Ask], 41: Int < Ask)(
                [C] => (_, cont) => cont(0),
                a =>
                    ran = true
                    a + 1
            )
            assert(ran)
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

        "answers a single operation" in {
            val v            = ask.map(_ + 1)
            val r: Int < Any = ArrowEffect.handleCont(Tag[Ask], v)([C] => (_, cont) => cont(41), a => a)
            assert(eval(r) == 42)
        }

        "lazy: the handled computation is a value and answers at eval" in {
            var ran = false
            val v = ask.map { a =>
                ran = true
                a + 1
            }
            val r: Int < Any = ArrowEffect.handleCont(Tag[Ask], v)([C] => (_, cont) => cont(41), a => a)
            assert(!ran)
            assert(eval(r) == 42)
            assert(ran)
        }

        "a long map tower on a pending suspension handles in bounded stack" in {
            @tailrec def tower(v: Int < Ask, n: Int): Int < Ask =
                if n == 0 then v else tower(v.map(_ + 1), n - 1)
            val v            = tower(ask, 1000000)
            val r: Int < Any = ArrowEffect.handleCont(Tag[Ask], v)([C] => (_, cont) => cont(0), a => a)
            assert(eval(r) == 1000000)
        }

        "stays in force across a foreign crossing with a trailing transform" in {
            val v: Int < (Ask & Say) = say("x").map(_ => ask).map(_ + 1)
            val r: Int < Any = ArrowEffect.handleCont(
                Tag[Say],
                ArrowEffect.handleCont(Tag[Ask], v)([C] => (_, cont) => cont(41), a => a)
            )([C] => (_, cont) => cont(()), a => a)
            assert(eval(r) == 42)
        }

        "stays in force across a budget bounce with a trailing transform" in {
            def burn(n: Int): Int < Any =
                if n == 0 then
                    val v: Int < Ask = (0: Int < Any).map(_ => ask).map(_ + 1)
                    ArrowEffect.handleCont(Tag[Ask], v)([C] => (_, cont) => cont(41), a => a)
                else ((): Unit < Any).map(_ => burn(n - 1))
            assert(eval(burn(Period)) == 42)
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
            assert(eval(r) == 30)
            assert(count == 2)
        }

        "a foreign operation passes through and keeps the handler attached" in {
            val v: Int < (Ask & Say)  = ask.map(a => say(a.toString).map(_ => ask.map(b => a + b)))
            val handledAsk: Int < Say = ArrowEffect.handleCont(Tag[Ask], v)([C] => (_, cont) => cont(21), a => a)
            val r: Int < Any          = ArrowEffect.handleCont(Tag[Say], handledAsk)([C] => (_, cont) => cont(()), a => a)
            assert(eval(r) == 42)
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
            assert(eval(r) == 42)
        }

        "a supertype handler leaves a subtype effect in the row" in {
            val v: Int < AskSub = ArrowEffect.suspend[Any](Tag[AskSub], ()).map(_ + 1)
            val r               = ArrowEffect.handleCont(Tag[Ask], v)([C] => (_, cont) => cont(41), a => a)
            assertTypeError("val fullyHandled: Int < Any = r")
            val stillOwed: Int < AskSub = r
            assert(eval(ArrowEffect.handleCont(Tag[AskSub], stillOwed)([C] => (_, cont) => cont(41), a => a)) == 42)
        }

        "a handler at a subtype effect answers a computation typed at the supertype" in {
            val v: Int < Ask = askSub.map(_ + 1)
            val r: Int < Any = ArrowEffect.handleCont(Tag[AskSub], v)([C] => (_, cont) => cont(41), a => a)
            assert(eval(r) == 42)
        }

        "the operation clause receives the operation reified at its own tag" in {
            var seen         = List.empty[String]
            val v: Int < Ask = ask.map(_ + 1)

            val r: Int < Any = ArrowEffect.handleContOperation(Tag[AskSub], v)(
                [X] =>
                    (operation, _) =>
                        val suspend = operation.asInstanceOf[kyo.proto.kernel.internal.Kyo.Suspend[?, ?, ?]]
                        seen = suspend.tag.show :: seen
                        -1
                ,
                a => a
            )
            assert(eval(r) == -1)
            assert(seen == List(Tag[Ask].show))
        }

        "a map chained after the region applies to the result" in {
            val v            = ask.map(_ + 1)
            val r: Int < Any = ArrowEffect.handleCont(Tag[Ask], v)([C] => (_, cont) => cont(41), a => a).map(_ * 10)
            assert(eval(r) == 420)
        }

        "a map chained after the region runs outside the scope" in {
            val v                = ask.map(_ + 1)
            val r: Int < Ask     = ArrowEffect.handleCont(Tag[Ask], v)([C] => (_, cont) => cont(41), a => a).map(a => ask.map(_ + a))
            val outer: Int < Any = ArrowEffect.handleCont(Tag[Ask], r)([C] => (_, cont) => cont(1000), a => a)
            assert(eval(outer) == 1042)
        }

        "a map chained after a settled pass-through applies strictly" in {
            var ran = false
            val r: Int < Any = ArrowEffect.handleCont(Tag[Ask], box(42))([C] => (_, cont) => cont(0), a => a).map { a =>
                ran = true
                a + 1
            }
            assert(ran)
            assert(eval(r) == 43)
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
                        Loop.continue((), (): Unit < Any)
                ,
                a => a
            )
            assert(eval(r) == 12)
            assert(seen == List("state 2", "state 1"))
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

        "a settled input applies done strictly with the initial state" in {
            val r: Int < Any = ArrowEffect.handleLoopState(Tag[Ask], 7, 35: Int < Ask)(
                [C] => (s, _) => Loop.continue(s, 0: Int < Any),
                (s, a) => s + a
            )
            assert(eval(r) == 42)
        }

        "state composes with done" in {
            def go(n: Int): Int < Ask =
                if n == 0 then 0 else ask.map(_ => go(n - 1))
            val r: Int < Any = ArrowEffect.handleLoopState(Tag[Ask], 3, go(5))(
                [C] => (remaining, _) => if remaining > 0 then Loop.continue(remaining - 1, 1: Int < Any) else Loop.done(-1),
                (_, a) => a
            )
            assert(eval(r) == -1)
        }

        "state survives an inner handler's exit" in {
            val program: Int < (Ask & Say) = ask.map(a => say("x").map(_ => a))
            val sayHandled: Int < Ask      = ArrowEffect.handleCont(Tag[Say], program)([C] => (_, cont) => cont(()), a => a)
            val v: Int < Ask               = sayHandled.map(a => ask.map(b => a * 10 + b))
            val r: Int < Any = ArrowEffect.handleLoopState(Tag[Ask], 1, v)(
                [C] => (state, _) => Loop.continue(state + 1, state: Int < Any),
                (_, a) => a
            )
            assert(eval(r) == 12)
        }

        "done sees the final answer" in {
            val v = ask.map(a => ask.map(b => a + b))
            val r: Int < Any = ArrowEffect.handleLoopState(Tag[Ask], 10, v)(
                [C] => (s, _) => Loop.continue(s + 1, s: Int < Any),
                (_, a) => a * 2
            )
            assert(eval(r) == 42)
        }

        "done may be effectful" in {
            val r: Int < Say = ArrowEffect.handleLoopState(Tag[Ask], 5, ask)(
                [C] => (s, _) => Loop.continue(s, s: Int < Any),
                (s, a) => say("bye").map(_ => s + a)
            )
            val out: Int < Any = ArrowEffect.handleCont(Tag[Say], r)([C] => (_, cont) => cont(()), a => a)
            assert(eval(out) == 10)
        }

    }

    "handleWith" - {
        "applies the continuation to the region result" in {
            val r: Int < Any = ArrowEffect.handleContWith(Tag[Ask], ask.map(_ + 1))(
                [C] => (_, cont) => cont(20),
                a => a * 2
            )(b => b + 100)
            assert(eval(r) == 142)
        }

        "applies the continuation to a settled input" in {
            val r: Int < Any = ArrowEffect.handleContWith(Tag[Ask], 5: Int < Ask)(
                [C] => (_, cont) => cont(0),
                a => a + 1
            )(b => b * 10)
            assert(eval(r) == 60)
        }

        "the continuation can suspend on an outer effect" in {
            var seen = ""
            val inner: Int < Say =
                ArrowEffect.handleContWith(Tag[Ask], ask)([C] => (_, cont) => cont(1), a => a)(b => say("s").map(_ => b + 1))
            val r: Int < Any = ArrowEffect.handleLoop(Tag[Say], inner)(
                [C] =>
                    s =>
                        seen = s
                        Loop.continue((), (): Unit < Any)
                ,
                a => a
            )
            assert(eval(r) == 2)
            assert(seen == "s")
        }

        "deep sequential operations stay stack safe through the continuation" in {
            def loop(n: Int): Int < Ask =
                if n == 0 then 0 else ask.map(_ => loop(n - 1))
            val r: Int < Any = ArrowEffect.handleContWith(Tag[Ask], loop(100000))([C] => (_, cont) => cont(1), a => a)(b => b + 7)
            assert(eval(r) == 7)
        }
    }

    "handleLoopWith" - {
        "applies the continuation to the region result" in {
            val r: Int < Any = ArrowEffect.handleLoopWith[Const[Unit], Const[Int], Ask, Int, Int, Any, Any](Tag[Ask], ask.map(_ + 1))(
                [C] => _ => Loop.continue((), 41: Int < Any),
                a => a * 10
            )(b => b + 1)
            assert(eval(r) == 421)
        }

        "applies the continuation to a settled input" in {
            val r: Int < Any = ArrowEffect.handleLoopWith[Const[Unit], Const[Int], Ask, Int, Int, Any, Any](Tag[Ask], 41: Int < Ask)(
                [C] => _ => Loop.continue((), 0: Int < Any),
                a => a + 1
            )(b => b * 10)
            assert(eval(r) == 420)
        }

        "Loop.done flows through the continuation" in {
            val r: Int < Any = ArrowEffect.handleLoopWith(Tag[Ask], ask.map(_ + 1))(
                [C] => _ => Loop.done(-1),
                a => a
            )(b => b * 2)
            assert(eval(r) == -2)
        }
    }

    "handleLoopStateWith" - {
        "applies the continuation with the final state observed" in {
            val v = ask.map(a => ask.map(b => a * 10 + b))
            val r: Int < Any = ArrowEffect.handleLoopStateWith[Const[Unit], Const[Int], Ask, Int, Int, Any, Any, Int](Tag[Ask], 1, v)(
                [C] => (s, _) => Loop.continue(s + 1, s: Int < Any),
                (s, a) => s * 100 + a
            )(b => b + 1)
            assert(eval(r) == 313)
        }

        "applies the continuation to a settled input" in {
            val r: Int < Any =
                ArrowEffect.handleLoopStateWith[Const[Unit], Const[Int], Ask, Int, Int, Any, Any, Int](Tag[Ask], 7, 35: Int < Ask)(
                    [C] => (s, _) => Loop.continue(s, 0: Int < Any),
                    (s, a) => s + a
                )(b => b * 2)
            assert(eval(r) == 84)
        }
    }

    "suspendWith" - {
        "suspends and continues in one node" in {
            val v: Int < Ask = ArrowEffect.suspendWith[Any](Tag[Ask], ())(_ + 1)
            val r: Int < Any = ArrowEffect.handleLoop(Tag[Ask], v)([C] => _ => Loop.continue((), 41: Int < Any), a => a)
            assert(eval(r) == 42)
        }

        "deep recursion is stack safe" in {
            def loop(i: Int): Int < Ask =
                if i > 100000 then i
                else ArrowEffect.suspendWith[Any](Tag[Ask], ())(a => loop(i + a))
            val r: Int < Any = ArrowEffect.handleLoop(Tag[Ask], loop(0))([C] => _ => Loop.continue((), 1: Int < Any), a => a)
            assert(eval(r) == 100001)
        }

        "maps chain onto the node" in {
            val v            = ArrowEffect.suspendWith[Any](Tag[Ask], ())(_ + 1).map(_ * 2)
            val r: Int < Any = ArrowEffect.handleCont(Tag[Ask], v)([C] => (_, cont) => cont(20), a => a)
            assert(eval(r) == 42)
        }

        "an effectful continuation suspends again" in {
            val v            = ArrowEffect.suspendWith[Any](Tag[Ask], ())(a => ask.map(b => a + b))
            val r: Int < Any = ArrowEffect.handleCont(Tag[Ask], v)([C] => (_, cont) => cont(21), a => a)
            assert(eval(r) == 42)
        }

        "a long map tower on the node evaluates in bounded stack" in {
            @tailrec def tower(v: Int < Ask, n: Int): Int < Ask =
                if n == 0 then v else tower(v.map(_ + 1), n - 1)
            val v            = tower(ArrowEffect.suspendWith[Any](Tag[Ask], ())(_ + 1), 1000000)
            val r: Int < Any = ArrowEffect.handleCont(Tag[Ask], v)([C] => (_, cont) => cont(0), a => a)
            assert(eval(r) == 1000001)
        }
    }

    "a handle capture crossing an inner region" in {
        val inner: Int < Say = ArrowEffect.handleLoop(
            Tag[Ask],
            ask.map(a => say("x").map(_ => ask.map(b => a + b)))
        )([C] => _ => Loop.continue((), 1: Int < Any), a => a)
        val r: Int < Any = ArrowEffect.handleCont(Tag[Say], inner)([C] => (_, cont) => cont(()), a => a)
        assert(eval(r) == 2)
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
        )([C] => _ => Loop.continue((), 1: Int < Any), a => a)
        val r: Int < Any = ArrowEffect.handleCont(Tag[Say], inner)([C] => (_, cont) => cont(()), a => a)
        assert(eval(r) == 2)
        assert(runs == 1)
    }

    "a crossed stateful region resumes with its in-flight state" in {
        val inner: Int < Say = ArrowEffect.handleLoopState(
            Tag[Ask],
            10,
            ask.map(a => say("x").map(_ => ask.map(b => a * 100 + b)))
        )([C] => (s, _) => Loop.continue(s + 1, s: Int < Any), (_, a) => a)
        val r: Int < Any = ArrowEffect.handleCont(Tag[Say], inner)([C] => (_, cont) => cont(()), a => a)
        assert(eval(r) == 1011)
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

    "a map after the region applies to the result" in {
        val r: Int < Any = ArrowEffect.handleLoop(Tag[Ask], ask.map(_ + 1))([C] => _ => Loop.continue((), 41: Int < Any), a => a)
        assert(eval(r.map(_ * 10)) == 420)
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
            assert(eval(r) == 1998)
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
        assert(eval(r) == 85012)
    }

    "park" - {

        "a clause parks by returning and resumes by rewrapping the continuation" in {
            var ready: Maybe[Int]              = Maybe.Absent
            var stash: Maybe[Int => Int < Ask] = Maybe.Absent
            var polls                          = 0
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
                                    stash = Maybe(cont(_))
                                    -1
                            end match
                    ,
                    a => a
                )
            val body = ask.map(a => ask.map(b => ask.map(c => a * 100 + b * 10 + c)))

            ready = Maybe(1)
            assert(eval(boundary(body)) == -1)
            assert(polls == 2)

            assert(eval(boundary(stash.get.apply(2))) == -1)
            assert(polls == 3)

            assert(eval(boundary(stash.get.apply(3))) == 123)
            assert(polls == 3)
        }

        "a park preserves standing sibling regions" in {
            var seen                           = List.empty[String]
            var stash: Maybe[Int => Int < Ask] = Maybe.Absent
            def boundary(v: Int < Ask): Int < Any =
                ArrowEffect.handleCont(Tag[Ask], v)(
                    [C] =>
                        (_, cont) =>
                            stash = Maybe(cont(_))
                            -1
                    ,
                    a => a
                )
            val body: Int < (Ask & Say) = say("before").map(_ => ask.map(a => say("after").map(_ => a + 1)))
            val inner: Int < Ask = ArrowEffect.handleLoop(Tag[Say], body)(
                [C] =>
                    s =>
                        seen = s :: seen
                        Loop.continue((), (): Unit < Any)
                ,
                a => a
            )
            assert(eval(boundary(inner)) == -1)
            assert(seen == List("before"))
            assert(eval(boundary(stash.get.apply(41))) == 42)
            assert(seen == List("after", "before"))
        }

        "a union tag subsumes both effects the way regions are found" in {
            assert(Tag[Ask].erased <:< Tag[Ask | Say].erased)
            assert(Tag[Say].erased <:< Tag[Ask | Say].erased)
            assert(!(Tag[Ask | Say].erased <:< Tag[Ask].erased))
            assert(!(Tag[Say].erased <:< Tag[Ask].erased))
        }
    }

    "recover" - {

        object Boom extends RuntimeException("boom", null, false, false)

        "a region's recovery clause answers a throw raised in its extent" in {
            val body: Int < Ask = ask.map(_ => (throw Boom): Int)
            val r: Int < Any    = ArrowEffect.handleCont(Tag[Ask], body)([C] => (_, cont) => cont(1), a => a, _ => Maybe(-1))
            assert(eval(r) == -1)
        }

        "a stateful region's recovery clause receives the live state, not the install-time state" in {
            val body: Int < Ask = ask.map(_ => ask.map(_ => (throw Boom): Int))
            val r: Int < Any = ArrowEffect.handleLoopState(Tag[Ask], 0, body)(
                [C] => (s, _) => Loop.continue(s + 1, 1: Int < Any),
                (_, a) => a,
                (s, _) => Maybe(-100 - s)
            )

            assert(eval(r) == -102)
        }

        "Absent declines and the failure unwinds to the enclosing region" in {
            val body: Int < (Ask & Say) = ask.map(_ => (throw Boom): Int)
            val inner: Int < Say        = ArrowEffect.handleCont(Tag[Ask], body)([C] => (_, cont) => cont(1), a => a)
            val r: Int < Any = ArrowEffect.handleCont(Tag[Say], inner)(
                [C] => (_, cont) => cont(()),
                a => a,
                _ => Maybe(-7)
            )
            assert(eval(r) == -7)
        }

        "a settled input's done throw reaches the recovery clause" in {
            val r: Int < Any = ArrowEffect.handleCont(Tag[Ask], 42: Int < Ask)(
                [C] => (_, cont) => cont(1),
                _ => (throw Boom): Int,
                _ => Maybe(-1)
            )
            assert(eval(r) == -1)
        }
    }

    "handleFirst" - {
        "answers the first operation and hands the raw remainder" in {
            val v = ask.map(a => ask.map(b => a * 10 + b))
            val r: Int < Any = ArrowEffect.handleFirst(Tag[Ask], v)(
                [C] => (_, cont) => ArrowEffect.handleCont(Tag[Ask], cont(4))([C2] => (_, k) => k(2), a => a),
                a => a
            )
            assert(eval(r) == 42)
        }

        "a body that completes without the effect takes done" in {
            val r: Int < Any = ArrowEffect.handleFirst(Tag[Ask], 5: Int < Ask)([C] => (_, _) => -1, a => a * 2)
            assert(eval(r) == 10)
        }

        "the clause may end the computation without resuming" in {
            var reached = false
            val v = ask.map { a =>
                reached = true
                a
            }
            val r: Int < Any = ArrowEffect.handleFirst(Tag[Ask], v)([C] => (_, _) => -1, a => a)
            assert(eval(r) == -1)
            assert(!reached)
        }

        "re-handling the remainder round by round sees every operation" in {
            def collect(v: Int < Ask, acc: List[Int]): List[Int] < Any =
                ArrowEffect.handleFirst(Tag[Ask], v)(
                    [C] => (_, cont) => collect(cont(acc.size + 1), acc :+ (acc.size + 1)),
                    a => acc :+ a
                )
            val v = ask.map(a => ask.map(b => a * 10 + b))
            assert(eval(collect(v, Nil)) == List(1, 2, 12))
        }
    }

    "dispatchFirst" - {
        "reports the first operation through a region and a handed-in deferral" in {
            val inner: Int < (Ask & Say) = ask.map(a => a)
            val idle: Int < Ask          = ArrowEffect.handleCont(Tag[Say], inner)([C] => (_, k) => k(()), a => a)
            val deferred: Int < Ask      = Effect.defer(idle, Arrow.id)
            var seen                     = 0
            ArrowEffect.dispatchFirst(Tag[Ask], deferred)([C] => _ => seen += 1)
            assert(seen == 1)

            assert(eval(ArrowEffect.handleCont(Tag[Ask], deferred)([C] => (_, k) => k(42), a => a)) == 42)
        }

        "a foreign operation standing first is not reported" in {
            var seen = 0
            ArrowEffect.dispatchFirst(Tag[Say], ask.map(_ + 1))([C] => _ => seen += 1)
            assert(seen == 0)
        }

        "queries in the dispatch direction" in {
            var seen = 0

            ArrowEffect.dispatchFirst(Tag[AskSub], ask)([C] => _ => seen += 1)

            ArrowEffect.dispatchFirst(Tag[Ask], askSub)([C] => _ => seen += 10)
            assert(seen == 1)
        }

        "a settled value reports nothing" in {
            var seen = 0
            ArrowEffect.dispatchFirst(Tag[Ask], 42: Int < Ask)([C] => _ => seen += 1)
            assert(seen == 0)
        }

        "sees through a parked slice" in {
            val body: Int < Ask =
                ask.map { a =>
                    kyo.discard(Safepoint.stop(Thread.currentThread()))
                    Safepoint.deadline(java.lang.System.currentTimeMillis() - 1)
                    Effect.defer(ask.map(b => a + b), Arrow.id)
                }
            val handled = ArrowEffect.handleCont(Tag[Ask], body)([C] => (_, k) => k(21), a => a)
            val parked  = Eval.partial(handled)
            var seen    = 0
            ArrowEffect.dispatchFirst(Tag[Ask], parked)([C] => _ => seen += 1)
            assert(seen == 1)
            assert(eval(parked) == 42)
        }
    }

    "contracts" - {
        "a clause raising a foreign effect is answered by the outer handler across the region" in {
            val v: Int < (Ask & Say) = ask.map(a => say("x").map(_ => ask.map(b => a + b)))
            val sayHandled: Int < Ask =
                ArrowEffect.handleCont(Tag[Say], v)([C] => (_, cont) => ask.map(extra => cont(()).map(_ + extra)), a => a)
            val r: Int < Any = ArrowEffect.handleCont(Tag[Ask], sayHandled)([C] => (_, cont) => cont(10), a => a)
            assert(eval(r) == 30)
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
            assert(eval(r) == 30)
            assert(runs == 2)
        }

        "a handler may run another handle inside its answer" in {
            val inner: Int < Say = say("s").map(_ => 5)
            val r: Int < Say = ArrowEffect.handleCont(Tag[Ask], ask.map(_ + 1))(
                [C] =>
                    (_, cont) =>
                        val answered: Int < Any = ArrowEffect.handleCont(Tag[Say], inner)([D] => (_, c) => c(()), a => a)
                        answered.map(cont(_))
                ,
                a => a
            )
            val out: Int < Any = ArrowEffect.handleCont(Tag[Say], r)([C] => (_, cont) => cont(()), a => a)
            assert(eval(out) == 6)
        }

        "a computation held as a value passes through a handler untouched" in {
            val payload: Int < Any   = (1: Int < Any).map(_ + 1)
            val v: (Int < Any) < Ask = ask.map(_ => box(payload))
            val r: (Int < Any) < Any = ArrowEffect.handleCont(Tag[Ask], v)([C] => (_, cont) => cont(0), a => box(a))
            assert(eval(eval(r)) == 2)
        }

        "a throw in the handler surfaces at eval" in {
            val r: Int < Any = ArrowEffect.handleCont(Tag[Ask], ask.map(_ + 1))(
                [C] => (_, _) => (throw new RuntimeException("boom")): Int < Ask,
                a => a
            )
            intercept[RuntimeException] {
                val _ = eval(r)
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
            val back: (Int < Say) < Any          = eval(twice)
            val r: Int < Any                     = ArrowEffect.handleCont(Tag[Say], eval(back))([C] => (_, cont) => cont(()), a => a)
            assert(eval(r) == 1)
        }

        "mapping over a double-boxed computation sees the once-boxed value" in {
            val inner: Int < Say                 = say("x").map(_ => 1)
            val twice: ((Int < Say) < Any) < Any = box(box(inner))
            def widen[A, S](v: A < S): A < S     = v
            val unbox = (once: (Int < Say) < Any) =>
                eval(ArrowEffect.handleCont(Tag[Say], eval(once))([C] => (_, cont) => cont(()), a => a))
            val r: Int < Any = widen(twice).map(once => unbox(once))
            assert(eval(r) == 1)
        }

        "a pending computation held as a value crosses a handler boxed" in {
            val payload: Int < Say         = say("p").map(_ => 7)
            val v: (Int < Say) < Ask       = ask.map(_ => box(payload))
            val handled: (Int < Say) < Any = ArrowEffect.handleCont(Tag[Ask], v)([C] => (_, cont) => cont(0), a => box(a))
            val r: Int < Any               = ArrowEffect.handleCont(Tag[Say], eval(handled))([C] => (_, cont) => cont(()), a => a)
            assert(eval(r) == 7)
        }

        "a stateful handler passes a pending computation value through intact" in {
            val payload: Int < Say   = say("p").map(_ => 7)
            val v: (Int < Say) < Ask = ask.map(_ => box(payload))
            val r: (Int < Say) < Any = ArrowEffect.handleLoopState(Tag[Ask], 0, v)(
                [C] => (state, _) => Loop.continue(state + 1, 0: Int < Any),
                (_, a) => box(a)
            )
            val boxed          = eval(r)
            val out: Int < Any = ArrowEffect.handleCont(Tag[Say], boxed)([C] => (_, cont) => cont(()), a => a)
            assert(eval(out) == 7)
        }
    }

    "a handler stepping a rescue at the exact budget boundary floats it outward" in {
        def nest(n: Int): Int < Any =
            if n == 0 then
                ArrowEffect.handleCont(Tag[Ask], ask.map(_ + 1))([C] => (_, cont) => cont(41), a => a)
            else (0: Int < Any).map(_ => nest(n - 1))
        assert(eval(nest(Period)) == 42)
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
            assert(eval(outer) == 11)
            assert(outerCount == 0)
        }

        "settled inputs pass through strictly" in {
            val v: Int < Ask     = 42
            val cont: Int < Any  = ArrowEffect.handleCont(Tag[Ask], v)([C] => (_, cont) => cont(0), a => a)
            val loop: Int < Any  = ArrowEffect.handleLoop(Tag[Ask], v)([C] => _ => Loop.continue((), 0: Int < Any), a => a)
            val state: Int < Any = ArrowEffect.handleLoopState(Tag[Ask], 7, v)([C] => (s, _) => Loop.continue(s, 0: Int < Any), (_, a) => a)
            assert(cont.evalNow == Maybe(42))
            assert(loop.evalNow == Maybe(42))
            assert(state.evalNow == Maybe(42))
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
            assert(eval(r) == 42)
            assert(order == List("answer", "after"))
        }

        "evaluation recovers after a thrown handler" in {
            intercept[RuntimeException] {
                val failing: Int < Any = ArrowEffect.handleCont(Tag[Ask], ask.map(_ + 1))(
                    [C] => (_, _) => (throw new RuntimeException("boom")): Int < Ask,
                    a => a
                )
                val _ = eval(failing)
            }
            val r: Int < Any = ArrowEffect.handleCont(Tag[Ask], ask.map(_ + 1))([C] => (_, cont) => cont(41), a => a)
            assert(eval(r) == 42)
        }
    }

end ArrowEffectTest
