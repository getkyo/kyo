package kyo.internal.crypto

import kyo.*

/** RSA public keys for signature verification, built only through checks that bound what a verification may cost.
  *
  * A key usually arrives from a peer (a JSON Web Key set fetched over the network), and `s.modPow(e, n)` costs time quadratic in the
  * modulus length and linear in the exponent's, so both are bounded before any arithmetic runs. The ceilings are the ones kyo-sql-mysql's
  * `RsaOaep` enforces (8192-bit modulus, 64-bit exponent). The floor is RFC 7518 section 3.3's 2048 bits for RS256. An even modulus, an
  * exponent below 3 and an even exponent are rejected because RFC 8017 section 3.1 rules them out for any RSA key. A JWK's `n` and `e` must be
  * unpadded base64url without leading zero octets, as RFC 7518 section 6.3.1 requires.
  *
  * Failure is a [[Rsa.KeyFailure]] value carrying what was measured, never an exception and never a module's own error type: each caller
  * maps it to its own failure leaf.
  */
private[kyo] object Rsa:

    val MinModulusBits: Int = 2048

    val MaxModulusBits: Int = 8192

    val MaxExponentBits: Int = 64

    /** An RSA public key whose modulus and exponent passed every check of [[Rsa]]. */
    final case class PublicKey private (modulus: BigInt, exponent: BigInt) derives CanEqual:
        def sizeInBytes: Int = (modulus.bitLength + 7) / 8

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

    /** The key a JSON Web Key's `n` and `e` members describe (RFC 7518 section 6.3.1). */
    def publicKeyFromJwk(n: String, e: String): Result[KeyFailure, PublicKey] =
        unsigned(n, Component.Modulus).flatMap { modulus =>
            unsigned(e, Component.Exponent).flatMap(exponent => PublicKey(modulus, exponent))
        }

    /** Why a key was not accepted. */
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

    enum Component derives CanEqual:
        case Modulus, Exponent

    private def unsigned(text: String, component: Component): Result[KeyFailure, BigInt] =
        Base64.decodeUrl(text) match
            case Result.Success(bytes) =>
                if bytes.size > 0 && bytes(0) == 0 then Result.fail(KeyFailure.LeadingZero(component))
                else Result.succeed(BigInt(1, bytes.toArray))
            case Result.Failure(_)   => Result.fail(KeyFailure.NotBase64Url(component))
            case panic: Result.Panic => panic

end Rsa
