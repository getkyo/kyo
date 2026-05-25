# Followup Analysis — Code-Review Resolution Plan

This plan resolves 17 review items raised against the kyo-schema bug-fix campaign that landed in commit `30ec730d4`. Each item is resolved to a concrete decision (no opens), then composed into a dependency-ordered phase DAG.

Working dir: `/Users/fwbrasil/workspace/kyo/.claude/worktrees/squishy-wibbling-parrot` (verified). Branch: `worktree-squishy-wibbling-parrot`.

---

## Per-item analysis

### Item 1 — TagMacro narrow to Java-only

**Root cause.** `TagMacro.visit` (`kyo-data/shared/src/main/scala/kyo/internal/TagMacro.scala:54-56`) added an unconditional `TypeBounds(_, hi) => hi` peel to fix a Java-wildcard crash. That peel also fires for Scala-side `?` wildcards, which previously fell through to existing `loop` branches and now silently take the upper-bound shortcut, losing wildcard identity in the entry graph.

**Approach.** Restrict the peel to `hi.typeSymbol == defn.FromJavaObjectSymbol`. Scala 3 represents Java raw-type and wildcard upper bounds with the synthetic `FromJavaObjectSymbol` (the `<FromJavaObject>` term that behaves like `java.lang.Object` to the Java type-system but is structurally distinct from Scala `AnyRef`). That is the wildcard shape Bug-A's `Comparable[ChronoLocalDateTime[?]]` repro hit. Scala-side `?` carries `Any` (or a user-written upper bound) and is unaffected.

**Reflection API.** Use `defn.FromJavaObjectSymbol` (the `Symbol`). The comparison is by-symbol so the `Symbol` accessor is the right surface.

**Rationale.** Smallest behavioural diff that retains the Bug-A fix; preserves Scala wildcard identity in `Tag`.

---

### Item 2 — PlatformSchemas per-type cross-platform verification

**Root cause.** All seven givens (`URI`, `URL`, `InetAddress`, `Path`, `File`, `Locale`, `Currency`) were placed in `kyo-schema/jvm/` on the assumption that they are JVM-only. The assumption was never verified per-type against the actual JS/Native classpath.

**Approach (chosen).** Verify by trial-move on a working copy:
1. For each of the 7 types, move the corresponding `given` from `kyo-schema/jvm/.../PlatformSchemas.scala` to a new file `kyo-schema/shared/src/main/scala/kyo/JavaPlatformSchemas.scala`.
2. Run `+ kyo-schema/Test/compile` (cross-aggregate alias) — fails on JS/Native if the symbol is unresolved.
3. Whichever types pass on all three platforms move to `shared/`; the rest stay JVM-only.

**Build classpath check (informs the expectation, not the decision).** The kyo-schema sbt project is `crossProject(JSPlatform, JVMPlatform, NativePlatform)` with `crossType(CrossType.Full)` (`build.sbt:451-460`) and adds NO platform polyfill libraries (`scala-java-time`, `scala-java-locales`, `scala-java-net`, etc.). Therefore:
- `URI`, `URL`, `InetAddress` (java.net.*) — not in scala.js stdlib, no polyfill wired, JVM-only expected.
- `Path`, `File` (java.nio.file, java.io) — not in scala.js stdlib, no polyfill wired, JVM-only expected.
- `Locale`, `Currency` (java.util) — not in scala.js stdlib, no polyfill wired, JVM-only expected.

Expectation: all 7 stay JVM-only. The trial-move step nonetheless runs and the decision is data-driven. If any type unexpectedly compiles on JS+Native, that given moves to `shared/`.

**Rationale.** The user asks for verification, not assertion. The classpath reasoning gives the prior; the per-type trial-move is the test that decides.

---

### Item 3 — Eliminate PlatformSymbols + MacroUtils.platformPrimitiveSymbols

**Root cause.** The platform shadow `kyo.internal.PlatformSymbols` (3-file cross-build set) exists to widen `isSerializableType`'s primitive set on JVM. `MacroUtils.platformPrimitiveSymbols` is the single hook that reads it. Both exist solely to feed `isSerializableType`; with Item 12 deleting that gate, they have no remaining consumer and become dead code in the same phase.

**Approach.** Folded into Phase 11 (the same phase that deletes `isSerializableType`):
1. Delete `MacroUtils.platformPrimitiveSymbols` from `MacroUtils.scala`.
2. Delete the three-file `PlatformSymbols` shadow (`jvm/`, `js/`, `native/` files).
3. `PlatformSchemas` (`jvm/`-only) stays — it is the user-importable surface (`import kyo.internal.PlatformSchemas.given`). Its scaladoc reference to `PlatformSymbols` is rewritten to drop the now-stale mention.

**Rationale.** The deletions are the trivial completion of removing the gate; doing them in a separate later phase would leave dead code on the trunk between phases for no benefit.

---

### Item 4 — Strip Phase X references from production code

**Root cause.** 18 phase-tagged comments survive in `kyo-schema/**/main/` (grep `"Phase \d|Added Phase"`). These are campaign-internal language that shouldn't ship in scaladoc / source comments.

**Approach.** Sweep all `// Phase N`, `// Added Phase N`, `Phase N consolidation`, `Phase N — `, etc. Replace each with either (a) deletion (when the comment was pure provenance) or (b) re-wording that describes the WHY without the phase number (when the comment carries genuine intent). The `SchemaSerializer.scala:324-328` block (`// Phase 0: initial …`) describes a state machine, NOT the campaign — those are kept (they are a phase enum for the discriminator-flatten state machine). The phase enumerates the 18 sites individually and updates each by line number.

**Test-side phases.** Tests in `CodecTest.scala`, `ProtobufTest.scala`, `StructureTest.scala`, `SchemaTest.scala`, `MacroUtilsDriftTest.scala`, `CodecJvmTest.scala` use `"Phase N: <description>"` as `in {}` block names. Rename each to a behaviour-only label (e.g. `"Phase 10: URI round-trip"` -> `"URI round-trip with query and fragment"`).

**Rationale.** Campaign log belongs in commit messages and the original `improvement-plan/` docs, not in shipped source or test names.

---

### Item 5 — Delete KeyCodec, route Map[K, V] via Dict

**Root cause.** `KeyCodec[K]` was introduced to give `Map[K, V]` object-keyed JSON encoding (`{"1": "a"}` for `Map[Int, String]`). This duplicates the encoding-decision surface: `Dict[K, V]` already has a fully-functional schema at `Schema.scala:2272` that encodes as array-of-pairs `[[k, v], …]` for non-String keys.

**Dict wire format (verified).** `dictSchema[K, V]` writes `arrayStart(size)` -> per entry `arrayStart(2)` + write(k) + write(v) + `arrayEnd` -> `arrayEnd`. Identical to `mapPairsSchema`'s format. `Map[K, V]` and `Dict[K, V]` already share a wire shape on the non-String path.

**Approach.**
1. Delete `kyo-schema/shared/src/main/scala/kyo/KeyCodec.scala`.
2. Delete `kyo-schema/shared/src/test/scala/kyo/KeyCodecTest.scala`.
3. Delete `Schema.mapSchemaWithKeyCodec`, `Schema.mapPairsSchema`, `Schema.sortedMapSchemaWithKeyCodec`, `Schema.sortedMapPairsSchema`.
4. Replace with one `mapSchema[K, V](using kSchema: Schema[K], vSchema: Schema[V], frame: Frame): Schema[Map[K, V]] = dictSchema[K, V].transform(_.toMap)(Dict.from)`.
5. Replace with one `sortedMapSchema[K, V](using kSchema: Schema[K], vSchema: Schema[V], ord: Ordering[K], frame: Frame): Schema[SortedMap[K, V]] = dictSchema[K, V].transform(d => SortedMap.from(d.toMap))(m => Dict.from(m.toMap))`.
6. `Schema.stringMapSchema` stays (object-keyed encoding for the common `Map[String, V]` case).
7. Remove `KeyCodec` references from scaladoc.

**Breaking surface.** Wire format for `Map[Int, V]`, `Map[Long, V]`, `Map[UUID, V]` changes from object-keyed (`{"1": v, "2": v}`) to array-of-pairs (`[[1, v], [2, v]]`). Acceptable per `feedback_no_backcompat`: "migrations replace APIs outright, no overloads/shims."

**Rationale.** Single encoding rule, one schema, no `NotGiven` precedence trick, no per-key-type codec registry.

---

### Item 6 — Proper intersection type support `A & B`

**Root cause.** `FocusMacro.derivedImpl:711-723` rejects `AndType` outright with a compile error directing users to "derive a concrete class or use .transform". This is overly restrictive; many intersection shapes have a viable decoder.

**Approach.** Replace the unconditional error with a four-case dispatch matching the user-listed sub-cases. A new file `kyo-schema/shared/src/main/scala/kyo/internal/IntersectionMacro.scala` is small (~80-100 lines) because it REUSES `SerializationMacro.caseClassWriteBody` / `caseClassReadBodyResolved` for the per-field encode / decode work. Only the intersection-specific bits are new code.

**What is reused (no duplication).**
- `SerializationMacro.caseClassWriteBody` handles the per-field write loop (field name, schema lookup, writer call) for the encoded value's fields.
- `SerializationMacro.caseClassReadBodyResolved` handles the per-field read loop, building the field-name -> value map that the synthesized anonymous-class constructor consumes.

**What is genuinely new (intersection-only).**
- **Field collection**: collect distinct field names from both halves of the intersection; deduplicate by name; error on type-collision (same name, different types).
- **Anonymous-class synthesis**: `Symbol.newClass` + `Block(classDef, New(...))` for constructing the `A & B` value from decoded fields. This is the only piece that has no analogue in case-class derivation.
- **Refinement passthrough**: if one half is a refinement, recurse into the parent. The `peel` helper currently lives in `MacroUtilsDriftMacro.peel` but that file is deleted by Phase 12 (drift-test deletion); port the peel logic into the intersection macro (or a small shared internal helper) before that deletion lands.
- **Unconstructible residual**: keep the compile error for the genuinely unrecoverable case (two unrelated data carriers).

**Sub-case dispatch.**

| Shape | Decoder strategy |
|---|---|
| `Concrete & PureTrait` (one half is data-carrying case class or sealed; other half is marker interface with no abstract members and no case-class shape) | Encode/decode via the data half's Schema; cast result to `T` (which is-a both halves). |
| `Refinement(parent, _, _)` | Recurse via the ported `peel` helper — refinements drop into parent. |
| `TraitWithFields & TraitWithFields` (both halves contribute fields; no class constructor exists) | Collect fields from both halves (dedupe + collision check), reuse `caseClassWriteBody` for write and `caseClassReadBodyResolved` for read, then anonymous-class-synthesize the constructor: `Symbol.newClass` + `Block(classDef :: Nil, Typed(Apply(Select(New(Ident(classSym)), classSym.primaryConstructor), argTerms), TypeTree.of[T]))`. |
| `CaseClass & CaseClass` (two unrelated data carriers — unconstructible residual) | `report.errorAndAbort` with the existing message, scoped to this case. |

**Extension points beyond FocusMacro.**
- `ExpandMacro.expandType`: add an `AndType` branch parallel to the existing `OrType` branch (line 33). Flatten the intersection, expand each half, recombine via `AndType.apply(_, _)`.

**Design rationale.** This design avoids the UnionMacro duplication problem that Phase 10 had to fix. By reusing `caseClassWriteBody` / `caseClassReadBodyResolved` from the start, the new macro stays small (~80-100 lines) and stays in lockstep with case-class derivation: future field-encoding changes propagate to intersection support automatically.

**Tests.** Cover each sub-case with positive round-trip, the field-collision negative, and the unconstructible-residual negative.

---

### Item 7 — Remove `// cast:` justification comments

**Root cause.** A compliance commit added `// cast:` explanatory comments next to every surviving `asInstanceOf`. The casts themselves are necessary (CIO erasure boundaries, leg-typed Schema array slots, reflective enum invoke); the explanatory comments are review-noise that doesn't ship.

**Approach.** Grep `// cast:` across 5 files. Counts: `Schema.scala 17, FocusMacro.scala 3, SerializationMacro.scala 5, UnionMacro.scala 6, SchemaSerializer.scala 3`. Sweep with targeted `Edit` per occurrence (line-anchored). Also delete the multi-line `// Unsafe: …` block comments that explain `Frame.internal` usage — most of those become unnecessary with Items 9 / 10.

**Rationale.** The `// Unsafe:` and `// cast:` styling departs from the rest of kyo. The casts speak for themselves; the comments don't.

---

### Item 8 — Remove redundant trailing `()` after `discard(...)`

**Root cause.** One site (`Schema.scala:1420-1421`):
```
readFn = r =>
    discard(r.skip())
    ()
```
`discard` returns `Unit`, so the trailing `()` is noise.

**Approach.** Delete line 1421.

---

### Item 9 — Givens take `using Frame` instead of `Frame.internal`

**Root cause.** Collection / wrapper givens (listSchema, vectorSchema, setSchema, chunkSchema, seqSchema, spanSchema, maybeSchema, optionSchema, stringMapSchema, resultSchema, eitherSchema, stringDictSchema, dictSchema) use `Frame.internal` inside their `writeFn` / `readFn` lambdas because the lambdas' bodies have no `Frame` in scope. Adding `using Frame` to the given itself captures the call-site `Frame` into the lambda closure.

**Approach.** Per given:
1. Append `using Frame` to the parameter list (alongside the existing `using inner: Schema[A]`, etc.).
2. Replace every `(using Frame.internal)` in the lambda bodies with `(using summon[Frame])` (or rely on implicit search to find the captured Frame).
3. Remove the `// Unsafe:` block comments that explained why `Frame.internal` was used (overlap with Item 7's sweep; this phase handles the ones tied to these givens).

**Sites.** listSchema, vectorSchema, setSchema, chunkSchema, seqSchema, spanSchema, maybeSchema, optionSchema, stringMapSchema, resultSchema, eitherSchema, stringDictSchema, dictSchema. The 5 schemas refactored to `.transform` in Item 10 (arraySchema, arraySeqSchema, queueSchema, sortedSetSchema, sortedMapSchema) inherit Frame from the delegate's `transform` and don't need their own `using Frame`.

**Rationale.** Aligns with `feedback_no_unsafe`: never `Frame.internal` when a real `Frame` is reachable. The given is the natural point to inject one.

---

### Item 10 — arraySchema / arraySeqSchema / queueSchema / sortedSetSchema use `.transform` delegation

**Root cause.** Each of these schemas hand-rolls the same write/read loop (arrayStart, foreach write, arrayEnd; then arrayStart, while-hasNextElement read, arrayEnd). The existing `seqSchema` / `setSchema` already does this work; the new schemas should compose.

**Approach.**

| Schema | Delegate via |
|---|---|
| `arraySchema[A](using inner: Schema[A], ct: ClassTag[A])` | `seqSchema[A].transform(_.toArray)(_.toSeq)`. |
| `arraySeqSchema[A](using inner: Schema[A], ct: ClassTag[A])` | `seqSchema[A].transform(scala.collection.immutable.ArraySeq.from)(_.toSeq)`. |
| `queueSchema[A](using inner: Schema[A])` | `seqSchema[A].transform(scala.collection.immutable.Queue.from)(_.toSeq)`. |
| `sortedSetSchema[A](using inner: Schema[A], ord: Ordering[A])` | `setSchema[A].transform(scala.collection.immutable.SortedSet.from)(_.toSet)`. |
| `sortedMapSchema[K, V]` (new, from Item 5) | `dictSchema.transform` — already specified above. |

**Side effects.** Removes `discard(reader.hasNextElement())` from these sites. Eliminates `Frame.internal` from the 4 hand-rolled bodies (the underlying delegate already does its own Frame capture from Item 9).

---

### Item 11 — Replace internal `Map[Int, String]` with `Dict[Int, String]`

**Root cause.** Three internal sites use `Map[Int, String]` for the field-id -> field-name reverse lookup:
- `SchemaSerializer.buildFieldNameMap` (return type, line 26).
- `Codec.Reader.withFieldNames` (parameter type, `Codec.scala:140`).
- `ProtobufReader.fieldNames` (private var type, line 27).

User-facing `Schema[Map[K, V]]` stays Map (it is the user's container choice).

**Approach.**
1. Change return / param / field types from `Map[Int, String]` -> `Dict[Int, String]`.
2. `SchemaSerializer.buildFieldNameMap` builds via `Dict.empty[Int, String]` + `.update(k, v)` fold (allocation-symmetric with `Map.newBuilder`; avoids an intermediate Map).
3. `ProtobufReader.fieldNames.get(id)` continues to work — `Dict` has `get`.
4. Update the macro-emitted call site (`FocusMacro.scala:399`, `FocusMacro.scala:458`): `val _fieldNameMap: Dict[Int, String] = …`.

---

### Item 12 — Eliminate hardcoded type lists in macros

**Root cause.** Three locations duplicate type-set bookkeeping:
- `MacroUtils.{basePrimitiveSymbols, extendedPrimitiveSymbols, collectionSymbols, optionalSymbols, mapSymbols, platformPrimitiveSymbols}` — 6 sets.
- `SerializationMacro.isSerializableType` builds its own `primitiveSymbols` (35 entries) + `tupleSymbols` (Tuple1..22).
- `StructureMacro.primitiveKindExpr` hardcodes the type -> PrimitiveKind mapping (15 entries).

These drift independently. Bug C in the prior campaign was exactly this drift; the drift-guard tests added were a workaround, not a fix.

**Approach (chosen).** Three-strategy combo:

1. **Drop `isSerializableType` entirely.** The gate exists to give a soft fallback (emit a no-serialization Schema instead of failing) when a field's type lacks a Schema. Remove the gate. When a field has no Schema, derivation now fails at the natural macro point (the per-field `Expr.summon[Schema[T]]` already inside `caseClassWriteBody` / `caseClassReadBodyResolved` returns None -> `report.errorAndAbort` with a clear message). The no-serialization fallback obscured the real problem.

2. **Source-of-truth enumeration for collection/optional/map sets.** Replace `MacroUtils.collectionSymbols`, `MacroUtils.optionalSymbols`, `MacroUtils.mapSymbols` with a single helper `MacroUtils.containerSymbolsFromSchema(using Quotes): (Set[Symbol], Set[Symbol], Set[Symbol])` that, at macro expansion, reads `kyo.Schema.type`.declaredMethods, peels `Schema[F[_]]` / `Schema[F[_, _]]` from each given's return type (using the same `peel` from `MacroUtilsDriftMacro`), and classifies by arity:
   - 1-arg + tycon-name in {Option, Maybe} -> optional.
   - 1-arg otherwise -> collection.
   - 2-arg + tycon-name in {Map, Dict, SortedMap} -> map.

   The arity-based classification needs a tiebreaker because both `Option` and `List` are 1-arg. Use a small static set INSIDE `MacroUtils` for the optional tycons (just `Option` and `Maybe`); everything else 1-arg is collection. That static set is the ONLY remaining list and is the natural source of truth (optionality is a 2-element domain, not an enumeration that drifts).

3. **`StructureMacro.primitiveKindExpr` — `summonPrimitiveKind[T]`.** Replace the hardcoded `if/else` chain with a per-type typeclass lookup: add a new `final class PrimitiveKindFor[T] private[kyo] (val kind: Structure.PrimitiveKind)` with a `given PrimitiveKindFor[T]` for every primitive type. The macro summons `PrimitiveKindFor[T]` (a regular non-inline given, summon-safe) and reads its `.kind` field. For `T` with no `PrimitiveKindFor`, fall through to non-primitive handling instead of erroring.

**Concrete API additions for strategy 3.**

```scala
// kyo/Structure.scala
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
    given PrimitiveKindFor[BigInt]           = new PrimitiveKindFor(Structure.PrimitiveKind.BigInt)
    given PrimitiveKindFor[java.math.BigInteger]    = new PrimitiveKindFor(Structure.PrimitiveKind.BigInt)
    given PrimitiveKindFor[BigDecimal]       = new PrimitiveKindFor(Structure.PrimitiveKind.BigDecimal)
    given PrimitiveKindFor[java.math.BigDecimal]    = new PrimitiveKindFor(Structure.PrimitiveKind.BigDecimal)
    given PrimitiveKindFor[java.time.Instant]       = new PrimitiveKindFor(Structure.PrimitiveKind.Instant)
    given PrimitiveKindFor[kyo.Instant]             = new PrimitiveKindFor(Structure.PrimitiveKind.Instant)
    given PrimitiveKindFor[java.time.Duration]      = new PrimitiveKindFor(Structure.PrimitiveKind.Duration)
    given PrimitiveKindFor[kyo.Duration]            = new PrimitiveKindFor(Structure.PrimitiveKind.Duration)
    given PrimitiveKindFor[kyo.Frame]               = new PrimitiveKindFor(Structure.PrimitiveKind.Frame)
    given PrimitiveKindFor[kyo.Text]                = new PrimitiveKindFor(Structure.PrimitiveKind.Text)
```

Macro:
```scala
Expr.summon[kyo.PrimitiveKindFor[t]] match
    case Some(p) => '{ $p.kind }
    case None    => fall through to non-primitive branches
```

`PrimitiveKindFor` is a regular (non-inline) given, so `Expr.summon` is safe.

**ExpandMacro / StructureMacro consumer impact.** `ExpandMacro.isPrimitive` (line 110) calls `MacroUtils.basePrimitiveSymbols.contains(...)`. Replace with `Expr.summon[PrimitiveKindFor[t]].isDefined`. `StructureMacro.isPrimitive` (line 192) — same replacement. `MacroUtils.{basePrimitiveSymbols, extendedPrimitiveSymbols}` then have no callers and are deleted.

**`Schema.derived` inline problem.** `Schema.derived[T]` is `inline given`; `Expr.summon[Schema[T]]` would trigger derivation. None of the three strategies summon `Schema[T]`: strategy 1 (delete the gate) bypasses summon altogether; strategy 2 reads `declaredMethods` directly; strategy 3 summons a different non-inline typeclass.

**Drift-guard fallout.** Once the hardcoded lists are gone, `MacroUtilsDriftMacro` / `MacroUtilsDriftTest` / `SerializationMacroDriftMacro` / `SerializationMacroDriftTest` have nothing to guard. They are deleted in Item 16.

---

### Item 13 — Don't drive-by-edit unchanged code

**Root cause.** The compliance commit added `// @unchecked:` annotations and similar styling tweaks to lines that were not part of the substantive change.

**Approach.** Folded into Phase 2 alongside Item 7 (same files swept). For each `// @unchecked:` annotation in `Schema.scala`, `FocusMacro.scala`, `UnionMacro.scala`, `SerializationMacro.scala`, `SchemaSerializer.scala`: confirm via `git log -p -- <file> | grep '+.*@unchecked:'` that the line was added by `30ec730d4`; if so, remove. Pre-existing `@unchecked` annotations (without the trailing colon-comment styling) stay.

---

### Item 14 — UnionMacro deduplicates SerializationMacro infrastructure

**Root cause.** `UnionMacro` (346 lines) implements its own write dispatch (`writeBody` + `buildLegBranch`) and read dispatch (`readBody`), even though `SerializationMacro.sealedWriteBody` (lines 155-193) and `SerializationMacro.sealedReadBody` (lines 1064-1097) implement the same dispatch shape for sealed traits. Both consume a `List[VariantInfo[A]]`.

**Approach.**
1. Reuse `VariantInfo[A]` for union legs: `name` = leg's wire name; `checkExpr` = `value.isInstanceOf[L]`; `castExpr` = `value.asInstanceOf[L]`; `schemaResolver` = constant resolver returning the leg's summoned `Schema[L]` erased to `Schema[Any]`.
2. Build union legs as `VariantInfo[T]` and feed to `sealedWriteBody` / `sealedReadBody`. The wire shape (`{"L": val}` single-field wrapper) is identical between sealed and union dispatch.
3. Retain in `UnionMacro`: `collectOrTypeLegs` (flatten + Nothing-strip + dedup), `collectOrTypeLegsRaw`, `degenerate` (degenerate-shape detection), `legName`, single-leg short-circuit. Delete `writeBody`, `readBody`, `buildLegBranch`, `LegInfo` (replaced by `VariantInfo`).

**Schema lookup.** `SchemaResolver[A]` resolves a per-variant `Schema`. For a union leg's summoned `Schema[L]`, the resolver ignores the `selfSchema` input and returns the captured leg-schema Expr.

**Untagged-read.** The current `UnionMacro.readBody` adds a "try each leg" untagged-read path (used when input lacks the wrapper). `sealedReadBody` doesn't have this. Keep a `unionUntaggedReadBody` helper in `UnionMacro` that wraps `sealedReadBody`'s tagged path and adds the untagged-fallback try/catch. Net effect: still ~80 lines smaller than current 346.

**Expected drop.** UnionMacro 346 -> ~100 lines (leg-extraction, degenerate-detection, `sealedWriteBody` / `sealedReadBody` wiring, untagged-read wrapper).

---

### Item 15 — Test file renaming / folding

**Root cause.** Five test files don't share a name-prefix with any source file: `NestedTransformTest.scala`, `CompositionMatrixTest.scala`, `JavaEnumTest.scala`, `UnionTest.scala`, `CodecJvmTest.scala`.

**Approach (fold destinations).**

| Test file | Destination |
|---|---|
| `NestedTransformTest.scala` (114 lines) | Fold into `SchemaTest.scala` (transforms are a Schema feature). |
| `CompositionMatrixTest.scala` (479 lines) | Fold into `SchemaTest.scala` (composition is Schema-level). |
| `JavaEnumTest.scala` (jvm, 38 lines) | Fold into a new `kyo-schema/jvm/src/test/scala/kyo/SchemaJvmTest.scala` — `SchemaJvm` is the platform-suffixed prefix of `Schema.scala`. |
| `UnionTest.scala` (185 lines) | Rename to `kyo-schema/shared/src/test/scala/kyo/internal/UnionMacroTest.scala` (matches `internal/UnionMacro.scala`). |
| `CodecJvmTest.scala` (jvm, 88 lines) | Stays — `CodecJvm` is the platform-suffixed prefix of `Codec.scala`. |
| `KeyCodecTest.scala` | Deleted with Item 5 (Phase 8). |

**Naming rule restated.** `<TestFile>Test.scala` must satisfy: stripping `Test.scala` yields a string that is the name of an existing source file (modulo a `Jvm` / `Js` / `Native` platform suffix). `CodecJvm` satisfies this because `Codec.scala` is the source.

---

### Item 16 — Delete drift-guard infrastructure

**Root cause.** With Item 12 deleting the hardcoded lists, the drift-guard tests have no source-of-truth-vs-list comparison to make.

**Approach.** Delete:
- `kyo-schema/shared/src/test/scala/kyo/internal/MacroUtilsDriftMacro.scala`
- `kyo-schema/shared/src/test/scala/kyo/internal/MacroUtilsDriftTest.scala`
- `kyo-schema/shared/src/test/scala/kyo/internal/SerializationMacroDriftMacro.scala`
- `kyo-schema/shared/src/test/scala/kyo/internal/SerializationMacroDriftTest.scala`

**Salvage.** Scan each file for tests that verify behaviour (not drift). The drift tests are pure structural ("every parameterised given's tycon is in MacroUtils' set"). With MacroUtils' sets gone, there is nothing to verify. None of the existing assertions translate to a post-Item-12 invariant; no salvage required. The phase confirms this by reviewing each `in {…}` block; any block testing round-trip behaviour rather than structural invariants is folded into `SchemaTest` or `CodecTest` before deletion.

---

### Item 17 — Direct tests for the TagMacro narrow (Item 1)

**Root cause.** Item 1 narrows the `TypeBounds` peel in `TagMacro.visit` to the `FromJavaObjectSymbol` shape. Today that change is only indirectly covered by `CodecTest`'s `LocalDateTime` round-trip (which exercises kyo-schema, not kyo-data). The change needs direct kyo-data-side tests pinning behaviour, otherwise a future macro tweak could silently re-broaden the peel.

**Approach.** Append 4 direct leaves to the existing `kyo-data/shared/src/test/scala/kyo/TagTest.scala` (verified to exist; FreeSpec-style — uses `"name" in { assert(...) }`). Tests are folded into Phase 4 (the TagMacro narrow phase) so the fix and its direct coverage land together.

**Tests.**
1. `Tag[java.time.LocalDateTime]` derives without crashing (the original Bug-A regression repro).
2. `Tag[java.lang.Comparable[java.time.chrono.ChronoLocalDateTime[?]]]` derives (the explicit Java-wildcard repro that surfaced the crash).
3. `Tag[List[?]]` (Scala-side wildcard) still derives cleanly after the narrow.
4. Negative: a synthetic `Tag[T]` site where `T` resolves to a structurally unsupported shape continues to produce a TagMacro error (no regression on the proper error path; the precise shape is selected during Phase 4 by reading the existing `TagMacro.scala` source for an `errorAndAbort` branch and exercising the corresponding type).

**Naming-rule check.** `TagTest.scala` already matches the `Tag.scala` source-file prefix per the Item 15 rule (`Tag` source -> `TagTest` test); no new file needed.

**Rationale.** Direct kyo-data tests guard the narrow at the layer it lives in; the existing `CodecTest` LocalDateTime test stays as integration coverage on the kyo-schema side.

---

## Dependency DAG

Each edge `A -> B` means phase B touches code or test surface affected by phase A.

```
Phase 1  (Item 4)  Strip Phase X references
   |
   v
Phase 2  (Items 7+13)  Remove cast/Unsafe/@unchecked comments + drive-bys
   |    [same files swept; ordering avoids interleaved edits in Schema.scala, FocusMacro.scala, UnionMacro.scala, SerializationMacro.scala, SchemaSerializer.scala]
   v
Phase 3  (Item 8)  Remove redundant trailing ()
   |    [Schema.scala line 1421 only]
   v
Phase 4  (Items 1 + 17)  TagMacro narrow + direct kyo-data tests
   |    [kyo-data module, independent of all kyo-schema phases; Item 17 lives here so the regression coverage ships with the narrow]
   v
Phase 5  (Item 11) Map[Int, String] -> Dict[Int, String]
   |    [SchemaSerializer.scala buildFieldNameMap signature; Codec.Reader.withFieldNames; ProtobufReader.fieldNames; FocusMacro emit site]
   v
Phase 6  (Item 9)  Givens take using Frame
   |    [Schema.scala collection givens; uses Frame propagation pattern]
   v
Phase 7  (Item 10) .transform delegation for arraySchema / arraySeqSchema / queueSchema / sortedSetSchema
   |    [depends on Phase 6's Frame-capture pattern]
   v
Phase 8  (Item 5)  Delete KeyCodec, route Map[K, V] via Dict
   |    [uses the .transform pattern from Phase 7]
   v
Phase 9  (Item 2)  PlatformSchemas per-type verification
   |    [isolated to PlatformSchemas.scala]
   v
Phase 10 (Item 14) UnionMacro dedup via VariantInfo
   |    [SerializationMacro.VariantInfo + sealedWriteBody + sealedReadBody are untouched by earlier phases]
   v
Phase 11 (Items 12 + 3) Eliminate hardcoded type lists and the platform-symbol hook
   |    [removes isSerializableType, MacroUtils sets, primitiveKindExpr; adds PrimitiveKindFor; also deletes MacroUtils.platformPrimitiveSymbols and the 3-file PlatformSymbols shadow (no remaining consumer once the gate is gone)]
   v
Phase 12 (Item 16) Delete drift-guard infrastructure
   |    [the lists they guarded are gone after Phase 11]
   v
Phase 13 (Item 6)  Intersection type support
   |    [new IntersectionMacro.scala; reuses SerializationMacro.caseClassWriteBody / caseClassReadBodyResolved for field encoding; extends FocusMacro.derivedImpl AndType branch and ExpandMacro.expandType]
   v
Phase 14 (Item 15) Test renaming / folding
        [picks up after KeyCodecTest deletion (Phase 8), drift-test deletions (Phase 12), and new IntersectionMacroTest creation (Phase 13)]
```

**Cross-platform verification.** Every phase runs `+ kyo-schema/Test/compile` (cross-aggregate JVM+JS+Native compile) plus `kyo-schema/Test/test` on JVM. Phase 13 (intersection) additionally runs the new tests on JS and Native explicitly to confirm the macro emits portable code. Phase 4 (kyo-data TagMacro) runs `+ kyo-data/Test/compile` + `kyo-data/Test/test` on JVM.

**Total: 14 phases.**
