# Bazel: declarative test execution model

Scope note: this covers Bazel's *test* scheduling model (`bazel test`, `size`/`timeout`/`tags`, resource
budgeting, sharding, flaky retries). It deliberately excludes Bazel Persistent Workers (the
`--persistent_worker` long-lived compiler-process protocol) — that is a sibling agent's brief.

## 1. What it is

Bazel is a build/test system where every test is a build graph *action*: a hermetic, declaratively
described unit of work with declared inputs, a command, and declared resource needs. The scheduler
(not the user) decides how many tests run concurrently, locally, based on a global resource budget
and per-test declared consumption. There is no persistent test process and no queue the user manages by
hand — concurrency is an emergent property of "how many declared-resource actions fit in the box."

## 2. Unit of work (a test target/action)

A `*_test` rule (`cc_test`, `java_test`, `sh_test`, ...) produces one test *target*. At execution time
each target becomes one or more test *actions* (one per shard × per attempt/run). Each action is:
- a single subprocess invocation of the test binary,
- run inside a sandbox (on platforms that support it) so it can only see its declared runfiles,
- given a fixed set of environment variables describing its identity and constraints (`TEST_SIZE`,
  `TEST_TIMEOUT`, `TEST_SHARD_INDEX`, `TEST_TOTAL_SHARDS`, `XML_OUTPUT_FILE`, `TEST_UNDECLARED_OUTPUTS_DIR`, ...).

There is no notion of a test *session* spanning multiple targets; each action is independently
schedulable and independently retryable.

## 3. Grouping / routing (tags, exclusive groups)

Routing is done entirely through the `tags` attribute (a `List[String]`), interpreted by both the
scheduler and the CLI's `--test_tag_filters`. Bazel test rules inherit `args`, `flaky`, `local`,
`shard_count`, `size`, `timeout` as first-class attributes, plus arbitrary tags:

- **`exclusive`** — "will force the test to be run in the 'exclusive' mode, ensuring that no other
  tests are running at the same time. Such tests will be executed in serial fashion after all build
  activity and non-exclusive tests have been completed. Remote execution is disabled for such tests
  because Bazel doesn't have control over what's running on a remote machine." This is a hard global
  exclusion, not a named lock: it drains to "run alone."
- **`exclusive-if-local`** — same serial-alone guarantee, but *only* when the test executes locally;
  if it executes remotely it runs in parallel with everything else. This is the closest Bazel concept
  to a conditional/soft exclusive.
- **`local`** — "precludes the action or test from being remotely cached, remotely executed, or run
  inside the sandbox." (There's also a `local` *attribute*, `local = True`, equivalent to the tag —
  forces the test to run locally, unsandboxed.)
- **`manual`** — "will exclude the target from expansion of target pattern wildcards (`...`, `:*`,
  `:all`, etc.)" and from `test_suite` targets that don't list it explicitly — i.e. opt-out of "run
  everything" sweeps; must be named directly to run.
- **`external`** — "will force test to be unconditionally executed (regardless of
  `--cache_test_results` value)" — always re-run, never served from cache.
- **`flaky`** (attribute, not tag) — "Marks test as flaky. If set, executes the test up to three times,
  marking it as failed only if it fails each time."

`exclusive` has no notion of *named* exclusion groups — it is one global serialization bucket, unlike
a lock keyed by name. Anything finer-grained (e.g. "these two targets must not run together, but can
run with everything else") is not expressible through tags; teams work around it with manual
target-splitting or external orchestration.

## 4. Declarative config / requirements model

### size / timeout

`size` declares a target's "heaviness": how much time/resources it needs. Default is `medium`. This
single attribute drives *both* the default timeout *and* the assumed peak local resource footprint,
straight from Bazel's own docgen template (`size.html`):

| size | RAM | CPU | implied timeout | timeout duration |
|---|---|---|---|---|
| `small` | 20 MB | 1 core | `short` | 60s |
| `medium` | 100 MB | 1 core | `moderate` | 300s |
| `large` | 300 MB | 1 core | `long` | 900s |
| `enormous` | 800 MB | 1 core | `eternal` | 3600s |

`timeout` can be set independently of `size` — "all combinations of size and timeout labels are
legal, so an 'enormous' test may be declared to have a timeout of 'short'." `TEST_SIZE` and
`TEST_TIMEOUT` (seconds) are exported into the test's environment so the test binary itself can adapt.

### CPU reservation beyond the size default

"Tests are granted at least one CPU core. Others may be available but this is not guaranteed... You
can increase the reservation to a higher number of CPU cores by adding the tag `cpu:n` (where n is a
positive number) to a test rule." (Equivalently `resources:cpu:n`.) There is no first-class RAM
override tag — RAM stays pinned to the `size` table; teams that need more have historically had to
lobby for or hack around it (see bazelbuild/bazel#14601).

### sharding

`shard_count` (attribute, non-negative integer, ≤ 50) requests that many parallel shards for a single
target. When sharding is enabled, "the test runner is launched once per shard," and each shard process
receives `TEST_SHARD_INDEX` (0-based) and `TEST_TOTAL_SHARDS` in its environment so the test binary can
partition its own cases. `--test_sharding_strategy` controls whether/how sharding is honored at
invocation time (e.g. force it on/off across all shardable targets).

## 5. Worker/process model (per-action process, sandbox, hermetic)

Each test action is a fresh subprocess, sandboxed where the platform supports it (Linux namespaces,
macOS sandbox-exec). "Tests should be hermetic: that is, they ought to access only those resources on
which they have a declared dependency" — inputs reach the process only "through the runfiles
mechanism, or other parts of the execution environment which are specifically intended to make input
files available." No implicit filesystem/network access, no cross-test shared state by default. The
`local` tag/attribute is the explicit escape hatch when a test genuinely cannot be sandboxed (needs a
local daemon, a device, etc.) — at the cost of losing remote execution/caching for that target.

There is no "warm" or persistent test worker in this model (that's the sibling Persistent Workers
brief): every test run pays full process-start cost, by design, in exchange for hermeticity and cache
correctness.

## 6. Scheduling & concurrency control

Bazel's *local* scheduler is a bin-packing admission controller, not a fixed-size thread pool:

- `--jobs` sets a nominal concurrency cap, but "the number of concurrent jobs that Bazel will run is
  determined not only by the `--jobs` setting, but also by Bazel's scheduler, which tries to avoid
  running concurrent jobs that will use up more resources (RAM or CPU) than are available, based on
  some (very crude) estimates of the resource consumption of each job."
- `--local_ram_resources` / `--local_cpu_resources` declare the total budget: "the amount of local
  resources ... that Bazel can take into consideration when scheduling build and test activities to
  run locally." Accepts a float or `HOST_RAM`/`HOST_CPUS` with `[-|*]float` modifiers (e.g.
  `--local_cpu_resources=HOST_CPUS-1`, `--local_ram_resources=HOST_RAM*.5`). By default Bazel
  estimates both directly from the local machine. These flags are independent; either or both may be
  set — the documented remediation for OOMs under a container runtime is to set
  `--local_ram_resources` explicitly to the container's actual limit, because Bazel otherwise sizes
  the budget off the host, not the cgroup.
- Admission is a greedy bin-pack: "the scheduler estimates the memory usage of each action and doesn't
  schedule actions if there isn't enough memory available (except it'll always schedule at least one,
  to make progress)" — a starvation-avoidance guarantee, not a fairness guarantee.
- `exclusive`/`exclusive-if-local` tests are pulled out of that pool entirely and serialized: "executed
  in serial fashion after all build activity and non-exclusive tests have been completed."

So concurrency is: `min(jobs, resource-budget-bin-pack)` for the normal pool, plus a fully serial tail
for exclusive tests. Every action's cost estimate comes from its declared `size` (RAM/CPU table above)
or explicit `cpu:n` tag — the same declaration that sets the timeout doubles as the scheduler's
admission cost.

## 7. Worker / process reliability

Bazel's test model has no persistent worker to keep alive — reliability is entirely about making one
ephemeral subprocess *fail safely* and *leave nothing behind*. That's a narrower problem than a
long-lived worker pool (the sibling Persistent Workers brief owns that), but the mechanisms below are
the concrete playbook Bazel uses for it, and several transfer directly.

### Liveness/health detection

There is no heartbeat or ping protocol. Bazel substitutes a **deadline contract**: the declared
`timeout` (from `size`, or explicit) is handed to the process as `TEST_TIMEOUT` and simultaneously
armed as a hard external kill deadline by the execution wrapper. "Alive" is never actively checked
mid-run; a test is presumed healthy until it either exits or blows the deadline. This is simpler than
heartbeating but coarser: a test wedged at 90% of its timeout budget is indistinguishable from one
making steady progress until the deadline actually expires.

### Hung/stuck detection and recovery

Enforcement is done by Bazel's **process-wrapper** helper binary, which every local action (including
every test) runs under. Per its own description, process-wrapper: "runs a subprocess with an optional
timeout," "redirects stdout and stderr to optionally-provided files," and "ensures the subprocess and
all of its children are terminated on exit." Recovery from a hang is two-stage: a `-t/--timeout` flag
fires `SIGTERM` at the deadline, and a `-k/--kill_delay` flag (surfaced to users as
`--local_termination_grace_seconds`) specifies how long to wait before escalating to `SIGKILL` if the
process hasn't exited. This is the textbook graceful-then-forced pattern — but it is not bulletproof in
practice: bazelbuild/bazel#14298 documents tests being `SIGKILL`-ed immediately, skipping the declared
grace period entirely, for some language runtimes. Worth carrying forward as a caution, not just a
recipe: the two-stage kill is simple to describe and easy to get wrong in the small (which signal target,
which order, whether the grace period is actually honored under all code paths).

### Crash detection and automatic restart/respawn

There is no restart of a *worker* because there is no persistent worker — "crash" is scoped to a single
action. A nonzero exit or signal death simply ends that attempt as FAILED. "Respawn" is the flaky-attempt
budget from Section 8 below: a completely fresh process launch, not a resumed one. This only works
because Bazel requires the thing being restarted (a hermetic test) to be safe to fully re-run from
scratch — the model has no notion of resuming partial progress.

### Orphan prevention

This is the most concretely reusable mechanism. process-wrapper places the test subprocess "under a new
process group" specifically so it can "terminate this whole group in unison" — a test that forks a
grandchild doesn't leak that grandchild when the wrapper kills the group, because the whole tree dies
together, not just the process the wrapper directly spawned. On Linux, `linux-sandbox` (layered on top
of process-wrapper) hardens this further with `PR_SET_PDEATHSIG` — a `prctl()` flag that "registers a
signal to be sent to the child process on its parent's death" — combined with PID namespaces, so the
guarantee holds even if Bazel's own process is killed abruptly (not just at a declared timeout). Bazel's
own docs are honest about the limit of the simpler mechanism: "process groups are not infallible because
a process can trivially escape them" (e.g. by double-forking/daemonizing) — which is exactly why the
PID-namespace layer exists as the stronger guarantee for the cases that matter.

### Graceful vs forced shutdown and draining

Two distinct shutdown paths exist:
- **Per-action timeout**: SIGTERM to the process group, wait the grace period, SIGKILL — see above.
- **Whole-invocation interrupt (Ctrl-C)**: documented guidance is to "press Ctrl-C only once to request
  a graceful end of the current invocation" (a second Ctrl-C kills the Bazel server outright). In
  practice this path has real, documented gaps: bazelbuild/bazel#2602 reports subprocesses not reliably
  killed on interrupt requiring manual cleanup, and bazelbuild/bazel#10573 reports Bazel not terminating
  subprocesses on Ctrl-C at all on Windows, causing a subsequent server restart to hang until the old
  processes exit on their own. Also notable: an in-flight *build error* kills sibling actions immediately,
  but a Ctrl-C historically let already-running actions run to completion instead of cutting them short
  — an asymmetry between "we already know this is doomed, so end it" and "the user asked to stop" that a
  new system should decide deliberately rather than let it fall out by accident.

### Resource-leak avoidance and recycling

Because no test process is ever reused, there is no "worker recycling" concept in this half of Bazel at
all — the leak-avoidance strategy is structural, not threshold-based: never let a process live long
enough to accumulate fd/memory leaks in the first place. The sandbox directory backing each action is
torn down after the action completes and is retained (bypassing cleanup) only when `--sandbox_debug` is
explicitly passed for debugging — i.e. "don't clean up" is an opt-in diagnostic mode, never the default.
(Threshold-based recycling of a *long-lived* process — `--worker_max_instances`, memory-pressure
eviction — is a Persistent Workers concept and belongs to the sibling brief; it has no counterpart here
because there is nothing long-lived to recycle.)

### Delivery/execution guarantees when a worker dies mid-task

Bazel's test model doesn't map cleanly onto the classic at-least-once/at-most-once/exactly-once
vocabulary, because it has no partial-progress state to protect. A killed or crashed attempt is simply a
FAILED attempt; the *only* recovery primitive is "launch a brand new attempt from zero," bounded by the
flaky-attempt budget. Safety of doing this is not detected or negotiated at runtime — it's an a priori
contract the rule author is required to uphold (tests must be hermetic and side-effect-free enough that
re-running is always correct). Layered on top, `--cache_test_results` (default `auto`) skips
re-execution entirely once Bazel has a matching-input result already recorded, which behaves like
memoized at-most-once execution for the *unchanged* case, but says nothing about a currently in-flight
run that gets killed — that attempt's result is simply discarded, not deduplicated or merged with
anything.

### Backpressure/overload protection

Covered mechanically in Section 6 (the resource-budget bin-pack), but there's a second, test-specific
knob: `--local_test_jobs` caps concurrent local tests independently of the general build-action pool,
though it is itself capped by `--jobs` ("setting this to a value above `--jobs` has no effect"). This is
a real cautionary tale for composing two caps: multiple open bugs (bazelbuild/bazel#16786,
bazelbuild/bazel#22598) document `--local_test_jobs` and per-test resource tags (`cpu:n`,
`resources:*`) disagreeing about admission — resource-tagged tests getting dispatched without respecting
their declared cost once a job-count cap is also in play. Two independently-reasonable caps composing
incorrectly is a proven failure mode in a system built by people who think about exactly this problem
for a living.

## 8. Failure handling (flaky attempts, retries)

- Default: one attempt per test. `flaky = True` on the rule bumps that to "up to three times, marking
  it as failed only if it fails each time" — i.e. any single pass within the budget marks PASSED.
- `--flaky_test_attempts=N` overrides the attempt budget from the CLI (max 10), independent of the
  rule's own `flaky` attribute; same all-or-nothing semantics (all runs pass → PASSED, all fail →
  FAILED — note this is stricter than "any pass wins" for some Bazel versions' interpretation, so the
  practical rule to carry forward is "N attempts, majority/any-pass semantics configurable, but the
  attempts themselves are Bazel-owned retries, not the test's own retry logic").
- `--runs_per_test=N` is the orthogonal debugging tool: forces every run to execute (no short-circuit
  on first pass) and reports each run as a *separate* test result — used to characterize flake rate,
  not to get a single green signal.
- `--test_output` controls how much of a run's stdout/stderr surfaces to the invoking console
  (`errors` = only failing tests' output, `all`, `summary`, `streamed`).

## 9. Results

Each test action writes a JUnit-schema XML result to the path in `XML_OUTPUT_FILE`, plus arbitrary
undeclared artifacts under `TEST_UNDECLARED_OUTPUTS_DIR`. Bazel aggregates these per-target (and across
shards/attempts) into the invocation's overall PASS/FAIL summary; `--test_output`/`--test_summary`
govern what's echoed live versus left in the result tree for post-hoc inspection.

## 10. Transport/protocol (brief)

No RPC/wire protocol for local test execution: it's argv + env vars + filesystem (runfiles in,
declared outputs + XML out). Remote execution (RBE) layers a separate gRPC action-execution protocol
underneath the same declarative action description, but that's an execution-backend concern, not part
of the test-declaration model itself — the `size`/`tags`/`shard_count` contract is backend-agnostic by
design (same target definition runs local or remote).

## 11. Lessons for `Workers`

Direct mappings from Bazel's vocabulary onto the sketch (`Workers.run`, `Task.Queue`,
`task.locks`/`.weight`/`.isolation`):

- **`exclusive` tag → our `.exclusive`.** Bazel's `exclusive` is unnamed/global: one serialization
  bucket, drains before running alone. `Workers`' named `locks` (including a global `exclusive`) is
  strictly more expressive — steal the *guarantee* (serial, no overlap with anything), but keep the
  named-lock generality since Bazel's own community has repeatedly wanted finer-grained exclusion and
  never gotten it (workaround is manual target-splitting, which is exactly the pain a named-lock model
  avoids).
- **`exclusive-if-local` → a `.exclusive` that's conditional on worker placement.** Worth stealing as
  a *concept* even if `Workers` doesn't have a remote/local distinction yet: a task's exclusivity
  requirement can be a function of which worker kind/queue it lands on, not a fixed property of the
  task.
- **`size` (with its baked-in RAM/CPU table) + `local_resources` budget → our `weight` + global cap.**
  This is the single most directly reusable mechanism: Bazel ties one declarative label to both a
  *default timeout* and a *scheduler admission cost*, and the scheduler bin-packs against an explicit,
  overridable total budget (`--local_ram_resources`/`--local_cpu_resources`) rather than a bare
  concurrency count. Steal: let `weight` (permits vs the parallelism cap) double as the *default*
  timeout tier the way `size` does, so a task declares "heaviness" once instead of separately tuning
  weight and timeout. Also steal the "always schedule at least one to avoid starvation" admission rule
  for the weight/cap admission logic.
- **`shard_count` + `TEST_SHARD_INDEX`/`TEST_TOTAL_SHARDS` → leaf-level task sharding.** If a `Task`
  ever wants to fan out N parallel sub-instances of itself (analogous to sharding a test binary), the
  clean primitive is: declare a shard count, inject `(index, total)` into each forked worker's env, and
  let the task's own thunk decide how to partition. This composes with `weight`/`locks` unchanged
  since each shard is just another `Task` instance from the scheduler's point of view.
- **Tags (`manual`, `external`, `local`, `flaky`) → declarative `Task` fields, not a magic-string
  tag bag.** Bazel's tag strings are stringly-typed and rely on filter conventions
  (`--test_tag_filters`) at the CLI layer to be useful; `Workers`' typed `Task` config (`retry`,
  `timeout`, `isolation`, `env`) is already the more disciplined version of the same idea — no
  action needed except resisting the temptation to add a generic string-tag escape hatch later, since
  Bazel's own ecosystem shows that's where ad-hoc, unenforceable conventions creep in.
- **Orphan-free kill → process group + parent-death fallback.** Steal the two-layer pattern directly:
  fork each worker JVM into its own process group (or on the JVM, its own OS process anyway — the
  requirement is that the coordinator can kill the *whole* subtree, not just the immediate child) so a
  single kill call reaps every descendant a worker might itself spawn. Then add the second layer Bazel
  adds specifically for the "coordinator died" case: since the JVM has no built-in `PR_SET_PDEATHSIG`
  equivalent, `Workers` needs an explicit parent-liveness mechanism on the worker side (e.g. the forked
  JVM watches its parent's PID/a heartbeat pipe and self-terminates if the parent vanishes) rather than
  relying solely on the coordinator remembering to clean up — Bazel's own docs concede process groups
  "are not infallible because a process can trivially escape them," which is precisely the gap
  `PR_SET_PDEATHSIG`/PID namespaces close. Flag explicitly: `PR_SET_PDEATHSIG` is Linux-only and Bazel's
  own Ctrl-C/orphan cleanup is documented as broken on Windows (bazelbuild/bazel#10573) — if `Workers`
  must run cross-platform, the parent-liveness mechanism needs a real (tested) per-platform story, not
  an assumption that the Linux trick generalizes.
- **Hung-task timeout → two-stage SIGTERM-then-grace-then-SIGKILL, but test it, don't just implement
  it.** The mechanism is simple to state and Bazel still has an open bug (#14298) where the grace period
  is silently skipped for some runtimes. Treat "does the grace period actually get honored on every
  worker-kill code path" as a concrete regression test to write for `Workers`, not an assumption that
  follows from having implemented the two-stage logic once.
- **Crash → retry with a bound, but Bazel's safety assumption may not transfer.** Bazel's
  flaky-attempt retry (Section 8) is safe *only* because a test rule is contractually required to be
  hermetic and side-effect-free on re-run. `Workers` tasks are arbitrary user thunks that may have real
  side effects (writes, external calls) — blindly adopting "kill and relaunch from scratch, bounded by
  `retry`" is only correct for tasks the caller has actually declared idempotent. This is a genuine open
  design fork, not something to resolve by default: either `retry` requires/assumes idempotence as a
  documented contract (Bazel's approach, pushed onto the task author), or `Workers` needs a narrower
  at-most-once guarantee (no automatic retry without an explicit opt-in) for tasks that aren't declared
  safe to repeat.
- **Worker recycling → out of scope for this brief, by design.** Bazel's test-execution model has zero
  worker-recycling behavior because it never reuses a process — the leak-avoidance story is "never let a
  process live long enough to leak." `Workers`' stateful/warm worker kind is explicitly the opposite
  case (long-lived by design), so threshold-based recycling belongs to the sibling Persistent Workers
  research, not this one; noting the boundary rather than inventing a Bazel-flavored answer here.
- **Backpressure composition → a concrete regression test, not just a design note.** Bazel's own bug
  tracker shows two independently-reasonable caps (`--local_test_jobs` and per-test `cpu:n`/resource
  tags) disagreeing about admission in practice. `Workers` has the same shape of risk between the global
  `parallelism` cap and per-task `weight`: write the equivalent regression test early (a weighted task
  under a simultaneously-full job-count queue and a simultaneously-full weight budget must not be
  double-admitted) rather than trusting that composing two caps "obviously" works.
- **Avoid: RAM has no override tag, only CPU does.** Bazel's `cpu:n` tag lets you bump CPU
  reservation but there's no equivalent for RAM (`resources:ram:n` is filed as broken/unsupported,
  bazelbuild/bazel#14601/#19572) — a known, long-standing asymmetry the community complains about.
  `weight` in `Workers` should not replicate this gap: whatever the "cap" dimension is (worker slots,
  memory, or both), the override path should be uniform across all dimensions from day one.
- **Avoid: exclusive has no remote-caching story once triggered.** Bazel disables remote execution
  entirely for `exclusive` tests ("Bazel doesn't have control over what's running on a remote
  machine"). For `Workers`, an `exclusive`-locked task still needs a defined interaction with
  `Task.Queue`'s named worker pools (does exclusive drain the whole queue, or just serialize within
  it?) — Bazel's global-only model dodges this question by having only one global bucket; `Workers`
  cannot dodge it because locks are named and queues are named, so this is a design question to close
  explicitly, not inherit implicitly.

## Steal / avoid summary

- **Steal:** size-as-both-timeout-and-cost declaration, budget-driven bin-pack admission (not a bare
  thread count), starvation-avoidance ("always schedule at least one"), sharding via injected
  index/total env pair, `manual`-style opt-out-of-sweep semantics for tasks not meant to run by
  default, process-group-based kill so a task's own children never leak.
- **Steal (as typed fields, not string tags):** `flaky`/attempt-budget semantics for `retry`,
  `external`-style "never serve from cache" for any future task-memoization feature.
- **Steal (reliability specifically):** the two-layer orphan defense (kill-the-group, plus an
  independent parent-liveness check on the child side rather than trusting the coordinator alone); the
  deadline-contract model for liveness (no heartbeat needed, but be honest that it's coarser than one);
  treating "grace period actually honored" and "two caps compose correctly under load" as regression
  tests to write, because Bazel's own tracker shows both are easy to get subtly wrong even in a mature
  system.
- **Avoid:** single global unnamed exclusive bucket (use named locks instead, already planned);
  asymmetric resource-override support (CPU but not RAM); leaving the exclusive-vs-named-queue
  interaction undefined the way Bazel's exclusive-vs-remote interaction is basically "just disabled";
  assuming Bazel's "retry = always safe" premise transfers to arbitrary user thunks without an explicit
  idempotence contract; assuming a Linux-only orphan-prevention trick (`PR_SET_PDEATHSIG`) generalizes
  to other platforms without being tested there.
