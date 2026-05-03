package kyo

/** Cross-platform Base64 (RFC 4648) encoder/decoder.
  *
  * Provides a pure-Scala implementation of standard ("basic") Base64 encoding and decoding so kyo modules can avoid depending on
  * `java.util.Base64` from `shared/src/main`. The standard alphabet (`A-Z a-z 0-9 + /`) with `=` padding is used; line breaks are not
  * inserted. Decoding rejects non-alphabet, non-padding characters.
  *
  * The implementation cross-compiles to JVM, Scala.js, and Scala Native without relying on the `java.util.Base64` shape exported by each
  * platform's javalib stub.
  */
object Base64:

    private val Alphabet: Array[Char] =
        ("ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/").toCharArray

    private val Decode: Array[Int] =
        val arr = Array.fill[Int](128)(-1)
        var i   = 0
        while i < Alphabet.length do
            arr(Alphabet(i).toInt) = i
            i += 1
        arr
    end Decode

    /** Encodes a `Span[Byte]` to its Base64 string representation using the standard alphabet with `=` padding. */
    def encode(bytes: Span[Byte]): String =
        val len = bytes.size
        if len == 0 then ""
        else
            val outLen = ((len + 2) / 3) * 4
            val out    = new Array[Char](outLen)
            var i      = 0
            var o      = 0
            while i + 3 <= len do
                val b0 = bytes(i) & 0xff
                val b1 = bytes(i + 1) & 0xff
                val b2 = bytes(i + 2) & 0xff
                out(o) = Alphabet(b0 >>> 2)
                out(o + 1) = Alphabet(((b0 & 0x03) << 4) | (b1 >>> 4))
                out(o + 2) = Alphabet(((b1 & 0x0f) << 2) | (b2 >>> 6))
                out(o + 3) = Alphabet(b2 & 0x3f)
                i += 3
                o += 4
            end while
            val rem = len - i
            if rem == 1 then
                val b0 = bytes(i) & 0xff
                out(o) = Alphabet(b0 >>> 2)
                out(o + 1) = Alphabet((b0 & 0x03) << 4)
                out(o + 2) = '='
                out(o + 3) = '='
            else if rem == 2 then
                val b0 = bytes(i) & 0xff
                val b1 = bytes(i + 1) & 0xff
                out(o) = Alphabet(b0 >>> 2)
                out(o + 1) = Alphabet(((b0 & 0x03) << 4) | (b1 >>> 4))
                out(o + 2) = Alphabet(((b1 & 0x0f) << 2))
                out(o + 3) = '='
            end if
            new String(out)
        end if
    end encode

    /** Decodes a Base64-encoded string into a `Span[Byte]`.
      *
      * Returns a `Result.Failure` on malformed input (non-alphabet character, length not divisible by 4, padding in the wrong place). On
      * success the returned `Span` owns a fresh array, so the caller may safely retain it.
      */
    def decode(string: String): Result[IllegalArgumentException, Span[Byte]] =
        val s   = string
        val len = s.length
        if len == 0 then Result.succeed(Span.empty[Byte])
        else if len % 4 != 0 then
            Result.fail(new IllegalArgumentException(s"Base64 input length must be a multiple of 4 (got $len)"))
        else
            // Count padding characters (must be 0, 1, or 2 trailing `=`).
            var pad = 0
            if s.charAt(len - 1) == '=' then pad += 1
            if len >= 2 && s.charAt(len - 2) == '=' then pad += 1
            val outLen                          = (len / 4) * 3 - pad
            val out                             = new Array[Byte](outLen)
            var i                               = 0
            var o                               = 0
            var error: IllegalArgumentException = null
            while i < len && (error eq null) do
                val c0 = s.charAt(i)
                val c1 = s.charAt(i + 1)
                val c2 = s.charAt(i + 2)
                val c3 = s.charAt(i + 3)
                val v0 = lookup(c0)
                val v1 = lookup(c1)
                val v2 = if c2 == '=' then 0 else lookup(c2)
                val v3 = if c3 == '=' then 0 else lookup(c3)
                if v0 < 0 || v1 < 0 || v2 < 0 || v3 < 0 then
                    error = new IllegalArgumentException(s"Illegal Base64 character in input at offset $i")
                else
                    val b0 = (v0 << 2) | (v1 >>> 4)
                    out(o) = b0.toByte
                    if c2 != '=' then
                        val b1 = ((v1 & 0x0f) << 4) | (v2 >>> 2)
                        out(o + 1) = b1.toByte
                        if c3 != '=' then
                            val b2 = ((v2 & 0x03) << 6) | v3
                            out(o + 2) = b2.toByte
                    end if
                    o += 3 - (if c2 == '=' then 2 else if c3 == '=' then 1 else 0)
                    i += 4
                end if
            end while
            if error ne null then Result.fail(error)
            else Result.succeed(Span.fromUnsafe(out))
        end if
    end decode

    /** Decodes a Base64-encoded string, throwing `IllegalArgumentException` on malformed input.
      *
      * Convenience wrapper for callers that have already validated the input (e.g. it came from the same encoder, or from a CDP reply that
      * is part of the protocol contract). Use [[decode]] when the input source is untrusted.
      */
    def decodeOrThrow(string: String): Span[Byte] =
        decode(string) match
            case Result.Success(value) => value
            case Result.Failure(ex)    => throw ex
            case Result.Panic(ex)      => throw ex

    private def lookup(c: Char): Int =
        val i = c.toInt
        if i < 0 || i >= Decode.length then -1
        else Decode(i)
    end lookup

end Base64
