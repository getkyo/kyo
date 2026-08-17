# Prior Art: Worker-Pool Primitives (jest-worker, Python `concurrent.futures` / `multiprocessing`)

Both libraries expose the exact `submit → future` shape our `Workers.fork(thunk, task): Fiber[A,E]` primitive needs: a caller hands over a unit of work, gets back a handle it can await, and a fixed-size pool of out-of-process workers does the executing. Neither is a distributed scheduler; both are single-host, single-process-tree pool managers, which matches our "single global concurrency cap" framing more closely than a cluster scheduler would.

## 1. What it is

**jest-worker** (npm, used by Jest and Metro) loads a specified Node module into N forked child processes and re-exposes that module's exported functions in the parent as async methods with an identical signature. Calling `worker.someExportedFn(args)` in the parent transparently dispatches to a child, runs the real function there, and resolves a Promise with the result.

**`concurrent.futures.ProcessPoolExecutor`** (Python stdlib) and its lower-level cousin **`multiprocessing.Pool`** manage a fixed pool of worker processes and let the caller hand arbitrary picklable callables to them, either one at a time (`submit`/`apply_async`) or over an iterable (`map`/`imap`/`imap_unordered`).

## 2. Unit of work

jest-worker's unit is a **method call**: `method name + argument list`, resolved against the pre-loaded worker module (no ad-hoc closures cross the wire, only args). `concurrent.futures` and `multiprocessing`'s unit is a **callable + positional/keyword args**: `submit(fn, *args, **kwargs)`, `apply_async(func, args, kwds)`. Both designs avoid shipping arbitrary closures; they ship a *reference* to code that must already exist in the worker (module export, or importable top-level function) plus data. This maps directly onto our "thunk" concern below (§10).

## 3. Grouping / routing (sticky routing = our queue affinity)

This is the standout feature for `Task.Queue` affinity. jest-worker's `computeWorkerKey(method, ...args)` callback runs on *every* call; it returns a string or `null`. Per the README: *"Every time a method exposed via the API is called, `computeWorkerKey` is also called in order to bound the call to a worker."* Mechanically: the first time a given key is seen, the call goes to any idle worker, and that worker becomes "bound" to the key; every subsequent call returning the same key is routed (and queued if busy) to that same bound worker. Returning `null` opts a given call out of stickiness entirely, falling back to plain load-balancing. This is a pure caller-side routing function, no separate "queue" object; the pool itself has no notion of named queues.

Python's pool primitives have **no equivalent**. `submit`/`apply_async` always go to "whichever process is free"; there is no per-call routing key, no affinity, no notion that call N should land on the same process as call N-1. Anything resembling affinity must be built by the caller (e.g., maintaining N separate single-process pools). This is a real gap relative to jest-worker and relative to our `Task.Queue` design.

## 4. Declarative config / requirements model

| | jest-worker | ProcessPoolExecutor | multiprocessing.Pool |
|---|---|---|---|
| pool size | `numWorkers` (default: CPUs − 1) | `max_workers` (default: `os.process_cpu_count()`) | `processes` (default: `os.cpu_count()`) |
| warm init | `setup()` method + `setupArgs` | `initializer` + `initargs` | `initializer` + `initargs` |
| per-call retry | `maxRetries` (default 3, per call, on child death) | none (pool-level `BrokenProcessPool` instead) | none |
| recycle | none (child reused until pool `end()`s) | `max_tasks_per_child` | `maxtasksperchild` |
| process launch | `forkOptions` → `child_process.fork` | `mp_context` (fork/spawn/forkserver) | `context` |

All three are flat, imperative constructor options, not a declarative "requirements model" like our per-`Task` `locks`/`weight`/`isolation`/`timeout`. None of them has per-task-type declarative config; `initializer`/`setup()` is pool-wide, not per queue/task-type.

## 5. Worker/process model

All three use **real OS child processes** by default (jest-worker: `child_process.fork`; Python: `multiprocessing.Process` under fork/spawn/forkserver), not threads, so a crashed unit of work cannot corrupt the caller's process. jest-worker additionally supports `enableWorkerThreads: true` to use `worker_threads` (shared-memory, lower IPC overhead, but loses process isolation).

**Warm state** is the `initializer`/`setup()` pattern in all three: a function that runs once per worker process at startup (or `setupArgs`-configured) and whose closures/globals persist for every subsequent task routed to that process: this is precisely our `warmState`. Python's `initargs` gives per-pool (not per-task) warm parameters; jest-worker's `setupArgs` is likewise pool-wide, passed identically to every worker.

**Recycling**: Python's `max_tasks_per_child` / `maxtasksperchild` retires and respawns a worker after N tasks, bounding the blast radius of memory leaks or corrupted native state, directly the shape of our `isolation`/recycle knob. Note the interaction the docs call out explicitly: `max_tasks_per_child` forces the `spawn` start method and is **incompatible with `fork`**: recycling and copy-on-write warm-start are in tension in Python's model, worth avoiding as a footgun in ours. jest-worker has no recycle-after-N-tasks option; a worker lives until the whole pool `end()`s or it crashes.

## 6. Scheduling & concurrency control

All three enforce a hard, single, pool-wide concurrency cap: N workers, N concurrent units of work, full stop, exactly our "single global concurrency cap." Dispatch is "any idle worker" (Python) or "any idle worker, unless bound by `computeWorkerKey`" (jest-worker). None expose priority, weight, or lock-based scheduling; a submitted task simply queues in FIFO order behind whatever's already assigned to its target worker(s). This is the ceiling our per-task `locks`/`weight` will need to go past: none of these libraries model contention over resources *inside* the pool, only over worker slots.

## 7. Worker reliability (deep dive)

This is the load-bearing section for our reliability model, so it goes concrete: exact APIs, exact code paths, and where each library's story runs out.

### 7a. Crash detection and respawn

**jest-worker**: `ChildProcessWorker._onExit()` inspects the exit code/signal of the dead child. The behavior forks on *how* it died:
- On a genuine crash (non-zero exit that isn't a `SIGTERM`/`SIGKILL` the pool itself issued, or a worker already in `RESTARTING` state), the worker reinitializes a fresh child process and **resends the pending request it was holding**: `if (this._request) { this._child.send(this._request); }`. This is **at-least-once** execution: if the dead worker had already produced a side effect before dying (e.g. wrote a file, made a network call) but died before the parent received the response, the resend silently re-executes that side effect on the new worker. The retry budget is `maxRetries` (README: *"Maximum amount of times that a dead child can be re-spawned, per call. Defaults to `3`, pass `Infinity` to allow endless retries."*), tracked per call via a `_retries` counter; exceeding it surfaces as an explicit "exceeding retry limit" error to the caller instead of another silent retry.
- On a graceful exit or an externally-killed process with no retry path available, the in-flight request instead **fails immediately** with an error resembling `"A jest worker process (pid=...) crashed for an unknown reason"`, with no resend: at-most-once for this path.
- A respawned worker is a brand-new process: `setup()`/warm state reruns from scratch, so recovering from a crash costs the full warm-init cost again.

**Python `ProcessPoolExecutor`**: no per-call retry exists. A worker terminating abnormally raises `BrokenProcessPool` (subclass of `BrokenExecutor`) and **poisons the entire pool**: every pending future raises immediately, and any further `submit()` call raises too. If the `initializer` itself raises during startup, the same blanket poisoning occurs. Recovery is caller-driven: catch `BrokenProcessPool`, discard the executor, construct a new one. `multiprocessing.Pool`'s `AsyncResult.get()` re-raises whatever exception the worker's *function* raised (an application-level failure) but its handling of the *process* dying underneath a call is comparatively under-specified in the stdlib docs relative to `ProcessPoolExecutor`'s explicit `BrokenProcessPool` contract.

### 7b. Hung / stuck-worker detection and timeouts

Neither library has this. jest-worker has no execution-timeout mechanism for a call that is merely slow rather than dead: its detection is purely exit-based (`_onExit`), so a worker spinning forever without exiting is invisible to it; a real, filed bug (jest/jest#13183, fixed by PR jestjs/jest#13566) was specifically that if a worker is `SIGKILL`'d *externally* the harness could hang indefinitely waiting for a response that will never arrive, because nothing time-bounds the wait. Community guidance for "hung Jest worker" is entirely OS-level triage (check `dmesg`/`journalctl` for OOM kills), not a jest-worker feature. Python is the same shape: `Future.result(timeout=…)` and `AsyncResult.get(timeout=…)` are **retrieval timeouts on the caller's wait**, not execution timeouts that kill the running task; a hung worker just never completes and the pool never notices on its own.

This is a genuine gap in both libraries, and it is exactly where our per-`Task` `timeout` needs to be first-class rather than borrowed: the only correct implementation is the parent actively killing the specific worker process once a caller-visible deadline passes, and, critically, treating that self-inflicted kill as a **timeout outcome**, distinct from an unprovoked crash, so it does not fall into either library's crash-retry or pool-poisoning path by accident.

### 7c. Orphan prevention (leaked children when the parent dies)

**`multiprocessing.Pool`**: worker processes are constructed with `daemon = True` (`Lib/multiprocessing/pool.py`). Per the stdlib docs: *"When a process exits, it attempts to terminate all of its daemonic child processes."* The docs also explain the safety reasoning: *"a daemonic process is not allowed to create child processes[, o]therwise a daemonic process would leave its children orphaned if it gets terminated when its parent process exits."* This is the most concrete orphan-prevention mechanism found in either library, but it is opportunistic, not a hard guarantee: it fires on an *orderly* interpreter exit (the module additionally registers an atexit-style hook that `.join()`s running children), and does nothing if the parent itself is `SIGKILL`'d, since no OS cascades a `SIGKILL` to children automatically. That residual gap is exactly why standalone packages like `pyreap`/`processfamily`/`multiexit` exist in the wild: they implement the "child polls its own parent PID and self-terminates if it changed" pattern precisely because the stdlib's daemon flag doesn't cover a killed parent.

**`ProcessPoolExecutor`**: registers its shutdown path via `threading._register_atexit()` (a lower-level hook than plain `atexit`, chosen for subinterpreter compatibility) so idle worker processes are cleaned up on normal interpreter exit, plus a `weakref.finalize` callback on the executor object that wakes the internal queue-management thread once the executor itself is garbage-collected, letting it shut down without an explicit `shutdown()` call. Same caveat as `Pool`: this is an orderly-exit mechanism, not orphan-proof against a killed parent.

**jest-worker**: no orphan-specific mechanism at all beyond what `child_process.fork` gives Node by default. Its `killChild()`/`forceExit()` machinery (next subsection) only fires when the *pool itself* decides to shut down (`end()`), not when the parent process is killed out from under it.

Bottom line: both libraries' orphan protection is **best-effort, tied to normal process exit** (daemon flag or atexit/finalizer hooks); neither uses process groups, session leadership, or a supervising reaper process, and neither survives the parent being `SIGKILL`'d. A `Workers` implementation that wants a hard guarantee needs a process-group kill (spawn workers into their own group, kill the group) or an external supervisor, since this is a place where the prior art simply stops.

### 7d. Graceful vs forced shutdown

**jest-worker**: `end()` kills every worker and returns `Promise<{forceExited: boolean}>`. The kill sequence (`killChild()`) sends `SIGTERM` first, then schedules `SIGKILL` after a fixed grace window (`SIGKILL_DELAY = 500` ms) if the process hasn't exited by then; `forceExit()` can trigger this immediately. `forceExited: true` in the result means at least one worker needed the `SIGKILL` escalation, which the README attributes to *"a leaky task that left handles open."* Notably jest-worker has **no "let all in-flight calls finish, then stop" mode** analogous to Python's `close()`: its only graceful lever is the fixed 500ms SIGTERM window, not a wait-for-completion drain.

**Python**: `Pool.close()` (stop accepting new tasks, let running ones finish, exit workers afterward) vs `Pool.terminate()` (immediate `SIGTERM`, unfinished tasks discarded) are two clearly distinct graceful/forced modes, composed with `.join()` to block until workers actually exit. `ProcessPoolExecutor.shutdown(wait=True, cancel_futures=False)` layers a third axis: `cancel_futures=True` cancels only futures that haven't *started* running; futures already executing always complete, so shutdown is graceful-by-default and forced-cancellation only touches the still-queued backlog, never in-flight work. Python 3.14 adds explicit escape hatches for real emergencies: `terminate_workers()` (SIGTERM-class) and `kill_workers()` (SIGKILL), separate from `shutdown()`.

### 7e. Resource-leak avoidance and recycling

**jest-worker**'s `idleMemoryLimit` is **measured-resource-based**: specified as a fraction (≤1, percentage of system memory) or an absolute byte count (>1); after every task, `checkMemoryUsage()` / `_performRestartIfRequired()` checks the child's memory footprint and calls `_restart()` (transition to `RESTARTING`, kill the child) if it's over budget: a fresh process replaces it before the next call. `idleMemoryLimit: 0` means "always restart," i.e. one task per process lifetime, the maximal-isolation setting.

**Python**'s `max_tasks_per_child`/`maxtasksperchild` is **fixed-count-based**: a worker is unconditionally retired and replaced after executing N tasks, regardless of actual memory or resource usage. As already noted in §5, this forces the `spawn` start method and is incompatible with `fork`: recycling and fork-based warm-start reuse are mutually exclusive in Python's model.

The two libraries recycle on genuinely different signals, measured memory footprint (jest-worker) versus raw task count (Python), and neither recycles on other plausible signals (wall-clock worker age, open-handle count, CPU time). A combined "whichever threshold trips first" policy is strictly stronger than either library alone and worth adopting for `isolation`/recycle rather than picking just one axis.

### 7f. At-least-once vs at-most-once, made explicit

Pulling 7a together: jest-worker's crash-with-resend path is **at-least-once** (possible duplicate side effects on the exact call that was executing when the worker died); its graceful/externally-killed path and both of Python's failure paths (`BrokenProcessPool`, application exception via `.get()`) are **at-most-once / fail-fast** (the call simply fails, never silently reruns). Crucially, in neither library does the *caller* choose this: it falls out of which code path happened to detect the death. For `Task.retry`, this needs to be a policy the caller states explicitly (idempotent tasks may opt into at-least-once retry-on-crash; non-idempotent tasks must default to fail-fast), not an accident of which internal detection path fired.

### 7g. Backpressure when all workers are busy

Neither library provides real backpressure. jest-worker's own source docstring says workers "queue calls while busy," but each `ChildProcessWorker` instance tracks only a single in-flight `_request`; the actual multi-call queueing lives in the parent pool/farm, which accepts calls **unboundedly**: the Promise is created eagerly at call time and just waits its turn, with no way to signal "the pool is saturated, stop submitting" back to the caller. Python is the same shape: `apply_async`/`submit`/`map` enqueue onto the pool's internal task queue with no documented bound. The one exception is `Executor.map()`'s new-in-3.14 `buffersize` parameter, which pauses pulling from the input iterable once that many results are buffered awaiting consumption: a real backpressure knob, but scoped to `map()`'s iterator-pull loop, not to `submit()`/`apply_async()`/method calls in general.

Conclusion: **both libraries default to unbounded submission-side queueing**. A `Workers.fork` that wants real backpressure (reject or suspend the caller when the queue backlog crosses a threshold, rather than accepting arbitrarily many pending `Fiber`s) has to add that itself; it is not something either prior-art library gives for free.

### Lessons for the `Workers` reliability model

- **Crash → retry with a bound**: adopt jest-worker's per-call `maxRetries` shape (a finite or `Infinity` retry budget tracked per call, not per pool), but make the at-least-once-vs-fail-fast choice an explicit, caller-visible `Task` policy (§7f) rather than an artifact of which death-detection path fired, and never let one task's crash poison sibling in-flight `Fiber`s the way `BrokenProcessPool` does.
- **Hung-task timeout is ours to build**: neither library detects a merely-slow (not dead) worker; per-`Task` `timeout` must be implemented as the parent actively killing the specific worker after a deadline, with that self-inflicted kill tagged as a timeout outcome so it doesn't fall into the crash-retry path by accident (§7b).
- **Orphan-free kill needs more than daemon flags**: both libraries' orphan protection is opportunistic and tied to orderly exit (`daemon=True` cascading termination, atexit/weakref finalizers); neither survives a `SIGKILL`'d parent. `Workers` should spawn into a dedicated process group (or use an external reaper) if it wants a real guarantee, rather than relying on the daemon-flag pattern alone (§7c).
- **Worker recycle on a combined signal**: adopt both axes, jest-worker's measured-memory `idleMemoryLimit` and Python's fixed-count `max_tasks_per_child`, as a single "whichever trips first" policy for `isolation`/recycle, and decide explicitly (rather than discover in production) whether recycling is compatible with warm-start reuse the way Python's fork/spawn incompatibility was not (§7e).
- **Shutdown needs a real drain mode**: prefer Python's `close()`-then-`join()` (let in-flight work finish) as the default graceful path, with jest-worker's fixed SIGTERM→SIGKILL(500ms) escalation and Python 3.14's `terminate_workers()`/`kill_workers()` reserved for explicit force-stop, not the default (§7d).
- **Backpressure must be designed in, not inherited**: both libraries queue submissions unboundedly; `Workers.fork` needs an explicit bounded-queue/backpressure story if unbounded pending `Fiber`s are not acceptable (§7g).

## 9. Results (submit → Future ≡ our fork → Fiber)

jest-worker: every exposed method call returns a **Promise**, resolved with the child's return value or rejected with the thrown/serialized error, with no separate "Future" type: the call site itself is the future. `ProcessPoolExecutor.submit`/`Pool.apply_async` return an explicit handle (`Future` / `AsyncResult`) decoupled from the call: `.result(timeout)` / `.get(timeout)` blocks and re-raises the worker's exception verbatim (module the worker raised, or `CancelledError`/`TimeoutError` for control-flow outcomes), `future.cancel()` is available before execution starts, `.done()`/`.ready()` poll without blocking. This split-handle shape (submit returns immediately, a separate call blocks/polls/cancels) is structurally identical to `Fiber[A,E]`: `fork` returns the `Fiber` immediately, `.get`/`.await` blocks, and the error channel `E` is exactly "the exception the worker raised, faithfully propagated."

## 10. Transport / protocol (serialization constraints: our thunk-shipping footgun)

jest-worker ships **no code at all** across the wire per call, only the method name (a string) and arguments, serialized via the standard `child_process` IPC channel (JSON-like structured clone, so no functions, no cyclic structures beyond what the channel supports). The actual code lives in the pre-loaded worker module, required once at child startup. Python is the opposite and more dangerous case: `submit`/`apply_async`/`map` **pickle the function object itself** plus its arguments and ship both. The docs are explicit and blunt: *"functions and arguments needing to be picklable... A function defined in a REPL or a lambda should not be expected to work."* Concretely un-picklable: lambdas, closures over local variables, nested/local functions, methods of locally-defined classes. Under `spawn`/`forkserver` (the modern default), the callable must be **importable by name** from a module the child process can also import: pickling a function actually pickles a reference (`module.qualname`), not the bytecode.

This is precisely our thunk-shipping footgun: `Workers.fork(thunk, task)` implies shipping a serialized closure to a forked process, and Python's twenty-plus years of "works until someone passes a lambda or closes over a socket/lock/file handle" is the direct cautionary tale. jest-worker sidesteps the whole problem by **never serializing code**, only data plus a name resolved against code already loaded in the worker. That is the safer design point if our thunk can be restructured as "named task type in a pre-loaded module + serializable args" rather than "arbitrary closure."

## 11. Lessons for `Workers`

- `Workers.fork(thunk, task)` ↔ `submit`/`computeWorkerKey`-routed call: the mapping is exact for the happy path (submit → handle → await/get). Model `Fiber[A,E]`'s error channel on Python's "re-raise the worker's real exception," not on a generic wrapped failure.
- `Task.Queue({name, classpath, maxWorkers, warmState})` ↔ jest-worker's `numWorkers` + `setup()`/`setupArgs`, but **named/multiple** instead of one global pool; jest-worker's `computeWorkerKey` is the strongest precedent for our queue-affinity/sticky-routing requirement and should be studied closely (bind-on-first-use, `null` opts out).
- `warmState` ↔ `initializer`/`initargs` (Python) or `setup()`/`setupArgs` (jest-worker): both are pool-wide only, never per-task-type; if `Task.Queue` warm state needs to differ by task type sharing a pool, that's new ground neither library covers.
- `isolation`/recycle ↔ `max_tasks_per_child`/`maxtasksperchild` and jest-worker's `idleMemoryLimit`: adopt a combined measured-resource + fixed-count policy (§7e), and design around Python's own documented footgun where recycling forces `spawn` and forbids `fork`: decide up front whether our recycle knob is compatible with warm-start reuse or trades one off against the other, and say so explicitly rather than discovering it in production.
- Reliability, concretely (§7): per-call bounded retry on crash with an explicit at-least-once/fail-fast policy choice, not an accident of detection path; a parent-driven kill-on-deadline for hung tasks tagged distinctly from crashes; a process-group (not just daemon-flag) kill for orphan-free teardown; `close()`-then-drain as the default graceful shutdown with SIGTERM→SIGKILL escalation reserved for force-stop; and an explicit bounded-queue backpressure story, since both libraries queue submissions unboundedly by default.
- Avoid Python's pickle-the-closure transport wholesale. Prefer jest-worker's "no code over the wire" shape: resolve `thunk` to a name registered in the worker's pre-loaded classpath plus serializable args, not an ad-hoc closure capture, or the exact "worked in my REPL, breaks in production" failure mode documented for `multiprocessing` becomes ours.
