package kyo.kernel

import kyo.Maybe
import kyo.Tag
import kyo.kernel.internal.*
import org.scalatest.freespec.AnyFreeSpec
import scala.annotation.tailrec

class ArrowEffectTest extends AnyFreeSpec:

    type Const[A] = [B] =>> A

    sealed trait Ask extends ArrowEffect[Const[Unit], Const[Int]]
    def ask: Int < Ask = ArrowEffect.suspend[Any](Tag[Ask], ())

    sealed trait AskSub extends Ask
    def askSub: Int < Ask = ArrowEffect.suspend[Any](Tag[AskSub].asInstanceOf[Tag[Ask]], ())

    sealed trait Say extends ArrowEffect[Const[String], Const[Unit]]
    def say(s: String): Unit < Say = ArrowEffect.suspend[Any](Tag[Say], s)

    // holds a computation as a value: the generic parameter routes through
    // the runtime lift, which boxes pending values; the direct ascription is
    // rejected by the lift discipline
    def box[A](v: A): A < Any = v

    private val Period = 512

    "handle" - {
        "answers a single operation" in {
            val v = ask.map(_ + 1)
            val r = ArrowEffect.handle(Tag[Ask], v)([X] => (_, cont) => cont(41))
            assert(r.eval == 42)
        }

        "lazy: the handled computation is a value and answers at eval" in {
            var ran = false
            val v = ask.map { a =>
                ran = true
                a + 1
            }
            val r = ArrowEffect.handle(Tag[Ask], v)([X] => (_, cont) => cont(41))
            assert(!ran)
            assert(r.eval == 42)
            assert(ran)
        }

        "a long map tower on a pending suspension handles in bounded stack" in {
            @tailrec def tower(v: Int < Ask, n: Int): Int < Ask =
                if n == 0 then v else tower(v.map(_ + 1), n - 1)
            val v = tower(ask, 1000000)
            val r = ArrowEffect.handle(Tag[Ask], v)([X] => (_, cont) => cont(0))
            assert(r.eval == 1000000)
        }

        "stays in force across resumptions" in {
            val v = ask.map(a => ask.map(b => a + b))
            val r = ArrowEffect.handle(Tag[Ask], v)([X] => (_, cont) => cont(21))
            assert(r.eval == 42)
        }

        "stays in force across a foreign crossing with a trailing transform" in {
            val v: Int < (Ask & Say) = say("x").map(_ => ask).map(_ + 1)
            val r = ArrowEffect.handle(
                Tag[Say],
                ArrowEffect.handle(Tag[Ask], v)([X] => (_, cont) => cont(41))
            )([X] => (_, cont) => cont(()))
            assert(r.eval == 42)
        }

        "stays in force across a budget bounce with a trailing transform" in {
            def burn(n: Int): Int < Any =
                if n == 0 then
                    val v: Int < Ask = (0: Int < Any).map(_ => ask).map(_ + 1)
                    ArrowEffect.handle(Tag[Ask], v)([X] => (_, cont) => cont(41))
                else ((): Unit < Any).map(_ => burn(n - 1))
            assert(burn(512).eval == 42)
        }

        "can end the computation without resuming" in {
            var reached = false
            val v = ask.map { a =>
                reached = true
                a + 1
            }
            val r = ArrowEffect.handle(Tag[Ask], v)([X] => (_, _) => -1)
            assert(r.eval == -1)
            assert(!reached)
        }

        "handler state threads through resumptions" in {
            var count = 0
            val v     = ask.map(a => ask.map(b => a + b))
            val r = ArrowEffect.handle(Tag[Ask], v)(
                [X] =>
                    (_, cont) =>
                        count += 1
                        cont(count * 10)
            )
            assert(r.eval == 30)
            assert(count == 2)
        }

        "deep sequential operations are stack safe" in {
            def loop(n: Int): Int < Ask =
                if n == 0 then 0 else ask.map(_ => loop(n - 1))
            val r = ArrowEffect.handle(Tag[Ask], loop(100000))([X] => (_, cont) => cont(1))
            assert(r.eval == 0)
        }

        "a foreign operation passes through and keeps the handler attached" in {
            val v: Int < (Ask & Say) = ask.map(a => say(a.toString).map(_ => ask.map(b => a + b)))
            val handledAsk           = ArrowEffect.handle(Tag[Ask], v)([X] => (_, cont) => cont(21))
            val r                    = ArrowEffect.handle(Tag[Say], handledAsk)([X] => (_, cont) => cont(()))
            assert(r.eval == 42)
        }

        "nested handlers answer their own operations" in {
            val v =
                ask.map { a =>
                    say(a.toString).map(_ => a * 2)
                }
            val r = ArrowEffect.handle(
                Tag[Say],
                ArrowEffect.handle(Tag[Ask], v.asInstanceOf[Int < (Ask & Say)])([X] => (_, cont) => cont(21))
            )([X] => (s, cont) => cont(()))
            assert(r.eval == 42)
        }

        "answers operations of a subtype effect" in {
            val v: Int < Ask = askSub.map(_ + 1)
            val r            = ArrowEffect.handle(Tag[Ask], v)([X] => (_, cont) => cont(41))
            assert(r.eval == 42)
        }

        "installed after a partial evaluation answers the parked operation" in {
            val v      = ask.map(_ + 1)
            val parked = Eval.partial(v)
            val r      = ArrowEffect.handle(Tag[Ask], parked)([X] => (_, cont) => cont(41))
            assert(r.eval == 42)
        }
    }

    "handleLoop" - {
        "answers operations" in {
            val v = ask.map(a => ask.map(b => a + b))
            val r = ArrowEffect.handleLoop(Tag[Ask], v)([X] => _ => Loop.continue(21))
            assert(r.eval == 42)
        }

        "done ends the computation at the operation" in {
            var reached = false
            val v = ask.map { a =>
                reached = true
                a + 1
            }
            val r = ArrowEffect.handleLoop(Tag[Ask], v)([X] => _ => Loop.done(-1))
            assert(r.eval == -1)
            assert(!reached)
        }

        "an effectful answer resolves through an outer handler" in {
            val v: Int < Ask = ask.map(_ + 1)
            val looped       = ArrowEffect.handleLoop(Tag[Ask], v)([X] => _ => Loop.continue(say("fetch").map(_ => 41)))
            val r            = ArrowEffect.handle(Tag[Say], looped)([X] => (_, cont) => cont(()))
            assert(r.eval == 42)
        }

        "a clause may suspend before producing its outcome" in {
            var logged = 0
            val looped = ArrowEffect.handleLoop(Tag[Ask], ask.map(_ + 1))(
                [X] =>
                    _ =>
                        say("pre").map { _ =>
                            logged += 1
                            Loop.continue(41)
                    }
            )
            val r = ArrowEffect.handle(Tag[Say], looped)([X] => (_, cont) => cont(()))
            assert(r.eval == 42)
            assert(logged == 1)
        }

        "done climbs past an inner handler without running its remainder" in {
            var innerExit = false
            var reached   = false
            val program: Int < (Ask & Say) = say("m").map(_ => ask).map { a =>
                reached = true
                a + 1
            }
            val sayHandled = ArrowEffect.handle(Tag[Say], program)([X] => (_, cont) => cont(())).map { v =>
                innerExit = true
                v
            }
            val r = ArrowEffect.handleLoop(Tag[Ask], sayHandled)([X] => _ => Loop.done(-1))
            assert(r.eval == -1)
            assert(!reached)
            assert(!innerExit)
        }

        "the innermost handleLoop wins under nested same-tag handlers" in {
            val inner = ArrowEffect.handleLoop(Tag[Ask], ask.map(_ + 1))([X] => _ => Loop.continue(1))
            val outer = ArrowEffect.handleLoop(Tag[Ask], inner.asInstanceOf[Int < Ask])([X] => _ => Loop.continue(41))
            assert(outer.eval == 2)
        }

        "threads state through operations" in {
            val v = ask.map(a => ask.map(b => a * 10 + b))
            val r = ArrowEffect.handleLoop(Tag[Ask], 1, v)([X] => (_, state) => Loop.continue(state + 1, state))
            assert(r.eval == 12)
        }

        "state composes with done" in {
            def go(n: Int): Int < Ask =
                if n == 0 then 0 else ask.map(_ => go(n - 1))
            val r = ArrowEffect.handleLoop(Tag[Ask], 3, go(5))(
                [X] => (_, remaining) => if remaining > 0 then Loop.continue(remaining - 1, 1) else Loop.done(-1)
            )
            assert(r.eval == -1)
        }

        "a stateful clause may suspend before producing its outcome" in {
            var logged = 0
            val v      = ask.map(a => ask.map(b => a * 10 + b))
            val looped = ArrowEffect.handleLoop(Tag[Ask], 1, v)(
                [X] =>
                    (_, state) =>
                        say("pre").map { _ =>
                            logged += 1
                            Loop.continue(state + 1, state)
                    }
            )
            val r = ArrowEffect.handle(Tag[Say], looped)([X] => (_, cont) => cont(()))
            assert(r.eval == 12)
            assert(logged == 2)
        }

        "state survives an inner handler's exit" in {
            val program: Int < (Ask & Say) = ask.map(a => say("x").map(_ => a))
            val sayHandled                 = ArrowEffect.handle(Tag[Say], program)([X] => (_, cont) => cont(()))
            val v                          = sayHandled.map(a => ask.map(b => a * 10 + b))
            val r = ArrowEffect.handleLoop(Tag[Ask], 1, v.asInstanceOf[Int < Ask])(
                [X] => (_, state) => Loop.continue(state + 1, state)
            )
            assert(r.eval == 12)
        }

        "deep sequential operations are stack safe" in {
            def loop(n: Int): Int < Ask =
                if n == 0 then 0 else ask.map(_ => loop(n - 1))
            val r = ArrowEffect.handleLoop(Tag[Ask], loop(100000))([X] => _ => Loop.continue(1))
            assert(r.eval == 0)
        }

        "handles nested per recursion step in bounded stack" in {
            def go(n: Int): Int < Any =
                if n == 0 then 0
                else ArrowEffect.handleLoop(Tag[Ask], ask.map(_ => go(n - 1)))([X] => _ => Loop.continue(1))
            assert(go(100000).eval == 0)
        }

        "context effects arise from answering handlers" in {
            def provide[A, S](value: Int)(v: A < (Ask & S)): A < S =
                ArrowEffect.handleLoop(Tag[Ask], v)([X] => _ => Loop.continue(value))
            assert(provide(42)(ask.map(_ + 1)).eval == 43)
            assert(provide(1)(provide(2)(ask)).eval == 2)
        }

        "stays in force across a foreign crossing with a trailing transform" in {
            val v: Int < (Ask & Say) = say("x").map(_ => ask).map(_ + 1)
            val looped               = ArrowEffect.handleLoop(Tag[Ask], v)([X] => _ => Loop.continue(41))
            val r                    = ArrowEffect.handle(Tag[Say], looped)([X] => (_, cont) => cont(()))
            assert(r.eval == 42)
        }

        "the innermost done wins under nested same-tag handlers" in {
            var outerReached = false
            val inner        = ArrowEffect.handleLoop(Tag[Ask], ask.map(_ + 1))([X] => _ => Loop.done(-1))
            val outer = ArrowEffect.handleLoop(Tag[Ask], inner.asInstanceOf[Int < Ask])(
                [X] =>
                    _ =>
                        outerReached = true
                        Loop.done(-2)
            )
            assert(outer.eval == -1)
            assert(!outerReached)
        }
    }

    "suspendWith" - {
        "suspends and maps in one node" in {
            val v = ArrowEffect.suspendWith[Any](Tag[Ask], ())(_ + 1)
            val r = ArrowEffect.handle(Tag[Ask], v)([X] => (_, cont) => cont(41))
            assert(r.eval == 42)
        }

        "the node is its own continuation" in {
            val v = ArrowEffect.suspendWith[Any](Tag[Ask], ())(_ + 1)
            v match
                case kyo: Kyo.Suspend[?, ?, ?, ?, ?, ?] => assert(kyo.cont eq kyo)
                case _                                  => fail("expected a suspension")
        }

        "deep recursion is stack safe" in {
            def loop(i: Int): Int < Ask =
                if i > 100000 then i
                else ArrowEffect.suspendWith[Any](Tag[Ask], ())(a => loop(i + a))
            val r = ArrowEffect.handle(Tag[Ask], loop(0))([X] => (_, cont) => cont(1))
            assert(r.eval == 100001)
        }

        "maps chain onto the node" in {
            val v = ArrowEffect.suspendWith[Any](Tag[Ask], ())(_ + 1).map(_ * 2)
            val r = ArrowEffect.handle(Tag[Ask], v)([X] => (_, cont) => cont(20))
            assert(r.eval == 42)
        }

        "an effectful continuation suspends again" in {
            val v = ArrowEffect.suspendWith[Any](Tag[Ask], ())(a => ask.map(b => a + b))
            val r = ArrowEffect.handle(Tag[Ask], v)([X] => (_, cont) => cont(21))
            assert(r.eval == 42)
        }

        "a long map tower on the node evaluates in bounded stack" in {
            @tailrec def tower(v: Int < Ask, n: Int): Int < Ask =
                if n == 0 then v else tower(v.map(_ + 1), n - 1)
            val v = tower(ArrowEffect.suspendWith[Any](Tag[Ask], ())(_ + 1), 1000000)
            val r = ArrowEffect.handle(Tag[Ask], v)([X] => (_, cont) => cont(0))
            assert(r.eval == 1000001)
        }
    }

    "handleWith" - {
        "fuses the continuation into the region" in {
            val v = ask.map(_ + 1)
            val r = ArrowEffect.handleWith(Tag[Ask], v)([X] => (_, cont) => cont(41))(_ * 10)
            assert(r.eval == 420)
        }

        "the continuation runs outside the scope" in {
            val v     = ask.map(_ + 1)
            val r     = ArrowEffect.handleWith(Tag[Ask], v)([X] => (_, cont) => cont(41))(a => ask.map(_ + a))
            val outer = ArrowEffect.handle(Tag[Ask], r)([X] => (_, cont) => cont(1000))
            assert(outer.eval == 1042)
        }

        "a settled input applies the continuation strictly" in {
            var ran = false
            val r = ArrowEffect.handleWith(Tag[Ask], box(42))([X] => (_, cont) => cont(0)) { a =>
                ran = true
                a + 1
            }
            assert(ran)
            assert(r.eval == 43)
        }
    }

    "handleLoopWith" - {
        "fuses the continuation into the region" in {
            val v = ask.map(a => ask.map(b => a + b))
            val r = ArrowEffect.handleLoopWith(Tag[Ask], v)([X] => _ => Loop.continue(21))(_ + 1)
            assert(r.eval == 43)
        }

        "stateful: the continuation sees the final answer" in {
            val v = ask.map(a => ask.map(b => a + b))
            val r = ArrowEffect.handleLoopWith(Tag[Ask], 10, v)([X] => (_, s) => Loop.continue(s + 1, s))(_ * 2)
            assert(r.eval == 42)
        }

        "a settled input applies the continuation strictly" in {
            val r = ArrowEffect.handleLoopWith(Tag[Ask], box(41))([X] => _ => Loop.continue(0))(_ + 1)
            assert(r.eval == 42)
        }

        "a parked stateful region resumes with its state and fused exit" in {
            val v: Int < (Ask & Say) = ask.map(_ => say("x")).map(_ => ask)
            val fused =
                ArrowEffect.handleLoopWith(Tag[Ask], 10, v)([X] => (_, s) => Loop.continue(s + 1, s))(_ * 2)
            val parked = Eval.partial(fused)
            assert(parked.evalNow.isEmpty)
            assert(ArrowEffect.handle(Tag[Say], parked)([X] => (_, cont) => cont(())).eval == 22)
        }
    }

    "handlePartial" - {
        "answers operations while the clause allows" in {
            val v = ask.map(a => ask.map(b => a + b))
            val r = ArrowEffect.handlePartial(Tag[Ask], v)([X] => (_, cont) => Maybe(cont(21)))
            assert(ArrowEffect.handle(Tag[Ask], r)([X] => (_, cont) => cont(0)).eval == 42)
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
            assert(ArrowEffect.handle(Tag[Ask], second)([X] => (_, cont) => cont(0)).eval == 42)
            assert(answered == 1)
        }

        "parks and an answering handler finishes the remainder" in {
            val v      = ask.map(a => ask.map(b => a + b))
            val parked = ArrowEffect.handlePartial(Tag[Ask], v)([X] => (_, _) => Maybe.Absent)
            val r      = ArrowEffect.handleLoop(Tag[Ask], parked)([X] => _ => Loop.continue(21))
            assert(r.eval == 42)
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
            val handledSay = ArrowEffect.handle(Tag[Say], first)([X] => (_, cont) => cont(()))
            val r          = ArrowEffect.handle(Tag[Ask], handledSay)([X] => (_, cont) => cont(21))
            assert(r.eval == 42)
            assert(answered == 1)
        }

        "parks at a region node without evaluating it" in {
            val region = ArrowEffect.handleLoop(Tag[Ask], ask.map(_ + 1))([X] => _ => Loop.continue(41))
            val parked = ArrowEffect.handlePartial(Tag[Ask], region)([X] => (_, cont) => Maybe(cont(0)))
            assert(parked.evalNow.isEmpty)
            assert(ArrowEffect.handle(Tag[Ask], parked)([X] => (_, cont) => cont(0)).eval == 42)
        }

        "parks at a stateful region node without evaluating it" in {
            val region =
                ArrowEffect.handleLoop(Tag[Ask], 10, ask.map(a => ask.map(b => a * 100 + b)))(
                    [X] => (_, state) => Loop.continue(state + 1, state)
                )
            val parked = ArrowEffect.handlePartial(Tag[Ask], region)([X] => (_, cont) => Maybe(cont(0)))
            assert(parked.evalNow.isEmpty)
            assert(ArrowEffect.handle(Tag[Ask], parked)([X] => (_, cont) => cont(0)).eval == 1011)
        }

        "answers operations leading into a stateful region and leaves it intact" in {
            val v: Int < Ask = ask.map { outer =>
                ArrowEffect.handleLoop(Tag[Ask], 5, ask.map(a => ask.map(b => outer * 10000 + a * 100 + b)))(
                    [X] => (_, state) => Loop.continue(state + 1, state)
                )
            }
            val parked = ArrowEffect.handlePartial(Tag[Ask], v)([X] => (_, cont) => Maybe(cont(3)))
            assert(parked.evalNow.isEmpty)
            assert(ArrowEffect.handle(Tag[Ask], parked)([X] => (_, cont) => cont(0)).eval == 30506)
        }

        "passes a boxed computation through intact" in {
            val payload: Int < Say   = say("p").map(_ => 7)
            val v: (Int < Say) < Ask = ask.map(_ => box(payload))
            val r                    = ArrowEffect.handlePartial(Tag[Ask], v)([X] => (_, cont) => Maybe(cont(0)))
            val boxed                = ArrowEffect.handle(Tag[Ask], r)([X] => (_, cont) => cont(0)).eval
            assert(ArrowEffect.handle(Tag[Say], boxed)([X] => (_, cont) => cont(())).eval == 7)
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
            assert(ArrowEffect.handle(Tag[Ask], parked)([X] => (_, cont) => cont(21)).eval == 21)
        }
    }

    "eval throws on an unhandled suspension" in {
        intercept[IllegalStateException] {
            ask.asInstanceOf[Int < Any].eval
        }
    }

    "contracts" - {
        "a clause raising a foreign effect is answered by the outer handler across the region" in {
            val v: Int < (Ask & Say) = ask.map(a => say("x").map(_ => ask.map(b => a + b)))
            val sayHandled: Int < Ask =
                ArrowEffect.handle(Tag[Say], v)([X] => (_, cont) => ask.map(extra => cont(()).map(_ + extra)))
            val r = ArrowEffect.handle(Tag[Ask], sayHandled)([X] => (_, cont) => cont(10))
            assert(r.eval == 30)
        }

        "a continuation is a value: invoking it twice runs the rest twice" in {
            var runs = 0
            val v = ask.map { a =>
                runs += 1
                a * 10
            }
            val r = ArrowEffect.handle(Tag[Ask], v)(
                [X] => (_, cont) => cont(1).map(a => cont(2).map(b => a + b))
            )
            assert(r.eval == 30)
            assert(runs == 2)
        }

        "a handler may run another handle inside its answer" in {
            val inner: Int < Say = say("s").map(_ => 5)
            val r = ArrowEffect.handle(Tag[Ask], ask.map(_ + 1))(
                [X] =>
                    (_, cont) =>
                        val answered = ArrowEffect.handle(Tag[Say], inner)([Y] => (_, c) => c(()))
                        answered.map(cont)
            )
            assert(ArrowEffect.handle(Tag[Say], r)([X] => (_, cont) => cont(())).eval == 6)
        }

        "a computation held as a value passes through a handler untouched" in {
            val payload: Int < Any   = (1: Int < Any).map(_ + 1)
            val v: (Int < Any) < Ask = ask.map(_ => box(payload))
            val r                    = ArrowEffect.handle(Tag[Ask], v)([X] => (_, cont) => cont(0))
            assert(r.eval.eval == 2)
        }

        "a throw in the handler surfaces at eval" in {
            val r = ArrowEffect.handle(Tag[Ask], ask.map(_ + 1))(
                [X] => (_, _) => (throw new RuntimeException("boom")): Int < Ask
            )
            intercept[RuntimeException] {
                val _ = r.eval
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
            val back: (Int < Say) < Any          = twice.eval
            val r                                = ArrowEffect.handle(Tag[Say], back.eval)([X] => (_, cont) => cont(()))
            assert(r.eval == 1)
        }

        "mapping over a double-boxed computation sees the once-boxed value" in {
            val inner: Int < Say                 = say("x").map(_ => 1)
            val twice: ((Int < Say) < Any) < Any = box(box(inner))
            def widen[A, S](v: A < S): A < S     = v
            val unbox = (once: (Int < Say) < Any) =>
                ArrowEffect.handle(Tag[Say], once.eval)([X] => (_, cont) => cont(())).eval
            val r = widen(twice).map(once => unbox(once))
            assert(r.eval == 1)
        }

        "a pending computation held as a value crosses a handler boxed" in {
            val payload: Int < Say         = say("p").map(_ => 7)
            val v: (Int < Say) < Ask       = ask.map(_ => box(payload))
            val handled: (Int < Say) < Any = ArrowEffect.handle(Tag[Ask], v)([X] => (_, cont) => cont(0))
            val r                          = ArrowEffect.handle(Tag[Say], handled.eval)([X] => (_, cont) => cont(()))
            assert(r.eval == 7)
        }

        "a stateful handler passes a pending computation value through intact" in {
            val payload: Int < Say   = say("p").map(_ => 7)
            val v: (Int < Say) < Ask = ask.map(_ => box(payload))
            val r                    = ArrowEffect.handleLoop(Tag[Ask], 0, v)([X] => (_, state) => Loop.continue(state + 1, 0))
            val boxed                = r.eval
            assert(ArrowEffect.handle(Tag[Say], boxed)([X] => (_, cont) => cont(())).eval == 7)
        }
    }

    "a handler stepping a rescue at the exact budget boundary floats it outward" in {
        def nest(n: Int): Int < Any =
            if n == 0 then
                ArrowEffect.handle(Tag[Ask], ask.map(_ + 1))([X] => (_, cont) => cont(41))
            else (0: Int < Any).map(_ => nest(n - 1))
        assert(nest(Period).eval == 42)
    }

    "coverage" - {
        "the innermost handle wins under nested same-tag handlers" in {
            var outerCount = 0
            val inner      = ArrowEffect.handle(Tag[Ask], ask.map(_ + 1))([X] => (_, cont) => cont(10))
            val outer = ArrowEffect.handle(Tag[Ask], inner.asInstanceOf[Int < Ask])(
                [X] =>
                    (_, cont) =>
                        outerCount += 1
                        cont(100)
            )
            assert(outer.eval == 11)
            assert(outerCount == 0)
        }

        "settled inputs pass through strictly" in {
            val v: Int < Ask = 42
            assert(ArrowEffect.handle(Tag[Ask], v)([X] => (_, cont) => cont(0)).evalNow == Maybe(42))
            assert(ArrowEffect.handleLoop(Tag[Ask], v)([X] => _ => Loop.continue(0)).evalNow == Maybe(42))
            assert(ArrowEffect.handleLoop(Tag[Ask], 7, v)([X] => (_, s) => Loop.continue(s, 0)).evalNow == Maybe(42))
        }

        "a map chained after a parked handler runs after the handler completes" in {
            var order                = List.empty[String]
            val v: Int < (Ask & Say) = say("x").map(_ => ask.map(_ + 1))
            val handled = ArrowEffect.handle(Tag[Ask], v)(
                [X] =>
                    (_, cont) =>
                        order :+= "answer"
                        cont(41)
            )
            val chained = handled.map { r =>
                order :+= "after"
                r
            }
            val r = ArrowEffect.handle(Tag[Say], chained)([X] => (_, cont) => cont(()))
            assert(r.eval == 42)
            assert(order == List("answer", "after"))
        }

        "evaluation recovers after a thrown handler" in {
            intercept[RuntimeException] {
                val _ = ArrowEffect.handle(Tag[Ask], ask.map(_ + 1))(
                    [X] => (_, _) => (throw new RuntimeException("boom")): Int < Ask
                ).eval
            }
            val r = ArrowEffect.handle(Tag[Ask], ask.map(_ + 1))([X] => (_, cont) => cont(41))
            assert(r.eval == 42)
        }
    }
end ArrowEffectTest
