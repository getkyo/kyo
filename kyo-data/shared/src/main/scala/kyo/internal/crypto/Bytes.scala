package kyo.internal.crypto

/** Byte-array operations the primitives and the protocols built on them share. */
private[kyo] object Bytes:

    /** `a xor b` element-wise, as a fresh array of `a.length` bytes. Requires `b` to be at least as long as `a`; neither is modified.
      *
      * The authentication exchanges combine a key with a signature this way (a SCRAM client proof, a challenge response), and OAEP applies
      * its masks with it, so the bytes it produces are part of what the peer re-derives.
      */
    def xor(a: Array[Byte], b: Array[Byte]): Array[Byte] =
        val out = new Array[Byte](a.length)
        var i   = 0
        while i < a.length do
            out(i) = (a(i) ^ b(i)).toByte
            i += 1
        end while
        out
    end xor

end Bytes
