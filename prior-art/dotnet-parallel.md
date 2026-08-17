# Prior art: xUnit.net and NUnit test parallelization

## 1. What it is

**xUnit.net** and **NUnit** are the two dominant .NET unit test frameworks. Both ship built-in parallel test execution as a first-class feature (not a plugin), controlled entirely through declarative attributes on test classes, methods, and assemblies. Both models exist to answer the same question our `Workers` module answers: which units of work may run concurrently, which must not, and how many run at once.

## 2. Unit of work

- **xUnit**: the test *method* is the schedulable unit, but grouping happens at the *class* level. By default there is "a test collection per test class" — every class implicitly becomes its own collection unless overridden.
- **NUnit**: the test *method* and the test *fixture* (class) are both independently schedulable; `[Parallelizable]` can be applied at either level, plus at the assembly level.

## 3. Grouping / routing — the load-bearing concept

### xUnit collections: named serial groups (== our `locks`)

```csharp
[Collection("Our Test Collection #1")]
public class TestClass1
{
    [Fact]
    public void Test1() { Thread.Sleep(3000); }
}
```

Tests **within** the same collection always run sequentially; tests in **different** collections may run in parallel with each other. This is structurally identical to a named mutual-exclusion lock: `[Collection("DatabaseTests")]` on multiple classes is exactly "these tasks share the `DatabaseTests` lock." A class with no `[Collection]` attribute is its own singleton collection — the equivalent of an unnamed/default lock scope.

To force full serialization of everything inside one named group (i.e., disallow even the collection's own parallel-eligible members), the group's definition is marked:

```csharp
[CollectionDefinition("Test Collection Name", DisableParallelization = true)]
public class OurTestCollectionDefinition { }
```

### NUnit `ParallelScope`: declared eligibility, not grouping by name

NUnit has no named-collection concept. Instead `[Parallelizable(ParallelScope)]` is a `[Flags]` enum declaring *how far down the tree* parallel eligibility extends:

| Value | Meaning | Valid on |
|---|---|---|
| `Self` | this test/fixture may run in parallel with siblings at its level (default when the attribute has no argument) | Class, Method |
| `Children` | this node's direct children may run in parallel with one another | Assembly, Class |
| `Fixtures` | descendant fixtures may run in parallel with one another | Assembly, Class |
| `All` | test and descendants all become parallel-eligible at their respective levels | Class, Method |

The counterpart `[NonParallelizable]` marks a fixture/method as must-run-alone — closer to our per-task `exclusive` lock than to a named group, since NUnit has no notion of *which other tests* it's exclusive with beyond "not concurrently with anything else in its shift" (see §6).

**Key difference from xUnit**: NUnit's scope is *hierarchical eligibility* declared per node, and eligibility composes down the tree but a child can only narrow, never widen, what an ancestor allowed ("lower-level specifications override higher ones, but cannot override restrictions from above"). xUnit's collection is *named-group membership*, symmetric and flat — two classes in the same named collection are mutually exclusive regardless of tree position. xUnit's model maps directly onto named locks; NUnit's maps onto a parallel/non-parallel eligibility flag plus scope propagation.

## 4. Declarative config / requirements model

Both frameworks push concurrency policy to **assembly-level attributes**, read once at process/run start:

```csharp
// xUnit
[assembly: CollectionBehavior(CollectionBehavior.CollectionPerClass)]
[assembly: CollectionBehavior(MaxParallelThreads = n)]
[assembly: CollectionBehavior(DisableTestParallelization = true)]

// NUnit
[assembly: LevelOfParallelism(3)]
```

`LevelOfParallelism` defaults to `Environment.ProcessorCount` (min 2) if unset, and is explicitly a **cap**, not a target: "Tests must be marked `[Parallelizable]` to actually run in parallel. This attribute only sets the upper limit on worker threads." `MaxParallelThreads` in xUnit plays the identical role (default: available CPU threads). Both frameworks separate "how many workers exist" (a single global assembly-level number) from "which tests are allowed to use them" (per-class/method attributes) — the same two-tier shape our `Config(parallelism=N)` + per-`Task` declarations already have.

## 5. Worker/process model — the one thing that does NOT transfer

Both frameworks are **in-process and thread-based**, not process-forking:

- NUnit's internals (confirmed via its technical docs) run a **shift-based queue system**: a `WorkShift` owns a pool of worker *threads*, distinguishing MTA (multi-threaded apartment) workers from a dedicated STA (single-threaded apartment) worker — a .NET COM-interop threading concept that only makes sense in-process. "Whenever a non-parallel fixture begins execution, an entirely new set of queues is created."
- xUnit parallelizes "tests within a single assembly" using thread-pool-backed task scheduling; no mention anywhere of separate OS processes.

This is the single biggest structural gap versus `Workers`: xUnit/NUnit parallelism assumes cooperative in-memory isolation (shared static state is the developer's problem to avoid) and pays no IPC/serialization cost, whereas `Workers` forks OS processes specifically to get hard fault/memory isolation between tasks. Their scheduling vocabulary (locks, caps, fixtures) is reusable; their execution substrate is not.

## 6. Scheduling & concurrency control

- **NUnit**: shifts run to exhaustion one at a time — Parallel Shift (N workers, MTA + 1 STA), then Non-Parallel Shift (1 worker), then Non-Parallel STA Shift (1 worker) — so non-parallel work is globally serialized against *all* other non-parallel work, not just work in its own group, which is coarser than a named lock. Setting `LevelOfParallelism(1)` collapses everything to fully sequential.
- **xUnit**: collections are the unit of concurrency; the assembly-level `MaxParallelThreads` bounds how many collections' tests run concurrently at once, analogous to our global `parallelism` cap sitting above per-task named locks.

## 7. Reliability

Neither framework's parallelization docs couple scheduling to failure/retry policy — a failed parallel test simply fails and reports independently; there's no cascade-cancel or grouped abort tied to collection/fixture membership. This section covers what reliability machinery actually exists on the .NET side, and then — the part that matters more for `Workers` — what does *not* transfer from an in-process model to a process-forking one, and why.

### 7.1 What exists in-process

**Timeout.** xUnit: `[Fact(Timeout = 5000)]` (ms). Documented limitations are severe: it "only works with async tests" (the sync-test case is an open bug), and it "is only supported when parallelization is disabled" — timeout and parallel execution are documented as mutually exclusive features. NUnit's story is more instructive because it changed shape: the original `[Timeout(ms)]` "immediately cancelled" a hung test by calling `Thread.Abort()` on its thread. `Thread.Abort()` was removed in .NET 5+, so as of NUnit 4.5 `[Timeout]` is obsolete and using it on .NET 5+ **is itself reported as a failure**. Its replacement, `[CancelAfter(ms)]`, only *marks a `CancellationToken` as canceled* after the interval — the test method must itself observe that token to actually stop. **If it doesn't (blocked in native code, a tight synchronous loop, a deadlock), NUnit has no way to stop it.** This is a direct consequence of thread-level (not process-level) isolation: there is no safe way to force-terminate one thread from outside it in modern .NET, only to ask it to cooperate.

**Retry.** NUnit ships `[Retry(n)]` built in (`n` = total attempts including the first, not retries-after-failure). By default it retries only on assertion failure, not on an arbitrary thrown exception — "an error result is returned and it is not retried" — unless the exception type is added to `RetryExceptions` (NUnit 4.5+). A separate `[Repeat(n)]` runs a test `n` times unconditionally (with `StopOnFailure` true by default, or false to collect every rep's result). xUnit has **no built-in retry at all**; the community fills this with the third-party `xRetry` package (`[RetryFact(n)]` / `[RetryTheory(n)]`), whose own docs carry an explicit warning that it exists only for transient/external-dependency flakiness and "should not be used" to paper over a real bug's sporadic failures.

**Isolation between collections/classes.** None, beyond scheduling order. Every test in an assembly runs in the same OS process, same runtime, same static/global state; xUnit's own guidance for avoiding cross-test corruption is simply "don't use statics." Named collections and fixtures buy mutual-exclusion (ordering), never memory or fault isolation — two collections running "in parallel" are two sets of threads sharing one heap.

**Crash handling.** The vstest test host that actually executes tests (`dotnet test` / `vstest.console`) is one OS process hosting many tests at once. A documented, unresolved class of issue: an unhandled exception anywhere — including on a background thread the test spawned — can crash that **entire host process**, aborting the whole run, not just failing the one test ("Test host process crashed", tracked across multiple long-open vstest issues). There is no online detection-and-recovery; the tooling is forensic and opt-in: `dotnet test --blame` records the run-order sequence so that, post-mortem, whichever test was executing when the host died can be inferred from the log, and can attach a crash dump.

**Hang handling.** No framework-level heartbeat or liveness check exists at all. The only mitigation is the same opt-in blame tooling, extended: `--blame-hang-timeout <duration>` triggers when a test exceeds the timeout, and its remedy is coarse — it **terminates the test host process and all of its child processes**, optionally capturing a dump first. There is no "kill just the hung test and keep the run going": the timeout's unit of remediation is the whole host, because there is no process boundary around a single test to kill instead.

### 7.2 What does NOT transfer to `Workers`, and why

`Workers` forks one OS process per task specifically to buy a hard fault boundary. Given that, most of §7.1's reliability apparatus is either irrelevant (because the problem it solves doesn't exist under process isolation) or exists on the .NET side only in a degraded, whole-run-granularity form that `Workers` should explicitly do better than, not imitate:

- **Orphan-process kill** has no analogue: xUnit/NUnit spawn no child worker processes to leak. The nearest .NET concept is vstest's own supervision of its single test-host process, which is orchestration-tool plumbing, not test-framework reliability logic — it says nothing about killing an orphaned *unit of work* because a unit of work was never its own process.
- **Mid-task crash recovery** doesn't exist as a concept on the .NET side, because a crash *is* a whole-run abort in the shared-host model — there is no "recover and keep running the other tests" path, since all tests share the one process that just died. `Workers` gets to make a crashed task a local, per-task event (kill and report that one forked worker, keep the pool running) precisely because it paid the fork cost that xUnit/NUnit did not; their cheaper common-case execution carries a strictly worse tail risk (total run loss) that `Workers` is designed to not have.
- **Hung-worker detection at task granularity** doesn't exist; `--blame-hang` is host-granularity, and even NUnit's per-test `[CancelAfter]` is best-effort cooperative cancellation with a documented failure mode (a test that never checks the token can never be stopped). `Workers` can wait on a task's declared `timeout` and hard-kill only the offending OS process, which is a strictly stronger guarantee than anything in either framework — it does not depend on the task's code cooperating.
- **Worker recycling** (periodically restarting a worker to bound memory/handle accumulation across many tasks) has no analogue: a vstest test host process typically lives for the duration of the entire run, with no "restart the host every K tests" concept, because doing so would mean re-loading the whole assembly and losing any in-process fixture state anyway.

The throughline: xUnit/NUnit's declarative *scheduling* vocabulary (locks, caps, fixture lifecycle) is solid prior art (§10). Their *reliability* posture is not something to imitate — it is a natural consequence of choosing threads over processes, and where it strains against that choice (`Thread.Abort()` removal forcing a switch to unenforceable cooperative cancellation; a single background-thread exception aborting an entire CI run; a hang timeout that can only kill everything) it is a concrete, cited argument *for* `Workers`' process-per-task design, not evidence against the extra cost of forking.

## 8. Results

Both report per-test-method pass/fail/duration regardless of which shift/collection ran it; parallel execution is invisible in the result shape, only visible in wall-clock time and (optionally) verbose runner logs that print thread/worker id.

## 9. Transport / protocol

N/A. Everything is in-process; there is no serialization boundary, no wire protocol, no worker handshake. This is exactly the layer `Workers` needs and .NET test parallelism has nothing to say about.

## 10. Lessons for `Workers`

| .NET concept | Maps to `Workers` |
|---|---|
| xUnit named `[Collection("X")]` | named `Task.locks` entry `"X"` — both are "these tasks are mutually exclusive with each other, and only each other" |
| xUnit `[CollectionDefinition(DisableParallelization=true)]` | a lock declared with capacity 1 (or our `exclusive` global lock, generalized: xUnit's global-serialize-everything drops out as the degenerate one-collection case) |
| xUnit `ICollectionFixture<T>` (shared context across a named group, created once, disposed after the group finishes) | our per-`Task.Queue` warm/shared worker state — same lifecycle: init before first member runs, teardown after last member finishes |
| xUnit `IClassFixture<T>` | shared state scoped to one task/queue rather than a whole named group |
| NUnit `[Parallelizable(scope)]` | per-`Task` `isolation`/weight declarations — an *eligibility* flag rather than a grouping; worth keeping distinct from `locks` rather than conflating the two, since NUnit itself keeps them as two different attributes |
| NUnit `LevelOfParallelism` / xUnit `MaxParallelThreads` | `Config(parallelism=N)` — confirms the two-tier shape (one global cap + per-task declarations) is the standard, proven design, not a novel one |
| NUnit shift model (parallel shift drains fully before non-parallel shift starts) | a cautionary example, not a pattern to copy: coarse "all non-parallel work is mutually exclusive with all other non-parallel work" is *less* expressive than named locks and would be a regression from xUnit's model |
| NUnit `TimeoutAttribute` → `CancelAfterAttribute` migration (forced by `Thread.Abort()` removal in .NET 5+) and `--blame-hang`'s whole-host kill | a cautionary example, not a pattern to copy: both are the in-process model hitting a wall it cannot get past — `Workers`' per-task `timeout` should hard-kill the one forked process, a guarantee neither framework can offer |

### Steal
- **xUnit's named-collection vocabulary is the closest existing prior art to our `locks` design** — validate `Task.locks: ["name1", "name2"]` against it directly: multiple named locks per task, a task belongs to the intersection of all of them, matches xUnit's (single) collection membership generalized to multiple simultaneous memberships.
- **Two-tier cap model** (global `MaxParallelThreads`/`LevelOfParallelism` as a hard ceiling, independent of which/how many tasks declare themselves eligible) is validated by both frameworks converging on the same shape — keep `Config(parallelism=N)` as a pure ceiling, never a target.
- **Fixture lifecycle (create-before-first-member, dispose-after-last-member) is the right model for warm/shared state** in a named `Task.Queue` — steal the lifecycle contract, not the DI mechanism.
- **Keep "parallel-eligible" (isolation/weight) and "mutually-exclusive-with" (locks) as separate declarations**, per NUnit's `[Parallelizable]` vs. xUnit's `[Collection]` being orthogonal attributes rather than one field — conflating them loses expressiveness.
- **A hard, process-level `timeout` kill is a real differentiator, not a nice-to-have**: NUnit's own migration history (forced off `Thread.Abort()`-based `[Timeout]` and onto cooperative-only `[CancelAfter]` by the .NET 5 runtime) is direct evidence that thread-level forced termination is not available on modern managed runtimes — `Workers` should make a point of guaranteeing what NUnit explicitly cannot: a task that never checks anything still gets killed on time.

### Avoid
- **Do not adopt NUnit's shift-drain model** (all parallel work must finish before any non-parallel work starts, and non-parallel work is globally serialized as one bucket) — it is coarser than named locks and would make `exclusive` swallow work that should only be exclusive with its own named group.
- **Do not copy the in-process/thread execution substrate** — both frameworks assume shared-memory safety is the test author's job; `Workers`' entire value proposition (fault isolation via forked processes) is the opposite bet, so nothing in their scheduler *implementation* (thread pools, apartment state) transfers, only the *declarative surface* does.
- **Do not copy vstest's whole-host failure/hang response** (one bad test crashes or force-kills the entire run) — it is a documented, still-open architectural limitation of the shared-process model, not a design choice worth reproducing; `Workers` should treat a crashed or hung task as contained to that one forked worker by construction.
