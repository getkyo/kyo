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
- Abort acceptance moved after the region (done-clause re-raise of unaccepted errors; kernel2
  deliberately has no dispatch accept filter). Per the migration ledger.
- Loop-family handler clauses are answer-style everywhere (the clause answers through the
  Loop outcome instead of applying a passed continuation). Per the migration ledger.

## Checklist: every changed file (diff line count in parentheses)

Legend: [x] processed (aligned, or classified with every hunk covered by the register);
[ ] pending. D = the file only exists on one side (old-kernel removal or new machinery), where
the whole diff is the ruled swap itself.

### Build, CI, scripts
- [x] (0) .github/workflows/build.yml - adopted main's landed version; branch residue was the
  older generation of the same CI work (NATIVE_HEAVY isolation superseded by NATIVE_SKIP)
- [x] (106) build.sbt - the kernel swap and single-kernel compile-bench; reviewed this session
- [x] (0) project/TestKyo.scala - adopted main's; --dry-run et al present on both sides
- [x] (0) scripts/build.sh - adopted main's
- [x] (0) scripts/ci-test.sh - adopted main's (includes the 8c/8d self-tests the merge dropped)

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
- [x] (205) kyo/ArrowTest.scala - new file for new machinery (Arrow did not exist on main)
- [x] (66) kyo/KyoForeachCollTest.scala - heir audit: full parity (base-class swap +
  discard wrappers only; every assertion identical)
- [x] (29) kyo/KyoForeachTest.scala - helpers only, no test cases; adapted 1:1
- [x] (163) kyo/KyoTest.scala - classified: interim scalatest base, ruled renames, semantic asserts
- [x] (2845) kernel/ArrowEffectTest.scala - heir audit: 22 heirs, 9 n/a-by-design (3
  interceptor-seam, 6 handlePartial-removal), 7 missing in 3 gaps. handleFirst deep-chain and
  boxed pins re-enabled this session (suite folded onto the primitive); the remaining gaps
  are AUTHORED: non-Const inputs and outputs group (3 tests), two-effects-interleaved-at-depth
  (1000 crossings), handleCatching boxed pass-through. Compile-unverified until sbt clears.
- [x] (346) kernel/ContextEffectTest.scala - heir audit: 11/11 full parity, plus new
  release/default/crossing coverage
- [x] (1551) kernel/EffectTest.scala - heir audit: all heired after this session re-enabled
  defer-with-catching and combining-multiple-effects; detach suite parked on the missing
  Effect.detach
- [x] (672) kernel/IsolateTest.scala - heir audit: 24 heired, 1 n/a (interceptor restoration),
  3 missing collapsing to one gap: a pending Keep-row arrow effect crossing the isolation
  boundary and handled outside. AUTHORED: two run-group tests (direct and subtype-tag
  crossing over updateA). Compile-unverified until sbt clears.
- [x] (459) kernel/LoopTest.scala - heir audit: 73/73 full parity plus the new constructor
  and nesting groups
- [x] (940) kernel/PendingTest.scala - heir audit: all heired after this session re-enabled
  TestEffect3 + multiple operations; one by-design evalNow semantic inversion documented in
  its heir
- [x] (166) internal/CanLiftTest.scala - heir audit done by name-and-content map: all 11 main
  cases have heirs under renamed descriptions, plus new cases (module-object rejection,
  case-object fast path); nothing missing
- [x] D (102) internal/ContextTest.scala - old kernel removal
- [x] (179) internal/DebuggerTest.scala - new file, new machinery (2 ignored tests remain a
  ship-list item)
- [x] (357) internal/EffectTraceTest.scala - new file, new machinery; heirs the deleted
  Trace/TracePool coverage together with EffectTracePhysicalTest and EffectTraceThreadingTest
- [x] (62) internal/EvalCaptureTowerTest.scala - new file, new machinery
- [x] (944) internal/EvalTest.scala - new file, new machinery (the evaluator is new)
- [x] (117) internal/HandlerTest.scala - new file, new machinery
- [x] (167) internal/ImplicitsTest.scala - new file, new machinery
- [x] (85) internal/NestedTest.scala - new file, new machinery (representation contract pins)
- [x] (541) internal/StackTest.scala - new file, new machinery
- [x] D (132) internal/TracePoolTest.scala - old kernel removal
- [x] D (334) internal/TraceTest.scala - old kernel removal
- [x] (564) outsidekyo/PendingExpansionSiteTest.scala - new file: outside-package expansion
  pins (the accessibility trap the kernel skill documents); new machinery
- [x] (new) jvm/internal/SafepointConcurrencyTest.scala - new-machinery concurrency pins for
  the slot/stop protocol (heirs the deleted jvm SafepointTest's concurrency lanes)
- [x] D jvm-native tests (ThreadingTests, SafepointTest, SafepointUnstartedThreadTest,
  EffectTracePhysicalTest) - this session's platform extraction, ruled
- [x] D (68) jvm/BytecodeTest.scala - old kernel removal
- [x] (84) jvm/ArrowEffectBytecodeTest.scala; (107) jvm/PendingBytecodeTest.scala - new
  bytecode pins; together they heir the deleted jvm/BytecodeTest for the new node shapes
- [x] D (324/849/73) jvm Safepoint/TracePool concurrency tests - old kernel removals
- [x] D (455) jmh/KernelBench.scala - new board; (268) ProtoKernelBench - session rows
- [x] (342/329/345/309) bench-cross CatsEffect/Turbolift/Zio/ZioBlocks - new comparison-bench
  project (kyo-kernel-bench-cross), session work; new machinery
- [x] (196) CONTRIBUTING.md; (743) README.md - BOTH FLAGGED (see queue): README was deleted
  with no replacement; CONTRIBUTING is stale (kyo-kernel2 naming, Handlers/evalNow references,
  links to ship-excluded root session docs)

### kyo-prelude
- [x] (54) Abort.scala - acceptance-after-region migration (register), design comment; no alignable residue
- [x] (31) Batch.scala - handleCont + Arrow continuation type (register); new design comment kept
- [x] (39) Check.scala - handleCont/handleLoopState/answer-style (register)
- [x] (34) Choice.scala - handleCont + cont(_) eta form (register); Debug import removal (flagged)
- [x] (102) Emit.scala - answer-style/positional (register); runFirst Arrow-in-signature
  FLAGGED (see queue, covers Poll.runFirst and the StreamCompression/ZStreams ripple)
- [x] (201) Local.scala - fork/join reshape (campaign design); docs describe the new
  semantics accurately so no doc restoration applies; public-surface NOTE in queue
- [x] (12) Memo.scala - register-covered renames only
- [x] (115) Pipe.scala - answer-style clause migration only (register)
- [x] (44) Poll.scala - handleLoopState/answer-style (register); doc reword matches new
  continuation shape, kept
- [x] (84) Sink.scala - answer-style clause migration only (register)
- [x] (572) Stream.scala - answer-style loop clauses + handleCont renames (register); Debug import removal (flagged)
- [x] (27) Var.scala - register-covered renames only
- [x] (130) debug/Debug.scala - full deletion; covered by the standing Debug FLAG, awaiting
  ruling (rebuild on Debugger seam vs ship without)
- [x] (91) ChoiceTest.scala - issue #208 pendingUntilFixed removed (new kernel makes deep
  Choice stack-safe: test strengthened, keep) + new bracket-interaction coverage
- [x] (53) EmitTest.scala - runFirst ripple (`cont()` to `cont(())`); rides the runFirst FLAG
- [x] (64) LocalTest.scala - Isolate API migration (register)
- [x] (235) DebugTest.scala - deleted with Debug; rides the standing Debug FLAG

### kyo-core
- [x] (16/25/25) scheduler/IOTaskPlatformSpecific (js-wasm/jvm/native) - new internal
  machinery (per-platform status-word handle), pure additions
- [x] (176) jvm/StreamCompression.scala - almost entirely the runFirst thunk-to-Arrow ripple;
  rides that FLAG (collapses if the function shape is restored)
- [x] (128) Async.scala - session work (Keep restoration, merge); docs to verify once more
- [x] (12) Clock.scala - Isolate import only (register)
- [x] (348) Fiber.scala - session work; align docs of unchanged members
- [x] (12) KyoApp.scala - Isolate import only (register)
- [x] (115) StreamCoreExtensions.scala - answer-style migration (register) + session
  groupedWithin fix; nothing alignable
- [x] (50) Sync.scala - defer drops the `Safepoint ?=>` leak (ruling 21: Safepoint never
  public); ensure/acquireRelease rebuilt on Effect.bracket; new outcome-aware
  acquireReleaseWith overload NOTED in queue
- [x] (29) scheduler/IOPromise.scala - drops Safepoint.Interceptor (internal; kernel2 has no
  interceptor seam, same substrate change behind the Debug flag)
- [x] (726) scheduler/IOTask.scala - kernel2 integration, all internal (private[kyo]/
  private[scheduler]); apply takes the session's isolate-crossing signature; new machinery
- [x] (26) AsyncTest.scala; (40) MeterTest.scala; (84) ScopeTest.scala;
  (13) StreamCoreExtensionsTest.scala; (36) SyncTest.scala; (203) IOTaskTest.scala - all
  pure regression/coverage additions pinning session semantics (park, ensure-bracket,
  contention); keep

### kyo-data
- [x] (36) Chunk.scala; (30) Span.scala - orphaned updated additions FLAGGED (see queue);
  data.scala reverted to main (zero diff, bug.exception unused)
- [x] (62) ChunkTest.scala - updated + toIndexed test additions; see queue notes
- [x] (45) SpanTest.scala - session fix (interceptThrown), ruled

### kyo-combinators (uniform small diffs; classify one, apply the reading to all)
- [x] (11) AbortCombinators; (11) AsyncCombinators; (11) ChoiceCombinators; (40)
  EmitCombinators; (11) EnvCombinators; (33) KyoCombinators; (11) MaybeCombinators - all
  Debug-deletion fallout (debugValue/debugTrace removal); rides the standing Debug FLAG
- [x] (29) README.md - removes the debugValue/debugTrace section; rides the Debug FLAG

### kyo-compile-bench
- [x] (78) CompileBench.scala; (51) ExpansionDump.scala; (50) CompileBenchNegativeTest.scala -
  session single-kernel rework, ruled by the swap
- [x] fixtures/ (13 files) and fixtures-expansion/ (4) and fixtures-negative/ (1) - verified:
  the whole kyo-compile-bench project is new vs main; all fixture files are new machinery

### Small modules
- [x] (34) kyo-ai/LLM.scala - handleLoopState/answer-style (register) + capture-row narrowing
  from the Isolate fix (session-ruled)
- [x] (47) kyo-browser/Browser.scala - clone snapshot move, session-ruled
- [x] kyo-direct/internal/AsyncShift.scala - one added @targetName("resultIntoTuple");
  kernel-forced (the pending type's new erasure makes the two resultInto overloads clash)
- [x] kyo-http HttpHandler/HttpClientBackend/RouteUtil/UnsafeServerDispatch - kernel-forced:
  IOTask.unscoped replaces the old (Trace, Context) constructor, RouteUtil answer-style,
  serveRequest genericized to [In, Out, E] because an Any-erased computation would re-enter
  the lift and nest as data (which is why HttpHandler's encodeResponseUnchecked is deleted);
  panic-logging onComplete NOTED in queue
- [x] (0) kyo-jsonrpc/JsonRpcEndpointImpl.scala - RESTORED `sv.eval(using frame)` (the
  Pending.scala eval restoration re-admits the explicit frame); zero diff
- [x] (23) kyo-parse/Parse.scala - handleLoopState/answer-style (register) + cast design comment
- [x] (0) kyo-scheduler/InternalClockTest.scala - RESTORED main's plain literals (the
  `1_000L` underscores were churn); zero diff
- [x] kyo-slack Slack/SlackReconnect/SlackSocketEngine/SlackReconnectTest - RESTORED to main
  verbatim (zero diff): the branch had narrowed the Keep to Sync at 11 sites; the session's
  capture-row fix makes main's `Isolate[S, Abort[SlackException] & Async, S]` workable again,
  matching the kyo-core combinator restoration. Compile-unverified until sbt is cleared.
- [x] (0) kyo-system/PathPlatformSpecific.scala - RESTORED main's Windows-mtime
  normalization block that the branch had dropped (a real behavior regression, not
  kernel-related); zero diff
- [x] (13) kyo-ui/ReactiveUITeardownTest.scala - session workaround removal, ruled
- [x] kyo-workers ForkQueue.scala (new, `???` bodies) + WorkersException.scala (new) +
  root kyo-workers-scheduling-design.md - FOREIGN-CAMPAIGN content (workers scheduling API
  sketch, commit b713cf7f50), not kernel work; the ship port excludes all three. Left on
  the branch to preserve that campaign's WIP.
- [x] (1) kyo-zio/ZStreams.scala - single line, rides the runFirst FLAG

### Excluded from minimization (session artifacts; the ship port excludes them)
reviews/, bench-results/, root-level session .md files, .recovery/, qa-artifacts/, prior-art/,
optimization-candidates/, backlog-sections/, .claude/ trees.

## Validation record (sbt cleared)

- Kernel suite green on all four platforms: JVM 1151/1151, JS 1111/1111 (the 15 self-stop
  park tests converted through the SafepointStop platform helper), Native 1134/1134,
  Wasm 1111/1111.
- Module suites green: kyo-core 3436 (StreamCompression included), kyo-data, kyo-prelude,
  kyo-slack (wide-Keep restoration compiles and passes), kyo-zio 21/22, kyo-jsonrpc,
  kyo-scheduler. Test compiles green additionally for kyo-system, kyo-direct, kyo-actor.
- The single red repo-wide is ZStreamsTest "round trip: get then run" (the bracket-vs-runFirst
  design fork below).
- Doctests: kyo-kernel/README.md 29/29 blocks, kyo-prelude/README.md 34/34; the kernel's
  doctest task re-enabled (obsolete interim carve-out removed) with the fresh-driver guard
  restored.
- Still owed before ship: kyo-test/kyo-doctest migration off the scalatest interim, the JMH
  board re-run, CI-faithful podman runs.

## Flag queue (semantic divergences awaiting a ruling; append as found)

- RESOLVED by ruling: kyo.debug.Debug stays removed for now; a future kyo-debugger module
  will carry a real debugging proto. The prelude README's Debug section and the Eval scaladoc
  mention are cleaned up; the combinators/README fallout stands as-is.
- kyo-slack, kyo-workers, kyo-zio, kyo-direct, kyo-jsonrpc, kyo-scheduler diffs: not traceable
  to any ruling this session knows; classification pending.
- RESOLVED by ruling: Chunk.updated and Span.updated stay. Chunk's is a performance override
  of the inherited Seq.updated (constant-time last-element relink or one flat copy, replacing
  the iterator-plus-builder default); Span's is the same operation for the array-backed type.
  Both are tested; the original kernel consumer is gone but the API is kept on its own merit.
- REVISED: bug.exception in kyo-data/data.scala is restored. Its designed caller is Eval's
  unhandled path, which must construct the failure, attach the effect trace, and throw once
  (the interim failTag-based form was throw-then-catch and was rejected). Consequence: the
  restored kernel.scala failTag extension (main-verbatim per ruling 22) now has no caller on
  the branch; unhandled builds the same message via bug.exception. Options if wanted: leave
  failTag as main-verbatim surface, or drop it (breaks kernel.scala zero-diff).
- NOTE: ChunkTest gains a toIndexed test block covering API that already exists on main; kept
  as meaningful coverage, listed here for visibility.
- RESOLVED by ruling: the outside-package suite stays, renamed outsidekyo.KernelTest (file
  prefix matches kernel.scala, the module facade), and extended to represent the full effect
  cycle from an external call site: the Isolate surface (derive, implicit summon under the
  Restore <: Remove bound, nest+flatten, use, andThen), the remaining builder combinators
  (fill, zip, when, unless, filterKeys), and the context binding fork/join edge parameters.
  91 tests.
- RESOLVED by ruling: Emit.runFirst and Poll.runFirst are now private[kyo]. The Arrow shape
  stays (internal surface); the StreamCompression/ZStreams/EmitTest ripple stands as-is. All
  callers (StreamCompression, ZStreams, Actor, Stream, tests) are in package kyo.
- NOTE: Local's public surface changed with the fork/join reshape: `init(default)(forkValue,
  joinValue)` is a new overload, the regular/non-inheritable doc model is replaced by
  per-local strategies, and `initNonInheritable` remains as shorthand for
  `init(default)(_ => Absent)`. Campaign-designed machinery; listed for visibility.
- NOTE: Sync gains a public outcome-aware overload `acquireReleaseWith(acquire)((a, result) =>
  release)(use)` on top of the resource-only form, both now built on Effect.bracket. The
  checklist had recorded ensure/acquireRelease as session-approved; the new overload is listed
  here so the approval is explicit rather than remembered.
- NOTE: UnsafeServerDispatch now logs handler-fiber panics via onComplete (previously they
  vanished silently since nothing reads a handler fiber's result). Internal behavior addition,
  listed for visibility.
- RESOLVED by ruling: kyo-kernel/README.md restored from main and updated for the new kernel
  (handleCont naming, fork strategies replacing Noninheritable, answer-style handleLoop,
  handleFirst section removed as private, repeat runs exactly n, isolate derivation covers
  context effects, runtime section rewritten for budget + stop preemption); 41+/49- delta vs
  main's 737 lines. CONTRIBUTING.md rewritten against the current tree (Stack-based evaluator,
  current file inventory and test names, current handler-variant names) with the dangling
  kernel2-*.md links removed. Doctest validation pending sbt.
- FLAG (design fork, from validation; diagnosis CORRECTED by reproduction): a kernel bracket
  inside a peeled stream breaks only when the remainder crosses a FIBER boundary. New
  StreamCoreExtensionsTest pins prove the in-evaluation hand-out lane already works (runFirst
  peel and splitAt both release exactly once, after the remainder is consumed, in the same
  fiber); the red lane is a fiber completing while its result value still owes a release: the
  completion drains it, and the next fiber's entry into the spent scope panics. That lane has
  a minimal in-repo reproduction now ("a resource-carrying remainder crosses a fiber
  boundary", pendingUntilFixed) and is what ZStreamsTest hits through ZIO (each peel is its
  own ZIOs.run). The ruling narrows to: should a normally-completing fiber drain finalizers
  its own result value still carries (current behavior, protects against dropped results,
  breaks cross-fiber peels), or should the result value keep custody of them (restores main's
  pattern; a dropped result leaks unless the holder finalizes). Recommendation: keep custody
  with the value; the fiber cannot know its result carries the scope, and the interrupt path
  (IOTask.abandon calling finalizeResources) already handles the genuinely-abandoned case.
- (append here as the pass proceeds)
