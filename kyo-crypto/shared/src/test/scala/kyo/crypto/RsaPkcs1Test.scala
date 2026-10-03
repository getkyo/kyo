package kyo.crypto

import java.nio.charset.StandardCharsets
import kyo.*
import kyo.internal.crypto.TestVectors
import kyo.internal.crypto.TestVectorsJson.Json

class RsaPkcs1Test extends kyo.test.Test[Any]:

    "the JDK's verdict on every jdk-differential case: its own RS256 signatures, and one bit flipped in the message, the signature or n" in {
        val cases = kyo.internal.crypto.TestVectorsJdk.of("rs256")
        assert(cases.size == 96)
        assert(cases.count(_.flag("verdict")) == 24)
        cases.foreach { c =>
            val answer = Rsa.VerificationKey(c.big("n"), c.big("e")) match
                case Result.Success(key) => RsaPkcs1.verifySha256(key, Span.from(c.bytes("msg")), Span.from(c.bytes("sig")))
                case _                   => false
            assert(answer == c.flag("verdict"), s"tamper=${c.value("tamper")}: the JDK said ${c.flag("verdict")}")
        }
    }

    import RsaPkcs1Test.*
    import RsaTest.rfc7515

    "RFC 7515 appendix A.2's RS256 signature verifies, and not flipped or empty" in {
        assert(RsaPkcs1.verifySha256(rfc7515.key, Span.from(rfc7515.signingInput), Span.from(rfc7515.signature)))
        assert(!RsaPkcs1.verifySha256(rfc7515.key, Span.from(rfc7515.signingInput), Span.from(flip(rfc7515.signature, 9))))
        assert(!RsaPkcs1.verifySha256(rfc7515.key, Span.from(rfc7515.signingInput), Span.empty[Byte]))
    }

    "the array tier answers as the Span surface" in {
        assert(RsaPkcs1.verifySha256Array(rfc7515.key, rfc7515.signingInput, rfc7515.signature))
        assert(!RsaPkcs1.verifySha256Array(rfc7515.key, rfc7515.signingInput, flip(rfc7515.signature, 9)))
        assert(!RsaPkcs1.verifySha256Array(rfc7515.key, rfc7515.signingInput, Array.emptyByteArray))
    }

    "Wycheproof" - {
        Seq(2048, 3072, 4096, 8192).foreach { size =>
            s"rsa_signature_${size}_sha256_test.json" in {
                val root = Json.parse(TestVectors.text("wycheproof", s"rsa_signature_${size}_sha256_test.json"))
                assert(root("algorithm").string == "RSASSA-PKCS1-v1_5")
                val outcomes = root("testGroups").items.flatMap { group =>
                    assert(group("type").string == "RsassaPkcs1Verify")
                    assert(group("sha").string == "SHA-256")
                    assert(group("keySize").int == size)
                    val key = RsaTest.keyOf(Rsa.verificationKeyFromJwk(group("keyJwk")("n").string, group("keyJwk")("e").string))
                    assert(key.modulus == BigInt(group("publicKey")("modulus").string, 16))
                    assert(key.exponent == BigInt(group("publicKey")("publicExponent").string, 16))
                    group("tests").items.map { test =>
                        val id       = test("tcId").int
                        val flags    = test("flags").items.map(_.string)
                        val expected = test("result").string match
                            case "valid"                                     => true
                            case "invalid"                                   => false
                            case "acceptable" if flags == Seq("MissingNull") => false
                            case other => fail(s"tcId $id: result '$other' with flags $flags has no rule in this suite")
                        val answer = RsaPkcs1.verifySha256(key, Span.from(bytes(test("msg").string)), Span.from(bytes(test("sig").string)))
                        (id, answer == expected)
                    }
                }
                assert(outcomes.size == root("numberOfTests").int)
                val mismatched = outcomes.filterNot(_._2).map(_._1)
                assert(mismatched == Seq.empty, s"tcIds answering against their rule: $mismatched")
            }
        }
    }

    "signature length" - {
        "one byte short is rejected" in {
            assert(!RsaPkcs1.verifySha256Array(rfc7515.key, rfc7515.signingInput, rfc7515.signature.drop(1)))
        }

        "one byte long is rejected, even when the extra byte is a leading zero that keeps the value (Wycheproof tcId 246 is two bytes)" in {
            assert(!RsaPkcs1.verifySha256Array(rfc7515.key, rfc7515.signingInput, rfc7515.signature :+ 0.toByte))
            assert(!RsaPkcs1.verifySha256Array(rfc7515.key, rfc7515.signingInput, 0.toByte +: rfc7515.signature))
        }

        "empty is rejected (Wycheproof tcId 247)" in {
            assert(!RsaPkcs1.verifySha256Array(rfc7515.key, rfc7515.signingInput, Array.emptyByteArray))
        }
    }

    "signature value" - {
        "equal to the modulus is rejected (Wycheproof tcId 252)" in {
            val key = rfc7515.key
            assert(!RsaPkcs1.verifySha256Array(key, rfc7515.signingInput, fixed(key.modulus, key.sizeInBytes)))
        }

        "the modulus plus one is rejected (Wycheproof tcId 253)" in {
            val key = rfc7515.key
            assert(!RsaPkcs1.verifySha256Array(key, rfc7515.signingInput, fixed(key.modulus + 1, key.sizeInBytes)))
        }

        "a valid signature plus the modulus is rejected (Wycheproof tcId 244 and 245, SignatureMalleability)" in {
            val candidates = Seq(2048, 3072, 4096, 8192).flatMap(validCases).filter { c =>
                (BigInt(1, c.signature) + c.key.modulus).bitLength <= c.key.sizeInBytes * 8
            }
            assert(candidates.nonEmpty)
            candidates.foreach { c =>
                assert(RsaPkcs1.verifySha256Array(c.key, c.message, c.signature))
                val malleated = fixed(BigInt(1, c.signature) + c.key.modulus, c.key.sizeInBytes)
                assert(!RsaPkcs1.verifySha256Array(c.key, c.message, malleated))
            }
        }
    }

    "one flipped bit" - {
        "in the signature is rejected" in {
            Seq(0, 7, 1000, 2047).foreach { bit =>
                assert(!RsaPkcs1.verifySha256Array(rfc7515.key, rfc7515.signingInput, flip(rfc7515.signature, bit)))
            }
        }

        "in the message is rejected" in {
            Seq(0, 100).foreach { bit =>
                assert(!RsaPkcs1.verifySha256Array(rfc7515.key, flip(rfc7515.signingInput, bit), rfc7515.signature))
            }
        }
    }

    "a signature over one message does not verify another" in {
        assert(!RsaPkcs1.verifySha256Array(rfc7515.key, "another message".getBytes(StandardCharsets.UTF_8), rfc7515.signature))
    }

    "leaves the message and signature unchanged" in {
        val message   = rfc7515.signingInput.clone()
        val signature = rfc7515.signature.clone()
        assert(RsaPkcs1.verifySha256Array(rfc7515.key, message, signature))
        discard(RsaPkcs1.verifySha256Array(rfc7515.key, message, flip(signature, 5)))
        assert(message.sameElements(rfc7515.signingInput) && signature.sameElements(rfc7515.signature))
    }

    "known forgeries" - {

        /** The block is a forgery against a verifier with `lenience` and against no other, `signature` recovers exactly the block under
          * `key`, and this module refuses it.
          */
        def refused(
            label: String,
            key: Rsa.VerificationKey,
            message: Array[Byte],
            signature: Array[Byte],
            block: Array[Byte],
            lenience: Lenience
        )(using kyo.test.AssertScope): Unit =
            assert(Rsa.publicOperation(key, signature).exists(_.sameElements(block)), s"$label: the signature must recover the block")
            assert(parsedVerifies(block, message, lenience), s"$label: the reference parser with $lenience must accept the block")
            assert(!parsedVerifies(block, message, Lenience()), s"$label: the strict reference parser must refuse the block")
            assert(!RsaPkcs1.verifySha256Array(key, message, signature), s"$label: this module must refuse the forgery")
        end refused

        "the harness: a block signed with the test key's d is the JDK's own signature, and both parsers and this module accept it" in {
            val jdk     = kyo.internal.crypto.TestVectorsJdk.of("rs256").find(_.value("tamper") == "none").get
            val message = jdk.bytes("msg")
            val block   = emsa(sha256DigestInfo(message))
            assert(signWithD(block).sameElements(jdk.bytes("sig")))
            assert(parsedVerifies(block, message, Lenience()))
            assert(parsedVerifies(block, message, Lenience.all))
            assert(RsaPkcs1.verifySha256Array(testKey, message, signWithD(block)))
        }

        "CVE-2006-4339, Bleichenbacher's e = 3 forgery: a cube root with garbage after the DigestInfo, and no private key" in {
            val message = "CVE-2006-4339".getBytes(StandardCharsets.UTF_8)
            val prefix  = Array[Byte](0, 1) ++ Array.fill(8)(0xff.toByte) ++ Array[Byte](0) ++ sha256DigestInfo(message)
            val target  = BigInt(1, prefix) << (8 * (TestKeyBytes - prefix.length))
            val floor   = cubeRootFloor(target)
            val s       = if floor.pow(3) == target then floor else floor + 1
            val block   = fixed(s.pow(3), TestKeyBytes)
            assert(block.take(prefix.length).sameElements(prefix), "the cube keeps the prefix; the garbage absorbs the rounding")
            assert(s.pow(3) < exponentThreeKey.modulus, "no reduction: the verifier sees s^3 itself")
            refused("trailing garbage", exponentThreeKey, message, fixed(s, TestKeyBytes), block, Lenience(trailingBytes = true))
        }

        "CVE-2016-1494, python-rsa: an e = 3 cube root with garbage in the padding, and no private key" in {
            // The low bits of s^3 depend only on the low bits of s, so a cube root modulo 2^bits fixes the separator, DigestInfo and hash at
            // the end; the high bits come from a real cube root near 00 01, and the bytes between are garbage, retried until none is zero.
            val (message, suffix) = Iterator.from(0).map { i =>
                val candidate = s"CVE-2016-1494 $i".getBytes(StandardCharsets.UTF_8)
                (candidate, Array[Byte](0) ++ sha256DigestInfo(candidate))
            }.find((_, suffix) => (suffix.last & 1) == 1).get
            val bits   = suffix.length * 8
            val low    = cubeRootModPowerOfTwo(BigInt(1, suffix), bits)
            val top    = BigInt(1) << (8 * (TestKeyBytes - 2))
            val random = new scala.util.Random(1494)
            val s      = Iterator.continually {
                val high = cubeRootFloor(top + (top >> 2) + BigInt(8 * (TestKeyBytes - 2) - 2, random))
                ((high >> bits) << bits) | low
            }.find { s =>
                val block = fixed(s.pow(3), TestKeyBytes)
                block(1) == 1.toByte && block.slice(2, TestKeyBytes - suffix.length).forall(_ != 0.toByte)
            }.get
            val block = fixed(s.pow(3), TestKeyBytes)
            assert(block.takeRight(suffix.length).sameElements(suffix))
            refused("padding garbage", exponentThreeKey, message, fixed(s, TestKeyBytes), block, Lenience(anyPadding = true))
        }

        "CVE-2014-1568, BERserk: DigestInfo lengths in long form, with garbage in the bytes an overflowing reader drops" in {
            val message = "CVE-2014-1568".getBytes(StandardCharsets.UTF_8)
            val body    = tlv(0x30, Sha256Oid ++ DerNull) ++ tlv(0x04, Sha256.hashArray(message))
            Seq(
                "81 31 (Wycheproof tcId 9)"                    -> bytes("8131"),
                "82 00 31 (Wycheproof tcId 10)"                -> bytes("820031"),
                "89 and five garbage bytes before 00 00 00 31" -> bytes("89deadbeef4200000031")
            ).foreach { case (label, length) =>
                val block = emsa(Array(0x30.toByte) ++ length ++ body)
                refused(label, testKey, message, signWithD(block), block, Lenience(berLengths = true))
            }
        }

        "Chau et al., NDSS 2019 (CVE-2018-16151, CVE-2018-16152, CVE-2018-15836): parameter garbage, trailing bytes, unchecked padding" in {
            val message = "NDSS 2019".getBytes(StandardCharsets.UTF_8)
            val digest  = Sha256.hashArray(message)
            val garbage = bytes("0badc0ffee0ddf00d15ea5e0c0ffee42")
            Seq(
                "garbage in the algorithm parameters" -> (
                    emsa(digestInfo(Sha256Oid, tlv(0x04, garbage), digest)),
                    Lenience(anyParameters = true)
                ),
                "bytes after the hash (Wycheproof tcId 24, 30)" -> (
                    emsa(sha256DigestInfo(message) ++ garbage),
                    Lenience(trailingBytes = true)
                ),
                "padding bytes that are not 0xff" -> (
                    emsa(sha256DigestInfo(message), padding = i => (0x11 + i % 200).toByte),
                    Lenience(anyPadding = true)
                )
            ).foreach { case (label, (block, lenience)) =>
                refused(label, testKey, message, signWithD(block), block, lenience)
            }
        }

        "a hash named by the signature instead of by the verifier (Wycheproof WrongHash, tcId 215 to 235)" in {
            val message = "WrongHash".getBytes(StandardCharsets.UTF_8)
            Seq(
                "SHA-1" -> emsa(digestInfo(Sha1Oid, DerNull, Sha1.hashArray(message))),
                "MD5"   -> emsa(digestInfo(Md5Oid, DerNull, Md5.hashArray(message)))
            ).foreach { case (label, block) =>
                refused(label, testKey, message, signWithD(block), block, Lenience(hashFromOid = true))
            }
        }

        "a DigestInfo without the NULL parameters is refused by policy (Wycheproof tcId 8, MissingNull, acceptable)" in {
            // RFC 8017 section 9.2 note 2 names the NULL form as the one to generate; accepting both would give one signature two encodings.
            val message = "MissingNull".getBytes(StandardCharsets.UTF_8)
            val block   = emsa(digestInfo(Sha256Oid, Array.emptyByteArray, Sha256.hashArray(message)))
            refused("no parameters", testKey, message, signWithD(block), block, Lenience(anyParameters = true))
        }

        "padding under 8 bytes and block type 02 (Wycheproof tcId 238 and 243)" in {
            val message = "ShortPadding".getBytes(StandardCharsets.UTF_8)
            val info    = sha256DigestInfo(message)
            val short   = Array[Byte](0, 1) ++ Array.fill(7)(0xff.toByte) ++ Array[Byte](0) ++ info
            val tail    = Array.fill(TestKeyBytes - short.length)(0x5a.toByte)
            val random  = new scala.util.Random(238)
            Seq(
                "7 bytes of padding" -> (short ++ tail, Lenience(shortPadding = true, trailingBytes = true)),
                "block type 02"      -> (
                    emsa(info, padding = _ => (1 + random.nextInt(255)).toByte, blockType = 2),
                    Lenience(anyBlockType = true, anyPadding = true)
                )
            ).foreach { case (label, (block, lenience)) =>
                refused(label, testKey, message, signWithD(block), block, lenience)
            }
        }
    }

end RsaPkcs1Test

object RsaPkcs1Test:

    final case class Case(key: Rsa.VerificationKey, message: Array[Byte], signature: Array[Byte])

    def bytes(hex: String): Array[Byte] =
        require(hex.length % 2 == 0, s"odd-length hex: $hex")
        Array.tabulate(hex.length / 2)(i => Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16).toByte)

    /** `value` as exactly `length` big-endian bytes. */
    def fixed(value: BigInt, length: Int): Array[Byte] =
        val raw = value.toByteArray.dropWhile(_ == 0)
        require(raw.length <= length, s"$value does not fit $length bytes")
        Array.fill[Byte](length - raw.length)(0) ++ raw
    end fixed

    def flip(bytes: Array[Byte], bit: Int): Array[Byte] =
        val copy = bytes.clone()
        copy(bit / 8) = (copy(bit / 8) ^ (1 << (bit % 8))).toByte
        copy
    end flip

    def validCases(size: Int): Seq[Case] =
        val root = Json.parse(TestVectors.text("wycheproof", s"rsa_signature_${size}_sha256_test.json"))
        root("testGroups").items.flatMap { group =>
            val key = RsaTest.keyOf(Rsa.verificationKeyFromJwk(group("keyJwk")("n").string, group("keyJwk")("e").string))
            group("tests").items.filter(_("result").string == "valid").map { test =>
                Case(key, bytes(test("msg").string), bytes(test("sig").string))
            }
        }
    end validCases

    val Sha256Oid: Array[Byte] = bytes("0609608648016503040201")
    val Sha1Oid: Array[Byte]   = bytes("06052b0e03021a")
    val Md5Oid: Array[Byte]    = bytes("06082a864886f70d0205")
    val DerNull: Array[Byte]   = bytes("0500")

    /** The 2048-bit key pair of the jdk-differential set: the public half as a key, `d` to sign any crafted block. */
    lazy val testKey: Rsa.VerificationKey =
        RsaTest.keyOf(Rsa.VerificationKey(
            kyo.internal.crypto.TestVectorsJdk.rsaKey.big("n"),
            kyo.internal.crypto.TestVectorsJdk.rsaKey.big("e")
        ))

    /** The same modulus with `e = 3`: only the public operation matters to a cube-root forgery, and 3 is an exponent the key type admits. */
    lazy val exponentThreeKey: Rsa.VerificationKey = RsaTest.keyOf(Rsa.VerificationKey(testKey.modulus, 3))

    val TestKeyBytes = 256

    def signWithD(block: Array[Byte]): Array[Byte] =
        fixed(BigInt(1, block).modPow(kyo.internal.crypto.TestVectorsJdk.rsaKey.big("d"), testKey.modulus), TestKeyBytes)

    /** A DER TLV with a short-form length. */
    def tlv(tag: Int, content: Array[Byte]): Array[Byte] =
        require(content.length < 0x80)
        Array(tag.toByte, content.length.toByte) ++ content

    def digestInfo(oid: Array[Byte], parameters: Array[Byte], digest: Array[Byte]): Array[Byte] =
        tlv(0x30, tlv(0x30, oid ++ parameters) ++ tlv(0x04, digest))

    def sha256DigestInfo(message: Array[Byte]): Array[Byte] = digestInfo(Sha256Oid, DerNull, Sha256.hashArray(message))

    /** `00 blockType PS 00 suffix` over the test key's length, the padding byte at each index given by `padding`. */
    def emsa(suffix: Array[Byte], padding: Int => Byte = _ => 0xff.toByte, blockType: Int = 1): Array[Byte] =
        Array[Byte](0, blockType.toByte) ++ Array.tabulate(TestKeyBytes - 3 - suffix.length)(padding) ++ Array[Byte](0) ++ suffix

    def cubeRootFloor(x: BigInt): BigInt =
        @scala.annotation.tailrec
        def loop(r: BigInt): BigInt =
            val next = (2 * r + x / (r * r)) / 3
            if next >= r then r else loop(next)
        end loop
        loop(BigInt(1) << ((x.bitLength + 2) / 3))
    end cubeRootFloor

    /** The cube root of an odd `c` modulo `2^bits`: cubing is a bijection on odd residues, and bit `i` of `s` decides bit `i` of `s^3`. */
    def cubeRootModPowerOfTwo(c: BigInt, bits: Int): BigInt =
        require(c.testBit(0))
        (1 until bits).foldLeft(BigInt(1))((s, i) => if (s.pow(3) - c).testBit(i) then s.setBit(i) else s)

    /** The flaws of the historical PKCS#1 v1.5 verifiers that parse the recovered block, each one a switch. With none set,
      * [[parsedVerifies]] is a strict DER parser, which accepts exactly the block this module compares against.
      */
    final case class Lenience(
        trailingBytes: Boolean = false,
        anyPadding: Boolean = false,
        shortPadding: Boolean = false,
        anyBlockType: Boolean = false,
        berLengths: Boolean = false,
        anyParameters: Boolean = false,
        hashFromOid: Boolean = false
    )

    object Lenience:
        val all: Lenience = Lenience(true, true, true, true, true, true, true)

    /** The reference verifier: parses `block` as `00 01 PS 00 DigestInfo`, with the flaws `lenience` enables. */
    def parsedVerifies(block: Array[Byte], message: Array[Byte], lenience: Lenience): Boolean =
        val separator = block.indexWhere(_ == 0.toByte, 2)
        val padding   = if separator < 0 then Array.emptyByteArray else block.slice(2, separator)
        block.length >= 11 && block(0) == 0.toByte &&
        (block(1) == 1.toByte || (lenience.anyBlockType && block(1) == 2.toByte)) &&
        separator > 0 &&
        (lenience.anyPadding || padding.forall(_ == 0xff.toByte)) &&
        (lenience.shortPadding || padding.length >= 8) &&
        digestInfoVerifies(block, separator + 1, message, lenience)
    end parsedVerifies

    private def digestInfoVerifies(block: Array[Byte], at: Int, message: Array[Byte], lenience: Lenience): Boolean =
        val ber = lenience.berLengths
        readTlv(block, at, 0x30, ber, block.length).exists { (infoStart, infoEnd) =>
            readTlv(block, infoStart, 0x30, ber, infoEnd).exists { (algStart, algEnd) =>
                readTlv(block, algStart, 0x06, ber, algEnd).exists { (_, oidEnd) =>
                    val oid  = block.slice(algStart, oidEnd)
                    val hash =
                        if oid.sameElements(Sha256Oid) then Present(Sha256.hashArray(message))
                        else if !lenience.hashFromOid then Absent
                        else if oid.sameElements(Sha1Oid) then Present(Sha1.hashArray(message))
                        else if oid.sameElements(Md5Oid) then Present(Md5.hashArray(message))
                        else Absent
                    val parameters = block.slice(oidEnd, algEnd)
                    (lenience.anyParameters || parameters.sameElements(DerNull)) &&
                    readTlv(block, algEnd, 0x04, ber, infoEnd).exists { (digestStart, digestEnd) =>
                        hash.exists(_.sameElements(block.slice(digestStart, digestEnd))) &&
                        (lenience.trailingBytes || (digestEnd == infoEnd && infoEnd == block.length))
                    }
                }
            }
        }
    end digestInfoVerifies

    /** The content range of the TLV at `at` if its tag is `tag` and it ends by `limit`. DER takes a long-form length only above 127 with no
      * leading zero byte in at most four bytes; `ber` takes any, folding the bytes into an `Int`, which keeps only the low four, as the NSS
      * reader behind BERserk did.
      */
    private def readTlv(block: Array[Byte], at: Int, tag: Int, ber: Boolean, limit: Int): Maybe[(Int, Int)] =
        def within(start: Int, length: Int): Maybe[(Int, Int)] =
            if length >= 0 && start + length <= limit then Present((start, start + length)) else Absent
        if at + 2 > limit || (block(at) & 0xff) != tag then Absent
        else
            val first = block(at + 1) & 0xff
            if first < 0x80 then within(at + 2, first)
            else
                val count = first & 0x7f
                if count == 0 || at + 2 + count > limit then Absent
                else
                    val value = (0 until count).foldLeft(0)((acc, i) => (acc << 8) | (block(at + 2 + i) & 0xff))
                    val der   = count <= 4 && value >= 0x80 && block(at + 2) != 0.toByte
                    if ber || der then within(at + 2 + count, value) else Absent
                end if
            end if
        end if
    end readTlv

end RsaPkcs1Test
