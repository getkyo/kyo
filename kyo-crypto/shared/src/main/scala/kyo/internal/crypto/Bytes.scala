package kyo.internal.crypto

import kyo.bug

/** Byte-array operations the primitives and the protocols built on them share. */
private[kyo] object Bytes:

    /** `a xor b` element-wise, as a fresh array of `a.length` bytes. Requires `b` to be at least as long as `a`; neither is modified.
      *
      * The output is part of what a peer re-derives, so it must match the specification byte for byte.
      */
    def xor(a: Array[Byte], b: Array[Byte]): Array[Byte] =
        bug.check(b.length >= a.length)
        val out = new Array[Byte](a.length)
        var i   = 0
        while i < a.length do
            out(i) = (a(i) ^ b(i)).toByte
            i += 1
        end while
        out
    end xor

end Bytes
