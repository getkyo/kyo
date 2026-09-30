package kyo.crypto

import java.nio.charset.StandardCharsets
import kyo.*

/** The name-based UUID constructors, as extension methods on the `UUID` companion so `UUID.v5(namespace, name)` reads as a constructor:
  * version 5 of RFC 9562, over SHA-1, and a version 8 profile over SHA-256 with a domain-separation prefix, for a name that must map to
  * the same identifier everywhere it is derived. They are reached with `import kyo.crypto.*`.
  *
  * They live here rather than in kyo-data because they digest with [[Sha1]] and [[Sha256]], and kyo-data holds no cryptography. Both
  * are pure functions of their arguments: equal `(namespace, name)` pairs always produce the same UUID, and the name is bytes so the
  * caller chooses its encoding.
  *
  * @see
  *   [[kyo.UUID]], the type both produce
  * @see
  *   [[Sha1.hashAll]] and [[Sha256.hashAll]], the digests over the namespace and the name
  * @see
  *   [[kyo.Hex]], for a UUID's bytes rendered as text
  */
extension (u: UUID.type)

    /** Deterministically derives a version 5 UUID from a namespace and a name, per RFC 9562's name-based algorithm.
      *
      * The result is `SHA-1(namespace.bytes ++ name)`, truncated to 128 bits with the version nibble set to `5` and the variant bits set
      * to the RFC 9562 variant. SHA-1 is what RFC 9562 fixes for version 5; the construction relies on none of the collision resistance
      * SHA-1 has lost.
      */
    def v5(namespace: UUID, name: Span[Byte]): UUID =
        UUID.fromHash(Sha1.hashAll(Chunk(namespace.bytes, name)), version = 5)

    /** Deterministically derives a version 8 UUID from a namespace and a name using the Kyo `v8Sha256` profile.
      *
      * The result hashes `u32be(21) ++ UTF8("kyo.uuid.v8.sha256.v1") ++ namespace.bytes ++ u32be(name.length) ++ name`, truncates the
      * SHA-256 digest to 128 bits, and sets the version nibble to `8` and the variant bits to the RFC 9562 variant. The unsigned 32-bit
      * lengths use big-endian encoding. A `Span` length is bounded by `Int`, so every accepted name length has an exact representation.
      */
    def v8Sha256(namespace: UUID, name: Span[Byte]): UUID =
        val input = Chunk(
            UUIDCrypto.unsignedIntBytes(UUIDCrypto.v8Sha256Domain.size),
            UUIDCrypto.v8Sha256Domain,
            namespace.bytes,
            UUIDCrypto.unsignedIntBytes(name.size),
            name
        )
        UUID.fromHash(Sha256.hashAll(input), version = 8)
    end v8Sha256
end extension

/** The constants of the version 8 profile. */
private[kyo] object UUIDCrypto:

    val v8Sha256Domain: Span[Byte] = Span.from("kyo.uuid.v8.sha256.v1".getBytes(StandardCharsets.UTF_8))

    def unsignedIntBytes(value: Int): Span[Byte] =
        Span.from(Array(
            (value >>> 24).toByte,
            (value >>> 16).toByte,
            (value >>> 8).toByte,
            value.toByte
        ))

end UUIDCrypto
