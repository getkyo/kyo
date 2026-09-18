# "new kernel" issues against the Arrow kernel branch

Branch: `worktree-effervescent-painting-backus`, with `origin/main` merged in, so `git diff origin/main` is exactly the
branch's change. Verdicts below come from reading the branch's sources and tests, not commit messages. A leaf that is
`pendingUntilFixed` or `.ignore`d counts as not fixed.

All of `kyo-kernel`, `kyo-core`, `kyo-prelude`, `kyo-system` and `kyo-aeron` cross-build for JVM, JS, Native and Wasm
(`build.sbt:774`, `:819`, `:864`, `:1236`, `:2376`), so every leaf cited from a `shared/src/test` tree runs on all four
platforms unless the leaf itself carries `.onlyJvm`.

## Summary

| Issue | Title | Verdict | Regression test |
|---|---|---|---|
| #1937 | ReactiveUITeardownTest samples waiter count before observer re-arms | NOT APPLICABLE (fixed on main, not by the branch) | yes, on main |
| #1936 | TopicInvariantsTest intermittently strands concurrent JVM consumers | NOT FIXED | no |
| #1928 | Scope.ensure finalizers can be lost when their fiber is interrupted under load | FIXED | yes |
| #1846 | Sync.ensure does not run its finalizer on a typed Abort | FIXED | yes |
| #1820 | Scope.acquireRelease registers the finalizer after acquire returns | FIXED | yes |
| #1739 | Recursive computation with `Sync.defer` is not stack-safe | FIXED | yes |
| #1735 | Scope and/or Channel losing items | FIXED | yes |
| #1723 | Scope.run finalizer runs out of order under an outer handler | PARTIALLY FIXED | partial (core leaf is pending) |
| #1398 | Stream.take resource safety | PARTIALLY FIXED (see review notes) | partial (Scope variant asserts less than the issue) |
| #1381 | Isolated Scopes | PARTIALLY FIXED | partial (mechanism pinned, requested API absent) |
| #1224 | Strengthen guarantees of Resource/bracket | FIXED | yes |
| #1131 | Hierarchical Resource scopes | FIXED | yes |
| #531 | optimize `map` call after a large number of effect suspensions | FIXED (see review notes) | yes (stack safety by unit tests, throughput by kernel benchmark rows) |

Verdict counts over the 13 issues after review: 8 FIXED, 3 PARTIALLY FIXED, 1 NOT FIXED (#1936), 1 NOT APPLICABLE
(#1937, whose fix the branch inherits from main rather than contributing).

## Review notes

Every cited leaf, line and code claim in the sections below was re-checked against the tree. Two verdicts were
changed on review; the sections keep their original reasoning, which is accurate, and these notes carry the correction.

- #1398: the `Sync.ensure` form of the issue is fixed exactly, and the leaf at `StreamCoreExtensionsTest.scala:1011`
  pins the issue's ordering criterion for it. The `Scope.ensure` form is not: the scope's release runs on the detached
  drain that `Finalizer.close` spawns, and the abnormal-exit path does not await it (the decision recorded under #1723),
  so the issue's own second test, which reads its log as soon as `take(5).run` returns, can observe the log before
  "finalized" lands. The leaf at `:992` counts releases through `assertEventually` for that reason. The verdict is
  therefore PARTIALLY FIXED, with the same missing scenario the section already states, and it resolves together with
  #1723.
- #531: the section says the branch adds no benchmark. That holds for `kyo-bench`, but the branch adds the kernel's own
  JMH suite, `kyo-kernel/jvm/src/jmh/scala/kyo/kernel/bench/KernelBench.scala`, whose rows `trailingMapsStayLinear`,
  `dynamicChainOfMapsStaysLinear`, `dynamicChainOfBindsStaysLinear` and `deferBindUnderTrailingMap` measure exactly the
  title's claim, a map chain after suspensions staying linear, with fifty-row ports for ZIO, cats-effect and Turbolift
  under `bench/cross`. The throughput half is therefore pinned by benchmark rows, and the verdict is FIXED. The gap that
  remains is the one the section names first: the arena's `StateMapBench` still runs at the reduced count.
- Minor: the `EvalTest` leaf cited under #1820 at line 1142 is at line 1145.

---

## #1937 ReactiveUITeardownTest samples waiter count before observer re-arms

**What it asks.** The leaf "bound element replacement does not recursively subscribe to itself" waits for
`renders == 2` and then samples `ref.waiters`, but the render counter is incremented before `Signal.observe` reaches
`holdUntilChanged` and registers its next waiter, so a transient zero is legitimate. The stated fix is to await
`ref.waiters == 1` before capturing `finalWaiters`. The report also notes a second, independent failure of the same
class at the root-scope finalizer assertion, which it attributes to #1928.

**Does the branch address it.** NOT APPLICABLE as branch work. `kyo-ui/shared/src/test/scala/kyo/internal/ReactiveUITeardownTest.scala`
is byte-identical to `origin/main` on this branch; the branch's only `kyo-ui` change is four added lines in
`kyo-ui/js-wasm/src/test/scala/kyo/DomBackendDelegationTest.scala`. The suggested fix already landed upstream in
`56e294a6aa` ("[ui] synchronize teardown subscription assertions (#1953)"): the file now carries
`assertEventually(ref.waiters.map(_ == 1))` at line 78, immediately before `finalWaiters <- ref.waiters` at line 79,
with the comment "Rendering finishes before observe registers its next waiter. Fence that registration separately."

The issue's second half is a different matter. The `assert(!doneBefore)` failure in "root scope waits for an active
reactive change and its nested finalizers" (`ReactiveUITeardownTest.scala:161`) is the #1928 symptom, and the branch's
#1928 work is what would settle it.

**Regression coverage.** `kyo.internal.ReactiveUITeardownTest`, leaf "bound element replacement does not recursively
subscribe to itself", in `kyo-ui/shared/src/test`, running on JVM, JS, Native and Wasm. The fence is the assertion
itself, so the leaf pins its own criterion. Ownership is main's, not the branch's.

**Gaps.** None attributable to the branch. If the issue stays open for its second half, the thing to pin is the
root-scope leaf under the branch's interrupt semantics: set up a root `Scope.run` with a finalizer that blocks on a
promise, interrupt the fiber while a nested reactive change is mid-flight, observe that `rootFinalized.done` is false
before the nested finalizer is released and true after. That leaf already exists at `ReactiveUITeardownTest.scala:157`
and is not modified by the branch, so it acts as the check.

---

## #1936 TopicInvariantsTest intermittently strands concurrent JVM consumers

**What it asks.** `kyo.TopicInvariantsTest`, leaf "two concurrent Topic.stream consumers get distinct subscriptions
(per-emit safety)", times out at the suite's two minute limit on the JVM, with scheduler diagnostics showing no
runnable work while the embedded Aeron driver is still open. The requested first correction is stated concretely:
retain and supervise the publisher fiber, which the test currently starts with `Fiber.initUnscoped` and discards, then
propagate or assert its result so the underlying transport failure surfaces instead of an opaque timeout. The failure
that then appears is expected to name the registration or connection path needing the implementation fix.

**Does the branch address it.** NOT FIXED. `kyo-aeron/shared/src/test/scala/kyo/TopicInvariantsTest.scala` is unchanged
on the branch; the publisher is still spawned and discarded at line 242 (`_ <- Fiber.initUnscoped {`), so an aborted
publication is still invisible and the consumers still block on `consumer1.get` and `consumer2.get`. The branch does
change `kyo-aeron/shared/src/main/scala/kyo/Topic.scala`, but for a different defect: `addPublicationDeadline` and
`addSubscriptionDeadline` now take the driver's token ownership and record the produced handle inside the same
synchronous node as the `Done` poll (`Topic.scala:421-443` and `:520-540`), and `Topic.publish` and `Topic.stream`
register their closers with `ensureMap` rather than `map` (`Topic.scala:256` and `:341`) so an interrupt arriving as
the handle is produced cannot park in front of the registration. That is leak-on-interrupt custody, not the strand.

No file on the branch mentions #1936, and nothing in the branch's diff supervises the publisher or propagates its
result.

**Regression coverage.** None for this issue. The branch's new aeron leaves cover the custody change instead:
`kyo.AeronTransportTest`, leaves "an interrupt on a completed add closes the publication the add produced" and "an
interrupt on a completed add closes the subscription the add produced", in `kyo-aeron/shared/src/test`, running on JVM,
JS, Native and Wasm. Neither touches the stranded-consumer path.

**Gaps.** The concrete missing scenario is the one the issue names. Setup: the same two `Topic.stream` consumers, with
the publisher retained as a fiber value rather than discarded. Action: race the consumers' joins against the
publisher's `getResult`, or register an `onComplete` that fails the test when the publisher settles with an error
before both consumer latches are released. Expected observation: a consumer that never receives its batch fails with
the publisher's transport or registration error, named and attributed, rather than with the suite's two minute
timeout. Until that leaf exists, a regression here is indistinguishable from CI slowness. The gap applies to every
platform kyo-aeron builds for, since the leaf lives in `shared/src/test`.

---

## #1928 Scope.ensure finalizers can be lost when their fiber is interrupted under load

**What it asks.** Under load on Windows CI, a fiber that registered finalizers with `Scope.ensure` and was then
interrupted ran neither of them: the instrumented trail showed `ensured=186`, `marker-ran=185`, `claim=31`,
`finalizer-claimed=30`, with no "Scope finalizer failed" entry, so the finalizers were never attempted rather than
attempted and failed. The reporter's conclusion is that delivery of the `Sync.ensure(finalizer.close)` finalizer on an
interrupted fiber is what goes missing. A commenter supplied a deterministic reproduction: a fiber parked on a masked
(uninterruptible) promise inside `Scope.run`, interrupted and then completed inside one `Sync.Unsafe.defer` node; on
main the scope finalizer never releases the latch and the test times out, and the acceptance criterion is that the
latch releases while the interrupted continuation stays dead (`assert(!didResume)`). The issue's own symptom was
`HostPathLockTest > "repeated interrupted acquisitions leave the path acquirable"`, which #1866 marked ignored.

**Does the branch address it.** FIXED, by three cooperating changes.

1. A parked fiber's owed releases are now run by an abandonment walk rather than by resuming the fiber.
   `IOTask.abandon` clears the remainder, sets `Status.Done`, and calls `Eval.release(remainder, KyoException("fiber
   abandoned"), Tag[Async.Join])` before settling the interrupt
   (`kyo-core/shared/src/main/scala/kyo/scheduler/IOTask.scala:393-404`). The comment there states the ordering the
   issue needs: "The completion comes last: the cascade to what this fiber linked, and every observer of its result,
   run only once its finalizers have." `Status.Done` is what keeps a later schedule from resuming what was released,
   which is the `!didResume` half of the commenter's criterion.
2. The walk itself takes a live `Safepoint` state and restores the caller's afterwards
   (`kyo-kernel/shared/src/main/scala/kyo/kernel/internal/Eval.scala:730-738`), with the comment naming this issue:
   "without it the abandoned regions are not reached and their releases are lost (#1735, and #1928's drain never
   ends)". Without that, the walk runs on the just-interrupted fiber's stopped safepoint and reaches nothing.
3. `Scope.Finalizer` closes without a window. The claim on the queue's backlog and the drain of it are one detached
   fiber spawned as the close's only step (`kyo-core/shared/src/main/scala/kyo/Scope.scala:301-332`), and the comment
   cites the issue: claiming in one step and draining in a continuation "would leave a window between the two steps: an
   interrupt honored there abandons the continuation with the backlog already claimed, and the close that runs from the
   abandonment finds it claimed and rightly leaves it alone, so the finalizers in it never run (#1928)". The
   completion promise is `Promise.Unsafe.initUninterruptible` (`Scope.scala:248`) so an interrupt at a caller's `await`
   cannot travel into the drain and stop the finalizers halfway, also cited to this issue.

**Regression coverage.**

- `kyo.ScopeInterruptTest`, leaf "a fiber interrupted while parked on an uninterruptible promise still runs its scope
  finalizers" (`kyo-core/shared/src/test/scala/kyo/ScopeInterruptTest.scala:396`). This is the commenter's
  deterministic reproduction, structurally identical: uninterruptible promise, interrupt and completion in one
  `Sync.Unsafe.defer`, and both halves of the criterion asserted, `fin == 1` and `!woke`. `kyo-core/shared/src/test`,
  so JVM, JS, Native and Wasm.
- `kyo.ScopeTest`, block "finalizers lost under interrupt (#1928)", leaf "an interrupt racing the close does not stop
  the drain" (`ScopeTest.scala:1115`), 200 rounds of register, signal ready, interrupt, then poll until registered
  count equals released count equals 200. `kyo-core/shared/src/test`, all four platforms.
- `kyo.ScopeTest`, leaf "a finalizer that suspends still completes when the closing computation is interrupted"
  (`ScopeTest.scala:1030`), which is the uninterruptible-promise half specifically: the drain is parked inside a
  finalizer when the closing computation is interrupted, and the finalizer must still run exactly once.
- `kyo.HostPathLockTest`, leaf "repeated interrupted acquisitions leave the path acquirable"
  (`kyo-system/shared/src/test/scala/kyo/PathLockTest.scala:74`). The branch removes the `.ignore("... see #1928")`
  and rewrites the body so it is no longer vacuous: each of 50 rounds arms `HostFileSystem.afterClaimHook` to count
  the claim and complete a promise with the OS lock held, races that promise against the fiber's result before
  interrupting, and finally asserts both that the path becomes acquirable again and that `claims > 0`, so a run where
  no interrupt landed on a held claim fails rather than passes. `kyo-system/shared/src/test`, all four platforms.
- The sibling leaf "an interrupt delivered once the lock is held still releases it" (`PathLockTest.scala:28`) pins the
  single-shot form and documents its own mutation check.

**Gaps.** The load-dependence the issue describes (roughly one lost finalizer per several thousand interrupted
acquisitions under CI load) is only approximated: `PathLockTest` runs 50 rounds and `ScopeTest` 200, both chosen to
keep emulated platforms inside the suite timeout, and the `ScopeTest` comment says as much. A regression that is rarer
than one in a few hundred would not be caught by either. Nothing here is a platform hole; the issue's observed failures
were Windows-only but every leaf runs everywhere.

---

## #1846 Sync.ensure does not run its finalizer when the computation aborts with a typed Abort

**What it asks.** `Sync.ensure`, and therefore `Sync.acquireReleaseWith`, runs its finalizer on normal completion and
on a panic but not on a typed `Abort.fail`, because a typed abort is an effect suspension rather than a thrown
exception or a terminal value, so the handler short-circuits past the finalizer. Resources acquired with
`Sync.acquireReleaseWith` leak whenever the body aborts, with `Meter` named as a live exposure. The stated acceptance
criterion is explicit: "the two `.ignore`d `SyncTest` cases become the regression tests", meaning
`SyncTest.scala:147` "Sync.ensure finalizer is not yet run when the computation aborts via Abort.fail" and the
error-aware variant at `SyncTest.scala:207`.

**Does the branch address it.** FIXED. `Sync.ensure` no longer delegates to the safepoint. It is now built on
`Bracket.ensuringWith` with an `AtomicRef` slot made per run, and it runs `Abort.run[E](v)` inside the region, records
the first error into the slot, and re-raises with `Abort.get` outside
(`kyo-core/shared/src/main/scala/kyo/Sync.scala:138-163`). Because the body always completes as a `Result`, the
region's end is reached on a typed abort as on any other ending, and the release reads the recorded error out of the
slot. The comment states why `ensuringWith` rather than a bracket over a `()` acquire: a bracket installs its region
only when the acquire's value arrives, so a computation abandoned before it ran would get no finalizer.
`Sync.acquireReleaseWith` has the same shape over the real acquire (`Sync.scala:78-107`), with the
first-failure-wins `compareAndSet` so a replaying handler cannot let a later successful branch overwrite an earlier
aborted one. The release is told `Present(new Result.Panic(ex))` for an unwind and the recorded typed error otherwise,
and the "constructed directly" comments explain that `Result.Panic.apply` would refuse a fatal.

The kernel side is `Bracket.ensuringWith` at `kyo-kernel/shared/src/main/scala/kyo/kernel/Bracket.scala:119-127`,
which installs the region before the body's first step, and `Handler.ContextHandler.release` at
`kyo-kernel/shared/src/main/scala/kyo/kernel/internal/Handler.scala:241`, which is what the evaluator calls at the
region's own end.

**Regression coverage.** The issue's own acceptance criterion is met literally: both `.ignore`d stubs are now real
leaves with real assertions.

- `kyo.SyncTest`, leaf "runs finalizer on Abort.fail" (`kyo-core/shared/src/test/scala/kyo/SyncTest.scala:161`),
  asserting both `result == Result.fail("boom")` and `called`.
- `kyo.SyncTest`, leaf "error-aware ensure passes error on Abort.fail" (`SyncTest.scala:225`), asserting the finalizer
  received `Present(Result.Failure("boom"))`, which is stronger than "the finalizer ran": it pins that the typed error
  reaches the finalizer rather than a synthesized panic.
- `kyo.SyncTest`, leaf "releases when the use aborts with a typed error" (`SyncTest.scala:374`), carrying the comment
  "#1846: the use aborting typed, with no Abort.run inside", which is the `acquireReleaseWith` exposure the issue
  names.
- Downstream, `kyo.TopicRuntimeReleaseTest` un-pends three leaves whose `pendingUntilFixed` reasons all named this
  defect: "a body that fails with a typed error still releases the driver" (`:23`), "a publish that fails with a typed
  error still closes its publication" (`:87`) and "a stream that fails with a typed error still closes its
  subscription" (`:102`), in `kyo-aeron/shared/src/test`.

All of these are in `shared/src/test` trees, so JVM, JS, Native and Wasm.

**Gaps.** `Meter` is cited in the issue as a live exposure at four sites and is not covered by a leaf that aborts
through a meter permit specifically. The scenario to add: acquire a `Meter` permit with `Meter.run` or the
`acquireReleaseWith` path, abort the body with a typed `Abort.fail`, and assert `meter.availablePermits` returns to
its pre-acquisition value rather than merely asserting some release counter. `kyo.MeterTest` is modified by the branch
(39 lines) but no leaf name ties to this issue.

---

## #1820 Scope.acquireRelease registers the finalizer after acquire returns

**What it asks.** `Scope.acquireRelease` registered its finalizer in a continuation running after `acquire` returned,
so an interrupt delivered in that gap left the resource acquired and nothing registered to release it. The report
supplies a measured reproduction whose criterion is `leaked == 0` over 500 rounds, where a round counts as leaked when
`claimed && !released`; it observed 500 of 500 leaking, and noted that a fully synchronous single-node acquire leaked
just as reliably as a suspending one. It also names, from inspection rather than measurement, that `Fiber.init` is
`Scope.acquireRelease(initUnscoped(v))(_.interrupt)` and inherits the same window.

**Does the branch address it.** FIXED. `Scope.acquireRelease` now uses `ensureMap` rather than `map`
(`kyo-core/shared/src/main/scala/kyo/Scope.scala:86`) and registers through `finalizer.ensureUnsafe` inside that step
(`Scope.scala:89`), with the comment stating the mechanism: "`ensureMap` registers in the step the acquire completes;
with `map` the registration is a suspension of its own, and a pending interrupt parks before it is dispatched, leaving
the abandonment nothing to release."

`ensureMap` is the new kernel combinator at `kyo-kernel/shared/src/main/scala/kyo/kernel/Pending.scala:98`, routed
through `Arrow.ensure`. Its scaladoc states the contract: `map` polls the safepoint before applying its function, so
an interrupt pending when the value arrives parks and `f` is never reached; `ensureMap` applies `f` as the value
arrives, "so an interrupt lands on either side of the pair". `Scope.Finalizer.ensureUnsafe` exists specifically so the
registration is not itself a suspension (`Scope.scala:176-179`).

At the kernel level the same guarantee is the bracket's: `Bracket.apply` installs its region inside an
`Arrow.Ensure` applied to the acquire (`kyo-kernel/shared/src/main/scala/kyo/kernel/Bracket.scala:81-96`), so the
`Cell.Live` that owns the release is constructed with the acquired value in the same step it arrives. `Fiber.init`
still builds on `Scope.acquireRelease` (`kyo-core/shared/src/main/scala/kyo/Fiber.scala:146`) and so inherits the fix.

`Async`, `Exchange` and `Topic` were converted to `ensureMap` at their own spawn and add sites
(`Async.scala:211`, `Exchange.scala:183`, `Topic.scala:256` and `:341`).

**Regression coverage.**

- `kyo.ScopeTest`, block "acquire-time registration (#1820)": leaf "an interrupt requested inside the acquire still
  releases what it produced" (`kyo-core/shared/src/test/scala/kyo/ScopeTest.scala:1151`), 1000 rounds asserting
  `acquired == released` and `acquired > 0`; leaf "an acquire whose last step follows the interrupt is still
  released" (`:1191`), 200 rounds of the multi-node shape; leaf "a single interrupt requested inside the acquire
  releases what it produced" (`:1224`), the deterministic single shot, asserting the body was interrupted and the
  value released exactly once.
- `kyo.ScopeTest`, leaf "a self-interrupt inside the acquire still releases what the acquire produced"
  (`ScopeTest.scala:646`) is the reporter's own program, 500 rounds, with the reporter's own criterion
  (`leaked == 0` where leaked means acquired and not freed).
- `kyo.ScopeInterruptTest` covers the two surfaces `ScopeTest` does not: leaf "Sync.acquireReleaseWith still releases
  what the acquire produced (value in the same node as the interrupt)" (`:131`) and leaf "Scope.acquire closes the
  handle it opened (value in the same node as the interrupt)" (`:155`). The file's header comment states that split
  explicitly.
- The `Fiber.init` inference in the issue is turned into measured coverage by `kyo.ScopeInterruptTest`, leaves
  "Fiber.use interrupts the fiber it spawned when an interrupt lands on the spawn" (`:327`) and "Fiber.init interrupts
  and awaits the fiber it spawned when the interrupt lands on the spawn" (`:363`).
- At the kernel level, `kyo.kernel.internal.EvalTest`, leaf "a stop landing on the acquire's last step hands the value
  to the bracket before parking" (`kyo-kernel/shared/src/test/scala/kyo/kernel/internal/EvalTest.scala:1142`), whose
  comment cites #1820 directly, plus the two following leaves for the resumed-instead case and the nested-region case.

All in `shared/src/test` trees, so JVM, JS, Native and Wasm.

**Gaps.** None material. One nuance worth stating precisely: the leaves assert that the release eventually runs, using
`assertEventually`, because the drain is detached, so they pin "not leaked" rather than "released before the fiber's
result is observed". That is the issue's criterion, not a weakening of it.

---

## #1739 Recursive computation with `Sync.defer` is not stack-safe

**What it asks.** `def step(n) = if n <= 0 then 0 else Sync.defer(step(n - 1)).map(_ + 1)` at `step(1000000)` throws
`StackOverflowError`, contradicting the documented claim that effectful computations are stack-safe. The acceptance
criterion is the program itself running to completion. fwbrasil's reply on the issue says the new kernel is what
addresses it.

**Does the branch address it.** FIXED. The evaluator no longer holds continuations on the call stack. A `map` whose
receiver is already a `Pending` node builds a `Pending.DeferWith` rather than recursing
(`kyo-kernel/shared/src/main/scala/kyo/kernel/Pending.scala:69-89`), and `Pending.Defer` carries two continuation
slots flat so a composition does not add a node
(`kyo-kernel/shared/src/main/scala/kyo/kernel/internal/PendingInternal.scala:30-46`). `Arrow.apply(v, cont)` passes
the rest of the computation as an argument and reaches the next step as `cont.head(result, cont.tail)` rather than
`cont(result)`, so a composed continuation does not bounce through a composition node
(`kyo-kernel/shared/src/main/scala/kyo/kernel/Arrow.scala:74-89`). The evaluator's loop in
`kyo-kernel/shared/src/main/scala/kyo/kernel/internal/Eval.scala` is what consumes those nodes.

**Regression coverage.**

- `kyo.SyncTest`, leaf "stack-safe when a map follows the recursive defer"
  (`kyo-core/shared/src/test/scala/kyo/SyncTest.scala:76`). It is the issue's program verbatim at the issue's depth of
  1,000,000, and the leaf's comment names the issue: "The leaves above recurse in tail position; the map after the
  recursive defer makes each level leave a cont behind (#1739). The assertion is on the value, so a rescue that unwinds
  by dropping accumulated conts fails too." Asserting `result == depth` rather than merely "did not throw" is what
  makes a drop-the-continuations rescue fail, which is the right criterion.
- At the kernel level, `kyo.kernel.internal.EvalTest`, leaf "deep recursion through map pays rescues only" (`:81`) at
  1,000,000, and leaf "a long map tower evaluates in bounded stack" (`:75`) at 1,000,000 compositions.

`kyo-core/shared/src/test` and `kyo-kernel/shared/src/test`, so JVM, JS, Native and Wasm.

**Gaps.** None. Depth 1,000,000 matches the issue.

---

## #1735 Scope and/or Channel losing items

**What it asks.** With a resource that is an item taken from a `Channel` and released by putting it back
(`Scope.acquireRelease(chan.take)(chan.put)`), items go missing: eight racers under `Async.race` against four items,
repeated, and the drained channel comes back with three of the four. The guarantee the reporter expects is stated
plainly: whatever is taken is always put back. The acceptance criterion is the reporter's program running to
completion with `els.toSet == exp`.

**Does the branch address it.** FIXED, by two changes that both had to land.

1. The release now exists to be run. `Scope.acquireRelease` registers in the step the take's value arrives
   (`kyo-core/shared/src/main/scala/kyo/Scope.scala:86`, see #1820), so a racer interrupted right after taking an item
   already owes the put. The abandonment walk then runs it: `IOTask.abandon` calls `Eval.release` on the remainder
   (`kyo-core/shared/src/main/scala/kyo/scheduler/IOTask.scala:393-404`), and the walk takes a live safepoint state so
   the abandoned regions are reachable, with the comment naming this issue at
   `kyo-kernel/shared/src/main/scala/kyo/kernel/internal/Eval.scala:730`.
2. `Channel` hands back a value delivered into a parked taker whose fiber was interrupted. The branch's `Channel.scala`
   change (88 lines) is what the new `ChannelTest` block covers, and the block's own comment states the contract: "a
   value delivered into that promise as the interrupt lands has to go back to the channel, and a delivery after the
   interrupt has to be refused."

**Regression coverage.**

- `kyo.ScopeTest`, block "racing scopes (#1735)": leaf "the reporter's program leaves every item in the channel"
  (`kyo-core/shared/src/test/scala/kyo/ScopeTest.scala:1481`), which reproduces the reporter's shape (capacity 16
  multi-producer multi-consumer channel, four items, `Scope.acquireRelease(chan.take)(chan.put)`, concurrent users)
  and asserts the drained set equals the expected set; and leaf "every racer that took an item from the channel puts it
  back" (`:1507`), which states the general guarantee with eight racers against four items over 25 rounds, gating the
  interrupts behind a latch that opens only once all four items are held, and asserting both the counts
  (`t <= r && r >= 4`) and the final channel contents (`size == 4 && drained.toSet == Set("1","2","3","4")`).
- `kyo.ChannelTest`, block "parked take under interruption": leaf "a take interrupted while parked leaves a later
  value in the channel" (`kyo-core/shared/src/test/scala/kyo/ChannelTest.scala:157`), the deterministic form, and leaf
  "nothing is lost when parked takers are interrupted under a producer parked on a full ring" (`:174`), 512 items
  through a capacity-one channel with eight racing takers per round, asserting the recovered multiset equals `1 to 512`
  exactly and reporting both lost and extra elements.

`kyo-core/shared/src/test`, so JVM, JS, Native and Wasm.

**Gaps.** The reporter ran 10,000 iterations; the branch runs 25 rounds of the general leaf and one pass of the
reporter's shape. Both leaf comments acknowledge the probabilistic nature and say the round count is what makes a
regression a reliable failure. That is a sensitivity limit rather than a hole, but a rarer regression than roughly one
in 25 would slip through.

---

## #1723 Scope.run finalizer runs out of order when the scoped body short-circuits via an outer handler

**What it asks.** With `Check.runAbort` installed outside `Scope.run`, so that the handler discards `Scope.run`'s
continuation, the expected output is `acquire`, `release`, `after`, and the observed output is `acquire`, `after`,
`release`: the next effect runs while the resource is still held and the finalizer fires late, when the enclosing
region unwinds. The acceptance criterion is the ordering of those three lines. fwbrasil's reply states the intended
fix: move finalizer registration into the kernel so `eval` and `ArrowEffect.handle*` hold the finalizers even when a
handler discards the continuation.

**Does the branch address it.** PARTIALLY FIXED, and the split is precise.

The kernel half is done and is exactly what the reply described. A handler's continuation now carries the regions that
sat between handler and suspension, and the handler releases what they carry at the region's own end
(`kyo-kernel/shared/src/main/scala/kyo/kernel/Region.scala:3-11`). `Stack` keeps a release lane per region and moves a
discarded continuation's releases onto the holder's entry
(`kyo-kernel/shared/src/main/scala/kyo/kernel/internal/Stack.scala:27-29`, `:289-326`).
`Handler.ContextHandler.release` is the hook the evaluator calls (`Handler.scala:241`), and `Bracket.region` supplies
it (`kyo-kernel/shared/src/main/scala/kyo/kernel/Bracket.scala:129-135`). So the finalizer now fires when
`Check.runAbort`'s region ends, not when the outermost region unwinds.

The user-visible half is not done, by an explicit decision the branch records. `Scope.run`'s abnormal-exit path is the
`Sync.ensure(finalizer.close)` backstop (`kyo-core/shared/src/main/scala/kyo/Scope.scala:166`), and
`Finalizer.close` spawns the drain as a detached `Fiber.initUnscoped` and returns without awaiting it
(`Scope.scala:301-332`). `IOTask` schedules rather than running inline, so even a synchronous finalizer such as the
issue's `Console.printLine(">>> release")` runs on the drain, concurrently with `>>> after`. The normal path does
apply backpressure (`finalizer.close(...).andThen(finalizer.await)` at `Scope.scala:161-163`); the abnormal path does
not.

**Regression coverage.**

- Kernel level, green: `kyo.kernel.BracketTest`, block "clause stops and discards", leaves "a handleLoopState clause
  that stops the computation still releases" (`kyo-kernel/shared/src/test/scala/kyo/kernel/BracketTest.scala:822`),
  "every outstanding bracket releases when a clause stops the computation" (`:842`) and "a discarded continuation
  releases every outstanding bracket" (`:855`), the last asserting `released == List("inner", "outer")` so it pins
  order as well as occurrence. Also `kyo.kernel.ArrowEffectMaskTest`, leaf "a bracket inside the mask releases when the
  outer handler discards the continuation".
- Core level, pending: `kyo.ScopeTest`, block "release ordering under an outer handler (#1723)", leaf "a scope
  short-circuited by an outer handler awaits its async release before the next effect"
  (`kyo-core/shared/src/test/scala/kyo/ScopeTest.scala:1353`) is `pendingUntilFixed` with the reason "by decision
  there is no backpressure on abnormal exit: the scope's synchronous releases run, but the detached drain that runs its
  async finalizers is not awaited before the next effect". The leaf's own body is the issue's program shape
  (`Abort.run { Check.runAbort { Scope.run { Scope.ensure(...) andThen Check.require(false, "boom") } } }`), run 50
  rounds so a round that happens to win the race still fails and the pending marker stays stable.

`kyo-kernel/shared/src/test` and `kyo-core/shared/src/test`, so JVM, JS, Native and Wasm.

**Gaps.** Two, and the first is the issue itself.

1. The issue's stated criterion is unpinned by any green leaf. The missing scenario, once the decision changes:
   setup, `Abort.run { Check.runAbort { Scope.run { Scope.acquireRelease(log "acquire")(_ => log "release") andThen
   Check.require(false) } } } andThen log "after"`; action, run it to completion; expected observation, the log reads
   exactly `List("acquire", "release", "after")`. A purely synchronous finalizer is enough, since even that currently
   lands on the detached drain; the existing pending leaf's async finalizer is the stricter version of the same thing.
2. No leaf distinguishes the improvement the branch did make from the old behavior. The missing scenario: the same
   program with a second scope enclosing the whole `Abort.run`, asserting that the inner scope's finalizer ran before
   the outer region unwound rather than with it. That would pin the kernel half at the `Scope` level and would fail on
   `origin/main`, where the release fires at the enclosing region's unwind.

---

## #1398 Stream.take resource safety

**What it asks.** A stream whose emitter is wrapped in `Sync.ensure` or in `Scope.run` plus `Scope.ensure` does not
run its finalizer when the stream is ended early by `.take`. The issue supplies two tests, both with the same
criterion: `emittedValues == Chunk(0, 1, 2, 3, 4)` and `refValues == List("finalized", "4", "3", "2", "1", "0")`, so
the ordering of the finalizer relative to the emitted elements is part of the criterion, not just the fact that it ran.
A follow-up comment adds that finalizers do not run on `Abort` either, with a third test whose criterion is
`called == true`.

**Does the branch address it.** FIXED. `Stream.take` ends the emitter by having its `handleLoopState` clause return
`Loop.done(())`, discarding the continuation (`kyo-prelude/shared/src/main/scala/kyo/Stream.scala:243-259`). The
kernel's release lane then releases whatever regions that continuation carried, which is the same mechanism described
under #1723: `Stack`'s per-region release lane (`Stack.scala:27-29`, `:289-326`) and
`Handler.ContextHandler.release` (`Handler.scala:241`). The `Abort` half of the follow-up is #1846's fix in
`Sync.ensure` (`kyo-core/shared/src/main/scala/kyo/Sync.scala:138-163`).

**Regression coverage.** Block "stream resource cleanup (#1398)" in
`kyo-core/shared/src/test/scala/kyo/StreamCoreExtensionsTest.scala:903`.

- The issue's first test is reproduced exactly by leaf "the finalizer of a taken stream runs after the last element it
  emitted" (`:1011`): same `Sync.ensure` over a `Loop` that logs each index and emits it, same `take(5)`, and the same
  two assertions, `emitted == Chunk(0, 1, 2, 3, 4)` and `entries == List("finalized", "4", "3", "2", "1", "0")`.
- The issue's second test, the `Scope.ensure` variant, is covered more weakly by leaf "Scope.ensure over an unbounded
  stream releases once when take ends it" (`:992`). It asserts `taken == Chunk(0, 1, 2, 3, 4)` and that the release
  count settles at 1, but it reaches that count through `assertEventually` and never asserts the interleaving of the
  finalizer with the emitted elements. The leaf's own comment explains why: `Scope.run`'s close hands its backlog to a
  detached fiber, so the finalizer runs but not before the next effect. This is weaker than the issue's stated
  criterion.
- The `Sync.ensure` count form is leaf "Sync.ensure over an unbounded stream releases once when take ends it"
  (`:976`).
- The follow-up `Abort` test is covered by `kyo.SyncTest`, leaf "runs finalizer on Abort.fail" (`SyncTest.scala:161`),
  and inside the stream setting by leaf "Abort in stream with Scope.ensure"
  (`StreamCoreExtensionsTest.scala:957`).
- Surrounding leaves cover the shapes the issue implies: `take(0)`, `takeWhile` early exit, `Channel.use` with take,
  the peel and hand-out lane (`Emit.runFirst`, `splitAt`, `splitAtWith`, `zip`, `mapPar`), and the custody rule for a
  remainder carried past its enclosing handler's exit.

`kyo-core/shared/src/test`, so JVM, JS, Native and Wasm.

**Gaps.** One, and it is exactly the ordering criterion in the issue's second test. The missing scenario: setup, a
`Stream` whose body is `Scope.run { Scope.ensure(log "finalized") andThen Loop(0)(i => log i.toString andThen
Emit.valueWith(Chunk(i))(Loop.continue(i + 1))) }`; action, `stream.take(5).run` and then await the drain; expected
observation, the log reads exactly `List("finalized", "4", "3", "2", "1", "0")`, not just a count of one. Given the
detached drain, that leaf would need an explicit wait for the release before reading the log, which is what makes it a
different assertion from the existing one rather than a free strengthening. Until it exists, a regression that fired
the `Scope` finalizer at the wrong moment, before the last element rather than after it, would pass the current leaf.

---

## #1381 Isolated Scopes

**What it asks.** When a generic function handles `Scope` on a computation `A < (Async & Scope & S)` where `S` itself
includes `Scope`, the caller's finalizers, passed in through the argument, are run by the callee's `Scope.run`. The
request is for a way to keep them out: "Provide an `Isolate[Scope, Sync, Scope & Sync]` ... Provide a simpler API for
isolating", with a sketched `Scope.isolate(effect) { isolatedEffect => Scope.run { ... } }` used inside the generic
function, and an alternative of an aliased `Scope` type. The closing line says what is really wanted: "a slightly more
convenient API for `Isolate`".

**Does the branch address it.** PARTIALLY FIXED. The capability exists and is new on the branch; the API shape the
issue asked for does not.

`ArrowEffect.Mask` is new (`kyo-kernel/shared/src/main/scala/kyo/kernel/ArrowEffect.scala:581-602`; `origin/main` has
no `Mask` in `kyo-kernel/kyo/kernel`). `Mask[E](v)` turns each operation of `E` inside `v` into a `Mask[E]` operation
carrying the original as an opaque payload, and `Mask.run[E]` is the boundary where those payloads re-raise for the
handlers outside. The scaladoc states that this is not limited to arrow effects: "the region shadows its tag in the
context too, so a `ContextEffect` read inside a mask is answered by the binding outside an inner one", which is what
makes it applicable to `Scope`, a `ContextEffect` (`kyo-core/shared/src/main/scala/kyo/Scope.scala:37`). The scaladoc
also warns about the consequence that matters here: "moving where a value is answered moves where a scope ends with
it."

What the branch does not provide is the callee-side convenience the issue sketched. There is no `Scope.isolate`, no
`Isolate[Scope, Sync, Scope & Sync]`, and no aliased `Scope` type; grepping `kyo-core/shared/src/main` and
`kyo-kernel/shared/src/main` for `Scope.isolate` or `Isolate[Scope` returns nothing. The branch's own test achieves
isolation from the caller's side: `Mask.run[Scope](generic(Mask[Scope](...)))`. The caller must know that `S` contains
`Scope` and must wrap both the argument and the call. The issue's shape has the callee do it.

**Regression coverage.** `kyo.ScopeTest`, block "scope isolation (#1381)", one leaf: "a `Scope.run` inside a generic
function does not run the caller's finalizers" (`kyo-core/shared/src/test/scala/kyo/ScopeTest.scala:1260`). It is
precise about what it pins. It registers two caller finalizers, one in the outer scope and one handed to `generic` in
its argument, and asserts four things: the result is `42`; inside the callee's scope neither caller finalizer has run
(`c == 0`, `i == 0`); and after the outer exit each has run exactly once (`afterCaller == 1`, `afterSupplied == 1`).
The leaf's comment is honest about the model: `Scope` is a `ContextEffect`, so the innermost `Scope.run` answers every
`Scope` suspension in its dynamic extent, and masking is how the caller opts out.

Supporting kernel coverage is `kyo.kernel.ArrowEffectMaskTest`
(`kyo-kernel/shared/src/test/scala/kyo/kernel/ArrowEffectMaskTest.scala`, 379 lines, new on the branch), including
leaves for tunneling past an inner handler, selective masking with other effects staying live, double masking, and the
bracket interactions.

Both trees are `shared/src/test`, so JVM, JS, Native and Wasm.

**Gaps.** Two.

1. No leaf exercises the isolation from inside the generic function, which is the position the issue described. The
   missing scenario: setup, a function `def generic[A, S](effect: A < (Scope & S)): A < (Async & S)` that isolates
   `Scope` on its own, without the caller wrapping anything; action, call it with an argument carrying a caller
   finalizer, from inside an outer `Scope.run`; expected observation, the callee's `Scope.run` runs only its own
   finalizers and the caller's runs at the outer exit. With no callee-side API this cannot be written today, which is
   what keeps the verdict at partial.
2. The existing leaf covers `Scope.ensure` only. The scenario worth adding within the current API: the same masked
   call where the caller's contribution is a `Scope.acquireRelease` rather than a bare `ensure`, asserting that the
   acquired resource is still live when the callee's scope has closed and released only at the outer exit. That pins
   the "unexpectedly executed finalizers" wording of the issue against a real resource rather than a counter.

---

## #1224 Strengthen guarantees of Resource/bracket

**What it asks.** An interruption between acquiring a resource and registering its finalizer leaves the resource
acquired with no finalizer registered. The proposed direction, attributed to fwbrasil in the issue body, is that "the
way finalizers are added to the safe-point may need to be reworked. Instead of just a finalizer thunk, something more
structured representing the acquisition and finalizer". No reproduction or numeric criterion is stated; this is the
general form of what #1820 later measured.

**Does the branch address it.** FIXED, and the branch's design is the one the issue proposed.

The finalizer is no longer a thunk parked on the safepoint. It is a `Bracket.Cell.Live[R]`, which holds the acquired
value as its `state`, the release function, and the exactly-once guard, extending `AtomicBoolean` so
`compareAndSet(false, true)` fires the release once whichever ending reaches it first
(`kyo-kernel/shared/src/main/scala/kyo/kernel/Bracket.scala:49-62`). That is literally "something more structured
representing the acquisition and finalizer". `Bracket.apply` constructs the cell with the acquired value in the step
the value arrives, inside an `Arrow.Ensure`, and installs the region around the use body
(`Bracket.scala:78-97`); the scaladoc states the guarantee: "The release is registered as the acquired value arrives,
with nothing schedulable in between, so an interrupt lands on one side of the pair or the other, never between
acquiring the resource and owing its release."

The old `Safepoint`-based finalizer machinery is gone: `kyo-kernel/shared/src/main/scala/kyo/kernel/internal/Safepoint.scala`
is deleted (238 lines removed) and replaced by the `Stack` release lane
(`kyo-kernel/shared/src/main/scala/kyo/kernel/internal/Stack.scala`, 524 lines, new).
`Scope.acquireRelease` rides the same guarantee through `ensureMap` (`kyo-core/shared/src/main/scala/kyo/Scope.scala:86`).

The cell also carries the two pieces the old thunk could not: `complete()` records that the extent ran to a clean end,
so a release told `Absent` can distinguish a clean ending from a discarded remainder (`Bracket.scala:52-61`), and
`reenter` refuses a continuation that re-enters an already-released bracket with a `kyo.Closed` naming which of the two
causes applies (`Bracket.scala:141-163`).

**Regression coverage.** `kyo.ScopeTest`, block "acquireRelease safety (#1224)"
(`kyo-core/shared/src/test/scala/kyo/ScopeTest.scala:641`), six leaves:

- "a self-interrupt inside the acquire still releases what the acquire produced" (`:646`), 500 rounds, asserting
  `leaked == 0` where leaked means acquired and never freed. This is the issue's defect in its measured form.
- "finalizer runs after normal acquire" (`:687`).
- "acquire failure skips release" (`:696`), asserting both `!released` and `result.isPanic`.
- "ensure on closed scope panics with Closed" (`:710`).
- "concurrent acquireRelease all cleaned up" (`:725`), ten fibers, counter reaches 10.

`kyo.ScopeInterruptTest` extends the same window to the other owners: "a bracket nested as the acquire of another hands
what its use produced to the outer bracket" (`:40`), "an acquire under its own `Sync.ensure` runs that finalizer, and
the bracket never over-releases" (`:78`), "an acquire interrupted a step before its value produces nothing and
releases nothing" (`:110`), plus the `Sync.acquireReleaseWith` and `Scope.acquire` leaves listed under #1820.

At the kernel level, `kyo.kernel.BracketTest` (1944 lines, new) is the exactly-once and release-payload contract:
blocks "bracket", "captured continuations", "finalizer failures", "clause stops and discards", "acquire and release
failure edges", "multi-shot clauses", "release before recovery and fatal failures", "ensuringWith".

All in `shared/src/test` trees, so JVM, JS, Native and Wasm.

**Gaps.** None identified. The issue states no criterion beyond the defect, and the defect is measured.

---

## #1131 Hierarchical Resource scopes

**What it asks.** "Hierarchical scopes allow for early closure of Resources when completed, but can also defer to the
parent scope", with the proposed solution "Attach Child `Resource.Finalizer` to parent Finalizer/Scope". fwbrasil's
comment names the use case: an actor whose mailbox closes either when the actor finishes or when the parent resource
scope closes. A later comment adds streams as a second use case. No reproduction or numeric criterion.

**Does the branch address it.** FIXED. `Scope.Finalizer` now has an explicit parent-child link.
`Scope.Finalizer.addChild` records a nested run as the scope it opened (`kyo-core/shared/src/main/scala/kyo/Scope.scala:191`),
and `Scope.run`'s `derive` calls it, so a nested `Scope.run` joins the enclosing scope as a child
(`Scope.scala:143-151`). `Finalizer.close` closes and awaits its children before releasing any of its own, so inner
resources release before outer (`Scope.scala:307-316`). The scaladoc at `Scope.scala:169-172` states the model and
`Scope.scala:187-190` explains why the link holds the child's finalizer rather than merely waiting on it: a wait would
deadlock, because the child's computation is often ended by one of this scope's own finalizers, which reverse order
runs after the wait.

The counterpart is the fork. `Finalizer.forked` returns a `Forked` view whose `addChild` is a no-op
(`Scope.scala:211-236`, `:229`), so registrations still land in the scope the fork was made in but a run opened inside
a fork is a root rather than a child. The reasoning is at `Scope.scala:193-197`: the fork carries a fiber this scope
does not end, so that run can outlive this one and closing it here would release what its owner is using.

`Fiber.init` is where the actor use case is served. It gives its fiber a standalone scope of its own, and the release
interrupts the fiber, closes the fiber's scope with the fiber's own verdict, and awaits the drain
(`kyo-core/shared/src/main/scala/kyo/Fiber.scala:143-152`). The comment states that the `await` is load-bearing: it
orders the fiber's releases ahead of the enclosing scope's own finalizers, which a bare close does not, since the drain
runs on a detached fiber. That is exactly "closes when it finishes, or when the parent scope closes".

**Regression coverage.** `kyo.ScopeTest`, block "hierarchical scopes (#1131)"
(`kyo-core/shared/src/test/scala/kyo/ScopeTest.scala:1378`), four leaves:

- "a scoped fiber's nested run releases when the scope it was spawned in closes" (`:1383`). Its comment names the
  issue's use case: "#1131's actor mailbox closes on the parent's close by this route." The child parks on
  `Async.never`, so only the parent's close can release it.
- "a scoped fiber's nested run releases before the enclosing scope's own finalizers" (`:1405`), asserting the order is
  `Chunk("inner", "outer")` rather than merely that both ran.
- "a run opened inside an unscoped fiber outlives the scope the fiber was spawned in" (`:1430`), the fork counterpart,
  asserting the resource is still held when the enclosing scope has closed (`onParentExit == 0`) and released at the
  fiber's own exit (`onOwnExit == 1`).
- "a nested run releases before the enclosing scope's own finalizers when the enclosing run is aborted" (`:1458`), the
  abnormal-exit form, again asserting `Chunk("inner", "outer")`.

Supporting leaves in block "finalizer ordering (#1439)": "nested Scope.run releases inner before outer" (`:594`) and
"a nested Scope.run does not run the enclosing scope's finalizers" (`:610`). The streams use case is served by the
block "forks" (`:1292`) and by `StreamCoreExtensionsTest`'s resource leaves.

`kyo-core/shared/src/test`, so JVM, JS, Native and Wasm.

**Gaps.** One, in API shape rather than behavior. `addChild` is `private[kyo]`, so the hierarchy is implicit in nesting
rather than something a user can name; the issue asked for the behavior, not an API, so this does not change the
verdict. The scenario worth adding: an actor-shaped test where the child scope's early completion releases its own
resource before the parent closes, asserting the release happened at the child's own exit rather than at the parent's,
which is the "early closure when completed" half of the issue. The existing leaves all exercise the "defer to the
parent" half, where the child parks forever.

---

## #531 optimize `map` call after a large number of effect suspensions

**What it asks.** Two distinct symptoms in one report. First, rewriting a recursive `Var` program from `flatMap` to a
for-comprehension turns a working program into a `StackOverflowError` at 100,000 iterations. Second, adding an `Sync`
suspension to work around that overflow makes the program roughly 500 times slower, with the reporter having to cut the
iteration count from 100,000 to 1,000 to get a measurable result. The title names the second: optimize `map` after a
large number of effect suspensions. The reporter's framing in the comments is comparative: this is the `cdown` and
`sumh` scenarios from an effect-system benchmark suite, so the criterion is throughput against other libraries.

**Does the branch address it.** PARTIALLY FIXED. The stack overflow is fixed and pinned; the throughput claim is
neither pinned nor measurable from the branch's contents.

For the overflow, the mechanism is the one described under #1739: a `map` over an already-suspended computation builds
a `Pending.DeferWith` node instead of recursing (`kyo-kernel/shared/src/main/scala/kyo/kernel/Pending.scala:69-89`),
`Pending.Defer` carries two continuation slots flat so a composition costs no extra node
(`kyo-kernel/shared/src/main/scala/kyo/kernel/internal/PendingInternal.scala:30-46`), and `Arrow.chain` collapses
compositions against `Arrow.id` by identity rather than building a node
(`kyo-kernel/shared/src/main/scala/kyo/kernel/Arrow.scala:64-72`). `Arrow.apply(v, cont)` reaches the next step as
`cont.head(result, cont.tail)` so a composed continuation does not bounce through its composition node
(`Arrow.scala:74-89`), which is the structural claim relevant to the title.

For throughput, the branch changes no benchmark. `git diff origin/main -- kyo-bench` is empty.
`kyo-bench/src/main/scala/kyo/bench/arena/StateMapBench.scala` is the issue's own program and still runs at `n = 1000`,
the reduced count the reporter had to fall back to; `DeepBindMapBench.scala` runs at depth 10,000. Neither was raised,
and no new benchmark was added, so nothing on the branch demonstrates or guards the improvement the title asks for.

**Regression coverage.**

- `kyo.VarTest`, leaf "stack-safe when a for-comprehension follows a recursive `Var` suspension"
  (`kyo-prelude/shared/src/test/scala/kyo/VarTest.scala:408`). It is the issue's step-1 program at the issue's own
  count of 100,000, and its comment names the issue: "#531's own program, through `Var`'s `ArrowEffect` dispatch. The
  for-comprehension desugars to a map after the recursive suspension, so each level leaves a continuation behind rather
  than recursing in tail position. The assertion is on the value, so a rescue that unwinds by dropping continuations
  fails too." Asserting `Var.run(100000)(program).eval == 0` rather than "did not throw" is the right strength.
- `kyo.SyncTest`, leaf "stack-safe when a map follows the recursive defer" (`SyncTest.scala:76`) covers the step-3
  shape with `Sync.defer` at 1,000,000.
- `kyo.kernel.internal.EvalTest`, leaves "a long map tower evaluates in bounded stack" (`:75`) and "deep recursion
  through map pays rescues only" (`:81`), both at 1,000,000.

`kyo-prelude/shared/src/test`, `kyo-core/shared/src/test` and `kyo-kernel/shared/src/test`, so JVM, JS, Native and
Wasm.

**Gaps.** The throughput half is entirely unpinned. Two concrete items:

1. Missing benchmark scenario. Setup: `StateMapBench` with `n` raised from 1,000 back to the 100,000 the reporter
   originally wanted, which the old kernel could not survive. Action: run the JMH arena comparison against the cats and
   zio arms already in that file. Expected observation: the kyo arm completes and its per-iteration cost scales
   linearly in `n` rather than quadratically, which is the claim behind "optimize `map` after a large number of effect
   suspensions". Without this, a regression to quadratic continuation composition would show up only as CI slowness.
2. Missing unit-level guard for the structural claim. Setup: build a deep chain of suspensions and then apply a large
   number of `map`s after it. Action: measure allocated nodes or evaluate at two sizes. Expected observation: the work
   grows linearly in the number of maps. A leaf asserting a bound on node count would catch a composition regression
   that a stack-safety leaf passes.

Neither gap is platform-specific; `kyo-bench` is JVM-only, so any benchmark-based guard would cover JVM alone, while
the unit-level guard would live in `shared/src/test` and cover all four platforms.

---

## Issues the branch fixes in passing, without the "new kernel" label

Every issue number cited in a test comment on the branch's changed test files was checked against GitHub. The result
is that the branch cites no open unlabeled issue as fixed in passing. The citations found are:

- `#736` ("core: make masked IOPromise not interruptible"), cited in the leaf name "uninterruptible promise cannot be
  interrupted (#736)" added to `kyo-core/shared/src/test/scala/kyo/FiberTest.scala:1244`. That issue is
  already MERGED; the branch is adding coverage for behavior it relies on for #1928's uninterruptible drain promise,
  not fixing it.
- `#208` ("Make Choices handler tail-recursive", CLOSED, labeled `optimization`), cited in a comment added to
  `kyo-prelude/shared/src/test/scala/kyo/ChoiceTest.scala`: "suspensions run on the evaluator's loop, not the call
  stack, so this depth does not overflow." This is the branch restating why the closed issue stays closed under the new
  evaluator.
- `#1172` ("[BUG]: NPE in exception handling(?)", CLOSED), cited as the leaf name "bug #1172 null frames" in
  `kyo-kernel/shared/src/test/scala/kyo/kernel/internal/EffectTraceTest.scala`. The kernel rewrite replaced the whole
  `Trace` implementation (`Trace.scala` deleted, `EffectTrace.scala` added), and this leaf carries the old bug's
  regression guard across that rewrite.
- `#1439` ("[feature]: Change chained Scope release order from FIFO to LIFO", CLOSED) and `#1228` ("[BUG]: strange
  behaviour with IO.ensure", CLOSED) appear as block and leaf names in `ScopeTest` and `SyncTest` respectively, both
  pre-existing guards the branch preserves.

Three labeled issues were also un-pended downstream rather than in their own module, which is worth listing here
because the un-pending is the strongest evidence for the verdict and is easy to miss:

- `kyo.TopicRuntimeReleaseTest` in `kyo-aeron/shared/src/test` drops three `pendingUntilFixed` markers whose stated
  reason was #1846's defect, at lines 23, 87 and 102.
- `kyo.HostPathLockTest` in `kyo-system/shared/src/test` drops the `.ignore` that pointed at #1928, at line 74.
