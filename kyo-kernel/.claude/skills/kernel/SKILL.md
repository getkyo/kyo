---
name: kernel
description: Design ethos and type-safety discipline for working on the kernel. Load before changing kernel sources, reviewing kernel code, or designing kernel features. Composition first, the evaluator second; casts and other concessions only when measured, justified, and protected.
---

# Working on the kernel

This skill captures how kernel work is done, distilled from the sessions that built the Arrow-based kernel. It is
the standard the code is held to, not a suggestion. Scope for now: how work reaches the tree, design ethos, type
safety, how concessions are made, and how performance claims are established. Other areas (porting, docs) come
later.

## How work reaches the tree: the live review

Kernel sources are the user's code. The agent does not edit them as it goes, and a compaction does not reset
that: an agent that finds itself mid-edit in the kernel tree without a review under way has already broken this
rule and stops.

Work happens in an **isolated worktree**, where the agent is free and uses whatever tooling is fastest. Nothing
crosses into the user's tree except through a **live review**: the agent proposes it, the user opens it, and only
then are edits applied to kernel sources, one at a time, **with the Edit tool**, with the user watching and
ruling as they go. Scripted edits (`sed`, heredocs, rewrites through bash) are for the isolated worktree only.

The measure of the isolated work is not that it compiles or that the suite is green. It is **whether the review
will pass**, run by a picky reviewer who reads every line. That reframes what "done" means before the review is
even proposed:

- **Arrive with the design settled, not with options.** A review is not where a fork gets discovered. Forks are
  raised as questions before the review opens, and the review carries the ruling already applied.
- **Every line has to be defensible on its own.** A line the agent cannot justify unprompted is a line the
  reviewer will stop on, and stopping on it costs more than deleting it did.
- **Bring the evidence with the diff**: the clean batch build, the proto suites, the benchmark rows the change
  reaches, and the pinning tests for whatever the change could break. A review that turns into a request to go
  measure something has already failed.
- **The reviewer's time is the scarce resource.** Anything the agent could have decided, verified, or removed
  beforehand and did not is a defect in the preparation, whatever the code does.

### Naming and shape discipline

These are review-blocking on sight, because they are cheap to get right and expensive to read past:

- **No new terminology.** The machine's vocabulary is fixed and every new synonym costs the reader a
  translation. "drive" is banned; the evaluator **evals**. There is no "after" arrow; a continuation is a
  **cont**. A name that already exists in the kernel is the correct name, and consistency across a change is
  not negotiable.
- **Avoid new types.** A new type must earn itself against the ones already here. Reach for an existing shape
  before inventing a carrier, and when a new one is unavoidable, it is named for what the kernel already calls
  that concept.
- **Correct by construction beats correct by inspection.** Prefer a shape where the invariant cannot be
  violated over one where it holds because every call site remembered to. Structural properties are not
  asserted in comments; they are made true by the structure, so that a reader can see them without simulating
  the code.
- **Multiple edge cases mean the approach is wrong.** Accumulating special arms, flags, or "unless it is also"
  clauses is the signal to stop and re-read the problem, not to keep adding arms. The correct shape usually
  makes most of the cases stop existing.

## Preparing a live review

The pipeline exists to absorb thrash. Everything an agent gets wrong on the way to a change should be caught
inside the isolated worktree, so the reviewer sees **one artifact** and spends judgment on the design rather
than on catching drift. A phase that does not reduce what the reviewer has to look at is ceremony; delete it.

```
DERIVE    equation -> surface -> forks                [STOP only on a surviving fork]
BUILD     isolated worktree; flags.sh as you go; kernel-pulse at the first compile
EVIDENCE  clean batch build, proto suites, pinning tests, benchmark rows
          [gate: each benchmark class must reference the package under review]
REVIEW    flags.sh -> flags.md, every row adjudicated [gate: zero unadjudicated rows]
          package-check.sh -> every mechanical claim re-derived [gate: no STALE]
REHEARSE  kernel-conformance | kernel-discipline | kernel-rehearsal, in parallel
PACKAGE   reviews/<change>/review.md                  [STOP: propose the live review]
LIVE      the user opens it; edits applied one at a time with the Edit tool
ESCAPE    anything the user catches that the pipeline missed -> rulings.md, verbatim
```

Two user touchpoints, not seven: a fork that survives, and the review itself.

### The coordinator

You hold the full context and you are the only actor that talks to the user. Unlike a pure supervisor you also
do the work: the derivation, the build, the adjudication, and the live review itself. You dispatch the lenses,
you decide what the findings mean, and you own the result whatever they returned.

**Three things are never delegated**, and the reasons differ:

- **The derivation**, because it is what the user collaborates on. Delegating it would delegate the
  collaboration rather than protect it.
- **The adjudication**, because the entire mechanism of the REVIEW phase is that *the author* must write the
  justification. A line whose author cannot defend it is usually one the author already knew was weak, and an
  empty verdict cell is what makes that visible. Hand the table to an agent and the mechanism is gone while the
  artifact still looks the same.
- **Forks and measurements**: a fork is the user's to rule, and a measurement is mechanical, so run it.

**Findings are mandatory, and disagreement escalates rather than overrides.** A finding you believe is wrong
goes to the user with the evidence, never quietly dropped and never argued away in the package. Two moves are
banned outright: **re-dispatching a lens hoping for a different verdict**, and **weakening a brief because the
lens is inconvenient**. Both convert the pipeline into a machine for producing PASS. A PASS is also not a
decision: the lenses inform your judgment, they do not replace it, and shipping something you would not defend
because three reports were green is the same failure in a new costume.

**A user interruption is a finding from the highest-authority reviewer.** Stop immediately. Then locate the
cause before changing anything, because the strong pull is to make a change in the direction of the
complaint's surface, and that is how a small correct objection produces a large wrong change: told the code
was unsafe, the right move is to ask where the unsafety is, not to reach for a safer-looking shape. Record the
objection verbatim in `rulings.md` before continuing, while the words are still exact.

**Stop conditions, which autonomy makes more binding rather than less.** Working unattended means nobody else
is watching the drift, so each of these ends the current line of work:

- you are about to write a line you could not defend in one sentence if asked;
- the design has started needing a special case, a flag, or an "unless it is also" clause;
- a type error is being routed around rather than read as information about the design;
- you are working outside the derivation's surface.

The first is the one that fires most and gets overridden most. Treat the feeling of "I will explain this later"
as the signal itself.

**The artifacts are the memory, not the conversation.** After a compaction, re-read `derivation.md` and
`rulings.md` rather than trusting recall: a summary of a summary is how a settled question gets relitigated and
how a ruling quietly stops binding.

**At the two stops.** A surviving fork is presented alone, with the evidence and a recommendation, never as a
menu. The review is *proposed*, not started: the user opens it. During the review, edits are applied one at a
time **with the Edit tool**, each preceded by the one sentence that justifies it, never batched and never
applied ahead of a ruling. Any objection stops the sequence and goes into `rulings.md` verbatim before work
resumes.

### DERIVE, which is not delegated

Write `reviews/<change>/derivation.md` yourself. It carries the equation in the existing combinators, the
mapping of each piece to a value the kernel already has, the **surface** (the exact files and methods that
change, and what must not), and the forks. This is the phase the user collaborates on, so delegating it would
delegate the collaboration rather than protect it.

A piece with no counterpart is a **fork for the user**, never an invention. That single rule is what stops a
new carrier type from appearing because the types would not line up otherwise. Present a surviving fork alone,
never as a menu; when none survives, the phase passes without a stop.

### REVIEW, the phase that does the most work

Judgment misses things; enumeration cannot. `flags.sh` lists every construct of concern on the diff's added
lines, and each one gets a row and a written verdict in `reviews/<change>/flags.md`:

```
| id | site           | added line                    | class   | verdict |
|----|----------------|-------------------------------|---------|---------|
| F1 | Eval.scala:113 | stack.handler.asInstanceOf[…] | cast    | justified: erasure-forced, array element re-typing at the storage boundary |
| F2 | Eval.scala:36  | ): Any < Nothing =            | carrier | REMOVE: the signature must state the eval's answer, A < S |
```

A verdict is a **category from the cast ladder's closed set, a measurement, a `moved` provenance naming where
the code came from, or `REMOVE`**. Nothing else counts, and these in particular do not: "needed for the types
to work", "the evaluator is the engine room", "consistent with the existing code". Each is a conclusion that
requires a category or an experiment behind it.

The mechanism is not the table, it is **being made to write the verdict**. A line whose author cannot defend it
is usually a line the author already knew was weak, and an empty verdict cell next to it makes that visible
while it is still cheap to delete. Gate: zero unadjudicated rows.

Classes the script cannot emit, because they need reading rather than a pattern: work outside the declared
**surface**, a **claim** with no number behind it, and a **tail call** asserted in a comment that is not one
(a call under a cast, inside a `try`, or crossing into another method). Those are `kernel-discipline`'s to apply.

`package-check.sh` runs beside it and re-derives every claim that is mechanical: the tip, the commit count, the
surface the range touches, whether the tree is clean, the flag count against the table's, whether the recorded
edit sequence still reproduces the tip, and **whether each benchmark class the package names actually
references the package under review**. Each line is OK, CHECK or STALE, and a STALE line is a defect.

The last of those is there because of the worst escape this pipeline has had. A campaign ran four rounds of
review over benchmark tables measured by a class named `ProtoKernelBench`, sitting beside a proto kernel,
which contained no reference to `proto` and measured a different package entirely; the name was left over from
a rename. Four rounds of lenses read those tables and argued about drift bands inside them. **Before a number
is evidence, check that the thing measured is the thing changed**, and check it with a script, because a
plausible name answers the question by looking right.

### The lenses, and what they are denied

Dispatch the three in one message; they are independent.

| lens | judges | reads |
|---|---|---|
| `kernel-conformance` | did the code become the derived design, and is everything inside the surface | derivation + diff |
| `kernel-discipline` | is every flag in the table, and is every verdict a category or a number | flags.md + diff + this skill |
| `kernel-rehearsal` | would the reviewer stop on any line | package + diff + this skill + rulings.md |

Held-out means **denied**: the session transcript, the agent's own reasoning, the worktree's failed attempts,
and any summary asserting that something is fine. `kernel-rehearsal` reads exactly what the user will read and
nothing more, because the author found every one of these lines acceptable at the time and re-supplying that
reasoning re-supplies the blind spot. Findings are mandatory fixes with stable ids; a fix is a new diff, so the
lenses whose input changed are re-dispatched.

### Dispatch and model selection

Dispatch as `general-purpose` with the sub-skill in the prompt, the way the readme pipeline does:

```
Agent({ subagent_type: "general-purpose", model: "<below>",
        description: "...", prompt: "/kernel-rehearsal <change-dir> <diff-range>" })
```

**Never dispatch a lens as `fork`.** A fork inherits the full parent context, which is precisely the exclusion
set, and it silently destroys the one property that makes the lens worth running. The failure is invisible: the
report still arrives, still looks like a review, and is worthless. A fork also ignores the model override.

| lens | model | why |
|---|---|---|
| `kernel-rehearsal` | fable | it is the gate, and predicting a picky reviewer is the hardest judgment here: no rubric names what it looks for, and a miss becomes a finding raised live at the user's expense |
| `kernel-conformance` | opus | detecting substitution is semantic judgment about whether two shapes are the same design, which pattern matching does not reach |
| `kernel-discipline` | opus | half of it is mechanical set comparison, but the half that matters is refusing a rationalisation, and that is exactly where the author's own judgment already failed |
| `kernel-pulse` | sonnet | a narrow catalog on an unfinished tree, dispatched often; it has to stay cheap and quiet or it stops being read at the moment it is useful |

Model choice tracks judgment difficulty, never context. A stronger model does not license a weaker brief: the
exclusion set is the mechanism, and the model only decides how well the lens uses what it was given.

**Correlated blind spots** are the one risk this table does not solve. A `fable` rehearsal reading a change
written by a `fable` author shares its author's instincts even with the transcript withheld, so the failures it
is least likely to catch are exactly the author's characteristic ones. Three things mitigate it, in order of
how much they carry: `rulings.md` supplies a rubric the author did not write, `kernel-discipline` enumerates
mechanically rather than judging, and the tiers below put a second lens on anything touching the evaluator. On
a tier-three change where the rehearsal returns PASS on the first round, consider a second rehearsal at a
different model before believing it; a first-round PASS on a large change is more often a lens that did not
engage than a change that is clean.

Re-dispatch after fixes runs only the lenses whose input changed. A fix to a flag verdict re-runs
`kernel-discipline`; a fix to the code re-runs all of them, because the diff is every lens's input.

### rulings.md, and why this does not become theater

`rulings.md` beside this file records the user's objections verbatim and dated, and it is `kernel-rehearsal`'s
rubric. A **rehearsal that passes and is then contradicted by the live review is a defect in the rehearsal**,
recorded as an escape entry naming which rung should have caught it. The two repairs are different and
conflating them is how a pipeline accretes stages instead of getting sharper:

- the construct was never flagged: a hole in `flags.sh`, fix the script;
- it was flagged and rationalised: a hole in the gate, tighten what counts as a verdict;
- it was neither, and only a reader would have seen it: a rulings entry, so the rehearsal has it next time.

**No stage is added to this pipeline without an escape entry justifying it.**

### Scaling, so a two-line fix does not get a fleet

| the change touches | run |
|---|---|
| the inside of one method, no signature change | derivation as a paragraph in the package; `kernel-discipline` |
| a method's shape, or a new private helper | add `kernel-conformance` |
| the evaluator, the representation, a node class, the public surface, or any signature | all three lenses, `kernel-pulse`, and benchmarks are mandatory |

### PACKAGE

`reviews/<change>/review.md` is the one file the user opens. It carries the derivation folded in, the diff
walked **in the order the edits will be applied**, each with the one sentence to say when applying it, the
adjudication table, the evidence in the form the benchmark section dictates, and the open rulings. A live
review is a sequence, so preparing one means having the sequence, not a pile of changes and an explanation.

## Composition first, the evaluator second

A kernel computation is a value built by composition. The evaluator is an accelerator for those values, not the
definition of what they mean. Every piece of evaluator behavior must be the operational reading of an equation you
can write in the public combinators; if you cannot write the equation, you do not understand the case yet, and no
amount of machine choreography will substitute.

Working rules that follow from this:

- **When the evaluator has a gap, write the equation first.** In terms of `map`, `Handle`, the segment values, the
  existing adapters. Then realize each piece with values the evaluator already has. A piece with no counterpart is
  a missing value, never a missing instruction.
- **Wanting a new node kind is the signal you are off the path.** The node kinds are the reifications of the
  combinators; a new one implies a new combinator, which is a surface decision, not an evaluator patch. The
  effectful-clause fix (see `proto-effectful-clauses.md` at the repo root) is the worked example: two designs that
  added evaluator machinery failed review; the landed fix is one `map` over the clause's outcome, rebuilding the
  region as a fresh `Handle` value, with `done` handled by the *absence* of anything to do.
- **When a wall appears, dissolve it before scaffolding around it.** First ask whether a change already
  agreed on removes the wall, and whether a sibling in the repo already demonstrates the answer (the old
  kernel, the kernel2 impl); both were true for the clean-build crash that the outcome dispatcher dissolved.
  Parallel machinery grown around a problem (package copies, extra modules or scopes, compiler bumps) is the
  same off-path signal as wanting a new node kind, and infrastructure stacking on infrastructure is the
  signal to stop and re-read the problem.
- **Signatures are semantics; read the rows as region geography.** Where a computation's row places it is where it
  runs. `handleCont`'s clause returns `A < (E & S)`: it is region currency, self-re-entrant. `handleLoop`'s clause
  returns `Outcome[O[C] < (E & S), B] < S`: the clause lives *outside* the region it serves; only the answer
  payload is region currency, and `B` at row `S` is why `Loop.done` bypasses `complete`. Intersection rows are
  idempotent, so `E & (E & S)` collapses; the *position* in the signature is what distinguishes "the `E` handled
  here" from "an `E` for the region outside". Types like these decide evaluation strategy: outer-row code must run
  with the region absent (as values in hand), because dynamic tag scoping cannot skip a region that is present.
- **Fast paths specialize laws; they never replace them.** Every fast path must be observationally equivalent to
  the general equation it accelerates (the settled-outcome handler arms are `map` on an already-settled argument).
  If a fast path and the law can disagree, the fast path is wrong, whatever the benchmarks say.
- **When stuck, read the CPS sibling in kyo-kernel.** Continuation-passing style makes the composition explicit;
  the old kernel's `handleLoop` handles a suspended clause with `v.map(handleLoopLoop(_, context))`, one line,
  because its loop state is the same `Outcome` currency the clause speaks and entry equals resumption
  (`Loop.continue(v)`). If the proto needs many lines where the kernel needs one, the delta is a representation
  cost to pay knowingly, not extra semantics to invent.

## Type safety: the representation and its contract

The pending type is a union: a raw value, an `Arrow`, or a `Nested` payload. The whole kernel rests on one
representation contract:

- **Nest exactly once** at the public lift boundary (the `CanLift` emission), and only for `Boxed` values.
- **Carry opaquely**: the evaluator moves union values without inspecting payloads.
- **Unnest exactly once** at delivery (map's strict arm, handler completes, the eval wrapper).
- **Suspension continuations receive raw payloads**, never union representations. Feeding a union value into a
  suspension double-wraps; three delivery sites were fixed for exactly this, each with a failing reproduction
  first.
- **Everything handed out is a complete value**, valid in any context, any number of times. This is what makes
  the mutable stack safe: dumps reify fully, segments are immutable, replays are independent.

Conversions are not free safety. The implicit lift *re-wraps* `Boxed` values, so applying it to an
already-union-represented value corrupts it: know whether a position holds a raw or a union value before letting
a conversion fire. Removing a cast can compile and be wrong — one such removal passed the compiler and failed
nine nesting tests.

### The cast discipline

Preference ladder, in order, with the step down taken only when the step above is impossible:

1. **Variance and ascription.** The row is contravariant; `Int < Any` conforms to `Int < Ask` by ascription.
   Two casts in this codebase's history were exactly this, found by review.
2. **Typed patterns.** Bind at the needed type with `@unchecked` rather than rebinding and casting; the runtime
   test is identical and the claim is visible.
3. **The conversion**, only where semantically correct (raw value, lift-once position).
4. **A cast, justified and categorized.** Every surviving cast belongs to a closed set:
   - *Erasure-forced*: `O[A]` erased, `unnest`'s `Any => A`, array element re-typing at the storage boundary
     (`Stack`), `Tag` storage outside its opaque scope.
   - *Reference-identity knowledge*: `f eq Identity` implies `C = B`; the type system cannot carry it.
   - *Representation assertion*: "this value is already union-represented; do not lift again" at settled
     re-delivery sites. These casts actively *block* the conversion from corrupting; they are load-bearing.
   - *Evidence-backed*: a compiler-checked equality in hand (`using S =:= Any`) applied where the inliner blocks
     the typed spelling.
   - *Macro-emitted under analysis*: `CanLift`'s bare-cast arm, taken only for types that provably admit no
     `Boxed` subtype.

Rules around the ladder:

- **No look-safe indirection.** A helper that wraps a cast, or a "checked" widening whose check is vacuous
  (at `A = Any` everything inhabits the union's first arm), is worse than the cast: it hides an assertion the
  reader needs to see. Casts announce themselves; that is part of their value.
- **Necessity is verified, not argued.** A cast stays only if removing it makes the compiler error, or makes a
  test fail. Both verifications have caught confident reasoning being wrong, in both directions.
- **Opaque transparency is narrower than intuition says.** The alias is transparent in its companion
  (`object <`) and NOT in sibling objects (`object Nested`). In a non-transparent scope, a "typed" spelling can
  silently compile through the conversion instead — the cast-free `nest` would have been an infinite recursion,
  exposed only because its other arm failed to compile. Inside such scopes, the casts are the safe spelling.
- **New casts require sign-off.** Never introduce one without surfacing it; never remove an existing one without
  the compiler/test verification above.

### The single lift and the suspension equilibrium

There is one lift: the macro-backed implicit, the same for user code and kernel files. No internal lift
variants, and no explicit nest spellings standing in for the lift; the simplicity of the lift surface is a
design requirement, not a style preference.

The compiler consequence: the CanLift evidence is a splice macro, and a file that summons a same-module
macro is suspended to a retry run. The module compiles in a fragile equilibrium: leaf-ish files may suspend
(the old kernel's Kyo.scala and Isolate.scala; the proto's Arrow.scala and Eval.scala carry one summon
each), but new summons inside a core, inlined-from file deepen the cascade until dotty crashes with a
StaleSymbolException, and only on the clean batch build: incremental compiles mask it. The map-based clause
dispatch broke exactly this way, and its silent lift in the done arm was simultaneously the representation
defect and the compiler crash.

Rules that follow:

- **The evaluator does not lift.** Its positions are typed union-currency positions where no conversion can
  fire; outcome dispatch is a named Transform (`resume` and `outcome` are the patterns), never `map` over an
  outcome value. `map` and the implicit lift are user-boundary machinery.
- **Verify the clean batch build** (`sbt --batch 'kyo-kernelJVM/clean' 'kyo-kernelJVM/compile'`) after any
  change that could summon the lift in kernel files; incremental green is not clean green.
- **Diagnose with `-Xprint-suspension`**: it names each suspended file and the macro that suspended it.
- **A compiler bump is not a fix**: the crash reproduces unchanged from 3.8.4 through 3.9.0-RC5.

## Concessions: measured, justified, protected

The kernel makes concessions — mutability, casts, statics, code duplication — but a concession is only
acceptable in a fixed shape: **justification (a measurement or a structural proof) + minimal scope + a protective
measure + a pinning test.** A concession missing any of the four is a defect. Standing examples of the shape:

| concession | justification | protection |
|---|---|---|
| `Loop.continue` constructors carry casts | design comment records the three bare-return designs that failed and why | `Continue` extends nothing but `Serializable`, so the lift's boxing arm is provably unreachable |
| raw values and arrows share the union | zero-allocation settled path | the `Boxed` marker closes the channel: a computation used as data is always `Nested`-wrapped, so the evaluator cannot mistake a payload for a suspension |
| the implicit lift exists at all | ergonomics of the pending type | the `CanLift` lint rejects already-pending types at concrete sites; `abortCastUnit` turns the Unit-row trap into a guided error |
| interpreter mutability (loop vars, thread-local stack) | it is the accelerator's engine room | never escapes: locals only, absence is `Maybe` never `null`, and the complete-value rule above governs everything that leaves |
| `*With` overloads copy their bodies | inline nesting measurably inflates compile time | each variant has its own tests; the copies are kept textually parallel |
| `@static` on boundary primitives | expansion sites must not capture the module (measured bytecode and compile-time cost) | known caveat: a cross-file non-inline reference to a static can fail the clean batch build; verified by clean builds, not incremental ones |

Two meta-rules bind the concessions together:

- **Reproduce before you fix, and pin what the concession could break.** Every concession that touches
  representation or replay has tests on the hostile axes: multi-shot application, capture and replay in a foreign
  eval, double nesting, budget parks mid-path. A fix without a reproduction that failed for the right reason
  is not done.
- **Nothing is rolled back or accepted on feel.** Optimizations are not reverted, and regressions are not
  accepted, without measurement and explicit sign-off; probes are run one variable at a time and reported with
  their numbers. The procedure that makes this checkable is below.

## Evaluating performance

A performance result is a number **and** a named mechanism. A number alone cannot be acted on, and a mechanism
alone is a guess. The rules here exist because skipping them produced days of motion: a redesign was proposed,
measured, and defended before anyone knew which of its two changes the numbers belonged to.

### A regression is never assumed to be acceptable

**Nobody has agreed to a regression until they say so about that specific number.** A slower variant is an open
defect, not a trade the author gets to make on the reader's behalf, and the bar is parity with the best number
ever measured on that row, including numbers produced by a design that was later rejected for other reasons.
Deleting the old design does not retire its measurements; they remain the target.

What this forbids, concretely:

- **Reporting a regression as settled.** Restating "+14% on this row" across several summaries while continuing
  to build on the variant is not disclosure, it is normalization. If a row is red, the state of the work is
  *unfinished*, and it is described that way every time it comes up.
- **Substituting a rationale for a fix.** "Justified by the composition shape", "the price of stack safety",
  "inherent to the representation" are conclusions that require an isolation experiment behind them. Until that
  experiment exists, a rationale is a hypothesis, and shipping on a hypothesis is the failure this rule names.
- **Stopping at the first mechanism found.** Finding *a* cause and closing part of the gap is progress, not
  completion. The remaining delta gets its own diagnosis, from its own profile of the actual variant in hand,
  never from a profile of a sibling variant or from reasoning about the code.

**The claim covers every row, not the rows you chose.** A subset run cannot support "no regression". The rows an
author picks are the ones the author is already thinking about, and the surprise lives in the others: kernel
changes land on shared machinery (node layout, delivery, currency handling, the eval loop, the safepoint poll),
so an edit aimed at trailing maps also runs under stateful handlers, region rebuilds, nested payloads, the idle
handler, and every fusion row.

- **The unit of measurement for a claim is the whole benchmark class on both variants**, same session, back to
  back. A hand-picked subset is for iteration while hunting a mechanism; it never becomes the evidence.
- **Name the rows the change reaches before running.** Touching the `HandleLoopState` arms makes the stateful row
  mandatory; touching region rebuild makes the emitting row mandatory; touching the loop head makes all of them
  mandatory. A row that exercises changed code and was not measured is an unverified claim, never a safe omission.
- **Screen wide, then confirm narrow.** Full class at `-f 1` on both variants to find suspects, then `-f 3` on any
  row outside the drift band. One long run removes the whole class of hidden regressions; skipping it means the
  next person finds them.
- **An empty or short result set is a failed run, not a clean one.** Count the rows returned against the rows the
  class defines, and rerun when they disagree (the first invocation after a recompile can silently match nothing).

**Deviations are allowed, and they are written down.** Sometimes the correct design does cost measurable time.
That outcome is reached, never assumed, and it is presented as a self-contained case the reader can decide on
without reconstructing the session: which rows regressed and by how much, the mechanism named and evidenced,
every attempt made to close it and what each measured, why the remainder is structural rather than incidental,
and what the design buys in exchange. Then the decision is the user's, explicitly. Silence, a passing mention,
or a summary that leads with the win is not acceptance.

**The balance point.** This is not a mandate for unbounded optimization; it is a mandate against quiet
acceptance. The stopping condition is a named mechanism, evidence that closing it is either done or genuinely
structural, and an explicit decision from the user. Reaching that point and stopping is correct. Reaching
"it is slower but the design reads better" and stopping is not.

### One variable per measurement

**A measurement over a bundled change attributes nothing.** The worked example is the suspension redesign. Two
changes shipped together: a node-layout change (a suspension stops being a `Transform` and its continuation moves
into a separate node) and an implementation change (currency handling hoisted out of the suspension's protocol
method into the two evaluator sites that produce currency). The bundle was faster, and the win was confidently
attributed first to the node layout, then, after a JIT log arrived, to the method size. Both stories were wrong
as told. Isolating the currency hoist onto the *old* layout settled it: the protocol method went from 68 bytes
and `failed to inline: callee is too large` to 25 bytes and `inline (hot)`, worth about 3% on the fused-handler
row, while the remaining ~7% (and ~13% on trailing maps) belonged to the layout. Two independent costs; neither
single-cause explanation survived the isolation run.

Rules that follow:

- **Split design changes from implementation changes and measure each.** If a probe changes a node's class *and*
  the body of a method on the hot path, it has no attribution until one of them is measured alone.
- **The cheap direction first.** Reverse the implementation change on the old design before redesigning around
  it; that experiment is two edits and it can dissolve the case for the redesign entirely.
- **Diagnose, then design.** "It is slower, so replace it" is not a diagnosis, and a redesign justified that way
  is unfalsifiable. Name the mechanism first, then decide whether it is reachable without a redesign.

### The evidence ladder

Wall clock says *whether*; it never says *why*. Climb until the mechanism is named, and stop there:

| step | what it answers | how |
|---|---|---|
| wall clock | is there a delta outside drift | `Jmh/run -f 3 <rows>` |
| allocation totals | did allocation change at all | `-prof gc`, read `gc.alloc.rate.norm` (B/op) |
| allocation sites | which classes are allocated, and by whom | `-prof "async:libPath=<dylib>;event=alloc"` |
| cycles | roughly where time goes | `-prof "async:libPath=<dylib>;event=itimer"` |
| JIT decisions | what the compiler refused, and why | `-f 1 -wi 5 -i 1 -jvmArgsAppend "-XX:+UnlockDiagnosticVMOptions -XX:+PrintInlining"` |

Reading notes that decide how much weight each carries:

- **`gc.alloc.rate.norm` is exact and nearly noise-free.** Identical B/op across two variants *rules out*
  allocation as the cause and forces the search into path length or code shape; a delta localizes immediately
  when paired with the allocation-site profile.
- **The CPU profile is coarse.** A one-second iteration yields a few hundred samples, so a 1.6% entry is seven
  samples. Use it directionally, to notice that a method appeared or vanished, never to attribute percentages.
- **`PrintInlining` is the highest-signal tool for kernel work**, because it prints each callee's bytecode size
  next to the decision. Grep it for the specific method rather than reading it whole.

### Method size is a design property, not a micro-optimization

HotSpot inlines by budget: roughly 35 bytes always, 325 for hot callees, and `failed to inline: callee is too
large` on a *small* method means the caller had already spent its budget. So a hot-path method's bytecode size
is part of its design, and the way to keep it small is to move cold work out of line: currency checks, error
paths, region rebuilds, growth and truncation loops. The 68-to-25-byte result above is exactly this, and it was
invisible to every measurement except the inlining log.

Two corollaries the logs make concrete:

- **The eval itself will never inline** (`Eval$::loop` at ~1500 bytes, `dispatch` at ~557 report
  `inlining prohibited by policy` / `hot method too big`). That is expected and fine; it is precisely why
  everything they call on the per-suspension path must be small enough to inline *into* them.
- **`no static binding` on a 0-byte abstract method is megamorphism, not a defect.** `Step::head`, `Step::tail`,
  and `Transform::apply` report it in every variant, because those sites see every arrow kind and every user map
  site. Do not chase it; confirm it is equal across variants and move on.

### Brackets, drift, and preservation

- **Controls are same-session and back-to-back.** Numbers from an earlier session are not comparable, and a
  design's numbers are meaningless without its control re-measured beside it.
- **Know the drift band before believing a delta.** Repeat runs of identical code have moved 3-4% here. Inside
  that band there is no result; widen the run or find the mechanism.
- **`-f 3` for a claim, `-f 1` for diagnosis.** Never report a `-f 1` number as a result.
- **A bracket runs in a throwaway worktree, never in the tree you commit from.** `git worktree add --detach`
  costs seconds and makes the whole class of accidents impossible.
- **Flip designs with `git restore --source=<sha> --worktree`, never `git checkout <sha> -- <paths>`.** Checkout
  writes the *index* as well as the working tree, so an interrupted bracket leaves the comparison design staged,
  and the next commit silently sweeps it in. That happened: a commit whose message claimed to touch only a skill
  file reverted the entire redesign, and three later commits built on the reverted tree before anyone noticed.
- **Commit before the bracket anyway.** Uncommitted work in a tree an experiment touches is destroyed by it.
  Commit first, even red, even mid-refactor, and commit the user's in-progress TODOs on sight for the same
  reason.
- **Rejected experiments become branches, never stashes.** A stash is invisible in every later summary and gets
  forgotten; `parked/<name>` keeps the diff and the reason findable.
- **Know what fraction of the row is yours.** `boxToInteger` accounts for ~44% of samples on the suspension rows
  because the benchmarks thread `Int`s through effect boundaries and the pending union is erased. Kernel deltas
  are therefore diluted in these rows, and an "optimization" that moves boxing is measuring the benchmark.

### Mechanics specific to this module (performance)

- **Jmh extends Test here.** A bracket that checks out an older commit's `main` sources must check out that
  commit's tests too, or the run fails to compile against the newer suite.
- **The first Jmh invocation after a recompile can report no matching benchmarks.** Rerun it; it is not a
  configuration error.
- **Never edit sources while a run is in flight**, and remove the untracked `<Bench>-AverageTime/` directories
  the profiler leaves behind.

## The backlog: how work is queued and answered

Kernel work outruns a single exchange almost immediately: designs get parked, rulings get made, TODOs accumulate
in the source, and three threads run at once. The backlog file is where that state lives, and it is a working
board rather than a log. Two shapes have been used and both stay available.

**The collaborative board** is the default whenever the user is present. Sections by state, in this order: done
awaiting ack, implementing now, next up, and parked awaiting a decision to revive. The board's own header states
the contract, and it is worth restating verbatim because it is the whole discipline: each item carries its own
context so it reads without the linked docs, the docs carry the full designs, and done work is removed once
acked. A board that accumulates finished items has become a log and stops being read.

**The autonomous queue** is for a session the user is away from. A numbered list in execution order with `[x]`
and `[ ]` markers, each item naming the concrete sites it touches, quoting the user's instruction verbatim where
one exists, and ending in its acceptance step, which is almost always test and commit. Its header carries the
standing status (what is done, whether the suite is green, the commit range, whether the tree is clean) and the
working rules in force for that session. Trailing sections hold what was deferred and why, and the reference
numbers a later session would otherwise have to re-measure.

### The item contract

An item opens with context that stands alone: what the thing is, what is wrong with it or wanted from it, and
what it interacts with. Then the analysis or the design, in enough depth to be argued with. Then either the open
question, stated so it can be answered in one line, or the ruling once it exists. A reader who has not seen the
conversation must be able to act on the item, because within a week that reader is the author.

Rulings are recorded **in the user's own words, quoted**. "Parked by your call (not sure about this region
thing)" survives a compaction and a month; "user decided against it" does not, and invites a later session to
relitigate a settled question from a summary of a summary. The same applies to the reasoning behind a rejected
alternative: record the failure mode and the number that killed it, so the next attempt starts from the fourth
design rather than the first.

### How the two sides use it

**The user writes into the file directly**, as new items, as objections inside an existing item, or as TODOs in
the source that become items. **The agent answers per item, in the file**, not only in chat: analysis, a design,
or a question, written into the item it belongs to. Chat carries the summary and the ask; the board carries the
state.

Rules that make this hold:

- **Every item the user wrote gets an explicit response**, including the ones the answer is "nothing to do" for,
  with the reason. Silence on an item reads as agreement and is how a real objection gets buried.
- **Never silently reorder, merge, split, or drop an item.** Reordering is a proposal, made in the open. An item
  that turns out to be two items says so and keeps both.
- **An item that conflicts with a recorded design decision is raised, not executed.** Bring the recorded
  rationale and its evidence into the item and ask, because the alternative is a change that reintroduces a
  failure the project already paid for.
- **A TODO the user writes in source is an item.** Commit it immediately, because working-tree comments do not
  survive the A/B brackets, then mirror it into the board with the analysis. The source keeps the marker; the
  board keeps the reasoning.
- **The board is committed on the working branch**, for the same reason. An untracked board is one stray
  `git checkout` or `git clean` from gone, and this project has already lost uncommitted work exactly that way.

### The harness enforces this; use it

`bench-harness/` beside this file implements the protocol below as a program, so a result that
violates it cannot be produced. It is an isolated sbt project on published kyo artifacts (its own
`build.sbt`, kyo-test suites), deliberately independent of the repo's build, so it compiles and
runs while the kernel tree is red or mid-edit and never shares the kernel's sbt server. `sbt test`
in that directory self-checks every guard in seconds without running a benchmark; the commands are
`sbt "runMain BenchRun ..."`, `BenchBracket`, `BenchCompare`, `BenchIngest` and the rest, listed
in its README.

Prefer it over hand-written bash. Every guard it carries exists because typing the bracket by
hand got that exact thing wrong and either corrupted a measurement or wasted a run. When a rule
below changes, change the harness with it; a protocol documented in one place and enforced in
another drifts apart silently.

The drift runs the other way too, and this list is the correction: these are rules the harness
now enforces that the text below never stated, so a reader working from the prose alone would
not know to follow them.

- **A session that cannot check itself is not a session.** Every bracket runs an A/A null over
  its control legs. Each row that null classifies is a false positive by construction, so a
  dirty null, or too few control legs to run one, is a blocker with an exit code and not a
  warning line. The data still prints in full; what changes is that it cannot be mistaken for
  a clean run.
- **Two shas attribute nothing.** A pair measures the difference between two trees, and the
  partition between the changes inside that diff was never declared, so "this moved because of
  X" is unsupported however plausible X is. Declare a chain of three or more shas to isolate
  one change; each adjacent step is then attributable and says so.
- **A falsifier whose flag did not take refutes nothing.** `CompileCommand=inline` is a hint,
  and HotSpot still refuses on `MaxInlineLevel`, node budget, or a method it cannot compile.
  Such a run is inconclusive. A confirm additionally requires that *only* the instructed
  method's verdict moved, since forcing a callee spends the caller's remaining budget.
- **A leg still warming up is a blocker.** Judged from the per-iteration series by how far the
  first iteration drags the mean the verdict is computed from, not by a fixed percentage, which
  cannot separate jitter on a noisy row from a ramp on a stable one.
- **Allocation is attributed to a site, not just a class.** The flat profiler table names the
  class and can never say by whom; the collapsed view carries the stack. Both come from one
  recording, because conservation between two recordings measures run-to-run variance rather
  than the parse. The frame named is where the JIT *placed* the allocation, so this is never
  independent of the inlining verdicts.
- **Every delta arrives with the experiment that could contradict it.** A comparison that stops
  at "this row moved" is where "it is slower, so replace it" comes from.

### Reporting a benchmark run

Numbers are reported in one standard table carrying **everything the run produced**, never as
prose with a few scores picked out. A reader must be able to judge the result without asking
what was run, against what, or how confident it is.

The header states provenance and configuration: the two commits compared, where the run
happened, the JMH configuration (forks, warmup and measurement iterations), the verified
design markers for each leg, and the machine's current drift band. Without the band a
percentage is uninterpretable.

The table carries one row per benchmark, with the score **and its error** for both legs, the
delta, and a status marker. Never drop the error column: a delta smaller than the combined
error is not a result, and a reader who cannot see the error cannot tell. Include every row
the class defines, including the flat ones, because "everything else was unchanged" is a
claim that has to be visible to be trusted. Sort by delta so the extremes read first.

Status markers, used consistently:

- 🟢 faster beyond the drift band
- ⚪ flat, inside the band, no result
- 🔴 regressed beyond the band, which means the work is unfinished
- 🔵 below measurement resolution, where the percentage is an artifact and is labeled as one

When a row is flagged, the table says what was done about it: confirmed at `-f 3` with the
tighter numbers, or diagnosed with its mechanism, or listed as open. A flagged row with no
follow-up line is an unfinished report.

Secondary profiler data belongs in the same report when it exists: `gc.alloc.rate.norm` in
B/op next to the score settles allocation questions on sight, and the inlining verdict for a
method under discussion belongs beside the row it explains.

### Finish the ladder; a rung that fits your guess is not the answer

Eliminating a cause is not naming one. When `gc.alloc.rate.norm` comes back identical, that
rules allocation out and says nothing about what the remaining cycles are doing. Stopping
there and filling the gap by reading the code produces a sentence that sounds like a
diagnosis and is really a hypothesis with a profiler's credibility borrowed from the previous
rung.

**Every statement about mechanism cites the tool output that shows it.** "The call site is
megamorphic so it cannot inline" is a claim about what the JIT did, and the JIT publishes
what it did; assert it only with the `PrintInlining` line in hand. The same holds for "this
allocates more" (the B/op figure), "this is where the cycles go" (the CPU profile), and "this
method got too big" (the byte count in the log). If no tool output was produced, the honest
sentence is that the cause is not yet known.

This work has no room for assumptions. Three times in one session a mechanism was stated
confidently and then contradicted by the next measurement: a redesign was credited to the
wrong one of its two changes, a residual was called structural before the variant had ever
been profiled, and a type test was blamed for a regression that removing it did not fix.
Each was reasoning about code that had not been run.

### A win and a loss are two diagnoses, not one tradeoff

When a change is a large win on one row and a smaller loss on another, the tempting move is
to weigh them and ship. That decision is not available until **both sides have a named
mechanism**, because the usual outcome of investigating the loss is that it turns out to be
removable, and the trade never had to be made. Accepting the loss early ships a defect that
the same afternoon's work would have deleted.

So the sequence is: diagnose the win (what makes it fast, and is it real), diagnose the loss
independently (down the full ladder, not by symmetry with the win), then ask whether a shape
exists that keeps the win without the loss. Only when that search has actually been run does
the trade become a decision, and then it is the user's, presented with both mechanisms and
the numbers.

## Optimization techniques

The catalog of moves that have actually worked here, and the ones that reliably look right and
do not. Every entry is a hypothesis generator, never a justification: each was established by
measurement on this codebase and each must be re-measured where it is applied next.

**`inline` is never added without the user's approval.** It is a public-surface and
compile-cost decision as much as a speed one: expansion multiplies bytecode at every call
site, inflates compile time, and interacts with the macro suspension equilibrium. Two uses in
the proto (`Eval.dispatchInline`, `Effect.deferInline`) were introduced without asking and
stand as open questions rather than precedent. Propose it with a measurement and let the user
decide.

Moves that have paid:

- **Make the hot method smaller by moving cold shapes out of line.** The entry point keeps the
  common shape; every other shape becomes one call into a private method. This is what lets a
  delivery path serve several continuation shapes without any of them paying for the others.
- **Do the work once at construction instead of once per use.** The largest single win measured
  here (41% on trailing maps) came from delivering through a folded continuation directly
  rather than routing it through `Identity`, which allocated a `Bind` on every suspension.
  Ask where in the value's life the work can happen only once.
- **Hand back a value the caller already has.** Passing the whole arrow to a handler as its own
  continuation removed a per-suspension closure allocation, because the continuation the
  handler needs is the value the user already built.
- **Specialize the degenerate tier.** The empty-region case of dispatch is three lines and is
  what the fused rows execute every time; keeping it as its own branch above the general
  region-copy path costs nothing and skips all of it.
- **Delete state the hot loop maintains for a cold consumer.** A `suspended` variable written on
  every iteration existed only to enrich an exception at one boundary, and no test pinned it.

Moves that looked right and measured flat or worse:

- **Inlining the delivery entry.** Making it small enough to inline measured *slower* than a
  larger version that the JIT refused, on the same row. Method size is a lever on some paths
  and inert on others; the log tells you which, and only the benchmark tells you whether it
  matters.
- **Fusing composition into wrappers.** Delegating wrappers made mapped suspensions fast and
  made `tag`, `input` and `cont` O(depth), which is a stack overflow at 100k maps. Speed that
  scales with user input is a defect, not a fast path.
- **Unrolling a delivery by one level.** Tried against the trailing-map fold, measured no
  better on its target row, reverted.
- **Reference equality against a type test.** Measured indistinguishable on the composition
  path; neither is a reason to choose the other.

The framing that generates candidates: allocation per operation, indirections per delivery,
work repeated per use that could happen per construction, and state maintained for a consumer
that rarely reads it. Profile first to learn which of those the row is actually spending on,
because on the suspension rows roughly 60% of samples are boxing the benchmark's own `Int`s
and no kernel change touches that.
