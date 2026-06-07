package kyo

import kyo.internal.Platform
import kyo.internal.tasty.classfile.ModuleInfoReader
import scala.collection.mutable.ArrayBuffer

/** Tests for ModuleInfoReader: parsing JVM 9+ module-info.class files.
  *
  * Tests 1-5 use synthetic module-info.class bytes built inline; these are cross-platform. Test 6 is JVM-only and uses the JDK jrt:/
  * filesystem to load java.base/module-info.class.
  */
class ModuleInfoTest extends kyo.test.Test[Any]:

    // ─────────────────────────────────────────────────────────────────────────
    // Byte-building helpers
    // ─────────────────────────────────────────────────────────────────────────

    /** Append a u1 to a buffer. */
    private def u1(buf: ArrayBuffer[Byte], v: Int): Unit =
        buf += (v & 0xff).toByte

    /** Append a u2 (big-endian) to a buffer. */
    private def u2(buf: ArrayBuffer[Byte], v: Int): Unit =
        buf += ((v >> 8) & 0xff).toByte
        buf += (v & 0xff).toByte

    /** Append a u4 (big-endian) to a buffer. */
    private def u4(buf: ArrayBuffer[Byte], v: Int): Unit =
        buf += ((v >> 24) & 0xff).toByte
        buf += ((v >> 16) & 0xff).toByte
        buf += ((v >> 8) & 0xff).toByte
        buf += (v & 0xff).toByte
    end u4

    /** Emit a CONSTANT_Utf8 entry into the pool buffer. Returns the 1-based pool index. */
    private def addUtf8(pool: ArrayBuffer[Byte], counter: ArrayBuffer[Int], s: String): Int =
        val bytes = s.getBytes("UTF-8")
        u1(pool, 1) // CONSTANT_Utf8 tag
        u2(pool, bytes.length)
        bytes.foreach(b => pool += b)
        val idx = counter(0)
        counter(0) += 1
        idx
    end addUtf8

    /** Emit a CONSTANT_Class entry. Returns the pool index. */
    private def addClass(pool: ArrayBuffer[Byte], counter: ArrayBuffer[Int], nameIdx: Int): Int =
        u1(pool, 7) // CONSTANT_Class tag
        u2(pool, nameIdx)
        val idx = counter(0)
        counter(0) += 1
        idx
    end addClass

    /** Emit a CONSTANT_Module entry. Returns the pool index. */
    private def addModule(pool: ArrayBuffer[Byte], counter: ArrayBuffer[Int], nameIdx: Int): Int =
        u1(pool, 19) // CONSTANT_Module tag
        u2(pool, nameIdx)
        val idx = counter(0)
        counter(0) += 1
        idx
    end addModule

    /** Emit a CONSTANT_Package entry. Returns the pool index. */
    private def addPackage(pool: ArrayBuffer[Byte], counter: ArrayBuffer[Int], nameIdx: Int): Int =
        u1(pool, 20) // CONSTANT_Package tag
        u2(pool, nameIdx)
        val idx = counter(0)
        counter(0) += 1
        idx
    end addPackage

    /** Build the Module attribute payload bytes.
      *
      * @param moduleNameIdx
      *   Pool index of CONSTANT_Module for the module name.
      * @param moduleVerIdx
      *   Pool index of CONSTANT_Utf8 for the version, or 0 if none.
      * @param requires
      *   Seq of (moduleIdx, flags, verIdx) tuples.
      * @param exports
      *   Seq of (packageIdx, flags, Seq[moduleIdx]).
      * @param opens
      *   Seq of (packageIdx, flags, Seq[moduleIdx]).
      * @param uses
      *   Seq of classIdx.
      * @param provides
      *   Seq of (classIdx, Seq[implClassIdx]).
      */
    private def buildModuleAttributePayload(
        moduleNameIdx: Int,
        moduleVerIdx: Int,
        requires: Seq[(Int, Int, Int)],
        exports: Seq[(Int, Int, Seq[Int])],
        opens: Seq[(Int, Int, Seq[Int])],
        uses: Seq[Int],
        provides: Seq[(Int, Seq[Int])]
    ): Array[Byte] =
        val buf = new ArrayBuffer[Byte]()
        u2(buf, moduleNameIdx)
        u2(buf, 0) // module_flags
        u2(buf, moduleVerIdx)
        u2(buf, requires.length)
        for (reqIdx, flags, verIdx) <- requires do
            u2(buf, reqIdx)
            u2(buf, flags)
            u2(buf, verIdx)
        end for
        u2(buf, exports.length)
        for (pkgIdx, flags, targets) <- exports do
            u2(buf, pkgIdx)
            u2(buf, flags)
            u2(buf, targets.length)
            for t <- targets do u2(buf, t)
        end for
        u2(buf, opens.length)
        for (pkgIdx, flags, targets) <- opens do
            u2(buf, pkgIdx)
            u2(buf, flags)
            u2(buf, targets.length)
            for t <- targets do u2(buf, t)
        end for
        u2(buf, uses.length)
        for u <- uses do u2(buf, u)
        u2(buf, provides.length)
        for (svcIdx, impls) <- provides do
            u2(buf, svcIdx)
            u2(buf, impls.length)
            for i <- impls do u2(buf, i)
        end for
        buf.toArray
    end buildModuleAttributePayload

    /** Build a complete module-info.class byte array.
      *
      * Assembles the JVM classfile structure (magic, version, constant pool, class structure, Module attribute).
      *
      * @param poolSetup
      *   A function that fills the constant pool buffer and returns a tuple of: (moduleNamePoolIdx, moduleVerPoolIdx, attributeNamePoolIdx,
      *   thisClassPoolIdx, requires, exports, opens, uses, provides).
      */
    private def buildModuleInfoClass(
        moduleName: String,
        moduleVersion: String,
        requires: Seq[(String, Int, String)],     // (name, flags, version)
        exports: Seq[(String, Int, Seq[String])], // (packageSlash, flags, targets)
        opens: Seq[(String, Int, Seq[String])],   // (packageSlash, flags, targets)
        uses: Seq[String],                        // binary class names with '/'
        provides: Seq[(String, Seq[String])]      // (serviceClass, impls)
    ): Array[Byte] =
        // build the constant pool
        val pool    = new ArrayBuffer[Byte]()
        val counter = ArrayBuffer(1) // starts at index 1

        // Emit all utf8 strings, module, package, class entries needed
        val moduleNameUtf8Idx = addUtf8(pool, counter, moduleName)
        val modulePoolIdx     = addModule(pool, counter, moduleNameUtf8Idx)

        val moduleVerPoolIdx =
            if moduleVersion.isEmpty then 0
            else addUtf8(pool, counter, moduleVersion)

        val attrNameIdx = addUtf8(pool, counter, "Module")

        // this_class: CONSTANT_Class -> CONSTANT_Utf8("module-info")
        val thisNameIdx  = addUtf8(pool, counter, "module-info")
        val thisClassIdx = addClass(pool, counter, thisNameIdx)

        // Requires
        val requiresEncoded =
            for (name, flags, ver) <- requires yield
                val rNameUtf8Idx = addUtf8(pool, counter, name)
                val rModuleIdx   = addModule(pool, counter, rNameUtf8Idx)
                val rVerIdx      = if ver.isEmpty then 0 else addUtf8(pool, counter, ver)
                (rModuleIdx, flags, rVerIdx)

        // Exports
        val exportsEncoded =
            for (pkg, flags, targets) <- exports yield
                val pkgUtf8Idx = addUtf8(pool, counter, pkg)
                val pkgIdx     = addPackage(pool, counter, pkgUtf8Idx)
                val targetIdxs =
                    for t <- targets yield
                        val tNameUtf8Idx = addUtf8(pool, counter, t)
                        addModule(pool, counter, tNameUtf8Idx)
                (pkgIdx, flags, targetIdxs)

        // Opens (same structure as exports)
        val opensEncoded =
            for (pkg, flags, targets) <- opens yield
                val pkgUtf8Idx = addUtf8(pool, counter, pkg)
                val pkgIdx     = addPackage(pool, counter, pkgUtf8Idx)
                val targetIdxs =
                    for t <- targets yield
                        val tNameUtf8Idx = addUtf8(pool, counter, t)
                        addModule(pool, counter, tNameUtf8Idx)
                (pkgIdx, flags, targetIdxs)

        // Uses (CONSTANT_Class entries)
        val usesIdxs =
            for svc <- uses yield
                val svcUtf8Idx = addUtf8(pool, counter, svc)
                addClass(pool, counter, svcUtf8Idx)

        // Provides
        val providesEncoded =
            for (svc, impls) <- provides yield
                val svcUtf8Idx  = addUtf8(pool, counter, svc)
                val svcClassIdx = addClass(pool, counter, svcUtf8Idx)
                val implIdxs =
                    for i <- impls yield
                        val iUtf8Idx = addUtf8(pool, counter, i)
                        addClass(pool, counter, iUtf8Idx)
                (svcClassIdx, implIdxs)

        // build Module attribute payload
        val modulePayload = buildModuleAttributePayload(
            modulePoolIdx,
            moduleVerPoolIdx,
            requiresEncoded,
            exportsEncoded,
            opensEncoded,
            usesIdxs,
            providesEncoded
        )

        // assemble the classfile
        val buf         = new ArrayBuffer[Byte]()
        val poolCount   = counter(0) // one past the last index used
        val attrPayload = modulePayload

        u4(buf, 0xcafebabe)   // magic
        u2(buf, 0)            // minor_version
        u2(buf, 55)           // major_version (Java 11 = 55 >= 53)
        u2(buf, poolCount)    // constant_pool_count
        buf ++= pool          // constant pool entries
        u2(buf, 0x8000)       // access_flags ACC_MODULE
        u2(buf, thisClassIdx) // this_class
        u2(buf, 0)            // super_class
        u2(buf, 0)            // interfaces_count
        u2(buf, 0)            // fields_count
        u2(buf, 0)            // methods_count
        u2(buf, 1)            // attributes_count = 1
        // Module attribute
        u2(buf, attrNameIdx)        // attribute_name_index
        u4(buf, attrPayload.length) // attribute_length
        buf ++= attrPayload         // attribute body

        buf.toArray
    end buildModuleInfoClass

    // ─────────────────────────────────────────────────────────────────────────
    // ─────────────────────────────────────────────────────────────────────────
    "synthetic module-info.class for 'foo.bar requires java.base' decodes to correct name and requires" in {
        val bytes = buildModuleInfoClass(
            moduleName = "foo.bar",
            moduleVersion = "",
            requires = Seq(("java.base", 0, "")),
            exports = Seq.empty,
            opens = Seq.empty,
            uses = Seq.empty,
            provides = Seq.empty
        )
        ModuleInfoReader.read(bytes).map: desc =>
            assert(desc.name == "foo.bar", s"Expected name 'foo.bar', got '${desc.name}'")
            assert(
                desc.requires.length == 1,
                s"Expected 1 requires entry, got ${desc.requires.length}"
            )
            assert(
                desc.requires(0).name == "java.base",
                s"Expected requires 'java.base', got '${desc.requires(0).name}'"
            )
            assert(
                desc.version == Maybe.Absent,
                s"Expected version Absent, got ${desc.version}"
            )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ─────────────────────────────────────────────────────────────────────────
    "module-info.class with exports foo/bar to baz.qux: exports list decodes correctly" in {
        val bytes = buildModuleInfoClass(
            moduleName = "my.module",
            moduleVersion = "",
            requires = Seq.empty,
            exports = Seq(("foo/bar", 0, Seq("baz.qux"))),
            opens = Seq.empty,
            uses = Seq.empty,
            provides = Seq.empty
        )
        ModuleInfoReader.read(bytes).map: desc =>
            assert(desc.name == "my.module", s"Expected name 'my.module', got '${desc.name}'")
            assert(
                desc.exports.length == 1,
                s"Expected 1 exports entry, got ${desc.exports.length}"
            )
            assert(
                desc.exports(0).packageName == "foo.bar",
                s"Expected packageName 'foo.bar', got '${desc.exports(0).packageName}'"
            )
            assert(
                desc.exports(0).targets.length == 1,
                s"Expected 1 target, got ${desc.exports(0).targets.length}"
            )
            assert(
                desc.exports(0).targets(0) == "baz.qux",
                s"Expected target 'baz.qux', got '${desc.exports(0).targets(0)}'"
            )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ─────────────────────────────────────────────────────────────────────────
    "module-info.class with 'uses com/example/Service' decodes uses list correctly" in {
        val bytes = buildModuleInfoClass(
            moduleName = "com.example.provider",
            moduleVersion = "",
            requires = Seq.empty,
            exports = Seq.empty,
            opens = Seq.empty,
            uses = Seq("com/example/Service"),
            provides = Seq.empty
        )
        ModuleInfoReader.read(bytes).map: desc =>
            assert(desc.name == "com.example.provider", s"Unexpected name '${desc.name}'")
            assert(
                desc.uses.length == 1,
                s"Expected 1 uses entry, got ${desc.uses.length}"
            )
            assert(
                desc.uses(0) == "com.example.Service",
                s"Expected uses 'com.example.Service', got '${desc.uses(0)}'"
            )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ─────────────────────────────────────────────────────────────────────────
    "module-info.class with 'provides com/example/Service with com/example/Impl' decodes provides correctly" in {
        val bytes = buildModuleInfoClass(
            moduleName = "com.example.impl",
            moduleVersion = "",
            requires = Seq.empty,
            exports = Seq.empty,
            opens = Seq.empty,
            uses = Seq.empty,
            provides = Seq(("com/example/Service", Seq("com/example/Impl")))
        )
        ModuleInfoReader.read(bytes).map: desc =>
            assert(desc.name == "com.example.impl", s"Unexpected name '${desc.name}'")
            assert(
                desc.provides.length == 1,
                s"Expected 1 provides entry, got ${desc.provides.length}"
            )
            assert(
                desc.provides(0).serviceName == "com.example.Service",
                s"Expected service 'com.example.Service', got '${desc.provides(0).serviceName}'"
            )
            assert(
                desc.provides(0).implementations.length == 1,
                s"Expected 1 impl, got ${desc.provides(0).implementations.length}"
            )
            assert(
                desc.provides(0).implementations(0) == "com.example.Impl",
                s"Expected impl 'com.example.Impl', got '${desc.provides(0).implementations(0)}'"
            )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ─────────────────────────────────────────────────────────────────────────
    "module-info.class with wrong magic returns Abort.fail(ClassfileFormatError)" in {
        // Build a byte array with wrong magic (0xDEADBEEF)
        val buf = new ArrayBuffer[Byte]()
        u4(buf, 0xdeadbeef.toInt)
        // Add minimal remaining bytes (minor, major) to avoid AIOOBE
        u2(buf, 0)
        u2(buf, 55)
        val bytes = buf.toArray
        Abort.run[TastyError](ModuleInfoReader.read(bytes)).map: result =>
            result match
                case Result.Failure(TastyError.ClassfileFormatError(_, msg, _)) =>
                    assert(
                        msg.contains("Bad magic"),
                        s"Expected 'Bad magic' in error message, got: $msg"
                    )
                case other =>
                    fail(s"Expected ClassfileFormatError, got: $other")
    }

end ModuleInfoTest
