# kyo-tasty

A runtime reflection library for Scala 3. Given a classpath, `kyo-tasty` loads
`.tasty` and `.class` files, parses them into an immutable in-memory model, and
lets you ask the questions a tool wants to ask about a Scala codebase: what
classes live here, what does this method look like, what implements that trait,
what is annotated with `@deprecated`, is `Foo` a subtype of `Bar`. The result is
plain typed data you can pattern-match against.

This module ships with the Kyo distribution and the `kyo.*` import you will see
in examples is internal effect-plumbing. The library itself is generic Scala 3
reflection; nothing about it is Kyo-specific.

The mental model has three tiers and the rest of this README only makes sense
once they are clear. **Acquiring** a classpath is async and can fail:
`Tasty.withClasspath(roots) { ... }` returns `A < (Async & Abort[TastyError] & S)`
because the loader decodes files in parallel and registers mmap finalizers.
**Decoding a body** (a method or val implementation) is sync and can fail:
`Tasty.bodyTree(sym)` returns `Maybe[Tree] < (Sync & Abort[TastyError])` because
the AST bytes are parsed on demand. **Everything else** is a `Sync`-carrying
query on `object Tasty.*`. `Tasty.owner(sym)`, `Tasty.findClass(fqn)`,
`Tasty.allMethods` all carry `< Sync` to enable the module-level fallback
classpath; do not confuse that with `IO`.

The reading arc is **withClasspath -> look up -> navigate -> inspect -> decode**.

```scala
import kyo.*
import kyo.AllowUnsafe.embrace.danger
import kyo.Tasty.*

val program: Chunk[String] < (Async & Abort[TastyError]) =
    Tasty.withClasspath(Seq("target/scala-3.8.3/classes")):
        Tasty.allClassLike.map(Kyo.foreach(_)(sym => Tasty.fullName(sym)))
```

Cross-symbol references inside the model travel as `SymbolId` (an opaque `Int`
index into `cp.symbols`) rather than as direct symbol pointers, which sidesteps
case-class cycles. Resolving any of them needs the active classpath binding.
Every `Tasty.*` query reads that binding automatically via the module-level
`Local`; the `Tasty.withClasspath` block installs it.

**Running domain.** Every example below queries this sealed family, which we
assume is already compiled and present on the classpath under FQN
`example.Shape`:

```scala
object example:
    sealed trait Shape:
        def area: Double

    final case class Circle(radius: Double) extends Shape:
        def area: Double = math.Pi * radius * radius

    final case class Box(width: Double, height: Double) extends Shape:
        def area: Double = width * height
end example
```

<!-- doctest:setup
```scala
// Type-check stub: every block below is inside a Tasty.withClasspath block.
// Acquisition happens through Tasty.withClasspath / withPickles at runtime; the
// stub keeps doctest blocks typed without performing any I/O.
```
-->

## Acquiring a classpath

You start by pointing `kyo-tasty` at one or more roots and opening a scoped
context. Roots can be directories of `.class` / `.tasty` files or `.jar`
archives; both are decoded uniformly into the same `Symbol` model. The
acquisition step is the only place where the library does I/O on your behalf.
Every query inside the block reads the installed classpath automatically.

### Three ways to open a classpath context

Pick by where the bytes are coming from:

```scala
val roots: Seq[String] = Seq("target/classes")

// From the filesystem (parallel decoder):
Tasty.withClasspath(roots):
    Tasty.allClassLike // Chunk[Symbol.ClassLike] < Sync

// With an on-disk dev cache (digest-keyed; cold-start only on first open):
Tasty.withClasspath(roots, Maybe.Present("/tmp/cache")):
    Tasty.allClassLike

// From in-memory pickles (no filesystem access; tests and embedded uses):
Tasty.withPickles(pickles):
    Tasty.allClassLike

// From a pre-loaded pure-data Classpath (no decode context; serialisation):
Tasty.withClasspath(cp):
    Tasty.allClassLike
```

`withClasspath(roots)` is the canonical entry point and runs the parallel
per-file decoder. Passing `Maybe.Present(cacheDir)` wraps the decode with a
binary-snapshot read/write keyed by a content-hash digest of the roots;
cold-start latency drops on repeat opens and stays the same on a miss.
`withPickles` skips the filesystem entirely and builds a classpath from
in-memory `Pickle` byte chunks. `withClasspath(cp: Classpath)` binds a
pre-loaded value without a decode context (body decoding returns
`Maybe.Absent`). Resource cleanup (JAR pools, mmap arenas) is handled
automatically inside `withClasspath(roots, ...)`.

Cache maintenance is handled outside the open call.
`Tasty.evictOlderThan(cacheDir, maxAge: Duration)` deletes any `*.krfl` file
in `cacheDir` whose modified time is older than `maxAge`. The return type is
`Unit < (Sync & Abort[TastyError])`: the call touches the disk and surfaces I/O
errors through `Abort`, but the list of evicted files is not part of the
contract.

> **Note:** `Tasty.supportedTastyVersion` is `28.8.0`. Pickles whose TASTy version falls outside the supported range produce `TastyError.UnsupportedVersion` rather than a best-effort decode; check this constant if you are loading classfiles from a different Scala 3 toolchain version.

### Soft fail vs fail fast

Real classpaths contain garbage. A stale JAR with a corrupt `.tasty`, a
stripped-down Scala 2 jar without the Scala 3 signatures, a section the
unpickler does not yet support; any of these can derail a load. `ErrorMode`
decides what happens:

```scala
val softFail: ErrorMode = ErrorMode.SoftFail // default; per-file errors accumulate in cp.errors
val failFast: ErrorMode = ErrorMode.FailFast // first decode error aborts the open
```

In `SoftFail` mode (the default) the loader keeps going and the resulting
`Classpath` carries a `Chunk[TastyError]` in its `errors` field. **If you do
not inspect `cp.errors`, silent data loss is on you.** The classpath is still
useful, but it is an incomplete view of the roots. `FailFast` is the right
choice in CI or test harnesses where you want any decode error to surface.

```scala
import kyo.AllowUnsafe.embrace.danger

Tasty.withClasspath(Seq("target/classes")):
    Tasty.classpath.map { cp =>
        if cp.errors.nonEmpty then Abort.fail(cp.errors.head)
        else Tasty.findClass("example.Circle").map(Tasty.fullName)
    }
```

## Looking up symbols by name

Inside a `withClasspath` block, the most common starting point is a
fully-qualified name. `kyo-tasty` returns those lookups as narrowly-typed
`Maybe[Symbol.X] < Sync` where `X` is the kind you asked for, so a `findClass`
result lets you reach straight for `Symbol.Class`-specific fields without
re-narrowing.

### Find vs require

The lookups come in two flavours that mirror each other. `Tasty.findClass(fqn)`,
`Tasty.findObject(fqn)`, `Tasty.findClassLike(fqn)`,
`Tasty.findPackage(fqn)` all return `Maybe[Symbol.X] < Sync`; `Absent` means no
symbol of that exact kind at that FQN. The `require*` variants raise
`Abort.fail(TastyError.NotFound)` when the symbol is missing, which is the right
shape when "I expect this to exist" is a postcondition of your code rather than
a question.

```scala
val maybeCircle: Maybe[Symbol.Class] < Sync           = Tasty.findClass("example.Circle")
val circle: Symbol.Class < (Sync & Abort[TastyError]) = Tasty.requireClass("example.Circle")
```

The narrow naming has a real trap. `findClass("example.Shape")` returns
`Absent` because `Shape` is a `trait`, not a `class`. When the question is
"any class-like at this FQN", reach for `Tasty.findClassLike`, which returns
`Maybe[Symbol.ClassLike] < Sync` and matches a `Class`, `Trait`, or `Object`. For
"any symbol whatsoever", `Tasty.findSymbol(fqn)` returns `Maybe[Symbol] < Sync`.
When you need to restrict the result to a concrete, instantiable class (skipping
sealed abstract bases like `scala.Option`), reach for `Tasty.findConcreteClass(fqn)`;
it behaves like `findClass` but filters out the `Abstract` flag. A companion
accessor, `Tasty.findClassesByName(simpleName: String)`, uses a prebuilt index
keyed on simple name and returns `Chunk[Symbol.Class] < Sync` (because two
unrelated packages can both define `Circle`); `Tasty.findClassByBinary` accepts
the JVM `com/example/Foo$Inner` form. For the compile-time case where you have
the type statically, `Tasty.classFqn[example.Circle]` is a macro that expands to
the FQN string via the `Tag` machinery.

Within a `ClassLike`, member lookup is done via `Tasty.findMember(sym, name)`,
which checks declared members first and then inherited ones. For scope-specific
lookup, use `Tasty.members(sym, scope)` with `MemberScope.Declared`,
`MemberScope.Inherited`, or `MemberScope.All`.

## The Symbol model

Everything you query in `kyo-tasty` is a `Symbol`. Understanding the shape of
this ADT is what lets you write exhaustive, type-safe code over a classpath.

### The sealed hierarchy

`Symbol` is a sealed trait with thirteen concrete subtypes. The only intermediate
sealed trait is `ClassLike`:

```
Symbol
  ClassLike  -> Class, Trait, Object, EnumCase
  TypeAlias, OpaqueType, AbstractType, TypeParam
  Method, Val, Var, Field, Parameter
  Package
```

`Symbol.EnumCase` is a peer of `Symbol.Class` under `Symbol.ClassLike`; a
`Symbol.EnumCase` does NOT match a `Symbol.Class` pattern. Put an explicit
`EnumCase` arm before `Class` when you need to specialize Scala 3 enum cases.

Every concrete symbol is a pure case class that carries data only. The common
fields are: `id: SymbolId`, `name: Name`, `flags: Flags`,
`ownerId: SymbolId`. Subtype-specific fields (parent IDs, member IDs, param
IDs, type IDs) vary by case. Symbols derive `Schema` and `CanEqual`; they
are serialisable and equality-comparable out of the box.

A typed walk over the hierarchy is an exhaustive pattern match:

```scala
def role(sym: Symbol): String = sym match
    case _: Symbol.Class     => "class"
    case _: Symbol.EnumCase  => "enum case"
    case _: Symbol.Trait     => "trait"
    case _: Symbol.Object    => "object"
    case _: Symbol.Method    => "method"
    case _: Symbol.Val       => "val"
    case _: Symbol.Var       => "var"
    case _: Symbol.Field     => "field"
    case _: Symbol.TypeAlias |
        _: Symbol.OpaqueType |
        _: Symbol.AbstractType |
        _: Symbol.TypeParam => "type"
    case _: Symbol.Parameter  => "parameter"
    case _: Symbol.Package    => "package"
```

## Navigating from a symbol

Once you have a symbol, the rest is navigation: walking owner chains
upward, walking declarations downward, and asking simple questions about
modifiers and naming. All navigation goes through `object Tasty.*` free
functions that read the current classpath binding automatically.

### Walking up: owners, parents, companions

A symbol's owner chain is the path from itself up to the root package. The
companion is the matching object or class at the same FQN. The first
declared parent of a `Symbol.Class` (the `extends` class) is the head of
`Tasty.parents(classLike)`.

```scala
Tasty.withClasspath(roots):
    Tasty.findClass("example.Box").map { mbBox =>
        mbBox.foreach { b =>
            Tasty.owner(b)        // Maybe[Symbol] < Sync -- the example package, Absent at root
            Tasty.ownersChain(b)  // Chunk[Symbol] < Sync
            Tasty.parents(b)      // Chunk[Symbol.ClassLike] < Sync
            Tasty.companion(b)    // Maybe[Symbol] < Sync
        }
    }
```

For sealed hierarchies, `Tasty.permittedSubclasses(cls)` returns the
`Chunk[Symbol.ClassLike] < Sync` declared in the `sealed` parent. Type
parameters of the symbol are `Tasty.typeParams(sym): Chunk[Symbol.TypeParam] < Sync`.
Every symbol also carries a `sourcePosition: Maybe[Position]` field pointing
back into the original source file when the TASTy file recorded one.

`Parameter` carries `defaultArgId: Maybe[SymbolId]` (the synthesized
default-argument method when the parameter has one, otherwise `Absent`),
`declaredType: Maybe[Type]` (the parameter's declared type, wrapped in
`Type.ByName` for by-name parameters and `Type.Repeated` for varargs), and
`annotations: Chunk[Annotation]`. Implicit/given parameters have `Flag.Given`
set in `flags`. `TypeParam` carries `variance: Variance` (one of `Invariant`,
`Covariant`, `Contravariant`) and `bounds: TypeBounds`.

### Walking down: declarations and members

Member lookup goes through `Tasty.members(sym)` and `Tasty.findMember(sym, name)`.

```scala
Tasty.withClasspath(roots):
    Tasty.findClassLike("example.Shape").map { mbShape =>
        mbShape.foreach { shape =>
            Tasty.members(shape, MemberScope.Declared) // Chunk[Symbol] < Sync
            Tasty.findMember(shape, "area")            // Maybe[Symbol] < Sync
        }
    }
```

`MemberScope.Declared` returns only what this symbol directly declares;
`MemberScope.Inherited` walks parents and returns inherited members;
`MemberScope.All` gives the deduplicated union (most-specific per simple name).
The default scope is `Declared`.

For typed aggregation, `Tasty.allMethods`, `Tasty.allVals`, `Tasty.allVars`,
`Tasty.allFields` each return the classpath-wide `Chunk` of that symbol kind.

### Names, modifiers, visibility

Every symbol has multiple naming projections, each answering a different
question. `sym.name.asString` is the local name. `Tasty.fullName(sym)` returns
the dotted fully-qualified name as a `String`. `Tasty.binaryName(sym)` returns
the JVM internal form (`example/Circle$Inner`). `Tasty.signature(sym)` is a
Scala-source-shaped declaration string (e.g. `def area: Double`,
`class Box[T] extends Shape`), not a JVM erased descriptor. `Tasty.show(sym)`
and `Tasty.show(sym, format: ShowFormat)` render via `ShowFormat.FullyQualified`,
`Simple`, or `Code`; `Code` delegates to `signature`.

Modifiers live in `sym.flags: Flags`, a packed bitfield. The most-used
predicates have direct getters: `isFinal`, `isAbstract`, `isCase`,
`isSealed`, `isPrivate`, `isProtected`, `isInline`, `isImplicit`,
`isGiven`, `isOverride`, `isLazy`, `isExtension`. Around forty such
predicates exist; for the rest, `sym.flags.contains(Flag.X)` reaches the
underlying flag set. Visibility is computed from those flags into a small
`Visibility` enum (`Public`, `Private`, `Protected`, etc.), and class-level
openness lands in `OpenLevel` (`Final`, `Sealed`, `Open`, `Default`).

## Inspecting types

Types in `kyo-tasty` are a separate ADT from symbols. `Type` is a sealed
enum with around twenty-six cases; in practice you pattern-match a
handful and let the rest be handled by traversal helpers.

### The Type ADT in practice

The cases you will actually write code against are these:

```scala
def classify(tpe: Type): String = tpe match
    case Type.Named(id)                  => "a class/trait/type alias reference"
    case Type.Applied(tycon, args)       => "List[Int], Map[String, Int]"
    case Type.Function(params, res)      => "(Int, String) => Boolean"
    case Type.ContextFunction(params, r) => "(using Ctx) => A"
    case Type.Tuple(elems)               => "(Int, String, Boolean)"
    case Type.AndType(l, r)              => "A & B"
    case Type.OrType(l, r)               => "A | B"
    case Type.Annotated(under, ann)      => "T @unchecked"
    case _                               => "ConstantType, Refinement, ByName, ..."
```

Two sentinel cases stand in for missing or unresolvable bounds: `Type.Nothing`
and `Type.Any`. They are first-class cases on the `Type` enum, not
`Type.Named` values: pattern-match them directly when you need to distinguish
"genuinely Any" from a load-time fallback. Fields whose type information could
not be resolved (e.g. `TypeAlias.body`, `OpaqueType.body`, `Parameter.declaredType`)
use `Maybe.Absent` rather than a sentinel case.

`Constant` is the payload type inside `Type.ConstantType` and
`Tree.Literal`. Its cases use the `Const` suffix: `StringConst`,
`IntConst`, `LongConst`, `FloatConst`, `DoubleConst`, `BooleanConst`,
`CharConst`, `ByteConst`, `ShortConst`, `UnitConst`, `NullConst`,
`ClassConst`. (The `*Constant` names belong to Scala 3's
`quoted.reflect.Constant`, not to `Tasty.Constant`; do not confuse the
two.) Literal values are not their own `Type` cases.

### Subtype checking and the three-way verdict

The subtype check threads the implicit classpath and returns a three-way
verdict rather than a `Boolean`. The third case is the typed "I could not
decide":

```scala
val sub:           SubtypeVerdict = SubtypeVerdict.Sub
val notSub:        SubtypeVerdict = SubtypeVerdict.NotSub
val indeterminate: SubtypeVerdict = SubtypeVerdict.Indeterminate

Tasty.withClasspath(roots):
    Tasty.findClass("example.Circle").flatMap { mbCircle =>
        Tasty.findClassLike("example.Shape").map { mbShape =>
            for
                circle <- mbCircle
                shape  <- mbShape
            yield
                val circleTpe: Type    = Type.Named(circle.id)
                val shapeTpe: Type     = Type.Named(shape.id)
                val v1: SubtypeVerdict < Sync = Tasty.isSubtypeOf(circleTpe, shapeTpe) // Sub
                val v2: SubtypeVerdict < Sync = Tasty.isSubtypeOf(shapeTpe, circleTpe) // NotSub
                (v1, v2)
        }
    }
```

`Indeterminate` happens when the parent chain is not fully loaded into the
current classpath, or when the subtype check exhausts its 64-step budget
on a deeply nested type. Treat it as a distinct outcome: it almost always
means "open the JAR that supplies the missing parent and try again",
not "this is not a subtype". Unhandled type shapes during the check also
accumulate in `cp.errors` as `TastyError.UnhandledSubtypingCase`.

For walking nested types, `tpe.children`, `tpe.collect`, `tpe.find`,
`tpe.foldLeft`, and `tpe.exists` are the pure traversal primitives.
`Tasty.typeSymbol(tpe)` returns `Maybe[Symbol] < Sync` for the nominal head
of a `Type.Named`, `Absent` otherwise. `Tasty.typeShow(tpe)` renders a
Scala-source-shaped string.

The small support enums `TypeBounds(lo, hi)`, `Variance` (`Invariant`,
`Covariant`, `Contravariant`), and `Visibility` are referenced from various
symbol and type fields; their shapes are obvious enough that they do not
get their own chapter.

## Reading method and value bodies

Bodies are the only part of the model that is not eagerly decoded. Calling
`Tasty.bodyTree(sym)` parses the stored AST bytes into a `Tree` on demand;
the result is memoized per classpath instance keyed by `SymbolId`. **This is
the only post-open accessor that carries `Sync & Abort[TastyError]`**;
everything else on the snapshot is pure.

### Decoding a body and traversing the AST

```scala
Tasty.withClasspath(roots):
    Tasty.findClass("example.Circle").map { mbCircle =>
        mbCircle.foreach { circle =>
            Tasty.findMember(circle, "area").map { mbArea =>
                mbArea.collect { case m: Symbol.Method => m }.foreach { m =>
                    Tasty.bodyTree(m) // Maybe[Tree] < (Sync & Abort[TastyError])
                }
            }
        }
    }
```

`Absent` means the symbol has no recorded body (an abstract method, for
instance, or a body the loader chose not to keep). Under
`withClasspath(cp: Classpath)` (no decode context), `bodyTree` always returns
`Absent`.

`Tree` is a sealed-trait ADT with more than fifty cases (`Apply`,
`Select`, `Ident`, `Literal`, `If`, `Match`, `Block`, `ValDef`,
`DefDef`, ...). You rarely match all of them. The traversal helpers cover
nearly every interactive use:

```scala
def walk(tree: Tree): Unit =
    val a: Chunk[Tree] = tree.children
    tree.foreach(t => ())
    val b: Chunk[String] = tree.collect { case lit: Tree.Literal => lit.toString }
    val c: Maybe[Tree]   = tree.find(_ => true)
    val d: Int           = tree.foldLeft(0)((acc, _) => acc + 1)
    val e: Boolean       = tree.exists(_ => false)
    val f: String        = tree.show
    ()
end walk
```

For "find every selection of `math.Pi` inside `Circle.area`", `collect`
with a partial function that matches `Tree.Select(_, name)` is the
idiomatic shape.

Alongside `bodyTree`, `Symbol.Method` carries a handful of pure shape fields:
`paramListIds: Chunk[Chunk[SymbolId]]` (the parameter groups),
`declaredType: Maybe[Type]`, plus flag predicates `isExtension` /
`isInline` / `isGiven` / `isMacro` / `isTailrec`. None carry a `Sync`
effect; they are straight case-class field reads.

## Annotations

Scala 3 annotations and Java annotations are different value spaces, and
`kyo-tasty` keeps them as two parallel ADTs rather than papering over the
difference. A Scala-source class carries `Chunk[Annotation]`; a class
loaded from a `.class` file with retention-class annotations carries
`Chunk[Java.Annotation]`. Some symbols carry both.

### Asking whether a symbol is annotated

The two predicates work uniformly across both sides:

```scala
Tasty.withClasspath(roots):
    Tasty.findClass("example.Circle").map { mbCircle =>
        mbCircle.foreach { circle =>
            Tasty.hasAnnotation(circle, "scala.deprecated")         // Boolean < Sync
            Tasty.findAnnotation(circle, "scala.deprecated")        // Maybe[Annotation | Java.Annotation] < Sync
        }
    }
```

`Tasty.hasAnnotation(sym, fqn)` is subtype-aware: it returns true for any
annotation whose annotation class is a subclass of `fqn` (so a
`@MyDeprecated extends scala.deprecated` is caught by querying for
`scala.deprecated`).

`Annotation` carries `annotationType: Type` and
`arguments: Chunk[Tree]`. Each entry of `arguments` is one argument tree
in source order, already decoded against the canonical type arena. A
decode failure produces an empty `arguments` chunk and lands a
`TastyError.MalformedSection` in `cp.errors`; the annotation itself is
still present, just without its decoded arguments. Treat
`arguments.isEmpty` as the "no decoded args" signal; there is no separate
`Maybe` wrapper on the slot.

`Tasty.Java.Annotation` carries `annotationClass: Symbol` and
`values: Chunk[(Name, Java.Annotation.Value)]` (an ordered chunk of pairs,
preserving the element order from the classfile attribute).
`Java.Annotation.Value` is a sealed ADT whose cases are `StringVal`,
`IntVal`, `LongVal`, `FloatVal`, `DoubleVal`, `BoolVal`, `ClassVal`,
`EnumVal`, `ArrayVal`, `AnnotationVal`; the last two nest recursively
(note: the JVM annotation format collapses `char`, `byte`, and `short`
element values into `IntVal`, so there are no separate `CharVal`, `ByteVal`,
or `ShortVal` cases). If your code only walks `Chunk[Annotation]` and skips
`Chunk[Java.Annotation]`, you will miss `@SuppressWarnings` from Java sources
and a number of Lombok-style annotations.

### Classpath-wide annotation queries

When the question is "every symbol annotated with X", reach for
`Tasty.symbolsAnnotatedWith(fqn): Chunk[Symbol] < Sync`. This walks both
Scala and Java annotation lists and is subtype-aware on the FQN. It is the
only classpath-wide annotation query you need for the common cases.

## Classpath-wide queries

A loaded classpath is a queryable index, not just a directory of symbols.
The library precomputes a subclass index and a companion index at open
time, so closure queries do not scan.

### Sealed-trait closure and inheritance

For a sealed hierarchy, "list every implementation" is one call. These
methods live on `Classpath`, not on `object Tasty` directly, so you access
them through `Tasty.classpath`:

```scala
Tasty.withClasspath(roots):
    Tasty.classpath.flatMap { cp =>
        Tasty.findClassLike("example.Shape").map { mbShape =>
            mbShape match
                case Maybe.Present(shape) =>
                    cp.directSubclassesOf(shape) // Chunk[Symbol.ClassLike]
                    cp.subclassesOf(shape)       // Chunk[Symbol.ClassLike]
                    cp.implementationsOf(shape)  // Chunk[Symbol.Class]
                case Maybe.Absent => Chunk.empty
        }
    }
```

`cp.directSubclassesOf` returns only the immediate subclasses;
`cp.subclassesOf` walks the full transitive closure;
`cp.implementationsOf` returns only the concrete (non-abstract,
non-trait) leaves. All three are backed by an inverted index built at open
time, so they are O(number of direct edges) rather than a linear classpath
scan.

### Linear scans for everything else

When you need every symbol of a kind, the `all*` accessors do a linear
scan and return a typed `Chunk`:

```scala
Tasty.allClassLike // Chunk[Symbol.ClassLike] < Sync (Class + Trait + Object + EnumCase)
Tasty.allTraits    // Chunk[Symbol.Trait] < Sync
Tasty.allObjects   // Chunk[Symbol.Object] < Sync
Tasty.allMethods   // Chunk[Symbol.Method] < Sync
// ... plus allVals, allVars, allFields, allTypeAliases,
//     allOpaqueTypes, allAbstractTypes, allTypeParams,
//     allParameters, allPackages.
```

`Tasty.classpath` returns the active `Classpath` value directly.
`cp.topLevelClasses` and `cp.packages` are O(1) accessors of pre-built
chunks; they are what you reach for when you want to iterate roots
rather than the full symbol table.

JPMS modules are surfaced through a parallel descriptor index built from
`module-info.class` files. `Tasty.classpath.map(_.modules)` returns
`Chunk[Tasty.Java.Module.Descriptor]`; `Tasty.findModule(name)` looks one
up by name; and each `Tasty.Java.Module.Descriptor` carries `requires`,
`exports`, `opens`, `uses`, and `provides` directives as plain immutable
data (`Tasty.Java.Module.Requires`, `Tasty.Java.Module.Exports`,
`Tasty.Java.Module.Opens`, `Tasty.Java.Module.Provides`). If you do not
work with module-info classpaths, you can ignore this side of the API.

## Errors

`kyo-tasty` never throws across its API boundary. Every failure path is
`Abort.fail(TastyError.X)`, and `TastyError` is a closed enum you can
exhaustively match on.

The variants group naturally by source:

- **File-level decode errors:** `FileNotFound`, `CorruptedFile`,
  `UnsupportedVersion`, `MalformedSection`, `ClassfileFormatError`,
  `InconsistentClasspath` (the classpath's UUID metadata disagrees across
  decode passes, typically a partial classfile rewrite caught mid-load).
  In `SoftFail` mode these accumulate into `cp.errors`; in `FailFast` they
  abort the open.
- **Symbol resolution errors:** `UnresolvedReference` (a cross-file
  reference the loader could not resolve), `UnknownType` (a declared type
  that could not be resolved), `MissingDeclaredType` (a symbol whose
  declared type is absent under `FailFast` mode).
- **Lookup errors:** `SymbolNotFound` (the model has the symbol id but
  not the symbol; usually means a stale `SymbolId` from a different
  classpath), `NotFound` (the FQN does not resolve; raised by the
  `require*` lookups).
- **Subtype checking:** `UnhandledSubtypingCase` (a type shape the checker
  does not handle; verdict is `Indeterminate`; also accumulated in `cp.errors`).
- **Snapshot errors:** `SnapshotFormatError`, `SnapshotVersionMismatch`,
  `SnapshotIoError`. Raised by the cached-open path when the cache
  file is corrupt or written by a different `kyo-tasty` version.
- **Lifecycle errors:** `ClasspathClosed` (you used the classpath after
  its scope exited), `ClasspathBuilding` (you tried to query a classpath
  that is still being constructed; only reachable from internals).
- **Reserved:** `NotImplemented` is the marker for sections the
  unpickler does not yet understand; it carries a description of the
  feature for bug reports.

## Putting it together

A realistic use is: given a sealed trait, list every concrete
implementation together with the names of any methods it overrides,
sorted by FQN. Every accessor below is a `< Sync` query; the only
`Async` effect comes from `Tasty.withClasspath(roots)`.

```scala
import kyo.*
import kyo.AllowUnsafe.embrace.danger
import kyo.Tasty.*

def shapeImplementations(roots: Seq[String]): Chunk[String] < (Async & Abort[TastyError]) =
    Tasty.withClasspath(roots):
        Tasty.classpath.flatMap { cp =>
            Tasty.requireClassLike("example.Shape").map { shape =>
                val impls  = cp.implementationsOf(shape)
                val sorted = impls.sortBy(_.name.asString)
                Kyo.foreach(sorted) { impl =>
                    Tasty.fullName(impl).map { fqn =>
                        Tasty.members(impl, MemberScope.Declared).map { members =>
                            val overrides = members
                                .collect { case m: Symbol.Method if m.flags.contains(Flag.Override) => m }
                                .map(_.name.asString)
                                .mkString(", ")
                            s"$fqn overrides: [$overrides]"
                        }
                    }
                }
            }
        }
```

The composition pattern that makes this idiomatic Kyo: `Tasty.withClasspath`
once at the boundary, then chain `Tasty.*` queries inside the block where the
binding is active. Every query carries `< Sync` because the module-level
fallback may trigger a lazy classpath init on first call; the actual I/O is
confined to the `withClasspath` open. `ErrorMode.FailFast` raises the first
decode error through `Abort`; the soft-fail default would instead accumulate
them into `cp.errors`, where `cp.errors.headOption` is the minimum check
before trusting any query result.
