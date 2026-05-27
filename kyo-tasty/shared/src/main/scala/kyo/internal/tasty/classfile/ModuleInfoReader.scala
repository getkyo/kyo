package kyo.internal.tasty.classfile

import kyo.*
import kyo.internal.tasty.binary.ByteView
import kyo.internal.tasty.symbol.Interner

/** Reads a JVM 9+ module-info.class file and produces a Tasty.ModuleDescriptor.
  *
  * A module-info.class is a standard JVM classfile where: - The class access_flags includes ACC_MODULE (0x8000). - The class has a single
  * "Module" attribute (JVMS §4.7.25) containing the module declaration. - The class may have a "ModuleMainClass" and "ModulePackages"
  * attribute (not read here; not part of the public API).
  *
  * This reader is cross-platform: no JVM I/O; all state is in Array[Byte] + ByteView.
  *
  * Reference: JVMS §4.7.25 (Java SE 9+).
  */
object ModuleInfoReader:

    import ConstantPool.readU1
    import ConstantPool.readU2
    import ConstantPool.readU4

    /** Module access flags as defined in JVMS §4.7.25. */
    private val ACC_TRANSITIVE: Int   = 0x0020
    private val ACC_STATIC_PHASE: Int = 0x0040

    /** Read a module-info.class from raw bytes.
      *
      * Returns a Tasty.ModuleDescriptor on success. Fails with TastyError.ClassfileFormatError if: - The magic number is not 0xCAFEBABE. -
      * The major version is less than 53 (Java 9). - The Module attribute is missing or malformed. - Any required constant pool index is
      * out of range.
      *
      * The `interner` is used to intern module name strings from the constant pool for efficient deduplication. Callers may pass a fresh
      * `Interner` or share one across multiple reads.
      */
    def read(bytes: Array[Byte])(using Frame): Tasty.ModuleDescriptor < (Sync & Abort[TastyError]) =
        val view     = ByteView(bytes)
        val path     = "<module-info.class>"
        val interner = new Interner(numShards = 16, initialShardCapacity = 16)
        readFrom(view, interner, path)
    end read

    /** Read from an existing ByteView with a given interner and path label. For internal use by tests and ClasspathOrchestrator. */
    private[classfile] def readFrom(
        view: ByteView,
        interner: Interner,
        path: String
    )(using Frame): Tasty.ModuleDescriptor < (Sync & Abort[TastyError]) =
        checkHeader(view, path).flatMap: _ =>
            ConstantPool.read(view, interner, path).flatMap: pool =>
                skipClassStructure(view)
                val attrCount = readU2(view)
                readModuleAttribute(view, pool, path, attrCount)
    end readFrom

    /** Validate the classfile magic number and minimum major version. */
    private def checkHeader(
        view: ByteView,
        path: String
    )(using Frame): Unit < (Sync & Abort[TastyError]) =
        val magic = readU4(view)
        if magic != ClassfileFormat.Magic then
            Abort.fail(TastyError.ClassfileFormatError(
                path,
                s"Bad magic: expected 0xCAFEBABE, got 0x${magic.toHexString}"
            ))
        else
            val minorVersion = readU2(view)
            val majorVersion = readU2(view)
            if majorVersion < 53 then
                Abort.fail(TastyError.ClassfileFormatError(
                    path,
                    s"module-info.class requires major version >= 53 (Java 9+), got $majorVersion.$minorVersion"
                ))
            else
                Kyo.unit
            end if
        end if
    end checkHeader

    /** Skip access_flags, this_class, super_class, interfaces, fields, and methods (all empty in module-info.class).
      *
      * Advances the view cursor to just before the class-level attributes_count field.
      */
    private def skipClassStructure(view: ByteView): Unit =
        // Skip access_flags (u2), this_class (u2), super_class (u2)
        discard(readU2(view))
        discard(readU2(view))
        discard(readU2(view))
        // interfaces_count followed by interfaces
        val ifaceCount = readU2(view)
        var i          = 0
        while i < ifaceCount do
            discard(readU2(view))
            i += 1
        // fields_count followed by fields
        val fieldCount = readU2(view)
        i = 0
        while i < fieldCount do
            skipMember(view)
            i += 1
        // methods_count followed by methods
        val methodCount = readU2(view)
        i = 0
        while i < methodCount do
            skipMember(view)
            i += 1
    end skipClassStructure

    /** Skip a field_info or method_info (access_flags u2, name u2, desc u2, attributes_count u2, then attributes). */
    private def skipMember(view: ByteView): Unit =
        discard(readU2(view)) // access_flags
        discard(readU2(view)) // name_index
        discard(readU2(view)) // descriptor_index
        val attrCount = readU2(view)
        var i         = 0
        while i < attrCount do
            skipAttribute(view)
            i += 1
        end while
    end skipMember

    /** Skip one attribute_info: name_index (u2), attribute_length (u4), then skip attribute_length bytes. */
    private def skipAttribute(view: ByteView): Unit =
        discard(readU2(view)) // attribute_name_index
        val len = readU4(view)
        var i   = 0
        while i < len do
            discard(view.readByte())
            i += 1
        end while
    end skipAttribute

    /** Scan `attrCount` class-level attributes and decode the "Module" attribute when found. */
    private def readModuleAttribute(
        view: ByteView,
        pool: ConstantPool,
        path: String,
        attrCount: Int
    )(using Frame): Tasty.ModuleDescriptor < (Sync & Abort[TastyError]) =
        // We need to iterate over attributes and, for "Module", call decodeModuleAttribute.
        // Because this is an effectful loop, we convert to a Kyo-effect-based approach:
        // read attribute names one by one; when we hit "Module", decode it and return.
        // Use a mutable cursor approach: read each attribute name index, check via pool.utf8, skip if not Module.
        // We collect the found module descriptor as a Maybe, then fail if not found.
        readAttributesForModule(view, pool, path, attrCount, 0, Maybe.Absent)

    /** Recursive helper: iterate over attributes, looking for "Module". Returns the decoded descriptor once found. */
    private def readAttributesForModule(
        view: ByteView,
        pool: ConstantPool,
        path: String,
        total: Int,
        idx: Int,
        found: Maybe[Tasty.ModuleDescriptor]
    )(using Frame): Tasty.ModuleDescriptor < (Sync & Abort[TastyError]) =
        if idx >= total then
            found match
                case Present(desc) => desc
                case Absent =>
                    Abort.fail(TastyError.ClassfileFormatError(path, "No Module attribute found in module-info.class"))
        else
            val nameIdx = readU2(view)
            val attrLen = readU4(view)
            found match
                case Present(_) =>
                    // Already found Module attribute; skip this attribute
                    var i = 0
                    while i < attrLen do
                        discard(view.readByte())
                        i += 1
                    readAttributesForModule(view, pool, path, total, idx + 1, found)
                case Absent =>
                    pool.utf8(nameIdx).flatMap: attrName =>
                        if attrName == "Module" then
                            decodeModuleAttribute(view, pool, path).flatMap: desc =>
                                readAttributesForModule(view, pool, path, total, idx + 1, Maybe(desc))
                        else
                            // Skip this attribute
                            var i = 0
                            while i < attrLen do
                                discard(view.readByte())
                                i += 1
                            readAttributesForModule(view, pool, path, total, idx + 1, Absent)
                        end if
            end match
    end readAttributesForModule

    /** Decode the Module attribute payload (JVMS §4.7.25).
      *
      * Precondition: `view` is positioned just after the attribute_length field for a Module attribute.
      */
    private def decodeModuleAttribute(
        view: ByteView,
        pool: ConstantPool,
        path: String
    )(using Frame): Tasty.ModuleDescriptor < (Sync & Abort[TastyError]) =
        // module_name_index: CONSTANT_Module -> points to CONSTANT_Utf8 with module name (e.g., "java.base")
        val moduleNameIdx = readU2(view)
        val moduleFlags   = readU2(view)
        val moduleVerIdx  = readU2(view)
        val requiresCount = readU2(view)

        resolveModuleName(pool, moduleNameIdx, path).flatMap: moduleName =>
            resolveVersion(pool, moduleVerIdx, path).flatMap: moduleVersion =>
                readRequires(view, pool, path, requiresCount, 0, Chunk.empty).flatMap: requires =>
                    val exportsCount = readU2(view)
                    readExports(view, pool, path, exportsCount, 0, Chunk.empty).flatMap: exports =>
                        val opensCount = readU2(view)
                        readOpens(view, pool, path, opensCount, 0, Chunk.empty).flatMap: opens =>
                            val usesCount = readU2(view)
                            readUses(view, pool, path, usesCount, 0, Chunk.empty).flatMap: uses =>
                                val providesCount = readU2(view)
                                readProvides(view, pool, path, providesCount, 0, Chunk.empty).map: provides =>
                                    Tasty.ModuleDescriptor(
                                        name = moduleName,
                                        version = moduleVersion,
                                        requires = requires,
                                        exports = exports,
                                        opens = opens,
                                        uses = uses,
                                        provides = provides
                                    )
    end decodeModuleAttribute

    /** Resolve a CONSTANT_Module name to a String (module names use '.' separators in the constant pool). */
    private def resolveModuleName(
        pool: ConstantPool,
        idx: Int,
        path: String
    )(using Frame): String < (Sync & Abort[TastyError]) =
        pool.moduleName(idx)

    /** Resolve a CONSTANT_Package name to a String (package names use '/' separators; convert to '.'). */
    private def resolvePackageName(
        pool: ConstantPool,
        idx: Int,
        path: String
    )(using Frame): String < (Sync & Abort[TastyError]) =
        pool.packageName(idx).map(_.replace('/', '.'))

    /** Resolve an optional version index (0 means no version). */
    private def resolveVersion(
        pool: ConstantPool,
        idx: Int,
        path: String
    )(using Frame): Maybe[String] < (Sync & Abort[TastyError]) =
        if idx == 0 then Maybe.Absent
        else pool.utf8(idx).map(Maybe(_))

    /** Read `count` requires entries. */
    private def readRequires(
        view: ByteView,
        pool: ConstantPool,
        path: String,
        total: Int,
        idx: Int,
        acc: Chunk[Tasty.ModuleRequires]
    )(using Frame): Chunk[Tasty.ModuleRequires] < (Sync & Abort[TastyError]) =
        if idx >= total then acc
        else
            val requiresIdx    = readU2(view)
            val requiresFlags  = readU2(view)
            val requiresVerIdx = readU2(view)
            resolveModuleName(pool, requiresIdx, path).flatMap: name =>
                resolveVersion(pool, requiresVerIdx, path).flatMap: version =>
                    val isTransitive  = (requiresFlags & ACC_TRANSITIVE) != 0
                    val isStaticPhase = (requiresFlags & ACC_STATIC_PHASE) != 0
                    val req           = Tasty.ModuleRequires(name, version, isTransitive, isStaticPhase)
                    readRequires(view, pool, path, total, idx + 1, acc.appended(req))

    /** Read `count` exports entries. */
    private def readExports(
        view: ByteView,
        pool: ConstantPool,
        path: String,
        total: Int,
        idx: Int,
        acc: Chunk[Tasty.ModuleExports]
    )(using Frame): Chunk[Tasty.ModuleExports] < (Sync & Abort[TastyError]) =
        if idx >= total then acc
        else
            val exportsIdx   = readU2(view)
            val exportsFlags = readU2(view)
            val toCount      = readU2(view)
            resolvePackageName(pool, exportsIdx, path).flatMap: packageName =>
                readModuleRefs(view, pool, path, toCount, 0, Chunk.empty).flatMap: targets =>
                    val exp = Tasty.ModuleExports(packageName, targets, exportsFlags.toLong)
                    readExports(view, pool, path, total, idx + 1, acc.appended(exp))

    /** Read `count` opens entries (same structure as exports). */
    private def readOpens(
        view: ByteView,
        pool: ConstantPool,
        path: String,
        total: Int,
        idx: Int,
        acc: Chunk[Tasty.ModuleOpens]
    )(using Frame): Chunk[Tasty.ModuleOpens] < (Sync & Abort[TastyError]) =
        if idx >= total then acc
        else
            val opensIdx   = readU2(view)
            val opensFlags = readU2(view)
            val toCount    = readU2(view)
            resolvePackageName(pool, opensIdx, path).flatMap: packageName =>
                readModuleRefs(view, pool, path, toCount, 0, Chunk.empty).flatMap: targets =>
                    val op = Tasty.ModuleOpens(packageName, targets, opensFlags.toLong)
                    readOpens(view, pool, path, total, idx + 1, acc.appended(op))

    /** Read `count` uses entries (each is a CONSTANT_Class index). */
    private def readUses(
        view: ByteView,
        pool: ConstantPool,
        path: String,
        total: Int,
        idx: Int,
        acc: Chunk[String]
    )(using Frame): Chunk[String] < (Sync & Abort[TastyError]) =
        if idx >= total then acc
        else
            val usesIdx = readU2(view)
            pool.classRef(usesIdx).flatMap: binaryName =>
                readUses(view, pool, path, total, idx + 1, acc.appended(binaryName.replace('/', '.')))

    /** Read `count` provides entries. */
    private def readProvides(
        view: ByteView,
        pool: ConstantPool,
        path: String,
        total: Int,
        idx: Int,
        acc: Chunk[Tasty.ModuleProvides]
    )(using Frame): Chunk[Tasty.ModuleProvides] < (Sync & Abort[TastyError]) =
        if idx >= total then acc
        else
            val providesIdx       = readU2(view)
            val providesWithCount = readU2(view)
            pool.classRef(providesIdx).flatMap: interfaceName =>
                readClassRefs(view, pool, path, providesWithCount, 0, Chunk.empty).flatMap: impls =>
                    val prov = Tasty.ModuleProvides(interfaceName.replace('/', '.'), impls)
                    readProvides(view, pool, path, total, idx + 1, acc.appended(prov))

    /** Read `count` CONSTANT_Module indices and resolve each to a module name string. */
    private def readModuleRefs(
        view: ByteView,
        pool: ConstantPool,
        path: String,
        total: Int,
        idx: Int,
        acc: Chunk[String]
    )(using Frame): Chunk[String] < (Sync & Abort[TastyError]) =
        if idx >= total then acc
        else
            val moduleIdx = readU2(view)
            resolveModuleName(pool, moduleIdx, path).flatMap: name =>
                readModuleRefs(view, pool, path, total, idx + 1, acc.appended(name))

    /** Read `count` CONSTANT_Class indices and resolve each to a dotted class name string. */
    private def readClassRefs(
        view: ByteView,
        pool: ConstantPool,
        path: String,
        total: Int,
        idx: Int,
        acc: Chunk[String]
    )(using Frame): Chunk[String] < (Sync & Abort[TastyError]) =
        if idx >= total then acc
        else
            val classIdx = readU2(view)
            pool.classRef(classIdx).flatMap: binaryName =>
                readClassRefs(view, pool, path, total, idx + 1, acc.appended(binaryName.replace('/', '.')))

end ModuleInfoReader
