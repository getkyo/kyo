package kyo.kernel.internal

import kyo.*
import kyo.kernel.*
import kyo.kernel.ArrowEffect.Mask

/** The shapes the evaluator has to agree on: every handler kind with every arm it has, each under every configuration the evaluator
  * distinguishes.
  *
  * A scenario states what its clause does to one occurrence, and the laws derive everything else. The fusion law folds that one
  * occurrence over n consecutive ones, so the value of n occurrences is never written down by hand; the at-top law asserts the value
  * unchanged under an inert region pushed above the handler; the suspension law asserts it unchanged when the clause first performs an
  * effect answered outside the region. A cell that disagrees is a path the evaluator takes for that configuration that diverges from the
  * law it specialises.
  */
class EvalShapeTest extends Test:

    // the effect under test, answered by the handler each scenario installs
    sealed trait Ask extends ArrowEffect[Const[Unit], Const[Int]]
    def ask: Int < Ask = ArrowEffect.suspend[Any](Tag[Ask], ())

    // an effect a clause may perform, answered outside every region under test
    sealed trait Say extends ArrowEffect[Const[String], Const[Unit]]
    def say(s: String): Unit < Say = ArrowEffect.suspend[Any](Tag[Say], s)

    // interlopers: a binding never read and a region never answered, pushed above the handler under test
    sealed trait Cfg  extends ContextEffect[Int]
    sealed trait Idle extends ArrowEffect[Const[Unit], Const[Unit]]

    def record[A](v: A < Say): (List[String], A) < Any =
        ArrowEffect.handleLoopState(Tag[Say], List.empty[String], v)(
            [C] => (log, s) => Loop.continue(log :+ s, ()),
            (log, a) => (log, a)
        )

    // n occurrences of the effect, consecutive, summed; zero occurrences settles without it
    def prog(n: Int): Int < Ask =
        if n == 0 then 0 else ask.map(a => prog(n - 1).map(_ + a))

    /** The same program with a value-preserving map directly after each occurrence's own map. The deferral over the suspension then
      * carries a real continuation on each side, the shape the fused answering walk has to chain rather than hand back to the evaluator,
      * and the law says the value must not change.
      */
    def progTrailing(n: Int): Int < Ask =
        if n == 0 then 0 else ask.map(a => progTrailing(n - 1).map(_ + a)).map(x => x)

    def cfgAbove[A, S](v: A < S): A < S  = ContextEffect.handleInheritable(Tag[Cfg], 0)(v)
    def idleAbove[A, S](v: A < S): A < S = ArrowEffect.handleCont(Tag[Idle], v)([C] => (_, k) => k(()))

    // an inner handler for the same tag: the innermost region answers, and the scenario's own never runs
    def innerAbove[A, S](v: A < (Ask & S)): A < S =
        ArrowEffect.handleCont(Tag[Ask], v)([C] => (_, k) => k(1000))

    /** The fusion law: n consecutive occurrences are n independent answers. `resumes` lists the values one occurrence resumes with,
      * empty when the clause ends the region with `ends`; each resumption contributes its value once per path below it, plus the rest's
      * total, so `prog(n)` follows from one occurrence by folding.
      */
    def law(resumes: List[Int], ends: Int = 0)(n: Int): Int =
        if n == 0 then 0
        else if resumes.isEmpty then ends
        else
            val paths = (1 until n).foldLeft(1)((acc, _) => acc * resumes.size)
            resumes.map(r => r * paths + law(resumes, ends)(n - 1)).sum

    /** The fusion law for a clause that threads state: `step` answers one occurrence from the state, or ends the region with `ends`, and
      * `done` sees the final state and the body's value.
      */
    def lawState[St](init: St, step: St => Maybe[(St, Int)], done: (St, Int) => Int, ends: Int = 0)(n: Int): Int =
        def loop(st: St, acc: Int, i: Int): Int =
            if i == n then done(st, acc)
            else
                step(st) match
                    case Present(next) => loop(next._1, acc + next._2, i + 1)
                    case Absent        => ends
        loop(init, 0, 0)
    end lawState

    /** How many times a clause runs for n occurrences: once for the first, then once more per resumption for the rest. */
    def runs(resumes: Int)(n: Int): Int = if n == 0 then 0 else 1 + resumes * runs(resumes)(n - 1)

    /** One handler under test.
      *
      * `run` installs it with a clause that answers at once; `suspending` installs the same handler with a clause that performs `say("s")`
      * and then answers identically, so the two must agree on the value and differ only in the log. `expected(n)` is the value of
      * `prog(n)` by the fusion law, `inner(n)` the value when an inner region for the same tag sits between the handler and the program,
      * and `says(n)` how many times the suspending clause runs for `prog(n)`.
      */
    final case class Scenario(
        name: String,
        run: (Int < (Ask & Say)) => Int < Say,
        suspending: (Int < (Ask & Say)) => Int < Say,
        expected: Int => Int,
        inner: Int => Int,
        says: Int => Int
    )

    val counter: (Int, Int) => Int = (st, a) => a * 1000 + st

    // every handler kind with every arm it has: handleCont resumes once or never, handleContRepeated once, twice or never, handleLoop
    // and handleLoopState continue or end from the clause, Mask has no arm of its own and tunnels a handleCont past an inner handler
    val scenarios: List[Scenario] = List(
        Scenario(
            "handleCont resuming once",
            v => ArrowEffect.handleCont(Tag[Ask], v)([C] => (_, k) => k(7)),
            v => ArrowEffect.handleCont(Tag[Ask], v)([C] => (_, k) => say("s").map(_ => k(7))),
            law(List(7)),
            law(List(1000)),
            runs(1)
        ),
        Scenario(
            "handleCont never resuming",
            v => ArrowEffect.handleCont(Tag[Ask], v)([C] => (_, _) => -1),
            v => ArrowEffect.handleCont(Tag[Ask], v)([C] => (_, _) => say("s").map(_ => -1)),
            law(Nil, ends = -1),
            law(List(1000)),
            runs(0)
        ),
        /* handleContRepeated is not in this kernel (handleFirstRepeated replaced it). Disabled; restore if the API is reintroduced.
        Scenario(
            "handleContRepeated resuming once",
            v => ArrowEffect.handleContRepeated(Tag[Ask], v)([C] => (_, k) => k(7), a => a),
            v => ArrowEffect.handleContRepeated(Tag[Ask], v)([C] => (_, k) => say("s").map(_ => k(7)), a => a),
            law(List(7)),
            law(List(1000)),
            runs(1)
        ),
        Scenario(
            "handleContRepeated resuming twice",
            v => ArrowEffect.handleContRepeated(Tag[Ask], v)([C] => (_, k) => k(7).map(a => k(8).map(b => a + b)), a => a),
            v =>
                ArrowEffect.handleContRepeated(Tag[Ask], v)(
                    [C] => (_, k) => say("s").map(_ => k(7).map(a => k(8).map(b => a + b))),
                    a => a
                ),
            law(List(7, 8)),
            law(List(1000)),
            runs(2)
        ),
        Scenario(
            "handleContRepeated never resuming",
            v => ArrowEffect.handleContRepeated(Tag[Ask], v)([C] => (_, _) => -1, a => a),
            v => ArrowEffect.handleContRepeated(Tag[Ask], v)([C] => (_, _) => say("s").map(_ => -1), a => a),
            law(Nil, ends = -1),
            law(List(1000)),
            runs(0)
        ),
         */
        Scenario(
            "handleLoop continuing",
            v => ArrowEffect.handleLoop(Tag[Ask], v)([C] => _ => Loop.continue(7)),
            v => ArrowEffect.handleLoop(Tag[Ask], v)([C] => _ => say("s").map(_ => Loop.continue(7))),
            law(List(7)),
            law(List(1000)),
            runs(1)
        ),
        Scenario(
            "handleLoop ending from the clause",
            v => ArrowEffect.handleLoop(Tag[Ask], v)([C] => _ => Loop.done(-1), a => a),
            v => ArrowEffect.handleLoop(Tag[Ask], v)([C] => _ => say("s").map(_ => Loop.done(-1)), a => a),
            law(Nil, ends = -1),
            law(List(1000)),
            runs(0)
        ),
        Scenario(
            "handleLoopState threading a counter",
            v => ArrowEffect.handleLoopState(Tag[Ask], 100, v)([C] => (st, _) => Loop.continue(st + 1, 7 + st), counter),
            v =>
                ArrowEffect.handleLoopState(Tag[Ask], 100, v)(
                    [C] => (st, _) => say("s").map(_ => Loop.continue(st + 1, 7 + st)),
                    counter
                ),
            lawState(100, st => Present((st + 1, 7 + st)), counter),
            lawState(100, st => Present((st, 1000)), counter),
            runs(1)
        ),
        Scenario(
            "handleLoopState ending from the clause",
            v => ArrowEffect.handleLoopState(Tag[Ask], 100, v)([C] => (_, _) => Loop.done(-1), counter),
            v => ArrowEffect.handleLoopState(Tag[Ask], 100, v)([C] => (_, _) => say("s").map(_ => Loop.done(-1)), counter),
            lawState(100, _ => Absent, counter, ends = -1),
            lawState(100, st => Present((st, 1000)), counter),
            runs(0)
        ),
        Scenario(
            "Mask tunnelling past an inner handler",
            v => ArrowEffect.handleCont(Tag[Ask], Mask.run[Ask](innerAbove(Mask[Ask](v))))([C] => (_, k) => k(7)),
            v =>
                ArrowEffect.handleCont(Tag[Ask], Mask.run[Ask](innerAbove(Mask[Ask](v))))([C] => (_, k) => say("s").map(_ => k(7))),
            law(List(7)),
            law(List(1000)),
            runs(1)
        )
    )

    for s <- scenarios do
        s.name - {
            for n <- List(0, 1, 2, 3) do
                s"$n occurrences" - {

                    "base" in {
                        assert(record(s.run(prog(n))).eval == ((Nil, s.expected(n))))
                    }

                    // fusion law over a trailing map: the answering walk chains the deferral's continuation onto the suspension's
                    // instead of leaving the region, and the value is the law's either way
                    "base, with a trailing map after each occurrence" in {
                        assert(record(s.run(progTrailing(n))).eval == ((Nil, s.expected(n))))
                    }

                    "the clause suspends first, with a trailing map after each occurrence" in {
                        assert(record(s.suspending(progTrailing(n))).eval == ((List.fill(s.says(n))("s"), s.expected(n))))
                    }

                    // at-top law: an inert region above the handler changes nothing, whichever kind it is
                    "a binding above" in {
                        assert(record(s.run(cfgAbove(prog(n)))).eval == ((Nil, s.expected(n))))
                    }

                    "a region above" in {
                        assert(record(s.run(idleAbove(prog(n)))).eval == ((Nil, s.expected(n))))
                    }

                    // suspension law: a clause that performs an outer effect and then answers equals one that answers at once
                    "the clause suspends first" in {
                        assert(record(s.suspending(prog(n))).eval == ((List.fill(s.says(n))("s"), s.expected(n))))
                    }

                    "the clause suspends first, with a binding above" in {
                        assert(record(s.suspending(cfgAbove(prog(n)))).eval == ((List.fill(s.says(n))("s"), s.expected(n))))
                    }

                    "the clause suspends first, with a region above" in {
                        assert(record(s.suspending(idleAbove(prog(n)))).eval == ((List.fill(s.says(n))("s"), s.expected(n))))
                    }

                    // geography: an inner handler for the same tag answers, and this handler's clause never runs
                    "an inner handler for the same tag" in {
                        assert(record(s.run(innerAbove(prog(n)))).eval == ((Nil, s.inner(n))))
                    }

                    "an inner handler for the same tag, the clause suspending" in {
                        assert(record(s.suspending(innerAbove(prog(n)))).eval == ((Nil, s.inner(n))))
                    }
                }
        }
    end for

end EvalShapeTest
