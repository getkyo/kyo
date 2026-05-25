# Followup Execution Plan — 14 Phases

This plan executes the 14-phase DAG locked in [analysis.md](./analysis.md). Each phase compiles standalone with phases 1..N-1.

Every phase verifies cross-platform via `+ kyo-schema/Test/compile` and runs `kyo-schema/Test/test` on JVM. Items concerning kyo-data add `+ kyo-data/Test/compile`.

---

### Phase 1 — Strip Phase X references from production code (Item 4)

**Dependency justification**: Must run first; subsequent phases edit the same files (`Schema.scala`, `MacroUtils.scala`, `SerializationMacro.scala`, `Json.scala`, `Protobuf.scala`, `KeyCodec.scala`) and would compound rebase noise with later edits.

### Files to produce
None.

### Files to modify
- `kyo-schema/shared/src/main/scala/kyo/Json.scala` — line 228 comment `// Phase 2: Instant / Duration / Frame / Text serialize as JSON strings.` -> `// Instant / Duration / Frame / Text serialize as JSON strings.`
- `kyo-schema/shared/src/main/scala/kyo/KeyCodec.scala` — line 6 scaladoc `(Phase 8)` removed (file later deleted in Phase 8 but cleanup here keeps the chronology clean).
- `kyo-schema/shared/src/main/scala/kyo/Protobuf.scala` — line 223 comment `// Phase 2: Instant / Duration / Frame / Text are serialized as strings on the wire` -> `// Instant / Duration / Frame / Text are serialized as strings on the wire`
- `kyo-schema/shared/src/main/scala/kyo/internal/MacroUtils.scala` — lines 243, 276, 278, 302: drop `Phase N` provenance prefix from each comment (the comment body that follows stays).
- `kyo-schema/shared/src/main/scala/kyo/internal/SerializationMacro.scala` — lines 1129, 1138, 1144, 1156, 1165, 1210: drop `Phase N` / `Added Phase N` prefixes; keep substantive content.
- `kyo-schema/shared/src/test/scala/kyo/CodecTest.scala`, `kyo-schema/shared/src/test/scala/kyo/ProtobufTest.scala`, `kyo-schema/shared/src/test/scala/kyo/StructureTest.scala`, `kyo-schema/shared/src/test/scala/kyo/SchemaTest.scala`, `kyo-schema/jvm/src/test/scala/kyo/CodecJvmTest.scala`, `kyo-schema/shared/src/test/scala/kyo/internal/MacroUtilsDriftTest.scala` — rewrite `"Phase N: <name>"` block labels to plain behaviour labels.

### Files to delete
None.

### Public API additions / modifications / removals
None. Comment / scaladoc / test-label changes only.

### Code changes

`Json.scala:228`:
```scala
// BEFORE
                        // Phase 2: Instant / Duration / Frame / Text serialize as JSON strings.
// AFTER
                        // Instant / Duration / Frame / Text serialize as JSON strings.
```

`MacroUtils.scala:243`:
```scala
// BEFORE
            // Phase 2: Instant / Duration / Frame / Text are flat scalars handled by primitiveKindExpr.
// AFTER
            // Instant / Duration / Frame / Text are flat scalars handled by primitiveKindExpr.
```

`MacroUtils.scala:276,278,302`:
```scala
// BEFORE
            // Added Phase 9:
            TypeRepr.of[scala.util.Try].typeSymbol,
            // Added Phase 13:
            TypeRepr.of[scala.collection.immutable.ArraySeq].typeSymbol,
// AFTER
            TypeRepr.of[scala.util.Try].typeSymbol,
            TypeRepr.of[scala.collection.immutable.ArraySeq].typeSymbol,
```
(deletion of the bare provenance comments; same treatment for line 302.)

`SerializationMacro.scala:1129..1210`: same comment-deletion pattern (5 sites).

`SchemaSerializer.scala:324-328`: **kept** — this is a state-machine enum, not campaign provenance.

Test labels (`CodecJvmTest.scala` shown; pattern repeated):
```scala
// BEFORE
"Phase 10: URI round-trip with query and fragment" in {
// AFTER
"URI round-trip with query and fragment" in {
```

### Tests
1. Existing tests pass unchanged.
2. `grep -rE 'Phase [0-9]|Added Phase' kyo-schema/**/src/main kyo-schema/**/src/test` returns ONLY the `SchemaSerializer.scala:324-328` state-machine block.

Total: 2 invariants checked, 0 new tests.

### Verification command
`sbt '+ kyo-schemaJVM/Test/compile' '+ kyo-schemaJS/Test/compile' '+ kyo-schemaNative/Test/compile' 'kyo-schemaJVM/Test/test'`

---

### Phase 2 — Remove cast/Unsafe/@unchecked justification comments and drive-by edits (Items 7 + 13)

**Dependency justification**: Same 5 files (`Schema.scala`, `FocusMacro.scala`, `UnionMacro.scala`, `SerializationMacro.scala`, `SchemaSerializer.scala`) are touched by later phases; doing the comment sweep BEFORE those phases avoids interleaved rebase noise.

### Files to produce
None.

### Files to modify
- `kyo-schema/shared/src/main/scala/kyo/Schema.scala` — remove all `// cast:` end-of-line annotations (17 sites). Remove all `// Unsafe:` block comments (multi-line, several sites). Remove `// @unchecked:` annotations added by `30ec730d4` (audit per-line via `git blame`).
- `kyo-schema/shared/src/main/scala/kyo/internal/FocusMacro.scala` — same sweep (3 + N sites).
- `kyo-schema/shared/src/main/scala/kyo/internal/UnionMacro.scala` — same sweep (6 + N sites).
- `kyo-schema/shared/src/main/scala/kyo/internal/SerializationMacro.scala` — same sweep (5 + N sites).
- `kyo-schema/shared/src/main/scala/kyo/internal/SchemaSerializer.scala` — same sweep (3 + N sites).

### Files to delete
None.

### Public API additions / modifications / removals
None.

### Code changes

Example pattern (Schema.scala leg cast site, exact line numbers vary):
```scala
// BEFORE
                            // cast: macro reached this branch only when sym is a Java enum class
                            writeFn = (v, w) => w.string(v.asInstanceOf[java.lang.Enum[?]].name),
// AFTER
                            writeFn = (v, w) => w.string(v.asInstanceOf[java.lang.Enum[?]].name),
```

Example multi-line `// Unsafe:` block in UnionMacro.scala:225-228:
```scala
// BEFORE
        // Unsafe: no Frame is reachable inside the emitted (value, writer) => Unit
        // lambda body. TypeMismatchException requires `using Frame`; the macro-time
        // Frame.internal is the only option here. Same pattern as
        // SerializationMacro.scala:172.
        val terminal: Expr[Unit] =
// AFTER
        val terminal: Expr[Unit] =
```

Example `// @unchecked:` drive-by (line audited via `git blame` to confirm it was added by `30ec730d4`):
```scala
// BEFORE
                case fld @ (_, _, _, _, _, _): @unchecked  // @unchecked: tuple destructure on known-shape iterator
// AFTER
                case fld @ (_, _, _, _, _, _): @unchecked
```

### Tests
1. Existing tests pass unchanged.
2. `grep -nE '// cast:|// Unsafe:|// @unchecked:' kyo-schema/shared/src/main` returns no hits.

Total: 1 invariant check, 0 new tests.

### Verification command
`sbt '+ kyo-schemaJVM/Test/compile' '+ kyo-schemaJS/Test/compile' '+ kyo-schemaNative/Test/compile' 'kyo-schemaJVM/Test/test'`

---

### Phase 3 — Remove redundant trailing `()` after `discard(...)` (Item 8)

**Dependency justification**: Single isolated edit to `Schema.scala`; runs after Phase 2 to avoid line-number drift in the comment sweep.

### Files to produce
None.

### Files to modify
- `kyo-schema/shared/src/main/scala/kyo/Schema.scala` — delete line 1421 (the standalone `()` after `discard(r.skip())`).

### Files to delete
None.

### Public API additions / modifications / removals
None.

### Code changes

```scala
// BEFORE (Schema.scala:1417-1422)
    given unitSchema: Schema[Unit] = Schema.init[Unit](
        writeFn = (_, w) => w.nil(),
        readFn = r =>
            discard(r.skip())
            ()
    )
// AFTER
    given unitSchema: Schema[Unit] = Schema.init[Unit](
        writeFn = (_, w) => w.nil(),
        readFn = r =>
            discard(r.skip())
    )
```

### Tests
1. `Schema[Unit]` round-trip in `SchemaTest.scala` still passes.

Total: 1 invariant check, 0 new tests.

### Verification command
`sbt '+ kyo-schemaJVM/Test/compile' '+ kyo-schemaJS/Test/compile' '+ kyo-schemaNative/Test/compile' 'kyo-schemaJVM/Test/test'`

---

### Phase 4 — TagMacro narrow TypeBounds peel to FromJavaObject (Items 1 + 17)

**Dependency justification**: Isolated to `kyo-data` module; touches no kyo-schema file. Runs anywhere after Phase 1; placed here to enable Tag-based regression check before later kyo-schema macro phases use Tags. Item 17's direct TagMacro tests append to the existing `TagTest.scala` so the regression coverage ships in the same phase as the narrow.

### Files to produce
None.

### Files to modify
- `kyo-data/shared/src/main/scala/kyo/internal/TagMacro.scala` — line 54-56: gate the peel on `defn.FromJavaObjectSymbol`.
- `kyo-data/shared/src/test/scala/kyo/TagTest.scala` — append 4 Item 17 leaves verifying the narrowed peel. TagTest uses FreeSpec (`"name" in { assert(...) }`); the new leaves slot into an existing top-level scope (e.g. under a new `"TagMacro narrow"` section).

### Files to delete
None.

### Public API additions / modifications / removals
None. Behaviour change: Scala-side `?` wildcards now traverse `loop` branches instead of taking the `hi` shortcut.

### Code changes

```scala
// BEFORE (TagMacro.scala:48-57)
        def visit(t: TypeRepr): Type.Entry.Id =

            // Java raw / wildcard type arguments (e.g. `Comparable[ChronoLocalDateTime[?]]`)
            // surface as bare TypeBounds(lo, hi) and don't match any of `loop`'s cases.
            // Represent a wildcard `? <: hi` as its upper bound: that's the most informative
            // type known statically, and matches the QuoteMatcher's view used in subtype tests.
            val resolved = t match
                case TypeBounds(_, hi) => hi
                case other             => other
            val tpe = resolved.dealiasKeepOpaques.simplified.dealiasKeepOpaques

// AFTER
        def visit(t: TypeRepr): Type.Entry.Id =

            // Java raw / wildcard type arguments (e.g. `Comparable[ChronoLocalDateTime[?]]`)
            // surface as bare TypeBounds(lo, FromJavaObject). Scala 3 marks the upper bound
            // of Java wildcards / raw types with the synthetic `<FromJavaObject>` symbol,
            // so we peel only that shape and let Scala-side `? <: hi` traverse `loop` cases.
            val resolved = t match
                case TypeBounds(_, hi) if hi.typeSymbol == defn.FromJavaObjectSymbol => hi
                case other                                                            => other
            val tpe = resolved.dealiasKeepOpaques.simplified.dealiasKeepOpaques
```

Append to existing `kyo-data/shared/src/test/scala/kyo/TagTest.scala` (FreeSpec style, slotted into a new top-level scope inside the existing `TagTest` class, before `end TagTest`):

```scala
    "TagMacro narrow (FromJavaObject peel)" - {

        "Tag[java.time.LocalDateTime] derives without crashing" in {
            val t = Tag[java.time.LocalDateTime]
            assert(t.show.contains("java.time.LocalDateTime"))
        }

        "Tag[Comparable[ChronoLocalDateTime[?]]] derives (explicit Java wildcard)" in {
            val t = Tag[java.lang.Comparable[java.time.chrono.ChronoLocalDateTime[?]]]
            assert(t.show.contains("Comparable"))
        }

        "Tag[List[?]] derives cleanly after the narrow (Scala-side wildcard)" in {
            val t = Tag[List[?]]
            assert(t.show.contains("List"))
        }

        "negative — synthetic unsupported shape still produces a TagMacro error" in {
            // The exact shape is chosen during execution by inspecting `TagMacro.scala`'s
            // existing `report.errorAndAbort` branches and exercising one of them via
            // `scala.compiletime.testing.typeCheckErrors`. Confirms no regression on the
            // proper error paths after the narrow.
            val errs = scala.compiletime.testing.typeCheckErrors(
                "summon[kyo.Tag[<<TAG_MACRO_ERROR_SHAPE>>]]"
            )
            assert(errs.nonEmpty)
        }
    }
```

### Tests
1. Existing `TagTest` cases still pass unchanged.
2. New `TagTest` leaf — `Tag[java.time.LocalDateTime]` derives without crashing (direct Bug-A regression lock).
3. New `TagTest` leaf — `Tag[java.lang.Comparable[java.time.chrono.ChronoLocalDateTime[?]]]` (explicit Java wildcard repro).
4. New `TagTest` leaf — `Tag[List[?]]` (Scala-side wildcard) still derives cleanly after the narrow.
5. New `TagTest` leaf — negative: a synthetic shape selected from `TagMacro.scala`'s existing `errorAndAbort` branches still produces a TagMacro error (no regression on proper error paths).

Total: 4 new tests, 1 invariant check.

### Verification command
`sbt '+ kyo-dataJVM/Test/compile' '+ kyo-dataJS/Test/compile' '+ kyo-dataNative/Test/compile' 'kyo-dataJVM/Test/test' 'kyo-dataJVM/testOnly *TagMacroTest'`

---

### Phase 5 — Internal `Map[Int, String]` → `Dict[Int, String]` (Item 11)

**Dependency justification**: Three internal sites (`SchemaSerializer.buildFieldNameMap`, `Codec.Reader.withFieldNames`, `ProtobufReader.fieldNames`) and 2 macro emit sites (`FocusMacro`). All independent of later given-refactor phases.

### Files to produce
None.

### Files to modify
- `kyo-schema/shared/src/main/scala/kyo/internal/SchemaSerializer.scala` — `buildFieldNameMap` return type + body.
- `kyo-schema/shared/src/main/scala/kyo/Codec.scala` — `Reader.withFieldNames` parameter type.
- `kyo-schema/shared/src/main/scala/kyo/internal/ProtobufReader.scala` — `fieldNames` private var type and `withFieldNames` override.
- `kyo-schema/shared/src/main/scala/kyo/internal/FocusMacro.scala` — line 398 and line 457: change the emitted type from `Map[Int, String]` to `Dict[Int, String]`.

### Files to delete
None.

### Public API additions / modifications / removals
- **modification**: `kyo.Codec.Reader.withFieldNames(names: Map[Int, String]): this.type` -> `kyo.Codec.Reader.withFieldNames(names: Dict[Int, String]): this.type`.

### Code changes

`SchemaSerializer.scala:26-33`:
```scala
// BEFORE
    def buildFieldNameMap(names: Array[String]): Map[Int, String] =
        val b = Map.newBuilder[Int, String]
        var i = 0
        while i < names.length do
            b += (CodecMacro.fieldId(names(i)) -> names(i))
            i += 1
        b.result()
    end buildFieldNameMap
// AFTER
    def buildFieldNameMap(names: Array[String]): Dict[Int, String] =
        var d = Dict.empty[Int, String]
        var i = 0
        while i < names.length do
            d = d.update(CodecMacro.fieldId(names(i)), names(i))
            i += 1
        d
    end buildFieldNameMap
```

`Codec.scala:140`:
```scala
// BEFORE
        def withFieldNames(names: Map[Int, String]): this.type = this
// AFTER
        def withFieldNames(names: Dict[Int, String]): this.type = this
```

`ProtobufReader.scala:27,36`:
```scala
// BEFORE
    private var fieldNames: Map[Int, String] = Map.empty
    ...
    override def withFieldNames(names: Map[Int, String]): this.type =
// AFTER
    private var fieldNames: Dict[Int, String] = Dict.empty
    ...
    override def withFieldNames(names: Dict[Int, String]): this.type =
```

`FocusMacro.scala:398-399` (and identical block at 457-458):
```scala
// BEFORE
                        val _fieldNameMap: Map[Int, String] =
                            kyo.internal.SchemaSerializer.buildFieldNameMap(_fieldNames)
// AFTER
                        val _fieldNameMap: Dict[Int, String] =
                            kyo.internal.SchemaSerializer.buildFieldNameMap(_fieldNames)
```

### Tests
1. Existing `ProtobufTest` sealed-trait discriminator decode still passes (verifies the `withFieldNames` plumbing).
2. Existing `CodecTest` per-field-id lookups still pass.

Total: 2 invariant checks, 0 new tests.

### Verification command
`sbt '+ kyo-schemaJVM/Test/compile' '+ kyo-schemaJS/Test/compile' '+ kyo-schemaNative/Test/compile' 'kyo-schemaJVM/Test/test'`

---

### Phase 6 — Givens take `using Frame` instead of `Frame.internal` (Item 9)

**Dependency justification**: Establishes the Frame-capture pattern used by Phase 7 (`.transform` delegation inherits Frame from the delegate's captured Frame).

### Files to produce
None.

### Files to modify
- `kyo-schema/shared/src/main/scala/kyo/Schema.scala` — 13 givens (listSchema, vectorSchema, setSchema, chunkSchema, seqSchema, spanSchema, maybeSchema, optionSchema, stringMapSchema, resultSchema, eitherSchema, stringDictSchema, dictSchema): append `using Frame` to the parameter list; replace `(using Frame.internal)` inside `writeFn` / `readFn` bodies with implicit search.

### Files to delete
None.

### Public API additions / modifications / removals
- **modification**: each of the 13 givens gains a `(using Frame)` parameter. Source-compatible (call sites already in `using Frame` context).

### Code changes

`listSchema` (illustrative; pattern repeats for all 13):
```scala
// BEFORE (Schema.scala:1467 approx)
    given listSchema[A](using inner: Schema[A]): Schema[List[A]] =
        Schema.init[List[A]](
            writeFn = (value, writer) =>
                writer.arrayStart(value.size)
                value.foreach(internal.SchemaSerializer.writeTo(inner, _, writer)(using Frame.internal))
                writer.arrayEnd()
            ,
            readFn = reader =>
                discard(reader.arrayStart())
                val builder = List.newBuilder[A]
                @tailrec
                def loop(count: Int): Unit =
                    if reader.hasNextElement() then
                        reader.checkCollectionSize(count)
                        builder += internal.SchemaSerializer.readFrom(inner, reader)(using reader.frame)
                        loop(count + 1)
                loop(1)
                reader.arrayEnd()
                builder.result()
        )

// AFTER
    given listSchema[A](using inner: Schema[A], frame: Frame): Schema[List[A]] =
        Schema.init[List[A]](
            writeFn = (value, writer) =>
                writer.arrayStart(value.size)
                value.foreach(internal.SchemaSerializer.writeTo(inner, _, writer))
                writer.arrayEnd()
            ,
            readFn = reader =>
                discard(reader.arrayStart())
                val builder = List.newBuilder[A]
                @tailrec
                def loop(count: Int): Unit =
                    if reader.hasNextElement() then
                        reader.checkCollectionSize(count)
                        builder += internal.SchemaSerializer.readFrom(inner, reader)(using reader.frame)
                        loop(count + 1)
                loop(1)
                reader.arrayEnd()
                builder.result()
        )
```

The 12 remaining givens (vectorSchema, setSchema, chunkSchema, seqSchema, spanSchema, maybeSchema, optionSchema, stringMapSchema, resultSchema, eitherSchema, stringDictSchema, dictSchema) get the same `using Frame` parameter addition and `Frame.internal` removal.

### Tests
1. Existing round-trip tests for each of the 13 collection / wrapper schemas pass unchanged.
2. New `SchemaTest` case verifying `Frame` propagation: a custom Schema whose `readFrom` throws using the captured frame produces a `DecodeException` whose Frame is the test's call-site Frame (not `internal`).

Total: 1 new test, 1 invariant check.

### Verification command
`sbt '+ kyo-schemaJVM/Test/compile' '+ kyo-schemaJS/Test/compile' '+ kyo-schemaNative/Test/compile' 'kyo-schemaJVM/Test/test'`

---

### Phase 7 — `.transform` delegation for arraySchema / arraySeqSchema / queueSchema / sortedSetSchema (Item 10)

**Dependency justification**: Requires Phase 6's Frame-captured collection givens; the delegated `.transform` chains rely on `seqSchema` / `setSchema` carrying their own Frame.

### Files to produce
None.

### Files to modify
- `kyo-schema/shared/src/main/scala/kyo/Schema.scala` — `arraySchema`, `arraySeqSchema`, `queueSchema`, `sortedSetSchema` rewritten as one-line delegations.

### Files to delete
None.

### Public API additions / modifications / removals
- **modification**: parameter lists unchanged (still `using inner: Schema[A], ct: ClassTag[A]` / `using inner: Schema[A]` / `using inner: Schema[A], ord: Ordering[A]`). No `using Frame` needed — inherited via the delegate.

### Code changes

`arraySchema` (Schema.scala:1630 approx):
```scala
// BEFORE
    given arraySchema[A](using inner: Schema[A], ct: scala.reflect.ClassTag[A]): Schema[Array[A]] =
        Schema.init[Array[A]](
            writeFn = (value, writer) =>
                writer.arrayStart(value.length)
                value.foreach(internal.SchemaSerializer.writeTo(inner, _, writer)(using Frame.internal))
                writer.arrayEnd()
            ,
            readFn = reader =>
                discard(reader.arrayStart())
                val builder = Array.newBuilder[A]
                @tailrec
                def loop(count: Int): Unit =
                    if reader.hasNextElement() then
                        reader.checkCollectionSize(count)
                        builder += internal.SchemaSerializer.readFrom(inner, reader)(using reader.frame)
                        loop(count + 1)
                loop(1)
                reader.arrayEnd()
                builder.result()
        )

// AFTER
    given arraySchema[A](using inner: Schema[A], ct: scala.reflect.ClassTag[A]): Schema[Array[A]] =
        seqSchema[A].transform(_.toArray)(_.toSeq)
```

`arraySeqSchema`:
```scala
// AFTER
    given arraySeqSchema[A](using inner: Schema[A], ct: scala.reflect.ClassTag[A]): Schema[scala.collection.immutable.ArraySeq[A]] =
        seqSchema[A].transform(scala.collection.immutable.ArraySeq.from)(_.toSeq)
```

`queueSchema`:
```scala
// AFTER
    given queueSchema[A](using inner: Schema[A]): Schema[scala.collection.immutable.Queue[A]] =
        seqSchema[A].transform(scala.collection.immutable.Queue.from)(_.toSeq)
```

`sortedSetSchema`:
```scala
// AFTER
    given sortedSetSchema[A](using inner: Schema[A], ord: Ordering[A]): Schema[scala.collection.immutable.SortedSet[A]] =
        setSchema[A].transform(scala.collection.immutable.SortedSet.from)(_.toSet)
```

### Tests
1. Existing round-trip tests for Array, ArraySeq, Queue, SortedSet still pass (wire format unchanged).
2. New `SchemaTest` case: `Schema[Array[Int]]` and `Schema[Seq[Int]]` produce byte-identical encoded JSON.

Total: 1 new test, 4 invariant checks.

### Verification command
`sbt '+ kyo-schemaJVM/Test/compile' '+ kyo-schemaJS/Test/compile' '+ kyo-schemaNative/Test/compile' 'kyo-schemaJVM/Test/test'`

---

### Phase 8 — Delete KeyCodec, route Map[K, V] via Dict (Item 5)

**Dependency justification**: Uses the `.transform` delegation pattern established in Phase 7.

### Files to produce
None.

### Files to modify
- `kyo-schema/shared/src/main/scala/kyo/Schema.scala` — delete `mapSchemaWithKeyCodec`, `mapPairsSchema`, `sortedMapSchemaWithKeyCodec`, `sortedMapPairsSchema`; add single `mapSchema` and `sortedMapSchema` delegations.

### Files to delete
- `kyo-schema/shared/src/main/scala/kyo/KeyCodec.scala`
- `kyo-schema/shared/src/test/scala/kyo/KeyCodecTest.scala`

### Public API additions / modifications / removals
- **removal**: `kyo.KeyCodec` trait, companion, and all derived givens (`stringKeyCodec`, `intKeyCodec`, `longKeyCodec`, `uuidKeyCodec`).
- **removal**: `Schema.mapSchemaWithKeyCodec`, `Schema.mapPairsSchema`, `Schema.sortedMapSchemaWithKeyCodec`, `Schema.sortedMapPairsSchema`.
- **addition**: `Schema.mapSchema[K, V](using kSchema: Schema[K], vSchema: Schema[V], frame: Frame): Schema[Map[K, V]]`.
- **addition**: `Schema.sortedMapSchema[K, V](using kSchema: Schema[K], vSchema: Schema[V], ord: Ordering[K], frame: Frame): Schema[SortedMap[K, V]]`.
- **breaking wire change**: `Map[Int, V]`, `Map[Long, V]`, `Map[UUID, V]` previously encoded as JSON objects `{"1": v}`; now encode as array-of-pairs `[[1, v]]`. `Map[String, V]` (object encoding) unchanged via `stringMapSchema`.

### Code changes

New `mapSchema`:
```scala
    /** Schema for `Map[K, V]` (non-String keys): array-of-pairs encoding via Dict delegation. */
    given mapSchema[K, V](using kSchema: Schema[K], vSchema: Schema[V], frame: Frame): Schema[Map[K, V]] =
        dictSchema[K, V].transform(_.toMap)(Dict.from)
```

New `sortedMapSchema`:
```scala
    /** Schema for immutable.SortedMap[K, V]: delegates to `dictSchema` with Ordering[K] for reconstruction. */
    given sortedMapSchema[K, V](using kSchema: Schema[K], vSchema: Schema[V], ord: Ordering[K], frame: Frame): Schema[scala.collection.immutable.SortedMap[K, V]] =
        dictSchema[K, V].transform(d => scala.collection.immutable.SortedMap.from(d.toMap))(m => Dict.from(m.toMap))
```

Deletions: `mapSchemaWithKeyCodec` (Schema.scala:1808-1839), `mapPairsSchema` (1846-1884 approx), `sortedMapSchemaWithKeyCodec` (1723-1732), `sortedMapPairsSchema` (1741-1776).

### Tests
1. Migrate any KeyCodecTest scenarios to `SchemaTest` as `Map[Int, V]` array-of-pairs round-trip.
2. New `SchemaTest` cases: `Map[Int, String]` round-trip yields `[[1, "a"]]` shape; `Map[UUID, V]` round-trip; `SortedMap[Int, V]` round-trip preserves key order.
3. New `SchemaTest` case: `Map[String, V]` still uses object encoding `{"k": v}`.

Total: 4 new tests.

### Verification command
`sbt '+ kyo-schemaJVM/Test/compile' '+ kyo-schemaJS/Test/compile' '+ kyo-schemaNative/Test/compile' 'kyo-schemaJVM/Test/test'`

---

### Phase 9 — PlatformSchemas per-type cross-platform verification (Item 2)

**Dependency justification**: Isolated to `PlatformSchemas.scala` and its cross-platform shadow. No code depends on its location.

### Files to produce
- (conditional) `kyo-schema/shared/src/main/scala/kyo/JavaPlatformSchemas.scala` — destination for any given that proves cross-platform during trial-move.

### Files to modify
- `kyo-schema/jvm/src/main/scala/kyo/internal/PlatformSchemas.scala` — trial-move each given to shared and observe cross-aggregate compile; revert each given that fails.

### Files to delete
None.

### Public API additions / modifications / removals
Depends on trial-move outcome. Expected (per build.sbt classpath analysis): all 7 givens stay in `PlatformSchemas` (JVM-only); no API change.

### Code changes

Trial-move procedure (executed per type, the example shows URI):

```bash
# Step 1: move uriSchema to a new shared file
cat > kyo-schema/shared/src/main/scala/kyo/JavaPlatformSchemas.scala <<'EOF'
package kyo.internal

import kyo.*

object JavaPlatformSchemas:
    given uriSchema: Schema[java.net.URI] =
        Schema.stringSchema.transform[java.net.URI](new java.net.URI(_))(_.toString)
EOF

# Step 2: remove uriSchema from PlatformSchemas.scala (jvm)

# Step 3: cross-compile
sbt '+ kyo-schemaJVM/Test/compile' '+ kyo-schemaJS/Test/compile' '+ kyo-schemaNative/Test/compile'

# Step 4: if JS or Native fails (java.net.URI not in classpath), revert.
```

The trial-move ends with a documented per-type table at the top of `PlatformSchemas.scala` recording the outcome:

```scala
/** Platform support per type:
  *
  * | Type                  | JVM | JS  | Native |
  * |-----------------------|-----|-----|--------|
  * | java.net.URI          | yes | no  | no     |
  * | java.net.URL          | yes | no  | no     |
  * | java.net.InetAddress  | yes | no  | no     |
  * | java.nio.file.Path    | yes | no  | no     |
  * | java.io.File          | yes | no  | no     |
  * | java.util.Locale      | yes | no  | no     |
  * | java.util.Currency    | yes | no  | no     |
  *
  * Verified by trial-move + cross-aggregate compile (see followup execution plan, Phase 9).
  */
```

### Tests
1. `CodecJvmTest` URI/URL/InetAddress/Path/File/Locale/Currency round-trip tests still pass.
2. (conditional) If any given moves to `shared/`, add the same round-trip test to `kyo-schema/shared/src/test/scala/kyo/SchemaTest.scala`.

Total: 7 invariant checks; 0-7 new shared tests depending on outcome.

### Verification command
`sbt '+ kyo-schemaJVM/Test/compile' '+ kyo-schemaJS/Test/compile' '+ kyo-schemaNative/Test/compile' 'kyo-schemaJVM/Test/test'`

---

### Phase 10 — UnionMacro deduplicates SerializationMacro infrastructure (Item 14)

**Dependency justification**: `SerializationMacro.VariantInfo`, `sealedWriteBody`, `sealedReadBody` are untouched by Phases 1-9; reusing them is safe.

### Files to produce
None.

### Files to modify
- `kyo-schema/shared/src/main/scala/kyo/internal/UnionMacro.scala` — replace `LegInfo` with `VariantInfo[T]`; replace `writeBody` / `readBody` / `buildLegBranch` with calls to `SerializationMacro.sealedWriteBody` / `sealedReadBody`; keep `collectOrTypeLegs`, `collectOrTypeLegsRaw`, `degenerate`, `legName`, single-leg short-circuit.
- `kyo-schema/shared/src/main/scala/kyo/internal/SerializationMacro.scala` — if `VariantInfo` / `sealedWriteBody` / `sealedReadBody` are `private[internal]`, no change. If `private`, widen to `private[internal]`.

### Files to delete
None.

### Public API additions / modifications / removals
None (internal-only refactor).

### Code changes

`UnionMacro.derive` body (illustrative target shape):
```scala
    def derive[T: Type](using Quotes): Expr[Schema[T]] =
        import quotes.reflect.*
        val tpe     = TypeRepr.of[T]
        val rawLegs = collectOrTypeLegsRaw(tpe)
        val legs    = collectOrTypeLegs(tpe)
        degenerate(rawLegs, legs).foreach(report.errorAndAbort)

        if legs.size == 1 then
            legs.head.asType match
                case '[l] =>
                    Expr.summon[Schema[l]] match
                        case Some(s) => '{ $s.asInstanceOf[Schema[T]] }
                        case None    => report.errorAndAbort(s"No given Schema[${legs.head.show}] for union leg.")
        else
            // Build VariantInfo[T] per leg, reusing SerializationMacro's dispatch.
            val variants: List[SerializationMacro.VariantInfo[T]] = legs.map(buildVariantInfo[T])
            val variantIds: List[(String, Int)] = variants.map(v => v.name -> CodecMacro.fieldId(v.name))
            val fieldBytesExpr: Expr[Array[Array[Byte]]] =
                '{ Array[Array[Byte]](${ Varargs(variants.map(v => Expr(v.name.getBytes(java.nio.charset.StandardCharsets.UTF_8)))) }*) }
            '{
                val _fieldBytes = $fieldBytesExpr
                Schema.init[T](
                    writeFn = (value, writer) =>
                        ${ SerializationMacro.sealedWriteBody[T](
                            typeName     = Type.show[T],
                            variantIds   = variantIds,
                            variants     = variants,
                            fieldBytes   = '_fieldBytes,
                            selfSchema   = '{ null.asInstanceOf[Schema[T]] },  // unused by union legs
                            value        = 'value,
                            writer       = 'writer
                        ) },
                    readFn = (reader) =>
                        ${ unionUntaggedReadBody[T]('reader, '_fieldBytes, variants) }
                )
            }
        end if
    end derive

    private def buildVariantInfo[T: Type](using Quotes)(leg: quotes.reflect.TypeRepr): SerializationMacro.VariantInfo[T] =
        import quotes.reflect.*
        leg.asType match
            case '[l] =>
                val schemaExpr = Expr.summon[Schema[l]].getOrElse(
                    report.errorAndAbort(s"No given Schema[${leg.show}] for union leg."))
                SerializationMacro.VariantInfo[T](
                    name           = legName(leg),
                    checkExpr      = (v: Expr[T]) => '{ $v.isInstanceOf[l] },
                    castExpr       = (v: Expr[T]) => '{ $v.asInstanceOf[l] },
                    schemaResolver = _ => '{ $schemaExpr.asInstanceOf[Schema[Any]] }
                )

    /** Wraps `sealedReadBody`'s tagged-object read with an untagged try-each fallback for bare wire values. */
    private def unionUntaggedReadBody[T: Type](using Quotes)(
        reader: Expr[Reader],
        fieldBytes: Expr[Array[Array[Byte]]],
        variants: List[SerializationMacro.VariantInfo[T]]
    ): Expr[T] = ...  // ~40 lines: try sealedReadBody first; on TypeMismatchException, try each leg's schema directly
```

Expected file size: 346 -> ~100-110 lines.

### Tests
1. Existing `UnionTest` (renamed in Phase 14 to `UnionMacroTest`) full pass.
2. New union test: `Schema[Int | String]` produces wire identical to the pre-refactor version (regression lock).
3. New union test: union of two sealed traits dispatches correctly through `sealedReadBody`.

Total: 2 new tests, 1 invariant check.

### Verification command
`sbt '+ kyo-schemaJVM/Test/compile' '+ kyo-schemaJS/Test/compile' '+ kyo-schemaNative/Test/compile' 'kyo-schemaJVM/Test/test'`

---

### Phase 11 — Eliminate hardcoded type lists and the platform-symbol hook (Items 12 + 3)

**Dependency justification**: Largest architectural change. Removing `isSerializableType` also kills the sole consumer of `MacroUtils.platformPrimitiveSymbols` and `PlatformSymbols`, so both deletions happen in this phase. Removes the lists the drift tests guard (enabling the next phase's drift-test deletion).

### Files to produce
None.

### Files to modify
- `kyo-schema/shared/src/main/scala/kyo/Structure.scala` — add `final class PrimitiveKindFor[T] private[kyo] (val kind: Structure.PrimitiveKind)` and its companion with 20 `given` declarations.
- `kyo-schema/shared/src/main/scala/kyo/internal/StructureMacro.scala` — `isPrimitive` rewritten to use `Expr.summon[PrimitiveKindFor[t]].isDefined`; `primitiveKindExpr` rewritten to summon `PrimitiveKindFor[t]` and read `.kind`.
- `kyo-schema/shared/src/main/scala/kyo/internal/ExpandMacro.scala` — `isPrimitive` rewritten to use `Expr.summon[PrimitiveKindFor[t]].isDefined`.
- `kyo-schema/shared/src/main/scala/kyo/internal/MacroUtils.scala` — delete `basePrimitiveSymbols`, `extendedPrimitiveSymbols`, `collectionSymbols`, `optionalSymbols`, `mapSymbols`, AND `platformPrimitiveSymbols` (the latter exists solely to feed `isSerializableType`'s primitive set; with that gate gone the hook has no consumer). Add `containerSymbolsFromSchema(using Quotes): (Set[Symbol], Set[Symbol], Set[Symbol])` that enumerates Schema companion givens.
- `kyo-schema/shared/src/main/scala/kyo/internal/SerializationMacro.scala` — delete `isSerializableType` (the entire ~150-line method, lines 1108-1260). The union with `MacroUtils.platformPrimitiveSymbols` inside the deleted method goes with it (verify exact location during execution). Delete its callers in `FocusMacro` (lines 357, 360, 527): the `checkSerializability` gate becomes vestigial; the per-field `Expr.summon[Schema[T]]` inside the field-write/read body produces a clean compile error when no Schema exists.
- `kyo-schema/shared/src/main/scala/kyo/internal/FocusMacro.scala` — remove `checkSerializability` parameter from `generateCaseClassSchema` / `generateSealedTraitSchema` (callers pass `false` anyway in the `derived` path; `metaApply` path's `true` value is moot once the gate is gone).
- `kyo-schema/jvm/src/main/scala/kyo/internal/PlatformSchemas.scala` — scaladoc no longer mentions `PlatformSymbols.primitiveSymbols`.

### Files to delete
- `kyo-schema/jvm/src/main/scala/kyo/internal/PlatformSymbols.scala`
- `kyo-schema/js/src/main/scala/kyo/internal/PlatformSymbols.scala`
- `kyo-schema/native/src/main/scala/kyo/internal/PlatformSymbols.scala`

(Drift tests delete in the next phase.)

### Public API additions / modifications / removals
- **addition**: `kyo.PrimitiveKindFor[T]` typeclass with 20 givens.
- **removal**: `MacroUtils.basePrimitiveSymbols`, `MacroUtils.extendedPrimitiveSymbols`, `MacroUtils.collectionSymbols`, `MacroUtils.optionalSymbols`, `MacroUtils.mapSymbols`, `MacroUtils.platformPrimitiveSymbols` (`private[internal]`, no external surface).
- **removal**: `SerializationMacro.isSerializableType` (`private[internal]`).
- **removal**: `kyo.internal.PlatformSymbols` object (3-file cross-build shadow; `private[internal]` — no external surface).

### Code changes

`Structure.scala` addition (after the existing `enum PrimitiveKind`):
```scala
final class PrimitiveKindFor[T] private[kyo] (val kind: Structure.PrimitiveKind)
object PrimitiveKindFor:
    given PrimitiveKindFor[Int]              = new PrimitiveKindFor(Structure.PrimitiveKind.Int)
    given PrimitiveKindFor[Long]             = new PrimitiveKindFor(Structure.PrimitiveKind.Long)
    given PrimitiveKindFor[Short]            = new PrimitiveKindFor(Structure.PrimitiveKind.Short)
    given PrimitiveKindFor[Byte]             = new PrimitiveKindFor(Structure.PrimitiveKind.Byte)
    given PrimitiveKindFor[Char]             = new PrimitiveKindFor(Structure.PrimitiveKind.Char)
    given PrimitiveKindFor[Float]            = new PrimitiveKindFor(Structure.PrimitiveKind.Float)
    given PrimitiveKindFor[Double]           = new PrimitiveKindFor(Structure.PrimitiveKind.Double)
    given PrimitiveKindFor[Boolean]          = new PrimitiveKindFor(Structure.PrimitiveKind.Boolean)
    given PrimitiveKindFor[String]           = new PrimitiveKindFor(Structure.PrimitiveKind.String)
    given PrimitiveKindFor[Unit]             = new PrimitiveKindFor(Structure.PrimitiveKind.Unit)
    given PrimitiveKindFor[BigInt]                  = new PrimitiveKindFor(Structure.PrimitiveKind.BigInt)
    given PrimitiveKindFor[java.math.BigInteger]    = new PrimitiveKindFor(Structure.PrimitiveKind.BigInt)
    given PrimitiveKindFor[BigDecimal]              = new PrimitiveKindFor(Structure.PrimitiveKind.BigDecimal)
    given PrimitiveKindFor[java.math.BigDecimal]    = new PrimitiveKindFor(Structure.PrimitiveKind.BigDecimal)
    given PrimitiveKindFor[java.time.Instant]       = new PrimitiveKindFor(Structure.PrimitiveKind.Instant)
    given PrimitiveKindFor[kyo.Instant]             = new PrimitiveKindFor(Structure.PrimitiveKind.Instant)
    given PrimitiveKindFor[java.time.Duration]      = new PrimitiveKindFor(Structure.PrimitiveKind.Duration)
    given PrimitiveKindFor[kyo.Duration]            = new PrimitiveKindFor(Structure.PrimitiveKind.Duration)
    given PrimitiveKindFor[kyo.Frame]               = new PrimitiveKindFor(Structure.PrimitiveKind.Frame)
    given PrimitiveKindFor[kyo.Text]                = new PrimitiveKindFor(Structure.PrimitiveKind.Text)
end PrimitiveKindFor
```

`StructureMacro.scala` `primitiveKindExpr` rewrite (lines 202-233):
```scala
// BEFORE
    private def primitiveKindExpr(using Quotes)(tpe: quotes.reflect.TypeRepr): Expr[Structure.PrimitiveKind] =
        import quotes.reflect.*
        given CanEqual[Symbol, Symbol] = CanEqual.derived
        val sym = tpe.dealias.typeSymbol
        if sym == TypeRepr.of[Int].typeSymbol then '{ Structure.PrimitiveKind.Int }
        else if sym == TypeRepr.of[Long].typeSymbol then '{ Structure.PrimitiveKind.Long }
        // ... 13 more branches ...
        else
            report.errorAndAbort(s"No PrimitiveKind mapping for primitive type: ${tpe.show}. ...")

// AFTER
    private def primitiveKindExpr(using Quotes)(tpe: quotes.reflect.TypeRepr): Expr[Structure.PrimitiveKind] =
        import quotes.reflect.*
        tpe.asType match
            case '[t] =>
                Expr.summon[kyo.PrimitiveKindFor[t]] match
                    case Some(p) => '{ $p.kind }
                    case None    => report.errorAndAbort(s"No PrimitiveKindFor[${tpe.show}] in scope.")
```

`StructureMacro.isPrimitive` (line 192):
```scala
// BEFORE
    private def isPrimitive(using Quotes)(tpe: quotes.reflect.TypeRepr): Boolean =
        import quotes.reflect.*
        MacroUtils.extendedPrimitiveSymbols.contains(tpe.dealias.typeSymbol)

// AFTER
    private def isPrimitive(using Quotes)(tpe: quotes.reflect.TypeRepr): Boolean =
        import quotes.reflect.*
        tpe.asType match
            case '[t] => Expr.summon[kyo.PrimitiveKindFor[t]].isDefined
```

`ExpandMacro.isPrimitive` (line 110): same rewrite pattern.

`MacroUtils.scala` deletions: lines 216-305 (5 set definitions). Add:
```scala
    /** Container / optional / map tycon symbols, sourced from the actual `Schema` companion givens. */
    private[internal] def containerSymbolsFromSchema(using Quotes): (Set[quotes.reflect.Symbol], Set[quotes.reflect.Symbol], Set[quotes.reflect.Symbol]) =
        import quotes.reflect.*
        val optionalTycons: Set[Symbol] = Set(
            TypeRepr.of[Option].typeSymbol,
            TypeRepr.of[kyo.Maybe].typeSymbol
        )
        val mapTycons: Set[Symbol] = Set(
            TypeRepr.of[Map].typeSymbol,
            TypeRepr.of[kyo.Dict].typeSymbol,
            TypeRepr.of[scala.collection.immutable.SortedMap].typeSymbol
        )

        def peel(ret: TypeRepr): Option[TypeRepr] =
            ret.dealias match
                case AppliedType(_, List(target)) => Some(target)
                case Refinement(parent, _, _)     => peel(parent)
                case _                            => None

        val schemaSym    = TypeRepr.of[kyo.Schema.type].typeSymbol
        val givenMembers = schemaSym.declaredMethods.filter(_.flags.is(Flags.Given))

        var collections = Set.empty[Symbol]
        var optionals   = Set.empty[Symbol]
        var maps        = Set.empty[Symbol]
        givenMembers.foreach { m =>
            m.tree match
                case d: DefDef =>
                    peel(d.returnTpt.tpe).foreach {
                        case AppliedType(tycon, args) =>
                            val sym = tycon.typeSymbol
                            args.size match
                                case 1 =>
                                    if optionalTycons.contains(sym) then optionals = optionals + sym
                                    else collections = collections + sym
                                case 2 if mapTycons.contains(sym) =>
                                    maps = maps + sym
                                case _ => ()
                            end match
                        case _ => ()
                    }
                case _ => ()
        }
        (collections, optionals, maps)
    end containerSymbolsFromSchema
```

`SerializationMacro.scala` — delete `isSerializableType` (lines 1108-1260). Audit callers: `FocusMacro.scala:357, 360, 527`. In each caller, replace the boolean-gated branch with the always-emit-serialization branch (the `cannotSerialize` value becomes always `false`). Then remove `checkSerializability` parameter from `generateCaseClassSchema` and `generateSealedTraitSchema` (5 callers update).

`StructureMacro.scala` consumers of removed sets: `isOptionalType` (line 235), `isCollectionType` (line 239), `isMapType` (line 243) get rewritten to use the new `containerSymbolsFromSchema` triple cached at the start of `deriveType`.

`MacroUtils.scala` `platformPrimitiveSymbols` deletion (lines 253-263):
```scala
// BEFORE
    /** Platform-specific primitive symbols. Empty on shared; each platform module
      * (kyo-schema/jvm, kyo-schema/js, kyo-schema/native) ships a sibling
      * `kyo.internal.PlatformSymbols` object containing the per-platform set ...
      */
    private[internal] def platformPrimitiveSymbols(using Quotes): Set[quotes.reflect.Symbol] =
        PlatformSymbols.primitiveSymbols
    end platformPrimitiveSymbols
// AFTER
(deleted)
```

`PlatformSymbols.scala` deletions (all 3 files): the jvm/, js/, native/ shadow set is removed. With `isSerializableType` and `platformPrimitiveSymbols` both gone, no consumer remains.

`PlatformSchemas.scala` scaladoc trim:
```scala
// BEFORE
  * The corresponding `typeSymbol`s are
  * registered in `PlatformSymbols.primitiveSymbols` so that case classes referencing these
  * types pass the `isSerializableType` gate during `metaApply` macro expansion on JVM.
// AFTER
(deleted; the gate no longer exists)
```

### Tests
1. Existing `SchemaTest` + `StructureTest` round-trips pass (regression).
2. New `SchemaTest` case: `Schema.derived[CaseClass(missingFieldType)]` where `missingFieldType` has NO Schema produces a clear compile-time error (the post-gate behaviour). Requires a compile-fail harness — use `scala.compiletime.testing.typeCheckErrors`.
3. New `StructureTest` case: `Structure.of[CaseClassWithInstant]` continues to compile (regression).

Total: 2 new tests, 1 invariant check.

### Verification command
`sbt '+ kyo-schemaJVM/Test/compile' '+ kyo-schemaJS/Test/compile' '+ kyo-schemaNative/Test/compile' 'kyo-schemaJVM/Test/test'`

---

### Phase 12 — Delete drift-guard infrastructure (Item 16)

**Dependency justification**: The hardcoded lists guarded by these tests are gone after Phase 11.

### Files to produce
None.

### Files to modify
None.

### Files to delete
- `kyo-schema/shared/src/test/scala/kyo/internal/MacroUtilsDriftMacro.scala`
- `kyo-schema/shared/src/test/scala/kyo/internal/MacroUtilsDriftTest.scala`
- `kyo-schema/shared/src/test/scala/kyo/internal/SerializationMacroDriftMacro.scala`
- `kyo-schema/shared/src/test/scala/kyo/internal/SerializationMacroDriftTest.scala`

### Public API additions / modifications / removals
None (test-only deletions).

### Code changes
Deletions only. Per-file scan: each `in {…}` block checked for behavioural content (vs structural drift). None found; all 4 files removed.

### Tests
1. Total kyo-schema test count drops by however many `in {…}` blocks lived in the 4 drift files (expected 6-10 blocks). Regression: all OTHER tests still pass.

Total: 1 invariant check, 0 new tests.

### Verification command
`sbt '+ kyo-schemaJVM/Test/compile' '+ kyo-schemaJS/Test/compile' '+ kyo-schemaNative/Test/compile' 'kyo-schemaJVM/Test/test'`

---

### Phase 13 — Intersection type support `A & B` (Item 6)

**Dependency justification**: New code; depends on `ExpandMacro` and `FocusMacro.derivedImpl` being clean from earlier phases (no Phase X comments, no cast comments).

### Files to produce
- `kyo-schema/shared/src/main/scala/kyo/internal/IntersectionMacro.scala` — entry-point `derive[T]` for AndType `T`; helpers for field collection, collision detection, and Scala 3 anonymous-class synthesis.

### Files to modify
- `kyo-schema/shared/src/main/scala/kyo/internal/FocusMacro.scala` — replace the unconditional AndType rejection (lines 711-723) with a dispatch to `IntersectionMacroProxy.derive[T]` (proxy pattern matches `UnionMacroProxy`).
- `kyo-schema/shared/src/main/scala/kyo/internal/ExpandMacro.scala` — add an `AndType` branch parallel to the existing `OrType` branch (line 33).

### Files to delete
None.

### Public API additions / modifications / removals
- **addition**: `Schema.derived[A & B]` no longer errors; produces a `Schema[A & B]` matching the 4 sub-case dispatch in [analysis.md, Item 6].

### Code changes

New file `kyo-schema/shared/src/main/scala/kyo/internal/IntersectionMacro.scala` (~80-100 lines). The macro DELEGATES per-field encode/decode to `SerializationMacro.caseClassWriteBody` and `SerializationMacro.caseClassReadBodyResolved`; only intersection-specific bits are new:

```scala
package kyo.internal

import kyo.*
import scala.quoted.*

/** Macro for deriving `Schema[T]` where `T` is an intersection type `A & B`.
  *
  * Four sub-cases (see analysis.md, Item 6). Reuses `SerializationMacro.caseClassWriteBody` /
  * `caseClassReadBodyResolved` for per-field encode/decode; intersection-specific bits are
  * field-collection (dedupe + collision detection), anonymous-class synthesis, and refinement peel.
  */
object IntersectionMacro:

    def derive[T: Type](using Quotes): Expr[Schema[T]] =
        import quotes.reflect.*
        val tpe = TypeRepr.of[T].dealias
        peel(tpe) match
            case Some(parent) => deriveRecursing[T](parent)
            case None =>
                val halves = flattenAnd(tpe)
                classifyHalves(halves) match
                    case Classification.ConcreteAndMarker(dataHalf) => deriveViaHalf[T](dataHalf)
                    case Classification.BothHaveFields(fs) =>
                        // collectFields: List[(name, TypeRepr)] from the two halves, deduped by name.
                        // Collision (same name, different type) -> report.errorAndAbort.
                        val fields = collectFields(fs)
                        // Delegate the per-field write/read loops to SerializationMacro.
                        // selfSchema is unused for intersection but required by the helper signature;
                        // pass a stub.
                        '{
                            Schema.init[T](
                                writeFn = (value, writer) =>
                                    ${ SerializationMacro.caseClassWriteBody[T](
                                          fields, 'value, 'writer) },
                                readFn = (reader) =>
                                    ${
                                        // caseClassReadBodyResolved yields a field-name -> value
                                        // map; we feed those into the anonymous-class constructor
                                        // synthesized below.
                                        val decodedExpr =
                                            SerializationMacro.caseClassReadBodyResolved[T](fields, 'reader)
                                        synthesizeAnonymous[T](fields, decodedExpr)
                                    }
                            )
                        }
                    case Classification.Unconstructible =>
                        report.errorAndAbort(
                            s"Schema.derived[${tpe.show}]: intersection of two unrelated data-carrying types has no constructor.")
                end match
    end derive

    /** Port of the deleted MacroUtilsDriftMacro.peel — single-arg refinement passthrough. */
    private def peel(using Quotes)(tpe: quotes.reflect.TypeRepr): Option[quotes.reflect.TypeRepr] =
        import quotes.reflect.*
        tpe.dealias match
            case Refinement(parent, _, _) => Some(parent)
            case _                         => None

    private def flattenAnd(using Quotes)(tpe: quotes.reflect.TypeRepr): List[quotes.reflect.TypeRepr] =
        import quotes.reflect.*
        def go(t: TypeRepr): List[TypeRepr] = t.dealias match
            case AndType(a, b) => go(a) ++ go(b)
            case other         => List(other)
        go(tpe)

    /** Field collection across both halves; dedupe by name; abort on same-name-different-type. */
    private def collectFields[T: Type](using Quotes)(halves: List[quotes.reflect.TypeRepr]): List[(String, quotes.reflect.TypeRepr)] = ???

    /** Scala 3 anonymous-class synthesis. Only new code with no case-class analogue.
      *   Symbol.newClass(...) -> ClassDef -> Block(classDef :: Nil, New(...))
      */
    private def synthesizeAnonymous[T: Type](using Quotes)(
        fields: List[(String, quotes.reflect.TypeRepr)],
        decoded: Expr[Map[String, Any]]
    ): Expr[T] = ???

    private enum Classification:
        case ConcreteAndMarker(dataHalf: quotes.reflect.TypeRepr)
        case BothHaveFields(halves: List[quotes.reflect.TypeRepr])
        case Unconstructible

    private def classifyHalves(using Quotes)(halves: List[quotes.reflect.TypeRepr]): Classification = ???
    private def deriveViaHalf[T: Type](using Quotes)(dataHalf: quotes.reflect.TypeRepr): Expr[Schema[T]] = ???
    private def deriveRecursing[T: Type](using Quotes)(parent: quotes.reflect.TypeRepr): Expr[Schema[T]] = ???

end IntersectionMacro

/** Trampoline proxy to defer loading IntersectionMacro$ until Schema.scala's own compilation has settled. */
object IntersectionMacroProxy:
    inline def derive[T]: Schema[T] = ${ IntersectionMacro.derive[T] }
```

**Reuse contract.** `SerializationMacro.caseClassWriteBody` and `caseClassReadBodyResolved` must be visible to `IntersectionMacro`. If they are currently `private[SerializationMacro]`, widen to `private[internal]`. If their signature takes a `List[(String, TypeRepr)]` (or equivalent field descriptor), no change is needed — intersection fields plug in directly. If the signature differs (e.g. takes a `Symbol` for the case-class constructor), introduce a shared `private[internal] def writeFieldsBody` / `readFieldsBody` extracted from the existing case-class path during this phase, with both case-class and intersection sites calling it.

**Refinement peel port.** `MacroUtilsDriftMacro.peel` is deleted in Phase 12. The peel logic (~5 lines) is inlined into `IntersectionMacro.peel` above; no shared helper needed beyond this macro.

`FocusMacro.scala:711-723` rewrite:
```scala
// BEFORE
        val isIntersection = tpe match
            case AndType(_, _) => true
            case _             => false

        val result =
            if isIntersection then
                report.errorAndAbort(
                    s"Schema.derived[${tpe.show}]: intersection types have no general constructor. ...")
            else if isUnion then
// AFTER
        val isIntersection = tpe match
            case AndType(_, _) => true
            case _             => false

        val result =
            if isIntersection then
                IntersectionMacroProxy.derive[A].asInstanceOf[Expr[Any]]
            else if isUnion then
```

`ExpandMacro.scala:33` add branch:
```scala
                case AndType(_, _) =>
                    val halves    = flattenAndType(dealiased)
                    val tildeType = TypeRepr.of[Record.~]
                    val tagged = halves.map { half =>
                        val sym      = half.typeSymbol
                        val halfName = if sym.exists then sym.name else half.show
                        val nameType = ConstantType(StringConstant(halfName))
                        val expanded = expandType(half)
                        tildeType.appliedTo(List(nameType, expanded))
                    }
                    tagged.reduce(AndType(_, _))

    private def flattenAndType(using Quotes)(tpe: quotes.reflect.TypeRepr): List[quotes.reflect.TypeRepr] =
        import quotes.reflect.*
        def go(t: TypeRepr): List[TypeRepr] = t.dealias match
            case AndType(a, b) => go(a) ++ go(b)
            case other         => List(other)
        go(tpe)
    end flattenAndType
```

### Tests
1. New `IntersectionMacroTest.scala` in `kyo-schema/shared/src/test/scala/kyo/internal/`: positive — `case class C(x: Int) extends Marker` round-trip via `Schema[C & Marker]`.
2. New: positive — `trait Named { val name: String }` + `trait Aged { val age: Int }` -> `Schema[Named & Aged]` round-trips via anonymous synthesis.
3. New: positive — refinement `Schema[Foo { type Out = Int }]` peels to `Schema[Foo]`.
4. New: negative — `trait Named { val x: String }` + `trait Other { val x: Int }` -> field-collision compile error.
5. New: negative — `case class A(x: Int)` + `case class B(y: Int)` -> unconstructible-residual compile error.
6. Existing `SchemaTest` "intersection rejected" test (if present in the existing AndType-rejection error coverage) updated or removed (it asserted the old rejection behaviour).

Total: 5 new tests, 1 invariant audit.

### Verification command
`sbt '+ kyo-schemaJVM/Test/compile' '+ kyo-schemaJS/Test/compile' '+ kyo-schemaNative/Test/compile' 'kyo-schemaJVM/Test/test' 'kyo-schemaJS/Test/test' 'kyo-schemaNative/Test/test'`

---

### Phase 14 — Test renaming / folding (Item 15)

**Dependency justification**: Runs last because earlier phases delete (`KeyCodecTest` in Phase 8, 4 drift tests in Phase 12) and create (`IntersectionMacroTest` in Phase 13) test files. Renaming after those churns minimises rebase noise.

### Files to produce
- `kyo-schema/jvm/src/test/scala/kyo/SchemaJvmTest.scala` — destination for `JavaEnumTest` contents.

### Files to modify
- `kyo-schema/shared/src/test/scala/kyo/SchemaTest.scala` — fold in NestedTransformTest contents (114 lines, ~3-5 test blocks) and CompositionMatrixTest contents (479 lines, ~15-25 test blocks). Each folded block stays as `in {…}` under a new section header `"nested transforms"` / `"composition matrix"`.

### Files to delete
- `kyo-schema/shared/src/test/scala/kyo/NestedTransformTest.scala`
- `kyo-schema/shared/src/test/scala/kyo/CompositionMatrixTest.scala`
- `kyo-schema/shared/src/test/scala/kyo/UnionTest.scala` (after rename to `internal/UnionMacroTest.scala`)
- `kyo-schema/jvm/src/test/scala/kyo/JavaEnumTest.scala` (after fold into `SchemaJvmTest.scala`)

### Files renamed
- `kyo-schema/shared/src/test/scala/kyo/UnionTest.scala` -> `kyo-schema/shared/src/test/scala/kyo/internal/UnionMacroTest.scala` (package change to `kyo.internal`; class rename to `UnionMacroTest`).

### Files that stay
- `kyo-schema/jvm/src/test/scala/kyo/CodecJvmTest.scala` (CodecJvm is a valid platform-suffixed prefix of Codec.scala).

### Public API additions / modifications / removals
None (test-only restructure).

### Code changes

Fold pattern (illustrative, NestedTransformTest -> SchemaTest):
```scala
// In SchemaTest.scala, append before end-of-class:
    "nested transforms" - {
        // <every "name" in {…} block from NestedTransformTest.scala, inserted verbatim>
    }
```

`SchemaJvmTest.scala` creation:
```scala
package kyo

import java.nio.file.StandardOpenOption
import java.time.DayOfWeek

class SchemaJvmTest extends Test:

    given CanEqual[Any, Any] = CanEqual.derived

    given Schema[DayOfWeek]          = Schema.derived[DayOfWeek]
    given Schema[StandardOpenOption] = Schema.derived[StandardOpenOption]

    "round-trip DayOfWeek" in {
        // ... contents from JavaEnumTest ...
    }
    // ... remaining JavaEnumTest blocks ...
end SchemaJvmTest
```

`UnionMacroTest` rename: change file location, change `package kyo` -> `package kyo.internal`, rename class `UnionTest` -> `UnionMacroTest`, add `import kyo.*` if needed.

### Tests
1. Total test count BEFORE Phase 14: existing kyo-schema test count minus (Phase 8 deleted KeyCodecTest blocks) minus (Phase 12 deleted drift-test blocks) plus (Phase 13 added IntersectionMacroTest blocks).
2. Total test count AFTER Phase 14: unchanged from above (folds preserve count).
3. Naming-rule invariant check: for every `*Test.scala` under `kyo-schema/`, stripping `Test.scala` yields a source-file-name (modulo `Jvm`/`Js`/`Native` suffix). Automated via:
   ```bash
   for f in $(find kyo-schema -name '*Test.scala' -not -path '*/internal/*'); do
       base=$(basename "$f" Test.scala)
       stripped=$(echo "$base" | sed -E 's/(Jvm|Js|Native)$//')
       find kyo-schema -path "*/main/*" -name "${stripped}.scala" | grep -q . || echo "ORPHAN: $f"
   done
   ```

Total: 1 invariant check, 0 new tests (folds preserve content).

### Verification command
`sbt '+ kyo-schemaJVM/Test/compile' '+ kyo-schemaJS/Test/compile' '+ kyo-schemaNative/Test/compile' 'kyo-schemaJVM/Test/test'`

---

## Aggregate

- 14 phases.
- New tests total: 4 (Ph4) + 1 (Ph6) + 1 (Ph7) + 4 (Ph8) + (0-7 Ph9) + 2 (Ph10) + 2 (Ph11) + 5 (Ph13) = 19-26 new tests (Phase 9 conditional).
- Invariant checks (existing-test regressions explicitly verified): every phase carries at least one.
- Cross-platform verification: every phase ends with `+ kyo-schemaJVM/Test/compile + kyo-schemaJS/Test/compile + kyo-schemaNative/Test/compile`. Phase 4 substitutes `kyo-data`. Phase 13 additionally runs the test suites on JS and Native.
