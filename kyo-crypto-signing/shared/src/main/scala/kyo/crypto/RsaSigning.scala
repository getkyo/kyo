package kyo.crypto

import kyo.*
import kyo.internal.crypto.DerReader
import kyo.internal.signing.PrivateOperation

/** RSASSA-PKCS1-v1_5 signing with SHA-256 (RFC 8017 section 8.2.1), the RS256 of JSON Web Signatures, under an RSA private key read
  * from a PEM.
  *
  * The private exponentiation uses fixed-width arithmetic with no secret-dependent branches or array indices in the source. Each
  * exponent window selects its power by reading every table entry. This is not a guarantee about code emitted by a JIT, JavaScript
  * engine or LLVM. kyo-crypto's public-key exponentiation never sees a private exponent. The input is blinded before the
  * exponentiation, with values the caller draws, so the exponentiations by the private exponents run on a value independent of the message.
  * Every signature is checked under the key's public part before it is returned: a fault in the computation of a CRT signature would give
  * a prime of the modulus to whoever reads it, so a signature that fails the check is a [[SignFailure.Fault]], never a result.
  *
  * Key loading checks the encoding, public-key bounds, factor widths and the algebraic relationships between the private components.
  * It does not certify primality or the quality of key generation. Load keys from a trusted generator; key loading uses variable-time
  * BigInt arithmetic. A [[SigningKey]] records these consistency checks, not a security certification.
  *
  * The blinding comes from the caller, as an OAEP seed comes from [[RsaOaep.encryptSha1]]'s caller, since the module has no effect
  * dependency: [[SigningKey.blindingSizeInBytes]] bytes from a cryptographic source, such as `kyo.SecureRandom`. The signature does not
  * depend on it: PKCS#1 v1.5 signatures are deterministic.
  *
  * @see
  *   [[RsaPkcs1.verifySha256]], the verification of what this signs
  * @see
  *   [[KeyFailure]] and [[SignFailure]], what the reader and the signing refuse with
  */
object RsaSigning:

    /** An RSA private key that passed every check of [[signingKeyFromPem]], with its public part as a [[Rsa.VerificationKey]].
      *
      * No public constructor and no `Schema`: a value is a key read from its encoding and checked. Equal to another key exactly when their
      * public parts are equal; its `toString` names its size and nothing else.
      */
    final class SigningKey private (
        /** The public part, the key a signature of this key verifies under. */
        val verificationKey: Rsa.VerificationKey,
        private[kyo] val privateKey: PrivateOperation.Key
    ) derives CanEqual:

        /** The modulus length in bytes, the length of every signature the key makes. */
        def sizeInBytes: Int = verificationKey.sizeInBytes

        /** The length of the blinding [[signSha256]] takes: two values, each drawn [[BlindingMarginBytes]] bytes wider than the modulus, so
          * that its reduction modulo the modulus is within `2^-64` of uniform.
          */
        def blindingSizeInBytes: Int = 2 * (sizeInBytes + BlindingMarginBytes)

        override def equals(other: Any): Boolean = other match
            case that: SigningKey => verificationKey == that.verificationKey
            case _                => false

        override def hashCode: Int = verificationKey.hashCode

        override def toString: String = s"RsaSigning.SigningKey(${verificationKey.modulus.bitLength} bits)"
    end SigningKey

    object SigningKey:
        private[RsaSigning] def init(verificationKey: Rsa.VerificationKey, privateKey: PrivateOperation.Key): SigningKey =
            new SigningKey(verificationKey, privateKey)
    end SigningKey

    /** The key a PEM block holds: `RSA PRIVATE KEY`, a PKCS#1 RSAPrivateKey (RFC 8017 appendix A.1.2), or `PRIVATE KEY`, a PKCS#8
      * PrivateKeyInfo (RFC 5208, or RFC 5958's version 1) whose algorithm is rsaEncryption around one. The body is base64 between the two
      * marker lines, whitespace anywhere, a final unit lacking its `=` padding padded. The key uses definite-length encoding; redundant
      * length octets are accepted, but INTEGERs must be nonnegative and minimally encoded. Optional PKCS#8 attributes are ignored;
      * an optional public key must match. All declared key contents must be consumed.
      */
    def signingKeyFromPem(pem: String): Result[KeyFailure, SigningKey] =
        if pem.contains(Pkcs1Header) then pemBody(pem, Pkcs1Header, Pkcs1Footer).flatMap(signingKeyFromPkcs1Array)
        else if pem.contains(Pkcs8Header) then pemBody(pem, Pkcs8Header, Pkcs8Footer).flatMap(signingKeyFromPkcs8Array)
        else Result.fail(KeyFailure.PemHeaderMissing)

    /** The key a DER RSAPrivateKey holds: version 0, two primes. */
    private[kyo] def signingKeyFromPkcs1Array(der: Array[Byte]): Result[KeyFailure, SigningKey] =
        for
            reader          <- sequence(der)
            version         <- integer(reader)
            _               <- if version == BigInt(0) then Result.unit else Result.fail(KeyFailure.VersionUnsupported(version))
            modulus         <- integer(reader)
            publicExponent  <- integer(reader)
            privateExponent <- integer(reader)
            p               <- integer(reader)
            q               <- integer(reader)
            dP              <- integer(reader)
            dQ              <- integer(reader)
            qInv            <- integer(reader)
            _               <- end(reader)
            verificationKey <- Rsa.VerificationKey.checked(modulus, publicExponent).mapFailure(KeyFailure.Bounds(_))
            key             <- checked(verificationKey, privateExponent, p, q, dP, dQ, qInv)
        yield key
        end for
    end signingKeyFromPkcs1Array

    /** The key a DER PrivateKeyInfo holds: version 0 or 1, rsaEncryption with its NULL parameters, and an RSAPrivateKey. */
    private[kyo] def signingKeyFromPkcs8Array(der: Array[Byte]): Result[KeyFailure, SigningKey] =
        for
            reader    <- sequence(der)
            version   <- integer(reader)
            _         <- if version <= BigInt(1) then Result.unit else Result.fail(KeyFailure.VersionUnsupported(version))
            algorithm <- contents(reader, 0x30)
            _         <- if java.util.Arrays.equals(algorithm, RsaEncryption) then Result.unit else Result.fail(KeyFailure.AlgorithmNotRsa)
            encoded   <- contents(reader, 0x04)
            // Attributes are metadata, not part of the RSA key. Consume their declared envelope before the optional public key.
            _         <- optional(reader, 0xa0)
            publicKey <- optional(reader, 0x81)
            _         <- end(reader)
            key       <- signingKeyFromPkcs1Array(encoded)
            _         <- publicKey match
                case Present(bits) =>
                    if version == BigInt(0) || bits.isEmpty || bits(0) != 0 then Result.fail(KeyFailure.PublicKeyMismatch)
                    else
                        for
                            publicReader <- sequence(bits.drop(1))
                            modulus      <- integer(publicReader)
                            exponent     <- integer(publicReader)
                            _            <- end(publicReader)
                            _ <- if modulus == key.verificationKey.modulus && exponent == key.verificationKey.exponent then Result.unit
                            else Result.fail(KeyFailure.PublicKeyMismatch)
                        yield ()
                case Absent => Result.unit
        yield key
    end signingKeyFromPkcs8Array

    /** The RS256 signature of `message` under `key`, `key.sizeInBytes` bytes. `blinding` is `key.blindingSizeInBytes` bytes from a
      * cryptographic source; the signature does not depend on it. The signature is checked under the key's public part before it is
      * returned. No argument is modified.
      */
    def signSha256(key: SigningKey, message: Span[Byte], blinding: Span[Byte]): Result[SignFailure, Span[Byte]] =
        // Unsafe: the message and blinding arrays are only read, and the signature array is fresh and held by nothing else.
        signSha256Array(key, message.toArrayUnsafe, blinding.toArrayUnsafe).map(Span.fromUnsafe(_))

    private[kyo] def signSha256Array(key: SigningKey, message: Array[Byte], blinding: Array[Byte]): Result[SignFailure, Array[Byte]] =
        if blinding.length != key.blindingSizeInBytes then Result.fail(SignFailure.BlindingLength(key.blindingSizeInBytes))
        else
            val encoded = RsaPkcs1.encode(Sha256.hashArray(message), key.sizeInBytes)
            PrivateOperation(key.privateKey, encoded, blinding) match
                case Present(signature) => verified(key, encoded, signature)
                case Absent             => Result.fail(SignFailure.BlindingNotInvertible)
        end if
    end signSha256Array

    /** `signature`, when raised to the public exponent it gives back `encoded`: the check every signature passes before it leaves. */
    private[kyo] def verified(key: SigningKey, encoded: Array[Byte], signature: Array[Byte]): Result[SignFailure, Array[Byte]] =
        Rsa.publicOperation(key.verificationKey, signature) match
            case Present(recovered) if java.util.Arrays.equals(recovered, encoded) => Result.succeed(signature)
            case _                                                                 => Result.fail(SignFailure.Fault)

    /** Why a PEM or a DER did not yield a [[SigningKey]]. No case carries a part of the key beyond the
      * public exponent a bounds failure may name.
      */
    enum KeyFailure derives CanEqual:

        /** Neither `-----BEGIN RSA PRIVATE KEY-----` nor `-----BEGIN PRIVATE KEY-----`. */
        case PemHeaderMissing

        /** The body between the marker lines is not base64. */
        case PemNotBase64(failure: Base64.Failure)

        /** The DER ends, or holds another tag, where the structure needs a part. */
        case Der(failure: Rsa.DerFailure)

        /** An INTEGER is empty, negative or has redundant leading zero bytes. */
        case IntegerEncoding

        /** Bytes remain after the key structure's declared contents. */
        case TrailingData

        /** The optional PKCS#8 public key disagrees with the private key, or has an invalid BIT STRING encoding. */
        case PublicKeyMismatch

        /** A PKCS#1 version other than 0, two primes, or a PKCS#8 version other than 0 or 1. */
        case VersionUnsupported(version: BigInt)

        /** The PKCS#8 algorithm identifier is not rsaEncryption with NULL parameters. */
        case AlgorithmNotRsa

        /** The modulus and public exponent fail a check of [[Rsa.VerificationKey]]. */
        case Bounds(failure: Rsa.BoundsFailure)

        /** A prime of `bits` bits, where both must have `expectedBits`, the modulus's bits halved and rounded up, so every width the
          * arithmetic runs at is public.
          */
        case PrimeSize(bits: Int, expectedBits: Int)

        /** The modulus is not the product of the two primes. */
        case NotProduct

        /** The private exponent is not positive and below the modulus, or is not the public exponent's inverse modulo `lcm(p - 1, q - 1)`. */
        case PrivateExponent

        /** A CRT exponent is not the private exponent reduced modulo `p - 1` or `q - 1`. */
        case CrtExponent

        /** The CRT coefficient is not the inverse of q modulo p. */
        case CrtCoefficient

    end KeyFailure

    /** Why [[signSha256]] returned no signature. No case carries a byte of the blinding or of the result. */
    enum SignFailure derives CanEqual:

        /** The blinding is not `expected` bytes long. */
        case BlindingLength(expected: Int)

        /** The blinding's values share a factor with the modulus: draw fresh blinding bytes and retry. */
        case BlindingNotInvertible

        /** The signature failed the check under the public part: a fault in the computation, so it is not released, since a faulty CRT
          * signature gives a prime of the modulus to whoever reads it.
          */
        case Fault

    end SignFailure

    /** The bytes each blinding value is drawn wider than the modulus. */
    private val BlindingMarginBytes = 8

    private val Pkcs1Header = "-----BEGIN RSA PRIVATE KEY-----"

    private val Pkcs1Footer = "-----END RSA PRIVATE KEY-----"

    private val Pkcs8Header = "-----BEGIN PRIVATE KEY-----"

    private val Pkcs8Footer = "-----END PRIVATE KEY-----"

    /** The contents of rsaEncryption's AlgorithmIdentifier: the OID 1.2.840.113549.1.1.1 and the NULL parameters RFC 8017 appendix A.1
      * requires.
      */
    private val RsaEncryption: Array[Byte] =
        Array(0x06, 0x09, 0x2a, 0x86, 0x48, 0x86, 0xf7, 0x0d, 0x01, 0x01, 0x01, 0x05, 0x00).map(_.toByte)

    private def pemBody(pem: String, header: String, footer: String): Result[KeyFailure, Array[Byte]] =
        // Unsafe: the decoded array is fresh and only read.
        Rsa.pemBody(pem, header, footer).map(_.toArrayUnsafe).mapFailure(KeyFailure.PemNotBase64(_))

    private def read[A](result: Result[Rsa.DerFailure, A]): Result[KeyFailure, A] =
        result.mapFailure(KeyFailure.Der(_))

    /** A bounded sequence, with no bytes following its declared extent. Public-key readers retain their existing permissive behavior. */
    private def sequence(der: Array[Byte]): Result[KeyFailure, DerReader] =
        val reader = new DerReader(der)
        contents(reader, 0x30).flatMap(body => end(reader).map(_ => new DerReader(body)))

    private def contents(reader: DerReader, tag: Int): Result[KeyFailure, Array[Byte]] =
        read(reader.readTag(tag)).flatMap(_ => read(reader.readLength())).flatMap(length => read(reader.readBytes(length)))

    private def end(reader: DerReader): Result[KeyFailure, Unit] =
        if reader.remaining == 0 then Result.unit else Result.fail(KeyFailure.TrailingData)

    private def integer(reader: DerReader): Result[KeyFailure, BigInt] =
        contents(reader, 0x02).flatMap { bytes =>
            if bytes.isEmpty || bytes(0) < 0 || (bytes.length > 1 && bytes(0) == 0 && bytes(1) >= 0) then
                Result.fail(KeyFailure.IntegerEncoding)
            else Result.succeed(BigInt(1, bytes))
        }

    private def optional(reader: DerReader, tag: Int): Result[KeyFailure, Maybe[Array[Byte]]] =
        if reader.hasTag(tag) then contents(reader, tag).map(Present(_)) else Result.succeed(Absent)

    /** The checks of the private part, over numbers whose public part passed: the widths of the primes, then the identities that tie each
      * number to the others. `BigInt` arithmetic, which is not constant time, runs here once per key, when it is read.
      */
    private def checked(
        key: Rsa.VerificationKey,
        privateExponent: BigInt,
        p: BigInt,
        q: BigInt,
        dP: BigInt,
        dQ: BigInt,
        qInv: BigInt
    ): Result[KeyFailure, SigningKey] =
        val expectedBits = (key.modulus.bitLength + 1) / 2
        // lazy so it is computed only once both primes have their width: below it, p - 1 and q - 1 may both be 0, and their gcd too
        lazy val lambda = (p - 1) * (q - 1) / (p - 1).gcd(q - 1)
        if p.bitLength != expectedBits then Result.fail(KeyFailure.PrimeSize(p.bitLength, expectedBits))
        else if q.bitLength != expectedBits then Result.fail(KeyFailure.PrimeSize(q.bitLength, expectedBits))
        else if p * q != key.modulus then Result.fail(KeyFailure.NotProduct)
        else if privateExponent <= 0 || privateExponent >= key.modulus || (privateExponent * key.exponent).mod(lambda) != BigInt(1) then
            Result.fail(KeyFailure.PrivateExponent)
        else if dP != privateExponent.mod(p - 1) || dQ != privateExponent.mod(q - 1) then Result.fail(KeyFailure.CrtExponent)
        else if qInv >= p || (q * qInv).mod(p) != BigInt(1) then Result.fail(KeyFailure.CrtCoefficient)
        else Result.succeed(SigningKey.init(key, PrivateOperation.Key.init(key.modulus, key.exponent, p, q, dP, dQ, qInv)))
        end if
    end checked

end RsaSigning
