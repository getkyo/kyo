# Prior art: Apache Spark and Dask

Source: Spark docs (spark.apache.org, latest/4.x and 3.5.x), Dask distributed
docs (distributed.dask.org, 2026.x), `apache/spark` and `dask/distributed` on
GitHub.

## 1. What it is

**Spark** is a cluster compute engine built around the RDD/DataFrame
abstraction: a driver program builds a lazy DAG of transformations
(`map`, `filter`, `reduceByKey`, ...), and calling an *action* (`collect`,
`count`, `saveAsTextFile`) breaks that DAG into stages of **tasks**, each
task a closure shipped to and run on an **executor** — a long-lived JVM
process, one per node-slot, that "stay[s] alive for the life cycle of a
single Spark application."

**Dask** is a Python parallel-computing library with the same lazy-graph
idea but a much lighter-weight, general-purpose unit: `dask.delayed`
wraps an arbitrary Python call into a graph node, and the lower-level
`distributed` scheduler exposes `Client.submit(fn, *args) -> Future` to
ship one function call at a time to a pool of **worker** processes.
Where Spark is closure-over-RDD-partition, Dask is closer to a plain
future-based task queue with declarative, cluster-wide resource tags.

Both matter for `Workers` because both are *the* production-proven
answer to "ship an arbitrary closure to a remote process, run it, get
the result back," and both have well-documented scars from getting
closure capture wrong.

## 2. Unit of work: a shipped closure / delayed / submit

**Spark** — the unit is a **task**: one partition's worth of a stage's
closure chain. Nothing is submitted explicitly by the user; calling an
action implicitly computes the task's **closure**, "those variables and
methods which must be visible for the executor to perform its
computations," serializes it, and ships it:

```scala
var counter = 0
var rdd = sc.parallelize(data)

// Wrong: Don't do this!!
rdd.foreach(x => counter += x)

println("Counter value: " + counter)
```

The doc's own point: the closure captured by `foreach` is *serialized
and shipped*, so the executor mutates a **copy** of `counter`; the
driver's `counter` never changes. ("This closure is serialized and sent
to each executor.")

**Dask** — the unit is a plain function call, submitted explicitly and
returning a future immediately:

```python
from distributed import Client
client = Client('scheduler:8786')

future = client.submit(inc, 10)      # Future, not a blocking call
future.result()                      # 11

futures = client.map(inc, range(1000))
results = client.gather(futures)
```

`dask.delayed` is the higher-level, graph-building sibling: wrapping a
call in `@dask.delayed` builds a node instead of running it, letting
many delayed calls compose into one graph before a single `.compute()`
or `client.compute()` triggers execution — closer to Spark's
lazy-DAG-then-action shape, whereas `submit`/`map` are Dask's
immediate, future-returning primitive (the one structurally closest to
`Workers.fork`).

## 3. Grouping / routing

**Spark** routes by **partition and stage**, not by declared task
identity: the DAG scheduler splits each stage into one task per RDD
partition, and the cluster manager (standalone/YARN/Kubernetes) assigns
each task to an executor with a free core slot. There is no per-task
"send this one to that queue" API; routing is a function of data
partitioning plus resource availability.

**Dask** routes primarily by **resource tags** (see §4) and secondarily
by explicit `workers=` placement hints on `submit`/`map`. A task with no
resource constraint can land on any idle worker; a resource-tagged task
is confined to workers that advertised a matching capacity. This is the
closer analogue to `Workers`' `Task.Queue`-based routing: Dask resources
are a coarse, string-keyed capacity match, not a named-queue system, but
the intent (only run this where the declared capability exists) is the
same one `Workers` wants for `Task.Queue(name, {classpath, ...})`.

## 4. Declarative config / requirements model

**Spark**'s requirement model is cluster-wide and static, not per-task:

- `spark.task.cpus` (default `1`) — "Number of cores to allocate for
  each task."
- `spark.executor.cores` (default `1` under YARN, all available cores
  under standalone) — cores per executor process.
- `spark.executor.memory` (default `1g`) — memory per executor process.

A task's actual resource *need* is expressed only as "this task consumes
`spark.task.cpus` cores of whatever executor runs it" — there is no
per-task declaration like "this specific task needs a GPU" without the
separate, heavier `spark.task.resource.<name>.amount` GPU-scheduling
config added later for accelerator-aware scheduling. The model is
fleet-uniform: every task in the job costs the same slice unless you opt
into the resource-aware extension.

**Dask** is the more relevant precedent for `Workers`' per-`Task`
`weight`/requirements design, because it is genuinely per-call and
declarative:

```python
processed = [client.submit(process, d, resources={'GPU': 1}) for d in data]
final = client.submit(aggregate, processed, resources={'MEMORY': 70e9})

with dask.annotate(resources={'GPU': 1}):
    processed = [client.submit(process, d) for d in data]
```

Workers advertise capacity at launch (`dask worker scheduler:8786
--resources "GPU=2"`); the scheduler "will never schedule a task to a
worker that cannot meet the resource limits." Resource names are inert
strings the scheduler treats as "just abstract quantities" — GPU has no
semantic meaning to Dask itself, consistency between what workers
advertise and what tasks request is entirely the operator's
responsibility. If no worker currently satisfies a resource-tagged
task's constraint, the task sits in the scheduler's `no-worker` state
until one becomes available, rather than failing — a queued-admission
behavior, not a rejection.

## 5. Worker/process model

**Spark executors** are warm, per-application, multi-slot JVM
processes: "each Spark application gets an independent set of executor
JVMs that only run tasks and store data for that application," and they
persist for the application's full lifetime, running many tasks
sequentially or concurrently (up to `spark.executor.cores /
spark.task.cpus` slots) rather than one-process-per-task. Starting a new
executor is expensive — "typically incurs a delay of 2-3 seconds" — which
is exactly why the executor pool is provisioned once up front and reused,
not spun up per task.

**Dask workers** are the same shape: long-lived Python processes (or
threads within a process) that register with the scheduler, advertise
resources, and pull/receive many task submissions over their lifetime.
Nothing here is analogous to a cold-start-per-task model; both systems
treat "warm worker pool, N slots, reused across submissions" as
foundational, which directly validates `Workers`' `Task.Queue(name,
{classpath, maxWorkers, ...})` warm-pool design over spawning a fresh
process per `fork`.

## 6. Scheduling & concurrency control

**Spark**: admission is core-slot arithmetic. An executor with
`spark.executor.cores = c` and a job-wide `spark.task.cpus = t` can run
`c / t` tasks concurrently; the cluster-wide cap falls out of
`executors × cores`, configured once at cluster/app launch, not
adjusted per task beyond the `spark.task.cpus` multiplier. `spark.speculation`
(off by default) is the closest thing to dynamic rebalancing: a
periodic daemon thread (interval governed by `spark.speculation.interval`)
detects straggler tasks and launches a duplicate copy on another node,
"whichever task finishes first, its results are accepted, and the other
running task is immediately killed" — a duplication strategy for tail
latency, not a load-balancing one.

**Dask**: admission is the same resource-slot idea generalized to
named resources plus a genuine rebalancing mechanism, **work
stealing**, that Spark deliberately omits. When some workers are
saturated and others idle, "the Dask Scheduler ... takes that work away
and gives it to another worker." Stealing decisions are cost-based:
tasks are bucketed by computation-to-communication time ratio, and
"ratios of 8 or higher [are] always ... stolen, decreasing down to
ratios of 1/256 which are never stolen" — cheap-to-move, expensive-to-run
tasks steal readily; tasks dominated by data-transfer cost don't, since
moving them would cost more than it saves.

## 7. Failure handling and worker reliability

Task-level retry is one piece; the harder, more load-bearing machinery
in both systems is **worker reliability**: detecting a dead/hung
process, recovering the work it was doing, and doing so without leaking
resources or silently losing correctness guarantees. This is the part
most directly relevant to `Workers`' crash/timeout/recycle design.

### Liveness: heartbeats and timeouts

**Spark**: executors push heartbeats to the driver's `HeartbeatReceiver`
on a fixed interval, `spark.executor.heartbeatInterval` (default `10s`).
The driver marks an executor dead purely on elapsed time: "an executor
is considered as dead if, at the time of checking, its last heartbeat
message is older than the timeout value specified in
`spark.network.timeout`" (default `120s`) — at which point
`HeartbeatReceiver` logs a warning and calls `TaskScheduler.executorLost`.
Note the documented gotcha: raising `heartbeatInterval` to "fix" spurious
timeouts is a known misconception — fewer heartbeats per timeout window
*increases* the chance of a false death detection; the correct dial is
`spark.network.timeout`, not the interval.

**Dask**: the same two-timer shape — workers heartbeat to the scheduler,
and "if the scheduler doesn't hear back from them in a certain amount of
time, the scheduler can ask the nanny to kill the worker, or just give
up on the worker." The timeout is `distributed.scheduler.worker-ttl`
(default `5min`); once exceeded, "the scheduler will notice that the
worker has gone, either because of an explicit de-registration, or
because the worker no longer produces heartbeats." (`worker-ttl` was
previously unbounded/infinite by default — a documented historical
footgun where a truly hung worker could sit undetected forever.)

### Task retry and recomputation on worker loss (the at-least-once assumption)

**Spark**: `spark.task.maxFailures` (default `4`) bounds *per-task*
retry — "after failing `spark.task.maxFailures` number of times on the
same task, the Spark job would be aborted." A task lost to executor
death is one of the failure causes counted here, alongside application
exceptions. Recovery of the actual data is **lineage-based**, not
snapshot/checkpoint-based by default: RDDs carry their DAG of
transformations, so "if a partition is lost due to a failure, Spark
consults the lineage to recompute only that partition by reapplying
transformations to the source data" — only the lost partition's chain
is replayed, not the whole job. This recomputation is silently
**at-least-once**: Spark's own docs lean on the assumption that
"assuming that all of the RDD transformations are deterministic, the
data in the final transformed RDD will always be the same irrespective
of failures" — the safety of a recompute is conditioned entirely on the
task closure being pure/deterministic and free of external side
effects. A task that writes to an external system (a DB insert, an
HTTP call) as a side effect can be replayed by lineage recovery exactly
like a pure one; Spark does not distinguish them.

Beyond per-task retry, Spark also has an **exclusion (formerly
"blacklist") mechanism** that stops routing work to a bad node/executor
rather than just retrying blindly: `spark.blacklist.task.maxTaskAttemptsPerExecutor`
and `...PerNode` block a specific executor/node from receiving the
*same* task again after repeated failures there, and
`spark.blacklist.application.maxFailedTasksPerExecutor` /
`...maxFailedExecutorsPerNode` escalate to excluding that
executor/node for the rest of the stage or application once its
failure count crosses a threshold — the documented constraint is that
`maxTaskAttemptsPerNode` must exceed `spark.task.maxFailures`, or every
attempt for a task can get blacklisted before `maxFailures` is even
reached, failing the job outright.

**Dask**: `distributed.scheduler.allowed-failures` is the corresponding
"how many worker deaths does one task tolerate" counter. When a worker
dies mid-task, "work that was being done by the worker will be
redirected to other workers" — the task is resubmitted, not resumed,
i.e. it reruns from scratch on the new worker, the same at-least-once
shape as Spark's lineage replay. If the *same task* kills a second
worker too, and the death count for that task exceeds
`allowed-failures`, Dask raises `KilledWorker` and blames the task
rather than retrying indefinitely — with an explicit caveat in the docs
that this attribution can be wrong: "it is possible for a task to be
unfairly blamed — the worker happened to die while the task was active,
perhaps due to another thread." Per-submission `retries=N` (§7 above)
governs *application-level* exceptions the function itself raises;
`allowed-failures` governs *worker death* specifically — two separate
counters for two separate failure modes. Dask's docs do not spell out
an idempotency requirement the way Spark's determinism assumption
does, but the mechanism is structurally identical: a retried/rescheduled
task re-executes the same Python call from the start, so any
non-idempotent side effect inside it will re-fire.

### Speculative execution (stragglers, not just crashes)

**Spark**'s `spark.speculation` (off by default) is specifically for
tasks that are alive but abnormally slow, not dead: a periodic
`task-scheduler-speculation` daemon (`spark.speculation.interval`)
detects stragglers and launches a duplicate attempt on another
executor; first-to-finish wins and the loser is killed. This assumes
the task is safely re-runnable *while the original is still running* —
a strictly stronger requirement than retry-after-death, since for a
window both copies are executing concurrently.

**Dask** deliberately does not have this: the docs acknowledge Spark
and Hadoop's speculative-duplication capability as something "Dask
currently does not do," tracked as an open gap upstream. Dask's answer
to a slow-but-alive worker is **work stealing** (§6) — move the *queued*
work elsewhere, never duplicate work already in flight.

### Orphan cleanup, graceful decommission, and OOM/resource-leak handling

**Spark**: `spark.decommission.enabled` makes executor shutdown (from
dynamic allocation scaling down, or a cloud spot-node reclaim signal) a
negotiated handoff rather than an abrupt kill — "Spark will try its
best to shut down the executor gracefully," and with
`spark.storage.decommission.enabled` additionally true, it migrates
cached RDD blocks and shuffle blocks off the departing executor to
peers first (`spark.storage.decommission.maxReplicationFailuresPerBlock`
bounds migration retries), specifically so the job doesn't have to fall
back to lineage recomputation for data that could just be moved. This
is orthogonal to the heartbeat-timeout death path: decommission is a
*voluntary*, coordinated exit; heartbeat timeout is the driver
unilaterally declaring an executor gone.

**Dask**: `Client.retire_workers()` is the equivalent voluntary path —
it flips a worker to `closing_gracefully`, "copies over to other
workers all unique data they contain," and only finalizes the shutdown
once every in-memory result is durably replicated elsewhere or no peer
can accept it; a hung transition here (worker stuck in
`closing_gracefully`) has been a real, tracked bug class, i.e. graceful
decommission is a genuinely hard state machine to keep race-free even
in a mature codebase. For the crash/leak side rather than the
voluntary side, the **Nanny** is Dask's dedicated supervisor process:
one nanny per worker, watching worker process health independently of
the scheduler's heartbeat view. Memory is managed by four fractional
thresholds against `memory_limit` — `target` (`0.60`), `spill` (`0.70`,
starts writing unused data to disk), `pause` (`0.80`, the worker's
thread pool "stops starting computation on additional tasks in the
queue" — i.e. it stops accepting new work while still finishing what's
in flight), and `terminate` (`0.95`, hard kill) — and "after
termination, the nanny will restart the worker in a fresh state." This
is a graduated response, not a binary healthy/dead signal: spill and
pause are backpressure mechanisms that try to avoid ever reaching the
terminate/restart path.

### Backpressure

Neither system needs a distinct network-level backpressure primitive
because both fold it into the same admission machinery already
described: Spark simply never assigns a task to an executor with no
free core slot (§6), so a saturated cluster produces a growing pending-task
queue on the driver, not overload on any executor. Dask's scheduler
"picks the least-busy worker out of idle workers" and, "if all workers
are full, the task transitions to `queued`... waits to send it to a
worker until a thread opens up" (governed by
`distributed.scheduler.worker-saturation`) — explicitly designed so
"downstream tasks always run before new root tasks are started,"
preventing a flood of eagerly-scheduled root tasks from filling worker
memory with intermediate results that have nowhere to flow yet. Memory
pressure backpressure is the `pause` threshold above: a locally
saturated worker stops pulling new work without needing a
scheduler-side round trip to notice.

## 8. Results

**Spark** actions block synchronously in the driver (`collect`,
`count`, ...) or write directly to storage; there is no first-class
future object in the public RDD/DataFrame API — the DAG-then-action
model means "the future" is implicit in when the action call returns.

**Dask**'s `Future` is the direct structural analogue to `Fiber[A,E]`:
`submit`/`map` return immediately (`Future` / `List[Future]`),
`future.result()` blocks for one, `Client.gather(futures)` resolves a
whole collection at once preserving its input shape (list, dict, nested
structure), and `Future.cancel()` / `Future.exception()` round out the
handle. This is the API shape `Workers.fork` should be measured against
more than Spark's — it is a plain, composable async handle rather than
an action-triggered blocking call.

## 9. Transport/protocol: closure serialization (the priority)

This is where the two projects diverge most and where the footguns
live.

**Spark uses JVM serialization** — Java serialization by default
(`spark.serializer = org.apache.spark.serializer.JavaSerializer`),
Kryo optional and recommended for speed ("the default of Java
serialization works with any Serializable Java object but is quite
slow"). Every task closure must be `Serializable` end to end: the
function object itself, and transitively everything it captures. Scala
closures capture their **entire enclosing scope** by reference unless
Spark's `ClosureCleaner` can prove a captured field is unused and null
it out — "renders closures serializable if they can be done so safely
... as long as it does not explicitly reference unserializable
objects." This is a best-effort static analysis, not a guarantee, and
its failure mode is the single most infamous Spark error:

```
org.apache.spark.SparkException: Task not serializable
Caused by: java.io.NotSerializableException: ...
```

The canonical trigger is a closure implicitly capturing `this` — a
method reference or field access on an outer class pulls in the whole
outer instance, including anything non-serializable it holds (a
`SparkContext`, a DB connection, a logger). The universally-cited fix is
"grab the values from outside the closure into a local `val`" so only
the value, not the enclosing object, gets captured — or mark the
enclosing class `Serializable` (or `extends Serializable` on a case
class) if capture is unavoidable. **Broadcast variables** are the
purpose-built escape hatch for data that legitimately needs to reach
every executor without going through closure capture: `sc.broadcast(v)`
serializes `v` once, caches it in deserialized form on each executor,
and the closure carries only the lightweight `Broadcast` handle instead
of the value itself — avoiding both the serialization-correctness
problem and the bandwidth cost of re-shipping the same data with every
task.

**Dask uses cloudpickle**, a strictly more permissive serializer than
stdlib `pickle` built specifically for this use case ("especially useful
for cluster computing where Python code is shipped over the network to
execute on remote hosts"). The key differences: standard `pickle`
serializes functions/classes **by reference** — it records "which
module to import and which name to look up," so the unpickling side must
already have that exact module importable; it fails outright on lambdas
and functions defined interactively (REPL, notebook, `__main__`) because
there is no importable reference to point at. Cloudpickle instead
serializes **by value** for anything it can't safely reference: it
walks the function's actual bytecode-level closure — "captures the
global variables and closures ... that the function depends on" — and
embeds them, so a lambda or a notebook-defined function ships correctly
without needing the far end to have the same source file. This trades
away Spark's *fail loud at submission* posture (a `NotSerializableException`
before the job runs) for a different failure mode: cloudpickle is
permissive enough that a captured object which merely *looks* small
(e.g. a pandas DataFrame or a live client connection pulled in
implicitly through a closure) can be silently shipped whole, which is
Dask's own version of the accidental-capture footgun, just less likely
to hard-fail up front.

## 10. Lessons for `Workers`

Direct mapping:

| Spark / Dask | `Workers` |
|---|---|
| Spark task (closure over a partition) / Dask `Client.submit(fn, *args)` | `Workers.fork(thunk, task)` |
| Executor (warm JVM, N core-slots, one per app lifetime) / Dask worker process | worker process under `Task.Queue(name, {maxWorkers, ...})` |
| `spark.executor.cores` / `spark.task.cpus` admission arithmetic | `parallelism` global cap / per-`Task` `weight` |
| Dask `resources={'GPU': 1}` + worker-advertised `--resources` | `weight` as a declared, worker-advertised capacity match |
| Dask `Future` (`submit`/`map`/`gather`) | `Fiber[A,E]` |
| Spark broadcast variable (`sc.broadcast(v)`) | a shared-payload mechanism for `Workers`, separate from per-task closure capture |
| Spark `ClosureCleaner` + `NotSerializableException` | JVM `Serializable`/serialization-check at `fork` submission time |
| Dask cloudpickle (by-value capture) | JVM has no equivalent — see verdict below |
| `heartbeatInterval`/`network.timeout` (Spark), heartbeat/`worker-ttl` (Dask) | worker liveness heartbeat + a hung-task/worker-dead timeout |
| `spark.task.maxFailures` / lineage recompute; Dask `allowed-failures` + task resubmission | bounded crash→retry (worker dies mid-task → resubmit, capped) |
| Spark exclusion/blacklist (`maxTaskAttemptsPerExecutor`/`PerNode`) | stop routing to a worker that keeps failing the same task |
| `spark.speculation` (duplicate stragglers) vs. Dask work-stealing (move queued work) | hung-task timeout + kill, not duplicate-and-race |
| `spark.decommission.enabled` / `Client.retire_workers()` | orphan-free graceful worker shutdown (drain in-flight, then kill) |
| Dask **Nanny** + `target`/`spill`/`pause`/`terminate` memory fractions | worker-process supervisor + graduated backpressure before hard recycle |
| Dask `worker-saturation` queuing | bounded per-worker in-flight task count before the global queue backs up |

**Steal — reliability specifically:**
- **Two independent timers, not one.** Both systems split "how often do
  I check you're alive" (`heartbeatInterval` / heartbeat cadence) from
  "how long past due before I declare you dead" (`network.timeout` /
  `worker-ttl`), and both explicitly warn against conflating them —
  Spark's own docs call out that stretching the *interval* to fix
  false-positive deaths is backwards; the *timeout* is the dial.
  `Workers` needs both knobs named separately from day one, and the
  `Task.timeout` mentioned in the API sketch should be documented as
  the per-call analogue of the *timeout*, not the heartbeat cadence.
- **A dedicated process supervisor, decoupled from the scheduler's
  liveness view.** Dask's Nanny watches its one worker's process health
  (OOM, crash, hang) independently of whether the scheduler's heartbeat
  channel is even working — the two failure detectors are deliberately
  redundant. `Workers` should have an equivalent local supervisor per
  forked worker process (not rely solely on the IPC channel going quiet
  to infer death), since a worker that's alive but wedged on IPC looks
  identical, from the channel alone, to one that's dead.
- **Graduated backpressure before a hard kill.** Dask's `spill` →
  `pause` → `terminate` ladder stops accepting new work well before it
  resorts to killing and restarting a worker; a worker recycle policy in
  `Workers` (mentioned in the design sketch) should have the same
  shape — a "stop admitting new tasks to this worker" state distinct
  from and prior to "kill and respawn this worker."
- **Voluntary decommission is a different code path from crash
  detection**, in both systems, and worth keeping distinct in
  `Workers` too: draining a worker for a `Task.Queue` scale-down or
  planned restart should mean "stop assigning new work, let in-flight
  work finish, then close," never routed through the same "declare
  dead, resubmit elsewhere" path used for an actual crash — Spark's
  shuffle-block migration and Dask's `retire_workers` data-copy both
  exist specifically to make a voluntary exit cheaper and non-lossy
  compared to a crash-triggered recompute/resubmit.
- **Stop routing to a serial offender**, not just retrying blindly:
  Spark's exclusion mechanism (don't resend the same task to an
  executor/node that just failed it) is worth having even in a
  single-machine-class `Workers` deployment — if one worker process is
  consistently failing a given `Task`, retrying it on that exact same
  worker is wasted latency at best and a masked bug at worst.

**The idempotency assumption behind at-least-once retry — directly
relevant to `Workers`.** Every recovery path in both systems (Spark's
lineage recompute, Dask's task-resubmit-on-worker-death) is
**at-least-once, not exactly-once**, and neither system enforces or
even checks that the retried work is safe to re-run: Spark's fault
tolerance model is correct *only* "assuming that all of the RDD
transformations are deterministic;" Dask's docs describe the
resubmission mechanics without ever asserting an idempotency guarantee.
A task closure with an external side effect (a file write, a network
call, a mutation of shared state outside the sandboxed process) can be
silently re-executed by either system's recovery path exactly as if it
were pure. This is a **design obligation for `Workers`**, not just a
caveat to document: since the stated use cases include compiler and
test invocations, the `Task.retry` field needs to be honest that a
retried run is a *second, independent execution* of the same thunk, not
a resume — which is generally fine for a compiler task (recompiling is
idempotent by construction) and for most test runs, but is exactly the
kind of assumption that breaks silently for a task with side effects
(writing to a shared output path, appending to a log, mutating a
database fixture). `Workers` should document this explicitly at the
`Task.retry`/`timeout` API surface — "a retried task re-executes the
thunk from scratch; only enable retry for idempotent thunks" — rather
than leaving it implicit the way both Spark and Dask do.

**Steal:**
- The warm-worker-pool-with-slot-accounting model, validated
  independently by both systems: never spin up a process per task,
  provision `maxWorkers` once, admit by weight/cores against a
  cluster-wide cap. This directly backs `Workers`' `Task.Queue`
  warm-pool design.
- Dask's per-task, declarative `resources={}` + worker-side
  advertisement is the right shape for `weight`: workers declare what
  they have, tasks declare what they need, the scheduler only matches
  compatible pairs and queues (never fails) when nothing currently
  qualifies.
- A first-class future (`Future`/`Fiber`) as the result handle, not an
  action-triggered blocking call — Dask's API, not Spark's, is the
  precedent to follow for `fork`'s return type.
- Spark's broadcast-variable pattern: give `Workers` an explicit
  "ship this once, cache it on the worker, reference it cheaply from
  many task closures" primitive, separate from ordinary closure
  capture, so large shared payloads don't get silently re-serialized
  per task.
- Dask's cost-ratio work-stealing as a candidate for a later
  `Workers` scheduling refinement (not required for v1, but the right
  model if idle-worker underutilization ever becomes a problem):
  rebalance based on move-cost vs. run-cost, not naive load count.

**Avoid:**
- Spark's implicit `this`-capture footgun is a language-level hazard,
  not a Spark-specific one — Scala closures on the JVM have the exact
  same "captures the whole enclosing instance" behavior `Workers` will
  inherit. This needs to be documented prominently and, if feasible,
  checked eagerly (fail at `fork()` call time with a precise message
  naming the non-serializable captured field) rather than relying on
  best-effort cleaning the way `ClosureCleaner` does.
- Spark's uniform `spark.task.cpus` (one slice size for the whole job)
  under-serves genuinely heterogeneous task weights; Dask's per-call
  `resources=` is the better precedent and what `weight` should already
  match, so no course change needed here, just confirmation.
- Dask's byte-code-level closure capture (cloudpickle) has no
  faithful JVM equivalent: Java serialization requires explicit
  `Serializable` and reconstructs by class reference, not by re-executing
  captured bytecode against a rebuilt environment. **Honest verdict on
  "ship an arbitrary closure" for the JVM**: it is *feasible* for
  closures whose captured environment is itself fully `Serializable`
  (immutable case classes, primitives, other Kyo values), exactly
  Spark's proven regime for fifteen years at scale — but it is not, and
  cannot be, as permissive as Dask/cloudpickle. `Workers` should commit
  to Spark's model (serializability is a hard, checked requirement of
  what a thunk may capture) rather than gesture at Dask's (silently
  capture whatever bytecode touches), and should invest specifically in
  making the failure mode good: a `ClosureCleaner`-equivalent static
  check plus a `Task not serializable`-quality error naming the exact
  offending captured field, at `fork()` time, not at the worker after a
  round trip.
