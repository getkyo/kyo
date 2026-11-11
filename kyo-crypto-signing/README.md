# kyo-crypto-signing

`kyo-crypto-signing` signs with an RSA private key: RSASSA-PKCS1-v1_5 with SHA-256, the RS256 of JSON Web Signatures, which a service uses to sign the token it presents to an API, such as a GitHub App's JWT. It reads the key from its PEM, PKCS#1 or PKCS#8, and every operation produces the same bytes on the JVM, Scala.js, Scala Native and Wasm, without `java.security`.

Private exponentiation uses fixed-width arithmetic without secret-dependent branches or array indices in the source. This does not guarantee constant-time execution by a JIT, JavaScript engine or LLVM. The message is blinded before exponentiation, and every signature is checked under the public key before it is returned, because a faulty CRT signature can reveal a prime factor of the modulus.

```scala
import kyo.*
import kyo.crypto.*

// Sign a JWT's `header.payload`. The caller supplies fresh blinding bytes drawn through its effectful randomness API.
def rs256(key: RsaSigning.SigningKey, input: String, blinding: Span[Byte]): Result[RsaSigning.SignFailure, Span[Byte]] =
    RsaSigning.signSha256(key, Span.from(input.getBytes("US-ASCII")), blinding)
```

A service reads its key once and signs with it for as long as it runs. The rest of this document reads the key, signs with it, and says what the module leaves to others.

<!-- doctest:setup
```scala
import kyo.*
import kyo.crypto.*

// RFC 7515 appendix A.2: the key, as a PKCS#1 PEM, and the JWS it signs.
val pem: String =
    """-----BEGIN RSA PRIVATE KEY-----
MIIEowIBAAKCAQEAofgWCuLjybRlzo0tZWJjNiuSfb4p4fAkd/wWJcyQoTbji9k0
l8W26mPddxHmfHQp+Vaw+4qPCJrcS2mJPMEzP1Pt0Bm4d4QlL+yRT+SFd2lZS+pC
gNMsD1W/YpRPEwOWvG6b32690r2jZ47soMZo9wGzjb/7OMg0LOL+bSf63kpaSHSX
ndS5z5rexMdbBYUsLA9e+KXBdQOS+UTo7WTBEMa2R2CapHg665xsmtdVMTBQY4uD
Zlxvb3qCo5ZwKh9kG4LT6/I5IhlJH7aGhyxXFvUK+DWNmoudF8NAco9/h9iaGNj8
q2ethFkMLs91kzk2PAcDTW9gb54h4FRWyuXpoQIDAQABAoIBABKucaRpzQorw35S
bEUAVx8dYXUdZOlJcHtiWQ+dC6V8ljxAHj/PLyzTveyI5QO/xkObCyjIL303l2cf
UhPu2MFaJdjVzqACXuOrLot/eSFvxjvqVidTtAZExqFRJ9mylUVAoLvhowVWmC1O
n95fZCXxTUtxNEG1Xcc7m0rtzJKs45J+N/V9DP1edYH6USyPSWGp6wuA+KgHRnKK
Vf9GRx80JQY7nVNkL17eHoTWEwga+lwi0FEoW9Y7lDtWXYmKBWhUE+U8PGxlJf8f
40493HDw1WRQ/aSLoS4QTp3rn7gYgeHEvfJdkkf0UMhlknlo53M09EFPdadQ4TlU
bjqKc50CgYEA4BzEEOtIpmVdVEZNCqS7baC4crd0pqnRH/5IB3jw3bcxGn6QLvnE
tfdUdiYrqBdss1l58BQ3KhooKeQTa9AB0Hw/Py5PJdTJNPY8cQn7ouZ2KKDcmnPG
BY5t7yLc1QlQ5xHdwW1VhvKn+nXqhJTBgIPgtldC+KDV5z+y2XDwGUcCgYEAuQPE
fgmVtjL0Uyyx88GZFF1fOunH3+7cepKmtH4pxhtCoHqpWmT8YAmZxaewHgHAjLYs
p1ZSe7zFYHj7C6ul7TjeLQeZD/YwD66t62wDmpe/HlB+TnBA+njbglfIsRLtXlnD
zQkv5dTltRJ11BKBBypeeF6689rjcJIDEz9RWdcCgYAHAp9XcCSrn8wVkMVkKdb7
DOX4IKjzdahm+ctDAJN4O/y7OW5FKebvUjdAIt2GuoTZ71iTG+7F0F+lP88jtjP4
U4qe7VHoewl4MKOfXZKTe+YCS1XbNvfgwJ3Ltyl1OH9hWvu2yza7q+d5PCsDzqtm
27kxuvULVeya+TEdAB1ijQKBgQCH/3r6YrVH/uCWGy6bzV1nGNOdjKc9tmkfOJmN
54dxdixdpozCQ6U4OxZrsj3FcOhHBsqAHvX2uuYjagqvo3cOj1TRqNocX40omfCC
Mx3bD1yPPf/6TI2XECva/ggqEY2mYzmIiA5LVVmc5nrybr+lssFKneeyxN2Wq93S
0iJMdQKBgCGHewxzoa1r8ZMD0LETNrToK423K377UCYqXfg5XMclbrjPbEC3YI1Z
NqMtuhdBJqUnBi6tjKMF+34Xf0CUN8ncuXGO2CAYvO8PdyCixHX52ybaDjy1FtCE
6yUXjoKNXKvUm7MWGsAYH6f4IegOetN5NvmUMFStCSkh7ixZLkN1
-----END RSA PRIVATE KEY-----"""

val signingInput: String =
    "eyJhbGciOiJSUzI1NiJ9.eyJpc3MiOiJqb2UiLA0KICJleHAiOjEzMDA4MTkzODAsDQogImh0dHA6Ly9leGFtcGxlLmNvbS9pc19yb290Ijp0cnVlfQ"
val publishedSignature: String =
    "cC4hiUPoj9Eetdgtv3hF80EGrhuB__dzERat0XF9g2VtQgr9PJbu3XOiZj5RZmh7AAuHIm4Bh-0Qc_lF5YKt_O8W2Fp5jujGbds9uJdbF9CUAr7t1dnZcAcQjbKBYNX4BAynRFdiuB--f_nZLgrnbyTyWzO75vRK5h6xBArLIARNPvkSjtQBMHlb1L07Qe7K0GarZRmB_eSN9383LcOLn6_dO--xi12jzDwusC-eOkHWEsqtFZESc6BfI7noOPqvhJ1phCnvWh6IeYI2w9QOYEUipUTI8np6LbgGY9Fs98rqVt5AXLIhWkWywlVmtVrBp0igcN_IoypGlUPQGe77Rw"

// Deterministic bytes for this executable example only. A service must draw fresh bytes from kyo.SecureRandom for every signature.
def drawn(size: Int): Span[Byte] = Span.from(Array.tabulate[Byte](size)(i => (i * 37 + 11).toByte))

def valueOf[E, A](result: Result[E, A]): A = result match
    case Result.Success(a) => a
    case other             => throw new IllegalStateException(s"expected a value, got $other")
```
-->

## Reading the key

Load a key once, then reuse it for successive signatures. `RsaSigning.signingKeyFromPem(pem)` accepts `RSA PRIVATE KEY` (PKCS#1) or `PRIVATE KEY` (PKCS#8). It checks the public part through `Rsa.VerificationKey`: a 2048 to 8192-bit odd modulus and an odd exponent between 3 and the 64-bit limit. Both factors must have half the modulus's bit width, rounded up. The private exponent and CRT components must satisfy their algebraic relationships.

The reader checks definite-length key boundaries and nonnegative, minimally encoded integers. It accepts redundant length octets, ignores optional PKCS#8 attributes and verifies that an optional public key matches. It checks key consistency, not primality or the quality of key generation: use keys from a trusted generator.

```scala doctest:expect=runs
import kyo.*
import kyo.crypto.*

val key: RsaSigning.SigningKey = valueOf(RsaSigning.signingKeyFromPem(pem))
assert(key.verificationKey.modulus.bitLength == 2048)
assert(key.verificationKey.exponent == BigInt(65537))
assert(key.sizeInBytes == 256)
assert(key.toString == "RsaSigning.SigningKey(2048 bits)")

val publicPem: Result[RsaSigning.KeyFailure, RsaSigning.SigningKey] =
    RsaSigning.signingKeyFromPem("-----BEGIN PUBLIC KEY-----\nMAA=\n-----END PUBLIC KEY-----")
assert(publicPem == Result.fail(RsaSigning.KeyFailure.PemHeaderMissing))
```

A `SigningKey` has no public constructor. Its `toString` names its size alone, and equality compares public parts. `KeyFailure` distinguishes encoding errors, unsupported versions or algorithms, public-key bounds and inconsistent private components, without exposing private key values.

## Signing

For each signature, supply fresh `key.blindingSizeInBytes` bytes from a cryptographic source. `RsaSigning.signSha256(key, message, blinding)` returns `key.sizeInBytes` signature bytes. The module takes randomness as an argument because it has no effect dependency, as `kyo-crypto`'s OAEP takes its seed. PKCS#1 v1.5 signatures are deterministic, so valid blinding draws produce the same signature.

```scala doctest:expect=runs
import kyo.*
import kyo.crypto.*

val key: RsaSigning.SigningKey = valueOf(RsaSigning.signingKeyFromPem(pem))
val message: Span[Byte]        = Span.from(signingInput.getBytes("US-ASCII"))

val signature: Span[Byte] = valueOf(RsaSigning.signSha256(key, message, drawn(key.blindingSizeInBytes)))
assert(Base64.encodeUrl(signature) == publishedSignature)
assert(RsaPkcs1.verifySha256(key.verificationKey, message, signature))

val short: Result[RsaSigning.SignFailure, Span[Byte]] = RsaSigning.signSha256(key, message, drawn(16))
assert(short.failure == Present(RsaSigning.SignFailure.BlindingLength(528)))
```

`BlindingLength` means the blinding buffer has the wrong length. On `BlindingNotInvertible`, draw fresh bytes and retry. `Fault` means the result failed public verification, which can indicate inconsistent key material or a computation fault; the signature is withheld.

Private exponentiation scans every limb and table entry. Blinding inversion uses variable-time arithmetic on a masked random value; key loading uses variable-time arithmetic on the private components. The source-level exponentiation checks do not establish runtime timing guarantees.

## What the module is not

- **Not a verifier.** `kyo-crypto`'s `RsaPkcs1.verifySha256` checks what this signs, under `key.verificationKey`.
- **Not a key generator.** The key comes from where it was made, as a PEM.
- **Not other paddings or hashes.** No PSS, no SHA-384 or SHA-512, no keys of more than two primes.
- **Not a JWT library.** It signs bytes; the token's header, claims and encoding are the caller's.
