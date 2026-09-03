package outsidekyo

import kyo.Arrow
import kyo.Const
import kyo.Loop
import kyo.Maybe
import kyo.Tag
import kyo.kernel.<
import kyo.kernel.ArrowEffect
import kyo.kernel.ContextEffect
import kyo.kernel.Effect
import kyo.kernel.Isolate
import org.scalatest.freespec.AnyFreeSpec

class ProtoKernelTest extends AnyFreeSpec:

    sealed trait Ask   extends ArrowEffect[Const[Unit], Const[Int]]
    sealed trait Say   extends ArrowEffect[Const[String], Const[Unit]]
    sealed trait Level extends ContextEffect[Int]

    def ask: Int < Ask             = ArrowEffect.suspend[Any](Tag[Ask], ())
    def say(s: String): Unit < Say = ArrowEffect.suspend[Any](Tag[Say], s)

    def answer[A, S](v: A < (Ask & S)): A < S =
        ArrowEffect.handleCont(Tag[Ask], v)([C] => (_, cont) => cont(1), a => a)

    "the pending combinators" - {

        "map" in {
            val r = answer(ask.map(_ + 1)).eval
            assert(r == 2)
        }

        "flatMap" in {
            val r = answer(ask.flatMap(a => ask.map(b => a + b))).eval
            assert(r == 2)
        }

        "andThen" in {
            val r = answer(ask.andThen(ask.map(_ + 10))).eval
            assert(r == 11)
        }

        "unit" in {
            var seen = 0
            val v = ask.map { a =>
                seen = a; a
            }.unit
            val _ = answer(v).eval
            assert(seen == 1)
        }

        "flatten" in {
            val nested: (Int < Ask) < Any = kyo.Kyo.lift(ask.map(_ + 1))
            val r                         = answer(nested.flatten).eval
            assert(r == 2)
        }

        "eval" in {
            val v: Int < Any = 42
            assert(v.eval == 42)
        }
    }

    "handle, at every arity" - {

        "one" in {
            val r = ask.handle(v => answer(v).eval)
            assert(r == 1)
        }

        "two" in {
            val r = ask.handle(v => v.map(_ + 1), v => answer(v).eval)
            assert(r == 2)
        }

        "three" in {
            val r = ask.handle(v => v.map(_ + 1), v => v.map(_ + 1), v => answer(v).eval)
            assert(r == 3)
        }

        "four" in {
            val r = ask.handle(v => v.map(_ + 1), v => v.map(_ + 1), v => v.map(_ + 1), v => answer(v).eval)
            assert(r == 4)
        }

        "five" in {
            val r = ask.handle(
                v => v.map(_ + 1),
                v => v.map(_ + 1),
                v => v.map(_ + 1),
                v => v.map(_ + 1),
                v => answer(v).eval
            )
            assert(r == 5)
        }

        "six" in {
            val r = ask.handle(
                v => v.map(_ + 1),
                v => v.map(_ + 1),
                v => v.map(_ + 1),
                v => v.map(_ + 1),
                v => v.map(_ + 1),
                v => answer(v).eval
            )
            assert(r == 6)
        }

        "seven" in {
            val r = ask.handle(
                v => v.map(_ + 1),
                v => v.map(_ + 1),
                v => v.map(_ + 1),
                v => v.map(_ + 1),
                v => v.map(_ + 1),
                v => v.map(_ + 1),
                v => answer(v).eval
            )
            assert(r == 7)
        }

        "eight" in {
            val r = ask.handle(
                v => v.map(_ + 1),
                v => v.map(_ + 1),
                v => v.map(_ + 1),
                v => v.map(_ + 1),
                v => v.map(_ + 1),
                v => v.map(_ + 1),
                v => v.map(_ + 1),
                v => answer(v).eval
            )
            assert(r == 8)
        }

        "nine" in {
            val r = ask.handle(
                v => v.map(_ + 1),
                v => v.map(_ + 1),
                v => v.map(_ + 1),
                v => v.map(_ + 1),
                v => v.map(_ + 1),
                v => v.map(_ + 1),
                v => v.map(_ + 1),
                v => v.map(_ + 1),
                v => answer(v).eval
            )
            assert(r == 9)
        }

        "ten" in {
            val r = ask.handle(
                v => v.map(_ + 1),
                v => v.map(_ + 1),
                v => v.map(_ + 1),
                v => v.map(_ + 1),
                v => v.map(_ + 1),
                v => v.map(_ + 1),
                v => v.map(_ + 1),
                v => v.map(_ + 1),
                v => v.map(_ + 1),
                v => answer(v).eval
            )
            assert(r == 10)
        }
    }

    "the arrow constructors" - {

        "Arrow.apply" in {
            val plusOne: Arrow[Int, Int, Any] = Arrow[Int](x => x + 1)
            val r                             = answer(ask.map(x => plusOne(x))).eval
            assert(r == 2)
        }

        "Arrow.recursive" in {
            val sum: Arrow[Int, Int, Any] = Arrow.recursive((self, x) => if x == 0 then 0 else self(x - 1).map(_ + x))
            assert(sum(4).eval == 10)
        }
    }

    "the effect surface" - {

        "suspend" in {
            assert(answer(ask).eval == 1)
        }

        "suspendWith" in {
            val v = ArrowEffect.suspendWith[Any](Tag[Ask], ())(i => i + 5)
            assert(answer(v).eval == 6)
        }

        "handleCont" in {
            val v = ArrowEffect.handleCont(Tag[Ask], ask.map(_ + 1))([C] => (_, cont) => cont(2), a => a * 10)
            assert(v.eval == 30)
        }

        "handleContOperation" in {
            val v: Int < Any = ArrowEffect.handleContOperation(Tag[Ask], ask.map(_ + 1))(
                [X] => (_, cont) => cont(1.asInstanceOf[X]),
                a => a
            )
            assert(v.eval == 2)
        }

        "handleLoop" in {
            val v = ArrowEffect.handleLoop(Tag[Ask], ask.map(_ + 1))([C] => _ => Loop.continue(3))
            assert(v.eval == 4)
        }

        "handleLoop completing from the clause" in {
            val v = ArrowEffect.handleLoop(Tag[Ask], ask.map(_ + 1))([C] => _ => Loop.done(99), a => a)
            assert(v.eval == 99)
        }

        "handleLoopState" in {
            val v: Int < Any = ArrowEffect.handleLoopState(Tag[Ask], 7, ask.map(_ + 1))(
                [C] => (state, _) => Loop.continue(state + 1, state),
                (state, a) => a * 100 + state
            )
            assert(v.eval == 808)
        }

        "handleLoopState without a done clause" in {
            val v = ArrowEffect.handleLoopState(Tag[Ask], 7, ask.map(_ + 1))(
                [C] => (state, _) => Loop.continue(state + 1, state)
            )
            assert(v.eval == 8)
        }

        "handleContWith" in {
            val v: Int < Any = ArrowEffect.handleContWith(Tag[Ask], ask.map(_ + 1))(
                [C] => (_, cont) => cont(2),
                a => a
            )((b: Int) => b * 10)
            assert(v.eval == 30)
        }

        "handleLoopWith" in {
            val v: Int < Any = ArrowEffect.handleLoopWith(Tag[Ask], ask.map(_ + 1))(
                [C] => _ => Loop.continue(3),
                a => a
            )((b: Int) => b * 10)
            assert(v.eval == 40)
        }

        "handleLoopStateWith" in {
            val v: Int < Any = ArrowEffect.handleLoopStateWith(Tag[Ask], 7, ask.map(_ + 1))(
                [C] => (state, _) => Loop.continue(state + 1, state),
                (state, a) => a + state
            )(b => b * 10)
            assert(v.eval == 160)
        }

        "bracket" in {
            var released = false
            val v        = Effect.bracket(1)((_, _) => released = true)(r => ask.map(_ + r))
            assert(answer(v).eval == 2)
            assert(released)
        }

        "ContextEffect.suspend and handle" in {
            val v = ContextEffect.suspend(Tag[Level])
            assert(ContextEffect.handleInheritable(Tag[Level], 42)(v).eval == 42)
        }

        "ContextEffect.suspendWith" in {
            val v = ContextEffect.suspendWith(Tag[Level])(l => ask.map(_ + l))
            assert(answer(ContextEffect.handleInheritable(Tag[Level], 41)(v)).eval == 42)
        }

        "ContextEffect.suspend with a default" in {
            assert(ContextEffect.suspend(Tag[Level], -1).eval == -1)
        }

        "ContextEffect.suspendWith with a default" in {
            val v = ContextEffect.suspendWith(Tag[Level], 40)(l => ask.map(_ + l))
            assert(answer(v).eval == 41)
        }

        "ContextEffect.handle layering" in {
            val v = ContextEffect.handleInheritable(Tag[Level], 100, _ * 2) {
                ContextEffect.handleInheritable(Tag[Level], 100, _ * 2)(ContextEffect.suspend(Tag[Level]))
            }
            assert(v.eval == 200)
        }

        "a region nested under another effect" in {
            var said = List.empty[String]
            val body = ask.map(a => say("a" + a).map(_ => a))
            val v = ArrowEffect.handleCont(Tag[Say], answer(body))(
                [C] =>
                    (input, cont) =>
                        said = said :+ input
                        cont(())
                ,
                a => a
            )
            assert(v.eval == 1)
            assert(said == List("a1"))
        }
    }

    "Loop" - {

        "apply with one state" in {
            val v = Loop(0)(i => if i == 3 then Loop.done(i) else Loop.continue(i + 1))
            assert(v.eval == 3)
        }

        "apply with two states" in {
            val v = Loop(0, 10)((i, acc) => if i == 3 then Loop.done(acc) else Loop.continue(i + 1, acc + i))
            assert(v.eval == 13)
        }

        "apply with three states" in {
            val v = Loop(0, 10, 100)((i, a, b) => if i == 3 then Loop.done(a + b) else Loop.continue(i + 1, a + i, b))
            assert(v.eval == 113)
        }

        "apply with four states" in {
            val v = Loop(0, 10, 100, 1000)((i, a, b, c) =>
                if i == 3 then Loop.done(a + b + c) else Loop.continue(i + 1, a + i, b, c)
            )
            assert(v.eval == 1113)
        }

        "apply resuming through an effect" in {
            val v = Loop(0)(i => if i == 3 then Loop.done(i) else ask.map(a => Loop.continue(i + a)))
            assert(answer(v).eval == 3)
        }

        "indexed with no state" in {
            val v = Loop.indexed(idx => if idx == 3 then Loop.done(idx) else Loop.continue)
            assert(v.eval == 3)
        }

        "indexed with one state" in {
            val v = Loop.indexed(10)((idx, acc) => if idx == 3 then Loop.done(acc) else Loop.continue(acc + idx))
            assert(v.eval == 13)
        }

        "indexed with two states" in {
            val v = Loop.indexed(10, 100)((idx, a, b) => if idx == 3 then Loop.done(a + b) else Loop.continue(a + idx, b))
            assert(v.eval == 113)
        }

        "indexed with three states" in {
            val v = Loop.indexed(10, 100, 1000)((idx, a, b, c) =>
                if idx == 3 then Loop.done(a + b + c) else Loop.continue(a + idx, b, c)
            )
            assert(v.eval == 1113)
        }

        "indexed with four states" in {
            val v = Loop.indexed(10, 100, 1000, 10000)((idx, a, b, c, d) =>
                if idx == 3 then Loop.done(a + b + c + d) else Loop.continue(a + idx, b, c, d)
            )
            assert(v.eval == 11113)
        }

        "foreach" in {
            var n = 0
            val v = Loop.foreach {
                n += 1
                if n == 3 then Loop.done(n) else Loop.continue
            }
            assert(v.eval == 3)
        }

        "repeat" in {
            var n = 0
            val _ = Loop.repeat(3) { n += 1 }.eval
            assert(n == 3)
        }

        "whileTrue" in {
            var n = 0
            val _ = Loop.whileTrue(n < 3) { n += 1 }.eval
            assert(n == 3)
        }

        "forever" in {
            var n = 0
            val v: Nothing < Ask = Loop.forever(ask.map { a =>
                n += a
                if n == 3 then throw new IllegalStateException("stop")
            })
            val stopped =
                try
                    val _ = answer(v).eval
                    false
                catch case _: IllegalStateException => true
            assert(stopped)
            assert(n == 3)
        }
    }

    "the lifts" - {

        "Kyo.lift" in {
            val v: Int < Ask = kyo.Kyo.lift(42)
            assert(answer(v).eval == 42)
        }

        "a bare value in a lambda" in {
            assert(answer(ask.map(a => a + 1)).eval == 2)
        }

        "a singleton in a lambda, which reaches the CanLift macro" in {
            object Plain
            assert(answer(ask.map(_ => Plain)).eval eq Plain)
        }

        "a pure function of one argument" in {
            val f: Int => Int < Any = (a: Int) => a + 1
            assert(f(1).eval == 2)
        }

        "a pure function of two arguments" in {
            val f: (Int, Int) => Int < Any = (a: Int, b: Int) => a + b
            assert(f(1, 2).eval == 3)
        }

        "a pure function of three arguments" in {
            val f: (Int, Int, Int) => Int < Any = (a: Int, b: Int, c: Int) => a + b + c
            assert(f(1, 2, 3).eval == 6)
        }

        "a pure function of four arguments" in {
            val f: (Int, Int, Int, Int) => Int < Any = (a: Int, b: Int, c: Int, d: Int) => a + b + c + d
            assert(f(1, 2, 3, 4).eval == 10)
        }

        "a pure function of five arguments" in {
            val f: (Int, Int, Int, Int, Int) => Int < Any = (a: Int, b: Int, c: Int, d: Int, e: Int) => a + b + c + d + e
            assert(f(1, 2, 3, 4, 5).eval == 15)
        }

        "a pure function of six arguments" in {
            val f: (Int, Int, Int, Int, Int, Int) => Int < Any =
                (a: Int, b: Int, c: Int, d: Int, e: Int, g: Int) => a + b + c + d + e + g
            assert(f(1, 2, 3, 4, 5, 6).eval == 21)
        }
    }

    "Isolate" - {

        def level: Int < Level = ContextEffect.suspend(Tag[Level])

        "derive for a context effect and run" in {
            val isolate = Isolate.derive[Level, Any, Any]
            val r       = ContextEffect.handleInheritable(Tag[Level], 3)(isolate.run(level.map(_ + 1)))
            assert(r.eval == 4)
        }

        "summons implicitly" in {
            val isolate = Isolate[Level, Any, Level]
            assert(ContextEffect.handleInheritable(Tag[Level], 2)(isolate.run(level)).eval == 2)
        }

        "nest and flatten" in {
            val isolate = Isolate.derive[Level, Any, Level]
            val nested  = isolate.nest(level.map(_ * 10))
            assert(ContextEffect.handleInheritable(Tag[Level], 5)(nested.flatten).eval == 50)
        }

        "use provides the isolate as a given" in {
            val r = Isolate.derive[Level, Any, Any].use {
                summon[Isolate[Level, Any, Any]].run(level)
            }
            assert(ContextEffect.handleInheritable(Tag[Level], 8)(r).eval == 8)
        }

        "andThen composes" in {
            sealed trait Level2 extends ContextEffect[Int]
            val isolate = Isolate.derive[Level, Any, Any].andThen(Isolate.derive[Level2, Any, Any])
            val v       = level.map(a => ContextEffect.suspend(Tag[Level2]).map(_ + a))
            val r       = ContextEffect.handleInheritable(Tag[Level], 1)(ContextEffect.handleInheritable(Tag[Level2], 10)(isolate.run(v)))
            assert(r.eval == 11)
        }
    }

    "context binding edges" - {

        def level: Int < Level = ContextEffect.suspend(Tag[Level])

        "a fork strategy is accepted at the handle site" in {
            val r = ContextEffect.handle(Tag[Level])(7, (l: Int) => l, (l: Int) => l + 1, (parent: Int, _: Int, _: Int) => parent)(level)
            assert(r.eval == 7)
        }

        "a join strategy is accepted at the handle site" in {
            val r = ContextEffect.handle(Tag[Level])(7, (l: Int) => l, (l: Int) => l, (parent: Int, _: Int, _: Int) => parent)(level)
            assert(r.eval == 7)
        }

        "the done and release hooks are accepted at the handle site" in {
            var completed = false
            val r = ContextEffect.handle(Tag[Level])(
                derive = (_: Maybe[Int]) => 7,
                fork = (l: Int) => l,
                join = (parent: Int, _: Int, _: Int) => parent,
                done = (_: Int) => completed = true,
                release = (_: Int, _: Throwable) => ()
            )(level)
            assert(r.eval == 7)
            assert(completed)
        }
    }

end ProtoKernelTest
