# Fixing the per-suite cost of kyo-test's end-of-run leak check

Target defect: every forked test JVM that keeps a process-lifetime event loop alive spends the whole
30-second `idleBudgetNanos` at its sbt `done()` boundary, then correctly reports nothing. Measured on
run [30699720019](https://github.com/getkyo/kyo/actions/runs/30699720019): 107 occurrences worth
56.5 min on linux-x64/JVM (27% of a 3h27m job), reproduced to within 1% on the prior main run.

Confirmed locally, not just inferred (section 6): a kyo-http suite whose tests take 211ms spends
31.0s at `done()`, while a kyo-data suite taking 195ms spends 1.3s. The defect reproduces on macOS in
about a minute, which is the implementation loop.

The constraint driving this design: **do not weaken the checks**. The recommendation below removes
the wait without changing which forks are reported, and closes one existing hole in the process.

## 1. What `awaitSchedulerIdle` actually protects

From `LeakCheck.awaitSchedulerIdle` and its call site in `LeakCheck.detect`, the wait serves two
distinct purposes. Both must survive the fix.

**Purpose A: decide the fiber-leak verdict without false positives.** The docstring is explicit:

> The settle window lets transient tail activity (a reporter fiber, a finalizer) drain before a
> verdict, so only work that persists past the budget is reported as `Busy`.

The class this exists to catch is named in the file header: "runnable/spinning fiber leaks (a fiber
pegging or repeatedly rescheduling onto a worker, the class the async-merge spinning-producer bug
produced)". A false negative here lets a test leave a fiber spinning on a scheduler worker forever,
burning a core for the rest of the JVM's life and, in a non-forked context, for the rest of the
process. The header is equally explicit about what is already out of scope: "a fiber parked on a
still-reachable promise/channel is off-scheduler and invisible to scheduler status".

**Purpose B: quiesce before the thread and descriptor diffs.** From `detect`:

> Always settle on scheduler quiescence first: it lets in-flight fibers finish and release their
> resources before the thread and descriptor diffs run, which trims false positives for every
> category.

A false negative here means the thread and fd diffs run while a test's fibers are still mid-teardown,
reporting resources that were about to be released. That is a false *positive* risk, not a missed
leak, but it is why the wait runs even when `checkFibers` is off.

So the property to preserve is: **when the wait returns, no work that could still belong to a test is
running on the scheduler**, and the fiber verdict is unchanged.

## 2. Why the budget is spent today

`loadAvg()` (`kyo-scheduler/.../Scheduler.scala:392`) is "the number of queued plus executing tasks
per worker". A process-lifetime event loop occupying a worker keeps `load() > 0` forever, so
`loadAvg() != 0` never clears and the loop below runs to the deadline every time:

```scala
while result.isEmpty && System.nanoTime() < deadline do
    val now = System.nanoTime()
    if loadAvg() == 0.0 then
        if idleSince < 0 then idleSince = now
        else if now - idleSince >= settleNanos then result = Maybe(IdleResult.Idle)
    else idleSince = -1L
    if result.isEmpty then LockSupport.parkNanos(pollNanos)
```

The decisive detail is the ordering in `detect`. The allowlist that already declares this work
intentional is consulted **only after** the budget has been spent:

```scala
awaitSchedulerIdle(idleBudgetNanos, settleNanos, pollNanos) match
    case IdleResult.Idle => ()
    case IdleResult.Busy(la, frame) =>
        if checkFibers then
            ...
            val allowlisted = effectiveAllowlist.exists(matchText.contains)
            if !allowlisted then findings += ...
```

`LeakCheck.defaultAllowlist` is `Chunk("processSharedTransport")`, which by its own docstring exists
because kyo-net's process-lifetime transport carriers "sit armed at every net- or http-using module's
end-of-run check". `BaseBrowserTest` confirms the same fibers are live and deliberately left checked:

> The other long-lived resource, the kyo-http NioIoDriver event-loop fiber, is already covered by the
> built-in allowlist, so fiber and thread detection stay on.

So the sequence in a browser or ui fork is: wait 30s for an event loop that will never stop, then
match it against the allowlist, then report nothing. The verdict is right; the wait is pure waste.

**The same file already does this correctly for descriptors.** `awaitFdDrain` applies the allowlist
*before* it waits, inside `leaksNow()`, via `fdLeaks(before, _, effectiveAllowlist)`, so an
allowlisted descriptor never enters the set and "an empty first sample returns immediately, so a
clean run pays nothing". The fix is to make the scheduler wait consistent with the descriptor wait.

### Why the cost scales the way it does

The budget is spent once per `done()`, which is once per forked JVM. That predicts the observed
distribution exactly:

| module | forking | `done()` per run | measured gaps |
|---|---|---|---|
| kyo-net, kyo-http | one fork per module (`fork := true`) | 1 | 1 gap each (34s, 33s) |
| kyo-browser, kyo-ui | one fork per test class (`build.sbt:2549`, `build.sbt:2670`) | one per suite | 43 and 54 gaps |
| kyo-pod | one fork per suite, per runtime | one per fork | 8 gaps |

Nothing else in the codebase reproduces that pattern, which is strong corroboration that this budget,
and not some other 30s, is the consumer.

## 3. Candidate designs

### A. Allowlist-aware quiescence (recommended)

**Mechanism.** Redefine the wait's predicate from "no work" to "no *unaccounted* work": quiescent
when `loadAvg() == 0`, or when every worker currently holding work is matched by the effective
allowlist. Keep the settle window: the predicate must hold continuously for `settleNanos` before the
wait returns, exactly as today. Rate-limit the expensive half of the predicate to at most one
evaluation per settle window, since `Scheduler.busyFiberTraces()` renders a kyo trace per busy worker
and its docstring warns it is "a leak-probe diagnostic, not a monitoring surface".

**What it preserves.** The fiber verdict is computed from the same allowlist against the same busy
worker traces and stacks that `detect` already uses, so no fork changes classification. Purpose B is
preserved because the predicate can only become true once every non-allowlisted worker has drained;
the only work still running is the process-lifetime infra, which by definition is not holding a test's
resources. A fork with genuinely unaccounted work still waits the full 30s and is still reported.

**What it gives up.** Nothing in detection power. The cost is one extra concept in the wait
(`Accounted` alongside `Idle`) and a slightly more intricate loop.

**Blast radius.** `kyo-test/runner/jvm` only: `LeakCheck.awaitSchedulerIdle`, `LeakCheck.IdleResult`,
one new helper, and the call in `detect`. No scheduler change, no module change, no build.sbt change,
no test-suite change anywhere in the repo. Every module that pays the cost today is fixed without
editing it.

**Behaviour for a genuinely leaking fork.** A spinning fiber whose trace and stack match no allowlist
pattern makes the predicate false, so the loop runs to the deadline and returns `Busy` with the same
report as today. Detection latency and content are unchanged.

### B. Register intentional long-lived fibers with the scheduler

**Mechanism.** Give the scheduler (or `kyo.internal.Diagnostics`, which `StrandedOpCheck` and
`TeardownViolationCheck` already use as a registration point) a way to mark a task as process-lifetime
infrastructure, and add an `unaccountedLoad()` that excludes it. The wait then polls that instead of
`loadAvg()`.

**What it preserves.** Everything A preserves, and it expresses "idle means no unaccounted work" at
the source rather than at the probe, which is conceptually cleaner and would also benefit anything
else that reasons about scheduler quiescence.

**What it gives up.** It introduces a second mechanism for a job the allowlist already does, and the
two would have to be kept in agreement. It touches kyo-scheduler, a foundation module whose
`loadAvg()` the regulators use "to make admission and concurrency decisions"; a new parallel method
avoids perturbing that but duplicates the worker walk. Every long-lived-infra site (kyo-net's drivers
at minimum, plus anything else that parks an event loop) must be found and updated, and a site that is
missed silently keeps paying the 30s.

**Blast radius.** kyo-scheduler plus kyo-net plus every future long-lived-infra site. Substantially
larger than A for the same measured saving.

**Behaviour for a genuinely leaking fork.** An unregistered busy fiber is still counted and still
reported. Sound, but the failure mode of a *wrongly* registered fiber is a silent false negative,
which is worse than A's failure mode (a wrongly allowlisted pattern, which is at least visible in the
suite's config).

### C. Stop forking per suite in kyo-browser and kyo-ui

Rejected on evidence already in the build file. `build.sbt:2549` records that a single shared Chrome
was tried and reverted: "Cross-suite Chrome state degradation makes a single shared Chrome unstable
over 700+ tests in a 10-minute run", and running the per-suite forks concurrently "was tried and
reverted: cores/2 simultaneous Chrome processes starve each other". The per-suite fork is load-bearing
for stability; the leak check should stop punishing it, not the other way round.

### D. Lower the budget, or add an opt-out flag

Rejected as the weakening the constraint forbids. The comment at `SbtRunner.scala:135` records that
2s was already tried and was insufficient: "2s was tuned on an unloaded box and is not enough on a CI
runner with four contended cores". Any value large enough to keep that property is large enough to
keep the cost. An opt-out flag would disable fiber detection for exactly the modules that run the most
concurrent I/O, which is where a spinning-fiber leak is most likely.

## 4. Recommendation

**Adopt A.** It is the smallest change that removes the cost, it reuses the mechanism the codebase
already uses to declare this work intentional rather than inventing a second one, it makes the
scheduler wait consistent with the descriptor wait in the same file, and it is confined to
`kyo-test/runner/jvm`. B is defensible and strictly cleaner in the abstract, but it duplicates the
allowlist's job across two more modules and its failure mode is a silent false negative.

### Concrete changes

`kyo-test/runner/jvm/src/main/scala/kyo/test/runner/internal/LeakCheck.scala`:

1. `IdleResult` gains a case distinguishing "nothing running" from "only accounted work running", so
   the report can stay truthful and `detect` can skip the fiber finding without pretending the
   scheduler was idle:

```scala
enum IdleResult derives CanEqual:
    case Idle
    case Accounted(loadAvg: Double)
    case Busy(loadAvg: Double, frame: Maybe[String])
```

2. A new predicate, factored out so the wait and the verdict share one definition of "accounted".
   Note `busy.nonEmpty`: an empty busy set is `Idle`, never `Accounted`.

```scala
/** True when at least one worker holds work and EVERY such worker is matched by `allowlist`,
  * against either its kyo trace or its JVM stack. This is the same predicate `detect` uses to
  * excuse a fiber finding, so waiting on it cannot change any fork's classification.
  */
def busyWorkAllAccounted(allowlist: Chunk[String]): Boolean =
    val busy = Scheduler.get.busyFiberTraces()
    busy.nonEmpty && busy.forall { w =>
        val text = w.fiberTrace + "\n" + stackOfThread(w.mount).getOrElse("")
        allowlist.exists(text.contains)
    }
```

3. `awaitSchedulerIdle` splits into a canonical, fully injectable form and a production binding, per
   the repo's "overloads delegate to canonical" rule and matching how `awaitFdDrain` already takes its
   probe as a parameter so it can be unit-tested without a live scheduler:

```scala
def awaitSchedulerIdle(
    budgetNanos: Long,
    settleNanos: Long,
    pollNanos: Long,
    loadNow: () => Double,
    allAccounted: () => Boolean
): IdleResult

def awaitSchedulerIdle(budgetNanos: Long, settleNanos: Long, pollNanos: Long, allowlist: Chunk[String]): IdleResult =
    awaitSchedulerIdle(budgetNanos, settleNanos, pollNanos, () => loadAvg(), () => busyWorkAllAccounted(allowlist))
```

The canonical loop keeps today's structure and changes only the predicate. `quiet` is
`loadNow() == 0.0` or, re-evaluated at most once per settle window, `allAccounted()`; the window must
hold continuously as it does today; on expiry the fallback returns `Idle`, `Accounted`, or `Busy` from
one final sample.

4. `detect` passes `effectiveAllowlist` into the wait and treats the new case as a non-finding:

```scala
awaitSchedulerIdle(idleBudgetNanos, settleNanos, pollNanos, effectiveAllowlist) match
    case IdleResult.Idle | IdleResult.Accounted(_) => ()
    case IdleResult.Busy(la, frame) => ... unchanged ...
```

`SbtRunner.scala` needs no change beyond the comment at line 132, which should stop claiming the
budget is never spent and instead say it is spent only by a fork with unaccounted work.

### A separable strengthening, to land as its own commit

Today's verdict is `effectiveAllowlist.exists(matchText.contains)` where `matchText` is the
concatenation of *all* busy workers' traces, so one allowlisted worker excuses every other busy
worker in the same fork, including a genuinely leaked one. `busyWorkAllAccounted` uses `forall`, which
is the correct rule. Applying that same `forall` to the finding in `detect` closes the hole and makes
the wait and the verdict share one predicate.

This is a strengthening, so it may surface real leaks that currently pass, and those are the work it
creates. Keep it in a second commit so the performance fix is not blocked by whatever it finds, and so
a bisect can tell the two apart. It is required work, not optional.

## 5. Verification

**That the check still catches a real leak.** `LeakCheckTest.scala` already tests `awaitFdDrain` in
both directions with an injected probe; the canonical `awaitSchedulerIdle` above is testable the same
way, with no live scheduler:

- `awaitSchedulerIdle` returns `Busy` and consumes the whole budget when `allAccounted` is false and
  load is non-zero. Assert both the result and that elapsed time is at least the budget, so a
  regression that short-circuits the wait fails.
- returns `Accounted` well inside the budget when load is non-zero and `allAccounted` is true. Assert
  elapsed is under, say, a quarter of the budget, which is the regression guard for this whole fix.
- returns `Idle` when load is zero, unchanged from today.
- does not return `Accounted` on a single favourable sample: a probe that reports accounted, then
  unaccounted, then accounted must not satisfy the settle window early.
- `busyWorkAllAccounted` returns false when any busy worker is unmatched, and false when nothing is
  busy.

Beyond the unit tests, the end-to-end proof that detection is intact is that the existing suites still
pass with fibers and threads checked: kyo-browser, kyo-ui, kyo-net, kyo-http and kyo-pod all keep
`leakCheckFibers` and `leakCheckThreads` on, so a broken predicate that excused everything would have
to be caught by deliberately introducing a spinning fiber. Add that as a fixture suite under
`kyo-test/runner/jvm/src/test` that leaks a spinning fiber and asserts `detect` reports it.

**That the 30s is actually gone.**

- Local, the fastest loop, and already measured as the before number (section 6): time the interval
  between `Results:` and `[success] Total time` for `sbt 'kyo-httpJVM/testOnly kyo.HttpRequestPartTest'`.
  It is 31.0s today and must fall to roughly the 1.3s that `sbt 'kyo-dataJVM/testOnly kyo.MaybeTest'`
  takes. This needs no Chrome and no Linux, so it is the iteration loop while implementing.
- Local, the per-suite case: run two browser suites in sequence and time the boundary between the
  first suite's `Results:` line and the second's `Running 1 suite(s)` line. Today that is 31.5s; after
  the fix it should be about one settle window plus fork restart.
  `sbt 'kyo-browserJVM/testOnly kyo.ResolverTest kyo.BrowserMutationTest'`
- CI, the aggregate: `scripts/ci-analyze.py gaps <run-id> --min 25` reports 107 gaps and 56.5 min on
  linux-x64/JVM today and should report approximately zero after. The same command on
  `linux-arm64/JVM` should drop from 70 gaps and 36.8 min. `scripts/ci-analyze.py timeline <run-id>`
  gives the job-level before and after.
- Sanity on a module that forks once: kyo-net and kyo-http each show exactly one such gap today and
  should show none, which confirms the fix works independently of the per-suite forking.

## 6. The diagnosis, confirmed by direct measurement

Everything in section 2 is read directly from the source: the loop that cannot exit while
`loadAvg() != 0`, the allowlist consulted only after the wait, the `defaultAllowlist` docstring saying
these carriers "sit armed at every net- or http-using module's end-of-run check", and
`BaseBrowserTest` stating the NioIoDriver event-loop fiber is live and allowlisted while fiber
detection stays on. The fork-count table in section 2 is a quantitative prediction that matches the
measured gap counts across five modules with different forking strategies, which is the strongest
available corroboration short of instrumenting a run.

Two things sharpen the case further:

- For kyo-browser and kyo-ui, `checkFileDescriptors` and `checkSockets` are both off
  (`BaseBrowserTest.scala:24`, `UITest.scala:20`), so `detect` skips the descriptor block entirely and
  `fdDrainBudgetNanos` cannot be the consumer in those forks. Exactly one 30s budget is reachable,
  which matches the measured 31.5s; two would have shown roughly 61s.
- The two other 30s budgets on the browser teardown path were already excluded in the parent
  investigation: `BrowserLauncher.removeTmpDir` logs a warning when its schedule is exhausted and no
  such warning appears in any job log, and `Command.spawn`'s scope release calls `destroyForcibly()`
  with no grace.

### Observed, not just inferred

The parent investigation stopped at inference. Two local runs on this machine close that gap. Both
were run with the repo's standard `JAVA_OPTS`, back to back, timing the interval between the suite's
`Results:` line and sbt's `[success] Total time`:

| command | tests took | `done()` boundary |
|---|---|---|
| `sbt 'kyo-httpJVM/testOnly kyo.HttpRequestPartTest'` | 211ms | **31.0s** |
| `sbt 'kyo-dataJVM/testOnly kyo.MaybeTest'` | 195ms | **1.3s** |

Same harness, same JVM settings, comparable test times. The only material difference is whether a
process-lifetime event loop is live on the scheduler: kyo-http builds on kyo-net's process-shared
transport, kyo-data touches neither.

The macOS detail makes this airtight rather than merely strong. `openFdTargets()` reads
`/proc/self/fd`, which does not exist on macOS, so it returns `Absent` and `detect` skips the
descriptor block entirely (`case Maybe.Absent => ()`). `fdDrainBudgetNanos` is therefore unreachable
in both runs, leaving `idleBudgetNanos` as the only 30-second budget that can be spent. The 31.0s is
`awaitSchedulerIdle` running to its deadline, plus the settle window.

This also reproduces the defect outside CI and off Linux, so the fix can be validated locally in about
a minute per iteration: the kyo-http number should fall to roughly the kyo-data number.
