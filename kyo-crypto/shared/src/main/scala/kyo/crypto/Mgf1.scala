package kyo.crypto

import kyo.*
import kyo.internal.crypto.Blocks

/** MGF1 (RFC 8017 appendix B.2.1), the mask generation function OAEP and PSS padding apply, instantiated with SHA-1.
  *
  * The mask is `Hash(seed || C0) || Hash(seed || C1) || ...`, each counter a 4-byte big-endian integer from 0, truncated to the requested
  * length. SHA-1 is the instantiation RSA-OAEP's default profile fixes (RFC 8017 appendix A.2.1); the function is a pseudorandom
  * expansion of a seed, not a commitment, so SHA-1's broken collision resistance does not bear on it.
  *
  * `length` must be at least 0; a negative length is refused with a [[Mgf1.Failure]] before any work runs. OAEP derives it from a key
  * size it has already bounded, never from a peer's bytes directly.
  *
  * @see
  *   [[RsaOaep.encryptSha1]], the padding that applies it
  * @see
  *   [[Sha1]], the hash it expands with
  * @see
  *   [[Rsa.EncryptionKey.sizeInBytes]], where the mask lengths come from
  */
object Mgf1:

    /** Why [[sha1]] produced no mask: a negative length, carried as offered.
      *
      * @see
      *   [[sha1]], which produces it
      */
    enum Failure derives CanEqual:

        /** A negative mask length. */
        case Length(length: Int)
    end Failure

    private val DigestSize = 20

    /** `length` mask bytes derived from `seed`, or the bound `length` breaks. */
    def sha1(seed: Span[Byte], length: Int): Result[Failure, Span[Byte]] =
        // Unsafe: the seed array is only read; the mask array is fresh and held by nothing else.
        sha1Array(seed.toArrayUnsafe, length).map(Span.fromUnsafe)

    private[kyo] def sha1Array(seed: Array[Byte], length: Int): Result[Failure, Array[Byte]] =
        if length < 0 then Result.fail(Failure.Length(length))
        else Result.succeed(expand(seed, length))

    private def expand(seed: Array[Byte], length: Int): Array[Byte] =
        val blocks  = Blocks.count(length, DigestSize)
        val mask    = new Array[Byte](length)
        val counter = new Array[Byte](4)
        var i       = 0
        while i < blocks do
            Blocks.writeIntBigEndian(counter, 0, i)
            val digest = Sha1.hashArrays(Chunk(seed, counter))
            val offset = i * DigestSize
            java.lang.System.arraycopy(digest, 0, mask, offset, math.min(DigestSize, length - offset))
            i += 1
        end while
        mask
    end expand

end Mgf1
