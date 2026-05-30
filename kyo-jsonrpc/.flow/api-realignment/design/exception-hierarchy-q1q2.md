# JsonRpcError hierarchy design ; four open questions

Scope: explore the operation-category traits, multi-inheritance leaves, auxiliary-enum placement, and `JsonRpcInternalError` field shape for the realigned `JsonRpcError` hierarchy. All decisions assume the locked rules from `realignment-plan.md` Phase C: top-level types only, traits for subcategories, typed fields, message constructed from fields, `String` not `Text`.

References:
- Template: `kyo-http/shared/src/main/scala/kyo/HttpException.scala` lines 28-348.
- Current state: `kyo-jsonrpc/shared/src/main/scala/kyo/JsonRpcError.scala` lines 24-54.
- Engine construction sites: `kyo-jsonrpc/shared/src/main/scala/kyo/internal/engine/JsonRpcEndpointImpl.scala`.
- Codec construction sites: `kyo-jsonrpc/shared/src/main/scala/kyo/internal/codec/JsonRpcCodecImpl.scala`.
- Parser: `kyo-jsonrpc/shared/src/main/scala/kyo/internal/codec/RawJsonParser.scala`.
- Wire adapter: `kyo-jsonrpc/shared/src/main/scala/kyo/internal/transport/WireTransportAdapter.scala`.
- Downstream consumer: `kyo-browser/shared/src/main/scala/kyo/internal/CdpBackend.scala` lines 56-72.

---

## Inventory of actual error-construction sites

Engine, by stage of the request lifecycle:

| File:Line | Site | Stage |
|---|---|---|
| `JsonRpcEndpointImpl.scala:107` | `JsonRpcError.invalidParams(e.getMessage)` after `Structure.decode[Out]` failure on a response payload | Response decode (caller side, but really an `Application`-data shape failure) |
| `JsonRpcEndpointImpl.scala:128-129` | `JsonRpcError.cancelled(Absent)` on request timeout | Cancellation |
| `JsonRpcEndpointImpl.scala:203` | `JsonRpcError.invalidParams(e.getMessage)` on response decode | Response decode |
| `JsonRpcEndpointImpl.scala:226-227, 310-311` | `JsonRpcError.cancelled(Absent)` on per-call timeout (progress & non-progress paths) | Cancellation |
| `JsonRpcEndpointImpl.scala:373-374, 453-454` | `JsonRpcError.internalError("progress not configured: ...")` | Configuration error (pre-execution) |
| `JsonRpcEndpointImpl.scala:518, 534` | `JsonRpcError.invalidParams(e.getMessage)` on partial-result decode | Response decode |
| `JsonRpcEndpointImpl.scala:592, 602` | `JsonRpcError.cancelled(reason)` on explicit cancel | Cancellation |
| `JsonRpcEndpointImpl.scala:674, 680` | `JsonRpcError.internalError("endpoint closed", Absent)` on shutdown | Lifecycle/teardown |
| `JsonRpcEndpointImpl.scala:813, 836` | `JsonRpcError.internalError(s"transport closed: ...")` from sendCallback/receiveStream | Transport |
| `JsonRpcEndpointImpl.scala:819` | `JsonRpcError.internalError("wire decode error")` ; wire-level JSON decode failed | Wire/parse |
| `JsonRpcEndpointImpl.scala:1041-1044` | `JsonRpcError.internalError("Internal error", Present(t.getMessage))` when a handler **panics** | Handler execution |
| `JsonRpcEndpointImpl.scala:1096, 1115` | `JsonRpcError.methodNotFound(method)` for unknown method (request) | Dispatch |
| `JsonRpcEndpointImpl.scala:1213` | `JsonRpcError.invalidRequest(s"malformed response: $reason")` when an inbound response is malformed | Wire/parse on response |

Codec:

| File:Line | Site |
|---|---|
| `JsonRpcCodecImpl.scala:51, 151` | `JsonRpcError.internalError("cannot encode Malformed", Absent)` ; trying to encode a parse-failure carrier (programmer error in our code) |
| `JsonRpcCodecImpl.scala:108, 233` | `Structure.decode[JsonRpcError](ev).getOrElse(JsonRpcError.InvalidRequest)` ; fallback when the wire `error` payload itself fails to project |
| `JsonRpcCodecImpl.scala:166, 172, 174` | `JsonRpcError.invalidRequest("extras key/shape constraint")` ; CDP-mode extras shape policing |

Parser/transport boundary:

| File:Line | Site |
|---|---|
| `RawJsonParser.scala:32, 248, 254` | Parser throws `ParseException` directly (its own type) ; **not** `JsonRpcError`. |
| `WireTransportAdapter.scala:34` | Parser failures become `JsonRpcEnvelope.Malformed(_, "json parse failed", _)` ; never `JsonRpcError`. The dispatch stage decides how to respond. |

Downstream consumer (`CdpBackend.scala:56-71`):
- Matches `JsonRpcError.RequestCancelled.code` for timeout-as-disconnect translation.
- Matches `err.message == "endpoint closed"` and `err.message.startsWith("transport closed")` to decide between `BrowserConnectionLostException` and `BrowserProtocolErrorException`.

Critical observations:

1. **Pure parse errors never reach `JsonRpcError`**: the wire transport layer materialises them as `JsonRpcMalformedMessage` envelopes. A `JsonRpcParseError` value only enters existence when the engine constructs an *outbound response* in reaction to a peer's parse failure on our messages, or when the *peer* sends us a response whose `error.code == -32700`.
2. **`internalError` is overloaded today**: it covers four operationally distinct things ; configuration error, lifecycle teardown, transport failure, and handler panic. The `CdpBackend` consumer disambiguates via *string-prefix matching on `err.message`*. This is fragile and a strong signal that the operation should be a typed field.
3. **`invalidParams` is constructed for response-decode failures** in `JsonRpcEndpointImpl.scala:107, 203, 518, 534`. That is technically misuse of the -32602 code (the spec reserves -32602 for *params on a request*, not *result-shape on a response*). The decoder is failing to apply the caller's `Schema[Out]`. This is a place where the realignment can correct the code; or, if we keep the existing wire behaviour, document it as an intentional reuse.
4. **There is no `Application` site in engine code today.** Application errors arrive on the wire from handlers' `Abort.fail` and are decoded via `Structure.decode[JsonRpcError]`, then propagated unchanged.

---

## Question 1 ; operation-category traits

Restatement: which traits should the hierarchy carry to mark *operational stages*? The set must cover the stages where errors actually surface in the engine.

Distinct operational stages observed in the engine:

A. **Parse**: peer-sent bytes failed to project into JSON structure (today: surfaces as `Malformed`, then engine sends a `-32700` response).
B. **Dispatch**: routing a parsed request to a method (today: `methodNotFound`).
C. **Execution**: handler runs and panics (today: `internalError("Internal error", t.getMessage)`).
D. **Application**: handler completes with a domain `Abort.fail[E]` mapped to a wire error (today: not constructed here; arrives on the wire from peer).
E. **Codec / Encode**: response could not be encoded (today: `internalError("cannot encode Malformed")` ; programmer-error path).
F. **Transport / Lifecycle**: `endpoint closed`, `transport closed: ...`, `wire decode error` (today: bundled into `internalError`).
G. **Cancellation / Timeout**: timeout fires, peer-issued cancel arrives (today: `cancelled`).

The locked Phase C draft has only three subcategories: `Decode`, `Server`, `Application`. That collapses A+E into Decode, B+C+F+G into Server. Operationally, this **lumps configuration errors, handler panics, transport failures, and timeouts under the same trait**, which is exactly what makes the `CdpBackend` string-matching brittle.

### Option 1A ; four operation traits (Parse / Dispatch / Execution / Application)

```scala
sealed trait JsonRpcError extends KyoException:
    def code: Int
    def data: Maybe[Structure.Value]

sealed trait JsonRpcParseFailure       extends JsonRpcError
sealed trait JsonRpcDispatchFailure    extends JsonRpcError
sealed trait JsonRpcExecutionFailure   extends JsonRpcError
trait JsonRpcApplicationFailure        extends JsonRpcError   // user-extensible
```

Leaves multi-inherit where needed (see Question 2).

- Pro: minimal set, matches the JSON-RPC 2.0 spec's natural cuts (parse / dispatch / execution / app).
- Pro: matches kyo-http's count (kyo-http has 5 categories; we have 4 ; one shorter because we have no "connection-vs-request" split).
- Con: transport-level failures (`endpoint closed`, `transport closed`) and timeout/cancellation get folded into Execution, which is what `CdpBackend` is already string-matching to disambiguate.

### Option 1B ; six operation traits (1A + Transport + Cancellation)

```scala
sealed trait JsonRpcParseFailure       extends JsonRpcError
sealed trait JsonRpcDispatchFailure    extends JsonRpcError
sealed trait JsonRpcExecutionFailure   extends JsonRpcError
sealed trait JsonRpcTransportFailure   extends JsonRpcError
sealed trait JsonRpcCancellationFailure extends JsonRpcError
trait JsonRpcApplicationFailure        extends JsonRpcError
```

- Pro: directly replaces `CdpBackend`'s string-prefix matching with type-driven dispatch.
- Pro: cancellation/timeout is operationally distinct (caller may want to retry transport, but never wants to retry an explicit cancel).
- Con: more public surface; two of the new traits (`Transport`, `Cancellation`) have exactly one leaf each, which is the kyo-http red flag against over-categorising.

### Option 1C ; three traits, mirror Phase C draft exactly (Decode / Server / Application)

```scala
sealed trait JsonRpcDecodeFailure      extends JsonRpcError   // parse + dispatch + invalid-params
sealed trait JsonRpcServerFailure      extends JsonRpcError   // internal + server-error range
trait JsonRpcApplicationFailure        extends JsonRpcError
```

- Pro: matches kyo-http's "fail-mode-by-direction" framing (kyo-http has `HttpDecodeException` covering parse + decode + URL parse + field decode).
- Pro: smallest public surface.
- Con: bundles dispatch (which can succeed without any decode) into the decode category. `MethodNotFound` isn't really a *decode* failure ; the message decoded fine, the routing failed.
- Con: keeps the `CdpBackend` string-matching pattern because all transport/lifecycle failures still land under `Server`.

### Recommendation ; **Option 1A** (four operation traits)

Four is the minimal set that names every operationally-distinct stage. Transport/lifecycle failures fold into `JsonRpcExecutionFailure`, and the disambiguation that `CdpBackend` does today moves from string-matching `err.message` to **field-matching `operation` on the typed `JsonRpcInternalError`** (see Question 4 ; we recommend Option B). That keeps the trait count at four (vs six) while still giving downstream consumers a typed handle on transport-vs-handler-panic.

Cancellation has a single leaf (`JsonRpcRequestCancelled` per JSON-RPC LSP extension), which kyo-http precedent says doesn't justify its own category trait (kyo-http `HttpRequestException` has multiple leaves; single-leaf categories like `HttpWebSocketException` exist only when there's a non-trivial namespace to gather under them). Cancellation extends `JsonRpcExecutionFailure`.

Citations: kyo-http categories at `HttpException.scala:49, 90, 146, 191, 318` (5 sealed abstract classes); JSON-RPC dispatch site `JsonRpcEndpointImpl.scala:1096, 1115`; handler-panic site `JsonRpcEndpointImpl.scala:1041-1044`.

---

## Question 2 ; multi-inheritance leaves

Restatement: for each spec-defined leaf, which operation traits does it extend? Multi-inheritance is justified when the leaf surfaces in genuinely multiple stages.

For each leaf, the stages it surfaces in (per the inventory above):

| Leaf | Spec code | Surfaces in stage(s) | Trait(s) |
|---|---|---|---|
| `JsonRpcParseError` | -32700 | A (engine reacting to peer parse fail), F (peer sent us -32700) | `JsonRpcParseFailure` only |
| `JsonRpcInvalidRequestError` | -32600 | A (envelope shape failed: missing jsonrpc/id/method), also codec-fallback at `JsonRpcCodecImpl.scala:108, 233`, also extras-shape rejection at `JsonRpcCodecImpl.scala:166, 172, 174`, also malformed-response at `JsonRpcEndpointImpl.scala:1213` | `JsonRpcParseFailure` AND `JsonRpcDispatchFailure` |
| `JsonRpcMethodNotFoundError` | -32601 | B (engine dispatch) at `JsonRpcEndpointImpl.scala:1096, 1115` | `JsonRpcDispatchFailure` only |
| `JsonRpcInvalidParamsError` | -32602 | B (params validation pre-handler) and C (response decode failures at `:107, 203, 518, 534`) | `JsonRpcDispatchFailure` AND `JsonRpcExecutionFailure` |
| `JsonRpcInternalError` | -32603 | C (handler panic at `:1041`), E (codec failure at `:51, 151`), F (`endpoint closed`, `transport closed`, `wire decode error`), pre-execution config error at `:373, 453` | `JsonRpcExecutionFailure` only |
| `JsonRpcImplementationError` | -32099..-32000 | C (server-defined; mirrors -32603) | `JsonRpcExecutionFailure` only |
| `JsonRpcRequestCancelled` | -32800 (LSP) | C (`cancel()` and timeout) | `JsonRpcExecutionFailure` only |
| `JsonRpcCustomError` | application | D (caller-defined) | `JsonRpcApplicationFailure` only |

### Discussion of the two multi-inheritance candidates

**`JsonRpcInvalidRequestError`** ; Parse + Dispatch.

The wire decoder at `JsonRpcCodecImpl.scala:108` falls back to `InvalidRequest` when the peer's `error` object itself fails to project (parse-side use). Dispatch at `:1213` raises it for malformed *response* envelopes. The CDP extras-key check at `:166` is technically a dispatch-policy violation. The argument **for** multi-inheritance: a downstream consumer that wants "anything where the wire shape was wrong, regardless of which side" wants a single trait to match. The argument **against**: in practice no consumer asks that question; they ask "was this a *receive*-side problem (so I can't recover by retrying)" which is what `JsonRpcParseFailure` already names.

**`JsonRpcInvalidParamsError`** ; Dispatch + Execution.

Spec sense: -32602 is for params validation at dispatch time. Engine actuality: the engine *also* uses -32602 at `JsonRpcEndpointImpl.scala:107, 203, 518, 534` for **response-decode failures on the caller side**. That's not a params failure at all; it's a result-shape failure. Two ways to resolve:
- (a) Reclassify those four sites to use a new leaf `JsonRpcResultDecodeError` (or `JsonRpcInternalError` with an operation tag). Then `InvalidParamsError` is dispatch-only.
- (b) Keep the current wire semantics and have `InvalidParamsError` carry both traits.

(a) is technically cleaner but **changes the wire code on caller-side decode failures from -32602 to -32603**. That's a behaviour change visible to peers. Since the engine doesn't actually send these on the wire (the failure is local: it happens *after* receiving the response and the value is consumed by the caller's `Abort` channel), the wire code is irrelevant to peers ; it only affects what the caller code sees in `Abort.fail`. So (a) is safe.

### Recommendation

**Single-inheritance for every leaf.** Reclassify the four response-decode sites to `JsonRpcInternalError` with `operation = JsonRpcOperation.DecodeResult` (see Question 4 Option B). Reclassify the codec-fallback at `:108, 233` from `InvalidRequest` to a new construction site that records the actual project failure ; but this is internal to the codec, not on the wire, and can stay as `InvalidRequest` if simpler. Single-inheritance means:

```scala
case class JsonRpcParseError(reason: JsonRpcParseReason, data: Maybe[Structure.Value])(using Frame)
    extends KyoException(s"Parse error: ${reason.describe}") with JsonRpcParseFailure { val code = -32700 }

case class JsonRpcInvalidRequestError(reason: JsonRpcInvalidRequestReason, data: Maybe[Structure.Value])(using Frame)
    extends KyoException(s"Invalid Request: ${reason.describe}") with JsonRpcParseFailure { val code = -32600 }

case class JsonRpcMethodNotFoundError(method: String, data: Maybe[Structure.Value])(using Frame)
    extends KyoException(s"Method not found: '$method'") with JsonRpcDispatchFailure { val code = -32601 }

case class JsonRpcInvalidParamsError(method: String, problem: JsonRpcParamProblem, data: Maybe[Structure.Value])(using Frame)
    extends KyoException(s"Invalid params for '$method': ${problem.describe}") with JsonRpcDispatchFailure { val code = -32602 }

case class JsonRpcInternalError(operation: JsonRpcOperation, cause: Throwable)(using Frame)
    extends KyoException(s"Internal error during ${operation.describe}: ${cause.getMessage}", cause)
    with JsonRpcExecutionFailure { val code = -32603 }

case class JsonRpcImplementationError(code: Int, detail: String, data: Maybe[Structure.Value])(using Frame)
    extends KyoException(s"Server error ($code): $detail") with JsonRpcExecutionFailure

case class JsonRpcRequestCancelled(reason: Maybe[String], data: Maybe[Structure.Value])(using Frame)
    extends KyoException(s"Request cancelled${reason.map(r => s": $r").getOrElse("")}")
    with JsonRpcExecutionFailure { val code = -32800 }

abstract class JsonRpcApplicationError(val code: Int, msg: String, data: Maybe[Structure.Value])(using Frame)
    extends KyoException(msg) with JsonRpcApplicationFailure
case class JsonRpcCustomError(override val code: Int, message: String, data: Maybe[Structure.Value])(using Frame)
    extends JsonRpcApplicationError(code, message, data)
```

Rationale: **multi-inheritance buys flexibility at the cost of pattern-match determinism**. With single-inheritance, `case _: JsonRpcDispatchFailure` is unambiguous; with multi-inheritance, the same value matches both `Parse` and `Dispatch` and consumers need to know which to prefer. Single-inheritance also closes the only realistic ambiguity (response-decode-as-invalid-params) by recategorising it ; which is the more honest fix.

The user's rule says traits "support multi-inheritance for leaves that span operations". The analysis shows the only leaves with genuine multi-stage surface are `InvalidRequestError` (parse-vs-dispatch) and `InvalidParamsError` (dispatch-vs-execution), and **both reduce to single-inheritance under tighter site-by-site categorisation**. The multi-inheritance affordance stays in the trait design (the traits are mixable in principle) but no current leaf needs it. That's a healthier baseline than committing to multi-inheritance up front and then matching arbitrary leaves on it.

Citations: dispatch sites `JsonRpcEndpointImpl.scala:1096, 1115`; response-decode reuse of `-32602` at `:107, 203, 518, 534`; codec-fallback `JsonRpcCodecImpl.scala:108, 233`.

---

## Question 3 ; placement of auxiliary typed enums

Restatement: typed fields replace `detail: String`. Examples:

- `JsonRpcParseReason` (for `JsonRpcParseError`).
- `JsonRpcInvalidRequestReason` (for `JsonRpcInvalidRequestError`).
- `JsonRpcParamProblem` (for `JsonRpcInvalidParamsError`).
- `JsonRpcOperation` (for `JsonRpcInternalError`).

Estimated count of auxiliary enums: 4. Each is consumed by exactly one leaf.

### Option A ; all top-level, `JsonRpc*`-prefixed

```scala
package kyo
enum JsonRpcParseReason:
    case TrailingContent(position: Int)
    case UnexpectedEof(position: Int)
    case UnterminatedEscape(position: Int)
    case InvalidNumber(position: Int)
    case Expected(char: String, position: Int)
    def describe: String = this match
        case TrailingContent(p)  => s"trailing content at position $p"
        case UnexpectedEof(p)    => s"unexpected end of input at position $p"
        // ...
end JsonRpcParseReason
```

- Pro: maximally consistent with "everything top-level".
- Pro: cross-cuttable ; a future `JsonRpcParseAuditor` could consume `JsonRpcParseReason` directly.
- Con: inflates the top-level public surface by ~4 types, all consumed by exactly one leaf.
- Con: discoverability gets worse: a reader of `JsonRpcInvalidParamsError`'s scaladoc must follow a link to `JsonRpcParamProblem` to see the cases.

### Option B ; nested under the consuming leaf

```scala
package kyo
case class JsonRpcParseError(reason: JsonRpcParseError.Reason, data: Maybe[Structure.Value])(using Frame)
    extends KyoException(s"Parse error: ${reason.describe}") with JsonRpcParseFailure

object JsonRpcParseError:
    enum Reason:
        case TrailingContent(position: Int)
        case UnexpectedEof(position: Int)
        // ...
        def describe: String = ...
```

- Pro: locality ; the reason cases sit next to the leaf that consumes them.
- Pro: the dotted reference `JsonRpcParseError.Reason.TrailingContent` is self-describing at the call site.
- Pro: smaller top-level public surface (4 fewer types in the package).
- Con: violates the user's "no nesting" rule literally.

The user's rule says **no nesting under `JsonRpcEnvelope` and other primary public types**. The motivation behind that rule (from `realignment-plan.md` problem 1, plus Phase B) is that Phase 03 wrongly nested *protocol-level* types like `MessageGate`, `IdStrategy`, `CancellationPolicy` under their owners. Those nested types are independent abstractions consumed across the protocol. The auxiliary enums here are different in kind: each is a private vocabulary of one leaf, with no consumer outside it. The kyo-http template at `HttpException.scala` doesn't use enums for reason-types (it uses `detail: String`), so there's no direct precedent ; but the *spirit* of the rule (don't nest things that should be reusable) doesn't apply to a single-consumer enum.

### Option C ; nest as a regular ADT under the leaf, with a re-export at the top level

```scala
case class JsonRpcParseError(reason: JsonRpcParseError.Reason, ...)
object JsonRpcParseError:
    enum Reason:
        case ...

// in kyo package object or scala 3 export:
export JsonRpcParseError.Reason as JsonRpcParseReason
```

- Pro: lets callers use `JsonRpcParseReason` at the top level while keeping the source-of-truth nested.
- Con: two names for the same type ; pattern-match output uses one, doctest output may use the other. Confusing.

### Recommendation ; **Option B** (nested under consuming leaf)

The "everything top-level" rule was written against `MessageGate`/`IdStrategy`-style policy types that have independent semantic identity and cross-leaf consumers. The auxiliary enums proposed here are **private vocabularies of a single leaf**, comparable to how Scala stdlib nests `Try.Success` under `Try`. Top-leveling them (Option A) inflates the public surface by 4 types whose only valid call shape is `(consuming-leaf-name).Reason`; nothing else in the module ever takes a `JsonRpcParseReason` argument.

The user's stated motivation for the rule (don't bury protocol-level types under owners) is satisfied: the protocol-level types stay top-level; only the *reason vocabularies internal to one leaf* nest. The total top-level type count after this decision: 8 error types (1 base trait + 4 operation traits + 3 concrete-leaf hierarchies' top entries) + 0 auxiliary enums = 8. Option A would push this to 12.

Citations: `HttpException.scala` uses `detail: String` everywhere (lines 195, 210, 244, 255), so no direct kyo-http precedent on enums; the spec-rule against nesting (`realignment-plan.md` §"Naming decisions (locked)") targets policy and protocol types, not single-consumer ADTs.

---

## Question 4 ; `JsonRpcInternalError` field shape

Restatement: should `JsonRpcInternalError` carry just a `Throwable`, or also a typed operation tag, or additionally a `Maybe[Structure.Value]` wire-data slot?

Today the engine constructs `JsonRpcError.internalError(...)` in **four operationally distinct contexts** (see inventory): configuration error (`progress not configured`), lifecycle teardown (`endpoint closed`), transport failure (`transport closed: ...`, `wire decode error`), and handler panic (`Internal error` with `Structure.Value.Str(t.getMessage)`). Today the only way for a consumer to distinguish them is to match on the `message` string ; which is what `CdpBackend.scala:65-71` does:

```scala
else if err.message == "endpoint closed" || err.message.startsWith("transport closed") then
    Abort.fail(BrowserConnectionLostException(...))
else
    Abort.fail(BrowserProtocolErrorException(method, err.message))
```

This is the canonical example of "string-as-tag", and exactly what typed fields are supposed to fix.

### Option A ; cause only

```scala
case class JsonRpcInternalError(cause: Throwable)(using Frame)
    extends KyoException(s"Internal error: ${cause.getMessage}", cause)
    with JsonRpcExecutionFailure { val code = -32603 }
```

- Pro: matches kyo-http's `HttpHandlerException(error: Any)` at `HttpException.scala:159` ; cause-only is the kyo-http norm.
- Pro: minimum surface; the cause's class name and stack trace already encode the operation in practice.
- Con: forces `CdpBackend` to either keep string-matching or to match on the cause's runtime class. The four current call sites have **no cause `Throwable`** for three of them (config error, lifecycle teardown, `wire decode error`); the engine would need to construct synthetic `RuntimeException("endpoint closed")` instances just to fit the field.
- Con: the *configuration-not-set* sites at `:373, 453` aren't internal errors at all ; they're user-config failures and would need their own leaf anyway.

### Option B ; (operation, cause)

```scala
enum JsonRpcOperation:
    case Dispatch, ExecuteHandler, EncodeResponse, DecodeResult, Transport, Lifecycle, Configuration
    def describe: String = this match
        case Dispatch         => "method dispatch"
        case ExecuteHandler   => "handler execution"
        case EncodeResponse   => "response encoding"
        case DecodeResult     => "result decoding"
        case Transport        => "transport I/O"
        case Lifecycle        => "endpoint lifecycle"
        case Configuration   => "endpoint configuration"

case class JsonRpcInternalError(operation: JsonRpcOperation, cause: Throwable)(using Frame)
    extends KyoException(s"Internal error during ${operation.describe}: ${cause.getMessage}", cause)
    with JsonRpcExecutionFailure { val code = -32603 }
```

- Pro: directly replaces `CdpBackend`'s string-prefix matching with `case JsonRpcInternalError(JsonRpcOperation.Transport | JsonRpcOperation.Lifecycle, _) => ...`.
- Pro: scaladoc on `JsonRpcOperation` enumerates *exactly where in the engine an internal error can arise*, which is documentation that doesn't exist today.
- Con: requires every construction site to pick an operation. The four current sites pick: `Lifecycle` (`:674, 680`), `Transport` (`:813, 836`), `Transport` (`:819`), `ExecuteHandler` (`:1041`).
- Con: `cause: Throwable` is mandatory; the lifecycle/transport sites synthesise from `Closed.getMessage` strings (the existing code already does this awkwardly).

### Option C ; (operation, cause, data)

```scala
case class JsonRpcInternalError(
    operation: JsonRpcOperation,
    cause: Throwable,
    data: Maybe[Structure.Value]
)(using Frame) extends ...
```

- Pro: keeps the wire `data` slot for protocols (LSP) that attach structured diagnostic context.
- Con: in the current engine no construction site attaches structured data; the existing `:1041` site puts `Structure.Value.Str(t.getMessage)` which is just the cause's message in a different shape.
- Con: three positional parameters with mixed semantics is harder to read at call sites.

### Recommendation ; **Option B** (operation + cause, no data slot)

The motivating consumer evidence ; `CdpBackend.scala:65-71` ; shows that downstream code already wants to discriminate by operation. Replacing string-prefix matching with a typed enum is the highest-leverage change in this whole hierarchy. Skipping the `data: Maybe[Structure.Value]` slot keeps the constructor at two arguments and matches the observation that no current site attaches structured data; if a future LSP-style use case needs it, the field can be added as a `Maybe[Structure.Value] = Absent` default later without breaking callers (case-class binary compat is a separate issue but additive).

The four *non-cause* internal-error sites today (`endpoint closed`, `transport closed: $msg`, `wire decode error`, `progress not configured`) need explicit handling:
- `endpoint closed`, `transport closed` ; synthesise `new java.lang.IllegalStateException("endpoint closed")` or carry the `Closed` exception as the cause directly. The `Closed` value (from `Abort.run[Closed]`) **is** a `Throwable`, so it satisfies the constructor.
- `wire decode error` ; the original `Result.Panic(t)` already carries a `Throwable`; capture it.
- `progress not configured` ; this is a **configuration error**, not an internal error. Either reclassify under a new `JsonRpcConfigurationError` leaf (probably extending `JsonRpcExecutionFailure`) or keep it as `InternalError(JsonRpcOperation.Configuration, new IllegalStateException("progress not configured"))`. The former is more honest; the latter is less disruptive. Lean toward the former.

Citations: kyo-http handler-error pattern `HttpException.scala:159`; CDP string-matching `CdpBackend.scala:65-71`; engine internal-error sites `:373, 453, 674, 680, 813, 819, 836, 1041`.

---

## Decision summary

| Q | Decision | Rationale (1 sentence) |
|---|---|---|
| 1 | **Four operation traits**: `JsonRpcParseFailure`, `JsonRpcDispatchFailure`, `JsonRpcExecutionFailure`, `JsonRpcApplicationFailure`. | Minimal set that names every operationally-distinct stage in the engine; transport and cancellation fold into Execution because they have one leaf each. |
| 2 | **Single-inheritance for every leaf**: reclassify the four response-decode sites currently using -32602 to `InternalError(operation=DecodeResult, ...)`; codec-fallback to `InvalidRequest` stays. | Genuine multi-stage surface dissolves under tighter per-site categorisation, and single-inheritance gives consumers deterministic pattern-match arms. |
| 3 | **Nest auxiliary enums under their consuming leaf** (`JsonRpcParseError.Reason`, `JsonRpcInvalidParamsError.Problem`, etc.). | The "no-nesting" rule targets protocol-level types with cross-leaf consumers; single-leaf reason vocabularies are private to one leaf and don't justify 4 extra top-level types. |
| 4 | **`JsonRpcInternalError(operation: JsonRpcOperation, cause: Throwable)`**; introduce a separate `JsonRpcConfigurationError` leaf rather than overloading `InternalError` for config errors. | The `CdpBackend.scala:65-71` string-matching today is the canonical example of "should have been a typed field"; the operation enum names exactly where in the engine internal errors arise. |

---

## Most-important trade-off grappled with

The hardest call was Question 2 ; whether to commit to multi-inheritance as a structural affordance or single-inheritance with per-site reclassification. Multi-inheritance is the user's stated rule and offers maximal flexibility for future leaves. But concretely tracing every construction site in the engine showed that **only two leaves** even appeared in multiple stages, and **both reduce to single-stage when the sites are categorised honestly** ; the response-decode-as-invalid-params reuse is technically misuse of -32602, and `InvalidRequest`-as-codec-fallback at `:108, 233` is a fallback choice, not a genuine semantic claim. Keeping the trait design multi-inheritance-capable (mixable traits) but committing every current leaf to single-inheritance gives the cleanest pattern-match surface today without painting future leaves into a corner.
