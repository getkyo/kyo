# Prior art: JVM test forking in Gradle and Maven Surefire

Research for `Workers` (forked-worker-JVM task execution with a global concurrency
cap). Both build tools solve a narrower version of our problem: distribute
independent units of work (test classes/methods) across a bounded pool of forked
JVMs, with knobs for process reuse vs. recycling. Maven adds a remote-agent layer
(Develocity Test Distribution) that is the closest existing analog to a
`Task.Queue` coordinator.

## 1. What it is

- **Gradle `Test` task**: forks one or more JVM subprocesses ("test workers") to
  run the test classes owned by one `Test` task instance. Fork count, JVM args,
  and process recycling are per-task properties (`Test extends
  AbstractTestTask`, source: `org.gradle.api.tasks.testing.Test`).
- **Maven Surefire/Failsafe**: a plugin bound to the `test`/`integration-test`
  lifecycle phases. It forks JVM processes ("forked booters") that execute the
  test classes in a module, with independent knobs for fork count and
  in-process thread parallelism.
- **Gradle Test Distribution** (Develocity add-on, not open-source Gradle core):
  farms test *partitions* out to a pool of remote/local agent processes
  connected through a Develocity server, on top of the same `Test` task API.

## 2. Unit of work

Both tools schedule at **test-class granularity** for cross-process
distribution; per-process parallelism (Surefire's `parallel=methods`, JUnit 5's
own parallel executor under Gradle) can go finer to method granularity, but
that's *within* one forked JVM, not a routing unit across forks. Test
Distribution partitions at the same class granularity but groups classes into
"partitions" (see §3) rather than handing out one class per unit.

## 3. Grouping / routing (fork assignment)

- **Gradle core**: static, **round-robin** assignment of test classes to the
  `maxParallelForks` worker slots, computed once at task startup, not
  re-balanced as workers finish early. A worker unlucky enough to draw several
  slow classes just runs long; there is no runtime work-stealing (confirmed
  gap, tracked as [gradle/gradle#2669](https://github.com/gradle/gradle/issues/2669)).
- **Surefire**: when `parallel` is unset, one test class is handed to a fork at
  a time as forks free up (dynamic, not pre-partitioned); `forkCount` just caps
  how many run concurrently.
- **Test Distribution**: routing is the most sophisticated of the three. It
  uses **historical execution-time data from Develocity** to build balanced
  partitions of near-equal expected duration, then matches each partition to a
  compatible agent via the requirements model below. This is real load-aware
  scheduling, not round-robin.

## 4. Declarative config / requirements model

Gradle (`Test` task, Groovy/Kotlin DSL):

```kotlin
tasks.test {
    maxParallelForks = Runtime.getRuntime().availableProcessors()  // fork count cap
    forkEvery = 100                                                 // recycle after N classes (0 = never)
    jvmArgs("-Xmx1g")
}
```

Surefire (`pom.xml`):

```xml
<plugin>
  <groupId>org.apache.maven.plugins</groupId>
  <artifactId>maven-surefire-plugin</artifactId>
  <configuration>
    <forkCount>2.5C</forkCount>        <!-- 2.5x core count, like sbt's %-of-cores pattern -->
    <reuseForks>true</reuseForks>
    <argLine>-Xmx1024m</argLine>
    <parallel>classesAndMethods</parallel>
    <threadCount>4</threadCount>
    <perCoreThreadCount>true</perCoreThreadCount>
    <threadCountClasses>2</threadCountClasses>
    <threadCountMethods>2</threadCountMethods>
  </configuration>
</plugin>
```

Test Distribution's model is the one worth stealing outright: **string
requirements with three shapes**, matched against agent capabilities:

- key-value: `os=linux`
- expression: `memory>=2.5Gi`
- bare tag: `postgres`

An agent runs a partition only if it satisfies every requirement; local
executors are treated as satisfying anything (so a build degrades gracefully
with zero remote agents attached). Gradle also injects an implicit
`jdk=«major-version»` requirement automatically from the test task's toolchain,
so version mismatches can't silently misroute. This is directly a
`Task.Queue({classpath, jvmOptions, maxWorkers, ...})`-shaped model already:
declared *requirements* on the work item, declared *capabilities* on the
worker pool, and a matcher in between rather than the caller hand-picking a
pool.

## 5. Worker/process model — reuse vs. recycle

This is the section that maps most directly onto `Task.isolation`
(fresh-worker vs. recycle).

- **`reuseForks` (Surefire, default `true`) / `forkEvery` (Gradle, default `0`
  = unlimited reuse)** are the same knob from opposite ends: Surefire's
  default keeps one JVM alive for the whole module; Gradle's default keeps one
  worker alive for the whole task. Both let you force **recycling** — Surefire
  via `reuseForks=false` (new JVM per class, maximum isolation, worst
  throughput), Gradle via `forkEvery=N` (new JVM every N classes, a throughput
  vs. leak-tolerance dial).
  - `forkCount=1/reuseForks=true` (Surefire default): one JVM runs the whole
    module.
  - `forkCount=1/reuseForks=false`: one JVM per class, sequential — maximum
    isolation, no parallelism.
  - `forkCount>1/reuseForks=true`: N JVMs, each reused across many classes —
    this is the practical "warm pool" mode.
- Gradle's own docs are blunt that `forkEvery` exists almost entirely as a
  **leak/static-state escape hatch**, not a performance feature: "Low values
  severely degrade test performance." That's the same trade-off `Task.isolation
  = fresh` vs. `recycle` needs to document plainly rather than imply it's free.
- Neither tool exposes a *mid-lifetime* health check that recycles a worker
  early on suspicion of corruption; recycling is purely count-based
  (`forkEvery`) or timeout-based (below), never state-based. That's a gap our
  design shouldn't inherit if `Task` wants a "recycle after failure" policy.

## 6. Scheduling & concurrency control

- **Gradle**: `maxParallelForks` is the hard cap on concurrent worker JVMs for
  *one* `Test` task; it's explicitly clamped to Gradle's own
  `--max-workers` build-wide concurrency budget, so a single task can't
  oversubscribe the daemon. This is the direct analog of a **global
  parallelism cap composed with a per-pool cap** — exactly the
  `Workers.run(Config(parallelism=N))` outer bound vs. `Task.Queue(maxWorkers=
  ...)` inner bound shape.
- **Surefire**: `forkCount` supports a `<N>C` multiplier syntax
  (`2.5C` = 2.5 × cores) as a declarative way to scale to the host without
  hardcoding a number — worth stealing verbatim for `Config(parallelism = ...)`
  defaults.
- **Test Distribution**: concurrency is bounded by the number of *connected,
  requirement-matching* agents, which is a dynamic pool size the coordinator
  doesn't fully control (agents connect/disconnect independently). It is the
  clearest existing model of "global cap + external elastic worker pool"
  rather than "global cap + fixed worker set."

## 7. Worker / forked-JVM reliability

This is the section that matters most for `Workers`: how each system keeps a
pool of forked JVMs alive, honest, and bounded when things go wrong — hangs,
crashes, and the coordinator itself dying.

### 7a. Hung/stuck fork detection

- **Surefire**: `forkedProcessTimeoutInSeconds` bounds the total time a forked
  JVM may run before Surefire kills it and reports a timeout failure for
  whatever was in flight. No default — opt-in, so a bare Surefire config can
  still hang forever on a stuck fork.
- **Gradle core**: no equivalent at all. There is no per-worker execution
  timeout on the `Test` task; a hung test worker hangs the whole `Test` task
  indefinitely, and the documented workaround is wrapping the *entire build
  invocation* in an external timeout, not anything scoped to one worker.
  Confirmed, meaningful gap relative to Surefire.
- **Test Distribution**: no separate stuck-test timeout of its own layered on
  top; a hang inside a partition is bounded only by whatever the underlying
  `Test` task configuration provides (i.e., it inherits Gradle core's gap
  unless the project also adds `forkedProcessTimeoutInSeconds`-equivalent
  tooling itself).

### 7b. Crash detection and result semantics (at-least-once vs. at-most-once)

- **Surefire's crash signal**: the parent detects a crash by the *absence* of
  the fork's expected clean-shutdown handshake on the event channel — logged
  as `"The forked VM terminated without properly saying goodbye. VM crash or
  System.exit called?"` and raised as a `SurefireBooterForkException`. By
  default this is **fatal to the build**, not silently retried and not
  silently dropped: Surefire fails loud on uncertainty rather than guessing
  the crashed test passed or quietly excluding it.
- Attribution of *which* test was running at crash time is genuinely fragile
  in practice, not just in theory: [SUREFIRE-1945](https://issues.apache.org/jira/browse/SUREFIRE-1945)
  documents real-world cases where a crashed fork with heavy concurrent
  logging output produces **no usable surefire-report** for the in-flight
  test at all — the crash is detected, but which unit of work it interrupted
  is lost. Any crash-attribution scheme needs to be robust to output
  interleaving, not just "whatever the worker was last told to run."
  Confirming which task a crash interrupted needs to be a property the
  *coordinator* tracks authoritatively (last dispatched, not yet acked), not
  reconstructed from worker-side output after the fact.
- Net posture: Surefire defaults to **at-most-once with fail-loud-on-crash**;
  turning it into at-least-once requires explicitly opting into
  `rerunFailingTestsCount` (see §7f) — and even that only helps for tests that
  *finish and report a failure*, not for one that vanishes with its fork.
- **Gradle core**: same shape, less machinery. A crashed worker's unreported
  tests show as missing/failed in the result set; there is no automatic
  redistribution of that worker's remaining assigned classes to another
  worker in core Gradle (that capability only exists in the separate Test
  Distribution product, §7g).

### 7c. Orphan prevention (leaked forked JVMs when the parent dies)

This is the single most concrete, most stealable mechanism in the whole
survey. **Surefire's `PpidChecker` runs two independent liveness checks
*inside the forked JVM itself*, simultaneously**, so the fork polices its own
lifetime instead of trusting the parent to always get a chance to clean up:

- **NATIVE check**: every ~1 second, runs `ps -o etime,pid <parent-pid>` and
  confirms the parent's elapsed-time is still monotonically increasing (i.e.,
  it's still the same live process, not a PID that got reused); if the parent
  is gone, calls `Runtime.halt(1)` immediately.
- **PING check**: the parent periodically sends NOOP commands down the
  existing event channel; if the fork receives none within **30 seconds**, it
  assumes the parent is gone and self-halts.

Both run at once because they catch different failure modes: NATIVE catches a
parent that's outright gone (`kill -9`), PING catches a parent that's still a
live PID but has stopped servicing the channel (frozen, deadlocked). Neither
alone is sufficient.

**Gradle core has the opposite, well-documented failure mode.** Worker
processes are **not** cleaned up when the Gradle daemon crashes or is killed —
tracked for years as [GRADLE-3298](https://issues.gradle.org/browse/GRADLE-3298)
and reopened specifically for `Ctrl+C` at [gradle/gradle#21260](https://github.com/gradle/gradle/issues/21260)
and [gradle/gradle#18716](https://github.com/gradle/gradle/issues/18716) /
[#2128](https://github.com/gradle/gradle/issues/2128). Killing the daemon
leaves orphaned worker JVMs running indefinitely, still holding a CPU core,
and — worse — a subsequent build invocation doesn't start a fresh worker but
silently blocks waiting on the stale one. This is a live, currently-open bug
class precisely because there is no Surefire-style self-terminating heartbeat
inside the worker; the worker has no independent way to notice its owner is
gone.

### 7d. Graceful vs. forced kill

- **Surefire's two-tier shutdown**: on a clean end-of-run, the parent asks the
  fork to shut down (drain remaining queued events, call `System.exit(0)`),
  then waits up to `forkedProcessExitTimeoutInSeconds` (default **30s**) for
  the JVM's non-daemon threads to actually finish before force-killing it.
  This is distinct from the PpidChecker path in §7c: when the *parent* is
  detected as gone, the fork skips the graceful path entirely and calls
  `Runtime.halt(1)` directly (bypassing finalizers/shutdown hooks), because
  there's no one left to report a clean shutdown to.
- **Gradle**: no comparably documented two-tier kill exposed on the public
  `Test` task API for individual workers — consistent with the orphan-leak
  gap in §7c; without an owned, unconditional force-kill path once the
  coordinator itself is gone, there's nothing to trigger it.

### 7e. Resource-leak avoidance / recycling

Covered in depth in §5 (`forkEvery` / `reuseForks`): both tools' only lever
for a leaky worker is **count-based recycling**, configured up front. Neither
ties recycling to a worker's actual health or crash history — a worker that
just survived (or narrowly avoided) a crash is not treated any differently
from a brand-new one, it just continues accruing toward the same `forkEvery`
count. That is a gap worth not inheriting (see Lessons).

### 7f. Flaky-test retry mechanisms

These operate one full layer above §7a-d: they answer "the unit of work
*finished* and reported a failure," never "the JVM running it disappeared."

- **Gradle test-retry plugin** (`org.gradle.test-retry`, a separate plugin,
  not core Gradle — disabled by default):

  ```kotlin
  test {
      retry {
          maxRetries = 2
          maxFailures = 20
          failOnPassedAfterRetry = true
          failOnSkippedAfterRetry = true
      }
  }
  ```

  `maxRetries` bounds retries *per test*; `maxFailures` is a separate,
  whole-task circuit breaker — once more than 20 tests are failing, the
  plugin stops retrying altogether, so a systemic break (bad config, broken
  build) can't be masked as "just flaky" by retrying every failure into
  exhaustion. `failOnPassedAfterRetry` inverts the plugin's usual purpose into
  a flake *detector*: fail the build anyway if a test needed a retry to pass,
  rather than silently treating retried-and-passed as a clean success.
- **Surefire's `rerunFailingTestsCount`**: reruns a failing test immediately,
  in the same run, up to N times until it passes or reruns are exhausted
  (supported for JUnit 4.12+, JUnit 5, TestNG). Since 3.0.0-M6 it composes
  with `failOnFlakeCount`, failing the build if more than N tests were
  observed flaky (passed only after a rerun) — the same "a needed retry is
  still a signal, not a free pass" idea as Gradle's `failOnPassedAfterRetry`.
- Neither mechanism is crash-aware. A rerun only fires for a test that
  *returned* a failure; a fork that dies mid-test never reaches the retry
  logic at all — it has to be caught by §7b/§7c first, and only then handed to
  something retry-shaped.

### 7g. Test Distribution: agent failure handling

- Each agent holds a **persistent WebSocket connection** to the Develocity
  server; on disconnect (network loss, agent crash, server restart) the agent
  itself auto-reconnects periodically.
- From the coordinator's side, a dropped agent connection **during** a
  partition is what drives the reschedule behavior already noted in §4/§10:
  the affected partition (not the whole task) is rescheduled to another local
  or remote executor, with retries configurable to run in the same JVM or a
  fresh one. This is coordinator-side detection of worker loss (via the
  broken connection, no separate heartbeat protocol needed on top of the
  transport) driving automatic, unit-scoped redistribution — the most
  complete "at-least-once for the unit of work" story of the three systems,
  because it's the only one of the three that actually completes the retry
  automatically rather than just making a retry configurable.

## 8. Results

Both tools stream structured per-test events back to the parent process as
tests complete (not batched at process exit), so a hung or crashed worker
loses only its own unreported tests, not the whole run's results. Test
Distribution additionally transfers back **registered output files** per
partition (reports, coverage data) after each partition finishes, merging
directory trees from multiple agents — the closest existing model for
`Workers.fork` returning both a typed result and arbitrary worker-produced
artifacts.

## 9. Transport/protocol (brief)

- **Surefire**: binary framed protocol over the fork's stdin/stdout pipes by
  default (`pipe://`), with a `tcp://` alternative via a pluggable
  `MasterProcessChannelProcessorFactory` SPI; each frame is
  `:magic::opcode::data:`, ~21 opcodes covering test-lifecycle events plus
  control commands. Events flow fork→parent, commands flow parent→fork.
  Notably fragile: anything the forked process writes directly to stdout
  (native libs, misbehaving logging config) corrupts the channel — a real
  argument for kyo `Workers` to use a dedicated socket/pipe for protocol
  traffic and leave the worker's inherited stdout for human-readable logs
  only.
- **Gradle**: socket-based, not documented as a public wire format (internal
  API); worker JVMs are launched with a connector address and report
  `TestDescriptor`/`TestResult` events back over that socket.

## 10. Lessons for `Workers`

Direct mappings:

- `maxParallelForks` → `Task.Queue(maxWorkers = ...)`, always composed with
  (never replacing) an outer `Workers.run(Config(parallelism = N))` cap, the
  way Gradle clamps per-task forks to daemon-wide `--max-workers`.
- `forkEvery` / `reuseForks=false` → `Task.isolation = fresh`; `reuseForks=
  true` / `forkEvery=0` → `Task.isolation = recycle`. Both tools default to
  reuse and treat forced-fresh as an opt-in isolation/leak escape hatch, which
  argues `recycle` should be `Task`'s default too, not `fresh`.
- Test Distribution's **requirements/capabilities matcher** → the model for
  `Task.Queue({classpath, jvmOptions, maxWorkers, ...})` selection: give each
  `Task` declared requirements (tags, resource minimums) and each `Task.Queue`
  declared capabilities, and let a matcher route, rather than requiring the
  caller to name a queue directly. Include an implicit-requirement mechanism
  (Gradle auto-adds `jdk=«version»`) so obvious mismatches (e.g. classpath
  incompatibility) can't silently misroute.
- Surefire's `forkedProcessTimeoutInSeconds` vs.
  `forkedProcessExitTimeoutInSeconds` split → `Task.timeout` should
  distinguish "work deadline" from "graceful-shutdown grace period" as two
  values, not one.
- Test Distribution's per-partition reschedule-on-disconnect → `Task.retry`
  should be partition/task-scoped (retry just the failed unit), matching how
  Distribution never re-runs a whole `Test` task for one flaky partition.

Reliability mappings (from §7):

- Surefire's `PpidChecker` (dual NATIVE ps-liveness + PING channel-heartbeat,
  self-`Runtime.halt(1)` on parent loss, §7c) → every kyo worker process
  should carry the same self-terminating heartbeat against the coordinator,
  not rely on the coordinator always getting a chance to kill it. This is the
  highest-value concrete steal in this document: it is the one mechanism that
  directly prevents the exact class of bug Gradle core has had open for years
  (§7c) — orphaned worker JVMs surviving a killed coordinator and silently
  blocking the next run.
- A worker crash needs to be a **first-class, distinguishable outcome** from a
  task returning `Abort`/`Left` — `Workers` needs an explicit "the worker died
  while executing task T" signal, tracked authoritatively by the coordinator
  (last-dispatched-not-yet-acked), not reconstructed from worker output after
  the fact (SUREFIRE-1945 shows real projects get this attribution wrong,
  §7b). Retry policy legitimately differs between the two cases: a worker
  crash plausibly warrants a fresh worker on retry regardless of the task's
  own `isolation` setting, while a task-level failure should retry under
  whatever isolation policy was already configured.
- Kill needs **three tiers**, not two: graceful (drain in-flight protocol
  messages, ask cleanly, bounded grace period, mirroring
  `forkedProcessExitTimeoutInSeconds`'s 30s default) → forced (hard kill from
  a live coordinator) → **self-halt** (the worker unilaterally terminates
  itself the moment it can no longer reach the coordinator at all, mirroring
  PpidChecker's `Runtime.halt(1)`, §7d). Only Surefire has all three; Gradle's
  missing third tier is exactly why it leaks orphans.
- `Task.retry` should make a needed retry **visible, not silent** — Gradle's
  `failOnPassedAfterRetry` and Surefire's `failOnFlakeCount` both treat
  "succeeded, but only after retry" as a signal worth surfacing rather than a
  quiet pass (§7f); `Workers` should expose whether a successful `Task` result
  came from a first attempt or a retry.
- Retry needs a **two-level cap**, not one: per-unit (`maxRetries` /
  `rerunFailingTestsCount`) *and* a whole-run circuit breaker
  (`maxFailures`/`failOnFlakeCount`) so a systemic break (e.g. every task on
  one queue failing because the queue's JVM options are wrong) can't be
  masked as "just retry it" one task at a time into exhaustion (§7f).
- Recycling (§5/§7e) should be able to factor in a worker's crash/health
  history, not just a raw task count — neither Gradle nor Surefire does this,
  and it is a real gap: a worker that just survived a near-crash is currently
  treated identically to a fresh one by both tools' `forkEvery`/`reuseForks`
  counters.

Contrast with sbt (what to avoid): kyo's own `build.sbt` shows exactly the
failure mode a `Workers` module should not inherit. Test forking there is
**scattered and imperative** rather than declarative:

- A single global cap lives in `Global / concurrentRestrictions` via
  `Tags.limit(Tags.ForkedTestGroup, forkLimit)` (`build.sbt:107`), computed
  from ad hoc environment-variable sniffing (`isCI`, `SBT_TASK_LIMIT`) rather
  than a typed `Config`.
- Per-module worker routing is done by hand-writing `Test / testGrouping :=`
  blocks that construct `ForkOptions` directly and detect routing by
  string-matching bracketed markers in test names, e.g. `[podman]` /
  `[docker]` (`build.sbt:2557-2562`), to split single suites across two
  container-runtime forks. That is a `Task.locks`/routing requirement
  expressed as a source-level convention nobody can discover without reading
  the build file's comments.
- The global cap and the per-module `testForkedParallel`/`testGrouping`
  settings are two independent mechanisms that happen to compose correctly
  only because someone reasoned through sbt's tag-resolution semantics
  (`build.sbt:88-91` explicitly documents having to replace, not append,
  `concurrentRestrictions` because sbt takes the *most restrictive* of
  duplicate `Tags.limit` entries). A `Workers` design should make "declare a
  cap, declare a routing requirement" compose by construction, not by an
  engineer re-deriving resolution order and leaving a comment explaining why.

## Steal / avoid

- **Steal (highest value)**: Surefire's `PpidChecker` dual liveness check
  (NATIVE ps-based parent monitoring + PING channel heartbeat, 30s timeout,
  self-`Runtime.halt(1)` on parent loss) for orphan-free worker termination —
  this is the mechanism that keeps a killed coordinator from ever leaking a
  worker JVM, which Gradle core still doesn't have after years of open bug
  reports (§7c).
- **Steal**: Test Distribution's string requirements/capabilities matcher
  (key-value, expression, bare tag) for `Task` ↔ `Task.Queue` routing —
  richer than a name-based pool selection and self-documenting.
- **Steal**: Surefire's `<N>C` core-multiplier syntax for parallelism config,
  its two-timeout split (work deadline vs. shutdown grace period), and its
  crash-vs-failure distinction (fail loud on an unexplained fork death rather
  than silently dropping or silently passing the in-flight unit).
- **Steal**: reuse-by-default with isolation as an explicit opt-in, per both
  tools' defaults; Test Distribution's partition-scoped (not task-scoped)
  retry-and-reschedule on worker loss; and the "retry succeeded is still a
  signal" posture of `failOnPassedAfterRetry`/`failOnFlakeCount` plus their
  two-level retry caps (per-unit and whole-run circuit breaker).
- **Avoid**: Gradle core's static round-robin fork assignment with no runtime
  rebalancing — a known, tracked weakness; prefer Test Distribution's
  historical-duration-based balanced partitioning if `Workers` ever needs
  load-aware scheduling.
- **Avoid**: Gradle core's missing self-termination path for workers (§7c/§7d)
  — a currently-open, multi-year bug class (orphaned worker JVMs surviving a
  killed daemon and blocking the next build) that exists purely because the
  worker has no independent way to notice its owner is gone; don't reproduce
  this by omission.
- **Avoid**: sbt's pattern of a global concurrency cap and per-module routing
  logic as two separately-reasoned-about mechanisms glued together by
  hand-written `ForkOptions` and string-marker conventions; `Workers`' cap and
  routing should be one composed, declarative model from the start.
