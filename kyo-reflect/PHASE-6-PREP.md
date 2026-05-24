# Phase 6 Prep: Reflect.Reads Derivation Macro

Source of truth: `kyo-reflect/execution-plan.md` lines 461-530 and `kyo-reflect/DESIGN.md` section 13.

---

## 1. Verbatim API Signatures

### `Reflect.Reads` trait (from `Reflect.scala` lines 328-333, current on-disk state)

```scala
trait Reads[A]:
    val symbolKinds: Set[SymbolKind]
    val needsBodies: Boolean
    val touchedFields: FieldSet
    def read(sym: Symbol): A < (Sync & Abort[ReflectError])
end Reads

object Reads:
    inline def derived[A]: Reads[A] = scala.compiletime.error("Reflect.Reads.derived not implemented in Phase 0; lands in Phase 6")
```

Phase 6 replaces the `compiletime.error` stub with the macro splice (see Section 2).

### `Reflect.Symbol` pure accessors (from `Reflect.scala` lines 202-229)

Pure (no effect, work after classpath close):
- `val kind: SymbolKind`
- `val flags: Flags`
- `val name: Name`
- `val owner: Symbol`
- `def fullName: Name`
- `def binaryName: String`
- `def isInline: Boolean`
- `def isContextual: Boolean`
- `def isOpaque: Boolean`
- `def isPackageObject: Boolean`
- `def isModule: Boolean`
- `def isJava: Boolean`
- `def javaSpecific: Maybe[JavaMetadata]`

Resolving (return `< (Sync & Abort[ReflectError])`):
- `def declaredType(using Frame): Type < (Sync & Abort[ReflectError])`
- `def parents(using Frame): Chunk[Type] < (Sync & Abort[ReflectError])`
- `def typeParams(using Frame): Chunk[Symbol] < (Sync & Abort[ReflectError])`
- `def declarations(using Frame): Chunk[Symbol] < (Sync & Abort[ReflectError])`
- `def companion(using Frame): Maybe[Symbol] < (Sync & Abort[ReflectError])`

### `Reflect.FieldSet` constants (from `Reflect.scala` lines 340-360)

```scala
final class FieldSet(val bits: Long) extends AnyVal:
    def |(other: FieldSet): FieldSet       = new FieldSet(bits | other.bits)
    def contains(other: FieldSet): Boolean = (bits & other.bits) == other.bits

object FieldSet:
    val Empty: FieldSet        = new FieldSet(0L)
    val Name: FieldSet         = new FieldSet(1L << 0)
    val BinaryName: FieldSet   = new FieldSet(1L << 1)
    val Flags: FieldSet        = new FieldSet(1L << 2)
    val Kind: FieldSet         = new FieldSet(1L << 3)
    val Owner: FieldSet        = new FieldSet(1L << 4)
    val DeclaredType: FieldSet = new FieldSet(1L << 5)
    val Parents: FieldSet      = new FieldSet(1L << 6)
    val TypeParams: FieldSet   = new FieldSet(1L << 7)
    val Members: FieldSet      = new FieldSet(1L << 8)
    val Companion: FieldSet    = new FieldSet(1L << 9)
    val JavaSpecific: FieldSet = new FieldSet(1L << 10)
    val ParamTypes: FieldSet   = new FieldSet(1L << 11)
    val Annotations: FieldSet  = new FieldSet(1L << 12)
    val All: FieldSet          = new FieldSet((1L << 32) - 1)
end FieldSet
```

Note: DESIGN.md section 12 also lists `Positions` and `Comments` bits in its sketch, but the on-disk `Reflect.scala` (the authoritative source) does not include them yet. Phase 6 uses only the bits currently present.

### `Reflect.SymbolKind` enum cases (from `Reflect.scala` lines 117-121)

```scala
enum SymbolKind derives CanEqual:
    case Package, Class, Trait, Object, Method, Field, Val, Var,
        TypeAlias, OpaqueType, AbstractType, TypeParam, Parameter,
        Unresolved
end SymbolKind
```

---

## 2. Verbatim Macro Entry Signature

File: `kyo-reflect/shared/src/main/scala/kyo/internal/ReflectMacro.scala`

Package: `kyo.internal` (flat, per the established kyo internal precedent matching `kyo-schema/kyo-data/kyo-direct` patterns).

Replacement in `Reflect.scala` `object Reads`:

```scala
object Reads:
    inline def derived[A]: Reads[A] = ${ kyo.internal.ReflectMacro.derivedImpl[A] }
```

Macro object skeleton:

```scala
package kyo.internal

import kyo.*
import kyo.Reflect.*
import scala.quoted.*

object ReflectMacro:
    def derivedImpl[A: Type](using q: Quotes): Expr[Reflect.Reads[A]] =
        import quotes.reflect.*
        // ... implementation
    end derivedImpl
end ReflectMacro
```

---

## 3. StructureMacro Reference Patterns

File: `kyo-schema/shared/src/main/scala/kyo/internal/StructureMacro.scala`

Key patterns for `ReflectMacro` to follow:

**TypeRepr.classSymbol + caseFields (lines 68, 130-158):**

```scala
val sym = dealiased.typeSymbol

// Case class detection
if sym.isClassDef && sym.flags.is(Flags.Case) then
    val name    = sym.name
    val newSeen = seen + sym.fullName

    val fieldExprs = sym.caseFields.zipWithIndex.map { (field, idx) =>
        val fieldName = field.name
        val fieldType = dealiased.memberType(field)
        // ... per-field derivation
    }
```

**Sealed trait / enum detection (lines 71-108):**

```scala
if sym.isClassDef && sym.flags.is(Flags.Sealed) then
    val children = sym.children
    // ...
```

**Recursion guard via `seen: Set[String]` (lines 23-29, 75, 131):**

```scala
val typeName = dealiased.typeSymbol.fullName
if seen.contains(typeName) && typeName.nonEmpty then
    // return a placeholder or lazy reference
val newSeen = seen + sym.fullName
```

For `ReflectMacro`, recursion emits `lazy val instance: Reads[Node]` rather than a placeholder type, matching the design spec (see Section 7).

**`Expr.summon[X]` for field-type dispatch (via `Expr.summon[Tag[A]]` at line 184):**

```scala
Expr.summon[Tag[A]] match
    case Some(tagExpr) => ...
    case None          => report.errorAndAbort(...)
```

For `ReflectMacro`, the field dispatch pattern is:

```scala
fieldType.asType match
    case '[ft] =>
        Expr.summon[Reflect.Reads[ft]] match
            case Some(readsExpr) => // use readsExpr for this field
            case None            => report.errorAndAbort(s"No Reads[${TypeRepr.of[ft].show}] available for field $fieldName")
```

---

## 4. FocusMacro.extractAllFocusFieldNames Pattern

File: `kyo-schema/shared/src/main/scala/kyo/internal/FocusMacro.scala` lines 1362-1397.

The `extractAllFocusFieldNames` function is the precedent for field-name collection via tree walking. It uses direct recursive pattern matching rather than `Trees.traverseGoto`. The kyo-reflect `TouchedFields` analysis adapts the `Trees.traverseGoto` + pattern-match idiom from `Trees.scala` instead, per DESIGN.md section 13:

> `FocusMacro.extractAllFocusFieldNames` (kyo-schema, FocusMacro.scala:1362) is the precedent for `touchedFields` analysis: walk a `Term` via `Trees.traverseGoto`, pattern-match `Select(qualifier, methodName)` where `qualifier.tpe <:< TypeRepr.of[Symbol]`, collect method names.

The `extractAllFocusFieldNames` approach (lines 1365-1397) uses a private recursive function. The kyo-reflect `TouchedFields` object instead calls `Trees.traverseGoto` from `kyo-direct/shared/src/main/scala/kyo/internal/Trees.scala` (lines 20-29), using its `Step.goto` mechanic to control traversal descent.

**Adapted pattern for `TouchedFields.analyze`:**

```scala
def analyze(using Quotes)(readBody: quotes.reflect.Term): Reflect.FieldSet =
    import quotes.reflect.*
    var result = Reflect.FieldSet.Empty

    Trees.traverseGoto(readBody) {
        // Skip Match patterns entirely (hygiene guard 2)
        case Match(scrutinee, cases) =>
            Trees.Step.goto(scrutinee)
            cases.foreach { c =>
                c.guard.foreach(Trees.Step.goto)
                Trees.Step.goto(c.rhs)
            }

        // Collect Symbol accessor calls
        case sel @ Select(qualifier, methodName) if qualifier.tpe <:< TypeRepr.of[Reflect.Symbol] =>
            result = result | fieldSetForAccessor(methodName)
            Trees.Step.goto(qualifier)
    }
    result
```

The `fieldSetForAccessor` mapping:

| Method name | FieldSet bit |
|---|---|
| `"name"` | `FieldSet.Name` |
| `"fullName"` | `FieldSet.Name` |
| `"binaryName"` | `FieldSet.BinaryName` |
| `"flags"`, `"isInline"`, `"isContextual"`, `"isOpaque"`, `"isPackageObject"`, `"isModule"`, `"isJava"` | `FieldSet.Flags` |
| `"kind"` | `FieldSet.Kind` |
| `"owner"` | `FieldSet.Owner` |
| `"declaredType"` | `FieldSet.DeclaredType` |
| `"parents"` | `FieldSet.Parents` |
| `"typeParams"` | `FieldSet.TypeParams` |
| `"declarations"` | `FieldSet.Members` |
| `"companion"` | `FieldSet.Companion` |
| `"javaSpecific"` | `FieldSet.JavaSpecific` |
| anything else | `FieldSet.Empty` (ignored) |

**Transitive touchedFields:** After the tree walk, for each `Select(qualifier, "read")` where `qualifier.tpe <:< TypeRepr.of[Reflect.Reads[?]]`, the macro evaluates `qualifier`'s statically-known `touchedFields` at compile time and unions it in. This is the transitive propagation described in DESIGN.md section 13.

---

## 5. Hygiene Precautions from kyo-direct PR (Validate.scala)

File: `kyo-direct/shared/src/main/scala/kyo/internal/Validate.scala` and `Trees.scala`.

**Hygiene rule 1: `Trees.exists` pre-check guard (Trees.scala lines 49-55):**

```scala
def exists(using quotes: Quotes)(tree: quotes.reflect.Tree)(pf: PartialFunction[quotes.reflect.Tree, Boolean]) =
    var r = false
    traverse(tree) {
        case t if pf.isDefinedAt(t) && !r => r = pf(t)
    }
    r
```

Usage in `TouchedFields.analyze`: before calling `Trees.traverseGoto`, add a pre-check:

```scala
val hasSymbolSelect = Trees.exists(readBody) {
    case Select(qualifier, _) if qualifier.tpe <:< TypeRepr.of[Reflect.Symbol] => true
}
if !hasSymbolSelect then Reflect.FieldSet.Empty
else // proceed with full traverseGoto walk
```

This avoids unnecessary `TreeTraverser` recursion when the body contains no Symbol-typed selections.

**Hygiene rule 2: Match pattern skip (Validate.scala lines 238-243):**

```scala
// Verbatim from Validate.scala:
case Match(scrutinee, cases) =>
    Trees.Step.goto(scrutinee)
    cases.foreach { c =>
        c.guard.foreach(Trees.Step.goto)
        Trees.Step.goto(c.rhs)
    }
```

This is copied directly into `TouchedFields.analyze`'s `traverseGoto` partial function. `Bind` / `Wildcard` / `Unapply` subtrees in `.pattern` carry effect-tagged types from generic destructuring that look like accessor uses but are not real `Symbol` field reads.

**Hygiene rule 3: `Block(Nil, ...)` lambda wrapper peeling (Validate.scala lines 79-82):**

```scala
@tailrec
def unwrapLambda(t: Tree): Option[Tree] = t match
    case Block(List(DefDef(_, _, _, Some(body))), _) => Some(body)
    case Block(Nil, expr)                            => unwrapLambda(expr)
    case _                                           => None
```

Relevant when the derived `read` body is emitted as a lambda block. The touched-fields analysis should unwrap leading empty-stat `Block(Nil, ...)` wrappers before entering `traverseGoto`.

**Hygiene rule 4: `ByNameType` arg detection (Validate.scala lines 118-131):**

Not directly applicable to `ReflectMacro` since the generated `read` body uses `for/yield` with no by-name arguments. Document as a "not needed" item to avoid confusion.

**Hygiene rule 5: Owner consistency:**

From Trees.scala line 28: `traverseTree(tree)(Symbol.spliceOwner)`. The `Trees.traverseGoto` implementation already passes `Symbol.spliceOwner` as the owner for the traversal root. Inside `TreeMap` callbacks (if used), use `given Quotes = owner.asQuotes` when splicing into a new subtree. Since `TouchedFields.analyze` is read-only (`TreeTraverser` not `TreeMap`), owner threading is only relevant in the code-generation phase of `derivedImpl`, not in the analysis phase.

---

## 6. Generated Code Template

From DESIGN.md section 13, verbatim for a 2-field case class:

```scala
// User writes:
case class MethodSig(
    name:       Name,
    flags:      Flags,
    returnType: Type,
    params:     Chunk[Type]
) derives Reflect.Reads

// Macro generates:
given Reflect.Reads[MethodSig] = new Reflect.Reads[MethodSig]:
    val symbolKinds   = Set(SymbolKind.Method)
    val needsBodies   = false
    val touchedFields = FieldSet.Name | Flags | DeclaredType | ParamTypes
    def read(sym: Symbol): MethodSig < (Sync & Abort[ReflectError]) =
        for
            sig <- sym.declaredType
        yield MethodSig(
            name       = sym.name,                          // pure accessor
            flags      = sym.flags,                         // pure accessor
            returnType = sig.asMethod.resultType,
            params     = sig.asMethod.paramTypes
        )
```

And for a composed case class:

```scala
// User writes:
case class ClassInfo(
    name:    Name,
    flags:   Flags,
    parents: Chunk[Type],
    methods: Chunk[MethodSig]
) derives Reflect.Reads

// Macro generates:
given Reflect.Reads[ClassInfo] = new Reflect.Reads[ClassInfo]:
    val symbolKinds   = Set(SymbolKind.Class, SymbolKind.Trait, SymbolKind.Object)
    val needsBodies   = false
    val touchedFields = FieldSet.Name | Flags | Parents | Members
    val methodReads   = summon[Reflect.Reads[MethodSig]]
    def read(sym: Symbol): ClassInfo < (Sync & Abort[ReflectError]) =
        for
            parents <- sym.parents
            decls   <- sym.declarations
            methods <- Kyo.foreach(decls.filter(d => methodReads.symbolKinds.contains(d.kind)))(methodReads.read)
        yield ClassInfo(
            name    = sym.name,
            flags   = sym.flags,
            parents = parents,
            methods = methods
        )
```

### `symbolKinds` inference rules (from execution-plan.md lines 471)

Two branches:
1. If the `Reads` body uses ONLY pure accessor fields (`name`, `flags`, `kind`, `fullName`, `binaryName`, `isJava`, `annotations`, `javaSpecific`): emit `Set(SymbolKind.values*)` (all kinds accepted).
2. If the body accesses ANY structural field (`parents`, `declarations`, `typeParams`): emit `Set(SymbolKind.Class, SymbolKind.Trait, SymbolKind.Object)` (narrowed to kinds that carry those fields).

The macro determines this by calling `TouchedFields.analyze` on the generated `read` body and checking whether `FieldSet.Parents`, `FieldSet.Members`, or `FieldSet.TypeParams` bits are set.

### `needsBodies` inference rule

`needsBodies = false` always for Phase 6. Body decode is deferred to a future phase. No case class field type in the current API requires body access.

---

## 7. Recursive Case Classes

From DESIGN.md section 13:

```scala
case class Node(name: Name, children: Chunk[Node]) derives Reflect.Reads
```

Macro emits `lazy val instance: Reads[Node]` to handle self-reference:

```scala
given Reflect.Reads[Node] = {
    lazy val instance: Reflect.Reads[Node] = new Reflect.Reads[Node]:
        val symbolKinds   = Set(SymbolKind.values*)
        val needsBodies   = false
        val touchedFields = FieldSet.Name | FieldSet.Members
        def read(sym: Symbol): Node < (Sync & Abort[ReflectError]) =
            for
                decls    <- sym.declarations
                children <- Kyo.foreach(decls)(instance.read)
            yield Node(name = sym.name, children = children)
    instance
}
```

The `lazy val instance` pattern exactly matches StructureMacro's recursion guard: `StructureMacro` uses `seen: Set[String]` and returns a placeholder when a type name reappears. `ReflectMacro` uses a different strategy — it detects the recursion (when `TypeRepr.of[A].typeSymbol.fullName` appears in the `seen` set during field-type derivation) and emits the `lazy val instance` splice instead of recursing further.

Implementation note: the `seen` set tracks type names encountered during field type dispatch. When a field's type `F` has `F.typeSymbol.fullName` already in `seen`, the macro emits a reference to the enclosing `lazy val instance` for that field's `Reads[F]`, rather than calling `Expr.summon` (which would trigger another macro expansion and infinite loop).

---

## 8. Built-in Instances Inventory

File: `kyo-reflect/shared/src/main/scala/kyo/internal/reflect/reads/ReadsInstances.scala`

Object name `ReadsInstances` (avoids basename collision with the public `Reflect.Reads` trait).

Expected contents (from execution-plan.md lines 477):

```scala
package kyo.internal.reflect.reads

import kyo.*
import kyo.Reflect.*

object ReadsInstances:

    // Reflect types - direct from Symbol record
    given nameReads: Reads[Reflect.Name] = new Reads[Reflect.Name]:
        val symbolKinds   = Set(SymbolKind.values*)
        val needsBodies   = false
        val touchedFields = FieldSet.Name
        def read(sym: Symbol): Reflect.Name < (Sync & Abort[ReflectError]) =
            Kyo.pure(sym.name)

    given flagsReads: Reads[Reflect.Flags] = new Reads[Reflect.Flags]:
        val symbolKinds   = Set(SymbolKind.values*)
        val needsBodies   = false
        val touchedFields = FieldSet.Flags
        def read(sym: Symbol): Reflect.Flags < (Sync & Abort[ReflectError]) =
            Kyo.pure(sym.flags)

    given kindReads: Reads[Reflect.SymbolKind] = new Reads[Reflect.SymbolKind]:
        val symbolKinds   = Set(SymbolKind.values*)
        val needsBodies   = false
        val touchedFields = FieldSet.Kind
        def read(sym: Symbol): Reflect.SymbolKind < (Sync & Abort[ReflectError]) =
            Kyo.pure(sym.kind)

    given typeReads: Reads[Reflect.Type] = new Reads[Reflect.Type]:
        val symbolKinds   = Set(SymbolKind.values*)
        val needsBodies   = false
        val touchedFields = FieldSet.DeclaredType
        def read(sym: Symbol): Reflect.Type < (Sync & Abort[ReflectError]) =
            sym.declaredType

    given symbolReads: Reads[Reflect.Symbol] = new Reads[Reflect.Symbol]:
        val symbolKinds   = Set(SymbolKind.values*)
        val needsBodies   = false
        val touchedFields = FieldSet.Empty
        def read(sym: Symbol): Reflect.Symbol < (Sync & Abort[ReflectError]) =
            Kyo.pure(sym)

    // Primitives
    given booleanReads: Reads[Boolean] = new Reads[Boolean]:
        val symbolKinds   = Set(SymbolKind.values*)
        val needsBodies   = false
        val touchedFields = FieldSet.Empty
        def read(sym: Symbol): Boolean < (Sync & Abort[ReflectError]) =
            Kyo.pure(false)  // no canonical Boolean projection; used for custom given override

    given intReads: Reads[Int] = new Reads[Int]:
        val symbolKinds   = Set(SymbolKind.values*)
        val needsBodies   = false
        val touchedFields = FieldSet.Empty
        def read(sym: Symbol): Int < (Sync & Abort[ReflectError]) =
            Kyo.pure(0)

    given longReads: Reads[Long] = new Reads[Long]:
        val symbolKinds   = Set(SymbolKind.values*)
        val needsBodies   = false
        val touchedFields = FieldSet.Empty
        def read(sym: Symbol): Long < (Sync & Abort[ReflectError]) =
            Kyo.pure(0L)

    given stringReads: Reads[String] = new Reads[String]:
        val symbolKinds   = Set(SymbolKind.values*)
        val needsBodies   = false
        val touchedFields = FieldSet.Name
        def read(sym: Symbol): String < (Sync & Abort[ReflectError]) =
            Kyo.pure(sym.name.asString)

    // Collection wrappers
    given chunkReads[T](using inner: Reads[T]): Reads[Chunk[T]] = new Reads[Chunk[T]]:
        val symbolKinds   = inner.symbolKinds
        val needsBodies   = inner.needsBodies
        val touchedFields = inner.touchedFields | FieldSet.Members
        def read(sym: Symbol): Chunk[T] < (Sync & Abort[ReflectError]) =
            sym.declarations.flatMap(decls =>
                Kyo.foreach(decls.filter(d => inner.symbolKinds.contains(d.kind)))(inner.read)
            )

    given maybeReads[T](using inner: Reads[T]): Reads[Maybe[T]] = new Reads[Maybe[T]]:
        val symbolKinds   = inner.symbolKinds
        val needsBodies   = inner.needsBodies
        val touchedFields = inner.touchedFields | FieldSet.Companion
        def read(sym: Symbol): Maybe[T] < (Sync & Abort[ReflectError]) =
            sym.companion.flatMap:
                case Absent         => Kyo.pure(Absent)
                case Present(csym)  => inner.read(csym).map(Present(_))

    // Tuple Reads (arities 2-22)
    // Pattern shown for arity 2; arities 3-22 follow same pattern:
    given tuple2Reads[A, B](using ra: Reads[A], rb: Reads[B]): Reads[(A, B)] = new Reads[(A, B)]:
        val symbolKinds   = ra.symbolKinds & rb.symbolKinds
        val needsBodies   = ra.needsBodies || rb.needsBodies
        val touchedFields = ra.touchedFields | rb.touchedFields
        def read(sym: Symbol): (A, B) < (Sync & Abort[ReflectError]) =
            for
                a <- ra.read(sym)
                b <- rb.read(sym)
            yield (a, b)

    // ... arities 3-22 follow the same shape

end ReadsInstances
```

These givens must be exported from `Reflect.scala`'s companion or imported via a low-priority implicit scope chain so they're always in scope without explicit import. The recommended approach is placing them in the `Reads` companion object's `given` imports or having `object Reflect` export them.

---

## 9. TouchedFields Helper Design

File: `kyo-reflect/shared/src/main/scala/kyo/internal/reflect/reads/TouchedFields.scala`

Extracted from `ReflectMacro` for testability.

```scala
package kyo.internal.reflect.reads

import kyo.*
import kyo.internal.Trees
import scala.quoted.*

object TouchedFields:

    /** Walk a compiled `read` body Term and collect the set of Reflect.Symbol accessors it
      * calls. Returns the union of FieldSet bits for every `Select(sym, methodName)` where
      * `sym.tpe <:< TypeRepr.of[Reflect.Symbol]`.
      *
      * Hygiene rule 1: fast-path pre-check via Trees.exists.
      * Hygiene rule 2: Match nodes -- skip .pattern, visit scrutinee/guard/rhs only.
      * Transitive: if a Select's qualifier has statically-known touchedFields (i.e., it
      * resolves to a Reads instance val), those bits are also unioned in.
      */
    def analyze(using Quotes)(readBody: quotes.reflect.Term): Reflect.FieldSet =
        import quotes.reflect.*

        // Hygiene rule 1: cheap pre-check
        val hasSymbolSelect = Trees.exists(readBody) {
            case Select(qualifier, _) if qualifier.tpe <:< TypeRepr.of[Reflect.Symbol] => true
        }
        if !hasSymbolSelect then return Reflect.FieldSet.Empty

        var result = Reflect.FieldSet.Empty

        Trees.traverseGoto(readBody) {
            // Hygiene rule 2: Match -- skip .pattern
            case Match(scrutinee, cases) =>
                Trees.Step.goto(scrutinee)
                cases.foreach { c =>
                    c.guard.foreach(Trees.Step.goto)
                    Trees.Step.goto(c.rhs)
                }

            // Symbol accessor collection
            case sel @ Select(qualifier, methodName)
                if qualifier.tpe <:< TypeRepr.of[Reflect.Symbol] =>
                result = result | fieldSetForAccessor(methodName)
                Trees.Step.goto(qualifier)

            // Transitive: Reads[X].touchedFields propagation
            // Detect `readsVal.read(sym)` calls and union in readsVal.touchedFields
            case Apply(Select(readsQual, "read"), _)
                if readsQual.tpe <:< TypeRepr.of[Reflect.Reads[?]] =>
                // readsQual is a statically-known val; eval its touchedFields
                readsQual.asExprOf[Reflect.Reads[?]] match
                    case '{ ($r: Reflect.Reads[t]) } =>
                        // Summon statically to get touchedFields at compile time
                        Expr.summon[Reflect.Reads[t]] match
                            case Some(re) =>
                                // touchedFields is a val, access via reflection on Expr
                                // Actual impl: use '{ $re.touchedFields } and splice-eval
                                () // placeholder; see implementation note below
                            case None => ()
                Trees.Step.goto(readsQual)
        }

        result
    end analyze

    private def fieldSetForAccessor(methodName: String): Reflect.FieldSet =
        methodName match
            case "name" | "fullName"                                             => Reflect.FieldSet.Name
            case "binaryName"                                                    => Reflect.FieldSet.BinaryName
            case "flags" | "isInline" | "isContextual" | "isOpaque" |
                 "isPackageObject" | "isModule" | "isJava"                       => Reflect.FieldSet.Flags
            case "kind"                                                          => Reflect.FieldSet.Kind
            case "owner"                                                         => Reflect.FieldSet.Owner
            case "declaredType"                                                  => Reflect.FieldSet.DeclaredType
            case "parents"                                                       => Reflect.FieldSet.Parents
            case "typeParams"                                                    => Reflect.FieldSet.TypeParams
            case "declarations"                                                  => Reflect.FieldSet.Members
            case "companion"                                                     => Reflect.FieldSet.Companion
            case "javaSpecific"                                                  => Reflect.FieldSet.JavaSpecific
            case _                                                               => Reflect.FieldSet.Empty
    end fieldSetForAccessor

end TouchedFields
```

Implementation note on transitive touchedFields: the `analyze` function walks the generated body. For the generated `read` method, inner `Reads[X]` instances appear as `val innerReads = summon[Reads[X]]` bindings in the generated class body, then `innerReads.read(sym)` calls. The macro can access `innerReads.touchedFields` by reading the statically-known `Expr[Reflect.Reads[X]]` and splicing `'{ $re.touchedFields }` into an `Expr[Reflect.FieldSet]`, which is evaluable at macro expansion time by the compiler. The exact splice-eval mechanism uses `quotes.reflect.Ref` or similar to extract the compile-time constant. Alternatively, the macro tracks which inner `Reads` vals it summoned (at derivation time, not analysis time) and unions their `touchedFields` directly without needing to inspect the generated Term.

### FieldSet operations summary

| Operation | Syntax |
|---|---|
| Union | `a | b` |
| Test containment | `a.contains(b)` -- true if all bits in `b` are set in `a` |
| Empty set | `FieldSet.Empty` |
| All fields | `FieldSet.All` |
| Bit-and (intersection) | Not in current API; use `new FieldSet(a.bits & b.bits)` internally if needed |

---

## 10. ADT Derivation Scope (CRITICAL)

Phase 6 derives ONLY product types (case classes). Sum types require hand-written instances.

### Sum-type guard

When the macro receives a type whose `classSymbol` has `sym.flags.is(Flags.Sealed)` (sealed trait/class) or `sym.flags.is(Flags.Enum)` (Scala 3 enum), it must call `report.errorAndAbort` with this exact message:

```
Reflect.Reads.derived does not support sum types (sealed traits, enums) in v1.
Write a hand-written Reads instance instead. Template:

  given Reflect.Reads[YourSealedTrait] = new Reflect.Reads[YourSealedTrait]:
      val symbolKinds   = Set(SymbolKind.Class, SymbolKind.Trait, SymbolKind.Method)
      val needsBodies   = false
      val touchedFields = FieldSet.Kind
      def read(sym: Symbol): YourSealedTrait < (Sync & Abort[ReflectError]) =
          Kyo.pure(sym.kind match
              case SymbolKind.Class  => /* your class case */
              case SymbolKind.Trait  => /* your trait case */
              case SymbolKind.Method => /* your method case */
              case other             => return Abort.fail(ReflectError.SymbolNotFound(s"unexpected kind: $other"))
          )

See kyo-reflect DESIGN.md section 13 "Worked example: hand-written ADT Reads" for full details.
```

The phrase "hand-written" must appear in the error message (test 9 asserts `contains("hand-written")`).

### Higher-kinded guard

When the type `A` in `derivedImpl[A]` has remaining abstract type parameters at macro expansion (detected by checking `TypeRepr.of[A].typeSymbol.typeParams.exists(p => p.isAbstract)`), emit:

```
Reflect.Reads.derived requires a fully monomorphic type at derivation site.
Abstract type parameter found in: ${TypeRepr.of[A].show}
To handle a polymorphic type, build an explicit factory:
  def fooReads[X](using Reads[X]): Reads[Foo[X]] = new Reads[Foo[X]]: ...
```

### Not-a-named-class guard

When `TypeRepr.of[A].classSymbol` is absent or `NoSymbol`, emit:

```
Reflect.Reads.derived requires a named class symbol; got: ${TypeRepr.of[A].show}
```

---

## 11. Test Enumeration (18 tests in `ReadsDerivationTest.scala`)

File: `kyo-reflect/shared/src/test/scala/kyo/ReadsDerivationTest.scala`

All 18 tests from execution-plan.md lines 494-511:

1. `case class Simple(name: Reflect.Name, flags: Reflect.Flags) derives Reflect.Reads` compiles.

2. Derived `Reads[Simple]` has `touchedFields` containing `FieldSet.Name | FieldSet.Flags` and no other bits.

3. Derived `Reads[Simple].symbolKinds` is `Set(SymbolKind.values*)` (all kinds accepted, only pure accessors used).

4. Derived `Reads[Simple].needsBodies` is `false`.

5. `Reads[Simple].read(sym)` returns a `Simple` with `name == sym.name` and `flags == sym.flags` for a fixture symbol.

6. `case class WithParents(name: Reflect.Name, parents: Chunk[Reflect.Type]) derives Reflect.Reads` compiles and `touchedFields` contains `FieldSet.Name | FieldSet.Parents`.

7. `Reads[WithParents].symbolKinds` is `Set(SymbolKind.Class, SymbolKind.Trait, SymbolKind.Object)` (narrowed because `parents` accessor accessed).

8. `case class Node(name: Reflect.Name, children: Chunk[Node]) derives Reflect.Reads` compiles (recursive case class handled via `lazy val`).

9. Deriving `Reads` on a `sealed trait` produces a compile error containing the phrase "hand-written".

10. Deriving `Reads` on `case class Foo[A](xs: Chunk[A]) derives Reflect.Reads` produces a compile error about abstract type parameter.

11. `case class Custom(special: Int, name: Reflect.Name) derives Reflect.Reads` given `given Reads[Int]` in scope: the derived instance uses the given `Reads[Int]` for the `special` field.

12. `Reads[Chunk[Reflect.Symbol]].read(sym)` (built-in chunk instance) maps over declarations and returns a Chunk of Symbols.

13. `Reads[Maybe[Reflect.Symbol]].read(sym)` returns `Absent` for an unresolved symbol's companion (which returns `Absent`).

14. `Reads[(Reflect.Name, Reflect.Flags)]` tuple reads both fields.

15. Transitive `touchedFields`: `case class Outer(inner: Inner, name: Reflect.Name) derives Reflect.Reads` where `Inner` reads `parents`, `Outer`'s `touchedFields` includes `FieldSet.Parents`.

16. A `Match` node in a hand-written `Reads.read` body containing a `Bind` pattern does not cause macro hygiene assertions to fire (hygiene guard 2 test: skip `.pattern`); additionally assert that the derived `touchedFields` for this `Reads` instance excludes any `FieldSet` bits that appear only in the `Bind` pattern and not in the guard or RHS of the match cases.

17. All built-in `Reads` instances resolve implicitly: `summon[Reads[Reflect.Name]]`, `summon[Reads[Reflect.Flags]]`, `summon[Reads[Reflect.SymbolKind]]`, `summon[Reads[Reflect.Type]]`, `summon[Reads[Reflect.Symbol]]` all compile.

18. `Reads.read` on a real fixture symbol (from `AstUnpicklerTest` fixture) returns the expected product value for a simple two-field case class.

### Verification commands

```
sbt 'project kyo-reflect; testOnly kyo.ReadsDerivationTest'
sbt 'kyo-reflectJS/Test/compile; kyo-reflectNative/Test/compile'
```

---

## 12. Concerns

### C1. `TouchedFields` transitive splice-eval

The cleanest implementation of transitive `touchedFields` collection does NOT require tree-walking the generated body for inner `Reads` calls. Instead, the macro tracks, during field-type dispatch, which `Expr[Reads[X]]` it summoned (in the `derivedImpl` loop over `sym.caseFields`), and unions their `touchedFields` statically using `'{ $re.touchedFields }` splices composed with `'{ $a | $b }`. This avoids the need for the `Apply(Select(readsQual, "read"), _)` pattern in `TouchedFields.analyze`. The tree-walking approach in Section 4/9 is a fallback for hand-written bodies; the macro-generated bodies use the compile-time tracking approach.

### C2. `Reads[Chunk[T]]` built-in semantics

The current plan says `given chunkReads[T](using inner: Reads[T]): Reads[Chunk[T]]` maps over `sym.declarations`. This is a domain assumption: `Chunk[T]` inside a case class derivation always means "read declarations as T". Test 12 exercises this. However, a `case class` field of type `Chunk[Reflect.Type]` (e.g., `parents`) is NOT dispatched through `chunkReads` -- `parents` is a first-class accessor. The derivation macro must check if a field type is `Chunk[Type]` and maps to `sym.parents` rather than `sym.declarations`. The plan (execution-plan.md line 471) delegates per-field dispatch to `Expr.summon[Reads[FieldType]]`; the built-in instances must handle this disambiguation. The `given chunkReads` should NOT be the primary dispatch for `Chunk[Type]` when the field name is `"parents"` -- the macro uses field name matching to pick the correct Symbol accessor, not just the field type. The built-in instances are used for explicit summoning, not for field-type-only dispatch.

### C3. `needsBodies` inference

The plan always emits `needsBodies = false`. This is correct for Phase 6 but must be revisited if Phase 7 adds body-inspecting field types. The constant `false` is hardcoded, not inferred.

### C4. `symbolKinds` narrowing rule is structural-accessor-based

The two-branch rule (plan line 471) says: pure-accessors-only => all kinds; structural-field access => narrow to Class/Trait/Object. The "structural fields" are `parents`, `declarations`, `typeParams`. The macro detects this by checking `touchedFields.contains(FieldSet.Parents | FieldSet.Members | FieldSet.TypeParams)`. Note: `declaredType` (method signatures) is present on `Method` symbols too, so touching `declaredType` alone should NOT narrow to Class/Trait/Object. Only `parents`, `declarations`, and `typeParams` trigger narrowing.

### C5. `ReadsInstances` export path

The built-in instances in `ReadsInstances` must be reachable without explicit import in user code. The established pattern in kyo is low-priority given resolution via companion objects. Since `Reads` is a trait in `object Reflect`, its companion is `Reflect.Reads`. The built-in instances should be in `Reflect.Reads` companion or exported from it. If they live in a separate `ReadsInstances` object, that object must be referenced from the `Reads` companion via `export` or `given` delegation. This is an implementation decision the agent must make; the plan does not specify the export path explicitly.

### C6. `Frame` parameter on resolving accessors

The on-disk `Symbol` resolving accessors have `(using Frame)` parameter (e.g., `def declaredType(using Frame): Type < (Sync & Abort[ReflectError])`). The generated `read` body must propagate `Frame`. Standard practice: the derived `read` method itself takes `(using Frame)` implicitly -- but looking at the current `Reads` trait definition, `read` does NOT have a `(using Frame)` parameter. This means the generated body must use `Frame.internal` or the macro must ensure the `Frame` is synthesized at the call site. Check whether `for/yield` in the generated body creates a new implicit `Frame` context. This is a potential compilation issue that must be resolved during implementation.
