# kyo-kernel2 test suite: internal-API usage audit

Worktree: `/Users/fwbrasil/workspace/kyo/.claude/worktrees/effervescent-painting-backus`, at `b8ad9309e8`.

Files audited (every `.scala` under the module's test roots):

- `kyo-kernel2/shared/src/test/scala/kyo/kernel/EvalTest.scala` (26 tests)
- `kyo-kernel2/shared/src/test/scala/kyo/kernel/KyoTest.scala` (5 tests)
- `kyo-kernel2/shared/src/test/scala/kyo/kernel/HandlersTest.scala` (14 tests)
- `kyo-kernel2/shared/src/test/scala/kyo/kernel/ArrowEffectTest.scala` (56 tests)
- `kyo-kernel2/shared/src/test/scala/kyo/kernel/PendingTest.scala` (12 tests)
- `kyo-kernel2/jvm/src/test/scala/kyo/kernel/HandlersProbe.scala` (probe, not a test class)
- `kyo-kernel2/jvm/src/test/scala/kyo/kernel/EvalProbe.scala` (probe, not a test class)

`kyo-kernel2/jvm/src/jmh/scala/kyo/kernel/bench/KernelBench.scala` was checked and touches no internal API; it is not discussed further.

## Surfaces used for the verdicts

User-facing (as given in the brief, confirmed against the source):

- `<` and its extensions: `lift` (`Pending.scala:12`), `map` (`Pending.scala:22`), `flatMap` (`:48`), `andThen` (`:51`), `eval` (`:54`), `evalPartial` (`:64`).
- `ArrowEffect.suspend` (`ArrowEffect.scala:14`), `suspendWith` (`:24`), `handle` (`:61`), `resume` (`:123`), `stop` (`:186`), `loop` (`:248`), `partial` (`:311`).
- `Arrow.apply` (`Arrow.scala:105`) and `Arrow.Transform` where users construct arrows.
- `Handler.Loop.continue` / `Handler.Loop.done` (`Handler.scala:33`, `:40`, `:48`, `:52`): these build the clause outcome, so a clause author calls them even when the `Handler.*` classes themselves are out of reach.

Internal machinery: direct `new Kyo.Handled` / `new Kyo.Suspend` / `new Kyo.Defer` (`Kyo.scala:36`, `:59`, `:64`), subclassing `Handler.Cont` / `Handler.Loop` / `Handler.LoopState` (`Handler.scala:11`, `:14`, `:17`), the `Handlers` collection (`Handlers.scala`), `Nested` / `Boxed` (`Kyo.scala:7`, `:12`), `Safepoint`, and `Eval`.

### Assumed shape of the parallel change

The new constructors are not in this tree yet (`ArrowEffect.scala` here still ends at `partial`). The audit assumes they mirror the old kernel:

- `ArrowEffect.handle(effectTag, v)(handle: [C] => (I[C], O[C] => A < (E & S & S2)) => A < (E & S & S2)): A < (S & S2)`, old kernel `kyo-kernel/shared/src/main/scala/kyo/kernel/ArrowEffect.scala:118`.
- `ArrowEffect.handleLoop(effectTag, v)(handle: [C] => (I[C], O[C] => A < (E & S)) => Loop.Outcome[A < (E & S), A] < S2): A < (S & S2)`, stateless, old kernel `:439`.
- `ArrowEffect.handleLoop(effectTag, state, v)(handle: [C] => (I[C], State, O[C] => A < (E & S)) => Loop.Outcome2[State, A < (E & S), A] < S2): A < (S & S2)`, stateful, old kernel `:493`.

The `Outcome` / `Continue` encoding in `Handler.scala:22-53` is the same one the old kernel uses (`kyo-kernel/shared/src/main/scala/kyo/kernel/Loop.scala:29`, `:83`, `:94`), so the outcome values in the snippets below are written as `Handler.Loop.continue` / `Handler.Loop.done`.

### The one translation rule every migration snippet uses

kernel2's `Handler.Loop` clause returns `Loop.Outcome[O[X] < (E & S), A]` (`Handler.scala:15`): its continue payload is **the answer to the operation**, and the region's own `cont` arrow carries the rest of the computation. The old-kernel `handleLoop` clause returns `Loop.Outcome[A < (E & S), A]`: its continue payload is **the continued computation**, produced by applying the `cont` parameter. So:

| region clause | public clause |
|---|---|
| `Handler.Loop.continue(answer)` | `Handler.Loop.continue(cont(answer))` |
| `Handler.Loop.continue(pendingAnswer)` where the answer itself raises `E` | `Handler.Loop.continue(pendingAnswer.map(cont))` |
| `Handler.Loop.done(v)` | `Handler.Loop.done(v)` |
| `Handler.Loop.continue(successorHandler, answer)` | `Handler.Loop.continue(nextState, cont(answer))` |

The two models agree on what runs inside and what runs outside the handler's own installation, which is why the migrations preserve meaning: an outcome that is itself pending (`< S2`) is chained outside the handler's layer (`Eval.scala:66-72` rebuilds from `idx`, old kernel `ArrowEffect.scala:452-453` maps the loop over it), while a continue payload that raises `E` again is answered by the same (successor) handler (`Eval.scala:81-84` keeps `take(idx + 1)`, old kernel feeds the payload back into `handleLoopLoop`).

---

## EvalTest.scala

Shared helpers, all internal: `loopAsk` (`:21`) and `loopSay` (`:25`) subclass `Handler.Loop`; `VarHandler` (`:34`) subclasses `Handler.LoopState`.

| Test (line) | Internal APIs touched | Verdict | Replacement or reason |
|---|---|---|---|
| a scope answers through its handler (`:40`) | `new Kyo.Handled` `:41`, `Handler.Loop` subclass `:22` | SHOULD-MIGRATE | `ArrowEffect.handleLoop(Tag[Ask], ask.map(_ + 1))([X] => (_, cont) => Handler.Loop.continue(cont(41)))` |
| scope exits run innermost first (`:45`) | `new Kyo.Handled` `:47`, `:48`, `Handler.Loop` subclasses | SHOULD-MIGRATE | two nested `handleLoop` calls with the same `.map` tails around each |
| the innermost handler of a tag answers (`:52`) | `new Kyo.Handled` `:53`, `:54` | SHOULD-MIGRATE | `handleLoop(Tag[Ask], handleLoop(Tag[Ask], ask.map(_ + 1))(inner))(outer)`; the outer clause must stay unreachable, so keep an assertion that it never fires |
| an effectful answer resolves through the scope's own collection (`:58`) | `Handler.Loop` subclass `:61`, `new Kyo.Handled` `:63`, `:64` | SHOULD-MIGRATE | inner `handleLoop(Tag[Ask], ...)` whose clause is `Handler.Loop.continue(say("c").map(_ => cont(41)))`, wrapped in `handleLoop(Tag[Say], ...)`; result type becomes `Int < Say` before the outer scope, which is the point the test makes |
| a clause runs outside its own scope (`:69`) | `Handler.Loop` subclass `:72`, `new Kyo.Handled` `:76`, `:77`, `:78` | SHOULD-MIGRATE | three stacked `handleLoop` calls, Say inner, Ask, Say outer; the typing (`Int < Ask`, then `Int < Say`, then `Int < Any`) states the assertion the log currently makes |
| drives deep recursion within a scope in bounded stack (`:83`) | `new Kyo.Handled` `:86` | SHOULD-MIGRATE | `handleLoop(Tag[Ask], loop(100000))([X] => (_, cont) => Handler.Loop.continue(cont(1)))` |
| done ends its scope at the operation (`:90`) | `Handler.Loop` subclass `:93`, `new Kyo.Handled` `:99` | SHOULD-MIGRATE | `handleLoop(Tag[Ask], program)([X] => (_, _) => Handler.Loop.done(-1))` |
| a done climbs past an inner scope without running its remainder (`:104`) | `Handler.Loop` subclass `:108`, `new Kyo.Handled` `:112`, `:117` | SHOULD-MIGRATE | inner `handleLoop(Tag[Say], program)`, `.map` tail, outer `handleLoop(Tag[Ask], ...)` with a `done` clause |
| a continue answer raising the effect is answered by the successor, which may done (`:123`) | `Handler.LoopState` subclass `:124`, `new Kyo.Handled` `:129` | SHOULD-MIGRATE | stateful overload, state is the phase: `handleLoop(Tag[Ask], 0, ask.map(_ + 1))([X] => (_, phase, cont) => if phase == 0 then Handler.Loop.continue(1, ask.map(a => cont(a + 100))) else Handler.Loop.done(-2))` |
| state threads through updates (`:133`) | `Handler.LoopState` subclass `:34`, `new Kyo.Handled` `:135` | SHOULD-MIGRATE (flagged) | `handleLoop(Tag[VarE], 0, program)([X] => (f, state, cont) => { val v2 = f(state); Handler.Loop.continue(v2, cont(v2)) })`. Flag: see "Coverage that migration drops" below |
| state updates survive an inner scope's exit (`:139`) | `new Kyo.Handled` `:143`, `:145`, `VarHandler` | SHOULD-MIGRATE (flagged) | same stateful clause with an inner `handleLoop(Tag[Say], inner)` scope; same flag |
| a stateful handler composes state and done (`:150`) | `Handler.LoopState` subclass `:151`, `new Kyo.Handled` `:158` | SHOULD-MIGRATE | `handleLoop(Tag[Ask], 3, go(5))([X] => (_, n, cont) => if n > 0 then Handler.Loop.continue(n - 1, cont(1)) else Handler.Loop.done(-1))` |
| rejects a suspension no scope handles (`:162`) | `new Kyo.Handled` `:165` | SHOULD-MIGRATE | `handleLoop(Tag[Ask], program)` yields `Int < Say`; the existing `asInstanceOf[Int < Any].eval` and `intercept[IllegalStateException]` carry over unchanged (throw site `Eval.scala:56`) |
| a clause may suspend before producing its outcome (`:169`) | `Handler.Loop` subclass `:172`, `new Kyo.Handled` `:174`, `:175` | SHOULD-MIGRATE | clause `say("pre").map(_ => Handler.Loop.continue(cont(41)))`, whole thing wrapped in `handleLoop(Tag[Say], ...)`; this is exactly the `Outcome < S2` position of the public signature |
| a clause may suspend before producing a done (`:180`) | `Handler.Loop` subclass `:184`, `new Kyo.Handled` `:190`, `:191` | SHOULD-MIGRATE | clause `say("pre").map(_ => Handler.Loop.done(-1))`, wrapped in a Say scope |
| a done fired while a clause outcome settles climbs to its own scope (`:197`) | `Handler.Loop` subclasses `:200`, `:203`, `new Kyo.Handled` `:209`, `:210` | SHOULD-MIGRATE | inner Ask `handleLoop` with a suspending clause, outer Say `handleLoop` whose clause is `Handler.Loop.done(-9)` |
| a stateful clause may suspend before producing its outcome (`:215`) | `Handler.LoopState` subclass `:217`, `new Kyo.Handled` `:220`, `:221` | SHOULD-MIGRATE | stateful overload with `state = 1` and clause `say("pre").map(_ => Handler.Loop.continue(state + 1, cont(state)))` |
| a done fired while a stateful clause outcome settles climbs to its own scope (`:226`) | `Handler.Loop` subclass `:229`, `Handler.LoopState` subclass `:231`, `new Kyo.Handled` `:237`, `:238` | SHOULD-MIGRATE | stateful inner scope, Say outer scope with a `done` clause |
| a clause does not see handlers inside its own scope (`:243`) | `Handler.Loop` subclass `:246`, `new Kyo.Handled` `:250`, `:251` | SHOULD-MIGRATE (needs a decision) | expressible, but the result type changes from `Int < Any` to `Int < Say`; see "Tests whose meaning shifts" below |
| evalPartial settles a deferred computation (`:256`) | none | n/a | already user-facing; `asInstanceOf[Int]` is a settled-representation assertion, discussed under "Representation pins" |
| evalPartial with an immediate stop returns the computation unchanged (`:263`) | none | n/a | already user-facing |
| evalPartial stops between defers leaving the rest evaluable (`:271`) | `Kyo.Defer` type pattern `:280` | JUSTIFIED | the parked shape is `evalPartial`'s internal contract (`Eval.scala:19-35` only steps `Kyo.Defer`), and there is no public predicate for "still pending" |
| evalPartial does not evaluate scopes (`:284`) | `new Kyo.Handled` `:285` | SHOULD-MIGRATE (confirm) | `val r = handleLoop(Tag[Ask], ask.map(_ + 1))(...)`, then `assert(r.evalPartial(() => false).asInstanceOf[AnyRef] eq r.asInstanceOf[AnyRef])`. Valid only if the constructor returns the region node itself rather than a deferred wrapper; confirm against the parallel change |
| enters deeply nested scopes in bounded stack (`:289`) | `new Kyo.Handled` `:292` | SHOULD-MIGRATE | `(1 to depth).foldLeft(0: Int < Any)((acc, _) => handleLoop(Tag[Ask], acc)([X] => (_, cont) => Handler.Loop.continue(cont(1))))` |
| opens a scope per recursion step in bounded stack (`:298`) | `new Kyo.Handled` `:302` | SHOULD-MIGRATE | same clause, built per recursion step |
| settles chained re-raised answers in bounded stack (`:307`) | `Handler.LoopState` subclass `:309`, `new Kyo.Handled` `:314` | SHOULD-MIGRATE | `handleLoop(Tag[Ask], depth, ask)([X] => (_, n, cont) => if n == 0 then Handler.Loop.continue(0, cont(0)) else Handler.Loop.continue(n - 1, ask.map(a => cont(a + 1))))` |

## KyoTest.scala

Every test pins node shape in `Kyo.scala`, which is the internal unit under test. Helpers `AskHandled` (`:16`), `loopAsk` (`:18`), `node` (`:22`) are internal by construction.

| Test (line) | Internal APIs touched | Verdict | Reason |
|---|---|---|---|
| Handled saves the region parts (`:32`) | `new Kyo.Handled` `:23`, field reads `handler` / `cont` | JUSTIFIED | asserts the constructor stores its three parts (`Kyo.scala:64-68`); no user-facing observation exists |
| Handled map lands outside the region (`:39`) | `Kyo.Handled` pattern match and field reads `:44-:47` | JUSTIFIED | the `Handled.map` cont-chaining pin (`Kyo.scala:69-70`); named in the brief as unreachable from the public surface |
| chained maps accumulate in order outside the region (`:53`) | `Kyo.Handled` pattern match, `m.cont` `:59` | JUSTIFIED | same pin over two chained arrows |
| mapping an outer region leaves a nested inner region untouched (`:65`) | `new Kyo.Handled` `:69`, field reads `:73-:75` | JUSTIFIED | asserts `map` on the outer node does not rebuild the inner node; structural, not observable through `eval` |
| eval cannot handle a cont region yet (`:81`) | `Handler.Cont` subclass `:83`, `new Kyo.Handled` `:85` | JUSTIFIED | pins `Eval.scala:149-150` (`cannot handle`) for a `Handler.Cont` region, which exists before Cont evaluation does. If the parallel `ArrowEffect.handle` turns out to build a Cont region node, this pin can move onto that constructor; as long as `handle` stays the trampoline at `ArrowEffect.scala:61`, no public route produces a Cont region |

## HandlersTest.scala

Two groups. Tests `:22` to `:89` exercise the `Handlers` collection, which is the internal unit under test. Tests `:91` to `:125` exercise the `Handler` clause protocol by invoking handler objects directly; they are equally internal, but they belong to `Handler.scala`, not `Handlers.scala` (see "Incidental findings").

| Test (line) | Internal APIs touched | Verdict | Reason |
|---|---|---|---|
| empty resolves nothing (`:22`) | `Handlers.empty`, `indexOf` | JUSTIFIED | `Handlers` is the unit under test |
| add then indexOf resolves the handler (`:26`) | `Handlers.add` / `indexOf` / `apply`, `Handler.Loop` subclass `:15` | JUSTIFIED | same |
| indexOf misses on an unrelated tag (`:34`) | `Handlers.add` / `indexOf` | JUSTIFIED | same |
| the innermost handler of a tag wins (`:38`) | `Handlers.add` / `indexOf` / `apply` | JUSTIFIED | pins the reverse scan in `Handlers.scala:38-44` |
| handlers of distinct tags resolve independently of order (`:45`) | `Handlers.add` / `indexOf` / `apply` | JUSTIFIED | same |
| a subtype suspension tag resolves the supertype handler (`:53`) | `Handlers.add` / `indexOf` | JUSTIFIED | pins the `<:<` test in `Handlers.scala:41` |
| a supertype suspension tag does not resolve a subtype handler (`:59`) | `Handler.Loop` subclass `:60`, `Handlers.add` / `indexOf` | JUSTIFIED | same, negative direction |
| add returns a new collection and leaves the original unchanged (`:65`) | `Handlers.empty` / `add` / `indexOf` | JUSTIFIED | persistence of the internal collection |
| updated replaces at the position and leaves the original unchanged (`:73`) | `Handlers.updated` | JUSTIFIED | pins `Handlers.scala:27-28`, used by the `LoopState` successor path (`Eval.scala:124`) |
| take keeps the outer prefix only (`:82`) | `Handlers.take` / `size` / `indexOf` | JUSTIFIED | pins `Handlers.scala:24-25`, used by every layer pop in `Eval` |
| a Loop clause continues at its declared types (`:91`) | direct handler invocation `:92`, `Handler.Loop.Continue` pattern `:94` | JUSTIFIED (misfiled) | pins the opaque `Outcome` encoding (`Handler.scala:29`, `:33`); belongs in a `HandlerTest.scala` |
| a Loop clause dones with the bare value (`:98`) | `Handler.Loop` subclass `:99`, direct invocation `:101` | JUSTIFIED (misfiled) | pins `done` erasing to the bare value (`Handler.scala:48-49`) |
| a LoopState clause carries its successor (`:107`) | `Handler.LoopState` subclass `:108`, direct invocation `:110`, `:114` | JUSTIFIED (misfiled) | pins `Continue2` carrying the successor handler (`Handler.scala:25`, `:40`) |
| a Cont clause receives the continuation at its declared types (`:120`) | `Handler.Cont` subclass `:121`, direct invocation `:123` | JUSTIFIED (misfiled) | the only coverage of `Handler.Cont`'s clause shape (`Handler.scala:11-12`); `Eval` cannot run it yet |

## ArrowEffectTest.scala

54 of 56 tests are already written entirely against `ArrowEffect` plus `<`. Only the two rows below touch internals.

| Test (line) | Internal APIs touched | Verdict | Reason |
|---|---|---|---|
| suspendWith / the node is its own continuation (`:142`) | `Kyo.Suspend` pattern and `cont` read `:145` | JUSTIFIED | the fused node is what `suspendWith` exists to build (`ArrowEffect.scala:50-57`); "the suspension is its own `Arrow.Transform`" has no value-level observation |
| a handler stepping a rescue at the exact budget boundary floats it outward (`:428`) | `Safepoint.Period` `:433` | JUSTIFIED | the boundary is defined by the internal constant (`Safepoint.scala:14`); reading it is what keeps the test from hard-coding 512 |
| the other 54 tests | none | n/a | already reached through `suspend`, `suspendWith`, `handle`, `resume`, `stop`, `loop`, `partial`, `map`, `eval`, `evalPartial` |

Two things in this file are worth a maintainer's eye even though neither is an internal API:

- `:16` builds a subtype suspension with `Tag[AskSub].asInstanceOf[Tag[Ask]]` because `suspend` ties the tag to `E`. It is a type-system bypass in test support, not machinery access, but it does mean "answers operations of a subtype effect" (`:121`) exercises a tag pairing the public API cannot produce.
- `:312`, `:465` cast `partial`'s `A < (E & S)` result to `Int < Any` before `eval`. That is inherent to `partial` keeping `E` in the row (`ArrowEffect.scala:313`), not a test defect.

## PendingTest.scala

| Test (line) | Internal APIs touched | Verdict | Reason |
|---|---|---|---|
| construction past the safepoint budget rescues instead of overflowing (`:57`) | `Safepoint.Period` `:60` | JUSTIFIED | the budget is the mechanism under test (`Pending.scala:35-41`) |
| a long map tower on a rescued computation evaluates in bounded stack (`:64`) | `Safepoint.Period` `:69` | JUSTIFIED | same |
| depth leaked by throwing maps resets at the eval loop (`:73`) | `Safepoint.Period` `:83`, `:85` | JUSTIFIED | the test is about depth accounting after a throw skips `Safepoint.exit` (`Pending.scala:40`), reset at `Eval.scala:13-15` |
| evalPartial pauses at the stop check and the remainder resumes (`:93`) | `Safepoint.Period` `:100`, `Kyo[?, ?]` type pattern `:103` | JUSTIFIED | same budget reason; the `Kyo` check is the pending-ness pin discussed below |
| the other 8 tests (`:8`, `:13`, `:23`, `:28`, `:33`, `:39`, `:45`, `:51`) | none | n/a | pure `<` surface |

## Probes (dev artifacts, not test classes)

Both files are `object`s with a `main` method under `jvm/src/test`. They compile with the test configuration but no ScalaTest runner picks them up, so `sbt kyo-kernel2JVM/test` does not execute them.

### HandlersProbe.scala

An executable spec for the `Handler.Cont` mechanism that `Eval` does not implement (stated at `:8-:11`). It reimplements evaluation over the node protocol: `Kyo.Handled` / `Kyo.Suspend` / `Kyo.Defer` pattern matches and field reads (`:51`, `:71`, `:99`), `Handlers` (`:47`, `:73`, `:82`), `Arrow.step` / `head` / `tail` (`:33`, `:37`), `Safepoint` (`:48`, `:101`, `:129-:135`), `Kyo.unnest` (`:139`), `<.lift` (`:95`), plus `Handler.Cont` and `Handler.Loop` subclasses in `main` (`:188`, `:191`, `:201`).

| Scenario | What it covers | Verdict |
|---|---|---|
| P1 clause parks to an outer capture scope (`:187-:196`) | a `Handler.Cont` region answering a park raised inside a `Handler.Loop` clause | JUSTIFIED, and it is the only coverage of this behavior anywhere in the module |
| P2 multi-shot capture re-enters crossed scopes (`:199-:213`) | a `Cont` clause invoking its continuation twice, crossed scopes replayed per shot | JUSTIFIED, only coverage |
| P3 scope cycle cost (`:215-:230`) | measures the region path against the `resume` trampoline | JUSTIFIED as a probe measurement; it is not a correctness claim |

Recommendation: P1 and P2 are real semantics that no test asserts. When `Eval` learns to run `Cont` regions, they should become `EvalTest` cases (constructed through `ArrowEffect.handle` if that constructor is Cont-backed), and the probe should be deleted at that point.

### EvalProbe.scala

Same category of access, wider: `Kyo.Handled` construction through its own `region` helper (`:54-:59`), `Kyo.Suspend` / `Kyo.Defer` matches (`:83`, `:154`), `Handlers` (`:82`, `:85`, `:102`), `Handler.Loop` / `LoopState` subclasses (`:174`, `:191`), `Nested.lift` / `Nested.unnest` (`:100`, `:104`, `:171`), `Arrow.Transform` (`:41-:50`), `Safepoint` (`:78`, `:156`, `:166-:170`).

| Scenarios | What they cover | Verdict |
|---|---|---|
| S1 to S20 (`:245-:459`) | region evaluation semantics | JUSTIFIED as internal machinery access, but redundant: every scenario has a 1:1 counterpart in `EvalTest` (S1 = `EvalTest:40`, S2 = `:45`, S3 = `:52`, S4 = `:58`, S5 = `:69`, S6 = `:83`, S7 = `:90`, S8 = `:104`, S9 = `:123`, S10 = `:133`, S11 = `:139`, S12 = `:150`, S13 = `:162`, S14 = `:169`, S15 = `:180`, S16 = `:197`, S17 = `:226`, S18 = `:289`, S19 = `:298`, S20 = `:307`) |
| P1 to P3 measurements (`:463-:519`) | compares the probe's copy of the algorithm ("rewrite") against `eval` | Obsolete: the rewrite landed in `5b0a910b4c` ("Eval evaluates regions as merged layers"), so both sides now run the same algorithm |

Recommendation: `EvalProbe.scala` is fully subsumed and should be deleted. Nothing in it is uniquely covering.

---

## Summary

### Counts

| Verdict | EvalTest | KyoTest | HandlersTest | ArrowEffectTest | PendingTest | Total |
|---|---|---|---|---|---|---|
| SHOULD-MIGRATE | 23 | 0 | 0 | 0 | 0 | 23 |
| JUSTIFIED | 1 | 5 | 14 | 2 | 4 | 26 |
| No internal use | 2 | 0 | 0 | 54 | 8 | 64 |
| Total | 26 | 5 | 14 | 56 | 12 | 113 |

Probes are counted separately: 2 files, 3 scenarios in `HandlersProbe` (all uniquely covering), 20 scenarios plus 3 measurements in `EvalProbe` (all redundant).

Every SHOULD-MIGRATE row is in `EvalTest`, and every one of them is blocked on the same thing: the stateless and stateful `ArrowEffect.handleLoop` overloads landing.

### Ordered migration list

Groups are ordered so that each one shares a single translation rule and the earlier groups establish the pattern for the later ones. Within a group the order does not matter.

1. Stateless region, settled answer, translation `continue(cont(answer))`: `EvalTest:40`, `:45`, `:52`, `:83`, `:289`, `:298`.
2. Stateless region, `done`: `EvalTest:90`, `:104`.
3. Stateless region, answer pending on another effect: `EvalTest:58`, `:69`.
4. Stateless region, clause suspends before producing its outcome (the `Outcome < S2` position): `EvalTest:169`, `:180`, `:197`.
5. Stateful region, handler-instance state becomes value state: `EvalTest:123`, `:150`, `:215`, `:226`, `:307`.
6. Stateful region with the handler-identity subtlety: `EvalTest:133`, `:139`.
7. Rejection and partial-evaluation pins, last because each needs a confirmation against the new constructor's behavior: `EvalTest:162`, `:284`, `:243`.

### Tests whose meaning shifts, needing a decision rather than a mechanical rewrite

`Kyo.Handled` takes the handler as `Handler[I, O, E]` (`Kyo.scala:66`), dropping the handler's own `A` and `S` parameters that `Handler.Loop[I, O, E, A, S]` declares (`Handler.scala:14`). A clause's effects are therefore invisible in the node's type, and several tests are written against that gap.

1. **`EvalTest:243`, "a clause does not see handlers inside its own scope".** The node types as `Int < Any` even though the clause raises `Say` (`:246`), and the test calls `.eval` directly (`:252`). Under the public constructor the result is `Int < Say`, and calling `eval` requires an explicit `asInstanceOf[Int < Any]`. The runtime assertion (`IllegalStateException`, and the log showing only `"inner"`) is unchanged. Decision: keep the test with the widening cast, in which case the type now states what the test asserts, or restate it as a compile-level assertion that the clause's `Say` lands in the result row and drop the runtime interception. The two express different things and only the second one survives if `Eval`'s rejection path is later reworked.
2. **`EvalTest:133` and `:139`, `VarHandler`.** `VarHandler` returns `this` when the value does not change (`:37`), which is the only thing that exercises `Eval.scala:123`, `case next if next eq h => hs` (the branch that skips rebuilding the handler chunk). Once state is a plain value, the test can no longer control handler identity. Decision: either the stateful `handleLoop` adapter preserves the handler instance when the state is unchanged (and one test asserts that it does), or a small internal test stays behind, subclassing `Handler.LoopState` purely to pin that branch. Migrating both tests without picking one silently drops the coverage.
3. **`EvalTest:284`, "evalPartial does not evaluate scopes".** The assertion is reference identity between the constructed region and what `evalPartial` returns. It holds only if the public constructor returns the `Kyo.Handled` node itself. Confirm against the parallel change before rewriting; if the constructor is not eager, the test needs a different formulation of "no region was entered".

### Coverage that migration drops

Once `EvalTest` is migrated, `KyoTest` and the probes are the only remaining direct constructions of `Kyo.Handled`. That is the desired end state, but it makes `KyoTest:32` to `:65` the sole coverage of `Handled.map`'s cont chaining, and `KyoTest:81` the sole coverage of `Eval.scala:149`. Neither should be removed as "redundant with the public tests", because they are not.

### Representation pins with no public equivalent

Three assertions observe whether a value is settled or pending by looking at its runtime shape: `EvalTest:260` (`asInstanceOf[Int]` on an `Int < Any`), `EvalTest:280` (`isInstanceOf[Kyo.Defer[?, ?, ?]]`), `PendingTest:103` (`isInstanceOf[Kyo[?, ?]]`). They are the reason those tests are JUSTIFIED: `evalPartial` returns `A < S` and nothing in the public surface answers "did this settle". If a settled-ness predicate is ever added, these three are the call sites to revisit.

### A caution about eagerness, if any `handle` test is ever moved onto `handleLoop`

The trampoline `ArrowEffect.handle` (`ArrowEffect.scala:61`) answers at the call site, which `ArrowEffectTest:28` pins explicitly ("eager: answering runs the continuation at the handle call"). A region constructor is inert until `eval` walks it. No test in this audit requires moving between the two, and no `EvalTest` case asserts a side effect before `.eval`, so nothing in the migration list is affected. It matters only if someone later collapses the two families.

## Incidental findings

1. **Four tests are in the wrong file.** `HandlersTest:91`, `:98`, `:107`, `:120` test `Handler.scala`, not `Handlers.scala`. Under the repo rule that a test file shares its prefix with the source it covers, they belong in a new `HandlerTest.scala`. `HandlersTest:120` is also the only place `Handler.Cont`'s clause shape is asserted at all.
2. **`EvalProbe.scala` is dead weight.** Every scenario duplicates an `EvalTest` case (mapping above) and its benchmark now compares the shipped algorithm against a copy of itself. Deleting it costs no coverage.
3. **`HandlersProbe.scala` carries coverage that exists nowhere else** (P1 clause parking, P2 multi-shot capture). It should be promoted to real tests when `Eval` gains `Cont` support, not deleted with the other probe.
4. **Neither probe is a test class**, so neither runs in CI; they only compile. Under the repo's naming rule they are scratch artifacts awaiting promotion or removal, which items 2 and 3 resolve.
5. **`EvalTest:147`** writes `assert(log.toList == List("x").map(_ => "s"))` where the assertion is `List("s")`. The indirection reads like a leftover from an earlier version of the test and should be written directly.
