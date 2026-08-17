# bench-harness improvement plan (v2)

Supersedes v1 entirely. v1's thesis was "the tool refuses well and volunteers poorly", which a held-out
review showed covers about one defect in eight. This version rests on a different finding, arrived at
by asking why I bypassed the tool six times in one campaign.

## The finding

The harness contains **eight result-driven selectors**: functions whose job is to decide *what is worth
showing given what the results were*. That is exactly the mechanism the tool needs, and it was built.

| selector | selects on | wired into production? |
|---|---|---|
| `LogCompilation.diffVerdicts` | verdict changed between legs | yes, via `Comparison.jitChanges` |
| `Bench.actionableJit` | refusal worth chasing vs inherent | yes, 1 call |
| `InlineSites.nearBudget` | method sits close to a budget | yes, 1 call, inside `Investigate` |
| **`LogCompilation.budgetCandidates`** | refusals ranked by site count | **no** |
| **`Bytecode.Change.crossedBudget`** | size change flipped an inlining budget | **no** |
| **`Bytecode.diff` / `Bytecode.of`** | per-method sizes between two trees | **no** |
| **`LogCompilation.efficacy`** | did the instructed flag actually take | **no** |
| **`LogCompilation.unprofiledSites`** | sites with no receiver data | **no** |

**Three wired, five not.** Every one of the five has tests. `Bytecode` is an entire tested module with
zero production call sites. `unprofiledSites` has no caller anywhere but its own definition.

Every throwaway probe written during this campaign called one of the five. That is the whole
explanation, and it is not a discipline failure: **the logic was on no code path, so no amount of
remembering would have surfaced it.** Writing a probe was the only way to reach it.

## The rule this yields

> **A flag must carry its own evidence.** If the report says a row is unexplained, that paragraph
> contains the evidence it is standing on. If it proposes an experiment, it can score that experiment.
> If a guard fails a leg, the failure names what did not reconcile. **No output may instruct the
> operator to go and look something up.**

The tool violates this in its own text today:

```
⚠️  Moved with nothing in the evidence behind it, so the cause is not known yet:
  - continuationBodiesFuse: check allocation sites and the inlining log before proposing a mechanism
```

It holds `alloc`, `allocByMethod`, `jit` (944 entries), `cpu` and `deopts` for both legs, and responds
to "I cannot explain this" by assigning homework.

**Acceptance test for the whole plan:** grep the renderers for imperatives — *check, look at, verify,
see the, diagnose*. Each surviving one is a place the tool knows something and delegated it back.

## The changes

### 1. Wire `efficacy` into the comparison report
The highest-value item. It is the guard that stopped DIS-2 being credited with a −17.4% win the flag
never caused, and it fires today only if someone writes a probe. When two legs differ in `jvmArgs`,
the report must state whether the instructed method's verdict actually moved, and whether anything
else moved with it. **Acceptance:** the stored DIS-2 pair reports that `dispatch$1` is refused in both
logs, without a probe.

### 2. Give the unexplained-row flag its evidence
Replace the homework line with what exists: the row's own allocation delta (per-row, real), the leg's
actionable refusals and near-budget methods, **explicitly labelled leg-wide**, and then the honest
statement that a whole-class leg cannot attribute inlining to one row. **This is a hard limit, not an
omission:** fifteen rows produce one compilation log and the model has no row→method mapping. Saying
so is the fix; inventing the join would be the tool asserting what it cannot know.

### 3. `Report.renderRun`, used by `run` and `show`
`run` spends four JMH invocations building a 22-field `Run` and prints one line. `show` renders 944 jit
entries as the number 944. One renderer, two call sites, gives homes to `budgetCandidates`,
`unprofiledSites`, the per-leg JIT cost table, allocation attribution, and `coverage`.
**Open question for the reviewer:** a bracket calls `runLeg` five times, so a full per-leg render would
emit five reports before the comparison. Suppress under `bracket`, or print a digest?

### 4. Fix `KnownNoise`, and name frames instead of one aggregate
`Seq("BoxesRunTime", "java.lang.Integer", "jmh_generated")` matches `boxToInteger` and nothing else on
the only real profile in the repository. The benchmark's own generated code is `ProtoKernelBench.loop$9`
(17.1%), `run$39` (13.2%), `ask` (11.1%). So the report prints **29% where the truth is 70%**,
understated in the direction that flatters the kernel. Its fixture is two authored frames with no
`ProtoKernelBench` in them, so it cannot fail. Fix the constant, name the top contributors, and take
the fixture from `qa-artifacts/qa-cpu.txt` rather than authoring it.

### 5. Run the A/A null in `compare` and `chain`
Its only call sites are `Cli.scala:210/215`, inside `BenchBracket`. Re-reading a stored bracket through
`compare` reproduces the verdict and **silently drops the strongest refusal the tool has**. `chain`
never checks itself on any step.

### 6. Annotate `resolves` with which term binds it
`Stats.threshold` is `max(t·se, ownError·controlMean)` and both terms are already computed. Seven of
fifteen rows in the replicated sweep are floor-bound, including `handleLoopAnswersInPlace` at −10.7%
with a ±14.4% threshold set **entirely** by the own-error floor against a ±8.2% spread term — the row
whose demotion turned five wins into three. `±14.4% (own error)` is fixed by forks; `±12.6% (spread)`
by more legs. `Plan.scala` currently tells the operator, unconditionally, that forks do not help.

### 7. Capture method sizes in `runLeg`, so `crossedBudget` has two sides
The only item needing new capture. `Bytecode.of` reads compiled classes at analysis time and nothing
calls it, so no leg records sizes and `crossedBudget` has nothing to compare. Capture **sizes for a
named method set**, not instruction listings: every use in this campaign was "how big is this method".
Guard on the classes matching the leg's sha, and record absence rather than guessing.

### 8. Orphaned `Investigate.adjudicate`
The report proposes experiments and cannot score them. This is the one genuine new shape (baseline,
isolation, target) and the only place I think a new entrypoint is justified.

### 9. `allocConservation` must explain itself
Called in `runLeg`, can fail a leg, and its result never reaches any output.

### 10. `Ingest` records a warmup it cannot know
`Ingest.scala` writes `warmup = Bench.WarmupIterations` unconditionally. `e6-base-wi25` was measured at
`-wi 25` and is **stored as 10**. Not a missing field but a fabricated one.

## Anti-goals

- **No new subcommands** except item 8. A command is another thing to remember, and forgetting is the
  problem being solved.
- **No new statistics.** The replicate statistic, the A/A null and the resolution floor are validated.
- **No claim the data cannot support**, specifically no row→method attribution from a whole-class leg.

## Ordering

1, 2, 4, 5, 6 first: all are wiring or correcting existing logic, none needs new capture, and each
closes a case where the tool held the answer. Then 3 (the renderer), 9, 10. Then 7 (capture) and 8
(new entrypoint), which are the only two that add rather than connect.
