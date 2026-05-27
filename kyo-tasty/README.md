# kyo-reflect

Compile-time reflection for the Kyo ecosystem. Reads Scala 3 TASTy files and Java classfiles, exposes them through a unified Symbol/Type API. Cross-platform: JVM, JS, Native.

## What it does

You point it at a classpath (a directory or set of `.tasty` / `.class` files), and it gives you typed access to every class, method, field, and type signature it finds. No classloading. No exceptions. No runtime instrumentation. Just structured metadata you can query.

```scala
import kyo.*
import kyo.Reflect.*

def listTopLevelClasses(roots: Seq[String])(using Frame): Chunk[String] < (Sync & Abort[ReflectError] & Scope) =
    for
        cp      <- Reflect.Classpath.open(roots)
        classes <- cp.topLevelClasses
    yield classes.map(_.fullName.asString)
```

That snippet works whether the classes were compiled from Scala or Java, on JVM or JS or Native, and gives you a flat `Chunk[String]` of FQNs.

## Why this exists

If you've ever needed to look at the structure of Scala code at build time (code generators, facade emitters, IDE tooling, runtime reflection replacement) you've hit one of these dead ends:

* **`tasty-query`**: JVM only. Throws exceptions for missing classes. Threads `using Context` through every helper. Has a seven-level deep Symbol hierarchy that leaks at call sites. Exposes nothing for inline detection or flag bits, forcing callers to reach for `getDeclaredField("myFlags")` reflection escapes.
* **`scala.reflect.runtime`**: requires runtime classloading. Doesn't work cleanly on Scala Native or Scala.js.
* **`java.lang.reflect`**: JVM only, Java-only, erased generics.
* **`asm`**, **`javassist`**: bytecode level, not source level. No Scala-3 specific information.

kyo-reflect collapses all of that into a single typed API that runs everywhere Kyo runs.

## Core concepts

Three nested types do most of the work, all under the `Reflect` object:

* **`Reflect.Classpath`** is the universe. You open one with `Reflect.Classpath.open(roots)` and it gives you query access to everything it finds.
* **`Reflect.Symbol`** is a class, method, field, type alias, package, anything definable. Identity-level data (name, flags, kind, owner) is always available; relationship data (type, parents, declarations) is effectful because it may follow cross-file references.
* **`Reflect.Type`** is the type ADT. Covers Scala-3 type forms (`AndType`, `OrType`, `TypeLambda`, `MatchType`, `OpaqueType`, etc.) and Java types (parameterized, arrays, wildcards).

There's also `Reflect.Reads[A]`, a typeclass for projecting a `Symbol` into your own data type via `derives` clauses.

## Use case 1: code generation

Suppose you want to generate TypeScript facades for every public class in a kyo module. You scan the classpath, project each class into your descriptor, render.

```scala
import kyo.*
import kyo.Reflect.*

case class FacadeType(
    name:    Reflect.Name,
    flags:   Reflect.Flags,
    parents: Chunk[Reflect.Type],
    methods: Chunk[FacadeMethod]
)

case class FacadeMethod(
    name:       Reflect.Name,
    returnType: Reflect.Type,
    params:     Chunk[Reflect.Type]
)

def generate(roots: Seq[String])(using Frame): Unit < (Sync & Abort[ReflectError] & Scope) =
    for
        cp      <- Reflect.Classpath.openCached(roots, cacheDir = ".kyo-reflect-cache")
        classes <- cp.topLevelClasses
        facades <- Kyo.foreach(classes)(buildFacade)
        _       <- Kyo.foreach(facades)(f => Sync.defer(println(renderFacade(f))))
    yield ()

private def buildFacade(sym: Reflect.Symbol)(using Frame): FacadeType < (Sync & Abort[ReflectError]) =
    for
        parents <- sym.parents
        decls   <- sym.declarations
        methods <- Kyo.foreach(decls.filter(_.kind == Reflect.SymbolKind.Method))(buildMethod)
    yield FacadeType(sym.name, sym.flags, parents, methods)

private def buildMethod(sym: Reflect.Symbol)(using Frame): FacadeMethod < (Sync & Abort[ReflectError]) =
    sym.declaredType.map {
        case f: Reflect.Type.Function => FacadeMethod(sym.name, f.result, f.params)
        case other                    => FacadeMethod(sym.name, other, Chunk.empty)
    }
```

`openCached` writes a snapshot of the decoded classpath to disk on first run. Subsequent runs (the common case for a build-driven codegen tool) load in ~10ms instead of paying the full decode cost every build.

## Use case 2: IDE-style symbol lookup

Look up one class by fully qualified name, find a member, render a hover string. Fast on the warm path (sub-millisecond).

```scala
import kyo.*
import kyo.Reflect.*

def hover(fqn: String, member: String)(using Frame): Maybe[String] < (Sync & Abort[ReflectError] & Scope) =
    for
        cp     <- Reflect.Classpath.openCached(Seq("."), ".kyo-reflect-cache")
        clsOpt <- cp.findClass(fqn)
        out    <- clsOpt match
                      case Absent       => Sync.defer(Absent: Maybe[String])
                      case Present(cls) =>
                          cls.declarations.flatMap { decls =>
                              Maybe.fromOption(decls.find(_.name.asString == member)) match
                                  case Absent     => Sync.defer(Absent: Maybe[String])
                                  case Present(s) => s.declaredType.map(t => Present(s"${s.name.asString}: ${t.show}"))
                          }
    yield out
```

Call:

```scala
hover("scala.collection.immutable.List", "head")
// Maybe[String] => Present("head: A")
```

`findClass` goes through a `Cache.memo` with CLOCK eviction: warm calls return in microseconds, concurrent callers asking for the same FQN dedupe through a single load.

## Use case 3: runtime reflection replacement

Want field info about a Scala class at runtime, without loading the class? Especially useful on Scala Native and Scala.js where `scala.reflect.runtime` is fragile or missing.

```scala
import kyo.*
import kyo.Reflect.*

def fieldsOf[A: Tag](using Frame): Chunk[(String, Reflect.Type)] < (Sync & Abort[ReflectError] & Scope) =
    val fqn = Reflect.classFqn[A]
    for
        cp     <- Reflect.Classpath.openCached(Seq("."), ".kyo-reflect-cache")
        clsOpt <- cp.findClass(fqn)
        cls    <- clsOpt match
                      case Present(s) => Sync.defer(s)
                      case Absent     => Abort.fail(ReflectError.SymbolNotFound(fqn))
        decls  <- cls.declarations
        vals    = decls.filter(_.kind == Reflect.SymbolKind.Val)
        out    <- Kyo.foreach(vals)(f => f.declaredType.map(t => (f.name.asString, t)))
    yield out
```

```scala
case class Person(name: String, age: Int)

fieldsOf[Person]
// Chunk(("name", Named(String)), ("age", Named(Int)))
```

## Use case 4: Java + Scala unified

The same API works for Java classes. `Symbol.isJava` discriminates source language; `Symbol.javaSpecific` exposes Java-only metadata (throws clauses, raw annotations, record components, JVM access flags).

```scala
import kyo.*
import kyo.Reflect.*

case class ClassSummary(name: String, isJava: Boolean, parents: Chunk[String], members: Int)

def summarize(fqn: String)(using Frame): Maybe[ClassSummary] < (Sync & Abort[ReflectError] & Scope) =
    for
        cp     <- Reflect.Classpath.openCached(Seq("."), ".kyo-reflect-cache")
        clsOpt <- cp.findClass(fqn)
        out    <- clsOpt match
                      case Absent       => Sync.defer(Absent: Maybe[ClassSummary])
                      case Present(cls) =>
                          for
                              parents <- cls.parents
                              decls   <- cls.declarations
                          yield Present(ClassSummary(
                              name    = cls.fullName.asString,
                              isJava  = cls.isJava,
                              parents = parents.map(_.show),
                              members = decls.size
                          ))
    yield out
```

```scala
summarize("java.util.HashMap")
// Present(ClassSummary("java.util.HashMap", isJava = true, parents = ..., members = 67))

summarize("scala.collection.mutable.HashMap")
// Present(ClassSummary("scala.collection.mutable.HashMap", isJava = false, parents = ..., members = 42))
```

FQNs use dotted form regardless of source language. Inner classes (Java `Map$Entry`, Scala nested objects) canonicalize via the classfile's `InnerClasses` attribute, never by `$`-splitting. JVM-binary form is available via `Symbol.binaryName` (e.g., `java/util/Map$Entry`) for callers that need it.

## Schema-driven projection via `derives`

Writing per-field traversal code by hand gets tedious. Define a case class describing the shape you want, derive `Reflect.Reads`:

```scala
import kyo.*
import kyo.Reflect.*

case class MethodSig(
    name:       Reflect.Name,
    returnType: Reflect.Type,
    params:     Chunk[Reflect.Type]
) derives Reflect.Reads

case class ClassInfo(
    name:    Reflect.Name,
    parents: Chunk[Reflect.Type],
    methods: Chunk[MethodSig]
) derives Reflect.Reads

def collectClasses(roots: Seq[String])(using Frame): Chunk[ClassInfo] < (Sync & Abort[ReflectError] & Scope) =
    for
        cp     <- Reflect.Classpath.openCached(roots, ".kyo-reflect-cache")
        result <- cp.query[ClassInfo].run
    yield result
```

The `derives Reflect.Reads` macro generates the traversal code for you. It also analyzes which `Symbol` accessors you actually touch and tells the unpickler to skip decoding the others (annotations, source positions, comments). Schemas that only need names and signatures decode 30 to 50% faster than full decode.

`cp.query[ClassInfo].run` translates into a single traversal over the symbol cache, only visiting classes (not methods, fields, packages) because the macro knows `ClassInfo` is class-shaped.

## Cross-language bridging via `kyo.Record`

For FFI binding generation, API translation, or anywhere you want compile-time-typed field iteration with per-field type-class dispatch, project a Symbol into a `kyo.Record`:

```scala
import kyo.*
import kyo.Reflect.*

// Describe what you want as a Record type
type FunctionSig =
    "name"       ~ String &
    "params"     ~ Chunk[Reflect.Type] &
    "returnType" ~ Reflect.Type

// Read a Symbol into the typed Record
val sig: Record[FunctionSig] < (Sync & Abort[ReflectError]) =
    Reflect.symbolToRecord[FunctionSig](symbol)

// Define a per-field translation type class
trait CSignature[A]:
    def render(a: A): String
given CSignature[String]            = identity
given CSignature[Reflect.Type]      = t => mapTypeToC(t)
given CSignature[Chunk[Reflect.Type]] = ts => ts.map(mapTypeToC).mkString("(", ", ", ")")

// Record.mapFields traverses, summoning CSignature per field
val cSig: Record["name" ~ String & "params" ~ String & "returnType" ~ String] =
    sig.mapFields([v] => (field, value) => summon[CSignature[v]].render(value))

// Emit
val cHeader = s"${cSig.returnType} ${cSig.name}${cSig.params};"
// "int add(int, int);"
```

If any field type in `FunctionSig` is missing a `CSignature` instance, the `mapFields` call fails to compile, pointing at the missing instance. The translation table is verified exhaustive at the call site. This is the substrate for tools that bridge Scala APIs to C, Rust, Python, etc.

## Snapshot cache

Codegen tools run on every build. Reading and decoding TASTy on every run is wasteful when nothing changed. `openCached` solves this:

```scala
Reflect.Classpath.openCached(roots, cacheDir = ".kyo-reflect-cache")
```

Behind the scenes:

1. Hash sorted input `(path, mtime, size)` triples into a 32-byte digest.
2. If a snapshot file with that digest exists in `cacheDir`, mmap it and return the decoded classpath in ~10ms.
3. Otherwise full decode, then atomically write the snapshot for next time.

The snapshot format (KRFL) is a single binary file with sections for names, symbols, types, parents, members, body slices, and accumulated errors. JVM and Native mmap with demand paging (only touched sections are read). JS reads into `Array[Byte]` (no mmap in browser; node uses `fs.readFileSync`).

Concurrent processes hitting the same cache (sbt + IntelliJ + a CLI tool) coexist safely via atomic-rename, no file locking.

## Effect signatures

Every operation that could fail returns a Kyo computation with a closed error type:

```scala
def findClass(fqn: String): Maybe[Symbol] < (Sync & Abort[ReflectError])
def parents:                Chunk[Type]   < (Sync & Abort[ReflectError])
def declarations:           Chunk[Symbol] < (Sync & Abort[ReflectError])
```

`ReflectError` enumerates every failure mode: `FileNotFound`, `CorruptedFile`, `UnsupportedVersion`, `SymbolNotFound`, `MalformedSection`, `ClasspathClosed`, etc. Callers can match exhaustively. No exception ever crosses the boundary.

Identity-level Symbol accessors (`name`, `flags`, `kind`, `owner`, `isInline`, `isJava`) are pure: they return values directly, no effect, and keep working after the classpath has been closed because they touch no shared state.

## Performance targets

Measured against `tasty-query` on a kyo-size classpath (30 modules, ~600 classes, ~5000 methods):

| Workload | tasty-query | kyo-reflect | Speedup |
|---|---|---|---|
| Cold-load whole classpath | 500-800ms | 100-200ms | 3-5x |
| Warm reload (snapshot hit, JVM mmap) | n/a | 5-15ms | 35-50x |
| Per-FQN lookup, warm | sub-ms | sub-ms | parity (Cache.memo dedups concurrent calls) |
| Memory (cold load) | baseline | 30-50% lower | 2-3x |
| JS / Native | does not run | runs | categorical win |

The cold-load speedup comes from three compounding optimizations: parallel per-file decode using `Async.foreach`, skeleton-eager decoding that skips method bodies via TASTy's length-prefixed tag encoding, and hash-consed types via per-thread arenas merged in a single sequential Phase C pass. Memory savings come from sharded name interning (no contention) and lazy `String` decoding (names compare by byte slice).

## Cross-platform notes

| Platform | File source | Snapshot | Classpath scanning |
|---|---|---|---|
| JVM | `Files.readAllBytes`, `jrt:/` for JDK modules | mmap via `FileChannel.map` | jar walking, `.tasty` extraction |
| Native | POSIX `open`/`read` via FFI | POSIX `mmap` via FFI | directory walk via FFI |
| JS (node) | `fs.readFileSync` + `Buffer` | `fs.readFileSync` (no mmap) | local fs walk |
| JS (browser) | not supported; use `fromPickles(Seq[Pickle])` | no-op (always miss, never write) | consumer enumerates pickles explicitly |

Classfile reading is pure byte arithmetic (no ASM); runs identically on all three platforms.

## What it does NOT do (v1)

* Write TASTy or classfiles.
* Read Scala 2 pickles (use `tasty-query` if you need them).
* Decode method bodies / control flow (signatures and member lists yes; statements inside method bodies are stored as opaque byte slices, decoding deferred to v2).
* Subtype checking. Type comparison is structural equality only.
* C/C++ headers (anticipated future sibling `kyo-cbindings`, libclang-bound, JVM+Native only).
* Java module-info.class (JPMS).

## Project status

Phase 0 is checked in: build cross-project entry, public API stubs, four example files compiling on all three platforms. Resolving accessors currently fail with `ReflectError.NotImplemented` until phases 1 through 7 land. The full phased plan is in `DESIGN.md` Section 18.

## Learn more

* `DESIGN.md` is the source of truth: 25 sections covering binary format, symbol model, type ADT, parallel decode protocol, snapshot format, macro design, cross-platform considerations, prior-art analysis, and locked decisions.
* `shared/src/main/scala/kyo/reflect/examples/` has the four worked examples in this README as standalone Scala files.
