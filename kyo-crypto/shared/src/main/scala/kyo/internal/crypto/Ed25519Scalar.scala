package kyo.internal.crypto

/** Arithmetic on scalars modulo the group order `L = 2^252 + 27742317777372353535851937790883648493`, TweetNaCl's `modL` and `reduce`
  * written function for function, with the width-5 signed-digit recoding of ref10's `slide` for the double-scalar multiplication.
  *
  * The intermediate `x` of [[reduce]] is 64 signed limbs held in `Double`; every value it takes is an integer below `2^30` in magnitude
  * (a limb starts below `2^8`; the carry of one row, at most about `2^12`, lands on a limb twelve positions down, which then multiplies
  * `16 L(j)` into a carry of at most about `2^16` that lands twelve further down, and the chain ends at limb 32 after two rounds, so a
  * term `16 x(i) L(j)` is at most about `2^28`), far below `2^53`, so the computation is exact on every platform, as in tweetnacl-js.
  *
  * Note: nothing here is constant time; the scalars are public.
  */
private[kyo] object Ed25519Scalar:

    /** `L` as 32 little-endian bytes, TweetNaCl's `L`. */
    private val L: Array[Int] = Array(
        0xed, 0xd3, 0xf5, 0x5c, 0x1a, 0x63, 0x12, 0x58, 0xd6, 0x9c, 0xf7, 0xa2, 0xde, 0xf9, 0xde, 0x14,
        0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0x10
    )

    /** `modL`: `x`, 64 little-endian limbs of 8 bits, reduced into the 32 bytes `r`; `x` is destroyed. */
    def modL(r: Array[Byte], x: Array[Double]): Unit =
        var i = 63
        while i >= 32 do
            var carry = 0.0
            var j     = i - 32
            while j < i - 12 do
                x(j) += carry - 16 * x(i) * L(j - (i - 32))
                carry = Math.floor((x(j) + 128) / 256)
                x(j) -= carry * 256
                j += 1
            end while
            x(j) += carry
            x(i) = 0
            i -= 1
        end while
        var carry = 0.0
        var j     = 0
        while j < 32 do
            x(j) += carry - Math.floor(x(31) / 16) * L(j)
            carry = Math.floor(x(j) / 256)
            x(j) -= carry * 256
            j += 1
        end while
        j = 0
        while j < 32 do
            x(j) -= carry * L(j)
            j += 1
        end while
        i = 0
        while i < 32 do
            x(i + 1) += Math.floor(x(i) / 256)
            r(i) = (x(i) - Math.floor(x(i) / 256) * 256).toInt.toByte
            i += 1
        end while
    end modL

    /** `reduce`: the 64-byte little-endian `hash` modulo `L` as 32 little-endian bytes, through `x`, a 64-double scratch. */
    def reduce(r: Array[Byte], hash: Array[Byte], x: Array[Double]): Unit =
        var i = 0
        while i < 64 do
            x(i) = (hash(i) & 0xff).toDouble
            i += 1
        end while
        modL(r, x)
    end reduce

    /** Whether the 32 little-endian bytes at `offset` are below `L`. */
    def isBelowL(bytes: Array[Byte], offset: Int): Boolean =
        var i = 31
        while i >= 0 do
            val b = bytes(offset + i) & 0xff
            if b < L(i) then return true
            if b > L(i) then return false
            i -= 1
        end while
        false
    end isBelowL

    /** ref10's `slide` (`crypto_sign/ed25519/ref10/ge_double_scalarmult.c` of supercop-20120210): the 32 little-endian bytes of a scalar below
      * `2^255` as 256 signed digits, each odd in `[-15, 15]` or zero, the four digits above a nonzero one zero, so
      * `scalar = sum digits(i) 2^i`.
      */
    def slide(digits: Array[Byte], scalar: Array[Byte]): Unit =
        var i = 0
        while i < 256 do
            digits(i) = (1 & ((scalar(i >> 3) & 0xff) >> (i & 7))).toByte
            i += 1
        end while
        i = 0
        while i < 256 do
            if digits(i) != 0 then
                var b    = 1
                var stop = false
                while b <= 6 && i + b < 256 && !stop do
                    if digits(i + b) != 0 then
                        if digits(i) + (digits(i + b) << b) <= 15 then
                            digits(i) = (digits(i) + (digits(i + b) << b)).toByte
                            digits(i + b) = 0
                        else if digits(i) - (digits(i + b) << b) >= -15 then
                            digits(i) = (digits(i) - (digits(i + b) << b)).toByte
                            var k    = i + b
                            var done = false
                            while k < 256 && !done do
                                if digits(k) == 0 then
                                    digits(k) = 1
                                    done = true
                                else
                                    digits(k) = 0
                                    k += 1
                                end if
                            end while
                        else stop = true
                    end if
                    b += 1
                end while
            end if
            i += 1
        end while
    end slide

end Ed25519Scalar
