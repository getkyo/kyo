# kyo-workers: scheduling-restriction design

Design analysis for the per-task scheduling-restriction surface on `ForkQueue[R]` and the coordinator
pieces behind it. Scope: replace the `locks: Set[Lock]` mutex-set (an unconvincing abstraction) with
a proper one, ground every mechanism in the two initial consumers (kyo-compiler, kyo-test), and settle
the task-dependency question.

Current surface under review: `kyo-workers/shared/src/main/scala/kyo/ForkQueue.scala:48-64`
(`Options(locks, weight, fresh, timeout, retry, env, leakCheck)`, `opaque type Lock = String`).

---

## 1. What the two real consumers actually need

The rule for this doc: every mechanism kept below names a concrete need in kyo-compiler or kyo-test and
cites it. A mechanism no consumer needs is left out (section 6).

### 1.1 kyo-compiler needs: a global cap and per-worker seriality, nothing relational

kyo-compiler is a presentation-compiler pool (IDE language intelligence: `compile`, `completions`,
`hover`, `signatureHelp`, `symbol`, `didClose`), not a batch build
(`kyo-compiler/jvm/src/main/scala/kyo/Compiler.scala:35-57`, `:59`). It forks one worker JVM per
`Config` over aeron (`SpawnBackend.scala:75-125`, `:150-177`; each config gets distinct even/odd aeron
stream ids so "two configs' workers never cross-talk", `SpawnBackend.scala:10-13`, `:82-87`), and the
worker holds a warm `LocalBackend` presentation-compiler instance and serves requests until the host
kills it (`CompilerWorker.scala:12`, `WorkerServer.serve` "runs until the host kills the process"
`CompilerWorker.scala:83`).

Its entire scheduling model is three constraints, all already covered by `ForkQueue` without any
relational restriction:

1. **A global concurrency cap across all instances.** `globalSemaphore.run(...)` sized by
   `maxConcurrentCompiles` (`CompilerPool.scala:84`, `:182`). Test: "global compile cap: at most
   maxConcurrentCompiles ops run concurrently across all instances" (`CompilerPoolTest.scala:276`).
   Maps to the coordinator's `Config.parallelism` (`ForkQueue.scala:35`, `:41-45`).

2. **Per-instance serialization: one op at a time per config-instance.** "Ops serialize per instance
   (one at a time; even queries mutate lazy denotations)" (`Compiler.scala:19`), implemented as a
   per-instance mutex held inside the global semaphore (`CompilerPool.scala:84`, mutex created per
   instance at `:143`). Test: "per-instance serialization: two concurrent ops on the same instance
   never overlap", asserting max overlap 1 (`CompilerPoolTest.scala:210`, `:266`).

3. **One instance per config, created single-flight, LRU-evicted.** (`CompilerPool.scala:107-136`,
   `:181-203`; `CompilerPoolTest.scala:63`, `:110`.)

The load-bearing observation: constraint 2 is **not** a cross-task lock. It is the natural behavior of a
warm worker that runs one task at a time. Mapped onto `ForkQueue`, kyo-compiler is "one warm-resource
queue per config, `maxWorkers = 1`, warm `R` = the pc instance"; with one worker per config and that
worker single-inflight, ops for a config serialize for free, and different configs (different queues)
run concurrently under the global cap. The prior orchestrator sketch already reached this: kyo-compiler
is "one stateful queue per config, maxWorkers = 1 (warm pc + serialize), **no locks**"
(`kyo-test-orchestrator-design.md:96-101`).

**kyo-compiler contributes to the restriction design: nothing.** It needs no named mutex, no run-alone,
no task dependency. It needs the global cap (have it), warm-worker reuse with per-worker seriality
(a warm queue with `maxWorkers = 1` gives it), and a per-task stuck-timeout reclaim (`stuckTimeout`,
`CompilerPool.scala:81-102`; already `Options.timeout`). This is a strong negative result: it means the
relational surface is justified entirely by kyo-test, and it warns against over-building.

### 1.2 kyo-test needs: run-alone, named mutual exclusion (on the shared-host path)

_(Grounded from `kyo-test-orchestrator-design.md`; being sharpened with direct kyo-test source
file:line by a parallel source read, to be folded into this section.)_

kyo-test forks per-suite worker JVMs and runs suites in parallel under a global cap. Two genuine
relational needs surface:

- **Globally sequential suites (run alone against everything).** A suite flagged `globallySequential`
  maps to `.exclusiveWhen(s.globallySequential)` (`kyo-test-orchestrator-design.md:91`). The reason is
  process-global JVM state (system properties, global singletons, a shared clock) or a whole-machine
  need (a saturation/benchmark suite) that cannot tolerate any concurrent work. This is the
  "run alone across all pools" requirement.

- **Named mutual exclusion for a shared external resource.** Suites sharing a fixed port, a database,
  or a fixed filesystem path must not overlap, but unrelated suites may run: `.holding("port:8080")`
  (`kyo-test-orchestrator-design.md:105`). This is xUnit `[Collection]` exactly (same collection serial,
  different collections parallel; `prior-art/dotnet-parallel.md:14`, `:25`, `:118`) and K8s
  anti-affinity (`prior-art/cluster-schedulers.md:445`). Note it is the **shared-host (bare Process)**
  need: under container isolation each worker has its own network and filesystem namespace, so
  fixed-port suites can each bind the same port and the named locks "become unnecessary for
  containerized queues" (`kyo-test-orchestrator-design.md:418-421`). So named exclusion earns its place
  for the default Process path, not universally.

Two things kyo-test does **not** need from the restriction surface:

- **Fresh-worker isolation** (`needsFreshJvm` to `.isolatedWhen`, `:91`; browser suites fork per-suite
  because Chrome degrades across suites, `:245`) is already a distinct execution knob (`Options.fresh`,
  `ForkQueue.scala:51`), not a relational restriction. Keep it where it is.

- **Leak-check quiescence.** The leak check runs per fork over a JVM holding only that run's resources
  (`kyo-test-orchestrator-design.md:249-250`, citing `SbtRunner.scala:112-113`). Its leak kinds (fibers,
  threads, file descriptors, sockets; `ForkQueue.scala:122-127`) are all **per-process**. A warm worker
  is single-inflight, so a per-task or before-recycle scan attributes process state to exactly one task
  without any cross-pool coordination. Leak-check soundness is a worker-lifecycle/quiescence concern
  (already `Options.leakCheck`, `ForkQueue.scala:92-108`), **not** a scheduling restriction. It imposes
  no cross-pool constraint and stays off the restriction axis.

### 1.3 Ordering / dependencies in the consumers

- kyo-compiler: **none.** Presentation-compiler ops are independent per-config queries; `CompilerPool`
  has no dependency graph.
- kyo-test: suites are **mutually independent** (that is the premise of parallel test execution). The
  one real ordering, compile-before-test, is handled by the build tool (sbt) **above** kyo-workers;
  kyo-test does not submit a graph of inter-suite edges to the coordinator. See section 4.

---

## 2. The two axes (keep them separate)

Two orthogonal kinds of scheduling constraint, confirmed by every prior-art system and kept as separate
declarations (conflating them "loses expressiveness", `prior-art/dotnet-parallel.md:131`):

- **Quantitative admission (`weight`).** How much of the global parallelism budget a task consumes while
  running. A counting concern against one central budget.
- **Relational exclusion (the redesign target).** Which tasks may or may not run at the same time. A
  boolean-compatibility concern over named resources.

Both are **submit-time coordinator metadata**: the task body runs in a remote worker, so a restriction
must be plain serializable data in `Options`, evaluated by the coordinator before the task starts, never
an effect inside the computation. This is what makes the coordinator a scheduler (it decides admission
from a task's full declared demand) rather than a runtime lock protocol, and it is why the design below
is deadlock-free by construction (section 3.3).

### Axis A: `weight: Int` (keep as-is, clarify semantics)

Keep `weight` a scalar, default 1, **admission-only** (K8s "requests", never runtime enforcement;
`ForkQueue` has no enforcement layer). A weight-N task counts as N against `Config.parallelism` while
running. Concrete need: memory-heavy suites and compiles (the current CI hand-tunes "two 5GB forks plus
the driver fit the 16GB box"; weight can be RAM-denominated, `kyo-test-orchestrator-design.md:367`);
Ninja's canonical case is link steps that each eat gigabytes getting a tighter lane while many compiles
run (`prior-art/ninja-pools.md:32`). One addition to specify: a **starvation guard**, a task whose
weight exceeds the whole cap still runs (alone) rather than deadlocking (`prior-art/00-synthesis.md:53`).
Rejected refinements are in section 6.

### Axis B: relational exclusion (the redesign)

The rest of this document.

---

## 3. Recommended design: shared/exclusive access over named scopes

Replace `locks: Set[Lock]` with a typed access declaration over named scopes, arbitrated by the single
coordinator as one lock manager, with a reserved global scope that makes "run alone" correct by
construction.

### 3.1 The surface

```scala
// A named arbitration scope. Same name == the same scope across ALL pools under the coordinator,
// so exclusion is cross-pool by construction (the scope table lives in the coordinator, not a pool).
opaque type Scope = String
object Scope:
    def apply(name: String): Scope = name

// How a task accesses a scope while it runs.
enum Access derives CanEqual:
    case Shared    // compatible with other Shared holders of the same scope; conflicts with Exclusive
    case Exclusive // conflicts with every other holder (Shared or Exclusive) of the same scope

// One access grant a task must hold to run. The unit of relational restriction.
final case class Hold(scope: Scope, access: Access) derives CanEqual

object Hold:
    // Run alone against every task in every pool: take the reserved World scope exclusively.
    val exclusive: Hold                = Hold(Scope.World, Access.Exclusive)
    // Named mutual exclusion (the old `Lock`): exclusive access to a named resource.
    def mutex(name: String): Hold      = Hold(Scope(name), Access.Exclusive)
    // Shared access to a named resource (readers co-run; a writer takes it Exclusive). Offered for
    // free by the model; not required by the initial consumers (see 3.5).
    def shared(name: String): Hold     = Hold(Scope(name), Access.Shared)
```

`Options` changes: `locks: Set[Lock]` becomes `holds: Set[Hold]`. `weight` is unchanged. The other
fields (`fresh`, `timeout`, `retry`, `env`, `leakCheck`) are execution options, not restrictions, and
are untouched.

```scala
final case class Options(
    holds: Set[Hold],         // replaces `locks`: relational exclusion, shared/exclusive over scopes
    weight: Int,              // unchanged: admission cost against the global cap
    fresh: Boolean,
    timeout: Maybe[Duration],
    retry: Maybe[Schedule],
    env: Map[String, String],
    leakCheck: LeakCheck
)
```

Compatibility rule (the whole semantics in one line): two running tasks conflict iff they both hold the
same `Scope` and at least one holds it `Exclusive`.

### 3.2 The reserved World scope makes "run alone" correct by construction

`Scope.World` is a coordinator-reserved scope (not user-constructible by name). The coordinator enforces
one invariant:

> **Every admitted task implicitly holds `Hold(World, Shared)`, unless it declares `Hold.exclusive`, in
> which case it holds `Hold(World, Exclusive)`.**

Because Exclusive conflicts with any other holder, and every ordinary task is a World-Shared holder, an
exclusive task is admissible only when no other task runs in any pool, and while it runs no other task
can be admitted. It runs strictly alone across all pools. This is the read-write barrier the prior work
flagged as the correct shape (`kyo-test-orchestrator-design.md:303-307`, open question 4 at `:448`;
`prior-art/00-synthesis.md:60-68`), and it does not have the trap the same notes warn about: because
ordinary tasks **do** hold World (shared), an exclusive task excludes them, unlike a naive "a lock only
exclusive tasks hold" (Ninja's `console` "exclusive" that lets unrelated work keep running,
`prior-art/ninja-pools.md:61`; NUnit's coarser global non-parallel bucket, `prior-art/dotnet-parallel.md:76`).

So the two cited kyo-test intents and the free third one all reduce to one primitive:

| Intent | Declaration | Mechanism |
|--------|-------------|-----------|
| Run alone across all pools (`globallySequential`) | `Hold.exclusive` | Exclusive on World; every other task holds World Shared |
| Named mutual exclusion (`port:8080`, shared db/file) | `Hold.mutex("port:8080")` | Exclusive on a named scope; unrelated tasks unaffected |
| Shared readers + exclusive writer on a fixture (free, uncited) | `Hold.shared("fx")` / `Hold(Scope("fx"), Exclusive)` | Shared co-run; a writer takes it Exclusive |

### 3.3 Coordinator mechanics (single enforcement point, deadlock-free)

Because holds are static submit-time data, the coordinator schedules by **admission**, not by a runtime
lock acquisition protocol:

- The coordinator keeps, per scope, the multiset of accesses held by currently-running tasks. World is
  included, with every non-exclusive running task contributing a Shared hold.
- A ready task is admissible iff **both**: (a) its `weight` fits the remaining global budget, or the
  starvation guard applies; and (b) every `Hold` it declares (plus its implicit World hold) is
  compatible with the current holders of that scope.
- On admission the coordinator adds all of the task's holds atomically; on completion it removes them.

Atomic all-or-nothing admission is the key property: a task never holds some scopes while waiting on
others, so there is no hold-and-wait and therefore no lock-ordering deadlock, the failure mode a runtime
multi-lock acquire would invite. This falls out of "restrictions are data evaluated before the task
starts", not effects run inside it. It is Bazel-style resource bin-packing (`prior-art/00-synthesis.md:11`)
plus a compatibility check.

Fairness (avoid starving an exclusive holder): apply writer preference per scope. Once an Exclusive-
waiting task is the oldest waiter on a scope, stop admitting new tasks that would take that scope
Shared, let the current Shared holders of that scope drain, then admit the exclusive one. For World this
is a global drain; for a named scope it drains only that scope, so a named-exclusive never over-
serializes the whole run (the NUnit anti-pattern, `prior-art/dotnet-parallel.md:76`, `:124`).

### 3.4 Why this satisfies "globally exclusive across multiple pools, one coordinator"

Requirements 1 and 2 (run-alone across all pools; cross-pool restrictions with a single enforcement
point) are met **by construction**, not by a bolt-on:

- The scope table lives in the process-global coordinator, not in any pool. `Scope("db")` declared by a
  task in pool A and by a task in pool B is the same key, so they exclude each other across pools. There
  is nothing per-pool to keep in sync.
- World is a coordinator scope, so `Hold.exclusive` drains and excludes tasks in **every** pool. A
  single lock manager arbitrates all pools; a pool is just a worker source (a launch spec + `maxWorkers`
  cap + optional warm `R`), never a restriction boundary.

### 3.5 The one honest generality call: `Access.Shared` on named scopes

`Access.Shared` is load-bearing for World (it is how run-alone works). Exposing it on **named** scopes
(`Hold.shared`) then costs nothing: the compatibility rule is uniform, so the reader/writer case (many
suites read a shared read-only fixture; one suite rebuilds it) falls out with no extra mechanism. But no
initial consumer cites it. Two defensible positions:

- **Recommended: keep it exposed.** It is the same code path, not speculative machinery; it makes the
  model fully uniform and teachable ("a task declares a set of `(scope, access)` holds; it runs when all
  are compatible; World is the reserved everyone-shares scope"); and it future-proofs the one real RW
  case without a later surface change.
- **Minimalist alternative:** make `Access` `private[kyo]` and expose only `Hold.exclusive` and
  `Hold.mutex`, covering 100% of cited needs. The internal manager stays RW (World needs it); only the
  named-Shared constructor is withheld.

I recommend the exposed form and flag this as the single piece of surface not driven by a named consumer
need, so the choice is explicit.

---

## 4. Task dependencies: OUT for v1

The verdict the brief asked for, weighed honestly rather than dismissed.

**What the consumers show.** kyo-compiler has zero task-to-task dependencies (1.1, 1.3). kyo-test's
suites are mutually independent by design; its only ordering, compile-before-test, is enforced by sbt
above kyo-workers and never submitted to the coordinator as task edges (1.3). Neither initial consumer
submits a task DAG.

**What fiber composition already gives (the honest bar to beat).** `fork` returns a
`Fiber[Outcome[...], ...]` in the coordinator JVM (`ForkQueue.scala:10-13`), so the coordinator-side
caller sequences with plain effect composition: `f1.get.andThen(pool.fork(t2))`. This is the kyo model
(sequencing is value composition), and crucially it does **not** hold a worker slot while blocked: `t2`
is not forked until `f1` completes, so nothing occupies a permit during the wait. The main efficiency
argument usually made for an explicit dependency ("don't hold a slot while waiting on a predecessor") is
therefore already satisfied.

**What an explicit dependency mechanism would add beyond composition.** Only one thing: the coordinator
seeing the whole graph **upfront**, enabling critical-path / topological scheduling (run the task that
unblocks the most remaining work first). This is real, and it is exactly Ninja's one graph-derived
priority (longest remaining weighted path, `prior-art/ninja-pools.md:60`), but Ninja is a build system
whose input **is** a DAG. No kyo-workers consumer submits one. The secondary benefits (declarative batch
submission, cycle detection) are minor: batch submission is expressible with `Kyo.foreach`/`Async`
combinators, and a cycle in composition is a local self-inflicted deadlock, not a class of bug worth a
whole graph type to detect.

**The cost of adding it.** A dependency edge needs stable, serializable task identities the coordinator
indexes (fork currently hands back a `Fiber`, not a scheduler-visible id), plus a graph scheduler. It
also introduces a **second** way to express sequencing that competes with fiber composition, which in a
value-oriented effect system is a real downside (two idioms for one job).

**Verdict: OUT.** It does not pay its weight for either initial consumer, and composition covers the
sequencing that actually exists with better slot behavior. **Minimal form if a future consumer is a
genuine build DAG** (kyo-workers driving inter-module `compile` tasks where critical-path ordering
matters): not a graph type in `Options`, but a `fork(..., after: Set[Fiber[...]])` that lets the
coordinator defer admission until the named fibers complete without the caller blocking. Even that is
redundant with composition today; revisit only when critical-path scheduling has a named consumer.

---

## 5. Alternatives considered

The framings the brief sketched (A-D), judged on merit:

- **(A) Typed `Restriction` ADT `{Exclusive, Mutex(group)}` + scalar `weight`.** Closest to the
  recommendation. The design here **refines** it: (i) it unifies `Exclusive` and `Mutex` as
  Exclusive-access at the World scope vs a named scope on one lock manager, so run-alone is the same
  primitive as a mutex rather than a second concept, and it is correct by construction (the World-Shared
  invariant) instead of the underspecified "exclusive holds a lock" that fails to exclude ordinary
  tasks; (ii) it makes the access mode explicit (`Shared`/`Exclusive`), which is what lets the World
  barrier be legible rather than magic and gives the free RW-fixture case. Adopt, refined.

- **(B) Named weighted semaphores (one permit primitive + a per-scope capacity registry).** Rejected.
  Per-scope capacities are per-task-visible variable capacity, which invites inconsistency (the same
  scope given different capacities by different submitters); a configurable cap belongs centrally. The
  only counted budget that needs to exist is the one global `parallelism` cap; relational exclusion is
  boolean compatibility (shared vs exclusive), not counting. Semaphores with capacity greater than one
  solve a problem (N-at-a-time on a named resource) no consumer has.

- **(C) Tags + central coordinator policy rules.** Rejected. A rules engine mapping tags to scheduling
  behavior is a policy DSL with no consumer; the two concrete intents (alone, mutex) are expressible
  directly and legibly without it. Revisit only if a routing story (Celery's declaration-vs-routing
  split) ever becomes real, which is a pool-routing concern, not a per-task restriction.

- **(D) Typed resource requests (vectors of typed capacities, Slurm `--gres`).** Rejected as
  over-engineered, matching the brief's own read and `prior-art/00-synthesis.md:51`. No consumer needs a
  vector of typed capacities; a scalar admission `weight` plus a set of named exclusion scopes covers
  every cited need. Keep weight and exclusion as separate, simple declarations rather than one resource
  algebra.

---

## 6. Left out, and why

- **Task dependencies / a submitted DAG.** Section 4: fiber composition covers the sequencing that
  exists (with no slot held while blocked); only critical-path scheduling would be new, and no consumer
  needs it. Minimal `after:` form named for the future.

- **Affinity / co-location (pytest `xdist_group`, jest `computeWorkerKey`).** "Run these on the same
  worker" (`prior-art/pytest-xdist.md:28`, `prior-art/worker-pools.md:17`) is the **opposite** of
  exclusion and is a **pool/routing** concern, not a per-task restriction: a pool is already a set of
  like workers holding a warm `R`, and routing same-config work to the same warm worker (kyo-compiler's
  actual need) is pool routing, not a `Hold`. Kept off the restriction surface.

- **Priority.** No consumer orders tasks by importance; ordering is admissibility plus arrival, with the
  writer-preference drain for fairness. Declared priority invites starvation and needs a policy no
  consumer defines. (Ninja's only priority is graph-derived, not declared, `prior-art/ninja-pools.md:60`.)

- **Per-task variable capacity / weighted semaphores.** Section 5(B): the one counted budget is the
  global cap; exclusion is boolean.

- **Typed resource vectors.** Section 5(D).

- **Rate limiting / per-pool throughput quotas.** No consumer needs throughput shaping; global cap plus
  `weight` is the only admission control.

- **Leak-check as a restriction.** Section 1.2: per-process leak kinds plus single-inflight warm workers
  make the scan sound without cross-pool coordination; it stays `Options.leakCheck`.

- **`fresh` / isolation as a restriction.** Already a separate execution knob (`Options.fresh`); it is
  about worker recycling, not inter-task exclusion.

---

## 7. Summary of the recommendation

- **Keep `weight: Int`** (scalar, admission-only, default 1, plus a starvation guard). Axis A, unchanged.
- **Replace `locks: Set[Lock]` with `holds: Set[Hold]`**, where `Hold = (Scope, Access)` and
  `Access = Shared | Exclusive`. Axis B, one primitive.
- **Reserve `Scope.World`**; every task implicitly holds it Shared, `Hold.exclusive` holds it Exclusive.
  This is the read-write barrier that makes globally-exclusive correct across all pools by construction.
- **Constructors** `Hold.exclusive`, `Hold.mutex(name)`, `Hold.shared(name)` cover run-alone, named
  mutual exclusion, and the free reader/writer case; the first two are the cited consumer needs.
- **Coordinator** is the single lock manager: admits a task atomically when its weight and all its holds
  are simultaneously satisfiable, with writer-preference draining. Deadlock-free because restrictions are
  submit-time data, not runtime lock acquisition.
- **Task dependencies: out.** Fiber composition already sequences without holding a slot; only unneeded
  critical-path scheduling would be gained.
- **kyo-compiler drives none of the relational surface**; it needs the global cap plus a warm
  `maxWorkers = 1` queue. All relational mechanism is justified by kyo-test, and only two constructors
  (`exclusive`, `mutex`) are consumer-cited.
