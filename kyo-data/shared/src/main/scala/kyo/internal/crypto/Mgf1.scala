package kyo.internal.crypto

/** MGF1 (RFC 8017 appendix B.2.1), the mask generation function OAEP and PSS padding apply, instantiated with SHA-1.
  *
  * The mask is `Hash(seed || C0) || Hash(seed || C1) || ...`, each counter a 4-byte big-endian integer from 0, truncated to the requested
  * length. SHA-1 is the instantiation MySQL's `sha256_password` and `caching_sha2_password` plugins fix for their RSA-OAEP exchange; the
  * function is a pseudorandom expansion of a seed, not a commitment, so SHA-1's broken collision resistance does not bear on it.
  */
private[kyo] object Mgf1:

    private val DigestSize = 20

    /** `length` mask bytes derived from `seed`, which is not modified. */
    def sha1(seed: Array[Byte], length: Int): Array[Byte] =
        val blocks  = (length + DigestSize - 1) / DigestSize
        val mask    = new Array[Byte](blocks * DigestSize)
        val counter = new Array[Byte](4)
        var i       = 0
        while i < blocks do
            counter(0) = ((i >>> 24) & 0xff).toByte
            counter(1) = ((i >>> 16) & 0xff).toByte
            counter(2) = ((i >>> 8) & 0xff).toByte
            counter(3) = (i & 0xff).toByte
            val digest = Sha1.hashChunks(Seq(seed, counter))
            java.lang.System.arraycopy(digest, 0, mask, i * DigestSize, DigestSize)
            i += 1
        end while
        java.util.Arrays.copyOf(mask, length)
    end sha1

end Mgf1
