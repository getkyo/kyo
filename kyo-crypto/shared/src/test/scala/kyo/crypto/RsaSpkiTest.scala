package kyo.crypto

import kyo.*
import kyo.crypto.Rsa.BoundsFailure
import kyo.crypto.Rsa.DerFailure
import kyo.crypto.Rsa.SpkiFailure

/** [[Rsa.encryptionKeyFromPem]] and [[Rsa.encryptionKeyFromSpki]]: the SubjectPublicKeyInfo reader and the checks it applies. */
class RsaSpkiTest extends kyo.test.Test[Any]:

    import RsaSpkiTest.*

    "the Span surface" - {
        "encryptionKeyFromSpki reads the key its array tier reads" in {
            assert(Rsa.encryptionKeyFromSpki(Span.from(testPubDer)) == Rsa.encryptionKeyFromPem(testPubPem))
            assert(Rsa.encryptionKeyFromSpki(Span.from(testPubDer)) == Rsa.encryptionKeyFromSpkiArray(testPubDer))
            assert(Rsa.encryptionKeyFromSpki(Span.empty[Byte]) == Result.fail(SpkiFailure.Der(DerFailure.EndOfData(0x30))))
            assert(Rsa.encryptionKeyFromSpkiArray(Array.emptyByteArray) == Result.fail(SpkiFailure.Der(DerFailure.EndOfData(0x30))))
        }
    }

    "PEM" - {
        "a 2048-bit key with e = 65537 is read out of its PEM" in {
            val key = keyOf(Rsa.encryptionKeyFromPem(testPubPem))
            assert(key.modulus.bitLength == 2048)
            assert(key.exponent == BigInt(65537))
            assert(key.sizeInBytes == 256)
            assert(key.modulus.testBit(0))
        }

        "the DER between the markers is what encryptionKeyFromSpki reads" in {
            assert(Rsa.encryptionKeyFromPem(testPubPem) == spki(testPubDer))
        }

        "whitespace anywhere in the body is ignored: space, tab, CR, LF, VT and FF" in {
            val spaced = testPubPem.replace("\n", " \t\r\n\u000b\f ").replace("MIIBIjAN", "MIIB\u000bIj\fAN")
            assert(Rsa.encryptionKeyFromPem(spaced) == Rsa.encryptionKeyFromPem(testPubPem))
        }

        "a no-break space in the body is PemNotBase64 at its offset, the same on every platform" in {
            val body = testPubPem.replace("MIIBIjAN", "MIIB" + 0x00a0.toChar + "jAN")
            assert(Rsa.encryptionKeyFromPem(body) == Result.fail(SpkiFailure.PemNotBase64(Base64.Failure.IllegalCharacter(4))))
            val lineSeparator = testPubPem.replace("MIIBIjAN", "MIIBIj" + 0x2028.toChar + "N")
            assert(Rsa.encryptionKeyFromPem(lineSeparator) == Result.fail(SpkiFailure.PemNotBase64(Base64.Failure.IllegalCharacter(6))))
        }

        "a final unit without its padding decodes as the padded body does" in {
            // The key's own DER is a multiple of 3 bytes and needs no padding; one trailing byte, which the reader ignores, makes the
            // body end in "==" and so gives a padded and an unpadded spelling of the same key.
            val padded = pemOf(testPubDer :+ 0x7f.toByte)
            assert(padded.contains("==\n-----END"))
            val unpadded = padded.replace("=", "")
            assert(Rsa.encryptionKeyFromPem(padded) == Rsa.encryptionKeyFromPem(testPubPem))
            assert(Rsa.encryptionKeyFromPem(unpadded) == Rsa.encryptionKeyFromPem(testPubPem))
        }

        "no BEGIN PUBLIC KEY marker is PemHeaderMissing" in {
            assert(Rsa.encryptionKeyFromPem("MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEA...") == Result.fail(SpkiFailure.PemHeaderMissing))
            assert(Rsa.encryptionKeyFromPem("") == Result.fail(SpkiFailure.PemHeaderMissing))
        }

        "a body that is not base64 is PemNotBase64 with the decoder's failure" in {
            val bad = "-----BEGIN PUBLIC KEY-----\n!!!!not-valid-base64!!!!\n-----END PUBLIC KEY-----\n"
            assert(Rsa.encryptionKeyFromPem(bad) == Result.fail(SpkiFailure.PemNotBase64(Base64.Failure.IllegalCharacter(0))))
        }

        "a body whose length is 1 mod 4 is PemNotBase64, not padded" in {
            val bad = "-----BEGIN PUBLIC KEY-----\nMIIBI\n-----END PUBLIC KEY-----\n"
            assert(Rsa.encryptionKeyFromPem(bad) == Result.fail(SpkiFailure.PemNotBase64(Base64.Failure.BadLength(5))))
        }
    }

    "DER" - {
        "an empty input is EndOfData at the outer SEQUENCE" in {
            assert(spki(Array.emptyByteArray) == Result.fail(SpkiFailure.Der(DerFailure.EndOfData(0x30))))
        }

        "a lone SEQUENCE tag is LengthEndOfData" in {
            assert(spki(Array[Byte](0x30)) == Result.fail(SpkiFailure.Der(DerFailure.LengthEndOfData)))
        }

        "a wrong tag names the tag found and its offset" in {
            assert(spki(Array[Byte](0x31, 0x00)) ==
                Result.fail(SpkiFailure.Der(DerFailure.UnexpectedTag(0x30, 0x31, 0))))
            val wrongInner = Array[Byte](0x30, 0x02, 0x04, 0x00)
            assert(spki(wrongInner) == Result.fail(SpkiFailure.Der(DerFailure.UnexpectedTag(0x30, 0x04, 2))))
        }

        "a long-form length of 0 or more than 4 bytes is LengthUnsupported at the length's offset" in {
            assert(spki(Array[Byte](0x30, 0x80.toByte)) ==
                Result.fail(SpkiFailure.Der(DerFailure.LengthUnsupported(1))))
            val fiveBytes = Array[Byte](0x30, 0x85.toByte, 0, 0, 0, 0, 1)
            assert(spki(fiveBytes) == Result.fail(SpkiFailure.Der(DerFailure.LengthUnsupported(1))))
            val cut = Array[Byte](0x30, 0x82.toByte, 0x01)
            assert(spki(cut) == Result.fail(SpkiFailure.Der(DerFailure.LengthUnsupported(1))))
        }

        "an algorithm identifier longer than the data is SkipPastEnd" in {
            val der = Array[Byte](0x30, 0x10, 0x30, 0x20, 0x06, 0x00)
            assert(spki(der) == Result.fail(SpkiFailure.Der(DerFailure.SkipPastEnd(0x20, 4))))
        }

        "an INTEGER longer than the data is IntegerExceedsData" in {
            val der = Array[Byte](0x30, 0x10, 0x30, 0x00, 0x03, 0x10, 0x00, 0x30, 0x10, 0x02, 0x10, 0x01)
            assert(spki(der) == Result.fail(SpkiFailure.Der(DerFailure.IntegerExceedsData(11))))
        }

        "the reader is lenient where BER allows it: declared lengths are not checked, the identifier is not compared, trailing bytes are ignored" in {
            val modulus = oddOfBits(2048)
            val rsaKey  = tlv(0x30, derInteger(modulus) ++ derInteger(BigInt(65537)))
            val other   = Array[Byte](0x30, 0x03, 0x06, 0x01, 0x2a)
            val loose   = Array[Byte](0x30, 0x05) ++ other ++ Array[Byte](0x03, 0x01, 0x00) ++ rsaKey ++ Array[Byte](0x7f, 0x7f)
            val key     = keyOf(spki(loose))
            assert(key.modulus == modulus && key.exponent == BigInt(65537))
        }

        "a four-byte length with its top bit set is LengthUnsupported, never a position moved backwards" in {
            // 0x84 ff ff ff f0 read as a signed Int is -16; skipping it would put the reader before the array.
            val algorithm = Array[Byte](0x30, 0x05, 0x30, 0x84.toByte, 0xff.toByte, 0xff.toByte, 0xff.toByte, 0xf0.toByte)
            assert(spki(algorithm) == Result.fail(SpkiFailure.Der(DerFailure.LengthUnsupported(3))))
            val integer = Array[Byte](
                0x30,
                0x10,
                0x30,
                0x00,
                0x03,
                0x10,
                0x00,
                0x30,
                0x10,
                0x02,
                0x84.toByte,
                0xff.toByte,
                0xff.toByte,
                0xff.toByte,
                0xff.toByte
            )
            assert(spki(integer) == Result.fail(SpkiFailure.Der(DerFailure.LengthUnsupported(10))))
        }

        "a length that fits an Int but not the data is reported against the data, not wrapped around" in {
            val skip = Array[Byte](0x30, 0x05, 0x30, 0x84.toByte, 0x7f, 0xff.toByte, 0xff.toByte, 0xff.toByte)
            assert(spki(skip) == Result.fail(SpkiFailure.Der(DerFailure.SkipPastEnd(Int.MaxValue, 8))))
            val integer = Array[Byte](
                0x30,
                0x10,
                0x30,
                0x00,
                0x03,
                0x10,
                0x00,
                0x30,
                0x10,
                0x02,
                0x84.toByte,
                0x7f,
                0xff.toByte,
                0xff.toByte,
                0xff.toByte
            )
            assert(spki(integer) == Result.fail(SpkiFailure.Der(DerFailure.IntegerExceedsData(15))))
        }
    }

    "parser attacks" - {
        def spkiWithIntegers(modulus: Array[Byte], exponent: Array[Byte]): Array[Byte] =
            val rsaKey = tlv(0x30, tlv(0x02, modulus) ++ tlv(0x02, exponent))
            tlv(0x30, rsaEncryptionAlgorithmId ++ tlv(0x03, Array(0x00.toByte) ++ rsaKey))

        "negative INTEGERs (the CVE-2016-2108 negative-zero class) are read as their magnitude: 02 01 80 is 128, 02 00 is 0, both under the floor" in {
            val e = BigInt(65537).toByteArray
            assert(spki(spkiWithIntegers(Array(0x80.toByte), e)) == boundsFailure(BoundsFailure.ModulusTooSmall(8, 1024)))
            assert(spki(spkiWithIntegers(Array(0x80.toByte, 0x00), e)) == boundsFailure(BoundsFailure.ModulusTooSmall(16, 1024)))
            assert(spki(spkiWithIntegers(Array.emptyByteArray, e)) == boundsFailure(BoundsFailure.ModulusTooSmall(0, 1024)))
        }

        "a modulus without its leading zero byte, negative in DER, is the same key as the canonical encoding (policy: BER magnitudes)" in {
            val modulus  = oddOfBits(2048)
            val unsigned = modulus.toByteArray.dropWhile(_ == 0.toByte)
            assert((unsigned(0) & 0x80) != 0 && unsigned.length == 256)
            val lax       = keyOf(spki(spkiWithIntegers(unsigned, BigInt(65537).toByteArray)))
            val canonical = keyOf(spki(spkiWithIntegers(modulus.toByteArray, BigInt(65537).toByteArray)))
            assert(lax == canonical && lax.modulus == modulus)
        }

        "PEM bodies that differ only in the bits after the last byte read as the same key (RFC 4648 section 3.5 lets the decoder ignore them)" in {
            // Policy pin: nothing compares a server key as PEM text, so the lenient decoder's second spelling is harmless; this leaf fails if
            // the PEM path ever starts reading those bits.
            val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
            val pem      = Seq(BigInt(3), BigInt(65537), BigInt(257)).map(keyPemOf(oddOfBits(2048), _)).find(_.contains("=")).get
            val end      = pem.indexOf('=')
            val last     = pem(end - 1)
            val other    = pem.substring(0, end - 1) + alphabet(alphabet.indexOf(last) ^ 1) + pem.substring(end)
            assert(other != pem)
            assert(keyOf(Rsa.encryptionKeyFromPem(other)) == keyOf(Rsa.encryptionKeyFromPem(pem)))
        }
    }

    "ceilings" - {
        "a modulus above 8192 bits is ModulusTooLarge, before any arithmetic" in {
            assert(Rsa.encryptionKeyFromPem(syntheticKeyPem(65536, BigInt(65537))) ==
                boundsFailure(BoundsFailure.ModulusTooLarge(65536, 8192)))
        }

        "an exponent above 64 bits is ExponentTooLarge" in {
            val huge = (BigInt(1) << 4095) | BigInt(1)
            assert(Rsa.encryptionKeyFromPem(syntheticKeyPem(2048, huge)) == boundsFailure(BoundsFailure.ExponentTooLarge(4096, 64)))
        }

        "a key at both ceilings is accepted" in {
            val key =
                keyOf(Rsa.encryptionKeyFromPem(syntheticKeyPem(Rsa.MaxModulusBits, (BigInt(1) << (Rsa.MaxExponentBits - 1)) | BigInt(1))))
            assert(key.modulus.bitLength == Rsa.MaxModulusBits)
            assert(key.exponent.bitLength == Rsa.MaxExponentBits)
        }

        "a 1024-bit modulus, the floor, with e = 3 is accepted, though the verification entry points reject it" in {
            val key = keyOf(Rsa.encryptionKeyFromPem(syntheticKeyPem(1024, BigInt(3))))
            assert(key.modulus.bitLength == 1024)
            assert(key.exponent == BigInt(3))
            assert(Rsa.VerificationKey(key.modulus, key.exponent) ==
                Result.fail(Rsa.KeyFailure.Bounds(BoundsFailure.ModulusTooSmall(1024, 2048))))
        }
    }

    "floor and parity" - {
        "a 1023-bit modulus is ModulusTooSmall" in {
            assert(Rsa.encryptionKeyFromPem(syntheticKeyPem(1023, BigInt(65537))) ==
                boundsFailure(BoundsFailure.ModulusTooSmall(1023, 1024)))
        }

        "a modulus of 0 or 1 is ModulusTooSmall with its bit length" in {
            assert(Rsa.encryptionKeyFromPem(keyPemOf(BigInt(0), BigInt(65537))) == boundsFailure(BoundsFailure.ModulusTooSmall(0, 1024)))
            assert(Rsa.encryptionKeyFromPem(keyPemOf(BigInt(1), BigInt(65537))) == boundsFailure(BoundsFailure.ModulusTooSmall(1, 1024)))
        }

        "an even modulus is ModulusEven" in {
            assert(Rsa.encryptionKeyFromPem(keyPemOf(oddOfBits(2048) + 1, BigInt(65537))) == boundsFailure(BoundsFailure.ModulusEven))
        }

        "an exponent of 0, 1 or 2 is ExponentTooSmall" in {
            Seq(0, 1, 2).foreach { e =>
                assert(Rsa.encryptionKeyFromPem(syntheticKeyPem(2048, BigInt(e))) ==
                    boundsFailure(BoundsFailure.ExponentTooSmall(BigInt(e))))
            }
        }

        "an even exponent is ExponentEven" in {
            assert(Rsa.encryptionKeyFromPem(syntheticKeyPem(2048, BigInt(65536))) ==
                boundsFailure(BoundsFailure.ExponentEven(BigInt(65536))))
        }

        "the checks run in the order of the verification factory: size, then modulus parity, then the exponent" in {
            assert(Rsa.encryptionKeyFromPem(keyPemOf(oddOfBits(512) + 1, BigInt(2))) ==
                boundsFailure(BoundsFailure.ModulusTooSmall(512, 1024)))
            assert(Rsa.encryptionKeyFromPem(keyPemOf(oddOfBits(2048) + 1, BigInt(2))) == boundsFailure(BoundsFailure.ModulusEven))
        }
    }

    "the key type" - {
        "two keys are equal exactly when their modulus and exponent are" in {
            val a = keyOf(Rsa.encryptionKeyFromPem(testPubPem))
            val b = keyOf(spki(testPubDer))
            val c = keyOf(Rsa.encryptionKeyFromPem(syntheticKeyPem(2048, BigInt(65537))))
            assert(a == b)
            assert(a.hashCode == b.hashCode)
            assert(a != c)
            assert(a.toString == s"EncryptionKey(${a.modulus}, 65537)")
        }

        "the public operation over it is the one over the verification key with the same numbers" in {
            val encryption   = keyOf(Rsa.encryptionKeyFromPem(testPubPem))
            val verification = RsaTest.keyOf(Rsa.VerificationKey(encryption.modulus, encryption.exponent))
            val input        = Array.tabulate[Byte](256)(i => (i * 7 + 1).toByte)
            input(0) = 0x00
            val fromEncryption = Rsa.publicOperation(encryption, input)
            assert(fromEncryption.exists(_.length == 256))
            assert(fromEncryption.map(_.toSeq) == Rsa.publicOperation(verification, input).map(_.toSeq))
            assert(Rsa.publicOperation(encryption, input.drop(1)) == Absent)
        }
    }

end RsaSpkiTest

object RsaSpkiTest:

    def spki(der: Array[Byte]): Result[SpkiFailure, Rsa.EncryptionKey] = Rsa.encryptionKeyFromSpki(Span.from(der))

    def boundsFailure(failure: Rsa.BoundsFailure): Result[SpkiFailure, Nothing] = Result.fail(SpkiFailure.Bounds(failure))

    /** Pre-generated RSA 2048-bit public key in SubjectPublicKeyInfo PEM format. */
    val testPubPem: String =
        """-----BEGIN PUBLIC KEY-----
MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEA0fjhZ5a4z9ULtk0Xdeq1
O79oB9+t9VWGEicXHNrkIsqPswer2tOwDE4hlu/GkDh8w2kO9K/x+q+mSPg5SzlT
dDHBTlLTQnHm8Wc74CPBJHExcwJzuq7Xy1c1tmD1m69EO1QFvJcso/10RK3pnJ8g
IpWqwVJ8QOsXSRnwvTJYAUX0A/HLISgxI4YFXQUKevNxdQlLd82Wne6qZIjZwiXc
JvvIoQ/d4dsFhMs0FSSw9fgXcG3x89kCSj2TyUl0KlyL5AWr1gRqS4Psjo62GTTc
sufsIMrHVlDaMkvdPnPFtyARqWknXA1Lj6DfjcBSwaQY9F0g7T4UxV9SobYFeftU
FwIDAQAB
-----END PUBLIC KEY-----"""

    val testPubDer: Array[Byte] =
        val body = testPubPem.replace("-----BEGIN PUBLIC KEY-----", "").replace("-----END PUBLIC KEY-----", "").replaceAll("\\s+", "")
        Base64.decode(body) match
            case Result.Success(bytes) => bytes.toArray
            case other                 => throw new IllegalStateException(s"the test key's PEM body is not base64: $other")
    end testPubDer

    def keyOf(result: Result[SpkiFailure, Rsa.EncryptionKey]): Rsa.EncryptionKey = result match
        case Result.Success(key) => key
        case other               => throw new IllegalStateException(s"expected a key, got $other")

    /** The odd number with exactly `bits` bits: `2^(bits - 1) + 1`. */
    def oddOfBits(bits: Int): BigInt = BigInt(0).setBit(bits - 1) + 1

    /** A syntactically valid SubjectPublicKeyInfo PEM with a modulus of exactly `modulusBits` bits and the given exponent. Built here
      * rather than generated with a key tool because the sizes these leaves need are ones no key tool will produce.
      */
    def syntheticKeyPem(modulusBits: Int, exponent: BigInt): String =
        keyPemOf(oddOfBits(modulusBits), exponent)

    def keyPemOf(modulus: BigInt, exponent: BigInt): String =
        val rsaKey    = tlv(0x30, derInteger(modulus) ++ derInteger(exponent))
        val bitString = tlv(0x03, Array(0x00.toByte) ++ rsaKey)
        pemOf(tlv(0x30, rsaEncryptionAlgorithmId ++ bitString))
    end keyPemOf

    /** `der` between the PEM markers, in 64-character lines as OpenSSL writes them. */
    def pemOf(der: Array[Byte]): String =
        val body = Base64.encode(Span.from(der)).grouped(64).mkString("\n")
        s"-----BEGIN PUBLIC KEY-----\n$body\n-----END PUBLIC KEY-----"

    /** DER AlgorithmIdentifier for `rsaEncryption` (OID 1.2.840.113549.1.1.1) with a NULL parameter. */
    val rsaEncryptionAlgorithmId: Array[Byte] =
        Array[Byte](0x30, 0x0d, 0x06, 0x09, 0x2a, 0x86.toByte, 0x48, 0x86.toByte, 0xf7.toByte, 0x0d, 0x01, 0x01, 0x01, 0x05, 0x00)

    /** One DER tag-length-value, using the long length form when the value needs it. */
    def tlv(tag: Int, value: Array[Byte]): Array[Byte] =
        val header =
            if value.length < 0x80 then Array(tag.toByte, value.length.toByte)
            else
                val lengthBytes = BigInt(value.length).toByteArray.dropWhile(_ == 0.toByte)
                Array(tag.toByte, (0x80 | lengthBytes.length).toByte) ++ lengthBytes
        header ++ value
    end tlv

    def derInteger(value: BigInt): Array[Byte] =
        tlv(0x02, value.toByteArray)

end RsaSpkiTest
