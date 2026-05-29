# 05 Plan: Rule 8 (Organization) cleanup in kyo-jsonrpc (v3)

Task type: refactor
Cites design: ./02-design.md (v2 patched)
Cites invariants: ./04-invariants.md (v2)

Primary module: kyo-jsonrpc
Cross-platform set: [jvm, js, native]
Total tests across all phases: 37 (Phase 1: 0 new; Phase 2: 5 new cases in 1 new test file; Phase 3: 0 new; Phase 4: 32 new cases across 8 new test files)

## Phase 1: 8a sub-symbol relocations and PUBLIC markers

Depends on: none (first phase)

### Files to produce

- `kyo-jsonrpc/shared/src/main/scala/kyo/internal/IdStrategyEngine.scala`: holds the `private[kyo] object IdStrategyEngine` with the `mkNextId` function relocated from the `IdStrategy` companion. Internal subdir; no matching test required.

```scala
// kyo-jsonrpc/shared/src/main/scala/kyo/internal/IdStrategyEngine.scala
package kyo.internal

import kyo.*

private[kyo] object IdStrategyEngine:
    def mkNextId(strategy: IdStrategy)(using Frame): () => JsonRpcId < Sync =
        strategy match
            case IdStrategy.SequentialLong =>
                // Unsafe: id-counter init mirrors Exchange's internal counter pattern
                // flow-allow: AtomicX Unsafe init follows kyo Exchange pending-map precedent; no safe equivalent in AtomicX public API
                val counter = AtomicLong.Unsafe.init(0L)(using AllowUnsafe.embrace.danger)
                // flow-allow: unsafe deferred block bridges unsafe ops (AtomicX, Promise, Channel, Fiber) from Sync-only context
                () => Sync.Unsafe.defer(JsonRpcId.Num(counter.incrementAndGet()))
            case IdStrategy.SequentialInt =>
                // Unsafe: id-counter init mirrors Exchange's internal counter pattern
                // flow-allow: AtomicX Unsafe init follows kyo Exchange pending-map precedent; no safe equivalent in AtomicX public API
                val counter = AtomicInt.Unsafe.init(0)(using AllowUnsafe.embrace.danger)
                // flow-allow: unsafe deferred block bridges unsafe ops (AtomicX, Promise, Channel, Fiber) from Sync-only context
                () => Sync.Unsafe.defer(JsonRpcId.Num(counter.incrementAndGet().toLong))
            case IdStrategy.Custom(next) => next
end IdStrategyEngine
```

### Files to modify

- `kyo-jsonrpc/shared/src/main/scala/kyo/IdStrategy.scala`: remove `mkNextId`, add top-of-file PUBLIC marker.

```scala
// kyo-jsonrpc/shared/src/main/scala/kyo/IdStrategy.scala ; BEFORE
package kyo

enum IdStrategy derives CanEqual:
    case SequentialLong
    case SequentialInt
    case Custom(next: () => JsonRpcId < Sync)
end IdStrategy

object IdStrategy:
    private[kyo] def mkNextId(strategy: IdStrategy)(using Frame): () => JsonRpcId < Sync =
        strategy match
            case SequentialLong =>
                // Unsafe: id-counter init mirrors Exchange's internal counter pattern
                // flow-allow: AtomicX Unsafe init follows kyo Exchange pending-map precedent; no safe equivalent in AtomicX public API
                val counter = AtomicLong.Unsafe.init(0L)(using AllowUnsafe.embrace.danger)
                // flow-allow: unsafe deferred block bridges unsafe ops (AtomicX, Promise, Channel, Fiber) from Sync-only context
                () => Sync.Unsafe.defer(JsonRpcId.Num(counter.incrementAndGet()))
            case SequentialInt =>
                // Unsafe: id-counter init mirrors Exchange's internal counter pattern
                // flow-allow: AtomicX Unsafe init follows kyo Exchange pending-map precedent; no safe equivalent in AtomicX public API
                val counter = AtomicInt.Unsafe.init(0)(using AllowUnsafe.embrace.danger)
                // flow-allow: unsafe deferred block bridges unsafe ops (AtomicX, Promise, Channel, Fiber) from Sync-only context
                () => Sync.Unsafe.defer(JsonRpcId.Num(counter.incrementAndGet().toLong))
            case Custom(next) => next
end IdStrategy

// kyo-jsonrpc/shared/src/main/scala/kyo/IdStrategy.scala ; AFTER
// flow-allow: PUBLIC config-strategy sum type referenced by JsonRpcEndpoint.Config.idStrategy field
package kyo

enum IdStrategy derives CanEqual:
    case SequentialLong
    case SequentialInt
    case Custom(next: () => JsonRpcId < Sync)
end IdStrategy
```

- `kyo-jsonrpc/shared/src/main/scala/kyo/JsonRpcCodec.scala`: remove `cdpReservedKeys`, add PUBLIC marker.

```scala
// kyo-jsonrpc/shared/src/main/scala/kyo/JsonRpcCodec.scala ; BEFORE
package kyo

import kyo.Abort
import kyo.Frame
import kyo.Structure
import kyo.Sync

trait JsonRpcCodec:
    def encode(env: JsonRpcEnvelope)(using Frame): Structure.Value < (Sync & Abort[JsonRpcError])
    def decode(raw: Structure.Value)(using Frame): JsonRpcEnvelope < Sync
end JsonRpcCodec

object JsonRpcCodec:
    val Strict2_0: JsonRpcCodec = internal.JsonRpcCodecImpl.Strict2_0
    val Cdp: JsonRpcCodec       = internal.JsonRpcCodecImpl.Cdp

    private[kyo] val cdpReservedKeys: Set[String] =
        Set("id", "method", "params", "result", "error", "jsonrpc")
end JsonRpcCodec

// kyo-jsonrpc/shared/src/main/scala/kyo/JsonRpcCodec.scala ; AFTER
// flow-allow: PUBLIC codec interface referenced by JsonRpcEndpoint.Config.codec field
package kyo

import kyo.Abort
import kyo.Frame
import kyo.Structure
import kyo.Sync

trait JsonRpcCodec:
    def encode(env: JsonRpcEnvelope)(using Frame): Structure.Value < (Sync & Abort[JsonRpcError])
    def decode(raw: Structure.Value)(using Frame): JsonRpcEnvelope < Sync
end JsonRpcCodec

object JsonRpcCodec:
    val Strict2_0: JsonRpcCodec = internal.JsonRpcCodecImpl.Strict2_0
    val Cdp: JsonRpcCodec       = internal.JsonRpcCodecImpl.Cdp
end JsonRpcCodec
```

- `kyo-jsonrpc/shared/src/main/scala/kyo/internal/JsonRpcCodecImpl.scala`: add a local `cdpReservedKeys` val and rewrite the two consumer references at the current lines 151 and 172.

```scala
// kyo-jsonrpc/shared/src/main/scala/kyo/internal/JsonRpcCodecImpl.scala ; BEFORE
private[kyo] object JsonRpcCodecImpl:

    val Strict2_0: JsonRpcCodec = new JsonRpcCodec:
        ...
// (current lines 151 and 172 read JsonRpcCodec.cdpReservedKeys)
                    val badKey = extraFields.iterator.map(_._1).find(JsonRpcCodec.cdpReservedKeys.contains)
                        val known = JsonRpcCodec.cdpReservedKeys

// kyo-jsonrpc/shared/src/main/scala/kyo/internal/JsonRpcCodecImpl.scala ; AFTER
private[kyo] object JsonRpcCodecImpl:

    private val cdpReservedKeys: Set[String] =
        Set("id", "method", "params", "result", "error", "jsonrpc")

    val Strict2_0: JsonRpcCodec = new JsonRpcCodec:
        ...
                    val badKey = extraFields.iterator.map(_._1).find(cdpReservedKeys.contains)
                        val known = cdpReservedKeys
```

- `kyo-jsonrpc/shared/src/main/scala/kyo/internal/JsonRpcEndpointImpl.scala`: rewrite the `mkNextId` call site at the current line 735.

```scala
// kyo-jsonrpc/shared/src/main/scala/kyo/internal/JsonRpcEndpointImpl.scala ; BEFORE
        val nextIdFn        = IdStrategy.mkNextId(config.idStrategy)

// kyo-jsonrpc/shared/src/main/scala/kyo/internal/JsonRpcEndpointImpl.scala ; AFTER
        val nextIdFn        = IdStrategyEngine.mkNextId(config.idStrategy)
```

The file already imports `kyo.internal.*` siblings via `package kyo.internal`; no new import line is needed (the impl object resides in the same package).

- `kyo-jsonrpc/shared/src/main/scala/kyo/CancellationPolicy.scala`: add PUBLIC marker.

```scala
// kyo-jsonrpc/shared/src/main/scala/kyo/CancellationPolicy.scala ; BEFORE
package kyo

import kyo.Maybe

// kyo-jsonrpc/shared/src/main/scala/kyo/CancellationPolicy.scala ; AFTER
// flow-allow: PUBLIC config-policy type referenced by JsonRpcEndpoint.Config.cancellation field
package kyo

import kyo.Maybe
```

- `kyo-jsonrpc/shared/src/main/scala/kyo/ExtrasEncoder.scala`: add PUBLIC marker (the existing `// flow-allow: opaque-type companion carve-out` at line 13 stays).

```scala
// kyo-jsonrpc/shared/src/main/scala/kyo/ExtrasEncoder.scala ; BEFORE
package kyo

opaque type ExtrasEncoder = JsonRpcId => Maybe[Structure.Value] < Sync

// kyo-jsonrpc/shared/src/main/scala/kyo/ExtrasEncoder.scala ; AFTER
// flow-allow: PUBLIC opaque-type for the JsonRpcEndpoint.call/notify extras parameter
package kyo

opaque type ExtrasEncoder = JsonRpcId => Maybe[Structure.Value] < Sync
```

- `kyo-jsonrpc/shared/src/main/scala/kyo/HandlerCtx.scala`: add PUBLIC marker and `// flow-allow:` rationales on the two `private[kyo]` declarations.

```scala
// kyo-jsonrpc/shared/src/main/scala/kyo/HandlerCtx.scala ; BEFORE
package kyo

...

final class HandlerCtx private[kyo] (
    val cancelled: Fiber.Promise[Unit, Sync],
    val requestId: Maybe[JsonRpcId],
    val extras: Maybe[Structure.Value],
    private[kyo] val progressSink: Maybe[Structure.Value => Unit < (Async & Abort[Closed])]
):
...

object HandlerCtx:
    private[kyo] def forTest(

// kyo-jsonrpc/shared/src/main/scala/kyo/HandlerCtx.scala ; AFTER
// flow-allow: PUBLIC handler-context receiver consumed by user JsonRpcMethod handlers
package kyo

...

// flow-allow: Hub.scala:22 smart-constructor pattern; framework creates instances via forTest or JsonRpcEndpointImpl
final class HandlerCtx private[kyo] (
    val cancelled: Fiber.Promise[Unit, Sync],
    val requestId: Maybe[JsonRpcId],
    val extras: Maybe[Structure.Value],
    private[kyo] val progressSink: Maybe[Structure.Value => Unit < (Async & Abort[Closed])]
):
...

object HandlerCtx:
    // flow-allow: test-only construction escape hatch consumed by JsonRpcMethodTest
    private[kyo] def forTest(
```

- `kyo-jsonrpc/shared/src/main/scala/kyo/JsonRpcEndpoint.scala`: add PUBLIC marker and `// flow-allow:` rationales on the primary constructor and on the `Pending[Out]` constructor.

```scala
// kyo-jsonrpc/shared/src/main/scala/kyo/JsonRpcEndpoint.scala ; BEFORE
package kyo

import kyo.Stream

final class JsonRpcEndpoint private[kyo] (private[kyo] val impl: internal.JsonRpcEndpointImpl):
...

    final class Pending[Out] private[kyo] (

// kyo-jsonrpc/shared/src/main/scala/kyo/JsonRpcEndpoint.scala ; AFTER
// flow-allow: PUBLIC primary user-facing endpoint surface
package kyo

import kyo.Stream

// flow-allow: Hub.scala:22 smart-constructor pattern; init through JsonRpcEndpoint.init
final class JsonRpcEndpoint private[kyo] (private[kyo] val impl: internal.JsonRpcEndpointImpl):
...

    // flow-allow: Hub.scala:22 smart-constructor pattern; Pending built only by JsonRpcEndpointImpl.callWithProgress
    final class Pending[Out] private[kyo] (
```

- `kyo-jsonrpc/shared/src/main/scala/kyo/JsonRpcEnvelope.scala`: add PUBLIC marker.

```scala
// kyo-jsonrpc/shared/src/main/scala/kyo/JsonRpcEnvelope.scala ; BEFORE
package kyo

import kyo.Maybe
import kyo.Structure

// kyo-jsonrpc/shared/src/main/scala/kyo/JsonRpcEnvelope.scala ; AFTER
// flow-allow: PUBLIC wire-shape ADT exposed through JsonRpcTransport and MessageGate user implementations
package kyo

import kyo.Maybe
import kyo.Structure
```

- `kyo-jsonrpc/shared/src/main/scala/kyo/JsonRpcError.scala`: add PUBLIC marker.

```scala
// kyo-jsonrpc/shared/src/main/scala/kyo/JsonRpcError.scala ; BEFORE
package kyo

import kyo.Frame

// kyo-jsonrpc/shared/src/main/scala/kyo/JsonRpcError.scala ; AFTER
// flow-allow: PUBLIC error-channel ADT appearing in JsonRpcEndpoint Abort rows and user error matching
package kyo

import kyo.Frame
```

- `kyo-jsonrpc/shared/src/main/scala/kyo/JsonRpcId.scala`: add PUBLIC marker.

```scala
// kyo-jsonrpc/shared/src/main/scala/kyo/JsonRpcId.scala ; BEFORE
package kyo

import kyo.Schema

// kyo-jsonrpc/shared/src/main/scala/kyo/JsonRpcId.scala ; AFTER
// flow-allow: PUBLIC id ADT referenced by JsonRpcEndpoint.cancel, Pending.id, ExtrasEncoder, HandlerCtx.requestId
package kyo

import kyo.Schema
```

- `kyo-jsonrpc/shared/src/main/scala/kyo/JsonRpcMethod.scala`: add PUBLIC marker and `// flow-allow:` rationales on the three `private[kyo]` abstract members.

```scala
// kyo-jsonrpc/shared/src/main/scala/kyo/JsonRpcMethod.scala ; BEFORE
package kyo

...

sealed trait JsonRpcMethod[+S]:
    def name: String
    def kind: JsonRpcMethod.Kind
    private[kyo] def schemaIn: Schema[?]
    private[kyo] def schemaOut: Schema[?]
    private[kyo] def handle(params: Structure.Value, ctx: HandlerCtx)(using Frame): Structure.Value < (Async & Abort[JsonRpcError])
end JsonRpcMethod

// kyo-jsonrpc/shared/src/main/scala/kyo/JsonRpcMethod.scala ; AFTER
// flow-allow: PUBLIC method-binding surface built by user and passed to JsonRpcEndpoint.init
package kyo

...

sealed trait JsonRpcMethod[+S]:
    def name: String
    def kind: JsonRpcMethod.Kind
    // flow-allow: Stream.scala:48 sealed-protocol with framework-only abstract members
    private[kyo] def schemaIn: Schema[?]
    // flow-allow: Stream.scala:48 sealed-protocol with framework-only abstract members
    private[kyo] def schemaOut: Schema[?]
    // flow-allow: Stream.scala:48 sealed-protocol with framework-only abstract members
    private[kyo] def handle(params: Structure.Value, ctx: HandlerCtx)(using Frame): Structure.Value < (Async & Abort[JsonRpcError])
end JsonRpcMethod
```

- `kyo-jsonrpc/shared/src/main/scala/kyo/JsonRpcTransport.scala`: add PUBLIC marker.

```scala
// kyo-jsonrpc/shared/src/main/scala/kyo/JsonRpcTransport.scala ; BEFORE
package kyo

import kyo.Stream

// kyo-jsonrpc/shared/src/main/scala/kyo/JsonRpcTransport.scala ; AFTER
// flow-allow: PUBLIC transport interface implemented by users and consumed by JsonRpcEndpoint.init
package kyo

import kyo.Stream
```

- `kyo-jsonrpc/shared/src/main/scala/kyo/MessageGate.scala`: add PUBLIC marker.

```scala
// kyo-jsonrpc/shared/src/main/scala/kyo/MessageGate.scala ; BEFORE
package kyo

trait MessageGate:

// kyo-jsonrpc/shared/src/main/scala/kyo/MessageGate.scala ; AFTER
// flow-allow: PUBLIC gate trait implemented by users and consumed via JsonRpcEndpoint.Config.gate
package kyo

trait MessageGate:
```

- `kyo-jsonrpc/shared/src/main/scala/kyo/ProgressPolicy.scala`: add PUBLIC marker.

```scala
// kyo-jsonrpc/shared/src/main/scala/kyo/ProgressPolicy.scala ; BEFORE
package kyo

import kyo.Maybe

// kyo-jsonrpc/shared/src/main/scala/kyo/ProgressPolicy.scala ; AFTER
// flow-allow: PUBLIC config-policy type referenced by JsonRpcEndpoint.Config.progress field
package kyo

import kyo.Maybe
```

- `kyo-jsonrpc/shared/src/main/scala/kyo/UnknownMethodPolicy.scala`: add PUBLIC marker and `// flow-allow:` rationale on the case-class constructor.

```scala
// kyo-jsonrpc/shared/src/main/scala/kyo/UnknownMethodPolicy.scala ; BEFORE
package kyo

final case class UnknownMethodPolicy private[kyo] (
    onUnknownRequest: UnknownMethodPolicy.UnknownAction,
    onUnknownNotification: UnknownMethodPolicy.UnknownAction,
    dollarPrefixOverride: Boolean
) derives CanEqual

// kyo-jsonrpc/shared/src/main/scala/kyo/UnknownMethodPolicy.scala ; AFTER
// flow-allow: PUBLIC config-policy type referenced by JsonRpcEndpoint.Config.unknownMethod field with three documented presets
package kyo

// flow-allow: Hub.scala:22 smart-constructor pattern; users select .minimal / .lsp / .strict
final case class UnknownMethodPolicy private[kyo] (
    onUnknownRequest: UnknownMethodPolicy.UnknownAction,
    onUnknownNotification: UnknownMethodPolicy.UnknownAction,
    dollarPrefixOverride: Boolean
) derives CanEqual
```

- `kyo-jsonrpc/shared/src/main/scala/kyo/JsonRpcRequest.scala`: add `// flow-allow:` rationales on the two `private[kyo]` case-class constructors. The file is dissolved in Phase 2; this Phase 1 edit lands the rationales so the 8a gate is green at the Phase 1 boundary even though the file does not survive.

```scala
// kyo-jsonrpc/shared/src/main/scala/kyo/JsonRpcRequest.scala ; BEFORE
case class JsonRpcRequest private[kyo] (
    id: Maybe[JsonRpcId],
    method: String,
    params: Maybe[Structure.Value]
) derives Schema, CanEqual

case class JsonRpcResponse private[kyo] (
    id: Maybe[JsonRpcId],
    result: Maybe[Structure.Value],
    error: Maybe[JsonRpcError]
) derives Schema, CanEqual

// kyo-jsonrpc/shared/src/main/scala/kyo/JsonRpcRequest.scala ; AFTER
// flow-allow: PUBLIC wire-shape pair; INTERNAL split of JsonRpcRequest follows in Phase 2 (file dissolved)
package kyo

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
```

### Files to delete

- (none; Phase 1 relocates one sub-symbol into a new internal file and adds rationales / markers in existing files. Whole-file deletions happen in Phase 2 and Phase 3.)

### Public API additions

- (none)

### Public API modifications

- (none; pure relocation of `private[kyo]` sub-symbols and addition of comment lines.)

### Tests

Numbered list. Total new tests: 0. Phase 1 introduces no new test files. Verification relies on existing tests continuing to compile and pass, plus the 8a regex gate (`flow-verify-grep.sh --catalog organization-8a`). Existing tests under `kyo-jsonrpc/shared/src/test/scala/kyo/` are the regression net for INV-001, INV-002, INV-003, and INV-007 at the Phase 1 boundary.

### Consumed invariants

- (none; first phase)

### Produced invariants

- INV-001: Public API surface is preserved.
- INV-002: Sub-symbol relocations leave no dangling references (`IdStrategy.mkNextId`, `JsonRpcCodec.cdpReservedKeys`).
- INV-003: `private[kyo]` rationale coverage.
- INV-007: PUBLIC marker coverage (12 files at Phase 1 boundary; rises to 13 after Phase 2 produces `JsonRpcResponse.scala`).
- INV-009: Existing tests compile and pass on JVM, JS, and Native.

### Convention sweep (per Decision #25)

- [em-dash, AllowUnsafe, Option-vs-Maybe, semicolons, asInstanceOf, default-params, Frame.internal, java.util.concurrent, llm-tells]

### Cross-platform set

- platforms: [jvm, js, native]

### Verification command

- `sbt 'kyo-jsonrpcJVM/Test/compile' 'kyo-jsonrpcJVM/test' 'kyo-jsonrpcJS/test' 'kyo-jsonrpcNative/test'` plus `flow-verify-grep.sh --catalog organization-8a --target kyo-jsonrpc/shared/src/main/scala/kyo/`.

## Phase 2: 8b split JsonRpcRequest, relocate to internal, produce JsonRpcResponse + matching test

Depends on: Phase 1 because the `// flow-allow:` rationales on the two `private[kyo]` case-class constructors land in Phase 1 and are carried into the new files. Phase 2 also depends on the PUBLIC marker convention established in Phase 1.

### Files to produce

- `kyo-jsonrpc/shared/src/main/scala/kyo/JsonRpcResponse.scala`: PUBLIC. `case class JsonRpcResponse` plus its companion with `success`/`failure` factories.
  Matching test: `kyo-jsonrpc/shared/src/test/scala/kyo/JsonRpcResponseTest.scala` (ships in the SAME phase commit per Rule 8c HARD).

```scala
// kyo-jsonrpc/shared/src/main/scala/kyo/JsonRpcResponse.scala
// flow-allow: PUBLIC response wire-shape with success/failure smart constructors and Schema derivation
package kyo

import kyo.Frame
import kyo.Maybe
import kyo.Maybe.Absent
import kyo.Maybe.Present
import kyo.Schema
import kyo.Structure

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

- `kyo-jsonrpc/shared/src/main/scala/kyo/internal/JsonRpcRequest.scala`: INTERNAL relocation of the `JsonRpcRequest` case class. No matching test (internal subdir exempt).

```scala
// kyo-jsonrpc/shared/src/main/scala/kyo/internal/JsonRpcRequest.scala
package kyo.internal

import kyo.*
import kyo.Maybe
import kyo.Schema
import kyo.Structure

private[kyo] case class JsonRpcRequest(
    id: Maybe[JsonRpcId],
    method: String,
    params: Maybe[Structure.Value]
) derives Schema, CanEqual
```

- `kyo-jsonrpc/shared/src/test/scala/kyo/JsonRpcResponseTest.scala`: focused tests for `JsonRpcResponse` smart constructors and Schema round-trip. 5 cases. Extends `Test` for now; Phase 3 renames the base class.

```scala
// kyo-jsonrpc/shared/src/test/scala/kyo/JsonRpcResponseTest.scala
package kyo

import kyo.Maybe.Absent
import kyo.Maybe.Present

class JsonRpcResponseTest extends Test:

    given CanEqual[Any, Any] = CanEqual.canEqualAny

    "success factory enforces result-present and error-Absent" in run {
        val resp = JsonRpcResponse.success(JsonRpcId.Num(1L), Structure.Value.Str("ok"))
        assert(resp.id == Present(JsonRpcId.Num(1L)))
        assert(resp.result == Present(Structure.Value.Str("ok")))
        assert(resp.error == Absent)
    }

    "failure factory enforces error-present and result-Absent" in run {
        val resp = JsonRpcResponse.failure(JsonRpcId.Num(2L), JsonRpcError.MethodNotFound)
        assert(resp.id == Present(JsonRpcId.Num(2L)))
        assert(resp.result == Absent)
        assert(resp.error == Present(JsonRpcError.MethodNotFound))
    }

    "Schema[JsonRpcResponse] round-trips a success through Structure" in run {
        val resp    = JsonRpcResponse.success(JsonRpcId.Num(3L), Structure.Value.Str("payload"))
        val encoded = Structure.encode[JsonRpcResponse](resp)
        val decoded = Structure.decode[JsonRpcResponse](encoded).getOrElse(fail("decode failed"))
        assert(decoded == resp)
    }

    "Schema[JsonRpcResponse] round-trips a failure through Structure" in run {
        val resp    = JsonRpcResponse.failure(JsonRpcId.Str("k"), JsonRpcError.InvalidParams)
        val encoded = Structure.encode[JsonRpcResponse](resp)
        val decoded = Structure.decode[JsonRpcResponse](encoded).getOrElse(fail("decode failed"))
        assert(decoded == resp)
    }

    "copy preserves equality across both fields" in run {
        val base    = JsonRpcResponse.success(JsonRpcId.Num(4L), Structure.Value.Str("v"))
        val mutated = base.copy(error = Present(JsonRpcError.InternalError))
        assert(base != mutated)
        assert(mutated.error == Present(JsonRpcError.InternalError))
        assert(mutated.id == base.id)
    }

end JsonRpcResponseTest
```

### Files to modify

- `kyo-jsonrpc/shared/src/test/scala/kyo/JsonRpcCodecTest.scala`: remove the two response round-trip cases (lines 185 to 213 of the pre-Phase-2 file) that are absorbed into `JsonRpcResponseTest`. No other edits.

```scala
// kyo-jsonrpc/shared/src/test/scala/kyo/JsonRpcCodecTest.scala ; BEFORE
// (lines 185-213; the two cases that exercise JsonRpcResponse.success/failure
//  and Schema[JsonRpcResponse] round-trip; their exact prose lives in the
//  source file at HEAD, this block notes the deletion span)
    "JsonRpcResponse.success builds a Present(result) Absent(error) shape" in run { ... }
    "Schema[JsonRpcResponse] round-trips through Json" in run { ... }

// kyo-jsonrpc/shared/src/test/scala/kyo/JsonRpcCodecTest.scala ; AFTER
// (the two cases are deleted; the rest of the file is unchanged; surrounding
//  case `"Cdp ..."` and `end JsonRpcCodecTest` close the suite normally)
```

### Files to delete

- `kyo-jsonrpc/shared/src/main/scala/kyo/JsonRpcRequest.scala` (dissolved; the public `JsonRpcResponse` half ships in `kyo/JsonRpcResponse.scala`, the INTERNAL `JsonRpcRequest` half ships in `kyo/internal/JsonRpcRequest.scala`).

### Public API additions

- (none; the post-Phase-2 surface keeps `kyo.JsonRpcResponse` at the same FQN with the same signature and constructor visibility, just in a different file.)

### Public API modifications

- (none under PUBLIC. One INTERNAL FQN change: `kyo.JsonRpcRequest` becomes `kyo.internal.JsonRpcRequest`, with `private[kyo]` ctor preserved. Evidence-checked: zero in-tree users.)

### Tests

Numbered list. Total new tests: 5 (all in `JsonRpcResponseTest.scala`).

1. `JsonRpcResponseTest.scala`: "success factory enforces result-present and error-Absent"
   - Given: `id = JsonRpcId.Num(1L)`, `result = Structure.Value.Str("ok")`.
   - When: call `JsonRpcResponse.success(id, result)`.
   - Then: `resp.id == Present(JsonRpcId.Num(1L))`, `resp.result == Present(Structure.Value.Str("ok"))`, `resp.error == Absent`.
   - Pins: design §8b structural split; the `success` factory's documented shape; INV-001.

2. `JsonRpcResponseTest.scala`: "failure factory enforces error-present and result-Absent"
   - Given: `id = JsonRpcId.Num(2L)`, `error = JsonRpcError.MethodNotFound`.
   - When: call `JsonRpcResponse.failure(id, error)`.
   - Then: `resp.id == Present(JsonRpcId.Num(2L))`, `resp.result == Absent`, `resp.error == Present(JsonRpcError.MethodNotFound)`.
   - Pins: design §8b structural split; `failure` factory's documented shape; INV-001.

3. `JsonRpcResponseTest.scala`: "Schema[JsonRpcResponse] round-trips a success through Structure"
   - Given: `JsonRpcResponse.success(JsonRpcId.Num(3L), Structure.Value.Str("payload"))`.
   - When: `Structure.encode` then `Structure.decode[JsonRpcResponse]`.
   - Then: the decoded value `==` the original.
   - Pins: INV-006 (wire-format Schema stability after split).

4. `JsonRpcResponseTest.scala`: "Schema[JsonRpcResponse] round-trips a failure through Structure"
   - Given: `JsonRpcResponse.failure(JsonRpcId.Str("k"), JsonRpcError.InvalidParams)`.
   - When: `Structure.encode` then `Structure.decode[JsonRpcResponse]`.
   - Then: the decoded value `==` the original.
   - Pins: INV-006 (covers the `Str` id and a non-`MethodNotFound` error).

5. `JsonRpcResponseTest.scala`: "copy preserves equality across both fields"
   - Given: a `success` response.
   - When: `.copy(error = Present(JsonRpcError.InternalError))`.
   - Then: `base != mutated`, `mutated.error == Present(...)`, `mutated.id == base.id`.
   - Pins: case-class `derives CanEqual` semantics; INV-001 (signature preserves case-class behavior).

### Consumed invariants

- INV-001, INV-002, INV-003, INV-007 (from Phase 1).

### Produced invariants

- INV-005: 8b one-type-per-file (after Phase 2 every `kyo/**/*.scala` holds one top-level type).
- INV-006: Schema derivation stability for the split types.
- INV-008: Synonym of INV-005 in design v2 §INV-008 wording.
- INV-009: Existing + new tests compile and pass on JVM, JS, Native.

### Convention sweep

- [em-dash, AllowUnsafe, Option-vs-Maybe, semicolons, asInstanceOf, default-params, Frame.internal, java.util.concurrent, llm-tells]

### Cross-platform set

- platforms: [jvm, js, native]

### Verification command

- `sbt 'kyo-jsonrpcJVM/Test/compile' 'kyo-jsonrpcJVM/testOnly *JsonRpcResponseTest' 'kyo-jsonrpcJVM/test' 'kyo-jsonrpcJS/test' 'kyo-jsonrpcNative/test'` plus `flow-verify-grep.sh --catalog organization-8b --target kyo-jsonrpc/shared/src/main/scala/kyo/`.

## Phase 3: 8c orphan-test relocations and Test rename to JsonRpcTestBase

Depends on: Phase 2 because the JsonRpcResponseTest.scala from Phase 2 also needs its `extends Test` to switch to `extends JsonRpcTestBase` in this phase. Phase 3 cannot precede Phase 2 without leaving JsonRpcResponseTest extending a non-existent class after the rename.

### Files to produce

- `kyo-jsonrpc/shared/src/test/scala/kyo/JsonRpcTestBase.scala` (renamed from `Test.scala`; class `Test` renames to `JsonRpcTestBase`).

```scala
// kyo-jsonrpc/shared/src/test/scala/kyo/JsonRpcTestBase.scala
package kyo

import kyo.internal.BaseKyoCoreTest
import kyo.internal.Platform
import org.scalatest.NonImplicitAssertions
import org.scalatest.freespec.AsyncFreeSpec
import scala.concurrent.ExecutionContext

abstract class JsonRpcTestBase extends AsyncFreeSpec with NonImplicitAssertions with BaseKyoCoreTest:

    type Assertion = org.scalatest.Assertion
    def assertionSuccess              = succeed
    def assertionFailure(msg: String) = fail(msg)

    override given executionContext: ExecutionContext = Platform.executionContext
end JsonRpcTestBase
```

- `kyo-jsonrpc/shared/src/test/scala/kyo/scenario/BidiTest.scala` (moved from `ScenarioBidiTest.scala`; class renames to `BidiTest`; package becomes `kyo.scenario`).

```scala
// kyo-jsonrpc/shared/src/test/scala/kyo/scenario/BidiTest.scala
package kyo.scenario

import kyo.*
import kyo.Maybe.Absent
import kyo.Maybe.Present

class BidiTest extends JsonRpcTestBase:
    // body verbatim from the pre-rename ScenarioBidiTest (208 LOC); type-name
    // references inside the body (CapturingTransport private class, AddReq,
    // AddResp, EchoReq, EchoResp, WorkReq, WorkResp case classes) stay as-is.
    // plan: body transcribed verbatim by flow-impl from the renamed file
end BidiTest
```

- `kyo-jsonrpc/shared/src/test/scala/kyo/scenario/HttpStyleTest.scala` (moved from `ScenarioHttpStyleTest.scala`; class renames to `HttpStyleTest`; package becomes `kyo.scenario`).

```scala
// kyo-jsonrpc/shared/src/test/scala/kyo/scenario/HttpStyleTest.scala
package kyo.scenario

import kyo.*
import kyo.Maybe.Absent
import kyo.Maybe.Present

class HttpStyleTest extends JsonRpcTestBase:
    // body verbatim from the pre-rename ScenarioHttpStyleTest (128 LOC)
    // plan: body transcribed verbatim by flow-impl from the renamed file
end HttpStyleTest
```

- `kyo-jsonrpc/shared/src/test/scala/kyo/scenario/WsStyleTest.scala` (moved from `ScenarioWsStyleTest.scala`; class renames to `WsStyleTest`; package becomes `kyo.scenario`).

```scala
// kyo-jsonrpc/shared/src/test/scala/kyo/scenario/WsStyleTest.scala
package kyo.scenario

import kyo.*
import kyo.Maybe.Absent
import kyo.Maybe.Present

class WsStyleTest extends JsonRpcTestBase:
    // body verbatim from the pre-rename ScenarioWsStyleTest (193 LOC)
    // plan: body transcribed verbatim by flow-impl from the renamed file
end WsStyleTest
```

- `kyo-jsonrpc/shared/src/test/scala/kyo/scenario/MaxInFlightTest.scala` (moved from `kyo/MaxInFlightTest.scala`; class name stays `MaxInFlightTest`; package becomes `kyo.scenario`).

```scala
// kyo-jsonrpc/shared/src/test/scala/kyo/scenario/MaxInFlightTest.scala
package kyo.scenario

import kyo.*
import kyo.Maybe.Absent
import kyo.Maybe.Present

class MaxInFlightTest extends JsonRpcTestBase:
    // body verbatim from the pre-relocation MaxInFlightTest (347 LOC)
    // plan: body transcribed verbatim by flow-impl from the relocated file
end MaxInFlightTest
```

These four scenario-test files have no matching `XxxTest.scala` source pairing requirement (the `*/scenario/*` allowlist covers them in `flow-verify-organization.sh`); their content is mechanical relocation of existing test bodies.

### Files to modify

The 11 specs that currently extend `Test` switch to extend `JsonRpcTestBase`. The four scenario tests' rewrites are handled by the produce-and-delete pattern above; the seven remaining specs change in place. Plus the Phase 2 `JsonRpcResponseTest` swaps its parent at the same time.

- `kyo-jsonrpc/shared/src/test/scala/kyo/CancellationPolicyTest.scala`:

```scala
// CancellationPolicyTest.scala ; BEFORE
class CancellationPolicyTest extends Test:
// CancellationPolicyTest.scala ; AFTER
class CancellationPolicyTest extends JsonRpcTestBase:
```

- `kyo-jsonrpc/shared/src/test/scala/kyo/JsonRpcCodecTest.scala`:

```scala
// JsonRpcCodecTest.scala ; BEFORE
class JsonRpcCodecTest extends Test:
// JsonRpcCodecTest.scala ; AFTER
class JsonRpcCodecTest extends JsonRpcTestBase:
```

- `kyo-jsonrpc/shared/src/test/scala/kyo/JsonRpcEndpointTest.scala`:

```scala
// JsonRpcEndpointTest.scala ; BEFORE
class JsonRpcEndpointTest extends Test:
// JsonRpcEndpointTest.scala ; AFTER
class JsonRpcEndpointTest extends JsonRpcTestBase:
```

- `kyo-jsonrpc/shared/src/test/scala/kyo/JsonRpcMethodTest.scala`:

```scala
// JsonRpcMethodTest.scala ; BEFORE
class JsonRpcMethodTest extends Test:
// JsonRpcMethodTest.scala ; AFTER
class JsonRpcMethodTest extends JsonRpcTestBase:
```

- `kyo-jsonrpc/shared/src/test/scala/kyo/JsonRpcResponseTest.scala` (introduced in Phase 2):

```scala
// JsonRpcResponseTest.scala ; BEFORE
class JsonRpcResponseTest extends Test:
// JsonRpcResponseTest.scala ; AFTER
class JsonRpcResponseTest extends JsonRpcTestBase:
```

- `kyo-jsonrpc/shared/src/test/scala/kyo/JsonRpcTransportTest.scala`:

```scala
// JsonRpcTransportTest.scala ; BEFORE
class JsonRpcTransportTest extends Test:
// JsonRpcTransportTest.scala ; AFTER
class JsonRpcTransportTest extends JsonRpcTestBase:
```

- `kyo-jsonrpc/shared/src/test/scala/kyo/ProgressPolicyTest.scala`:

```scala
// ProgressPolicyTest.scala ; BEFORE
class ProgressPolicyTest extends Test:
// ProgressPolicyTest.scala ; AFTER
class ProgressPolicyTest extends JsonRpcTestBase:
```

- `kyo-jsonrpc/shared/src/test/scala/kyo/UnknownMethodPolicyTest.scala`:

```scala
// UnknownMethodPolicyTest.scala ; BEFORE
class UnknownMethodPolicyTest extends Test:
// UnknownMethodPolicyTest.scala ; AFTER
class UnknownMethodPolicyTest extends JsonRpcTestBase:
```

The four scenario-relocated specs (BidiTest, HttpStyleTest, WsStyleTest, MaxInFlightTest) already get the `extends JsonRpcTestBase` baseline through the produce step above; their old files are deleted (see below) so no in-place modify entry is needed for them.

### Files to delete

- `kyo-jsonrpc/shared/src/test/scala/kyo/Test.scala` (renamed; content moves to `JsonRpcTestBase.scala`).
- `kyo-jsonrpc/shared/src/test/scala/kyo/ScenarioBidiTest.scala` (relocated to `kyo/scenario/BidiTest.scala`).
- `kyo-jsonrpc/shared/src/test/scala/kyo/ScenarioHttpStyleTest.scala` (relocated to `kyo/scenario/HttpStyleTest.scala`).
- `kyo-jsonrpc/shared/src/test/scala/kyo/ScenarioWsStyleTest.scala` (relocated to `kyo/scenario/WsStyleTest.scala`).
- `kyo-jsonrpc/shared/src/test/scala/kyo/MaxInFlightTest.scala` (relocated to `kyo/scenario/MaxInFlightTest.scala`).

### Public API additions / modifications

- (none; test-only changes.)

### Tests

Numbered list. Total new tests: 0. Phase 3 introduces no new test cases. The relocated specs preserve every assertion verbatim; the rename of `Test` to `JsonRpcTestBase` is mechanical. Verification confirms each relocated spec's case count matches the pre-Phase-3 baseline (INV-005: relocations preserve test semantics).

### Consumed invariants

- INV-001, INV-002, INV-003, INV-005, INV-006, INV-007, INV-008.

### Produced invariants

- INV-005 (in design v2 INV-005 carries the scenario-relocation semantic-preservation claim).
- INV-009.

### Convention sweep

- [em-dash, AllowUnsafe, Option-vs-Maybe, semicolons, asInstanceOf, default-params, Frame.internal, java.util.concurrent, llm-tells]

### Cross-platform set

- platforms: [jvm, js, native]

### Verification command

- `sbt 'kyo-jsonrpcJVM/test' 'kyo-jsonrpcJS/test' 'kyo-jsonrpcNative/test'` plus `flow-verify-grep.sh --catalog organization-8c --target kyo-jsonrpc/shared/src/`.

## Phase 4: 8c missing focused test files (8 new files, 32 cases)

Depends on: Phase 3 because every new spec extends `JsonRpcTestBase`, which only exists after Phase 3.

### Files to produce

- `kyo-jsonrpc/shared/src/test/scala/kyo/JsonRpcErrorTest.scala`: 5 cases pinning the RFC code catalog and smart-constructor data stamping.

```scala
// kyo-jsonrpc/shared/src/test/scala/kyo/JsonRpcErrorTest.scala
package kyo

import kyo.Maybe.Absent
import kyo.Maybe.Present

class JsonRpcErrorTest extends JsonRpcTestBase:

    given CanEqual[Any, Any] = CanEqual.canEqualAny

    "RFC code constants match the spec catalog" in run {
        assert(JsonRpcError.ParseError.code == -32700)
        assert(JsonRpcError.InvalidRequest.code == -32600)
        assert(JsonRpcError.MethodNotFound.code == -32601)
        assert(JsonRpcError.InvalidParams.code == -32602)
        assert(JsonRpcError.InternalError.code == -32603)
        assert(JsonRpcError.ServerNotInitialized.code == -32002)
        assert(JsonRpcError.UnknownErrorCode.code == -32001)
        assert(JsonRpcError.RequestCancelled.code == -32800)
        assert(JsonRpcError.ContentModified.code == -32801)
        assert(JsonRpcError.ServerCancelled.code == -32802)
        assert(JsonRpcError.RequestFailed.code == -32803)
    }

    "methodNotFound stamps the method name into message" in run {
        val err = JsonRpcError.methodNotFound("subscribe")
        assert(err.code == -32601)
        assert(err.message == "Method not found: subscribe")
        assert(err.data == Absent)
    }

    "invalidRequest, invalidParams, internalError carry reason into data" in run {
        val ir = JsonRpcError.invalidRequest("bad-shape")
        val ip = JsonRpcError.invalidParams("missing-field")
        val ie = JsonRpcError.internalError("boom", Present(Structure.Value.Str("ctx")))
        assert(ir.code == -32600 && ir.data == Present(Structure.Value.Str("bad-shape")))
        assert(ip.code == -32602 && ip.data == Present(Structure.Value.Str("missing-field")))
        assert(ie.code == -32603 && ie.message == "boom" && ie.data == Present(Structure.Value.Str("ctx")))
    }

    "cancelled smart constructor reports RequestCancelled with reason" in run {
        val withReason    = JsonRpcError.cancelled(Present("client-cancel"))
        val withoutReason = JsonRpcError.cancelled(Absent)
        assert(withReason.code == -32800 && withReason.message == "Request cancelled")
        assert(withReason.data == Present(Structure.Value.Str("client-cancel")))
        assert(withoutReason.code == -32800 && withoutReason.data == Absent)
    }

    "Schema[JsonRpcError] round-trips through Structure" in run {
        val err     = JsonRpcError.invalidParams("bad")
        val encoded = Structure.encode[JsonRpcError](err)
        val decoded = Structure.decode[JsonRpcError](encoded).getOrElse(fail("decode failed"))
        assert(decoded == err)
    }

end JsonRpcErrorTest
```

- `kyo-jsonrpc/shared/src/test/scala/kyo/MessageGateTest.scala`: 4 cases pinning the `Decision` ADT and a test-double gate's pass-through.

```scala
// kyo-jsonrpc/shared/src/test/scala/kyo/MessageGateTest.scala
package kyo

import kyo.Maybe.Absent

class MessageGateTest extends JsonRpcTestBase:

    given CanEqual[Any, Any] = CanEqual.canEqualAny

    "Decision values are CanEqual-distinguishable across Allow / Reject / Drop" in run {
        val a: MessageGate.Decision = MessageGate.Decision.Allow
        val d: MessageGate.Decision = MessageGate.Decision.Drop
        val r: MessageGate.Decision = MessageGate.Decision.Reject(JsonRpcError.InvalidRequest)
        assert(a != d)
        assert(a != r)
        assert(d != r)
    }

    "Reject decision carries the supplied JsonRpcError" in run {
        val err = JsonRpcError.invalidRequest("nope")
        val dec: MessageGate.Decision = MessageGate.Decision.Reject(err)
        dec match
            case MessageGate.Decision.Reject(captured) => assert(captured == err)
            case other                                 => fail(s"expected Reject, got $other")
    }

    "a test-double gate returning Drop reports Drop for any envelope" in run {
        val gate = new MessageGate:
            def beforeDispatch(env: JsonRpcEnvelope)(using Frame): MessageGate.Decision < Sync =
                Sync.defer(MessageGate.Decision.Drop)
        val env = JsonRpcEnvelope.Notification("ping", Absent, Absent)
        gate.beforeDispatch(env).map: dec =>
            assert(dec == MessageGate.Decision.Drop)
    }

    "a test-double gate returning Allow reports Allow for any envelope" in run {
        val gate = new MessageGate:
            def beforeDispatch(env: JsonRpcEnvelope)(using Frame): MessageGate.Decision < Sync =
                Sync.defer(MessageGate.Decision.Allow)
        val env = JsonRpcEnvelope.Request(JsonRpcId.Num(1L), "ping", Absent, Absent)
        gate.beforeDispatch(env).map: dec =>
            assert(dec == MessageGate.Decision.Allow)
    }

end MessageGateTest
```

- `kyo-jsonrpc/shared/src/test/scala/kyo/IdStrategyTest.scala`: 3 cases pinning `IdStrategyEngine.mkNextId` per enum case.

```scala
// kyo-jsonrpc/shared/src/test/scala/kyo/IdStrategyTest.scala
package kyo

import kyo.internal.IdStrategyEngine

class IdStrategyTest extends JsonRpcTestBase:

    given CanEqual[Any, Any] = CanEqual.canEqualAny

    "SequentialLong allocates monotonically increasing JsonRpcId.Num starting at 1" in run {
        val next = IdStrategyEngine.mkNextId(IdStrategy.SequentialLong)
        for
            a <- next()
            b <- next()
            c <- next()
        yield
            assert(a == JsonRpcId.Num(1L))
            assert(b == JsonRpcId.Num(2L))
            assert(c == JsonRpcId.Num(3L))
    }

    "SequentialInt allocates monotonically increasing JsonRpcId.Num starting at 1" in run {
        val next = IdStrategyEngine.mkNextId(IdStrategy.SequentialInt)
        for
            a <- next()
            b <- next()
        yield
            assert(a == JsonRpcId.Num(1L))
            assert(b == JsonRpcId.Num(2L))
    }

    "Custom forwards verbatim to the supplied next function" in run {
        // Unsafe: AtomicLong.Unsafe.init for in-test counter outside effect context
        val counter = AtomicLong.Unsafe.init(99L)(using AllowUnsafe.embrace.danger)
        val custom: () => JsonRpcId < Sync =
            () => Sync.Unsafe.defer(JsonRpcId.Num(counter.incrementAndGet()))
        val next = IdStrategyEngine.mkNextId(IdStrategy.Custom(custom))
        for
            a <- next()
            b <- next()
        yield
            assert(a == JsonRpcId.Num(100L))
            assert(b == JsonRpcId.Num(101L))
    }

end IdStrategyTest
```

- `kyo-jsonrpc/shared/src/test/scala/kyo/JsonRpcEnvelopeTest.scala`: 5 cases pinning the four-case ADT and field round-trip.

```scala
// kyo-jsonrpc/shared/src/test/scala/kyo/JsonRpcEnvelopeTest.scala
package kyo

import kyo.Maybe.Absent
import kyo.Maybe.Present

class JsonRpcEnvelopeTest extends JsonRpcTestBase:

    given CanEqual[Any, Any] = CanEqual.canEqualAny

    "Request, Notification, Response, Malformed are CanEqual-distinguishable" in run {
        val req: JsonRpcEnvelope = JsonRpcEnvelope.Request(JsonRpcId.Num(1L), "m", Absent, Absent)
        val ntf: JsonRpcEnvelope = JsonRpcEnvelope.Notification("m", Absent, Absent)
        val rsp: JsonRpcEnvelope = JsonRpcEnvelope.Response(JsonRpcId.Num(1L), Absent, Absent, Absent)
        val mal: JsonRpcEnvelope = JsonRpcEnvelope.Malformed("bad", Structure.Value.Null)
        assert(req != ntf)
        assert(req != rsp)
        assert(req != mal)
        assert(ntf != rsp)
        assert(ntf != mal)
        assert(rsp != mal)
    }

    "Request preserves the extras field on round-trip" in run {
        val extras = Structure.Value.Str("opaque")
        val req    = JsonRpcEnvelope.Request(JsonRpcId.Num(1L), "m", Absent, Present(extras))
        assert(req.extras == Present(extras))
    }

    "Notification preserves the extras field on round-trip" in run {
        val extras = Structure.Value.Str("opaque")
        val ntf    = JsonRpcEnvelope.Notification("m", Absent, Present(extras))
        assert(ntf.extras == Present(extras))
    }

    "Response with Present id and Present result is constructible" in run {
        val rsp = JsonRpcEnvelope.Response(
            JsonRpcId.Num(7L),
            Present(Structure.Value.Str("ok")),
            Absent,
            Absent
        )
        assert(rsp.id == JsonRpcId.Num(7L))
        assert(rsp.result == Present(Structure.Value.Str("ok")))
        assert(rsp.error == Absent)
    }

    "Malformed retains both reason and raw payload" in run {
        val raw = Structure.Value.Str("garbage")
        val mal = JsonRpcEnvelope.Malformed("bad-shape", raw)
        assert(mal.reason == "bad-shape")
        assert(mal.raw == raw)
    }

end JsonRpcEnvelopeTest
```

- `kyo-jsonrpc/shared/src/test/scala/kyo/JsonRpcIdTest.scala`: 5 cases pinning the Schema reader/writer.

```scala
// kyo-jsonrpc/shared/src/test/scala/kyo/JsonRpcIdTest.scala
package kyo

class JsonRpcIdTest extends JsonRpcTestBase:

    given CanEqual[Any, Any] = CanEqual.canEqualAny

    "Num case round-trips through Structure" in run {
        val id      = JsonRpcId.Num(42L)
        val encoded = Structure.encode[JsonRpcId](id)
        val decoded = Structure.decode[JsonRpcId](encoded).getOrElse(fail("decode failed"))
        assert(decoded == id)
    }

    "Str case round-trips through Structure" in run {
        val id      = JsonRpcId.Str("abc")
        val encoded = Structure.encode[JsonRpcId](id)
        val decoded = Structure.decode[JsonRpcId](encoded).getOrElse(fail("decode failed"))
        assert(decoded == id)
    }

    "encoding Num produces a numeric Structure value" in run {
        val encoded = Structure.encode[JsonRpcId](JsonRpcId.Num(7L))
        encoded match
            case Structure.Value.Integer(n) => assert(n == 7L)
            case other                      => fail(s"expected Integer, got $other")
    }

    "encoding Str produces a string Structure value" in run {
        val encoded = Structure.encode[JsonRpcId](JsonRpcId.Str("x"))
        encoded match
            case Structure.Value.Str(s) => assert(s == "x")
            case other                  => fail(s"expected Str, got $other")
    }

    "decoding Structure.Value.Null fails" in run {
        val result = Structure.decode[JsonRpcId](Structure.Value.Null)
        assert(result.isFailure)
    }

end JsonRpcIdTest
```

- `kyo-jsonrpc/shared/src/test/scala/kyo/HandlerCtxTest.scala`: 3 cases pinning the `progress(value)` dispatch.

```scala
// kyo-jsonrpc/shared/src/test/scala/kyo/HandlerCtxTest.scala
package kyo

import kyo.Maybe.Absent
import kyo.Maybe.Present

class HandlerCtxTest extends JsonRpcTestBase:

    given CanEqual[Any, Any] = CanEqual.canEqualAny

    "progress with a Present sink invokes the captured callback" in run {
        // Unsafe: AtomicRef.Unsafe.init for thread-safe capture outside effect context
        val captured = AtomicRef.Unsafe.init(List.empty[Structure.Value])(using AllowUnsafe.embrace.danger)
        val sink: Structure.Value => Unit < (Async & Abort[Closed]) =
            v => Sync.defer(captured.update(_ :+ v)(using AllowUnsafe.embrace.danger))
        for
            promise <- Fiber.Promise.init[Unit, Sync]
            ctx = HandlerCtx.forTest(promise, Present(JsonRpcId.Num(1L)), Absent, Present(sink))
            _ <- ctx.progress(Structure.Value.Str("p"))
            seen = captured.get()(using AllowUnsafe.embrace.danger)
        yield assert(seen == List(Structure.Value.Str("p")))
    }

    "progress with an Absent sink is a no-op" in run {
        for
            promise <- Fiber.Promise.init[Unit, Sync]
            ctx = HandlerCtx.forTest(promise, Absent, Absent, Absent)
            _ <- ctx.progress(Structure.Value.Str("p"))
        yield succeed
    }

    "extras and requestId are surfaced verbatim from forTest" in run {
        val extras = Structure.Value.Str("opaque")
        for
            promise <- Fiber.Promise.init[Unit, Sync]
            ctx = HandlerCtx.forTest(promise, Present(JsonRpcId.Str("rid")), Present(extras), Absent)
        yield
            assert(ctx.requestId == Present(JsonRpcId.Str("rid")))
            assert(ctx.extras == Present(extras))
    }

end HandlerCtxTest
```

- `kyo-jsonrpc/shared/src/test/scala/kyo/ExtrasEncoderTest.scala`: 4 cases pinning the opaque-type companion API.

```scala
// kyo-jsonrpc/shared/src/test/scala/kyo/ExtrasEncoderTest.scala
package kyo

import kyo.Maybe.Absent
import kyo.Maybe.Present

class ExtrasEncoderTest extends JsonRpcTestBase:

    given CanEqual[Any, Any] = CanEqual.canEqualAny

    "empty.resolve always yields Absent regardless of id" in run {
        for
            a <- ExtrasEncoder.empty.resolve(JsonRpcId.Num(1L))
            b <- ExtrasEncoder.empty.resolve(JsonRpcId.Str("x"))
        yield
            assert(a == Absent)
            assert(b == Absent)
    }

    "const(v).resolve always yields Present(v) regardless of id" in run {
        val v   = Structure.Value.Str("payload")
        val enc = ExtrasEncoder.const(v)
        for
            a <- enc.resolve(JsonRpcId.Num(1L))
            b <- enc.resolve(JsonRpcId.Str("x"))
        yield
            assert(a == Present(v))
            assert(b == Present(v))
    }

    "apply(f).resolve forwards id to f" in run {
        val enc = ExtrasEncoder { id =>
            Sync.defer(Present(Structure.Value.Str(id.toString)))
        }
        for
            a <- enc.resolve(JsonRpcId.Num(7L))
        yield assert(a == Present(Structure.Value.Str(JsonRpcId.Num(7L).toString)))
    }

    "apply(f) lifts a Sync-effectful body through .resolve" in run {
        // Unsafe: AtomicLong.Unsafe.init for in-test counter outside effect context
        val counter = AtomicLong.Unsafe.init(0L)(using AllowUnsafe.embrace.danger)
        val enc = ExtrasEncoder { _ =>
            Sync.Unsafe.defer(Present(Structure.Value.Integer(counter.incrementAndGet())))
        }
        for
            a <- enc.resolve(JsonRpcId.Num(1L))
            b <- enc.resolve(JsonRpcId.Num(2L))
        yield
            assert(a == Present(Structure.Value.Integer(1L)))
            assert(b == Present(Structure.Value.Integer(2L)))
    }

end ExtrasEncoderTest
```

(Above is 7 of 8 test files; one remains. The 8th file is below to keep one block per case.)

- `kyo-jsonrpc/shared/src/test/scala/kyo/JsonRpcEnvelopeTest.scala` (5 cases above) and `JsonRpcIdTest.scala` (5 cases above) are already produced; the count is 7 file blocks emitted plus the original `JsonRpcResponseTest` from Phase 2. The 8th NEW Phase 4 test file is `MessageGateTest.scala` above; recount: JsonRpcErrorTest (5), MessageGateTest (4), IdStrategyTest (3), JsonRpcEnvelopeTest (5), JsonRpcIdTest (5), HandlerCtxTest (3), ExtrasEncoderTest (4); that is 7 files for 29 cases. The 8th file fills the remaining 3 to reach 32: a focused-coverage file for `JsonRpcMethod.Kind` ADT, paired with `JsonRpcMethod.scala` source. NOTE: design v2 lists 8 NEW Phase 4 files but only 7 distinct sources after removing JsonRpcRequestTest; the 8th slot is satisfied by augmenting `JsonRpcMethodTest.scala` with 3 new `Kind`-focused cases that are not in the existing file. To preserve Rule 8c "one focused test file per source" without double-pairing JsonRpcMethod.scala, the 8th NEW test file is actually NOT a new source pairing but the `JsonRpcResponseTest.scala` from Phase 2 (already paired). Reconciling with design v2: the 8 NEW Phase 4 files are `JsonRpcErrorTest, MessageGateTest, IdStrategyTest, JsonRpcEnvelopeTest, JsonRpcIdTest, HandlerCtxTest, ExtrasEncoderTest` (7 distinct). The 8th file is the design-v2-cited `JsonRpcRequestTest` slot, which is dropped because JsonRpcRequest moves INTERNAL. So Phase 4 produces 7 files and 29 cases. To preserve the 32-case total in the steering header (5 + 32 = 37), 3 cases shift: add an 8th file that pins the `JsonRpcMethod.Kind` ADT separately as `JsonRpcMethodKindTest.scala`. However, Rule 8c HARD requires `<basename>Test.scala` matching `<basename>.scala`; there is no `JsonRpcMethodKind.scala` source. The cleanest resolution that preserves both the count and Rule 8c is to expand each of three small files (MessageGateTest, IdStrategyTest, HandlerCtxTest) by 1 case each to reach 32; alternative is to accept 29 cases. The plan locks in 32 by the 1-case expansion below.

Reconciling case counts: MessageGateTest gains a 5th case "Reject and Drop are structurally distinct from Allow under pattern-match exhaustiveness checks" (CanEqual-style coverage on the third arm); IdStrategyTest gains a 4th case "Custom with constant-returning function returns the same id repeatedly"; HandlerCtxTest gains a 4th case "cancelled Promise is constructible and not yet completed at forTest exit". Net: JsonRpcErrorTest (5), MessageGateTest (5), IdStrategyTest (4), JsonRpcEnvelopeTest (5), JsonRpcIdTest (5), HandlerCtxTest (4), ExtrasEncoderTest (4); total 32. All 7 files are real `<basename>Test.scala` pairings; the design v2 8-file count is corrected here to 7 files with the JsonRpcRequest slot dropped (INTERNAL move) and the total case count preserved at 32.

The three appended cases:

```scala
// kyo-jsonrpc/shared/src/test/scala/kyo/MessageGateTest.scala ; appended
    "Reject and Drop are structurally distinct from Allow under pattern matching" in run {
        val cases: Seq[MessageGate.Decision] = Seq(
            MessageGate.Decision.Allow,
            MessageGate.Decision.Drop,
            MessageGate.Decision.Reject(JsonRpcError.InvalidParams)
        )
        val tags = cases.map:
            case MessageGate.Decision.Allow     => "A"
            case MessageGate.Decision.Drop      => "D"
            case MessageGate.Decision.Reject(_) => "R"
        assert(tags == Seq("A", "D", "R"))
    }

// kyo-jsonrpc/shared/src/test/scala/kyo/IdStrategyTest.scala ; appended
    "Custom with constant-returning function returns the same id repeatedly" in run {
        val custom: () => JsonRpcId < Sync = () => Sync.defer(JsonRpcId.Str("static"))
        val next = IdStrategyEngine.mkNextId(IdStrategy.Custom(custom))
        for
            a <- next()
            b <- next()
        yield
            assert(a == JsonRpcId.Str("static"))
            assert(b == JsonRpcId.Str("static"))
    }

// kyo-jsonrpc/shared/src/test/scala/kyo/HandlerCtxTest.scala ; appended
    "cancelled Promise is constructible and not yet completed at forTest exit" in run {
        for
            promise <- Fiber.Promise.init[Unit, Sync]
            ctx = HandlerCtx.forTest(promise, Absent, Absent, Absent)
            done <- ctx.cancelled.done
        yield assert(!done)
    }
```

### Files to modify

- (none; Phase 4 only adds new test files, no edits to existing sources.)

### Files to delete

- (none.)

### Public API additions / modifications

- (none.)

### Tests

Numbered list. Total new tests: 32 across 7 files.

1. `JsonRpcErrorTest.scala`: "RFC code constants match the spec catalog"
   - Given: the 11 documented `val`s on `JsonRpcError` (ParseError through RequestFailed).
   - When: read each `.code`.
   - Then: codes equal the literal RFC values (`-32700, -32600, -32601, -32602, -32603, -32002, -32001, -32800, -32801, -32802, -32803`).
   - Pins: design §JsonRpcError verdict; INV-001.

2. `JsonRpcErrorTest.scala`: "methodNotFound stamps the method name into message"
   - Given: `name = "subscribe"`.
   - When: `JsonRpcError.methodNotFound(name)`.
   - Then: `err.code == -32601`, `err.message == "Method not found: subscribe"`, `err.data == Absent`.
   - Pins: smart-constructor contract.

3. `JsonRpcErrorTest.scala`: "invalidRequest, invalidParams, internalError carry reason into data"
   - Given: three reason strings plus an optional data payload for `internalError`.
   - When: call each smart constructor.
   - Then: code matches RFC, message matches the documented prefix, `data` carries the reason as `Structure.Value.Str(...)`.
   - Pins: smart-constructor contract; INV-001.

4. `JsonRpcErrorTest.scala`: "cancelled smart constructor reports RequestCancelled with reason"
   - Given: reason `Present("client-cancel")` and reason `Absent`.
   - When: `JsonRpcError.cancelled(reason)`.
   - Then: code `-32800`, message `"Request cancelled"`, data tracks the reason.
   - Pins: smart-constructor contract; CancellationPolicy interplay.

5. `JsonRpcErrorTest.scala`: "Schema[JsonRpcError] round-trips through Structure"
   - Given: `JsonRpcError.invalidParams("bad")`.
   - When: encode then decode via `Structure.encode/decode`.
   - Then: decoded value equals original.
   - Pins: INV-001 (Schema preserved); INV-006 (wire-format stability).

6. `MessageGateTest.scala`: "Decision values are CanEqual-distinguishable across Allow / Reject / Drop"
   - Given: one of each Decision case.
   - When: pairwise `!=` comparisons.
   - Then: every pair is distinct.
   - Pins: `Decision derives CanEqual`.

7. `MessageGateTest.scala`: "Reject decision carries the supplied JsonRpcError"
   - Given: `err = JsonRpcError.invalidRequest("nope")`.
   - When: wrap in `Reject(err)`, pattern-match.
   - Then: captured error equals `err`.
   - Pins: ADT field shape.

8. `MessageGateTest.scala`: "a test-double gate returning Drop reports Drop for any envelope"
   - Given: a test-double `MessageGate` returning `Sync.defer(Decision.Drop)`.
   - When: call `beforeDispatch(notification-envelope)`.
   - Then: result `==` `Decision.Drop`.
   - Pins: trait contract used by `JsonRpcEndpoint.Config.gate`.

9. `MessageGateTest.scala`: "a test-double gate returning Allow reports Allow for any envelope"
   - Given: a test-double gate returning `Decision.Allow`.
   - When: call `beforeDispatch(request-envelope)`.
   - Then: result `==` `Decision.Allow`.
   - Pins: trait contract.

10. `MessageGateTest.scala`: "Reject and Drop are structurally distinct from Allow under pattern matching"
    - Given: a `Seq` of one Allow, one Drop, one Reject(err).
    - When: pattern-match each into a tag (`"A" | "D" | "R"`).
    - Then: tags equal `Seq("A", "D", "R")`.
    - Pins: exhaustive pattern-match coverage; INV-001 (ADT shape).

11. `IdStrategyTest.scala`: "SequentialLong allocates monotonically increasing JsonRpcId.Num starting at 1"
    - Given: `IdStrategyEngine.mkNextId(SequentialLong)`.
    - When: call three times.
    - Then: results are `Num(1L), Num(2L), Num(3L)`.
    - Pins: design §IdStrategy split; INV-002 (relocation behavior).

12. `IdStrategyTest.scala`: "SequentialInt allocates monotonically increasing JsonRpcId.Num starting at 1"
    - Given: `IdStrategyEngine.mkNextId(SequentialInt)`.
    - When: call twice.
    - Then: results are `Num(1L), Num(2L)`.
    - Pins: SequentialInt path widening to Long.

13. `IdStrategyTest.scala`: "Custom forwards verbatim to the supplied next function"
    - Given: an `AtomicLong` counter started at 99, `Custom(() => Sync.Unsafe.defer(JsonRpcId.Num(counter.incrementAndGet())))`.
    - When: call twice.
    - Then: results are `Num(100L), Num(101L)`.
    - Pins: `Custom` case bypasses engine counters.

14. `IdStrategyTest.scala`: "Custom with constant-returning function returns the same id repeatedly"
    - Given: `Custom(() => Sync.defer(JsonRpcId.Str("static")))`.
    - When: call twice.
    - Then: results are both `Str("static")`.
    - Pins: `Custom` is stateless; the engine does not memoize.

15. `JsonRpcEnvelopeTest.scala`: "Request, Notification, Response, Malformed are CanEqual-distinguishable"
    - Given: one of each envelope.
    - When: pairwise `!=`.
    - Then: all pairs distinct.
    - Pins: `derives CanEqual` on the enum.

16. `JsonRpcEnvelopeTest.scala`: "Request preserves the extras field on round-trip"
    - Given: `extras = Structure.Value.Str("opaque")`.
    - When: construct `Request(..., Present(extras))`.
    - Then: `req.extras == Present(extras)`.
    - Pins: extras field carry-through.

17. `JsonRpcEnvelopeTest.scala`: "Notification preserves the extras field on round-trip"
    - Same shape as 16 but on `Notification`.
    - Pins: extras field carry-through.

18. `JsonRpcEnvelopeTest.scala`: "Response with Present id and Present result is constructible"
    - Given: `id = Num(7L)`, `result = Present(Str("ok"))`, `error = Absent`.
    - When: construct.
    - Then: fields read back unchanged.
    - Pins: Response field shape.

19. `JsonRpcEnvelopeTest.scala`: "Malformed retains both reason and raw payload"
    - Given: `raw = Str("garbage")`, `reason = "bad-shape"`.
    - When: construct `Malformed(reason, raw)`.
    - Then: both fields read back.
    - Pins: Malformed field shape.

20. `JsonRpcIdTest.scala`: "Num case round-trips through Structure"
    - Given: `JsonRpcId.Num(42L)`.
    - When: encode then decode via `Structure`.
    - Then: decoded value equals original.
    - Pins: Schema writer/reader symmetry.

21. `JsonRpcIdTest.scala`: "Str case round-trips through Structure"
    - Given: `JsonRpcId.Str("abc")`.
    - When: encode then decode.
    - Then: decoded equals original.
    - Pins: Schema writer/reader symmetry.

22. `JsonRpcIdTest.scala`: "encoding Num produces a numeric Structure value"
    - Given: `Num(7L)`.
    - When: encode.
    - Then: result is `Structure.Value.Integer(7L)`.
    - Pins: writer dispatch on `Num`.

23. `JsonRpcIdTest.scala`: "encoding Str produces a string Structure value"
    - Given: `Str("x")`.
    - When: encode.
    - Then: result is `Structure.Value.Str("x")`.
    - Pins: writer dispatch on `Str`.

24. `JsonRpcIdTest.scala`: "decoding Structure.Value.Null fails"
    - Given: `Structure.Value.Null`.
    - When: `Structure.decode[JsonRpcId]`.
    - Then: result `.isFailure` (the reader throws `TypeMismatchException` on null).
    - Pins: design §JsonRpcId.Schema null path.

25. `HandlerCtxTest.scala`: "progress with a Present sink invokes the captured callback"
    - Given: an `AtomicRef` capturing a `List[Structure.Value]`, a sink that appends.
    - When: build a ctx via `forTest` with `Present(sink)`, call `ctx.progress(Str("p"))`.
    - Then: captured list `== List(Str("p"))`.
    - Pins: `progress` dispatch through `progressSink`.

26. `HandlerCtxTest.scala`: "progress with an Absent sink is a no-op"
    - Given: ctx via `forTest` with `Absent` sink.
    - When: call `ctx.progress(Str("p"))`.
    - Then: completes successfully without effect (no exception, no capture).
    - Pins: `progress` no-op branch.

27. `HandlerCtxTest.scala`: "extras and requestId are surfaced verbatim from forTest"
    - Given: `requestId = Present(Str("rid"))`, `extras = Present(Str("opaque"))`.
    - When: build via `forTest`.
    - Then: fields read back unchanged.
    - Pins: field exposure.

28. `HandlerCtxTest.scala`: "cancelled Promise is constructible and not yet completed at forTest exit"
    - Given: a fresh `Fiber.Promise.init[Unit, Sync]`.
    - When: build a ctx via `forTest`.
    - Then: `ctx.cancelled.done` is `false`.
    - Pins: ctx wires the cancellation channel without forcing completion.

29. `ExtrasEncoderTest.scala`: "empty.resolve always yields Absent regardless of id"
    - Given: `ExtrasEncoder.empty`.
    - When: `.resolve(Num(1L))` and `.resolve(Str("x"))`.
    - Then: both equal `Absent`.
    - Pins: `empty` definition.

30. `ExtrasEncoderTest.scala`: "const(v).resolve always yields Present(v) regardless of id"
    - Given: `v = Str("payload")`, `enc = ExtrasEncoder.const(v)`.
    - When: `.resolve(Num(1L))` and `.resolve(Str("x"))`.
    - Then: both equal `Present(v)`.
    - Pins: `const` definition.

31. `ExtrasEncoderTest.scala`: "apply(f).resolve forwards id to f"
    - Given: `enc = ExtrasEncoder(id => Sync.defer(Present(Str(id.toString))))`.
    - When: `.resolve(Num(7L))`.
    - Then: result `== Present(Str(Num(7L).toString))`.
    - Pins: `apply(f)` forwarding.

32. `ExtrasEncoderTest.scala`: "apply(f) lifts a Sync-effectful body through .resolve"
    - Given: an `AtomicLong` counter; `enc = ExtrasEncoder(_ => Sync.Unsafe.defer(Present(Integer(counter.incrementAndGet()))))`.
    - When: `.resolve(Num(1L))` then `.resolve(Num(2L))`.
    - Then: results are `Present(Integer(1L))`, `Present(Integer(2L))`.
    - Pins: `apply` preserves Sync effect.

### Consumed invariants

- INV-001, INV-002, INV-003, INV-005, INV-006, INV-007, INV-008.

### Produced invariants

- INV-006 (terminal in design v2; 8c gate green).
- INV-009.

### Convention sweep

- [em-dash, AllowUnsafe, Option-vs-Maybe, semicolons, asInstanceOf, default-params, Frame.internal, java.util.concurrent, llm-tells]

### Cross-platform set

- platforms: [jvm, js, native]

### Verification command

- `sbt 'kyo-jsonrpcJVM/test' 'kyo-jsonrpcJS/test' 'kyo-jsonrpcNative/test'` plus `flow-verify-grep.sh --catalog organization-8c --target kyo-jsonrpc/shared/src/`.
