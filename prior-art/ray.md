# Prior art: Ray

Source: Ray Core docs (docs.ray.io, v2.55/2.56/master), `ray-project/ray` on GitHub.

## 1. What it is

Ray is an open-source distributed compute framework for Python. The core
primitives are three: **tasks** (stateless functions run in the cluster),
**actors** (stateful worker processes), and **objects** (immutable values
in a shared-memory store, addressed by future-like references). Ray
"automatically handles ... Orchestration ..., Scheduling ..., Fault
tolerance ..., and Auto-scaling" on top of those primitives. A single
decorator, `@ray.remote`, turns a plain function or class into a
task/actor definition; `.remote(...)` on the decorated object submits
work and returns immediately with a future.

## 2. Unit of work: task vs. actor call

**Task** — a plain function, decorated once, invoked many times, each
call independent and stateless:

```python
@ray.remote
def my_function():
    return 1

obj_ref = my_function.remote()
assert ray.get(obj_ref) == 1
```

**Actor** — a class, decorated once, *instantiated* with `.remote()` to
spawn a long-lived worker process; each method call is then a request
against that specific instance and shares state with prior calls on the
same instance:

```python
@ray.remote
class Counter:
    def __init__(self):
        self.value = 0
    def increment(self):
        self.value += 1
        return self.value

counter = Counter.remote()          # spawns the actor process
obj_ref = counter.increment.remote() # a call against that instance
print(ray.get(obj_ref))
```

"Methods on the same actor share state with one another" — calling
`increment` five times sequentially on one actor handle returns
`[2, 3, 4, 5, 6]`, i.e. instance state persists across calls the way it
would in a plain object, just remoted over IPC. Object-ref arguments are
resolved as dependencies automatically: passing one task's `ObjectRef` as
another task's argument makes Ray wait for the first before scheduling
the second, without the caller blocking.

## 3. Grouping / placement

**Placement groups** atomically reserve resource "bundles" across the
cluster (gang scheduling) so a set of tasks/actors lands together (or
apart) instead of trickling in piecemeal:

```python
from ray.util.placement_group import placement_group
pg = placement_group([{"CPU": 1}, {"GPU": 1}], strategy="PACK")
ray.get(pg.ready(), timeout=10)

actor = Actor.options(
    scheduling_strategy=PlacementGroupSchedulingStrategy(
        placement_group=pg, placement_group_bundle_index=0,
    )
).remote()
```

Strategies: `PACK` (best-effort co-locate on one node), `STRICT_PACK`
(hard co-location requirement), `SPREAD` (best-effort one-per-node),
`STRICT_SPREAD` (hard one-per-node). Strict variants "can lead to lower
resource utilization and [are] harder to schedule" — the docs explicitly
recommend the soft variants unless the hard guarantee is required.
Removing a placement group forcefully kills any actor/task still
occupying its bundles.

Below placement groups sits a lower-level **`NodeAffinitySchedulingStrategy`**
pinning a task/actor to a specific node id, with a `soft` flag: if
`soft=True` and that node is gone or infeasible, Ray falls back to any
other feasible node instead of failing.

## 4. Declarative config / requirements model

Every task and actor declares its resource *requirements* at the
decorator, and every call site can override them via `.options(...)`
without touching the definition:

```python
@ray.remote(num_cpus=2, num_gpus=2, resources={"special_hardware": 1}, memory=1*1024**3)
def func(): return 1

func.options(num_cpus=3, num_gpus=1, resources={"special_hardware": 0}).remote()

@ray.remote(num_cpus=0, num_gpus=1)
class Actor: pass

actor = Actor.options(num_cpus=1, num_gpus=0).remote()
```

`num_cpus` and `num_gpus` accept **fractional** values (`num_cpus=0.5`),
letting several lightweight tasks/actors timeshare one logical unit —
Ray's scheduler treats these as a bin-packing/admission constraint, not a
hard OS-level slice. `resources` is an open string→float map for
arbitrary custom capacities (accelerator types, licensed-tool slots,
whatever a cluster operator defines), matched the same way as CPU/GPU.
Nodes declare their available capacity at `ray.init(num_cpus=..., resources={...})`,
and the scheduler only places a task/actor on a node whose remaining
capacity covers every declared requirement.

## 5. Worker/process model

This is Ray's central duality and the one closest to `Workers`' split:

- **Task workers are anonymous and stateless.** A pool of generic worker
  processes exists per node; when a task is scheduled, any idle worker
  matching the resource requirement picks it up, runs the function to
  completion, and returns to the pool. `max_calls` bounds how many task
  invocations one worker process serves before Ray recycles it (default
  behavior favors exiting after the *first* call for tasks with
  `num_gpus>0`, guarding against GPU-memory leakage across unrelated
  tasks). There is no durable identity between one task call and the
  next — nothing is "warm" except the OS-level process/import cache.

- **Actors are named, stateful, and durable.** `Class.remote()` performs
  an explicit spawn: a dedicated OS process is created, the constructor
  runs once, and that process now *is* the actor for its entire
  lifetime. Every subsequent method call is routed to that same process
  and can read/mutate instance fields set up once in `__init__` (open
  connections, loaded models, caches). Actors can be looked up cluster-wide
  by name (`ray.get_actor(name)`) and have an explicit `lifetime`:
  default is fate-sharing with the creator (garbage-collected when the
  last handle's refcount drops to zero), or `lifetime="detached"` to
  outlive the driver as a standalone named service.

This is a direct structural analogue to `Workers`' stateless-thunk
(`Task`) vs. stateful-warm-queue (`Task.Queue` with `warmState`) split:
Ray tasks ≈ our thunk path (any worker, no identity across calls); Ray
actors ≈ our declarative `Task.Queue` (named, warm, process-scoped state
reused across submissions).

## 6. Scheduling & concurrency control

Admission is resource-based: the scheduler only assigns a task/actor to
a node/worker slot whose free `num_cpus`/`num_gpus`/`memory`/custom
`resources` cover the request; the cluster-wide sum of live commitments
is the global cap, computed continuously and "decentralized" across
nodes rather than a single central admission queue. Within a *single*
actor, `max_concurrency` bounds how many of its method calls run
concurrently (threaded execution inside one process rather than a queue
of one-at-a-time requests):

```python
a = ThreadedActor.options(max_concurrency=2).remote()
ray.get([a.task_1.remote(), a.task_2.remote()])
```

**Concurrency groups** go further, partitioning an actor's *own methods*
into named pools with independent concurrency limits (e.g. an `"io"`
group capped at 1 concurrent call while a `"compute"` group runs more),
so one slow method category cannot starve another on the same actor.

## 7. Worker reliability

This is the deepest-engineered part of Ray Core, because it is what lets
a cluster survive individual machine and process failure without losing
or silently duplicating work. Eight concrete mechanisms:

### 7.1 Liveness detection

Detection is two-tier: node-level and process-level.

- **Node-level**: every raylet heartbeats to the GCS (global control
  store). "Nodes are marked dead when the detector has missed too many
  heartbeats" — default heartbeat-miss timeout is 30&nbsp;seconds
  (`RAY_gcs_rpc_server_reconnect_timeout_s`, default 60s, governs how
  long a raylet that *lost* its GCS connection keeps retrying before it
  gives up and exits — "every raylet exits" if it can't reconnect
  within that window). A node marked dead has every task/actor on it
  treated as failed, triggering the retry/reconstruction paths below.
- **Process-level**: the raylet spawns and directly supervises worker
  processes; it receives `SIGCHLD` when one exits and immediately knows
  which task/actor died and why (crash vs. clean exit vs. killed), no
  polling needed for local processes.

### 7.2 Hung/stuck-worker detection — a real gap to note

Ray has **no built-in execution-level timeout**. `ray.get(ref,
timeout=...)` only bounds how long the *caller* blocks waiting for a
result; "the timeout in `ray.get()` only applies to the client-side
retrieval operation, not to the execution of the task itself" — the
task keeps running (or hanging) on the worker regardless, and nothing
kills it. A worker stuck in an infinite loop or blocked I/O call is
invisible to raylet's heartbeat/SIGCHLD machinery (the process is
alive, just not making progress) and is only ever caught by whatever
timeout+cancel logic the *caller* bolts on manually via `ray.cancel()`.
This is a documented, unresolved gap in the ecosystem, not a deliberate
design choice with a mitigating alternative.

### 7.3 Crash detection & automatic restart

Once a worker process's death is observed (7.1), Ray's response
differs by unit: a **task** in flight on that worker is a candidate for
the `max_retries` resubmission described in 7.6, rescheduled onto any
other free worker (no identity to preserve). An **actor**'s process
death is handled by `max_restarts`: Ray "automatically restart[s]
actors that crash unexpectedly", respawning the process on a
(possibly different) node and rerunning `__init__` from scratch —
restart is a full re-construction, not a resume, so anything the actor
built up in memory since the last constructor call is gone unless the
actor externalized it itself.

### 7.4 Orphan prevention

Ray explicitly engineers against leaking subprocesses across the whole
raylet → worker → user-spawned-subprocess chain:

- Raylet "maintains a list of 'known' direct children pid it spawns,
  and when the Raylet process receives the `SIGCHLD` signal, it knows
  that one of its child processes ... has died"; any live process that
  is *not* on that known list is treated as an orphan and killed via
  `SIGKILL`. In a chain (raylet → worker → user process A → user
  process B), when the worker dies, raylet kills A because it wasn't
  a direct known child; when A dies, B goes next, and so on.
- For deeper chains or processes that detach, Ray offers
  `RAY_kill_child_processes_on_worker_exit_with_raylet_subreaper`
  (default `false`, Linux 3.4+ only): raylet sets
  `prctl(PR_SET_CHILD_SUBREAPER, 1)` so any orphaned descendant
  reparents to it directly, then raylet "recursively kills any child
  processes and grandchild processes that were spawned by the worker
  after the worker exits ... within 10 seconds after the worker death."
- A separate flag, `RAY_process_group_cleanup_enabled` (default
  `false`), isolates each worker into its own OS process group at spawn
  and cleans the whole group via `killpg` on worker exit — the
  POSIX-native version of the same guarantee. (A process that calls
  `setsid()` escapes this, since it leaves the group.)
- Both are opt-in and off by default — Ray's own docs frame this as
  "a last resort ... not a replacement for proper process management",
  i.e. even Ray does not consider its default posture (best-effort
  known-children SIGKILL) sufficient on its own for orphan-free
  guarantees under all topologies.

### 7.5 Graceful vs. forced shutdown, and draining

Actor termination is a deliberate two-tier API:

```python
# graceful: from inside the actor
ray.actor.exit_actor()
# "waits until any previously submitted tasks finish executing and
#  then exits the process gracefully with sys.exit", calling
#  __ray_shutdown__() if defined.

# forceful: from outside, on the handle
ray.kill(actor_handle)  # or ray.kill(actor_handle, no_restart=True)
# "causes the actor to immediately exit its process, causing any
#  current, pending, and future tasks to fail with a RayActorError";
# will NOT call __ray_shutdown__() or atexit handlers.
```

Ray "waits 30 seconds for the graceful shutdown procedure ... to
complete" before escalating — a default grace period, not an
indefinite wait. Node-level draining (autoscaler scaling a node down)
follows the same shape at the cluster level: idle nodes are drained
rather than yanked, letting in-flight work finish before the node's
resources are reclaimed.

### 7.6 Resource-leak avoidance & worker recycling

`max_calls` bounds how many task invocations one worker process may
serve before Ray exits and replaces it — "used to address memory leaks
in third-party libraries or to reclaim resources that cannot easily be
released, such as GPU memory." Its default is workload-dependent: for
GPU tasks Ray *disables* worker reuse by default (`max_calls=1`,
i.e. exit after every single task) specifically because "the task may
allocate memory on the GPU and may not release it when the task
finishes executing" — recycling the whole process is the only reliable
way to reclaim GPU memory a task leaked; `max_calls=0` opts back into
unbounded reuse when the task is known not to leak.

Independently, a per-node **memory monitor** in the raylet polls
combined worker-heap + object-store + raylet memory every
`RAY_memory_monitor_refresh_ms` (default 250ms) and, once usage crosses
`RAY_memory_usage_threshold` (default 0.95), kills a worker process to
free memory before the node OOMs outright. The kill target is chosen by
an explicit priority policy, not arbitrarily: prefer killing a
**retriable** task/actor first (`max_retries`/`max_restarts` > 0, so the
kill is recoverable), then the caller with the most concurrently running
tasks, then — among a given caller's tasks — the most recently started
one. Tasks killed this way "retr[y] infinitely (not respecting
`max_retries`)"; actors killed this way still respect `max_restarts`
(default 0, i.e. not restarted).

### 7.7 Delivery/execution guarantees when a worker dies mid-task

Layered guarantees depending on unit and configuration:

- **Task retry** (worker crashes mid-task): `max_retries` (default 3,
  `-1` = infinite) resubmits the function on another worker;
  `retry_exceptions` additionally retries on *application-level*
  exceptions the function itself raised (opt-in, since re-running
  arbitrary side-effecting code on a logic error is not always safe).
- **Actor restart** (`max_restarts`, default `0`) vs. **actor task
  retry** (`max_task_retries`, default `0`, at-most-once): if
  `max_task_retries` is `0`, an in-flight call to a dead actor
  surfaces `RayActorError` immediately to the caller — no silent
  re-execution. Setting it `>0` or `-1` gives at-least-once: the call
  is resubmitted to the respawned actor up to N times (or until
  `max_restarts` is exhausted), with execution ordering still
  guaranteed to match original submission order. The docs are explicit
  this "is best suited for read-only actors or actors with ephemeral
  state" — it is not a free correctness upgrade, since a retried call
  can re-execute non-idempotent side effects. `ActorUnavailableError`
  (transient, may self-heal) is distinguished from the terminal
  `RayActorError`/`ActorDiedError`.
- **Object/result recovery** (the object itself, not just the task,
  was lost — e.g. its node died): Ray "will first automatically
  attempt to recover the value by looking for copies of the same
  object on other nodes. If none are found, then Ray will automatically
  recover the value by re-executing the task that previously created
  the value" (lineage reconstruction), bounded by the same retry
  counters as above. Two hard exclusions: objects from `ray.put()` are
  never reconstructable (no producing task to re-run), and actor-task
  outputs are not reconstructable *unless* `max_task_retries` is
  non-zero. If the **owner** of the object (the worker that created the
  `ObjectRef`) has itself died, reconstruction is not attempted at all
  — "Ray does not support recovery from owner failure" — and any
  caller still holding that ref gets a terminal `OwnerDiedError`.

Net effect: Ray defaults to **at-most-once** for actor calls and
**bounded-retry-to-a-new-worker** for stateless tasks, and requires
explicit opt-in plus an idempotency assumption to get anything close to
at-least-once; true exactly-once is not offered anywhere in the model.

### 7.8 Backpressure / overload protection

Two independent levers, both opt-in (unbounded by default):

- `max_pending_calls` on an actor handle caps how many not-yet-started
  calls may be queued against that actor from one handle; once
  exceeded, further `.remote()` calls raise `PendingCallsLimitExceeded`
  synchronously at the call site rather than queuing indefinitely
  (`-1`, the default, is unbounded).
- The documented pattern for bounding an unbounded *producer* of tasks
  is manual: submit a batch, `ray.wait(refs, num_returns=k)` to drain
  completions before submitting more, so "the pending task queue won't
  grow indefinitely and cause OOM" — backpressure is something the
  caller must build with `ray.wait`, not something Ray enforces
  automatically for plain tasks.
- Per-actor concurrency (`max_concurrency`, 7 above) is itself a soft
  overload control: it bounds how much work one actor process runs at
  once, independent of how many calls are queued against it.

## 8. Results

`.remote()` never blocks; it returns an `ObjectRef`, a future backed by
the distributed object store. `ray.get(ref)` blocks until resolved (or
raises the remote exception/`RayActorError`). `ray.wait(refs,
num_returns=k, timeout=...)` is the non-blocking multi-future join,
returning `(ready, remaining)` partitions — used for
first-N-of-M / timeout-bounded fan-in instead of blocking on every
future in submission order. `ray.cancel(ref)` cancels an in-flight task
by its ref.

## 9. Transport/protocol

Ray is built on gRPC for control-plane messages (the owning worker
"schedules the task by sending the task specification over gRPC to the
leased worker") and a Plasma-derived shared-memory object store for
data-plane payloads: large/immutable objects live once in per-node
shared memory and are handed to any local process by reference (no
copy), with a distributed ownership/ref-counting protocol tracking who
holds a reference to what across the cluster so objects are freed once
unreachable.

## 10. Lessons for `Workers`

Direct mapping:

| Ray | `Workers` |
|---|---|
| task (`@ray.remote def`) | stateless thunk fork |
| actor (`@ray.remote class`, `.remote()` spawn) | `Task.Queue` with `warmState` |
| `ObjectRef` / `ray.get` / `ray.wait` | `Fiber[A,E]` / `.get` / race-style join |
| `num_cpus`/`num_gpus`/`resources` dict + `.options()` override | `weight` + per-call override |
| placement group (`PACK`/`SPREAD`, bundles) | multi-task grouping/co-location, if ever needed |
| `max_restarts` (actor) vs `max_retries`/`retry_exceptions` (task) | `Task.Queue`-level restart policy vs per-`Task` `retry` |
| `max_task_retries` at-least-once caveat | our `retry` should carry the same non-idempotent-side-effect warning |
| `max_concurrency` / concurrency groups | per-queue or per-task concurrency limits inside one worker |
| raylet heartbeat + SIGCHLD node/process liveness | coordinator↔worker heartbeat + IPC-channel-death detection |
| raylet known-children list + subreaper/`killpg` orphan kill | coordinator kills the whole worker process group/tree on crash or its own exit |
| `ray.actor.exit_actor()` (drain, then exit) vs `ray.kill()` (immediate) | `Task.Queue` graceful drain-and-stop vs forced kill |
| memory monitor's priority-ordered worker kill | `Workers`' own resource-pressure kill policy, if we build one, should retry-prefer the same way |
| `max_calls` GPU-leak recycling | `Task.Queue` `maxWorkers`-scoped recycle-after-N-tasks threshold |
| `max_pending_calls` / manual `ray.wait`-based backpressure | bounded submission queue per `Task.Queue`, enforced by us rather than left to the caller |
| **absence** of an execution-level task timeout | our per-`Task` `timeout` must actually kill the worker process, not just abandon the `Fiber` client-side |

**Reliability mapping, concretely — crash→retry-with-a-bound, orphan-free
kill, hung-task timeout, worker recycle:**

- **Crash → retry with a bound.** Ray's split (task: `max_retries`
  resubmit-anywhere; actor: `max_restarts` respawn-same-identity +
  `max_task_retries` at-most-once-by-default for the in-flight call) is
  the right shape to copy directly: `Workers`' stateless `fork` should
  behave like a Ray task (retry, no identity to preserve), while
  `Task.Queue` should expose the same two-axis knob (restart the queue
  worker vs. retry the specific in-flight `Task`), defaulting
  at-most-once and documenting the same non-idempotency caveat for any
  opt-in at-least-once mode.
- **Orphan-free kill.** Ray treats this as load-bearing enough to
  engineer three overlapping mechanisms (known-children SIGKILL,
  subreaper, process-group `killpg`) and still calls its own default
  posture insufficient without the opt-in flags. `Workers` should not
  repeat that gap: spawn every worker into its own process group (or
  job object on Windows) unconditionally and kill the group on crash,
  timeout, or coordinator shutdown, so "opt-in for real orphan safety"
  is never a decision the caller has to make.
- **Hung-task timeout.** This is Ray's clearest missed case (7.2): no
  execution-level timeout exists, only a client-side `ray.get(timeout=)`
  that abandons the caller without touching the still-running worker.
  `Task`'s `timeout` field must be enforced by the coordinator killing
  the worker process (or the process group) when it fires, not merely
  by the `Fiber` completing with a timeout error while the underlying
  process keeps running and holding its slot in the concurrency cap.
- **Worker recycle.** Ray's `max_calls` (recycle after N tasks, forced
  to 1 for GPU-bearing tasks by default) and its memory-monitor kill
  (priority-ordered by retriability, then busiest caller, then
  most-recent task) are both real answers to fd/memory leak avoidance,
  not just theoretical knobs. `Task.Queue` should carry an analogous
  recycle-after-N-tasks (or after-memory-threshold) policy per worker,
  and any resource-pressure kill we add should prefer killing
  retriable/idempotent work first the way Ray's does, rather than
  picking arbitrarily.

**Steal:**
- The stateless/stateful split as the *primary* organizing axis, exactly
  mirrored by our thunk-fork vs. `Task.Queue` distinction — Ray's years
  of production use validate this is the right cut, not an artificial one.
- Decorator/definition-time defaults overridable per-call
  (`options()`), giving both a stable declared contract and call-site
  flexibility — maps cleanly onto `Task.Queue` declaring `maxWorkers`/`env`
  while individual `task.queue(q)` calls can still carry their own
  `locks`/`weight`/`timeout`.
- Separate restart-the-process vs. retry-the-call knobs for the
  stateful case, with an explicit warning that retry-after-restart is
  at-least-once and only safe for idempotent/ephemeral-state work. Our
  `retry` semantics for `Task.Queue` should say the same thing rather
  than imply exactly-once.
- `ray.wait(num_returns=, timeout=)` as the shape for bounded fan-in;
  worth having a `Fiber`-level equivalent beyond a plain `Fiber.race`/`gather`.
- The graceful/forceful termination split (`exit_actor()` drains
  in-flight work then exits vs. `ray.kill()` immediate-and-unclean),
  with a bounded grace period (30s default) before escalating, rather
  than one undifferentiated "stop".
- A named, ordered kill-priority policy for resource-pressure
  eviction (retriable first, then busiest caller, then newest task) —
  turns an otherwise arbitrary "kill something" into a documented,
  predictable behavior under load.

**Avoid:**
- Fractional `num_cpus`/`num_gpus` as a *scheduling fiction* (0.5 CPU is
  a bin-packing hint, not an enforced slice) has caused real confusion
  in the Ray community about actual isolation guarantees; if `weight`
  in `Workers` is similarly just an admission-accounting number, say so
  explicitly rather than implying resource isolation.
- Ray's default `max_restarts=0`/`max_task_retries=0` (silent
  fail-fast) is easy to hit by surprise in production; worth deciding
  deliberately whether `Workers`' defaults for `Task.Queue` restart /
  `Task` retry should default to "off" (matches Ray, safest for
  non-idempotent work) or something more forgiving, rather than
  inheriting Ray's choice by default.
- Plasma's shared-memory-per-node design is a JVM-hostile transport
  choice for us; our IPC layer should pick something process-boundary-appropriate
  for the JVM instead of copying the object-store architecture wholesale.
- Ray's opt-in, off-by-default orphan-prevention flags
  (`RAY_kill_child_processes_on_worker_exit_with_raylet_subreaper`,
  `RAY_process_group_cleanup_enabled`) and Ray's own admission that this
  is "a last resort ... not a replacement for proper process
  management" — `Workers` should make orphan-free cleanup unconditional
  and structural (process-group-per-worker from spawn), not a flag the
  caller has to discover and enable.
- Ray's total absence of an execution-level task timeout (7.2): do not
  ship `Task.timeout` as a client-side `Fiber`-abandonment convenience:
  it must actually terminate the worker, or a hung task silently
  occupies a slot in the global concurrency cap forever.
