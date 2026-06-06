package kyo

import kyo.Tasty.SymbolId

/** JavaMetadata.isJvmPublic/Private/Protected/Static/Final.
  */
class JavaMetadataPredicateTest extends Test:

    private def meta(flags: Int): Tasty.Java.Metadata = Tasty.Java.Metadata(
        throwsTypes = Chunk.empty,
        annotations = Chunk.empty,
        enclosingMethod = Maybe.Absent,
        accessFlags = flags,
        recordComponents = Chunk.empty,
        bootstrapMethods = Chunk.empty,
        nestHost = Maybe.Absent,
        nestMembers = Chunk.empty,
        paramNames = Chunk.empty,
        runtimeTypeAnnotations = Chunk.empty
    )

    // ── Leaf 170: isJvmPublic ─────────────────────────────────────────────────
    // Given: JavaMetadata(access = 0x0001 | 0x0008) (public static)
    // When: invoke isJvmPublic, isJvmStatic, isJvmFinal
    // Then: true, true, false
    "Leaf 170: isJvmPublic and isJvmStatic true for 0x0009; isJvmFinal false" in run {
        val m = meta(0x0001 | 0x0008)
        assert((m.accessFlags & 0x0001) != 0, "isJvmPublic must be true for 0x0001")
        assert((m.accessFlags & 0x0008) != 0, "isJvmStatic must be true for 0x0008")
        assert(!((m.accessFlags & 0x0010) != 0), "isJvmFinal must be false")
        assert(!((m.accessFlags & 0x0002) != 0), "isJvmPrivate must be false")
        assert(!((m.accessFlags & 0x0004) != 0), "isJvmProtected must be false")
        succeed
    }

    // ── Leaf 171: isJvmPrivate ────────────────────────────────────────────────
    // Given: JavaMetadata(access = 0x0002)
    // When: invoke all 5 predicates
    // Then: isJvmPrivate == true; others false
    "Leaf 171: isJvmPrivate true for 0x0002; others false" in run {
        val m = meta(0x0002)
        assert((m.accessFlags & 0x0002) != 0, "isJvmPrivate must be true for 0x0002")
        assert(!((m.accessFlags & 0x0001) != 0), "isJvmPublic must be false")
        assert(!((m.accessFlags & 0x0004) != 0), "isJvmProtected must be false")
        assert(!((m.accessFlags & 0x0008) != 0), "isJvmStatic must be false")
        assert(!((m.accessFlags & 0x0010) != 0), "isJvmFinal must be false")
        succeed
    }

    // Additional: isJvmFinal
    "Leaf 171b: isJvmFinal true for 0x0010; others false" in run {
        val m = meta(0x0010)
        assert((m.accessFlags & 0x0010) != 0, "isJvmFinal must be true for 0x0010")
        assert(!((m.accessFlags & 0x0001) != 0))
        assert(!((m.accessFlags & 0x0002) != 0))
        assert(!((m.accessFlags & 0x0004) != 0))
        assert(!((m.accessFlags & 0x0008) != 0))
        succeed
    }

    // Additional: isJvmProtected
    "Leaf 171c: isJvmProtected true for 0x0004" in run {
        val m = meta(0x0004)
        assert((m.accessFlags & 0x0004) != 0, "isJvmProtected must be true for 0x0004")
        assert(!((m.accessFlags & 0x0001) != 0))
        assert(!((m.accessFlags & 0x0002) != 0))
        assert(!((m.accessFlags & 0x0008) != 0))
        assert(!((m.accessFlags & 0x0010) != 0))
        succeed
    }

end JavaMetadataPredicateTest
