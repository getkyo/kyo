# Phase 6 In-Flight Review (pulse 1)

Pulse 1: 2026-05-28T07:13Z
Files reviewed:
- `kyo-jsonrpc/shared/src/main/scala/kyo/ProgressPolicy.scala` (61 LOC)
- `kyo-jsonrpc/shared/src/main/scala/kyo/internal/ProgressEngine.scala` (87 LOC)
- `kyo-jsonrpc/shared/src/main/scala/kyo/JsonRpcEndpoint.scala` (77 LOC)
- `kyo-jsonrpc/shared/src/main/scala/kyo/internal/JsonRpcEndpointImpl.scala` (841 LOC, relevant sections)
- `kyo-jsonrpc/shared/src/test/scala/kyo/JsonRpcEndpointTest.scala` (18 top-level labels)

---

## Plan anchor

- **ProgressPolicy.scala field count**: 6/6 (progressMethod, extractInboundToken, extractRequestToken, stampOutboundToken, encodeProgressParams, enforceMonotonic) — exact match.
- **internal/ProgressEngine.scala**: present, 87 LOC. Implements `buildProgressSink` only. Does NOT implement `callWithProgress`, `callPartialResults`, `subscribeProgress`, `unsubscribeProgress`, or the inbound step-1b intercept. Those four are in `JsonRpcEndpointImpl.scala` directly.
- **JsonRpcEndpoint.scala stubs replaced**: YES. No `NotImplementedError("Phase 6")` hits. All four methods delegate to `impl.*` without stubs.
- **JsonRpcEndpointImpl.scala progressStreams field**: YES. `private[kyo] val progressStreams: ConcurrentHashMap[Structure.Value, Channel[Structure.Value]]` at line 59; referenced at 14 sites throughout the file.
- **Step 1b intercept exists**: YES. `case Present(ppolicy) if method == ppolicy.progressMethod =>` at line 598 in `decodeCallback`.
- **ProgressPolicyTest.scala**: ABSENT. No such file exists anywhere in the tree. The plan requires it as the sole test file for all 14 Phase 6 tests.
- **Phase 6 test count in existing files**: 0. No progress-related test labels found in `JsonRpcEndpointTest.scala`. All 18 labels there are pre-Phase-6 tests.
- **Config.progress default**: `Absent` (line 62 in JsonRpcEndpoint.scala). Plan says `Present(ProgressPolicy.lsp)`. This is a divergence.
- **PHASE-7-PREP.md pre-created**: YES. This file (293 LOC) exists as an untracked file. Phase 6 is not complete. Premature prep is a scope-drift signal.

---

## Convention sweep

- em-dashes: 0
- asInstanceOf: 0
- `Option[`: 0 (only `fromOption` in ProgressPolicy, which is correct bridging from stdlib)
- `Frame.internal`: 0
- semicolons: 0 (the one counted hit is in a package path string, not a statement separator)
- `AllowUnsafe` sites: all carry `// Unsafe:` comments as required

---

## Compile state

```
[error] -- [E007] Type Mismatch Error: JsonRpcEndpointImpl.scala:294:16
  Found:    Stream[T, Async & Abort[JsonRpcError | Closed]] < Sync
  Required: Stream[T, Async & Abort[JsonRpcError | Closed]]

[error] -- [E007] Type Mismatch Error: JsonRpcEndpointImpl.scala:346:16
  Found:    Stream[Structure.Value, Async] < Sync
  Required: Stream[Structure.Value, Async & Abort[Closed]]

[error] -- [E007] Type Mismatch Error: JsonRpcEndpointImpl.scala:666:84
  Found:    Structure.Value < Any
  Required: Structure.Value

3 errors — does not compile
```

---

## Reward-hacking / drifting / scope-cutting

| Pattern | Verdict | Citation |
|---------|---------|---------|
| ProgressPolicyTest.scala missing | CRITICAL SCOPE CUT | Plan line 372: "all Phase 6 tests" must be in this file; 0/14 tests written |
| PHASE-7-PREP.md created before Phase 6 is green | DRIFT | Untracked file at tree root; Phase 6 does not compile |
| `ProgressEngine.buildProgressSink` only | PARTIAL IMPL | Plan says ProgressEngine implements progress engine behaviors; `callWithProgress` / `callPartialResults` / `subscribeProgress` / `unsubscribeProgress` landed in JsonRpcEndpointImpl instead; structural location differs from plan but logic is present |
| `Config.progress = Absent` | DIVERGENCE | Plan says default should be `Present(ProgressPolicy.lsp)` (IMPLEMENTATION.md line 375: "update Config default to progress = Present(ProgressPolicy.lsp)"); current default is Absent |
| 3 compile errors in JsonRpcEndpointImpl | BLOCKER | `callPartialResults` body is inside a `Sync.Unsafe.defer {}` producing an unwanted `< Sync` wrapper on the Stream return; `subscribeProgress` has same issue; line 666 has a stray `< Any` on a `Structure.Value` |
| No weak assertions | OK | No `assert(true)`, `case _ => succeed`, `Result.Failure(_)` wildcard guards found |

---

## CRITICAL

1. **ProgressPolicyTest.scala does not exist.** 0/14 tests written. This is the primary deliverable for Phase 6. Agent must create the file and write all 14 tests (Tests 65-78) before Phase 6 can be considered complete.

2. **Does not compile.** Three type-mismatch errors in `JsonRpcEndpointImpl.scala`:
   - `callPartialResults` return type is `Stream[T,...] < Sync` because the whole body is wrapped in `Sync.Unsafe.defer {}`. The outer `Sync` wrapper must be eliminated (the method signature returns `Stream[T,...]`, not a suspended stream).
   - `subscribeProgress` same issue: body inside `Sync.Unsafe.defer {}` produces `Stream[...] < Sync` but signature expects `Stream[...]`.
   - Line 666: a `Structure.Value < Any` is passed where `Structure.Value` is expected; needs `Sync.Unsafe.evalOrThrow` or restructuring.

3. **Config.progress default is `Absent`, not `Present(ProgressPolicy.lsp)`.** Plan explicitly requires the default be changed to `Present(ProgressPolicy.lsp)` (IMPLEMENTATION.md line 375).

4. **PHASE-7-PREP.md was created prematurely.** Phase 6 must be fully green before Phase 7 prep is written. The file should not exist yet.

---

## Recommendation: STEER

Steer the agent to fix in this order:
1. Fix the 3 compile errors in `JsonRpcEndpointImpl.scala` (type mismatches at lines 294, 346, 666).
2. Change `Config.progress` default from `Absent` to `Present(ProgressPolicy.lsp)`.
3. Create `ProgressPolicyTest.scala` with all 14 tests (Tests 65-78).
4. Verify `sbt 'kyo-jsonrpc/Test/compile'` is clean before proceeding.
5. Do NOT touch PHASE-7-PREP.md or start Phase 7 until all 14 tests are written and compile is green.
