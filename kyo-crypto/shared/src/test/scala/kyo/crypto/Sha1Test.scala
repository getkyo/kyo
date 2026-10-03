package kyo.crypto

import java.nio.charset.StandardCharsets
import kyo.*
import kyo.internal.crypto.TestVectors
import kyo.internal.crypto.TestVectorsCavp

class Sha1Test extends kyo.test.Test[Any]:

    "every SHA-1 the JDK computed in jdk-differential, at the block and length-field edges and random sizes" in {
        val cases = kyo.internal.crypto.TestVectorsJdk.of("sha1")
        assert(cases.size == 45)
        cases.foreach(c => assert(TestVectorsCavp.hex(Sha1.hashArray(c.bytes("msg"))) == c.value("out"), s"${c.bytes("msg").length} bytes"))
    }

    "the vendored SHA-1 CAVP files match their MANIFEST digests" in {
        val names = TestVectors.names(TestVectorsCavp.set).filter(_.startsWith("SHA1"))
        assert(names == Seq("SHA1ShortMsg.rsp", "SHA1LongMsg.rsp", "SHA1Monte.rsp"))
        names.foreach { name =>
            val embedded = TestVectors.text(TestVectorsCavp.set, name).getBytes(StandardCharsets.UTF_8)
            assert(Hex.encode(Sha256.hash(Span.from(embedded))) == TestVectors.sha256(TestVectorsCavp.set, name))
        }
    }

    "NIST CAVP" - {
        "SHA1ShortMsg: every message from 0 to 64 bytes" in {
            val vectors = TestVectorsCavp.messageVectors("SHA1ShortMsg.rsp")
            assert(vectors.map(_.bits) == (0 to 512 by 8))
            vectors.foreach { v =>
                assert(v.message.length * 8 == v.bits)
                assert(Hex.encode(Sha1.hash(Span.from(v.message))) == v.digest)
            }
        }

        "SHA1LongMsg" in {
            val vectors = TestVectorsCavp.messageVectors("SHA1LongMsg.rsp")
            assert(vectors.size == 64)
            vectors.foreach { v =>
                assert(v.message.length * 8 == v.bits)
                assert(Hex.encode(Sha1.hash(Span.from(v.message))) == v.digest)
            }
        }

        "SHA1Monte: 100 checkpoints of 1000 chained digests" in {
            val monte = TestVectorsCavp.monteCarlo("SHA1Monte.rsp")
            assert(monte.checkpoints.size == 100)
            assert(TestVectorsCavp.monteCarloCheckpoints(monte.seed, 100, Sha1.hashAll).map(hex) == monte.checkpoints)
        }
    }

    private def utf8(value: String): Array[Byte] =
        value.getBytes(StandardCharsets.UTF_8)

    private def hex(bytes: Array[Byte]): String =
        val result = new StringBuilder(bytes.length * 2)
        bytes.foreach { byte =>
            result.append(f"${byte & 0xff}%02x")
        }
        result.result()
    end hex

    private def generated(size: Int): Array[Byte] =
        Array.tabulate(size)(index => ((index * 31 + size * 17) & 0xff).toByte)

    "hashing" - {
        "matches the published empty-input digest" in {
            assert(hex(Sha1.hashArray(Array.emptyByteArray)) == "da39a3ee5e6b4b0d3255bfef95601890afd80709")
        }

        "matches the published short-input digest" in {
            assert(hex(Sha1.hashArray(utf8("abc"))) == "a9993e364706816aba3e25717850c26c9cd0d89d")
        }

        "matches additional published and padding digests" in {
            val vectors = Seq(
                Array('a'.toByte) ->
                    "86f7e437faa5a7fce15d1ddcb9eaeaea377667b8",
                utf8("The quick brown fox jumps over the lazy dog") ->
                    "2fd4e1c67a2d28fced849ee1bb76e7391b93eb12",
                Array.fill[Byte](55)('A'.toByte) ->
                    "5021b3d42aa093bffc34eedd7a1455f3624bc552",
                Array.fill[Byte](10)('B'.toByte) ->
                    "2b88ae576b03a7c136ecca94de57f500973fee76",
                Array.fill[Byte](60)('D'.toByte) ->
                    "a288bd3300951e43722aa150d2e959ef871f24af",
                utf8(
                    "abcdefghbcdefghicdefghijdefghijkefghijklfghijklmghijklmnhijklmno" +
                        "ijklmnopjklmnopqklmnopqrlmnopqrsmnopqrstnopqrstu"
                ) ->
                    "a49b2446a02c645bf419f995b67091253a04a259"
            )
            vectors.foreach { case (input, expected) =>
                assert(hex(Sha1.hashArray(input)) == expected)
            }
        }

        "matches the published padding-boundary digest" in {
            val input = "abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq"
            assert(input.length == 56)
            assert(hex(Sha1.hashArray(utf8(input))) == "84983e441c3bd26ebaae4aa1f95129e5e54670f1")
        }

        "matches the published million-byte multi-block digest" in {
            val input = Array.fill[Byte](1_000_000)('a'.toByte)
            assert(hex(Sha1.hashArray(input)) == "34aa973cd4c4daa4f61eeb2bdbad27316534016f")
        }

        "matches exact digests for generated boundary inputs" in {
            val vectors = Seq(
                1   -> "a8abd012eb59b862bf9bc1ea443d2f35a1a2e222",
                55  -> "7f6be3aef551f6fb2641a7166fcf492996f95dc1",
                56  -> "53f25c02f30e17a95781e26a110650c8a7abe86b",
                63  -> "461918a31171942e745193cb4635818b3007a302",
                64  -> "b2f62e382014bec909954fbf86a19cb0c5cfe4bf",
                65  -> "6f56f30ef2dadf7d0bf809c501fba9294c824d20",
                127 -> "7a5b9627cdd6325b0187bd5e05b1a303756899a5",
                128 -> "c131d8e40b6a8b7236d20393a17220bec5981461",
                129 -> "0a39291a384f60dfbc5ebb44231c29302f78da7b"
            )
            vectors.foreach { case (size, expected) =>
                assert(hex(Sha1.hashArray(generated(size))) == expected)
            }
        }

        "matches one-shot hashing when input is split across chunks" in {
            val input  = generated(257)
            val chunks = Chunk(input.take(55), input.slice(55, 64), Array.emptyByteArray, input.slice(64, 129), input.drop(129))
            assert(Sha1.hashArrays(chunks).sameElements(Sha1.hashArray(input)))
        }

        "hashes an empty sequence of parts as the empty input" in {
            assert(hex(Sha1.hashArrays(Chunk.empty)) == "da39a3ee5e6b4b0d3255bfef95601890afd80709")
        }

        "returns the same digest for the same generated input" in {
            val input = generated(257)
            assert(Sha1.hashArray(input).sameElements(Sha1.hashArray(input)))
        }

        "leaves generated input bytes unchanged" in {
            val input    = generated(257)
            val original = input.clone()
            Sha1.hashArray(input)
            assert(input.sameElements(original))
        }
    }

    "the Span surface" - {
        "hash digests the same bytes as the array tier" in {
            val input = generated(257)
            assert(Sha1.hash(Span.from(input)).toArray.toSeq == Sha1.hashArray(input).toSeq)
            assert(hex(Sha1.hash(Span.empty[Byte]).toArray) == "da39a3ee5e6b4b0d3255bfef95601890afd80709")
        }

        "hashAll digests the concatenation of the parts" in {
            val input = generated(257)
            val parts = Chunk(Span.from(input.take(55)), Span.empty[Byte], Span.from(input.slice(55, 200)), Span.from(input.drop(200)))
            assert(Sha1.hashAll(parts).toArray.toSeq == Sha1.hashArray(input).toSeq)
            assert(Sha1.hashAll(Chunk.empty).toArray.toSeq == Sha1.hash(Span.empty[Byte]).toArray.toSeq)
        }
    }
end Sha1Test
