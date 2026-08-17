# What I verified myself, and two defects neither agent could settle

Two held-out opus agents ran against the harness independently: one attacking `IMPROVEMENT-PLAN.md` v2
(`REVIEW-PLAN-V2.md`), one enumerating every feature and its reachability (`FEATURE-SURVEY.md`). They
used different methods and did not see each other's output. I then re-derived the disputed facts with
my own greps, and ran two empirical checks neither of them could run.

## The table my plan rested on was wrong twice, both times flattering the tool

Plan v2 claimed **three of eight** result-driven selectors were wired. Verified reference counts over
the eleven production sources, where a count of 1 means the definition line and no caller:

| selector | production refs | plan v2 said | truth |
|---|---|---|---|
| `Bench.actionableJit` | 1 (def only) | "yes, 1 call" | **dead: no caller, no test** |
| `LogCompilation.diffVerdicts` | 2 (def + a call inside `efficacy`) | "yes, via `Comparison.jitChanges`" | **transitively unwired** |
| `InlineSites.nearBudget` | 3 | yes | yes, the only true one |
| `budgetCandidates` / `crossedBudget` / `efficacy` / `unprofiledSites` | 1-2 | no | no |

I counted definition lines as call sites. That is the whole error, and it is the third time in this
campaign I have stated a number wrong in the direction that flatters the work.

`diffVerdicts` is worse than unwired. `Comparison.jitChanges` is produced by `Bench.jitShift`, a
**separate implementation** over `Run.jit`, while `diffVerdicts` works over two `Parsed` logs. Two
implementations of the same question, in different modules, over different types, one wired.

The corrected count is **one of eight**, and both agents independently found more the table never
listed: `allocConservation`, `jitUnstable`, `measureDrift`, `residual`, `verifyAgainst`, and the entire
`Bytecode` module. Plus `Stats.commonMode`, which is *called* at `Bench.scala:729` and whose result is
bound to `common` and never read.

Item 9 of plan v2 said `allocConservation` is "called in `runLeg`". It is not called anywhere in
production; its only callers are in `QaParsers.scala`.

## Two things I checked by running, not by reading

### 1. A multi-row leg's compilation log describes only the LAST row

`runLeg` passes `-XX:LogFile=<worktree>/logc-$label.xml`, a fixed path, and JMH forks a fresh JVM per
benchmark. Whether the forks truncate or append was unknown: the reviewer flagged it as gating four
plan items and could not settle it, because **no whole-class Full-evidence run exists** to inspect.

Settled with two JVMs writing one `LogFile`:

    java -XX:+LogCompilation -XX:LogFile=shared.xml -version                      # 7,221 bytes
    java -XX:+LogCompilation -XX:LogFile=shared.xml -XX:+PrintFlagsFinal -version # 94,528 bytes

The result contains **one `<hotspot_log>` header and one pid**, and the surviving content carries
JVM #2's `PrintFlagsFinal` output. The second JVM truncated the first.

So a 15-row selector at `-f 1` stores a `Run.jit` describing **one row, deterministically the last
one**, labelled as the leg's. Not "an arbitrary row": the last. Nothing in the harness says so.

### 2. There is no decodable jit data in the repository at all

47 of the 49 stored runs are `Timing` with no `jit`. The two that carry it are 1-row QA fixtures, and
running the tool on one gives:

    $ scala-cli run . --main-class BenchShow -- --store ../../qa-artifacts/store --id qa-control-...
    ⛔ run qa-control-36b41336fb-1786935756136 is unreadable:
      Failure(kyo.MissingFieldException: Missing required field 'osrTasks'.)

Their `jit` entries are the old `JitEntry` shape (`inlined` boolean) against the current `InlineSites`
(`inlined: Int`, plus required `refused` and `reasons`), and `JitMetrics` has since gained `osrTasks`.
`osrTasks` fails first; both breaks are real.

This is defect 17 recurring on a **changed field shape** rather than an added field. `StoreSchemaTest`
decodes "a run recorded before this field existed" and does not cover an element type that changed.

**Consequence:** plan v2's items 1, 2, 3 and 7 all consume `Run.jit`, and no run in the repository has
any that can be loaded. Four of ten items were written against data nobody can read.

## Item 2 of plan v2 asserted a hard limit that is not one

v2 said a whole-class leg "cannot attribute inlining to one row", called it a hard limit, and made
saying so the fix. The row key is in the log, on the `<task>` element, and **the harness already parses
it** into `Task.method` (`LogCompilation.scala:170`). One `flatMap` at `LogCompilation.scala:272`
discards it:

    p.tasks.filter(_.level >= 4).flatMap(_.inlines)

The reviewer measured the join on a real log: **99 of 1,117 C2 verdicts (8.9%)** were made compiling
the row's own `jmhStub`, which is the OSR compile the harness itself calls "where the measured code
actually runs". The other 91% are shared methods and genuinely belong to no row. So the limit is real
for most of the log and false for the part that matters most.

## Item 7 of plan v2 proposed capturing data already captured

v2 said "no leg records sizes and `crossedBudget` has nothing to compare". Every Full leg already
records per-method sizes in `InlineSites.bytes`, parsed from HotSpot's own `bytes=` attribute, at 94%
population on both jit-bearing runs. `Bytecode.verifyAgainst` exists precisely to check javap's
arithmetic *against* the log, so item 7 proposed adding a capture whose output the module treats as the
side needing verification.

## What the survey found that the review did not

The survey enumerated all 22 `Run` fields against their renderers and found **14 captured-but-never-
rendered**, far past the selector question:

- `Run.alloc` costs **a whole extra JMH invocation** and reaches output only as the integer
  `alloc sites N` in `bench show`. The per-class byte table is never printed.
- `Run.cpu` costs **a second extra JMH invocation**, is collapsed to one percentage, and that
  percentage prints **only when it is ≥ 25%**.
- `Run.coverage`, `Run.treeHash`, `Run.warmup`, `Session.host`, `Session.jvm`, `Row.unit`,
  `AllocByMethod.bytes`, `AllocByMethod.site`, `JitMetrics.plantedTraps`, `Resolution.absolute`,
  `Task.compileId`: no reader in any command.

And four things the tool renders that it cannot capture:

- **`--drift-row` is inert.** `Bench.openSession(worktree, driftRow)` never references the parameter.
- **`driftPercent` is hardcoded `0.0`**, so the "drift measured this session" branch is unreachable and
  every report prints "drift 4.0% assumed, not measured".
- **`QaEndToEnd.scala:26` asserts `driftPercent > 0.0`** and therefore fails by construction.
- **`Delta.flatButUnbounded` can never be true**: both producers always attach a `Resolution` to a
  `Flat` verdict.

## The finding, restated at the right scope

Plan v2's thesis was "result-driven selectors exist and are not wired". That is true and it is a
special case. The general statement, which all three enumerations support:

> **The harness captures a great deal at real cost and renders almost none of it.** Two extra JMH
> invocations per Full leg produce one integer and one conditional percentage. Nine of the fifteen
> selectors that decide what is worth showing have no caller. The single largest output of `bench run`,
> after four JMH invocations, is one line.

That is why every probe in this campaign was written by hand: the data was captured, and the path from
it to an output did not exist.
