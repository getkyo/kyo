# Design: `Workers`, forked task execution over aeron

Status: exploration, no code. The API has converged on an **effect-based, thunk-forking `Workers`
module**; a 12-system prior-art survey is complete (`prior-art/00-synthesis.md`) and its conclusions
are folded in below. Grounded in `kyo-test-observability-analysis.md` (Findings 1-8) and
kyo-compiler's forked-worker pattern.

Naming note: the module is `Workers`. The per-group config value is currently `Task.Queue` (open:
`Queue` clashes with `kyo.Queue`; `Lane` is the clean alternative).

## What this is

A reusable kyo module, `Workers`, that runs serializable task **thunks** in forked worker JVM
processes over an aeron IPC transport, under a single global concurrency cap. It began as a kyo-test
orchestrator (to fix the parallelism + aggregate problems below) and generalized: **kyo-test is the
driving consumer, kyo-compiler is a validating second consumer** whose forked-pc-over-aeron pattern
is the template `Workers` generalizes.

## Motivation (what today's stack structurally cannot do)

1. **A bound on TOTAL parallelism.** Today the effective degree is
   `concurrent-forks (sbt) x globalK (LeafPool, per fork) x adaptive scheduler`, set across three
   files, and `globalK` is per-JVM, so there is no single knob for "at most N tests running across
   the whole suite" (Findings 4, 6, 7). Each module's fork has its own `LeafPool.global`.
2. **Globally-sequential tests.** `globallySequential` is a per-JVM `Meter` mutex; it only bites
   when suites share one JVM, so across forks it degenerates to `sequential` (Findings 4, 8). The
   single carrier today is kyo-ai's `BaseAITest`.

A single owner of the worker pool makes both trivial: the global cap is "how many tasks run at once",
and global-sequential is "a named lock a task holds". The same abstraction serves kyo-compiler
(per-config warm workers under a global compile cap), which is why it generalizes.

## The `Workers` API (current direction)

Effect-based, not an instance. Fork an arbitrary thunk; get a `Fiber`.

```scala
object Workers:
    /** Handle the effect: stand up the pool (driver, global cap, worker lifecycle), run
      * `computation`, tear it all down (kill every worker) on completion. The single once-per-run point. */
    def run[A, S](config: Config)(computation: A < (Workers & Async & S))(using Frame): A < (Async & S)

    /** Fork `thunk` to a worker process; get a Fiber (join/interrupt/race compose as usual). The task
      * starts when a permit is free (bounded by the enclosing run's parallelism). */
    def fork[A, E](thunk: => A < (Async & Abort[E] & Scope), task: Task = Task.default)(using Frame): Fiber[A, E] < (Workers & Sync)

    final case class Config(parallelism: Int, maxWorkersPerQueue: Int = Int.MaxValue,
                            idleEviction: Duration = 60.seconds, spawnTimeout: Duration = 30.seconds)
```

**Fork-a-thunk (the key simplification).** Capture the closure, serialize it, ship it to a
generic library-provided worker forked with a classpath (default = the closure's classloader
classpath); the worker deserializes and runs it and ships the result back. No per-use worker main,
no `serve`, no shared Request/Response protocol. Honest cost (the Spark/distributed-closure model):
the thunk + its captures + the result must be serializable, and it can only use effects the worker
can discharge itself (no capturing a host `Env`/`Var`/live resource). The `spark-dask` prior-art
report is specifically tasked with hardening this and the serialization footguns.

**Declarative config, three levels:**

- **Global**, `Workers.run(Config(parallelism = N, ...))`. The total-parallelism cap + lifecycle.
- **Queue** (a declarative *value*, not a runtime handle), `Task.Queue(name, Queue.Config(...))`: a
  named pool of like workers; tasks opt in via `task.queue(q)`. Same `name` == same pool. Carries
  the spawn profile (`classpath`, `jvmOptions`, `env`), `maxWorkers` (its structural concurrency),
  and, for stateful workers, a `warm` init.
- **Task**, `Task(queue, locks, weight, isolation, timeout, retry, env)`, immutable builder:
  `.queue(q)`, `.holding(key)`, `.exclusive` (== `holding(global)`), `.weight(n)`, `.isolated`,
  `.timeout(d)`, `.retry(r)`, `.env(k, v)`, plus `*When(cond)` sugar.

**Two serialization axes (the load-bearing insight).** `maxWorkers` (queue-level, structural: how
many of this queue run at once) and `locks` (task-level, cross-cutting exclusion) are different jobs
and both are needed. kyo-compiler serializes a config's ops with `maxWorkers = 1`; kyo-test excludes
across queues with `locks`. The **lock collapse**: per-worker serialization, global-sequential, and
shared-external-resource exclusion are all "a task holds named locks" (`.exclusive`,
`.holding("port:8080")`, `.holding("config:X")`).

**Stateless vs stateful workers.** Stateless (run a thunk) covers kyo-test. Stateful covers
kyo-compiler's warm pc: the queue carries `warm = () => S` (a serializable init run once per worker;
`S` stays in the worker), and tasks are `S => A`. `fork(thunk, task)` for stateless;
`fork(q)(s => ...)` for a stateful queue.

### Both consumers on this API

```scala
// kyo-test: one stateless queue per module; exclusive/isolated per suite; global cap = 8
Workers.run(Config(parallelism = 8)) {
  Kyo.foreach(modules) { m =>
    val q = Task.Queue(m.name, Queue.Config(classpath = m.testClasspath, jvmOptions = testOpts))
    Kyo.foreach(m.suites) { s =>
      Workers.fork(TestRunner.runSuite(s.className),
                   Task.default.queue(q).exclusiveWhen(s.globallySequential).isolatedWhen(s.needsFreshJvm))
    }
  }.map(joinAndAggregate)     // one place, once-per-run aggregate (fixes Findings 1-3/6)
}

// kyo-compiler: one stateful queue per config, maxWorkers = 1 (warm pc + serialize), no locks
Workers.run(Config(parallelism = maxConcurrentCompiles)) {
  def q(c: Config) = Task.Queue("compiler:" + c.id,
                       Queue.Config(classpath = c.pcClasspath, maxWorkers = 1, warm = () => startPc(c)))
  def op(c: Config, req: Req) = Workers.fork(q(c)) { pc => runOp(pc, req) }
}
```

Everything the current test stack fights for falls out: total cap = `parallelism`; global-sequential
= `.exclusive`; shared port/container = `.holding(key)`; browser isolation = `.isolated`; and because
it is all inside one `Workers.run`, the single whole-run aggregate is "join the fibers, then
aggregate". All the sbt `testGrouping`/`testForkedParallel`/`parallelExecution`/`ForkedTestGroup`
machinery is gone.

## Transport basis (kyo-compiler primitives we reuse)

kyo-compiler already runs forked JVM workers over aeron; its pieces are the transport foundation:

- **`AeronDriver`** shared embedded C media driver (Panama), one per pool; workers connect an
  **`AeronClient`** to its directory (`CompilerPool.scala:181-203`, `SpawnBackend.scala:198-199`).
- **`Command(...).spawnUnscoped`** to fork `java -cp <cp> <WorkerMain>`, lifetime owned by an
  explicit `close`/finalizer, module-opener flags forwarded (`SpawnBackend.scala:150-193`).
- **`kyo.aeron.Topic.publish`/`stream[Envelope]`** with distinct even/odd `stream-id`s per direction
  and a unique base per worker (`SpawnBackend.scala:82-92, 206-235`).
- **`kyo.Exchange`** owns request/reply id correlation, the reader fiber, and cleanup on a broken
  session (`SpawnBackend.scala:206-214`). (kyo-test streams results, so the shared layer needs a
  response STREAM, not only single-reply Exchange.)
- **Transport-break discipline**: a break maps to a typed error and fails pending ops instead of
  hanging; the worker ends and the host force-kills and respawns (`SpawnBackend.scala:29-44`).
- **Global cap + serialization** via `Meter` (`CompilerPool.scala:84, 164`) - the cap and the lock lane.
- **Readiness probe with kill-on-interrupt finalizer** so a worker that never starts leaks nothing
  (`SpawnBackend.scala:98-141`).

## `Workers` is the shared module (supersedes the earlier "extract later" framing)

An earlier draft argued "share the session/server, not the pool, and extract from a second consumer
later". The API exploration changed that: with a requirements/lock model, **the scheduler itself
generalizes**, and `Workers` IS that generic module. It is not a speculative abstraction, because
**kyo-compiler already has this structure internally** - `CompilerPool` = a global-cap `Meter` + a
per-instance mutex + a profile-keyed warm cache with eviction, and `Backend = Local | Spawn`. So
`Workers` is a generalization of working, tested code.

- **In `Workers`**: the effect + `run` + `fork` + `Task.Queue`/`Task`, plus the transport/session/
  server assembly (spawn/connect/ready/close/stream-id discipline; the worker serve loop; the `-D`
  handshake). Streaming results and worker-pull (kyo-test) vs single-reply/host-push (kyo-compiler)
  are both accommodated by sharing the raw framed session, not a fixed req/reply shape.
- **Per-consumer**: the message content and any domain policy. kyo-compiler adds its `LocalBackend`
  in-JVM fast path and the cancel-by-interrupt invariant; kyo-test adds discovery + aggregation +
  reporting.
- **Sequencing**: build kyo-test on `Workers` first; add the stateful path; adopt kyo-compiler onto
  `Workers` behavior-preservingly (its `SpawnBackendTest`/`WorkerTest` are the net) once proven.
- **Layering (Q7)**: `Workers` depends on kyo-aeron + kyo-core and pulls in the native driver, so it
  stays OUT of the base `kyo-test-runner` (which is on every module's test classpath, incl.
  kyo-kernel/kyo-data). A separate consumer artifact (`kyo-test-orchestrator`) depends on `Workers`
  and wires it in only at the JVM edge via the plugin. JVM-only; JS/Native keep the in-process runner.

## sbt + plugin integration

For kyo-test, the code that calls `Workers.run` and forks the suites is the coordinator. It lives in
the sbt session (or a child process), and the sbt Runner becomes a `Workers` consumer.

### Coordinator lifecycle: owned by the sbt session (no persistent daemon)

The pool's lifetime IS the session's. The plugin starts it at load and tears it down at session end;
`Workers.run` (via `Scope`) force-kills its workers on the way out.

- **Start / teardown.** `Global / onLoad` opens the pool; `Global / onUnload` plus a JVM shutdown-hook
  backstop close it, force-killing every worker. For `kill -9` on sbt where no hook fires, the worker
  layer self-terminates on parent-PID death or after idle. So "closes with the session, workers
  included" is fully met.
- **No daemon machinery.** Session lifetime means NO lockfile / liveness / stale / version-stamp dance
  (that is only for surviving ACROSS sessions; cross-session warm reuse is a possible future local-dev
  speedup, out of scope now).
- **Scope of "all test execution" = one sbt / CI-job invocation.** Separate CI jobs are correctly
  separate pools; a cap cannot and should not span machines.

### Where the pool + driver run: child process vs in the sbt JVM

This is about WHERE `Workers.run`'s pool lives; the **workers are forked JVM processes either way**.
sbt's classloader model, not the "single pool" requirement, is what forces the choice:

- sbt loads the test framework in a **per-project test classloader**, so a naive `object` singleton in
  the runner jar is instantiated **once per module**, not once per JVM - which yields per-module pools
  and DEFEATS a single cap.
- A single in-JVM pool across modules must live in a classloader shared by every module's test task,
  i.e. hosted by the **sbt plugin** (build classloader), with a cross-classloader bridge to the
  per-module Runners. Workable but fiddly and fragile across `reload`.
- A **session-owned child process** sidesteps the classloader problem: one process at a known aeron
  medium IS the singleton, and Runner-clients AND workers talk to it over the SAME transport uniformly.
  It adds one spawned process, which the design already does for workers.

**Recommendation:** a session-owned child-process coordinator, with in-JVM-plugin-hosted as the
fallback; prototype decides.

### The Runner becomes a `Workers` consumer (rework of `SbtRunner`/`SbtTask`)

- `runner(args, remoteArgs, testClassLoader)`: reach the pool; extract the module's worker classpath
  from `testClassLoader`'s URLs and use it as the queue's profile.
- `tasks(taskDefs)`: keep **one `SbtTask` per suite** so per-suite `EventHandler` routing and
  `testOnly`/IDE granularity survive.
- `SbtTask.execute`: `Workers.fork` the suite, await the Fiber, emit the same per-leaf `Event`s via
  `EventBuilder`, return.
- `done()`: still per module; the single whole-run aggregate/timing/report is the coordinator's.

### Plugin changes (concrete)

- `fork := false` wherever kyo-test runs (the Runner is a thin client). **Remove** the per-module
  `testGrouping`/`testForkedParallel`/`parallelExecution` overrides in kyo-browser/kyo-ui/kyo-pod/
  kyo-aeron; their isolation needs become `Task`/`Queue` config the coordinator honors.
- **Open sbt's own caps** (drop `Tags.ForkedTestGroup` and the `Test` limit; the coordinator owns the
  cap). Keep `SBT_TASK_LIMIT`/update caps for non-test work if wanted.
- Add the `kyo-test-orchestrator` artifact (Workers + worker main + transport) to the test classpath;
  add `--enable-native-access=ALL-UNNAMED`.
- Build settings: the cap (`-Dkyo.test.parallelism=N`), the coordinator medium, `onLoad`/`onUnload`.

### Wrinkles to design around

1. **Blocked sbt threads.** `Task.execute` is synchronous and must emit Events before returning, so
   each in-flight suite holds one sbt thread awaiting its Fiber. Bound how many suites sbt starts
   concurrently (above the coordinator cap so it stays fed), or drive the full run through `testKyo`
   -> coordinator under one aggregate task (loses per-suite `testOnly`). Likely support both.
2. **Start once** from `Global / onLoad` (no per-Runner launch race); tear down on `onUnload` +
   shutdown-hook; worker layer self-terminates on parent-death/idle for `kill -9`.
3. **Aggregate timing.** No sbt "after all tasks" hook; the coordinator emits the grand total on
   `onUnload`/`testKyo`-end/idle.
4. **Cancel / Ctrl-C** must propagate (interrupt -> cancel) so workers stop and nothing orphans.
5. **IDE / `testOnly` / single module** is unchanged: a coordinator-of-one is still correct.
6. **Leak-check placement (D3).** The coordinator knows when a queue's last task completed, so it can
   drain that module's worker to quiescence and run the check there.

## Why this also fixes Findings 1-3 and 6

One `Workers.run` sees the whole run, so it renders ONE correct whole-run aggregate (the per-fork
`Summary` problem, Findings 3/6), can stamp absolute timestamps on the true timeline (Finding 1), and
can emit a machine-readable timing report out of the box (Finding 2). The aggregate-count gap is a
direct symptom of "no once-per-run point"; `Workers.run` IS that point.

## The decisions that actually shape it

### D1. Task granularity: suite vs leaf
Suite-level (a task = one suite) is simplest and preserves within-suite locality; to get an exact
total-*leaf* bound, pin in-worker leaf concurrency to 1 or acquire a global leaf-permit per leaf.
Leaf-level (task = suiteClass + cursor) gives an exact leaf cap and per-leaf sequencing (feasible:
kyo-test already re-instantiates per leaf at a cursor) but multiplies message volume and breaks
suites that guard an external resource across leaves. Leaning: suite-level tasks + a global
leaf-permit.

### D2. Warm workers vs isolation
Warm workers amortize JIT/classload, but per-suite forking exists today FOR isolation
(kyo-browser/kyo-ui: Chrome degrades across suites, `build.sbt:2655-2662`; kyo-pod: per runtime). So
`.isolated` (fresh worker, recycled after) and worker-recycle-after-K are first-class.

### D3. Leak-check soundness (hardest)
Today the check runs once per fork over a JVM holding only this run's resources
(`SbtRunner.scala:112-113`, forked-only). A warm multi-suite worker is not that clean point. Options:
per-task against a baseline; at worker shutdown; or drain-to-quiescence between tasks and diff. Must
be redesigned deliberately; it is a load-bearing correctness gate.

### D4. Failure / retry / reliability
Resolved shape from the prior-art survey (see "Prior-art survey" item 5 and `prior-art/00-synthesis.md`
section 6). Headlines: **orphan-free kill via a kernel tether** (PR_SET_PDEATHSIG / Job Object /
process-group + worker self-halt), structural not opt-in; and **hung-task `timeout` enforced by a
coordinator watchdog that force-kills the worker process** (a thread cannot be force-killed on a
managed runtime). Then: bounded **task retry** (at-most-once default, at-least-once opt-in, idempotency
documented) SEPARATE from a **pool circuit-breaker**; **two-tier `timeout`** (work deadline, graceful
window, kill) with an unkillable escalation; **explicit heartbeat** decoupled from task traffic;
**per-Fiber failure isolation**, never whole-pool poison; a coordinator-held authoritative
dispatched-not-acked ledger; policy in the coordinator, not the thunk.

### D5. Platform scope: full cross-platform
The module is `crossProject(JS, JVM, Native, Wasm)`. Verified cross-platform: the transport
(`kyo-aeron`), the wire codec (`kyo-schema`), process spawning (`kyo.Command`, in `kyo-core/shared` with
`jvm-native` and `js-wasm` process impls), and container isolation (`kyo-pod`) are all
`crossProject(all 4)`. The one platform-conditional thing is the IMPLEMENTATION of shipping an arbitrary
CLOSURE (`fork(thunk)` capturing state), NOT the module:
- **JVM**: uses `ObjectOutputStream` (kyo-schema cannot derive a `Schema` for a `Function0`, so the
  closure graph rides Java serialization).
- **JS**: Scala.js has NO `ObjectOutputStream` in its javalib (only an empty `Serializable` marker),
  but a Scala.js closure is a JS object at runtime, so a serializer IS buildable: `fn.toString()` for
  the body (the worker runs the same bundle, so mangled `$m_...` refs resolve) plus the Node V8
  inspector to read captured variables, the mechanism Pulumi's `serializeFunction` uses. Unproven and
  unbuilt for Scala.js and fragile under the optimizer, so JVM-today, JS-buildable-later.
- **Native/Wasm**: same, no built serializer.
- **Universal wall (not JS-specific)**: the captured VALUES must themselves be serializable on EVERY
  platform. A closure over a `Fiber`/`Channel`/open socket ships nowhere, including the JVM (Spark's
  "Task not serializable").

So the cross-platform-today primitive is a **registered handler + `Schema` message** (the Node
`worker_threads`/`child_process` model, and exactly what `RunSuite(className)` already is); arbitrary
`fork(thunk)` is a JVM-today convenience. Bonus: cross-platform Workers lets **JS (Node) and Native
parallelize tests across OS processes**, which the current single-process in-process runner cannot.

### D6. Statefulness now or later
Ship the stateless `fork(thunk, task)` path first (all kyo-test needs); add the stateful
`Queue.warm` + `fork(q)(s => ...)` path when kyo-compiler adopts `Workers`.

## Prior-art survey (complete) - what it resolved

12 systems mined into `prior-art/` (Ninja, Bazel tests + persistent workers, Ray, Celery, Slurm+K8s,
pytest-xdist, Gradle+Surefire, xUnit/NUnit, jest-worker + Python pools, Erlang/OTP, Spark+Dask); full
cross-cut in `prior-art/00-synthesis.md`. The conclusions that change the design:

1. **Keep a TRUE global cap.** No global cap is a documented pain in Celery (none) and Bazel workers
   (per-key only, bazelbuild#12165). `Workers.run(Config(parallelism=N))` as a cross-queue cap is a
   deliberate improvement; per-queue `maxWorkers` only tightens under it (Ninja depth, Slurm `%N`).
2. **Two-tier cap + per-task declaration is a converged pattern** (xUnit/NUnit, Bazel, Slurm/K8s), not
   novel - de-risks the shape.
3. **Named `locks` == xUnit `[Collection]`** (near-exact); adopt directly. But **`.exclusive` is
   underspecified**: Ninja's "exclusive" only buffers output (others keep running) and NUnit's single
   global non-parallel bucket over-serializes. "Runs alone" must be a read-write global barrier (all
   tasks hold it shared, `.exclusive` takes it exclusively) or an explicit drain, not "a lock only
   exclusive tasks hold". Decide deliberately.
4. **`weight` = admission-only scalar** (K8s requests vs limits; Workers has no enforcement layer),
   with Bazel's "always schedule at least one" starvation guard; `--gres`-style vectors only if needed.
5. **Reliability model** (the addendum), two strong convergent findings first:
   - **(a) Orphan-free kill is STRUCTURAL, via a kernel tether, not signal forwarding.** Every mature
     system that gets it right uses an OS parent-death mechanism (Linux `PR_SET_PDEATHSIG`,
     process-group/cgroup containment, Windows Job Object `KILL_ON_JOB_CLOSE`) plus a worker-side
     self-halt on parent death (Surefire `PpidChecker`); everyone relying on signals alone has a
     documented orphan leak (Gradle, pytest-xdist, Ray, Bazel workers). The JVM has no
     `PR_SET_PDEATHSIG`, so a worker-side parent-liveness watcher is a real cross-platform item.
   - **(b) Hung-task detection is the near-universal GAP; enforce it coordinator-side.** Nearly no
     system kills a stuck-but-alive task (Ray, pytest-xdist, Ninja, Bazel workers all leave it open).
     xUnit/NUnit prove a *thread* cannot be force-killed on a managed runtime (`Thread.Abort` removed
     in .NET 5+). So `Task.timeout` is enforced by a coordinator watchdog that **force-kills the worker
     process**; the process boundary is the design's biggest reliability differentiator.
   - Then: **task-retry** (bounded, at-most-once default, at-least-once opt-in, idempotency a
     documented obligation) SEPARATE from a **pool circuit-breaker** (deaths-before-run-fails); a
     **two-tier `timeout`** (work deadline, graceful window, then kill) plus an "unkillable" escalation
     (Slurm); **explicit heartbeat** decoupled from task traffic (catches init/warm-state wedges), not
     Bazel's lazy failure-on-next-response; **per-Fiber failure**, never whole-pool poison (jest >
     Python `BrokenProcessPool`); the **coordinator holds the authoritative "dispatched-not-acked"
     ledger** (worker output is unreliable at crash time); policy in the coordinator, not the thunk;
     backoff + max + **jitter**.
6. **Warm state lifecycle** = init-before-first / dispose-after-last (xUnit `ICollectionFixture` == Ray
   actor == OTP gen_server), queue-keyed; consider poolboy `size + max_overflow` with explicit
   block-vs-reject backpressure over a single `maxWorkers`. Default = warm reuse; `.isolated` =
   opt-in fresh (Gradle/Surefire defaults).
7. **Closure shipping**: the JVM only ships Spark-regime (fully `Serializable`) closures; no cloudpickle
   equivalent. Keep the ergonomic `fork(thunk)` but enforce serializability with a `ClosureCleaner`-style
   check + a precise error **at `fork()` call time**, and steer toward named-object + serializable-data
   captures (jest model). Our real thunks (e.g. `runSuite(className)`) already sit in that safe subset.
8. **Auto-injected implicit requirement** (classpath/jdk), on top of pick-by-name queues, so a task can
   never land on a mismatched worker (Gradle Test Distribution).
9. **Transport**: dedicated channel, never shared stdout (Surefire pipe + Bazel both break on native-lib
   stdout pollution) - validates the aeron/dedicated-IPC choice.

## Leveraging kyo-machine (resource-aware coordination)

`kyo-stats-machine` (`kyo.stats.machine.Machine`) already implements the hard, correctness-critical,
cross-platform host sensing a resource-aware coordinator needs, and crucially it is **cgroup-aware**
(v1 + v2): cgroup memory limit (`memory.max`) + current usage (`memory.current`), CPU quota/period
(`cpu.max` / `cfs_quota_us`) + `cpu.stat` throttling, and Linux **PSI** (`/proc/pressure/{cpu,memory,io}`
`some`/`full`, avg10/avg60), with macOS/Windows FFI readers and `NullMachine` (graceful degradation)
elsewhere. It depends only on kyo-ffi (no cycle with the orchestrator) and has an on-demand `read()`
path, though the surface is `private[kyo]` and stats-registry-oriented, so leveraging it for scheduling
means adding a small read API (cgroup mem limit/current, cpu quota, PSI avg10). The expensive part
(the sensing) is done and tested.

Why this matters here: the current CI parallelism story is a set of crude workarounds for NOT knowing
the container's real limits, `SBT_TASK_LIMIT=1` on constrained runners, hand-tuned fork heaps ("two
5GB forks plus the driver fit the 16GB box", `build.sbt`), and a `cores/2` guess, all because
`availableProcessors()` can over-report inside a cgroup and nothing reads the container's memory
ceiling. A machine-aware `Workers` coordinator replaces the guesswork:

1. **Auto-size the global cap to the container's real limits.** `Config(parallelism = auto)` reads the
   cgroup CPU quota (effective cores, not host cores) and memory ceiling, directly serving the founding
   goal (bound TOTAL parallelism) and killing the CI hacks.
2. **RAM-budget admission** (Bazel `--local_ram_resources` model, now grounded in the real cgroup
   limit): a worker JVM has a heap, so admit workers against `cgMemLimit * fraction` with Bazel's
   "always schedule at least one" starvation guard. This is the enforcement layer the prior-art
   synthesis said we would otherwise lack; `weight` can be RAM-denominated.
3. **PSI-based backpressure** (Dask's target/spill/pause/terminate, but from a direct kernel stall
   signal instead of an inferred probe): pause admission when memory `full` pressure rises; grow when
   cpu pressure is low and the cap is unfilled. Strictly better information than the adaptive
   scheduler's sleep-jitter regulator, and at a different layer (worker-process count, which the
   in-JVM scheduler cannot see).
4. **Reliability: proactive OOM-avoidance recycling** (ties to D4). Recycle/evict a worker as its
   cgroup memory usage nears the limit, BEFORE the kernel OOM killer fires, the cgroup-accurate version
   of jest `idleMemoryLimit` / Celery `max-memory-per-child` / Dask `terminate=0.95`.
5. **Weight calibration** from live headroom plus per-task history (Ninja critical-path-from-`.ninja_log`,
   Gradle Test Distribution historical balancing).

Guardrails: full metrics only on Linux (the CI-container case that matters most); macOS/Windows are
partial and other OSes fall back to `availableProcessors` + a conservative default, so resource
awareness degrades gracefully and the cap always has a floor. And it must not double-regulate: kyo's
adaptive scheduler already governs concurrency WITHIN a worker JVM via its jitter probe; the
machine-aware coordinator governs the COUNT of worker processes ACROSS JVMs. Complementary layers, not
competing ones.

## Worker isolation levels (kyo-pod, configurable)

kyo-pod's `Container.Config` already exposes everything a containerized worker needs (`cpuLimit`,
`memory`/`memorySwap`, `maxProcesses` (pid cap), `mounts`, `networkMode`, `env`, `command`,
`kill(signal)`, `remove(force)`, `initUnscoped`), and it is proven in-repo (the SQL conformance
suites). So worker isolation becomes a **configurable spectrum** on `Task.Queue`/`Config`:

- `isolation = Process` (default): a forked JVM. Fast, crash-isolated. The current design.
- `isolation = Container(image, cpuLimit, memory, maxProcesses, networkMode, mounts, ...)`: a kyo-pod
  container per worker. Stronger isolation + hard resource caps + a clean kill boundary.

Opt-in per queue, with automatic fallback to Process when no container runtime is present (dev
machines, some CI). This also subsumes kyo-pod's OWN per-runtime test forking (the `build.sbt`
`KYO_POD_RUNTIME` `testGrouping`) into a queue config.

Why containers resolve or simplify open problems:
- **The adaptive-scheduler saturation concern dissolves.** A container `cpuLimit=k` makes the worker's
  cgroup report k cores, so its own kyo scheduler (`coreWorkers = availableProcessors`, plus
  kyo-machine) self-sizes to k and cannot oversubscribe the host. The coordinator allocates the host
  budget (read via kyo-machine) as per-container quotas whose sum is at most the host. Deterministic,
  no feedback-loop reliance.
- **Bare-fork workers self-balance anyway (your point, confirmed).** Each worker's Concurrency
  regulator measures host scheduling jitter (jHiccup-style); cross-worker contention IS thread
  interference, so each pool shrinks toward a non-saturating equilibrium. It is a soft feedback loop
  (converges, can overshoot at startup) versus the container's hard bound. Mitigate the startup spike
  without containers by having the coordinator pass each bare worker a pre-sized
  `-Dkyo.scheduler.coreWorkers = hostCores / workerCount` (it knows both, via kyo-machine and its cap).
  So oversubscription is addressable for bare forks (pre-size) and hard-bounded for containers (cgroup).
- **Orphan-free kill (the #1 reliability finding) comes for free.** A container IS the cgroup/namespace
  containment the survey said orphan-free kill requires; `Container.remove(force)` / `kill` reaps the
  whole tree via the runtime, so the container path needs no `PR_SET_PDEATHSIG` watcher (only the bare
  Process path does).
- **Network isolation removes a whole lock class.** Each container has its own network namespace, so
  fixed-port tests (aeron UDP, kyo-net, URI-validation) can each bind the same port without collision;
  the `.holding("port:8080")` locks become unnecessary for containerized queues.
- **Filesystem isolation** removes cross-suite `/tmp`/fixed-path collisions (what browser/pod fight).

Honest costs and the one real wrinkle:
- **Startup latency**: container create is slower than a JVM fork, so containers are opt-in, mitigated
  by warm container pools plus a base image with the JVM baked and the classpath bind-mounted.
- **Transport across the container boundary (the real integration item).** kyo-compiler uses
  `aeron:ipc` (shared-memory, one host, shared driver dir). A container has its own IPC/mount
  namespace, so the worker reaches the host medium only if we **bind-mount the aeron driver directory**
  into the container (keeps ipc) or fall back to **`aeron:udp`** over the container network (kyo-net's
  native stack makes this viable). Bind-mount is the cleaner default; the module test classpath is
  likewise bind-mounted or baked.
- **Two-level kyo-machine, both correct**: the worker's in-container kyo-machine reads the CONTAINER
  cgroup (self-sizes its scheduler); the coordinator's kyo-machine reads the HOST to allocate the
  per-container budget.
- **Platform**: Linux-first; Docker Desktop's VM on macOS/Windows adds overhead. Process stays the
  portable default.

This unifies the three modules: **kyo-machine** senses the host budget, the **coordinator** allocates
it as per-worker CPU/mem quotas under a global cap, and **kyo-pod** enforces the quota and isolation
per worker, with the bare-fork Process path as the fast, portable default.

## Open questions

Still open (design-defining):
1. Granularity (D1): suite-tasks + global leaf-permit, or leaf-tasks?
2. Leak-check model under warm workers (D3) - the biggest correctness question.
3. Isolation/recycle policy for the fork-per-suite modules (browser/ui/pod).
4. `.exclusive` semantics (survey item 3): read-write global barrier vs drain barrier.
5. Coordinator home: session-owned child process vs plugin-hosted in-JVM singleton.
6. Naming: `Task.Queue` vs `Lane` (the `kyo.Queue` clash).
7. Q7 layering: keep aeron out of the base runner via a separate consumer artifact (confirmed no hard
   cycle; kyo-aeron uses kyo-test only in test scope; `kyo-test-runner` does not depend on kyo-aeron).

Narrowed by the survey (was open, now has a default):
- Cap counts: a true global cap of concurrent tasks; `weight` = admission-only scalar with a
  starvation guard (survey item 4). Leaf-vs-suite counting follows D1.
- Serialization: keep `fork(thunk)`; require serializability, check at `fork()` with a precise error,
  steer toward named-object + serializable-data captures (survey item 7).
- Warm/recycle: default warm reuse, `.isolated` opt-in fresh; warm lifecycle init-before-first /
  dispose-after-last (survey item 6).

## Recommended path

1. Resolve Q7 layering and the coordinator-home question (they gate the module boundary).
2. Fold in the prior-art survey (config/requirements + reliability) once reports land.
3. Pick granularity (D1) and the leak-check model (D3); these define the contract.
4. Spike the smallest end-to-end slice: `Workers.run` + one stateless queue + `fork(thunk)` + the
   global cap + one `.exclusive` suite, over the real transport, on a two-module run. No leak check,
   no recycling yet.
5. Layer in the hard parts (leak model, isolation/recycle, retry, stateful path) once the spine holds.
