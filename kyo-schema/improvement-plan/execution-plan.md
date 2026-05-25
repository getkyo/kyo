# kyo-schema Improvement: Execution Plan

Phases are dependency-ordered. Each phase lists seven required headings (dependency justification, files to produce, files to modify, files to delete, code changes, tests, verification command). Test leaves are single-sentence scenarios.

---

### Phase 1 — Fix `isSerializableType` enumeration drift

**Dependency justification**: none; this is the foundation gate. Other phases (6, 9, 10, 11, 12, 13, 14, 15) depend on this gate recognising their new givens or supported types, so the gate must move first.

### Files to produce
- `kyo-schema/shared/src/test/scala/kyo/internal/SerializationMacroDriftTest.scala` — drift-guard suite that introspects `Schema` companion givens and asserts gate recognises each.

### Files to modify
- `kyo-schema/shared/src/main/scala/kyo/internal/SerializationMacro.scala` at L1070-1085: add 8 entries to `primitiveSymbols`.
- Same file at L1089-1098: add `Seq` and `kyo.Span` to `containerSymbols`; delete the stale comment at L1093.
- Same file at L1100-1104: add `tupleSymbols` Set near the existing `mapSymbols`.
- Same file at L1124-1133 (the `match` block inside `check`): add an `Either` branch and a tuple branch.
- `kyo-schema/shared/src/test/scala/kyo/CodecTest.scala`: add a `metaApply` round-trip test per type added to the gate.

### Files to delete
None.

### Public API additions / modifications / removals
None. `isSerializableType` is `private[internal]`.

### Code changes

#### kyo-schema/shared/src/main/scala/kyo/internal/SerializationMacro.scala — L1070-1085

Before:
```scala
val primitiveSymbols = Set(
    TypeRepr.of[String].typeSymbol,
    TypeRepr.of[Int].typeSymbol,
    TypeRepr.of[Long].typeSymbol,
    TypeRepr.of[Double].typeSymbol,
    TypeRepr.of[Float].typeSymbol,
    TypeRepr.of[Boolean].typeSymbol,
    TypeRepr.of[Short].typeSymbol,
    TypeRepr.of[Byte].typeSymbol,
    TypeRepr.of[Char].typeSymbol,
    TypeRepr.of[BigInt].typeSymbol,
    TypeRepr.of[BigDecimal].typeSymbol,
    TypeRepr.of[java.time.Instant].typeSymbol,
    TypeRepr.of[java.time.Duration].typeSymbol,
    TypeRepr.of[kyo.Frame].typeSymbol
)
```

After:
```scala
val primitiveSymbols = Set(
    TypeRepr.of[String].typeSymbol,
    TypeRepr.of[Int].typeSymbol,
    TypeRepr.of[Long].typeSymbol,
    TypeRepr.of[Double].typeSymbol,
    TypeRepr.of[Float].typeSymbol,
    TypeRepr.of[Boolean].typeSymbol,
    TypeRepr.of[Short].typeSymbol,
    TypeRepr.of[Byte].typeSymbol,
    TypeRepr.of[Char].typeSymbol,
    TypeRepr.of[BigInt].typeSymbol,
    TypeRepr.of[BigDecimal].typeSymbol,
    TypeRepr.of[java.time.Instant].typeSymbol,
    TypeRepr.of[java.time.Duration].typeSymbol,
    TypeRepr.of[kyo.Frame].typeSymbol,
    // Added Phase 1:
    TypeRepr.of[java.time.LocalDate].typeSymbol,
    TypeRepr.of[java.time.LocalTime].typeSymbol,
    TypeRepr.of[java.time.LocalDateTime].typeSymbol,
    TypeRepr.of[java.util.UUID].typeSymbol,
    TypeRepr.of[kyo.Instant].typeSymbol,
    TypeRepr.of[kyo.Duration].typeSymbol,
    TypeRepr.of[kyo.Text].typeSymbol,
    TypeRepr.of[Unit].typeSymbol
)
```

#### kyo-schema/shared/src/main/scala/kyo/internal/SerializationMacro.scala — L1087-1098

Before:
```scala
// Container type constructors that need inner type checked
// NOTE: Only include types that have corresponding Schema[Container[A]] givens in Schema companion
val containerSymbols = Set(
    TypeRepr.of[List].typeSymbol,
    TypeRepr.of[Vector].typeSymbol,
    TypeRepr.of[Set].typeSymbol,
    // NOT Seq - there's no Schema[Seq[A]] given
    TypeRepr.of[kyo.Chunk].typeSymbol,
    TypeRepr.of[kyo.Maybe].typeSymbol,
    TypeRepr.of[Option].typeSymbol,
    TypeRepr.of[kyo.Result].typeSymbol
)
```

After:
```scala
// Container type constructors that need inner type checked.
// Each entry has a matching Schema[Container[A]] given in the Schema companion.
val containerSymbols = Set(
    TypeRepr.of[List].typeSymbol,
    TypeRepr.of[Vector].typeSymbol,
    TypeRepr.of[Set].typeSymbol,
    TypeRepr.of[Seq].typeSymbol,
    TypeRepr.of[kyo.Span].typeSymbol,
    TypeRepr.of[kyo.Chunk].typeSymbol,
    TypeRepr.of[kyo.Maybe].typeSymbol,
    TypeRepr.of[Option].typeSymbol,
    TypeRepr.of[kyo.Result].typeSymbol
)
```

#### kyo-schema/shared/src/main/scala/kyo/internal/SerializationMacro.scala — Insert after L1104 (after `mapSymbols`)

Insert at L1105:
```scala
// Tuple type constructors. The arity-bounded ladder lives on the Schema companion;
// Phase 12 extends this set to Tuple1 and Tuple6..Tuple22.
val tupleSymbols = Set(
    TypeRepr.of[Tuple2].typeSymbol,
    TypeRepr.of[Tuple3].typeSymbol,
    TypeRepr.of[Tuple4].typeSymbol,
    TypeRepr.of[Tuple5].typeSymbol
)
```

#### kyo-schema/shared/src/main/scala/kyo/internal/SerializationMacro.scala — L1123-1133

Before:
```scala
dealiased match
    case AppliedType(tycon, List(inner)) if containerSymbols.contains(tycon.typeSymbol) =>
        check(inner)
    case AppliedType(tycon, List(key, value)) if mapSymbols.contains(tycon.typeSymbol) =>
        // For Map/Dict, check both key and value
        check(key) && check(value)
    case AppliedType(tycon, List(err, success)) if tycon.typeSymbol == TypeRepr.of[kyo.Result].typeSymbol =>
        // Result[E, A] needs both E and A serializable
        check(err) && check(success)
    case _ =>
```

After:
```scala
dealiased match
    case AppliedType(tycon, List(inner)) if containerSymbols.contains(tycon.typeSymbol) =>
        check(inner)
    case AppliedType(tycon, List(key, value)) if mapSymbols.contains(tycon.typeSymbol) =>
        // For Map/Dict, check both key and value
        check(key) && check(value)
    case AppliedType(tycon, List(err, success)) if tycon.typeSymbol == TypeRepr.of[kyo.Result].typeSymbol =>
        // Result[E, A] needs both E and A serializable
        check(err) && check(success)
    case AppliedType(tycon, List(a, b)) if tycon.typeSymbol == TypeRepr.of[Either].typeSymbol =>
        // Either[A, B] needs both legs serializable
        check(a) && check(b)
    case AppliedType(tycon, args) if tupleSymbols.contains(tycon.typeSymbol) =>
        // Tuples: every slot must be serializable
        args.forall(check)
    case _ =>
```

#### kyo-schema/shared/src/test/scala/kyo/internal/SerializationMacroDriftTest.scala (new)

```scala
package kyo.internal

import kyo.*
import kyo.test.KyoTest

/** Drift-guard: every `given Schema[T]` in the `Schema` companion must be reachable through `isSerializableType`.
  * The check is performed by a macro that enumerates declared givens on `Schema.type` and calls the gate on each
  * target type, collecting the names that fail.
  */
class SerializationMacroDriftTest extends KyoTest:

    "every Schema companion given is recognised by isSerializableType" in {
        val unrecognised: Seq[String] = SerializationMacroDriftMacro.unrecognisedGivens
        assert(unrecognised.isEmpty, s"isSerializableType rejected: ${unrecognised.mkString(", ")}")
    }

    "Either[String, Int] is recognised" in {
        assert(SerializationMacroDriftMacro.isRecognised[Either[String, Int]])
    }

    "Tuple3[Int, String, Boolean] is recognised" in {
        assert(SerializationMacroDriftMacro.isRecognised[(Int, String, Boolean)])
    }

    "Span[Int] is recognised" in {
        assert(SerializationMacroDriftMacro.isRecognised[kyo.Span[Int]])
    }

end SerializationMacroDriftTest

object SerializationMacroDriftMacro:
    import scala.quoted.*

    inline def unrecognisedGivens: Seq[String] = ${ unrecognisedGivensImpl }
    inline def isRecognised[A]: Boolean        = ${ isRecognisedImpl[A] }

    private def unrecognisedGivensImpl(using Quotes): Expr[Seq[String]] =
        import quotes.reflect.*
        val schemaType   = TypeRepr.of[Schema.type]
        val givenMembers = schemaType.typeSymbol.declaredMethods.filter(_.flags.is(Flags.Given))
        val rejected = givenMembers.flatMap { m =>
            val ret = m.tree match
                case d: DefDef => d.returnTpt.tpe
                case _         => return Nil
            // Strip Schema[T] -> T
            ret match
                case AppliedType(_, List(target)) if !SerializationMacro.isSerializableType(target) =>
                    Some(s"${m.name}: ${target.show}")
                case _ => None
        }
        Expr.ofSeq(rejected.map(Expr(_)))
    end unrecognisedGivensImpl

    private def isRecognisedImpl[A: Type](using Quotes): Expr[Boolean] =
        import quotes.reflect.*
        Expr(SerializationMacro.isSerializableType(TypeRepr.of[A]))
end SerializationMacroDriftMacro
```

### Tests
1. Round-trip a case class with a `java.time.LocalDate` field through `metaApply`; assert decoded value equals encoded value.
2. Round-trip a case class with a `java.time.LocalTime` field via `metaApply`; assert equality.
3. Round-trip a case class with a `java.time.LocalDateTime` field via `metaApply`; assert equality.
4. Round-trip a case class with a `java.util.UUID` field via `metaApply`; assert equality with a fixed UUID literal.
5. Round-trip a case class with a `kyo.Instant` field via `metaApply`; assert equality.
6. Round-trip a case class with a `kyo.Duration` field via `metaApply`; assert equality.
7. Round-trip a case class with a `kyo.Text` field via `metaApply`; assert equality on a non-ASCII string.
8. Round-trip a case class with a `Unit` field via `metaApply`; assert decoded value equals `()`.
9. Round-trip a case class with a `Seq[Int]` field via `metaApply`; assert sequence equality.
10. Round-trip a case class with a `Span[Int]` field via `metaApply`; assert span equality.
11. Round-trip a case class with an `Either[String, Int]` Right value via `metaApply`; assert equality.
12. Round-trip a case class with an `Either[String, Int]` Left value via `metaApply`; assert equality.
13. Round-trip a case class with a `(Int, String, Boolean)` field via `metaApply`; assert tuple equality.
14. Drift-guard: enumerate `Schema` companion `given` declarations at compile time via a macro and assert each target type is reachable through `isSerializableType`; fail with a list of unrecognised types.

Total: 14 leaves.

### Verification command
```
sbt 'kyo-schemaJVM/testOnly *CodecTest *SerializationMacroDriftTest' 2>&1 | tail -20
```

---

### Phase 2 — Fix `Structure.PrimitiveKind` and `StructureMacro.primitiveKindExpr`

**Dependency justification**: none. Isolated to the `Structure` surface, which does not depend on the gate (Phase 1) or any new givens.

### Files to produce
None.

### Files to modify
- `kyo-schema/shared/src/main/scala/kyo/Structure.scala` at L88-93: extend the `PrimitiveKind` enum with 4 cases (`Instant`, `Duration`, `Frame`, `Text`).
- `kyo-schema/shared/src/main/scala/kyo/internal/StructureMacro.scala` at L202-227: add 4 mapping branches in `primitiveKindExpr`.
- `kyo-schema/shared/src/test/scala/kyo/StructureTest.scala`: add tests below.

### Files to delete
None.

### Public API additions / modifications / removals
- `Structure.PrimitiveKind` gains four cases: `Instant`, `Duration`, `Frame`, `Text`. Additive; pattern-match call sites inside kyo-schema must add the new cases.

### Code changes

#### kyo-schema/shared/src/main/scala/kyo/Structure.scala — L88-93

Before:
```scala
enum PrimitiveKind derives CanEqual, Schema:
    case Int, Long, Short, Byte, Char
    case Float, Double
    case BigInt, BigDecimal
    case String, Boolean, Unit
end PrimitiveKind
```

After:
```scala
enum PrimitiveKind derives CanEqual, Schema:
    case Int, Long, Short, Byte, Char
    case Float, Double
    case BigInt, BigDecimal
    case String, Boolean, Unit
    case Instant, Duration, Frame, Text
end PrimitiveKind
```

#### kyo-schema/shared/src/main/scala/kyo/internal/StructureMacro.scala — L216-226

Before:
```scala
else if sym == TypeRepr.of[BigInt].typeSymbol || sym == TypeRepr.of[java.math.BigInteger].typeSymbol then
    '{ Structure.PrimitiveKind.BigInt }
else if sym == TypeRepr.of[BigDecimal].typeSymbol || sym == TypeRepr.of[java.math.BigDecimal].typeSymbol then
    '{ Structure.PrimitiveKind.BigDecimal }
else if sym == TypeRepr.of[Unit].typeSymbol then '{ Structure.PrimitiveKind.Unit }
else
    report.errorAndAbort(
        s"No PrimitiveKind mapping for primitive type: ${tpe.show}. " +
            "Add a case to Structure.PrimitiveKind or remove the type from extendedPrimitiveSymbols."
    )
end if
```

After:
```scala
else if sym == TypeRepr.of[BigInt].typeSymbol || sym == TypeRepr.of[java.math.BigInteger].typeSymbol then
    '{ Structure.PrimitiveKind.BigInt }
else if sym == TypeRepr.of[BigDecimal].typeSymbol || sym == TypeRepr.of[java.math.BigDecimal].typeSymbol then
    '{ Structure.PrimitiveKind.BigDecimal }
else if sym == TypeRepr.of[Unit].typeSymbol then '{ Structure.PrimitiveKind.Unit }
else if sym == TypeRepr.of[kyo.Instant].typeSymbol || sym == TypeRepr.of[java.time.Instant].typeSymbol then
    '{ Structure.PrimitiveKind.Instant }
else if sym == TypeRepr.of[kyo.Duration].typeSymbol || sym == TypeRepr.of[java.time.Duration].typeSymbol then
    '{ Structure.PrimitiveKind.Duration }
else if sym == TypeRepr.of[kyo.Frame].typeSymbol then '{ Structure.PrimitiveKind.Frame }
else if sym == TypeRepr.of[kyo.Text].typeSymbol then '{ Structure.PrimitiveKind.Text }
else
    report.errorAndAbort(
        s"No PrimitiveKind mapping for primitive type: ${tpe.show}. " +
            "Add a case to Structure.PrimitiveKind or remove the type from extendedPrimitiveSymbols."
    )
end if
```

#### kyo-schema/shared/src/test/scala/kyo/StructureTest.scala (additions)

```scala
final case class CaseClassWithInstant(at: kyo.Instant) derives Schema
final case class CaseClassWithDuration(d: kyo.Duration) derives Schema
final case class CaseClassWithFrame(f: kyo.Frame) derives Schema
final case class CaseClassWithText(t: kyo.Text) derives Schema

"PrimitiveKind.Instant" in {
    val s = Structure.of[CaseClassWithInstant]
    s match
        case Structure.Type.Product(_, _, fields, _) =>
            assert(fields.head.tpe.asInstanceOf[Structure.Type.Primitive].kind == Structure.PrimitiveKind.Instant)
        case _ => fail()
}

// Duration / Frame / Text follow the same shape with their respective kinds.

"unmapped primitive triggers error" in {
    assertCompileError(
        "kyo.Structure.of[kyo.MarkedAsPrimitiveButNoKind]",
        "No PrimitiveKind mapping"
    )
}
```

### Tests
1. `Structure.of[CaseClassWithInstant]` compiles and yields a `Type.Product` whose single field has `PrimitiveKind.Instant`.
2. `Structure.of[CaseClassWithDuration]` compiles and yields a field with `PrimitiveKind.Duration`.
3. `Structure.of[CaseClassWithFrame]` compiles and yields a field with `PrimitiveKind.Frame`.
4. `Structure.of[CaseClassWithText]` compiles and yields a field with `PrimitiveKind.Text`.
5. Negative: `Structure.of[CaseClassWithUnsupportedPrimitive]` (using a synthetic class flagged as primitive but unmapped) produces a compile error containing the substring "No PrimitiveKind mapping".

Total: 5 leaves.

### Verification command
```
sbt 'kyo-schemaJVM/testOnly *StructureTest' 2>&1 | tail -20
```

---

### Phase 3 — Consolidate `MacroUtils` symbol sets

**Dependency justification**: none. `MacroUtils` is the consolidation target; `SerializationMacro` updates in this phase delegate to it but the delegation is mechanical and does not depend on Phase 1's new entries (those entries themselves continue to live in `SerializationMacro` since the gate has special two-argument branches that `MacroUtils` does not model).

### Files to produce
- `kyo-schema/shared/src/test/scala/kyo/internal/MacroUtilsDriftTest.scala` — drift-guard suite comparing `MacroUtils` sets to the `Schema` companion given set.

### Files to modify
- `kyo-schema/shared/src/main/scala/kyo/internal/MacroUtils.scala` at L247-256: add `kyo.Result` and `kyo.Span` to `collectionSymbols`.
- Same file at L268-273: add `kyo.Dict` to `mapSymbols`.
- `kyo-schema/shared/src/main/scala/kyo/internal/SerializationMacro.scala` at L1087-1098: replace the body of `containerSymbols` with `MacroUtils.collectionSymbols ++ MacroUtils.optionalSymbols`, keeping the two-argument special-case branches (Result, Either, Map/Dict) unchanged in the `check` body since `MacroUtils` does not differentiate by arity.

### Files to delete
None.

### Public API additions / modifications / removals
None. All symbol sets are `private[internal]`.

### Code changes

#### kyo-schema/shared/src/main/scala/kyo/internal/MacroUtils.scala — L246-256

Before:
```scala
/** Collection type symbols: List, Seq, Vector, Set, Chunk. */
private[internal] def collectionSymbols(using Quotes): Set[quotes.reflect.Symbol] =
    import quotes.reflect.*
    Set(
        TypeRepr.of[List].typeSymbol,
        TypeRepr.of[Seq].typeSymbol,
        TypeRepr.of[Vector].typeSymbol,
        TypeRepr.of[Set].typeSymbol,
        TypeRepr.of[kyo.Chunk].typeSymbol
    )
end collectionSymbols
```

After:
```scala
/** Collection type symbols: List, Seq, Vector, Set, Chunk, Span. */
private[internal] def collectionSymbols(using Quotes): Set[quotes.reflect.Symbol] =
    import quotes.reflect.*
    Set(
        TypeRepr.of[List].typeSymbol,
        TypeRepr.of[Seq].typeSymbol,
        TypeRepr.of[Vector].typeSymbol,
        TypeRepr.of[Set].typeSymbol,
        TypeRepr.of[kyo.Chunk].typeSymbol,
        TypeRepr.of[kyo.Span].typeSymbol
    )
end collectionSymbols
```

#### kyo-schema/shared/src/main/scala/kyo/internal/MacroUtils.scala — L267-273

Before:
```scala
/** Map type symbols: Map. */
private[internal] def mapSymbols(using Quotes): Set[quotes.reflect.Symbol] =
    import quotes.reflect.*
    Set(
        TypeRepr.of[Map].typeSymbol
    )
end mapSymbols
```

After:
```scala
/** Map type symbols: Map, kyo.Dict. */
private[internal] def mapSymbols(using Quotes): Set[quotes.reflect.Symbol] =
    import quotes.reflect.*
    Set(
        TypeRepr.of[Map].typeSymbol,
        TypeRepr.of[kyo.Dict].typeSymbol
    )
end mapSymbols
```

#### kyo-schema/shared/src/main/scala/kyo/internal/SerializationMacro.scala — L1087-1098

After (replaces the literal `containerSymbols` set from Phase 1's edit):
```scala
// Container type constructors that need inner type checked. Sourced from MacroUtils as the
// single source of truth; two-argument shapes (Map/Dict, Result, Either) are handled by
// dedicated branches in `check` below since MacroUtils does not differentiate by arity.
val containerSymbols: Set[Symbol] =
    MacroUtils.collectionSymbols ++ MacroUtils.optionalSymbols
```

#### kyo-schema/shared/src/test/scala/kyo/internal/MacroUtilsDriftTest.scala (new)

```scala
package kyo.internal

import kyo.*
import kyo.test.KyoTest

class MacroUtilsDriftTest extends KyoTest:

    "every container/optional/map given in Schema companion is in MacroUtils" in {
        val unmatched = MacroUtilsDriftMacro.containerGivensNotInMacroUtils
        assert(unmatched.isEmpty, s"MacroUtils missing: ${unmatched.mkString(", ")}")
    }

    "SerializationMacro.containerSymbols equals MacroUtils collection ++ optional" in {
        assert(MacroUtilsDriftMacro.containerSymbolsConsistent)
    }
end MacroUtilsDriftTest

object MacroUtilsDriftMacro:
    import scala.quoted.*
    inline def containerGivensNotInMacroUtils: Seq[String] = ${ containerImpl }
    inline def containerSymbolsConsistent: Boolean         = ${ consistentImpl }

    private def containerImpl(using Quotes): Expr[Seq[String]] =
        // Walk Schema.type givens whose return type is Schema[F[_]] / Schema[F[_, _]];
        // for each F, look up F.typeSymbol in MacroUtils.collectionSymbols ++ optionalSymbols ++ mapSymbols.
        // Collect tycon names that are absent.
        ???

    private def consistentImpl(using Quotes): Expr[Boolean] =
        // Compare SerializationMacro.containerSymbols against MacroUtils.collectionSymbols ++ optionalSymbols
        // at compile time via Set equality.
        ???
end MacroUtilsDriftMacro
```

### Tests
1. Drift-guard: enumerate every `given Schema[T[_]]` and `given Schema[T[_, _]]` in the `Schema` companion at compile time; assert each container-shaped target's type constructor is in `MacroUtils.collectionSymbols`, `MacroUtils.optionalSymbols`, or `MacroUtils.mapSymbols`; fail with the unmatched list.
2. Drift-guard: enumerate `SerializationMacro.containerSymbols` and assert it equals `MacroUtils.collectionSymbols ++ MacroUtils.optionalSymbols` after Phase 3 consolidation.

Total: 2 leaves.

### Verification command
```
sbt 'kyo-schemaJVM/testOnly *MacroUtilsDriftTest *SerializationMacroDriftTest' 2>&1 | tail -20
```

---

### Phase 4 — Route nested field codecs through transform-aware dispatch

**Dependency justification**: none for the fix itself. Placed in the bugfix layer (Phases 1-4) because Phase 15 (union derivation) extends the `.discriminator(name)` API to unions, and a union schema carrying a discriminator must compose correctly under nesting from day one; landing this fix before any phase that produces transform-carrying schemas (Phases 15, 16, and any user-defined `.drop`/`.rename`/`.add` chain) means subsequent phases never have to special-case nested composition. The reporter's failing repro (envelope-wrapped sealed-trait-with-discriminator) is sufficient evidence the bug ships today.

### Files to produce
- `kyo-schema/shared/src/test/scala/kyo/NestedTransformTest.scala` — coverage for transform composition across nesting.

### Files to modify
- `kyo-schema/shared/src/main/scala/kyo/internal/SerializationMacro.scala` at L117 (generic-fallback branch inside `caseClassWriteBody`): replace `'{ $schemaExpr.serializeWrite($fieldAccess, $writer) }` with `'{ kyo.internal.SchemaSerializer.writeTo($schemaExpr, $fieldAccess, $writer) }`. Leave the primitive (L85-91), primitive-element container (L93-101), and Result fast path (L103-113) branches unchanged — those types cannot carry transforms.
- Same file in `caseClassWriteBody`, L69-77 (`maybeFields` branch) and L78-84 (`optionFields` branch): replace `$schemaExpr.serializeWrite(innerVal, $writer)` / `$schemaExpr.serializeWrite($optAccess, $writer)` with `kyo.internal.SchemaSerializer.writeTo(...)`.
- Same file in `caseClassReadBodyResolved` `fieldReadExpr` (L837-866): replace each `$schemaExpr.serializeRead($reader)` call with `kyo.internal.SchemaSerializer.readFrom($schemaExpr, $reader)` for Maybe / Option / generic-reference branches.
- `kyo-schema/shared/src/main/scala/kyo/internal/SchemaSerializer.scala`: visibility check only — `writeTo` and `readFrom` are already `private[kyo]` and reachable from `kyo.internal`. No code change.

### Files to delete
None.

### Public API additions / modifications / removals
None. The fix is internal to `SerializationMacro` macro emission; `Schema` and `SchemaSerializer` public surfaces are unchanged.

### Code changes

#### kyo-schema/shared/src/main/scala/kyo/internal/SerializationMacro.scala — L69-84

Before:
```scala
if maybeFields.contains(idx) then
    val maybeAccess = fieldAccess.asExprOf[kyo.Maybe[Any]]
    List('{
        $maybeAccess match
            case kyo.Present(innerVal) =>
                $writer.fieldBytes($fieldBytes($idxExpr), $fidExpr)
                $schemaExpr.serializeWrite(innerVal, $writer)
            case _ => ()
    })
else if optionFields.contains(idx) then
    val optAccess = fieldAccess.asExprOf[Option[Any]]
    List('{
        if $optAccess.isDefined then
            $writer.fieldBytes($fieldBytes($idxExpr), $fidExpr)
            $schemaExpr.serializeWrite($optAccess, $writer)
    })
```

After:
```scala
if maybeFields.contains(idx) then
    val maybeAccess = fieldAccess.asExprOf[kyo.Maybe[Any]]
    List('{
        $maybeAccess match
            case kyo.Present(innerVal) =>
                $writer.fieldBytes($fieldBytes($idxExpr), $fidExpr)
                kyo.internal.SchemaSerializer.writeTo($schemaExpr, innerVal, $writer)
            case _ => ()
    })
else if optionFields.contains(idx) then
    val optAccess = fieldAccess.asExprOf[Option[Any]]
    List('{
        if $optAccess.isDefined then
            $writer.fieldBytes($fieldBytes($idxExpr), $fidExpr)
            kyo.internal.SchemaSerializer.writeTo($schemaExpr, $optAccess, $writer)
    })
```

#### kyo-schema/shared/src/main/scala/kyo/internal/SerializationMacro.scala — L114-118

Before:
```scala
case None =>
    List(
        '{ $writer.fieldBytes($fieldBytes($idxExpr), $fidExpr) },
        '{ $schemaExpr.serializeWrite($fieldAccess, $writer) }
    )
```

After:
```scala
case None =>
    List(
        '{ $writer.fieldBytes($fieldBytes($idxExpr), $fidExpr) },
        '{ kyo.internal.SchemaSerializer.writeTo($schemaExpr, $fieldAccess, $writer) }
    )
```

#### kyo-schema/shared/src/main/scala/kyo/internal/SerializationMacro.scala — L837-862

Before:
```scala
if isMaybe then
    // Maybe[T]: wrap the inner serializeRead result in kyo.Present.
    ft.asType match
        case '[t] => '{ kyo.Present($schemaExpr.serializeRead($reader)).asInstanceOf[t] }
else if isOption then
    // Option[T]: the Option schema's serializeRead already yields Option[T].
    ft.asType match
        case '[t] => '{ $schemaExpr.serializeRead($reader).asInstanceOf[t] }
else if fieldIsPrimitive(idx) then
    // Direct primitive reader call — no boxing.
    primitiveReadExpr(ft, reader)
else
    containerElementSpec(ft) match
        case Some((containerSym, elemTpe)) =>
            val readExpr = containerReadExpr(containerSym, elemTpe, reader)
            ft.asType match
                case '[t] => '{ $readExpr.asInstanceOf[t] }
        case None =>
            resultFieldSpec(ft) match
                case Some((errTpe, okTpe)) =>
                    val readExpr = resultReadExpr(errTpe, okTpe, reader)
                    ft.asType match
                        case '[t] => '{ $readExpr.asInstanceOf[t] }
                case None =>
                    ft.asType match
                        case '[t] => '{ $schemaExpr.serializeRead($reader).asInstanceOf[t] }
            end match
    end match
end if
```

After:
```scala
if isMaybe then
    ft.asType match
        case '[t] => '{ kyo.Present(kyo.internal.SchemaSerializer.readFrom($schemaExpr, $reader)).asInstanceOf[t] }
else if isOption then
    ft.asType match
        case '[t] => '{ kyo.internal.SchemaSerializer.readFrom($schemaExpr, $reader).asInstanceOf[t] }
else if fieldIsPrimitive(idx) then
    primitiveReadExpr(ft, reader)
else
    containerElementSpec(ft) match
        case Some((containerSym, elemTpe)) =>
            val readExpr = containerReadExpr(containerSym, elemTpe, reader)
            ft.asType match
                case '[t] => '{ $readExpr.asInstanceOf[t] }
        case None =>
            resultFieldSpec(ft) match
                case Some((errTpe, okTpe)) =>
                    val readExpr = resultReadExpr(errTpe, okTpe, reader)
                    ft.asType match
                        case '[t] => '{ $readExpr.asInstanceOf[t] }
                case None =>
                    ft.asType match
                        case '[t] => '{ kyo.internal.SchemaSerializer.readFrom($schemaExpr, $reader).asInstanceOf[t] }
            end match
    end match
end if
```

#### kyo-schema/shared/src/test/scala/kyo/NestedTransformTest.scala (new)

```scala
package kyo

import kyo.test.KyoTest

class NestedTransformTest extends KyoTest:

    sealed trait RO derives CanEqual
    object RO:
        final case class `string`(value: String) extends RO derives Schema
        final case class `number`(value: Int)    extends RO derives Schema

    given Schema[RO]               = Schema.derived[RO].discriminator("type")
    final case class Envelope(result: RO) derives Schema

    "discriminator survives one level of nesting (reporter's repro)" in {
        val v   = Envelope(RO.`string`("hi"))
        val js  = Json.encode(v).toUtf8
        assert(js == """{"result":{"type":"string","value":"hi"}}""", js)
        val dec = Json.decode[Envelope](Span.from(js.getBytes("UTF-8")))
        assert(dec == v)
    }

    "discriminator survives two levels deep" in {
        final case class Middle(payload: RO) derives Schema
        final case class Outer(middle: Middle) derives Schema
        val v   = Outer(Middle(RO.`number`(42)))
        val js  = Json.encode(v).toUtf8
        assert(js.contains("""{"type":"number","value":42}"""), js)
        assert(Json.decode[Outer](Span.from(js.getBytes("UTF-8"))) == v)
    }

    "drop on nested schema omits the dropped field at the inner level" in {
        final case class Inner(visible: String, secret: String) derives Schema
        given Schema[Inner] = Schema.derived[Inner].drop(_.secret)
        final case class Outer(inner: Inner) derives Schema
        val js = Json.encode(Outer(Inner("v", "s"))).toUtf8
        assert(!js.contains("secret"), js)
        assert(js.contains("\"visible\":\"v\""), js)
    }

    "rename on nested schema renames at the inner level" in {
        final case class Inner(x: Int) derives Schema
        given Schema[Inner] = Schema.derived[Inner].rename(_.x, "y")
        final case class Outer(inner: Inner) derives Schema
        val js = Json.encode(Outer(Inner(5))).toUtf8
        assert(js.contains("\"y\":5"), js)
    }

    "add (computed field) on nested schema emits the computed field at the inner level" in {
        final case class Inner(x: Int) derives Schema
        given Schema[Inner] = Schema.derived[Inner].add("derived")(i => i.x * 2)
        final case class Outer(inner: Inner) derives Schema
        val js = Json.encode(Outer(Inner(3))).toUtf8
        assert(js.contains("\"derived\":6"), js)
    }

    "discriminator + drop combine on a nested schema" in {
        // Setup analogous to RO above with each variant carrying `metadata: String`.
        // Asserts both transforms apply at the nested level.
        pending
    }

    "discriminator survives Protobuf round-trip" in {
        val v   = Envelope(RO.`string`("hi"))
        val b   = Protobuf.encode(v)
        val dec = Protobuf.decode[Envelope](b)
        assert(dec == v)
    }
end NestedTransformTest
```

### Tests
1. Reporter's repro: define `sealed trait RO derives CanEqual` with two case-class variants and `given Schema[RO] = Schema.derived[RO].discriminator("type")`; define `case class Envelope(result: RO) derives Schema`; assert `Json.encode[Envelope](Envelope(RO.string("hi")))` produces exactly `{"result":{"type":"string","value":"hi"}}` and the decode of that same string yields `Envelope(RO.string("hi"))`.
2. Discriminator nested two levels deep: round-trip an `Outer(Middle(RO))` value and assert the flat-discriminator form appears at the deepest level.
3. `.drop` on a nested schema: wrap inside `Outer`; assert `Json.encode[Outer](...)` omits the dropped field at the nested level.
4. `.rename` on a nested schema: assert the renamed key appears at the nested level and decoding with the new name yields the original value.
5. `.add` (computed field) on a nested schema: assert the computed field appears at the nested level on encode.
6. Discriminator + drop combined on a nested schema: assert both transforms apply.
7. Protobuf parity: round-trip the test-1 shape through Protobuf instead of JSON; assert the discriminator-aware encoding survives the wire format.

Total: 7 leaves.

### Verification command
```
sbt 'kyo-schemaJVM/testOnly *NestedTransformTest *CodecTest' 2>&1 | tail -20
```

---

### Phase 5 — Protobuf discriminator decode

**Dependency justification**: depends on Phase 4. Phase 4's `NestedTransformTest` Protobuf round-trip leaf (`kyo-schema/shared/src/test/scala/kyo/NestedTransformTest.scala:73-78`) stays RED until this phase ships, exposing Bug E (Protobuf decode of a sealed-trait value carrying `.discriminator(name)` throws `MissingFieldException` because `ProtobufReader.fieldNames` is never populated by the production decode path). Placed before the composition matrix (Phase 6) so the matrix's Sweep B transform-at-position cells can pass on Protobuf as well as JSON.

### Files to produce
None.

### Files to modify
- `kyo-schema/shared/src/main/scala/kyo/internal/ProtobufReader.scala` — no signature change; `withFieldNames` already exists at L31-33. The fix is purely about wiring it from the macro-emitted read prelude.
- `kyo-schema/shared/src/main/scala/kyo/Codec.scala` (or wherever the `Reader` trait lives — verify path) — add a default no-op `withFieldNames(names: Map[Int, String]): this.type = this` to the `Reader` trait so the macro can call it uniformly across `JsonReader`, `ProtobufReader`, and `StructureValueReader` without conditional branching.
- `kyo-schema/shared/src/main/scala/kyo/internal/SerializationMacro.scala` — `caseClassReadBody` and `caseClassReadBodyResolved`: in the read prelude around `$reader.objectStart()` at L990 (and the analogous prelude inside `caseClassReadBody` if it materialises its own object-start), publish the field-name → field-id map to the reader before the field-parse loop begins. The map is built from the already-generated `_fieldNames: Array[String]` (`fieldNamesExpr` at L726, L1009) by zipping each name with its `CodecMacro.fieldId(name)` (CodecMacro.scala:18-19).
- `kyo-schema/shared/src/test/scala/kyo/NestedTransformTest.scala` — promote the Protobuf round-trip leaf at L73-78 from a single shape to a strengthened assertion (use a fresh case-class fixture so the assertion does not couple to the existing `NestedEnvelope` test's setup). Add three more leaves (see Tests below).

### Files to delete
None.

### Public API additions / modifications / removals
- `kyo.Codec.Reader` (the public trait) gains a defaulted `withFieldNames(names: Map[Int, String]): this.type = this`. Existing custom Reader implementations are unaffected by the default.

### Code changes

#### kyo-schema/shared/src/main/scala/kyo/internal/SerializationMacro.scala — L988-995 (the `caseClassReadBodyResolved` outer body)

Before:
```scala
val bodyExpr: Expr[A] = '{
    val _       = $reader.objectStart()
    val nFields = $nExpr
    // `initFields` is purely for pooled-reader lifecycle (e.g. JsonReader depth tracking). Result is discarded.
    val _ = $reader.initFields(nFields)
    ${ initialCall.asExprOf[A] }
}
```

After:
```scala
val bodyExpr: Expr[A] = '{
    // Publish the field-name -> field-id map to the reader so wire formats that key by id
    // (Protobuf) can return canonical names from `field()` / `lastFieldName()`. JSON / structure
    // readers fall through to the no-op default. The map is built once per decode call from the
    // generated `_fieldNames` array; bytecode-wise this is one allocation per case-class read.
    val _names = $fieldNamesExpr
    val _ids   = new Array[Int](_names.length)
    var _i     = 0
    while _i < _names.length do
        _ids(_i) = kyo.internal.CodecMacro.fieldId(_names(_i))
        _i += 1
    val _fieldNameMap: Map[Int, String] =
        (_ids.iterator zip _names.iterator).map((id, n) => id -> n).toMap
    val _ = $reader.withFieldNames(_fieldNameMap)

    val _       = $reader.objectStart()
    val nFields = $nExpr
    // `initFields` is purely for pooled-reader lifecycle (e.g. JsonReader depth tracking). Result is discarded.
    val _ = $reader.initFields(nFields)
    ${ initialCall.asExprOf[A] }
}
```

#### kyo-schema/shared/src/main/scala/kyo/Codec.scala — `Reader` trait

Append a defaulted no-op method:
```scala
/** Publish a field-id -> field-name mapping to this reader. Wire formats that key fields by
  * numeric id (Protobuf) override this to translate `field()` / `lastFieldName()` results back
  * to canonical names. The default is a no-op; readers that already key by name (JSON,
  * StructureValueReader) inherit it.
  */
def withFieldNames(names: Map[Int, String]): this.type = this
```

`ProtobufReader.withFieldNames` (ProtobufReader.scala:31-33) already matches this signature and continues to populate the local `fieldNames` field.

#### kyo-schema/shared/src/test/scala/kyo/NestedTransformTest.scala — replace the L73-78 leaf and add three more

Replace the existing single-leaf body with a strengthened set. Sketch (assume fresh fixtures declared at file top to avoid coupling to `NestedEnvelope`):

```scala
// --- Protobuf discriminator coverage (Phase 5) ---
sealed trait ProtoRO derives CanEqual
object ProtoRO:
    final case class label(value: String) extends ProtoRO derives CanEqual, Schema
    final case class count(value: Int)    extends ProtoRO derives CanEqual, Schema
end ProtoRO
given Schema[ProtoRO]                              = Schema.derived[ProtoRO].discriminator("type")
given Schema[ProtoRO]("$variant")                  // see leaf #3 — uses a separate type for the alt-name case
final case class ProtoEnvelope(payload: ProtoRO) derives CanEqual, Schema

"top-level Protobuf round-trip with discriminator(\"type\")" in {
    val v   = ProtoRO.label("hello")
    val b   = Protobuf.encode[ProtoRO](v)
    val dec = Protobuf.decode[ProtoRO](b)
    assert(dec == Result.succeed(v))
}

"nested Protobuf round-trip with discriminator(\"type\")" in {
    val v   = ProtoEnvelope(ProtoRO.count(42))
    val b   = Protobuf.encode(v)
    val dec = Protobuf.decode[ProtoEnvelope](b)
    assert(dec == Result.succeed(v))
}

"Protobuf round-trip with discriminator field name other than \"type\"" in {
    // Setup uses a separate sealed trait `AltDiscRO` configured with `.discriminator("$variant")`
    // so the bug fix is exercised against a non-default field name.
    val v   = AltEnvelope(AltDiscRO.left("x"))
    val dec = Protobuf.decode[AltEnvelope](Protobuf.encode(v))
    assert(dec == Result.succeed(v))
}

"Protobuf decode of payload missing the discriminator field throws a clear error" in {
    // Encode a sibling case class that does NOT carry the discriminator, decode as the
    // discriminated trait, assert the resulting Result.Failure names the discriminator field.
    val sibling = SiblingNoDisc("hello")
    val bytes   = Protobuf.encode(sibling)
    val result  = Protobuf.decode[ProtoRO](bytes)
    assert(result.failureOpt.exists(_.getMessage.contains("type")), result)
}
```

### Tests
1. Top-level Protobuf round-trip with `.discriminator("type")`: encode a sealed-trait variant directly (no envelope), decode, assert equality.
2. Nested Protobuf round-trip with `.discriminator("type")`: encode the variant wrapped in an `Envelope` case class, decode, assert equality. This is the strengthened version of the existing Phase 4 leaf and stays RED until this phase ships.
3. Protobuf round-trip with a discriminator field name OTHER than `"type"` (e.g. `"$variant"`): assert the same round-trip works against an arbitrary configured discriminator name, ruling out a hard-coded `"type"` regression.
4. Negative: decoding a Protobuf payload that does not carry the discriminator field yields `Result.Failure` whose error is a `MissingFieldException` whose message names the configured discriminator field.

Total: 4 leaves.

### Verification command
```
sbt 'kyo-schemaJVM/testOnly *NestedTransformTest *ProtobufTest' 2>&1 | tail -20
```

---

### Phase 6 — Composition test matrix

**Dependency justification**: depends on Phase 1 (each type must be recognised by `isSerializableType` before being eligible for embedded-position testing) and Phase 4 (the transform-aware nested dispatch must be in place before the transform half of the matrix can pass). Placed before all subsequent given-adding phases (7-14) so the matrix is established as a baseline regression net before new types are added that will plug into it.

### Files to produce
- `kyo-schema/shared/src/test/scala/kyo/CompositionMatrixTest.scala` — composition matrix sweeps.

### Files to modify
None.

### Files to delete
None.

### Public API additions / modifications / removals
None.

### Code changes

#### kyo-schema/shared/src/test/scala/kyo/CompositionMatrixTest.scala (new)

```scala
package kyo

import java.time.Instant
import kyo.test.KyoTest

class CompositionMatrixTest extends KyoTest:

    // ----- Position wrapper case classes (one per embedded position) -----
    final case class Wrap[T](value: T) derives Schema

    // ----- Sample-value providers per type category (C1..C8) -----
    // Each provider supplies (a) a Schema-derivable type and (b) a sample value
    // used both as the canonical encoder input and the equality check target.

    final case class MTUser(name: String, password: String) derives Schema
    sealed trait MTShape derives CanEqual
    object MTShape:
        final case class MTCircle(radius: Double)              extends MTShape derives Schema
        final case class MTRectangle(w: Double, h: Double)     extends MTShape derives Schema
    given Schema[MTShape] = Schema.derived[MTShape]

    private val c1: Int                        = 42
    private val c2: String                     = "hello"
    private val c3: Instant                    = Instant.parse("2024-01-15T10:30:00Z")
    private val c4: List[Int]                  = List(1, 2, 3)
    private val c5: Maybe[Int]                 = Present(42)
    private val c6: Either[String, Int]        = Right(42)
    private val c7: (Int, String)              = (1, "hi")
    private val c8: MTShape                    = MTShape.MTCircle(5.0)

    // ----- Round-trip helper -----
    private def rt[T: Schema](value: T): T =
        val bytes = Json.encode(value)
        Json.decode[T](bytes)

    // ----- Sweep A: 8 categories x 6 positions = 48 leaves -----

    "P1 case-class field — C1 Int" in { assert(rt(Wrap(c1)).value == c1) }
    // Sweep A: 47 more leaves following the same pattern (Wrap(c2)..Wrap(c8), then
    // List(c1, c1)..List(c8, c8), then Present(c1)..Present(c8), then
    // Right(c1)..Right(c8) as Either[String, Cx], then Map("k1"->c1, "k2"->c1)..
    // Map(...->c8), then (c1, true)..(c8, true). See plan §Phase 6.

    // ----- Sweep B: 4 transforms x 7 positions = 28 leaves -----

    // T1 (discriminator on MTShape)
    "PT1 parent field — T1 discriminator" in {
        given Schema[MTShape] = Schema.derived[MTShape].discriminator("type")
        final case class Outer(inner: MTShape) derives Schema
        val js = Json.encode(Outer(MTShape.MTCircle(1.0))).toUtf8
        assert(js.contains("\"type\":\"MTCircle\""), js)
    }
    // Sweep B: 27 more leaves: T1 across PT2..PT7, then T2 (.drop(_.password) on MTUser)
    // across PT1..PT7, T3 (.rename(_.name, "userName") on MTUser) across PT1..PT7,
    // T4 (.add("computed")(...) on MTUser) across PT1..PT7. See plan §Phase 6.

    // ----- Sweep C: 5 composition-invariant leaves -----

    "Sweep C #1 — Envelope(MTShape) discriminator invariant" in {
        given Schema[MTShape] = Schema.derived[MTShape].discriminator("type")
        final case class Envelope(result: MTShape) derives Schema
        val childJs  = Json.encode[MTShape](MTShape.MTCircle(1.0)).toUtf8
        val parentJs = Json.encode(Envelope(MTShape.MTCircle(1.0))).toUtf8
        assert(parentJs.contains(childJs), s"$parentJs does not contain $childJs")
    }
    // Sweep C: 4 more leaves — List[MTShape], Map[String, MTUser].drop, Maybe[MTUser].rename,
    // (MTUser, Boolean).add — each asserting the child's JSON substring appears verbatim
    // (modulo wrapping) inside the parent's JSON. See plan §Phase 6.

end CompositionMatrixTest
```

### Tests

**Sweep A — Type-at-position (48 leaves).** For each of the eight representative type categories below, round-trip a fixed sample value at each of the six embedded positions; assert decoded equals encoded at each position.

Type categories with sample values:
- C1 `Int = 42`
- C2 `String = "hello"`
- C3 `java.time.Instant = Instant.parse("2024-01-15T10:30:00Z")`
- C4 `List[Int] = List(1, 2, 3)`
- C5 `Maybe[Int] = Present(42)` (paired with `Absent` for the Maybe position)
- C6 `Either[String, Int] = Right(42)`
- C7 `(Int, String) = (1, "hi")`
- C8 `MTShape = MTCircle(5.0)` (sealed-trait variant)

Position cells (one leaf per category x position, total 8 x 6 = 48):
1. P1 (case-class field): for each Cx, round-trip `Wrap(sampleCx)` where `case class Wrap[T](value: T) derives Schema`; assert `decoded.value == sampleCx`.
2. P2 (List element): for each Cx, round-trip `List(sampleCx, sampleCx)`; assert decoded sequence equality.
3. P3 (Maybe wrapped): for each Cx, round-trip `Present(sampleCx)`; for one Cx also round-trip `Absent` and assert preserved.
4. P4 (Either Right leg): for each Cx, round-trip `Right(sampleCx): Either[String, Cx]`; assert decoded is `Right` carrying `sampleCx`.
5. P5 (Map value): for each Cx, round-trip `Map("k1" -> sampleCx, "k2" -> sampleCx)`; assert map equality.
6. P6 (Tuple slot): for each Cx, round-trip `(sampleCx, true): (Cx, Boolean)`; assert tuple equality.

**Sweep B — Transform-at-position (28 leaves).** For each of the four schema-level transforms below, build the transformed schema and place it as a given, then round-trip through each of the seven embedded positions; assert the transform applies at the embedded position.

Transforms with setup (paired with `case class MTUser(name: String, password: String) derives Schema`):
- T1 `.discriminator("type")` on MTShape
- T2 `.drop(_.password)` on MTUser
- T3 `.rename(_.name, "userName")` on MTUser
- T4 `.add("computed")(u => u.name.length)` on MTUser

Position cells (one leaf per transform x position, total 4 x 7 = 28):
1. PT1 (parent case-class field): for each Tx, round-trip through `Schema[Outer]` and assert the transform's wire effect is present at the inner level.
2. PT2 (List element): assert each element shows the transform effect.
3. PT3 (Maybe wrapped): assert the inner object shows the transform effect.
4. PT4 (Either Right leg): assert Right leg shows the transform effect.
5. PT5 (Map value): assert the map's value shows the transform effect.
6. PT6 (Tuple slot): assert slot 0 shows the transform effect.
7. PT7 (two-level deep nesting): assert the transform effect is present at the deepest level.

**Sweep C — Composition invariant (5 leaves).** For each (parent shape, transformed child) pair below, encode the child alone and encode the parent containing the same child value; assert the child's encoded JSON value (string-form, modulo wrapping syntax) appears verbatim inside the parent's encoded JSON.
1. Parent `case class Envelope(result: MTShape)`, child `Schema[MTShape].discriminator("type")`.
2. Parent `List[MTShape]` with two elements; assert each element shows the discriminator-flat form.
3. Parent `Map[String, MTUser]` with one entry, child `Schema[MTUser].drop(_.password)`.
4. Parent `Maybe[MTUser]` carrying `Present(user)`, child `Schema[MTUser].rename(_.name, "userName")`.
5. Parent `(MTUser, Boolean)`, child `Schema[MTUser].add("computed")(u => u.name.length)`.

Total: 48 + 28 + 5 = 81 leaves.

### Verification command
```
sbt 'kyo-schemaJVM/testOnly *CompositionMatrixTest' 2>&1 | tail -20
```

---

### Phase 7 — Introduce `KeyCodec[K]` typeclass

**Dependency justification**: none. Self-contained new file; required by Phase 8 (`Map[K, V]` generic schema).

### Files to produce
- `kyo-schema/shared/src/main/scala/kyo/KeyCodec.scala` — new typeclass with built-in givens for String, Int, Long, UUID.
- `kyo-schema/shared/src/test/scala/kyo/KeyCodecTest.scala` — round-trip tests for built-in givens.

### Files to modify
None.

### Files to delete
None.

### Public API additions / modifications / removals
Additions: `kyo.KeyCodec` trait, `kyo.KeyCodec` companion with four built-in givens. No removals.

### Code changes

#### kyo-schema/shared/src/main/scala/kyo/KeyCodec.scala (new)

```scala
package kyo

/** String-key codec for map keys.
  *
  * A `KeyCodec[K]` lets the generic `Map[K, V]` schema encode maps as JSON objects keyed by `K`'s string form.
  * Without a `KeyCodec[K]`, `Map[K, V]` falls back to the array-of-pairs encoding (Phase 8).
  *
  * Built-in instances cover `String`, `Int`, `Long`, and `java.util.UUID`. Implement this trait to make a custom
  * key type eligible for the JSON-object encoding.
  */
trait KeyCodec[K]:
    /** Encode a key to its canonical string form. Must round-trip with `decode`. */
    def encode(k: K): String

    /** Decode a key from its string form. Returns `Result.Failure(DecodeException)` if the input does not parse. */
    def decode(s: String)(using Frame): Result[DecodeException, K]
end KeyCodec

object KeyCodec:

    given stringKeyCodec: KeyCodec[String] with
        def encode(k: String): String                                       = k
        def decode(s: String)(using Frame): Result[DecodeException, String] = Result.succeed(s)

    given intKeyCodec: KeyCodec[Int] with
        def encode(k: Int): String = k.toString
        def decode(s: String)(using Frame): Result[DecodeException, Int] =
            s.toIntOption match
                case Some(n) => Result.succeed(n)
                case None    => Result.fail(ParseException(Json, s"invalid Int key: $s"))

    given longKeyCodec: KeyCodec[Long] with
        def encode(k: Long): String = k.toString
        def decode(s: String)(using Frame): Result[DecodeException, Long] =
            s.toLongOption match
                case Some(n) => Result.succeed(n)
                case None    => Result.fail(ParseException(Json, s"invalid Long key: $s"))

    given uuidKeyCodec: KeyCodec[java.util.UUID] with
        def encode(k: java.util.UUID): String = k.toString
        def decode(s: String)(using Frame): Result[DecodeException, java.util.UUID] =
            try Result.succeed(java.util.UUID.fromString(s))
            catch case _: IllegalArgumentException => Result.fail(ParseException(Json, s"invalid UUID key: $s"))

end KeyCodec
```

#### kyo-schema/shared/src/test/scala/kyo/KeyCodecTest.scala (new)

```scala
package kyo

import java.util.UUID
import kyo.test.KyoTest

class KeyCodecTest extends KyoTest:

    "Int round-trip" in {
        val k = KeyCodec[Int]
        assert(k.decode(k.encode(42)).toOption == Some(42))
    }

    "Long round-trip" in {
        val k = KeyCodec[Long]
        assert(k.decode(k.encode(Long.MaxValue)).toOption == Some(Long.MaxValue))
    }

    "UUID round-trip" in {
        val u = UUID.fromString("12345678-1234-1234-1234-123456789abc")
        val k = KeyCodec[UUID]
        assert(k.decode(k.encode(u)).toOption == Some(u))
    }

    "String round-trip" in {
        val k = KeyCodec[String]
        assert(k.decode(k.encode("hello")).toOption == Some("hello"))
    }

    "Int decode of non-numeric input fails" in {
        val k = KeyCodec[Int].decode("not-a-number")
        assert(k.isFailure)
        assert(k.failureOpt.exists(_.message.show.contains("not-a-number")))
    }

end KeyCodecTest
```

### Tests
1. Round-trip `42` through `KeyCodec[Int].encode` then `decode`; assert equality.
2. Round-trip `Long.MaxValue` through `KeyCodec[Long]`; assert equality.
3. Round-trip a fixed UUID literal through `KeyCodec[java.util.UUID]`; assert equality.
4. Round-trip `"hello"` through `KeyCodec[String]`; assert identity.
5. Negative: `KeyCodec[Int].decode("not-a-number")` returns `Result.Failure` carrying a `DecodeException` with a message naming the invalid input.

Total: 5 leaves.

### Verification command
```
sbt 'kyo-schemaJVM/testOnly *KeyCodecTest' 2>&1 | tail -20
```

---

### Phase 8 — Generic `Map[K, V]` via KeyCodec, array-of-pairs fallback

**Dependency justification**: depends on Phase 7 — the `KeyCodec[K]` typeclass introduced there is the discriminator between the JSON-object encoding (KeyCodec available) and the array-of-pairs fallback (no KeyCodec).

### Files to produce
None.

### Files to modify
- `kyo-schema/shared/src/main/scala/kyo/Schema.scala` immediately after L1529 (`stringMapSchema` ends at L1529): add `mapSchemaWithKeyCodec` and `mapPairsSchema`. Preserve `stringMapSchema` (most-specific given for `Map[String, V]`).
- `kyo-schema/shared/src/test/scala/kyo/CodecTest.scala`: add tests below.

### Files to delete
None.

### Public API additions / modifications / removals
Additions: two new `given` declarations on `Schema` companion. No removals. `Map[String, V]` continues to resolve to `stringMapSchema` (most specific).

### Code changes

#### kyo-schema/shared/src/main/scala/kyo/Schema.scala — Insert at L1530 (immediately after `stringMapSchema`)

Insert at L1530:
```scala
/** Schema for Map[K, V] when a KeyCodec[K] is available — object encoding keyed by `kc.encode(k)`. */
given mapSchemaWithKeyCodec[K, V](using kc: KeyCodec[K], v: Schema[V]): Schema[Map[K, V]] =
    Schema.init[Map[K, V]](
        writeFn = (value, writer) =>
            writer.mapStart(value.size)
            value.iterator.zipWithIndex.foreach { case ((k, vv), idx) =>
                writer.field(kc.encode(k), idx)
                v.serializeWrite(vv, writer)
            }
            writer.mapEnd()
        ,
        readFn = reader =>
            given Frame = reader.frame
            discard(reader.mapStart())
            val builder = Map.newBuilder[K, V]
            @tailrec
            def loop(count: Int): Unit =
                if reader.hasNextEntry() then
                    reader.checkCollectionSize(count)
                    val rawKey = reader.field()
                    val key = kc.decode(rawKey) match
                        case Result.Success(k) => k
                        case Result.Failure(e) => throw e
                        case Result.Panic(t)   => throw t
                    val value = v.serializeRead(reader)
                    builder += (key -> value)
                    loop(count + 1)
            loop(1)
            reader.mapEnd()
            builder.result()
    )

/** Lower-priority fallback for Map[K, V] when no KeyCodec[K] is available — array-of-pairs encoding. */
trait LowPriorityMapGivens:
    given mapPairsSchema[K, V](using kSchema: Schema[K], vSchema: Schema[V]): Schema[Map[K, V]] =
        Schema.init[Map[K, V]](
            writeFn = (value, writer) =>
                writer.arrayStart(value.size)
                value.foreach { case (k, vv) =>
                    writer.arrayStart(2)
                    kSchema.serializeWrite(k, writer)
                    vSchema.serializeWrite(vv, writer)
                    writer.arrayEnd()
                }
                writer.arrayEnd()
            ,
            readFn = reader =>
                discard(reader.arrayStart())
                val builder = Map.newBuilder[K, V]
                @tailrec
                def loop(count: Int): Unit =
                    if reader.hasNextElement() then
                        reader.checkCollectionSize(count)
                        discard(reader.arrayStart())
                        val k = kSchema.serializeRead(reader)
                        val v = vSchema.serializeRead(reader)
                        reader.arrayEnd()
                        builder += (k -> v)
                        loop(count + 1)
                loop(1)
                reader.arrayEnd()
                builder.result()
        )
end LowPriorityMapGivens
```

Note: `object Schema extends LowPriorityMapGivens` must be applied to the `object Schema:` declaration at L1085 to wire the fallback into implicit search at lower priority.

### Tests
1. Round-trip `Map(1 -> "a", 2 -> "b")` (`Map[Int, String]`) as JSON; assert it serialises to `{"1":"a","2":"b"}` and decodes back equal.
2. Round-trip `Map(1L -> "a")` (`Map[Long, String]`) as JSON object; assert equality.
3. Round-trip `Map(UUID.randomUUID -> 1)` (`Map[UUID, Int]`); assert it serialises as object keyed by UUID string and round-trips.
4. Round-trip `Map(CaseClassKey("a", 1) -> "v")` for a key with no `KeyCodec`; assert it serialises as a JSON array of two-element `[key, value]` arrays and round-trips equal.
5. Round-trip `Map((1, 2) -> "v")` (`Map[(Int, Int), String]`); assert array-of-pairs fallback engages and decoded map equals input.

Total: 5 leaves.

### Verification command
```
sbt 'kyo-schemaJVM/testOnly *CodecTest' 2>&1 | tail -20
```

---

### Phase 9 — Shared string-transform givens (cross-platform)

**Dependency justification**: depends on Phase 1. Each new given becomes a recognised primitive; the `isSerializableType` gate must include them or `metaApply`-built schemas fail silently as in Bug A.

### Files to produce
None.

### Files to modify
- `kyo-schema/shared/src/main/scala/kyo/Schema.scala` after L1339 (end of `unitSchema`, immediately before the collection givens header at L1341): add 6 givens.
- `kyo-schema/shared/src/main/scala/kyo/internal/SerializationMacro.scala` `primitiveSymbols` and `containerSymbols`: add the new symbols.
- `kyo-schema/shared/src/test/scala/kyo/CodecTest.scala`: add tests below.

### Files to delete
None.

### Public API additions / modifications / removals
Six new `given` declarations on the `Schema` companion. No removals.

### Code changes

#### kyo-schema/shared/src/main/scala/kyo/Schema.scala — Insert at L1340

Insert at L1340:
```scala
/** Schema for java.math.BigInteger — delegates to bigIntSchema. */
given javaBigIntegerSchema: Schema[java.math.BigInteger] =
    bigIntSchema.transform[java.math.BigInteger](_.bigInteger)(BigInt(_))

/** Schema for java.math.BigDecimal — delegates to bigDecimalSchema. */
given javaBigDecimalSchema: Schema[java.math.BigDecimal] =
    bigDecimalSchema.transform[java.math.BigDecimal](_.bigDecimal)(BigDecimal(_))

/** Schema for scala.Symbol — encoded as its `.name`. */
given symbolSchema: Schema[scala.Symbol] =
    stringSchema.transform[scala.Symbol](Symbol(_))(_.name)

/** Schema for scala.util.matching.Regex — encoded as its source string. */
given regexSchema: Schema[scala.util.matching.Regex] =
    stringSchema.transform[scala.util.matching.Regex](_.r)(_.regex)

/** Schema for Throwable — encoded as `getMessage`.
  *
  * NOTE: class information is lost on round-trip; decoded value is always a `RuntimeException` carrying the
  * original message.
  */
given throwableSchema: Schema[Throwable] =
    stringSchema.transform[Throwable](msg => new RuntimeException(msg))(_.getMessage)

/** Schema for scala.util.Try[A] — encoded as Either[Throwable, A]. */
given trySchema[A](using inner: Schema[A]): Schema[scala.util.Try[A]] =
    eitherSchema[Throwable, A].transform[scala.util.Try[A]] {
        case Left(t)  => scala.util.Failure(t)
        case Right(a) => scala.util.Success(a)
    } {
        case scala.util.Failure(t) => Left(t)
        case scala.util.Success(a) => Right(a)
    }
```

#### kyo-schema/shared/src/main/scala/kyo/internal/SerializationMacro.scala — `primitiveSymbols` set

Append (after the Phase 1 additions):
```scala
TypeRepr.of[java.math.BigInteger].typeSymbol,
TypeRepr.of[java.math.BigDecimal].typeSymbol,
TypeRepr.of[scala.Symbol].typeSymbol,
TypeRepr.of[scala.util.matching.Regex].typeSymbol,
TypeRepr.of[Throwable].typeSymbol
```

#### kyo-schema/shared/src/main/scala/kyo/internal/SerializationMacro.scala — `containerSymbols` set (post-Phase-3 inheriting from MacroUtils, or this set directly if Phase 3 not yet landed)

Append:
```scala
TypeRepr.of[scala.util.Try].typeSymbol
```

(If `containerSymbols` is sourced from `MacroUtils.collectionSymbols` per Phase 3, add the entry in `MacroUtils.collectionSymbols` instead.)

### Tests
1. Round-trip `new java.math.BigInteger("99999999999999999999")` through JSON; assert equality.
2. Round-trip `new java.math.BigDecimal("3.1415926535897932384626")` through JSON; assert equality at scale and unscaled value.
3. Round-trip `Symbol("hello-world")` through JSON; assert decoded `.name` equals original.
4. Round-trip `"a+b".r` through JSON; assert decoded `.regex` equals `"a+b"`.
5. Round-trip `new RuntimeException("boom")` as `Throwable` through JSON; assert decoded `.getMessage` equals `"boom"` and decoded type is `RuntimeException`.
6. Round-trip `scala.util.Success(42)` (`Try[Int]`); assert equality.
7. Round-trip `scala.util.Failure(new RuntimeException("x"))` (`Try[Int]`); assert decoded is `Failure` carrying a `RuntimeException` with message `"x"`.

Total: 7 leaves.

### Verification command
```
sbt 'kyo-schemaJVM/testOnly *CodecTest' 2>&1 | tail -20
```

---

### Phase 10 — JVM-only string-transform givens

**Dependency justification**: depends on Phase 1. JVM-only givens must be recognised by the `isSerializableType` gate when reached on JVM. Because the gate compiles in shared, the JVM-only type symbols cannot be added to the shared gate set directly; instead this phase introduces a `platformPrimitiveSymbols` extension hook on `MacroUtils` (empty in shared, populated on JVM) that `isSerializableType` unions into its check.

### Files to produce
- `kyo-schema/jvm/src/main/scala/kyo/internal/PlatformSchemas.scala` — JVM-only `given` declarations and a JVM override of `MacroUtils.platformPrimitiveSymbols`.
- `kyo-schema/jvm/src/test/scala/kyo/CodecJvmTest.scala` — round-trip tests for the seven JVM-only givens.

### Files to modify
- `kyo-schema/shared/src/main/scala/kyo/internal/MacroUtils.scala`: add `platformPrimitiveSymbols` extension point (empty in shared).
- `kyo-schema/shared/src/main/scala/kyo/internal/SerializationMacro.scala` at L1114 (the `primitiveSymbols.contains(sym)` check): union with `MacroUtils.platformPrimitiveSymbols`.

### Files to delete
None.

### Public API additions / modifications / removals
Seven new `given` declarations visible on JVM only: `Schema[java.net.URI]`, `Schema[java.net.URL]`, `Schema[java.net.InetAddress]`, `Schema[java.nio.file.Path]`, `Schema[java.io.File]`, `Schema[java.util.Locale]`, `Schema[java.util.Currency]`. No removals. No cross-platform API impact.

### Code changes

#### kyo-schema/shared/src/main/scala/kyo/internal/MacroUtils.scala — Insert after `extendedPrimitiveSymbols`

Insert at L245:
```scala
/** Platform-specific primitive symbols. Empty on shared; the JVM build overrides via a parallel
  * `MacroUtils.scala` in `kyo-schema/jvm/src/main/scala/kyo/internal/` (cross-build shadow pattern
  * matching AsciiStringFactory). The gate unions this set into its primitive check at SerializationMacro.scala:L1114.
  */
private[internal] def platformPrimitiveSymbols(using Quotes): Set[quotes.reflect.Symbol] =
    Set.empty
end platformPrimitiveSymbols
```

#### kyo-schema/shared/src/main/scala/kyo/internal/SerializationMacro.scala — L1113-1115

Before:
```scala
// Check if it's a primitive
if primitiveSymbols.contains(sym) then
    true
```

After:
```scala
// Check if it's a primitive (shared + platform-specific)
if primitiveSymbols.contains(sym) || MacroUtils.platformPrimitiveSymbols.contains(sym) then
    true
```

#### kyo-schema/jvm/src/main/scala/kyo/internal/PlatformSchemas.scala (new)

```scala
package kyo.internal

import java.io.File
import java.net.{InetAddress, URI, URL}
import java.nio.file.{Path, Paths}
import java.util.{Currency, Locale}
import kyo.*

/** JVM-only Schema givens for platform-bound types not portable to JS/Native. */
object PlatformSchemas:

    given uriSchema: Schema[URI] =
        Schema.stringSchema.transform[URI](new URI(_))(_.toString)

    given urlSchema: Schema[URL] =
        Schema.stringSchema.transform[URL](new URL(_))(_.toString)

    given inetAddressSchema: Schema[InetAddress] =
        Schema.stringSchema.transform[InetAddress](InetAddress.getByName)(_.getHostAddress)

    given pathSchema: Schema[Path] =
        Schema.stringSchema.transform[Path](s => Paths.get(s))(_.toString)

    given fileSchema: Schema[File] =
        Schema.stringSchema.transform[File](new File(_))(_.getPath)

    given localeSchema: Schema[Locale] =
        Schema.stringSchema.transform[Locale](Locale.forLanguageTag)(_.toLanguageTag)

    given currencySchema: Schema[Currency] =
        Schema.stringSchema.transform[Currency](Currency.getInstance)(_.getCurrencyCode)

end PlatformSchemas
```

A separate JVM-shadow `kyo-schema/jvm/src/main/scala/kyo/internal/MacroUtilsPlatform.scala` defines an override that the macro picks up at compile time. The exact shadow form (companion-augmenting object, full shadow of `MacroUtils.scala`, or a settable `lazy val` initialised by the JVM module) is selected during implementation to match the existing pattern used for `AsciiStringFactory`.

### Tests
1. Round-trip `new URI("https://example.com/path?query=v&q2=w#frag")` through JSON; assert decoded URI equals original.
2. Round-trip `new URL("https://example.com/path")` through JSON; assert decoded URL equals original.
3. Round-trip `InetAddress.getByName("192.168.1.1")` through JSON; assert decoded `getHostAddress` equals `"192.168.1.1"`.
4. Round-trip `Paths.get("foo", "bar", "baz.txt")` through JSON; assert `Paths.get(decoded.toString)` equals original (idempotent under `Paths.get(_).toString`).
5. Round-trip `new File("/tmp/foo")` through JSON; assert decoded `.getPath` equals `/tmp/foo`.
6. Round-trip `Locale.forLanguageTag("zh-Hant-TW")` through JSON; assert decoded `.toLanguageTag` equals `"zh-Hant-TW"` (BCP-47 with script and region).
7. Round-trip `Currency.getInstance("EUR")` through JSON; assert decoded `.getCurrencyCode` equals `"EUR"`.

Total: 7 leaves.

### Verification command
```
sbt 'kyo-schemaJVM/testOnly *CodecJvmTest' 2>&1 | tail -20
```

---

### Phase 11 — java.time gap closure

**Dependency justification**: depends on Phase 1. These primitives must be recognised by `isSerializableType` (else metaApply-built case classes containing them silently no-op as in Bug A's pattern).

### Files to produce
None.

### Files to modify
- `kyo-schema/shared/src/main/scala/kyo/Schema.scala` after L1325 (end of `localDateTimeSchema`): add 8 givens using `stringSchema.transform` for the String-encoded ones and `intSchema.transform` for `Year`.
- `kyo-schema/shared/src/main/scala/kyo/internal/SerializationMacro.scala` `primitiveSymbols`: add the 8 new symbols.
- `kyo-schema/shared/src/test/scala/kyo/CodecTest.scala`: add tests below.

### Files to delete
None.

### Public API additions / modifications / removals
Eight new `given` declarations on the `Schema` companion. No removals.

### Code changes

#### kyo-schema/shared/src/main/scala/kyo/Schema.scala — Insert at L1326 (after `localDateTimeSchema`)

Insert at L1326:
```scala
/** Schema for java.time.ZoneId — encoded as IANA zone id string. */
given zoneIdSchema: Schema[java.time.ZoneId] =
    stringSchema.transform[java.time.ZoneId](java.time.ZoneId.of)(_.getId)

/** Schema for java.time.ZoneOffset — encoded as ISO offset string. */
given zoneOffsetSchema: Schema[java.time.ZoneOffset] =
    stringSchema.transform[java.time.ZoneOffset](java.time.ZoneOffset.of)(_.getId)

/** Schema for java.time.OffsetDateTime — encoded as ISO-8601 string. */
given offsetDateTimeSchema: Schema[java.time.OffsetDateTime] =
    stringSchema.transform[java.time.OffsetDateTime](java.time.OffsetDateTime.parse)(_.toString)

/** Schema for java.time.ZonedDateTime — encoded as ISO-8601 with zone string. */
given zonedDateTimeSchema: Schema[java.time.ZonedDateTime] =
    stringSchema.transform[java.time.ZonedDateTime](java.time.ZonedDateTime.parse)(_.toString)

/** Schema for java.time.Year — encoded as Int. */
given yearSchema: Schema[java.time.Year] =
    intSchema.transform[java.time.Year](java.time.Year.of)(_.getValue)

/** Schema for java.time.YearMonth — encoded as ISO-8601 string. */
given yearMonthSchema: Schema[java.time.YearMonth] =
    stringSchema.transform[java.time.YearMonth](java.time.YearMonth.parse)(_.toString)

/** Schema for java.time.MonthDay — encoded as ISO-8601 string. */
given monthDaySchema: Schema[java.time.MonthDay] =
    stringSchema.transform[java.time.MonthDay](java.time.MonthDay.parse)(_.toString)

/** Schema for java.time.Period — encoded as ISO-8601 string. */
given periodSchema: Schema[java.time.Period] =
    stringSchema.transform[java.time.Period](java.time.Period.parse)(_.toString)
```

#### kyo-schema/shared/src/main/scala/kyo/internal/SerializationMacro.scala — `primitiveSymbols`

Append:
```scala
TypeRepr.of[java.time.ZoneId].typeSymbol,
TypeRepr.of[java.time.ZoneOffset].typeSymbol,
TypeRepr.of[java.time.OffsetDateTime].typeSymbol,
TypeRepr.of[java.time.ZonedDateTime].typeSymbol,
TypeRepr.of[java.time.Year].typeSymbol,
TypeRepr.of[java.time.YearMonth].typeSymbol,
TypeRepr.of[java.time.MonthDay].typeSymbol,
TypeRepr.of[java.time.Period].typeSymbol
```

### Tests
1. Round-trip `ZoneId.of("America/Los_Angeles")` through JSON; assert decoded equals original.
2. Round-trip `ZoneOffset.of("+05:30")` through JSON; assert decoded equals original.
3. Round-trip `OffsetDateTime.parse("2024-03-10T02:30:00-08:00")` (DST spring-forward moment); assert decoded equals original.
4. Round-trip `ZonedDateTime.parse("2024-11-03T01:30:00-07:00[America/Los_Angeles]")` (DST fall-back ambiguous moment); assert decoded equals original including offset.
5. Round-trip `Year.of(2024)` (leap year); assert decoded equals original and `.isLeap` is true.
6. Round-trip `YearMonth.of(2024, 2)` (leap February); assert decoded equals original and `.lengthOfMonth == 29`.
7. Round-trip `MonthDay.of(2, 29)` (leap-day-only valid); assert decoded equals original.
8. Round-trip `Period.of(1, 2, 3)` (1 year, 2 months, 3 days); assert decoded equals original.

Total: 8 leaves.

### Verification command
```
sbt 'kyo-schemaJVM/testOnly *CodecTest' 2>&1 | tail -20
```

---

### Phase 12 — Tuple ladder Tuple1, Tuple6..Tuple22

**Dependency justification**: depends on Phase 1. The gate must accept new tuple arities through the tuple-arity branch added in Phase 1 (extend `tupleSymbols` to include Tuple1 and Tuple6..Tuple22 alongside the Tuple2..Tuple5 already present).

### Files to produce
None.

### Files to modify
- `kyo-schema/shared/src/main/scala/kyo/Schema.scala` immediately after L1536 (`tuple5Schema`): add 18 one-line givens for arities 1, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22.
- `kyo-schema/shared/src/main/scala/kyo/internal/SerializationMacro.scala`: extend `tupleSymbols` (added in Phase 1) to include Tuple1 and Tuple6 through Tuple22.
- `kyo-schema/shared/src/test/scala/kyo/CodecTest.scala`: add tests below.

### Files to delete
None.

### Public API additions / modifications / removals
Eighteen new `given` declarations on the `Schema` companion. No removals.

### Code changes

#### kyo-schema/shared/src/main/scala/kyo/Schema.scala — Insert at L1537

Insert at L1537 (pattern shown for Tuple6 and Tuple1; Tuple7..Tuple22 follow identically):
```scala
given tuple1Schema[A: Schema]: Schema[Tuple1[A]] = Schema.derived

given tuple6Schema[A: Schema, B: Schema, C: Schema, D: Schema, E: Schema, F: Schema]: Schema[(A, B, C, D, E, F)] = Schema.derived

// Tuple7..Tuple22 follow the exact same shape with one extra `: Schema`-bound type
// parameter per arity. Spell each out so implicit search resolves the most-specific given;
// do NOT use `[T <: Tuple]` since erased tuple shapes would lose per-arity inference.
```

#### kyo-schema/shared/src/main/scala/kyo/internal/SerializationMacro.scala — `tupleSymbols`

Replace the Phase 1 definition with:
```scala
val tupleSymbols = Set(
    TypeRepr.of[Tuple1[?]].typeSymbol,
    TypeRepr.of[Tuple2[?, ?]].typeSymbol,
    TypeRepr.of[Tuple3[?, ?, ?]].typeSymbol,
    TypeRepr.of[Tuple4[?, ?, ?, ?]].typeSymbol,
    TypeRepr.of[Tuple5[?, ?, ?, ?, ?]].typeSymbol,
    TypeRepr.of[Tuple6[?, ?, ?, ?, ?, ?]].typeSymbol
    // Tuple7..Tuple22 follow identically; spell each out for clarity.
)
```

### Tests
1. Round-trip `Tuple1(42)` through JSON; assert decoded equals original.
2. Round-trip a `Tuple6[Int, String, Boolean, Long, Double, Char]` with distinct values per slot; assert decoded equals original.
3. Round-trip a `Tuple12` carrying mixed-primitive payload; assert decoded equals original at every slot.
4. Round-trip a `Tuple22` carrying mixed-primitive payload; assert decoded equals original at every slot.

Total: 4 leaves.

### Verification command
```
sbt 'kyo-schemaJVM/testOnly *CodecTest' 2>&1 | tail -20
```

---

### Phase 13 — `Array[A]` and missing immutable collections

**Dependency justification**: depends on Phase 1 (gate must accept new collection types) and Phase 3 (`MacroUtils.collectionSymbols` is the single source of truth and must list these constructors so any downstream macro recognises them).

### Files to produce
None.

### Files to modify
- `kyo-schema/shared/src/main/scala/kyo/Schema.scala` after L1502 (end of `optionSchema`): add givens for `Array[A]`, `ArraySeq[A]`, `Queue[A]`, `SortedSet[A]` (requires `Ordering[A]`), `SortedMap[K, V]`.
- `kyo-schema/shared/src/main/scala/kyo/internal/MacroUtils.scala` `collectionSymbols`: add the new constructors.
- `kyo-schema/shared/src/test/scala/kyo/CodecTest.scala`: add tests below.

### Files to delete
None.

### Public API additions / modifications / removals
Five new `given` declarations on the `Schema` companion. No removals.

### Code changes

#### kyo-schema/shared/src/main/scala/kyo/Schema.scala — Insert at L1503 (after `optionSchema`)

Insert at L1503:
```scala
/** Schema for Array[A] — delegates to spanSchema. */
given arraySchema[A](using inner: Schema[A], ct: scala.reflect.ClassTag[A]): Schema[Array[A]] =
    spanSchema[A].transform[Array[A]](_.toArray)(Span.from)

/** Schema for ArraySeq[A] — encoded as an array of elements. */
given arraySeqSchema[A](using inner: Schema[A]): Schema[scala.collection.immutable.ArraySeq[A]] =
    listSchema[A].transform[scala.collection.immutable.ArraySeq[A]](
        l => scala.collection.immutable.ArraySeq.from(l)
    )(_.toList)

/** Schema for Queue[A] — encoded as an array; preserves FIFO order. */
given queueSchema[A](using inner: Schema[A]): Schema[scala.collection.immutable.Queue[A]] =
    listSchema[A].transform[scala.collection.immutable.Queue[A]](
        l => scala.collection.immutable.Queue.from(l)
    )(_.toList)

/** Schema for SortedSet[A] — encoded as an array; requires Ordering[A]. */
given sortedSetSchema[A](using inner: Schema[A], ord: Ordering[A]): Schema[scala.collection.immutable.SortedSet[A]] =
    listSchema[A].transform[scala.collection.immutable.SortedSet[A]](
        l => scala.collection.immutable.SortedSet.from(l)
    )(_.toList)

/** Schema for SortedMap[K, V] — same encoding rules as Map[K, V] (Phase 8). */
given sortedMapSchema[K, V](using
    kc: KeyCodec[K],
    v: Schema[V],
    ord: Ordering[K]
): Schema[scala.collection.immutable.SortedMap[K, V]] =
    mapSchemaWithKeyCodec[K, V].transform[scala.collection.immutable.SortedMap[K, V]](
        m => scala.collection.immutable.SortedMap.from(m)
    )(_.toMap)
```

#### kyo-schema/shared/src/main/scala/kyo/internal/MacroUtils.scala — `collectionSymbols` (post-Phase-3)

Append:
```scala
TypeRepr.of[scala.collection.immutable.ArraySeq].typeSymbol,
TypeRepr.of[scala.collection.immutable.Queue].typeSymbol,
TypeRepr.of[scala.collection.immutable.SortedSet].typeSymbol,
defn.ArrayClass // Array is special — uses defn.ArrayClass not TypeRepr.of[Array].typeSymbol
```

### Tests
1. Round-trip `Array(1, 2, 3)` through JSON; assert decoded array has same length and `.toSeq` equality.
2. Round-trip `ArraySeq(1, 2, 3)` through JSON; assert decoded equals original.
3. Round-trip `Queue(1, 2, 3)` through JSON; assert decoded equals original including FIFO order.
4. Round-trip `SortedSet(3, 1, 2)` (default Ordering) through JSON; assert decoded equals `SortedSet(1, 2, 3)`.
5. Round-trip `SortedMap(2 -> "b", 1 -> "a")` (`Int` keys; uses Phase 8 KeyCodec object encoding) through JSON; assert decoded equals `SortedMap(1 -> "a", 2 -> "b")` and ordering preserved.

Total: 5 leaves.

### Verification command
```
sbt 'kyo-schemaJVM/testOnly *CodecTest' 2>&1 | tail -20
```

---

### Phase 14 — Java enum derivation

**Dependency justification**: depends on Phase 1. The gate's case-class-or-sealed-only fallback at SerializationMacro.scala:1135-1156 must learn a Java-enum branch; otherwise `metaApply`-built schemas containing Java enums no-op silently.

### Files to produce
- `kyo-schema/shared/src/test/scala/kyo/JavaEnumTest.scala` — new test file.

### Files to modify
- `kyo-schema/shared/src/main/scala/kyo/internal/FocusMacro.scala` `derivedImpl` (L675-695): add a Java-enum branch.
- `kyo-schema/shared/src/main/scala/kyo/internal/ExpandMacro.scala` `expandType` (L18-86): add a Java-enum branch producing a sum-type expansion over `tpe.typeSymbol.children`.
- `kyo-schema/shared/src/main/scala/kyo/internal/SerializationMacro.scala` `check` body (L1124-1158): add a Java-enum recognition arm.

### Files to delete
None.

### Public API additions / modifications / removals
`Schema.derived[E]` now succeeds for Java enum types. No new declared API surface; behaviour extension only.

### Code changes

#### kyo-schema/shared/src/main/scala/kyo/internal/FocusMacro.scala — L683-693

Before:
```scala
val result =
    if sym.isClassDef && sym.flags.is(Flags.Sealed) then
        generateSealedTraitSchema[A, A](nilExpr, checkSerializability = false)
    else if sym.isClassDef && sym.flags.is(Flags.Case) then
        generateCaseClassSchema[A, A](nilExpr, checkSerializability = false)
    else
        report.errorAndAbort(
            s"Schema.derived requires a case class or sealed trait, got: ${tpe.show}. " +
                "Provide a given Schema instance for this type if derivation is not possible."
        )
```

After:
```scala
val result =
    if sym.isClassDef && sym.flags.is(Flags.Sealed) then
        generateSealedTraitSchema[A, A](nilExpr, checkSerializability = false)
    else if sym.isClassDef && sym.flags.is(Flags.Case) then
        generateCaseClassSchema[A, A](nilExpr, checkSerializability = false)
    else if sym.flags.is(Flags.JavaDefined) && sym.flags.is(Flags.Enum) then
        // Java enum derivation: encode by `name`, decode via `Enum.valueOf(cls, name)`.
        val clsExpr: Expr[Class[?]] = '{ Class.forName(${ Expr(tpe.dealias.typeSymbol.fullName) }) }
        '{
            Schema.init[A](
                writeFn = (v, w) => w.string(v.asInstanceOf[java.lang.Enum[?]].name),
                readFn = r =>
                    val name = r.string()
                    java.lang.Enum.valueOf(${ clsExpr }.asInstanceOf[Class[java.lang.Enum[?]]], name).asInstanceOf[A]
            )
        }
    else
        report.errorAndAbort(
            s"Schema.derived requires a case class, sealed trait, or Java enum, got: ${tpe.show}. " +
                "Provide a given Schema instance for this type if derivation is not possible."
        )
```

#### kyo-schema/shared/src/main/scala/kyo/internal/ExpandMacro.scala — L36-69 (sealed-trait sum block)

Insert a Java-enum branch after the case-class arm but before the fallback. Sketch:
```scala
else if sym.flags.is(Flags.JavaDefined) && sym.flags.is(Flags.Enum) then
    // Java enum: expand as sum of singleton variant names like the Scala sealed branch above.
    val tildeType = TypeRepr.of[Record.~]
    val variants = sym.children.map: child =>
        val nameType = ConstantType(StringConstant(child.name))
        tildeType.appliedTo(List(nameType, child.typeRef))
    if variants.nonEmpty then variants.reduce(OrType(_, _))
    else dealiased
```

#### kyo-schema/shared/src/main/scala/kyo/internal/SerializationMacro.scala — L1155-1157 (inside the trailing `else` of `check`)

Before:
```scala
else
    false
end if
```

After:
```scala
else if sym.flags.is(Flags.JavaDefined) && sym.flags.is(Flags.Enum) then
    true
else
    false
end if
```

#### kyo-schema/shared/src/test/scala/kyo/JavaEnumTest.scala (new)

```scala
package kyo

import java.nio.file.StandardOpenOption
import java.time.DayOfWeek
import kyo.test.KyoTest

class JavaEnumTest extends KyoTest:

    given Schema[DayOfWeek]          = Schema.derived[DayOfWeek]
    given Schema[StandardOpenOption] = Schema.derived[StandardOpenOption]

    "round-trip DayOfWeek" in {
        val b = Json.encode(DayOfWeek.WEDNESDAY)
        assert(Json.decode[DayOfWeek](b) == DayOfWeek.WEDNESDAY)
    }

    "round-trip StandardOpenOption" in {
        val b = Json.encode(StandardOpenOption.READ)
        assert(Json.decode[StandardOpenOption](b) == StandardOpenOption.READ)
    }

    "decode of unknown constant fails with UnknownVariantException" in {
        val raw = "\"NOT_A_DAY\""
        val r   = Result.attempt(Json.decode[DayOfWeek](Span.from(raw.getBytes("UTF-8"))))
        assert(r.isFailure)
        assert(r.failureOpt.exists(_.getMessage.contains("NOT_A_DAY")))
    }
end JavaEnumTest
```

### Tests
1. Round-trip `java.time.DayOfWeek.WEDNESDAY` through JSON; assert decoded equals original.
2. Round-trip `java.nio.file.StandardOpenOption.READ` (a Java enum from java.nio) through JSON; assert decoded equals original.
3. Negative: decoding the JSON string `"NOT_A_DAY"` as `java.time.DayOfWeek` yields a `Result.Failure` whose error is an `UnknownVariantException` naming the unknown constant.

Total: 3 leaves.

### Verification command
```
sbt 'kyo-schemaJVM/testOnly *JavaEnumTest' 2>&1 | tail -20
```

---

### Phase 15 — Union type derivation `A | B`

**Dependency justification**: depends on Phase 1 (gate must accept new union types) and Phase 4 (transform-aware nested dispatch is required because a union schema configured with `.discriminator(name)` carries a transform; without Phase 4 the discriminator would be silently lost whenever the union appears as a sub-schema, identical to Bug D's pattern).

### Files to produce
- `kyo-schema/shared/src/main/scala/kyo/internal/UnionMacro.scala` — new macro emitting union-shaped `Schema`. Default untagged.
- `kyo-schema/shared/src/test/scala/kyo/UnionTest.scala` — tests below.

### Files to modify
- `kyo-schema/shared/src/main/scala/kyo/internal/FocusMacro.scala` `derivedImpl` (L675-695): add a top-level `case OrType(_, _) => UnionMacro.derive[A]` branch.
- `kyo-schema/shared/src/main/scala/kyo/internal/ExpandMacro.scala` `expandType` (L18-86): add an `OrType` branch that flattens nested `OrType`s into a flat list of leaf types and rejects degenerate `A | A`.
- `kyo-schema/shared/src/main/scala/kyo/internal/SerializationMacro.scala` `check` body (L1124-1158): add `case OrType(a, b) => check(a) && check(b)`.

### Files to delete
None.

### Public API additions / modifications / removals
- `Schema.derived[A | B]` newly succeeds. No new declared API; behaviour extension.
- The existing `.discriminator(name)` method on `Schema` now also accepts union-derived schemas (no API change; internal pattern match extended).

### Code changes

#### kyo-schema/shared/src/main/scala/kyo/internal/UnionMacro.scala (new)

```scala
package kyo.internal

import kyo.*
import kyo.Codec.{Reader, Writer}
import scala.quoted.*

object UnionMacro:

    /** Entry: build a Schema[T] for a union type T = L1 | L2 | ... | Ln.
      *
      * Reuses FocusMacro.summonSchema (per-leg implicit lookup) and SerializationMacro.isSerializableType
      * (per-leg gate check). Each leg's schema is materialised at compile time; runtime dispatch is by
      * isInstanceOf check on write and by best-effort try-each on read.
      */
    def derive[T: Type](using Quotes): Expr[Schema[T]] =
        import quotes.reflect.*
        val legs       = collectLegs(TypeRepr.of[T])
        val legSchemas = legs.map(materializeLegSchema[T])
        // Sketch: emit a Schema.init[T] whose writeFn pattern-matches the value via
        // instance-of checks against each leg, and whose readFn captures the reader
        // and dispatches by attempting each leg's serializeRead in declaration order.
        '{
            Schema.init[T](
                writeFn = (v, w) => ${ writeBody[T]('v, 'w, legs, legSchemas) },
                readFn  = (r) => ${ readBody[T]('r, legs, legSchemas) }
            )
        }
    end derive

    /** Flatten nested OrTypes into a deduplicated leaf list. Rejects A | A as a compile error. */
    private[internal] def collectLegs(using Quotes)(tpe: quotes.reflect.TypeRepr): List[quotes.reflect.TypeRepr] =
        import quotes.reflect.*
        def go(t: TypeRepr): List[TypeRepr] = t.dealias match
            case OrType(a, b) => go(a) ++ go(b)
            case other        => List(other)
        val raw  = go(tpe)
        val dedupe = raw.distinctBy(_.show)
        if dedupe.size < raw.size then
            report.errorAndAbort(s"Degenerate or duplicate union legs in ${tpe.show}")
        dedupe
    end collectLegs

    private def materializeLegSchema[T: Type](using Quotes)(leg: quotes.reflect.TypeRepr): Expr[Schema[Any]] =
        import quotes.reflect.*
        leg.asType match
            case '[t] =>
                Expr.summon[Schema[t]] match
                    case Some(s) => '{ $s.asInstanceOf[Schema[Any]] }
                    case None    => report.errorAndAbort(s"No given Schema for union leg ${leg.show}")
    end materializeLegSchema

    // writeBody / readBody implementations omitted from this sketch. They produce, respectively:
    //   - a chain of `if (v.isInstanceOf[L1]) legSchemas(0).serializeWrite(v.asInstanceOf[L1], w) else ...`
    //   - a captured-reader try-each: for each leg, `try { return legSchemas(i).serializeRead(r.replay) }
    //     catch { case e: DecodeException => ... }`, with a terminal throw naming every attempted leg.
    private def writeBody[T: Type](using Quotes)(
        v: Expr[T],
        w: Expr[Writer],
        legs: List[quotes.reflect.TypeRepr],
        legSchemas: List[Expr[Schema[Any]]]
    ): Expr[Unit] = ???

    private def readBody[T: Type](using Quotes)(
        r: Expr[Reader],
        legs: List[quotes.reflect.TypeRepr],
        legSchemas: List[Expr[Schema[Any]]]
    ): Expr[T] = ???

end UnionMacro
```

#### kyo-schema/shared/src/main/scala/kyo/internal/FocusMacro.scala — L683-693

Add an `OrType` branch (before the trailing error). Pattern:
```scala
val result = tpe.dealias match
    case OrType(_, _) =>
        UnionMacro.derive[A]
    case _ =>
        // existing arms (sealed / case / Java-enum-from-Phase-13) fall through here
        if sym.isClassDef && sym.flags.is(Flags.Sealed) then ...
        else if sym.isClassDef && sym.flags.is(Flags.Case) then ...
        else if sym.flags.is(Flags.JavaDefined) && sym.flags.is(Flags.Enum) then ...
        else report.errorAndAbort(...)
```

#### kyo-schema/shared/src/main/scala/kyo/internal/ExpandMacro.scala — Inside `expandType` after the `isStructural` short-circuit

Insert:
```scala
case OrType(_, _) =>
    val legs = flattenOrType(dealiased)
    val expanded = legs.map(expandType)
    expanded.reduce(OrType(_, _))
```

with helper:
```scala
private def flattenOrType(using Quotes)(tpe: quotes.reflect.TypeRepr): List[quotes.reflect.TypeRepr] =
    import quotes.reflect.*
    def go(t: TypeRepr): List[TypeRepr] = t.dealias match
        case OrType(a, b) => go(a) ++ go(b)
        case other        => List(other)
    val raw = go(tpe)
    val dedupe = raw.distinctBy(_.show)
    if dedupe.size < raw.size then
        report.errorAndAbort(s"Degenerate or duplicate union legs in ${tpe.show}")
    dedupe
end flattenOrType
```

#### kyo-schema/shared/src/main/scala/kyo/internal/SerializationMacro.scala — Inside the `match` of `check` (after the Phase 1 tuple branch)

Insert:
```scala
case OrType(a, b) =>
    check(a) && check(b)
```

### Tests
1. Round-trip `"hello"` as `Schema[String | Int]`; assert decoded equals `"hello"` and is typed as `String | Int`.
2. Round-trip `42` as `Schema[String | Int]`; assert decoded equals `42`.
3. Round-trip `true` as `Schema[String | Int | Boolean]` (3-way union); assert decoded equals `true`.
4. Round-trip a `Foo` case-class value as `Schema[Foo | Bar]` (two disjoint case classes); assert decoded equals original.
5. Round-trip a `Bar` value as `Schema[Foo | Bar]` (other branch); assert decoded equals original.
6. Round-trip a `Foo` value as `Schema[Foo | Bar].discriminator("kind")`; assert wire shape contains a `"kind"` field with the simple class name and decoded equals original.
7. Compile-error: `Schema.derived[Foo | Foo]` (degenerate union) fails with a message naming "duplicate" or "degenerate" union legs.
8. `Schema.derived[String | Nothing]` reduces to `Schema[String]` and round-trips a String value.
9. `Schema.derived[(String | Int) | Boolean]` flattens to a 3-way union and round-trips each leaf.
10. Round-trip a case class with a field of type `String | Int` containing each variant.
11. Decode failure: decoding the JSON value `false` as `Schema[String | Int]` yields a `Result.Failure` whose error message names both attempted branches.
12. Order-sensitivity: round-trip `123L` as `Schema[Int | Long]` and assert `Long` is preserved as `Long` not narrowed to `Int`.

Total: 12 leaves.

### Verification command
```
sbt 'kyo-schemaJVM/testOnly *UnionTest' 2>&1 | tail -20
```

---

### Phase 16 — Intersection type rejection `A & B`

**Dependency justification**: depends on Phase 15. The `OrType` and `AndType` branches in `derivedImpl` (FocusMacro.scala) live next to each other; introducing the `AndType` rejection in the same area immediately after `OrType` succeeds avoids back-and-forth edits to the same `match` and keeps the macro shape coherent.

### Files to produce
None.

### Files to modify
- `kyo-schema/shared/src/main/scala/kyo/internal/FocusMacro.scala` `derivedImpl`: add an `AndType` rejection arm.
- `kyo-schema/shared/src/test/scala/kyo/SchemaTest.scala` (existing file): add the negative test.

### Files to delete
None.

### Public API additions / modifications / removals
None. `Schema.derived[A & B]` was already an error; the change is to provide a clearer message.

### Code changes

#### kyo-schema/shared/src/main/scala/kyo/internal/FocusMacro.scala — inside the `tpe.dealias match` of `derivedImpl` (added in Phase 15)

Insert above the trailing fallback:
```scala
case AndType(_, _) =>
    report.errorAndAbort(
        s"Schema.derived does not support intersection types: ${tpe.show}. " +
            "Use `.transform` to bridge to/from a concrete representation, or derive the concrete intersecting case class directly."
    )
```

#### kyo-schema/shared/src/test/scala/kyo/SchemaTest.scala (addition)

```scala
"derive of intersection type fails with a clear message" in {
    val msg = compileErrors("Schema.derived[String & Int]")
    assert(msg.nonEmpty)
    assert(msg.contains("intersection"), msg)
    assert(msg.contains(".transform"), msg)
}
```

### Tests
1. `compileErrors("Schema.derived[String & Int]")` returns a non-empty string containing the substrings "intersection" and ".transform"; assert presence of both.

Total: 1 leaf.

### Verification command
```
sbt 'kyo-schemaJVM/testOnly *SchemaTest' 2>&1 | tail -20
```

---

### Phase 17 — Scaladoc and recipe documentation

**Dependency justification**: depends on all prior phases. The supported-type matrix table cannot be written until phases 1-15 have established what is supported.

### Files to produce
None.

### Files to modify
- `kyo-schema/shared/src/main/scala/kyo/Schema.scala`: extend the scaladoc block that precedes `abstract class Schema[A]` at L17-55. The `object Schema` at L1085 has no preceding doc block in the current source, so the additions land on the class doc (which Scaladoc renders on the companion page via `/** Companion ... */` cross-linking).

### Files to delete
None.

### Public API additions / modifications / removals
Scaladoc only. No code surface change.

### Code changes

#### kyo-schema/shared/src/main/scala/kyo/Schema.scala — Append to the doc block ending at L55, just before `@see [[Focus]]`

Insert after the example block (after L38) and before `@tparam`:
```scala
  *
  * == Supported types ==
  *
  * The following types have a `given Schema[T]` available on the `Schema` companion and are recognised by
  * `metaApply` / `Schema.derived`. Where a type is JVM-only, the platform column says so.
  *
  *  - Primitives: `Int`, `Long`, `Short`, `Byte`, `Char`, `Float`, `Double`, `Boolean`, `String`, `Unit`,
  *    `BigInt`, `BigDecimal`, `java.math.BigInteger`, `java.math.BigDecimal`, `scala.Symbol`,
  *    `scala.util.matching.Regex`, `Throwable`
  *  - Time: `kyo.Instant`, `kyo.Duration`, `java.time.Instant`, `java.time.Duration`,
  *    `java.time.LocalDate`, `java.time.LocalTime`, `java.time.LocalDateTime`,
  *    `java.time.OffsetDateTime`, `java.time.ZonedDateTime`, `java.time.ZoneId`, `java.time.ZoneOffset`,
  *    `java.time.Year`, `java.time.YearMonth`, `java.time.MonthDay`, `java.time.Period`
  *  - Identifiers: `java.util.UUID`, `kyo.Frame`, `kyo.Text`
  *  - Collections: `List`, `Vector`, `Seq`, `Set`, `Chunk`, `Span`, `Array`, `ArraySeq`, `Queue`,
  *    `SortedSet`, `Map`, `SortedMap`, `kyo.Dict`
  *  - Optional / sum: `Option`, `Maybe`, `Either`, `kyo.Result`, `scala.util.Try`
  *  - Tuples: `Tuple1` through `Tuple22`
  *  - User-defined: case classes, sealed traits, enums, Scala 3 union types `A | B`, Java enums
  *  - JVM-only: `java.net.URI`, `java.net.URL`, `java.net.InetAddress`, `java.nio.file.Path`,
  *    `java.io.File`, `java.util.Locale`, `java.util.Currency`
  *
  * == Opaque types ==
  *
  * Opaque types do not have an automatic Schema. Provide one via `.transform` on the underlying primitive's
  * schema:
  *
  * {{{
  * opaque type Email = String
  * object Email:
  *   given Schema[Email] = Schema.stringSchema.transform[Email](identity)(identity)
  * }}}
  *
  * == Intersection types ==
  *
  * Intersection types `A & B` are explicitly rejected at derivation. Use `.transform` to bridge to/from a
  * concrete representation, or derive a Schema for the concrete intersecting case class directly.
```

### Tests
No tests. This phase is documentation-only; no runtime or compile-time behaviour changes, so there is nothing to assert beyond the scaladoc compiling cleanly (covered by the regular `compile` step).

### Verification command
```
sbt 'kyo-schemaJVM/doc' 2>&1 | tail -20
```
