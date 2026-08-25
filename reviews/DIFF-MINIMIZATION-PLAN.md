# Diff minimization against origin/main: full-branch plan

Objective: make `git diff origin/main` as small and reviewable as possible for every changed
file in the branch, not only kyo-kernel. Alignment never changes behavior silently: any
divergence that is semantic is either already ruled by the owner (the intent register below) or
gets FLAGGED in the queue at the bottom and waits for a ruling. Only wording, ordering, doc
restoration, and owner-ruled API restorations are applied without asking.

## Method, per file

1. Classify every hunk: NEW MACHINERY (no old counterpart; stays), SEMANTIC (behavior differs;
   must be in the intent register or go to the flag queue), ALIGNABLE (doc drift, member order,
   spelling, formulation differences with identical behavior).
2. Apply the alignable lane only: restore old scaladoc verbatim where semantics hold, match
   member order and file layout, restore old formulations where the owner ruled them back.
3. One commit per file or small file group, so the owner can review the stream.
4. Compile and test validation after each module once sbt is clear; the eval/flatten form
   restorations in particular may surface downstream call sites to adjust.

## Intent register (owner rulings; these diverge from old main BY DESIGN and stay)

- ArrowEffect naming: `handleCont`, `handleLoopState` (and the `*With` family) are intentional.
- Multi-tag `handle` (2/3/4 effects in one region) is removed by design; regions nest instead.
- Continuations are `Arrow` values by design (the migration to Arrow is intentional).
- `Mask` is public surface but still being worked on; do not align or finalize it.
- Safepoint was never public functionality; the new slot machinery's public members exist only
  for inline reach. No restoration of `Safepoint ?=>` threading anywhere.
- The public `Effect.defer(v, cont)` outcome dispatchers stay public and non-inline (inline
  reach plus bytecode budget; scaladoc added stating this).
- Loop constructors return pending types (approved); `Outcome*` covariant in O.
- `Isolate.capture` row is `Remove & S` (approved); `run(state, v)`/`apply`/`Contextual` are new
  by design.
- ContextEffect `handle` fork/join/release parameters are new by design; `Noninheritable` is
  replaced by `fork = _ => Absent`.
- Restorations already executed on rulings: `handleFirst` private[kyo]; `eval` back to the old
  `A < Any` extension with Frame; `evalNow` private[kyo]; `flatten` back to the `A < S < S2`
  receiver; `bug.failTag` restored and wired into `Eval.unhandled`.
- The `@static` removals (Effect.defer/Nested) and the *PlatformSpecific holder pattern for
  platform-varying cells are settled this session.
- kyo-kernel tests extend scalatest directly as an interim until kyo-test migrates to the new
  kernel (ship-list item); the textual delta collapses then and is not chased now.

## Checklist: every changed file (diff line count in parentheses)

Legend: [x] processed (aligned, or classified with every hunk covered by the register);
[ ] pending. D = the file only exists on one side (old-kernel removal or new machinery), where
the whole diff is the ruled swap itself.

### Build, CI, scripts (hunks predate this session; classify and flag, do not guess intent)
- [ ] (30) .github/workflows/build.yml
- [x] (106) build.sbt - the kernel swap and single-kernel compile-bench; reviewed this session
- [ ] (24) project/TestKyo.scala
- [ ] (26) scripts/build.sh
- [ ] (142) scripts/ci-test.sh

### kyo-kernel: public files
- [x] (250) kyo/Arrow.scala - new public surface by design (Arrow migration)
- [x] (1256) kyo/Kyo.scala - only the Safepoint-threading signature change remains (register)
- [x] (21) kyo/Mask.scala - in progress per owner; untouched
- [x] (1094) kyo/kernel/ArrowEffect.scala - docs restored; remainder is the Handle-node design
- [x] (166) kyo/kernel/ContextEffect.scala - docs restored; fork/join/release per register
- [x] (226) kyo/kernel/Effect.scala - trait doc restored; bracket new; defer scaladoc added
- [x] (513) kyo/kernel/Isolate.scala - capture row and run/apply/Contextual per register
- [x] (610) kyo/kernel/Loop.scala - pending constructors per register; docs kept
- [x] (664) kyo/kernel/Pending.scala - full doc layer restored; eval/evalNow/flatten rulings in
- (0) kyo/kernel.scala - fully aligned, no diff remains (failTag restored)

### kyo-kernel: internals (new machinery or old-kernel removals; the swap IS the diff)
- [x] (72) internal/CanLift.scala - doc and message aligned; guard design differs by design
- [x] D (64) internal/Context.scala - old kernel removal
- [x] (87) internal/Debugger.scala - new; holder pattern applied
- [x] D (345) internal/EffectTrace.scala - new machinery
- [x] D (833) internal/Eval.scala - new machinery; failTag wired
- [x] D (90) internal/Finalizer.scala - new machinery
- [x] D (539) internal/Handler.scala - new machinery
- [x] D (57) internal/Implicits.scala - new machinery
- [x] (336) internal/KyoInternal.scala - new node hierarchy replacing the old
- [x] D (47) internal/LiftMacro.scala - old kernel removal (new lift is runtime-analysis based)
- [x] D (43) internal/Nested.scala - new machinery
- [x] D (244) internal/Safepoint.scala - old shared removal; platform split replaces it
- [x] D (664) internal/Stack.scala - new machinery; holder pattern applied
- [x] D (209) internal/Trace.scala - old kernel removal
- [x] D (18) internal/package.scala - old kernel removal
- [x] D (17/19) js-wasm + (18/20) jvm-native *PlatformSpecific - new, ruled pattern
- [x] D (142) js-wasm/internal/Safepoint.scala - new: deadline preemption, ruled
- [x] D (247) jvm-native/internal/Safepoint.scala - moved from shared plus deadline no-op
- [x] D (46/83/52) TracePool (js-wasm/jvm/native) - old kernel removals

### kyo-kernel: tests
- [ ] (205) kyo/ArrowTest.scala - new machinery test; classify
- [ ] (66) kyo/KyoForeachCollTest.scala
- [ ] (29) kyo/KyoForeachTest.scala
- [x] (163) kyo/KyoTest.scala - classified: interim scalatest base, ruled renames, semantic asserts
- [ ] (2845) kernel/ArrowEffectTest.scala - largely rewritten; verify old tests all have heirs
- [ ] (346) kernel/ContextEffectTest.scala
- [ ] (1551) kernel/EffectTest.scala - largely rewritten; verify old tests all have heirs
- [ ] (672) kernel/IsolateTest.scala
- [ ] (459) kernel/LoopTest.scala - constructor changes plus this session's battery
- [ ] (940) kernel/PendingTest.scala
- [ ] (166) internal/CanLiftTest.scala
- [x] D (102) internal/ContextTest.scala - old kernel removal
- [ ] (179) internal/DebuggerTest.scala
- [ ] (357) internal/EffectTraceTest.scala
- [ ] (62) internal/EvalCaptureTowerTest.scala
- [ ] (944) internal/EvalTest.scala
- [ ] (117) internal/HandlerTest.scala
- [ ] (167) internal/ImplicitsTest.scala
- [ ] (85) internal/NestedTest.scala
- [ ] (541) internal/StackTest.scala
- [x] D (132) internal/TracePoolTest.scala - old kernel removal
- [x] D (334) internal/TraceTest.scala - old kernel removal
- [ ] (564) outsidekyo/PendingExpansionSiteTest.scala
- [x] D jvm-native tests (ThreadingTests, SafepointTest, SafepointUnstartedThreadTest,
  EffectTracePhysicalTest) - this session's platform extraction, ruled
- [x] D (68) jvm/BytecodeTest.scala - old kernel removal
- [ ] (84) jvm/ArrowEffectBytecodeTest.scala; (107) jvm/PendingBytecodeTest.scala - new pins
- [x] D (324/849/73) jvm Safepoint/TracePool concurrency tests - old kernel removals
- [x] D (455) jmh/KernelBench.scala - new board; (268) ProtoKernelBench - session rows
- [ ] (342/329/345/309) bench-cross CatsEffect/Turbolift/Zio/ZioBlocks - classify (new project)
- [ ] (196) CONTRIBUTING.md; (743) README.md - rewrite for the new kernel is expected; verify

### kyo-prelude
- [ ] (54) Abort.scala
- [ ] (31) Batch.scala
- [ ] (39) Check.scala
- [ ] (34) Choice.scala
- [ ] (102) Emit.scala
- [ ] (201) Local.scala - reshaped by design (fork/join strategies); restore old docs
- [ ] (12) Memo.scala
- [ ] (115) Pipe.scala
- [ ] (44) Poll.scala
- [ ] (84) Sink.scala
- [ ] (572) Stream.scala - biggest prelude delta; classify hunk by hunk
- [ ] (27) Var.scala
- [ ] (130) debug/Debug.scala - Debug was deleted-then-reworked per ledger; classify
- [ ] (91) ChoiceTest.scala; (53) EmitTest.scala; (64) LocalTest.scala; (235) DebugTest.scala

### kyo-core
- [ ] (16/25/25) scheduler/IOTaskPlatformSpecific (js-wasm/jvm/native) - kernel2 integration
- [ ] (176) jvm/StreamCompression.scala
- [x] (128) Async.scala - session work (Keep restoration, merge); docs to verify once more
- [ ] (12) Clock.scala
- [x] (348) Fiber.scala - session work; align docs of unchanged members
- [ ] (12) KyoApp.scala
- [ ] (115) StreamCoreExtensions.scala - includes session groupedWithin fix
- [ ] (50) Sync.scala - ensure/acquireRelease outcome widenings are session-approved
- [ ] (29) scheduler/IOPromise.scala
- [ ] (726) scheduler/IOTask.scala - kernel2 integration; largely new machinery
- [ ] (26) AsyncTest.scala; (40) MeterTest.scala; (84) ScopeTest.scala;
  (13) StreamCoreExtensionsTest.scala; (36) SyncTest.scala; (203) IOTaskTest.scala

### kyo-data
- [ ] (36) Chunk.scala; (30) Span.scala; (17) data.scala - classify; flag API additions
- [ ] (62) ChunkTest.scala
- [x] (45) SpanTest.scala - session fix (interceptThrown), ruled

### kyo-combinators (uniform small diffs; classify one, apply the reading to all)
- [ ] (11) AbortCombinators; (11) AsyncCombinators; (11) ChoiceCombinators; (40)
  EmitCombinators; (11) EnvCombinators; (33) KyoCombinators; (11) MaybeCombinators
- [ ] (29) README.md

### kyo-compile-bench
- [x] (78) CompileBench.scala; (51) ExpansionDump.scala; (50) CompileBenchNegativeTest.scala -
  session single-kernel rework, ruled by the swap
- [ ] fixtures/ (13 files) and fixtures-expansion/ (4) and fixtures-negative/ (1) - new corpus;
  classify (project is new on this branch relative to main? verify)

### Small modules
- [ ] (90) kyo-ai/LLM.scala - capture re-spell is session-ruled; classify the rest
- [x] (47) kyo-browser/Browser.scala - clone snapshot move, session-ruled
- [ ] (12) kyo-direct/AsyncShift.scala
- [ ] (21) kyo-http/HttpHandler.scala; (42) HttpClientBackend.scala; (34) RouteUtil.scala;
  (93) UnsafeServerDispatch.scala - dispatch fix and merge resolutions are session work;
  classify remainder
- [ ] (13) kyo-jsonrpc/JsonRpcEndpointImpl.scala
- [ ] (80) kyo-parse/Parse.scala
- [ ] (26) kyo-scheduler/InternalClockTest.scala
- [ ] (22/31/67/13) kyo-slack Slack/SlackReconnect/SlackSocketEngine/SlackReconnectTest
- [ ] (18) kyo-system/PathPlatformSpecific.scala
- [x] (13) kyo-ui/ReactiveUITeardownTest.scala - session workaround removal, ruled
- [ ] (138) kyo-workers/ForkQueue.scala; (25) WorkersException.scala
- [ ] (13) kyo-zio/ZStreams.scala

### Excluded from minimization (session artifacts; the ship port excludes them)
reviews/, bench-results/, root-level session .md files, .recovery/, qa-artifacts/, prior-art/,
optimization-candidates/, backlog-sections/, .claude/ trees.

## Flag queue (semantic divergences awaiting a ruling; append as found)

- scripts/ci-test.sh, scripts/build.sh, .github/workflows/build.yml, project/TestKyo.scala:
  hunks not yet classified; origin unknown to this session.
- kyo-slack, kyo-workers, kyo-zio, kyo-direct, kyo-jsonrpc, kyo-scheduler diffs: not traceable
  to any ruling this session knows; classification pending.
- (append here as the pass proceeds)
