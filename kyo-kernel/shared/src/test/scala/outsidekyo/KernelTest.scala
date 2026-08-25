package outsidekyo

import kyo.*
import kyo.Arrow
import kyo.Const
import kyo.kernel.*
import kyo.kernel.internal.Eval
import org.scalatest.freespec.AnyFreeSpec

/** The kernel's public surface, exercised from outside package `kyo`: suspension, transformation, and handling for every entry point.
  *
  * Every other kernel test is in package `kyo`, so none of them can observe what a call site outside it sees, and two failures have
  * already reached that blind spot. An inline body is re-typechecked where it expands, so a `private[kyo]` name it selects qualified does
  * not resolve there: `map` and `Eval` both stopped compiling outside `kyo` that way. And a `private[kyo]` term an inline body names gets
  * an inline accessor, which for a top-level object in `kyo.kernel.internal` dotty emits with the package itself as the receiver, so every
  * eval failed with `NoClassDefFoundError: kyo/kernel/internal`.
  *
  * The two halves need different evidence, so the tests here both expand each entry point and assert on what it produces: compiling proves
  * the name resolves, running proves the accessor the compiler emitted for it is well formed.
  *
  * A new public inline entry point belongs here. `Implicits.abortCastUnit` is the one deliberate omission: it exists to fail compilation,
  * so exercising it would fail this file.
  *
  * Two spellings here differ from the rest of the corpus for the same reason the file exists. `kyo.discard` is `private[kyo]`, so a
  * discarded result is bound to `val _`. And a `Loop` clause under a region ascribes its resumed value (`1: Int < Any`), because the
  * clause answers at the region's row rather than the successor's.
  */
class KernelTest extends AnyFreeSpec:

    sealed trait Ask   extends ArrowEffect[Const[Unit], Const[Int]]
    sealed trait Say   extends ArrowEffect[Const[String], Const[Unit]]
    sealed trait Level extends ContextEffect[Int]

    def ask: Int < Ask             = ArrowEffect.suspend[Any](Tag[Ask], ())
    def say(s: String): Unit < Say = ArrowEffect.suspend[Any](Tag[Say], s)

    /** Answers every Ask with 1. */
    def answer[A, S](v: A < (Ask & S)): A < S =
        ArrowEffect.handleCont(Tag[Ask], v)([C] => (_, cont) => cont(1), a => a)

    "the pending combinators" - {

        "map" in {
            val r = Eval(answer(ask.map(_ + 1)))
            assert(r == 2)
        }

        "flatMap" in {
            val r = Eval(answer(ask.flatMap(a => ask.map(b => a + b))))
            assert(r == 2)
        }

        "andThen" in {
            val r = Eval(answer(ask.andThen(ask.map(_ + 10))))
            assert(r == 11)
        }

        "unit" in {
            var seen = 0
            val v = ask.map { a =>
                seen = a; a
            }.unit
            val _ = Eval(answer(v))
            assert(seen == 1)
        }

        "flatten" in {
            val nested: (Int < Ask) < Any = Kyo.lift(ask.map(_ + 1))
            val r                         = Eval(answer(nested.flatten))
            assert(r == 2)
        }

        "eval" in {
            val v: Int < Any = 42
            assert(v.eval == 42)
        }
    }

    "handle, at every arity" - {

        "one" in {
            val r = ask.handle(v => Eval(answer(v)))
            assert(r == 1)
        }

        "two" in {
            val r = ask.handle(v => v.map(_ + 1), v => Eval(answer(v)))
            assert(r == 2)
        }

        "three" in {
            val r = ask.handle(v => v.map(_ + 1), v => v.map(_ + 1), v => Eval(answer(v)))
            assert(r == 3)
        }

        "four" in {
            val r = ask.handle(v => v.map(_ + 1), v => v.map(_ + 1), v => v.map(_ + 1), v => Eval(answer(v)))
            assert(r == 4)
        }

        "five" in {
            val r = ask.handle(
                v => v.map(_ + 1),
                v => v.map(_ + 1),
                v => v.map(_ + 1),
                v => v.map(_ + 1),
                v => Eval(answer(v))
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
                v => Eval(answer(v))
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
                v => Eval(answer(v))
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
                v => Eval(answer(v))
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
                v => Eval(answer(v))
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
                v => Eval(answer(v))
            )
            assert(r == 10)
        }
    }

    "the arrow constructors" - {

        "Arrow.apply" in {
            val plusOne: Arrow[Int, Int, Any] = Arrow(x => x + 1)
            val r                             = Eval(answer(ask.map(x => plusOne(x))))
            assert(r == 2)
        }

        "Arrow.recursive" in {
            val sum: Arrow[Int, Int, Any] = Arrow.recursive((self, x) => if x == 0 then 0 else self(x - 1).map(_ + x))
            assert(Eval(sum(4)) == 10)
        }
    }

    "the effect surface" - {

        "suspend" in {
            assert(Eval(answer(ask)) == 1)
        }

        "suspendWith" in {
            val v = ArrowEffect.suspendWith[Any](Tag[Ask], ())(i => i + 5)
            assert(Eval(answer(v)) == 6)
        }

        "handleCont" in {
            val v = ArrowEffect.handleCont(Tag[Ask], ask.map(_ + 1))([C] => (_, cont) => cont(2), a => a * 10)
            assert(Eval(v) == 30)
        }

        "handleLoop" in {
            val v = ArrowEffect.handleLoop(Tag[Ask], ask.map(_ + 1))([C] => _ => Loop.continue(3))
            assert(Eval(v) == 4)
        }

        "handleLoop completing from the clause" in {
            val v = ArrowEffect.handleLoop(Tag[Ask], ask.map(_ + 1))([C] => _ => Loop.done(99), a => a)
            assert(Eval(v) == 99)
        }

        "handleLoopState" in {
            // a done clause that computes leaves the result type to the expected type
            val v: Int < Any = ArrowEffect.handleLoopState(Tag[Ask], 7, ask.map(_ + 1))(
                [C] => (state, _) => Loop.continue(state + 1, state),
                (state, a) => a * 100 + state
            )
            assert(Eval(v) == 808)
        }

        "handleLoopState without a done clause" in {
            val v = ArrowEffect.handleLoopState(Tag[Ask], 7, ask.map(_ + 1))(
                [C] => (state, _) => Loop.continue(state + 1, state)
            )
            assert(Eval(v) == 8)
        }

        "handleContWith" in {
            val v: Int < Any = ArrowEffect.handleContWith(Tag[Ask], ask.map(_ + 1))(
                [C] => (_, cont) => cont(2),
                a => a
            )((b: Int) => b * 10)
            assert(Eval(v) == 30)
        }

        "handleLoopWith" in {
            val v: Int < Any = ArrowEffect.handleLoopWith(Tag[Ask], ask.map(_ + 1))(
                [C] => _ => Loop.continue(3),
                a => a
            )((b: Int) => b * 10)
            assert(Eval(v) == 40)
        }

        "handleLoopStateWith" in {
            val v: Int < Any = ArrowEffect.handleLoopStateWith(Tag[Ask], 7, ask.map(_ + 1))(
                [C] => (state, _) => Loop.continue(state + 1, state),
                (state, a) => a + state
            )(b => b * 10)
            // the clause answers 7 and advances the state to 8, so the body settles at 8 and the done
            // clause adds the final state, not the initial one
            assert(Eval(v) == 160)
        }

        "catching" in {
            val v = Effect.catching((throw new RuntimeException("boom")): Int < Any)(_ => 42)
            assert(Eval(v) == 42)
        }

        "bracket" in {
            var released = false
            val v        = Effect.bracket(1)(_ => released = true)(r => ask.map(_ + r))
            assert(Eval(answer(v)) == 2)
            assert(released)
        }

        "ContextEffect.suspend and handle" in {
            val v = ContextEffect.suspend(Tag[Level])
            assert(Eval(ContextEffect.handle(Tag[Level], 42)(v)) == 42)
        }

        "ContextEffect.suspendWith" in {
            val v = ContextEffect.suspendWith(Tag[Level])(l => ask.map(_ + l))
            assert(Eval(answer(ContextEffect.handle(Tag[Level], 41)(v))) == 42)
        }

        "ContextEffect.suspend with a default" in {
            assert(Eval(ContextEffect.suspend(Tag[Level], -1)) == -1)
        }

        "ContextEffect.suspendWith with a default" in {
            val v = ContextEffect.suspendWith(Tag[Level], 40)(l => ask.map(_ + l))
            assert(Eval(answer(v)) == 41)
        }

        "ContextEffect.handle layering" in {
            val v = ContextEffect.handle(Tag[Level], 100, _ * 2) {
                ContextEffect.handle(Tag[Level], 100, _ * 2)(ContextEffect.suspend(Tag[Level]))
            }
            assert(Eval(v) == 200)
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
            assert(Eval(v) == 1)
            assert(said == List("a1"))
        }
    }

    "Loop" - {

        "apply with one state" in {
            val v = Loop(0)(i => if i == 3 then Loop.done(i) else Loop.continue(i + 1))
            assert(Eval(v) == 3)
        }

        "apply with two states" in {
            val v = Loop(0, 10)((i, acc) => if i == 3 then Loop.done(acc) else Loop.continue(i + 1, acc + i))
            assert(Eval(v) == 13)
        }

        "apply with three states" in {
            val v = Loop(0, 10, 100)((i, a, b) => if i == 3 then Loop.done(a + b) else Loop.continue(i + 1, a + i, b))
            assert(Eval(v) == 113)
        }

        "apply with four states" in {
            val v = Loop(0, 10, 100, 1000)((i, a, b, c) =>
                if i == 3 then Loop.done(a + b + c) else Loop.continue(i + 1, a + i, b, c)
            )
            assert(Eval(v) == 1113)
        }

        "apply resuming through an effect" in {
            val v = Loop(0)(i => if i == 3 then Loop.done(i) else ask.map(a => Loop.continue(i + a)))
            assert(Eval(answer(v)) == 3)
        }

        "indexed with no state" in {
            val v = Loop.indexed(idx => if idx == 3 then Loop.done(idx) else Loop.continue)
            assert(Eval(v) == 3)
        }

        "indexed with one state" in {
            val v = Loop.indexed(10)((idx, acc) => if idx == 3 then Loop.done(acc) else Loop.continue(acc + idx))
            assert(Eval(v) == 13)
        }

        "indexed with two states" in {
            val v = Loop.indexed(10, 100)((idx, a, b) => if idx == 3 then Loop.done(a + b) else Loop.continue(a + idx, b))
            assert(Eval(v) == 113)
        }

        "indexed with three states" in {
            val v = Loop.indexed(10, 100, 1000)((idx, a, b, c) =>
                if idx == 3 then Loop.done(a + b + c) else Loop.continue(a + idx, b, c)
            )
            assert(Eval(v) == 1113)
        }

        "indexed with four states" in {
            val v = Loop.indexed(10, 100, 1000, 10000)((idx, a, b, c, d) =>
                if idx == 3 then Loop.done(a + b + c + d) else Loop.continue(a + idx, b, c, d)
            )
            assert(Eval(v) == 11113)
        }

        "foreach" in {
            var n = 0
            val v = Loop.foreach {
                n += 1
                if n == 3 then Loop.done(n) else Loop.continue
            }
            assert(Eval(v) == 3)
        }

        "repeat" in {
            var n = 0
            val _ = Eval(Loop.repeat(3)(Kyo.lift[Unit, Any] { n += 1 }))
            assert(n == 3)
        }

        "whileTrue" in {
            var n = 0
            val _ = Eval(Loop.whileTrue(Kyo.lift[Boolean, Any](n < 3))(Kyo.lift[Unit, Any] { n += 1 }))
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
                    val _ = Eval(answer(v))
                    false
                catch case _: IllegalStateException => true
            assert(stopped)
            assert(n == 3)
        }
    }

    "the lifts" - {

        "Kyo.lift" in {
            val v: Int < Ask = Kyo.lift(42)
            assert(Eval(answer(v)) == 42)
        }

        "Kyo.unit" in {
            var ran = false
            val _   = Eval(Kyo.unit.map(_ => ran = true))
            assert(ran)
        }

        "a bare value in a lambda" in {
            assert(Eval(answer(ask.map(a => a + 1))) == 2)
        }

        "a singleton in a lambda, which reaches the CanLift macro" in {
            object Plain
            assert(Eval(answer(ask.map(_ => Plain))) eq Plain)
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

    "the collection combinators" - {

        "foreach" in {
            assert(Eval(answer(Kyo.foreach(Seq(1, 2, 3))(x => ask.map(_ + x)))) == Seq(2, 3, 4))
        }

        "foreachConcat" in {
            assert(Eval(answer(Kyo.foreachConcat(Seq(1, 2))(x => ask.map(a => Seq(x, x + a))))) == Seq(1, 2, 2, 3))
        }

        "foreachIndexed" in {
            assert(Eval(answer(Kyo.foreachIndexed(Seq(10, 20))((idx, v) => ask.map(a => idx + v + a)))) == Seq(11, 22))
        }

        "foreachDiscard" in {
            var seen = List.empty[Int]
            val _    = Eval(answer(Kyo.foreachDiscard(Seq(1, 2))(x => ask.map(a => seen = seen :+ (x + a)))))
            assert(seen == List(2, 3))
        }

        "filter" in {
            assert(Eval(answer(Kyo.filter(Seq(1, 2, 3, 4))(x => ask.map(a => x % (a + 1) == 0)))) == Seq(2, 4))
        }

        "foldLeft" in {
            assert(Eval(answer(Kyo.foldLeft(Seq(1, 2, 3))(0)((acc, x) => ask.map(a => acc + x * a)))) == 6)
        }

        "collect" in {
            assert(Eval(answer(Kyo.collect(Seq(1, 2, 3))(x => ask.map(a => if x % 2 == a then Maybe(x) else Maybe.empty)))) == Seq(1, 3))
        }

        "collectAll" in {
            assert(Eval(answer(Kyo.collectAll(Seq(ask, ask.map(_ + 1))))) == Seq(1, 2))
        }

        "collectAllDiscard" in {
            var n = 0
            val _ = Eval(answer(Kyo.collectAllDiscard(Seq(ask.map(a => n += a), ask.map(a => n += a)))))
            assert(n == 2)
        }

        "findFirst" in {
            assert(Eval(answer(Kyo.findFirst(Seq(1, 2, 3))(x => ask.map(a => if x > a then Maybe(x * 10) else Maybe.empty)))) == Maybe(20))
        }

        "takeWhile" in {
            assert(Eval(answer(Kyo.takeWhile(Seq(1, 2, 3))(x => ask.map(a => x <= a + 1)))) == Seq(1, 2))
        }

        "span" in {
            assert(Eval(answer(Kyo.span(Seq(1, 2, 3))(x => ask.map(a => x <= a + 1)))) == (Seq(1, 2), Seq(3)))
        }

        "dropWhile" in {
            assert(Eval(answer(Kyo.dropWhile(Seq(1, 2, 3))(x => ask.map(a => x <= a + 1)))) == Seq(3))
        }

        "partition" in {
            assert(Eval(answer(Kyo.partition(Seq(1, 2, 3, 4))(x => ask.map(a => x % (a + 1) == 0)))) == (Seq(2, 4), Seq(1, 3)))
        }

        "partitionMap" in {
            val r = Eval(answer(Kyo.partitionMap(Seq(1, 2, 3))(x => ask.map(a => if x > a then Right(x) else Left(x.toString)))))
            assert(r == (Seq("1"), Seq(2, 3)))
        }

        "scanLeft" in {
            assert(Eval(answer(Kyo.scanLeft(Seq(1, 2))(0)((acc, x) => ask.map(a => acc + x * a)))) == Seq(0, 1, 3))
        }

        "groupBy" in {
            assert(Eval(answer(Kyo.groupBy(Seq(1, 2, 3))(x => ask.map(a => x % (a + 1))))) == Map(1 -> Seq(1, 3), 0 -> Seq(2)))
        }

        "groupMap" in {
            val r = Eval(answer(Kyo.groupMap(Seq(1, 2, 3))(x => ask.map(a => x % (a + 1)))(x => ask.map(a => x * 10 * a))))
            assert(r == Map(1 -> Seq(10, 30), 0 -> Seq(20)))
        }

        "filterKeys" in {
            val r = Eval(answer(Kyo.filterKeys(Map("a" -> 1, "b" -> 2))((k: String) => ask.map(a => k.length == a))))
            assert(r == Map("a" -> 1, "b" -> 2))
        }

        "fill" in {
            val r = Eval(answer(Kyo.fill(3)(ask)))
            assert(r == Chunk(1, 1, 1))
        }

        "zip" in {
            val r = Eval(answer(Kyo.zip(ask, ask.map(_ + 1), ask.map(_ + 2))))
            assert(r == (1, 2, 3))
        }

        "when with both branches" in {
            val r = Eval(answer(ask.map(a => Kyo.when(a == 1)("yes", "no"))))
            assert(r == "yes")
        }

        "when with one branch" in {
            assert(Eval(answer(Kyo.when(true)(ask))) == Maybe(1))
            assert(Eval(answer(Kyo.when(false)(ask))) == Maybe.empty)
        }

        "unless" in {
            assert(Eval(answer(Kyo.unless(true)(ask))) == Maybe.empty)
            assert(Eval(answer(Kyo.unless(false)(ask))) == Maybe(1))
        }
    }

    "Isolate" - {

        def level: Int < Level = ContextEffect.suspend(Tag[Level])

        "derive for a context effect and run" in {
            val isolate = Isolate.derive[Level, Any, Any]
            val r       = ContextEffect.handle(Tag[Level], 3)(isolate.run(level.map(_ + 1)))
            assert(Eval(r) == 4)
        }

        "summons implicitly" in {
            val isolate = Isolate[Level, Any, Level]
            assert(Eval(ContextEffect.handle(Tag[Level], 2)(isolate.run(level))) == 2)
        }

        "nest and flatten" in {
            val isolate = Isolate.derive[Level, Any, Level]
            val nested  = isolate.nest(level.map(_ * 10))
            assert(Eval(ContextEffect.handle(Tag[Level], 5)(nested.flatten)) == 50)
        }

        "use provides the isolate as a given" in {
            val r = Isolate.derive[Level, Any, Any].use {
                summon[Isolate[Level, Any, Any]].run(level)
            }
            assert(Eval(ContextEffect.handle(Tag[Level], 8)(r)) == 8)
        }

        "andThen composes" in {
            sealed trait Level2 extends ContextEffect[Int]
            val isolate = Isolate.derive[Level, Any, Any].andThen(Isolate.derive[Level2, Any, Any])
            val v       = level.map(a => ContextEffect.suspend(Tag[Level2]).map(_ + a))
            val r       = ContextEffect.handle(Tag[Level], 1)(ContextEffect.handle(Tag[Level2], 10)(isolate.run(v)))
            assert(Eval(r) == 11)
        }
    }

    "context binding edges" - {

        "a fork strategy is accepted at the handle site" in {
            val v = ContextEffect.suspend(Tag[Level])
            val r = ContextEffect.handle(Tag[Level], 7, identity, fork = l => Maybe(l + 1))(v)
            assert(Eval(r) == 7)
        }

        "a join strategy is accepted at the handle site" in {
            val v = ContextEffect.suspend(Tag[Level])
            val r = ContextEffect.handle(Tag[Level], 7, identity, join = (held, _) => held)(v)
            assert(Eval(r) == 7)
        }
    }
end KernelTest
