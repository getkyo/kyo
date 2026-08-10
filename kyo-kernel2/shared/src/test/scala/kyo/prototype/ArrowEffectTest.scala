package kyo.prototype

import kyo.Tag
import kyo.test.Test

class ArrowEffectTest extends Test[Any]:

    type Const[A] = [B] =>> A

    sealed trait Ask extends ArrowEffect[Const[Unit], Const[Int]]
    def ask: Int < Ask = ArrowEffect.suspend[Const[Unit], Const[Int], Ask, Any](Tag[Ask], ())

    sealed trait Say extends ArrowEffect[Const[String], Const[Unit]]
    def say(s: String): Unit < Say = ArrowEffect.suspend[Const[String], Const[Unit], Say, Any](Tag[Say], s)

    "handle answers a single operation" in {
        val v = ask.map(_ + 1)
        val r = ArrowEffect.handle(Tag[Ask], v)([X] => (_, cont) => cont(41))
        assert(r.eval == 42)
    }

    "handle is eager: answering runs the continuation at the handle call" in {
        var ran = false
        val v = ask.map { a =>
            ran = true
            a + 1
        }
        val r = ArrowEffect.handle(Tag[Ask], v)([X] => (_, cont) => cont(41))
        assert(ran)
        assert(r.eval == 42)
    }

    "handle stays in force across resumptions" in {
        val v = ask.map(a => ask.map(b => a + b))
        val r = ArrowEffect.handle(Tag[Ask], v)([X] => (_, cont) => cont(21))
        assert(r.eval == 42)
    }

    "handle can end the computation without resuming" in {
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

    "a handler installed after evalPartial answers the parked operation" in {
        val v      = ask.map(_ + 1)
        val parked = v.evalPartial(() => false)
        val r      = ArrowEffect.handle(Tag[Ask], parked)([X] => (_, cont) => cont(41))
        assert(r.eval == 42)
    }

    "eval throws on an unhandled suspension" in {
        interceptThrown[IllegalStateException] {
            ask.asInstanceOf[Int < Any].eval
        }
    }
end ArrowEffectTest
