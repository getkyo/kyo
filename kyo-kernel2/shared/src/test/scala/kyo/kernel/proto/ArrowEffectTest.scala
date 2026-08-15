package kyo.kernel.proto

import kyo.Const
import kyo.Loop
import kyo.Loop.Outcome
import kyo.Loop.Outcome2
import kyo.Tag
import org.scalatest.freespec.AnyFreeSpec

class ArrowEffectTest extends AnyFreeSpec:

    sealed trait Ask extends ArrowEffect[Const[Unit], Const[Int]]
    def ask: Int < Ask = ArrowEffect.suspend[Any](Tag[Ask], ())

    sealed trait Say extends ArrowEffect[Const[String], Const[Unit]]
    def say(s: String): Unit < Say = ArrowEffect.suspend[Any](Tag[Say], s)

    def continue[A, O](v: A): Outcome[A, O] < Any =
        Loop.continue[A, O, Any](v).asInstanceOf[Outcome[A, O] < Any]

    def continue2[State, A, O](s: State, v: A): Outcome2[State, A, O] < Any =
        Loop.continue[State, A, O](s, v).asInstanceOf[Outcome2[State, A, O] < Any]

    "handleLoop" - {
        "answers every operation in place" in {
            def loop(i: Int): Int < Ask =
                if i < 3 then ask.map(a => loop(i + a)) else i
            val r: Int < Any = ArrowEffect.handleLoop(Tag[Ask], loop(0))([C] => _ => continue(1), a => a)
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
            val r: Int < Any = ArrowEffect.handleLoop(Tag[Ask], ask.map(_ + 1))([C] => _ => continue(41), a => a * 10)
            assert(Eval(r) == 420)
        }

        "a settled input applies done strictly" in {
            var ran = false
            val r: Int < Any = ArrowEffect.handleLoop(Tag[Ask], 41: Int < Ask)(
                [C] => _ => continue(0),
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
            val r: Int < Any = ArrowEffect.handleLoop(Tag[Ask], loop(100000))([C] => _ => continue(1), a => a)
            assert(Eval(r) == 0)
        }

        "the innermost region of a tag answers" in {
            val inner: Int < Any = ArrowEffect.handleLoop(Tag[Ask], ask)([C] => _ => continue(1), a => a)
            val outer: Int < Any = ArrowEffect.handleLoop(Tag[Ask], inner.asInstanceOf[Int < Ask])([C] => _ => continue(2), a => a)
            assert(Eval(outer) == 1)
        }

        "a foreign operation crosses the region in place" in {
            var order                   = List.empty[String]
            val body: Int < (Ask & Say) = say("a").map(_ => ask).map(i => i + 1)
            val inner: Int < Say        = ArrowEffect.handleLoop(Tag[Ask], body)([C] => _ => continue(41), a => a)
            val r: Int < Any = ArrowEffect.handleLoop(Tag[Say], inner)(
                [C] =>
                    s =>
                        order = s :: order
                        continue(())
                ,
                a => a
            )
            assert(Eval(r) == 42)
            assert(order == List("a"))
        }
    }

    "handle" - {
        "answers with the continuation in hand" in {
            val body         = ask.map(a => ask.map(b => a + b))
            val r: Int < Any = ArrowEffect.handle(Tag[Ask], body)([C] => (_, cont) => cont(10), a => a)
            assert(Eval(r) == 20)
        }

        "the captured continuation is multi-shot" in {
            val body = ask.map(_ * 2)
            val r: Int < Any = ArrowEffect.handle(Tag[Ask], body)(
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
            val r: Int < Any = ArrowEffect.handle(Tag[Ask], v)([C] => (_, _) => -1, a => a)
            assert(Eval(r) == -1)
            assert(!reached)
        }

        "a settled input applies done strictly" in {
            var ran = false
            val r: Int < Any = ArrowEffect.handle(Tag[Ask], 41: Int < Ask)(
                [C] => (_, cont) => cont(0),
                a =>
                    ran = true
                    a + 1
            )
            assert(ran)
            assert(Eval(r) == 42)
        }

        "done applies to the settled result" in {
            val r: Int < Any = ArrowEffect.handle(Tag[Ask], ask.map(_ + 1))([C] => (_, cont) => cont(41), a => a * 10)
            assert(Eval(r) == 420)
        }

        "deep sequential operations are stack safe" in {
            def loop(n: Int): Int < Ask =
                if n == 0 then 0 else ask.map(_ => loop(n - 1))
            val r: Int < Any = ArrowEffect.handle(Tag[Ask], loop(100000))([C] => (_, cont) => cont(1), a => a)
            assert(Eval(r) == 0)
        }
    }

    "handleLoopState" - {
        "threads state through operations" in {
            val v = ask.map(a => ask.map(b => a * 10 + b))
            val r: Int < Any = ArrowEffect.handleLoopState(Tag[Ask], 1, v)(
                [C] => (s, _) => continue2(s + 1, s),
                (_, a) => a
            )
            assert(Eval(r) == 12)
        }

        "done observes the final state" in {
            val v = ask.map(a => ask.map(b => a + b))
            val r: (Int, Int) < Any = ArrowEffect.handleLoopState(Tag[Ask], 10, v)(
                [C] => (s, _) => continue2(s + 1, s),
                (s, a) => (s, a)
            )
            assert(Eval(r) == (12, 21))
        }

        "Loop.done bypasses done" in {
            val v = ask.map(a => ask.map(b => a + b))
            val r: String < Any = ArrowEffect.handleLoopState(Tag[Ask], 0, v)(
                [C] => (s, _) => if s == 1 then Loop.done("stopped") else continue2(s + 1, 1),
                (s, a) => s"done $a"
            )
            assert(Eval(r) == "stopped")
        }

        "state survives a foreign crossing" in {
            val body: Int < (Ask & Say) = ask.map(a => say("x").map(_ => ask.map(b => a * 10 + b)))
            val inner: Int < Say = ArrowEffect.handleLoopState(Tag[Ask], 1, body)(
                [C] => (s, _) => continue2(s + 1, s),
                (_, a) => a
            )
            val r: Int < Any = ArrowEffect.handleLoop(Tag[Say], inner)([C] => _ => continue(()), a => a)
            assert(Eval(r) == 12)
        }

        "a settled input applies done strictly with the initial state" in {
            val r: Int < Any = ArrowEffect.handleLoopState(Tag[Ask], 7, 35: Int < Ask)(
                [C] => (s, _) => continue2(s, 0),
                (s, a) => s + a
            )
            assert(Eval(r) == 42)
        }
    }

    "suspendWith" - {
        "suspends and continues in one node" in {
            val v: Int < Ask = ArrowEffect.suspendWith[Any](Tag[Ask], ())(_ + 1)
            val r: Int < Any = ArrowEffect.handleLoop(Tag[Ask], v)([C] => _ => continue(41), a => a)
            assert(Eval(r) == 42)
        }

        "deep recursion is stack safe" in {
            def loop(i: Int): Int < Ask =
                if i > 100000 then i
                else ArrowEffect.suspendWith[Any](Tag[Ask], ())(a => loop(i + a))
            val r: Int < Any = ArrowEffect.handleLoop(Tag[Ask], loop(0))([C] => _ => continue(1), a => a)
            assert(Eval(r) == 100001)
        }
    }

    "a handle capture crossing an inner region" in {
        val inner: Int < Say = ArrowEffect.handleLoop(
            Tag[Ask],
            ask.map(a => say("x").map(_ => ask.map(b => a + b)))
        )([C] => _ => continue(1), a => a)
        val r: Int < Any = ArrowEffect.handle(Tag[Say], inner)([C] => (_, cont) => cont(()), a => a)
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
        )([C] => _ => continue(1), a => a)
        val r: Int < Any = ArrowEffect.handle(Tag[Say], inner)([C] => (_, cont) => cont(()), a => a)
        assert(Eval(r) == 2)
        assert(runs == 1)
    }

    "a crossed stateful region resumes with its in-flight state" in {
        val inner: Int < Say = ArrowEffect.handleLoopState(
            Tag[Ask],
            10,
            ask.map(a => say("x").map(_ => ask.map(b => a * 100 + b)))
        )([C] => (s, _) => continue2(s + 1, s), (_, a) => a)
        val r: Int < Any = ArrowEffect.handle(Tag[Say], inner)([C] => (_, cont) => cont(()), a => a)
        assert(Eval(r) == 1011)
    }

    "each shot of a multi-shot capture resumes from capture-time state" in {
        val inner: Int < Say = ArrowEffect.handleLoopState(
            Tag[Ask],
            0,
            ask.map(a => say("x").map(_ => ask.map(b => a * 10 + b)))
        )([C] => (s, _) => continue2(s + 1, s), (_, a) => a)
        val r: Int < Any = ArrowEffect.handle(Tag[Say], inner)(
            [C] => (_, cont) => cont(()).map(r1 => cont(()).map(r2 => r1 * 100 + r2)),
            a => a
        )
        assert(Eval(r) == 101)
    }

    "a map after the region applies to the result" in {
        val r: Int < Any = ArrowEffect.handleLoop(Tag[Ask], ask.map(_ + 1))([C] => _ => continue(41), a => a)
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
            val r: Int < Any = ArrowEffect.handle(Tag[Ask], v)([C] => (_, _) => 999, a => a * 2)
            assert(Eval(r) == 1998)
            assert(runs.forall(_ == 0))
    }

    "state survives dumping above a live region" in {
        def tower(v: Int < Ask, n: Int): Int < Ask =
            if n == 0 then v else tower(v.map(_ + 1), n - 1)
        val body = ask.map(a => tower(ask.map(b => a + b), 64))
        val r: Int < Any = ArrowEffect.handleLoopState(Tag[Ask], 10, body)(
            [C] => (st, _) => continue2(st + 1, st),
            (st, a) => a * 1000 + st
        )
        assert(Eval(r) == 85012)
    }

end ArrowEffectTest
