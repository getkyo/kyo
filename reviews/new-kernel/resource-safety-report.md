# Resource safety audit: the Arrow kernel and the stack built on it

Branch `worktree-effervescent-painting-backus`, tree as read on 2026-09-18 (other agents were editing concurrently; the kernel files `Eval.scala`, `Handler.scala`, `Stack.scala` and `Bracket.scala` carried uncommitted comment-only edits, which this report ignores). Analysis only: nothing was built or run. Every claim below was checked against the source, and every kernel rule was traced through `Eval`, `Stack`, `Bracket`, `Arrow`, `Isolate` and `IOTask` rather than taken from the prose.

The audit asks one question everywhere: is every resource released exactly once, on every ending (clean end, throw, typed abort, interrupt of a parked fiber, discard by a handler that never resumes, fatal), never twice and never against a live use.

## 1. Summary of findings by severity

Severity scale:

- **S1**: a real leak, double release, or release against a live use, on a path that ordinary API use reaches with plausible timing (a join that lasts a network round trip, a per-request path under a timeout).
- **S2**: real, but the interrupt has to land inside one producing step or one specific join; the window is the duration of that step (wide for a process spawn, a blocking connect or a server round trip; nanoseconds for an in-memory step).
- **S3**: fragile: holds today by ordering, by a guard that covers a narrower case than its comment claims, or only while a documented usage rule is obeyed, with a failure mode other than the documented one when it is not.
- **S4**: documentation states a rule the code does not implement.

| Id | Area | Finding | Severity |
|----|------|---------|----------|
| K1 | kernel: peels | A single-shot peeled remainder resumed on another fiber while the peeling scope is still open is released under the live resumption when that scope exits; the `reenter` refusal only guards entry, not a resumption already running | S3 |
| K2 | kernel: Bracket | `Bracket.ensuring` and `ensuringWith` mint a fresh exactly-once cell per `derive`, so exactly-once is per abandonment walk, not per node; only `IOTask.abandon` walks today and it walks once, which is what keeps `Sync.ensure` from firing twice | S3 |
| K3 | core: Sync.ensure, acquireReleaseWith | The trailing `.map(result => Abort.get(result))` polls after the region's clean release, so a guard that hands its value on at a clean end (releases nothing, expects the next step to own it) has an unowned window that the caller's `ensureMap` cannot close | S2 |
| C1 | core: Scope.run | A clean `Scope.run` exit awaits its drain fiber on a join; the body's value crosses that join, so a caller that registers the value's release after `Scope.run` returns is exposed at every clean exit | S2 |
| C2 | core: spawn then map | `Fiber.initUnscoped(...).map { fiber => register }` and the same shape over `Channel.Unsafe.init`, `listeners.add`, `unsafe.spawn()` and `pool.close()` leave the produced handle unowned when the stop lands during the producing step: Hub, KyoApp.runAndBlock, AsyncCombinators, Constructors.async, Async.uninterruptible, StreamCoreExtensions (collectAll, collectAllHalting, merge, mapPar family, groupedWithin), Hub.listen, compat CIO | S2 |
| C3 | core: Channel.take | The parked-take handoff covers the value only until `f` runs; `take` (f = identity) hands a resource-carrying element to a continuation the next poll can abandon, so consumers that take fiber handles (`mapPar` and siblings) orphan the fiber | S2 |
| C4 | core: Async.uninterruptible | The masked promise refuses the join link, so an interrupt at `_.get` strands the shielded fiber's value; and the leading `.map` orphans the fiber outright | S2 |
| C5 | core: Channel.close, Queue.close | Buffered elements cross a join and are discarded on interrupt; documented on Channel, undocumented on Queue | S3 |
| C6 | core: Async.timeout | The sleep timer is armed before a park point that precedes the spawn; an interrupt there leaves a timer nobody cancels until it expires (no external resource, informational) | S3 |
| D1 | kyo-sql: closeAll | `pool.close()` extracts the idle ring, then `.flatMap` polls before the `Sync.ensure` force-close installs; an interrupt in that window strands every idle connection with the pool already closed | S2 |
| D2 | kyo-sql: custody handover | `Scope.ensure(decideExit)` then `custody.take()` one step later: a stop between them makes decideExit pool the connection and the orphan finalizer close it | S3 |
| D3 | kyo-sql: SqlClient.openScoped, Runtime.init | The warmed pool crosses the inner `Scope.run`'s drain join and then a `.flatMap` before `Scope.ensure(client.close)` registers; an interrupt at either strands a pool with `minConnections` live sockets | S2 |
| D4 | kyo-sql: lockedOn | The advisory lock is granted by the server before the reply is awaited; `Scope.ensure(release)` registers only in the continuation, and session-scoped locks survive the reclaim's ROLLBACK, so the lock rides the pooled session to its next borrower | S1 |
| D5 | kyo-browser: launcher | Chrome is spawned unscoped and registered one poll later; a stop during the fork/exec orphans the process tree | S2 |
| D6 | kyo-browser: CdpBackend.init | The dialog drainer fiber is spawned mid `initUnscoped` and owned only by the record's `close`, registered after the `getVersion` probe join; an interrupt at the probe orphans it | S2 |
| D7 | kyo-browser: contexts and targets | `createBrowserContext`, `createTarget`, popup detection: Chrome mints the object before the reply; the dispose or close is registered in the next step | S1 |
| D8 | kyo-browser: page-state acquires | `Scope.acquireRelease(<CDP round trip>)` for freeze styles, marks, viewport, emulated media, background, download policy: the side effect lands before the reply, the restore registers after | S2 |
| D9 | kyo-aeron: Topic | The add-deadline guard hands the publication or subscription on at a clean end; K3's window sits between that hand-off and Topic's `ensureMap`, so an interrupt there strands the Aeron object | S2 |
| D10 | kyo-aeron: AeronPlatformTransport | `driverStart` and `clientConnect` are blocking FFI joins with the runtime (and its `close`) built after; an interrupt at the join leaves a native client or driver with no Scala object able to close it | S1 |
| D11 | kyo-http: client connect paths | `connect` completes a promise the caller joins; the producer's handoff guard covers only the interrupt-first ordering, so a connection delivered and then abandoned before `trackConn`/`releasingConn` run leaks its socket; same for WebSocket, raw, `connectWith`, and the server's listen socket | S1 |
| D12 | kyo-http: fiber trios | WebSocket read/monitor/write and the chunked decoder are spawned unscoped with the interrupt registration after the last spawn | S2 |
| D13 | kyo-jsonrpc: stdio, UDS | `stdio().safe.get.map { Scope.ensure }` leaks the process-wide stdio claim (every later `contentLengthStdio` fails forever); `listenUnix(...).safe.get.map { Scope.ensure }` leaks the listener and the socket file | S1 |
| D14 | kyo-jsonrpc: engine | Handler fiber and `pendingInbound` entry, writer fiber, Exchange, writer channel, progress monitor and call fiber are each registered one `.map` after their spawn | S2 |
| D15 | kyo-actor: PubSub | `actor.ask(Subscribe)` join then `Scope.ensure(Unsubscribe)`: a subscriber left in the set blocks every later `publish` on a mailbox nobody drains | S1 |
| D16 | kyo-compiler: SpawnBackend | The worker JVM is spawned, the kill is armed after `aeronClient(driver)` parks; the comment claims coverage the ordering does not give | S1 |
| D17 | kyo-flow: superviseDetached | Detached supervision fiber registered one `.map` after the spawn | S2 |
| D18 | kyo-system: Command.spawn | `unsafe.spawn()` then `.map { Scope.acquireRelease }`: a stop during fork/exec orphans the process | S2 |
| D19 | kyo-caliban, kyo-zio | `ZIOs.get` strands a value the ZIO fiber already produced; Resolvers' queue and pipe, ZStreams' channel and producer are registered after the join or the spawn | S2 |
| DOC1 | kernel docs | `kyo-kernel/CONTRIBUTING.md` line 160 describes a budgeted `Eval.release` overload with `leftmost` and `ensuring` that offers a settled value to a waiting `Ensure`, and cites two `EvalTest` leaves; none exist. The code deliberately does the opposite (commit `c8b9735e16`: a stopped acquire owes nothing) | S4 |

Verified and holding, with the evidence in section 2: Bracket exactly-once and re-entry refusal, the release and abandonment walk, `ensureMap`, `Stack.dump` and the owed-remainder lanes, unwinding, Isolate crossings, `Scope` (finalizer close, children, forks, closed-scope registration, #1928), `Fiber`/`IOTask` (interrupt, abandon, link-before-release, settle-after-release, uninterruptible promises), `Channel.parkedTake`, `Meter`, `Fiber.init`, `Fiber.use`, `Async.timeout`'s spawn wiring, kyo-sql's slot permit and connection custody, kyo-sql reclaim, kyo-net `ConnectionPool` ring and reaper.

## Review notes

Findings were re-checked against the tree and, where a deterministic leaf could be written without production changes, turned into tests; the results are recorded here.

- K3 (`Sync.ensure` polls after the region's clean release): reproduced. `SyncTest`, "a caller's ensureMap after the region runs when the interrupt lands as the region ends", pending. Deterministic on every platform through the finalizer's self-interrupt.
- C1 (`Scope.run`'s clean exit awaits its drain with the value in flight): reproduced. `ScopeInterruptTest`, "an interrupt at Scope.run's drain await does not strand the value the body produced", pending.
- C2, `Kyo.async` instance: reproduced. `AsyncCombinatorsTest`, "interrupting the caller of async interrupts the effect it registered", pending. The `&>`, `<&` and `<&>` instances could not be written as a leaf: the extensions' isolate inference folds `Async` into the receiver's `S` for any computation whose row names `Async`, so they fail to compile against such computations; a usability defect of its own, since the orphaning only matters for computations that park.
- C2 and C3, `mapPar`: not reproduced. `StreamCoreExtensionsTest`, "mapPar interrupted with element fibers buffered interrupts every element fiber", is green: with one element fiber joined and the rest buffered, all four are interrupted with the consumer. The window in the finding, a stop between the take and the join's link, is narrower than a leaf can land on without a seam.
- C4 (`Async.uninterruptible`): a usage contract rather than a defect, since nothing can release an arbitrary value delivered to a masked promise. Pinned as such: `AsyncTest`, "interrupting the caller of uninterruptible runs the caller's finalizer while the shielded body completes".
- C5: `Queue.close` does document the interrupted close (`Queue.scala:129-131`), in the same words as `Channel.close`. No gap.
- D13, stdio half: not observable in-process, because the stdio claim is by design never released (`NioTransport.scala:172-176`: exactly one stdio per process), so a leaked claim and a correctly closed one look alike. The UDS half is reproduced: `JsonRpcTransportUnixTest`, "an interrupt landing as the listener binds leaves no listener or socket file behind", pending. An interrupt requested as the fiber starts leaves the socket file, and the listener holding it, behind; a later bind on the path fails with `NetBindException`.
- D15 (PubSub): not reproduced. `PubSubTest`, "a subscriber interrupted at the subscribe reply is not left in the set", samples the window in 200 rounds and stays green; the interrupt requested as soon as the actor reports the subscriber lands after the subscriber's fiber has resumed. The finding stands by reading; a seam on the reply promise would be needed to land the interrupt inside the window.
- D18 (`Command.spawn`): reproduced. `CommandTest`, "an interrupt landing during spawn does not orphan the process", pending: 8 of 40 rounds, interrupted at staggered delays around the fork, left a `sleep` process behind (killed by the leaf afterwards).
- DOC1: fixed in `kyo-kernel/CONTRIBUTING.md`, rewritten to the current walk.
- T6 (`KyoApp.runAndBlock`): reproduced. `KyoAppTest`, "runAndBlock's timeout does not leave the forked computation running", pending: the forked fiber stays parked on its gate after the block reported `Timeout`.
- T10 (`Hub.listen`): not reproduced. `HubTest`, "a listener whose registration is abandoned is not left in the set", samples the poll between the add and the registration in 100 rounds and stays green; the probe through a live listener would stall on a leaked one-slot listener.
- T5 (`Hub.initUnscopedWith`): no leaf. An orphaned publisher fiber parks on a channel nothing else references, so nothing in the API can observe it.
- T12 (`Channel.takeWith`): pinned green. `ChannelTest`, "takeWith registers a release for the element it delivers under a pending interrupt": in 100 rounds every delivered element was released, and the rest were handed back to the channel.
- T27: pinned green. `FiberTest`, "a fatal thrown in the body releases the fiber's finalizers before the promise settles with the panic".
- T2 (K2): not reproduced. `EvalTest`, "a double abandonment reaches an ensuring region stopped in its first step once": the region is entered before its body's first step, so the park carries the entered cell and the second walk finds it run. The per-walk shape the finding describes needs a region the walk reaches unentered, which the raw-hook leaf beside it shows for a plain context region.
- T1 (K1), core companion: not reproduced. `ScopeInterruptTest`, "a peeled remainder running on a child fiber is not released under it when the peeling scope ends", is green: an `Emit.runFirst` remainder holding a `Sync.ensure` region, handed to a child fiber and parked inside its use while the peeling `Scope.run` ends, completes with the resource still held. The kernel-level shape in the spec (a nested `Eval.partial` on one thread) was not written.
- D11, server half: not a defect. `HttpServer.init` wraps the join in `Scope.acquireRelease`, which registers through `ensureMap` in the step the value arrives (`Scope.scala`, the comment on `acquireRelease`), so no poll separates the listener from its release. `HttpServerTest`, "an interrupt landing as the listener binds leaves no listener behind", pins it green over 40 rounds. The client half (`poolWithImpl`, `connectWebSocket`, `connectRaw`) has no leaf: a leaked client connection is observable from neither side without a seam.
- D3 (`Runtime.init` warm-up handover): not reproduced. `SqlClientInterruptTest` (kyo-sql-postgres, real container), "an interrupt landing as the warmed pool is handed over strands no session", 30 rounds interrupting at 0 to 29 ms, every session gone from `pg_stat_activity` within the bound.
- D4 (advisory lock): not reproduced. Same suite, "an interrupt landing as the advisory lock is granted strands no lock", 40 rounds, `pg_locks` clear after each. The window is the one park between the grant's reply and the registration; the rounds sample it, a seam on the reply would land in it.
- D16 (`SpawnBackend.init`): reproduced. `SpawnBackendTest`, "an interrupt landing before the kill is armed does not orphan the worker JVM", pending: an interrupt 4 ms after the init started left a worker JVM running past a 10 s bound (the leaf kills it afterwards).
- D1, D2, D5 to D8, D9, D10, D12, D14, D17: no leaf. Each window is one park inside code with no observation point or seam (a ring drain, a custody take, Chrome and CDP replies, aeron native handles, http fiber trios, the jsonrpc engine, the flow poll loop); the report keeps them as findings by reading.

## Why the kyo-test leak check does not catch these

The end-of-run probes live in `kyo-test/runner/jvm/src/main/scala/kyo/test/runner/internal/LeakCheck.scala` and
`StrandedOpCheck.scala`, wired from `SbtRunner.runEndOfRunChecks`. What they can see, and where each finding above falls:

- **When and where they run.** Once per forked test JVM, at sbt's `done()` after every suite in the fork has finished, and only in a
  fork. They are JVM-only: the JS and Native legs have no probe at all. The descriptor probe reads `/proc/self/fd`, so it is a no-op
  on macOS and Windows; on this machine every local run is unprobed, and only the Linux CI legs carry it.
- **What they sample.** Three process-global resources: open descriptors (diffed against a baseline taken at construction, benign and
  allowlisted targets removed, re-sampled for up to 30s so a close still in flight drains), the scheduler (`loadAvg`, busy workers), and
  non-daemon threads. `StrandedOpCheck` adds a per-driver lost-wakeup classifier from kyo-net's `Diagnostics` (a loop whose pending work
  survives with a frozen cycle counter).
- **Parked fibers are invisible by design.** The fiber probe reports a scheduler that stays busy, that is a fiber running or repeatedly
  rescheduling. A fiber parked on a promise, latch, or channel is off-scheduler; the header of `LeakCheck` says so ("catching that
  would need a core registry"). This is the class most of the findings fall in: the `Kyo.async` orphan (C2), `runAndBlock`'s forked
  computation (C2, T6), `Hub`'s publisher and a listener left in its set (C2, T5 and T10), `PubSub`'s subscriber (D15), the pool permit
  stranded by the stream lease, the abandoned drain in the `take` finalizer, and the values `Scope.run` (C1) and `Sync.ensure` (K3)
  hand to nobody. None of them holds a descriptor or a thread, so the fork looks clean.
- **In-memory and server-side state is not a probe surface.** A registration missing from a set, a slot never given back, an advisory
  lock left on a pooled session (D4) or warmed sessions on the server (D3) are visible only to a test that reads that state, which is what
  the leaves added here do.
- **The descriptor-visible leaks never happened in the suite.** A listener nobody registered (D11, D13), a child process abandoned during
  its fork (D18), or an aeron client abandoned at its connect join (D10, D16) does hold descriptors, and the Linux probe would report
  them. Each needs a stop delivered inside a window one park wide, between the step that produces the resource and the step that
  registers its release. No existing leaf interrupted inside such a window, so the fork ended with nothing to report. The leaves added
  here are the first to land there, which is also why they must be run under the Linux probe before the branch goes to CI: a pending leaf
  whose body leaks a listener trips the fork-wide descriptor check in that module's JVM leg, regardless of its own pending status. A
  per-suite `leakCheckSockets(false)` does not help: each category is enabled fork-wide when any suite in the fork enables it.
- **A note on the runner's comments.** `SbtRunner` and `LeakCheckTest` say `BaseHttpTest` disables the socket category. It does not:
  no suite in kyo-http, kyo-net, kyo-jsonrpc, or kyo-system overrides `leakCheckSockets`, so those forks check sockets on Linux. The
  suites that do exempt sockets are kyo-browser, kyo-ui, kyo-pod, kyo-flow's `FlowApiTest`, kyo-slack's live suites, and kyo-stats-otlp.
- **The check reads the wrong moment for a window this narrow.** Even for a descriptor leak, one sample at the end of the fork cannot say
  which leaf leaked; `KYO_TEST_LEAK_DEBUG=1` runs leaves serially and attributes descriptors to leaves, which is a debugging mode, not the
  CI configuration.

## 2. Kernel rules, verified against the code

Each rule is stated as the docs state it, then where the code does it and which leaf pins it.

**A release runs exactly once.** `Bracket.Cell.Live.run` is a `compareAndSet(false, true)` with the flag set before `fin` runs (`Bracket.scala:52-60`), and the region's own release is one `Stack.OwnRelease` entry in exactly one lane at a time (`Stack.scala:103-105, 369-374`; `Eval.scala:343-344` installs it at region entry). Pinned: `BracketTest` "releases exactly once", "a release that throws on completion runs exactly once", "a double abandonment reaches each bracket once" (`EvalTest:1247`). Qualification in K2 for `ensuring`/`ensuringWith`.

**The release is told how the extent ended, never what it produced.** `Cell.Live.run` forwards `Absent` only if `complete()` recorded a clean end, else the discard signal (`Bracket.scala:56-59`); `complete` is called at the settled arm before the pop (`Eval.scala:377`) and by nothing else. Pinned: "a discarded capture's bracket is told the discard signal", "a multi-shot capture ... releases once".

**No gap between acquiring and owing.** `Bracket.apply` is `ensure(Effect.defer(acquire))` (`Bracket.scala:96`); `Arrow.Ensure.apply` omits the safepoint arm (`Arrow.scala:224-229`), and the `HandleContext` arm pushes with no stop check (`Eval.scala:329-345`). I traced every path from the acquire's last settled step to the push: `Id.apply`, `Step.apply` (which polls before `f`, so a stop there parks in front of the acquire's step, not after it), `Ensure.apply`. Pinned: `BracketTest` "a stop landing as the acquire settles still installs the region", `EvalTest` "a stop landing on the acquire's last step hands the value to the bracket before parking" (:1145, :1181), `ScopeInterruptTest` "Sync.acquireReleaseWith still releases what the acquire produced". A multi-step acquire has its own internal gaps; that is the caller's contract and the whole of section 4.

**The abandonment walk runs nothing.** `Eval.release` descends a `Defer` only while its value is still a computation and stops at a settled one (`Eval.scala:682-688`); it collects `HandleContext` releases via `derive(Absent)`, `Park` entries and lanes, and reports the first `Join` it stands at (`Eval.scala:690-726`). Pinned: `EvalTest` "an acquire the park stopped in front of is neither run nor released on abandonment" (:1211), `BracketTest` "a bracket whose acquire is interrupted before it finishes owns nothing", `ScopeInterruptTest` "an acquire abandoned before it resumed with its value owns nothing" and the fiber-join variant (:186-227).

**Failure unwinds on one walk, releases before recoveries.** `Eval.recovered` drains a context region's own release and its owed remainders, then pops; for an arrow region it drains what the region held before `onRecover` is consulted (`Eval.scala:542-584`); a throwing release is suppressed onto the failure (`Eval.scala:643-647`). Pinned: `BracketTest` "a bracket under a recovering handler releases with the failure, before the recovery", "a throwing release on the unwind does not starve the ones after it".

**A dump moves releases to the holder; an escaping peel keeps them and owes the snapshot below.** `Stack.dump` (`Stack.scala:292-328`), the `FirstHandler` arm owing the snapshot to the region below the peel (`Eval.scala:148-152`), `Stack.settle` removing it by identity when the remainder resumes (`Stack.scala:152-162`, called from `Eval.installed:468`), and `drainRemainders` draining what was never resumed (`Eval.scala:501-516`, called at every region end and eval end). Pinned: the `BracketTest` "multi-shot clauses" group (:1455-1790), `EvalTest` "sibling dumps drain newest first at the owner's exit". Qualification in K1.

**Re-entering a released region is refused.** `Eval.installed` asks every entry's `reenter` before any state changes (`Eval.scala:459-465`); `Bracket`'s refuses once the cell has fired (`Bracket.scala:141-163`). Pinned: "a leaked capture resumed after its region was released is refused as closed", "a park evaluated twice refuses the second evaluation after release".

**A bracket does not cross into an isolated child.** `Contextual.fork` wraps each context handler in `Forked` and forks its state (`Isolate.scala:317-328`); `Bracket.fork` answers `Cell.inert` (`Bracket.scala:133`); the forked snapshot carries no releases (`Stack.Snapshot.Builder.add` stores `null`, `Stack.scala:470-476`), so `installed` owes nothing for it. Pinned: "a contextual isolate inside a bracket forks an inert obligation", "abandoning an isolated child releases its bracket", "a bracket owned by an isolated child releases at the child's completion". Note `Forked` does not forward `reenter` or `complete` to its origin (`Isolate.scala:307-315`); that is correct today because the only handler overriding them, `Bracket`, forks to the inert cell, but a future `ContextHandler` overriding `reenter` for a real forked state would silently lose the refusal in children.

**Fatal errors release everything and are answered by nothing.** `Eval.guarded` catches every throwable, `recovered` skips `onRecover` when `IsFatal` and unwinds every region (`Eval.scala:566, 591-602`). `IOTask.run` then clears `curr` without a second walk (`IOTask.scala:306-314`), which is right only because the eval already released; nothing at the fiber level pins that, see spec T27.

**Fiber abandonment: link first, deliver nothing, settle last.** `IOTask.abandon` walks with `Tag[Async.Join]` so the join the remainder stands at is linked before the releases run, and `settleInterrupt` completes the promise after them (`IOTask.scala:393-404`; `IOPromise.settleInterrupt:208-222`). The status word makes the release happen once (`IOTask.scala:372-376, 393-396`). Pinned: `FiberTest` "the result of an interrupted fiber arrives after its finalizers ran", `ScopeTest` "interrupting scoped fibers reaches the promises they are parked on", `ScopeInterruptTest` "a fiber interrupted while parked on an uninterruptible promise still runs its scope finalizers".

**Scope.** `acquireRelease` registers through `ensureMap` (`Scope.scala:86-91`); a registration on a closed scope runs detached and throws `Closed` (`Scope.scala:254-272`); `close` claims and drains in one detached fiber so the #1928 window is closed (`Scope.scala:301-331`); the run's `Sync.ensure(finalizer.close)` backstop covers abandonment (`Scope.scala:166`). Pinned throughout `ScopeTest`. Qualification in C1.

**Channel and Meter handoffs.** `parkedTake` installs its putBack finalizer before waiting and marks the normal exit inside `f` (`Channel.scala:185-202`); `Meter.run` installs `settle` before any take and sets `taken` in the CAS step (`Meter.scala:449-458`). Pinned: `ChannelTest` "parked take under interruption" (both leaves), `MeterTest` "semaphore interrupt invariants". Qualification in C3.

## 3. Findings

### K1. A peeled remainder resumed on another fiber is released under it when the peeling scope exits

Files: `kyo-kernel/shared/src/main/scala/kyo/kernel/internal/Eval.scala:137-154` (the `FirstHandler` arm owes the escaping snapshot to the region below the peel), `:454-486` (`installed` settles only on the stack it runs on), `:499-516` (`drainRemainders` fires each snapshot release against its captured state), `kyo-kernel/shared/src/main/scala/kyo/kernel/Bracket.scala:141-163` (`reenter` reads the cell only at installation), `kyo-kernel/shared/src/main/scala/kyo/kernel/internal/PendingInternal.scala:78-94` (`crossing` builds the `Park` the remainder resumes through).

Scenario: fiber A runs `Emit.runFirst(stream)` (or `Stream.splitAt`, `Poll`, `Sink`, `Batch.capture`: every `handleFirst` user in kyo-prelude) over a body that holds a `Bracket`. The remainder is a value whose row is `Emit & S`, which has an isolate, so A hands it to `Fiber.init(Emit.run(rest))`. B installs the remainder's `Park`: `reenter` passes (the cell has not fired), `stack.settle` finds nothing on B's stack, and B runs the use against the live resource. A's owning region (or A's fiber boundary, when the peel was at depth 0) exits with the snapshot still in its lane and drains it: `cell.run(Absent)` with `ended` false fires the release with the discard signal while B is inside the use. When B's region ends, `complete` and `run` find the cell fired and do nothing, so B never learns.

Why the rules allow it: the doc's guarantee is settle-or-drain, never both, which holds; the `Closed` refusal is documented for a remainder consumed *after* the peeling scope ended. The concurrent case is neither refused nor settled cross-stack. README `Bracket` section says to consume the remainder inside the scope that peeled it, so this is a violated usage rule, but the consequence is a release against a live use rather than the refusal the same section promises.

Severity: S3. Spec T1.

### K2. `ensuring` and `ensuringWith` are exactly-once per walk, not per node

Files: `Bracket.scala:105-112, 119-127, 129-132` (`derive(outer) = cell` where `cell` is a by-name parameter: `apply` passes a `val`, the other two pass a `new Cell.Live(...)` expression), `Eval.scala:690-693` (the walk calls `derive(Maybe.empty)` on an unentered `HandleContext`).

Scenario: an unentered `Sync.ensure` node walked twice fires its finalizer twice, each walk minting its own cell. `EvalTest` "a double abandonment reaches a raw hook twice and a bracket once" pins the raw-hook and `apply` halves and leaves this third case unpinned. Today `Eval.release` has exactly one caller, `IOTask.abandon`, guarded by the status CAS, so no remainder is walked twice; the untagged overload the docs mention for a refused continuation has no caller in the tree.

Severity: S3. Spec T2.

### K3. `Sync.ensure` and `Sync.acquireReleaseWith` poll after the region's clean release

Files: `kyo-core/shared/src/main/scala/kyo/Sync.scala:162` (`.map(result => Abort.get(result))` applied to the `ensuringWith` region node) and `:105` (the same after `Bracket(...)`), `kyo-kernel/.../Pending.scala:69-89` (`map` takes the safepoint before applying), `Eval.scala:370-387` (the region's clean end: `complete`, `drainCleanOwn`, pop, then the continuation runs).

Scenario: a guard whose release does nothing on a clean end because the value is being handed to the next step (the shape `Topic.addPublicationDeadline` documents at `Topic.scala:424-428`: "a clean end is that hand-off, so the guard closes nothing"). The body's last poll is inside the region. Then the region ends, `fin` runs (a nested `Eval` of the finalizer, which reads the slot but does not consume the stop), the region pops, and `Abort.get`'s `Step.apply` polls. A stop delivered anywhere between the body's last poll and this one, which includes the whole execution of the finalizer, parks here. The walk finds a `Defer` over a settled value and stops. The value is owned by nobody: the guard already reported a clean end, and the caller's `ensureMap` never ran because it is chained after this map.

Deterministic reproduction: request the fiber's own interrupt from inside the finalizer on the clean path; the stop then lands on exactly this poll.

Why the rules allow it: the kernel's no-gap guarantee is per `Ensure` application; `Sync.ensure` composes a region with a polling `map`, and no rule says a hand-off across a region boundary is atomic.

Severity: S2. Spec T3. Fix shape: `ensureMap` for the `Abort.get` step in both `Sync.ensure` and `acquireReleaseWith`, and a sentence in their scaladoc saying that a value handed on by a guard is owned only from the caller's `ensureMap`.

### C1. `Scope.run`'s clean exit awaits its drain on a join with the body's value in flight

Files: `kyo-core/shared/src/main/scala/kyo/Scope.scala:159-166`: `.map { result => finalizer.close(result.error).andThen(finalizer.await).andThen(Abort.get(result)) }.handle(Sync.ensure(finalizer.close))`.

Scenario: the body completes with a value that outlives the scope (a pool, a client, a handle the caller will register on an outer scope). `close` spawns the drain and returns; `await` is `promise.get`, a join. An interrupt while parked there abandons the continuation; the backstop `Sync.ensure(finalizer.close)` finds the queue already claimed and does nothing; `Abort.get(result)` never runs. The value is stranded. The same happens on the `.andThen` poll after `await` and on any poll the caller adds before its registration.

Why the rules allow it: exactly the rule in the brief. The scope's own resources are safe (the drain owns them); what is exposed is the value the scope was built to produce.

Severity: S2 (the drain fiber's scheduling latency is the window; D3 is the instance found). Spec T4.

### C2. Spawn, then register in the next step

The shape: a producing step runs inside one `Sync.Unsafe.defer` or one `SnapshotWith` (the spawn), and the registration sits in a following `.map`, `.andThen` or for-comprehension step. `Step.apply` calls `Safepoint.get()` and `enter` before applying its function (`Arrow.scala:149-155`, `Pending.scala:72`); once a stop has been delivered to the thread, `Safepoint.resolve` drains the budget (`Safepoint.scala:119-157`) so that `enter` returns false and the step defers; the `Defer` arm then parks (`Eval.scala:72-76`), and the walk stops at the settled value (`Eval.scala:686-688`). `Fiber.initUnscoped` is unparented by design (`Fiber.scala:180-195`), so nothing else reaches the orphan. `Fiber.init`, `Fiber.use`, `Scope.acquireRelease`, `Async._timeout` and `Exchange.initUnscoped` all avoid this with `ensureMap` or `Bracket`, and their comments name the hazard.

Sites (all main sources):

| File:lines | Produced | Registered | Consequence |
|---|---|---|---|
| `kyo-core/.../Hub.scala:255-271` | publisher fiber | in the caller's `f` via `initWith`/`use` | publisher parked on `channel.take` for the process's life |
| `kyo-core/.../Hub.scala:173-186` | listener added to `listeners` | `Scope.acquireRelease(listener)` after `closed.map` | hub keeps pushing into a listener nobody drains |
| `kyo-core/.../KyoApp.scala:33-36` | app fiber | nowhere (and `block`'s timeout path never interrupts it) | orphan |
| `kyo-core/.../Async.scala:130-135` | fiber, then masked promise | nowhere | see C4 |
| `kyo-combinators/.../AsyncCombinators.scala:67-71, 88-91, 111-114` | two fibers | nowhere; `right.join` sits behind a step that never ran | `right` orphaned when interrupted at `left.await` |
| `kyo-combinators/.../Constructors.scala:44-50` | two fibers | nowhere | orphan whose result goes to a promise nobody reads |
| `kyo-core/.../StreamCoreExtensions.scala:142-147, 274-281, 351-358` | producer fiber | `Sync.ensure(producers.interrupt)` one step later | producers keep pulling their sources |
| `StreamCoreExtensions.scala:422, 534, 647, 767` | per-chunk fiber (`semaphore.run(Fiber.initUnscoped(...))`) | `channelOut.put` in the next map | fiber runs to completion unowned; the semaphore permit is returned by `Meter.run`'s pre-installed settle |
| `StreamCoreExtensions.scala:1116-1122, 1157-1161` | `push` fiber | nowhere but the final `fiber.get` | push survives, keeps pulling the source, wedges on `channel.put`; the sibling `tick` at :1129 is correctly `Scope.acquireRelease`d |
| `kyo-compat/bindings/kyo/.../CIO.scala:143` | fiber | nowhere | orphan |
| `kyo-system/.../Command.scala:67-79` | OS process (`unsafe.spawn()`) | `Scope.acquireRelease` in the next map | D18 |

Severity: S2 for each (the window is the producing step; for a process spawn or a blocking connect it is milliseconds). Specs T5 to T11.

### C3. `Channel.take` hands a resource-carrying element to a continuation a park can drop

Files: `Channel.scala:157-158` (`take = takeWith(identity)`), `:185-202` (`parkedTake`: `taken = true` is set inside `f`, after which the finalizer is a no-op), `:246-248` (the same hazard is documented for `close` only).

Scenario: `channelOut.take.map { chunkFiber => chunkFiber.getResult ... }` (`StreamCoreExtensions.scala:443-448, 552-553, 670-671, 785-786`). The take completes, `f = identity` runs (`taken = true`), the value leaves the region through K3's poll or the caller's `.map`. A stop there parks; the fiber handle is out of the channel, so `cleanup`'s drain-and-interrupt (`:408-412`) never sees it, and `getResult`'s link never registered. The orphan finishes its chunk on its own, holding whatever `f` acquired, and its result is dropped.

Why the rules allow it: the channel's contract says `f` owns the value; with `take`, `f` is the identity and the owner is the continuation. The rule from the brief applies: the registration has to be in the step that produced the value, which for a channel means inside `takeWith`.

Severity: S2. Specs T12, T13.

### C4. `Async.uninterruptible`

File: `Async.scala:130-135`: `Fiber.internal.initUnscoped(v).map(_.uninterruptible.map(_.get))`. `IOPromise.uninterruptible` answers `preInterrupt = false` (`IOPromise.scala:96-101`), so `useResult`'s `task.interrupts(masked)` link (`Async.scala:829-837`) is inert by design. An interrupt at `_.get` abandons the caller; the shielded fiber runs to its end and its value lands in the mask with no consumer. `Async.uninterruptible(acquire).map(register)` therefore leaks whatever `acquire` produced. The leading `.map` is a second, narrower window that orphans the fiber before the mask exists.

Severity: S2. Spec T14.

### C5. `Channel.close` and `Queue.close`

`Channel.scala:253` documents that an interrupt at the join discards the buffered elements. `Queue.scala:137` has the same shape and no note. Severity S3, documentation and one pin (T15).

### C6. `Async.timeout` arms the sleep before a park point that precedes the spawn

`Async.scala:200-216`: `clock.unsafe.sleep(after)` runs inside the `Sync.Unsafe.defer` block; the block returns `Effect.defer(SnapshotWith, ensure, id)`, and the `Defer` arm may park there before the spawn. The `ensureMap` correctly covers the spawn itself. An interrupt at that park leaves a timer entry with no `onComplete` wired, which fires and does nothing when `after` elapses (under `Clock.withTimeControl`, never). No external resource; informational. Spec T16 pins the covered half.

### D1. kyo-sql `closeAll` extracts the ring before the force-close is installed

File: `kyo-sql/shared/src/main/scala/kyo/internal/client/SqlConnectionPool.scala:215-222`: `Sync.Unsafe.defer(pool.close()).flatMap { idleConns => Sync.ensure { ... idleConns.foreach(_.closeNow) ... }(drain(gracePeriod)) }`.

Scenario: a stop delivered during `pool.close()` (a ring drain across every host pool) makes the `flatMap` poll defer and park; `idleConns` is a local of the abandoned continuation, the pool is closed so no reclaim or later lease reaches those connections, and `closeAll` has no outer owner. Every idle connection leaks. The comment above the `Sync.ensure` explains why the force-close is a finalizer and misses that the extraction precedes it. `SqlConnectionCancelTest` "closeAll force-closes a connection whose reclaim never completes on its own" and `drainPollCount` exist to land the interrupt inside the grace window, after extraction, so this window is unpinned.

Severity: S2. Spec T17. Fix shape: the one `Connection.openSocket` uses at `db/Connection.scala:395-407`: a cell filled in the same unsafe step as the extraction, with the finalizer registered before it.

### D2. kyo-sql custody take one step after the exit registration

Files: `SqlConnectionPool.scala:760-767` with `:482-487` and `:511-518` (`onLease` registers `decideExit` through `resolvingOnce`, then the body's first step is `Sync.Unsafe.defer(custody.take())`), `:916-918` and `:926-927` (`acquireScoped`: `Scope.ensure(decideExit).andThen(Sync.Unsafe.defer(custody.take()))`).

Scenario: `Scope.ensure` registers inside a `DeferWith`; the following `andThen` polls again before `custody.take()` runs. A stop delivered during the registration itself (a queue offer, nanoseconds) parks between them. Both scopes then close: `resolvingOnce`'s runs `decideExit`, which pools a reusable connection; `withCustody`'s runs the orphan finalizer, which `closeNow`s it. A closed connection sits in the idle ring until the next poll's `isAlive` evicts it (`ConnectionPool.scala:213-215`), and the lease counters record one release and one discard for one connection.

Severity: S3. Spec T18. Fix shape: take custody in the same unsafe step that registers the exit, or register through `ensureMap`.

### D3. kyo-sql `SqlClient.openScoped` and `Runtime.init` strand a warmed pool

Files: `kyo-sql/shared/src/main/scala/kyo/SqlClient.scala:1461-1466`, `kyo-sql/shared/src/main/scala/kyo/db/Runtime.scala:151-176`.

Scenario: `Runtime.init` warms the pool under an inner `Scope.run` whose `Scope.ensure` closes the pool only on a failure edge. On the clean edge the inner run awaits its drain (C1): an interrupt there strands a pool holding `minConnections` established sockets. If that join is passed, `.andThen(new Runtime(...))` and then `openScoped`'s `.flatMap(client => Scope.ensure(client.close))` are two more polls before any owner exists. `SqlClient.init` under `Async.timeout`, or losing a race, reaches this.

Severity: S2 (the drain join and two polls; the drain runs on a detached fiber, so the join lasts a scheduling round trip). Spec T19. Fix shape: register `pool.closeAll` on the caller's scope before warm-up, as the inner ensure already does on its own scope.

### D4. kyo-sql advisory lock granted before its release is registered

File: `SqlClient.scala:845-857` (`lockedOn`): `self.serialised(meter)(conn.acquireAdvisoryLock(key, timeout)).andThen { Scope.run { Scope.ensure(release...).andThen(body) } }`.

Scenario: `acquireAdvisoryLock` is a server round trip (`pg_advisory_lock`, `GET_LOCK`) joined under `Meter.run`. The server grants the lock and the reply is in flight when the caller is interrupted (an `Async.timeout` around `withAdvisoryLock` expiring, a race loss, a scope close). The continuation with `Scope.ensure(release)` never runs. `decideExit` sees a statement in flight and reclaims: `cancelInFlight` arrives after the grant, `drainToIdle` reads the reply, `rollbackIfOpenTransaction` does not touch a session-scoped lock (the file's own comment at `:476-477` says so). The connection goes back to the ring holding the lock. The next borrower's `withAdvisoryLock` on the same key deadlocks or times out, forever, on that session.

Why the rules allow it: the value produced by the forked wait is a server-side lock; nothing owns it until the next step.

Severity: S1. Spec T20. Fix shape: register a scope finalizer that releases the lock *before* sending the acquire, guarded by a flag the reply handler sets in the step it delivers in (the `takeSlot`/`withSlot` shape at `SqlConnectionPool.scala:380-451`), and have `decideExit` treat a session that may hold a lock as not reusable.

### D5. kyo-browser launcher spawns Chrome one poll before registering it

File: `kyo-browser/shared/src/main/scala/kyo/internal/BrowserLauncher.scala:96-98`: `Command(args*).inheritStderr.spawnUnscoped.map { proc => Scope.acquireRelease(proc)(terminateTree)... }`.

Scenario: C2 with a wide window: a Chrome fork/exec takes tens of milliseconds; a stop delivered during it parks the `map`. The process tree (main, zygotes, GPU, network service) lives on; `killOrphans` on the *next* launch is the only thing that reaps it, by user-data-dir name. The temp directory itself is covered: `Path.tempDir` carries `Scope` in its row (`kyo-system/.../Path.scala:615`), and `launch`'s own `Scope.ensure(removeTmpDir)` at `:26` is a second registration.

Severity: S2. Spec T21.

### D6. kyo-browser `CdpBackend.init`'s dialog drainer

Files: `kyo-browser/shared/src/main/scala/kyo/internal/CdpBackend.scala:170-173` (`init = Scope.acquireRelease(initUnscoped(...))(_.close)`), `:178-200` and the transport-based `initUnscoped` at `:464-530` (the WebSocket transport is scope-registered before its fork inside `JsonRpcHttpTransport.webSocket`; the `JsonRpcHandler` registers itself at its end; `buildDialogDrainer` spawns a `Fiber.initUnscoped` owned only by the record's `close`; the `getVersion` probe follows).

Scenario: `Scope.acquireRelease`'s `ensureMap` covers only the acquire's final value. An interrupt at the probe join (or at any of the several polls between the drainer's spawn and the end) abandons the acquire: the transport and the handler close through their own scope registrations, the drainer fiber stays parked on `dialogQueue.take` (a `Channel.initUnscoped` nobody closes) for the process's life.

Severity: S2. Spec T22.

### D7. kyo-browser contexts, targets, popups

Files: `kyo-browser/shared/src/main/scala/kyo/internal/BrowserTab.scala:243-246` (`attachAndSetupTab`), `:291-294` (`createChildTab`, behind `withFork`, `isolate.fresh`, `isolate.clone`), `kyo-browser/shared/src/main/scala/kyo/Browser.scala:3683-3688` (`withNewTab`), `:3620-3626` (`withPopup`).

Scenario: `createBrowserContext` and `createTarget` are JSON-RPC requests whose reply is awaited; Chrome creates the context or target before answering. `Scope.ensure(dispose...)` is the next for-comprehension step. An interrupt at the reply await leaks the context (service workers, storage, downloads, renderer) or the target for the browser's life. `withPopup` is the same with the popup already opened by the trigger and detected through a polled `getTargets` join.

Severity: S1 (a CDP round trip per fork, under session timeouts). Spec T23.

### D8. kyo-browser page-state acquires through a round trip

Files: `kyo-browser/.../internal/HoldStill.scala:99-101`, `Browser.scala:2156-2158, 2181-2192, 2263-2278, 2304-2320, 2417-2420, 3010-3020`.

Scenario: `Scope.acquireRelease(<CDP call>)(restore)`: the stylesheet, marks, viewport, emulated media, background override or download policy is applied in Chrome before the reply; `ensureMap` runs only when the reply arrives. An interrupt at the await leaves the page state changed and, for viewport and emulation, the tab's cached override cell already mutated (`:2266`), so a later nested `withViewport` restores to a stale value. `withDownloads` is the longest-lived: the tab keeps accepting downloads to a temp path.

Severity: S2. Spec T24 (one representative).

### D9. kyo-aeron `Topic` hand-off window (instance of K3)

Files: `kyo-aeron/shared/src/main/scala/kyo/Topic.scala:256-261, 342-349` (the `ensureMap` sites), `:429-434, 527-532` (the guards: `if tokOwned then free else if outcome.isDefined then close`).

Scenario: on a `Done` poll the guard hands the publication on at its clean end and closes nothing. K3's poll sits between that clean end and Topic's `ensureMap`. An interrupt delivered during the guard's own execution (it runs an FFI call through `Sync.Unsafe.defer`) parks there; the publication is open, `closePublication` is registered by nobody. The guard's comment ("a clean end is that hand-off") assumes the atomicity K3 shows is missing. Same for subscriptions.

Severity: S2. Spec T25 (deterministic through the finalizer self-interrupt of T3).

### D10. kyo-aeron native runtimes built after blocking joins

File: `kyo-aeron/shared/src/main/scala/kyo/internal/AeronPlatformTransport.scala:32-37` (embedded: `driverStart(...).flatMap(_.safe.get)` then `clientConnect(dir).flatMap(_.safe.get)` then the runtime), `:81` (driver), `:115-120` (external).

Scenario: `@Ffi.blocking` bindings run on a carrier and deliver a native handle through a fiber promise the caller joins. `AeronRuntime.close` is built in the step after. An interrupt at the connect join (a client connect can take seconds when the driver is slow to answer) leaves a connected native client, or a running media driver with its CnC mapping and sockets, that no Scala object references, so `aeron_close`/`driver_close` can never be called. In `embedded`, an interrupt at the second join also strands the driver from the first, before its `Diagnostics.register` entry exists. `AeronClient.connect`'s `Scope.acquireRelease(connectUnscoped(...))` (`AeronClient.scala:39-40`) wraps the result and inherits the exposure.

Severity: S1. Spec T26.

### D11. kyo-http connections delivered and then abandoned

Files: `kyo-http/shared/src/main/scala/kyo/internal/client/HttpClientBackend.scala:65-105` (`connect`: the transport callback completes `resultPromise`; `if !resultPromise.complete(...) then transportConn.close()` at `:96-101`), `:1202-1212` (`poolWithImpl`: `connectFiber.safe.use { conn => trackConn(conn); ... releasingConn(...) }`), `:717-724` (`connectWebSocket`), `:770-777` with `:801-806` (`connectRaw` and `setupRawConnection`'s `Scope.ensure` after the join), `:223-229` (`connectWith`, no finalizer at all), `kyo-http/shared/src/main/scala/kyo/HttpServer.scala:83-86` and `:135-141` (`Scope.acquireRelease(initUnscoped(...))` where the acquire joins `listenFiber`).

Scenario: the producer's guard covers the ordering in which the interrupt reaches the promise first. In the other ordering the callback completes the promise with the connection, the wakeup schedules the requesting fiber, and the interrupt (the request's own `Async.timeoutWithError(config.timeout)` at `:1168-1179`, a race loss, a scope close) takes the status word before the resumed slice runs `trackConn`. `IOTask.abandon` links and settles; `resultPromise.interrupt` returns false on a completed promise; the connection is in no pool and no registry, so `closeAll` cannot reach it. Only `pool.unreserve` (registered before the join) runs, freeing a slot for the next leak. The server variant leaves a bound listening socket and accept loop for the process's life, with the port occupied.

Severity: S1 for `poolWithImpl` (per request under a timeout), S1 for `HttpServer`, S2 for the rest.

### D12. kyo-http fiber trios and the chunked decoder

Files: `HttpClientBackend.scala:951-1009`, `kyo-http/shared/src/main/scala/kyo/internal/server/UnsafeServerDispatch.scala:453-527` (three `Fiber.initUnscoped(...).map` in a row, `Sync.ensure(interrupt all three)` after the third), `:572-599` (decoder fiber, `Sync.ensure(decoderFiber.interrupt)` after the map). C2 shape; the read loop keeps the transport stream open with no owner. Severity: S2.

### D13. kyo-jsonrpc stdio and Unix-socket transports

Files: `kyo-jsonrpc/shared/src/main/scala/kyo/JsonRpcTransport.scala:169-176`, `kyo-jsonrpc/shared/src/main/scala/kyo/internal/transport/UdsBackend.scala:21-36`; producers `kyo-net/jvm/src/main/scala/kyo/net/internal/NioTransport.scala:185`, `kyo-net/shared/src/main/scala/kyo/net/internal/posix/PosixTransport.scala:219-234` (`stdioClaimed` CAS, never reset, no handoff guard).

Scenario: `NetPlatform.transport.stdio().safe.get.map { conn => Scope.ensure(wire.close) ... }`. An interrupt at the join (a startup timeout around the transport) abandons `conn`: its pumps keep running, the claim stays set, and every later `contentLengthStdio()` in the process aborts `NetStdioAlreadyOpenException`. `UdsBackend.connect` binds the listener and creates the socket file before the join; an interrupt leaves both, and the next bind on the path fails with `EADDRINUSE`.

Severity: S1.

### D14. kyo-jsonrpc engine registrations one step late

Files: `kyo-jsonrpc/.../internal/engine/JsonRpcEndpointImpl.scala:571-582` (handler fiber and `pendingInbound` entry: `handlerProxy.becomeDiscard` and `onComplete` in the map; an orphan here also never writes a response, so the peer hangs), `:255, 781-787, 818-822` (writer channel, Exchange, writer fiber, released only by `Scope.acquireRelease` on the final value at `JsonRpcHandler.scala:240`), `kyo-jsonrpc/.../internal/engine/CallEngine.scala:230-242` (progress monitor), `:412-443` (call fiber, `progressStreams`/`tokenToDeadline` entries, then an `idPromise.get` join before the `Pending` that owns the fiber is built). C2 and join shapes. Severity: S2.

### D15. kyo-actor `PubSub`

Files: `kyo-actor/shared/src/main/scala/kyo/PubSub.scala:207-210` (linearized: `actor.ask(Subscribe)` join, `Scope.ensure(Unsubscribe)` in the `andThen`), `:91-98` (`state.updateAndGet(_ + subscriber).andThen { Scope.ensure(remove) }`).

Scenario: the actor adds the subscriber to its set and completes the reply; the subscriber's fiber is interrupted before it resumes. The subscriber stays in the set for the PubSub's life, and because `fanOut` awaits delivery (`:44`), every later `publish` blocks on a mailbox nobody drains until it closes.

Severity: S1 (the join is an actor round trip). Spec T28.

### D16. kyo-compiler `SpawnBackend` worker JVM

File: `kyo-compiler/jvm/src/main/scala/kyo/internal/SpawnBackend.scala:89-105` (`spawnWorker(...).map { process => aeronClient(driver).map { aeron => connect(...).map { exchange => ... Sync.ensure(kill unless started)(...) } } }`), `:172` (`spawnUnscoped`).

Scenario: the JVM is spawned at `:89`; the kill is armed at `:99`; `aeronClient(driver)` between them is `AeronClient.connectUnscoped`, an `Async` connect that parks (and inherits D10). An interrupt there orphans the worker JVM and its Aeron client for the machine's life. The comment at `:93-96` promises the kill "on failure or interrupt during the readiness probe", which is true only from `:99` on.

Severity: S1. Spec T29.

### D17. kyo-flow `superviseDetached`

File: `kyo-flow/shared/src/main/scala/kyo/FlowEngine.scala:685-693`: `Fiber.initUnscoped(supervise(...)).map { fiber => supervisions.updateAndGet(...) }`. The registry is the only owner (`stopSupervisions` at `:702-706`). C2 shape; an orphan keeps renewing the store lease for an execution nobody supervises. Severity: S2.

### D18. kyo-system `Command.spawn`

File: `kyo-system/shared/src/main/scala/kyo/Command.scala:67-79`. C2 with the fork/exec window. Every `Command.spawn` user inherits it (`ClaudeCodeCompletion`, `CodexCompletion`, `Process`). Severity: S2. Spec T30.

### D19. kyo-caliban and kyo-zio

Files: `kyo-caliban/src/main/scala/kyo/Resolvers.scala:450-455` (`ZIOs.get(setup).map { (inputQ, pipe) => ... ZStream.fromQueueWithShutdown(inputQ) }`: the queue's shutdown is wired in the continuation), `kyo-zio/shared/src/main/scala/kyo/ZIOs.scala:24-38` (`p.onInterrupt(interrupt the ZIO fiber)` registered before the join covers a still-running ZIO fiber, not one that already succeeded with a resource), `:65-87` and `kyo-zio/.../ZStreams.scala:67-83` (C2 shape, mitigated by `ZIO.uninterruptibleMask` and `acquireRelease`'s uninterruptible acquire). Severity: S2.

### DOC1. Kernel guide describes a walk the code no longer has

`kyo-kernel/CONTRIBUTING.md:160` ("the budgeted overload, the one a fiber abandonment uses, spends a unit of budget per step ... carries the continuation down to its leftmost step and offers an already-settled value to an `Ensure` waiting on it ... `Eval.release`, and its `leftmost` and `ensuring`; `EvalTest`, "a release waiting on a value that already arrived is found on abandonment", "a release the abandoned Ensure installs rather than registers is still run"). `Eval.release` (`Eval.scala:654-741`) has no budget, no `leftmost`, no `ensuring`; the two leaves do not exist; `git log -S leftmost` shows the machinery added in `ae5fb24362` and removed in `c8b9735e16` ("a stopped acquire owes nothing; it also corrupted #1735"). `IOTask.abandon`'s scaladoc (`IOTask.scala:378-392`) states the current rule correctly. The README's `ensureMap` and `Bracket` chapters match the code.

## 4. Sweep: resources produced by a forked wait, and where their release is registered

Verdict key: SAFE(1) release inside the child; SAFE(2) registration through `ensureMap`/`Bracket` on the producing step, or a finalizer registered before the wait; SAFE(3) the value carries no obligation; SAFE(4) producer-side handoff (a delivery that lost to the interrupt is refused or put back); SAFE(5) the join link interrupts the child and the child's own finalizers release what it produced. EXPOSED(join): the release registers after a join. EXPOSED(spawn): the release registers one poll after a synchronous producing step (C2).

| Site | Shape | Verdict | Reason |
|---|---|---|---|
| `kyo-core/.../Fiber.scala:146` `Fiber.init` | `Scope.acquireRelease(initUnscoped(owned))` | SAFE(2) | `ensureMap` over a `SnapshotWith` acquire; traced: spawn and registration in one arrow application |
| `Fiber.scala:171` `Fiber.use` | `Sync.acquireReleaseWith(initUnscoped(v))(_.interrupt)` | SAFE(2) | `Bracket` installs the region as the spawn's value arrives; pinned by `ScopeInterruptTest:327` |
| `Async.scala:207-214` `_timeout` | `initUnscoped(v).ensureMap { wire; task.get }` | SAFE(2) for the spawn; the value `A` is the caller's | see C6 for the pre-spawn park |
| `Async.scala:242, 288, 396, 422` race/raceFirst/gather/foreachIndexed | `Fiber.internal.X(...).map(_.get)` | SAFE(5) for the children (parent-linked at `IOTask(crossing)(state, v, parent)`); the raced value is the caller's | a raced value that is a resource is exposed by the brief's rule |
| `Async.scala:130-135` uninterruptible | spawn, mask, join | EXPOSED(spawn), EXPOSED(join) | C4 |
| `Async.scala:807` fromFuture | `Fiber.fromFuture(f).map(_.get)` | SAFE(3) unless the Future yields a resource | Future is not cancellable |
| `Scope.scala:86-91` acquireRelease | `Sync.defer(acquire).ensureMap` | SAFE(2) for a settled or single-step acquire; EXPOSED(join) for an acquire that parks inside (D3, D6, D8, D10) | `kyo-sql/.../db/Connection.scala:395-400` states this limit |
| `Scope.scala:159-166` run | `close` then `await` join then `Abort.get` | EXPOSED(join) for a body value the caller registers afterwards | C1 |
| `Scope.scala:301-331` Finalizer.close | detached unparented drain fiber | SAFE | nothing interrupts it; pinned #1928 |
| `Channel.scala:185-202` parkedTake | putBack finalizer before the wait | SAFE(4) until `f` runs; EXPOSED after for `take` of a resource | C3 |
| `Channel.scala:128-137, 147-149` put, putBatch | `putFiber(...).safe.get` | SAFE(4) | `pollNextLive` drops a cancelled producer's value (`:546-550`) |
| `Channel.scala:253`, `Queue.scala:137` close | `close().safe.get` | EXPOSED(join) | C5, documented on Channel |
| `Meter.scala:449-489` run, `:497-517` tryRun | `Sync.ensure(settle)` before any take | SAFE(2)+(4) | pinned by `MeterTest` |
| `Hub.scala:255-271` publisher, `:173-186` listen | spawn/add then map | EXPOSED(spawn) | C2 |
| `Exchange.scala:183` | `initUnscoped(...).ensureMap` | SAFE(2) | the reference shape |
| `Cache.scala:184, 229, 297` | `Fiber.init`, timeout on `Unit`, memo winner owns | SAFE(2)/(3)/(4) | |
| `Clock.scala:371-389, 592, 734` | sleeps, timer fibers | SAFE(3) | `Unit` values |
| `Gate.scala:80-115`, `Latch.scala:30` | `pass().safe.get`, `await().safe.get` | SAFE(3) | `Unit`; Gate counts the arrival inside the unsafe call, so an interrupted party's arrival stays counted (ledger, not a leak) |
| `Signal.scala:136-309, 548` | races and zips over `Unit`, `next().safe.use(f)` | SAFE(3), SAFE(2) | |
| `KyoApp.scala:33-36` | spawn then map, `block` never interrupts on timeout | EXPOSED(spawn) | C2 |
| `KyoAppInterrupts.scala:46` | `raceFirst(v, awaitInterrupt.get)` | SAFE(3) | |
| `StreamCoreExtensions.scala:91, 1129, 465, 577, 690, 818` | `Scope.acquireRelease(spawn)`, `Fiber.use(background)` | SAFE(2) | |
| `StreamCoreExtensions.scala:142-147, 274-281, 351-358, 422, 534, 647, 767, 1116-1161` | spawn then map | EXPOSED(spawn) | C2 |
| `StreamCoreExtensions.scala:445, 552, 670, 785` | `channelOut.take.map { fiber => fiber.getResult }` | EXPOSED(join) | C3 |
| `StreamCoreExtensions.scala:15-35` emit helpers | `channel.take.map` on data chunks | SAFE(3) | a chunk dropped at the map is data loss, not a leak |
| `kyo-combinators/.../AsyncCombinators.scala:67-114`, `Constructors.scala:44-50` | two unparented spawns, no registration | EXPOSED(spawn) | C2; `Async.zip` is the safe equivalent |
| `kyo-compat/bindings/kyo/.../CIO.scala:60-62` | `Scope.run(Scope.acquireRelease(acquire)(release).map(use))` | SAFE(2) for a Sync acquire | |
| `CIO.scala:143`, `CPromise.scala:35` | spawn then map; `uninterruptible.map(get)` | EXPOSED | C2, C4 |
| `kyo-core/jvm-native/.../AsyncPlatformSpecific.scala:13-19, 36-45` | CompletionStage joins | EXPOSED(join) for a resource stage value (documented "abandons the stage"); the cancel variant is under `Sync.ensure` | |
| `kyo-prelude/shared/src/main` | no `Async` use | not applicable | |
| `kyo-sql/.../SqlConnectionPool.scala:380-451` takeSlot, withSlot | give-back registered before the take; `held` set inside `takeWith`'s `f` | SAFE(2)+(4) | the reference fix; the timeout child's claim is a flag write inside the delivery step |
| `SqlConnectionPool.scala:127-161` leaseScoped | same on the caller's scope | SAFE(2)+(4) | |
| `SqlConnectionPool.scala:458-469, 553-593` withCustody, acquireOrReserve | poll and `custody.claim` in one unsafe block; orphan finalizer registered before | SAFE(4) | pinned by the two handover leaves |
| `SqlConnectionPool.scala:480-487, 510-518, 913-931` custody take | one step after the exit registration | fragile | D2 |
| `SqlConnectionPool.scala:215-222` closeAll | extraction then flatMap then `Sync.ensure` | EXPOSED(spawn) | D1 |
| `SqlConnectionPool.scala:180-193` warmUp | `Async.fill` of `Scope.run(withCustody(connect))` | SAFE(1) | each child owns its connection through custody until `pool.release` |
| `SqlConnectionPool.scala:772-823, 837-870` decideExit, cancelAndReclaim | detached carrier with `Sync.ensure` claim on every edge, `quarantined.remove` as the atomic claim | SAFE | nothing interrupts the carrier; `closeAll`'s sweep claims what it can |
| `kyo-sql/.../db/Connection.scala:381-441` openSocket | finalizer registered before the connect is launched, cell filled in the launch step; `custody.claim` in `body`'s last step | SAFE(2)+(4) | the reference fix for the whole class |
| `Connection.scala:482-512` reads under `timeoutWithError` | protocol frames; `requestInFlight` lowered by `Sync.ensure` | SAFE(3) | |
| `SqlClient.scala:1461-1466`, `db/Runtime.scala:151-176` | pool through `Scope.run` await then flatMap | EXPOSED(join) | D3 |
| `SqlClient.scala:845-857` lockedOn | server lock, release registered after the reply | EXPOSED(join) | D4 |
| `SqlClient.scala:533-538` serialised | `meter.run` permit | SAFE(2) | Meter's own settle |
| `kyo-net/.../ConnectionPool.scala:114-124` reaper | assigned in the same unsafe block as the spawn, interrupted by `close` | SAFE(2) | |
| `ConnectionPool.scala` ring ops | direct `AllowUnsafe`, no joins | not applicable | `release` re-reads `closed` and drains itself (`:65-70`) |
| `PosixTransport.scala:786-814` completeConnect, `HttpClientBackend.scala:96-101` | `if !promise.complete(conn) then close()` | SAFE(4) for interrupt-first; EXPOSED(join) for complete-first | D11 |
| `NioTransport.scala:185`, `PosixTransport.scala:219-234` stdio | `Fiber.Unsafe.init` completes the promise with the value, no guard | EXPOSED at the consumer | D13 |
| `kyo-net` drivers, pumps, resolvers, accept handlers | promises consumed by `onComplete`/`poll`, fire-and-forget carriers | SAFE(3) | no safe-tier join in those files |
| `HttpClientBackend.scala:253-257` sendWith, `:1199-1200` | `Sync.ensure(onRelease)`/`releasingConn` wrapping the join | SAFE(2) | |
| `HttpClientBackend.scala:223-229, 717-724, 770-777, 1202-1212`, `HttpServer.scala:135-141` | connect joins, release after | EXPOSED(join) | D11 |
| `HttpClientBackend.scala:951-1009`, `UnsafeServerDispatch.scala:453-527, 572-599` | spawn trios and decoder | EXPOSED(spawn) | D12 |
| `UnsafeServerDispatch.scala:358-424` | `IOTask.detached` registered in the same unsafe block | SAFE(2) | |
| `HttpWebSocket.scala:144-152` | `Async.raceFirst` under `Sync.ensure(close both)` | SAFE(2) | |
| `JsonRpcTransport.scala:169-176`, `UdsBackend.scala:21-36` | join then `Scope.ensure` | EXPOSED(join) | D13 |
| `UdsBackend.scala:23-27` accept | `if !first.complete(conn) then conn.close()` | SAFE(4) | the never-delivered half |
| `JsonRpcEndpointImpl.scala:255, 571-582, 781-822`, `CallEngine.scala:230-242, 412-443` | spawn then map; `idPromise.get` join | EXPOSED | D14 |
| `CallEngine.scala:56-111, 244-278, 504-541` | races and timeouts over decoded values; cleanup inside the child | SAFE(1)/(2)/(3) | |
| `JsonRpcHttpTransport.scala:81-158` | `Scope.ensure(doneRef.complete; close channels)` before the fork | SAFE(4) | the pre-registered signal unwinds the session |
| `kyo-caliban/.../Resolvers.scala:450-455` | `ZIOs.get(setup).map { queue, pipe }` | EXPOSED(join) | D19 |
| `kyo-zio/.../ZIOs.scala:24-38` | `onInterrupt` before the join | SAFE(5) while the ZIO fiber runs; EXPOSED(join) once it succeeded with a resource | D19 |
| `ZIOs.scala:65-87`, `ZStreams.scala:67-83` | spawn then map under ZIO masks | EXPOSED(spawn), mitigated | D19 |
| `ZStreams.scala:33-45`, `ZLayers.scala:30-31` | `Scope.ensure(scope.close)` before the join | SAFE(2) | |
| `kyo-actor/.../Actor.scala:225-229, 388-402, 759-788` | `Sync.ensure(waiters.remove)` around the reply join; `Fiber.init`; `Hub.listen` | SAFE(2) | |
| `kyo-actor/.../PubSub.scala:91-98, 207-210` | registration after the join or the update | EXPOSED | D15 |
| `kyo-aeron/.../Topic.scala:256, 342` | `ensureMap` after the deadline loop | EXPOSED(join) through K3 | D9 |
| `Topic.scala:429-498, 527-588` deadline loops | `Sync.ensure` guard covering the token and the `Done` step | SAFE(2) inside the region | the hand-off at the region's end is D9 |
| `AeronPlatformTransport.scala:34-37, 81, 115-120`, `AeronClient.scala:39-40` | FFI joins then runtime | EXPOSED(join) | D10 |
| `kyo-ai/.../LLM.scala:681`, `TypeSafeDecider.scala:107`, `CodexCompletion.scala:96-155` | timeouts over replies | SAFE(3) | |
| `kyo-ai/.../Completion.scala:363-364`, `ClaudeCodeCompletion.scala:209-233`, `CodexCompletion.scala:188-194` | `Scope.acquireRelease(spawn)`, `Fiber.init`, `Command.spawn` chains | SAFE(2), inheriting D18's window | |
| `kyo-browser/.../BrowserLauncher.scala:96-98` | `spawnUnscoped.map { Scope.acquireRelease }` | EXPOSED(spawn) | D5 |
| `BrowserLauncher.scala:25-28, 248-275` | `Path.tempDir` (Scope in its row) plus `Scope.ensure`; `Async.timeout` over a String | SAFE(2), SAFE(3) | |
| `CdpBackend.scala:170-173, 178-200, 464-530, 648` | `Scope.acquireRelease(initUnscoped)` over a parking acquire with a mid-way spawn | EXPOSED(join) | D6 |
| `BrowserTab.scala:243-246, 291-294`, `Browser.scala:3620-3626, 3683-3688` | CDP create then `Scope.ensure` | EXPOSED(join) | D7 |
| `HoldStill.scala:99-101`, `Browser.scala:2156-3020` (six sites) | `Scope.acquireRelease(<round trip>)` | EXPOSED(join) | D8 |
| `Browser.scala:3111, 3228, 3385-3392` | `Fiber.init` drainers; `Scope.ensure` registered before `startScreencast` | SAFE(2) | the correct shapes in the same file |
| `Browser.scala:3349` | per-frame unscoped ack fiber | SAFE(3) | no obligation; an orphan by construction |
| `SharedChrome.scala:93-107` | detached fiber owning its own `Scope.run` | SAFE(1) | released by scheduler shutdown |
| `kyo-compiler/.../SpawnBackend.scala:89-105` | JVM spawned, kill armed after a parking connect | EXPOSED(join) | D16 |
| `CompilerPool.scala:114-134, 189` | `Sync.Unsafe.ensure` around `create`; loser awaits the winner's cached instance | SAFE(2)/(4) at this layer | inherits D16 |
| `kyo-flow/.../FlowEngine.scala:685-693` | spawn then map | EXPOSED(spawn) | D17 |
| `FlowEngine.scala:743-778, 1284-1286`, `StoreInterpreter.scala:164-521`, `Flow.scala:732-1694` | `Fiber.init`, timeouts over durable values, branch outcomes written inside the branch | SAFE(1)/(2)/(3) | |
| `MemoryFlowStore.scala:318-322` | `Async.race(delay, channel.take, registrations.take)` | SAFE(3) by construction | the taken token is `Unit` and the claim is outside the race (comment at `:308-311`); any payload in that channel would turn it into a lost-row bug |
| `kyo-doctest/.../Orchestrator.scala:37, 118` | `Scope.acquireRelease(Driver.init)` (Sync), `Async.foreach` over outcomes | SAFE(2), SAFE(3) | |
| `kyo-system/.../Command.scala:67-79` | `unsafe.spawn()` then map | EXPOSED(spawn) | D18 |
| `Command.scala:146-151`, `Process.scala:107-111`, `PathWatch.scala:288-289` | `Fiber.init`, joins over bytes and exit codes, `firstTick.get` inside the child | SAFE(2)/(3), SAFE(1) | |
| `kyo-examples/.../Log.scala:26` | `val _ = Fiber.initUnscoped(flushLoop)` holding a `FileWriter` | SAFE(3) as written | an unowned resource by construction, never joined; example code |
| `kyo-ffi/.../NativeLeakDetector.scala`, `kyo-stm` | no kyo joins | not applicable | |

## 5. Test scenario specs

Every spec uses deterministic constructs only: `Promise`, `Latch`, `Channel`, `Clock.withTimeControl`, `assertEventually` on an observable counter, and the self-interrupt technique already used by `ScopeInterruptTest` (`self.unsafe.interrupt()` requested from inside the step the stop must follow, so the stop lands on the next poll). Platforms: JVM, JS and Native unless a native process or a container backend is required. "Reproduces a bug" means the leaf is expected red on the current tree.

**T1. Kernel, `kyo-kernel/.../BracketTest.scala`, "multi-shot clauses" group: a peeled remainder resumed while the peeling scope is open is not released under it.** Reproduces a bug (K1). Setup: `Bracket(Effect.defer(1)) { r => ask.map(_ + r) }((_, o) => outcome = Maybe(o))` under `handleFirst(Tag[Ask], v)(handle = [C] => (_, cont) => cont, done = ...)` peeled inside an outer region `Rel` (a `ContextEffect.handle` with a release hook recording "outer released"). Action: obtain the remainder as a value; from *inside* the outer region's body, hand the remainder to a nested `Eval.partial` on the same thread and stop it in front of the bracket's use (`requestStop()` inside the use's first step) so it is parked with the bracket installed; then let the outer region end (its lane still holds the snapshot). Observe: today `outcome` is `Present(KyoException("remainder discarded"))` before the parked remainder resumes, and resuming it then runs the use against a released resource with no `Closed`. Expected: either the outer exit refuses to drain a snapshot whose regions are installed elsewhere (the cell is live but installed), or the resumption is refused with `Closed`; the leaf asserts that the use never runs after `outcome` is set. Companion in `kyo-core/.../ScopeInterruptTest.scala`: `Emit.runFirst` remainder handed to `Fiber.init` from inside the peeling fiber, the child parks on a `Latch` inside the bracket's use, the parent's `Scope.run` ends; assert the child observes the resource still open when the latch releases. Platforms: all.

**T2. Kernel, `BracketTest.scala` "ensuringWith": a double abandonment reaches an `ensuring` region once.** Pins a rule, and is red if per-node exactly-once is the intended contract (K2). Setup: `Bracket.ensuring(o => seen += o)(Effect.defer { requestStop(); 1 })`; `Eval.partial` it, then `Eval.release(p, Boom)` twice. Expected: `seen.size == 1`. Currently 2 (one fresh cell per `derive`). Same for `ensuringWith` asserting `init` ran once across both walks. Platforms: all.

**T3. Core, `kyo-core/.../SyncTest.scala` "ensure under interruption": a value handed on at a clean end reaches the caller's `ensureMap` or the guard sees a non-clean ending.** Reproduces a bug (K3). Setup: `Fiber.initUnscoped { handoff.get.map { self => Sync.ensure { outcome => if outcome.isEmpty then Sync.Unsafe.defer { discard(self.unsafe.interrupt()); handedOff.set(true) } else closed.set(true) } { Sync.defer(token) }.ensureMap { t => owned.set(true); Async.never } } }`. Action: complete `handoff` with the fiber, await `fiber.getResult`. Expected: `owned || closed` (someone owns the token). Currently the finalizer reports a clean end, the interrupt lands on `Abort.get`'s poll, and neither flag is set. Platforms: all (single fiber, no second thread).

**T4. Core, `ScopeInterruptTest.scala`: an interrupt at `Scope.run`'s drain await strands the body's value.** Reproduces a bug (C1). Setup: a `Scope.run` whose body registers one finalizer that parks on a `Latch` `gate` (so the drain fiber is parked and `await` is a real join) and returns a `Handle` counted in `opened`; the caller does `.map(h => Scope.acquireRelease(h)(_.close))` on an outer scope. Action: `assertEventually(finalizer.promise.waiters >= 1)` (or observe the drain parked via a counter set inside the finalizer), interrupt the fiber, `gate.release`, await `getResult`. Expected: `closed == opened`. Currently the handle is never registered. Platforms: all.

**T5. Core, `HubTest`: an interrupt landing on `Hub.initUnscopedWith`'s spawn does not orphan the publisher.** Reproduces (C2). Setup: `Fiber.initUnscoped(Hub.use(4) { hub => started.completeUnit.andThen(Async.never) })` with a self-interrupt requested from inside the hub's channel construction is not reachable from the API, so use the stress shape of `ScopeInterruptTest:327` (40 rounds, half interrupting at the spawn) and observe the publisher fiber through `hub.unsafe` or a `Channel.pendingTakes` probe on the internal channel: after `parent.getResult`, `assertEventually(pendingTakes == 0)`. Currently an orphan keeps one pending take. Platforms: all.

**T6. Core, `KyoAppTest`: `runAndBlock` interrupts its fiber on timeout and on interrupt.** Reproduces (C2). Setup: `KyoApp.runAndBlock(10.millis)(gate.await)` under `Clock.withTimeControl`, advance past the deadline, assert the result is `Timeout` and `assertEventually(gate.waiters == 0)`. Currently the fiber stays parked on the gate. Platform: JVM and Native (`block` parks a thread).

**T7. kyo-combinators, `AsyncCombinatorsTest`: interrupting `a &> b` at `left.await` interrupts `right`.** Reproduces (C2). Setup: `left = Async.never`, `right = Sync.ensure(rightReleased.set(true))(gate2.await)`; run `left &> right` in a fiber, `assertEventually(gate2.waiters == 1)`, interrupt, await result, `assertEventually(rightReleased)`. Currently `right` is orphaned. Same shape for `<&` and `<&>`. Platforms: all.

**T8. Core, `AsyncTest` or `FiberTest`: `Async.uninterruptible` does not strand a produced resource.** Reproduces (C4). Setup: `Async.uninterruptible(Sync.defer(Handle(opened)))`, joined by a fiber that is interrupted while parked at the join with the child parked on a `Promise` inside its body; complete the promise after the interrupt. Expected: the handle is closed by someone (the spec accepts either delivery to a caller-registered `ensureMap`, or the child closing it because the mask reports the abandoned consumer). Platforms: all.

**T9. Core, `StreamCoreExtensionsTest`: `mapPar` interrupted at the take of a chunk fiber interrupts that fiber.** Reproduces (C2, C3). Setup: `Stream.init(1 to 4).mapPar(2)(v => Sync.ensure(released.incrementAndGet)(gate.await.andThen(v)))` consumed by `.run` in a fiber; `assertEventually(gate.waiters == 4)`; interrupt the consumer; `gate.release`; expected `assertEventually(released == 4)` before any deadline, and no fiber still parked. Currently the chunk fiber taken out of `channelOut` is orphaned and completes only when the gate opens, unowned. Platforms: all. Companion for `groupedWithin`: interrupt while parked in `pull`; expected the `push` fiber is interrupted (observe through a `Sync.ensure` in the source stream).

**T10. Core, `HubTest`: a listener whose registration is abandoned is removed.** Reproduces (C2, `Hub.listen`). Setup: a hub with one listener registered through `Scope.run(hub.listen(1).andThen(...))` in a fiber that self-interrupts from inside `closed.map`'s step is not reachable; use the 40-round stress with an interrupt at the spawn and assert `hub.listeners` (via a test seam or `Hub.listeners` size accessor) returns to 0 after every round. Platforms: all.

**T11. kyo-system, `CommandTest` (JVM, Native): a stop landing during `spawn` does not orphan the process.** Reproduces (D18). Setup: 40 rounds of `Fiber.initUnscoped(Scope.run(Command("sleep", "30").spawn.andThen(Async.never)))` with half the rounds interrupted immediately; after each round assert the spawned pid (recorded through `onSpawn`-style seam or `pgrep -f` on a unique argv marker) is gone. Currently a round whose stop lands inside `unsafe.spawn()` leaves the process. Platform: JVM, Native.

**T12. Core, `ChannelTest` "parked take under interruption": `takeWith` is the ownership boundary.** Pins a rule (C3). Setup: `Channel.init[Handle](1)`; a taker fiber does `c.takeWith { h => Scope.acquireRelease(h)(_.close) }` under `Scope.run`; a producer puts a handle after the taker parked; interrupt the taker *after* the put (register `parent.interrupt` on the channel's internal take promise via `onComplete`, the ordering `ScopeInterruptTest:207` uses). Expected: the handle is closed (registered in the delivery step). Platforms: all.

**T13. Core, `ChannelTest`: `take` of a resource element abandoned at the next poll is documented and pinned.** Pins a rule (C3): same setup with `c.take.map(h => register)`, expected the handle is *not* closed and the leaf name says why (`take` hands ownership to the continuation); this documents the contract the `close` scaladoc already states. Platforms: all.

**T14. Core, `FiberTest` "uninterruptible": interrupting the caller does not orphan the masked fiber's finalizer.** Pins (C4): the existing leaf checks the shielded fiber runs to completion; add: the caller's `Sync.ensure` finalizer runs, and the shielded fiber's value is observable through a promise the body completes. Platforms: all.

**T15. Core, `QueueTest`: interrupting `Queue.close` at its join discards the elements and still closes the queue.** Pins (C5). Mirror the `Channel.close` scaladoc: after the interrupt, `queue.closed == true` and the elements are gone. Platforms: all.

**T16. Core, `AsyncTest` "timeout": an interrupt landing on `Async.timeout`'s spawn interrupts the child and cancels the sleep.** Pins (C6, the covered half). Setup: `Clock.withTimeControl`; 40 rounds of `Fiber.initUnscoped(Async.timeout(1.hour)(Sync.ensure(childReleased.set(true))(gate.await)))`, half interrupted at the spawn; after `parent.getResult`, if the child started then `assertEventually(childReleased)`; and the controlled clock's pending sleeps are 0 after each round (`clock.unsafe` seam or `Clock.TimeControl` pending count). Platforms: all.

**T17. kyo-sql, `SqlConnectionCancelTest` (container-backed, JVM): an interrupt landing during `closeAll`'s extraction still closes every idle connection.** Reproduces (D1). Setup: warm two connections, take none; wrap `pool.closeAll(1.second)` in a fiber and request the interrupt from inside `pool.close()` (a `raceProbe` seam already exists on `ConnectionPool` at `:46`; set it to `self.unsafe.interrupt()` for the test); await the result; assert both probe descriptors close within the poll loop the handover tests use (`:990-1020`). Currently they leak. Platform: JVM with Postgres and MySQL.

**T18. kyo-sql, `SqlConnectionCancelTest`: custody take and exit registration are one step.** Pins/reproduces (D2). Setup: a lease whose interrupt is requested from inside the `Scope.ensure` registration (needs a seam: a `Frame`-keyed hook in `resolvingOnce`, or run the leaf against a fake `Connection` whose `isOpen` records a close after a release). Expected: exactly one of `recordRelease`/`recordDiscard` fires per connection (metrics), never both. Platform: JVM.

**T19. kyo-sql, `SqlClientTest` (container): `SqlClient.init` interrupted at the pool's clean handover closes the warmed connections.** Reproduces (D3). Setup: `minConnections = 2`; run `Scope.run(SqlClient.init(url, config).andThen(Async.never))` in a fiber; register `parent.interrupt` on the inner finalizer's `await` promise completion (a seam: count `drainPolls`-style, or use a `Clock.withTimeControl`-independent hook that fires when `Runtime.init`'s inner scope drain completes); interrupt; assert the two sockets close (the probe pattern of the handover tests). Currently they stay ESTABLISHED. Platform: JVM.

**T20. kyo-sql, `SqlConnectionCancelTest` (container): an interrupt at the advisory-lock grant releases the lock before the connection is reused.** Reproduces (D4). Setup: pool of one connection; fiber A runs `withAdvisoryLock(key)(...)` and is interrupted while the `pg_advisory_lock` reply is in flight (use `Sql.hang`-style server-side delay: a lock held by a second dedicated connection, released right after A's interrupt is requested, so the grant lands after the interrupt); await A; then fiber B runs `withAdvisoryLock(key, timeout = 1.second)` on the pooled session. Expected: B acquires. Currently B times out (lock held by the pooled session) or, on Postgres, `pg_advisory_unlock` reports the mismatch. Platform: JVM, both engines.

**T21. kyo-browser, `BrowserLauncherJvmTest` (JVM, needs Chrome): an interrupt landing during the spawn leaves no Chrome process.** Reproduces (D5). The stress shape of T11 against `BrowserLauncher.launch`, asserting through `pgrep -f user-data-dir=.*<unique tag>` after each round, with `killOrphans` disabled for the check. Platform: JVM.

**T22. kyo-browser, `CdpBackendTest`: an interrupt at the version probe leaves no drainer fiber.** Reproduces (D6). Setup: the transport-based `initUnscoped(transport, cfg)` with a fake `JsonRpcTransport` whose `getVersion` reply is gated on a `Promise`; run `Scope.run(CdpBackend.init...)` in a fiber; interrupt while parked on the probe; release the gate; assert the dialog queue has no pending take (`Channel.pendingTakes == 0`) and the drainer fiber is done. Platforms: all (fake transport).

**T23. kyo-browser, `BrowserTabTest`: an interrupt at `createBrowserContext`'s reply disposes the context.** Reproduces (D7). Setup: fake transport that records `Target.createBrowserContext` and gates its reply; interrupt the caller while parked; complete the reply; expected a `Target.disposeBrowserContext` for that id arrives. Currently none. Same for `createTarget` and `withPopup`. Platforms: all.

**T24. kyo-browser, `BrowserTest`: `withViewport` interrupted at the override reply restores the viewport.** Reproduces (D8). Setup: fake transport gating `Emulation.setDeviceMetricsOverride`'s reply; interrupt; expected a restore call (or `clearDeviceMetricsOverride`) and `tab.viewportOverride` back to the prior value. Platforms: all.

**T25. kyo-aeron, `TopicRuntimeReleaseTest` (JVM, embedded driver): an interrupt taken as the add completes closes the publication.** Reproduces (D9). Setup: `Topic.publish(uri)(stream)` where the stream's first chunk is gated; instrument through the `AeronTransport` seam so that `pollAddPublication` returning `Done` also requests the fiber's own interrupt (the stop then lands on `Sync.ensure`'s trailing poll); await the fiber; assert `transport.closePublication` was called exactly once for the produced publication. Currently zero. Same for `stream`. Platform: JVM (Native if the transport seam exists there).

**T26. kyo-aeron, `AeronClientTest` (JVM, embedded driver): an interrupt at `clientConnect`'s join closes the client.** Reproduces (D10). Setup: a fake `AeronBindings` whose `clientConnect` returns a fiber gated on a promise; `Scope.run(AeronClient.connect(dir).andThen(Async.never))` in a fiber; interrupt while parked; complete the promise with a handle; assert `closeClient` was called for it. Currently never. Platform: JVM (fake bindings make it cross-platform if the FFI seam allows).

**T27. Core, `FiberTest` "resource safety regressions": a fatal thrown in a fiber's body releases its brackets before the promise completes with the panic.** Pins a rule. Setup: `Fiber.initUnscoped(Sync.ensure(released.set(true))(Sync.defer(throw new StackOverflowError())))`; `getResult` is a `Panic(StackOverflowError)` and `released` is true. Platforms: all (a manually thrown `StackOverflowError` is `IsFatal` without recursion).

**T28. kyo-actor, `PubSubTest`: a subscriber interrupted at the subscribe reply is not left in the set.** Reproduces (D15). Setup: `PubSub.linearized`; subscriber fiber does `Scope.run(pubsub.subscribe(subject).andThen(Async.never))`; register the fiber's interrupt on the actor's reply promise completion (the `onComplete` ordering of `ScopeInterruptTest:207`) so the interrupt lands after the actor answered and before the fiber resumes; await; assert `subscriberCount == 0` and a subsequent `publish` completes. Currently the count stays 1 and `publish` blocks. Platforms: all.

**T29. kyo-compiler, `SpawnBackendTest` (JVM): an interrupt during the Aeron connect kills the worker.** Reproduces (D16). Setup: the existing `onSpawn` seam; interrupt the `SpawnBackend.init` fiber from inside `onSpawn` (which fires before `aeronClient`), await; assert the worker pid is gone. Currently the JVM survives because the kill is armed later. Platform: JVM.

**T30. kyo-system, `CommandTest`: covered by T11.**

**T31. Core, `ScopeInterruptTest`: `Scope.acquireRelease` over an acquire that joins is documented as not atomic.** Pins the limit the sweep relies on (and that `db/Connection.scala:395-400` states): `Scope.acquireRelease(promise.get)(close)` interrupted after the promise completed and before the fiber resumed releases nothing, and the same acquire written as `promise.get` inside a child with the registration in the producing step does. The first half exists at `ScopeInterruptTest:186`; add the leaf name and a scaladoc sentence on `Scope.acquireRelease` naming the shape that is covered (a settled or single-step acquire) and the one that is not.

**T32. Kernel docs: DOC1.** Not a test: replace the paragraph at `kyo-kernel/CONTRIBUTING.md:160` with the current rule (the walk descends deferrals over computations and stops at a settled value; a stopped acquire owes nothing; an `Ensure` in front of a settled value is not applied), cite `EvalTest` "an acquire the park stopped in front of is neither run nor released on abandonment" and `BracketTest` "a bracket whose acquire is interrupted before it finishes owns nothing".
