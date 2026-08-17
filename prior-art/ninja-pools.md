# Prior Art: Ninja's Pool-Based Concurrency Model

## 1. What it is

[Ninja](https://ninja-build.org/) is a small, fast build system designed to be a compilation target for higher-level build generators (CMake, GN, Meson) rather than something humans write by hand. Its `build.ninja` files are deliberately low-level: explicit dependency edges, no conditionals, no loops. The one concurrency-control feature it exposes beyond the global job count is the **pool**: a named, depth-bounded lane that a subset of build edges can opt into. It is the closest prior art to `Task.Queue`: a declarative, named group with its own concurrency cap, referenced by opt-in from individual units of work.

## 2. Unit of work

The unit of work is the **edge**: one build statement, `build <outputs>: <rule> <inputs>`, which expands a `rule`'s `command` template and runs it as a single subprocess. Edges are pure process invocations, chosen by Ninja's scheduler once their input dependencies are satisfied. There is no notion of a long-lived worker owning state between edges — every edge is a fresh subprocess (see §5).

## 3. Grouping / pools / routing

- **Pools are named and declared globally**, independent of any rule or build statement:
  ```
  pool link_pool
    depth = 4
  ```
- **Opt-in happens via a `pool` variable**, settable either on a `rule` (applies to every edge using that rule) or overridden per `build` statement:
  ```
  rule link
    command = link.py $out $in
    pool = link_pool

  build foo.exe: link foo.o bar.o
  # inherits pool = link_pool from the rule

  build bar.exe: link bar.o
    pool =
  # empty pool = opts this specific edge OUT, back to the unpooled default
  ```
- **Depth is a per-pool concurrency cap**, not a priority or weight: at most `depth` edges assigned to that pool run simultaneously, regardless of how many CPUs/`-j` slots are otherwise free.
- Pools are commonly used to bound resource-heavy edges that the OS-level `-j` count doesn't protect — e.g. link steps that each consume gigabytes of RAM get `depth = 4` even when `-j 32` is otherwise in effect, so 32 compiles can run alongside only 4 links.
- There is **no nesting or hierarchy of pools** and no per-pool sub-pools; the model is a single flat namespace of named lanes, each edge in at most one.

## 4. Declarative config / requirements model

Pools are declared as top-level statements in the `.ninja` file, syntactically identical in weight to `rule` and `build` statements:

```
pool link_pool
  depth = 4

rule link
  command = link.py $out $in
  pool = link_pool

build foo.exe: link foo.o bar.o
```

`depth` is the only variable a pool declaration carries — no per-pool environment, no resource shape, no warm/cold state. All of that (`command`, environment variables, working directory) lives on the `rule`/`build` side, not the pool. In other words, Ninja's pool is a pure concurrency gate with no identity of its own beyond a name and a number; it says nothing about *what* runs in it, only *how many at once*.

## 5. Worker/process model

Ninja spawns **one fresh subprocess per edge**, runs the rule's `command` to completion, and discards it. There is no persistent worker process, no process pool, no warm state carried between edges — every edge pays full process-startup cost and starts from a clean slate. This is a structural difference from `Workers`: Ninja's "workers" are anonymous, stateless, ephemeral OS processes summoned per unit of work, not long-lived JVMs with declared classpath/jvmOptions/env and a `warmState` a task can reuse. Ninja has nothing analogous to a stateful worker kind.

## 6. Scheduling & concurrency control

- **Global cap**: `-j N` sets a hard ceiling on concurrently running edges (`0` means unlimited), default `#CPUs`. `-l N` additionally refuses to *start* new jobs while the 1-minute load average exceeds `N`, a soft backpressure valve layered on top of `-j`, independent of pools.
- **Pool depth composes with `-j` as a min, never a max**: "No matter what pools you specify, ninja will never run more concurrent jobs than the default parallelism, or the number of jobs specified on the command line (with `-j`)." A pool can only tighten concurrency further within the global envelope, never loosen it — there is no per-pool override that exceeds `-j`.
- **Edge selection order**: originally FIFO by discovery order among ready edges. A later addition ([PR #2019](https://github.com/ninja-build/ninja/pull/2019)) introduced a critical-path scheduler: each ready edge gets a priority equal to the longest weighted path remaining through the dependency DAG (weights sourced from historical run times in `.ninja_log`), and a `std::priority_queue` pops the highest-priority ready edge first, so edges that gate the longest remaining chain of work run before the rest. This is the one place Ninja has anything resembling per-task priority — it is derived automatically from the graph, not declared by the user, and pool depth constraints are respected unchanged underneath it.
- **The `console` pool**: a pre-defined pool named `console`, `depth = 1`, whose task gets direct access to Ninja's own stdin/stdout/stderr instead of a captured pipe. "Exclusive" here is narrower than global scheduler exclusivity: other, unrelated edges *continue running concurrently* in the background (subject to `-j`/other pools); what actually happens is Ninja *buffers its own progress output and any output from those concurrent tasks* until the console job finishes, so nothing interleaves with the console job's direct terminal writes. Depth 1 guarantees at most one console-pool job holds that terminal access at a time. It is used for edges that need a real interactive TTY (e.g. a test runner that prints a live progress bar).

## 7. Failure handling

- A nonzero exit from an edge's command is a build failure for that edge.
- `-k N`: keep going until `N` jobs have failed before stopping the whole build (`0` = never stop / build everything reachable regardless of failures); **default `N = 1`**, i.e. Ninja stops scheduling new work after the very first failure by default, though already-started concurrent jobs are allowed to finish.
- There is **no retry mechanism** anywhere in Ninja. A failed edge is reported and left failed; the next `ninja` invocation will simply re-attempt it because its output is missing/stale. Retry policy, if wanted, lives entirely in the generator or the human re-invoking the tool.
- No timeout mechanism exists either — a hung subprocess hangs the build until externally killed.

## 8. Worker reliability

Ninja's reliability story is minimal by construction: it has no persistent worker to keep alive, so several categories below don't exist for it at all. That absence is itself the most important data point for `Workers`, which does need a persistent-worker reliability story — and the one place Ninja *does* build real, deliberate, platform-specific machinery (orphan prevention) is instructive precisely because its two platforms diverge.

**Liveness / health detection.** No heartbeats, no ping protocol, no health-check RPC anywhere in Ninja. Ninja is the *direct parent* of every subprocess (`posix_spawn` on POSIX, `CreateProcess` on Windows) and blocks on it via `waitpid()` / `WaitForSingleObject`, so the OS itself is the liveness oracle. There is no separate "is the worker alive" question distinct from "has the child process exited," because the worker *is* the task's own process, not a daemon fronting it — the whole category of heartbeat/missed-ping/split-brain problems is sidestepped by not having long-lived worker identity in the first place.

**Hung/stuck-task detection.** None. Confirmed by direct read of `src/build.cc`: there is no timeout logic anywhere in the build loop. A subprocess that hangs (infinite loop, blocked read, deadlock) hangs the entire build indefinitely; the only recovery is a human hitting Ctrl-C. This is a real, acknowledged, still-open gap in a mature, widely-deployed tool.

**Crash detection & automatic restart.** Crash detection is free: `waitpid()`'s `WIFSIGNALED`/`WIFEXITED` (POSIX) or `GetExitCodeProcess` (Windows) tell Ninja exactly how and why a child died. But there is **no automatic restart or retry of a failed/crashed edge anywhere in the codebase** — confirmed by direct read of `build.cc`: a failed edge calls `Plan::EdgeFinished(edge, kEdgeFailed, ...)` and that is the end of it; the `-k N` failure-budget counter is the only thing consulted, never a retry counter. "Retry" is emergent, not a feature: because Ninja's freshness model is "does a valid, correctly-stamped output already exist," simply re-invoking `ninja` after a crash naturally redoes exactly the edges that didn't complete and nothing else. The retry loop lives entirely outside Ninja, in the human or CI system invoking it repeatedly.

**Orphan prevention** is the one area where Ninja does real, deliberate, platform-specific work — and where the two platforms diverge sharply, confirmed by direct read of `src/subprocess-posix.cc` and `src/subprocess-win32.cc`:
- **POSIX**: every non-console child is spawned with `posix_spawn` + `POSIX_SPAWN_SETPGROUP`, deliberately placing it in **its own process group** so a raw Ctrl-C (SIGINT delivered to the terminal's foreground process group) does *not* hit children directly — the source comment is explicit: *"Put the child in its own process group, so ctrl-c won't reach it."* This is intentional: Ninja wants to own interrupt sequencing itself rather than let every child race to handle SIGINT independently. When Ninja is interrupted, its own cleanup path explicitly does `kill(-pid, interrupted_signal)` — a **negative PID targets the whole process group**, so any further descendants a child itself spawned (e.g. a shell invoked by the rule's `command`) die too, not just the immediate child. Console-pool jobs are the deliberate exception: they stay in *Ninja's own* process group, so they get SIGINT/SIGTERM at the same instant Ninja does, since they share the real terminal and a simultaneous joint interrupt is exactly the wanted behavior there.
- **Windows**: no Job Object usage at all — no `CreateJobObject`, no `AssignProcessToJobObject`, no `JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE`. Orphan prevention relies purely on a registered `SetConsoleCtrlHandler` plus `GenerateConsoleCtrlEvent(CTRL_BREAK_EVENT, ...)`, asking children to cooperatively stop. This is strictly weaker than the POSIX path: if Ninja itself is hard-killed (task-killed, OOM-killed, crashes) before it propagates the break event, or a child ignores `CTRL_BREAK`, the child is orphaned. This is a live, unresolved gap in a tool used at Google/Chromium/Android scale — concrete evidence that **signal/event propagation alone is not orphan-proof**; only a kernel-enforced construct (a POSIX process-group kill, or a Windows Job Object with `KILL_ON_JOB_CLOSE`) actually guarantees no child outlives its parent's death.

**Graceful vs. forced shutdown / draining.** Ninja has no drain mode. On interrupt it does not wait for in-flight edges to finish gracefully — it kills everything immediately (or, for console jobs, dies alongside them) and marks the whole build `ExitInterrupted`. What it does do carefully is **selective output cleanup** (`Builder::Cleanup()`, confirmed in `build.cc`): for every edge still active at interrupt time, it re-stats each declared output and deletes it **only if the mtime actually changed during this run, or the edge has a depfile** — a pre-existing valid file that the interrupted command never touched is left alone (this asymmetry exists specifically so interrupting the *regeneration* of `build.ninja` itself doesn't destroy a perfectly good existing `build.ninja`, per the ongoing debate in [issue #2156](https://github.com/ninja-build/ninja/issues/2156)). The purpose is strictly correctness, not resource hygiene: a half-written output must never be mistaken for a valid, complete result by the next build.

**Resource-leak avoidance / worker recycling.** No concept of it, and there is structurally nothing to recycle: each subprocess's pipes and handles are closed the instant it exits, and Ninja itself carries no per-edge state forward. This tracks with there being no persistent worker; a "recycle after N tasks" policy only makes sense once long-lived worker identity exists to begin with, which Ninja never has.

**Delivery/execution guarantees on death mid-task.** Best characterized as *at-most-once effect on disk, eventual completion by re-invocation, deduplicated by construction*: `Cleanup()`'s selective deletion guarantees a half-completed edge's output is never mistaken for a done one (at-most-once on the artifact); nothing forces a retry, but because "needs rebuilding" is derived purely from the dependency graph plus the `.ninja_log`/deps log, re-running `ninja` after any death recovers exactly the incomplete work and never redoes already-good work. Deduplication is a side effect of content/timestamp-keyed freshness checking, not a tracked in-flight-work ledger or idempotency-key mechanism — there is no notion of "in-flight work" as first-class state at all. A crash leaves no bookkeeping to reconcile, only files on disk that are either valid or not, and even the logs themselves are designed to tolerate a mid-write crash: both `.ninja_log` and the deps log are append-only streams, and on a torn/corrupt trailing record the parser recovers by truncating to the last fully-read record rather than treating the whole log as invalid.

**Backpressure/overload protection.** `-l N` (load average) is the whole story: refuse to *start* new jobs while the 1-minute load average exceeds `N`, re-checked before each new dispatch. It is coarse and global — no per-pool or per-task admission control, no queueing/shedding policy, no signal back to a producer — because Ninja's "producer" is a static dependency graph decided entirely up front, not a live stream of incoming tasks; there is nothing to apply backpressure *to* in the sense a task-queue system needs.

**Mapped to `Workers`' reliability model.** Ninja's biggest lesson is negative-by-omission: it can afford almost no reliability machinery because it has no persistent worker, and the one place it *does* build real machinery (orphan prevention on interrupt) is exactly the one problem that survives even in a stateless-subprocess world. Concretely:
- **Crash → retry with a bound**: steal OS-level exit-status detection (`waitpid`/`WIFSIGNALED` equivalent) as the free, correct *detection* primitive, but do not stop there — Ninja's "retry" is just a human re-running the whole tool, which only works because a full invocation is cheap and idempotent. `Task.retry` needs to be a first-class scheduler feature that re-dispatches the one failed task, not a pattern punted to the caller.
- **Orphan-free kill**: steal the POSIX half of Ninja's design (dedicated process group per worker, `kill(-pgid, sig)` on teardown so grandchildren die too) as the floor, but explicitly do **not** steal the Windows half. Every platform needs a kernel-enforced teardown primitive (Job Objects with `KILL_ON_JOB_CLOSE` on Windows, not just a cooperative break-event), because Ninja's own unresolved Windows gap is direct evidence that signal-forwarding-only is insufficient for a real orphan-prevention guarantee.
- **Hung-task timeout**: adopt `Task.timeout` as a hard requirement precisely because Ninja's total absence of one is a known, live pain point for its users (see the linked fail-fast/stuck-console issues), not a gap nobody has noticed.
- **Worker recycling on a threshold**: no Ninja precedent exists to steal — stateless-by-construction workers have nothing to leak, so this has to be designed fresh for the stateful `warmState` worker kind, sourced from elsewhere (e.g. general worker-pool/actor-supervisor prior art), not from Ninja.
- **At-least-once with dedup**: deliberately do *not* copy Ninja's "idempotent redo, deduped by freshness state" as the delivery model — a thunk is an arbitrary computation, not a declared graph of files with mtimes, so there is no free idempotency signal to lean on the way Ninja leans on the filesystem. `Workers` needs an explicit in-flight-task ledger (which task was dispatched to which worker, and what its last known status was) precisely because it lacks the one thing that let Ninja skip building one.

## 9. Results

An edge's "result" is purely its exit code plus whatever files it wrote to its declared `outputs`; there is no in-process return value, no serialized result object, and no result channel back to a caller. Downstream edges consume the *files*, not a value — the dependency graph is the only handoff mechanism. `restat = 1` lets a rule tell Ninja "re-stat my outputs after running; if the mtime didn't actually change, treat downstream edges as not needing a rebuild," which is the closest thing to a freshness/idempotency signal, but it's still filesystem-timestamp based, not a typed result.

## 10. Transport/protocol

Not applicable in any IPC sense: an edge is a direct subprocess `fork`/`exec` from the Ninja process, communicating only via argv, environment, and the filesystem (declared inputs/outputs). Since Ninja 1.13 there is GNU Make jobserver client support, letting Ninja participate in a shared token-based concurrency pool across a parent `make` invocation and its children (relevant to `-l` interaction, but this is inter-tool coordination, not a task-transport protocol). There is nothing resembling serialized thunks sent to a remote/forked worker process — every "worker" is local and stateless.

## 11. Lessons for our `Workers` design

Mapping Ninja's vocabulary onto ours: `pool depth` ↔ `Task.Queue(maxWorkers)`, `pool = name` on a rule/build ↔ `task.queue(q)`, `-j` ↔ the global `Config(parallelism = N)` cap, `console` (depth 1, exclusive) ↔ our per-task `locks`/global "exclusive" lock.

**Steal:**
- **Depth-as-hard-cap composing as a min with the global cap**, never a max — directly matches our stated requirement that `maxWorkers` bounds a queue while the global `parallelism` cap still bounds everything. Ninja's phrasing ("never more than the default parallelism *or* `-j`") is a clean, precedent-tested way to state the same invariant for `Task.Queue.maxWorkers` vs. `Config(parallelism)`.
- **Named pool as pure concurrency gate, opt-in per unit of work** — the flat-namespace, opt-in-by-reference shape (`pool = link_pool` on a rule, overridable per-build with `pool =` to opt back out) is worth mirroring in `task.queue(q)`: allow a per-task override to leave a queue, the same way an individual `build` statement can clear an inherited pool.
- **"Exclusive" as buffered/serialized *output*, not full scheduler exclusivity** — worth deliberately clarifying against, since our `locks`/global-"exclusive" wording invites the opposite (stronger) reading than Ninja's `console`. If our exclusive lock is meant to actually halt *other* work (unlike Ninja's console pool, which lets other edges keep running underneath it), that's a meaningful design divergence to state explicitly rather than let the naming imply Ninja's weaker semantics.
- **Critical-path-derived priority from historical run times** (`.ninja_log` timings feeding a priority queue) is a strong, low-effort idea for scheduling within a queue once tasks have run at least once: no user-declared priority field needed, the system can infer "gates the most downstream work" or "historically the slowest" and front-load it.

**Avoid:**
- **No retries, no timeouts, no results channel** — Ninja can get away with this because a Ninja invocation is idempotent and re-runnable at zero cost (the next invocation just resumes from missing outputs). `Workers` explicitly wants `Task.timeout`, `Task.retry`, and a typed `Fiber[A,E]` result; none of that has a Ninja analogue to borrow, and Ninja's silence here is a consequence of its narrower problem (rebuild orchestration), not a pattern to emulate.
- **Fully stateless, anonymous per-edge subprocess spawn** — fine for Ninja's rebuild-graph domain where the process's whole job is "run this command once," but not a model for our stateful worker kind (`warmState`), which needs persistent, addressable worker identity across tasks. Ninja has nothing to steal here; our stateful-worker design is unprecedented in this system and should be sourced elsewhere.
- **Default `-k 1` (stop scheduling on first failure)** as a *global* default is defensible for a build (a broken compile usually invalidates the point of continuing) but is a much more aggressive default than a task-queue system should probably assume; worth treating as a configurable policy rather than adopting the "stop on first failure" default outright.
- **Kernel-enforced orphan prevention, on every platform** (§8) — steal the POSIX process-group-kill pattern outright; explicitly reject Ninja's own Windows fallback (cooperative `CTRL_BREAK_EVENT` only, no Job Object) as insufficient, since it is a documented live gap in Ninja itself, not a design to emulate.
- **No hang detection anywhere** (§8) is Ninja's single clearest "avoid": a mature, widely-deployed tool still has no subprocess timeout, and it is a genuine, acknowledged pain point for its users — direct evidence that skipping `Task.timeout` is not a viable simplification.

## Sources

- [The Ninja build system manual](https://ninja-build.org/manual.html) — pools, `console` pool, `-j`/`-l` flags, jobserver support
- [manpages.debian.org: ninja(1)](https://manpages.debian.org/testing/ninja-build/ninja.1.en.html) — verbatim `-j`/`-l`/`-k` flag text and defaults
- [ninja-build/ninja PR #714: Introduce the "console" pool](https://github.com/ninja-build/ninja/pull/714)
- [ninja-build/ninja PR #2019: Add critical path scheduler to improve build times](https://github.com/ninja-build/ninja/pull/2019)
- [ninja-build/ninja src/subprocess-posix.cc](https://github.com/ninja-build/ninja/blob/master/src/subprocess-posix.cc) — process-group isolation (`POSIX_SPAWN_SETPGROUP`), group-kill on interrupt, console-pool passthrough
- [ninja-build/ninja src/subprocess-win32.cc](https://github.com/ninja-build/ninja/blob/master/src/subprocess-win32.cc) — no Job Object usage; `SetConsoleCtrlHandler`/`CTRL_BREAK_EVENT` only
- [ninja-build/ninja src/build.cc](https://github.com/ninja-build/ninja/blob/master/src/build.cc) — `Builder::Cleanup()` selective output deletion, no retry/timeout logic in the build loop
- [ninja-build/ninja src/deps_log.cc](https://github.com/ninja-build/ninja/blob/master/src/deps_log.cc) / [issue #595](https://github.com/ninja-build/ninja/issues/595) — append-only log recovery by truncation on corruption
- [ninja-build/ninja issue #110](https://github.com/ninja-build/ninja/issues/110) and [issue #2156](https://github.com/ninja-build/ninja/issues/2156) — partial-output deletion policy and its edge cases
- [ninja-build/ninja issue #965](https://github.com/ninja-build/ninja/issues/965) and [issue #2116](https://github.com/ninja-build/ninja/issues/2116) — known console-pool termination/signal-forwarding gaps
