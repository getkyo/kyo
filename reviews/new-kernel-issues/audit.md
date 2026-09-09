# Audit: the issues labelled "new kernel"

Read at branch `worktree-effervescent-painting-backus`, HEAD `40cfd86298`, clean tree
(the `origin/main` merge is committed as `9f0b6d38b9`). Every line number below was
re-verified against that HEAD; several shifted in the merge.

Method: source reading and test-corpus search only. Nothing was built and nothing was run,
so every "FIXED" here is a claim about what the code does, not a claim that it was observed
doing it. Where running something is what would settle a question, the entry says so.

Two fixes landed during the audit and are reflected below:

- `Finalizer.ensureUnsafe` now runs the release on a detached fiber before throwing `Closed`
  (`kyo-core/shared/src/main/scala/kyo/Scope.scala:229-247`), and
  `kyo-core/shared/src/test/scala/kyo/ScopeTest.scala:208` is un-pinned. This closed the
  residual previously flagged against 1820 and 1224.
- kyo-aeron's three `TopicRuntimeReleaseTest` cases are un-pinned
  (`kyo-aeron/shared/src/test/scala/kyo/TopicRuntimeReleaseTest.scala:23, :87, :102`),
  broadening the coverage of 1846.

## Summary

| # | Verdict | Test today |
|---|---|---|
| 1928 | FIXED | partial, wrong park path |
| 1846 | FIXED | yes |
| 1820 | FIXED | yes |
| 1739 | FIXED | kernel only, not the issue's shape |
| 1735 | PARTIAL | NONE |
| 1723 | PARTIAL | kernel only |
| 1398 | FIXED | yes (count, not order) |
| 1381 | NOT FIXED | NONE (a leaf is mislabelled as one) |
| 1224 | FIXED | yes |
| 1131 | PARTIAL | yes, for the half that landed |
| 531 | PARTIAL (stack yes, throughput unknown) | kernel only |

## The two calls that change what we do next

**Claimed fixed but effectively untested.**

- **1928** is the sharpest case. The fix is a scheduler invariant, and every existing test
  parks on a promise the interrupt cascades to, so none of them exercises the path the fix
  is about. Reverting `onInterrupted`'s unconditional schedule or the `!isPending() ->
  abandon` arm would leave the whole suite green.
- **1739** and the stack half of **531** are guarded only at the kernel layer. Nothing in
  kyo-core drives `Sync.defer` with a growing continuation, which is the shape users write.
- **1723** has a kernel pin but no test at the `Scope` level, and the residual described in
  its entry says the kyo-core composition is probably still wrong today.

**Not fixed at all.**

- **1381** (Isolated Scopes) has no implementation: no `Scope.isolate`, no `Isolate` for
  `Scope`, no `Kyo.isolate`. A test leaf carries the issue number in its title but does not
  exercise the defect, which makes the gap look covered when it is not.
- **1131** (Hierarchical scopes) is half delivered: nesting now composes, parent-initiated
  child close does not exist.
- **531**'s throughput half is a benchmark question, not a test question. No test can assert
  it, and none should be written; the answer is a `StateMapBench` run against a pre-branch
  baseline. Until that runs, "531 is fixed" is a claim about stack safety only.

---

## 1928, Scope.ensure finalizers can be lost when their fiber is interrupted under load

**Verdict: FIXED.** `IOTask.onInterrupted`
(`kyo-core/shared/src/main/scala/kyo/scheduler/IOTask.scala:258`) schedules unconditionally;
`run` (`:334`) claims a non-pending task and calls `abandon()` (`:454`), which runs
`Eval.release(remainder, ..., Tag[Async.Join])` (`:462`). That walks the park's regions and
fires each release
(`kyo-kernel/shared/src/main/scala/kyo/kernel/internal/Eval.scala:553`, `:610`), reaching the
`Bracket.ensuring` cell that `Scope.run` installs at
`kyo-core/shared/src/main/scala/kyo/Scope.scala:170`, via `Cell.drain`
(`kyo-kernel/shared/src/main/scala/kyo/kernel/Bracket.scala:78`). The finalizer no longer
depends on the fiber being rescheduled.

**Coverage: partial.** `kyo-core/jvm-native/src/test/scala/kyo/SyncInterruptTest.scala:23`
"Sync.ensure runs its finalizer for a fiber abandoned before its first slice";
`kyo-core/shared/src/test/scala/kyo/ScopeTest.scala:649` "an interrupted parked fiber runs its
brackets"; `kyo-core/shared/src/test/scala/kyo/AsyncTest.scala:1787` "interrupt runs
Scope.ensure finalizer". All three park on a promise the interrupt cascades to
(`Async.useResult` registers `task.interrupts(v)`,
`kyo-core/shared/src/main/scala/kyo/Async.scala:837`), so they exercise the resumption path,
not the abandonment path the fix is about. `Promise.Unsafe.initMasked` appears in no test file.

**Test to write:** `kyo-core/jvm-native/src/test/scala/kyo/ScopeInterruptTest.scala`. Drive the
reporter's repro verbatim: fork a fiber running
`Scope.run { Scope.ensure(finalized.release).andThen(promise.get.andThen(resumed.set(true))) }`
where `promise` came from `Promise.Unsafe.initMasked`; wait until `promise.waiters == 1`; then
in one `Sync.Unsafe.defer` call `fiber.unsafe.interrupt()` followed by
`promise.unsafe.completeUnitDiscard()`. Assert the latch releases (bounded by `Async.timeout`
as a failure detector only) and that `resumed == false`. The mask is load-bearing: it is what
makes the interrupt cascade a no-op so that only `onInterrupted -> schedule -> abandon` can
save the finalizer.

---

## 1846, Sync.ensure does not run its finalizer when the computation aborts with a typed Abort

**Verdict: FIXED.** `Sync.ensure` (`kyo-core/shared/src/main/scala/kyo/Sync.scala:144`) runs
the body under `Abort.run[E]`, records the first failure in an `aborted` note (`:183`), and
`Bracket.ensuring`'s release reads it and hands it to the finalizer (`:171`), re-raising past
the bracket. `Sync.acquireReleaseWith` does the same (`:78`, `:99`).

**Coverage: yes.** `kyo-core/shared/src/test/scala/kyo/SyncTest.scala:149` "runs finalizer on
Abort.fail" and `:213` "error-aware ensure passes error on Abort.fail", the two leaves the
issue names as `.ignore`d. Also
`kyo-core/jvm-native/src/test/scala/kyo/SyncInterruptTest.scala:70` "runs the finalizer exactly
once when the body aborts", and the three un-pinned aeron leaves at
`kyo-aeron/shared/src/test/scala/kyo/TopicRuntimeReleaseTest.scala:23`, `:87`, `:102`. No
`.ignore(` remains in kyo-core, kyo-kernel, kyo-prelude or kyo-aeron tests.

**Test to write:** none.

---

## 1820, Scope.acquireRelease registers the finalizer after acquire returns

**Verdict: FIXED.** `Scope.acquireRelease` reads the finalizer before the acquire and uses
`ensureMap` (`kyo-core/shared/src/main/scala/kyo/Scope.scala:80`, `:89`), which builds an
`Arrow.Ensure` (`kyo-kernel/shared/src/main/scala/kyo/kernel/Pending.scala:98` ->
`kyo-kernel/shared/src/main/scala/kyo/kernel/Arrow.scala:100`) whose `apply` runs `f` with no
`Safepoint.enter` poll and builds no deferral, so nothing can park between the acquire's value
arriving and the registration. The issue's `Fiber.init` corollary is closed too:
`Fiber.init` now routes the spawn through `Scope.acquireRelease`
(`kyo-core/shared/src/main/scala/kyo/Fiber.scala:129`) and `Fiber.use` through
`Sync.acquireReleaseWith` (`:155`). The adjacent close-versus-register leak is closed by
`Finalizer.ensureUnsafe` running the release on a detached fiber before throwing `Closed`
(`Scope.scala:229-247`).

**Coverage: yes.** `kyo-core/jvm-native/src/test/scala/kyo/ScopeInterruptTest.scala:80`
"Scope.acquireRelease releases what the acquire produced", with `:46` "Sync.acquireReleaseWith
still releases what the acquire produced" as the control, `:112` "Scope.acquire closes the
handle it opened", and `:181` "Fiber.use interrupts the fiber it spawned when an interrupt
lands on the spawn". These hold the acquiring worker inside the acquire's own step until the
interrupt has been sent, which is the issue's exact timing. For the closed-scope half,
`kyo-core/shared/src/test/scala/kyo/ScopeTest.scala:208` "a resource acquired after the scope
closed is released rather than leaked", now un-pinned.

**Test to write:** none.

---

## 1739, Recursive computation with Sync.defer is not stack-safe

**Verdict: FIXED at the source level, not confirmed by running it.** Continuation chains are
heap-held and unwound by the tail-recursive `Eval.loop`
(`kyo-kernel/shared/src/main/scala/kyo/kernel/internal/Eval.scala:48-55` for the `Defer` case,
`:257-280` for the terminal chain walk, which peels one `Arrow.Chain` link per tail call), and
arrow application rescues into a `Defer` node once the safepoint depth budget is spent instead
of recursing (`kyo-kernel/shared/src/main/scala/kyo/kernel/Arrow.scala:52-68`). The maintainer's
own marker is at `kyo-kernel/shared/src/test/scala/kyo/KyoTest.scala:167` and `:182`:
"Diverges from main: deep suspension is stack-safe here."

**Coverage: kernel only, and not in the issue's shape.**
`kyo-kernel/shared/src/test/scala/kyo/KyoTest.scala:169` "suspension at the start"
(n = 100 000); `kyo-kernel/shared/src/test/scala/kyo/kernel/PendingTest.scala:1046`
"construction past the safepoint budget rescues instead of overflowing" and `:1053` "a long map
tower on a rescued computation evaluates in bounded stack" (1 000 000 links).
`kyo-core/shared/src/test/scala/kyo/SyncTest.scala:60` and `:88` are the only `Sync.defer`
stack-safety leaves and both are tail-recursive (no `.map` after the recursive call), so neither
grows a continuation.

**Test to write:** `kyo-core/shared/src/test/scala/kyo/SyncTest.scala`, beside the existing
`"stack-safe"` leaves. Drive the issue's program:
`def step(n: Int): Int < Sync = if n <= 0 then 0 else Sync.defer(step(n - 1)).map(_ + 1)`,
evaluated at 1 000 000. Assert `== 1000000`, the value rather than merely the absence of a
throw, so that a rescue which drops accumulated continuations also fails.

---

## 1735, Scope and/or Channel losing items

**Verdict: PARTIAL.** The two mechanisms this program most plausibly rode on are both fixed
(1820's acquire-and-register window, 1928's lost finalizer on interrupt). But `Async.race`
interrupts the losing fibers and returns without waiting for them to unwind
(`kyo-core/shared/src/main/scala/kyo/Async.scala:236`, whose spawn does
`race.onComplete(_ => fiber.interruptDiscard(...))`), so the reproduction's `chan.drain` can
still run before an interrupted loser's `Scope.run` has completed its `chan.put`. Whether the
reported program now passes needs a run, which I did not do.

**Coverage: NONE.** `Async.race` appears in tests only in `AsyncTest.scala`, `SignalTest.scala`
and `ExchangeTest.scala`, none of them resource-carrying. Nothing anywhere combines it with
`Scope.acquireRelease` or a channel take/put bracket.

**Test to write:** `kyo-core/shared/src/test/scala/kyo/ScopeTest.scala`. Race 8 fibers each
running `Scope.run(Scope.acquireRelease(chan.take)(chan.put))` against a producer that puts 4
known items. Give each racer a `Latch` that its release opens, and await all 8 (bounded by
`Async.timeout` as a failure detector) before draining, rather than draining immediately.
Assert the drained set equals the produced set. Awaiting the releases is the point: it
separates the library guarantee ("what was taken is put back") from the reproduction's own
race, a distinction the issue never made.

---

## 1723, Scope.run finalizer runs out of order when the scoped body short-circuits via an outer handler

**Verdict: PARTIAL.** The kernel half is done, and done as the maintainer described in the
thread: regions crossed by a suspension are dumped and become a debt of the answering handler
(`kyo-kernel/shared/src/main/scala/kyo/kernel/internal/Eval.scala:364`), discharged when that
handler ends (`:122`, `:160`, `:184`, `:224` -> `:495` ->
`kyo-kernel/shared/src/main/scala/kyo/kernel/Bracket.scala:83`).

Residual, still readable in the source and slightly worse after the merge: at the `Scope` level
the discharge reaches only `Sync.ensure(finalizer.close)`
(`kyo-core/shared/src/main/scala/kyo/Scope.scala:170`), and `Finalizer.close` (`:263`) now hands
the queue backlog over asynchronously and still runs the finalizers on a detached fiber (`:285`,
`Fiber.initUnscoped`), its own comment saying "Nothing here waits for that." The only `await` is
`.andThen(finalizer.await)` at `:175`, on `Scope.run`'s normal continuation, which is precisely
what the outer handler discarded. So the release is initiated before the next effect but is not
completed before it, and the issue's literal expected output is not guaranteed.

**Coverage: kernel only.**
`kyo-kernel/shared/src/test/scala/kyo/kernel/BracketTest.scala:1114` "a discarded continuation
releases when its region completes, before the handler's continuation", which asserts
`List("acquire", "release 1", "after -1")`, the issue's ordering verbatim. Also `:769` "a
discarded continuation releases every outstanding bracket" and `:782` "does not release when the
acquire never completes". No kyo-core test runs the reported program; `Check.runAbort` appears
in exactly one test file repo-wide (`kyo-prelude/shared/src/test/scala/kyo/CheckTest.scala`) and
never with `Scope`.

**Test to write:** `kyo-core/shared/src/test/scala/kyo/ScopeTest.scala`. Drive the issue's
program with an event log in place of `Console`:
`Abort.run { Check.runAbort { Scope.run { Scope.acquireRelease(log("acquire"))(_ => log("release")).map(_ => Check.require(false, "boom")) } } }.andThen(log("after"))`.
Assert `log == Chunk("acquire", "release", "after")`. Expect this to fail today; that is the
point, since it converts the residual above from prose into a red leaf.

---

## 1398, Stream.take resource safety

**Verdict: FIXED for the reported cases.**

**Coverage: yes, for the count.**
`kyo-core/shared/src/test/scala/kyo/StreamCoreExtensionsTest.scala:976` "Sync.ensure over an
unbounded stream releases once when take ends it" is the issue's first test, asserting
`taken == Chunk(0, 1, 2) && r == 1`. Around it, the `"stream resource cleanup (#1398)"` block at
`:903`, including `:916` "Scope.run wrapping stream take"; and the hand-out family including
`:1137` "zip releases the side it drops when the other side ends" and `:1151` "a pipe that stops
early releases the source it drops".

Still open in the same family: three branch-added `pendingUntilFixed` leaves, `:1205`
"mergeHaltingLeft releases the halted side's resource when the merged stream ends", `:1228`
"merge releases a producer's resource when the consumer stops", and `:1251` "mapPar releases an
element's resource when the consumer stops". Added by `89d4aa5ec4`, absent from `origin/main`.
Same problem as 1398 (running effects only in part), different combinator: fibers spawned
unscoped and never interrupted when the consumer stops.

**Tests to write:** two leaves in the `#1398` block of
`kyo-core/shared/src/test/scala/kyo/StreamCoreExtensionsTest.scala`.

1. The issue's second test, which has no counterpart: an unbounded `Loop` plus `Emit` under
   `Scope.run` / `Scope.ensure` inside the `Stream`, consumed with `.take(5)`. Every existing
   `Scope.run`-inside-a-`Stream` leaf (`:1007`, `:1057`) uses a bounded emitter.
2. Ordering, which nothing asserts today: log each element and the finalizer into one
   `AtomicRef` and assert the full sequence `List("finalized", "4", "3", "2", "1", "0")`. As it
   stands, a finalizer that fires at the wrong moment but the right number of times passes.

---

## 1381, Isolated Scopes

**Verdict: NOT FIXED.** There is no `Scope.isolate`, no `Isolate` instance for `Scope`, and no
`Kyo.isolate`: a repo-wide grep over sources, tests and docs returns nothing. `Scope`'s entire
public surface is `ensure` (two overloads), `acquireRelease`, `acquire` and `run` (two
overloads), at `kyo-core/shared/src/main/scala/kyo/Scope.scala:50, 66, 80, 107, 122, 140`,
unchanged. `Scope` remains a plain `ContextEffect[Scope.Finalizer]` (`:37`), so the innermost
`Scope.run` handles every `Scope` suspension in its dynamic extent, caller-supplied ones
included.

**Coverage: NONE, and one leaf is mislabelled as coverage.**
`kyo-core/shared/src/test/scala/kyo/ScopeTest.scala:864` is titled `"scope isolation (#1381)"`,
but its single leaf (`:866`, "Scope.run on generic effect type with Scope should not run
caller's finalizers") calls `handleScoped(Sync.defer(42))`, a body with no `Scope` suspensions
at all. It only checks that a nested `Scope.run` does not fire the outer scope's finalizer. It
never passes a caller-supplied `A < (Scope & S)` through the generic function, which is the
entire defect.

**Test to write:** `kyo-core/shared/src/test/scala/kyo/ScopeTest.scala`, joining or replacing
the leaf at `:866`. Define
`def generic[A, S](effect: A < S): A < (Async & S) = Scope.run(Sync.defer(()).andThen(effect))`
and call it as
`Scope.run { Scope.ensure(caller.incrementAndGet.unit).andThen(generic(Scope.ensure(inner.incrementAndGet.unit).andThen(42))) }`.
Assert that the caller's finalizer has not run when `generic` returns, and has run exactly once
when the outer `Scope.run` exits. Red until the isolation API exists, which is the honest state.

---

## 1224, Strengthen guarantees of Resource/bracket

**Verdict: FIXED for the window it names.** The issue asks for structure in place of a bare
thunk at the safepoint, and that is what landed: `Bracket` makes the release a kernel region
carrying a `Cell` with a CAS exactly-once guard
(`kyo-kernel/shared/src/main/scala/kyo/kernel/Bracket.scala:47-101`) built as the acquire is
applied (`:103`, `:109`), and `Scope.acquireRelease` gets the same atomicity through `ensureMap`
(`kyo-core/shared/src/main/scala/kyo/Scope.scala:89`). The registration-on-a-closed-scope leak
is closed at `Scope.scala:229-247`.

**Coverage: yes.** `kyo-core/jvm-native/src/test/scala/kyo/ScopeInterruptTest.scala:46`, `:80`
and `:112` are the guards that actually pin the interrupt-in-the-window case, since they hold
the acquiring worker inside the acquire until the interrupt has been sent.
`kyo-core/shared/src/test/scala/kyo/ScopeTest.scala:208` covers the closed-scope half. Kernel
side: `kyo-kernel/shared/src/test/scala/kyo/kernel/BracketTest.scala:735-790` (the "clause stops
and discards" block) and `:782` "does not release when the acquire never completes". Note that
the block literally tagged `"acquireRelease safety (#1224)"`
(`kyo-core/shared/src/test/scala/kyo/ScopeTest.scala:567`) contains no interrupt and should not
be cited on its own.

**Test to write:** none.

---

## 1131, Hierarchical Resource scopes

**Verdict: PARTIAL. The feature as asked is not delivered.** What landed: a nested `Scope.run`
registers exactly one await-me finalizer in the enclosing scope
(`kyo-core/shared/src/main/scala/kyo/Scope.scala:158-165`, `derive` ->
`enclosing.ensureIfOpen(_ => finalizer.await)`), so a parent no longer exits while a nested
scope is still releasing. What did not land: `fork` and `join` are the identity again (`:166`,
`:167`); there is no parent-to-child close propagation, and no API for creating or attaching a
child scope other than nesting `Scope.run`, which predates the branch. Commit `0a79a67e91` built
the children-set version and its own message names the blocker: "closing a scope does not stop
the computation running under it, so a parent's await is vacuous"; `2338ac9fa8` superseded it.

**Coverage: yes, for the half that landed.**
`kyo-core/shared/src/test/scala/kyo/ScopeTest.scala:538` "nested Scope.run releases inner before
outer", `:847` "Scope.run wrapping Scope.run", and
`kyo-core/jvm-native/src/test/scala/kyo/ScopeInterruptTest.scala:146` "Scope.run waits for a
scoped fiber to release the bracket it is inside".

**Test to write:** none yet. The untested half (a parent closing a still-live child) does not
exist as a feature, so a test would be specifying it rather than guarding it. This needs a
design decision first.

---

## 531, optimize `map` call after a large number of effect suspensions

**Verdict: PARTIAL. Stack-overflow half FIXED, throughput half unknown from source.** Same
mechanism as 1739 (`kyo-kernel/shared/src/main/scala/kyo/kernel/internal/Eval.scala:48-55`,
`:257-280`; `kyo-kernel/shared/src/main/scala/kyo/kernel/Arrow.scala:52-68`), marked by the
maintainer at `kyo-kernel/shared/src/test/scala/kyo/KyoTest.scala:167` and `:182`.

**Coverage: stack half kernel-only; throughput half has none, and none is possible as a test.**
Stack half: `kyo-kernel/shared/src/test/scala/kyo/KyoTest.scala:169` "suspension at the start"
and `:184` "multiple effects"; `kyo-kernel/shared/src/test/scala/kyo/kernel/PendingTest.scala:1034`
"deep map chains evaluate", `:1040` "deep nested computations evaluate", `:1046` and `:1053`.
Throughput half: the measurement surface is benchmarks,
`kyo-bench/src/main/scala/kyo/bench/arena/StateMapBench.scala` (literally the issue's program at
n = 1000, with cats and zio comparators),
`kyo-bench/src/main/scala/kyo/bench/arena/DeepBindMapBench.scala`, and the branch's new
`kyo-kernel/jvm/src/jmh/scala/kyo/kernel/bench/KernelBench.scala:376` and `:386`
(`dynamicChainOfMapsStaysLinear`, `dynamicChainOfBindsStaysLinear`) with cats, zio and
zio-blocks counterparts.

**Test to write:** the 1739 leaf above covers the stack half at the `Sync` level. For the
throughput half there is no test to write, and none should be: the answer is a `StateMapBench`
run against a pre-branch baseline. Until that runs, "531 is fixed" is a claim about stack safety
only.

---

## Ranked: tests to write

Ordered by how silently the bug returns if the test is absent.

1. **1928, the masked-park leaf**, in
   `kyo-core/jvm-native/src/test/scala/kyo/ScopeInterruptTest.scala`. Every existing test parks
   on a promise the interrupt cascades to (`Async.scala:837`), so deleting `onInterrupted`'s
   unconditional schedule or the `!isPending() -> abandon` arm leaves the suite green. This is
   the path the fix is actually about, and the reporter handed over a deterministic
   reproduction for it.
2. **1723, the Scope-level ordering leaf**, in `kyo-core/shared/src/test/scala/kyo/ScopeTest.scala`.
   Not a regression risk but a current one: the kernel pin passes while the kyo-core
   composition is, by the reading above, still wrong. Writing it settles the question either
   way.
3. **1381, the generic-function leaf**, in `kyo-core/shared/src/test/scala/kyo/ScopeTest.scala`.
   No test exercises the defect, and the existing leaf's title makes the issue look covered.
   That mislabelling is worse than having nothing.
4. **1739 and 531, the `Sync.defer` growing-continuation leaf**, in
   `kyo-core/shared/src/test/scala/kyo/SyncTest.scala`. The kernel tests guard the mechanism,
   but nothing exercises it at the layer users write, so a change to `Effect.deferInline`'s node
   shape would land silently.
5. **1398, the ordering assertion and the `Scope.ensure` unbounded variant**, in
   `kyo-core/shared/src/test/scala/kyo/StreamCoreExtensionsTest.scala`. Counts are asserted,
   order is not, so a finalizer firing at the wrong moment but the right number of times passes.
6. **1735, the race-with-releases leaf**, in `kyo-core/shared/src/test/scala/kyo/ScopeTest.scala`.
   A real gap, but the residual sits in the reproduction's own timing more than in the library,
   so it is the least likely to hide a regression.
7. **1131**: nothing until parent-to-child close is designed. **531 throughput**: a benchmark
   run, not a test.

## Other symptoms still readable in the current source

- The 1723 residual above (`Scope.scala:170` versus `:175`, and `:263-292`).
- The 1735 race non-wait (`Async.scala:236`).
- 1381 in full: no isolation API of any kind.
- The three open stream-combinator release gaps
  (`StreamCoreExtensionsTest.scala:1205`, `:1228`, `:1251`), in 1398's family but at
  `mergeHaltingLeft`, `merge` and `mapPar` rather than `take`.
- `Arrow.Ensure.apply` (`kyo-kernel/shared/src/main/scala/kyo/kernel/Arrow.scala:100-109`) is
  the one arrow with no safepoint-budget rescue, unlike `Arrow.apply` and `Arrow.recursive`
  (`:52-68`, `:71-87`). That is deliberate, since it is what makes acquire-and-register atomic
  for 1820, but it means a long chain of consecutive `Ensure` arrows recurses on the JVM stack
  with no rescue. Likely unreachable in practice, because each `Scope.acquireRelease` sits
  behind a `Sync.defer`; noted because it is the one exception.

---

# Tests to add

One entry per issue, all eleven, nothing dropped. Each gives the target file, the leaf name as
it will read, the fixture, the assertions with expected values, whether it is plain or
`pendingUntilFixed`, and any API the test needs that does not exist yet.

**Two repo constraints every entry respects.**

- No dependence on the real clock. Coordination is by `Latch`, `Promise`, `Fiber.get` or
  `assertEventually` (`kyo-test/api/shared/src/main/scala/kyo/test/internal/TestBase.scala:449`);
  where virtual time is needed, `Clock.withTimeControl`
  (`kyo-core/shared/src/main/scala/kyo/Clock.scala:325`). `Async.timeout` appears only as a
  failure detector, never as a pass condition: a `Timeout` is the evidence that something never
  ran, and no assertion passes because of elapsed time.
- Every test file shares a name prefix with the source it covers, so no entry below creates a
  new file. All of them land in existing files: `ScopeTest.scala` and `ScopeInterruptTest.scala`
  cover `Scope.scala`, `SyncTest.scala` and `SyncInterruptTest.scala` cover `Sync.scala`,
  `StreamCoreExtensionsTest.scala` covers `StreamCoreExtensions.scala`, `VarTest.scala` covers
  `Var.scala`.

**How `pendingUntilFixed` behaves here, which is why the assertions below are never weakened.**
kyo-test's marker is a tripwire in both directions: a still-failing body reports Pending, and a
now-passing body reports **Failed** with "remove the pendingUntilFixed marker"
(`kyo-test/runner/shared/src/test/scala/kyo/LiveCoverageTest.scala:350-368`). So the assertion
states what should be true and the marker states that it is not true yet; the moment the
behaviour lands, the leaf goes red and asks to be un-marked.

**One divergence from the assignment, with its reason.** The 531 stack half is marked plain
below, not `pendingUntilFixed`. By the reading in the 531 entry that half is already fixed
(`kyo-kernel/shared/src/test/scala/kyo/KyoTest.scala:167` and `:182` assert the real answer
where main was pending-until-fixed), so the marker would trip on a passing body and report
Failed rather than Pending. The instruction is still honoured: write it plain, run it once, and
if it fails, add the marker with the reason string given in that entry. I have not run it, so
that fallback is real rather than rhetorical.

---

### 1928, Scope.ensure finalizers lost on interrupt (FIXED)

- **File:** `kyo-core/jvm-native/src/test/scala/kyo/ScopeInterruptTest.scala`
- **Leaf:** `"a fiber interrupted while parked on a masked promise still runs its scope finalizers"`
- **Existing coverage is a different path, so this is an addition, not a replacement.**
  `SyncInterruptTest.scala:23`, `ScopeTest.scala:649` and `AsyncTest.scala:1787` all park on a
  promise the interrupt cascades to (`Async.useResult` registers `task.interrupts(v)`,
  `kyo-core/shared/src/main/scala/kyo/Async.scala:837`), so all three are answered by the
  resumption path and stay green if the abandonment path is deleted. Keep them; they are valid
  coverage of the resumption path. This leaf forces the other one.
- **Fixture:** build the parked promise with
  `Sync.Unsafe.defer(Promise.Unsafe.initMasked[Unit, Any]().safe)`
  (`kyo-core/shared/src/main/scala/kyo/Fiber.scala:653`, `.safe` at `:674`, `Promise` exported
  top-level at `:46`). Its `preInterrupt()` returns `false`, which is what makes the cascade a
  no-op. Fork with `Fiber.initUnscoped` a body of
  `Scope.run { Scope.ensure(finalized.release).andThen(promise.get.andThen(resumed.set(true))) }`.
  Wait for the fiber to be genuinely parked with `assertEventually(promise.waiters.map(_ == 1))`
  (`Fiber.scala:574`). Then, inside a single `Sync.Unsafe.defer` node so the order is fixed,
  call `fiber.unsafe.interrupt()` and then `promise.unsafe.completeUnitDiscard()`.
- **Assertions:** `finalized.await` completes, wrapped in
  `Abort.run[Timeout](Async.timeout(3.seconds)(finalized.await))` purely as a failure detector,
  and `out.isSuccess` is true; `resumed.get == false`, proving the queued continuation did not
  run after cancellation.
- **Marker:** plain.
- **New API needed:** none. `Promise.Unsafe.initMasked`, `.safe`, `waiters`,
  `unsafe.interrupt()` and `unsafe.completeUnitDiscard()` all exist.

### 1846, Sync.ensure on a typed Abort (FIXED)

- **File:** `kyo-core/shared/src/test/scala/kyo/SyncTest.scala`, in the `"acquireReleaseWith"`
  block at `:324`
- **Leaf:** `"releases when the use aborts with a typed error"`
- **Existing coverage exercises the fixed path for `Sync.ensure`:** `SyncTest.scala:149` and
  `:213`, plus `SyncInterruptTest.scala:70` and the three un-pinned aeron leaves at
  `kyo-aeron/shared/src/test/scala/kyo/TopicRuntimeReleaseTest.scala:23`, `:87`, `:102`. The gap
  is the sibling the issue names as the live exposure: `Sync.acquireReleaseWith` has no leaf for
  a bare typed abort. `:161` covers only the reify-and-re-raise workaround, `:346` a panic, and
  `:369` an acquire panic.
- **Fixture:** `Abort.run[String](Sync.acquireReleaseWith(Sync.defer("resource"))(_ => Sync.defer { released += 1 })(_ => Abort.fail("boom")))`,
  with no reification inside the bracket.
- **Assertions:** `result == Result.fail("boom")` and `released == 1`.
- **Marker:** plain.
- **New API needed:** none.

### 1820, Scope.acquireRelease registers after acquire (FIXED)

- **File:** `kyo-core/jvm-native/src/test/scala/kyo/ScopeInterruptTest.scala`, in the
  `"an interrupt landing while the acquire's last step runs"` block at `:42`
- **Leaf:** `"Fiber.init interrupts and awaits the fiber it spawned when the interrupt lands on the spawn"`
- **Existing coverage exercises the fixed path:** `:80`, `:46`, `:112` and `:181`, plus
  `ScopeTest.scala:208` for the closed-scope half. The narrow gap is `Fiber.init` itself:
  `:181` covers `Fiber.use`, and `:146` exercises `Fiber.init` for the scope-exit wait rather
  than for an interrupt landing on the spawn.
- **Fixture:** mirror `:181`. Each of 40 rounds runs
  `Fiber.initUnscoped(Scope.run(Fiber.init(child).andThen(started.await)))` where `child` is
  `Sync.ensure(childAlive.set(false).andThen(torn.release))(childAlive.set(true).andThen(started.release).andThen(gate.await))`,
  interrupts the parent as close to the spawn as possible, awaits `parent.getResult`, then waits
  on `torn.await` under `Abort.run[Timeout](Async.timeout(300.millis)(...))` as a failure
  detector and releases `gate`.
- **Assertions:** the count of rounds where the timeout fired **and** `childAlive.get` is still
  true equals `0`. Assert on the count, not a proportion: one escape is a real one.
- **Marker:** plain.
- **New API needed:** none.

### 1739, recursive Sync.defer stack safety (FIXED)

- **File:** `kyo-core/shared/src/test/scala/kyo/SyncTest.scala`, beside the existing
  `"stack-safe"` leaves at `:60` and `:88`
- **Leaf:** `"stack-safe when a map follows the recursive defer"`
- **Existing coverage takes a different path:** `:60` and `:88` are both tail-recursive, so
  neither grows a continuation; the growing-continuation shape is guarded only at the kernel
  layer (`KyoTest.scala:169`, `PendingTest.scala:1053`).
- **Fixture:** the issue's program,
  `def step(n: Int): Int < Sync = if n <= 0 then 0 else Sync.defer(step(n - 1)).map(_ + 1)`,
  evaluated at `1000000`. No fibers, no clock, no interrupts.
- **Assertions:** `step(1000000) == 1000000`. The value rather than the mere absence of a throw,
  so a rescue that drops accumulated continuations also fails.
- **Marker:** plain. If it overflows when run, mark it
  `.pendingUntilFixed("a map after a recursive Sync.defer grows the continuation, and unwinding it overflows the stack at depth 1000000")`
  and leave the assertion exactly as written.
- **New API needed:** none.

### 1735, Scope and/or Channel losing items (PARTIAL)

Two leaves, because the issue's guarantee and the issue's program are not the same claim.

**Leaf A, the library guarantee.**

- **File:** `kyo-core/shared/src/test/scala/kyo/ScopeTest.scala`
- **Leaf:** `"every racer that took an item from the channel puts it back"`
- **Fixture:** a `Channel.init[String](16, Access.MultiProducerMultiConsumer)` inside
  `Scope.run`. Race 8 fibers, each running
  `Scope.run(Scope.acquireRelease(chan.take)(chan.put))`, against a producer that puts 4 known
  items. Give each racer a `Latch` its release opens; await all 8 under
  `Abort.run[Timeout](Async.timeout(3.seconds)(...))` as a failure detector **before** draining.
- **Assertions:** `drained.toSet == Set("1", "2", "3", "4")`, and every one of the 8 release
  latches opened (`out.isSuccess`).
- **Marker:** plain. Awaiting the releases is what makes this the library's actual contract,
  "what was taken is put back", rather than a claim about when it is put back.
- **New API needed:** none.

**Leaf B, the issue's program verbatim.**

- **File:** `kyo-core/shared/src/test/scala/kyo/ScopeTest.scala`
- **Leaf:** `"a raced scope has finished releasing by the time race returns"`
- **Fixture:** identical to Leaf A but with the reporter's ordering: drain immediately after the
  race returns, with no wait on the losers.
- **Assertions:** `drained.toSet == Set("1", "2", "3", "4")`.
- **Marker:**
  `.pendingUntilFixed("Async.race interrupts the losing fibers and returns without waiting for them to unwind, so a loser's Scope.run may still be putting its item back when the drain reads the channel")`
- **New API needed:** none, but this leaf encodes an open semantic question rather than an
  agreed defect: whether `Async.race` should await its losers' releases
  (`kyo-core/shared/src/main/scala/kyo/Async.scala:236`). Land it only alongside that decision;
  if the answer is that it should not, delete this leaf and keep Leaf A.

### 1723, Scope.run finalizer ordering under an outer handler (PARTIAL)

- **File:** `kyo-core/shared/src/test/scala/kyo/ScopeTest.scala`
- **Leaf:** `"a scope short-circuited by an outer handler has released before the next effect runs"`
- **Existing coverage is kernel-level only:** `BracketTest.scala:1114` asserts this ordering for
  a bare bracket. Nothing asserts it through `Scope.run`.
- **Fixture:** the issue's program with an event log in place of `Console`. Into an
  `AtomicRef[Chunk[String]]`, run
  `Abort.run { Check.runAbort { Scope.run { Scope.acquireRelease(log("acquire"))(_ => log("release")).map(_ => Check.require(false, "boom")) } } }.andThen(log("after"))`.
  No fibers of the test's own, no clock.
- **Assertions:** `log.get == Chunk("acquire", "release", "after")`.
- **Marker:**
  `.pendingUntilFixed("the outer handler discards Scope.run's continuation, so only the Sync.ensure backstop fires and Finalizer.close runs the finalizers on a detached fiber that nothing awaits")`
- **New API needed:** none to write the test. To make it pass, `Scope.run`'s discarded-path
  close needs something the synchronous release position can wait on, which does not exist
  today: `Finalizer.close` (`kyo-core/shared/src/main/scala/kyo/Scope.scala:263`) always detaches
  via `Fiber.initUnscoped` (`:285`) and the only `await` is on the normal continuation (`:175`).

### 1398, Stream.take resource safety (FIXED)

Two leaves, both in `kyo-core/shared/src/test/scala/kyo/StreamCoreExtensionsTest.scala`, in the
`"stream resource cleanup (#1398)"` block at `:903`.

**Leaf A, the issue's second test, which has no counterpart.**

- **Leaf:** `"Scope.ensure over an unbounded stream releases once when take ends it"`
- **Existing coverage takes a different path:** `:976` covers the `Sync.ensure` half; every
  `Scope.run`-inside-a-`Stream` leaf (`:1007`, `:1057`) uses a bounded emitter, so none exercises
  early termination of an unbounded one.
- **Fixture:**
  `Stream { Scope.run { Scope.ensure(released.incrementAndGet.unit).andThen(Loop(0)(i => Emit.valueWith(Chunk(i))(Loop.continue(i + 1)))) } }`,
  consumed with `.take(5).run`.
- **Assertions:** `taken == Chunk(0, 1, 2, 3, 4)` and `released.get == 1`.
- **Marker:** plain.

**Leaf B, the ordering nothing asserts today.**

- **Leaf:** `"the finalizer of a taken stream runs after the last element it emitted"`
- **Fixture:** the issue's own shape. One `AtomicRef[List[String]]` receives both the elements
  and the finalizer:
  `Stream { Sync.ensure(ref.getAndUpdate("finalized" :: _))(Loop(0)(i => ref.getAndUpdate(i.toString :: _).andThen(Emit.valueWith(Chunk(i))(Loop.continue(i + 1))))) }`,
  consumed with `.take(5).run`.
- **Assertions:** `emitted == Chunk(0, 1, 2, 3, 4)` and
  `ref.get == List("finalized", "4", "3", "2", "1", "0")`. The existing leaves assert the
  release count only, so a finalizer firing at the wrong moment but the right number of times
  passes today.
- **Marker:** plain.
- **New API needed:** none for either leaf.
- **Not duplicated here:** the three open combinator gaps already have leaves in this file at
  `:1205`, `:1228` and `:1251`, each `pendingUntilFixed` with its own reason. Leave them as they
  are.

### 1381, Isolated Scopes (NOT FIXED)

- **File:** `kyo-core/shared/src/test/scala/kyo/ScopeTest.scala`, in the
  `"scope isolation (#1381)"` block at `:864`
- **Leaf:** `"a Scope.run inside a generic function does not run the caller's finalizers"`
- **Existing coverage does not exercise the defect and should be relabelled.** `:866` passes
  `handleScoped(Sync.defer(42))`, a body with no `Scope` suspensions, so it only checks that a
  nested `Scope.run` leaves the outer scope alone. It is correct as a nesting test; the block's
  title is what makes it look like coverage of this issue. Keep it, retitle it
  `"a nested Scope.run does not run the enclosing scope's finalizers"`, and move it out of the
  `#1381` block.
- **Fixture:** written against the API that exists, so it compiles today.
  `def generic[A, S](effect: A < S): A < (Async & S) = Scope.run(Sync.defer(()).andThen(effect))`.
  Call it as
  `Scope.run { Scope.ensure(caller.incrementAndGet.unit).andThen(generic(Scope.ensure(inner.incrementAndGet.unit).andThen(42)).map(r => caller.get.map(c => (r, c)))) }`.
- **Assertions:** inside the outer `Scope.run`, immediately after `generic` returns,
  `caller.get == 0` and `inner.get == 1`: the callee's own finalizer ran, the caller's did not.
  After the outer `Scope.run` exits, `caller.get == 1`. And `r == 42`.
- **Marker:**
  `.pendingUntilFixed("Scope is a ContextEffect, so the innermost Scope.run handles every Scope suspension in its dynamic extent, the caller's included; there is no isolation API to keep them apart")`
- **New API needed to make it pass:** the feature itself. Some form of `Scope.isolate` or an
  `Isolate` instance for `Scope`; none of `Scope.isolate`, `Isolate[Scope, ...]` or
  `Kyo.isolate` exists anywhere in the repo. The test above deliberately does not reference it,
  so it compiles and fails now rather than not compiling.

### 1224, Strengthen guarantees of Resource/bracket (FIXED)

- **File:** `kyo-core/shared/src/test/scala/kyo/ScopeTest.scala`, in the
  `"acquireRelease safety (#1224)"` block at `:567`
- **Leaf:** the block needs a pointer, not a new fixture.
- **Existing coverage exercises the fixed path, but not from the block that carries the issue
  number.** The real guards are `ScopeInterruptTest.scala:46`, `:80` and `:112`, which hold the
  acquiring worker inside the acquire until the interrupt has been sent, plus
  `ScopeTest.scala:208` for the closed-scope half and
  `kyo-kernel/shared/src/test/scala/kyo/kernel/BracketTest.scala:735-790` and `:782` at the
  kernel layer. The `#1224` block itself contains no interrupt, so citing it alone overstates
  what is covered.
- **Action:** add a comment at the head of the `#1224` block naming
  `ScopeInterruptTest.scala:46/:80/:112` as where the interrupt-in-the-window case is actually
  pinned, so the next reader does not mistake this block for the guard. No new fixture, no new
  assertions.
- **Marker:** not applicable.
- **New API needed:** none.

### 1131, Hierarchical Resource scopes (PARTIAL)

- **File:** `kyo-core/shared/src/test/scala/kyo/ScopeTest.scala`
- **Leaf:** `"closing a scope releases the resources of a nested scope still running under it"`
- **Existing coverage is the half that landed:** `:538`, `:847` and
  `ScopeInterruptTest.scala:146` all cover a nested run that finishes first. Nothing covers a
  parent closing while a child is live.
- **Fixture:** inside an outer `Scope.run`, fork with `Fiber.initUnscoped` a body that opens its
  own `Scope.run`, acquires a resource with `Scope.acquireRelease`, releases a `started` latch,
  and then parks on `gate.await` (a `Latch` the test never releases until the assertions are
  done). Await `started`, then let the outer `Scope.run` exit.
- **Assertions:** after the outer `Scope.run` returns, `released.get == 1`, awaited via
  `assertEventually` with `Abort.run[Timeout](Async.timeout(3.seconds)(...))` as the failure
  detector. Then release `gate` and assert `released.get == 1` still, so the child's own exit
  does not double-release.
- **Marker:**
  `.pendingUntilFixed("a parent scope waits for a nested run but cannot close it: fork and join are the identity, so closing a scope does not stop the computation running under it")`
- **New API needed to make it pass:** parent-to-child close propagation, which was built once in
  `0a79a67e91` and withdrawn by `2338ac9fa8`. `fork` and `join` are the identity today
  (`kyo-core/shared/src/main/scala/kyo/Scope.scala:166`, `:167`). This leaf specifies the
  behaviour; landing it needs the design decision the 1131 entry flags, so file the leaf and the
  decision together.

### 531, map after a large number of effect suspensions (PARTIAL)

**Stack half, a test.**

- **File:** `kyo-prelude/shared/src/test/scala/kyo/VarTest.scala`
- **Leaf:** `"stack-safe when a for-comprehension follows a recursive Var suspension"`
- **Existing coverage takes a different path:** `KyoTest.scala:169` and `:184` use the kernel's
  own `TestEffect1`, and `PendingTest.scala:1053` uses a map tower over a rescued computation.
  Neither is the `Var` program the issue reported, and `VarTest.scala` has no stack-safety leaf.
- **Fixture:** the issue's Step 1 program verbatim,
  `def program: Int < Var[Int] = for { n <- Var.get[Int]; x <- if n <= 0 then n else Var.set(n - 1).andThen(program) } yield x`,
  run as `Var.run(100000)(program)`.
- **Assertions:** `== 0`. The value, so a rescue that loses accumulated continuations fails too.
- **Marker:** plain, for the reason given at the top of this section. If it overflows when run,
  mark it
  `.pendingUntilFixed("the map the for-comprehension desugars to grows the continuation across 100000 Var suspensions, and unwinding it overflows the stack")`
  and leave the assertion as written.
- **New API needed:** none.
- The `Sync`-level twin of this is the 1739 leaf above; both shapes are worth having, since one
  goes through `Var`'s `ArrowEffect` dispatch and the other through `Effect.deferInline`.

**Throughput half, no test.**

This is a benchmark question and no test should be written for it. A test cannot assert a
throughput property without depending on wall-clock time, which this repo forbids, and a
threshold would be flaky on CI hardware. The measurement is:

- **Benchmark:** `kyo-bench/src/main/scala/kyo/bench/arena/StateMapBench.scala`, which is the
  issue's program at n = 1000 with cats and zio comparators already in place. Secondary:
  `kyo-bench/src/main/scala/kyo/bench/arena/DeepBindMapBench.scala`, and
  `kyo-kernel/jvm/src/jmh/scala/kyo/kernel/bench/KernelBench.scala:376` and `:386`
  (`dynamicChainOfMapsStaysLinear`, `dynamicChainOfBindsStaysLinear`) with their cats, zio and
  zio-blocks counterparts.
- **Baseline to compare against:** the merge base of this branch with `origin/main`, so the
  comparison isolates the kernel rewrite from anything main changed. The issue's claim is a
  roughly 500x slowdown of the `map`-after-suspension form against the `flatMap`-only form, so
  the ratio to watch is `StateMapBench.kyoBench` against `StateBench.kyoBench` on each side, not
  the absolute number.
- Until that run exists, "531 is fixed" is a claim about stack safety only, and the entry above
  says so.
