# Held-out review of `IMPROVEMENT-PLAN.md` v2

Delivered by a held-out opus agent briefed to attack the plan, check its cited numbers rather than
accept them, and specifically try to break the claim that inlining cannot be attributed to a row from
a whole-class leg. Reproduced here as delivered.

**Note on two of its figures, added after re-derivation** (see `ORACLES.md`): its OSR attribution
figure of "99 of 1,117 C2 verdicts (8.9%)" did **not** reproduce; splitting on `<task ` boundaries
gives 141 of 6,329, or 2.2%. Its `KnownNoise` figure of "70%" was also wrong, in the same direction as
mine; the true non-kernel share is 83.97%. Its `InlineSites.bytes` 94% figure reproduced exactly. The
mechanisms it identifies all hold; two of its denominators do not.

---

## 1. The thesis is correct, and understated by about a factor of two

Verified by enumerating every `def` in the eleven production sources and counting references in
production versus test files, hand-checking every ambiguous case. The count includes the definition
line, so `prod=1` means zero callers.

Two rows of the plan's table are wrong, both claiming "wired" where nothing is:

- **`Bench.actionableJit`**, listed "yes, 1 call". It has **zero callers and zero tests**. Repo-wide it
  appears at its own definition (`Bench.scala:32`) and in the plan's table. It is the only selector
  with neither a call site nor a test, and it carries a 6-line docstring documenting a reason
  vocabulary "verified against a capture".
- **`LogCompilation.diffVerdicts`**, listed "yes, via `Comparison.jitChanges`". It is transitively
  unwired: its only production reference besides the definition is `LogCompilation.scala:362`, inside
  `efficacy`, which the plan's own table marks unwired. `Comparison.jitChanges` is produced by
  `Bench.jitShift` (`Bench.scala:590`, `:757`), a **separate implementation** over `Run.jit` rather
  than over two `Parsed` logs. So this is worse than unwired: **duplicated logic, one copy wired and
  one not**, in different modules over different types.

So the eight rows are **one wired, seven not**, not three and five.

Six more unwired result-driven selectors the table misses:

| selector | production references | status |
|---|---|---|
| `Bench.allocConservation` | `Bench.scala:324` (def), `:331` (`end`) | zero callers |
| `Bench.jitUnstable` | `Bench.scala:804` (def) | zero callers |
| `Bench.measureDrift` | `Bench.scala:171` (def), `:185` (`end`) | zero callers |
| `Stats.residual` | `Stats.scala:191` (def) | zero callers |
| `Bytecode.verifyAgainst` | `Bytecode.scala:149` (def) | zero callers |
| `ParseCoverage.show` / `Run.coverage` | captured `Bench.scala:507` | never rendered |

**Item 9 is factually wrong.** It says `allocConservation` is "called in `runLeg`, can fail a leg". It
is not called anywhere. What `runLeg` has at `Bench.scala:478-484` is a *different* guard
(`evidence == Full && allocSites.nonEmpty && byMethod.isEmpty`), which checks the collapsed view is
non-empty, not that it conserves.

**One wired-but-dead case.** `Stats.commonMode` *is* called at `Bench.scala:729`,
`val common = Stats.commonMode(reps)`, and `common` is referenced nowhere in the remaining 29 lines of
`compareReplicated`. Its only possible consumer, `Stats.residual`, has zero callers. Every replicated
comparison computes the session's common-mode drift and discards it.

**`Run.coverage`.** `Model.scala:162-166` says carrying the parse fraction "turns a silent partial
parse into a visible one". It does not: `coverage` is stored on every Full leg and read by no renderer.
A parser that reads 637 of 5,093 elements looks identical to one that read all of them.

**Unverifiable:** the causal claim that "every throwaway probe called one of the five". The probes are
gone. The structural claim stands on its own and should replace it.

## 2. Item 2's central claim is false, and here is the join

The compilation log names the benchmark row on the `<task>` element:

    <task compile_id='1040' compile_kind='osr'
      method='kyo.kernel.bench.jmh_generated.ProtoKernelBench_continuationBodiesFuse_jmhTest
              continuationBodiesFuse_avgt_jmhStub (...)'

**The harness already parses it.** `LogCompilation.scala:170` reads that attribute into `Task.method`,
and `Task.inlines` holds the decisions made while compiling that task. The row key is in `Parsed` today.

**One `flatMap` throws it away.** `LogCompilation.inlining` (`:270-285`) does
`p.tasks.filter(_.level >= 4).flatMap(_.inlines)`, discarding `Task.method`.

Splitting C2 inline verdicts by the root method of the task they were made in gave 782 `kyo.`-rooted,
236 JMH/JDK-rooted, and 99 in the row's own `jmhStub`, of 1,117. *(This denominator did not reproduce
on re-derivation; see the note at the top. The mechanism did.)*

The stub compiles are what the harness itself singles out: `LogCompilation.scala:12` calls OSR
compilations "where a benchmark's loop body actually lives", and `Model.scala:231-233` says folding
them into `tasks` "hides the compilation that matters most".

So item 2 should say *these N decisions belong to this row; these M belong to no row*, and the anti-goal
should narrow to "no attribution of shared-method compilations to a row".

### A prerequisite gating items 1, 2, 3 and 7

`runLeg`'s `compilationLog` passes `-XX:LogFile=` a **fixed path** (`Bench.scala:445`). JMH forks a
fresh JVM per benchmark, so a 15-row selector at `-f 1` is fifteen JVMs opening the same path. If each
truncates, a whole-class leg's `Run.jit` describes **one arbitrary row**.

**Could not settle empirically: no whole-class Full-evidence run exists.** All 45 campaign runs are
`Timing` with `jit = 0`; the only two Full runs are 1-row QA fixtures, and the exp1 log contains
exactly one benchmark. Checkable for the cost of one two-row invocation, and it should be checked
before items 1, 2, 3 or 7 are written.

## 3. Item 7: cut it, the capture already exists

The plan says "no leg records sizes and `crossedBudget` has nothing to compare". Every Full leg already
records per-method sizes in `InlineSites.bytes` (`Model.scala:119`), parsed from HotSpot's `bytes=`
attribute at `LogCompilation.scala:235`, stored at `Bench.scala:506`.

**887 of 944 entries (94%) on the control leg and 904 of 958 (94%) on the variant carry a non-zero byte
count**, with 851 methods present in both. Budget crossing is computable from
`control.jitFor(m).bytes` against `variant.jitFor(m).bytes` with nothing new.

HotSpot's declared size is also the more trustworthy source: `Bytecode.verifyAgainst`
(`Bytecode.scala:144-148`) exists to check javap's arithmetic against the log. Item 7 proposes a
capture whose output the module itself treats as the side needing verification.

Cut item 7; fold budget crossing into items 1/2. `Bytecode`'s distinct value is instruction listings,
and no question in this campaign needed those.

## 4. Item 1's acceptance cannot be run

**There is no stored DIS-2 pair.** `bench-results/dis2/` contains one file, `RESULT.md`. The DIS-2
evidence is raw XML under `exp1/` and `exp2/`. Nine store directories exist and none is a DIS-2 store.
And `Ingest.run` (`Ingest.scala:43-66`) hardcodes `evidence = Timing`, `jit = Chunk.empty`, so the
ingest path **cannot produce a run carrying a compilation log at all**.

**The only two runs with jit data are in the obsolete schema.** Their entries are
`{"method","bytes","inlined":true,"reason":"inline"}`, which is `JitEntry` (`Model.scala:111`), not the
current `InlineSites` (`Model.scala:119`: `inlined: Int, refused: Int, reasons: Chunk[String]`, none
defaulted). A boolean cannot decode into `Int` and two required fields are absent.

This is **defect 17 recurring on a changed field shape rather than an added field**, and it is unfiled.
`StoreSchemaTest` decodes "a run recorded before this field existed"; neither covers a changed element
type. **Items 1, 2, 3 and 7 all consume `Run.jit`, and no decodable run in the repository has any.**

## 5. The acceptance test is prose motion

Running the plan's grep against the renderers gives six hits, **one a true positive**:

| hit | verdict |
|---|---|
| `Cli.scala:224` "see the banner above" | false positive, the banner is in the same output |
| `Store.scala:143` "this session cannot check itself" | false positive, description |
| `Store.scala:315` "until this is diagnosed" | borderline, followed by a table the report prints |
| `Store.scala:406` "two diagnoses, not one tradeoff" | false positive, a noun |
| `Store.scala:419` "until each is diagnosed or ruled on" | false positive, a state description |
| **`Store.scala:430` "check allocation sites and the inlining log"** | **true positive** |

83% false positives, and the one true positive is satisfiable by rewording. Its recall is worse:
`bench show` printing `944` is the plan's own headline example and contains no imperative, so the grep
passes it. **Unsound and incomplete on its own target.**

Two better tests:

**(a) An inventory gate.** Assert every public selector has at least one production call site reachable
from a `Cli` entrypoint, with an explicit allowlist. This is the check that would have found the entire
v2 finding, and it fails loudly rather than being reworded around. The harness already runs source-level
QA mains, so it has a home.

**(b) A per-section evidence assertion**, driven by a real stored leg. For each conditional section,
assert the emitted text contains at least one datum from the record it describes: when `unexplained` is
non-empty, the output must contain that row's `allocDelta` and a method name from `variant.jit`.

## 6. Item 3's open question: neither, split it

- `run` and `show`: full render.
- `bracket`: a one-line digest per leg carrying only what is per-leg and otherwise unrecoverable
  (`coverage` completeness, jit entry count, whether every guard passed), plus the full render **only
  for a leg that failed a guard**.

## 7. Ordering and what is missing

1. **Insert a prerequisite step 0**: settle the `-XX:LogFile` per-fork question and migrate the
   `JitEntry`-schema runs.
2. **Move item 9 into the 1/5 group.** It is a seventh unwired selector, not a rendering omission.
3. **Cut item 7.**

Three items from the earlier review are not covered by v2 and two were dropped wrongly:

- **The `-f N is diagnostic` note** (`Store.scala:229`, keyed on `variant.forks < 3`). Every replicated
  bracket in this campaign ran at `-f 1` with a real t-threshold at df 3 and carries this caveat. Five
  legs at `-f 1` are five independent JVMs; the claim rests on replication. Key it on
  `resolution.df == 0`. **A wrong statement the tool volunteers on every bracket**, the same class as
  `KnownNoise`, which the plan kept.
- **`budgetCandidates` has no CLI surface** and no numbered item wires it; if item 3 covers it, say so
  in its acceptance.
- **`commonMode` computed and discarded / `residual` unwired.** Either wire or delete both.

**Summary.** Keep the thesis, fix the table: **1 of 8 wired**, plus 6 more unwired, plus 1
computed-and-discarded, plus 1 captured-and-never-rendered. Item 2's hard-limit claim is false. Item 7
should be cut. Item 1's acceptance is unrunnable. The acceptance grep should be replaced by an
inventory gate.
