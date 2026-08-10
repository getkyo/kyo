package kyo.prototype

import kyo.Maybe
import kyo.Tag
import kyo.test.Test

class ArrowEffectTest extends Test[Any]:

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
        interceptThrown[IllegalStateException] {
            ask.asInstanceOf[Int < Any].eval
        }
    }
end ArrowEffectTest
