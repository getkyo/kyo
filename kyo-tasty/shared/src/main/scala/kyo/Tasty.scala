package kyo

import kyo.internal.tasty.binary.Utf8
import kyo.internal.tasty.query.Binding
import kyo.internal.tasty.query.ClasspathOrchestrator
import kyo.internal.tasty.query.DecodeContext
import kyo.internal.tasty.query.PlatformModuleOps
import kyo.internal.tasty.query.TastyStat
import kyo.internal.tasty.snapshot.DigestComputer as SnapshotDigest
import kyo.internal.tasty.snapshot.SnapshotReader
import kyo.internal.tasty.snapshot.SnapshotWriter
import kyo.internal.tasty.symbol.SymbolKind
import kyo.stats.Attributes
import scala.collection.immutable.IntMap
import scala.util.control.NonFatal

/** Public entry object for kyo-tasty.
  *
  * The single namespace for the runtime reflection library. Opening a classpath gives back a `Classpath`
  * snapshot; everything you can ask about a Scala 3 program is reachable through the types nested here.
  *
  * `Symbol` (and its sealed subtypes) is the declaration model: classes, traits, objects, methods, vals, vars,
  * fields, type aliases, type parameters, parameters, packages. `Type` is the type model: named references,
  * applied constructors, function shapes, intersections and unions, refinements. `Tree` is the AST model
  * returned by `Tasty.bodyTree` for method and val bodies. `Annotation` and `Java.Annotation` cover Scala and
  * JVM annotations as parallel ADTs. `Classpath` is the in-memory index that resolves cross-symbol references
  * and answers subclass, annotation, and fully-qualified name queries.
  *
  * The public API is identical across JVM, JS, and Native; platform-specific behaviour stays in the loader, not
  * the model.
  *
  * All types nest under `object Tasty` (`Tasty.Type`, `Tasty.Symbol`, ...) to keep separation from
  * `kyo.Structure.Type` (kyo-schema's value-structure tree). When both `Structure` and `Tasty` are imported in
  * the same file, reference them qualified (`Structure.Type`, `Tasty.Type`).
  */
object Tasty:

    // Bring the `value` extension into scope for the entirety of `object Tasty` so that
    // internal code can write `id.value` without per-site imports. The extension itself is
    // defined inside `object SymbolId` in the "Nested types" section below; this forward
    // reference is legal because the extension is a member of a sibling nested object.
    import SymbolId.value

    // ── Active binding ─────────────────────────────────────────────────────

    /** Thread-local binding read by every Tasty.* query method to resolve the active Classpath.
      *
      * Set by `withClasspath` and `withPickles`. Query methods call `bindingLocal.use` to obtain
      * the active `Binding`; if absent they fall back to `global`. `private[kyo]` so callers
      * cannot escape the binding contract.
      */
    private[kyo] val bindingLocal: Local[Maybe[Binding]] = Local.init(Maybe.Absent)

    /** Module-level lazy fallback binding used outside any `withClasspath` / `withPickles` scope.
      *
      * On JVM, `PlatformFallback.initFallback` cold-loads `java.class.path` exactly once per
      * process. On JS and Native, the fallback returns `Binding.empty` (an effectful query reading
      * it surfaces `TastyError.UnsupportedPlatform`). `private[kyo]`; not part of the public surface.
      */
    // Unsafe: lazy global fallback binding for callers outside a `withClasspath` / `withPickles` scope.
    // Named one of the four `AllowUnsafe` sites (kyo-tasty/CONTRIBUTING.md:39-72): site 2.
    private[kyo] lazy val global: Binding =
        kyo.internal.tasty.query.PlatformFallback.initFallback

    // ── Suspend / create ───────────────────────────────────────────────────

    /** Bind a fresh Classpath loaded from `roots` and run `f` in that scope.
      *
      * Loads the classpath from the given file-system roots under `ErrorMode.SoftFail`. When `cacheDir` is
      * `Present(dir)`, reads a cached snapshot from `dir` first; on a miss, loads from source and writes the
      * snapshot before returning. Resources held by the loader are released when the scope exits.
      *
      * This is the only entry point that touches the file system. Every `Tasty.*` query called inside `f` is
      * pure and performs no IO.
      */
    def withClasspath[A, S](
        roots: Seq[String],
        cacheDir: Maybe[String] = Maybe.Absent
    )(f: => A < S)(using Frame): A < (Async & Abort[TastyError] & S) =
        Scope.run {
            ClasspathOrchestrator.coldLoadBinding(roots, ErrorMode.SoftFail, cacheDir).map { binding =>
                bindingLocal.let(Maybe.Present(binding))(f)
            }
        }

    /** Bind a pre-existing (deserialised) Classpath and run `f` in that scope.
      *
      * No filesystem access; no scope overhead. `Tasty.bodyTree` returns `Maybe.Absent` for every
      * symbol inside `f`: the supplied classpath carries no body source handle.
      */
    def withClasspath[A, S](classpath: Classpath)(f: => A < S)(using Frame): A < S =
        bindingLocal.let(Maybe.Present(Binding(classpath, Maybe.Absent)))(f)

    /** Bind a Classpath decoded from in-memory pickles and run `f` in that scope.
      *
      * Decodes the pickles sequentially from the in-memory bytes map; no file system access. The binding
      * carries a fresh `DecodeContext` so `Tasty.bodyTree` can decode body bytes on demand. Every `Tasty.*`
      * query called inside `f` is pure.
      */
    def withPickles[A, S](pickles: Chunk[Pickle])(f: => A < S)(using Frame): A < (Async & Abort[TastyError] & S) =
        Scope.run {
            ClasspathOrchestrator.loadPickles(pickles).map { binding =>
                bindingLocal.let(Maybe.Present(binding))(f)
            }
        }

    /** Delete snapshot files in `cacheDir` whose modification time is older than `maxAge`.
      *
      * `withClasspath(roots, Present(cacheDir))` writes a binary snapshot file (`*.krfl`) keyed by a digest
      * of the input roots, so repeat opens of the same classpath restore the in-memory state without
      * re-decoding the underlying source files. The cache directory accumulates snapshots over time;
      * long-running processes call this to bound its size.
      *
      * Only `*.krfl` files are considered; no recursion into subdirectories; eviction is age-based with no
      * content inspection. Eviction proceeds oldest-first and stops once a file within the retention window is
      * reached. `SnapshotIoError` surfaces when the directory cannot be read.
      *
      * Snapshot read and write happen implicitly inside `withClasspath`. Snapshot-related failures surface as
      * `SnapshotFormatError`, `SnapshotVersionMismatch`, `SnapshotIoError`, or `DigestMismatch` on `TastyError`.
      *
      * @param cacheDir directory containing snapshot files
      * @param maxAge   maximum age; files older than this are deleted
      */
    def evictOlderThan(cacheDir: String, maxAge: Duration)(using Frame): Unit < (Sync & Abort[TastyError]) =
        val maxAgeMs = maxAge.toMillis
        // List all *.krfl files; wrap FileFsException so the caller sees TastyError.SnapshotIoError.
        Abort.recover[FileFsException](e => Abort.fail(TastyError.SnapshotIoError(s"list $cacheDir: ${e.getMessage}")))(
            Path(cacheDir).list("*.krfl")
        ).map { files =>
            // Collect (path, mtime) for each file; skip files whose stat call fails (concurrent-writer race).
            Kyo.collect(files) { p =>
                Abort.run[FileReadException](p.stat).map {
                    case Result.Success(st) => Maybe((p, st.lastModifiedMs))
                    case _                  => Maybe.Absent
                }
            }.map { pairs =>
                Clock.now.map { now =>
                    val nowMs  = now.toDuration.toMillis
                    val sorted = pairs.toSeq.sortBy(_._2)
                    // Delete in ascending-mtime order; stop at the first non-stale file (early exit).
                    Kyo.foreachDiscard(sorted.takeWhile { case (_, mtimeMs) => nowMs - mtimeMs > maxAgeMs }) {
                        case (p, _) =>
                            // Absorb errors: a missing file means a concurrent writer already replaced it.
                            Abort.run[FileFsException](p.remove).map(_ => Kyo.unit)
                    }
                }
            }
        }
    end evictOlderThan

    // ── Access ─────────────────────────────────────────────────────────────

    /** Get the current Classpath from the active binding.
      *
      * Inside a `withClasspath` scope, returns the bound classpath. Outside a scope, returns the
      * module-level JVM classpath stub. Load-time diagnostics are carried in `classpath.errors`.
      */
    def classpath(using Frame): Classpath < Sync =
        bindingLocal.use { mbind => mbind.getOrElse(global).classpath }

    // ── Tasty.* query operations ────────────────────────────────────────────
    // All query operations read the active binding from `Tasty.bindingLocal`. They carry
    // < Sync in their effect row because the lazy fallback `Tasty.global` may trigger
    // initialization on the first call.

    /** Expand to the fully-qualified dotted name of `A`'s class symbol at compile time.
      *
      * Type parameters are stripped: `classFullName[List[Int]]` returns `"scala.collection.immutable.List"`, not
      * `"scala.collection.immutable.List[scala.Int]"`. The bare dotted form is what `findClass`,
      * `findClassLike`, and `findSymbol` accept; the JVM binary form (`example/Circle$Inner`) is reachable
      * through `Classpath.findClassByBinary` instead.
      *
      * No runtime cost; resolves entirely at compile time.
      */
    inline def classFullName[A]: String = ${ kyo.internal.tasty.macros.ClassFullNameMacro.impl[A] }

    /** Look up a class symbol by fully-qualified dotted name.
      *
      * Delegates to `Classpath.findClass`, which performs a pure O(1) index lookup. Returns
      * `Maybe.Absent` when the fully-qualified name is not in the loaded classpath or when the resolved symbol
      * is not a `Symbol.Class` (e.g., a Trait or Object at the same fully-qualified name is silently absent).
      *
      * Abstract classes, sealed abstract classes (e.g. `scala.Option`), and concrete classes
      * are all included. Use `findConcreteClass` to restrict to non-abstract classes.
      *
      * @param fullName dotted fully-qualified name (e.g. `"scala.collection.immutable.List"`)
      * @return the class symbol wrapped in `Maybe.Present`, or `Maybe.Absent` if not found
      */
    def findClass(fullName: String)(using Frame): Maybe[Symbol.Class] < Sync =
        classpath.map(_.findClass(fullName))

    /** Look up any class-like symbol by fully-qualified dotted name.
      *
      * Matches `Symbol.Class`, `Symbol.Trait`, `Symbol.Object`, and `Symbol.EnumCase` at the given fully-qualified name. The
      * broad accessor when the caller does not need to discriminate the kind.
      *
      * Returns `Maybe.Absent` when the fully-qualified name is absent from the classpath or when the resolved symbol is a
      * non-class-like kind (e.g. a `Symbol.Package` or `Symbol.Method`).
      *
      * @param fullName dotted fully-qualified name
      * @return the class-like symbol wrapped in `Maybe.Present`, or `Maybe.Absent`
      */
    def findClassLike(fullName: String)(using Frame): Maybe[Symbol.ClassLike] < Sync =
        classpath.map(_.findClassLike(fullName))

    /** Look up a Scala object (module) symbol by fully-qualified name.
      *
      * Accepts both the source form (`"mypackage.MyObject"`) and the binary `$`-suffix form
      * (`"mypackage.MyObject$"`). When given the source form, the lookup first tries a direct
      * fully-qualified name match and, on miss, appends `"$"` and retries against the binary-name index.
      *
      * Returns `Maybe.Absent` when neither variant resolves to a `Symbol.Object`. Use
      * `findClassLike` if you want to match objects together with classes and traits.
      *
      * @param fullName dotted source-form or `$`-suffixed binary-form fully-qualified name of the object
      * @return the object symbol wrapped in `Maybe.Present`, or `Maybe.Absent`
      */
    def findObject(fullName: String)(using Frame): Maybe[Symbol.Object] < Sync =
        classpath.map(_.findObject(fullName))

    /** Look up a trait symbol by fully-qualified dotted name.
      *
      * Delegates to `Classpath.findTrait`. Returns `Maybe.Absent` when the fully-qualified name is
      * not in the loaded classpath or when the resolved symbol is not a `Symbol.Trait`.
      *
      * Use `findClassLike` to match any class-like symbol regardless of kind. Use this method when
      * the caller specifically expects a trait and wants absence (e.g. a class at the same name) to
      * be distinguishable from not-found.
      *
      * @param fullName dotted fully-qualified name (e.g. `"scala.collection.Iterable"`)
      * @return the trait symbol wrapped in `Maybe.Present`, or `Maybe.Absent`
      */
    def findTrait(fullName: String)(using Frame): Maybe[Symbol.Trait] < Sync =
        classpath.map(_.findTrait(fullName))

    /** Look up any symbol by fully-qualified dotted name.
      *
      * The most permissive lookup: returns `Maybe.Present` for any registered symbol kind
      * (Class, Trait, Object, Method, Val, Package, etc.). Use the typed variants
      * (`findClass`, `findClassLike`, `findObject`, `findPackage`) when a specific kind
      * is expected; use this method when the kind is unknown or irrelevant.
      *
      * Returns `Maybe.Absent` when the fully-qualified name has no entry in the loaded classpath.
      *
      * @param fullName dotted fully-qualified name of the target symbol
      * @return the symbol wrapped in `Maybe.Present`, or `Maybe.Absent`
      */
    def findSymbol(fullName: String)(using Frame): Maybe[Symbol] < Sync =
        classpath.map(_.findSymbol(fullName))

    /** Look up a package symbol by fully-qualified dotted name.
      *
      * Returns `Maybe.Present` only when the fully-qualified name resolves to a `Symbol.Package`. Any other
      * symbol kind at the same name (e.g. a companion object) produces `Maybe.Absent`.
      *
      * Packages are synthesized during classpath loading from the directory structure of
      * each root. The root package is registered under the empty string `""`.
      *
      * @param fullName dotted package name (e.g. `"scala.collection.immutable"`), or `""` for root
      * @return the package symbol wrapped in `Maybe.Present`, or `Maybe.Absent`
      */
    def findPackage(fullName: String)(using Frame): Maybe[Symbol.Package] < Sync =
        classpath.map(_.findPackage(fullName))

    /** Look up a JPMS module descriptor by module name.
      *
      * Returns `Maybe.Present` only when a `module-info.class` entry for `name` was found
      * in the loaded classpath roots and successfully decoded. Module descriptors are populated
      * during classpath loading from JVM module roots (e.g. a JDK image passed via
      * `Classpath.Root.jrt`).
      *
      * Returns `Maybe.Absent` when no module with that name was loaded, or when the classpath
      * was opened without JDK/module roots.
      *
      * @param name JPMS module name (e.g. `"java.base"`, `"com.example.mymodule"`)
      * @return the module descriptor wrapped in `Maybe.Present`, or `Maybe.Absent`
      */
    def findModule(name: String)(using Frame): Maybe[Java.Module.Descriptor] < Sync =
        classpath.map(_.findModule(name))

    /** Look up a concrete (non-abstract) class by fully-qualified dotted name.
      *
      * Equivalent to `findClass(fullName).filter(!_.isAbstract)`. Returns `Maybe.Absent` when the fully-qualified name is missing
      * from the classpath, when it resolves to a non-Class symbol, or when the class has the Abstract
      * modifier (e.g. `scala.Option`, `scala.collection.AbstractMap`). Use `findClass` when abstract classes
      * are acceptable.
      *
      * @param fullName dotted fully-qualified name of the target class
      * @return the class symbol wrapped in `Maybe.Present`, or `Maybe.Absent`
      */
    def findConcreteClass(fullName: String)(using Frame): Maybe[Symbol.Class] < Sync =
        classpath.map(_.findConcreteClass(fullName))

    /** Find all class symbols whose simple (unqualified) name matches `simpleName`.
      *
      * Unlike `findClass`, which requires the fully-qualified dotted name, this method searches across all
      * packages. The result may contain multiple symbols when the same name appears in different packages
      * (e.g. `"List"` in `scala.collection.immutable` and `java.util`). The lookup is constant-time in the
      * classpath size.
      *
      * @param simpleName unqualified class name (e.g. `"List"`, `"Option"`)
      * @return all matching class symbols; empty `Chunk` when none are found
      */
    def findClassesByName(simpleName: String)(using Frame): Chunk[Symbol.Class] < Sync =
        classpath.map(_.findClassesByName(simpleName))

    /** Find a method symbol by owner fully-qualified name and simple method name.
      *
      * Resolves the owner symbol by `ownerFullName`, expects it to be a `Symbol.ClassLike`, then
      * scans the owner's `declarationIds` for the first `Symbol.Method` whose `simpleName`
      * equals `methodName`. Returns `Maybe.Absent` when the owner fully-qualified name is not found, when the
      * owner is not a class-like, or when no declared method with that simple name exists.
      *
      * When multiple overloads share the same name, the first one in declaration order is
      * returned. Use `declarations` or `members` followed by manual filtering when overload
      * discrimination is needed.
      *
      * @param ownerFullName dotted fully-qualified name of the class-like that owns the method
      * @param methodName    simple (unqualified) name of the method
      * @return the first matching method symbol, or `Maybe.Absent`
      */
    def findMethod(ownerFullName: String, methodName: String)(using Frame): Maybe[Symbol.Method] < Sync =
        classpath.map { classpath =>
            classpath.findSymbol(ownerFullName).flatMap {
                case cl: Symbol.ClassLike =>
                    import Name.asString
                    Maybe.fromOption(cl.declarationIds.flatMap { id =>
                        classpath.symbol(id) match
                            case Maybe.Present(m: Symbol.Method) if m.name.asString == methodName => Chunk(m)
                            case Maybe.Present(_)                                                 => Chunk.empty
                            case Maybe.Absent                                                     => Chunk.empty
                    }.headOption)
                case _: Symbol.Method       => Maybe.Absent
                case _: Symbol.Val          => Maybe.Absent
                case _: Symbol.Var          => Maybe.Absent
                case _: Symbol.Field        => Maybe.Absent
                case _: Symbol.TypeAlias    => Maybe.Absent
                case _: Symbol.OpaqueType   => Maybe.Absent
                case _: Symbol.AbstractType => Maybe.Absent
                case _: Symbol.TypeParam    => Maybe.Absent
                case _: Symbol.Parameter    => Maybe.Absent
                case _: Symbol.Package      => Maybe.Absent
            }
        }

    /** Require a class symbol by fully-qualified name, aborting when not found.
      *
      * `findClass(fullName)` with absence raised as `TastyError.NotFound`. The require variant when absence is
      * an unrecoverable error in the pipeline; use `findClass` when the caller wants to handle absence
      * explicitly.
      *
      * @param fullName dotted fully-qualified name of the expected class
      * @return the class symbol, or `Abort.fail(TastyError.NotFound(fullName))`
      */
    def requireClass(fullName: String)(using Frame): Symbol.Class < (Sync & Abort[TastyError]) =
        classpath.map(_.requireClass(fullName))

    /** Require a class-like symbol by fully-qualified name, aborting when not found.
      *
      * Equivalent to `findClassLike(fullName)` followed by an explicit check: if the result is
      * `Maybe.Absent`, the computation aborts with `TastyError.NotFound(fullName)`.
      *
      * Matches Class, Trait, Object, and EnumCase at the given fully-qualified name. Use `requireClass` when
      * only `Symbol.Class` is acceptable.
      *
      * @param fullName dotted fully-qualified name of the expected class-like symbol
      * @return the class-like symbol, or `Abort.fail(TastyError.NotFound(fullName))`
      */
    def requireClassLike(fullName: String)(using Frame): Symbol.ClassLike < (Sync & Abort[TastyError]) =
        classpath.map(_.requireClassLike(fullName))

    /** Require an object symbol by fully-qualified name, aborting when not found.
      *
      * Equivalent to `findObject(fullName)` followed by an explicit check: if the result is
      * `Maybe.Absent`, the computation aborts with `TastyError.NotFound(fullName)`.
      *
      * Accepts both source-form (`"mypackage.MyObject"`) and `$`-suffix binary-form fully-qualified names.
      *
      * @param fullName dotted source-form or `$`-suffixed binary-form fully-qualified name of the expected object
      * @return the object symbol, or `Abort.fail(TastyError.NotFound(fullName))`
      */
    def requireObject(fullName: String)(using Frame): Symbol.Object < (Sync & Abort[TastyError]) =
        classpath.map(_.requireObject(fullName))

    /** Require a trait symbol by fully-qualified name, aborting when not found.
      *
      * `findTrait(fullName)` with absence raised as `TastyError.NotFound`. Use when absence is
      * an unrecoverable error in the pipeline.
      *
      * @param fullName dotted fully-qualified name of the expected trait
      * @return the trait symbol, or `Abort.fail(TastyError.NotFound(fullName))`
      */
    def requireTrait(fullName: String)(using Frame): Symbol.Trait < (Sync & Abort[TastyError]) =
        classpath.map(_.requireTrait(fullName))

    /** Require any symbol by fully-qualified name, aborting when not found.
      *
      * Equivalent to `findSymbol(fullName)` followed by an explicit check: if the result is
      * `Maybe.Absent`, the computation aborts with `TastyError.NotFound(fullName)`.
      *
      * The most permissive require variant: succeeds for any registered symbol kind. Use the
      * typed variants (`requireClass`, `requireClassLike`, `requireObject`) when a specific
      * symbol subtype is expected.
      *
      * @param fullName dotted fully-qualified name of the expected symbol
      * @return the symbol, or `Abort.fail(TastyError.NotFound(fullName))`
      */
    def requireSymbol(fullName: String)(using Frame): Symbol < (Sync & Abort[TastyError]) =
        classpath.map(_.requireSymbol(fullName))

    /** Require a package symbol by fully-qualified name, aborting when not found.
      *
      * Equivalent to `findPackage(fullName)` followed by an explicit check: if the result is
      * `Maybe.Absent`, the computation aborts with `TastyError.NotFound(fullName)`.
      *
      * Useful in tools that assert that a specific package exists in the loaded classpath
      * (e.g., a static analysis pass that requires `scala.collection`).
      *
      * @param fullName dotted package name, or `""` for the root package
      * @return the package symbol, or `Abort.fail(TastyError.NotFound(fullName))`
      */
    def requirePackage(fullName: String)(using Frame): Symbol.Package < (Sync & Abort[TastyError]) =
        classpath.map(_.requirePackage(fullName))

    /** Require a method symbol by owner fully-qualified name and simple name, aborting when not found.
      *
      * Equivalent to `findMethod(ownerFullName, methodName)` followed by an explicit check: if the
      * result is `Maybe.Absent`, the computation aborts with
      * `TastyError.NotFound("ownerFullName.methodName")`.
      *
      * When multiple overloads share the same name, the first one in declaration order is
      * returned (same behaviour as `findMethod`).
      *
      * @param ownerFullName dotted fully-qualified name of the class-like that owns the method
      * @param methodName    simple (unqualified) name of the method
      * @return the first matching method symbol, or `Abort.fail(TastyError.NotFound(...))`
      */
    def requireMethod(ownerFullName: String, methodName: String)(using Frame): Symbol.Method < (Sync & Abort[TastyError]) =
        findMethod(ownerFullName, methodName).map {
            case Maybe.Present(m) => m
            case Maybe.Absent     => Abort.fail(TastyError.NotFound(s"$ownerFullName.$methodName"))
        }

    /** All class-like symbols in the loaded classpath.
      *
      * Returns every `Symbol.ClassLike` regardless of sub-kind: includes `Symbol.Class`,
      * `Symbol.Trait`, `Symbol.Object`, and `Symbol.EnumCase`. The result spans all packages
      * and all classpath roots supplied to `Tasty.withClasspath`.
      *
      * Use the narrower variants (`allClasses`, `allObjects`, `allTraits`) when only a single
      * kind is needed.
      *
      * @return all class-like symbols across the classpath; empty when the classpath is empty
      */
    def allClassLike(using Frame): Chunk[Symbol.ClassLike] < Sync =
        classpath.map(_.allClassLike)

    /** All class symbols in the loaded classpath.
      *
      * Returns every `Symbol.Class` across all packages and classpath roots. Does not include
      * `Symbol.Trait`, `Symbol.Object`, or `Symbol.EnumCase`; use `allClassLike` to include
      * those. Abstract classes are included; use `findConcreteClass` per fully-qualified name when concreteness
      * matters.
      *
      * The result is computed by filtering `classpath.symbols` on each call; for large classpaths
      * (tens of thousands of symbols) consider caching the result in the caller.
      *
      * @return all `Symbol.Class` instances across the classpath; empty when none are found
      */
    def allClasses(using Frame): Chunk[Symbol.Class] < Sync =
        classpath.map(_.allClasses)

    /** All object (module) symbols in the loaded classpath.
      *
      * Returns every `Symbol.Object` across all packages and classpath roots. Only Scala `object`
      * declarations contribute; Java classfiles never produce `Symbol.Object` (the `Symbol.Object` ADT
      * doc names this constraint explicitly).
      *
      * @return all `Symbol.Object` instances across the classpath; empty when none are found
      */
    def allObjects(using Frame): Chunk[Symbol.Object] < Sync =
        classpath.map(_.allObjects)

    /** All trait symbols in the loaded classpath.
      *
      * Returns every `Symbol.Trait` across all packages and classpath roots. Sealed traits,
      * open traits, and Java interfaces, which the loader normalizes to `Symbol.Trait`, are all included.
      *
      * @return all `Symbol.Trait` instances across the classpath; empty when none are found
      */
    def allTraits(using Frame): Chunk[Symbol.Trait] < Sync =
        classpath.map(_.allTraits)

    /** All method symbols in the loaded classpath.
      *
      * Returns every `Symbol.Method` across all owners and classpath roots. Includes
      * constructors (`<init>`), extension methods, and synthetic methods generated by the
      * compiler. Use `declarations` or `members` on a specific owner symbol to restrict
      * results to a single class or package.
      *
      * @return all `Symbol.Method` instances across the classpath; empty when none are found
      */
    def allMethods(using Frame): Chunk[Symbol.Method] < Sync =
        classpath.map(_.allMethods)

    /** All val symbols in the loaded classpath.
      *
      * Returns every `Symbol.Val` (immutable value definition) across all owners and
      * classpath roots. Includes top-level vals, member vals, and lazy vals. Does not
      * include `Symbol.Var`, `Symbol.Field`, or parameters.
      *
      * @return all `Symbol.Val` instances across the classpath; empty when none are found
      */
    def allVals(using Frame): Chunk[Symbol.Val] < Sync =
        classpath.map(_.allVals)

    /** All var symbols in the loaded classpath.
      *
      * Returns every `Symbol.Var` (mutable variable definition) across all owners and
      * classpath roots. Includes member vars but does not include `Symbol.Val` or
      * `Symbol.Field`. Java mutable fields are represented as `Symbol.Field`.
      *
      * @return all `Symbol.Var` instances across the classpath; empty when none are found
      */
    def allVars(using Frame): Chunk[Symbol.Var] < Sync =
        classpath.map(_.allVars)

    /** All field symbols in the loaded classpath.
      *
      * Returns every `Symbol.Field` across all owners and classpath roots. Fields are the
      * Java-level representation: raw instance/static fields from `.class` files decoded via
      * the Java class reader. Scala `var` and `val` members compiled to JVM fields appear
      * as `Symbol.Field` in the Java class model; `Symbol.Var` and `Symbol.Val` are the
      * TASTy-model equivalents from Scala sources.
      *
      * @return all `Symbol.Field` instances across the classpath; empty when none are found
      */
    def allFields(using Frame): Chunk[Symbol.Field] < Sync =
        classpath.map(_.allFields)

    /** All type declaration symbols in the loaded classpath.
      *
      * Aggregates `Symbol.TypeAlias`, `Symbol.OpaqueType`, and `Symbol.AbstractType` from
      * all owners and classpath roots. `Symbol.TypeParam` is excluded because type parameters
      * are part of their enclosing symbol's structure rather than independent declarations.
      *
      * The result is the union of `classpath.allTypeAliases`, `classpath.allOpaqueTypes`, and
      * `classpath.allAbstractTypes`, returned as a flat `Chunk[Symbol]`.
      *
      * @return all type declaration symbols across the classpath; empty when none are found
      */
    def allTypes(using Frame): Chunk[Symbol] < Sync =
        classpath.map { classpath =>
            // covariant upcast of Chunk[Symbol.TypeAlias/OpaqueType/AbstractType] to Chunk[Symbol]; safe because Chunk is covariant in its element type and all subtypes extend Symbol.
            classpath.allTypeAliases.asInstanceOf[Chunk[Symbol]] ++
                classpath.allOpaqueTypes.asInstanceOf[Chunk[Symbol]] ++
                classpath.allAbstractTypes.asInstanceOf[Chunk[Symbol]]
        }

    /** All package symbols in the loaded classpath.
      *
      * Returns every `Symbol.Package` synthesized during classpath loading. Package symbols
      * are derived from the directory structure of each root; the root package itself is
      * included and is registered under the empty string `""`.
      *
      * @return all `Symbol.Package` instances across the classpath; never empty (root is always present)
      */
    def allPackages(using Frame): Chunk[Symbol.Package] < Sync =
        classpath.map(_.allPackages)

    /** All symbols in the loaded classpath.
      *
      * Returns the raw `Classpath.symbols` chunk, which includes every symbol of every kind
      * loaded from the classpath roots. Use the narrower variants (`allClasses`, `allTraits`,
      * etc.) when only one symbol kind is needed.
      *
      * @return all symbols across the classpath; empty when the classpath is empty
      */
    def allSymbols(using Frame): Chunk[Symbol] < Sync =
        classpath.map(_.allSymbols)

    /** All type-alias symbols in the loaded classpath.
      *
      * Returns every `Symbol.TypeAlias` across all owners and classpath roots.
      *
      * @return all `Symbol.TypeAlias` instances across the classpath; empty when none are found
      */
    def allTypeAliases(using Frame): Chunk[Symbol.TypeAlias] < Sync =
        classpath.map(_.allTypeAliases)

    /** Return all symbols in the loaded classpath carrying the annotation with `fullName`.
      *
      * Walks every symbol once and matches its annotations against `fullName` (same matching logic as
      * `classpath.hasAnnotation`). For large classpaths the cost is linear in the number of loaded symbols; cache the
      * result in the caller when querying the same annotation repeatedly.
      *
      * @param fullName dotted fully-qualified name of the annotation class to scan for
      * @return all symbols carrying at least one annotation of the given fully-qualified name; empty when none
      */
    def symbolsAnnotatedWith(fullName: String)(using Frame): Chunk[Symbol] < Sync =
        classpath.map(_.symbolsAnnotatedWith(fullName))

    /** Decode the AST body of `symbol`.
      *
      * Returns `Maybe.Absent` for symbols with no AST body slice (packages, Java symbols, and any other kind
      * without recorded body bytes), and for symbols looked up outside a `withClasspath(roots, ...)` or
      * `withPickles` scope (where no body source handle is available).
      *
      * The first call for a given `symbol` decodes the bytes and caches the result; subsequent calls return the
      * cached tree without re-decoding. The cache lives on the active `DecodeContext`, so a second
      * `withClasspath` opens a fresh cache.
      */
    // A closed mmap arena surfaces as IllegalStateException("mmap arena closed") from
    // ByteView.Mapped.checkOpen. Distinguishing it by message keeps a genuine arena-close
    // (ClasspathClosed) apart from a decoder gap (MalformedSection), which also throws
    // IllegalStateException but for an unrelated reason.
    private def isArenaClosed(ise: IllegalStateException): Boolean =
        val msg = ise.getMessage
        msg != null && msg.contains("mmap arena closed")

    def bodyTree(symbol: Symbol)(using frame: Frame): Maybe[Tree] < (Sync & Abort[TastyError]) =
        bindingLocal.use { mbind =>
            val maybeCtx = mbind.flatMap(_.decodeCtx)
            if maybeCtx.isEmpty then Maybe.Absent
            else
                val ctx  = maybeCtx.get
                val blob = Maybe.fromOption(Option(ctx.bodyStore.get(symbol.id)))
                blob match
                    case Maybe.Absent => Maybe.Absent
                    case Maybe.Present(b) =>
                        val classpath = mbind.get.classpath
                        Sync.Unsafe.defer {
                            // Sync.Unsafe.defer is the only AllowUnsafe in the public query layer.
                            Maybe.fromOption(Option(ctx.bodyMemo.get(symbol.id))) match
                                case Maybe.Present(cached) =>
                                    cached match
                                        case Result.Success(t) => Maybe(t)
                                        case Result.Failure(e) => Abort.fail(e)
                                        // The memo only stores Success/Failure; no Result.Panic arm.
                                case Maybe.Absent =>
                                    val result: Result[TastyError, Tree] =
                                        // Upfront bounds validation. A malformed (bodyStart, bodyEnd) tuple is documented as
                                        // MalformedSection; computing the verdict from the integers themselves avoids relying
                                        // on a thrown ArrayIndexOutOfBoundsException. Scala.js converts the same bounds violation
                                        // into org.scalajs.linker.runtime.UndefinedBehaviorError (extends java.lang.Error, which
                                        // NonFatal explicitly filters out), so on JS the throw would escape every catch arm and
                                        // crash the test process. The upfront check is platform-agnostic by construction.
                                        val sectionLen = b.sectionBytes.size
                                        if b.bodyStart < 0 || b.bodyEnd > sectionLen || b.bodyStart > b.bodyEnd then
                                            Result.Failure(TastyError.MalformedSection(
                                                "ASTs",
                                                s"truncated body: bodyStart=${b.bodyStart}, bodyEnd=${b.bodyEnd}, sectionSize=$sectionLen",
                                                0L
                                            ))
                                        else
                                            try
                                                val syms = classpath.symbols
                                                Result.Success(kyo.internal.tasty.reader.TreeUnpickler.decodeSync(
                                                    b,
                                                    symbol,
                                                    idx => if idx >= 0 && idx < syms.size then syms(idx) else symbol
                                                ))
                                            catch
                                                // Reader gaps on in-bounds bytes (the upfront check already validated the
                                                // slice) are not corrupt input: the section is well-formed TASTy carrying a
                                                // construct the reader does not yet fully model, or a cursor desync. The
                                                // documented contract (see README "Errors and diagnostics": unrecognised tags
                                                // become Tree.Unknown; MalformedSection is only for corrupt bytes) is graceful
                                                // degradation, so these surface a top-level Tree.Unknown rather than aborting.
                                                case _: kyo.internal.tasty.reader.TreeUnpickler.DecodeException =>
                                                    Result.Success(Tasty.Tree.Unknown(0, 0))
                                                case _: ArrayIndexOutOfBoundsException =>
                                                    // A nested decoder read past its slice on a valid in-bounds body: a reader
                                                    // gap, not a truncated section. Degrade.
                                                    Result.Success(Tasty.Tree.Unknown(0, 0))
                                                case ise: IllegalStateException if isArenaClosed(ise) =>
                                                    // The backing mmap arena was closed before bodyTree ran (the scope
                                                    // finalizer flipped the closed flag); documented contract is ClasspathClosed.
                                                    Result.Failure(TastyError.ClasspathClosed(s"bodyTree(symbol.id=${symbol.id.value})"))
                                                case _: IllegalStateException =>
                                                    // An unhandled TASTy tag surfaced as IllegalStateException: a reader gap,
                                                    // not a closed arena. Degrade. (Unrecognised type tags arrive here as a
                                                    // DecodeException, converted from TastyErrorException in decodeSync.)
                                                    Result.Success(Tasty.Tree.Unknown(0, 0))
                                                // Genuinely malformed byte ENCODING (e.g. MalformedVarintException, where the
                                                // varint guard fires on too many continuation bytes) is corrupt input: per the
                                                // contract it aborts with MalformedSection. Fatal throwables (OOM,
                                                // InterruptedException) propagate untouched because NonFatal filters them out.
                                                case ex: Throwable if NonFatal(ex) =>
                                                    Result.Failure(TastyError.MalformedSection(
                                                        "ASTs",
                                                        s"${ex.getClass.getSimpleName}: ${ex.getMessage}",
                                                        0L
                                                    ))
                                        end if
                                    end result
                                    ctx.bodyMemo.put(symbol.id, result)
                                    result match
                                        case Result.Success(t) => Maybe(t)
                                        case Result.Failure(e) => Abort.fail(e)
                                        // The memo only stores Success/Failure; no Result.Panic arm.
                                    end match
                        }
                end match
            end if
        }
    end bodyTree

    // ── Nested types ────────────────────────────────────────────────────────
    // The vocabulary (SymbolId, Version, Name, Flags, ErrorMode, SubtypeVerdict,
    // Constant, Position, Annotation, Type, Tree, Symbol, Java, Pickle, Classpath)
    // referenced by the public API above. Read top-to-bottom for an overview of the
    // model; jump back up for callable entry points.

    // ── SymbolId ────────────────────────────────────────────────────────────

    /** Opaque integer handle that references a Symbol within a single Classpath instance.
      *
      * Obtained from `Classpath.symbol`, `Classpath.rootSymbolId`, `Classpath.topLevelClassIds`,
      * `Classpath.packageIds`, or any `SymbolId` field on `Symbol` or `Type`. User code cannot construct a
      * `SymbolId` from a raw `Int`.
      *
      * Two `SymbolId` values from the same classpath compare equal via `==` exactly when they refer to the
      * same symbol. `SymbolId` values are not portable across classpaths: distinct `Tasty.withClasspath` calls
      * produce independent id spaces, so to resolve a symbol from one classpath against another, look it up by
      * fully-qualified name via `findSymbol` / `findClass` / `findObject`.
      */
    opaque type SymbolId = Int

    object SymbolId:

        /** Internal smart constructor. Callable only from inside `kyo` (via `private[kyo]`); user code cannot invoke this. */
        private[kyo] def apply(i: Int): SymbolId = i

        extension (id: SymbolId)
            /** Internal accessor for the underlying integer value. Used by `Classpath.symbol(id)` to index the dense
              * `symbols: IndexedSeq[Symbol]` array.
              */
            private[kyo] def value: Int = id
        end extension

        given CanEqual[SymbolId, SymbolId] = CanEqual.canEqualAny

        /** Schema[SymbolId] delegates to Schema[Int], mirroring the Schema[Name] = Schema[String] precedent. */
        given schemaSymbolId: Schema[SymbolId] = summon[Schema[Int]]

    end SymbolId

    // ── Version ─────────────────────────────────────────────────────────────

    /** Three-part version number used by both the TASTy wire format and the kyo-tasty snapshot format.
      *
      * `major.minor.experimental` matches the layout that the dotty `TastyFormat` constants advertise: a TASTy
      * file declares its format version in the same shape, and the snapshot reader compares its embedded version
      * against the running kyo-tasty release. Two `Version` values are equal when all three components match.
      *
      * `Tasty.supportedTastyVersion` is the version this release targets. Pickles whose version falls outside
      * the supported range surface as `TastyError.UnsupportedVersion`, with the found and supported versions
      * carried as fields so the caller can report the mismatch.
      */
    final case class Version(major: Int, minor: Int, experimental: Int):
        /** Render the version as `"<major>.<minor>.<experimental>"` (e.g. `"28.8.0"`). */
        def show: String = s"$major.$minor.$experimental"

    /** The Scala 3 TASTy format version this kyo-tasty release targets.
      *
      * Pickles whose major version differs from this value fail to load with
      * `TastyError.UnsupportedVersion(found, supported)`. The minor version is the tail of a backwards-compatible
      * range: pickles with a minor at or below this number are accepted. Bump this value when picking up a new
      * Scala 3 minor release in CI; the `Version.show` rendering (e.g. `"28.8.0"`) is what `TastyError` carries
      * to the caller for human-readable diagnostics.
      */
    val supportedTastyVersion: Version = Version(28, 8, 0)

    // ── Names and flags ─────────────────────────────────────────────────────

    /** A name backed by a `String`.
      *
      * The opaque alias over `String` keeps `Name` distinct from raw `String` at the type level while
      * eliminating the per-name allocation and per-classpath intern table that the former `Interner.Entry`
      * representation required. Equality and ordering are `String` equality. `Schema[Name]` delegates to
      * `Schema[String]` so serialization round-trips byte-stably.
      */
    opaque type Name = String
    object Name:
        given CanEqual[Name, Name] = CanEqual.canEqualAny
        given Schema[Name]         = summon[Schema[String]]

        /** Internal factory: widen a raw `String` to `Name`. For use by kyo-internal unpicklers only. */
        private[kyo] def apply(s: String): Name = s

        extension (n: Name)
            /** Return the `String` form of this name. */
            def asString: String = n

            /** True when this name is the empty string. */
            def isEmpty: Boolean = n.isEmpty
        end extension
    end Name

    // ── Uuid ────────────────────────────────────────────────────────────────

    /** Canonical 36-character lowercase hex form of a 128-bit UUID, used by `TastyError.InconsistentClasspath`.
      *
      * Constructed via `Uuid.parse(input)`, which accepts uppercase or lowercase hex and normalises to lowercase.
      * Malformed input surfaces as `Result.fail(TastyError.InvalidUuid(input))`. Two `Uuid` values are equal when
      * their canonical strings agree.
      *
      * The internal `private[kyo]` companion helpers (`unsafeWrap`, `msb`, `lsb`) compose the wire-boundary
      * encoding for the snapshot reader and writer; they keep the JDK `java.util.UUID` round-trip scoped to this
      * companion so no JDK type leaks onto the public surface.
      */
    opaque type Uuid = String

    object Uuid:

        private val HexPattern = "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}".r

        /** Parse a 36-character hex UUID string. Accepts uppercase or lowercase; returns the canonical
          * lowercase form. Non-conforming input produces `Result.fail(TastyError.InvalidUuid(input))`.
          */
        def parse(input: String): Result[TastyError, Uuid] =
            if HexPattern.matches(input) then Result.succeed(input.toLowerCase(java.util.Locale.ROOT))
            else Result.fail(TastyError.InvalidUuid(input))

        /** Internal: wrap a canonical lowercase 36-character hex string as a `Uuid` without re-validating.
          * The caller MUST supply a canonical form (the wire-boundary reader builds it via
          * `new java.util.UUID(msb, lsb).toString`, which is canonical by construction).
          */
        private[kyo] def unsafeWrap(canonicalLowercaseHex: String): Uuid =
            canonicalLowercaseHex

        // Unsafe: internal wire-boundary helper; the JDK round-trip is scoped to this opaque companion so the
        // public surface does not expose `java.util.UUID`.
        private[kyo] def msb(uuid: Uuid): Long =
            java.util.UUID.fromString(uuid).getMostSignificantBits

        // Unsafe: internal wire-boundary helper; the JDK round-trip is scoped to this opaque companion so the
        // public surface does not expose `java.util.UUID`.
        private[kyo] def lsb(uuid: Uuid): Long =
            java.util.UUID.fromString(uuid).getLeastSignificantBits

        extension (uuid: Uuid)
            /** The underlying canonical hex string. */
            def asString: String = uuid

            /** Human-readable form: the canonical hex string itself. */
            def show: String = uuid
        end extension

        given CanEqual[Uuid, Uuid] = CanEqual.derived

        /** Schema[Uuid] delegates to Schema[String]; the wire encoding is the canonical hex form. */
        given Schema[Uuid] = summon[Schema[String]]
    end Uuid

    /** A packed set of `Flag` modifiers, treated as an immutable bitmask.
      *
      * Backed by an opaque `Long`: a single 64-bit word stores up to 64 distinct flag bits, so testing,
      * combining, and equality are all O(1) and allocate nothing. Each public modifier (`Flag.Inline`,
      * `Flag.Private`, ...) has a unique bit; `Flags` is the union of zero or more such bits.
      *
      * **Construction.** `Flags.empty` is the empty set. `Flags(flag, rest*)` constructs a set from one or
      * more `Flag` values. `flags1.union(flags2)` is the union of two sets. The underlying bits are exposed only
      * via `private[kyo]` accessors (`bits`, `Flags.fromBits`) for the internal unpicklers and snapshot
      * writer; user code should not depend on a specific bit layout because the layout is not stable across
      * kyo-tasty versions.
      *
      * **Querying.** `flags.contains(flag)` tests membership. `flags.isEmpty` returns true for the empty
      * set. `flags.show` renders a human-readable representation (`"Flags(Inline, Private)"`).
      *
      * **Equality.** Reference / value equality on the underlying `Long`; `CanEqual[Flags, Flags]` is
      * provided so `==` works without an import.
      */
    opaque type Flags = Long

    object Flags:
        /** The empty flag set (no modifiers). */
        val empty: Flags = 0L

        /** Combine one or more flags into a `Flags` value. */
        def apply(head: Flag, rest: Flag*): Flags =
            var b = head.bit
            rest.foreach(f => b |= f.bit)
            b
        end apply

        /** Construct a `Flags` directly from its underlying bitmask. For use by kyo-internal
          * unpicklers and snapshot reader/writer that need to materialise an accumulated mask.
          */
        private[kyo] def fromBits(bits: Long): Flags = bits

        /** Reference equality on the underlying Long; safe because Flags is a pure bitmask. */
        given CanEqual[Flags, Flags] = CanEqual.canEqualAny
    end Flags

    /** Public operations on [[Flags]]. Defined at `Tasty` scope so they are in implicit scope
      * for any code that already references `Tasty.Flags`, mirroring how nested opaque-type
      * extensions are surfaced in this file.
      */
    extension (flags: Flags)
        /** True when `flag`'s bit is set in this flag set. */
        def contains(flag: Flag): Boolean = (flags & flag.bit) != 0L

        /** Union of two flag sets. */
        def union(other: Flags): Flags = flags | other

        /** The raw bitmask. Used by the internal snapshot writer and any other kyo-internal
          * code that must persist the flag set; not part of the public API.
          */
        private[kyo] def bits: Long = flags

        /** True when no flag bits are set. Equivalent to `flags == Flags.empty`. */
        def isEmpty: Boolean = flags == 0L

        /** Human-readable representation: `Flags.empty.show == "Flags()"`. */
        @scala.annotation.targetName("flagsShow")
        def show: String =
            val sb       = new java.lang.StringBuilder("Flags(")
            var firstOne = true
            var i        = 0
            while i < Flag.values.length do
                val f = Flag.values(i)
                if (flags & f.bit) != 0L then
                    if firstOne then firstOne = false
                    else discard(sb.append(", "))
                    discard(sb.append(f.toString))
                end if
                i += 1
            end while
            sb.append(')').toString
        end show
    end extension

    /** A single modifier flag declared on a `Symbol`.
      *
      * Each case (`Inline`, `Private`, `Protected`, `Final`, `Sealed`, `Abstract`, `Implicit`,
      * `Given`, `Opaque`, `Case`, `Module`, `Synthetic`, `JavaDefined`, ...) is a distinct enum
      * value carrying a unique single-bit `Long` in its `bit` field. The full 45-case set covers
      * every Scala 3 source modifier plus the TASTy and JVM origin markers (`JavaDefined`,
      * `JavaRecord`, `Scala2`, `Synthetic`, `Tracked`, ...) so callers can faithfully reflect
      * the original declaration.
      *
      * **Usage.** A `Flag` is rarely used in isolation; the typical pattern is
      * `flags.contains(Flag.X)` against a `Flags` set or `Flags(Flag.X, Flag.Y)` to construct
      * one. The case name (e.g. `Flag.Inline.toString == "Inline"`) serves as the human-readable
      * label used by `Flags.show`.
      *
      * **Exhaustive matching.** Because `Flag` is a sealed `enum`, a `match` on a `Flag` value
      * that omits any of the 45 cases produces a compile-time non-exhaustive-match warning under
      * `-Wnonunit-statement -Werror`, allowing callers to detect new flag additions at compile
      * time.
      *
      * **Equality.** `CanEqual[Flag, Flag]` is derived so `==` works without an import; two
      * `Flag` values are equal iff they are the same enum case (i.e. carry the same `bit`).
      */
    enum Flag(val bit: Long) derives Schema, CanEqual:
        case Inline        extends Flag(1L << 0)
        case Private       extends Flag(1L << 1)
        case Protected     extends Flag(1L << 2)
        case Public        extends Flag(1L << 3)
        case Final         extends Flag(1L << 4)
        case Sealed        extends Flag(1L << 5)
        case Abstract      extends Flag(1L << 6)
        case Given         extends Flag(1L << 7)
        case Implicit      extends Flag(1L << 8)
        case Opaque        extends Flag(1L << 9)
        case Case          extends Flag(1L << 10)
        case Module        extends Flag(1L << 11)
        case Synthetic     extends Flag(1L << 12)
        case JavaDefined   extends Flag(1L << 13)
        case Enum          extends Flag(1L << 14)
        case JavaRecord    extends Flag(1L << 15)
        case Open          extends Flag(1L << 16)
        case ParamAccessor extends Flag(1L << 17)
        case Lazy          extends Flag(1L << 18)
        case Override      extends Flag(1L << 19)
        case Mutable       extends Flag(1L << 20)
        case Erased        extends Flag(1L << 21)
        case Tracked       extends Flag(1L << 22)
        case Tailrec       extends Flag(1L << 23)
        case Infix         extends Flag(1L << 24)
        case Transparent   extends Flag(1L << 25)
        case Trait         extends Flag(1L << 26)
        case CaseAccessor  extends Flag(1L << 27)
        case FieldAccessor extends Flag(1L << 28)
        case Macro         extends Flag(1L << 29)
        case InlineProxy   extends Flag(1L << 30)
        case Extension     extends Flag(1L << 31)
        case Exported      extends Flag(1L << 32)
        case Covariant     extends Flag(1L << 33)
        case Contravariant extends Flag(1L << 34)
        case HasDefault    extends Flag(1L << 35)
        case Stable        extends Flag(1L << 36)
        case Local         extends Flag(1L << 37)
        case Artifact      extends Flag(1L << 38)
        case Invisible     extends Flag(1L << 39)
        case Into          extends Flag(1L << 40)
        case ParamSetter   extends Flag(1L << 41)
        case ParamAlias    extends Flag(1L << 42)
        case Static        extends Flag(1L << 43)
        case Scala2        extends Flag(1L << 44)
    end Flag

    /** Public operations on [[Flag]]. */
    extension (flag: Flag)
        /** Human-readable flag name (the enum case name, e.g. `"Inline"`). */
        @scala.annotation.targetName("flagShow")
        def show: String = flag.toString
    end extension

    // ── Error mode ───────────────────────────────────────────────────────────

    /** Controls error handling during classpath open.
      *
      * Passed to `Tasty.withClasspath` (and the cached variant) to select between a tolerant load and an early
      * abort. The mode only governs decode errors found while walking the classpath; missing entries that
      * surface later (a fully-qualified name that resolves to no symbol, a subtype check that touches an unresolved parent)
      * are reported through their own return shapes (`Maybe`, `SubtypeVerdict.Indeterminate`) regardless of mode.
      *
      *   - `SoftFail`: decode errors accumulate in `classpath.errors`; the classpath is returned regardless and
      *     all subsequent queries operate on the best-effort symbol set. Use this for IDE / tooling paths
      *     where progress is preferable to total failure.
      *   - `FailFast`: any decode error immediately raises `Abort[TastyError]` from `init`. Use this for
      *     batch tools (CI checks, codegen) where a malformed classpath should abort the run.
      *
      * Equality is structural via `derives CanEqual`.
      */
    enum ErrorMode derives CanEqual:
        case SoftFail
        case FailFast
    end ErrorMode

    // ── Subtype verdict ──────────────────────────────────────────────────────

    /** Three-valued result of a subtype check.
      *
      * `Sub` and `NotSub` are the definitive verdicts. `Indeterminate` means the verdict is not decidable
      * from the current classpath (recursion exhausted, or an irreducible `Or` / `And` shape with mixed
      * `Sub` / `NotSub` children). Unhandled parent-walk shapes do NOT collapse into `Indeterminate`; they
      * surface as `TastyError.UnhandledSubtypingCase` in `classpath.errors`.
      *
      * Combining verdicts: `combineAnd` returns `NotSub` if any operand is `NotSub`, `Indeterminate` if any
      * is indeterminate and none is `NotSub`, and `Sub` if all are `Sub`. `combineOr` returns `Sub` if any
      * operand is `Sub`, `Indeterminate` if any is indeterminate and none is `Sub`, and `NotSub` otherwise.
      */
    enum SubtypeVerdict derives Schema, CanEqual:
        case Sub, NotSub, Indeterminate
    end SubtypeVerdict

    // ── Constants and annotations ───────────────────────────────────────────

    /** Literal constant payload used inside `Type.ConstantType` and `Tree.Literal`.
      *
      * `Constant` is a sealed enum of typed literals: every Scala primitive (`IntConst`, `LongConst`,
      * `FloatConst`, `DoubleConst`, `BooleanConst`, `CharConst`, `ByteConst`, `ShortConst`), strings
      * (`StringConst`), `()` (`UnitConst`), `null` (`NullConst`), and class literals (`ClassConst(tpe)`).
      *
      * **Naming.** The `*Const` suffix is intentional and distinguishes these from `quoted.reflect.Constant`
      * cases (which use the `*Constant` suffix). Do not confuse the two; they cover different value spaces.
      * Literal values are not their own `Type` cases: a Scala constant type like `42` appears as
      * `Type.ConstantType(IntConst(42))`, not as a top-level `Type.IntConst`.
      *
      * **Rendering.** `show` returns a Scala-source-shaped representation: strings are quoted, longs get the
      * `L` suffix, floats get the `f` suffix, `()` and `null` render as themselves, class literals render as
      * `classOf[T]`. Equality and hashing are structural via `derives CanEqual`.
      */
    enum Constant derives Schema, CanEqual:
        case StringConst(s: String)
        case IntConst(i: Int)
        case LongConst(l: Long)
        case FloatConst(f: Float)
        case DoubleConst(d: Double)
        case BooleanConst(b: Boolean)
        case CharConst(c: Char)
        case ByteConst(b: Byte)
        case ShortConst(s: Short)
        case UnitConst
        case NullConst
        case ClassConst(tpe: Type)

        /** Human-readable representation. Pure; requires no Classpath. */
        def show: String = this match
            case StringConst(s) => Constant.escapeStringLiteral(s)
            case IntConst(i)    => i.toString
            case LongConst(l)   => l.toString + "L"
            case FloatConst(f) =>
                if f.isNaN then "Float.NaN"
                else if f == Float.PositiveInfinity then "Float.PositiveInfinity"
                else if f == Float.NegativeInfinity then "Float.NegativeInfinity"
                else f.toString + "f"
            case DoubleConst(d) =>
                if d.isNaN then "Double.NaN"
                else if d == Double.PositiveInfinity then "Double.PositiveInfinity"
                else if d == Double.NegativeInfinity then "Double.NegativeInfinity"
                else d.toString
            case BooleanConst(b) => b.toString
            case CharConst(c)    => Constant.escapeCharLiteral(c)
            case ByteConst(b)    => b.toString
            case ShortConst(s)   => s.toString
            case UnitConst       => "()"
            case NullConst       => "null"
            case ClassConst(t)   => "classOf[" + Constant.classConstTypeShow(t) + "]"
        end show
    end Constant

    object Constant:

        /** Renders a String value as a Scala string literal, escaping all characters that would
          * make the literal unparseable. Handles: double-quote, backslash, newline, carriage
          * return, tab, backspace, and form-feed.
          */
        private[kyo] def escapeStringLiteral(s: String): String =
            val sb = new java.lang.StringBuilder("\"")
            s.foreach {
                case '"'  => sb.append("\\\"")
                case '\\' => sb.append("\\\\")
                case '\n' => sb.append("\\n")
                case '\r' => sb.append("\\r")
                case '\t' => sb.append("\\t")
                case '\b' => sb.append("\\b")
                case '\f' => sb.append("\\f")
                case c    => sb.append(c)
            }
            sb.append('"')
            sb.toString
        end escapeStringLiteral

        /** Renders a Char value as a Scala character literal, escaping single-quote, backslash,
          * and the same control characters as escapeStringLiteral.
          */
        private[kyo] def escapeCharLiteral(c: Char): String =
            val inner = c match
                case '\'' => "\\'"
                case '\\' => "\\\\"
                case '\n' => "\\n"
                case '\r' => "\\r"
                case '\t' => "\\t"
                case '\b' => "\\b"
                case '\f' => "\\f"
                case ch   => ch.toString
            "'" + inner + "'"
        end escapeCharLiteral

        /** Renders a Type for use inside classOf[...]. Since Constant.show is pure and has no
          * Classpath, Named references cannot be resolved to their fully-qualified name. An unresolved
          * Type.Named(id) emits the placeholder <id:N>. Other common Type cases
          * are rendered with their Scala source shape; the catch-all preserves existing
          * behaviour for any new Type cases added in the future.
          */
        private[kyo] def classConstTypeShow(t: Type): String = t match
            case Type.Named(id) => s"<id:${id.value}>"
            case Type.Any       => "Any"
            case Type.Nothing   => "Nothing"
            case Type.Applied(base, args) =>
                val baseStr = classConstTypeShow(base)
                val argsStr = args.iterator.map(classConstTypeShow).mkString(", ")
                s"$baseStr[$argsStr]"
            case Type.Tuple(elems) =>
                "(" + elems.iterator.map(classConstTypeShow).mkString(", ") + ")"
            case Type.Function(params, result) =>
                val ps = params.iterator.map(classConstTypeShow).mkString(", ")
                val r  = classConstTypeShow(result)
                if params.size == 1 then s"${classConstTypeShow(params(0))} => $r"
                else s"($ps) => $r"
            case other => other.toString
        end classConstTypeShow
    end Constant

    /** Source position attached to a TASTy symbol.
      *
      * Carried by `Symbol.sourcePosition: Maybe[Position]`. `Absent` for classfile-sourced symbols and for
      * TASTy symbols loaded from a file without a Positions section; otherwise populated from the TASTy
      * Positions table during classpath open. Positions point at the symbol's declaration site, not at
      * every reference to it. There is no per-tree positional information; the public model deliberately
      * stops at the symbol level.
      *
      * `sourceFile` is the file name from the Attributes section, exactly as recorded in the pickle
      * (no path normalisation). A `Position` is only constructed when the SOURCEFILE attribute is present;
      * absence of source information is represented by `Symbol.sourcePosition == Maybe.Absent`, not by a
      * sentinel string inside this case class. `line` and `column` are 1-based (line 1 is the first line
      * of the file; column 1 is the first character of the line). A column of 0 is possible when the
      * underlying TASTy entry carried no column information.
      *
      * Equality is structural across all three fields (case class auto-generation).
      */
    final case class Position(sourceFile: String, line: Int, column: Int) derives Schema, CanEqual:
        /** Human-readable representation: `file:line:column`. */
        def show: String = s"$sourceFile:$line:$column"
    end Position

    /** Common base for all annotation kinds produced by the kyo-tasty loader.
      *
      * Two concrete subtypes exist: `Annotation` for Scala-source annotations decoded from TASTy, and
      * `Java.Annotation` for Java-retention annotations decoded from `.class` files. Both carry an
      * `annotationFullName` field that holds the dotted fully-qualified class name of the annotation
      * interface (e.g. `"scala.deprecated"` or `"java.lang.Override"`).
      *
      * The sealed hierarchy enables exhaustive pattern matching without a catch-all arm:
      * ```scala
      *   annotation match
      *     case a: Tasty.Annotation      => a.arguments
      *     case a: Tasty.Java.Annotation => a.values
      * ```
      *
      * `annotationFullName` is consistent with the fully-qualified name that `Classpath.findAnnotation` and
      * `Classpath.hasAnnotation` accept as their lookup key, so callers can compare without resolving
      * through `annotationType` or `annotationClass`.
      *
      * Equality across the hierarchy follows structural case-class equality on each concrete subtype.
      * A value of type `AnnotationLike` can be compared to another only when both have the same runtime
      * type; the `derives CanEqual` on the sealed base enables cross-subtype structural comparisons.
      */
    sealed trait AnnotationLike derives CanEqual:
        /** Dotted fully-qualified name of the annotation class (e.g. `"scala.deprecated"`).
          *
          * For `Annotation`, this is populated from `annotationType` when the symbol has been
          * fully loaded by a `Classpath`. For `Java.Annotation`, this is derived from the JVM
          * field descriptor at decode time. Callers should read this field only from symbols
          * held by a fully-loaded `Classpath`; the value is `Tasty.Name("")` otherwise.
          */
        def annotationFullName: Name
    end AnnotationLike

    /** A Scala annotation as it appears on a `Type.Annotated` and, indirectly, on a `Symbol`.
      *
      * Annotations attach to types in the Scala 3 model; a symbol's annotations are reachable by walking its
      * `declaredType` and collecting the `Type.Annotated` wrappers. `annotationType` carries the annotation
      * class as a `Type` (typically `Type.Named` to the annotation class symbol). `arguments` carries the
      * argument trees in source order, each a `Tree.Literal`, `Tree.Apply`, `Tree.Select`, or other AST
      * shape consistent with what was written at the annotation call site.
      *
      * `annotationFullName` holds the dotted fully-qualified name of the annotation class (e.g. `"scala.deprecated"`),
      * computed during classpath finalization from `annotationType`. It matches the key accepted by
      * `Classpath.findAnnotation` and `Classpath.hasAnnotation`.
      *
      * A decode failure produces `arguments = Chunk.empty` and surfaces a `TastyError.MalformedSection` in
      * `classpath.errors`. `annotationType` may reference a placeholder symbol when the annotation class itself is
      * not in the loaded classpath.
      *
      * Equality and hashing are structural over all three fields.
      */
    final case class Annotation(annotationType: Type, arguments: Chunk[Tree], annotationFullName: Name)
        extends AnnotationLike derives Schema, CanEqual

    // ── Type ADT ────────────────────────────────────────────────────────────

    /** Structural representation of a Scala type as it appears in TASTy.
      *
      * Sealed enum of around two dozen cases covering the full Scala 3 type language: nominal references
      * (`Named`, `TermRef`, `TypeRef`), type constructors (`Applied`, `TypeLambda`, `Function`,
      * `ContextFunction`, `Tuple`), composite shapes (`AndType`, `OrType`, `Refinement`, `Annotated`), self /
      * super / this references (`ThisType`, `SuperType`, `ParamRef`), bounds and wildcards (`Bounds`,
      * `Wildcard`), and match-type machinery (`MatchType`, `MatchCase`, `Skolem`, `FlexibleType`, `Rec`,
      * `RecThis`). Constant payloads (`Type.ConstantType`) carry a `Constant` value.
      *
      * Structurally equal `Type` values produced by the loader share the same reference, so reference
      * equality is sound for cache keys on values returned by `Tasty.*` queries. `Type` values constructed
      * directly by user code do not share this property.
      *
      * `Nothing`, `Any`, and `Unknown` are distinct enum cases, not magic `Named` ids; pattern matching is
      * the canonical way to detect them.
      *
      * `children` returns first-level structural children; `foreach` walks pre-order; `classpath.typeSymbol`
      * resolves the head symbol of a `Named`; `classpath.typeShow` renders a Scala-source-shaped string.
      * `classpath.isSubtypeOf` performs a structural subtype check returning a three-way `SubtypeVerdict`.
      */
    enum Type derives Schema, CanEqual:

        /** Reference to a symbol by its id. Wire tag: `TYPEREFdirect` (105) and related forms.
          *
          * The most common type constructor: names a class, trait, object, type alias, or type
          * parameter by its `SymbolId`. Resolves via `classpath.symbol(symbolId)`. A `Named` whose
          * `symbolId.value < 0` denotes an unresolvable reference that survived loading.
          *
          * Callers performing type comparison should use `classpath.isSubtypeOf` rather than comparing
          * `symbolId` values directly, as parent-chain resolution requires the `Classpath`.
          */
        case Named(symbolId: SymbolId)

        /** Reference to a term-level path as a type. Wire tag: `TERMREF` (111) and variants.
          *
          * Appears where a stable term path is used as a type, most commonly in path-dependent
          * types (`p.type`) and singleton bounds. `prefix` is the qualifier type; `name` is the
          * term name. Semantically distinct from `TypeRef` (which carries a type-level reference).
          *
          * Callers that need to match annotation fully-qualified names should use `typeFullNameString`, which handles
          * both `TermRef` and `TypeRef` transparently.
          */
        case TermRef(prefix: Type, name: Name)

        /** Type application: `F[A1, ..., AN]`. Wire tag: `APPLIEDtype` (69).
          *
          * `base` is the constructor type (often a `Named`); `args` are the type arguments in declaration
          * order. Applications of `scala.FunctionN`, `scala.ContextFunctionN`, `scala.TupleN`, and
          * `scala.Array` decode into their dedicated cases (`Function`, `ContextFunction`, `Tuple`, `Array`)
          * rather than `Applied`; callers see `Applied` only for forms that do not match a dedicated case.
          */
        case Applied(base: Type, args: Chunk[Type])

        /** Higher-kinded type lambda: `[X1, ..., XN] =>> body`. Wire tag: `TYPELAMBDAtype` (75).
          *
          * Represents a type-level function abstraction. `paramIds` holds the `SymbolId`s of the lambda's
          * parameter symbols; `body` is the result type, which may contain `ParamRef` nodes pointing back
          * into `paramIds`. Type lambdas arise from type members with type parameters and from certain
          * higher-kinded class definitions.
          */
        case TypeLambda(paramIds: Chunk[SymbolId], body: Type)

        /** Plain function type: `(A1, ..., AN) => R`.
          *
          * Dedicated case for `scala.FunctionN`. Distinct from `ContextFunction` (`?=>`); callers can
          * pattern-match the two forms without testing a Boolean flag.
          *
          * `params` holds the argument types in declaration order; `result` is the return type. An empty
          * `params` represents a `Function0` (thunk).
          */
        case Function(params: Chunk[Type], result: Type)

        /** Context function type: `(A1, ..., AN) ?=> R`.
          *
          * Dedicated case for `scala.ContextFunctionN`, structurally disjoint from `Type.Function`. Methods
          * decoded from `scala.ContextFunctionN` produce this case; methods decoded from `scala.FunctionN`
          * produce `Type.Function`. `params` holds the implicit parameter types; `result` is the return
          * type.
          */
        case ContextFunction(params: Chunk[Type], result: Type)

        /** Tuple type: `(A1, ..., AN)`.
          *
          * Dedicated case for `scala.TupleN` (N >= 2). `elements` holds the component types in declaration
          * order.
          *
          * A `Tuple` with zero elements is never produced; `Unit` decodes to `Named(scala.Unit)`.
          */
        case Tuple(elements: Chunk[Type])

        /** Call-by-name parameter type: `=> T`.
          *
          * The argument expression is not evaluated at the call site but on each use. `underlying` is the
          * parameter type `T`. Distinct from `Repeated` (varargs). Source shape: `def f(x: => T)`.
          */
        case ByName(underlying: Type)

        /** Varargs element type: `T*`.
          *
          * Represents the last parameter of a varargs method. `elem` is the element type `T`. Distinct from
          * `ByName` (call-by-name) and `Array` (Java arrays). Source shape: `def f(xs: T*)`.
          */
        case Repeated(elem: Type)

        /** Java array type: `Array[T]`.
          *
          * Dedicated case for `scala.Array` and Java array types, separated from `Applied` so callers can
          * pattern-match without resolving the constructor fully-qualified name. `elem` is the element type.
          */
        case Array(elem: Type)

        /** Structural refinement type: `P { def name: I }`. Wire tag: `REFINEDtype`.
          *
          * A structural type adding a member `name` with info type `I` to parent type `P`. Commonly
          * appears in type-class evidence derivation, anonymous structural types, and object literals.
          *
          * `parent` is the base type, `name` is the declared member name, `info` is the member's
          * declared type. For abstract member refinements, `info` is a `Type.Bounds` value; for
          * concrete member refinements, `info` is the member type directly.
          */
        case Refinement(parent: Type, name: Name, info: Type)

        /** Recursive type binding: `mu X. parent[X]`.
          *
          * Marks the binding site of a recursive type variable, always paired with one or more `RecThis`
          * references inside `parent` that point back to this `Rec` node. `Rec(parent)` reads as the type
          * `mu X. parent[X]` where `X` is any `RecThis` inside `parent`.
          */
        case Rec(parent: Type)

        /** Back-reference inside a `Rec` type. Wire tag: `RECthis`.
          *
          * `rec` is the enclosing `Rec` node this reference points back to. Callers should treat
          * `RecThis` as an opaque back-pointer; traversal via `visit` includes the cycle guard. Direct
          * structural comparison of `RecThis` values requires the enclosing `Rec` context (see
          * `Subtyping.typeEquivAlpha`).
          *
          * `RecThis` never appears outside a `Rec.parent` subtree in well-formed TASTy.
          */
        case RecThis(rec: Type)

        /** Intersection type: `A & B`. Wire tag: `ANDtype`.
          *
          * `left` and `right` are the operands. `AndType(scala.Singleton, X)` is normalised to `X` (the
          * singleton bound collapses).
          *
          * In `isSubtypeOf`, an `AndType` on the supertype side requires `Sub` for both components. Source
          * shape: `A & B`.
          */
        case AndType(left: Type, right: Type)

        /** Union type: `A | B`.
          *
          * `left` and `right` are the operands. In `isSubtypeOf`, an `OrType` on the subtype side requires
          * `Sub` for either component.
          */
        case OrType(left: Type, right: Type)

        /** Annotated type: `T @annotation`. Wire tag: `ANNOTATEDtype` or `ANNOTATEDtpt`.
          *
          * `underlying` is the base type, `annotation` is the `Tasty.Annotation` carrying the
          * annotation class and arguments. Primary uses include
          * `@scala.annotation.internal.Repeated` on varargs (decoded to `Type.Repeated` before
          * reaching the user surface) and user-visible annotations like `@uncheckedVariance`.
          *
          * Callers interested only in the structural type should strip the `Annotated` wrapper.
          */
        case Annotated(underlying: Type, annotation: Annotation)

        /** Literal singleton type. Wire tag: `SINGLETONtpt` or `CONSTANTtype`.
          *
          * `value` is the `Tasty.Constant` payload. Represents a compile-time constant promoted to a
          * type. Examples: `42.type`, `"hello".type`, `true.type`. Appears as a type argument or
          * return type in code that uses literal types.
          *
          * Callers should extract the `Constant` via `value` and dispatch on `Constant` variants to
          * inspect the payload.
          */
        case ConstantType(value: Constant)

        /** Self-type reference `C.this` for class `C`. Wire tag: `THIStpe`.
          *
          * `clsId` is the `SymbolId` of the enclosing class. Resolves to the actual
          * `Symbol.ClassLike` via `classpath.symbol(clsId)`. Distinct from `SuperType`, which carries both
          * self and mixin components.
          *
          * Appears inside the bodies of class members where `this` is used as a type (common in
          * self-referential type bounds and type members).
          */
        case ThisType(clsId: SymbolId)

        /** Super-type reference `C.super[M]`. Wire tag: `SUPERtype`.
          *
          * `self` is the `ThisType` of the enclosing class; `mixin` is the type of the linearized
          * parent being referenced (the explicit `[M]` in `super[M]`, or the first parent when no
          * explicit mixin is given). Used when a class overrides a method and calls
          * `super.method(...)`.
          *
          * Distinct from `ThisType` (which carries only the self reference).
          */
        case SuperType(self: Type, mixin: Type)

        /** Reference to a type-lambda or method type parameter by position. Wire tag: `PARAMtype`.
          *
          * `binderId` is the `SymbolId` of the enclosing binder's first parameter symbol;
          * `idx` is the zero-based index of the parameter within that binder. Used inside
          * `TypeLambda.body` to refer back to the lambda's parameters without creating a recursive
          * cycle.
          *
          * Callers matching inside a `TypeLambda` should use `paramIds(idx)` on the enclosing
          * `TypeLambda` to resolve the symbol the `ParamRef` points to.
          */
        case ParamRef(binderId: SymbolId, idx: Int)

        /** Bounded wildcard type: `_ >: lo <: hi`. Wire tag: `WILDCARDtype`.
          *
          * Used for existential type arguments and wildcard imports. `lo` and `hi` are the bounds;
          * `Type.Nothing` stands in for an absent lower bound and `Type.Any` for an absent upper
          * bound.
          *
          * Distinct from `Bounds` (a bounds declaration at an abstract-type site, not a wildcard
          * argument). The corresponding Scala source shape is `_ >: A <: B`.
          */
        case Wildcard(lo: Type, hi: Type)

        /** Skolem (existential witness) type. Wire tag: `SKOLEMtype`.
          *
          * `underlying` is the type being approximated. Introduced during type inference; represents
          * an existentially-bound type variable that has been given a concrete identity for
          * unification purposes. Rarely appears in decoded TASTy; mostly an artifact of the Scala 3
          * compiler's inference machinery leaking into serialized form for complex GADTs or
          * existential patterns.
          *
          * Callers should generally treat `Skolem` as opaque or unwrap via `underlying`.
          */
        case Skolem(underlying: Type)

        /** Match type: `scrutinee match { cases }`. Wire tag: `MATCHtype`.
          *
          * `bound` is the declared upper bound of the match type (`Type.Any` if absent), `scrutinee`
          * is the type being matched, `cases` is a `Chunk[Type]` where each element is a
          * `Type.MatchCase`. Exhaustive matches over `Type` must handle both `MatchType` and
          * `MatchCase`. The `MatchCase` sub-case carries one arm; `MatchType` carries the full match
          * expression.
          *
          * @see Type.MatchCase
          */
        case MatchType(bound: Type, scrutinee: Type, cases: Chunk[Type])

        /** Flexible (Java-nullable) type: `T!`. Wire tag: `FLEXIBLEtype`.
          *
          * `underlying` is the Scala type `T`. Flexible types appear when the Scala 3 compiler
          * decodes a Java class whose field or return type has no nullability annotation; the result
          * is `FlexibleType(T)` rather than `T | Null`. Callers doing Java interop that need to
          * handle Java-origin `null` should check for this wrapper and treat the value as nullable.
          *
          * Rarely seen in pure Scala codebases; mostly an artifact of Java class file decoding.
          */
        case FlexibleType(underlying: Type)

        /** Match-type case: `pat => rhs`. Wire-level TASTy tag MATCHCASEtype (192).
          *
          * First-class ADT case for a single match-type arm. Exhaustive matches over `Type` should add this
          * case; existing matches that traverse via `Type.children` remain total via the wildcard fallback.
          */
        case MatchCase(pat: Type, rhs: Type)

        /** Match-type pattern binder: the `t` in a `case List[t] => ...` match-type case. Wire tag BIND (150).
          *
          * Symmetric to `Tree.Bind` in term position: `name` is the bound type variable and `pattern` is the
          * bound pattern type. The bound variable's info bounds are not retained, matching `Tree.Bind`.
          * Exhaustive matches over `Type` must handle this case.
          */
        case Bind(name: Name, pattern: Type)

        /** Type-position reference. Wire tag TYPEREF (117).
          *
          * Semantically distinct from `TermRef` (term-position reference). Callers that need to distinguish
          * type references from term references should match on `TypeRef`. `typeFullNameString` handles both
          * `TermRef` and `TypeRef` for annotation fully-qualified name matching.
          */
        case TypeRef(qual: Type, name: Name)

        /** Explicit type bounds. Wire tags TYPEBOUNDS (163) and TYPEBOUNDStpt (164).
          *
          * Represents `lo .. hi` as declared in source. Distinct from `Type.Wildcard`, which carries the
          * bounds of an unspecified type argument (`_ <: A`); `Type.Bounds` carries the bounds at an
          * explicit-bounds declaration site.
          */
        case Bounds(lo: Type, hi: Type)

        /** Sentinel lower-bound type used in `TypeBounds` when no concrete lower bound is known. */
        case Nothing

        /** Sentinel upper-bound type used in `TypeBounds` when no concrete upper bound is known. */
        case Any

        /** Visit each direct child of this Type without allocating an intermediate Chunk.
          *
          * Used internally by `children` and `foreach` so the hot traversal path does not materialize a
          * Chunk per node.
          */
        private[kyo] def visit(f: Type => Unit): Unit = this match
            case Applied(base, args) =>
                f(base); args.foreach(f)
            case TypeLambda(_, body) => f(body)
            case Function(params, ret) =>
                params.foreach(f); f(ret)
            case ContextFunction(params, ret) =>
                params.foreach(f); f(ret)
            case Tuple(elements) => elements.foreach(f)
            case ByName(t)       => f(t)
            case Repeated(t)     => f(t)
            case Array(t)        => f(t)
            case Refinement(p, _, i) =>
                f(p); f(i)
            case Rec(p)       => f(p)
            case RecThis(rec) => f(rec)
            case AndType(l, r) =>
                f(l); f(r)
            case OrType(l, r) =>
                f(l); f(r)
            case Annotated(u, _) => f(u)
            case SuperType(s, m) =>
                f(s); f(m)
            case Wildcard(lo, hi) =>
                f(lo); f(hi)
            case Skolem(u) => f(u)
            case MatchType(b, sc, cases) =>
                f(b); f(sc); cases.foreach(f)
            case FlexibleType(u) => f(u)
            case MatchCase(p, r) =>
                f(p); f(r)
            case Bind(_, p)       => f(p)
            case TypeRef(qual, _) => f(qual)
            case Bounds(lo, hi) =>
                f(lo); f(hi)
            case Named(_)           => ()
            case TermRef(prefix, _) => f(prefix)
            case ConstantType(_)    => ()
            case ThisType(_)        => ()
            case ParamRef(_, _)     => ()
            case Nothing            => ()
            case Any                => ()
        end visit

        /** First-level structural children of this Type. Leaf cases return an empty Chunk.
          *
          * Materializes a Chunk for callers that need an indexable structure. Internal traversals should
          * prefer `visit` (non-allocating).
          */
        def children: Chunk[Type] =
            val b = Chunk.newBuilder[Type]
            visit(b += _)
            b.result()
        end children

        /** Visit this type and every structural descendant in pre-order (self first). */
        private[kyo] def foreach(f: Type => Unit): Unit =
            f(this)
            visit(_.foreach(f))
        end foreach

        /** Collect all nodes matching `pf` in pre-order. Inline entry delegates to the non-inline body loop. */
        inline def collect[A](inline pf: PartialFunction[Type, A]): Chunk[A] =
            collectImpl(pf)

        private def collectImpl[A](pf: PartialFunction[Type, A]): Chunk[A] =
            val b = Chunk.newBuilder[A]
            foreach { t =>
                if pf.isDefinedAt(t) then b += pf(t)
            }
            b.result()
        end collectImpl

        /** Find the first node satisfying `p` in pre-order. Inline entry delegates to the non-inline body loop. */
        inline def find(inline p: Type => Boolean): Maybe[Type] =
            findImpl(p)

        private def findImpl(p: Type => Boolean): Maybe[Type] =
            var found: Maybe[Type] = Maybe.Absent
            def go(t: Type): Boolean =
                if found.isDefined then true
                else if p(t) then
                    found = Maybe(t)
                    true
                else
                    var hit = false
                    t.visit { c =>
                        if !hit && go(c) then hit = true
                    }
                    hit
                end if
            end go
            discard(go(this))
            found
        end findImpl

        /** Left-fold over all nodes in pre-order. Inline entry delegates to the non-inline body loop. */
        inline def foldLeft[A](z: A)(inline f: (A, Type) => A): A =
            foldLeftImpl(z)(f)

        private def foldLeftImpl[A](z: A)(f: (A, Type) => A): A =
            var acc = z
            foreach((t: Type) => acc = f(acc, t))
            acc
        end foldLeftImpl

        /** True when any node in the subtree (including this node) satisfies `p`. Pre-order short-circuits on first match.
          * Inline entry delegates to the non-inline body loop.
          */
        inline def exists(inline p: Type => Boolean): Boolean =
            existsImpl(p)

        private def existsImpl(p: Type => Boolean): Boolean =
            if p(this) then true
            else
                var hit = false
                visit { c =>
                    if !hit && c.existsImpl(p) then hit = true
                }
                hit
        end existsImpl

    end Type

    // ── Tree ADT ────────────────────────────────────────────────────────────

    /** Structural representation of a TASTy expression or definition body.
      *
      * Produced on demand by `Tasty.bodyTree`. Sub-trees are eagerly populated when a body is decoded;
      * laziness lives at the body boundary, not inside the AST. Cross-references to symbols travel as fully
      * resolved `Symbol` values and references to types travel as `Type` values from the loader.
      *
      * Cases group into four categories. Term-level cases (`Apply`, `Select`, `Ident`, `Block`, `If`,
      * `Match`, ...) cover expression bodies. Definition cases (`ValDef`, `DefDef`, `TypeDef`, `ClassDef`,
      * `PackageDef`, `Template`) appear inside class templates. Type-position cases (`AppliedType`,
      * `RefinedType`, `AnnotatedType`, `MatchType`, `TypeBounds`, ...) appear inside type trees referenced by
      * `tpt` slots. Pattern cases (`Bind`, `Unapply`, `Alternative`) appear under `CaseDef`. `Shared` carries
      * a back-reference address used to deduplicate sub-trees within the source TASTy file.
      *
      * `children`, `foreach`, `collect`, `find`, `foldLeft`, and `exists` cover the typical structural walks;
      * `classpath.treeShow` renders a Scala-source-shaped string for debugging.
      */
    enum Tree derives Schema, CanEqual:
        /** Term reference by name (IDENT tag). */
        case Ident(name: Name, tpe: Type)

        /** Member selection (SELECT tag). */
        case Select(qualifier: Tree, name: Name, tpe: Type)

        /** Function application (APPLY tag). */
        case Apply(fun: Tree, args: Chunk[Tree])

        /** Type application (TYPEAPPLY tag). */
        case TypeApply(fun: Tree, args: Chunk[Type])

        /** Block of statements followed by an expression (BLOCK tag). */
        case Block(stats: Chunk[Tree], expr: Tree)

        /** Conditional expression (IF tag). */
        case If(cond: Tree, thenp: Tree, elsep: Tree)

        /** Pattern match (MATCH tag). */
        case Match(selector: Tree, cases: Chunk[Tree.CaseDef])

        /** Single case in a match (CASEDEF tag). */
        case CaseDef(pattern: Tree, guard: Maybe[Tree], body: Tree)

        /** Literal constant (various const tags). */
        case Literal(constant: Constant)

        /** Object allocation (NEW tag). */
        case New(tpe: Type)

        /** Assignment (ASSIGN tag). */
        case Assign(lhs: Tree, rhs: Tree)

        /** Return statement (RETURN tag). */
        case Return(expr: Maybe[Tree], from: Symbol)

        /** Throw expression (THROW tag). */
        case Throw(expr: Tree)

        /** Lambda / anonymous function (LAMBDA tag). */
        case Lambda(method: Tree, tpe: Maybe[Type])

        /** Type ascription (TYPED tag). */
        case Typed(expr: Tree, tpe: Type)

        /** Inlined call expansion (INLINED tag). */
        case Inlined(call: Maybe[Tree], bindings: Chunk[Tree], body: Tree)

        /** Try/catch/finally (TRY tag). */
        case Try(expr: Tree, cases: Chunk[Tree.CaseDef], finalizer: Maybe[Tree])

        /** While loop (WHILE tag). */
        case While(cond: Tree, body: Tree)

        /** Pattern binding (BIND tag). */
        case Bind(name: Name, pattern: Tree)

        /** Alternative patterns in a case (ALTERNATIVE tag). */
        case Alternative(patterns: Chunk[Tree])

        /** Unapply extractor call (UNAPPLY tag). */
        case Unapply(fun: Tree, implicits: Chunk[Tree], patterns: Chunk[Tree])

        /** Val or var definition (VALDEF tag). */
        case ValDef(symbol: Symbol, tpt: Type, rhs: Maybe[Tree])

        /** Method definition (DEFDEF tag). */
        case DefDef(symbol: Symbol, paramss: Chunk[Chunk[Tree]], tpt: Type, rhs: Maybe[Tree])

        /** Type alias or abstract type definition (TYPEDEF tag). */
        case TypeDef(symbol: Symbol, rhs: Type)

        /** Package definition (PACKAGE tag). */
        case PackageDef(symbol: Symbol, stats: Chunk[Tree])

        /** Class definition (TYPEDEF with TEMPLATE). */
        case ClassDef(symbol: Symbol, template: Tree.Template)

        /** Class template body (TEMPLATE tag). */
        case Template(parents: Chunk[Tree], self: Maybe[Symbol], body: Chunk[Tree])

        /** Super reference (SUPER tag). */
        case Super(qual: Tree, mix: Maybe[Name])

        /** This reference (THIS tag). */
        case This(cls: Symbol)

        /** Named argument in an application (NAMEDARG tag). */
        case NamedArg(name: Name, value: Tree)

        /** Annotated tree (ANNOTATEDtpt/ANNOTATEDtype). */
        case Annotated(expr: Tree, annotation: Tree)

        /** Shared sub-tree back-reference (SHAREDtype or SHAREDterm tag). `address` is the byte address of the original node. */
        case Shared(address: Int)

        /** TASTy category-1 modifier tag (single-byte, no payload; tag in range [1, 59]). */
        case Modifier(flag: Flag)

        /** Recursive type wrapper (RECtype tag). */
        case RecType(parent: Tree)

        /** Super type pair (SUPERtype tag). */
        case SuperType(thistpe: Tree, supertpe: Tree)

        /** Structural refinement type (REFINEDtype tag). */
        case RefinedType(parent: Tree, name: Name, info: Tree)

        /** Type constructor applied to arguments (APPLIEDtype tag). */
        case AppliedType(tycon: Tree, args: Chunk[Tree])

        /** Type bounds (TYPEBOUNDS tag). */
        case TypeBounds(lo: Tree, hi: Tree)

        /** Annotated type (ANNOTATEDtype tag). */
        case AnnotatedType(parent: Tree, annot: Tree)

        /** Intersection type (ANDtype tag). */
        case AndType(left: Tree, right: Tree)

        /** Union type (ORtype tag). */
        case OrType(left: Tree, right: Tree)

        /** By-name type (BYNAMEtype tag). */
        case ByNameType(arg: Tree)

        /** Match type with scrutinee and cases (MATCHtype tag). */
        case MatchType(bound: Tree, scrutinee: Tree, cases: Chunk[Tree])

        /** Flexible (Java-nullable) type (FLEXIBLEtype tag). */
        case FlexibleType(arg: Tree)

        /** Type-position identifier (IDENTtpt tag): nameRef + type. */
        case IdentTpt(name: Name, tpe: Type)

        /** Type-position selection (SELECTtpt tag): qualifier + name. */
        case SelectTpt(qual: Tree, name: Name)

        /** Singleton type (SINGLETONtpt tag): ref tree. */
        case SingletonTpt(tpe: Tree)

        /** Package-level term reference (TERMREFpkg tag): package name only. */
        case TermRefPkg(name: Name)

        /** Package-level type reference (TYPEREFpkg tag): package name only. */
        case TypeRefPkg(name: Name)

        /** Symbol-addressed term reference (TERMREFsymbol tag): address + qualifier. */
        case TermRefSymbol(address: Int, qual: Tree)

        /** Symbol-addressed type reference (TYPEREFsymbol tag): address + qualifier. */
        case TypeRefSymbol(address: Int, qual: Tree)

        /** Direct-address term reference (TERMREFdirect tag): symbol address. */
        case TermRefDirect(address: Int)

        /** Direct-address type reference (TYPEREFdirect tag): symbol address. */
        case TypeRefDirect(address: Int)

        /** Owner-qualified selection (SELECTin tag): qualifier + name + owner. */
        case SelectIn(qual: Tree, name: Name, owner: Tree)

        /** Import statement (IMPORT tag): qualifier expression and selector trees. */
        case Import(qual: Tree, selectors: Chunk[Tree])

        /** Export clause (EXPORT tag): qualifier expression and selector trees. */
        case Export(qual: Tree, selectors: Chunk[Tree])

        /** In-tree annotation node (ANNOTATION tag): annotation class type tree and annotation argument tree. */
        case AnnotationNode(annotType: Tree, arg: Tree)

        /** Recursive-this reference (RECthis tag): address of the enclosing Rec frame. */
        case RecThisAddr(address: Int)

        /** Import selector: the imported name (IMPORTED tag). */
        case Imported(qual: Tree)

        /** Import rename: the renamed-to name (RENAMED tag). */
        case Renamed(name: Name)

        /** By-name type annotation in type position (BYNAMEtpt tag). */
        case ByNameTpt(inner: Type)

        /** A type written in tree position whose shape is carried by the decoded `Type` rather than a
          * dedicated tree node (LAMBDAtpt, REFINEDtpt, TYPEBOUNDStpt, MATCHtpt). The tree decoder routes
          * these through the type decoder and wraps the result here.
          */
        case TypeTree(tpe: Type)

        /** Bounded wildcard type (BOUNDED tag): the bound tree. */
        case Bounded(bound: Tree)

        /** Explicit type annotation in type position (EXPLICITtpt tag). */
        case ExplicitTpt(inner: Type)

        /** Elided (inferred) type position (ELIDED tag). */
        case Elided(inner: Type)

        /** Type-position reference by name and qualifier (TYPEREF tag). */
        case TypeRefTree(qual: Tree, name: Name)

        /** Term-position path-dependent reference. Wire tag TERMREFin (174).
          *
          * prefix is the qualifier tree (encoded as Tree.Ident(name, qualType)). name identifies the
          * referenced member.
          */
        case TermRef(prefix: Tree, name: Name)

        /** Repeated (varargs) sequence literal. Wire tag REPEATED (149).
          *
          * elems are the element trees. tpe is the static element type for typed callers; when the element
          * type is not statically known the placeholder `Type.Wildcard(Nothing, Any)` is carried.
          */
        case SeqLiteral(elems: Chunk[Tree], tpe: Type)

        /** Self type definition in a class template (SELFDEF tag). */
        case SelfDef(name: Name, tpe: Tree)

        /** Outer reference (SELECTouter tag): outer class at given level. */
        case SelectOuter(qual: Tree, name: Name, levels: Int, tpe: Type)

        /** Unknown tag: encountered a tag not covered by this ADT version. */
        case Unknown(tag: Int, length: Int)

        /** Visit each direct child of this Tree without allocating an intermediate Chunk.
          *
          * The pattern match dispatches once and calls `f` per child in source order. Used internally by
          * `children`, `foreach`, `collect`, `find`, `foldLeft`, and `exists` so the hot traversal path does
          * not materialize a Chunk per node.
          */
        private[kyo] def visit(f: Tree => Unit): Unit = this match
            case Tree.Ident(_, _) => ()
            case Tree.Select(qualifier, _, _) =>
                f(qualifier)
            case Tree.Apply(fun, args) =>
                f(fun); args.foreach(f)
            case Tree.TypeApply(fun, _) =>
                f(fun)
            case Tree.Block(stats, expr) =>
                stats.foreach(f); f(expr)
            case Tree.If(cond, thenp, elsep) =>
                f(cond); f(thenp); f(elsep)
            case Tree.Match(selector, cases) =>
                f(selector); cases.foreach(f)
            case Tree.CaseDef(pattern, guard, body) =>
                f(pattern)
                guard match
                    case Maybe.Present(t) => f(t)
                    case Maybe.Absent     => ()
                f(body)
            case Tree.Literal(_) => ()
            case Tree.New(_)     => ()
            case Tree.Assign(lhs, rhs) =>
                f(lhs); f(rhs)
            case Tree.Return(expr, _) =>
                expr match
                    case Maybe.Present(t) => f(t)
                    case Maybe.Absent     => ()
            case Tree.Throw(expr) =>
                f(expr)
            case Tree.Lambda(method, _) =>
                f(method)
            case Tree.Typed(expr, _) =>
                f(expr)
            case Tree.Inlined(call, bindings, body) =>
                call match
                    case Maybe.Present(t) => f(t)
                    case Maybe.Absent     => ()
                bindings.foreach(f)
                f(body)
            case Tree.Try(expr, cases, finalizer) =>
                f(expr)
                cases.foreach(f)
                finalizer match
                    case Maybe.Present(t) => f(t)
                    case Maybe.Absent     => ()
            case Tree.While(cond, body) =>
                f(cond); f(body)
            case Tree.Bind(_, pattern) =>
                f(pattern)
            case Tree.Alternative(patterns) =>
                patterns.foreach(f)
            case Tree.Unapply(fun, implicits, patterns) =>
                f(fun); implicits.foreach(f); patterns.foreach(f)
            case Tree.ValDef(_, _, rhs) =>
                rhs match
                    case Maybe.Present(t) => f(t)
                    case Maybe.Absent     => ()
            case Tree.DefDef(_, paramss, _, rhs) =>
                paramss.foreach(_.foreach(f))
                rhs match
                    case Maybe.Present(t) => f(t)
                    case Maybe.Absent     => ()
            case Tree.TypeDef(_, _) => ()
            case Tree.PackageDef(_, stats) =>
                stats.foreach(f)
            case Tree.ClassDef(_, template) =>
                f(template)
            case Tree.Template(parents, _, body) =>
                parents.foreach(f); body.foreach(f)
            case Tree.Super(qual, _) =>
                f(qual)
            case Tree.This(_) => ()
            case Tree.NamedArg(_, value) =>
                f(value)
            case Tree.Annotated(expr, annot) =>
                f(expr); f(annot)
            case Tree.Shared(_)       => ()
            case Tree.Modifier(_)     => ()
            case Tree.RecType(parent) => f(parent)
            case Tree.SuperType(t1, t2) =>
                f(t1); f(t2)
            case Tree.RefinedType(parent, _, info) =>
                f(parent); f(info)
            case Tree.AppliedType(tycon, args) =>
                f(tycon); args.foreach(f)
            case Tree.TypeBounds(lo, hi) =>
                f(lo); f(hi)
            case Tree.AnnotatedType(parent, a) =>
                f(parent); f(a)
            case Tree.AndType(l, r) =>
                f(l); f(r)
            case Tree.OrType(l, r) =>
                f(l); f(r)
            case Tree.ByNameType(arg) =>
                f(arg)
            case Tree.MatchType(bound, scrutinee, cases) =>
                f(bound); f(scrutinee); cases.foreach(f)
            case Tree.FlexibleType(arg) =>
                f(arg)
            case Tree.IdentTpt(_, _) => ()
            case Tree.SelectTpt(qual, _) =>
                f(qual)
            case Tree.SingletonTpt(tpe) =>
                f(tpe)
            case Tree.TermRefPkg(_) => ()
            case Tree.TypeRefPkg(_) => ()
            case Tree.TermRefSymbol(_, qual) =>
                f(qual)
            case Tree.TypeRefSymbol(_, qual) =>
                f(qual)
            case Tree.TermRefDirect(_) => ()
            case Tree.TypeRefDirect(_) => ()
            case Tree.SelectIn(qual, _, owner) =>
                f(qual); f(owner)
            case Tree.Import(qual, selectors) =>
                f(qual); selectors.foreach(f)
            case Tree.Export(qual, selectors) =>
                f(qual); selectors.foreach(f)
            case Tree.AnnotationNode(annotType, arg) =>
                f(annotType); f(arg)
            case Tree.RecThisAddr(_) => ()
            case Tree.TypeTree(_)    => ()
            case Tree.Imported(qual) =>
                f(qual)
            case Tree.Renamed(_)   => ()
            case Tree.ByNameTpt(_) => ()
            case Tree.Bounded(bound) =>
                f(bound)
            case Tree.ExplicitTpt(_) => ()
            case Tree.Elided(_)      => ()
            case Tree.TypeRefTree(qual, _) =>
                f(qual)
            case Tree.TermRef(prefix, _) =>
                f(prefix)
            case Tree.SeqLiteral(elems, _) =>
                elems.foreach(f)
            case Tree.SelfDef(_, tpe) =>
                f(tpe)
            case Tree.SelectOuter(qual, _, _, _) =>
                f(qual)
            case Tree.Unknown(_, _) => ()
        end visit

        /** Direct structural child trees of this node. Leaf nodes return `Chunk.empty`.
          *
          * Materializes a Chunk for callers that need an indexable structure. Internal traversals should
          * prefer `visit` (non-allocating).
          */
        def children: Chunk[Tree] =
            val b = Chunk.newBuilder[Tree]
            visit(b += _)
            b.result()
        end children

        /** Pre-order traversal: visits this node then all descendants. */
        private[kyo] def foreach(f: Tree => Unit): Unit =
            f(this)
            visit(_.foreach(f))
        end foreach

        /** Collect all nodes matching `pf` in pre-order. Inline entry delegates to the non-inline body loop. */
        inline def collect[A](inline pf: PartialFunction[Tree, A]): Chunk[A] =
            collectImpl(pf)

        private def collectImpl[A](pf: PartialFunction[Tree, A]): Chunk[A] =
            val b = Chunk.newBuilder[A]
            foreach { t =>
                if pf.isDefinedAt(t) then b += pf(t)
            }
            b.result()
        end collectImpl

        /** Find first node satisfying `p` in pre-order. Inline entry delegates to the non-inline body loop. */
        inline def find(inline p: Tree => Boolean): Maybe[Tree] =
            findImpl(p)

        private def findImpl(p: Tree => Boolean): Maybe[Tree] =
            var found: Maybe[Tree] = Maybe.Absent
            def go(t: Tree): Boolean =
                if found.isDefined then true
                else if p(t) then
                    found = Maybe(t)
                    true
                else
                    var hit = false
                    t.visit { c =>
                        if !hit && go(c) then hit = true
                    }
                    hit
                end if
            end go
            discard(go(this))
            found
        end findImpl

        /** Left-fold over all nodes in pre-order. Inline entry delegates to the non-inline body loop. */
        inline def foldLeft[A](z: A)(inline f: (A, Tree) => A): A =
            foldLeftImpl(z)(f)

        private def foldLeftImpl[A](z: A)(f: (A, Tree) => A): A =
            var acc = z
            foreach((t: Tree) => acc = f(acc, t))
            acc
        end foldLeftImpl

        /** True when any node in the subtree (including this node) satisfies `p`. Pre-order short-circuits on first match.
          * Inline entry delegates to the non-inline body loop.
          */
        inline def exists(inline p: Tree => Boolean): Boolean =
            existsImpl(p)

        private def existsImpl(p: Tree => Boolean): Boolean =
            if p(this) then true
            else
                var hit = false
                visit { c =>
                    if !hit && c.existsImpl(p) then hit = true
                }
                hit
        end existsImpl

    end Tree

    // ── Supporting ADTs for the Symbol hierarchy ────────────────────────────

    /** Variance of a type parameter or abstract type member.
      *
      * Three cases: `Invariant` (no variance annotation), `Covariant` (declared with `+`), and
      * `Contravariant` (declared with `-`). Returned by `Symbol.TypeParam.variance` for every type parameter
      * in the model and by the bounds machinery wherever a variance position is meaningful.
      *
      * The decoder reads the variance directly from the TASTy flag set on the type parameter symbol (the
      * `Covariant` and `Contravariant` flag bits); the absence of either flag is `Invariant`. The convenience
      * accessor `Symbol.TypeParam.varianceLabel` returns the printable form (`""`, `"+"`, `"-"`) so callers
      * rendering signatures do not have to do their own dispatch.
      */
    enum Variance derives Schema, CanEqual:
        case Invariant, Covariant, Contravariant

    /** Lower / upper bounds on a type parameter or abstract type member.
      *
      * Carries the resolved `Type` values for both ends of the bound: `lower` is the lower bound (typically
      * `Type.Nothing` when unbounded), `upper` is the upper bound (typically `Type.Any` when unbounded). Used
      * by `Symbol.TypeParam.bounds`, `Symbol.AbstractType.bounds`, and `Symbol.OpaqueType.bounds`.
      *
      * **Naming overlap.** This is the Symbol-layer bounds type, distinct from `Tree.TypeBounds` which is the
      * wire-level AST node that appears inside a `Tree`. The Symbol layer is what user code typically wants:
      * `Tree.TypeBounds` only matters when traversing a decoded body tree. Both are kept for layer separation.
      *
      * Equality is structural via `derives CanEqual`; two `TypeBounds` values are equal when both bound
      * fields are equal.
      */
    final case class TypeBounds(lower: Type, upper: Type) derives Schema, CanEqual

    /** Source-level visibility of a symbol, derived from `Symbol.flags`.
      *
      * Five cases: `Public` (no `private` / `protected` modifier), `Private` (the `Private` flag is set),
      * `Protected` (the `Protected` flag is set), and the two scoped flavours `ScopedPrivate` /
      * `ScopedProtected` which indicate the `Local` flag is also set (Scala's `private[this]` /
      * `protected[this]` analogues).
      *
      * Returned by `Symbol.visibility`. The mapping from raw flag bits to this enum is in
      * `Symbol.visibility`; user code should match on the enum rather than poking at the underlying flags,
      * because the flag combinations that produce each case are not stable across kyo-tasty versions.
      */
    enum Visibility derives Schema, CanEqual:
        case Private, Protected, Public, ScopedPrivate, ScopedProtected

    /** Inheritance-openness level of a class-like symbol, derived from `Symbol.flags`.
      *
      * Four cases: `Final` (the `Final` flag is set; subclassing rejected), `Sealed` (the `Sealed` flag is
      * set; subclasses restricted to the same compilation unit and surfaced via `permittedSubclasses`),
      * `Open` (the `Open` flag is set; explicitly inheritable across compilation units), and `Default` (no
      * openness flag is set; subclassable but the compiler emits warnings under `-Wopen`).
      *
      * Returned by `Symbol.openLevel`. The order of precedence when multiple flags are set is
      * `Final > Sealed > Open > Default`, which is what `Symbol.openLevel` enforces.
      */
    enum OpenLevel derives Schema, CanEqual:
        case Open, Default, Sealed, Final

    /** Format selector for `Symbol.show(format: ShowFormat)`.
      *
      * Three cases controlling how a symbol prints when callers want a single string per symbol.
      * `FullyQualified` returns the dotted fully-qualified name (`example.Box`). `Simple` returns just the local name
      * (`Box`). `Code` returns the source-shaped declaration string (`class Box[T] extends Shape`) which
      * is what `Symbol.signature` produces.
      *
      * Equivalent to a function from `Symbol` to `String`; provided as an enum so callers can pattern-match
      * on the chosen format when rendering, log it, or pass it through a config. The default rendering used
      * by `Symbol.show()` (no argument) is `FullyQualified`.
      */
    enum ShowFormat derives Schema, CanEqual:
        case FullyQualified, Simple, Code

    // ── MemberScope enum ─────────────────────────────────────────────────────

    /** Scope selector for `classpath.members` and `classpath.findMember`.
      *
      * Three cases: `Declared` (only symbols directly declared on the receiver), `Inherited` (only symbols
      * inherited from parent types, not directly declared), and `All` (union of declared and inherited,
      * deduplicated by simple name keeping the most-specific occurrence).
      *
      * The default scope is `Declared`, matching the most common use case (checking what a class
      * introduces, not what it inherits).
      */
    enum MemberScope derives Schema, CanEqual:
        case Declared, Inherited, All

    // ── Symbol ──────────────────────────────────────────────────────────────

    /** Sealed root of the Symbol hierarchy.
      *
      * Every symbol is one of 14 final case classes under this trait. Pattern matching is exhaustive. All
      * fields are pure immutable data; every classpath-dependent operation (owner, fullName, members, ...)
      * lives on `object Tasty.*`, so a `Symbol` can be serialised independently of any `Classpath`.
      *
      * Flag-based predicates (`isFinal`, `isAbstract`, `isPrivate`, ...) are pure bitmask checks. For kind
      * discrimination, pattern match: `symbol match { case _: Symbol.Class => ... }`.
      *
      * `scaladoc` returns the raw TASTy comment text including the `/**` opening delimiter, the `*/`
      * closing delimiter, and any inner `*` margin characters. No stripping, trimming, or markdown
      * rendering is performed; callers extract `@param` / `@return` themselves. `Symbol.TypeParam`,
      * `Symbol.Parameter`, and `Symbol.Package` always return `Maybe.Absent`; every other symbol kind
      * carries whatever comment the compiler recorded.
      */
    sealed trait Symbol derives CanEqual:
        def id: SymbolId
        def name: Name
        def flags: Flags
        def ownerId: SymbolId
        def scaladoc: Maybe[String]
        def sourcePosition: Maybe[Position]

        // Id-and-kind equality on the sealed-trait body. `final` blocks the 14 case classes'
        // auto-derived structural equality from shadowing this override.
        final override def equals(that: Any): Boolean = that match
            case other: Symbol =>
                val selfIdVal  = id.value
                val otherIdVal = other.id.value
                selfIdVal == otherIdVal && selfIdVal != -1 && otherIdVal != -1 && kind == other.kind
            case _ => false // carve-out: Any is open; exhaustive enumeration is not possible here

        final override def hashCode(): Int = id.value

        // 40 flag predicates on base trait: pure bitmask checks, no Classpath dependency
        def isFinal: Boolean       = flags.contains(Flag.Final)
        def isAbstract: Boolean    = flags.contains(Flag.Abstract)
        def isSealed: Boolean      = flags.contains(Flag.Sealed)
        def isCase: Boolean        = flags.contains(Flag.Case)
        def isLazy: Boolean        = flags.contains(Flag.Lazy)
        def isOverride: Boolean    = flags.contains(Flag.Override)
        def isPrivate: Boolean     = flags.contains(Flag.Private)
        def isProtected: Boolean   = flags.contains(Flag.Protected)
        def isPublic: Boolean      = flags.contains(Flag.Public)
        def isStatic: Boolean      = flags.contains(Flag.Static)
        def isMutable: Boolean     = flags.contains(Flag.Mutable)
        def isErased: Boolean      = flags.contains(Flag.Erased)
        def isInfix: Boolean       = flags.contains(Flag.Infix)
        def isOpen: Boolean        = flags.contains(Flag.Open)
        def isTransparent: Boolean = flags.contains(Flag.Transparent)

        /** True when this symbol is a user-defined macro method.
          *
          * Excludes synthetic enum-case methods (`ordinal`, `productElement`, ...) that dotty also tags with
          * `Flag.Macro`, and restricts the result to `Symbol.Method`.
          */
        def isMacro: Boolean =
            flags.contains(Flag.Macro) && !flags.contains(Flag.Synthetic) && this.isInstanceOf[Symbol.Method]
        def isSynthetic: Boolean     = flags.contains(Flag.Synthetic)
        def isArtifact: Boolean      = flags.contains(Flag.Artifact)
        def isCovariant: Boolean     = flags.contains(Flag.Covariant)
        def isContravariant: Boolean = flags.contains(Flag.Contravariant)
        def isExtension: Boolean     = flags.contains(Flag.Extension)
        def isTracked: Boolean       = flags.contains(Flag.Tracked)
        def isStable: Boolean        = flags.contains(Flag.Stable)
        def isParamAccessor: Boolean = flags.contains(Flag.ParamAccessor)
        def isCaseAccessor: Boolean  = flags.contains(Flag.CaseAccessor)
        def isFieldAccessor: Boolean = flags.contains(Flag.FieldAccessor)
        def isExported: Boolean      = flags.contains(Flag.Exported)
        def isLocal: Boolean         = flags.contains(Flag.Local)
        def hasDefault: Boolean      = flags.contains(Flag.HasDefault)
        def isInvisible: Boolean     = flags.contains(Flag.Invisible)
        def isInto: Boolean          = flags.contains(Flag.Into)
        def isInlineProxy: Boolean   = flags.contains(Flag.InlineProxy)
        def isTailrec: Boolean       = flags.contains(Flag.Tailrec)
        def isScala2: Boolean        = flags.contains(Flag.Scala2)
        def isJavaRecord: Boolean    = flags.contains(Flag.JavaRecord)
        def isEnum: Boolean          = flags.contains(Flag.Enum)
        def isModule: Boolean        = flags.contains(Flag.Module)
        def isJava: Boolean          = flags.contains(Flag.JavaDefined)
        def isInline: Boolean        = flags.contains(Flag.Inline)

        /** Symbol is both `inline` and `transparent`. */
        def isTransparentInline: Boolean = flags.contains(Flag.Inline) && flags.contains(Flag.Transparent)

        /** Symbol marked as a `given` instance (using-clause parameters excluded). */
        def isGiven: Boolean  = flags.contains(Flag.Given) && !this.isInstanceOf[Symbol.Parameter]
        def isOpaque: Boolean = flags.contains(Flag.Opaque)

        /** Simple name as a `String`. Mirrors `name.asString`. */
        def simpleName: String =
            import Name.asString
            name.asString

        /** Value-level discriminator for snapshot wire format (private[kyo]).
          *
          * The primary consumer is SnapshotWriter which writes a kind byte. User code must use
          * sealed pattern matching instead.
          */
        private[kyo] def kind: SymbolKind = this match
            case _: Symbol.Package => SymbolKind.Package
            // Cases are disjoint final classes; order is informational only.
            case _: Symbol.EnumCase     => SymbolKind.EnumCase
            case _: Symbol.Class        => SymbolKind.Class
            case _: Symbol.Trait        => SymbolKind.Trait
            case _: Symbol.Object       => SymbolKind.Object
            case _: Symbol.Method       => SymbolKind.Method
            case _: Symbol.Field        => SymbolKind.Field
            case _: Symbol.Val          => SymbolKind.Val
            case _: Symbol.Var          => SymbolKind.Var
            case _: Symbol.TypeAlias    => SymbolKind.TypeAlias
            case _: Symbol.OpaqueType   => SymbolKind.OpaqueType
            case _: Symbol.AbstractType => SymbolKind.AbstractType
            case _: Symbol.TypeParam    => SymbolKind.TypeParam
            case _: Symbol.Parameter    => SymbolKind.Parameter

        /** Typed grouped queries derived from flags (pure, no Classpath). */
        def visibility: Visibility =
            val priv = flags.contains(Flag.Private)
            val prot = flags.contains(Flag.Protected)
            val loc  = flags.contains(Flag.Local)
            (priv, prot, loc) match
                case (true, _, true) => Visibility.ScopedPrivate
                case (_, true, true) => Visibility.ScopedProtected
                case (true, _, _)    => Visibility.Private
                case (_, true, _)    => Visibility.Protected
                case _               => Visibility.Public
            end match
        end visibility

        def openLevel: OpenLevel =
            if flags.contains(Flag.Final) then OpenLevel.Final
            else if flags.contains(Flag.Sealed) then OpenLevel.Sealed
            else if flags.contains(Flag.Open) then OpenLevel.Open
            else OpenLevel.Default

    end Symbol

    /** Companion of `Symbol`; carries the intermediate sealed traits, the 14 final case classes, and internal factories used by the loader. */
    object Symbol:

        // ── Intermediate sealed traits ────────────────────────────────────────

        /** Common Class / Trait / Object / EnumCase contract: pure data fields for classlike symbols.
          *
          * Raw fields are the data as decoded from TASTy/classfile bytes. No query methods; use the `Classpath`
          * instance operations (e.g. `classpath.parents(symbol)`, `classpath.members(symbol)`) for classpath-dependent queries.
          *
          * `ClassLike` is the recommended pattern-match target when the caller wants to handle all four
          * classlike subtypes uniformly: `case c: Symbol.ClassLike => ...`.
          */
        sealed trait ClassLike extends Symbol:
            def javaMetadata: Maybe[Java.Metadata]
            def parentTypes: Chunk[Type]
            def typeParamIds: Chunk[SymbolId]
            def declarationIds: Chunk[SymbolId]
            def annotations: Chunk[Annotation]
            def javaAnnotations: Chunk[Java.Annotation]

        end ClassLike

        // ── 14 final case classes ─────────────────────────────────────────────

        /** A `class` declaration: Scala source `class`, Java `class`, the lifted backing class of a Scala 3 `enum`.
          *
          * `permittedSubclassIds` is `Present(ids)` for sealed parents; `Absent` for non-sealed classes.
          * `javaMetadata` is `Present` for symbols sourced from `.class` files.
          * Use `Tasty.bodyTree(symbol)` to decode the AST body bytes for this symbol.
          * `EnumCase` is a peer of `Class` under `ClassLike`, not a subtype; pattern-match `Symbol.EnumCase` before
          * `Symbol.Class` if you need to discriminate enum cases.
          */
        final case class Class(
            id: SymbolId,
            name: Name,
            flags: Flags,
            ownerId: SymbolId,
            scaladoc: Maybe[String],
            sourcePosition: Maybe[Position],
            javaMetadata: Maybe[Java.Metadata],
            parentTypes: Chunk[Type],
            typeParamIds: Chunk[SymbolId],
            declarationIds: Chunk[SymbolId],
            permittedSubclassIds: Maybe[Chunk[SymbolId]],
            annotations: Chunk[Annotation],
            javaAnnotations: Chunk[Java.Annotation]
        ) extends ClassLike derives Schema, CanEqual

        /** A single case of a Scala 3 enum.
          *
          * `EnumCase` is now a peer of `Symbol.Class` under `Symbol.ClassLike`, not a subtype of `Class`.
          * Pattern-match on `Symbol.EnumCase` directly; it will NOT match a `Symbol.Class` arm.
          * The `Flag.Enum` and `Flag.Case` flags are always set on this symbol.
          */
        final case class EnumCase(
            id: SymbolId,
            name: Name,
            flags: Flags,
            ownerId: SymbolId,
            scaladoc: Maybe[String],
            sourcePosition: Maybe[Position],
            javaMetadata: Maybe[Java.Metadata],
            parentTypes: Chunk[Type],
            typeParamIds: Chunk[SymbolId],
            declarationIds: Chunk[SymbolId],
            permittedSubclassIds: Maybe[Chunk[SymbolId]],
            annotations: Chunk[Annotation],
            javaAnnotations: Chunk[Java.Annotation]
        ) extends ClassLike derives Schema, CanEqual

        /** A `trait` declaration: Scala source `trait` or a Java `interface` (which the loader normalizes to this
          * representation). Shares the `ClassLike` shape with `Class` and `Object`; the difference is that a Trait
          * cannot be `new`-ed directly and that Java interfaces collapse `default` and `static` methods into its
          * `declarationIds`.
          *
          * `parentTypes` carries the source-order `extends`/`with` types; for a Java interface the head is
          * `java.lang.Object` followed by the declared interface parents. `permittedSubclassIds` is `Present` for
          * sealed traits, `Absent` otherwise (use `isSealed` to discriminate). `javaMetadata` is `Present` for
          * interfaces sourced from `.class` files; use `Tasty.bodyTree(symbol)` to decode the template envelope lazily.
          *
          * The narrow `ClassLike` accessors (`methods`, `vals`, `vars`, `fields`, `nestedTypes`, ...) work the same
          * here as on `Class`; `vars` and `fields` are typically empty for Scala-sourced traits and non-empty for
          * Java interfaces with `default` accessors.
          */
        final case class Trait(
            id: SymbolId,
            name: Name,
            flags: Flags,
            ownerId: SymbolId,
            scaladoc: Maybe[String],
            sourcePosition: Maybe[Position],
            javaMetadata: Maybe[Java.Metadata],
            parentTypes: Chunk[Type],
            typeParamIds: Chunk[SymbolId],
            declarationIds: Chunk[SymbolId],
            permittedSubclassIds: Maybe[Chunk[SymbolId]],
            annotations: Chunk[Annotation],
            javaAnnotations: Chunk[Java.Annotation]
        ) extends ClassLike derives Schema, CanEqual

        /** A Scala `object` declaration: the singleton companion of a class/trait, a top-level `object`, or the
          * lifted companion that holds an enum's generated members. Java has no equivalent; symbols decoded from
          * `.class` files never produce `Object`.
          *
          * Carries the same shape as `Class` minus `permittedSubclassIds` (objects are not sealable parents). Use
          * `companion(using classpath)` (inherited from `ClassLike`) to walk from the object to its companion class or
          * trait, when one exists. `declarationIds` includes any nested objects, generated `values`/`valueOf` for
          * enum companions, and any user-declared `def`s, `val`s, or nested types.
          *
          * Note: the case-class identifier is `Object` and shadows `java.lang.Object` and `scala.Object` inside
          * this file; callers writing `import kyo.Tasty.Symbol.Object` should be aware of the same shadowing risk.
          */
        final case class Object(
            id: SymbolId,
            name: Name,
            flags: Flags,
            ownerId: SymbolId,
            scaladoc: Maybe[String],
            sourcePosition: Maybe[Position],
            javaMetadata: Maybe[Java.Metadata],
            parentTypes: Chunk[Type],
            typeParamIds: Chunk[SymbolId],
            declarationIds: Chunk[SymbolId],
            annotations: Chunk[Annotation],
            javaAnnotations: Chunk[Java.Annotation]
        ) extends ClassLike derives Schema, CanEqual

        /** A `def`: a Scala source `def`, a Scala constructor (`<init>`), an extension method, a `transparent inline
          * def`, or a Java method. `paramListIds` records parameter groups in source order; each inner `Chunk`
          * resolves through `classpath.paramLists(method)` into `Symbol.Parameter` entries. `typeParamIds` carries the
          * method's own type parameters; per-parameter-list type parameters appear under those parameter symbols.
          *
          * `declaredType` is the method's `MethodType` view (parameter types + result), `Present` for symbols with
          * a recorded signature and `Absent` for synthetics whose type the loader did not retain. `returnType`
          * unwraps the `Type.Function` or `Type.ContextFunction` result; for non-function shapes it returns the
          * declared type as-is. Use `Tasty.bodyTree(symbol)` to decode the AST envelope for the implementation;
          * abstract methods (`flags.contains(Flag.Abstract)`) and methods sourced from `.class` files return Absent.
          *
          * `javaMetadata` is `Present` for Java methods and holds the throws clauses, JVM access flags, parameter
          * names from the JVM `MethodParameters` attribute, and runtime/compile-time annotations. `annotations` is
          * the Scala-side annotation list and is independent.
          */
        final case class Method(
            id: SymbolId,
            name: Name,
            flags: Flags,
            ownerId: SymbolId,
            scaladoc: Maybe[String],
            sourcePosition: Maybe[Position],
            declaredType: Maybe[Type],
            paramListIds: Chunk[Chunk[SymbolId]],
            typeParamIds: Chunk[SymbolId],
            annotations: Chunk[Annotation],
            javaMetadata: Maybe[Java.Metadata]
        ) extends Symbol derives Schema, CanEqual

        /** A Scala `val`: an immutable value member of a class, trait, object, or top-level package. Also represents
          * a Scala 3 enum case that has no parameters (the case is lifted to a `Val` on the enum's companion with
          * `Flag.Case | Flag.Enum` set). Java has no Scala-shaped `val`; Java `final` fields surface as `Field`.
          *
          * `declaredType` is `Present` for any Scala-sourced `val`; the only `Absent` case is synthetic ValDefs the
          * loader has reason to keep without a recorded type. Use `Tasty.bodyTree(symbol)` to decode the AST for the
          * right-hand side; returns Absent for abstract members and for cases where the loader did not retain the
          * initializer.
          */
        final case class Val(
            id: SymbolId,
            name: Name,
            flags: Flags,
            ownerId: SymbolId,
            scaladoc: Maybe[String],
            sourcePosition: Maybe[Position],
            declaredType: Maybe[Type],
            annotations: Chunk[Annotation]
        ) extends Symbol derives Schema, CanEqual

        /** A Scala `var`: a mutable value member. Carries `Flag.Mutable`. Same shape as `Val`; the distinction is
          * the semantics that callers expect from the symbol's flags, not the field layout. Synthetic getter/setter
          * `Method` symbols are emitted alongside the `Var` on the owning class.
          *
          * Java mutable fields surface as `Field` (with the JVM access-flag projection), not as `Var`; only Scala
          * sources produce `Var`.
          */
        final case class Var(
            id: SymbolId,
            name: Name,
            flags: Flags,
            ownerId: SymbolId,
            scaladoc: Maybe[String],
            sourcePosition: Maybe[Position],
            declaredType: Maybe[Type],
            annotations: Chunk[Annotation]
        ) extends Symbol derives Schema, CanEqual

        /** A Java field decoded from a `.class` file. Holds the field's declared type, the JVM access flags via
          * `javaMetadata`, and any class-retention annotations under `javaAnnotations`. Has no `body` slot because
          * the JVM does not carry field initializers as serialized bytes; constant-pool literal values are visible
          * via the field's owner's `bodyTree` if needed.
          *
          * Scala-sourced backing fields surface as `Val` or `Var`, not as `Field`; the `Field` case is the Java
          * side of the split. The `isJvmPublic` / `isJvmPrivate` / `isJvmProtected` / `isJvmStatic` / `isJvmFinal`
          * predicates project the access-flags bitmask without forcing callers to know the JVMS bit positions.
          */
        final case class Field(
            id: SymbolId,
            name: Name,
            flags: Flags,
            ownerId: SymbolId,
            scaladoc: Maybe[String],
            sourcePosition: Maybe[Position],
            declaredType: Maybe[Type],
            javaMetadata: Maybe[Java.Metadata],
            javaAnnotations: Chunk[Java.Annotation]
        ) extends Symbol derives Schema, CanEqual

        /** A Scala `type X[T] = Body` declaration: a transparent name for another type. `body` is the right-hand
          * side as a fully-resolved `Type`; pattern-match on its shape to follow the alias. `typeParamIds` records
          * any type parameters introduced by the alias and resolves via `typeParams(using classpath)` into
          * `Symbol.TypeParam` entries.
          *
          * Unlike `OpaqueType`, type aliases do not introduce a new identity at the type level: the alias and its
          * body are interchangeable. Java has no source equivalent; type aliases come only from Scala sources.
          */
        final case class TypeAlias(
            id: SymbolId,
            name: Name,
            flags: Flags,
            ownerId: SymbolId,
            scaladoc: Maybe[String],
            sourcePosition: Maybe[Position],
            body: Maybe[Type],
            typeParamIds: Chunk[SymbolId],
            annotations: Chunk[Annotation]
        ) extends Symbol derives Schema, CanEqual

        /** A Scala 3 `opaque type X = Body` (with optional bounds and type parameters). Unlike a `TypeAlias`,
          * an opaque type carries its own identity outside the defining scope: callers see only the declared
          * `bounds` and cannot substitute `body` freely.
          *
          * `body` is the underlying type as visible inside the defining scope; `bounds` are the public upper and
          * lower bounds used by the outside world. `typeParamIds` carries any type parameters in the declaration.
          * `Flag.Opaque` is always set; carrying both the `bounds` and `body` lets a caller render either the
          * public or the private view without re-decoding.
          */
        final case class OpaqueType(
            id: SymbolId,
            name: Name,
            flags: Flags,
            ownerId: SymbolId,
            scaladoc: Maybe[String],
            sourcePosition: Maybe[Position],
            body: Maybe[Type],
            bounds: TypeBounds,
            typeParamIds: Chunk[SymbolId],
            annotations: Chunk[Annotation]
        ) extends Symbol derives Schema, CanEqual

        /** A Scala abstract type member: `type X` (with optional bounds) declared inside a class, trait, or object
          * without a right-hand side. Distinct from `TypeParam` (which appears as a parameter of a generic class
          * or method) and from `TypeAlias` (which carries a body).
          *
          * `bounds` is the declared `>: lower <: upper` bound pair; both default to `Type.Nothing` / `Type.Any`
          * when no bound was written. Java has no source equivalent; abstract type members come only from Scala.
          *
          * Annotation positions and source positions are preserved when present; flags carry the visibility and
          * other modifiers such as `Flag.Sealed` (rare but legal for abstract types in dotty).
          */
        final case class AbstractType(
            id: SymbolId,
            name: Name,
            flags: Flags,
            ownerId: SymbolId,
            scaladoc: Maybe[String],
            sourcePosition: Maybe[Position],
            bounds: TypeBounds,
            annotations: Chunk[Annotation]
        ) extends Symbol derives Schema, CanEqual

        /** A type parameter symbol of a generic class, trait, type alias, opaque type, or method. The owning symbol
          * is reachable via `ownerId`; the parameter's index in its owner's parameter list is its position in the
          * owner's `typeParams(using classpath)`.
          *
          * `bounds` carries the declared `>: lower <: upper` constraints (defaulting to `Type.Nothing` and
          * `Type.Any` when absent). `variance` is one of `Invariant`, `Covariant`, `Contravariant`; the
          * `varianceLabel` convenience returns the matching source sigil (`""`, `"+"`, `"-"`) for printing.
          *
          * No `scaladoc` is recorded for type parameters (they are accessor-shadowed to `Absent`); flags carry
          * `Flag.TypeParameter` plus any user annotations such as `Flag.Sealed` on bounded type parameters.
          */
        final case class TypeParam(
            id: SymbolId,
            name: Name,
            flags: Flags,
            ownerId: SymbolId,
            sourcePosition: Maybe[Position],
            bounds: TypeBounds,
            variance: Variance
        ) extends Symbol derives Schema, CanEqual:
            def scaladoc: Maybe[String] = Maybe.Absent
        end TypeParam

        /** A value parameter of a method or constructor. Owned by the enclosing `Method`; its position in the
          * `classpath.paramLists(method)` result reflects the parameter's source order within its parameter group.
          *
          * `declaredType` is the parameter's declared type after by-name and repeated wrapping (so an `=> Foo`
          * parameter has `declaredType = Type.ByName(Type.Named(foo))` and a `Foo*` parameter has
          * `Type.Repeated(...)`). The `isByName` and `isRepeated` predicates inspect those wrappers without
          * forcing a manual pattern match. `isImplicit` returns true for `given` and `implicit` parameters
          * (`Flag.Given` is set in both cases).
          *
          * `defaultArgId` is `Present` for parameters that have a default value; the referenced symbol is the
          * synthetic accessor method emitted by dotty (`foo$default$1` and friends). `defaultArg(using classpath)`
          * resolves the id without forcing the caller to deal with the indirection.
          *
          * No `scaladoc` is carried (parameters do not get their own scaladoc blocks); `annotations` covers any
          * source-level annotations on the parameter itself.
          */
        final case class Parameter(
            id: SymbolId,
            name: Name,
            flags: Flags,
            ownerId: SymbolId,
            sourcePosition: Maybe[Position],
            declaredType: Maybe[Type],
            defaultArgId: Maybe[SymbolId],
            annotations: Chunk[Annotation]
        ) extends Symbol derives Schema, CanEqual:
            def scaladoc: Maybe[String] = Maybe.Absent
        end Parameter

        /** A package symbol: the root package or any nested package. The root package has `name` equal to the empty
          * `Name` and `ownerId == id` (it is its own owner); descend through `members(using classpath)` to walk a
          * classpath as a tree.
          *
          * `memberIds` carries direct children: every top-level `Class`, `Trait`, `Object`, top-level `Method` or
          * `Val` from a Scala package object, and any nested `Package`. The narrowed accessors (`classes`,
          * `traits`, `objects`, `classLike`, plus `subPackages(using classpath)`) project this chunk by kind without
          * forcing the caller to pattern-match.
          *
          * Packages carry no `scaladoc`, no `sourcePosition`, no `annotations`, and no `body`: they are purely
          * structural anchors in the symbol graph. Equality is by `id`, as for every Symbol.
          */
        final case class Package(
            id: SymbolId,
            name: Name,
            flags: Flags,
            ownerId: SymbolId,
            memberIds: Chunk[SymbolId]
        ) extends Symbol derives Schema, CanEqual:
            def scaladoc: Maybe[String]         = Maybe.Absent
            def sourcePosition: Maybe[Position] = Maybe.Absent
        end Package

    end Symbol

    // Schema[Symbol] derived after object Symbol is fully defined.
    // Schema[Symbol.ClassLike] is provided via object Symbol.ClassLike companion (inside object Symbol)
    // which Scala 3 implicit lookup finds via companion object rules when expanding Schema[Symbol].
    given schemaSymbol: Schema[Symbol] = Schema.derived

    // ── Java namespace ──────────────────────────────────────────────────────

    /** Namespace for all Java/JVM-specific types decoded from `.class` files.
      *
      * All types that are specific to the JVM binary format and have no Scala-source equivalent live here.
      * `Java.Annotation` and `Java.Metadata` cover the JVM annotation and classfile metadata models;
      * `Java.Module.*` covers the JPMS module-info.class descriptors. Scala-source annotations on the
      * same symbols still live in the `Annotation` type (not here).
      */
    object Java:

        /** A single declared field of a Java record (JVMS `Record` attribute entry).
          *
          * Carried by `Java.Metadata.recordComponents: Chunk[RecordComponent]` on the symbol of the record
          * class itself. Java records expose their components in declaration order; the loader preserves that
          * order so the chunk index aligns with the canonical constructor parameter list.
          *
          * `name` is the component's source-level name; `tpe` is its declared type as resolved against the
          * classpath at load time. Equality is structural across both fields. Present only on JVM symbols
          * (the attribute is JVMS-defined); `Absent` on TASTy-sourced symbols even if they happen to be record
          * classes, because the record-ness lives in the JVM attribute and not in the TASTy ADT.
          */
        final case class RecordComponent(name: Name, tpe: Type) derives Schema, CanEqual

        /** Parameter-name table for one method overload: the method's name plus the names of its parameters in source order.
          *
          * Carried by `Java.Metadata.paramNames: Chunk[ParamGroup]` on the owning class symbol. Java classfiles
          * record parameter names in a `MethodParameters` attribute when compiled with `-parameters`; the loader
          * groups those entries by their owning method so each `ParamGroup` corresponds to one overload of
          * `methodName`. `parameterNames` is in source order and may be empty (no `MethodParameters` attribute or
          * a zero-arity method).
          *
          * Equality is structural across both fields (case class auto-generation).
          */
        final case class ParamGroup(methodName: Name, parameterNames: Chunk[Name]) derives Schema, CanEqual

        /** The enclosing-method context for a local or anonymous class (JVMS `EnclosingMethod` attribute).
          *
          * Carried by `Java.Metadata.enclosingMethod: Maybe[EnclosingMethod]` on the symbol of a local or
          * anonymous class. The JVMS records the immediately enclosing method for any class that was declared
          * inside one; absent otherwise. `owner` is the enclosing method's owner class symbol, and `methodName`
          * is the enclosing method's source-level name. Use this to walk back from an anonymous inner class
          * symbol to the method that declared it.
          *
          * Equality is structural across both fields (case class auto-generation).
          */
        final case class EnclosingMethod(owner: Symbol, methodName: Name) derives Schema, CanEqual

        /** JVM-only metadata attached to symbols sourced from `.class` files.
          *
          * Carried by `Symbol.javaMetadata: Maybe[Java.Metadata]` and `Absent` on symbols that come from TASTy
          * sources only (where the equivalent information lives in `Symbol.flags`, `Annotation`, etc.). This
          * companion exposes the JVM-specific attributes that have no clean TASTy analogue: the JVM access flag
          * word, the `throws` clause, the `EnclosingMethod` attribute for local / anonymous classes, the `Record`
          * component table for Java records, the bootstrap method table, the nest host / nest members for
          * Java 11+ nestmates, parameter-name groups, and runtime type annotations.
          *
          * **Annotations.** `annotations` carries `RuntimeVisibleAnnotations` and `RuntimeInvisibleAnnotations`
          * decoded into the `Java.Annotation` ADT; `runtimeTypeAnnotations` covers the type-annotation flavour
          * (`RuntimeVisibleTypeAnnotations` and its invisible sibling). Scala-side annotations on the same symbol
          * still live in the symbol's `annotations` field, not here.
          *
          * **Access flags.** `accessFlags` is the raw 16-bit access flag word; `isJvmPublic`, `isJvmPrivate`,
          * `isJvmProtected`, `isJvmStatic`, and `isJvmFinal` are the common predicates. For flags without a
          * predicate, mask `accessFlags` against the JVMS constants directly.
          */
        final case class Metadata(
            throwsTypes: Chunk[Type],
            annotations: Chunk[Annotation],
            enclosingMethod: Maybe[EnclosingMethod],
            accessFlags: Int,
            recordComponents: Chunk[RecordComponent],
            bootstrapMethods: Chunk[Chunk[Int]],
            nestHost: Maybe[Symbol],
            nestMembers: Chunk[Symbol],
            paramNames: Chunk[ParamGroup],
            runtimeTypeAnnotations: Chunk[Annotation]
        ) derives Schema, CanEqual

        /** A Java retention-class annotation decoded from a `.class` file's
          * `RuntimeVisibleAnnotations` / `RuntimeInvisibleAnnotations` attribute.
          *
          * Kept structurally separate from `Tasty.Annotation` (the Scala-source annotation type) because the value
          * spaces are different: a Java annotation's element values are primitive constants, class literals, enum
          * constants, nested annotations, and arrays thereof, while a Scala annotation carries arbitrary
          * `Tree.Apply` arguments. Mixing the two into a single ADT would require either lossy normalisation or
          * a sum type at every callsite; keeping them parallel keeps each side honest.
          *
          * `annotationClass` is the resolved `Symbol` for the annotation interface (e.g. the symbol of
          * `java.lang.SuppressWarnings`). `values` is the ordered list of `(elementName, value)` pairs as they
          * appeared in the classfile; element names are interned `Name` values and ordering matches the source
          * declaration. Element values are typed via the `Java.Annotation.Value` enum nested in the companion.
          *
          * `annotationFullName` holds the dotted fully-qualified name of the annotation interface (e.g. `"java.lang.Override"`),
          * derived at decode time from the JVM field descriptor. Matches the key accepted by
          * `Classpath.findAnnotation` and `Classpath.hasAnnotation`.
          *
          * **Querying.** `Classpath.hasAnnotation` and `Classpath.findAnnotation` walk both this list and the
          * Scala `annotations` list (where applicable), so the common "is this symbol annotated with X" question
          * does not need to branch on the value space.
          */
        final case class Annotation(
            annotationClass: Symbol,
            values: Chunk[(Name, Annotation.Value)],
            annotationFullName: kyo.Tasty.Name
        ) extends kyo.Tasty.AnnotationLike
        object Annotation:
            /** Typed value space for a Java annotation element. Mirrors the `element_value` shapes defined by
              * JVMS §4.7.16.1: every primitive constant, string, class literal, enum constant, nested annotation,
              * and array of any of those. Cases nest recursively through `ArrayVal` and `AnnotationVal`.
              *
              * Note: the JVM annotation format collapses `char`, `byte`, and `short` element values into
              * `IntVal`, so there are no separate `CharVal`, `ByteVal`, or `ShortVal` cases.
              */
            enum Value derives CanEqual:
                case StringVal(s: String)
                case IntVal(i: Int)
                case LongVal(l: Long)
                case FloatVal(f: Float)
                case DoubleVal(d: Double)
                case BoolVal(b: Boolean)
                case ClassVal(tpe: Type)
                case EnumVal(enumType: Symbol, constant: Name)
                case ArrayVal(elements: Chunk[Value])
                case AnnotationVal(nested: Annotation)
            end Value
        end Annotation

        // Schema for Java.Annotation.Value: recursive type requires a lazy given.
        // Java.Annotation.Value.AnnotationVal contains Annotation which contains Value.
        // The lazy initialization breaks the compile-time recursion.
        // Unsafe: Schema derivation cycle sentinel.
        //   Schema.derived for Annotation and Annotation.Value forms a mutual reference cycle:
        //   Annotation.Value.AnnotationVal contains Annotation, which contains Value. A null
        //   sentinel with once-per-process initialization guarded by null-check breaks the cycle
        //   without allocating on every derived-Schema call. The init is not synchronized; the
        //   derivation is idempotent so a concurrent double-derive is benign (both writers produce
        //   an equal value). Replacing with Maybe[Schema[X]] would require invasive changes to
        //   Schema.derived and an extra Maybe wrapper at every derived-Schema call site; the cycle
        //   break is therefore structural, not JMH-measured.
        //   Authorized as a non-defaultable structural cycle break.
        private var _schemaAnnotationValue: Schema[Annotation.Value] =
            null.asInstanceOf[Schema[Annotation.Value]]
        given schemaAnnotationValue: Schema[Annotation.Value] =
            if _schemaAnnotationValue == null then
                _schemaAnnotationValue = Schema.derived[Annotation.Value]
            _schemaAnnotationValue
        end schemaAnnotationValue

        // Unsafe: same Schema derivation cycle sentinel as _schemaAnnotationValue.
        //   Annotation itself references Annotation.Value in its fields, completing the mutual
        //   cycle. This paired sentinel uses the same null-sentinel lazy init: the derivation is
        //   idempotent so a concurrent double-derive is benign (both writers produce an equal value).
        //   Authorized as a non-defaultable structural cycle break.
        private var _schemaAnnotation: Schema[Annotation] = null.asInstanceOf[Schema[Annotation]]
        given schemaAnnotation: Schema[Annotation] =
            if _schemaAnnotation == null then
                _schemaAnnotation = Schema.derived[Annotation]
            _schemaAnnotation
        end schemaAnnotation

        given canEqualAnnotationValue: CanEqual[Annotation.Value, Annotation.Value] = CanEqual.canEqualAny
        given canEqualAnnotation: CanEqual[Annotation, Annotation]                  = CanEqual.canEqualAny

        /** JPMS module-info.class types. */
        object Module:

            /** Parsed content of a module-info.class file.
              *
              * Produced by `ModuleInfoReader.read` and stored in the classpath `moduleIndex` after a successful parse.
              *
              * @param name
              *   The module name (e.g., "java.base").
              * @param version
              *   The module version string, if present in the `module-info.class`.
              * @param requires
              *   All requires directives.
              * @param exports
              *   All exports directives.
              * @param opens
              *   All opens directives.
              * @param uses
              *   Service interfaces used by this module (dotted class names).
              * @param provides
              *   Service implementations provided by this module.
              */
            final case class Descriptor(
                name: String,
                version: Maybe[String],
                requires: Chunk[Requires],
                exports: Chunk[Exports],
                opens: Chunk[Opens],
                uses: Chunk[String],
                provides: Chunk[Provides]
            ) derives Schema, CanEqual

            /** Describes a JPMS `requires` directive (one dependency of a module).
              *
              * @param name
              *   The required module name (e.g., "java.base").
              * @param version
              *   The required module version, if specified.
              * @param isTransitive
              *   True if the requires has `ACC_TRANSITIVE` (0x0020) set.
              * @param isStaticPhase
              *   True if the requires has `ACC_STATIC_PHASE` (0x0040) set.
              */
            final case class Requires(
                name: String,
                version: Maybe[String],
                isTransitive: Boolean,
                isStaticPhase: Boolean
            ) derives Schema, CanEqual

            /** Describes a JPMS `exports` directive (a package exported to zero or more modules).
              *
              * @param packageName
              *   The exported package name in dotted form (e.g., "java.lang").
              * @param targets
              *   The module names this package is exported to. Empty chunk means exported unconditionally (unqualified export).
              * @param flags
              *   The raw `exports_flags` value from the classfile Module attribute (JVMS §4.7.25). The `ACC_EXPORTS_SYNTHETIC` bit (0x1000)
              *   indicates the directive was generated by the compiler and not present in the source.
              */
            final case class Exports(
                packageName: String,
                targets: Chunk[String],
                flags: Long
            ) derives Schema, CanEqual

            /** Describes a JPMS `opens` directive (a package opened for deep reflection).
              *
              * @param packageName
              *   The opened package name in dotted form.
              * @param targets
              *   The module names this package is opened to. Empty chunk means opened unconditionally.
              * @param flags
              *   The raw `opens_flags` value from the classfile Module attribute (JVMS §4.7.25). The `ACC_OPENS_SYNTHETIC` bit (0x1000) indicates
              *   the directive was generated by the compiler and not present in the source.
              */
            final case class Opens(
                packageName: String,
                targets: Chunk[String],
                flags: Long
            ) derives Schema, CanEqual

            /** Describes a JPMS `provides` directive (a service implementation).
              *
              * @param serviceName
              *   The service interface class name in dotted form.
              * @param implementations
              *   The implementation class names in dotted form.
              */
            final case class Provides(
                serviceName: String,
                implementations: Chunk[String]
            ) derives Schema, CanEqual

        end Module

    end Java

    // ── Pickle (in-memory TASTy + classfile bytes) ──────────────────────────

    /** In-memory TASTy pickle: header UUID, format version, and raw `.tasty` bytes.
      *
      * A `Pickle` is the smallest input unit the classpath loader accepts: one `.tasty` file decoded into
      * its header and body. `Tasty.withPickles` takes a `Chunk[Pickle]` for tests and out-of-band classpath
      * construction. The standard `Tasty.withClasspath` path reads pickles from files and JARs internally
      * and never exposes them to user code.
      *
      * `bytes` is the unmodified `.tasty` payload (header included). `uuid` is the TASTy header UUID as a
      * hex string. `TastyError.InconsistentClasspath` reports UUID mismatches as `Tasty.Uuid` values.
      *
      * Equality is structural over all three fields.
      */
    final case class Pickle(uuid: String, version: Version, bytes: Span[Byte]) derives Schema, CanEqual:
        /** Human-readable summary: `Pickle(<uuid> v<version> <n>B)`. */
        def show: String = s"Pickle($uuid v${version.show} ${bytes.size}B)"
    end Pickle

    // ── Classpath ───────────────────────────────────────────────────────────

    /** Immutable snapshot of a fully-loaded TASTy classpath.
      *
      * All fields are pure immutable data. Reading any field is a pure operation. Direct lookups
      * (`findClass`, `findSymbol`, `companion`, `directSubclassesOf`, ...) live on this class; effectful
      * cross-binding queries live on `object Tasty.*`.
      *
      * Instances are obtained via `Tasty.withClasspath`, `Tasty.withPickles`, or by reading
      * `Tasty.classpath` inside an active scope.
      */
    final case class Classpath(
        symbols: Chunk[Symbol],
        indices: Classpath.Indices,
        errors: Chunk[TastyError],
        modules: Chunk[Java.Module.Descriptor],
        rootSymbolId: SymbolId
    ):

        private def symbolsOfKind[A <: Symbol](k: SymbolKind): Chunk[A] =
            if symbols.isEmpty then Chunk.empty
            else
                val b = Chunk.newBuilder[A]
                // guarded by s.kind == k; the kind discriminant ensures the runtime type matches A.
                symbols.foreach(s => if s.kind == k then b += s.asInstanceOf[A])
                b.result()

        /** O(1) Symbol lookup by SymbolId. Returns the Symbol at index `id.value`, or `Maybe.Absent` for out-of-range or negative ids.
          *
          * SymbolIds are only valid within the Classpath that produced them. Passing a SymbolId from one classpath into another classpath's
          * `symbol(id)` returns whatever Symbol happens to sit at that index in the receiving classpath (usually an unrelated symbol),
          * not the originating one. Cross-classpath operations should resolve by fully-qualified name via `findSymbol` / `findClass` / `findObject`, not
          * by SymbolId.
          *
          * Returns `Maybe.Absent` for:
          *   - `id.value < 0`: any negative index including the canonical sentinel -1.
          *   - `id.value >= symbols.length`: out-of-range positive index.
          *   - Empty classpath (`symbols.isEmpty`, `rootSymbolId.value == -1`): `classpath.symbol(classpath.rootSymbolId)` returns `Maybe.Absent`.
          */
        def symbol(id: SymbolId): Maybe[Symbol] =
            val idx = SymbolId.value(id)
            if idx >= 0 && idx < symbols.length then Maybe.Present(symbols(idx))
            else Maybe.Absent
        end symbol

        /** Look up any symbol by fully-qualified dotted name.
          *
          * Pure O(1) lookup in the immutable `indices.byFullName` map. Returns `Absent` if the fully-qualified name is not registered. For typed lookups that
          * narrow to a specific subtype, use `findClass`, `findTrait`, `findObject`, `findClassLike`, or `findPackage`.
          *
          * Null safety: a `null` `fullName` argument resolves to `Maybe.Absent` (Scala Map.get(null) returns None). No NPE is raised.
          * Defensive null checks in call sites are unnecessary.
          */
        def findSymbol(fullName: String): Maybe[Symbol] =
            indices.byFullName.get(fullName) match
                case Maybe.Present(id) => symbol(id)
                case Maybe.Absent      => Maybe.Absent

        /** Look up a class symbol by fully-qualified dotted name.
          *
          * Pure O(1) lookup in the immutable `indices.byFullName` map. Returns `Absent` if the fully-qualified name is not registered or resolves to a non-Class
          * symbol (e.g., a Trait or Object). Use `findClassLike` to match any class-like symbol regardless of subtype.
          *
          * Includes sealed abstract classes (e.g. `scala.Option`); use `findConcreteClass` to restrict to non-abstract classes.
          *
          * Null safety: a `null` `fullName` argument returns `Maybe.Absent`; no NPE is raised. An empty string `""` also returns
          * `Maybe.Absent` because no symbol is registered under the empty key.
          *
          * Example:
          * ```scala
          *   val symbol: Maybe[Symbol.Class] = classpath.findClass("scala.collection.List")
          * ```
          */
        def findClass(fullName: String): Maybe[Symbol.Class] =
            indices.byFullName.get(fullName) match
                case Maybe.Present(id) =>
                    symbol(id).flatMap {
                        case c: Symbol.Class        => Maybe(c)
                        case _: Symbol.EnumCase     => Maybe.Absent
                        case _: Symbol.Trait        => Maybe.Absent
                        case _: Symbol.Object       => Maybe.Absent
                        case _: Symbol.Method       => Maybe.Absent
                        case _: Symbol.Val          => Maybe.Absent
                        case _: Symbol.Var          => Maybe.Absent
                        case _: Symbol.Field        => Maybe.Absent
                        case _: Symbol.TypeAlias    => Maybe.Absent
                        case _: Symbol.OpaqueType   => Maybe.Absent
                        case _: Symbol.AbstractType => Maybe.Absent
                        case _: Symbol.TypeParam    => Maybe.Absent
                        case _: Symbol.Parameter    => Maybe.Absent
                        case _: Symbol.Package      => Maybe.Absent
                    }
                case Maybe.Absent => Maybe.Absent

        /** Look up a concrete (non-abstract) class symbol by fully-qualified dotted name.
          *
          * Returns `Absent` when the fully-qualified name is not registered, when the symbol is not a Class, or when the matched Class has the Abstract
          * flag set (e.g. `scala.Option`, `scala.Either`). Use `findClass` when abstract classes are acceptable.
          *
          * `findClass` remains permissive (returns sealed abstract classes); this method is the narrow accessor for callers that
          * need a concrete, instantiable class.
          *
          * Example:
          * ```scala
          *   classpath.findConcreteClass("scala.Some")    // Present(_)
          *   classpath.findConcreteClass("scala.Option")  // Absent (abstract)
          * ```
          */
        def findConcreteClass(fullName: String): Maybe[Symbol.Class] =
            findClass(fullName).filter(!_.isAbstract)

        /** Count of type references that could not be resolved to a final SymbolId after all resolution passes.
          *
          * Nonzero values indicate cross-file TYPEREFsymbol targets not found in the loaded classpath
          * (e.g., JDK types when no JDK roots are passed to `Tasty.withClasspath`). This metric provides
          * visibility into how many Named(-1) sentinels remain in parentTypes after the cross-file
          * resolution pass.
          *
          * Note: a count > 0 is expected behavior when the classpath does not include all transitive
          * dependencies. It is not an error condition.
          *
          * Performance: O(symbols) per call; not cached. For repeated access, callers should cache the result.
          */
        def unresolvedTypeReferenceCount: Int =
            // Count parent-type references that point to a symbol not on this classpath.
            // Negative SymbolIds fall into two categories:
            //   (a) ids tracked in unresolvedFullNameByNegId: fully-qualified name-tracked cross-classpath refs
            //       whose defining library was absent. These are "truly unresolved."
            //   (b) other negative ids (e.g., decode-phase TERMREFdirect misses, -1 sentinel):
            //       internal artifacts; not user-visible cross-classpath gaps.
            // Only category (a) is counted here.
            val tracked = indices.unresolvedFullNameByNegId
            symbols.foldLeft(0) { (acc, symbol) =>
                symbol match
                    case c: Symbol.ClassLike =>
                        acc + c.parentTypes.count {
                            case Type.Named(id)          => id.value < 0 && tracked.contains(id)
                            case _: Type.TermRef         => false
                            case _: Type.Applied         => false
                            case _: Type.TypeLambda      => false
                            case _: Type.Function        => false
                            case _: Type.ContextFunction => false
                            case _: Type.Tuple           => false
                            case _: Type.ByName          => false
                            case _: Type.Repeated        => false
                            case _: Type.Array           => false
                            case _: Type.Refinement      => false
                            case _: Type.Rec             => false
                            case _: Type.RecThis         => false
                            case _: Type.AndType         => false
                            case _: Type.OrType          => false
                            case _: Type.Annotated       => false
                            case _: Type.ConstantType    => false
                            case _: Type.ThisType        => false
                            case _: Type.SuperType       => false
                            case _: Type.ParamRef        => false
                            case _: Type.Wildcard        => false
                            case _: Type.Skolem          => false
                            case _: Type.MatchType       => false
                            case _: Type.FlexibleType    => false
                            case _: Type.MatchCase       => false
                            case _: Type.Bind            => false
                            case _: Type.TypeRef         => false
                            case _: Type.Bounds          => false
                            case Type.Nothing            => false
                            case Type.Any                => false
                        }
                    case _: Symbol.Method       => acc
                    case _: Symbol.Val          => acc
                    case _: Symbol.Var          => acc
                    case _: Symbol.Field        => acc
                    case _: Symbol.TypeAlias    => acc
                    case _: Symbol.OpaqueType   => acc
                    case _: Symbol.AbstractType => acc
                    case _: Symbol.TypeParam    => acc
                    case _: Symbol.Parameter    => acc
                    case _: Symbol.Package      => acc
            }
        end unresolvedTypeReferenceCount

        /** Look up a trait symbol by fully-qualified dotted name.
          *
          * Returns `Absent` when the fully-qualified name resolves to a non-Trait symbol. Use `findClassLike` for the broader case.
          */
        def findTrait(fullName: String): Maybe[Symbol.Trait] =
            indices.byFullName.get(fullName) match
                case Maybe.Present(id) =>
                    symbol(id).flatMap {
                        case t: Symbol.Trait        => Maybe(t)
                        case _: Symbol.Class        => Maybe.Absent
                        case _: Symbol.EnumCase     => Maybe.Absent
                        case _: Symbol.Object       => Maybe.Absent
                        case _: Symbol.Method       => Maybe.Absent
                        case _: Symbol.Val          => Maybe.Absent
                        case _: Symbol.Var          => Maybe.Absent
                        case _: Symbol.Field        => Maybe.Absent
                        case _: Symbol.TypeAlias    => Maybe.Absent
                        case _: Symbol.OpaqueType   => Maybe.Absent
                        case _: Symbol.AbstractType => Maybe.Absent
                        case _: Symbol.TypeParam    => Maybe.Absent
                        case _: Symbol.Parameter    => Maybe.Absent
                        case _: Symbol.Package      => Maybe.Absent
                    }
                case Maybe.Absent => Maybe.Absent

        /** Look up an object symbol by fully-qualified dotted name.
          *
          * Accepts both source-form names (e.g. `"foo.Bar"`) and binary names with a trailing `$`
          * (e.g. `"foo.Bar$"`). When the source-form fully-qualified name resolves to a non-Object symbol (for example,
          * a case class whose companion Object is stored under the binary `"foo.Bar$"` key because
          * the source-form key is taken by the class), the method automatically falls back to looking
          * up `fullName + "$"`. This handles the Scala 3 case-class companion pattern where both the class
          * and its companion object share the same source-form name but the object is indexed under the
          * `$`-suffixed binary name.
          *
          * Returns `Absent` when neither key resolves to an Object symbol.
          */
        def findObject(fullName: String): Maybe[Symbol.Object] =
            def lookupObj(id: SymbolId): Maybe[Symbol.Object] =
                symbol(id).flatMap {
                    case o: Symbol.Object       => Maybe(o)
                    case _: Symbol.Class        => Maybe.Absent
                    case _: Symbol.EnumCase     => Maybe.Absent
                    case _: Symbol.Trait        => Maybe.Absent
                    case _: Symbol.Method       => Maybe.Absent
                    case _: Symbol.Val          => Maybe.Absent
                    case _: Symbol.Var          => Maybe.Absent
                    case _: Symbol.Field        => Maybe.Absent
                    case _: Symbol.TypeAlias    => Maybe.Absent
                    case _: Symbol.OpaqueType   => Maybe.Absent
                    case _: Symbol.AbstractType => Maybe.Absent
                    case _: Symbol.TypeParam    => Maybe.Absent
                    case _: Symbol.Parameter    => Maybe.Absent
                    case _: Symbol.Package      => Maybe.Absent
                }
            def tryDollar(f: String): Maybe[Symbol.Object] =
                if f.endsWith("$") then Maybe.Absent
                else
                    indices.byFullName.get(f + "$") match
                        case Maybe.Present(id2) => lookupObj(id2)
                        case Maybe.Absent       => Maybe.Absent
            indices.byFullName.get(fullName) match
                case Maybe.Present(id) =>
                    val direct = lookupObj(id)
                    if direct.isDefined then direct
                    // Source-form fully-qualified name is taken by a non-Object (e.g. the case class itself).
                    // Fall back to the binary $-suffixed key where the companion Object lives.
                    else tryDollar(fullName)
                    end if
                case Maybe.Absent =>
                    // No entry at the source-form key; try the binary $-suffixed key directly.
                    tryDollar(fullName)
            end match
        end findObject

        /** Look up a class-like symbol (Class, Trait, or Object) by fully-qualified dotted name.
          *
          * Returns `Absent` when the fully-qualified name resolves to a Package or other non-ClassLike symbol.
          */
        def findClassLike(fullName: String): Maybe[Symbol.ClassLike] =
            indices.byFullName.get(fullName) match
                case Maybe.Present(id) =>
                    symbol(id).flatMap {
                        case c: Symbol.ClassLike    => Maybe(c)
                        case _: Symbol.Method       => Maybe.Absent
                        case _: Symbol.Val          => Maybe.Absent
                        case _: Symbol.Var          => Maybe.Absent
                        case _: Symbol.Field        => Maybe.Absent
                        case _: Symbol.TypeAlias    => Maybe.Absent
                        case _: Symbol.OpaqueType   => Maybe.Absent
                        case _: Symbol.AbstractType => Maybe.Absent
                        case _: Symbol.TypeParam    => Maybe.Absent
                        case _: Symbol.Parameter    => Maybe.Absent
                        case _: Symbol.Package      => Maybe.Absent
                    }
                case Maybe.Absent => Maybe.Absent

        /** Look up a package symbol by fully-qualified dotted name.
          *
          * Pure O(1) lookup in the immutable packageIndex. Returns `Absent` if the package is not in this classpath.
          */
        def findPackage(fullName: String): Maybe[Symbol.Package] =
            indices.packageIndex.get(fullName) match
                case Maybe.Present(id) =>
                    symbol(id).flatMap {
                        case p: Symbol.Package      => Maybe(p)
                        case _: Symbol.Class        => Maybe.Absent
                        case _: Symbol.EnumCase     => Maybe.Absent
                        case _: Symbol.Trait        => Maybe.Absent
                        case _: Symbol.Object       => Maybe.Absent
                        case _: Symbol.Method       => Maybe.Absent
                        case _: Symbol.Val          => Maybe.Absent
                        case _: Symbol.Var          => Maybe.Absent
                        case _: Symbol.Field        => Maybe.Absent
                        case _: Symbol.TypeAlias    => Maybe.Absent
                        case _: Symbol.OpaqueType   => Maybe.Absent
                        case _: Symbol.AbstractType => Maybe.Absent
                        case _: Symbol.TypeParam    => Maybe.Absent
                        case _: Symbol.Parameter    => Maybe.Absent
                    }
                case Maybe.Absent => Maybe.Absent

        /** Find all `Symbol.Class` instances whose simple name equals `simpleName`.
          *
          * Returns an empty Chunk when no match is found.
          *
          * Performance: O(1) lookup via `indices.bySimpleName`, a pre-built simple-name to SymbolId-chunk
          * index populated during classpath construction. The index is part of the immutable `Indices`
          * case class and is preserved across `classpath.copy(...)` calls that do not replace the `indices` field.
          */
        def findClassesByName(simpleName: String): Chunk[Symbol.Class] =
            indices.bySimpleName.getOrElse(simpleName, Chunk.empty).flatMap { id =>
                symbol(id) match
                    case Maybe.Present(c: Symbol.Class) => Chunk(c)
                    case _                              => Chunk.empty
            }
        end findClassesByName

        /** All package symbols in this classpath.
          *
          * Pure accessor over the immutable `packageIds` Chunk. Each id is resolved and narrowed to `Symbol.Package`; ids that resolve to
          * non-Package symbols are excluded.
          */
        def packages: Chunk[Symbol.Package] =
            indices.packageIds.flatMap { id =>
                symbol(id) match
                    case Maybe.Present(p: Symbol.Package) => Chunk(p)
                    case _                                => Chunk.empty
            }

        /** All top-level class-like symbols (not packages) in this classpath.
          *
          * Pure accessor over the immutable `topLevelClassIds` Chunk. Each id is resolved and narrowed to `Symbol.ClassLike`; ids that
          * resolve to non-ClassLike symbols are excluded.
          */
        def topLevelClasses: Chunk[Symbol.ClassLike] =
            indices.topLevelClassIds.flatMap { id =>
                symbol(id) match
                    case Maybe.Present(c: Symbol.ClassLike) => Chunk(c)
                    case _                                  => Chunk.empty
            }

        /** Look up a JPMS module descriptor by module name (e.g., "java.base").
          *
          * Returns `Present(descriptor)` if a `module-info.class` with the given module name was found in the classpath roots. Returns
          * `Absent` if no matching module was found.
          *
          * O(1) lookup via the modulesIndex in Indices.
          */
        def findModule(name: String): Maybe[Java.Module.Descriptor] =
            indices.modulesIndex.get(name)

        /** Find a class symbol by JVM binary name (e.g., "com/example/Foo$Inner").
          *
          * Converts the binary name to a dotted fully-qualified name and delegates to `findClass`. Returns `Maybe[Symbol.Class]`.
          *
          * Pure O(1) lookup; no I/O.
          *
          * Nested-class handling: the naive `'/' -> '.'` and `'$' -> '.'` translation fails for binary names that include
          * anonymous-class or local-class suffixes such as `com/example/Foo$1` (produces `com.example.Foo.1`) or
          * `com/example/Foo$$anon$1` (produces `com.example.Foo..anon.1` with a double dot). This method passes the translated dotted
          * name through `FullNameNormalizer.canonicalSourceFullName` to apply the same normalization rules as the cold-load path, so that most
          * named inner classes resolve correctly. Truly anonymous classes (`$1`, `$anon$N`) remain unresolvable via this method because
          * they are excluded from user-facing indexes (they carry `isSyntheticName == true`).
          */
        def findClassByBinary(binaryName: String): Maybe[Symbol.Class] =
            // First translate slashes to dots (standard binary-to-fully-qualified-name conversion).
            val dotted = binaryName.replace('/', '.')
            // Apply the same fully-qualified name normalization as the cold-load path. This handles named inner classes
            // encoded as Outer$Inner (produced by javac) and converts them to Outer.Inner.
            val fullName = kyo.internal.tasty.symbol.FullNameNormalizer.canonicalSourceFullName(dotted)
            findClass(fullName)
        end findClassByBinary

        // ── require* throwing variants ──

        /** Require a class by fully-qualified name; fails with `TastyError.InvalidFullName` when `fullName` is empty, or `TastyError.NotFound` when absent.
          *
          * Empty-string behavior: an empty `fullName` is a caller-side programming error. Rather than returning
          * `TastyError.NotFound("")` (which looks like a normal lookup miss), this method raises
          * `TastyError.InvalidFullName("", "fullName must be non-empty")` so the caller can distinguish a bad input from a genuine
          * not-found result.
          */
        def requireClass(fullName: String)(using Frame): Symbol.Class < Abort[TastyError] =
            if fullName.isEmpty then Abort.fail(TastyError.InvalidFullName(fullName, "fullName must be non-empty"))
            else
                findClass(fullName) match
                    case Maybe.Present(c) => c
                    case Maybe.Absent     => Abort.fail(TastyError.NotFound(fullName))

        /** Require a trait by fully-qualified name; fails with `TastyError.InvalidFullName` when `fullName` is empty, or `TastyError.NotFound` when absent. */
        def requireTrait(fullName: String)(using Frame): Symbol.Trait < Abort[TastyError] =
            if fullName.isEmpty then Abort.fail(TastyError.InvalidFullName(fullName, "fullName must be non-empty"))
            else
                findTrait(fullName) match
                    case Maybe.Present(t) => t
                    case Maybe.Absent     => Abort.fail(TastyError.NotFound(fullName))

        /** Require an object by fully-qualified name; fails with `TastyError.InvalidFullName` when `fullName` is empty, or `TastyError.NotFound` when absent. */
        def requireObject(fullName: String)(using Frame): Symbol.Object < Abort[TastyError] =
            if fullName.isEmpty then Abort.fail(TastyError.InvalidFullName(fullName, "fullName must be non-empty"))
            else
                findObject(fullName) match
                    case Maybe.Present(o) => o
                    case Maybe.Absent     => Abort.fail(TastyError.NotFound(fullName))

        /** Require a class-like by fully-qualified name; fails with `TastyError.InvalidFullName` when `fullName` is empty, or `TastyError.NotFound` when absent. */
        def requireClassLike(fullName: String)(using Frame): Symbol.ClassLike < Abort[TastyError] =
            if fullName.isEmpty then Abort.fail(TastyError.InvalidFullName(fullName, "fullName must be non-empty"))
            else
                findClassLike(fullName) match
                    case Maybe.Present(c) => c
                    case Maybe.Absent     => Abort.fail(TastyError.NotFound(fullName))

        /** Require a package by fully-qualified name; fails with `TastyError.InvalidFullName` when `fullName` is empty, or `TastyError.NotFound` when absent. */
        def requirePackage(fullName: String)(using Frame): Symbol.Package < Abort[TastyError] =
            if fullName.isEmpty then Abort.fail(TastyError.InvalidFullName(fullName, "fullName must be non-empty"))
            else
                findPackage(fullName) match
                    case Maybe.Present(p) => p
                    case Maybe.Absent     => Abort.fail(TastyError.NotFound(fullName))

        /** Require a JPMS module descriptor by name; fails with `TastyError.InvalidFullName` when `name` is empty, or `TastyError.NotFound`
          * when absent.
          */
        def requireModule(name: String)(using Frame): Java.Module.Descriptor < Abort[TastyError] =
            if name.isEmpty then Abort.fail(TastyError.InvalidFullName(name, "fullName must be non-empty"))
            else
                findModule(name) match
                    case Maybe.Present(m) => m
                    case Maybe.Absent     => Abort.fail(TastyError.NotFound(name))

        /** Require any symbol by fully-qualified dotted name; fails with `TastyError.InvalidFullName` when `fullName` is empty, or
          * `TastyError.NotFound` when absent.
          *
          * This accessor replaces the accidental `findSymbol(fullName).get` pattern that would throw a `NoSuchElementException` at runtime.
          * Unlike `requireClass` / `requireTrait` / `requireObject`, this method does not narrow the kind: any registered symbol satisfies
          * the lookup regardless of its `SymbolKind`. The absent case is funneled into the same `NotFound` variant the kind-specific
          * `requireX` methods use, so callers do not have to distinguish two near-identical absent shapes.
          */
        def requireSymbol(fullName: String)(using Frame): Symbol < Abort[TastyError] =
            if fullName.isEmpty then Abort.fail(TastyError.InvalidFullName(fullName, "fullName must be non-empty"))
            else
                findSymbol(fullName) match
                    case Maybe.Present(s) => s
                    case Maybe.Absent     => Abort.fail(TastyError.NotFound(fullName))

        /** Return all fully-qualified name collisions recorded during classpath initialization.
          *
          * A collision arises when two distinct source roots each define a symbol under the same fully-qualified name. Under
          * `ErrorMode.SoftFail`, every collision is recorded here; `findSymbol(fullName)` still returns a deterministic winner (last-write-wins by
          * root insertion order). Under `ErrorMode.FailFast`, initialization aborts with `TastyError.InconsistentClasspath` on the first
          * collision, so this chunk is always empty when FailFast is used.
          *
          * Returns an empty `Chunk` when no collisions occurred.
          */
        def collisionReport: Chunk[Classpath.FullNameCollision] =
            indices.diagnostics.collect {
                case c: Classpath.FullNameCollision => c
            }

        /** Structural subtype check between two `Type` values.
          *
          * Returns `Result.Success(verdict)` for the three-way `SubtypeVerdict`, or
          * `Result.Failure(TastyError.UnhandledSubtypingCase)` when the parent-walk encounters
          * a type shape not handled by the subtyping engine.
          *
          * Pure: reads only from this classpath's immutable `Symbol.parentTypes` fields. No effects
          * required. The `Result.Failure` carries the unhandled case for the caller to surface via
          * `Abort.get` when an effect scope is in context.
          *
          * @param tpe   the candidate subtype
          * @param other the candidate supertype
          * @return `Result.Success(SubtypeVerdict)` or `Result.Failure(TastyError.UnhandledSubtypingCase)`
          */
        def isSubtypeOf(tpe: Type, other: Type): Result[TastyError, SubtypeVerdict] =
            kyo.internal.tasty.type_.Subtyping.isSubtype(tpe, other, this, budget = 64)

        /** Resolve the symbol referenced by a `Type.Named`.
          *
          * Returns `Maybe.Present(symbol)` when `tpe` is `Type.Named` and the `SymbolId` resolves
          * to a live entry in this classpath's symbol table. Returns `Maybe.Absent` for all other
          * `Type` shapes: `Type.Applied`, `Type.Function`, `Type.Array`, `Type.TermRef`, and
          * the remaining 25 structural and leaf cases. The check is exhaustive over all 29 `Type`
          * cases; adding a new `Type` variant causes a compile error here.
          *
          * Pure: reads only from the immutable symbol table. No effects required.
          *
          * @param tpe the type whose referenced symbol is requested
          * @return the symbol wrapped in `Maybe.Present`, or `Maybe.Absent` for non-Named types
          */
        def typeSymbol(tpe: Type): Maybe[Symbol] = tpe match
            case Type.Named(id)             => symbol(id)
            case Type.TermRef(_, _)         => Maybe.Absent
            case Type.Applied(_, _)         => Maybe.Absent
            case Type.TypeLambda(_, _)      => Maybe.Absent
            case Type.Function(_, _)        => Maybe.Absent
            case Type.ContextFunction(_, _) => Maybe.Absent
            case Type.Tuple(_)              => Maybe.Absent
            case Type.ByName(_)             => Maybe.Absent
            case Type.Repeated(_)           => Maybe.Absent
            case Type.Array(_)              => Maybe.Absent
            case Type.Refinement(_, _, _)   => Maybe.Absent
            case Type.Rec(_)                => Maybe.Absent
            case Type.RecThis(_)            => Maybe.Absent
            case Type.AndType(_, _)         => Maybe.Absent
            case Type.OrType(_, _)          => Maybe.Absent
            case Type.Annotated(_, _)       => Maybe.Absent
            case Type.ConstantType(_)       => Maybe.Absent
            case Type.ThisType(_)           => Maybe.Absent
            case Type.SuperType(_, _)       => Maybe.Absent
            case Type.ParamRef(_, _)        => Maybe.Absent
            case Type.Wildcard(_, _)        => Maybe.Absent
            case Type.Skolem(_)             => Maybe.Absent
            case Type.MatchType(_, _, _)    => Maybe.Absent
            case Type.FlexibleType(_)       => Maybe.Absent
            case Type.MatchCase(_, _)       => Maybe.Absent
            case Type.Bind(_, _)            => Maybe.Absent
            case Type.TypeRef(_, _)         => Maybe.Absent
            case Type.Bounds(_, _)          => Maybe.Absent
            case Type.Nothing               => Maybe.Absent
            case Type.Any                   => Maybe.Absent

        /** Render a `Type` as a human-readable Scala-like string.
          *
          * Converts every `Type` case to a display string. `Type.Named` nodes are resolved to
          * their symbol's simple name via the classpath; unresolved ids render as `"<unresolved>"`.
          * Composite types render in Scala-source syntax (`"List[String]"`, `"(Int) => Boolean"`,
          * `"Int & Serializable"`). The match is exhaustive over all 29 `Type` cases; adding a
          * new `Type` variant causes a compile error here.
          *
          * Pure: reads only from this classpath's immutable symbol table. No effects required.
          *
          * @param tpe the type to render
          * @return a human-readable string representation of the type
          */
        def typeShow(tpe: Type): String =
            import Name.asString
            tpe match
                case Type.Named(id) =>
                    symbol(id).map(_.name.asString).getOrElse("<unresolved>")
                case Type.TermRef(prefix, name) =>
                    s"${typeShow(prefix)}.${name.asString}"
                case Type.Applied(base, args) =>
                    s"${typeShow(base)}[${args.map(typeShow).mkString(", ")}]"
                case Type.TypeLambda(_, body) =>
                    s"[...] =>> ${typeShow(body)}"
                case Type.Function(params, result) =>
                    s"(${params.map(typeShow).mkString(", ")}) => ${typeShow(result)}"
                case Type.ContextFunction(params, result) =>
                    s"(${params.map(typeShow).mkString(", ")}) ?=> ${typeShow(result)}"
                case Type.Tuple(elements) =>
                    s"(${elements.map(typeShow).mkString(", ")})"
                case Type.ByName(underlying) =>
                    s"=> ${typeShow(underlying)}"
                case Type.Repeated(elem) =>
                    s"${typeShow(elem)}*"
                case Type.Array(elem) =>
                    s"${typeShow(elem)}[]"
                case Type.Refinement(parent, name, info) =>
                    s"${typeShow(parent)} { ${name.asString}: ${typeShow(info)} }"
                case Type.Rec(parent) =>
                    s"<rec: ${typeShow(parent)}>"
                case Type.RecThis(_) =>
                    "<recthis>"
                case Type.AndType(left, right) =>
                    s"${typeShow(left)} & ${typeShow(right)}"
                case Type.OrType(left, right) =>
                    s"${typeShow(left)} | ${typeShow(right)}"
                case Type.Annotated(underlying, _) =>
                    typeShow(underlying)
                case Type.ConstantType(value) =>
                    value.show
                case Type.ThisType(_) =>
                    "<this>"
                case Type.SuperType(self, mixin) =>
                    s"<super: ${typeShow(self)} with ${typeShow(mixin)}>"
                case Type.ParamRef(_, idx) =>
                    s"<param $idx>"
                case Type.Wildcard(lo, hi) =>
                    s"_ >: ${typeShow(lo)} <: ${typeShow(hi)}"
                case Type.Skolem(underlying) =>
                    s"<skolem: ${typeShow(underlying)}>"
                case Type.MatchType(_, scrutinee, cases) =>
                    s"${typeShow(scrutinee)} match { ${cases.map(typeShow).mkString("; ")} }"
                case Type.FlexibleType(underlying) =>
                    s"${typeShow(underlying)}?"
                case Type.MatchCase(pat, rhs) =>
                    s"case ${typeShow(pat)} => ${typeShow(rhs)}"
                case Type.Bind(name, pattern) =>
                    s"${name.asString} @ ${typeShow(pattern)}"
                case Type.TypeRef(qual, name) =>
                    s"${typeShow(qual)}.${name.asString}"
                case Type.Bounds(lo, hi) =>
                    s">: ${typeShow(lo)} <: ${typeShow(hi)}"
                case Type.Nothing =>
                    "Nothing"
                case Type.Any =>
                    "Any"
            end match
        end typeShow

        /** Render a `Tree` as a human-readable string, resolving symbols and types via this classpath.
          *
          * Delegates to the internal `TreeShow.show` rendering engine, which converts every
          * `Tree` case to a display string. Type-bearing nodes (such as `ValDef`, `DefDef`,
          * `Typed`, `New`) resolve their type via `typeShow`. The result is intended for
          * human inspection (diagnostics, logging, tests) rather than round-trip fidelity.
          *
          * Pure: reads only from this classpath's immutable data. No effects required.
          *
          * @param tree the tree to render
          * @return a human-readable string representation of the tree
          */
        def treeShow(tree: Tree): String =
            kyo.internal.tasty.reader.TreeShow.show(tree, this)

        // ── typed Classpath-wide all* aggregations ──

        /** All Trait symbols in the classpath. Linear scan over symbols. */
        def allTraits: Chunk[Symbol.Trait] = symbolsOfKind(SymbolKind.Trait)

        /** All Object symbols in the classpath. Linear scan over symbols. */
        def allObjects: Chunk[Symbol.Object] = symbolsOfKind(SymbolKind.Object)

        /** All ClassLike symbols (Class, Trait, Object, EnumCase) at any nesting depth.
          *
          * The invariant `allClassLike.size >= topLevelClasses.size` holds because the result includes
          * nested ClassLike symbols. Use `allTraits` / `allObjects` / `symbolsOfKind(SymbolKind.Class)`
          * to narrow to a specific subtype; the result here keeps the union of all four.
          */
        def allClassLike: Chunk[Symbol.ClassLike] =
            if symbols.isEmpty then Chunk.empty
            else
                val b = Chunk.newBuilder[Symbol.ClassLike]
                symbols.foreach {
                    case c: Symbol.ClassLike    => b += c
                    case _: Symbol.Method       => ()
                    case _: Symbol.Val          => ()
                    case _: Symbol.Var          => ()
                    case _: Symbol.Field        => ()
                    case _: Symbol.TypeAlias    => ()
                    case _: Symbol.OpaqueType   => ()
                    case _: Symbol.AbstractType => ()
                    case _: Symbol.TypeParam    => ()
                    case _: Symbol.Parameter    => ()
                    case _: Symbol.Package      => ()
                }
                b.result()

        /** All Method symbols in the classpath. O(n) scan over `symbols`. */
        def allMethods: Chunk[Symbol.Method] = symbolsOfKind(SymbolKind.Method)

        /** All Val symbols in the classpath. O(n) scan over `symbols`. */
        def allVals: Chunk[Symbol.Val] = symbolsOfKind(SymbolKind.Val)

        /** All Var symbols in the classpath. O(n) scan over `symbols`. */
        def allVars: Chunk[Symbol.Var] = symbolsOfKind(SymbolKind.Var)

        /** All Field symbols (Java-level) in the classpath. O(n) scan over `symbols`. */
        def allFields: Chunk[Symbol.Field] = symbolsOfKind(SymbolKind.Field)

        /** All TypeAlias symbols in the classpath. O(n) scan over `symbols`. */
        def allTypeAliases: Chunk[Symbol.TypeAlias] = symbolsOfKind(SymbolKind.TypeAlias)

        /** All OpaqueType symbols in the classpath. O(n) scan over `symbols`. */
        def allOpaqueTypes: Chunk[Symbol.OpaqueType] = symbolsOfKind(SymbolKind.OpaqueType)

        /** All AbstractType symbols in the classpath. O(n) scan over `symbols`. */
        def allAbstractTypes: Chunk[Symbol.AbstractType] = symbolsOfKind(SymbolKind.AbstractType)

        /** All TypeParam symbols in the classpath. O(n) scan over `symbols`. */
        def allTypeParams: Chunk[Symbol.TypeParam] = symbolsOfKind(SymbolKind.TypeParam)

        /** All Parameter symbols in the classpath. O(n) scan over `symbols`. */
        def allParameters: Chunk[Symbol.Parameter] = symbolsOfKind(SymbolKind.Parameter)

        /** All Package symbols in the classpath. O(n) scan over `symbols`. */
        def allPackages: Chunk[Symbol.Package] = symbolsOfKind(SymbolKind.Package)

        /** All Class symbols in the classpath. O(n) scan over `symbols`. */
        def allClasses: Chunk[Symbol.Class] = symbolsOfKind(SymbolKind.Class)

        /** All symbols in the classpath. Returns the underlying `symbols` Chunk directly. */
        def allSymbols: Chunk[Symbol] = symbols

        /** All symbols carrying the Scala or Java annotation whose fully-qualified name is `annotationFullName`.
          *
          * Checks Scala `annotations` (via `Annotation.annotationType`: must be `Type.Named(id)` whose fully-qualified
          * name matches) and Java `javaAnnotations` (via `Java.Annotation.annotationClass` fully-qualified name).
          * Symbols that carry neither field (`Symbol.TypeParam`, `Symbol.Package`) are excluded.
          *
          * @param annotationFullName dotted fully-qualified name of the annotation class to scan for
          * @return all symbols carrying at least one annotation with the given fully-qualified name; empty when none
          */
        def symbolsAnnotatedWith(annotationFullName: String): Chunk[Symbol] =
            import Name.asString
            symbols.filter { symbol =>
                val scalaMatch: Boolean = symbol match
                    case c: Symbol.ClassLike =>
                        c.annotations.exists(annotation => annotationFullNameMatches(annotation, annotationFullName))
                    case m: Symbol.Method => m.annotations.exists(annotation => annotationFullNameMatches(annotation, annotationFullName))
                    case v: Symbol.Val    => v.annotations.exists(annotation => annotationFullNameMatches(annotation, annotationFullName))
                    case w: Symbol.Var    => w.annotations.exists(annotation => annotationFullNameMatches(annotation, annotationFullName))
                    case ta: Symbol.TypeAlias =>
                        ta.annotations.exists(annotation => annotationFullNameMatches(annotation, annotationFullName))
                    case ot: Symbol.OpaqueType =>
                        ot.annotations.exists(annotation => annotationFullNameMatches(annotation, annotationFullName))
                    case at: Symbol.AbstractType =>
                        at.annotations.exists(annotation => annotationFullNameMatches(annotation, annotationFullName))
                    case p: Symbol.Parameter =>
                        p.annotations.exists(annotation => annotationFullNameMatches(annotation, annotationFullName))
                    case _: Symbol.Field     => false
                    case _: Symbol.TypeParam => false
                    case _: Symbol.Package   => false
                val javaMatch: Boolean = symbol match
                    case c: Symbol.ClassLike =>
                        c.javaAnnotations.exists(ja => computeFullName(ja.annotationClass).asString == annotationFullName)
                    case f: Symbol.Field =>
                        f.javaAnnotations.exists(ja => computeFullName(ja.annotationClass).asString == annotationFullName)
                    case _: Symbol.Method       => false
                    case _: Symbol.Val          => false
                    case _: Symbol.Var          => false
                    case _: Symbol.TypeAlias    => false
                    case _: Symbol.OpaqueType   => false
                    case _: Symbol.AbstractType => false
                    case _: Symbol.TypeParam    => false
                    case _: Symbol.Parameter    => false
                    case _: Symbol.Package      => false
                scalaMatch || javaMatch
            }
        end symbolsAnnotatedWith

        private def annotationFullNameMatches(annotation: Annotation, fullName: String): Boolean =
            typeFullNameString(annotation.annotationType) == fullName
        end annotationFullNameMatches

        /** Reconstruct a dotted fully-qualified name string from a `Type.Named` or `Type.TermRef` tycon, or an empty
          * string when the type shape is not resolvable to a name.
          *
          * Used by annotation fully-qualified name matching to support both `Type.Named(id)` and `Type.TermRef(qual, name)`
          * tycon forms. Annotation tycons decoded from the TYPEREF wire tag arrive as `Type.TermRef`.
          */
        /** Reconstruct a dotted fully-qualified name string from a `Type.Named` or `Type.TermRef` tycon, or an empty
          * string when the type shape is not resolvable to a name.
          *
          * Handles Named, TermRef, TypeRef, and Applied forms. Returns an empty string for all other type shapes.
          * Used by annotation fully-qualified name matching to support both `Type.Named(id)` and `Type.TermRef(qual, name)`
          * tycon forms.
          */
        private[kyo] def typeFullNameString(t: Type): String =
            import Name.asString
            t match
                case Type.Named(id) =>
                    symbol(id) match
                        case Maybe.Absent =>
                            // Out-of-range or negative id: check unresolvedFullNameByNegId for annotation types
                            // that reference external symbols (e.g. scala.deprecated on JS/Native).
                            indices.unresolvedFullNameByNegId.getOrElse(id, "")
                        case Maybe.Present(symbol) => computeFullName(symbol).asString
                    end match
                case Type.TermRef(qual, name) =>
                    val q = typeFullNameString(qual)
                    if q.nonEmpty then q + "." + name.asString else name.asString
                case Type.TypeRef(qual, name) =>
                    // TYPEREF emits TypeRef; annotation fully-qualified name matching must handle both forms.
                    val q = typeFullNameString(qual)
                    if q.nonEmpty then q + "." + name.asString else name.asString
                case Type.Applied(base, _) =>
                    // @Child[T] enrichment wraps the TermRef tycon in Applied(tycon, Chunk(T)).
                    // For fully-qualified name matching, use the unapplied base type.
                    typeFullNameString(base)
                case _: Type.TypeLambda      => ""
                case _: Type.Function        => ""
                case _: Type.ContextFunction => ""
                case _: Type.Tuple           => ""
                case _: Type.ByName          => ""
                case _: Type.Repeated        => ""
                case _: Type.Array           => ""
                case _: Type.Refinement      => ""
                case _: Type.Rec             => ""
                case _: Type.RecThis         => ""
                case _: Type.AndType         => ""
                case _: Type.OrType          => ""
                case _: Type.Annotated       => ""
                case _: Type.ConstantType    => ""
                case _: Type.ThisType        => ""
                case _: Type.SuperType       => ""
                case _: Type.ParamRef        => ""
                case _: Type.Wildcard        => ""
                case _: Type.Skolem          => ""
                case _: Type.MatchType       => ""
                case _: Type.FlexibleType    => ""
                case _: Type.MatchCase       => ""
                case _: Type.Bind            => ""
                case _: Type.Bounds          => ""
                case Type.Nothing            => ""
                case Type.Any                => ""
            end match
        end typeFullNameString

        /** Look up the companion symbol (companion object for a class/trait; companion class for an object).
          *
          * Pure O(1) lookup in the immutable `companionIndex`. Returns `Maybe.Absent` when no companion is
          * registered for this symbol, when the symbol is not a class or object, or when the companion is
          * not present in the loaded classpath.
          *
          * @param symbol the class or object symbol whose companion is requested
          * @return the companion symbol wrapped in `Maybe.Present`, or `Maybe.Absent`
          */
        def companion(symbol: Symbol): Maybe[Symbol] =
            indices.companionIndex.get(symbol.id) match
                case Maybe.Present(cid) => this.symbol(cid)
                case Maybe.Absent       => Maybe.Absent

        /** Compute the fully-qualified dotted name of `symbol` by walking the owner chain.
          *
          * Walks upward collecting non-empty segment names; stops when the symbol owns itself (the root sentinel),
          * when `ownerId` is -1, or when the same symbol appears twice (cycle guard). Depth limit of 64
          * prevents unbounded loops on malformed data.
          *
          * The returned `Name` can be converted to a `String` with `import Name.asString`.
          *
          * @param symbol the symbol whose fully-qualified name is computed
          * @return the dotted fully-qualified name
          */
        def fullName(symbol: Symbol): Name =
            computeFullName(symbol)

        /** Pure kernel for `fullName`. Walks the owner chain and concatenates segment strings.
          * Callers may rely on the result being identical to `fullName` for any fully-loaded symbol.
          */
        private[kyo] def computeFullName(rootSymbol: Symbol): Name =
            import Name.asString
            val visited = new java.util.HashSet[Int]()
            @scala.annotation.tailrec
            def go(cur: Symbol, depth: Int, acc: List[String]): List[String] =
                if depth >= 64 || !visited.add(cur.id.value) then acc
                else
                    val n          = cur.name.asString
                    val nextAcc    = if n.nonEmpty then n :: acc else acc
                    val ownerIdCur = cur.ownerId
                    if ownerIdCur == cur.id || ownerIdCur.value == -1 then nextAcc
                    else
                        this.symbol(ownerIdCur) match
                            case Maybe.Present(ownerSym) if ownerSym.id != cur.id && ownerSym.name.asString.nonEmpty =>
                                go(ownerSym, depth + 1, nextAcc)
                            case _ => nextAcc
                    end if
            val parts = go(rootSymbol, 0, Nil)
            (parts.mkString("."): Name)
        end computeFullName

        /** Return the immediate owner of `symbol`, one level up in the enclosing hierarchy.
          *
          * Delegates to `symbol(symbol.ownerId)`. Returns `Maybe.Absent` for root symbols whose `ownerId` is
          * `-1` and for symbols whose owner is not registered in this classpath.
          *
          * @param symbol the symbol whose owner is requested
          * @return the owner symbol wrapped in `Maybe.Present`, or `Maybe.Absent` for root symbols
          */
        def owner(symbol: Symbol): Maybe[Symbol] =
            this.symbol(symbol.ownerId)

        /** Compute the JVM binary name of `symbol`.
          *
          * The binary name uses `$` as the separator for nested and companion symbols rather than `.`,
          * following the JVM class-file convention. For example, `example.Outer.Inner` maps to
          * `"example/Outer$Inner"` and a companion object `example.MyObj` maps to `"example/MyObj$"`.
          *
          * Computed by `BinaryName.compute` using the owner chain and module flags on each symbol.
          *
          * @param symbol the symbol whose binary name is computed
          * @return the JVM binary class name (slash-separated, `$` for nesting)
          */
        def binaryName(symbol: Symbol): String =
            kyo.internal.tasty.symbol.BinaryName.compute(symbol, this)

        /** Render `symbol` as a human-readable string using the given format.
          *
          * Three formats are supported via `ShowFormat`:
          * - `ShowFormat.FullyQualified` (default): the dotted fully-qualified name string, e.g. `"scala.collection.immutable.List"`.
          *   Computed by walking the owner chain via `fullName`.
          * - `ShowFormat.Code`: the Scala source code signature, e.g. `"def foo[A](x: A): A"`.
          *   Computed by `SymbolSignature.computePure`, which resolves type parameter names and parent types.
          * - `ShowFormat.Simple`: the unqualified simple name, e.g. `"List"`, computed without
          *   classpath access.
          *
          * @param symbol the symbol to render
          * @param format rendering format; defaults to `ShowFormat.FullyQualified`
          * @return the rendered string
          */
        def show(symbol: Symbol, format: ShowFormat = ShowFormat.FullyQualified): String =
            import Name.asString
            format match
                case ShowFormat.FullyQualified => fullName(symbol).asString
                case ShowFormat.Simple         => symbol.simpleName
                case ShowFormat.Code           => kyo.internal.tasty.symbol.SymbolSignature.computePure(symbol, this)
            end match
        end show

        /** Compute the full owner chain of `symbol`, from `symbol` itself up to the root.
          *
          * The first element is `symbol`; subsequent elements are its owner, the owner's owner, and so on.
          * The walk stops at a root symbol or when a cycle is detected.
          *
          * Useful for determining nesting depth, computing enclosing packages, or checking membership in an
          * owner chain.
          *
          * @param symbol the symbol whose owner chain is computed
          * @return ordered chain starting with `symbol`; single-element when `symbol` has no owner
          */
        def ownersChain(symbol: Symbol): Chunk[Symbol] =
            val out     = Chunk.newBuilder[Symbol]
            val visited = new java.util.HashSet[Int]()
            @scala.annotation.tailrec
            def go(cur: Symbol, depth: Int): Unit =
                if depth >= 64 || !visited.add(cur.id.value) then ()
                else
                    out += cur
                    this.symbol(cur.ownerId) match
                        case Maybe.Present(ownerSym) if ownerSym.id != cur.id => go(ownerSym, depth + 1)
                        case _                                                => ()
            go(symbol, 0)
            out.result()
        end ownersChain

        /** Compute the human-readable signature of a method symbol.
          *
          * Delegates to `SymbolSignature.computePure`, producing a string in Scala source syntax,
          * e.g. `"def map[B](f: A => B): List[B]"`. Resolves type parameter and return-type
          * references to their names via this classpath.
          *
          * This is equivalent to `show(method, ShowFormat.Code)` but typed to accept only
          * `Symbol.Method`, providing a cleaner API at call sites where the method kind is
          * already known.
          *
          * @param method the method symbol to render
          * @return the Scala-syntax signature string
          */
        def signature(method: Symbol.Method): String =
            kyo.internal.tasty.symbol.SymbolSignature.computePure(method, this)

        /** Resolve a method's parameter-list groups into `Symbol.Parameter` entries.
          *
          * The outer `Chunk` is per parameter list in source order; the inner `Chunk` is the parameters
          * of that list in source order. For a method with no parameter lists the result is `Chunk.empty`.
          * For `def f()(a: A): Unit` the result has two elements: `Chunk(Chunk.empty, Chunk(a))`, preserving
          * the explicit empty clause.
          *
          * Broken `SymbolId` references inside `paramListIds` (symbols not present in this classpath) are
          * dropped from the result. Callers that need a one-to-one positional mapping should walk the raw
          * `paramListIds` field via `symbol(id)` directly.
          *
          * @param method the method whose parameter lists are requested
          * @return per-list-group resolved `Symbol.Parameter` entries; outer chunk preserves list boundaries
          */
        def paramLists(method: Symbol.Method): Chunk[Chunk[Symbol.Parameter]] =
            method.paramListIds.map { idGroup =>
                idGroup.flatMap { id =>
                    this.symbol(id) match
                        case Maybe.Present(p: Symbol.Parameter) => Chunk(p)
                        case Maybe.Present(_)                   => Chunk.empty
                        case Maybe.Absent                       => Chunk.empty
                }
            }

        /** Return the direct parent symbols of `classLike`.
          *
          * Reads `classLike.parentTypes`, filters for `Type.Named` entries, and resolves each
          * `SymbolId` to the corresponding symbol via `this.symbol(id)`. Parent types encoded as
          * other `Type` forms (e.g. `Type.Applied` for generic parents such as `List[A]`) are
          * excluded; use `classLike.parentTypes` directly when raw type information is needed.
          *
          * Only direct (first-generation) parents are returned. Use `ownersChain` or recursive
          * calls to `parents` for deeper ancestry traversal.
          *
          * @param classLike the class-like symbol whose direct parents are requested
          * @return resolved parent symbols; may be empty for `AnyRef`-rooted classes
          */
        def parents(classLike: Symbol.ClassLike): Chunk[Symbol] =
            classLike.parentTypes.flatMap {
                case Type.Named(pid)         => symbol(pid).toChunk
                case _: Type.TermRef         => Chunk.empty
                case _: Type.Applied         => Chunk.empty
                case _: Type.TypeLambda      => Chunk.empty
                case _: Type.Function        => Chunk.empty
                case _: Type.ContextFunction => Chunk.empty
                case _: Type.Tuple           => Chunk.empty
                case _: Type.ByName          => Chunk.empty
                case _: Type.Repeated        => Chunk.empty
                case _: Type.Array           => Chunk.empty
                case _: Type.Refinement      => Chunk.empty
                case _: Type.Rec             => Chunk.empty
                case _: Type.RecThis         => Chunk.empty
                case _: Type.AndType         => Chunk.empty
                case _: Type.OrType          => Chunk.empty
                case _: Type.Annotated       => Chunk.empty
                case _: Type.ConstantType    => Chunk.empty
                case _: Type.ThisType        => Chunk.empty
                case _: Type.SuperType       => Chunk.empty
                case _: Type.ParamRef        => Chunk.empty
                case _: Type.Wildcard        => Chunk.empty
                case _: Type.Skolem          => Chunk.empty
                case _: Type.MatchType       => Chunk.empty
                case _: Type.FlexibleType    => Chunk.empty
                case _: Type.MatchCase       => Chunk.empty
                case _: Type.Bind            => Chunk.empty
                case _: Type.TypeRef         => Chunk.empty
                case _: Type.Bounds          => Chunk.empty
                case Type.Nothing            => Chunk.empty
                case Type.Any                => Chunk.empty
            }

        /** Return the permitted direct subclasses of a sealed class or trait.
          *
          * For `Symbol.Class` and `Symbol.Trait`, returns `Maybe.Present` with the resolved symbols
          * from `permittedSubclassIds`. Returns `Maybe.Absent` for `Symbol.Object` and
          * `Symbol.EnumCase`, which carry no permitted-subclass information.
          *
          * The result contains only the directly permitted subclasses as encoded in the
          * TASTy/classfile metadata. Indirect subtypes are not included; call this method
          * recursively on each element when a full sealed hierarchy is needed.
          *
          * @param classLike the class-like symbol to inspect
          * @return Maybe.Present with permitted subclass symbols, or Maybe.Absent for Object/EnumCase
          */
        def permittedSubclasses(classLike: Symbol.ClassLike): Maybe[Chunk[Symbol]] =
            classLike match
                case c: Symbol.Class    => c.permittedSubclassIds.map(_.flatMap(id => symbol(id).toChunk))
                case t: Symbol.Trait    => t.permittedSubclassIds.map(_.flatMap(id => symbol(id).toChunk))
                case _: Symbol.Object   => Maybe.Absent
                case _: Symbol.EnumCase => Maybe.Absent

        /** All direct `ClassLike` subclasses of `classLike` (one hop, from the subclass index).
          *
          * Pure O(k) lookup where k is the number of direct subclasses. Returns an empty Chunk
          * when `classLike` has no registered subclasses. Non-ClassLike entries in the index are
          * excluded (should not occur in well-formed classpath data).
          */
        def directSubclassesOf(classLike: Symbol.ClassLike): Chunk[Symbol.ClassLike] =
            indices.subclassIndex.getOrElse(classLike.id, Chunk.empty).flatMap { id =>
                symbol(id) match
                    case Maybe.Present(c: Symbol.ClassLike) => Chunk(c)
                    case Maybe.Present(_)                   => Chunk.empty
                    case Maybe.Absent                       => Chunk.empty
            }

        /** All transitive `ClassLike` subclasses of `classLike` (BFS closure over the subclass index).
          *
          * Returns an empty Chunk when `classLike` has no registered subclasses. The BFS visited
          * set prevents infinite loops on malformed (cyclic) classpath data.
          */
        def subclassesOf(classLike: Symbol.ClassLike): Chunk[Symbol.ClassLike] =
            transitiveClassLikeSubclasses(classLike)

        /** All concrete `Symbol.Class` instances that are transitive subclasses of `classLike` and not abstract.
          *
          * Equivalent to `subclassesOf(classLike).collect { case c: Symbol.Class if !c.isAbstract => c }`.
          */
        def implementationsOf(classLike: Symbol.ClassLike): Chunk[Symbol.Class] =
            subclassesOf(classLike).flatMap {
                case c: Symbol.Class if !c.isAbstract => Chunk(c)
                case _: Symbol.Class                  => Chunk.empty
                case _: Symbol.EnumCase               => Chunk.empty
                case _: Symbol.Trait                  => Chunk.empty
                case _: Symbol.Object                 => Chunk.empty
            }

        private def transitiveClassLikeSubclasses(classLike: Symbol.ClassLike): Chunk[Symbol.ClassLike] =
            val visited = scala.collection.mutable.HashSet.empty[SymbolId]
            val out     = Chunk.newBuilder[Symbol.ClassLike]
            @scala.annotation.tailrec
            def bfs(frontier: Chunk[SymbolId]): Unit =
                if frontier.isEmpty then ()
                else
                    val next = frontier.flatMap { curId =>
                        indices.subclassIndex.getOrElse(curId, Chunk.empty).flatMap { childId =>
                            if visited.add(childId) then
                                symbol(childId) match
                                    case Maybe.Present(c: Symbol.ClassLike) =>
                                        out += c
                                        Chunk(childId)
                                    case Maybe.Present(_) => Chunk.empty[SymbolId]
                                    case Maybe.Absent     => Chunk.empty[SymbolId]
                            else Chunk.empty[SymbolId]
                        }
                    }
                    bfs(next)
            bfs(Chunk(classLike.id))
            out.result()
        end transitiveClassLikeSubclasses

        /** Return the symbols declared directly on `symbol`.
          *
          * For `Symbol.ClassLike`, reads `declarationIds` (methods, vals, vars, nested types,
          * and nested classes declared on the class body). For `Symbol.Package`, reads `memberIds`
          * (top-level classes and objects in the package).
          *
          * Does not include inherited members. Use `members(symbol, MemberScope.All)` to include
          * symbols inherited from parent types.
          *
          * @param symbol the class-like or package symbol whose direct declarations are requested
          * @return the declared symbols in registration order
          */
        def declarations(symbol: Symbol.ClassLike | Symbol.Package): Chunk[Symbol] =
            symbol match
                case c: Symbol.ClassLike => c.declarationIds.flatMap(id => this.symbol(id).toChunk)
                case p: Symbol.Package   => p.memberIds.flatMap(id => this.symbol(id).toChunk)

        /** Return the members of `symbol` filtered by the given `MemberScope`.
          *
          * Three scopes are available:
          * - `MemberScope.Declared` (default): symbols declared directly on `symbol` (same as
          *   `declarations`). O(n) in the number of declared members.
          * - `MemberScope.Inherited`: symbols inherited from parent types and not redeclared on
          *   `symbol`. The parent walk deduplicates by `simpleName`; the first (most-specific)
          *   occurrence wins.
          * - `MemberScope.All`: union of Declared and Inherited, deduplicated by `simpleName`.
          *   Most-specific (nearest in the hierarchy) symbol wins on name clash.
          *
          * For `Symbol.Package`, `Declared` and `All` both read `memberIds`; `Inherited` returns
          * empty (packages do not inherit).
          *
          * @param symbol the class-like or package symbol whose members are requested
          * @param scope  which members to include; defaults to `MemberScope.Declared`
          * @return the member symbols; empty when `symbol` has no members in the given scope
          */
        def members(symbol: Symbol.ClassLike | Symbol.Package, scope: MemberScope = MemberScope.Declared): Chunk[Symbol] =
            scope match
                case MemberScope.Declared =>
                    symbol match
                        case c: Symbol.ClassLike => c.declarationIds.flatMap(id => this.symbol(id).toChunk)
                        case p: Symbol.Package   => p.memberIds.flatMap(id => this.symbol(id).toChunk)
                case MemberScope.Inherited =>
                    val directNames = scala.collection.mutable.HashSet.empty[String]
                    val declIds = symbol match
                        case c: Symbol.ClassLike => c.declarationIds
                        case p: Symbol.Package   => p.memberIds
                    declIds.foreach(id => this.symbol(id).foreach(s => discard(directNames.add(s.simpleName))))
                    allMembersOf(symbol).filter(s => !directNames.contains(s.simpleName))
                case MemberScope.All =>
                    allMembersOf(symbol)

        /** Find a member of `symbol` by simple name within the given scope.
          *
          * Calls `members(symbol, scope)` and returns the first symbol whose `simpleName` equals
          * `name`. Returns `Maybe.Absent` when no member with that name is found in the given
          * scope.
          *
          * When multiple overloads exist under the same simple name, the first one in the
          * `members` result is returned. Use `members(symbol, scope)` and filter manually when
          * overload discrimination is required.
          *
          * @param symbol the class-like or package symbol to search
          * @param name   the simple (unqualified) member name to look up
          * @param scope  which members to search; defaults to `MemberScope.Declared`
          * @return the first matching member symbol, or `Maybe.Absent`
          */
        def findMember(symbol: Symbol.ClassLike | Symbol.Package, name: String, scope: MemberScope = MemberScope.Declared): Maybe[Symbol] =
            Maybe.fromOption(members(symbol, scope).find(_.simpleName == name))

        /** Find a directly-declared member of `symbol` by simple name.
          *
          * Shorthand for `findMember(symbol, name, MemberScope.Declared)`. Searches only symbols
          * declared on `symbol` itself, not symbols inherited from parent types.
          *
          * Returns `Maybe.Absent` when no declared member with that simple name exists. When
          * inherited members should be considered, use `findMember` with `MemberScope.All`.
          *
          * @param symbol the class-like or package symbol to search
          * @param name   the simple (unqualified) member name to look up
          * @return the first matching declared member symbol, or `Maybe.Absent`
          */
        def findDeclaredMember(symbol: Symbol.ClassLike | Symbol.Package, name: String): Maybe[Symbol] =
            findMember(symbol, name, MemberScope.Declared)

        /** Return the type parameters declared on `symbol`.
          *
          * Accepted symbol kinds are `Symbol.ClassLike`, `Symbol.Method`, `Symbol.TypeAlias`, and
          * `Symbol.OpaqueType`. Each element is a `Symbol.TypeParam` resolved from this classpath.
          * `typeParamIds` entries that do not resolve (e.g., from an incomplete classpath) are silently
          * dropped. Returns an empty `Chunk` when no type parameters are declared.
          *
          * @param symbol the class-like, method, type alias, or opaque type whose type parameters are requested
          * @return the type parameter symbols in declaration order; empty when none declared
          */
        def typeParams(symbol: Symbol.ClassLike | Symbol.Method | Symbol.TypeAlias | Symbol.OpaqueType): Chunk[Symbol.TypeParam] =
            val ids = symbol match
                case c: Symbol.ClassLike   => c.typeParamIds
                case m: Symbol.Method      => m.typeParamIds
                case ta: Symbol.TypeAlias  => ta.typeParamIds
                case ot: Symbol.OpaqueType => ot.typeParamIds
            ids.flatMap(id => this.symbol(id).toChunk.collect { case tp: Symbol.TypeParam => tp })
        end typeParams

        /** Return `true` when `symbol` carries the Scala or Java annotation with the given fully-qualified name.
          *
          * Checks Scala `annotations` (via `Annotation.annotationType` resolved to its fully-qualified name string)
          * and Java `javaAnnotations` (via `Java.Annotation.annotationClass` fully-qualified name).
          *
          * For `Symbol.ClassLike`, both Scala and Java annotation lists are checked.
          * For `Symbol.Field`, only Java annotations are checked.
          * Symbol kinds with no annotation storage (`Symbol.TypeParam`, `Symbol.Package`) always return `false`.
          *
          * @param symbol             the symbol to inspect
          * @param annotationFullName dotted fully-qualified name of the annotation class
          * @return `true` if at least one matching annotation is present, `false` otherwise
          */
        def hasAnnotation(symbol: Symbol, annotationFullName: String): Boolean =
            def matchScala(a: Annotation): Boolean =
                typeFullNameString(a.annotationType) == annotationFullName
            def matchJava(a: Java.Annotation): Boolean =
                import Name.asString
                computeFullName(a.annotationClass).asString == annotationFullName
            symbol match
                case c: Symbol.ClassLike    => c.annotations.exists(matchScala) || c.javaAnnotations.exists(matchJava)
                case m: Symbol.Method       => m.annotations.exists(matchScala)
                case v: Symbol.Val          => v.annotations.exists(matchScala)
                case w: Symbol.Var          => w.annotations.exists(matchScala)
                case f: Symbol.Field        => f.javaAnnotations.exists(matchJava)
                case t: Symbol.TypeAlias    => t.annotations.exists(matchScala)
                case t: Symbol.OpaqueType   => t.annotations.exists(matchScala)
                case t: Symbol.AbstractType => t.annotations.exists(matchScala)
                case p: Symbol.Parameter    => p.annotations.exists(matchScala)
                case _: Symbol.TypeParam    => false
                case _: Symbol.Package      => false
            end match
        end hasAnnotation

        /** Find the first Scala or Java annotation matching `annotationFullName` on `symbol`.
          *
          * Returns `Maybe.Present(annotation)` where the annotation is either a decoded Scala `Annotation`
          * (with typed arguments) or a raw `Java.Annotation`.
          *
          * For `Symbol.ClassLike`, Scala annotations are checked first; if none match, Java annotations are
          * checked. For `Symbol.Field`, only Java annotations are checked.
          *
          * Returns `Maybe.Absent` when no matching annotation exists on `symbol`, or when `symbol` is a kind
          * that does not carry annotations (`Symbol.TypeParam`, `Symbol.Package`).
          *
          * @param symbol             the symbol to inspect
          * @param annotationFullName dotted fully-qualified name of the annotation class
          * @return the first matching annotation, or `Maybe.Absent`
          */
        def findAnnotation(symbol: Symbol, annotationFullName: String): Maybe[AnnotationLike] =
            def matchScala(a: Annotation): Boolean =
                typeFullNameString(a.annotationType) == annotationFullName
            def matchJava(a: Java.Annotation): Boolean =
                import Name.asString
                computeFullName(a.annotationClass).asString == annotationFullName
            symbol match
                case c: Symbol.ClassLike =>
                    Maybe.fromOption(c.annotations.find(matchScala).orElse(c.javaAnnotations.find(matchJava)))
                case m: Symbol.Method       => Maybe.fromOption(m.annotations.find(matchScala))
                case v: Symbol.Val          => Maybe.fromOption(v.annotations.find(matchScala))
                case w: Symbol.Var          => Maybe.fromOption(w.annotations.find(matchScala))
                case f: Symbol.Field        => Maybe.fromOption(f.javaAnnotations.find(matchJava))
                case t: Symbol.TypeAlias    => Maybe.fromOption(t.annotations.find(matchScala))
                case t: Symbol.OpaqueType   => Maybe.fromOption(t.annotations.find(matchScala))
                case t: Symbol.AbstractType => Maybe.fromOption(t.annotations.find(matchScala))
                case p: Symbol.Parameter    => Maybe.fromOption(p.annotations.find(matchScala))
                case _: Symbol.TypeParam    => Maybe.Absent
                case _: Symbol.Package      => Maybe.Absent
            end match
        end findAnnotation

        private def allMembersOf(symbol: Symbol.ClassLike | Symbol.Package): Chunk[Symbol] =
            symbol match
                case c: Symbol.ClassLike =>
                    val seen = scala.collection.mutable.HashSet.empty[String]
                    val out  = Chunk.newBuilder[Symbol]
                    def visit(cl: Symbol.ClassLike): Unit =
                        cl.declarationIds.foreach { id =>
                            this.symbol(id).foreach { d =>
                                val nm = d.simpleName
                                if seen.add(nm) then out += d
                            }
                        }
                        cl.parentTypes.foreach {
                            case Type.Named(pid) =>
                                this.symbol(pid).foreach {
                                    case pcl: Symbol.ClassLike  => visit(pcl)
                                    case _: Symbol.Method       => ()
                                    case _: Symbol.Val          => ()
                                    case _: Symbol.Var          => ()
                                    case _: Symbol.Field        => ()
                                    case _: Symbol.TypeAlias    => ()
                                    case _: Symbol.OpaqueType   => ()
                                    case _: Symbol.AbstractType => ()
                                    case _: Symbol.TypeParam    => ()
                                    case _: Symbol.Parameter    => ()
                                    case _: Symbol.Package      => ()
                                }
                            case _: Type.TermRef         => ()
                            case _: Type.Applied         => ()
                            case _: Type.TypeLambda      => ()
                            case _: Type.Function        => ()
                            case _: Type.ContextFunction => ()
                            case _: Type.Tuple           => ()
                            case _: Type.ByName          => ()
                            case _: Type.Repeated        => ()
                            case _: Type.Array           => ()
                            case _: Type.Refinement      => ()
                            case _: Type.Rec             => ()
                            case _: Type.RecThis         => ()
                            case _: Type.AndType         => ()
                            case _: Type.OrType          => ()
                            case _: Type.Annotated       => ()
                            case _: Type.ConstantType    => ()
                            case _: Type.ThisType        => ()
                            case _: Type.SuperType       => ()
                            case _: Type.ParamRef        => ()
                            case _: Type.Wildcard        => ()
                            case _: Type.Skolem          => ()
                            case _: Type.MatchType       => ()
                            case _: Type.FlexibleType    => ()
                            case _: Type.MatchCase       => ()
                            case _: Type.Bind            => ()
                            case _: Type.TypeRef         => ()
                            case _: Type.Bounds          => ()
                            case Type.Nothing            => ()
                            case Type.Any                => ()
                        }
                    end visit
                    visit(c)
                    out.result()
                // Packages do not inherit; their memberIds ARE their All-scope members.
                case p: Symbol.Package =>
                    p.memberIds.flatMap(id => this.symbol(id).toChunk)

    end Classpath

    object Classpath:

        /** Sealed hierarchy for structured build-time observations accumulated in `Classpath.Indices.diagnostics`.
          *
          * Unlike `TastyError` (which represents failures during decoding or classpath operations), `Diagnostic` represents observations
          * about the classpath shape that do not prevent a usable classpath from being returned. Currently the only concrete type is
          * `FullNameCollision`.
          */
        sealed trait Diagnostic derives Schema, CanEqual

        /** Recorded when two or more source roots each provide a symbol under the same fully-qualified name.
          *
          * `fullName` is the colliding fully-qualified name. `ids` contains the `SymbolId` of every symbol that was registered under this fully-qualified name
          * across the input roots; the winning symbol (the one returned by `findSymbol(fullName)`) is the last entry in insertion order.
          *
          * This diagnostic is only populated under `ErrorMode.SoftFail`. Under `ErrorMode.FailFast`, a collision immediately raises
          * `TastyError.InconsistentClasspath` and initialization aborts.
          */
        final case class FullNameCollision(fullName: String, ids: Chunk[SymbolId]) extends Diagnostic derives Schema, CanEqual

        /** All lookup indices for a Classpath. Immutable, populated once during `init` and never mutated.
          *
          * Fields:
          *   - `byFullName`: fully-qualified dotted name to SymbolId (replaces old `fullNameIndex`).
          *   - `bySimpleName`: simple name to SymbolId chunk (for findClassesByName; replaces lazy `nameIndex`).
          *   - `packageIndex`: package fully-qualified name to SymbolId.
          *   - `subclassIndex`: parent SymbolId to direct subclass SymbolIds (for directSubclassesOf).
          *   - `companionIndex`: SymbolId to companion SymbolId.
          *   - `modulesIndex`: module name to ModuleDescriptor (O(1) findModule).
          *   - `topLevelClassIds`: top-level class-like SymbolIds.
          *   - `packageIds`: package SymbolIds.
          *   - `unresolvedFullNameByNegId`: negative SymbolId to fully-qualified name string (annotation types not on classpath).
          *   - `diagnostics`: fully-qualified name collision records from initialization.
          *
          * `derives CanEqual`: Schema derivation is provided by the hand-written `given schemaIndices`
          * (placed after `symbolIdMapSchema`) so `Dict[SymbolId, V]` fields resolve correctly.
          */
        final case class Indices(
            byFullName: Dict[String, SymbolId],
            bySimpleName: Dict[String, Chunk[SymbolId]],
            packageIndex: Dict[String, SymbolId],
            subclassIndex: Dict[SymbolId, Chunk[SymbolId]],
            companionIndex: Dict[SymbolId, SymbolId],
            modulesIndex: Dict[String, Java.Module.Descriptor],
            topLevelClassIds: Chunk[SymbolId],
            packageIds: Chunk[SymbolId],
            unresolvedFullNameByNegId: Dict[SymbolId, String],
            diagnostics: Chunk[Classpath.Diagnostic]
        ) derives CanEqual:
            // Dict is an opaque type without structural == ; override equals to use Dict.is
            // for each Dict field so that structural comparison works as expected for case class equality.
            override def equals(other: Any): Boolean = other match
                case i: Indices =>
                    byFullName.is(i.byFullName)(using CanEqual.canEqualAny, CanEqual.canEqualAny) &&
                    bySimpleName.is(i.bySimpleName)(using CanEqual.canEqualAny, CanEqual.canEqualAny) &&
                    packageIndex.is(i.packageIndex)(using CanEqual.canEqualAny, CanEqual.canEqualAny) &&
                    subclassIndex.is(i.subclassIndex)(using CanEqual.canEqualAny, CanEqual.canEqualAny) &&
                    companionIndex.is(i.companionIndex)(using CanEqual.canEqualAny, CanEqual.canEqualAny) &&
                    modulesIndex.is(i.modulesIndex)(using CanEqual.canEqualAny, CanEqual.canEqualAny) &&
                    topLevelClassIds == i.topLevelClassIds &&
                    packageIds == i.packageIds &&
                    unresolvedFullNameByNegId.is(i.unresolvedFullNameByNegId)(using CanEqual.canEqualAny, CanEqual.canEqualAny) &&
                    diagnostics == i.diagnostics
                case _ => false // carve-out: Any is open; exhaustive enumeration is not possible here
            end equals

            // Override hashCode so that structurally-equal Indices instances produce the same hash.
            // The auto-generated case-class hashCode mixes each Dict's reference-based hashCode, which
            // violates the equals/hashCode contract established by the equals override above.
            // XOR-based fold over (key, value) pairs is used for each Dict field because XOR is commutative
            // and iteration order over Dict is not guaranteed to be stable.
            override def hashCode(): Int =
                def dictHash[K, V](d: Dict[K, V]): Int =
                    d.foldLeft(0)((h, k, v) => h ^ (k.hashCode * 31 + v.hashCode))
                var h = 1
                h = 31 * h + dictHash(byFullName)
                h = 31 * h + dictHash(bySimpleName)
                h = 31 * h + dictHash(packageIndex)
                h = 31 * h + dictHash(subclassIndex)
                h = 31 * h + dictHash(companionIndex)
                h = 31 * h + dictHash(modulesIndex)
                h = 31 * h + topLevelClassIds.hashCode
                h = 31 * h + packageIds.hashCode
                h = 31 * h + dictHash(unresolvedFullNameByNegId)
                h = 31 * h + diagnostics.hashCode
                h
            end hashCode
        end Indices

        object Indices:
            val empty: Indices = Indices(
                byFullName = Dict.empty[String, SymbolId],
                bySimpleName = Dict.empty[String, Chunk[SymbolId]],
                packageIndex = Dict.empty[String, SymbolId],
                subclassIndex = Dict.empty[SymbolId, Chunk[SymbolId]],
                companionIndex = Dict.empty[SymbolId, SymbolId],
                modulesIndex = Dict.empty[String, Java.Module.Descriptor],
                topLevelClassIds = Chunk.empty,
                packageIds = Chunk.empty,
                unresolvedFullNameByNegId = Dict.empty[SymbolId, String],
                diagnostics = Chunk.empty
            )
        end Indices

        /** Schema for Dict[SymbolId, V]: encodes SymbolId keys as their String representation (Int value).
          *
          * kyo-schema provides Schema[Dict[String, V]] (stringDictSchema, JSON object) but not
          * Schema[Dict[K, V]] for non-String keys via the object-format path.
          * This given bridges the gap for all Dict[SymbolId, V] fields in Classpath.Indices.
          * Encoding: each SymbolId key is converted to its Int value rendered as a String; decoding
          * parses the String back to Int and wraps in SymbolId. Wire format is a JSON object,
          * preserving the existing snapshot encoding.
          */
        private[kyo] given symbolIdMapSchema[V](using vs: Schema[V]): Schema[Dict[SymbolId, V]] =
            summon[Schema[Dict[String, V]]].transform((d: Dict[String, V]) => d.map((k, v) => (SymbolId(k.toInt), v)))(
                (d: Dict[SymbolId, V]) => d.map((k, v) => (k.value.toString, v))
            )

        /** Schema for Classpath.Indices. Placed here (after symbolIdMapSchema is defined) so Dict[SymbolId, V] fields resolve. */
        given schemaIndices: Schema[Indices] = Schema.derived

        /** Init the classpath and pre-load JDK `module-info.class` entries from the JDK module image.
          *
          * Opt-in JDK auto-discovery. On JVM, reads `module-info.class` for every JDK module and merges them
          * into the returned classpath's `moduleIndex`, so `classpath.findModule("java.base")` and other JDK modules
          * resolve. On Scala.js and Scala Native this fails with `TastyError.UnsupportedPlatform`.
          */
        def initWithPlatformModules(roots: Seq[String])(using Frame): Classpath < (Async & Scope & Abort[TastyError]) =
            initWithPlatformModulesFiltered(roots, Set.empty)

        /** Variant of `initWithPlatformModules` that walks only the specified JPMS modules from the `jrt:/` filesystem.
          *
          * When `moduleFilter` is non-empty, only the named modules (e.g. `Set("java.base")`) are scanned for classfiles. This reduces
          * decode time from ~27,000 classfiles (all JDK modules) to ~7,000 (java.base only), making it suitable for test fixtures. The
          * production `initWithPlatformModules` always passes an empty filter, which walks all modules.
          *
          * The returned Classpath is immutable; this overload does not weaken that invariant.
          */
        private[kyo] def initWithPlatformModulesFiltered(
            roots: Seq[String],
            moduleFilter: Set[String]
        )(using Frame): Classpath < (Async & Scope & Abort[TastyError]) =
            // Prepend every `.class` path under `jrt:/modules/<m>/...` to the user's roots so JDK class
            // symbols decode alongside user TASTy. The shape of `roots` is preserved (a Seq[String] of
            // file-system paths); the new entries use the `jrt:/` URI scheme handled by ZipHandle.
            // PlatformModuleOps.listJdkClassFiles is JVM-only; JS/Native
            // return Chunk.empty so this method degrades to the module-descriptor-only path.
            // Sync.Unsafe.defer supplies AllowUnsafe to just the listJdkClassFiles call, so the rest of
            // the for-comprehension cannot pick up the proof implicitly.
            Sync.Unsafe.defer(kyo.internal.tasty.query.PlatformModuleOps.listJdkClassFiles(moduleFilter)).map { jdkClassFiles =>
                for
                    classpath  <- initImpl(jdkClassFiles.toSeq ++ roots, ErrorMode.SoftFail)
                    jdkModules <- PlatformModuleOps.readJdkModuleDescriptors
                yield
                    val newModulesIndex = classpath.indices.modulesIndex ++ Dict.from(jdkModules)
                    val newModulesBuf   = Chunk.newBuilder[Java.Module.Descriptor]
                    newModulesIndex.foreach((_, v) => newModulesBuf += v)
                    val newModules = newModulesBuf.result()
                    classpath.copy(
                        modules = newModules,
                        indices = classpath.indices.copy(modulesIndex = newModulesIndex)
                    )
                end for
            }
        end initWithPlatformModulesFiltered

        /** Create a test-only classpath from a pre-built symbols array.
          *
          * symbols(i).id.value must equal i for classpath.symbol(id) to resolve correctly. Only callable from within package kyo (private[kyo]).
          */
        private[kyo] def fromPicklesWithSymbols(symbols: Chunk[Symbol])(using Frame): Classpath < Sync =
            Classpath(
                symbols = symbols,
                indices = Indices.empty,
                errors = Chunk.empty,
                modules = Chunk.empty,
                rootSymbolId = if symbols.nonEmpty then SymbolId(0) else SymbolId(-1)
            )

        /** Internal: init implementation, delegates to ClasspathOrchestrator. */
        private def initImpl(
            roots: Seq[String],
            mode: ErrorMode
        )(using Frame): Classpath < (Async & Scope & Abort[TastyError]) =
            val concurrency = Runtime.getRuntime.availableProcessors().max(1)
            TastyStat.scope.traceSpan(
                "coldLoad",
                Attributes.empty.add("roots", roots.size.toString)
            ) {
                ClasspathOrchestrator.init(roots, mode, concurrency)
            }
        end initImpl

        private def initCachedImpl(
            roots: Seq[String],
            cacheDir: String
        )(using Frame): Classpath < (Async & Scope & Abort[TastyError]) =
            // Compute digest of root metadata
            Abort.run[TastyError](SnapshotDigest.compute(roots)).map {
                case Result.Failure(_) =>
                    // Digest computation failed (e.g., browser): fall through to normal init
                    initImpl(roots, ErrorMode.SoftFail)
                case Result.Panic(_) =>
                    initImpl(roots, ErrorMode.SoftFail)
                case Result.Success(digest) =>
                    val hexDigest    = SnapshotDigest.toHexString(digest)
                    val snapshotPath = s"$cacheDir/$hexDigest.krfl"
                    Path(snapshotPath).exists.map { exists =>
                        if exists then
                            // Try to load from snapshot using mmap on JVM/Native, heap on JS.
                            Abort.run[TastyError](SnapshotReader.readMapped(snapshotPath)).map {
                                case Result.Success(classpath) =>
                                    classpath
                                case Result.Failure(_) | Result.Panic(_) =>
                                    // Snapshot unreadable; fall through to normal init
                                    initImpl(roots, ErrorMode.SoftFail)
                            }
                        else
                            // No snapshot; init normally then write snapshot
                            initImpl(roots, ErrorMode.SoftFail).map { classpath =>
                                Abort.run[TastyError](SnapshotWriter.write(classpath, cacheDir, digest)).andThen(classpath)
                            }
                    }
            }
        end initCachedImpl

        /** Internal factory for constructing a Tasty.Classpath from a finalized 13-parameter
          * expanded form. Used by callers that supply pre-built indices and symbol arrays.
          */
        private[kyo] def make(
            symbols: Chunk[Symbol],
            rootSymbolId: SymbolId,
            topLevelClassIds: Chunk[SymbolId],
            packageIds: Chunk[SymbolId],
            fullNameIndex: Dict[String, SymbolId],
            packageIndex: Dict[String, SymbolId],
            subclassIndex: Dict[SymbolId, Chunk[SymbolId]],
            companionIndex: Dict[SymbolId, SymbolId],
            moduleIndex: Dict[String, Java.Module.Descriptor],
            errors: Chunk[TastyError],
            diagnostics: Chunk[Classpath.Diagnostic] = Chunk.empty,
            unresolvedFullNameByNegId: Dict[SymbolId, String] = Dict.empty[SymbolId, String]
        ): Classpath =
            import Name.asString
            val bySimpleName: Dict[String, Chunk[SymbolId]] =
                val b = scala.collection.mutable.HashMap.empty[String, scala.collection.mutable.ArrayBuffer[SymbolId]]
                symbols.foreach { symbol =>
                    val nm = symbol.name.asString
                    if nm.nonEmpty then
                        b.getOrElseUpdate(nm, new scala.collection.mutable.ArrayBuffer()) += symbol.id
                }
                Dict.from(b.map((k, v) => k -> Chunk.from(v)).toMap)
            end bySimpleName
            val moduleValues =
                val builder = Chunk.newBuilder[Java.Module.Descriptor]
                moduleIndex.foreach((_, v) => builder += v)
                builder.result()
            end moduleValues
            Classpath(
                symbols = symbols,
                indices = Classpath.Indices(
                    byFullName = fullNameIndex,
                    bySimpleName = bySimpleName,
                    packageIndex = packageIndex,
                    subclassIndex = subclassIndex,
                    companionIndex = companionIndex,
                    modulesIndex = moduleIndex,
                    topLevelClassIds = topLevelClassIds,
                    packageIds = packageIds,
                    unresolvedFullNameByNegId = unresolvedFullNameByNegId,
                    diagnostics = diagnostics
                ),
                errors = errors,
                modules = moduleValues,
                rootSymbolId = rootSymbolId
            )
        end make

        /** Internal helper: prepend pre-errors (e.g., FileNotFound for missing roots under SoftFail) to classpath.errors. */
        private[kyo] def copyWithPreErrors(classpath: Classpath, preErrors: Chunk[TastyError]): Classpath =
            classpath.copy(errors = preErrors ++ classpath.errors)

        /** empty: canonical empty classpath, useful for tests and as a default value. */
        val empty: Classpath = Classpath(
            symbols = Chunk.empty,
            indices = Indices.empty,
            errors = Chunk.empty,
            modules = Chunk.empty,
            rootSymbolId = SymbolId(-1)
        )

    end Classpath

    /** Schema[Classpath] placed after object Classpath closes so Classpath.Indices and its Schema are in scope.
      *
      * Placed after object Classpath closes so Classpath.Indices and its Schema are in scope, avoiding the forward-reference
      * issue that arises when derives Schema is placed inline on the class.
      */
    given schemaClasspath: Schema[Classpath] = Schema.derived

    /** CanEqual[Classpath, Classpath] derived after the companion closes; same placement rationale as schemaClasspath. */
    given canEqualClasspath: CanEqual[Classpath, Classpath] = CanEqual.canEqualAny

end Tasty
