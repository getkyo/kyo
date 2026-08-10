package kyo.kernel

import kyo.Maybe
import kyo.Tag
import org.scalatest.freespec.AnyFreeSpec
import scala.annotation.tailrec

class ArrowEffectTest extends AnyFreeSpec:

    type Const[A] = [B] =>> A

    sealed trait Ask extends ArrowEffect[Const[Unit], Const[Int]]
    def ask: Int < Ask = ArrowEffect.suspend[Const[Unit], Const[Int], Ask, Any](Tag[Ask], ())

    sealed trait AskSub extends Ask
    def askSub: Int < Ask = ArrowEffect.suspend[Const[Unit], Const[Int], Ask, Any](Tag[AskSub].asInstanceOf[Tag[Ask]], ())

    sealed trait Say extends ArrowEffect[Const[String], Const[Unit]]
    def say(s: String): Unit < Say = ArrowEffect.suspend[Const[String], Const[Unit], Say, Any](Tag[Say], s)

    "handle" - {
        "answers a single operation" in {
            val v = ask.map(_ + 1)
            val r = ArrowEffect.handle(Tag[Ask], v)([X] => (_, cont) => cont(41))
            assert(r.eval == 42)
        }

        "eager: answering runs the continuation at the handle call" in {
            var ran = false
            val v = ask.map { a =>
                ran = true
                a + 1
            }
            val r = ArrowEffect.handle(Tag[Ask], v)([X] => (_, cont) => cont(41))
            assert(ran)
            assert(r.eval == 42)
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

        "installed after evalPartial answers the parked operation" in {
            val v      = ask.map(_ + 1)
            val parked = v.evalPartial(() => false)
            val r      = ArrowEffect.handle(Tag[Ask], parked)([X] => (_, cont) => cont(41))
            assert(r.eval == 42)
        }
    }

    "suspendWith" - {
        "suspends and maps in one node" in {
            val v = ArrowEffect.suspendWith[Const[Unit], Const[Int], Ask, Any, Int, Any](Tag[Ask], ())(_ + 1)
            val r = ArrowEffect.handle(Tag[Ask], v)([X] => (_, cont) => cont(41))
            assert(r.eval == 42)
        }

        "the node is its own continuation" in {
            val v = ArrowEffect.suspendWith[Const[Unit], Const[Int], Ask, Any, Int, Any](Tag[Ask], ())(_ + 1)
            v match
                case kyo: Kyo.Suspend[?, ?, ?, ?, ?, ?] => assert(kyo.cont eq kyo)
                case _                                  => fail("expected a suspension")
        }

        "deep recursion is stack safe" in {
            def loop(i: Int): Int < Ask =
                if i > 100000 then i
                else ArrowEffect.suspendWith[Const[Unit], Const[Int], Ask, Any, Int, Ask](Tag[Ask], ())(a => loop(i + a))
            val r = ArrowEffect.handle(Tag[Ask], loop(0))([X] => (_, cont) => cont(1))
            assert(r.eval == 100001)
        }

        "maps chain onto the node" in {
            val v = ArrowEffect.suspendWith[Const[Unit], Const[Int], Ask, Any, Int, Any](Tag[Ask], ())(_ + 1).map(_ * 2)
            val r = ArrowEffect.handle(Tag[Ask], v)([X] => (_, cont) => cont(20))
            assert(r.eval == 42)
        }

        "an effectful continuation suspends again" in {
            val v = ArrowEffect.suspendWith[Const[Unit], Const[Int], Ask, Any, Int, Ask](Tag[Ask], ())(a => ask.map(b => a + b))
            val r = ArrowEffect.handle(Tag[Ask], v)([X] => (_, cont) => cont(21))
            assert(r.eval == 42)
        }

        "a long map tower on the node evaluates in bounded stack" in {
            @tailrec def tower(v: Int < Ask, n: Int): Int < Ask =
                if n == 0 then v else tower(v.map(_ + 1), n - 1)
            val v = tower(ArrowEffect.suspendWith[Const[Unit], Const[Int], Ask, Any, Int, Any](Tag[Ask], ())(_ + 1), 1000000)
            val r = ArrowEffect.handle(Tag[Ask], v)([X] => (_, cont) => cont(0))
            assert(r.eval == 1000001)
        }
    }

    "resume" - {
        "answers every operation and resumes" in {
            val v = ask.map(a => ask.map(b => a + b))
            val r = ArrowEffect.resume(Tag[Ask], v)([X] => _ => 21)
            assert(r.eval == 42)
        }

        "context effects arise from resume handlers" in {
            def provide[A, S](value: Int)(v: A < (Ask & S)): A < S =
                ArrowEffect.resume(Tag[Ask], v)([X] => _ => value)
            assert(provide(42)(ask.map(_ + 1)).eval == 43)
        }

        "the innermost resume handler wins" in {
            def provide[A, S](value: Int)(v: A < (Ask & S)): A < S =
                ArrowEffect.resume(Tag[Ask], v)([X] => _ => value)
            assert(provide(1)(provide(2)(ask)).eval == 2)
        }

        "the binding stays in force across steps and foreign crossings" in {
            def provide[A, S](value: Int)(v: A < (Ask & S)): A < S =
                ArrowEffect.resume(Tag[Ask], v)([X] => _ => value)
            val v: Int < (Ask & Say) = ask.map(a => say("x").map(_ => ask.map(b => a + b)))
            val provided             = provide(7)(v)
            val r                    = ArrowEffect.handle(Tag[Say], provided)([X] => (_, cont) => cont(()))
            assert(r.eval == 14)
        }

        "an effectful answer suspends and resumes into the continuation" in {
            val v: Int < Ask        = ask.map(_ + 1)
            val answered: Int < Say = ArrowEffect.resume(Tag[Ask], v)([X] => _ => say("fetch").map(_ => 41))
            val r                   = ArrowEffect.handle(Tag[Say], answered)([X] => (_, cont) => cont(()))
            assert(r.eval == 42)
        }

        "deep operations are stack safe" in {
            def loop(n: Int): Int < Ask =
                if n == 0 then 0 else ask.map(_ => loop(n - 1))
            assert(ArrowEffect.resume(Tag[Ask], loop(100000))([X] => _ => 1).eval == 0)
        }
    }

    "stop" - {
        "ends the computation at the operation" in {
            var reached = false
            val v = ask.map { a =>
                reached = true
                a + 1
            }
            val r = ArrowEffect.stop(Tag[Ask], v)([X] => _ => -1)
            assert(r.eval == -1)
            assert(!reached)
        }

        "passes a settled computation through" in {
            val v: Int < Ask = 42
            assert(ArrowEffect.stop(Tag[Ask], v)([X] => _ => -1).eval == 42)
        }

        "remains installed across a foreign crossing" in {
            val v: Int < (Ask & Say) = say("x").map(_ => ask.map(_ + 1))
            val stopped              = ArrowEffect.stop(Tag[Ask], v)([X] => _ => -1)
            val r                    = ArrowEffect.handle(Tag[Say], stopped)([X] => (_, cont) => cont(()))
            assert(r.eval == -1)
        }
    }

    "loop" - {
        "threads state through resumptions" in {
            val v = ask.map(a => ask.map(b => a + b))
            val r = ArrowEffect.loop(Tag[Ask], 0, v)(
                [X] => (_, state, cont) => (state + 1, cont((state + 1) * 10))
            )
            assert(r.eval == (2, 30))
        }

        "keeps state across a foreign crossing" in {
            val v: Int < (Ask & Say) = ask.map(a => say("x").map(_ => ask.map(b => a + b)))
            val looped = ArrowEffect.loop(Tag[Ask], 0, v)(
                [X] => (_, state, cont) => (state + 1, cont((state + 1) * 10))
            )
            val r = ArrowEffect.handle(Tag[Say], looped)([X] => (_, cont) => cont(()))
            assert(r.eval == (2, 30))
        }

        "returns the final state with a settled value" in {
            val v: Int < Ask = 42
            val r            = ArrowEffect.loop(Tag[Ask], 7, v)([X] => (_, state, cont) => (state, cont(0)))
            assert(r.eval == (7, 42))
        }
    }

    "partial" - {
        "answers operations while the check allows" in {
            val v = ask.map(a => ask.map(b => a + b))
            val r = ArrowEffect.partial(Tag[Ask], v)([X] => (_, cont) => Maybe(cont(21)))
            assert(ArrowEffect.handle(Tag[Ask], r)([X] => (_, cont) => cont(0)).eval == 42)
        }

        "parks at the first refused operation and re-enters" in {
            var answered = 0
            val v        = ask.map(a => ask.map(b => a + b))
            val first = ArrowEffect.partial(Tag[Ask], v)(
                [X] =>
                    (_, cont) =>
                        if answered == 0 then
                            answered += 1
                            Maybe(cont(21))
                        else Maybe.Absent
            )
            val second = ArrowEffect.partial(Tag[Ask], first)([X] => (_, cont) => Maybe(cont(21)))
            assert(second.asInstanceOf[Int < Any].eval == 42)
            assert(answered == 1)
        }
    }

    "eval throws on an unhandled suspension" in {
        intercept[IllegalStateException] {
            ask.asInstanceOf[Int < Any].eval
        }
    }

    "contracts" - {
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
            val v: (Int < Any) < Ask = ask.map(_ => payload)
            val r                    = ArrowEffect.handle(Tag[Ask], v)([X] => (_, cont) => cont(0))
            assert(r.eval.eval == 2)
        }

        "partial parks and resume finishes the remainder" in {
            val v      = ask.map(a => ask.map(b => a + b))
            val parked = ArrowEffect.partial(Tag[Ask], v)([X] => (_, _) => kyo.Maybe.Absent)
            val r      = ArrowEffect.resume(Tag[Ask], parked)([X] => _ => 21)
            assert(r.eval == 42)
        }

        "the innermost stop wins under nested same-tag handlers" in {
            var outerReached = false
            val inner        = ArrowEffect.stop(Tag[Ask], ask.map(_ + 1))([X] => _ => -1)
            val outer = ArrowEffect.stop(Tag[Ask], inner.asInstanceOf[Int < Ask])(
                [X] =>
                    _ =>
                        outerReached = true
                        -2
            )
            assert(outer.eval == -1)
            assert(!outerReached)
        }

        "a throw in the handler surfaces at the handle call" in {
            intercept[RuntimeException] {
                val _ = ArrowEffect.handle(Tag[Ask], ask.map(_ + 1))(
                    [X] => (_, _) => (throw new RuntimeException("boom")): Int < Ask
                )
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
            val once: (Int < Say) < Any          = inner
            val twice: ((Int < Say) < Any) < Any = once
            val back: (Int < Say) < Any          = twice.eval
            val r                                = ArrowEffect.handle(Tag[Say], back.eval)([X] => (_, cont) => cont(()))
            assert(r.eval == 1)
        }

        "mapping over a double-boxed computation sees the once-boxed value" in {
            val inner: Int < Say                 = say("x").map(_ => 1)
            val twice: ((Int < Say) < Any) < Any = (inner: (Int < Say) < Any)
            def widen[A, S](v: A < S): A < S     = v
            val unbox = (once: (Int < Say) < Any) =>
                ArrowEffect.handle(Tag[Say], once.eval)([X] => (_, cont) => cont(())).eval
            val r = widen(twice).map(once => unbox(once))
            assert(r.eval == 1)
        }

        "a pending computation held as a value crosses a handler boxed" in {
            val payload: Int < Say         = say("p").map(_ => 7)
            val v: (Int < Say) < Ask       = ask.map(_ => payload)
            val handled: (Int < Say) < Any = ArrowEffect.handle(Tag[Ask], v)([X] => (_, cont) => cont(0))
            val r                          = ArrowEffect.handle(Tag[Say], handled.eval)([X] => (_, cont) => cont(()))
            assert(r.eval == 7)
        }

        "loop returns a pending computation value intact in its tuple" in {
            val payload: Int < Say   = say("p").map(_ => 7)
            val v: (Int < Say) < Ask = ask.map(_ => payload)
            val r                    = ArrowEffect.loop(Tag[Ask], 0, v)([X] => (_, state, cont) => (state + 1, cont(0)))
            val (state, boxed)       = r.eval
            assert(state == 1)
            assert(ArrowEffect.handle(Tag[Say], boxed)([X] => (_, cont) => cont(())).eval == 7)
        }
    }

    "a handler stepping a rescue at the exact budget boundary floats it outward" in {
        def nest(n: Int): Int < Any =
            if n == 0 then
                ArrowEffect.handle(Tag[Ask], ask.map(_ + 1))([X] => (_, cont) => cont(41))
            else (0: Int < Any).map(_ => nest(n - 1))
        assert(nest(Safepoint.Period).eval == 42)
    }

    "coverage" - {
        "loop forks state per continuation invocation" in {
            val v = ask.map(a => a * 10)
            val r = ArrowEffect.loop(Tag[Ask], 0, v)(
                [X] =>
                    (_, state, cont) =>
                        val (s1, v1) = (state + 1, cont(1))
                        (s1, v1.map(a => cont(2).map(b => a + b)))
            )
            assert(r.eval == (1, 30))
        }

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

        "settled inputs pass through handle, resume, and partial" in {
            val v: Int < Ask = 42
            assert(ArrowEffect.handle(Tag[Ask], v)([X] => (_, cont) => cont(0)).eval == 42)
            assert(ArrowEffect.resume(Tag[Ask], v)([X] => _ => 0).eval == 42)
            assert(ArrowEffect.partial(Tag[Ask], v)([X] => (_, cont) => kyo.Maybe(cont(0))).asInstanceOf[Int < Any].eval == 42)
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

        "a stop replacement may suspend on the same effect and stops again" in {
            var calls = 0
            val r = ArrowEffect.stop(Tag[Ask], ask.map(_ + 1))(
                [X] =>
                    _ =>
                        calls += 1
                        if calls == 1 then ask.map(_ + 100) else -1
            )
            assert(r.eval == -1)
            assert(calls == 2)
        }

        "an operation after a foreign crossing is not answered by an earlier partial call" in {
            var answered             = 0
            val v: Int < (Ask & Say) = ask.map(a => say("x").map(_ => ask.map(b => a + b)))
            val first = ArrowEffect.partial(Tag[Ask], v)(
                [X] =>
                    (_, cont) =>
                        answered += 1
                        kyo.Maybe(cont(21))
            )
            assert(answered == 1)
            val handledSay = ArrowEffect.handle(Tag[Say], first)([X] => (_, cont) => cont(()))
            val r          = ArrowEffect.handle(Tag[Ask], handledSay)([X] => (_, cont) => cont(21))
            assert(r.eval == 42)
            assert(answered == 1)
        }

        "evaluation recovers after a thrown handler" in {
            intercept[RuntimeException] {
                val _ = ArrowEffect.handle(Tag[Ask], ask.map(_ + 1))(
                    [X] => (_, _) => (throw new RuntimeException("boom")): Int < Ask
                )
            }
            val r = ArrowEffect.handle(Tag[Ask], ask.map(_ + 1))([X] => (_, cont) => cont(41))
            assert(r.eval == 42)
        }
    }
end ArrowEffectTest
