package kyo.ffi

import kyo.AllowUnsafe
import kyo.Chunk
import kyo.Maybe
import kyo.Maybe.Absent
import kyo.Maybe.Present
import kyo.discard
import scala.annotation.StaticAnnotation
import scala.compiletime.erasedValue
import scala.concurrent.duration.FiniteDuration

/** Marker trait. FFI binding traits extend this.
  *
  * The build-time generator discovers all direct and indirect subtypes of [[Ffi]] in the current module's classpath and emits
  * platform-specific implementations ({TraitName}Impl) for each under `src_managed/`. Runtime code obtains an instance via [[Ffi.load]],
  * which reflectively loads the generated impl class.
  *
  * This trait has no members, it is a marker only. All per-method and per-trait configuration is derived from the binding trait's
  * signatures plus the optional companion [[Ffi.Config]].
  */
trait Ffi

/** Companion to [[Ffi]]. Contains FFI type wrappers ([[Handle]], [[Borrowed]], [[Outcome]]), the `@Ffi.blocking` annotation, the optional
  * per-trait [[Config]] base class, the callback [[Guard]], and the runtime entry points [[load]].
  */
object Ffi:

    /** Phantom-typed opaque pointer handle.
      *
      * Users declare a plain marker class (`class LlamaModel`) and use `Handle[LlamaModel]` in binding signatures. The code generator
      * discovers `Handle[A]` references and emits platform-specific marshalling (JVM: `MemorySegment` / `ADDRESS`, Native: `Ptr[Byte]`, JS:
      * koffi `"pointer"`).
      *
      * On each platform the concrete carrier differs:
      *   - JVM: wraps a `MemorySegment` (address)
      *   - Native: wraps a boxed `Ptr[Byte]` (via `NativePtr`)
      *   - JS: wraps a koffi opaque pointer handle (`js.Any`)
      *
      * The type is defined as `AnyRef` in the shared API, the same pattern used by [[Buffer.Raw]].
      */
    opaque type Handle[A] = AnyRef

    object Handle:
        private[kyo] inline def wrap[A](v: AnyRef): Handle[A]   = v
        private[kyo] inline def unwrap[A](h: Handle[A]): AnyRef = h
    end Handle

    /** Method-level annotation. Marks a method whose C implementation may block: I/O, locks, waits, or syscalls that can suspend.
      *
      * The FFI binding layer is the unsafe tier, so a `@blocking` binding method is generated to declare a trailing `(using AllowUnsafe)`
      * clause and to return `kyo.Fiber.Unsafe[A, Any]` rather than a bare value: the blocking downcall is surfaced as a fiber.
      *
      *   - On JVM and Native the blocking downcall runs synchronously on (and parks) the carrier thread; the generated body wraps it in
      *     `kyo.ffi.internal.BlockingBridge.run`, which completes a `Promise.Unsafe` with the result and returns it as a `Fiber.Unsafe`.
      *     The safe, non-critical downcall on JVM and the `@blocking @extern` downcall on Native let the GC and the scheduler's blocking
      *     monitor recognise the parked carrier, so a genuinely-blocking call does not permanently starve the scheduler.
      *   - On JS the call runs via koffi's asynchronous dispatch on a libuv worker thread (the event loop is not blocked) through
      *     `kyo.ffi.internal.BlockingBridge.runAsync`, which resolves the returned `Fiber.Unsafe` from the completion callback; errno is
      *     captured inside that callback.
      *
      * The consumer bridges the returned `Fiber.Unsafe` into a `< Async` computation with `.safe.get`. The call is not cancellable
      * mid-flight: once the C call has started it runs to completion. Any argument buffers passed to the call must outlive it (the
      * marshalled arguments are pinned for the call's duration but not beyond).
      *
      * Omitting the annotation is a promise that the call is short, non-blocking, non-allocating, and does not call back into Scala; such
      * methods are generated as plain values (still under the binding tier's `(using AllowUnsafe)`).
      */
    final class blocking extends StaticAnnotation

    /** Method-level annotation. Marks a binding method that returns a struct by value at the Scala level, mapped to the C
      * out-pointer ABI `void f(S* out, ...args)`.
      *
      * A case-class return type is otherwise interpreted as a multi-value (C out-param) return: the first field is the C
      * return value and the remaining fields are filled through trailing out-param pointers. `@Ffi.byValue` selects the
      * struct-return ABI instead. The C function takes a pointer to caller-allocated struct storage as its FIRST argument,
      * fills it, and returns `void` (e.g. `void make_point(Point* out, int x, int y)`). The generated code allocates that
      * storage, passes the out-pointer first, and marshals the filled struct back into the case class. This mirrors how
      * struct PARAMETERS already cross the boundary (also as pointers), so it is register-ABI-free and identical on every
      * backend (JVM Panama, Scala Native, JS koffi).
      *
      * From Scala the method still returns the bare case class; the annotation only selects the return ABI, it does not
      * change the declared return type. Unlike a multi-value return (which requires at least two fields), a by-value
      * struct return may have a single field.
      *
      * {{{
      * trait GeometryBindings extends Ffi:
      *     @Ffi.byValue def makePoint(x: Int, y: Int): Point   // C: void make_point(Point* out, int, int)
      *     def divmod(a: Int, b: Int): DivMod                  // C out-param: int divmod(int, int, int* rem)
      * }}}
      */
    final class byValue extends StaticAnnotation

    /** Opaque return type wrapper indicating C-borrowed (non-owning) memory.
      *
      * The caller MUST NOT free the underlying memory, the C side retains ownership. Only valid as a return type wrapping `String` or
      * `Buffer[A]`. The codegen detects `Borrowed[A]` in method return types and emits the appropriate borrowed marshalling.
      *
      * For `Borrowed[Buffer[A]]`, the codegen infers the element count from the method's `Int` or `Long` parameters: when there is exactly
      * one such parameter, it is used as the size. When there are zero or multiple, the build fails with a clear error.
      *
      * For `Borrowed[String]`, the length is derived from the NUL terminator (bounded by `-Dkyo.ffi.stringFieldMaxBytes=`).
      *
      * {{{
      * trait PosixBindings extends Ffi:
      *     def getenv(name: String): Borrowed[String]
      *     def malloc_chunk(n: Long): Borrowed[Buffer[Byte]]
      * }}}
      */
    opaque type Borrowed[A] = A

    object Borrowed:
        private[kyo] def wrap[A](value: A): Borrowed[A] = value
        extension [A](b: Borrowed[A])
            def value: A = b
    end Borrowed

    /** Zero-allocation POSIX value-or-error carrier for errno-aware C calls.
      *
      * Packs a syscall result and its errno into one `Long`: `o >= 0` carries the return value (an fd, a byte count, 0); `o < 0` carries
      * `-errno`. POSIX returns are `Int` fds and result codes and `Long` byte counts, all of which fit a 64-bit word, so the opaque `Long`
      * is lossless. `.value` reads back at the C width: an `Outcome[Int]` yields an `Int`, an `Outcome[Long]` a `Long`.
      *
      * The phantom type parameter `A` records the C return width (`Int` or `Long`): the code generator reads it to pick the function
      * descriptor's return layout, so an `Outcome[Int]` reads a C `int` at `JAVA_INT` (sign-extended into the packed `Long`) and an
      * `Outcome[Long]` reads a C `long` at `JAVA_LONG`. `A` carries no runtime cost, `Outcome[A]` still erases to a bare `Long`.
      *
      * The failure model is the throwing one: this is a value representation, not a typed `Abort`. The caller inspects `.errorCode`
      * (or the branch-free `.isError`); `.errorCode != 0` is the error test.
      *
      * {{{
      * val r = bindings.riskyOp(42)        // returns Outcome[Int]
      * if r.errorCode != 0 then handleError(r.errorCode)
      * else useValue(r.value)              // .value is Int for Outcome[Int], Long for Outcome[Long]
      * }}}
      */
    opaque type Outcome[A] = Long

    object Outcome:
        /** Smart constructor the codegen emits to wrap an already-packed Long. Companion-private,
          * mirroring `Handle.wrap` / `Borrowed.wrap`.
          */
        private[kyo] inline def wrap[A](packed: Long): Outcome[A] = packed

        /** The packing constructor: returns `value` when `errno == 0` or `value >= 0`, else `-errno`. The single place the POSIX packing
          * convention is defined; codegen calls it after the C call.
          */
        private[kyo] inline def fromValueErrno[A](value: Long, errno: Int): Outcome[A] =
            if errno == 0 || value >= 0L then value else -errno.toLong
        extension [A](o: Outcome[A])
            /** The syscall return value at its C width (`Int` for `Outcome[Int]`, `Long` for `Outcome[Long]`) when `o >= 0`; `-1` when
              * `o` packs an error (matching the POSIX `-1`-on-error convention). `inline` so the width is resolved at the call site and the
              * primitive is returned unboxed: a non-`inline` `def value: A` would box the `Int` (the erased `A` is `Object`), defeating the
              * zero-allocation goal.
              */
            inline def value: A =
                inline erasedValue[A] match
                    case _: Int  => (if o >= 0L then o.toInt else -1).asInstanceOf[A]
                    case _: Long => (if o >= 0L then o else -1L).asInstanceOf[A]

            /** 0 when `value >= 0` (success); the positive errno (`= -o`) when `o < 0`. The `errorCode != 0` error test. */
            def errorCode: Int = if o < 0L then (-o).toInt else 0

            /** Total, branch-free error predicate (`o < 0L`); the canonical check `errorCode != 0` stays valid. */
            def isError: Boolean = o < 0L
        end extension
    end Outcome

    /** Optional customization for an `Ffi` binding trait's companion. All parameters are compile-time constants read by the generator via
      * TASTy, supply concrete literals only.
      *
      * A binding companion declares its config by extending this class with named arguments: `object FooBindings extends
      * Ffi.Config(library = "foo", headers = Chunk("foo.h"))`. This `extends Ffi.Config(...)` form is the canonical and required path: the
      * codegen reads the config exclusively from the super-constructor arguments in TASTy, so it is the only construction that a binding
      * companion can use. Named arguments keep it readable despite the parameter count; supply only the fields you need (every parameter has a
      * default).
      *
      * For programmatic `Config` construction outside a binding companion (tests, tooling), [[Config.builder]] offers an immutable copy-based
      * alternative. The builder produces a runtime `Config` instance and therefore cannot drive codegen for a binding.
      *
      * @param library
      *   Library identifier. Default is the snake_case of the simple trait name; supply a literal here to override.
      * @param symbolPrefix
      *   Prefix prepended to each derived C symbol. Default: no prefix, so `tcpConnect` → `tcp_connect`. With prefix `kyo_`, `tcpConnect` →
      *   `kyo_tcp_connect`.
      * @param symbols
      *   Per-method C-symbol override. Overrides prefix + derivation when the method name matches a key.
      * @param packedStructs
      *   Case-class names whose layout should be packed (no padding).
      * @param scratchSize
      *   Per-binding override for the JVM per-thread scratch block size in bytes (default: `Absent` falls back to the global `-Dkyo.ffi.scratch.size=`).
      *   Supply `Present(N)` to pre-size the block for bindings that routinely exceed the 64 KiB default. Must be a compile-time constant.
      * @param checkedBorrows
      *   Opt this binding into checked-borrow mode for every borrowed [[kyo.ffi.Buffer]] the generator produces. Default: `false`.
      *   Process-wide override: `-Dkyo.ffi.checkedBorrows=true`. Must be a compile-time constant.
      * @param headers
      *   C headers required by this binding. When targeting Scala Native, the generator probes the build host for each header via `cc -E`;
      *   if any header is missing, the emitted impl class contains runtime stubs (throwing `UnsupportedOperationException`) instead of
      *   `@extern` declarations. This allows OS-specific bindings (e.g. epoll on Linux, kqueue on macOS) to coexist in the same source tree
      *   without causing link failures on platforms that lack the symbols. Empty (the default) means no header check, the binding is
      *   assumed available everywhere.
      * @param nativeBundled
      *   On Scala Native this library's C is compiled into the binary (placed under `resources/scala-native`), so the generated binding
      *   must NOT emit `@link` (which would force a `-l<library>` the linker can't find). JVM/JS still load the shared library at runtime.
      *   Default: `false` (the binding emits `@link(<library>)` and the library is resolved as an ordinary `-l<library>` on Native).
      */
    abstract class Config(
        val library: String = "",
        val symbolPrefix: String = "",
        val symbols: Map[String, String] = Map.empty,
        val packedStructs: Set[String] = Set.empty,
        val scratchSize: Maybe[Int] = Absent,
        val checkedBorrows: Boolean = false,
        val headers: Chunk[String] = Chunk.empty,
        val nativeBundled: Boolean = false
    )

    /** Companion to [[Config]]. Houses the immutable copy-based [[Config.Builder]], the preferred construction path. */
    object Config:

        /** Immutable builder for [[Config]]. Each setter returns a new instance; call [[build]] to produce the final `Config`.
          *
          * Copy-based so intermediate builders can be shared without cross-contamination:
          * {{{
          * val base = Ffi.Config.builder.library("kyo_tcp")
          * val a = base.scratchSize(1024).build
          * val b = base.checkedBorrows(true).build
          * }}}
          */
        final class Builder private[Config] (
            private val library: String = "",
            private val symbolPrefix: String = "",
            private val symbols: Map[String, String] = Map.empty,
            private val packedStructs: Set[String] = Set.empty,
            private val scratchSize: Maybe[Int] = Absent,
            private val checkedBorrows: Boolean = false,
            private val headers: Chunk[String] = Chunk.empty,
            private val nativeBundled: Boolean = false
        ):
            def library(v: String): Builder =
                new Builder(v, symbolPrefix, symbols, packedStructs, scratchSize, checkedBorrows, headers, nativeBundled)
            def symbolPrefix(v: String): Builder =
                new Builder(library, v, symbols, packedStructs, scratchSize, checkedBorrows, headers, nativeBundled)
            def symbols(v: Map[String, String]): Builder =
                new Builder(library, symbolPrefix, v, packedStructs, scratchSize, checkedBorrows, headers, nativeBundled)
            def packedStructs(v: Set[String]): Builder =
                new Builder(library, symbolPrefix, symbols, v, scratchSize, checkedBorrows, headers, nativeBundled)
            def scratchSize(v: Int): Builder =
                new Builder(library, symbolPrefix, symbols, packedStructs, Present(v), checkedBorrows, headers, nativeBundled)
            def checkedBorrows(v: Boolean): Builder =
                new Builder(library, symbolPrefix, symbols, packedStructs, scratchSize, v, headers, nativeBundled)
            def headers(v: Chunk[String]): Builder =
                new Builder(library, symbolPrefix, symbols, packedStructs, scratchSize, checkedBorrows, v, nativeBundled)
            def nativeBundled(v: Boolean): Builder =
                new Builder(library, symbolPrefix, symbols, packedStructs, scratchSize, checkedBorrows, headers, v)

            def build: Config =
                new Config(library, symbolPrefix, symbols, packedStructs, scratchSize, checkedBorrows, headers, nativeBundled) {}
        end Builder

        /** Start a new [[Builder]] with default values. */
        def builder: Builder = new Builder()
    end Config

    /** Outcome of a [[Guard.close]] or [[Guard.closeAwait]] call.
      *
      *   - [[CloseOutcome.Clean]]: drain completed within the timeout; `platformCloser` ran and the guard is in its terminal closed state.
      *   - [[CloseOutcome.TimedOut]]: the drain timeout elapsed while retained callbacks were still in flight. The guard is left in
      *     `StateClosing`, `platformCloser` has NOT run, and the arena / retained slots remain valid. When the last in-flight callback
      *     eventually calls `endCallback`, `platformCloser` runs at that point (exactly once) and the guard transitions to closed.
      *   - [[CloseOutcome.AlreadyClosed]]: the guard was already closed (or a concurrent caller won the race). No side effects.
      */
    sealed trait CloseOutcome derives CanEqual
    object CloseOutcome:
        case object Clean         extends CloseOutcome
        case object TimedOut      extends CloseOutcome
        case object AlreadyClosed extends CloseOutcome
    end CloseOutcome

    /** Guard for callback stubs and lifetime-bounded FFI resources. Open adds to a process-wide registry; close releases and removes.
      *
      * Users obtain a guard exclusively via [[Guard.use]], which guarantees cleanup. Direct `open`/`close` are restricted to `private[kyo]`
      * so that guards cannot leak.
      */
    trait Guard:
        /** Release all resources owned by this scope and remove it from the process-wide registry. Uses the default drain timeout (`5s`,
          * configurable on JVM via `-Dkyo.ffi.guard.drainTimeoutMs`). Idempotent.
          *
          * Returns [[CloseOutcome.Clean]] when retained callbacks finish within the timeout; [[CloseOutcome.TimedOut]] when the timeout
          * elapsed while callbacks were still in flight (platformCloser is DEFERRED to the last `endCallback` in that case, leaving the
          * arena / retained slots valid); [[CloseOutcome.AlreadyClosed]] on a second invocation.
          *
          * `private[kyo]` by design: the visibility ladder forces user code through [[Guard.use]] (which guarantees cleanup) or the public
          * [[closeAwait]], so a guard cannot leak by being closed out of bracket. Only kyo-internal call paths invoke `close` directly.
          */
        private[kyo] def close(): CloseOutcome

        /** Variant of [[close]] that takes an explicit drain timeout. Semantics and return values match [[close]] exactly. */
        def closeAwait(timeout: FiniteDuration)(using frame: kyo.Frame): CloseOutcome

        /** Register a buffer with this scope so its lifetime is bounded by the scope's. Returns the same buffer for chaining.
          */
        def registerBuffer[A](b: kyo.ffi.Buffer[A]): b.type

        /** Pin `ref` for the lifetime of this guard. No-op after [[close]]. Used by generated code. Not for user code. */
        private[ffi] def unsafeRetain(ref: AnyRef): Unit

        /** Diagnostic access to the retained-count. For tests only. */
        private[ffi] def retainedCount: Int
    end Guard

    /** Entry points for opening and using an FFI [[Guard]]. */
    object Guard:

        /** Open a new scope. Must be paired with [[Guard.close]]. Restricted to `private[kyo]`, callers should use [[use]] instead.
          */
        private[kyo] def open()(using frame: kyo.Frame): Guard =
            kyo.ffi.internal.GuardFactory.open(frame)

        /** Open a scope, run `f` with it, and close the scope, even on exception, before returning. Inline to keep the hot path clean at
          * call sites.
          */
        inline def use[R](inline f: Guard => R)(using inline frame: kyo.Frame): R =
            val g = open()
            try f(g)
            finally discard(g.close())
        end use
    end Guard

    /** Load and instantiate the generated impl for a binding trait `T`. Cached after the first call.
      *
      * This constructs the impl only. The generated impl defers both its ABI check and its native library load to the FIRST binding method
      * CALL, not to this `load`: the checks run when the impl's companion initializes, which a bare `Ffi.load` does not touch. A
      * `LibraryNotFound` or `AbiMismatch` therefore surfaces from the first invocation on `T`, not from here, so a caller that must contain a
      * load failure cannot wrap `load` alone: it guards the first binding call, or forces a probe read inside the same guard.
      *
      * @throws kyo.ffi.FfiLoadError
      *   on a documented load failure: `LibraryNotFound` (native library not resolvable), `AbiMismatch`, `Unsupported` (32-bit host,
      *   browser Scala.js), or `ImplNotFound` (no generated impl on the classpath).
      * @throws java.lang.IllegalStateException
      *   on the JVM when the generated impl class lacks a public nullary constructor: the ISE thrown inside `FfiReflect.instantiate`
      *   escapes `Ffi.load` uncaught (`computeIfAbsent` propagates it; only the class-not-found case is wrapped into `ImplNotFound`).
      */
    inline def load[T <: Ffi](using ct: scala.reflect.ClassTag[T], allow: AllowUnsafe): T =
        cache.computeIfAbsent(ct.runtimeClass, c => instantiate(c)).asInstanceOf[T]

    /** Pre-warm the [[load]] cache for `T`. Idempotent. Useful during startup to amortize first-call reflection cost. */
    inline def warmLoad[T <: Ffi](using ct: scala.reflect.ClassTag[T], allow: AllowUnsafe): Unit =
        discard(load[T])

    /** Evict the cached impl for `T` so the next [[load]] call re-instantiates. Intended for test scenarios, not normal use. */
    def unload[T <: Ffi](using ct: scala.reflect.ClassTag[T], allow: AllowUnsafe): Unit =
        discard(cache.remove(ct.runtimeClass))

    // ---- internals ----

    private val cache = new java.util.concurrent.ConcurrentHashMap[Class[?], AnyRef]()

    private def instantiate(cls: Class[?]): AnyRef =
        val implName = cls.getName + "Impl"
        kyo.ffi.internal.FfiReflect.instantiate(implName, cls.getName)
end Ffi
