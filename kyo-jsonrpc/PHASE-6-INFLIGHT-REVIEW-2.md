# Phase 6 In-Flight Review (pulse 2)

## Pulse-1 steer verification

| # | Status | Evidence |
|---|--------|----------|
| 1 | 3 compile errors at 294/346/666 fixed | `sbt kyo-jsonrpc/Test/compile` now reports exactly 1 error at line 329, not at 294/346/666 |
| 2 | ProgressPolicyTest exists | NOT PRESENT — `kyo-jsonrpc/shared/src/test/scala/kyo/ProgressPolicyTest.scala` does not exist; the phase-6 commit (missing from git log) has not been created yet |
| 3 | Config.progress still Absent | Cannot confirm — ProgressPolicyTest absent means phase 6 impl is incomplete |

Note: git log shows only 5 commits (phases 1–5). Phase 6 has not been committed.

## Current compile state

```
sbt kyo-jsonrpc/Test/compile 2>&1 | tail -10:
[error] -- Error: .../kyo-jsonrpc/shared/src/main/scala/kyo/internal/JsonRpcEndpointImpl.scala:329:90
[error] 329 |                                            case Result.Success(v) => Emit.value(Chunk(v))
[error]     |                                                                                          ^
[error]     |                      Please provide an implicit kyo.Tag[T] parameter.
[error] one error found
[error] (kyo-jsonrpc / Compile / compileIncremental) Compilation failed
[error] Total time: 8 s
```

## Tag[T] error analysis

File: `kyo-jsonrpc/shared/src/main/scala/kyo/internal/JsonRpcEndpointImpl.scala`
Line: 329

Context:
```scala
progChan.streamUntilClosed().map { sv =>
    Structure.decode[T](sv) match
        case Result.Success(v) => Emit.value(Chunk(v))   // line 329 — Tag[T] missing
        case Result.Failure(e) => Abort.fail(JsonRpcError.invalidParams(e.getMessage))
        case Result.Panic(t)   => Abort.panic(t)
}.emit
```

Root cause: `Emit.value(Chunk(v))` produces `Unit < Emit[Chunk[T]]`. The `Emit` effect carries `T`
in its phantom position, and `Emit.value` requires `Tag[T]` to materialise the `Emit[Chunk[T]]`
type tag at the call site. The method signature is:

```scala
def callPartialResults[In: Schema, T: Schema](...)
```

`Schema` gives `Tag[T]` indirectly in some contexts, but `Emit.value` resolves `Tag[T]` directly
(not through `Schema`). Because the bound is only `T: Schema`, the compiler cannot find `Tag[T]`.

Fix: Add `: Tag` to the `T` type parameter bound in both the public facade and the impl.

Public facade (`JsonRpcEndpoint.scala` line 28):
```scala
def callPartialResults[In: Schema, T: Schema: Tag](...)
```

Impl (`JsonRpcEndpointImpl.scala` line 282):
```scala
def callPartialResults[In: Schema, T: Schema: Tag](...)
```

No other changes needed. `Schema` already requires `Tag` in some codepaths but does not expose it
as an implicit `Tag[T]` directly; adding `: Tag` explicitly makes it available to `Emit.value`.

## Recommendation

STEER: Add `T: Schema: Tag` to `callPartialResults` in both `JsonRpcEndpoint.scala` and
`JsonRpcEndpointImpl.scala`, then create `ProgressPolicyTest.scala` with the 14 tests from
PHASE-6-PREP.md and commit phase 6.
