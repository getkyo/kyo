package kyo.internal.reflect.classfile

import kyo.*
import kyo.internal.reflect.binary.ByteView
import kyo.internal.reflect.query.ClasspathRef
import kyo.internal.reflect.symbol.Flags as FlagsHelper
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

    /** Raw parsed data from a field_info or method_info entry. */
    final private case class MemberInfo(
        accessFlags: Int,
        nameIdx: Int,
        descriptorIdx: Int,
        signatureIdx: Maybe[Int],
        exceptionIdxs: Chunk[Int],
        hasRuntimeVisibleAnnotations: Boolean
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
            readMemberAttributes(view, pool, path, attrCount, Maybe.empty, Chunk.empty, hasRVA = false).map:
                (sigIdx, exceptionIdxs, hasRVA) =>
                    MemberInfo(accessFlags, nameIdx, descIdx, sigIdx, exceptionIdxs, hasRVA)

    private def readMemberAttributes(
        view: ByteView,
        pool: ConstantPool,
        path: String,
        remaining: Int,
        sigIdx: Maybe[Int],
        exceptionIdxs: Chunk[Int],
        hasRVA: Boolean
    )(using Frame): (Maybe[Int], Chunk[Int], Boolean) < (Sync & Abort[ReflectError]) =
        if remaining == 0 then (sigIdx, exceptionIdxs, hasRVA)
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
                                readMemberAttributes(view, pool, path, remaining - 1, Present(i), exceptionIdxs, hasRVA)

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
                                readMemberAttributes(view, pool, path, remaining - 1, sigIdx, idxs, hasRVA)

                        case ClassfileFormat.AttrRuntimeVisibleAnnotations =>
                            Sync.defer(skipBytes(view, attrLen)).map: _ =>
                                readMemberAttributes(view, pool, path, remaining - 1, sigIdx, exceptionIdxs, hasRVA = true)

                        case _ =>
                            Sync.defer(skipBytes(view, attrLen)).map: _ =>
                                readMemberAttributes(view, pool, path, remaining - 1, sigIdx, exceptionIdxs, hasRVA)

    // -------------------------------------------------------------------------
    // Class-level attribute reading
    // -------------------------------------------------------------------------

    final private case class ClassAttributes(
        signatureIdx: Maybe[Int],
        innerClasses: Chunk[(Int, Int, Int, Int)],
        enclosingClassIdx: Maybe[Int],
        enclosingMethodIdx: Maybe[Int],
        hasRecord: Boolean,
        recordComponents: Chunk[(Int, Int, Maybe[Int])]
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
                Chunk.empty
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
        recordComponents: Chunk[(Int, Int, Maybe[Int])]
    )(using Frame): ClassAttributes < (Sync & Abort[ReflectError]) =
        if remaining == 0 then
            ClassAttributes(sigIdx, innerClasses, enclosingClassIdx, enclosingMethodIdx, hasRecord, recordComponents)
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
                                    recordComponents
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
                                    recordComponents
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
                                    recordComponents
                                )

                        case ClassfileFormat.AttrRecord =>
                            Sync.defer {
                                val numComponents = readU2(view)
                                var comps         = Chunk.empty[(Int, Int, Maybe[Int])]
                                var k             = 0
                                while k < numComponents do
                                    val compNameIdx = readU2(view)
                                    val compDescIdx = readU2(view)
                                    val compAttrCnt = readU2(view)
                                    var compSigIdx  = Maybe.empty[Int]
                                    var j           = 0
                                    while j < compAttrCnt do
                                        val cAttrNameIdx = readU2(view)
                                        val cAttrLen     = readU4(view)
                                        // We don't have pool access in Sync.defer here,
                                        // store nameIdx for later resolution.
                                        // For simplicity: skip all component attributes inline.
                                        // Signature attribute index = 1 (tag = CONSTANT_Utf8 at typical small class)
                                        // We will read cAttrLen bytes and ignore.
                                        skipBytes(view, cAttrLen)
                                        j += 1
                                    end while
                                    comps = comps.appended((compNameIdx, compDescIdx, compSigIdx))
                                    k += 1
                                end while
                                comps
                            }.map: comps =>
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
                                    comps
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
                                    recordComponents
                                )

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

        val simpleName   = thisBinaryName.split("[./]").last
        val isInterface  = (accessFlags & ClassfileFormat.ACC_INTERFACE) != 0
        val isAnnotation = (accessFlags & ClassfileFormat.ACC_ANNOTATION) != 0
        val isEnum       = (accessFlags & ClassfileFormat.ACC_ENUM) != 0
        val isRecord     = classAttrs.hasRecord

        val kind =
            if isInterface || isAnnotation then Reflect.SymbolKind.Trait
            else Reflect.SymbolKind.Class

        val baseFlags   = FlagsHelper.fromJvmAccessFlags(accessFlags)
        val javaDefined = new Reflect.Flags(Reflect.Flag.JavaDefined.bit)
        val traitFlag   = if isInterface || isAnnotation then new Reflect.Flags(Reflect.Flag.Trait.bit) else Reflect.Flags.empty
        val recordFlag  = if isRecord then new Reflect.Flags(Reflect.Flag.JavaRecord.bit) else Reflect.Flags.empty
        val classFlags  = baseFlags | javaDefined | traitFlag | recordFlag
        val classMetadata = Reflect.JavaMetadata(
            throwsTypes = Chunk.empty,
            annotations = Chunk.empty,
            enclosingMethod = Absent,
            accessFlags = accessFlags,
            recordComponents = Chunk.empty
        )

        val classSym = SymbolFactory.makeSymbol(
            kind,
            classFlags,
            Reflect.Name(simpleName),
            null,
            home,
            Reflect.Symbol.JavaOrigin,
            Present(classMetadata)
        )

        // Build member symbols
        buildMemberSymbols(pool, interner, home, path, classSym, fieldInfos, isMethods = false).map: fieldSyms =>
            buildMemberSymbols(pool, interner, home, path, classSym, methodInfos, isMethods = true).map: methodSyms =>
                val allSymbols = fieldSyms ++ methodSyms
                buildInnerClassTable(pool, path, classAttrs.innerClasses).map: innerTable =>
                    ClassfileResult(classSym, parents, innerTable, allSymbols, arena)
    end buildResult

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
                val metadata = Reflect.JavaMetadata(
                    throwsTypes = throwsTypes,
                    annotations = Chunk.empty,
                    enclosingMethod = Absent,
                    accessFlags = accessFlags,
                    recordComponents = Chunk.empty
                )

                val nameEntry = interner.intern(
                    memberName.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    0,
                    memberName.getBytes(java.nio.charset.StandardCharsets.UTF_8).length
                )
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
                val sym = JavaSignatures.nothingSym // placeholder: real sym from binaryName
                import kyo.internal.reflect.symbol.Symbol as SymbolFactory
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
    // Byte skip helper
    // -------------------------------------------------------------------------

    private def skipBytes(view: ByteView, count: Int): Unit =
        var i = 0
        while i < count do
            discard(view.readByte())
            i += 1
    end skipBytes

end ClassfileUnpickler
