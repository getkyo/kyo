# Contributing to kyo-schema

This guide complements the root [CONTRIBUTING.md](../CONTRIBUTING.md), which covers global Kyo conventions (naming, `Maybe` / `Result` / `Chunk` / `Span`, `using`-clause ordering, Frame/Tag, inline guidelines, scaladoc, file organisation, visibility tiers, KyoException, the test framework, cross-platform source placement, AllowUnsafe). Defer to the root guide for those; this file covers only what is specific to kyo-schema.

**The headline invariant**: a `Schema[A]` is a four-slot value (`serializeWrite`, `serializeRead`, `getter`, `setter`) plus a lazy `Structure.Type` projection, and the derivation macro emits **one** runtime call per `derives Schema`, never per-field branching. Two cycle-breaks (a by-name `Structure.Field._fieldType` thunk and a `lazy val _structure` on every Schema) are what let recursive type graphs derive without `StackOverflowError`; the structural-emit / runtime-walk split is what keeps test classes with many derivations under JVM class-file limits. Internalise both halves before changing anything in the derivation path.

## Architecture at a Glance

kyo-schema's sbt module depends only on `kyo-data` (no `kyo-kernel`, no `kyo-prelude`, no effect runtime), so it is adoptable as a standalone serialization library [build.sbt:586-597].

Dependency direction is strictly downward: `internal/` may not import from outside `internal/` other than the public types it implements, and the public surface calls into `internal/` exclusively through named entry points; there is no cycle through `internal -> public -> internal` [kyo-schema/shared/src/main/scala/kyo/internal/SchemaDerivedMacro.scala:13-16].

| Layer | Files | Role |
|-------|-------|------|
| Public surface | `Schema.scala`, `Codec.scala`, `Structure.scala`, `SchemaException.scala`, `Json.scala`, `Protobuf.scala`, `Yaml.scala`, `Focus.scala`, `Builder.scala`, `Compare.scala`, `Changeset.scala`, `Convert.scala`, `Modify.scala` | The four-slot Schema, the Codec abstraction, the exception hierarchy, the format entry points, the navigation/transform helpers. |
| Macro boundary | `internal/SchemaDerivedMacro.scala` | Thin class-file boundary that immediately delegates to `FocusMacro.derivedImpl`; exists only to break the cyclic compile-time dependency the `inline given derived` call in `Schema.scala` would otherwise create [kyo-schema/shared/src/main/scala/kyo/internal/SchemaDerivedMacro.scala:6-22]. |
| Macros | `internal/FocusMacro.scala`, `internal/SchemaTransformMacro.scala`, `internal/NavigationMacro.scala`, `internal/ExpandMacro.scala`, `internal/MacroUtils.scala`, `internal/CodecMacro.scala` | Derivation, transforms (drop/rename/add/select/flatten), navigation, structural-shape expansion, shared quote-reflection utilities, and the `fieldId` MurmurHash3 helper. |
| Runtime engine | `internal/SchemaCodecRuntime.scala`, `internal/SchemaSerializer.scala`, `internal/SchemaFactory.scala`, `internal/StructureValueWriter.scala`, `internal/StructureValueReader.scala` | The runtime walk fed by the macro-emitted metadata table; the transform-aware dispatcher; the path-key recomputation factory; the in-memory `Structure.Value` writer/reader. |
| Wire formats | `internal/JsonWriter.scala`, `internal/JsonReader.scala`, `internal/ProtobufWriter.scala`, `internal/ProtobufReader.scala`, `internal/YamlWriter.scala`, `internal/YamlReader.scala`, `internal/YamlParser.scala`, `internal/YamlEventReader.scala`, `internal/JsonSchemaEnricher.scala` | Concrete `Codec.Writer` / `Codec.Reader` implementations; JSON math sublayers (`Ryu` for write, `FastFloat` for read). |
| Platform split | `jvm/.../AsciiStringFactory.scala`, `js-wasm/.../AsciiStringFactory.scala`, `native/.../AsciiStringFactory.scala`, `jvm/.../tools/FastFloatPow10Gen.scala` | The single platform-specific surface plus a JVM-only build-time table generator. |

### Representative call flow

For `case class User(name: String, age: Int) derives Schema` and `Json.encode(user)`, the path is:

1. `derives Schema` resolves to `Schema.derived`, an `inline given derived` that calls `SchemaDerivedMacro.derivedImpl` [kyo-schema/shared/src/main/scala/kyo/Schema.scala:1213-1225].
2. `SchemaDerivedMacro` delegates to `FocusMacro.derivedImpl` [kyo-schema/shared/src/main/scala/kyo/internal/SchemaDerivedMacro.scala:6-22].
3. `FocusMacro.derivedImpl` walks `sym.caseFields`, emits one `summonInline[Schema[ft]]` thunk per field, and emits a single call to `SchemaCodecRuntime.buildProductSchema[User]` carrying the precomputed `ProductFieldsMeta` and the thunk array [kyo-schema/shared/src/main/scala/kyo/internal/FocusMacro.scala:11-22] [kyo-schema/shared/src/main/scala/kyo/internal/SchemaCodecRuntime.scala:7-14].
4. `buildProductSchema` constructs a fresh `new Schema[User] { ... }` whose `serializeWrite` / `serializeRead` each contain one call back into `writeProduct` / `readProduct` [kyo-schema/shared/src/main/scala/kyo/internal/SchemaCodecRuntime.scala:84-118].
5. At call time `Json.encode(user)` -> `summon[Json].newWriter()` -> `kyo.internal.JsonWriter()`, then `schema.writeTo(value, w)` -> `internal.SchemaSerializer.writeTo` -> `schema.serializeWrite` (direct branch, no transforms) -> `SchemaCodecRuntime.writeProduct`, which walks `meta.names` / `meta.fieldIds` / `meta.nameBytes` and calls `writer.fieldBytes(...)` followed by a recursive `SchemaSerializer.writeTo(schemas(i), raw, writer)` per field [kyo-schema/shared/src/main/scala/kyo/Json.scala:36-40] [kyo-schema/shared/src/main/scala/kyo/internal/SchemaCodecRuntime.scala:121-150].

### Cross-platform split

`AsciiStringFactory` is the only platform-specific surface in the module. The JVM has a private LATIN1 `String(byte[], byte)` constructor that allows zero-copy adoption of an ASCII byte[] as the String backing; Scala.js and Scala Native have no equivalent and fall back to a copying `String(bytes, US_ASCII)` [kyo-schema/jvm/src/main/scala/kyo/internal/AsciiStringFactory.scala:7-25]. It is used only on the JSON-write hot path (string tail trim, ASCII fast-path) and lives in `private[internal]` [kyo-schema/shared/src/main/scala/kyo/internal/JsonWriter.scala:189-222].

JS and WASM share a single source root: the build's `js-wasm/` partially-shared directory holds the Scala.js-common copy of `AsciiStringFactory`, so JS and WASM both pick it up without per-platform duplication [project/WasmPlatform.scala:8-16].

`FastFloatPow10Gen` is a build-only tool in `jvm/src/main/scala/kyo/internal/tools/`, never linked into the runtime; it regenerates the checked-in `FastFloatPow10Table` for the Eisel-Lemire fast-path [kyo-schema/jvm/src/main/scala/kyo/internal/tools/FastFloatPow10Gen.scala:8-18].

## The Headline: Four-Slot Schema, Structural-Emit, Runtime-Walk

### Goal

`Schema.derived` must simultaneously support **recursive type graphs** (a `case class Tree(children: List[Tree])` must derive without `StackOverflowError`) and **codebases with many derivations** (a test class with hundreds of `derives Schema` cases must compile without exceeding JVM class-file limits). The architecture solves both with one design.

### The four-slot abstraction

A public `Schema[A]` is a single abstract class with four `private[kyo]` abstract methods (`serializeWrite`, `serializeRead`, `getter`, `setter`); every other surface method delegates to one of them, so the whole module is bottle-necked through four function-shaped slots [kyo-schema/shared/src/main/scala/kyo/Schema.scala:55-89].

Each concrete `Schema[A]` MUST also supply `structure: Structure.Type`. The method is abstract on the class; omitting the `structure` argument when calling `Schema.init` is a compile error. The structure is the sole source of truth for "what shape does this Schema produce on the wire": it is consumed by `Json.JsonSchema.from[A]`, `Protobuf.ProtoSchema.from[A]`, and the case-class derivation macro to build product/sum structures [kyo-schema/shared/src/main/scala/kyo/Schema.scala:800-810].

### The single supported construction surface

`Schema.init` (and the `Schema.initFocused` variant) is the canonical factory. It is `inline` so the caller's four lambdas substitute directly into the abstract method bodies of a fresh `new Schema[A] { ... }` subclass; no `Function` closure is allocated per derivation [kyo-schema/shared/src/main/scala/kyo/Schema.scala:1099-1152]. `@nowarn("msg=anonymous")` is the documented suppression for that inline-expansion pattern.

The `structure` parameter on `Schema.init` is by-name (`structure: => Structure.Type`); the implementation captures it via `lazy val _structure = structure`. Container givens pass `inner.structure`, and the lazy capture is what prevents initialization cycles in recursive structure type graphs [kyo-schema/shared/src/main/scala/kyo/Schema.scala:1125-1150].

### Cycle-break one: `lazy val _structure`

Every `Schema[A]` carries its `Structure.Type` lazily via `lazy val _structure` so recursive type graphs construct without forcing the inner Schema's structure mid-init [kyo-schema/shared/src/main/scala/kyo/Schema.scala:1127-1150]. This is the outer half of the recursion break.

### Cycle-break two: by-name `Structure.Field._fieldType`

`Structure.Field` holds `_fieldType` as a `() => Structure.Type` thunk; the public accessor `def fieldType` forces it [kyo-schema/shared/src/main/scala/kyo/Structure.scala:285-292]. The by-name `Field.apply` is the only contract callers may use to build a `Structure.Field`. Both the strict caller form (`Structure.Field("x", someStructure, ...)`) and the macro emission (`Structure.Field("x", summonInline[Schema[t]].structure, ...)`) rely on it deferring the structure's evaluation until the first `field.fieldType` read. Strictly evaluating the inner structure here is the canonical way to deadlock the recursive-derivation contract [kyo-schema/shared/src/main/scala/kyo/Structure.scala:320-338].

**Trap (case-class accessors expose the thunk)**: the case-class-generated `productElement(1)` / `unapply` / `copy` for `Structure.Field` expose the storage member `_fieldType` (a `Function0[Structure.Type]`), NOT the forced value. A contributor reading `productElement(1)` and expecting a `Structure.Type` will get a thunk. Read field type only through the public `fieldType` accessor [kyo-schema/shared/src/main/scala/kyo/Structure.scala:272-274].

**Trap (default equals compares function references)**: the default case-class `equals` on `Structure.Field` would compare wrapping `Function0` references and report `false` for structurally identical Fields. A hand-rolled `equals` forces both thunks and compares the structures via `.equals`. `Structure.Type` has no `CanEqual`; reach for `.equals` directly. New `Structure.Type` variants must support `.equals` [kyo-schema/shared/src/main/scala/kyo/Structure.scala:294-306].

### Structural-emit + runtime-walk

The derivation macro is a **structural emitter**, not a per-type specializer. It walks `sym.caseFields` (for case classes) or `sym.children` (for sealed traits) and, for each field/variant, emits a thunk wrapping `scala.compiletime.summonInline[Schema[ft]]`. It assembles a runtime field/variant table consumed by `SchemaCodecRuntime`. **The macro never pattern-matches on a specific container or primitive type symbol**: every nested type resolves via `summonInline` at the inline-expansion phase, which sees forward-references to the in-flight `derived$Schema` and so handles recursion without any special-case in the macro [kyo-schema/shared/src/main/scala/kyo/internal/FocusMacro.scala:11-22] [kyo-schema/shared/src/main/scala/kyo/internal/FocusMacro.scala:315-322].

The emission has **constant per-method bytecode**: `serializeWrite` / `serializeRead` each call a single runtime helper passing the precomputed field-entry table. The table itself uses `summonInline[Schema[ft]]` thunks that resolve at the inline-expansion phase, with the in-flight `derived$Schema` visible by forward-reference [kyo-schema/shared/src/main/scala/kyo/internal/FocusMacro.scala:367-373].

The JVM class-file limit drives this architecture: keeping per-derivation inline bytecode constant-sized is what allows test classes with many `derives Schema` to compile. **Reintroducing per-field branching back into the emitted methods is what gets a test class to exceed the limit** [kyo-schema/shared/src/main/scala/kyo/internal/SchemaCodecRuntime.scala:7-14] [kyo-schema/shared/src/main/scala/kyo/internal/FocusMacro.scala:400-405].

All per-field metadata is packed into ONE compile-time string literal `name<TAB>flags;...` that the runtime `ProductFieldsMeta` inflates into parallel arrays once at construction. The macro deliberately emits a single string constant rather than N field-meta values to keep emission small [kyo-schema/shared/src/main/scala/kyo/internal/SchemaCodecRuntime.scala:26-37].

The runtime helper walks the per-field thunk array lazily: a single `lazy val schemas: Array[Schema[Any]] = schemasBuilder()` forces the entire field-Schema array on first use; per-field schemas are then indexed by position in `writeProduct` / `readProduct`. The thunk indirection is what lets recursive Schemas reach their `derived$Schema` binding by forward-reference [kyo-schema/shared/src/main/scala/kyo/internal/SchemaCodecRuntime.scala:84-117].

### The write/read inner loops

`SchemaCodecRuntime.writeProduct` reads field `i` via `product.productElement(i)`, special-cases `Maybe` and `Option` for omission, and otherwise emits `writer.fieldBytes(meta.nameBytes(i), meta.fieldIds(i))` followed by a recursive `SchemaSerializer.writeTo(schemas(i), raw, writer)`. This is the actual byte-loop the JIT sees [kyo-schema/shared/src/main/scala/kyo/internal/SchemaCodecRuntime.scala:121-150].

`SchemaCodecRuntime.readProduct` initialises every slot from `meta.defaults` / `Maybe.empty` / `None`, loops over `reader.hasNextField()`, matches names with `reader.matchField(meta.nameBytes(j))` (zero-allocation byte compare), and OR-s a `seen` bitmap against `meta.requiredMask` (after `reader.droppedFieldsMask(n)`) to enforce required fields [kyo-schema/shared/src/main/scala/kyo/internal/SchemaCodecRuntime.scala:152-212].

### Transform-aware dispatch: `hasTransforms`

`SchemaSerializer` is the second-tier dispatcher between the abstract `serializeWrite` / `serializeRead` and the real wire reader/writer; the public `Schema.writeTo` / `Schema.readFrom` (and `encode` / `encodeString` / `decode`) all funnel through it [kyo-schema/shared/src/main/scala/kyo/Schema.scala:1006-1018].

`SchemaSerializer.writeTo` branches on a single `hasTransforms` precomputed flag: clean derivations take the direct `serializeWrite` path, while drop / rename / add / discriminator transforms route through `writeWithTransforms`, which serializes to a `Structure.Value` first, applies transforms, then re-emits [kyo-schema/shared/src/main/scala/kyo/internal/SchemaSerializer.scala:14-23].

`hasTransforms` is precomputed once per Schema as a single boolean OR over the four transform-state fields, so the write hot path is a single branch instead of four `isEmpty` probes [kyo-schema/shared/src/main/scala/kyo/Schema.scala:91-96]:

```
private[kyo] val hasTransforms: Boolean =
    droppedFields.nonEmpty || renamedFields.nonEmpty ||
        computedFields.nonEmpty || discriminatorField.isDefined
```

The serde-parity naming layer adds one more transform-state field, `variantNaming: Schema.VariantNaming`, carrying the per-variant wire-name map, the variant/field `renameAll` case conventions, and the decode-alias maps. It is a SEPARATE constructor slot from `renamedFields`, so it composes with field `rename` / drop rather than replacing them [kyo-schema/shared/src/main/scala/kyo/Schema.scala:62-69]. It is ORed into BOTH `hasTransforms` (write) and `hasReadTransforms` (read); the trap is structural, a new transform-state field that misses either flag silently bypasses the transform on the corresponding hot path, so a configured rename would never run [kyo-schema/shared/src/main/scala/kyo/Schema.scala:114-123]. The rewriting lives at the `SchemaSerializer` runtime layer, NOT in the derivation macro: variant Schemas are emitted inline by the macro using raw Scala names, and `SchemaSerializer` resolves each name through the slot at serialize/deserialize time [kyo-schema/shared/src/main/scala/kyo/internal/SchemaSerializer.scala:116-138]. The five `Schema.NameCase` conventions tokenize with an acronym-aware two-pass lookahead (NOT a serde-exact split): an uppercase run is treated as one word but its last uppercase starts the next word, so `HTTPServer` becomes `http_server` (snake) / `httpServer` (camel) and `DList` becomes `d_list` / `dList` [kyo-schema/shared/src/main/scala/kyo/internal/NameCaseConversion.scala:37-76]. Encode resolution is explicit `variantNames` / `rename` first, then the convention, else the raw name; decode is primary-wins, the primary wire name resolves before any alias [kyo-schema/shared/src/main/scala/kyo/internal/SchemaSerializer.scala:392-401].

### Sum wire representations: the `representation` slot

`representation: Schema.SumRepresentation` is a SEPARATE constructor slot, a sibling to `variantNaming`, carrying which wire shape a sum type (sealed trait / enum) serializes as. It is `@publicInBinary private[kyo]`, threaded through every construction site (`Schema.init`, `initFocused`, `copyWith` all default it to `SumRepresentation.External`), and ORed into BOTH `hasTransforms` (write) and `hasReadTransforms` (read) via `representation.nonDefault` [kyo-schema/shared/src/main/scala/kyo/Schema.scala:70] [kyo-schema/shared/src/main/scala/kyo/Schema.scala:127-138]. `External` is the inert default: `nonDefault` is `false` only for `External`, so a clean derivation stays on the direct path; any other case forces the transform-aware engine path. The same structural trap that governs `variantNaming` governs this slot: a new transform-state field that misses either flag silently bypasses its hot path. A contributor adding a future representation extends the `SumRepresentation` enum, adds the slot's case to the encode dispatch in `SchemaSerializer.writeWithTransforms` and the decode dispatch in `Schema.transformedRead`, and nothing in the macro changes.

The six cases and how each is selected and what it puts on the wire [kyo-schema/shared/src/main/scala/kyo/Schema.scala:1418-1432]:

| Case | Builder | Wire shape |
|------|---------|-----------|
| `External` (default) | none | single-field wrapper object `{"Circle":{"radius":5.0}}` |
| `Internal(tagKey)` | `.discriminator("type")` | flat discriminator `{"type":"Circle","radius":5.0}` |
| `Adjacent(tagKey, contentKey)` | `.adjacent("type","content")` | two-field object `{"type":"Circle","content":{"radius":5.0}}` |
| `Tuple` | `.tupleTagged` | nested positional array `["Circle",{"radius":5.0}]` |
| `TupleFlat` | `.tupleFlat` | flattened positional array `["Triangle",10.0,10.0,10.0]` (payload field values spread positionally, field names dropped; a record-typed field is one nested element, not deep-flattened) |
| `Untagged` | `.untagged` | bare payload `{"radius":5.0}`, no tag or wrapper |

`discriminator` is retained as sugar: it sets both `discriminatorField` and `representation = Internal(fieldName)` in one `copyWith` [kyo-schema/shared/src/main/scala/kyo/Schema.scala:321-326]. The builders are mutually replacing on the single slot, except `tupleFlat` and `tupleTagged` which are documented as coexisting forms (each is a distinct case; the last builder called wins the slot).

All encode/decode rewriting for these cases lives in `SchemaSerializer` (the four-slot bottleneck), NOT in the derivation macro. The macro emits variant Schemas inline using raw Scala names and a `variantDecoders: Chunk[Codec.Reader => Any]` table of per-variant `serializeRead` thunks; `SchemaSerializer` reads `schema.representation` at serialize/deserialize time and rewrites accordingly [kyo-schema/shared/src/main/scala/kyo/internal/SchemaSerializer.scala:84-101] [kyo-schema/shared/src/main/scala/kyo/internal/FocusMacro.scala:977-989].

### Composition with the naming layer

The representation slot composes with the variant-naming layer; the two are orthogonal slots, not alternatives:

- **Tagged representations resolve the variant tag through the naming layer.** `Internal`, `Adjacent`, `Tuple`, and `TupleFlat` all run the variant wire name through `resolveVariantWire` (which honors `variantNames` / `renameAllVariants`), and decode reverse-resolves accepting any configured `variantAlias` [kyo-schema/shared/src/main/scala/kyo/internal/SchemaSerializer.scala:88-96] [kyo-schema/shared/src/main/scala/kyo/internal/SchemaSerializer.scala:593-603].
- **Untagged emits no tag**, so `variantNames` / `renameAllVariants` / `variantAlias` do not apply to a tag under `Untagged`. Field-level naming (`renameAllFields` / `alias`) still applies to the payload.
- **Field naming does NOT propagate from a sum schema to its variant payloads.** `renameAllFields` / `alias` configured on a sum schema rename that schema's OWN product fields (via `applyFieldConvention` over the materialized field Chunk), not the fields of a variant's payload [kyo-schema/shared/src/main/scala/kyo/internal/SchemaSerializer.scala:243-263]. Each variant payload is serialized by its own variant Schema (emitted inline by the macro), which does not inherit the parent sum's naming. This is consistent with how field naming has always worked: it is a property of the schema whose fields are being written, not a transitive one.
- **A case-class variant payload always serializes as an object/Record.** kyo-schema does not single-field-unwrap, so there is no bare-scalar adjacent/tuple content for a case-class variant; the `content` of an `Adjacent` or element 1 of a `Tuple` for a case-class variant is the variant's Record. A non-object payload (a bare scalar, an array, null) survives whole only when the variant's own payload is genuinely non-object.

### Codec capability for top-level non-object shapes

`Tuple`, `TupleFlat`, and `Untagged` produce a top-level array or bare top-level value, which a field-number-driven binary codec cannot express. Capability is a POSITIVE opt-in on the writer: `Codec.Writer.canWriteTopLevelNonObject` defaults to `false`, and a self-describing writer (Json, Yaml, Ion, MsgPack) overrides it to `true` [kyo-schema/shared/src/main/scala/kyo/Codec.scala:208-214]. `SchemaSerializer.requireTopLevelCapable` checks the flag BEFORE any bytes are written and raises `RepresentationUnsupportedException(writer.codecName, representation)` when it is unset; Protobuf hits this path [kyo-schema/shared/src/main/scala/kyo/internal/SchemaSerializer.scala:140-144]. The exception names the codec by its PUBLIC name through `Codec.Writer.codecName` (each concrete writer overrides this with the user-facing codec name, not the writer's class name) [kyo-schema/shared/src/main/scala/kyo/Codec.scala:216-221]. A new Writer that can express these shapes opts in by overriding `canWriteTopLevelNonObject` to `true`; omitting the override leaves the representation unsupported, which is the safe default.

### Decode: the reader-wrapper pattern and untagged capture/replay

Decode for the tagged representations uses a Reader-wrapper pattern. `DelegatingWrapperReader` is the abstract base [kyo-schema/shared/src/main/scala/kyo/internal/SchemaSerializer.scala:620-749]: each concrete subclass reads the wire format once in a private `readWire()`, records which variant was found, then re-presents the data in the `{variantName: payload}` wrapper shape the macro-generated `sealedReadBody` expects. After a field's sub-reader is captured, `delegateReader` holds it and `delegateDepth` tracks nested depth; every scalar and container call forwards to `delegateReader` when set. A subclass overrides `objectStartDirect` / `objectEndDirect` (the non-delegate paths) and the field-iteration methods (`field`, `fieldParse`, `matchField`, `lastFieldName`, `hasNextField`) for its representation. `DiscriminatorReader`, `AdjacentReader`, `TupleReader`, and `TupleFlatReader` are the four concrete subclasses; `Schema.transformedRead` dispatches to `SchemaSerializer.readWithDiscriminator` / `readAdjacent` / `readTuple` / `readTupleFlat` by the `representation` case [kyo-schema/shared/src/main/scala/kyo/Schema.scala:104-116]. A future representation adds a subclass of `DelegatingWrapperReader` overriding `readWire` and the field-iteration methods, plus a dispatch arm.

Untagged decode does NOT use the wrapper pattern. It captures the whole payload once, materializes it to an immutable `Structure.Value` through the codec's `IntrospectingReader.readStructure()`, then tries each entry of `schema.variantDecoders` in declaration order over a FRESH `StructureValueReader` per attempt (non-destructive replay), returning the first that decodes without a `DecodeException`; no match raises `NoVariantMatchException` listing the attempted variant wire names [kyo-schema/shared/src/main/scala/kyo/internal/SchemaSerializer.scala:537-563]. The capture-once + replay-per-variant mechanism REQUIRES a self-describing (introspecting) reader; a non-introspecting reader (Protobuf) cannot materialize the tree and surfaces `SchemaNotSerializableException`. `Result.Panic` is re-thrown (an unexpected error is not a no-match), only `Result.Failure` advances to the next variant.

Collision detection is convention-aware and order-independent on BOTH the variant and the field axis: two names resolving to one wire name surface as `VariantNameCollisionException` / `FieldNameCollisionException` (both `case class`es carrying `Chunk[String]` of the colliding names, extending `SchemaException with TransformException`) regardless of the order the conventions and explicit overrides were applied [kyo-schema/shared/src/main/scala/kyo/SchemaException.scala:110-119]. A variant Scala name (in `variantNames`) or a variant wire name (in `variantAlias`) that does not resolve raises `UnknownVariantException` at the CONFIG call, not at decode; its message branches on the path to distinguish a config-time Scala-name miss from a decode-time discriminator-value miss [kyo-schema/shared/src/main/scala/kyo/SchemaException.scala:45-52]. Variant naming (`variantNames` / `renameAllVariants` / `variantAlias`) applies ONLY under a discriminator; without `.discriminator(field)` the default wrapper-object format keeps the Scala variant names and the configuration is inert. Field naming (`renameAllFields` / `alias`) is independent of the discriminator [kyo-schema/shared/src/main/scala/kyo/Schema.scala:312-431].

`StructureValueWriter` and `StructureValueReader` are alternate `Writer` / `IntrospectingReader` implementations that build / consume an in-memory `Structure.Value` tree instead of a byte stream; they are how the transform path in `SchemaSerializer` rebuilds a value before re-emitting to the real wire [kyo-schema/shared/src/main/scala/kyo/internal/StructureValueWriter.scala:6-21].

### Hard limits and rejections

| Rule | Value | Where |
|------|-------|-------|
| Max case-class fields with `derives Schema` | 64 | Generated decoder uses a `Long` required-field bitmap; > 64 is a compile-time error, not silent truncation [kyo-schema/shared/src/main/scala/kyo/internal/FocusMacro.scala:385-392]. |
| Private case-class fields | Rejected | `Cannot derive Schema for ...: case-class field(s) ... are private.` Hand-roll a `given Schema` instead (mirror `structureFieldSchema`) [kyo-schema/shared/src/main/scala/kyo/internal/FocusMacro.scala:343-361]. |
| `Codec.Reader.maxDepth` default | 512 | Built into the abstract base, inherited uniformly [kyo-schema/shared/src/main/scala/kyo/Codec.scala:36-108]. |
| `Codec.Reader.maxCollectionSize` default | 100000 | Same [kyo-schema/shared/src/main/scala/kyo/Codec.scala:36-108]. |
| `Codec.Reader.droppedFieldsMask(n)` default | `0L` | Default means no fields are pre-satisfied; macro-generated decoder ORs this into its `seen` bitmap before required-field validation [kyo-schema/shared/src/main/scala/kyo/Codec.scala:98-108]. |

### Sealed-trait shape and variants

Wire shape for sealed traits is wrapper-format by default (`{"VariantName": ...}`); calling `.discriminator("type")` flips to a flat shape with a discriminator field [kyo-schema/shared/src/main/scala/kyo/Schema.scala:301-314]. The five alternate wire shapes (`Internal`, `Adjacent`, `Tuple`, `TupleFlat`, `Untagged`) and their builders are documented under [Sum wire representations](#sum-wire-representations-the-representation-slot); all of them are carried by the single `representation` slot and rewritten at the `SchemaSerializer` layer, never in the macro.

Variant Schemas of a sealed trait are emitted **inline** (not via `summonInline[Schema[Variant]]`). This is because the variant child does not necessarily carry its own `derives Schema`; an inline re-entry into `emitProductSchema` is how the trait's derivation reaches each variant. Field references back to the parent type still forward-reference through the parent's `derived$Schema` [kyo-schema/shared/src/main/scala/kyo/internal/FocusMacro.scala:569-575].

### Conventions a new method on `Schema` must follow

- The four abstract codec methods (`serializeWrite`, `serializeRead`, `getter`, `setter`) carry `@publicInBinary private[kyo]`; the class constructor is `@publicInBinary private[kyo]` too, anchoring the contract every concrete Schema overrides [kyo-schema/shared/src/main/scala/kyo/Schema.scala:55-69] [kyo-schema/shared/src/main/scala/kyo/Schema.scala:80-89].
- Hand-rolled Schemas that override the abstract codec methods carry the same `@publicInBinary private[kyo]` on each override, and override `structure` as a `lazy val` named `_structure` [kyo-schema/shared/src/main/scala/kyo/Structure.scala:357-423].
- New surface methods that traverse a focus take an inline `Focus.Select` lambda; the method itself is `inline` and forwards to a private `Schema.fieldCheck...` / `Schema.withField...` helper. Both `check` and `doc` follow that recipe: capture `rootSelect`, apply the lambda, delegate [kyo-schema/shared/src/main/scala/kyo/Schema.scala:249-255].
- Internal Schemas with no user call-site frame supply `Frame.internal` as a private given inside the new-instance body; that is the documented sentinel for that case [kyo-schema/shared/src/main/scala/kyo/Structure.scala:358-362].

## Conventions

### Exception hierarchy

`SchemaException` is a sealed abstract class extending `KyoException`, takes a `(using Frame)`, and lives in one file `SchemaException.scala` [kyo-schema/shared/src/main/scala/kyo/SchemaException.scala:8-10].

Operation-mode is carried by sealed marker traits that every leaf mixes in alongside the base [kyo-schema/shared/src/main/scala/kyo/SchemaException.scala:15-24]:

- `DecodeException`
- `ValidationException`
- `TransformException`
- `NavigationException`

Every concrete exception leaf is a `case class` taking field-level data plus `(using Frame)`, carries its message inline in `extends SchemaException(s"...")`, and mixes in one or more marker traits; navigation-relevant leaves derive `CanEqual` [kyo-schema/shared/src/main/scala/kyo/SchemaException.scala:29-31] [kyo-schema/shared/src/main/scala/kyo/SchemaException.scala:41-45]:

```
case class MissingFieldException(path: Seq[String], fieldName: String)(using Frame)
    extends SchemaException(s"Missing required field '$fieldName'" ...)
    with DecodeException with NavigationException derives CanEqual
```

Path-bearing exceptions format the path suffix through the shared `SchemaException.pathSuffix` helper; no leaf re-implements the formatting [kyo-schema/shared/src/main/scala/kyo/SchemaException.scala:127-129].

The sum-representation leaves follow the same template: `NoVariantMatchException(path, variants)` (untagged decode matched no variant; `DecodeException`) and `MissingTagKeyException(path, tagKey)` (adjacent decode input lacks the tag key; `DecodeException with NavigationException`) both carry their data plus `(using Frame)`, derive `CanEqual`, and surface as `Result.Failure` on decode. `RepresentationUnsupportedException(codec, representation)` is a `TransformException` raised on the WRITE path before any bytes when the codec cannot express the representation's wire shape; it carries the codec's public name (from `Codec.Writer.codecName`) and the representation name [kyo-schema/shared/src/main/scala/kyo/SchemaException.scala:59-75] [kyo-schema/shared/src/main/scala/kyo/SchemaException.scala:171-175].

### The `reader.frame` propagation rule

`Codec.Reader.frame` carries the user's decode call-site Frame; codec implementations MUST pass `using reader.frame` when throwing decode exceptions so the diagnostic points at the caller, not the codec's internal synthesis site [kyo-schema/shared/src/main/scala/kyo/Codec.scala:28-34].

This applies uniformly to every decode-time throw site:

- The `Result` sum-Schema and the `Either` sum-Schema pass `reader.frame` to `MissingFieldException` and `UnknownVariantException` [kyo-schema/shared/src/main/scala/kyo/Schema.scala:1745-1758] [kyo-schema/shared/src/main/scala/kyo/Schema.scala:1810-1818].
- The `Schema[Structure.Value]` and `Schema[Json.JsonSchema]` non-introspecting-reader guards attach `reader.frame` to the raised `SchemaNotSerializableException` [kyo-schema/shared/src/main/scala/kyo/Structure.scala:504-510].
- Macro-generated readers in `SchemaCodecRuntime` throw `MissingFieldException` / `UnknownVariantException` `using reader.frame` for required-field misses and unknown sum variants [kyo-schema/shared/src/main/scala/kyo/internal/SchemaCodecRuntime.scala:200-205].

### The return-type discriminator: throw or `Result`

The boundary between "throw inside, catch at the public edge" and "return `Result` directly" is operation-mode, not personal preference.

| Surface kind | Return shape | Pattern |
|--------------|-------------|---------|
| Public decode entry point (`Json.decode`, `Protobuf.decode`, `schema.decode`) | `Result[DecodeException, A]` | Wrap the throw-based reader call in `Result.catching[DecodeException]` [kyo-schema/shared/src/main/scala/kyo/Json.scala:62-70] [kyo-schema/shared/src/main/scala/kyo/Schema.scala:172-173]. |
| Internal reader implementation | Throws | Macro-generated and hand-rolled readers throw `MissingFieldException` / `UnknownVariantException` `using reader.frame` [kyo-schema/shared/src/main/scala/kyo/internal/SchemaCodecRuntime.scala:200-205]. |
| Navigation on `Structure.Path` | `Result[NavigationException, _]` | Navigation never throws across the public boundary; caller branches on a typed result [kyo-schema/shared/src/main/scala/kyo/Structure.scala:603-607]. |

The two examples in one file: `Json.decode` returns `Result[DecodeException, A]` via `Result.catching`, while `JsonReader` inside throws. `Structure.Path.get` returns `Result[NavigationException, Chunk[Value]]` directly without an internal throw bracket.

### Capability marker for self-describing readers

`Codec.IntrospectingReader` is the capability marker for self-describing wire formats (JSON, YAML, in-memory Structure source); binary codecs without per-value type tags (e.g. Protobuf) do not extend it. The identity `Schema[Structure.Value]` requires this capability, so the type system catches a `Structure.Value` decode through a non-introspecting codec at the point of mismatch rather than letting it surface as `UnknownVariantException` at runtime. **Binary codecs without per-value type tags MUST NOT extend `IntrospectingReader`** [kyo-schema/shared/src/main/scala/kyo/Codec.scala:146-163].

### `Structure.Type.Open` and the identity Schemas

`Structure.Type.Open` is the identity / shape-dynamic projection: a Schema carrying `Open` accepts and produces any wire shape. Compatibility with another `Open` is determined by tag equality, NOT structural recursion, and an `Open` type is NEVER compatible with any non-Open type [kyo-schema/shared/src/main/scala/kyo/Structure.scala:198-210].

`Schema[Structure.Value]` is the identity / open-shape Schema: writes preserve the shape Scala already has, reads accept whatever shape the wire carries via `Codec.IntrospectingReader.readStructure()`. Auto-deriving the enum would emit a tagged-union wrapper like `{"Record":{...}}` and refuse a plain JSON object [kyo-schema/shared/src/main/scala/kyo/Structure.scala:485-510].

### `PrimitiveKind` is exhaustive

`PrimitiveKind` is a closed enum that codec backends pattern-match exhaustively on; there is no silent fallback path. Adding a new primitive kind requires the enum extension AND every consumer's match branch; this is enforced by exhaustiveness, not by convention [kyo-schema/shared/src/main/scala/kyo/Structure.scala:84-94].

The wire shape of `Structure.Type` itself is explicitly hand-pinned by `given Schema[Structure.Type] = Schema.derived` so that any change to the sum's variant set is gated by a code change to this given. Adding a new `Type` variant changes the wire shape; the explicit declaration is the review gate [kyo-schema/shared/src/main/scala/kyo/Structure.scala:255-261].

### Hand-rolled `Structure.Field` Schema

`Structure.Field`'s wire shape is a hand-rolled 5-key object (`name, fieldType, doc, default, optional`). An auto-derived Schema would emit the private storage member `_fieldType: Function0[Structure.Type]` as a wire field, which is wrong on two counts: the wire name would be the storage name (not the public `fieldType`), and the wire field type would be `Function0[Structure.Type]` for which no Schema can be summoned. New private case-fields anywhere in `kyo-schema` need the same hand-roll [kyo-schema/shared/src/main/scala/kyo/Structure.scala:340-356].

The hand-rolled reader uses the canonical `Codec.Reader` contract: `hasNextField()` as the loop predicate, `fieldParse()` advances past the field name, `lastFieldName()` returns the just-parsed name. The same contract is named in the Reader's scaladoc and reused by macro-generated readers [kyo-schema/shared/src/main/scala/kyo/Structure.scala:351-391].

### Schema acquisition: derive, summon, or hand-roll

- User-defined product/sum types acquire their Schema by `derives Schema`; the macro path produces the wire shape. Tuples in `Schema` follow the same pattern via explicit `Schema.derived` (e.g. `given tuple2Schema[A: Schema, B: Schema]: Schema[(A, B)] = Schema.derived`) [kyo-schema/shared/src/main/scala/kyo/Schema.scala:1697-1700].
- Enums that participate in the wire shape and `CanEqual` derive both in one clause: `derives CanEqual, Schema` (`PrimitiveKind`, `PathSegment`) [kyo-schema/shared/src/main/scala/kyo/Structure.scala:89-94].
- There is no parallel companion-typeclass path: hand-rolled givens use `Schema.init` and become indistinguishable from derived ones to the rest of the module [kyo-schema/shared/src/main/scala/kyo/Schema.scala:1216-1225].

### `Focus.Select` and the lattice mode

`Focus.Select[A, V]` is the lambda-navigator type used by every navigation-by-lambda surface (`Schema.check(_.field)`, `Schema.drop(_.field)`, `Compare.changed(_.field)`, `Builder.name`). It is a `Dynamic` with ONLY `selectDynamic` so it cannot collide with any user-defined field name. Adding new navigation methods to `Select` itself would reintroduce field-name collisions [kyo-schema/shared/src/main/scala/kyo/Schema.scala:1079-1095] [kyo-schema/shared/src/main/scala/kyo/Focus.scala:198-220].

`Focus[Root, Value, Mode[_]]` is the post-navigation lens triple `(getter, setter, updateFn)` plus a back-reference to its `Schema[Root]`. `Mode[_]` forms a lattice `Id < Maybe < Chunk`. Crossing a sum-variant boundary or a collection boundary widens the mode; the lattice is encoded in the type, so a contributor cannot accidentally compose a Chunk-mode Focus back down to an Id-mode one [kyo-schema/shared/src/main/scala/kyo/Focus.scala:10-17].

### Stable Protobuf field IDs

`CodecMacro.fieldId(name)` is a 21-bit `MurmurHash3` over field names used as the stable Protobuf field number, so adding / removing fields does not collide on the wire [kyo-schema/shared/src/main/scala/kyo/internal/CodecMacro.scala:6-19]:

```
def fieldId(name: String): Int =
    (MurmurHash3.stringHash(name) & 0x1fffff) + 1
```

## Extension Recipes

### Add a Schema for a new primitive

Define a `given <name>Schema: Schema[A]` in `object Schema`, inlining `Schema.init[A]` with the typed `Writer.<prim>` / `Reader.<prim>` calls and a `Structure.Type.Primitive` descriptor [kyo-schema/shared/src/main/scala/kyo/Schema.scala:1267-1271]:

```
given intSchema: Schema[Int] = Schema.init[Int](
    writeFn = (v, w) => w.int(v),
    readFn = _.int(),
    structure = Structure.Type.Primitive(Structure.PrimitiveKind.Int, Tag[Int].asInstanceOf[Tag[Any]])
)
```

The `Tag[A].asInstanceOf[Tag[Any]]` cast is the existing pattern for the positional `Tag` slot.

### Add a Schema for a string-like primitive (UUID, LocalDate, LocalDateTime, Instant)

Keep `Structure.PrimitiveKind.String`, use `w.string(v.toString)` on write and `parse(r.string())` on read; the structure kind reflects the wire shape, not the Scala type [kyo-schema/shared/src/main/scala/kyo/Schema.scala:1401-1406]:

```
given localDateTimeSchema: Schema[java.time.LocalDateTime] =
    Schema.init[java.time.LocalDateTime](
        writeFn = (v, w) => w.string(v.toString),
        readFn = r => java.time.LocalDateTime.parse(r.string()),
        structure = Structure.Type.Primitive(Structure.PrimitiveKind.String, Tag[Any])
    )
```

**Trap (`Tag[Any]` for Java time types)**: `LocalDateTime` falls back to `Tag[Any]` (not `Tag[java.time.LocalDateTime].asInstanceOf[Tag[Any]]`) because of a Scala 3 inline limitation with Java class tags. Copy that pattern when adding a `java.time.*` primitive whose `Tag` does not synthesize cleanly in an inline structural position; the wire shape is unaffected [kyo-schema/shared/src/main/scala/kyo/Schema.scala:1397-1406].

### Add a Schema for a new container (one type parameter)

Write a non-inline given parameterized by an inner Schema and `frame`, using `Structure.Type.Collection` for the structure shape; the inner Schema is summoned via `using`, not via `summonInline`, and the positional Tag is `Tag[Any]` because non-inline givens have no implicit `Tag[A]` in scope [kyo-schema/shared/src/main/scala/kyo/Schema.scala:1534-1560]:

```
given chunkSchema[A](using inner: Schema[A], frame: Frame): Schema[Chunk[A]] =
    Schema.init[Chunk[A]](
        writeFn = ...,                            // arrayStart / foreach(inner.serializeWrite) / arrayEnd
        readFn  = ...,                            // arrayStart / @tailrec loop / arrayEnd
        structure = Structure.Type.Collection("Chunk", Tag[Any], inner.structure)
    )
```

Container readers wrap the per-element loop in `@tailrec` and call `reader.checkCollectionSize(count)` before each element to enforce the DoS limit; copy this shape verbatim for any new array-backed container [kyo-schema/shared/src/main/scala/kyo/Schema.scala:1457-1465]:

```
@tailrec
def loop(count: Int): Unit =
    if reader.hasNextElement() then
        reader.checkCollectionSize(count)
        builder += inner.serializeRead(reader)
        loop(count + 1)
loop(1)
reader.arrayEnd()
```

`listSchema` and `vectorSchema` follow the exact same recipe; only the collection-name string and builder change [kyo-schema/shared/src/main/scala/kyo/Schema.scala:1447-1473].

### Add an Optional-style container (null-on-absent encoding)

Match on the present/absent variants on write (writing `writer.nil()` for absent), check `reader.isNil()` on read, and use `Structure.Type.Optional(name, Tag[Any], inner.structure)` as the structure shape [kyo-schema/shared/src/main/scala/kyo/Schema.scala:1621-1638]:

```
given maybeSchema[A](using inner: Schema[A], frame: Frame): Schema[Maybe[A]] = ...
```

### Add a key-value container

Write/read with `writer.mapStart(size)` / `reader.mapStart()` and emit `writer.field(k, idx)` per entry; the structure is `Structure.Type.Mapping(name, Tag[Any], keyStructure, valueStructure)`. The `Map[String, V]` case uses the inline String-primitive node for the key [kyo-schema/shared/src/main/scala/kyo/Schema.scala:1661-1693]:

```
given stringMapSchema[V](using valueSchema: Schema[V], frame: Frame): Schema[Map[String, V]] = ...
```

### Add a newtype / opaque-type Schema

Call `.transform[B](to)(from)` on the underlying primitive's Schema in the companion object; the resulting Schema delegates encode/decode through the wrapper and shares the underlying structure [kyo-schema/shared/src/main/scala/kyo/Schema.scala:899-921]:

```
opaque type Email = String
object Email:
    given Schema[Email] = Schema.stringSchema.transform[Email](identity)(identity)
```

### Add `derives Schema` support for a user ADT

There is no recipe. `case class Foo(...) derives Schema` or `sealed trait Bar derives Schema` is the entire surface; the macro walks `sym.caseFields` (case classes) or `sym.children` (sealed traits) and resolves each nested type via `summonInline[Schema[ft]]`, so any user `given Schema[X]` already in implicit scope plugs in without macro changes [kyo-schema/shared/src/main/scala/kyo/internal/FocusMacro.scala:315-340].

Two compile-time refusals to be aware of:

- **Private case-fields**: rejected because the macro would otherwise emit the private storage name on the wire; for a type with private case-fields, hand-roll a `given Schema` instead [kyo-schema/shared/src/main/scala/kyo/internal/FocusMacro.scala:343-360].
- **More than 64 fields**: rejected because the generated decoder packs required-field tracking into a `Long` bitmap; for wider types, refactor the type or hand-roll a Schema [kyo-schema/shared/src/main/scala/kyo/internal/FocusMacro.scala:389-391].

### Add a custom Schema for a user-defined container at a nested field position

Write the data type plus a non-inline given (the container's Schema is summoned via `using`, not `summonInline`) [kyo-schema/shared/src/test/scala/kyo/SchemaCustomContainerNestedTest.scala:13-35]:

```
case class Box[A](item: A) derives CanEqual
given boxSchema[A](using inner: Schema[A], frame: Frame): Schema[Box[A]] =
    Schema.init[Box[A]](...)
```

### Add a new wire format (Codec)

Extend `abstract class Codec` with `def newWriter(): Codec.Writer` and `def newReader(input: Span[Byte])(using Frame): Codec.Reader`, then provide concrete `Codec.Writer` / `Codec.Reader` subclasses for the format. Schema-derived traversal is format-agnostic; adding a new wire format does NOT touch `Schema` or `SchemaCodecRuntime` [kyo-schema/shared/src/main/scala/kyo/Codec.scala:5-24].

Minimum shape: a `final class` extending `Codec`, the two factory methods delegating to package-private `kyo.internal.<Format>Writer` / `kyo.internal.<Format>Reader` implementations of `Codec.Writer` / `Codec.Reader`. Mirror `Json` (the simplest one) when wiring a new format [kyo-schema/shared/src/main/scala/kyo/Json.scala:3-7]:

```
final class Json extends Codec:
    def newWriter(): Codec.Writer = kyo.internal.JsonWriter()
    def newReader(input: Span[Byte])(using Frame): Codec.Reader =
        kyo.internal.JsonReader(input)
```

Each codec's companion object provides a `given <Codec> = <Codec>()` so codec-polymorphic call sites (e.g. `summon[Json].newWriter()`) resolve without explicit codec construction [kyo-schema/shared/src/main/scala/kyo/Json.scala:27]:

```
given Json = Json()
```

A codec that carries configuration (Yaml's `writerConfig`) uses a default-argument constructor to keep the `given Yaml = Yaml()` form valid while letting callers construct configured instances [kyo-schema/shared/src/main/scala/kyo/Yaml.scala:16-23]:

```
final class Yaml(writerConfig: Yaml.WriterConfig = Yaml.WriterConfig.Default) extends Codec:
```

If the new format is **self-describing** (can materialize an arbitrary wire value into `Structure.Value` without a schema), its Reader subclass should also extend `Codec.IntrospectingReader` and implement `readStructure(): Structure.Value`. Json and Yaml extend it; Protobuf does not [kyo-schema/shared/src/main/scala/kyo/Codec.scala:146-163].

## Testing

### Base trait and equality

Every test suite extends `kyo.test.Test[Any]`, never ScalaTest directly [kyo-schema/shared/src/test/scala/kyo/SchemaTest.scala:14]. `Test[Any]` is the explicit Scala 3 spelling for the common case where leaves need only the baseline effects; kyo-schema suites never widen `S` [kyo-test/api/shared/src/main/scala/kyo/test/Test.scala:12].

Every suite opens with `given CanEqual[Any, Any] = CanEqual.derived` so heterogeneous `==` comparisons inside `assert` compile under strict equality [kyo-schema/shared/src/test/scala/kyo/SchemaTest.scala:16].

Internal-package tests under `shared/src/test/scala/kyo/internal/` follow the same base-class convention [kyo-schema/shared/src/test/scala/kyo/internal/FastFloatTest.scala:26].

When two `Structure.Type` instances must be compared with `==` (tag equality), add a local `given CanEqual[Structure.Type, Structure.Type] = CanEqual.derived` alongside the baseline `Any` instance [kyo-schema/shared/src/test/scala/kyo/StructureTest.scala:62-63].

### Assertion styles, by kind

**Round-trip** is the canonical behavioral check: write with the format API, read back, assert equality with the original value [kyo-schema/shared/src/test/scala/kyo/JsonTest.scala:28-33]:

```
val person = MTPerson("Bob", 25)
val bytes  = Json.encodeBytes[MTPerson](person)
val result = Json.decodeBytes[MTPerson](bytes).getOrThrow
assert(result == person)
```

`CodecTest` factors round-trip into a `CodecTestHelper.roundTrip[A]` that drives an in-memory `TestWriter` -> `TestReader` token stream through `schema.writeTo` / `schema.readFrom`, so primitive and case-class round-trips share one helper [kyo-schema/shared/src/test/scala/kyo/CodecTest.scala:288-301].

**Token-shape** assertions verify the exact ordered token stream the writer emits, pinning wire shape independently of decoder symmetry [kyo-schema/shared/src/test/scala/kyo/CodecTest.scala:363-377].

**Structure-tree** assertions cast `Schema[A].structure` to the expected `Structure.Type.Product` / `Sum` / `Collection` / `Primitive` variant and assert on `.fields`, `.name`, `.elementType`, and `.typeParams` [kyo-schema/shared/src/test/scala/kyo/SchemaCustomContainerNestedTest.scala:55-58]:

```
val product = holder.structure.asInstanceOf[Structure.Type.Product]
```

When two distinct summons of a polymorphic `given def` Schema cannot be compared by reference, use `Structure.Type.compatible` for structural equality and pattern-match the variant for shape [kyo-schema/shared/src/test/scala/kyo/SchemaCustomContainerNestedTest.scala:59-74]:

```
assert(
    Structure.Type.compatible(fieldType, boxInt.structure),
    s"expected structural compat with boxSchema[Int].structure but got $fieldType"
)
```

**Decode results** return `Result[A]`; tests assert success either by `.getOrThrow` followed by value equality, or by `assert(result == Result.succeed(expected))` [kyo-schema/shared/src/test/scala/kyo/JsonTest.scala:75-78]. Failure cases on `Json.decode` are asserted with `.isFailure`, never `try / catch` [kyo-schema/shared/src/test/scala/kyo/JsonTest.scala:81-85].

**Trap (cast without fallback)**: an `asInstanceOf[Structure.Type.Product]` (or any other variant) without a matching `case _ => fail(s"...")` arm produces a `ClassCastException` instead of a readable assertion message. Pair every cast with either a guard pattern-match plus `fail`, or `assert` right after the cast [kyo-schema/shared/src/test/scala/kyo/SchemaCustomContainerNestedTest.scala:68-74].

### Compile-time tests

**Type-resolution-only leaves** use a typed `val _: Schema[X] { type Focused = ... } = m` ascription and discharge with `succeed("type-resolution compile check: ...")`; there is no runtime equality to assert because the compile is the verification [kyo-schema/shared/src/test/scala/kyo/SchemaTest.scala:24-28].

**Negative compile checks** (focus on a nonexistent field, defaults access on a field without a default) use `typeCheckFailure(src)(expectedSubstring)` from the kyo-test base, asserting both that the snippet does not compile and that the error mentions the right token [kyo-schema/shared/src/test/scala/kyo/SchemaTest.scala:137-139]:

```
typeCheckFailure("Schema[kyo.MTPerson].focus(_.nonexistent)")("not found")
```

**`derives`-clause failure tests**: when the failing snippet must include a `derives` clause that itself fails (so it cannot be lifted into `typeCheckFailure`'s context), drive `scala.compiletime.testing.typeChecks` / `typeCheckErrors` directly and assert on the head error's message [kyo-schema/shared/src/test/scala/kyo/SchemaTest.scala:7239-7246]:

```
val src      = "case class Bad(private val x: Int) derives kyo.Schema"
val compiles = scala.compiletime.testing.typeChecks(src)
```

### Recursive-ADT regression guards

Recursive ADTs MUST be derivable without `StackOverflowError`; the regression test touches the full structure tree of a self-recursive `case class Tree(children: List[Tree])` to exercise the cycle-break path [kyo-schema/shared/src/test/scala/kyo/SchemaTest.scala:7205-7220]:

```
"Self-recursive case class derives Schema without StackOverflow" in { ... }
```

The `Box[Holder]` regression guard exercises a user-defined container at a recursive position; the cycle-break is purely structural (the outer Schema's `lazy val structure` plus the by-name `Structure.Field._fieldType`), and any failure surfaces as `StackOverflowError` [kyo-schema/shared/src/test/scala/kyo/SchemaCustomContainerNestedTest.scala:98-120].

The generic-sealed-trait regression guards that `buildSumSchema` populates `Structure.Type.Sum.typeParams`; the test names the CR it pins [kyo-schema/shared/src/test/scala/kyo/StructureTest.scala:314-322]:

```
"derived generic sealed trait populates typeParams" in {
    // Regression guard for CR-r1-003
```

### Fixtures: shared vs. ad-hoc

Recursive ADT fixtures used as shared regression carriers live in `SchemaTestData.scala` and ship `derives Schema` (sometimes alongside `derives CanEqual`) so multiple suites can summon their Schema without redeclaring it [kyo-schema/shared/src/test/scala/kyo/SchemaTestData.scala:93]:

```
case class TreeNode(value: Int, children: List[TreeNode]) derives CanEqual, Schema
```

**Trap (orphan fixture)**: adding a recursive ADT to `SchemaTestData.scala` without `derives Schema` (or an explicit companion `given Schema[X] = Schema.derived[X]`) breaks every downstream suite that summons it; the existing `TreeNode` / `Expr` / `RTDepartment` / `RTEmployee` fixtures are the templates for both shapes [kyo-schema/shared/src/test/scala/kyo/SchemaTestData.scala:93].

Mutually recursive ADTs whose cycle crosses two companions use explicit `given Schema[X] = Schema.derived[X]` in each companion (instead of `derives Schema` on the type) so the derivation summons see a present given on the recursive backedge [kyo-schema/shared/src/test/scala/kyo/SchemaTestData.scala:95-102]:

```
object RTDepartment:
    given Schema[RTDepartment] = Schema.derived[RTDepartment]
```

Generic sealed-trait regression fixtures (`GenericSealed[A]`) live as top-level test types in the test source's package so they have a stable `typeParams` to inspect; variants are kept concrete to avoid forcing the macro to substitute the parent's type argument [kyo-schema/shared/src/test/scala/kyo/StructureTest.scala:44-49].

Per-test ad-hoc recursive case classes are declared inside the `in { ... }` block when the regression is local to that leaf, never bled into the shared fixture file [kyo-schema/shared/src/test/scala/kyo/SchemaCustomContainerNestedTest.scala:84]:

```
case class BoxedHolder(payload: Box[BoxedHolder]) derives CanEqual, Schema
```

Structure-introspection helpers shared across a single suite are declared once as `private def` on the suite class (e.g. `toStructure` / `fromStructure` in `StructureTest`), not duplicated per leaf [kyo-schema/shared/src/test/scala/kyo/StructureTest.scala:65-74].

### Cross-platform and timing

All behavioral tests for kyo-schema live in `shared/src/test`; the module's test tree has no `jvm/src/test`, `js/src/test`, `native/src/test`, or `wasm/src/test` directory and no platform-tagged leaves [build.sbt:586-597]. `.withKyoTest` wires the per-platform `kyo-test-runner` jar onto the test classpath and registers the platform's `TestFramework`; this is what makes a single `class FooTest extends kyo.test.Test[Any]` in `shared/src/test` runnable on JVM, JS, Native, and Wasm [project/WithKyoTest.scala:38-72].

kyo-schema does not override `Test / parallelExecution`, `Test / fork`, or `Test / testForkedParallel`; its tests run under the kyo-settings defaults inherited from the build, with no per-platform test-config overrides [build.sbt:586-597].

Test leaves are pure synchronous bodies; there is no `Async.sleep`, `Clock.sleep`, `Latch`, `Channel`, `Fiber.get`, `Thread.sleep`, or `synchronized` anywhere in the kyo-schema test tree. Schema correctness is data-equality, not a race, so the deterministic-timing toolkit other modules need does not apply here [kyo-schema/shared/src/test/scala/kyo/].

## Decision Checklist: Before Adding a New X

Run through this list before touching the derivation path or adding a new public surface.

1. **Is this a new primitive, container, optional, map, newtype, ADT, or codec?** Pick the matching recipe above and copy it verbatim. The recipes are mature; deviating without a specific reason is how regressions enter.
2. **For a new primitive or container**, does the structure carry the correct `PrimitiveKind` / `Structure.Type.Collection` / `Optional` / `Mapping` variant? `PrimitiveKind` is closed and exhaustively matched by every codec; adding a kind is not a one-file change [kyo-schema/shared/src/main/scala/kyo/Structure.scala:84-94].
3. **For a container**, is the reader `@tailrec` and does it call `reader.checkCollectionSize(count)` before each element? The DoS limit is enforced per-element, not at the top of the loop [kyo-schema/shared/src/main/scala/kyo/Schema.scala:1457-1465].
4. **For a hand-rolled Schema with a recursive structure**, is `_structure` a `lazy val` and is `Structure.Field._fieldType` constructed by-name? Both cycle-breaks are required jointly. Strictly evaluating either side is how recursive derivations deadlock [kyo-schema/shared/src/main/scala/kyo/Schema.scala:1127-1150] [kyo-schema/shared/src/main/scala/kyo/Structure.scala:320-338].
5. **For a hand-rolled Schema with private case-fields** (e.g. the storage thunk in `Structure.Field`), have you provided an explicit `given` rather than relying on derivation? The macro rejects private case-fields at compile time [kyo-schema/shared/src/main/scala/kyo/internal/FocusMacro.scala:343-360].
6. **For a hand-rolled Schema with custom `equals`**, does it force the by-name thunks before comparing structures? The default case-class `equals` compares `Function0` references and reports `false` for structurally identical instances [kyo-schema/shared/src/main/scala/kyo/Structure.scala:294-306].
7. **For a new variant of `Structure.Type`**, have you updated the hand-pinned `given Schema[Structure.Type] = Schema.derived` and every exhaustive `PrimitiveKind` match in the codec backends? The wire shape is gated by code review at this given [kyo-schema/shared/src/main/scala/kyo/Structure.scala:255-261].
8. **For a new decode throw site**, is it `using reader.frame` so the diagnostic points at the user's call-site, not the codec? [kyo-schema/shared/src/main/scala/kyo/Codec.scala:28-34].
9. **For a new public decode entry point**, does it return `Result[DecodeException, A]` by wrapping a throw-based reader call in `Result.catching[DecodeException]`? Navigation surfaces return `Result[NavigationException, _]` directly [kyo-schema/shared/src/main/scala/kyo/Json.scala:62-70] [kyo-schema/shared/src/main/scala/kyo/Structure.scala:603-607].
10. **For a new exception leaf**, is it a `case class` mixing one of the four marker traits (`DecodeException`, `ValidationException`, `TransformException`, `NavigationException`) into `SchemaException`, with `(using Frame)` and the message inline? Does it derive `CanEqual` if it appears in a navigation path? [kyo-schema/shared/src/main/scala/kyo/SchemaException.scala:15-45].
11. **For a new Codec**, is it a `final class extends Codec` with two factory methods delegating to `kyo.internal.<Format>Writer` / `kyo.internal.<Format>Reader`, plus a `given <Codec> = <Codec>()` in the companion? Does the Reader extend `Codec.IntrospectingReader` if (and only if) the format is self-describing? [kyo-schema/shared/src/main/scala/kyo/Json.scala:3-27] [kyo-schema/shared/src/main/scala/kyo/Codec.scala:146-163].
12. **For a derivation-touching change**, does the macro still emit ONE call into `SchemaCodecRuntime.buildProductSchema` / `buildSumSchema` per derived type, with all per-field metadata in one string literal? Per-field branching inside the emitted methods is what blows the JVM class-file limit on test classes with many derivations [kyo-schema/shared/src/main/scala/kyo/internal/SchemaCodecRuntime.scala:7-37] [kyo-schema/shared/src/main/scala/kyo/internal/FocusMacro.scala:367-373].
13. **For a regression on the recursive-derivation path**, have you added a test in `shared/src/test` that exercises the full structure tree (not just `schema.structure`, the children's children too)? Recursive ADT fixtures shared across suites belong in `SchemaTestData.scala` with `derives Schema`; per-leaf ad-hoc recursive types belong in the `in { ... }` block [kyo-schema/shared/src/test/scala/kyo/SchemaTest.scala:7205-7220] [kyo-schema/shared/src/test/scala/kyo/SchemaTestData.scala:93].
14. **For a new platform-specific file**, is the JVM/JS/Native/Wasm split genuinely required by a platform capability difference (like `AsciiStringFactory`'s LATIN1 trick)? If not, keep it in `shared/`. The module has exactly one platform-specific surface today [kyo-schema/jvm/src/main/scala/kyo/internal/AsciiStringFactory.scala:7-25].
15. **For a new sum wire representation**, have you (a) added the case to `Schema.SumRepresentation` and confirmed `nonDefault` returns `true` for it (so it reaches the transform-aware path through both `hasTransforms` and `hasReadTransforms`), (b) added the encode arm in `SchemaSerializer.writeWithTransforms` and the decode arm in `Schema.transformedRead`, (c) added a `DelegatingWrapperReader` subclass (for a tagged shape) or a capture/replay path (for an untagged shape), and (d) gated any top-level-array / bare-scalar shape behind `requireTopLevelCapable` so it raises `RepresentationUnsupportedException` on a codec that has not opted in via `canWriteTopLevelNonObject`? The macro does NOT change: variant Schemas stay inline and the representation is resolved at the `SchemaSerializer` layer [kyo-schema/shared/src/main/scala/kyo/Schema.scala:104-138] [kyo-schema/shared/src/main/scala/kyo/internal/SchemaSerializer.scala:84-144] [kyo-schema/shared/src/main/scala/kyo/internal/SchemaSerializer.scala:620-749].
