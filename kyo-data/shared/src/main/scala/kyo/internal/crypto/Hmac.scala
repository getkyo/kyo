package kyo.internal.crypto

/** HMAC (RFC 2104) instantiated with SHA-256, the construction RFC 4231 specifies and webhook signatures use.
  *
  * The tag is `H((K ^ opad) || H((K ^ ipad) || message))` over the 64-byte SHA-256 block, where a key longer than the block is first
  * replaced by its digest and a shorter one is zero-padded. Both hashes stream the padded key and the data through `Sha256.hashChunks`, so
  * the message is never copied into a combined buffer.
  *
  * To check a received tag, compare it with the computed one through [[ConstantTime.isEqual]], never with `==` or `sameElements`.
  */
private[kyo] object Hmac:

    private val BlockSize = 64

    /** The 32-byte HMAC-SHA-256 tag of `message` under `key`. Neither argument is modified. */
    def sha256(key: Array[Byte], message: Array[Byte]): Array[Byte] =
        val blockKey = if key.length > BlockSize then Sha256.hash(key) else key

        def pad(mask: Int): Array[Byte] =
            Array.tabulate[Byte](BlockSize)(i => ((if i < blockKey.length then blockKey(i) else 0) ^ mask).toByte)
        val inner = Sha256.hashChunks(Seq(pad(0x36), message))
        Sha256.hashChunks(Seq(pad(0x5c), inner))
    end sha256

end Hmac
