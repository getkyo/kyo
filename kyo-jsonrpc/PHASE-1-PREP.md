# Phase 1 Prep: Wire Types and Codecs

This document is the sole reference the Phase 1 implementation agent reads before writing any code. All signatures below are verbatim from the sources listed. Do not re-explore kyo-schema, kyo-prelude, or the kyo-ai-plugin branch unless a discrepancy is found; report it instead.

---

## 1. Verbatim API Signatures

### Structure.Value constructors

Source: `kyo-schema/shared/src/main/scala/kyo/Structure.scala` lines 288-318.

```scala
// All ten cases of the Value enum:
enum Value derives CanEqual, Schema:
    case Record(fields: Chunk[(String, Value)])
    case VariantCase(name: String, value: Value)
    case Sequence(elements: Chunk[Value])
    case MapEntries(entries: Chunk[(Value, Value)])
    case Str(value: String)
    case Bool(value: Boolean)
    case Integer(value: Long)
    case Decimal(value: Double)
    case BigNum(value: BigDecimal)
    case Null
```

`Record` takes a `Chunk[(String, Value)]`. Key uniqueness is NOT enforced; last-key-wins semantics on decode (same as Json.encode behavior). Do not call `.distinct` or deduplicate keys.

### Structure.encode / Structure.decode

Source: `kyo-schema/shared/src/main/scala/kyo/Structure.scala` lines 44 and 57.

```scala
def encode[A](value: A)(using schema: Schema[A], frame: Frame): Structure.Value =
    schema.toStructureValue(value)

def decode[A](value: Structure.Value)(using schema: Schema[A], frame: Frame): Result[DecodeException, A] =
    schema.fromStructureValue(value)
```

Both require an implicit `Schema[A]` and `Frame`. `Structure.decode` returns `Result[DecodeException, A]` (not `< Abort[...]`). To lift into `Abort`, use:

```scala
Structure.decode[A](v).get          // throws on failure (only inside readFn context)
Structure.decode[A](v) match { ... } // explicit pattern match
```

### Json.encode / Json.decode

Source: `kyo-schema/shared/src/main/scala/kyo/Json.scala` lines 36 and 62.

```scala
inline def encode[A](value: A)(using schema: Schema[A], frame: Frame): String

def decode[A](
    input: String,
    maxDepth: Int = DefaultMaxDepth,
    maxCollectionSize: Int = DefaultMaxCollectionSize
)(using json: Json, schema: Schema[A], frame: Frame): Result[DecodeException, A]
```

`Json.given` is in scope from `object Json`. `Json.encode` returns `String`. `Json.decode` returns `Result[DecodeException, A]`.

### Maybe API

Source: `kyo-data/shared/src/main/scala/kyo/Maybe.scala`.

```scala
opaque type Maybe[+A] >: (Absent | Present[A]) = Absent | Present[A]

// Constructors:
Present(v: A): Present[A]    // unapply also available: case Present(x) =>
Absent                        // singleton

// Extension methods on Maybe[A]:
def isEmpty: Boolean
def isDefined: Boolean
def get: A                    // throws NoSuchElementException if Absent
def getOrElse[B >: A](default: => B): B
def fold[B](ifEmpty: => B)(ifDefined: A => B): B
def map[B](f: A => B): Maybe[B]
def flatMap[B](f: A => Maybe[B]): Maybe[B]
def toOption: Option[A]

// Factory:
def Maybe.fromOption[A](opt: Option[A]): Maybe[A]
def Maybe.empty[A]: Maybe[A]   // = Absent
```

### Schema.init (hand-written Schema factory)

Source: `kyo-schema/shared/src/main/scala/kyo/Schema.scala` lines 1094-1133.

```scala
@nowarn("msg=anonymous")
inline def init[A](
    inline writeFn: (A, Writer) => Unit,
    inline readFn: Reader => A,
    // optional params with defaults (do not add to internal code per project rules):
    inline getterFn: A => Maybe[Any] = ...,
    inline setterFn: (A, Any) => A = ...,
    segments: Seq[String] = Seq.empty,
    // ... other optional fields
): Schema[A]
```

Minimum usage pattern (what kyo-ai-plugin uses verbatim):

```scala
given schema: Schema[JsonRpcId] = Schema.init[JsonRpcId](
    writeFn = (id, writer) =>
        id match
            case Num(n) => writer.long(n)
            case Str(s) => writer.string(s)
    ,
    readFn = reader => ???  // see Section 3 for the critical gotcha
)
```

### Codec.Reader methods available in this worktree

Source: `kyo-schema/shared/src/main/scala/kyo/Codec.scala` lines 63-88.

```scala
def string(): String
def long(): Long
def int(): Int
def double(): Double
def float(): Float
def boolean(): Boolean
def short(): Short
def byte(): Byte
def char(): Char
def isNil(): Boolean
def objectStart(): Int
def objectEnd(): Unit
def arrayStart(): Int
def arrayEnd(): Unit
def field(): String
def hasNextField(): Boolean
def hasNextElement(): Boolean
def skip(): Unit
// ...
```

**CRITICAL: `reader.peekType()` does NOT exist in this worktree's `Codec.scala` (194 lines, no `peekType` method, no `TokenType` enum).** The kyo-ai-plugin branch's `JsonRpcId` schema uses `reader.peekType()` which comes from a DIFFERENT branch's `Codec.scala`. The impl agent MUST NOT copy that pattern verbatim. See Section 3 for the correct alternative.

### Codec.Writer methods available

```scala
def string(value: String): Unit
def long(value: Long): Unit
def int(value: Int): Unit
def double(value: Double): Unit
def boolean(value: Boolean): Unit
def nil(): Unit
def objectStart(name: String, size: Int): Unit
def objectEnd(): Unit
def field(name: String, fieldId: Int): Unit
// ...
```

### Sync.defer

Source: `kyo-core/shared/src/main/scala/kyo/Sync.scala` line 49.

```scala
inline def defer[A, S](inline f: Safepoint ?=> A < S)(using inline frame: Frame): A < (Sync & S)
```

Use `Sync.defer { ... }` to wrap the codec method bodies.

### Abort.fail

Source: `kyo-prelude/shared/src/main/scala/kyo/Abort.scala` line 57.

```scala
inline def fail[E](inline value: E)(using inline frame: Frame): Nothing < Abort[E]
```

### Abort.run

Source: `kyo-prelude/shared/src/main/scala/kyo/Abort.scala` line 242.

```scala
def run[E](using Frame)[A, S, ER](
    v: => A < (Abort[E | ER] & S)
)(using ct: ConcreteTag[E], reduce: Reducible[Abort[ER]]): Result[E, A] < (S & reduce.SReduced)
```

### Abort.catching

Source: `kyo-prelude/shared/src/main/scala/kyo/Abort.scala` line 547.

```scala
def catching[E](using Frame)[A, S](v: => A < S)(using ct: ConcreteTag[E]): A < (Abort[E] & S)
```

Use this to convert thrown exceptions into `Abort[E]` failures.

### Chunk

```scala
Chunk[(String, Structure.Value)]     // type for Record fields
Chunk.empty[(String, Structure.Value)]
a ++ b                               // Chunk concatenation
fields.iterator.collectFirst { case (k, v) if k == name => v }
```

`Record(a ++ b)` merges two records inline; last key wins on overlap.

### Frame propagation

Every public method takes `(using Frame)`. Each `def` in `JsonRpcCodec` takes `(using Frame)`. The `Schema.init` `readFn` receives a `Reader` that carries `reader.frame`. For `TypeMismatchException` in a hand-written schema:

```scala
throw TypeMismatchException(Seq.empty, "number or string", other.toString)(using reader.frame)
```

`TypeMismatchException` is in `kyo-schema/shared/src/main/scala/kyo/SchemaException.scala` line 34.

---

## 2. File:line Anchors

| Item | Location |
|---|---|
| `Structure.Value.Record` constructor | `kyo-schema/shared/src/main/scala/kyo/Structure.scala:290` |
| `Structure.Value.Null` case | `kyo-schema/shared/src/main/scala/kyo/Structure.scala:317` |
| `Structure.encode[A]` public API | `kyo-schema/shared/src/main/scala/kyo/Structure.scala:44` |
| `Structure.decode[A]` public API | `kyo-schema/shared/src/main/scala/kyo/Structure.scala:57` |
| `private[kyo] def toStructureValue` | `kyo-schema/shared/src/main/scala/kyo/Schema.scala:1016` |
| `private[kyo] def fromStructureValue` | `kyo-schema/shared/src/main/scala/kyo/Schema.scala:1029` |
| `Schema.init[A]` factory | `kyo-schema/shared/src/main/scala/kyo/Schema.scala:1095` |
| `Json.encode[A]` inline def | `kyo-schema/shared/src/main/scala/kyo/Json.scala:36` |
| `Json.decode[A]` def | `kyo-schema/shared/src/main/scala/kyo/Json.scala:62` |
| `Sync.defer` | `kyo-core/shared/src/main/scala/kyo/Sync.scala:49` |
| `Abort.fail` | `kyo-prelude/shared/src/main/scala/kyo/Abort.scala:57` |
| `Abort.run` | `kyo-prelude/shared/src/main/scala/kyo/Abort.scala:242` |
| `TypeMismatchException` | `kyo-schema/shared/src/main/scala/kyo/SchemaException.scala:34` |
| `Maybe` opaque type | `kyo-data/shared/src/main/scala/kyo/Maybe.scala:12` |

### kyo-ai-plugin branch anchors

All at `git show kyo-ai-plugin:kyo-http/shared/src/main/scala/kyo/JsonRpc.scala`:

| Item | Line |
|---|---|
| `case class JsonRpcRequest(...)` | 17 |
| `case class JsonRpcResponse(...)` | 24 |
| `object JsonRpcResponse` with `success`/`failure` factories | 29-36 |
| `case class JsonRpcError(...)` | 39 |
| `object JsonRpcError` with constants and factories | 43-62 |
| `enum JsonRpcId derives CanEqual` | 71 |
| `object JsonRpcId` with `given schema: Schema[JsonRpcId]` | 78 |
| `Schema.init[JsonRpcId]` block (writeFn + readFn) | 80-96 |
| `JsonRpcMethod` sealed trait | 99 |
| `object JsonRpcMethod` with `apply` | 117 |

**IMPORTANT DIFFERENCES from kyo-ai-plugin to Phase 1 target:**

1. `JsonRpcRequest` drops the `jsonrpc: String` field. The codec adds it; the plain type does not carry it.
2. `JsonRpcError.data` type changes from `Maybe[Json.Value]` to `Maybe[Structure.Value]`. `Json.Value` does not exist; use `Structure.Value` throughout.
3. `JsonRpcError` constants change from `val ParseError: Int = -32700` (plain Int) to `val ParseError = JsonRpcError(-32700, "Parse error", Absent)` (full instances per DESIGN.md §15).
4. `JsonRpcId.schema`'s `readFn` uses `reader.peekType()` which does NOT compile on this branch. See Section 3 for the replacement.
5. `JsonRpcEnvelope` is a new type not present in kyo-ai-plugin.
6. `JsonRpcCodec` trait and `Strict2_0`/`Cdp` implementations are new.

---

## 3. Edge Cases and Gotchas

### 3.1 `JsonRpcId` flat schema: `peekType()` is unavailable

The kyo-ai-plugin `readFn` pattern:

```scala
readFn = reader =>
    reader.peekType() match
        case Codec.Reader.TokenType.IntegerNumber | Codec.Reader.TokenType.DecimalNumber =>
            Num(reader.long())
        case Codec.Reader.TokenType.StringToken =>
            Str(reader.string())
        case other =>
            throw TypeMismatchException(Nil, "number or string", other.toString)(using reader.frame)
```

**This does not compile in this worktree.** `Codec.Reader` has no `peekType()` method and no `TokenType` enum in `crispy-swinging-lemur`. The replacement: use `isNil()` to guard against null, then try `reader.long()` catching `TypeMismatchException`, falling back to `reader.string()`:

```scala
readFn = reader =>
    if reader.isNil() then
        throw TypeMismatchException(Seq.empty, "number or string", "null")(using reader.frame)
    else
        try Num(reader.long())
        catch case _: TypeMismatchException => Str(reader.string())
```

This works because `StructureValueReader.long()` throws `TypeMismatchException` when `currentValue` is not `Integer`. The catch is narrowed to `TypeMismatchException` to avoid swallowing panics.

**FLAT encoding is mandatory.** The auto-derived `Schema[JsonRpcId]` from `derives Schema` produces a tagged union: `{"Num": {"value": 1}}`. That is WRONG. The hand-written schema MUST produce bare `1` or `"abc"` on the wire.

### 3.2 `JsonRpcResponse` xor invariant

The raw `apply` constructor is `private[kyo]`. Consumers use only:

```scala
def success(id: JsonRpcId, result: Maybe[Structure.Value]): JsonRpcResponse =
    JsonRpcResponse(id, Present(result), Absent)     // private[kyo] apply

def failure(id: JsonRpcId, error: JsonRpcError): JsonRpcResponse =
    JsonRpcResponse(id, Absent, Present(error))      // private[kyo] apply
```

The codec's `decode` for a `Strict2_0` response MUST reject an envelope that has BOTH `result` AND `error` populated. Return `JsonRpcEnvelope.Malformed("response has both result and error", raw)`. Do NOT `Abort.fail` for this case; `decode` is `Sync` only and must return `JsonRpcEnvelope < Sync`, not `< Abort[...]`.

### 3.3 `JsonRpcEnvelope` does NOT derive Schema

`JsonRpcEnvelope derives CanEqual` only. The `Malformed` case holds a `String` reason and a raw `Structure.Value`; it cannot survive a round-trip through a schema-derived codec. Both `Strict2_0` and `Cdp` encode by case-matching on the enum and decode by parsing the JSON object fields manually.

### 3.4 `extras: Maybe[Structure.Value]` null vs absent distinction

These two are observably different on the wire:

- `extras = Absent`: no extra keys written to the output `Record`. The resulting wire JSON has no field corresponding to extras.
- `extras = Present(Structure.Value.Null)`: the codec writes a top-level key (or keys derived from the value) with an explicit JSON null value.

For `Cdp.encode`: when `extras = Present(Record(fields))`, stamp each field directly into the output record. When `extras = Present(Structure.Value.Null)`, write... nothing useful (a null-extras slot has no defined wire representation for CDP; treat as no extras for the Cdp codec or reserve for test 14's round-trip). For `Strict2_0`, extras are ignored on encode (always `Absent` for LSP/MCP); on decode, any unrecognized top-level fields are silently dropped (extras is always `Absent` on decoded Strict2_0 envelopes).

For **test 14** specifically: `extras = Absent` must produce a Record with only the standard JSON-RPC fields. `extras = Present(Structure.Value.Null)` must produce a Record that is observably different on the wire. The simplest implementation: for `Cdp.encode`, if `extras = Present(v)` where `v` is not a `Record`, encode as a top-level `"_extras"` key with value `v`. If `v` is a `Record`, stamp each entry into the top-level Record. This gives `Absent` vs `Present(Null)` an observable difference.

### 3.5 Cdp reserved-key rejection

`cdpReservedKeys = Set("id", "method", "params", "result", "error", "jsonrpc")`.

In `Cdp.encode`, when `extras = Present(Record(fields))`, iterate `fields`. If any `(key, _)` pair has `key` in `cdpReservedKeys`, immediately `Abort.fail(JsonRpcError.invalidRequest(s"extras key '$key' is reserved"))`. This is an `encode` failure, so the return type `Structure.Value < (Sync & Abort[JsonRpcError])` accommodates it.

### 3.6 Notification: no `id` key on the wire

A notification is an envelope without a request id. On encode: simply omit the `"id"` field from the output Record. On decode: if the Record has no `"id"` key AND has a `"method"` key AND has no `"result"` or `"error"` key, it is a `Notification`. Do NOT write `"id": null` for notifications.

`Strict2_0.decode`: if Record has `"id": null`, that is a null-id response (spec-legal for error replies to unparseable requests). Produce `Malformed` OR route to the appropriate envelope with `id = Absent`. Per DESIGN.md §3: `Maybe[JsonRpcId] = Absent` on the envelope. Do not introduce a `JsonRpcId.Null` variant.

### 3.7 Strict2_0 adds `"jsonrpc": "2.0"` on every outbound envelope

On `Strict2_0.encode`, prepend `("jsonrpc", Structure.Value.Str("2.0"))` to the Record fields. On `Cdp.encode`, omit it entirely.

On `Strict2_0.decode`, the `"jsonrpc"` field is consumed and discarded (its value is not validated beyond being present). On `Cdp.decode`, there is no `"jsonrpc"` field to look for.

### 3.8 Envelope classification in decode

Both codecs decode by extracting fields from a `Structure.Value.Record`. Classification:

- Has `"method"` and has `"id"` (and id is not null): `Request`
- Has `"method"` and has no `"id"` (or id is null): `Notification`
- Has no `"method"` and has `"id"` and has `"result"` or `"error"`: `Response`
- Anything else: `Malformed`

For `Cdp.decode`: any field that is NOT in `{"id", "method", "params", "result", "error", "jsonrpc"}` goes into `extras = Present(Record(unknownFields))`. If there are no unknown fields, `extras = Absent`.

### 3.9 `VariantCase` and `MapEntries` in extras are rejected

If caller-supplied `extras` is a `Structure.Value.VariantCase` or `Structure.Value.MapEntries`, both codecs reject with `JsonRpcError.invalidRequest`. `VariantCase` and `MapEntries` are in-process artifacts that do not survive JSON serialization. They should never appear in a JSON-originated `Structure.Value` from the wire.

### 3.10 Schema derivation for `Maybe[Structure.Value]`

`JsonRpcError` has `data: Maybe[Structure.Value]`. `Structure.Value` itself `derives Schema` (line 288 in Structure.scala). `Maybe[T]` has a `given Schema[Maybe[T]]` when `Schema[T]` is in scope. So `derives Schema` on `JsonRpcError` works without a hand-written schema. Do NOT write a manual schema for `JsonRpcError`.

Similarly, `JsonRpcRequest` with `params: Maybe[Structure.Value]` and `JsonRpcResponse` with `result: Maybe[Structure.Value]` both derive Schema automatically.

### 3.11 `private[kyo]` raw apply for JsonRpcResponse

```scala
case class JsonRpcResponse private[kyo] (
    id: JsonRpcId,
    result: Maybe[Structure.Value],
    error: Maybe[JsonRpcError]
) derives Schema, CanEqual
```

The `private[kyo]` modifier on the constructor still allows `derives Schema` to generate a valid schema (the macro reads the case class structure). External code in other packages cannot call `JsonRpcResponse(...)` directly; they must use `success` / `failure`.

---

## 4. Test Data Suggestions

For each of the 17 test leaves in IMPLEMENTATION.md lines 104-120.

**Test 2: Strict2_0 encode request**

```scala
val env = JsonRpcEnvelope.Request(JsonRpcId.Num(1L), "m", Absent, Absent)
val v   = Abort.run(Strict2_0.encode(env)).get.get    // Result.Success unwrap
// assert v == Structure.Value.Record(Chunk(
//   ("jsonrpc", Str("2.0")),
//   ("id",      Integer(1L)),
//   ("method",  Str("m"))
// ))
// "params" key must NOT be present
```

Verify by pattern matching `v` as `Record(fields)` and asserting `fields.map(_._1).toSet == Set("jsonrpc","id","method")`.

**Test 3: Strict2_0 round-trip request**

```scala
val decoded = Strict2_0.decode(v).run  // Structure.Value.Record from Test 2
// assert decoded == JsonRpcEnvelope.Request(JsonRpcId.Num(1L), "m", Absent, Absent)
```

**Test 4: JsonRpcId.Num flat encode**

```scala
val sv = Structure.encode[JsonRpcId](JsonRpcId.Num(1L))
// assert sv == Structure.Value.Integer(1L)
// WRONG result would be: Structure.Value.Record(Chunk(("Num", Record(Chunk(("value", Integer(1L)))))))
```

**Test 5: JsonRpcId.Str flat encode**

```scala
val sv = Structure.encode[JsonRpcId](JsonRpcId.Str("req-1"))
// assert sv == Structure.Value.Str("req-1")
```

**Test 6: Notification has no `id` key**

```scala
val env = JsonRpcEnvelope.Notification("ping", Absent, Absent)
val v   = Abort.run(Strict2_0.encode(env)).get.get
// v must be a Record; v.fields must not contain any ("id", _) entry
```

**Test 7: Success response has no `error` key**

```scala
val env = JsonRpcEnvelope.Response(JsonRpcId.Num(1L), Present(Structure.Value.Str("ok")), Absent, Absent)
val v   = Abort.run(Strict2_0.encode(env)).get.get
// Record must contain "result" with Str("ok") and must NOT contain "error"
```

**Test 8: Response with both result and error is Malformed**

```scala
val raw = Structure.Value.Record(Chunk(
    ("jsonrpc", Str("2.0")),
    ("id",      Integer(1L)),
    ("result",  Record(Chunk.empty)),
    ("error",   Record(Chunk(("code", Integer(-32600L)), ("message", Str(""))))))
)
val decoded = Strict2_0.decode(raw).run
// assert decoded.isInstanceOf[JsonRpcEnvelope.Malformed]
```

**Test 9: `id: null` on wire becomes `Maybe[JsonRpcId] = Absent`**

```scala
val raw = Structure.Value.Record(Chunk(
    ("jsonrpc", Str("2.0")),
    ("id",      Structure.Value.Null),
    ("error",   Record(Chunk(("code", Integer(-32700L)), ("message", Str("Parse error")))))
))
val decoded = Strict2_0.decode(raw).run
// For Strict2_0: this looks like a Response with null id.
// The envelope should have id resolved as Absent (no JsonRpcId.Null variant exists).
// Exact envelope type may be Malformed (null-id responses are spec edge cases).
// Key assertion: no crash, and no JsonRpcId.Null variant introduced.
```

**Test 10: Cdp extras appear at top level**

```scala
val extras = Present(Structure.Value.Record(Chunk("sessionId" -> Str("s1"))))
val env    = JsonRpcEnvelope.Request(JsonRpcId.Num(2L), "m", Absent, extras)
val v      = Abort.run(Cdp.encode(env)).get.get
// Record must contain ("sessionId", Str("s1")) at the top level
// Record must NOT contain ("jsonrpc", _)
```

**Test 11: Cdp reserved key in extras fails**

```scala
val badExtras = Present(Structure.Value.Record(Chunk("method" -> Str("hijack"))))
val env       = JsonRpcEnvelope.Request(JsonRpcId.Num(1L), "legit", Absent, badExtras)
val result    = Abort.run[JsonRpcError](Cdp.encode(env))
// assert result.isInstanceOf[Result.Failure[JsonRpcError]]
// assert result.failure.get.code == -32600   // invalidRequest
```

Use `Abort.run[JsonRpcError]` to discharge the `Abort[JsonRpcError]` effect.

**Test 12: Cdp decode harvests unknown top-level fields into extras**

```scala
val raw = Structure.Value.Record(Chunk(
    ("id",        Integer(2L)),
    ("method",    Str("Page.navigate")),
    ("params",    Record(Chunk("url" -> Str("https://example.com")))),
    ("sessionId", Str("abc123"))
))
val decoded = Cdp.decode(raw).run
// assert decoded == JsonRpcEnvelope.Request(
//     JsonRpcId.Num(2L), "Page.navigate",
//     Present(Record(Chunk("url" -> Str("https://example.com")))),
//     Present(Record(Chunk("sessionId" -> Str("abc123"))))
// )
```

**Test 13: Unclassifiable envelope is Malformed**

```scala
val raw = Structure.Value.Record(Chunk(("foo", Str("bar"))))
val decoded = Strict2_0.decode(raw).run
// assert decoded.isInstanceOf[JsonRpcEnvelope.Malformed]
```

**Test 14: extras Absent vs Present(Null) are distinct on the wire**

```scala
val envAbsent  = JsonRpcEnvelope.Request(JsonRpcId.Num(1L), "m", Absent, Absent)
val envNull    = JsonRpcEnvelope.Request(JsonRpcId.Num(1L), "m", Absent, Present(Structure.Value.Null))
val vAbsent    = Abort.run(Cdp.encode(envAbsent)).get.get
val vNull      = Abort.run(Cdp.encode(envNull)).get.get
// assert vAbsent != vNull
// vAbsent must not contain any key not in {id, method, params}
// vNull must differ from vAbsent in at least one field
```

**Test 15: JsonRpcError.cancelled has correct code and data**

```scala
val err = JsonRpcError.cancelled(Present("user"))
// assert err.code == -32800
// assert err.data == Present(Structure.Value.Str("user"))  // or similar encoding of "user"
```

**Test 16: success/failure factories enforce xor**

```scala
val ok  = JsonRpcResponse.success(JsonRpcId.Num(1L), Present(Structure.Value.Str("r")))
val bad = JsonRpcResponse.failure(JsonRpcId.Num(1L), JsonRpcError.MethodNotFound)
// assert ok.result  == Present(Structure.Value.Str("r"))
// assert ok.error   == Absent
// assert bad.error  == Present(JsonRpcError.MethodNotFound)
// assert bad.result == Absent
```

**Test 17: Cdp omits `jsonrpc`, Strict2_0 includes it**

```scala
val env    = JsonRpcEnvelope.Request(JsonRpcId.Num(1L), "m", Absent, Absent)
val vStrict = Abort.run(Strict2_0.encode(env)).get.get
val vCdp    = Abort.run(Cdp.encode(env)).get.get
// assert vStrict.asInstanceOf[Record].fields.exists(_._1 == "jsonrpc")
// assert !vCdp.asInstanceOf[Record].fields.exists(_._1 == "jsonrpc")
```

**Test 18: Schema[JsonRpcResponse] compiles and round-trips**

```scala
val schema = summon[Schema[JsonRpcResponse]]   // must compile
val resp   = JsonRpcResponse.success(JsonRpcId.Num(42L), Present(Structure.Value.Str("done")))
val sv     = Structure.encode(resp)
val back   = Structure.decode[JsonRpcResponse](sv).get
// assert back == resp
```

---

## 5. Anti-Flakiness Deltas

Phase 1 tests are pure (encode then decode, no fibers, no timers). Zero flakiness risk if the impl agent follows these rules:

1. **Do not use `assert(result.isFailure)` catch-alls.** Pattern-match on `Result.Failure(e)` explicitly to verify the error code.
2. **Use `Result.get` for the success path.** E.g., `Structure.decode[T](v).get` asserts success and unwraps in one line. Throws on failure, which gives a clear stacktrace.
3. **Use `Abort.run[JsonRpcError](...).run` for encode failure tests.** The two `.run` calls are: (1) `Abort.run[JsonRpcError]` discharges the `Abort[JsonRpcError]`, (2) `.run` on the resulting `Result < Sync` (or just `.get` if Sync is already discharged). In tests that inherit from `kyo.Test` (which runs effects), `Abort.run[JsonRpcError](...)` suffices since the test runner handles `Sync`.
4. **Use `Json.encode` for byte-level round-trip tests (Test 18).** `Structure.encode` then `Structure.decode` alone does not exercise the JSON serialization path. For test 18, go through `Json.encode[JsonRpcResponse](resp)` then `Json.decode[JsonRpcResponse](jsonStr)` to verify full round-trip including the `Schema[JsonRpcId]` writeFn/readFn.
5. **No `Thread.sleep` anywhere in Phase 1.**
6. **Pattern-match `Structure.Value.Record(fields)` with a `val Record(fields) = v` guard** rather than `.asInstanceOf`. The types are exact matches.

---

## 6. Concerns

### C1 (Critical): `reader.peekType()` / `Codec.Reader.TokenType` missing from this branch

The kyo-ai-plugin `Schema[JsonRpcId]` uses `reader.peekType()` and `Codec.Reader.TokenType.IntegerNumber`. These do NOT exist in `kyo-schema/shared/src/main/scala/kyo/Codec.scala` in the `crispy-swinging-lemur` worktree (194 lines; confirmed zero occurrences). The `luminous-toasting-graham` worktree has a newer `Codec.scala` with `peekType` (line 156) and `TokenType` (line 165). The impl agent MUST use the fallback dispatch described in Section 3.1. If the supervisor determines that `peekType` should be backported from the other worktree before Phase 1, that work must happen BEFORE Phase 1 begins.

### C2 (Minor): `IMPLEMENTATION.md` line 81 says `JsonRpcResponse.success(id, result)` takes `id: JsonRpcId`, but `id` could be `Maybe[JsonRpcId]`

DESIGN.md §3 shows `Response(id: JsonRpcId, ...)` on the envelope (not `Maybe`). So `JsonRpcResponse.success` takes `id: JsonRpcId` (non-optional). The null-id case (spec-legal for parse errors) is handled by routing to `Malformed` rather than `Response`. This is consistent.

### C3 (Minor): Test 14 semantics for `extras = Present(Structure.Value.Null)` in Cdp codec are underspecified

DESIGN.md §19 decision 1 states that `Present(Null)` and `Absent` are "observably different on the wire" but does not specify the exact wire representation for `Present(Null)` in the Cdp codec (since CDP uses Record-flattening for extras). The impl agent should pick a stable convention (e.g., write no keys for `Present(Null)` in Cdp, but still differ from `Absent` in the decoded round-trip by returning `Present(Null)` for an explicit null extras field). Escalate if the convention needs supervisor sign-off before test 14 is written.

### C4 (Minor): `JsonRpcError.internalError` factory signature in DESIGN.md has `data: Maybe[Structure.Value] = Absent` as a default parameter

Per project rules (`feedback_no_default_params_internal.md`), default parameters are forbidden in internal/private APIs. `internalError` is a public factory (it is in the public API surface in DESIGN.md §15). Default parameters on public API factories are exempted by the CLAUDE.md exception for `Config` case class and by the project convention for user-facing convenience factories. The impl agent should use the exact signature from DESIGN.md:

```scala
def internalError(cause: String, data: Maybe[Structure.Value] = Absent): JsonRpcError
```

Since `internalError` is a public factory, the default param is acceptable here.

### C5 (Informational): `VariantCase` and `MapEntries` rejection path

DESIGN.md §3 says both codecs reject `VariantCase` and `MapEntries` in caller-supplied extras. Test coverage for this path is not in the 17 named tests. The impl agent should add rejection for these cases in the encode path (alongside the reserved-key check) even without a dedicated test, to avoid a later correctness gap.
