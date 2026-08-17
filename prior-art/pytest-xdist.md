# Prior art: pytest-xdist

## 1. What it is

`pytest-xdist` is a pytest plugin that distributes a single test session's items across multiple worker processes (and optionally remote hosts) using `execnet`. It is the de facto standard for parallel test execution in the Python ecosystem: mature (in production since ~2010), simple invocation (`pytest -n auto`), and its vocabulary for grouping tests to workers is exactly the affinity problem `Workers` needs to solve for `Task.Queue` + `locks`.

## 2. Unit of work (a test item)

The unit distributed to a worker is a single collected pytest item (a test function/method/parametrized case), identified by its node id (e.g. `test_mod.py::TestFoo::test_bar[param]`). Collection happens independently on every worker at session start; the controller then verifies all workers collected **the same tests in the same order** and converts node ids to integer indices into that shared list, so a scheduling instruction to a worker is just "run test #42" rather than a full node id round-trip. This is a hard requirement, not an optimization detail: "It is not possible to have tests that differ in order or their amount across workers" (Known Limitations). Non-deterministic collection (e.g. parametrizing from a `set`) breaks the whole session.

## 3. Grouping / routing (priority: loadscope / loadgroup / xdist_group)

This is the section most directly reusable for `Workers`.

- **`--dist=loadscope`**: tests are grouped by *module* (plain functions) or *class* (methods), and "Groups are distributed to available workers as whole units. This guarantees that all tests in a group run in the same process." Class grouping takes precedence over module grouping. This is the answer to "expensive class/module-scoped fixture must not be torn down and rebuilt mid-run": affinity is inferred structurally, no annotation needed.
- **`--dist=loadfile`**: same idea, grouped by containing file instead of module/class.
- **`--dist=loadgroup`**: affinity becomes *explicit* via a marker:
  ```python
  @pytest.mark.xdist_group(name="group1")
  def test_example():
      ...
  ```
  "Groups are distributed to available workers as whole units. This guarantees that all tests with same `xdist_group` name run in the same worker." Multiple `xdist_group` marks on one test are merged lexically (union of group membership); tests with no marker fall back to ordinary `load` scheduling, i.e. grouping is opt-in per test, not all-or-nothing for the run.
- **`--dist=worksteal`**: tests are split evenly across workers up front, but an idle worker can steal remaining work from a busier worker's queue, i.e. static partition plus dynamic rebalancing, rather than a single shared pull queue.
- **`--dist=each`**: not a grouping mode at all: every test runs on *every* configured remote environment (e.g. one worker per Python version/host via `--tx`), for cross-environment matrix runs, not load balancing.
- **`--dist=no`**: xdist disabled, sequential in the controller process.

`xdist_group` is the closest existing analogue to `Task.Queue` + per-task `locks`: it is a *name* a test opts into that pins it to co-locate with every other test sharing that name, resolved by the scheduler at dispatch time, not by the test author picking a worker index.

## 4. Declarative config / requirements model (markers)

xdist's "declarative requirements" surface is thin and marker-based, not a rich task descriptor:
- `@pytest.mark.xdist_group(name=...)`: co-location affinity (Section 3).
- `worker_id` fixture: lets a test introspect which worker it landed on (returns `"master"` when xdist is disabled), used for building per-worker resources (e.g. per-worker log/db names), not for routing decisions.
- `PYTEST_XDIST_WORKER` env var (e.g. `"gw2"`) and `PYTEST_XDIST_WORKER_COUNT`: same introspection, usable from arbitrary code, not just fixtures.
- `pytest_xdist_auto_num_workers(config)` hook / `PYTEST_XDIST_AUTO_NUM_WORKERS` env var: pluggable policy for what `-n auto` resolves to (hook takes priority over the env var).

There is no per-test weight, timeout, retry, or isolation-level concept in core xdist; those are left to other plugins (`pytest-timeout`, `pytest-rerunfailures`, etc.) layered on top. `Workers`' `Task` (locks, weight, isolation, timeout, retry) is a materially richer declarative model than xdist ships with; xdist's lesson is about the *affinity marker* pattern, not the full task spec.

## 5. Worker/process model

The controller ("master") spawns N workers at session start over `execnet` gateways (local subprocess by default, but the same gateway abstraction supports SSH/remote Python interpreters, e.g. `--tx ssh=host//python=python3.11`, used by `--dist=each`). Each worker is literally "a mini pytest runner": it does its own full test collection independently, then reports the collected id list back to the controller for the consistency check described in Section 2. Workers are named `gw0`, `gw1`, `gw2`, ... in log/report output and via `PYTEST_XDIST_WORKER`. Workers are stateless with respect to prior sessions: they exist only for the duration of one pytest invocation (no persistent pool reused across separate `pytest` runs, unlike `Task.Queue`'s named, presumably longer-lived pools).

## 6. Scheduling & concurrency control (pull / work-steal model, -n cap)

- `-n <N>`: fixed worker count. `-n auto`: physical CPU core count. `-n logical`: logical core count (needs `psutil`). `-n 0`: xdist off.
- `--maxprocesses=<N>`: caps worker count independent of `-n`'s computed value (e.g. `-n auto --maxprocesses=4` on a 32-core box).
- Default (`load`) mode is a dynamic pull queue: "Sends pending tests to any worker that is available, without any guaranteed order". A free worker asks the controller for the next unit and gets it; this is the pattern to mirror for `Workers.run(Config(parallelism=N))`'s global cap feeding fibers as they free up, rather than a static up-front partition.
- `worksteal` adds rebalancing on top of an initial static split, useful when unit cost is uneven and the pool is small enough that a bad initial partition matters.
- `-n` is a pure process-count cap; it has no notion of *weighted* concurrency (a test worth "3 units" of the cap). That is squarely new territory `Workers`' `weight` field would need to define itself; xdist has no analogue.

## 7. Worker reliability (liveness, crash handling, shutdown, leaks, backpressure)

This is the section most relevant to `Workers`' operational model, and where xdist's design is most instructive by its gaps as well as its mechanisms. It comes from reading the actual controller/worker source (`dsession.py`, `workermanage.py`, `scheduler/load.py`), not just the docs.

**Liveness/health detection: none, purely reactive.** xdist has no heartbeat, ping, or periodic health check between controller and worker. `WorkerController` only learns a worker is gone when its `execnet` channel closes:
```python
if eventcall is Marker.END:
    err = self.channel._getremoteerror()
    if not self._down:
        if not err or isinstance(err, EOFError):
            err = "Not properly terminated"
        self.notify_inproc("errordown", node=self, error=err)
```
This detects a **crashed** (process exited / connection dropped) worker; it detects nothing about a worker that is still alive but stuck.

**Hung/stuck-worker detection: a documented, unresolved gap.** A worker that is alive but wedged (infinite loop, deadlock, blocked I/O) sends no channel-close event, so the controller "does not detect remote session crash or disconnect and as such will wait for results forever" (pytest-xdist issue #57/#298/#1094 territory). Compounding this, `pytest-timeout`'s per-test timeout defaults to signal-based interruption, which only works on the main thread, and `execnet`'s worker transport runs a threaded event loop, so the timeout plugin's thread-based fallback frequently leaves the run "stuck forever anyway" with no information about which test hung. xdist ships no server-side (controller-driven) timeout of its own; it is entirely dependent on a cooperating, correctly-configured child-side timeout plugin, and the two are documented to interact badly.

**Crash detection and worker restart.** On `errordown`, the controller asks the scheduler to hand back the crashed worker's in-flight item, reports it as a failure, and immediately clones a fresh replacement worker into the pool so total worker count is restored:
```python
def worker_errordown(self, node, error):
    crashitem = self.sched.remove_node(node)
    if crashitem:
        self.handle_crashitem(crashitem, node)
    ...
    self._clone_node(node)   # spawn a fresh worker with a new id, same spec
```
`--max-worker-restart=<N>` bounds how many such replacements are allowed across the whole session (default, when unset, is `numprocesses * 4`, per `get_default_max_worker_restart`); `--max-worker-restart 0` disables replacement entirely, so the first crash aborts the run via `triggershutdown()`.

**What happens to the crashed worker's work: at-most-once for the running item, safe requeue for the rest.** The scheduler's `remove_node()` does two different things with a crashed worker's local queue:
```python
crashitem = self.collection[pending.pop(0)]   # the ONE item mid-execution: reported failed, not rerun
self.pending.extend(pending)                  # every OTHER item that worker had been handed but not started: back into the global pool
```
Only the single actively-executing item is lost/marked-failed (`handle_crashitem` emits a synthetic `pytest.TestReport(outcome="failed", ...)`; it is never automatically rescheduled: a rerun, if any, is an external CI concern, e.g. `pytest --lf`). Everything else the crashed worker had been handed but not yet started is safely returned to the shared pending pool and picked up by a surviving worker. This is deliberately a small blast radius, not an accident; see backpressure below.

**Backpressure: bounded per-worker local queues, not a full-queue handoff.** The `load` scheduler never gives a worker its whole remaining share up front. It doles out small, watermark-bounded chunks and tops them up as the worker reports completions:
```python
# initial dispatch
node_chunksize = min(items_per_node // 4, self.maxschedchunk)
node_chunksize = max(node_chunksize, 2)          # always at least 2 items
# top-up on every completion report
items_per_node_min = max(2, len(self.pending) // num_nodes // 4)
items_per_node_max = max(2, len(self.pending) // num_nodes // 2)
num_send = items_per_node_max - len(node_pending)
self._send_tests(node, min(num_send, maxschedchunk))
```
`--maxschedchunk` caps the ceiling; the low/high watermark keeps each worker's outstanding queue in a narrow band. The direct payoff is reliability, not just load balance: because a worker never holds more than a handful of unstarted items, a crash only ever loses one item outright (the one running) and only ever requires requeuing a handful of others, never the tail of the whole session.

**Graceful vs. forced shutdown.** Normal end-of-session teardown is two-phase: `triggershutdown()` sends every live worker a cooperative `"shutdown"` command over its channel (`node.shutdown()` -> `self.sendcommand("shutdown")`), then `teardown_nodes()` calls `self.group.terminate(self.EXIT_TIMEOUT)` (10 seconds) as a forceful backstop that kills any gateway process that didn't exit cleanly in time. Notably, a worker-side `Ctrl+C` (`exitstatus == 2`, keyboard-interrupt) is **not** treated as a graceful path distinct from a crash: the controller routes it straight into `worker_errordown(node, "keyboard-interrupt")`, the same code as an unplanned death.

**Orphan prevention: a real, documented, unresolved weakness.** The mechanisms above all assume the *controller* is alive to either send `"shutdown"` or run `group.terminate()`. If the controller itself is killed hard (SIGKILL, OOM, container kill) there is no OS-level tether (no process-group kill-on-parent-death, no `atexit`-registered forced kill independent of the controller's own exit path) linking worker subprocess lifetime to controller lifetime. pytest/pytest-xdist issue #2498 records exactly this: workers "remain running even after all tests are finished, becoming zombies inside containers," with the maintainers' own workaround being to manually call `gw._io.kill(); gw._io.wait()` on every gateway from a `pytest_sessionfinish` hook. This is xdist's clearest reliability gap: cooperative shutdown is well designed, but nothing survives the controller dying uncleanly.

**Resource-leak handling / worker recycling: none, beyond crash-triggered replacement.** There is no proactive recycling policy in core xdist (no "restart a worker after N tests" or "after peak RSS exceeds X" knob). The *only* mechanism that ever replaces a live-but-tainted worker process is the crash path (`_clone_node` after `errordown`); a worker that is merely leaking memory without crashing runs for the whole session.

## 8. Results

Because `execnet` cannot forward stdout/stderr from workers (`-s`/`--capture=no` is explicitly broken under xdist for this reason, and there is no plan to fix it), results are relayed as structured data, not stream output: each worker sends its per-test outcome back to the controller, which replays it through the normal `pytest_runtest_logstart` / `pytest_runtest_logreport` hooks so downstream reporting plugins (coverage, junit-xml, custom reporters) see one coherent stream indistinguishable from a sequential run. This "structured event replay through the real hook path" pattern (not raw log/text shipping) is the right target for `Workers.fork`'s result path if fibers need to feed a central reporter.

## 9. Transport/protocol (execnet)

`execnet` is the generic RPC/channel layer underneath: it opens a *gateway* to a Python interpreter (local subprocess, SSH remote, or other), and exposes bidirectional typed *channels* over it for sending Python objects (test ids, results, protocol messages) between controller and worker. xdist is essentially a scheduling and result-aggregation policy layered on top of a fairly generic "run Python somewhere and talk to it" primitive; the interesting design is almost entirely in xdist's scheduler classes (`LoadScheduling`, `LoadScopeScheduling`, `WorkStealingScheduling`, etc.), not in the transport.

## 10. Lessons for `Workers`

Direct mappings:
- `xdist_group` marker -> `Task.queue(q)` / a task-level affinity tag: both are opt-in, per-task labels resolved by the scheduler into "these must land in the same execution context," not something the task author manually wires to a worker index.
- `loadscope`/`loadfile` (structural, implicit grouping) suggest `Workers` could offer an *inferred* affinity tier (e.g. co-locate tasks sharing a `Task.Queue` name or a declared lock) in addition to the explicit one, the same two-tier pattern xdist uses (automatic scope grouping vs. explicit `xdist_group`).
- `load`'s dynamic pull-queue is the right model for `Workers.run(Config(parallelism=N))`'s global cap: workers ask for work as they free up rather than a static partition, matching kyo's fiber-scheduling idiom already.
- `-n auto`/`--maxprocesses` cap-plus-override pattern maps directly to `Config(parallelism=N)`: a computed default with an explicit ceiling override.
- `--max-worker-restart` is a pool-level circuit breaker distinct from `retry`: `Workers` needs both a per-task `retry` (xdist has no analogue: it never re-runs a test) and a pool-level "how many worker-process deaths before we give up on the whole run" ceiling; conflating the two loses information.
- The collection-consistency check (Section 2) is a good defensive pattern if `Workers` ever needs multiple processes to agree on task manifests, but only if `Workers` shares xdist's constraint of homogeneous, statically-known task sets. That is likely not true for a general task-thunk model, so treat it as inspiration for validation, not a requirement to copy.

Reliability mapping (crash -> retry with a bound / orphan-free kill / hung-task timeout / worker recycle), each keyed to a concrete gap or mechanism found in Section 7:
- **Crash -> retry with a bound**: xdist's `handle_crashitem` marks the interrupted item failed and never reschedules it; the pool-level `--max-worker-restart` counter governs worker replacement, not task retry, and the two are conflated nowhere in xdist because xdist has no task-retry concept at all. `Workers` should keep them as two independent counters that both fire on the same crash event: requeue the interrupted task itself up to its own `retry` bound (something xdist structurally cannot do), while separately incrementing a pool-level replacement counter capped the way `--max-worker-restart` caps it. The bounded local-queue design (Section 7, backpressure) is directly reusable here: keeping a worker's outstanding batch small is what makes "retry just the one lost task" cheap instead of "requeue an unbounded tail."
- **Orphan-free kill**: xdist's own unresolved bug (issue #2498, zombie workers when the controller dies uncleanly) is the concrete cautionary example, not a pattern to imitate. Its cooperative `"shutdown"` message plus a 10s `group.terminate()` backstop only works because the controller is alive to run it. `Workers` needs the tether to be OS-level and survive the controller dying hard: process-group placement plus kill-on-parent-death (`PR_SET_PDEATHSIG` on Linux, a Job Object with `JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE` on Windows) or an external supervising watchdog, so a worker cannot outlive its controller regardless of how the controller exits.
- **Hung-task timeout**: xdist has no server-side timeout and no heartbeat at all (Section 7); it depends entirely on a child-side plugin (`pytest-timeout`) whose signal-based default is documented to be incompatible with `execnet`'s threaded transport, producing the exact "waits forever" failure mode `Workers`' `Task.timeout` must not have. The fix implied by watching xdist fail at this: enforce `timeout` from the controller side (an external watchdog fiber that force-kills the worker process once a task's timeout elapses) rather than trusting the child process to self-interrupt via a signal the runtime may not deliver reliably.
- **Worker recycle**: xdist recycles a worker only reactively, on crash (`_clone_node`); it has no proactive recycling for leak mitigation (no restart-after-N-tasks or restart-above-memory-threshold policy), so a slowly leaking-but-not-crashing worker runs unchecked for the whole session. If `Workers` wants leak mitigation it has to add that policy itself; xdist offers no template beyond "replace on outright death." The one transferable piece is mechanical: the same bounded-batch/backpressure scheduling that limits blast radius on crash also limits it for a *deliberate* recycle, since a worker being retired mid-session only ever has a handful of in-flight/queued tasks to hand back, not the session tail.

Steal:
- The opt-in affinity marker resolved by the scheduler (`xdist_group`), not by the task author picking a target worker.
- The dynamic pull-queue as the default scheduling discipline under a global concurrency cap.
- Watermark-bounded per-worker local queues (Section 7): never hand a worker its full remaining share; this is what keeps a crash's or a deliberate recycle's blast radius to a handful of tasks instead of the whole tail.
- Separating "replace a dead worker" (pool-level breaker, count-capped) from "retry a failed unit" (task-level policy) as two independent knobs.
- Structured result replay through a single reporting path rather than raw stream forwarding, since forked/worker processes generally can't cheaply stream stdout back live.

Avoid (reliability-specific):
- Purely reactive, channel-EOF-only liveness detection with no heartbeat: it is exactly why xdist cannot distinguish "crashed" from "hung" and documents an unresolved "waits forever" failure mode.
- Assuming the controller will always be alive to run cooperative shutdown: xdist's own zombie-worker bug (issue #2498) is the direct evidence this assumption fails in practice; `Workers` needs an OS-level tether, not just a shutdown message.
- Depending on the worker process to self-enforce its own timeout via a signal: xdist's documented `pytest-timeout` incompatibility shows this breaks silently under a threaded transport.

Avoid:
- Requiring a fully deterministic, session-wide-consistent task manifest across all workers before scheduling starts; that is a pytest-specific consequence of shared no-arg test collection, and it is a real constraint (see Known Limitations) that `Workers`, built for a broader "arbitrary serializable thunk" model, should not inherit as a hard requirement.
- `execnet`'s no-stdout-forwarding limitation as a design accident to just live with; a general worker-process model should decide deliberately whether to support live output streaming rather than ruling it out by transport choice.

File written: `prior-art/pytest-xdist.md`.
