package uuidclient {

    import scala.compiletime.testing.typeChecks

    object UUIDOpacityEvidence:
        val tupleAssignmentCompiles: Boolean =
            typeChecks("val value: kyo.UUID = (0L, 0L)")

        val tupleMemberAccessCompiles: Boolean =
            typeChecks("kyo.UUID.nil._1")
    end UUIDOpacityEvidence
}

package kyo {

    import java.nio.charset.StandardCharsets

    class UUIDTest extends kyo.test.Test[Any]:

        private val canonical = "00112233-4455-6677-8899-aabbccddeeff"
        private val dns       = parse("6ba7b810-9dad-11d1-80b4-00c04fd430c8")

        private def parse(value: String): UUID =
            UUID.parse(value).getOrThrow

        private def bytes(values: Int*): Span[Byte] =
            Span.from(values.map(_.toByte).toArray)

        private def utf8(value: String): Span[Byte] =
            Span.from(value.getBytes(StandardCharsets.UTF_8))

        private def problem(result: Result[UUID.InvalidUUID, UUID]): Maybe[UUID.InvalidProblem] =
            result.failure.map(_.problem)

        private def generatedBytes(sample: Int): Span[Byte] =
            Span.from(Array.tabulate(16)(index => ((sample * 73 + index * 41 + 19) & 0xff).toByte))

        "value" - {
            "stores exactly 128 bits in network byte order" in {
                val id = parse(canonical)
                assert(id.bytes.is(bytes(0x00, 0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77, 0x88, 0x99, 0xaa, 0xbb, 0xcc, 0xdd, 0xee, 0xff)))
            }

            "remains opaque to code outside its defining package" in {
                assert(!uuidclient.UUIDOpacityEvidence.tupleAssignmentCompiles)
                assert(!uuidclient.UUIDOpacityEvidence.tupleMemberAccessCompiles)
            }

            "provides an all-zero nil value" in {
                assert(UUID.nil.show == "00000000-0000-0000-0000-000000000000")
                assert(UUID.nil.bytes.is(Span.fill(16)(0.toByte)))
            }

            "provides an all-one max value" in {
                assert(UUID.max.show == "ffffffff-ffff-ffff-ffff-ffffffffffff")
                assert(UUID.max.bytes.is(Span.fill(16)(0xff.toByte)))
            }

            "renders canonical text in lowercase" in {
                assert(parse("550E8400-E29B-41D4-A716-446655440000").show == "550e8400-e29b-41d4-a716-446655440000")
            }

            "compares equal values by all 128 bits" in {
                val first  = parse(canonical)
                val second = UUID.fromBytes(first.bytes).getOrThrow
                assert(first == second)
                assert(first.compare(second) == 0)
                assert(summon[Ordering[UUID]].compare(first, second) == 0)
            }

            "orders the most significant bytes as unsigned values" in {
                val beforeSignBit = parse("7fffffff-ffff-ffff-ffff-ffffffffffff")
                val afterSignBit  = parse("80000000-0000-0000-0000-000000000000")
                assert(beforeSignBit.compare(afterSignBit) < 0)
                assert(summon[Ordering[UUID]].compare(beforeSignBit, afterSignBit) < 0)
            }

            "orders the least significant bytes as unsigned values" in {
                val beforeSignBit = parse("00000000-0000-0000-7fff-ffffffffffff")
                val afterSignBit  = parse("00000000-0000-0000-8000-000000000000")
                assert(beforeSignBit.compare(afterSignBit) < 0)
                assert(summon[Ordering[UUID]].compare(beforeSignBit, afterSignBit) < 0)
            }

            "reports the version nibble" in {
                assert(parse("00112233-4455-a677-8899-aabbccddeeff").version == 10)
            }

            "recognizes every defined variant bit pattern" in {
                assert(parse("00000000-0000-0000-0000-000000000000").variant == UUID.Variant.NCS)
                assert(parse("00000000-0000-0000-8000-000000000000").variant == UUID.Variant.RFC)
                assert(parse("00000000-0000-0000-c000-000000000000").variant == UUID.Variant.Microsoft)
                assert(parse("00000000-0000-0000-e000-000000000000").variant == UUID.Variant.Future)
            }

            "does not expose a logical timestamp for non-version-7 values" in {
                assert(parse("01890f5e-a410-4000-8000-000000000000").unixTimestampMillis == Absent)
            }

            "extracts the 48-bit logical timestamp from version-7 values" in {
                assert(parse("01890f5e-a410-7000-8000-000000000000").unixTimestampMillis == Maybe(0x01890f5ea410L))
            }
        }

        "parsing" - {
            "parses canonical text to the exact value" in {
                assert(UUID.parse(canonical).getOrThrow.bytes.is(bytes(
                    0x00, 0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77, 0x88, 0x99, 0xaa, 0xbb, 0xcc, 0xdd, 0xee, 0xff
                )))
            }

            "accepts uppercase canonical text" in {
                assert(UUID.parse(canonical.toUpperCase(java.util.Locale.ROOT)).getOrThrow == parse(canonical))
            }

            "reports the exact problem for bad text length" in {
                assert(problem(UUID.parse("00112233-4455-6677-8899-aabbccddee")) == Maybe(UUID.InvalidProblem.TextLength(34)))
            }

            "reports the exact problem for braced text" in {
                assert(problem(UUID.parse(s"{$canonical}")) == Maybe(UUID.InvalidProblem.TextLength(38)))
            }

            "rejects URN text in the canonical parser" in {
                assert(problem(UUID.parse(s"urn:uuid:$canonical")) == Maybe(UUID.InvalidProblem.TextLength(45)))
            }

            "reports the exact problem for a missing separator" in {
                assert(problem(UUID.parse("0011223304455-6677-8899-aabbccddeeff")) == Maybe(UUID.InvalidProblem.Separator(8)))
            }

            "reports the exact problem for a moved separator" in {
                assert(problem(UUID.parse("0011223-34455-6677-8899-aabbccddeeff")) == Maybe(UUID.InvalidProblem.HexDigit(7, '-')))
            }

            "reports the exact problem for a non-hex character" in {
                assert(problem(UUID.parse("g0112233-4455-6677-8899-aabbccddeeff")) == Maybe(UUID.InvalidProblem.HexDigit(0, 'g')))
            }

            "parses a standard UUID URN payload" in {
                assert(UUID.parseUrn(s"urn:uuid:$canonical").getOrThrow == parse(canonical))
            }

            "reports the exact problem for an invalid URN prefix" in {
                assert(problem(UUID.parseUrn(s"uuid:$canonical")) == Maybe(UUID.InvalidProblem.UrnPrefix))
            }

            "reports canonical payload problems when parsing a URN" in {
                assert(problem(UUID.parseUrn("urn:uuid:0011223304455-6677-8899-aabbccddeeff")) == Maybe(UUID.InvalidProblem.Separator(8)))
            }

            "constructs the exact value from 16 network-order bytes" in {
                val input = bytes(0x00, 0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77, 0x88, 0x99, 0xaa, 0xbb, 0xcc, 0xdd, 0xee, 0xff)
                assert(UUID.fromBytes(input).getOrThrow.show == canonical)
            }

            "reports the exact problem for too few bytes" in {
                assert(problem(UUID.fromBytes(Span.fill(15)(0.toByte))) == Maybe(UUID.InvalidProblem.ByteLength(15)))
            }

            "reports the exact problem for too many bytes" in {
                assert(problem(UUID.fromBytes(Span.fill(17)(0.toByte))) == Maybe(UUID.InvalidProblem.ByteLength(17)))
            }

            "round-trips generated values through canonical text" in {
                (0 until 128).foreach { sample =>
                    val id     = UUID.fromBytes(generatedBytes(sample)).getOrThrow
                    val parsed = UUID.parse(id.show).getOrThrow
                    assert(parsed == id)
                    assert(parsed.show == id.show)
                }
            }

            "round-trips generated values through network-order bytes" in {
                (0 until 128).foreach { sample =>
                    val input = generatedBytes(sample)
                    val id    = UUID.fromBytes(input).getOrThrow
                    assert(id.bytes.is(input))
                    assert(UUID.fromBytes(id.bytes).getOrThrow == id)
                }
            }
        }

        "deterministic construction" - {
            "matches the RFC version-5 DNS namespace vector" in {
                assert(UUID.v5(dns, utf8("www.example.com")).show == "2ed6657d-e927-568b-95e1-2665a8aea6a2")
            }

            "sets the version-5 nibble" in {
                assert(UUID.v5(dns, utf8("www.example.com")).version == 5)
            }

            "sets the RFC variant on version-5 values" in {
                assert(UUID.v5(dns, utf8("www.example.com")).variant == UUID.Variant.RFC)
            }

            "changes version-5 output when the namespace bytes change" in {
                val otherNamespace = parse("6ba7b811-9dad-11d1-80b4-00c04fd430c8")
                assert(UUID.v5(dns, utf8("www.example.com")) != UUID.v5(otherNamespace, utf8("www.example.com")))
            }

            "changes version-5 output when the exact name bytes change" in {
                assert(UUID.v5(dns, utf8("www.example.com")) != UUID.v5(dns, utf8("www.example.coM")))
            }

            "matches the Kyo version-8 SHA-256 profile snapshot" in {
                assert(UUID.v8Sha256(dns, utf8("kyo")).show == "40b14c55-e8a6-81ec-befd-7dcf39275a9b")
            }

            "sets the version-8 nibble" in {
                assert(UUID.v8Sha256(dns, utf8("kyo")).version == 8)
            }

            "sets the RFC variant on version-8 values" in {
                assert(UUID.v8Sha256(dns, utf8("kyo")).variant == UUID.Variant.RFC)
            }

            "returns equal version-8 values for equal inputs" in {
                assert(UUID.v8Sha256(dns, utf8("kyo")) == UUID.v8Sha256(dns, utf8("kyo")))
            }

            "changes version-8 output when the namespace bytes change" in {
                val otherNamespace = parse("6ba7b811-9dad-11d1-80b4-00c04fd430c8")
                assert(UUID.v8Sha256(dns, utf8("kyo")) != UUID.v8Sha256(otherNamespace, utf8("kyo")))
            }

            "changes version-8 output when the exact name bytes change" in {
                assert(UUID.v8Sha256(dns, utf8("kyo")) != UUID.v8Sha256(dns, utf8("Kyo")))
            }
        }
    end UUIDTest
}
