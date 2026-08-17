# Prior art: Erlang/OTP (gen_server, supervisor, poolboy)

## 1. What it is

Erlang/OTP is the standard library and design-pattern layer ("Open Telecom Platform") built
on top of the Erlang runtime's process model. Every unit of concurrency is a lightweight,
isolated process with its own heap and mailbox, communicating only by asynchronous message
passing (no shared memory). OTP formalizes recurring process shapes as **behaviours**:
`gen_server` (stateful request/reply worker), `supervisor` (fault-tolerance tree node),
`gen_statem`, `gen_event`, `application`. `poolboy` is a widely used third-party library
(not part of OTP itself) built on top of `gen_server`/`supervisor` that adds worker-pool
checkout/checkin semantics, the piece OTP itself does not provide out of the box.

This is the mature, decades-old answer to "stateful worker processes that must be pooled,
supervised, and recovered from crashes" — precisely `Workers`'s core problem.

## 2. Unit of work: a call/cast to a `gen_server`

A `gen_server` is a stateful process. State is threaded explicitly, one message at a time:

```erlang
-callback init(Args :: term()) ->
    {ok, State} | {ok, State, Action} | {stop, Reason} | ignore | {error, Reason}.

-callback handle_call(Request, From :: {pid(), Tag}, State) ->
    {reply, Reply, NewState} |
    {reply, Reply, NewState, Action} |
    {noreply, NewState} |
    {stop, Reason, Reply, NewState} |
    {stop, Reason, NewState}.

-callback handle_cast(Request, State) ->
    {noreply, NewState} | {noreply, NewState, Action} | {stop, Reason, NewState}.
```

- `gen_server:call(ServerRef, Request, Timeout)` — synchronous, blocks the caller until a
  `{reply, Reply, ...}` is returned by `handle_call/3`. Default `Timeout` is **5000 ms**;
  `infinity` waits forever. On timeout the *caller* exits with
  `{timeout, {gen_server, call, [...]}}` — the server keeps running, it is the client side
  that fails. Since OTP 24, late replies to an already-timed-out call are silently discarded
  via process aliases (no leaked reply landing in the caller's mailbox later).
- `gen_server:cast(ServerRef, Request)` — fire-and-forget, returns `ok` immediately, no
  reply, no error if the server doesn't exist.
- `send_request/2,4` + `receive_response/wait_response/check_response` — a third mode:
  async call with an explicit request id, for callers that want overlap without giving up
  the reply.
- State is single-threaded through the process: each callback receives the current `State`
  and must return the `NewState`; there is no concurrent mutation because only one message
  is handled at a time. This is the direct analogue of our "warm state reused across tasks"
  — the state literally *is* the process, not a value passed alongside it.

## 3. Grouping / pools: poolboy

`poolboy` wraps N `gen_server` workers behind checkout/checkin:

```erlang
{pool1, [{size, 10}, {max_overflow, 20}], [{worker_module, my_worker}, ...]}

Worker = poolboy:checkout(Pool).             % checkout(Pool, true, 5000)
poolboy:checkin(Pool, Worker).
poolboy:transaction(Pool, fun(W) -> gen_server:call(W, Req) end).
```

- `size` — steady-state pool of always-warm workers.
- `max_overflow` — extra, transient workers spun up past `size` under load and torn down
  once idle. This two-tier shape (warm floor + elastic ceiling) is directly our
  `maxWorkers` question: poolboy treats it as two numbers, not one.
- `checkout/3` spec: `checkout(Pool, Block :: boolean(), Timeout :: timeout()) -> pid() | full`.
  Default `Block = true`, default `Timeout = 5000`. With `Block = false` and no worker
  available (pool and overflow both exhausted), it returns the atom `full` immediately —
  explicit non-blocking backpressure signal, caller decides queue/reject/retry. With
  `Block = true` the caller's own `gen_server:call` to the pool manager blocks until a
  worker is checked in or `Timeout` elapses, at which point it throws like any other
  `gen_server:call` timeout. `transaction/2,3` is the recommended entry point: checkout,
  run the fun, checkin in an `after`/try-guard so a crash inside the fun still returns the
  worker (or lets it die and get replaced — see §7).

## 4. Declarative config / requirements model

OTP's declarative unit is the **child spec**, consumed by a supervisor:

```erlang
#{id => my_worker,
  start => {my_worker, start_link, [Args]},
  restart => permanent,      % permanent | transient | temporary
  shutdown => 5000,          % ms, or brutal_kill, or infinity
  type => worker,            % worker | supervisor
  modules => [my_worker]}
```

and the supervisor's own flags:

```erlang
#{strategy => one_for_one,
  intensity => 1,     % max restarts ...
  period => 5,         % ... within this many seconds (defaults)
  auto_shutdown => never}
```

Map this onto `Workers`:
- child spec `start` (`{M,F,A}`) == our `Task.Queue(name, {classpath, maxWorkers, warmState})`
  — a declarative "how to build this worker" the supervising layer owns, not something a
  caller instantiates directly.
- `restart: permanent/transient/temporary` == our per-`Task` `retry` policy dimension:
  *always* respawn regardless of exit reason, *only* on abnormal exit, or *never*.
- `intensity`/`period` == a direct model for capping retry storms globally, not just
  per-task (see §7).
- poolboy's pool config (`size`, `max_overflow`, `worker_module`) == our `Task.Queue`'s
  `maxWorkers` + classpath/warmState — the config is a value, constructed once, handed to
  the pool starter, never mutated in place.

## 5. Worker/process model — stateful `gen_server` == our stateful queue

This is the closest structural analogue in the whole ecosystem. A `gen_server` process:

1. Runs `init/1` once, producing initial `State` — our "warm resource acquired once,
   reused across tasks."
2. Serves a sequence of `call`/`cast` messages, each handler reading and returning `State`
   — our per-task invocation against the already-warm worker.
3. Persists between requests until explicitly stopped or crashed — the whole point of a
   *named* `Task.Queue` versus one-shot `Workers.fork`.
4. On uncaught exception, the process simply dies (no silent catch-and-continue); OTP's
   supervision layer, not the worker's own code, decides what happens next. This
   separation — worker code assumes success and does not defensively handle its own
   crash-recovery — is the "let it crash" idea (§7) and argues for keeping `Task`'s
   `retry`/`isolation` policy outside the task body, at the `Workers` supervision layer,
   exactly as OTP keeps restart policy in the supervisor, not the `gen_server` callback
   module.

`gen_statem` (not detailed above but worth naming) generalizes `gen_server` with explicit
FSM states — relevant if a warm worker's readiness (cold / warming / ready / draining)
ever needs to be a first-class state machine rather than an implicit boolean.

## 6. Scheduling & concurrency control

- The pool manager (`poolboy`'s own `gen_server`) is itself a serialization point: checkout
  requests queue on it, so ordering and fairness are whatever that single process's mailbox
  gives you (FIFO by arrival, `strategy` controls whether *idle worker reuse* is LIFO —
  favors warming/keeping recently-used workers hot, cache-friendly — or FIFO — round-robins
  all workers evenly).
- Backpressure is explicit and caller-chosen, not implicit: `Block=false` gives an
  immediate `full` signal (reject/shed), `Block=true` gives bounded blocking via `Timeout`
  (queue-with-deadline). There is no unbounded queue by default — a design worth stealing
  directly for our global `parallelism` cap and per-queue `maxWorkers`: make the "pool
  exhausted" behavior a caller-visible choice (reject-now vs wait-with-timeout), not a
  hidden unbounded buffer.
- `max_overflow` is the release valve for burst load without paying the steady-state cost
  of always running `size + max_overflow` workers — directly informs whether `Workers`
  should support a soft/hard `maxWorkers` split per queue rather than one hard number.

## 7. Worker reliability — the heart of OTP

Everything else in OTP (behaviours, pools, config) exists in service of this section.
Reliability is not a feature bolted onto `gen_server`/`supervisor`; it *is* what they are
for. This is the maturity benchmark `Workers` should be measured against.

### 7.1 Crash detection: links vs monitors

The two low-level primitives everything else (including supervisors) is built from:

- **Links** (`link/1`, `spawn_link`) are *bidirectional*: "there can only be one link
  between two processes," and when either side terminates, it sends an exit signal to the
  other. By default an exit signal kills the receiver too (fate-sharing, propagates
  transitively unless trapped). A process can set `process_flag(trap_exit, true)`,
  converting incoming exit signals into ordinary messages `{'EXIT', SenderPid, Reason}`
  instead of dying — this is exactly how a supervisor stays alive to observe and react to
  a child's death rather than dying alongside it.
- **Monitors** (`erlang:monitor(process, Pid)`) are *unidirectional*: the monitoring
  process gets a `{'DOWN', Ref, process, Pid, Reason}` message when the monitored process
  dies (or immediately with `Reason = noproc` if it's already dead), with no risk of being
  killed itself and no need to trap anything. Multiple independent monitors can target the
  same process; `erlang:demonitor(Ref)` cleanly stops watching without side effects.
- Supervisors use links + `trap_exit` internally (they must stay alive through a child's
  death to act on it). General "watch but don't couple fate" code prefers monitors. For
  `Workers`, a forked task's `Fiber` watching its worker process is a monitor relationship
  (passive, no risk of being taken down with it); the supervising `Task.Queue` watching and
  reviving its own workers is a link+trap_exit relationship (must react, is structurally
  responsible for the child).

### 7.2 Restart strategies: which siblings are affected

How a supervisor reacts to *which* child died, when it has more than one:

- `one_for_one` (default): only the dead child restarts, siblings untouched.
- `one_for_all`: the dead child's siblings are all killed and restarted too — for children
  that are mutually dependent and can't run correctly in a partially-restarted state.
- `rest_for_one`: only the children started *after* the dead one (in start order) are
  killed and restarted — for a pipeline where later stages depend on earlier ones but not
  vice versa.
- `simple_one_for_one`: a template for many *dynamically added* homogeneous children (all
  the same start function) — structurally the closest strategy to a worker pool: every
  child is "another worker of this pool," added/removed at runtime rather than fixed at
  supervisor-start time. Directly analogous to `Task.Queue`'s dynamic worker set.

### 7.3 Restart intensity and escalation

The circuit breaker on restart storms:
```erlang
#{strategy => one_for_one, intensity => 1, period => 5}
```
"If more than `MaxR` restarts occur within `MaxT` seconds, the supervisor terminates all
child processes and then itself." Default `intensity=1`, `period=5`: more than one restart
within 5 seconds and the supervisor gives up. Critically it *escalates* rather than
spinning forever: "the termination reason for the supervisor itself in that case will be
`shutdown`," and "when the supervisor terminates, then the next higher-level supervisor
takes some action. It either restarts the terminated supervisor or terminates itself" —
the failure propagates one level up the supervision tree, which may retry, back off, or
itself escalate further, all the way to the application terminating if nothing above can
absorb it. Nothing retries forever silently. This is the concrete model for capping
`Workers`'s per-task `retry` count/window and for what happens when a worker in a stateful
queue keeps dying on `init` (warm-state acquisition failing repeatedly): escalate rather
than spin.

**Child restart types** control *whether* a given child is a restart candidate at all:
`permanent` (always restart, any exit reason), `transient` (restart only on abnormal exit;
a clean/expected exit is left dead), `temporary` (never restart). This maps directly onto
a `Task`'s retry semantics being conditional on *how* it failed (timeout vs explicit
cancel vs uncaught exception), not just a blanket "retry N times."

### 7.4 "Let it crash"

Worker code is written assuming success — no defensive try/catch-and-recover scattered
through business logic. Failures are isolated (a process crash cannot corrupt another
process's state, since nothing is shared) and handled structurally by a supervisor, not
locally by the failing code. Rationale, quoted from Fred Hébert's summary: many bugs are
transient/statistical rather than deterministic logic errors, so "restarting tends to make
them disappear altogether" — a fresh process with fresh state often just works where the
old one didn't. This argues for `Workers` keeping task `retry`/crash-recovery entirely in
the supervising layer (the `Task.Queue` / `Workers.run` runtime), never inside the user's
thunk.

### 7.5 In-flight work when a worker dies mid-call: at-most-once by default

`gen_server:call` documents this precisely: "the function returns an error if the
`gen_server` died before a reply was sent," and enumerates the exit reasons the caller can
see, including "the server process exited during the call, with reason `Reason`." In
practice `gen_server:call` is implemented so the caller detects the server's death
promptly (it fails with the server's exit reason) rather than only discovering the problem
after the full call `Timeout` elapses — death and timeout are distinguishable outcomes, not
conflated into one generic failure. There is **no automatic redelivery**: if the server
dies mid-call, the in-flight request is simply lost from the server's point of view (it
never gets replayed against a restarted replacement), and the caller's job is to decide
what to do — this is at-most-once semantics by default, exactly the same default any
system built on process crash + restart has to reckon with.

OTP does not ship an at-least-once primitive itself; the standard idiom layered on top is:
persist the work item (via `mnesia`, `disk_log`, or an external durable queue) *before*
dispatching it to a worker, and only remove/mark it done after an explicit acknowledgment
from the worker — a crash before the ack leaves the item in the durable store to be
redelivered (by a supervisor-driven or poll-driven redelivery loop), and the workload must
be written idempotently since redelivery can duplicate. This is the standard job-queue
pattern (also used well outside Erlang, e.g. by durable-queue-backed task systems); OTP's
role in it is only the crash-detection/restart half, not the durability half.

For `Workers`: `Workers.fork`'s default should be treated as at-most-once (mirrors
`gen_server:call` exactly — the `Fiber` fails distinctly on "worker died" vs "task timed
out," and the in-flight thunk is not silently rerun). Any at-least-once behavior for a
`Task` (its `retry` policy re-attempting after a crash) is an explicit opt-in the caller
requests, not a hidden default, and it is the caller's responsibility that the thunk is
safe to run twice (idempotent) if `retry` is configured that way — `Workers` does not need
to build OTP-style durable persistence, but it must document the same at-most-once ->
at-least-once distinction explicitly rather than leaving it implicit.

### 7.6 Hung-process detection: call timeouts only, no preemption-based hang detection

OTP has exactly one first-class mechanism for a stuck worker: the *caller's*
`gen_server:call` `Timeout`. There is no server-side "this handler has been running too
long, kill it" facility in `gen_server` itself. The BEAM scheduler does preempt processes
via reduction counting for scheduling fairness (each process gets a bounded number of
reductions — roughly one per function call — before yielding to the next runnable process),
but that mechanism exists purely to keep the scheduler fair among many processes; it is
*not* hang detection and does not stop or flag a process that is legitimately still
computing (or stuck in a tight loop with no function-call yield points, historically a real
gap — some non-yielding BIFs could starve the scheduler before later OTP versions fixed
them). A `gen_server` that hangs (deadlocked waiting on another call, or genuinely stuck)
keeps running and keeps holding its process/mailbox resources indefinitely unless something
external calls `exit(Pid, kill)` on it (untrappable, unlike a plain exit signal) — nothing
in OTP does that automatically.

For `Workers`, this is the argument for `Task.timeout` being enforced from the *supervising*
side (the queue/fiber layer), not something the worker process is trusted to self-police,
and for that enforcement to culminate in an actual forced kill of the worker/OS process
(the moral equivalent of `exit(Pid, kill)`) rather than merely failing the caller's `Fiber`
and leaving a runaway worker process consuming a concurrency-cap slot forever — that failure
mode (caller times out, worker silently keeps running and stays "checked out") is exactly
what OTP does *not* solve for you automatically; the pool/supervisor layer must be the one
that owns killing the unresponsive worker.

### 7.7 Graceful shutdown: `terminate/2`, shutdown timeouts, `brutal_kill`

A supervisor's child spec `shutdown` field governs how a child is stopped, with three modes:

- **`brutal_kill`**: "the child process is unconditionally terminated using
  `exit(Child, kill)`" — immediate, no chance to clean up.
- **integer timeout (ms)**: "the supervisor tells the child process to terminate by calling
  `exit(Child, shutdown)` and then waits for an exit signal back. If no exit signal is
  received within the specified time, the child process is unconditionally terminated using
  `exit(Child, kill)`" — a graceful-then-forced two-step, giving the child's `terminate/2`
  callback a bounded window to release resources (close file handles, flush a warm
  connection, etc.) before being killed outright.
- **`infinity`**: no forced-kill fallback; reserved for children that are themselves
  supervisors, "to give the subtree enough time to shut down" recursively.

`terminate/2` is the `gen_server` callback invoked (when reachable — a `brutal_kill` or an
untrapped fatal signal skips it) to let a worker release its warm resource cleanly before
exiting. This is the precise model for `Workers`' warm-state teardown: a stateful
`Task.Queue` worker needs a `terminate`-equivalent hook to release its `warmState`
(close a connection, flush a buffer) on planned shutdown, with a bounded grace period, and
an unconditional kill fallback if that grace period is exceeded — not an unbounded wait for
cooperative shutdown.

### 7.8 poolboy: worker death and pool refill

poolboy demonstrates the pool-specific slice of this: keeping the pool's advertised
capacity truthful across worker crashes, and never leaking a checked-out slot when its
*client* (not the worker) dies.

- The pool manager traps exits and links to every worker it starts:
  ```erlang
  init({PoolArgs, WorkerArgs}) ->
      process_flag(trap_exit, true),
      ...
  ```
  new workers are created via `new_worker(Sup)` (`supervisor:start_child(Sup, [])` then
  `link(Pid)`), so a worker's death arrives at the pool manager as a trapped `'EXIT'`:
  ```erlang
  handle_info({'EXIT', Pid, _Reason}, State) ->
      case ets:lookup(Monitors, Pid) of
          [{Pid, _, MRef}] ->
              true = erlang:demonitor(MRef),
              true = ets:delete(Monitors, Pid),
              NewState = handle_worker_exit(Pid, State),
              {noreply, NewState};
  ```
  `handle_worker_exit/2` immediately spawns a replacement (`new_worker/1`) and, if another
  client is already waiting on checkout, hands it the fresh worker directly — the pool's
  advertised `size` is kept whole across a crash without the caller ever seeing "pool
  temporarily short a worker."
- Symmetrically, poolboy also monitors the **client** for the duration of a checkout
  (`MRef = erlang:monitor(process, FromPid)`), so if the *caller* crashes while still
  holding a checked-out worker, the pool detects it via `{'DOWN', MRef, ...}` and
  automatically checks the worker back in (`handle_checkin/2`) rather than leaking it as
  permanently "checked out to a dead process."

Both halves matter for `Workers`: a `Task.Queue` must replace a crashed worker to keep
`maxWorkers` truthful (poolboy's `'EXIT'`-triggered refill), and it must also reclaim a
worker whose *caller* (the forking fiber) died or was cancelled mid-task, rather than
stranding that worker as permanently busy (poolboy's client-monitor checkin).

### 7.9 Supervision trees prevent orphans

Shutdown is strictly top-down and ordered: "a supervisor terminates all child processes in
reverse start order according to the respective shutdown specifications before terminating
itself," and this cascades recursively through nested supervisors, each applying its own
children's `shutdown` spec before it, in turn, reports itself terminated to its parent.
Combined with the rule that restart intensity being exceeded makes a supervisor terminate
all of *its* children before terminating itself, the invariant holds all the way up: a
process with no supervisor above it simply cannot exist in a well-formed OTP application,
and a supervisor cannot disappear while leaving live children behind — every leaf is
provably reachable from the root at all times, or it is being torn down in an orderly,
bounded-time sequence. There is no code path in normal operation that "forgets" to signal
a child; the tree structure makes it structurally impossible, not merely a matter of
diligent bookkeeping.

For `Workers`, this is the strongest argument for organizing worker processes as an actual
supervision tree (a `Task.Queue` supervising its own workers, itself under `Workers.run`'s
top-level supervisor) rather than a flat registry of independently-tracked OS processes:
the property we want — "no worker process ever outlives the `Workers.run` scope, and no
crash anywhere leaves an OS process orphaned" — is exactly the property OTP's supervision
tree gives you for free by construction, and is easy to get wrong with ad hoc bookkeeping
(a crash during cleanup, a missed unregister) that a tree shape doesn't have to worry about.

### 7.10 Mapping to `Workers`' reliability model

| Reliability concern | OTP mechanism | `Workers` target behavior |
|---|---|---|
| Crash → retry with a bound | restart `intensity`/`period`, then escalate | `Task.retry` count/window per task, plus a queue-wide circuit breaker so a crash-looping task/worker can't burn unbounded resources |
| Orphan-free kill | supervision tree, top-down reverse-start-order shutdown | every worker OS process provably owned by exactly one `Task.Queue`/`Workers.run` scope; no code path exits that scope without accounting for every live worker |
| Hung-task timeout | `gen_server:call` `Timeout` only (no server-side self-policing, no scheduler-level hang detection) | `Task.timeout` enforced by the supervising queue, culminating in an actual forced kill of the worker process, not just a failed `Fiber` with the worker left running |
| Worker recycle on crash | poolboy `'EXIT'` handling + immediate `new_worker/1` refill | `Task.Queue` replaces a crashed worker to keep `maxWorkers` truthful, without the caller observing reduced capacity |
| Caller-death leak | poolboy client monitor + auto-checkin on `'DOWN'` | if a `Fiber` awaiting a task is itself cancelled/killed, the worker it was using must be reclaimed, not stranded as busy forever |
| At-most-once vs at-least-once | `gen_server:call` fails distinctly on server death vs timeout; no built-in redelivery | `Workers.fork` is at-most-once by default (matches `gen_server:call`); `Task.retry`-driven at-least-once is an explicit, documented opt-in, with idempotency the caller's responsibility |
| Graceful vs forced shutdown | child spec `shutdown`: `terminate/2` window, then `brutal_kill` fallback | warm `Task.Queue` workers get a bounded grace period to release `warmState` cleanly, then an unconditional kill if they don't exit in time |

## 8. Results — `call` reply == our `Fiber` result

`gen_server:call` is synchronous from the caller's point of view but the server processes
one message at a time from its mailbox, so many concurrent callers each get an
independent, correctly-correlated reply (`{pid(), Tag}` in `From` disambiguates which
caller a given `handle_call` invocation is replying to). This is the same shape as
`Workers.fork(thunk, task): Fiber[A,E]` — a call returns a future-like result the issuer
awaits, decoupled from how many other calls are in flight against the same stateful
worker. `send_request`/`wait_response` is OTP's explicit non-blocking variant for a caller
that wants to fire several calls and collect results as they land, i.e. closer to holding
multiple `Fiber`s and joining them.

## 9. Transport/protocol

Everything is Erlang's built-in message passing: async, ordered per sender/receiver pair,
delivered to the receiving process's mailbox, copy semantics (no shared memory even
locally) so a message crossing a `gen_server` boundary is already "IPC-shaped" whether the
two processes are on the same node or a different one via distributed Erlang — the
language does not distinguish local and remote message passing at the API level. Brief
because it is out of scope for what `Workers` needs to design (we have explicit IPC over
forked worker processes, not a language-level uniform mailbox), but it is worth noting OTP
gets its supervision/pooling story to be network-transparent for free because the
messaging substrate already is.

## 10. Lessons for `Workers`

| OTP concept | `Workers` mapping |
|---|---|
| `gen_server` stateful process, `init/1` once + serialized `handle_call` | `Task.Queue` warm worker: acquire `warmState` once, serialize task execution per worker |
| `simple_one_for_one` supervisor (homogeneous dynamic children) | `Task.Queue`'s dynamically sized worker set |
| supervisor `restart` (permanent/transient/temporary) | `Task.retry` conditioned on failure kind, not a blanket count |
| restart `intensity`/`period` (crash-loop circuit breaker, then escalate) | cap on `Task.retry` attempts/window; when exceeded, fail the queue/task up rather than spin forever |
| `one_for_one` / `one_for_all` / `rest_for_one` | `Task.isolation`: does one task's crash/restart affect only itself, its whole queue, or a dependency chain |
| poolboy `size` + `max_overflow`, `checkout(Block, Timeout)` | `Task.Queue.maxWorkers` as a floor/ceiling pair; explicit reject-now vs block-with-deadline choice on saturation, not a hidden unbounded queue |
| `gen_server:call` timeout (default 5000ms, caller exits, server survives) | `Task.timeout`: the *caller* (Fiber awaiter) fails on timeout; the worker process's fate is a separate decision (kill it? let it finish and discard the late result, mirroring OTP's post-OTP-24 discarded-late-reply behavior?) |
| links + `trap_exit` vs monitors | internal supervising-queue-to-worker relationship (link-like, must react) vs external caller-watching-fiber relationship (monitor-like, passive) |
| "let it crash" | keep retry/crash policy entirely in the `Workers` runtime layer, never inside the user's thunk |

**Steal:**
- The **two-number pool shape** (`size` steady-state + `max_overflow` elastic ceiling)
  instead of one `maxWorkers` — cheap to add, directly answers "warm floor vs burst
  ceiling" for `Task.Queue`.
- **Explicit backpressure choice at the call site** (blocking-with-timeout vs
  non-blocking-immediate-reject on saturation) rather than an implicit unbounded queue.
- **Restart intensity as a sliding-window circuit breaker that escalates**, not a per-task
  retry count with no global ceiling — prevents one crash-looping task/worker from burning
  unbounded resources.
- **Keep crash/retry policy structurally separate from task code** ("let it crash"): the
  thunk should not need its own try/catch-and-retry; `Workers` owns that.
- **`simple_one_for_one` as the model for a homogeneous dynamic worker pool**, distinct
  from a fixed-topology supervision tree — validates treating `Task.Queue` as its own
  supervisor subtree rather than folding it into a generic one.
- **Supervision-tree shutdown as a structural, not bookkeeping, guarantee against
  orphans**: reverse-start-order top-down teardown means "no live child survives its
  parent" is true by construction, not by remembering to unregister things — worth
  building `Workers.run`/`Task.Queue` as an actual owning tree rather than a flat process
  registry with manual cleanup.
- **Distinguish "caller timed out" from "worker died" as different, explicitly detectable
  outcomes** (as `gen_server:call` does), and treat a hung/unresponsive worker as something
  the *supervising* layer must forcibly kill, not something the worker is trusted to
  self-police — there is no BEAM-level hang detector to lean on, and there won't be a JVM
  one either.

**Avoid:**
- OTP's restart policy is **global per supervisor**, not really parametrized per call the
  way our `Task.retry`/`timeout` are meant to be per-task-definition; a straight port would
  under-serve the "different tasks in the same queue want different retry policies"
  requirement — worth keeping restart/retry policy at the `Task` level as designed, using
  OTP's *intensity* idea only as a secondary, queue-wide safety cap.
- Erlang's crash isolation is free because processes share nothing; our worker isolation
  is a *process* (OS-level) boundary already, which is stronger but far more expensive to
  spin up — do not assume OTP's cheap "just restart the process" default translates
  directly to cheap `Workers.fork` restarts across a real IPC/process boundary.
- OTP itself has **no built-in at-least-once/durable-redelivery story** — that is a pattern
  bolted on top with `mnesia`/`disk_log`/an external queue, not something `gen_server`
  or `supervisor` provide. Don't treat "OTP-inspired" as license to skip designing
  `Workers`' own at-most-once vs at-least-once contract explicitly; OTP's own answer here
  is "bring your own persistence," not a mechanism to copy.

---

Sources:
- [gen_server behaviour — stdlib](https://www.erlang.org/doc/apps/stdlib/gen_server.html)
- [supervisor behaviour — stdlib](https://www.erlang.org/doc/apps/stdlib/supervisor.html)
- [Supervisor Behaviour — design principles (sup_princ)](https://www.erlang.org/doc/system/sup_princ.html)
- [poolboy — GitHub (devinus/poolboy)](https://github.com/devinus/poolboy)
- [poolboy source — src/poolboy.erl](https://raw.githubusercontent.com/devinus/poolboy/master/src/poolboy.erl)
- [Erlang processes reference manual — links, monitors, trap_exit](https://www.erlang.org/doc/system/ref_man_processes.html)
- [The Zen of Erlang — Fred Hébert](https://ferd.ca/the-zen-of-erlang.html)
- [theBeamBook — scheduling and reduction counting](https://github.com/happi/theBeamBook/blob/master/chapters/scheduling.asciidoc)
