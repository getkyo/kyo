# Contributing to kyo-kernel2

Module-specific guide for kyo-kernel2, the effect-system kernel. Read the repository-root [CONTRIBUTING.md](../CONTRIBUTING.md) first: it carries the conventions that apply across all of Kyo (naming, the kyo type vocabulary, scaladoc, inline guidelines, test patterns, the unsafe boundary). This document records only what is specific to the kernel: the structural-property invariant that governs every design decision here, the evaluation model, the recursion-carrier rule, the currency discipline, the mechanism checklist, and the kernel's testing, performance, and naming doctrine.

None of these rules is a preference. Each one was paid for by a concrete defect in this kernel's own development, and each section cites the code or the commit where the bill arrived. The full record is [kernel2-iteration-report.md](../kernel2-iteration-report.md); see [The history behind this guide](#the-history-behind-this-guide).

## What kyo-kernel2 is

The layout mirrors the old kernel: public files at `kyo.kernel`, machinery in `kyo.kernel.internal`, and the `kyo`-level aliases and utilities in `shared/src/main/scala/kyo`.

Public, under `kyo/kernel`:

- `Pending.scala`: the currency. `opaque type <[+A, -S] = A | Kyo[A, S]` with the representation sealed behind a private `fromKyo` conversion, and the extensions: `map`, `flatMap`, `andThen`, `unit`, `eval`, `evalNow`, `flatten`, and the `handle` pipe overloads.
- `Implicits.scala`: the implicit evidences the `<` companion extends: the lift family under the `CanLift` discipline, `abortCastUnit`, `liftPureFunction1-6`, and the `Render` given.
- `ArrowEffect.scala`: the effect protocol, fully inline. `suspend` / `suspendWith` create operations; the handling variants are `handle` (Cont) with its `handleWith` form that fuses the continuation in place of the identity exit arrow, the three `handleLoop` overloads mirroring the old kernel's organization (stateless; stateful delegating to the canonical; canonical stateful with `done: (State, A) => B` observing the final state, bypassed by a clause's `Loop.done`), `handleFirst` (First), which answers one operation and leaves, and `handlePartial`, the eager driver for scheduler integration. `handleCatching` is not a variant of its own: it is `handle` over `Effect.catching` with the clause wrapped in the same guard. `dispatchFirst` handles nothing at all: it peels region nodes to the standing operation and runs a clause on its input for the side effect, entering no handler and stepping no `Defer`, which is what lets a scheduler inspect a remainder it will never evaluate.
- `Effect.scala`: the effect base class, `defer` (a Defer node built with its transform arrow in place), and `catching`, whose guard rewraps continuations so every resumed step stays covered.
- `Loop.scala`: stack-safe user-level iteration; its `Outcome` encoding is also what handler clauses return.

Internal, under `kyo/kernel/internal`:

- `KyoInternal.scala`: the node shapes (`Suspend`, `Defer`, and the region nodes `Handled`, `HandledState`, `HandledFirst`, each with a diagnostic `toString`), and the `Nested` box that keeps boxed data distinguishable from computations.
- `Handler.scala` / `Handlers.scala`: the four handler kinds as traits with an abstract `apply` (`Cont`, `Loop`, `LoopState`, `First`, all pure logic; a region's state lives in its node and its cell, never in the handler) and the spine, the stack of entered regions: one immutable cell per region, linked through `prev` with `Empty` as the empty stack. Which kinds of cell exist is `Handlers.scala`'s own business. A walk elsewhere reads the four values every region has (`tag`, `handler`, `exit`, `prev`) and asks the spine for the rest: `find` resolves an operation, `push` enters a region, `replace` swaps a cell, `withState` advances one, and `rebuilt` reifies one as a region node. The operation dispatch is the exception and reads `handler`, since the kinds are the vocabulary the handling variants already publish.
- `Eval.scala`: the evaluator. One flat loop over the erased `Any < Nothing` currency; `Eval.partial` is the scheduler entry that yields residuals instead of throwing or spinning, driven only by the thread's `Safepoint.stop` signal.
- `Safepoint.scala`: the per-thread depth budget (`Period = 512`, 8192 line-strided slots with overflow degradation) that bounds fused execution, and the `Stop` wrapper in `owners` through which `Safepoint.stop(thread)` requests preemption, read only at entry and on the budget's slow path.
- `CanLift.scala`: the lift's evidence and the system's one macro. The pending lint rides the NotGiven parameter, resolved where the conversion is written (so generic paths are waived and stay sound through the runtime box); the macro rejects kyo module singletons; `CanLift.lift` is the runtime box bridge the conversion's expansion calls.

At the `kyo` level: `Arrow.scala` (user-facing typed continuations; `Arrow.AndThen.step` is the kernel's one stack-safe recursion carrier), `kernel.scala` (the `<` and `Loop` aliases, `Id`, `Const`) and `Kyo.scala` (the sequential collection operations over computations).

## Properties are structural, not enforced

This is the module's headline invariant, and it is a claim about categories of correctness, not about style. A property held by the shape of the representation cannot regress: there is no code whose job is to keep it true, so there is no code that can get it wrong, and there is nothing a future editor has to hold in their head. A property held by a dedicated mechanism is different in kind: it is a proof obligation discharged by hand at every edit, forever, and the mechanism is a habitat for bugs. When you design a change to this kernel, the question to ask of every property you rely on is "what keeps this true?". If the answer is a code path, you are adding an obligation. Prefer the representation in which the property is a corollary. The maintainer's acceptance criterion for the current evaluator, verbatim from the record, was a solution that "provides the properties we need out of the box".

The strongest evidence is this kernel's own evaluator. The first complete `Eval` (commit `6b68934741`) answered operations at a distance through the handler collection while tracking scopes on the Java call stack, and needed four mechanisms to bridge that split. This is the deleted shape, quoted from that commit:

```scala
// DELETED shape, commit 6b68934741. Do not reintroduce any of these pieces.
private def evalLoop[A, S](v0: A < S, handlers: Handlers, slot: Safepoint.Slot): (A < S, Handlers)

case kyo: Kyo.Handled[i, o, ?, a, b, s] @unchecked =>
    val res = evalLoop(kyo.value, hs.add(kyo.handler), slot)     // one Java frame per scope
...
case done => (new Kyo.Halt(h, done), hs)                          // minted at the operation
...
case halt: Kyo.Halt[?] if halt.owner eq hsExit(hsExit.size - 1) => // consumed frames later
```

Four mechanisms: Java-stack recursion per scope entry, a `Kyo.Halt` climber with positional ownership, a `settle` sub-evaluation for pending clause computations, and a pair-returning `evalLoop` so state updates could travel back out of a frame. Every one of them existed to keep one property true, and every one was wrong at least once. Three defect classes, five red reproductions (committed red at `b0c7859937`): a `done` fired while an inner clause outcome settled was delivered to the wrong scope and leaked the raw `Halt` as a user value (a `ClassCastException` far from the cause); 1M nested scopes overflowed the stack; 1M chained re-raised answers overflowed the stack. The pair returns cost a measured +24 B on every benchmark row that entered `Eval.apply`. Positional ownership is the purest specimen: it was itself a fix for a mechanism, added because a `LoopState` successor replaces its entry and breaks the naive identity check.

The current evaluator (commits `5b0a910b4c`, `b8ad9309e8`, and the spine at `a9a0791beb`) replaces all four with one representation: the value travels with its regions, one immutable cell per region holding the handler, that scope's exit continuation, and a stateful handler's current state, linked through `prev`. Entry is one cell; exit is `prev`; `done` feeds the exit sitting on its own handler's cell:

```scala
case kyo: Kyo.Handled[[X] =>> Any, [X] =>> Any, Nothing, Any, Any, Any, Any] @unchecked =>
    loop(kyo.value, new Node(kyo.handler, kyo.cont, hs))
```

```scala
case done =>
    loop(walk(node.exit, Nested.nest(done)), node.prev)
```

What each mechanism enforced, the shape now implies:

| property | step-1 Eval: enforcement site | merged regions: why it holds |
|---|---|---|
| a `done` reaches its own scope | `Kyo.Halt` plus an ownership check at every scope exit | the owning exit is on the handler's own cell; no climber exists to misroute |
| a clause runs outside its own scope | `settle` sub-call under a prefix, merged back by arithmetic | the loop continues at `node.prev`; there is no second evaluator to disagree with the first |
| state survives an inner scope's exit | `(A < S, Handlers)` returned out of every frame | the state rides the cell, and captures rebuild it into the region node; there is no "back" for state to travel |
| stack safety of scope nesting | nothing; the Java stack | nothing recurses, so nothing can overflow |
| answering allocates nothing | branch duplication to dodge tuples | the loop has no per-bracket structure to allocate |

The mis-delivered `done` was not fixed. It became unrepresentable: there is no `Halt`, so there is no value the wrong match arm can capture. That is the difference between the two categories, and the rewrite proved it cheaply: all five red reproductions went green with zero test edits, and the JMH board came back byte-identical to the pre-mechanism baseline. When you find yourself defending a mechanism because it is well tested, notice that the test is exactly the proof obligation the structural version would not need.

## The evaluation model

After this section you should be able to predict what `Eval` does with any node.

**Currency.** `A < S` is `A | Kyo[A, S]` (`Pending.scala:8`): a settled value and a computation share one runtime channel, which is what makes the hot paths allocation-free. Three node shapes in `Kyo.scala`: `Suspend` (an operation carrying its tag, input, and continuation; `map` chains onto the continuation, delegating tag, input, and frame to the `root`), `Defer` (a budget rescue holding a value and the rest of the chain), and the region node (a computation under a handler, as a value: the computation with its effect still in the row, the typed handler, and the continuation outside the region, where `map` chains, `Kyo.scala:69-70`), which comes in one shape per handler kind, `Handled`, `HandledState`, and `HandledFirst`.

**The handling variants.** `ArrowEffect.handle` (a `Handler.Cont`), the two `ArrowEffect.handleLoop` overloads (`Handler.Loop` and `Handler.LoopState`), and `ArrowEffect.handleFirst` (`Handler.First`) build region nodes and run nothing: handling is a value, and answering happens at `eval`, with a settled input passing through strictly with no node (pinned by `ArrowEffectTest` "lazy: the handled computation is a value and answers at eval", "settled inputs pass through strictly", and "a settled input applies the done clause strictly"). The region node splits by handler kind: `Kyo.Handled` for the stateless kinds, its handler field typed as the Cont-or-Loop union so a stateless region cannot carry a stateful handler, `Kyo.HandledState` whose `state` field is the value this entry of the region starts from, the initial state at construction and the current state on a rebuilt node, so re-entering resumes rather than resetting (absence of state is node shape, not a sentinel), and `Kyo.HandledFirst`, whose inner value type and exit input type differ because the First kind's two clauses meet at a type of their own: the first-operation clause produces it directly and the `done` clause produces it from the settled inner value. That is what forces First to be a kind rather than an encoding over the other three. `done` cannot ride the exit arrow, because a `map` after the region fuses into that arrow and the first-operation path must still reach it, and it cannot ride the region's value, because the continuation handed to the clause would carry it and stop being the continuation the clause's type promises. The handler kinds are traits with an abstract `apply`, so the inline handle variants generate one anonymous instance per region with the handling logic compiled into its body and no function value is allocated; `handleWith` mixes the Cont kind with `Arrow.Transform` so one object is both the handler and the region's exit arrow, while the stateful Loop kind carries its `done` transform as a second handler method (`applyDone`), which the completion arm invokes with the live cell's state and which a clause's `Loop.done(b)` bypasses by arriving at the identity exit already carrying the final `B`. `handlePartial` is the one eager driver: it answers matching operations while the clause returns a present continuation and parks at the first refusal, foreign suspension, pending `Safepoint.stop` request, budget exhaustion, or region node, returning the computation as it stands. It does not rotate; the wrap-not-append re-entry invariant survives in `Effect.catching`'s guard, whose append encoding once agreed with every existing test and still lost the handler behind one trailing transform (commit `727e8e6742`; the pins are the `ArrowEffectTest` "stays in force across a foreign crossing with a trailing transform" family).

**The loop.** `evalLoop` is one flat `@tailrec` loop over the value and the spine, `hs: Handlers`: the stack of entered regions, one immutable cell per region. Behavior by behavior:

1. **Entering a region is one cell.** No call, no frame: `loop(kyo.value, new Node(kyo.handler, kyo.cont, hs))`, and a `HandledState` node seeds its cell's state from the node, so a rebuilt region resumes where it left off.
2. **A settled value pops through the top cell's exit.** Exit order is entry order reversed because popping is following `prev`:

```scala
case v =>
    hs match
        case Empty                  => v
        case n: Node[?, ?, ?, ?, ?] => loop(walk(n.exit, v), n.prev)
        ...
```

3. **An operation resolves to the innermost matching cell.** `Handlers.find` walks `prev` with a `<:<` test against the cell's own `tag` field, so innermost-wins is walk order and a subtype tag resolves a supertype handler. The tag is copied onto the cell at entry rather than read back through the handler: every handle site mints a fresh anonymous handler class, so a read through the handler makes this walk, one call per answered operation, dispatch on a new receiver type per site. On a miss, `eval` throws `IllegalStateException("unhandled suspension: ...")` and `Eval.partial` returns the standing computation reified with its remaining regions, resumable by evaluating it again. `Eval.partial` also yields that residual when a `Safepoint.stop` request is pending on the thread's slot, which is the whole preemption mechanism: dispatch is only through the thread's slot, detection is a volatile read at entry and on the budget's slow path, and the residual is ordinary data. `Eval.partial` stays alongside `handlePartial` because the eager driver parks at region nodes by design, so evaluating regions without throwing on a miss needs the evaluator entry.
4. **A settled `Loop.continue(answer)` feeds the suspension's own continuation and touches nothing else.** This is the hot path; it allocates nothing beyond the clause's outcome box: `loop(walk(kyo.cont, answer), hs)`.
5. **`done` feeds its own cell's exit and continues at `node.prev`.** The discarded cells' exits never run, which is exactly the semantics that `done` skips the inner scopes' remainders.
6. **A clause that suspends before deciding is chained, not evaluated in a sub-call.** The pending computation becomes the current value with the decision chained after it, running at `node.prev`: outside the clause's own scope, because a clause runs outside its own region by construction. The closure captures the spine by reference (the cells are immutable and shared, so capture costs nothing) and the crossed cells are rebuilt around the resumption when the outcome settles.
7. **A continue whose answer is itself pending runs at `node`**: inside the handler's own cell, so a re-raise of the scope's effect is answered by the same handler. Note the asymmetry with 6: a pending clause *outcome* runs outside its own region, a pending *answer* runs inside it. This mirrors the old kernel's `handleLoop` semantics and is what lets a handler express "raise the scope's effect again".
8. **State is a cell replacement.** The clause continues with the next state; the top-cell case is `node.withState(c._1)`, one cell with the handler object untouched, and an interior cell (a stateful handler answering under unrelated inner regions) path-copies the cells above it through `replace`. The reference check is a pure optimization: a false negative rebuilds an identical cell.

```scala
val updated =
    if c._1.asInstanceOf[AnyRef] eq node.state.asInstanceOf[AnyRef] then node
    else node.withState(c._1)
val hs2 = if updated eq node then hs else replace(hs, node, updated)
```

9. **A `Cont` clause receives the continuation as a function that rebuilds the crossed cells per call.** Every call builds a fresh value, so capture is multi-shot by construction; the clause body runs at `node`, so its re-raises are answered by this handler and its exit applies when the body settles.
10. **A `Defer` resets the budget and steps.**
11. **A `First` clause answers once, and its result runs at `node.prev`.** The cell is off the spine before that result is evaluated, so the operations the continuation still carries reach the outer handlers, which is what the continuation's type says: `rebuild` walks from the top cell down to this one and stops, so the value it produces re-enters the crossed inner regions and never this one. When the region settles without an operation instead, the top-cell case feeds the settled value to the `done` clause, whose result the exit consumes.

Two helpers, both cold. `rebuild` walks the spine from a top cell down to a stop cell, asking each for the region node it reconstitutes into, states included; it serves clause resumptions, `Cont` continuations, and residuals, and what it produces the next loop iterations simply re-enter. Data in, data out. `Handlers.replace` swaps one cell, path-copying the cells above an interior update iteratively so pathological depths never reach the Java stack; it lives with the spine because the copy is the spine's own shape.

## Every recursion names its stack-safe carrier

The kernel has exactly one: arrow chains. `Arrow.AndThen.step` flattens a chain iteratively through a thread-local scratch buffer and relinks (`Arrow.scala:76-102`), and the Safepoint budget converts construction past `Period` into `Kyo.Defer` rescues that `Eval` steps flat. Every recursion in the kernel either rides that carrier or is a flat `@tailrec` loop.

The rule for any change: for every recursion you introduce, name the carrier that makes it stack-safe, at design time, in one sentence. If the honest answer is "the Java stack", the recursion is a defect whenever its depth is proportional to program shape, and this is the moment to say so, not after a 1M-depth test overflows. The step-1 evaluator never asked the question: scope entry recursed per `Handled` node and `settle` recursed per re-raised answer, producing two of its three defect classes. The reproductions are permanent guards in `EvalTest`: "enters deeply nested scopes in bounded stack", "opens a scope per recursion step in bounded stack", "settles chained re-raised answers in bounded stack", each at depth 1000000.

A budget is not a carrier. The redesign between step 1 and merged layers kept entry recursion and guarded it with a budget cap that unwound into a `Defer` re-nesting the enclosing regions; the unwind re-paid the whole descent on every cycle, making region entry quadratic in nesting depth, observed as a hang at 1M (recorded in the commit message of `344770888f`). When you find yourself sizing a cap, the question is not "how big" but "what representation has nothing to cap".

## Currency discipline

The implicit lift passes primitives and `String` raw and routes everything else through `Nested.nest`, which boxes only `Boxed` values: `Kyo` nodes and already-nested values. Ordinary user values pass raw. The lift requires `CanLift` evidence, which `NotGiven[A <:< (Any < Nothing)]` derives for everything except statically pending types: ascribing a computation into a nested `< S` position is a compile error with a teaching message, so the footgun in rule 2 cannot arrive through inference anymore. Deliberately holding a computation as data goes through a generic indirection (`def box[A](v: A): A < Any = v`), where the runtime lift boxes it. A macro-based lift with per-type static mode selection was tried and reverted: same-module macro expansion trips the compiler's compilation-suspension bug, and the constraint that matters needs no macro. This gives four rules:

1. **`lift` is for a plain value entering the kernel, exactly once.** A settled result of an evaluation pass is already currency; passing it through `lift` again double-nests it as data.
2. **Know the footgun.** `<` is contravariant in `S`, so a value read at `Any < Nothing` does not conform to an expected `Any < Any`. At any site where a `Kyo` fails to conform to the expected `<` type, the compiler silently applies the implicit conversion and turns the whole computation into data as `Nested(...)`. Nothing fails at that point; the failure surfaces arbitrarily far away as `ClassCastException: kyo.kernel.Nested cannot be cast to ...`. This bit three times during the kernel's development. The canonical bad site was an erased alias pinning the effect slot to `Nothing`, making every field read a conversion site; the debugging session was long precisely because the construction sites had been pinned with explicit type parameters while the real culprits were the erased reads.
3. **The defense is a cast at the boundary, with a comment saying why.** The pattern in the source (`Eval.scala:211-214`):

```scala
// the crossed layers are restored as plain region nodes around the
// resumed computation; the casts keep the erased construction out of the
// implicit lift, which would nest the computation as data
private def rebuildFrom(from: Int, value: Any < Any, hs: Handlers, exits: Exits): Any < Any =
```

4. **When a constructor's result must unify with an expected `< S`, return the currency type, and cast to a type the constructor names itself.** The `Loop.continue` constructors are the worked example (`Loop.scala`). A handler clause expects `Outcome[answer < row, result] < S2`, whose answer slot is pending so a clause may answer effectfully, while most clauses answer with a settled value. Three bare-return designs were built and gated against that expectation before the currency-shaped return settled it. Plain bare constructors leave 64 settled-answer clause sites red: the settled-into-pending step is a conversion, and a conversion blocks expected-type propagation into the constructor's type parameter, so the answer type infers from the argument and the invariant `Outcome` rejects it. Dedicated `Outcome`-lifting conversions in the companion fix those sites but break effectful clause bodies: the conversion search inside a `map`-final lambda cycles against the lambda's own result-type inference, with dotc reporting cyclic errors and exceeding its recursion limit. Carrying the payload row on `Continue` (`_1: A < S`, covariant payload, contravariant row) makes every site infer by plain subtyping with no conversion at all, and it passed the full suite, but the field then erases to `Object` where the old `_1: A` specializes to a primitive at the inline site: the stored box cost `Loop`'s driver 16 extra bytes and 2.9x time per settled iteration (JMH, 100k countdown), which disqualified it. The `<`-shaped return is the remaining design that keeps settled answers inferring out of the box at zero cost: inference solves the answer type from the expectation, the cast stands in for a lift whose boxing arm is unreachable (`Continue` is never `Boxed`), and each cast names its constructor's own result type, because a shared helper whose target comes from inference once solved it to the `Kyo` member of the currency union and failed as a `checkcast` only when a loop ran. `done` returns bare payloads: the ordinary value lift at the clause boundary reaches them (no type parameter needs the expectation), and its boxing arm is what keeps a computation held as data from reading as a clause that suspended.

## When a change wants a new mechanism

The kernel's failure mode is not bad code; it is locally-justified machinery. Every register in the discarded designs answered a real question in isolation, and each was a flattened encoding of something the structure already expressed. Before adding any coordinating device (a register, a marker value that climbs, a sub-evaluator, an ownership protocol, a cap, a save/restore pair), run this list:

1. **Do several failures share this mechanism? Then the mechanism is the bug.** The five red tests against the step-1 evaluator were not five bugs; they were one representation error. Asked individually they yield five patches (a `Halt` case here, a cap there, both were sketched); asked collectively they yield one model in which all five are one case. The productive question is not "how do I fix this failure" but "what is wrong with the composition such that this failure is expressible".
2. **Is the new structure protecting existing structure? The frame is wrong.** A cap guarding a recursion, a register guarding a scan bound, an ownership check guarding a climber: machinery guarding machinery. The budget-cap redesign passed all 17 semantic scenarios and was still quadratic under its own fix.
3. **Ask what single representation makes all the failing cases one case.** That question, applied to five reds, produced the merged-layers evaluator: it deleted `Halt`, `settle`, the pair returns, and positional ownership (`5b0a910b4c`, `b8ad9309e8`) and the defects became unrepresentable.
4. **Search the tree for the precedent before inventing.** The final model is `AndThen.step`'s iterative flattening applied to regions, generalized from the one-layer rewriting discipline that survives in `Effect.catching`'s guard. Both were in this module the whole time; effort went into inventing where it should have gone into recognizing. Read `Arrow.AndThen.step` and `Effect.catching` before designing any new evaluation mechanism, and ask whether your problem is one of them at a different scale.

The maintainer's framing when this pattern was live, verbatim: "it seems like you're going with a virtual machine mindset not composition and there's a lot of unnecessary complexity due to that". That was said of a probe that had accumulated a frames array, a depth register, a bound register with save and restore, marker frames, and in-place truncation, with no test failing. The rewrite that followed was smaller than what it replaced and closed a semantic hole the machinery had been hiding.

Mechanisms also have two trailing costs covered below: each one is an allocation site (see [Performance](#performance)) and each one tends to arrive with new vocabulary (see [Terminology, naming, and typing](#terminology-naming-and-typing)).

## Testing

The root guide's reproduce-before-fix and meaningful-tests rules apply in full. The kernel-specific doctrine:

- **Semantic reproductions before fixes, always, because they are what make a representation swap cheap.** The merged-layers rewrite replaced the entire evaluator and the suite came back 113/113 with zero test edits, flipping the five committed-red reproductions green. That was only possible because the reproductions assert values and observable effects (`eval == -9`, `!reached`, no `StackOverflowError` at depth 1000000) and name no mechanism. A test written against the mechanism must be rewritten alongside it, at which point it no longer proves the semantics were preserved.
- **Tests state observable compositional semantics, never mechanism.** If an expectation cannot be stated without naming an internal coordination device, the expectation is about the mechanism, and it will not survive the next representation.
- **Tests reach features through the user-facing surface**: `ArrowEffect.suspend` / `suspendWith` / `handle` / `handleLoop` / `handlePartial`, `Effect.defer` / `catching`, the `<` extensions including `evalNow`, and `Loop.continue` / `Loop.done`. Direct node construction, `Handlers`, `Nested`, and `Safepoint` internals appear only in the `kyo.kernel.internal` test package, where the internal unit is itself under test: `HandlersTest` over the collection, `HandlerTest` over the clause shapes, `KyoTest` over the node shapes, `EvalTest` over evaluation semantics and `Eval.partial`, and `SafepointTest` over the budget and the stop signal. The migration that produced this state is recorded per test in [kernel2-test-api-audit.md](../kernel2-test-api-audit.md).
- **Probes are disposable dev artifacts.** An executable spec under `jvm/src/test`, named after the source it probes, with a header stating the model it encodes; no test runner picks it up. Probes made it possible to build competing evaluator models and measure both without one speculative edit to main source, and they are a liability if left behind: the repo rule is that scratch files are deleted before the change ships, their validated assertions folded into the matching `*Test.scala`. `EvalProbe.scala` and `HandlersProbe.scala` served that role for the evaluator models and are deleted; the multi-shot capture coverage they carried lives in the public `handle` tests.

## Performance

Performance in this kernel is structural, the same way correctness is. Allocation and time accumulate at coordination points, and coordination points are exactly what mechanisms create. The pair-returning `evalLoop` signature cost a measured +24 B on every benchmark row that entered `Eval.apply`; no local optimization could remove it while the mechanism stood (a first round of tuple-elimination fixes removed only the avoidable ones), and it disappeared the moment the mechanism did, returning every row byte-identical to the pre-mechanism baseline.

The budget: answering (the settled-continue arm) allocates nothing beyond the clause's outcome box. Cold paths may allocate: pending clause chains, `LoopState` successors, `rebuildFrom` nodes, `Defer` rescues.

The gate: any change to `Eval.scala`, the node shapes in `Kyo.scala`, or `Arrow.scala` is validated by a JMH A/B (`jvm/src/jmh/scala/kyo/kernel/bench/KernelBench.scala`) against a frozen snapshot. The snapshot discipline: build the baseline classpath before the change and freeze it; run both sides from frozen classpaths, three forks, with `-prof gc`; never run benchmarks while anything else loads the machine. Read allocation first: time is noisy, bytes per op are not, and an allocation delta localizes to the exact rows that exercise the new structure (the +24 B appeared only on rows entering `Eval.apply`, which is what identified its source).

## Terminology, naming, and typing

- **The evaluator is `eval`. Names in the kernel are maintainer-approved; introduce no new terminology in code, comments, or docs without a ruling.** This is not stylistic. Machine vocabulary arrives with machine designs, and it arrives first: `drive`, `exitCondition`, `downgradeStops`, `shadow`, and `Entry` were each rejected on sight or renamed away, and commit `6160c23c3b` exists solely to rename `drive` to `eval`. If a new noun seems necessary to describe what the evaluator is doing, first check whether the noun is naming a compensation.
- **Properly typed code, with casts only at documented boundaries.** The sanctioned boundaries are: the erased cell patterns after `Handlers.find` (the walk proves the tag, the type system cannot), the erased currency inside `evalLoop`, the effect slot pinned to `Nothing` where a pattern-bound effect type loses its GADT bound, and the lift-avoidance casts of the currency discipline. Each site carries a comment naming its boundary. A cast that fits none of these categories is a design smell to resolve, not a typing convenience.
- **No explicit type parameters unless inference genuinely fails, and then the site says so.** The current examples: `rebuildFrom`'s erased `Kyo.Handled[[B] =>> Any, [B] =>> Any, Nothing, Any, Any, Any]` construction under its lift comment, and `Eval`'s successor construction in the `LoopState` arm.
- **No implicits for internal plumbing.** Handlers are threaded explicitly through `Eval`; they are never implicit parameters. This was ruled before it could be built, and it stays ruled.

## The history behind this guide

Every rule above is a distillation of a recorded incident, and the record is worth reading before making a substantial change here. [kernel2-iteration-report.md](../kernel2-iteration-report.md) carries the full account: the timeline across three kernel implementations, the catalog of flawed mechanisms with the defect each one caused and how it was found, the maintainer's guidance verbatim, and the analysis of why the merged-layers model made the properties structural. [kernel2-handlers-design.md](../kernel2-handlers-design.md) is the ruled design for the handler kinds; [kernel2-test-api-audit.md](../kernel2-test-api-audit.md) is the per-test internal-API adjudication. When a rule in this guide seems arbitrary, the report has the defect that produced it.

## Pre-submission checklist (kyo-kernel2)

Beyond the root checklist:

- [ ] Every property the change relies on has an answer to "what keeps this true", and the preferred answer is the shape of the representation, not a code path. A mechanism added to protect a property is a flag, not a fix.
- [ ] Every new recursion names its stack-safe carrier (the arrow chain via `AndThen.step` plus the Safepoint budget, or a flat `@tailrec` loop). Nothing recurses on the Java stack proportional to program shape; the 1M-depth `EvalTest` scenarios pass.
- [ ] No new structure guards existing structure. If several failures shared one mechanism, the representation changed, not the patch count.
- [ ] No re-lift of settled currency. Every erased cast sits at one of the documented boundaries with a comment; any read at a pinned effect slot (`< Nothing` in particular) is cast rather than left to the implicit lift.
- [ ] Tests assert concrete values and observable effects through the public surface; internal construction appears only where the internal unit is under test. No probe ships; a probe that validated the change is deleted with its assertions folded into the matching `*Test.scala`.
- [ ] A change to `Eval.scala`, `Kyo.scala`, or `Arrow.scala` carries a JMH A/B against a frozen baseline with `-prof gc`, run on an idle machine; the answering rows stay allocation-flat.
- [ ] No new terminology; no explicit type parameters without a forcing comment; no implicit parameters for internal plumbing.
