package kyo

import kyo.Tasty.SymbolId

/** Plan-mandated tests for Phase 08 (leaves 170-171): JavaMetadata.isJvmPublic/Private/Protected/Static/Final.
  *
  * Pins: INV-002.
  */
class JavaMetadataPredicateTest extends Test:

    private def meta(flags: Int): Tasty.JavaMetadata = Tasty.JavaMetadata(
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
        assert(m.isJvmPublic, "isJvmPublic must be true for 0x0001")
        assert(m.isJvmStatic, "isJvmStatic must be true for 0x0008")
        assert(!m.isJvmFinal, "isJvmFinal must be false")
        assert(!m.isJvmPrivate, "isJvmPrivate must be false")
        assert(!m.isJvmProtected, "isJvmProtected must be false")
        succeed
    }

    // ── Leaf 171: isJvmPrivate ────────────────────────────────────────────────
    // Given: JavaMetadata(access = 0x0002)
    // When: invoke all 5 predicates
    // Then: isJvmPrivate == true; others false
    "Leaf 171: isJvmPrivate true for 0x0002; others false" in run {
        val m = meta(0x0002)
        assert(m.isJvmPrivate, "isJvmPrivate must be true for 0x0002")
        assert(!m.isJvmPublic, "isJvmPublic must be false")
        assert(!m.isJvmProtected, "isJvmProtected must be false")
        assert(!m.isJvmStatic, "isJvmStatic must be false")
        assert(!m.isJvmFinal, "isJvmFinal must be false")
        succeed
    }

    // Additional: isJvmFinal
    "Leaf 171b: isJvmFinal true for 0x0010; others false" in run {
        val m = meta(0x0010)
        assert(m.isJvmFinal, "isJvmFinal must be true for 0x0010")
        assert(!m.isJvmPublic)
        assert(!m.isJvmPrivate)
        assert(!m.isJvmProtected)
        assert(!m.isJvmStatic)
        succeed
    }

    // Additional: isJvmProtected
    "Leaf 171c: isJvmProtected true for 0x0004" in run {
        val m = meta(0x0004)
        assert(m.isJvmProtected, "isJvmProtected must be true for 0x0004")
        assert(!m.isJvmPublic)
        assert(!m.isJvmPrivate)
        assert(!m.isJvmStatic)
        assert(!m.isJvmFinal)
        succeed
    }

end JavaMetadataPredicateTest
