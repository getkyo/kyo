package kyo.crypto

import java.nio.charset.StandardCharsets
import kyo.*
import kyo.crypto.Ed25519.KeyFailure
import kyo.crypto.Ed25519.VerificationKey
import kyo.internal.crypto.TestVectors
import kyo.internal.crypto.TestVectorsJson.Json
import scala.annotation.tailrec

class Ed25519Test extends kyo.test.Test[Any]:

    import Ed25519Test.*

    "the JDK's verdict on every jdk-differential case: its own signatures, and one bit flipped in the message, the signature or the key" in {
        val cases = kyo.internal.crypto.TestVectorsJdk.of("ed25519")
        assert(cases.size == 96)
        assert(cases.count(_.flag("verdict")) == 24)
        cases.foreach { c =>
            assert(
                verifies(c.bytes("pub"), c.bytes("msg"), c.bytes("sig")) == c.flag("verdict"),
                s"tamper=${c.value("tamper")}: the JDK said ${c.flag("verdict")}"
            )
        }
    }

    "VerificationKey" - {
        "decodes each RFC 8032 key, keeps its bytes, and verifies its signature through the Span surface" in {
            rfcVectors.foreach { v =>
                val key = keyOf(VerificationKey.fromBytes(Span.from(v.publicKey)))
                assert(key.bytes.toArray.sameElements(v.publicKey))
                assert(Ed25519.verify(key, Span.from(v.message), Span.from(v.signature)))
                assert(!Ed25519.verify(key, Span.from(v.message), Span.from(flip(v.signature, 3))))
                assert(!Ed25519.verify(key, Span.from(v.message), Span.from(v.signature.take(63))))
                assert(!Ed25519.verify(key, Span.from(v.message), Span.from(v.signature :+ 0.toByte)))
            }
        }

        "is equal to another key exactly when the bytes are" in {
            val a = keyOf(VerificationKey.fromBytes(Span.from(rfc(1).publicKey)))
            val b = keyOf(VerificationKey.fromBytes(Span.from(rfc(1).publicKey.clone())))
            val c = keyOf(VerificationKey.fromBytes(Span.from(rfc(2).publicKey)))
            assert(a == b)
            assert(a.hashCode == b.hashCode)
            assert(a != c)
            assert(a.toString == s"VerificationKey(${hex(rfc(1).publicKey)})")
        }

        "the array tier answers as the Span rows" in {
            val v   = rfc(2)
            val key = keyOf(VerificationKey.fromArray(v.publicKey))
            assert(key == keyOf(VerificationKey.fromBytes(Span.from(v.publicKey))))
            assert(Ed25519.verifyArrays(key, v.message, v.signature))
            assert(Ed25519.verifyArrays(key, v.message, v.signature) == Ed25519.verify(key, Span.from(v.message), Span.from(v.signature)))
            assert(!Ed25519.verifyArrays(key, v.message, flip(v.signature, 9)))
        }

        "copies the bytes it was built from" in {
            val bytes = rfc(1).publicKey.clone()
            val key   = keyOf(VerificationKey.fromBytes(Span.from(bytes)))
            bytes(0) = (bytes(0) ^ 1).toByte
            assert(key.bytes.toArray.sameElements(rfc(1).publicKey))
        }

        "a key of 0, 31 or 33 bytes is a Length failure" in {
            assert(VerificationKey.fromBytes(Span.empty[Byte]) == Result.fail(KeyFailure.Length(0)))
            assert(VerificationKey.fromBytes(Span.from(rfc(2).publicKey.take(31))) == Result.fail(KeyFailure.Length(31)))
            assert(VerificationKey.fromBytes(Span.from(rfc(2).publicKey :+ 0.toByte)) == Result.fail(KeyFailure.Length(33)))
        }

        "y = p and y = p + 1 are YNotReduced" in {
            assert(VerificationKey.fromBytes(Span.from(le32(P))) == Result.fail(KeyFailure.YNotReduced))
            assert(VerificationKey.fromBytes(Span.from(le32(P + 1))) == Result.fail(KeyFailure.YNotReduced))
            assert(VerificationKey.fromBytes(Span.from(le32(P).updated(31, (le32(P)(31) | 0x80).toByte))) ==
                Result.fail(KeyFailure.YNotReduced))
        }

        "a y with no x on the curve is NotOnCurve" in {
            assert(VerificationKey.fromBytes(Span.from(le32(BigInt(2)))) == Result.fail(KeyFailure.NotOnCurve))
        }

        "x = 0 with the sign bit set is ZeroWithSign, for y = 1 and y = p - 1" in {
            assert(VerificationKey.fromBytes(Span.from(le32(BigInt(1).setBit(255)))) == Result.fail(KeyFailure.ZeroWithSign))
            assert(VerificationKey.fromBytes(Span.from(le32(P - 1).updated(31, (le32(P - 1)(31) | 0x80).toByte))) ==
                Result.fail(KeyFailure.ZeroWithSign))
            assert(VerificationKey.fromBytes(Span.from(le32(BigInt(1)))) == Result.fail(KeyFailure.SmallOrder))
        }

        "each of the eight small-order encodings is SmallOrder; the base point, every vector key and every generated key are not" in {
            assert(torsionPoints.size == 8)
            torsionPoints.foreach { t =>
                val encoding = Ed25519Reference.encode(t)
                assert(smallOrder(encoding))
                assert(VerificationKey.fromBytes(Span.from(encoding)) == Result.fail(KeyFailure.SmallOrder), hex(encoding))
            }
            assert(VerificationKey.fromBytes(Span.from(le32(BigInt(1)))) == Result.fail(KeyFailure.SmallOrder))
            assert(VerificationKey.fromBytes(Span.from(le32(BigInt(0)))) == Result.fail(KeyFailure.SmallOrder))
            assert(VerificationKey.fromBytes(Span.from(le32(P - 1))) == Result.fail(KeyFailure.SmallOrder))
            val honest =
                Seq(Ed25519Reference.encode(Ed25519Reference.Base)) ++ rfcVectors.map(_.publicKey) ++
                    Seq(generated(1L, 1).publicKey, generated(2L, 1).publicKey) ++
                    torsionPoints.map(t => mixedOrderKey(41L, t).publicKey)
            honest.foreach { key =>
                assert(!smallOrder(key))
                assert(VerificationKey.fromBytes(Span.from(key)).isSuccess, hex(key))
            }
        }
    }

    "vendored vector files match their MANIFEST digests" in {
        Seq("ietf-rfc8032", "wycheproof", "ed25519-speccheck").foreach { set =>
            assert(TestVectors.names(set).nonEmpty)
            TestVectors.names(set).foreach { name =>
                val embedded = TestVectors.text(set, name).getBytes(StandardCharsets.UTF_8)
                assert(hex(Sha256.hashArray(embedded)) == TestVectors.sha256(set, name))
            }
        }
    }

    "RFC 8032 section 7.1" - {
        "the base point is B of section 5.1, and decodes from its encoding" in {
            val x = BigInt("15112221349535400772501151409588531511454012693041857206046113283949847762202")
            val y = BigInt("46316835694926478169428394003475163141307993866256225615783033603165251855960")
            assert(Ed25519Reference.Base.x == x)
            assert(Ed25519Reference.Base.y == y)
            assert(Ed25519Reference.Base.z == BigInt(1))
            assert(Ed25519Reference.Base.t == (x * y).mod(P))
            val encoding = bytes("5866666666666666666666666666666666666666666666666666666666666666")
            assert(Ed25519Reference.encode(Ed25519Reference.Base).sameElements(encoding))
            val base = Ed25519.BaseTable(0)
            assert(fieldValue(base.x) == x)
            assert(fieldValue(base.y) == y)
            assert(fieldValue(base.z) == BigInt(1))
            assert(fieldValue(base.t) == (x * y).mod(P))
            assert(encodeLimbPoint(base).sameElements(encoding))
            assert(decodedX(encoding) == Maybe(x))
        }

        "reads the five Ed25519 tests out of the RFC text" in {
            assert(rfcVectors.map(_.name) == Seq("1", "2", "3", "1024", "SHA(abc)"))
            assert(rfcVectors.map(_.message.length) == Seq(0, 1, 2, 1023, 64))
        }

        "every test's signature verifies" in {
            rfcVectors.foreach { v =>
                assert(verifies(v.publicKey, v.message, v.signature))
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
                    val accepted = verifies(key, bytes(test("msg").string), bytes(test("sig").string))
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
            false, // 0: small-order A, refused at the key
            false, // 1: small-order A, refused at the key
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
            verifies(bytes(c("pub_key").string), bytes(c("message").string), bytes(c("signature").string))
        }
        val mismatchedCases = answers.indices.filter(i => answers(i) != expected(i))
        assert(mismatchedCases == Seq.empty, s"cases answering differently from RFC 8032 cofactorless: $mismatchedCases")
        val keys = cases.map(c => bytes(c("pub_key").string))
        assert(keys.indices.filter(i => smallOrder(keys(i))) == Seq(0, 1))
        assert(VerificationKey.fromBytes(Span.from(keys(0))) == Result.fail(KeyFailure.SmallOrder))
        assert(VerificationKey.fromBytes(Span.from(keys(1))) == Result.fail(KeyFailure.SmallOrder))
    }

    "Chalkias, Garillot and Nikolaenko, \"Taming the many EdDSAs\" (SSR 2020): the ed25519-speccheck case table, one leaf per case" - {

        /** An encoding no canonical encoder produces: `y` at or above p, or `x = 0` (`y` of 1 or p - 1) with the sign bit set. */
        def nonCanonical(encoding: Array[Byte]): Boolean =
            val y = littleEndian(encoding.updated(31, (encoding(31) & 0x7f).toByte))
            y >= P || ((y == 1 || y == P - 1) && (encoding(31) & 0x80) != 0)

        final case class SpeccheckCase(publicKey: Array[Byte], message: Array[Byte], signature: Array[Byte]):
            def r: Array[Byte] = signature.take(32)
            def s: BigInt      = littleEndian(signature.drop(32))

        def speccheck(i: Int): SpeccheckCase =
            val c = Json.parse(TestVectors.text("ed25519-speccheck", "cases.json")).items(i)
            SpeccheckCase(bytes(c("pub_key").string), bytes(c("message").string), bytes(c("signature").string))

        Seq[(String, Boolean, SpeccheckCase => Boolean)](
            ("S = 0 with small-order A and R: refused, A is small order", false, c => c.s == 0 && smallOrder(c.publicKey)),
            (
                "0 < S < L, small-order A, mixed-order R: refused, A is small order",
                false,
                c => c.s > 0 && c.s < L && smallOrder(c.publicKey)
            ),
            (
                "0 < S < L, mixed-order A, small-order R: accepted, RFC 8032 has no blocklist on R",
                true,
                c => c.s < L && !smallOrder(c.publicKey) && smallOrder(c.r)
            ),
            ("mixed-order A and R, valid under both equations: accepted", true, c => c.s < L && !smallOrder(c.publicKey)),
            ("mixed-order A and R, valid only under the cofactored equation: refused, the equation is cofactorless", false, c => c.s < L),
            ("mixed-order A, L-order R, valid only under a cofactored equation that pre-reduces 8h: refused", false, c => c.s < L),
            ("S > L (S + L malleability): refused", false, c => c.s >= L && !c.s.testBit(253)),
            ("S >> L (top bits of S set): refused", false, c => c.s >= L && c.s.bitLength > 253),
            ("non-canonical R: refused", false, c => nonCanonical(c.r) && !nonCanonical(c.publicKey)),
            ("non-canonical R, the other encoding: refused", false, c => nonCanonical(c.r) && !nonCanonical(c.publicKey)),
            ("non-canonical A: refused", false, c => nonCanonical(c.publicKey)),
            ("non-canonical A, the other encoding: refused", false, c => nonCanonical(c.publicKey))
        ).zipWithIndex.foreach { case ((property, verdict, shape), i) =>
            s"case $i: $property" in {
                val c = speccheck(i)
                assert(shape(c), s"case $i does not have the shape its name states")
                assert(verifies(c.publicKey, c.message, c.signature) == verdict)
            }
        }
    }

    "S" - {
        "S = L is rejected" in {
            val v = rfc(2)
            assert(!verifies(v.publicKey, v.message, v.signature.take(32) ++ le32(L)))
        }

        "a small-order R verifies under a mixed-order key, as RFC 8032 has no blocklist on R" in {
            val key     = mixedOrderKey(7L, torsion4)
            val r       = le32(BigInt(0))
            val message = messageWithK(r, key.publicKey, remainder = 3)
            assert(smallOrder(r) && !smallOrder(key.publicKey))
            assert(verifies(key.publicKey, message, mixedSignature(key, r, message)))
            assert(!verifies(key.publicKey, message, mixedSignature(key, r, message :+ 0.toByte)))
        }

        "S = L + s for a valid s is rejected (malleability; Wycheproof tcId 63 to 66, speccheck case 6)" in {
            val v = rfc(2)
            val s = littleEndian(v.signature.drop(32))
            assert(s < L)
            assert(verifies(v.publicKey, v.message, v.signature))
            assert(!verifies(v.publicKey, v.message, v.signature.take(32) ++ le32(s + L)))
        }

        "S with the top bits set is rejected" in {
            val v = rfc(2)
            val s = littleEndian(v.signature.drop(32))
            assert(!verifies(v.publicKey, v.message, v.signature.take(32) ++ le32(s + L * 8)))
            assert(!verifies(v.publicKey, v.message, v.signature.take(32) ++ le32(L * 15)))
            assert((s + L * 8).testBit(255) && (L * 15).testBit(255))
        }
    }

    "decoding" - {
        "y = p is rejected for the key and for R, where y = 0 encodes the point R must be" in {
            val zero = le32(BigInt(0))
            val yIsP = le32(P)
            val key  = mixedOrderKey(8L, torsion4)
            assert(!verifies(yIsP, anyMessage, zero ++ le32(BigInt(0))))
            assert(VerificationKey.fromArray(yIsP) == Result.fail(KeyFailure.YNotReduced))
            val canonical = messageWithK(zero, key.publicKey, remainder = 3)
            assert(verifies(key.publicKey, canonical, mixedSignature(key, zero, canonical)))
            val lax = messageWithK(yIsP, key.publicKey, remainder = 3)
            assert(!verifies(key.publicKey, lax, mixedSignature(key, yIsP, lax)))
            assert(decodedX(yIsP).isEmpty)
        }

        "y = p + 1 is rejected for the key and for R, where y = 1 encodes the point R must be" in {
            val nonCanonical = le32(P + 1)
            val key          = mixedOrderKey(9L, torsion2)
            assert(!verifies(nonCanonical, anyMessage, identityR ++ le32(BigInt(0))))
            assert(VerificationKey.fromArray(nonCanonical) == Result.fail(KeyFailure.YNotReduced))
            val canonical = messageWhere(identityR, key.publicKey)((reduced, _) => reduced.mod(2) == 0)
            assert(verifies(key.publicKey, canonical, mixedSignature(key, identityR, canonical)))
            val lax = messageWhere(nonCanonical, key.publicKey)((reduced, _) => reduced.mod(2) == 0)
            assert(!verifies(key.publicKey, lax, mixedSignature(key, nonCanonical, lax)))
            assert(decodedX(nonCanonical).isEmpty)
        }

        "x = 0 with the sign bit set is rejected for the key and for R, for y = 1 and y = p - 1" in {
            val signedOne      = le32(BigInt(1).setBit(255))
            val minusOne       = le32(P - 1)
            val signedMinusOne = minusOne.updated(31, (minusOne(31) | 0x80).toByte)
            assert(!verifies(signedOne, anyMessage, identityR ++ le32(BigInt(0))))
            assert(VerificationKey.fromArray(signedOne) == Result.fail(KeyFailure.ZeroWithSign))
            val key  = mixedOrderKey(10L, torsion2)
            val even = messageWhere(identityR, key.publicKey)((reduced, _) => reduced.mod(2) == 0)
            assert(verifies(key.publicKey, even, mixedSignature(key, identityR, even)))
            val evenSigned = messageWhere(signedOne, key.publicKey)((reduced, _) => reduced.mod(2) == 0)
            assert(!verifies(key.publicKey, evenSigned, mixedSignature(key, signedOne, evenSigned)))
            val odd = messageWhere(minusOne, key.publicKey)((reduced, _) => reduced.mod(2) == 1)
            assert(verifies(key.publicKey, odd, mixedSignature(key, minusOne, odd)))
            val oddSigned = messageWhere(signedMinusOne, key.publicKey)((reduced, _) => reduced.mod(2) == 1)
            assert(!verifies(key.publicKey, oddSigned, mixedSignature(key, signedMinusOne, oddSigned)))
            assert(decodedX(signedOne).isEmpty)
            assert(decodedX(signedMinusOne).isEmpty)
        }

        "a y with no x on the curve is rejected" in {
            val y = BigInt(2)
            val u = (y * y - 1).mod(P)
            val v = (Dcurve * y * y + 1).mod(P)
            assert((u * v.modPow(P - 2, P)).mod(P).modPow((P - 1) / 2, P) == P - 1)
            val offCurve = le32(y)
            assert(decodedX(offCurve).isEmpty)
            val v2 = rfc(2)
            assert(!verifies(offCurve, v2.message, v2.signature))
            assert(!verifies(v2.publicKey, v2.message, offCurve ++ v2.signature.drop(32)))
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
                val even = decodedX(le32(y))
                val odd  = decodedX(le32(y.setBit(255)))
                assert(even.map(_.testBit(0)) == Maybe(false))
                assert(odd.map(_.testBit(0)) == Maybe(true))
                assert(even.flatMap(e => odd.map(o => (e + o).mod(P))) == Maybe(BigInt(0)))
            }
        }
    }

    "group equation" - {
        "k is reduced modulo L before it multiplies the key" in {
            val zero       = le32(BigInt(0))
            val key        = mixedOrderKey(11L, torsion4)
            val reducedIs3 = messageWhere(zero, key.publicKey)((reduced, raw) => reduced.mod(4) == 3 && raw.mod(4) != 3)
            val rawIs3     = messageWhere(zero, key.publicKey)((reduced, raw) => reduced.mod(4) != 3 && raw.mod(4) == 3)
            assert(verifies(key.publicKey, reducedIs3, mixedSignature(key, zero, reducedIs3)))
            assert(!verifies(key.publicKey, rawIs3, mixedSignature(key, zero, rawIs3)))
        }

        "R with the right x and the wrong y is rejected: R = (0, -1) where the identity is expected" in {
            val r   = le32(P - 1)
            val key = mixedOrderKey(12L, torsion2)
            assert(Ed25519Reference.decode(r, 0).map(p => (p.x, p.y)) == Maybe((BigInt(0), P - 1)))
            assert(VerificationKey.fromArray(r) == Result.fail(KeyFailure.SmallOrder))
            val even = messageWhere(identityR, key.publicKey)((reduced, _) => reduced.mod(2) == 0)
            assert(verifies(key.publicKey, even, mixedSignature(key, identityR, even)))
            val wrongY = messageWhere(r, key.publicKey)((reduced, _) => reduced.mod(2) == 0)
            assert(!verifies(key.publicKey, wrongY, mixedSignature(key, r, wrongY)))
        }
    }

    "lengths" - {
        "a decode of anything but 32 bytes is a length failure, never a read past the array" in {
            val v = rfc(2)
            assert(decodedX(v.publicKey.take(31)).isEmpty)
            assert(decodedX(Array.emptyByteArray).isEmpty)
            assert(decodedX(v.publicKey :+ 0.toByte).isEmpty)
            assert(decodedX(v.publicKey).isDefined)
            assert(decodedX(v.signature.take(32)).isDefined)
        }

        "a key of 0, 31 or 33 bytes is rejected" in {
            val v = rfc(2)
            Seq(0, 31).foreach(n => assert(!verifies(v.publicKey.take(n), v.message, v.signature)))
            assert(!verifies(v.publicKey :+ 0.toByte, v.message, v.signature))
        }

        "a signature of 0, 63 or 65 bytes is rejected" in {
            val v = rfc(2)
            Seq(0, 63).foreach(n => assert(!verifies(v.publicKey, v.message, v.signature.take(n))))
            assert(!verifies(v.publicKey, v.message, v.signature :+ 0.toByte))
        }
    }

    "one flipped bit" - {
        "in the message is rejected" in {
            val v = rfc(3)
            Seq(0, 7, 15).foreach(bit => assert(!verifies(v.publicKey, flip(v.message, bit), v.signature)))
        }

        "in the key is rejected" in {
            val v = rfc(2)
            Seq(0, 100, 254, 255).foreach(bit => assert(!verifies(flip(v.publicKey, bit), v.message, v.signature)))
        }

        "in R is rejected" in {
            val v = rfc(2)
            Seq(0, 131, 255).foreach(bit => assert(!verifies(v.publicKey, v.message, flip(v.signature, bit))))
        }

        "in S is rejected" in {
            val v = rfc(2)
            Seq(256, 400, 507).foreach(bit => assert(!verifies(v.publicKey, v.message, flip(v.signature, bit))))
        }
    }

    "the empty message (RFC 8032 test 1) verifies and a one-byte message under its signature does not" in {
        val v = rfc(1)
        assert(v.message.isEmpty)
        assert(verifies(v.publicKey, v.message, v.signature))
        assert(!verifies(v.publicKey, Array[Byte](0), v.signature))
    }

    "leaves the key, message and signature unchanged" in {
        rfcVectors.foreach { v =>
            val (key, message, signature) = (v.publicKey.clone(), v.message.clone(), v.signature.clone())
            discard(verifies(key, message, signature))
            discard(verifies(key, message, flip(signature, 3)))
            assert(key.sameElements(v.publicKey) && message.sameElements(v.message) && signature.sameElements(v.signature))
        }
    }

    "the reference oracle" - {
        "reads the five secret keys out of the RFC text" in {
            assert(rfcVectors.map(_.secretKey.length) == Seq(32, 32, 32, 32, 32))
            assert(hex(rfc(1).secretKey) == "9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60")
        }

        "derives each RFC public key from its secret key" in {
            rfcVectors.foreach { v =>
                assert(Ed25519Reference.publicKey(v.secretKey).sameElements(v.publicKey), s"TEST ${v.name}")
            }
        }

        "reproduces each RFC signature byte for byte from its secret key" in {
            rfcVectors.foreach { v =>
                assert(Ed25519Reference.sign(v.secretKey, v.message).sameElements(v.signature), s"TEST ${v.name}")
            }
        }

        "answers as the implementation on the RFC, Wycheproof and speccheck inputs" in {
            rfcVectors.foreach { v =>
                assert(Ed25519Reference.verify(v.publicKey, v.message, v.signature))
                assert(!Ed25519Reference.verify(v.publicKey, v.message, flip(v.signature, 3)))
            }
            val wycheproof = Json.parse(TestVectors.text("wycheproof", "ed25519_test.json"))
            wycheproof("testGroups").items.foreach { group =>
                val key = bytes(group("publicKey")("pk").string)
                group("tests").items.foreach { test =>
                    val message   = bytes(test("msg").string)
                    val signature = bytes(test("sig").string)
                    assert(
                        Ed25519Reference.verify(key, message, signature) == verifies(key, message, signature),
                        s"tcId ${test("tcId").int}"
                    )
                }
            }
            Json.parse(TestVectors.text("ed25519-speccheck", "cases.json")).items.zipWithIndex.foreach { (c, i) =>
                val (key, message, signature) = (bytes(c("pub_key").string), bytes(c("message").string), bytes(c("signature").string))
                val reference                 = Ed25519Reference.verify(key, message, signature) && !smallOrder(key)
                assert(reference == verifies(key, message, signature), s"case $i")
            }
        }

        "signatures under generated keys verify in both, and the limb verifier rejects every one-bit mutation of a sample" in {
            val pairs = Seq(1L, 2L, 3L).map(seed => generated(seed, 12))
            pairs.foreach { g =>
                val key = keyOf(VerificationKey.fromBytes(Span.from(g.publicKey)))
                g.messages.foreach { message =>
                    val signature = Ed25519Reference.sign(g.secretKey, message)
                    assert(Ed25519Reference.verify(g.publicKey, message, signature))
                    assert(Ed25519.verify(key, Span.from(message), Span.from(signature)))
                }
            }
            val sample    = pairs.head
            val message   = sample.messages(5)
            val signature = Ed25519Reference.sign(sample.secretKey, message)
            assert(message.length == 35)
            val key = keyOf(VerificationKey.fromBytes(Span.from(sample.publicKey)))
            // A one-bit mutation of an honest signature, message or key is a forgery, so its verdict is known without the reference. The
            // reference takes 70 ms a verify on Native: consulting it for the 1048 mutations is past the leaf timeout and tests only the
            // oracle.
            (0 until 512).foreach { bit =>
                val mutated = flip(signature, bit)
                assert(!Ed25519.verify(key, Span.from(message), Span.from(mutated)), s"signature bit $bit")
            }
            (0 until message.length * 8).foreach { bit =>
                val mutated = flip(message, bit)
                assert(!Ed25519.verify(key, Span.from(mutated), Span.from(signature)), s"message bit $bit")
            }
            (0 until 256).foreach { bit =>
                val mutated = flip(sample.publicKey, bit)
                assert(!verifies(mutated, message, signature), s"key bit $bit")
            }
        }
    }

    "the limb point arithmetic" - {
        "every base-table entry is the odd multiple of B the reference computes" in {
            assert(Ed25519.BaseTable.length == 8)
            Ed25519.BaseTable.zipWithIndex.foreach { (entry, i) =>
                assertSamePoint(entry, Ed25519Reference.multiply(BigInt(2 * i + 1), Ed25519Reference.Base), s"entry $i")
            }
        }

        "a key's table holds the odd multiples of -A" in {
            (rfcVectors.map(_.publicKey) ++ Seq(generated(11L, 1).publicKey, generated(12L, 1).publicKey)).foreach { publicKey =>
                val key      = keyOf(VerificationKey.fromArray(publicKey))
                val negatedA = Ed25519Reference.negate(Ed25519Reference.decode(publicKey, 0).get)
                assert(key.negatedTable.length == 8)
                key.negatedTable.zipWithIndex.foreach { (entry, i) =>
                    assertSamePoint(entry, Ed25519Reference.multiply(BigInt(2 * i + 1), negatedA), s"entry $i of ${hex(publicKey)}")
                }
            }
        }

        "the eight torsion points are SmallOrder under either sign bit, except x = 0 signed, which is ZeroWithSign first" in {
            assert(torsionPoints.size == 8)
            val zeroX = torsionPoints.filter(t => (t.x * Ed25519Reference.inverse(t.z)).mod(P) == 0)
            assert(zeroX.size == 2)
            torsionPoints.foreach { t =>
                val encoding = Ed25519Reference.encode(t)
                val flipped  = encoding.updated(31, (encoding(31) ^ 0x80).toByte)
                assert(VerificationKey.fromArray(encoding) == Result.fail(KeyFailure.SmallOrder))
                assert(decodedX(encoding).isEmpty)
                val expected = if zeroX.contains(t) then KeyFailure.ZeroWithSign else KeyFailure.SmallOrder
                assert(VerificationKey.fromArray(flipped) == Result.fail(expected), hex(flipped))
            }
        }

        "add and double match the reference on the identity, B, its multiples, its negation and the torsion points, under every aliasing" in {
            val ws = new Ed25519.Workspace
            samplePoints.foreach { p =>
                samplePoints.foreach { q =>
                    val expected = Ed25519Reference.add(p, q)
                    val label    = s"${hex(Ed25519Reference.encode(p))} + ${hex(Ed25519Reference.encode(q))}"
                    val out      = Ed25519.Point()
                    Ed25519.add(out, limbPoint(p), limbPoint(q), ws)
                    assertSamePoint(out, expected, label)
                    val first = limbPoint(p)
                    Ed25519.add(first, first, limbPoint(q), ws)
                    assertSamePoint(first, expected, label)
                    val second = limbPoint(q)
                    Ed25519.add(second, limbPoint(p), second, ws)
                    assertSamePoint(second, expected, label)
                }
                val doubled  = Ed25519Reference.add(p, p)
                val label    = s"double ${hex(Ed25519Reference.encode(p))}"
                val out      = Ed25519.Point()
                val aliased  = limbPoint(p)
                val negated  = Ed25519.Point()
                val identity = Ed25519.Point()
                Ed25519.double(out, limbPoint(p), ws)
                assertSamePoint(out, doubled, label)
                Ed25519.double(aliased, aliased, ws)
                assertSamePoint(aliased, doubled, label)
                Ed25519.negate(negated, limbPoint(p))
                assertSamePoint(negated, Ed25519Reference.negate(p), label)
                Ed25519.add(identity, limbPoint(p), negated, ws)
                assertSamePoint(identity, Ed25519Reference.Identity, label)
                assert(encodeLimbPoint(identity).sameElements(identityKey))
            }
        }

        "encode is the reference encoding for projective coordinates with any z" in {
            val ws     = new Ed25519.Workspace
            val random = new java.util.Random(0x7748)
            samplePoints.foreach { p =>
                (0 until 3).foreach { _ =>
                    val scale  = BigInt(255, random).mod(P - 1) + 1
                    val scaled =
                        Ed25519Reference.Point((p.x * scale).mod(P), (p.y * scale).mod(P), (p.z * scale).mod(P), (p.t * scale).mod(P))
                    val out = new Array[Byte](32)
                    Ed25519.encode(out, limbPoint(scaled, normalize = false), ws)
                    assert(out.sameElements(Ed25519Reference.encode(p)))
                }
            }
        }

        "doubleScalarMultiply is [s]B + [k](-A) for s and k at 0, 1, 2, L - 1 and seeded values, on RFC, generated and mixed-order keys" in {
            val ws      = new Ed25519.Workspace
            val random  = new java.util.Random(0x5519)
            val scalars = Seq(BigInt(0), BigInt(1), BigInt(2), BigInt(8), L - 1) ++ (0 until 4).map(_ => BigInt(253, random).mod(L))
            val keys    =
                rfcVectors.map(_.publicKey) ++ Seq(generated(21L, 1).publicKey) ++
                    torsionPoints.map(t => mixedOrderKey(22L, t).publicKey)
            assert(keys.size == rfcVectors.size + 1 + 8)
            // Each reference multiple is computed once and combined per pair: a BigInt scalar multiplication takes 34 ms on Native, and
            // recomputing both per pair is 2268 of them, past the leaf timeout.
            val baseMultiples = scalars.map(s => Ed25519Reference.multiply(s, Ed25519Reference.Base))
            keys.foreach { publicKey =>
                val key          = keyOf(VerificationKey.fromArray(publicKey))
                val negatedA     = Ed25519Reference.negate(Ed25519Reference.decode(publicKey, 0).get)
                val keyMultiples = scalars.map(k => Ed25519Reference.multiply(k, negatedA))
                scalars.indices.foreach { si =>
                    scalars.indices.foreach { ki =>
                        val s        = scalars(si)
                        val k        = scalars(ki)
                        val expected = Ed25519Reference.add(baseMultiples(si), keyMultiples(ki))
                        val sDigits  = new Array[Byte](256)
                        val kDigits  = new Array[Byte](256)
                        kyo.internal.crypto.Ed25519Scalar.slide(sDigits, le32(s))
                        kyo.internal.crypto.Ed25519Scalar.slide(kDigits, le32(k))
                        val out = Ed25519.Point()
                        Ed25519.doubleScalarMultiply(out, sDigits, Ed25519.BaseTable, kDigits, key.negatedTable, ws)
                        assertSamePoint(out, expected, s"s = $s, k = $k, key ${hex(publicKey)}")
                        assert(encodeLimbPoint(out).sameElements(Ed25519Reference.encode(expected)))
                    }
                }
            }
        }
    }

    /** The limb point and the reference point are the same affine point, and the limb point's `T Z = X Y` holds. */
    private def assertSamePoint(limb: Ed25519.Point, reference: Ed25519Reference.Point, label: String)(using
        kyo.test.AssertScope
    ): Unit =
        val x = fieldValue(limb.x)
        val y = fieldValue(limb.y)
        val z = fieldValue(limb.z)
        val t = fieldValue(limb.t)
        assert(z != 0, label)
        // Compared projectively: an inverse is a 255-bit modPow, which dominates this check on Native.
        assert(Ed25519Reference.samePoint(Ed25519Reference.Point(x, y, z, t), reference), s"$label: (x $x, y $y, z $z)")
        assert((t * z).mod(P) == (x * y).mod(P), s"$label: T Z != X Y")
    end assertSamePoint

    private def assertDecodesOnCurve(y: BigInt)(using kyo.test.AssertScope): Unit =
        val reference = Ed25519Reference.decode(le32(y), 0)
        assert(reference.map(p => (p.y, p.z)) == Maybe((y, BigInt(1))))
        val decoded = decodedX(le32(y))
        assert(decoded == reference.map(_.x))
        assert(decoded.map(x => onCurve(x, y)) == Maybe(true))
        assert(decoded.map(_.testBit(0)) == Maybe(false))
    end assertDecodesOnCurve

end Ed25519Test

object Ed25519Test:

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
            val raw       = littleEndian(Sha512.hashArrays(Chunk(r, key, candidate)))
            if accept(raw.mod(L), raw) then candidate else search(i + 1)
        end search
        search(0)
    end messageWhere

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

    final case class RfcVector(name: String, secretKey: Array[Byte], publicKey: Array[Byte], message: Array[Byte], signature: Array[Byte])

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
            RfcVector(name, bytes(field("SECRET KEY:")), bytes(field("PUBLIC KEY:")), message, bytes(field("SIGNATURE:")))
        }
    end rfcVectors

    def rfc(number: Int): RfcVector = rfcVectors.find(_.name == number.toString).get

    def keyOf(result: Result[KeyFailure, VerificationKey]): VerificationKey = result match
        case Result.Success(key) => key
        case other               => throw new IllegalStateException(s"expected a key, got $other")

    /** [[Ed25519.verify]] over encoded key bytes, through the public rows: false when the key does not decode. */
    def verifies(publicKey: Array[Byte], message: Array[Byte], signature: Array[Byte]): Boolean =
        VerificationKey.fromBytes(Span.from(publicKey)) match
            case Result.Success(key)                 => Ed25519.verify(key, Span.from(message), Span.from(signature))
            case Result.Failure(_) | Result.Panic(_) => false

    /** The `x` of the point `bytes` encode, from the limb decoder: the key's first table entry is `-A`, so its `x` negated. */
    def decodedX(bytes: Array[Byte]): Maybe[BigInt] =
        VerificationKey.fromArray(bytes) match
            case Result.Success(key) =>
                val negated = fieldValue(key.negatedTable(0).x)
                Present((-negated).mod(P))
            case _ => Absent

    /** The value of a limb element, through the canonical bytes. */
    def fieldValue(fe: Array[Double]): BigInt =
        val scratch = new kyo.internal.crypto.Ed25519Field.Scratch
        val out     = new Array[Byte](32)
        kyo.internal.crypto.Ed25519Field.pack(out, fe, scratch)
        littleEndian(out)
    end fieldValue

    /** The 32-byte encoding of a limb point. */
    def encodeLimbPoint(p: Ed25519.Point): Array[Byte] =
        val out = new Array[Byte](32)
        Ed25519.encode(out, p, new Ed25519.Workspace)
        out
    end encodeLimbPoint

    /** A reference point as limbs: affine (`z = 1`, `t = x y`) unless `normalize` is false, in which case the four projective coordinates
      * are taken as they are.
      */
    def limbPoint(p: Ed25519Reference.Point, normalize: Boolean = true): Ed25519.Point =
        val point = Ed25519.Point()
        if normalize then
            val zInverse = Ed25519Reference.inverse(p.z)
            val x        = (p.x * zInverse).mod(P)
            val y        = (p.y * zInverse).mod(P)
            kyo.internal.crypto.Ed25519Field.unpack(point.x, le32(x), 0)
            kyo.internal.crypto.Ed25519Field.unpack(point.y, le32(y), 0)
            kyo.internal.crypto.Ed25519Field.unpack(point.z, le32(BigInt(1)), 0)
            kyo.internal.crypto.Ed25519Field.unpack(point.t, le32((x * y).mod(P)), 0)
        else
            kyo.internal.crypto.Ed25519Field.unpack(point.x, le32(p.x), 0)
            kyo.internal.crypto.Ed25519Field.unpack(point.y, le32(p.y), 0)
            kyo.internal.crypto.Ed25519Field.unpack(point.z, le32(p.z), 0)
            kyo.internal.crypto.Ed25519Field.unpack(point.t, le32(p.t), 0)
        end if
        point
    end limbPoint

    /** The eight points of order dividing 8: `[L] Q` for seeded points `Q` of the curve, distinct by encoding. */
    val torsionPoints: Seq[Ed25519Reference.Point] =
        val random = new java.util.Random(0x2551)
        @tailrec def collect(found: Map[Seq[Byte], Ed25519Reference.Point], remaining: Int): Seq[Ed25519Reference.Point] =
            if remaining == 0 then found.values.toSeq
            else
                val bytes = le32(BigInt(255, random).mod(P))
                Ed25519Reference.decode(bytes, 0) match
                    case Present(q) =>
                        val t = Ed25519Reference.multiply(L, q)
                        collect(found.updated(Ed25519Reference.encode(t).toSeq, t), remaining - 1)
                    case Absent => collect(found, remaining - 1)
                end match
        collect(Map.empty, 200)
    end torsionPoints

    /** `(sqrt(-1), 0)` with the even root, of order 4: the point `y = 0` encodes with the sign bit clear. */
    val torsion4: Ed25519Reference.Point = Ed25519Reference.decode(le32(BigInt(0)), 0).get

    /** `(0, -1)`, of order 2. */
    val torsion2: Ed25519Reference.Point = Ed25519Reference.decode(le32(P - 1), 0).get

    /** Whether the 32 bytes encode a point of order dividing 8, by the reference: `[8]P` is the identity. */
    def smallOrder(bytes: Array[Byte]): Boolean =
        Ed25519Reference.decode(bytes, 0).exists { p =>
            Ed25519Reference.samePoint(Ed25519Reference.multiply(BigInt(8), p), Ed25519Reference.Identity)
        }

    /** A key of mixed order, `[a]B + T` for the torsion point `T` and the secret scalar `a`: it passes the small-order check and still
      * carries a small-order component, so `R = [-k]T` with `S = k a mod L` verifies under it for every message whose `k` makes
      * `[-k]T` the point `R` encodes. That is how the leaves build a verifying signature with a small-order `R` without a signer's nonce.
      */
    final case class MixedOrderKey(scalar: BigInt, torsion: Ed25519Reference.Point, publicKey: Array[Byte])

    def mixedOrderKey(seed: Long, torsion: Ed25519Reference.Point): MixedOrderKey =
        val scalar = BigInt(253, new java.util.Random(seed)).mod(L - 1) + 1
        val point  = Ed25519Reference.add(Ed25519Reference.multiply(scalar, Ed25519Reference.Base), torsion)
        MixedOrderKey(scalar, torsion, Ed25519Reference.encode(point))
    end mixedOrderKey

    /** `r || S` with `S = k a mod L` for the `k` that `r`, the key and the message hash to; `r` is taken as given, canonical or not. */
    def mixedSignature(key: MixedOrderKey, r: Array[Byte], message: Array[Byte]): Array[Byte] =
        val k = littleEndian(Sha512.hashArrays(Chunk(r, key.publicKey, message))).mod(L)
        r ++ le32((k * key.scalar).mod(L))
    end mixedSignature

    /** The identity, `B`, `-B`, seeded multiples of `B`, and the torsion points. */
    val samplePoints: Seq[Ed25519Reference.Point] =
        val random = new java.util.Random(0x4d3)
        Seq(Ed25519Reference.Identity, Ed25519Reference.Base, Ed25519Reference.negate(Ed25519Reference.Base)) ++
            (0 until 4).map(_ => Ed25519Reference.multiply(BigInt(253, random).mod(L), Ed25519Reference.Base)) ++
            torsionPoints
    end samplePoints

    /** A key pair and `count` messages from one seed, through the reference signer. */
    final case class Generated(secretKey: Array[Byte], publicKey: Array[Byte], messages: Seq[Array[Byte]])

    def generated(seed: Long, count: Int): Generated =
        val random = new java.util.Random(seed)
        val secret = new Array[Byte](32)
        random.nextBytes(secret)
        val messages = (0 until count).map { i =>
            val message = new Array[Byte](i * 7 % 200)
            random.nextBytes(message)
            message
        }
        Generated(secret, Ed25519Reference.publicKey(secret), messages)
    end generated

end Ed25519Test
