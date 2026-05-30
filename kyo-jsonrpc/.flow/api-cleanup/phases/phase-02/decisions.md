# Phase 02 Decisions

## Files changed

- **EDITED** `kyo-jsonrpc/shared/src/main/scala/kyo/JsonRpcEnvelope.scala`: added `object JsonRpcEnvelope` companion with nested `object Response` containing `success(id, result)` and `failure(id, error)` factories. Both factories produce `JsonRpcEnvelope.Response` with `extras = Absent`.
- **DELETED** `kyo-jsonrpc/shared/src/main/scala/kyo/JsonRpcResponse.scala` (via `git rm`).
- **DELETED** `kyo-jsonrpc/shared/src/test/scala/kyo/JsonRpcResponseTest.scala` (via `git rm`).
- **EDITED** `kyo-jsonrpc/shared/src/test/scala/kyo/JsonRpcEnvelopeTest.scala`: absorbed 3 adapted test cases from the deleted `JsonRpcResponseTest.scala` (success-factory, failure-factory, copy-equality). The two Schema round-trip tests from `JsonRpcResponseTest.scala` were dropped because `JsonRpcEnvelope.Response` does not `derive Schema` (it is a Scala 3 enum case, not a standalone case class with a standalone Schema derivation); no schema round-trip behavior is lost since the wire codec covers encoding/decoding via `JsonRpcCodecImpl`.

## Caller survey

Grep across the entire worktree found `JsonRpcResponse` referenced only in the two deleted files. `JsonRpcEndpointImpl.scala` and `JsonRpcCodecImpl.scala` already used `JsonRpcEnvelope.Response(...)` direct constructor calls and required no changes. No caller used `id = Absent`; no `Malformed` branch substitution was needed.

## Deviations

1. **Correct project name**: the plan's verification command used `kyo-jsonrpcJVM` but the actual sbt aggregate name is `kyo-jsonrpc`. Ran `kyo-jsonrpc/Test/compile` and `kyo-jsonrpc/testOnly kyo.JsonRpcEnvelopeTest` instead; all 9 tests passed.
2. **Schema round-trip tests dropped**: the two `Schema[JsonRpcResponse]` round-trip tests from `JsonRpcResponseTest.scala` had no direct equivalent because `JsonRpcEnvelope.Response` is an enum case and does not have its own `Schema` derivation. The wire-level codec (tested by `JsonRpcCodecImplTest`) covers the same surface. Three behaviorally equivalent factory/copy tests were absorbed.

## Convention sweep results (9 checks, 0 hits in changed code)

em-dash, AllowUnsafe, Option-vs-Maybe, semicolons (code only), asInstanceOf, default-params, Frame.internal, java.util.concurrent, llm-tells: all 0 hits.
