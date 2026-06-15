package kyo.ffi.codegen.emitters

import kyo.ffi.codegen.model.*

private[emitters] object EmitterBase:

    def scalaTypeOf(t: TypeRef): String = t match
        case TypeRef.BooleanT   => "Boolean"
        case TypeRef.ByteT      => "Byte"
        case TypeRef.ShortT     => "Short"
        case TypeRef.IntT       => "Int"
        case TypeRef.LongT      => "Long"
        case TypeRef.FloatT     => "Float"
        case TypeRef.DoubleT    => "Double"
        case TypeRef.UnitT      => "Unit"
        case TypeRef.StringT    => "String"
        case TypeRef.ArrayT(e)  => s"Array[${scalaTypeOf(e)}]"
        case TypeRef.BufferT(e) => s"Buffer[${scalaTypeOf(e)}]"
        case TypeRef.StructT(n) => n
        case TypeRef.HandleT(n) => s"Ffi.Handle[$n]"
        case TypeRef.EnumT(n)   => n
        case TypeRef.FnPtrT(ps, r) =>
            if ps.isEmpty then s"() => ${scalaTypeOf(r)}"
            else if ps.size == 1 then s"${scalaTypeOf(ps.head)} => ${scalaTypeOf(r)}"
            else s"(${ps.map(scalaTypeOf).mkString(", ")}) => ${scalaTypeOf(r)}"
        case TypeRef.UnionT(variants) => variants.map(scalaTypeOf).mkString(" | ")
        case TypeRef.GuardT           => "Ffi.Guard"

    def returnType(r: ReturnShape): String = r match
        case ReturnShape.Void                     => "Unit"
        case ReturnShape.Primitive(t)             => scalaTypeOf(t)
        case ReturnShape.Struct(s)                => s.fqcn
        case ReturnShape.MultiValue(s)            => s.fqcn
        case ReturnShape.BorrowedString(_)        => s"Ffi.Borrowed[String]"
        case ReturnShape.BorrowedBuffer(elem, _)  => s"Ffi.Borrowed[Buffer[${scalaTypeOf(elem)}]]"
        case ReturnShape.HandleReturn(fqcn, true) => s"kyo.Maybe[Ffi.Handle[$fqcn]]"
        case ReturnShape.HandleReturn(fqcn, _)    => s"Ffi.Handle[$fqcn]"
        case ReturnShape.EnumReturn(fqcn)         => fqcn

    def safeName(n: String): String =
        if ReservedNames.contains(n) then s"`$n`" else n

    val ReservedNames: Set[String] = Set(
        "type",
        "val",
        "var",
        "def",
        "class",
        "object",
        "trait",
        "enum",
        "given",
        "using",
        "if",
        "then",
        "else",
        "match",
        "case",
        "new",
        "this",
        "super",
        "return",
        "throw",
        "throws",
        "try",
        "catch",
        "finally",
        "while",
        "do",
        "for",
        "yield",
        "with",
        "extends",
        "abstract",
        "final",
        "sealed",
        "private",
        "protected",
        "override",
        "implicit",
        "lazy",
        "package",
        "import",
        "export",
        "extension",
        "inline",
        "opaque",
        "transparent",
        "derives",
        "end",
        // Scala 2 / cross-compat keywords (soft Scala 3 reserved)
        "forSome",
        "macro"
    )

    def sizeAndAlign(t: TypeRef, structsByName: Map[String, StructSpec], packed: Boolean): (Long, Long) =
        def collapse(natural: Long): Long = if packed then 1L else natural
        t match
            case TypeRef.BooleanT     => (4L, collapse(4L))
            case TypeRef.ByteT        => (1L, collapse(1L))
            case TypeRef.ShortT       => (2L, collapse(2L))
            case TypeRef.IntT         => (4L, collapse(4L))
            case TypeRef.LongT        => (8L, collapse(8L))
            case TypeRef.FloatT       => (4L, collapse(4L))
            case TypeRef.DoubleT      => (8L, collapse(8L))
            case TypeRef.StringT      => (8L, collapse(8L))
            case TypeRef.BufferT(_)   => (8L, collapse(8L))
            case TypeRef.HandleT(_)   => (8L, collapse(8L))
            case TypeRef.EnumT(_)     => (4L, collapse(4L))
            case TypeRef.FnPtrT(_, _) => (8L, collapse(8L))
            case TypeRef.StructT(n) =>
                val child = structsByName.getOrElse(
                    n,
                    throw new IllegalStateException(s"struct '$n' not found")
                )
                val (size, maxAlign) = child.fields.foldLeft((0L, 1L)) { case ((off, maxA), f) =>
                    val (fs, fa) = sizeAndAlign(f.tpe, structsByName, child.packed)
                    val aligned  = align(off, fa)
                    (aligned + fs, math.max(maxA, fa))
                }
                // Round size up to max align.
                val totalSize = align(size, maxAlign)
                (totalSize, collapse(maxAlign))
            case TypeRef.UnionT(variants) =>
                val (maxSize, maxAlign) = variants.foldLeft((0L, 1L)) { case ((mxS, mxA), v) =>
                    val (vs, va) = sizeAndAlign(v, structsByName, packed)
                    (math.max(mxS, vs), math.max(mxA, va))
                }
                val totalSize = align(maxSize, maxAlign)
                (totalSize, collapse(maxAlign))
            case other =>
                throw new IllegalStateException(s"sizeAndAlign: unsupported $other")
        end match
    end sizeAndAlign

    def align(offset: Long, a: Long): Long = (offset + a - 1L) & -a

    def structByteSize(s: StructSpec, structsByName: Map[String, StructSpec]): Long =
        sizeAndAlign(TypeRef.StructT(s.fqcn), structsByName, packed = false)._1

    /** Collect struct FQCNs referenced by union variant types. */
    private def collectUnionStructs(variants: List[TypeRef], roots: scala.collection.mutable.Builder[String, List[String]]): Unit =
        variants.foreach {
            case TypeRef.StructT(fqcn) => roots += fqcn
            case _                     => ()
        }

    def layoutRequiredStructs(spec: TraitSpec, structsByName: Map[String, StructSpec]): Set[String] =
        val roots = List.newBuilder[String]
        spec.methods.foreach { m =>
            m.params.foreach { p =>
                p.tpe match
                    case TypeRef.StructT(fqcn)    => roots += fqcn
                    case TypeRef.UnionT(variants) => collectUnionStructs(variants, roots)
                    case _                        => ()
            }
            m.returnShape match
                case ReturnShape.Struct(s) => roots += s.fqcn
                case _                     => ()
        }
        val out     = scala.collection.mutable.Set.empty[String]
        val pending = scala.collection.mutable.Queue.from(roots.result())
        while pending.nonEmpty do
            val fqcn = pending.dequeue()
            if !out.contains(fqcn) then
                out += fqcn
                structsByName.get(fqcn).foreach { s =>
                    s.fields.foreach { f =>
                        f.tpe match
                            case TypeRef.StructT(child) if !out.contains(child) => pending.enqueue(child)
                            case TypeRef.UnionT(variants) =>
                                variants.foreach {
                                    case TypeRef.StructT(child) if !out.contains(child) => pending.enqueue(child)
                                    case _                                              => ()
                                }
                            case _ => ()
                    }
                }
            end if
        end while
        out.toSet
    end layoutRequiredStructs

    /** Pre-computed layout for a single struct field: descriptor, byte offset, and positional index. */
    case class FieldLayout(field: StructField, offset: Long, index: Int)

    /** Compute laid-out fields for a struct. */
    def structFieldLayouts(
        sSpec: StructSpec,
        structsByName: Map[String, StructSpec],
        forWrite: Boolean
    ): List[FieldLayout] =
        val fields = sSpec.fields
        var offset = 0L
        fields.zipWithIndex.map { case (f, i) =>
            val (size, fAlign) = sizeAndAlign(f.tpe, structsByName, sSpec.packed)
            val fieldOffset    = align(offset, fAlign)
            offset = fieldOffset + size
            FieldLayout(f, fieldOffset, i)
        }
    end structFieldLayouts

    /** The fields that should be written for a struct. */
    def fieldsToWrite(sSpec: StructSpec): List[StructField] =
        sSpec.fields

    /** Build the `structsByName` lookup map from a [[TraitSpec]]'s struct list. */
    def structsByName(spec: TraitSpec): Map[String, StructSpec] =
        spec.structs.map(s => s.fqcn -> s).toMap

    /** Current ABI version baked into every emitted impl; must track `kyo.ffi.internal.AbiCheck.runtimeAbi`. */
    val AbiVersion: Int = 1

    /** Emit the auto-generated header: comment banner, package declaration, and platform-specific imports.
      *
      * @param imports
      *   the platform-specific import block (e.g. Panama imports for JVM, `scala.scalanative.unsafe.*` for Native)
      */
    def emitHeader(spec: TraitSpec, imports: String): String =
        val pkg =
            if spec.packageName.isEmpty then ""
            else s"package ${spec.packageName}\n\n"
        s"""// AUTO-GENERATED by kyo-ffi-codegen v$AbiVersion. DO NOT EDIT.
           |// Regenerate via `sbt compile` after modifying the binding trait.
           |
           |$pkg""".stripMargin + imports
    end emitHeader

    /** Build a method signature line: `    def name(params): ReturnType =`
      *
      * @param includeVarargs
      *   when `true` and the method is variadic, appends `args: Any*` to the parameter list
      */
    def methodSignature(method: MethodSpec, includeVarargs: Boolean = false): String =
        val fixedPs  = method.params.map(p => s"${safeName(p.name)}: ${scalaTypeOf(p.tpe)}")
        val allPs    = if includeVarargs && method.hasVarargs then fixedPs :+ "args: Any*" else fixedPs
        val innerRet = returnType(method.returnShape)
        // For an Outcome return the inner shape is the C return width (Int / Long); render it as the phantom type
        // argument `Ffi.Outcome[<width>]` so the generated signature matches the binding trait and the descriptor reads
        // the C value at that width.
        val errRet = if method.withError then s"Ffi.Outcome[$innerRet]" else innerRet
        // The FFI binding layer is the unsafe tier: every binding method takes a trailing `(using AllowUnsafe)`
        // clause (every native call is a side effect tracked by the caller). `@Ffi.blocking` methods additionally
        // return `kyo.Fiber.Unsafe[<ret>, Any]` rather than a bare value: the blocking downcall is surfaced as an
        // already-completed (JVM/Native) or callback-resolved (JS) fiber. `kyo.Fiber` / `kyo.AllowUnsafe` are literal
        // strings here (kyo-ffi-codegen cannot import them), but the generated `Impl` is compiled in the consumer
        // module where they resolve.
        val ret = if method.blocking then s"kyo.Fiber.Unsafe[$errRet, Any]" else errRet
        s"    def ${method.scalaName}(${allPs.mkString(", ")})(using kyo.AllowUnsafe): $ret ="
    end methodSignature

    /** Wrap a synchronous JVM/Native method body in a `kyo.ffi.internal.BlockingBridge.run { ... }` block, lifting the
      * already-computed result into a `kyo.Fiber.Unsafe`.
      *
      * The synchronous blocking downcall runs on the carrier thread inside the thunk; `BlockingBridge.run` completes a
      * `Promise.Unsafe` with the result and returns it as the `Fiber.Unsafe[<ret>, Any]` the `@Ffi.blocking` signature
      * declares (see [[methodSignature]]). The body is re-indented one level (4 spaces) inside the braces.
      *
      * `BlockingBridge.run` needs the caller's `AllowUnsafe`, which is the binding method's trailing `(using
      * AllowUnsafe)` clause: the side effect is tracked by the caller, so the body uses that evidence directly rather
      * than establishing it internally.
      */
    def wrapBlockingRun(body: String): String =
        val sb = new StringBuilder
        sb ++= "kyo.ffi.internal.BlockingBridge.run {\n"
        body.linesIterator.foreach { l =>
            if l.isEmpty then sb ++= "\n"
            else
                sb ++= "    "
                sb ++= l
                sb ++= "\n"
        }
        sb ++= "}"
        sb.toString
    end wrapBlockingRun

    /** Emit the `AbiCheck.verify(...)` companion preamble line (with 4-space indent). */
    def abiCheckLine(fqcn: String): String =
        s"""    AbiCheck.verify($AbiVersion, "$fqcn")"""

    /** Indent a method body block by the given number of spaces, preserving blank lines as-is. */
    def indentBody(body: String, spaces: Int = 8): String =
        val prefix = " " * spaces
        body.linesIterator.map(l => if l.isEmpty then l else prefix + l).mkString("\n")

    /** Mix-in that exposes [[EmitterBase]] helpers as direct methods, eliminating delegation wrappers in each emitter. */
    trait Ops:
        protected def scalaTypeOf(t: TypeRef): String                         = EmitterBase.scalaTypeOf(t)
        protected def returnType(r: ReturnShape): String                      = EmitterBase.returnType(r)
        protected def safeName(n: String): String                             = EmitterBase.safeName(n)
        protected def structsByName(spec: TraitSpec): Map[String, StructSpec] = EmitterBase.structsByName(spec)

        protected def sizeAndAlign(t: TypeRef, structsByName: Map[String, StructSpec], packed: Boolean): (Long, Long) =
            EmitterBase.sizeAndAlign(t, structsByName, packed)

        protected def align(offset: Long, a: Long): Long = EmitterBase.align(offset, a)

        protected def structByteSize(s: StructSpec, structsByName: Map[String, StructSpec]): Long =
            EmitterBase.structByteSize(s, structsByName)

        protected def layoutRequiredStructs(spec: TraitSpec, structsByName: Map[String, StructSpec]): Set[String] =
            EmitterBase.layoutRequiredStructs(spec, structsByName)

        protected type FieldLayout = EmitterBase.FieldLayout
        protected val FieldLayout: EmitterBase.FieldLayout.type = EmitterBase.FieldLayout

        protected def structFieldLayouts(
            sSpec: StructSpec,
            structsByName: Map[String, StructSpec],
            forWrite: Boolean
        ): List[EmitterBase.FieldLayout] =
            EmitterBase.structFieldLayouts(sSpec, structsByName, forWrite)

        protected def fieldsToWrite(sSpec: StructSpec): List[StructField] =
            EmitterBase.fieldsToWrite(sSpec)

        protected val AbiVersion: Int = EmitterBase.AbiVersion

        protected def emitHeader(spec: TraitSpec, imports: String): String =
            EmitterBase.emitHeader(spec, imports)

        protected def methodSignature(method: MethodSpec, includeVarargs: Boolean = false): String =
            EmitterBase.methodSignature(method, includeVarargs)

        protected def wrapBlockingRun(body: String): String =
            EmitterBase.wrapBlockingRun(body)

        protected def abiCheckLine(fqcn: String): String =
            EmitterBase.abiCheckLine(fqcn)

        protected def indentBody(body: String, spaces: Int = 8): String =
            EmitterBase.indentBody(body, spaces)
    end Ops

end EmitterBase

/** Mix-in for emitter objects providing platform-specific type name mapping. */
private[emitters] trait PlatformTypes:

    /** Platform-specific primitive type name for numeric/boolean TypeRefs (BooleanT, ByteT, ShortT, IntT, LongT, FloatT, DoubleT). */
    def primitiveTypeName(t: TypeRef): String

    /** Boolean marshalling expression: Scala Boolean → platform int. */
    def booleanMarshal(expr: String): String = s"if $expr then 1 else 0"

    /** Boolean unmarshalling expression: platform int → Scala Boolean. */
    def booleanUnmarshal(expr: String): String = s"$expr != 0"

end PlatformTypes
