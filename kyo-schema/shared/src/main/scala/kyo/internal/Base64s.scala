package kyo.internal

/** Base64 decoding that bounds its result to the byte length implied by the input text, not the length of the array the decoder returns.
  *
  * On scala-native 0.5.12 a concurrent heap-corruption defect can clobber a small array's length-header word between allocation and first
  * read; when it lands on the decoder's output array, a 3-byte decode reads back as hundreds of
  * bytes of adjacent heap, and the over-read propagates through any length-driven copy. The decoded length is fully determined by the text
  * (each non-pad character carries 6 bits), so recompute and trim when the returned array disagrees. On JVM and JS the trim never fires.
  */
private[kyo] object Base64s:

    /** Base64-decodes `value` and returns an array whose length is the text-implied decoded length. Throws `IllegalArgumentException` for
      * invalid input, exactly as the underlying decoder does (callers keep their existing catch).
      */
    def decodeExact(value: String): Array[Byte] =
        val decoded  = java.util.Base64.getDecoder.decode(value)
        val expected = decodedLength(value)
        if decoded.length == expected then decoded
        else java.util.Arrays.copyOf(decoded, expected)
    end decodeExact

    /** Decoded byte length implied by a base64 string the basic decoder accepts: `(chars - padding) * 6 / 8`. Exact for all pad forms. */
    private def decodedLength(value: String): Int =
        val len = value.length
        val pad =
            if len > 0 && value.charAt(len - 1) == '=' then
                if len > 1 && value.charAt(len - 2) == '=' then 2 else 1
            else 0
        (((len - pad).toLong * 3) / 4).toInt
    end decodedLength

end Base64s
