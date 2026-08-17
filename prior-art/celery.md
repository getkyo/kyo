# Prior Art: Celery

Source: Celery stable docs (docs.celeryq.dev), `userguide/tasks.html`, `userguide/routing.html`,
`userguide/workers.html`, `userguide/canvas.html`, `userguide/configuration.html`,
`userguide/daemonizing.html`, `userguide/monitoring.html`, `getting-started/introduction.html`,
plus targeted searches against celery/celery and celery/billiard on GitHub for process-lifecycle
specifics (PR_SET_PDEATHSIG, pool supervisor reaping).

## 1. What it is

Celery is a distributed task queue for Python: "Task queues are used as a mechanism to
distribute work across threads or machines." It follows a producer/broker/worker model:
"Celery communicates via messages, usually using a broker to mediate between clients and
workers. To initiate a task the client adds a message to the queue, the broker then
delivers that message to a worker." Brokers: RabbitMQ, Redis, Amazon SQS (experimental
SQLite for local dev). Results, if wanted, go to a separate result backend (Redis,
database, RPC, etc.) — the broker and the result store are explicitly decoupled.

## 2. Unit of work

A task is a decorated Python callable:

```python
@app.task
def create_user(username, password):
    User.objects.create(username=username, password=password)
```

Invoked async via `.delay()` (shorthand) or `.apply_async()` (full options):

```python
add.delay(8, 8)                                  # AsyncResult
mytask.apply_async((1, 2), ignore_result=True)
```

`bind=True` gives the task access to `self` (the task instance/context) for retry,
request info, etc. Options are attached at decoration time (`@app.task(serializer='json',
time_limit=300, rate_limit="100/m")`), not at call time — the task's own resource/behavior
policy travels with its definition, and callers only add per-call overrides
(`countdown`, `queue`, `priority`, `eta`).

## 3. Grouping / queues / routing (closest analog to `Task.Queue`)

Queues are declared explicitly with `kombu.Queue`, each bound to an exchange/routing key:

```python
from kombu import Exchange, Queue
app.conf.task_queues = (
    Queue('default', Exchange('default'), routing_key='default'),
    Queue('videos',  Exchange('media'),   routing_key='media.video'),
    Queue('images',  Exchange('media'),   routing_key='media.image'),
)
```

Routing maps *task names* (not call sites) to queues, either exact or by glob pattern —
this is the declarative binding, analogous to `task.queue(q)`:

```python
task_routes = {'feed.tasks.import_feed': {'queue': 'feeds'}}
# or pattern-based:
task_routes = ([
    ('feed.tasks.*', {'queue': 'feeds'}),
    ('web.tasks.*',  {'queue': 'web'}),
],)
```

A caller can still override per-call: `import_feed.apply_async(args=[...], queue='feed_tasks',
routing_key='feed.import')`. `task_default_queue` (default `"celery"`) is the fallback when
nothing routes.

Workers subscribe to a subset of queues at startup — a queue has no dedicated process pool
of its own; workers opt into whichever queues they were started with:

```bash
celery -A proj worker -Q feeds
celery -A proj worker -Q feeds,celery
```

Multiple queues can share one worker or be split across separate worker fleets (e.g. a
dedicated `videos` worker pool vs. a general pool), giving isolation by deployment topology
rather than a first-class "pool" object in the queue declaration itself.

Priority is a queue-level feature layered on top, not part of routing: RabbitMQ queues
declare `x-max-priority`; Redis needs `broker_transport_options = {'queue_order_strategy':
'priority'}`. `task_default_priority`, `task_queue_max_priority`, and per-call
`apply_async(priority=0)` (0 = highest) round it out. `task_inherit_parent_priority`
(default `False`) lets chained/chorded children inherit the parent's priority.

## 4. Declarative config / requirements model

Per-task decorator options double as the requirements model: `rate_limit="100/m"`,
`time_limit`, `soft_time_limit`, `max_retries`, `retry_backoff`, `acks_late`, `queue`
(via routing), `priority`. All attach to the task class/decorator, so a task's resource
contract is defined once, alongside its code, not re-specified by every caller. A task
subclass (`class BaseTaskWithRetry(Task): autoretry_for = (...); max_retries = 5;
retry_backoff = True`) is the idiom for sharing a policy bundle across many tasks — the
rough equivalent of a reusable `Task` config object.

## 5. Worker/process model

`celery -A proj worker -l INFO -Q foo,bar,baz` starts one worker node; `--hostname`
disambiguates multiple nodes per machine. The worker owns a *pool* of execution
contexts — pluggable: **prefork** (multiprocessing via `billiard`, Celery's own fork of
the stdlib `multiprocessing`, the default and the one that gives real OS-process isolation
for CPU-bound/unsafe code), **eventlet**/**gevent** (greenlet-based, for I/O-bound
concurrency), **threads**, **solo** (single blocking process, limited remote-control
support). The pool type is a worker-startup choice, not a per-task choice — task placement
(which queue) is orthogonal to how the receiving worker executes it.

## 6. Scheduling & concurrency control

- `--concurrency=N` sets pool size, defaulting to CPU count — this is the per-worker cap,
  not a global one; the global cap emerges from how many workers/pools you deploy.
- `worker_prefetch_multiplier` controls how many messages a worker reserves ahead of
  finishing current work (throughput vs. fairness knob); reduced automatically after
  broker disconnects via `worker_enable_prefetch_count_reduction`.
- `--autoscale=max,min` (e.g. `--autoscale=10,3`) dynamically resizes the pool with load,
  supported only on prefork/gevent.
- Rate limits are per-task and adjustable live: `app.control.rate_limit('myapp.mytask',
  '200/m')`.
- No single "global concurrency cap" primitive exists across all queues/workers in one
  place — global capacity is the sum of independently configured worker fleets, each with
  its own `--concurrency`. This is a real gap relative to what `Workers.run(Config
  (parallelism=N))` wants to guarantee.

## 7. Worker Reliability

This is the section most directly relevant to `Workers`' fork-and-IPC model: Celery's
reliability machinery splits into distinct, independently-tunable mechanisms rather than
one "make it reliable" flag. Each is covered below with the concrete knob.

### 7.1 Liveness / health detection

Three cooperating bootsteps, each independently disableable:

- **Heartbeat** (`--without-heartbeat` to disable): "Sent every minute, if the worker
  hasn't sent a heartbeat in 2 minutes, it is considered to be offline." The heartbeat
  event carries `hostname, timestamp, freq, sw_ident, sw_ver, sw_sys, active, processed`.
  This is a liveness signal independent of task completion — a worker that is alive but
  idle still proves it's alive.
- **Gossip** (`--without-gossip` to disable): a bootstep where each worker passively
  subscribes to other workers' events over the broker, keeping a logical clock and
  learning when a peer stops sending heartbeats — a peer-to-peer liveness view, not just
  a central monitor.
- **Mingle** (`--without-mingle` to disable): a startup-time synchronization step where a
  newly-started worker gathers the set of revoked task ids and logical clocks from peers
  before joining, so it doesn't re-execute something already revoked cluster-wide.
- Active checks on demand: `app.control.ping()` (are you alive, with a timeout) and
  `app.control.inspect()` (active/reserved/scheduled tasks, per-worker stats) — a
  pull-based health probe layered on top of the push-based heartbeat.

### 7.2 Hung / stuck-task detection and recovery

Celery has no generic "is this worker wedged" detector beyond heartbeat absence at the
process level. The tool that actually exists is per-task, not per-worker: `time_limit`
(hard — the child process executing the task is forcibly killed) and `soft_time_limit`
(a catchable signal delivered first, for cleanup, before the hard kill would land). Both
are enforced by the pool's own timer, not by the broker or an external watchdog, so a
task that ignores its soft signal and never returns is still bounded by the hard limit —
the mechanism that catches a stuck *task* also frees the *worker slot* it was occupying.

### 7.3 Crash detection and automatic restart

Two different scopes, two different mechanisms:

- **Child-process crash within a running worker node**: the prefork pool's supervisor
  thread repeatedly calls `_maintain_pool()`, which calls `_join_exited_workers()` to reap
  any dead child processes and release their slots, then `_repopulate_pool(exitcodes)` to
  fork fresh replacements — this is Celery managing its own child pool, transparent to the
  application.
- **Whole worker-node crash**: Celery does *not* self-restart at this scope. It relies on
  an external process supervisor. The documented systemd unit sets `Restart=always` so the
  init system relaunches the whole `celery worker` process if it dies; the init-script
  alternative is `/etc/init.d/celeryd`. Reliability at the node level is explicitly
  delegated to deployment tooling, not built into Celery itself.

### 7.4 Orphan prevention

Two distinct guards, at two different layers:

- **Forked children outliving their immediate parent**: on Linux, since Celery 5.2, worker
  child processes register `PR_SET_PDEATHSIG` via `prctl(2)` so that if the parent process
  dies (crash, `kill -9`, anything that skips normal shutdown), the kernel delivers
  `SIGKILL` to the children automatically — no separate reaper process needed, the
  guarantee is enforced by the OS.
- **Whole worker-node group outliving the supervisor**: a known failure mode is that a
  process supervisor's STOP/TERM signal to the top-level Celery process does not
  automatically propagate to its own children unless the whole *process group* is
  targeted; the documented fix under supervisord is `stopasgroup=true`, which sends the
  signal to the entire group instead of just the tracked PID.

### 7.5 Graceful vs. forced shutdown and draining

A layered escalation, not a single switch:

- **Warm shutdown** (`TERM`, or first `INT`/Ctrl-C): the worker stops accepting new work
  and waits, by default indefinitely, for currently-running tasks to finish. Additional
  `TERM` signals during warm shutdown are ignored (won't restart the clock or escalate).
  As of 5.6, heartbeats are maintained *during* warm shutdown on the prefork pool, so the
  worker doesn't look dead to the rest of the cluster while it's draining.
- **Soft shutdown** (added 5.5, `worker_soft_shutdown_timeout`, positive float seconds): a
  *time-bounded* warm shutdown inserted just before cold shutdown — running tasks get up
  to this many seconds to finish; if the window expires, it escalates to cold shutdown and
  cancels whatever is still running. `worker_enable_soft_shutdown_on_idle` extends this to
  idle workers that are only holding reserved ETA tasks.
- **Cold shutdown** (`QUIT`, or a second `INT` while warm shutdown is in progress): stop
  all currently executing tasks and terminate immediately — no draining.
  `REMAP_SIGTERM=SIGQUIT` (env var) reroutes `TERM` itself to mean cold shutdown instead
  of warm, for environments (e.g. certain container platforms) whose default termination
  signal should not wait indefinitely.
- **Hard shutdown**: further repeated `INT` signals force immediate termination via an
  internal `WorkerTerminate` exception — the final, no-cleanup escape hatch.

### 7.6 Resource-leak avoidance and worker recycling

Two independent thresholds, either of which retires a child process and forks a fresh one
in its place: `--max-tasks-per-child` / `worker_max_tasks_per_child` (retire after N
tasks — the documented rationale is "to avoid potential memory leaks in uncontrolled
dependencies") and `--max-memory-per-child` / `worker_max_memory_per_child` (retire once
resident memory crosses a threshold). Both exist because Celery cannot control leaks
inside arbitrary application code or C extensions the task calls into — recycling the
process is the only leak-proof mitigation available at the framework layer. The
documented caveat: set the threshold too low and the worker spends more time restarting
child processes than executing tasks (a 1-second child-process startup cost against a
`max_tasks_per_child=1` policy caps throughput at 60 tasks/minute regardless of how fast
the task itself runs) — recycling cost must be amortized against task volume.

### 7.7 Delivery / execution guarantees when a worker dies mid-task

Celery's default is **at-most-once**: a message is acknowledged to the broker *before* the
task executes, so if the worker dies mid-execution the task is simply lost (the broker
already considers it delivered and won't redeliver). Flipping `acks_late=True` moves the
ack to *after* completion, which converts the guarantee to **at-least-once** — but
introduces the possibility of double execution if the worker dies after finishing the
side effects but before the ack lands, which is why the docs are explicit that
`acks_late` tasks must be idempotent. A further hole exists even under `acks_late`: if the
child process executing the task is killed directly (`sys.exit()` or a signal, e.g. OOM
killer), the worker still acks the message by default (it looks like a normal completion
from the broker's point of view) — `task_reject_on_worker_lost` closes that hole by
rejecting/redelivering instead. `task_acks_on_failure_or_timeout` (default enabled) is a
distinct anti-poison-message guard: even a task that failed or timed out gets acked, so a
task that reliably crashes its worker cannot loop forever redelivering itself purely
through the ack mechanism (bounding *that* failure mode is `max_retries`' job, on the
application side, not the transport's). **Exactly-once delivery is not offered** — the
docs are explicit that true exactly-once would need a two-phase-commit-style protocol and
is not worth the cost; idempotent task design is the recommended (and only) answer.
`revoke()` state is optionally persisted via `--statedb` specifically so a revoked task
id survives a worker restart and isn't accidentally re-executed — the one place Celery
does something resembling deduplication, and it's scoped narrowly to revocation, not to
general at-least-once delivery.

Retry policy itself (the bound on redelivery/re-execution, whichever mechanism triggered
it): manual `raise self.retry(exc=exc, countdown=60)` in a `bind=True` task, or automatic
`@app.task(autoretry_for=(FailWhaleError,), retry_kwargs={'max_retries': 5})`.
`default_retry_delay` defaults to 180s; `max_retries` defaults to 3 (`None` = infinite —
an explicit opt-in to unbounded retry, not the default). `retry_backoff=True` (base delay
1s, multiplies per attempt), `retry_backoff_max` (default 600s cap), and `retry_jitter`
(default `True`, randomizes the delay to avoid a thundering herd when many tasks fail
together) form one coherent triple. `time_limit`/`soft_time_limit` (7.2) are enforced
per-worker-instance, not cluster-wide.

### 7.8 Backpressure / overload protection

`worker_prefetch_multiplier` bounds how far ahead of actual capacity a worker reserves
messages, preventing one worker from hoarding the queue while sitting idle relative to its
concurrency; it's automatically reduced after a broker reconnect via
`worker_enable_prefetch_count_reduction` so a recovering worker doesn't immediately
re-flood itself. `rate_limit` throttles a specific task type
(`app.control.rate_limit('myapp.mytask', '200/m')`, adjustable live, or disabled globally
via `worker_disable_rate_limits`). `broker_connection_retry_on_startup` and
`broker_connection_retry` govern reconnection behavior on broker loss instead of an
unbounded retry storm, and `worker_cancel_long_running_tasks_on_connection_loss` gives an
explicit policy choice for what happens to in-flight work when the broker connection itself
drops. `--autoscale=max,min` bounds how far the pool can grow under load, which is itself a
backpressure control on resource consumption, not just a throughput one.

## 8. Results

`.delay()`/`.apply_async()` return an `AsyncResult` immediately — a future handle, not a
value: `.id`, `.get(timeout=...)` (blocks, raises the task's exception if it failed),
`.ready()`, `.forget()`. Requires a configured `result_backend` (none by default — you can
run fire-and-forget with no backend at all). `chain` returns the last task's `AsyncResult`
with `.parent` links back through the pipeline; `group` returns a `GroupResult` (list of
child results, `.join()`); `chord` runs a group then feeds all header results as a list
into one callback task: `chord((add.s(i, i) for i in range(10)), tsum.s())()`.

## 9. Transport/protocol

Broker-agnostic by design (RabbitMQ/Redis/SQS interchangeable via `broker_url` scheme).
AMQP (RabbitMQ) is the reference transport with native priority queues and per-message ack;
Redis is popular but weaker on ordering/priority (needs `queue_order_strategy` set
explicitly). This layer is out of scope for `Workers` (no external broker — IPC is
direct to forked processes) but explains *why* Celery's ack/retry vocabulary exists: it is
built on at-least-once message delivery semantics, not a direct process handle. Note that
remote control (revoke, ping, inspect, rate_limit) only works over RabbitMQ or Redis — the
reliability-control-plane features in section 7 are themselves transport-dependent in
Celery, a constraint `Workers` doesn't inherit since it owns the IPC channel directly.

## 10. Lessons for `Workers`

Direct mappings:
- Named queue + routing (`task_queues` + `task_routes` + `-Q`) → `Task.Queue(name)` +
  `task.queue(q)`. Celery separates *queue declaration* (capacity/binding policy) from
  *routing* (which task goes where) from *worker subscription* (`-Q`); our design should
  keep the same three-way split rather than collapsing routing into the queue object.
- `acks_late` + `task_reject_on_worker_lost` + retry/backoff → our per-task `retry`.
  Celery's hard lesson: message-ack timing and retry-on-worker-death are two different
  failure modes that both need independent settings, not one flag.
- `AsyncResult` → `Fiber`. Celery's `.get(timeout=)` / `.ready()` is the same future
  shape; a real `Fiber` is strictly better (no separate result-backend infra needed for
  the common intra-process case).
- `time_limit` / `soft_time_limit` → our `timeout`. Keep both a hard kill and a
  soft/cancellable phase, matching Celery's two-tier design.
- Task-level declarative options (`rate_limit`, `time_limit`, decorator-attached policy)
  validate the design of putting `locks`, `weight`, `isolation`, `timeout`, `retry`, `env`
  on the `Task` itself rather than at each call site.

Reliability-specific mappings (section 7):
- **Crash → retry with a bound**: Celery does not auto-retry a worker crash by default;
  a crash only becomes a redelivery if `acks_late` + `task_reject_on_worker_lost` are both
  set, and even then the redelivered task consumes from the *same* `max_retries` budget as
  an application-level failure. `Workers` should route a worker-process crash mid-task
  through the identical bounded-retry path as a thrown exception inside the task, so a
  task that reliably kills its worker cannot retry forever — it hits the same `retry`
  ceiling either way, never a separate unbounded crash-recovery loop.
- **Orphan-free kill**: adopt the `PR_SET_PDEATHSIG` pattern directly rather than
  delegating to external supervision. Celery's orphan story has two tiers (OS-level
  parent-death signal for immediate children; `stopasgroup`-style process-group signaling
  for the whole node under an external supervisor) precisely because it doesn't control
  its own top-level restart. `Workers` forks its own children directly, so it should own
  the strong guarantee itself: every forked worker dies with its parent, full stop, not
  "as long as the deployment's init system was configured correctly."
- **Hung-task timeout**: adopt the soft/hard two-tier `time_limit`/`soft_time_limit`
  design as `Task.timeout` — a soft signal first (lets a cooperative task or `Async`
  fiber cancel cleanly), then a hard kill of the worker process if the grace window
  expires. This has a second job beyond bounding one task: it's the only mechanism that
  guarantees a wedged worker eventually returns to the pool, so it protects the whole
  worker slot, not just the offending task.
- **Worker recycle**: adopt `max-tasks-per-child` / `max-memory-per-child` on
  `Task.Queue`'s `warmState`/pool config, with the same explicit amortization warning —
  recycling too aggressively trades leak-safety for throughput, so the threshold needs to
  be a visible, tunable number, not a hidden default.
- **Liveness independent of task completion**: Celery's heartbeat/gossip/mingle triad
  proves a worker is alive even when idle, and lets peers (not just a central monitor)
  notice a missing heartbeat. `Workers`' coordinator should maintain an explicit
  heartbeat/ping channel to each forked worker independent of task traffic, so a worker
  wedged before ever picking up a task (e.g. stuck in classpath/JVM init) is still
  detected, not just one that goes quiet mid-task.
- **Delivery guarantee, stated honestly**: Celery deliberately does not offer
  exactly-once and says so plainly, pushing idempotency to the task author; `Workers`
  should make the same choice explicitly rather than imply a stronger guarantee, and tie
  it to the existing `isolation` field so a retried task's side-effect contract
  (safe to re-run vs. not) is a declared property of the `Task`, not a docs footnote.

**Steal:**
- Separate queue *declaration* from task *routing* from worker *subscription* — three
  independently composable concerns, not one config blob.
- Distinguish "ack before running" vs. "ack after running" vs. "worker died mid-run" as
  three named failure points, each independently configurable (`acks_late` /
  `task_reject_on_worker_lost` / `task_acks_on_failure_or_timeout`).
- `retry_backoff` + `retry_backoff_max` + `retry_jitter` as a triple, not just a flat delay
  — jitter is called out explicitly as a thundering-herd guard, worth keeping as a named
  knob rather than folding into `retry_backoff`.
- Hard vs. soft timeout as two settings, so cleanup code gets a chance before the kill,
  and so a stuck task always eventually frees its worker slot.
- OS-level parent-death guarantee (`PR_SET_PDEATHSIG`) for orphan prevention, owned by the
  framework itself rather than pushed to deployment config.
- Multi-tier shutdown (warm → time-bounded soft → cold → hard) instead of a single
  graceful/forceful toggle.

**Avoid:**
- No global concurrency cap primitive: Celery's total capacity is an emergent property of
  how many independent worker fleets you deploy with `--concurrency`, not something you
  can state once. `Workers.run(Config(parallelism=N))` should keep the explicit global cap
  as a first-class guarantee — that's a genuine improvement over Celery's model, not
  something to imitate.
- Whole-node crash recovery pushed entirely to external process supervision (systemd
  `Restart=always`) with no framework-level fallback — leaves reliability dependent on
  deployment configuration being correct. `Workers` owns its own worker lifecycle end to
  end, so this gap shouldn't be inherited.
- Priority is bolted on per-broker (RabbitMQ `x-max-priority` vs. Redis
  `queue_order_strategy`) with different semantics and caveats each way; if `Task.Queue`
  wants priority, define it once at the queue-abstraction level so it doesn't leak
  broker-specific behavior to callers.
- `acks_late` correctness is caller-owned ("ensure idempotency") with no structural help
  from the framework — worth doing better by tying `retry` + `isolation` together so a
  retried task's partial side effects are a documented, enforced contract rather than a
  docs warning.
- Reliability control-plane operations (revoke, ping, inspect, rate_limit) are only
  available over specific brokers (RabbitMQ/Redis) in Celery — an avoidable limitation
  since `Workers` owns its IPC channel and can make every control operation universally
  available.
