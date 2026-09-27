package kyo.internal.crypto

import java.nio.charset.StandardCharsets

class Pbkdf2Test extends kyo.test.Test[Any]:

    import Pbkdf2Test.*

    "the vendored RFC 7914 text matches its MANIFEST digest" in {
        assert(TestVectors.names(set) == Seq("rfc7914.txt"))
        val embedded = TestVectors.text(set, "rfc7914.txt").getBytes(StandardCharsets.UTF_8)
        assert(Hex.encode(Sha256.hash(embedded)) == TestVectors.sha256(set, "rfc7914.txt"))
    }

    "RFC 7914 section 11" - {
        "reads the two vectors out of the RFC text" in {
            assert(vectors.map(v => (v.password, v.salt, v.iterations, v.keyLength)) == Seq(
                ("passwd", "salt", 1, 64),
                ("Password", "NaCl", 80000, 64)
            ))
            assert(vectors.forall(_.derived.length == 128))
        }

        "every vector derives the published key" in {
            vectors.foreach { v =>
                val derived = Pbkdf2.hmacSha256(ascii(v.password), ascii(v.salt), v.iterations, v.keyLength)
                assert(Hex.encode(derived) == v.derived)
            }
        }
    }

    "RFC 7677 section 3's SCRAM parameters derive the salted password" in {
        val salt = kyo.Base64.decodeOrThrow("W22ZaJ0SNY7soEsUEjb6gQ==").toArray
        assert(Hex.encode(Pbkdf2.hmacSha256(ascii("pencil"), salt, 4096, 32)) ==
            "c4a49510323ab4f952cac1fa99441939e78ea74d6be81ddf7096e87513dc615d")
    }

    "a key longer than one digest is the concatenation of the numbered blocks" in {
        val salt  = kyo.Base64.decodeOrThrow("W22ZaJ0SNY7soEsUEjb6gQ==").toArray
        val short = Pbkdf2.hmacSha256(ascii("pencil"), salt, 4096, 32)
        val long  = Pbkdf2.hmacSha256(ascii("pencil"), salt, 4096, 48)
        assert(long.length == 48)
        assert(long.take(32).sameElements(short))
        // With one iteration block 2 is exactly HMAC(password, salt || 00 00 00 02), which a 32-byte derivation never computes.
        val once = Pbkdf2.hmacSha256(ascii("pencil"), salt, 1, 64)
        assert(once.take(32).sameElements(Hmac.sha256(ascii("pencil"), salt ++ Array[Byte](0, 0, 0, 1))))
        assert(once.drop(32).sameElements(Hmac.sha256(ascii("pencil"), salt ++ Array[Byte](0, 0, 0, 2))))
    }

    "a password longer than the 64-byte block is hashed first, as HMAC requires" in {
        val password = Array.fill[Byte](100)(0x61)
        val salt     = ascii("salt")
        val direct   = Pbkdf2.hmacSha256(password, salt, 3, 32)
        val hashed   = Pbkdf2.hmacSha256(Sha256.hash(password), salt, 3, 32)
        assert(direct.sameElements(hashed))
        // One iteration is exactly HMAC(password, salt || 00 00 00 01).
        assert(Pbkdf2.hmacSha256(password, salt, 1, 32).sameElements(Hmac.sha256(password, salt ++ Array[Byte](0, 0, 0, 1))))
    }

    "leaves the password and salt unchanged" in {
        val password = ascii("pencil")
        val salt     = ascii("salt")
        val (p, s)   = (password.clone(), salt.clone())
        kyo.discard(Pbkdf2.hmacSha256(password, salt, 2, 40))
        assert(password.sameElements(p) && salt.sameElements(s))
    }

end Pbkdf2Test

object Pbkdf2Test:

    val set = "ietf-rfc7914"

    def ascii(text: String): Array[Byte] = text.getBytes(StandardCharsets.US_ASCII)

    final case class Vector(password: String, salt: String, iterations: Int, keyLength: Int, derived: String)

    /** The `PBKDF2-HMAC-SHA-256 (P="...", S="...", c=..., dkLen=...) =` records of section 11, each followed by hex lines up to a blank
      * line.
      */
    lazy val vectors: Seq[Vector] =
        val text    = TestVectors.text(set, "rfc7914.txt")
        val start   = text.indexOf("\n11.  Test Vectors for PBKDF2 with HMAC-SHA-256")
        val end     = text.indexOf("\n12.  ", start)
        val lines   = text.substring(start, end).linesIterator.toVector
        val headers = lines.indices.filter(i => lines(i).trim.startsWith("PBKDF2-HMAC-SHA-256 (P="))
        headers.map { i =>
            val header                        = lines(i).trim + " " + lines(i + 1).trim
            def quoted(label: String): String =
                val at = header.indexOf(label + "=\"")
                require(at >= 0, s"no $label in $header")
                header.substring(at + label.length + 2, header.indexOf('"', at + label.length + 2))
            end quoted
            def number(label: String): Int =
                val at = header.indexOf(label + "=")
                require(at >= 0, s"no $label in $header")
                header.substring(at + label.length + 1).takeWhile(_.isDigit).toInt
            end number
            val hex = lines.drop(i + 2).takeWhile(_.trim.nonEmpty).map(_.replaceAll("\\s", "")).mkString
            Vector(quoted("P"), quoted("S"), number("c"), number("dkLen"), hex)
        }
    end vectors

end Pbkdf2Test
