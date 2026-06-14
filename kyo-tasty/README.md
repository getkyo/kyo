# kyo-tasty

kyo-tasty answers reflection questions about Scala 3 code without running it. "What classes exist on this classpath?", "What does this method look like in source form?", "Which symbols carry `@deprecated`?", "Is `Dog <: Animal`?". You point it at compiled `.tasty` and `.class` files on disk (or at in-memory pickles for tests), and inside the resulting scope every query is a pure call that returns immutable data. A class is a `Symbol.Class`; a method signature is a `String`; a sealed hierarchy is a `Chunk[Symbol.ClassLike]`; a subtype check is a three-valued `SubtypeVerdict`. There is no live JVM in the loop, no `Class.forName`, no agent: you are reading what the Scala 3 compiler wrote down.

The single shape to learn is `Tasty.withClasspath(roots) { ... }`. `roots` is a `Seq[String]` of file-system paths (directories, JARs, or `jrt:/` URIs); the block runs in a scope where the lookup and aggregation shortcuts under `object Tasty` read the loaded classpath, and the navigation and rendering operations are pure methods on the `Classpath` value you obtain with `Tasty.classpath`; every result returned out of the block is plain immutable data you can hand around freely. The same shape works with a snapshot cache for fast reopens, with an already-built `Classpath` value, or with in-memory `Pickle` chunks for tests. Cross-platform: JVM reads `.tasty` and `.class` files from disk and JARs; JS and Native do the same with their own backends and skip the JVM-only `jrt:/` JDK image.

<!-- doctest:setup
```scala
import kyo.*
import kyo.Tasty
import kyo.TastyError

// Running domain: a small library being introspected.
// Its compiled output lives at `/build/shop`, and the README walks queries against it.
val shopRoots: Seq[String] = Seq("/build/shop")
```
-->

```scala
Tasty.withClasspath(shopRoots) {
    Tasty.classpath.map { classpath =>
        classpath.requireClass("shop.Dog").map(dog => classpath.show(dog, Tasty.ShowFormat.Code))
    }
}
```

## Loading a classpath

Every kyo-tasty query reads an active classpath. The classpath is bound for the duration of a block and is released when the block exits; queries outside the block fall back to a JVM-default stub and silently lose any subtype diagnostics they collect, so the right shape is always to do the work inside the block.

### `Tasty.withClasspath(roots)`: load from disk

The primary entry point. Walks the file-system roots, decodes every `.tasty` and `.class` file it finds, and runs `f` with the resulting classpath bound. The decoded classpath is the lookup index for `findClass`, `members`, `bodyTree`, the subclass queries, and everything else.

```scala
val program: Chunk[String] < (Async & Abort[TastyError]) =
    Tasty.withClasspath(shopRoots) {
        Tasty.allClasses.map(_.map(_.simpleName))
    }
```

Pass `cacheDir = Maybe.Present(dir)` to back the load with a binary snapshot. On a miss kyo-tasty cold-loads the classpath as usual and writes a `.krfl` snapshot under `dir`; on a hit it restores the in-memory state directly from the snapshot. Repeat opens of the same roots become orders of magnitude cheaper.

```scala
val cached: Unit < (Async & Abort[TastyError]) =
    Tasty.withClasspath(shopRoots, cacheDir = Maybe.Present("/tmp/kyo-tasty-cache")) {
        Tasty.allClasses.map(_ => ())
    }
```

> **Note:** `Tasty.classpath` called outside a `withClasspath` scope returns a JVM-default stub instead of failing, so the lookup shortcuts that read the active binding resolve against an empty stub and any load-time diagnostics are silently lost on the next read. Always wrap queries in `withClasspath`.

### `Tasty.withClasspath(classpath)`: rebinding an existing classpath

When the classpath is already in hand (deserialized from a snapshot, constructed for a test, or carried across module boundaries) bind it directly. No file IO, no scope overhead.

```scala
val classpath: Tasty.Classpath = Tasty.Classpath.empty
val report: Chunk[String] < Sync =
    Tasty.withClasspath(classpath) {
        Tasty.allClasses.map(_.map(_.simpleName))
    }
```

> **Note:** `bodyTree(symbol)` returns `Maybe.Absent` under `withClasspath(classpath)`. The pre-built overload carries no decode context, so on-demand AST decoding is not available. Use the roots-based `withClasspath` (or `withPickles`) when bodies are needed.

### `Tasty.withPickles(pickles)`: in-memory bytes for tests

`Tasty.Pickle(uuid, version, bytes)` packages one `.tasty` file's bytes. A `Chunk[Pickle]` is enough to drive `withPickles`, which decodes the bytes directly without touching the file system. Tests use this to assemble a classpath from fixtures.

```scala
val pickles: Chunk[Tasty.Pickle] = Chunk.empty
val test: Unit < (Async & Abort[TastyError]) =
    Tasty.withPickles(pickles) {
        Tasty.allClasses.map(_ => ())
    }
```

### `Tasty.Classpath.empty`: the baseline value

Useful as a default or as the seed for a synthetic test classpath.

```scala
val classpath: Tasty.Classpath = Tasty.Classpath.empty
assert(classpath.symbols.isEmpty)
```

### `Tasty.Classpath.initWithPlatformModules(roots)`: JDK auto-discovery

On the JVM, the JDK's `module-info.class` files live behind `jrt:/`. Calling `initWithPlatformModules` walks those automatically and merges them into the returned classpath's `modulesIndex`, so `findModule("java.base")` resolves without naming the JDK image explicitly.

```scala
val jdk: Tasty.Classpath < (Async & Scope & Abort[TastyError]) =
    Tasty.Classpath.initWithPlatformModules(shopRoots)
```

> **Note:** `initWithPlatformModules` is JVM-only. JS and Native have no `jrt:/`; calling it on those platforms raises `TastyError.UnsupportedPlatform`. Everything else in this section (including `withClasspath` itself) is cross-platform.

### `Tasty.ErrorMode`: tolerant load vs early abort

The roots-based `withClasspath` defaults to `ErrorMode.SoftFail`: decode errors collect into `classpath.errors`, and the classpath comes back ready to query against the symbols that did decode. The alternative, `ErrorMode.FailFast`, aborts the open on the first decode failure with `Abort[TastyError]`.

`SoftFail` is the right default for IDE-shaped tooling that wants progress over total failure. `FailFast` is appropriate for batch jobs (CI checks, codegen) where a partial classpath is a bug.

### Snapshot cache hygiene

A `cacheDir` accumulates `.krfl` files over time: different roots, different commits, transient builds. `Tasty.evictOlderThan(cacheDir, maxAge)` deletes snapshot files older than `maxAge`; the policy is purely age-based and the operation does not recurse. Use it from a periodic maintenance task.

```scala
val cleanup: Unit < (Sync & Abort[TastyError]) =
    Tasty.evictOlderThan("/tmp/kyo-tasty-cache", maxAge = 7.days)
```

## Looking up a symbol by name

Once a classpath is bound the first move is usually "give me the symbol for this name". kyo-tasty provides two parallel families: `find*` returns `Maybe[Symbol]` and lets the caller handle absence; `require*` aborts with `TastyError.NotFound` when absent. Pick `find*` when missing is a normal outcome (an optional class, a maybe-present companion); pick `require*` when missing should kill the computation.

### `findClass`, `findClassLike`, `findConcreteClass`

```scala
val q: Maybe[Tasty.Symbol.Class] < (Async & Abort[TastyError]) =
    Tasty.withClasspath(shopRoots) {
        Tasty.findClass("shop.Dog")
    }
```

`findClass` matches `Symbol.Class` exactly. `findClassLike` matches `Class`, `Trait`, `Object`, or `EnumCase` at the same full name. `findConcreteClass` is `findClass` with an extra filter that drops anything carrying `Flag.Abstract`.

> **Note:** `findClass("shop.Animal")` returns `Maybe.Absent` for a sealed `trait` because `Animal` is a `Symbol.Trait`, not a `Symbol.Class`. Use `findClassLike` when the kind is uncertain or when traits and classes are both acceptable.

### `findTrait`, `findObject`, `findPackage`, `findSymbol`

```scala
val obj: Maybe[Tasty.Symbol.Object] < (Async & Abort[TastyError]) =
    Tasty.withClasspath(shopRoots)(Tasty.findObject("shop.AnimalRegistry"))

val pkg: Maybe[Tasty.Symbol.Package] < (Async & Abort[TastyError]) =
    Tasty.withClasspath(shopRoots)(Tasty.findPackage("shop"))

val any: Maybe[Tasty.Symbol] < (Async & Abort[TastyError]) =
    Tasty.withClasspath(shopRoots)(Tasty.findSymbol("shop.Dog"))
```

`findTrait` is the counterpart to `findClass` for trait definitions: it returns `Maybe[Symbol.Trait]` and is the right choice when you know the name refers to a trait, not a class or object. Use it instead of `findClassLike` when you want the narrower `Symbol.Trait` type and do not want classes or objects to match.

`findObject` accepts both source form (`"shop.Cat"`) and the JVM `$`-suffix form (`"shop.Cat$"`): the source form falls back to the dollar form, which is where case-class companions live in the binary index.

`findSymbol` is the most permissive: any registered kind succeeds. Use the typed variants when the kind is fixed; use `findSymbol` when it is unknown.

### `findMethod` and the require variants

```scala
val m: Maybe[Tasty.Symbol.Method] < (Async & Abort[TastyError]) =
    Tasty.withClasspath(shopRoots) {
        Tasty.findMethod("shop.LegacyAnimalStore", "find")
    }
```

`findMethod` resolves the owner full name, expects it to be class-like, and returns the first declaration with the matching simple name.

> **Note:** When multiple overloads share a name, `findMethod` returns the first in declaration order. For overload discrimination, walk `classpath.declarations(ownerSym)` and filter by signature.

The `require*` family mirrors every lookup above and aborts with `TastyError.NotFound(fullName)` on absence:

```scala
val dog: Tasty.Symbol.Class < (Async & Abort[TastyError]) =
    Tasty.withClasspath(shopRoots)(Tasty.requireClass("shop.Dog"))
```

`requireClass`, `requireClassLike`, `requireTrait`, `requireObject`, `requirePackage`, `requireSymbol`, and `requireMethod` each have the same signature as their `find*` counterpart with `Maybe[T]` replaced by `T < (Sync & Abort[TastyError])`.

### `findClassesByName`: lookup by simple name

When the full name is unknown but the unqualified name is, `findClassesByName` walks an inverted simple-name index and returns every match across all packages.

```scala
val anyDog: Chunk[Tasty.Symbol.Class] < (Async & Abort[TastyError]) =
    Tasty.withClasspath(shopRoots)(Tasty.findClassesByName("Dog"))
```

The result may have more than one element when the same simple name appears in different packages.

### `classFullName[A]`: compile-time full name

A macro that yields the dotted fully-qualified name of `A`. Use it when the type is known statically and you want the full name as a string without spelling it out.

```scala
val name: String = Tasty.classFullName[scala.collection.immutable.Vector[Int]]
assert(name == "scala.collection.immutable.Vector")
```

Type parameters are stripped: `classFullName[List[Int]]` is `"scala.collection.immutable.List"`. The bare dotted form is what `findClass` and friends accept.

### `Classpath.findClassByBinary`: JVM binary-name lookup

When the only name you have is the JVM binary form (slash-separated path with `$` for nesting), reach for `findClassByBinary` on a `Classpath` value.

```scala
val byBinary: Maybe[Tasty.Symbol.Class] < (Async & Abort[TastyError]) =
    Tasty.withClasspath(shopRoots) {
        Tasty.classpath.map(classpath => classpath.findClassByBinary("shop/Dog"))
    }
```

### `Tasty.all*`: full-classpath aggregations

When you want every symbol of a given kind, the `all*` family returns the unfiltered set as a `Chunk`. There is one variant per kind: `allClassLike`, `allClasses`, `allObjects`, `allTraits`, `allMethods`, `allVals`, `allVars`, `allFields`, `allTypes`, `allPackages`, and `allSymbols` for the full cross-kind set.

```scala
val classes: Chunk[Tasty.Symbol.Class] < (Async & Abort[TastyError]) =
    Tasty.withClasspath(shopRoots)(Tasty.allClasses)
```

> **Note:** the `all*` queries scan `classpath.symbols` on every call. For repeated queries against the same kind, cache the result in the caller.

## Reading a symbol's shape

`Symbol` is a sealed hierarchy of 14 final case classes plus an intermediate `Symbol.ClassLike` trait. Pattern-match to discriminate the kind; use flag predicates for modifier checks; use `simpleName`, `scaladoc`, and `sourcePosition` for the raw fields the loader recorded.

### Pattern-matching on what a symbol is

The sealed hierarchy has 14 leaves: `Class`, `Trait`, `Object`, `EnumCase` (which together form `ClassLike`), `Method`, `Val`, `Var`, `Field`, `TypeAlias`, `OpaqueType`, `AbstractType`, `TypeParam`, `Parameter`, `Package`.

```scala
def describe(symbol: Tasty.Symbol): String = symbol match
    case _: Tasty.Symbol.Class    => "class"
    case _: Tasty.Symbol.Trait    => "trait"
    case _: Tasty.Symbol.Object   => "object"
    case _: Tasty.Symbol.EnumCase => "enum case"
    case _: Tasty.Symbol.Method   => "method"
    case _: Tasty.Symbol.Val      => "val"
    case _: Tasty.Symbol.Var      => "var"
    case _: Tasty.Symbol.Field    => "java field"
    case _                        => "other"
```

Match on `Symbol.ClassLike` when classes, traits, objects, and enum cases should be handled uniformly:

```scala
def isClassLike(symbol: Tasty.Symbol): Boolean = symbol match
    case _: Tasty.Symbol.ClassLike => true
    case _                         => false
```

> **Note:** `EnumCase` is a peer of `Symbol.Class` under `ClassLike`, not a subtype of `Class`. Match `EnumCase` first when discriminating, or it will be lost in a `case _: Symbol.Class` arm.

> **Note:** `import kyo.Tasty.Symbol.Object` shadows `java.lang.Object` and `scala.Object` in the importing file. Reference it qualified (`Tasty.Symbol.Object`) when both might be in scope.

> **Note:** Java `.class` files never produce `Symbol.Object`. A Java enum is a `Symbol.Class`; a `static final` field is a `Symbol.Field`. Only Scala sources contribute `Symbol.Object`, `Symbol.Val`, and `Symbol.Var`. The same JVM field can appear as `Symbol.Field` or `Symbol.Val`/`Var` depending on whether it was decoded from a `.class` or `.tasty` file.

### Reading modifiers via flag predicates

`Symbol` carries 40+ pure predicates that test the flag bitmask: `isFinal`, `isAbstract`, `isSealed`, `isCase`, `isLazy`, `isOverride`, `isPrivate`, `isProtected`, `isPublic`, `isStatic`, `isMutable`, `isErased`, `isInfix`, `isOpen`, `isTransparent`, `isMacro`, `isSynthetic`, `isArtifact`, `isCovariant`, `isContravariant`, `isExtension`, `isTracked`, `isStable`, `isParamAccessor`, `isCaseAccessor`, `isFieldAccessor`, `isExported`, `isLocal`, `hasDefault`, `isInvisible`, `isInto`, `isInlineProxy`, `isTailrec`, `isScala2`, `isJavaRecord`, `isEnum`, `isModule`, `isJava`, `isInline`, `isTransparentInline`, `isGiven`, `isOpaque`. Each is O(1) and requires no classpath access.

```scala
val isSealedAnimal: Boolean < (Async & Abort[TastyError]) =
    Tasty.withClasspath(shopRoots) {
        Tasty.requireClassLike("shop.Animal").map(_.isSealed)
    }
```

> **Note:** `isMacro` excludes synthetic methods that dotty marks with `Flag.Macro` (the `ordinal` / `productElement` methods on enum cases). User-written macros are not synthetic, so `isMacro` is true only for those.

### Visibility and openness, typed

`Symbol.visibility` and `Symbol.openLevel` are typed projections of the flag bits. Prefer them over raw flag tests when the question is "what tier of visibility?" rather than "is this exactly private?".

```scala
val vis: Tasty.Visibility < (Async & Abort[TastyError]) =
    Tasty.withClasspath(shopRoots) {
        Tasty.requireClass("shop.Dog").map(_.visibility)
    }
// Visibility.Public | Private | Protected | ScopedPrivate | ScopedProtected
```

`OpenLevel` covers `Open`, `Default`, `Sealed`, `Final`, in that precedence order.

### `simpleName`, `scaladoc`, `sourcePosition`

`simpleName: String` is the unqualified name. `scaladoc: Maybe[String]` and `sourcePosition: Maybe[Position]` are the raw fields shared across the hierarchy.

```scala
val docs: Maybe[String] < (Async & Abort[TastyError]) =
    Tasty.withClasspath(shopRoots) {
        Tasty.requireClass("shop.LegacyAnimalStore").map(_.scaladoc)
    }
```

> **Note:** `scaladoc` returns the raw comment text including the `/**`, `*/` delimiters and the `*` margins on each line. No stripping, no markdown processing; treat it as bytes the compiler recorded. `Symbol.TypeParam`, `Symbol.Parameter`, and `Symbol.Package` always return `Maybe.Absent`; comments on those positions are not preserved.

### Rendering a symbol

Four renderers cover the common cases, all pure `Classpath` instance methods. `classpath.show(symbol, ShowFormat.Code)` produces a Scala-syntax form (`"def find(name: String): Option[Animal]"`). `ShowFormat.FullyQualified` is the dotted full name. `ShowFormat.Simple` is the unqualified name. `classpath.signature(m)` is `show(m, ShowFormat.Code)` restricted to `Symbol.Method`.

```scala
val rendered: String < (Async & Abort[TastyError]) =
    Tasty.withClasspath(shopRoots) {
        for
            m         <- Tasty.requireMethod("shop.LegacyAnimalStore", "find")
            classpath <- Tasty.classpath
        yield classpath.signature(m)
    }
```

`classpath.fullName(symbol)` returns a `Name` (the opaque-string wrapper); use `import Tasty.Name.asString` to unwrap. `classpath.binaryName(symbol)` returns the JVM binary form: `"shop/Dog"`, `"shop/Cat$"` for the case-class companion.

### Walking the owner chain and companion

```scala
val chain: Chunk[Tasty.Symbol] < (Async & Abort[TastyError]) =
    Tasty.withClasspath(shopRoots) {
        for
            dog       <- Tasty.requireClass("shop.Dog")
            classpath <- Tasty.classpath
        yield classpath.ownersChain(dog)
    }

val maybeCompanion: Maybe[Tasty.Symbol] < (Async & Abort[TastyError]) =
    Tasty.withClasspath(shopRoots) {
        for
            dog       <- Tasty.requireClass("shop.Dog")
            classpath <- Tasty.classpath
        yield classpath.companion(dog)
    }
```

`classpath.owner(symbol)` is the immediate enclosing symbol. `classpath.ownersChain(symbol)` walks until the root, capped at depth 64 to guard against pathological inputs. `classpath.companion(symbol)` is O(1) via the `companionIndex`; it returns the companion class for an object, the companion object for a class, or `Absent` when neither exists.

## Walking class structure

Once you have a `Symbol.ClassLike`, the next move is enumerating what's inside it: declarations, members (including inherited), type parameters, parameter lists, parents, and the sealed hierarchy.

### Declarations and members

`classpath.declarations(symbol)` returns symbols declared directly on `symbol`. For class-like symbols this reads `declarationIds`; for packages it reads `memberIds`; for everything else it returns an empty `Chunk`.

```scala
val decls: Chunk[Tasty.Symbol] < (Async & Abort[TastyError]) =
    Tasty.withClasspath(shopRoots) {
        for
            dog       <- Tasty.requireClass("shop.Dog")
            classpath <- Tasty.classpath
        yield classpath.declarations(dog)
    }
```

`classpath.members(symbol, scope)` takes a `MemberScope` selector and extends the walk:

- `MemberScope.Declared` (default): same as `declarations`.
- `MemberScope.Inherited`: members inherited from parent types, with declared members excluded.
- `MemberScope.All`: union of both, deduplicated by simple name (most-specific wins).

```scala
val all: Chunk[Tasty.Symbol] < (Async & Abort[TastyError]) =
    Tasty.withClasspath(shopRoots) {
        for
            dog       <- Tasty.requireClass("shop.Dog")
            classpath <- Tasty.classpath
        yield classpath.members(dog, Tasty.MemberScope.All)
    }
```

`classpath.findMember(symbol, name, scope)` and `classpath.findDeclaredMember(symbol, name)` look up a single member by simple name. The latter is shorthand for `findMember(symbol, name, MemberScope.Declared)`.

### Type parameters and parameter lists

`classpath.typeParams(symbol)` returns the type parameters declared on `symbol` (applies to `ClassLike`, `Method`, `TypeAlias`, `OpaqueType`). `classpath.paramLists(method)` returns the method's parameter groups: the outer `Chunk` is per parameter list, the inner is the parameters of that list.

```scala
val params: Chunk[Chunk[Tasty.Symbol.Parameter]] < (Async & Abort[TastyError]) =
    Tasty.withClasspath(shopRoots) {
        for
            reg       <- Tasty.requireMethod("shop.AnimalRegistry", "register")
            classpath <- Tasty.classpath
        yield classpath.paramLists(reg)
    }
```

For an extension method, the synthetic receiver is `paramLists(method).head.head`; the positional convention is the identification rule, not a flag.

### Parents and permitted subclasses

`classpath.parents(cl)` resolves the direct parent class-like symbols. Generic parents (e.g. `extends Container[Int]`) decode as `Type.Applied`; `parents` only follows the head `Type.Named`. Use `cl.parentTypes` directly when the full parent type is needed.

```scala
val parentSyms: Chunk[Tasty.Symbol] < (Async & Abort[TastyError]) =
    Tasty.withClasspath(shopRoots) {
        for
            dog       <- Tasty.requireClass("shop.Dog")
            classpath <- Tasty.classpath
        yield classpath.parents(dog)
    }
```

`classpath.permittedSubclasses(sealedSym)` reads the `permittedSubclassIds` on a sealed class or trait. Direct children only; recurse on each element for the full transitive set, or use the classpath-level queries below.

### Classpath-wide subclass queries

When the question is "every concrete impl of this sealed trait on the classpath", three operations live on `Tasty.Classpath`:

- `classpath.directSubclassesOf(symbol)`: one hop.
- `classpath.subclassesOf(symbol)`: transitive BFS closure.
- `classpath.implementationsOf(symbol)`: transitive, filtered to non-abstract `Symbol.Class` only.

```scala
val concrete: Chunk[Tasty.Symbol.Class] < (Async & Abort[TastyError]) =
    Tasty.withClasspath(shopRoots) {
        for
            animal    <- Tasty.requireClassLike("shop.Animal")
            classpath <- Tasty.classpath
        yield classpath.implementationsOf(animal)
    }
```

For the running `shop.Animal` hierarchy, `implementationsOf` returns the two concrete cases (`Dog`, `Cat`). The subclass index is built at classpath open time, so these queries are O(number of edges), not O(classpath).

## Annotations: Scala and Java together

A symbol can carry annotations from two sources: Scala-source annotations stored as `Tasty.Annotation` and class-file annotations stored as `Tasty.Java.Annotation`. The argument shapes are different (Scala annotations carry `Tree` arguments; Java annotations carry typed JVM element values), so the two are parallel ADTs. The query layer hides the split: `hasAnnotation` and `findAnnotation` walk both lists transparently.

### Per-symbol checks

```scala
val isDeprecated: Boolean < (Async & Abort[TastyError]) =
    Tasty.withClasspath(shopRoots) {
        for
            c         <- Tasty.requireClass("shop.LegacyAnimalStore")
            classpath <- Tasty.classpath
        yield classpath.hasAnnotation(c, "scala.deprecated")
    }
```

`classpath.findAnnotation(symbol, annotationFullName)` returns the first matching annotation as `Maybe[Annotation | Java.Annotation]`. Pattern-match to dispatch on the value space:

```scala
def describe(annotation: Tasty.Annotation | Tasty.Java.Annotation): String = annotation match
    case _: Tasty.Annotation      => "scala"
    case _: Tasty.Java.Annotation => "java"
```

### Classpath-wide reverse lookup

`Tasty.symbolsAnnotatedWith(fullName)` scans every symbol in the classpath and returns those carrying an annotation of the given full name. Linear in symbol count; cache the result when querying the same annotation repeatedly.

```scala
val deprecated: Chunk[Tasty.Symbol] < (Async & Abort[TastyError]) =
    Tasty.withClasspath(shopRoots) {
        Tasty.symbolsAnnotatedWith("scala.deprecated")
    }
```

For the plugin-discovery domain, the same query finds every `@PluginEntry`-tagged class on the classpath.

### Java annotation values

`Tasty.Java.Annotation(annotationClass, values)` holds the resolved annotation class symbol and the ordered `(elementName, value)` pairs. The value space is `Tasty.Java.Annotation.Value`, mirroring JVMS §4.7.16.1 element values: `StringVal`, `IntVal`, `LongVal`, `FloatVal`, `DoubleVal`, `BoolVal`, `ClassVal`, `EnumVal`, `ArrayVal`, `AnnotationVal`.

```scala
def pluginId(annotation: Tasty.Java.Annotation): Maybe[String] =
    Maybe.fromOption(annotation.values.collectFirst {
        case (_, Tasty.Java.Annotation.Value.StringVal(s)) => s
    })
```

`Tasty.Annotation(annotationType, arguments)` is the Scala side: `annotationType` is the annotation class as a `Type`, `arguments` is a `Chunk[Tree]` (the unevaluated source-level arguments).

## Reading types

Type information appears wherever a symbol carries a signature: method return types, val declared types, parent types, annotation arguments. kyo-tasty represents the Scala 3 type language as a sealed enum `Tasty.Type` with around 30 cases.

### The Type ADT

Pattern-match on `Type` for structural questions:

```scala
def isApplied(t: Tasty.Type): Boolean = t match
    case Tasty.Type.Applied(_, _) => true
    case _                        => false
```

Cases group by purpose: nominal references (`Named`, `TermRef`, `TypeRef`), type constructors (`Applied`, `TypeLambda`, `Function`, `ContextFunction`, `Tuple`), composite shapes (`AndType`, `OrType`, `Refinement`, `Annotated`), self / super / this references (`ThisType`, `SuperType`, `ParamRef`), bounds and wildcards (`Bounds`, `Wildcard`), match-type machinery (`MatchType`, `MatchCase`, `Bind`, `Skolem`, `FlexibleType`, `Rec`, `RecThis`), constants (`ConstantType`), and array / by-name / repeated wrappers (`Array`, `ByName`, `Repeated`). Two reserved sentinels, `Type.Nothing` and `Type.Any`, stand in for missing bounds.

> **Note:** `Type.Nothing` and `Type.Any` are real enum cases, not magic `Named` ids. Match them explicitly, do not look for a sentinel `SymbolId`.

### Traversal

Every `Type` carries a uniform traversal set: `children`, `foreach`, `collect`, `find`, `foldLeft`, `exists`. The inline forms delegate to non-inline bodies for code-size reasons.

```scala
def countNamed(t: Tasty.Type): Int =
    t.foldLeft(0) {
        case (n, _: Tasty.Type.Named) => n + 1
        case (n, _)                   => n
    }
```

```scala
def usesNamed(t: Tasty.Type, target: Tasty.SymbolId): Boolean =
    t.exists {
        case Tasty.Type.Named(id) => id == target
        case _                    => false
    }
```

### Resolving the head symbol

`classpath.typeSymbol(tpe)` returns the symbol of a `Type.Named` (the head of the type). Other shapes return `Maybe.Absent`; recurse into the appropriate child to follow the structure.

```scala
val headSym: Maybe[Tasty.Symbol] < (Async & Abort[TastyError]) =
    Tasty.withClasspath(shopRoots) {
        for
            m         <- Tasty.requireMethod("shop.LegacyAnimalStore", "find")
            classpath <- Tasty.classpath
        yield classpath.typeSymbol(m.declaredType.getOrElse(Tasty.Type.Any))
    }
```

### Rendering a type

`classpath.typeShow(tpe)` returns a human-readable string. Named types resolve to their `simpleName`; composite shapes render in Scala-like syntax.

```scala
val display: String < (Async & Abort[TastyError]) =
    Tasty.withClasspath(shopRoots) {
        for
            m         <- Tasty.requireMethod("shop.LegacyAnimalStore", "all")
            classpath <- Tasty.classpath
        yield classpath.typeShow(m.declaredType.getOrElse(Tasty.Type.Any))
    }
```

### Subtype checking

`classpath.isSubtypeOf(tpe, other)` is a three-valued structural check that returns a `Result[TastyError, SubtypeVerdict]`. The `SubtypeVerdict` is one of:

- `Sub`: `tpe` is a structural subtype of `other`.
- `NotSub`: `tpe` is not a subtype of `other`.
- `Indeterminate`: the walk could not decide.

```scala
val verdict: Result[TastyError, Tasty.SubtypeVerdict] < (Async & Abort[TastyError]) =
    Tasty.withClasspath(shopRoots) {
        for
            dog       <- Tasty.requireClass("shop.Dog")
            animal    <- Tasty.requireClassLike("shop.Animal")
            classpath <- Tasty.classpath
        yield classpath.isSubtypeOf(Tasty.Type.Named(dog.id), Tasty.Type.Named(animal.id))
    }
// Result.Success(SubtypeVerdict.Sub)
```

`Indeterminate` covers the cases where the walk could not produce `Sub` or `NotSub` (typically a deeply nested shape or an irreducible mixed `Or`/`And`). Crucially, it is NOT "we forgot this branch": unhandled parent-walk shapes go into `classpath.errors` as `TastyError.UnhandledSubtypingCase`, not into the verdict.

`SubtypeVerdict` combines via three-valued lattice math: an `AndType` on the right side is `Sub` only when both components are `Sub`; an `OrType` on the left side is `Sub` when either component is.

### Constants

`Tasty.Constant` is the typed literal payload used by `Type.ConstantType` and `Tree.Literal`: `StringConst(s)`, `IntConst(i)`, `LongConst(l)`, `FloatConst(f)`, `DoubleConst(d)`, `BooleanConst(b)`, `CharConst(c)`, `ByteConst(b)`, `ShortConst(s)`, `UnitConst`, `NullConst`, `ClassConst(tpe)`.

```scala
val s: String = Tasty.Constant.IntConst(42).show      // "42"
val l: String = Tasty.Constant.StringConst("hi").show // "\"hi\""
val u: String = Tasty.Constant.UnitConst.show         // "()"
```

> **Note:** The `*Const` suffix differs from `scala.quoted.reflect.Constant`'s `*Constant` suffix. A literal type `42` is `Type.ConstantType(Constant.IntConst(42))`, never a top-level `Type.IntConst`.

### Variance, type bounds

`Tasty.Variance` (`Invariant`, `Covariant`, `Contravariant`) and `Tasty.TypeBounds(lower: Type, upper: Type)` are the supporting ADTs for type-parameter declarations and abstract-type bounds.

## Decoding method and val bodies

Parsing every body upfront would be wasteful: most reflective code never asks for an AST. `Tasty.bodyTree(symbol)` is the lazy boundary. The first call for a given symbol decodes the bytes; subsequent calls hit a cache, so a body is decoded at most once per scope.

### `Tasty.bodyTree(symbol)`

```scala
val body: Maybe[Tasty.Tree] < (Async & Abort[TastyError]) =
    Tasty.withClasspath(shopRoots) {
        for
            m <- Tasty.requireMethod("shop.LegacyAnimalStore", "find")
            t <- Tasty.bodyTree(m)
        yield t
    }
```

Returns `Maybe.Present(tree)` for methods and vals whose body was recorded, `Maybe.Absent` for symbols with no body slot (parameters, packages, Java fields), and aborts with `TastyError.MalformedSection` when the bytes are corrupt.

> **Note:** `bodyTree` returns `Absent` under `withClasspath(classpath)` because that overload carries no decode context. Use the roots-based `withClasspath` or `withPickles` when bodies are needed.

> **Note:** The body cache is per-`withClasspath` invocation. A second `withClasspath` call decodes from scratch.

### The Tree ADT

`Tasty.Tree` is a sealed enum of around 70 cases covering expressions, definitions, type-position nodes, patterns, and imports. Like `Type`, every `Tree` exposes the same traversal interface: `children`, `foreach`, `collect`, `find`, `foldLeft`, `exists`.

```scala
def findStringLiterals(tree: Tasty.Tree): Chunk[String] =
    tree.collect {
        case Tasty.Tree.Literal(Tasty.Constant.StringConst(s)) => s
    }
```

### Rendering a tree

`classpath.treeShow(tree)` returns a human-readable string with symbols and types resolved against the classpath.

```scala
val pretty: Maybe[String] < (Async & Abort[TastyError]) =
    Tasty.withClasspath(shopRoots) {
        for
            m         <- Tasty.requireMethod("shop.LegacyAnimalStore", "find")
            mbT       <- Tasty.bodyTree(m)
            classpath <- Tasty.classpath
        yield mbT.map(classpath.treeShow)
    }
```

## Java and JPMS metadata

When a symbol comes from a `.class` file there is information that has no Scala-source analogue: JVM access flags, throws clauses, the enclosing-method record for inner classes, record components, the bootstrap-methods table for `invokedynamic`, JVM nest membership, parameter names, and runtime-visible type annotations. `Tasty.Java.Metadata` is the per-symbol container; `Symbol.javaMetadata: Maybe[Java.Metadata]` exposes it on every class-like and on `Symbol.Field` and `Symbol.Method`.

### `Java.Metadata`

```scala
val throws: Maybe[Chunk[Tasty.Type]] < (Async & Abort[TastyError]) =
    Tasty.withClasspath(shopRoots) {
        Tasty.requireMethod("shop.LegacyAnimalStore", "find").map(m => m.javaMetadata.map(_.throwsTypes))
    }
```

Fields: `throwsTypes`, `annotations`, `enclosingMethod: Maybe[EnclosingMethod]`, `accessFlags: Int`, `recordComponents: Chunk[RecordComponent]`, `bootstrapMethods: Chunk[Chunk[Int]]`, `nestHost: Maybe[Symbol]`, `nestMembers: Chunk[Symbol]`, `paramNames: Chunk[ParamGroup]`, `runtimeTypeAnnotations: Chunk[Annotation]`.

The sub-records: `Java.RecordComponent(name, tpe)`, `Java.ParamGroup(methodName, parameterNames)`, `Java.EnclosingMethod(owner, methodName)`.

### JPMS modules

A `module-info.class` parses into `Tasty.Java.Module.Descriptor(name, version, requires, exports, opens, uses, provides)`. The directive records are `Requires(name, version, isTransitive, isStaticPhase)`, `Exports(packageName, targets, flags)`, `Opens(packageName, targets, flags)`, and `Provides(serviceName, implementations)`.

```scala
val javaBase: Maybe[Tasty.Java.Module.Descriptor] < (Async & Abort[TastyError]) =
    Tasty.withClasspath(shopRoots)(Tasty.findModule("java.base"))
```

`Tasty.findModule(name)` is the `Maybe`-returning lookup; `classpath.requireModule(name)` aborts with `TastyError.NotFound` on absence.

`Classpath.initWithPlatformModules(roots)` is the JVM-only convenience that pre-loads every JDK module from `jrt:/` (see [Loading a classpath](#loading-a-classpath)).

## Errors and diagnostics

`TastyError` is a closed enum: every failure that flows on `Abort[TastyError]` is one of its cases. The cases group by surface.

### The error families

- File-level decode: `FileNotFound`, `CorruptedFile`, `UnsupportedVersion`, `MalformedSection`, `ClassfileFormatError`, `UnknownTagInPosition`, `InconsistentClasspath`, `FullNameCollisionError`.
- Lookup: `SymbolNotFound`, `NotFound`, `InvalidFullName`, `InvalidUuid`.
- Snapshot cache: `SnapshotFormatError`, `SnapshotVersionMismatch`, `SnapshotIoError`, `DigestMismatch`.
- Lifecycle: `ClasspathClosed`, `ClasspathBuilding`.
- Platform / reserved: `UnsupportedPlatform`, `NotImplemented`.
- Subtype-engine diagnostic: `UnhandledSubtypingCase`.
- Load diagnostics: `UnresolvedReference`, `UnknownType`, `MissingDeclaredType`.

```scala
val outcome: Result[TastyError, Tasty.Symbol.Class] < Async =
    Abort.run(Tasty.withClasspath(shopRoots)(Tasty.requireClass("shop.Nope")))
// Result.Failure(TastyError.NotFound("shop.Nope"))
```

> **Note:** `NotImplemented` is NOT returned for absent attributes (those are `Maybe.Absent`) or for unrecognised TASTy tags (those become `Tree.Unknown` for graceful degradation). It is only for features the reader recognises but has not yet decoded, e.g. a snapshot section written by a newer kyo-tasty version.

### Best-effort diagnostics

Under the default `ErrorMode.SoftFail`, decode errors accumulate in `classpath.errors: Chunk[TastyError]` instead of aborting the load. Read them after the scope opens to surface what was skipped.

```scala
val errors: Chunk[TastyError] < (Async & Abort[TastyError]) =
    Tasty.withClasspath(shopRoots) {
        Tasty.classpath.map(_.errors)
    }
```

`UnhandledSubtypingCase` is the diagnostic that `isSubtypeOf` writes when it encounters a parent shape it does not know how to walk. The verdict for the affected check becomes `Indeterminate`, and the diagnostic lands in `classpath.errors` on the next `Tasty.classpath` read inside the scope.

> **Note:** After a `.krfl` snapshot round-trip, `UnhandledSubtypingCase.lhs` and `rhs` may decode to `Type.Nothing` for complex shapes (`Refinement`, `AndType`, `OrType`, `MatchType`, `FlexibleType`) that fall outside the snapshot type encoder's covered set. The shape label remains accurate; the carried types do not.

`Classpath.collisionReport` and `Classpath.unresolvedTypeReferenceCount` are the standalone diagnostic accessors on `Classpath`, populated during load.

### Composing on the effect row

Three effect rows appear in this README:

- `Tasty.withClasspath(roots, ...)` and `Tasty.withPickles(...)` introduce `Async & Abort[TastyError]` (the roots overload reads files; both consume `Scope` internally).
- `Tasty.withClasspath(classpath)` introduces nothing: its row is identical to `f`'s row.
- The companion lookup shortcuts (`findClass`, `findMethod`, `Tasty.classpath`, the `all*` family, `symbolsAnnotatedWith`) carry `< Sync` because they read the active binding. The `require*` variants carry `< (Sync & Abort[TastyError])`. `bodyTree` carries `< (Sync & Abort[TastyError])` for on-demand AST decoding. Once a `Classpath` value is in hand (via `Tasty.classpath`), navigation that needs classpath data (`show`, `signature`, `paramLists`, `parents`, `permittedSubclasses`, `members`, `findMember`, `hasAnnotation`, `findAnnotation`, `isSubtypeOf`, `typeShow`, `treeShow`) is a pure instance method with no effect row.

Compose with `for`-comprehensions inside the `withClasspath` body:

```scala
val composed: Chunk[String] < (Async & Abort[TastyError]) =
    Tasty.withClasspath(shopRoots) {
        for
            animal    <- Tasty.requireClassLike("shop.Animal")
            classpath <- Tasty.classpath
            impls = classpath.implementationsOf(animal)
            names = impls.map(c => classpath.show(c, Tasty.ShowFormat.Code))
        yield names
    }
```

## Working with a Classpath directly

The `Tasty.*` queries dispatch through the active binding. When a `Classpath` value is already in hand, it exposes the same operations as instance methods. This is the right shape when a snapshot reader hands you a classpath outside a scope, or when you re-enter a scope you just left.

### `Classpath` fields

```scala
val classpath: Tasty.Classpath                   = Tasty.Classpath.empty
val symbols: Chunk[Tasty.Symbol]                 = classpath.symbols
val indices: Tasty.Classpath.Indices             = classpath.indices
val errors: Chunk[TastyError]                    = classpath.errors
val modules: Chunk[Tasty.Java.Module.Descriptor] = classpath.modules
val root: Tasty.SymbolId                         = classpath.rootSymbolId
```

### Operations mirrored on Classpath

Every `Tasty.find*` and `Tasty.require*` has a same-named method on `Classpath`: `classpath.findClass(fullName)`, `classpath.findClassLike(fullName)`, `classpath.findConcreteClass(fullName)`, `classpath.findObject(fullName)`, `classpath.findPackage(fullName)`, `classpath.findSymbol(fullName)`, `classpath.findClassesByName(name)`, `classpath.findModule(name)`, `classpath.findClassByBinary(name)`, plus `classpath.requireClass(fullName)`, `classpath.requireSymbol(fullName)`, `classpath.requireModule(name)`, and the rest.

The `Tasty.all*` aggregations mirror as `classpath.allSymbols`, `classpath.allClassLike`, `classpath.allClasses`, `classpath.allObjects`, `classpath.allTraits`, `classpath.allMethods`, `classpath.allVals`, `classpath.allVars`, `classpath.allFields`, `classpath.allPackages`, plus typed variants `classpath.allTypeAliases`, `classpath.allOpaqueTypes`, `classpath.allAbstractTypes`.

Subclass-index operations: `classpath.directSubclassesOf(symbol)`, `classpath.subclassesOf(symbol)`, `classpath.implementationsOf(symbol)`.

Misc: `classpath.companion(symbol)`, `classpath.fullName(symbol)`, `classpath.symbolsAnnotatedWith(fullName)`, `classpath.collisionReport`, `classpath.unresolvedTypeReferenceCount`, `classpath.topLevelClasses`, `classpath.packages`.

### `SymbolId` and `classpath.symbol(id)`

`Tasty.SymbolId` is an opaque `Int` handle into the dense `classpath.symbols` array. `classpath.symbol(id)` is the O(1) resolution.

```scala
val bySymbol: Maybe[Tasty.Symbol] < (Async & Abort[TastyError]) =
    Tasty.withClasspath(shopRoots) {
        for
            dog       <- Tasty.requireClass("shop.Dog")
            classpath <- Tasty.classpath
        yield classpath.symbol(dog.id)
    }
```

> **Note:** `SymbolId` values are NOT stable across `withClasspath` invocations. A `SymbolId` from one classpath fed to another classpath's `symbol(id)` returns whatever symbol happens to sit at that index, not `Maybe.Absent`. Cross-classpath identity must use the fully-qualified name, never `SymbolId`.

> **Note:** Two symbols from the same classpath compare equal via `==` iff their `id` values match AND their `kind` values match AND neither id is `-1`. Equality on root-sentinel ids returns `false` even for the same symbol.

### `Classpath.Indices` and diagnostics

`Classpath.Indices` is the immutable index bundle: `byFullName`, `bySimpleName`, `packageIndex`, `subclassIndex`, `companionIndex`, `modulesIndex`, `topLevelClassIds`, `packageIds`, `unresolvedFullNameByNegId`, `diagnostics`. Direct access is rarely needed; the named queries cover the common cases.

`Classpath.Diagnostic` is a sealed trait; the only current concrete case is `Classpath.FullNameCollision(fullName, ids)` recording roots that registered the same fully-qualified name under `ErrorMode.SoftFail`.

### Format-level types

`Tasty.Pickle(uuid, version, bytes)` is the in-memory `.tasty` payload. `Tasty.Version(major, minor, experimental)` is the format version triple; `Tasty.supportedTastyVersion` is the version this kyo-tasty release targets.

```scala
val supported: String = Tasty.supportedTastyVersion.show // "28.8.0"
```

## Putting it together

The plugin-discovery scenario: every concrete impl of a sealed interface, with each impl's overridden methods listed.

```scala
import kyo.*
import kyo.Tasty
import kyo.TastyError

case class PluginInfo(name: String, methods: Chunk[String])

val pluginRoots: Seq[String] = Seq("/build/plugins")

val discover: Chunk[PluginInfo] < (Async & Abort[TastyError]) =
    Tasty.withClasspath(pluginRoots) {
        for
            plugin    <- Tasty.requireClassLike("plugin.Plugin")
            classpath <- Tasty.classpath
            impls = classpath.implementationsOf(plugin)
            infos = impls.map { impl =>
                val name  = classpath.show(impl, Tasty.ShowFormat.FullyQualified)
                val decls = classpath.members(impl, Tasty.MemberScope.Declared)
                val overridden = decls.collect {
                    case m: Tasty.Symbol.Method if m.isOverride => m
                }
                val sigs = overridden.map(classpath.signature)
                PluginInfo(name, sigs)
            }
        yield infos
    }
```

Inside `withClasspath`:

1. `requireClassLike("plugin.Plugin")` resolves the trait or aborts with `TastyError.NotFound`.
2. `Tasty.classpath` lifts the bound classpath into the effect row; `implementationsOf(plugin)` returns the concrete subclasses from the precomputed subclass index.
3. For each impl, `classpath.show` renders the full name, `classpath.members(impl, MemberScope.Declared)` lists declarations, and the `isOverride` flag predicate filters to overridden methods. All three are pure once the classpath value is in hand.
4. `classpath.signature(m)` renders each one as a Scala-syntax signature string.

The returned `Chunk[PluginInfo]` is plain immutable data: pass it across modules, serialize it with `kyo-schema`, hold it past the end of the scope. The `withClasspath` block runs once, decodes the classpath once, queries it many times, releases its resources at exit.
