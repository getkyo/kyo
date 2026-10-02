package kyo.crypto

import java.nio.charset.StandardCharsets
import kyo.*
import kyo.internal.crypto.TestVectors
import kyo.internal.crypto.TestVectorsCavp

class Sha256Test extends kyo.test.Test[Any]:

    "every SHA-256 the JDK computed in jdk-differential, at the block and length-field edges and random sizes" in {
        val cases = kyo.internal.crypto.TestVectorsJdk.of("sha256")
        assert(cases.size == 45)
        cases.foreach(c =>
            assert(TestVectorsCavp.hex(Sha256.hashArray(c.bytes("msg"))) == c.value("out"), s"${c.bytes("msg").length} bytes")
        )
    }

    "the vendored SHA-256 CAVP files match their MANIFEST digests" in {
        val names = TestVectors.names(TestVectorsCavp.set).filter(_.startsWith("SHA256"))
        assert(names == Seq("SHA256ShortMsg.rsp", "SHA256LongMsg.rsp", "SHA256Monte.rsp"))
        names.foreach { name =>
            val embedded = TestVectors.text(TestVectorsCavp.set, name).getBytes(StandardCharsets.UTF_8)
            assert(Hex.encode(Sha256.hash(Span.from(embedded))) == TestVectors.sha256(TestVectorsCavp.set, name))
        }
    }

    "NIST CAVP" - {
        "SHA256ShortMsg: every message from 0 to 64 bytes" in {
            val vectors = TestVectorsCavp.messageVectors("SHA256ShortMsg.rsp")
            assert(vectors.map(_.bits) == (0 to 512 by 8))
            vectors.foreach { v =>
                assert(v.message.length * 8 == v.bits)
                assert(Hex.encode(Sha256.hash(Span.from(v.message))) == v.digest)
            }
        }

        "SHA256LongMsg" in {
            val vectors = TestVectorsCavp.messageVectors("SHA256LongMsg.rsp")
            assert(vectors.size == 64)
            vectors.foreach { v =>
                assert(v.message.length * 8 == v.bits)
                assert(Hex.encode(Sha256.hash(Span.from(v.message))) == v.digest)
            }
        }

        "SHA256Monte: 100 checkpoints of 1000 chained digests" in {
            val monte = TestVectorsCavp.monteCarlo("SHA256Monte.rsp")
            assert(monte.checkpoints.size == 100)
            assert(TestVectorsCavp.monteCarloCheckpoints(monte.seed, 100, Sha256.hashAll).map(hex) == monte.checkpoints)
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
            assert(hex(Sha256.hashArray(Array.emptyByteArray)) == "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855")
        }

        "matches the published short-input digest" in {
            assert(hex(Sha256.hashArray(utf8("abc"))) == "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")
        }

        "matches the published padding-boundary digest" in {
            val input = "abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq"
            assert(input.length == 56)
            assert(hex(Sha256.hashArray(utf8(input))) == "248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1")
        }

        "matches the published multi-block digest" in {
            val input =
                "abcdefghbcdefghicdefghijdefghijkefghijklfghijklmghijklmnhijklmno" +
                    "ijklmnopjklmnopqklmnopqrlmnopqrsmnopqrstnopqrstu"
            assert(input.length == 112)
            assert(hex(Sha256.hashArray(utf8(input))) == "cf5b16a778af8380036ce59e7b0492370b249b11e8f07a51afac45037afee9d1")
        }

        "matches the published one-million-a digest" in {
            val input = Array.fill(1000000)('a'.toByte)
            assert(hex(Sha256.hashArray(input)) == "cdc76e5c9914fb9281a1c7e284d73e67f1809a48a497200e046d39ccc7112cd0")
        }

        "matches exact digests for repeated-character inputs at the padding boundaries" in {
            assert(hex(Sha256.hashArray(Array.fill(55)('y'.toByte))) == "fb66d40c3bfff05b0d5af8612d0abfbfacc6f5f26c330bc7ad634f1f44bc20ad")
            assert(hex(Sha256.hashArray(Array.fill(56)('z'.toByte))) == "c66a5b692b9a20229733ef8b87cfec52679c86a0c0245643484c46d4dcd82afa")
            assert(hex(Sha256.hashArray(Array.fill(64)('x'.toByte))) == "7ce100971f64e7001e8fe5a51973ecdfe1ced42befe7ee8d5fd6219506b5393c")
        }

        "matches exact digests for generated boundary inputs" in {
            val vectors = Seq(
                1   -> "4a64a107f0cb32536e5bce6c98c393db21cca7f4ea187ba8c4dca8b51d4ea80a",
                55  -> "7ce4e0e40de7324a02e7cf0cd700dd5022e0f0013aa1851001b6a4e911372e2e",
                56  -> "c4d6a4c722c6410c3f618c5d22e2624dfef61c1d5ae0f8f9685dd96183aa382e",
                63  -> "55cb1d1ce5c16add76433b2057e0af5802b2b00f6459e2919942792db7ab76eb",
                64  -> "3ea97ec766b8247739939247b4d4cb362cf13c100deb0cc2ba5391f762023852",
                65  -> "29f9271ca7d028ab5612ea14e8bcd7057a4df39f167fe9fd999360df4295afc9",
                127 -> "50e2381c1f398559773e06a3685821477dff4baee91356bdc457c664f9f98c6a",
                128 -> "3b35116c160c0ffdaf1287960af39caf2760811b02a36e3bd5294bdd61eea9a9",
                129 -> "ef7a8d95e4cc02c7b88886d1114ce8fecb1fd2a2a45c076edb8b060013723fac"
            )
            vectors.foreach { case (size, expected) =>
                assert(hex(Sha256.hashArray(generated(size))) == expected)
            }
        }

        "matches one-shot hashing when input is split across chunks" in {
            val input  = generated(257)
            val chunks = Chunk(input.take(55), input.slice(55, 64), Array.emptyByteArray, input.slice(64, 129), input.drop(129))
            assert(Sha256.hashArrays(chunks).sameElements(Sha256.hashArray(input)))
        }

        "hashes an empty sequence of parts as the empty input" in {
            assert(hex(Sha256.hashArrays(Chunk.empty)) == "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855")
        }

        "returns the same digest for the same generated input" in {
            val input = generated(257)
            assert(Sha256.hashArray(input).sameElements(Sha256.hashArray(input)))
        }

        "leaves generated input bytes unchanged" in {
            val input    = generated(257)
            val original = input.clone()
            Sha256.hashArray(input)
            assert(input.sameElements(original))
        }
    }

    "the Span surface" - {
        "hash digests the same bytes as the array tier" in {
            val input = generated(257)
            assert(Sha256.hash(Span.from(input)).toArray.toSeq == Sha256.hashArray(input).toSeq)
            assert(hex(Sha256.hash(Span.empty[Byte]).toArray) == "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855")
        }

        "hashAll digests the concatenation of the parts" in {
            val input = generated(257)
            val parts = Chunk(Span.from(input.take(55)), Span.empty[Byte], Span.from(input.slice(55, 200)), Span.from(input.drop(200)))
            assert(Sha256.hashAll(parts).toArray.toSeq == Sha256.hashArray(input).toSeq)
            assert(Sha256.hashAll(Chunk.empty).toArray.toSeq == Sha256.hash(Span.empty[Byte]).toArray.toSeq)
        }

        "the digest is a fresh value the input does not alias" in {
            val input = Span.from(generated(64))
            val first = Sha256.hash(input)
            assert(first.toArray.toSeq == Sha256.hash(input).toArray.toSeq)
            assert(first.size == 32)
        }
    }
end Sha256Test
