package kyo

import kyo.Tasty.SymbolId

/** Field JVM accessor predicates.
  *
  * Field is Java-classfile-level (no Scala body). The isJvmPublic / isJvmPrivate / isJvmProtected / isJvmStatic / isJvmFinal predicates
  * read the JVM access flags from JavaMetadata.accessFlags. Leaf 82 tests isJvmPublic with a real javaMetadata value. Leaf 83 tests
  * isJvmStatic returning false when javaMetadata is Absent.
  */
class FieldJvmAccessTest extends kyo.test.Test[Any]:

    import AllowUnsafe.embrace.danger

    // ACC_PUBLIC = 0x0001, ACC_PRIVATE = 0x0002, ACC_PROTECTED = 0x0004,
    // ACC_STATIC = 0x0008, ACC_FINAL = 0x0010

    private def makeFieldWithAccess(id: Int, name: String, accessFlags: Int): Tasty.Symbol.Field =
        val meta = Tasty.Java.Metadata(
            throwsTypes = Chunk.empty,
            annotations = Chunk.empty,
            enclosingMethod = Maybe.Absent,
            accessFlags = accessFlags,
            recordComponents = Chunk.empty,
            bootstrapMethods = Chunk.empty,
            nestHost = Maybe.Absent,
            nestMembers = Chunk.empty,
            paramNames = Chunk.empty,
            runtimeTypeAnnotations = Chunk.empty
        )
        Tasty.Symbol.Field(
            SymbolId(id),
            Tasty.Name(name),
            Tasty.Flags.empty,
            SymbolId(0),
            Maybe.Absent,
            Maybe.Absent,
            Maybe.Absent,
            Maybe(meta),
            Chunk.empty
        )
    end makeFieldWithAccess

    private def makeFieldNoMeta(id: Int, name: String): Tasty.Symbol.Field =
        Tasty.Symbol.Field(
            SymbolId(id),
            Tasty.Name(name),
            Tasty.Flags.empty,
            SymbolId(0),
            Maybe.Absent,
            Maybe.Absent,
            Maybe.Absent,
            Maybe.Absent,
            Chunk.empty
        )

    // ── Leaf 82: isJvmPublic ──────────────────────────────────────────────────
    // Given: Field with javaMetadata.accessFlags = ACC_PUBLIC (0x0001)
    // When: f.isJvmPublic
    // Then: true
    "Leaf 82: isJvmPublic: returns true when ACC_PUBLIC bit is set in accessFlags" in {
        val field = makeFieldWithAccess(id = 1, name = "F", accessFlags = 0x0001)
        Tasty.Classpath.fromPicklesWithSymbols(Chunk(field)).map: cp =>
            assert(
                field.javaMetadata.map(m => (m.accessFlags & 0x0001) != 0).getOrElse(false),
                "isJvmPublic must be true when ACC_PUBLIC (0x0001) is set"
            )
            assert(
                !field.javaMetadata.map(m => (m.accessFlags & 0x0002) != 0).getOrElse(false),
                "isJvmPrivate must be false when only ACC_PUBLIC is set"
            )
            assert(
                !field.javaMetadata.map(m => (m.accessFlags & 0x0004) != 0).getOrElse(false),
                "isJvmProtected must be false when only ACC_PUBLIC is set"
            )
            assert(
                !field.javaMetadata.map(m => (m.accessFlags & 0x0008) != 0).getOrElse(false),
                "isJvmStatic must be false when only ACC_PUBLIC is set"
            )
            assert(
                !field.javaMetadata.map(m => (m.accessFlags & 0x0010) != 0).getOrElse(false),
                "isJvmFinal must be false when only ACC_PUBLIC is set"
            )
            succeed
    }

    // ── Leaf 83: isJvmStatic-absent-javaMetadata ─────────────────────────────
    // Given: Field with javaMetadata=Maybe.Absent
    // When: f.isJvmStatic
    // Then: false
    "Leaf 83: isJvmStatic-absent-javaMetadata: returns false when javaMetadata is Absent" in {
        val field = makeFieldNoMeta(id = 1, name = "G")
        Tasty.Classpath.fromPicklesWithSymbols(Chunk(field)).map: cp =>
            assert(
                !field.javaMetadata.map(m => (m.accessFlags & 0x0008) != 0).getOrElse(false),
                "isJvmStatic must be false when javaMetadata is Absent"
            )
            assert(
                !field.javaMetadata.map(m => (m.accessFlags & 0x0001) != 0).getOrElse(false),
                "isJvmPublic must be false when javaMetadata is Absent"
            )
            assert(
                !field.javaMetadata.map(m => (m.accessFlags & 0x0002) != 0).getOrElse(false),
                "isJvmPrivate must be false when javaMetadata is Absent"
            )
            assert(
                !field.javaMetadata.map(m => (m.accessFlags & 0x0004) != 0).getOrElse(false),
                "isJvmProtected must be false when javaMetadata is Absent"
            )
            assert(
                !field.javaMetadata.map(m => (m.accessFlags & 0x0010) != 0).getOrElse(false),
                "isJvmFinal must be false when javaMetadata is Absent"
            )
            succeed
    }

    // ── Additional coverage: all five predicates across multiple flag combinations ──

    "Field JVM access flags: private static final combination" in {
        // ACC_PRIVATE=0x0002, ACC_STATIC=0x0008, ACC_FINAL=0x0010
        val field = makeFieldWithAccess(id = 1, name = "CONSTANT", accessFlags = 0x0002 | 0x0008 | 0x0010)
        Tasty.Classpath.fromPicklesWithSymbols(Chunk(field)).map: cp =>
            assert(
                !field.javaMetadata.map(m => (m.accessFlags & 0x0001) != 0).getOrElse(false),
                "isJvmPublic must be false for private static final"
            )
            assert(
                field.javaMetadata.map(m => (m.accessFlags & 0x0002) != 0).getOrElse(false),
                "isJvmPrivate must be true for private static final"
            )
            assert(
                !field.javaMetadata.map(m => (m.accessFlags & 0x0004) != 0).getOrElse(false),
                "isJvmProtected must be false for private static final"
            )
            assert(
                field.javaMetadata.map(m => (m.accessFlags & 0x0008) != 0).getOrElse(false),
                "isJvmStatic must be true for private static final"
            )
            assert(
                field.javaMetadata.map(m => (m.accessFlags & 0x0010) != 0).getOrElse(false),
                "isJvmFinal must be true for private static final"
            )
            succeed
    }

    "Field JVM access flags: protected field" in {
        // ACC_PROTECTED=0x0004
        val field = makeFieldWithAccess(id = 1, name = "protectedField", accessFlags = 0x0004)
        Tasty.Classpath.fromPicklesWithSymbols(Chunk(field)).map: cp =>
            assert(
                !field.javaMetadata.map(m => (m.accessFlags & 0x0001) != 0).getOrElse(false),
                "isJvmPublic must be false for protected field"
            )
            assert(
                !field.javaMetadata.map(m => (m.accessFlags & 0x0002) != 0).getOrElse(false),
                "isJvmPrivate must be false for protected field"
            )
            assert(
                field.javaMetadata.map(m => (m.accessFlags & 0x0004) != 0).getOrElse(false),
                "isJvmProtected must be true for protected field"
            )
            assert(
                !field.javaMetadata.map(m => (m.accessFlags & 0x0008) != 0).getOrElse(false),
                "isJvmStatic must be false for protected field"
            )
            assert(
                !field.javaMetadata.map(m => (m.accessFlags & 0x0010) != 0).getOrElse(false),
                "isJvmFinal must be false for protected field"
            )
            succeed
    }

end FieldJvmAccessTest
