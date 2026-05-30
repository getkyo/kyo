# kyo-jsonrpc realignment plan

The original 7-phase campaign committed (Phase 01 through Phase 07, all green cross-platform). Subsequent audit shows the campaign achieved ~5/30 alignment axes and ~7/30 partial alignment, with 18 remaining divergences from the kyo-http template. This plan addresses those 18 + 7 partials.

Source: `kyo-jsonrpc/.flow/api-cleanup/research/kyo-http-vs-kyo-jsonrpc-audit.md`.

## Problem statement

The kyo-jsonrpc API was supposed to mirror kyo-http's structural conventions. The implemented result diverges along these dimensions:

1. **Wrong direction on nesting**: Phase 03 nested 9 types under their companions (`MessageGate`, `IdStrategy`, `CancellationPolicy`, `ProgressPolicy`, `UnknownMethodPolicy`, `ExtrasEncoder`, `Framer`, `WireTransport`, plus envelope cases). kyo-http keeps the equivalent types top-level with the `Http` prefix. D5's resolved verdict was `RENAME-WITH-PREFIX-KEEP-PUBLIC`; D6 reversed it under my pressure. The audit confirms D5 was correct.
2. **`JsonRpcEndpoint` is a `final class`**; kyo-http's `HttpServer` is an `opaque type`.
3. **`JsonRpcMethod[+S]` exposes a free effect row**; kyo-http's `HttpRoute[In, Out, +E]` exposes data dimensions + a typed error.
4. **`JsonRpcError` is a flat case class**, not a sealed hierarchy extending `KyoException`. The Frame parameter on the helper factories is silently ignored.
5. **Wire messages nested in `JsonRpcEnvelope`**: `Request`, `Response`, `Notification`, `Malformed` are enum cases. kyo-http has separate `HttpRequest` / `HttpResponse` top-level types.
6. **Missing lifecycle variants**: `initWith`, `initUnscoped`, `initUnscopedWith` not present.
7. **Missing patterns**: no Halt short-circuit, no `.error[E](code, message)` declarations, no `JsonRpcFilter` analogue to `HttpFilter`.
8. **Additional findings**: 10 smaller divergences (A1-A10 in the audit). Each fits into the phases below.

## Naming decisions (locked)

Per user choice in the chat:
- `JsonRpcEndpoint` → `JsonRpcHandler` (the runtime)
- `JsonRpcMethod` → `JsonRpcRoute` (the declarative entry)
- `JsonRpcEnvelope.Request` → top-level `JsonRpcRequest`
- `JsonRpcEnvelope.Response` → top-level `JsonRpcResponse` (restored, undoes Phase 02 merge)
- `JsonRpcEnvelope.Notification` → top-level `JsonRpcNotification`
- `JsonRpcEnvelope.Malformed` → top-level `JsonRpcMalformedMessage`
- `JsonRpcEnvelope.Id` → top-level `JsonRpcId` (restored, undoes Phase 03 rename)
- `JsonRpcEnvelope` itself → bare `sealed trait`, no nested members

Plus from the audit's recommendation (still requires sign-off):
- `JsonRpcMethod.Context` → `JsonRpcRoute.Context` (carries the rename)
- `MessageGate` → top-level `JsonRpcMessageGate`
- `CancellationPolicy` → top-level `JsonRpcCancellationPolicy`
- `ProgressPolicy` → top-level `JsonRpcProgressPolicy`
- `UnknownMethodPolicy` → top-level `JsonRpcUnknownMethodPolicy`
- `IdStrategy` → top-level `JsonRpcIdStrategy`
- `ExtrasEncoder` → top-level `JsonRpcExtrasEncoder`
- `Framer` → top-level `JsonRpcFramer`
- `WireTransport` → top-level `JsonRpcWireTransport`

After realignment, the top-level public surface in `kyo.*` under `kyo-jsonrpc/shared/`:

| Type | Kind | Role |
|---|---|---|
| `JsonRpcHandler` | opaque type | Runtime peer |
| `JsonRpcRoute` | sealed trait `[In, Out, +E]` | Declarative handler entry |
| `JsonRpcRequest` | case class extends `JsonRpcEnvelope` | Wire request |
| `JsonRpcResponse` | case class extends `JsonRpcEnvelope` | Wire response (with `.Halt` for short-circuit) |
| `JsonRpcNotification` | case class extends `JsonRpcEnvelope` | Wire notification |
| `JsonRpcMalformedMessage` | case class extends `JsonRpcEnvelope` | Wire parse-failure carrier |
| `JsonRpcEnvelope` | sealed trait | Parent of the 4 wire types |
| `JsonRpcId` | opaque type | Wire correlation id |
| `JsonRpcError` | sealed abstract extends KyoException | Error hierarchy root |
| `JsonRpcError.{ParseError, InvalidRequest, MethodNotFound, InvalidParams, InternalError, ServerError, ApplicationError}` | case classes | Leaf errors |
| `JsonRpcTransport` | sealed trait | Wire transport seam |
| `JsonRpcTransportException` | sealed abstract extends KyoException | Transport error hierarchy root |
| `JsonRpcCodec` | trait + presets | Wire codec |
| `JsonRpcMessageGate` | trait + namespace presets | Filter / interceptor analogue |
| `JsonRpcCancellationPolicy` | sealed trait + companion | Cancel-protocol policy |
| `JsonRpcProgressPolicy` | sealed trait + companion | Progress-protocol policy |
| `JsonRpcUnknownMethodPolicy` | sealed trait + companion | Unknown-method handling |
| `JsonRpcIdStrategy` | sealed trait + companion | Outbound id generation |
| `JsonRpcExtrasEncoder` | opaque type + companion | Protocol-extension encoder |
| `JsonRpcFramer` | trait + companion | Byte-framing strategy |
| `JsonRpcWireTransport` | trait + companion | Byte-stream transport |

Count: ~21 top-level + nested Config/Halt/Decision under owning types. Comparable to kyo-http's 27 top-level Http* types.

## Phase plan

Eight phases, each producing one commit. Each phase preserves build-green + test-green before commit. Cross-platform verification at the final phase only (per the established convention).

### Phase A — Foundation renames (no behaviour change)

Scope:
- Rename `JsonRpcEndpoint` → `JsonRpcHandler` everywhere (source + tests + downstream callers in kyo-jsonrpc-http and kyo-browser).
- Rename `JsonRpcMethod` → `JsonRpcRoute` everywhere.
- Rename `JsonRpcMethod.Context` → `JsonRpcRoute.Context`.
- Rename `JsonRpcTestBase` → `JsonRpcTest` (per audit Axis 19) — verify no collision with sibling modules' `Test` class.
- Drop or rename the 2nd-overload `JsonRpcRoute.apply` (no-Context form) per audit A6, mitigating eta-expansion ambiguity.

Estimate: ~250 LoC, ~80 files. Pure renames; sed-driven across .scala files plus targeted handler-signature fixups.

Verification: `sbt 'kyo-jsonrpc/Test/compile' 'kyo-jsonrpc/test'` green; `sbt 'kyo-browser/Test/compile'` green.

### Phase B — Wire-message hoist (Axes 9 + 1 partial)

Scope:
- Hoist `JsonRpcEnvelope.Request` → top-level `JsonRpcRequest` (separate file, extends `JsonRpcEnvelope`).
- Hoist `JsonRpcEnvelope.Response` → top-level `JsonRpcResponse` (extends `JsonRpcEnvelope`). Undoes Phase 02's merge: the `success`/`failure` factories live on `JsonRpcResponse` companion again, with the 4-field shape (including `extras`). Field types use `JsonRpcId` (top-level, hoisted in this phase too).
- Hoist `JsonRpcEnvelope.Notification` → top-level `JsonRpcNotification`.
- Hoist `JsonRpcEnvelope.Malformed` → top-level `JsonRpcMalformedMessage`.
- Hoist `JsonRpcEnvelope.Id` → top-level `JsonRpcId` (opaque type), preserving the hand-written Schema (audit A3 confirmed correct).
- Convert `enum JsonRpcEnvelope` → `sealed trait JsonRpcEnvelope` with the 4 hoisted types as subtypes.
- Update every reference site: codec, engine, gate, downstream callers.

Estimate: ~300 LoC, ~30 files. The 4 hoisted types each get their own file with focused scaladoc.

Verification: full kyo-jsonrpc test suite + kyo-jsonrpc-http + kyo-browser JVM tests green. Wire round-trip tests must still pass (Schema derivation must match the pre-hoist encoding byte-for-byte).

### Phase C — Error hierarchy (Axis 10 + A9 + A10)

**Faithful mirror of `HttpException.scala` + the realignment refinements**: operation-based trait categories, leaf-per-operation, raw structured data in every leaf, message constructed inside the leaf from typed fields. All types in this section are **top-level** `package kyo`. The only nesting is single-consumer auxiliary enums under their consuming leaf (`JsonRpcParseError.Reason`, `JsonRpcInvalidParamsError.{ParamError, Problem}`, `JsonRpcInternalError.Operation`).

Reference: `kyo-http/shared/src/main/scala/kyo/HttpException.scala:28-348`. Design exploration: `research/exception-hierarchy-design.md` and `research/exception-hierarchy-q3q4.md`.

#### Core rules

1. **String not Text** for all `message` parameters and fields.
2. **No `detail: String` parameters on leaves.** Callers pass typed structured data; the leaf's case-class body constructs the message via `s"..."` interpolation. The only String parameters are ones that ARE the data (e.g. `method: String`, `setting: String`, `label: String`).
3. **Operation-based trait categories** at the subcategory level. Leaves single-inherit one trait (multi-inheritance is the affordance, not the norm).
4. **Frame captured on every leaf** via `(using Frame)` — the Frame is what `KyoException` uses for stack location.
5. **All top-level** ; subcategories are top-level traits, leaves are top-level case classes. Only single-consumer auxiliary enums nest under their consuming leaf's companion.

#### Operation traits

```scala
package kyo

/** Base. All JSON-RPC errors carry a wire code, message, optional data, and a chain cause. */
sealed abstract class JsonRpcError(val code: Int, val message: String, val data: Maybe[Structure.Value], cause: String | Throwable = "")(using Frame)
    extends KyoException(message, cause)

object JsonRpcError:
    /** Wire decoder: maps known codes back to specific leaves; unknown codes become `JsonRpcCustomError`. */
    def fromWire(code: Int, message: String, data: Maybe[Structure.Value])(using Frame): JsonRpcError = ...

    /** Hand-rolled Schema projecting any JsonRpcError to its wire triple `(code, message, data)`. */
    given Schema[JsonRpcError] = ...
end JsonRpcError

/** Operation marker: error surfaced during JSON-text parsing. */
sealed trait JsonRpcParseFailure extends JsonRpcError

/** Operation marker: error surfaced during method-routing / param-validation. */
sealed trait JsonRpcDispatchFailure extends JsonRpcError

/** Operation marker: error surfaced during handler-body execution (or in supporting operations
  * like response-encode, transport, lifecycle, configuration ; see the specific leaves below). */
sealed trait JsonRpcExecutionFailure extends JsonRpcError

/** Operation marker: user-domain error from a handler. */
sealed trait JsonRpcApplicationFailure extends JsonRpcError
```

#### Decode-side leaves (parse-failure category)

```scala
case class JsonRpcParseError(input: String, offset: Int, reason: JsonRpcParseError.Reason)(using Frame)
    extends JsonRpcError(
        code = -32700,
        message = s"""Parse error at offset $offset: ${reason.describe}.
                     |
                     |  Input excerpt: ${input.take(80)}${if input.length > 80 then "..." else ""}""".stripMargin,
        data = Absent
    ) with JsonRpcParseFailure
object JsonRpcParseError:
    enum Reason:
        case UnexpectedEof
        case UnexpectedChar(c: Char, expected: String)
        case InvalidEscape(seq: String)
        case NumberOutOfRange(value: String)
        case TrailingContent
        def describe: String = this match
            case UnexpectedEof                  => "unexpected end of input"
            case UnexpectedChar(c, exp)         => s"unexpected '$c', expected $exp"
            case InvalidEscape(seq)             => s"invalid escape sequence \\$seq"
            case NumberOutOfRange(v)            => s"number $v out of range"
            case TrailingContent                => "trailing content after JSON value"

case class JsonRpcInvalidRequestError(received: Structure.Value, missingFields: Chunk[String])(using Frame)
    extends JsonRpcError(
        code = -32600,
        message = s"""Invalid Request.
                     |
                     |  Missing required fields: ${missingFields.mkString(", ")}""".stripMargin,
        data = Present(received)
    ) with JsonRpcParseFailure
```

#### Dispatch-side leaves

```scala
case class JsonRpcMethodNotFoundError(method: String, available: Chunk[String])(using Frame)
    extends JsonRpcError(
        code = -32601,
        message = s"""Method not found: '$method'.
                     |
                     |  Available methods: ${available.mkString(", ")}""".stripMargin,
        data = Absent
    ) with JsonRpcDispatchFailure

case class JsonRpcInvalidParamsError(method: String, received: Maybe[Structure.Value], errors: Chunk[JsonRpcInvalidParamsError.ParamError])(using Frame)
    extends JsonRpcError(
        code = -32602,
        message = s"""Invalid params for '$method'.
                     |
                     |  Errors: ${errors.map(_.describe).mkString("; ")}""".stripMargin,
        data = received
    ) with JsonRpcDispatchFailure
object JsonRpcInvalidParamsError:
    case class ParamError(field: String, problem: Problem):
        def describe: String = s"'$field': ${problem.describe}"
    enum Problem:
        case Missing
        case TypeMismatch(expected: String, received: String)
        case ConstraintViolation(constraint: String)
        def describe: String = this match
            case Missing                          => "missing required field"
            case TypeMismatch(expected, received) => s"expected $expected, got $received"
            case ConstraintViolation(constraint)  => s"constraint violated: $constraint"
```

#### Execution-side leaves (4 specialized + 1 catchall)

Replaces today's overloaded `JsonRpcError.internalError(...)` (used across 4 distinct operational contexts with `CdpBackend.scala:65-71` disambiguating via `err.message` prefix matching). Per the audit, each context gets its own leaf with typed fields.

```scala
case class JsonRpcConfigurationError(setting: String, reason: String)(using Frame)
    extends JsonRpcError(
        code = -32603,
        message = s"Configuration error: '$setting' ; $reason",
        data = Absent
    ) with JsonRpcExecutionFailure

case class JsonRpcLifecycleError(stage: JsonRpcLifecycleError.Stage)(using Frame)
    extends JsonRpcError(
        code = -32603,
        message = s"Lifecycle error: ${stage.describe}",
        data = Absent
    ) with JsonRpcExecutionFailure
object JsonRpcLifecycleError:
    enum Stage:
        case Init, Bind, Connect, Drain, Close
        def describe: String = this.toString.toLowerCase

case class JsonRpcTransportError(detail: String, cause: Throwable)(using Frame)
    extends JsonRpcError(
        code = -32603,
        message = s"Transport error: $detail (${cause.getMessage})",
        data = Absent,
        cause = cause
    ) with JsonRpcExecutionFailure

case class JsonRpcHandlerPanicError(method: String, cause: Throwable)(using Frame)
    extends JsonRpcError(
        code = -32603,
        message = s"Handler '$method' panicked: ${cause.getMessage}",
        data = Absent,
        cause = cause
    ) with JsonRpcExecutionFailure

/** Residual catchall for internal errors that don't fit the specialized leaves above
  * (e.g. response-decode failures that the engine raises to its own caller). */
case class JsonRpcInternalError(operation: JsonRpcInternalError.Operation, cause: Throwable)(using Frame)
    extends JsonRpcError(
        code = -32603,
        message = s"Internal error during ${operation.describe}: ${cause.getMessage}",
        data = Absent,
        cause = cause
    ) with JsonRpcExecutionFailure
object JsonRpcInternalError:
    enum Operation:
        case DecodeResult, EncodeResponse, Other
        def describe: String = this match
            case DecodeResult   => "result decode"
            case EncodeResponse => "response encode"
            case Other          => "internal operation"

/** Implementation-defined server error in the reserved -32099..-32000 range per JSON-RPC 2.0. */
case class JsonRpcImplementationError private (override val code: Int, label: String, data: Maybe[Structure.Value])(using Frame)
    extends JsonRpcError(
        code = code,
        message = s"Server error ($code): $label",
        data = data
    ) with JsonRpcExecutionFailure
object JsonRpcImplementationError:
    def apply(code: Int, label: String, data: Maybe[Structure.Value] = Absent)(using Frame): JsonRpcImplementationError =
        require(code >= -32099 && code <= -32000, s"JsonRpcImplementationError code must be in [-32099, -32000], got $code")
        new JsonRpcImplementationError(code, label, data)
```

#### Application leaves (open hierarchy)

```scala
/** User-domain errors with caller-defined codes outside the JSON-RPC spec range.
  *
  * Non-sealed: callers extend this with their own typed errors:
  * {{{
  * case class Unauthorized()(using Frame) extends JsonRpcApplicationError(401, "Unauthorized")
  * case class NotFound(resource: String)(using Frame) extends JsonRpcApplicationError(404, s"Not found: $resource")
  * }}}
  */
abstract class JsonRpcApplicationError(code: Int, message: String, data: Maybe[Structure.Value] = Absent, cause: String | Throwable = "")(using Frame)
    extends JsonRpcError(code, message, data, cause) with JsonRpcApplicationFailure

/** Catchall application error for callers that don't want a typed subclass. */
case class JsonRpcCustomError(override val code: Int, label: String, data: Maybe[Structure.Value] = Absent)(using Frame)
    extends JsonRpcApplicationError(code, s"Application error ($code): $label", data)
```

#### Type inventory

**5 traits**: `JsonRpcError` (sealed abstract base), `JsonRpcParseFailure`, `JsonRpcDispatchFailure`, `JsonRpcExecutionFailure`, `JsonRpcApplicationFailure`.

**11 case-class leaves**: `JsonRpcParseError`, `JsonRpcInvalidRequestError`, `JsonRpcMethodNotFoundError`, `JsonRpcInvalidParamsError`, `JsonRpcConfigurationError`, `JsonRpcLifecycleError`, `JsonRpcTransportError`, `JsonRpcHandlerPanicError`, `JsonRpcInternalError`, `JsonRpcImplementationError`, `JsonRpcCustomError`.

**1 open abstract class**: `JsonRpcApplicationError` (for user-defined typed subclasses).

**3 nested auxiliary enums** + 1 nested case class (single-consumer aux):
- `JsonRpcParseError.Reason`
- `JsonRpcInvalidParamsError.ParamError` + `JsonRpcInvalidParamsError.Problem`
- `JsonRpcLifecycleError.Stage`
- `JsonRpcInternalError.Operation`

#### Caller migration

Most current `JsonRpcError(code, message, data)` and `JsonRpcError.internalError(detail)` construction sites get a specific leaf with typed fields:

| Before | After |
|---|---|
| `JsonRpcError(-32601, "Method not found", Absent)` | `JsonRpcMethodNotFoundError(methodName, availableMethods)` |
| `JsonRpcError(-32602, s"Invalid params: $field missing", Absent)` | `JsonRpcInvalidParamsError(method, received, Chunk(ParamError(field, Problem.Missing)))` |
| `JsonRpcError.internalError(s"Transport setup: $msg")` | `JsonRpcTransportError(msg, cause)` |
| `JsonRpcError.internalError(s"Configuration error: $msg")` | `JsonRpcConfigurationError(setting, reason)` |
| `JsonRpcError.internalError(s"Handler panic: $msg")` | `JsonRpcHandlerPanicError(method, cause)` |
| `JsonRpcError(409, "Conflict", Absent)` | `JsonRpcCustomError(409, "Conflict")` (or user subclass) |

Pattern-matching at consumer sites:

| Before | After |
|---|---|
| `case JsonRpcError(-32601, _, _) =>` | `case _: JsonRpcMethodNotFoundError =>` |
| `case e: JsonRpcError if e.message.startsWith("Transport") =>` | `case _: JsonRpcTransportError =>` |
| `case e: JsonRpcError if e.code >= 400 =>` | `case e: JsonRpcApplicationError if e.code >= 400 =>` |
| Category match (new) | `case _: JsonRpcDispatchFailure =>` (matches MethodNotFound OR InvalidParams) |

#### Coordination

- **`CdpBackend.scala:65-71` string-prefix-match** migrates to a typed pattern match on the specialized execution leaves.
- **kyo-ai-harness/MCP** (if applicable) ; same migration.
- **Out-of-spec `-32602` reuse at `JsonRpcEndpointImpl.scala:107, 203, 518, 534`** (currently raising `InvalidParams` for response-decode failures) ; these 4 sites get reclassified to `JsonRpcInternalError(Operation.DecodeResult, cause)` since they're caller-side result-shape failures, not peer-side request-param failures.

Estimate: ~550 LoC, ~50 files. Larger than the earlier flat-version estimate because of the additional specialized leaves + their typed enums, but recovers two pieces of data the current API discards (handler-panic method name + transport-error cause).

Verification: full cross-module compile + JVM tests green.

### Phase D — Route restructure (Axes 3, 4, 5, 6)

Scope:
- Change `sealed trait JsonRpcRoute[+S]` to `sealed trait JsonRpcRoute[In, Out, +E]`. Remove free `S` type parameter.
- Handler signature: `(In, Context) => Out < (Async & Abort[E | JsonRpcError | JsonRpcResponse.Halt])` — fixed effect row mirroring kyo-http.
- Add `JsonRpcResponse.Halt` short-circuit pattern (mirrors `HttpResponse.Halt`). Caller does `Abort.fail(JsonRpcResponse.Halt(myResponse))` to short-circuit without invoking subsequent gates/filters.
- Add `.error[E](code: Int, message: String)` declaration on `JsonRpcRoute` so user's typed domain errors map to `JsonRpcError.ApplicationError` instances with the declared code.
- Add `.errors[E1, E2, ...](mappings: PartialFunction[E, JsonRpcError])` for multi-error mapping.
- Engine boundary: `JsonRpcHandler.init` accepts `Seq[JsonRpcRoute[?, ?, ?]]` (existential, like kyo-http's `HttpHandler[?, ?, ?]*`).

Estimate: ~600 LoC, ~50 files. The signature change ripples through every route construction site in kyo-jsonrpc tests + kyo-browser CdpBackend + kyo-jsonrpc-http examples.

Verification: full cross-module JVM tests green. Special attention: routes that used `Any` (no effects) and routes that used `Async & Abort[X]` need targeted regression tests.

### Phase E — Entry-point opaque type (Axes 2, 16, 17, A4)

Scope:
- Convert `final class JsonRpcHandler private[kyo] (impl: ...)` → `opaque type JsonRpcHandler = JsonRpcHandler.Unsafe`.
- Move instance methods to `extension (self: JsonRpcHandler)` block in the companion.
- Introduce `JsonRpcHandler.Unsafe` as the underlying type, mirroring `HttpServer.Unsafe`.
- Bridge safe ↔ unsafe via `Sync.Unsafe.defer` boundaries.
- Drop the `close` overload ambiguity workaround documented in scaladoc (audit A4) — opaque-type extension methods resolve cleanly in Scala 3.
- Move `JsonRpcRoute.dispatch` (currently leaks an internal helper to public, audit A1) into `kyo.internal.engine` + add a `JsonRpcHandler.Unsafe.dispatch` wrapper.

Estimate: ~250 LoC, ~10 files. Touches every caller that constructs `JsonRpcHandler` (or formerly `JsonRpcEndpoint`) — but the syntactic shape is unchanged (`JsonRpcHandler.init(...)` still works); only the implementation type changes.

Verification: JVM tests green; check that scaladoc examples (and any doctests) compile.

### Phase F — Lifecycle variants + filter hoist (Axes 7, 11, A2, A5)

Scope:
- Add `JsonRpcHandler.initWith[A, S](transport, routes, config)(f: JsonRpcHandler => A < S): A < (S & Async & Scope)`.
- Add `JsonRpcHandler.initUnscoped(transport, routes, config): JsonRpcHandler < Async`.
- Add `JsonRpcHandler.initUnscopedWith[A, S](transport, routes, config)(f): A < (S & Async)`.
- Add varargs convenience: `JsonRpcHandler.init(transport)(routes*)`, `JsonRpcHandler.init(transport, config)(routes*)`.
- Add `Config.lsp` / `Config.mcp` presets that wire matched cancellation+progress in one step (audit A5).
- Widen `JsonRpcMessageGate.Decision.Reject` from `JsonRpcError` to `JsonRpcResponse` so gates can construct full responses on rejection (audit A2).
- Hoist `JsonRpcMessageGate` to top-level with `JsonRpcMessageGate.{noop, server, client, andThen, ...}` companion namespaces (mirror `HttpFilter` layout).

Estimate: ~300 LoC, ~10 files. Lifecycle variants are mechanical; the filter hoist + namespaces is the substantive part.

Verification: JVM tests green; add new tests for the lifecycle variants + the `Config.lsp` / `.mcp` presets.

### Phase G — Cleanup nested types + remaining audit items (Axes 1 fully, 12, 13, 14, A7)

Scope:
- Hoist remaining nested-under-companion types per the locked naming decisions:
  - `JsonRpcCancellationPolicy`, `JsonRpcProgressPolicy`, `JsonRpcUnknownMethodPolicy`, `JsonRpcIdStrategy`, `JsonRpcExtrasEncoder` (currently under `JsonRpcHandler` after Phase 03)
  - `JsonRpcFramer`, `JsonRpcWireTransport` (currently under `JsonRpcTransport` after Phase 03)
- Update all references in main + tests + downstream callers.
- Add `+Out` covariance to `Pending` (audit A7).
- Add `JsonRpcCodec.default` preset (Axis 13).
- Add `JsonRpcMessageGate.noop` (Axis 13).
- Rename `JsonRpcRoute.apply` → `JsonRpcRoute.request` for symmetry with `JsonRpcRoute.notification` (Axis 12).
- Optional: extract `kyo.internal.dispatch` subpackage from `kyo.internal.engine` for clearer boundaries (Axis 14, optional).

Estimate: ~350 LoC, ~30 files.

Verification: JVM tests green.

### Phase H — Final cross-platform green gate

Scope: full kyo-jsonrpc + kyo-jsonrpc-http + kyo-browser test suites on JVM, JS, Native. No source changes.

Estimate: 0 LoC, ~45 min wall-clock (per Phase 07 baseline).

Verification: each platform's log ends in `[success]`; zero failures.

## Total

~2150 LoC across 8 phases, ~80 files (deduped). Estimated wall-clock: 4-6 hours of impl agent time for the substantive phases (D, C, B) plus shorter time for A, E, F, G. Phase H is the gate.

## Cross-module coordination

**Three downstream callers** must be updated in tandem with each phase:
- `kyo-jsonrpc-http` (sibling module) — typically 1-3 files per phase.
- `kyo-browser/CdpBackend` + its tests — heaviest impact from Phase C (error hierarchy pattern matches) and Phase D (route signature changes).
- `kyo-ai-harness/MCP` (if it exists in this worktree) — to verify.

A single commit per phase touches all 3 callers, ensuring no intermediate broken state.

## Risks

1. **Phase C is the biggest break**: every caller constructing or matching `JsonRpcError` changes. Grep all `JsonRpcError(` and `case _: JsonRpcError` sites before starting.
2. **Phase D's `S → E` change** is conceptually the deepest: the engine's internal dispatch must accept routes with their declared `E` and convert `Abort[E]` to the right wire code via the `.error` declarations. Risk of subtle behavioural drift if the abort-channel handling isn't exact.
3. **Phase E's opaque-type conversion** may break `.equals` / `.hashCode` semantics if any caller relies on identity. Audit usage.
4. **Schema byte-format preservation** through Phase B's wire-type hoist: the encoded JSON must match the pre-hoist encoding bit-for-bit. Wire round-trip tests are the gate.

## Verification approach

Per-phase: JVM compile + targeted test suite (just kyo-jsonrpc).
Phase boundaries that cross modules (C, D): JVM compile of kyo-jsonrpc + kyo-jsonrpc-http + kyo-browser; targeted tests in each.
Final (Phase H): full cross-platform green on all 3 modules × 3 platforms.

## Resolutions (locked, modeled on kyo-http exactly)

All decisions resolved by matching kyo-http's pattern verbatim. No menu options below — these are the locked answers.

### 1. Hoist the 9 Phase-03 nested types — YES

Confirmed: hoist `MessageGate`, `IdStrategy`, `UnknownMethodPolicy`, `CancellationPolicy`, `ProgressPolicy`, `ExtrasEncoder`, `Framer`, `WireTransport` back to top-level with `JsonRpc` prefix. Rationale: kyo-http keeps every analogous type top-level (`HttpFilter`, `HttpStatus`, `HttpMethod`, `HttpTransportConfig`, etc. — none nested under owners). My D6 reversal of D5 was wrong; the audit confirms D5's `RENAME-WITH-PREFIX-KEEP-PUBLIC` verdict was correct.

### 2. `JsonRpcError` leaf count — 7 leaves, 3 subcategories

Locked structure already in Phase C above. Mirrors `HttpException` shape (sealed base → sealed subcategories → leaves). Matches JSON-RPC 2.0 spec coverage exactly:

- `Decode` (4 leaves for the 4 decode-side spec codes)
- `Server` (2 leaves for the 2 server-side spec codes)
- `Application` (1 catchall leaf `CustomError` + open hierarchy for user extension)

No more, no fewer. kyo-http has more leaves (~20) because HTTP has more failure modes; JSON-RPC's spec defines exactly 5 standard codes + 1 reserved range, so 7 leaves covers the spec.

### 3. `Halt` placement — `JsonRpcResponse.Halt`

Locked. Mirrors `HttpResponse.Halt` at `kyo-http/shared/src/main/scala/kyo/HttpResponse.scala:169`:

```scala
// in JsonRpcResponse companion:
object JsonRpcResponse:
    /** Short-circuit signal for routes and gates to abort request processing
      * and send a response immediately. Used with `Abort.fail(Halt(response))`
      * or the convenience `JsonRpcResponse.halt(response)`. When a route or gate
      * aborts with Halt, the framework skips remaining processing and sends the
      * wrapped response directly.
      */
    case class Halt(response: JsonRpcResponse)

    /** Convenience for short-circuiting: aborts with the given response immediately. */
    def halt(response: JsonRpcResponse)(using Frame): Nothing < Abort[Halt] =
        Abort.fail(Halt(response))
end JsonRpcResponse
```

### 4. `.error[E]` API surface — chainable inline single-error form (matches kyo-http verbatim)

Locked. Mirrors `HttpRoute.error` at `kyo-http/shared/src/main/scala/kyo/HttpRoute.scala:82`:

```scala
// in JsonRpcRoute:
inline def error[E2](using schema: Schema[E2], tag: ConcreteTag[E2])(code: Int, message: String): JsonRpcRoute[In, Out, E | E2] =
    copy(response = response.error[E2](code, message))
```

Single-error chainable form. The `E | E2` union accumulates. Users chain multiple errors:

```scala
val route = JsonRpcRoute[Req, Resp, Nothing]("doThing")(handler)
    .error[NotFound](-32001, "Not found")
    .error[Forbidden](-32002, "Forbidden")
    .error[RateLimited](-32003, "Rate limited")
// route: JsonRpcRoute[Req, Resp, NotFound | Forbidden | RateLimited]
```

No mapping-based form (kyo-http doesn't have one). No `.errors[E1, E2, ...]` multi-form.

### 5. `Decision.Reject` widening — YES, carry `JsonRpcResponse`

Locked. Mirrors kyo-http's filter-rejection pattern where `HttpResponse.Halt(response)` carries a full response. The kyo-jsonrpc gate's `Decision.Reject` widens from `JsonRpcError` to `JsonRpcResponse`:

```scala
// in JsonRpcMessageGate:
enum Decision:
    case Admit
    case Reject(response: JsonRpcResponse)   // was: Reject(error: JsonRpcError)
```

Gates that previously did `Decision.Reject(error)` migrate to `Decision.Reject(JsonRpcResponse.failure(id, error))`. Phase F includes the caller migration.

### 6. `Application` subclass openness — open (non-sealed)

Locked. `JsonRpcError.Application` is `abstract` not `sealed`, so callers can define their own subclasses:

```scala
case class Unauthorized()(using Frame) extends JsonRpcError.Application(401, "Unauthorized")
case class NotFound(resource: String)(using Frame) extends JsonRpcError.Application(404, s"Not found: $resource")
```

This is the same openness kyo-http allows for user-defined exception subtypes.

### 7. Engine boundary error type — `Async & Abort[E | JsonRpcError | JsonRpcResponse.Halt]`

Locked. Mirrors `HttpRoute.handler`'s row at `kyo-http/shared/src/main/scala/kyo/HttpRoute.scala:95`:

```scala
def handler[E2 >: E](f: HttpRequest[In] => HttpResponse[Out] < (Async & Abort[E2 | HttpResponse.Halt]))
```

In kyo-jsonrpc:

```scala
def handler[E2 >: E](f: (JsonRpcRequest[In], Context) => JsonRpcResponse[Out] < (Async & Abort[E2 | JsonRpcResponse.Halt | JsonRpcError]))
```

The `JsonRpcError` in the row is the spec-defined error layer (the engine knows how to wire-encode it). `E2` is the user's typed domain errors mapped via `.error`. `JsonRpcResponse.Halt` is the short-circuit channel.

## Execution path — Per-phase user-approved increments

Path 2 from the previous version, now confirmed. I dispatch one phase impl at a time, you sign off before the next. Per-phase commit boundary keeps the realignment reversible.

Start with **Phase A** (foundation renames — lowest risk, fully reversible, sets up names that the subsequent phases consume).
