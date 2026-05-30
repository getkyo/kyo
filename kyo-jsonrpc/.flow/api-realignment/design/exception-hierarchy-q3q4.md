# JsonRpcError hierarchy ; Q3 (auxiliary enum placement) and Q4 (internal-error shape)

Scope: the prior exploration (`exception-hierarchy-design.md`) resolved the trait set (Q1) and the multi-inheritance question (Q2). The two remaining design questions are (Q3) where to place the auxiliary reason-vocabularies that replace today's `detail: String` slots, and (Q4) how to express the four operationally-distinct uses of today's `JsonRpcError.internalError`.

Anchors checked while writing this note:
- `kyo-jsonrpc/shared/src/main/scala/kyo/JsonRpcError.scala:24-54` (current shape).
- `kyo-http/shared/src/main/scala/kyo/HttpException.scala:28-348` (template).
- `kyo-jsonrpc/shared/src/main/scala/kyo/internal/engine/JsonRpcEndpointImpl.scala` (all `JsonRpcError.*` call sites, enumerated by grep; the operationally distinct internal-error sites are lines 373, 453, 674, 680, 813, 819, 836, 1041).
- `kyo-browser/shared/src/main/scala/kyo/internal/CdpBackend.scala:60-71` (the consumer that disambiguates by string prefix today).

---

## Question 3 ; placement of auxiliary reason-enums

### Restatement

The realigned hierarchy replaces `detail: String` with typed fields. The candidate vocabularies are:

| Consumer leaf | Vocabulary | Approx. case count |
|---|---|---|
| `JsonRpcParseError` | `ParseReason` (trailing content, EOF, unterminated escape, invalid number, ...) | ~5-8 |
| `JsonRpcInvalidRequestError` | `InvalidRequestReason` (missing jsonrpc field, missing method, malformed id, extras-shape violation, malformed-response) | ~5 |
| `JsonRpcInvalidParamsError` | `ParamProblem` (missing field, wrong type, range violation, ...) and possibly a `ParamError(field, problem)` record wrapping it | ~4 + 1 record |
| `JsonRpcInternalError` | `JsonRpcOperation` (Transport, Lifecycle, HandlerPanic, EncodeResponse, DecodeResult, Configuration) | ~6 (see Q4) |

Total: four enums + one supporting record. Each enum is consumed by exactly one leaf today.

### Option A ; top-level, all `JsonRpc*`-prefixed

```scala
package kyo

enum JsonRpcParseReason:
    case TrailingContent(position: Int)
    case UnexpectedEof(position: Int)
    case UnterminatedEscape(position: Int)
    case InvalidNumber(position: Int)
    case Expected(char: String, position: Int)

enum JsonRpcInvalidRequestReason:
    case MissingJsonRpcField
    case MissingMethod
    case MalformedId(detail: String)
    case ExtrasShape(field: String, reason: String)
    case MalformedResponse(reason: String)

enum JsonRpcParamProblem:
    case Missing
    case WrongType(expected: String, actual: String)
    case OutOfRange(reason: String)
    case DecodeFailed(detail: String)

case class JsonRpcParamError(field: String, problem: JsonRpcParamProblem) derives CanEqual

enum JsonRpcOperation:
    case Transport, Lifecycle, HandlerPanic, EncodeResponse, DecodeResult, Configuration
```

Caller construction: `JsonRpcParseError(JsonRpcParseReason.UnexpectedEof(pos), Absent)`. Pattern match: `case JsonRpcParseReason.UnexpectedEof(p) => ...`.

Trade-offs:
- Pro: maximally consistent with the "everything top-level with `JsonRpc*` prefix" rule.
- Pro: if a future leaf wants to share a vocabulary (e.g. a `JsonRpcStrictParseError` that reuses `JsonRpcParseReason`), it just works.
- Pro: each vocabulary is its own unit of documentation; scaladoc for `JsonRpcParamProblem` is a single page.
- Con: top-level public surface inflates by **5 types** (4 enums + the `JsonRpcParamError` record) on top of the 8 base/trait/leaf types already in scope. The package goes from 8 to 13 `JsonRpc*` entries that a reader skimming the package sees first.
- Con: the dotted form at call sites is `JsonRpcParamProblem.WrongType(...)`, which is fine, but the connection to `JsonRpcInvalidParamsError` is only visible in the constructor signature, not in the symbol's name.

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

case class JsonRpcInvalidParamsError(method: String, errors: Chunk[JsonRpcInvalidParamsError.ParamError], data: Maybe[Structure.Value])(using Frame)
    extends KyoException(...) with JsonRpcDispatchFailure
object JsonRpcInvalidParamsError:
    case class ParamError(field: String, problem: Problem) derives CanEqual
    enum Problem:
        case Missing, WrongType(expected: String, actual: String), OutOfRange(reason: String), DecodeFailed(detail: String)

case class JsonRpcInternalError(operation: JsonRpcInternalError.Operation, cause: Throwable)(using Frame)
    extends KyoException(...) with JsonRpcExecutionFailure
object JsonRpcInternalError:
    enum Operation:
        case Transport, Lifecycle, HandlerPanic, EncodeResponse, DecodeResult, Configuration
```

Caller construction: `JsonRpcParseError(JsonRpcParseError.Reason.UnexpectedEof(pos), Absent)`. Pattern match: `case JsonRpcParseError.Reason.UnexpectedEof(p) => ...`.

Trade-offs:
- Pro: locality. A reader landing on `JsonRpcParseError` sees its vocabulary in the same companion. The IDE outline view shows leaf + reasons together.
- Pro: zero new top-level types; the package surface stays at 8.
- Pro: the dotted reference `JsonRpcParseError.Reason.UnexpectedEof` self-describes the binding at the use site.
- Con: literal violation of the user's "no nesting" rule.
- Con: if a second leaf later wants to consume the same vocabulary, the source-of-truth nesting becomes awkward (the other leaf has to reach into a sibling's companion).
- Con: the dotted form `JsonRpcParseError.Reason.UnexpectedEof` is verbose at call sites.

### Option C ; top-level in a `kyo.jsonrpc` (or `kyo.errors`) subpackage

```scala
package kyo.jsonrpc  // or kyo.errors

enum ParseReason
enum InvalidRequestReason
enum ParamProblem
case class ParamError(field: String, problem: ParamProblem)
enum Operation
```

The leaves stay in `kyo`:

```scala
package kyo
case class JsonRpcParseError(reason: jsonrpc.ParseReason, ...) ...
```

Trade-offs:
- Pro: keeps `kyo.*` package surface lean.
- Pro: groups all auxiliary vocabularies in one place, the same way the codebase already groups internals under `kyo.internal.*`.
- Con: introduces a *public* subpackage when the project currently only has `kyo.internal.*` (which is the private-by-convention subpackage). Adding `kyo.jsonrpc` or `kyo.errors` is a new pattern with no precedent.
- Con: callers see `kyo.jsonrpc.ParseReason` and `kyo.JsonRpcParseError` at the same level of intent ; that split is confusing.
- Con: doesn't match the kyo-http template; kyo-http puts `HttpStatus` at top-level in `kyo.*`, not in `kyo.http.*`.

### kyo-http precedent

`HttpException.scala` does **not** use enums for reason-types ; it uses `detail: String` (lines 195, 210, 244, 255). So there is no direct enum-placement precedent inside `HttpException.scala`. The closest analogue is `HttpStatus`, which IS top-level (`kyo.HttpStatus`). But `HttpStatus` is consumed by multiple leaves (`HttpStatusException`, route definitions, response builders) ; it has cross-cutting consumers, which is exactly the criterion that justifies top-leveling.

The auxiliary enums proposed here are different in kind: each is consumed by exactly one leaf, with no expected cross-cutting use. The kyo-http precedent for top-leveling (`HttpStatus`) does not extend to single-consumer vocabularies.

### Recommendation ; **Option B (nested under consuming leaf)**

The user's "no nesting" rule was stated against the wire-message ADT (`JsonRpcEnvelope.Request` should be top-level `JsonRpcRequest`) and the policy types from Phase 03 (`MessageGate`, `IdStrategy`, `CancellationPolicy`). Both targets are *cross-cutting protocol types* with multiple consumers and independent semantic identity. The auxiliary reason vocabularies here are the opposite: each is a private vocabulary of one leaf. Top-leveling them (Option A) inflates the `kyo.*` public surface by 5 entries whose only valid usage shape is `<leaf-name>.Reason.<case>` ; nothing else in the protocol ever consumes a `JsonRpcParseReason`.

`JsonRpcOperation` (Q4) is the most plausible cross-cutter ; a downstream consumer like CdpBackend might want to match on it across multiple leaves. But the realigned design has only ONE leaf carrying it (`JsonRpcInternalError`), so the cross-cutting argument is hypothetical. If a future leaf needs it, promoting `JsonRpcInternalError.Operation` to top-level `JsonRpcOperation` is a non-breaking move (Scala 3 `export` makes it transparent for one release).

Risk of API churn under Option B: if `ParamProblem` later needs to be shared with a `JsonRpcResultDecodeError` leaf (because Q2's recommended reclassification of -32602 response-decode reuses similar shapes), we promote it then. Until then, nesting keeps the surface honest about who actually consumes each vocabulary.

---

## Question 4 ; `JsonRpcInternalError` field shape

### Restatement

Today `JsonRpcError.internalError(...)` is constructed in four operationally-distinct contexts:

| Context | Engine sites | Today's message string |
|---|---|---|
| Configuration | `JsonRpcEndpointImpl.scala:373, 453` | `"progress not configured: ..."` |
| Lifecycle (endpoint shutdown) | `:674, 680` | `"endpoint closed"` |
| Transport (I/O failure) | `:813, 836` | `"transport closed: ${closedException.getMessage}"` |
| Transport (wire JSON decode) | `:819` | `"wire decode error"` |
| Handler panic | `:1041` | `"Internal error"` + `Structure.Value.Str(t.getMessage)` |

CdpBackend.scala:65 disambiguates by string-prefix on `err.message`:

```scala
else if err.message == "endpoint closed" || err.message.startsWith("transport closed") then
    Abort.fail(BrowserConnectionLostException(...))
else
    Abort.fail(BrowserProtocolErrorException(method, err.message))
```

This is the canonical "string-as-tag" smell. The replacement must give CdpBackend a typed handle.

### Option A ; single `JsonRpcInternalError` + `JsonRpcOperation` enum

```scala
case class JsonRpcInternalError(operation: JsonRpcOperation, cause: Throwable)(using Frame)
    extends KyoException(s"Internal error during ${operation.describe}: ${cause.getMessage}", cause)
    with JsonRpcExecutionFailure { val code = -32603 }

object JsonRpcInternalError:
    enum Operation:
        case Configuration, Lifecycle, Transport, HandlerPanic, EncodeResponse, DecodeResult
```

CdpBackend migration:

```scala
case e: JsonRpcInternalError if e.operation == JsonRpcInternalError.Operation.Transport
                             || e.operation == JsonRpcInternalError.Operation.Lifecycle =>
    Abort.fail(BrowserConnectionLostException(...))
```

Trade-offs:
- Pro: one leaf to remember; one constructor signature; consumers discriminate by a typed field.
- Pro: matches the prior exploration's Q4 recommendation directly.
- Pro: replacing string-prefix matching is mechanical and contained.
- Con: the configuration site at `:373, 453` is not really an *internal* error ; it's a misuse of the API (the caller forgot to set `progressPolicy`). Putting it under `JsonRpcInternalError` continues to conflate "we panicked" with "you misconfigured us".
- Con: the handler-panic site has a `Throwable` (`t` from `Result.Panic(t)`); the lifecycle/transport sites do NOT have a natural `Throwable` ; they have a `Closed` exception object, which IS a `Throwable` but is being matched-on as a value. The constructor forces them all into a uniform `cause: Throwable` slot.

### Option B ; separate leaves for each operational context (with their own fields)

```scala
case class JsonRpcConfigurationError(setting: String, reason: String)(using Frame)
    extends KyoException(s"Endpoint misconfiguration: $setting ; $reason") with JsonRpcExecutionFailure {
    val code = -32603
}

case class JsonRpcLifecycleError(stage: JsonRpcLifecycleError.Stage)(using Frame)
    extends KyoException(s"Endpoint ${stage.describe}") with JsonRpcExecutionFailure {
    val code = -32603
}
object JsonRpcLifecycleError:
    enum Stage:
        case Closed, ShuttingDown

case class JsonRpcTransportError(detail: String, cause: Maybe[Throwable])(using Frame)
    extends KyoException(s"Transport error: $detail") with JsonRpcExecutionFailure {
    val code = -32603
}

case class JsonRpcHandlerPanicError(method: String, cause: Throwable)(using Frame)
    extends KyoException(s"Handler '$method' panicked: ${cause.getMessage}", cause) with JsonRpcExecutionFailure {
    val code = -32603
}

case class JsonRpcInternalError(operation: JsonRpcOperation, cause: Throwable)(using Frame)
    extends KyoException(...) with JsonRpcExecutionFailure { val code = -32603 }
    // catchall for EncodeResponse, DecodeResult, anything not separately leaf-typed
```

CdpBackend migration:

```scala
case _: JsonRpcLifecycleError | _: JsonRpcTransportError =>
    Abort.fail(BrowserConnectionLostException(...))
case _: JsonRpcError =>
    Abort.fail(BrowserProtocolErrorException(method, err.message))
```

Trade-offs:
- Pro: each leaf carries the fields that operation actually has ; `JsonRpcConfigurationError(setting, reason)` is honest about what data it has; the handler-panic carries the method name (which would otherwise be lost).
- Pro: matches the kyo-http precedent strongly: kyo-http has separate leaves for `HttpBindException` (server-side bind), `HttpHandlerException` (handler panic), `HttpStatusException` (protocol), `HttpConnectException` (transport). These are exactly the operationally-distinct stages kyo-http judged worth leaf-typing.
- Pro: pattern-matching is by-type, which is the most idiomatic Scala 3 form.
- Pro: enables removing the conflation between "misconfigured" and "panicked" ; the configuration error stops sharing the -32603 wire code's connotation (though it still uses the code because there's no better one in the spec).
- Con: adds 4 top-level leaf types to `kyo.*` (Configuration, Lifecycle, Transport, HandlerPanic).
- Con: requires every engine construction site to pick the right leaf. Reading the inventory: the four sites map cleanly (`:373, 453` -> Configuration; `:674, 680` -> Lifecycle; `:813, 836` -> Transport with `cause = Closed`; `:819` -> Transport with `cause = Absent`; `:1041` -> HandlerPanic with `cause = t`). No site needs to "smuggle" data into a wrong-shaped leaf.

### Option C ; mixed (separate leaves for the ones whose fields differ; single `InternalError` catchall)

```scala
case class JsonRpcConfigurationError(setting: String, reason: String)(using Frame) extends ...
case class JsonRpcHandlerPanicError(method: String, cause: Throwable)(using Frame) extends ...
case class JsonRpcInternalError(operation: JsonRpcOperation, cause: Throwable)(using Frame) extends ...
    // operation covers Lifecycle, Transport, EncodeResponse, DecodeResult
```

Trade-offs:
- Pro: smaller surface than Option B (2 new leaves + 1 catchall instead of 4).
- Pro: keeps the "doesn't really fit anywhere" cases (encode/decode failures) consolidated.
- Con: the CdpBackend split between "connection lost" (Lifecycle + Transport) and "protocol error" (other) still requires inspecting `JsonRpcInternalError.operation` ; partial migration of the string-matching problem. Cleaner than Option A but not as clean as Option B.
- Con: drawing the line between "deserves its own leaf" and "lives under `JsonRpcInternalError`" is a judgement call that future contributors will redo.

### kyo-http precedent

kyo-http has SEPARATE leaves for distinct failure modes:
- `HttpBindException(host, port, cause)` ; `HttpException.scala:150` ; server bind failure with its own fields.
- `HttpHandlerException(error: Any)` ; `:159` ; handler panic with its own field.
- `HttpConnectException(host, port, cause)` ; `:53` ; transport connect failure.
- `HttpPoolExhaustedException(host, port, maxConnections, clientFrame)` ; `:68` ; pool failure.

kyo-http does NOT have a single `HttpInternalError(operation, cause)` with a discriminator enum. Every operationally-distinct failure gets its own leaf with its own typed fields. This is a strong precedent for **Option B**.

### Recommendation ; **Option B (separate leaves)**

CdpBackend.scala:65-71 is the existence proof that consumers want to discriminate by operation. The kyo-http template makes the same call ; separate leaves with operation-specific fields, no discriminator enum on a single super-leaf. Option B costs 4 new top-level types but each one carries fields that the operation actually has (the handler-panic gets `method`, the configuration error gets `setting`, the transport error gets an optional cause for the wire-decode case). Option A's `cause: Throwable` constraint forces three of the five sites to synthesise a `Throwable` they don't naturally have. Option C is a defensible compromise but ends up rebuilding the string-prefix-matching problem at a smaller scale: CdpBackend still has to inspect `operation` for the Lifecycle/Transport split.

Construction-site verification (engine has enough typed context):
- `:373, 453` ; the field name (`"progressPolicy"`) and the constraint (`"required for progress(...) calls"`) are statically known. `JsonRpcConfigurationError(setting, reason)` fits cleanly.
- `:674, 680` ; the lifecycle stage is statically `Closed`. `JsonRpcLifecycleError(Stage.Closed)` fits.
- `:813, 836` ; the `Closed` value is in scope as `c`. `JsonRpcTransportError(c.getMessage, Present(c))` fits.
- `:819` ; the underlying parser failure is in `Result.Failure(_)` ; the engine currently discards it. `JsonRpcTransportError("wire decode error", Absent)` fits, OR (better) capture the actual failure into the cause slot.
- `:1041` ; the method name is in scope (the inbound entry knows its method); `JsonRpcHandlerPanicError(method, t)` fits and recovers the method name that is currently lost.

No site needs to smuggle data into a wrong-shaped leaf, and Option B recovers two pieces of information that the current API discards (the method name on handler panic; the captured `Closed`/`Throwable` cause on transport failures).

---

## Decision summary

| Q | Decision | Rationale |
|---|---|---|
| 3 | **Nest auxiliary reason-enums under the consuming leaf** (`JsonRpcParseError.Reason`, `JsonRpcInvalidParamsError.{ParamError, Problem}`, `JsonRpcInternalError.Operation`). | The user's no-nesting rule targets cross-cutting protocol types (the wire-message ADT, policy types); single-consumer reason vocabularies are private to one leaf and don't justify 5 new top-level entries. kyo-http top-levels `HttpStatus` because it has multiple consumers; these enums have one each. |
| 4 | **Separate leaves** for each operationally-distinct internal-error context: `JsonRpcConfigurationError`, `JsonRpcLifecycleError`, `JsonRpcTransportError`, `JsonRpcHandlerPanicError`, plus a residual `JsonRpcInternalError(operation, cause)` catchall for encode/decode programmer-error paths. | Matches kyo-http's leaf-per-operation pattern (`HttpBindException`, `HttpHandlerException`, ...); gives CdpBackend a typed handle to replace string-prefix matching; recovers two pieces of information (handler method name, transport `Closed` cause) the current API discards. |

## Most-important trade-off grappled with per question

**Q3**: the tension between "consistency with the no-nesting rule applied literally" (Option A) and "honest accounting of which types are cross-cutting versus private to one leaf" (Option B). The literal reading inflates the package surface by 5 single-consumer types; the honest reading preserves the surface but explicitly nests. The honest reading wins because the rule's *motivation* (don't bury reusable protocol types) is preserved; only single-consumer vocabularies nest.

**Q4**: choosing between a single-leaf-with-discriminator (Option A; lowest surface) and leaf-per-operation (Option B; +4 surface but matches kyo-http). The deciding factor was that Option B's leaves carry fields the operations actually have, while Option A forces every site through a uniform `(operation, cause: Throwable)` shape that doesn't fit configuration (no cause) or lifecycle (no cause) sites without synthesising a Throwable. The kyo-http precedent is unambiguous on this and the engine inventory confirms each leaf has a real, statically-known shape.
