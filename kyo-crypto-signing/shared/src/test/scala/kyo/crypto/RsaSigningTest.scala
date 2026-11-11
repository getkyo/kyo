package kyo.crypto

import kyo.*
import kyo.crypto.RsaSigning.KeyFailure
import kyo.crypto.RsaSigning.SignFailure
import kyo.crypto.RsaSigning.SigningKey
import kyo.internal.crypto.DerReader
import kyo.internal.crypto.TestVectorsCavp.bytes
import kyo.internal.crypto.TestVectorsJdk
import kyo.internal.crypto.TestVectorsJson.Json
import kyo.internal.signing.TestVectors

class RsaSigningTest extends kyo.test.Test[Any]:

    import RsaSigningTest.*

    "signingKeyFromPem" - {
        "reads every Wycheproof key of exponent 65537 from its PKCS#1 PEM and its PKCS#8, with its public part" in {
            val keys = wycheproofGroups.filter(_("privateKey")("publicExponent").string == "010001")
            assert(keys.size == 11)
            keys.foreach { group =>
                val pkcs1 = RsaSigning.signingKeyFromPem(group("privateKeyPem").string)
                val pkcs8 = RsaSigning.signingKeyFromPem(pem("PRIVATE KEY", bytes(group("privateKeyPkcs8").string)))
                assert(pkcs1.map(_.verificationKey.modulus) == Result.succeed(BigInt(group("privateKey")("modulus").string, 16)))
                assert(pkcs1.map(_.verificationKey.exponent) == Result.succeed(BigInt(65537)))
                assert(pkcs8 == pkcs1)
            }
        }

        "refuses the Wycheproof keys of exponent 3, whose primes are not of one width" in {
            val failures = wycheproofGroups.filter(_("privateKey")("publicExponent").string == "03").map { group =>
                RsaSigning.signingKeyFromPem(group("privateKeyPem").string)
            }
            val expected: Seq[Result[KeyFailure, SigningKey]] = Seq(
                Result.fail(KeyFailure.PrimeSize(1364, 1024)),
                Result.fail(KeyFailure.PrimeSize(1365, 1024)),
                Result.fail(KeyFailure.PrimeSize(1364, 1024)),
                Result.fail(KeyFailure.PrimeSize(2047, 1536)),
                Result.fail(KeyFailure.PrimeSize(2047, 1536))
            )
            assert(failures == expected)
        }

        "reads a PKCS#8 of version 1, RFC 5958's, and a body with its padding dropped and its lines spaced" in {
            val numbers = jdkNumbers("2048-e3")
            val key     = keyOf(RsaSigning.signingKeyFromPkcs1Array(pkcs1(numbers)))
            assert(RsaSigning.signingKeyFromPem(pem("PRIVATE KEY", pkcs8(1, RsaEncryption, pkcs1(numbers)))) == Result.succeed(key))
            val spaced = pem("RSA PRIVATE KEY", pkcs1(numbers)).replace("=", "").replace("\n", "\r\n  ")
            assert(RsaSigning.signingKeyFromPem(spaced) == Result.succeed(key))
        }

        "fails the PEM's checks: its header, then its base64" in {
            assert(RsaSigning.signingKeyFromPem(pem("PUBLIC KEY", Array[Byte](1, 2, 3))) == Result.fail(KeyFailure.PemHeaderMissing))
            assert(RsaSigning.signingKeyFromPem("") == Result.fail(KeyFailure.PemHeaderMissing))
            assert(
                RsaSigning.signingKeyFromPem("-----BEGIN RSA PRIVATE KEY-----\n!!!!\n-----END RSA PRIVATE KEY-----") ==
                    Result.fail(KeyFailure.PemNotBase64(Base64.Failure.IllegalCharacter(0)))
            )
            assert(
                RsaSigning.signingKeyFromPem("-----BEGIN PRIVATE KEY-----\n!!!!\n-----END PRIVATE KEY-----") ==
                    Result.fail(KeyFailure.PemNotBase64(Base64.Failure.IllegalCharacter(0)))
            )
        }

        "fails the DER's checks: a structure cut short, a version, the algorithm" in {
            val numbers = jdkNumbers("2048-e3")
            val der     = pkcs1(numbers)
            assert(
                RsaSigning.signingKeyFromPem("-----BEGIN RSA PRIVATE KEY-----\n-----END RSA PRIVATE KEY-----") ==
                    Result.fail(KeyFailure.Der(Rsa.DerFailure.EndOfData(0x30)))
            )
            assert(
                RsaSigning.signingKeyFromPkcs1Array(sequence(integer(BigInt(0)) ++ integer(numbers.n))) ==
                    Result.fail(KeyFailure.Der(Rsa.DerFailure.EndOfData(0x02)))
            )
            assert(
                RsaSigning.signingKeyFromPkcs1Array(pkcs1(numbers.copy(version = BigInt(1)))) ==
                    Result.fail(KeyFailure.VersionUnsupported(BigInt(1)))
            )
            assert(RsaSigning.signingKeyFromPkcs8Array(pkcs8(2, RsaEncryption, der)) ==
                Result.fail(KeyFailure.VersionUnsupported(BigInt(2))))
            val ecPublicKey = bytes("06072a8648ce3d020106082a8648ce3d030107")
            assert(RsaSigning.signingKeyFromPkcs8Array(pkcs8(0, ecPublicKey, der)) == Result.fail(KeyFailure.AlgorithmNotRsa))
            val withoutNull = RsaEncryption.dropRight(2)
            assert(RsaSigning.signingKeyFromPkcs8Array(pkcs8(0, withoutNull, der)) == Result.fail(KeyFailure.AlgorithmNotRsa))
            assert(
                RsaSigning.signingKeyFromPkcs8Array(pkcs8(1, RsaEncryption, pkcs1(numbers.copy(version = BigInt(1))))) ==
                    Result.fail(KeyFailure.VersionUnsupported(BigInt(1)))
            )
        }

        "rejects contents outside the PKCS#1 sequence and extra integers inside it" in {
            val numbers = jdkNumbers("2048-e3")
            val content = numbers.all.toArray.flatMap(integer)
            Seq(Array[Byte](0x30, 0) ++ content, sequence(content) ++ Array[Byte](0), sequence(content ++ integer(BigInt(1))))
                .foreach { der =>
                    assert(RsaSigning.signingKeyFromPem(pem("RSA PRIVATE KEY", der)) == Result.fail(KeyFailure.TrailingData))
                }
        }

        "rejects empty, negative and redundant INTEGER encodings" in {
            val numbers = jdkNumbers("2048-e3")
            val rest    = numbers.all.drop(1).toArray.flatMap(integer)
            Seq(value(0x02, Array.emptyByteArray), value(0x02, Array[Byte](0, 0))).foreach { version =>
                assert(RsaSigning.signingKeyFromPkcs1Array(sequence(version ++ rest)) == Result.fail(KeyFailure.IntegerEncoding))
            }
            val negativeModulus = value(0x02, numbers.n.toByteArray.drop(1))
            val negative        = sequence(integer(BigInt(0)) ++ negativeModulus ++ numbers.all.drop(2).toArray.flatMap(integer))
            assert(RsaSigning.signingKeyFromPkcs1Array(negative) == Result.fail(KeyFailure.IntegerEncoding))
        }

        "rejects PKCS#8 trailing data and a conflicting optional public key" in {
            val numbers = jdkNumbers("2048-e3")
            val content = integer(BigInt(1)) ++ value(0x30, RsaEncryption) ++ value(0x04, pkcs1(numbers))
            assert(RsaSigning.signingKeyFromPkcs8Array(sequence(content) ++ Array[Byte](0)) == Result.fail(KeyFailure.TrailingData))
            val conflicting = Array[Byte](0) ++ sequence(integer(numbers.n + 2) ++ integer(numbers.e))
            assert(RsaSigning.signingKeyFromPkcs8Array(sequence(content ++ value(0x81, conflicting))) ==
                Result.fail(KeyFailure.PublicKeyMismatch))
        }

        "reads PKCS#8 attributes and the matching optional public key" in {
            val numbers   = jdkNumbers("2048-e3")
            val encoded   = pkcs1(numbers)
            val key       = keyOf(RsaSigning.signingKeyFromPkcs1Array(encoded))
            val body      = value(0x30, RsaEncryption) ++ value(0x04, encoded)
            val publicKey = value(0x81, Array[Byte](0) ++ sequence(integer(numbers.n) ++ integer(numbers.e)))
            // An empty attribute set is valid; metadata is not used to construct the RSA key.
            val attributes = value(0xa0, Array.emptyByteArray)
            assert(RsaSigning.signingKeyFromPkcs8Array(sequence(integer(BigInt(0)) ++ body ++ attributes)) == Result.succeed(key))
            assert(RsaSigning.signingKeyFromPkcs8Array(sequence(integer(BigInt(1)) ++ body ++ attributes ++ publicKey)) ==
                Result.succeed(key))
            assert(RsaSigning.signingKeyFromPkcs8Array(sequence(integer(BigInt(0)) ++ body ++ publicKey)) ==
                Result.fail(KeyFailure.PublicKeyMismatch))
            assert(RsaSigning.signingKeyFromPkcs8Array(sequence(integer(BigInt(1)) ++ body ++ publicKey ++ publicKey)) ==
                Result.fail(KeyFailure.TrailingData))
            val misaligned = value(0x81, Array[Byte](1) ++ sequence(integer(numbers.n) ++ integer(numbers.e)))
            assert(RsaSigning.signingKeyFromPkcs8Array(sequence(integer(BigInt(1)) ++ body ++ misaligned)) ==
                Result.fail(KeyFailure.PublicKeyMismatch))
        }

        "rejects truncated sequence contents before reading a key" in {
            val der = pkcs1(jdkNumbers("2048-e3"))
            assert(RsaSigning.signingKeyFromPkcs1Array(der.dropRight(1)) ==
                Result.fail(KeyFailure.Der(Rsa.DerFailure.SkipPastEnd(der.length - 4, 4))))
            val short = Array[Byte](0x30, 0x02, 0x02, 0x02)
            assert(RsaSigning.signingKeyFromPkcs1Array(short) == Result.fail(KeyFailure.Der(Rsa.DerFailure.SkipPastEnd(2, 2))))
        }

        "rejects a private exponent outside the modulus" in {
            val numbers   = jdkNumbers("2048-e3")
            val lambda    = (numbers.p - 1) * (numbers.q - 1) / (numbers.p - 1).gcd(numbers.q - 1)
            val oversized = numbers.copy(d = numbers.d + numbers.n * lambda)
            assert(RsaSigning.signingKeyFromPkcs1Array(pkcs1(oversized)) == Result.fail(KeyFailure.PrivateExponent))
        }

        "fails the numbers' checks, each with the one number it names changed" in {
            val numbers                                                = jdkNumbers("2048-e3")
            def read(changed: Numbers): Result[KeyFailure, SigningKey] = RsaSigning.signingKeyFromPkcs1Array(pkcs1(changed))
            assert(
                read(numbers.copy(n = (BigInt(1) << 1023) + 1)) ==
                    Result.fail(KeyFailure.Bounds(Rsa.BoundsFailure.ModulusTooSmall(1024, Rsa.MinModulusBits)))
            )
            assert(read(numbers.copy(e = BigInt(4))) == Result.fail(KeyFailure.Bounds(Rsa.BoundsFailure.ExponentEven(BigInt(4)))))
            assert(read(numbers.copy(p = numbers.p >> 1)) == Result.fail(KeyFailure.PrimeSize(1023, 1024)))
            assert(read(numbers.copy(q = numbers.q << 1)) == Result.fail(KeyFailure.PrimeSize(1025, 1024)))
            assert(read(numbers.copy(p = BigInt(0), q = BigInt(0))) == Result.fail(KeyFailure.PrimeSize(0, 1024)))
            assert(read(numbers.copy(n = numbers.n + 2)) == Result.fail(KeyFailure.NotProduct))
            assert(read(numbers.copy(d = numbers.d + 2)) == Result.fail(KeyFailure.PrivateExponent))
            assert(read(numbers.copy(dP = numbers.dP + 2)) == Result.fail(KeyFailure.CrtExponent))
            assert(read(numbers.copy(dQ = numbers.dQ + 2)) == Result.fail(KeyFailure.CrtExponent))
            assert(read(numbers.copy(qInv = numbers.qInv + 1)) == Result.fail(KeyFailure.CrtCoefficient))
            assert(read(numbers.copy(qInv = numbers.qInv + numbers.p)) == Result.fail(KeyFailure.CrtCoefficient))
        }

        "a key equals the same key from the other encoding, and prints its size alone" in {
            val numbers = jdkNumbers("2048-e3")
            val key     = keyOf(RsaSigning.signingKeyFromPkcs1Array(pkcs1(numbers)))
            assert(RsaSigning.signingKeyFromPkcs8Array(pkcs8(0, RsaEncryption, pkcs1(numbers))) == Result.succeed(key))
            assert(key.toString == "RsaSigning.SigningKey(2048 bits)")
            assert(key.sizeInBytes == 256)
            assert(key.blindingSizeInBytes == 528)
            assert(key != keyOf(RsaSigning.signingKeyFromPkcs1Array(pkcs1(jdkNumbers("2048-e64")))))
        }
    }

    "signSha256" - {
        "signs as Wycheproof's RS256 vectors at 2048, 3072 and 4096 bits" in {
            val groups = wycheproofGroups.filter { group =>
                group("sha").string == "SHA-256" && group("privateKey")("publicExponent").string == "010001"
            }
            val signed = groups.flatMap { group =>
                val key = keyOf(RsaSigning.signingKeyFromPem(group("privateKeyPem").string))
                group("tests").items.map { test =>
                    assert(test("result").string == "valid")
                    val signature = RsaSigning.signSha256(key, Span.from(bytes(test("msg").string)), blinding(key, test("tcId").int))
                    assert(signature.map(_.toArray.toSeq) == Result.succeed(bytes(test("sig").string).toSeq), s"tcId ${test("tcId").int}")
                    key.sizeInBytes
                }
            }
            assert(signed == Seq.fill(8)(256) ++ Seq.fill(8)(384) ++ Seq.fill(8)(512))
        }

        "signs as the JDK at 8192 bits and at public exponents 3 and 2^64 - 59" in {
            val signed = jdkCases.filter(_.kind == "rsa-signing-key").map { line =>
                val key = keyOf(RsaSigning.signingKeyFromPkcs1Array(line.bytes("pkcs1")))
                assert(RsaSigning.signingKeyFromPkcs8Array(line.bytes("pkcs8")) == Result.succeed(key))
                assert(key.verificationKey.modulus == line.big("n"))
                assert(key.verificationKey.exponent == line.big("e"))
                val cases = jdkCases.filter(c => c.kind == "rs256-sign" && c.value("key") == line.value("name"))
                cases.foreach { c =>
                    val signature = RsaSigning.signSha256(key, Span.from(c.bytes("msg")), blinding(key, c.bytes("msg").length))
                    assert(
                        signature.map(_.toArray.toSeq) == Result.succeed(c.bytes("sig").toSeq),
                        s"${line.value("name")} ${c.value("msg")}"
                    )
                }
                (line.value("name"), key.verificationKey.modulus.bitLength, cases.size)
            }
            assert(signed == Seq(("8192", 8192, 6), ("2048-e3", 2048, 6), ("2048-e64", 2048, 6)))
        }

        "gives one signature under two blindings, which verifies under the public part" in {
            val key     = jdkKey("2048-e64")
            val message = Span.from("header.payload".getBytes("US-ASCII"))
            val first   = RsaSigning.signSha256(key, message, blinding(key, 1))
            val second  = RsaSigning.signSha256(key, message, blinding(key, 2))
            assert(first.map(_.toArray.toSeq) == second.map(_.toArray.toSeq))
            assert(first.map(signature => RsaPkcs1.verifySha256(key.verificationKey, message, signature)) == Result.succeed(true))
        }

        "fails a blinding of another length with the length it takes" in {
            val key = jdkKey("2048-e3")
            Seq(0, 527, 529, 264).foreach { length =>
                val result = RsaSigning.signSha256(key, Span.from(Array[Byte](1)), Span.from(new Array[Byte](length)))
                assert(result.map(_.toArray.toSeq) == Result.fail(SignFailure.BlindingLength(528)), s"length=$length")
            }
        }

        "fails a blinding whose values have no inverse modulo n" in {
            val key    = jdkKey("2048-e3")
            val result = RsaSigning.signSha256(key, Span.from(Array[Byte](1)), Span.from(new Array[Byte](key.blindingSizeInBytes)))
            assert(result.map(_.toArray.toSeq) == Result.fail(SignFailure.BlindingNotInvertible))
        }

        "releases no signature that fails the check under the public part" in {
            val key       = jdkKey("2048-e3")
            val encoded   = RsaPkcs1.encode(Sha256.hashArray(Array[Byte](1, 2, 3)), key.sizeInBytes)
            val signature = signed(RsaSigning.signSha256Array(key, Array[Byte](1, 2, 3), blinding(key, 3).toArray))
            assert(RsaSigning.verified(key, encoded, signature).map(_.toSeq) == Result.succeed(signature.toSeq))
            Seq(0, 7, 1000, signature.length * 8 - 1).foreach { bit =>
                val flipped = signature.clone()
                flipped(bit / 8) = (flipped(bit / 8) ^ (1 << (bit % 8))).toByte
                assert(RsaSigning.verified(key, encoded, flipped).map(_.toSeq) == Result.fail(SignFailure.Fault), s"bit=$bit")
            }
        }

        "modifies neither the message nor the blinding" in {
            val key     = jdkKey("2048-e3")
            val message = Array[Byte](9, 8, 7)
            val drawn   = blinding(key, 4).toArray
            val before  = (message.toSeq, drawn.toSeq)
            assert(RsaSigning.signSha256Array(key, message, drawn).isSuccess)
            assert((message.toSeq, drawn.toSeq) == before)
        }
    }

end RsaSigningTest

object RsaSigningTest:

    val wycheproofGroups: Seq[Json] =
        Seq(2048, 3072, 4096).flatMap { size =>
            val root = Json.parse(TestVectors.text("wycheproof", s"rsa_pkcs1_${size}_sig_gen_test.json"))
            root("testGroups").items
        }

    val jdkCases: Seq[TestVectorsJdk.Case] =
        TestVectorsJdk.parse(TestVectors.text("jdk-differential", "cases.txt"), Set("rsa-signing-key", "rs256-sign"))

    /** The contents of rsaEncryption's AlgorithmIdentifier, with its NULL parameters. */
    val RsaEncryption: Array[Byte] = bytes("06092a864886f70d0101010500")

    /** An RSAPrivateKey's integers, in their order. */
    final case class Numbers(version: BigInt, n: BigInt, e: BigInt, d: BigInt, p: BigInt, q: BigInt, dP: BigInt, dQ: BigInt, qInv: BigInt):
        def all: Seq[BigInt] = Seq(version, n, e, d, p, q, dP, dQ, qInv)

    /** The integers of a JDK key's PKCS#1, read apart from `RsaSigning`'s reader, and checked to encode back to the JDK's bytes. */
    def jdkNumbers(name: String): Numbers =
        val der    = jdkLine(name).bytes("pkcs1")
        val reader = new DerReader(der)
        val read   = reader.readTag(0x30).flatMap(_ => reader.readLength()).flatMap(_ => Result.collect(Seq.fill(9)(reader.readInteger())))
        read match
            case Result.Success(all) =>
                val numbers = Numbers(all(0), all(1), all(2), all(3), all(4), all(5), all(6), all(7), all(8))
                assert(pkcs1(numbers).toSeq == der.toSeq)
                numbers
            case other => throw new IllegalStateException(s"the JDK's $name key does not read as an RSAPrivateKey: $other")
        end match
    end jdkNumbers

    def jdkKey(name: String): SigningKey = keyOf(RsaSigning.signingKeyFromPkcs1Array(jdkLine(name).bytes("pkcs1")))

    def jdkLine(name: String): TestVectorsJdk.Case =
        jdkCases.find(c => c.kind == "rsa-signing-key" && c.value("name") == name).getOrElse(throw new NoSuchElementException(name))

    def keyOf(result: Result[KeyFailure, SigningKey]): SigningKey = result match
        case Result.Success(key) => key
        case other               => throw new IllegalStateException(s"expected a key, got $other")

    def signed(result: Result[SignFailure, Array[Byte]]): Array[Byte] = result match
        case Result.Success(signature) => signature
        case other                     => throw new IllegalStateException(s"expected a signature, got $other")

    /** A blinding of the length the key takes, its bytes fixed by `seed`. */
    def blinding(key: SigningKey, seed: Int): Span[Byte] =
        val out = new Array[Byte](key.blindingSizeInBytes)
        new scala.util.Random(seed).nextBytes(out)
        Span.from(out)
    end blinding

    def pem(label: String, der: Array[Byte]): String =
        s"-----BEGIN $label-----\n" + Base64.encode(Span.from(der)).grouped(64).mkString("\n") + s"\n-----END $label-----\n"

    def pkcs1(numbers: Numbers): Array[Byte] = sequence(numbers.all.toArray.flatMap(integer))

    def pkcs8(version: Int, algorithm: Array[Byte], key: Array[Byte]): Array[Byte] =
        sequence(integer(BigInt(version)) ++ value(0x30, algorithm) ++ value(0x04, key))

    def sequence(content: Array[Byte]): Array[Byte] = value(0x30, content)

    def integer(number: BigInt): Array[Byte] = value(0x02, number.toByteArray)

    /** A DER tag, its length, short or long form, and its contents. */
    def value(tag: Int, content: Array[Byte]): Array[Byte] =
        val length =
            if content.length < 0x80 then Array(content.length.toByte)
            else
                val digits = BigInt(content.length).toByteArray.dropWhile(_ == 0)
                (0x80 | digits.length).toByte +: digits
        (tag.toByte +: length) ++ content
    end value

end RsaSigningTest
