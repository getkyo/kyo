package kyo.ffi.codegen.emitters

import kyo.ffi.codegen.model.*

/** Emits the Scala.js (`koffi`-backed) implementation source for a single binding trait.
  *
  * Structure mirrors [[JvmEmitter]] and [[NativeEmitter]]:
  *
  *   - An impl class delegating each method to a `facade.<scalaName>(...)` call, converting primitives, unwrapping `Buffer` to
  *     `Uint8Array`, and reading multi-value out-pointers post-call.
  *   - A companion object that calls `kyo.ffi.internal.AbiCheck.verify` and builds the `facade` by calling `KoffiFacade.load(libPath,
  *     Seq(KoffiFn(...), ...))`.
  *
  * Emission is validated against a real Node + koffi install in integration tests.
  */
object JsEmitter extends EmitterBase.Ops with PlatformTypes:

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
            """import scala.scalajs.js
              |import scala.scalajs.js.annotation.*
              |import scala.scalajs.js.typedarray.*
              |import scala.scalajs.reflect.annotation.EnableReflectiveInstantiation
              |import kyo.ffi.*
              |import kyo.ffi.internal.{AbiCheck, JsRawSegment, KoffiFacade, KoffiFn, NativeLoader, StructAbiCheck}
              |""".stripMargin
        )
    end emitHeader

    // -------------------------------------------------------------------------
    // Impl class
    // -------------------------------------------------------------------------

    private[codegen] def emitImplClass(spec: TraitSpec): String =
        val sb = new StringBuilder
        // `@EnableReflectiveInstantiation` tells the Scala.js linker to retain the nullary constructor so
        // `scala.reflect.Reflect.lookupInstantiatableClass` (used by `Ffi.load`) can instantiate it at runtime.
        sb ++= s"@EnableReflectiveInstantiation final class ${spec.simpleName}Impl extends ${spec.simpleName}:\n"
        // Import `facade` and, when the trait declares callbacks, the per-callback proto handles
        // registered in the companion. Wildcard-select works because the companion exposes these as
        // private vals, which are still visible to nested references inside the class body.
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
        val sig  = methodSignature(method, includeVarargs = true)
        val body = indentBody(emitMethodBody(method, spec))
        sig + "\n" + body + "\n"
    end emitMethod

    /** Emit the method body. Shared across callback-free, transient, and retained methods, the plain body already handles `FnPtrT` and
      * `GuardT` parameter marshalling when `method.callbackKind != None`. Variadic bindings take the dedicated [[emitVariadicMethodBody]]
      * path.
      */
    private[codegen] def emitMethodBody(method: MethodSpec, spec: TraitSpec): String =
        if method.hasVarargs then emitVariadicMethodBody(method, spec)
        else emitPlainMethodBody(method, spec)

    /** Emit the body for a variadic method. Mirrors the JVM emitter's variadic path: marshal fixed args inline, classify runtime-typed
      * varargs via [[kyo.ffi.internal.JsVariadicMarshaller]], and invoke the koffi facade with a spread call.
      *
      * The koffi function is registered with a trailing `"..."` arg marker (see [[emitKoffiFn]]), which tells koffi the function is
      * variadic and each vararg at call time must already be pinned to a koffi type via `koffi.as(...)`.
      */
    private def emitVariadicMethodBody(method: MethodSpec, spec: TraitSpec): String =
        // Validate fixed-arg shape, variadic v1 supports primitives / String / Buffer on the fixed side.
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

        method.returnShape match
            case ReturnShape.Void         => ()
            case ReturnShape.Primitive(_) => ()
            case other =>
                throw new IllegalStateException(
                    s"variadic method '${method.scalaName}' on '${spec.fqcn}' has unsupported return shape $other, " +
                        "variadic v1 supports only Unit / primitive returns"
                )
        end match

        val sb = new StringBuilder

        // Fixed-arg marshalling, same rules as the plain body (BooleanT → 0/1, Long → js.BigInt, String → direct, Buffer → Uint8Array).
        case class Marshalled(passExpr: String, setup: List[String])
        val marshalled: List[Marshalled] = method.params.map { p =>
            val name = safeName(p.name)
            p.tpe match
                case TypeRef.BooleanT =>
                    Marshalled(s"if $name then 1 else 0", Nil)
                case TypeRef.ByteT | TypeRef.ShortT | TypeRef.IntT | TypeRef.FloatT | TypeRef.DoubleT =>
                    Marshalled(name, Nil)
                case TypeRef.LongT =>
                    Marshalled(s"js.BigInt($name.toString)", Nil)
                case TypeRef.StringT =>
                    Marshalled(name, Nil)
                case TypeRef.BufferT(_) =>
                    val u = s"${name}U8"
                    Marshalled(
                        u,
                        List(
                            s"val $u = kyo.ffi.internal.FfiUnsafe.expect[JsRawSegment](" +
                                s"""$name.raw.asInstanceOf[AnyRef], classOf[JsRawSegment], "JsRawSegment on JS", "${spec.fqcn}", "${method.scalaName}").u8a"""
                        )
                    )
                case _ =>
                    throw new IllegalStateException("variadic fixed-arg unreachable")
            end match
        }

        marshalled.flatMap(_.setup).foreach { l =>
            sb ++= l
            sb ++= "\n"
        }

        // Compose fixed-arg pass list + marshalled varargs via JsVariadicMarshaller. koffi's bound `func`
        // is a JS function; apply it via `applyDynamic` so we can splat a single variable-length JS array
        // of args (Scala can't mix named fixed params and a repeated-seq splat in one call).
        sb ++= s"""val __kyoVarargs$$ = kyo.ffi.internal.JsVariadicMarshaller.marshalVarargs(args, "${spec.fqcn}", "${method.scalaName}")\n"""
        val fixedPassList = marshalled.map(m => s"${m.passExpr}.asInstanceOf[js.Any]")
        sb ++= s"val __kyoAllArgs$$ = js.Array[js.Any](${fixedPassList.mkString(", ")})\n"
        sb ++= "var __kyoVI$ = 0\n"
        sb ++= "while __kyoVI$ < __kyoVarargs$.length do\n"
        sb ++= "    val _ = __kyoAllArgs$.push(__kyoVarargs$(__kyoVI$))\n"
        sb ++= "    __kyoVI$ += 1\n"
        val callExpr = s"""facade.applyDynamic("${method.scalaName}")(__kyoAllArgs$$.toSeq*)"""

        val fqnLit    = s""""${spec.fqcn}""""
        val methodLit = s""""${method.scalaName}""""

        method.returnShape match
            case ReturnShape.Void =>
                sb ++= s"val _ = $callExpr\n"
                if method.withError then
                    sb ++= "val __errno = KoffiFacade.errno()\n"
                    sb ++= "new Ffi.WithError((), __errno)\n"
                else
                    sb ++= "()\n"
                end if
            case ReturnShape.Primitive(TypeRef.BooleanT) =>
                sb ++= s"val retVal = $callExpr.asInstanceOf[Int]\n"
                if method.withError then
                    sb ++= "val __errno = KoffiFacade.errno()\n"
                    sb ++= "new Ffi.WithError(retVal != 0, __errno)\n"
                else
                    sb ++= "retVal != 0\n"
                end if
            case ReturnShape.Primitive(TypeRef.LongT) =>
                sb ++= s"val retVal = $callExpr.asInstanceOf[js.BigInt].toString.toLong\n"
                if method.withError then
                    sb ++= "val __errno = KoffiFacade.errno()\n"
                    sb ++= "new Ffi.WithError(retVal, __errno)\n"
                else
                    sb ++= "retVal\n"
                end if
            case ReturnShape.Primitive(t) =>
                sb ++= s"val retVal = $callExpr.asInstanceOf[${jsScalaReturn(t)}]\n"
                if method.withError then
                    sb ++= "val __errno = KoffiFacade.errno()\n"
                    sb ++= s"new Ffi.WithError(retVal, __errno)\n"
                else
                    sb ++= "retVal\n"
                end if
            case _ => throw new IllegalStateException("unreachable")
        end match

        sb.toString
    end emitVariadicMethodBody

    /** Method body. Callback-free, transient, and retained callbacks all share this path; the callback-specific pieces differ per
      * [[CallbackKind]]:
      *   - **Transient**: pass the Scala function directly at the callback arg slot (koffi auto-accepts a JS function at any
      *     `koffi.pointer(proto)` position, no `register`/`unregister` needed). Scala.js lifts a Scala `FunctionN` to a `js.Function` at
      *     the call site. This avoids the koffi handle round-trip and its associated lifetime management entirely for transient calls.
      *   - **Retained**: call `KoffiFacade.register(fn, KoffiFacade.pointer(proto))` to get a C-callable handle, schedule a
      *     `KoffiFacade.unregister(handle)` cleanup on the user-supplied `Ffi.Guard` via `unsafeRetainCleanup`, and pass the handle at the
      *     callback arg slot. Close-time teardown is driven by `GuardCore.forEachRetainedCleanup` on JS.
      *
      * The callback registry manages handle lifetime on the JS side.
      */
    private def emitPlainMethodBody(method: MethodSpec, spec: TraitSpec): String =
        val structsByName = EmitterBase.structsByName(spec)

        case class Marshalled(
            paramName: String,
            passExpr: String,
            setup: List[String]
        )

        val marshalled: List[Marshalled] = method.params.flatMap { p =>
            val name = safeName(p.name)
            p.tpe match
                case TypeRef.BooleanT =>
                    List(Marshalled(p.name, s"if $name then 1 else 0", Nil))
                case TypeRef.ByteT | TypeRef.ShortT | TypeRef.IntT | TypeRef.FloatT | TypeRef.DoubleT =>
                    List(Marshalled(p.name, name, Nil))
                case TypeRef.LongT =>
                    // koffi "int64" expects a JS BigInt on the wire; Scala's Long maps to a Scala.js long runtime repr that koffi
                    // doesn't understand, so we stringify through BigInt (§DESIGN 9.3 JS Long marshalling).
                    List(Marshalled(p.name, s"js.BigInt($name.toString)", Nil))
                case TypeRef.UnitT =>
                    List(Marshalled(p.name, "()", Nil))
                case TypeRef.StringT =>
                    List(Marshalled(p.name, name, Nil))
                case TypeRef.BufferT(_) =>
                    val u = s"${name}U8"
                    // After the R2 Layout unification Buffer.Raw on JS wraps a JsRawSegment (Uint8Array + DataView).
                    // koffi accepts the Uint8Array directly, extract it from the segment. Checked unwrap
                    // so an alien `Buffer.Raw` surfaces with binding + method context.
                    List(Marshalled(
                        p.name,
                        u,
                        List(
                            s"val $u = kyo.ffi.internal.FfiUnsafe.expect[JsRawSegment](" +
                                s"""$name.raw.asInstanceOf[AnyRef], classOf[JsRawSegment], "JsRawSegment on JS", "${spec.fqcn}", "${method.scalaName}").u8a"""
                        )
                    ))
                case TypeRef.ArrayT(_) =>
                    // koffi typed-array args accept JS typed arrays directly.
                    List(Marshalled(p.name, name, Nil))
                case TypeRef.StructT(fqcn) =>
                    val sSpec = structsByName.getOrElse(
                        fqcn,
                        throw new IllegalStateException(s"struct '$fqcn' not found in trait ${spec.fqcn}")
                    )
                    val lit                   = s"${name}Lit"
                    val (extraSetup, litExpr) = structWriteWithSetup(name, sSpec, structsByName, spec.fqcn, method.scalaName)
                    val setup                 = extraSetup :+ s"val $lit = $litExpr"
                    List(Marshalled(p.name, lit, setup))
                case TypeRef.UnionT(variants) =>
                    val bufN  = s"${name}Buf"
                    val setup = emitJsUnionParamWrite(name, bufN, variants, structsByName, spec, method.scalaName)
                    List(Marshalled(p.name, bufN, setup))
                case TypeRef.HandleT(_) =>
                    // Handle[A] pointer: unwrap to get the raw koffi pointer handle on JS.
                    val raw = s"${name}Raw"
                    List(Marshalled(
                        p.name,
                        raw,
                        List(
                            s"val $raw = Ffi.Handle.unwrap($name).asInstanceOf[js.Any]"
                        )
                    ))
                case TypeRef.EnumT(_) =>
                    // Enum parameter: extract the underlying Int via `.value` and pass as int.
                    List(Marshalled(p.name, s"$name.value", Nil))
                case TypeRef.FnPtrT(cbParams, cbRet) =>
                    val protoName = callbackProtoName(method.scalaName, p.name)
                    val safeN     = s"${name}Safe"
                    // Wrap the user callback in a try/catch so exceptions do NOT cross the C boundary.
                    // On exception the wrapper reports via `FfiErrors.reportCallbackFailed` and returns a typed-zero default.
                    val safeWrapper = emitJsCallbackWrapper(safeN, name, cbParams, cbRet, spec.fqcn, method.scalaName, method.callbackKind)
                    method.callbackKind match
                        case CallbackKind.Transient =>
                            // koffi accepts a plain JS function at any arg whose declared type is `koffi.pointer(proto)`; Scala.js
                            // auto-lifts a Scala `FunctionN` to `js.Function` at the call site. No register/unregister round-trip.
                            List(Marshalled(p.name, safeN, List(safeWrapper)))
                        case CallbackKind.Retained =>
                            // Retained: obtain a C-callable handle via `KoffiFacade.register`, pin a close-time unregister on the
                            // user-supplied guard, and pass the handle at the arg slot. The guard's close() drains the cleanups via
                            // `GuardCore.forEachRetainedCleanup` → `KoffiFacade.unregister`.
                            val handleN = s"${name}H"
                            val guardParam = method.params.find(_.tpe == TypeRef.GuardT).getOrElse(
                                throw new IllegalStateException(
                                    s"Retained callback method '${method.scalaName}' is missing an Ffi.Guard parameter"
                                )
                            )
                            val setup = List(
                                safeWrapper,
                                s"""val $handleN = KoffiFacade.register($safeN, KoffiFacade.pointer($protoName))""",
                                s"${safeName(guardParam.name)}.asInstanceOf[kyo.ffi.internal.JsGuard].unsafeRetainCleanup(() => KoffiFacade.unregister($handleN))"
                            )
                            List(Marshalled(p.name, handleN, setup))
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

        // Out-param allocations for MultiValue returns.
        case class OutCell(name: String, tpe: TypeRef)
        val outCells: List[OutCell] = method.returnShape match
            case ReturnShape.MultiValue(sSpec) =>
                sSpec.fields.drop(1).map(f => OutCell(s"${f.name}Out", f.tpe))
            case _ => Nil

        // Struct-return out-cell. A `@Ffi.byValue` binding maps to the C function `void f(S* out, ...args)`: koffi
        // allocates a struct-sized buffer, passes it as the FIRST argument, the C function fills it and returns void,
        // and the body decodes the filled buffer back into the case class (the same out-pointer convention struct
        // PARAMETERS already use, and the same out-first order as the JVM and Native emitters). This avoids koffi's
        // native by-value struct return so the ABI is identical across all three backends.
        val structOutName = "__kyoStructOut$"
        val structOutCell: Option[String] = method.returnShape match
            case ReturnShape.Struct(sSpec) => Some(s"""KoffiFacade.outStruct("${sSpec.simpleName}")""")
            case _                         => None

        val sb = new StringBuilder

        marshalled.flatMap(_.setup).foreach { line =>
            sb ++= line
            sb ++= "\n"
        }

        structOutCell.foreach { alloc =>
            sb ++= s"val $structOutName = $alloc\n"
        }

        outCells.foreach { o =>
            sb ++= s"val ${o.name} = ${outAllocator(o.tpe)}\n"
        }

        // The struct out-pointer goes FIRST; multi-value out-cells append after the user args.
        val callArgs = structOutCell.map(_ => s"$structOutName.buf").toList ++
            marshalled.map(_.passExpr) ++ outCells.map(o => s"${o.name}.buf")
        val callExpr = s"facade.${method.scalaName}(${callArgs.mkString(", ")})"

        val fqnLit    = s""""${spec.fqcn}""""
        val methodLit = s""""${method.scalaName}""""

        // Marshal the koffi call result into the Scala return value, parameterized on the raw-result source expression:
        //   - On the synchronous (non-blocking) path, `rawExpr` is the sync facade call `facade.<fn>(...)`.
        //   - On the `@Ffi.blocking` path, `rawExpr` is the `raw` argument delivered to the koffi async completion
        //     callback (the call ran on a libuv worker via `KoffiFacade.callAsync`).
        // Returns `(preLines, resultExpr)`, `preLines` are the `val retVal = ...` casts / out-cell reads that must be
        // emitted before `resultExpr`, the final Scala return value (errno wrapping is added by the caller, since it
        // differs between the sync return position and the async completion callback).
        def resultMarshal(rawExpr: String): (List[String], String) =
            method.returnShape match
                case ReturnShape.Void =>
                    (List(s"val _ = $rawExpr"), "()")
                case ReturnShape.Primitive(TypeRef.BooleanT) =>
                    (List(s"val retVal = $rawExpr.asInstanceOf[Int]"), "retVal != 0")
                case ReturnShape.Primitive(TypeRef.LongT) =>
                    // koffi returns int64 as a js.BigInt; round-trip via toString to avoid precision loss.
                    (List(s"val retVal = $rawExpr.asInstanceOf[js.BigInt].toString.toLong"), "retVal")
                case ReturnShape.Primitive(t) =>
                    (List(s"val retVal = $rawExpr.asInstanceOf[${jsScalaReturn(t)}]"), "retVal")
                case ReturnShape.MultiValue(sSpec) =>
                    val head = sSpec.fields.head
                    val headCast = head.tpe match
                        case TypeRef.LongT => "asInstanceOf[js.BigInt].toString.toLong"
                        case _             => s"asInstanceOf[${jsScalaReturn(head.tpe)}]"
                    val headExpr = head.tpe match
                        case TypeRef.BooleanT => "retVal != 0"
                        case _                => "retVal"
                    val tailExprs = sSpec.fields.drop(1).zip(outCells).map { case (f, o) =>
                        f.tpe match
                            case TypeRef.BooleanT => s"${o.name}.read().asInstanceOf[Int] != 0"
                            case TypeRef.LongT    => s"${o.name}.read().asInstanceOf[js.BigInt].toString.toLong"
                            case _                => s"${o.name}.read().asInstanceOf[${jsScalaReturn(f.tpe)}]"
                    }
                    (List(s"val retVal = $rawExpr.$headCast"), s"${sSpec.fqcn}(${(headExpr :: tailExprs).mkString(", ")})")
                case ReturnShape.Struct(sSpec) =>
                    // The C function returned void/undefined after filling the struct out-buffer. Evaluate the call for
                    // its side effect, then decode the buffer into a koffi object with named fields. `read()` calls
                    // `Koffi.decode(buf, "<StructName>")`, which yields the same `selectDynamic`-keyed object that
                    // `emitStructReadExpr` reads, so the struct-decode-from-bytes path is shared with struct field reads
                    // (it is NOT koffi's native by-value object decode).
                    val structExpr = emitStructReadExpr(sSpec, "result", structsByName, spec.companion.exists(_.checkedBorrows))
                    (List(s"val _ = $rawExpr", s"val result = $structOutName.read()"), structExpr)
                case ReturnShape.HandleReturn(_, true) =>
                    // Nullable Handle return (Maybe[Handle[A]]). NULL/undefined maps to Absent, non-null to Present.
                    (
                        List(s"val retValRaw = $rawExpr"),
                        "if js.isUndefined(retValRaw) || retValRaw == null then kyo.Absent else kyo.Present(Ffi.Handle.wrap(retValRaw.asInstanceOf[AnyRef]))"
                    )
                case ReturnShape.HandleReturn(_, _) =>
                    // Non-nullable Handle return. NULL/undefined throws FfiNullPointer.
                    (
                        List(s"val retValRaw = $rawExpr"),
                        s"""if js.isUndefined(retValRaw) || retValRaw == null then throw new kyo.ffi.FfiNullPointer("${spec.fqcn}.${method.scalaName} returned null") else Ffi.Handle.wrap(retValRaw.asInstanceOf[AnyRef])"""
                    )
                case ReturnShape.EnumReturn(fqcn) =>
                    // Enum return. Convert the raw Int to the enum type via companion's fromInt.
                    (List(s"val retVal = $rawExpr.asInstanceOf[Int]"), s"$fqcn.fromInt(retVal)")
                case ReturnShape.BorrowedString(_) =>
                    // Borrowed String return.
                    (List(s"val retVal = $rawExpr.asInstanceOf[String]"), "Ffi.Borrowed.wrap(retVal)")
                case ReturnShape.BorrowedBuffer(elem, sizeParam) =>
                    // Borrowed Buffer[A] return.
                    val sp             = safeName(sizeParam)
                    val elemScala      = scalaTypeOf(elem)
                    val checkedBorrows = spec.companion.exists(_.checkedBorrows)
                    val checkedCall =
                        s"Buffer.Unsafe.wrapBorrowedChecked[$elemScala](retVal, $sp.toInt, kyo.ffi.internal.BufferFactory.currentBorrowOwner())"
                    val uncheckedCall =
                        s"Buffer.Unsafe.wrapBorrowed[$elemScala](retVal, $sp.toInt)"
                    val nonNullWrap =
                        if checkedBorrows then checkedCall
                        else
                            s"""(if (java.lang.System.getProperty("kyo.ffi.checkedBorrows") == "true") $checkedCall else $uncheckedCall)"""
                    (
                        List(
                            s"val retValRaw = $rawExpr",
                            "val __kyoBorrowedResult = Ffi.Borrowed.wrap(if js.isUndefined(retValRaw) || retValRaw == null then null else",
                            s"    val retVal = KoffiFacade.decodeBorrowedBytes(retValRaw.asInstanceOf[js.Any], $sp.toInt)",
                            s"    $nonNullWrap)"
                        ),
                        "__kyoBorrowedResult"
                    )
            end match
        end resultMarshal

        if method.blocking then
            // koffi async path: run the C call on a libuv worker via `kyo.ffi.internal.BlockingBridge.runAsync`, which
            // dispatches through `KoffiFacade.callAsync` and resolves the returned `Fiber.Unsafe` from the completion
            // callback. The `marshal` lambda supplied here decodes the raw koffi result and reads `KoffiFacade.errno()`
            // inside the callback (errno survives the async boundary, validated by spike). The marshalled args are
            // exactly the synchronous arg list (`callArgs`). The method's trailing `(using AllowUnsafe)` drives the
            // bridge, the side effect is tracked by the caller.
            val (preLines, resultExpr) = resultMarshal("raw")
            sb ++= s"""kyo.ffi.internal.BlockingBridge.runAsync(facade, "${method.scalaName}", js.Array[js.Any](${callArgs.mkString(
                    ", "
                )}), (raw: js.Any) =>\n"""
            preLines.foreach { l =>
                sb ++= "    "
                sb ++= l
                sb ++= "\n"
            }
            if method.withError then
                sb ++= "    val __errno = KoffiFacade.errno()\n"
                sb ++= s"    val __kyoResult = $resultExpr\n"
                sb ++= "    new Ffi.WithError(__kyoResult, __errno)\n"
            else
                sb ++= s"    $resultExpr\n"
            end if
            sb ++= ")"
            sb.toString
        else
            // Synchronous (non-blocking) path: call koffi directly and marshal the return inline.
            val (preLines, resultExpr) = resultMarshal(callExpr)
            preLines.foreach { l =>
                sb ++= l
                sb ++= "\n"
            }
            if method.withError then
                sb ++= "val __errno = KoffiFacade.errno()\n"
                sb ++= s"val __kyoResult = $resultExpr\n"
                sb ++= "new Ffi.WithError(__kyoResult, __errno)\n"
            else
                sb ++= resultExpr + "\n"
            end if
            sb.toString
        end if
    end emitPlainMethodBody

    /** Build a `js.Dynamic.literal(...)` expression for one struct. Recurses into nested struct fields, producing nested literals.
      *
      * [[bindingFqn]] and [[methodName]] thread the FFI call site through to the `FfiUnsafe.expect` unwrap at Buffer-typed struct fields so
      * alien `Buffer.Raw` carriers surface with context.
      */
    /** Build a `js.Dynamic.literal(...)` expression for writing a case-class into a koffi struct.
      *
      * Returns `(setupLines, literalExpr)`, `setupLines` must be emitted BEFORE the literal to handle callback registration.
      */
    private def structWriteWithSetup(
        structVar: String,
        sSpec: StructSpec,
        structsByName: Map[String, StructSpec],
        bindingFqn: String,
        methodName: String
    ): (List[String], String) =
        val setupBuf = List.newBuilder[String]
        val fields = fieldsToWrite(sSpec).map { f =>
            val (fSetup, fExpr) = structFieldJsExprWithSetup(structVar, f, structsByName, bindingFqn, methodName)
            setupBuf ++= fSetup
            s""""${f.name}" -> $fExpr"""
        }
        (setupBuf.result(), s"js.Dynamic.literal(${fields.mkString(", ")})")
    end structWriteWithSetup

    private def structWriteLiteral(
        structVar: String,
        sSpec: StructSpec,
        structsByName: Map[String, StructSpec],
        bindingFqn: String,
        methodName: String
    ): String =
        val (_, lit) = structWriteWithSetup(structVar, sSpec, structsByName, bindingFqn, methodName)
        lit
    end structWriteLiteral

    /** Returns `(setupLines, fieldExpression)`, setupLines are emitted before the struct literal for callback registration. */
    private def structFieldJsExprWithSetup(
        structVar: String,
        f: StructField,
        structsByName: Map[String, StructSpec],
        bindingFqn: String,
        methodName: String
    ): (List[String], String) =
        val acc = s"$structVar.${safeName(f.name)}"
        f.tpe match
            case TypeRef.FnPtrT(cbParams, cbRet) =>
                // Function pointer struct field on JS: koffi cannot pass a JS function to a void*
                // struct field.  We must register the callback with koffi to get a C-callable handle.
                // Proto names must be globally unique in koffi, so we use a counter suffix.
                val handleVar  = s"${structVar}_${f.name}_cbH"
                val protoVar   = s"${structVar}_${f.name}_proto"
                val jsFnVar    = s"${structVar}_${f.name}_jsFn"
                val counterVar = s"${structVar}_${f.name}_cnt"
                // Use an empty TraitSpec for koffiProtoType since callback params are primitives. Proto positions keep
                // Boolean as "bool" (the Scala wrapper exchanges a genuine Boolean), unlike func-ABI positions.
                val emptySpec  = TraitSpec("", "", "", "", Nil, Nil, None)
                val paramTypes = cbParams.map(t => s""""${koffiProtoType(t, emptySpec)}"""").mkString(", ")
                val retType = cbRet match
                    case TypeRef.UnitT => "void"
                    case other         => koffiProtoType(other, emptySpec)
                // Use a unique proto name per call to avoid koffi "Duplicate type name" errors.
                val arity = cbParams.size
                // Convert Scala function to js.Function via explicit js.Any.fromFunctionN.
                val jsFnExpr        = s"js.Any.fromFunction$arity($acc)"
                val protoNamePrefix = s"${structVar}_${f.name}"
                val protoExpr       = s"""KoffiFacade.proto("${protoNamePrefix}_" + $counterVar, "$retType", Seq[String]($paramTypes))"""
                val setup = List(
                    s"val $counterVar = kyo.ffi.internal.KoffiFacade.nextProtoId()",
                    s"val $jsFnVar: js.Function = $jsFnExpr",
                    s"val $protoVar = $protoExpr",
                    s"val $handleVar = KoffiFacade.register($jsFnVar, KoffiFacade.pointer($protoVar))"
                )
                (setup, s"$handleVar.asInstanceOf[js.Any]")
            case TypeRef.StructT(n) =>
                val child                  = structsByName.getOrElse(n, throw new IllegalStateException(s"nested struct '$n' not found"))
                val (childSetup, childLit) = structWriteWithSetup(acc, child, structsByName, bindingFqn, methodName)
                (childSetup, s"$childLit.asInstanceOf[js.Any]")
            case TypeRef.UnionT(variants) =>
                // Union struct field on JS: koffi represents a union-typed field as an object with exactly the active
                // variant set ({ vK: value }), keyed by the positional member names the companion registered via
                // KoffiFacade.union. Emit a runtime type match producing that object for the active variant.
                val objVar = s"${structVar}_${f.name}_uobj"
                val setup  = emitJsUnionFieldObject(objVar, acc, variants, structsByName, bindingFqn, methodName)
                (setup, s"$objVar.asInstanceOf[js.Any]")
            case _ =>
                (Nil, structFieldJsExpr(structVar, f, structsByName, bindingFqn, methodName))
        end match
    end structFieldJsExprWithSetup

    /** Build a koffi union value object for a union struct field: a runtime type match on `valExpr` producing
      * `js.Dynamic.literal("vK" -> <marshalled value>)` for the active variant, where `vK` is the positional member
      * name registered by [[emitUnionRegistrations]]. koffi requires exactly the active variant set on a union value.
      * Returns the setup lines (the `val objVar = ... match { ... }` assignment).
      */
    private def emitJsUnionFieldObject(
        objVar: String,
        valExpr: String,
        variants: List[TypeRef],
        structsByName: Map[String, StructSpec],
        bindingFqn: String,
        methodName: String
    ): List[String] =
        val buf = List.newBuilder[String]
        buf += s"val $objVar: js.Dynamic = $valExpr match {"
        variants.zipWithIndex.foreach { case (v, i) =>
            val m = s"v$i"
            v match
                case TypeRef.BooleanT =>
                    buf += s"""  case __v: java.lang.Boolean => js.Dynamic.literal("$m" -> (if __v.booleanValue then 1 else 0).asInstanceOf[js.Any])"""
                case TypeRef.ByteT =>
                    buf += s"""  case __v: java.lang.Byte => js.Dynamic.literal("$m" -> __v.byteValue.asInstanceOf[js.Any])"""
                case TypeRef.ShortT =>
                    buf += s"""  case __v: java.lang.Short => js.Dynamic.literal("$m" -> __v.shortValue.asInstanceOf[js.Any])"""
                case TypeRef.IntT =>
                    buf += s"""  case __v: java.lang.Integer => js.Dynamic.literal("$m" -> __v.intValue.asInstanceOf[js.Any])"""
                case TypeRef.LongT =>
                    buf += s"""  case __v: java.lang.Long => js.Dynamic.literal("$m" -> js.BigInt(__v.longValue.toString).asInstanceOf[js.Any])"""
                case TypeRef.FloatT =>
                    buf += s"""  case __v: java.lang.Float => js.Dynamic.literal("$m" -> __v.floatValue.asInstanceOf[js.Any])"""
                case TypeRef.DoubleT =>
                    buf += s"""  case __v: java.lang.Double => js.Dynamic.literal("$m" -> __v.doubleValue.asInstanceOf[js.Any])"""
                case TypeRef.StructT(fqcn) =>
                    val sSpec = structsByName.getOrElse(
                        fqcn,
                        throw new IllegalStateException(s"union variant struct '$fqcn' not found")
                    )
                    val (structSetup, structLit) = structWriteWithSetup("__v", sSpec, structsByName, bindingFqn, methodName)
                    if structSetup.isEmpty then
                        buf += s"""  case __v: $fqcn => js.Dynamic.literal("$m" -> $structLit.asInstanceOf[js.Any])"""
                    else
                        buf += s"  case __v: $fqcn => {"
                        structSetup.foreach(l => buf += s"    $l")
                        buf += s"""    js.Dynamic.literal("$m" -> $structLit.asInstanceOf[js.Any])"""
                        buf += "  }"
                    end if
                case other =>
                    throw new IllegalStateException(s"union variant type $other is not supported (only primitives and structs)")
            end match
        }
        buf += s"""  case __other => throw new kyo.ffi.FfiLoadError.Unsupported(s"Union variant not supported: $${__other.getClass}")"""
        buf += "}"
        buf.result()
    end emitJsUnionFieldObject

    private def structFieldJsExpr(
        structVar: String,
        f: StructField,
        structsByName: Map[String, StructSpec],
        bindingFqn: String,
        methodName: String
    ): String =
        val acc = s"$structVar.${safeName(f.name)}"
        f.tpe match
            case TypeRef.BooleanT   => s"(if $acc then 1 else 0).asInstanceOf[js.Any]"
            case TypeRef.LongT      => s"js.BigInt($acc.toString).asInstanceOf[js.Any]"
            case TypeRef.BufferT(_) =>
                // Checked unwrap.
                s"kyo.ffi.internal.FfiUnsafe.expect[JsRawSegment]($acc.raw.asInstanceOf[AnyRef], classOf[JsRawSegment], " +
                    s""""JsRawSegment on JS", "$bindingFqn", "$methodName").u8a.asInstanceOf[js.Any]"""
            case TypeRef.HandleT(_) =>
                // Handle field: unwrap via Ffi.Handle.unwrap to get the raw koffi handle.
                s"Ffi.Handle.unwrap($acc).asInstanceOf[js.Any]"
            case TypeRef.EnumT(_) =>
                // Enum field: extract the Int value.
                s"$acc.value.asInstanceOf[js.Any]"
            case TypeRef.FnPtrT(_, _) =>
                // Handled by structFieldJsExprWithSetup, should not be reached here.
                throw new IllegalStateException("FnPtrT struct field should be handled by structFieldJsExprWithSetup")
            case TypeRef.StructT(n) =>
                val child = structsByName.getOrElse(n, throw new IllegalStateException(s"nested struct '$n' not found"))
                s"${structWriteLiteral(acc, child, structsByName, bindingFqn, methodName)}.asInstanceOf[js.Any]"
            case _ =>
                s"$acc.asInstanceOf[js.Any]"
        end match
    end structFieldJsExpr

    private def emitStructReadExpr(sSpec: StructSpec, resultVar: String): String =
        emitStructReadExpr(sSpec, resultVar, Map.empty, checkedBorrows = false)

    private def emitStructReadExpr(
        sSpec: StructSpec,
        resultVar: String,
        structsByName: Map[String, StructSpec],
        checkedBorrows: Boolean
    ): String =
        // Infer buffer size from the sole Int/Long sibling in the same struct. TypeValidator rejects zero/multiple cases,
        // so a BufferT field reaching this emitter MUST have a resolvable sibling; otherwise this is an internal error.
        def bufferSizeExpr(bufferFieldName: String): String =
            sSpec.inferredBufferSizeField match
                case Some(sizeField) =>
                    val sib = s"""$resultVar.selectDynamic("${sizeField.name}")"""
                    sizeField.tpe match
                        case TypeRef.IntT  => s"$sib.asInstanceOf[Int]"
                        case TypeRef.LongT => s"$sib.asInstanceOf[js.BigInt].toString.toLong.toInt"
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

        val parts = sSpec.fields.map { f =>
            val sel = s"""$resultVar.selectDynamic("${f.name}")"""
            f.tpe match
                case TypeRef.BooleanT      => s"$sel.asInstanceOf[Int] != 0"
                case TypeRef.ByteT         => s"$sel.asInstanceOf[Byte]"
                case TypeRef.ShortT        => s"$sel.asInstanceOf[Short]"
                case TypeRef.IntT          => s"$sel.asInstanceOf[Int]"
                case TypeRef.LongT         => s"$sel.asInstanceOf[js.BigInt].toString.toLong"
                case TypeRef.FloatT        => s"$sel.asInstanceOf[Float]"
                case TypeRef.DoubleT       => s"$sel.asInstanceOf[Double]"
                case TypeRef.StringT       => s"$sel.asInstanceOf[String]"
                case TypeRef.BufferT(elem) =>
                    // Borrowed pointer field, koffi surfaces `pointer`-typed fields as Uint8Array-like objects. We wrap it as a
                    // borrowed Buffer whose close() is a no-op. The buffer extent is inferred from the unique Int/Long sibling
                    // field in this struct. TypeValidator rejects structs with zero or multiple candidate siblings.
                    //
                    // Checked-borrow opt-in via `Ffi.Config.checkedBorrows` or the process-wide
                    // sys-prop `-Dkyo.ffi.checkedBorrows=true`. On JS the checked path additionally detects detached
                    // ArrayBuffers via `u8a.buffer.byteLength == 0`.
                    val sizeExpr = bufferSizeExpr(f.name)
                    val checkedCall =
                        s"Buffer.Unsafe.wrapBorrowedChecked[${scalaTypeOf(elem)}]($sel.asInstanceOf[Uint8Array], $sizeExpr, kyo.ffi.internal.BufferFactory.currentBorrowOwner())"
                    val uncheckedCall =
                        s"Buffer.Unsafe.wrapBorrowed[${scalaTypeOf(elem)}]($sel.asInstanceOf[Uint8Array], $sizeExpr)"
                    if checkedBorrows then checkedCall
                    else
                        s"""(if (java.lang.System.getProperty("kyo.ffi.checkedBorrows") == "true") $checkedCall else $uncheckedCall)"""
                    end if
                case TypeRef.HandleT(_) =>
                    // Handle field: wrap the koffi pointer handle via Ffi.Handle.wrap.
                    s"Ffi.Handle.wrap($sel.asInstanceOf[AnyRef])"
                case TypeRef.EnumT(enumFqcn) =>
                    // Enum field: read the Int and convert via companion's fromInt.
                    s"$enumFqcn.fromInt($sel.asInstanceOf[Int])"
                case TypeRef.FnPtrT(_, _) =>
                    // Reading a C function pointer back into a Scala function is not meaningful.
                    s"""throw new UnsupportedOperationException("Cannot read function pointer field '${f.name}' from C struct '${sSpec.simpleName}'")"""
                case TypeRef.StructT(n) =>
                    val child = structsByName.getOrElse(n, throw new IllegalStateException(s"nested struct '$n' not found"))
                    emitStructReadExpr(child, s"$sel.asInstanceOf[js.Dynamic]", structsByName, checkedBorrows)
                case TypeRef.UnionT(variants) =>
                    // Union field read: return the first variant. On JS the union bytes come as a Uint8Array;
                    // interpret via DataView for the first variant type.
                    emitJsUnionFieldRead(variants.head, sel, structsByName, checkedBorrows)
                case other =>
                    throw new IllegalStateException(s"unsupported struct-read field type: $other")
            end match
        }
        s"${sSpec.fqcn}(${parts.mkString(", ")})"
    end emitStructReadExpr

    /** Choose the right `kyo.ffi.internal.KoffiFacade` out-cell allocator for a multi-value out-param type. */
    private def outAllocator(t: TypeRef): String = t match
        case TypeRef.BooleanT | TypeRef.ByteT | TypeRef.ShortT | TypeRef.IntT | TypeRef.FloatT | TypeRef.DoubleT =>
            // int-family and float-family out params go through the generic int cell, koffi decodes by the declared
            // koffi type (see `koffiType` used in the KoffiFn arg suffix).
            "KoffiFacade.outInt()"
        case TypeRef.LongT =>
            "KoffiFacade.outLong()"
        case TypeRef.StringT | TypeRef.BufferT(_) | TypeRef.ArrayT(_) =>
            "KoffiFacade.outPointer()"
        case other =>
            throw new IllegalStateException(s"outAllocator: unsupported type $other")

    // -------------------------------------------------------------------------
    // Impl companion
    // -------------------------------------------------------------------------

    private[codegen] def emitImplCompanion(spec: TraitSpec): String =
        val sb            = new StringBuilder
        val structsByName = EmitterBase.structsByName(spec)
        sb ++= s"object ${spec.simpleName}Impl:\n"
        sb ++= abiCheckLine(spec.fqcn)
        sb ++= "\n"
        // Register all struct and union types with koffi up-front so the per-function arg/result strings (and struct
        // field types) can reference them by name. koffi resolves type-name references eagerly at registration, so the
        // emission is dependency-ordered: a union's struct member variants and a struct's struct/union field types are
        // registered before the type that references them. Packed structs go through `koffi.pack`, others `koffi.struct`.
        sb ++= emitTypeRegistrations(spec)
        // Struct ABI self-check. Compare the code-generator's expected byte size against koffi's runtime `sizeof` for each
        // registered struct. Runs after every struct has been registered above so nested struct references resolve. Mismatch throws
        // `FfiLoadError.AbiMismatch` before `Ffi.load[T]` returns.
        val checked = layoutRequiredStructs(spec, structsByName)
        spec.structs.foreach { s =>
            if checked.contains(s.fqcn) then
                val expected = structByteSize(s, structsByName)
                sb ++= s"""    StructAbiCheck.verifyByteSize("${spec.fqcn}", "${s.simpleName}", ${expected}L, KoffiFacade.sizeof(${koffiStructHandleName(
                        s
                    )}.asInstanceOf[js.Any]).toLong)\n"""
        }
        // Register callback prototypes so the per-function arg strings can reference them by name. Each callback param produces exactly
        // one proto declaration, shared between transient and retained shapes. We bind the return
        // value (the koffi-issued proto handle) to a `<protoName>` companion-private val so that the
        // method body can pass it directly to `KoffiFacade.register`, koffi 2.x rejects bare string
        // names in `register` (`TypeError: Unknown or invalid type name`).
        spec.methods.foreach { m =>
            emitCallbackProtos(m, spec).foreach { case (pname, expr) =>
                // `KoffiFacade.proto` returns `js.Any`, the opaque koffi Type handle, and we store
                // it under a companion-private val for reuse inside each method body.
                sb ++= s"    private val $pname: scala.scalajs.js.Any = $expr\n"
            }
        }
        sb ++= s"""    private val facade = KoffiFacade.load(\n"""
        sb ++= s"""        NativeLoader.jsResolve("${spec.library}"),\n"""
        sb ++= "        Seq(\n"
        val fnLines = spec.methods.zipWithIndex.map { case (m, i) =>
            val last = i == spec.methods.size - 1
            emitKoffiFn(m, spec) + (if last then "" else ",")
        }
        fnLines.foreach { l =>
            sb ++= s"            $l\n"
        }
        sb ++= "        )\n"
        sb ++= "    )\n"
        sb ++= s"end ${spec.simpleName}Impl\n"
        sb.toString
    end emitImplCompanion

    /** Emit one `KoffiFacade.proto(...)` call per callback parameter of `method`. The proto name is [[callbackProtoName]]; the argument
      * koffi types are derived from the callback's parameter types via [[koffiType]]; the return koffi type via [[jsCallbackReturnType]].
      * Non-callback methods return [[Nil]].
      */
    /** @return
      *   list of `(companionValName, rhsExpression)`, one entry per callback parameter of `method`. The caller emits
      *   `private val <valName> = <rhs>` so each proto is registered exactly once and the returned handle is available to body emission via
      *   the same `<valName>`.
      */
    private def emitCallbackProtos(method: MethodSpec, spec: TraitSpec): List[(String, String)] =
        method.callbackKind match
            case CallbackKind.None => Nil
            case _ =>
                method.params.collect { case ParamSpec(pname, TypeRef.FnPtrT(params, ret)) =>
                    val protoName = callbackProtoName(method.scalaName, pname)
                    val argStrs   = params.map(t => s""""${koffiProtoType(t, spec)}"""").mkString(", ")
                    // `KoffiFacade.proto` expects `Seq[String]`, see counterpart in the body emitter
                    // above for the fix rationale.
                    val rhs =
                        s"""KoffiFacade.proto("$protoName", "${jsCallbackReturnType(ret, spec)}", Seq[String]($argStrs))"""
                    (protoName, rhs)
                }
    end emitCallbackProtos

    /** Proto name used to identify one callback parameter inside koffi's name-keyed registry. Namespaced by method + parameter so two
      * callback parameters with the same shape on different methods do not collide.
      */
    private def callbackProtoName(methodName: String, paramName: String): String =
        s"${methodName}_${paramName}_proto"

    /** koffi return-type string for a callback's C-level return. `Unit` → `"void"`. Otherwise the same mapping as method returns (delegates
      * to [[koffiType]]).
      */
    private def jsCallbackReturnType(t: TypeRef, spec: TraitSpec): String = t match
        case TypeRef.UnitT => "void"
        case other         => koffiProtoType(other, spec)
    end jsCallbackReturnType

    /** Emit a `KoffiFacade.struct(...)` or `KoffiFacade.pack(...)` line registering the struct type with koffi. The registered name is the
      * [[StructSpec.simpleName]], the same string used by [[koffiType]] elsewhere. Nested-struct fields reference their own registered
      * name; user-facing struct types MUST all appear in `spec.structs` so the registration order does not matter for name resolution at
      * `func(...)` time.
      *
      * The koffi-issued handle is retained under a `<structSimpleName>_koffiType` companion val so the struct-ABI self-check can pass it to
      * [[KoffiFacade.sizeof]] without a second registration.
      */
    private def emitStructRegistration(s: StructSpec, spec: TraitSpec): String =
        val fnName =
            if s.packed then "pack"
            else "struct"
        val fields = s.fields.map(f => s""""${f.name}" -> "${koffiType(f.tpe, spec)}".asInstanceOf[js.Any]""").mkString(", ")
        s"""    private val ${koffiStructHandleName(
                s
            )}: js.Dynamic = KoffiFacade.$fnName("${s.simpleName}", js.Dynamic.literal($fields))\n"""
    end emitStructRegistration

    private def koffiStructHandleName(s: StructSpec): String =
        val n = s.simpleName
        if n.isEmpty then "structKoffiType"
        else n.head.toLower.toString + n.tail + "KoffiType"
    end koffiStructHandleName

    private[codegen] def emitKoffiFn(method: MethodSpec, spec: TraitSpec): String =
        // Callback methods: skip GuardT (it never crosses the boundary) and at FnPtrT positions emit a `KoffiFacade.pointer(<protoVal>)`
        // Scala expression, the opaque koffi Type handle for the registered proto. koffi 2.x rejects bare proto-name strings at the arg-type
        // slot, so we thread the handle through verbatim. String-typed entries are upcast to `js.Any` so `KoffiFn.args: Seq[js.Any]` accepts
        // them. Struct parameters are marshalled as POINTERS ("StructName*") to match the JVM emitter's ADDRESS convention, the same C
        // signature `int fn(const S *s)` works across JVM / Native / JS.
        val argParts = method.params.flatMap { p =>
            p.tpe match
                case TypeRef.GuardT =>
                    Nil
                case TypeRef.HandleT(_) =>
                    List(s""""void*".asInstanceOf[js.Any]""")
                case TypeRef.FnPtrT(_, _) =>
                    List(s"KoffiFacade.pointer(${callbackProtoName(method.scalaName, p.name)})")
                case TypeRef.StructT(n) =>
                    val name = spec.structs.find(_.fqcn == n).map(_.simpleName).getOrElse(n)
                    List(s""""$name*".asInstanceOf[js.Any]""")
                case TypeRef.UnionT(_) =>
                    // Union params are passed as pointers (void*), same as struct params.
                    List(s""""void*".asInstanceOf[js.Any]""")
                case other =>
                    List(s""""${koffiType(other, spec)}".asInstanceOf[js.Any]""")
            end match
        }
        val extraOut = method.returnShape match
            case ReturnShape.MultiValue(sSpec) =>
                sSpec.fields.drop(1).map(f => s""""${koffiType(f.tpe, spec)}*".asInstanceOf[js.Any]""")
            case _ => Nil
        // Struct return: a leading struct out-pointer (`"StructName*"`) before the user args, matching the JVM and
        // Native emitters' out-first convention. koffi fills the pointee and the C function returns void.
        val structOutArg = method.returnShape match
            case ReturnShape.Struct(sSpec) => List(s""""${sSpec.simpleName}*".asInstanceOf[js.Any]""")
            case _                         => Nil
        // Variadic marker. koffi accepts `"..."` at the args array's tail to signal a variadic function; each vararg
        // at call time must already be pinned to its koffi type via `koffi.as(...)`.
        val variadicMarker =
            if method.hasVarargs then List(""""...".asInstanceOf[js.Any]""") else Nil
        val allArgs = (structOutArg ++ argParts ++ extraOut ++ variadicMarker).mkString(", ")
        val result = method.returnShape match
            case ReturnShape.Void                 => "void"
            case ReturnShape.Primitive(t)         => koffiType(t, spec)
            case ReturnShape.MultiValue(sSpec)    => koffiType(sSpec.fields.head.tpe, spec)
            case ReturnShape.Struct(_)            => "void"   // out-pointer-first ABI: C fills `S* out` and returns void
            case ReturnShape.HandleReturn(_, _)   => "void*"  // koffi opaque pointer return
            case ReturnShape.EnumReturn(_)        => "int"    // C enum is int
            case ReturnShape.BorrowedString(_)    => "string" // koffi auto-decodes char* → JS string (F8a)
            case ReturnShape.BorrowedBuffer(_, _) => "void*"  // koffi returns a Uint8Array view of the pointee (F8a)
        s"""KoffiFn("${method.scalaName}", "${method.cSymbol}", "$result", Seq[js.Any]($allArgs))"""
    end emitKoffiFn

    // -------------------------------------------------------------------------
    // Type mapping helpers
    // -------------------------------------------------------------------------

    /** Emit a one-line `val <safeN> = <wrapper>` that takes the user's callback [[userFnVal]] and returns a Scala function of the same
      * shape that wraps the user's `apply(...)` in try/catch. On exception the wrapper reports via
      * `kyo.ffi.internal.FfiErrors.reportCallbackFailed` and returns a zero default, see README "Callback exception handling".
      *
      * Scala.js auto-lifts a Scala `FunctionN` to a `js.Function` at the koffi call site (or in `KoffiFacade.register`), so a plain Scala
      * wrapper is sufficient, no explicit `js.Function` annotation is needed.
      */
    private def emitJsCallbackWrapper(
        safeN: String,
        userFnVal: String,
        cbParams: List[TypeRef],
        cbRet: TypeRef,
        bindingFqn: String,
        methodName: String,
        kind: CallbackKind
    ): String =
        val kindName = kind match
            case CallbackKind.Transient => "transient"
            case CallbackKind.Retained  => "retained"
            case CallbackKind.None      => "none"
        val fnType  = scalaTypeOf(TypeRef.FnPtrT(cbParams, cbRet))
        val params  = cbParams.zipWithIndex.map { case (t, i) => s"a$i: ${scalaTypeOf(t)}" }.mkString(", ")
        val argList = cbParams.indices.map(i => s"a$i").mkString(", ")
        val zero    = jsZeroExpr(cbRet)
        s"""val $safeN: $fnType = ($params) =>
           |    try $userFnVal($argList)
           |    catch case t: Throwable =>
           |        kyo.ffi.internal.FfiGenErrors.reportCallbackFailed("$bindingFqn", "$methodName", "$kindName", t)
           |        $zero""".stripMargin
    end emitJsCallbackWrapper

    /** Zero-expression for a callback return type at the JS-side Scala shim. Mirrors the typed-zero defaults of the JVM `UpcallBridge` and
      * Native `CallbackRegistry` trampolines.
      */
    private def jsZeroExpr(t: TypeRef): String = t match
        case TypeRef.UnitT      => "()"
        case TypeRef.BooleanT   => "false"
        case TypeRef.ByteT      => "0.toByte"
        case TypeRef.ShortT     => "0.toShort"
        case TypeRef.IntT       => "0"
        case TypeRef.LongT      => "0L"
        case TypeRef.FloatT     => "0.0f"
        case TypeRef.DoubleT    => "0.0"
        case TypeRef.StringT    => "\"\""
        case TypeRef.BufferT(_) => "null"
        case other              => throw new IllegalStateException(s"jsZeroExpr: unsupported callback return $other")

    /** koffi type-name for a [[TypeRef]]. For struct types, the [[StructSpec.simpleName]] is used, matching the name used when the struct
      * was registered via `koffi.struct` / `koffi.pack` in the impl companion's static block.
      */
    def primitiveTypeName(t: TypeRef): String = t match
        case TypeRef.BooleanT => "bool"
        case TypeRef.ByteT    => "int8"
        case TypeRef.ShortT   => "int16"
        case TypeRef.IntT     => "int"
        case TypeRef.LongT    => "int64"
        case TypeRef.FloatT   => "float"
        case TypeRef.DoubleT  => "double"
        case other            => throw new IllegalStateException(s"primitiveTypeName: unsupported primitive $other")

    private[codegen] def koffiType(t: TypeRef, spec: TraitSpec): String = t match
        case TypeRef.UnitT => "void"
        // Func-ABI / struct-field / out-param Booleans register as "int" (4-byte C int), not koffi's 1-byte "bool":
        // the C ABI passes/returns `bool` in an int-width slot and the Scala side casts the raw value via
        // `asInstanceOf[Int]` and compares `!= 0`. Callback PROTO positions keep "bool" (see `koffiProtoType`), mirroring
        // JvmEmitter's callbackValueLayout (JAVA_BOOLEAN) vs paramLayout (JAVA_INT) split.
        case TypeRef.BooleanT         => "int"
        case TypeRef.StringT          => "string"
        case TypeRef.BufferT(_)       => "void*"
        case TypeRef.HandleT(_)       => "void*"
        case TypeRef.EnumT(_)         => "int"
        case TypeRef.ArrayT(e)        => s"${koffiType(e, spec)}*"
        case TypeRef.StructT(n)       => spec.structs.find(_.fqcn == n).map(_.simpleName).getOrElse(n)
        case TypeRef.UnionT(variants) =>
            // A union struct field references a koffi union type by name. The companion registers it via
            // KoffiFacade.union before the enclosing struct, so koffi resolves it here and lays the field out with
            // the union's size and alignment (max over variants), matching the C ABI. The value passed for the field
            // is the raw union bytes built by structFieldJsExprWithSetup.
            unionTypeName(variants, spec)
        case TypeRef.FnPtrT(_, _) =>
            // In struct field context, function pointers are stored as void* (pointer-sized).
            "void*"
        case TypeRef.GuardT => throw new IllegalStateException("Guard is not a C-passed parameter")
        case prim           => primitiveTypeName(prim)

    /** Stable koffi type name for a union used as a struct field, derived from the variants' koffi type names. The
      * companion registers one `KoffiFacade.union` per distinct union type under this name (see [[emitTypeRegistrations]]).
      */
    private def unionTypeName(variants: List[TypeRef], spec: TraitSpec): String =
        "__kyoUnion_" + variants.map(v => koffiType(v, spec).replaceAll("[^A-Za-z0-9]", "_")).mkString("_")

    /** Emit the koffi struct and union type registrations for `spec`, in dependency order. koffi resolves type-name
      * references eagerly, so a union's struct member variants and a struct's struct/union field types must be
      * registered before the referencing type. Each struct and each distinct union type is emitted exactly once, after
      * its dependencies. Struct registrations go through [[emitStructRegistration]] (koffi.struct / koffi.pack); union
      * registrations are `KoffiFacade.union(name, { v0 -> type0, ... })` with positional `vK` member names matching the
      * write side ([[emitJsUnionFieldObject]]) and read side ([[emitJsUnionFieldRead]]).
      */
    private def emitTypeRegistrations(spec: TraitSpec): String =
        val structsByName = EmitterBase.structsByName(spec)
        val emitted       = scala.collection.mutable.Set.empty[String]
        val sb            = new StringBuilder
        def emitUnionReg(variants: List[TypeRef]): Unit =
            val name = unionTypeName(variants, spec)
            if !emitted(name) then
                variants.foreach {
                    case TypeRef.StructT(n) => structsByName.get(n).foreach(emitStructReg)
                    case _                  => ()
                }
                emitted += name
                val members = variants.zipWithIndex
                    .map { case (v, i) => s""""v$i" -> "${koffiType(v, spec)}".asInstanceOf[js.Any]""" }
                    .mkString(", ")
                sb ++= s"""    private val ${name}Handle: js.Dynamic = KoffiFacade.union("$name", js.Dynamic.literal($members))\n"""
            end if
        end emitUnionReg
        def emitStructReg(s: StructSpec): Unit =
            if !emitted(s.fqcn) then
                s.fields.foreach { f =>
                    f.tpe match
                        case TypeRef.StructT(n)       => structsByName.get(n).foreach(emitStructReg)
                        case TypeRef.UnionT(variants) => emitUnionReg(variants)
                        case _                        => ()
                }
                emitted += s.fqcn
                sb ++= emitStructRegistration(s, spec)
            end if
        end emitStructReg
        spec.structs.foreach(emitStructReg)
        sb.toString
    end emitTypeRegistrations

    /** koffi type-name for a CALLBACK PROTO position (param or return of a `KoffiFacade.proto`). Unlike [[koffiType]], Boolean stays "bool":
      * the Scala callback wrapper exchanges a genuine Boolean with koffi at the proto boundary, mirroring JvmEmitter's `callbackValueLayout`
      * (JAVA_BOOLEAN) split from `paramLayout` (JAVA_INT). Every other shape matches [[koffiType]].
      */
    private def koffiProtoType(t: TypeRef, spec: TraitSpec): String = t match
        case TypeRef.BooleanT => "bool"
        case other            => koffiType(other, spec)

    /** The Scala-side return type used when casting the raw koffi return (as `js.Any`). */
    private def jsScalaReturn(t: TypeRef): String = t match
        case TypeRef.BooleanT => "Int"
        case TypeRef.ByteT    => "Byte"
        case TypeRef.ShortT   => "Short"
        case TypeRef.IntT     => "Int"
        case TypeRef.LongT    => "Long"
        case TypeRef.FloatT   => "Float"
        case TypeRef.DoubleT  => "Double"
        case TypeRef.StringT  => "String"
        case other            => throw new IllegalStateException(s"jsScalaReturn: unsupported $other")

    // -------------------------------------------------------------------------
    // Union helpers
    // -------------------------------------------------------------------------

    /** Emit setup lines for marshalling a union parameter on JS. */
    private def emitJsUnionParamWrite(
        paramName: String,
        bufName: String,
        variants: List[TypeRef],
        structsByName: Map[String, StructSpec],
        spec: TraitSpec,
        methodName: String
    ): List[String] =
        val (unionSize, _) = unionSizeAndAlignJs(variants, structsByName)
        val viewName       = s"${bufName}View"
        val buf            = List.newBuilder[String]
        buf += s"val $bufName = new scalajs.js.typedarray.Uint8Array($unionSize)"
        buf += s"val $viewName = new scalajs.js.typedarray.DataView($bufName.buffer)"
        buf ++= emitJsUnionVariantMatch(paramName, viewName, bufName, variants, structsByName, spec.fqcn, methodName)
        buf.result()
    end emitJsUnionParamWrite

    /** Emit the runtime type match that writes the correct union variant into the union's buffer. Primitive variants
      * are written via the `DataView` (`viewExpr`); a struct variant is serialized into the underlying `Uint8Array`
      * (`bufExpr`) via koffi's encoder, so it marshals every field type (including pointers) exactly as a struct
      * parameter does, matching JVM and Native.
      */
    private def emitJsUnionVariantMatch(
        valExpr: String,
        viewExpr: String,
        bufExpr: String,
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
                    buf += s"    case __v: java.lang.Boolean => $viewExpr.setInt32(0, if __v.booleanValue then 1 else 0, true)"
                case TypeRef.ByteT =>
                    buf += s"    case __v: java.lang.Byte => $viewExpr.setInt8(0, __v.byteValue)"
                case TypeRef.ShortT =>
                    buf += s"    case __v: java.lang.Short => $viewExpr.setInt16(0, __v.shortValue, true)"
                case TypeRef.IntT =>
                    buf += s"    case __v: java.lang.Integer => $viewExpr.setInt32(0, __v.intValue, true)"
                case TypeRef.LongT =>
                    // Write the 64-bit value as two little-endian 32-bit halves (low word first). DataView.setBigInt64
                    // is not in the scala.js typedarray facade, and setFloat64 would write the double bit-pattern (so C
                    // reading the union as int64 gets garbage, and values above 2^53 also lose precision). JVM/Native
                    // write the raw 8 long bytes, so JS must too.
                    buf += s"    case __v: java.lang.Long =>"
                    buf += s"        $viewExpr.setInt32(0, (__v.longValue & 0xffffffffL).toInt, true)"
                    buf += s"        $viewExpr.setInt32(4, (__v.longValue >>> 32).toInt, true)"
                case TypeRef.FloatT =>
                    buf += s"    case __v: java.lang.Float => $viewExpr.setFloat32(0, __v.floatValue, true)"
                case TypeRef.DoubleT =>
                    buf += s"    case __v: java.lang.Double => $viewExpr.setFloat64(0, __v.doubleValue, true)"
                case TypeRef.StructT(fqcn) =>
                    // Struct variant: serialize the case class into the union buffer via koffi's encoder, the same
                    // path a struct parameter uses. koffi writes every field at the C-ABI offsets, including pointer
                    // fields (String, Buffer[A], Handle[A], function pointers), so a struct variant marshals
                    // identically on JVM, Native, and JS.
                    val sSpec = structsByName.getOrElse(
                        fqcn,
                        throw new IllegalStateException(s"union variant struct '$fqcn' not found")
                    )
                    val (structSetup, structLit) = structWriteWithSetup("__v", sSpec, structsByName, bindingFqn, methodName)
                    buf += s"    case __v: $fqcn =>"
                    structSetup.foreach(l => buf += s"        $l")
                    buf += s"        KoffiFacade.encode($bufExpr, 0, ${koffiStructHandleName(sSpec)}, $structLit)"
                case other =>
                    throw new IllegalStateException(s"union variant type $other is not supported (only primitives and structs)")
            end match
        }
        buf += s"""    case __other => throw new kyo.ffi.FfiLoadError.Unsupported(s"Union variant not supported: $${__other.getClass}")"""
        buf.result()
    end emitJsUnionVariantMatch

    /** Read a union field's first variant from the koffi-decoded union value. koffi returns a union struct field as an
      * object with each registered member decoded; the active variant of a C union is not tracked, so reading the first
      * member (`v0`) is the documented best-effort. Mirrors the write representation in [[emitJsUnionFieldObject]].
      *
      * Reached only when a struct that contains a union field is read back, i.e. via a by-value struct return
      * (`ReturnShape.Struct`); the inspector does not currently produce that shape, so this path is not yet reachable
      * through a binding, but it is kept correct and consistent with the write side.
      */
    private def emitJsUnionFieldRead(
        firstVariant: TypeRef,
        sel: String,
        structsByName: Map[String, StructSpec],
        checkedBorrows: Boolean
    ): String =
        val v0 = s"""$sel.selectDynamic("v0")"""
        firstVariant match
            case TypeRef.BooleanT => s"$v0.asInstanceOf[Int] != 0"
            case TypeRef.ByteT    => s"$v0.asInstanceOf[Byte]"
            case TypeRef.ShortT   => s"$v0.asInstanceOf[Short]"
            case TypeRef.IntT     => s"$v0.asInstanceOf[Int]"
            case TypeRef.LongT    => s"$v0.asInstanceOf[js.BigInt].toString.toLong"
            case TypeRef.FloatT   => s"$v0.asInstanceOf[Float]"
            case TypeRef.DoubleT  => s"$v0.asInstanceOf[Double]"
            case TypeRef.StructT(n) =>
                val child = structsByName.getOrElse(n, throw new IllegalStateException(s"nested struct '$n' not found"))
                emitStructReadExpr(child, s"$v0.asInstanceOf[js.Dynamic]", structsByName, checkedBorrows)
            case other =>
                throw new IllegalStateException(s"unsupported union variant read type: $other")
        end match
    end emitJsUnionFieldRead

    /** Compute size and alignment for a union type on JS. */
    private def unionSizeAndAlignJs(variants: List[TypeRef], structsByName: Map[String, StructSpec]): (Long, Long) =
        val (maxSize, maxAlign) = variants.foldLeft((0L, 1L)) { case ((mxS, mxA), v) =>
            val (vs, va) = sizeAndAlign(v, structsByName, packed = false)
            (math.max(mxS, vs), math.max(mxA, va))
        }
        (align(maxSize, maxAlign), maxAlign)
    end unionSizeAndAlignJs

end JsEmitter
