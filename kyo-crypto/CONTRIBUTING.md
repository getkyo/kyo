# Contributing to kyo-crypto

Module-specific guide for kyo-crypto. Read the repository-root [CONTRIBUTING.md](../CONTRIBUTING.md) first: it carries the conventions, naming rules, type vocabulary, test patterns and the unsafe-boundary tiers that apply across all of Kyo. This document records only what is specific to kyo-crypto: the shape every public operation has, the two tiers inside one object, the checked-key types, how failures are modelled, what constant time does and does not mean here, the vendored vectors and their licences, and what the module refuses to contain.

## What kyo-crypto is

kyo-crypto is the pure-Scala cryptography the protocol modules verify, derive and encrypt with, written once and producing the same bytes on JVM, Scala.js, Scala Native and Wasm. The public surface, all in package `kyo.crypto`, reached with `import kyo.crypto.*`: cryptography is uncommon use, so it stays out of `import kyo.*`, the way kyo-net keeps `kyo.net`. A new public declaration goes in `kyo.crypto`, never in `kyo`:

- the digests `Sha1`, `Sha256`, `Sha512`, `Md5` (`hash`, `hashAll`);
- `Hmac` (`sha256`, `verifySha256`) and `ConstantTime` (`isEqual`);
- the derivations `Pbkdf2` (`hmacSha256`) and `Mgf1` (`sha1`);
- `Rsa` (`VerificationKey`, `EncryptionKey`, `verificationKeyFromJwk`, `encryptionKeyFromPem`, `encryptionKeyFromSpki`, the four bit-length constants, `KeyFailure`, `SpkiFailure`, `BoundsFailure`, `DerFailure`, `Component`), `RsaPkcs1` (`verifySha256`), `RsaOaep` (`encryptSha1`, `RsaOaep.Failure`);
- `Ed25519` (`VerificationKey`, `KeyFailure`, `verify`);
- `Saslprep` (`prepare`, `Saslprep.Failure`), the SCRAM password preparation;
- the `UUID.v5` and `UUID.v8Sha256` extensions, a top-level `extension (u: UUID.type)` in the package.

`kyo.internal.crypto` holds only internals: the byte and block helpers the primitives share, the Ed25519 field and scalar arithmetic, the NFKC normalizer and its generated `UnicodeTables`, and the generated `TestVectors`. The module depends on kyo-data alone. It has no effect dependency, and adding one is a design change, not a convenience: every operation is a pure function, the tests run without a scheduler, and the one operation that consumes randomness, OAEP, takes its seed as an argument so a ciphertext can be pinned.

## Two tiers in one object

Every public operation takes and returns `Span[Byte]`. No `Array[Byte]` appears in a public row; a public method that took an array would let a caller hand the module a buffer it keeps mutating, and would be the one place a copy could be skipped by accident. Each object also carries a `private[kyo]` array tier, named `<operation>Array` or `<operation>Arrays`, for dependents whose data is already an array, such as an authentication exchange feeding one digest into the next, and for the bridge itself. The two tiers are one implementation: the `Span` method unwraps with `toArrayUnsafe`, calls the array method, and wraps the fresh output with `Span.fromUnsafe`. The public method never has logic of its own.

Every `Span.fromUnsafe` and `toArrayUnsafe` site carries an `// Unsafe:` comment stating why it is sound: the input array is only read, or the output array is fresh and held by nothing else. A site without one is a review finding.

The array tier is named for its argument type (`hashArray`, not an overload) because `Span[Byte]` is an opaque type over the array, so an overload on the two would collide after erasure. When adding an operation, add both tiers and name them by this rule; the signature states that the `Span` form takes no `Array`, so no test pins the refusal.

`hashAll` takes `Chunk[Span[Byte]]` and digests the concatenation without building it. It exists for MACs and domain-separated derivations; do not add a `String` overload or a varargs form, since the caller chooses the encoding of its text.

## A key is a value that passed its checks

`Rsa.VerificationKey`, `Rsa.EncryptionKey` and `Ed25519.VerificationKey` are final plain classes with a private constructor, equality by value, and no `Schema`. Each is obtained only through a factory that applies its checks and returns a `Result`, so a value of the type is proof the checks ran. They are plain classes rather than case classes on purpose: a case class companion gets a synthesized `fromProduct` and the class a `copy`, both of which would build a key past the checks. The private constructor and the plain class are the whole guarantee; keep both when touching a key type.

The two RSA key types are two types because they were checked for different things. `VerificationKey` meets RFC 7518's 2048-bit floor for RS256 and is what `RsaPkcs1.verifySha256` takes. `EncryptionKey` meets a 1024-bit floor, since a weak server key is a configuration a client still has to talk to, and is what `RsaOaep.encryptSha1` takes. Neither converts to the other, so a key that passed only the encryption checks can never reach a signature check; the signatures of the two operations state it. Both apply the ceilings `MaxModulusBits` (8192) and `MaxExponentBits` (64) before any arithmetic, because `modPow` costs time quadratic in the modulus length and linear in the exponent's and the key came from the network. Both refuse an even modulus, an exponent below 3 and an even exponent, which RFC 8017 rules out for any RSA key.

`Rsa.publicOperation` is `private[kyo]`: RSAEP and RSAVP1 are one computation, but a public raw exponentiation invites a hand-rolled padding scheme. The public operations are the padded ones, `RsaPkcs1.verifySha256` and `RsaOaep.encryptSha1`.

An Ed25519 key is decoded once, strictly (RFC 8032 section 5.1.3), by `Ed25519.VerificationKey.fromBytes`, which also refuses a point of small order; the odd multiples of the negated point are held with the bytes, so `verify` decodes nothing: it compares the encoding of `[S]B + [k](-A)` with the bytes of `R`. The SPKI reader in `Rsa` is deliberately lenient (BER lengths, an unchecked algorithm identifier, trailing bytes ignored) because a database server's key arrives in whichever encoding its tool wrote; a leaf pins the leniency so a stricter reader is a deliberate change.

## Failures are values, and a bad signature is `false`

Whatever can be refused before an operation runs is refused with a value carrying what was measured: `Rsa.KeyFailure`, `Rsa.SpkiFailure`, `Rsa.BoundsFailure`, `Rsa.DerFailure`, `Ed25519.KeyFailure`, `RsaOaep.Failure`, `Saslprep.Failure`, and kyo-data's `Hex.Failure` and `Base64.Failure`. No public operation throws, and no failure is a module-specific exception: each caller (kyo-sql-mysql's `PasswordEncryption`, a webhook verifier) maps the neutral value to its own leaf. A new refusal is a new enum case with the measured number in it, never a message string.

A failure never carries the secret or the key. `RsaOaep.Failure.PlaintextTooLong` carries the maximum the key takes, not the plaintext's length, because the plaintext is a password. `Ed25519.KeyFailure` cases carry no key bytes.

Signature verification answers `Boolean`. A signature of the wrong length, an `S` not below the group order, a non-canonical `R`, a value not below the modulus and a bit flip are all "not valid", never a reason: the signature is the untrusted input, and a reason would only tell a forger which check to satisfy next. Do not add a `Result`-returning verify.

A public operation refuses an argument outside its bounds with a value too, never a panic: `Pbkdf2.hmacSha256` answers `Pbkdf2.Failure` for `iterations < 1` or `keyLength < 0`, `Mgf1.sha1` answers `Mgf1.Failure` for `length < 0`, each carrying the value offered. A peer-chosen count must still be bounded by the caller before it reaches the derivation, because the derivation runs on the calling carrier with no suspension point; the SCRAM client refuses `i <= 0` and `i > MaxIterations` with its own leaves first. Only an internal relation no argument can break is a `bug.check` (`Bytes.xor`'s length relation), and only an impossibility is a `bug` (an OAEP mask length the key bounds make positive). Each refusal has a leaf pinning the value.

## What constant time means here

`ConstantTime.isEqual` and `Hmac.verifySha256` (whose comparison is `isEqual`) are the only constant-time operations, and the scaladoc states exactly what is promised: no data-dependent exit in the source, the comparison reads every position up to the longer length, a length mismatch is a non-match, and no claim about what the JIT, a JavaScript engine or LLVM does with the loop. That is protection against a network observer timing a webhook or interaction endpoint, not against a co-located observer.

Everything else is not constant time and must say so where a reader could assume otherwise: `RsaPkcs1.verifySha256` and `Ed25519.verify` compare public values; `Hex` is for public values; RSA's `BigInt` arithmetic is what it is. Do not describe an operation as constant time because it has no early return, and do not add a comparison that returns at the first differing byte to any path that touches a tag.

## The vendored vectors

Every acceptance policy is pinned by a published vector or a leaf that states the policy. The vector sets live under `shared/src/test/vectors/<set>/`, each holding the upstream files byte for byte and a `MANIFEST` with `source`, `source-sha256`, `license` and one `file` line per file (name, SHA-256, path inside the source). The build's `TestVectorsGen` (in `project/`) turns the sets into the generated `kyo.internal.crypto.TestVectors` object, fails when a file is unlisted, missing or differs from its digest, and embeds the text so every platform reads the vectors without a file system. Tests parse the formats themselves (CAVP `.rsp`, Wycheproof JSON through `TestVectorsJson`, the RFC texts by section). The build verifies every digest before the tests compile; a suite that also checks its set's digests through the module's own `Sha256` runs that check on every platform.

The `license` line is the reviewer's evidence that the file may be redistributed; write it from the upstream's own terms (an IETF Trust TLP section, NIST's data licensing statement, Wycheproof's Apache 2.0, ed25519-speccheck's licence) and quote the clause that grants the right. RFC 1321 is vendored under the memo's own terms with the RSA Data Security notices retained; its appendix code is never ported, since the `Md5` implementation is written from the specification's loop form. A vector set without a licence line, or with a file whose provenance the manifest cannot state, does not ship.

The sets are the directories under `shared/src/test/vectors/`, each MANIFEST naming its source. JVM-only oracle suites compare against `java.security` and `javax.crypto` for the shapes the vectors do not cover, such as arbitrary split points and a round trip through the JDK's OAEP.

## SASLprep and the Unicode tables

`Saslprep.prepare` is RFC 4013 as PostgreSQL's `src/common/saslprep.c` applies it, and libpq is the reference because the server's stored secrets follow the same file: the prohibited-output and bidirectional checks read the mapped code points before NFKC, where RFC 3454 reads the output, and the caller falls back to the raw password when preparation fails. Both facts are pinned by leaves and stated in the scaladoc; a change that makes the checks read the output is a change to what password authenticates, not a conformance fix.

The tables come from build inputs under `shared/src/main/unicode/`, vendored with a MANIFEST in the vectors' shape: `UnicodeData.txt` and `CompositionExclusions.txt` of one Unicode version (15.1.0, PostgreSQL 17's) with the Unicode licence beside them, and the RFC 3454 text. `project/UnicodeTablesGen` verifies the digests, parses the decompositions, combining classes and exclusions from the data files and the stringprep tables from the RFC's own appendix text, and emits `kyo.internal.crypto.UnicodeTables` as a main source; nothing in it is typed by hand, and a suite compares the six stringprep tables with the six arrays of the vendored `saslprep.c`. The tables are a derived work of the Unicode Data Files and of RFC 3454's appendix tables and ship in the jar, so the generator writes the Unicode copyright line and licence reference, and the RFC's copyright notice with its derivative-works paragraph, into the generated file's header, and the jar carries `META-INF/kyo-crypto/NOTICE` with both, generated from the vendored `LICENSE` and RFC text, under the module's own directory so a shaded jar has no `META-INF/NOTICE` of this module's to merge; a JVM leaf reads the resource. The NFKC normalizer is `private[kyo]`, produces only NFKC, and is pinned by the Unicode conformance file on every platform; the module exposes no normalization API, and a caller that needs one is asking for a different module. The version can move only when the rest of the profile moves with it: every code point assigned after Unicode 3.2 is prohibited and the normalization stability policy freezes the decompositions of assigned code points, so a data file of any version from 4.1 on produces the same output for a string that passes; the corrigenda between 3.2 and 4.1 are the only exceptions.

## What the module refuses to contain

No ciphers, no key generation, no signing or any private-key operation, no key agreement, no randomness, no native backend, no `java.security` provider. No benchmark: the module is measured by its vectors and its bounds, not by throughput, and a benchmark would invite the platform-specific code paths the single implementation exists to avoid. No `Schema` on a key. No `String` in a public row where bytes are meant. A proposal that adds one of these is a proposal for a different module.

## Build and test

Set the standard JVM options before building (see the root guide). Building auto-formats; re-read edited files afterward.

```sh
sbt 'kyo-cryptoJVM/test'
sbt 'kyo-cryptoJVM/testOnly kyo.crypto.Ed25519Test'
sbt 'kyo-cryptoJVM/doctest'
sbt 'kyo-cryptoJS/test'
sbt 'kyo-cryptoNative/test'
sbt 'kyo-cryptoWasm/test'
```

Every behavioral suite lives in `shared/src/test/scala/kyo/` and runs on the four platforms; the JVM oracle suites are the only platform-gated ones, and each exists because its oracle is a JDK API with no cross-platform equivalent. After a change to a shared operation, rerun the dependents' authentication suites and their container suites for the wire round trip, and the suites of every module that compiles against the changed operation.

## Pre-submission checklist (kyo-crypto)

Beyond the root checklist:

- [ ] Every public operation takes and returns `Span[Byte]`; the array tier is `private[kyo]` and named by its argument type; every `Span.fromUnsafe` and `toArrayUnsafe` carries an `// Unsafe:` comment.
- [ ] A new operation has a vector or a policy leaf for each acceptance rule, and a leaf that its inputs are left unchanged; a `typeCheckFailure` leaf only asserts a message the module itself emits.
- [ ] A key type stays a final plain class with a private constructor and value equality; no `Schema`.
- [ ] Refusals are enum cases carrying the measured value, never the secret or the key; verification answers `Boolean`; invariants no peer controls are `bug.check` with a leaf.
- [ ] No operation is described as constant time except `ConstantTime.isEqual` and `Hmac.verifySha256`; the scaladoc of each non-constant-time verifier says so.
- [ ] A vendored vector set or build input has its `MANIFEST` with a `license` line quoting the grant, and the build verifies its digests; RFC 1321's appendix code is not ported; no Unicode table is edited by hand.
- [ ] No effect dependency, no cipher, no key generation, no signing, no randomness, no benchmark.
- [ ] `kyo-cryptoJVM/doctest` passes after a README change.
