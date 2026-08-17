# Prior-art synthesis for `Workers`

Consolidates the 12 system reports in this directory into decisions for the `Workers` design
(`../kyo-test-orchestrator-design.md`). Each claim points to the source report for detail.

## The 12 systems, single best idea each

| System | Report | The one thing to take |
|--------|--------|-----------------------|
| Ninja | `ninja-pools.md` | named pool with `depth`, opt-in via `pool=`; depth can only TIGHTEN under global `-j`, never loosen |
| Bazel tests | `bazel-tests.md` | `size`→(resources+timeout tier); resource bin-pack with a "always schedule at least one" starvation guard |
| Bazel workers | `bazel-workers.md` | `WorkerKey` = hash(mnemonic, flags, env) as pool identity; stdout is protocol-only; per-key cap with NO global cap is their documented pain |
| Ray | `ray.md` | task vs actor == our stateless vs stateful; `max_restarts` (state lost) vs `max_task_retries` (resubmit) as two knobs |
| Celery | `celery.md` | 3 independent failure knobs (ack-before / acks_late / reject_on_worker_lost); backoff+max+jitter; hard vs soft time_limit |
| Slurm+K8s | `cluster-schedulers.md` | K8s requests(admission) vs limits(enforcement), `weight` is requests-only; Slurm partition == `Task.Queue`; array `%N` == per-queue cap under global |
| pytest-xdist | `pytest-xdist.md` | opt-in affinity (`xdist_group`) resolved by the scheduler; dynamic pull-queue default; `--max-worker-restart` is a POOL breaker, separate from per-test retry |
| Gradle+Surefire | `gradle-surefire.md` | warm-reuse is the DEFAULT, fresh is opt-in; requirements/capabilities matching (Test Distribution) as an alt to pick-by-name; two timeout knobs |
| xUnit/NUnit | `dotnet-parallel.md` | xUnit `[Collection]` == named `locks` (near-exact); `ICollectionFixture` create-before-first/dispose-after-last warm lifecycle |
| jest-worker + Py pools | `worker-pools.md` | `computeWorkerKey` sticky routing; jest per-call retry (good) vs Python whole-pool poison (avoid); pickle can't ship closures → name+args |
| Erlang/OTP | `erlang-otp.md` | poolboy `size + max_overflow` + explicit block-vs-reject; supervisor restart-intensity sliding-window breaker (escalate, don't loop) |
| Spark+Dask | `spark-dask.md` | closure-shipping needs Serializable + a ClosureCleaner check + a fork-time error; Dask `resources={}` declarative per-call |

## Conclusions by design area

### 1. Two-tier cap (global + per-task), VALIDATED, keep the true global cap
The `Config(parallelism=N)` global cap + per-`Task` declaration is a **converged, proven** shape:
xUnit/NUnit (`MaxParallelThreads`/`LevelOfParallelism` ceilings + per-test attrs), Bazel
(`--local_resources` + per-target), Slurm/K8s (partition/node caps + per-job requests). Crucially,
**the absence of a global cap is a documented pain** in both Celery (none exists) and Bazel workers
(per-key only, bazelbuild/bazel#12165). So `Workers.run(Config(parallelism=N))` as a TRUE global cap
is a deliberate improvement to keep. Per-queue `maxWorkers` nests under it and can only tighten
(Ninja depth; Slurm array `%N`).

### 2. Queues / routing / affinity, one real fork to decide
Two viable models surfaced:
- **Pick-by-name** (our current `task.queue(q)`): simplest; matches Slurm partitions and Celery
  queue subscription. xUnit collections and pytest `xdist_group` confirm **named, opt-in, per-task**
  grouping resolved by the scheduler (not caller-picks-worker-index).
- **Requirements/capabilities matching** (Gradle Test Distribution): a task declares needs
  (`classpath=…`, `memory>=…`, tags) and workers advertise capabilities; the scheduler matches, with
  an **auto-injected implicit requirement** (e.g. jdk version) so a mismatch can't silently misroute.
  More declarative and misroute-proof, more machinery.
- Celery's separate warning: keep queue *declaration* distinct from *routing*, don't collapse "which
  pool exists" and "which pool this task wants" if we ever add routing rules.
- **Recommendation**: start pick-by-name (our `Task.Queue`), but adopt Gradle's **auto-injected
  implicit requirement** idea so a task can never land on a classpath-mismatched worker.

### 3. weight / resources, requests-only, scalar for v1
- Model `weight` as **admission-only** (K8s "requests"), never runtime enforcement, Workers has no
  enforcement layer and shouldn't invent one.
- Keep it a **scalar** for v1; Slurm `--gres` is the pattern if it ever needs to be a vector.
- Steal Bazel's **starvation guard**: always schedule at least one task even if it exceeds the
  budget, so a heavy task can't deadlock the pool.
- Avoid Bazel's dimension asymmetry (CPU override but not RAM) and be explicit (Ray) that `weight` is
  a scheduling number, not an isolation guarantee.

### 4. locks / exclusion, named locks good; `.exclusive` semantics need a fix
- xUnit `[Collection]` validates **`Task.locks` as named mutexes** almost exactly (same-name serial,
  different-name parallel). Adopt directly.
- **`.exclusive` semantics are underspecified and likely wrong as sketched.** Two warnings:
  Ninja's `console` "exclusive" is narrower than a full halt (it only buffers output; other work
  keeps running), and NUnit's single global non-parallel bucket over-serializes. If `.exclusive` just
  "holds the global lock" and only exclusive tasks hold it, then it does NOT exclude ordinary tasks.
  To mean "runs alone against everything," it must be a **read-write style barrier** (every task
  holds the global lock shared; `.exclusive` takes it exclusively) or an explicit drain barrier.
  Decide this deliberately.
- Keep `.exclusive` a plain boolean (Slurm `--exclusive`), and keep named `locks` for
  "exclusive with THIS group" (K8s anti-affinity's real job, better served by named locks).

### 5. Worker lifecycle & warm state
- **Default = warm reuse; `.isolated` = opt-in fresh.** Gradle (`forkEvery=0`) and Surefire
  (`reuseForks=true`) both default to reuse; validates our default.
- **Stateful queue == Ray actor == OTP gen_server == xUnit `ICollectionFixture`.** Steal the
  lifecycle contract: **init once before the first task, dispose after the last** (`ICollectionFixture`).
- **Two-number pool sizing** (OTP poolboy `size + max_overflow`) is worth considering over a single
  `maxWorkers`: a steady size plus an elastic ceiling, with **explicit block-vs-reject backpressure**
  on checkout rather than a hidden unbounded queue.
- **Warm state is pool-wide today everywhere** (jest `setup`, Python `initializer`, poolboy): warm
  state that varies *within* a queue is new ground, keep warm state keyed by the queue, not per task.
- Recycling vs warm-start tension (Python `max_tasks_per_child` forces `spawn`, disabling `fork`
  reuse): `.isolated`/recycle and warm reuse are mutually exclusive per task, make that explicit.

### 6. Failure, retry, reliability (the addendum) - the richest area
The reliability sweep (each report now has a full reliability section) produced two strong convergent
findings plus a detailed model. The top two are near-universal and load-bearing.

**A. Orphan-free kill needs a KERNEL tether, not signal forwarding (strongest convergence).**
Every mature system that gets this right uses an OS-enforced parent-death mechanism; every one that
relies on signals/process-groups alone has a documented orphan-leak bug:
- **Linux `PR_SET_PDEATHSIG`** (kernel SIGKILLs the child when the parent dies): Celery, Bazel tests.
- **Process-group / `killpg` / cgroup containment** torn down atomically: Ninja (POSIX `killpg`),
  Bazel `process-wrapper`, Slurm `proctrack/cgroup`.
- **Windows Job Object with `KILL_ON_JOB_CLOSE`** is the required primitive; Ninja's Windows fallback
  is cooperative-only and is a live orphan gap at Chromium scale.
- **Worker-side self-halt**: Surefire's `PpidChecker` has the fork itself poll the parent (`ps` plus a
  PING channel) and `Runtime.halt` if the parent is gone, a belt to the coordinator's braces.
- Cautionary leaks: Gradle (GRADLE-3298, multi-year), pytest-xdist (#2498 zombies), Ray (subreaper is
  opt-in, docs call it "a last resort"), Bazel workers (`bazel shutdown` leaves workers, #1868).
- **Conclusion**: `Workers` makes orphan-free kill **structural, not opt-in**: a kernel tether
  (PR_SET_PDEATHSIG / Job Object) plus process-group/cgroup containment plus worker self-halt on
  parent death. No JVM `PR_SET_PDEATHSIG` equivalent exists, so the worker-side parent-liveness watcher
  is a real, cross-platform design item.

**B. Hung-task detection is the near-universal GAP; enforce it coordinator-side.**
Almost nobody kills a stuck-but-alive task, and it is cited as pain everywhere: Ray (no execution
timeout; `ray.get(timeout)` only bounds the caller), pytest-xdist ("waits forever"; the signal-based
`pytest-timeout` only works on the main thread), Ninja (hangs the whole build), Bazel workers (#10288
deadlock, no watchdog). The clincher: xUnit/NUnit prove a **thread** cannot be force-killed on a modern
managed runtime (`Thread.Abort` was removed in .NET 5+, so NUnit's `[Timeout]` is obsolete and
`[CancelAfter]` is cooperative-only, unable to stop a test that never checks the token).
- **Conclusion**: enforce `Task.timeout` from the **coordinator** via a watchdog that **force-kills the
  worker process**; never trust the child to self-interrupt via a signal a threaded/native/forked
  runtime may not deliver. The process boundary is precisely WHY `Workers` can do what an in-process
  runner cannot; this is the design's biggest reliability differentiator.

**C. The rest of the model:**
- **Two-tier `timeout`**: work deadline, then a graceful cooperative-stop window, then force-kill; plus
  a separate "still hasn't died after kill" escalation (Slurm KillWait, then UnkillableStepTimeout,
  then drain). Two independent timers is the pattern (Spark heartbeatInterval vs network.timeout,
  Surefire work vs exit grace, Celery soft vs hard), and Spark's own gotcha is to widen the timeout,
  not the interval.
- **Retry SEPARATE from a pool circuit-breaker**: per-`Task` bounded retry AND a pool-level
  deaths-before-the-run-fails counter (pytest `--max-worker-restart`, OTP restart-intensity sliding
  window that escalates, Gradle/Surefire per-unit `maxRetries` plus whole-run `maxFailures`). Surface
  "passed only after retry" (Surefire `failOnPassedAfterRetry`); consider "stop routing to a serial
  offender" (Spark exclusion).
- **Default at-most-once; retry opt-in; idempotency is a documented obligation.** No system enforces
  that a retried unit is safe to re-run (Spark conditions correctness on determinism, Dask asserts
  nothing, Ray/Celery make at-least-once opt-in, Bazel makes hermeticity an author contract). So
  `Task.retry` must document: a retry re-executes the thunk from scratch, so enable it only for
  idempotent thunks. Compiler ops are idempotent; arbitrary side-effecting test thunks are not.
- **Explicit heartbeat decoupled from task traffic** (Celery heartbeat + gossip, Slurm SlurmdTimeout
  plus a separate HealthCheckProgram, Dask Nanny as an independent supervisor), which catches a worker
  wedged in init/warm-state, not just mid-task. NOT Bazel workers' lazy failure-on-next-response.
- **Per-Fiber failure isolation, not whole-pool poison**: jest resends the pending request to a fresh
  child (note: that is at-least-once and can duplicate side effects); Python `BrokenProcessPool`
  poisons the whole executor. Follow jest's blast radius, not Python's.
- **The coordinator holds the authoritative ledger** of "dispatched, not yet acked": worker output is
  unreliable at crash time (Surefire SUREFIRE-1945), so a vanished worker's in-flight task is known
  from the ledger, not from parsing worker logs. Crash is a first-class outcome distinct from task
  failure (Surefire "terminated without properly saying goodbye" -> fail loud, never silent-drop/pass).
- **Recycling** by policy: after N tasks (Python `max_tasks_per_child`, which forces spawn and disables
  fork-reuse), or a memory threshold (jest `idleMemoryLimit`), or any crash/timeout; combine count plus
  memory signals; mind Celery's amortization trap (too-aggressive recycling caps throughput on restart
  cost). Going further than prior art: factor crash/health history into recycling.
- **Graceful shutdown is multi-tier** (Celery warm/soft/cold/hard, Surefire graceful/forced/self-halt)
  and **backpressure = queue behind the cap** (Slurm PENDING), not admission-reject (K8s); pytest's
  watermark-bounded per-worker chunks make both load-balancing and crash-loss cheap.
- backoff, max, and **jitter** for retry scheduling (Celery, thundering-herd); policy in the
  coordinator, never in the thunk (OTP let-it-crash).

### 7. Results / futures, `Fiber` already wins
`submit→Future` (Dask, Python) and Promise-per-call (jest) are structurally our `fork→Fiber[A,E]`;
Fiber gives join/interrupt/race for free (weaker in Celery `AsyncResult`, absent in Spark). Steal two
patterns: re-raise the worker's real exception on the caller's `.get` (Python) → our `E` channel; and
`ray.wait(refs, num_returns=k, timeout=)` for bounded fan-in beyond join-all.

### 8. Closure shipping, the real tension, with a recommendation
Two positions:
- **spark-dask verdict**: feasible on the JVM only in Spark's regime (captures fully `Serializable`);
  the JVM has no cloudpickle by-value equivalent. Recommend committing to **serializability as a
  hard, checked requirement** plus a `ClosureCleaner`-style static check and a precise
  `Task not serializable`-quality error **at `fork()` call time**, not after a round trip.
- **worker-pools caution**: jest ships name+args (no code); Python can't pickle lambdas/closures under
  `spawn`. Recommend modeling `thunk` as a **named task type resolved in the worker's pre-loaded
  classpath**, not an arbitrary closure capture.
- **Reconciliation for us**: our real thunks already sit in the safe subset, e.g.
  `TestRunner.runSuite(className)` closes over a `String` and references top-level objects, which is
  exactly "named entry + serializable args." So keep the ergonomic `fork(thunk)` surface, but (a)
  enforce serializability with a fork-time check + great error (Spark), and (b) document/steer users
  toward top-level-object + serializable-data captures (jest). Do NOT promise arbitrary-closure magic.

### Transport note
Dedicated channel, never shared stdout: Surefire's pipe and Bazel's protocol both break on native-lib
stdout pollution ("stdout is protocol-only"). Validates the aeron/dedicated-IPC choice; keep worker
stdout/stderr OUT of the protocol channel.

## New / sharpened decisions this survey produces

1. **Keep the global cap** as a true cross-queue cap (Celery/Bazel gap → our improvement).
2. **Fix `.exclusive`**: define "runs alone" precisely (RW-global-lock or drain barrier), don't ship
   the ambiguous "holds a lock only exclusive tasks hold."
3. **Two-tier `Task.timeout`** (work deadline + graceful-exit grace).
4. **Split retry from the pool circuit-breaker**; retry is bounded, at-least-once, idempotent-only.
5. **Explicit heartbeat/liveness** and first-class cancellation with timeout-then-kill.
6. **Per-Fiber failure isolation**, not whole-pool poisoning.
7. **`weight` = admission-only scalar**, with a Bazel-style "always schedule one" starvation guard.
8. **Auto-injected implicit requirement** (classpath/jdk) so a task can't misroute, on top of
   pick-by-name queues.
9. **Warm state**: `init-before-first / dispose-after-last`, queue-keyed; consider `size + overflow`
   pool sizing with explicit backpressure.
10. **Closure shipping**: serializability as a checked contract with a fork-time error; steer toward
    named-entry + serializable-args; no arbitrary-closure promise.
