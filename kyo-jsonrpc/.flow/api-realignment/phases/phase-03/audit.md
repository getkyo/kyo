# Phase 03 audit — JsonRpcError sealed hierarchy

Commit: `cc067892b` ([jsonrpc] Realignment Phase 03: sealed JsonRpcError hierarchy)
Plan: `kyo-jsonrpc/.flow/api-realignment/design/realignment-plan.md` §Phase C (lines 103-348)
Design refs: `exception-hierarchy-q1q2.md`, `exception-hierarchy-q3q4.md`

## Verdict

**0 BLOCKER · 1 WARN · 4 NOTE**

Phase 03 satisfies every locked design rule. The sealed-base hierarchy, the four
top-level operation traits, the 11 typed-field leaves, the 1 abstract open
`JsonRpcApplicationError`, and the nested auxiliary enums all match the
realignment-plan and the q3/q4 resolutions verbatim. All 5 `internalError`
reclassifications and 4 out-of-spec `-32602` reclassifications land at the
file:line anchors specified by the plan. CdpBackend's string-prefix match is
fully migrated to typed pattern matches. 184/184 JVM tests green; cross-module
compile green on kyo-jsonrpc, kyo-jsonrpc-http, and kyo-browser.

## Item-by-item audit

### 1. Base shape ; PASS

`kyo-jsonrpc/shared/src/main/scala/kyo/JsonRpcError.scala:30-36`

```scala
sealed abstract class JsonRpcError(
    val code: Int,
    val message: String,
    val data: Maybe[Structure.Value],
    cause: String | Throwable = ""
)(using Frame)
    extends KyoException(message, cause)
```

Matches plan line 123 verbatim. `sealed abstract class` extending `KyoException`
with the wire-triple `(code, message, data)` plus `cause` and `(using Frame)`.

### 2. Four operation-trait subcategories ; PASS

`JsonRpcError.scala:121, 126, 131, 136` — all four are **sealed top-level traits**
in `package kyo`, not nested under `object JsonRpcError`:

- `sealed trait JsonRpcParseFailure extends JsonRpcError`
- `sealed trait JsonRpcDispatchFailure extends JsonRpcError`
- `sealed trait JsonRpcExecutionFailure extends JsonRpcError`
- `sealed trait JsonRpcApplicationFailure extends JsonRpcError`

The `object JsonRpcError` companion contains only `fromWire` (lines 45-53) and
the hand-rolled `Schema[JsonRpcError]` (lines 64-115). No nested traits or
leaves.

### 3. 11 leaves + 1 abstract open class ; PASS

All 12 are top-level in `package kyo`:

| Leaf | File:Line | Trait | Field shape |
|---|---|---|---|
| `JsonRpcParseError` | `:153` | `JsonRpcParseFailure` | `(input, offset, reason: Reason)` |
| `JsonRpcInvalidRequestError` | `:189` | `JsonRpcParseFailure` | `(received: Structure.Value, missingFields: Chunk[String])` |
| `JsonRpcMethodNotFoundError` | `:212` | `JsonRpcDispatchFailure` | `(method, available: Chunk[String])` |
| `JsonRpcInvalidParamsError` | `:231` | `JsonRpcDispatchFailure` | `(method, received: Maybe[Structure.Value], errors: Chunk[ParamError])` |
| `JsonRpcConfigurationError` | `:274` | `JsonRpcExecutionFailure` | `(setting, reason)` |
| `JsonRpcLifecycleError` | `:289` | `JsonRpcExecutionFailure` | `(stage: Stage)` |
| `JsonRpcTransportError` | `:313` | `JsonRpcExecutionFailure` | `(detail, cause: Throwable)` |
| `JsonRpcHandlerPanicError` | `:330` | `JsonRpcExecutionFailure` | `(method, cause: Throwable)` |
| `JsonRpcInternalError` | `:347` | `JsonRpcExecutionFailure` | `(operation: Operation, cause: Throwable)` |
| `JsonRpcImplementationError` | `:380` | `JsonRpcExecutionFailure` | `(code, label, data)` private + smart factory |
| `JsonRpcCustomError` | `:439` | (via Application) | `(code, label, data)` extends `JsonRpcApplicationError` |
| `JsonRpcApplicationError` | `:423` | `JsonRpcApplicationFailure` | abstract open `(code, message, data, cause)` |

Field signatures match the plan verbatim (cross-referenced against plan lines
151-302). No leaf is nested under `object JsonRpcError`.

### 4. Aux-enum nesting ; PASS

Each auxiliary enum lives in the companion of its single-consumer leaf, never
top-level:

| Aux | Owner companion | File:Line |
|---|---|---|
| `Reason` | `object JsonRpcParseError` | `:163-178` |
| `ParamError` (case class) + `Problem` (enum) | `object JsonRpcInvalidParamsError` | `:245-260` |
| `Stage` | `object JsonRpcLifecycleError` | `:296-302` |
| `Operation` | `object JsonRpcInternalError` | `:355-364` |

Matches q3 resolution exactly.

### 5. NO `detail: String` parameter on leaves ; PASS

Every leaf parameter is one of: `String` field-naming the data
(`method`/`setting`/`label`), `Int` (`code`/`offset`), `Chunk[T]`,
`Maybe[Structure.Value]`, `Throwable`, `Reason`/`Stage`/`Operation`/`Problem`
enum, or `Structure.Value`. The message string is constructed inside each leaf
body via `s"..."` interpolation from the typed fields.

The one apparent exception ; `JsonRpcTransportError(detail: String, cause:
Throwable)` ; is **plan-specified** (realignment-plan.md line 239). `detail` here
IS the data (the transport-closed reason or wire-decode tag); there is no
caller-supplied pre-rendered message. This matches the kyo-http precedent
`HttpConnectException` and was explicitly resolved in pulse-1.

### 6. NO `Text` parameter anywhere ; PASS

`grep -nE "\bText\b"` across `JsonRpcError.scala`, `JsonRpcEnvelope.scala`,
`JsonRpcHandler.scala`, `JsonRpcRoute.scala` returns zero hits. All message and
field types use `String`.

### 7. Five `internalError` reclassifications ; PASS

| Plan | Engine site | New leaf | Verified |
|---|---|---|---|
| Configuration | `JsonRpcEndpointImpl.scala:377` | `JsonRpcConfigurationError("progressPolicy", ...)` | ✓ |
| Configuration | `JsonRpcEndpointImpl.scala:458` | `JsonRpcConfigurationError("progressPolicy", ...)` | ✓ |
| Lifecycle | `JsonRpcEndpointImpl.scala:687, 693` | `JsonRpcLifecycleError(Stage.Close)` | ✓ |
| Transport | `JsonRpcEndpointImpl.scala:826, 852` | `JsonRpcTransportError(s"transport closed: ...", c)` | ✓ |
| Transport (wire decode) | `JsonRpcEndpointImpl.scala:832` | `JsonRpcTransportError(...)` | ✓ |
| HandlerPanic | `JsonRpcEndpointImpl.scala:1057` | `JsonRpcHandlerPanicError(method, t)` | ✓ |
| InternalError (encode) | `JsonRpcCodecImpl.scala` (Malformed encode) | `JsonRpcInternalError(Operation.EncodeResponse, ...)` | ✓ |
| InternalError (Other) | `ProgressEngine.scala:23` | `JsonRpcInternalError(Operation.Other, ...)` | ✓ |

(The plan said "5 internalError sites"; the decisions.md ledger groups them as
5 operational contexts ; configuration ×2, lifecycle, transport ×3, handler
panic ×1 ; spread across 8 source lines. All landed at the right leaf.)

### 8. Four out-of-spec -32602 reclassifications ; PASS

| Plan-named site | Actual site | Reclassification |
|---|---|---|
| `JsonRpcEndpointImpl.scala:107` | `:108` | `JsonRpcInternalError(Operation.DecodeResult, e)` |
| `JsonRpcEndpointImpl.scala:203` | `:206` | `JsonRpcInternalError(Operation.DecodeResult, e)` |
| `JsonRpcEndpointImpl.scala:518` | `:524-525` | `JsonRpcInternalError(Operation.DecodeResult, ...)` |
| `JsonRpcEndpointImpl.scala:534` | `:543-544` | `JsonRpcInternalError(Operation.DecodeResult, ...)` |

Lines drift slightly because of intervening edits to surrounding code, but
each site is identifiable by context (post-`Structure.decode[Out]` failure on
the response payload). All four migrated correctly.

### 9. CdpBackend.scala:60-71 typed-pattern migration ; PASS

`kyo-browser/shared/src/main/scala/kyo/internal/CdpBackend.scala:59-72`:

```scala
case e: JsonRpcCustomError if e.code == -32800 =>
    Abort.fail(BrowserConnectionLostException(s"Request timeout: $method", Absent))
case _: JsonRpcTransportError | _: JsonRpcLifecycleError =>
    Abort.fail(BrowserConnectionLostException(s"Connection lost during $method", Absent))
case err =>
    Abort.fail(BrowserProtocolErrorException(method, err.message))
```

String-prefix-match (`err.message == "endpoint closed" || err.message.startsWith("transport closed")`)
gone. Typed leaf-pattern in place.

### 10. Hand-rolled Schema[JsonRpcError] ; PASS

`JsonRpcError.scala:64-115`. Three-channel implementation:

- `serializeWrite` emits the wire triple `{code: Int, message: String, data: Structure.Value | nil}` ; matches JSON-RPC 2.0 §5.1.
- `serializeRead` decodes `(code, message)` and routes through `fromWire(code, message, Absent)`. (The binary `serializeRead` path drops `data`; see NOTE below.)
- `fromStructureValue` (the primary Structure round-trip path used by `Structure.encode`/`decode`) decodes the full `(code, message, data)` triple and routes through `fromWire`.

Wire round-trip is tested in `JsonRpcErrorTest.scala:96-108` with both an
empty-data leaf and a data-carrying leaf, both asserting `code`, `data`, and
the resulting leaf type. The Phase 02 wire-format tests continue to pass.

### 11. Em-dashes ; PASS

`grep -c "—"` across all 13 modified main-source files: **0**.

Two test files in the diff contain em-dashes:
- `JsonRpcPortInvariantsSpec.scala:174, 499` ; these are sweep assertions that
  *check for* em-dashes (`!content.exists(c => c == '—' || c == '–')`). The
  em-dashes are the search target, not prose. Allowed.
- `CdpClientDecoderTest.scala:125, 151` ; pre-existing comments NOT touched by
  this commit (`git show cc067892b` shows no `+`/`-` lines with em-dashes).
  Out of scope for Phase 03; remains for a later sweep if desired.

### 12. Convention sweep ; PASS (per decisions.md and pulse-2)

decisions.md line 106 records "9/9 = 0 hits in changed files". Spot-checks
confirm:
- No `protected`. No `@uncheckedVariance`. No blocking primitives. No `Option`/
  `Either`/`List` in changed signatures (`Maybe`/`Result`/`Chunk` throughout).
- All public types in `JsonRpcError.scala` carry scaladoc (8-35 lines each).
- `using Frame` clauses present on every leaf constructor as required for
  `KyoException`'s frame capture.

## Held-out cross-reference: 11 leaves × plan signatures

Verified all 11 leaves' field shapes against the plan table at
realignment-plan.md lines 151-302. **Every leaf has the exact field shape
specified.** Specifically:

- `JsonRpcInvalidParamsError` ; `(method: String, received: Maybe[Structure.Value], errors: Chunk[ParamError])` ✓ (NOT `(method, detail: String)`)
- `JsonRpcMethodNotFoundError` ; `(method: String, available: Chunk[String])` ✓
- `JsonRpcParseError` ; `(input: String, offset: Int, reason: Reason)` ✓
- `JsonRpcInvalidRequestError` ; `(received: Structure.Value, missingFields: Chunk[String])` ✓

No leaf substitutes a `detail: String` for the typed structured fields the plan
prescribes.

## WARN

**W1. Schema[JsonRpcError] binary-path drops `data`.** `serializeRead`
(`JsonRpcError.scala:78-98`) reads only `code` and `message` and passes
`data = Absent` to `fromWire`. This is intentional per decisions.md note #2
("the data field is captured in `fromStructureValue`; the binary `serializeRead`
path drops data ; no current binary decode caller needs it"), and the wire
round-trip is correct on the primary `Structure.encode`/`decode` path. But this
is asymmetric: if a future binary decoder (JSON via the lower-level reader
path, or Protobuf) round-trips a `JsonRpcError`, the `data` slot will be lost.
The plan's "preserve wire format byte-identically" requirement is satisfied for
the Structure-round-trip code path (which is what the codec uses) but not for
the binary-reader path. Recommend either documenting this limitation on the
`Schema` definition or filling in the `data` field in `serializeRead` for full
parity.

## NOTE

**N1. Stale scaladoc reference in `JsonRpcTransport.scala:90`** (not modified by
this commit): scaladoc still references `JsonRpcError.parseError(reason)` (the
old factory API). The file was last touched in Phase 01 and the reference is
pre-existing. Suggest a follow-up sweep to update to
`JsonRpcParseError(input, offset, reason)`.

**N2. ProgressEngine scaladoc fixed.** Pulse-1 and pulse-2 flagged a stale
`internalError` reference at `ProgressEngine.scala:13`. The committed file shows
the comment has been updated to reference
`JsonRpcInternalError(Operation.Other, ...)`. Resolved.

**N3. JsonRpcImplementationError uses `extends JsonRpcExecutionFailure`.** This
matches the plan (line 278) and the q3/q4 categorization. The JSON-RPC 2.0 spec
calls this range "implementation-defined server errors" ; conceptually closer
to application errors than to execution failures. Not a violation of the locked
design; flagging only because the categorization is a design judgement worth
documenting in the scaladoc.

**N4. Three em-dash STEER items flagged by pulse-2 were resolved before
commit.** The commit message records "Also folds in 4 em-dash cleanups
(no em-dashes in any kyo-jsonrpc or kyo-jsonrpc-http source)". Verified: 0
em-dashes in changed main-source files.

## Confidence

**High.** The hierarchy, leaf field shapes, trait categorization, aux-enum
nesting, reclassification anchors, and CdpBackend migration all match the
locked design verbatim. Wire-format preservation is verified via the
`Structure.encode`/`decode` round-trip tests. The one WARN (W1) is a
documented design choice that does not violate the plan's primary
requirement on the codec path, but warrants a follow-up if the binary path
becomes a real consumer.
