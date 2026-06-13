package kyo.ffi.internal

import java.lang.foreign.AddressLayout
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.lang.invoke.MethodHandle
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType
import java.lang.reflect.Method

/** Bridges a Scala `FunctionN[...]` value to a Panama upcall stub.
  *
  * Each `stubN` overload locates the `Function$N.apply` method reflectively (via `MethodHandles.publicLookup`), binds it to the supplied
  * Scala function instance, and then `asType`-coerces the handle to the primitive-typed `MethodType` implied by the supplied
  * [[java.lang.foreign.FunctionDescriptor]]. The coerced handle is handed to `Linker.upcallStub` which returns a `MemorySegment` usable as
  * a C function pointer.
  *
  * Primitive boxing/unboxing is delegated to `MethodHandle.asType`. Scala compiles `FunctionN.apply` with erased `Object` parameters, so
  * the bound handle has a generic `(Object...)Object` signature; `asType` inserts the appropriate unbox/box adapters at each position. This
  * is the cost: one box per primitive argument and one unbox of the return value per callback invocation, but it requires no specialized
  * code-gen per arity.
  *
  * The arena's lifetime determines the stub's validity. Transient callbacks use a per-call confined arena; retained callbacks use the
  * shared arena exposed by [[JvmGuard.unsafeArena]] so the stub lives until the guard closes.
  *
  * Used exclusively by generated FFI code emitted by `JvmEmitter`.
  */
object UpcallBridge:

    private val linker: Linker = Linker.nativeLinker().nn

    /** Wrap a `Function0`. */
    def stub0[R](
        f: () => R,
        descriptor: FunctionDescriptor,
        arena: Arena,
        bindingFqn: String,
        methodName: String,
        kind: String
    ): MemorySegment =
        wrap(f, 0, descriptor, arena, bindingFqn, methodName, kind)

    /** Wrap a `Function1`. */
    def stub1[A, R](
        f: A => R,
        descriptor: FunctionDescriptor,
        arena: Arena,
        bindingFqn: String,
        methodName: String,
        kind: String
    ): MemorySegment =
        wrap(f, 1, descriptor, arena, bindingFqn, methodName, kind)

    /** Wrap a `Function2`. */
    def stub2[A, B, R](
        f: (A, B) => R,
        descriptor: FunctionDescriptor,
        arena: Arena,
        bindingFqn: String,
        methodName: String,
        kind: String
    ): MemorySegment =
        wrap(f, 2, descriptor, arena, bindingFqn, methodName, kind)

    /** Wrap a `Function3`. */
    def stub3[A, B, C, R](
        f: (A, B, C) => R,
        descriptor: FunctionDescriptor,
        arena: Arena,
        bindingFqn: String,
        methodName: String,
        kind: String
    ): MemorySegment =
        wrap(f, 3, descriptor, arena, bindingFqn, methodName, kind)

    /** Wrap a `Function4`. */
    def stub4[A, B, C, D, R](
        f: (A, B, C, D) => R,
        descriptor: FunctionDescriptor,
        arena: Arena,
        bindingFqn: String,
        methodName: String,
        kind: String
    ): MemorySegment =
        wrap(f, 4, descriptor, arena, bindingFqn, methodName, kind)

    /** Wrap a `Function5`. */
    def stub5[A, B, C, D, E, R](
        f: (A, B, C, D, E) => R,
        descriptor: FunctionDescriptor,
        arena: Arena,
        bindingFqn: String,
        methodName: String,
        kind: String
    ): MemorySegment =
        wrap(f, 5, descriptor, arena, bindingFqn, methodName, kind)

    /** Wrap a `Function6`. */
    def stub6[A, B, C, D, E, F, R](
        f: (A, B, C, D, E, F) => R,
        descriptor: FunctionDescriptor,
        arena: Arena,
        bindingFqn: String,
        methodName: String,
        kind: String
    ): MemorySegment =
        wrap(f, 6, descriptor, arena, bindingFqn, methodName, kind)

    /** Wrap a `Function7`. */
    def stub7[A, B, C, D, E, F, G, R](
        f: (A, B, C, D, E, F, G) => R,
        descriptor: FunctionDescriptor,
        arena: Arena,
        bindingFqn: String,
        methodName: String,
        kind: String
    ): MemorySegment =
        wrap(f, 7, descriptor, arena, bindingFqn, methodName, kind)

    /** Wrap a `Function8`. */
    def stub8[A, B, C, D, E, F, G, H, R](
        f: (A, B, C, D, E, F, G, H) => R,
        descriptor: FunctionDescriptor,
        arena: Arena,
        bindingFqn: String,
        methodName: String,
        kind: String
    ): MemorySegment =
        wrap(f, 8, descriptor, arena, bindingFqn, methodName, kind)

    /** Wrap a `Function9`. */
    def stub9[A, B, C, D, E, F, G, H, I, R](
        f: (A, B, C, D, E, F, G, H, I) => R,
        descriptor: FunctionDescriptor,
        arena: Arena,
        bindingFqn: String,
        methodName: String,
        kind: String
    ): MemorySegment =
        wrap(f, 9, descriptor, arena, bindingFqn, methodName, kind)

    /** Wrap a `Function10`. */
    def stub10[A, B, C, D, E, F, G, H, I, J, R](
        f: (A, B, C, D, E, F, G, H, I, J) => R,
        descriptor: FunctionDescriptor,
        arena: Arena,
        bindingFqn: String,
        methodName: String,
        kind: String
    ): MemorySegment =
        wrap(f, 10, descriptor, arena, bindingFqn, methodName, kind)

    /** Wrap a `Function11`. */
    def stub11[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, R](
        f: (T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11) => R,
        descriptor: FunctionDescriptor,
        arena: Arena,
        bindingFqn: String,
        methodName: String,
        kind: String
    ): MemorySegment =
        wrap(f, 11, descriptor, arena, bindingFqn, methodName, kind)

    /** Wrap a `Function12`. */
    def stub12[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, R](
        f: (T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12) => R,
        descriptor: FunctionDescriptor,
        arena: Arena,
        bindingFqn: String,
        methodName: String,
        kind: String
    ): MemorySegment =
        wrap(f, 12, descriptor, arena, bindingFqn, methodName, kind)

    /** Wrap a `Function13`. */
    def stub13[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, R](
        f: (T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13) => R,
        descriptor: FunctionDescriptor,
        arena: Arena,
        bindingFqn: String,
        methodName: String,
        kind: String
    ): MemorySegment =
        wrap(f, 13, descriptor, arena, bindingFqn, methodName, kind)

    /** Wrap a `Function14`. */
    def stub14[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, R](
        f: (T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14) => R,
        descriptor: FunctionDescriptor,
        arena: Arena,
        bindingFqn: String,
        methodName: String,
        kind: String
    ): MemorySegment =
        wrap(f, 14, descriptor, arena, bindingFqn, methodName, kind)

    /** Wrap a `Function15`. */
    def stub15[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, R](
        f: (T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15) => R,
        descriptor: FunctionDescriptor,
        arena: Arena,
        bindingFqn: String,
        methodName: String,
        kind: String
    ): MemorySegment =
        wrap(f, 15, descriptor, arena, bindingFqn, methodName, kind)

    /** Wrap a `Function16`. */
    def stub16[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, R](
        f: (T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16) => R,
        descriptor: FunctionDescriptor,
        arena: Arena,
        bindingFqn: String,
        methodName: String,
        kind: String
    ): MemorySegment =
        wrap(f, 16, descriptor, arena, bindingFqn, methodName, kind)

    /** Wrap a `Function17`. */
    def stub17[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17, R](
        f: (T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17) => R,
        descriptor: FunctionDescriptor,
        arena: Arena,
        bindingFqn: String,
        methodName: String,
        kind: String
    ): MemorySegment =
        wrap(f, 17, descriptor, arena, bindingFqn, methodName, kind)

    /** Wrap a `Function18`. */
    def stub18[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17, T18, R](
        f: (T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17, T18) => R,
        descriptor: FunctionDescriptor,
        arena: Arena,
        bindingFqn: String,
        methodName: String,
        kind: String
    ): MemorySegment =
        wrap(f, 18, descriptor, arena, bindingFqn, methodName, kind)

    /** Wrap a `Function19`. */
    def stub19[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17, T18, T19, R](
        f: (T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17, T18, T19) => R,
        descriptor: FunctionDescriptor,
        arena: Arena,
        bindingFqn: String,
        methodName: String,
        kind: String
    ): MemorySegment =
        wrap(f, 19, descriptor, arena, bindingFqn, methodName, kind)

    /** Wrap a `Function20`. */
    def stub20[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17, T18, T19, T20, R](
        f: (T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17, T18, T19, T20) => R,
        descriptor: FunctionDescriptor,
        arena: Arena,
        bindingFqn: String,
        methodName: String,
        kind: String
    ): MemorySegment =
        wrap(f, 20, descriptor, arena, bindingFqn, methodName, kind)

    /** Wrap a `Function21`. */
    def stub21[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17, T18, T19, T20, T21, R](
        f: (T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17, T18, T19, T20, T21) => R,
        descriptor: FunctionDescriptor,
        arena: Arena,
        bindingFqn: String,
        methodName: String,
        kind: String
    ): MemorySegment =
        wrap(f, 21, descriptor, arena, bindingFqn, methodName, kind)

    /** Wrap a `Function22`. */
    def stub22[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17, T18, T19, T20, T21, T22, R](
        f: (T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17, T18, T19, T20, T21, T22) => R,
        descriptor: FunctionDescriptor,
        arena: Arena,
        bindingFqn: String,
        methodName: String,
        kind: String
    ): MemorySegment =
        wrap(f, 22, descriptor, arena, bindingFqn, methodName, kind)

    // =========================================================================
    // Specialized shape bridges
    // =========================================================================
    //
    // For callbacks whose signature matches one of the 12 catalog shapes used by the Scala Native runtime (V_U, I_U, I_I,
    // II_I, JJ_I, P_U, PI_U, J_J, J_U, D_D, II_U, JJ_J, see kyo.ffi.codegen.emitters.NativeCallbackCatalog), we build the MethodHandle from a
    // **static helper** whose primitive-typed signature matches the C descriptor exactly. The generic `stubN` path uses
    // `MethodHandle.asType` to adapt the Scala-function's erased `(Object...)Object` `apply` method to the primitive
    // signature, which inserts box/unbox adapters at every argument and the return, one box per primitive per call.
    // The specialized bridges side-step that chain by calling `MethodHandles.lookup().findStatic` on a typed helper
    // (`invokeShape_II_I`, etc.) and then `insertArguments(mh, 0, fn)` to bind the function instance; the resulting
    // MethodHandle's MethodType already matches the descriptor, so no `asType` adapter is installed on the hot path.
    //
    // A user callback whose signature falls outside the catalog still takes the generic `stubN` path (documented degraded
    // performance, correctness unchanged).

    private[internal] def invokeShape_V_U(f: () => Unit): Unit =
        f()

    private[internal] def invokeShape_I_U(f: Int => Unit, a: Int): Unit =
        f(a)

    private[internal] def invokeShape_I_I(f: Int => Int, a: Int): Int =
        f(a)

    private[internal] def invokeShape_II_I(f: (Int, Int) => Int, a: Int, b: Int): Int =
        f(a, b)

    private[internal] def invokeShape_JJ_I(f: (Long, Long) => Int, a: Long, b: Long): Int =
        f(a, b)

    private[internal] def invokeShape_P_U(f: MemorySegment => Unit, a: MemorySegment): Unit =
        f(a)

    private[internal] def invokeShape_PI_U(f: (MemorySegment, Int) => Unit, a: MemorySegment, b: Int): Unit =
        f(a, b)

    private[internal] def invokeShape_J_J(f: Long => Long, a: Long): Long =
        f(a)

    private[internal] def invokeShape_J_U(f: Long => Unit, a: Long): Unit =
        f(a)

    private[internal] def invokeShape_D_D(f: Double => Double, a: Double): Double =
        f(a)

    private[internal] def invokeShape_II_U(f: (Int, Int) => Unit, a: Int, b: Int): Unit =
        f(a, b)

    private[internal] def invokeShape_JJ_J(f: (Long, Long) => Long, a: Long, b: Long): Long =
        f(a, b)

    /** `object UpcallBridge` lowers to class `UpcallBridge$`; its `invokeShape_*` members are instance methods on the module. Bind the
      * module receiver once at class init so [[shapeMethodHandle]] returns a handle whose type matches the C descriptor with no
      * module-receiver slot.
      */
    private val moduleClass: Class[?] = UpcallBridge.getClass.nn

    /** Mapping from catalog shape id → a pre-bound MethodHandle whose type matches the Scala-side invoker. Looked up once at class init; no
      * per-call reflection.
      */
    private val specializedInvokers: java.util.Map[String, MethodHandle] =
        val m = new java.util.HashMap[String, MethodHandle]()
        // MethodType of the shape, shaped as (FunctionN, primitiveArgs*) -> primitiveRet. Bound via bindTo so the
        // singleton MODULE$ receiver is baked in; the callable handle then accepts (FunctionN, primitiveArgs*).
        def bind(shape: String, fnCls: Class[?], retCls: Class[?], argClses: Class[?]*): Unit =
            val allArgs: Array[Class[?]] = (fnCls +: argClses).toArray
            val mt                       = MethodType.methodType(retCls, allArgs).nn
            val raw                      = MethodHandles.lookup().nn.findVirtual(moduleClass, s"invokeShape_$shape", mt).nn
            val _                        = m.put(shape, raw.bindTo(UpcallBridge).nn)
        end bind
        bind("V_U", classOf[scala.Function0[?]], java.lang.Void.TYPE.nn)
        bind("I_U", classOf[scala.Function1[?, ?]], java.lang.Void.TYPE.nn, java.lang.Integer.TYPE.nn)
        bind("I_I", classOf[scala.Function1[?, ?]], java.lang.Integer.TYPE.nn, java.lang.Integer.TYPE.nn)
        bind(
            "II_I",
            classOf[scala.Function2[?, ?, ?]],
            java.lang.Integer.TYPE.nn,
            java.lang.Integer.TYPE.nn,
            java.lang.Integer.TYPE.nn
        )
        bind(
            "JJ_I",
            classOf[scala.Function2[?, ?, ?]],
            java.lang.Integer.TYPE.nn,
            java.lang.Long.TYPE.nn,
            java.lang.Long.TYPE.nn
        )
        bind("P_U", classOf[scala.Function1[?, ?]], java.lang.Void.TYPE.nn, classOf[MemorySegment])
        bind(
            "PI_U",
            classOf[scala.Function2[?, ?, ?]],
            java.lang.Void.TYPE.nn,
            classOf[MemorySegment],
            java.lang.Integer.TYPE.nn
        )
        bind("J_J", classOf[scala.Function1[?, ?]], java.lang.Long.TYPE.nn, java.lang.Long.TYPE.nn)
        bind("J_U", classOf[scala.Function1[?, ?]], java.lang.Void.TYPE.nn, java.lang.Long.TYPE.nn)
        bind("D_D", classOf[scala.Function1[?, ?]], java.lang.Double.TYPE.nn, java.lang.Double.TYPE.nn)
        bind(
            "II_U",
            classOf[scala.Function2[?, ?, ?]],
            java.lang.Void.TYPE.nn,
            java.lang.Integer.TYPE.nn,
            java.lang.Integer.TYPE.nn
        )
        bind(
            "JJ_J",
            classOf[scala.Function2[?, ?, ?]],
            java.lang.Long.TYPE.nn,
            java.lang.Long.TYPE.nn,
            java.lang.Long.TYPE.nn
        )
        java.util.Collections.unmodifiableMap(m).nn
    end specializedInvokers

    /** Generic specialised-bridge constructor. Called by the per-shape `stubShape_<SHAPE>` entry points below. Takes the catalog shape id,
      * the Scala function instance, the Panama [[FunctionDescriptor]] and the callback arena; returns the native upcall stub.
      *
      * Invariants:
      *   - `shape` MUST be in [[specializedInvokers]]; callers are the per-shape `stubShape_*` methods that enforce this.
      *   - `descriptor` MUST match the shape's C layout (the emitter's `callbackFunctionDescriptor` derives it from the Scala signature, so
      *     the pairing is ABI-correct by construction).
      *
      * The resulting handle is passed through [[installCallbackHandler]] exactly like the generic path, so the same `Throwable` →
      * typed-zero guarantee applies (see README "Callback exception handling").
      */
    private def wrapSpecialized(
        shape: String,
        f: AnyRef,
        descriptor: FunctionDescriptor,
        arena: Arena,
        bindingFqn: String,
        methodName: String,
        kind: String
    ): MemorySegment =
        val invoker = specializedInvokers.get(shape)
        if invoker == null then
            throw new IllegalStateException(s"UpcallBridge: no specialized invoker registered for shape '$shape'")
        val bound                 = MethodHandles.insertArguments(invoker, 0, f).nn
        val targetMt: MethodType  = descriptorToMethodType(descriptor)
        val guarded: MethodHandle = installCallbackHandler(bound, targetMt, bindingFqn, methodName, kind)
        linker.upcallStub(guarded, descriptor, arena).nn
    end wrapSpecialized

    /** Specialised upcall stub for the `V_U` shape `() => Unit`. */
    def stubShape_V_U(
        f: () => Unit,
        descriptor: FunctionDescriptor,
        arena: Arena,
        bindingFqn: String,
        methodName: String,
        kind: String
    ): MemorySegment =
        wrapSpecialized("V_U", f, descriptor, arena, bindingFqn, methodName, kind)

    /** Specialised upcall stub for the `I_U` shape `Int => Unit`. */
    def stubShape_I_U(
        f: Int => Unit,
        descriptor: FunctionDescriptor,
        arena: Arena,
        bindingFqn: String,
        methodName: String,
        kind: String
    ): MemorySegment =
        wrapSpecialized("I_U", f, descriptor, arena, bindingFqn, methodName, kind)

    /** Specialised upcall stub for the `I_I` shape `Int => Int`. */
    def stubShape_I_I(
        f: Int => Int,
        descriptor: FunctionDescriptor,
        arena: Arena,
        bindingFqn: String,
        methodName: String,
        kind: String
    ): MemorySegment =
        wrapSpecialized("I_I", f, descriptor, arena, bindingFqn, methodName, kind)

    /** Specialised upcall stub for the `II_I` shape `(Int, Int) => Int` (qsort-style comparator). */
    def stubShape_II_I(
        f: (Int, Int) => Int,
        descriptor: FunctionDescriptor,
        arena: Arena,
        bindingFqn: String,
        methodName: String,
        kind: String
    ): MemorySegment =
        wrapSpecialized("II_I", f, descriptor, arena, bindingFqn, methodName, kind)

    /** Specialised upcall stub for the `JJ_I` shape `(Long, Long) => Int`. */
    def stubShape_JJ_I(
        f: (Long, Long) => Int,
        descriptor: FunctionDescriptor,
        arena: Arena,
        bindingFqn: String,
        methodName: String,
        kind: String
    ): MemorySegment =
        wrapSpecialized("JJ_I", f, descriptor, arena, bindingFqn, methodName, kind)

    /** Specialised upcall stub for the `P_U` shape `MemorySegment => Unit` (pointer callback). */
    def stubShape_P_U(
        f: MemorySegment => Unit,
        descriptor: FunctionDescriptor,
        arena: Arena,
        bindingFqn: String,
        methodName: String,
        kind: String
    ): MemorySegment =
        wrapSpecialized("P_U", f, descriptor, arena, bindingFqn, methodName, kind)

    /** Specialised upcall stub for the `PI_U` shape `(MemorySegment, Int) => Unit`. */
    def stubShape_PI_U(
        f: (MemorySegment, Int) => Unit,
        descriptor: FunctionDescriptor,
        arena: Arena,
        bindingFqn: String,
        methodName: String,
        kind: String
    ): MemorySegment =
        wrapSpecialized("PI_U", f, descriptor, arena, bindingFqn, methodName, kind)

    /** Specialised upcall stub for the `J_J` shape `Long => Long`. */
    def stubShape_J_J(
        f: Long => Long,
        descriptor: FunctionDescriptor,
        arena: Arena,
        bindingFqn: String,
        methodName: String,
        kind: String
    ): MemorySegment =
        wrapSpecialized("J_J", f, descriptor, arena, bindingFqn, methodName, kind)

    /** Specialised upcall stub for the `J_U` shape `Long => Unit`. */
    def stubShape_J_U(
        f: Long => Unit,
        descriptor: FunctionDescriptor,
        arena: Arena,
        bindingFqn: String,
        methodName: String,
        kind: String
    ): MemorySegment =
        wrapSpecialized("J_U", f, descriptor, arena, bindingFqn, methodName, kind)

    /** Specialised upcall stub for the `D_D` shape `Double => Double`. */
    def stubShape_D_D(
        f: Double => Double,
        descriptor: FunctionDescriptor,
        arena: Arena,
        bindingFqn: String,
        methodName: String,
        kind: String
    ): MemorySegment =
        wrapSpecialized("D_D", f, descriptor, arena, bindingFqn, methodName, kind)

    /** Specialised upcall stub for the `II_U` shape `(Int, Int) => Unit`. */
    def stubShape_II_U(
        f: (Int, Int) => Unit,
        descriptor: FunctionDescriptor,
        arena: Arena,
        bindingFqn: String,
        methodName: String,
        kind: String
    ): MemorySegment =
        wrapSpecialized("II_U", f, descriptor, arena, bindingFqn, methodName, kind)

    /** Specialised upcall stub for the `JJ_J` shape `(Long, Long) => Long`. */
    def stubShape_JJ_J(
        f: (Long, Long) => Long,
        descriptor: FunctionDescriptor,
        arena: Arena,
        bindingFqn: String,
        methodName: String,
        kind: String
    ): MemorySegment =
        wrapSpecialized("JJ_J", f, descriptor, arena, bindingFqn, methodName, kind)

    /** `scala.FunctionN` interfaces by arity, indexed 0..22. */
    private val FunctionClasses: Array[Class[?]] = Array(
        classOf[scala.Function0[?]],
        classOf[scala.Function1[?, ?]],
        classOf[scala.Function2[?, ?, ?]],
        classOf[scala.Function3[?, ?, ?, ?]],
        classOf[scala.Function4[?, ?, ?, ?, ?]],
        classOf[scala.Function5[?, ?, ?, ?, ?, ?]],
        classOf[scala.Function6[?, ?, ?, ?, ?, ?, ?]],
        classOf[scala.Function7[?, ?, ?, ?, ?, ?, ?, ?]],
        classOf[scala.Function8[?, ?, ?, ?, ?, ?, ?, ?, ?]],
        classOf[scala.Function9[?, ?, ?, ?, ?, ?, ?, ?, ?, ?]],
        classOf[scala.Function10[?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?]],
        classOf[scala.Function11[?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?]],
        classOf[scala.Function12[?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?]],
        classOf[scala.Function13[?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?]],
        classOf[scala.Function14[?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?]],
        classOf[scala.Function15[?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?]],
        classOf[scala.Function16[?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?]],
        classOf[scala.Function17[?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?]],
        classOf[scala.Function18[?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?]],
        classOf[scala.Function19[?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?]],
        classOf[scala.Function20[?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?]],
        classOf[scala.Function21[?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?]],
        classOf[scala.Function22[?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?]]
    )

    /** Common bridge core.
      *
      *   - Resolves `FunctionN.apply(Object...)Object` on the `scala.FunctionN` interface (NOT the synthetic lambda subclass, which
      *     `publicLookup()` cannot access).
      *   - Binds `f` as the receiver (so the handle loses its leading `FunctionN` parameter).
      *   - `asType`-coerces the bound handle to the primitive [[MethodType]] derived from [[descriptor]].
      *   - Registers the coerced handle as an upcall stub tied to `arena`.
      *
      * `arena` governs the stub's lifetime: closing the arena invalidates the returned `MemorySegment`. The Linker keeps a strong reference
      * to the coerced handle (which transitively retains `f`), so the Scala function value cannot be GC'd while the stub is live.
      */
    private def wrap(
        f: AnyRef,
        arity: Int,
        descriptor: FunctionDescriptor,
        arena: Arena,
        bindingFqn: String,
        methodName: String,
        kind: String
    ): MemorySegment =
        if arity < 0 || arity >= FunctionClasses.length then
            throw new IllegalArgumentException(s"UpcallBridge: arity $arity is out of range [0, ${FunctionClasses.length - 1}]")
        val applyMt: MethodType   = MethodType.genericMethodType(arity).nn
        val fnCls: Class[?]       = FunctionClasses(arity)
        val mh: MethodHandle      = MethodHandles.publicLookup().nn.findVirtual(fnCls, "apply", applyMt).nn
        val bound: MethodHandle   = mh.bindTo(f).nn
        val targetMt: MethodType  = descriptorToMethodType(descriptor)
        val coerced: MethodHandle = bound.asType(targetMt).nn
        // Wrap with a Throwable handler so exceptions never propagate into C.
        // `MethodHandles.catchException(target, Throwable, handler)` requires `handler`'s type to be
        // `(Throwable, argTypes...) -> R`; the handler logs via FfiErrors.reportCallbackFailed and
        // returns the typed-zero value for the descriptor's return type.
        val guarded: MethodHandle = installCallbackHandler(coerced, targetMt, bindingFqn, methodName, kind)
        linker.upcallStub(guarded, descriptor, arena).nn
    end wrap

    /** Compose [[target]] with a `catchException` handler that reports the failure and returns a typed-zero default. See
      * [[FfiErrors.callbackFailed]] for the diagnostic wording.
      *
      * Panama rejects exceptions that escape an upcall stub by terminating the JVM (or, on older JDKs, corrupting the native frame). Every
      * generated callback must therefore funnel through this shim, see README "Callback exception handling".
      */
    private def installCallbackHandler(
        target: MethodHandle,
        targetMt: MethodType,
        bindingFqn: String,
        methodName: String,
        kind: String
    ): MethodHandle =
        val retCls  = targetMt.returnType().nn
        val paramCs = targetMt.parameterArray().nn
        // `object UpcallBridge` compiles to class `UpcallBridge$`. The package-private static-entry
        // methods below live on that class; findStatic needs that exact class handle.
        val moduleCls: Class[?] = UpcallBridge.getClass.nn
        // Base handler: (Throwable) -> R returning the typed-zero for R. The returned type on the Scala
        // side is `scala.Unit`, but on the JVM bytecode the void-returning helper is `V` (primitive).
        // MethodHandles expects the JVM descriptor return, so we pick the primitive-return class.
        val retForHandler: Class[?] =
            if retCls.asInstanceOf[AnyRef].eq(classOf[Unit].asInstanceOf[AnyRef]) then java.lang.Void.TYPE.nn
            else retCls
        val baseMt: MethodType = MethodType.methodType(retForHandler, classOf[Throwable]).nn
        // `object UpcallBridge` lowers to a non-static class (`UpcallBridge$`); the helper methods are
        // virtual. We bind the singleton instance so the resulting MethodHandle has the static-shaped
        // signature `(Throwable) -> R` that `catchException` requires.
        val baseHandler: MethodHandle = MethodHandles.lookup().nn.findVirtual(
            moduleCls,
            zeroHandlerName(retCls),
            baseMt
        ).nn.bindTo(UpcallBridge).nn
        // Bind the binding-context strings as leading args to a reporter handle. Reporter signature:
        // (Throwable, String, String, String) -> void.
        val reporterFull: MethodHandle = MethodHandles.lookup().nn.findVirtual(
            moduleCls,
            "reportCallback",
            MethodType.methodType(java.lang.Void.TYPE, classOf[Throwable], classOf[String], classOf[String], classOf[String])
        ).nn.bindTo(UpcallBridge).nn
        val reporter: MethodHandle = MethodHandles.insertArguments(reporterFull, 1, bindingFqn, methodName, kind).nn
        // Compose reporter with baseHandler so the handler reports THEN returns the zero default. Both
        // start with Throwable; `foldArguments(target, 0, combiner)` applies `combiner(args0..N-1)` then
        // calls target with (<combinerResult>, args0..N-1). We want: reporter(t); baseHandler(t). Model it
        // with `collectArguments`, attach reporter as a "consume-and-discard" side effect that precedes
        // baseHandler by pairing them via MethodHandles.filterReturnValue of a void combiner. Simpler:
        // use `MethodHandles.foldArguments(baseHandler, reporter)`, `foldArguments(target, combiner)`
        // invokes `combiner(args)` first, then passes its result (Unit here, empty) plus the args to
        // target. Since reporter returns void it feeds nothing into baseHandler, matching baseHandler's
        // (Throwable) -> R signature.
        val composed: MethodHandle = MethodHandles.foldArguments(baseHandler, reporter).nn
        // Extend handler's arg list to (Throwable, argTypes...) by dropping the extra args.
        val fullHandler: MethodHandle =
            if paramCs.length == 0 then composed
            else MethodHandles.dropArguments(composed, 1, paramCs*).nn
        MethodHandles.catchException(target, classOf[Throwable], fullHandler).nn
    end installCallbackHandler

    /** Name of the static zero-return handler in this class whose [[java.lang.invoke.MethodType]] is `(Throwable) -> R` for the given
      * return class [[retCls]]. Must match one of the `zero<Kind>` methods defined below.
      */
    private def zeroHandlerName(retCls: Class[?]): String =
        // Scala 3 rejects `classOf[Prim] == classOf[Prim]` as heterogeneous, use reference equality with
        // `.asInstanceOf[AnyRef].eq(...)`. The underlying classes are JVM singletons so `eq` is correct.
        val r = retCls.asInstanceOf[AnyRef]
        if r.eq(classOf[Unit].asInstanceOf[AnyRef]) || r.eq(java.lang.Void.TYPE.asInstanceOf[AnyRef]) then "zeroVoid"
        else if r.eq(classOf[Byte].asInstanceOf[AnyRef]) || r.eq(java.lang.Byte.TYPE.asInstanceOf[AnyRef]) then "zeroByte"
        else if r.eq(classOf[Short].asInstanceOf[AnyRef]) || r.eq(java.lang.Short.TYPE.asInstanceOf[AnyRef]) then "zeroShort"
        else if r.eq(classOf[Int].asInstanceOf[AnyRef]) || r.eq(java.lang.Integer.TYPE.asInstanceOf[AnyRef]) then "zeroInt"
        else if r.eq(classOf[Long].asInstanceOf[AnyRef]) || r.eq(java.lang.Long.TYPE.asInstanceOf[AnyRef]) then "zeroLong"
        else if r.eq(classOf[Float].asInstanceOf[AnyRef]) || r.eq(java.lang.Float.TYPE.asInstanceOf[AnyRef]) then "zeroFloat"
        else if r.eq(classOf[Double].asInstanceOf[AnyRef]) || r.eq(java.lang.Double.TYPE.asInstanceOf[AnyRef]) then "zeroDouble"
        else if r.eq(classOf[Boolean].asInstanceOf[AnyRef]) || r.eq(java.lang.Boolean.TYPE.asInstanceOf[AnyRef]) then "zeroBoolean"
        else if r.eq(classOf[Char].asInstanceOf[AnyRef]) || r.eq(java.lang.Character.TYPE.asInstanceOf[AnyRef]) then "zeroChar"
        else if r.eq(classOf[MemorySegment].asInstanceOf[AnyRef]) then "zeroPointer"
        else throw new IllegalArgumentException(s"UpcallBridge: no zero-return handler for $retCls")
        end if
    end zeroHandlerName

    /** Static reporter entry: keeps a stable [[java.lang.invoke.MethodType]] so the [[installCallbackHandler]] `insertArguments` chain
      * doesn't need platform-specific reflection. Intentionally package-private so it survives Scala 3's `object` lowering to a `MODULE$`
      * static init.
      */
    private[internal] def reportCallback(t: Throwable, bindingFqn: String, methodName: String, kind: String): Unit =
        FfiGenErrors.reportCallbackFailed(bindingFqn, methodName, kind, t)

    private[internal] def zeroVoid(t: Throwable): Unit             = ()
    private[internal] def zeroByte(t: Throwable): Byte             = 0.toByte
    private[internal] def zeroShort(t: Throwable): Short           = 0.toShort
    private[internal] def zeroInt(t: Throwable): Int               = 0
    private[internal] def zeroLong(t: Throwable): Long             = 0L
    private[internal] def zeroFloat(t: Throwable): Float           = 0.0f
    private[internal] def zeroDouble(t: Throwable): Double         = 0.0
    private[internal] def zeroBoolean(t: Throwable): Boolean       = false
    private[internal] def zeroChar(t: Throwable): Char             = 0.toChar
    private[internal] def zeroPointer(t: Throwable): MemorySegment = MemorySegment.NULL.nn

    /** Map a [[java.lang.foreign.FunctionDescriptor]] back to a primitive-typed [[java.lang.invoke.MethodType]]. */
    private def descriptorToMethodType(d: FunctionDescriptor): MethodType =
        val retCls: Class[?] = d.returnLayout().nn.orElse(null) match
            case null            => classOf[Unit]
            case vl: ValueLayout => layoutClass(vl)
            case other =>
                throw new IllegalArgumentException(s"UpcallBridge: unsupported callback return layout: $other")
        val argLayouts                = d.argumentLayouts().nn
        val argClses: Array[Class[?]] = new Array[Class[?]](argLayouts.size())
        var i                         = 0
        while i < argLayouts.size() do
            argLayouts.get(i) match
                case vl: ValueLayout =>
                    argClses(i) = layoutClass(vl)
                case other =>
                    throw new IllegalArgumentException(s"UpcallBridge: unsupported callback parameter layout: $other")
            end match
            i += 1
        end while
        MethodType.methodType(retCls, argClses).nn
    end descriptorToMethodType

    /** Map a [[ValueLayout]] to the JVM `Class` it is laid out as. */
    private def layoutClass(l: ValueLayout): Class[?] = l match
        case _: ValueLayout.OfByte    => classOf[Byte]
        case _: ValueLayout.OfShort   => classOf[Short]
        case _: ValueLayout.OfInt     => classOf[Int]
        case _: ValueLayout.OfLong    => classOf[Long]
        case _: ValueLayout.OfFloat   => classOf[Float]
        case _: ValueLayout.OfDouble  => classOf[Double]
        case _: ValueLayout.OfBoolean => classOf[Boolean]
        case _: ValueLayout.OfChar    => classOf[Char]
        case _: AddressLayout         => classOf[MemorySegment]
        case other =>
            throw new IllegalArgumentException(s"UpcallBridge: unsupported callback value layout: $other")
end UpcallBridge
