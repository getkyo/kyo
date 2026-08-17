# kyo-test observability: three findings

Scope: the runner/reporter layer under `kyo-test/runner` and `kyo-test/api`. All three
concerns you raised check out against the code. Here is what each one actually is, with
citations, plus a proposed direction. No code changed yet.

## How the runner is wired (the shared fact behind all three)

sbt's model is: one `Runner` per invocation, one `Task` per suite, one `done()` at the end.
kyo-test maps that as:

- `SbtRunner.tasks` creates one `SbtTask` per suite; each `SbtTask.execute` calls
  `TestRunner.runToFuture` for a **single** suite (`SbtTask.scala:43-46`).
- `TestRunner.runReport` therefore handles **one suite**. It fires `onRunStart`,
  `onSuiteStart`, `onSuiteComplete`, and `onRunComplete` all inside that per-suite call,
  with `report = TestReport(Chunk(sr))` holding exactly one `SuiteReport`
  (`TestRunner.scala:227-229`, and `suiteCount = 1` at `:110`).
- The only true run-level aggregate is `SbtRunner.done()` -> `Summary.render(results, ...)`,
  which folds every accumulated suite report (`SbtRunner.scala:93-101`). JS and Native do the
  same (`JsRunner.scala:86-90`, `NativeRunner.scala:75-81`).

## Finding 1 (confirmed): no wall-clock timestamps anywhere

Every reporter emits **durations**, never absolute timestamps. `ConsoleReporter` prints
`[PASS] name (123ms)`, `--- Suite: X passed ... (dur)`, and `Results: ... (total: dur)`
(`ConsoleReporter.scala:108, 189-195, 266-273`). The only `currentTimeMillis` call is the
randomize seed (`Args.scala:137`); everything else is `nanoTime()` deltas for durations.

Consequence: `scripts/ci-analyze.py` (on branch `leak-check-quiescence`) cannot get absolute
time from kyo-test's own output. It reconstructs the timeline by interleaving
`ci-monitor.sh`'s `[ci-mon HH:MM:SS]` samples and sbt's `[success] Total time` lines. A
per-line/per-event timestamp would let build-time analysis attribute wall clock directly
instead of inferring it.

Proposed direction: an opt-in timestamp prefix on `ConsoleReporter` lines (env/flag, e.g.
`--timestamps` or `KYO_TEST_TIMESTAMPS=1`), off by default so normal output is unchanged.
Emit an ISO-8601 or `HH:mm:ss.SSS` prefix at least on `onSuiteStart`/`onSuiteComplete` (and
optionally per-leaf).

## Finding 2 (confirmed): no report generated out of the box

`JUnitXmlReporter` (JVM, `TEST-<suite>.xml` per suite) and `TapReporter` exist, but both are
**opt-in only**: nothing produces them unless `--reporter=junit-xml:PATH` / `--reporter=tap`
is passed (`Args.scala:157-211`). There is no env/property that turns any report on by
default (grep confirms: only `--reporter=` wires reporters). Out of the box you get the
console lines plus the one `Summary` line.

Proposed direction: pick the out-of-the-box artifact. Options: (a) default JUnit XML to a
conventional dir (e.g. `target/test-reports`) unless disabled; (b) a machine-readable timing
report (JSON: per-suite/per-leaf durations + start/end) aimed squarely at build-time
analysis, which JUnit XML only half-covers. (b) pairs naturally with Finding 1.

## Finding 3 (needs a 1-run reproduction to confirm the exact symptom)

The **aggregate** the user reads as "wrong" comes from the run-level reporter callbacks being
driven per-suite. `ConsoleReporter.onRunComplete` prints:

    Results: X passed, Y failed  (total: <dur>)

and `onRunStart` prints `Running 1 suite(s)`. Because `runReport` is per-suite, **both lines
are emitted once per suite**, each showing only that one suite's numbers, while reading like a
whole-run total (`ConsoleReporter.scala:88-89, 242-273`). The genuine run aggregate is only
the terminal `Summary` line `kyo-test: N tests, ...` from `done()` (`Summary.scala:44-51`).

So the mechanism is clear: there is no reporter callback that fires once with the whole-run
tally; the aggregate lives only in the `Summary` string. What I have NOT yet done is watch a
real multi-suite run to see which symptom you hit:

- (a) the per-suite `Results:` line masquerading as a run total (most likely), or
- (b) a genuinely wrong number in the `Summary` line, or
- (c) sbt's own `Passed: Total N` line (driven by the per-leaf `Event`s in
  `EventBuilder.scala`) disagreeing.

Per the reproduce-before-you-fix rule I want to capture a real run's tail first, then fix.

Proposed direction (pending repro): move the run-level aggregate to the `done()` boundary
where `Summary` already computes it correctly, and stop `ConsoleReporter` from printing a
per-suite line labeled as a run total (either demote it to a clearly suite-scoped line, since
`onSuiteComplete` already prints `--- Suite: ...`, or introduce a real once-per-run aggregate
callback invoked from each platform's `done()`). Add a reporter test that pins the aggregate
across multiple suites so it cannot regress.

## Finding 4: how forking and the sequential/parallelism config interact

There are TWO independent axes, one at the build layer and one at the runner layer. They
compose; neither is redundant, and picking the wrong knob silently fails to protect a resource.

### Build layer (sbt) decides the JVM topology

- `kyo-settings` sets `fork := true` (`build.sbt:131`), so JVM modules run in forked JVMs. The
  kyo-test sbt plugin sets NO parallelism defaults (`KyoTestPlugin.scala:35-37`); everything is
  build.sbt.
- **Default topology: one fork per module, suites sequential inside it.** sbt's default
  `testGrouping` puts all of a project's suites in a single `SubProcess` group, and
  `testForkedParallel` defaults to false (the base settings do not set it), so the module's
  suites run one at a time in that one JVM.
- **Overrides exist for specific modules:**
  - per-suite forking (each suite its own JVM) via `testGrouping`: browser/ui modules
    (`build.sbt:2655-2665`, `:2781`) and an aeron-style block (`:2914`);
  - multiple suites concurrent in one fork via `Test / testForkedParallel := true`: kyo-sql
    (`:2562`) and `:2912`.
- **Concurrency caps** (`build.sbt:103-118`): `Tags.limitAll(SBT_TASK_LIMIT)` serializes ALL
  tasks (CLAUDE.md notes CI uses `SBT_TASK_LIMIT=1`); `Tags.limit(Tags.Test, testLimit)` caps
  concurrent test tasks (CI cores/2); `Tags.limit(Tags.ForkedTestGroup, forkLimit)` caps
  concurrent forked test JVMs (CI hard-cap 2). Concurrent forks = the min of these.
- **JS/Native/Wasm don't fork a JVM** (`fork := false` in `native-settings-base:3059`,
  `js-settings:3108`, `wasm-settings:3130`); tests run in the Node / native-binary process, with
  `parallelExecution := false` (JS/Wasm) and the Native runner pinned to `globalK = 1`.

### Runner layer (RunConfig + LeafPool) decides in-JVM concurrency

- **`LeafPool.global` is a companion `val`, so it is one instance per JVM process = per fork**
  (`LeafPool.scala:129-132`). `globalK = max(1, Async.defaultConcurrency)` on JVM, `1` on Native
  or under leak-debug (`:125-127`). Every suite in that JVM routes its leaves through this one
  pool, so total concurrent leaves in a fork is bounded at `globalK` no matter how many suites.
- **`RunConfig.parallelism`** orders only a suite's OWN leaves: `1` = push-await-each (serial,
  `TestRunner.scala:182-185`); `0`/`N>1` = push all, bounded by `globalK` (`:186-192`). It is no
  longer a per-suite cap (`RunConfig.scala:109-112`).
- **`globallySequential`** takes a process-wide `Meter` mutex for the whole leaf body
  (`LeafPool.scala:144-167`), serializing leaves across every globally-sequential suite IN THE
  SAME JVM.

### The interactions (the actual point)

1. **`globalK` is per-fork, so machine-wide concurrent leaves = `globalK` x (concurrent forks).**
   The runner bounds concurrency WITHIN a JVM; the build (`ForkedTestGroup`/`Test`/`limitAll`)
   bounds it ACROSS JVMs. Neither alone bounds the machine; they multiply. On the constrained CI
   (`SBT_TASK_LIMIT=1`) it collapses to one fork at a time running `globalK` leaves.
2. **`globallySequential` only has teeth when multiple suites share one JVM** (`testForkedParallel
   := true`, or the `fork := false` in-sbt-JVM path). Under the default (one fork/module, suites
   sequential) or per-suite forking, suites never overlap in a JVM, so `globallySequential`
   collapses to `sequential`. The RunConfig scaladoc says exactly this (`RunConfig.scala:114-121`).
3. **A resource shared ACROSS suites is protected by a different knob depending on topology:**
   same-JVM concurrent suites -> `globallySequential` (runner gate); separate forks -> build-level
   `parallelExecution := false` + the `ForkedTestGroup` cap (this is exactly what the browser
   modules do to serialize Chrome, `build.sbt:2655-2664`). The runner gate cannot span JVMs; the
   build cap cannot serialize within a JVM. Choosing the wrong one silently fails to protect.
4. **The end-of-run leak check is per-fork:** it runs once at `done()` over that fork's
   accumulated suites, forked-only (`SbtRunner.scala:66-68, 112-113`). One-fork-per-module -> one
   aggregated check across the module's suites; per-suite forking -> a check per suite;
   `fork := false` (JS/Native, or an unforked JVM module) -> no check at all.

### This is also the deeper form of Finding 3

`Summary.render` is rendered per `Runner`, and there is one `Runner` per fork
(`SbtRunner.done():93-101`, mirrored on JS/Native). So the kyo-test aggregate is **per-fork**, not
per-run. A multi-module `testKyo` run, or any per-suite-forked / grouped module, produces one
`Summary` line per fork, none of which is the whole-run total; the only cross-fork total is sbt's
own, tallied from the per-leaf `Event`s (`EventBuilder.scala`). So "no correct aggregate count"
is structural: kyo-test has no cross-fork total by construction, because each fork is its own
isolated runner with its own `results` queue and its own `done()`.

## Finding 5: full inventory of the parallelism configs in build.sbt

Four layers, from machine-wide down to one JVM.

### A. Global scheduler caps: `Global / concurrentRestrictions` (`build.sbt:92-119`)

Set with a wholesale `:=` that REPLACES sbt's defaults, because sbt resolves multiple
`Tags.limit` on one tag by taking the most-restrictive, and sbt's default
`Tags.limit(Tags.ForkedTestGroup, 1)` would otherwise shadow kyo's larger `forkLimit`
(`:88-91`). Externally-contributed `Global / concurrentRestrictions ++=` (the Scala.js linker
lock the plugin adds) still appends and composes. The caps, all env-tunable:

- `Tags.limitAll(SBT_TASK_LIMIT or cores)` (`:104`): cap on ALL concurrent tasks of any kind.
  CI sets `SBT_TASK_LIMIT=1` (per CLAUDE.md) -> the whole build is one task at a time.
- `Tags.limit(Tags.Update, SBT_UPDATE_LIMIT or 1)` (`:105`): dependency-resolution concurrency;
  default 1 (serialized, for Windows file-lock avoidance).
- `Tags.limit(Tags.Test, testLimit)` (`:106`): concurrent test TASKS. `testLimit` = CI `cores/2`,
  local `ceil(cores*0.8)`, min 1 (`:97`).
- `Tags.limit(Tags.ForkedTestGroup, forkLimit)` (`:107`): concurrent forked test JVMs.
  `forkLimit` = CI hard-cap 2, local `max(1, cores/2)` (`:102`). CI=2 is chosen so kyo-sql's
  podman+docker split lands one fork per daemon.
- `Tags.limit(DoctestTag, 2)` (`:114`): concurrent doctest forks (tag owned by KyoDoctestPlugin;
  restated here because the wholesale `:=` drops the plugin's own `+=`).
- `Tags.limit(DocTag, 1)` (`:117`, tag defined `:78`, applied `:196` via `.tag(DocTag)`):
  scaladoc forks serialized (each is a whole-module TASTy JVM).

Effective concurrent forks = `min(limitAll, Test, ForkedTestGroup)`. On CI with
`SBT_TASK_LIMIT=1` that is 1.

### B. Per-project fork + test-parallelism knobs (the three sbt keys)

- **`fork`**: JVM modules `true` (base `kyo-settings:131`); JS/Native/Wasm `false`
  (`:3059/:3108/:3130`), so those run in the Node / native process, not a forked JVM.
- **`parallelExecution`** (sbt default `true`): forced `false` where a shared resource or fixed
  port would collide: aeron-native (`:1978`, fixed UDP ports), browser jvm/native
  (`:2663/:2692/:2779/:2808`, Chrome), js/wasm (`:3110/:3132`). `false` makes that project's test
  groups (forks) run one at a time.
- **`testForkedParallel`** (sbt default `false`): the ONLY `true`s are kyo-sql jvm (`:2562`) and
  kyo-bench (`:2912`); set explicitly `false` as belt-and-braces in browser (`:2664/:2780`) and
  `native-settings-base` (`:3061`). It controls parallelism of MULTIPLE suites WITHIN one forked
  group; see the caveat in D.

### C. `testGrouping` overrides (the fork topology)

- **Default (no override): one group per module -> one fork per module**, all its suites in that
  one JVM.
- **kyo-sql jvm (`:2563-2605`): one group PER SUITE**, and per (suite x runtime) for
  container suites, pinning `KYO_POD_RUNTIME=podman|docker` per fork. It auto-detects which
  suites need the split by scanning the suite SOURCE for `runBackends`/`runRuntimes` at config
  time (kyo-test suites can't be reflectively instantiated to call `testNames`). Groups run in
  parallel (parallelExecution left at default true), capped at 2 forks by ForkedTestGroup.
- **browser jvm (`:2665-2685`) + native (`:2687-2705`): one group per suite, but
  `parallelExecution := false`** so the per-suite Chrome forks run strictly one at a time; the
  comment records that running them concurrently was tried and reverted (Chrome starvation
  cascade, `:2655-2662`).
- **kyo-bench (`:2914-...`): one group per suite, `testForkedParallel := true`**, parallelExecution
  default -> parallel forks capped by ForkedTestGroup.

### D. How B and C actually combine (and one likely-vestigial setting)

- The build knobs (A-C) bound concurrency ACROSS JVMs; the runner's `globalK` /
  `globallySequential` (Finding 4) bound it WITHIN a JVM. Total machine leaves =
  `globalK x concurrent-forks`, and the two sides are set in different files.
- **Caveat on `testForkedParallel := true` in kyo-sql and kyo-bench:** both use strictly
  one-suite-per-group `testGrouping`, and `testForkedParallel` only affects a group that holds
  MORE than one suite. With single-suite groups it has no observable effect; their real cross-fork
  parallelism comes from `parallelExecution` (left at default `true`) + the `ForkedTestGroup` cap.
  So those two `:= true` lines look vestigial (harmless, but not load-bearing). Worth confirming
  before trusting them to do anything; flagging, not asserting a bug.
- **The knob depends on topology, and they are not interchangeable:** `parallelExecution := false`
  serializes forks (across JVMs) but cannot order suites inside one JVM; `testForkedParallel`
  orders suites inside one JVM but does nothing across forks; the runner's `globallySequential`
  orders leaves across suites inside one JVM. A resource shared across suites in separate forks is
  ONLY protected by `parallelExecution := false` + the fork cap (what browser does), never by the
  runner gate.

### E. JS/Native/Wasm specifics

No JVM fork. js/wasm set `parallelExecution := false` (`:3110/:3132`) and the Scala.js linker
lock serializes linking; CI turns on `withBatchMode(true)` (`:3117-3120/:3142`) to drop
incremental linker state (footprint, not parallelism). Native pins the runner to `globalK = 1`
(single-threaded; concurrent unwinding crashes libunwind, `LeafPool.scala:125-127`) and sets
`testForkedParallel := false` (`:3061`).

## Finding 6: consolidated end-to-end execution model (cross-checked across all entry points)

This section reconciles timing, parallelism, forking, and reporting into one consistent model,
verified against JVM (sbt + CLI), JS, and Native.

### The single execution primitive

`TestRunner.runReport(suiteClass, config)` runs exactly ONE suite as one Kyo computation, fires
every reporter callback for it, and returns a single-suite `TestReport`. **Every entry point
calls it once per suite:**

| Entry point | Site | Bridge | parallelism cap | Aggregate at end |
|---|---|---|---|---|
| sbt (JVM) | `SbtTask.execute` | `runToFuture` + `Await` | none | `Summary` at `done()`, per fork + leak check |
| JS | `JsTask.execute` | `runToFuture` + `onComplete` (async, JS can't block) | capped to 1, warns | `Summary` at `done()` |
| Native | `NativeTask.execute` | `runToFuture` + `Await` | capped to 1, warns | `Summary` at `done()`, no leak check |
| CLI | `CliPlatform.runSuites` loops suites | `runToFutureAtCliEdge` + `Await` | none | **none** (only exit code + per-suite console) |

The consequence, which is the through-line of Findings 1-5: **there is no once-per-run code path
anywhere.** `runReport` is the unit of everything, and it is a single suite.

### Inside `runReport`: discover -> order -> execute

1. Synchronous cursor walk (`walkNode`/`probe`) enumerates every leaf, including macro/loop
   generated ones. `--count`/`--list` stop here and print `[kyo-test:count]` / `[kyo-test:leaf]`,
   executing no body (`TestRunner.scala:115-126`).
2. Filter (glob path + tags) -> optional shuffle (`randomize` seed) -> focus resolution.
3. Each leaf becomes a `leafComp` submitted to `LeafPool`; the suite awaits the promises in input
   order (`TestRunner.scala:181-192`).

### Parallelism, as one consistent set of degrees

- **Within a suite** (`RunConfig.parallelism`): `1` = push-await-each (serial); `0`/`N>1` = push
  all, ordered await. Not a per-suite cap anymore.
- **Within a JVM/fork** (`LeafPool`, one instance per process): `globalK` workers bound total
  concurrent leaves across ALL suites in that process. **`globalK = 1` on Native / leak-debug,
  else `max(1, Async.defaultConcurrency)` = `max(1, 2 x availableProcessors)`**
  (`Async.scala:75-78`, override `-Dkyo.async.concurrency.default`). So a single JVM fork already
  runs up to `2 x cores` leaves concurrently on kyo's scheduler. `globallySequential` = a
  process-wide mutex serializing leaves across suites in that JVM.
- **Across JVMs** (build layer, Finding 5): fork topology + `Tags` caps.
- **Machine-wide concurrent leaves = `globalK` x concurrent-forks**, and the two factors live in
  two different files (runner `globalK` vs build `ForkedTestGroup`/`Test`/`limitAll`).
- **JS/Native** cap `RunConfig.parallelism` to 1 at the task bridge with a stderr warning
  (`JsTask.scala:59-69`, `NativeTask.scala:40-48`); JS concurrency is cooperative (kyo Scheduler as
  a MacrotaskExecutor EC), Native is truly serial (`globalK = 1`).

### Timing, as one consistent model

- **Per-leaf reported ms** is measured from the body's actual start ON the pool worker (after
  dequeue, `TestRunner.scala:359`) to the body join (`:453`). It therefore EXCLUDES time the leaf
  spent queued waiting for a worker.
- **Per-suite span** is measured from `onSuiteStart` (`:112`) to all-leaves-done (`:195`), so it
  INCLUDES queue wait and parallel overlap. Hence `suite span >= sum(leaf ms)`, and the gap is
  exactly pool-wait + overlap. This is the same "reported vs span" split `ci-analyze.py` computes,
  already present at suite granularity but never surfaced as an absolute timeline.
- **No absolute wall-clock timestamps** are emitted anywhere (Finding 1).
- **Heartbeat**: a forked `Clock` fiber calls `onLeafHeartbeat` after `heartbeatInterval`
  (default 1 min) and dumps threads on the first STUCK leaf (`TestRunner.scala:320-334`,
  `ConsoleReporter.scala:156-163`).
- Latent: `TestReport.totalDuration` SUMS suite durations (`TestReport.scala:57-58`), which would
  over-count wall time for concurrent suites; harmless only because `onRunComplete` is always fed
  a single suite.

### Reporting, as one consistent model

- Callback ORDER matches the `TestReporter` contract, but `onRunStart`/`onRunComplete` fire per
  SUITE on every entry point, contradicting their own docs ("once before any suite" / "once after
  all suites", `TestReporter.scala:25-49`). So the console `Running 1 suite(s)` and `Results: ...`
  lines repeat once per suite and are never a run total.
- `ConsoleReporter` is the default; `TapReporter`/`JUnitXmlReporter` are opt-in per suite (Finding
  2); `CombinedReporter` fans out and isolates a throwing reporter (`CombinedReporter.scala:79-88`).
- The ONLY cross-suite aggregate kyo-test renders is `Summary.render` at `done()`, and that is
  **per Runner = per fork** (Finding 4). The CLI renders no aggregate at all. The only whole-run
  total in a multi-fork build is sbt's own, tallied from the one-per-leaf `Event`s
  (`EventBuilder.scala`), one `Status` per leaf.

### Forking, as one consistent model (from Findings 4-5)

JVM modules fork (`fork := true`), default one fork per module; each fork has its own `LeafPool`,
its own `results` queue + `done()`/`Summary`, and its own end-of-run leak check (forked-only).
JS/Native/Wasm do not fork a JVM; Native pins `globalK = 1` and runs no leak check.

### The consistency conclusions (what this all adds up to)

1. **Everything is per-suite; nothing is per-run.** `runReport` is the only primitive, so the
   run-level reporter callbacks, the console "Results:" line, and (per fork) the `Summary` line
   are all really per-suite or per-fork. This single fact explains Findings 1-4.
2. **No kyo-test-level whole-run aggregate exists**, by construction. Per-fork `Summary` on
   sbt/JS/Native, nothing on CLI; sbt's own `Event` tally is the sole cross-fork total.
3. **Machine load = `2 x cores` (per fork) x concurrent-forks**, configured across two files that
   must be reasoned about together.
4. **`testForkedParallel := true` (kyo-sql, kyo-bench) is inert** given their single-suite groups.
5. **Build-time attribution has to be reconstructed externally** (`ci-analyze.py`) because the
   per-leaf/per-suite timing kyo-test already computes is never emitted with absolute timestamps
   or as a machine-readable per-run artifact (Findings 1-2).

## Finding 7: the parallelism layers for kyo's own test run (with the adaptive scheduler)

Parallelism for the kyo codebase's tests is not one setting; it is six nested layers, each
bounding or shaping the one below it. Top = coarsest (whole CI job), bottom = the OS threads.

### L1. CI job / matrix (separate processes)

Each `(platform x scala-version)` is its own CI job on its own runner, so those run in parallel
but share nothing. Within a job, `testKyo` (`project/TestKyo.scala`) emits a single `;`-joined
list of `module/test` tasks into ONE sbt process, and the three phases (compile-main,
compile-test, test) are split into separate sbt processes so the driver never holds a compile
heap while test forks run.

### L2. sbt task scheduler (`Global / concurrentRestrictions`, `build.sbt:92-119`)

Bounds how many of those tasks run at once: `limitAll(SBT_TASK_LIMIT)` (CI=1 -> fully serial),
`Tags.Test` (CI cores/2), `Tags.ForkedTestGroup` (CI hard-cap 2). This layer decides how many
forked test JVMs coexist.

### L3. Module fork topology (`fork`, `testGrouping`, `parallelExecution`, `testForkedParallel`)

Decides how suites map to JVMs (default one fork per module; per-suite for browser/kyo-sql/
kyo-bench), whether a module's forks run in parallel (`parallelExecution`), and whether suites
inside one fork run in parallel (`testForkedParallel`). (Findings 4-5.)

### L4. kyo-test runner dispatch (per fork)

`SbtRunner` creates one `SbtTask` per suite; sequential or concurrent within the fork per
`testForkedParallel`. Each `SbtTask` calls `runReport` for exactly one suite.

### L5. kyo-test `LeafPool` (per fork, logical governor)

One `LeafPool` per JVM bounds concurrent leaf FIBERS at `globalK` across all suites in the fork:
`globalK = max(1, 2 x cores)` on JVM, `1` on Native (`LeafPool.scala:125-132`).
`RunConfig.parallelism` orders a suite's own leaves; `globallySequential` is a process-wide mutex
across suites. **This layer exists specifically to govern L6**: the pool comment records that the
old per-suite bound let `(parallel suites) x K` leaf fibers "flood kyo's shared scheduler", so the
single global bound caps how much work is handed to the scheduler at once.

### L6. kyo adaptive scheduler (per fork, the real threads)

The leaf fibers from L5, AND every fiber each leaf body spawns (an `Async.foreach` at
`defaultConcurrency = 2 x cores`, races, forks), all run on the one process-wide
`Scheduler.get` (`Scheduler.scala:574`). It is ADAPTIVE, not a fixed pool:

- Worker count starts at `coreWorkers = cores`, and moves between `minWorkers = cores/2` and
  `maxWorkers = cores x 100` (`Flags.scala:5-7`).
- A **Concurrency regulator** on a dedicated OS thread runs a jHiccup-style 1ms sleep probe and
  reads scheduling jitter: high jitter (thread interference / CPU oversubscription) shrinks the
  pool, low jitter at target load grows it (`Concurrency.scala:61-74`, `updateWorkers:427-435`).
- A **BlockingMonitor** inspects worker thread states; blocked carriers (parked I/O drivers,
  blocking fibers) floor the count at `blocked + minWorkers`, so blocking is absorbed by adding
  workers instead of starving runnable work (`Scheduler.scala:427-448`).
- Plus work-stealing, `timeSliceMs` task preemption, and optional Loom virtual-thread workers.

So the actual thread count per fork is self-tuning to the workload: a blocking/IO-heavy suite can
grow the pool well past `cores`, while a CPU-bound suite that induces jitter shrinks it back.

### How the layers compose (the point)

- **L2-L3 bound processes/forks; L5 bounds dispatched leaf fibers per fork; L6 adapts OS threads
  per fork.** They are different quantities, not one number: `concurrent-forks (L2-L3)` x
  `globalK leaf fibers (L5)`, each fork's fibers multiplexed onto an adaptive thread pool (L6)
  that the OS ultimately arbitrates for CPU.
- **The fixed caps (L2, L3, L5) are what actually bound resource use**, precisely because L6 is
  adaptive and will not self-limit to cores: left ungoverned it grows workers to absorb load up to
  `cores x 100`. L5's `globalK` is the governor that keeps the flood of test leaves from driving
  that growth; L2's `ForkedTestGroup`/`SBT_TASK_LIMIT` keep too many adaptive schedulers (one per
  fork) from running at once.
- **On the constrained CI (`SBT_TASK_LIMIT=1`)** it collapses to: one fork at a time -> one
  adaptive scheduler -> up to `globalK = 2 x cores` leaf fibers in flight -> worker threads
  self-tuning between `cores/2` and `cores x 100` under the jitter/blocking regulators.
- **Native inverts most of this**: `globalK = 1` (L5) and the runner caps `parallelism` to 1, so
  leaves are serial regardless of the scheduler.

## Finding 8: per-module parallelism differences

Two orthogonal axes: the build-level fork topology (build.sbt, per module) and the suite-level
`RunConfig` overrides (test source, per suite). Most modules take the baseline on both.

### Baseline (the large majority: kyo-core, kyo-data, kyo-kernel, kyo-prelude, kyo-http, kyo-net, kyo-actor, kyo-stm, kyo-zio, kyo-cats, kyo-caliban, kyo-combinators, ...)

- `kyo-settings` -> `fork := true`, sbt-default `testGrouping` = **one fork per module**,
  `testForkedParallel` unset = **suites run sequentially inside that fork**, `parallelExecution`
  default true.
- Leaves run **parallel within a suite** via `LeafPool` (`globalK = 2 x cores`).
- Net: one JVM per module, suites serial, leaves parallel.

### Build-level divergences (build.sbt)

| Module(s) | fork / grouping | parallelExecution | testForkedParallel | Net parallelism |
|---|---|---|---|---|
| **kyo-pod** (`:2542`) | one fork per suite, and per (suite x runtime) for container suites (`KYO_POD_RUNTIME=podman/docker`, auto-detected from source) | default (parallel) | `true` (inert: 1-suite groups) | container suites fork once per daemon; <= 2 forks via ForkedTestGroup, one per daemon |
| **kyo-browser** JVM (`:2663`) | one fork per suite | **false** (forks serial) | false | each suite own JVM + Chrome, run one at a time (Chrome contention) |
| **kyo-browser** Native (`:2692`) | native binary (no JVM fork) | **false** | (n/a) | suites serial for shared Chrome WS |
| **kyo-ui** (`:2779`) | one fork per suite | **false** | false | same Chrome-isolation pattern as kyo-browser |
| **kyo-bench** (`:2912`) | one fork per suite | default (parallel) | `true` (inert) | each JMH suite its own fork, forks parallel (<= ForkedTestGroup) |
| **kyo-aeron** Native (`:1978`) | native binary | **false** | (n/a) | suites serial: they bind fixed UDP ports that collide if concurrent |
| **all JS** (`js-settings:3106`) | no JVM fork (Node) | **false** | (n/a) | test tasks serial + Scala.js linker lock |
| **all Wasm** (`wasm-settings:3128`) | no JVM fork (Node) | **false** | (n/a) | serial |
| **all Native** (`native-settings-base:3058`) | no JVM fork (native binary) | per-module | **false** | leaves serial regardless: runner forces `globalK = 1` + caps `parallelism` to 1 |

Everything else (kyo-aeron JVM, kyo-compiler, kyo-examples, ...) restates `fork := true` at most,
which is the baseline. Note `testForkedParallel := true` is **inert** in both places it appears
(kyo-pod, kyo-bench) because their groups hold one suite each (Finding 5).

### Suite-level divergences (`override def config`, test source)

- **`.sequential`** (within-suite serial leaves): **65 suites** across ~18 modules, concentrated in
  **kyo-ffi (27)**, **kyo-stats-machine (12)**, **kyo-core (7)**, kyo-stm (3); the rest have 1-2.
  Used where a suite's leaves touch a shared/global resource (OS signals, `Clock`, `System`,
  `Queue`/`Channel`, native FFI state, the stats machine). This only orders THAT suite's own
  leaves.
- **`.globallySequential(true)`**: exactly **one** carrier, kyo-ai's `BaseAITest` (`:205`, applied
  to every AI suite), for a shared AI provider/CLI; meaningful only because AI suites can share a
  JVM.
- **numeric `.parallelism(N)`**: **none** anywhere; suites either take the default (parallel) or
  `.sequential` (= parallelism 1).

### The one-line summary

Per module, parallelism differs on three things: (1) whether it forks per module (default) or per
suite (kyo-pod, kyo-browser, kyo-ui, kyo-bench); (2) whether those units run in parallel (default,
kyo-pod, kyo-bench) or serialized via `parallelExecution := false` (kyo-browser, kyo-ui,
aeron-native, all JS/Wasm); (3) the platform floor (JVM leaves parallel at `2 x cores`, Native
leaves always serial at `globalK = 1`). On top of that, 65 individual suites drop to
within-suite-serial and one base (kyo-ai) goes process-wide serial.

## Suggested order

1. Reproduce Finding 3 on a small multi-suite module, capture the tail, confirm the exact
   symptom. (cheap, decides the fix shape)
2. Fix Finding 3 with a regression test.
3. Finding 1 (timestamps) + Finding 2 (default timing report) together, since a JSON timing
   report is the natural carrier for both.

Which do you want to pursue, and in what order?
