# Bench-harness feature and reachability inventory

Delivered by a held-out opus agent briefed as an **inventory, not a critique**, with no
recommendations, because the "unwired selectors" count needed establishing independently rather than by
my own grep. Reproduced here as delivered.

Paths are relative to `kyo-kernel2/.claude/skills/kernel/bench-harness/`. Build (`project.scala:1-4`):
scala-cli, Scala 3.8.4, kyo-core / kyo-schema-json / kyo-case-app `1.0.0-RC6`. **No test framework;
every "test" is its own `main`.**

## Entrypoints

13 objects extend `KyoCaseApp`/`KyoApp`; four more are plain `def main`. All 8 `KyoCaseApp` bodies are
wrapped in `Cli.guard` (`Cli.scala:132-146`): an expected failure prints `⛔ <reason>` and exits 1; a
panic prints a stack trace and exits 2.

| command | key options | writes | output |
|---|---|---|---|
| **`BenchRun`** `Cli:156` | `--worktree --label --sha --row --forks(3) --evidence(full) --store` | `<store>/runs/<id>.json`, plus `bench-$label.json`, `alloc-$label/`, `logc-$label.xml` | **one line**: `stored <id> (<n> rows, <ev>) at <file>` |
| **`BenchBracket`** `Cli:190` | `--control --variant --control-jvm --variant-jvm --legs(5)` | one run per leg | A/A null line **or** blocker banner; then `Report.render` in full |
| **`BenchIngest`** `Cli:236` | `--json --label --sha --declared-rows(15)` | one run per json | one line per file, plus a fixed timing-only paragraph |
| **`BenchPlan`** `Cli:277` | `--from --legs(5) --target(5.0)` | nothing | forecast header, one line per row, then either "every row can resolve" or a warning list |
| **`BenchChain`** `Cli:309` | `--sha (>=3) --legs(2) --evidence(timing)` | one run per leg | chain header, then per adjacent pair a separator and `Report.render` |
| **`BenchCompare`** `Cli:351` | `--control --variant (repeatable) --store` | nothing | replicated or single-pair preamble, then `Report.render` |
| **`BenchShow`** `Cli:388` | `--id --store` | nothing | **exactly 5 lines**, ending `rows <n>, jit entries <n>, alloc sites <n>, cpu sites <n>` |
| **`BenchList`** `Cli:404` | `--store` | nothing | one line per run sorted by `recordedAt` |

Test/QA mains: `BenchKnownAnswerTest`, `LogCompilationTest` (runs `oracles.sh` as a subprocess for
ground truth), `BytecodeTest`, `PlanTest`, `QaGuards` (writes then reverts a real source file),
`QaEndToEnd`, plus plain-main `BenchTest`, `InvestigateTest`, `StatsTest`, `QaParsers`.

## Reachability classification

Roots are the 8 `KyoCaseApp` bodies. `PRODUCTION` = reachable from one.

**DEAD** (no call site anywhere): `Bench.actionableJit` (`:32`), `Bench.measureDrift` (`:171`),
`LogCompilation.unprofiledSites` (`:375`), `Bytecode.verifyAgainst` (`:149`), `Ingest.Failed` (`:24`).

**TEST-ONLY**: `Bench.allocConservation` (callers `QaParsers` only), `Bench.jitUnstable` (sole caller
`BenchTest:91`), `Stats.residual`, `LogCompilation.diffVerdicts` / `efficacy` / `budgetCandidates`,
**the entire `Bytecode` module** (`of`, `parse`, `diff`, `Method.*`, `Change.*`), and
`Investigate.adjudicate` / `efficacy` / `quantity` / `Quantity.*` / `Outcome.*` / `Hypothesis.target`.

**PRODUCTION-CALLED, RESULT DISCARDED**: `Stats.commonMode` (`Bench:729`), and its callee
`Stats.median`. The binding `common` is never read in the remaining lines of `compareReplicated`.

**PRODUCTION but structurally unreachable-true**: `Model.Delta.flatButUnbounded` (`:368`), called at
`Store:263`. Both producers always attach a `Resolution` to a `Flat` verdict (`Bench:587`;
`Stats.classify:168-172`), so it can never be true.

`Investigate` proposes falsifiers in every report, but the half that **adjudicates** a falsifier's
result has no command.

## The 22 `Run` fields against what renders them

Captured by `runLeg` (`Bench:493-523`) or `Ingest.run` (`Ingest:43-66`).

Rendered: `id`, `label`, `sha`, `forks`, `evidence`, `wholeClass`, `declaredRows`, `markers`, `rows`,
`jit_metrics` (9 of its 10 fields), `jit` (via `jitShift` and `Investigate`), `jvmArgs` (bracket only),
`allocByMethod` (`cls`, `method`, `samples` only), `cpu` (as one percentage, only at ≥25%), `deopts`,
`morphism`, `recordedAt` (`bench list` only), `session` (only `driftPercent` and `id`).

**Captured but never rendered:**

1. **`Run.coverage`** (`Model:307`, written `Bench:507`) — zero readers.
2. **`Run.treeHash`** (`:291`, written `Bench:496`) — no reader; the invalidation check runs on locals.
3. **`Run.warmup`** (`:299`) — read only by `QaEndToEnd:40`.
4. **`Session.host` and `Session.jvm`** (captured `Bench:341-342`) — never rendered.
5. **`JitMetrics.plantedTraps`** — excluded from the JIT table by design, printed nowhere else.
6. **`Run.alloc`** — **a full extra JMH invocation** (`Bench:469`); its per-class byte table reaches
   output only as the integer `alloc sites N` in `bench show`.
7. **`Run.cpu`** — **a second extra JMH invocation** (`Bench:485`), collapsed to one percentage that
   prints only above 25%.
8. **`AllocByMethod.bytes`** — the apportioned estimate `apportion` exists to compute.
9. **`AllocByMethod.site`** — the caller frame `parseCollapsed` walks out to find; `Store:370-371` keys
   on `(cls, method)` and drops it.
10. **`Row.unit`** (`Model:22`, parsed `Bench:230`) — the delta table has no unit column.
11. **`Resolution.absolute`** (`:345`) — read only by `Stats:170`.
12. **`LogCompilation.Task.compileId`** (`:82`) — never read.
13. **`Run.jvmArgs` on the `run` path** — `runLeg` accepts it but `RunOpts` exposes no flag.

## Rendered but not capturable

1. **`drift X% measured this session`** (`Store:232`) requires `driftPercent > 0`.
   `Bench.openSession:348` hardcodes `0.0` and `Cli:238` hardcodes `0.0`, and `measureDrift` is dead.
   **Every report renders the other branch**, `drift 4.0% assumed, not measured`.
2. **`⚠️ N flat rows carry no resolution`** (`Store:276-278`) — reachable only when every delta is
   `BelowResolution`, and its `flatButUnbounded` half is structurally unreachable.
3. **`--drift-row`** (`Cli:17`) — `Bench.openSession(worktree, driftRow)` **never references the
   parameter**. The flag and both `getOrElse("suspensionBaseline")` fallbacks are inert.
4. **`bench show`'s counts** are the only surface for `Run.jit`, `Run.alloc` and `Run.cpu`: counts of
   data the report never displays.

## `Report.render` sections, in emission order

Assembled at `Store.scala:447`. 19 sections: `blockerBanner`, `sessionWarning`, `header`, `body`,
`rampNote`, `resolutionNote`, `jit`, `deoptShift`, `polymorphic`, `allocSites`, `allocNote`,
`partition`, `verdictLine`, `ladder`, `steadyState`, `jitTable`, `bothWays`, `noiseNote`,
`investigation`. Conditions worth noting: `jitTable` needs both legs `Full`; `noiseNote` fires only at
`noiseShare >= 25.0`; `allocSites` needs a sample count to move by more than a quarter of the larger
side; `polymorphic` takes 3; `ladder` needs `Delta.unexplained`.

## Additional observations

- `QaEndToEnd.scala:26` asserts `session.driftPercent > 0.0`; `openSession` always returns `0.0`, so
  that check **fails by construction**.
- `Model.scala:295-298` carries two consecutive scaladoc comments before `warmup`; the first describes
  a field that no longer exists.
- `Roots.classes` falls back to a hardcoded absolute path naming a specific worktree and Scala version,
  which the file's own scaladoc argues against.
- `QaGuards.scala:22-23` and `QaEndToEnd.scala:11-15` hardcode absolute machine-specific paths.
- `QaGuards.scala:42-47` writes into a real source file and restores it at `:51`; an interrupted run
  leaves the edit behind.
- `Bench.chain` revalidates `requireChain` although `BenchChain` already validated it.
- `IngestOpts.declaredRows` defaults to `15`, a constant that must track `ProtoKernelBench`'s
  `@Benchmark` count, which `Bench.declaredRows` reads from source on the measured path but not on the
  ingest path.
