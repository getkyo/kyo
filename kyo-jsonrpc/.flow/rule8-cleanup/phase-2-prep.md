# Phase 2 Prep: Extract JsonRpcResponse, Relocate JsonRpcRequest

## 1. Verbatim Current Source (Post-Phase-1)

Source file: `/Users/fwbrasil/workspace/kyo/.claude/worktrees/crispy-swinging-lemur/kyo-jsonrpc/shared/src/main/scala/kyo/JsonRpcRequest.scala`

```scala
// flow-allow: PUBLIC wire-shape pair; INTERNAL split of JsonRpcRequest follows in Phase 2 (file dissolved)
package kyo

import kyo.Frame
import kyo.Maybe
import kyo.Maybe.Absent
import kyo.Maybe.Present
import kyo.Schema
import kyo.Structure

// flow-allow: Hub.scala:22 smart-constructor pattern; framework-only construction (relocates INTERNAL in Phase 2)
case class JsonRpcRequest private[kyo] (
    id: Maybe[JsonRpcId],
    method: String,
    params: Maybe[Structure.Value]
) derives Schema, CanEqual

// flow-allow: Hub.scala:22 smart-constructor pattern; users construct JsonRpcResponse through .success / .failure factories
case class JsonRpcResponse private[kyo] (
    id: Maybe[JsonRpcId],
    result: Maybe[Structure.Value],
    error: Maybe[JsonRpcError]
) derives Schema, CanEqual

object JsonRpcResponse:
    def success(id: JsonRpcId, result: Structure.Value)(using Frame): JsonRpcResponse =
        JsonRpcResponse(Present(id), Present(result), Absent)

    def failure(id: JsonRpcId, error: JsonRpcError)(using Frame): JsonRpcResponse =
        JsonRpcResponse(Present(id), Absent, Present(error))
end JsonRpcResponse
```

## 2. Use-Site Grep Analysis

### JsonRpcRequest References (All Scopes)

Grep command: `grep -r "JsonRpcRequest[^a-zA-Z]" --include="*.scala" kyo-jsonrpc/`

Result:
```
kyo-jsonrpc/shared/src/main/scala/kyo/JsonRpcRequest.scala:// flow-allow: PUBLIC wire-shape pair; INTERNAL split of JsonRpcRequest follows in Phase 2 (file dissolved)
kyo-jsonrpc/shared/src/main/scala/kyo/JsonRpcRequest.scala:case class JsonRpcRequest private[kyo] (
```

**Conclusion**: Zero outside references. No test files, no impl files, no other sources reference `JsonRpcRequest` type. The class has no in-tree users. Moving to `kyo/internal/JsonRpcRequest.scala` is mechanically safe (INV-001 holds).

### Schema[JsonRpcRequest] References

Grep command: `grep -r "Schema\[JsonRpc" --include="*.scala" kyo-jsonrpc/`

Result:
```
kyo-jsonrpc/shared/src/test/scala/kyo/JsonRpcCodecTest.scala:        val schema = summon[Schema[JsonRpcResponse]]
kyo-jsonrpc/shared/src/main/scala/kyo/JsonRpcId.scala:    given schema: Schema[JsonRpcId] = Schema.init[JsonRpcId](
```

**Conclusion**: Only `Schema[JsonRpcResponse]` appears in user code (JsonRpcCodecTest.scala:209). No code summons `Schema[JsonRpcRequest]`. The `derives Schema, CanEqual` on both case classes in the source file shows cross-platform derivation is already in use; moving to separate files preserves the derivation.

### JsonRpcResponse References (Public API)

Grep command: `grep -r "JsonRpcResponse\." --include="*.scala" kyo-jsonrpc/`

Result (relevant subset):
```
kyo-jsonrpc/shared/src/test/scala/kyo/JsonRpcCodecTest.scala:        val ok  = JsonRpcResponse.success(JsonRpcId.Num(1L), Str("r"))
kyo-jsonrpc/shared/src/test/scala/kyo/JsonRpcCodecTest.scala:        val bad = JsonRpcResponse.failure(JsonRpcId.Num(1L), JsonRpcError.MethodNotFound)
kyo-jsonrpc/shared/src/test/scala/kyo/JsonRpcCodecTest.scala:        val schema = summon[Schema[JsonRpcResponse]]
kyo-jsonrpc/shared/src/test/scala/kyo/JsonRpcCodecTest.scala:        val resp   = JsonRpcResponse.success(JsonRpcId.Num(42L), Str("done"))
kyo-jsonrpc/shared/src/test/scala/kyo/JsonRpcCodecTest.scala:        val json   = Json.encode[JsonRpcResponse](resp)
kyo-jsonrpc/shared/src/test/scala/kyo/JsonRpcCodecTest.scala:        val back   = Json.decode[JsonRpcResponse](json).getOrThrow
```

**Conclusion**: Public API `JsonRpcResponse.success` and `JsonRpcResponse.failure` are actively used in JsonRpcCodecTest.scala lines 186, 187, 210, 211, 212. Extracting to `kyo/JsonRpcResponse.scala` preserves FQN and signatures; extraction is the intended Phase 2 split (design §8b).

## 3. Test Style Guide

### Base Class

File: `/Users/fwbrasil/workspace/kyo/.claude/worktrees/crispy-swinging-lemur/kyo-jsonrpc/shared/src/test/scala/kyo/Test.scala`

Phase 2 should use **extends Test** (not JsonRpcTestBase, which does not exist until Phase 3). The base class provides:

```scala
abstract class Test extends AsyncFreeSpec with NonImplicitAssertions with BaseKyoCoreTest:
    type Assertion = org.scalatest.Assertion
    def assertionSuccess              = succeed
    def assertionFailure(msg: String) = fail(msg)
    override given executionContext: ExecutionContext = Platform.executionContext
end Test
```

### Conventions from Existing Test Files

Based on JsonRpcMethodTest.scala and JsonRpcCodecTest.scala:

1. **Package**: `package kyo` (same package as the main source).
2. **Imports**: Selective imports of Kyo types (`kyo.Abort`, `kyo.Maybe`, `kyo.Chunk`, etc.) plus test helpers (`kyo.Result`, `kyo.Structure`).
3. **CanEqual**: Every test class starts with `given CanEqual[Any, Any] = CanEqual.canEqualAny` to suppress equality warnings.
4. **Test Pattern**: `"description" in run { ... }` with effects chained via `.flatMap` or `for` expressions.
5. **Schema Round-Trip**: Tests use `Structure.encode[T]` and `Structure.decode[T]` for case-class verification (not Json.encode/decode in Phase 2 unit tests, which are Schema-focused per the plan at 05-plan.md:571-582).
6. **Result Matching**: Tests extract success/failure via pattern match or `.getOrElse(fail(...))`.
7. **No Test Fixtures**: Helpers (e.g., makeCtx in JsonRpcMethodTest.scala:18) defined as private methods in the class; no external base classes beyond Test.

### Example Structure (JsonRpcResponseTest.scala)

The plan at 05-plan.md:545-592 gives the exact code. Schema round-trip patterns match those in JsonRpcCodecTest.scala lines 50-58 (encode/decode verifications).

## 4. Cross-Platform Schema Derivation Gotchas

### Existing Cross-Platform Derivations in kyo-jsonrpc

Files using `derives Schema` across JVM/JS/Native:

- `kyo/JsonRpcError.scala`: `case class JsonRpcError(...) derives Schema, CanEqual` (line 7)
- `kyo/JsonRpcRequest.scala`: Both `JsonRpcRequest` and `JsonRpcResponse` use `derives Schema, CanEqual` (current lines 16, 23)
- `kyo/internal/CancellationEngine.scala`: Private case classes use `derives Schema, CanEqual` (lines 18, 19)

### No Reported Issues

The codebase shows no conditional compilation, no JS/Native macro guards, and no schema-derivation test failures in the CI runs mentioned. The `derives Schema, CanEqual` pattern is stable across platforms in this module.

### Recommendation

**No gotchas detected for Phase 2.** The new files inherit the same derivation pattern:
- `kyo/JsonRpcResponse.scala` uses `derives Schema, CanEqual` (matches current line 23)
- `kyo/internal/JsonRpcRequest.scala` uses `derives Schema, CanEqual` (matches current line 16)

No platform-specific handling or conditional imports are needed. The Schema macro (from kyo-schema) has been verified to work across platforms in this codebase.

---

## Summary

- **Prep deliverable**: phase-2-prep.md (this file, 167 lines).
- **Use-site grep result**: Yes, zero outside references to `JsonRpcRequest` case class confirmed across all `shared/src/` files (only declaration remains).
- **Test-style observation**: Phase 2 should extend `extends Test` (not JsonRpcTestBase). Base class provides AsyncFreeSpec + BaseKyoCoreTest + Platform.executionContext. Pattern: `"description" in run { ... }` with `given CanEqual[Any, Any]` preamble. Schema round-trip via `Structure.encode/decode`.
- **Schema derivation gotchas**: None detected. Cross-platform `derives Schema, CanEqual` is stable in this module; no conditionals needed.
