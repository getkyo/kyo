package kyo.crypto

import java.nio.charset.StandardCharsets
import kyo.*
import kyo.internal.crypto.TestVectors

class Md5Test extends kyo.test.Test[Any]:

    "every MD5 the JDK computed in jdk-differential, at the block and length-field edges and random sizes" in {
        val cases = kyo.internal.crypto.TestVectorsJdk.of("md5")
        assert(cases.size == 45)
        cases.foreach { c =>
            val out = Md5.hashArray(c.bytes("msg")).map(b => f"${b & 0xff}%02x").mkString
            assert(out == c.value("out"), s"${c.bytes("msg").length} bytes")
        }
    }

    import Md5Test.*

    "the vendored RFC 1321 text matches its MANIFEST digest" in {
        assert(TestVectors.names(set) == Seq("rfc1321.txt"))
        val embedded = TestVectors.text(set, "rfc1321.txt").getBytes(StandardCharsets.UTF_8)
        assert(Hex.encode(Sha256.hash(Span.from(embedded))) == TestVectors.sha256(set, "rfc1321.txt"))
    }

    "RFC 1321 appendix A.5" - {
        "reads the seven messages out of the RFC text" in {
            assert(vectors.map(_.message) == Seq(
                "",
                "a",
                "abc",
                "message digest",
                "abcdefghijklmnopqrstuvwxyz",
                "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789",
                "12345678901234567890123456789012345678901234567890123456789012345678901234567890"
            ))
            assert(vectors.forall(_.digest.length == 32))
        }

        "every message digests to the published value" in {
            vectors.foreach { v =>
                assert(Hex.encode(Md5.hash(Span.from(v.message.getBytes(StandardCharsets.US_ASCII)))) == v.digest)
            }
        }
    }

    "matches the published digests at the padding boundaries" in {
        // 55 bytes fit one block with the length; 56 and 64 spill into a second block.
        assert(Hex.encodeArray(Md5.hashArray(Array.fill(55)('a'.toByte))) == "ef1772b6dff9a122358552954ad0df65")
        assert(Hex.encodeArray(Md5.hashArray(Array.fill(56)('a'.toByte))) == "3b0c8ac703f828b04c6c197006d17218")
        assert(Hex.encodeArray(Md5.hashArray(Array.fill(64)('a'.toByte))) == "014842d480b571495a4a0363793f7367")
        assert(Hex.encodeArray(Md5.hashArray(Array.fill(1000000)('a'.toByte))) == "7707d6ae4e027c70eea2a935c2296f21")
    }

    "hashAll digests the concatenation of the parts" in {
        val message = "message digest".getBytes(StandardCharsets.US_ASCII)
        val parts   = Chunk(Span.from(message.take(7)), Span.empty[Byte], Span.from(message.drop(7)))
        assert(Hex.encode(Md5.hashAll(parts)) == "f96b697d7cb7938d525a2f31aaf161d0")
        assert(Hex.encode(Md5.hashAll(Chunk.empty)) == "d41d8cd98f00b204e9800998ecf8427e")
    }

    "matches one-shot hashing when input is split across chunks" in {
        val input  = Array.tabulate[Byte](257)(i => (i * 31 + 7).toByte)
        val chunks = Chunk(input.take(55), input.slice(55, 64), Array.emptyByteArray, input.slice(64, 129), input.drop(129))
        assert(Md5.hashArrays(chunks).sameElements(Md5.hashArray(input)))
        assert(Md5.hashArrays(input.grouped(1).toSeq.foldLeft(Chunk.empty[Array[Byte]])(_ :+ _)).sameElements(Md5.hashArray(input)))
    }

    "leaves the input unchanged and answers a fresh array each call" in {
        val input  = "message digest".getBytes(StandardCharsets.US_ASCII)
        val copy   = input.clone()
        val first  = Md5.hashArray(input)
        val second = Md5.hashArray(input)
        assert(input.sameElements(copy))
        assert(first.sameElements(second))
        assert(!(first eq second))
    }

end Md5Test

object Md5Test:

    val set = "ietf-rfc1321"

    final case class Vector(message: String, digest: String)

    /** The `MD5 ("<message>") = <digest>` lines of section A.5, two of which the RFC wraps across lines. */
    lazy val vectors: Seq[Vector] =
        val text   = TestVectors.text(set, "rfc1321.txt")
        val start  = text.indexOf("MD5 test suite:")
        val end    = text.indexOf("Security Considerations", start)
        val joined = text.substring(start, end).linesIterator.mkString("")
        joined.split("MD5 \\(\"").toSeq.drop(1).map { entry =>
            val close = entry.indexOf("\") =")
            require(close >= 0, s"unterminated MD5 entry: $entry")
            Vector(entry.substring(0, close), entry.substring(close + 4).trim.take(32))
        }
    end vectors

end Md5Test
