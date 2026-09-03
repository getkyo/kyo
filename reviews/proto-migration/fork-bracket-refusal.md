# A forked fiber is refused once its parent's bracket ends

Status: open design fork, needs a ruling. Nothing in the kernel was changed for it.

## Symptom

Every multi-suite run of a kyo-test module hangs at the start of a later suite, with the JVM idle:
the sbt task thread waits on the suite's future, the scheduler's workers are parked, no fiber is
runnable. Seen on `kyo-preludeJVM/test` (stuck at the second or third suite), on
`kyo-test-runnerJVM/test` (`SelfTestsRunnerTest`: the first `runToFuture` passes, the second never
completes), and reproduced without the test runner by
`kyo-test/runner/jvm/src/test/scala/kyo/HangScratchTest.scala` (a scratch, to be removed).

A single suite per JVM passes: `kyo.AbortTest` alone is 159 green after the fixes in
`3e3902d43e`.

## Mechanism

`LeafPool` forks its worker fibers with `Fiber.initUnscoped` from inside the first suite's
pipeline, which runs under `Scope.run`, whose `Sync.ensure` is an `Effect.bracket` region. The
scratch's dump shows what happens to those workers afterwards:

```
IOTask(id = 145044469, state = Done(result = Panic(kyo.Closed:
  Bracket resource created at <internal>:0:0 is closed.)), status = Done, curr = null)
```

The chain, each link read in the source:

1. `Isolate.internal.Contextual.capture` snapshots every context region on the stack
   (`Stack.contextual()`), and `Effect.bracket`'s region is a `ContextHandler` (`Finalize`).
2. `Contextual.fork` wraps each region as a `Forked` copy with `origin.fork(state)`; the bracket's
   `fork(parent) = parent`, so the child carries the parent's live `Cell`.
3. A `Forked` copy is silent to `done` and `release` (ruling S3) but forwards `reenter` to its
   origin (ruling S8, commit `164eb68f47`), and the bracket's `reenter` throws `Closed` once the
   cell was completed or drained.
4. A worker that parks on `channel.take` and is resumed after the first suite completed
   re-installs its regions (`Eval.installed` calls `reenter`), is refused, and completes with
   the panic. Nobody observes a detached worker's result, so the pool silently loses a worker per
   wake-up until no taker is left, and the next `put` has nobody to hand its work to.

Timing explains why the second suite sometimes passes: a `put` that finds no parked taker buffers
its work, and a worker that takes from the buffer never re-enters anything.

## Why this is a ruling and not a fix

S8 is deliberate: EffectBracketTest "a capture inside an isolated child, resumed after the bracket
ended, is refused" pins that a continuation captured inside an isolated child under a bracket and
resumed after the bracket ended must not run use code on a released resource. That ruling was made
for same-fiber crossings. A fiber spawn goes through the same `Contextual.isolate`, so a detached
fiber is treated as such a leaked continuation, and any fiber forked under `Scope.run`,
`Sync.ensure`, or `Sync.acquireReleaseWith` dies on its first park-and-resume after the enclosing
bracket ends. On main a child fiber never inherited `Sync.ensure` finalizers, so fibers outliving
the block that forked them were ordinary.

Both pins are individually right; they meet at the spawn. The question is what a forked fiber
inherits from a bracket: nothing (main's semantics) or an inert copy that still refuses.

## Options

A. The spawn does not carry bracket regions into the child. The kernel needs one hook for it, since
   only kyo-core knows a crossing is a fiber spawn: for instance a `ContextHandler` saying whether
   its region crosses into a spawned fiber, or a spawn-specific fork in `Contextual` that skips
   `Finalize` regions. S8 stays intact for same-fiber crossings, and fibers get main's semantics.
   This is the recommendation.

B. `Forked` stops forwarding `reenter`. One line, but it reverts S8 and its test goes red: a
   leaked continuation from a child would run use code after the release again.

C. Fork the pool's workers outside any bracket in kyo-test (`LeafPool` at object initialization).
   Hides a kyo-core regression that reaches every user forking a background fiber inside a
   resource scope. Rejected.

## A second, unrelated JS finding, fixed (`5ad7a269c7`)

Every prelude suite on JS died in its constructor with "Maximum call stack size exceeded". The
scheduler's join clause parks a fiber by re-raising the join with a stop requested, and the
js-wasm `Safepoint.stop` was a no-op returning false, `stopped` reporting only an expired slice
deadline: the re-raise was dispatched again and again until the stack ran out, the loop the JVM
showed before its stop was honored. js-wasm now records the stop and honors it by itself, consumed
at the slice boundary; EvalTest pins a clause that requests a stop and nothing else. The trace
builder also skips a null frame, which on JS was an undefined-behavior error escaping the
non-fatal catch. Kernel suites after the change: JVM 1459, JS 1403, Wasm 1403, all green. With it,
all 16 shared `kyo-preludeJS` suites are green one class per JVM, with the same counts as on the
JVM.

## What runs meanwhile

Prelude suites run one class per JVM, which sidesteps the pool: all 17 `kyo-preludeJVM` suites are
green that way (AbortTest 159, StreamTest 187, PipeTest 134, EmitTest 42, BatchTest 35, EnvTest 35,
VarTest 32, ChoiceTest 31, PollTest 30, AspectTest 23, LayerTest 23 with 3 pending, LocalTest 22,
SinkTest 18, CheckTest 15, MemoTest 15, IsolatePreludeTest 11, MonadLawsTest 2). The module-level
`kyo-preludeJVM/test` still hangs at its second or third suite by the mechanism above.
`kyo-test-runnerJVM`'s `SelfTestsRunnerTest` cannot pass until the ruling is applied, since it
runs two suites through one pool by design. `kyo-test-apiJVM` passes as a module because its
suites are plain ScalaTest classes that never touch the pool.

The other kyo-test modules, one class per JVM: `kyo-test-snapshotJVM` is green in every one of its
13 classes. `kyo-test-propJVM` is green in the 8 classes that run one suite (GenTest 26,
GenEdgeBiasTest 13, ShrinkTest 13, GenFilterBudgetTest 8, GenIntrospectionTest 5,
GenSeedIndependenceTest 5, TreeZipWithTest 4, PropertyMaybeTest 1) and stalls in the 7 ScalaTest
classes that drive several kyo-test fixture suites through the pool, each after its first fixture
suite completed (ForAllSeededTest, GenChoiceShrinkTest, GenShrinkChunkTest, GenZipTest,
IntegratedShrinkTest, PropTest, PropertyTestSelfTest).
