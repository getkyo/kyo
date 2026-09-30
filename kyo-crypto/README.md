# kyo-crypto

`kyo-crypto` is the set of cryptographic primitives that Kyo's protocol modules verify, derive and encrypt with: the digests (SHA-1, SHA-256, SHA-512, MD5), HMAC-SHA-256 with a constant-time check, PBKDF2 and MGF1, RSA signature verification (RS256) and RSA-OAEP encryption, Ed25519 signature verification, and the name-based UUID constructors. Every operation is written once in Scala and produces the same bytes on the JVM, Scala.js, Scala Native and Wasm, so a webhook verifier or a database handshake behaves identically wherever it runs, without `java.security` or `javax.crypto`.

Every public operation takes and returns `Span[Byte]`, never an `Array`. A key is a value that passed its checks (`Rsa.VerificationKey`, `Rsa.EncryptionKey`, `Ed25519.VerificationKey`): it cannot be constructed from raw numbers or bytes without going through the check, and a key checked for one purpose has a type no other operation accepts. Anything that can be refused (a key, a plaintext, a hex string) is refused with a failure value carrying what was measured, not an exception. The module depends only on `kyo-data` and has no effect dependency: nothing here suspends, and the one operation that needs randomness, OAEP, takes its seed as an argument.

```scala
import kyo.*
import kyo.crypto.*

// A webhook's HMAC-SHA-256 signature header, checked in constant time.
val sharedSecret: Span[Byte] = Span.from("Jefe".getBytes("UTF-8"))
val payload: Span[Byte]      = Span.from("what do ya want for nothing?".getBytes("UTF-8"))
val signatureHeader: String  = "5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843"
val accepted: Boolean        = Hex.decode(signatureHeader).exists(tag => Hmac.verifySha256(sharedSecret, payload, tag))
```

The rest of this document works through what a service receiving events does with these primitives: authenticating a delivery under a shared secret, verifying a signature from a peer's public key, encrypting a password to a server's key, digesting and deriving, and naming an event with a deterministic identifier.

<!-- doctest:setup
```scala
import kyo.*
import kyo.crypto.*

case class Delivery(body: Span[Byte], signatureHeader: String)
case class LoginToken(header: String, payload: String, signature: String)

val secret: Span[Byte] = Span.from("Jefe".getBytes("UTF-8"))

val delivery: Delivery = Delivery(
    Span.from("what do ya want for nothing?".getBytes("UTF-8")),
    "5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843"
)

// RFC 7515 appendix A.2: the JWK and the JWS it signs.
val jwkN: String =
    "ofgWCuLjybRlzo0tZWJjNiuSfb4p4fAkd_wWJcyQoTbji9k0l8W26mPddxHmfHQp-Vaw-4qPCJrcS2mJPMEzP1Pt0Bm4d4QlL-yRT-SFd2lZS-pCgNMsD1W_YpRPEwOWvG6b32690r2jZ47soMZo9wGzjb_7OMg0LOL-bSf63kpaSHSXndS5z5rexMdbBYUsLA9e-KXBdQOS-UTo7WTBEMa2R2CapHg665xsmtdVMTBQY4uDZlxvb3qCo5ZwKh9kG4LT6_I5IhlJH7aGhyxXFvUK-DWNmoudF8NAco9_h9iaGNj8q2ethFkMLs91kzk2PAcDTW9gb54h4FRWyuXpoQ"
val jwkE: String = "AQAB"

val token: LoginToken = LoginToken(
    "eyJhbGciOiJSUzI1NiJ9",
    "eyJpc3MiOiJqb2UiLA0KICJleHAiOjEzMDA4MTkzODAsDQogImh0dHA6Ly9leGFtcGxlLmNvbS9pc19yb290Ijp0cnVlfQ",
    "cC4hiUPoj9Eetdgtv3hF80EGrhuB__dzERat0XF9g2VtQgr9PJbu3XOiZj5RZmh7AAuHIm4Bh-0Qc_lF5YKt_O8W2Fp5jujGbds9uJdbF9CUAr7t1dnZcAcQjbKBYNX4BAynRFdiuB--f_nZLgrnbyTyWzO75vRK5h6xBArLIARNPvkSjtQBMHlb1L07Qe7K0GarZRmB_eSN9383LcOLn6_dO--xi12jzDwusC-eOkHWEsqtFZESc6BfI7noOPqvhJ1phCnvWh6IeYI2w9QOYEUipUTI8np6LbgGY9Fs98rqVt5AXLIhWkWywlVmtVrBp0igcN_IoypGlUPQGe77Rw"
)

// RFC 8032 section 7.1, test 1: an Ed25519 key and its signature over the empty message.
val ed25519KeyHex: String       = "d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a"
val ed25519SignatureHex: String =
    "e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e065224901555fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b"

val serverKeyPem: String =
    """-----BEGIN PUBLIC KEY-----
MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEA0fjhZ5a4z9ULtk0Xdeq1
O79oB9+t9VWGEicXHNrkIsqPswer2tOwDE4hlu/GkDh8w2kO9K/x+q+mSPg5SzlT
dDHBTlLTQnHm8Wc74CPBJHExcwJzuq7Xy1c1tmD1m69EO1QFvJcso/10RK3pnJ8g
IpWqwVJ8QOsXSRnwvTJYAUX0A/HLISgxI4YFXQUKevNxdQlLd82Wne6qZIjZwiXc
JvvIoQ/d4dsFhMs0FSSw9fgXcG3x89kCSj2TyUl0KlyL5AWr1gRqS4Psjo62GTTc
sufsIMrHVlDaMkvdPnPFtyARqWknXA1Lj6DfjcBSwaQY9F0g7T4UxV9SobYFeftU
FwIDAQAB
-----END PUBLIC KEY-----"""

// Twenty bytes a SecureRandom drew; in a service they come from kyo.SecureRandom.nextBytes(20).
val seed: Span[Byte] = Span.from(Array.tabulate[Byte](20)(i => (i * 37 + 11).toByte))

def valueOf[E, A](result: Result[E, A]): A = result match
    case Result.Success(a) => a
    case other             => throw new IllegalStateException(s"expected a value, got $other")
```
-->

## Authenticating a delivery under a shared secret

When a platform and a service share a secret, the platform signs each delivery with HMAC-SHA-256 and the service recomputes the tag over the body it received. The tag arrives as text (a hex or base64 header), so the first step is decoding it, and the second is a comparison whose running time must not depend on how much of a forged tag is right.

### Verifying the tag

`Hmac.verifySha256(key, message, tag)` computes the tag of `message` under `key` and compares it with `tag` in constant time. A tag of any other length is not valid, and the comparison still runs over the computed tag, so a short forgery costs the same as a long one.

```scala
import kyo.*
import kyo.crypto.*

def authentic(delivery: Delivery): Boolean =
    Hex.decode(delivery.signatureHeader) match
        case Result.Success(tag) => Hmac.verifySha256(secret, delivery.body, tag)
        case Result.Failure(_)   => false
        case Result.Panic(_)     => false

assert(authentic(delivery))
assert(!authentic(delivery.copy(body = Span.from("what do ya want for nothing!".getBytes("UTF-8")))))
assert(!authentic(delivery.copy(signatureHeader = "5bdc")))
```

`Hex.decode` (from `kyo-data`) fails with a `Hex.Failure` naming an odd length or the offset of the first character outside `0-9 a-f A-F`; a header that is not hex is not a valid signature, so the failure collapses to `false`.

> **Caution:** never compare a received tag with `==`, `sameElements` or `Span.is`. Those return at the first differing byte, and the time they take tells the sender how long a prefix of a forged tag is correct.

### Producing a tag, and comparing something else in constant time

The service is sometimes the sender: an outbound callback, or a token it will later verify itself. `Hmac.sha256(key, message)` is the 32-byte tag.

```scala
import kyo.*
import kyo.crypto.*

val tag: Span[Byte] = Hmac.sha256(secret, delivery.body)
assert(Hex.encode(tag) == delivery.signatureHeader)
```

`Hmac.verifySha256` covers the case where the value to compare is the tag itself. When the value is something else a peer computed and sent, such as the server signature at the end of a SCRAM exchange, compare it with `ConstantTime.isEqual`, the same comparison `verifySha256` uses.

```scala
import kyo.*
import kyo.crypto.*

val expected: Span[Byte] = Hmac.sha256(secret, Span.from("server".getBytes("UTF-8")))
val received: Span[Byte] = Hmac.sha256(secret, Span.from("server".getBytes("UTF-8")))
assert(ConstantTime.isEqual(expected, received))
assert(!ConstantTime.isEqual(expected, Span.from(received.toArray.take(16))))
```

`ConstantTime.isEqual` reads every position up to the longer length and folds the differences together before deciding, with no early exit. The lengths are not hidden: a length mismatch is a non-match, and the loop runs to the longer length. What is guaranteed is the absence of a data-dependent exit in this source; no claim is made about what the JIT, a JavaScript engine or LLVM does with the loop. This is the level of protection a network timing attack on a webhook or interaction endpoint calls for, not protection against a co-located observer.

## Verifying a signature from a peer's public key

When the sender holds a private key and publishes the public one (a JSON Web Key set, an interactions endpoint's Ed25519 key), the service checks a signature rather than a shared tag. Both signature schemes here separate the key from the check: the key is decoded and checked once into a value, and that value is what verification takes, so a key that failed its checks cannot reach the arithmetic.

### RS256 from a JSON Web Key

A JWK carries the modulus and exponent as unpadded base64url. `Rsa.verificationKeyFromJwk(n, e)` reads them and applies every check a verification key must pass: at least 2048 bits (RFC 7518 section 3.3's floor for RS256), at most 8192, an odd modulus, an odd exponent of at least 3, and an exponent of at most 64 bits. The ceilings matter because `modPow` costs time quadratic in the modulus length and linear in the exponent's, and the key came from the network.

```scala
import kyo.*
import kyo.crypto.*

val key: Rsa.VerificationKey = valueOf(Rsa.verificationKeyFromJwk(jwkN, jwkE))
assert(key.modulus.bitLength == 2048)
assert(key.exponent == BigInt(65537))
assert(key.sizeInBytes == 256)

val tooSmall: Result[Rsa.KeyFailure, Rsa.VerificationKey] =
    Rsa.verificationKeyFromJwk(Base64.encodeUrl(Span.from(Array.fill[Byte](128)(1))), jwkE)
assert(tooSmall.failure == Present(Rsa.KeyFailure.Bounds(Rsa.BoundsFailure.ModulusTooSmall(1017, 2048))))
```

`Rsa.KeyFailure` names the encoding problem (`NotBase64Url`, `LeadingZero`, each with the `Component` it was found in) or, as `Bounds`, the `Rsa.BoundsFailure` the numbers failed (`ModulusTooSmall`, `ModulusTooLarge`, `ModulusEven`, `ExponentTooSmall`, `ExponentTooLarge`, `ExponentEven`) with what was measured. `Rsa.VerificationKey(modulus, exponent)` applies the same checks to numbers obtained another way.

With the key, `RsaPkcs1.verifySha256(key, message, signature)` is RSASSA-PKCS1-v1_5 with SHA-256. For a JWS the message is the signing input, `header.payload` as ASCII, and the signature is the third part decoded from base64url.

```scala
import kyo.*
import kyo.crypto.*

val key: Rsa.VerificationKey = valueOf(Rsa.verificationKeyFromJwk(jwkN, jwkE))
val signingInput: Span[Byte] = Span.from(s"${token.header}.${token.payload}".getBytes("US-ASCII"))
val signature: Span[Byte]    = valueOf(Base64.decodeUrl(token.signature))

assert(RsaPkcs1.verifySha256(key, signingInput, signature))

val tampered: Span[Byte] = Span.from(s"${token.header}.${token.payload}x".getBytes("US-ASCII"))
assert(!RsaPkcs1.verifySha256(key, tampered, signature))
assert(!RsaPkcs1.verifySha256(key, signingInput, Span.from(signature.toArray.drop(1))))
```

The recovered block is compared whole with the one expected encoding, so a malformed padding, a `DigestInfo` without its NULL parameter or with trailing bytes, and a signature whose value is not below the modulus are all "not valid", never a partial match. Verification answers `Boolean`: the signature is the untrusted input, and there is nothing a caller does with the reason it failed.

> **Note:** `RsaPkcs1.verifySha256` is not constant time and does not need to be. It compares public values, the signature and the digest of a message the verifier already holds.

### Ed25519 from raw key bytes

An Ed25519 public key is 32 bytes, usually given as hex. `Ed25519.VerificationKey.fromBytes` decodes them once under the strict rules of RFC 8032 section 5.1.3, so a key that is not canonical is refused before any signature is checked.

```scala
import kyo.*
import kyo.crypto.*

val edKey: Ed25519.VerificationKey =
    valueOf(Hex.decode(ed25519KeyHex).flatMap(Ed25519.VerificationKey.fromBytes))

val edSignature: Span[Byte] = valueOf(Hex.decode(ed25519SignatureHex))
val emptyMessage: Span[Byte] = Span.empty[Byte]

assert(Ed25519.verify(edKey, emptyMessage, edSignature))
assert(!Ed25519.verify(edKey, Span.from(Array[Byte](0)), edSignature))

val short: Result[Ed25519.KeyFailure, Ed25519.VerificationKey] =
    Ed25519.VerificationKey.fromBytes(Span.from(edKey.bytes.toArray.take(31)))
assert(short.failure == Present(Ed25519.KeyFailure.Length(31)))
```

`Ed25519.KeyFailure` is `Length(length)`, `YNotReduced` (the encoded `y` is at or above the field prime), `NotOnCurve` (no `x` has that `y`), `ZeroWithSign` (`x = 0` with the sign bit set, which asks for an odd root that 0 is not) or `SmallOrder` (one of the eight points of order dividing 8, which no honest key is and under which a signature with `S = 0` verifies for some messages). None carries the key bytes.

`Ed25519.verify(key, message, signature)` requires a 64-byte signature whose `S` is below the group order and whose `R` decodes under the same strict rules, and checks the cofactorless equation `[S]B = R + [k]A`. That is the equation of RFC 8032's reference code and of the verifiers Discord documents for its interactions endpoint (tweetnacl, libsodium, Tink), and of Go, BoringSSL and OpenSSL; it differs from the cofactored form only on forged edge cases built from small-order points, never on an honest signature. A small-order `R` is not refused: RFC 8032 has no blocklist, and a key that passed `fromBytes` has no small-order forgery through it.

### Which key type for what

Both RSA operations take a key, but not the same key. `Rsa.VerificationKey` met the 2048-bit floor and is what `RsaPkcs1.verifySha256` takes. `Rsa.EncryptionKey`, introduced next, met a 1024-bit floor and is what `RsaOaep.encryptSha1` takes. Neither converts to the other: a server's 1024-bit key is a weak configuration a client still has to talk to, while a 1024-bit RS256 key is outside RFC 7518, so a value of one type is never accepted where the other is required, and the compiler enforces it.

## Encrypting a password to a server's key

MySQL's `sha256_password` and `caching_sha2_password` plugins send the server's RSA public key over a plaintext connection and expect the password back encrypted to it with RSA-OAEP. The key arrives as a PEM `PUBLIC KEY` block (a SubjectPublicKeyInfo) in an authentication packet, from a peer that is neither encrypted nor authenticated yet.

### Reading the server's key

`Rsa.encryptionKeyFromPem(pem)` reads the block: base64 between the markers, whitespace anywhere, a missing final `=` padded. `Rsa.encryptionKeyFromSpki(der)` takes the DER bytes directly. Both apply the encryption checks: the same two ceilings as a verification key, an odd modulus of at least 1024 bits, an odd exponent of at least 3. The floor exists because what is encrypted to the key is a password: a modulus an observer can factor, or an exponent of 1 (the identity), would hand it to whoever reads the wire.

```scala
import kyo.*
import kyo.crypto.*

val serverKey: Rsa.EncryptionKey = valueOf(Rsa.encryptionKeyFromPem(serverKeyPem))
assert(serverKey.sizeInBytes == 256)

val noHeader: Result[Rsa.SpkiFailure, Rsa.EncryptionKey] = Rsa.encryptionKeyFromPem("MIIBIjANBgkq...")
assert(noHeader.failure == Present(Rsa.SpkiFailure.PemHeaderMissing))

val cut: Result[Rsa.SpkiFailure, Rsa.EncryptionKey] = Rsa.encryptionKeyFromSpki(Span.from(Array[Byte](0x30)))
assert(cut.failure == Present(Rsa.SpkiFailure.Der(Rsa.DerFailure.LengthEndOfData)))
```

`Rsa.SpkiFailure` names the text (`PemHeaderMissing`, `PemNotBase64` with the `Base64.Failure`), the structure (`Der` with a `Rsa.DerFailure` saying where the reader stopped), or, as `Bounds`, the `Rsa.BoundsFailure` the numbers failed, with what was measured. The reader takes BER as well as DER, does not check a declared length against the bytes that follow, and does not compare the algorithm identifier, because a database server's key arrives in whichever encoding its tool wrote.

### Producing the ciphertext

`RsaOaep.encryptSha1(key, plaintext, seed)` is RSA-OAEP with SHA-1, MGF1-SHA-1 and an empty label, the instantiation the MySQL plugins fix, and its output is byte for byte what the JDK's `RSA/ECB/OAEPWithSHA-1AndMGF1Padding` produces from the same seed. The seed is the 20 random bytes OAEP masks the message with, and the caller draws it, from `kyo.SecureRandom` in a service, because this module does not consume randomness and a seed the function drew itself could not be pinned in a test.

```scala
import kyo.*
import kyo.crypto.*

val serverKey: Rsa.EncryptionKey = valueOf(Rsa.encryptionKeyFromPem(serverKeyPem))
val password: Span[Byte]         = Span.from("p4ssw0rd\u0000".getBytes("UTF-8"))

val ciphertext: Span[Byte] = valueOf(RsaOaep.encryptSha1(serverKey, password, seed))
assert(ciphertext.size == 256)

val tooLong: Result[RsaOaep.Failure, Span[Byte]] =
    RsaOaep.encryptSha1(serverKey, Span.from(Array.fill[Byte](215)(0x42)), seed)
assert(tooLong.failure == Present(RsaOaep.Failure.PlaintextTooLong(214)))

val badSeed: Result[RsaOaep.Failure, Span[Byte]] =
    RsaOaep.encryptSha1(serverKey, password, Span.from(seed.toArray.take(19)))
assert(badSeed.failure == Present(RsaOaep.Failure.SeedLength(20)))
```

`RsaOaep.Failure.PlaintextTooLong` carries the maximum the key takes, `k - 42` bytes for a key of `k` bytes, and never the plaintext's length, since the plaintext is a password. `RsaOaep.Failure.SeedLength` carries the 20 bytes expected.

## Digests and derived keys

The operations above are built from the digests, and a protocol sometimes needs a digest directly: a WebSocket accept key is a SHA-1, a SCRAM client key is a chain of SHA-256 and HMAC, PostgreSQL's legacy `md5` authentication is two MD5s.

### Digesting one value or several

`Sha256.hash(input)`, and the same on `Sha1`, `Sha512` and `Md5`, digests one span. `hashAll(parts)` digests the concatenation of several spans without building it, which is what a MAC or a domain-separated derivation needs: the parts are fed to the block function in order.

```scala
import kyo.*
import kyo.crypto.*

val whole: Span[Byte] = Sha256.hash(delivery.body)
assert(whole.size == 32)
assert(Hex.encode(whole) == "b381e7fec653fc3ab9b178272366b8ac87fed8d31cb25ed1d0e1f3318644c89c")

val head: Span[Byte] = Span.from(delivery.body.toArray.take(9))
val tail: Span[Byte] = Span.from(delivery.body.toArray.drop(9))
assert(ConstantTime.isEqual(Sha256.hashAll(Chunk(head, tail)), whole))
```

A digest is not a MAC: to authenticate a message under a key use `Hmac.sha256`, and to compare a received digest use `ConstantTime.isEqual`, never `==`.

> **Caution:** `Sha1` and `Md5` are broken for collision resistance. They exist for protocols that fix them by specification and rely on none of the properties they have lost: the WebSocket accept key, `mysql_native_password`, MGF1 inside MySQL's OAEP and a version 5 UUID for SHA-1; PostgreSQL's `md5` password exchange for MD5. Neither is a choice for a new design, a checksum of untrusted data, a signature, a certificate or a password hash.

### Stretching a password

SCRAM-SHA-256 salts a password with PBKDF2 over HMAC-SHA-256, with a salt and an iteration count the server chose. `Pbkdf2.hmacSha256(password, salt, iterations, keyLength)` is that derivation; the values below are RFC 7677's.

```scala
import kyo.*
import kyo.crypto.*

val salt: Span[Byte]   = valueOf(Base64.decode("W22ZaJ0SNY7soEsUEjb6gQ=="))
val salted: Span[Byte] = valueOf(Pbkdf2.hmacSha256(Span.from("pencil".getBytes("UTF-8")), salt, 4096, 32))
assert(Hex.encode(salted) == "c4a49510323ab4f952cac1fa99441939e78ea74d6be81ddf7096e87513dc615d")
```

The iteration count is work the caller commits to before calling: the derivation runs on the calling carrier with no suspension point, and `iterations` below 1 or `keyLength` below 0 is refused with a `Pbkdf2.Failure` carrying the value offered before any work runs. When the count comes from a peer, as SCRAM's server-first message carries it, bound it from above first.

### Preparing the password

SCRAM salts `Normalize(password)`, not the password: SASLprep (RFC 4013) maps a no-break space to a space, drops a soft hyphen or a zero-width joiner, and normalizes to NFKC, so a fullwidth digit becomes its ASCII digit and a decomposed accent recomposes. PostgreSQL applies it on both sides, when the server stores a SCRAM secret and when libpq authenticates, so a client that salts the raw bytes cannot log in with any password the profile changes. `Saslprep.prepare` is that profile, step for step as PostgreSQL's `pg_saslprep` applies it.

```scala
import kyo.*
import kyo.crypto.*

def text(codePoints: Int*): String =
    val out = new java.lang.StringBuilder
    codePoints.foreach(c => out.appendCodePoint(c))
    out.toString

// fullwidth p, a, s, s, a no-break space, fullwidth 1
assert(Saslprep.prepare(text(0xff50, 0xff41, 0xff53, 0xff53, 0x00a0, 0xff11)) == Result.succeed("pass 1"))
// c, a, f, e, combining acute accent: recomposed to e with acute
assert(Saslprep.prepare(text('c', 'a', 'f', 'e', 0x0301)) == Result.succeed(text('c', 'a', 'f', 0x00e9)))
// a soft hyphen alone
assert(Saslprep.prepare(text(0x00ad)) == Result.fail(Saslprep.Failure.Empty))
// an emoji, unassigned in Unicode 3.2
assert(Saslprep.prepare(text(0x1f600)) == Result.fail(Saslprep.Failure.Prohibited(0)))
```

A failure names an index, never a character. A SCRAM client does not surface one: when preparation fails, on an emoji or any code point assigned after Unicode 3.2, libpq and the server both fall back to the raw password, and the client here does the same, so a role whose password the profile refuses still logs in.

### Expanding a seed

`Mgf1.sha1(seed, length)` is the mask generation function OAEP applies, `SHA-1(seed || counter)` for counters from 0, cut to `length`. It is public because a protocol that fixes MGF1-SHA-1 (a PSS or OAEP variant) can build on it; `RsaOaep` uses it internally.

```scala
import kyo.*
import kyo.crypto.*

val mask: Span[Byte] = valueOf(Mgf1.sha1(Span.from(Array[Byte](0, 0, 0, 0)), 20))
assert(Hex.encode(mask) == "05fe405753166f125559e7c9ac558654f107c7e9")
```

SHA-1 here expands a seed rather than committing to attacker-chosen data, so its broken collision resistance does not bear on it.

## Naming an event with a deterministic identifier

A service that stores what it received wants an identifier it can recompute from the delivery, so a redelivery maps to the same row. The name-based UUID constructors hash a namespace and a name into a UUID; they live in this module because they digest, and reach `UUID` as extension methods under `import kyo.crypto.*`.

### Version 5, the RFC construction

`UUID.v5(namespace, name)` is RFC 9562's name-based algorithm: `SHA-1(namespace.bytes ++ name)`, truncated to 128 bits with the version nibble set to 5 and the RFC variant bits set.

```scala
import kyo.*
import kyo.crypto.*

val dns: UUID = UUID.parse("6ba7b810-9dad-11d1-80b4-00c04fd430c8").getOrThrow
val site: UUID = UUID.v5(dns, Span.from("www.example.com".getBytes("UTF-8")))
assert(site.show == "2ed6657d-e927-568b-95e1-2665a8aea6a2")
assert(site.version == 5)
```

### Version 8, the SHA-256 profile

`UUID.v8Sha256(namespace, name)` is Kyo's version 8 profile over SHA-256: it hashes a fixed domain string, the namespace and the name, each length-prefixed, and stamps version 8. The length prefixes mean a name boundary cannot shift: `(ns, "ab")` and a name that merely starts with `a` are different inputs.

```scala
import kyo.*
import kyo.crypto.*

val dns: UUID     = UUID.parse("6ba7b810-9dad-11d1-80b4-00c04fd430c8").getOrThrow
val eventId: UUID = UUID.v8Sha256(dns, delivery.body)
assert(eventId.version == 8)
assert(eventId == UUID.v8Sha256(dns, delivery.body))
assert(eventId != UUID.v8Sha256(dns, Span.from(delivery.body.toArray.dropRight(1))))
```

Use `v5` when the identifier must match one another system computes from the same inputs, since the RFC construction is what other libraries implement. Use `v8Sha256` when the identifier is yours: SHA-256 and the length prefixes give a construction that does not rely on SHA-1 and cannot be confused by a shifted boundary. Both are pure functions of their arguments; neither generates random or time-based values, which `kyo-core`'s `UUID` generators do.

## Putting it together

A delivery is authenticated under the shared secret, its login token is verified against the platform's published key, and the stored row is keyed by an identifier the same delivery always maps to.

```scala
import kyo.*
import kyo.crypto.*

case class Accepted(id: UUID, body: Span[Byte])

val platformKey: Rsa.VerificationKey = valueOf(Rsa.verificationKeyFromJwk(jwkN, jwkE))
val namespace: UUID                  = UUID.parse("6ba7b810-9dad-11d1-80b4-00c04fd430c8").getOrThrow

def accept(delivery: Delivery, token: LoginToken): Result[String, Accepted] =
    val authentic = Hex.decode(delivery.signatureHeader).exists(tag => Hmac.verifySha256(secret, delivery.body, tag))
    if !authentic then Result.fail("signature header does not authenticate the body")
    else
        val signingInput: Span[Byte] = Span.from(s"${token.header}.${token.payload}".getBytes("US-ASCII"))
        Base64.decodeUrl(token.signature).mapFailure(_.message).flatMap { signature =>
            if RsaPkcs1.verifySha256(platformKey, signingInput, signature) then
                Result.succeed(Accepted(UUID.v8Sha256(namespace, delivery.body), delivery.body))
            else Result.fail("login token is not signed by the platform key")
        }
    end if

assert(accept(delivery, token).isSuccess)
assert(accept(delivery.copy(signatureHeader = "00"), token).failure == Present("signature header does not authenticate the body"))
```

## What the module is not

`kyo-crypto` verifies, derives and encrypts to a peer's key. It has no ciphers (no AES, no ChaCha20), no key generation, no signing (no private-key operation of any kind), no key agreement and no randomness: a seed or a nonce comes from `kyo.SecureRandom` on the caller's side. It is not a general-purpose cryptography library, and it does not take a `java.security` provider or a native backend. It holds one string preparation, SASLprep, because SCRAM salts the prepared password; it holds no other stringprep profile and exposes no Unicode normalization of its own.

Nothing in it is constant time except `ConstantTime.isEqual` and `Hmac.verifySha256`'s comparison, which is that function. The digests, the key derivations, the RSA arithmetic (which is `BigInt`), the Ed25519 field arithmetic and the OAEP encoding all run in time that depends on their inputs. That is the right property for what they handle: public values, or a password whose length the protocol already reveals. It is the wrong property for a secret key compared byte by byte, which is why the two comparison functions exist and why a received tag never meets `==`.

Every operation produces the same bytes on the JVM, Scala.js, Scala Native and Wasm, from one implementation, pinned by the same vendored vectors (the NIST CAVP files for SHA-1, SHA-256 and SHA-512, RFC 1321, RFC 4013, RFC 4231, RFC 7515, RFC 7677, RFC 7914, RFC 8032, Wycheproof, ed25519-speccheck, the Unicode normalization conformance file and PostgreSQL's `saslprep.c`) on each. The RSA operations are `BigInt` arithmetic, so their cost differs between platforms: a JavaScript `BigInt` is slower than the JVM's, which is one more reason the key ceilings exist. The Ed25519 arithmetic is sixteen 16-bit limbs held in `Double`, which every platform computes exactly.
