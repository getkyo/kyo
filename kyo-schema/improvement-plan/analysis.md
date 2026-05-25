# kyo-schema Improvement: Analysis

## Executive Summary

The kyo-schema module ships with five real bugs in its macro-side type handling (three drift bugs in `SerializationMacro.isSerializableType`, `Structure.PrimitiveKind`/`StructureMacro.primitiveKindExpr`, and `MacroUtils` symbol sets, plus a composition bug where schema-level transforms are silently bypassed when a schema is used as a sub-schema, plus a latent Protobuf discriminator-decode bug surfaced by Phase 4 testing) plus a long list of missing `given Schema[...]` instances spanning JVM primitives, `java.time` extensions, full tuple ladder, `Array`, additional immutable collections, generic `Map[K, V]` for non-string keys, Java enums, and Scala 3 union types. This document inventories every gap with source citations, locks the four design decisions that govern the more open-ended pieces (unions, intersections, opaque types, non-string Map keys), and explains why test coverage emphasises edge correctness over smoke despite zero current consumer demand for the long tail.

## Drift bugs found

### Bug A — `SerializationMacro.isSerializableType` enumeration drift

**Location**: `kyo-schema/shared/src/main/scala/kyo/internal/SerializationMacro.scala` lines 1064-1163.

**Current behavior**:
- `primitiveSymbols` (L1070-1085) lists only String, Int, Long, Double, Float, Boolean, Short, Byte, Char, BigInt, BigDecimal, `java.time.Instant`, `java.time.Duration`, and `kyo.Frame`.
- `containerSymbols` (L1089-1098) lists List, Vector, Set, kyo.Chunk, kyo.Maybe, Option, kyo.Result. L1093 carries the comment `// NOT Seq - there's no Schema[Seq[A]] given`.
- `mapSymbols` (L1101-1104) lists Map and kyo.Dict only.

**Expected behavior**: every type for which a `given Schema[...]` exists in `Schema.scala` should pass `isSerializableType`. Specifically:
- The comment at L1093 is stale: `seqSchema` is defined at Schema.scala:1432. `Seq` must be in `containerSymbols`.
- `Span[A]` (spanSchema at Schema.scala:1453) is not recognised as a container.
- `Either` (eitherSchema at Schema.scala:1599) is not recognised; today only `kyo.Result` is handled by an ad-hoc two-argument case at L1130.
- `Tuple2..Tuple5` (Schema.scala:1533-1536) are not recognised.
- `java.time.LocalDate` / `LocalTime` / `LocalDateTime` (Schema.scala:1307-1325), `java.util.UUID` (Schema.scala:1328), `kyo.Instant` (Schema.scala:1274), `kyo.Duration` (Schema.scala:1278), `kyo.Text` (Schema.scala:1282) are missing from `primitiveSymbols`.

**Effect**: `Schema[CaseClass]` constructed via the `metaApply` path (Schema.scala:1192) silently produces a no-serialization Schema when any field has one of these types. `Schema.derived` bypasses the gate because it passes `checkSerializability = false` (FocusMacro.scala:685, 687), which is why this has slipped through user-facing derivation but breaks runtime serialization of metaApply-built schemas.

**Fix sketch**: extend `primitiveSymbols` with the eight missing primitive entries; extend `containerSymbols` with Seq and Span; add an explicit two-argument case for `Either`; add an N-argument case for tuples 2..5; remove the stale comment at L1093. Add a drift-guard test (a macro that enumerates the `Schema` companion's given declarations at compile time and asserts each one's target type is reachable through `isSerializableType`).

### Bug B — `Structure.PrimitiveKind` missing entries

**Location**: `kyo-schema/shared/src/main/scala/kyo/Structure.scala` lines 88-93; `kyo-schema/shared/src/main/scala/kyo/internal/StructureMacro.scala` lines 202-227.

**Current behavior**: the enum at Structure.scala:88-93 lists 12 cases: Int, Long, Short, Byte, Char, Float, Double, BigInt, BigDecimal, String, Boolean, Unit. `primitiveKindExpr` at StructureMacro.scala:202-227 is strict: any type that `isPrimitive` accepts but `primitiveKindExpr` cannot map is rejected with `report.errorAndAbort` (L221-225).

**Expected behavior**: `Instant`, `Duration`, `Frame`, and `kyo.Text` are all primitives elsewhere in kyo-schema (recognised by `SerializationMacro` after Bug A's fix; recognised by `Schema.scala` as scalar givens). They must have corresponding `PrimitiveKind` cases and `primitiveKindExpr` branches.

**Effect**: `Structure.of[CaseClassWithInstant]` (and Duration, Frame, Text) fails to compile with the "No PrimitiveKind mapping" error.

**Fix sketch**: add 4 cases to the `PrimitiveKind` enum at Structure.scala:88-93; add 4 mapping branches to `primitiveKindExpr` at StructureMacro.scala:202-227; cover each with a `Structure.of[...]` round-trip test plus one negative test that confirms an actually-unsupported primitive (a synthetic test type) still produces a clear compile error.

### Bug C — `MacroUtils` symbol-set drift

**Location**: `kyo-schema/shared/src/main/scala/kyo/internal/MacroUtils.scala` lines 247-273.

**Current behavior**:
- `collectionSymbols` (L247-256) lists List, Seq, Vector, Set, kyo.Chunk. Missing: `kyo.Result` (which `SerializationMacro` does treat as a container, with its own two-argument handler).
- `mapSymbols` (L268-273) lists Map only. Missing: `kyo.Dict` (which `SerializationMacro` treats as map-like at SerializationMacro.scala:1103).

`SerializationMacro` maintains its own `primitiveSymbols`, `containerSymbols`, `mapSymbols` sets at L1070-1104 rather than reusing `MacroUtils`. This produces three independently drifting sources of truth: `MacroUtils.collectionSymbols`, `SerializationMacro.containerSymbols`, and the implicit set defined by `Schema.scala`'s given declarations.

**Effect**: any consumer of `MacroUtils.collectionSymbols` (StructureMacro and other macros) treats `Result` as a non-collection; any consumer of `MacroUtils.mapSymbols` treats `Dict` as a non-map. Drift compounds whenever a new given is added.

**Fix sketch**: add `kyo.Result` to `MacroUtils.collectionSymbols`; add `kyo.Dict` to `MacroUtils.mapSymbols`. Where `SerializationMacro` overlaps with `MacroUtils`, replace the local set with delegation, keeping the gate's two-argument special-case branches (Result/Either error type, Map/Dict key type) as additional branches. Add a drift-guard test that compares `MacroUtils`'s union of `(primitiveSymbols, collectionSymbols, optionalSymbols, mapSymbols)` against the actual `Schema` companion givens set 1:1.

### Bug D — Transform composition lost on nested schemas

**Location**: `kyo-schema/shared/src/main/scala/kyo/internal/SerializationMacro.scala` L117 (the generic-fallback field write inside `caseClassWriteBody`) and the analogous read sites inside `caseClassReadBody` / `caseClassReadBodyResolved`.

**Current behavior**: the generic-fallback field codec dispatch at SerializationMacro.scala:117 emits `$schemaExpr.serializeWrite($fieldAccess, $writer)`. That call hits the raw codec body directly. It bypasses `SchemaSerializer.writeTo` (Schema.scala:1000, SchemaSerializer.scala:21), which is the only place the `hasTransforms` flag (Schema.scala:95) is consulted. `hasTransforms` is the disjunction `droppedFields.nonEmpty || renamedFields.nonEmpty || computedFields.nonEmpty || discriminatorField.isDefined` (Schema.scala:95-97); the discriminator-aware sealed-trait emission lives only inside `SchemaSerializer`'s `writeWithTransforms` (SchemaSerializer.scala:76-118, 269+), so any sub-schema that carries any of those four flags has its transform silently dropped at the parent level.

The reporter surfaced this through the discriminator case: `sealed trait RO derives CanEqual` with `given Schema[RO] = Schema.derived[RO].discriminator("type")`. Top-level `Json.encode[RO](RO.string("hi"))` correctly produces `{"type":"string","value":"hi"}`. The same value inside `case class Envelope(result: RO) derives Schema` produces `{"result":{"string":{"value":"hi"}}}` (wrapper format) and the corresponding decode fails with `UnknownVariantException("type")`. The same shape applies to `.drop`, `.rename`, `.add`, and `.computed` — any transform applied to a `Schema` that is then consumed as a sub-schema is lost.

**Expected behavior**: when a sub-schema's `hasTransforms` is true, the parent's field codec routes through the transform-aware path. The nested wire format must be byte-for-byte identical to the wire format the same sub-schema produces when encoded directly at the top level.

**Effect**: every transform composition is broken across nesting boundaries. The reporter's CDP `Runtime.evaluate` modelling (a discriminated `RemoteObject` nested inside an envelope) requires hand-rolled `Schema.init` codecs to work around this. Equivalent silent failures exist for any user of `.drop` / `.rename` / `.add` on schemas consumed as sub-schemas. The bug is invisible at the top-level entry point because `SchemaSerializer.writeTo` is invoked there (Schema.scala:130-156, 173-192 entry points), so the discriminator/drop/rename behaviour is correct in isolation and only fails under composition.

**Fix sketch**: in the generic-fallback branch at SerializationMacro.scala:117 (and the parallel `optionFields`/`maybeFields` generic branches if they suffer the same bypass), emit `kyo.internal.SchemaSerializer.writeTo($schemaExpr, $fieldAccess, $writer)` instead of the raw `serializeWrite` call. Do the equivalent swap from `serializeRead` to `SchemaSerializer.readFrom` in `caseClassReadBody` / `caseClassReadBodyResolved`. Preserve the primitive / primitive-element-container / Result fast paths (L85-113) unchanged — those branches handle types that cannot carry transforms meaningfully. Cost: one `hasTransforms` field read and one branch per non-primitive field write/read; in the no-transform case (the overwhelming default), `writeTo` short-circuits to the raw path at SchemaSerializer.scala:21. Ensure `SchemaSerializer.writeTo` / `readFrom` are reachable from `kyo.internal` (they already are, both being `private[kyo]`).

### Bug E — Protobuf discriminator-field decode fails (latent; surfaced by Phase 4 test)

**Location**: `kyo-schema/shared/src/main/scala/kyo/internal/SchemaSerializer.scala` `DiscriminatorReader.objectStart` (L317-358) — specifically the field-read loop at L328-336 that calls `inner.field()` (L329) and string-compares the returned name against `discField` (L330). Interacts with `kyo-schema/shared/src/main/scala/kyo/internal/ProtobufReader.scala` `field()` at L62-75, which returns `fieldNames.getOrElse(currentFieldNumber, currentFieldNumber.toString)` (L74) where `fieldNames: Map[Int, String]` is declared at L27 as `Map.empty` and is mutated only through `withFieldNames` (L31-33).

**Current behavior**: when a `Schema[Sealed].discriminator("type")` is decoded from Protobuf bytes, `readWithDiscriminator` (SchemaSerializer.scala:268-277) constructs a `DiscriminatorReader` wrapping the `ProtobufReader`. The macro-generated `sealedReadBody` invokes `discReader.objectStart()`, which enters phase 0 and loops over `inner.hasNextField()` calling `inner.field()` to obtain each field name. For `ProtobufReader`, `field()` returns the field-id stringified (e.g. `"1234567"`) because `fieldNames` was never populated — `withFieldNames` is only invoked by two test sites (`ProtobufTest.scala:139`, `SchemaTest.scala:2957, 2972`) and never by the production decode path in `Protobuf.decode` (Protobuf.scala:42-50). The string comparison `fname == discField` at SchemaSerializer.scala:330 never matches `"type"`, `foundVariant` stays empty, and `MissingFieldException(Seq.empty, discField)` is thrown at L340.

**Expected behavior**: Protobuf decoding of a sealed-trait value carrying `.discriminator(name)` produces the original value with the correct variant, mirroring the JSON path. The wire-format symmetry already holds on the write side because the writer materialises the discriminator field name through its own dictionary; only the read side breaks.

**Effect**: every Protobuf decode of a sealed-trait value configured with `.discriminator(...)` fails with `MissingFieldException` naming the discriminator field. This is latent: no pre-existing test combined Protobuf with `.discriminator(...)`. After Phase 4 lands, the symptom is visible in Phase 4's `NestedTransformTest` Protobuf round-trip leaf (`kyo-schema/shared/src/test/scala/kyo/NestedTransformTest.scala:73-78`); that test stays RED on purpose, exposing this bug.

**Fix sketch — two viable paths**:
- **(a) Populate `ProtobufReader.fieldNames` at decode-time.** The macro-generated `caseClassReadBody` / `caseClassReadBodyResolved` in `SerializationMacro.scala` already materialises a `_fieldNames: Array[String]` (threaded through `fieldNamesExpr` at L726, L813, L1009; used today only to label `MissingFieldException` at L813). Extend the read prelude (around the `$reader.objectStart()` call at L990) to publish the field-name → field-id map to the reader via a new `Reader` API (e.g. `reader.withFieldNames(map)`), backed by `ProtobufReader.withFieldNames` (already defined at L31-33). Other `Reader` implementations (JsonReader, StructureValueReader) get a no-op default. Benefits any other site that depends on `inner.field()` returning a meaningful name (e.g. error messages from custom decoders).
- **(b) Make `DiscriminatorReader` use field IDs.** Compute the discriminator's field ID once at `DiscriminatorReader` construction via `CodecMacro.fieldId(discField)` (CodecMacro.scala:18-19) and compare it against `inner` reader's currently-parsed field ID rather than the name. Requires extending the `Reader` API with `currentFieldId(): Int` (or reusing `matchField(nameBytes)` after the inner reader caches the parsed tag). Lighter touch but does not fix the broader gap, and risks fragmenting the discriminator path between JSON (string-compare) and Protobuf (id-compare).

**Recommendation: path (a)**. Removes a quiet correctness gap in the rest of the codebase (any `inner.field()` consumer); symmetric with how the JSON path returns the actual property name; reuses the already-defined `ProtobufReader.withFieldNames` setter that today exists only for test setup.

## Gap inventory

The following types are already supported and must not be re-added:

- Primitives: String, Boolean, Int, Long, Float, Double, Short, Byte, Char, BigDecimal, BigInt (Schema.scala:1232-1263).
- java.time: Instant, Duration, LocalDate, LocalTime, LocalDateTime (Schema.scala:1266-1325).
- Other: UUID, Unit, Span[Byte], Frame, Tag[A] (Schema.scala:1286-1335).
- kyo: kyo.Instant, kyo.Duration, kyo.Text (Schema.scala:1274-1283).
- Collections: List[A], Vector[A], Set[A], Chunk[A], Seq[A], Span[A], Maybe[A], Option[A] (Schema.scala:1344-1502).
- Maps: Map[String, V], Dict[String, V], Dict[K, V] (Schema.scala:1505, 1643, 1670).
- Tuples: Tuple2..Tuple5 (Schema.scala:1533-1536).
- Sums: Result[E, A], Either[A, B] (Schema.scala:1541, 1599).

### Missing primitives (cross-platform)

| Type | Wire encoding |
|---|---|
| `java.math.BigInteger` | BigInt via `BigInt(_)` / `_.bigInteger` |
| `java.math.BigDecimal` | BigDecimal via `BigDecimal(_)` / `_.bigDecimal` |
| `scala.Symbol` | String via `_.name` / `Symbol(_)` |
| `scala.util.matching.Regex` | String via `_.regex` / `_.r` |
| `scala.util.Try[A]` | encoded as `Either[Throwable, A]` |
| `Throwable` | String message; class info lost on round-trip; documented |

### Missing primitives (JVM-only)

| Type | Wire encoding |
|---|---|
| `java.net.URI` | String via `toString` / `new URI(_)` |
| `java.net.URL` | String via `toString` / `new URL(_)` |
| `java.net.InetAddress` | String via `getHostAddress` / `InetAddress.getByName(_)` |
| `java.nio.file.Path` | String via `toString` / `Paths.get(_)` |
| `java.io.File` | String via `getPath` / `new File(_)` |
| `java.util.Locale` | String via `toLanguageTag` / `Locale.forLanguageTag(_)` |
| `java.util.Currency` | String via `getCurrencyCode` / `Currency.getInstance(_)` |

### Missing java.time

| Type | Wire encoding |
|---|---|
| `java.time.ZoneId` | String via `getId` / `ZoneId.of(_)` |
| `java.time.ZoneOffset` | String via `getId` / `ZoneOffset.of(_)` |
| `java.time.OffsetDateTime` | ISO-8601 String |
| `java.time.ZonedDateTime` | ISO-8601 String |
| `java.time.Year` | Int via `getValue` / `Year.of(_)` |
| `java.time.YearMonth` | String via `toString` / `YearMonth.parse(_)` |
| `java.time.MonthDay` | String via `toString` / `MonthDay.parse(_)` |
| `java.time.Period` | String via `toString` / `Period.parse(_)` |

### Missing tuples

`Tuple1`, `Tuple6` through `Tuple22`. Each given is a one-liner of the form `given tupleNSchema[...]: Schema[(...)] = Schema.derived`, following the Schema.scala:1533-1536 pattern.

### Missing collections

| Type | Wire encoding |
|---|---|
| `Array[A]` | array, via `Span[A]` or `Seq[A]` transform (requires `ClassTag`) |
| `scala.collection.immutable.ArraySeq[A]` | array, via `Seq[A]` transform |
| `scala.collection.immutable.Queue[A]` | array, via `Seq[A]` transform |
| `scala.collection.immutable.SortedSet[A]` | array, via `Set[A]` transform; requires `Ordering[A]` |
| `scala.collection.immutable.SortedMap[K, V]` | object or array-of-pairs via Phase 6 pathway; requires `Ordering[K]` |

### Missing map shape

Generic `Map[K, V]` where `K` is not `String`. Today only `Map[String, V]` (Schema.scala:1505) and `Dict[K, V]` (Schema.scala:1670) are supported. See "Design decisions" for the `KeyCodec[K]` approach.

### Missing derivations

- Java enums. Schema.derived only recognises Scala 3 enums and Scala sealed traits; Java enum classes fall through and fail with an unhelpful error.
- Scala 3 union types `A | B`. `Schema.derived[A | B]` errors at FocusMacro.scala:684-692 because no OrType branch is present.
- Scala 3 intersection types `A & B`. Same error site; no general constructor exists (see Design decisions for rejection rationale).

## Design decisions

### Decision 1 — Union types: support both untagged and tagged

`Schema.derived[A | B]` will succeed. Default encoding is untagged: the writer tries each branch's schema in declaration order and uses the first that matches; the reader attempts each branch in order and returns the first that decodes without error. Users who need disambiguation in the presence of overlapping types call `.discriminator(name)` on the resulting union schema, identical to the existing sealed-trait API. This reuses existing plumbing rather than introducing a parallel mechanism.

**Rationale**: untagged matches the default mental model in Circe and zio-json and correctly handles the most common narrow-style union (`String | Int`, `Path | java.net.URI`). Tagged is required only when types share wire shape (two case classes with identical fields). The discriminator method already exists and works for sealed traits; reusing it preserves a single user-facing surface.

### Decision 2 — Intersection types: explicit compile-time rejection

`Schema.derived[A & B]` will fail at macro expansion with a message of the form:

> Cannot derive `Schema[A & B]`: intersection types have no general constructor.
> If `A & B` is a concrete case class extending both, derive its `Schema` directly.
> Otherwise, build a `Schema` for a concrete representation and use `.transform` to
> map to and from the intersection.

**Rationale**: there is no way to construct an arbitrary `A & B` value from independent `A` and `B` decoders without knowing the concrete type. The cases that work today (case classes that mix in both traits) already work via the case-class derivation path with a direct `Schema.derived[ConcreteClass]`. A clear error pointing at `.transform` is the correct experience for the residual cases.

### Decision 3 — Opaque types: no auto-derivation, document the `.transform` recipe

Opaque types remain unsupported by `Schema.derived` for opaque type aliases whose representation is hidden at the call site. The scaladoc for the `Schema` companion will include a worked example showing how to use `Schema.stringSchema.transform` (or whichever underlying-type schema is appropriate) to opt in explicitly.

**Rationale**: opaque types are intentionally opaque. Auto-deriving against their representation type would let any downstream client serialize and deserialize values that bypass invariants the opaque type author wanted to enforce. The transform recipe is one line and keeps the invariant in the author's hands.

### Decision 4 — `Map[K, V]` with non-String keys: KeyCodec typeclass with array fallback

A new typeclass `KeyCodec[K]` is introduced (`kyo-schema/shared/src/main/scala/kyo/KeyCodec.scala`):

```scala
trait KeyCodec[K]:
    def encode(k: K): String
    def decode(s: String): Result[DecodeException, K]
```

Built-in givens cover `String` (identity), `Int`, `Long`, `UUID`. When a `KeyCodec[K]` is in scope, `Map[K, V]` serialises as a JSON object with stringified keys. When no `KeyCodec[K]` is in scope, the schema falls back to encoding `Map[K, V]` as `Array[(K, V)]` (a JSON array of two-element arrays), using `Schema[K]` and `Schema[V]` directly.

**Rationale**: most non-String key types (`Int`, `UUID`) round-trip losslessly through strings and produce idiomatic JSON objects. Truly structural keys (a case class, a tuple) have no string projection; the array-of-pairs fallback keeps them serialisable without forcing the user to invent a `KeyCodec`. Both shapes are common in the ecosystem, and the user picks by importing or not importing the corresponding `KeyCodec` instance.

## Out of scope (explicit)

- **Mutable collections** (`mutable.Map`, `mutable.Buffer`, `mutable.HashSet`, etc.). kyo's data model is immutable; mutable surfaces add wire-encoding ambiguity and conflict with the safe-by-default posture. Users who need them can use `.transform` from the immutable equivalent.
- **NonEmpty\*** collection wrappers. Verified by grep across both `kyo-schema/` and `kyo-data/shared/src/main/scala/`: no `NonEmptyChunk`, `NonEmptyList`, `NonEmptySet`, or any `NonEmpty*` type exists in the kyo codebase. Nothing to support.
- **Opaque type auto-derivation**. See Design decision 3. Users opt in with `.transform`; scaladoc shows the recipe.
- **Tuple23+**. Tuple ladder caps at Tuple22 (Scala's own limit).

## Demand reality and test strategy

Grepping the kyo repo for actual `Schema[...]` usage (kyo-http, kyo-flow, kyo-examples, kyo-bench) shows consumers use only: primitives, List/Seq/Option, Map[String, V], nested case classes, `java.time.Instant`, and `Tag[Any]`. Zero current usage of Tuple6+, non-string-key Map, Array, `java.net.*`, Path, File, Locale, Currency, Symbol, Regex, Try, Throwable, union types, intersection types, or opaque-Schema.

The long tail ships anyway because the operating principle is "complete and correct, no scope cuts": serialization libraries that selectively cover popular types push edge cases onto users who then maintain bespoke `.transform` snippets that drift.

Because real demand is concentrated and the new givens are essentially defensive, test coverage emphasises **edge correctness** rather than smoke:

- `Locale` round-trip exercises a BCP-47 tag with script and region variant (`zh-Hant-TW`), not just `en`.
- `Path` round-trip exercises a platform-specific separator and validates idempotence on `Paths.get(_).toString`.
- `OffsetDateTime` round-trip exercises a DST-transition instant.
- `Year` / `MonthDay` round-trip exercises leap-year edges.
- `Map[K, V]` round-trip exercises a structural key (case class) to validate the array-of-pairs fallback, not just `Map[Int, String]`.
- Union round-trip exercises an overlapping case (`String | Int` where the source is a numeric string) to validate ordering rules.
- Nested-transform round-trip (Bug D coverage) exercises a discriminated sealed trait nested inside an envelope, plus `.drop` / `.rename` / `.add` on a nested schema, to lock the composition contract.

This guards against the kind of regression that only shows up the day a user finally reaches for the type, by which point the macro behaviour is opaque enough that debugging is expensive.

### Composition coverage principle (lesson from Bug D)

Bug D escaped the existing test suite because every test axis was covered independently and the cross-product cells were empty. The discriminator suite shipped 33 scenarios at the top level. The fixture for "discriminated trait nested in a case class" (`MTDrawing(title: String, shape: MTShape)`) existed in `SchemaTestData.scala:27` but was used only by `FocusTest.scala` for navigation lambdas — never by `SchemaTest.scala`'s discriminator section. Transformed schemas were placed into `given` position for `Changeset`/`Compare` tests at SchemaTest.scala:6079-6111 but never for parent-schema encoding. The "transform applied to a schema used as a sub-schema" cell of the (transform × embedded position) matrix had zero tests; that is precisely where Bug D lived.

**Operating principle going forward**: any property defined for a value in isolation that can be embedded in a context (a field, a container element, an optional wrap, a sum-type variant, a tuple slot, a Map value, an Either/Result leg) MUST be tested at the embedded position. Single-axis exhaustiveness is not coverage; cross-axis cells are where macro emission paths diverge and bugs hide.

This principle materialises as **Phase 5** of the execution plan, a composition test matrix that sweeps:
- **Type × embedded position**: each representative supported type tested at every embedded position. Catches the next Bug A-class regression (gate accepts type T at top level but the macro fails to emit a correct codec when T appears at some embedded position).
- **Transform × embedded position**: each schema-level transform (`.discriminator`, `.drop`, `.rename`, `.add`, `.computed`) tested at every embedded position. Catches the next Bug D-class regression directly.
- **Composition invariant**: assert that `parentEncode(wrap(value))` contains `childEncode(value)` as a substring (modulo wrapping syntax) for every (parent shape, transformed child) pair. This is the property Bug D violated; making it a first-class assertion locks the contract.
