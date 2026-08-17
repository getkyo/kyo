# bench-harness improvement plan (v3)

Supersedes v2. v2's thesis was "result-driven selectors exist and were never wired", supported by a
table claiming three of eight were wired. Two held-out agents and my own greps agree it is **one of
eight**, and that the thesis is a special case of something larger. Evidence in `reviews/`.

## The finding

> **The harness captures a great deal at real cost and renders almost none of it.**

Three independent enumerations support this at three scales.

**Selectors.** Fifteen functions decide *what is worth showing given the results*. One is wired
(`InlineSites.nearBudget`). Dead or test-only: `actionableJit`, `measureDrift`, `unprofiledSites`,
`verifyAgainst`, `jitUnstable`, `allocConservation`, `residual`, `diffVerdicts`, `efficacy`,
`budgetCandidates`, `crossedBudget`, `Bytecode.of`/`diff`, `Investigate.adjudicate`. `Stats.commonMode`
is called on every replicated comparison and its result is **bound and never read**.

`diffVerdicts` is worse than unwired: `Comparison.jitChanges` comes from `Bench.jitShift`, a separate
implementation of the same question over a different type. Two implementations, one wired.

**Captured data.** 14 of the 22 `Run` fields reach no output. Two of them cost a full extra JMH
invocation each: `Run.alloc` surfaces as the integer `alloc sites N`, and `Run.cpu` as one percentage
that prints **only when ≥ 25%**. Also unrendered: `coverage`, `treeHash`, `warmup`, `Session.host`,
`Session.jvm`, `Row.unit`, `AllocByMethod.bytes`, `AllocByMethod.site`, `plantedTraps`,
`Resolution.absolute`, `Task.compileId`.

**Outputs.** `bench run` performs four JMH invocations and prints one line. `bench show` renders 944
jit entries as the number 944.

This is why every probe in this campaign was written by hand. The data was captured; the path from it
to an output did not exist. That is not a discipline failure and no amount of remembering fixes it.

## The rule

> **A flag must carry its own evidence.** If the report says a row is unexplained, that paragraph
> contains the evidence it is standing on. If it proposes an experiment, it can score that experiment.
> **No output may instruct the operator to go and look something up.**

## Acceptance

v2's acceptance was a grep of the renderers for imperatives. Run against the code it is **83% false
positives** (5 of 6 hits are nouns or same-output references), and it passes `bench show` printing
`944`, which is the plan's own headline example. Replaced by two gates that cannot be satisfied by
rewording:

**A. Inventory gate.** Every public selector has at least one production call site reachable from a
`Cli` entrypoint, with an explicit allowlist for deliberate exceptions. This is the check that would
have found this entire finding, and it fails loudly rather than being edited around.

**B. Per-section evidence assertion**, driven by a real stored leg. For each conditional section
`Report.render` can emit, the emitted text must contain at least one datum from the record it
describes. Concretely: when `unexplained` is non-empty, the output contains that row's `allocDelta` and
at least one method name from `variant.jit`.

## Step 0, prerequisites

Items 1, 2 and 3 consume `Run.jit`. Today **no run in the repository has any that can be loaded**, and
it is now known that a multi-row leg's log is not what it claims to be. Building on that first is the
error this campaign keeps making.

**0a. A multi-row leg's compilation log describes only the last row.** `runLeg` passes
`-XX:LogFile=<worktree>/logc-$label.xml`, a fixed path, and JMH forks a fresh JVM per benchmark.
Verified with two JVMs sharing one `LogFile`: the file ends with one `<hotspot_log>` header and one
pid, carrying only the second JVM's content. So a 15-row selector at `-f 1` stores one row's log,
deterministically the last, labelled as the leg's. Give the path a per-fork discriminator and parse the
set, or record honestly that jit evidence is single-row and refuse it on a whole-class leg.

**0b. No decodable jit data exists.** 47 of 49 stored runs are `Timing` with no `jit`. The two that
carry it are 1-row QA fixtures and the tool refuses them: `⛔ ... Missing required field 'osrTasks'`.
Their entries are the old `JitEntry` shape (`inlined` boolean) against the current `InlineSites`
(`inlined: Int`, plus required `refused`/`reasons`). Re-ingest or migrate them, and add a schema test
for a **changed field shape**: defect 17's fix defaulted a *new* field, and `StoreSchemaTest` only
covers "recorded before this field existed".

**0c. `Ingest` cannot produce a run carrying a compilation log at all.** `Ingest.run` hardcodes
`evidence = Timing` and `jit = Chunk.empty`, so the ingest path cannot build the fixture item 1's
acceptance needs.

## Live defects found by the survey, not previously filed

**0d. `--drift-row` is inert.** `Bench.openSession(worktree, driftRow)` never references the parameter.
The flag and both `getOrElse("suspensionBaseline")` fallbacks do nothing.

**0e. Every report prints "drift 4.0% assumed, not measured".** `driftPercent` is hardcoded `0.0` in
both `openSession` and ingest, and `measureDrift` is dead, so the "drift measured this session" branch
is unreachable by any command.

**0f. `QaEndToEnd` fails by construction.** It asserts `driftPercent > 0.0` against a value now always
`0.0`.

**0g. `Delta.flatButUnbounded` can never be true.** Both producers always attach a `Resolution` to a
`Flat` verdict, so the "N flat rows carry no resolution" warning is unreachable.

## The changes

### 1. Wire `efficacy` into the comparison report
The guard that stopped DIS-2 being credited with a −17.4% win the flag never caused. When two legs
differ in `jvmArgs`, the report states whether the instructed method's verdict actually moved, and
whether anything else moved with it. **Acceptance:** a DIS-2 pair reports that `dispatch$1` is refused
in both logs, without a probe. Needs 0b and 0c first, since no DIS-2 store exists.

### 2. Give the unexplained-row flag its evidence, and make the row→method join
Replace the homework line with the row's own allocation delta, the leg's actionable refusals and
near-budget methods **explicitly labelled leg-wide**, and the honest split on attribution.

v2 called row→method attribution a hard limit and made saying so the fix. **It is not a limit.** The
row key is on the `<task>` element and the harness already parses it into `Task.method`; one `flatMap`
at `LogCompilation.scala:272` discards it. Measured on a real log: **99 of 1,117 C2 verdicts (8.9%)**
were made compiling the row's own `jmhStub` — the OSR compile the harness itself calls "where the
measured code actually runs". The other 91% are shared methods and belong to no row. So the report says
*these N belong to this row; these M belong to no row*, which is both true and stronger than the limit.

### 3. `Report.renderRun`, used by `run` and `show`
One renderer, two call sites; gives homes to `budgetCandidates`, `unprofiledSites`, the per-leg JIT
cost table, allocation attribution, and `coverage`.

**Resolved open question from v2** (a bracket calls `runLeg` five times): full render for `run` and
`show`; for `bracket`, a **one-line digest per leg** carrying only what is per-leg and otherwise
unrecoverable — `coverage` completeness, jit entry count, whether every guard passed — plus the full
render **only for a leg that failed a guard**. A parse that silently read 637 of 5,093 elements is a
per-leg fact the comparison cannot recover later.

### 4. Fix `KnownNoise`, and name frames instead of one aggregate
`Seq("BoxesRunTime", "java.lang.Integer", "jmh_generated")` matches `boxToInteger` and nothing else on
the only real profile in the repository. The benchmark's own code is `ProtoKernelBench.loop$9` (17.1%),
`run$39` (13.2%), `ask` (11.1%). The report prints **29% where the truth is 70%**, understated in the
direction that flatters the kernel. Its fixture is two authored frames containing no `ProtoKernelBench`
and cannot fail. Fix the constant, name the top contributors, take the fixture from the real capture.

### 5. Run the A/A null in `compare` and `chain`
Its only call sites are inside `BenchBracket`. Re-reading a stored bracket through `compare` reproduces
the verdict and **silently drops the strongest refusal the tool has**. `chain` never checks itself.

### 6. Annotate `resolves` with which term binds it
`Stats.threshold` is `max(t·se, ownError·controlMean)` and both terms are already computed. Seven of
fifteen rows in the replicated sweep are floor-bound, including `handleLoopAnswersInPlace` at −10.7%
with a ±14.4% threshold set **entirely** by own error against a ±8.2% spread term: the row whose
demotion turned five wins into three. `±14.4% (own error)` is fixed by forks; `±12.6% (spread)` by more
legs.

### 7. Fix the `-f N is diagnostic and not a claim` note
Keyed on `variant.forks < 3`, so every replicated bracket in this campaign carries it despite a real
t-threshold at df 3. Five legs at `-f 1` is five independent JVMs; the claim rests on replication, not
forks. Key it on `resolution.df == 0`. **This is a wrong statement the tool volunteers on every
bracket**, the same class as item 4.

*(v2's item 7, capturing method sizes for `crossedBudget`, is* **cut**. *Every Full leg already records
per-method sizes in `InlineSites.bytes` from HotSpot's own `bytes=` attribute, 94% populated on both
jit-bearing runs. `Bytecode.verifyAgainst` exists to check javap against the log, so v2 proposed adding
a capture whose output the module treats as the side needing verification. Budget-crossing becomes a
`Run.jit` computation folded into items 1 and 2, and `Bytecode` is left genuinely unused: its distinct
value is instruction listings, and no question in this campaign needed those.)*

### 8. Orphaned `Investigate.adjudicate`
The report proposes experiments and cannot score them. The one genuine new shape, and the only place a
new entrypoint is justified.

### 9. `allocConservation` must explain itself
**Corrected from v2, which said it is "called in `runLeg`".** It is called nowhere in production; its
only callers are in `QaParsers`. `runLeg` has a *different* guard (collapsed view non-empty, not
conserving). This is a tenth unwired selector, not a rendering omission, and it belongs with 1 and 5.

### 10. `Stats.commonMode`: wire it or delete it
Computed on every replicated comparison, bound to `common`, never read; its only possible consumer
`Stats.residual` has no caller. A session-drift estimate that is computed and discarded is worse than
absent, because a reader of `compareReplicated` will assume drift is accounted for.

### 11. `Ingest` records a warmup it cannot know
`Ingest.scala` writes `warmup = Bench.WarmupIterations` unconditionally. `e6-base-wi25` was measured at
`-wi 25` and is **stored as 10**. Not a missing field but a fabricated one.

## Anti-goals

- **No new subcommands** except item 8. A command is another thing to remember, and forgetting is the
  problem being solved.
- **No new statistics.** The replicate statistic, the A/A null and the resolution floor are validated.
- **No claim the data cannot support.** Narrowed from v2: not "no row→method attribution", which is
  false, but **no attribution of shared-method compilations to a row**, which is the true part.

## Ordering

**Step 0 first** (0a-0c gate items 1-3; 0d-0g are independent one-line corrections).
Then **4, 5, 6, 7, 9, 10, 11**: wiring or correcting existing logic, none needs new capture.
Then **1, 2** (need step 0), then **3** (the renderer), then **8** (the only new entrypoint).

Items 4, 5, 6, 7, 10 and 11 need no prerequisite and can start immediately.
