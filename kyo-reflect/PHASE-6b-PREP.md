# Phase 6b Prep: Record Interop — symbolToRecord Macro and Built-in Reads[Record[F]]

Source of truth: `kyo-reflect/execution-plan.md` lines 531-588, `kyo-reflect/DESIGN.md` sections 11-13.

---

## 1. Verbatim API Signatures

### `kyo.Record` exact signatures (from `kyo-data/shared/src/main/scala/kyo/Record.scala`)

```scala
final infix class ~[Name <: String, -Value] private () extends Serializable

final class Record[F](private[kyo] val dict: Dict[String, Any]) extends Dynamic:
    def selectDynamic[Name <: String & Singleton](name: Name)(using h: Fields.Have[F, Name]): h.Value
    def getField[Name <: String & Singleton, V](name: Name)(using h: Fields.Have[F, Name]): h.Value
    def &[A](other: Record[A]): Record[F & A]
    def update[Name <: String & Singleton, V](name: Name, value: V)(using F <:< (Name ~ V)): Record[F]
    def compact(using f: Fields[F]): Record[F]
    def fields(using f: Fields[F]): List[String]
    inline def values(using f: Fields[F]): f.Values
    def map[G[_]](using f: Fields[F])(fn: [t] => t => G[t]): Record[f.Map[~.MapValue[G]]]
    def mapFields[G[_]](using f: Fields[F])(fn: [t] => (Field[?, t], t) => G[t]): Record[f.Map[~.MapValue[G]]]
    inline def zip[F2](other: Record[F2])(using f1: Fields[F], f2: Fields[F2], ev: Fields.SameNames[F, F2]): Record[f1.Zipped[f2.AsTuple]]
    def size: Int
    def toDict: Dict[String, Any]
end Record

object Record:
    final infix class ~[Name <: String, -Value] private () extends Serializable
    val empty: Record[Any]
    implicit def widen[A <: B, B](r: Record[A]): Record[B]
    extension (self: String)
        def ~[Value](value: Value): Record[self.type ~ Value]  // creates single-field record
    transparent inline def fromProduct[A <: Product](value: A): Any  // macro
    inline def stage[A](using f: Fields[A]): StageOps[A, f.AsTuple]

    class StageOps[A, T <: Tuple](dummy: Unit) extends AnyVal:
        inline def apply[G[_]](fn: [v] => Field[?, v] => G[v])(using f: Fields[A]): Record[f.Map[~.MapValue[G]]]
        inline def using[TC[_]]: StageWith[A, T, TC]

    class StageWith[A, T <: Tuple, TC[_]](dummy: Unit) extends AnyVal:
        inline def apply[G[_]](fn: [v] => (Field[?, v], TC[v]) => G[v])(using f: Fields[A]): Record[f.Map[~.MapValue[G]]]

    private[kyo] def init[F](dict: Dict[String, Any]): Record[F]
```

### `kyo.Fields` typeclass (from `kyo-data/shared/src/main/scala/kyo/Fields.scala`)

`Fields[F]` is derived for any field-intersection type. It provides:

```scala
trait Fields[F]:
    type AsTuple <: Tuple
    type Map[MapFn[_]] // type-level transform
    type Zipped[OtherTuple <: Tuple]
    val fields: List[Field[?, ?]]
    def names: Set[String]
end Fields

object Fields:
    inline def derived[A]: Fields[A] = ${ kyo.internal.FieldsMacros.deriveImpl[A] }

    trait Have[F, Name <: String]:
        type Value
    object Have:
        private[kyo] def unsafe[F, Name <: String, V]: Have[F, Name] { type Value = V }

    trait Comparable[A]
    object Comparable:
        private[kyo] def unsafe[A]: Comparable[A]

    trait SameNames[A, B]
    object SameNames:
        private[kyo] def unsafe[A, B]: SameNames[A, B]

    trait SummonAll[F, TC[_]]:
        def contains(name: String): Boolean
        def get(name: String): TC[?]
end Fields
```

### `Reflect.Symbol` accessors used by Phase 6b

Pure (no effect, from `Reflect.scala` lines 214-231):

```scala
val kind: SymbolKind
val flags: Flags
val name: Name
val owner: Symbol
def fullName: Name
def binaryName: String
def isInline: Boolean
def isContextual: Boolean
def isOpaque: Boolean
def isPackageObject: Boolean
def isModule: Boolean
def isJava: Boolean
def javaSpecific: Maybe[JavaMetadata]
```

Resolving (return `< (Sync & Abort[ReflectError])`, from `Reflect.scala` lines 224-228):

```scala
def declaredType(using Frame): Type < (Sync & Abort[ReflectError])
def parents(using Frame): Chunk[Type] < (Sync & Abort[ReflectError])
def typeParams(using Frame): Chunk[Symbol] < (Sync & Abort[ReflectError])
def declarations(using Frame): Chunk[Symbol] < (Sync & Abort[ReflectError])
def companion(using Frame): Maybe[Symbol] < (Sync & Abort[ReflectError])
```

### `symbolToRecord[F]` macro signature (from DESIGN.md Section 12)

In `Reflect.scala`, replacing the current stub at line 387-388:

```scala
// inline def in public Reflect object:
inline def symbolToRecord[F: Fields](sym: Symbol): Record[F] < (Sync & Abort[ReflectError]) =
    ${ kyo.internal.SymbolToRecordMacro.symbolToRecordImpl[F]('sym) }
```

Macro implementation signature in `SymbolToRecordMacro.scala`:

```scala
def symbolToRecordImpl[F: Type](sym: Expr[Reflect.Symbol])(using q: Quotes): Expr[Record[F] < (Sync & Abort[ReflectError])]
```

### `Reflect.Reads[Record[F]]` built-in signature (from DESIGN.md Section 13)

In `RecordReads.scala`:

```scala
given recordReads[F](using fields: Fields[F]): Reflect.Reads[Record[F]] with
    val symbolKinds: Set[Reflect.SymbolKind]   // = Set(SymbolKind.values*)
    val needsBodies: Boolean                    // = false
    val touchedFields: Reflect.FieldSet         // union of all accessed accessor bits for fields in F
    def read(sym: Reflect.Symbol): Record[F] < (Sync & Abort[ReflectError]) =
        Reflect.symbolToRecord[F](sym)
```

---

## 2. FieldsMacros Intersection-Walking Patterns

File: `kyo-data/shared/src/main/scala/kyo/internal/FieldsMacros.scala`

### Primary decompose function (lines 48-66)

```scala
def decompose(tpe: TypeRepr): Vector[TypeRepr] =
    tpe.dealias match
        case AndType(l, r) => decompose(l) ++ decompose(r)   // line 50
        case OrType(l, r)  => decompose(l) ++ decompose(r)   // line 51
        case _ =>
            if tpe =:= TypeRepr.of[Any] then Vector()        // line 53
            else
                caseClassFields(tpe).getOrElse:              // line 55
                    try
                        tpe.typeSymbol.tree match
                            case typeDef: TypeDef =>
                                typeDef.rhs match
                                    case bounds: TypeBoundsTree =>
                                        val hi = bounds.hi.tpe
                                        if !(hi =:= TypeRepr.of[Any]) then decompose(hi)
                                        else Vector(tpe)
                                    case _ => Vector(tpe)
                            case _ => Vector(tpe)
                    catch case _: Exception => Vector(tpe)
```

Pattern: `AndType(l, r)` is the key case. Given `"name" ~ Name & "flags" ~ Flags`, the Scala compiler normalizes this to `AndType(AppliedType(~, [ConstantType("name"), Name]), AppliedType(~, [ConstantType("flags"), Flags]))`. Recursing on both arms collects each `AppliedType` leaf.

### Field extraction from AppliedType (lines 86-103)

```scala
case AppliedType(_, List(ConstantType(StringConstant(name)), valueType)) =>
    // name is the string literal (e.g. "name")
    // valueType is the value's TypeRepr (e.g. TypeRepr.of[Reflect.Name])
```

This is the leaf pattern. `SymbolToRecordMacro` uses the same decompose/extract approach to collect `(fieldName: String, valueType: TypeRepr)` pairs, then maps each field name to its corresponding `Symbol` accessor.

### `haveImpl` field-name lookup (lines 131-158)

Uses the same `AndType`/`OrType` recursion, with an early-exit on finding the matching `ConstantType(StringConstant(n))` where `n == nameStr`. The `SymbolToRecordMacro` re-implements this walk to validate that every field name in `F` has a known Symbol accessor mapping.

### Key observation for kyo-reflect

The `FieldsMacros.deriveImpl` `decompose` function dealiases first (`tpe.dealias`). The `SymbolToRecordMacro` must also call `.dealias` before walking, because user-defined type aliases (e.g., `type ClassView = "name" ~ Name & ...`) would otherwise appear as a single `TypeRef` rather than the expanded intersection.

---

## 3. Field-to-Accessor Mapping Table

From DESIGN.md Section 12. This table is the contract for `symbolToRecord`'s macro expansion. Every field name in `F` must appear here; any other name triggers `report.errorAndAbort`.

| Field name in F | Symbol accessor | Value type | Effectful? | FieldSet bit |
|---|---|---|---|---|
| `"name"` | `sym.name` | `Reflect.Name` | no | `FieldSet.Name` |
| `"binaryName"` | `sym.binaryName` | `String` | no | `FieldSet.BinaryName` |
| `"flags"` | `sym.flags` | `Reflect.Flags` | no | `FieldSet.Flags` |
| `"kind"` | `sym.kind` | `Reflect.SymbolKind` | no | `FieldSet.Kind` |
| `"owner"` | `sym.owner` | `Reflect.Symbol` | no | `FieldSet.Owner` |
| `"isInline"` | `sym.isInline` | `Boolean` | no | `FieldSet.Flags` |
| `"isContextual"` | `sym.isContextual` | `Boolean` | no | `FieldSet.Flags` |
| `"isOpaque"` | `sym.isOpaque` | `Boolean` | no | `FieldSet.Flags` |
| `"isPackageObject"` | `sym.isPackageObject` | `Boolean` | no | `FieldSet.Flags` |
| `"isModule"` | `sym.isModule` | `Boolean` | no | `FieldSet.Flags` |
| `"isJava"` | `sym.isJava` | `Boolean` | no | `FieldSet.Flags` |
| `"declaredType"` | `sym.declaredType` | `Reflect.Type` | yes | `FieldSet.DeclaredType` |
| `"parents"` | `sym.parents` | `Chunk[Reflect.Type]` | yes | `FieldSet.Parents` |
| `"typeParams"` | `sym.typeParams` | `Chunk[Reflect.Symbol]` | yes | `FieldSet.TypeParams` |
| `"declarations"` | `sym.declarations` | `Chunk[Reflect.Symbol]` | yes | `FieldSet.Members` |
| `"companion"` | `sym.companion` | `Maybe[Reflect.Symbol]` | yes | `FieldSet.Companion` |
| `"javaSpecific"` | `sym.javaSpecific` | `Maybe[Reflect.JavaMetadata]` | no | `FieldSet.JavaSpecific` |
| any other name | `report.errorAndAbort` | — | — | — |

**Type validation rule**: when the macro extracts `valueType` from the `AppliedType(~, [nameType, valueType])` pair, it must check that `valueType =:= expectedType` for the matching accessor. For example, if the user writes `"name" ~ Int`, the macro sees `valueType = TypeRepr.of[Int]` but the expected type for `"name"` is `TypeRepr.of[Reflect.Name]`. The macro must call `report.errorAndAbort` with a message like:

```
Field "name" expects type Reflect.Name but got Int.
Valid type for "name" is Reflect.Name.
```

---

## 4. Built-in `Reads[Record[F]]` Implementation

The built-in `given recordReads[F]` in `RecordReads.scala` delegates entirely to `symbolToRecord[F]`. The `touchedFields` is computed statically: given `Fields[F]`, the macro (or the `given` body itself at runtime via the field name loop) builds the union of `FieldSet` bits for every field name in `F`.

Concretely, the `given` body at runtime computes `touchedFields` by iterating `fields.fields` and mapping each `field.name` to its `FieldSet` bit via the same lookup table as the macro:

```scala
given recordReads[F](using fields: Fields[F]): Reflect.Reads[Record[F]] =
    // Compute touchedFields by walking the field set at runtime
    val tf = fields.fields.foldLeft(Reflect.FieldSet.Empty) { (acc, field) =>
        acc | fieldSetForName(field.name)
    }
    new Reflect.Reads[Record[F]]:
        val symbolKinds: Set[Reflect.SymbolKind] = Set(Reflect.SymbolKind.values*)
        val needsBodies: Boolean                  = false
        val touchedFields: Reflect.FieldSet       = tf
        def read(sym: Reflect.Symbol): Record[F] < (Sync & Abort[ReflectError]) =
            Reflect.symbolToRecord[F](sym)
```

Where `fieldSetForName` is a private helper mapping field name strings to `FieldSet` bits (the same table as Section 3, column 5).

**Wiring with `Reads.derived`**: when the Phase 6 `ReflectMacro.derivedImpl[A]` processes a case class with a field of type `Record[F]`, it calls `Expr.summon[Reflect.Reads[Record[F]]]`. This summon will resolve to `recordReads[F]` provided `Fields[F]` is available (which it always is for well-formed `F` intersection types). The derived case-class macro does NOT expand `symbolToRecord` again itself; it delegates to the already-summed `recordReads` instance.

**`symbolKinds` for `recordReads`**: always `Set(SymbolKind.values*)` because `symbolToRecord` works for any symbol kind (it projects fields directly, it does not filter by kind). The caller is responsible for ensuring the symbol has the expected fields at runtime.

---

## 5. Generated Code Template for `symbolToRecord`

### What the user writes

```scala
type ClassView =
    "name"         ~ Reflect.Name &
    "flags"        ~ Reflect.Flags &
    "parents"      ~ Chunk[Reflect.Type] &
    "declarations" ~ Chunk[Reflect.Symbol]

val view: Record[ClassView] < (Sync & Abort[ReflectError]) =
    Reflect.symbolToRecord[ClassView](classSym)
```

### What the macro emits

The macro walks the intersection type, separates pure fields from effectful fields, then emits a `for/yield` that sequences the effectful calls and builds the `Record` in the yield:

```scala
// Macro expansion of Reflect.symbolToRecord[ClassView](classSym):
{
    // effectful bindings first (for/yield):
    for
        parents      <- classSym.parents
        declarations <- classSym.declarations
    yield
        // Build Record via Dict:
        ("name"         ~ classSym.name) &
        ("flags"        ~ classSym.flags) &
        ("parents"      ~ parents) &
        ("declarations" ~ declarations)
}
```

This uses `Record`'s extension `String ~ Value` to create single-field records and `&` to combine them. The resulting type is `Record["name" ~ Name & "flags" ~ Flags & "parents" ~ Chunk[Type] & "declarations" ~ Chunk[Symbol]]`, which is `Record[ClassView]`.

### Simpler example: pure fields only

```scala
type NameView = "name" ~ Reflect.Name & "kind" ~ Reflect.SymbolKind

// Macro emits (no effectful fields, no for/yield needed):
{
    Kyo.pure(("name" ~ sym.name) & ("kind" ~ sym.kind))
}
```

### Example with mixed Boolean fields

```scala
type IsView = "isJava" ~ Boolean & "isInline" ~ Boolean & "declaredType" ~ Reflect.Type

// Macro emits:
{
    for
        dt <- sym.declaredType
    yield
        ("isJava"       ~ sym.isJava) &
        ("isInline"     ~ sym.isInline) &
        ("declaredType" ~ dt)
}
```

### Alternative: Dict-based assembly (preferred for performance)

Rather than chaining `~` and `&` (which creates intermediate `Record` objects), the macro should emit a `Dict`-building pattern analogous to `FieldsMacros.fromProductImpl` (lines 280-300):

```scala
// Preferred macro output:
{
    for
        parents      <- sym.parents
        declarations <- sym.declarations
    yield
        Record.init[ClassView](
            Dict[String, Any](
                "name"         -> sym.name,
                "flags"        -> sym.flags,
                "parents"      -> parents,
                "declarations" -> declarations
            )
        )
}
```

`Record.init[F]` is `private[kyo]` but `SymbolToRecordMacro` is in `kyo.internal` which can access `private[kyo]` members. This avoids N intermediate `Record` objects for N fields.

### DESIGN.md Example (verbatim from Section 11)

```scala
type FunctionSig =
    "name"       ~ String &
    "params"     ~ Chunk[Type] &
    "returnType" ~ Type

val sig: Record[FunctionSig] < (Sync & Abort[ReflectError]) =
    Reflect.symbolToRecord[FunctionSig](symbol)
```

Note: `"name" ~ String` maps to `sym.name.asString` (calling `Name.asString` on the `Name` value), because the accessor returns `Name` but the field declares `String`. The macro must handle this coercion: when `valueType =:= TypeRepr.of[String]` and the field name is `"name"` (which returns `Name`), emit `sym.name.asString`. Alternatively, re-examine whether `"name" ~ String` should be forbidden (the table says `"name"` maps to `Name`) and the user must write `"name" ~ Reflect.Name`. Per the table in Section 3, `"name"` maps to `Reflect.Name`, not `String` — so `"name" ~ String` is a type-mismatch compile error. The DESIGN.md bridging example (Section 11) uses `"name" ~ String` in `FunctionSig`, but that is AFTER a `mapFields` that transforms `Name -> String`, not the raw `symbolToRecord` output. This distinction is critical: `symbolToRecord` always uses the exact accessor return types; mapping to `String` is done post-hoc via `mapFields`.

---

## 6. TouchedFields Integration for `symbolToRecord`

The `SymbolToRecordMacro` must emit the `touchedFields` hint as part of the `Reads[Record[F]]` instance (not as part of the `symbolToRecord` expansion itself, since `symbolToRecord` returns a `Record[F]`, not a `Reads`). The `touchedFields` calculation happens in `RecordReads.recordReads`:

1. At compile time (in `recordReads`'s body), iterate `fields.fields` (available from the `Fields[F]` instance).
2. For each field name, look up the corresponding `FieldSet` bit (from the table in Section 3).
3. Fold with `|` to produce the union.

Since `Fields[F]` is a compile-time-derived typeclass, the field names are statically known. The `recordReads` `given` computes `touchedFields` once at instance-creation time (not per `read` call). This is pure runtime field enumeration via `fields.fields: List[Field[?, ?]]`.

### Emission path

```
User writes: summon[Reflect.Reads[Record["name" ~ Name & "parents" ~ Chunk[Type]]]]
        |
        v
given recordReads[F](using fields: Fields[F]) resolves with F = "name" ~ Name & "parents" ~ Chunk[Type]
        |
        v
fields.fields = List(Field("name", Tag[Name], ...), Field("parents", Tag[Chunk[Type]], ...))
        |
        v
touchedFields = fieldSetForName("name") | fieldSetForName("parents")
              = FieldSet.Name | FieldSet.Parents
        |
        v
Reads[Record[F]].touchedFields = FieldSet.Name | FieldSet.Parents  ✓
```

The `fieldSetForName` function in `RecordReads.scala` mirrors the accessor mapping table from Section 3. It is a simple `match` expression on `String`.

### Integration with Phase 6's `derivedImpl`

When `ReflectMacro.derivedImpl[ClassInfo]` processes a case class field of type `Record[F]`, it calls `Expr.summon[Reflect.Reads[Record[F]]]`. The returned `recordReads` instance carries `touchedFields`. The Phase 6 macro unions these into the outer case class's `touchedFields` at compile time — exactly the same transitive-touched-fields composition described in Phase 6 PREP Section 4 (C1 concern: track at derivation time, not via tree walk).

---

## 7. Macro File Location

### `SymbolToRecordMacro.scala`

```
kyo-reflect/shared/src/main/scala/kyo/internal/SymbolToRecordMacro.scala
```

Package: `kyo.internal` (flat, NOT in a sub-package, per kyo convention matching `ReflectMacro.scala`).

```scala
package kyo.internal

import kyo.*
import kyo.Reflect.*
import scala.quoted.*

object SymbolToRecordMacro:
    def symbolToRecordImpl[F: Type](sym: Expr[Reflect.Symbol])(using q: Quotes): Expr[Record[F] < (Sync & Abort[ReflectError])] =
        import quotes.reflect.*
        // ... implementation
    end symbolToRecordImpl
end SymbolToRecordMacro
```

Wiring in `Reflect.scala` (replace line 387-388):

```scala
inline def symbolToRecord[F: Fields](sym: Symbol): Record[F] < (Sync & Abort[ReflectError]) =
    ${ kyo.internal.SymbolToRecordMacro.symbolToRecordImpl[F]('sym) }
```

Note: the `Fields[F]` context bound on the `inline def` is needed so `Fields[F]` is always derivable for any `F` passed to `symbolToRecord`. The macro implementation receives `F` as a `Type[F]` but does not need an `Expr[Fields[F]]` — it walks the type directly via `TypeRepr`.

### `RecordReads.scala`

```
kyo-reflect/shared/src/main/scala/kyo/internal/reflect/reads/RecordReads.scala
```

Package: `kyo.internal.reflect.reads` (matching the layout in DESIGN.md Section 4).

```scala
package kyo.internal.reflect.reads

import kyo.*
import kyo.Reflect.*

object RecordReads:
    given recordReads[F](using fields: Fields[F]): Reflect.Reads[Record[F]] = ...
end RecordReads
```

The `given` must be exported from or reachable via the `Reflect.Reads` companion. The recommended approach (same as Phase 6's `ReadsInstances`): add `export kyo.internal.reflect.reads.RecordReads.recordReads` inside `object Reads` in `Reflect.scala`, or have `RecordReads` place its `given` in a location that Scala's implicit search finds without explicit import.

---

## 8. Hygiene Precautions (Inherited from Phase 6)

Phase 6b's `SymbolToRecordMacro` is primarily a code-generation macro, not a tree-walking analysis macro. It walks a `Type[F]` (the intersection type) rather than a generated `Term`. The Phase 6 hygiene precautions (Trees.exists pre-check, Match pattern skip) apply to `TouchedFields.analyze`, which is used by Phase 6's `ReflectMacro.derivedImpl`. Phase 6b does NOT call `TouchedFields.analyze` because:

1. `SymbolToRecordMacro` generates code — it does not analyze existing code.
2. `RecordReads.recordReads` computes `touchedFields` at runtime from `fields.fields`, not by walking a Term.

However, the following Phase 6 hygiene items still apply to `SymbolToRecordMacro`:

### H1. Dealias before walking

```scala
val dealiasedF = TypeRepr.of[F].dealias
```

User-defined type aliases (e.g., `type MyView = "name" ~ Name`) must be dealiased before the `AndType`/`AppliedType` decomposition, exactly as `FieldsMacros.deriveImpl` does at line 73.

### H2. `Any` base case

Stop recursing when `tpe =:= TypeRepr.of[Any]`. Intersection types are right-associative in Scala's internal representation; eventually all intersections bottom out at `Any` (the identity element for `&`). Without this guard, the macro recurses infinitely.

### H3. No `Trees.exists` pre-check needed

`SymbolToRecordMacro` walks a `TypeRepr`, not a `Term`. The `Trees.exists` / `Trees.traverseGoto` infrastructure (from `kyo-direct/Trees.scala`) operates on Terms. No pre-check is needed here.

### H4. `try/catch` on `tpe.typeSymbol.tree`

Matching `FieldsMacros.deriveImpl` lines 57-67: wrap tree access in `try/catch case _: Exception => Vector(tpe)` because calling `.tree` on certain type symbols (e.g., opaque types not in scope) can throw. For `SymbolToRecordMacro`, the "fall through" behavior on unexpected types is `report.errorAndAbort` with a clear message (see Section 10, edge cases).

---

## 9. Edge Cases and Gotchas

### E1. Unrecognized field name

If the user writes `type BadView = "nonexistent" ~ String`, the macro must error:

```
Reflect.symbolToRecord: field "nonexistent" is not a known Symbol accessor.
Valid field names: name, binaryName, flags, kind, owner, isInline, isContextual,
  isOpaque, isPackageObject, isModule, isJava, declaredType, parents, typeParams,
  declarations, companion, javaSpecific.
```

The error must include the full list of valid field names. This is test 9 in the plan.

### E2. Field value type mismatch

If the user writes `type TypeMismatch = "name" ~ Int` (correct name, wrong value type), the macro must error:

```
Reflect.symbolToRecord: field "name" has declared type Int but the Symbol.name
accessor returns Reflect.Name. Use "name" ~ Reflect.Name.
```

This is test 10 in the plan. The macro checks `valueType =:= expectedType` after resolving the accessor. For the Boolean predicates (`isInline`, `isJava`, etc.), the expected type is `Boolean`. For `javaSpecific`, the expected type is `Maybe[JavaMetadata]`.

### E3. `Record[F]`-typed field inside a `derives Reflect.Reads` case class

When Phase 6's `ReflectMacro.derivedImpl` processes:

```scala
case class Wrap(api: Record["name" ~ Reflect.Name], notes: String) derives Reflect.Reads
```

It calls `Expr.summon[Reflect.Reads[Record["name" ~ Reflect.Name]]]`. This must resolve to `RecordReads.recordReads`. For this to work, `RecordReads.recordReads` must be in implicit scope at the `derivedImpl` call site. The agent must ensure the `given` is properly exported from `Reflect.Reads` companion. This is test 13 in the plan.

### E4. Recursive Record types

If a user writes `type F = "child" ~ Record[F]`, this would require `Fields[F]` to be derivable, which in turn requires `Fields["child" ~ Record[F]]`, creating a cycle. Scala's `given` derivation cannot break this cycle automatically. The macro should detect self-referential record types (by tracking type aliases that expand back to themselves) and emit:

```
Reflect.symbolToRecord: recursive Record type F is not supported.
```

In practice, no Symbol accessor returns `Record[_]`, so this situation cannot arise organically via `symbolToRecord`. It could arise if the user constructs a `Record` field type that references itself. The macro should emit the error if `TypeRepr.of[F].dealias` produces a type that, when decomposed, contains a `Record[F]` field where the inner `F` dealiases to the same type as the outer.

### E5. `Frame` propagation through resolving accessors

The resolving accessors (`declaredType`, `parents`, etc.) take `(using Frame)`. The generated `read` body inside `RecordReads.recordReads` calls `Reflect.symbolToRecord[F](sym)`, which expands to the macro. The macro's generated `for/yield` calls `sym.parents`, `sym.declarations`, etc. directly. These calls require an implicit `Frame`.

The `Reflect.Reads.read` trait method signature is:

```scala
def read(sym: Symbol): A < (Sync & Abort[ReflectError])
```

There is NO `(using Frame)` on `read`. This means inside the `read` body, there is no user-provided `Frame`. The resolving accessor calls require `(using Frame)`.

**Resolution**: the `SymbolToRecordMacro` must emit calls with `Frame.internal` explicitly:

```scala
sym.parents(using kyo.Frame.internal)
```

Or, the macro wraps the entire `for/yield` in a `Sync.defer { ... }` which provides a `Frame` context (since `Sync.defer` does accept `(using Frame)`). Check current Phase 6 PREP concern C6 for the definitive resolution approach. The macro must apply the same solution as Phase 6's `derivedImpl`.

### E6. Empty field intersection `F = Any`

If `Fields[F]` is derived for `F = Any`, it produces an empty field list. `symbolToRecord[Any](sym)` should return `Record.empty`. The macro handles this as the base case: no fields to project, emit `Kyo.pure(Record.empty)`.

### E7. `"binaryName" ~ String` vs `"name" ~ Name`

`binaryName` returns `String` (not `Name`). `name` returns `Name`. These are different types. Users must write `"binaryName" ~ String` and `"name" ~ Reflect.Name` respectively. The type-checking in the macro enforces this.

---

## 10. Test Enumeration (14 tests in `RecordInteropTest.scala`)

File: `kyo-reflect/shared/src/test/scala/kyo/RecordInteropTest.scala`

From execution-plan.md lines 556-571:

1. `type View = "name" ~ Reflect.Name & "flags" ~ Reflect.Flags` and `Reflect.symbolToRecord[View](sym)` compiles and returns a `Record[View]`.

2. `record.get("name")` on the result of test 1 equals `sym.name` for a fixture symbol.

3. `record.get("flags")` on the result of test 1 equals `sym.flags` for a fixture symbol.

4. `type WithParents = "name" ~ Reflect.Name & "parents" ~ Chunk[Reflect.Type]` and `Reflect.symbolToRecord[WithParents](sym)` for a class symbol returns non-empty parents.

5. `type WithDecls = "declarations" ~ Chunk[Reflect.Symbol]` and the result has non-empty declarations for a class symbol.

6. `type WithCompanion = "companion" ~ Maybe[Reflect.Symbol]` for a case class with a companion object returns `Present(companionSym)`.

7. `type WithJavaSpecific = "javaSpecific" ~ Maybe[Reflect.JavaMetadata]` for a Java symbol returns `Present(meta)`.

8. `type WithIsJava = "isJava" ~ Boolean` returns `true` for a Java symbol and `false` for a Scala symbol.

9. `type BadField = "nonexistent" ~ String` produces a compile error with `report.errorAndAbort`. (Negative test — must use `assertDoesNotCompile` or similar.)

10. `type TypeMismatch = "name" ~ Int` (wrong type for `name`) produces a compile error. (Negative test.)

11. `summon[Reflect.Reads[Record["name" ~ Reflect.Name & "kind" ~ Reflect.SymbolKind]]]` resolves (built-in `Reads[Record[F]]` found via `recordReads`).

12. `Reflect.Reads[Record[F]].touchedFields` for `"name" ~ Name & "parents" ~ Chunk[Type]` contains `FieldSet.Name | FieldSet.Parents`.

13. `Reflect.symbolToRecord` used inside a `derives Reflect.Reads` case class with a `Record[F]` field compiles: `case class Wrap(api: Record["name" ~ Reflect.Name], notes: String) derives Reflect.Reads`.

14. The `Record.stage[T].using[TypeClass]` bridging idiom from DESIGN.md Section 11 compiles: given `trait Printer[A] { def print(a: A): String }` with instances for `Reflect.Name` and `Reflect.Flags`, `sig.mapFields(...)` on a `Record["name" ~ Name & "flags" ~ Flags]` produces a `Record["name" ~ String & "flags" ~ String]`.

### Verification commands

```
sbt 'project kyo-reflect; testOnly kyo.RecordInteropTest'
```

Plus cross-platform compilation checks:

```
sbt 'kyo-reflectJS/Test/compile; kyo-reflectNative/Test/compile'
```

---

## 11. Concerns

### C1. `Frame` inside `Reads.read` (inherited from Phase 6 C6, critical)

Phase 6 PREP concern C6 identified that `Reads.read` has no `(using Frame)` parameter, but the resolving accessor calls (`sym.parents(using Frame)`) require one. The `SymbolToRecordMacro` generates these calls inside the `read` body. Phase 6 must resolve this before Phase 6b lands. The two options are:

- Add `(using Frame)` to `Reads.read` (changes the trait API — impact on all Phase 6 test code).
- Emit `Frame.internal` at every resolving accessor call site in the macro.

Phase 6b inherits whichever solution Phase 6 chose. The agent must verify the current on-disk `Reads.read` signature and match it.

### C2. `RecordReads.recordReads` implicit scope visibility

The `given recordReads[F]` must be reachable without explicit import in user code. Putting it in `kyo.internal.reflect.reads.RecordReads` is NOT enough — users and the `ReflectMacro.derivedImpl` call `Expr.summon` which searches the implicit scope. The agent must either:

(a) Export from `object Reflect.Reads`: add `export kyo.internal.reflect.reads.RecordReads.recordReads` inside `object Reads`.

(b) Place `given recordReads` directly in `object Reflects.Reads` companion (less clean but works).

Option (a) is cleaner and matches Phase 6's `ReadsInstances` export pattern.

### C3. `Record.init[F]` accessibility

`Record.init[F]` is `private[kyo]`. The macro runs in `kyo.internal` (which is `kyo.internal`, not `kyo`), so it can access `private[kyo]` members only if the package access allows it. In Scala 3, `private[kyo]` means "accessible within package `kyo` and its sub-packages". Since `kyo.internal` IS a sub-package of `kyo`, `private[kyo]` members ARE accessible from `kyo.internal`. Confirmed by analogy with `FieldsMacros.fromProductImpl` in `kyo.internal` which uses `Dict.fromArrayUnsafe` which is also `private[kyo]`.

### C4. `Fields[F]` context bound on the `inline def` in `Reflect`

The current stub at line 387 has no `[F: Fields]` context bound:

```scala
inline def symbolToRecord[F](sym: Symbol): Any < (Sync & Abort[ReflectError]) = ...
```

The replacement must add `[F: Fields]`:

```scala
inline def symbolToRecord[F: Fields](sym: Symbol): Record[F] < (Sync & Abort[ReflectError]) = ...
```

This is technically a public API change (the signature is currently `Any < ...`). Since the current signature is a stub with a `compiletime.error` body, it never compiled successfully for callers, so this is not a backwards-compatibility break. Still, the agent should note this change explicitly in the commit.

### C5. Negative tests (tests 9 and 10) implementation

The `assertDoesNotCompile`-style negative tests must use `scala.compiletime.testing.typeCheckErrors` or the `compileErrors` macro from `kyo-test` (or just `assertNot(compileErrors("...").isEmpty)` if the test framework supports it). The exact test assertion pattern must match what other kyo tests use for negative macro-expansion tests. The agent must check `ReadsDerivationTest.scala` (Phase 6) for the precedent (its tests 9 and 10 are also negative compile tests).

### C6. `mapFields` return type for test 14

Test 14 calls `mapFields` on a `Record["name" ~ Name & "flags" ~ Flags]` with a `Printer[A]` type class. The `mapFields` signature returns `Record[f.Map[~.MapValue[G]]]` which is a dependent type. The test just needs to show the pattern compiles and the return type is `Record["name" ~ String & "flags" ~ String]`. The agent should use an explicit type ascription on the result to make the test self-documenting and to ensure the type is as expected.

### C7. Interaction with `isJava` / `isInline` boolean fields

The mapping for `"isJava" ~ Boolean` maps to `sym.isJava` (a pure accessor, no Frame needed). The macro must correctly identify that these Boolean-returning methods are pure, not effectful, even though they share the `FieldSet.Flags` bit with `sym.flags`. The effectful/pure classification is per-accessor, not per-FieldSet-bit.

### C8. DESIGN.md says `"name" ~ String` in the FunctionSig bridging example (Section 11)

DESIGN.md Section 11's `FunctionSig` type alias uses `"name" ~ String`, not `"name" ~ Reflect.Name`. This appears to conflict with the accessor mapping table in Section 12 which says `"name"` maps to `Reflect.Name`. The resolution: the Section 11 example is a POST-transformation type (after `mapFields` converts `Name -> String`). The `symbolToRecord[FunctionSig]` call in that example would fail if `FunctionSig` uses `"name" ~ String`, because the macro would reject the type mismatch. The DESIGN.md example is illustrating the full pipeline including post-mapFields transformation. The actual `symbolToRecord` call in that pipeline must use `"name" ~ Reflect.Name`, and the `String` version only appears after `mapFields`. The agent must NOT implement `"name" ~ String` as valid input to `symbolToRecord`.
