package kernel2test

import kyo.Maybe
import kyo.Tag
import kyo.kernel2.*
import kyo.test.Test
import language.implicitConversions

sealed trait EffAsk extends ArrowEffect[Const[Unit], Const[Int]]

class EffectTest extends Test[Any]:

    def ask: Int < EffAsk =
        ArrowEffect.suspend[Any](Tag[EffAsk], ())

    def park(v: Int < EffAsk): Int < EffAsk =
        ArrowEffect.handlePartial(Tag[EffAsk], v)(
            [C] => (input, cont) => Maybe.Absent
        )

    def resume(v: Int < EffAsk, answer: Int): Int =
        ArrowEffect.handle(Tag[EffAsk], v)(
            [C] => (input, cont) => cont(answer)
        ).eval

    "defer does not run at construction" in {
        var ran = false
        val v = Effect.defer {
            ran = true
            1
        }
        assert(!ran)
        assert(v.eval == 1)
        assert(ran)
    }

    "defer runs once per drive" in {
        var runs = 0
        val v = Effect.defer {
            runs += 1
            runs
        }
        assert(v.eval == 1)
        assert(v.eval == 2)
        assert(runs == 2)
    }

    "defer composes with map and stays lazy" in {
        var log = List.empty[String]
        val v = Effect.defer {
            log :+= "defer"
            10
        }.map { n =>
            log :+= "map"
            n + 1
        }
        assert(log == Nil)
        assert(v.eval == 11)
        assert(log == List("defer", "map"))
    }

    "defer result can suspend" in {
        val v = Effect.defer(ask.map(_ + 1))
        assert(resume(park(v), 41) == 42)
    }

    "deferred recursion is stack safe" in {
        def loop(i: Int): Int < Any =
            if i == 0 then 0
            else Effect.defer(loop(i - 1))
        assert(loop(100000).eval == 0)
    }

    "defer propagates exceptions at drive time" in {
        val v: Int < Any = Effect.defer((throw new RuntimeException("boom")): Int)
        val thrown =
            try
                val _ = v.eval
                false
            catch case e: RuntimeException => e.getMessage == "boom"
        assert(thrown)
    }

    "bracket releases on completion" in {
        var log = List.empty[String]
        val v = Effect.bracket {
            log :+= "acq"
            42
        } { _ =>
            log :+= "rel"
            ()
        } { r =>
            r + 1
        }
        assert(v.eval == 43)
        assert(log == List("acq", "rel"))
    }

    "bracket releases exactly once across a park" in {
        var log = List.empty[String]
        val v = Effect.bracket {
            log :+= "acq"
            42
        } { _ =>
            log :+= "rel"
            ()
        } { r =>
            ask.map(a => a + r)
        }
        val parked = park(v)
        assert(log == List("acq"))
        assert(resume(parked, 100) == 142)
        assert(log == List("acq", "rel"))
    }

    "bracket releases on exception" in {
        var log = List.empty[String]
        val v = Effect.bracket {
            log :+= "acq"
            42
        } { _ =>
            log :+= "rel"
            ()
        } { _ =>
            (throw new RuntimeException("boom")): Int
        }
        val thrown =
            try
                val _ = v.eval
                false
            catch case e: RuntimeException => e.getMessage == "boom"
        assert(thrown)
        assert(log == List("acq", "rel"))
    }

    "nested brackets release in reverse order" in {
        var log = List.empty[String]
        def mk(name: String)(body: Int => Int < Any): Int < Any =
            Effect.bracket {
                log :+= s"acq-$name"
                1
            } { _ =>
                log :+= s"rel-$name"
                ()
            }(body)
        val v = mk("outer")(_ => mk("inner")(r => r + 1))
        assert(v.eval == 2)
        assert(log == List("acq-outer", "acq-inner", "rel-inner", "rel-outer"))
    }

    "bracket acquire runs per drive" in {
        var acquisitions = 0
        val v = Effect.bracket {
            acquisitions += 1
            acquisitions
        }(_ => ())(r => r)
        assert(v.eval == 1)
        assert(v.eval == 2)
    }

    "catching intercepts an exception at construction" in {
        val v = Effect.catching[Int, Any, Int, Any] {
            throw new RuntimeException("boom")
        }(_ => 42)
        assert(v.eval == 42)
    }

    "catching passes through when nothing throws" in {
        val v = Effect.catching((1: Int < Any).map(_ + 1))(_ => -1)
        assert(v.eval == 2)
    }

    "catching intercepts a throw in a frame after resume" in {
        val program: Int < EffAsk =
            ask.map(x => if x > 0 then throw new RuntimeException("pos") else x)
        val wrapped = Effect.catching(program)(_ => 99)
        assert(resume(wrapped, 1) == 99)
        assert(resume(wrapped, -1) == -1)
    }

    "catching intercepts a throw inside a deferred thunk" in {
        val v = Effect.catching(Effect.defer[Int, Any] {
            throw new RuntimeException("late")
        })(_ => 7)
        assert(v.eval == 7)
    }

    "catching hands the thrown exception to the handler" in {
        val ex              = new RuntimeException("original")
        var seen: Throwable = null
        val v = Effect.catching[Int, Any, Int, Any] {
            throw ex
        } { t =>
            seen = t
            0
        }
        assert(v.eval == 0)
        assert(seen eq ex)
    }

    "catching stays armed across multiple suspensions" in {
        val program: Int < EffAsk =
            ask.map(a => ask.map(b => if b > a then throw new RuntimeException("desc") else a - b))
        val wrapped   = Effect.catching(program)(_ => -100)
        var remaining = List(5, 9)
        val handled = ArrowEffect.handle(Tag[EffAsk], wrapped)(
            [C] =>
                (input, cont) =>
                    val a = remaining.head
                    remaining = remaining.tail
                    cont(a)
        )
        assert(handled.eval == -100)
    }

end EffectTest
