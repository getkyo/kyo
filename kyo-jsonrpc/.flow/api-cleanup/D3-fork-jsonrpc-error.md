# D3. Fork 3 Resolution: `JsonRpcError` shape

Decision document for Fork 3 of the kyo-jsonrpc cleanup plan (`C-cleanup-plan.md` §8, §11 Fork 3, `A4-naming-and-nesting.md` §7). Question: should `JsonRpcError` stay a flat case class with named-code constants, or be refactored into a sealed hierarchy mirroring `kyo-http`'s `HttpException`?

---

## 1. Current shape (verified)

`kyo-jsonrpc/shared/src/main/scala/kyo/JsonRpcError.scala:11`:

```scala
case class JsonRpcError(code: Int, message: String, data: Maybe[Structure.Value]) derives Schema, CanEqual
```

Three fields. `Schema` is derived (`JsonRpcError.scala:11`), so encoding/decoding follows the macro-derived case-class layout. `CanEqual` is derived too.

Companion (`JsonRpcError.scala:13-41`) ships:

- 11 named constants for the JSON-RPC 2.0 standard codes plus LSP error codes: `ParseError` (-32700), `InvalidRequest` (-32600), `MethodNotFound` (-32601), `InvalidParams` (-32602), `InternalError` (-32603), `ServerNotInitialized` (-32002), `UnknownErrorCode` (-32001), `RequestCancelled` (-32800), `ContentModified` (-32801), `ServerCancelled` (-32802), `RequestFailed` (-32803) (`JsonRpcError.scala:14-24`).
- 5 parametric helpers: `methodNotFound(name)` (`:26`), `invalidRequest(reason)` (`:29`), `invalidParams(reason)` (`:32`), `internalError(cause, data)` (`:35`), `cancelled(reason)` (`:38`).

The constants cover the spec's reserved range (-32768 to -32000) plus the LSP RPC subrange (-32800 to -32899). The helpers attach `data: Maybe[Structure.Value]` for the cases where a human-readable reason or structured detail belongs.

## 2. Wire interaction

`JsonRpcCodec` (`JsonRpcCodec.scala:9-10`) is the codec extension point. The error appears in the codec's effect row only as an abort channel (`Abort[JsonRpcError]` at `JsonRpcCodec.scala:10`), not as part of the wire envelope schema. The wire envelope holds the error as a `JsonRpcEnvelope.Response` field (`JsonRpcEnvelope.scala:19`); the value-side decoding happens through `Structure.decode[JsonRpcError](ev).getOrElse(JsonRpcError.InvalidRequest)` (`JsonRpcCodecImpl.scala:108`, again at `:233`). That call is a straight derived-Schema decode of the `{code, message, data}` tuple. The encoding direction is symmetric: the derived `Schema` writes the three fields back out.

The codec also produces `JsonRpcError` values internally on encode failures (`JsonRpcCodecImpl.scala:51, :151, :166, :172, :174`), all via `JsonRpcError.internalError` or `JsonRpcError.invalidRequest`. The error is constructed in domain code, serialized through the derived Schema, and matched on by callers.

## 3. JSON-RPC 2.0 wire shape constraints

The on-wire representation is fixed by the spec: `{"code": <int>, "message": <string>, "data"?: <any>}`. Code ranges:

- -32700: parse error
- -32600: invalid request
- -32601: method not found
- -32602: invalid params
- -32603: internal error
- -32000 to -32099: server-reserved (implementation-defined per server)
- -32800 to -32899: LSP-specific extensions (`JsonRpcError.scala:21-24` covers four of these)
- All other ints: application-defined

The integer code is the wire-level discriminator. Crucially, it is open. Two open dimensions force the design:

1. **Server-reserved range** (-32000 to -32099). Every server that opts into JSON-RPC is allowed to mint its own codes here. LSP, MCP, and CDP all do.
2. **Application range** (any unreserved int). Application protocol authors can pick any int outside the reserved bands.

Sealed Scala enums cannot express this without a `Custom(code: Int, ...)` escape hatch, which already breaks the exhaustive-match guarantee that motivates a sealed hierarchy in the first place.

## 4. `HttpException` as template (verified)

`kyo-http/shared/src/main/scala/kyo/HttpException.scala:28-348` defines a 4-level tree: root `HttpException` (`:28`), five categories (`HttpConnectionException` `:49`, `HttpRequestException` `:90`, `HttpServerException` `:146`, `HttpDecodeException` `:191`, `HttpWebSocketException` `:318`), and ~18 leaf case classes. Every leaf is a `case class … (using Frame) extends <Category>(message, cause)`.

Two structural facts that disqualify the template for `JsonRpcError`:

1. **`HttpException extends KyoException`** (`HttpException.scala:29`), which is a `Throwable`. HTTP errors are exceptions that flow on both `Abort[HttpException]` (`HttpClient.scala:81`) and through the JVM exception machinery. JSON-RPC errors travel as values on `Abort[JsonRpcError]` only (`A4 §7`); they are not Throwables.
2. **HTTP errors do not appear on the wire**. The status code does (`HttpStatus`, `HttpStatus.scala:25`), but the exception itself is purely server-internal. `kyo-http` does not need to round-trip an `HttpException` through a serialized envelope. `JsonRpcError` must round-trip. The codec at `JsonRpcCodecImpl.scala:108` decodes one off the wire, and the engine encodes one back onto the wire as the `Response.error` field.

`HttpStatus` is the closer wire analogue. It is `sealed abstract class HttpStatus` with case-object subclasses (`A4 §6` flags this), but `HttpStatus` is keyed by an open `Int` too: `HttpStatus.scala:25` ships named statuses for the standard codes (e.g., `HttpStatus.NotFound` at `HttpStatus.scala:112` per `A1 §7`). And yet `HttpStatus` is sealed, with a named-case-object surface for the standard codes. So why not the same for `JsonRpcError`?

The difference: `HttpStatus` is one of two participating fields (status + body); the body carries semantic detail. `JsonRpcError` is the whole error surface (code + message + data); the data field is structured (`Maybe[Structure.Value]`) and per-error-class shapes vary. Modeling per-leaf data shapes in a sealed hierarchy would need either (a) every leaf to carry its own `data: SomeType` field with a custom Schema or (b) every leaf to share the generic `data: Maybe[Structure.Value]`, in which case the leaves only differ by code constant, which is exactly what the named `val`s already encode.

## 5. Pros and cons of a sealed hierarchy for `JsonRpcError`

PRO (typed exhaustive matching). With `sealed trait JsonRpcError` and leaves `ParseError`, `InvalidRequest`, etc., a user handler can `match { case ParseError => ... case MethodNotFound(name) => ... }` and the compiler checks coverage. With the flat shape, callers match on `code` (`if e.code == -32601 then ...`) or compare to the named constants.

PRO (per-leaf data fields). `MethodNotFound(name: String)` is more informative than `JsonRpcError(-32601, "Method not found: $name", Absent)`. Per-leaf typed `data` payloads (e.g. `InvalidParams(reason: String, paramPath: Maybe[String])`) would be sealed-hierarchy-natural.

PRO (kyo-http template parity). Visual alignment with `HttpException` is a real aesthetic win.

CON (open code space). JSON-RPC reserves -32000 to -32099 for server-defined codes and leaves the application range fully open. A sealed hierarchy needs `ServerError(code: Int, message: String, data: Maybe[Structure.Value])` and `ApplicationError(code: Int, ...)` escape hatches. The moment those exist, exhaustive matching is no longer load-bearing (every consumer must still handle the escape case), so the PRO collapses partially.

CON (wire round-trip). The derived `Schema, CanEqual` on the flat case class (`JsonRpcError.scala:11`) gives a free `{code, message, data}` codec. A sealed hierarchy with leaves discriminated by an `Int` (not a string tag) requires a hand-written `Schema` that reads `code` first, then dispatches to the right leaf decoder, then validates that the leaf's hard-coded code matches. This is real work and a permanent maintenance tax: every new leaf is two edits (case class + schema arm). See `kyo-jsonrpc/shared/src/main/scala/kyo/JsonRpcId.scala:14` for an example of a hand-written union Schema (num-or-string ID), which is non-trivial.

CON (consumer churn). `JsonRpcError(-32602, "Invalid params", Absent)` appears in test code (`kyo-browser/shared/src/test/scala/kyo/internal/CdpClientDecoderTest.scala:65, :133`). The flat constructor is part of the test vocabulary; a sealed hierarchy breaks every such site, and the LoC count across the repo is ~1100 references (`kyo-browser` test scope alone).

CON (escape hatch defeats the goal). A sealed hierarchy with ~11 named leaves plus `ServerError` plus `ApplicationError` (or `Other`) gives the user *no* exhaustive-match win, because the `Other` arm is mandatory. The user is left in the same position as the flat shape: pattern-match on the code, with a default arm.

CON (helper churn). The five helpers (`JsonRpcError.scala:26-40`) currently return `JsonRpcError`. In a sealed model they would return specific leaf types, which is fine, but then `internalError`'s polymorphism over `data` (any `Structure.Value`) is awkward to encode in a typed leaf.

## 6. Proposal A: keep flat, polish constants and scaladoc

Minimal change. The existing surface at `JsonRpcError.scala:14-40` already covers all 11 standard codes. Two additions would round it out:

- `JsonRpcError.serverError(code: Int, message: String, data: Maybe[Structure.Value] = Absent)(using Frame)` with a `require(code >= -32099 && code <= -32000, ...)` guard.
- `JsonRpcError.applicationError(code: Int, message: String, data: Maybe[Structure.Value] = Absent)(using Frame)` for the open application range, with a guard `require(code > -32000 || code < -32768, ...)` to refuse the reserved bands.

These two helpers document the intended code-range conventions in the API itself, not just in scaladoc. They preserve the case class as the canonical type.

The scaladoc block on the case class should:

- Cite JSON-RPC 2.0 §5.1 and the LSP §3.16 error-code conventions.
- Explain that the `Int` code is the wire discriminator and the open extension point.
- Point to `JsonRpcError.ParseError` etc. for the standard codes and to `JsonRpcError.serverError` / `applicationError` for custom ranges.

LoC delta: +~10 lines (two helpers + scaladoc).

## 7. Proposal B: sealed hierarchy

Draft sketch:

```scala
sealed trait JsonRpcError derives CanEqual:
    def code: Int
    def message: String
    def data: Maybe[Structure.Value]

object JsonRpcError:
    case object ParseError                                                                                 extends JsonRpcError { val code = -32700; val message = "Parse error";    val data = Absent }
    case class  InvalidRequest(reason: Maybe[String] = Absent)                                              extends JsonRpcError { val code = -32600; ... }
    case class  MethodNotFound(name: String)                                                                extends JsonRpcError { val code = -32601; val message = s"Method not found: $name"; val data = Absent }
    case class  InvalidParams(reason: String, extras: Maybe[Structure.Value] = Absent)                      extends JsonRpcError { val code = -32602; ... }
    case class  InternalError(cause: String, data: Maybe[Structure.Value] = Absent)                         extends JsonRpcError { val code = -32603 }
    case object ServerNotInitialized                                                                       extends JsonRpcError { val code = -32002; ... }
    case object UnknownErrorCode                                                                           extends JsonRpcError { val code = -32001; ... }
    case object RequestCancelled                                                                           extends JsonRpcError { val code = -32800; ... }
    case object ContentModified                                                                            extends JsonRpcError { val code = -32801; ... }
    case object ServerCancelled                                                                            extends JsonRpcError { val code = -32802; ... }
    case object RequestFailed                                                                              extends JsonRpcError { val code = -32803; ... }
    case class  ServerError(code: Int, message: String, data: Maybe[Structure.Value] = Absent)             extends JsonRpcError
    case class  ApplicationError(code: Int, message: String, data: Maybe[Structure.Value] = Absent)        extends JsonRpcError

    given Schema[JsonRpcError] = ... // hand-written: read {code, message, data}; dispatch on code; fall back to ApplicationError
```

LoC estimate: the case-class declarations are ~25 lines, the hand-written `Schema` is ~40-60 lines (one decode arm per code constant), tests for the Schema round-trip add ~30 lines. Total ~100-120 new lines plus the deletion of the current flat case class. Add ~1100 caller updates across `kyo-browser`, `kyo-jsonrpc-http`, internal engine code (which already uses helpers, so most sites are unchanged, but every direct constructor call breaks).

## 8. Verdict

**VERDICT**: KEEP-FLAT-WITH-CONSTANTS.

**Rationale**. The flat case class is wire-faithful: the JSON-RPC 2.0 error structure is literally `{code: Int, message: String, data: any}`, and `case class JsonRpcError(code, message, data) derives Schema` is a direct Scala encoding of that wire grammar. A sealed hierarchy would fight the grammar: it would force a hand-written `Schema` that reads the int code, dispatches to one of ~13 arms, and falls back to a generic `ApplicationError` / `ServerError` for the open ranges. That fall-back arm is mandatory because the spec leaves -32000 to -32099 server-defined and the rest application-defined. The fall-back arm defeats the only PRO that justifies the refactor (compile-time exhaustive matching), because every consumer must still handle the open arm. The named `val`-constant surface already at `JsonRpcError.scala:14-24` gives callers the same convenience for the 11 standard codes that case-object leaves would give, with zero additional codec machinery, zero Schema maintenance tax, and zero caller churn. The `HttpException` template (`HttpException.scala:28`) does not apply because (a) `HttpException` extends `KyoException` and inherits Throwable semantics that `JsonRpcError` deliberately rejects (`A4 §7`), and (b) `HttpException` never round-trips on a wire; `JsonRpcError` round-trips on every error response (`JsonRpcCodecImpl.scala:108, :233`). The closer wire-bound parallel inside kyo-http is `HttpStatus` (sealed over Int), but `HttpStatus` carries only the discriminator, while `JsonRpcError` carries the discriminator plus a polymorphic `data: Maybe[Structure.Value]` payload that the sealed-hierarchy form cannot type-specialize without exploding the API surface. The existing surface already ships 11 named constants and 5 helpers, which is the workable middle ground: spec-correct on the wire, ergonomic for the common cases, open for the custom-code ranges. The single addition worth making is two range-guarded helpers (`serverError`, `applicationError`) plus a scaladoc block on the case class citing the spec and pointing at the constants. This polishes the surface without changing the type.

**Migration cost** (KEEP-FLAT). +~10 LoC in `JsonRpcError.scala` (two helpers + scaladoc). Zero consumer impact. Zero codec changes. Zero test changes.

**Migration cost** (rejected SEALED option, for completeness). +~100 LoC new types, +~40-60 LoC hand-written Schema, ~1100 reference updates in `kyo-browser` (most via helpers; ~20-30 sites use the bare constructor), plus codec internals at `JsonRpcCodecImpl.scala:108, :233` migrating from `Structure.decode[JsonRpcError]` to a sealed-aware decoder. Test churn across `JsonRpcErrorTest.scala` plus every consumer-side `Abort.fail(JsonRpcError(-32602, ...))` site (`kyo-browser/.../CdpClientDecoderTest.scala:65, :133`). The cost is non-trivial and the deliverable (exhaustive matching) is partial because of the mandatory `ApplicationError` arm.

**Risk** (KEEP-FLAT). Negligible. The polish helpers are pure additions; the scaladoc is documentation only. No behaviour change, no wire change, no caller change.

**Risk** (rejected SEALED option). Two real risks: (a) the hand-written `Schema` is a permanent maintenance tax with one decode arm per leaf, easy to drift out of sync with the case-class list; (b) the open-extension arm (`ApplicationError`) leaks back into every consumer's match expression, so the exhaustiveness PRO does not actually land. A subtler risk: any future LSP / MCP / CDP server that mints its own `-32000 to -32099` code must either be added as a new sealed leaf (campaign tax) or fall into `ServerError` (where it is indistinguishable from any other server-minted code). The flat shape sidesteps the question entirely: every code is just an int.

---

## Recommendation

**Keep `JsonRpcError` as the flat case class.** Add two range-guarded helpers (`serverError`, `applicationError`) and a scaladoc block on the case class declaration explaining the wire-faithful rationale, the spec code ranges, and the named-constant + helper surface. Make no structural change. Reject the sealed-hierarchy refactor: it fights the JSON-RPC 2.0 wire grammar, requires a permanent codec maintenance tax, and does not deliver compile-time exhaustive matching once the mandatory open-range escape arm is in place.
