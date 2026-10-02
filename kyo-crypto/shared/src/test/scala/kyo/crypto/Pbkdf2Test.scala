package kyo.crypto

import java.nio.charset.StandardCharsets
import kyo.*
import kyo.internal.crypto.TestVectors

class Pbkdf2Test extends kyo.test.Test[Any]:

    "every PBKDF2-HMAC-SHA-256 key the JDK derived in jdk-differential, from one iteration to 4096 and lengths across block boundaries" in {
        val cases = kyo.internal.crypto.TestVectorsJdk.of("pbkdf2")
        assert(cases.size == 8)
        cases.foreach { c =>
            Pbkdf2.hmacSha256Array(c.bytes("password"), c.bytes("salt"), c.int("iterations"), c.int("length")) match
                case Result.Success(out) =>
                    assert(out.map(b => f"${b & 0xff}%02x").mkString == c.value("out"), s"${c.int("iterations")} iterations")
                case other => fail(s"the JDK derived a key where this module refused: $other")
        }
    }

    import Pbkdf2Test.*

    private def key(result: Result[Pbkdf2.Failure, Array[Byte]]): Array[Byte] = result match
        case Result.Success(bytes) => bytes
        case other                 => throw new IllegalStateException(s"expected a derived key, got $other")

    private def keySpan(result: Result[Pbkdf2.Failure, Span[Byte]]): Span[Byte] = result match
        case Result.Success(bytes) => bytes
        case other                 => throw new IllegalStateException(s"expected a derived key, got $other")

    private def base64(text: String): Array[Byte] = kyo.Base64.decode(text) match
        case Result.Success(bytes) => bytes.toArray
        case other                 => throw new IllegalStateException(s"the literal is not base64: $other")

    "the vendored RFC 7914 text matches its MANIFEST digest" in {
        assert(TestVectors.names(set) == Seq("rfc7914.txt"))
        val embedded = TestVectors.text(set, "rfc7914.txt").getBytes(StandardCharsets.UTF_8)
        assert(Hex.encodeArray(Sha256.hashArray(embedded)) == TestVectors.sha256(set, "rfc7914.txt"))
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
                val derived = key(Pbkdf2.hmacSha256Array(ascii(v.password), ascii(v.salt), v.iterations, v.keyLength))
                assert(Hex.encodeArray(derived) == v.derived)
            }
        }

        "every vector derives the published key through the Span surface" in {
            vectors.foreach { v =>
                val derived = keySpan(Pbkdf2.hmacSha256(Span.from(ascii(v.password)), Span.from(ascii(v.salt)), v.iterations, v.keyLength))
                assert(Hex.encode(derived) == v.derived)
            }
        }
    }

    "the vendored RFC 7677 text matches its MANIFEST digest" in {
        assert(TestVectors.names(scramSet) == Seq("rfc7677.txt"))
        val embedded = TestVectors.text(scramSet, "rfc7677.txt").getBytes(StandardCharsets.UTF_8)
        assert(Hex.encodeArray(Sha256.hashArray(embedded)) == TestVectors.sha256(scramSet, "rfc7677.txt"))
    }

    "RFC 7677 section 3's exchange: the password salted with the announced salt and count proves the client and verifies the server" in {
        val exchange = rfc7677Exchange
        assert(exchange.messages.size == 4)
        assert(exchange.password == "pencil")
        val Seq(clientFirst, serverFirst, clientFinal, serverFinal) = exchange.messages: @unchecked
        val serverAttributes = serverFirst.split(',').map(attribute => attribute.take(1) -> attribute.drop(2)).toMap
        val salt             = base64(serverAttributes("s"))
        val iterations       = serverAttributes("i").toInt
        assert(iterations == 4096)
        val clientFirstBare         = clientFirst.stripPrefix("n,,")
        val clientFinalWithoutProof = clientFinal.substring(0, clientFinal.indexOf(",p="))
        val proof                   = clientFinal.substring(clientFinal.indexOf(",p=") + 3)
        val authMessage             = ascii(clientFirstBare + "," + serverFirst + "," + clientFinalWithoutProof)
        val saltedPassword          = key(Pbkdf2.hmacSha256Array(ascii(exchange.password), salt, iterations, 32))
        val clientKey               = Hmac.sha256Array(saltedPassword, ascii("Client Key"))
        val clientSignature         = Hmac.sha256Array(Sha256.hashArray(clientKey), authMessage)
        val clientProof             = kyo.internal.crypto.Bytes.xor(clientKey, clientSignature)
        assert(kyo.Base64.encode(Span.from(clientProof)) == proof)
        val serverKey       = Hmac.sha256Array(saltedPassword, ascii("Server Key"))
        val serverSignature = Hmac.sha256Array(serverKey, authMessage)
        assert(serverFinal == "v=" + kyo.Base64.encode(Span.from(serverSignature)))
    }

    "a key longer than one digest is the concatenation of the numbered blocks" in {
        val salt  = base64("W22ZaJ0SNY7soEsUEjb6gQ==")
        val short = key(Pbkdf2.hmacSha256Array(ascii("pencil"), salt, 4096, 32))
        val long  = key(Pbkdf2.hmacSha256Array(ascii("pencil"), salt, 4096, 48))
        assert(long.length == 48)
        assert(long.take(32).sameElements(short))
        // With one iteration block 2 is exactly HMAC(password, salt || 00 00 00 02), which a 32-byte derivation never computes.
        val once = key(Pbkdf2.hmacSha256Array(ascii("pencil"), salt, 1, 64))
        assert(once.take(32).sameElements(Hmac.sha256Array(ascii("pencil"), salt ++ Array[Byte](0, 0, 0, 1))))
        assert(once.drop(32).sameElements(Hmac.sha256Array(ascii("pencil"), salt ++ Array[Byte](0, 0, 0, 2))))
    }

    "a password longer than the 64-byte block is hashed first, as HMAC requires" in {
        val password = Array.fill[Byte](100)(0x61)
        val salt     = ascii("salt")
        val direct   = key(Pbkdf2.hmacSha256Array(password, salt, 3, 32))
        val hashed   = key(Pbkdf2.hmacSha256Array(Sha256.hashArray(password), salt, 3, 32))
        assert(direct.sameElements(hashed))
        // One iteration is exactly HMAC(password, salt || 00 00 00 01).
        assert(key(Pbkdf2.hmacSha256Array(password, salt, 1, 32)).sameElements(Hmac.sha256Array(password, salt ++ Array[Byte](0, 0, 0, 1))))
    }

    "Django CVE-2013-1443 class: a 1 MiB password derives the key of its SHA-256 at every count, so it enters only through one hash" in {
        // The cost of a long password cannot be measured without a clock. What is pinned is the structure: the password reaches the
        // derivation only through its hash, and derive computes both pads from it once, before the iteration loop.
        val password = Array.tabulate[Byte](1 << 20)(i => (i * 31).toByte)
        val salt     = ascii("salt")
        Seq(1, 2, 1000).foreach { iterations =>
            assert(key(Pbkdf2.hmacSha256Array(password, salt, iterations, 32)).sameElements(
                key(Pbkdf2.hmacSha256Array(Sha256.hashArray(password), salt, iterations, 32))
            ))
        }
    }

    "a zero key length derives no bytes" in {
        assert(keySpan(Pbkdf2.hmacSha256(Span.from(ascii("pencil")), Span.from(ascii("salt")), 1, 0)).size == 0)
    }

    "leaves the password and salt unchanged" in {
        val password = ascii("pencil")
        val salt     = ascii("salt")
        val (p, s)   = (password.clone(), salt.clone())
        kyo.discard(Pbkdf2.hmacSha256Array(password, salt, 2, 40))
        assert(password.sameElements(p) && salt.sameElements(s))
    }

    "bounds" - {
        def refusal(iterations: Int, keyLength: Int): Maybe[Pbkdf2.Failure] =
            Pbkdf2.hmacSha256(Span.from(ascii("pencil")), Span.from(ascii("salt")), iterations, keyLength).failure

        "an iteration count below 1 is refused with the count offered" in {
            assert(refusal(0, 32) == Maybe(Pbkdf2.Failure.Iterations(0)))
            assert(refusal(-1, 32) == Maybe(Pbkdf2.Failure.Iterations(-1)))
            assert(Pbkdf2.hmacSha256Array(ascii("pencil"), ascii("salt"), 0, 32).failure == Maybe(Pbkdf2.Failure.Iterations(0)))
        }

        "a negative key length is refused with the length offered" in {
            assert(refusal(1, -1) == Maybe(Pbkdf2.Failure.KeyLength(-1)))
        }

        "the iteration count is checked before the key length" in {
            assert(refusal(0, -1) == Maybe(Pbkdf2.Failure.Iterations(0)))
        }
    }

end Pbkdf2Test

object Pbkdf2Test:

    val set = "ietf-rfc7914"

    val scramSet = "ietf-rfc7677"

    def ascii(text: String): Array[Byte] = text.getBytes(StandardCharsets.US_ASCII)

    /** The SCRAM-SHA-256 example of RFC 7677 section 3: the password the prose names and the four messages, `C:` and `S:` lines with
      * their continuation lines joined.
      */
    final case class Exchange(password: String, messages: Seq[String])

    lazy val rfc7677Exchange: Exchange =
        val text     = TestVectors.text(scramSet, "rfc7677.txt")
        val start    = text.indexOf("This is a simple example")
        val end      = text.indexOf("Security Considerations", start)
        val section  = text.substring(start, end)
        val password = section.substring(section.indexOf("password '") + 10).takeWhile(_ != '\'')
        val messages = section.linesIterator.foldLeft(Seq.empty[String]) { (acc, line) =>
            val trimmed = line.trim
            if trimmed.startsWith("C: ") || trimmed.startsWith("S: ") then acc :+ trimmed.drop(3)
            else if line.startsWith("      ") && trimmed.nonEmpty && acc.nonEmpty then acc.init :+ (acc.last + trimmed)
            else acc
        }
        Exchange(password, messages)
    end rfc7677Exchange

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
