# Contributing to kyo-kernel

Module-specific guide for kyo-kernel, the effect-system kernel. Read the repository-root [CONTRIBUTING.md](../CONTRIBUTING.md) first: it carries the conventions that apply across all of Kyo (naming, the kyo type vocabulary, scaladoc, inline guidelines, test patterns, the unsafe boundary). This document records only what is specific to the kernel: the structural-property invariant that governs every design decision here, the evaluation model, the recursion-carrier rule, the currency discipline, the mechanism checklist, and the kernel's testing, performance, and naming doctrine.

None of these rules is a preference. Each one was paid for by a concrete defect in this kernel's own development, and the sections cite the code or the commit where the bill arrived.

## What kyo-kernel is

Public files at `kyo.kernel`, machinery in `kyo.kernel.internal`, and the `kyo`-level aliases and utilities in `shared/src/main/scala/kyo`.

Public, under `kyo/kernel`:

- `Pending.scala`: the currency. `opaque type <[+A, -S] = A | Kyo[A, S] | Nested[A]` with the extensions: `map`, `flatMap`, `andThen`, `unit`, `eval`, `flatten`, and the `handle` pipe overloads.
- `ArrowEffect.scala`: the effect protocol, fully inline. `suspend` / `suspendWith` create operations; the handling variants are `handleCont` (the continuation-in-hand form, with an optional `done` clause), `handleLoop` and `handleLoopState` (answer-style clauses, the stateful form threading a state value and observing the final state in `done`), `handleCatching` (handling fused with a recovery scope), and the `private[kyo]` `handleFirst`, which answers one operation and leaves the raw remainder to its caller. `dispatchFirst` handles nothing at all: it peels region nodes to the standing operation and runs a clause on its input for the side effect, entering no handler, which is what lets a scheduler inspect a remainder it will never evaluate.
- `ContextEffect.scala`: value-shaped effects. `suspend` / `suspendWith` read the innermost binding; `handle` installs one, with `fork`, `join`, and `release` parameters deciding what happens at the edges of the binding's extent.
- `Effect.scala`: the effect base class, `defer`, `catching` (whose scope is a stack entry the eval consults while unwinding), and `bracket` (acquire, use, release, with the release owed on every path including an abandoned remainder).
- `Isolate.scala`: the three-phase fork-crossing abstraction (`capture`, `isolate`, `restore`) over the `Remove` / `Keep` / `Restore` rows, with `run`, `nest`, `andThen`, `use`, and the macro derivation for context effects.
- `Loop.scala`: stack-safe user-level iteration; its `Outcome` encoding is also what the loop-family handler clauses return.

Internal, under `kyo/kernel/internal`:

- `KyoInternal.scala`: the node shapes (`Suspend`, `Defer`, `Catching`, `Binding`, and the region node `Handle`, each with a diagnostic `toString`).
- `Nested.scala`: the box that keeps computations held as data distinguishable from suspensions; `nest` and `unnest` are the two ends of the representation contract below.
- `Handler.scala`: the handler kinds as `Arrow.Region` subclasses with the handling logic compiled into one anonymous instance per region.
- `Stack.scala`: the evaluator's stack of entered regions, states, and owed finalizers. Mutable, pooled per thread, and never escaping: dumps reify the live portion into immutable values, so everything handed out is a complete value.
- `Eval.scala`: the evaluator. One flat loop over the erased currency; `Eval.partial` is the scheduler entry that yields residuals instead of throwing or spinning, parking on the thread's `Safepoint` stop signal.
- `Safepoint.scala` (per platform, under `jvm-native` and `js-wasm`): the per-thread depth budget that bounds fused execution and the preemption channel. On jvm-native a stop is dispatched to the running thread's slot; on js-wasm the scheduler arms a deadline through `Safepoint.deadline` and expiry answers the stop checks.
- `CanLift.scala`: the lift's evidence and the system's one macro. The pending lint rides the NotGiven parameter, resolved where the conversion is written (so generic paths are waived and stay sound through the runtime box); the macro rejects kyo module singletons.
- `Implicits.scala`: the implicit evidences the `<` companion extends: the lift family under the `CanLift` discipline, `abortCastUnit`, the pure-function lifts, and the `Render` given.
- `EffectTrace.scala`, `Debugger.scala`, `Finalizer.scala`: the failure-trace attachment, the diagnostics seam, and the release entries the stack holds apart from its regions.

At the `kyo` level: `Arrow.scala` (typed continuations; `Arrow.AndThen.step` is the kernel's one stack-safe recursion carrier), `kernel.scala` (the `<` and `Loop` aliases, `Id`, `Const`) and `Kyo.scala` (the sequential collection operations over computations).

## Properties are structural, not enforced

This is the module's headline invariant, and it is a claim about categories of correctness, not about style. A property held by the shape of the representation cannot regress: there is no code whose job is to keep it true, so there is no code that can get it wrong, and there is nothing a future editor has to hold in their head. A property held by a dedicated mechanism is different in kind: it is a proof obligation discharged by hand at every edit, forever, and the mechanism is a habitat for bugs. When you design a change to this kernel, the question to ask of every property you rely on is "what keeps this true?". If the answer is a code path, you are adding an obligation. Prefer the representation in which the property is a corollary. The maintainer's acceptance criterion for the evaluator, verbatim from the record, was a solution that "provides the properties we need out of the box".

The strongest evidence is this kernel's own history. The first complete evaluator (commit `6b68934741`) answered operations at a distance through a handler collection while tracking scopes on the Java call stack, and needed four mechanisms to bridge that split: Java-stack recursion per scope entry, a `Halt` climber with positional ownership, a `settle` sub-evaluation for pending clause computations, and a pair-returning loop so state updates could travel back out of a frame. Every one of them existed to keep one property true, and every one was wrong at least once (three defect classes, five red reproductions, committed red at `b0c7859937`): a `done` fired while an inner clause outcome settled was delivered to the wrong scope and leaked the raw climber as a user value, and two 1M-depth scenarios overflowed the stack. The pair returns cost a measured +24 B on every benchmark row that entered the evaluator.

The evaluator that replaced it deletes all four: the value travels with its regions, entry pushes one stack slot, exit pops it, and `done` feeds the exit sitting at its own handler's slot. The mis-delivered `done` was not fixed; it became unrepresentable, because there is no climber for the wrong match arm to capture. All five red reproductions went green with zero test edits. When you find yourself defending a mechanism because it is well tested, notice that the test is exactly the proof obligation the structural version would not need.

## The evaluation model

After this section you should be able to predict what `Eval` does with any node.

**Currency.** `A < S` is `A | Kyo[A, S] | Nested[A]` (`Pending.scala`): a settled value and a computation share one runtime channel, which is what makes the hot paths allocation-free, and a computation deliberately held as data travels boxed as `Nested` so the evaluator cannot mistake it for a suspension. The representation contract has four clauses:

- **Nest exactly once** at the public lift boundary (the `CanLift` emission), and only for `Boxed` values (computations and already-nested values); ordinary user values pass raw.
- **Carry opaquely**: the evaluator moves union values without inspecting payloads.
- **Unnest exactly once** at delivery (`map`'s strict arm, handler completions, the eval entry).
- **Suspension continuations receive raw payloads**, never union representations; feeding a union value into a suspension double-wraps.

**Nodes.** `Suspend` is an operation carrying its tag, input, and continuation; `map` chains onto the continuation. `Defer` holds a computation unevaluated until an eval reaches it, and is also the budget rescue shape. `Catching` is a recovery scope; `Binding` is a context value scoped to an extent, carrying its own `fork` / `join` / `release` strategy for crossings. `Handle` is a region: a computation with its effect still in the row, under a typed handler, with the continuation outside the region. Handlers are `Arrow.Region` subclasses; the inline handle variants generate one anonymous instance per region with the handling logic compiled into its body, so no function value is allocated.

**The loop.** `Eval` is one flat `@tailrec` loop over the value and a `Stack` of entered regions. Entering a region pushes one slot; a settled value pops through the top slot's exit; an operation resolves to the innermost matching slot by tag, so innermost-wins is walk order and a subtype tag resolves a supertype handler. Answer-style clauses (`handleLoop`, `handleLoopState`) answer through the loop outcome and the region resumes with the answer; a `handleCont` clause receives the continuation as a complete value it may apply any number of times or not at all. `done` skips the inner scopes' remainders by feeding its own slot's exit. On an unhandled operation `Eval.apply` fails through `bug.failTag` with the effect trace attached; `Eval.partial` instead returns the standing computation reified with its remaining regions, resumable by evaluating it again, and yields the same residual when a stop request is pending, which is the whole preemption mechanism.

**The stack is mutable and never escapes.** Locals only, pooled per thread, absence as `Maybe` never `null`. Everything handed out is a complete value, valid in any context, any number of times: dumps reify the live portion into immutable segments, so captured continuations are multi-shot by construction and replays are independent. Finalizers are held apart from the region entries so a fold cannot bury one; an eval that ends holding a continuation a clause never applied runs the owed releases at the boundary.

**Budget and preemption.** The per-thread `Safepoint` budget bounds fused execution: a chain that exhausts it is reified through a `Defer` rescue and the loop re-enters flat. The same slot carries preemption: a stop request parks the evaluation at the next budget check, and the residual is ordinary data. On js-wasm the scheduler arms a deadline instead of dispatching to a thread, and expiry answers the same checks.

## Every recursion names its stack-safe carrier

The kernel has exactly one: arrow chains. `Arrow.AndThen.step` flattens a chain iteratively and relinks, and the Safepoint budget converts construction past the period into `Defer` rescues that `Eval` steps flat. Every recursion in the kernel either rides that carrier or is a flat `@tailrec` loop.

The rule for any change: for every recursion you introduce, name the carrier that makes it stack-safe, at design time, in one sentence. If the honest answer is "the Java stack", the recursion is a defect whenever its depth is proportional to program shape, and this is the moment to say so, not after a 1M-depth test overflows. The first evaluator never asked the question: scope entry recursed per region node and `settle` recursed per re-raised answer, producing two of its three defect classes. The reproductions are permanent guards in `EvalTest`, each at depth 1000000.

A budget is not a carrier. An intermediate redesign kept entry recursion and guarded it with a budget cap that unwound into a `Defer` re-nesting the enclosing regions; the unwind re-paid the whole descent on every cycle, making region entry quadratic in nesting depth, observed as a hang at 1M (recorded in the commit message of `344770888f`). When you find yourself sizing a cap, the question is not "how big" but "what representation has nothing to cap".

## Currency discipline

The implicit lift passes primitives and `String` raw and routes everything else through `Nested.nest`, which boxes only `Boxed` values: `Kyo` nodes and already-nested values. Ordinary user values pass raw. The lift requires `CanLift` evidence, which `NotGiven[A <:< (Any < Nothing)]` derives for everything except statically pending types: ascribing a computation into a nested `< S` position is a compile error with a teaching message. Deliberately holding a computation as data goes through a generic indirection (`def box[A](v: A): A < Any = v`), where the runtime lift boxes it. This gives four rules:

1. **`lift` is for a plain value entering the kernel, exactly once.** A settled result of an evaluation pass is already currency; passing it through `lift` again double-nests it as data.
2. **Know the footgun.** `<` is contravariant in `S`, so a value read at `Any < Nothing` does not conform to an expected `Any < Any`. At any site where a `Kyo` fails to conform to the expected `<` type, the compiler silently applies the implicit conversion and turns the whole computation into data as `Nested(...)`. Nothing fails at that point; the failure surfaces arbitrarily far away as `ClassCastException: kyo.kernel.internal.Nested cannot be cast to ...`. This bit three times during the kernel's development. The canonical bad site was an erased alias pinning the effect slot to `Nothing`, making every field read a conversion site; the debugging session was long precisely because the construction sites had been pinned with explicit type parameters while the real culprits were the erased reads.
3. **The defense is a cast at the boundary, with a comment saying why.** Erased construction sites in `Eval` and `Stack` carry casts that keep the implicit lift from firing, each under a comment naming the boundary.
4. **When a constructor's result must unify with an expected `< S`, return the currency type, and cast to a type the constructor names itself.** The `Loop.continue` and `Loop.done` constructors are the worked example (`Loop.scala`, with the design record in its comments). A handler clause expects `Outcome[answer < row, result] < S2`, whose answer slot is pending so a clause may answer effectfully, while most clauses answer with a settled value. Three bare-return designs were built and gated against that expectation before the currency-shaped return settled it: plain bare constructors leave settled-answer clause sites red (a conversion blocks expected-type propagation into the constructor's type parameter), companion conversions cycle dotc inside `map`-final lambdas, and carrying the payload row on `Continue` erases its field to `Object` and cost the loop driver 16 extra bytes and 2.9x time per settled iteration. The `<`-shaped return keeps settled answers inferring out of the box at zero cost: the cast stands in for a lift whose boxing arm is unreachable, because `Continue` is never `Boxed`.

## When a change wants a new mechanism

The kernel's failure mode is not bad code; it is locally-justified machinery. Every register in the discarded designs answered a real question in isolation, and each was a flattened encoding of something the structure already expressed. Before adding any coordinating device (a register, a marker value that climbs, a sub-evaluator, an ownership protocol, a cap, a save/restore pair), run this list:

1. **Do several failures share this mechanism? Then the mechanism is the bug.** The five red tests against the first evaluator were not five bugs; they were one representation error. Asked individually they yield five patches; asked collectively they yield one model in which all five are one case. The productive question is not "how do I fix this failure" but "what is wrong with the composition such that this failure is expressible".
2. **Is the new structure protecting existing structure? The frame is wrong.** A cap guarding a recursion, a register guarding a scan bound, an ownership check guarding a climber: machinery guarding machinery. The budget-cap redesign passed all 17 semantic scenarios and was still quadratic under its own fix.
3. **Ask what single representation makes all the failing cases one case.** That question, applied to five reds, produced the current shape and the defects became unrepresentable.
4. **Search the tree for the precedent before inventing.** Read `Arrow.AndThen.step` and `Effect.catching` before designing any new evaluation mechanism, and ask whether your problem is one of them at a different scale.

The maintainer's framing when this pattern was live, verbatim: "it seems like you're going with a virtual machine mindset not composition and there's a lot of unnecessary complexity due to that". That was said of a probe that had accumulated a frames array, a depth register, a bound register with save and restore, marker frames, and in-place truncation, with no test failing. The rewrite that followed was smaller than what it replaced and closed a semantic hole the machinery had been hiding.

Mechanisms also have two trailing costs covered below: each one is an allocation site (see [Performance](#performance)) and each one tends to arrive with new vocabulary (see [Terminology, naming, and typing](#terminology-naming-and-typing)).

## Testing

The root guide's reproduce-before-fix and meaningful-tests rules apply in full. The kernel-specific doctrine:

- **Semantic reproductions before fixes, always, because they are what make a representation swap cheap.** A full evaluator replacement has come back green with zero test edits, flipping committed-red reproductions, only because the reproductions assert values and observable effects (`eval == -9`, `!reached`, no `StackOverflowError` at depth 1000000) and name no mechanism. A test written against the mechanism must be rewritten alongside it, at which point it no longer proves the semantics were preserved.
- **Tests state observable compositional semantics, never mechanism.** If an expectation cannot be stated without naming an internal coordination device, the expectation is about the mechanism, and it will not survive the next representation.
- **Tests reach features through the user-facing surface**: `ArrowEffect.suspend` / `suspendWith` / `handleCont` / `handleLoop` / `handleLoopState`, `Effect.defer` / `catching` / `bracket`, `ContextEffect.suspend` / `handle`, the `<` extensions, and `Loop.continue` / `Loop.done`. Direct node construction, `Stack`, `Nested`, and `Safepoint` internals appear only in the `kyo.kernel.internal` test package, where the internal unit is itself under test: `HandlerTest` over the clause shapes, `StackTest` over the stack, `NestedTest` over the representation contract, `EvalTest` over evaluation semantics and `Eval.partial`, `ImplicitsTest` and `CanLiftTest` over the lift, and the jvm-native `SafepointTest` and threading tests over the budget, the stop signal, and cross-thread replay.
- **Probes are disposable dev artifacts.** An executable spec under `jvm/src/test`, named after the source it probes, with a header stating the model it encodes; no test runner picks it up. Probes made it possible to build competing evaluator models and measure both without one speculative edit to main source, and they are a liability if left behind: the repo rule is that scratch files are deleted before the change ships, their validated assertions folded into the matching `*Test.scala`.

## Performance

Performance in this kernel is structural, the same way correctness is. Allocation and time accumulate at coordination points, and coordination points are exactly what mechanisms create. A pair-returning loop signature once cost a measured +24 B on every benchmark row that entered the evaluator; no local optimization could remove it while the mechanism stood, and it disappeared the moment the mechanism did, returning every row byte-identical to the pre-mechanism baseline.

The budget: answering (the settled-answer arm) allocates nothing beyond the clause's outcome box. Cold paths may allocate: pending clause chains, stateful successors, rebuilt region nodes, `Defer` rescues.

The gate: any change to `Eval.scala`, the node shapes in `KyoInternal.scala`, `Stack.scala`, or `Arrow.scala` is validated by a JMH A/B (`jvm/src/jmh/scala/kyo/kernel/bench/KernelBench.scala`) against a frozen snapshot. The snapshot discipline: build the baseline classpath before the change and freeze it; run both sides from frozen classpaths, three forks, with `-prof gc`; never run benchmarks while anything else loads the machine. Read allocation first: time is noisy, bytes per op are not, and an allocation delta localizes to the exact rows that exercise the new structure.

Hot-path method size is a design property, not a micro-optimization: HotSpot inlines by budget, so cold work (currency checks, error paths, region rebuilds, growth loops) moves out of line to keep the hot methods inlinable. `-XX:+PrintInlining` is the highest-signal tool for verifying it.

## Terminology, naming, and typing

- **The evaluator is `eval`. Names in the kernel are maintainer-approved; introduce no new terminology in code, comments, or docs without a ruling.** This is not stylistic. Machine vocabulary arrives with machine designs, and it arrives first: `drive`, `exitCondition`, `downgradeStops`, `shadow`, and `Entry` were each rejected on sight or renamed away, and commit `6160c23c3b` exists solely to rename `drive` to `eval`. If a new noun seems necessary to describe what the evaluator is doing, first check whether the noun is naming a compensation.
- **Properly typed code, with casts only at documented boundaries.** The sanctioned boundaries are: erased dispatch after a tag walk (the walk proves the tag, the type system cannot), the erased currency inside the eval loop, the effect slot pinned to `Nothing` where a pattern-bound effect type loses its GADT bound, and the lift-avoidance casts of the currency discipline. Each site carries a comment naming its boundary. A cast that fits none of these categories is a design smell to resolve, not a typing convenience.
- **No explicit type parameters unless inference genuinely fails, and then the site says so.**
- **No implicits for internal plumbing.** The evaluator's stack is threaded explicitly; it is never an implicit parameter. This was ruled before it could be built, and it stays ruled.

## Pre-submission checklist (kyo-kernel)

Beyond the root checklist:

- [ ] Every property the change relies on has an answer to "what keeps this true", and the preferred answer is the shape of the representation, not a code path. A mechanism added to protect a property is a flag, not a fix.
- [ ] Every new recursion names its stack-safe carrier (the arrow chain via `AndThen.step` plus the Safepoint budget, or a flat `@tailrec` loop). Nothing recurses on the Java stack proportional to program shape; the 1M-depth `EvalTest` scenarios pass.
- [ ] No new structure guards existing structure. If several failures shared one mechanism, the representation changed, not the patch count.
- [ ] No re-lift of settled currency. Every erased cast sits at one of the documented boundaries with a comment; any read at a pinned effect slot (`< Nothing` in particular) is cast rather than left to the implicit lift.
- [ ] Tests assert concrete values and observable effects through the public surface; internal construction appears only where the internal unit is under test. No probe ships; a probe that validated the change is deleted with its assertions folded into the matching `*Test.scala`.
- [ ] A change to `Eval.scala`, `KyoInternal.scala`, `Stack.scala`, or `Arrow.scala` carries a JMH A/B against a frozen baseline with `-prof gc`, run on an idle machine; the answering rows stay allocation-flat.
- [ ] No new terminology; no explicit type parameters without a forcing comment; no implicit parameters for internal plumbing.
