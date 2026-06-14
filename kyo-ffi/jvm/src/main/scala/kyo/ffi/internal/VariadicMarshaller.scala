package kyo.ffi.internal

import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemoryLayout
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout.*
import java.lang.invoke.MethodHandle
import kyo.ffi.Buffer
import kyo.ffi.FfiUnsupported

/** Runtime support for variadic FFI downcalls on JVM.
  *
  * C variadic functions (`int printf(const char *fmt, ...)`) cannot use a cached fixed-arity `MethodHandle`: the Scala-side `args: Any*`
  * carries per-call shape, so the generator emits a call that builds the descriptor on the fly. This object performs that build and
  * invocation atomically.
  *
  * Supported varargs runtime types (v1):
  *   - `java.lang.Integer` / boxed Scala `Int` → `JAVA_INT`
  *   - `java.lang.Long` / boxed Scala `Long` → `JAVA_LONG`
  *   - `java.lang.Double` / boxed Scala `Double` → `JAVA_DOUBLE`
  *   - `String` → UTF-8 [[MemorySegment]] allocated in the supplied scratch
  *   - `Buffer[A]` → the buffer's backing `MemorySegment` (zero-copy borrow)
  *
  * Any other runtime type raises [[FfiUnsupported]] naming the binding + method + runtime class; the user either casts to a supported boxed
  * type or wraps the value in a `Buffer[A]`.
  *
  * Fidelity rationale: C's default-argument-promotion rules promote `float` to `double`, `short`/`char` to `int`, and `Boolean` is not a
  * legal variadic type, matching that semantics at the Scala boundary would be surprising, so we only accept types that already match a
  * C-level variadic-legal category and let the compiler reject the rest with a clear runtime error.
  */
object VariadicMarshaller:

    /** Invoke a C variadic function by name. Caches nothing, each call pays one `Linker.downcallHandle` + per-arg classification. Hot-path
      * use is not the target, variadic is inherently the fallback shape when a typed binding is impossible.
      *
      * @param lib
      *   the native library already loaded by the binding's generated impl.
      * @param symbol
      *   the C symbol name to look up.
      * @param errnoSeg
      *   scratch-allocated errno capture segment; the same `Linker.Option.captureCallState("errno")` used by fixed-arity methods.
      * @param retLayout
      *   `MemoryLayout` for the C return, or `null` for `void` / address returns. Pass `null` to use `ofVoid`; pass an `ADDRESS` for
      *   returns whose top-level handling (borrowed string/buffer) happens on the caller side.
      * @param fixedLayouts
      *   layouts for the fixed (non-variadic) parameters in declaration order.
      * @param fixedArgs
      *   values for the fixed parameters, already marshalled (e.g. String → `MemorySegment`, Buffer.Raw → `MemorySegment`).
      * @param args
      *   runtime varargs, classified per-value against the supported set above.
      * @param scratch
      *   per-thread scratch the marshaller uses to allocate transient UTF-8 segments for String varargs.
      * @param bindingFqn
      *   binding trait FQN, for error attribution.
      * @param methodName
      *   method name on the trait, for error attribution.
      * @return
      *   the raw return from `invokeExact`, callers cast to the expected Scala type.
      */
    def invoke(
        lib: SymbolLookup,
        symbol: String,
        errnoSeg: MemorySegment,
        retLayout: MemoryLayout | Null,
        fixedLayouts: List[MemoryLayout],
        fixedArgs: List[Any],
        firstVariadic: Linker.Option,
        args: Seq[Any],
        scratch: Scratch.Scratch,
        bindingFqn: String,
        methodName: String
    ): Any =
        val linker  = Linker.nativeLinker()
        val capture = Linker.Option.captureCallState("errno")

        // Classify each vararg to its (layout, marshalled-value) pair.
        val varargLayouts = List.newBuilder[MemoryLayout]
        val varargValues  = List.newBuilder[Any]
        args.foreach { v =>
            val (layout, marshalled) = classify(v, scratch, bindingFqn, methodName)
            varargLayouts += layout
            varargValues += marshalled
        }
        val vLayouts = varargLayouts.result()
        val vValues  = varargValues.result()

        val allLayouts = (fixedLayouts ++ vLayouts)
        val descriptor = retLayout match
            case null             => FunctionDescriptor.ofVoid(allLayouts*)
            case rl: MemoryLayout => FunctionDescriptor.of(rl, allLayouts*)

        // Panama signals variadic call convention via `firstVariadicArg(fixedCount)`, the emitter passes
        // the platform-appropriate `Linker.Option.firstVariadicArg(fixed.size)` verbatim.
        val addr             = lib.find(symbol).orElseThrow()
        val mh: MethodHandle = linker.downcallHandle(addr, descriptor, capture, firstVariadic)

        // Thread together errnoSeg + all marshalled values for invokeExact. Panama's polymorphic-signature
        // invocation requires a single array-args form at the call site, pass `Any*`.
        val invokeArgs = (errnoSeg :: (fixedArgs ++ vValues)).toArray.asInstanceOf[Array[AnyRef | Null]]
        val result     = mh.invokeWithArguments(invokeArgs*)
        // Errno is captured in errnoSeg by Panama, the caller reads it for WithError returns.
        result
    end invoke

    /** Classify one vararg value to `(ValueLayout, marshalled-value)`. String values allocate a UTF-8 segment in `scratch`; Buffers unwrap
      * their raw `MemorySegment`. Primitives box through java.lang.* wrappers, Panama unboxes them at the `invokeWithArguments` edge.
      */
    private def classify(
        v: Any,
        scratch: Scratch.Scratch,
        bindingFqn: String,
        methodName: String
    ): (MemoryLayout, Any) =
        if v.asInstanceOf[AnyRef] eq null then
            throw new FfiUnsupported(FfiGenErrors.unsupportedVararg(bindingFqn, methodName, "null"))
        else
            v match
                case i: java.lang.Integer => (JAVA_INT, i)
                case l: java.lang.Long    => (JAVA_LONG, l)
                case d: java.lang.Double  => (JAVA_DOUBLE, d)
                case s: String =>
                    val seg = scratch.allocUtf8(s, bindingFqn, methodName)
                    (ADDRESS, seg)
                case b: Buffer[?] =>
                    val raw = b.raw.asInstanceOf[AnyRef]
                    val seg = FfiUnsafe.expect[MemorySegment](
                        raw,
                        classOf[MemorySegment],
                        "MemorySegment on JVM",
                        bindingFqn,
                        methodName
                    )
                    (ADDRESS, seg)
                case other =>
                    throw new FfiUnsupported(FfiGenErrors.unsupportedVararg(bindingFqn, methodName, other.getClass.getName))
            end match
        end if
    end classify
end VariadicMarshaller
