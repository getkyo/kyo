package kyo.crypto

import kyo.*

/** HMAC (RFC 2104) instantiated with SHA-256, the construction RFC 4231 specifies and webhook signatures use.
  *
  * The tag is `H((K ^ opad) || H((K ^ ipad) || message))` over the 64-byte SHA-256 block, where a key longer than the block is first
  * replaced by its digest and a shorter one is zero-padded. Both hashes stream the padded key and the data, so the message is never
  * copied into a combined buffer.
  *
  * A received tag is checked with [[verifySha256]], which compares in constant time and answers `false` for a tag of any other length. A
  * tag must never be compared with `==`, `sameElements` or `Span.is`: those return at the first differing byte, and the timing tells the
  * sender how long a prefix of a forged tag is correct.
  *
  * @see
  *   [[Sha256]], the hash it is built on
  * @see
  *   [[ConstantTime.isEqual]], the comparison [[verifySha256]] uses
  * @see
  *   [[Pbkdf2.hmacSha256]], the key derivation that iterates it
  * @see
  *   [[Hex.decode]], the decoder a hex-encoded tag goes through before [[verifySha256]]
  */
object Hmac:

    private val BlockSize = 64

    /** The 32-byte HMAC-SHA-256 tag of `message` under `key`. */
    def sha256(key: Span[Byte], message: Span[Byte]): Span[Byte] =
        // Unsafe: the key and message arrays are only read; the tag array is fresh and held by nothing else.
        Span.fromUnsafe(sha256Array(key.toArrayUnsafe, message.toArrayUnsafe))

    /** Whether `tag` is the HMAC-SHA-256 tag of `message` under `key`, compared with [[ConstantTime.isEqual]]. A tag that is not 32 bytes
      * is not valid, and the comparison still runs over the computed tag.
      */
    def verifySha256(key: Span[Byte], message: Span[Byte], tag: Span[Byte]): Boolean =
        // Unsafe: every array is only read.
        ConstantTime.isEqualArrays(sha256Array(key.toArrayUnsafe, message.toArrayUnsafe), tag.toArrayUnsafe)

    private[kyo] def sha256Array(key: Array[Byte], message: Array[Byte]): Array[Byte] =
        val blockKey = if key.length > BlockSize then Sha256.hashArray(key) else key

        def pad(mask: Int): Array[Byte] =
            val out = new Array[Byte](BlockSize)
            var i   = 0
            while i < blockKey.length do
                out(i) = (blockKey(i) ^ mask).toByte
                i += 1
            end while
            while i < BlockSize do
                out(i) = mask.toByte
                i += 1
            end while
            out
        end pad
        val inner = Sha256.hashArrays(Chunk(pad(0x36), message))
        Sha256.hashArrays(Chunk(pad(0x5c), inner))
    end sha256Array

end Hmac
