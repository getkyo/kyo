package kyo.internal.crypto

import kyo.*

/** RSA public keys and the public-key operation, built only through checks that bound what an exponentiation may cost.
  *
  * A key usually arrives from a peer (a JSON Web Key set fetched over the network, a database server's key in an authentication packet),
  * and `m.modPow(e, n)` costs time quadratic in the modulus length and linear in the exponent's, so both are bounded before any
  * arithmetic runs: at most [[MaxModulusBits]] and [[MaxExponentBits]], the ceilings every entry point applies.
  *
  * Two kinds of key, each its own type, since they differ in what else was checked:
  *
  *   - [[PublicKey]], built by [[PublicKey.apply]] and [[publicKeyFromJwk]], is a key for signature verification. Those add RFC 7518
  *     section 3.3's floor of 2048 bits for RS256, and reject an even modulus, an exponent below 3 and an even exponent, which RFC 8017
  *     section 3.1 rules out for any RSA key. A JWK's `n` and `e` must be unpadded base64url without leading zero octets, as RFC 7518
  *     section 6.3.1 requires.
  *   - [[EncryptionKey]], built by [[encryptionKeyFromPem]] and [[encryptionKeyFromSpki]], is a key a peer offers to encrypt to (a
  *     SubjectPublicKeyInfo, as a MySQL server sends its `sha256_password` key). Those apply the two ceilings and nothing else, so every
  *     key such a server can be configured with is accepted; the reader takes BER as well as DER and does not compare the algorithm
  *     identifier. An `EncryptionKey` with `e = 1` or a 512-bit modulus is a valid value, which is why no verifier accepts the type: a
  *     signature check over it would verify every message.
  *
  * [[publicOperation]] is RSAEP and RSAVP1 of RFC 8017 sections 5.1.1 and 5.2.2, one computation, over octet strings of the key's length,
  * defined for both kinds.
  *
  * Failure is a [[Rsa.KeyFailure]] or [[Rsa.SpkiFailure]] value carrying what was measured, never an exception and never a module's own
  * error type: each caller maps it to its own failure leaf.
  */
private[kyo] object Rsa:

    val MinModulusBits: Int = 2048

    val MaxModulusBits: Int = 8192

    val MaxExponentBits: Int = 64

    /** An RSA public key whose modulus and exponent passed every verification check of [[Rsa]].
      *
      * A plain class rather than a case class: a case class companion gets a public synthesized `fromProduct` that calls the private
      * constructor, which would build a key past every check.
      */
    final class PublicKey private (val modulus: BigInt, val exponent: BigInt) derives CanEqual:
        def sizeInBytes: Int = (modulus.bitLength + 7) / 8

        override def equals(other: Any): Boolean = other match
            case that: PublicKey => modulus == that.modulus && exponent == that.exponent
            case _               => false

        override def hashCode: Int = 31 * modulus.hashCode + exponent.hashCode

        override def toString: String = s"PublicKey($modulus, $exponent)"
    end PublicKey

    object PublicKey:
        def apply(modulus: BigInt, exponent: BigInt): Result[KeyFailure, PublicKey] =
            val modulusBits  = modulus.bitLength
            val exponentBits = exponent.bitLength
            if modulus.signum <= 0 || modulusBits < MinModulusBits then Result.fail(KeyFailure.ModulusTooSmall(modulusBits, MinModulusBits))
            else if modulusBits > MaxModulusBits then Result.fail(KeyFailure.ModulusTooLarge(modulusBits, MaxModulusBits))
            else if !modulus.testBit(0) then Result.fail(KeyFailure.ModulusEven)
            else if exponent < 3 then Result.fail(KeyFailure.ExponentTooSmall(exponent))
            else if exponentBits > MaxExponentBits then Result.fail(KeyFailure.ExponentTooLarge(exponentBits, MaxExponentBits))
            else if !exponent.testBit(0) then Result.fail(KeyFailure.ExponentEven(exponent))
            else Result.succeed(new PublicKey(modulus, exponent))
            end if
        end apply
    end PublicKey

    /** A peer's RSA public key whose modulus and exponent sit under the two cost ceilings, and nothing else was checked. Its only use is
      * encrypting to the peer; see [[Rsa]] for why it is not a [[PublicKey]].
      *
      * A plain class for the reason [[PublicKey]] is one.
      */
    final class EncryptionKey private (val modulus: BigInt, val exponent: BigInt) derives CanEqual:
        def sizeInBytes: Int = (modulus.bitLength + 7) / 8

        override def equals(other: Any): Boolean = other match
            case that: EncryptionKey => modulus == that.modulus && exponent == that.exponent
            case _                   => false

        override def hashCode: Int = 31 * modulus.hashCode + exponent.hashCode

        override def toString: String = s"EncryptionKey($modulus, $exponent)"
    end EncryptionKey

    object EncryptionKey:
        private[Rsa] def underCeilings(modulus: BigInt, exponent: BigInt): Result[SpkiFailure, EncryptionKey] =
            val modulusBits  = modulus.bitLength
            val exponentBits = exponent.bitLength
            if modulusBits > MaxModulusBits then Result.fail(SpkiFailure.ModulusTooLarge(modulusBits, MaxModulusBits))
            else if exponentBits > MaxExponentBits then Result.fail(SpkiFailure.ExponentTooLarge(exponentBits, MaxExponentBits))
            else Result.succeed(new EncryptionKey(modulus, exponent))
        end underCeilings
    end EncryptionKey

    /** The key a JSON Web Key's `n` and `e` members describe (RFC 7518 section 6.3.1). */
    def publicKeyFromJwk(n: String, e: String): Result[KeyFailure, PublicKey] =
        unsigned(n, Component.Modulus).flatMap { modulus =>
            unsigned(e, Component.Exponent).flatMap(exponent => PublicKey(modulus, exponent))
        }

    /** The key a PEM `PUBLIC KEY` block holds: a base64 SubjectPublicKeyInfo between the two marker lines, whitespace anywhere. A body
      * whose final unit lacks its `=` padding is padded before decoding. Under the two ceilings only.
      */
    def encryptionKeyFromPem(pem: String): Result[SpkiFailure, EncryptionKey] =
        if !pem.contains(PemHeader) then Result.fail(SpkiFailure.PemHeaderMissing)
        else
            val body   = pem.replace(PemHeader, "").replace(PemFooter, "").replaceAll("\\s+", "")
            val padded =
                body.length % 4 match
                    case 2 => body + "=="
                    case 3 => body + "="
                    case _ => body
            Base64.decode(padded) match
                case Result.Success(der) => encryptionKeyFromSpki(der.toArray)
                case Result.Failure(e)   => Result.fail(SpkiFailure.PemNotBase64(e))
                case panic: Result.Panic => panic
            end match
        end if
    end encryptionKeyFromPem

    /** The key a DER SubjectPublicKeyInfo holds (RFC 5280 section 4.1, with the RSAPublicKey of RFC 8017 appendix A.1.1 in its BIT
      * STRING). Under the two ceilings only.
      */
    def encryptionKeyFromSpki(der: Array[Byte]): Result[SpkiFailure, EncryptionKey] =
        val reader                                       = new DerReader(der)
        val parsed: Result[DerFailure, (BigInt, BigInt)] =
            for
                _               <- reader.readTag(0x30)
                _               <- reader.readLength()
                _               <- reader.readTag(0x30)
                algorithmLength <- reader.readLength()
                _               <- reader.skip(algorithmLength)
                _               <- reader.readTag(0x03)
                _               <- reader.readLength()
                _               <- reader.skip(1)
                _               <- reader.readTag(0x30)
                _               <- reader.readLength()
                modulus         <- reader.readInteger()
                exponent        <- reader.readInteger()
            yield (modulus, exponent)
        parsed match
            case Result.Success((modulus, exponent)) => EncryptionKey.underCeilings(modulus, exponent)
            case Result.Failure(failure)             => Result.fail(SpkiFailure.Der(failure))
            case panic: Result.Panic                 => panic
        end match
    end encryptionKeyFromSpki

    /** `input ^ e mod n` as `key.sizeInBytes` big-endian bytes: RSAEP for a message and RSAVP1 for a signature, which RFC 8017 defines as
      * the same computation. `Absent` when `input` is not the key's length or its value is not below the modulus, the range both
      * primitives require. No argument is modified.
      */
    def publicOperation(key: PublicKey, input: Array[Byte]): Maybe[Array[Byte]] =
        publicOperation(key.modulus, key.exponent, input)

    /** [[publicOperation]] over a peer's key: RSAEP, the message to encrypt raised to the peer's exponent. */
    def publicOperation(key: EncryptionKey, input: Array[Byte]): Maybe[Array[Byte]] =
        publicOperation(key.modulus, key.exponent, input)

    private def publicOperation(modulus: BigInt, exponent: BigInt, input: Array[Byte]): Maybe[Array[Byte]] =
        val k = (modulus.bitLength + 7) / 8
        if input.length != k then Absent
        else
            val m = BigInt(1, input)
            if m >= modulus then Absent
            else Present(bigEndian(m.modPow(exponent, modulus), k))
        end if
    end publicOperation

    /** Why a key for verification was not accepted. */
    enum KeyFailure derives CanEqual:
        case NotBase64Url(component: Component)
        case LeadingZero(component: Component)
        case ModulusTooSmall(bits: Int, minimumBits: Int)
        case ModulusTooLarge(bits: Int, maximumBits: Int)
        case ModulusEven
        case ExponentTooSmall(exponent: BigInt)
        case ExponentTooLarge(bits: Int, maximumBits: Int)
        case ExponentEven(exponent: BigInt)
    end KeyFailure

    /** Why a PEM or DER SubjectPublicKeyInfo did not yield a key: the text, the structure, or a ceiling. */
    enum SpkiFailure derives CanEqual:
        case PemHeaderMissing
        case PemNotBase64(cause: IllegalArgumentException)
        case Der(failure: DerFailure)
        case ModulusTooLarge(bits: Int, maximumBits: Int)
        case ExponentTooLarge(bits: Int, maximumBits: Int)
    end SpkiFailure

    /** Where the DER reader stopped. Tags are the byte values the encoding uses (`0x30` SEQUENCE, `0x03` BIT STRING, `0x02` INTEGER). */
    enum DerFailure derives CanEqual:
        case EndOfData(expectedTag: Int)
        case UnexpectedTag(expectedTag: Int, actualTag: Int, offset: Int)
        case LengthEndOfData
        case LengthUnsupported(offset: Int)
        case SkipPastEnd(count: Int, offset: Int)
        case IntegerExceedsData(offset: Int)
    end DerFailure

    enum Component derives CanEqual:
        case Modulus, Exponent

    private val PemHeader = "-----BEGIN PUBLIC KEY-----"

    private val PemFooter = "-----END PUBLIC KEY-----"

    private def unsigned(text: String, component: Component): Result[KeyFailure, BigInt] =
        Base64.decodeUrl(text) match
            case Result.Success(bytes) =>
                if bytes.size > 0 && bytes(0) == 0 then Result.fail(KeyFailure.LeadingZero(component))
                else Result.succeed(BigInt(1, bytes.toArray))
            case Result.Failure(_)   => Result.fail(KeyFailure.NotBase64Url(component))
            case panic: Result.Panic => panic

    /** I2OSP: `value` as exactly `length` big-endian bytes; `value` is below a modulus of `length` bytes, so it fits. */
    private def bigEndian(value: BigInt, length: Int): Array[Byte] =
        val raw     = value.toByteArray
        val start   = if raw.length > length then raw.length - length else 0
        val encoded = new Array[Byte](length)
        java.lang.System.arraycopy(raw, start, encoded, length - (raw.length - start), raw.length - start)
        encoded
    end bigEndian

    /** A tag-length-value reader for the one structure [[encryptionKeyFromSpki]] walks. It reads lengths and never checks a declared length
      * against the bytes that follow, takes long-form lengths the short form would fit, and reads an INTEGER's magnitude whatever its high
      * bit: BER as much as DER, which is what a server's key was accepted as before the reader lived here.
      */
    final private class DerReader(der: Array[Byte]):
        private var pos: Int = 0

        def readTag(tag: Int): Result[DerFailure, Unit] =
            if pos >= der.length then Result.fail(DerFailure.EndOfData(tag))
            else
                val actual = der(pos) & 0xff
                if actual != tag then Result.fail(DerFailure.UnexpectedTag(tag, actual, pos))
                else
                    pos += 1
                    Result.unit
                end if
            end if
        end readTag

        def readLength(): Result[DerFailure, Int] =
            if pos >= der.length then Result.fail(DerFailure.LengthEndOfData)
            else
                val first = der(pos) & 0xff
                pos += 1
                if (first & 0x80) == 0 then Result.succeed(first)
                else
                    val count = first & 0x7f
                    if count == 0 || count > 4 || pos + count > der.length then Result.fail(DerFailure.LengthUnsupported(pos - 1))
                    else
                        var length = 0L
                        var i      = 0
                        while i < count do
                            length = (length << 8) | (der(pos) & 0xff)
                            pos += 1
                            i += 1
                        end while
                        // Four bytes with the top bit set exceed Int.MaxValue; read as an Int they would be negative and move the reader
                        // backwards.
                        if length > Int.MaxValue then Result.fail(DerFailure.LengthUnsupported(pos - 1 - count))
                        else Result.succeed(length.toInt)
                    end if
                end if
            end if
        end readLength

        def skip(count: Int): Result[DerFailure, Unit] =
            if pos.toLong + count > der.length then Result.fail(DerFailure.SkipPastEnd(count, pos))
            else
                pos += count
                Result.unit
        end skip

        def readInteger(): Result[DerFailure, BigInt] =
            readTag(0x02).flatMap { _ =>
                readLength().flatMap { length =>
                    if pos.toLong + length > der.length then Result.fail(DerFailure.IntegerExceedsData(pos))
                    else
                        val bytes = java.util.Arrays.copyOfRange(der, pos, pos + length)
                        pos += length
                        Result.succeed(BigInt(1, bytes))
                    end if
                }
            }
        end readInteger

    end DerReader

end Rsa
