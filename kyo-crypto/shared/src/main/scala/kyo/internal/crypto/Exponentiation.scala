package kyo.internal.crypto

import kyo.crypto.Rsa
import kyo.internal.Platform

/** RSA's public operation `base ^ e mod n`, taking the exponent only from a public key type so no secret exponent can reach the
  * non-constant-time arithmetic under it. Every key type caps its exponent at [[Rsa.MaxExponentBits]], far below the size of any private
  * exponent.
  *
  * The arithmetic is chosen per platform by timing the 258 vectors of Wycheproof's 8192-bit RSA file:
  *
  *   - Native: [[Montgomery.modPow]], 7.3 s against 28.8 s for the javalib `BigInteger.modPow`, which reduces by long division.
  *   - Wasm: [[Montgomery.modPow]], 0.66 s against 1.1 s; a `Long` is a native i64 there.
  *   - JVM: `BigInt.modPow`, 478 ms against 653 ms; HotSpot runs `BigInteger`'s Montgomery multiplication on intrinsics.
  *   - JS: `BigInt.modPow`, 3.0 s against 6.7 s; every `Long` is emulated there.
  */
private[kyo] object Exponentiation:

    /** `base ^ e mod n` for the key's `n` and `e`, with `0 <= base < n`. */
    def publicOperation(base: BigInt, key: Rsa.VerificationKey | Rsa.EncryptionKey): BigInt =
        key match
            case key: Rsa.VerificationKey => modPow(base, key.exponent, key.modulus)
            case key: Rsa.EncryptionKey   => modPow(base, key.exponent, key.modulus)

    private def modPow(base: BigInt, exponent: BigInt, modulus: BigInt): BigInt =
        if Platform.isNative || Platform.isWasm then Montgomery.modPow(base, exponent, modulus)
        else base.modPow(exponent, modulus)
end Exponentiation
