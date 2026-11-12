package uuidclient {

    import scala.compiletime.testing.typeChecks

    object UUIDOpacityEvidence:
        val tupleAssignmentCompiles: Boolean =
            typeChecks("val value: kyo.UUID = (0L, 0L)")

        val tupleMemberAccessCompiles: Boolean =
            typeChecks("kyo.UUID.nil._1")

        val trustedConstructorCompiles: Boolean =
            typeChecks("kyo.UUID.fromLongs(0L, 0L)")

        val plainAsV7Compiles: Boolean =
            typeChecks("val value: kyo.UUID.V7 = kyo.UUID.nil")

        val totalV7TimestampCompiles: Boolean =
            typeChecks("val millis: Long = kyo.UUID.V7.init(1000L, 0, 0L).unixTimestampMillis")
    end UUIDOpacityEvidence
}

package kyo {

    class UUIDTest extends kyo.test.Test[Any]:

        private val canonical = "00112233-4455-6677-8899-aabbccddeeff"

        private def parse(value: String): UUID =
            UUID.parse(value).getOrThrow

        private def bytes(values: Int*): Span[Byte] =
            Span.from(values.map(_.toByte).toArray)

        private def problem(result: Result[UUID.InvalidUUID, UUID]): Maybe[UUID.InvalidProblem] =
            result.failure.map(_.problem)

        private def generatedBytes(sample: Int): Span[Byte] =
            Span.from(Array.tabulate(16)(index => ((sample * 73 + index * 41 + 19) & 0xff).toByte))

        "value" - {
            "stores exactly 128 bits in network byte order" in {
                val id = parse(canonical)
                assert(id.bytes.is(bytes(0x00, 0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77, 0x88, 0x99, 0xaa, 0xbb, 0xcc, 0xdd, 0xee, 0xff)))
            }

            "remains opaque to code outside its defining package, which builds one from its halves through fromLongs" in {
                assert(!uuidclient.UUIDOpacityEvidence.tupleAssignmentCompiles)
                assert(!uuidclient.UUIDOpacityEvidence.tupleMemberAccessCompiles)
                assert(uuidclient.UUIDOpacityEvidence.trustedConstructorCompiles)
            }

            "constructs exact network order from trusted raw halves" in {
                val id = UUID.fromLongs(
                    0x0011223344556677L,
                    0x8899aabbccddeeffL
                )
                assert(id.show == canonical)
                assert(id.bytes.is(bytes(
                    0x00, 0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77,
                    0x88, 0x99, 0xaa, 0xbb, 0xcc, 0xdd, 0xee, 0xff
                )))
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

        "V7" - {
            "init lays out the published RFC 9562 version 7 example" in {
                val id = UUID.V7.init(0x017f22e279b0L, 0xcc3, 0x18c4dc0c0c07398fL)
                assert(id.show == "017f22e2-79b0-7cc3-98c4-dc0c0c07398f")
                assert(id.version == 7)
                assert(id.variant == UUID.Variant.RFC)
                assert(id.unixTimestampMillis == 0x017f22e279b0L)
            }

            "init drops the bits of an argument past its field's width" in {
                assert(UUID.V7.init(-1L, -1, -1L).show == "ffffffff-ffff-7fff-bfff-ffffffffffff")
                assert(UUID.V7.init(0L, 0, 0L).show == "00000000-0000-7000-8000-000000000000")
            }

            "from refines a version 7 UUID and refuses any other version" in {
                assert(UUID.V7.from(parse("01890f5e-a410-7000-8000-000000000000")).map(_.unixTimestampMillis) == Maybe(0x01890f5ea410L))
                assert(UUID.V7.from(parse("01890f5e-a410-4000-8000-000000000000")).isEmpty)
                assert(UUID.V7.from(UUID.nil).isEmpty)
            }

            "is a UUID whose total timestamp is the one the UUID holds" in {
                val id         = UUID.V7.init(1000L, 1, 2L)
                val uuid: UUID = id
                assert(uuid.unixTimestampMillis == Maybe(id.unixTimestampMillis))
                assert(UUID.V7.from(uuid).map(_.show) == Maybe(id.show))
            }

            "is refined only through V7, and its timestamp is total outside its defining package" in {
                assert(!uuidclient.UUIDOpacityEvidence.plainAsV7Compiles)
                assert(uuidclient.UUIDOpacityEvidence.totalV7TimestampCompiles)
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

        "fromHash" - {
            "takes the first 16 bytes of the hash and sets the version and variant" in {
                val hash = Span.from(Array.tabulate[Byte](20)(i => (i * 17 + 3).toByte))
                val uuid = UUID.fromHash(hash, version = 5)
                assert(uuid.version == 5)
                assert(uuid.variant == UUID.Variant.RFC)
                val expected = hash.toArray.take(16)
                expected(6) = ((expected(6) & 0x0f) | 0x50).toByte
                expected(8) = ((expected(8) & 0x3f) | 0x80).toByte
                assert(uuid.bytes.toArray.toSeq == expected.toSeq)
            }

            "leaves the hash unchanged" in {
                val array = Array.tabulate[Byte](32)(_.toByte)
                val copy  = array.clone()
                discard(UUID.fromHash(Span.from(array), version = 8))
                assert(array.sameElements(copy))
            }
        }
    end UUIDTest
}
