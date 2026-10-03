package kyo.crypto

import kyo.*

/** RSA public keys, built only through checks that bound what an exponentiation may cost and refuse what RFC 8017 rules out.
  *
  * A key usually arrives from a peer (a JSON Web Key set fetched over the network, a database server's key in an authentication packet),
  * and `m.modPow(e, n)` costs time quadratic in the modulus length and linear in the exponent's, so both are bounded before any
  * arithmetic runs: at most [[MaxModulusBits]] and [[MaxExponentBits]], the ceilings every entry point applies. An even modulus, an
  * exponent below 3 and an even exponent are refused everywhere, since RFC 8017 section 3.1 rules them out for any RSA key.
  *
  * Two kinds of key, each its own type, since they differ in what else was checked:
  *
  *   - [[VerificationKey]], built by [[VerificationKey.apply]] and [[verificationKeyFromJwk]], is a key for signature verification. It
  *     meets RFC 7518 section 3.3's floor of [[MinModulusBits]] for RS256. A JWK's `n` and `e` must be unpadded base64url without leading
  *     zero octets, as RFC 7518 section 6.3.1 requires. [[RsaPkcs1.verifySha256]] takes only this type.
  *   - [[EncryptionKey]], built by [[encryptionKeyFromPem]] and [[encryptionKeyFromSpki]], is a key a peer offers to encrypt to (a
  *     SubjectPublicKeyInfo, as a MySQL server sends its `sha256_password` key). Its floor is [[MinEncryptionModulusBits]]: what is
  *     encrypted to the key is a password, so a modulus an observer can factor or an exponent below 3 (for `e = 1` the encryption is the
  *     identity) would hand it to whoever reads the wire. The floor is lower than the verifier's because a 1024-bit server key is a weak
  *     configuration where a 1024-bit RS256 key is outside RFC 7518. The reader takes BER as well as DER and does not compare the
  *     algorithm identifier. An `EncryptionKey` is not a [[VerificationKey]] because it did not meet the verifier's floor, so no signature
  *     check accepts the type. [[RsaOaep.encryptSha1]] takes only this type.
  *
  * Neither type has a public constructor or a `Schema`: a key is not data to deserialize, and a value of either type is one that passed
  * its checks. Failure is a [[Rsa.KeyFailure]] or [[Rsa.SpkiFailure]] value carrying what was measured, never an exception: each caller
  * maps it to its own failure.
  *
  * @see
  *   [[RsaPkcs1.verifySha256]], the operation over a [[VerificationKey]]
  * @see
  *   [[RsaOaep.encryptSha1]], the operation over an [[EncryptionKey]]
  * @see
  *   [[Rsa.KeyFailure]], [[Rsa.SpkiFailure]] and [[Rsa.BoundsFailure]], what the factories refuse with
  * @see
  *   [[Base64.decodeUrl]], the decoder a JWK's numbers go through
  */
object Rsa:

    /** The floor for a [[VerificationKey]], RFC 7518 section 3.3's minimum for RS256. */
    val MinModulusBits: Int = 2048

    /** The floor for an [[EncryptionKey]]. */
    val MinEncryptionModulusBits: Int = 1024

    /** The ceiling on any modulus, which bounds the quadratic factor of the public operation's cost. */
    val MaxModulusBits: Int = 8192

    /** The ceiling on any public exponent, which bounds the linear factor of the public operation's cost. */
    val MaxExponentBits: Int = 64

    /** An RSA public key that passed every verification check of [[Rsa]]: the two ceilings, the [[MinModulusBits]] floor and the parity
      * rules. It is the only key [[RsaPkcs1.verifySha256]] takes, so a signature check can never run over a key that met a lower floor.
      * Equal to another key exactly when the modulus and exponent are equal; its `toString` prints both numbers, which are public.
      *
      * Obtained only through [[VerificationKey.apply]], over numbers, or [[verificationKeyFromJwk]], over a JWK's `n` and `e`. A plain
      * class rather than a case class: a case class companion gets a public synthesized `fromProduct` that calls the private constructor,
      * and the class a `copy`, either of which would build a key past every check.
      *
      * @see
      *   [[VerificationKey.apply]] and [[verificationKeyFromJwk]], the two factories
      * @see
      *   [[KeyFailure]], what the factories refuse with
      * @see
      *   [[RsaPkcs1.verifySha256]], the operation over this key
      * @see
      *   [[EncryptionKey]], the key of the other floor, which no signature check takes
      */
    final class VerificationKey private (
        /** The modulus `n`, odd and between [[MinModulusBits]] and [[MaxModulusBits]] bits. */
        val modulus: BigInt,
        /** The public exponent `e`, odd, at least 3 and at most [[MaxExponentBits]] bits. */
        val exponent: BigInt
    ) derives CanEqual:

        /** The modulus length in bytes, the length of every signature the key verifies. */
        def sizeInBytes: Int = Rsa.sizeInBytes(modulus)

        override def equals(other: Any): Boolean = other match
            case that: VerificationKey => modulus == that.modulus && exponent == that.exponent
            case _                     => false

        override def hashCode: Int = 31 * modulus.hashCode + exponent.hashCode

        override def toString: String = s"VerificationKey($modulus, $exponent)"
    end VerificationKey

    object VerificationKey:

        /** The key with `modulus` and `exponent`, or the first check they fail. */
        def apply(modulus: BigInt, exponent: BigInt): Result[KeyFailure, VerificationKey] =
            checkBounds(modulus, exponent, MinModulusBits) match
                case Absent           => Result.succeed(new VerificationKey(modulus, exponent))
                case Present(failure) => Result.fail(KeyFailure.Bounds(failure))
    end VerificationKey

    /** A peer's RSA public key: under the two ceilings, at least [[MinEncryptionModulusBits]], odd modulus, odd exponent of at least 3.
      * Its only use is encrypting to the peer, and it is the only key [[RsaOaep.encryptSha1]] takes. It is not a [[VerificationKey]]
      * because it did not meet the verifier's floor: a 1024-bit server key is a weak configuration a client still has to talk to, while a
      * 1024-bit RS256 key is outside RFC 7518. Equal to another key exactly when the modulus and exponent are equal; its `toString` prints
      * both numbers, which are public.
      *
      * Obtained only through [[encryptionKeyFromPem]] and [[encryptionKeyFromSpki]], which read a SubjectPublicKeyInfo and apply the
      * checks. A plain class for the reason [[VerificationKey]] is one.
      *
      * @see
      *   [[encryptionKeyFromPem]] and [[encryptionKeyFromSpki]], the two factories
      * @see
      *   [[SpkiFailure]], what the factories refuse with
      * @see
      *   [[RsaOaep.encryptSha1]], the operation over this key
      * @see
      *   [[VerificationKey]], the key of the higher floor
      */
    final class EncryptionKey private (
        /** The modulus `n`, odd and between [[MinEncryptionModulusBits]] and [[MaxModulusBits]] bits. */
        val modulus: BigInt,
        /** The public exponent `e`, odd, at least 3 and at most [[MaxExponentBits]] bits. */
        val exponent: BigInt
    ) derives CanEqual:

        /** The modulus length in bytes, the length of every ciphertext under the key. */
        def sizeInBytes: Int = Rsa.sizeInBytes(modulus)

        override def equals(other: Any): Boolean = other match
            case that: EncryptionKey => modulus == that.modulus && exponent == that.exponent
            case _                   => false

        override def hashCode: Int = 31 * modulus.hashCode + exponent.hashCode

        override def toString: String = s"EncryptionKey($modulus, $exponent)"
    end EncryptionKey

    object EncryptionKey:
        private[Rsa] def checked(modulus: BigInt, exponent: BigInt): Result[SpkiFailure, EncryptionKey] =
            checkBounds(modulus, exponent, MinEncryptionModulusBits) match
                case Absent           => Result.succeed(new EncryptionKey(modulus, exponent))
                case Present(failure) => Result.fail(SpkiFailure.Bounds(failure))
    end EncryptionKey

    /** The first of the checks every key must pass that `modulus` and `exponent` fail, with `floor` the modulus bits the key type
      * requires: the floor, the two ceilings and the parities, in the order a peer's key is most likely to fail them.
      */
    private def checkBounds(modulus: BigInt, exponent: BigInt, floor: Int): Maybe[BoundsFailure] =
        val modulusBits  = modulus.bitLength
        val exponentBits = exponent.bitLength
        if modulus.signum <= 0 || modulusBits < floor then Present(BoundsFailure.ModulusTooSmall(modulusBits, floor))
        else if modulusBits > MaxModulusBits then Present(BoundsFailure.ModulusTooLarge(modulusBits, MaxModulusBits))
        else if !modulus.testBit(0) then Present(BoundsFailure.ModulusEven)
        else if exponent < 3 then Present(BoundsFailure.ExponentTooSmall(exponent))
        else if exponentBits > MaxExponentBits then Present(BoundsFailure.ExponentTooLarge(exponentBits, MaxExponentBits))
        else if !exponent.testBit(0) then Present(BoundsFailure.ExponentEven(exponent))
        else Absent
        end if
    end checkBounds

    /** The length in bytes of a modulus, `k` in RFC 8017: the length of every signature and ciphertext under the key. */
    private def sizeInBytes(modulus: BigInt): Int = (modulus.bitLength + 7) / 8

    /** The key a JSON Web Key's `n` and `e` members describe (RFC 7518 section 6.3.1): unpadded base64url, no leading zero octet, `n`
      * checked before `e`.
      */
    def verificationKeyFromJwk(n: String, e: String): Result[KeyFailure, VerificationKey] =
        unsigned(n, Component.Modulus).flatMap { modulus =>
            unsigned(e, Component.Exponent).flatMap(exponent => VerificationKey(modulus, exponent))
        }

    /** The key a PEM `PUBLIC KEY` block holds: a base64 SubjectPublicKeyInfo between the two marker lines, whitespace anywhere. A body
      * whose final unit lacks its `=` padding is padded before decoding. Checked as [[EncryptionKey]] states.
      */
    def encryptionKeyFromPem(pem: String): Result[SpkiFailure, EncryptionKey] =
        if !pem.contains(PemHeader) then Result.fail(SpkiFailure.PemHeaderMissing)
        else
            val body   = withoutWhitespace(pem.replace(PemHeader, "").replace(PemFooter, ""))
            val padded =
                body.length % 4 match
                    case 2 => body + "=="
                    case 3 => body + "="
                    case _ => body
            Base64.decode(padded) match
                case Result.Success(der) => encryptionKeyFromSpki(der)
                case Result.Failure(e)   => Result.fail(SpkiFailure.PemNotBase64(e))
                case panic: Result.Panic => panic
            end match
        end if
    end encryptionKeyFromPem

    /** The key a DER SubjectPublicKeyInfo holds (RFC 5280 section 4.1, with the RSAPublicKey of RFC 8017 appendix A.1.1 in its BIT
      * STRING). Checked as [[EncryptionKey]] states.
      */
    def encryptionKeyFromSpki(der: Span[Byte]): Result[SpkiFailure, EncryptionKey] =
        // Unsafe: the array is only read.
        encryptionKeyFromSpkiArray(der.toArrayUnsafe)

    private[kyo] def encryptionKeyFromSpkiArray(der: Array[Byte]): Result[SpkiFailure, EncryptionKey] =
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
            case Result.Success((modulus, exponent)) => EncryptionKey.checked(modulus, exponent)
            case Result.Failure(failure)             => Result.fail(SpkiFailure.Der(failure))
            case panic: Result.Panic                 => panic
        end match
    end encryptionKeyFromSpkiArray

    /** `input ^ e mod n` as `key.sizeInBytes` big-endian bytes: RSAVP1 of RFC 8017 section 5.2.2. `Absent` when `input` is not the key's
      * length or its value is not below the modulus. No argument is modified.
      */
    private[kyo] def publicOperation(key: VerificationKey, input: Array[Byte]): Maybe[Array[Byte]] =
        publicOperation(key.modulus, key.exponent, input)

    /** RSAEP of RFC 8017 section 5.1.1 over a peer's key, the same computation as the verification form. */
    private[kyo] def publicOperation(key: EncryptionKey, input: Array[Byte]): Maybe[Array[Byte]] =
        publicOperation(key.modulus, key.exponent, input)

    private def publicOperation(modulus: BigInt, exponent: BigInt, input: Array[Byte]): Maybe[Array[Byte]] =
        val k = sizeInBytes(modulus)
        if input.length != k then Absent
        else
            val m = BigInt(1, input)
            if m >= modulus then Absent
            else Present(bigEndian(m.modPow(exponent, modulus), k))
        end if
    end publicOperation

    /** A check on the numbers of a public key that failed, whichever way the key arrived: the floor the key type requires, the two
      * ceilings that bound the public operation's cost, and the parities RFC 8017 section 3.1 rules out. Each case carries what was
      * measured, in bits or as the value, and the bound it was measured against; none carries the modulus.
      *
      * The order of checks is the floor, the modulus ceiling, the modulus parity, the exponent floor, the exponent ceiling and the
      * exponent parity, and a key that fails several reports the first.
      *
      * @see
      *   [[KeyFailure.Bounds]] and [[SpkiFailure.Bounds]], the cases that carry it
      * @see
      *   [[MinModulusBits]], [[MinEncryptionModulusBits]], [[MaxModulusBits]] and [[MaxExponentBits]], the bounds it names
      * @see
      *   [[VerificationKey.apply]] and [[encryptionKeyFromSpki]], which apply the checks
      */
    enum BoundsFailure derives CanEqual:

        /** The modulus has `bits` bits, or is not positive, where the key type requires `minimumBits`. */
        case ModulusTooSmall(bits: Int, minimumBits: Int)

        /** The modulus has `bits` bits, above the ceiling of `maximumBits`. */
        case ModulusTooLarge(bits: Int, maximumBits: Int)

        /** The modulus is even, which no product of two odd primes is. */
        case ModulusEven

        /** The public exponent is `exponent`, below 3: 1 makes the public operation the identity and 2 is even. */
        case ExponentTooSmall(exponent: BigInt)

        /** The public exponent has `bits` bits, above the ceiling of `maximumBits`. */
        case ExponentTooLarge(bits: Int, maximumBits: Int)

        /** The public exponent is `exponent`, even, so not invertible modulo the totient of any RSA modulus. */
        case ExponentEven(exponent: BigInt)

    end BoundsFailure

    /** Why a JWK or a pair of numbers did not yield a [[VerificationKey]]: the base64url of a component, a leading zero octet in one, or
      * a bound or parity of the numbers. The encoding cases name the [[Component]] they were found in; the numeric case carries the
      * [[BoundsFailure]]. `n` is checked before `e`, and a failure carries no part of the key.
      *
      * @see
      *   [[verificationKeyFromJwk]] and [[VerificationKey.apply]], which produce it
      * @see
      *   [[BoundsFailure]], the numeric checks
      * @see
      *   [[SpkiFailure]], the counterpart for an [[EncryptionKey]]
      */
    enum KeyFailure derives CanEqual:

        /** The component is not unpadded base64url, as RFC 7518 section 6.3.1 requires. */
        case NotBase64Url(component: Component)

        /** The component decodes to bytes with a leading zero octet, a second spelling of the same number that RFC 7518 forbids. */
        case LeadingZero(component: Component)

        /** The numbers decoded but fail a bound or a parity. */
        case Bounds(failure: BoundsFailure)

    end KeyFailure

    /** Why a PEM or DER SubjectPublicKeyInfo did not yield an [[EncryptionKey]]: the PEM markers, the base64 of the body, the DER
      * structure, or a bound or parity of the numbers it holds. The text and structure cases say where the reader stopped, through the
      * [[Base64.Failure]] or the [[DerFailure]] they carry; the numeric case carries the [[BoundsFailure]]. A failure carries no part of
      * the key.
      *
      * @see
      *   [[encryptionKeyFromPem]] and [[encryptionKeyFromSpki]], which produce it
      * @see
      *   [[DerFailure]] and [[BoundsFailure]], the failures it wraps
      * @see
      *   [[KeyFailure]], the counterpart for a [[VerificationKey]]
      */
    enum SpkiFailure derives CanEqual:

        /** The text has no `-----BEGIN PUBLIC KEY-----` line. */
        case PemHeaderMissing

        /** The body between the markers is not base64, with the decoder's own failure. */
        case PemNotBase64(failure: Base64.Failure)

        /** The DER does not hold a SubjectPublicKeyInfo with an RSAPublicKey, with where the reader stopped. */
        case Der(failure: DerFailure)

        /** The numbers were read but fail a bound or a parity. */
        case Bounds(failure: BoundsFailure)

    end SpkiFailure

    /** Where the reader of a SubjectPublicKeyInfo stopped: the structure it walks is SEQUENCE, SEQUENCE (the algorithm identifier, skipped),
      * BIT STRING, SEQUENCE, INTEGER, INTEGER, and each case names the step that did not find what it needs and the offset it was at.
      * Tags are the byte values the encoding uses (`0x30` SEQUENCE, `0x03` BIT STRING, `0x02` INTEGER). Offsets count from the first byte
      * of the DER.
      *
      * The reader takes BER as well as DER and never compares a declared length with the bytes that follow, so only a structure that runs
      * out of data or shows the wrong tag stops it; a failure carries no part of the key.
      *
      * @see
      *   [[SpkiFailure.Der]], the case that carries it
      * @see
      *   [[encryptionKeyFromSpki]] and [[encryptionKeyFromPem]], which produce it
      * @see
      *   [[EncryptionKey]], what a structure that reads through yields
      */
    enum DerFailure derives CanEqual:

        /** The data ended where a value with `expectedTag` should begin. */
        case EndOfData(expectedTag: Int)

        /** The byte at `offset` is `actualTag` where `expectedTag` should be. */
        case UnexpectedTag(expectedTag: Int, actualTag: Int, offset: Int)

        /** The data ended where a length should begin. */
        case LengthEndOfData

        /** The length at `offset` is indefinite, uses more than 4 octets, or exceeds `Int.MaxValue`. */
        case LengthUnsupported(offset: Int)

        /** Skipping `count` bytes from `offset` would pass the end of the data. */
        case SkipPastEnd(count: Int, offset: Int)

        /** The INTEGER at `offset` declares more bytes than remain. */
        case IntegerExceedsData(offset: Int)

    end DerFailure

    /** The two numbers of a public key, as a [[KeyFailure]] names the one it refused: the modulus `n` or the public exponent `e`, in
      * RFC 8017's names. A JWK carries them as its `n` and `e` members and a SubjectPublicKeyInfo as two INTEGERs, in that order, and the
      * modulus is checked before the exponent everywhere.
      *
      * @see
      *   [[KeyFailure.NotBase64Url]] and [[KeyFailure.LeadingZero]], the cases that carry it
      * @see
      *   [[verificationKeyFromJwk]], which reads the two components
      * @see
      *   [[BoundsFailure]], the numeric checks on the two numbers
      */
    enum Component derives CanEqual:

        /** The modulus `n`, a JWK's `n` and the first INTEGER of a SubjectPublicKeyInfo. */
        case Modulus

        /** The public exponent `e`, a JWK's `e` and the second INTEGER. */
        case Exponent
    end Component

    /** `text` without SP, HTAB, CR, LF, VT and FF: the whitespace RFC 7468 allows around a PEM body and the two more the JVM's `\s`
      * matches. An explicit set, because `\s` names a different set in each platform's regex engine and a PEM must be accepted by all four
      * alike; no other character is dropped, so a no-break space reaches the decoder and is refused as any other character is.
      */
    private def withoutWhitespace(text: String): String =
        val out = new java.lang.StringBuilder(text.length)
        var i   = 0
        while i < text.length do
            val c = text.charAt(i)
            if c != ' ' && c != '\t' && c != '\r' && c != '\n' && c != '\u000b' && c != '\f' then discard(out.append(c))
            i += 1
        end while
        out.toString
    end withoutWhitespace

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
      * bit: BER as much as DER, since a database server's key arrives in whichever encoding its tool wrote.
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
