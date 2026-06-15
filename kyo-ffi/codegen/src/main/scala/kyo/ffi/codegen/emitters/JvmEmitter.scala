package kyo.ffi.codegen.emitters

import kyo.ffi.codegen.model.*

/** Emits the JVM (Panama) implementation source for a single binding trait.
  *
  * Given a [[kyo.ffi.codegen.model.TraitSpec]] extracted from TASTy, [[JvmEmitter.emit]] returns the full contents of
  * `{TraitName}Impl.scala`, an `AbiCheck`-guarded implementation class plus a companion object holding the `MethodHandle` cache and shared
  * `Linker.Option` constants.
  *
  * The generator follows the mapping table for `Linker.Option` lists:
  *
  *   - `@Ffi.blocking` = no, array in signature = no → `capture, critOff`
  *   - `@Ffi.blocking` = no, array in signature = yes → `capture, critOn`
  *   - `@Ffi.blocking` = yes, array in signature = no → `capture` (normal downcall, implicit safepoint)
  *   - `@Ffi.blocking` = yes, array in signature = yes → `capture` (array copied into scratch before call)
  *
  * Callback emission handles both transient and retained callbacks. Transient callbacks allocate a per-call `Arena.ofConfined()` that is
  * closed in the finally block after scratch reset. Retained callbacks use the guard's shared arena so the stub lives until guard.close().
  */
object JvmEmitter extends EmitterBase.Ops with PlatformTypes:

    /** Emit the full contents of `{spec.simpleName}Impl.scala`. */
    def emit(spec: TraitSpec): String =
        val sb = new StringBuilder
        sb ++= emitHeader(spec)
        sb ++= "\n"
        sb ++= emitImplClass(spec)
        sb ++= "\n"
        sb ++= emitImplCompanion(spec)
        sb.toString
    end emit

    // -------------------------------------------------------------------------
    // Header
    // -------------------------------------------------------------------------

    private[codegen] def emitHeader(spec: TraitSpec): String =
        emitHeader(
            spec,
            """import java.lang.foreign.*
              |import java.lang.foreign.ValueLayout.*
              |import java.lang.invoke.MethodHandle
              |import kyo.ffi.*
              |import kyo.ffi.internal.{AbiCheck, NativeLoader, Scratch, Layouts, StructAbiCheck, UpcallBridge}
              |""".stripMargin
        )
    end emitHeader

    // -------------------------------------------------------------------------
    // Impl class
    // -------------------------------------------------------------------------

    private[codegen] def emitImplClass(spec: TraitSpec): String =
        val sb = new StringBuilder
        sb ++= s"final class ${spec.simpleName}Impl extends ${spec.simpleName}:\n"
        sb ++= s"    import ${spec.simpleName}Impl.*\n"
        spec.methods.foreach { m =>
            sb ++= "\n"
            sb ++= emitMethod(m, spec)
        }
        sb ++= s"end ${spec.simpleName}Impl\n"
        sb.toString
    end emitImplClass

    private[codegen] def emitMethod(method: MethodSpec, spec: TraitSpec): String =
        // Variadic bindings append a trailing `args: Any*` to the Scala-level signature.
        val sig = methodSignature(method, includeVarargs = true)
        // Indent body 8 spaces (method body sits two indentation levels deep inside the class).
        val body = indentBody(emitMethodBody(method, spec))
        sig + "\n" + body + "\n"
    end emitMethod

    /** Emit the body (including the `val __kyoScratch$ = Scratch.current` / try-finally wrapping when needed).
      *
      * When `method.blocking`, the synchronous body is wrapped in a `kyo.ffi.internal.BlockingBridge.run { ... }` block
      * so the already-computed result is lifted into `kyo.Fiber.Unsafe[<ret>, Any]` (matching the signature emitted by
      * `methodSignature`). The blocking downcall itself stays synchronous on the carrier thread; the `Linker.Option`
      * matrix and array marshalling are unchanged.
      */
    private[codegen] def emitMethodBody(method: MethodSpec, spec: TraitSpec): String =
        val sync =
            if method.hasVarargs then emitVariadicMethodBody(method, spec)
            else emitPlainMethodBody(method, spec)
        if method.blocking then wrapBlockingRun(sync) else sync
    end emitMethodBody

    /** Method body, used for all callback kinds (None, Transient, Retained).
      *
      * Transient callbacks allocate a per-call `Arena.ofConfined()` closed in the finally block after scratch reset; retained callbacks
      * read the arena from the user-supplied `Ffi.Guard` via `JvmGuard.unsafeArena` so the stub lifetime is owned by the guard.
      */
    private def emitPlainMethodBody(method: MethodSpec, spec: TraitSpec): String =
        val structsByName = EmitterBase.structsByName(spec)
        // Binding/method context threaded into every spill-capable alloc site so `FfiErrors.scratchSpilled` names the
        // call origin in the default-off `-Dkyo.ffi.scratch.logSpills=true` diagnostic.
        val fqnLit    = s""""${spec.fqcn}""""
        val methodLit = s""""${method.scalaName}""""

        // Collect per-parameter marshal plan.
        case class Marshalled(
            paramName: String,  // original Scala name
            passExpr: String,   // expression passed to invokeExact for this parameter
            setup: List[String] // setup lines before the invoke (marshalling)
        )

        // Callback parameters are rendered as upcall stubs; `Ffi.Guard` is consumed locally (provides the arena for retained callbacks)
        // and not passed to C. All other params marshal through the same code paths used for callback-free methods.
        val marshalled: List[Marshalled] = method.params.flatMap { p =>
            val name = safeName(p.name)
            p.tpe match
                case TypeRef.BooleanT =>
                    List(Marshalled(p.name, s"if $name then 1 else 0", Nil))
                case TypeRef.ByteT | TypeRef.ShortT | TypeRef.IntT | TypeRef.LongT | TypeRef.FloatT | TypeRef.DoubleT =>
                    List(Marshalled(p.name, name, Nil))
                case TypeRef.UnitT =>
                    List(Marshalled(p.name, "()", Nil))
                case TypeRef.StringT =>
                    val segN = s"${name}Seg"
                    List(Marshalled(p.name, segN, List(s"val $segN = __kyoScratch$$.allocUtf8($name, $fqnLit, $methodLit)")))
                case TypeRef.BufferT(_) =>
                    val segN = s"${name}Seg"
                    // `Buffer.Raw` erases to `AnyRef`; the concrete carrier on JVM is `MemorySegment`.
                    // Route through `FfiUnsafe.expect` so an alien `Buffer.Raw` produces a diagnostic
                    // naming the binding + method + expected type. The shared
                    // `BufferFactory.alloc` only ever produces MemorySegment-backed `JvmBuffer`, so
                    // well-typed code never hits the throw path, the check is defensive against
                    // hand-constructed `Buffer.Raw`.
                    List(Marshalled(
                        p.name,
                        segN,
                        List(
                            s"val $segN = kyo.ffi.internal.FfiUnsafe.expect[MemorySegment](" +
                                s"""$name.raw.asInstanceOf[AnyRef], classOf[MemorySegment], "MemorySegment on JVM", "${spec.fqcn}", "${method.scalaName}")"""
                        )
                    ))
                case TypeRef.ArrayT(elem) =>
                    val segN = s"${name}Seg"
                    if method.blocking then
                        // Copy Array into scratch. After the R2 Layout unification the write target is
                        // the shared Buffer.Raw opaque; `.asInstanceOf[Buffer.Raw]` on the MemorySegment
                        // is a no-op at runtime (Raw erases to AnyRef) and is amortized by JIT.
                        val bps   = primitiveBytes(elem)
                        val opsId = layoutOpsFor(elem)
                        val rawN  = s"${name}Raw"
                        val setup = List(
                            s"val ${name}Bps = ${bps}L",
                            s"val $segN = __kyoScratch$$.alloc($name.length.toLong * ${name}Bps, ${name}Bps, $fqnLit, $methodLit)",
                            s"val $rawN = $segN.asInstanceOf[Buffer.Raw]",
                            s"var ${name}I = 0",
                            s"while ${name}I < $name.length do",
                            s"    $opsId.write($rawN, ${name}I.toLong * ${name}Bps, $name(${name}I))",
                            s"    ${name}I += 1"
                        )
                        List(Marshalled(p.name, segN, setup))
                    else
                        // Zero-copy pin via ofArray + critical(true).
                        List(Marshalled(p.name, segN, List(s"val $segN = MemorySegment.ofArray($name)")))
                    end if
                case TypeRef.StructT(fqcn) =>
                    val sSpec = structsByName.getOrElse(
                        fqcn,
                        throw new IllegalStateException(s"struct '$fqcn' not found in trait ${spec.fqcn}")
                    )
                    val segN = s"${name}Seg"
                    val setup =
                        s"val $segN = __kyoScratch$$.alloc(${layoutConst(sSpec)}.byteSize(), ${layoutConst(sSpec)}.byteAlignment(), $fqnLit, $methodLit)" ::
                            emitStructWrite(name, segN, sSpec, structsByName, "0L", spec.fqcn, method.scalaName)
                    List(Marshalled(p.name, segN, setup))
                case TypeRef.UnionT(variants) =>
                    val segN  = s"${name}Seg"
                    val setup = emitUnionParamWrite(name, segN, variants, structsByName, spec.fqcn, method.scalaName)
                    List(Marshalled(p.name, segN, setup))
                case TypeRef.HandleT(_) =>
                    // Handle[A] pointer: unwrap to get the platform carrier (MemorySegment on JVM).
                    val segN = s"${name}Seg"
                    List(Marshalled(
                        p.name,
                        segN,
                        List(
                            s"val $segN = kyo.ffi.internal.FfiUnsafe.expect[MemorySegment](" +
                                s"""Ffi.Handle.unwrap($name).asInstanceOf[AnyRef], classOf[MemorySegment], "MemorySegment on JVM", "${spec.fqcn}", "${method.scalaName}")"""
                        )
                    ))
                case TypeRef.EnumT(_) =>
                    // Enum parameter: extract the underlying Int via `.value` and pass as JAVA_INT.
                    List(Marshalled(p.name, s"$name.value", Nil))
                case TypeRef.FnPtrT(params, ret) =>
                    // Upcall stub construction. The per-param FunctionDescriptor is emitted as a val in the companion (see
                    // `emitCallbackDescriptor`) under a name shared with the emitted stub. Tag args (bindingFqn / methodName / kind)
                    // thread the callback site identity to `UpcallBridge.installCallbackHandler` so exceptions caught inside C
                    // name their origin.
                    //
                    // Callbacks matching one of the 12 catalog shapes dispatch to the primitive-typed
                    // `UpcallBridge.stubShape_<SHAPE>` entry point that avoids the generic `MethodHandle.asType` box/unbox
                    // adapter chain. Non-catalog shapes fall back to the generic `stubN` path.
                    //
                    // Transient callbacks allocate a per-call `Arena.ofConfined()` that is closed in finally.
                    // Retained callbacks use `cbArena` from the guard (unchanged).
                    val stubN  = s"${name}Stub"
                    val arity  = params.size
                    val descId = callbackDescName(method.scalaName, p.name)
                    val kindName = method.callbackKind match
                        case CallbackKind.Transient => "transient"
                        case CallbackKind.Retained  => "retained"
                        case CallbackKind.None      => "none"
                    val tagArgs = s""""${spec.fqcn}", "${method.scalaName}", "$kindName""""
                    // Only dispatch to a specialised bridge for catalog shapes whose parameters + return are
                    // strictly primitive (no Buffer / pointer). The Native-side catalog's P-prefixed shapes
                    // `P_U` and `PI_U` map pointer callbacks where the Scala callback takes a `Buffer[A]`, on
                    // JVM the Panama upcall surfaces the pointer as a `MemorySegment`, which is NOT a
                    // `Buffer[A]` at the Scala type level, so routing through a specialised `MemorySegment =>
                    // Unit` helper would break the user-facing callback signature. Those shapes fall through to
                    // the generic `stubN` path, which preserves the existing behaviour.
                    val jvmSafeShape = NativeCallbackCatalog.shapeId(params, ret).filter { id =>
                        // Pointer-free shape: every param + return is a primitive or Unit.
                        def isPrimOrUnit(t: TypeRef): Boolean = t match
                            case TypeRef.BooleanT | TypeRef.ByteT | TypeRef.ShortT | TypeRef.IntT |
                                TypeRef.LongT | TypeRef.FloatT | TypeRef.DoubleT | TypeRef.UnitT => true
                            case _ => false
                        params.forall(isPrimOrUnit) && isPrimOrUnit(ret)
                    }
                    val stubCall = method.callbackKind match
                        case CallbackKind.Transient =>
                            // Per-call Arena: allocate a confined arena for the stub; closed in finally.
                            jvmSafeShape match
                                case Some(id) =>
                                    s"val $stubN = UpcallBridge.stubShape_$id($name, $descId, cbArena, $tagArgs)"
                                case None =>
                                    s"val $stubN = UpcallBridge.stub$arity($name, $descId, cbArena, $tagArgs)"
                        case _ =>
                            // Retained: use cbArena from the guard (unchanged).
                            jvmSafeShape match
                                case Some(id) =>
                                    s"val $stubN = UpcallBridge.stubShape_$id($name, $descId, cbArena, $tagArgs)"
                                case None =>
                                    s"val $stubN = UpcallBridge.stub$arity($name, $descId, cbArena, $tagArgs)"
                    List(Marshalled(p.name, stubN, List(stubCall)))
                case TypeRef.GuardT =>
                    // Guard never crosses the FFI boundary; it is consumed locally to get the arena. Drop from the invoke args.
                    Nil
            end match
        }

        // Out-param segments for MultiValue returns.
        //
        // The trailing out-params are coalesced into a single scratch allocation sized to the sum of per-field
        // bytes with natural alignment padding; each out-param is exposed as a zero-copy `asSlice(offset, size)`
        // view anchored in that single block. Keeps scratch-allocator pressure at O(1) per call regardless of
        // trailing-field count.
        case class OutSeg(name: String, tpe: TypeRef, alignBytes: Long, sizeBytes: Long, offsetBytes: Long, readExpr: String => String)
        val outSegs: List[OutSeg] = method.returnShape match
            case ReturnShape.MultiValue(sSpec) =>
                // Walk the trailing fields, tracking a running offset with natural-alignment padding. Each
                // out-param gets a `(offset, size)` slice view into the coalesced block.
                var running = 0L
                val buf     = List.newBuilder[OutSeg]
                sSpec.fields.drop(1).foreach { f =>
                    val n = s"${f.name}Out"
                    val (fAlign, fSize, readExpr) = f.tpe match
                        case TypeRef.StringT =>
                            // String out-param: C writes a `char*` pointer into an 8-byte slot. Reinterpret the address
                            // with a BOUNDED size driven by `Scratch.stringFieldMaxBytes` (default 64 KiB, tunable via
                            // -Dkyo.ffi.stringFieldMaxBytes=) and hand off to `Scratch.readCStringBounded`, which fails
                            // fast with `FfiMalformedResult` if no NUL terminator is found within the cap.
                            // Ownership stays with the callee, typical pattern is a statically allocated message
                            // string, so no free is issued on the Scala side.
                            val re: String => String = seg =>
                                s"Scratch.readCStringBounded($seg.get(ADDRESS, 0L).reinterpret(Scratch.stringFieldMaxBytes), 0L, " +
                                    s"""Scratch.stringFieldMaxBytes, "${spec.fqcn}", "${method.scalaName}", "${f.name}")"""
                            (8L, 8L, re)
                        case _ =>
                            val bytes                = primitiveBytes(f.tpe).toLong
                            val re: String => String = seg => primitiveReadExpr(f.tpe, seg, "0L")
                            (bytes, bytes, re)
                    end val
                    val off = align(running, fAlign)
                    running = off + fSize
                    buf += OutSeg(n, f.tpe, fAlign, fSize, off, readExpr)
                }
                buf.result()
            case _ => Nil

        /** Total coalesced byte-size for the multi-value out-param block. */
        val outBlockSize: Long =
            if outSegs.isEmpty then 0L
            else
                val last     = outSegs.last
                val maxAlign = outSegs.map(_.alignBytes).max
                // Round final length up to the max alignment so the block's end is aligned for reuse.
                align(last.offsetBytes + last.sizeBytes, maxAlign)

        /** Coalesced block's required alignment (= max of per-field alignments). */
        val outBlockAlign: Long =
            if outSegs.isEmpty then 1L else outSegs.map(_.alignBytes).max

        // Compose invokeExact argument list: errnoSeg first, then struct-return out (if Struct), then marshalled args, then multi-value outs.
        val returnOutForStruct: Option[String] = method.returnShape match
            case ReturnShape.Struct(sSpec) => Some(s"out")
            case _                         => None

        val invokeArgs = (List("errnoSeg") ++ returnOutForStruct.toList ++ marshalled.map(_.passExpr) ++ outSegs.map(_.name))
            .mkString(", ")

        val mhName = s"${method.scalaName}MH"

        // Return-value capture.
        // Scala 3 supports Java's @PolymorphicSignature on MethodHandle.invokeExact: when the
        // call's return type is ascribed to a specific type, the Scala compiler emits the
        // callsite with that return descriptor. To make this work, declare the val with an
        // explicit type annotation BEFORE the invocation (no trailing `.asInstanceOf`, the
        // JVM rejects the mismatched signature if it's applied after).
        val retValName = "retVal"
        val invokeLine =
            method.returnShape match
                case ReturnShape.Void =>
                    // For void return, the JVM expects descriptor `(...)V`. Scala's polymorphic
                    // signature for `invokeExact` defaults to `Object` return when the result is
                    // unused; to force void, pattern-match the result as Unit.
                    s"val _: Unit = $mhName.invokeExact($invokeArgs)"
                case ReturnShape.Primitive(t) =>
                    s"val $retValName: ${primitiveScala(t)} = $mhName.invokeExact($invokeArgs)"
                case ReturnShape.MultiValue(sSpec) =>
                    s"val $retValName: ${primitiveScala(sSpec.fields.head.tpe)} = $mhName.invokeExact($invokeArgs)"
                case ReturnShape.Struct(_) =>
                    // Struct-return uses an out-ADDRESS; C function returns void.
                    s"val _: Unit = $mhName.invokeExact($invokeArgs)"
                case ReturnShape.HandleReturn(_, _) =>
                    // Handle return. C returns a pointer (ADDRESS); wrap via Ffi.Handle.wrap.
                    s"val $retValName: MemorySegment = $mhName.invokeExact($invokeArgs)"
                case ReturnShape.EnumReturn(_) =>
                    // Enum return. C returns an int; wrap via companion's fromInt.
                    s"val $retValName: Int = $mhName.invokeExact($invokeArgs)"
                case ReturnShape.BorrowedString(_) | ReturnShape.BorrowedBuffer(_, _) =>
                    // Borrowed top-level returns. C returns a pointer (ADDRESS);
                    // the JVM MethodHandle surfaces that as a MemorySegment, which we decode into either
                    // a Scala String (readCStringBounded, copies) or wrap as a borrowed Buffer.
                    s"val $retValName: MemorySegment = $mhName.invokeExact($invokeArgs)"

        // Build the body.
        val sb = new StringBuilder

        // All methods need scratch because at minimum we allocate the errnoSeg. Per-binding scratch size override via
        // `Ffi.Config.scratchSize`: when set, the emitted code acquires a binding-specific per-thread `Scratch` sized to the override,        // a binding that routinely needs more than the global default block does not spill every call, and a small binding does not
        // carry the cost of a large block it never fills. When unset, the emitted code uses `Scratch.configuredSize`.
        val scratchSizeExpr = spec.companion.flatMap(_.scratchSize) match
            case Some(n) => s"${n}L"
            case None    => "Scratch.configuredSize"
        sb ++= s"val __kyoScratch$$ = Scratch.currentFor($fqnLit, $scratchSizeExpr)\n"
        sb ++= "val __kyoMark$ = __kyoScratch$.mark()\n"

        // Callback arena. Transient callbacks allocate a per-call `Arena.ofConfined()` closed in finally after scratch reset.
        // Retained callbacks read the arena from the user-supplied Ffi.Guard via JvmGuard.unsafeArena; the guard, not
        // this method, owns the arena's lifetime.
        //
        // Struct parameters with FnPtrT fields also require a cbArena for their upcall stubs. When the method itself
        // has no top-level callback parameter (callbackKind == None) but one of its struct params contains a FnPtrT
        // field, we emit a transient cbArena that is closed in finally.
        val hasStructFnPtrField = method.params.exists { p =>
            p.tpe match
                case TypeRef.StructT(fqcn) =>
                    structsByName.get(fqcn).exists(_.fields.exists(_.tpe.isInstanceOf[TypeRef.FnPtrT]))
                case _ => false
        }
        val needsCbArena = method.callbackKind != CallbackKind.None || hasStructFnPtrField
        method.callbackKind match
            case CallbackKind.Transient =>
                sb ++= "val cbArena = Arena.ofConfined().nn\n"
            case CallbackKind.Retained =>
                val guardParam = method.params.find(_.tpe == TypeRef.GuardT).getOrElse(
                    throw new IllegalStateException(
                        s"Retained callback method '${method.scalaName}' is missing an Ffi.Guard parameter"
                    )
                )
                sb ++= s"val cbArena = ${safeName(guardParam.name)}.asInstanceOf[kyo.ffi.internal.JvmGuard].unsafeArena\n"
            case CallbackKind.None if hasStructFnPtrField =>
                sb ++= "val cbArena = Arena.ofConfined().nn\n"
            case CallbackKind.None => ()
        end match

        sb ++= "try\n"

        val bodyInner = new StringBuilder

        // Marshal setup lines.
        marshalled.flatMap(_.setup).foreach { line =>
            bodyInner ++= line
            bodyInner ++= "\n"
        }

        // Struct out-segment (for ReturnShape.Struct).
        method.returnShape match
            case ReturnShape.Struct(sSpec) =>
                bodyInner ++=
                    s"val out = __kyoScratch$$.alloc(${layoutConst(sSpec)}.byteSize(), ${layoutConst(sSpec)}.byteAlignment(), $fqnLit, $methodLit)\n"
            case _ => ()
        end match

        // Multi-value out-segments, coalesced into a single scratch allocation.
        //
        // Allocate one block sized to the sum of per-field bytes with natural-alignment padding, and expose
        // each out-param as a zero-copy `asSlice(offset, size)` view into that block. Panama's `asSlice` does
        // not allocate native memory, it returns a wrapper bounded to the requested range, so the semantics
        // of the downstream `seg.get(JAVA_*, 0L)` / `seg.get(ADDRESS, 0L)` reads are unchanged.
        if outSegs.nonEmpty then
            bodyInner ++=
                s"val __kyoMultiOut$$ = __kyoScratch$$.alloc(${outBlockSize}L, ${outBlockAlign}L, $fqnLit, $methodLit)\n"
            outSegs.foreach { o =>
                bodyInner ++= s"val ${o.name} = __kyoMultiOut$$.asSlice(${o.offsetBytes}L, ${o.sizeBytes}L)\n"
            }
        end if

        // errno segment last (convention: errnoSeg is always passed first to invokeExact, but declaration order just needs to be before invoke).
        bodyInner ++= s"val errnoSeg = __kyoScratch$$.alloc(4L, 4L, $fqnLit, $methodLit)\n"

        // The invocation.
        bodyInner ++= invokeLine
        bodyInner ++= "\n"
        // Capture errno from the segment if this method uses WithError.
        if method.withError then
            bodyInner ++= "val __errno = errnoSeg.get(JAVA_INT, 0L)\n"

        // When the method carries a retained callback parameter AND argument marshalling spilled to fresh confined arenas
        // (oversized Buffer[Byte], string, or struct-by-value param), the spill arenas must outlive the method, the C-side retained
        // callback may reference spilled payload memory after the downcall returns. Promote the spill list to the enclosing `Ffi.Guard`
        // so the spill arenas close only when the guard closes. On the callback-free or transient-callback path, spills keep their
        // method-scoped lifetime (closed by `__kyoScratch$.reset(__kyoMark$)` in `finally`).
        //
        // Placed after the invoke (so spills covering the C call are intact for the call itself) and before result construction (so
        // result construction runs against an empty spill list, protecting against a hypothetical mid-result-build exception from
        // double-closing the promoted arenas). On an exception thrown from the invoke itself, control jumps to `finally` and the
        // spills close normally, the retained callback was never installed, so no C-side pointer references the spilled memory.
        method.callbackKind match
            case CallbackKind.Retained =>
                val guardName = method.params.find(_.tpe == TypeRef.GuardT).map(gp => safeName(gp.name)).getOrElse(
                    throw new IllegalStateException(
                        s"Retained callback method '${method.scalaName}' is missing an Ffi.Guard parameter"
                    )
                )
                bodyInner ++= "val __kyoSpills$ = __kyoScratch$.takeSpills()\n"
                bodyInner ++= "if __kyoSpills$.nonEmpty then\n"
                bodyInner ++= s"    val __kyoGuard$$ = $guardName.asInstanceOf[kyo.ffi.internal.JvmGuard]\n"
                bodyInner ++= "    var __kyoSpillsI$ = __kyoSpills$\n"
                bodyInner ++= "    while __kyoSpillsI$.nonEmpty do\n"
                bodyInner ++= "        __kyoGuard$.adoptArena(__kyoSpillsI$.head)\n"
                bodyInner ++= "        __kyoSpillsI$ = __kyoSpillsI$.tail\n"
            case _ => ()
        end match

        // Result construction, compute the inner result expression, then wrap with errno handling.
        val resultExpr: String = method.returnShape match
            case ReturnShape.Void =>
                "()"
            case ReturnShape.Primitive(TypeRef.BooleanT) =>
                s"$retValName != 0"
            case ReturnShape.Primitive(_) =>
                retValName
            case ReturnShape.MultiValue(sSpec) =>
                val head = sSpec.fields.head
                val headExpr =
                    head.tpe match
                        case TypeRef.BooleanT => s"$retValName != 0"
                        case _                => retValName
                val tailExprs = sSpec.fields.drop(1).zip(outSegs).map { case (_, o) =>
                    o.readExpr(o.name)
                }
                s"${sSpec.fqcn}(${(headExpr :: tailExprs).mkString(", ")})"
            case ReturnShape.Struct(sSpec) =>
                emitStructReadExpr(
                    sSpec,
                    structsByName,
                    "out",
                    "0L",
                    spec.fqcn,
                    method.scalaName,
                    checkedBorrows = spec.companion.exists(_.checkedBorrows)
                )
            case ReturnShape.HandleReturn(_, true) =>
                // Nullable Handle return (Maybe[Handle[A]]). NULL maps to Absent, non-null to Present.
                s"if $retValName.address() == 0L then kyo.Absent else kyo.Present(Ffi.Handle.wrap($retValName))"
            case ReturnShape.HandleReturn(_, _) =>
                // Non-nullable Handle return. NULL throws FfiNullPointer.
                s"""if $retValName.address() == 0L then throw new kyo.ffi.FfiNullPointer("${spec.fqcn}.${method.scalaName} returned null") else Ffi.Handle.wrap($retValName)"""
            case ReturnShape.EnumReturn(fqcn) =>
                // Enum return. Convert the raw Int to the enum type via companion's fromInt.
                s"$fqcn.fromInt($retValName)"
            case ReturnShape.BorrowedString(maxBytes) =>
                // Borrowed String return.
                s"Ffi.Borrowed.wrap(if $retValName.address() == 0L then null else Scratch.readCStringBounded($retValName.reinterpret(${maxBytes}L), 0L, " +
                    s"""${maxBytes}L, "${spec.fqcn}", "${method.scalaName}", "<return>"))"""
            case ReturnShape.BorrowedBuffer(elem, sizeParam) =>
                // Borrowed Buffer[A] return.
                val sp           = safeName(sizeParam)
                val elemScala    = scalaTypeOf(elem)
                val elemBytes    = primitiveBytes(elem)
                val reinterpretE = s"$retValName.reinterpret($sp.toLong * ${elemBytes}L)"
                val checkedCall =
                    s"Buffer.Unsafe.wrapBorrowedChecked[$elemScala]($reinterpretE, $sp.toInt, " +
                        s"kyo.ffi.internal.BufferFactory.currentBorrowOwner())"
                val uncheckedCall =
                    s"Buffer.Unsafe.wrapBorrowed[$elemScala]($reinterpretE, $sp.toInt)"
                val checkedBorrows = spec.companion.exists(_.checkedBorrows)
                val nonNullWrap =
                    if checkedBorrows then checkedCall
                    else s"""(if (java.lang.System.getProperty("kyo.ffi.checkedBorrows") == "true") $checkedCall else $uncheckedCall)"""
                s"Ffi.Borrowed.wrap(if $retValName.address() == 0L then null else $nonNullWrap)"

        // Emit errno handling + result. WithError wraps the result + errno; plain returns just emit the result.
        if method.withError then
            bodyInner ++= s"val __kyoResult = $resultExpr\n"
            bodyInner ++= "new Ffi.WithError(__kyoResult, __errno)\n"
        else
            bodyInner ++= resultExpr + "\n"
        end if

        bodyInner.toString.linesIterator.foreach { line =>
            sb ++= "    "
            sb ++= line
            sb ++= "\n"
        }

        // Transient callbacks (including struct-field FnPtrT) close the per-call cbArena in finally.
        // Retained callbacks leave the arena alone, the guard will close it. All paths reset scratch in finally.
        if needsCbArena && method.callbackKind != CallbackKind.Retained then
            sb ++= "finally\n"
            sb ++= "    __kyoScratch$.reset(__kyoMark$)\n"
            sb ++= "    cbArena.close()\n"
        else
            sb ++= "finally __kyoScratch$.reset(__kyoMark$)\n"
        end if
        sb.toString
    end emitPlainMethodBody

    /** Emit the body for a variadic method.
      *
      * Unlike fixed-arity methods, the generated body cannot use a cached `MethodHandle`, the C-level descriptor depends on the runtime
      * types of the `args: Any*` tail. The emitted code allocates scratch + errno, marshals the fixed args inline, and delegates to
      * [[kyo.ffi.internal.VariadicMarshaller.invoke]] which classifies each vararg, builds the descriptor, links a fresh downcall, and
      * invokes it with `Linker.Option.firstVariadicArg(fixedCount)`.
      *
      * Fixed param marshalling mirrors [[emitPlainMethodBody]]'s primitive / String / Buffer paths; callbacks, varargs inside callback
      * params, arrays, structs, Guard, and FnPtr parameters are intentionally NOT supported in v1 variadic bindings, those carry more
      * subtle ABI rules (callback lifetime, zone-copy vs critical, struct layouts) that would expand the surface beyond what a variadic
      * `printf`-like binding typically needs. Reject at emit-time if the fixed-arg set contains anything other than primitives / String /
      * Buffer.
      */
    private def emitVariadicMethodBody(method: MethodSpec, spec: TraitSpec): String =
        // Validate fixed-arg shape upfront, varargs methods are intended for `printf`-style bindings whose fixed arg list is primitive
        // + String. Anything else would require per-platform marshalling that is out of scope for the F8b v1 cut.
        method.params.foreach { p =>
            p.tpe match
                case TypeRef.BooleanT | TypeRef.ByteT | TypeRef.ShortT | TypeRef.IntT | TypeRef.LongT |
                    TypeRef.FloatT | TypeRef.DoubleT | TypeRef.StringT => ()
                case TypeRef.BufferT(_) => ()
                case other =>
                    throw new IllegalStateException(
                        s"variadic method '${method.scalaName}' on '${spec.fqcn}' has unsupported fixed parameter type $other, " +
                            "variadic v1 supports only primitives, String, and Buffer[A] in the fixed-arg list"
                    )
        }

        val fqnLit    = s""""${spec.fqcn}""""
        val methodLit = s""""${method.scalaName}""""

        // Marshal each fixed parameter; the pass-through expression feeds `fixedArgs` (as AnyRef) to VariadicMarshaller.
        case class Fixed(layout: String, pass: String, setup: List[String])
        val fixed: List[Fixed] = method.params.map { p =>
            val name = safeName(p.name)
            p.tpe match
                case TypeRef.BooleanT =>
                    Fixed("JAVA_INT", s"java.lang.Integer.valueOf(if $name then 1 else 0)", Nil)
                case TypeRef.ByteT =>
                    Fixed("JAVA_BYTE", s"java.lang.Byte.valueOf($name)", Nil)
                case TypeRef.ShortT =>
                    Fixed("JAVA_SHORT", s"java.lang.Short.valueOf($name)", Nil)
                case TypeRef.IntT =>
                    Fixed("JAVA_INT", s"java.lang.Integer.valueOf($name)", Nil)
                case TypeRef.LongT =>
                    Fixed("JAVA_LONG", s"java.lang.Long.valueOf($name)", Nil)
                case TypeRef.FloatT =>
                    Fixed("JAVA_FLOAT", s"java.lang.Float.valueOf($name)", Nil)
                case TypeRef.DoubleT =>
                    Fixed("JAVA_DOUBLE", s"java.lang.Double.valueOf($name)", Nil)
                case TypeRef.StringT =>
                    val segN = s"${name}Seg"
                    Fixed("ADDRESS", segN, List(s"val $segN = __kyoScratch$$.allocUtf8($name, $fqnLit, $methodLit)"))
                case TypeRef.BufferT(_) =>
                    val segN = s"${name}Seg"
                    Fixed(
                        "ADDRESS",
                        segN,
                        List(
                            s"val $segN = kyo.ffi.internal.FfiUnsafe.expect[MemorySegment](" +
                                s"""$name.raw.asInstanceOf[AnyRef], classOf[MemorySegment], "MemorySegment on JVM", "${spec.fqcn}", "${method.scalaName}")"""
                        )
                    )
                case other =>
                    throw new IllegalStateException(s"variadic fixed-arg type $other unreachable")
            end match
        }

        // Return classification.
        val retLayoutExpr: String = method.returnShape match
            case ReturnShape.Void         => "null"
            case ReturnShape.Primitive(t) => valueLayoutOf(t)
            case other =>
                throw new IllegalStateException(
                    s"variadic method '${method.scalaName}' on '${spec.fqcn}' has unsupported return shape $other, " +
                        "variadic v1 supports only Unit / primitive returns"
                )

        val scratchSizeExpr = spec.companion.flatMap(_.scratchSize) match
            case Some(n) => s"${n}L"
            case None    => "Scratch.configuredSize"

        val sb = new StringBuilder
        sb ++= s"val __kyoScratch$$ = Scratch.currentFor($fqnLit, $scratchSizeExpr)\n"
        sb ++= "val __kyoMark$ = __kyoScratch$.mark()\n"
        sb ++= "try\n"

        val inner = new StringBuilder
        fixed.flatMap(_.setup).foreach { l =>
            inner ++= l
            inner ++= "\n"
        }
        inner ++= s"val errnoSeg = __kyoScratch$$.alloc(4L, 4L, $fqnLit, $methodLit)\n"

        val fixedLayouts =
            if fixed.isEmpty then "Nil"
            else fixed.map(f => s"${f.layout}: java.lang.foreign.MemoryLayout").mkString("List(", ", ", ")")

        val fixedArgsList =
            if fixed.isEmpty then "Nil"
            else fixed.map(f => s"${f.pass}: Any").mkString("List(", ", ", ")")

        val invokeCall =
            s"""kyo.ffi.internal.VariadicMarshaller.invoke(
               |    lib,
               |    "${method.cSymbol}",
               |    errnoSeg,
               |    $retLayoutExpr,
               |    $fixedLayouts,
               |    $fixedArgsList,
               |    Linker.Option.firstVariadicArg(${fixed.size}),
               |    args,
               |    __kyoScratch$$,
               |    "${spec.fqcn}",
               |    "${method.scalaName}"
               |)""".stripMargin

        method.returnShape match
            case ReturnShape.Void =>
                inner ++= s"val _ = $invokeCall\n"
                if method.withError then
                    inner ++= "val __errno = errnoSeg.get(JAVA_INT, 0L)\n"
                    inner ++= "new Ffi.WithError((), __errno)\n"
                else
                    inner ++= "()\n"
                end if
            case ReturnShape.Primitive(TypeRef.BooleanT) =>
                inner ++= s"val retVal = $invokeCall\n"
                if method.withError then
                    inner ++= "val __errno = errnoSeg.get(JAVA_INT, 0L)\n"
                    inner ++= "new Ffi.WithError(retVal.asInstanceOf[java.lang.Integer].intValue() != 0, __errno)\n"
                else
                    inner ++= s"retVal.asInstanceOf[java.lang.Integer].intValue() != 0\n"
                end if
            case ReturnShape.Primitive(t) =>
                val boxed = boxedName(t)
                val prim = primitiveScala(t) match
                    case "Int"    => "intValue()"
                    case "Byte"   => "byteValue()"
                    case "Short"  => "shortValue()"
                    case "Long"   => "longValue()"
                    case "Float"  => "floatValue()"
                    case "Double" => "doubleValue()"
                inner ++= s"val retVal = $invokeCall\n"
                if method.withError then
                    inner ++= "val __errno = errnoSeg.get(JAVA_INT, 0L)\n"
                    inner ++= s"new Ffi.WithError(retVal.asInstanceOf[$boxed].$prim, __errno)\n"
                else
                    inner ++= s"retVal.asInstanceOf[$boxed].$prim\n"
                end if
            case _ =>
                // Already rejected above, unreachable.
                throw new IllegalStateException("unreachable variadic return shape")
        end match

        inner.toString.linesIterator.foreach { l =>
            sb ++= "    "
            sb ++= l
            sb ++= "\n"
        }
        sb ++= "finally __kyoScratch$.reset(__kyoMark$)\n"
        sb.toString
    end emitVariadicMethodBody

    private def boxedName(t: TypeRef): String = t match
        case TypeRef.ByteT    => "java.lang.Byte"
        case TypeRef.ShortT   => "java.lang.Short"
        case TypeRef.IntT     => "java.lang.Integer"
        case TypeRef.LongT    => "java.lang.Long"
        case TypeRef.FloatT   => "java.lang.Float"
        case TypeRef.DoubleT  => "java.lang.Double"
        case TypeRef.BooleanT => "java.lang.Integer"
        case other            => throw new IllegalStateException(s"boxedName: $other not a primitive")

    /** Minimum byte-size for a simple primitive-only struct to take the bulk-write path.
      *
      * Read once at class-init from `-Dkyo.ffi.structBulkWriteMinBytes=` (default 128). Structs below the threshold keep the field-by-field
      * pattern, for small structs the loop overhead dwarfs any batching win. Structs at or above the threshold with purely primitive
      * fields get the bulk-write marker so future optimisations (packed-long writes, MemorySegment.copy from a pre-populated carrier) can
      * slot in without churning the emitter.
      *
      * The threshold only gates the marker comment; field writes themselves are unchanged today. The gating is stable so emitter snapshot
      * tests can assert the path selection.
      */
    private val structBulkWriteMinBytes: Long =
        try sys.props.getOrElse("kyo.ffi.structBulkWriteMinBytes", "128").toLong
        catch case _: Throwable => 128L

    /** True when [[sSpec]] qualifies for the bulk-write marker: only primitive fields (no String / Buffer / nested struct / Array / FnPtr /
      * Guard / Unit).
      */
    private def isSimplePrimitiveStruct(sSpec: StructSpec): Boolean =
        sSpec.fields.nonEmpty && sSpec.fields.forall { f =>
            f.tpe match
                case TypeRef.BooleanT | TypeRef.ByteT | TypeRef.ShortT | TypeRef.IntT |
                    TypeRef.LongT | TypeRef.FloatT | TypeRef.DoubleT => true
                case _ => false
        }

    /** Emit the field-by-field writes for a struct located at `segExpr + baseOffExpr`.
      *
      * [[bindingFqn]] and [[methodName]] thread the FFI call site through to the `FfiUnsafe.expect` helper so an alien `Buffer.Raw`
      * implementation produces a diagnostic naming the origin.
      *
      * When [[sSpec]] is a pure-primitive struct of byte size ≥ [[structBulkWriteMinBytes]] (default 128 bytes; override via
      * `-Dkyo.ffi.structBulkWriteMinBytes=`), the emission is preceded by a `// __kyoStructBulkWrite$:` marker comment naming the struct
      * and byte size. The marker is a stable emitter signal so downstream snapshot tests and runtime observers can confirm the path
      * selection; field writes themselves remain field-by-field in v1. The threshold plus marker unlock future bulk-copy optimisations
      * (packed-long writes, MemorySegment.copy) without changing the emitter contract.
      */
    private def emitStructWrite(
        caseClassVal: String,
        segExpr: String,
        sSpec: StructSpec,
        structsByName: Map[String, StructSpec],
        baseOffExpr: String,
        bindingFqn: String,
        methodName: String
    ): List[String] =
        val buf = List.newBuilder[String]
        // Bulk-write marker: simple primitive struct above the configured threshold. Emitted at the top of the struct's writes
        // so snapshot tests and human readers can spot the path selection. Marker stays stable across regen.
        if isSimplePrimitiveStruct(sSpec) then
            val byteSize = structByteSize(sSpec, structsByName)
            if byteSize >= structBulkWriteMinBytes then
                buf += s"// __kyoStructBulkWrite$$: ${sSpec.fqcn} ($byteSize bytes)"
        end if
        structFieldLayouts(sSpec, structsByName, forWrite = true).foreach { case FieldLayout(f, fieldOffset, _) =>
            val offExpr =
                if baseOffExpr == "0L" then s"${fieldOffset}L"
                else s"$baseOffExpr + ${fieldOffset}L"
            val access = s"$caseClassVal.${safeName(f.name)}"
            f.tpe match
                case TypeRef.BooleanT =>
                    buf += s"$segExpr.set(JAVA_INT, $offExpr, if $access then 1 else 0)"
                case TypeRef.ByteT =>
                    buf += s"$segExpr.set(JAVA_BYTE, $offExpr, $access)"
                case TypeRef.ShortT =>
                    buf += s"$segExpr.set(JAVA_SHORT, $offExpr, $access)"
                case TypeRef.IntT =>
                    buf += s"$segExpr.set(JAVA_INT, $offExpr, $access)"
                case TypeRef.LongT =>
                    buf += s"$segExpr.set(JAVA_LONG, $offExpr, $access)"
                case TypeRef.FloatT =>
                    buf += s"$segExpr.set(JAVA_FLOAT, $offExpr, $access)"
                case TypeRef.DoubleT =>
                    buf += s"$segExpr.set(JAVA_DOUBLE, $offExpr, $access)"
                case TypeRef.StringT =>
                    val tmp = s"${caseClassVal}_${f.name}_cs"
                    // Thread binding + method context so spill logs identify the struct-field marshalling call site.
                    buf += s"""val $tmp = __kyoScratch$$.allocUtf8($access, "$bindingFqn", "$methodName")"""
                    buf += s"$segExpr.set(ADDRESS, $offExpr, $tmp)"
                case TypeRef.BufferT(_) =>
                    // Checked unwrap, alien `Buffer.Raw` implementations produce a
                    // diagnostic instead of a silent `ClassCastException`.
                    buf += (s"$segExpr.set(ADDRESS, $offExpr, kyo.ffi.internal.FfiUnsafe.expect[MemorySegment](" +
                        s"""$access.raw.asInstanceOf[AnyRef], classOf[MemorySegment], "MemorySegment on JVM", "$bindingFqn", "$methodName"))""")
                case TypeRef.HandleT(_) =>
                    // Handle field: unwrap via Ffi.Handle.unwrap and set as ADDRESS.
                    buf += (s"$segExpr.set(ADDRESS, $offExpr, kyo.ffi.internal.FfiUnsafe.expect[MemorySegment](" +
                        s"""Ffi.Handle.unwrap($access).asInstanceOf[AnyRef], classOf[MemorySegment], "MemorySegment on JVM", "$bindingFqn", "$methodName"))""")
                case TypeRef.EnumT(_) =>
                    // Enum field: write the Int value.
                    buf += s"$segExpr.set(JAVA_INT, $offExpr, $access.value)"
                case TypeRef.StructT(n) =>
                    val child = structsByName.getOrElse(
                        n,
                        throw new IllegalStateException(s"nested struct '$n' not found")
                    )
                    buf ++= emitStructWrite(access, segExpr, child, structsByName, offExpr, bindingFqn, methodName)
                case TypeRef.FnPtrT(params, ret) =>
                    // Function pointer struct field: create an upcall stub using UpcallBridge and write
                    // the stub address at the field offset.  The stub is allocated on cbArena which is
                    // emitted by emitPlainMethodBody when it detects FnPtrT struct fields, and closed
                    // in the finally block after the FFI call.
                    val stubN  = s"${caseClassVal}_${f.name}_stub"
                    val descId = s"${caseClassVal}_${f.name}_desc"
                    val arity  = params.size
                    val desc   = callbackFunctionDescriptor(params, ret)
                    buf += s"val $descId = $desc"
                    buf += s"val $stubN = UpcallBridge.stub$arity($access, $descId, cbArena, " +
                        s""""$bindingFqn", "$methodName", "transient")"""
                    buf += s"$segExpr.set(ADDRESS, $offExpr, $stubN)"
                case TypeRef.UnionT(variants) =>
                    // Union field in a struct: allocate scratch for the union, runtime-match the value,
                    // then copy the union bytes into the struct at the field offset.
                    buf ++= emitUnionFieldWrite(access, segExpr, offExpr, variants, structsByName, bindingFqn, methodName)
                case TypeRef.ArrayT(_) | TypeRef.GuardT | TypeRef.UnitT =>
                    throw new IllegalStateException(s"unsupported struct field type in '${sSpec.simpleName}.${f.name}'")
            end match
        }
        buf.result()
    end emitStructWrite

    /** Emit setup lines for marshalling a union parameter: allocate scratch, runtime-match, write the correct variant. */
    private def emitUnionParamWrite(
        paramName: String,
        segName: String,
        variants: List[TypeRef],
        structsByName: Map[String, StructSpec],
        bindingFqn: String,
        methodName: String
    ): List[String] =
        val (unionSize, unionAlign) = unionSizeAndAlign(variants, structsByName)
        val buf                     = List.newBuilder[String]
        buf += s"""val $segName = __kyoScratch$$.alloc(${unionSize}L, ${unionAlign}L, "$bindingFqn", "$methodName")"""
        buf ++= emitUnionVariantMatch(paramName, segName, "0L", variants, structsByName, bindingFqn, methodName)
        buf.result()
    end emitUnionParamWrite

    /** Emit setup lines for writing a union field in a struct at a given offset. */
    private def emitUnionFieldWrite(
        access: String,
        segExpr: String,
        offExpr: String,
        variants: List[TypeRef],
        structsByName: Map[String, StructSpec],
        bindingFqn: String,
        methodName: String
    ): List[String] =
        emitUnionVariantMatch(access, segExpr, offExpr, variants, structsByName, bindingFqn, methodName)

    /** Emit the runtime type match that writes the correct union variant to a segment at a given offset. */
    private def emitUnionVariantMatch(
        valExpr: String,
        segExpr: String,
        offExpr: String,
        variants: List[TypeRef],
        structsByName: Map[String, StructSpec],
        bindingFqn: String,
        methodName: String
    ): List[String] =
        val buf = List.newBuilder[String]
        buf += s"($valExpr: @scala.annotation.switch) match"
        variants.foreach { v =>
            v match
                case TypeRef.BooleanT =>
                    buf += s"    case __v: java.lang.Boolean => $segExpr.set(JAVA_INT, $offExpr, if __v.booleanValue then 1 else 0)"
                case TypeRef.ByteT =>
                    buf += s"    case __v: java.lang.Byte => $segExpr.set(JAVA_BYTE, $offExpr, __v.byteValue)"
                case TypeRef.ShortT =>
                    buf += s"    case __v: java.lang.Short => $segExpr.set(JAVA_SHORT, $offExpr, __v.shortValue)"
                case TypeRef.IntT =>
                    buf += s"    case __v: java.lang.Integer => $segExpr.set(JAVA_INT, $offExpr, __v.intValue)"
                case TypeRef.LongT =>
                    buf += s"    case __v: java.lang.Long => $segExpr.set(JAVA_LONG, $offExpr, __v.longValue)"
                case TypeRef.FloatT =>
                    buf += s"    case __v: java.lang.Float => $segExpr.set(JAVA_FLOAT, $offExpr, __v.floatValue)"
                case TypeRef.DoubleT =>
                    buf += s"    case __v: java.lang.Double => $segExpr.set(JAVA_DOUBLE, $offExpr, __v.doubleValue)"
                case TypeRef.StructT(fqcn) =>
                    val sSpec = structsByName.getOrElse(
                        fqcn,
                        throw new IllegalStateException(s"union variant struct '$fqcn' not found")
                    )
                    // For struct variants, we write each field into the segment at the union's offset.
                    buf += s"    case __v: $fqcn =>"
                    emitStructWrite("__v", segExpr, sSpec, structsByName, offExpr, bindingFqn, methodName).foreach { line =>
                        buf += s"        $line"
                    }
                case other =>
                    throw new IllegalStateException(s"union variant type $other is not supported (only primitives and structs)")
            end match
        }
        buf += s"""    case __other => throw new kyo.ffi.FfiLoadError.Unsupported(s"Union variant not supported: $${__other.getClass}")"""
        buf.result()
    end emitUnionVariantMatch

    /** Compute size and alignment for a union type (max of all variants). */
    private def unionSizeAndAlign(variants: List[TypeRef], structsByName: Map[String, StructSpec]): (Long, Long) =
        val (maxSize, maxAlign) = variants.foldLeft((0L, 1L)) { case ((mxS, mxA), v) =>
            val (vs, va) = sizeAndAlign(v, structsByName, packed = false)
            (math.max(mxS, vs), math.max(mxA, va))
        }
        (align(maxSize, maxAlign), maxAlign)
    end unionSizeAndAlign

    /** Emit a single expression that constructs the case-class from the memory segment.
      *
      * [[bindingFqn]] and [[methodName]] thread the FFI call site into the bounded `readCStringBounded` helper so error messages for String
      * fields and Buffer reinterpretations name the origin.
      */
    private def emitStructReadExpr(
        sSpec: StructSpec,
        structsByName: Map[String, StructSpec],
        segExpr: String,
        baseOffExpr: String,
        bindingFqn: String,
        methodName: String,
        checkedBorrows: Boolean
    ): String =
        val layouts = structFieldLayouts(sSpec, structsByName, forWrite = false)
        // Infer buffer size from the sole Int/Long sibling. TypeValidator rejects zero/multiple; any BufferT field reaching
        // this emitter MUST have a resolvable sibling.
        def bufferSizeExpr(bufferFieldName: String): String =
            sSpec.inferredBufferSizeField match
                case Some(sizeField) =>
                    val sizeLayout = layouts.find(_.field.name == sizeField.name).getOrElse(
                        throw new IllegalStateException(
                            s"internal: sibling '${sizeField.name}' not in layout for struct '${sSpec.fqcn}'"
                        )
                    )
                    val sibOffExpr =
                        if baseOffExpr == "0L" then s"${sizeLayout.offset}L"
                        else s"$baseOffExpr + ${sizeLayout.offset}L"
                    sizeField.tpe match
                        case TypeRef.IntT  => s"$segExpr.get(JAVA_INT, $sibOffExpr)"
                        case TypeRef.LongT => s"$segExpr.get(JAVA_LONG, $sibOffExpr).toInt"
                        case other =>
                            throw new IllegalStateException(
                                s"internal: sibling size type $other not Int/Long for struct '${sSpec.fqcn}'"
                            )
                    end match
                case None =>
                    throw new IllegalStateException(
                        s"internal: struct '${sSpec.fqcn}' has BufferT field '$bufferFieldName' without resolved sibling, should have been rejected by TypeValidator"
                    )
        end bufferSizeExpr

        val parts = layouts.map { case FieldLayout(f, fieldOffset, _) =>
            val offExpr =
                if baseOffExpr == "0L" then s"${fieldOffset}L"
                else s"$baseOffExpr + ${fieldOffset}L"
            f.tpe match
                case TypeRef.BooleanT => s"$segExpr.get(JAVA_INT, $offExpr) != 0"
                case TypeRef.ByteT    => s"$segExpr.get(JAVA_BYTE, $offExpr)"
                case TypeRef.ShortT   => s"$segExpr.get(JAVA_SHORT, $offExpr)"
                case TypeRef.IntT     => s"$segExpr.get(JAVA_INT, $offExpr)"
                case TypeRef.LongT    => s"$segExpr.get(JAVA_LONG, $offExpr)"
                case TypeRef.FloatT   => s"$segExpr.get(JAVA_FLOAT, $offExpr)"
                case TypeRef.DoubleT  => s"$segExpr.get(JAVA_DOUBLE, $offExpr)"
                case TypeRef.StringT  =>
                    // Bounded reinterpret. `Scratch.stringFieldMaxBytes` caps the window to 64 KiB by default;
                    // `readCStringBounded` throws `FfiMalformedResult` if no NUL is found within the cap.
                    s"Scratch.readCStringBounded($segExpr.get(ADDRESS, $offExpr).reinterpret(Scratch.stringFieldMaxBytes), 0L, " +
                        s"""Scratch.stringFieldMaxBytes, "$bindingFqn", "$methodName", "${f.name}")"""
                case TypeRef.BufferT(elem) =>
                    // Borrowed pointer: the C callee retains ownership of the referenced memory. The reinterpret window is
                    // bounded by `Scratch.stringFieldMaxBytes` so downstream reads cannot walk past the cap; the logical
                    // Buffer element count is inferred from the unique Int/Long sibling field. Caller honors the
                    // real extent.
                    //
                    // When the binding opts into `Ffi.Config.checkedBorrows` OR the process-wide
                    // sys-prop `-Dkyo.ffi.checkedBorrows=true` is set, route through the checked variant with the per-thread
                    // BorrowOwner. The unchecked path is unchanged, zero overhead beyond the sys-prop read.
                    val sizeExpr = bufferSizeExpr(f.name)
                    val borrowBody =
                        s"$segExpr.get(ADDRESS, $offExpr).reinterpret(Scratch.stringFieldMaxBytes), $sizeExpr"
                    val checkedCall =
                        s"Buffer.Unsafe.wrapBorrowedChecked[${scalaTypeOf(elem)}]($borrowBody, kyo.ffi.internal.BufferFactory.currentBorrowOwner())"
                    val uncheckedCall =
                        s"Buffer.Unsafe.wrapBorrowed[${scalaTypeOf(elem)}]($borrowBody)"
                    if checkedBorrows then checkedCall
                    else
                        s"""if (java.lang.System.getProperty("kyo.ffi.checkedBorrows") == "true") $checkedCall else $uncheckedCall"""
                    end if
                case TypeRef.HandleT(_) =>
                    // Handle field: read the ADDRESS from the segment and wrap via Ffi.Handle.wrap.
                    s"Ffi.Handle.wrap($segExpr.get(ADDRESS, $offExpr))"
                case TypeRef.EnumT(enumFqcn) =>
                    // Enum field: read Int and convert via companion's fromInt.
                    s"$enumFqcn.fromInt($segExpr.get(JAVA_INT, $offExpr))"
                case TypeRef.FnPtrT(_, _) =>
                    // Reading a C function pointer back into a Scala function is not meaningful,                    // the C function pointer cannot be invoked safely from Scala without a descriptor.
                    s"""throw new UnsupportedOperationException("Cannot read function pointer field '${f.name}' from C struct '${sSpec.simpleName}'")"""
                case TypeRef.StructT(n) =>
                    val child = structsByName.getOrElse(
                        n,
                        throw new IllegalStateException(s"nested struct '$n' not found")
                    )
                    emitStructReadExpr(child, structsByName, segExpr, offExpr, bindingFqn, methodName, checkedBorrows)
                case TypeRef.UnionT(variants) =>
                    // Union field read: return the first variant type (the bytes are ambiguous).
                    emitUnionFieldRead(variants.head, segExpr, offExpr, structsByName, bindingFqn, methodName, checkedBorrows)
                case other =>
                    throw new IllegalStateException(s"unsupported struct-read field type: $other")
            end match
        }
        s"${sSpec.fqcn}(${parts.mkString(", ")})"
    end emitStructReadExpr

    /** Emit a read expression for a union field, reading the first variant type. */
    private def emitUnionFieldRead(
        firstVariant: TypeRef,
        segExpr: String,
        offExpr: String,
        structsByName: Map[String, StructSpec],
        bindingFqn: String,
        methodName: String,
        checkedBorrows: Boolean
    ): String =
        firstVariant match
            case TypeRef.BooleanT => s"$segExpr.get(JAVA_INT, $offExpr) != 0"
            case TypeRef.ByteT    => s"$segExpr.get(JAVA_BYTE, $offExpr)"
            case TypeRef.ShortT   => s"$segExpr.get(JAVA_SHORT, $offExpr)"
            case TypeRef.IntT     => s"$segExpr.get(JAVA_INT, $offExpr)"
            case TypeRef.LongT    => s"$segExpr.get(JAVA_LONG, $offExpr)"
            case TypeRef.FloatT   => s"$segExpr.get(JAVA_FLOAT, $offExpr)"
            case TypeRef.DoubleT  => s"$segExpr.get(JAVA_DOUBLE, $offExpr)"
            case TypeRef.StructT(n) =>
                val child = structsByName.getOrElse(n, throw new IllegalStateException(s"nested struct '$n' not found"))
                emitStructReadExpr(child, structsByName, segExpr, offExpr, bindingFqn, methodName, checkedBorrows)
            case other =>
                throw new IllegalStateException(s"unsupported union variant read type: $other")
    end emitUnionFieldRead

    // -------------------------------------------------------------------------
    // Impl companion
    // -------------------------------------------------------------------------

    private[codegen] def emitImplCompanion(spec: TraitSpec): String =
        val sb          = new StringBuilder
        val needsCritOn = spec.methods.exists(m => !m.blocking && m.hasArrayParam)
        sb ++= s"object ${spec.simpleName}Impl:\n"
        sb ++= abiCheckLine(spec.fqcn)
        sb ++= "\n"
        sb ++= s"""    private val lib      = NativeLoader.load("${spec.library}")\n"""
        sb ++= "    private val linker   = Linker.nativeLinker()\n"
        sb ++= """    private val capture  = Linker.Option.captureCallState("errno")""" + "\n"
        if needsCritOn then
            // `Linker.Option.critical(...)` was added in JDK 22. Detect it reflectively at class-init time
            // so the emitted impl still class-loads under JDK 21 (capture-only downcall path).
            sb ++= "    private val hasCriticalApi: Boolean =\n"
            sb ++= "        try\n"
            sb ++= "            val _ = classOf[Linker.Option].getMethod(\"critical\", classOf[Boolean])\n"
            sb ++= "            true\n"
            sb ++= "        catch case _: Throwable => false\n"
            sb ++= "    private val critOn: Linker.Option | Null =\n"
            sb ++= "        if hasCriticalApi then Linker.Option.critical(true) else null\n"
            sb ++= "    private def mkOpts(opts: Linker.Option | Null*): Array[Linker.Option] =\n"
            sb ++= "        opts.iterator.collect { case o: Linker.Option => o }.toArray\n"
        end if

        // Emit struct layouts, only for structs that actually need a MemoryLayout at runtime
        // (i.e. referenced by a StructT param, a ReturnShape.Struct return, or transitively nested inside
        // one of those). MultiValue return shapes decompose their case class into primitive out-params at
        // call time and never construct the struct memory layout; emitting one anyway would force Panama
        // to resolve struct-level alignment for a type that is never packed, which fails for cases like
        // `(Int, String)` where ADDRESS (align 8) follows JAVA_INT (align 4) without explicit padding.
        val structsByName = EmitterBase.structsByName(spec)
        val layoutNeeded  = layoutRequiredStructs(spec, structsByName)
        spec.structs.foreach { s =>
            if layoutNeeded.contains(s.fqcn) then
                sb ++= "\n"
                sb ++= s"    ${emitStructLayoutVal(s, spec)}\n"
        }

        // Struct ABI self-check. For each struct whose MemoryLayout was emitted above, compare the code-generator's
        // expected byte size (computed from the Scala case class layout at generation time) against `MemoryLayout.byteSize()` at
        // class-init time. A disagreement typically means the user forgot to add a packed struct to `Ffi.Config.packedStructs` (or
        // listed one that the C side does NOT pack); failing at impl init keeps the mistake from silently corrupting field reads.
        spec.structs.foreach { s =>
            if layoutNeeded.contains(s.fqcn) then
                val expected = structByteSize(s, structsByName)
                sb ++= s"""    StructAbiCheck.verifyByteSize("${spec.fqcn}", "${s.simpleName}", ${expected}L, ${layoutConst(
                        s
                    )}.byteSize())\n"""
        }

        // Emit MethodHandle bindings. Variadic methods do not use a cached MH, their
        // C-level descriptor is built per call by `VariadicMarshaller.invoke`, so skip the companion-
        // level handle emission for those methods.
        spec.methods.foreach { m =>
            if !m.hasVarargs then
                sb ++= "\n"
                sb ++= emitMethodHandle(m, spec)
        }

        sb ++= s"end ${spec.simpleName}Impl\n"
        sb.toString
    end emitImplCompanion

    private def emitStructLayoutVal(s: StructSpec, trait0: TraitSpec): String =
        val structsByName = trait0.structs.map(x => x.fqcn -> x).toMap
        s"private val ${layoutConstName(s)}: MemoryLayout = ${structLayoutExpr(s, structsByName)}"

    private def structLayoutExpr(s: StructSpec, structsByName: Map[String, StructSpec]): String =
        def baseLayout(t: TypeRef): String = t match
            case TypeRef.BooleanT     => "JAVA_INT"
            case TypeRef.ByteT        => "JAVA_BYTE"
            case TypeRef.ShortT       => "JAVA_SHORT"
            case TypeRef.IntT         => "JAVA_INT"
            case TypeRef.LongT        => "JAVA_LONG"
            case TypeRef.FloatT       => "JAVA_FLOAT"
            case TypeRef.DoubleT      => "JAVA_DOUBLE"
            case TypeRef.StringT      => "ADDRESS"
            case TypeRef.BufferT(_)   => "ADDRESS"
            case TypeRef.HandleT(_)   => "ADDRESS"
            case TypeRef.EnumT(_)     => "JAVA_INT"
            case TypeRef.FnPtrT(_, _) => "ADDRESS"
            case TypeRef.StructT(n) =>
                val child = structsByName.getOrElse(
                    n,
                    throw new IllegalStateException(s"nested struct '$n' not found")
                )
                structLayoutExpr(child, structsByName)
            case TypeRef.UnionT(variants) =>
                // Union field in a struct: emit a Panama union layout for the variants.
                unionLayoutExpr(variants, structsByName)
            case other =>
                throw new IllegalStateException(s"unsupported struct-layout field type: $other")

        if s.packed then
            // Packed: every member is forced to byte-alignment 1 and laid out contiguously, so no interior or
            // trailing padding is needed.
            val parts = s.fields.map(f => s"${baseLayout(f.tpe)}.withByteAlignment(1L)")
            s"MemoryLayout.structLayout(${parts.mkString(", ")})"
        else
            // Natural alignment: Panama requires each member's offset to be a multiple of its alignment and the
            // struct's byteSize to be a multiple of its alignment, and it does NOT auto-insert padding. Emit explicit
            // paddingLayout members for interior alignment gaps (e.g. a 4-byte Int before an 8-aligned ADDRESS) and a
            // trailing pad so the layout's byteSize matches the C ABI struct size. Offsets come from the same
            // structFieldLayouts the marshalling uses, so layout and field writes stay in lock-step.
            val parts    = scala.collection.mutable.ListBuffer.empty[String]
            var cursor   = 0L
            var maxAlign = 1L
            structFieldLayouts(s, structsByName, forWrite = false).foreach { case FieldLayout(f, off, _) =>
                val (fSize, fAlign) = sizeAndAlign(f.tpe, structsByName, packed = false)
                maxAlign = math.max(maxAlign, fAlign)
                if off > cursor then parts += s"MemoryLayout.paddingLayout(${off - cursor}L)"
                parts += baseLayout(f.tpe)
                cursor = off + fSize
            }
            val total = align(cursor, maxAlign)
            if total > cursor then parts += s"MemoryLayout.paddingLayout(${total - cursor}L)"
            s"MemoryLayout.structLayout(${parts.mkString(", ")})"
        end if
    end structLayoutExpr

    /** Emit `MemoryLayout.unionLayout(...)` for a union type's variants. */
    private def unionLayoutExpr(variants: List[TypeRef], structsByName: Map[String, StructSpec]): String =
        val parts = variants.map { v =>
            v match
                case TypeRef.BooleanT => "JAVA_INT"
                case TypeRef.ByteT    => "JAVA_BYTE"
                case TypeRef.ShortT   => "JAVA_SHORT"
                case TypeRef.IntT     => "JAVA_INT"
                case TypeRef.LongT    => "JAVA_LONG"
                case TypeRef.FloatT   => "JAVA_FLOAT"
                case TypeRef.DoubleT  => "JAVA_DOUBLE"
                case TypeRef.StructT(n) =>
                    val child = structsByName.getOrElse(n, throw new IllegalStateException(s"nested struct '$n' not found"))
                    structLayoutExpr(child, structsByName)
                case other =>
                    throw new IllegalStateException(s"unsupported union variant layout type: $other")
        }
        s"MemoryLayout.unionLayout(${parts.mkString(", ")})"
    end unionLayoutExpr

    private def emitMethodHandle(method: MethodSpec, spec: TraitSpec): String =
        val structsByName = EmitterBase.structsByName(spec)
        val descr         = emitFunctionDescriptor(method, structsByName)
        val opts          = emitLinkerOptions(method)
        val indent        = "        "

        // Callback-bearing methods additionally emit one `FunctionDescriptor` val per callback parameter so generated bodies can pass
        // it to `UpcallBridge.stubN(...)`. Emitted BEFORE the MethodHandle val to keep declaration order user-readable.
        val cbDescVals = method.callbackKind match
            case CallbackKind.Transient =>
                method.params.collect { case ParamSpec(pname, TypeRef.FnPtrT(params, ret)) =>
                    val descName = callbackDescName(method.scalaName, pname)
                    val fd       = callbackFunctionDescriptor(params, ret)
                    s"    private val $descName: FunctionDescriptor = $fd\n"
                }.mkString
            case CallbackKind.Retained =>
                method.params.collect { case ParamSpec(pname, TypeRef.FnPtrT(params, ret)) =>
                    val name = callbackDescName(method.scalaName, pname)
                    val fd   = callbackFunctionDescriptor(params, ret)
                    s"    private val $name: FunctionDescriptor = $fd\n"
                }.mkString
            case CallbackKind.None => ""
        end cbDescVals

        // When critOn may be in play (non-blocking + array params), splat the options through `mkOpts(...)` so the
        // generated code gracefully degrades to capture-only under JDK 21 (where `critOn` is null and is filtered out).
        val optsLine =
            if !method.blocking && method.hasArrayParam then s"mkOpts($opts)*"
            else opts

        val mhLines =
            s"""    private val ${method.scalaName}MH: MethodHandle = linker.downcallHandle(
               |${indent}lib.find("${method.cSymbol}").orElseThrow(),
               |${indent}$descr,
               |${indent}$optsLine
               |    )
               |""".stripMargin

        cbDescVals + mhLines
    end emitMethodHandle

    /** FunctionDescriptor for one callback parameter's C-level signature. */
    private[codegen] def callbackFunctionDescriptor(params: List[TypeRef], ret: TypeRef): String =
        val paramLayouts = params.map(callbackValueLayout).mkString(", ")
        ret match
            case TypeRef.UnitT => s"FunctionDescriptor.ofVoid($paramLayouts)"
            case other => s"FunctionDescriptor.of(${callbackValueLayout(other)}${if params.isEmpty then "" else s", $paramLayouts"})"
    end callbackFunctionDescriptor

    /** ValueLayout expression for a callback's primitive/pointer position. */
    private def callbackValueLayout(t: TypeRef): String = t match
        case TypeRef.BooleanT     => "JAVA_BOOLEAN"
        case TypeRef.ByteT        => "JAVA_BYTE"
        case TypeRef.ShortT       => "JAVA_SHORT"
        case TypeRef.IntT         => "JAVA_INT"
        case TypeRef.LongT        => "JAVA_LONG"
        case TypeRef.FloatT       => "JAVA_FLOAT"
        case TypeRef.DoubleT      => "JAVA_DOUBLE"
        case TypeRef.BufferT(_)   => "ADDRESS"
        case TypeRef.StringT      => "ADDRESS"
        case TypeRef.FnPtrT(_, _) => "ADDRESS"
        case other =>
            throw new IllegalStateException(s"callbackValueLayout: unsupported callback position type $other")

    /** Name of the `FunctionDescriptor` companion val associated with one callback parameter. */
    private def callbackDescName(methodName: String, paramName: String): String =
        val p = if paramName.isEmpty then "Cb" else paramName.head.toUpper.toString + paramName.tail
        s"cb${methodName.head.toUpper}${methodName.tail}${p}Desc"

    /** Build the FunctionDescriptor expression for a method, honoring errno-capture's leading state segment.
      *
      * Under `captureCallState`, the leading capture segment is injected by Panama at `invokeExact` time, it is NOT part of
      * `FunctionDescriptor`. So we emit the descriptor representing the raw C signature.
      */
    private[codegen] def emitFunctionDescriptor(method: MethodSpec, structsByName: Map[String, StructSpec]): String =
        // Scala-level return type → descriptor return (for Void/Primitive/MultiValue the C return is the first-field primitive).
        val returnPart: Option[String] = method.returnShape match
            case ReturnShape.Void                                                 => None
            case ReturnShape.Primitive(t)                                         => Some(valueLayoutOf(t))
            case ReturnShape.MultiValue(sSpec)                                    => Some(valueLayoutOf(sSpec.fields.head.tpe))
            case ReturnShape.Struct(_)                                            => None // passed as out-ADDRESS, C returns void
            case ReturnShape.HandleReturn(_, _)                                   => Some("ADDRESS")
            case ReturnShape.EnumReturn(_)                                        => Some("JAVA_INT")
            case ReturnShape.BorrowedString(_) | ReturnShape.BorrowedBuffer(_, _) => Some("ADDRESS")

        val paramParts = List.newBuilder[String]

        // Out-segment for ReturnShape.Struct is an additional ADDRESS param (before user params).
        method.returnShape match
            case ReturnShape.Struct(_) => paramParts += "ADDRESS"
            case _                     => ()

        method.params.foreach { p =>
            // Ffi.Guard never crosses the FFI boundary, it's consumed locally to derive the upcall arena.
            if p.tpe != TypeRef.GuardT then paramParts += paramLayout(p.tpe, structsByName)
        }

        // Multi-value out-params append after user params.
        method.returnShape match
            case ReturnShape.MultiValue(sSpec) =>
                sSpec.fields.drop(1).foreach { _ => paramParts += "ADDRESS" }
            case _ => ()
        end match

        val ps = paramParts.result().mkString(", ")
        returnPart match
            // Zero-arg primitive-return methods (e.g. `getpid(): Int`) must not
            // emit a trailing comma, `FunctionDescriptor.of(JAVA_INT, )` is a syntax
            // error. Drop the comma when there are no parameters.
            case Some(r) if ps.isEmpty => s"FunctionDescriptor.of($r)"
            case Some(r)               => s"FunctionDescriptor.of($r, $ps)"
            case None                  => s"FunctionDescriptor.ofVoid($ps)"
        end match
    end emitFunctionDescriptor

    private def paramLayout(t: TypeRef, structsByName: Map[String, StructSpec]): String =
        t match
            case TypeRef.BooleanT     => "JAVA_INT"
            case TypeRef.ByteT        => "JAVA_BYTE"
            case TypeRef.ShortT       => "JAVA_SHORT"
            case TypeRef.IntT         => "JAVA_INT"
            case TypeRef.LongT        => "JAVA_LONG"
            case TypeRef.FloatT       => "JAVA_FLOAT"
            case TypeRef.DoubleT      => "JAVA_DOUBLE"
            case TypeRef.UnitT        => throw new IllegalStateException("Unit is not a valid parameter type")
            case TypeRef.StringT      => "ADDRESS"
            case TypeRef.BufferT(_)   => "ADDRESS"
            case TypeRef.ArrayT(_)    => "ADDRESS"
            case TypeRef.StructT(_)   => "ADDRESS"
            case TypeRef.UnionT(_)    => "ADDRESS"
            case TypeRef.HandleT(_)   => "ADDRESS"
            case TypeRef.EnumT(_)     => "JAVA_INT"
            case TypeRef.FnPtrT(_, _) => "ADDRESS"
            case TypeRef.GuardT       => throw new IllegalStateException("Guard is not a C-passed parameter")

    /** Compute the comma-separated `Linker.Option` argument list per §4.6. errno capture is always on.
      *
      * Note: `Linker.Option.critical(false)` is equivalent to the default (non-critical) behavior, so we omit it, this also avoids
      * requiring JDK 22+ (where `critical` was added) for code that has no array parameters.
      */
    private[codegen] def emitLinkerOptions(method: MethodSpec): String =
        if method.blocking then "capture"
        else if method.hasArrayParam then "capture, critOn"
        else "capture"

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    def primitiveTypeName(t: TypeRef): String = t match
        case TypeRef.BooleanT => "JAVA_INT"
        case TypeRef.ByteT    => "JAVA_BYTE"
        case TypeRef.ShortT   => "JAVA_SHORT"
        case TypeRef.IntT     => "JAVA_INT"
        case TypeRef.LongT    => "JAVA_LONG"
        case TypeRef.FloatT   => "JAVA_FLOAT"
        case TypeRef.DoubleT  => "JAVA_DOUBLE"
        case other            => throw new IllegalStateException(s"primitiveTypeName: unsupported primitive $other")

    private def valueLayoutOf(t: TypeRef): String = primitiveTypeName(t)

    private def primitiveScala(t: TypeRef): String = t match
        case TypeRef.BooleanT => "Int"
        case TypeRef.ByteT    => "Byte"
        case TypeRef.ShortT   => "Short"
        case TypeRef.IntT     => "Int"
        case TypeRef.LongT    => "Long"
        case TypeRef.FloatT   => "Float"
        case TypeRef.DoubleT  => "Double"
        case other            => throw new IllegalStateException(s"primitiveScala: not a primitive $other")

    private def primitiveBytes(t: TypeRef): Int = t match
        case TypeRef.BooleanT => 4
        case TypeRef.ByteT    => 1
        case TypeRef.ShortT   => 2
        case TypeRef.IntT     => 4
        case TypeRef.LongT    => 8
        case TypeRef.FloatT   => 4
        case TypeRef.DoubleT  => 8
        case other            => throw new IllegalStateException(s"primitiveBytes: not a primitive $other")

    private def primitiveReadExpr(t: TypeRef, segExpr: String, offExpr: String): String =
        val raw = s"$segExpr.get(${valueLayoutOf(t)}, $offExpr)"
        if t == TypeRef.BooleanT then booleanUnmarshal(raw) else raw

    private def layoutConst(s: StructSpec): String = layoutConstName(s)

    private def layoutConstName(s: StructSpec): String =
        // Lower-case first letter of the simpleName + "Layout".
        val n = s.simpleName
        val h = if n.isEmpty then "layout" else n.head.toLower.toString + n.tail + "Layout"
        h
    end layoutConstName

    /** The Layouts accessor for a primitive array's element type. */
    private def layoutOpsFor(t: TypeRef): String = t match
        case TypeRef.ByteT   => "Layouts.byteL"
        case TypeRef.ShortT  => "Layouts.shortL"
        case TypeRef.IntT    => "Layouts.intL"
        case TypeRef.LongT   => "Layouts.longL"
        case TypeRef.FloatT  => "Layouts.floatL"
        case TypeRef.DoubleT => "Layouts.doubleL"
        case other           => throw new IllegalStateException(s"layoutOpsFor: $other is not a supported Array element")

end JvmEmitter
