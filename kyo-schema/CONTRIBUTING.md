# Contributing to kyo-schema

This guide complements the root [CONTRIBUTING.md](../CONTRIBUTING.md), which covers global Kyo conventions (naming, `Maybe` / `Result` / `Chunk` / `Span`, `using`-clause ordering, Frame/Tag, inline guidelines, scaladoc, file organisation, visibility tiers, KyoException, the test framework, cross-platform source placement, AllowUnsafe). Defer to the root guide for those; this file covers only what is specific to kyo-schema.

**The headline invariant**: a `Schema[A]` is a four-slot value (`serializeWrite`, `serializeRead`, `getter`, `setter`) plus a lazy `Structure.Type` projection, and the derivation macro emits **one** runtime call per `derives Schema`, never per-field branching. Two cycle-breaks (a by-name `Structure.Field._fieldType` thunk and a `lazy val _structure` on every Schema) are what let recursive type graphs derive without `StackOverflowError`; the structural-emit / runtime-walk split is what keeps test classes with many derivations under JVM class-file limits. Internalise both halves before changing anything in the derivation path.

**Configuration is carried by constructor SLOTS, gated behind two boolean flags.** Every wire-shaping feature (representation, representation chains, variant naming, field omission, union ambiguity, drop/rename/add transforms, strict-unknown-field decode, decode defaults, predicate omission, per-field codec transforms) is a `private[kyo]` constructor slot on `Schema` with an inert default, threaded through four construction sites (`init`, `initFocused`, `createWithFocused`, `copyWith`) plus the `SchemaFactory.createFrom` rebuild path, and ORed into `hasTransforms` (write) and/or `hasReadTransforms` (read). An unconfigured schema has both flags `false` and encodes/decodes byte-identically on the direct path. Before adding ANY slot, read [The dual-flag invariant](#the-dual-flag-invariant) and [Adding a slot to Schema](#adding-a-slot-to-schema-the-construction-site-and-bytecode-traps): a missed flag or a missed construction site compiles cleanly and silently drops the feature, and a slot insertion needs a HARD clean (nuke `target/`, `.bloop/`, `.bsp/`, `project/`), not `sbt clean`.

## Architecture at a Glance

kyo-schema is an eight-module family: the format-agnostic core (`kyo-schema`: Schema, derivation, the `Codec` SPI, annotations, validation, optics, structural conversion), six published format modules that each contribute one wire-format entry point on top of it (`kyo-schema-json`, `kyo-schema-protobuf`, `kyo-schema-msgpack`, `kyo-schema-bson`, `kyo-schema-ion`, `kyo-schema-yaml`), and the unpublished `kyo-schema-tests`, which depends on the core plus all six formats and hosts the cross-format test suites and the `kyo-schema/README.md` doctest validation [the `kyo-schema*` project definitions in `build.sbt`]. Everything in this guide applies across the family: a format module is just the core's `Codec` contract implemented in its own artifact.

The core depends only on `kyo-data` (no `kyo-kernel`, no `kyo-prelude`, no effect runtime), so it remains adoptable as a standalone serialization library. Most format modules add only the core. `kyo-schema-json` additionally depends on `kyo-system` because it includes the effectful `Jsonl` streaming and file API [`kyo-schema-json` in `build.sbt`].

The JSON module keeps its pure and effectful layers separate inside one artifact. `kyo.JsonLines`, exported as `Json.Lines`, owns immutable framing plus whole-input decode and encode. `Jsonl` owns the `Sync`, `Async`, `Scope`, `Stream`, and `Path` drivers over that same framer. New parsing rules belong in `JsonLines.Framer`; new stream and file drivers belong in `Jsonl`.

Dependency direction is strictly downward: `internal/` may not import from outside `internal/` other than the public types it implements, and the public surface calls into `internal/` exclusively through named entry points; there is no cycle through `internal -> public -> internal` [`Schema.derived`] [`SchemaDerivedMacro` in `internal/FocusMacro.scala`].

| Layer | Files | Role |
|-------|-------|------|
| Public surface (core) | `Schema.scala`, `Codec.scala`, `Structure.scala`, `SchemaException.scala`, `Focus.scala`, `Builder.scala`, `Compare.scala`, `Changeset.scala`, `Convert.scala`, `Modify.scala` | The four-slot Schema, the Codec abstraction, the exception hierarchy, the navigation/transform helpers. |
| Format entry points | `kyo-schema-json/.../kyo/Json.scala` + `JsonLines.scala` + `Jsonl.scala`, `kyo-schema-protobuf/.../kyo/Protobuf.scala`, `kyo-schema-msgpack/.../kyo/MsgPack.scala`, `kyo-schema-bson/.../kyo/Bson.scala`, `kyo-schema-ion/.../kyo/Ion.scala` + `IonBinary.scala` + `IonSchema.scala`, `kyo-schema-yaml/.../kyo/Yaml.scala` | One public codec entry point per format module, with JSON also providing pure and effectful JSONL surfaces. |
| Annotation surface | `schema/SchemaAnnotation.scala` (package `kyo.schema`, the only file outside package `kyo`) | The marker base `SchemaAnnotation`, the capture-filtering `AnnotationPolicy`, the built-in annotation set (`@rename`, `@alias`, `@discriminator`, `@adjacent`, `@untagged`, `@transient`, `@transform`, `@omit`, `@doc`, and the scoped `@proto.fieldNumber`), and the `Transformer` / `OmitPredicate` object-reference families. See [Annotation-driven configuration](#annotation-driven-configuration). |
| Macro boundary | `object SchemaDerivedMacro`, at the end of `internal/FocusMacro.scala` | Thin boundary that immediately delegates to `FocusMacro.derivedImpl`. It is a separate object so it gets its own class file, which is what lets the suspended `derives Schema` units load it; it shares `FocusMacro.scala` so zinc can never invalidate one without the other. Both halves are load-bearing: see [The derivation macro boundary](#the-derivation-macro-boundary) [`SchemaDerivedMacro` and its scaladoc]. |
| Macros | `internal/FocusMacro.scala`, `internal/SchemaTransformMacro.scala`, `internal/NavigationMacro.scala`, `internal/ExpandMacro.scala`, `internal/MacroUtils.scala`, `internal/CodecMacro.scala` | Derivation, transforms (drop/rename/add/select/flatten), navigation, structural-shape expansion, shared quote-reflection utilities, and the `fieldId` XXH32 helper. |
| Runtime engine | `internal/SchemaCodecRuntime.scala`, `internal/SchemaSerializer.scala`, `internal/SchemaFactory.scala`, `internal/StructureValueWriter.scala`, `internal/StructureValueReader.scala` | The runtime walk fed by the macro-emitted metadata table; the transform-aware dispatcher; the path-key recomputation factory; the in-memory `Structure.Value` writer/reader. |
| Wire formats | Each format module's `internal/`: e.g. `kyo-schema-json/.../internal/JsonWriter.scala` + `JsonReader.scala` + `JsonSchemaEnricher.scala`, `kyo-schema-protobuf/.../internal/ProtobufWriter.scala` + `ProtobufReader.scala`, `kyo-schema-yaml/.../internal/YamlWriter.scala` + `YamlReader.scala` + `YamlParser.scala` + `YamlEventReader.scala` | Concrete `Codec.Writer` / `Codec.Reader` implementations. The math sublayers they share (`Ryu` for float write, `FastFloat` for float read) stay in the core's `internal/`. |
| Platform-specific tooling | `kyo-schema/jvm/.../tools/FastFloatPow10Gen.scala` | Runtime format implementations are shared across platforms. The core retains one JVM-only build-time table generator. |

### Representative call flow

For `case class User(name: String, age: Int) derives Schema` and `Json.encode(user)`, the path is:

1. `derives Schema` resolves to `Schema.derived`, an `inline given derived` that splices `SchemaDerivedMacro.derivedImpl` [`Schema.derived`].
2. `SchemaDerivedMacro` delegates to `FocusMacro.derivedImpl` [`SchemaDerivedMacro.derivedImpl`].
3. `FocusMacro.derivedImpl` walks `sym.caseFields`, emits one `summonInline[Schema[ft]]` thunk per field, and emits a single call to `SchemaCodecRuntime.buildProductSchema[User]` carrying the precomputed `ProductFieldsMeta` and the thunk array [the `FocusMacro` scaladoc] [kyo-schema/shared/src/main/scala/kyo/internal/SchemaCodecRuntime.scala].
4. `buildProductSchema` constructs a fresh `new Schema[User] { ... }` whose `serializeWrite` / `serializeRead` each contain one call back into `writeProduct` / `readProduct` [kyo-schema/shared/src/main/scala/kyo/internal/SchemaCodecRuntime.scala].
5. At call time `Json.encode(user)` -> `summon[Json].newWriter()` -> `kyo.internal.JsonWriter()`, then `schema.writeTo(value, w)` -> `internal.SchemaSerializer.writeTo` -> `schema.serializeWrite` (direct branch, no transforms) -> `SchemaCodecRuntime.writeProduct`, which walks `meta.names` / `meta.fieldIds` / `meta.nameBytes` and calls `writer.fieldBytes(...)` followed by a recursive `SchemaSerializer.writeTo(schemas(i), raw, writer)` per field [`Json.encode`] [kyo-schema/shared/src/main/scala/kyo/internal/SchemaCodecRuntime.scala].

### Cross-platform split

Runtime format implementations are shared across JVM, Scala.js, Scala Native, and WASM. In particular, `JsonWriter.resultString` constructs the result from the written byte range with the public UTF-8 `String` constructor in shared source, so JSON string construction does not depend on platform-specific runtime code or private JDK APIs [`JsonWriter.resultString`].

`FastFloatPow10Gen` is a build-only tool in the core's `jvm/src/main/scala/kyo/internal/tools/`, never linked into the runtime; it regenerates the checked-in `FastFloatPow10Table` for the Eisel-Lemire fast-path [the `FastFloatPow10Gen` scaladoc].

## The Headline: Four-Slot Schema, Structural-Emit, Runtime-Walk

### Goal

`Schema.derived` must simultaneously support **recursive type graphs** (a `case class Tree(children: List[Tree])` must derive without `StackOverflowError`) and **codebases with many derivations** (a test class with hundreds of `derives Schema` cases must compile without exceeding JVM class-file limits). The architecture solves both with one design.

### The four-slot abstraction

A public `Schema[A]` is a single abstract class with four `private[kyo]` abstract methods (`serializeWrite`, `serializeRead`, `getter`, `setter`); every other surface method delegates to one of them, so the whole module is bottle-necked through four function-shaped slots [the `Schema` class declaration].

Each concrete `Schema[A]` MUST also supply `structure: Structure.Type`. The method is abstract on the class; omitting the `structure` argument when calling `Schema.init` is a compile error. The structure is the sole source of truth for "what shape does this Schema produce on the wire": it is consumed by `Json.JsonSchema.from[A]`, `Protobuf.ProtoSchema.from[A]`, and the case-class derivation macro to build product/sum structures [`Schema.structure`].

### The single supported construction surface

`Schema.init` (and the `Schema.initFocused` variant) is the canonical factory. It is `inline` so the caller's four lambdas substitute directly into the abstract method bodies of a fresh `new Schema[A] { ... }` subclass; no `Function` closure is allocated per derivation [`Schema.init`]. `@nowarn("msg=anonymous")` is the documented suppression for that inline-expansion pattern.

The `structure` parameter on `Schema.init` is by-name (`structure: => Structure.Type`); the implementation captures it via `lazy val _structure = structure`. Container givens pass `inner.structure`, and the lazy capture is what prevents initialization cycles in recursive structure type graphs [`Schema.init`].

### Cycle-break one: `lazy val _structure`

Every `Schema[A]` carries its `Structure.Type` lazily via `lazy val _structure` so recursive type graphs construct without forcing the inner Schema's structure mid-init [`Schema.init`, `Schema.initFocused`]. This is the outer half of the recursion break.

### Cycle-break two: by-name `Structure.Field._fieldType`

`Structure.Field` holds `_fieldType` as a `() => Structure.Type` thunk; the public accessor `def fieldType` forces it [`Structure.Field.fieldType`]. The by-name `Field.apply` is the only contract callers may use to build a `Structure.Field`. Both the strict caller form (`Structure.Field("x", someStructure, ...)`) and the macro emission (`Structure.Field("x", summonInline[Schema[t]].structure, ...)`) rely on it deferring the structure's evaluation until the first `field.fieldType` read. Strictly evaluating the inner structure here is the canonical way to deadlock the recursive-derivation contract [`Structure.Field.apply` and its scaladoc].

**Trap (case-class accessors expose the thunk)**: the case-class-generated `productElement(1)` / `unapply` / `copy` for `Structure.Field` expose the storage member `_fieldType` (a `Function0[Structure.Type]`), NOT the forced value. A contributor reading `productElement(1)` and expecting a `Structure.Type` will get a thunk. Read field type only through the public `fieldType` accessor [the `Structure.Field` scaladoc].

**Trap (default equals compares function references)**: the default case-class `equals` on `Structure.Field` would compare wrapping `Function0` references and report `false` for structurally identical Fields. A hand-rolled `equals` forces both thunks and compares the structures via `.equals`. `Structure.Type` has no `CanEqual`; reach for `.equals` directly. New `Structure.Type` variants must support `.equals` [`Structure.Field.equals`].

### Structural-emit + runtime-walk

The derivation macro is a **structural emitter**, not a per-type specializer. It walks `sym.caseFields` (for case classes) or `sym.children` (for sealed traits) and, for each field/variant, emits a thunk wrapping `scala.compiletime.summonInline[Schema[ft]]`. It assembles a runtime field/variant table consumed by `SchemaCodecRuntime`. **The macro never pattern-matches on a specific container or primitive type symbol**: every nested type resolves via `summonInline` at the inline-expansion phase, which sees forward-references to the in-flight `derived$Schema` and so handles recursion without any special-case in the macro [the `FocusMacro` scaladoc] [`FocusMacro.derivedImpl`].

The emission has **constant per-method bytecode**: `serializeWrite` / `serializeRead` each call a single runtime helper passing the precomputed field-entry table. The table itself uses `summonInline[Schema[ft]]` thunks that resolve at the inline-expansion phase, with the in-flight `derived$Schema` visible by forward-reference [`FocusMacro.derivedImpl`].

The JVM class-file limit drives this architecture: keeping per-derivation inline bytecode constant-sized is what allows test classes with many `derives Schema` to compile. **Reintroducing per-field branching back into the emitted methods is what gets a test class to exceed the limit** [kyo-schema/shared/src/main/scala/kyo/internal/SchemaCodecRuntime.scala] [`FocusMacro.derivedImpl`].

All per-field metadata is packed into ONE compile-time string literal `name<TAB>flags;...` that the runtime `ProductFieldsMeta` inflates into parallel arrays once at construction. The macro deliberately emits a single string constant rather than N field-meta values to keep emission small [kyo-schema/shared/src/main/scala/kyo/internal/SchemaCodecRuntime.scala].

The runtime helper walks the per-field thunk array lazily: a single `lazy val schemas: Array[Schema[Any]] = schemasBuilder()` forces the entire field-Schema array on first use; per-field schemas are then indexed by position in `writeProduct` / `readProduct`. The thunk indirection is what lets recursive Schemas reach their `derived$Schema` binding by forward-reference [kyo-schema/shared/src/main/scala/kyo/internal/SchemaCodecRuntime.scala].

### The write/read inner loops

`SchemaCodecRuntime.writeProduct` reads field `i` via `product.productElement(i)`, special-cases `Maybe` and `Option` for omission, and otherwise emits `writer.fieldBytes(meta.nameBytes(i), meta.fieldIds(i))` followed by a recursive `SchemaSerializer.writeTo(schemas(i), raw, writer)`. This is the actual byte-loop the JIT sees [kyo-schema/shared/src/main/scala/kyo/internal/SchemaCodecRuntime.scala].

`SchemaCodecRuntime.readProduct` initialises every slot from `meta.defaults` / `Maybe.empty` / `None`, loops over `reader.hasNextField()`, matches names with `reader.matchField(meta.nameBytes(j))` (zero-allocation byte compare), and OR-s a `seen` bitmap against `meta.requiredMask` (after `reader.droppedFieldsMask(n)`) to enforce required fields [kyo-schema/shared/src/main/scala/kyo/internal/SchemaCodecRuntime.scala].

### Transform-aware dispatch: `hasTransforms`

`SchemaSerializer` is the second-tier dispatcher between the abstract `serializeWrite` / `serializeRead` and the real wire reader/writer; the public `Schema.writeTo` / `Schema.readFrom` (and `encode` / `encodeString` / `decode`) all funnel through it [`Schema.writeTo`, `Schema.readFrom`].

`SchemaSerializer.writeTo` branches on a single `hasTransforms` precomputed flag: clean derivations take the direct `serializeWrite` path, while drop / rename / add / discriminator transforms route through `writeWithTransforms`, which serializes to a `Structure.Value` first, applies transforms, then re-emits [`SchemaSerializer.writeTo`].

`hasTransforms` is precomputed once per Schema as a single boolean OR over the four transform-state fields, so the write hot path is a single branch instead of four `isEmpty` probes [`Schema.hasTransforms`]:

```
private[kyo] val hasTransforms: Boolean =
    droppedFields.nonEmpty || renamedFields.nonEmpty ||
        computedFields.nonEmpty || discriminatorField.isDefined
```

The serde-parity naming layer adds one more transform-state field, `variantNaming: Schema.VariantNaming`, carrying the per-variant wire-name map, the variant/field `renameAll` case conventions, and the decode-alias maps. It is a SEPARATE constructor slot from `renamedFields`, so it composes with field `rename` / drop rather than replacing them [`Schema.variantNaming`]. It is ORed into BOTH `hasTransforms` (write) and `hasReadTransforms` (read); the trap is structural, a new transform-state field that misses either flag silently bypasses the transform on the corresponding hot path, so a configured rename would never run [`Schema.hasTransforms`, `Schema.hasReadTransforms`]. The rewriting lives at the `SchemaSerializer` runtime layer, NOT in the derivation macro: variant Schemas are emitted inline by the macro using raw Scala names, and `SchemaSerializer` resolves each name through the slot at serialize/deserialize time [`SchemaSerializer.writeWithTransforms`]. The five `Schema.NameCase` conventions tokenize with an acronym-aware two-pass lookahead (NOT a serde-exact split): an uppercase run is treated as one word but its last uppercase starts the next word, so `HTTPServer` becomes `http_server` (snake) / `httpServer` (camel) and `DList` becomes `d_list` / `dList` [`NameCaseConversion.tokenize`]. Encode resolution is explicit `variantNames` / `rename` first, then the convention, else the raw name; decode is primary-wins, the primary wire name resolves before any alias [`SchemaSerializer.resolveVariantWire`].

### Sum wire representations: the `representation` slot

`representation: Schema.UnionRepresentation` is a SEPARATE constructor slot, a sibling to `variantNaming`, carrying which wire shape a UNION type (sealed trait, enum, or a Scala `A | B` type union) serializes as. The enum is named `UnionRepresentation` (one taxonomy across all three union forms), not `SumRepresentation`. It is `@publicInBinary private[kyo]`, threaded through every construction site (`init`, `initFocused`, `createWithFocused`, `copyWith` all default it to `UnionRepresentation.External`), and ORed into BOTH `hasTransforms` (write) and `hasReadTransforms` (read) via `representation.nonDefault` [`Schema.representation`] [`Schema.hasTransforms`, `Schema.hasReadTransforms`]. `External` is the inert default: `nonDefault` is `false` only for `External`, so a clean derivation stays on the direct path; any other case forces the transform-aware engine path. The same structural trap that governs `variantNaming` governs this slot: a new transform-state field that misses either flag silently bypasses its hot path (see [The dual-flag invariant](#the-dual-flag-invariant)). A contributor adding a future representation extends the `UnionRepresentation` enum, adds the slot's case to the encode dispatch in `SchemaSerializer.writeWithTransforms` and the decode dispatch in `Schema.transformedRead`, and nothing in the macro changes.

The six cases and how each is selected and what it puts on the wire [`Schema.UnionRepresentation`]:

| Case | Builder | Wire shape |
|------|---------|-----------|
| `External` (default) | none | single-field wrapper object `{"Circle":{"radius":5.0}}` |
| `Internal(tagKey)` | `.discriminator("type")` | flat discriminator `{"type":"Circle","radius":5.0}` |
| `Adjacent(tagKey, contentKey)` | `.adjacent("type","content")` | two-field object `{"type":"Circle","content":{"radius":5.0}}` |
| `Tuple` | `.tupleTagged` | nested positional array `["Circle",{"radius":5.0}]` |
| `TupleFlat` | `.tupleFlat` | flattened positional array `["Triangle",10.0,10.0,10.0]` (payload field values spread positionally, field names dropped; a record-typed field is one nested element, not deep-flattened) |
| `Untagged` | `.untagged` | bare payload `{"radius":5.0}`, no tag or wrapper |

`discriminator` is retained as sugar: it sets both `discriminatorField` and `representation = Internal(fieldName)` in one `copyWith` [`Schema.discriminator`]. The builders are mutually replacing on the single slot, except `tupleFlat` and `tupleTagged` which are documented as coexisting forms (each is a distinct case; the last builder called wins the slot).

All encode/decode rewriting for these cases lives in `SchemaSerializer` (the four-slot bottleneck), NOT in the derivation macro. The macro emits variant Schemas inline using raw Scala names and a `variantDecoders: Chunk[Codec.Reader => Any]` table of per-variant `serializeRead` thunks; `SchemaSerializer` reads `schema.representation` at serialize/deserialize time and rewrites accordingly [`SchemaSerializer.writeWithTransforms`] [`FocusMacro.derivedImpl`].

### Composition with the naming layer

The representation slot composes with the variant-naming layer; the two are orthogonal slots, not alternatives:

- **Tagged representations resolve the variant tag through the naming layer.** `Internal`, `Adjacent`, `Tuple`, and `TupleFlat` all run the variant wire name through `resolveVariantWire` (which honors `variantNames` / `renameAllVariants`), and decode reverse-resolves accepting any configured `variantAlias` [`SchemaSerializer.resolveVariantWire`] [`SchemaSerializer.readFrom`].
- **Untagged emits no tag**, so `variantNames` / `renameAllVariants` / `variantAlias` do not apply to a tag under `Untagged`. Field-level naming (`renameAllFields` / `alias`) still applies to the payload.
- **Field naming does NOT propagate from a sum schema to its variant payloads.** `renameAllFields` / `alias` configured on a sum schema rename that schema's OWN product fields (via `applyFieldConvention` over the materialized field Chunk), not the fields of a variant's payload [`SchemaSerializer.applyFieldConvention`]. Each variant payload is serialized by its own variant Schema (emitted inline by the macro), which does not inherit the parent sum's naming. This is consistent with how field naming has always worked: it is a property of the schema whose fields are being written, not a transitive one.
- **A case-class variant payload always serializes as an object/Record.** kyo-schema does not single-field-unwrap, so there is no bare-scalar adjacent/tuple content for a case-class variant; the `content` of an `Adjacent` or element 1 of a `Tuple` for a case-class variant is the variant's Record. A non-object payload (a bare scalar, an array, null) survives whole only when the variant's own payload is genuinely non-object.

### Codec capability for top-level non-object shapes

`Tuple`, `TupleFlat`, and `Untagged` produce a top-level array or bare top-level value, which a field-number-driven binary codec cannot express. Capability is a POSITIVE opt-in on the writer: `Codec.Writer.canWriteTopLevelNonObject` defaults to `false`, and a self-describing writer (Json, Yaml, Ion, MsgPack) overrides it to `true` [`Codec.Writer.canWriteTopLevelNonObject`]. `SchemaSerializer.requireTopLevelCapable` checks the flag BEFORE any bytes are written and raises `RepresentationUnsupportedException(writer.codecName, representation)` when it is unset; Protobuf hits this path [`SchemaSerializer.requireTopLevelCapable`]. The exception names the codec by its PUBLIC name through `Codec.Writer.codecName` (each concrete writer overrides this with the user-facing codec name, not the writer's class name) [`Codec.Writer.codecName`]. A new Writer that can express these shapes opts in by overriding `canWriteTopLevelNonObject` to `true`; omitting the override leaves the representation unsupported, which is the safe default.

The capability is ALSO projected into a structured descriptor `Codec.Capabilities` (a `final case class` with a single boolean axis `canWriteTopLevelNonObject` today; future axes add fields without changing the selection arity), via the default `Codec.Writer.capabilities` body that reads the existing `canWriteTopLevelNonObject` opt-in [`Codec.Capabilities`] [`Codec.Writer.capabilities`]. The descriptor is the surface a chain-bearing schema consults (see [Representation fallback chains](#representation-fallback-chains)); because the default body derives from the existing opt-in, an EXTERNAL codec (one defined outside kyo-schema) participates in chain selection with no kyo-schema source change. A Writer with a richer capability profile overrides `capabilities` directly.

### Decode: the reader-wrapper pattern and untagged capture/replay

Decode for the tagged representations uses a Reader-wrapper pattern. `DelegatingWrapperReader` is the abstract base [`SchemaSerializer.DelegatingWrapperReader`]: each concrete subclass reads the wire format once in a private `readWire()`, records which variant was found, then re-presents the data in the `{variantName: payload}` wrapper shape the macro-generated `sealedReadBody` expects. After a field's sub-reader is captured, `delegateReader` holds it and `delegateDepth` tracks nested depth; every scalar and container call forwards to `delegateReader` when set. A subclass overrides `objectStartDirect` / `objectEndDirect` (the non-delegate paths) and the field-iteration methods (`field`, `fieldParse`, `matchField`, `lastFieldName`, `hasNextField`) for its representation. `DiscriminatorReader`, `AdjacentReader`, `TupleReader`, and `TupleFlatReader` are the four concrete subclasses; `Schema.transformedRead` dispatches to `SchemaSerializer.readWithDiscriminator` / `readAdjacent` / `readTuple` / `readTupleFlat` by the `representation` case. The dispatch first branches on `representationChain`: a present chain routes to `readChain` (see [Representation fallback chains](#representation-fallback-chains)), and only an absent chain falls through to the single-`representation` switch [`Schema.transformedRead`]. A future representation adds a subclass of `DelegatingWrapperReader` overriding `readWire` and the field-iteration methods, plus a dispatch arm.

Untagged decode does NOT use the wrapper pattern. It captures the whole payload once, materializes it to an immutable `Structure.Value` through the codec's `IntrospectingReader.readStructure()`, then tries each entry of `schema.variantDecoders` in declaration order over a FRESH `StructureValueReader` per attempt (non-destructive replay), returning the first that decodes without a `DecodeException`; no match raises `NoVariantMatchException` listing the attempted variant wire names [`SchemaSerializer.readUntagged`]. The capture-once + replay-per-variant mechanism REQUIRES a self-describing (introspecting) reader; a non-introspecting reader (Protobuf) cannot materialize the tree and surfaces `SchemaNotSerializableException`. `Result.Panic` is re-thrown (an unexpected error is not a no-match), only `Result.Failure` advances to the next variant.

Collision detection is convention-aware and order-independent on BOTH the variant and the field axis: two names resolving to one wire name surface as `VariantNameCollisionException` / `FieldNameCollisionException` (both `case class`es carrying `Chunk[String]` of the colliding names, extending `SchemaException with TransformException`) regardless of the order the conventions and explicit overrides were applied [`VariantNameCollisionException`, `FieldNameCollisionException`]. A variant Scala name (in `variantNames`) or a variant wire name (in `variantAlias`) that does not resolve raises `UnknownVariantException` at the CONFIG call, not at decode; its message branches on the path to distinguish a config-time Scala-name miss from a decode-time discriminator-value miss [`UnknownVariantException`]. Variant naming (`variantNames` / `renameAllVariants` / `variantAlias`) applies ONLY under a discriminator; without `.discriminator(field)` the default wrapper-object format keeps the Scala variant names and the configuration is inert. Field naming (`renameAllFields` / `alias`) is independent of the discriminator [`Schema.variantNames`, `Schema.renameAllVariants`, `Schema.variantAlias`, `Schema.renameAllFields`].

`StructureValueWriter` and `StructureValueReader` are alternate `Writer` / `IntrospectingReader` implementations that build / consume an in-memory `Structure.Value` tree instead of a byte stream; they are how the transform path in `SchemaSerializer` rebuilds a value before re-emitting to the real wire [the `StructureValueWriter` scaladoc].

### Representation fallback chains

A sum schema can carry an ORDERED chain of representations rather than a single one, so one schema degrades gracefully across codecs of differing capability. The chain lives in a SEPARATE slot, `representationChain: Maybe[Chunk[Schema.UnionRepresentation]]` (`Maybe.empty` is the no-chain default), a sibling to `representation` [`Schema.representationChain`]. Three builders configure it [`Schema.representations`, `Schema.orElseRepresentation`, `Schema.representationFor`]:

- `representations(first, rest*)` sets the full chain explicitly and sets `representation = first` (the primary).
- `orElseRepresentation(fallback)` is sugar over `representations(currentPrimary, fallback)`, where the current primary is this schema's existing `representation`.
- `representationFor(capabilities)` is the total, deterministic query: it returns the highest-priority chain entry the capabilities admit, or the single `representation` when no chain is set. `External` is always admitted and is the chain floor, so it never throws.

Both encode and decode walk the chain, but in different directions and with different selection rules:

- **Encode selects the single highest-priority expressible entry.** `SchemaSerializer.selectRepresentation` finds the first chain entry that `Schema.representationExpressibleBy(rep, writer.capabilities)` admits, then emits ONLY that representation; if EVERY entry is inexpressible it throws `RepresentationUnsupportedException` naming the codec and the joined chain, before any bytes are written [`SchemaSerializer.selectRepresentation`] [`Schema.representationExpressibleBy`]. `representationExpressibleBy` is the single predicate both encode-time selection and `representationFor` consult: the three top-level non-object shapes (`Tuple` / `TupleFlat` / `Untagged`) require `canWriteTopLevelNonObject`; the object-shaped representations (`External` / `Internal` / `Adjacent`) are always expressible.
- **Decode tries the chain in DECLARED order over a fresh reader.** `SchemaSerializer.readChain` captures the wire once (requires a self-describing reader, else `SchemaNotSerializableException`), then tries each entry's per-representation read path over a FRESH `StructureValueReader` per attempt (non-destructive replay, the same capture-once + replay model as `readUntagged`), returning the first that decodes without a `SchemaException`. A `Result.Panic` is re-thrown immediately, never folded into a no-match; if no entry parses, the last attempt's failure surfaces [`SchemaSerializer.readChain`]. A chain entry of `Internal(tagKey)` decodes through `readWithDiscriminatorField`, which takes the tag key from the chain entry directly, so the chain works even when `.discriminator` was never called [`SchemaSerializer.readWithDiscriminatorField`].

The chain slot is ORed into BOTH `hasTransforms` and `hasReadTransforms` via `representationChain.isDefined` (see [The dual-flag invariant](#the-dual-flag-invariant)). An empty or duplicate-containing chain is rejected with `DuplicateRepresentationException` at the BUILDER call (`Schema.checkRepresentationChain`), never silently normalized [`Schema.checkRepresentationChain`] [`DuplicateRepresentationException`]. The macro does not change for chains; the whole mechanism lives at the `SchemaSerializer` layer.

### Field omission on encode

A schema can omit optional or empty-collection fields from the wire instead of writing a null-valued or empty key. Three slots carry the policy, all ORed into both transform flags [`Schema.omitPolicies`, `Schema.omitNoneAll`, `Schema.omitEmptyCollectionsAll`]:

- `omitNone` sets `omitNoneAll`: drop any field whose encoded value is `Null` (None / `Maybe.empty`) [`Schema.omitNone`].
- `omitEmptyCollections` sets `omitEmptyCollectionsAll`: drop any collection/map field whose encoded value is empty, AND decode-default a missing such field to the typed empty value [`Schema.omitEmptyCollections`].
- `omit(_.field).whenEmpty` / `.whenNone` set a per-field entry in `omitPolicies: Chunk[(String, OmitPolicy)]`; a per-field entry SHADOWS the schema-wide flags for that field [`Schema.omit`] [`Schema.OmitWhen.whenEmpty`, `Schema.OmitWhen.whenNone`]. `omit` is a `transparent inline` that splices through `SchemaTransformMacro.omitFocusImpl`: the macro extracts the source field name at compile time and returns a `Schema.OmitWhen[A, F]` carrier the user finishes with `whenEmpty` / `whenNone` [`SchemaTransformMacro.omitFocusImpl`].

The omit gate is TYPE-AWARE, and this is the load-bearing invariant: `WhenEmpty` / `omitEmptyCollectionsAll` fires ONLY for a field whose declared `Field.tag` is a collection, set, or map (`isCollectionOrMapTag`), NEVER a product or sum [`SchemaSerializer.isCollectionOrMapTag`]. The reason is structural: an empty product or case object ALSO materializes as an empty `Structure.Value.Record` on encode, so a shape-only "is this an empty Record?" test would wrongly drop an empty nested product. Both the empty-shape test AND the declared-type predicate must hold [`SchemaSerializer.omitField`]. `WhenNone` excludes `Option` / `Maybe` from the decode-default set because the macro already seeds those to `None`.

THREE code paths share the SAME `isCollectionOrMapTag` predicate, and they must never diverge:

1. **Encode-omit** drops the field via `omitField` [`SchemaSerializer.omitField`].
2. **Decode-default** re-injects the typed empty for a missing slot. The macro hard-seeds collection slots to `null` and never calls `zeroForField`, so a missing collection field needs a SYNTHETIC wire value, not a defaults entry: `readWithTransforms` builds `omitDefaultedNames` (the `WhenEmpty`-configured collection/map fields) and one `SyntheticField` per name, and a `TransformAwareReader` drains `_pendingSyntheticFields` after the inner reader is exhausted, replaying each field's `Structure.Value` through a `StructureValueReader` for every field not already seen from the wire [`SchemaSerializer.readWithTransforms`] [`SchemaSerializer.SyntheticField`] [`SchemaSerializer.TransformAwareReader`].
3. **`zeroForField`'s typed-empty seeding** uses the same Map-before-List/Vector/Set/Chunk/Seq ordering [`Schema.zeroForField`].

A mapping field (`Map`, `Dict`, `OrderedDict`) takes its injected empty value from `emptyMappingWireValue`, an empty `Structure.Value.MapEntries`, rather than from `zeroForField` [`SchemaSerializer.emptyMappingWireValue`]. Two reasons, and the second is the invariant to keep: `Dict` and `OrderedDict` erase to a bare `Span`-backed array that `zeroToStructureValue` has no shape to match, and a mapping's wire form belongs to the given bound at the field rather than to the declared type. The object-form and array-of-pairs givens of all three types declare a byte-identical `Structure.Type.Mapping`, so an injected value chosen from the declared key type is a guess, and it was wrong for an array-form given bound at a `String` key. `MapEntries` needs no guess: it is what `StructureValueWriter` produces for an empty mapping under both forms, and `StructureValueReader` accepts it under both protocols (`mapStart` presents the entries as object fields, `arrayStart` as the array-of-`{key, value}` envelope). Do not narrow it back to a key-type choice.

The decode-default set is built only from collection/map fields under a `WhenEmpty` policy (per-field) or `omitEmptyCollectionsAll`; a `WhenNone` field is excluded from it (Option/Maybe is already None-seeded by the macro) [`SchemaSerializer.readWithTransforms`].

### Serialization customization: the four field builders

Beyond the omit / rename / representation surface above, four builders shape per-field decode strictness, decode defaults, predicate-driven encode omission, and per-field custom wire form. All four are slots on `Schema[A]`, all four sit BEHIND the dual-flag gate, and none of them touch the derivation macro: the macro keeps emitting raw-name variant Schemas and the customization is resolved at the `SchemaSerializer` layer. Each follows the same slot precedent (a `private[kyo]` carrier the builder or macro populates, threaded through all four construction sites, ORed into the right flag). Read [The dual-flag invariant](#the-dual-flag-invariant) and [Adding a slot to Schema](#adding-a-slot-to-schema-the-construction-site-and-bytecode-traps) before extending any of them.

The carriers (`OmitPolicy`, `FieldDefault`, `FieldTransform`, plus `VariantNaming`) are `private[kyo]` case classes / enums that the builders and macros populate; a user NEVER constructs one. `OmitPolicy` and `FieldDefault` / `FieldTransform` are reached only through the builder method names (`when`, `whenDefault`, `default`, `transformField`), never by hand [`Schema.OmitPolicy`, `Schema.FieldDefault`, `Schema.FieldTransform`]. A new slot follows this same precedent: a `private[kyo]` carrier, a builder that splices it, no public constructor.

The customization slots and which flag(s) they OR into [`Schema.hasTransforms`, `Schema.hasReadTransforms`]:

| Slot | Builder | Flag(s) |
|------|---------|---------|
| `denyUnknownFieldsEnabled: Boolean` | `denyUnknownFields` | `hasReadTransforms` only (decode-only) |
| `fieldDefaults: Chunk[(String, FieldDefault)]` | `default(_.f)(supplier)` | `hasReadTransforms` only (decode-only) |
| `omitPolicies` entry `When` / `WhenDefault` (+ `fieldMaterializedDefaults`) | `omit(_.f).when` / `.whenDefault` | `hasTransforms` and `hasReadTransforms` (omit slot is in both) |
| `fieldTransforms: Chunk[(String, FieldTransform[A])]` | `transformField` / `transformFieldWrite` / `transformFieldRead` | `fieldTransforms.nonEmpty` ORs into BOTH (`hasTransforms` write, `hasReadTransforms` read) |

The `fieldTransforms.nonEmpty` clause appears in BOTH flag bodies even for a write-only or read-only transform; that is deliberate. A transform carries optional `write` and optional `read`, so the slot's presence cannot tell the flag which direction is configured, and the dispatcher decides per-direction inside `writeWithTransforms` / `readWithTransforms` by checking `transform.write.isDefined` / `transform.read.isDefined`. ORing the slot into both flags keeps a write-only or read-only configuration from silently bypassing whichever direction it does configure [`Schema.hasTransforms`] [`Schema.hasReadTransforms`] [`SchemaSerializer.writeWithTransforms`] [`SchemaSerializer.readWithTransforms`].

#### `denyUnknownFields`: strict decode

`denyUnknownFields` is a TOTAL builder (no `Frame`, no focus lambda): it sets `denyUnknownFieldsEnabled = true` via `copyWith` and returns the schema unchanged on encode [`Schema.denyUnknownFields`]. With it set, a wire field the schema does not account for raises `UnknownFieldException` (a `case class` mixing `DecodeException`, NOT `NavigationException` because the failure is malformed input rather than user navigation) [`UnknownFieldException`].

The check fires AFTER field naming, aliases, and schema transforms, so a renamed-away, aliased, flatten-child, or synthetic-injected field is NOT "unknown". The gate is `strictUnknownField()` in `TransformAwareReader`: it raises only when the field was neither a synthetic injection (`_syntheticField`), nor matched by the macro decoder (`_matchedField`), nor a known flatten-child (`flattenedReadFields.contains(_rawFieldName)`); the raise happens inside `skip()`, the path the macro decoder takes for a field it did not consume [`TransformAwareReader.strictUnknownField`]. Because the predicate keys off the post-translation `_matchedField` / `_syntheticField` state, every legitimately-accounted-for field has already flipped one of those flags by the time `skip()` runs.

#### `default(_.field)(supplier)`: decode-time default

`default` is a `transparent inline` taking `(using Frame)` and a by-name `supplier: => V`; it splices through `SchemaTransformMacro.defaultFocusImpl`, which extracts the focus field name at compile time (resolving through `renamedFields` to the SOURCE name), summons the field's `Schema[V]`, and installs a `FieldDefault(() => supplier, writeDefault)` keyed by source name [`Schema.default`] [`SchemaTransformMacro.defaultFocusImpl`]. `FieldDefault.writeDefault` writes the supplied value through the field's own derived schema, so the default takes the same codec path as an ordinary field write [`Schema.FieldDefault`].

Precedence on decode is configured supplier > Scala default > tag zero. The mechanism: `readWithTransforms` turns each `fieldDefaults` entry into a `SyntheticField(name, () => materializeDefault(...))`; `TransformAwareReader` drains the synthetic fields ONLY after `inner.hasNextField()` is exhausted and ONLY for names not already in `_seenFromWire`, so a field present on the wire keeps its wire value and the supplier never runs [`SchemaSerializer.readWithTransforms`] [`SchemaSerializer.TransformAwareReader`]. When the field IS absent, the synthetic injection presents the materialized supplier value to the macro decoder's normal field-read path, so it wins over the macro's own `meta.defaults` seeding (the Scala default) and over the tag zero the macro would otherwise leave. The supplier is by-name and `field.value()` is invoked exactly once at injection time, so it is evaluated at most once per decode and only when the field is absent.

#### `omit(_.field).when` / `.whenDefault`: predicate-driven encode omission

`when` and `whenDefault` extend the existing `omit` builder (which produces the `OmitWhen[A, F]` carrier through `omitFocusImpl`), adding two more `OmitPolicy` cases beside `WhenEmpty` / `WhenNone`. Both are ENCODE-ONLY [`Schema.OmitWhen.when`, `Schema.OmitWhen.whenDefault`] [`Schema.OmitPolicy`]:

- `.when(pred: Structure.Value => Boolean)` installs `OmitPolicy.When(pred)`; on encode `omitField` runs the predicate against the field's encoded `Structure.Value` and omits when it returns `true` [`SchemaSerializer.omitField`].
- `.whenDefault` installs `OmitPolicy.WhenDefault` AND a `fieldMaterializedDefaults` entry: a `Structure.Value` the `omitFocusImpl` macro materializes ONCE from the field's compile-time Scala default, THROUGH the field's own schema writer. The default is a constant, so it is materialized at builder time and the encode-time comparison reuses the stored value rather than re-materializing it per record. On encode `omitField` omits the field when its materialized value equals this stored default; with no compile-time default the entry is absent and the field never omits [`SchemaSerializer.omitField`] [`Schema.OmitWhen.whenDefault`] [`SchemaTransformMacro.omitFocusImpl`]. Materializing through the field schema is what makes `whenDefault` correct for product, collection, and sum fields, not only scalars.

`fieldMaterializedDefaults` is a SEPARATE slot from `omitPolicies` (it holds the per-field materialized default value) and must be threaded through all four construction sites alongside `omitPolicies`; it is consulted only inside `WhenDefault`'s `omitField` arm, so it needs no transform flag of its own (the `omitPolicies.nonEmpty` clause already gates the path) [`Schema.fieldMaterializedDefaults`] [`Schema.createWithFocused`] [`Schema.copyWith`].

#### `transformField` / `transformFieldWrite` / `transformFieldRead`: per-field custom wire form

The three transform builders install a per-field codec override carried in `fieldTransforms`. Each is a `transparent inline` taking `(using Frame)`; `transformField` takes `write: (V, Codec.Writer) => Unit` then `read: Codec.Reader => V`, `transformFieldWrite` takes only `write`, `transformFieldRead` only `read` [`Schema.transformField`, `Schema.transformFieldWrite`, `Schema.transformFieldRead`]. All three splice to one private `transformFieldFocusImpl` that builds a `FieldTransform[A]` (carrying the source getter, optional write override, optional read override, and the field's derived `writeDerived` path) and merges it into the existing slot by source field name through `Schema.mergeFieldTransform` [`SchemaTransformMacro.transformFieldFocusImpl`] [`Schema.mergeFieldTransform`].

Merge-by-source-field is what lets a write-only and a read-only transform on the same field be applied in either order without duplicating the slot: `mergeFieldTransform` keeps the existing direction the new call did not configure (`replaceWrite` / `replaceRead` flags), so `transformFieldWrite(_.f)(w)` followed by `transformFieldRead(_.f)(r)` yields one slot with both directions [`Schema.mergeFieldTransform`]. The carrier is parameterized by the ROOT type `A` (not the field type `V`) because the getter receives the whole root value; the field value type is erased inside the carrier and restored by the macro-generated `write` / `read` / `writeDerived` closures at the call site [`Schema.FieldTransform`].

Encode order is transform-write -> omit -> rename/naming/representation. `writeWithTransforms` first materializes the value to a `Structure.Value` via `StructureValueWriter`, then for each write-direction transform runs the user writer against a FRESH `StructureValueWriter` and substitutes the field's value in the record BEFORE the drop / omit / rename / convention pass runs. So the omit predicate sees the TRANSFORMED wire value, and rename moves the already-transformed value to its target name [`SchemaSerializer.writeWithTransforms`].

The replay at the end of that method must reproduce the framing each field's own writer used, and one framing is not recoverable from the tree: both mapping framings produce a `Structure.Value.MapEntries`, and the declared structure does not distinguish them either. So each `StructureValueWriter` reports the map nodes it built from `mapEntriesStart` (the array-of-pairs form), `writeWithTransforms` unions those lists (its own writer plus every field-transform writer), and `writeStructureValue` honors them before falling back to the key-type spelling [`StructureValueWriter.mapEntriesStart`] [`SchemaSerializer.writeWithTransforms`]. The list is held by IDENTITY: two empty mappings are equal values from different givens. Every transform carries a field's value into the output tree by reference, which is what makes identity work; a rewrite that copied a nested value would silently lose the framing and fall back to the key-type guess. A caller replaying a tree it did not materialize passes nothing and keeps the key-type spelling, which is what `Structure.encode` and the JSON Schema example writer want.

Decode applies the read override for a PRESENT field and the default supplier for an ABSENT field. `readWithTransforms` builds `fieldReadOverrides` as a `Map[WireKey.Key, ...]` keyed by BOTH the source field name AND its numeric field id, registered as the disjoint `WireKey.ByName` and `WireKey.ByFieldId` opaque types: a self-describing codec reports the field by name, Protobuf by its numeric tag, so the lookup must hit on either wire form [`SchemaSerializer.readWithTransforms`]. On a hit in `TransformAwareReader.fieldParse()`, the override consumes the field's wire bytes via `transform.read.get(inner)`, re-emits the decoded value through `writeDerived` into a pending synthetic value, and rewrites `_translatedField` to the SOURCE name so `matchField`'s string compare succeeds even when the matched key was a numeric field id [`TransformAwareReader.fieldParse`]. A serialize-only configuration (no `read`) leaves decode on the derived codec, and a deserialize-only configuration (no `write`) leaves encode on the derived codec.

`WireKey` (`ByName` opaque over `String`, `ByFieldId` opaque over `Int`, `type Key = ByName | ByFieldId`) is the single decode-lookup key model. It also backs `_seenFromWire`: each wire field is recorded under its name key and, when the token is numeric, its id key, so the synthetic-default and flattened-parent suppression checks (`seenOnWire`, which probes both forms of a known source name) recognize a present field on either codec and never overwrite it with a configured default. Because `ByName` is a `String` map key and `ByFieldId` an `Int` key, a field name and a numeric id can never alias inside one map, which is the trap a flat `Map[String, ...]` of name-and-id strings carries [the `SchemaSerializer.WireKey` scaladoc].

#### `SchemaFactory.createFrom` threads the full slot set

The path-key recomputation factory `SchemaFactory.createFrom` (the engine behind drop / rename that rebuilds field IDs) MUST forward EVERY slot from the source schema, including the four customization slots and `fieldMaterializedDefaults`. It forwards `denyUnknownFieldsEnabled`, `fieldDefaults`, `fieldTransforms`, and `fieldMaterializedDefaults` explicitly, the same way it forwards every other slot [`SchemaFactory.createFrom`]. This is the SAME class of trap as the four-construction-site rule: a `createFrom` that forgets a slot resets it to the default the moment a `.drop` or path-affecting builder runs after the slot was configured, so a transform / default / strict-decode set before a later `.drop` would silently vanish. Treat `createFrom` as a FIFTH construction site for any new slot.

#### Decode reader state: the single-instance mutable carve-out

The decode synthetic-injection state lives in `TransformAwareReader` as per-instance mutable `var`s (`_translatedField`, `_matchedField`, `_syntheticField`, `_pendingSyntheticFields`, `_seenFromWire`, `_syntheticActive`, `_syntheticDepth`, ...) [`SchemaSerializer.TransformAwareReader`]. This is the documented decode-reader carve-out from the no-mutable-state rule: a `TransformAwareReader` is constructed fresh per `readWithTransforms` call, is single-instance scoped to that one decode, and never crosses a fiber. The `List`-typed `_pendingSyntheticFields` is a deliberate hot-path choice (O(1) head/tail as the reader drains each pending field), not an oversight [`TransformAwareReader._pendingSyntheticFields`]. A new decode-time injection slot adds its own per-instance `var` here, never a shared or class-level one.

### Type unions (`A | B`)

`derives Schema` works for a Scala type union `A | B`, not just case classes and sealed traits. The macro's `derivedImpl` adds an `isOrType` arm that dispatches to `emitUnionSchemaStatic` [`FocusMacro.derivedImpl`] [`FocusMacro.emitUnionSchemaStatic`]:

- `flattenUnion` flattens a (possibly nested) union into its members in declared first-occurrence order (depth-first, left-to-right); a member appearing twice is kept only at its first occurrence. It uses a `ListBuffer`, never a `Set`, so declaration order is stable [`FocusMacro.flattenUnion`].
- `memberLabel` is the default wire label: the simple name (last `.`-segment), so `scala.Int` becomes `Int`. Member naming REUSES the existing `variantNames` / `variantAlias` vocabulary; there is no separate union naming surface [`FocusMacro.memberLabel`].
- The emitted union Schema defaults `representation = UnionRepresentation.Untagged`. Each member's `writeBody` wraps the payload in a one-field `{memberName: payload}` Record envelope (mirroring the sealed-sum `writeBody`); `untaggedEncode` then strips the envelope, leaving the bare payload on the wire [`FocusMacro.emitUnionSchemaStatic`].

The decode side is the DELIBERATE divergence from nominal sums. `readUntagged` branches on the `Structure.Type.Sum` NAME: union derivation always names the sum `"Union"`, so a `"Union"`-named sum routes to `readUnionMultiProbe`, while every nominal untagged sum falls through to first-declared-wins [`SchemaSerializer.readUntagged`]:

- **Type unions multi-probe.** `readUnionMultiProbe` captures the wire once, then replays a fresh `StructureValueReader` per member and collects EVERY member that decodes (it does not short-circuit). Zero matches raises `NoVariantMatchException`; exactly one returns that value; more than one consults `unionAmbiguityPolicy` [`SchemaSerializer.readUnionMultiProbe`]. `Strict` (the default) raises `AmbiguousVariantMatchException` listing the matched members; `FirstMatch` returns the first-declared success [`Schema.UnionAmbiguity`] [`AmbiguousVariantMatchException`]. The policy is set by `schema.unionAmbiguity(policy)` and is DECODE-ONLY [`Schema.unionAmbiguity`]. `unionAmbiguityPolicy != Strict` is ORed into `hasReadTransforms` ONLY (it has no write effect), the read-only case of the dual-flag rule [`Schema.hasReadTransforms`].
- **Nominal untagged sums keep first-declared-wins**, the legacy behavior. They never multi-probe and so never raise `AmbiguousVariantMatchException`.

### The dual-flag invariant

Every new schema slot that affects serialization behavior MUST be ORed into the right transform flag(s), or the feature silently never runs. `hasTransforms` gates the WRITE hot path (a clean derivation takes the direct `serializeWrite`; a non-clean schema routes through `transformedWrite`) and `hasReadTransforms` gates the READ hot path identically [`Schema.hasTransforms`, `Schema.hasReadTransforms`]:

- A slot affecting BOTH encode and decode (`representation`, `representationChain`, the omit slots, `fieldTransforms`) is ORed into BOTH flags. `fieldTransforms` ORs into both even for a write-only or read-only transform, because the slot's presence cannot tell the flag which direction is configured (the dispatcher decides per-direction by `transform.write.isDefined` / `transform.read.isDefined`); see [Serialization customization](#serialization-customization-the-four-field-builders).
- A DECODE-ONLY slot (`unionAmbiguityPolicy`, where the inert default is `Strict`; `denyUnknownFieldsEnabled`, inert `false`; `fieldDefaults`, inert empty) is ORed into `hasReadTransforms` only.
- A WRITE-ONLY slot (`computedFields`) is ORed into `hasTransforms` only.
- A slot consulted only inside an already-gated path (`fieldMaterializedDefaults`, read only inside `OmitPolicy.WhenDefault`'s `omitField` arm, which is already reached via `omitPolicies.nonEmpty`) needs no flag of its own.

A slot that misses its flag compiles cleanly and silently bypasses the feature on the corresponding hot path: a configured rename, omit, chain, or ambiguity policy would never run. This is the single most error-prone step when adding a slot. The flags are a boolean OR over the slot states, so the hot path stays a single branch rather than one probe per slot.

This invariant underwrites the **default-byte-identity guarantee**: an UNCONFIGURED schema (no builder called) has every slot at its inert default, so both flags are `false`, so encode/decode take the direct `serializeWrite` / `serializeRead` path and produce byte-identical output to a schema compiled before any of these slots existed. The entire transform/representation/omit/union machinery sits BEHIND the `hasTransforms` / `hasReadTransforms` gate; nothing on the clean path pays for a feature it does not use. A new slot whose inert default does NOT leave both flags `false` breaks this guarantee.

### Adding a slot to `Schema`: the construction-site and bytecode traps

Two mechanical traps bite every slot addition.

**Thread the slot through ALL FOUR construction sites.** A `Schema` slot is a constructor parameter with a default, and four factories construct or copy a `Schema`: `Schema.init`, `Schema.initFocused`, `Schema.createWithFocused`, and `Schema.copyWith`. A new slot must appear as a parameter AND be forwarded in the constructor call of all four. A miss COMPILES (the parameter defaults), but silently drops the slot on whichever path missed it: a `copyWith` that forgets the slot resets it to the default on every subsequent builder call, so a representation/omit/chain configured before a later `.rename` would vanish. `copyWith` is the highest-risk site because every builder method routes through it.

**A slot insertion requires a HARD clean, not `sbt clean`.** Adding a `Schema` constructor slot changes the constructor's bytecode signature, but the derivation macro's emitted `derived$Schema` is compiled against the OLD signature and cached. `sbt clean` does NOT evict it; the stale macro bytecode then constructs a `Schema` against the old arity and the build fails or, worse, silently mis-wires the slot. Nuke `target/`, `.bloop/`, `.bsp/`, and `project/` (the compiler-bridge and macro caches), then rebuild from cold. Treat any inexplicable post-slot-insertion failure (an arity mismatch, a slot that reads its default when it was set) as this stale-bytecode trap first, before suspecting the source.

### The derivation macro boundary

`Schema.derived` and `Schema.derivedVia` do not splice `FocusMacro` directly. They splice `SchemaDerivedMacro`, a two-method object that delegates straight to it and lives at the end of `internal/FocusMacro.scala` [`Schema.derived`] [`SchemaDerivedMacro`]. Both halves of that arrangement are load-bearing, for different reasons, and each has its own failure mode.

**It must be a separate object, because a separate object is a separate class file.** `Schema.scala`, `Changeset.scala` and `Structure.scala` each carry a `derives Schema`, so the compiler suspends those units and expands their macros in a later run, which means their own class files do not exist yet at expansion time. `FocusMacro` names those types in its method signatures, so loading `FocusMacro$` at that point throws `NoClassDefFoundError`. Splicing `FocusMacro.derivedImpl` directly fails a CLEAN build with `Cyclic macro dependencies among Changeset.scala, Schema.scala, Structure.scala`. `SchemaDerivedMacro` names none of those types outside `Expr`, which erases, so its class file loads.

**It must share `FocusMacro.scala`, because the compiler only looks one hop.** Before expanding a macro the compiler asks whether the spliced symbol is being compiled in the current run, and suspends if so; the analysis reads the splice body and cannot see through the delegation. In a file of its own, `SchemaDerivedMacro` uses so few names that zinc almost never invalidates it, while `FocusMacro` is invalidated by nearly any kyo-data change. The compiler then sees no current-run dependency, declines to suspend, and expands against a `FocusMacro$.class` zinc has already deleted. That is the `NoClassDefFoundError: kyo/internal/FocusMacro$` that made every incremental kyo-schema build require a `clean`. Sharing a source file makes it impossible for zinc to invalidate one without the other.

Do not "tidy" this object into its own file, and do not inline it into `FocusMacro`. The first reintroduces the incremental failure, the second the clean-build one. The same reasoning applies to any new `${ ... }` splice target in this module: it belongs in the same file as the implementation it delegates to.

### The macro-decode protocol (null-seed + required-mask)

The macro-generated product decoder uses an `Array[AnyRef]` of field slots and a `Long` required-field bitmap. It SEEDS every reference slot to JVM `null` and detects a missing required field by a `values(idx) == null` check, OR-ing a `seen` bitmap against `meta.requiredMask` after `reader.droppedFieldsMask(n)` [kyo-schema/shared/src/main/scala/kyo/internal/SchemaCodecRuntime.scala]. This null-seed protocol is WHY decode-default for an omitted collection field cannot use `zeroForField` alone: the macro hard-seeds a collection slot to `null` and never calls `zeroForField`, so a missing collection field would decode as `null`, not the typed empty. The fix is the synthetic-wire-token injection in `TransformAwareReader` (above), which presents the missing field to the macro decoder as if it were an empty sequence/map on the wire, so the macro's normal field-read path produces the typed empty value. `zeroForField` itself is consulted only to BUILD the injected zero value, never to seed a slot directly [`SchemaSerializer.readWithTransforms`].

### Hard limits and rejections

| Rule | Value | Where |
|------|-------|-------|
| Max case-class fields with `derives Schema` | 64 | Generated decoder uses a `Long` required-field bitmap; > 64 is a compile-time error, not silent truncation [`FocusMacro.emitProductSchemaStatic`]. |
| Private case-class fields | Rejected | `Cannot derive Schema for ...: case-class field(s) ... are private.` Hand-roll a `given Schema` instead (mirror `structureFieldSchema`) [`FocusMacro.rejectPrivateCaseFields`]. |
| `Codec.Reader.maxDepth` default | 512 | Built into the abstract base, inherited uniformly [`Codec.DefaultMaxDepth`]. |
| `Codec.Reader.maxCollectionSize` default | 100000 | Same [`Codec.DefaultMaxCollectionSize`]. |
| `Codec.Reader.droppedFieldsMask(n)` default | `0L` | Default means no fields are pre-satisfied; macro-generated decoder ORs this into its `seen` bitmap before required-field validation [`Codec.Reader.droppedFieldsMask`]. |

### Sealed-trait shape and variants

Wire shape for sealed traits is wrapper-format by default (`{"VariantName": ...}`); calling `.discriminator("type")` flips to a flat shape with a discriminator field [`Schema.discriminator`]. The five alternate wire shapes (`Internal`, `Adjacent`, `Tuple`, `TupleFlat`, `Untagged`) and their builders are documented under [Sum wire representations](#sum-wire-representations-the-representation-slot); all of them are carried by the single `representation` slot and rewritten at the `SchemaSerializer` layer, never in the macro.

Variant Schemas of a sealed trait are emitted **inline** (not via `summonInline[Schema[Variant]]`). This is because the variant child does not necessarily carry its own `derives Schema`; an inline re-entry into `emitProductSchema` is how the trait's derivation reaches each variant. Field references back to the parent type still forward-reference through the parent's `derived$Schema` [`FocusMacro.emitProductSchemaStatic`].

Every reference to a case goes through `MacroUtils.sumCaseReference`, which selects the case from the path the derived sum type was written through, the rule the compiler's own sum mirror follows: an enum value or a case object gets its singleton `TermRef`, a class case its `TypeRef` [`MacroUtils.sumCaseReference` and its scaladoc]. The sum emitter types each variant schema, its `eq` check and its decoded value from it, and `ExpandMacro` types each variant of the focus shape from it [`FocusMacro.emitSealedSchemaStatic`] [`ExpandMacro.expandImpl`]. `Symbol.typeRef` is not a substitute. On an enum value it builds a type that designates a value: for an enum declared as a member of a class that value is a getter, the type erases to `Function0`, and the variant schema loses its bridges (`AbstractMethodError` on the JVM, a link error on Scala.js and Scala Native). Its prefix is also the declaring owner's `this`, which does not exist where a member type is derived from outside its class.

Scala TYPE UNIONS (`A | B`) are a third union form derived by the same `derives Schema` surface but a distinct macro arm; their members ARE summoned via `summonInline[Schema[member]]` (a union member is a named type that must carry its own Schema), and their decode default diverges from nominal sums. See [Type unions](#type-unions-a--b).

### Conventions a new method on `Schema` must follow

- The four abstract codec methods (`serializeWrite`, `serializeRead`, `getter`, `setter`) carry `@publicInBinary private[kyo]`; the class constructor is `@publicInBinary private[kyo]` too, anchoring the contract every concrete Schema overrides [`Schema.serializeWrite`, `Schema.serializeRead`, `Schema.getter`, `Schema.setter`] [the `Schema` class declaration].
- Hand-rolled Schemas that override the abstract codec methods carry the same `@publicInBinary private[kyo]` on each override, and override `structure` as a `lazy val` named `_structure` [`Structure.structureTypeSchema`, `Structure.structureFieldSchema`].
- New surface methods that traverse a focus take an inline `Focus.Select` lambda; the method itself is `inline` and forwards to a private `Schema.fieldCheck...` / `Schema.withField...` helper. Both `check` and `doc` follow that recipe: capture `rootSelect`, apply the lambda, delegate [`Schema.check`, `Schema.fieldCheck`].
- Internal Schemas with no user call-site frame supply `Frame.internal` as a private given inside the new-instance body; that is the documented sentinel for that case [`Structure.structureTypeSchema`].
- The WRITE path threads `(using Frame)` end to end. `serializeWrite`, `transformedWrite`, `writeTo`, `toStructureValue`, and `transform` all carry `(using Frame)`, so an encode diagnostic points at the user's call site [`Schema.serializeWrite`] [`Schema.writeTo`]. `Frame.internal` is the deliberate sentinel ONLY at genuinely-frameless container-given construction sites: a container given's per-element `inner.serializeWrite(_, writer)(using Frame.internal)` and `rawSerializeWrite`'s bridge supply it because no user frame exists at the container's element boundary [`Schema.rawSerializeWrite`] [the container givens in the `Schema` companion]. A new write method propagates the caller's `Frame`; it reaches for `Frame.internal` only at such a frameless boundary, never as the default.

## Annotation-driven configuration

Annotations are a SECOND spelling of the programmatic builder surface, plus an introspection-metadata carrier. They live in their own package `kyo.schema` in the single file `SchemaAnnotation.scala`; everything else in the module is package `kyo`. There are TWO independent rails, and a built-in annotation rides both:

1. **The capture rail** reifies surviving annotation INSTANCES onto the structural model (`Structure.Type.Product/Sum.annotations`, `Structure.Field.annotations`, `Structure.Variant.annotations`), for codec authors and tooling to read back by type. This is metadata, excluded from structural identity and the wire.
2. **The desugar rail** reads the built-in leaves at derivation time and emits them onto the EXISTING programmatic `Schema.init` config slots (`renamedFields`, `variantNaming`, `omitPolicies`, `representation`, ...). An annotation adds NO new runtime behavior; it is a compile-time spelling of a builder call.

### The capture rail and `AnnotationPolicy`

Every structural node that can carry annotations has an `annotations: Chunk[Any] = Chunk.empty` carrier: `Structure.Type.Product`, `Structure.Type.Sum`, `Structure.Field`, and `Structure.Variant`. The element type is `Any` because a captured annotation can be any user or third-party annotation class, and no `Schema[Any]` exists. The carrier defaults to `Chunk.empty`, and `Chunk` is covariant, so a cross-package case class deriving `Schema` from OUTSIDE package `kyo` compiles: the empty-config default infers through `Chunk` covariance and the generated code never names a `private[kyo]` type.

`kyo.schema.AnnotationPolicy` gates the capture rail for NON-marker (third-party) annotations [`kyo.schema.AnnotationPolicy`]. A non-marker annotation's fully-qualified name is captured iff it matches at least one `include` glob AND no `exclude` glob (`*` is a path-segment wildcard). The default `include = Chunk("*")` admits every FQN except the fixed `defaultExclusions` noise set (compiler/platform annotations: `scala.annotation.internal.*`, `nowarn`, `tailrec`, `targetName`, `unchecked.*`, `java.lang.Override`, `publicInBinary`). `AnnotationPolicy.markersOnly` (an empty `include`) admits no non-marker FQN. The macro summons the policy at expansion time, so a custom policy MUST be an `inline given` for its value to be readable: `inline given AnnotationPolicy = AnnotationPolicy.markersOnly`. A non-`inline` custom given is a compile error with that exact remediation message [`FocusMacro.summonAnnotationPolicy`].

**Markers are unconditional.** Any annotation subtyping `kyo.schema.SchemaAnnotation` (a `StaticAnnotation`) is ALWAYS captured, never consulting the policy [`kyo.schema.SchemaAnnotation`] [`FocusMacro.captureAnnotations`]. Every built-in annotation is a `SchemaAnnotation`, so the built-ins are captured regardless of policy AND additionally desugared. A CUSTOM `SchemaAnnotation` subtype is captured but drives no behavior on its own; the codec author reads it back off `schema.structure` and acts on it.

### Annotations are excluded from identity and the wire

The carrier is introspection metadata, NOT structural identity. The hand-rolled `equals` / `hashCode` on `Product`, `Sum`, `Field`, and `Variant` all OMIT `annotations` [`Structure.Type.Product.equals`] [`Structure.Field.equals`] [`Structure.Variant.equals`], `Structure.Type.compatible` never inspects it, and the hand-rolled `structureTypeSchema` / `variantSchema` / `structureFieldSchema` wire shapes EXCLUDE it (no `Schema` exists for the `Chunk[Any]` element type, and the wire must stay byte-stable). The guarantee that mirrors the [default-byte-identity guarantee](#the-dual-flag-invariant): adding a `SchemaAnnotation` to a type that carried none produces a byte-identical schema. This invariant is binding. A new structural node with an `annotations` carrier MUST exclude it from `equals`, `hashCode`, `compatible`, and the wire.

### The built-in annotation set

All built-ins live in package `kyo.schema`, extend `SchemaAnnotation`, and desugar onto an existing programmatic config slot. Programmatic config WINS over annotations: the desugar bakes config into `Schema.init` at derivation, and a later programmatic builder (`.rename`, `.discriminator`, ...) runs through `copyWith` on the already-derived schema, so last-write-wins leaves the programmatic call on top.

| Annotation | Placement | Desugars onto | Notes |
|------------|-----------|---------------|-------|
| `@rename(wireName)` | field or variant | `renamedFields` / variant wire name in `variantNaming` | rename resolves before `@alias`; the renamed name is the primary [`kyo.schema.rename`] |
| `@alias(names*)` | field or variant | field/variant aliases in `variantNaming` | decode-only; collisions raise `FieldNameCollisionException` / `VariantNameCollisionException` at construction [`kyo.schema.alias`] |
| `@discriminator(tagKey)` | sealed trait only | `discriminatorField` + `representation = Internal` | case-class placement is a compile error ("sum-representation annotation") [`kyo.schema.discriminator`] |
| `@adjacent(tagKey, contentKey)` | sealed trait only | `representation = Adjacent` | as above [`kyo.schema.adjacent`] |
| `@untagged()` | sealed trait only | `representation = Untagged` | as above [`kyo.schema.untagged`] |
| `@transient()` | field only | drop-with-Scala-default | field MUST have a Scala default, else decode fails `MissingFieldException`; distinct from `@omit` which is decode-symmetric [`kyo.schema.transient`] |
| `@doc(text)` | field or type | `fieldDocs` (keyed by effective wire name) / `documentation` | the single doc annotation, in package `kyo.schema`; `Json.jsonSchema` surfaces it as the field description [`kyo.schema.doc`] [kyo-ai/shared/src/main/scala/kyo/Thought.scala] |
| `@omit(mode, reason)` | field only | `omitPolicies` entry | see omit modes below [`kyo.schema.omit`] |
| `@transform(transformer)` | field only | `fieldTransforms` | object-reference arg, see below [`kyo.schema.transform`] |
| `@proto.fieldNumber(n)` | field only | `fieldIdOverrides` (single-segment `Seq(fieldName) -> n`) | the first SCOPED format-specific annotation, see below; pins the Protobuf field number, surfaced by `fieldNumberAudit` as `pinned` [`kyo.schema.proto.fieldNumber`] |

The `@omit` modes live in the `omit` COMPANION, not as top-level types [`kyo.schema.omit.Mode`]: `@omit` (= `omit.WhenAbsent`, type-aware), `@omit(omit.WhenNone)`, `@omit(omit.WhenEmpty)`, `@omit(omit.WhenDefault)`, and `@omit(omit.When(predicateObj))`. Each maps onto the corresponding `OmitPolicy` case documented in [Field omission on encode](#field-omission-on-encode) and [predicate-driven encode omission](#omit_fieldwhen--whendefault-predicate-driven-encode-omission). The mode enum lives in the companion specifically so the `Mode` enum name does NOT collide with the `omit` annotation class name on a case-insensitive filesystem; this is the convention for any enum used as an annotation arg.

### Object-reference args

A closure is not a valid annotation argument, so an annotation needing a function-shaped behavior takes a NAMED OBJECT extending a sealed interface, reified by reference at derivation time:

- `@transform(obj)` where `obj` extends one of `kyo.schema.Transformer.{Full, WriteOnly, ReadOnly}` (the sealed `Transformer[A]` family) [`kyo.schema.Transformer`]. `Full` supplies `write` + `read`, `WriteOnly` only `write` (decode uses the derived codec), `ReadOnly` only `read`. The macro verifies the transformer's element type matches the field type and lifts the singleton object reference onto the `fieldTransforms` slot [`FocusMacro.desugarProductConfig`].
- `@omit(omit.When(predObj))` where `predObj` extends `kyo.schema.OmitPredicate` (a single `test(value: Structure.Value): Boolean`) [`kyo.schema.OmitPredicate`]. The predicate object is lifted the same way and installed as `OmitPolicy.When(pred)`.

Both are lifted through the same reification path as any other constant annotation arg (below). This object-reference pattern, not a closure, is the rule for ANY future annotation that needs behavior as an argument.

### Scoped format-specific annotations

Schema-general annotations (`@rename`, `@doc`, `@discriminator`, ...) are flat top-level names in package `kyo.schema`. A FORMAT-SPECIFIC annotation, one that means something only to a single wire codec, is instead SCOPED under an object named for that format: `@proto.fieldNumber(n)`, not a flat `@protoFieldNumber(n)`. The `proto` object [`kyo.schema.proto`] holds `fieldNumber`, the first such annotation; a future `json.*` namespace would follow the same shape. The scope keeps the format coupling legible at the use site and reserves the flat namespace for cross-format directives.

`@proto.fieldNumber(n)` is an ordinary `SchemaAnnotation`, so it rides the capture rail unchanged: the scoped nesting does not affect reification, which keys on the subtype relation, not the name. On the desugar rail it reads the constant `Int` arg and emits a SINGLE-SEGMENT `fieldIdOverrides` entry `Seq(fieldName) -> n` [`FocusMacro.desugarProductConfig`], the same shape the programmatic `Schema[A].fieldId(_.field)(n)` builder writes. That single-segment override is wire-functional for the Protobuf codec (encode emits the pinned number, decode matches it) and is reported by `Protobuf.fieldNumberAudit` as `pinned = true` with no audit change needed, because the audit already projects single-segment overrides. The macro rejects a non-positive `n` at derivation (`errorAndAbort`), since proto field numbers are `>= 1`. Programmatic config still wins: a later `.fieldId` runs through `copyWith` on the derived schema and overwrites the baked override.

### The macro mechanism for contributors

`FocusMacro` drives both rails. The capture rail is `captureAnnotations(sym)` (type-level and variant-level annotations) and `captureFieldAnnotations(caseField, sym)` (case-class fields, which in Scala 3 carry user annotations on the PRIMARY-CONSTRUCTOR PARAMETER symbol, not the accessor getter, so it merges constructor-param and accessor annotations in declaration order) [`FocusMacro.captureAnnotations`, `FocusMacro.captureFieldAnnotations`]. Each reads `Symbol.annotations`, summons the `AnnotationPolicy`, keeps markers unconditionally and non-markers by `policyAdmits`, and reifies each surviving term via `reifyAnnotation` into an `Expr[Chunk[Any]]` threaded onto the structure node (`typeAnnots`, `fieldAnnots`, `memberAnnots`, `childAnnots`, `sumAnnots`) [`FocusMacro.emitProductSchemaStatic`] [`FocusMacro.emitSealedSchemaStatic`].

`reifyAnnotation` accepts only CONSTANT terms: literals, `new`-applied stable constructors, stable module / val / enum references, and stable case-class / enum-case applies (e.g. `omit.When(pred)`) over a stable qualifier with all-constant args; a term with method-call args or a closure is non-liftable and returns `None` [`FocusMacro.reifyAnnotation`]. **A non-reifiable non-marker term is skipped GRACEFULLY** (no `errorAndAbort`, no `info`), so a third-party annotation the policy admits but that cannot be lifted simply does not appear in the carrier rather than failing the derivation [`FocusMacro.captureAnnotations`]. Object-reference args (`@transform`, `@omit(omit.When(...))`) are lifted through this same constant-term path (a stable module ref is constant).

The desugar rail is `desugarProductConfig` / `desugarSumConfig`, which read the built-in leaves off the field / type / child symbols and assemble a macro-local config carrier whose fields map 1:1 onto `Schema.init` parameters; the built result is spliced into the single `Schema.init[A]` call the macro already emits [`FocusMacro.desugarProductConfig`, `FocusMacro.desugarSumConfig`] [`FocusMacro.emitProductSchemaStatic`] [`FocusMacro.emitSealedSchemaStatic`]. Annotation-baked aliases run the SAME collision checks at `Schema.init` time that the programmatic `.alias` builder runs at its call site, so a colliding annotation alias raises immediately instead of surfacing as `MissingFieldException` at decode [`Schema.checkFieldAliases`, `Schema.checkVariantAliases`]. Type unions reuse the capture rail: each union member's annotations come from `captureAnnotations(member)` [`FocusMacro.emitUnionSchemaStatic`].

### Introspection surface for codec authors

Reading captured annotations back off a derived `schema.structure`:

- The `Annotated` extractors yield `(name, annotations)`: `Structure.Type.Annotated` (matches a `Product` or `Sum`), `Structure.Field.Annotated` (irrefutable), `Structure.Variant.Annotated` (irrefutable).
- `annotationOf[A]: Maybe[A]` (first by type) and `annotationsOf[A]: Chunk[A]` (all by type) are extensions on all four annotated nodes (`Product`, `Sum`, `Field`, `Variant`), both backed by a `collect` over `ShallowTag.unapply`, which checks the annotation's erased class [the `annotationOf` / `annotationsOf` extensions in `Structure.scala`].
- `fieldsWith[A]` / `variantsWith[A]` are extensions on `Chunk[Structure.Field]` / `Chunk[Structure.Variant]` returning each node paired with its matching annotation instance.

A codec author drives behavior off a custom marker by, for example, `field.annotations.collectFirst { case r: rename => r.wireName }` or `structure.fields.fieldsWith[MyMarker]`.

### Conventions for adding an annotation

- A NEW built-in annotation goes in package `kyo.schema`, extends `SchemaAnnotation`, and DESUGARS onto an existing programmatic config method. Do not add new runtime behavior: the annotation is a spelling of the programmatic API, so the desugar target must already exist as a builder/slot. Add the desugar arm in `desugarProductConfig` (field/type leaf) or `desugarSumConfig` (sum/variant leaf).
- An annotation needing a function/closure arg uses the OBJECT-REFERENCE pattern (sealed interface + named object, like `Transformer` / `OmitPredicate`), NEVER a closure. Closures are not annotation args and are non-reifiable.
- An ENUM used as an annotation arg must not collide with its annotation class name on a case-insensitive filesystem; nest it in the annotation's companion (the `omit.Mode` precedent).
- Format-SPECIFIC annotations should be SCOPED (a future `@proto.fieldNumber` lives under a format-named owner); schema-GENERAL annotations stay flat in `kyo.schema`.
- The annotation MUST be excluded from structural `equals` / `hashCode` / `compatible` and the wire (it is introspection metadata, not structural identity); this is automatic when it rides the existing carriers and is a hard requirement for any new carrier.

## Conventions

### Exception hierarchy

`SchemaException` is a sealed abstract class extending `KyoException`, takes a `(using Frame)`, and lives in one file `SchemaException.scala`.

Operation-mode is carried by sealed marker traits that every leaf mixes in alongside the base:

- `DecodeException`
- `ValidationException`
- `TransformException`
- `NavigationException`

Every concrete exception leaf is a `case class` taking field-level data plus `(using Frame)`, carries its message inline in `extends SchemaException(s"...")`, and mixes in one or more marker traits; navigation-relevant leaves derive `CanEqual` [`MissingFieldException`] [`UnknownVariantException`]:

```
case class MissingFieldException(path: Seq[String], fieldName: String)(using Frame)
    extends SchemaException(s"Missing required field '$fieldName'" ...)
    with DecodeException with NavigationException derives CanEqual
```

Path-bearing exceptions format the path suffix through the shared `SchemaException.pathSuffix` helper; no leaf re-implements the formatting.

The sum-representation leaves follow the same template: `NoVariantMatchException(path, variants)` (untagged decode matched no variant; `DecodeException`) and `MissingTagKeyException(path, tagKey)` (adjacent decode input lacks the tag key; `DecodeException with NavigationException`) both carry their data plus `(using Frame)`, derive `CanEqual`, and surface as `Result.Failure` on decode. `RepresentationUnsupportedException(codec, representation)` is a `TransformException` raised on the WRITE path before any bytes when the codec cannot express the representation's wire shape; it carries the codec's public name (from `Codec.Writer.codecName`) and the representation name.

Two further leaves follow the same template. `AmbiguousVariantMatchException(path, matched)` is a `DecodeException` raised when a type-union untagged decode matches more than one member under the `Strict` ambiguity policy; it carries the matched member wire names and surfaces as `Result.Failure`. `DuplicateRepresentationException(chain)` is a `TransformException` raised at the BUILDER call (not at encode/decode) when a representation chain contains a duplicate entry; it never silently normalizes the chain.

`UnknownFieldException(path, fieldName)` is a `DecodeException` (NOT a `NavigationException`: the failure is malformed input against the configured read policy, not user navigation) raised on decode when `denyUnknownFields` is set and a wire field is not accounted for after naming, aliases, and transforms; it surfaces as `Result.Failure`. See [Serialization customization](#serialization-customization-the-four-field-builders).

`RecordDecodeException(recordIndex, byteOffset, cause)` is the one WRAPPING leaf: a `DecodeException` that carries another `DecodeException` as its `cause` and adds the failing record's position within a multi-record input. Every other leaf describes a failure inside one document; this one says which document of many failed, so a consumer of a JSONL log can report "record 41 at byte 9302" instead of "the file is malformed". It carries POSITION ONLY and never the record's own bytes: a record of an application log or an agent transcript is payload, and a payload on an exception reaches every log line and error page that renders it, so a new field here must pass that test. It does NOT derive `CanEqual`, because `cause` is an arbitrary exception and structural comparison of it would be meaningless. `JsonLines.decodeRecord` is the only site that raises it, and it wraps EVERY decode failure, which is what lets `Jsonl` tell a framing `LimitExceededException` apart from a record's own one [kyo-schema-json/shared/src/main/scala/kyo/JsonLines.scala].

### The `reader.frame` propagation rule

`Codec.Reader.frame` carries the user's decode call-site Frame; codec implementations MUST pass `using reader.frame` when throwing decode exceptions so the diagnostic points at the caller, not the codec's internal synthesis site [`Codec.Reader.frame`].

This applies uniformly to every decode-time throw site:

- The `Result` sum-Schema and the `Either` sum-Schema pass `reader.frame` to `MissingFieldException` and `UnknownVariantException` [`Schema.resultSchema`] [`Schema.eitherSchema`].
- The `Schema[Structure.Value]` and `Schema[Json.JsonSchema]` non-introspecting-reader guards attach `reader.frame` to the raised `SchemaNotSerializableException` [`Structure.Value.valueSchema`].
- Macro-generated readers in `SchemaCodecRuntime` throw `MissingFieldException` / `UnknownVariantException` `using reader.frame` for required-field misses and unknown sum variants [kyo-schema/shared/src/main/scala/kyo/internal/SchemaCodecRuntime.scala].

### The return-type discriminator: throw or `Result`

The boundary between "throw inside, catch at the public edge" and "return `Result` directly" is operation-mode, not personal preference.

| Surface kind | Return shape | Pattern |
|--------------|-------------|---------|
| Public decode entry point (`Json.decode`, `Protobuf.decode`, `schema.decode`) | `Result[DecodeException, A]` | Wrap the throw-based reader call in `Result.catching[DecodeException]` [`Json.decode`] [`Schema.decode`]. |
| Internal reader implementation | Throws | Macro-generated and hand-rolled readers throw `MissingFieldException` / `UnknownVariantException` `using reader.frame` [kyo-schema/shared/src/main/scala/kyo/internal/SchemaCodecRuntime.scala]. |
| Multi-record decode entry point (`Json.Lines.decodeAllBytesResults`) | `Chunk[Result[DecodeException, A]]` | One `Result` per record, each failure wrapped in `RecordDecodeException` carrying the record's index and byte offset. The strict sibling (`decodeAll` / `decodeAllBytes`) folds the same chunk to `Result[DecodeException, Chunk[A]]`, short-circuiting on the first failure [`JsonLines.decodeAllBytesResults`, `JsonLines.decodeAll`, `JsonLines.decodeAllBytes`]. |
| Navigation on `Structure.Path` | `Result[NavigationException, _]` | Navigation never throws across the public boundary; caller branches on a typed result [`Structure.Path.get`]. |

The two examples in one file: `Json.decode` returns `Result[DecodeException, A]` via `Result.catching`, while `JsonReader` inside throws. `Structure.Path.get` returns `Result[NavigationException, Chunk[Value]]` directly without an internal throw bracket.

### Capability marker for self-describing readers

`Codec.IntrospectingReader` is the capability marker for self-describing wire formats (JSON, YAML, in-memory Structure source); binary codecs without per-value type tags (e.g. Protobuf) do not extend it. The identity `Schema[Structure.Value]` requires this capability, so the type system catches a `Structure.Value` decode through a non-introspecting codec at the point of mismatch rather than letting it surface as `UnknownVariantException` at runtime. **Binary codecs without per-value type tags MUST NOT extend `IntrospectingReader`** [the `Codec.IntrospectingReader` scaladoc].

### `Structure.Type.Open` and the identity Schemas

`Structure.Type.Open` is the identity / shape-dynamic projection: a Schema carrying `Open` accepts and produces any wire shape. Compatibility with another `Open` is determined by tag equality, NOT structural recursion, and an `Open` type is NEVER compatible with any non-Open type [`Structure.Type.Open`, `Structure.Type.compatible`].

`Schema[Structure.Value]` is the identity / open-shape Schema: writes preserve the shape Scala already has, reads accept whatever shape the wire carries via `Codec.IntrospectingReader.readStructure()`. Auto-deriving the enum would emit a tagged-union wrapper like `{"Record":{...}}` and refuse a plain JSON object [`Structure.Value.valueSchema`].

### `PrimitiveKind` is exhaustive

`PrimitiveKind` is a closed enum that codec backends pattern-match exhaustively on; there is no silent fallback path. Adding a new primitive kind requires the enum extension AND every consumer's match branch; this is enforced by exhaustiveness, not by convention [`Structure.PrimitiveKind`].

`Bytes`, `Instant`, and `Duration` are base-value `PrimitiveKind` cases (alongside the matching `Structure.Value.Bytes` / `Instant` / `Duration` cases) [`Structure.PrimitiveKind`, `Structure.Value`]. Every codec that can represent a byte span or a timestamp natively (JSON, YAML, Ion, Ion Binary, BSON, MsgPack, Protobuf) maps these three kinds onto its own closest wire type rather than falling back to a generic encoding: adding a fourth base-value kind means extending `PrimitiveKind`, `Structure.Value`, and every codec's exhaustive match over both, the same invariant as any other `PrimitiveKind` addition.

A codec that cannot represent a base value at all raises a typed `DecodeException` (`ParseException`, or `SchemaNotSerializableException` for an encode-side rejection) naming the unsupported shape, rather than silently truncating or coercing it; `BsonReader.readStructure` and `IonBinaryReader.readStructure` are the worked precedent for mapping every wire value losslessly onto the shared `Structure.Value` vocabulary.

The wire shape of `Structure.Type` itself is explicitly hand-pinned by `given Schema[Structure.Type] = Schema.derived` so that any change to the sum's variant set is gated by a code change to this given. Adding a new `Type` variant changes the wire shape; the explicit declaration is the review gate [`Structure.Type.structureTypeSchema`].

### Hand-rolled `Structure.Field` Schema

`Structure.Field`'s wire shape is a hand-rolled 5-key object (`name, fieldType, doc, default, optional`). An auto-derived Schema would emit the private storage member `_fieldType: Function0[Structure.Type]` as a wire field, which is wrong on two counts: the wire name would be the storage name (not the public `fieldType`), and the wire field type would be `Function0[Structure.Type]` for which no Schema can be summoned. New private case-fields anywhere in `kyo-schema` need the same hand-roll [`Structure.Field.structureFieldSchema`].

The hand-rolled reader uses the canonical `Codec.Reader` contract: `hasNextField()` as the loop predicate, `fieldParse()` advances past the field name, `lastFieldName()` returns the just-parsed name. The same contract is named in the Reader's scaladoc and reused by macro-generated readers [`Structure.Field.structureFieldSchema`, the `Codec.Reader` scaladoc].

### Schema acquisition: derive, summon, or hand-roll

- User-defined product/sum types acquire their Schema by `derives Schema`; the macro path produces the wire shape. Tuples in `Schema` follow the same pattern via explicit `Schema.derived` (e.g. `given tuple2Schema[A: Schema, B: Schema]: Schema[(A, B)] = Schema.derived`) [`Schema.tuple2Schema`].
- Enums that participate in the wire shape and `CanEqual` derive both in one clause: `derives CanEqual, Schema` (`PrimitiveKind`, `PathSegment`).
- There is no parallel companion-typeclass path: hand-rolled givens use `Schema.init` and become indistinguishable from derived ones to the rest of the module [`Schema.init`].

### `Focus.Select` and the lattice mode

`Focus.Select[A, V]` is the lambda-navigator type used by every navigation-by-lambda surface (`Schema.check(_.field)`, `Schema.drop(_.field)`, `Compare.changed(_.field)`, `Builder.name`). It is a `Dynamic` with ONLY `selectDynamic` so it cannot collide with any user-defined field name. Adding new navigation methods to `Select` itself would reintroduce field-name collisions [`Focus.Select` and its scaladoc].

`Focus[Root, Value, Mode[_]]` is the post-navigation lens triple `(getter, setter, updateFn)` plus a back-reference to its `Schema[Root]`. `Mode[_]` forms a lattice `Id < Maybe < Chunk`. Crossing a sum-variant boundary or a collection boundary widens the mode; the lattice is encoded in the type, so a contributor cannot accidentally compose a Chunk-mode Focus back down to an Id-mode one [the `Focus` scaladoc].

### Stable Protobuf field IDs

`CodecMacro.fieldId(name)` is a 21-bit hash of the field name used as the stable Protobuf field number, so adding / removing fields does not collide on the wire. Note that `XXHash.hash32(name)` for a `String` is XXH32 applied to the four little-endian bytes of the name's JLS `String.hashCode`, not XXH32 of the name's UTF-8 bytes; an external implementation must reproduce that exact derivation [`CodecMacro.fieldId` and its scaladoc]:

```
def fieldId(name: String): Int =
    (XXHash.hash32(name) & 0x1fffff) + 1
```

### Conformance modes (Strict and Permissive)

`Protobuf.Config.conformance` is a CODEC-level concern, not a Schema slot. The schema derives the same structure in both modes; the codec gate is at encode time.

`Conformance.Strict` (the default): encode rejects any map whose key type is not a proto3-native scalar (an integral, `Boolean`, `String`, or an opaque/value-class whose `Schema` reduces to one of those). Rejection throws `SchemaNotSerializableException` with a message prefixed `"non-canonical proto3 map key: "`. No bytes are written before the throw.

`Conformance.Permissive`: all map key types are permitted. Non-canonical keys use the entry-message encoding and round-trip through this codec; they are not standard proto3 `map<K,V>` externally.

The `given Protobuf = Protobuf()` zero-argument instance is Strict. Permissive requires an explicit config:

```scala
given Protobuf = Protobuf(Protobuf.Config(conformance = Protobuf.Conformance.Permissive))
```

### fieldNumberAudit and FieldNumberInfo

`Protobuf.fieldNumberAudit[A]` is pure and total: no encode, no decode, no throw. It returns `Chunk[FieldNumberInfo]`, one row per message field, in declaration order, depth-first for nested messages.

`FieldNumberInfo` fields:
- `path`: dotted path from the root message (e.g., `"inner.id"` for a nested field)
- `name`: leaf field name
- `number`: the wire field number this codec uses (XXH32 formula or a leaf-name override)
- `pinned`: true when a single-segment (leaf field-name) `Schema.fieldId` override is in effect and is wire-functional; nested-path (multi-segment) overrides are a consistent no-op (`pinned` stays false), deferred to getkyo/kyo#1719
- `inReservedRange`: true when `number` is in proto3's reserved band 19000-19999, which external `protoc` rejects

The field-id formula is fixed and identical across `CodecMacro.fieldId`, `fieldNumberAudit`, and `protoSchema`:

```
(XXHash.hash32(name) & 0x1fffff) + 1
```

### Packed and unpacked repeated scalars

Scalar repeated fields (`List[Int]`, `Vector[Double]`, `Set[Boolean]`, and so on) are emitted as a single packed length-delimited record (wire type 2). Non-scalar repeated fields (repeated messages, repeated strings, repeated bytes) stay per-element. The reader accepts both packed (wire type 2) and unpacked (element's primitive wire type) forms, so data from proto3 producers that emit unpacked decodes correctly.

This dual-handling invariant must be preserved: adding a new scalar reader arm must accept both forms.

### protoSchema emits wire-true field numbers

`Protobuf.protoSchema[A]` emits the XXH32-derived field number for every message field, the same number the codec writes on the wire. Hash-derived fields carry a line-end provenance comment. A pinned field (leaf-name `Schema.fieldId` override) carries no provenance comment. A field in proto3's reserved range 19000-19999 carries a WARNING comment unconditionally.

Structural slots (oneof variant numbers, MapEntry key=1/value=2) are not affected and keep their structural assignments.

### Leaf-name Schema.fieldId pin is wire-functional

A single-segment `Schema.fieldId` override (e.g., `Schema[User].fieldId(_.id)(1)`) is wire-functional: encode writes the pinned number on the wire, decode matches it, and `protoSchema`/`fieldNumberAudit` report it. Pinning is keyed by field NAME and is global: pinning `id` to 1 assigns field 1 to every field named `id` across all messages in that schema tree. Nested-path overrides (multi-segment, e.g., `_.inner.id`) are a consistent no-op across all five surfaces, deferred to getkyo/kyo#1719.

## Extension Recipes

### Add a Schema for a new primitive

Define a `given <name>Schema: Schema[A]` in `object Schema`, inlining `Schema.init[A]` with the typed `Writer.<prim>` / `Reader.<prim>` calls and a `Structure.Type.Primitive` descriptor [`Schema.intSchema`]:

```
given intSchema: Schema[Int] = Schema.init[Int](
    writeFn = (v, w) => w.int(v),
    readFn = _.int(),
    structure = Structure.Type.Primitive(Structure.PrimitiveKind.Int, Tag[Int].asInstanceOf[Tag[Any]])
)
```

The `Tag[A].asInstanceOf[Tag[Any]]` cast is the existing pattern for the positional `Tag` slot.

### Add a Schema for a string-like primitive (UUID, LocalDate, LocalDateTime, Instant)

Keep `Structure.PrimitiveKind.String`, use `w.string(v.toString)` on write and `parse(r.string())` on read; the structure kind reflects the wire shape, not the Scala type [`Schema.localDateTimeSchema`]:

```
given localDateTimeSchema: Schema[java.time.LocalDateTime] =
    Schema.init[java.time.LocalDateTime](
        writeFn = (v, w) => w.string(v.toString),
        readFn = r => java.time.LocalDateTime.parse(r.string()),
        structure = Structure.Type.Primitive(Structure.PrimitiveKind.String, Tag[Any])
    )
```

**Trap (`Tag[Any]` for Java time types)**: `LocalDateTime` falls back to `Tag[Any]` (not `Tag[java.time.LocalDateTime].asInstanceOf[Tag[Any]]`) because of a Scala 3 inline limitation with Java class tags. Copy that pattern when adding a `java.time.*` primitive whose `Tag` does not synthesize cleanly in an inline structural position; the wire shape is unaffected [`Schema.localDateTimeSchema`].

### Add a Schema for a new container (one type parameter)

Write a non-inline given parameterized by an inner Schema and `frame`, using `Structure.Type.Collection` for the structure shape; the inner Schema is summoned via `using`, not via `summonInline`, and the positional Tag is `Tag[Any]` because non-inline givens have no implicit `Tag[A]` in scope [`Schema.chunkSchema`]:

```
given chunkSchema[A](using inner: Schema[A], frame: Frame): Schema[Chunk[A]] =
    Schema.init[Chunk[A]](
        writeFn = ...,                            // arrayStart / foreach(inner.serializeWrite) / arrayEnd
        readFn  = ...,                            // arrayStart / @tailrec loop / arrayEnd
        structure = Structure.Type.Collection("Chunk", Tag[Any], inner.structure)
    )
```

Container readers wrap the per-element loop in `@tailrec` and call `reader.checkCollectionSize(count)` before each element to enforce the DoS limit; copy this shape verbatim for any new array-backed container [`Schema.listSchema`]:

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

`listSchema` and `vectorSchema` follow the exact same recipe; only the collection-name string and builder change.

### Add an Optional-style container (null-on-absent encoding)

Match on the present/absent variants on write (writing `writer.nil()` for absent), check `reader.isNil()` on read, and use `Structure.Type.Optional(name, Tag[Any], inner.structure)` as the structure shape [`Schema.maybeSchema`, `Schema.optionSchema`]:

```
given maybeSchema[A](using inner: Schema[A], frame: Frame): Schema[Maybe[A]] = ...
```

### Add a key-value container

Write/read with `writer.mapStart(size)` / `reader.mapStart()` and emit `writer.field(k, idx)` per entry; the structure is `Structure.Type.Mapping(name, Tag[Any], keyStructure, valueStructure)`. The `Map[String, V]` case uses the inline String-primitive node for the key [`Schema.stringMapSchema`]:

```
given stringMapSchema[V](using valueSchema0: => Schema[V]): Schema[Map[String, V]] = ...
```

The value Schema is a by-name `using` parameter forced into a `lazy val` in the body; that is what keeps a recursive container type from diverging at given-resolution [`Schema.stringMapSchema`].

Each container gets two givens, not one: a String-keyed given that encodes as an object, and a general given for every other key type that encodes each entry as a two-field record. The String-keyed one is the more specific given and wins for `Map[String, V]`, while `Map[Int, V]` and friends fall to the general one [`Schema.stringMapSchema`, `Schema.mapSchema`].

**A map-like given must declare `absentDefaultValue`.** proto3 writes nothing for an empty map field, so a decode that sees no bytes must still produce an empty container rather than failing on a missing field. All six map givens declare it, and a new one that omits it will decode an absent field as an error instead of as empty [`Schema.stringMapSchema`, `Schema.mapSchema`, `Schema.stringDictSchema`, `Schema.dictSchema`, `Schema.stringOrderedDictSchema`, `Schema.orderedDictSchema`]:

```
absentDefaultValue = Maybe(Map.empty[String, V]),
```

`Dict` follows the same pair [`Schema.stringDictSchema`, `Schema.dictSchema`].

#### Order-preserving containers

`OrderedDict[K, V]` has the same two givens [`Schema.stringOrderedDictSchema`, `Schema.orderedDictSchema`], with one difference that matters when adding a container whose iteration order is part of its contract: the read loop must rebuild the container in wire order rather than collect into an unordered builder. `stringOrderedDictSchema` folds `map.update(k, v)` over the entries as they arrive, so the decoded order is the encoded order [`Schema.stringOrderedDictSchema`].

The order guarantee has a boundary a contributor should not overstate. It holds between a kyo-schema encode and a kyo-schema decode. The generated `.proto` declares a `map<K, V>` field and proto3 does not mandate entry order for map fields, so a foreign Protobuf implementation may reorder entries [the comment on `Schema.stringOrderedDictSchema`].

### Add a newtype / opaque-type Schema

Call `.transform[B](to)(from)` on the underlying primitive's Schema in the companion object; the resulting Schema delegates encode/decode through the wrapper and shares the underlying structure [`Schema.transform`]:

```
opaque type Email = String
object Email:
    given Schema[Email] = Schema.stringSchema.transform[Email](identity)(identity)
```

### Add `derives Schema` support for a user ADT

There is no recipe. `case class Foo(...) derives Schema` or `sealed trait Bar derives Schema` is the entire surface; the macro walks `sym.caseFields` (case classes) or `sym.children` (sealed traits) and resolves each nested type via `summonInline[Schema[ft]]`, so any user `given Schema[X]` already in implicit scope plugs in without macro changes [`FocusMacro.derivedImpl`].

Two compile-time refusals to be aware of:

- **Private case-fields**: rejected because the macro would otherwise emit the private storage name on the wire; for a type with private case-fields, hand-roll a `given Schema` instead [`FocusMacro.rejectPrivateCaseFields`].
- **More than 64 fields**: rejected because the generated decoder packs required-field tracking into a `Long` bitmap; for wider types, refactor the type or hand-roll a Schema [`FocusMacro.emitProductSchemaStatic`].

### Add a custom Schema for a user-defined container at a nested field position

Write the data type plus a non-inline given (the container's Schema is summoned via `using`, not `summonInline`) [`boxSchema` in `kyo-schema-json/shared/src/test/scala/kyo/SchemaCustomContainerNestedTest.scala`]:

```
case class Box[A](item: A) derives CanEqual
given boxSchema[A](using inner: Schema[A], frame: Frame): Schema[Box[A]] =
    Schema.init[Box[A]](...)
```

### Add a new wire format (Codec)

A new format is a new `kyo-schema-<format>` sbt module depending on the core (mirror `kyo-schema-msgpack`, the smallest one, in build.sbt), never new files inside the core. Extend `abstract class Codec` with `def newWriter(): Codec.Writer` and `def newReader(input: Span[Byte])(using Frame): Codec.Reader`, then provide concrete `Codec.Writer` / `Codec.Reader` subclasses for the format. Schema-derived traversal is format-agnostic; adding a new wire format does NOT touch `Schema` or `SchemaCodecRuntime` [the `Codec` scaladoc].

Minimum shape: a `final class` extending `Codec`, the two factory methods delegating to package-private `kyo.internal.<Format>Writer` / `kyo.internal.<Format>Reader` implementations of `Codec.Writer` / `Codec.Reader`. Mirror `Json` (the simplest one) when wiring a new format [the `Json` class]:

```
final class Json extends Codec:
    def newWriter(): Codec.Writer = kyo.internal.JsonWriter()
    def newReader(input: Span[Byte])(using Frame): Codec.Reader =
        kyo.internal.JsonReader(input)
```

Each codec's companion object provides a `given <Codec> = <Codec>()` so codec-polymorphic call sites (e.g. `summon[Json].newWriter()`) resolve without explicit codec construction [the `given Json` in the `Json` companion]:

```
given Json = Json()
```

A codec that carries configuration (Yaml's `writerConfig`) uses a default-argument constructor to keep the `given Yaml = Yaml()` form valid while letting callers construct configured instances [the `Yaml` class]:

```
final class Yaml(writerConfig: Yaml.WriterConfig = Yaml.WriterConfig.Default) extends Codec:
```

If the new format is **self-describing** (can materialize an arbitrary wire value into `Structure.Value` without a schema), its Reader subclass should also extend `Codec.IntrospectingReader` and implement `readStructure(): Structure.Value`. Json and Yaml extend it; Protobuf does not [the `Codec.IntrospectingReader` scaladoc]. Ion Binary and BSON are both self-describing binary formats and both implement `readStructure` this way [`IonBinaryReader.readStructure`] [`BsonReader.readStructure`].

**Private codec vocabulary policy.** A format's own wire vocabulary (BSON's element type bytes in `BsonFormat`, Ion Binary's type descriptor bytes in `IonBinaryFormat`) stays `private[kyo]`/internal to the codec package; it is never promoted to a public type. Every wire value a new codec can represent maps losslessly onto an EXISTING `Structure.Value` case (extending `PrimitiveKind` only when the value genuinely has no existing base-value home, per [`PrimitiveKind` is exhaustive](#primitivekind-is-exhaustive)); a wire value with no lossless mapping stays unsupported and is rejected with a typed exception rather than approximated. `BsonReader`/`IonBinaryReader`'s `readStructure` implementations are the worked precedent for both halves of this policy: map what has a home, reject what does not [`BsonFormat`] [`IonBinaryFormat`].

**Ion config routing.** `Ion.Config(format = Ion.Format.Binary)` does not change `Ion`'s public surface; `Ion.newWriter`/`Ion.newReader` branch on `config.format` and delegate to the same internal Ion Binary writer/reader `IonBinary` uses directly, so the two entry points (the codec-polymorphic `Ion` value and the standalone `IonBinary` codec) share one implementation rather than diverging [`Ion.newWriter`, `Ion.newReader`].

**Ion Binary and BSON authoring notes.** Both codecs eagerly materialize their reader state at construction (`IonBinaryReader` walks the binary stream into an in-memory value tree; `BsonReader` parses the whole document into a `BsonValue` tree), enforcing `maxDepth`/`maxCollectionSize` during that parse rather than lazily during traversal; a malformed wire value is rejected at construction, before any schema traversal begins. A new binary codec that wants the same fail-fast contract should follow this eager-parse shape rather than validating limits per-field during traversal.

**BSON requires an object-shaped root.** BSON is a document format, so `BsonWriter` rejects any top-level value that is not a document: a top-level array is refused at `arrayStart`, and a non-document root value is refused in `result()` / `pushValue`, both raising `SchemaNotSerializableException` with the message `BSON requires a top-level document` before any bytes are returned [`BsonWriter.arrayStart`] [`BsonWriter.result`, `BsonWriter.pushValue`]. This is the encode-side companion to the positive `canWriteTopLevelNonObject` opt-in that BSON leaves `false`: the capability flag gates the `Tuple` / `TupleFlat` / `Untagged` sum representations (see [Codec capability for top-level non-object shapes](#codec-capability-for-top-level-non-object-shapes)), and the writer's own root guard rejects any other non-object root, such as a top-level scalar or array schema. A new document-oriented codec enforces the same shape at its writer root rather than emitting an invalid stream.

**Decode limits are copied from config onto the reader, never owned by it.** `Codec.Reader` carries its own `maxDepth` / `maxCollectionSize` fields seeded with the shared defaults (512 / 100000) [`Codec.Reader.maxDepth`, `Codec.Reader.maxCollectionSize`]. A configured codec does not construct a reader that already knows those limits; it constructs the reader and then copies its config's limits onto it via `reader.resetLimits(config.maxDepth, config.maxCollectionSize)` [`Codec.Reader.resetLimits`] [`Ion.newReader`]. Per-call `decode` overloads take explicit `maxDepth` / `maxCollectionSize` and call `resetLimits` again after construction, so a per-call override wins over the contextual instance's config [`IonBinary.decode`]. BSON is the one exception: `BsonReader`'s constructor already knows its limits and self-seeds them from the passed `Bson.Config` [the `BsonReader` constructor], so `Bson.newReader` and `Bson.decode` construct the reader and read directly, with no follow-up `resetLimits` call [`Bson.newReader`]. A new codec that carries decode limits threads them the same way as Json/Ion/IonBinary: default on the reader base, copy from config in `newReader` or at the decode call site, allow a per-call override through `resetLimits`, never bake the limit into the reader constructor unless it follows BSON's self-seeding exception instead.

**Annotation emission is an opt-in writer SPI whose default is a no-op.** `Codec.Writer.annotations(values)` defaults to `()` and `canWriteAnnotations` defaults to `false`, so a codec that cannot carry metadata ignores schema annotations and keeps its wire output unchanged [`Codec.Writer.annotations`, `Codec.Writer.canWriteAnnotations`]. The Ion codecs are the worked precedent for opting in: `IonBinaryWriter` overrides `canWriteAnnotations` to `config.annotationEmissionMode == Ion.AnnotationEmissionMode.Emit` and only then accumulates the captured annotations in its `annotations` override, so emission stays off unless the caller selects `Ion.Config(annotationEmissionMode = Ion.AnnotationEmissionMode.Emit)` [`IonBinaryWriter.canWriteAnnotations`] [`IonBinaryWriter.annotations`]. A new codec that can represent metadata overrides both members; one that cannot leaves the defaults and is unaffected.

### Add a new built-in annotation

A built-in annotation is a compile-time spelling of an EXISTING programmatic builder; if the builder does not exist yet, add the builder/slot first (see [The dual-flag invariant](#the-dual-flag-invariant) and [Adding a slot to Schema](#adding-a-slot-to-schema-the-construction-site-and-bytecode-traps)). Then:

1. Declare the annotation in `SchemaAnnotation.scala`, package `kyo.schema`, extending `SchemaAnnotation` so it is always captured. Keep schema-general annotations flat; scope a format-specific one under a format-named owner [`kyo.schema.SchemaAnnotation`].
2. For a behavior arg, use the object-reference pattern (sealed interface + named object, mirror `Transformer` / `OmitPredicate`), never a closure. For an enum arg, nest it in the annotation's companion so the enum name cannot collide with the class name on a case-insensitive filesystem (the `omit.Mode` precedent) [`kyo.schema.omit`, `kyo.schema.Transformer`, `kyo.schema.OmitPredicate`].
3. Add the desugar arm in `desugarProductConfig` (a field/type leaf) or `desugarSumConfig` (a sum/variant leaf), reading the leaf off `Symbol.annotations` and writing its config onto the macro-local config carrier whose fields map 1:1 onto `Schema.init` params [`FocusMacro.desugarProductConfig`, `FocusMacro.desugarSumConfig`].
4. If the arg is an object reference or a constructed case-class / enum value, confirm `reifyAnnotation` accepts the term shape (constant literals, `new`-applied stable constructors, stable module/val/enum refs, stable case-class/enum-case applies); a method-call or closure arg is non-liftable [`FocusMacro.reifyAnnotation`].

A custom (non-built-in) marker needs only step 1: extend `SchemaAnnotation`, and the codec author reads instances back off `schema.structure` via `annotationOf[A]` / `fieldsWith[A]` and drives behavior directly. No desugar arm is added for a marker that has no programmatic-builder equivalent.

## Testing

A test lives in the most specific module whose classpath covers it: core-only suites (Schema, Structure, Codec SPI, `internal/` math) in `kyo-schema`, single-format suites in that format's module (e.g. `JsonTest` in kyo-schema-json), and any suite exercising two or more formats at once in the unpublished `kyo-schema-tests` (sbt cannot express mutual test-scope dependencies between sibling format modules). `kyo-schema-tests` also hosts the doctest validation of `kyo-schema/README.md`, whose blocks span every format.

### Base trait and equality

Every test suite extends `kyo.test.Test[Any]`, never ScalaTest directly [`SchemaTest`]. `Test[Any]` is the explicit Scala 3 spelling for the common case where leaves need only the baseline effects; kyo-schema suites never widen `S` [the `kyo.test.Test` scaladoc].

Every suite opens with `given CanEqual[Any, Any] = CanEqual.derived` so heterogeneous `==` comparisons inside `assert` compile under strict equality [`SchemaTest`].

Internal-package tests under `shared/src/test/scala/kyo/internal/` follow the same base-class convention [`FastFloatTest`].

When two `Structure.Type` instances must be compared with `==` (tag equality), add a local `given CanEqual[Structure.Type, Structure.Type] = CanEqual.derived` alongside the baseline `Any` instance [`StructureTest`].

### Assertion styles, by kind

**Round-trip** is the canonical behavioral check: write with the format API, read back, assert equality with the original value [`JsonTest` "json round-trip"]:

```
val person = MTPerson("Bob", 25)
val bytes  = Json.encodeBytes[MTPerson](person)
val result = Json.decodeBytes[MTPerson](bytes).getOrThrow
assert(result == person)
```

`CodecTest` factors round-trip into a `CodecTestHelper.roundTrip[A]` (in the core's test scope, shared via test->test) that drives an in-memory `TestWriter` -> `TestReader` token stream through `schema.writeTo` / `schema.readFrom`, so primitive and case-class round-trips share one helper [`CodecTestHelper.roundTrip` in `kyo-schema/shared/src/test/scala/kyo/CodecTestFixtures.scala`].

**Token-shape** assertions verify the exact ordered token stream the writer emits, pinning wire shape independently of decoder symmetry [`CodecTest` "person encode produces correct tokens"].

**Structure-tree** assertions cast `Schema[A].structure` to the expected `Structure.Type.Product` / `Sum` / `Collection` / `Primitive` variant and assert on `.fields`, `.name`, `.elementType`, and `.typeParams` [`SchemaCustomContainerNestedTest` "Holder structure carries Box at the nested fieldType matching boxSchema[Int].structure shape"]:

```
val product = holder.structure.asInstanceOf[Structure.Type.Product]
```

When two distinct summons of a polymorphic `given def` Schema cannot be compared by reference, use `Structure.Type.compatible` for structural equality and pattern-match the variant for shape [the same test]:

```
assert(
    Structure.Type.compatible(fieldType, boxInt.structure),
    s"expected structural compat with boxSchema[Int].structure but got $fieldType"
)
```

**Decode results** return `Result[A]`; tests assert success either by `.getOrThrow` followed by value equality, or by `assert(result == Result.succeed(expected))` [`JsonTest` "Json.decode from String returns Success"]. Failure cases on `Json.decode` are asserted with `.isFailure`, never `try / catch` [`JsonTest` "Json.decode from invalid JSON returns failure"].

**Trap (cast without fallback)**: an `asInstanceOf[Structure.Type.Product]` (or any other variant) without a matching `case _ => fail(s"...")` arm produces a `ClassCastException` instead of a readable assertion message. Pair every cast with either a guard pattern-match plus `fail`, or `assert` right after the cast [`SchemaCustomContainerNestedTest` "Holder structure carries Box at the nested fieldType matching boxSchema[Int].structure shape"].

### Compile-time tests

**Type-resolution-only leaves** use a typed `val _: Schema[X] { type Focused = ... } = m` ascription and discharge with `succeed("type-resolution compile check: ...")`; there is no runtime equality to assert because the compile is the verification [`SchemaTest` "apply simple case class"].

**Negative compile checks** (focus on a nonexistent field, defaults access on a field without a default) use `typeCheckFailure(src)(expectedSubstring)` from the kyo-test base, asserting both that the snippet does not compile and that the error mentions the right token [`SchemaTest` "schema navigate nonexistent compile error"]:

```
typeCheckFailure("Schema[kyo.MTPerson].focus(_.nonexistent)")("not found")
```

**`derives`-clause failure tests**: when the failing snippet must include a `derives` clause that itself fails (so it cannot be lifted into `typeCheckFailure`'s context), drive `scala.compiletime.testing.typeChecks` / `typeCheckErrors` directly and assert on the head error's message [`SchemaStructureTest` "Derivation rejects case class with a private case-field"]:

```
val src      = "case class Bad(private val x: Int) derives kyo.Schema"
val compiles = scala.compiletime.testing.typeChecks(src)
```

### Recursive-ADT regression guards

Recursive ADTs MUST be derivable without `StackOverflowError`; the regression test touches the full structure tree of a self-recursive `case class Tree(children: List[Tree])` to exercise the cycle-break path [`SchemaStructureTest`]:

```
"Self-recursive case class derives Schema without StackOverflow" in { ... }
```

The `Box[Holder]` regression guard exercises a user-defined container at a recursive position; the cycle-break is purely structural (the outer Schema's `lazy val structure` plus the by-name `Structure.Field._fieldType`), and any failure surfaces as `StackOverflowError` [`SchemaCustomContainerNestedTest` "Box[Holder] recursive Schema construction breaks the cycle (binding regression guard)"].

The generic-sealed-trait regression guards that `buildSumSchema` populates `Structure.Type.Sum.typeParams`; the test comment states what it pins [`StructureTest`]:

```
"derived generic sealed trait populates typeParams" in {
    // Regression guard
```

### Fixtures: shared vs. ad-hoc

Recursive ADT fixtures used as shared regression carriers live in `SchemaTestData.scala` and ship `derives Schema` (sometimes alongside `derives CanEqual`) so multiple suites can summon their Schema without redeclaring it [`TreeNode` in `kyo-schema/shared/src/test/scala/kyo/SchemaTestData.scala`]:

```
case class TreeNode(value: Int, children: List[TreeNode]) derives CanEqual, Schema
```

**Trap (orphan fixture)**: adding a recursive ADT to `SchemaTestData.scala` without `derives Schema` (or an explicit companion `given Schema[X] = Schema.derived[X]`) breaks every downstream suite that summons it; the existing `TreeNode` / `Expr` / `RTDepartment` / `RTEmployee` fixtures are the templates for both shapes.

Mutually recursive ADTs whose cycle crosses two companions use explicit `given Schema[X] = Schema.derived[X]` in each companion (instead of `derives Schema` on the type) so the derivation summons see a present given on the recursive backedge [`RTDepartment`, `RTEmployee` in `SchemaTestData.scala`]:

```
object RTDepartment:
    given Schema[RTDepartment] = Schema.derived[RTDepartment]
```

Generic sealed-trait regression fixtures (`GenericSealed[A]`) live as top-level test types in the test source's package so they have a stable `typeParams` to inspect; variants are kept concrete to avoid forcing the macro to substitute the parent's type argument [`GenericSealed` in `kyo-schema-tests/shared/src/test/scala/kyo/StructureTest.scala`].

Per-test ad-hoc recursive case classes are declared inside the `in { ... }` block when the regression is local to that leaf, never bled into the shared fixture file [`SchemaCustomContainerNestedTest` "Indirect-recursive user container at recursive position resolves through the user's given"]:

```
case class BoxedHolder(payload: Box[BoxedHolder]) derives CanEqual, Schema
```

Structure-introspection helpers shared across a single suite are declared once as `private def` on the suite class (e.g. `toStructure` / `fromStructure` in `StructureTest`), not duplicated per leaf.

### Cross-platform and timing

All behavioral tests for kyo-schema live in `shared/src/test`; the module's test tree has no `jvm/src/test`, `js/src/test`, `native/src/test`, or `wasm/src/test` directory and no platform-tagged leaves [`kyo-schema` in `build.sbt`]. `.withKyoTest` wires the per-platform `kyo-test-runner` jar onto the test classpath and registers the platform's `TestFramework`; this is what makes a single `class FooTest extends kyo.test.Test[Any]` in `shared/src/test` runnable on JVM, JS, Native, and Wasm [`withKyoTest` in `project/WithKyoTest.scala`].

kyo-schema does not override `Test / parallelExecution`, `Test / fork`, or `Test / testForkedParallel`; its tests run under the kyo-settings defaults inherited from the build, with no per-platform test-config overrides [`kyo-schema` in `build.sbt`].

Test leaves are pure synchronous bodies; there is no `Async.sleep`, `Clock.sleep`, `Latch`, `Channel`, `Fiber.get`, `Thread.sleep`, or `synchronized` anywhere in the kyo-schema test tree. Schema correctness is data-equality, not a race, so the deterministic-timing toolkit other modules need does not apply here [kyo-schema/shared/src/test/scala/kyo/].

## Decision Checklist: Before Adding a New X

Run through this list before touching the derivation path or adding a new public surface.

1. **Is this a new primitive, container, optional, map, newtype, ADT, or codec?** Pick the matching recipe above and copy it verbatim. The recipes are mature; deviating without a specific reason is how regressions enter.
2. **For a new primitive or container**, does the structure carry the correct `PrimitiveKind` / `Structure.Type.Collection` / `Optional` / `Mapping` variant? `PrimitiveKind` is closed and exhaustively matched by every codec; adding a kind is not a one-file change [`Structure.PrimitiveKind`].
3. **For a container**, is the reader `@tailrec` and does it call `reader.checkCollectionSize(count)` before each element? The DoS limit is enforced per-element, not at the top of the loop [`Schema.listSchema`].
4. **For a hand-rolled Schema with a recursive structure**, is `_structure` a `lazy val` and is `Structure.Field._fieldType` constructed by-name? Both cycle-breaks are required jointly. Strictly evaluating either side is how recursive derivations deadlock [`Schema.init`] [`Structure.Field.apply`].
5. **For a hand-rolled Schema with private case-fields** (e.g. the storage thunk in `Structure.Field`), have you provided an explicit `given` rather than relying on derivation? The macro rejects private case-fields at compile time [`FocusMacro.rejectPrivateCaseFields`].
6. **For a hand-rolled Schema with custom `equals`**, does it force the by-name thunks before comparing structures? The default case-class `equals` compares `Function0` references and reports `false` for structurally identical instances [`Structure.Field.equals`].
7. **For a new variant of `Structure.Type`**, have you updated the hand-pinned `given Schema[Structure.Type] = Schema.derived` and every exhaustive `PrimitiveKind` match in the codec backends? The wire shape is gated by code review at this given [`Structure.Type.structureTypeSchema`].
8. **For a new decode throw site**, is it `using reader.frame` so the diagnostic points at the user's call-site, not the codec? [`Codec.Reader.frame`].
9. **For a new public decode entry point**, does it return `Result[DecodeException, A]` by wrapping a throw-based reader call in `Result.catching[DecodeException]`? Navigation surfaces return `Result[NavigationException, _]` directly [`Json.decode`] [`Structure.Path.get`].
10. **For a new exception leaf**, is it a `case class` mixing one of the four marker traits (`DecodeException`, `ValidationException`, `TransformException`, `NavigationException`) into `SchemaException`, with `(using Frame)` and the message inline? Does it derive `CanEqual` if it appears in a navigation path? [`MissingFieldException`, `UnknownVariantException`].
11. **For a new Codec**, is it a `final class extends Codec` with two factory methods delegating to `kyo.internal.<Format>Writer` / `kyo.internal.<Format>Reader`, plus a `given <Codec> = <Codec>()` in the companion? Does the Reader extend `Codec.IntrospectingReader` if (and only if) the format is self-describing? [the `Json` class and companion] [the `Codec.IntrospectingReader` scaladoc].
12. **For a derivation-touching change**, does the macro still emit ONE call into `SchemaCodecRuntime.buildProductSchema` / `buildSumSchema` per derived type, with all per-field metadata in one string literal? Per-field branching inside the emitted methods is what blows the JVM class-file limit on test classes with many derivations [kyo-schema/shared/src/main/scala/kyo/internal/SchemaCodecRuntime.scala] [`FocusMacro.derivedImpl`].
13. **For a regression on the recursive-derivation path**, have you added a test in `shared/src/test` that exercises the full structure tree (not just `schema.structure`, the children's children too)? Recursive ADT fixtures shared across suites belong in `SchemaTestData.scala` with `derives Schema`; per-leaf ad-hoc recursive types belong in the `in { ... }` block [`SchemaStructureTest` "Self-recursive case class derives Schema without StackOverflow"] [`TreeNode` in `SchemaTestData.scala`].
14. **For a new platform-specific runtime file**, is the JVM/JS/Native/Wasm split genuinely required by a platform capability difference? If not, keep it in `shared/`. Runtime format implementations are currently shared across all platforms; `FastFloatPow10Gen` is JVM-specific only because it is a build-time table generator, not runtime code [the `FastFloatPow10Gen` scaladoc].
15. **For a new sum wire representation**, have you (a) added the case to `Schema.UnionRepresentation` and confirmed `nonDefault` returns `true` for it (so it reaches the transform-aware path through both `hasTransforms` and `hasReadTransforms`), (b) added the encode arm in `SchemaSerializer.writeWithTransforms` and the decode arm in `Schema.transformedRead`, (c) added a `DelegatingWrapperReader` subclass (for a tagged shape) or a capture/replay path (for an untagged shape), (d) gated any top-level-array / bare-scalar shape behind `requireTopLevelCapable` so it raises `RepresentationUnsupportedException` on a codec that has not opted in via `canWriteTopLevelNonObject`, and (e) updated `Schema.representationExpressibleBy` so chain selection knows whether the new case is expressible by a given capability profile? The macro does NOT change: variant Schemas stay inline and the representation is resolved at the `SchemaSerializer` layer [`Schema.transformedRead`, `Schema.hasTransforms`, `Schema.hasReadTransforms`] [`SchemaSerializer.writeWithTransforms`] [`SchemaSerializer.DelegatingWrapperReader`].
16. **For ANY new `Schema` slot** (representation, naming, omit, chain, ambiguity, or a new transform), have you (a) ORed it into `hasTransforms` and/or `hasReadTransforms` per the [dual-flag invariant](#the-dual-flag-invariant) (both for a write+read slot, `hasReadTransforms` only for a decode-only slot like `unionAmbiguityPolicy` / `denyUnknownFieldsEnabled` / `fieldDefaults`), (b) threaded it through ALL FOUR construction sites (`init`, `initFocused`, `createWithFocused`, `copyWith`) AND through `SchemaFactory.createFrom` (the de-facto fifth site: a `createFrom` that drops the slot resets it on the next `.drop` / path-affecting builder), (c) confirmed the inert default leaves both flags `false` so the default-byte-identity guarantee holds, and (d) done a HARD clean (`target/`, `.bloop/`, `.bsp/`, `project/`), not `sbt clean`, before trusting the build? A missed flag or construction site COMPILES and silently drops the feature [`Schema.hasTransforms`, `Schema.hasReadTransforms`].
17. **For a representation fallback chain**, does the encode side select via `representationExpressibleBy` against `writer.capabilities` (emitting one representation, or `RepresentationUnsupportedException` if none is expressible) and the decode side try the chain in declared order over a fresh reader per attempt? Is the chain rejected for emptiness/duplicates at the builder call via `checkRepresentationChain` (`DuplicateRepresentationException`)? [`SchemaSerializer.selectRepresentation`, `SchemaSerializer.readChain`] [`Schema.checkRepresentationChain`].
18. **For a field-omission feature**, does the empty-collection gate consult `isCollectionOrMapTag` (declared-type-aware) so an empty product/case-object Record is never dropped, and do all three paths (encode-omit `omitField`, decode-default synthetic injection in `TransformAwareReader`, and `zeroForField`) share that ONE predicate? Decode-default for a missing collection needs the synthetic-wire-token injection, not a `zeroForField` slot seed, because the macro hard-seeds collection slots `null` (see [The macro-decode protocol](#the-macro-decode-protocol-null-seed--required-mask)) [`SchemaSerializer.isCollectionOrMapTag`, `SchemaSerializer.omitField`] [`SchemaSerializer.readWithTransforms`].
19. **For a type-union (`A | B`) change**, does the macro's union arm keep `flattenUnion` order-preserving (no `Set`), default the union to `Untagged`, and name the `Structure.Type.Sum` `"Union"` so `readUntagged` routes it to multi-probe? Type unions default `Strict` ambiguity (raise on >1 match); nominal untagged sums keep first-declared-wins. Do not collapse the two decode paths [`FocusMacro.flattenUnion`, `FocusMacro.emitUnionSchemaStatic`] [`SchemaSerializer.readUntagged`].
20. **For a new annotation** (see [Annotation-driven configuration](#annotation-driven-configuration)), is it in package `kyo.schema` extending `SchemaAnnotation`, and does a BUILT-IN desugar onto an EXISTING programmatic config slot (no new runtime behavior) via a `desugarProductConfig` / `desugarSumConfig` arm? Does a behavior arg use the object-reference pattern (sealed interface + named object) rather than a closure, and an enum arg nest in the annotation's companion to dodge a case-insensitive name collision? Is the new node's `annotations` carrier excluded from `equals` / `hashCode` / `compatible` and the wire (introspection metadata, not structural identity), keeping byte-identity for an unannotated type? [`kyo.schema.SchemaAnnotation` and the built-ins in `SchemaAnnotation.scala`] [`FocusMacro.reifyAnnotation`] [`Structure.Type.Product.equals`].
21. **For a per-field serialization-customization builder** (strict decode, decode default, predicate omit, per-field transform), have you (a) populated a `private[kyo]` carrier from the builder/macro rather than exposing a public constructor, (b) ORed the slot into the right flag (`denyUnknownFieldsEnabled` / `fieldDefaults` read-only; `fieldTransforms.nonEmpty` into BOTH even for a one-direction transform; an omit-policy entry through `omitPolicies.nonEmpty`), (c) threaded it through the four construction sites AND `SchemaFactory.createFrom`, (d) kept the customization at the `SchemaSerializer` layer (the macro emits raw names; encode order is transform-write -> omit -> rename/naming/representation, decode applies read-overrides for present fields and synthetic defaults for absent ones), and (e) for any decode-time injection state, added a per-instance `var` on `TransformAwareReader` (the single-instance, non-fiber-crossing decode-reader carve-out), never a shared field? [`Schema.hasTransforms`, `Schema.hasReadTransforms`] [`SchemaSerializer.writeWithTransforms`] [`SchemaSerializer.readWithTransforms`] [`SchemaFactory.createFrom`].
