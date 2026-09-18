# Kernel benchmarks: cross-library parity review

Scope: `kyo-kernel/jvm/src/jmh/scala/kyo/kernel/bench/KernelBench.scala` and its three ports under
`bench/cross/` (`ZioBench.scala`, `CatsEffectBench.scala`, `TurboliftBench.scala`), reviewed against the
kernel at `6cfb09ee73` (branch `worktree-effervescent-painting-backus`). `CompileBench.scala` and the jmh
resource fixtures are untouched. Library sources consulted: ZIO 2.1.26, cats-effect 3.7.0 (cats-core 2.13.0),
Turbolift 0.126.0, all read from the sources jars in the Coursier cache.

Deliverables: the four benchmark files above, edited in place, and this report. The agent produced them in an
isolated worktree as a patch against `6cfb09ee73`; the patch was applied to the branch and reviewed, see the
review notes at the end.

## 1. Handler and suspension kinds in the kernel

Read: `kyo-kernel/README.md`, `kyo-kernel/CONTRIBUTING.md`, `ArrowEffect.scala`, `ContextEffect.scala`,
`Bracket.scala`, `Loop.scala`, `Isolate.scala`, `Region.scala`, `Effect.scala`, `internal/Eval.scala`
(`partial`), `jvm-native/internal/Safepoint.scala` (`enter`, `period`).

Suspensions (what puts a node in front of the evaluator):

| Kind | Entry point | What it costs, what answers it |
| --- | --- | --- |
| Context read | `ContextEffect.suspend(tag)`, `suspendWith` (fused), `suspend(tag, default)` (row-free) | One `SuspendContext` node; answered by the innermost binding `Stack.find` reaches, no clause runs. The cheapest suspension. |
| Arrow operation | `ArrowEffect.suspend`, `suspendWith` (continuation fused into the node) | One `SuspendArrow` node with `Arrow.id`; answered by the innermost handler whose tag subsumes the operation's. |
| Deferral | `Effect.defer(block)` | A `DeferWith` node and no effect: the block runs where the evaluator reaches it. |
| Budget rescue | a strict `map` on a settled value, denied by `Safepoint.enter` after 256 nested applications | The application is reified as a `Defer` instead of recursing; the period is `kyo.internal.Platform.maxStackDepth`, 256 on the JVM. |
| Snapshot and Park | `Isolate.internal.Contextual` (`capture`, `isolate`, `restore`) | A `Snapshot` node reads the stack; a `Park` node carries forked region entries and reinstalls them. |

Regions and answers (what the evaluator pushes on its stack, and how it answers):

| Kind | Entry point | Characteristics |
| --- | --- | --- |
| Binding | `ContextEffect.handle`, `handleInheritable`, `handleNonInheritable` | `HandleContext` region; the value is derived at entry from the enclosing binding (`ifUndefined` / `ifDefined`, the layering `Env.run` and `Local.let` use), reverted at exit; `fork` and `join` are consulted only by a crossing; an optional `release`. |
| Stateless answer | `ArrowEffect.handleLoop` | `LoopHandler`; the clause sees the input and answers with a `Loop.Outcome`; when the handler is innermost consecutive same-tag occurrences are answered in one fused walk (`Handler.answersLoop`); a clause that suspends before continuing (an "emitting" clause) ends the walk and the region is rebuilt around the pending answer; `Loop.done` ends the region; an operation the clause performs at the handled tag reaches an outer handler. |
| State-threading answer | `ArrowEffect.handleLoopState` | As above with a state value carried between occurrences (`Continue2` per answer), seen by `done`. |
| Continuation in hand | `ArrowEffect.handleCont` | `ContHandler`; the regions between the handler and the suspension are dumped into the continuation (`Eval.dumped`) and their releases move to this handler. The clause applies it once (resume), more than once (multi-shot, every shot against the live resource, release once at the handler's end), or never (short-circuit: the remainder is dropped and its brackets release with the discard signal). |
| Recovery arm | the three-clause overloads of the three handlers | A throw is offered to the region on one unwind walk (`Eval.recovered`), releases run on the same walk, the receiver is forced under the recovery. |
| Fused done | `handleContWith`, `handleLoopWith`, `handleLoopStateWith` | The caller's continuation is fused into the region node instead of a `map` after it. |
| Bracket | `Bracket.apply`, `Bracket.ensuring`, `Bracket.ensuringWith` | A `ContextHandler` region owning a release, run exactly once through a CAS (`Bracket.Cell.Live`); `apply` installs after the acquire's value arrives (an `Ensure` arrow that skips the safepoint poll), `ensuring` installs from the start. |
| Peel | `ArrowEffect.handleFirst`, `handleFirstRepeated` (`private[kyo]`) | `FirstHandler`; the region ends at the first occurrence and hands the remainder out with the effect still in its row; the remainder's regions are owed to the scope below (or held, for `repeated`). |
| Mask | `ArrowEffect.Mask`, `handleMasking` (`private[kyo]`) | `MaskingHandler` re-suspends every operation of the masked tag as a `Mask` operation; `Mask.run` re-raises it outside; a context read tunnels the same way. |
| Isolate crossing | `Isolate.run` over `Isolate.crossing` (`private[kyo]`) | The state half of leaving a fiber: capture the context regions, fork each through its `fork`, run under a `Park`, join back through `join`. The derived instance for a lone context effect is the identity; `crossing` adds the context half. |
| Same-tag nesting | two regions of one tag | `Stack.find` stops at the innermost, the outer is shadowed and idle. |
| Different-tag nesting | a region between a handler and its suspension | `handleCont` dumps and reinstalls it (rotation); `handleLoop` answers where it stands (`running`), nothing is dumped. |

Shapes above the regions: `Loop` (a cached step arrow, one `Continue` per round), `Arrow.recursive` (a
reified self-applying step), `Kyo.foreach` / `foldLeft` / `collect`, map chains on a settled receiver (fused
inline, allocation free), map chains on a pending receiver (one node per map), trailing maps folded into the
continuation, chains attached one link at a time at runtime, a computation carried as a payload (`Nested`),
and `Eval.partial` (the armed slice: a preemption poll per step that parks only when a stop is requested).

## 2. What each KernelBench row measures, and how each port stands

Iterations: `Depth` 10000, `NarrowDepth` 1000, `FusedDepth` 32, `FusedWideDepth` 8, `ShallowDepth` 200,
`OneRescueDepth` 400. "Suspensions" are per operation. Allocation columns describe kyo's row.

Status words: `equivalent` (the port measured the same thing already), `fixed` (the port was changed to match),
`removed` (the library cannot express the pattern; its row is gone rather than approximated), `absent` (a new
kyo row the library cannot express), `added` (a new row the library expresses), `changed` (a kyo row corrected).

| Row | What the kyo row measures | kyo | ZIO | cats-effect | Turbolift |
| --- | --- | --- | --- | --- | --- |
| evalFixedOverhead | one eval of a settled Int with one strict map; 0 suspensions; below JMH resolution | as is | equivalent | equivalent (entry now on the calling thread, see 4) | equivalent |
| evalFixedOverheadBatch | the same, 1000 per invocation | as is | equivalent | equivalent | equivalent |
| entryFloorBatch | 1000 evals of a settled value, nothing composed: the entry floor | as is | equivalent | equivalent | equivalent |
| fusionAllocatesNothing | 32 rounds of 10 fused maps on a settled value plus the recursion map; 0 suspensions; zero allocation | as is | equivalent (a Map node per link is the point) | equivalent | equivalent |
| fusionPastBudgetPaysRescuesOnly | 1000 rounds of the same; the nesting crosses the 256 budget about 39 times, each a Defer | as is | equivalent | equivalent | equivalent |
| uncachedValuesPayBoxingOnly | 1000 rounds of 10 maps over Ints outside the Integer cache: boxing only | as is | equivalent | equivalent | equivalent |
| deferBindPerStep | 1000 steps, each a DeferWith node and a map node; no effect | as is | equivalent (`suspendSucceed`) | equivalent (`IO.defer`) | equivalent (`impureEff`) |
| deferBindUnderIdleHandler | the same under an idle `handleLoop(Ask)` region that is never reached | as is | fixed: `ask.set` was an unscoped write; now `ask.locally`, the scoped install and restore | fixed: `ask.set` replaced by the bracketed scoped install (`IOLocal#asLocal.local`'s shape) | equivalent |
| deferBindUnderTrailingMap | the same with one map after the loop, so the tail is rebuilt each step | as is | equivalent | equivalent | equivalent |
| deepRecursionPaysRescuesOnly | 10000 nested strict maps on a settled Unit: about 39 rescues | as is | equivalent | equivalent | equivalent |
| deepRecursionNoRescue | nested strict maps that stay inside one period: no rescue | changed: 400 nests past the 256 period once; now `ShallowDepth` 200 | fixed (mirrors 200) | fixed | fixed |
| deepRecursionOneRescue | nested strict maps crossing the period once | changed: 600 crossed it twice; now `OneRescueDepth` 400 | fixed (mirrors 400) | fixed | fixed |
| suspensionBaseline | 10000 Ask suspensions (`SuspendArrow`, a map node, a `Continue` per answer), stateless `handleLoop` answering in place | as is | equivalent under the Tier B substitution: `FiberRef.get` plus `flatMap` per round | equivalent: `IOLocal.get` plus `flatMap` | equivalent, exact: a Reader operation answered from the handler's local |
| suspensionFusesContinuation | the same with `suspendWith`: one node per suspension | as is | removed: `FiberRef.getWith` is `get.flatMap`, the row would duplicate suspensionBaseline | removed: no fused read exists | equivalent, exact: `asksEff` is `Local.getsEff` |
| handleLoopAnswersInPlace | identical to suspensionBaseline, named for the handler | as is (a duplicate in kyo too, see 5) | equivalent (duplicates its baseline as kyo does) | equivalent | equivalent |
| handleLoopFusesContinuation | `handleLoopWith` with `_ + 1` fused into the region node | as is | removed: a map after the run is the unfused shape, not the fusion | removed | removed: `Handler.mapK` runs as a flatMap after `doHandle`, nothing fuses |
| handleContResumesOnce | 10000 suspensions answered by `handleCont` applying the continuation once | added | absent: no continuation capture | absent | added: a Stateful Reader handler whose `ask` is `Control.captureGet((k, r) => k(r))` |
| handleContResumesTwice | 1000 rounds, a `handleCont` region per round whose clause applies the continuation twice, second result kept | added | absent | absent | added: `captureGet((k, r) => k(r).flatMap(_ => k(r + 1)))` under a handler installed per round |
| abortUnwindsThroughRegions | 1000 rounds; a `handleCont` clause that never resumes drops a remainder holding a `Bracket` and a `Cfg` binding: both unwind, the bracket releases with the discard signal | added | added: `ZIO.fail` under `acquireReleaseWith` and `locally`, `catchAll` outside | added: `IO.raiseError` under `bracket` and the scoped install, `handleErrorWith` outside | added: `Err.raise` (Error effect, `Control.abort`) under `IO.bracket` and `Cfg.localPut`, `Err.handler` outside |
| recoverAnswersThrow | 1000 rounds; a `handleLoop` region with a recover arm answers one suspension, the map throws a preallocated NoStackTrace exception, the region recovers | added | added: `attempt(throw)` after the read, `catchAll` | added: `IO(throw)` after the read, `handleErrorWith` | added: `IO.sync(throw)` after the read, `IO.catchAll` |
| handleFirstPeelsRemainder | 1000 rounds; `handleFirst` ends its region at the first occurrence and the clause resumes the handed-out remainder once, under an outer handler | added | absent | absent | absent: `Control.capture0`'s remainder keeps the eliminated effect in its row and the clause must discharge it itself, a different pattern from the peel |
| maskTunnelsPastInnerHandler | 1000 rounds; a masked suspension tunnels past an idle inner `Ask` handler to the outer one through `Mask` and `Mask.run` | added | absent | absent | absent (`Control.shadow` is interpreter-internal, not a user construct) |
| sameTagInnerHandlerAnswers | 10000 suspensions under two `Ask` handlers, the inner answering, the outer shadowed | added | added: two nested `locally` of one ref | added: two nested scoped installs of one local | added: two nested Reader handlers of one effect |
| nestedPayloadsUnwrapInMaps | 1000 rounds; a pending Ask suspension boxed as a `Nested` payload and unwrapped by a strict map, never run | as is | fixed: `succeed(succeed(x)).flatten` ran the inner value; now a read node carried as data and dropped | fixed likewise | fixed likewise (`Ask.asks(a => a)` allocates the node per round as kyo's suspension does) |
| statefulAnswersPaySuccessor | 10000 suspensions, `handleLoopState` answering 1 and advancing the state (`Continue2` plus a boxed state per answer) | as is | equivalent: `FiberRef.modify(s => (1, s + 1))` | equivalent: `IOLocal.modify` | equivalent, exact: `State.update` under the local handler |
| idleHandlerAddsNothing | 1000 rounds of 10 fused maps under an idle `handleLoop(Ask)` region | as is | equivalent (`ask.locally`) | fixed: `ask.set` replaced by the scoped install | equivalent |
| trailingMapsStayLinear | 10000 suspensions each leaving one trailing map, all applied at the end | as is | equivalent | equivalent | equivalent |
| emittingClausesPayRegionRebuild | 1000 rounds; the `Ask` clause suspends `Tick` before continuing, so 2 suspensions per round and the region is rebuilt around the pending answer | as is | removed: the port read two refs in the body, which is foreignCrossingsAnsweredInPlace; a clause performing an effect has no counterpart | removed likewise | fixed: `Ask` handled by a Proxy interpreter whose operations perform `Tick`, `Tick` handled outside |
| continuationBodiesFuse | 1000 suspensions, each continuation body a 10-map fused chain | as is | equivalent | equivalent | equivalent |
| userTypesSkipKernelWrapping | 1000 rounds of 10 maps over a case class: no Int boxing, a Box per map | as is | equivalent | equivalent | equivalent |
| inlineLimitCostsTimeNotAllocation | 1000 rounds of 50 maps | as is | equivalent | equivalent | equivalent |
| inlineLimitKeepsZeroAllocation | 8 rounds of 50 fused maps | as is | equivalent | equivalent | equivalent |
| fusionAfterSuspensionRunOnly | one prebuilt suspension with 50 trailing maps; only the answer is timed | as is | equivalent | equivalent | equivalent |
| fusionAfterSuspension | 1000 rounds; a suspension with 10 maps composed on the pending receiver (a node per map) | as is | equivalent | equivalent | equivalent |
| partialSuspensionBaseline | the 10000-suspension loop through `Eval.partial`: the poll per step is armed, nothing parks | as is | fixed: runs on `Runtime.default` (cooperative yielding on) while every other row runs on a non-yielding runtime; see 6 for the asymmetry | removed: the fiber always auto-yields, there is no distinct armed run | removed: the engine always ticks, there is no distinct armed run |
| sharedHandlerPaysDispatch | 10000 `suspendWith` from 16 call sites, so the answering walk dispatches over 16 continuation classes | as is | equivalent, spelled `get.flatMap` (what `getWith` is) | equivalent | equivalent (the `ask` node is shared, the cont class varies) |
| foreignCrossingsPayRotation | 20000 suspensions (Ask then Ask2 per round) under two `handleCont` handlers; each Ask2 answer dumps the inner Ask region into the continuation and reinstalls it | as is | removed: two refs in one map, nothing is captured or reinstalled | removed | fixed: both handlers now capture and resume (`resumeOnce`), so the Ask2 capture unwinds through the inner prompt |
| foreignCrossingsAnsweredInPlace | the same shape under two `handleLoop` handlers: answered where they stand, nothing dumped | as is | equivalent | equivalent | equivalent |
| dynamicChainOfMapsStaysLinear | 1000 maps attached one at a time at runtime; kyo applies each strictly as it is attached | as is | equivalent (same program; ZIO builds 1000 nodes then runs them) | equivalent | equivalent |
| dynamicChainOfBindsStaysLinear | the bind spelling | as is | equivalent | equivalent | equivalent |
| pureIterationViaLoop | `Loop` over 10000 rounds: a tail-recursive walk, a `Continue` per round | as is | fixed: open recursion was pureIterationViaMethod again; now `ZIO.iterate` | fixed: now `Monad.iterateWhileM` | fixed: now `!!.iterateWhile` |
| pureIterationViaMethod | 10000 rounds of strict-map recursion (rescues) | as is | equivalent | equivalent | equivalent |
| pureIterationViaArrow | `Arrow.recursive`, a reified self-applying step | as is | equivalent (a self-referential function value) | equivalent | equivalent |
| effectfulIterationViaLoop | `Loop` with one suspension per round, 10000 rounds | as is | fixed: `ZIO.iterate` with the read in the body | fixed: `iterateWhileM` | fixed: `!!.iterateWhile` |
| effectfulIterationViaArrow | `Arrow.recursive` with one suspension per round | as is | equivalent | equivalent | equivalent |
| contextReadsUnderBindings | 1000 context reads of the outermost of three bindings, the scan walking past an idle Ask handler and the two inner bindings | as is | fixed: read the innermost ref; now the outermost, under the same four nested scopes | fixed: unscoped `set`s of the innermost; now four nested scoped installs, reading the outermost | fixed: a single handler; now three nested Reader handlers plus the idle one, reading the outermost |
| contextRegionsPayEntryExit | 1000 rounds each installing a `Cfg` region, reading it and exiting | as is | equivalent (`cfg.locally(1)(cfg.get)`) | fixed: `getAndSet` / `set` had no exit guarantee; now the bracketed scoped install | equivalent (a `Cfg` handler per round) |
| contextRegionsDeriveFromOuter | 1000 rounds each installing a region whose value derives from the enclosing binding (`ifDefined`) | added | added: `locallyWith(_ + 1)` under `locally(0)` | added: the bracketed modify with a derived value | added: `Cfg.localModify(_ + 1)` (`Control.delimitModify`) |
| isolateCrossingPerRound | 1000 rounds of `Isolate.crossing.run` over a context read under one binding: capture, fork, park, join | added | absent: a ZIO fork is a fiber and a scheduler hop | absent | absent |
| suspensionBaselineAltInstall | 10000 context reads under `handleInheritable` | as is | equivalent (`ask.locally(1)`) | fixed: `set` replaced by the scoped install | equivalent (`Ask.handler(1)` built per run) |
| suspensionBaselineAltEnv | the same over `Cfg2` | as is | equivalent as the recorded `ZIO.service` spelling, an Env-style read through the environment map | fixed: scoped install | equivalent |
| statefulAnswersPaySuccessorAltRef | 10000 suspensions answered 1 by a stateless clause that advances a shared atomic cell | changed: the row was byte-identical to suspensionBaseline with no cell; now an `AtomicInteger` incremented in the clause | fixed: `st.set(0)` plus plain reads never updated anything; now `Ref.modify(s => (1, s + 1))` | equivalent (`Ref[IO].modify`) | fixed: a plain Ask loop; now `AtomicVar.update` on a shared var, run through `runIOST` |
| bracketPerRound | 1000 rounds of `Bracket(acquire)(use)(release)`: an Ensure arrow, a cell, a region, one CAS release | as is | equivalent (`acquireReleaseWith`) | equivalent (`bracket`) | fixed: `pure.flatMap(guarantee)` was not a bracket and `runIO` used the MT pool; now `IO.bracket` and `runST` |
| bracketAroundLoop | one bracket around the 10000-round pure loop | as is | equivalent | equivalent | fixed as above |
| bracketEnsuringOnly | `Bracket.ensuring` around the pure loop: the region installed before the body | as is | equivalent (`ensuring`) | equivalent (`guarantee`) | fixed: `runIO` (MT pool) replaced by `runST` |
| foreachOverCollection | `Kyo.foreach` over 1000 elements with settled steps | as is | equivalent (see 6 on the fixture type) | equivalent | equivalent |
| foldOverCollection | `Kyo.foldLeft` over 1000 elements | as is | equivalent | equivalent | equivalent |
| collectOverCollection | `Kyo.collect` over 1000 elements, one pass, a `Maybe` per element | as is | equivalent with a note: `foreach` of Options plus a flatten, one extra pure pass, since `ZIO.collect` drops through the failure channel | fixed: `traverse` plus flatten replaced by the single-pass `traverseFilter` | fixed: `mapEff` plus flatten replaced by the single-pass `mapFilterEff` |

## 3. Rows added to KernelBench

Nine rows, for the kinds the file did not measure:

- `handleContResumesOnce`: the continuation applied once (the single-effect `handleCont` baseline; the file only had it inside the two-effect rotation row).
- `handleContResumesTwice`: multi-shot resumption.
- `abortUnwindsThroughRegions`: the never-resumed clause, unwinding a bracket and a binding.
- `recoverAnswersThrow`: the recover arm.
- `handleFirstPeelsRemainder`: the peel (`handleFirst`, reachable from the bench because it sits in package `kyo`).
- `maskTunnelsPastInnerHandler`: masking.
- `sameTagInnerHandlerAnswers`: nested handlers of one tag.
- `contextRegionsDeriveFromOuter`: a scoped rebinding derived from the enclosing binding.
- `isolateCrossingPerRound`: the isolate crossing through `Isolate.crossing`.

Ports were added where the library expresses the pattern (see the table): Turbolift for all but the peel, mask
and crossing; ZIO and cats-effect for the abort, the throw, the derived rebinding and the same-tag nesting.

Also corrected in KernelBench: `statefulAnswersPaySuccessorAltRef` now has a cell, and the two shallow
recursion rows now sit on either side of one 256-application period. Names follow the existing scheme
(a lower camel-case claim, `Alt` suffix for recorded alternatives), so regex filters over `KernelBench.*`
and the shared row names keep working.

## 4. Run entry changes in the ports

- ZIO: `unsafe.run` starts on the calling thread, but with `CooperativeYielding` on (the `Runtime.default`
  flags) `FiberRuntime.runLoop` returns after 10240 operations, the fiber is resubmitted to the ZIO executor
  and the caller parks on a `OneShot`; every `Depth` row paid one or two such handoffs. The class now runs on
  `Runtime(ZEnvironment.empty, FiberRefs.empty, RuntimeFlags.disable(RuntimeFlags.default)(CooperativeYielding))`,
  which also skips the fiber-roots registration the header credited to every run (it happens only on the
  yielding path). `partialSuspensionBaseline` keeps `Runtime.default`.
- cats-effect: `unsafeRunSync()` on the global runtime submits the fiber to the work-stealing pool and parks
  the caller on an `ArrayBlockingQueue`. The class now runs on an `IORuntime` whose compute context is
  `ExecutionContext.parasitic`: the fiber runs inline in `unsafeRunFiber`, auto-yield resubmissions
  trampoline on the same thread, and the queue is filled before the caller polls it. The IOFiber run loop is
  unchanged.
- Turbolift: the three bracket rows used `.runIO`, whose default mode is MT (a thread pool and a parked
  caller), contradicting the file's own header; they now use `runST` (the rows need no `IO` in the row) and
  the two rows that genuinely need `IO` use `runIOST`.
- Idle regions in ZIO and cats-effect are now consistently the scoped install (`locally`, and the bracketed
  `modify` / `set` that `IOLocal#asLocal.local` spells), where before they mixed unscoped `set`s and scoped
  installs across rows.

## 5. Questions of equivalence not settled by reading

1. Collection fixture: kyo iterates a `Chunk[Int]`, the three ports a `List[Int]`. The per-element step is
   the same, the container's iteration constant is not. Either choice is defensible (each library's idiom, or
   one shared fixture); left as is.
2. `partialSuspensionBaseline` in ZIO: kyo's armed slice polls and never parks when no stop is requested; ZIO's
   cooperative yielding fires every 10240 operations and moves the fiber to the executor, so the ZIO row carries
   real handoffs. It is ZIO's only preemptible run, kept with that caveat in its scaladoc.
3. ZIO's `FiberRef.get` is defined as `modify(v => (v, v))`, so `suspensionBaseline` and
   `statefulAnswersPaySuccessor` exercise the same ZIO mechanism; the kyo rows they mirror do not.
4. The recursion depths assume the default period of 256 (`Platform.maxStackDepth`, read by
   `Safepoint.period`, a `StaticFlag`). If the JMH fork sets `kyo.kernel.internal.Safepoint.period`, the two
   shallow rows need re-checking against it. The previous 400 and 600 were named as if the period were 512.
5. Bracket rows compare each library's bracket. ZIO's `acquireReleaseWith` and cats-effect's `bracket` mask
   interruption around acquire and release and Turbolift's `IO.bracket` snapshots the outcome; kyo's `Bracket`
   has no masking to pay. The rows measure the construct, not region install alone.
6. Turbolift's capture-based handlers (`resumeOnce`, `resumeTwice`) mirror the stock Reader handler in every
   member but `ask` and in the `Parallel.Trivial` mixin, so the delta to `suspensionBaseline` should be the
   capture and resume alone; whether `captureHint = true` removes all first-capture cost was not verified.
7. Turbolift's Proxy handler for the emitting row reinterprets each `Ask` operation as a `Tick` operation.
   That the reinterpreted operation is evaluated at the `Ask` prompt and dispatched to the `Tick` prompt above
   it (kyo's clause suspending at the handler, answered further out) follows from the engine's
   `Perform` dispatch as read, not from a run.
8. `abortUnwindsThroughRegions` carries each library's failure representation: kyo allocates a
   `KyoException` (NoStackTrace) per released bracket as the discard signal, ZIO a `Cause` per `fail`,
   cats-effect raises a preallocated exception, Turbolift a `Cause.Aborted`. None can be factored out.
9. `handleLoopAnswersInPlace` is byte-identical to `suspensionBaseline` in KernelBench itself; the ports
   duplicate their baselines the same way. Left as is since the kyo rows are the reference.
10. `dynamicChainOf*StaysLinear`: kyo applies each attached map strictly at attach time, so the timed region
    is the attach loop; the ports build a node per link and interpret them. Same program, different work
    placement; kept with the existing scaladoc.
11. `sharedHandlerPaysDispatch` in Turbolift shares the `ask` operation node across the sixteen sites (a
    `final val` in `ReaderEffect`); kyo allocates a fused node per site. Already noted in the row's scaladoc.

## 6. Validation

- `sbt --batch 'kyo-kernelJVM/Jmh/compile'`: success (110 s), with the tip tree of `6cfb09ee73` extracted
  into this worktree. scalafmt, which the build runs, left the four files unchanged.
- `sbt --batch 'kyo-kernelJVM/Jmh/run -f 1 -wi 0 -i 1 -r 1 <regex>'` over every added or changed row in all
  four classes: 101 rows executed, exit 0, no exception. The regex was
  `handleCont|abortUnwinds|recoverAnswers|handleFirst|maskTunnels|sameTag|contextRegions|isolateCrossing|statefulAnswersPaySuccessorAltRef|nestedPayloads|IterationViaLoop|contextReads|bracket|collectOver|foreignCrossingsPayRotation|emittingClauses|partialSuspension|idleHandler|deferBindUnderIdle|deepRecursionNoRescue|deepRecursionOneRescue|suspensionBaselineAlt|evalFixedOverhead`.
  One unwarmed iteration is a liveness check, not a measurement; the only reading taken from it is that the
  two shallow recursion rows now sit on either side of a rescue in kyo (0.84 us against 2.03 us) and that ZIO's
  `partialSuspensionBaseline` on the yielding runtime costs about 3.5 times its non-yielding rows, which is the
  handoff the row exists to show.
- `reviews/new-kernel/benchmarks.patch` was applied with `patch -p1` to a scratch copy of the four files at
  `6cfb09ee73` and reproduces the four edited files byte for byte.
- No em or en dash in any of the six files.
