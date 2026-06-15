package kyo.ffi.internal

import kyo.ffi.FfiLoadError
import scala.scalajs.js
import scala.scalajs.js.annotation.*

/** Thin Scala.js facade over the [koffi](https://koffi.dev) npm package. koffi is the runtime native-call dispatcher for the kyo-ffi JS
  * backend, a pure JS FFI alternative to N-API.
  *
  * The `native`-marked `Koffi` object maps to the `koffi` module. Members mirror the real koffi API surface used by generated code and by
  * user-facing helpers in [[KoffiFacade]]. Runtime correctness (semantics, out-param marshalling, callback lifetime) is validated only in
  * the scripted integration tests against a real Node + koffi install; the tests in this module exercise only structural shape (no calls into
  * koffi) so they are runnable without koffi installed.
  */
@js.native
@JSImport("koffi", JSImport.Namespace)
private[ffi] object Koffi extends js.Object:

    /** koffi 2.x helper that pins a JS value to a specific koffi type. Used for variadic call sites where each vararg must be typed at call
      * time rather than the prototype.
      *
      * koffi signature: `koffi.as(value, type): unknown`.
      */
    def as(value: js.Any, tpe: String): js.Any = js.native

    /** Load a native shared library (`.so`/`.dylib`/`.dll`). Returns a koffi "library" handle that exposes `.func(...)` and friends.
      *
      * A `null` path binds against the process's default symbol scope (POSIX `RTLD_DEFAULT`), which carries libc / libm / pthread on every
      * platform. [[NativeLoader.resolveSystemLib]] uses this for known system libraries. koffi accepts `null` here: it reaches
      * `RTLD_DEFAULT` whenever `koffi.load` is called with a non-string argument.
      *
      * koffi signature: `koffi.load(path: string | null): IKoffiLib`.
      */
    def load(path: String): js.Dynamic = js.native

    /** Last captured errno from the most recent koffi call on the current thread. koffi captures errno automatically when the binding is
      * declared with `captureErrno: true`, we use that on every binding.
      *
      * koffi signature: `koffi.errno(): number`.
      */
    def errno(): Int = js.native

    /** Allocate a typed buffer for out-params of the given koffi type. `count` defaults to `1`. The resulting buffer is passed by reference
      * to a C function that writes through an out-pointer, then read back via [[decode]].
      *
      * koffi signature: `koffi.alloc(type: string, count?: number): Buffer`.
      */
    def alloc(tpe: String, count: Int): js.Dynamic = js.native

    /** Decode a value of the given koffi type name out of a buffer previously populated by a native call.
      *
      * koffi signature: `koffi.decode(buf, type: string, ?offset: number): unknown`.
      */
    def decode(buf: js.Any, tpe: String): js.Dynamic = js.native

    /** Decode an array of `count` elements of `tpe` starting at the pointer `ptr`. Used for borrowed-Buffer returns where a top-level C
      * pointer is returned with a known element count.
      *
      * koffi signature: `koffi.decode(ptr, type: string, count: number): Array | TypedArray`.
      */
    def decode(ptr: js.Any, tpe: String, count: Int): js.Dynamic = js.native

    /** Write a JS value as `tpe` into the buffer `ref` at byte `offset`. Inverse of [[decode]]; used to serialize a struct
      * union variant into the union's byte buffer with full ABI fidelity, including pointer fields, which a manual
      * `DataView` write cannot represent.
      *
      * koffi signature: `koffi.encode(ref, offset: number, type, value): void`.
      */
    def encode(ref: js.Any, offset: Int, tpe: js.Any, value: js.Any): Unit = js.native

    /** Declare a C function-pointer prototype. koffi's docs describe the return as "a string identifier usable as a `koffi.pointer(...)`
      * argument", but on koffi 2.x the concrete value is an opaque `IKoffiCType` (Scala.js runtime rejects a `String` cast). Returning
      * `js.Any` preserves the opaque handle for re-submission to [[register]] / [[pointer]] without a runtime cast failure.
      *
      * koffi signature: `koffi.proto(name: string, returnType: string, argTypes: string[]): IKoffiCType`.
      */
    def proto(name: String, returnType: String, argTypes: js.Array[String]): js.Any = js.native

    /** koffi pointer-to-X type name helper. Used both for out-params (`koffi.pointer("int")`) and for callback signatures in combination
      * with [[proto]] (`koffi.pointer(koffi.proto(...))`). koffi accepts either a string type name or an opaque `IKoffiCType` handle and
      * returns a value consumable at arg-type positions; we type both sides as `js.Any` so the proto-handle overload threads through
      * without runtime string casts.
      *
      * koffi signature: `koffi.pointer(typeName: string | IKoffiCType): string | IKoffiCType`.
      */
    def pointer(tpe: js.Any): js.Any = js.native

    /** Register a Scala/JS callback as a C-callable function pointer, tying it to a koffi prototype previously declared via [[proto]]. The
      * returned pointer is passed to C; its lifetime is user-managed.
      *
      * `prototype` is typed `js.Any` rather than `String` because koffi's `proto(...)` returns an opaque `IKoffiCType` handle and our
      * generated code threads that handle directly into `register` to avoid the "Unknown or invalid type name" rejection koffi throws
      * against bare string identifiers.
      *
      * koffi signature: `koffi.register(callback: Function, prototype: string | IKoffiCType): IKoffiCType`.
      */
    def register(cb: js.Function, prototype: js.Any): js.Dynamic = js.native

    /** Reverse of [[register]], releases the C-callable wrapper. After this call the pointer must not be invoked from C.
      *
      * koffi signature: `koffi.unregister(ptr: IKoffiCType): undefined`.
      */
    def unregister(ptr: js.Any): Unit = js.native

    /** Declare a koffi struct type with natural alignment. Keyed by name; referenced from `func(...)` arg/result strings as the same name.
      *
      * koffi signature: `koffi.struct(name: string, fields: Record<string, string>): IKoffiCType`.
      */
    def struct(name: String, fields: js.Dynamic): js.Dynamic = js.native

    /** Declare a **packed** koffi struct type, identical to [[struct]] but alignment collapses to 1 per field, matching `#pragma pack(1)`.
      *
      * koffi signature: `koffi.pack(name: string, fields: Record<string, string>): IKoffiCType`.
      */
    def pack(name: String, fields: js.Dynamic): js.Dynamic = js.native

    /** Declare a koffi union type, every variant overlays at offset 0, `sizeof == max(fieldSizes)`, `alignof == max(fieldAlignments)`.
      * Used by `@Ffi.Union` case classes.
      *
      * koffi signature: `koffi.union(name: string, fields: Record<string, string>): IKoffiCType`.
      */
    def union(name: String, fields: js.Dynamic): js.Dynamic = js.native

    /** Return the byte size of a koffi-registered type. Accepts either a type name string (e.g. `"int"`) or an opaque `IKoffiCType` handle
      * returned by [[struct]] / [[pack]]. Used by the struct-ABI self-check to compare against the code generator's expected size.
      *
      * koffi signature: `koffi.sizeof(type: string | IKoffiCType): number`.
      */
    def sizeof(tpe: js.Any): Int = js.native
end Koffi

/** Description of one koffi-mediated function binding, as produced by [[kyo.ffi.codegen.emitters.JsEmitter]]. Used by [[KoffiFacade.load]]
  * to construct the runtime dispatch table.
  *
  * @param scalaName
  *   the name used by generated impl code (`facade.<scalaName>(...)`).
  * @param cSymbol
  *   the exported C symbol to locate in the loaded library.
  * @param result
  *   koffi result type string (e.g. `"int"`, `"void"`).
  * @param args
  *   koffi argument type entries in declaration order. Each entry is either a koffi type-name `String` (e.g. `"int"`, `"void*"`,
  *   `"MyStruct*"`) or an opaque koffi Type handle (e.g. `koffi.pointer(proto)` for a callback parameter). Both flow through unchanged to
  *   koffi's `func(...)` factory, which accepts mixed arrays. Typed as `js.Any` so strings and handles coexist without a runtime cast.
  */
final case class KoffiFn(
    scalaName: String,
    cSymbol: String,
    result: String,
    args: Seq[js.Any]
) derives CanEqual

/** Static-only koffi facade. Holds `load`, `outInt`/`outLong`/`outPointer`, `errno`, and callback helpers; per-trait dispatch tables are
  * plain `js.Dynamic` bags returned by [[load]].
  *
  * Generated code stores the bag in a `private val facade` field and calls `facade.<scalaName>(...)`, which desugars via
  * `js.Dynamic#selectDynamic` + `applyDynamic`.
  */
object KoffiFacade:

    /** Holder for a koffi-allocated out-parameter buffer plus its koffi type. [[buf]] is passed to koffi as the out-pointer; [[read]]
      * decodes the value on demand after the native call writes through that pointer.
      */
    final class OutHolder private[KoffiFacade] (val buf: js.Dynamic, tpe: String):

        /** Decode the value currently written into the buffer. */
        def read(): js.Dynamic = Koffi.decode(buf, tpe)
    end OutHolder

    /** Load the native library at `libPath` and bind each requested function via koffi's `func(symbol, result, args)` API.
      *
      * `libPath` may be `null`, in which case koffi binds against the process default symbol scope (`RTLD_DEFAULT`); see [[Koffi.load]].
      * [[NativeLoader.jsResolve]] returns `null` for known system libraries (libc, ...) so their symbols resolve without naming a SONAME.
      *
      * The returned `js.Dynamic` is a bag of function handles keyed by [[KoffiFn.scalaName]], generated code interacts with it directly.
      *
      * Side-effect: installs the koffi-backed unregister hook into [[CallbackRegistry]] so [[JsGuard.close]] can release retained callback
      * pointers at close time. See the comment in [[CallbackRegistry]] for why this indirection exists.
      */
    def load(libPath: String, fns: Seq[KoffiFn]): js.Dynamic =
        /* koffi-correctness verified in scripted integration tests */
        // Validate the koffi package ABI once per process before any downcall is wired. Fails fast with
        // FfiLoadError.Unsupported when the installed koffi is too old, too new, or missing an expected method.
        KoffiAbiProbe.probeOnce()
        val lib = Koffi.load(libPath)
        val bag = js.Dynamic.literal()
        fns.foreach { fn =>
            val args = js.Array(fn.args*)
            val fnHandle =
                lib.applyDynamic("func")(fn.cSymbol.asInstanceOf[js.Any], fn.result.asInstanceOf[js.Any], args)
            bag.updateDynamic(fn.scalaName)(fnHandle)
        }
        CallbackRegistry.installKoffi()
        bag
    end load

    /** Decode a `Uint8Array` view of `count` bytes located at pointer `ptr`. Used for `Borrowed[Buffer[Byte]]` returns where the C function
      * returns a top-level pointer plus a size-inferred element count.
      *
      * Implementation: use koffi's `decode(ptr, 'uint8_t', count)` form, which on koffi 2.x returns a regular JS array of numbers for
      * 1-byte integer types. We copy the content into a fresh `Uint8Array` so the borrowed `Buffer[Byte]` sits on a genuine typed-array;
      * the copy is O(count) but runs once per call and yields a stable storage type for downstream `BufferFactory.wrapBorrowed`.
      */
    def decodeBorrowedBytes(ptr: js.Any, count: Int): scala.scalajs.js.typedarray.Uint8Array =
        import scala.scalajs.js.typedarray.Uint8Array
        val raw = Koffi.decode(ptr, "uint8_t", count)
        // koffi returns a regular JS Array[Number] for 1-byte integer decodes; copy into a Uint8Array so downstream
        // code always sees the same concrete typed-array class.
        val u8a = new Uint8Array(count)
        val arr = raw.asInstanceOf[js.Array[Short]] // Short accommodates the 0..255 range returned by koffi
        var i   = 0
        while i < count do
            u8a(i) = arr(i).asInstanceOf[Short]
            i += 1
        u8a
    end decodeBorrowedBytes

    /** Allocate an `int` out-param cell. Pass `.buf` to koffi as the out-pointer; `.read()` decodes the written int post-call. */
    def outInt(): OutHolder =
        new OutHolder(Koffi.alloc("int", 1), "int")

    /** Allocate an `int64` out-param cell, used for `Long` out-pointers. koffi's `decode` for `int64` yields a `js.BigInt`; callers are
      * responsible for converting via `.toString.toLong` at the use site.
      */
    def outLong(): OutHolder =
        new OutHolder(Koffi.alloc("int64", 1), "int64")

    /** Allocate a generic `void*` out-param cell for pointer-out values. */
    def outPointer(): OutHolder =
        new OutHolder(Koffi.alloc("void*", 1), "void*")

    /** Allocate a struct-sized out-param cell for a by-value struct return (`@Ffi.byValue`). `structName` is the koffi
      * type name registered for the struct in the impl companion via [[struct]] / [[pack]]. Pass `.buf` to koffi as the
      * leading `S* out` argument; `.read()` decodes the filled struct buffer into a koffi object whose fields are read
      * by name, mirroring the out-pointer convention struct PARAMETERS already use. This avoids koffi's native by-value
      * struct return so the ABI (`void f(S* out, ...args)`) matches the JVM and Native backends exactly.
      */
    def outStruct(structName: String): OutHolder =
        new OutHolder(Koffi.alloc(structName, 1), structName)

    /** Read the captured errno from koffi's last call on the current thread. */
    def errno(): Int =
        Koffi.errno()

    /** Invoke a `@Ffi.blocking` binding through koffi's asynchronous dispatch.
      *
      * koffi exposes an `.async(...args, callback)` property on every function handle returned by `.func(...)` (the
      * same handle stored in the [[load]] bag). koffi runs the C call on a libuv worker thread, so the Node event
      * loop is not blocked, then invokes `callback(err, result)` on the main thread once the call completes. The
      * `err` slot is `null` (or `undefined`) on success and an `Error` on failure; `result` carries the C return
      * value, marshalled exactly as the synchronous path. koffi's `errno()` read inside the completion callback
      * returns the errno of the async call.
      *
      * @param facade
      *   the per-trait dispatch bag returned by [[load]].
      * @param name
      *   the [[KoffiFn.scalaName]] key under which the function handle is stored in the bag.
      * @param args
      *   the marshalled argument array, identical to the synchronous call site's arg list.
      * @param cb
      *   the completion callback `(err, result) => Unit`. Generated code routes a non-null/defined `err` to a
      *   promise panic and otherwise marshals `result` into the binding's return value.
      */
    def callAsync(facade: js.Dynamic, name: String, args: js.Array[js.Any], cb: js.Function2[js.Any, js.Any, Unit]): Unit =
        val fn = facade.selectDynamic(name)
        // koffi: fn.async(...args, callback), where callback is (err, result). The async handle lives on the same
        // function handle stored in the bag; spread the args and append the callback in the trailing slot. The
        // `applyDynamic` return is the koffi handle and is unused, discard it.
        val _ = fn.asInstanceOf[js.Dynamic].applyDynamic("async")((args.toSeq :+ (cb: js.Any))*)
        ()
    end callAsync

    /** Declare a C function-pointer prototype. See [[Koffi.proto]]. Returns the opaque koffi Type handle, threadable directly into
      * [[register]] or to `koffi.pointer(...)`.
      */
    def proto(name: String, returnType: String, argTypes: Seq[String]): js.Any =
        Koffi.proto(name, returnType, js.Array(argTypes*))

    /** koffi pointer-to-X type name (used for both out-params and callback signatures). See [[Koffi.pointer]]. `tpe` may be either a koffi
      * type-name string or an opaque proto handle returned by [[proto]]; the return value slots into any arg-type position koffi accepts.
      */
    def pointer(tpe: js.Any): js.Any =
        Koffi.pointer(tpe)

    /** Register a callback and return a C-callable pointer. See [[Koffi.register]]. `prototype` is the opaque handle returned by [[proto]];
      * typed `js.Any` so either a registered proto Type or a raw koffi type spec flows through unchanged.
      */
    def register(cb: js.Function, prototype: js.Any): js.Dynamic =
        Koffi.register(cb, prototype)

    /** Release a previously-[[register]]ed callback pointer. See [[Koffi.unregister]]. */
    def unregister(ptr: js.Any): Unit =
        Koffi.unregister(ptr)

    /** Monotonically-increasing counter for generating unique koffi proto names at runtime. Used by struct field FnPtrT emission to avoid
      * "Duplicate type name" errors when the same struct is marshalled across multiple FFI calls.
      */
    private var protoIdCounter: Int = 0
    def nextProtoId(): Int =
        protoIdCounter += 1
        protoIdCounter

    /** Register a struct type with natural alignment. See [[Koffi.struct]]. Generated impl companions call this for every non-packed struct
      * encountered in the binding trait before calling [[load]], so the per-function arg/result strings can reference the struct by name.
      */
    def struct(name: String, fields: js.Dynamic): js.Dynamic =
        Koffi.struct(name, fields)

    /** Register a packed struct type. See [[Koffi.pack]]. Mirror of [[struct]] for structs declared packed (via the binding's
      * `Ffi.Config.packedStructs` set).
      */
    def pack(name: String, fields: js.Dynamic): js.Dynamic =
        Koffi.pack(name, fields)

    /** Register a union type. See [[Koffi.union]]. Mirror of [[struct]] for unions declared via `@Ffi.Union` on a case class. */
    def union(name: String, fields: js.Dynamic): js.Dynamic =
        Koffi.union(name, fields)

    /** Return the byte size koffi measures for a registered type. Accepts a type name string or the opaque handle returned by [[struct]] /
      * [[pack]]. See [[Koffi.sizeof]]. Used by the struct-ABI self-check.
      */
    def sizeof(tpe: js.Any): Int =
        Koffi.sizeof(tpe)

    /** Serialize a JS value as a registered koffi type into `ref` at byte `offset`. See [[Koffi.encode]]. Generated impls
      * use this to write a struct union variant into the union's `Uint8Array`, delegating field layout (including pointer
      * fields) to koffi so a struct variant marshals identically to a struct parameter, matching JVM and Native.
      */
    def encode(ref: js.Any, offset: Int, tpe: js.Any, value: js.Any): Unit =
        Koffi.encode(ref, offset, tpe, value)

    /** koffi `as` helper, pin a JS value to a specific koffi type string at call time. Needed by variadic call sites where each vararg
      * must carry its own type annotation rather than being declared in the prototype. See [[Koffi.as]].
      */
    def as(value: js.Any, tpe: String): js.Any =
        Koffi.as(value, tpe)
end KoffiFacade

/** Runtime support for variadic FFI downcalls on JS.
  *
  * Each vararg is classified by runtime class to a koffi type name, then pinned via `koffi.as(value, type)` so the koffi dispatcher selects
  * the right C-ABI slot. Mirrors `kyo.ffi.internal.VariadicMarshaller` on JVM. Supported types (v1):
  *
  *   - Scala `Int` → `"int"`
  *   - Scala `Long` → `"int64"` (wrapped as js.BigInt before pinning)
  *   - Scala `Double` → `"double"`
  *   - `String` → `"string"` (koffi auto-marshals JS strings to `const char*`)
  *   - `Buffer[A]` → `"void*"` (unwrap the raw Uint8Array)
  *
  * Any other runtime type raises [[FfiLoadError.Unsupported]] naming the binding + method.
  */
object JsVariadicMarshaller:

    /** Convert the seq of varargs to the koffi-consumable flattened `type, value, type, value, ...` stream that koffi's variadic dispatch
      * expects after the fixed-arg tail. See https://koffi.dev/calls#variadic-calls for the reference.
      */
    def marshalVarargs(
        args: Seq[Any],
        bindingFqn: String,
        methodName: String
    ): js.Array[js.Any] =
        val out = js.Array[js.Any]()
        args.foreach { v =>
            val (tpe, value) = classify(v, bindingFqn, methodName)
            val _            = out.push(tpe, value)
        }
        out
    end marshalVarargs

    private def classify(v: Any, bindingFqn: String, methodName: String): (js.Any, js.Any) =
        if v.asInstanceOf[AnyRef] eq null then
            throw new FfiLoadError.Unsupported(FfiGenErrors.unsupportedVararg(bindingFqn, methodName, "null"))
        else
            v match
                case i: Int =>
                    ("int".asInstanceOf[js.Any], i.asInstanceOf[js.Any])
                case l: Long =>
                    ("int64".asInstanceOf[js.Any], js.BigInt(l.toString).asInstanceOf[js.Any])
                case d: Double =>
                    ("double".asInstanceOf[js.Any], d.asInstanceOf[js.Any])
                case s: String =>
                    ("string".asInstanceOf[js.Any], s.asInstanceOf[js.Any])
                case b: kyo.ffi.Buffer[?] =>
                    val raw = b.raw.asInstanceOf[AnyRef]
                    val seg = FfiUnsafe.expect[JsRawSegment](
                        raw,
                        classOf[JsRawSegment],
                        "JsRawSegment on JS",
                        bindingFqn,
                        methodName
                    )
                    ("void*".asInstanceOf[js.Any], seg.u8a.asInstanceOf[js.Any])
                case other =>
                    throw new FfiLoadError.Unsupported(FfiGenErrors.unsupportedVararg(bindingFqn, methodName, other.getClass.getName))
            end match
        end if
    end classify
end JsVariadicMarshaller
