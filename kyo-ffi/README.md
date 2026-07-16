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

The API surface is the unsafe tier on purpose: a binding call goes straight to the native function with no per-call allocation and no effect wrapping, and the off-heap `Buffer` operations are equally direct. Every non-pure operation (the binding methods, every `Buffer` operation, and `Ffi.load`) takes a `(using AllowUnsafe)` proof; that proof is contextual evidence the compiler erases from the call site, so it costs nothing at runtime and keeps the hot path direct.

```scala doctest:scope=isolated
println(math.mathAdd(2, 3)) // 5
```

That one line is the whole model: you declared `mathAdd` as a Scala method, the build mapped it to the C symbol `math_add`, and the call went straight through. The rest of this document is the vocabulary for writing richer signatures (buffers, structs, handles, callbacks, errno) and the build knobs for shipping the C across platforms.

## Calling your first C function

A binding is a Scala trait extending `Ffi`. Each method names a C function; you declare the parameter and return types in plain Scala, and the codegen maps them to the C ABI. `Ffi` itself is a marker with no members: the trait you write is the surface, and `Ffi.load` plus the codegen are the only fixed touch-points.

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

For a cross-built project, enable the plugin on the cross-project and let it pick the backend per platform:

```scala doctest:expect=skipped
lazy val demo = crossProject(JSPlatform, JVMPlatform, NativePlatform)
    .in(file("demo"))
    .enablePlugins(KyoFfiPlugin)
    .settings(
        ffiLibraryId                        := "kyo_tcp",
        libraryDependencies += "io.getkyo" %%% "kyo-ffi" % kyoVersion
    )
```

Platform detection is automatic: a sub-project with `ScalaNativePlugin` enabled gets the Native backend, a Scala.js project gets the JS backend, everything else defaults to JVM. Override with `ffiTargetPlatform := "JVM" | "Native" | "JS"` when the detection cannot infer the right answer.

Drop a C file in `src/main/c/`:

```c
// src/main/c/math.c
int math_add(int a, int b) { return a + b; }
```

Declare the trait. It extends `Ffi`, plain Scala types, one method per C function. Every binding method performs a side effect (the native call), so it takes a trailing `(using AllowUnsafe)`. The same applies to allocating and accessing a `Buffer` and to `Ffi.load`: the entire non-pure surface is the unsafe tier (see "The unsafe tier and AllowUnsafe" below).

```scala doctest:expect=skipped
import kyo.*
import kyo.ffi.*

trait MathBindings extends Ffi:
    def mathAdd(a: Int, b: Int)(using AllowUnsafe): Int
```

Call it. The caller supplies the `AllowUnsafe` proof, typically by importing it inside an effect that has already suspended the side effect (see "The unsafe tier and AllowUnsafe"):

```scala doctest:scope=isolated
println(math.mathAdd(2, 3)) // 5
```

`sbt compile` compiles `math.c` into a shared library, reads the TASTy of `MathBindings`, generates `MathBindingsImpl.scala` under `target/.../src_managed/`, and packages the library under `META-INF/native/<os>-<arch>/`. At runtime, `Ffi.load` extracts the library, loads it, and instantiates the generated impl. The generated code is on disk and inspectable: IDE navigation, debugger breakpoints, and stack traces all work.

> **Caution:** the codegen caches each `{Trait}Impl.scala` on the source hash. After editing a binding trait or its `Ffi.Config`, run `ffiClean` (or `sbt clean`) before rebuilding, otherwise the build serves the previously generated impl and your edit appears to have no effect.

### Loading a binding at runtime

`Ffi.load[T]` instantiates the generated impl and caches it per trait: every later `Ffi.load[T]` for the same trait returns the same instance from a process-wide map. Loading native code is a side effect, so `Ffi.load` (like the binding calls and `Buffer` operations) takes `(using AllowUnsafe)`, supplied from a suspended context (see "The unsafe tier and AllowUnsafe").

```scala doctest:scope=isolated
val m = Ffi.load[MathBindings] // same instance as `math` in the setup
```

Two related entry points exist for the cache, both also `(using AllowUnsafe)`:

- `Ffi.warmLoad[T]` pre-warms the cache at startup so the first real call does not pay the reflection cost. It is idempotent.
- `Ffi.unload[T]` evicts the cached impl so the next `Ffi.load[T]` re-instantiates. It is intended for test scenarios, not normal use.

`Ffi.load[T]` throws subtypes of `FfiLoadError`: `LibraryNotFound` (native library not resolvable), `AbiMismatch` (generated-impl ABI vs runtime), `Unsupported` (32-bit host, browser Scala.js), `ImplNotFound` (no generated impl on the classpath). Callers that want a single catch handler use `catch { case e: FfiLoadError => ... }`.

> **Note:** on the JVM only, `Ffi.load` also throws `java.lang.IllegalStateException` when the generated impl class lacks a public nullary constructor (regenerate with `sbt clean compile`). This exception escapes the `FfiLoadError` catch surface, so a handler that must cover every `Ffi.load` failure catches both. The typed-failure bridge for both is shown in "Errors and errno".

## The unsafe tier and AllowUnsafe

kyo-ffi is the unsafe tier in full, not just the generated calls. Every non-pure user-facing operation takes a trailing `(using AllowUnsafe)` clause:

- the generated binding methods (each is a native call);
- every `Buffer` operation, allocating a mutable off-heap buffer is itself a side effect, as are `get` / `set` / `close` and the array and mmap bridges;
- `Ffi.load`, `Ffi.warmLoad`, and `Ffi.unload`, which load native code and mutate the process-wide impl cache.

The pure operations do not require it: `Buffer.size` / `byteSize` / `isClosed` are plain reads, and `StructLayout.derived` only computes a layout. `AllowUnsafe` is a compiler-enforced proof that the side effect has already been suspended at an outer scope; it carries no runtime cost. There is no safe-tier wrapper, because these operations are the boundary.

Callers supply the proof from inside a suspended context. The idiomatic path is `Sync.Unsafe.defer { ... }`, which suspends the side effect and provides `AllowUnsafe` for the block:

```scala doctest:scope=isolated
def add(a: Int, b: Int)(using Frame): Int < Sync =
    Sync.Unsafe.defer(math.mathAdd(a, b))
```

At application boundaries (a `KyoApp`, a test) you can instead import the proof directly with `import AllowUnsafe.embrace.danger`.

The `(using AllowUnsafe)` clause is contextual evidence: the codegen never marshals it to C, and it is excluded from the C call descriptor along with any other `using` / given parameters (such as `Frame`). The clause shapes the Scala type, not the C signature.

This is a deliberate performance choice. A binding call returns its value directly with no `Abort`/`Result` boxing and no effect-row wrapping on the call itself, and `Buffer` access reads and writes off-heap memory in place; the proof is erased from the call site, so the generated code is as direct as a hand-written downcall. You pay the suspension once, at the `Sync.Unsafe.defer` (or import) boundary, and every operation inside that boundary runs on the hot path.

## Naming the C symbols

You reach for this when a Scala method name does not match the C symbol you want to call. The default convention is camelCase to snake_case:

| Scala | C |
|-------|---|
| `mathAdd` | `math_add` |
| `mathDot` | `math_dot` |
| `mathSolveLarge` | `math_solve_large` |

When binding an existing library that does not follow this convention, declare an `Ffi.Config` companion with constructor parameters:

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

The companion is entirely optional. Absent config means default conventions. `symbolPrefix` is prepended to every derived C symbol; `symbols` overrides individual methods and takes precedence over the prefix rule.

## Calling code that blocks

Reach for `@Ffi.blocking` when a method's C implementation may block (I/O, locks, syscalls that can suspend). Omitting it is a promise to the runtime that the call is short, non-blocking, non-allocating, and invokes no callback. A `@Ffi.blocking` method does not return its value directly: it returns a `Fiber.Unsafe[A, Any]` that the caller awaits.

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

> **Unlike** a non-blocking binding (which returns its value directly), a `@Ffi.blocking` method must declare a `Fiber.Unsafe[A, Any]` return; the FFI inspector enforces this shape at build time.

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

> **Caution:** forgetting `@Ffi.blocking` on a method that actually blocks risks GC starvation (JVM), deadlock (Native), or a stalled event loop (JS). The plugin ships an allowlist of known-blocking POSIX symbols (`read`, `write`, `connect`, `accept`, `poll`, `epoll_wait`, ...) and warns when one is matched without the annotation. `ffiStrictBlocking := true` promotes the warning to an error.

## Passing functions to C

A function-type parameter becomes a C function pointer. The plugin distinguishes two patterns from the signature, and the distinction decides whether you need a lifetime guard.

When C calls the function back only during the call and forgets it afterward, the callback is transient and needs no guard. When C stores the pointer to call later, the callback is retained and needs an `Ffi.Guard` to keep it alive past the call. Use a transient callback for a comparator or visitor; use a retained callback for an event handler or signal handler that C holds onto.

### Transient callbacks

C invokes the callback during the call only. No guard needed:

```scala doctest:expect=skipped
trait MathBindings extends Ffi:
    // ... previous methods ...
    def mathSort(data: Buffer[Double], n: Int, cmp: (Double, Double) => Int)(using AllowUnsafe): Unit
```

The upcall stub lives only for the duration of the call.

### Retained callbacks

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

### Close semantics

`Ffi.Guard.use { g => ... }` always calls `close()` (the default-timeout form) and discards the outcome, so it is leak-proof but tells you nothing about how the close went. When you need to observe the drain, call `closeAwait(timeout)` instead: it returns an `Ffi.CloseOutcome`.

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

When you need C-owned memory to outlive a single call, the guard also registers buffers: `guard.registerBuffer[A]` ties a `Buffer[A]` lifetime to the guard so it is released on close alongside the callback stubs.

### Callback exceptions

Callbacks must not propagate exceptions into C. Doing so corrupts the C call stack. kyo-ffi enforces this on every platform: if the user callback throws, the runtime logs the exception to `System.err` (naming the binding, method, and callback kind) and returns a typed-zero default at the C boundary (`0` for numerics, `""` for `String`, no-op for `Unit`). The surrounding FFI call continues normally.

## Errors and errno

Reach for this when a C function reports failure through `errno` and you need to read it, or when `Ffi.load` itself can fail and you want the failure as a typed value in your effect row.

For methods that return a plain type, errno is not captured or checked; the return value is passed through directly. Use a plain return for calls where errno is not meaningful:

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

val risky = Ffi.load[RiskyBindings]
val r     = risky.riskyOp(42)
if r.errorCode != 0 then
    handleError(r.errorCode)
else
    useValue(r.value) // .value is Int for Outcome[Int], Long for Outcome[Long]
end if
```

`Outcome` captures the error code but never raises on its own. When a non-zero `errorCode` constitutes a failure, throw `FfiErrno`: its `apply(errorCode, binding, method)` factory builds a message naming the binding and method:

```scala doctest:scope=isolated
import kyo.*
import kyo.ffi.*

trait RiskyBindings extends Ffi:
    def riskyOp(x: Int)(using AllowUnsafe): Ffi.Outcome[Int]
object RiskyBindings extends Ffi.Config(library = "math")

val risky = Ffi.load[RiskyBindings]
val res   = risky.riskyOp(7)
if res.errorCode != 0 then
    throw FfiErrno(res.errorCode, "RiskyBindings", "riskyOp")
else
    useValue(res.value) // .value is Int for Outcome[Int], Long for Outcome[Long]
end if
```

A returned-struct reader can also fail when a `char*` field is not NUL-terminated within the bounded scan window (`-Dkyo.ffi.stringFieldMaxBytes`, default 64 KiB): it throws `FfiMalformedResult`, naming the binding, method, and field. This indicates the C library returned a pointer that is not terminated within the cap, or a pointer into freed memory.

### Bridging thrown failures into the effect row

`Ffi.load` and the `Buffer` accessors surface failure by throwing, to keep the hot path allocation-free. The typed-failure boundary is your call site: lift the throw into the effect row with `Abort.catching`.

```scala doctest:scope=isolated
def loadMath(using Frame): MathBindings < (Sync & Abort[FfiLoadError]) =
    Sync.Unsafe.defer(Abort.catching[FfiLoadError](Ffi.load[MathBindings]))
```

After this, a `LibraryNotFound` or any other `FfiLoadError` subtype is a typed `Abort` leaf in the row rather than an exception. On the JVM, the constructor-shape `java.lang.IllegalStateException` from `Ffi.load` is outside `FfiLoadError`, so a handler that must cover it catches `java.lang.IllegalStateException` as well.

## The type system that replaces raw pointers

The trait method signatures use standard Scala types. You write the types, the codegen maps the ABI: every section below is one Scala-to-C mapping the generator recognizes structurally, with no annotation unless noted.

### Type mapping

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

Every `Buffer` operation that allocates, reads, writes, or releases off-heap memory (every constructor in the table below plus `get` / `set` / `close`) takes `(using AllowUnsafe)`, because off-heap access is an unsafe side effect. Supply the proof the same way as for a binding call, from inside `Sync.Unsafe.defer` or via the boundary import; the examples below rely on that proof being in scope. The pure accessors (`size`, `byteSize`, `isClosed`) do not require it.

```scala doctest:expect=skipped
import kyo.*
import kyo.ffi.*

trait MathBindings extends Ffi:
    def mathAdd(a: Int, b: Int)(using AllowUnsafe): Int
    def mathDot(a: Buffer[Double], b: Buffer[Double], n: Int)(using AllowUnsafe): Double
```

The primary lifetime pattern is `Buffer.use`:

```scala doctest:scope=isolated
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

A buffer instance exposes the pure accessors `size` (element count), `byteSize` (`size * sizeof(A)`), and `isClosed`, plus the unsafe operations `get(i)`, `set(i, v)`, and `close()` (the latter three take `(using AllowUnsafe)`). `get`/`set` dispatch through the `UnsafeLayout[A]` typeclass, which boxes every primitive (JVM erasure); a hot read/compare loop that already knows its element type can bypass that dispatch with the non-generic accessor pairs `getLong`/`setLong`, `getInt`/`setInt`, `getShort`/`setShort`, `getDouble`/`setDouble`, `getFloat`/`setFloat`, and `getByte`/`setByte`, each gated by a compiler-checked `A =:= T` evidence parameter so it can never misread a differently-typed buffer's bytes. Backed by `java.lang.foreign.Arena` (JVM), `stdlib.malloc/free` (Native), and a `Uint8Array` + `DataView` pair (JS). Thread-safe by default.

> **Caution:** `confinedUse` and `allocConfined` are single-thread only. On JVM they use `Arena.ofConfined`, so a cross-thread `get` / `set` throws; do not pass a confined buffer across threads or suspend a fiber inside a `confinedUse` block. On Native and JS they behave identically to `use` / `alloc`.

For advanced foreign-memory interop, `Buffer.Unsafe.wrapBorrowed[A](raw, size)` wraps a raw C handle as a borrowed `Buffer[A]` whose `close()` is a no-op; `Buffer.Unsafe.wrapBorrowedChecked[A](raw, size, owner)` does the same but validates `owner.isValid` on every `get` / `set` and throws `BorrowRevoked` if revoked. Both bridge a pointer you already hold from outside the generated code; the generated code never needs them.

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

#### Packed structs

Some C wire protocols use `#pragma pack` (no padding between fields). Opt in via the companion config:

```scala doctest:scope=isolated
import kyo.ffi.*

trait WireBindings  extends Ffi
object WireBindings extends Ffi.Config(packedStructs = Set("PackedVec2"))
```

#### Buffers of structs

When a binding takes a `Buffer[StructType]` (for example `epoll_wait` filling an array of `struct epoll_event`, or `kevent` reading a changelist), the codegen marshals the buffer as a raw pointer, but your code still allocates the buffer and reads or writes its elements field by field. The primitive layout givens cannot cover a case class, so derive one with `StructLayout.derived[A]` and bring it into scope as a `given`:

```scala doctest:scope=isolated
import kyo.internal.UnsafeLayout

given UnsafeLayout[Stats] = StructLayout.derived[Stats]
Buffer.use[Stats, Unit](4) { buf =>
    buf.set(0, Stats(1.5, 0.25))
    val first = buf.get(0)
    useValue(first.mean.toInt)
}
```

Use `StructLayout.derived[A]` for a naturally-aligned struct and `StructLayout.derivedPacked[A]` for one listed in the binding's `Ffi.Config.packedStructs`; the choice must match the struct's packed membership so the in-buffer layout agrees with the C ABI. Supported field types are the flat primitives (`Byte`, `Short`, `Int`, `Long`, `Float`, `Double`, `Boolean`); nested struct, `String`, `Buffer`, and `Handle` fields are rejected at compile time.

#### Multi-value returns

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

#### By-value struct returns

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

> **Unlike** a multi-value return (which requires at least two fields), a by-value struct return may have a single field. The annotation only selects the return ABI: `@Ffi.byValue` for the `void f(S* out, ...)` form, no annotation for the default multi-value (C return plus out-params) form.

### Handles and null safety

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

`MathContext` and `MathModel` are marker types. Any class works as a marker. `Handle[MathContext]` and `Handle[MathModel]` are distinct types; passing one where the other is expected is a compile error. At runtime, both are just pointer-sized values with zero wrapping overhead. Handle values can only be created by the generated FFI code, not by user code directly.

C functions that return pointers may return NULL. kyo-ffi encodes null safety in the return type. A bare `Handle[A]` return enforces a non-null contract: if C returns NULL, the runtime throws `FfiNullPointer`. There is no `isNull` method.

```scala doctest:expect=skipped
import kyo.*
import kyo.ffi.*

class MathContext

trait MathBindings extends Ffi:
    def mathInit()(using AllowUnsafe): Handle[MathContext]
    def mathSetSeed(ctx: Handle[MathContext], seed: Long)(using AllowUnsafe): Unit
object MathBindings extends Ffi.Config(library = "math")

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

val math = Ffi.load[MathBindings]
math.mathFind("weights") match
    case Present(model) => math.mathLoadModel(model)
    case Absent         => println("model not found")
```

The codegen enforces this at the call boundary. The type system prevents null handles from reaching user code.

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

At the FFI boundary, the generator converts enum cases to `Int` (via `.value`) for parameters and from `Int` (via `fromInt`) for returns:

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

If an enum is used in a binding method but does not match the pattern (missing `value` field or `fromInt` method), the build fails with a clear error.

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

### Borrowed returns

Reach for `Borrowed[A]` when C retains ownership of a returned `String` or `Buffer[A]` and you must not free it. Top-level `String` and `Buffer[A]` returns are supported only through `Borrowed`; declare the intent with `Borrowed[T]` as the return type:

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

Checked-borrow mode provides use-after-free diagnostics. Enable process-wide with `-Dkyo.ffi.checkedBorrows=true` or per-binding via `Ffi.Config.checkedBorrows`. When enabled, every borrowed `Buffer` is attached to a `BorrowOwner`; each `get` / `set` verifies the owner is still valid, throwing `BorrowRevoked` if revoked.

## Variadic C functions

Variadic C functions like `printf` and `snprintf` are bound by declaring the last Scala parameter as `args: Any*`:

```scala doctest:scope=isolated
import kyo.*
import kyo.ffi.*

trait PrintBindings extends Ffi:
    def snprintf(buf: Buffer[Byte], size: Long, fmt: String, args: Any*)(using AllowUnsafe): Int
```

Supported vararg runtime types: `Int`, `Long`, `Double`, `String`, `Buffer[A]`. Anything else raises `FfiLoadError.Unsupported` at the call site.

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

### Binding a variadic C function non-variadically

`args: Any*` is the only way to feed extra arguments through the variadic calling convention. A binding declared without it is generated as a fixed-arity call descriptor.

> **Caution:** binding a genuinely variadic C function (one declared with `...`, such as `int fcntl(int, int, ...)`) as a fixed-arity method silently misbehaves on arm64. The AArch64 calling convention (AAPCS64) routes variadic arguments through a different path than fixed arguments, so a fixed-arity descriptor passes the trailing argument in the wrong place: the call returns success but the variadic argument is dropped.

There is no annotation to mark a single trailing fixed argument as variadic (a `@Ffi.variadic` declaration does not exist). The correct workaround is to wrap the variadic call in a small non-variadic C shim that takes exactly the arguments you need, and bind the shim:

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

## Configuring a binding

Configuration is optional. Defaults work for most projects; reach for these knobs to override a symbol convention, tune the scratch allocator, or gate platform-specific headers.

### Ffi.Config

`Ffi.Config` constructor parameters, all optional with defaults:

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

> **Caution:** every `Ffi.Config` field must be a compile-time literal. The codegen reads them structurally from the companion's TASTy at build time, so a runtime-computed value (a `val` read from the environment, a constructor argument) produces a codegen failure, not a runtime error. Supply `Present(131072)`, `Chunk("sys/epoll.h")`, `Set("PackedVec2")` and the like as literals.

For programmatic construction in tests or tooling, `Ffi.Config.builder` builds a `Config` through per-field setters and a final `build`:

```scala doctest:scope=isolated
import kyo.ffi.*
val cfg = Ffi.Config.builder.library("kyo_tcp").scratchSize(1024).build
```

> **Note:** a `Config` built this way cannot drive codegen, because the codegen reads the companion's TASTy, not a runtime object. Use the `extends Ffi.Config(...)` companion form for a real binding; the builder is for tests and tooling that inspect or assemble a `Config` value directly.

### Runtime system properties

| Property | Default | Purpose |
|----------|---------|---------|
| `kyo.ffi.scratch.size` | `65536` | per-thread scratch allocator block size (bytes) |
| `kyo.ffi.scratch.maxSize` | `4194304` | maximum scratch size after auto-growth (4 MiB) |
| `kyo.ffi.scratch.logSpills` | `false` | log to stderr when scratch allocations spill |
| `kyo.ffi.stringFieldMaxBytes` | `65536` | upper bound for NUL scan on borrowed C string fields |
| `kyo.ffi.checkedBorrows` | `false` | enable BorrowOwner validation process-wide |
| `kyo.ffi.guard.drainTimeoutMs` | `5000` | drain timeout (ms) when closing a guard with in-flight callbacks |
| `kyo.ffi.native.retainedCallbackPoolSize` | `1024` | retained callback slots per shape (Native only) |
| `kyo.ffi.native.retainedCallbackPoolWarnPercent` | `75` | utilization percent at which a high-watermark warning is logged to stderr (Native only) |
| `kyo.ffi.native.retainedCallbackPoolBackpressure` | `false` | when `true`, a claim on a full pool waits for a free slot instead of throwing (Native only) |
| `kyo.ffi.native.retainedCallbackPoolBackpressureTimeoutMs` | `5000` | how long a backpressured claim waits before timing out (Native only) |
| `kyo.ffi.native.leakSweepIntervalMs` | `1000` | leak detector sweep interval (Native only) |

The runtime library load path is overridable per library with `-Dkyo.ffi.<library_id>.path=/abs/path/to/lib` (see "Shipping native code across platforms" for the full extraction-and-load chain).

### Header gating

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

> **Note:** on Scala Native, `headers` and `nativeBundled` jointly decide link behavior. `headers` drives the `cc -E` probe (a missing header emits stubs instead of `@extern`), while `nativeBundled = true` suppresses `@link` so the C is compiled into the binary rather than linked with `-l<library>`. Set them together: one without the other produces unexpected stubs or an unresolvable `-l`.

## Shipping native code across platforms

The same Scala source compiles on JVM, Scala Native, and Scala.js (Node). The plugin auto-detects the target platform from the surrounding cross-project setup. This section covers declaring more than one library, static-linking vendored archives, the per-platform differences, JS setup, and the security-sensitive load knobs.

### Multiple libraries

Reach for `ffiLibraries` when one module hosts bindings to more than one shared library; use the single `ffiLibraryId` when there is exactly one. The two modes are mutually exclusive.

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

If a binding's `Ffi.Config(library = "...")` id is not declared in `ffiLibraries` / `ffiLibraryId` and is not a system library, `ffiGenerate` fails the build and names the exact fix (add an `FfiLibrary(id, ...)`, set `ffiLibraryId`, or add the id to `ffiSystemLibraries`). The Scala-side `library` id and the sbt-side declaration are two halves of one contract.

`FfiLibrary` is re-exported from the plugin's `autoImport`, so `build.sbt` needs no extra import. Its fields:

| Field | Purpose |
|-------|---------|
| `id` | the library identifier matched against each binding's `library` |
| `cSources` | C sources compiled into this library |
| `cHeaders` | C headers; their parents feed `-I` |
| `includeDirs` | extra `-I` directories (vendored headers) |
| `libDirs` | `-L` directories holding vendored archives |
| `linkLibs` | `-l<name>` link libraries |
| `linkLibsByOs` | per-OS link additions, keyed by `<os>-<arch>` (e.g. `linux-aarch64`) |
| `cFlags` | extra C compiler flags for this library |
| `linkFlags` | extra linker flags for this library |
| `staticLink` | statically link this library's `linkLibs` |
| `dependsOn` | other library ids this one must build after (build-order topological sort) |

`FfiLibrary.resolvedLinkLibs(os)` returns the combined `linkLibs` plus the matching `linkLibsByOs` entries for the current OS.

### Static linking vendored archives

`FfiLibrary.linkLibs` folds vendored static archives (`lib<name>.a` under `libDirs`) into the shim. Static-linking a vendored archive is a three-field interaction: `libDirs` points `-L` at the archives, `linkLibs` names them, and `linkLibsByOs` splits the per-OS set. Two things commonly trip up a first integration:

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

> **Caution:** on Scala Native the plugin computes the link options but does not wire them into the build. A consumer using `ffiLibraries` with `linkLibs` / `libDirs` / `staticLink` must add `nativeConfig.linkingOptions ++= ffiNativeLinkingOptions.value` themselves, or the static link fails with undefined-reference errors. On JVM and JS the plugin links the shim directly and no manual wiring is needed.

### Cross-platform differences

Behavior is uniform for: trait API, `Buffer` lifetime semantics, `Ffi.Guard` registration, errno capture, struct layout, and multi-value returns. The differences:

| Aspect | JVM | Native | JS |
|--------|-----|--------|-----|
| Guard leak warnings | `Cleaner` logs to stderr | silent | logs to stderr |
| Critical downcalls | JDK 22+ (`Linker.Option.critical`) | N/A | N/A |
| Variadic functions | supported | rejected at build time | supported |
| Borrowed String returns | supported | supported | supported |

(The `@Ffi.blocking` mechanism also differs per platform; see "Calling code that blocks" for that table.)

**64-bit hosts only.** 32-bit hosts are rejected at runtime on the first `Ffi.load` call; this is a runtime check, not a build-time one. Scala.js in a browser is unsupported (`Unsupported` at load); kyo-ffi targets Node. Wasm is not a supported target.

Library resolution differs by platform in ways worth knowing when binding system libraries:

- **JVM:** a `library = "c"` resolves through the JVM Foreign Linker (`SymbolLookup.libraryLookup` then `dlopen(3)`), which works on macOS and Linux out of the box.
- **Native:** Scala Native auto-links libc for any `@extern` declaration; the emitted `@link("c")` folds into the default libc link with no warning.
- **JS:** koffi loads shared libraries by absolute path, so a bare `"c"` fails on macOS (libc is folded into `libSystem`). Resolve it by priming `process.env.KYO_FFI_C_PATH` (and `KYO_FFI_M_PATH` for libm) with the absolute path before the first `Ffi.load` (`/usr/lib/libSystem.B.dylib` on darwin, `libc.so.6` on linux); the loader consults these env vars before npm-package resolution.
- **Windows:** POSIX bindings (`getpid`, `getenv`, `time`) are unavailable on native Windows without platform-specific shims.

### JS / koffi setup

On JS (Node), kyo-ffi performs the native call through the [koffi](https://koffi.dev) npm package. koffi is a prebuilt native addon: it is loaded with `require('koffi')` at runtime and cannot be inlined into the emitted `.js`. A JS consumer must therefore have `koffi` resolvable in their `node_modules`. The required version is `^2.7` (`2.7.0 <= koffi < 3.0.0`), which matches the range the runtime checks against. Pin it in your `package.json`:

```json
{ "dependencies": { "koffi": "^2.7" } }
```

koffi ships prebuilt binaries for common platforms, so installing it needs no native toolchain (no C compiler, no node-gyp build step). kyo's own test build installs it automatically: a `Test / compile` hook writes a `package.json` pinning `koffi` to `^2.7` and runs `npm install` into the test target directory before the JS tests run.

> **Caution:** the Scala.js linker must emit a CommonJS module (`ModuleKind.CommonJSModule`) so Node's `require('koffi')` resolves at runtime. A JS consumer that leaves the linker at the default module kind gets a runtime failure when the first `Ffi.load` tries to load koffi.

The first `Ffi.load` call on Scala.js runs an ABI probe that verifies `koffi.version` satisfies `^2.7` and that all required methods are exported. A failed probe throws `FfiLoadError.Unsupported`. The probe runs once per Node session.

### Security

Two operator-controlled knobs can load native code. Never set them from untrusted input:

- `-Dkyo.ffi.<libraryId>.path=/abs/path` overrides the bundled library lookup and loads the path directly. An attacker who controls this property can load an arbitrary shared object.
- `-Dkyo.ffi.extractDir=/abs/path` (JVM only) changes where bundled libraries are unpacked before load. A writable directory under attacker control permits a swap-on-load attack.

These names are one override chain, highest priority first: the runtime resolves the extraction directory as `kyo.ffi.extractDir`, then `kyo.ffi.tmpdir` (which the `ffiExtractDir` sbt setting emits), then the Java temp dir. Set the chain deliberately; a half-set chain extracts to an unexpected directory.

## Build reference

The teaching sections above cover when to reach for each knob. This is the lookup zone: the full plugin settings and tasks, compiler detection, and the non-sbt code-generator entry point.

### Plugin settings

| Setting | Type | Default | Purpose |
|---------|------|---------|---------|
| `ffiLibraryId` | `String` | `"kyo_ffi"` | single-library identifier |
| `ffiLibraries` | `Seq[FfiLibrary]` | `Nil` | multi-library mode (takes precedence over `ffiLibraryId` / `ffiCSources` / `ffiCHeaders` / `ffiLinkLibs` / `ffiStaticLink`) |
| `ffiCSources` | `Seq[File]` | `src/main/c/**/*.c` when the dir exists, else `Nil` | C source files |
| `ffiCHeaders` | `Seq[File]` | `src/main/c/**/*.h` when the dir exists, else `Nil` | C header files; parents are added to `-I`; tracked as rebuild triggers |
| `ffiIncludes` | `Seq[File]` | `src/main/c/` when the dir exists, else `Nil` | extra `-I` include directories (appended after those derived from `ffiCHeaders`) |
| `ffiCFlags` | `Seq[String]` | `[-O2, -fPIC, -Wall]` | extra C compiler flags |
| `ffiLinkLibs` | `Seq[String]` | `Nil` | `-l<name>` link libraries |
| `ffiLinkFlags` | `Seq[String]` | `Nil` | extra linker flags |
| `ffiCCompiler` | `String` | `$CC`, else `cc` | C compiler binary |
| `ffiStaticLink` | `Boolean` | `false` | append `-static` family of flags |
| `ffiScratchSize` | `Int` | `65536` | per-thread scratch arena bytes (surfaced via `-Dkyo.ffi.scratch.size`) |
| `ffiExtractDir` | `Option[File]` | `None` | override JAR-extraction temp directory (surfaced via `-Dkyo.ffi.tmpdir`) |
| `ffiStrictBlocking` | `Boolean` | `false` | promote blocking-allowlist warning to error |
| `ffiStrictCallbacks` | `Boolean` | `false` | promote callback-retention warning to error |
| `ffiTargetPlatform` | `String` | auto-detected | manual override: `"JVM"` / `"Native"` / `"JS"` |
| `ffiStrictDiscovery` | `Boolean` | `false` | fail build when `ffiGenerate` discovers zero `Ffi` traits |
| `ffiSystemLibraries` | `Seq[String]` | common POSIX/Windows libs | library ids valid without declaration in `ffiLibraries` |

> **Caution:** `ffiCFlags` replaces the default list when assigned with `:=`. Assigning `ffiCFlags := Seq("-DFOO")` drops `-O2 -fPIC -Wall`; use `ffiCFlags += "-DFOO"` to keep the defaults and append.

`ffiCHeaders` is rarely set directly: the parent directory of each listed header is added to the C compiler's `-I` flags, and the default auto-detect under `src/main/c/` usually covers it.

### Plugin tasks

| Task | Purpose |
|------|---------|
| `ffiGenerate` | TASTy to platform-specific impl source (auto-runs as a `sourceGenerator`; trait changes re-run it, C changes do not) |
| `ffiCompile` | C to shared library (JVM + JS only; on Native the C routes through `nativeCompileOptions`) |
| `ffiPackage` | copy compiled libraries into `META-INF/native/<os>-<arch>/` (auto-runs as a `resourceGenerator`) |
| `ffiClean` | remove generated sources + compiled libs |
| `ffiCiWorkflow` | emit a starter `.github/workflows/ffi-native.yml` template (five-row OS/arch matrix) |
| `ffiNpmBundleTemplate` | emit a `package.json` pinning `koffi` to `^2.7` (Scala.js) |
| `ffiDumpCcCommand` | return the `cc` command-line that `ffiCompile` would invoke (diagnostic; does not run the compiler) |
| `ffiNativeLinkingOptions` | compute the Native linking options to wire into `nativeConfig.linkingOptions` |

### Compiler detection

Compiler family detection covers `gcc`, `clang`, MSVC `cl.exe`, and `zig cc`. Windows `cl.exe` flag translation is automatic.

### Beyond sbt

The code generator is a plain Scala function in `kyo-ffi-codegen`; it does not depend on sbt. Mill, scala-cli, and Bleep users call it directly. The entry point:

```scala doctest:expect=skipped
import kyo.ffi.codegen.FfiGenerator

val result = FfiGenerator.generate(
    tastyFiles = Seq("/path/to/TcpBindings.tasty"),
    classpath = Seq("/path/to/kyo-ffi_3.jar", "/path/to/kyo-data_3.jar"),
    outputDir = java.nio.file.Path.of("out/src_managed"),
    platform = FfiGenerator.Platform.JVM,
    config = FfiGenerator.Config.default
)

val files    = result.files    // Seq[Path], emitted source files
val warnings = result.warnings // Seq[String], blocking/callback allowlist misses under non-strict config
val traits   = result.traits   // Seq[TraitSpec], the extracted model (useful for tooling)
```

The function is idempotent: re-running against the same TASTy produces byte-stable output. `FfiGenerator.Platform` is the `JVM` / `Native` / `JS` enum; strictness and library selection live on `Config`:

```scala doctest:expect=skipped
import kyo.ffi.codegen.FfiGenerator

FfiGenerator.Config(
    libraryId = Some("kyo_tcp"),
    extraLibraries = Nil,    // Seq[FfiGenerator.LibraryConfig] for multi-library codegen
    strictBlocking = false,  // true: allowlist misses throw instead of warn
    strictCallbacks = false, // true: retention allowlist misses throw
    includeDirs = Nil        // Seq[String], -I dirs for the Native header probe
)
```

`FfiGenerator.LibraryConfig(id, cSources, linkLibs)` is the per-library entry for `extraLibraries` in multi-library codegen, mirroring the sbt `FfiLibrary`. C compilation and packaging are the caller's responsibility outside sbt.

**Mill:**

```scala noformat doctest:expect=skipped
import mill._, scalalib._
import $ivy.`io.getkyo::kyo-ffi-codegen::<version>`
import kyo.ffi.codegen.FfiGenerator

object demo extends ScalaModule {
    def scalaVersion = "3.3.4"
    def ivyDeps      = Agg(ivy"io.getkyo::kyo-ffi::<version>")

    def ffiGen = T {
        val tastys = os.walk(compile().classes.path).filter(_.ext == "tasty").map(_.toString)
        val cp     = compileClasspath().map(_.path.toString)
        val out    = T.dest.toNIO
        FfiGenerator.generate(tastys, cp, out, FfiGenerator.Platform.JVM).files.map(os.Path(_))
        PathRef(T.dest)
    }

    override def generatedSources = T { super.generatedSources() :+ ffiGen() }
}
```

**scala-cli:** scala-cli has no native code-generation hook, so wire a small driver script that calls `FfiGenerator.generate` and writes to `src_managed/`, then run it before the build:

```scala noformat doctest:expect=skipped
//> using scala 3.3.4
//> using dep io.getkyo::kyo-ffi::<version>
//> using dep io.getkyo::kyo-ffi-codegen::<version>
//> using resourceDir ./resources
//> using buildInfo
```

**Bleep:** Bleep's `@plugin` mechanism accepts any plain Scala entry point. Add `kyo-ffi-codegen` as a module dependency and call `FfiGenerator.generate` from a build script; see the [Bleep user guide](https://bleep.build/docs/) for the current plugin wiring syntax.

## How it works

The pipeline is four stages.

**1. TASTy inspection.** At `sbt compile`, the plugin reads the compiled TASTy of your binding trait. It extracts method signatures, parameter types, return types, and companion config. No macros or compiler plugins are involved; the codegen is a plain Scala library (`kyo-ffi-codegen`) that consumes TASTy via the standard `scala.tasty.inspector` API.

**2. Code generation.** For each method, the codegen emits a platform-specific implementation class (`{Trait}Impl.scala`) under `target/src_managed/`. The generated code is plain Scala with platform-specific marshalling:

| Concept | JVM | Native | JS |
|---------|-----|--------|-----|
| Function call | `MethodHandle.invokeExact` | `@extern` | koffi `call` |
| Pointer | `MemorySegment` | `Ptr[Byte]` | koffi opaque handle |
| String param | scratch arena UTF-8 encode | `toCString` | JS string (koffi converts) |
| Struct | `MemoryLayout` + `ValueLayout` | `CStruct` | `koffi.struct` |
| Callback | Panama upcall stub | `CFuncPtr` | `koffi.register` |

The generated code is on disk, navigable in IDEs, and shows up in stack traces.

**3. C compilation and packaging.** The plugin compiles your C sources into a platform-native shared library (`.so` / `.dylib` / `.dll`) and packages it under `META-INF/native/{os}-{arch}/` inside the JAR. On Native, C sources route through `nativeCompileOptions` instead.

**4. Runtime loading.** `Ffi.load[T]` extracts the shared library from the JAR to a temp directory, loads it via `System.loadLibrary` (JVM), linker (Native), or `koffi.load` (JS), and instantiates the generated impl via reflective construction. The impl is cached per trait; subsequent `Ffi.load` calls return the same instance.

**Plugin architecture.** The sbt plugin is a Scala 2.12 plugin (sbt's own compile target). The Scala 3 codegen is bundled as opaque resources inside the plugin JAR; at task-execution time the plugin extracts the bundled JARs to a cache directory and constructs a fresh classloader to invoke the codegen reflectively. There is no user-visible Scala 3 dependency beyond what `kyo-ffi` itself pulls in.

**Memory model.** `Buffer[A]` and `Handle[A]` are backed by `UnsafeBuffer` from kyo-data, an abstract class with platform subclasses: `JvmUnsafeBuffer` wrapping `MemorySegment`, `NativeUnsafeBuffer` wrapping `Ptr[Byte]`, and `JsUnsafeBuffer` wrapping a `Uint8Array` + `DataView` pair. This foundation is shared with kyo-offheap's `Memory[A]`. String parameters are encoded into a per-thread scratch arena (auto-growing from 64 KiB to 4 MiB) and freed when the call returns; array parameters are zero-copy for non-blocking calls and copied to scratch for blocking calls.

**Error types at a glance.** `FfiLoadError` (and its `LibraryNotFound` / `AbiMismatch` / `Unsupported` / `ImplNotFound` leaves) is the catch surface for `Ffi.load`; `FfiNullPointer` is thrown when a bare `Handle[A]` receives NULL; `FfiErrno` is the errno exception you throw after inspecting `Outcome.errorCode`; `FfiMalformedResult` is thrown by returned-struct readers on an unterminated `char*`; `BorrowRevoked` is thrown under checked-borrow mode; `FfiLoadError.Unsupported` is raised on an unsupported variadic runtime type. `FfiInternalError` is a should-not-happen internal-invariant diagnostic (a checked cast site); it names the binding and method but prompts no user action.

### Performance tips

- Use `Buffer[T]` instead of `Array[T]` for `@Ffi.blocking` methods. Arrays are copied to scratch before blocking calls, while buffers are already off-heap.
- Transient callbacks with stable references (a `val`, a method reference) are cached, so the upcall stub is reused across calls. New lambda instances each time disable caching.
- The per-thread scratch arena auto-tunes: it starts at 64 KiB and doubles on demand up to 4 MiB. For workloads with known large allocations, set `-Dkyo.ffi.scratch.size=` or `Ffi.Config(scratchSize = Present(131072))` to avoid the initial growth.
- `Ffi.warmLoad[T]` at startup amortizes the first-call reflection cost.

## Examples

Worked examples live under [`plugin/src/sbt-test/kyo-ffi/`](plugin/src/sbt-test/kyo-ffi/), run via `sbt kyo-ffi-plugin/scripted`:

- [`example-sqlite/`](plugin/src/sbt-test/kyo-ffi/example-sqlite/): in-memory SQLite: open, exec, read rows via callback.
- [`example-openssl/`](plugin/src/sbt-test/kyo-ffi/example-openssl/): initialize OpenSSL, allocate a context, generate random bytes.
- [`example-sdl2/`](plugin/src/sbt-test/kyo-ffi/example-sdl2/): create a window, run an event loop with a Scala event handler.
- [`callbacks-end-to-end/`](plugin/src/sbt-test/kyo-ffi/callbacks-end-to-end/): qsort with a Scala comparator (transient), epoll-style retained callback.
- [`structs-end-to-end/`](plugin/src/sbt-test/kyo-ffi/structs-end-to-end/): nested + packed structs, struct-with-String return, multi-value return.
- [`cross-project-end-to-end/`](plugin/src/sbt-test/kyo-ffi/cross-project-end-to-end/): same trait + C compiled and run on JVM + Native + JS in a single test.

`kyo-ffi-it` is a cross-built integration-test module that exercises the actual plugin against real system libraries (libc, libm, POSIX) and a bundled C surface. See [`it/README.md`](it/README.md) for its layout, the per-platform run commands, and the scripted-vs-IT coverage matrix.

JMH benchmarks live under [`bench/`](bench/) (call overhead, array passing, callbacks, multi-threaded calls, a Panama baseline, warm-load, and specialized-callback shapes).

## See Also

- [`it/README.md`](it/README.md): cross-platform integration-test module (layout, run commands, scripted-vs-IT coverage matrix).
- [`CONTRIBUTING.md`](CONTRIBUTING.md): contributor guide (the throwing error model rationale, the sanctioned thread-blocking substrate, the cross-platform layout rules).
