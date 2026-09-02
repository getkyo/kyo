# Test port audit: ArrowEffect, Isolate, Eval, EffectTrace

Scope: the 43 old cases with no exact-name twin in the proto suites, adjudicated by reading the old
case body and hunting the proto suites for a case that pins the same observable law.

Old files audited (paths relative to `kyo-kernel/`):

- `shared/src/test/scala/kyo/kernel/ArrowEffectTest.scala` (17 cases)
- `shared/src/test/scala/kyo/kernel/IsolateTest.scala` (15 cases)
- `shared/src/test/scala/kyo/kernel/internal/EvalTest.scala` (4 cases)
- `shared/src/test/scala/kyo/kernel/internal/EffectTraceTest.scala` (7 cases)

Proto file shorthands used in the tables:

- `proto/ArrowEffectTest` = `shared/src/test/scala/kyo/proto/kernel/ArrowEffectTest.scala`
- `proto/EvalTest` = `shared/src/test/scala/kyo/proto/kernel/internal/EvalTest.scala`
- `proto/EffectTraceTest` = `shared/src/test/scala/kyo/proto/kernel/internal/EffectTraceTest.scala`
- `proto/IsolateTest` = `shared/src/test/scala/kyo/proto/kernel/IsolateTest.scala`
- `proto/EffectBracketTest` = `shared/src/test/scala/kyo/proto/kernel/EffectBracketTest.scala`
- `proto/EvalThreadingTest` = `jvm-native/src/test/scala/kyo/proto/kernel/internal/EvalThreadingTest.scala`

## ArrowEffectTest

| old case | verdict | proto twin (file: case title) or reason |
| --- | --- | --- |
| answers the first operation and leaves the rest unhandled | TWIN | `proto/ArrowEffectTest`: "handleFirst" > "answers the first operation and hands the raw remainder". Same body (`ask.map(a => ask.map(b => a * 10 + b))`, first answered with 4, remainder re-handled with 2, result 42); the proto re-handles the remainder inside the clause instead of outside, and the handed-out shape is pinned separately by "handleFirst, ported" > "the remainder is handed out as a value and re-handled after a foreign crossing". |
| runs the clause on the standing operation | TWIN | `proto/ArrowEffectTest`: "dispatchFirst, ported" > "reads the input of a mapped suspension through its root". Pins that the clause runs on the standing operation and receives its input verbatim (`seen == "root"`). |
| peels a region node to reach the operation | TWIN | `proto/ArrowEffectTest`: "dispatchFirst" > "reports the first operation through a region and a handed-in deferral". The query walks past a `handleCont` region node (and a deferral) to reach the operation of the queried tag. |
| peels a stateless and a stateful region node | MISSING | No proto case puts a `handleLoopState` node in the dispatch walk. The construct exists: `ArrowEffect.dispatchFirst` peels every `Kyo.Handle` uniformly (`shared/src/main/scala/kyo/proto/kernel/ArrowEffect.scala:203-217`), and `handleLoopState` builds a `Kyo.Handle` like `handleCont`. Port value is modest (one node class covers both), but the two-deep stateless-over-stateful walk is unpinned. |
| does nothing when the standing operation has another tag | TWIN | `proto/ArrowEffectTest`: "dispatchFirst" > "a foreign operation standing first is not reported" (`seen == 0`). |
| stops at a settled value | TWIN | `proto/ArrowEffectTest`: "dispatchFirst" > "a settled value reports nothing" (`seen == 0`). |
| leaves the computation as it was | TWIN | `proto/ArrowEffectTest`: "dispatchFirst" > "reports the first operation through a region and a handed-in deferral". After the dispatch it evaluates the same value under a real handler to 42, which is the non-destructive law. The proto does not re-check the counter after the evaluation; the dispatch clause is a local lambda the walk never stores. |
| recovers a throw raised while the computation is built | DIVERGENT | The construct is gone. Old `ArrowEffect.handleCatching` takes the body by name (`inline v: => A < (E & S)`, `shared/src/main/scala/kyo/kernel/ArrowEffect.scala:577`) and installs the guard before forcing it. The proto's recovery is the third argument of `handleCont`/`handleLoop*`, whose `v` is a strict parameter matched before the handler exists (`shared/src/main/scala/kyo/proto/kernel/ArrowEffect.scala:91-124`), so a throw while the body is built escapes the region by construction. Worth flagging upward: the old comment names `Abort.run { throw ... }` as resting on the by-name body. |
| recovers a throw in the computation | TWIN | `proto/ArrowEffectTest`: "recover" > "a region's recovery clause answers a throw raised in its extent" (`ask.map(_ => throw Boom)` recovered to -1). |
| recovers a throw raised after a resumption | TWIN | `proto/ArrowEffectTest`: "recover" > "a stateful region's recovery clause receives the live state, not the install-time state". The body throws after two resumptions (`ask.map(_ => ask.map(_ => throw Boom))`) and the recovery answers (-102), which is the old case's law plus the live-state claim. |
| a throw in the done clause is not recovered | DIVERGENT | Ruled the other way. In the proto's evaluator the region's `done` runs while its frame is still on the stack: `handler.done(...)` is called before `arrowExit()` pops it (`shared/src/main/scala/kyo/proto/kernel/internal/Eval.scala:395-397`), so a `done` throw is offered to that region's own recovery. The proto pins the settled half explicitly in "recover" > "a settled input's done throw reaches the recovery clause". Coverage note: the pending-path done throw into its own recovery has no proto case; adding one would close the pair. |
| eval throws on an unhandled suspension | TWIN | `proto/EvalTest`: "an unhandled operation is a bug" (`intercept[Throwable](eval(ask.asInstanceOf[Int < Any]))`, message contains "unhandled suspension"). The proto's message wording replaces "Unexpected pending effect". |
| a clause may apply its continuation twice in one answer | TWIN | `proto/ArrowEffectTest`: "handleCont" > "the captured continuation is multi-shot" (`ask.map(_ * 2)`, clause runs `cont(1).map(x => cont(2).map(y => x + y))`, result 6). Structurally the same single-map suspension; the old file carried this case twice, once here and once under "handle". |
| a deferred payload that throws mid answer loop cannot commit another dispatch's state or continuation | MISSING | No proto case pins cross-region state and continuation isolation under a mid-loop throw. The nearest proto cases each cover half: "safety audit pins" > "a clause that suspends on the outer effect keeps its state lane across the shared cell" (state lanes, no throw) and "a throwing deferred payload after an answer does not re-run the consumed continuation" (throw and continuation, one region, no cross-typed state). Port note: the interior `Effect.catching` becomes a region installed for its recovery; `proto/EffectBracketTest:602` has the idiom (`recovering[A](v: A < Wrap)(f) = ArrowEffect.handleCont(Tag[Wrap], v)([C] => (_, cont) => cont(()), a => a, ex => Maybe(f(ex)))`). |
| a clause throw escapes the region on every dispatch path | MISSING | Half covered. That a clause throw escapes on both the loop and the cont dispatch path is pinned by "safety audit pins" > "a clause throw releases the interior brackets it escapes" (loop and cont sides both end at -2), and the cont fast/general split by "a clause throw escapes the region on the fast and general cont paths". Unpinned is the recover-no half this case exists for: a recovering scope standing between the suspension and the handler is passed over on every dispatch path. Port note: replace the interior `Effect.catching` with an interior recovering region (the `recovering` idiom above) and the exterior one with `try eval(...) catch`. |
| a throwing release in a nested eval does not disarm the enclosing slice | MISSING | No proto case combines a throwing release drained at a nested eval's boundary with the armed-bit probe. `proto/EvalTest`: "a nested eval inside a slice runs unarmed and completes despite the pending stop" covers the unarmed nested eval with no throwing release; `proto/ArrowEffectTest`: "a stop arriving during eager construction inside a slice reifies and parks the chain" is the same eager-chain probe without the nested eval. Constructs all exist. |
| a throwing release on the completing path leaves the caller's safepoint state intact | MISSING | No proto case checks the caller's safepoint state after a throwing release owed by the eval boundary. `proto/EvalTest`: "a throw escaping a root eval leaves the safepoint depth unchanged" covers a plain body throw, not a release drained at the boundary; `jvm-native/.../SafepointTest`: "the budget flows through enter, exit, save, and restore" covers the API alone. Constructs exist (`Safepoint.get/enter/save/restore/exit` are used by proto tests). |

## IsolateTest

| old case | verdict | proto twin (file: case title) or reason |
| --- | --- | --- |
| the inner binding still answers its own value after a crossing | TWIN | `proto/IsolateTest`: "Contextual" > "the inner binding of a tag still answers after the crossing". Nested `handleInheritable` of one tag, crossing, and the read answers "inner". The crossing is expressed as a detached `handleFirst` continuation instead of `Contextual.run`. |
| the inner binding still derives from the outer after a crossing | TWIN | `proto/IsolateTest`: "Contextual" > "derived layers reconstruct the fork point values on resume" (outer 1, inner derives `_ + 10`, the crossed read answers 11). |
| a join never reaches a scope that did not own the crossing | DIVERGENT | Ruled the other way, same program. `proto/IsolateTest`: "ported crossings" > "a restored crossing reads the binding it captured, not the scope it restores in" runs the identical three-region `Contextual.nest` shape and asserts 2, where the old asserts 10. The proto's restored crossing keeps its captured binding rather than taking the restoring scope's. |
| contravariant Keep with covariant Restore | TWIN | `proto/IsolateTest`: "variance" > "mixed variance scenarios" > "contravariant Keep with contravariant Restore". Body is identical (`Isolate[TestEffect1, Any, Any]` accepted as `Isolate[TestEffect1, TestEffect2, TestEffect1]`); only the wording of the Restore parameter's variance changed. |
| with nothing bound, the computation is unchanged | TWIN | `proto/IsolateTest`: "the contextual isolate" > "with no region in scope the cycle is the identity" (capture, isolate, restore over `42: Int < Any`, result 43). Same law: with no binding in scope the crossing changes nothing. The proto crosses a settled value where the old crosses a defaulted read, so it cannot observe a fabricated binding; a one-line strengthening would cross `read1` instead. |
| a bound value crosses into the computation | TWIN | `proto/IsolateTest`: "ported crossings" > "every binding in scope is asked" (three bindings cross and the crossed computation reads `(1, "a", true)`); also "Contextual" > "a binding crossed at the suspension is present in a detached resume". |
| a binding that refuses the crossing does not cross | DIVERGENT | The construct is gone. The proto's fork strategy is total: `inline fork: A => A` (`shared/src/main/scala/kyo/proto/kernel/ContextEffect.scala:94`), so a binding cannot answer `Maybe.empty` to refuse. The proto re-asserted its own "every binding in scope is asked" to `(1, "a", true)` accordingly, where the old expected `(1, "none", true)`. |
| a binding crosses as what its strategy answers | TWIN | `proto/IsolateTest`: "the contextual isolate" > "the child of an isolate reads the forked binding, the origin keeps its own" (`fork = parent => parent * 2`; the child reads 20 against a parent of 10). |
| the innermost binding of a tag is what crosses | TWIN | `proto/IsolateTest`: "ported crossings" > "every layer of a three-deep nest survives a crossing" (crossed read answers "inner"); also "Contextual" > "the inner binding of a tag still answers after the crossing". |
| the crossed value is complete: it runs more than once, anywhere | TWIN | `proto/IsolateTest`: "Contextual" > "the continuation is a complete value: it resumes more than once, anywhere" (two resumes) together with "a crossed binding resumes at its captured value" (resumed under a foreign binding of the same tag, still reads the captured value). |
| the forking computation keeps what it had | TWIN | `proto/IsolateTest`: "the contextual isolate" > "the child of an isolate reads the forked binding, the origin keeps its own" (origin reads 10 while the child reads 20). |
| a resource does not cross | TWIN | `proto/EffectBracketTest`: "mixed with other kernel features" > "a contextual isolate inside a bracket forks an inert obligation" (one release, `Maybe.empty`, nothing owed by the fork). The proto consumes the crossing in place rather than evaluating a handed-out crossed value a second time. |
| a crossing runs where it was defined | DIVERGENT | The construct is gone. The old case's fork strategy raises an effect of its own (`fork = n => read3.map(...)`) answered by the forking computation's handler. The proto's `fork: A => A` is pure and cannot suspend, so a strategy has no site to run at. |
| what a fork ended holding is joined into what is bound here | TWIN | `proto/IsolateTest`: "the contextual isolate" > "the origin continues at the joined state" (`join = (parent, _, child) => parent + child`; the origin reads 30 after the child ended holding 20); also "join observes the parent's current state, the forked state, and the child's final state". |
| a binding the fork did not carry is left alone | DIVERGENT | Same reason as "a binding that refuses the crossing does not cross": the refusing fork (`fork = _ => Maybe.empty`) has no proto counterpart now that `fork` is total. |

## EvalTest

| old case | verdict | proto twin (file: case title) or reason |
| --- | --- | --- |
| a clause's effectful answer is answered outside its scope | TWIN | `proto/EvalTest`: "handleLoop" > "an effectful answer runs under this handler with the interior parked". Body and assertions are the same program (`say("m").map(_ => ask).map(_ + 1)` under `recordSay("inner")`, clause answers `say("c").map(_ => 41)`, result 42, `log == List("inner", "outer")`); the old file carried this case twice. |
| the region body's handlers are intact after the clause returns | TWIN | `proto/EvalTest`: "handleLoop" > "an effectful answer's remainder runs inside the interior region". Same program (`ask.map(a => say("after").map(_ => a + 1))`) and the same `log == List("outer", "inner")`, which is the intact-interior claim. |
| a stop already pending ends the slice before it starts | TWIN | `proto/EvalTest`: "partial evaluation and parking" > "a stop already pending returns the input before the slice starts". Stronger: it asserts the returned value is reference-identical to the input and that the deferred body never ran. |
| a parked slice re-enters and completes on the next slice | TWIN | `proto/EvalThreadingTest`: "partial evaluation under stops" > "a preemption stop reifies and resumes with handler state" (`Eval.partial(first).evalNow == Maybe(100)`); "a stop delivered between slices short-circuits" pins the same re-entry after a short circuit. The proto moved the stop-driven partial cases to jvm-native. |

## EffectTraceTest

| old case | verdict | proto twin (file: case title) or reason |
| --- | --- | --- |
| a throw in a continuation frame names its site | DIVERGENT | Ruled the other way. `proto/EffectTraceTest`: "a throw in a continuation frame with no region standing travels on the physical trace" runs the same deep-chain program and asserts the carrier is empty and the JVM trace's top frame names the throwing line. Effect frames attach at cold sites only. |
| nested evals accumulate their regions innermost first | MISSING | `proto/EffectTraceTest`: "nested evals accumulate their regions" keeps the accumulation claim (both region labels present, one carrier) but drops the innermost-first ordering the old case's name carries; "regions splice innermost first" pins ordering only for a single eval. The port is one added ordering assertion on the existing proto case, not a new case. |
| NoStackTrace keeps its carrier and its empty stack | TWIN | `proto/EffectTraceTest`: "NoStackTrace keeps its carrier and skips the splice" (carrier present with elements, `ex.getStackTrace.isEmpty`). |
| carries the steps after a budget rescue | DIVERGENT | Ruled the other way. `proto/EffectTraceTest`: "a throw after a budget rescue with no region standing travels on the physical trace" runs the same `deep(600)` program and asserts the carrier elements are empty while the JVM trace names `boomHere`. |
| appears as one element per handler tag, innermost first | TWIN | `proto/EffectTraceTest`: "region nesting" > "names each region exactly once" (one carrier element per tag) with "regions splice innermost first" (Ask before Say, on the spliced trace the carrier order produces). Both halves are asserted for a single eval with two standing regions, which is the old case's shape. |
| a fused region names the body, then the region | MISSING | No proto trace case exercises a fused region. `ArrowEffect.handleLoopWith` exists in the proto (`shared/src/main/scala/kyo/proto/kernel/ArrowEffect.scala:407`) and `proto/EffectTraceTest` already carries `innerStep`, `carrier`, and `methods`, so the port is direct. |
| the effect frames of a throw are carried through a catching guard | DIVERGENT | The construct is gone. `Effect.catching` has no proto counterpart and the proto's `Effect` exposes no guard of its own (`shared/src/main/scala/kyo/proto/kernel/Effect.scala` carries `bracket` and `defer` only), so there is no `catching @ ` frame to name. Recovery is a region clause, covered by "a recovery clause inspects the enriched exception" and "a failure born in a recovery is described from the regions under it". |

## MISSING case bodies

Quoted verbatim from the old suites. Adaptation notes are mine, outside the quoted code.

### ArrowEffectTest

Helpers these bodies need from the old suite (all already present, under the proto names, in
`proto/ArrowEffectTest`):

```scala
    sealed trait Ask extends ArrowEffect[Const[Unit], Const[Int]]
    def ask: Int < Ask = ArrowEffect.suspend[Any](Tag[Ask], ())

    sealed trait Say extends ArrowEffect[Const[String], Const[Unit]]
    def say(s: String): Unit < Say = ArrowEffect.suspend[Any](Tag[Say], s)
```

`internal.SafepointStop.request()` becomes the proto suite's existing helper
(`proto/ArrowEffectTest:1276`):

```scala
    private def requestStop(): Unit =
        kyo.discard(Safepoint.get())
        kyo.discard(Safepoint.stop(Thread.currentThread()))
        Safepoint.deadline(java.lang.System.currentTimeMillis() - 1)
    end requestStop
```

`Effect.catching(v)(f)` becomes a region installed for its recovery, the idiom already in
`proto/EffectBracketTest:602`:

```scala
    sealed trait Wrap extends ArrowEffect[Const[Unit], Const[Unit]]

    def recovering[A](v: A < Wrap)(f: Throwable => A): A < Any =
        ArrowEffect.handleCont(Tag[Wrap], v)([C] => (_, cont) => cont(()), a => a, ex => Maybe(f(ex)))
```

`Effect.bracket`'s release takes `(value, outcome: Maybe[Throwable])` in the proto, and clause
outcomes take the proto's two-argument `Loop.continue(state, answer)` with the answer ascribed as a
computation (`1: Int < Any`).

#### peels a stateless and a stateful region node

```scala
        "peels a stateless and a stateful region node" in {
            var seen = ""
            val inner =
                ArrowEffect.handleLoopState(Tag[Ask], 0, say("deep").map(_ => ask))(
                    [X] => (state, _) => Loop.continue(state + 1, state),
                    (_, a) => a
                )
            val outer = ArrowEffect.handleCont(Tag[Ask], inner: Int < (Ask & Say))([X] => (_, cont) => cont(1), a => a)
            ArrowEffect.dispatchFirst(Tag[Say], outer)([X] => input => seen = input)
            assert(seen == "deep")
        }
```

#### a deferred payload that throws mid answer loop cannot commit another dispatch's state or continuation

```scala
        "a deferred payload that throws mid answer loop cannot commit another dispatch's state or continuation" in {
            case class Boom() extends RuntimeException
            // Say's stateful region holds an Int state, Ask's holds a String state; the body forces
            // a deferred payload that throws, with a recovery inside both regions. Any cross-typed
            // state or a foreign continuation answering the failure breaks the assertions
            val body: Int < (Ask & Say) =
                say("s").map(_ => ask.map(a => Effect.defer((throw Boom()): Int).map(_ + a)))
            val recovered: Int < (Ask & Say) = Effect.catching(body)(_ => -1)
            val askRegion: Int < Say = ArrowEffect.handleLoopState(Tag[Ask], "s0", recovered)(
                [C] => (s, _) => Loop.continue(s + "+", 1),
                (s, a) => if s == "s0+" then a else -100
            )
            val sayRegion: Int < Any = ArrowEffect.handleLoopState(Tag[Say], 100, askRegion)(
                [C] => (n, _) => Loop.continue(n + 1, ()),
                (n, a) => n * 1000 + a
            )
            assert(Eval(sayRegion) == 100999)
        }
```

Adaptation: `recovered` becomes `recovering(body)(_ => -1)` over a third tag that never suspends, so
the recovery still stands inside both stateful regions and the two `done` clauses still observe
their own states.

#### a clause throw escapes the region on every dispatch path

```scala
        "a clause throw escapes the region on every dispatch path" in {
            case class Boom() extends RuntimeException
            // the clause-scope law: the clause is the handler's code, outside the region it serves,
            // so its throw is answerable only by scopes wrapping the handler call. A Catching
            // standing between the suspension and the handler is passed over on every dispatch path
            val body: Int < Ask = Effect.catching(ask.map(_ + 1))(_ => -1)
            def viaLoop: Int < Any =
                ArrowEffect.handleLoop(Tag[Ask], body)([C] => _ => throw Boom(), a => a)
            def viaCont: Int < Any =
                ArrowEffect.handleCont(Tag[Ask], body)([C] => (_, _) => throw Boom(), a => a)
            assert(Eval(Effect.catching(viaLoop)(_ => -2)) == -2)
            assert(Eval(Effect.catching(viaCont)(_ => -2)) == -2)
        }
```

Adaptation: the interior guard becomes `recovering(ask.map(_ + 1))(_ => -1)`, which must be passed
over; the exterior guard becomes `try eval(...) catch case _: Boom => -2`, the shape the proto's
sibling case "a clause throw escapes the region on the fast and general cont paths" already uses.

#### a throwing release in a nested eval does not disarm the enclosing slice

```scala
        "a throwing release in a nested eval does not disarm the enclosing slice" in {
            // a nested full eval clears the slot's armed bit on entry (save installs a fresh
            // state) and owes it back through the restore in its finally; a boundary drain that
            // throws must not skip that restore. The disarm is observable through strict
            // construction: with the armed bit lost, a pending stop stops draining the budget,
            // so an eager chain runs past the stop instead of reifying at it. The body sits in
            // a deferred payload so the whole scenario runs inside the armed slice
            val inner: Int < Ask =
                Effect.bracket(Effect.defer(1))(_ => throw new IllegalStateException("release"))(_ => ask.map(_ + 1))
            val dropped: Int < Any =
                ArrowEffect.handleCont(Tag[Ask], inner)([C] => (_, _) => -1, a => a)
            var built = 0
            val outer: Int < Any =
                Effect.defer {
                    try kyo.discard(Eval(dropped))
                    catch case _: IllegalStateException => ()
                    0
                }.map { z =>
                    var acc: Int < Any = z
                    var i              = 0
                    while i < 100 do
                        acc = acc.map { x =>
                            built += 1
                            if built == 50 then internal.SafepointStop.request()
                            x + 1
                        }
                        i += 1
                    end while
                    acc
                }
            val p = Eval.partial(outer)
            assert(p.evalNow.isEmpty)
            // the armed bit came back from the nested eval, so the stop reified construction
            // within a step or two of where it lodged
            assert(built >= 50 && built <= 52, s"built=$built")
            assert(Eval(p) == 100)
            assert(built == 100)
        }
```

#### a throwing release on the completing path leaves the caller's safepoint state intact

```scala
        "a throwing release on the completing path leaves the caller's safepoint state intact" in {
            // the clause drops the continuation, so the bracket's release is owed by the drain at
            // the eval's boundary; its throw must not skip the safepoint restore
            val v: Int < Ask =
                Effect.bracket(Effect.defer(1))(_ => throw new IllegalStateException("release"))(_ => ask.map(_ + 1))
            val dropped: Int < Any =
                ArrowEffect.handleCont(Tag[Ask], v)([C] => (_, _) => -1, a => a)
            val slot = internal.Safepoint.get()
            // put the slot in a state distinct from a fresh one, so a skipped restore is visible
            kyo.discard(internal.Safepoint.enter(slot))
            kyo.discard(internal.Safepoint.enter(slot))
            try
                val before = internal.Safepoint.save(slot)
                internal.Safepoint.restore(slot, before)
                intercept[IllegalStateException](kyo.discard(Eval(dropped)))
                val after = internal.Safepoint.save(slot)
                internal.Safepoint.restore(slot, after)
                assert(after.equals(before))
            finally
                internal.Safepoint.exit(slot)
                internal.Safepoint.exit(slot)
            end try
        }
```

Adaptation for both release cases: `Effect.bracket(acquire)(release)(use)` in the proto passes
`(value, outcome: Maybe[Throwable])` to the release, and `internal.Safepoint` is
`kyo.proto.kernel.internal.Safepoint`, already imported by `proto/ArrowEffectTest`.

### EffectTraceTest

Helpers these bodies need from the old suite. `carrier` and `methods` already exist in
`proto/EffectTraceTest`; `answerAsk`/`dropSay` are the proto's `runAsk(v)(answer)` / `runSay(v)`, and
`innerStep`/`Boom` are already defined there:

```scala
    def answerAsk[A, S](value: Int)(v: A < (Ask & S)): A < S =
        ArrowEffect.handleLoop(Tag[Ask], v)([C] => _ => Loop.continue(value))

    def dropSay[A, S](v: A < (Say & S)): A < S =
        ArrowEffect.handleLoop(Tag[Say], v)([C] => _ => Loop.continue(()))

    def innerStep(v: Int < Ask): Int < Ask = v.map(_ => throw new Boom)
```

#### nested evals accumulate their regions innermost first

```scala
    "nested evals accumulate their regions innermost first" in {
        def innerBoom: Int =
            Eval(answerAsk(1)(ask.map(_ => (throw new RuntimeException("x")): Int)))
        val outer: Int < Any = dropSay(say("s").map(_ => innerBoom))
        val ex               = intercept[RuntimeException](Eval(outer))
        val t                = carrier(ex)
        assert(t.nonEmpty)
        val regions = t.get.elements.filter(_.getMethodName == "handle").map(_.getClassName)
        assert(regions.exists(_.contains("Ask")))
        assert(regions.exists(_.contains("Say")))
        assert(regions.indexWhere(_.contains("Ask")) < regions.indexWhere(_.contains("Say")))
    }
```

Adaptation: the proto's "nested evals accumulate their regions" is the same program under the proto
names; the port is the last three lines, either added there or kept as this separate case.

#### a fused region names the body, then the region

```scala
        "a fused region names the body, then the region" in {
            val fused: Int < Any =
                ArrowEffect.handleLoopWith[Const[Unit], Const[Int], Ask, Int, Int, Any, Any](Tag[Ask], innerStep(ask))(
                    [C] => _ => Loop.continue(1),
                    a => a
                )((_: Int) + 1)
            val ex  = intercept[Boom](Eval(fused))
            val els = carrier(ex).get.elements.toList
            assert(els.exists(_.getMethodName == "innerStep"))
            assert(els.exists(_.getMethodName == "handle"))
            assert(els.indexWhere(_.getMethodName == "innerStep") < els.indexWhere(_.getMethodName == "handle"))
        }
```

Adaptation: the clause outcome takes the proto's two-argument form,
`Loop.continue((), 1: Int < Any)`.

## Counts

| verdict | ArrowEffectTest | IsolateTest | EvalTest | EffectTraceTest | total |
| --- | --- | --- | --- | --- | --- |
| TWIN | 10 | 11 | 4 | 2 | 27 |
| MISSING | 5 | 0 | 0 | 2 | 7 |
| DIVERGENT | 2 | 4 | 0 | 3 | 9 |
| total | 17 | 15 | 4 | 7 | 43 |
