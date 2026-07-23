package kyo

class UUIDTest extends kyo.test.Test[Any]:
    "value" - {
        "UUID-VAL-001 exact 128 bits" in { assert(UUID.nil.bytes.length == 16) }
        "UUID-VAL-003 nil exists" in { assert(UUID.nil.show == "00000000-0000-0000-0000-000000000000") }
        "UUID-VAL-004 max exists" in { assert(UUID.max.show == "ffffffff-ffff-ffff-ffff-ffffffffffff") }
        "UUID-VAL-005 canonical lowercase show" in {
            val id = UUID.parse("550E8400-E29B-41D4-A716-446655440000").getOrThrow
            assert(id.show == "550e8400-e29b-41d4-a716-446655440000")
        }
    }

    "parsing" - {
        "UUID-PRS-001 parse canonical" in { assert(UUID.parse("550e8400-e29b-41d4-a716-446655440000").isSuccess) }
        "UUID-PRS-004 reject braces" in { assert(UUID.parse("{550e8400-e29b-41d4-a716-446655440000}").isFailure) }
        "UUID-PRS-006 parse URN via parseUrn" in { assert(UUID.parseUrn("urn:uuid:550e8400-e29b-41d4-a716-446655440000").isSuccess) }
        "UUID-PRS-008 reject non-16-byte input" in { assert(UUID.fromBytes(Span.from(Array.fill[Byte](15)(0))).isFailure) }
    }
end UUIDTest
