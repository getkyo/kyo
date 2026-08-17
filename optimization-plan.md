# Ten candidate optimizations

Merged from three independent analyses (`optimization-candidates/{allocation,dispatch,inlining}.md`,
1546 lines) which read the kernel and the captured artifacts without running anything. Selected for
spread of mechanism rather than for rank: two rows of the same kind would waste the exercise.

Each carries a hypothesis, the mechanism, **which harness field moves if it is true**, what would
falsify it, and whether it needs something gated. None may land: candidates are measured in the
detached throwaway worktree and arrive as numbers and diffs.

## What the evidence already settles, before any candidate

Three findings reframe every prior assumption, each verified locally:

**Every inline refusal in the kernel is C1.** Of 1969 refusals, 1929 are C1 and 40 are C2, and the
split by reason is total: `callee is too large` 1166 C1 / **0 C2**, `no static binding` 205 / 0,
`callee uses too much stack` 173 / 0. C1 runs for about two warmup iterations and has only a 35-byte
gate with no frequency tier. In C2, `Stack::push`, `pop`, `truncate`, `Safepoint::enter`, `exit`,
`Nested::nest` and `Eval$::dump$1` all inline at **every** site. So `Stack::push` refused at 7 of 8
sites, which seeded three of these analyses, is a red herring twice over: the refusals are warmup,
and it runs about once per op against ~1000 map deliveries.

**What C2 actually refuses** in the kernel is six methods, and the one worth knowing is
`Eval$::loop`, refused at all 5 sites for `hot method too big`.

**The log covers one row of fifteen.** Every artifact is a single fork of
`nestedPayloadsUnwrapInMaps` (59 hits; zero for the other fourteen). Every target-row prediction
below is therefore a prediction, not an observation.

## The ten

### 1. DIS-1: dispatch tier split
**Hypothesis.** The two suspension arms are not symmetric. The `Suspend` arm calls `dispatchInline`,
expanded into the loop (bci 146-790); the `SuspendWith` arm calls `dispatch`, which `javap` shows as a
separate **607-byte `dispatch$1`** invoked at bci 821 of a 1582-byte `Eval$::loop`. `ask.map{...}` used
to mint a `Suspend` and now mints a `SuspendWith`, so this row's entire per-iteration path moved from
the inlined copy to a non-inlinable call, 1000 times per op. Six other rows moved to the same arm and
got *faster*; `continuationBodiesFuse` is the only one whose continuation body (ten fused maps) is
itself too big to inline, so it alone cannot repay the frame with downstream inlining.
**Field.** JMH score on `continuationBodiesFuse`; per-site C2 verdict for the dispatch entry.
**Falsifier.** Adding the frame to `36b41336fb` does not reproduce the +4.3%.
**Rows.** `continuationBodiesFuse`, `suspensionFusesContinuation`. Not the fusion or map rows.
**Cost.** Low. **Gates.** None.

### 2. DIS-2: `Transform`-first delivery
**Hypothesis.** The delivery path tests the less likely shape first, so the hot row pays a failed
type test per step.
**Field.** JMH score; bytecode order via `javap`; the `-XX:FreqInlineSize=600` probe moving both legs
together or not.
**Falsifier.** `FreqInlineSize=600` removes the gap on both designs, which would mean the cause is
budget, not order.
**Rows.** `continuationBodiesFuse`, `trailingMapsStayLinear`.
**Cost.** Low. **Gates.** None.

### 3. C1: park from the payload, not from the incoming union
**Hypothesis.** The `Nested` box is heap-allocated only because the park arm stores the incoming
union; taking it from the payload removes the allocation entirely.
**Field.** `gc.alloc.rate.norm`, exact and per-op. The row is exactly 32,080.04 B/op, which is two
16-byte kernel nodes per iteration over 1001 iterations: `Nested` 16,043 and the `Suspend` node
15,985. This targets the first 16,043.
**Falsifier.** B/op unchanged.
**Rows.** `nestedPayloadsUnwrapInMaps` and the boxing rows; not the fusion rows.
**Cost.** Eight mechanical edits to one repeated arm (`Pending.scala:55/82/108/134/287`,
`ArrowEffect.scala:91/161/234`). **Gates. Needs an explicit `Nested(...)` spelling, which the skill
forbids without sign-off.**
The single cheapest decisive candidate: one allocation reading settles it either way.

### 4. IN-3: take the VarHandle guard chain off `Safepoint.get`
**Hypothesis.** One `AtomicReferenceArray.get` force-inlines 434 bytes into the 1708-byte hot unit
(25.4%); the whole safepoint poll is 712 B (41.7%), and shrinking it may flip the log's hottest
refusal.
**Field.** `javap` size of the hot unit; the C2 verdict on `loop$9` (`already compiled into a big
method`, count 109,640); JMH score.
**Falsifier.** The unit shrinks but the verdict does not flip, or flips with no score movement.
**Rows.** All suspension rows.
**Cost.** Medium. **Gates. Owner-gated: raises a memory-model question.**

### 5. IN-1: split `Arrow.Identity.apply` into fast entry and slow path
**Hypothesis.** 86 of 92 bytes are dead on the measured path; the branch record shows
`if_acmpne taken=0 not_taken=6007`, never taken, inlined twice into the hot unit.
**Field.** Per-site C2 verdict: predicts `deliver` refused with `low call site frequency` at the same
5 sites; `javap` size.
**Falsifier.** The size drops but no verdict moves and no score moves.
**Rows.** Map and delivery rows.
**Cost.** Low. **Gates.** None (a fast/slow split, not an `inline` annotation).

### 6. C3: move the park trigger out of the delivery arm into the chain structure
**Hypothesis.** While the park decision is a branch inside the delivery arm, every delivery pays for
it; moving it into the structure removes the second 16 KB/op.
**Field.** `gc.alloc.rate.norm`; JMH score across the suspension rows.
**Falsifier.** B/op unchanged, or moved with a compensating regression elsewhere.
**Rows.** Suspension and handler rows.
**Cost.** High: a design change to preemption. **Gates. Owner-gated: wants a conversation first.**
Highest ceiling of the allocation set, and the only one reaching the second 16 KB/op.

### 7. DIS-3: one suspension arm, unifying `Suspend` and `SuspendWith`
**Hypothesis.** The drive tests two classes and carries two copies of one concept; collapsing them
deletes 607 bytes and one type test from every non-suspension path.
**Field.** `javap` size of `Eval$::loop`; then the 15-row sweep.
**Falsifier.** Size drops without any score moving.
**Rows.** Broad, which is itself a risk: a candidate predicting everything moves is weak.
**Cost.** Low-medium. **Gates. Needs a cast (`@unchecked`) at the collapsed arm.**
Must not share a bracket with DIS-1: it subsumes the byte saving but leaves the frame in place, so it
must not be credited if the row moves.

### 8. C4: restore the safepoint budget when delivery throws
**Hypothesis.** `Safepoint.exit` is unguarded, so every exception unwinding through a delivery leaks
budget permanently.
**Field.** Not a score. A **reproduction**: budget after N throws.
**Falsifier.** Budget is restored, or the leak is bounded.
**Rows.** None necessarily. This is a **defect, not a speedup**, and is in the ten deliberately: it
tests whether the harness can handle a candidate whose deliverable is a failing test.
**Cost.** Low. **Gates.** None. Needs a reproduction before any edit, per the project rule.

### 9. IN-2: split `Eval.dump` and `Stack.truncate` at their entry test
**Hypothesis.** Both execute entry-compare-and-return essentially always (`truncate` taken 4382/4382;
`dump$1`'s `i == top` never-not-taken 4386), so the body is dead weight in the hot unit.
**Field.** `javap` sizes; C2 verdicts at their 5 sites; JMH score.
**Falsifier.** Sizes drop, verdicts hold, score flat. Likely outcome given both already inline hot.
**Rows.** Map-heavy rows.
**Cost.** Low. **Gates.** None.
Included as the honest low-ceiling case: its own analysis predicts it changes no score.

### 10. DIS-4: replace the drive's linear `instanceof` chains with a node-kind switch
**Hypothesis.** Two linear type-test chains cost more than a tableswitch on an integer kind.
**Field.** `gc.alloc.rate.norm` first (a kind field may cost a word), then the 15-row sweep;
`javap` bytecode shape.
**Falsifier.** B/op rises, or the switch does not change the score.
**Rows.** All.
**Cost.** High: touches every node type. **Gates. Adds a field to every `Arrow`; API-adjacent.**

## Run order

Two isolation experiments first, before any edit lands, because they need no source change and
between them they decide which of DIS-1 and DIS-2 owns the open regression:

1. DIS-1's isolation: add the dispatch frame to `36b41336fb` and see whether +4.3% reproduces.
2. DIS-2's flag probe: `-XX:FreqInlineSize=600` on both designs.

Then C1, as the cheapest decisive allocation reading. Then the rest by rank.

Running them in the other order, or after an edit, reproduces the exact failure the skill records: a
mechanism stated confidently and contradicted by the next measurement.

## Deliberately excluded

- **C2** (`Nested` as a plain final class): nearly free, and its own honest claim is that it changes
  no score.
- **C5** (widen the lift emission's bare-cast arm): correct and worth nothing on this suite.
- **DIS-5** (stack scan consolidation): expects flat scores.
- **IN-4**, **IN-5**: `IN-5` is not evaluable on this log at all, since C2 pruned every `Suspend` bci.
- **Removing the `Frame` field from `Arrow.Suspend`**: predicted saving is exactly zero. A one-
  reference instance and a zero-field instance are both 16 bytes after alignment under the default
  12-byte header. It would save 8 B/op only under `-XX:+UseCompactObjectHeaders`, which this run did
  not use.

## What no candidate here addresses

Roughly 29% of top-frame samples on `nestedPayloadsUnwrapInMaps` are `BoxesRunTime.boxToInteger`, and
the skill puts it near 44% on the suspension rows. That is the benchmark threading its own `Int`
through an erased union. No kernel change touches it, and any percentage read off those rows is a
percentage of something the kernel cannot move.
