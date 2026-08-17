# bench-harness improvement plan (v4)

Supersedes v3. Three held-out reviews and my own re-derivations have now run against this plan; v4 is
the first version whose every load-bearing number was re-derived by someone other than its author.
Evidence: `reviews/REVIEW-PLAN-V3.md`, `reviews/ORACLES.md`, `reviews/FEATURE-SURVEY.md`.

## What v3 got wrong, corrected up front

| v3 claim | truth | source |
|---|---|---|
| "14 of 22 `Run` fields reach no output" | **3** (`coverage`, `treeHash`, `warmup`). The list named 13 things, 5 of them `Run` fields, and 2 of those do render | F3 |
| "`Run.alloc` costs a full extra JMH invocation, surfaces as one integer" | one recording dumped twice; `allocByMethod` **is** rendered. Only the `itimer` run has the thin yield | F4 |
| "the other 91% are shared methods and belong to no row" | **64.8% is row-attributable** on the C2 population, 74.5% on the `kyo.`-callee population | F1 |
| my "8.9% is wrong, it is 2.2%" | **backwards.** 8.9% was right; `Run.jit` is C2-only (`level >= 4`), and 837 of 946 tasks carry C1 levels | ORACLES §2 |
| `KnownNoise` "truth is 83.97%" | a **third classification**, not the truth, and its predicate is contested | F5 |
| "fifteen result-driven selectors" | **twelve**; `Bytecode.of`/`diff` are captures, `measureDrift` is a measurement | F15 |
| acceptance gates A and B | A is under-specified and self-defeating; B is unsatisfiable on the data that triggers it | F6, F7 |

## The thesis, right-sized

v3 said "the harness captures a great deal at real cost and renders almost none of it". That indicts
everything and ranks nothing, and `Report.render` in fact emits fifteen conditional sections. Replaced:

> **The per-leg evidence has no renderer, and the comparison renderer withholds exactly the fields that
> say whether it can be trusted.** `bench run` prints one line after four JMH invocations
> (`Cli.scala:178`); `bench show` prints five (`:393-397`). `coverage`, `warmup` and `treeHash`, the
> three fields that answer "is this leg readable at all", reach no output. Per-row attribution is
> discarded by one `flatMap`.

That statement ranks the work by itself: it is items 3 and 2, in that order.

## Acceptance, rebuilt

**Gate A, selector reachability.** A source-symbol walk from a declared root set (the eight
`KyoCaseApp` objects), **with no allowlist**. An allowlist is the edit-around, and the plan cannot
claim a gate "fails loudly" one sentence after granting one. Unreachable means wire-or-delete, which is
what item 10 already says for `commonMode`. Not reflection (declared members, never call sites) and not
`Bytecode.parse` (`javap -c` omits the `BootstrapMethods` attribute where Scala 3 lambda bodies live).

**Gate B, per-section evidence**, conditioned on `Evidence.Full`, with a Timing-path variant asserting
on the row's iteration series. Its fixture is **produced by 0c**, not assumed. As v3 wrote it, the gate
asked the report to print a method name from `variant.jit`, which on a Timing leg is empty by
construction, for precisely the comparisons that produce unexplained rows today.

## Step 0, prerequisites

**0a. A multi-row leg's compilation log describes only its last row.** `runLeg` passes a fixed
`-XX:LogFile` at `-f 1` through `-jvmArgsAppend`, which JMH applies to every fork, and JMH runs one
forked JVM per matched benchmark. My two-JVM probe shows HotSpot truncates on open. **Fix: HotSpot's
own `%p` expansion, a one-token change**, then parse the set. Still unobserved end to end by anyone
(task 20); everything said about it is inference from the fixed path.

**0a has two consequences already in the output, which v3 never stated:**
- **F14**: `msInWindow`/`msTotal` are summed across all rows from `-prof comp` while the task census
  comes from the single surviving fork's log, printed as one table headed "JIT cost, which is a
  property of the design". A multi-row Full bracket renders a 15-row compile-time sum beside a 1-row
  census.
- **F13**: `parseAlloc` runs over the whole sbt stdout, one flat table per benchmark, so `Run.alloc`
  carries duplicate `cls` rows; `apportion` is last-wins on the numerator while `totals` sums across
  all benchmarks. **`allocConservation`, item 9's subject, is the check that fires on this.**

**0b. No decodable jit data, and defect 17's ORIGINAL class is live.** The two Full runs fail on
`osrTasks` and carry the old `JitEntry` shape. But also: they lack `coverage`, `jvmArgs` and
`allocByMethod`, and of those **`coverage` (`Model.scala:307`) has no default**. So a new field with no
default is in the tree right now, and v3 asked only for a changed-shape test. Both are needed.

**`StoreSchemaTest` does not exist.** The check is inline at `BenchTest.scala:498-511` and covers
`allocByMethod` only; the name came from a stale comment at `Model.scala:323` that v3 inherited
unchecked. Fix the comment too.

**0c. `Ingest` cannot produce a jit-bearing run at all** (`evidence = Timing`, `jit = Chunk.empty`
hardcoded). Gate B's fixture depends on this.

**0d.** `--drift-row` is inert: `openSession(worktree, driftRow)` never references the parameter.

**0e.** `driftPercent` hardcoded `0.0` in both `openSession` and ingest, so "drift measured this
session" is unreachable for anything measured now. Caveat: `Store.session` can recover an older session
carrying a real value (the QA fixtures hold 3.95), so this is not "every stored pair".

**0f.** `QaEndToEnd` asserts `driftPercent > 0.0`, now always false. **And its `check` only prints**
(`:17-18`), unlike `BenchTest.check` which throws (`:41-43`), so "fails by construction" today means
"prints FAIL and exits 0". **A QA main whose checks cannot fail is the more serious half.**

**0g. Corrected from v3, which called this unreachable.** `flatButUnbounded` is unreachable, but the
warning is not: `Store.scala:263` counts `flatButUnbounded || verdict == BelowResolution`, reachable
via `bracket --legs 2` (df 0, empty threshold). **The live defect is the text**, which says "N flat
rows carry no resolution" about rows that are not flat. Deleting the branch as dead would remove a
reachable, mislabelled warning.

## The changes

### 1. Wire `efficacy` into the comparison report
**Disambiguated (F15):** there are two. `LogCompilation.efficacy` takes two `Parsed`, a type never
persisted; `Investigate.efficacy` takes two `Run`s and **is the wirable one**, dead only because
`adjudicate` is dead. Wire the `Run`-based one; delete the `Parsed`-based pair or move it behind an
ingest that stores `Parsed`. Needs 0c, and needs `jvmArgs` on ingest (F11) since that is the only route
by which it can learn which method a `CompileCommandFile` instructed.

### 2. Per-row attribution, re-decided AFTER 0a
**F2: 0a subsumes most of this and v3 did not notice.** Once each fork writes its own log, the row key
is the filename and every verdict in the file belongs to that row. The `Task.method` join v3 proposed
then discards about 90% of what the file already attributes. **So item 2's content is re-decided after
0a, not merely unblocked.** What survives regardless: the unexplained-row line must carry its own
evidence, and must not name an inlining log on a Timing run (defect 43).

Numbers for the item, all re-derived: on the C2 population, 99 verdicts (8.9%) are the row's own stub,
625 (56.0%) the benchmark's own code, **724 (64.8%) row-attributable**. On a *single-row* leg, where
attribution is trivial. **The whole-class ratio is unmeasured by anyone.**

### 3. `Report.renderRun`, used by `run` and `show`
The thesis's first half. Full render for `run` and `show`; for `bracket`, a one-line digest per leg
carrying `coverage` completeness, jit entry count and guard status, plus the full render only for a leg
that failed a guard. Gives `coverage`, `warmup` and `treeHash` their first output.

### 4. REWORK IN FLIGHT: the three-way split
Landed as a two-way split (`50a8b73dfd`), and the inversion is right and stays: a noise **list** must
enumerate an open set, a kernel **package** is closed. Three problems remain (F5):

1. The sentence still ends "so kernel-attributable movement is a fraction of each delta above", which
   does not follow from a frame-name partition and is now asserted at 84% instead of 29%.
2. **The note now fires on every Full leg.** The `n < 25.0` gate became unreachable in the other
   direction, and a note that always prints carries no information, which is this plan's own complaint.
3. **The partition is contested.** `KernelPackage` classifies `ProtoKernelBench$$anon$95` as immovable,
   and that allocation is the entire subject of candidate C3.

**Fix: report three shares and let the reader infer.** `Bench.cpuPartition` is committed unwired
(`a70e15f89a`); wire it and drop the inference:

    16% kernel (kyo.kernel.proto), 48% the benchmark's own closures, which the kernel
    decides how often to run, 36% boxing and infrastructure. Largest contributors: ...

**Open question, flagged:** whether async-profiler's `itimer` flat output credits inlined callees to
the inlining frame. Unsettled by anyone; the three-way split does not depend on it, which is a further
argument for it over either two-way partition.

### 5. REWORK: the A/A null breaks both commands it wires
**F8, and v3 called this prerequisite-free.** `nullComparison` returns `Maybe.empty` below three
controls and `nullBlockers` turns empty into a blocker with an exit code. Wired as designed, **every
single-pair `compare` and every default `chain` (2 legs per sha) exits non-zero.** Needs a conditional
or a default change: below three controls the report states that the null could not run and why, which
is evidence-carrying, and does not fail the command.

### 6. REWORK: `resolves` needs a model change, not a render change
**F8.** Both terms are computed inside `Stats.threshold` and **discarded there**; `Resolution` carries
only `percent, absolute, df, alpha`. "Already computed" is not "available at the render site". Add the
bound to `Resolution`, which is on `Delta` and never persisted, so no schema risk.

Confirmed to the digit by the reviewer re-implementing `Stats.threshold` independently: 7 of 15
floor-bound, `handleLoopAnswersInPlace` at −10.72% with a 14.43% threshold set entirely by own error
against an 8.20% spread term.

### 7. DONE and confirmed correct
Keyed on the earned threshold rather than the fork count; `exists` is right and matches the existing
branch at `Store.scala:269`. Also fixed a false negative: single-pair at `-f 3` was treated as a claim.

### 8. Orphaned `Investigate.adjudicate`
The report proposes experiments and cannot score them. The only justified new entrypoint.

### 9. `allocConservation` has no production caller
**And F13 gives it its real job:** it is the check that fires on the multi-row allocation break. Wire
it beside 0a rather than as a standalone rendering fix.

### 10. `commonMode` computed and discarded, and it closes 0e
Carrying it on `Comparison` lets the report print a measured drift instead of the assumed 4.0, and
gives `residual` a meaning. Wire or delete both, with no allowlist.

### 11. RESCOPED: `warmup` is the harmless half
**F11, and v3 picked the wrong field.** `warmup` is fabricated and has **zero readers**. In the same
constructor:
- **`forks = 1` is equally fabricated and the json carries the truth.** It has **three** readers: the
  header, the diagnostic note item 7 just rewrote, and `stillCompiling`'s measured-window denominator,
  where a wrong value mis-scales the steady-state guard.
- **`jvmArgs` is discarded entirely** though every JMH json carries it, so an ingested configuration
  pair prints "no recorded JVM arguments on either leg ... the difference is unrecoverable" about a
  difference sitting in the file it just read. **Item 1's acceptance depends on this.**

Only `warmup`, `forks` and `jvmArgs` on `Run` touch the persisted schema; default every added field.

### 12. NEW: `mode` and `unit` are captured and never checked
**F12, ranked above item 11**, and **verified broader than the review stated**. The review cited the
single-pair path only. Both paths have it:

- `Bench.compare`: `if percent < -drift then Verdict.Faster` (`Bench.scala:615`)
- `Stats.classify`: `if diff < 0 then (Verdict.Faster, th)` (`Stats.scala:171`)

Neither consults `mode`. So **a `thrpt` row is classified backwards in replicated comparisons too**,
not just single pairs, and two legs in different time units give a percentage off by the unit ratio.

`mode` is captured (`Bench.scala:233`) and used **only for display** (`Store.scala:299`). `Row.unit` has
**zero readers anywhere**. The harness's own benchmark is `AverageTime`, so this is latent today; the
exposure is `bench ingest`, which accepts arbitrary JMH json with no check on either field.

Fix: refuse a pair whose `unit`s differ or whose `mode`s differ, and invert the sign convention for
`thrpt` in both classifiers. **Acceptance:** a synthetic `thrpt` pair where the variant has higher
throughput classifies `Faster`, and today classifies `Regressed`.

## Anti-goals

- **No new subcommands** except item 8.
- **No new statistics.**
- **No claim the data cannot support.** Narrowed twice now: not "no row→method attribution" (false),
  and not "the non-kernel share is X" (contested). State the partition, not the inference.
- **v2's item 7 stays cut.** Confirmed: HotSpot's `bytes=` and javap's size are the same quantity, and
  `verifyAgainst` exists to assert that identity. Three caveats carried into items 1/2: the 94% was
  measured over the fixtures' old JDK-inclusive records so the population under the current C2 +
  `kyo.`-prefixed path is unmeasured; `bytes = 0` means "unloaded, size unknown" and must be treated as
  unknown or every method missing a size reports "grew past MaxInlineSize"; and `InlineSites` keys on
  `class::method` with no signature, so overloads collapse.

## Ordering

**Free-standing, start now:** 4 (rework, in flight), 9, 10, 12, and 11's `forks`/`jvmArgs` half.
**Then Step 0**: 0a (with F13/F14), 0b (both schema classes), 0c, and the corrections 0d, 0e, 0f, 0g.
**Then** 6 and 5, which need model and default changes respectively.
**Then** 1 and 2, whose content 0a re-decides, then 3, then 8.

Item 7 is done. Lowest value in the plan is item 11's `warmup` half, kept only because it rides along
with `forks` and `jvmArgs` in the same constructor.
