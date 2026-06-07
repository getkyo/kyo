package kyo.fixtures

/** plan leaves 1-5: EmbeddedClassfiles Base64 refactor behavioral tests.
  *
  * Verifies that the refactored EmbeddedClassfiles produces byte-identical output
  * to the HEAD literal-byte form, that lazy decoding is single-init, that dispatch
  * parity holds, and that every accessor is cross-platform.
  */
class EmbeddedClassfilesTest extends kyo.test.Test[Any]:

    /** FNV-1a 64-bit hash of a byte array. Pure Scala, cross-platform, no MessageDigest dependency. */
    private def fnv1a64(data: Array[Byte]): Long =
        var h = -3750763034362895579L
        var i = 0
        while i < data.length do
            h ^= (data(i) & 0xff).toLong
            h *= 1099511628211L
            i += 1
        end while
        h
    end fnv1a64

    // Leaf 1: javaUtilArrayListClass decodes to a non-empty classfile
    "javaUtilArrayListClass decodes to a non-empty classfile with CAFEBABE magic" in {
        val bytes = EmbeddedClassfiles.javaUtilArrayListClass
        assert(bytes.length > 0, "ArrayList bytes must be non-empty")
        assert(bytes(0) == 0xca.toByte, "byte 0 must be 0xCA")
        assert(bytes(1) == 0xfe.toByte, "byte 1 must be 0xFE")
        assert(bytes(2) == 0xba.toByte, "byte 2 must be 0xBA")
        assert(bytes(3) == 0xbe.toByte, "byte 3 must be 0xBE")
    }

    // Leaf 2: byte-equality with HEAD literals via FNV-1a 64-bit hash
    "byte equality with HEAD literals for all 8 classes via FNV-1a hash" in {
        // Expected FNV-1a 64-bit hashes verified against HEAD literal bytes (JDK 25.0.3).
        val expected: List[(String, Array[Byte], Int, Long)] = List(
            ("java/util/ArrayList.class", EmbeddedClassfiles.javaUtilArrayListClass, 19356, 3965872221081036403L),
            ("java/io/FileInputStream.class", EmbeddedClassfiles.javaIoFileInputStreamClass, 7555, 6504141863715071781L),
            ("java/util/function/Function.class", EmbeddedClassfiles.javaUtilFunctionFunctionClass, 2392, 8261270231465015577L),
            ("java/util/HashMap$Node.class", EmbeddedClassfiles.javaUtilHashMapNodeClass, 2316, 5065845858803875196L),
            ("java/util/HashMap.class", EmbeddedClassfiles.javaUtilHashMapClass, 26411, 132939595944083119L),
            ("java/lang/constant/ClassDesc.class", EmbeddedClassfiles.javaLangConstantClassDescClass, 3879, -1497212317139314440L),
            (
                "java/lang/module/ModuleDescriptor$Requires$Modifier.class",
                EmbeddedClassfiles.javaLangModuleModuleDescriptorRequiresModifierClass,
                1835,
                -7163945528973120689L
            ),
            ("java/lang/Deprecated.class", EmbeddedClassfiles.javaLangDeprecatedClass, 647, -5715876541101650887L)
        )
        expected.foreach { (path, bytes, expectedSize, expectedHash) =>
            assert(
                bytes.length == expectedSize,
                s"$path: expected size $expectedSize, got ${bytes.length}"
            )
            val actualHash = fnv1a64(bytes)
            assert(
                actualHash == expectedHash,
                s"$path: FNV-1a mismatch: expected $expectedHash, got $actualHash"
            )
        }
        succeed
    }

    // Leaf 3: loadJdkClass dispatch parity
    "loadJdkClass dispatch returns the same array as each dedicated accessor" in {
        assert(EmbeddedClassfiles.loadJdkClass("java/util/ArrayList.class") eq EmbeddedClassfiles.javaUtilArrayListClass)
        assert(EmbeddedClassfiles.loadJdkClass("java/io/FileInputStream.class") eq EmbeddedClassfiles.javaIoFileInputStreamClass)
        assert(EmbeddedClassfiles.loadJdkClass("java/util/function/Function.class") eq EmbeddedClassfiles.javaUtilFunctionFunctionClass)
        assert(EmbeddedClassfiles.loadJdkClass("java/util/HashMap$Node.class") eq EmbeddedClassfiles.javaUtilHashMapNodeClass)
        assert(EmbeddedClassfiles.loadJdkClass("java/util/HashMap.class") eq EmbeddedClassfiles.javaUtilHashMapClass)
        assert(EmbeddedClassfiles.loadJdkClass("java/lang/constant/ClassDesc.class") eq EmbeddedClassfiles.javaLangConstantClassDescClass)
        assert(EmbeddedClassfiles.loadJdkClass(
            "java/lang/module/ModuleDescriptor$Requires$Modifier.class"
        ) eq EmbeddedClassfiles.javaLangModuleModuleDescriptorRequiresModifierClass)
        assert(EmbeddedClassfiles.loadJdkClass("java/lang/Deprecated.class") eq EmbeddedClassfiles.javaLangDeprecatedClass)
    }

    // Leaf 4: lazy decode runs at most once per JVM lifetime per entry
    // The lazy val is initialized at most once (Scala lazy val semantics); reference equality
    // proves the same backing array is returned on every call without re-decoding.
    // Same approach: ref-equality for lazy-val proof.
    "lazy decode runs at most once: accessor returns same array instance on repeated access" in {
        val first  = EmbeddedClassfiles.javaUtilArrayListClass
        val second = EmbeddedClassfiles.javaUtilArrayListClass
        assert(first eq second, "lazy val must return the same Array[Byte] instance on repeated access")
    }

    // Leaf 5: cross-platform decode on JS / Native
    // File is in shared/src/test so it runs on all three platforms.
    // Verify every accessor returns a non-empty classfile array with CAFEBABE magic.
    "cross-platform: every accessor returns a non-empty classfile with CAFEBABE magic" in {
        val allAccessors: List[(String, Array[Byte])] = List(
            ("javaUtilArrayListClass", EmbeddedClassfiles.javaUtilArrayListClass),
            ("javaIoFileInputStreamClass", EmbeddedClassfiles.javaIoFileInputStreamClass),
            ("javaUtilFunctionFunctionClass", EmbeddedClassfiles.javaUtilFunctionFunctionClass),
            ("javaUtilHashMapNodeClass", EmbeddedClassfiles.javaUtilHashMapNodeClass),
            ("javaUtilHashMapClass", EmbeddedClassfiles.javaUtilHashMapClass),
            ("javaLangConstantClassDescClass", EmbeddedClassfiles.javaLangConstantClassDescClass),
            ("javaLangModuleModuleDescriptorRequiresModifierClass", EmbeddedClassfiles.javaLangModuleModuleDescriptorRequiresModifierClass),
            ("javaLangDeprecatedClass", EmbeddedClassfiles.javaLangDeprecatedClass)
        )
        allAccessors.foreach { (name, bytes) =>
            assert(bytes.length > 0, s"$name must return non-empty array")
            assert(bytes(0) == 0xca.toByte, s"$name: byte 0 must be 0xCA (CAFEBABE magic)")
            assert(bytes(1) == 0xfe.toByte, s"$name: byte 1 must be 0xFE")
            assert(bytes(2) == 0xba.toByte, s"$name: byte 2 must be 0xBA")
            assert(bytes(3) == 0xbe.toByte, s"$name: byte 3 must be 0xBE")
        }
        succeed
    }

end EmbeddedClassfilesTest
