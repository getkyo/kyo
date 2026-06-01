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
import kyo.Tasty.*

val program: Chunk[String] < (Async & Scope & Abort[TastyError]) =
    Classpath.init(Seq("target/scala-3.7.4/classes")).map { cp =>
        given Classpath = cp
        cp.allClasses.map(_.fullNameString)
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

```scala
package example

sealed trait Shape:
    def area: Double

final case class Circle(radius: Double) extends Shape:
    def area: Double = math.Pi * radius * radius

final case class Box(width: Double, height: Double) extends Shape:
    def area: Double = width * height
```

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

```scala
def init(roots: Seq[String]): Classpath < (Async & Scope & Abort[TastyError])
def init(roots: Seq[String], mode: ErrorMode): Classpath < (Async & Scope & Abort[TastyError])
def initCached(roots, cacheDir: String): Classpath < (Sync & Async & Scope & Abort[TastyError])
def fromPickles(pickles: Seq[Pickle]): Classpath < Sync
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
`Snapshot.evictOlderThan(cacheDir, maxAge: Duration)` deletes snapshot files
older than `maxAge` and returns a `Chunk[String]` of evicted filenames; it
carries `Sync & Abort[TastyError]` because it actually touches the disk.

> **Note:** `Tasty.supportedTastyVersion` is `28.8.0`. Pickles whose TASTy version falls outside the supported range produce `TastyError.UnsupportedVersion` rather than a best-effort decode; check this constant if you are loading classfiles from a different Scala 3 toolchain version.

### Soft fail vs fail fast

Real classpaths contain garbage. A stale JAR with a corrupt `.tasty`, a
stripped-down Scala 2 jar without the Scala 3 signatures, a section the
unpickler does not yet support; any of these can derail a load. `ErrorMode`
decides what happens:

```scala
enum ErrorMode:
    case SoftFail   // default; per-file errors accumulate in cp.errors
    case FailFast   // first decode error aborts the open
```

In `SoftFail` mode (the default) the loader keeps going and the resulting
`Classpath` carries a `Chunk[TastyError]` in `cp.errors`. **If you do not
inspect `cp.errors`, silent data loss is on you.** The classpath is still
useful, but it is an incomplete view of the roots. `FailFast` is the right
choice in CI or test harnesses where you want any decode error to surface.

```scala
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
given Classpath = cp
val maybeCircle: Maybe[Symbol.Class]            = cp.findClass("example.Circle")
val circle: Symbol.Class < Abort[TastyError]    = cp.requireClass("example.Circle")
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
    case _: Symbol.Class       => "class"
    case _: Symbol.Trait       => "trait"
    case _: Symbol.Object      => "object"
    case _: Symbol.Method      => "method"
    case _: Symbol.Val         => "val"
    case _: Symbol.Var         => "var"
    case _: Symbol.Field       => "field"
    case _: Symbol.TypeAlias |
         _: Symbol.OpaqueType |
         _: Symbol.AbstractType |
         _: Symbol.TypeParam   => "type"
    case _: Symbol.Parameter   => "parameter"
    case _: Symbol.Package     => "package"
    case _: Symbol.Unresolved  => "unresolved"
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
direct parent is the first declared parent (for a `Symbol.Class`, the
`extends` class; for any classlike that explicitly extends nothing, the
`Symbol.Class` for `scala.AnyRef`). The companion is the matching object or
class at the same FQN.

```scala
given Classpath = cp
val box: Symbol.Class = cp.requireClass("example.Box")

box.owner            // the example package (Symbol.Package)
box.ownersChain      // Chunk(Box, example, <root>)
box.directParent     // AnyRef (a Symbol.Class)
box.parents          // Chunk(AnyRef, Shape)  -- includes the trait
box.companion        // Maybe[Symbol]  -- the companion object if any
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

```scala
val shape: Symbol.Trait = cp.requireTrait("example.Shape")

shape.declaredMembers           // Chunk(area: Symbol.Method)
shape.findDeclaredMember("area") // Present(Symbol.Method)

val circle: Symbol.Class = cp.requireClass("example.Circle")
circle.allMembers               // includes Shape.area, AnyRef.toString, etc
circle.findInheritedMember("area") // Present(Shape.area)
```

When you want only one kind, `ClassLike` exposes narrowed accessors that
already filter: `cls.methods: Chunk[Symbol.Method]`, `cls.vals`, `cls.vars`,
`cls.fields`, `cls.nestedTypes`, `cls.typeMembers`. These are pure filters
over `declaredMembers`; reach for `allMembers` if you also want inherited
ones. `constructors(using cp)` returns `Chunk[Method]` of the class's `<init>` methods, separated from the regular `methods` list for ergonomics; an instance constructor and any auxiliary constructors all appear here.

### Names, modifiers, visibility

Every symbol has multiple naming projections, each answering a different
question. `simpleName` is the local name. `fullName: Chunk[Name]` is the
owner chain rendered as names. `fullNameString` joins it with `.` for
human display. `binaryName` returns the JVM internal form
(`example/Circle$Inner`). `signature` is the erased JVM descriptor for
methods. `show` and `show(format: ShowFormat)` render a Scala-source-shaped
string with configurable depth.

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

```scala
tpe match
    case Type.Named(id)             => // a class/trait/type alias reference
    case Type.Applied(tycon, args)  => // List[Int], Map[String, Int]
    case Type.Function(params, res) => // (Int, String) => Boolean
    case Type.Tuple(elems)          => // (Int, String, Boolean)
    case Type.AndType(l, r)         => // A & B
    case Type.OrType(l, r)          => // A | B
    case Type.Annotated(under, ann) => // T @unchecked
    case _                          => // ConstantType, RefinedType, ByName, ...
```

Three sentinel values stand in for missing or unresolvable bounds:
`Type.Nothing`, `Type.Any`, and `Type.Unknown`. They are `Type.Named`
values with reserved negative `SymbolId`s (`-100`, `-101`, `-102`), so a
pattern match on `Type.Named` will catch them. Compare against the
sentinels by reference (`tpe eq Type.Unknown`) when you need to distinguish
"genuinely Any" from a load-time fallback. `Constant` is the payload type inside `Type.ConstantType` and `Tree.Literal`. Its cases use the `Const` suffix: `StringConst`, `IntConst`, `LongConst`, `FloatConst`, `DoubleConst`, `BooleanConst`, `CharConst`, `ByteConst`, `ShortConst`, `UnitConst`, `NullConst`, `ClassConst`. (The `*Constant` names belong to Scala 3's `quoted.reflect.Constant`, not to `Tasty.Constant`; do not confuse the two.) Literal values are not their own `Type` cases.

### Subtype checking and the three-way verdict

The subtype check threads the implicit classpath and returns a three-way
verdict rather than a `Boolean`. The third case is the typed "I could not
decide":

```scala
enum SubtypeVerdict:
    case Sub
    case NotSub
    case Unknown

val circleTpe: Type = Type.Named(cp.requireClass("example.Circle").id)
val shapeTpe: Type  = Type.Named(cp.requireTrait("example.Shape").id)

circleTpe.isSubtypeOf(shapeTpe)  // Sub
shapeTpe.isSubtypeOf(circleTpe)  // NotSub
```

`Unknown` happens when the parent chain is not fully loaded into the
current classpath, or when the subtype check exhausts its 64-step budget
on a deeply nested type. Treat it as a distinct outcome: it almost always
means "open the JAR that supplies the missing parent and try again",
not "this is not a subtype".

For walking nested types, `tpe.children`, `tpe.foreach(f)`, and
`tpe.symbolMaybe` are the traversal primitives. `tpe.show(using cp)`
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

```scala
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

```scala
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

```scala
given Classpath = cp
val circle = cp.requireClass("example.Circle")
circle.hasAnnotation("scala.deprecated")            // Boolean
circle.getAnnotation("scala.deprecated")            // Maybe[Annotation]
```

`hasAnnotation(fqn)` is subtype-aware: it returns true for any annotation
whose annotation class is a subclass of `fqn` (so a `@MyDeprecated extends
scala.deprecated` is caught by querying for `scala.deprecated`).

`Annotation` carries `annotationType: Type` and `args: Maybe[Tree]`. The
`args` slot is `Maybe` because annotation decode can fail independently of
the symbol decode; a `MalformedSection` lands in `cp.errors` and the
annotation is still present, just without its decoded args. To unwrap the
args as a flat list of `Tree`s, `annotation.argList` returns the children
of the underlying `Tree.Apply` when the args are call-shaped.

`JavaAnnotation` carries `annotationClass: Symbol` and
`values: Map[Name, JavaAnnotation.Value]`. `JavaAnnotation.Value` is a
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

```scala
given Classpath = cp
val shape: Symbol.Trait = cp.requireTrait("example.Shape")

cp.directSubclassesOf(shape)   // Chunk(Circle, Box)  -- exactly one hop
cp.subclassesOf(shape)         // Chunk(Circle, Box)  -- transitive closure
cp.implementationsOf(shape)    // Chunk(Circle, Box)  -- concrete classes only
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
cp.allClasses   // Chunk[Symbol.Class]
cp.allTraits    // Chunk[Symbol.Trait]
cp.allObjects   // Chunk[Symbol.Object]
cp.allMethods   // Chunk[Symbol.Method]
// ... plus allVals, allVars, allFields, allTypeAliases,
//     allOpaqueTypes, allAbstractTypes, allTypeParams,
//     allParameters, allPackages, allClassLike, allUnresolved
```

`cp.topLevelClasses` and `cp.packages` are O(1) accessors of pre-built
chunks; they are what you reach for when you want to iterate roots
rather than the full symbol table.

JPMS modules are surfaced through the same classpath. `cp.modules`
returns `Chunk[Symbol.Class]` for `module-info` classes,
`cp.findModule(name)` looks one up by name, and the descriptors
(`ModuleDescriptor`, `ModuleRequires`, `ModuleExports`, `ModuleOpens`,
`ModuleProvides`) are reachable through the module symbol's
`javaMetadata`. If you do not work with module-info classpaths, you can
ignore this side of the API.

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
import kyo.Tasty.*

def shapeImplementations(roots: Seq[String]): Chunk[String] < (Async & Scope & Abort[TastyError]) =
    Classpath.init(roots).map { cp =>
        given Classpath = cp

        val shape = cp.requireTrait("example.Shape")

        cp.implementationsOf(shape)
            .sortBy(_.fullNameString)
            .map { impl =>
                val overrides = impl.methods
                    .filter(_.isOverride)
                    .map(_.simpleName.asString)
                    .mkString(", ")
                s"${impl.fullNameString} overrides: [$overrides]"
            }
    }
```

The composition pattern that makes this idiomatic Kyo: `Classpath.init`
once at the boundary, `.map` to project into pure data, and let the
effect row stay where the I/O actually lives. Everything between the open
and the final `.map` is plain data manipulation.
