package kyo.internal.crypto

import java.nio.charset.StandardCharsets

class Md5Test extends kyo.test.Test[Any]:

    import Md5Test.*

    "the vendored RFC 1321 text matches its MANIFEST digest" in {
        assert(TestVectors.names(set) == Seq("rfc1321.txt"))
        val embedded = TestVectors.text(set, "rfc1321.txt").getBytes(StandardCharsets.UTF_8)
        assert(Hex.encode(Sha256.hash(embedded)) == TestVectors.sha256(set, "rfc1321.txt"))
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
                assert(Hex.encode(Md5.hash(v.message.getBytes(StandardCharsets.US_ASCII))) == v.digest)
            }
        }
    }

    "matches the published digests at the padding boundaries" in {
        // 55 bytes fit one block with the length; 56 and 64 spill into a second block.
        assert(Hex.encode(Md5.hash(Array.fill(55)('a'.toByte))) == "ef1772b6dff9a122358552954ad0df65")
        assert(Hex.encode(Md5.hash(Array.fill(56)('a'.toByte))) == "3b0c8ac703f828b04c6c197006d17218")
        assert(Hex.encode(Md5.hash(Array.fill(64)('a'.toByte))) == "014842d480b571495a4a0363793f7367")
        assert(Hex.encode(Md5.hash(Array.fill(1000000)('a'.toByte))) == "7707d6ae4e027c70eea2a935c2296f21")
    }

    "leaves the input unchanged and answers a fresh array each call" in {
        val input  = "message digest".getBytes(StandardCharsets.US_ASCII)
        val copy   = input.clone()
        val first  = Md5.hash(input)
        val second = Md5.hash(input)
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
