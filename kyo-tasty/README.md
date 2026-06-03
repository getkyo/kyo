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
once they are clear. **Acquiring** a classpath is async, scoped, and can fail:
`Classpath.init(roots)` returns `Classpath < (Async & Scope & Abort[TastyError])`
because the loader decodes files in parallel and registers mmap finalizers.
**Decoding a body** (a method or val implementation) is sync and can fail:
`bodyTree` returns `Maybe[Tree] < (Sync & Abort[TastyError])` because the AST
bytes are parsed on demand. **Everything else** is plain immutable data with no
effect row. `sym.owner`, `cp.findClass(fqn)`, `cls.methods`, `tpe.isSubtypeOf`
all return their result directly. Do not wrap them in `Sync.defer`.

The reading arc is **load -> look up -> navigate -> inspect -> decode**.

```scala
import kyo.*
import kyo.AllowUnsafe.embrace.danger
import kyo.Tasty.*

val program: Chunk[String] < (Async & Scope & Abort[TastyError]) =
    Classpath.init(Seq("target/scala-3.7.4/classes")).map { cp =>
        given Classpath = cp
        cp.allClassLike.map(_.fullNameString)
    }
```

The `given Classpath = cp` line is the price of the implicit-classpath
convention used throughout this module. Cross-symbol references inside the
model travel as `SymbolId` (an opaque `Int` index into `cp.symbols`) rather
than as direct symbol pointers, which sidesteps case-class cycles. Resolving
any of them needs the classpath in scope. This matches the implicit-context
pattern used by `tasty-query` and Scala 3 `Quotes`. Pass it as a `given` once
and chains of accessors compose.

**Running domain.** Every example below queries this sealed family, which we
assume is already compiled and present on the classpath under FQN
`example.Shape`:

```scala doctest:expect=skipped
package example

sealed trait Shape:
    def area: Double

final case class Circle(radius: Double) extends Shape:
    def area: Double = math.Pi * radius * radius

final case class Box(width: Double, height: Double) extends Shape:
    def area: Double = width * height
```

<!-- doctest:setup
```scala
// Type-check stub: every block below sees `cp` and the implicit `Classpath`.
// Acquisition happens through Classpath.init / fromPickles at runtime; the
// stub keeps doctest blocks typed without performing any I/O.
def cp: Classpath = ???
given Classpath   = cp
```
-->

## Acquiring a classpath

You start by pointing `kyo-tasty` at one or more roots and getting back an
immutable snapshot. Roots can be directories of `.class` / `.tasty` files or
`.jar` archives; both are decoded uniformly into the same `Symbol` model. The
acquisition step is the only place where the library does I/O on your behalf,
and the resulting `Classpath` value is what every other API in this README
takes as `using cp: Classpath`.

### Three ways to acquire a Classpath

The acquisition step has three constructors. Pick by where the bytes are coming
from and how often you re-open the same roots:

```scala doctest:expect=skipped
def init(roots: Seq[String]): Classpath < (Async & Scope & Abort[TastyError])
def init(roots: Seq[String], mode: ErrorMode): Classpath < (Async & Scope & Abort[TastyError])
def initCached(roots, cacheDir: String): Classpath < (Sync & Async & Scope & Abort[TastyError])
def fromPickles(pickles: Seq[Pickle]): Classpath < (Async & Scope & Abort[TastyError])
```

`init` is the canonical entry point and runs the parallel per-file decoder.
`initCached` wraps `init` with a binary-snapshot read/write keyed by a digest
of the roots; cold-start latency drops on repeat opens and stays the same on a
miss. `fromPickles` skips the filesystem entirely and builds a classpath from
in-memory `Pickle` byte chunks, which is what tests and embedded uses reach
for. The `Scope` effect on the disk-based forms is what closes JAR pools and
mmap arenas; once the scope exits, the snapshot is closed and accessors will
raise `TastyError.ClasspathClosed`.

Cache maintenance for the `initCached` path is handled outside the init call.
`Snapshot.evictOlderThan(cacheDir, maxAge: Duration)` deletes any `*.krfl` file
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

```scala doctest:expect=skipped
enum ErrorMode:
    case SoftFail // default; per-file errors accumulate in cp.errors
    case FailFast // first decode error aborts the open
```

In `SoftFail` mode (the default) the loader keeps going and the resulting
`Classpath` carries a `Chunk[TastyError]` in `cp.errors`. **If you do not
inspect `cp.errors`, silent data loss is on you.** The classpath is still
useful, but it is an incomplete view of the roots. `FailFast` is the right
choice in CI or test harnesses where you want any decode error to surface.

```scala
import kyo.AllowUnsafe.embrace.danger

Classpath.init(Seq("target/classes"), ErrorMode.FailFast).map { cp =>
    given Classpath = cp
    cp.findClass("example.Circle").map(_.fullNameString)
}
```

## Looking up symbols by name

Once a classpath is open, the most common starting point is a fully-qualified
name. `kyo-tasty` returns those lookups as narrowly-typed `Maybe[Symbol.X]`
where `X` is the kind you asked for, so a `findClass` result lets you reach
straight for `Symbol.Class`-specific accessors without re-narrowing.

### Find vs require

The lookups come in two flavours that mirror each other. `findClass(fqn)`,
`findTrait(fqn)`, `findObject(fqn)`, `findClassLike(fqn)`, `findPackage(fqn)`
all return `Maybe[Symbol.X]`; `Absent` means no symbol of that exact kind at
that FQN. The `require*` variants raise `Abort.fail(TastyError.NotFound)` when
the symbol is missing, which is the right shape when "I expect this to exist"
is a postcondition of your code rather than a question.

```scala
val maybeCircle: Maybe[Symbol.Class]         = cp.findClass("example.Circle")
val circle: Symbol.Class < Abort[TastyError] = cp.requireClass("example.Circle")
```

The narrow naming has a real trap. `findClass("example.Shape")` returns
`Absent` because `Shape` is a `trait`, not a `class`. When the question is
"any class-like at this FQN", reach for `findClassLike`, which returns
`Maybe[Symbol.ClassLike]` and matches a `Class`, `Trait`, or `Object`. For
"any symbol whatsoever", `findSymbol(fqn)` returns `Maybe[Symbol]`. A
companion accessor, `findClassByName(simpleName: String)`, does a linear scan
of the classpath by simple name and returns `Chunk[Symbol.Class]` (because
two unrelated packages can both define `Circle`); `findClassByBinary` accepts
the JVM `com/example/Foo$Inner` form. For the compile-time case where you
have the type statically, `Tasty.classFqn[example.Circle]` is a macro that
expands to the FQN string via the `Tag` machinery.

Within a `ClassLike`, two more lookup primitives sit on the typed Symbol: `findMember(name: String)` (returns the first declared member with that simple name) and `findMemberByName(n: Name)` (the same lookup keyed by an already-interned `Name`). `membersByKind(k: SymbolKind)` filters declarations by the legacy kind enum. These three live on every `Symbol` (not just `ClassLike`) and behave the same as the `findDeclaredMember` / `findInheritedMember` / `findAnyMember` accessors documented further down in "Navigating from a symbol"; pick whichever signature reads cleaner at the call site.

## The Symbol model

Everything you query in `kyo-tasty` is a `Symbol`. Understanding the shape of
this ADT is what lets you write exhaustive, type-safe code over a classpath.

### The sealed hierarchy

`Symbol` is a sealed trait with fourteen final case classes, grouped under
three intermediate sealed traits:

```
Symbol
  TypeLike
    ClassLike    -> Class, Trait, Object
    TypeAlias, OpaqueType, AbstractType, TypeParam
  TermLike       -> Method, Val, Var, Field, Parameter
  Package
  Unresolved
```

Every concrete symbol carries the same five fields: `id: SymbolId`,
`name: Name`, `flags: Flags`, `ownerId: SymbolId`, and
`sourcePosition: Maybe[Position]` (plus `scaladoc: Maybe[String]` and a
`javaMetadata: Maybe[JavaMetadata]` for symbols sourced from `.class` files).
Equality and hashing are by `id` alone; two `Symbol.Class` values with the
same id are the same symbol. This lets you put symbols into a `HashSet`
without surprises, and it lets the model carry cross-references as
`SymbolId` indexes into `cp.symbols`. The trade-off is the implicit
classpath: any accessor that resolves a reference needs `using cp: Classpath`
in scope to dereference the id.

A typed walk over the hierarchy is an exhaustive pattern match:

```scala
def role(sym: Symbol): String = sym match
    case _: Symbol.Class  => "class"
    case _: Symbol.Trait  => "trait"
    case _: Symbol.Object => "object"
    case _: Symbol.Method => "method"
    case _: Symbol.Val    => "val"
    case _: Symbol.Var    => "var"
    case _: Symbol.Field  => "field"
    case _: Symbol.TypeAlias |
        _: Symbol.OpaqueType |
        _: Symbol.AbstractType |
        _: Symbol.TypeParam => "type"
    case _: Symbol.Parameter  => "parameter"
    case _: Symbol.Package    => "package"
    case _: Symbol.Unresolved => "unresolved"
```

`Symbol.Unresolved` is not an error; it is the placeholder for a reference
the loader could not resolve in the current classpath (for instance, a
parent type whose defining JAR is missing). Match it explicitly when you
need to skip such holes. The legacy `SymbolKind` enum (a flat 14-way enum,
exposed via `sym.kind`) is kept for code that prefers a single-match-on-
value style; new code should pattern-match the sealed `Symbol` hierarchy.

## Navigating from a symbol

Once you have a symbol, the rest is navigation: walking owner chains
upward, walking declarations downward, and asking simple questions about
modifiers and naming. None of this allocates effects; it is all pure data
threaded through the implicit classpath.

### Walking up: owners, parents, companions

A symbol's owner chain is the path from itself up to the root package. The
companion is the matching object or class at the same FQN. The first
declared parent of a `Symbol.Class` (the `extends` class) is the head of
`classLike.parents`, not a separate accessor.

```scala doctest:expect=skipped
given Classpath       = cp
val box: Symbol.Class = cp.requireClass("example.Box")

box.owner        // the example package (Symbol.Package)
box.ownersChain  // Chunk(Box, example, <root>)
box.directParent // Maybe[Symbol]  -- the owner symbol (alias of `owner`)
box.parentTypes  // Chunk(Type)       -- raw parent Types (AnyRef and Shape)
box.parents      // Chunk(ClassLike)  -- resolved parent ClassLikes
box.companion    // Maybe[Symbol]     -- the companion object if any
```

For sealed hierarchies, `cls.permittedSubclasses` returns the
`Chunk[Symbol.ClassLike]` declared in the `sealed` parent. Type parameters
of the symbol are `cls.typeParams: Chunk[Symbol.TypeParam]`. Every symbol
also carries `sourcePosition: Maybe[Position]` pointing back into the
original source file when the TASTy file recorded one.

`Parameter` carries `defaultArg(using cp): Maybe[Symbol]` (the synthesized default-argument method when the parameter has one, otherwise `Absent`) plus `isImplicit` / `isByName` / `isRepeated`. `TypeParam` carries `bounds: TypeBounds` and `variance: Variance`, plus a convenience `varianceLabel: String` returning `''` / `'+'` / `'-'` for printing.

### Walking down: declarations and members

The downward walk has two layers. `sym.declaredMembers` returns only what
this symbol directly declares; `sym.allMembers` walks parents and
deduplicates by simple name, so an overridden method appears once. Both
return `Chunk[Symbol]`. The single-symbol lookups mirror the same split:
`findDeclaredMember(name)`, `findInheritedMember(name)`,
`findAnyMember(name)`, each returning `Maybe[Symbol]`.

```scala doctest:expect=skipped
val shape: Symbol.Trait = cp.requireTrait("example.Shape")

shape.declaredMembers            // Chunk(area: Symbol.Method)
shape.findDeclaredMember("area") // Present(Symbol.Method)

val circle: Symbol.Class = cp.requireClass("example.Circle")
circle.allMembers                  // includes Shape.area, AnyRef.toString, etc
circle.findInheritedMember("area") // Present(Shape.area)
```

When you want only one kind, `ClassLike` exposes narrowed accessors that
already filter: `cls.methods: Chunk[Symbol.Method]`, `cls.vals`, `cls.vars`,
`cls.fields`, `cls.nestedTypes`, `cls.typeMembers`. These are pure filters
over `declaredMembers`; reach for `allMembers` if you also want inherited
ones. `constructors(using cp)` returns `Chunk[Method]` of the class's `<init>` methods, separated from the regular `methods` list for ergonomics; an instance constructor and any auxiliary constructors all appear here.

### Names, modifiers, visibility

Every symbol has multiple naming projections, each answering a different
question. `simpleName` is the local name. `fullName: Name` is the dotted
fully-qualified name as a single interned `Name`. `fullNameString` decodes
the same value to a `String` for human display. `binaryName` returns the
JVM internal form (`example/Circle$Inner`). `signature` is a
Scala-source-shaped declaration string (e.g. `def area: Double`,
`class Box[T] extends Shape`), not a JVM erased descriptor. `show` and
`show(format: ShowFormat)` render via `ShowFormat.FullyQualified`,
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
enum with around twenty-three cases; in practice you pattern-match a
handful and let the rest be handled by traversal helpers.

### The Type ADT in practice

The cases you will actually write code against are these:

```scala doctest:expect=skipped
tpe match
    case Type.Named(id)                  => // a class/trait/type alias reference
    case Type.Applied(tycon, args)       => // List[Int], Map[String, Int]
    case Type.Function(params, res, _)   => // (Int, String) => Boolean
    case Type.ContextFunction(params, r) => // (using Ctx) => A
    case Type.Tuple(elems)               => // (Int, String, Boolean)
    case Type.AndType(l, r)              => // A & B
    case Type.OrType(l, r)               => // A | B
    case Type.Annotated(under, ann)      => // T @unchecked
    case _                               => // ConstantType, Refinement, ByName, ...
end match
```

Three sentinel cases stand in for missing or unresolvable bounds:
`Type.Nothing`, `Type.Any`, and `Type.Unknown`. They are first-class
cases on the `Type` enum, not `Type.Named` values: pattern-match them
directly when you need to distinguish "genuinely Any" from a load-time
fallback. `Constant` is the payload type inside `Type.ConstantType` and
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

```scala doctest:expect=skipped
enum SubtypeVerdict:
    case Sub
    case NotSub
    case Unknown
end SubtypeVerdict

val circleTpe: Type = Type.Named(cp.requireClass("example.Circle").id)
val shapeTpe: Type  = Type.Named(cp.requireTrait("example.Shape").id)

circleTpe.isSubtypeOf(shapeTpe) // Sub
shapeTpe.isSubtypeOf(circleTpe) // NotSub
```

`Unknown` happens when the parent chain is not fully loaded into the
current classpath, or when the subtype check exhausts its 64-step budget
on a deeply nested type. Treat it as a distinct outcome: it almost always
means "open the JAR that supplies the missing parent and try again",
not "this is not a subtype".

For walking nested types, `tpe.children`, `tpe.foreach(f)`, and
`tpe.symbol(using cp)` are the traversal primitives (the last returns
`Maybe[Symbol]` for the nominal head of a `Type.Named`, `Absent`
otherwise). `tpe.show(using cp)`
renders a Scala-source-shaped string. The small support enums
`TypeBounds(lo, hi)`, `Variance` (`Invariant`, `Covariant`,
`Contravariant`), and `Visibility` are referenced from various symbol and
type fields; their shapes are obvious enough that they do not get their
own chapter.

## Reading method and value bodies

Bodies are the only part of the model that is not eagerly decoded. Every
`Symbol.Method`, `Symbol.Val`, and `Symbol.Var` carries a
`Maybe[SymbolBody]` byte-slice slot. `SymbolBody` is just the envelope:
the raw AST bytes, the local name table, and an address map. Calling
`bodyTree` parses those bytes into a `Tree` on demand and the result is
memoized per classpath instance keyed by `SymbolId`. **This is the only
post-open accessor that carries `Sync & Abort[TastyError]`**; everything
else on the snapshot is pure.

### Decoding a body and traversing the AST

```scala doctest:expect=skipped
val areaMethod: Symbol.Method = cp.requireClass("example.Circle")
    .findDeclaredMember("area")
    .collect { case m: Symbol.Method => m }
    .getOrThrow

val areaBody: Maybe[Tree] < (Sync & Abort[TastyError]) =
    areaMethod.bodyTree
```

`cp.decodeBody(sym)` is the underlying call; `bodyTree` is the
convenience that threads `using cp`. `Absent` means the symbol has no
recorded body (an abstract method, for instance, or a body the loader
chose not to keep).

`Tree` is a sealed-trait ADT with more than fifty cases (`Apply`,
`Select`, `Ident`, `Literal`, `If`, `Match`, `Block`, `ValDef`,
`DefDef`, ...). You rarely match all of them. The traversal helpers cover
nearly every interactive use:

```scala doctest:expect=skipped
tree.children                       // Chunk[Tree]
tree.foreach(visit: Tree => Unit)
tree.collect[A](pf: PartialFunction[Tree, A]): Chunk[A]
tree.find(p: Tree => Boolean): Maybe[Tree]
tree.foldLeft(z)((acc, t) => ...)
tree.exists(p: Tree => Boolean): Boolean
tree.show(using cp): String         // source-shaped rendering
```

For "find every selection of `math.Pi` inside `Circle.area`", `collect`
with a partial function that matches `Tree.Select(_, name)` is the
idiomatic shape.

Alongside `bodyTree`, `Method` carries a handful of shape accessors that are all pure data reads against the in-memory Symbol: `paramLists: Chunk[Chunk[Parameter]]` (the parameter groups with their resolved Symbols), `returnType: Maybe[Type]` (the result type unwrapped from `Type.Function` when applicable), `isConstructor` (true when the method's simple name is `<init>`), plus `isExtension` / `isInline` / `isGiven` / `isMacro` / `isTailrec`. None take a `Classpath` and none lift into `Sync`.

## Annotations

Scala 3 annotations and Java annotations are different value spaces, and
`kyo-tasty` keeps them as two parallel ADTs rather than papering over the
difference. A Scala-source class carries `Chunk[Annotation]`; a class
loaded from a `.class` file with retention-class annotations carries
`Chunk[JavaAnnotation]`. Some symbols carry both.

### Asking whether a symbol is annotated

The two predicates work uniformly across both sides:

```scala doctest:expect=skipped
given Classpath = cp
val circle      = cp.requireClass("example.Circle")
circle.hasAnnotation("scala.deprecated") // Boolean
circle.getAnnotation("scala.deprecated") // Maybe[Annotation]
```

`hasAnnotation(fqn)` is subtype-aware: it returns true for any annotation
whose annotation class is a subclass of `fqn` (so a `@MyDeprecated extends
scala.deprecated` is caught by querying for `scala.deprecated`).

`Annotation` carries `annotationType: Type` and
`arguments: Chunk[Tree]`. Each entry of `arguments` is one argument tree
in source order, already decoded against the canonical type arena. A
decode failure produces an empty `arguments` chunk and lands a
`TastyError.MalformedSection` in `cp.errors`; the annotation itself is
still present, just without its decoded arguments. Treat
`arguments.isEmpty` as the "no decoded args" signal; there is no separate
`Maybe` wrapper on the slot.

`JavaAnnotation` carries `annotationClass: Symbol` and
`values: Chunk[(Name, JavaAnnotation.Value)]` (an ordered chunk of pairs,
preserving the element order from the classfile attribute).
`JavaAnnotation.Value` is a
sealed ADT whose cases are `StringVal`, `IntVal`, `LongVal`, `FloatVal`,
`DoubleVal`, `BoolVal`, `ClassVal`, `EnumVal`, `ArrayVal`, `AnnotationVal`;
the last two nest recursively (note: the JVM annotation format collapses
`char`, `byte`, and `short` element values into `IntVal`, so there are no
separate `CharVal`, `ByteVal`, or `ShortVal` cases). If your code only
walks `Chunk[Annotation]` and skips `Chunk[JavaAnnotation]`, you will miss
`@SuppressWarnings` from Java sources and a number of Lombok-style
annotations.

### Classpath-wide annotation queries

When the question is "every symbol annotated with X", reach for
`cp.symbolsAnnotatedWith(fqn): Chunk[Symbol]`. This walks both Scala and
Java annotation lists and is subtype-aware on the FQN. It is the only
classpath-wide annotation query you need for the common cases.

## Classpath-wide queries

A loaded classpath is a queryable index, not just a directory of symbols.
The library precomputes a subclass index and a companion index at open
time, so closure queries do not scan.

### Sealed-trait closure and inheritance

For a sealed hierarchy, "list every implementation" is one call:

```scala doctest:expect=skipped
given Classpath         = cp
val shape: Symbol.Trait = cp.requireTrait("example.Shape")

cp.directSubclassesOf(shape) // Chunk(Circle, Box)  -- exactly one hop
cp.subclassesOf(shape)       // Chunk(Circle, Box)  -- transitive closure
cp.implementationsOf(shape)  // Chunk(Circle, Box)  -- concrete classes only
```

`directSubclassesOf` returns only the immediate subclasses;
`subclassesOf` walks the full transitive closure; `implementationsOf`
returns only the concrete (non-abstract, non-trait) leaves. All three are
backed by an inverted index built at open time, so they are O(number of
direct edges) rather than a linear classpath scan.

### Linear scans for everything else

When you need every symbol of a kind, the `all*` accessors do a linear
scan and return a typed `Chunk`:

```scala
cp.allClassLike // Chunk[Symbol.ClassLike] (Class + Trait + Object + EnumCase)
cp.allTraits    // Chunk[Symbol.Trait]
cp.allObjects   // Chunk[Symbol.Object]
cp.allMethods   // Chunk[Symbol.Method]
// ... plus allVals, allVars, allFields, allTypeAliases,
//     allOpaqueTypes, allAbstractTypes, allTypeParams,
//     allParameters, allPackages, allUnresolved.
```

`cp.topLevelClasses` and `cp.packages` are O(1) accessors of pre-built
chunks; they are what you reach for when you want to iterate roots
rather than the full symbol table.

JPMS modules are surfaced through a parallel descriptor index built from
`module-info.class` files. `cp.modules` returns
`Chunk[ModuleDescriptor]`; `cp.findModule(name)` looks one up by name; and
each `ModuleDescriptor` carries `requires`, `exports`, `opens`, `uses`,
and `provides` directives as plain immutable data (`ModuleRequires`,
`ModuleExports`, `ModuleOpens`, `ModuleProvides`). If you do not work
with module-info classpaths, you can ignore this side of the API.

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
- **Lookup errors:** `SymbolNotFound` (the model has the symbol id but
  not the symbol; usually means a stale `SymbolId` from a different
  classpath), `NotFound` (the FQN does not resolve; raised by the
  `require*` lookups).
- **Snapshot errors:** `SnapshotFormatError`, `SnapshotVersionMismatch`,
  `SnapshotIoError`. Raised by the `initCached` path when the cache
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
sorted by FQN. Every accessor below is pure; the only effect comes from
`Classpath.init` itself.

```scala
import kyo.*
import kyo.AllowUnsafe.embrace.danger
import kyo.Tasty.*

def shapeImplementations(roots: Seq[String]): Chunk[String] < (Async & Scope & Abort[TastyError]) =
    Classpath.init(roots).map { cp =>
        given Classpath = cp

        cp.requireTrait("example.Shape").map { shape =>
            cp.implementationsOf(shape)
                .sortBy(_.fullNameString)
                .map { impl =>
                    val overrides = impl.methods
                        .filter(_.isOverride)
                        .map(_.simpleName)
                        .mkString(", ")
                    s"${impl.fullNameString} overrides: [$overrides]"
                }
        }
    }
```

The composition pattern that makes this idiomatic Kyo: `Classpath.init`
once at the boundary, `.map` to project into pure data, and let the
effect row stay where the I/O actually lives. Everything between the open
and the final `.map` is plain data manipulation.
