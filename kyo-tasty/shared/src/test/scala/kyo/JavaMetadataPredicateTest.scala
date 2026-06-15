package kyo

import kyo.Tasty.SymbolId

/** JavaMetadata.isJvmPublic/Private/Protected/Static/Final.
  */
class JavaMetadataPredicateTest extends kyo.test.Test[Any]:

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

    "isJvmPublic and isJvmStatic true for 0x0009; isJvmFinal false" in {
        val m = meta(0x0001 | 0x0008)
        assert((m.accessFlags & 0x0001) != 0, "isJvmPublic must be true for 0x0001")
        assert((m.accessFlags & 0x0008) != 0, "isJvmStatic must be true for 0x0008")
        assert(!((m.accessFlags & 0x0010) != 0), "isJvmFinal must be false")
        assert(!((m.accessFlags & 0x0002) != 0), "isJvmPrivate must be false")
        assert(!((m.accessFlags & 0x0004) != 0), "isJvmProtected must be false")
        succeed
    }

    "isJvmPrivate true for 0x0002; others false" in {
        val m = meta(0x0002)
        assert((m.accessFlags & 0x0002) != 0, "isJvmPrivate must be true for 0x0002")
        assert(!((m.accessFlags & 0x0001) != 0), "isJvmPublic must be false")
        assert(!((m.accessFlags & 0x0004) != 0), "isJvmProtected must be false")
        assert(!((m.accessFlags & 0x0008) != 0), "isJvmStatic must be false")
        assert(!((m.accessFlags & 0x0010) != 0), "isJvmFinal must be false")
        succeed
    }

    "isJvmFinal true for 0x0010; others false" in {
        val m = meta(0x0010)
        assert((m.accessFlags & 0x0010) != 0, "isJvmFinal must be true for 0x0010")
        assert(!((m.accessFlags & 0x0001) != 0))
        assert(!((m.accessFlags & 0x0002) != 0))
        assert(!((m.accessFlags & 0x0004) != 0))
        assert(!((m.accessFlags & 0x0008) != 0))
        succeed
    }

    "isJvmProtected true for 0x0004" in {
        val m = meta(0x0004)
        assert((m.accessFlags & 0x0004) != 0, "isJvmProtected must be true for 0x0004")
        assert(!((m.accessFlags & 0x0001) != 0))
        assert(!((m.accessFlags & 0x0002) != 0))
        assert(!((m.accessFlags & 0x0008) != 0))
        assert(!((m.accessFlags & 0x0010) != 0))
        succeed
    }

end JavaMetadataPredicateTest
