package kyo.internal.tasty.classfile

import kyo.*
import kyo.internal.tasty.binary.ByteView
import kyo.internal.tasty.scala2.Scala2PickleReader
import kyo.internal.tasty.symbol.Flags as FlagsHelper
import kyo.internal.tasty.symbol.FullNameCanonicalizer
import kyo.internal.tasty.symbol.LoadingSymbol
import kyo.internal.tasty.symbol.Symbol as SymbolFactory
import kyo.internal.tasty.symbol.SymbolKind
import kyo.internal.tasty.type_.TypeArena
import scala.collection.mutable

/** Reads a JVM .class file from raw bytes and produces a ClassfileResult.
  *
  * Handles: magic, version, constant pool, access flags, this/super, interfaces, fields, methods, and class-level attributes (Signature,
  * InnerClasses, EnclosingMethod, Record, RuntimeVisibleAnnotations, RuntimeInvisibleAnnotations, Exceptions).
  *
  * Cross-platform: no JVM I/O. All state is in Array[Byte] + ByteView.
  */
object ClassfileUnpickler:

    import ConstantPool.readU1
    import ConstantPool.readU2
    import ConstantPool.readU4

    /** Per-decode id counter for LoadingSymbol.Materialising instances.
      *
      * Each ClassfileUnpickler decode session creates one counter. Ids are assigned sequentially from 0. Counter is NOT shared across
      * concurrent decodes (each callsite in ClasspathOrchestrator creates a fresh ClassfileUnpickler decode, so the counter is single-use).
      */
    private class IdCounter:
        private var _next: Int = 0
        def nextId(): Int =
            val id = _next
            _next += 1
            id
        end nextId
    end IdCounter

    def read(
        bytes: Array[Byte],
        arena: TypeArena,
        nextGlobalId: Maybe[() => Int] = Maybe.Absent
    )(using Frame, AllowUnsafe): Result[TastyError, ClassfileResult] =
        val view = ByteView(bytes)
        val path = "<classfile>"
        readFrom(view, arena, path, nextGlobalId)
    end read

    def readFrom(
        view: ByteView,
        arena: TypeArena,
        path: String,
        nextGlobalId: Maybe[() => Int] = Maybe.Absent
    )(using Frame, AllowUnsafe): Result[TastyError, ClassfileResult] =
        // ClasspathOrchestrator Pass C constructs fully-populated Symbols from ClassfileResult data via materializeSymbols.
        readFromRaw(view, arena, path, nextGlobalId)

    private def readFromRaw(
        view: ByteView,
        arena: TypeArena,
        path: String,
        nextGlobalId: Maybe[() => Int] = Maybe.Absent
    )(using Frame, AllowUnsafe): Result[TastyError, ClassfileResult] =
        // Use the external global counter when provided (for ClasspathOrchestrator multi-file decodes).
        // Fall back to a local per-file counter otherwise (unit tests, standalone decodes).
        val idCounter: IdCounter =
            nextGlobalId match
                case Maybe.Present(f) =>
                    new IdCounter:
                        override def nextId(): Int = f()
                case Maybe.Absent => new IdCounter()
        val magic = readU4(view)
        if magic != ClassfileFormat.Magic then
            Result.Failure(TastyError.ClassfileFormatError(
                path,
                s"Invalid magic: 0x${magic.toHexString} (expected 0xcafebabe)",
                view.position
            ))
        else
            val minor = readU2(view)
            val major = readU2(view)
            if major < ClassfileFormat.MinMajorVersion ||
                (major == ClassfileFormat.MinMajorVersion && minor < ClassfileFormat.MinMinorVersion)
            then
                Result.Failure(TastyError.ClassfileFormatError(
                    path,
                    s"Unsupported classfile version $major.$minor (minimum ${ClassfileFormat.MinMajorVersion}.${ClassfileFormat.MinMinorVersion})",
                    view.position
                ))
            else
                ConstantPool.read(view, path).flatMap { pool =>
                    readBody(view, pool, arena, path, idCounter)
                }
            end if
        end if
    end readFromRaw

    private def readBody(
        view: ByteView,
        pool: ConstantPool,
        arena: TypeArena,
        path: String,
        idCounter: IdCounter
    )(using Frame, AllowUnsafe): Result[TastyError, ClassfileResult] =
        val accessFlags = readU2(view)
        val thisIdx     = readU2(view)
        val superIdx    = readU2(view)
        pool.classRefUnsafe(thisIdx).flatMap { thisBinaryName =>
            // module-info.class: skip entirely
            if thisBinaryName.endsWith("module-info") then
                Result.Success(ClassfileResult(
                    makeUnresolvedLoading(thisBinaryName, accessFlags, idCounter),
                    Chunk.empty,
                    Map.empty,
                    Chunk.empty,
                    Chunk.empty,
                    arena,
                    mutable.LongMap.empty[Tasty.Type]
                ))
            else
                readClassBody(view, pool, arena, path, accessFlags, thisBinaryName, superIdx, idCounter)
        }
    end readBody

    /** Create a LoadingSymbol.Materialising placeholder for unknown/module-info class symbols. */
    private def makeUnresolvedLoading(binaryName: String, accessFlags: Int, idCounter: IdCounter)(using
        AllowUnsafe
    ): LoadingSymbol.Materialising =
        val simpleName = binaryName.split("[./]").last
        SymbolFactory.makeSymbol(
            id = idCounter.nextId(),
            kind = SymbolKind.Class,
            flags = Tasty.Flags(Tasty.Flag.JavaDefined),
            name = Tasty.Name(simpleName)
        )
    end makeUnresolvedLoading

    private def unresolvedType(binaryName: String)(using AllowUnsafe): Tasty.Type =
        // Type.Named(SymbolId(-1)): sentinel for unresolved type references from classfiles.
        // The symbol is not added to allSyms; the id -1 is the canonical unresolved sentinel.
        Tasty.Type.Named(kyo.Tasty.SymbolId(-1))
    end unresolvedType

    private def readClassBody(
        view: ByteView,
        pool: ConstantPool,
        arena: TypeArena,
        path: String,
        accessFlags: Int,
        thisBinaryName: String,
        superIdx: Int,
        idCounter: IdCounter
    )(using Frame, AllowUnsafe): Result[TastyError, ClassfileResult] =
        // Read parent types: super class + interfaces
        resolveOptionalSuperTypePair(pool, superIdx).flatMap { superPairOpt =>
            val ifCount = readU2(view)
            val ifIdxs  = new Array[Int](ifCount)
            var i       = 0
            while i < ifCount do
                ifIdxs(i) = readU2(view)
                i += 1
            resolveInterfaceTypePairs(pool, path, ifIdxs, 0, Chunk.empty).flatMap { ifPairs =>
                val (superTypes, superNames) = superPairOpt match
                    case Present((st, bn)) =>
                        (ifPairs.map(_._1).prepended(st), ifPairs.map(_._2).prepended(bn))
                    case Absent =>
                        (ifPairs.map(_._1), ifPairs.map(_._2))
                val parents           = superTypes
                val parentBinaryNames = superNames
                // Fields
                readMemberInfos(view, pool, path, isMethods = false).flatMap { fieldInfos =>
                    // Methods
                    readMemberInfos(view, pool, path, isMethods = true).flatMap { methodInfos =>
                        // Class-level attributes
                        readClassAttributes(view, pool, path).flatMap { classAttrs =>
                            buildResult(
                                pool,
                                arena,
                                path,
                                accessFlags,
                                thisBinaryName,
                                parents,
                                parentBinaryNames,
                                fieldInfos,
                                methodInfos,
                                classAttrs,
                                idCounter
                            )
                        }
                    }
                }
            }
        }

    /** Returns (Type, binaryName) pair so callers can recover the binary fully-qualified name for parent resolution. */
    private def resolveOptionalSuperTypePair(
        pool: ConstantPool,
        superIdx: Int
    )(using Frame, AllowUnsafe): Result[TastyError, Maybe[(Tasty.Type, String)]] =
        if superIdx == 0 then Result.Success(Absent)
        else pool.classRefUnsafe(superIdx).map(bn => Present((unresolvedType(bn), bn)))

    /** Returns (Type, binaryName) pairs so callers can recover binary fully-qualified names for parent resolution. */
    private def resolveInterfaceTypePairs(
        pool: ConstantPool,
        path: String,
        idxs: Array[Int],
        i: Int,
        acc: Chunk[(Tasty.Type, String)]
    )(using Frame, AllowUnsafe): Result[TastyError, Chunk[(Tasty.Type, String)]] =
        if i >= idxs.length then Result.Success(acc)
        else
            pool.classRefUnsafe(idxs(i)).flatMap { bn =>
                resolveInterfaceTypePairs(pool, path, idxs, i + 1, acc.appended((unresolvedType(bn), bn)))
            }

    // -------------------------------------------------------------------------
    // Member info reading
    // -------------------------------------------------------------------------

    /** Raw parsed data from a field_info or method_info entry.
      *
      * annotationBytes stores the raw bytes of RuntimeVisibleAnnotations and RuntimeInvisibleAnnotations attributes for deferred decoding.
      */
    final private case class MemberInfo(
        accessFlags: Int,
        nameIdx: Int,
        descriptorIdx: Int,
        signatureIdx: Maybe[Int],
        exceptionIdxs: Chunk[Int],
        visibleAnnotationBytes: Maybe[Array[Byte]],
        invisibleAnnotationBytes: Maybe[Array[Byte]],
        // MethodParameters attribute (method-level only; empty for fields)
        paramNames: Chunk[String]
    )

    private def readMemberInfos(
        view: ByteView,
        pool: ConstantPool,
        path: String,
        isMethods: Boolean
    )(using Frame, AllowUnsafe): Result[TastyError, Chunk[MemberInfo]] =
        val count = readU2(view)
        readMemberList(view, pool, path, count, Chunk.empty, isMethods)
    end readMemberInfos

    private def readMemberList(
        view: ByteView,
        pool: ConstantPool,
        path: String,
        remaining: Int,
        acc: Chunk[MemberInfo],
        isMethods: Boolean
    )(using Frame, AllowUnsafe): Result[TastyError, Chunk[MemberInfo]] =
        if remaining == 0 then Result.Success(acc)
        else
            readOneMemberInfo(view, pool, path, isMethods).flatMap { info =>
                readMemberList(view, pool, path, remaining - 1, acc.appended(info), isMethods)
            }

    private def readOneMemberInfo(
        view: ByteView,
        pool: ConstantPool,
        path: String,
        isMethods: Boolean
    )(using Frame, AllowUnsafe): Result[TastyError, MemberInfo] =
        val accessFlags = readU2(view)
        val nameIdx     = readU2(view)
        val descIdx     = readU2(view)
        val attrCount   = readU2(view)
        readMemberAttributes(
            view,
            pool,
            path,
            attrCount,
            Maybe.empty,
            Chunk.empty,
            Maybe.empty,
            Maybe.empty,
            Chunk.empty
        ).map { (sigIdx, exceptionIdxs, visAnn, invisAnn, params) =>
            MemberInfo(accessFlags, nameIdx, descIdx, sigIdx, exceptionIdxs, visAnn, invisAnn, params)
        }
    end readOneMemberInfo

    private def readMemberAttributes(
        view: ByteView,
        pool: ConstantPool,
        path: String,
        remaining: Int,
        sigIdx: Maybe[Int],
        exceptionIdxs: Chunk[Int],
        visibleAnnBytes: Maybe[Array[Byte]],
        invisibleAnnBytes: Maybe[Array[Byte]],
        paramNames: Chunk[String]
    )(using
        Frame,
        AllowUnsafe
    ): Result[TastyError, (Maybe[Int], Chunk[Int], Maybe[Array[Byte]], Maybe[Array[Byte]], Chunk[String])] =
        if remaining == 0 then Result.Success((sigIdx, exceptionIdxs, visibleAnnBytes, invisibleAnnBytes, paramNames))
        else
            val nameIdx = readU2(view)
            val attrLen = readU4(view)
            pool.utf8Unsafe(nameIdx).flatMap { attrName =>
                attrName match
                    case ClassfileFormat.AttrSignature =>
                        val i = readU2(view)
                        readMemberAttributes(
                            view,
                            pool,
                            path,
                            remaining - 1,
                            Present(i),
                            exceptionIdxs,
                            visibleAnnBytes,
                            invisibleAnnBytes,
                            paramNames
                        )

                    case ClassfileFormat.AttrExceptions =>
                        val count = readU2(view)
                        var idxs  = Chunk.empty[Int]
                        var k     = 0
                        while k < count do
                            idxs = idxs.appended(readU2(view))
                            k += 1
                        readMemberAttributes(
                            view,
                            pool,
                            path,
                            remaining - 1,
                            sigIdx,
                            idxs,
                            visibleAnnBytes,
                            invisibleAnnBytes,
                            paramNames
                        )

                    case ClassfileFormat.AttrRuntimeVisibleAnnotations =>
                        // Capture raw bytes for deferred annotation decoding
                        val bytes = captureBytes(view, attrLen)
                        readMemberAttributes(
                            view,
                            pool,
                            path,
                            remaining - 1,
                            sigIdx,
                            exceptionIdxs,
                            Present(bytes),
                            invisibleAnnBytes,
                            paramNames
                        )

                    case ClassfileFormat.AttrRuntimeInvisibleAnnotations =>
                        val bytes = captureBytes(view, attrLen)
                        readMemberAttributes(
                            view,
                            pool,
                            path,
                            remaining - 1,
                            sigIdx,
                            exceptionIdxs,
                            visibleAnnBytes,
                            Present(bytes),
                            paramNames
                        )

                    case ClassfileFormat.AttrMethodParameters =>
                        // JVMS §4.7.24: u1 parameters_count, then for each: u2 name_index, u2 access_flags
                        readMethodParameterNames(view, pool).flatMap { names =>
                            readMemberAttributes(
                                view,
                                pool,
                                path,
                                remaining - 1,
                                sigIdx,
                                exceptionIdxs,
                                visibleAnnBytes,
                                invisibleAnnBytes,
                                names
                            )
                        }

                    // Carve-out: classfile attribute-name dispatch on String; unknown attributes are skipped per JVMS
                    case _ =>
                        skipBytes(view, attrLen)
                        readMemberAttributes(
                            view,
                            pool,
                            path,
                            remaining - 1,
                            sigIdx,
                            exceptionIdxs,
                            visibleAnnBytes,
                            invisibleAnnBytes,
                            paramNames
                        )
            }

    // -------------------------------------------------------------------------
    // Class-level attribute reading
    // -------------------------------------------------------------------------

    final private case class ClassAttributes(
        signatureIdx: Maybe[Int],
        innerClasses: Chunk[(Int, Int, Int, Int)],
        enclosingClassIdx: Maybe[Int],
        enclosingMethodIdx: Maybe[Int],
        hasRecord: Boolean,
        recordComponents: Chunk[(Int, Int, Maybe[Int])],
        visibleAnnotationBytes: Maybe[Array[Byte]],
        invisibleAnnotationBytes: Maybe[Array[Byte]],
        // Scala 2 pickle attribute bytes: ScalaSig (compact-encoded) or Scala (ZLIB-compressed)
        scalaSigBytes: Maybe[Array[Byte]],
        scalaAttrBytes: Maybe[Array[Byte]],
        // Additional attribute payloads: BootstrapMethods, NestHost, NestMembers, PermittedSubclasses, type annotations
        bootstrapMethodsData: Chunk[Chunk[Int]],
        nestHostIdx: Maybe[Int],
        nestMemberIdxs: Chunk[Int],
        permittedSubclassIdxs: Chunk[Int],
        visibleTypeAnnotationBytes: Maybe[Array[Byte]],
        invisibleTypeAnnotationBytes: Maybe[Array[Byte]]
    )

    private def readClassAttributes(
        view: ByteView,
        pool: ConstantPool,
        path: String
    )(using Frame, AllowUnsafe): Result[TastyError, ClassAttributes] =
        val count = readU2(view)
        readClassAttrList(
            view,
            pool,
            path,
            count,
            Maybe.empty,
            Chunk.empty,
            Maybe.empty,
            Maybe.empty,
            hasRecord = false,
            Chunk.empty,
            Maybe.empty,
            Maybe.empty,
            Maybe.empty,
            Maybe.empty,
            Chunk.empty,
            Maybe.empty,
            Chunk.empty,
            Chunk.empty,
            Maybe.empty,
            Maybe.empty
        )
    end readClassAttributes

    private def readClassAttrList(
        view: ByteView,
        pool: ConstantPool,
        path: String,
        remaining: Int,
        sigIdx: Maybe[Int],
        innerClasses: Chunk[(Int, Int, Int, Int)],
        enclosingClassIdx: Maybe[Int],
        enclosingMethodIdx: Maybe[Int],
        hasRecord: Boolean,
        recordComponents: Chunk[(Int, Int, Maybe[Int])],
        visibleAnnBytes: Maybe[Array[Byte]],
        invisibleAnnBytes: Maybe[Array[Byte]],
        scalaSigBytes: Maybe[Array[Byte]],
        scalaAttrBytes: Maybe[Array[Byte]],
        bootstrapMethodsData: Chunk[Chunk[Int]],
        nestHostIdx: Maybe[Int],
        nestMemberIdxs: Chunk[Int],
        permittedSubclassIdxs: Chunk[Int],
        visibleTypeAnnBytes: Maybe[Array[Byte]],
        invisibleTypeAnnBytes: Maybe[Array[Byte]]
    )(using Frame, AllowUnsafe): Result[TastyError, ClassAttributes] =
        if remaining == 0 then
            Result.Success(ClassAttributes(
                sigIdx,
                innerClasses,
                enclosingClassIdx,
                enclosingMethodIdx,
                hasRecord,
                recordComponents,
                visibleAnnBytes,
                invisibleAnnBytes,
                scalaSigBytes,
                scalaAttrBytes,
                bootstrapMethodsData,
                nestHostIdx,
                nestMemberIdxs,
                permittedSubclassIdxs,
                visibleTypeAnnBytes,
                invisibleTypeAnnBytes
            ))
        else
            val nameIdx = readU2(view)
            val attrLen = readU4(view)
            pool.utf8Unsafe(nameIdx).flatMap { attrName =>
                attrName match
                    case ClassfileFormat.AttrSignature =>
                        val idx = readU2(view)
                        readClassAttrList(
                            view,
                            pool,
                            path,
                            remaining - 1,
                            Present(idx),
                            innerClasses,
                            enclosingClassIdx,
                            enclosingMethodIdx,
                            hasRecord,
                            recordComponents,
                            visibleAnnBytes,
                            invisibleAnnBytes,
                            scalaSigBytes,
                            scalaAttrBytes,
                            bootstrapMethodsData,
                            nestHostIdx,
                            nestMemberIdxs,
                            permittedSubclassIdxs,
                            visibleTypeAnnBytes,
                            invisibleTypeAnnBytes
                        )

                    case ClassfileFormat.AttrInnerClasses =>
                        val numClasses = readU2(view)
                        var arr        = Chunk.empty[(Int, Int, Int, Int)]
                        var k          = 0
                        while k < numClasses do
                            val inner = readU2(view)
                            val outer = readU2(view)
                            val name  = readU2(view)
                            val flags = readU2(view)
                            arr = arr.appended((inner, outer, name, flags))
                            k += 1
                        end while
                        readClassAttrList(
                            view,
                            pool,
                            path,
                            remaining - 1,
                            sigIdx,
                            arr,
                            enclosingClassIdx,
                            enclosingMethodIdx,
                            hasRecord,
                            recordComponents,
                            visibleAnnBytes,
                            invisibleAnnBytes,
                            scalaSigBytes,
                            scalaAttrBytes,
                            bootstrapMethodsData,
                            nestHostIdx,
                            nestMemberIdxs,
                            permittedSubclassIdxs,
                            visibleTypeAnnBytes,
                            invisibleTypeAnnBytes
                        )

                    case ClassfileFormat.AttrEnclosingMethod =>
                        val classIdx    = readU2(view)
                        val methodIdx   = readU2(view)
                        val methodMaybe = if methodIdx == 0 then Maybe.empty[Int] else Present(methodIdx)
                        readClassAttrList(
                            view,
                            pool,
                            path,
                            remaining - 1,
                            sigIdx,
                            innerClasses,
                            Present(classIdx),
                            methodMaybe,
                            hasRecord,
                            recordComponents,
                            visibleAnnBytes,
                            invisibleAnnBytes,
                            scalaSigBytes,
                            scalaAttrBytes,
                            bootstrapMethodsData,
                            nestHostIdx,
                            nestMemberIdxs,
                            permittedSubclassIdxs,
                            visibleTypeAnnBytes,
                            invisibleTypeAnnBytes
                        )

                    case ClassfileFormat.AttrRecord =>
                        // Parse Record attribute: list of components with name, descriptor,
                        // and optional Signature sub-attribute.
                        readRecordComponents(view, pool, path).flatMap { comps =>
                            readClassAttrList(
                                view,
                                pool,
                                path,
                                remaining - 1,
                                sigIdx,
                                innerClasses,
                                enclosingClassIdx,
                                enclosingMethodIdx,
                                hasRecord = true,
                                comps,
                                visibleAnnBytes,
                                invisibleAnnBytes,
                                scalaSigBytes,
                                scalaAttrBytes,
                                bootstrapMethodsData,
                                nestHostIdx,
                                nestMemberIdxs,
                                permittedSubclassIdxs,
                                visibleTypeAnnBytes,
                                invisibleTypeAnnBytes
                            )
                        }

                    case ClassfileFormat.AttrRuntimeVisibleAnnotations =>
                        val bytes = captureBytes(view, attrLen)
                        readClassAttrList(
                            view,
                            pool,
                            path,
                            remaining - 1,
                            sigIdx,
                            innerClasses,
                            enclosingClassIdx,
                            enclosingMethodIdx,
                            hasRecord,
                            recordComponents,
                            Present(bytes),
                            invisibleAnnBytes,
                            scalaSigBytes,
                            scalaAttrBytes,
                            bootstrapMethodsData,
                            nestHostIdx,
                            nestMemberIdxs,
                            permittedSubclassIdxs,
                            visibleTypeAnnBytes,
                            invisibleTypeAnnBytes
                        )

                    case ClassfileFormat.AttrRuntimeInvisibleAnnotations =>
                        val bytes = captureBytes(view, attrLen)
                        readClassAttrList(
                            view,
                            pool,
                            path,
                            remaining - 1,
                            sigIdx,
                            innerClasses,
                            enclosingClassIdx,
                            enclosingMethodIdx,
                            hasRecord,
                            recordComponents,
                            visibleAnnBytes,
                            Present(bytes),
                            scalaSigBytes,
                            scalaAttrBytes,
                            bootstrapMethodsData,
                            nestHostIdx,
                            nestMemberIdxs,
                            permittedSubclassIdxs,
                            visibleTypeAnnBytes,
                            invisibleTypeAnnBytes
                        )

                    case ClassfileFormat.AttrScalaSig =>
                        // Scala 2 compact-encoded pickle
                        val bytes = captureBytes(view, attrLen)
                        readClassAttrList(
                            view,
                            pool,
                            path,
                            remaining - 1,
                            sigIdx,
                            innerClasses,
                            enclosingClassIdx,
                            enclosingMethodIdx,
                            hasRecord,
                            recordComponents,
                            visibleAnnBytes,
                            invisibleAnnBytes,
                            Present(bytes),
                            scalaAttrBytes,
                            bootstrapMethodsData,
                            nestHostIdx,
                            nestMemberIdxs,
                            permittedSubclassIdxs,
                            visibleTypeAnnBytes,
                            invisibleTypeAnnBytes
                        )

                    case ClassfileFormat.AttrScala =>
                        // Scala 2 ZLIB-compressed pickle
                        val bytes = captureBytes(view, attrLen)
                        readClassAttrList(
                            view,
                            pool,
                            path,
                            remaining - 1,
                            sigIdx,
                            innerClasses,
                            enclosingClassIdx,
                            enclosingMethodIdx,
                            hasRecord,
                            recordComponents,
                            visibleAnnBytes,
                            invisibleAnnBytes,
                            scalaSigBytes,
                            Present(bytes),
                            bootstrapMethodsData,
                            nestHostIdx,
                            nestMemberIdxs,
                            permittedSubclassIdxs,
                            visibleTypeAnnBytes,
                            invisibleTypeAnnBytes
                        )

                    case ClassfileFormat.AttrBootstrapMethods =>
                        // JVMS §4.7.23: u2 num_bootstrap_methods, each: u2 bootstrap_method_ref, u2 num_bootstrap_arguments, u2[n]
                        val bsmData = readBootstrapMethodsData(view)
                        readClassAttrList(
                            view,
                            pool,
                            path,
                            remaining - 1,
                            sigIdx,
                            innerClasses,
                            enclosingClassIdx,
                            enclosingMethodIdx,
                            hasRecord,
                            recordComponents,
                            visibleAnnBytes,
                            invisibleAnnBytes,
                            scalaSigBytes,
                            scalaAttrBytes,
                            bsmData,
                            nestHostIdx,
                            nestMemberIdxs,
                            permittedSubclassIdxs,
                            visibleTypeAnnBytes,
                            invisibleTypeAnnBytes
                        )

                    case ClassfileFormat.AttrNestHost =>
                        // JVMS §4.7.28: u2 host_class_index
                        val idx = readU2(view)
                        readClassAttrList(
                            view,
                            pool,
                            path,
                            remaining - 1,
                            sigIdx,
                            innerClasses,
                            enclosingClassIdx,
                            enclosingMethodIdx,
                            hasRecord,
                            recordComponents,
                            visibleAnnBytes,
                            invisibleAnnBytes,
                            scalaSigBytes,
                            scalaAttrBytes,
                            bootstrapMethodsData,
                            Present(idx),
                            nestMemberIdxs,
                            permittedSubclassIdxs,
                            visibleTypeAnnBytes,
                            invisibleTypeAnnBytes
                        )

                    case ClassfileFormat.AttrNestMembers =>
                        // JVMS §4.7.29: u2 number_of_classes, u2[n] classes
                        val n    = readU2(view)
                        var idxs = Chunk.empty[Int]
                        var k    = 0
                        while k < n do
                            idxs = idxs.appended(readU2(view))
                            k += 1
                        readClassAttrList(
                            view,
                            pool,
                            path,
                            remaining - 1,
                            sigIdx,
                            innerClasses,
                            enclosingClassIdx,
                            enclosingMethodIdx,
                            hasRecord,
                            recordComponents,
                            visibleAnnBytes,
                            invisibleAnnBytes,
                            scalaSigBytes,
                            scalaAttrBytes,
                            bootstrapMethodsData,
                            nestHostIdx,
                            idxs,
                            permittedSubclassIdxs,
                            visibleTypeAnnBytes,
                            invisibleTypeAnnBytes
                        )

                    case ClassfileFormat.AttrPermittedSubclasses =>
                        // JVMS §4.7.31: u2 number_of_classes, u2[n] classes
                        val n    = readU2(view)
                        var idxs = Chunk.empty[Int]
                        var k    = 0
                        while k < n do
                            idxs = idxs.appended(readU2(view))
                            k += 1
                        readClassAttrList(
                            view,
                            pool,
                            path,
                            remaining - 1,
                            sigIdx,
                            innerClasses,
                            enclosingClassIdx,
                            enclosingMethodIdx,
                            hasRecord,
                            recordComponents,
                            visibleAnnBytes,
                            invisibleAnnBytes,
                            scalaSigBytes,
                            scalaAttrBytes,
                            bootstrapMethodsData,
                            nestHostIdx,
                            nestMemberIdxs,
                            idxs,
                            visibleTypeAnnBytes,
                            invisibleTypeAnnBytes
                        )

                    case ClassfileFormat.AttrRuntimeVisibleTypeAnnotations =>
                        val bytes = captureBytes(view, attrLen)
                        readClassAttrList(
                            view,
                            pool,
                            path,
                            remaining - 1,
                            sigIdx,
                            innerClasses,
                            enclosingClassIdx,
                            enclosingMethodIdx,
                            hasRecord,
                            recordComponents,
                            visibleAnnBytes,
                            invisibleAnnBytes,
                            scalaSigBytes,
                            scalaAttrBytes,
                            bootstrapMethodsData,
                            nestHostIdx,
                            nestMemberIdxs,
                            permittedSubclassIdxs,
                            Present(bytes),
                            invisibleTypeAnnBytes
                        )

                    case ClassfileFormat.AttrRuntimeInvisibleTypeAnnotations =>
                        val bytes = captureBytes(view, attrLen)
                        readClassAttrList(
                            view,
                            pool,
                            path,
                            remaining - 1,
                            sigIdx,
                            innerClasses,
                            enclosingClassIdx,
                            enclosingMethodIdx,
                            hasRecord,
                            recordComponents,
                            visibleAnnBytes,
                            invisibleAnnBytes,
                            scalaSigBytes,
                            scalaAttrBytes,
                            bootstrapMethodsData,
                            nestHostIdx,
                            nestMemberIdxs,
                            permittedSubclassIdxs,
                            visibleTypeAnnBytes,
                            Present(bytes)
                        )

                    // Carve-out: classfile attribute-name dispatch on String; unknown attributes are skipped per JVMS
                    case _ =>
                        skipBytes(view, attrLen)
                        readClassAttrList(
                            view,
                            pool,
                            path,
                            remaining - 1,
                            sigIdx,
                            innerClasses,
                            enclosingClassIdx,
                            enclosingMethodIdx,
                            hasRecord,
                            recordComponents,
                            visibleAnnBytes,
                            invisibleAnnBytes,
                            scalaSigBytes,
                            scalaAttrBytes,
                            bootstrapMethodsData,
                            nestHostIdx,
                            nestMemberIdxs,
                            permittedSubclassIdxs,
                            visibleTypeAnnBytes,
                            invisibleTypeAnnBytes
                        )
            }

    /** Parse Record attribute components.
      *
      * Each component has: nameIdx (u2), descriptorIdx (u2), and attrCount (u2) sub-attributes. We extract Signature sub-attribute index if
      * present; skip others.
      */
    private def readRecordComponents(
        view: ByteView,
        pool: ConstantPool,
        path: String
    )(using Frame, AllowUnsafe): Result[TastyError, Chunk[(Int, Int, Maybe[Int])]] =
        val numComponents = readU2(view)
        readRecordComponentList(view, pool, path, numComponents, 0, Chunk.empty)
    end readRecordComponents

    private def readRecordComponentList(
        view: ByteView,
        pool: ConstantPool,
        path: String,
        total: Int,
        idx: Int,
        acc: Chunk[(Int, Int, Maybe[Int])]
    )(using Frame, AllowUnsafe): Result[TastyError, Chunk[(Int, Int, Maybe[Int])]] =
        if idx >= total then Result.Success(acc)
        else
            val compNameIdx = readU2(view)
            val compDescIdx = readU2(view)
            val compAttrCnt = readU2(view)
            readRecordComponentAttributes(view, pool, path, compAttrCnt, Maybe.empty).flatMap { sigIdx =>
                readRecordComponentList(
                    view,
                    pool,
                    path,
                    total,
                    idx + 1,
                    acc.appended((compNameIdx, compDescIdx, sigIdx))
                )
            }

    private def readRecordComponentAttributes(
        view: ByteView,
        pool: ConstantPool,
        path: String,
        remaining: Int,
        sigIdx: Maybe[Int]
    )(using Frame, AllowUnsafe): Result[TastyError, Maybe[Int]] =
        if remaining == 0 then Result.Success(sigIdx)
        else
            val attrNameIdx = readU2(view)
            val attrLen     = readU4(view)
            pool.utf8Unsafe(attrNameIdx).flatMap { attrName =>
                attrName match
                    case ClassfileFormat.AttrSignature =>
                        val idx = readU2(view)
                        readRecordComponentAttributes(view, pool, path, remaining - 1, Present(idx))
                    // Carve-out: classfile attribute-name dispatch on String; unknown attributes are skipped per JVMS
                    case _ =>
                        skipBytes(view, attrLen)
                        readRecordComponentAttributes(view, pool, path, remaining - 1, sigIdx)
            }

    // -------------------------------------------------------------------------
    // Symbol construction
    // -------------------------------------------------------------------------

    private def buildResult(
        pool: ConstantPool,
        arena: TypeArena,
        path: String,
        accessFlags: Int,
        thisBinaryName: String,
        parents: Chunk[Tasty.Type],
        parentBinaryNames: Chunk[String],
        fieldInfos: Chunk[MemberInfo],
        methodInfos: Chunk[MemberInfo],
        classAttrs: ClassAttributes,
        idCounter: IdCounter
    )(using Frame, AllowUnsafe): Result[TastyError, ClassfileResult] =

        val isInterface  = (accessFlags & ClassfileFormat.ACC_INTERFACE) != 0
        val isAnnotation = (accessFlags & ClassfileFormat.ACC_ANNOTATION) != 0
        val isRecord     = classAttrs.hasRecord

        val kind =
            if isInterface || isAnnotation then SymbolKind.Trait
            else SymbolKind.Class

        val baseFlags   = FlagsHelper.fromJvmAccessFlags(accessFlags)
        val javaDefined = Tasty.Flags(Tasty.Flag.JavaDefined)
        val recordFlag  = if isRecord then Tasty.Flags(Tasty.Flag.JavaRecord) else Tasty.Flags.empty
        // Set Sealed flag when the class has a PermittedSubclasses attribute (Java 17+ sealed
        // classes). The JVM access flags do not include a SEALED bit; the sealed status is encoded only by
        // the presence of the PermittedSubclasses classfile attribute (JVMS 4.7.31).
        val sealedFlag = if classAttrs.permittedSubclassIdxs.nonEmpty then Tasty.Flags(Tasty.Flag.Sealed)
        else Tasty.Flags.empty
        val classFlags = baseFlags.union(javaDefined).union(recordFlag).union(sealedFlag)

        // Build inner class table and fully-qualified name canonicalization.
        // Must be done before constructing the class symbol so we can set the correct owner.
        buildInnerClassTableUnsafe(pool, path, classAttrs.innerClasses).flatMap { innerTable =>
            // Determine the symbol's simple name from the inner class table.
            // Owner resolution is deferred to finalizeMerge via the fully-qualified name index.
            val symName = resolveSimpleName(thisBinaryName, innerTable)

            // Collect enclosing-method data (class fully-qualified name + method name) for deferred orchestrator resolution.
            resolveEnclosingMethodData(pool, path, classAttrs.enclosingClassIdx, classAttrs.enclosingMethodIdx, innerTable).flatMap {
                enclosingMethodDataOpt =>
                    // Build record components if this is a record class
                    buildRecordComponents(pool, path, classAttrs.recordComponents, isRecord).flatMap { recordComps =>
                        // Decode class-level annotations
                        decodeAnnotations(pool, classAttrs.visibleAnnotationBytes, classAttrs.invisibleAnnotationBytes).flatMap {
                            classAnnotations =>
                                // Decode type annotations (RuntimeVisibleTypeAnnotations / RuntimeInvisibleTypeAnnotations)
                                decodeTypeAnnotations(classAttrs.visibleTypeAnnotationBytes, pool).flatMap { visibleTypeAnns =>
                                    decodeTypeAnnotations(classAttrs.invisibleTypeAnnotationBytes, pool).flatMap { invisibleTypeAnns =>
                                        val allTypeAnns = visibleTypeAnns ++ invisibleTypeAnns
                                        // Collect NestHost fully-qualified name for deferred orchestrator resolution.
                                        resolveOptionalClassFullName(pool, classAttrs.nestHostIdx).flatMap { nestHostFullNameOpt =>
                                            // Collect NestMembers fully-qualified names for deferred orchestrator resolution.
                                            resolveClassFullNameList(pool, classAttrs.nestMemberIdxs).flatMap { nestMemberFullNameList =>
                                                // Resolve PermittedSubclasses fully-qualified names for finalizeMerge.
                                                resolveClassFullNames(pool, classAttrs.permittedSubclassIdxs).flatMap {
                                                    permittedSubFullNames =>
                                                        // nestHost and nestMembers are populated by finalizeMerge after
                                                        // the full symbol index is available; no sentinel symbols here.
                                                        val classMetadata = Tasty.Java.Metadata(
                                                            throwsTypes = Chunk.empty,
                                                            annotations = classAnnotations,
                                                            enclosingMethod = Absent,
                                                            accessFlags = accessFlags,
                                                            recordComponents = recordComps,
                                                            bootstrapMethods = classAttrs.bootstrapMethodsData,
                                                            nestHost = Absent,
                                                            nestMembers = Chunk.empty,
                                                            paramNames = Chunk.empty,
                                                            runtimeTypeAnnotations = allTypeAnns
                                                        )
                                                        // permittedSubclassIds: placeholder count resolved in finalizeMerge.
                                                        val permSubIds: Maybe[Chunk[Int]] =
                                                            if permittedSubFullNames.nonEmpty then
                                                                Maybe(permittedSubFullNames.map(_ => -1))
                                                            else Maybe.Absent
                                                        val classSym = LoadingSymbol.Materialising(
                                                            id = idCounter.nextId(),
                                                            kind = kind,
                                                            flags = classFlags,
                                                            name = Tasty.Name(symName),
                                                            javaMetadata = Maybe(classMetadata),
                                                            permittedSubclassIds = permSubIds
                                                        )
                                                        // Parse class-level Signature attribute to extract type parameters.
                                                        parseClassTypeParams(pool, classAttrs.signatureIdx, idCounter).flatMap {
                                                            classTypeParams =>
                                                                // Build member symbols (returns pairs of (LoadingSymbol.Materialising, DeclaredType))
                                                                buildMemberSymbols(
                                                                    pool,
                                                                    path,
                                                                    classSym,
                                                                    fieldInfos,
                                                                    isMethods = false,
                                                                    idCounter
                                                                ).flatMap { fieldPairs =>
                                                                    buildMemberSymbols(
                                                                        pool,
                                                                        path,
                                                                        classSym,
                                                                        methodInfos,
                                                                        isMethods = true,
                                                                        idCounter
                                                                    ).flatMap { methodPairs =>
                                                                        val allPairs   = fieldPairs ++ methodPairs
                                                                        val allSymbols = allPairs.map(_._1)
                                                                        // Keyed by symbol.id because LoadingSymbol.Materialising
                                                                        // has a mutable id; structural equality would break
                                                                        // if the id mutates after insertion.
                                                                        val memberTypes =
                                                                            val m = mutable.LongMap.empty[Tasty.Type]
                                                                            allPairs.foreach { case (symbol, tpe) =>
                                                                                m(symbol.id.toLong) = tpe
                                                                            }
                                                                            m
                                                                        end memberTypes
                                                                        val javaResult = ClassfileResult(
                                                                            classSym,
                                                                            parents,
                                                                            innerTable,
                                                                            allSymbols,
                                                                            classTypeParams,
                                                                            arena,
                                                                            memberTypes,
                                                                            // Carry raw binary names of
                                                                            // parent classes/interfaces so finalizeMerge
                                                                            // can resolve them via the fullNameIndex.
                                                                            parentBinaryNames,
                                                                            // Carry permitted subclass fully-qualified names for finalizeMerge
                                                                            // to resolve permittedSubclassIds.
                                                                            permittedSubFullNames,
                                                                            // Carry NestHost fully-qualified name for deferred resolution.
                                                                            nestHostFullNameOpt,
                                                                            // Carry NestMembers fully-qualified names for deferred resolution.
                                                                            nestMemberFullNameList,
                                                                            // Carry enclosing-method data for deferred resolution.
                                                                            enclosingMethodDataOpt,
                                                                            // Authoritative class name from the bytecode constant pool.
                                                                            thisBinaryName
                                                                        )
                                                                        // Dispatch to Scala2PickleReader if a ScalaSig or Scala attribute is present.
                                                                        mergeScala2Pickle(
                                                                            javaResult,
                                                                            arena,
                                                                            classAttrs.scalaSigBytes,
                                                                            classAttrs.scalaAttrBytes
                                                                        )
                                                                    } // end .flatMap { methodPairs =>
                                                                }     // end .flatMap { fieldPairs =>
                                                        }             // end .flatMap { classTypeParams =>
                                                }                     // end .flatMap { permittedSubFullNames =>
                                            }                         // end .flatMap { nestMemberFullNameList =>
                                        }                             // end .flatMap { nestHostFullNameOpt =>
                                    }                                 // end .flatMap { invisibleTypeAnns =>
                                }                                     // end .flatMap { visibleTypeAnns =>
                        }                                             // end .flatMap { classAnnotations =>
                    }                                                 // end .flatMap { recordComps =>
            }                                                         // end .flatMap { enclosingMethodDataOpt =>
        }
    end buildResult

    /** If Scala 2 pickle bytes are present, add Flag.Scala2 to the class symbol and merge the decoded symbols into the result.
      *
      * Returns the original result unmodified when no pickle bytes are present (plain Java classfile).
      */
    private def mergeScala2Pickle(
        javaResult: ClassfileResult,
        arena: TypeArena,
        scalaSigBytes: Maybe[Array[Byte]],
        scalaAttrBytes: Maybe[Array[Byte]]
    )(using Frame, AllowUnsafe): Result[TastyError, ClassfileResult] =
        scalaSigBytes match
            case Present(sigBytes) =>
                // ScalaSig attribute: compact-encoded pickle
                Scala2PickleReader.readScalaSig(sigBytes, arena) match
                    case Result.Success(pickleResult) =>
                        Result.Success(mergePickleResult(javaResult, pickleResult))
                    case Result.Failure(_) =>
                        // On decode failure, mark with Scala2 flag but keep Java symbols only
                        Result.Success(markScala2Flag(javaResult))
                    case Result.Panic(t) =>
                        Result.Panic(t)
            case Absent =>
                scalaAttrBytes match
                    case Present(attrBytes) =>
                        // Scala attribute: ZLIB-compressed pickle (JVM-only; JS/Native will get NotImplemented, treat as no-op)
                        Scala2PickleReader.readScalaAttr(attrBytes, arena) match
                            case Result.Success(pickleResult) =>
                                Result.Success(mergePickleResult(javaResult, pickleResult))
                            case Result.Failure(_) =>
                                Result.Success(markScala2Flag(javaResult))
                            case Result.Panic(t) =>
                                Result.Panic(t)
                    case Absent =>
                        Result.Success(javaResult)

    /** Fallback when Scala 2 pickle decode fails: return the Java-only result unchanged.
      *
      * The class symbol's flags field is an immutable val, so we cannot OR in Flag.Scala2 here.
      * The successful path produces a fresh symbol carrying Flag.Scala2 via mergePickleResult.
      */
    private def markScala2Flag(result: ClassfileResult)(using AllowUnsafe): ClassfileResult =
        result
    end markScala2Flag

    /** Merge a Scala2PickleResult into a ClassfileResult.
      *
      * Returns a fresh ClassfileResult whose classSymbol is the pickle's class symbol when present (it already
      * carries Flag.Scala2 and the correct Scala-level flags such as Flag.Case), falling back to the Java class
      * symbol otherwise. The Java class symbol is retained in the symbols list. Parent types prefer the pickle
      * list when non-empty.
      */
    private def mergePickleResult(
        javaResult: ClassfileResult,
        pickleResult: kyo.internal.tasty.scala2.Scala2PickleResult
    )(using Frame, AllowUnsafe): ClassfileResult =
        // Pickle symbols carry their own ids; assign new ids from a high-negative range to avoid
        // collision with the main idCounter for this decode session.
        var pickleId = Int.MinValue
        def pickleNextId(): Int =
            val id = pickleId
            pickleId += 1
            id
        end pickleNextId

        def symToLoading(symbol: Tasty.Symbol): LoadingSymbol.Materialising =
            LoadingSymbol.Materialising(
                id = pickleNextId(),
                kind = symbol.kind,
                flags = symbol.flags,
                name = symbol.name
            )

        val mergedClassSym = pickleResult.classSymbol match
            case Present(pickleSym) => symToLoading(pickleSym)
            case Absent             => javaResult.classSymbol

        // All symbols: Java symbols (minus the class itself) + pickle symbols converted to LoadingSymbol
        val mergedSymbols = javaResult.symbols ++ pickleResult.symbols.map(symToLoading)

        // Parents: prefer pickle parents if available
        val mergedParents =
            if pickleResult.parents.nonEmpty then pickleResult.parents
            else javaResult.parents

        ClassfileResult(
            mergedClassSym,
            mergedParents,
            javaResult.innerClassTable,
            mergedSymbols,
            javaResult.typeParams,
            javaResult.arena,
            javaResult.memberTypes,
            binaryName = javaResult.binaryName
        )
    end mergePickleResult

    /** Parse the class-level Signature attribute to extract type parameter symbols.
      *
      * Returns Chunk.empty if no Signature attribute is present or if parsing fails (graceful degradation: erased-type info is still
      * correct without generic signatures).
      */
    private def parseClassTypeParams(
        pool: ConstantPool,
        signatureIdx: Maybe[Int],
        idCounter: IdCounter
    )(using Frame, AllowUnsafe): Result[TastyError, Chunk[LoadingSymbol.Materialising]] =
        // Type params from classfile signatures are converted to LoadingSymbol.Materialising for Phase C.
        // On parse failure, graceful degradation returns an empty chunk.
        signatureIdx match
            case Absent => Result.Success(Chunk.empty)
            case Present(idx) =>
                pool.utf8Unsafe(idx).flatMap { sig =>
                    JavaSignatures.parseClassSignatureUnsafe(sig) match
                        case Result.Success((typeParams, _)) =>
                            Result.Success(typeParams.map { symbol =>
                                LoadingSymbol.Materialising(
                                    id = idCounter.nextId(),
                                    kind = symbol.kind,
                                    flags = symbol.flags,
                                    name = symbol.name
                                )
                            })
                        case Result.Failure(_) => Result.Success(Chunk.empty)
                        case Result.Panic(t)   => Result.Panic(t)
                }

    /** Resolve the symbol's simple name for a class, using the inner class table.
      *
      * For inner classes: uses the simple inner name from the table. For anonymous/local classes: uses the '$'-form name. For top-level
      * classes: uses the simple class name (last segment after '/'). Owner resolution is deferred to finalizeMerge.
      */
    private def resolveSimpleName(
        thisBinaryName: String,
        innerTable: Map[String, (String, String)]
    ): String =
        innerTable.get(thisBinaryName) match
            case Some((outerBinaryName, innerSimpleName))
                if outerBinaryName.nonEmpty && innerSimpleName.nonEmpty =>
                innerSimpleName
            case Some(_) =>
                // Anonymous, local, or partially-resolved: use '$'-form name
                thisBinaryName.replace('/', '.')
            case None =>
                // Top-level class: the class name is the last segment after the last '/'.
                val segments = thisBinaryName.split("/")
                segments.last

    /** Collect enclosing-method data (enclosing class dotted fully-qualified name, method name) for deferred orchestrator resolution.
      *
      * Returns Maybe.Absent when the EnclosingMethod attribute is absent or when method_index == 0 (enclosed by initializer, not a named
      * method). The orchestrator resolves the fully-qualified name to a final Symbol after finalizeMerge.
      */
    private def resolveEnclosingMethodData(
        pool: ConstantPool,
        path: String,
        enclosingClassIdx: Maybe[Int],
        enclosingMethodIdx: Maybe[Int],
        innerTable: Map[String, (String, String)]
    )(using Frame, AllowUnsafe): Result[TastyError, Maybe[(String, String)]] =
        enclosingClassIdx match
            case Absent => Result.Success(Absent)
            case Present(classIdx) =>
                pool.classRefUnsafe(classIdx).flatMap { enclosingBinaryName =>
                    val enclosingFullName = FullNameCanonicalizer.toFullName(enclosingBinaryName, innerTable)
                    enclosingMethodIdx match
                        case Absent =>
                            // The EnclosingMethod attribute is present but method_index == 0:
                            // enclosed by a class/field initializer, not a named method (JVMS 4.7.7).
                            Result.Success(Absent)
                        case Present(methodIdx) =>
                            pool.nameAndTypeUnsafe(methodIdx).map { (methodName, _) =>
                                Present((enclosingFullName, methodName))
                            }
                    end match
                }

    private def buildRecordComponents(
        pool: ConstantPool,
        path: String,
        components: Chunk[(Int, Int, Maybe[Int])],
        isRecord: Boolean
    )(using Frame, AllowUnsafe): Result[TastyError, Chunk[Tasty.Java.RecordComponent]] =
        if !isRecord || components.isEmpty then Result.Success(Chunk.empty)
        else buildRecordComponentList(pool, path, components, 0, Chunk.empty)

    private def buildRecordComponentList(
        pool: ConstantPool,
        path: String,
        components: Chunk[(Int, Int, Maybe[Int])],
        idx: Int,
        acc: Chunk[Tasty.Java.RecordComponent]
    )(using Frame, AllowUnsafe): Result[TastyError, Chunk[Tasty.Java.RecordComponent]] =
        if idx >= components.length then Result.Success(acc)
        else
            val (nameIdx, descIdx, sigIdx) = components(idx)
            pool.utf8Unsafe(nameIdx).flatMap { compName =>
                pool.utf8Unsafe(descIdx).flatMap { descriptor =>
                    resolveComponentType(pool, path, descriptor, sigIdx).flatMap { compType =>
                        val name = Tasty.Name(compName)
                        buildRecordComponentList(
                            pool,
                            path,
                            components,
                            idx + 1,
                            acc.appended(Tasty.Java.RecordComponent(name, compType))
                        )
                    }
                }
            }

    private def resolveComponentType(
        pool: ConstantPool,
        path: String,
        descriptor: String,
        sigIdx: Maybe[Int]
    )(using Frame, AllowUnsafe): Result[TastyError, Tasty.Type] =
        sigIdx match
            case Present(idx) =>
                pool.utf8Unsafe(idx).flatMap { sig =>
                    JavaSignatures.parseFieldSignatureUnsafe(sig) match
                        case Result.Success(tpe) => Result.Success(tpe)
                        case Result.Failure(_)   => Result.Success(parseErasedDescriptorType(descriptor))
                        case Result.Panic(t)     => Result.Panic(t)
                }
            case Absent =>
                Result.Success(parseErasedDescriptorType(descriptor))

    /** Parse a JVM field descriptor (erased type) into a Tasty.Type.
      *
      * Handles: B/C/D/F/I/J/S/Z (primitives), V (void), [X (array), Lfoo/bar/Baz; (class reference).
      */
    private def parseErasedDescriptorType(descriptor: String)(using AllowUnsafe): Tasty.Type =
        descriptor match
            case "B" => primType("scala.Byte")
            case "C" => primType("scala.Char")
            case "D" => primType("scala.Double")
            case "F" => primType("scala.Float")
            case "I" => primType("scala.Int")
            case "J" => primType("scala.Long")
            case "S" => primType("scala.Short")
            case "Z" => primType("scala.Boolean")
            case "V" => primType("scala.Unit")
            case s if s.startsWith("[") =>
                Tasty.Type.Array(parseErasedDescriptorType(s.substring(1)))
            case s if s.startsWith("L") && s.endsWith(";") =>
                val binaryName = s.substring(1, s.length - 1)
                unresolvedType(binaryName)
            case other =>
                unresolvedType(other)

    private def primType(fullName: String)(using AllowUnsafe): Tasty.Type =
        // Sentinel id -1 for primitive type references (not resolved against classpath).
        Tasty.Type.Named(kyo.Tasty.SymbolId(-1))
    end primType

    private def decodeAnnotations(
        pool: ConstantPool,
        visibleBytes: Maybe[Array[Byte]],
        invisibleBytes: Maybe[Array[Byte]]
    )(using Frame, AllowUnsafe): Result[TastyError, Chunk[Tasty.Java.Annotation]] =
        val visibleR: Result[TastyError, Chunk[Tasty.Java.Annotation]] =
            visibleBytes match
                case Absent => Result.Success(Chunk.empty)
                case Present(bytes) =>
                    val annView = ByteView(bytes)
                    JavaAnnotationUnpickler.readAnnotations(annView, pool)

        visibleR.flatMap { visible =>
            val invisibleR: Result[TastyError, Chunk[Tasty.Java.Annotation]] =
                invisibleBytes match
                    case Absent => Result.Success(Chunk.empty)
                    case Present(bytes) =>
                        val annView = ByteView(bytes)
                        JavaAnnotationUnpickler.readAnnotations(annView, pool)

            invisibleR.map(invisible => visible ++ invisible)
        }
    end decodeAnnotations

    private def buildMemberSymbols(
        pool: ConstantPool,
        path: String,
        owner: LoadingSymbol.Materialising,
        infos: Chunk[MemberInfo],
        isMethods: Boolean,
        idCounter: IdCounter
    )(using Frame, AllowUnsafe): Result[TastyError, Chunk[(LoadingSymbol.Materialising, Tasty.Type)]] =
        buildMemberList(pool, path, owner, infos, 0, Chunk.empty, isMethods, idCounter)

    private def buildMemberList(
        pool: ConstantPool,
        path: String,
        owner: LoadingSymbol.Materialising,
        infos: Chunk[MemberInfo],
        idx: Int,
        acc: Chunk[(LoadingSymbol.Materialising, Tasty.Type)],
        isMethods: Boolean,
        idCounter: IdCounter
    )(using Frame, AllowUnsafe): Result[TastyError, Chunk[(LoadingSymbol.Materialising, Tasty.Type)]] =
        if idx >= infos.length then Result.Success(acc)
        else
            buildOneMemberSymbol(pool, path, owner, infos(idx), isMethods, idCounter).flatMap { pair =>
                buildMemberList(pool, path, owner, infos, idx + 1, acc.appended(pair), isMethods, idCounter)
            }

    private def buildOneMemberSymbol(
        pool: ConstantPool,
        path: String,
        owner: LoadingSymbol.Materialising,
        info: MemberInfo,
        isMethod: Boolean,
        idCounter: IdCounter
    )(using Frame, AllowUnsafe): Result[TastyError, (LoadingSymbol.Materialising, Tasty.Type)] =
        pool.utf8Unsafe(info.nameIdx).flatMap { memberName =>
            val accessFlags = info.accessFlags
            val isStatic    = (accessFlags & ClassfileFormat.ACC_STATIC) != 0
            val isFinal     = (accessFlags & ClassfileFormat.ACC_FINAL) != 0

            val kind =
                if isMethod then SymbolKind.Method
                else if isStatic then SymbolKind.Field
                else if isFinal then SymbolKind.Val
                else SymbolKind.Var

            val baseFlags   = FlagsHelper.fromJvmAccessFlags(accessFlags)
            val javaDefined = Tasty.Flags(Tasty.Flag.JavaDefined)
            val memberFlags = baseFlags.union(javaDefined)

            // Resolve member declared type from descriptor or Signature attribute.
            pool.utf8Unsafe(info.descriptorIdx).flatMap { descriptor =>
                resolveComponentType(pool, path, descriptor, info.signatureIdx).flatMap { memberType =>
                    // Resolve throws types for JavaMetadata
                    resolveThrowsTypes(pool, path, info.exceptionIdxs).flatMap { throwsTypes =>
                        decodeAnnotations(pool, info.visibleAnnotationBytes, info.invisibleAnnotationBytes).map { memberAnnotations =>
                            // Wrap MethodParameters as paramNames entry: ParamGroup(methodName, paramName chunk)
                            val paramNamesEntry: Chunk[Tasty.Java.ParamGroup] =
                                if info.paramNames.isEmpty then Chunk.empty
                                else
                                    Chunk(Tasty.Java.ParamGroup(
                                        Tasty.Name(memberName),
                                        info.paramNames.map(n => Tasty.Name(n))
                                    ))
                            val metadata = Tasty.Java.Metadata(
                                throwsTypes = throwsTypes,
                                annotations = memberAnnotations,
                                enclosingMethod = Absent,
                                accessFlags = accessFlags,
                                recordComponents = Chunk.empty,
                                bootstrapMethods = Chunk.empty,
                                nestHost = Absent,
                                nestMembers = Chunk.empty,
                                paramNames = paramNamesEntry,
                                runtimeTypeAnnotations = Chunk.empty
                            )

                            val symbol = LoadingSymbol.Materialising(
                                id = idCounter.nextId(),
                                kind = kind,
                                flags = memberFlags,
                                name = Tasty.Name(memberName),
                                declaredType = Maybe(memberType),
                                javaMetadata = Maybe(metadata)
                            )
                            (symbol, memberType)
                        }
                    }
                }
            }
        }

    private def resolveThrowsTypes(
        pool: ConstantPool,
        path: String,
        exceptionIdxs: Chunk[Int]
    )(using Frame, AllowUnsafe): Result[TastyError, Chunk[Tasty.Type]] =
        resolveThrowsList(pool, path, exceptionIdxs, 0, Chunk.empty)

    private def resolveThrowsList(
        pool: ConstantPool,
        path: String,
        idxs: Chunk[Int],
        i: Int,
        acc: Chunk[Tasty.Type]
    )(using Frame, AllowUnsafe): Result[TastyError, Chunk[Tasty.Type]] =
        if i >= idxs.length then Result.Success(acc)
        else
            pool.classRefUnsafe(idxs(i)).flatMap { binaryName =>
                // Encode the throws class as a TermRef carrying the dotted fully-qualified name as the name.
                // This avoids emitting Named(SymbolId(-1)) sentinel while still preserving the
                // class identity (accessible via pattern-match on Type.TermRef(..., name)).
                // The fullNameIndex is not available here; full resolution to a Named(SymbolId(idx))
                // is deferred to a future finalizeMerge pass if needed.
                val dottedFullName = binaryName.replace('/', '.')
                val throwType      = Tasty.Type.TermRef(Tasty.Type.Tuple(Chunk.empty), Tasty.Name(dottedFullName))
                resolveThrowsList(pool, path, idxs, i + 1, acc.appended(throwType))
            }

    private def buildInnerClassTable(
        pool: ConstantPool,
        path: String,
        innerClasses: Chunk[(Int, Int, Int, Int)]
    )(using Frame): Map[String, (String, String)] < (Sync & Abort[TastyError]) =
        buildInnerClassList(pool, path, innerClasses, 0, Map.empty)

    /** Unsafe-tier sibling of `buildInnerClassTable`. Runs the suspension synchronously under the caller's `AllowUnsafe` and surfaces
      * failures as `Result[TastyError, Map[String, (String, String)]]`. Used by inventory-site migrations that need to call the inner-class
      * table builder from a non-`< S` body.
      */
    private[classfile] def buildInnerClassTableUnsafe(
        pool: ConstantPool,
        path: String,
        innerClasses: Chunk[(Int, Int, Int, Int)]
    )(using Frame, AllowUnsafe): Result[TastyError, Map[String, (String, String)]] =
        Sync.Unsafe.evalOrThrow(Abort.run(buildInnerClassTable(pool, path, innerClasses)))

    private def buildInnerClassList(
        pool: ConstantPool,
        path: String,
        innerClasses: Chunk[(Int, Int, Int, Int)],
        i: Int,
        acc: Map[String, (String, String)]
    )(using Frame): Map[String, (String, String)] < (Sync & Abort[TastyError]) =
        if i >= innerClasses.length then acc
        else
            val (innerIdx, outerIdx, nameIdx, _) = innerClasses(i)
            if innerIdx == 0 then
                buildInnerClassList(pool, path, innerClasses, i + 1, acc)
            else
                pool.classRef(innerIdx).map { innerBN =>
                    resolveOptionalClassRef(pool, path, outerIdx).map { outerBN =>
                        resolveOptionalUtf8(pool, path, nameIdx).map { simpleName =>
                            buildInnerClassList(pool, path, innerClasses, i + 1, acc + (innerBN -> (outerBN, simpleName)))
                        }
                    }
                }
            end if

    /** Resolve a Maybe[Int] class-pool index to a Maybe[String] dotted fully-qualified name, used for NestHost.
      *
      * The orchestrator resolves the fully-qualified name to a final Symbol after finalizeMerge.
      */
    private def resolveOptionalClassFullName(
        pool: ConstantPool,
        idx: Maybe[Int]
    )(using Frame, AllowUnsafe): Result[TastyError, Maybe[String]] =
        idx match
            case Absent => Result.Success(Absent)
            case Present(i) =>
                pool.classRefUnsafe(i).map(binaryName => Present(binaryName.replace('/', '.')))

    /** Resolve a Chunk[Int] of class-pool indices to a Chunk[String] of dotted fully-qualified names.
      *
      * Used for PermittedSubclasses so finalizeMerge can resolve permittedSubclassIds.
      */
    private def resolveClassFullNames(
        pool: ConstantPool,
        idxs: Chunk[Int]
    )(using Frame, AllowUnsafe): Result[TastyError, Chunk[String]] =
        resolveClassFullNamesRec(pool, idxs, 0, Chunk.empty)

    private def resolveClassFullNamesRec(
        pool: ConstantPool,
        idxs: Chunk[Int],
        i: Int,
        acc: Chunk[String]
    )(using Frame, AllowUnsafe): Result[TastyError, Chunk[String]] =
        if i >= idxs.length then Result.Success(acc)
        else
            pool.classRefUnsafe(idxs(i)).flatMap { binaryName =>
                resolveClassFullNamesRec(pool, idxs, i + 1, acc.appended(binaryName.replace('/', '.')))
            }

    /** Resolve a Chunk[Int] of class-pool indices to a Chunk[String] of dotted fully-qualified names, used for NestMembers.
      *
      * The orchestrator resolves these fully-qualified names to final Symbols after finalizeMerge.
      */
    private def resolveClassFullNameList(
        pool: ConstantPool,
        idxs: Chunk[Int]
    )(using Frame, AllowUnsafe): Result[TastyError, Chunk[String]] =
        resolveClassFullNameListRec(pool, idxs, 0, Chunk.empty)

    private def resolveClassFullNameListRec(
        pool: ConstantPool,
        idxs: Chunk[Int],
        i: Int,
        acc: Chunk[String]
    )(using Frame, AllowUnsafe): Result[TastyError, Chunk[String]] =
        if i >= idxs.length then Result.Success(acc)
        else
            pool.classRefUnsafe(idxs(i)).flatMap { binaryName =>
                resolveClassFullNameListRec(pool, idxs, i + 1, acc.appended(binaryName.replace('/', '.')))
            }

    /** Decode a Maybe[Array[Byte]] type-annotation attribute body.
      *
      * Returns Chunk.empty when the bytes are Absent (attribute not present).
      */
    private def decodeTypeAnnotations(
        bytes: Maybe[Array[Byte]],
        pool: ConstantPool
    )(using Frame, AllowUnsafe): Result[TastyError, Chunk[Tasty.Java.Annotation]] =
        bytes match
            case Absent        => Result.Success(Chunk.empty)
            case Present(data) => decodeTypeAnnotations(data, pool)

    private def resolveOptionalClassRef(
        pool: ConstantPool,
        path: String,
        idx: Int
    )(using Frame): String < (Sync & Abort[TastyError]) =
        if idx == 0 then ""
        else pool.classRef(idx)

    private def resolveOptionalUtf8(
        pool: ConstantPool,
        path: String,
        idx: Int
    )(using Frame): String < (Sync & Abort[TastyError]) =
        if idx == 0 then ""
        else pool.utf8(idx)

    // -------------------------------------------------------------------------
    // Attribute helpers: BootstrapMethods, NestHost, NestMembers, type annotations
    // -------------------------------------------------------------------------

    /** Parse BootstrapMethods attribute body (JVMS §4.7.23).
      *
      * Returns a Chunk where each entry is a Chunk of u2 indices: [bootstrap_method_ref, arg0, arg1, ...].
      */
    private def readBootstrapMethodsData(
        view: ByteView
    )(using AllowUnsafe): Chunk[Chunk[Int]] =
        val n   = readU2(view)
        var bsm = Chunk.empty[Chunk[Int]]
        var i   = 0
        while i < n do
            val methodRef = readU2(view)
            val argCount  = readU2(view)
            var entry     = Chunk.empty[Int]
            entry = entry.appended(methodRef)
            var j = 0
            while j < argCount do
                entry = entry.appended(readU2(view))
                j += 1
            bsm = bsm.appended(entry)
            i += 1
        end while
        bsm
    end readBootstrapMethodsData

    /** Parse MethodParameters attribute body (JVMS §4.7.24).
      *
      * Returns a Chunk of parameter name strings (empty string for unnamed parameters, i.e. name_index == 0).
      */
    private def readMethodParameterNames(
        view: ByteView,
        pool: ConstantPool
    )(using Frame, AllowUnsafe): Result[TastyError, Chunk[String]] =
        val count = readU1(view)
        readMethodParamList(view, pool, count, 0, Chunk.empty)
    end readMethodParameterNames

    private def readMethodParamList(
        view: ByteView,
        pool: ConstantPool,
        total: Int,
        idx: Int,
        acc: Chunk[String]
    )(using Frame, AllowUnsafe): Result[TastyError, Chunk[String]] =
        if idx >= total then Result.Success(acc)
        else
            val nameIdx = readU2(view)
            discard(readU2(view)) // access_flags: not needed
            if nameIdx == 0 then
                readMethodParamList(view, pool, total, idx + 1, acc.appended(""))
            else
                pool.utf8Unsafe(nameIdx).flatMap { name =>
                    readMethodParamList(view, pool, total, idx + 1, acc.appended(name))
                }
            end if

    /** Skip the target_info union from a type_annotation (JVMS §4.7.20 Table 4.7.20-A/B).
      *
      * After reading the u1 target_type, reads and discards the variable-length target_info union. Also reads and discards the target_path
      * (type_path). Leaves the view positioned at the start of the annotation type_index.
      *
      * All target_type codes are handled; unknown codes skip 0 bytes of target_info and let the annotation type_index parse proceed
      * normally (graceful degradation).
      */
    private def skipTypeAnnotationTargetAndPath(view: ByteView)(using AllowUnsafe): Unit =
        val targetType = readU1(view) & 0xff
        // JVMS Table 4.7.20-A: target_info union size by target_type code
        targetType match
            case 0x00 | 0x01 =>
                // type_parameter_target: u1 type_parameter_index
                discard(readU1(view))
            case 0x10 =>
                // supertype_target: u2 supertype_index
                discard(readU2(view))
            case 0x11 | 0x12 =>
                // type_parameter_bound_target: u1 type_parameter_index, u1 bound_index
                discard(readU1(view))
                discard(readU1(view))
            case 0x13 | 0x14 | 0x15 =>
                // empty_target: no fields
                ()
            case 0x16 =>
                // formal_parameter_target: u1 formal_parameter_index
                discard(readU1(view))
            case 0x17 =>
                // throws_target: u2 throws_type_index
                discard(readU2(view))
            case 0x40 | 0x41 =>
                // localvar_target: u2 table_length, then 3×u2 per entry
                val tableLen = readU2(view)
                skipBytes(view, tableLen * 6)
            case 0x42 =>
                // catch_target: u2 exception_table_index
                discard(readU2(view))
            case 0x43 | 0x44 | 0x45 | 0x46 =>
                // offset_target: u2 offset
                discard(readU2(view))
            case 0x47 | 0x48 | 0x49 | 0x4a | 0x4b =>
                // type_argument_target: u2 offset, u1 type_argument_index
                discard(readU2(view))
                discard(readU1(view))
            // Carve-out: target_type byte dispatch; spec-defined target_type values
            case _ =>
                // Unknown target type: no target_info bytes consumed; graceful degradation
                ()
        end match
        // type_path: u1 path_length, then 2 bytes per path entry
        val pathLen = readU1(view) & 0xff
        skipBytes(view, pathLen * 2)
    end skipTypeAnnotationTargetAndPath

    /** Decode a RuntimeVisibleTypeAnnotations or RuntimeInvisibleTypeAnnotations attribute body.
      *
      * The attribute body starts with u2 num_annotations. Each type_annotation starts with target_info (variable length) + target_path
      * (variable), then the regular annotation structure (type_index + element_value_pairs). We skip target_info/target_path and decode the
      * annotation using JavaAnnotationUnpickler.readOneAnnotation (reusing pool-resident strings).
      */
    private def decodeTypeAnnotations(
        bytes: Array[Byte],
        pool: ConstantPool
    )(using Frame, AllowUnsafe): Result[TastyError, Chunk[Tasty.Java.Annotation]] =
        val typeAnnView = ByteView(bytes)
        val count       = readU2(typeAnnView)
        decodeTypeAnnotationList(typeAnnView, pool, count, 0, Chunk.empty)
    end decodeTypeAnnotations

    private def decodeTypeAnnotationList(
        view: ByteView,
        pool: ConstantPool,
        total: Int,
        idx: Int,
        acc: Chunk[Tasty.Java.Annotation]
    )(using Frame, AllowUnsafe): Result[TastyError, Chunk[Tasty.Java.Annotation]] =
        if idx >= total then Result.Success(acc)
        else
            skipTypeAnnotationTargetAndPath(view)
            JavaAnnotationUnpickler.readOneAnnotation(view, pool, depth = 0).flatMap { annotation =>
                decodeTypeAnnotationList(view, pool, total, idx + 1, acc.appended(annotation))
            }

    // -------------------------------------------------------------------------
    // Byte helpers
    // -------------------------------------------------------------------------

    /** Capture `count` bytes from `view` into a fresh Array[Byte]. */
    private def captureBytes(view: ByteView, count: Int)(using AllowUnsafe): Array[Byte] =
        view match
            case h: ByteView.Heap =>
                val start = h.positionInt
                skipBytes(view, count)
                h.copyBytes(start, start + count)
            case _: ByteView.Mapped =>
                val out = new Array[Byte](count)
                var i   = 0
                while i < count do
                    out(i) = view.readByte()
                    i += 1
                out
    end captureBytes

    private def skipBytes(view: ByteView, count: Int)(using AllowUnsafe): Unit =
        var i = 0
        while i < count do
            discard(view.readByte())
            i += 1
    end skipBytes

end ClassfileUnpickler
