<!-- doctest:setup
```scala
import kyo.*
import kyo.ffi.*
import kyo.ffi.Ffi.*

class MathContext
class MathModel

enum Precision(val value: Int):
    case Single extends Precision(0)
    case Double extends Precision(1)
    case Quad   extends Precision(2)
end Precision
object Precision:
    def fromInt(v: Int): Precision = Precision.values.find(_.value == v)
        .getOrElse(throw new IllegalArgumentException(s"Unknown Precision: $v"))

case class Vec2(x: Double, y: Double)
case class Circle(center: Vec2, radius: Double)
case class Stats(mean: Double, variance: Double)
case class Event(tag: Int, data: Int | Float)

trait MathBindings extends Ffi:
    def mathAdd(a: Int, b: Int)(using AllowUnsafe): Int
    def mathDot(a: Buffer[Double], b: Buffer[Double], n: Int)(using AllowUnsafe): Double
    @Ffi.blocking
    def mathSolveLarge(matrix: Buffer[Double], rows: Int, cols: Int)(using AllowUnsafe): Fiber.Unsafe[Int, Any]
    def mathDistance(a: Vec2, b: Vec2)(using AllowUnsafe): Double
    def mathStats(data: Buffer[Double], n: Int)(using AllowUnsafe): Stats
    def mathInit()(using AllowUnsafe): Handle[MathContext]
    def mathFree(ctx: Handle[MathContext])(using AllowUnsafe): Unit
    def mathLoadModel(path: String)(using AllowUnsafe): Handle[MathModel]
    def mathFind(name: String)(using AllowUnsafe): Maybe[Handle[MathModel]]
    def mathSetPrecision(ctx: Handle[MathContext], p: Precision)(using AllowUnsafe): Unit
    def mathGetPrecision(ctx: Handle[MathContext])(using AllowUnsafe): Precision
    def mathProcess(n: Int | Float)(using AllowUnsafe): Unit
    def mathVersion()(using AllowUnsafe): Borrowed[String]
    def mathGetCoeffs(ctx: Handle[MathContext], n: Int)(using AllowUnsafe): Borrowed[Buffer[Double]]
    def mathSort(data: Buffer[Double], n: Int, cmp: (Double, Double) => Int)(using AllowUnsafe): Unit
    def mathOnProgress(ctx: Handle[MathContext], cb: Double => Unit, guard: Ffi.Guard)(using AllowUnsafe): Unit
end MathBindings
object MathBindings extends Ffi.Config(library = "math")

import AllowUnsafe.embrace.danger
val math                         = Ffi.load[MathBindings]
val ctx: Handle[MathContext]     = math.mathInit()
def handleError(code: Int): Unit = ()
def useValue(value: Int): Unit   = ()
```
-->

<!-- doctest:default scope=inherited -->

# kyo-ffi

Call C functions directly from Scala. kyo-ffi lets you define a Scala trait that mirrors a C API, and the build plugin generates all the platform-specific code to make the calls work on JVM, Scala Native, and Scala.js. The same Scala source compiles on all three platforms.

| Platform | Mechanism |
|----------|-----------|
| JVM | [Java Panama](https://docs.oracle.com/en/java/javase/22/docs/api/java.base/java/lang/foreign/package-summary.html) (`java.lang.foreign`) |
| Native | Scala Native [`@extern`](https://scala-native.org/en/stable/user/interop.html) |
| JS | [koffi](https://koffi.dev) (Node only) |

The sbt plugin inspects your trait via TASTy, generates platform-specific implementations, compiles your C into a shared library, and packages everything into the JAR. No macros at call sites, no `MemorySegment` / `CString` / `Ptr` in user code. The only annotation is `@Ffi.blocking` for methods whose C implementation may block; everything else is expressed through types and structural patterns.

## Getting Started

Add the plugin and runtime dependency:

```scala doctest:expect=skipped
// project/plugins.sbt
addSbtPlugin("io.getkyo" % "kyo-ffi-plugin" % "<version>")

// build.sbt
lazy val root = (project in file("."))
    .enablePlugins(KyoFfiPlugin)
    .settings(
        ffiLibraryId                       := "math",
        libraryDependencies += "io.getkyo" %% "kyo-ffi" % "<version>"
    )
```

Drop a C file in `src/main/c/`:

```c
// src/main/c/math.c
int math_add(int a, int b) { return a + b; }
```

Declare the trait. It extends `Ffi`, plain Scala types, one method per C function. Every binding method performs a side effect (the native call), so it takes a trailing `(using AllowUnsafe)`: the FFI binding layer is the unsafe tier (see "The Unsafe Tier" below):

```scala doctest:expect=skipped
import kyo.*
import kyo.ffi.*

trait MathBindings extends Ffi:
    def mathAdd(a: Int, b: Int)(using AllowUnsafe): Int
```

Call it. The caller supplies the `AllowUnsafe` proof, typically by importing it inside an effect that has already suspended the side effect:

```scala doctest:scope=isolated
import AllowUnsafe.embrace.danger
println(math.mathAdd(2, 3)) // 5
```

`sbt compile` compiles `math.c` into a shared library, reads the TASTy of `MathBindings`, generates `MathBindingsImpl.scala` under `target/.../src_managed/`, and packages the library under `META-INF/native/<os>-<arch>/`. At runtime, `Ffi.load` extracts the library, loads it, and instantiates the generated impl. The generated code is on disk and inspectable: IDE navigation, debugger breakpoints, and stack traces all work.

### Loading

`Ffi.load[T]` throws subtypes of `FfiLoadError`: `LibraryNotFound` (native library not resolvable), `AbiMismatch` (generated-impl ABI vs runtime), `Unsupported` (32-bit host, browser Scala.js), `ImplNotFound` (no generated impl on the classpath). On the JVM only, it also throws `java.lang.IllegalStateException` when the generated impl class lacks a public nullary constructor (regenerate with `sbt clean compile`). Callers that want a single catch handler use `catch { case e: FfiLoadError => ... }`. The legacy `FfiUnsupported`, `FfiAbiMismatch`, and `FfiKoffiVersionMismatch` types remain as deprecated aliases for the corresponding subtypes, so existing `catch FfiUnsupported` blocks continue to match.

## Defining Bindings

A binding is a Scala trait extending `Ffi`. Each method maps to a C function.

### The Unsafe Tier

Every binding method performs a side effect: it calls into native code. kyo-ffi places bindings in the unsafe tier, so each binding method is generated to take a trailing `(using AllowUnsafe)` clause. `AllowUnsafe` is a compiler-enforced proof that the side effect has already been suspended at an outer scope; it carries no runtime cost. Declare each method with the clause:

```scala doctest:expect=skipped
import kyo.*
import kyo.ffi.*

trait MathBindings extends Ffi:
    def mathAdd(a: Int, b: Int)(using AllowUnsafe): Int
```

Callers supply the proof from inside a suspended context. The idiomatic path is `Sync.Unsafe.defer { ... }`, which suspends the side effect and provides `AllowUnsafe` for the block:

```scala doctest:scope=isolated
def add(a: Int, b: Int)(using Frame): Int < Sync =
    Sync.Unsafe.defer(math.mathAdd(a, b))
```

At application boundaries (a `KyoApp`, a test) you can instead import the proof directly with `import AllowUnsafe.embrace.danger`. The `(using AllowUnsafe)` clause is contextual evidence: the codegen never marshals it to C, and it is excluded from the C call descriptor along with any other `using`/given parameters (such as `Frame`).

### Method Naming

The default convention is camelCase to snake_case:

| Scala | C |
|-------|---|
| `mathAdd` | `math_add` |
| `mathDot` | `math_dot` |
| `mathSolveLarge` | `math_solve_large` |

When binding an existing library that doesn't follow this convention, declare an `Ffi.Config` companion with constructor parameters:

```scala doctest:scope=isolated
import kyo.ffi.*

object GslBindings extends Ffi.Config(
        library = "gsl",
        symbolPrefix = "gsl_",
        // mathAdd -> gsl_math_add
        symbols = Map("mathAdd" -> "gsl_custom_add")
        // mathAdd -> gsl_custom_add (overrides the prefix rule)
    )
```

The companion is entirely optional. Absent config means default conventions.

### Blocking Calls

Apply `@Ffi.blocking` to methods whose C implementation may block (I/O, locks, syscalls that can suspend). A `@Ffi.blocking` method does not return its value directly: it returns a `Fiber.Unsafe[A, Any]` that the caller awaits.

```scala doctest:expect=skipped
import kyo.*
import kyo.ffi.*

trait MathBindings extends Ffi:
    def mathAdd(a: Int, b: Int)(using AllowUnsafe): Int
    def mathDot(a: Buffer[Double], b: Buffer[Double], n: Int)(using AllowUnsafe): Double

    @Ffi.blocking
    def mathSolveLarge(matrix: Buffer[Double], rows: Int, cols: Int)(using AllowUnsafe): Fiber.Unsafe[Int, Any]
end MathBindings
```

The caller bridges the returned fiber into an effectful computation with `.safe.get`, which produces an `A < Async`:

```scala doctest:scope=isolated
def solve(matrix: Buffer[Double], rows: Int, cols: Int)(using Frame): Int < (Async & Abort[Any]) =
    Sync.Unsafe.defer(math.mathSolveLarge(matrix, rows, cols)).map(_.safe.get)
```

The fiber must be awaited. Do not assume it is already completed (never read it with `.poll().get` or similar): on JS the underlying call is genuinely pending until the worker thread finishes. The await is the only correct way to observe the result on every platform.

| Platform | Non-blocking | `@Ffi.blocking` |
|----------|-------------|-----------------|
| JVM | Standard Panama downcall, returns the value | Safe (non-critical) Panama downcall runs on the carrier; result wrapped in an already-completed `Fiber.Unsafe` |
| Native | Plain `@extern`, returns the value | `@blocking @extern` downcall runs on the carrier; result wrapped in an already-completed `Fiber.Unsafe` |
| JS | Synchronous koffi call, returns the value | koffi `.async` dispatch on a libuv worker; `Fiber.Unsafe` resolved from the completion callback |

On JVM and Native the blocking downcall runs synchronously on (and parks) the carrier thread that called the binding method. The safe Panama downcall and the `@blocking @extern` downcall let the GC and the Kyo scheduler's blocking monitor recognise the parked carrier, drain its queue to other workers, and avoid starving the scheduler. The result is already available by the time the binding method returns, so the fiber is an already-completed `Promise.Unsafe`; awaiting it is non-blocking.

On JS there is a single event-loop thread. A synchronous blocking FFI call would stall that thread (head-of-line blocking: nothing else can run until the call returns). kyo-ffi therefore dispatches a `@Ffi.blocking` call through koffi's `.async`, which runs it on a libuv worker thread and resolves the fiber from the completion callback. The event loop stays responsive; the caller awaits the fiber exactly as on the other platforms.

The call is not cancellable mid-flight: once the C call has started it runs to completion. Any argument buffers passed to the call must outlive it.

Forgetting `@Ffi.blocking` on a method that actually blocks risks GC starvation (JVM), deadlock (Native), or a stalled event loop (JS). The plugin ships an allowlist of known-blocking POSIX symbols (`read`, `write`, `connect`, `accept`, `poll`, `epoll_wait`, ...) and warns when one is matched without the annotation. `ffiStrictBlocking := true` promotes the warning to an error.

### Callbacks

Function-type parameters become C function pointers. The plugin distinguishes two patterns from the signature.

#### Transient Callbacks

C invokes the callback during the call only. No guard needed:

```scala doctest:expect=skipped
trait MathBindings extends Ffi:
    // ... previous methods ...
    def mathSort(data: Buffer[Double], n: Int, cmp: (Double, Double) => Int)(using AllowUnsafe): Unit
```

The upcall stub lives only for the duration of the call.

#### Retained Callbacks

C stores the callback for later invocation. Pass an `Ffi.Guard` parameter to control lifetime:

```scala doctest:expect=skipped
trait MathBindings extends Ffi:
    // ... previous methods ...
    def mathOnProgress(ctx: Handle[MathContext], cb: Double => Unit, guard: Ffi.Guard)(using AllowUnsafe): Unit
    def mathRunComputation(ctx: Handle[MathContext])(using AllowUnsafe): Unit
end MathBindings
```

```scala doctest:expect=skipped
Ffi.Guard.use { guard =>
    math.mathOnProgress(ctx, progress => println(s"$progress%"), guard)
    math.mathRunComputation(ctx)
}
// guard closed; callback no longer valid
```

The guard registers itself in a process-wide set on `open()` so the callback stays GC-reachable until `close()`. On JVM, a `Cleaner` logs a warning if the guard is GC'd without explicit close. The plugin ships an allowlist of known-retaining C symbols (`epoll_ctl`, `pthread_create`, `signal`, ...) and warns when a method's C symbol matches without a declared `Ffi.Guard`. `ffiStrictCallbacks := true` promotes to error.

##### Close Semantics

`Ffi.Guard.close()` (and the explicit-timeout variant `closeAwait(timeout)`) returns an `Ffi.CloseOutcome`:

| Outcome         | Meaning                                                                                                                                                                                                                |
|-----------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `Clean`         | All retained callbacks finished within the drain timeout. `platformCloser` has run: on JVM the arena is closed, on Native the retained-callback pool slots are released, on JS koffi handles are unregistered.         |
| `TimedOut`      | The drain timeout elapsed while at least one retained callback was still in flight. The arena / retained slots are LEFT ALIVE so the in-flight callback keeps reading valid memory. `platformCloser` is deferred until the last `endCallback` returns, at which point it runs exactly once and the guard transitions to the fully closed state. If a retained callback never returns, the arena is leaked until process exit; this is a bounded memory cost in exchange for no use-after-free. |
| `AlreadyClosed` | Second `close()` on a guard that already reached either the clean or timed-out path. No side effects.                                                                                                                  |

The default drain timeout is 5s (override via `-Dkyo.ffi.guard.drainTimeoutMs=N` on JVM). For explicit control use:

```scala doctest:scope=isolated
import scala.concurrent.duration.*
def closeGuard(guard: Ffi.Guard)(using Frame): Unit =
    guard.closeAwait(100.millis) match
        case Ffi.CloseOutcome.Clean         => ()
        case Ffi.CloseOutcome.TimedOut      => () // arena leaked until callback returns; log + continue
        case Ffi.CloseOutcome.AlreadyClosed => ()
```

`Ffi.Guard.use { g => ... }` always calls `close()` (the default-timeout form) and discards the outcome.

#### Callback Exception Handling

Callbacks must not propagate exceptions into C. Doing so corrupts the C call stack. kyo-ffi enforces this on every platform: if the user callback throws, the runtime logs the exception to `System.err` (naming the binding, method, and callback kind) and returns a typed-zero default at the C boundary (`0` for numerics, `""` for `String`, no-op for `Unit`). The surrounding FFI call continues normally.

### Errors and errno

For methods that return a plain type, errno is not captured or checked; the return value is passed through directly. To inspect errno after a C call, declare the return type as `Ffi.Outcome[A]` (where `A` is the C return width, `Int` or `Long`):

**Plain return:** Errno is not captured. Use this for calls where errno is not meaningful:

```scala doctest:scope=isolated
import kyo.*
import kyo.ffi.*

trait FastOpBindings extends Ffi:
    def fastOp(x: Int)(using AllowUnsafe): Int // errno not checked
```

**Outcome return (user handles errno):** Declare the return type as `Ffi.Outcome[A]`, a zero-allocation opaque carrier that packs the return value and the error code into a single `Long`. The phantom `A` is the C return width (`Int` or `Long`) the codegen reads to pick the descriptor layout. The errno is captured via platform-specific mechanisms (Panama `captureCallState("errno")` on JVM, `errno.h` on Native, `koffi.errno()` on JS):

```scala doctest:scope=isolated
import kyo.*
import kyo.ffi.*

trait RiskyBindings extends Ffi:
    def riskyOp(x: Int)(using AllowUnsafe): Ffi.Outcome[Int]
object RiskyBindings extends Ffi.Config(library = "math")

import AllowUnsafe.embrace.danger
val risky = Ffi.load[RiskyBindings]
val r     = risky.riskyOp(42)
if r.errorCode != 0 then
    handleError(r.errorCode)
else
    useValue(r.value.toInt) // .value is Long; an Int consumer reads .toInt
end if
```

If `Ffi.load[T]` itself fails, it throws a subtype of `FfiLoadError` (`LibraryNotFound`, `AbiMismatch`, `Unsupported`, `ImplNotFound`), or, on the JVM only, a `java.lang.IllegalStateException` when the generated impl class lacks a public nullary constructor (regenerate with `sbt clean compile`). See the Loading section above for the full catch surface.

### Variadic Functions

Variadic C functions like `printf` and `snprintf` are bound by declaring the last Scala parameter as `args: Any*`:

```scala doctest:scope=isolated
import kyo.*
import kyo.ffi.*

trait PrintBindings extends Ffi:
    def snprintf(buf: Buffer[Byte], size: Long, fmt: String, args: Any*)(using AllowUnsafe): Int
```

Supported vararg runtime types: `Int`, `Long`, `Double`, `String`, `Buffer[A]`. Anything else raises `FfiUnsupported` at the call site.

| Platform | Support |
|----------|---------|
| JVM | Full. Panama rebuilds the descriptor per call with `Linker.Option.firstVariadicArg`. |
| JS | Full. koffi variadic convention (`"..."` marker + typed value stream). |
| Native | Rejected at build time. Scala Native's `@extern` cannot express variadic signatures. |

For cross-platform variadic coverage, place the `Any*` binding under `{jvm,js}/src/main/scala` and put a non-variadic C wrapper under `shared`:

```scala doctest:expect=skipped
import kyo.*
import kyo.ffi.*

// Non-variadic C wrapper. Works on all platforms including Native
trait MathBindings extends Ffi:
    // ... other methods ...
    def mathFormatResult(buf: Buffer[Byte], size: Long, value: Double)(using AllowUnsafe): Int
```

#### Binding a Variadic C Function Non-Variadically

`args: Any*` is the only way to feed extra arguments through the variadic calling convention. A binding declared without it is generated as a fixed-arity call descriptor. Binding a genuinely variadic C function (one declared with `...`, such as `int fcntl(int, int, ...)`) as a fixed-arity method silently misbehaves on arm64. The AArch64 calling convention (AAPCS64) routes variadic arguments through a different path than fixed arguments, so a fixed-arity descriptor passes the trailing argument in the wrong place: the call returns success but the variadic argument is dropped.

There is no annotation to mark a single trailing fixed argument as variadic (a `@Ffi.variadic` declaration does not exist yet; it is a possible future enhancement). The correct workaround is to wrap the variadic call in a small non-variadic C shim that takes exactly the arguments you need, and bind the shim:

```c
// Non-variadic shim around the variadic fcntl. Bind THIS, not fcntl directly.
int kyo_posix_set_nonblocking(int fd) {
    int flags = fcntl(fd, F_GETFL, 0);
    if (flags < 0) return -1;
    return fcntl(fd, F_SETFL, flags | O_NONBLOCK);
}
```

```scala doctest:scope=isolated
import kyo.*
import kyo.ffi.*

// Bind the fixed-arity shim, never the variadic fcntl.
trait PosixBindings extends Ffi:
    def kyoPosixSetNonblocking(fd: Int)(using AllowUnsafe): Int
```

`kyo-net`'s `kyo_posix_set_nonblocking` is the reference example of this pattern.

## Types

The trait method signatures use standard Scala types. The codegen marshals them to C equivalents at the platform boundary.

### Type Mapping

The plugin auto-marshals these Scala types:

| Scala | C | Direction |
|-------|---|-----------|
| `Boolean` | `int` (0/1) | both |
| `Byte` | `int8_t` | both |
| `Short` | `int16_t` | both |
| `Int` | `int32_t` | both |
| `Long` | `int64_t` | both |
| `Float` | `float` | both |
| `Double` | `double` | both |
| `Unit` | `void` | return |
| `String` | `const char*` (UTF-8, null-terminated) | input |
| `Array[A]` | `A*` | input |
| `Buffer[A]` | `A*` | both |
| `Handle[A]` | `void*` (or typed pointer) | both |
| `Maybe[Handle[A]]` | `void*` (nullable) | return |
| `Borrowed[String]` | `const char*` (C-owned, copied to Scala) | return |
| `Borrowed[Buffer[A]]` | `A*` (C-owned, wrapped as no-op-close buffer) | return |
| `A \| B` | union (all variants at offset 0) | input / struct field |

Strings are encoded into a per-thread scratch arena before each call and freed when the call returns. Arrays are zero-copy when the method is non-blocking (JVM pins the on-heap array; Native passes `arr.at(0)` directly). When the method is `@Ffi.blocking`, the array is copied into a transient buffer to avoid GC interference.

`Long` is always `int64_t`, never C `long`. This avoids the Windows LP64/LLP64 split. If you need C `long` (32-bit on Windows), write a one-line C adapter that takes `int64_t`.

### Buffers

`Buffer[A]` is kyo-ffi's off-heap typed buffer. Use it whenever C needs a pointer to memory it can read or write:

```scala doctest:expect=skipped
import kyo.*
import kyo.ffi.*

trait MathBindings extends Ffi:
    def mathAdd(a: Int, b: Int)(using AllowUnsafe): Int
    def mathDot(a: Buffer[Double], b: Buffer[Double], n: Int)(using AllowUnsafe): Double
```

The primary lifetime pattern is `Buffer.use`:

```scala doctest:scope=isolated
import AllowUnsafe.embrace.danger
Buffer.use[Double, Double](3) { a =>
    Buffer.use[Double, Double](3) { b =>
        a.set(0, 1.0); a.set(1, 2.0); a.set(2, 3.0)
        b.set(0, 4.0); b.set(1, 5.0); b.set(2, 6.0)
        math.mathDot(a, b, 3) // 32.0
    }
}
```

The buffer is allocated on entry and released on exit, even when the block throws.

| Method | Purpose |
|--------|---------|
| `Buffer.use[A](n) { buf => ... }` | scoped buffer; closed at block exit |
| `Buffer.confinedUse[A](n) { ... }` | single-thread variant, faster on JVM, identical on Native/JS |
| `Buffer.alloc[A](n)` | unscoped buffer; caller must `close()` |
| `Buffer.allocConfined[A](n)` | single-thread unscoped buffer; faster on JVM, caller must `close()` |
| `Buffer.fromArray[A](arr)` | copy on-heap `Array` into a fresh buffer |
| `Buffer.copyToArray[A](buf, from, len)` | copy buffer range out to a fresh `Array` |
| `Buffer.fromUtf8(s)` | UTF-8 + null terminator into a `Buffer[Byte]` |
| `Buffer.useArray[A](arr) { buf => ... }` | scoped buffer from array; closed at block exit |
| `Buffer.mmapReadOnly(path)` | memory-map a file as read-only `Buffer[Byte]` |
| `Buffer.mmapReadWrite(path)` | memory-map a file as read-write `Buffer[Byte]` |

Backed by `java.lang.foreign.Arena` (JVM), `stdlib.malloc/free` (Native), and `Uint8Array` (JS). Thread-safe by default.

### Structs

A case class whose fields are all supported types is a struct argument or return value. The plugin detects this from the signature. No annotation, no marker:

```scala doctest:expect=skipped
import kyo.*
import kyo.ffi.*

case class Vec2(x: Double, y: Double)
case class Circle(center: Vec2, radius: Double)

trait MathBindings extends Ffi:
    // ... other methods ...
    def mathDistance(a: Vec2, b: Vec2)(using AllowUnsafe): Double
    def mathCircleArea(c: Circle)(using AllowUnsafe): Double
end MathBindings
```

Layout matches what the C compiler produces on the target ABI: declaration order, natural alignment, native endianness, total size rounded up to max-field alignment.

Structs may contain nested case-class structs (recursively), pointer fields (`Buffer[A]`), and string fields (`String`). `String` fields in input position are encoded into scratch and the struct holds the pointer for the call's duration. Recursive struct types (a struct containing itself) are rejected. Use a pointer field for that.

#### Packed Structs

Some C wire protocols use `#pragma pack` (no padding between fields). Opt in via the companion config:

```scala doctest:scope=isolated
import kyo.ffi.*

trait WireBindings  extends Ffi
object WireBindings extends Ffi.Config(packedStructs = Set("PackedVec2"))
```

### Multi-value Returns

C uses out-parameters for multi-value returns. In Scala, return a case class:

```scala doctest:expect=skipped
import kyo.*
import kyo.ffi.*

case class Stats(mean: Double, variance: Double)

trait MathBindings extends Ffi:
    // ... other methods ...
    def mathStats(data: Buffer[Double], n: Int)(using AllowUnsafe): Stats
```

The plugin infers the C signature:

```c
double math_stats(double* data, int n, double* out_variance);
//  ^^^                                 ^^^^^^^^^^^^^^^^
//  first field = C return              remaining fields = out-params
```

The first field of the case class maps to the C return value. Every subsequent field becomes an out-pointer argument. A case class with only one field is a build-time error. For a single primitive return, declare the method as that primitive directly.

### By-Value Struct Returns

The default multi-value mapping decomposes a case-class return into a C return value plus trailing out-params. When the C function instead fills a whole struct through a caller-allocated pointer, annotate the method with `@Ffi.byValue`:

```scala doctest:expect=skipped
import kyo.*
import kyo.ffi.*

case class Vec2(x: Double, y: Double)

trait MathBindings extends Ffi:
    // ... other methods ...
    @Ffi.byValue
    def mathMakeVec(x: Double, y: Double)(using AllowUnsafe): Vec2
end MathBindings
```

The plugin maps this to a C function that takes a pointer to caller-allocated struct storage as its FIRST argument, fills it, and returns `void`:

```c
void math_make_vec(Vec2* out, double x, double y);
//                 ^^^^^^^^^^
//                 struct out-pointer first; C fills it and returns void
```

kyo-ffi allocates the storage, passes the out-pointer first, and marshals the filled struct back into the case class. This mirrors how struct parameters cross the boundary (also as pointers), so it is register-ABI-free and identical on JVM, Native, and JS. From Scala the method still returns the bare case class.

Unlike a multi-value return (which requires at least two fields), a by-value struct return may have a single field. The annotation only selects the return ABI: `@Ffi.byValue` for the `void f(S* out, ...)` form, no annotation for the default multi-value (C return plus out-params) form.

### Handles and Null Safety

C libraries commonly expose opaque pointers to internal state (`sqlite3*`, `llama_model*`, `EVP_MD_CTX*`). `Handle[A]` represents these as typed values in Scala. The type parameter `A` is a phantom type that prevents mixing different handle types at compile time:

```scala doctest:expect=skipped
import kyo.*
import kyo.ffi.*

class MathContext
class MathModel

trait MathBindings extends Ffi:
    // ... other methods ...
    def mathInit()(using AllowUnsafe): Handle[MathContext]
    def mathFree(ctx: Handle[MathContext])(using AllowUnsafe): Unit
    def mathLoadModel(path: String)(using AllowUnsafe): Handle[MathModel]
    def mathSetSeed(ctx: Handle[MathContext], seed: Long)(using AllowUnsafe): Unit
end MathBindings
```

`MathContext` and `MathModel` are marker types. Any class works as a marker:

```scala doctest:expect=skipped
class MathContext
class MathModel
```

`Handle[MathContext]` and `Handle[MathModel]` are distinct types. Passing one where the other is expected is a compile error. At runtime, both are just pointer-sized values with zero wrapping overhead.

Handle values can only be created by the generated FFI code, not by user code directly.

C functions that return pointers may return NULL. kyo-ffi encodes null safety in the return type.

A bare `Handle[A]` return enforces a non-null contract. If C returns NULL, the runtime throws `FfiNullPointer`:

```scala doctest:expect=skipped
import kyo.*
import kyo.ffi.*

class MathContext

trait MathBindings extends Ffi:
    def mathInit()(using AllowUnsafe): Handle[MathContext]
    def mathSetSeed(ctx: Handle[MathContext], seed: Long)(using AllowUnsafe): Unit
object MathBindings extends Ffi.Config(library = "math")

import AllowUnsafe.embrace.danger
val math = Ffi.load[MathBindings]
val ctx  = math.mathInit() // throws FfiNullPointer if C returned NULL
math.mathSetSeed(ctx, 42) // safe, ctx is guaranteed non-null
```

When a C function may legitimately return NULL, declare the return type as `Maybe[Handle[A]]`. NULL becomes `Absent`, non-null becomes `Present(handle)`:

```scala doctest:expect=skipped
import kyo.*
import kyo.ffi.*

class MathModel

trait MathBindings extends Ffi:
    def mathFind(name: String)(using AllowUnsafe): Maybe[Handle[MathModel]]
    def mathLoadModel(model: Handle[MathModel])(using AllowUnsafe): Unit
object MathBindings extends Ffi.Config(library = "math")

import AllowUnsafe.embrace.danger
val math = Ffi.load[MathBindings]
math.mathFind("weights") match
    case Present(model) => math.mathLoadModel(model)
    case Absent         => println("model not found")
```

The codegen enforces this at the call boundary. There is no `isNull` method. The type system prevents null handles from reaching user code.

| Return type | NULL behavior |
|---|---|
| `Handle[A]` | throws `FfiNullPointer` |
| `Maybe[Handle[A]]` | returns `Absent` |

### Enums

The codegen detects C integer enums structurally from the Scala 3 enum definition. No annotation is needed. An enum is recognized as a C int enum when both conditions hold:

1. Every case has a `val value: Int` parameter
2. The companion has a `def fromInt(v: Int): T` method

```scala doctest:expect=skipped
enum Precision(val value: Int):
    case Single extends Precision(0)
    case Double extends Precision(1)
    case Quad   extends Precision(2)
end Precision

object Precision:
    def fromInt(v: Int): Precision = Precision.values.find(_.value == v)
        .getOrElse(throw new IllegalArgumentException(s"Unknown Precision: $v"))
```

At the FFI boundary, the generator converts enum cases to `Int` (via `.value`) for parameters and from `Int` (via `fromInt`) for returns. If an enum is used in a binding method but does not match the pattern (missing `value` field or `fromInt` method), the build fails with a clear error:

```scala doctest:expect=skipped
import kyo.*
import kyo.ffi.*

class MathContext

enum Precision(val value: Int):
    case Single extends Precision(0)
    case Double extends Precision(1)
    case Quad   extends Precision(2)
end Precision
object Precision:
    def fromInt(v: Int): Precision = Precision.values.find(_.value == v)
        .getOrElse(throw new IllegalArgumentException(s"Unknown Precision: $v"))

trait MathBindings extends Ffi:
    // ... other methods ...
    def mathSetPrecision(ctx: Handle[MathContext], p: Precision)(using AllowUnsafe): Unit
    def mathGetPrecision(ctx: Handle[MathContext])(using AllowUnsafe): Precision
end MathBindings
```

### Unions

Union types are expressed as Scala 3 `A | B` union types. The codegen detects `OrType` in TASTy and emits union layout: `size = max(sizeof(variants))`, `alignment = max(alignof(variants))`, all variants at offset 0. At runtime, the codegen checks the value's type and writes the corresponding bytes:

```scala doctest:expect=skipped
import kyo.*
import kyo.ffi.*

trait MathBindings extends Ffi:
    // ... other methods ...
    def mathProcess(n: Int | Float)(using AllowUnsafe): Unit
```

Union types work as parameters and struct fields:

```scala doctest:expect=skipped
case class Event(tag: Int, data: Int | Float)
```

Union variant types must be primitives or structs (case classes). `String`, `Buffer[A]`, `Handle[A]`, and function types are rejected at build time. Union returns are not supported; use the concrete variant type directly when the active variant is known.

A struct variant marshals on every backend, including struct variants whose fields are pointer types (`String`, `Buffer[A]`, `Handle[A]`, function pointers). JVM and Native write the struct's fields into the union's native storage; JS serializes the struct into the union buffer with koffi's encoder, so the pointer fields are laid out identically.

### Borrowed Returns

Top-level `String` and `Buffer[A]` returns are supported when C retains ownership of the returned memory. Declare the intent with `Borrowed[T]` as the return type:

```scala doctest:expect=skipped
import kyo.*
import kyo.ffi.*

class MathContext

trait MathBindings extends Ffi:
    // ... other methods ...
    def mathVersion()(using AllowUnsafe): Borrowed[String]
    def mathGetCoeffs(ctx: Handle[MathContext], n: Int)(using AllowUnsafe): Borrowed[Buffer[Double]]
end MathBindings
```

For `Borrowed[String]`, the returned pointer is decoded into a Scala `String` (the bytes are copied, so the returned String is Scala-owned). For `Borrowed[Buffer[A]]`, the returned pointer is wrapped as a borrowed `Buffer` whose `close()` is a no-op. A NULL C pointer surfaces as a Scala `null` for borrowed String returns and as a zero-length Buffer for borrowed Buffer returns.

The element count for `Borrowed[Buffer[A]]` is inferred from the method's parameters: when there is exactly one `Int` or `Long` parameter, it is used as the size. When there are zero or multiple candidates, the build fails with a clear error.

Without `Borrowed`, a top-level `String` / `Buffer[A]` return is rejected at build time. The ownership intent must be explicit. Applying `Borrowed` to a primitive / `Unit` / struct return is similarly rejected.

Checked-borrow mode provides use-after-free diagnostics. Enable process-wide with `-Dkyo.ffi.checkedBorrows=true` or per-binding via `Ffi.Config.checkedBorrows`. When enabled, every borrowed `Buffer` is attached to a `BorrowOwner`; each `get`/`set` verifies the owner is still valid, throwing `BorrowRevoked` if revoked.

## Configuration

Configuration is optional. Defaults work for most projects.

### Ffi.Config

`Ffi.Config` constructor parameters, all optional with defaults and all compile-time constants:

| Field | Default | Purpose |
|-------|---------|---------|
| `library` | snake_case of trait name | shared library identifier |
| `symbolPrefix` | `""` | prepended to every derived C symbol |
| `symbols` | `Map.empty` | per-method C symbol overrides |
| `packedStructs` | `Set.empty` | case-class names whose layout is packed |
| `scratchSize` | `Absent` | per-binding JVM scratch block size override (bytes) |
| `checkedBorrows` | `false` | opt-in BorrowOwner validation on borrowed buffers |
| `headers` | `Chunk.empty` | C headers required; missing headers emit stubs on Native |
| `nativeBundled` | `false` | when true, skip `@link` so Native compiles the C into the binary instead of linking `-l<library>` |

### System Properties

| Property | Default | Purpose |
|----------|---------|---------|
| `kyo.ffi.scratch.size` | `65536` | per-thread scratch allocator block size (bytes) |
| `kyo.ffi.scratch.maxSize` | `4194304` | maximum scratch size after auto-growth (4 MiB) |
| `kyo.ffi.scratch.logSpills` | `false` | log to stderr when scratch allocations spill |
| `kyo.ffi.scratch.maxBindings` | `256` | maximum entries in per-binding scratch map before eviction |
| `kyo.ffi.stringFieldMaxBytes` | `65536` | upper bound for NUL scan on borrowed C string fields |
| `kyo.ffi.checkedBorrows` | `false` | enable BorrowOwner validation process-wide |
| `kyo.ffi.guard.drainTimeoutMs` | `5000` | drain timeout (ms) when closing a guard with in-flight callbacks |
| `kyo.ffi.native.retainedCallbackPoolSize` | `1024` | retained callback slots per shape (Native) |
| `kyo.ffi.native.leakSweepIntervalMs` | `1000` | leak detector sweep interval (Native) |

Override the runtime library load path with `-Dkyo.ffi.<library_id>.path=/abs/path/to/lib`.

### Header Gating

Platform-specific system calls (e.g. `epoll` on Linux, `kqueue` on macOS) can coexist in the same source tree via the `headers` config field. When targeting Scala Native, the generator probes the build host for each header via `cc -E`; if any header is missing, the emitted impl contains runtime stubs (throwing `UnsupportedOperationException`) instead of `@extern` declarations:

```scala doctest:scope=isolated
import kyo.*
import kyo.ffi.*

trait EpollBindings  extends Ffi
object EpollBindings extends Ffi.Config(library = "c", headers = Chunk("sys/epoll.h"))

trait KqueueBindings  extends Ffi
object KqueueBindings extends Ffi.Config(library = "c", headers = Chunk("sys/event.h"))
```

On a macOS build host, `EpollBindings` emits stubs while `KqueueBindings` generates real `@extern` calls. On Linux, the reverse. This prevents link failures without requiring platform-specific source trees.

### Multiple Libraries

`ffiLibraries` declares more than one shared library:

```scala doctest:expect=skipped
ffiLibraries := Seq(
    FfiLibrary("kyo_tcp", tcpCSources, linkLibs = Seq("pthread")),
    FfiLibrary("kyo_tls", tlsCSources, linkLibs = Seq("ssl", "crypto"))
)
```

Each binding trait's companion declares which library it uses:

```scala doctest:scope=isolated
import kyo.ffi.*

trait TcpBindings  extends Ffi
object TcpBindings extends Ffi.Config(library = "kyo_tcp")
```

Single-library mode (`ffiLibraryId`) and multi-library mode (`ffiLibraries`) are mutually exclusive.

If a binding's `Ffi.Config(library = "...")` id is not declared in `ffiLibraries` / `ffiLibraryId` and is not a system library, `ffiGenerate` fails the build and names the exact fix (add an `FfiLibrary(id, ...)`, set `ffiLibraryId`, or add the id to `ffiSystemLibraries`).

### Static Linking Third-Party Archives

`FfiLibrary.linkLibs` folds vendored static archives (`lib<name>.a` under `libDirs`) into the shim. Two things commonly trip up a first integration:

- **Transitive link dependencies are not pulled automatically.** kyo-ffi links each archive directly with `cc` / the Scala Native linker, not as a CMake/pkg-config target, so an archive's own link-interface libraries are not discovered. Enumerate every transitive dependency yourself. Use `linkLibsByOs` for the ones that differ per platform. For example, an archive that needs `uuid` everywhere and additionally `atomic` on `aarch64`:

```scala doctest:expect=skipped
ffiLibraries := Seq(
    FfiLibrary(
        "kyo_driver",
        driverCSources,
        libDirs = Seq(vendorDir),
        linkLibs = Seq("driver", "uuid"),
        linkLibsByOs = Map("linux-aarch64" -> Seq("atomic"))
    )
)
```

- **Multiple archives can collide on duplicate symbols.** Linking a whole vendored tree (a library archive plus its bundled copies of common objects) can fail with hundreds of duplicate-symbol errors on `ld64`. Link only the archive that actually provides the symbols your bindings call (for instance the driver archive, not also a client archive that re-bundles the same objects).

`ffiPackage` bundles only the libraries the plugin compiles from your `cSources` shim into `META-INF/native/<os>-<arch>/`; it does not bundle a standalone third-party `.so`. To ship a third-party library, static-link it into your shim via `linkLibs` (above) so there is a single self-contained artifact, rather than relying on a separately-distributed shared object.

## Cross-Platform

The same Scala source compiles on JVM, Scala Native, and Scala.js (Node). The plugin auto-detects the target platform from the surrounding cross-project setup.

Behavior is uniform for: trait API, `Buffer` lifetime semantics, `Ffi.Guard` registration, errno capture, struct layout, multi-value returns. Differences:

| Aspect | JVM | Native | JS |
|--------|-----|--------|-----|
| `@Ffi.blocking` mechanism | carrier-park, scheduler-aware downcall | carrier-park, GC-safepoint-aware downcall | koffi `.async` on a libuv worker |
| `@Ffi.blocking` fiber state on return | already completed | already completed | pending until the worker finishes |
| Guard leak warnings | `Cleaner` logs to stderr | silent | logs to stderr |
| Critical downcalls | JDK 22+ (`Linker.Option.critical`) | N/A | N/A |
| Variadic functions | supported | rejected at build time | supported |
| Borrowed String returns | supported | supported | supported |

**64-bit hosts only.** 32-bit hosts are rejected at runtime on first `Ffi.load` call.

### JS / koffi Setup

On JS (Node), kyo-ffi performs the native call through the [koffi](https://koffi.dev) npm package. koffi is a prebuilt native addon: it is loaded with `require('koffi')` at runtime and cannot be inlined into the emitted `.js`. A JS consumer must therefore have `koffi` resolvable in their `node_modules`. The required version is `^2.7` (`2.7.0 <= koffi < 3.0.0`), which matches the `kyo.ffi.internal.FfiPlatformErrors.KoffiSupportedRange` constant the runtime checks against. Pin it in your `package.json`:

```json
{ "dependencies": { "koffi": "^2.7" } }
```

koffi ships prebuilt binaries for common platforms, so installing it needs no native toolchain (no C compiler, no node-gyp build step). kyo's own test build installs it automatically: a `Test / compile` hook writes a `package.json` pinning `koffi` to `^2.7` and runs `npm install` into the test target directory before the JS tests run.

The first `Ffi.load` call on Scala.js runs an ABI probe that verifies `koffi.version` satisfies `^2.7` and that all required methods are exported. A failed probe throws `FfiKoffiVersionMismatch`. The probe runs once per Node session.

## Security

Two operator-controlled knobs can load native code. Never set them from untrusted input:

- `-Dkyo.ffi.<libraryId>.path=/abs/path` overrides the bundled library lookup and loads the path directly. An attacker who controls this property can load an arbitrary shared object.
- `-Dkyo.ffi.extractDir=/abs/path` (JVM only) changes where bundled libraries are unpacked before load. A writable directory under attacker control permits a swap-on-load attack.

32-bit hosts are rejected at the first `Ffi.load` call; this is a runtime check, not a build-time one.

## Reference

### Plugin Settings and Tasks

#### Settings

| Setting | Type | Default | Purpose |
|---------|------|---------|---------|
| `ffiLibraryId` | `String` | `"kyo_ffi"` | single-library identifier |
| `ffiLibraries` | `Seq[FfiLibrary]` | `Nil` | multi-library mode |
| `ffiCSources` | `Seq[File]` | `src/main/c/**/*.c` | C source files. Defaults to src/main/c/**/*.c when the directory exists. |
| `ffiCHeaders` | `Seq[File]` | `src/main/c/**/*.h` | C header files for rebuild triggers. Defaults to src/main/c/**/*.h when the directory exists. |
| `ffiIncludes` | `Seq[File]` | `src/main/c/` | -I include directories for C compilation. Defaults to src/main/c/ when the directory exists. |
| `ffiCFlags` | `Seq[String]` | `[-O2, -fPIC, -Wall]` | extra C compiler flags |
| `ffiLinkLibs` | `Seq[String]` | `Nil` | `-l<name>` link libraries |
| `ffiLinkFlags` | `Seq[String]` | `Nil` | extra linker flags |
| `ffiCCompiler` | `String` | `cc` (or `$CC`) | C compiler binary |
| `ffiStaticLink` | `Boolean` | `false` | append `-static` family of flags |
| `ffiScratchSize` | `Int` | `65536` | per-thread scratch arena bytes |
| `ffiExtractDir` | `Option[File]` | `None` | override JAR-extraction temp directory |
| `ffiStrictBlocking` | `Boolean` | `false` | blocking-allowlist warning to error |
| `ffiStrictCallbacks` | `Boolean` | `false` | callback-retention warning to error |
| `ffiTargetPlatform` | `String` | auto-detected | manual override: `"JVM"` / `"Native"` / `"JS"` |
| `ffiStrictDiscovery` | `Boolean` | `false` | fail build when ffiGenerate discovers zero Ffi traits |
| `ffiSystemLibraries` | `Seq[String]` | common POSIX/Windows libs | library ids valid without declaration in ffiLibraries |

#### Tasks

| Task | Purpose |
|------|---------|
| `ffiGenerate` | TASTy to platform-specific impl source (auto-runs as `sourceGenerator`) |
| `ffiCompile` | C to shared library (JVM + JS only; Native routes through `nativeCompileOptions`) |
| `ffiPackage` | copy compiled libraries into `META-INF/native/<os>-<arch>/` |
| `ffiClean` | remove generated sources + compiled libs |
| `ffiCiWorkflow` | emit a starter `.github/workflows/ffi-native.yml` template |
| `ffiNpmBundleTemplate` | emit a `package.json` pinning `koffi` to `^2.7` (Scala.js) |
| `ffiDumpCcCommand` | return the cc command-line that ffiCompile would invoke |

Compiler family detection: `gcc`, `clang`, MSVC `cl.exe`, `zig cc`. Windows `cl.exe` flag translation is automatic.

### Beyond sbt

The code generator is a plain Scala function. `kyo-ffi-codegen` doesn't depend on sbt. Mill, scala-cli, and Bleep users invoke it directly:

```scala doctest:expect=skipped
import kyo.ffi.codegen.*

FfiGenerator.generate(
    tastyFiles = compile().tastyFiles,
    classpath = dependencyClasspath,
    outputDir = T.dest / "kyo-ffi",
    platform = FfiGenerator.Platform.JVM,
    config = FfiGenerator.Config.default.copy(libraryId = Some("kyo_tcp"))
)
```

C compilation and packaging are the user's responsibility outside sbt.

### Performance Tips

- Use `Buffer[T]` instead of `Array[T]` for `@Ffi.blocking` methods. Arrays are copied to scratch before blocking calls, while buffers are already off-heap.
- Transient callbacks with stable references (val, method reference) are cached, so the upcall stub is reused across calls. New lambda instances each time disable caching.
- The per-thread scratch arena auto-tunes: it starts at 64 KiB and doubles on demand up to 4 MiB. For workloads with known large allocations, set `-Dkyo.ffi.scratch.size=` or `Ffi.Config(scratchSize = Present(131072))` to avoid the initial growth.

### How It Works

Four stages:

**1. TASTy Inspection**
At `sbt compile`, the plugin reads the compiled TASTy of your binding trait. It extracts method signatures, parameter types, return types, and companion config. No macros or compiler plugins are involved. The codegen is a plain Scala library (`kyo-ffi-codegen`) that consumes TASTy via the standard `scala.tasty.inspector` API.

**2. Code Generation**
For each method, the codegen emits a platform-specific implementation class (`{Trait}Impl.scala`) under `target/src_managed/`. The generated code is plain Scala with platform-specific marshalling:

| Concept | JVM | Native | JS |
|---------|-----|--------|-----|
| Function call | `MethodHandle.invokeExact` | `@extern` | koffi `call` |
| Pointer | `MemorySegment` | `Ptr[Byte]` | koffi opaque handle |
| String param | scratch arena UTF-8 encode | `toCString` | JS string (koffi converts) |
| Struct | `MemoryLayout` + `ValueLayout` | `CStruct` | `koffi.struct` |
| Callback | Panama upcall stub | `CFuncPtr` | `koffi.register` |

The generated code is on disk, navigable in IDEs, and shows up in stack traces.

**3. C Compilation and Packaging**
The plugin compiles your C sources into a platform-native shared library (`.so`/`.dylib`/`.dll`) and packages it under `META-INF/native/{os}-{arch}/` inside the JAR. On Native, C sources route through `nativeCompileOptions` instead.

**4. Runtime Loading**
`Ffi.load[T]` extracts the shared library from the JAR to a temp directory, loads it via `System.loadLibrary` (JVM), linker (Native), or `koffi.load` (JS), and instantiates the generated impl via reflective construction. The impl is cached per trait. Subsequent `Ffi.load` calls return the same instance.

**Memory Model**
`Buffer[A]` and `Handle[A]` are backed by `UnsafeBuffer` from kyo-data, a sealed abstract class with platform subclasses (`JvmUnsafeBuffer` wrapping `MemorySegment`, `NativeUnsafeBuffer` wrapping `Ptr[Byte]`, `JsUnsafeBuffer` wrapping `DataView`). This foundation is shared with kyo-offheap's `Memory[A]`.

String parameters are encoded into a per-thread scratch arena (auto-growing from 64 KiB to 4 MiB) and freed when the call returns. Array parameters are zero-copy for non-blocking calls and copied to scratch for blocking calls.

### Examples and Integration Tests

Worked examples live under `kyo-ffi/plugin/src/sbt-test/kyo-ffi/`:

- `example-sqlite/`: in-memory SQLite: open, exec, read rows via callback.
- `example-openssl/`: initialize OpenSSL, allocate a context, generate random bytes.
- `example-sdl2/`: create a window, run an event loop with a Scala event handler.
- `callbacks-end-to-end/`: qsort with a Scala comparator (transient), epoll-style retained callback.
- `structs-end-to-end/`: nested + packed structs, struct-with-String return, multi-value return.
- `cross-project-end-to-end/`: same trait + C compiled and run on JVM + Native + JS in a single test.

`kyo-ffi-it` is a cross-built integration-test module that exercises the actual plugin against real system libraries (libc, libm, POSIX) and a bundled C surface. See [`it/README.md`](it/README.md) for layout and usage.

Benchmarks under `kyo-ffi/bench/`: `CallOverheadBench`, `ArrayPassBench`, `CallbackBench` (JMH).

## Complete Example

```scala doctest:expect=skipped
import kyo.*
import kyo.ffi.*

class MathContext
class MathModel

enum Precision(val value: Int):
    case Single extends Precision(0)
    case Double extends Precision(1)
    case Quad   extends Precision(2)
end Precision
object Precision:
    def fromInt(v: Int): Precision = Precision.values.find(_.value == v)
        .getOrElse(throw new IllegalArgumentException(s"Unknown Precision: $v"))

case class Vec2(x: Double, y: Double)
case class Stats(mean: Double, variance: Double)
case class Event(tag: Int, data: Int | Float)

trait MathBindings extends Ffi:
    // Simple call
    def mathAdd(a: Int, b: Int)(using AllowUnsafe): Int

    // Buffer parameter
    def mathDot(a: Buffer[Double], b: Buffer[Double], n: Int)(using AllowUnsafe): Double

    // Blocking: returns a Fiber.Unsafe the caller awaits with .safe.get
    @Ffi.blocking
    def mathSolveLarge(matrix: Buffer[Double], rows: Int, cols: Int)(using AllowUnsafe): Fiber.Unsafe[Int, Any]

    // Struct parameter and return
    def mathDistance(a: Vec2, b: Vec2)(using AllowUnsafe): Double
    def mathStats(data: Buffer[Double], n: Int)(using AllowUnsafe): Stats

    // Opaque handles
    def mathInit()(using AllowUnsafe): Handle[MathContext]
    def mathFree(ctx: Handle[MathContext])(using AllowUnsafe): Unit
    def mathLoadModel(path: String)(using AllowUnsafe): Handle[MathModel]
    def mathFind(name: String)(using AllowUnsafe): Maybe[Handle[MathModel]] // NULL returns Absent

    // Enum (structural detection: val value: Int + fromInt)
    def mathSetPrecision(ctx: Handle[MathContext], p: Precision)(using AllowUnsafe): Unit
    def mathGetPrecision(ctx: Handle[MathContext])(using AllowUnsafe): Precision

    // Union (OrType in TASTy)
    def mathProcess(n: Int | Float)(using AllowUnsafe): Unit

    // Borrowed returns
    def mathVersion()(using AllowUnsafe): Borrowed[String]
    def mathGetCoeffs(ctx: Handle[MathContext], n: Int)(using AllowUnsafe): Borrowed[Buffer[Double]]

    // Callbacks
    def mathSort(data: Buffer[Double], n: Int, cmp: (Double, Double) => Int)(using AllowUnsafe): Unit
    def mathOnProgress(ctx: Handle[MathContext], cb: Double => Unit, guard: Ffi.Guard)(using AllowUnsafe): Unit
end MathBindings

object MathBindings extends Ffi.Config(library = "math")
```

## See Also

- [`codegen/README.md`](codegen/README.md): code generator (build-tool-agnostic invocation).
- [`plugin/README.md`](plugin/README.md): sbt plugin (full settings reference).
- [`it/README.md`](it/README.md): cross-platform integration-test module.
