# Phase 1 Prep: 8a sub-symbol relocations and PUBLIC markers

Prepared from live source at kyo-jsonrpc/shared/src/main/scala/kyo/*.scala (as of 2026-05-28).

## New file to produce

### IdStrategyEngine.scala (kyo/internal/)

Insertion point: Create new file at `kyo-jsonrpc/shared/src/main/scala/kyo/internal/IdStrategyEngine.scala`.

The `mkNextId` definition to relocate from IdStrategy.scala lines 10-25:

```scala
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
```

## Files to modify

### IdStrategy.scala

Current top-of-file (lines 1-7):
```scala
package kyo

enum IdStrategy derives CanEqual:
    case SequentialLong
    case SequentialInt
    case Custom(next: () => JsonRpcId < Sync)
end IdStrategy
```

Remove: lines 9-25 (entire `object IdStrategy` companion with `mkNextId` definition).

Add: PUBLIC marker as first line after `package kyo`.

**API signature (public half):**
```scala
enum IdStrategy derives CanEqual:
    case SequentialLong
    case SequentialInt
    case Custom(next: () => JsonRpcId < Sync)
end IdStrategy
```

---

### JsonRpcCodec.scala

Current top-of-file (lines 1-6):
```scala
package kyo

import kyo.Abort
import kyo.Frame
import kyo.Structure
import kyo.Sync
```

Remove: lines 17-18 (`private[kyo] val cdpReservedKeys: Set[String] = Set("id", "method", "params", "result", "error", "jsonrpc")`).

Add: PUBLIC marker as first line after `package kyo`.

**API signature (public half):**
```scala
trait JsonRpcCodec:
    def encode(env: JsonRpcEnvelope)(using Frame): Structure.Value < (Sync & Abort[JsonRpcError])
    def decode(raw: Structure.Value)(using Frame): JsonRpcEnvelope < Sync
end JsonRpcCodec

object JsonRpcCodec:
    val Strict2_0: JsonRpcCodec = internal.JsonRpcCodecImpl.Strict2_0
    val Cdp: JsonRpcCodec       = internal.JsonRpcCodecImpl.Cdp
```

---

### JsonRpcCodecImpl.scala (internal)

Current context (line 5):
```scala
private[kyo] object JsonRpcCodecImpl:

    val Strict2_0: JsonRpcCodec = new JsonRpcCodec:
```

Insertion point: Add `private val cdpReservedKeys` immediately after line 5 (before `val Strict2_0`).

**Definition to insert:**
```scala
    private val cdpReservedKeys: Set[String] =
        Set("id", "method", "params", "result", "error", "jsonrpc")
```

Rewrite references:
- Line 151: `JsonRpcCodec.cdpReservedKeys.contains` -> `cdpReservedKeys.contains`
- Line 172: `JsonRpcCodec.cdpReservedKeys` -> `cdpReservedKeys`

---

### JsonRpcEndpointImpl.scala (internal)

Line 735 current:
```scala
        val nextIdFn        = IdStrategy.mkNextId(config.idStrategy)
```

Rewrite to:
```scala
        val nextIdFn        = IdStrategyEngine.mkNextId(config.idStrategy)
```

(No new import needed; package kyo.internal contains both IdStrategyEngine and JsonRpcEndpointImpl.)

---

### CancellationPolicy.scala

Current top-of-file (lines 1-5):
```scala
package kyo

import kyo.Maybe
import kyo.Maybe.Absent
import kyo.Maybe.Present
```

Add: PUBLIC marker as first line after `package kyo`.

**API signature:**
```scala
enum CancellationPolicy derives CanEqual:
    case lsp
    case mcp
end CancellationPolicy
```

---

### ExtrasEncoder.scala

Current top-of-file (lines 1-3):
```scala
package kyo

opaque type ExtrasEncoder = JsonRpcId => Maybe[Structure.Value] < Sync
```

Add: PUBLIC marker as first line after `package kyo` (the existing `// flow-allow: opaque-type companion carve-out` at line 13 stays).

**API signature:**
```scala
opaque type ExtrasEncoder = JsonRpcId => Maybe[Structure.Value] < Sync

object ExtrasEncoder:
    def apply(f: JsonRpcId => Maybe[Structure.Value] < Sync): ExtrasEncoder = f
    val empty: ExtrasEncoder = (_: JsonRpcId) => Absent
    def const(extras: Structure.Value): ExtrasEncoder = (_: JsonRpcId) => Present(extras)
```

---

### HandlerCtx.scala

Current top-of-file (lines 1-11):
```scala
package kyo

import kyo.Async
import kyo.Closed
import kyo.Frame
import kyo.Maybe
import kyo.Maybe.Absent
import kyo.Maybe.Present
import kyo.Structure
import kyo.Sync

final class HandlerCtx private[kyo] (
```

Add: PUBLIC marker as first line after `package kyo`.

Add `// flow-allow:` rationales:
- Line 12 (`final class HandlerCtx private[kyo]`): add preceding line `// flow-allow: Hub.scala:22 smart-constructor pattern; framework creates instances via forTest or JsonRpcEndpointImpl`.
- On `forTest` factory (in companion, around line 25): add preceding line `// flow-allow: test-only construction escape hatch consumed by JsonRpcMethodTest`.

**API signature:**
```scala
final class HandlerCtx private[kyo] (
    val cancelled: Fiber.Promise[Unit, Sync],
    val requestId: Maybe[JsonRpcId],
    val extras: Maybe[Structure.Value],
    private[kyo] val progressSink: Maybe[Structure.Value => Unit < (Async & Abort[Closed])]
)
```

---

### JsonRpcEndpoint.scala

Current top-of-file (lines 1-5):
```scala
package kyo

import kyo.Stream

final class JsonRpcEndpoint private[kyo] (private[kyo] val impl: internal.JsonRpcEndpointImpl):
```

Add: PUBLIC marker as first line after `package kyo`.

Add `// flow-allow:` rationales:
- Line 5 (primary constructor): add preceding line `// flow-allow: Hub.scala:22 smart-constructor pattern; init through JsonRpcEndpoint.init`.
- On `Pending[Out]` inner class constructor (around line 52): add preceding line `// flow-allow: Hub.scala:22 smart-constructor pattern; Pending built only by JsonRpcEndpointImpl.callWithProgress`.

**API signature:**
```scala
final class JsonRpcEndpoint private[kyo] (private[kyo] val impl: internal.JsonRpcEndpointImpl):
    def call[In: Schema, Out: Schema](method: String, params: In, extras: ExtrasEncoder = ExtrasEncoder.empty)(using Frame): JsonRpcId < (Async & Abort[JsonRpcError | Closed])
    def notify[In: Schema](method: String, params: In, extras: ExtrasEncoder = ExtrasEncoder.empty)(using Frame): Unit < (Async & Abort[Closed])
    def callWithProgress[In: Schema, Out: Schema](method: String, params: In, extras: ExtrasEncoder = ExtrasEncoder.empty)(using Frame): Pending[Out] < (Async & Abort[JsonRpcError | Closed])
    def callPartialResults[In: Schema, Out: Schema](method: String, params: In, extras: ExtrasEncoder = ExtrasEncoder.empty)(using Frame): Stream[Out, Async & Abort[JsonRpcError | Closed]]
    def subscribeProgress(id: JsonRpcId, sink: Structure.Value => Unit < (Async & Abort[Closed]))(using Frame): Unit < (Async & Abort[Closed])
    def unsubscribeProgress(id: JsonRpcId)(using Frame): Unit < (Async & Abort[Closed])
    def cancel(id: JsonRpcId, reason: Maybe[String] = Absent)(using Frame): Unit < (Async & Abort[Closed])
    def awaitDrain(using Frame): Unit < (Async & Abort[Closed])
    def close(using Frame): Unit < Async

    final class Pending[Out] private[kyo] (...)
```

---

### JsonRpcEnvelope.scala

Current top-of-file (lines 1-4):
```scala
package kyo

import kyo.Maybe
import kyo.Structure
```

Add: PUBLIC marker as first line after `package kyo`.

**API signature:**
```scala
enum JsonRpcEnvelope derives CanEqual:
    case Request(id: JsonRpcId, method: String, params: Maybe[Structure.Value], extras: Maybe[Structure.Value])
    case Notification(method: String, params: Maybe[Structure.Value], extras: Maybe[Structure.Value])
    case Response(id: JsonRpcId, result: Maybe[Structure.Value], error: Maybe[JsonRpcError], extras: Maybe[Structure.Value])
    case Malformed(reason: String, raw: Structure.Value)
end JsonRpcEnvelope
```

---

### JsonRpcError.scala

Current top-of-file (lines 1-4):
```scala
package kyo

import kyo.Frame
import kyo.Maybe
```

Add: PUBLIC marker as first line after `package kyo`.

**API signature:**
```scala
enum JsonRpcError derives CanEqual:
    case ParseError
    case InvalidRequest
    case MethodNotFound
    case InvalidParams
    case InternalError
    case ServerNotInitialized
    case UnknownErrorCode
    case RequestCancelled
    case ContentModified
    case ServerCancelled
    case RequestFailed
    // ... plus companion with smart constructors
```

---

### JsonRpcId.scala

Current top-of-file (lines 1-4):
```scala
package kyo

import kyo.Schema
import kyo.Structure
```

Add: PUBLIC marker as first line after `package kyo`.

**API signature:**
```scala
enum JsonRpcId derives Schema, CanEqual:
    case Num(value: Long)
    case Str(value: String)
end JsonRpcId
```

---

### JsonRpcMethod.scala

Current top-of-file (lines 1-11):
```scala
package kyo

import kyo.Abort
import kyo.Async
import kyo.Frame
import kyo.Maybe
import kyo.Maybe.Absent
import kyo.Maybe.Present
import kyo.Result
import kyo.Schema
import kyo.Structure
```

Add: PUBLIC marker as first line after `package kyo`.

Add `// flow-allow:` rationales on abstract members:
- Line 16 (`private[kyo] def schemaIn`): add preceding line `// flow-allow: Stream.scala:48 sealed-protocol with framework-only abstract members`.
- Line 17 (`private[kyo] def schemaOut`): add preceding line `// flow-allow: Stream.scala:48 sealed-protocol with framework-only abstract members`.
- Line 18 (`private[kyo] def handle`): add preceding line `// flow-allow: Stream.scala:48 sealed-protocol with framework-only abstract members`.

**API signature:**
```scala
sealed trait JsonRpcMethod[+S]:
    def name: String
    def kind: JsonRpcMethod.Kind
    private[kyo] def schemaIn: Schema[?]
    private[kyo] def schemaOut: Schema[?]
    private[kyo] def handle(params: Structure.Value, ctx: HandlerCtx)(using Frame): Structure.Value < (Async & Abort[JsonRpcError])
end JsonRpcMethod

object JsonRpcMethod:
    enum Kind derives CanEqual:
        case Request
        case Notification
    end Kind
```

---

### JsonRpcRequest.scala

Current top-of-file (lines 1-8):
```scala
package kyo

import kyo.Frame
import kyo.Maybe
import kyo.Maybe.Absent
import kyo.Maybe.Present
import kyo.Schema
import kyo.Structure
```

Add: PUBLIC marker as first line after `package kyo`.

Add `// flow-allow:` rationales:
- Line 10 (`case class JsonRpcRequest private[kyo]`): add preceding line `// flow-allow: Hub.scala:22 smart-constructor pattern; framework-only construction (relocates INTERNAL in Phase 2)`.
- Line 16 (`case class JsonRpcResponse private[kyo]`): add preceding line `// flow-allow: Hub.scala:22 smart-constructor pattern; users construct JsonRpcResponse through .success / .failure factories`.

**API signatures:**
```scala
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

object JsonRpcResponse:
    def success(id: JsonRpcId, result: Structure.Value)(using Frame): JsonRpcResponse
    def failure(id: JsonRpcId, error: JsonRpcError)(using Frame): JsonRpcResponse
```

---

### JsonRpcTransport.scala

Current top-of-file (lines 1-4):
```scala
package kyo

import kyo.Stream

trait JsonRpcTransport:
```

Add: PUBLIC marker as first line after `package kyo`.

**API signature:**
```scala
trait JsonRpcTransport:
    def send(env: JsonRpcEnvelope)(using Frame): Unit < (Async & Abort[Closed])
    def incoming: Stream[JsonRpcEnvelope, Async & Abort[Closed]]
end JsonRpcTransport

object JsonRpcTransport:
    def inMemory(capacity: Int)(using Frame): (JsonRpcTransport, JsonRpcTransport) < Sync
```

---

### MessageGate.scala

Current file (all 12 lines):
```scala
package kyo

trait MessageGate:
    def beforeDispatch(env: JsonRpcEnvelope)(using Frame): MessageGate.Decision < Sync

object MessageGate:
    enum Decision derives CanEqual:
        case Allow
        case Reject(error: JsonRpcError)
        case Drop
    end Decision
end MessageGate
```

Add: PUBLIC marker as first line after `package kyo`.

**API signature:**
```scala
trait MessageGate:
    def beforeDispatch(env: JsonRpcEnvelope)(using Frame): MessageGate.Decision < Sync

object MessageGate:
    enum Decision derives CanEqual:
        case Allow
        case Reject(error: JsonRpcError)
        case Drop
    end Decision
```

---

### ProgressPolicy.scala

Current top-of-file (lines 1-5):
```scala
package kyo

import kyo.Maybe
import kyo.Maybe.Absent
import kyo.Maybe.Present
```

Add: PUBLIC marker as first line after `package kyo`.

**API signature:**
```scala
enum ProgressPolicy derives CanEqual:
    case lsp
    case mcp
end ProgressPolicy
```

---

### UnknownMethodPolicy.scala

Current top-of-file (lines 1-7):
```scala
package kyo

final case class UnknownMethodPolicy private[kyo] (
    onUnknownRequest: UnknownMethodPolicy.UnknownAction,
    onUnknownNotification: UnknownMethodPolicy.UnknownAction,
    dollarPrefixOverride: Boolean
) derives CanEqual
```

Add: PUBLIC marker as first line after `package kyo`.

Add `// flow-allow:` rationale:
- Line 3 (case class constructor): add preceding line `// flow-allow: Hub.scala:22 smart-constructor pattern; users select .minimal / .lsp / .strict`.

**API signature:**
```scala
final case class UnknownMethodPolicy private[kyo] (
    onUnknownRequest: UnknownMethodPolicy.UnknownAction,
    onUnknownNotification: UnknownMethodPolicy.UnknownAction,
    dollarPrefixOverride: Boolean
) derives CanEqual

object UnknownMethodPolicy:
    enum UnknownAction derives CanEqual:
        case ReplyMethodNotFound
        case Drop
        case Reject
    end UnknownAction
    val minimal: UnknownMethodPolicy
    val lsp: UnknownMethodPolicy
    val strict: UnknownMethodPolicy
```

## Summary

- 13 PUBLIC files receiving top-of-file markers
- 2 SPLIT files (IdStrategy, JsonRpcCodec) relocating sub-symbols
- 1 new file to produce (IdStrategyEngine.scala in kyo/internal/)
- 3 file modifications involving cdpReservedKeys and mkNextId
- 5 files receiving `// flow-allow:` rationales on `private[kyo]` members (HandlerCtx, JsonRpcEndpoint, JsonRpcMethod, JsonRpcRequest, UnknownMethodPolicy)
