package kyo.internal.crypto

import java.nio.charset.StandardCharsets
import kyo.*
import kyo.internal.crypto.TestVectorsJson.Json
import scala.annotation.tailrec

class Ed25519Test extends kyo.test.Test[Any]:

    import Ed25519Test.*

    // --- vector sets ---

    "vendored vector files match their MANIFEST digests" in {
        Seq("ietf-rfc8032", "wycheproof", "ed25519-speccheck").foreach { set =>
            assert(TestVectors.names(set).nonEmpty)
            TestVectors.names(set).foreach { name =>
                val embedded = TestVectors.text(set, name).getBytes(StandardCharsets.UTF_8)
                assert(hex(Sha256.hash(embedded)) == TestVectors.sha256(set, name))
            }
        }
    }

    "RFC 8032 section 7.1" - {
        "reads the five Ed25519 tests out of the RFC text" in {
            assert(rfcVectors.map(_.name) == Seq("1", "2", "3", "1024", "SHA(abc)"))
            assert(rfcVectors.map(_.message.length) == Seq(0, 1, 2, 1023, 64))
        }

        "every test's signature verifies" in {
            rfcVectors.foreach { v =>
                assert(Ed25519.verify(v.publicKey, v.message, v.signature))
            }
        }
    }

    "Wycheproof ed25519_test.json" in {
        val root = Json.parse(TestVectors.text("wycheproof", "ed25519_test.json"))
        assert(root("algorithm").string == "EDDSA")
        val outcomes =
            root("testGroups").items.flatMap { group =>
                assert(group("type").string == "EddsaVerify")
                assert(group("publicKey")("curve").string == "edwards25519")
                val key = bytes(group("publicKey")("pk").string)
                group("tests").items.map { test =>
                    val id       = test("tcId").int
                    val accepted = Ed25519.verify(key, bytes(test("msg").string), bytes(test("sig").string))
                    val expected = test("result").string match
                        case "valid"   => true
                        case "invalid" => false
                        case other     => fail(s"tcId $id has result '$other', which this suite has no rule for")
                    (id, accepted == expected)
                }
            }
        assert(outcomes.size == root("numberOfTests").int)
        assert(outcomes.filterNot(_._2).map(_._1) == Seq.empty)
    }

    "ed25519-speccheck cases.json pins the cofactorless equation and strict decoding" in {
        val cases    = Json.parse(TestVectors.text("ed25519-speccheck", "cases.json")).items
        val expected = Seq(
            true,  // 0: small A and R, S = 0
            true,  // 1: small A, mixed-order R
            true,  // 2: mixed-order A, small R
            true,  // 3: mixed-order A and R, valid under both equations
            false, // 4: valid only under the cofactored equation
            false, // 5: valid only under the cofactored equation, pre-reduced
            false, // 6: S > L
            false, // 7: S >> L
            false, // 8: non-canonical R
            false, // 9: non-canonical R
            false, // 10: non-canonical A
            false  // 11: non-canonical A
        )
        assert(cases.size == expected.size)
        val answers = cases.map { c =>
            Ed25519.verify(bytes(c("pub_key").string), bytes(c("message").string), bytes(c("signature").string))
        }
        val mismatchedCases = answers.indices.filter(i => answers(i) != expected(i))
        assert(mismatchedCases == Seq.empty, s"cases answering differently from RFC 8032 cofactorless: $mismatchedCases")
    }

    // --- the scalar S ---

    "S" - {
        "S = L is rejected" in {
            assert(!Ed25519.verify(identityKey, anyMessage, identityR ++ le32(L)))
        }

        "S = 0 over the identity key and R verifies, as RFC 8032 has no small-order blocklist" in {
            assert(Ed25519.verify(identityKey, anyMessage, identityR ++ le32(BigInt(0))))
        }

        "S = L + s for a valid s is rejected (malleability)" in {
            val v = rfc(2)
            val s = littleEndian(v.signature.drop(32))
            assert(s < L)
            assert(Ed25519.verify(v.publicKey, v.message, v.signature))
            assert(!Ed25519.verify(v.publicKey, v.message, v.signature.take(32) ++ le32(s + L)))
        }

        "S with the top bits set is rejected" in {
            val v = rfc(2)
            val s = littleEndian(v.signature.drop(32))
            assert(!Ed25519.verify(v.publicKey, v.message, v.signature.take(32) ++ le32(s + L * 8)))
            assert(!Ed25519.verify(identityKey, anyMessage, identityR ++ le32(L * 15)))
            assert((s + L * 8).testBit(255) && (L * 15).testBit(255))
        }
    }

    // --- point decoding ---

    "decoding" - {
        "y = p is rejected for the key and for R" in {
            val zero    = le32(BigInt(0))
            val yIsP    = le32(P)
            val message = messageWithK(zero, zero, remainder = 3)
            assert(Ed25519.verify(zero, message, zero ++ le32(BigInt(0))))
            assert(!Ed25519.verify(yIsP, messageWithK(zero, yIsP, remainder = 3), zero ++ le32(BigInt(0))))
            assert(!Ed25519.verify(zero, messageWithK(yIsP, zero, remainder = 3), yIsP ++ le32(BigInt(0))))
            assert(Ed25519.decode(yIsP, 0).isEmpty)
        }

        "y = p + 1 is rejected for the key and for R" in {
            val nonCanonical = le32(P + 1)
            assert(!Ed25519.verify(nonCanonical, anyMessage, identityR ++ le32(BigInt(0))))
            assert(!Ed25519.verify(identityKey, anyMessage, nonCanonical ++ le32(BigInt(0))))
            assert(Ed25519.decode(nonCanonical, 0).isEmpty)
        }

        "x = 0 with the sign bit set is rejected for the key and for R" in {
            val signed = le32(BigInt(1).setBit(255))
            assert(!Ed25519.verify(signed, anyMessage, identityR ++ le32(BigInt(0))))
            assert(!Ed25519.verify(identityKey, anyMessage, signed ++ le32(BigInt(0))))
            assert(Ed25519.decode(signed, 0).isEmpty)
            assert(Ed25519.decode(le32(P - 1).updated(31, (le32(P - 1)(31) | 0x80).toByte), 0).isEmpty)
        }

        "a y with no x on the curve is rejected" in {
            val y = BigInt(2)
            val u = (y * y - 1).mod(P)
            val v = (Dcurve * y * y + 1).mod(P)
            assert((u * v.modPow(P - 2, P)).mod(P).modPow((P - 1) / 2, P) == P - 1)
            val offCurve = le32(y)
            assert(Ed25519.decode(offCurve, 0).isEmpty)
            val v2 = rfc(2)
            assert(!Ed25519.verify(offCurve, v2.message, v2.signature))
            assert(!Ed25519.verify(v2.publicKey, v2.message, offCurve ++ v2.signature.drop(32)))
        }

        "a y whose candidate root squares to u decodes to that root" in {
            val y = firstY(RootBranch.Direct)
            assert(rootBranch(y) == RootBranch.Direct)
            assertDecodesOnCurve(y)
        }

        "a y whose candidate root squares to -u decodes to the candidate times sqrt(-1)" in {
            val y = firstY(RootBranch.TimesSqrtMinusOne)
            assert(rootBranch(y) == RootBranch.TimesSqrtMinusOne)
            assert(!onCurve(candidateRoot(y), y))
            assertDecodesOnCurve(y)
        }

        "the sign bit selects the root of that parity, on both branches" in {
            Seq(firstY(RootBranch.Direct), firstY(RootBranch.TimesSqrtMinusOne)).foreach { y =>
                val even = Ed25519.decode(le32(y), 0).map(_.x)
                val odd  = Ed25519.decode(le32(y.setBit(255)), 0).map(_.x)
                assert(even.map(_.testBit(0)) == Maybe(false))
                assert(odd.map(_.testBit(0)) == Maybe(true))
                assert(even.flatMap(e => odd.map(o => (e + o).mod(P))) == Maybe(BigInt(0)))
            }
        }
    }

    // --- the group equation ---

    "group equation" - {
        "k is reduced modulo L before it multiplies the key" in {
            val zero       = le32(BigInt(0))
            val signature  = zero ++ zero
            val reducedIs3 = messageWhere(zero, zero)((reduced, raw) => reduced.mod(4) == 3 && raw.mod(4) != 3)
            val rawIs3     = messageWhere(zero, zero)((reduced, raw) => reduced.mod(4) != 3 && raw.mod(4) == 3)
            assert(Ed25519.verify(zero, reducedIs3, signature))
            assert(!Ed25519.verify(zero, rawIs3, signature))
        }

        "R with the right x and the wrong y is rejected: identity key, R = (0, -1), S = 0" in {
            val r = le32(P - 1)
            assert(Ed25519.decode(r, 0).map(p => (p.x, p.y)) == Maybe((BigInt(0), P - 1)))
            assert(!Ed25519.verify(identityKey, anyMessage, r ++ le32(BigInt(0))))
            assert(Ed25519.verify(identityKey, anyMessage, identityR ++ le32(BigInt(0))))
        }
    }

    // --- lengths ---

    "lengths" - {
        "a key of 0, 31 or 33 bytes is rejected" in {
            val v = rfc(2)
            Seq(0, 31).foreach(n => assert(!Ed25519.verify(v.publicKey.take(n), v.message, v.signature)))
            assert(!Ed25519.verify(v.publicKey :+ 0.toByte, v.message, v.signature))
            assert(!Ed25519.verify(identityKey :+ 0.toByte, anyMessage, identityR ++ le32(BigInt(0))))
        }

        "a signature of 0, 63 or 65 bytes is rejected" in {
            val v = rfc(2)
            Seq(0, 63).foreach(n => assert(!Ed25519.verify(v.publicKey, v.message, v.signature.take(n))))
            assert(!Ed25519.verify(v.publicKey, v.message, v.signature :+ 0.toByte))
        }
    }

    // --- tampering ---

    "one flipped bit" - {
        "in the message is rejected" in {
            val v = rfc(3)
            Seq(0, 7, 15).foreach(bit => assert(!Ed25519.verify(v.publicKey, flip(v.message, bit), v.signature)))
        }

        "in the key is rejected" in {
            val v = rfc(2)
            Seq(0, 100, 254, 255).foreach(bit => assert(!Ed25519.verify(flip(v.publicKey, bit), v.message, v.signature)))
        }

        "in R is rejected" in {
            val v = rfc(2)
            Seq(0, 131, 255).foreach(bit => assert(!Ed25519.verify(v.publicKey, v.message, flip(v.signature, bit))))
        }

        "in S is rejected" in {
            val v = rfc(2)
            Seq(256, 400, 507).foreach(bit => assert(!Ed25519.verify(v.publicKey, v.message, flip(v.signature, bit))))
        }
    }

    "the empty message (RFC 8032 test 1) verifies and a one-byte message under its signature does not" in {
        val v = rfc(1)
        assert(v.message.isEmpty)
        assert(Ed25519.verify(v.publicKey, v.message, v.signature))
        assert(!Ed25519.verify(v.publicKey, Array[Byte](0), v.signature))
    }

    "leaves the key, message and signature unchanged" in {
        rfcVectors.foreach { v =>
            val (key, message, signature) = (v.publicKey.clone(), v.message.clone(), v.signature.clone())
            discard(Ed25519.verify(key, message, signature))
            discard(Ed25519.verify(key, message, flip(signature, 3)))
            assert(key.sameElements(v.publicKey) && message.sameElements(v.message) && signature.sameElements(v.signature))
        }
    }

    private def assertDecodesOnCurve(y: BigInt)(using kyo.test.AssertScope): Unit =
        val decoded = Ed25519.decode(le32(y), 0)
        assert(decoded.map(p => (p.y, p.z)) == Maybe((y, BigInt(1))))
        assert(decoded.map(p => onCurve(p.x, y)) == Maybe(true))
        assert(decoded.map(_.x.testBit(0)) == Maybe(false))
    end assertDecodesOnCurve

end Ed25519Test

object Ed25519Test:

    // --- curve constants as RFC 8032 section 5.1 states them ---

    val P: BigInt      = (BigInt(1) << 255) - 19
    val L: BigInt      = (BigInt(1) << 252) + BigInt("27742317777372353535851937790883648493")
    val Dcurve: BigInt = (BigInt(-121665) * BigInt(121666).modPow(P - 2, P)).mod(P)

    def le32(value: BigInt): Array[Byte] =
        val bigEndian = value.toByteArray.dropWhile(_ == 0)
        require(bigEndian.length <= 32, s"$value does not fit 32 bytes")
        bigEndian.reverse.padTo(32, 0.toByte)
    end le32

    def littleEndian(bytes: Array[Byte]): BigInt = BigInt(1, bytes.reverse)

    val identityKey: Array[Byte] = le32(BigInt(1))
    val identityR: Array[Byte]   = identityKey
    val anyMessage: Array[Byte]  = "any message".getBytes(StandardCharsets.UTF_8)

    /** A message for which `SHA-512(r || key || m) mod L` is `remainder` mod 4. */
    def messageWithK(r: Array[Byte], key: Array[Byte], remainder: Int): Array[Byte] =
        messageWhere(r, key)((reduced, _) => reduced.mod(4) == remainder)

    /** The first message `m` for which `accept(h mod L, h)` holds, `h` being `SHA-512(r || key || m)` read little-endian. */
    def messageWhere(r: Array[Byte], key: Array[Byte])(accept: (BigInt, BigInt) => Boolean): Array[Byte] =
        @tailrec def search(i: Int): Array[Byte] =
            val candidate = s"message $i".getBytes(StandardCharsets.UTF_8)
            val raw       = littleEndian(Sha512.hashChunks(Seq(r, key, candidate)))
            if accept(raw.mod(L), raw) then candidate else search(i + 1)
        end search
        search(0)
    end messageWhere

    // --- the square root of RFC 8032 section 5.1.3, step 3 ---

    enum RootBranch derives CanEqual:
        case Direct, TimesSqrtMinusOne, NoRoot

    def candidateRoot(y: BigInt): BigInt =
        val u = (y * y - 1).mod(P)
        val v = (Dcurve * y * y + 1).mod(P)
        (u * v.pow(3) * (u * v.pow(7)).modPow((P - 5) / 8, P)).mod(P)
    end candidateRoot

    def rootBranch(y: BigInt): RootBranch =
        val u     = (y * y - 1).mod(P)
        val v     = (Dcurve * y * y + 1).mod(P)
        val x     = candidateRoot(y)
        val check = (v * x * x).mod(P)
        if check == u then RootBranch.Direct
        else if check == (-u).mod(P) then RootBranch.TimesSqrtMinusOne
        else RootBranch.NoRoot
    end rootBranch

    /** The smallest `y >= 2` whose root takes `branch`; `y = 1` is excluded because its root is 0. */
    def firstY(branch: RootBranch): BigInt =
        Iterator.iterate(BigInt(2))(_ + 1).find(rootBranch(_) == branch).get

    def onCurve(x: BigInt, y: BigInt): Boolean = (y * y - x * x - 1 - Dcurve * x * x * y * y).mod(P) == 0

    def flip(bytes: Array[Byte], bit: Int): Array[Byte] =
        val copy = bytes.clone()
        copy(bit / 8) = (copy(bit / 8) ^ (1 << (bit % 8))).toByte
        copy
    end flip

    def hex(bytes: Array[Byte]): String = bytes.map(b => f"${b & 0xff}%02x").mkString

    def bytes(hex: String): Array[Byte] =
        require(hex.length % 2 == 0, s"odd-length hex: $hex")
        Array.tabulate(hex.length / 2)(i => Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16).toByte)

    // --- RFC 8032 section 7.1, read from the vendored RFC text ---

    final case class RfcVector(name: String, publicKey: Array[Byte], message: Array[Byte], signature: Array[Byte])

    lazy val rfcVectors: Seq[RfcVector] =
        val text    = TestVectors.text("ietf-rfc8032", "rfc8032.txt")
        val start   = text.indexOf("\n7.1.  Test Vectors for Ed25519")
        val end     = text.indexOf("\n7.2.  Test Vectors for Ed25519ctx")
        val section = text.substring(start, end).linesIterator.filter(_.startsWith("   ")).map(_.trim).toSeq
        val tests   = section.dropWhile(!_.startsWith("-----TEST ")).foldLeft(Vector.empty[Vector[String]]) { (acc, line) =>
            if line.startsWith("-----TEST ") then acc :+ Vector(line)
            else acc.init :+ (acc.last :+ line)
        }
        tests.map { lines =>
            val name                         = lines.head.stripPrefix("-----TEST ")
            def field(label: String): String =
                lines.dropWhile(!_.startsWith(label)).drop(1).takeWhile(_.matches("[0-9a-f]+")).mkString
            val declared = lines.collectFirst {
                case line if line.startsWith("MESSAGE (length ") => line.stripPrefix("MESSAGE (length ").takeWhile(_.isDigit).toInt
            }.getOrElse(throw new IllegalStateException(s"TEST $name has no MESSAGE length"))
            val message = bytes(field("MESSAGE"))
            require(message.length == declared, s"TEST $name: message is ${message.length} bytes, the RFC says $declared")
            RfcVector(name, bytes(field("PUBLIC KEY:")), message, bytes(field("SIGNATURE:")))
        }
    end rfcVectors

    def rfc(number: Int): RfcVector = rfcVectors.find(_.name == number.toString).get

end Ed25519Test
