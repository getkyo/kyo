package kyo.internal.reflect.classfile

import kyo.*
import kyo.internal.reflect.binary.ByteView
import kyo.internal.reflect.query.ClasspathRef
import kyo.internal.reflect.symbol.Flags as FlagsHelper
import kyo.internal.reflect.symbol.FqnCanonicalizer
import kyo.internal.reflect.symbol.Interner
import kyo.internal.reflect.symbol.Symbol as SymbolFactory
import kyo.internal.reflect.type_.TypeArena
import scala.collection.mutable

/** Result produced by ClassfileUnpickler for a single .class file.
  *
  * @param classSymbol
  *   The Reflect.Symbol for the class or interface defined by this file.
  * @param parents
  *   Unresolved parent types: super class (if any) followed by implemented interfaces. Phase 7 resolver replaces with real symbols.
  * @param innerClassTable
  *   Map from inner binary name to (outer binary name, simple inner name). Outer is "" for anonymous/local classes.
  * @param symbols
  *   All field and method symbols declared by the class.
  * @param arena
  *   The TypeArena passed in; included for Phase 7 merge.
  */
final case class ClassfileResult(
    classSymbol: Reflect.Symbol,
    parents: Chunk[Reflect.Type],
    innerClassTable: Map[String, (String, String)],
    symbols: Chunk[Reflect.Symbol],
    arena: TypeArena
)

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

    def read(
        bytes: Array[Byte],
        interner: Interner,
        arena: TypeArena,
        home: ClasspathRef
    )(using Frame): ClassfileResult < (Sync & Abort[ReflectError]) =
        val view = ByteView(bytes)
        val path = "<classfile>"
        readFrom(view, interner, arena, home, path)
    end read

    def readFrom(
        view: ByteView,
        interner: Interner,
        arena: TypeArena,
        home: ClasspathRef,
        path: String
    )(using Frame): ClassfileResult < (Sync & Abort[ReflectError]) =

        // Magic
        Sync.defer(readU4(view)).map: magic =>
            if magic != ClassfileFormat.Magic then
                Abort.fail(ReflectError.ClassfileFormatError(path, s"Invalid magic: 0x${magic.toHexString} (expected 0xcafebabe)"))
            else
                // Version
                Sync.defer {
                    val minor = readU2(view)
                    val major = readU2(view)
                    (minor, major)
                }.map: (minor, major) =>
                    if major < ClassfileFormat.MinMajorVersion ||
                        (major == ClassfileFormat.MinMajorVersion && minor < ClassfileFormat.MinMinorVersion)
                    then
                        Abort.fail(ReflectError.ClassfileFormatError(
                            path,
                            s"Unsupported classfile version $major.$minor (minimum ${ClassfileFormat.MinMajorVersion}.${ClassfileFormat.MinMinorVersion})"
                        ))
                    else
                        // Constant pool
                        ConstantPool.read(view, interner, path).map: pool =>
                            readBody(view, pool, interner, arena, home, path)

    private def readBody(
        view: ByteView,
        pool: ConstantPool,
        interner: Interner,
        arena: TypeArena,
        home: ClasspathRef,
        path: String
    )(using Frame): ClassfileResult < (Sync & Abort[ReflectError]) =
        Sync.defer {
            val accessFlags = readU2(view)
            val thisIdx     = readU2(view)
            val superIdx    = readU2(view)
            (accessFlags, thisIdx, superIdx)
        }.map: (accessFlags, thisIdx, superIdx) =>
            pool.classRef(thisIdx).map: thisBinaryName =>
                // module-info.class: skip entirely
                if thisBinaryName.endsWith("module-info") then
                    ClassfileResult(
                        makeUnresolvedSymbol(thisBinaryName, accessFlags, home),
                        Chunk.empty,
                        Map.empty,
                        Chunk.empty,
                        arena
                    )
                else
                    readClassBody(view, pool, interner, arena, home, path, accessFlags, thisBinaryName, superIdx)

    private def makeUnresolvedSymbol(binaryName: String, accessFlags: Int, home: ClasspathRef): Reflect.Symbol =
        val simpleName = binaryName.split("[./]").last
        SymbolFactory.makeSymbol(
            Reflect.SymbolKind.Unresolved,
            new Reflect.Flags(Reflect.Flag.JavaDefined.bit),
            Reflect.Name(simpleName),
            null,
            home,
            Reflect.Symbol.JavaOrigin
        )
    end makeUnresolvedSymbol

    private def unresolvedType(binaryName: String, home: ClasspathRef): Reflect.Type =
        val sym = SymbolFactory.makeSymbol(
            Reflect.SymbolKind.Unresolved,
            new Reflect.Flags(Reflect.Flag.JavaDefined.bit),
            Reflect.Name(binaryName.replace('/', '.')),
            null,
            home,
            Reflect.Symbol.JavaOrigin
        )
        Reflect.Type.Named(sym)
    end unresolvedType

    private def readClassBody(
        view: ByteView,
        pool: ConstantPool,
        interner: Interner,
        arena: TypeArena,
        home: ClasspathRef,
        path: String,
        accessFlags: Int,
        thisBinaryName: String,
        superIdx: Int
    )(using Frame): ClassfileResult < (Sync & Abort[ReflectError]) =
        // Read parent types: super class + interfaces
        resolveOptionalSuperType(pool, home, superIdx).map: superTypeOpt =>
            Sync.defer {
                val ifCount = readU2(view)
                val ifIdxs  = new Array[Int](ifCount)
                var i       = 0
                while i < ifCount do
                    ifIdxs(i) = readU2(view)
                    i += 1
                ifIdxs
            }.map: ifIdxs =>
                resolveInterfaceTypes(pool, home, path, ifIdxs, 0, Chunk.empty).map: ifTypes =>
                    val parents = superTypeOpt match
                        case Present(st) => ifTypes.prepended(st)
                        case Absent      => ifTypes
                    // Fields
                    readMemberInfos(view, pool, path, isMethods = false).map: fieldInfos =>
                        // Methods
                        readMemberInfos(view, pool, path, isMethods = true).map: methodInfos =>
                            // Class-level attributes
                            readClassAttributes(view, pool, path).map: classAttrs =>
                                buildResult(
                                    pool,
                                    interner,
                                    arena,
                                    home,
                                    path,
                                    accessFlags,
                                    thisBinaryName,
                                    parents,
                                    fieldInfos,
                                    methodInfos,
                                    classAttrs
                                )

    private def resolveOptionalSuperType(
        pool: ConstantPool,
        home: ClasspathRef,
        superIdx: Int
    )(using Frame): Maybe[Reflect.Type] < (Sync & Abort[ReflectError]) =
        if superIdx == 0 then Absent
        else pool.classRef(superIdx).map(bn => Present(unresolvedType(bn, home)))

    private def resolveInterfaceTypes(
        pool: ConstantPool,
        home: ClasspathRef,
        path: String,
        idxs: Array[Int],
        i: Int,
        acc: Chunk[Reflect.Type]
    )(using Frame): Chunk[Reflect.Type] < (Sync & Abort[ReflectError]) =
        if i >= idxs.length then acc
        else
            pool.classRef(idxs(i)).map: bn =>
                resolveInterfaceTypes(pool, home, path, idxs, i + 1, acc.appended(unresolvedType(bn, home)))

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
        invisibleAnnotationBytes: Maybe[Array[Byte]]
    )

    private def readMemberInfos(
        view: ByteView,
        pool: ConstantPool,
        path: String,
        isMethods: Boolean
    )(using Frame): Chunk[MemberInfo] < (Sync & Abort[ReflectError]) =
        Sync.defer(readU2(view)).map: count =>
            readMemberList(view, pool, path, count, Chunk.empty, isMethods)

    private def readMemberList(
        view: ByteView,
        pool: ConstantPool,
        path: String,
        remaining: Int,
        acc: Chunk[MemberInfo],
        isMethods: Boolean
    )(using Frame): Chunk[MemberInfo] < (Sync & Abort[ReflectError]) =
        if remaining == 0 then acc
        else
            readOneMemberInfo(view, pool, path, isMethods).map: info =>
                readMemberList(view, pool, path, remaining - 1, acc.appended(info), isMethods)

    private def readOneMemberInfo(
        view: ByteView,
        pool: ConstantPool,
        path: String,
        isMethods: Boolean
    )(using Frame): MemberInfo < (Sync & Abort[ReflectError]) =
        Sync.defer {
            val accessFlags = readU2(view)
            val nameIdx     = readU2(view)
            val descIdx     = readU2(view)
            val attrCount   = readU2(view)
            (accessFlags, nameIdx, descIdx, attrCount)
        }.map: (accessFlags, nameIdx, descIdx, attrCount) =>
            readMemberAttributes(
                view,
                pool,
                path,
                attrCount,
                Maybe.empty,
                Chunk.empty,
                Maybe.empty,
                Maybe.empty
            ).map: (sigIdx, exceptionIdxs, visAnn, invisAnn) =>
                MemberInfo(accessFlags, nameIdx, descIdx, sigIdx, exceptionIdxs, visAnn, invisAnn)

    private def readMemberAttributes(
        view: ByteView,
        pool: ConstantPool,
        path: String,
        remaining: Int,
        sigIdx: Maybe[Int],
        exceptionIdxs: Chunk[Int],
        visibleAnnBytes: Maybe[Array[Byte]],
        invisibleAnnBytes: Maybe[Array[Byte]]
    )(using Frame): (Maybe[Int], Chunk[Int], Maybe[Array[Byte]], Maybe[Array[Byte]]) < (Sync & Abort[ReflectError]) =
        if remaining == 0 then (sigIdx, exceptionIdxs, visibleAnnBytes, invisibleAnnBytes)
        else
            Sync.defer {
                val nameIdx = readU2(view)
                val attrLen = readU4(view)
                (nameIdx, attrLen)
            }.map: (nameIdx, attrLen) =>
                pool.utf8(nameIdx).map: attrName =>
                    attrName match
                        case ClassfileFormat.AttrSignature =>
                            val idx = Sync.defer(readU2(view))
                            idx.map: i =>
                                readMemberAttributes(
                                    view,
                                    pool,
                                    path,
                                    remaining - 1,
                                    Present(i),
                                    exceptionIdxs,
                                    visibleAnnBytes,
                                    invisibleAnnBytes
                                )

                        case ClassfileFormat.AttrExceptions =>
                            Sync.defer {
                                val count = readU2(view)
                                var idxs  = Chunk.empty[Int]
                                var k     = 0
                                while k < count do
                                    idxs = idxs.appended(readU2(view))
                                    k += 1
                                idxs
                            }.map: idxs =>
                                readMemberAttributes(
                                    view,
                                    pool,
                                    path,
                                    remaining - 1,
                                    sigIdx,
                                    idxs,
                                    visibleAnnBytes,
                                    invisibleAnnBytes
                                )

                        case ClassfileFormat.AttrRuntimeVisibleAnnotations =>
                            // Capture raw bytes for deferred annotation decoding
                            Sync.defer {
                                val bytes = captureBytes(view, attrLen)
                                bytes
                            }.map: bytes =>
                                readMemberAttributes(
                                    view,
                                    pool,
                                    path,
                                    remaining - 1,
                                    sigIdx,
                                    exceptionIdxs,
                                    Present(bytes),
                                    invisibleAnnBytes
                                )

                        case ClassfileFormat.AttrRuntimeInvisibleAnnotations =>
                            Sync.defer {
                                val bytes = captureBytes(view, attrLen)
                                bytes
                            }.map: bytes =>
                                readMemberAttributes(
                                    view,
                                    pool,
                                    path,
                                    remaining - 1,
                                    sigIdx,
                                    exceptionIdxs,
                                    visibleAnnBytes,
                                    Present(bytes)
                                )

                        case _ =>
                            Sync.defer(skipBytes(view, attrLen)).map: _ =>
                                readMemberAttributes(
                                    view,
                                    pool,
                                    path,
                                    remaining - 1,
                                    sigIdx,
                                    exceptionIdxs,
                                    visibleAnnBytes,
                                    invisibleAnnBytes
                                )

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
        invisibleAnnotationBytes: Maybe[Array[Byte]]
    )

    private def readClassAttributes(
        view: ByteView,
        pool: ConstantPool,
        path: String
    )(using Frame): ClassAttributes < (Sync & Abort[ReflectError]) =
        Sync.defer(readU2(view)).map: count =>
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
                Maybe.empty
            )

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
        invisibleAnnBytes: Maybe[Array[Byte]]
    )(using Frame): ClassAttributes < (Sync & Abort[ReflectError]) =
        if remaining == 0 then
            ClassAttributes(
                sigIdx,
                innerClasses,
                enclosingClassIdx,
                enclosingMethodIdx,
                hasRecord,
                recordComponents,
                visibleAnnBytes,
                invisibleAnnBytes
            )
        else
            Sync.defer {
                val nameIdx = readU2(view)
                val attrLen = readU4(view)
                (nameIdx, attrLen)
            }.map: (nameIdx, attrLen) =>
                pool.utf8(nameIdx).map: attrName =>
                    attrName match
                        case ClassfileFormat.AttrSignature =>
                            Sync.defer(readU2(view)).map: idx =>
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
                                    invisibleAnnBytes
                                )

                        case ClassfileFormat.AttrInnerClasses =>
                            Sync.defer {
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
                                arr
                            }.map: arr =>
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
                                    invisibleAnnBytes
                                )

                        case ClassfileFormat.AttrEnclosingMethod =>
                            Sync.defer {
                                val classIdx  = readU2(view)
                                val methodIdx = readU2(view)
                                (classIdx, methodIdx)
                            }.map: (classIdx, methodIdx) =>
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
                                    invisibleAnnBytes
                                )

                        case ClassfileFormat.AttrRecord =>
                            // Parse Record attribute: list of components with name, descriptor,
                            // and optional Signature sub-attribute.
                            readRecordComponents(view, pool, path).map: comps =>
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
                                    invisibleAnnBytes
                                )

                        case ClassfileFormat.AttrRuntimeVisibleAnnotations =>
                            Sync.defer {
                                val bytes = captureBytes(view, attrLen)
                                bytes
                            }.map: bytes =>
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
                                    invisibleAnnBytes
                                )

                        case ClassfileFormat.AttrRuntimeInvisibleAnnotations =>
                            Sync.defer {
                                val bytes = captureBytes(view, attrLen)
                                bytes
                            }.map: bytes =>
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
                                    Present(bytes)
                                )

                        case _ =>
                            Sync.defer(skipBytes(view, attrLen)).map: _ =>
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
                                    invisibleAnnBytes
                                )

    /** Parse Record attribute components.
      *
      * Each component has: nameIdx (u2), descriptorIdx (u2), and attrCount (u2) sub-attributes. We extract Signature sub-attribute index if
      * present; skip others.
      */
    private def readRecordComponents(
        view: ByteView,
        pool: ConstantPool,
        path: String
    )(using Frame): Chunk[(Int, Int, Maybe[Int])] < (Sync & Abort[ReflectError]) =
        Sync.defer(readU2(view)).map: numComponents =>
            readRecordComponentList(view, pool, path, numComponents, 0, Chunk.empty)

    private def readRecordComponentList(
        view: ByteView,
        pool: ConstantPool,
        path: String,
        total: Int,
        idx: Int,
        acc: Chunk[(Int, Int, Maybe[Int])]
    )(using Frame): Chunk[(Int, Int, Maybe[Int])] < (Sync & Abort[ReflectError]) =
        if idx >= total then acc
        else
            Sync.defer {
                val compNameIdx = readU2(view)
                val compDescIdx = readU2(view)
                val compAttrCnt = readU2(view)
                (compNameIdx, compDescIdx, compAttrCnt)
            }.map: (compNameIdx, compDescIdx, compAttrCnt) =>
                readRecordComponentAttributes(view, pool, path, compAttrCnt, Maybe.empty).map: sigIdx =>
                    readRecordComponentList(
                        view,
                        pool,
                        path,
                        total,
                        idx + 1,
                        acc.appended((compNameIdx, compDescIdx, sigIdx))
                    )

    private def readRecordComponentAttributes(
        view: ByteView,
        pool: ConstantPool,
        path: String,
        remaining: Int,
        sigIdx: Maybe[Int]
    )(using Frame): Maybe[Int] < (Sync & Abort[ReflectError]) =
        if remaining == 0 then sigIdx
        else
            Sync.defer {
                val attrNameIdx = readU2(view)
                val attrLen     = readU4(view)
                (attrNameIdx, attrLen)
            }.map: (attrNameIdx, attrLen) =>
                pool.utf8(attrNameIdx).map: attrName =>
                    attrName match
                        case ClassfileFormat.AttrSignature =>
                            Sync.defer(readU2(view)).map: idx =>
                                readRecordComponentAttributes(view, pool, path, remaining - 1, Present(idx))
                        case _ =>
                            Sync.defer(skipBytes(view, attrLen)).map: _ =>
                                readRecordComponentAttributes(view, pool, path, remaining - 1, sigIdx)

    // -------------------------------------------------------------------------
    // Symbol construction
    // -------------------------------------------------------------------------

    private def buildResult(
        pool: ConstantPool,
        interner: Interner,
        arena: TypeArena,
        home: ClasspathRef,
        path: String,
        accessFlags: Int,
        thisBinaryName: String,
        parents: Chunk[Reflect.Type],
        fieldInfos: Chunk[MemberInfo],
        methodInfos: Chunk[MemberInfo],
        classAttrs: ClassAttributes
    )(using Frame): ClassfileResult < (Sync & Abort[ReflectError]) =

        val isInterface  = (accessFlags & ClassfileFormat.ACC_INTERFACE) != 0
        val isAnnotation = (accessFlags & ClassfileFormat.ACC_ANNOTATION) != 0
        val isRecord     = classAttrs.hasRecord

        val kind =
            if isInterface || isAnnotation then Reflect.SymbolKind.Trait
            else Reflect.SymbolKind.Class

        val baseFlags   = FlagsHelper.fromJvmAccessFlags(accessFlags)
        val javaDefined = new Reflect.Flags(Reflect.Flag.JavaDefined.bit)
        val recordFlag  = if isRecord then new Reflect.Flags(Reflect.Flag.JavaRecord.bit) else Reflect.Flags.empty
        val classFlags  = baseFlags | javaDefined | recordFlag

        // Build inner class table and FQN canonicalization.
        // Must be done before constructing the class symbol so we can set the correct owner.
        buildInnerClassTable(pool, path, classAttrs.innerClasses).map: innerTable =>
            // Determine the symbol's name and owner from the inner class table.
            // For inner classes, use the simple inner name; set owner to an unresolved outer symbol.
            // For top-level classes, use the simple class name; owner = null.
            val (symName, symOwner) = resolveNameAndOwner(thisBinaryName, innerTable, home)

            // Build enclosingMethod from EnclosingMethod attribute
            buildEnclosingMethod(pool, path, classAttrs.enclosingClassIdx, classAttrs.enclosingMethodIdx, innerTable, home).map:
                enclosingMethodMaybe =>
                    // Build record components if this is a record class
                    buildRecordComponents(pool, interner, home, path, classAttrs.recordComponents, isRecord).map: recordComps =>
                        // Decode class-level annotations
                        decodeAnnotations(pool, interner, home, classAttrs.visibleAnnotationBytes, classAttrs.invisibleAnnotationBytes).map:
                            classAnnotations =>
                                val classMetadata = Reflect.JavaMetadata(
                                    throwsTypes = Chunk.empty,
                                    annotations = classAnnotations,
                                    enclosingMethod = enclosingMethodMaybe,
                                    accessFlags = accessFlags,
                                    recordComponents = recordComps
                                )

                                val classSym = SymbolFactory.makeSymbol(
                                    kind,
                                    classFlags,
                                    Reflect.Name(symName),
                                    symOwner,
                                    home,
                                    Reflect.Symbol.JavaOrigin,
                                    Present(classMetadata)
                                )

                                // Build member symbols
                                buildMemberSymbols(pool, interner, home, path, classSym, fieldInfos, isMethods = false).map: fieldSyms =>
                                    buildMemberSymbols(
                                        pool,
                                        interner,
                                        home,
                                        path,
                                        classSym,
                                        methodInfos,
                                        isMethods = true
                                    ).map: methodSyms =>
                                        val allSymbols = fieldSyms ++ methodSyms
                                        ClassfileResult(classSym, parents, innerTable, allSymbols, arena)
    end buildResult

    /** Resolve the symbol name and owner symbol for a class, using the inner class table.
      *
      * For inner classes: uses the simple inner name from the table and creates an unresolved outer symbol as the owner. For
      * anonymous/local classes: uses the '$'-form name, owner = null. For top-level classes: uses the simple class name (last segment after
      * '/'), owner = null.
      */
    private def resolveNameAndOwner(
        thisBinaryName: String,
        innerTable: Map[String, (String, String)],
        home: ClasspathRef
    ): (String, Reflect.Symbol) =
        innerTable.get(thisBinaryName) match
            case Some((outerBinaryName, innerSimpleName))
                if outerBinaryName.nonEmpty && innerSimpleName.nonEmpty =>
                // Named inner class: use innerSimpleName; create an unresolved outer symbol
                val outerSym = buildPackageOwnerChain(outerBinaryName, innerTable, home)
                (innerSimpleName, outerSym)
            case Some(_) =>
                // Anonymous, local, or partially-resolved: use '$'-form name, owner = null
                (thisBinaryName.replace('/', '.'), null)
            case None =>
                // Top-level class: parse the package prefix and class name.
                // The class name is the last segment (after the last '/').
                // The package prefix (segments before the last '/') becomes the owner chain.
                val segments  = thisBinaryName.split("/")
                val className = segments.last
                val pkgOwner  = buildPackageSymbol(segments.dropRight(1), home)
                (className, pkgOwner)

    /** Build an owner symbol for an outer binary name. Creates a chain of package/class owner symbols so that computeFullName and
      * computeBinaryName produce correct results.
      *
      * For a top-level outer class "java/util/Map": creates a package symbol for "java.util" as owner, then a class symbol for "Map" as the
      * returned symbol.
      */
    private def buildPackageOwnerChain(
        outerBinaryName: String,
        innerTable: Map[String, (String, String)],
        home: ClasspathRef
    ): Reflect.Symbol =
        // Check if the outer is itself an inner class
        innerTable.get(outerBinaryName) match
            case Some((outerOuterBinaryName, outerSimpleName))
                if outerOuterBinaryName.nonEmpty && outerSimpleName.nonEmpty =>
                // Nested inner class: recurse
                val grandParent = buildPackageOwnerChain(outerOuterBinaryName, innerTable, home)
                SymbolFactory.makeSymbol(
                    Reflect.SymbolKind.Class,
                    new Reflect.Flags(Reflect.Flag.JavaDefined.bit),
                    Reflect.Name(outerSimpleName),
                    grandParent,
                    home,
                    Reflect.Symbol.JavaOrigin
                )
            case _ =>
                // Top-level outer class: parse the package prefix and class name
                val segments  = outerBinaryName.split("/")
                val className = segments.last
                val pkgSymbol = buildPackageSymbol(segments.dropRight(1), home)
                SymbolFactory.makeSymbol(
                    Reflect.SymbolKind.Class,
                    new Reflect.Flags(Reflect.Flag.JavaDefined.bit),
                    Reflect.Name(className),
                    pkgSymbol,
                    home,
                    Reflect.Symbol.JavaOrigin
                )

    /** Build a chain of Package symbols for a package path (e.g., Array("java", "util")). Returns the innermost package symbol. If segments
      * is empty, returns null.
      */
    private def buildPackageSymbol(
        segments: Array[String],
        home: ClasspathRef
    ): Reflect.Symbol =
        if segments.isEmpty then null
        else
            var cur: Reflect.Symbol = null
            var i                   = 0
            while i < segments.length do
                cur = SymbolFactory.makeSymbol(
                    Reflect.SymbolKind.Package,
                    new Reflect.Flags(Reflect.Flag.JavaDefined.bit),
                    Reflect.Name(segments(i)),
                    cur,
                    home,
                    Reflect.Symbol.JavaOrigin
                )
                i += 1
            end while
            cur

    private def buildEnclosingMethod(
        pool: ConstantPool,
        path: String,
        enclosingClassIdx: Maybe[Int],
        enclosingMethodIdx: Maybe[Int],
        innerTable: Map[String, (String, String)],
        home: ClasspathRef
    )(using Frame): Maybe[(Reflect.Symbol, Reflect.Name)] < (Sync & Abort[ReflectError]) =
        enclosingClassIdx match
            case Absent => Absent
            case Present(classIdx) =>
                pool.classRef(classIdx).map: enclosingBinaryName =>
                    val enclosingFqn   = FqnCanonicalizer.toFullName(enclosingBinaryName, innerTable)
                    val enclosingName  = enclosingFqn.split("\\.").last
                    val enclosingOwner = buildPackageOwnerChain(enclosingBinaryName, innerTable, home)
                    val enclosingClassSym = SymbolFactory.makeSymbol(
                        Reflect.SymbolKind.Unresolved,
                        new Reflect.Flags(Reflect.Flag.JavaDefined.bit),
                        Reflect.Name(enclosingName),
                        enclosingOwner,
                        home,
                        Reflect.Symbol.JavaOrigin
                    )
                    enclosingMethodIdx match
                        case Absent => Present((enclosingClassSym, Reflect.Name("")))
                        case Present(methodIdx) =>
                            pool.nameAndType(methodIdx).map: (methodName, _) =>
                                Present((enclosingClassSym, Reflect.Name(methodName)))
                    end match

    private def buildRecordComponents(
        pool: ConstantPool,
        interner: Interner,
        home: ClasspathRef,
        path: String,
        components: Chunk[(Int, Int, Maybe[Int])],
        isRecord: Boolean
    )(using Frame): Chunk[(Reflect.Name, Reflect.Type)] < (Sync & Abort[ReflectError]) =
        if !isRecord || components.isEmpty then Chunk.empty
        else buildRecordComponentList(pool, interner, home, path, components, 0, Chunk.empty)

    private def buildRecordComponentList(
        pool: ConstantPool,
        interner: Interner,
        home: ClasspathRef,
        path: String,
        components: Chunk[(Int, Int, Maybe[Int])],
        idx: Int,
        acc: Chunk[(Reflect.Name, Reflect.Type)]
    )(using Frame): Chunk[(Reflect.Name, Reflect.Type)] < (Sync & Abort[ReflectError]) =
        if idx >= components.length then acc
        else
            val (nameIdx, descIdx, sigIdx) = components(idx)
            pool.utf8(nameIdx).map: compName =>
                pool.utf8(descIdx).map: descriptor =>
                    resolveComponentType(pool, interner, home, path, descriptor, sigIdx).map: compType =>
                        val name = Reflect.Name(compName)
                        buildRecordComponentList(pool, interner, home, path, components, idx + 1, acc.appended((name, compType)))

    private def resolveComponentType(
        pool: ConstantPool,
        interner: Interner,
        home: ClasspathRef,
        path: String,
        descriptor: String,
        sigIdx: Maybe[Int]
    )(using Frame): Reflect.Type < (Sync & Abort[ReflectError]) =
        sigIdx match
            case Present(idx) =>
                pool.utf8(idx).map: sig =>
                    Abort.run(JavaSignatures.parseFieldSignature(sig, interner)).map:
                        case Result.Success(tpe) => tpe
                        case Result.Failure(_)   => parseErasedDescriptorType(descriptor, home)
                        case Result.Panic(t)     => Abort.panic(t)
            case Absent =>
                parseErasedDescriptorType(descriptor, home)

    /** Parse a JVM field descriptor (erased type) into a Reflect.Type.
      *
      * Handles: B/C/D/F/I/J/S/Z (primitives), V (void), [X (array), Lfoo/bar/Baz; (class reference).
      */
    private def parseErasedDescriptorType(descriptor: String, home: ClasspathRef): Reflect.Type =
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
                Reflect.Type.Array(parseErasedDescriptorType(s.substring(1), home))
            case s if s.startsWith("L") && s.endsWith(";") =>
                val binaryName = s.substring(1, s.length - 1)
                unresolvedType(binaryName, home)
            case other =>
                unresolvedType(other, home)

    private def primType(fqn: String): Reflect.Type =
        val sym = SymbolFactory.makeSymbol(
            Reflect.SymbolKind.Class,
            new Reflect.Flags(Reflect.Flag.JavaDefined.bit),
            Reflect.Name(fqn.split("\\.").last),
            null,
            new ClasspathRef,
            Reflect.Symbol.JavaOrigin
        )
        Reflect.Type.Named(sym)
    end primType

    private def decodeAnnotations(
        pool: ConstantPool,
        interner: Interner,
        home: ClasspathRef,
        visibleBytes: Maybe[Array[Byte]],
        invisibleBytes: Maybe[Array[Byte]]
    )(using Frame): Chunk[Reflect.JavaAnnotation] < (Sync & Abort[ReflectError]) =
        val visibleEffect: Chunk[Reflect.JavaAnnotation] < (Sync & Abort[ReflectError]) =
            visibleBytes match
                case Absent => Chunk.empty
                case Present(bytes) =>
                    val annView = ByteView(bytes)
                    JavaAnnotationUnpickler.readAnnotations(annView, pool, interner, home)

        visibleEffect.map: visible =>
            val invisibleEffect: Chunk[Reflect.JavaAnnotation] < (Sync & Abort[ReflectError]) =
                invisibleBytes match
                    case Absent => Chunk.empty
                    case Present(bytes) =>
                        val annView = ByteView(bytes)
                        JavaAnnotationUnpickler.readAnnotations(annView, pool, interner, home)

            invisibleEffect.map: invisible =>
                visible ++ invisible
    end decodeAnnotations

    private def buildMemberSymbols(
        pool: ConstantPool,
        interner: Interner,
        home: ClasspathRef,
        path: String,
        owner: Reflect.Symbol,
        infos: Chunk[MemberInfo],
        isMethods: Boolean
    )(using Frame): Chunk[Reflect.Symbol] < (Sync & Abort[ReflectError]) =
        buildMemberList(pool, interner, home, path, owner, infos, 0, Chunk.empty, isMethods)

    private def buildMemberList(
        pool: ConstantPool,
        interner: Interner,
        home: ClasspathRef,
        path: String,
        owner: Reflect.Symbol,
        infos: Chunk[MemberInfo],
        idx: Int,
        acc: Chunk[Reflect.Symbol],
        isMethods: Boolean
    )(using Frame): Chunk[Reflect.Symbol] < (Sync & Abort[ReflectError]) =
        if idx >= infos.length then acc
        else
            buildOneMemberSymbol(pool, interner, home, path, owner, infos(idx), isMethods).map: sym =>
                buildMemberList(pool, interner, home, path, owner, infos, idx + 1, acc.appended(sym), isMethods)

    private def buildOneMemberSymbol(
        pool: ConstantPool,
        interner: Interner,
        home: ClasspathRef,
        path: String,
        owner: Reflect.Symbol,
        info: MemberInfo,
        isMethod: Boolean
    )(using Frame): Reflect.Symbol < (Sync & Abort[ReflectError]) =
        pool.utf8(info.nameIdx).map: memberName =>
            val accessFlags = info.accessFlags
            val isStatic    = (accessFlags & ClassfileFormat.ACC_STATIC) != 0
            val isFinal     = (accessFlags & ClassfileFormat.ACC_FINAL) != 0

            val kind =
                if isMethod then Reflect.SymbolKind.Method
                else if isStatic then Reflect.SymbolKind.Field
                else if isFinal then Reflect.SymbolKind.Val
                else Reflect.SymbolKind.Var

            val baseFlags   = FlagsHelper.fromJvmAccessFlags(accessFlags)
            val javaDefined = new Reflect.Flags(Reflect.Flag.JavaDefined.bit)
            val memberFlags = baseFlags | javaDefined

            // Resolve throws types for JavaMetadata
            resolveThrowsTypes(pool, path, info.exceptionIdxs).map: throwsTypes =>
                decodeAnnotations(pool, interner, home, info.visibleAnnotationBytes, info.invisibleAnnotationBytes).map:
                    memberAnnotations =>
                        val metadata = Reflect.JavaMetadata(
                            throwsTypes = throwsTypes,
                            annotations = memberAnnotations,
                            enclosingMethod = Absent,
                            accessFlags = accessFlags,
                            recordComponents = Chunk.empty
                        )

                        val nameBytes = memberName.getBytes(java.nio.charset.StandardCharsets.UTF_8)
                        val nameEntry = interner.intern(nameBytes, 0, nameBytes.length)
                        SymbolFactory.makeSymbol(
                            kind,
                            memberFlags,
                            Reflect.Name.wrap(nameEntry),
                            owner,
                            home,
                            Reflect.Symbol.JavaOrigin,
                            Present(metadata)
                        )

    private def resolveThrowsTypes(
        pool: ConstantPool,
        path: String,
        exceptionIdxs: Chunk[Int]
    )(using Frame): Chunk[Reflect.Type] < (Sync & Abort[ReflectError]) =
        resolveThrowsList(pool, path, exceptionIdxs, 0, Chunk.empty)

    private def resolveThrowsList(
        pool: ConstantPool,
        path: String,
        idxs: Chunk[Int],
        i: Int,
        acc: Chunk[Reflect.Type]
    )(using Frame): Chunk[Reflect.Type] < (Sync & Abort[ReflectError]) =
        if i >= idxs.length then acc
        else
            pool.classRef(idxs(i)).map: binaryName =>
                val exSym = SymbolFactory.makeSymbol(
                    Reflect.SymbolKind.Unresolved,
                    new Reflect.Flags(Reflect.Flag.JavaDefined.bit),
                    Reflect.Name(binaryName.replace('/', '.')),
                    null,
                    new ClasspathRef,
                    Reflect.Symbol.JavaOrigin
                )
                resolveThrowsList(pool, path, idxs, i + 1, acc.appended(Reflect.Type.Named(exSym)))

    private def buildInnerClassTable(
        pool: ConstantPool,
        path: String,
        innerClasses: Chunk[(Int, Int, Int, Int)]
    )(using Frame): Map[String, (String, String)] < (Sync & Abort[ReflectError]) =
        buildInnerClassList(pool, path, innerClasses, 0, Map.empty)

    private def buildInnerClassList(
        pool: ConstantPool,
        path: String,
        innerClasses: Chunk[(Int, Int, Int, Int)],
        i: Int,
        acc: Map[String, (String, String)]
    )(using Frame): Map[String, (String, String)] < (Sync & Abort[ReflectError]) =
        if i >= innerClasses.length then acc
        else
            val (innerIdx, outerIdx, nameIdx, _) = innerClasses(i)
            if innerIdx == 0 then
                buildInnerClassList(pool, path, innerClasses, i + 1, acc)
            else
                pool.classRef(innerIdx).map: innerBN =>
                    resolveOptionalClassRef(pool, path, outerIdx).map: outerBN =>
                        resolveOptionalUtf8(pool, path, nameIdx).map: simpleName =>
                            buildInnerClassList(pool, path, innerClasses, i + 1, acc + (innerBN -> (outerBN, simpleName)))
            end if

    private def resolveOptionalClassRef(
        pool: ConstantPool,
        path: String,
        idx: Int
    )(using Frame): String < (Sync & Abort[ReflectError]) =
        if idx == 0 then ""
        else pool.classRef(idx)

    private def resolveOptionalUtf8(
        pool: ConstantPool,
        path: String,
        idx: Int
    )(using Frame): String < (Sync & Abort[ReflectError]) =
        if idx == 0 then ""
        else pool.utf8(idx)

    // -------------------------------------------------------------------------
    // Byte helpers
    // -------------------------------------------------------------------------

    /** Capture `count` bytes from `view` into a fresh Array[Byte]. */
    private def captureBytes(view: ByteView, count: Int): Array[Byte] =
        view match
            case h: ByteView.Heap =>
                val start = h.position
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

    private def skipBytes(view: ByteView, count: Int): Unit =
        var i = 0
        while i < count do
            discard(view.readByte())
            i += 1
    end skipBytes

end ClassfileUnpickler
