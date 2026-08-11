# Contributing to kyo-kernel2

Module-specific guide for kyo-kernel2, the effect-system kernel. Read the repository-root [CONTRIBUTING.md](../CONTRIBUTING.md) first: it carries the conventions that apply across all of Kyo (naming, the kyo type vocabulary, scaladoc, inline guidelines, test patterns, the unsafe boundary). This document records only what is specific to the kernel: the structural-property invariant that governs every design decision here, the evaluation model, the recursion-carrier rule, the currency discipline, the mechanism checklist, and the kernel's testing, performance, and naming doctrine.

None of these rules is a preference. Each one was paid for by a concrete defect in this kernel's own development, and each section cites the code or the commit where the bill arrived. The full record is [kernel2-iteration-report.md](../kernel2-iteration-report.md); see [The history behind this guide](#the-history-behind-this-guide).

## What kyo-kernel2 is

The layout mirrors the old kernel: public files at `kyo.kernel`, machinery in `kyo.kernel.internal`, and the `kyo`-level aliases and utilities in `shared/src/main/scala/kyo`.

Public, under `kyo/kernel`:

- `Pending.scala`: the currency. `opaque type <[+A, -S] >: Kyo[A, S] = A | Kyo[A, S]`, the implicit lift under the `CanLift` discipline, and the extensions: `map`, `flatMap`, `andThen`, `unit`, `eval`, `evalNow`, `flatten`, and the `handle` pipe overloads.
- `ArrowEffect.scala`: the effect protocol. `suspend` / `suspendWith` create operations; the four handling variants are `handle` (Cont), the two `handleLoop` overloads (Loop and LoopState), and `handlePartial`, the eager driver for scheduler integration.
- `ContextEffect.scala`: values awaiting provision, expressed over the same machinery: a `ContextEffect[A]` is an `ArrowEffect[Const[Unit], Const[A]]`, provision is an answering handler layer, and optional context is a `Defaulted` suspension.
- `Effect.scala`: the effect base class, `defer`, and `catching`, whose guard rewraps continuations so every resumed step stays covered.
- `Loop.scala`: stack-safe user-level iteration; its `Outcome` encoding is also what handler clauses return.

Internal, under `kyo/kernel/internal`:

- `Kyo.scala`: the three node shapes (`Suspend`, `Defer`, `Handled`), the `Defaulted` mixin, and the `Nested` box that keeps boxed data distinguishable from computations.
- `Arrow.scala`: typed continuations. `Arrow.AndThen.step` is the kernel's one stack-safe recursion carrier.
- `Handler.scala` / `Handlers.scala`: the three handler kinds as final data classes (`Cont`, `Loop`, `LoopState`, each a tag plus a polymorphic clause, with `LoopState` carrying its state) and the collection `Eval` scans.
- `Eval.scala`: the evaluator. One flat loop; `Eval.partial` is the scheduler entry that yields residuals instead of throwing or spinning.
- `Safepoint.scala`: the per-thread depth budget (`Period = 512`) that bounds fused execution, and the `Stop` wrapper in `owners` through which `Safepoint.stop(thread)` requests preemption, read only on the budget's slow path.
- `CanLift.scala`: the soft constraint that rejects lifting an already pending computation.

At the `kyo` level: `kernel.scala` (the `<` and `Loop` aliases, `Id`, `Const`) and `Kyo.scala` (the sequential collection operations over computations).

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

The current evaluator (commits `5b0a910b4c`, `b8ad9309e8`) replaces all four with one representation: the value travels with its layers, a layer being a handler and that scope's exit continuation, held at the same index in two parallel chunks. Entry appends; exit pops; `done` feeds the exit sitting at its own handler's index:

```scala
case kyo: Kyo.Handled[?, ?, ?, ?, ?, ?] @unchecked =>
    loop(
        kyo.value.asInstanceOf[A < S],
        hs.add(kyo.handler),
        exits.append(kyo.cont.asInstanceOf[Arrow[Any, Any, Any]])
    )
```

```scala
case done =>
    loop(
        walk(exits(idx), Nested.lift(done)).asInstanceOf[A < S],
        hs.take(idx),
        exits.take(idx)
    )
```

(`Eval.scala:48-53`, `Eval.scala:92-97`.) What each mechanism enforced, the shape now implies:

| property | step-1 Eval: enforcement site | merged layers: why it holds |
|---|---|---|
| a `done` reaches its own scope | `Kyo.Halt` plus an ownership check at every scope exit | the owning exit is at the handler's own index; no climber exists to misroute |
| a clause runs outside its own scope | `settle` sub-call under a prefix, merged back by arithmetic | the loop continues under `take(idx)`; there is no second evaluator to disagree with the first |
| state survives an inner scope's exit | `(A < S, Handlers)` returned out of every frame | layers travel with the value; there is no "back" for state to travel |
| stack safety of scope nesting | nothing; the Java stack | nothing recurses, so nothing can overflow |
| answering allocates nothing | branch duplication to dodge tuples | the loop has no per-bracket structure to allocate |

The mis-delivered `done` was not fixed. It became unrepresentable: there is no `Halt`, so there is no value the wrong match arm can capture. That is the difference between the two categories, and the rewrite proved it cheaply: all five red reproductions went green with zero test edits, and the JMH board came back byte-identical to the pre-mechanism baseline. When you find yourself defending a mechanism because it is well tested, notice that the test is exactly the proof obligation the structural version would not need.

## The evaluation model

After this section you should be able to predict what `Eval` does with any node.

**Currency.** `A < S` is `A | Kyo[A, S]` (`Pending.scala:8`): a settled value and a computation share one runtime channel, which is what makes the hot paths allocation-free. Three node shapes in `Kyo.scala`: `Suspend` (an operation carrying its tag, input, and continuation; `map` chains onto the continuation, delegating tag, input, and frame to the `root`), `Defer` (a budget rescue holding a value and the rest of the chain), and `Handled` (a computation under a handler, as a value: the computation with its effect still in the row, the typed handler, and the continuation outside the region, where `map` chains, `Kyo.scala:69-70`).

**The four handling variants.** `ArrowEffect.handle` (a `Handler.Cont`) and the two `ArrowEffect.handleLoop` overloads (`Handler.Loop` and `Handler.LoopState`) build `Kyo.Handled` region nodes and run nothing: handling is a value, and answering happens at `eval`, with a settled input passing through strictly with no node (pinned by `ArrowEffectTest` "lazy: the handled computation is a value and answers at eval" and "settled inputs pass through strictly"). The handler kinds are data: a tag plus a polymorphic clause, with `LoopState` carrying its state value, so `Eval` builds successors itself and the constructors are one line each. `handlePartial` is the fourth variant and the one eager driver: it answers matching operations while the clause returns a present continuation and parks at the first refusal, foreign suspension, pending `Safepoint.stop` request, budget exhaustion, or region node, returning the computation as it stands. It does not rotate; the wrap-not-append re-entry invariant survives in `Effect.catching`'s guard, whose append encoding once agreed with every existing test and still lost the handler behind one trailing transform (commit `727e8e6742`; the pins are the `ArrowEffectTest` "stays in force across a foreign crossing with a trailing transform" family).

**The loop.** `evalLoop` (`Eval.scala:45-172`) is one flat `@tailrec` loop over the value with two parallel chunks, `hs: Handlers` and `exits: Chunk[Arrow[Any, Any, Any]]`. A layer is the pair at one index. Behavior by behavior:

1. **Entering a region appends a layer.** No call, no frame (the entry arm quoted above).
2. **A settled value pops the innermost exit.** Exit order is entry order reversed, because the exits sit in entry order (`Eval.scala:167-170`):

```scala
case v =>
    val n = hs.size
    if n == 0 then v
    else loop(walk(exits(n - 1), v.asInstanceOf[Any < Any]).asInstanceOf[A < S], hs.take(n - 1), exits.take(n - 1))
```

3. **An operation resolves to the innermost matching handler.** `Handlers.indexOf` scans from the innermost end with a `<:<` tag test, so innermost-wins is scan order and a subtype tag resolves a supertype handler. On a miss, a `Defaulted` suspension (resolved through its `root`, so the property survives maps) resumes with its fallback; otherwise `eval` throws `IllegalStateException("unhandled suspension: ...")` and `Eval.partial` returns the standing computation reified with its remaining layers, resumable by evaluating it again. `Eval.partial` also yields that residual when a `Safepoint.stop` request is pending on the thread's slot, which is the whole preemption mechanism: dispatch is only through the thread's slot, detection is a volatile read at entry and on the budget's slow path, and the residual is ordinary data. `Eval.partial` stays alongside `handlePartial` because the eager driver parks at region nodes by design, so evaluating regions without throwing on a miss needs the evaluator entry.
4. **A settled `Loop.continue(answer)` feeds the suspension's own continuation and touches nothing else.** This is the hot path; it allocates nothing beyond the clause's outcome box (`Eval.scala:85-91`):

```scala
case answer =>
    val step = kyo.cont.step
    loop(
        step.head(answer.asInstanceOf[o[x] < Any], step.tail).asInstanceOf[A < S],
        hs,
        exits
    )
```

5. **`done` feeds its own layer's exit and truncates** (the arm quoted above). The discarded layers' exits never run, which is exactly the semantics that `done` skips the inner scopes' remainders.
6. **A clause that suspends before deciding is chained, not evaluated in a sub-call.** The pending computation becomes the current value with the decision chained after it, running under `take(idx)`: the layers outside the clause's own scope, because a clause runs outside its own region by construction. The layers the operation crossed are rebuilt around the resumption from a snapshot (`Eval.scala:62-72`):

```scala
case pending: Kyo[?, ?] =>
    val hsAll = hs
    val exAll = exits
    val kCont = kyo.cont.asInstanceOf[Arrow[Any, Any, Any]]
    val chained = pending.asInstanceOf[Kyo[Any, Any]].map(transform {
        case c: Loop.Continue[?] =>
            rebuildFrom(idx, walk(kCont, c._1.asInstanceOf[Any < Any]), hsAll, exAll)
        case done =>
            walk(exAll(idx), Nested.lift(done))
    })
    loop(chained.asInstanceOf[A < S], hs.take(idx), exits.take(idx))
```

7. **A continue whose answer is itself pending runs under `take(idx + 1)`**: inside the handler's own layer, so a re-raise of the scope's effect is answered by the same handler (`Eval.scala:76-84`). Note the index asymmetry with 6: a pending clause *outcome* runs outside its own layer, a pending *answer* runs inside it. This mirrors the old kernel's `handleLoop` semantics and is what lets a handler express "raise the scope's effect again".
8. **State is an index update.** The clause continues with the next state value; `Eval` builds the successor handler around it and replaces the layer in place, with a reference check as a pure optimization (a false negative only rebuilds an identical successor):

```scala
val hs2 =
    if c._1.asInstanceOf[AnyRef] eq h.state.asInstanceOf[AnyRef] then hs
    else hs.updated(idx, new Handler.LoopState[i, o, Nothing, Any, Any, Any](h.tag, c._1, h.clause))
```

9. **A `Cont` clause receives the continuation as a function that rebuilds the crossed layers per call.** Every call builds a fresh value, so capture is multi-shot by construction; the clause body runs under `take(idx + 1)`, so its re-raises are answered by this handler and its exit applies when the body settles (`Eval.scala:149-161`).
10. **A `Defer` resets the budget and steps** (`Eval.scala:163-166`).

Two helpers, both cold. `rebuildFrom` (`Eval.scala:214-227`) restores crossed layers as plain `Kyo.Handled` nodes around a resumption; it is bounded by the number of layers the operation crossed, and what it produces the next loop iterations simply re-append. Data in, data out. `transform` (`Eval.scala:181-209`) is one arrow step over erased currency in the `suspendWith` shape, so a pending input re-suspends and the budget defers deep chains.

## Every recursion names its stack-safe carrier

The kernel has exactly one: arrow chains. `Arrow.AndThen.step` flattens a chain iteratively through a thread-local scratch buffer and relinks (`Arrow.scala:76-102`), and the Safepoint budget converts construction past `Period` into `Kyo.Defer` rescues that `Eval` steps flat. Every recursion in the kernel either rides that carrier or is a flat `@tailrec` loop.

The rule for any change: for every recursion you introduce, name the carrier that makes it stack-safe, at design time, in one sentence. If the honest answer is "the Java stack", the recursion is a defect whenever its depth is proportional to program shape, and this is the moment to say so, not after a 1M-depth test overflows. The step-1 evaluator never asked the question: scope entry recursed per `Handled` node and `settle` recursed per re-raised answer, producing two of its three defect classes. The reproductions are permanent guards in `EvalTest`: "enters deeply nested scopes in bounded stack", "opens a scope per recursion step in bounded stack", "settles chained re-raised answers in bounded stack", each at depth 1000000.

A budget is not a carrier. The redesign between step 1 and merged layers kept entry recursion and guarded it with a budget cap that unwound into a `Defer` re-nesting the enclosing regions; the unwind re-paid the whole descent on every cycle, making region entry quadratic in nesting depth, observed as a hang at 1M (recorded in the commit message of `344770888f`). When you find yourself sizing a cap, the question is not "how big" but "what representation has nothing to cap".

## Currency discipline

The implicit lift passes primitives and `String` raw and routes everything else through `Nested.lift`, which boxes only `Boxed` values: `Kyo` nodes and already-nested values. Ordinary user values pass raw. The lift requires `CanLift` evidence, which `NotGiven[A <:< (Any < Nothing)]` derives for everything except statically pending types: ascribing a computation into a nested `< S` position is a compile error with a teaching message, so the footgun in rule 2 cannot arrive through inference anymore. Deliberately holding a computation as data goes through a generic indirection (`def box[A](v: A): A < Any = v`), where the runtime lift boxes it. A macro-based lift with per-type static mode selection was tried and reverted: same-module macro expansion trips the compiler's compilation-suspension bug, and the constraint that matters needs no macro. This gives four rules:

1. **`lift` is for a plain value entering the kernel, exactly once.** A settled result of an evaluation pass is already currency; passing it through `lift` again double-nests it as data.
2. **Know the footgun.** `<` is contravariant in `S`, so a value read at `Any < Nothing` does not conform to an expected `Any < Any`. At any site where a `Kyo` fails to conform to the expected `<` type, the compiler silently applies the implicit conversion and turns the whole computation into data as `Nested(...)`. Nothing fails at that point; the failure surfaces arbitrarily far away as `ClassCastException: kyo.kernel.Nested cannot be cast to ...`. This bit three times during the kernel's development. The canonical bad site was an erased alias pinning the effect slot to `Nothing`, making every field read a conversion site; the debugging session was long precisely because the construction sites had been pinned with explicit type parameters while the real culprits were the erased reads.
3. **The defense is a cast at the boundary, with a comment saying why.** The pattern in the source (`Eval.scala:211-214`):

```scala
// the crossed layers are restored as plain region nodes around the
// resumed computation; the casts keep the erased construction out of the
// implicit lift, which would nest the computation as data
private def rebuildFrom(from: Int, value: Any < Any, hs: Handlers, exits: Exits): Any < Any =
```

4. **When a constructor's result must unify with an expected `< S`, return the lifted type.** The `Loop.Outcome` constructors are the positive example: this kernel's `<` lower-bounds only `Kyo`, so a bare `Outcome` would not unify with an expected `Outcome[...] < S`; the constructors lift, which is free for `Continue` values and produces correct currency for boxed done payloads (`Loop.scala:126-130`).

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
- **Tests reach features through the user-facing surface**: `ArrowEffect.suspend` / `suspendWith` / `handle` / `handleLoop` / `handlePartial`, `ContextEffect`, `Effect.defer` / `catching`, the `<` extensions including `evalNow`, and `Loop.continue` / `Loop.done`. Direct node construction, `Handlers`, `Nested`, and `Safepoint` internals appear only in the `kyo.kernel.internal` test package, where the internal unit is itself under test: `HandlersTest` over the collection, `HandlerTest` over the clause shapes, `KyoTest` over the node shapes, `EvalTest` over evaluation semantics and `Eval.partial`, and `SafepointTest` over the budget and the stop signal. The migration that produced this state is recorded per test in [kernel2-test-api-audit.md](../kernel2-test-api-audit.md).
- **Probes are disposable dev artifacts.** An executable spec under `jvm/src/test`, named after the source it probes, with a header stating the model it encodes; no test runner picks it up. Probes made it possible to build competing evaluator models and measure both without one speculative edit to main source, and they are a liability if left behind: the repo rule is that scratch files are deleted before the change ships, their validated assertions folded into the matching `*Test.scala`. `EvalProbe.scala` and `HandlersProbe.scala` served that role for the evaluator models and are deleted; the multi-shot capture coverage they carried lives in the public `handle` tests.

## Performance

Performance in this kernel is structural, the same way correctness is. Allocation and time accumulate at coordination points, and coordination points are exactly what mechanisms create. The pair-returning `evalLoop` signature cost a measured +24 B on every benchmark row that entered `Eval.apply`; no local optimization could remove it while the mechanism stood (a first round of tuple-elimination fixes removed only the avoidable ones), and it disappeared the moment the mechanism did, returning every row byte-identical to the pre-mechanism baseline.

The budget: answering (the settled-continue arm) allocates nothing beyond the clause's outcome box. Cold paths may allocate: pending clause chains, `LoopState` successors, `rebuildFrom` nodes, `Defer` rescues.

The gate: any change to `Eval.scala`, the node shapes in `Kyo.scala`, or `Arrow.scala` is validated by a JMH A/B (`jvm/src/jmh/scala/kyo/kernel/bench/KernelBench.scala`) against a frozen snapshot. The snapshot discipline: build the baseline classpath before the change and freeze it; run both sides from frozen classpaths, three forks, with `-prof gc`; never run benchmarks while anything else loads the machine. Read allocation first: time is noisy, bytes per op are not, and an allocation delta localizes to the exact rows that exercise the new structure (the +24 B appeared only on rows entering `Eval.apply`, which is what identified its source).

## Terminology, naming, and typing

- **The evaluator is `eval`. Names in the kernel are maintainer-approved; introduce no new terminology in code, comments, or docs without a ruling.** This is not stylistic. Machine vocabulary arrives with machine designs, and it arrives first: `drive`, `exitCondition`, `downgradeStops`, `shadow`, and `Entry` were each rejected on sight or renamed away, and commit `6160c23c3b` exists solely to rename `drive` to `eval`. If a new noun seems necessary to describe what the evaluator is doing, first check whether the noun is naming a compensation.
- **Properly typed code, with casts only at documented boundaries.** The sanctioned boundaries are: tag-keyed handler recovery after `Handlers.indexOf` (the scan proves the tag, the type system cannot), the erased currency inside `evalLoop`, the effect slot pinned to `Nothing` where a pattern-bound effect type loses its GADT bound, and the lift-avoidance casts of the currency discipline. Each site carries a comment naming its boundary. A cast that fits none of these categories is a design smell to resolve, not a typing convenience.
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
