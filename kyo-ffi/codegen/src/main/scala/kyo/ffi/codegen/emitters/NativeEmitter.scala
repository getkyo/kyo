package kyo.ffi.codegen.emitters

import kyo.ffi.codegen.model.*

/** Emits the Scala Native (`@extern`) implementation source for a single binding trait.
  *
  * Given a [[kyo.ffi.codegen.model.TraitSpec]], [[NativeEmitter.emit]] returns the full contents of `{TraitName}Impl.scala`:
  *
  *   - A top-level `@extern @link(library)` object named `` `{Trait}$externs` `` holding the C signatures. Scala Native requires `@extern`
  *     methods to sit on a top-level object, never inside a class.
  *   - A `final class {Trait}Impl extends {Trait}` that marshals Scala values into the `@extern` signatures and captures `errno` after
  *     every call.
  *   - An empty companion that performs the `kyo.ffi.internal.AbiCheck` check at class-loading time.
  *
  * The [[JvmEmitter]] is the structural reference.
  */
object NativeEmitter extends EmitterBase.Ops with PlatformTypes:

    /** Emit the full contents of `{spec.simpleName}Impl.scala`.
      *
      * @param headersAvailable
      *   when `false`, the impl class methods become runtime stubs throwing `UnsupportedOperationException` and the `@extern` object is
      *   emitted empty. This allows OS-specific bindings to coexist in the same source tree without link failures on platforms that lack
      *   the required C headers.
      */
    def emit(spec: TraitSpec, headersAvailable: Boolean = true): String =
        val sb = new StringBuilder
        sb ++= emitHeader(spec)
        sb ++= "\n"
        if headersAvailable then
            sb ++= emitImplClass(spec)
        else
            sb ++= emitStubImplClass(spec)
        end if
        sb ++= "\n"
        sb ++= emitImplCompanion(spec)
        sb ++= "\n"
        if headersAvailable then
            sb ++= emitExternObject(spec)
        else
            sb ++= emitEmptyExternObject(spec)
        end if
        sb.toString
    end emit

    // -------------------------------------------------------------------------
    // Header
    // -------------------------------------------------------------------------

    private[codegen] def emitHeader(spec: TraitSpec): String =
        emitHeader(
            spec,
            """import scala.scalanative.unsafe.*
              |import scala.scalanative.libc.errno.errno
              |import scala.scalanative.reflect.annotation.EnableReflectiveInstantiation
              |import kyo.ffi.*
              |import kyo.ffi.internal.{AbiCheck, NativePtr, StructAbiCheck}
              |""".stripMargin
        )
    end emitHeader

    // -------------------------------------------------------------------------
    // Impl class
    // -------------------------------------------------------------------------

    private[codegen] def emitImplClass(spec: TraitSpec): String =
        val sb = new StringBuilder
        // `@EnableReflectiveInstantiation` tells the Scala Native linker to retain the nullary constructor so
        // `scala.reflect.Reflect.lookupInstantiatableClass` (used by `Ffi.load`) can instantiate it at runtime.
        sb ++= s"@EnableReflectiveInstantiation final class ${spec.simpleName}Impl extends ${spec.simpleName}:\n"
        sb ++= s"    import `${spec.simpleName}$$externs` as ext\n"
        // Import packed-struct byte-size constants from the companion so method bodies can reference them.
        if spec.structs.exists(_.packed) then
            sb ++= s"    import ${spec.simpleName}Impl.*\n"
        spec.methods.foreach { m =>
            sb ++= "\n"
            if m.hasVarargs then
                sb ++= emitVarargsStubMethod(m, spec)
            else
                sb ++= emitMethod(m, spec)
            end if
        }
        sb ++= s"end ${spec.simpleName}Impl\n"
        sb.toString
    end emitImplClass

    /** Emit a runtime stub for a variadic method. Scala Native's `@extern` cannot express variadic function pointers, the language-level
      * `CFuncPtrN` carriers are fixed-arity. The stub matches the trait signature (including the trailing `args: Any*`) and throws
      * `UnsupportedOperationException` at runtime.
      */
    private[codegen] def emitVarargsStubMethod(method: MethodSpec, spec: TraitSpec): String =
        val sig = methodSignature(method, includeVarargs = true)
        val body =
            s"""        throw new UnsupportedOperationException("Variadic method '${method.scalaName}' is not supported on Scala Native.")"""
        sig + "\n" + body + "\n"
    end emitVarargsStubMethod

    private[codegen] def emitMethod(method: MethodSpec, spec: TraitSpec): String =
        val sig = methodSignature(method)
        // Indent body 8 spaces for the plain case; Zone-wrapped methods get their own indentation.
        val body = indentBody(emitMethodBody(method, spec))
        sig + "\n" + body + "\n"
    end emitMethod

    /** Emit the body, including any `Zone.acquire` wrapping.
      *
      * When `method.blocking`, the synchronous body (including any `Zone` logic) is wrapped in a
      * `kyo.ffi.internal.BlockingBridge.run { ... }` block so the already-computed result is lifted into
      * `kyo.Fiber.Unsafe[<ret>, Any]` (matching the signature emitted by `methodSignature`). The `@blocking @extern`
      * downcall itself stays synchronous on the carrier thread.
      */
    private[codegen] def emitMethodBody(method: MethodSpec, spec: TraitSpec): String =
        val sync = emitPlainMethodBody(method, spec)
        if method.blocking then wrapBlockingRun(sync) else sync
    end emitMethodBody

    /** Method body; shared between callback-free, transient-callback, and retained-callback methods.
      *
      * Callback parameters are marshalled into `CFuncPtrN[...]` values at the top of the method body via Scala Native's implicit conversion
      * from `FunctionN`. Retained callbacks additionally pin each wrapper on the user-supplied guard via `NativeGuard.unsafeRetain` so the
      * closure survives `guard.close()`. `Ffi.Guard` is consumed locally and never crosses the FFI boundary.
      */
    private def emitPlainMethodBody(method: MethodSpec, spec: TraitSpec): String =
        val structsByName = EmitterBase.structsByName(spec)

        // Inspect the signature to decide whether we need a Zone (for CString allocation, Array copy, struct-alloc, or multi-value out-cells).
        val hasString = method.params.exists(_.tpe == TypeRef.StringT)
        val hasArrayBlocking = method.blocking && method.params.exists {
            case ParamSpec(_, TypeRef.ArrayT(_)) => true
            case _                               => false
        }
        val hasArrayNonBlocking = !method.blocking && method.params.exists {
            case ParamSpec(_, TypeRef.ArrayT(_)) => true
            case _                               => false
        }
        val hasStructParam = method.params.exists(p => p.tpe.isInstanceOf[TypeRef.StructT])
        val hasMultiValue = method.returnShape match
            case ReturnShape.MultiValue(_) => true
            case _                         => false
        val hasStructReturn = method.returnShape match
            case ReturnShape.Struct(_) => true
            case _                     => false

        val needsZone = hasString || hasArrayBlocking || hasStructParam || hasMultiValue || hasStructReturn || hasArrayNonBlocking

        // Build the inner body (what lives inside Zone.acquire { implicit z => ... } when present).
        val inner = emitBodyInner(method, spec, structsByName)

        if needsZone then
            val sb = new StringBuilder
            sb ++= "Zone.acquire { implicit z =>\n"
            inner.linesIterator.foreach { line =>
                sb ++= "    "
                sb ++= line
                sb ++= "\n"
            }
            sb ++= "}"
            sb.toString
        else
            inner
        end if
    end emitPlainMethodBody

    /** Core body (no Zone wrapper): marshal args, call ext.cSymbol, capture errno, construct result. */
    private def emitBodyInner(method: MethodSpec, spec: TraitSpec, structsByName: Map[String, StructSpec]): String =
        case class Marshalled(
            paramName: String,
            passExpr: String,
            setup: List[String],
            /** For transient callbacks: the `CallbackRegistry.popTransient_<SHAPE>()` teardown line to emit AFTER the extern call inside a
              * `finally` block. Retained callbacks and all non-callback params have `Nil`, their cleanup (if any) is driven by the guard's
              * close, not by per-call bracketing.
              */
            teardown: List[String]
        )

        // The user-supplied `Ffi.Guard` is consumed locally (used to pin retained callbacks) and never crosses the FFI boundary. All
        // other param kinds produce exactly one `Marshalled` entry, callbacks route through `CallbackRegistry` (see FnPtrT below).
        val marshalled: List[Marshalled] = method.params.flatMap { p =>
            val name = safeName(p.name)
            p.tpe match
                case TypeRef.BooleanT =>
                    // C-side is CInt; Scala-side Boolean.
                    List(Marshalled(p.name, s"if $name then 1 else 0", Nil, Nil))
                case TypeRef.ByteT | TypeRef.ShortT | TypeRef.IntT | TypeRef.LongT | TypeRef.FloatT | TypeRef.DoubleT =>
                    List(Marshalled(p.name, name, Nil, Nil))
                case TypeRef.UnitT =>
                    List(Marshalled(p.name, "()", Nil, Nil))
                case TypeRef.StringT =>
                    val cN = s"${name}C"
                    List(Marshalled(p.name, cN, List(s"val $cN = toCString($name)"), Nil))
                case TypeRef.BufferT(_) =>
                    val pN = s"${name}Ptr"
                    // Checked unwrap: alien `Buffer.Raw` implementations surface as a diagnostic
                    // naming the binding + method instead of a silent `ClassCastException` at the `.ptr` access.
                    List(Marshalled(
                        p.name,
                        pN,
                        List(
                            s"val $pN = kyo.ffi.internal.FfiUnsafe.expect[NativePtr](" +
                                s"""$name.raw.asInstanceOf[AnyRef], classOf[NativePtr], "NativePtr on Native", "${spec.fqcn}", "${method.scalaName}").ptr"""
                        ),
                        Nil
                    ))
                case TypeRef.ArrayT(elem) =>
                    val pN = s"${name}Ptr"
                    // Both blocking and non-blocking paths zone-copy on Native (no pinning primitive).
                    val setup = List(
                        s"val $pN = alloc[${cPrimitiveOf(elem)}]($name.length)",
                        s"var ${name}I = 0",
                        s"while ${name}I < $name.length do",
                        s"    ($pN + ${name}I.toLong)(0) = $name(${name}I)",
                        s"    ${name}I += 1"
                    )
                    List(Marshalled(p.name, pN, setup, Nil))
                case TypeRef.StructT(fqcn) =>
                    val sSpec = structsByName.getOrElse(
                        fqcn,
                        throw new IllegalStateException(s"struct '$fqcn' not found in trait ${spec.fqcn}")
                    )
                    val pN = s"${name}Ptr"
                    // Collect teardown lines for any FnPtrT fields in the struct (CallbackRegistry pops).
                    val td = scala.collection.mutable.ListBuffer.empty[String]
                    val allocExpr =
                        if sSpec.packed then
                            s"val $pN = alloc[Byte](${packedSizeConst(sSpec)})"
                        else
                            s"val $pN = alloc[${externStructTypeName(sSpec, structsByName)}](1)"
                    val setup =
                        allocExpr ::
                            emitStructWrite(name, pN, sSpec, structsByName, spec.fqcn, method.scalaName, Some(td))
                    // Pass struct params as POINTERS (not dereferenced by-value) to match the JVM emitter's
                    // `ADDRESS` layout convention. The JVM path hands C a pointer; Native must do the same
                    // so the same C function (`int fn(const Circle *c)`) works on both platforms.
                    List(Marshalled(p.name, pN, setup, td.toList))
                case TypeRef.UnionT(variants) =>
                    val pN    = s"${name}Ptr"
                    val setup = emitNativeUnionParamWrite(name, pN, variants, structsByName, spec, method.scalaName)
                    List(Marshalled(p.name, pN, setup, Nil))
                case TypeRef.HandleT(_) =>
                    // Handle[A] pointer: unwrap to get the platform carrier (NativePtr on Native).
                    val pN = s"${name}Ptr"
                    List(Marshalled(
                        p.name,
                        pN,
                        List(
                            s"val $pN = kyo.ffi.internal.FfiUnsafe.expect[NativePtr](" +
                                s"""Ffi.Handle.unwrap($name).asInstanceOf[AnyRef], classOf[NativePtr], "NativePtr on Native", "${spec.fqcn}", "${method.scalaName}").ptr"""
                        ),
                        Nil
                    ))
                case TypeRef.EnumT(_) =>
                    // Enum parameter: extract the underlying Int via `.value` and pass as CInt.
                    List(Marshalled(p.name, s"$name.value", Nil, Nil))
                case TypeRef.FnPtrT(params, ret) =>
                    // Scala Native 0.5's `CFuncPtr.fromScalaFunction` rejects closures over local state, so the user callback cannot be
                    // marshalled directly. Instead we route it through `kyo.ffi.internal.CallbackRegistry`, which exposes top-level
                    // trampoline `def`s that `fromScalaFunction` accepts, see `CallbackRegistry.scala` + `RetainedTrampolines.scala`.
                    val shape    = NativeCallbackCatalog.requireShapeId(method, params, ret, cFuncPtrType(params, ret))
                    val pushName = s"pushTransient_$shape"
                    val popName  = s"popTransient_$shape"
                    val cbType   = cFuncPtrType(params, ret)
                    method.callbackKind match
                        case CallbackKind.Transient =>
                            // Bracket the extern call with push/pop on the per-shape stack; the trampoline reads the callback from the
                            // top of that stack when C fires it. Eta-expansion of the top-level trampoline keeps `fromScalaFunction`
                            // happy. Nested re-entrant calls of the same shape stack naturally. The push carries the binding + method
                            // tag so the trampoline can name the callback when the user function throws.
                            val ptrVal   = s"${name}Ptr"
                            val trampRef = s"kyo.ffi.internal.CallbackRegistry.${NativeCallbackCatalog.transientTrampolineName(shape)}"
                            val fromFn =
                                if params.isEmpty then s"CFuncPtr0.fromScalaFunction($trampRef)"
                                else s"CFuncPtr${params.size}.fromScalaFunction($trampRef)"
                            val setup = List(
                                s"val $ptrVal: $cbType = $fromFn",
                                s"""kyo.ffi.internal.CallbackRegistry.$pushName("${spec.fqcn}", "${method.scalaName}", $name)"""
                            )
                            val teardown = List(
                                s"kyo.ffi.internal.CallbackRegistry.$popName()"
                            )
                            List(Marshalled(p.name, ptrVal, setup, teardown))
                        case CallbackKind.Retained =>
                            // Claim a pool slot; the claim returns a pre-built `CFuncPtr` whose trampoline reads from that specific slot.
                            // Register the slot with the guard via `unsafeRetainRetainedSlot(shape, slot)`, which records the slot with the
                            // leak-detector token AND schedules the close-time release, a leaked guard still frees the pool slot.
                            // Claim carries the binding + method tag so the trampoline can name the callback on exception (#1 / #20).
                            val slotVal = s"${name}Slot"
                            val ptrVal  = s"${name}Ptr"
                            val guardParam = method.params.find(_.tpe == TypeRef.GuardT).getOrElse(
                                throw new IllegalStateException(
                                    s"Retained callback method '${method.scalaName}' is missing an Ffi.Guard parameter"
                                )
                            )
                            val claim        = NativeCallbackCatalog.claimRetainedName(shape)
                            val guardRefExpr = s"${safeName(guardParam.name)}.asInstanceOf[kyo.ffi.internal.NativeGuard]"
                            // Pass the guard's token as the first argument. The registry claims any free slot from the
                            // global pool (1024 slots per shape by default). Slots are released by `NativeGuard.close()`.
                            val setup = List(
                                s"""val ($slotVal, $ptrVal) = kyo.ffi.internal.CallbackRegistry.$claim($guardRefExpr.guardToken, "${spec.fqcn}", "${method.scalaName}", $name)""",
                                s"""$guardRefExpr.unsafeRetainRetainedSlot("$shape", $slotVal)"""
                            )
                            List(Marshalled(p.name, ptrVal, setup, Nil))
                        case CallbackKind.None =>
                            throw new IllegalStateException(
                                s"Method '${method.scalaName}' has an FnPtrT parameter '${p.name}' but callbackKind = None"
                            )
                    end match
                case TypeRef.GuardT =>
                    // Guard never crosses the FFI boundary; it is consumed locally (see FnPtrT branch for retained callbacks).
                    Nil
            end match
        }

        // Out-param allocations for ReturnShape.MultiValue.
        case class OutCell(name: String, tpe: TypeRef)
        val outCells: List[OutCell] = method.returnShape match
            case ReturnShape.MultiValue(sSpec) =>
                sSpec.fields.drop(1).map { f =>
                    OutCell(s"${f.name}Out", f.tpe)
                }
            case _ => Nil

        val sb = new StringBuilder

        // Setup lines for each marshalled parameter.
        marshalled.flatMap(_.setup).foreach { line =>
            sb ++= line
            sb ++= "\n"
        }

        // Out-cell allocations (multi-value).
        outCells.foreach { o =>
            sb ++= s"val ${o.name} = alloc[${cPrimitiveOf(o.tpe)}](1)\n"
        }

        // Struct-return out-cell (C returns the struct into an out-param via pointer).
        method.returnShape match
            case ReturnShape.Struct(sSpec) =>
                if sSpec.packed then
                    sb ++= s"val out = alloc[Byte](${packedSizeConst(sSpec)})\n"
                else
                    sb ++= s"val out = alloc[${externStructTypeName(sSpec, structsByName)}](1)\n"
            case _ => ()
        end match

        // Build the call argument list. The struct-return out-pointer goes FIRST (before the user args), matching the
        // JVM emitter's out-ADDRESS-first convention: a `@Ffi.byValue def f(args): S` binding maps to the C function
        // `void f(S* out, ...args)`. Multi-value out-cells append AFTER the user args (their C signature appends one
        // out-pointer per trailing field). A method is either a struct return or a multi-value return, never both.
        val callArgs: List[String] =
            val base = marshalled.map(_.passExpr)
            val withOutMulti = method.returnShape match
                case ReturnShape.MultiValue(_) => base ++ outCells.map(_.name)
                case _                         => base
            method.returnShape match
                case ReturnShape.Struct(_) => "out" :: withOutMulti
                case _                     => withOutMulti
        end callArgs

        val callExpr  = s"ext.${method.cSymbol}(${callArgs.mkString(", ")})"
        val fqnLit    = s""""${spec.fqcn}""""
        val methodLit = s""""${method.scalaName}""""

        // Invoke + errno capture + result, rendered as a block so it can be wrapped in try/finally when transient callbacks
        // need per-call bracketing. Clear errno before the call so the post-call value reflects only this invocation.
        val resultExpr: String = method.returnShape match
            case ReturnShape.Void =>
                "()"
            case ReturnShape.Primitive(TypeRef.BooleanT) =>
                "retVal != 0"
            case ReturnShape.Primitive(_) =>
                "retVal"
            case ReturnShape.MultiValue(sSpec) =>
                val head = sSpec.fields.head
                val headExpr =
                    head.tpe match
                        case TypeRef.BooleanT => "retVal != 0"
                        case _                => "retVal"
                val tailExprs = sSpec.fields.drop(1).zip(outCells).map { case (f, o) =>
                    f.tpe match
                        case TypeRef.BooleanT => s"!${o.name} != 0"
                        case _                => s"!${o.name}"
                }
                s"${sSpec.fqcn}(${(headExpr :: tailExprs).mkString(", ")})"
            case ReturnShape.Struct(sSpec) =>
                val checkedBorrows = spec.companion.exists(_.checkedBorrows)
                emitStructRead(sSpec, structsByName, "out", checkedBorrows)
            case ReturnShape.HandleReturn(_, true) =>
                "if retVal == null then kyo.Absent else kyo.Present(Ffi.Handle.wrap(new NativePtr(retVal)))"
            case ReturnShape.HandleReturn(_, _) =>
                s"""if retVal == null then throw new kyo.ffi.FfiNullPointer("${spec.fqcn}.${method.scalaName} returned null") else Ffi.Handle.wrap(new NativePtr(retVal))"""
            case ReturnShape.EnumReturn(fqcn) =>
                s"$fqcn.fromInt(retVal)"
            case ReturnShape.BorrowedString(_) =>
                "Ffi.Borrowed.wrap(if retVal == null then null else fromCString(retVal))"
            case ReturnShape.BorrowedBuffer(elem, sizeParam) =>
                val sp             = safeName(sizeParam)
                val elemScala      = scalaTypeOf(elem)
                val checkedBorrows = spec.companion.exists(_.checkedBorrows)
                val checkedCall =
                    s"Buffer.Unsafe.wrapBorrowedChecked[$elemScala](new NativePtr(retVal), $sp.toInt, kyo.ffi.internal.BufferFactory.currentBorrowOwner())"
                val uncheckedCall =
                    s"Buffer.Unsafe.wrapBorrowed[$elemScala](new NativePtr(retVal), $sp.toInt)"
                val wrapLine =
                    if checkedBorrows then checkedCall
                    else s"""(if (java.lang.System.getProperty("kyo.ffi.checkedBorrows") == "true") $checkedCall else $uncheckedCall)"""
                s"Ffi.Borrowed.wrap(if retVal == null then null else $wrapLine)"
        end resultExpr

        // Build the callBlock: clear errno, invoke, capture errno, construct result with errno handling.
        val hasRetVal = method.returnShape match
            case ReturnShape.Void | ReturnShape.Struct(_) => false
            case _                                        => true

        val callBlockSb = new StringBuilder
        if hasRetVal then
            callBlockSb ++= s"val retVal = $callExpr\n"
        else
            callBlockSb ++= s"$callExpr\n"
        end if
        if method.withError then
            callBlockSb ++= "val __errno = errno\n"
            callBlockSb ++= s"val __kyoResult = $resultExpr\n"
            callBlockSb ++= "Ffi.Outcome.fromValueErrno(__kyoResult.toLong, __errno)"
        else
            callBlockSb ++= resultExpr
        end if
        val callBlock = callBlockSb.toString

        // Teardown lines for transient callbacks (reverse push order: LIFO). Emitted in a `finally` block so the pops run even if the
        // FFI call throws. Retained callbacks don't contribute here, their cleanup fires at guard close, not per-call.
        val teardownLines = marshalled.flatMap(_.teardown).reverse

        if teardownLines.isEmpty then
            sb ++= callBlock
            sb ++= "\n"
        else
            sb ++= "try\n"
            callBlock.linesIterator.foreach { l =>
                sb ++= "    "
                sb ++= l
                sb ++= "\n"
            }
            sb ++= "finally\n"
            teardownLines.foreach { l =>
                sb ++= "    "
                sb ++= l
                sb ++= "\n"
            }
        end if

        sb.toString
    end emitBodyInner

    /** Emit `ptr.fieldName = value` lines for writing a case-class into a zone-allocated struct.
      *
      * Nested struct fields recurse, the accessor `ptrVal.atN` produces a `Ptr` to the nested sub-struct, and [[emitStructWrite]] is
      * called recursively against it. Primitives/strings/pointers use the direct `(!ptrVal)._N = ...` assignment form.
      */
    private def emitStructWrite(
        caseClassVal: String,
        ptrVal: String,
        sSpec: StructSpec,
        structsByName: Map[String, StructSpec],
        bindingFqn: String,
        methodName: String
    ): List[String] = emitStructWrite(caseClassVal, ptrVal, sSpec, structsByName, bindingFqn, methodName, None)

    /** Emit struct writes. When `teardownCollector` is provided, FnPtrT fields push their callback via CallbackRegistry and the
      * corresponding pop lines are appended to the collector.
      */
    private def emitStructWrite(
        caseClassVal: String,
        ptrVal: String,
        sSpec: StructSpec,
        structsByName: Map[String, StructSpec],
        bindingFqn: String,
        methodName: String,
        teardownCollector: Option[scala.collection.mutable.ListBuffer[String]]
    ): List[String] =
        val buf = List.newBuilder[String]
        structFieldLayouts(sSpec, structsByName, forWrite = true).foreach { case FieldLayout(f, offset, i) =>
            val fieldAcc = s"$caseClassVal.${safeName(f.name)}"

            if sSpec.packed then
                // Packed struct: use byte-offset pointer arithmetic. The ptrVal is a Ptr[Byte].
                f.tpe match
                    case TypeRef.BooleanT =>
                        buf += s"!($ptrVal + $offset).asInstanceOf[Ptr[CInt]] = if $fieldAcc then 1 else 0"
                    case TypeRef.ByteT =>
                        buf += s"!($ptrVal + $offset) = $fieldAcc"
                    case TypeRef.ShortT =>
                        buf += s"!($ptrVal + $offset).asInstanceOf[Ptr[CShort]] = $fieldAcc"
                    case TypeRef.IntT =>
                        buf += s"!($ptrVal + $offset).asInstanceOf[Ptr[CInt]] = $fieldAcc"
                    case TypeRef.LongT =>
                        buf += s"!($ptrVal + $offset).asInstanceOf[Ptr[CLongLong]] = $fieldAcc"
                    case TypeRef.FloatT =>
                        buf += s"!($ptrVal + $offset).asInstanceOf[Ptr[CFloat]] = $fieldAcc"
                    case TypeRef.DoubleT =>
                        buf += s"!($ptrVal + $offset).asInstanceOf[Ptr[CDouble]] = $fieldAcc"
                    case TypeRef.StringT =>
                        buf += s"!($ptrVal + $offset).asInstanceOf[Ptr[CString]] = toCString($fieldAcc)"
                    case TypeRef.BufferT(_) =>
                        buf += (s"!($ptrVal + $offset).asInstanceOf[Ptr[Ptr[Byte]]] = kyo.ffi.internal.FfiUnsafe.expect[NativePtr](" +
                            s"""$fieldAcc.raw.asInstanceOf[AnyRef], classOf[NativePtr], "NativePtr on Native", "$bindingFqn", "$methodName").ptr""")
                    case TypeRef.StructT(n) =>
                        val child = structsByName.getOrElse(
                            n,
                            throw new IllegalStateException(s"nested struct '$n' not found")
                        )
                        val childPtr = s"${caseClassVal}_${f.name}_ptr"
                        buf += s"val $childPtr = ($ptrVal + $offset)"
                        buf ++= emitStructWrite(fieldAcc, childPtr, child, structsByName, bindingFqn, methodName, teardownCollector)
                    case TypeRef.HandleT(_) =>
                        buf += (s"!($ptrVal + $offset).asInstanceOf[Ptr[Ptr[Byte]]] = kyo.ffi.internal.FfiUnsafe.expect[NativePtr](" +
                            s"""Ffi.Handle.unwrap($fieldAcc).asInstanceOf[AnyRef], classOf[NativePtr], "NativePtr on Native", "$bindingFqn", "$methodName").ptr""")
                    case TypeRef.EnumT(_) =>
                        buf += s"!($ptrVal + $offset).asInstanceOf[Ptr[CInt]] = $fieldAcc.value"
                    case TypeRef.FnPtrT(params, ret) =>
                        val shape = NativeCallbackCatalog.shapeId(params, ret).getOrElse(
                            throw new IllegalStateException(
                                s"No catalog shape for struct field callback '${sSpec.simpleName}.${f.name}' " +
                                    s"with signature (${params.map(scalaTypeOf).mkString(", ")}) => ${scalaTypeOf(ret)}. " +
                                    "Add a matching shape to project/CallbackShapesGen.scala and NativeCallbackCatalog."
                            )
                        )
                        val pushName = s"pushTransient_$shape"
                        val popName  = s"popTransient_$shape"
                        val cbType   = cFuncPtrType(params, ret)
                        val ptrVal2  = s"${caseClassVal}_${f.name}_cfp"
                        val trampRef = s"kyo.ffi.internal.CallbackRegistry.${NativeCallbackCatalog.transientTrampolineName(shape)}"
                        val fromFn =
                            if params.isEmpty then s"CFuncPtr0.fromScalaFunction($trampRef)"
                            else s"CFuncPtr${params.size}.fromScalaFunction($trampRef)"
                        buf += s"val $ptrVal2: $cbType = $fromFn"
                        buf += s"""kyo.ffi.internal.CallbackRegistry.$pushName("$bindingFqn", "$methodName", $fieldAcc)"""
                        buf += s"!($ptrVal + $offset).asInstanceOf[Ptr[Ptr[Byte]]] = CFuncPtr.toPtr($ptrVal2).asInstanceOf[Ptr[Byte]]"
                        teardownCollector.foreach { td =>
                            td += s"kyo.ffi.internal.CallbackRegistry.$popName()"
                        }
                    case TypeRef.UnionT(_) | TypeRef.ArrayT(_) | TypeRef.GuardT | TypeRef.UnitT =>
                        throw new IllegalStateException(s"unsupported packed struct field type in '${sSpec.simpleName}.${f.name}'")
                end match
            else
                // Non-packed struct: use CStruct field syntax (_1, _2, ...).
                val target = s"(!$ptrVal)._${i + 1}"
                f.tpe match
                    case TypeRef.BooleanT => buf += s"$target = if $fieldAcc then 1 else 0"
                    case TypeRef.ByteT | TypeRef.ShortT | TypeRef.IntT | TypeRef.LongT |
                        TypeRef.FloatT | TypeRef.DoubleT => buf += s"$target = $fieldAcc"
                    case TypeRef.StringT    => buf += s"$target = toCString($fieldAcc)"
                    case TypeRef.BufferT(_) =>
                        // Checked unwrap.
                        buf += (s"$target = kyo.ffi.internal.FfiUnsafe.expect[NativePtr](" +
                            s"""$fieldAcc.raw.asInstanceOf[AnyRef], classOf[NativePtr], "NativePtr on Native", "$bindingFqn", "$methodName").ptr""")
                    case TypeRef.StructT(n) =>
                        val child = structsByName.getOrElse(
                            n,
                            throw new IllegalStateException(s"nested struct '$n' not found")
                        )
                        val childPtr = s"${caseClassVal}_${f.name}_ptr"
                        buf += s"val $childPtr = $ptrVal.at${i + 1}"
                        buf ++= emitStructWrite(fieldAcc, childPtr, child, structsByName, bindingFqn, methodName, teardownCollector)
                    case TypeRef.UnionT(variants) =>
                        // Union field: runtime type match at the field's offset in the struct.
                        val unionPtr = s"${caseClassVal}_${f.name}_unionPtr"
                        buf += s"val $unionPtr = $ptrVal.at${i + 1}.asInstanceOf[Ptr[Byte]]"
                        buf ++= emitNativeUnionVariantMatch(fieldAcc, unionPtr, variants, structsByName, bindingFqn, methodName)
                    case TypeRef.HandleT(_) =>
                        // Handle field: unwrap via Ffi.Handle.unwrap to get the NativePtr and write it.
                        buf += (s"$target = kyo.ffi.internal.FfiUnsafe.expect[NativePtr](" +
                            s"""Ffi.Handle.unwrap($fieldAcc).asInstanceOf[AnyRef], classOf[NativePtr], "NativePtr on Native", "$bindingFqn", "$methodName").ptr""")
                    case TypeRef.EnumT(_) =>
                        // Enum field: write the Int value.
                        buf += s"$target = $fieldAcc.value"
                    case TypeRef.FnPtrT(params, ret) =>
                        // Function pointer struct field: route through CallbackRegistry (Scala Native can't
                        // convert closures via CFuncPtr.fromScalaFunction).  Push the callback onto the
                        // per-shape transient stack, get the trampoline CFuncPtr, write its pointer.
                        val shape = NativeCallbackCatalog.shapeId(params, ret).getOrElse(
                            throw new IllegalStateException(
                                s"No catalog shape for struct field callback '${sSpec.simpleName}.${f.name}' " +
                                    s"with signature (${params.map(scalaTypeOf).mkString(", ")}) => ${scalaTypeOf(ret)}. " +
                                    "Add a matching shape to project/CallbackShapesGen.scala and NativeCallbackCatalog."
                            )
                        )
                        val pushName = s"pushTransient_$shape"
                        val popName  = s"popTransient_$shape"
                        val cbType   = cFuncPtrType(params, ret)
                        val ptrVal2  = s"${caseClassVal}_${f.name}_cfp"
                        val trampRef = s"kyo.ffi.internal.CallbackRegistry.${NativeCallbackCatalog.transientTrampolineName(shape)}"
                        val fromFn =
                            if params.isEmpty then s"CFuncPtr0.fromScalaFunction($trampRef)"
                            else s"CFuncPtr${params.size}.fromScalaFunction($trampRef)"
                        buf += s"val $ptrVal2: $cbType = $fromFn"
                        buf += s"""kyo.ffi.internal.CallbackRegistry.$pushName("$bindingFqn", "$methodName", $fieldAcc)"""
                        buf += s"$target = CFuncPtr.toPtr($ptrVal2).asInstanceOf[Ptr[Byte]]"
                        teardownCollector.foreach { td =>
                            td += s"kyo.ffi.internal.CallbackRegistry.$popName()"
                        }
                    case TypeRef.ArrayT(_) | TypeRef.GuardT | TypeRef.UnitT =>
                        throw new IllegalStateException(s"unsupported struct field type in '${sSpec.simpleName}.${f.name}'")
                end match
            end if
        }
        buf.result()
    end emitStructWrite

    /** Scala Native primitive/struct name used to reinterpret a union's shared bytes for one variant field. Mirrors
      * [[externStructFieldType]] but drops field kinds that unions reject (String, Buffer, Array, FnPtr, Guard, enforced at the inspector
      * level).
      */
    private def unionFieldCarrier(t: TypeRef, structsByName: Map[String, StructSpec]): String = t match
        case TypeRef.BooleanT => "CInt"
        case TypeRef.ByteT    => "CChar"
        case TypeRef.ShortT   => "CShort"
        case TypeRef.IntT     => "CInt"
        case TypeRef.LongT    => "CLongLong"
        case TypeRef.FloatT   => "CFloat"
        case TypeRef.DoubleT  => "CDouble"
        case TypeRef.StructT(n) =>
            val child = structsByName.getOrElse(
                n,
                throw new IllegalStateException(s"nested struct '$n' not found in union field carrier")
            )
            externStructTypeName(child, structsByName)
        case other =>
            throw new IllegalStateException(s"unionFieldCarrier: unsupported $other (unions accept only primitives or nested structs)")

    /** Emit an expression that constructs the case-class by reading fields from a zone-allocated struct pointer.
      *
      * For non-packed structs, fields are read via `ptrVal.atN` (CStruct positional access). For packed structs, fields are read via
      * byte-offset pointer arithmetic on the raw `Ptr[Byte]`.
      */
    private def emitStructRead(
        sSpec: StructSpec,
        structsByName: Map[String, StructSpec],
        ptrVal: String,
        checkedBorrows: Boolean
    ): String =
        val layouts = structFieldLayouts(sSpec, structsByName, forWrite = false)
        // Infer buffer size from the sole Int/Long sibling in the same struct. TypeValidator rejects zero/multiple cases,
        // so a BufferT field reaching this emitter MUST have a resolvable sibling; otherwise this is an internal error.
        def bufferSizeExpr(bufferFieldName: String): String =
            sSpec.inferredBufferSizeField match
                case Some(sizeField) =>
                    val sizeLayout = layouts.find(_.field.name == sizeField.name).getOrElse(
                        throw new IllegalStateException(
                            s"internal: sibling '${sizeField.name}' not in layout for struct '${sSpec.fqcn}'"
                        )
                    )
                    val raw =
                        if sSpec.packed then
                            sizeField.tpe match
                                case TypeRef.IntT  => s"!($ptrVal + ${sizeLayout.offset}).asInstanceOf[Ptr[CInt]]"
                                case TypeRef.LongT => s"!($ptrVal + ${sizeLayout.offset}).asInstanceOf[Ptr[CLongLong]]"
                                case other =>
                                    throw new IllegalStateException(
                                        s"internal: sibling size type $other not Int/Long for struct '${sSpec.fqcn}'"
                                    )
                        else
                            s"(!$ptrVal)._${sizeLayout.index + 1}"
                    sizeField.tpe match
                        case TypeRef.LongT => s"$raw.toInt"
                        case _             => raw
                    end match
                case None =>
                    throw new IllegalStateException(
                        s"internal: struct '${sSpec.fqcn}' has BufferT field '$bufferFieldName' without resolved sibling, should have been rejected by TypeValidator"
                    )
        end bufferSizeExpr

        val parts = layouts.map { case FieldLayout(f, offset, i) =>
            if sSpec.packed then
                // Packed struct: byte-offset based reads from Ptr[Byte].
                f.tpe match
                    case TypeRef.BooleanT =>
                        s"!($ptrVal + $offset).asInstanceOf[Ptr[CInt]] != 0"
                    case TypeRef.ByteT =>
                        s"!($ptrVal + $offset)"
                    case TypeRef.ShortT =>
                        s"!($ptrVal + $offset).asInstanceOf[Ptr[CShort]]"
                    case TypeRef.IntT =>
                        s"!($ptrVal + $offset).asInstanceOf[Ptr[CInt]]"
                    case TypeRef.LongT =>
                        s"!($ptrVal + $offset).asInstanceOf[Ptr[CLongLong]]"
                    case TypeRef.FloatT =>
                        s"!($ptrVal + $offset).asInstanceOf[Ptr[CFloat]]"
                    case TypeRef.DoubleT =>
                        s"!($ptrVal + $offset).asInstanceOf[Ptr[CDouble]]"
                    case TypeRef.StringT =>
                        s"fromCString(!($ptrVal + $offset).asInstanceOf[Ptr[CString]])"
                    case TypeRef.BufferT(elem) =>
                        val rawPtr   = s"!($ptrVal + $offset).asInstanceOf[Ptr[Ptr[Byte]]]"
                        val sizeExpr = bufferSizeExpr(f.name)
                        val checkedCall =
                            s"Buffer.Unsafe.wrapBorrowedChecked[${scalaTypeOf(elem)}](new NativePtr($rawPtr), $sizeExpr, kyo.ffi.internal.BufferFactory.currentBorrowOwner())"
                        val uncheckedCall =
                            s"Buffer.Unsafe.wrapBorrowed[${scalaTypeOf(elem)}](new NativePtr($rawPtr), $sizeExpr)"
                        if checkedBorrows then checkedCall
                        else
                            s"""(if (java.lang.System.getProperty("kyo.ffi.checkedBorrows") == "true") $checkedCall else $uncheckedCall)"""
                    case TypeRef.HandleT(_) =>
                        s"Ffi.Handle.wrap(new NativePtr(!($ptrVal + $offset).asInstanceOf[Ptr[Ptr[Byte]]]))"
                    case TypeRef.EnumT(enumFqcn) =>
                        s"$enumFqcn.fromInt(!($ptrVal + $offset).asInstanceOf[Ptr[CInt]])"
                    case TypeRef.FnPtrT(_, _) =>
                        s"""throw new UnsupportedOperationException("Cannot read function pointer field '${f.name}' from C struct '${sSpec.simpleName}'")"""
                    case TypeRef.StructT(n) =>
                        val child = structsByName.getOrElse(
                            n,
                            throw new IllegalStateException(s"nested struct '$n' not found")
                        )
                        val childPtr = s"($ptrVal + $offset)"
                        emitStructRead(child, structsByName, childPtr, checkedBorrows)
                    case other =>
                        throw new IllegalStateException(s"unsupported packed struct-read field type: $other")
                end match
            else
                // Non-packed struct: CStruct positional field access.
                val src = s"(!$ptrVal)._${i + 1}"
                f.tpe match
                    case TypeRef.BooleanT => s"$src != 0"
                    case TypeRef.ByteT | TypeRef.ShortT | TypeRef.IntT | TypeRef.LongT |
                        TypeRef.FloatT | TypeRef.DoubleT => src
                    case TypeRef.StringT =>
                        s"fromCString($src)"
                    case TypeRef.BufferT(elem) =>
                        val sizeExpr = bufferSizeExpr(f.name)
                        val checkedCall =
                            s"Buffer.Unsafe.wrapBorrowedChecked[${scalaTypeOf(elem)}](new NativePtr($src), $sizeExpr, kyo.ffi.internal.BufferFactory.currentBorrowOwner())"
                        val uncheckedCall =
                            s"Buffer.Unsafe.wrapBorrowed[${scalaTypeOf(elem)}](new NativePtr($src), $sizeExpr)"
                        if checkedBorrows then checkedCall
                        else
                            s"""(if (java.lang.System.getProperty("kyo.ffi.checkedBorrows") == "true") $checkedCall else $uncheckedCall)"""
                        end if
                    case TypeRef.HandleT(_) =>
                        s"Ffi.Handle.wrap(new NativePtr($src.asInstanceOf[Ptr[Byte]]))"
                    case TypeRef.EnumT(enumFqcn) =>
                        s"$enumFqcn.fromInt($src)"
                    case TypeRef.FnPtrT(_, _) =>
                        s"""throw new UnsupportedOperationException("Cannot read function pointer field '${f.name}' from C struct '${sSpec.simpleName}'")"""
                    case TypeRef.StructT(n) =>
                        val child = structsByName.getOrElse(
                            n,
                            throw new IllegalStateException(s"nested struct '$n' not found")
                        )
                        val childPtr = s"$ptrVal.at${i + 1}"
                        emitStructRead(child, structsByName, childPtr, checkedBorrows)
                    case TypeRef.UnionT(variants) =>
                        // Union field read: return the first variant type (the bytes are ambiguous).
                        val unionPtr = s"$ptrVal.at${i + 1}.asInstanceOf[Ptr[Byte]]"
                        emitNativeUnionFieldRead(variants.head, unionPtr, structsByName, checkedBorrows)
                    case other =>
                        throw new IllegalStateException(s"unsupported struct-read field type: $other")
                end match
            end if
        }
        s"${sSpec.fqcn}(${parts.mkString(", ")})"
    end emitStructRead

    /** Read a union field's first variant from a Ptr[Byte]. */
    private def emitNativeUnionFieldRead(
        firstVariant: TypeRef,
        ptrExpr: String,
        structsByName: Map[String, StructSpec],
        checkedBorrows: Boolean
    ): String =
        firstVariant match
            case TypeRef.BooleanT => s"(!$ptrExpr.asInstanceOf[Ptr[CInt]]) != 0"
            case TypeRef.ByteT    => s"(!$ptrExpr.asInstanceOf[Ptr[CChar]])"
            case TypeRef.ShortT   => s"(!$ptrExpr.asInstanceOf[Ptr[CShort]])"
            case TypeRef.IntT     => s"(!$ptrExpr.asInstanceOf[Ptr[CInt]])"
            case TypeRef.LongT    => s"(!$ptrExpr.asInstanceOf[Ptr[CLongLong]])"
            case TypeRef.FloatT   => s"(!$ptrExpr.asInstanceOf[Ptr[CFloat]])"
            case TypeRef.DoubleT  => s"(!$ptrExpr.asInstanceOf[Ptr[CDouble]])"
            case TypeRef.StructT(n) =>
                val child = structsByName.getOrElse(n, throw new IllegalStateException(s"nested struct '$n' not found"))
                emitStructRead(
                    child,
                    structsByName,
                    s"$ptrExpr.asInstanceOf[Ptr[${externStructTypeName(child, structsByName)}]]",
                    checkedBorrows
                )
            case other =>
                throw new IllegalStateException(s"unsupported union variant read type: $other")
    end emitNativeUnionFieldRead

    // -------------------------------------------------------------------------
    // Union helpers
    // -------------------------------------------------------------------------

    /** Emit setup lines for marshalling a union parameter on Native. */
    private def emitNativeUnionParamWrite(
        paramName: String,
        ptrName: String,
        variants: List[TypeRef],
        structsByName: Map[String, StructSpec],
        spec: TraitSpec,
        methodName: String
    ): List[String] =
        val (unionSize, _) = unionSizeAndAlignNative(variants, structsByName)
        val buf            = List.newBuilder[String]
        buf += s"val $ptrName = stackalloc[Byte]($unionSize)"
        buf ++= emitNativeUnionVariantMatch(paramName, ptrName, variants, structsByName, spec.fqcn, methodName)
        buf.result()
    end emitNativeUnionParamWrite

    /** Emit the runtime type match that writes the correct union variant to a Ptr[Byte]. */
    private def emitNativeUnionVariantMatch(
        valExpr: String,
        ptrExpr: String,
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
                    buf += s"    case __v: java.lang.Boolean => !($ptrExpr.asInstanceOf[Ptr[CInt]]) = if __v.booleanValue then 1 else 0"
                case TypeRef.ByteT =>
                    buf += s"    case __v: java.lang.Byte => !($ptrExpr.asInstanceOf[Ptr[CChar]]) = __v.byteValue"
                case TypeRef.ShortT =>
                    buf += s"    case __v: java.lang.Short => !($ptrExpr.asInstanceOf[Ptr[CShort]]) = __v.shortValue"
                case TypeRef.IntT =>
                    buf += s"    case __v: java.lang.Integer => !($ptrExpr.asInstanceOf[Ptr[CInt]]) = __v.intValue"
                case TypeRef.LongT =>
                    buf += s"    case __v: java.lang.Long => !($ptrExpr.asInstanceOf[Ptr[CLongLong]]) = __v.longValue"
                case TypeRef.FloatT =>
                    buf += s"    case __v: java.lang.Float => !($ptrExpr.asInstanceOf[Ptr[CFloat]]) = __v.floatValue"
                case TypeRef.DoubleT =>
                    buf += s"    case __v: java.lang.Double => !($ptrExpr.asInstanceOf[Ptr[CDouble]]) = __v.doubleValue"
                case TypeRef.StructT(fqcn) =>
                    val sSpec = structsByName.getOrElse(
                        fqcn,
                        throw new IllegalStateException(s"union variant struct '$fqcn' not found")
                    )
                    buf += s"    case __v: $fqcn =>"
                    val childPtr = s"${valExpr.replace(".", "_")}_structPtr"
                    buf += s"        val $childPtr = $ptrExpr.asInstanceOf[Ptr[${externStructTypeName(sSpec, structsByName)}]]"
                    emitStructWrite("__v", childPtr, sSpec, structsByName, bindingFqn, methodName).foreach { line =>
                        buf += s"        $line"
                    }
                case other =>
                    throw new IllegalStateException(s"union variant type $other is not supported (only primitives and structs)")
            end match
        }
        buf += s"""    case __other => throw new kyo.ffi.FfiLoadError.Unsupported(s"Union variant not supported: $${__other.getClass}")"""
        buf.result()
    end emitNativeUnionVariantMatch

    /** Compute size and alignment for a union type on Native. */
    private def unionSizeAndAlignNative(variants: List[TypeRef], structsByName: Map[String, StructSpec]): (Long, Long) =
        val (maxSize, maxAlign) = variants.foldLeft((0L, 1L)) { case ((mxS, mxA), v) =>
            val (vs, va) = sizeAndAlign(v, structsByName, packed = false)
            (math.max(mxS, vs), math.max(mxA, va))
        }
        (align(maxSize, maxAlign), maxAlign)
    end unionSizeAndAlignNative

    // -------------------------------------------------------------------------
    // Stub impl class (headers unavailable)
    // -------------------------------------------------------------------------

    /** Emit an impl class where every method is a runtime stub. Used when the required C headers are not available on the build host. */
    private[codegen] def emitStubImplClass(spec: TraitSpec): String =
        val sb          = new StringBuilder
        val headersList = spec.headers.mkString(", ")
        sb ++= s"@EnableReflectiveInstantiation final class ${spec.simpleName}Impl extends ${spec.simpleName}:\n"
        spec.methods.foreach { m =>
            sb ++= "\n"
            sb ++= methodSignature(m, includeVarargs = true)
            sb ++= "\n"
            sb ++= s"""        throw new UnsupportedOperationException("Required header(s) [$headersList] not available on this platform.")\n"""
        }
        sb ++= s"end ${spec.simpleName}Impl\n"
        sb.toString
    end emitStubImplClass

    /** Emit an empty `@extern` object (no method declarations). Used when headers are unavailable, the object must still exist so the
      * companion can reference it, but it has no extern methods.
      */
    private[codegen] def emitEmptyExternObject(spec: TraitSpec): String =
        val sb = new StringBuilder
        sb ++= "@extern\n"
        // A `nativeBundled` library's C is compiled into the Native binary, so there is no shared library to `-l`:
        // emitting `@link` would force a missing `-l<library>` at link time. The symbols resolve via nativeConfig
        // linkingOptions instead. JVM/JS still load the shared library at runtime, so this only affects Native.
        if !spec.nativeBundled then sb ++= s"""@link("${spec.library}")\n"""
        val visibility =
            if spec.packageName.isEmpty then "private"
            else s"private[${spec.packageName.split('.').last}]"
        sb ++= s"$visibility object `${spec.simpleName}$$externs`:\n"
        sb ++= s"end `${spec.simpleName}$$externs`\n"
        sb.toString
    end emitEmptyExternObject

    // -------------------------------------------------------------------------
    // Impl companion
    // -------------------------------------------------------------------------

    private[codegen] def emitImplCompanion(spec: TraitSpec): String =
        val sb            = new StringBuilder
        val structsByName = EmitterBase.structsByName(spec)
        sb ++= s"object ${spec.simpleName}Impl:\n"
        sb ++= "    import scala.scalanative.unsafe.*\n"
        sb ++= abiCheckLine(spec.fqcn)
        sb ++= "\n"
        // Emit a byte-size constant per packed struct. Scala Native 0.5 has no `@packed` annotation; packed structs
        // are represented as raw `Ptr[Byte]` with manual offset-based field access. The constant is used by alloc sites.
        spec.structs.foreach { s =>
            if s.packed then
                val byteSize = packedByteSize(s, structsByName)
                sb ++= s"    inline val ${packedSizeConst(s)} = $byteSize\n"
        }
        // Struct ABI self-check. For each struct that participates in a binding (param, return, or transitively nested),
        // compare the code generator's expected byte size against Scala Native's `sizeof[<StructType>]`. Mismatch indicates that the
        // user forgot to add a packed struct to `Ffi.Config.packedStructs` (or listed a naturally-aligned one). The check runs at
        // companion class-init, always before the first `Ffi.load[T]` returns.
        // Packed structs use manual byte-offset layout and cannot be sizeof-checked (no CStruct type to measure).
        val checked = layoutRequiredStructs(spec, structsByName)
        spec.structs.foreach { s =>
            if checked.contains(s.fqcn) && !s.packed then
                val expected = structByteSize(s, structsByName)
                val typeRef  = structTypeRef(s, structsByName, qualify = false, spec.simpleName + "Impl")
                sb ++= s"""    StructAbiCheck.verifyByteSize("${spec.fqcn}", "${s.simpleName}", ${expected}L, sizeof[$typeRef].toLong)\n"""
        }
        sb ++= s"end ${spec.simpleName}Impl\n"
        sb.toString
    end emitImplCompanion

    /** Name of the packed-struct byte-size constant emitted in the impl companion. */
    private def packedSizeConst(s: StructSpec): String = s"${s.simpleName}_packedSize"

    /** Compute the packed byte size of a struct (sum of field sizes with no alignment padding). */
    private def packedByteSize(s: StructSpec, structsByName: Map[String, StructSpec]): Long =
        s.fields.foldLeft(0L) { (acc, f) =>
            acc + sizeAndAlign(f.tpe, structsByName, packed = true)._1
        }

    /** Produce the Scala type reference a consumer uses for `s`. Packed structs use raw `Byte` (accessed via `Ptr[Byte]` with manual offset
      * arithmetic). Non-packed structs use inline `CStruct{N}[...]`.
      */
    private def structTypeRef(s: StructSpec, structsByName: Map[String, StructSpec], qualify: Boolean, implName: String): String =
        if s.packed then "Byte"
        else externStructTypeName(s, structsByName)

    // -------------------------------------------------------------------------
    // @extern object
    // -------------------------------------------------------------------------

    private[codegen] def emitExternObject(spec: TraitSpec): String =
        val sb = new StringBuilder
        sb ++= "@extern\n"
        // See `emitEmptyExternObject`: a `nativeBundled` library's C is linked into the binary, so no `@link` is emitted.
        if !spec.nativeBundled then sb ++= s"""@link("${spec.library}")\n"""
        val visibility =
            if spec.packageName.isEmpty then "private"
            else s"private[${spec.packageName.split('.').last}]"
        sb ++= s"$visibility object `${spec.simpleName}$$externs`:\n"
        // The extern object is the linker-facing C-symbol table, so it holds exactly one declaration per distinct C symbol. Two binding
        // methods may resolve to the same symbol (e.g. a `@Ffi.blocking` overload and a synchronous one bound via the `symbols` override),
        // which would otherwise emit two `def <symbol>` externs with the same erased signature and fail to compile. Keep the first method per
        // symbol (declaration order) and mark the extern `@blocking` if ANY method sharing the symbol is blocking, since a synchronous caller
        // invoking a `@blocking`-annotated extern is safe (it just joins the GC blocking protocol) whereas the reverse is not.
        val emitted = scala.collection.mutable.Set.empty[String]
        spec.methods.foreach { m =>
            if !m.hasVarargs && !emitted.contains(m.cSymbol) then
                val _           = emitted.add(m.cSymbol)
                val anyBlocking = spec.methods.exists(o => !o.hasVarargs && o.cSymbol == m.cSymbol && o.blocking)
                sb ++= emitExternDecl(m, spec, forceBlocking = anyBlocking)
        }
        sb ++= s"end `${spec.simpleName}$$externs`\n"
        sb.toString
    end emitExternObject

    private[codegen] def emitExternDecl(method: MethodSpec, spec: TraitSpec, forceBlocking: Boolean = false): String =
        val structsByName = EmitterBase.structsByName(spec)
        val implName      = spec.simpleName + "Impl"
        val prefix        = if method.blocking || forceBlocking then "@blocking " else ""

        // `Ffi.Guard` never crosses the FFI boundary; callback params become `CFuncPtrN[...]` slots. All remaining params use the
        // standard type-mapping.
        val externParams = method.params.flatMap { p =>
            p.tpe match
                case TypeRef.GuardT        => Nil
                case TypeRef.FnPtrT(ps, r) => List(s"${safeName(p.name)}: ${cFuncPtrType(ps, r)}")
                case _                     => List(s"${safeName(p.name)}: ${externParamType(p.tpe, structsByName, implName)}")
        }

        // Append multi-value out-pointers (these go AFTER the user params: one out-pointer per trailing field).
        val extraOut = method.returnShape match
            case ReturnShape.MultiValue(sSpec) =>
                sSpec.fields.drop(1).map(f => s"${f.name}Out: Ptr[${cPrimitiveOf(f.tpe)}]")
            case _ => Nil

        // Struct return: out-pointer FIRST, matching the JVM out-ADDRESS-first convention. The binding maps to the C
        // function `void f(S* out, ...args)`: the struct out-pointer is the leading parameter, the C function fills it
        // and returns void. The extern decl param order MUST match the call arg order built in `emitBodyInner`.
        val structOut = method.returnShape match
            case ReturnShape.Struct(sSpec) =>
                List(s"out: Ptr[${structTypeRef(sSpec, structsByName, qualify = true, spec.simpleName + "Impl")}]")
            case _ => Nil

        val allParams = structOut ++ externParams ++ extraOut
        val ret = method.returnShape match
            case ReturnShape.Void                 => "Unit"
            case ReturnShape.Primitive(t)         => cPrimitiveOf(t)
            case ReturnShape.MultiValue(sSpec)    => cPrimitiveOf(sSpec.fields.head.tpe)
            case ReturnShape.Struct(_)            => "Unit"
            case ReturnShape.HandleReturn(_, _)   => "Ptr[Byte]"
            case ReturnShape.EnumReturn(_)        => "CInt"
            case ReturnShape.BorrowedString(_)    => "CString"
            case ReturnShape.BorrowedBuffer(_, _) => "Ptr[Byte]"

        s"    ${prefix}def ${method.cSymbol}(${allParams.mkString(", ")}): $ret = extern\n"
    end emitExternDecl

    // -------------------------------------------------------------------------
    // Type mapping helpers
    // -------------------------------------------------------------------------

    def primitiveTypeName(t: TypeRef): String = t match
        case TypeRef.BooleanT => "CInt"
        case TypeRef.ByteT    => "CChar"
        case TypeRef.ShortT   => "CShort"
        case TypeRef.IntT     => "CInt"
        case TypeRef.LongT    => "CLongLong"
        case TypeRef.FloatT   => "CFloat"
        case TypeRef.DoubleT  => "CDouble"
        case other            => throw new IllegalStateException(s"primitiveTypeName: unsupported primitive $other")

    /** The C-primitive Scala Native type name for a primitive TypeRef. */
    private[codegen] def cPrimitiveOf(t: TypeRef): String = primitiveTypeName(t)

    /** The Scala Native type used for an extern parameter. */
    private[codegen] def externParamType(t: TypeRef, structsByName: Map[String, StructSpec]): String =
        externParamType(t, structsByName, implName = "")

    private[codegen] def externParamType(t: TypeRef, structsByName: Map[String, StructSpec], implName: String): String = t match
        case TypeRef.UnitT      => throw new IllegalStateException("Unit is not a valid extern parameter type")
        case TypeRef.StringT    => "CString"
        case TypeRef.BufferT(_) => "Ptr[Byte]"
        case TypeRef.ArrayT(e)  => s"Ptr[${cPrimitiveOf(e)}]"
        case TypeRef.StructT(n) =>
            // Struct params are declared in the extern as POINTERS to the struct (Ptr[CStruct...])
            // to mirror the JVM emitter's ADDRESS convention. The same C `int fn(const Circle *c)`
            // signature then works on both platforms; see the body-marshalling case for the
            // pass-pointer-without-deref counterpart.
            // Packed structs use Ptr[Byte] since Scala Native 0.5 has no @packed annotation.
            val s = structsByName.getOrElse(n, throw new IllegalStateException(s"struct '$n' not found"))
            if s.packed then "Ptr[Byte]"
            else s"Ptr[${externStructTypeName(s, structsByName)}]"
        case TypeRef.UnionT(_)     => "Ptr[Byte]"
        case TypeRef.HandleT(_)    => "Ptr[Byte]"
        case TypeRef.EnumT(_)      => "CInt"
        case TypeRef.FnPtrT(ps, r) => cFuncPtrType(ps, r)
        case TypeRef.GuardT        => throw new IllegalStateException("Guard is not a C-passed parameter")
        case prim                  => cPrimitiveOf(prim)

    /** C-level `CFuncPtrN[param1, ..., paramN, ret]` type for a callback signature.
      *
      * Scala Native 0.5 defines `CFuncPtr0..CFuncPtr22` in `scala.scalanative.unsafe`; the wildcard header import brings all arities into
      * scope. Element type mapping follows the extern table: primitives → `CInt`/`CLongLong`/…, `Buffer[A]` → `Ptr[A]`, `String` →
      * `CString`, `Unit` return → `Unit`. `Array`, nested `FnPtr`, struct, and `Guard` are not permitted inside callback signatures
      * (validator rejects them upstream, per DESIGN §4.5).
      */
    private[codegen] def cFuncPtrType(params: List[TypeRef], ret: TypeRef): String =
        val arity = params.size
        if arity > 22 then
            throw new IllegalStateException(
                s"Scala Native supports only CFuncPtr0..CFuncPtr22; callback with arity $arity is not representable"
            )
        end if
        val paramPart = params.map(cFuncPtrElem).mkString(", ")
        val retPart   = cFuncPtrReturn(ret)
        if arity == 0 then s"CFuncPtr0[$retPart]"
        else s"CFuncPtr$arity[$paramPart, $retPart]"
    end cFuncPtrType

    /** Type-mapping for a callback parameter/return slot. Mirrors the extern-param mapping but collapses `Unit` to the literal `Unit` type
      * (only legal as a return position; [[cFuncPtrType]] never invokes this on a parameter position with `Unit`).
      */
    private def cFuncPtrElem(t: TypeRef): String = t match
        case TypeRef.UnitT                     => "Unit"
        case TypeRef.StringT                   => "CString"
        case TypeRef.BufferT(e)                => s"Ptr[${cPrimitiveOf(e)}]"
        case prim if TypeRef.isPrimitive(prim) => cPrimitiveOf(prim)
        case other =>
            throw new IllegalStateException(s"cFuncPtrElem: unsupported callback-slot type $other")

    /** Like [[cFuncPtrElem]] but `Unit` is permitted (return position). */
    private def cFuncPtrReturn(t: TypeRef): String = t match
        case TypeRef.UnitT => "Unit"
        case other         => cFuncPtrElem(other)

    /** Build the `CStruct{N}[...]` type for a struct value. Recursively expands nested-struct fields into their own `CStruct{N}[...]`. */
    private[codegen] def externStructTypeName(s: StructSpec): String =
        externStructTypeName(s, Map.empty)

    private[codegen] def externStructTypeName(s: StructSpec, structsByName: Map[String, StructSpec]): String =
        val n     = s.fields.size
        val parts = s.fields.map(f => externStructFieldType(f.tpe, structsByName))
        if n == 0 then "CStruct0"
        else s"CStruct$n[${parts.mkString(", ")}]"
    end externStructTypeName

    private def externStructFieldType(t: TypeRef, structsByName: Map[String, StructSpec]): String = t match
        case TypeRef.BooleanT     => "CInt"
        case TypeRef.ByteT        => "CChar"
        case TypeRef.ShortT       => "CShort"
        case TypeRef.IntT         => "CInt"
        case TypeRef.LongT        => "CLongLong"
        case TypeRef.FloatT       => "CFloat"
        case TypeRef.DoubleT      => "CDouble"
        case TypeRef.StringT      => "CString"
        case TypeRef.BufferT(_)   => "Ptr[Byte]"
        case TypeRef.HandleT(_)   => "Ptr[Byte]"
        case TypeRef.EnumT(_)     => "CInt"
        case TypeRef.FnPtrT(_, _) => "Ptr[Byte]"
        case TypeRef.StructT(n) =>
            val child = structsByName.getOrElse(
                n,
                throw new IllegalStateException(s"nested struct '$n' not found in extern type expansion")
            )
            externStructTypeName(child, structsByName)
        case TypeRef.UnionT(variants) =>
            // Union field in a struct: use the carrier type sized to max(sizeof(variants)).
            unionFieldCarrierType(variants, structsByName)
        case other => throw new IllegalStateException(s"externStructFieldType: unsupported $other")

    /** Pick a Scala Native carrier type for a union field embedded in a struct. */
    private def unionFieldCarrierType(variants: List[TypeRef], structsByName: Map[String, StructSpec]): String =
        val (maxSize, maxAlign) = unionSizeAndAlignNative(variants, structsByName)
        val carrier =
            if maxAlign >= 8 then "CLongLong"
            else if maxAlign == 4 then "CInt"
            else if maxAlign == 2 then "CShort"
            else "CChar"
        val nCarriers = (maxSize / maxAlign).max(1L)
        if nCarriers == 1 then s"CStruct1[$carrier]"
        else if nCarriers <= 22 then s"CStruct${nCarriers}[${List.fill(nCarriers.toInt)(carrier).mkString(", ")}]"
        else
            throw new IllegalStateException(
                s"Union requires $nCarriers carrier slots which exceeds Scala Native's `CStruct22` maximum."
            )
        end if
    end unionFieldCarrierType

end NativeEmitter
