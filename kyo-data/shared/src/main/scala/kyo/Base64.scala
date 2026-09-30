package kyo

/** Cross-platform Base64 (RFC 4648) encoder/decoder.
  *
  * Provides a pure-Scala implementation of Base64 encoding and decoding so kyo modules can avoid depending on `java.util.Base64` from
  * `shared/src/main`. Two forms are supported, and neither inserts line breaks:
  *
  *   - `encode` and `decode`: the standard alphabet (`A-Z a-z 0-9 + /`) with `=` padding (RFC 4648 section 4).
  *   - `encodeUrl` and `decodeUrl`: the URL and filename safe alphabet (`A-Z a-z 0-9 - _`) without padding (RFC 4648 section 5), the
  *     form JSON Web Tokens and JSON Web Keys carry.
  *
  * Both decoders reject characters outside their alphabet, including the other form's `+ /` or `- _`, and padding anywhere other than
  * the end of padded input.
  *
  * IMPORTANT: `decodeUrl` accepts only the canonical encoding. The bits left over after the last whole byte must be zero, so each byte
  * sequence has exactly one accepted encoding, as token and key comparisons require. `decode` ignores those bits, which RFC 4648 section
  * 3.5 permits.
  *
  * The implementation cross-compiles to JVM, Scala.js, and Scala Native without relying on the `java.util.Base64` shape exported by each
  * platform's javalib stub.
  *
  * A malformed input is a [[Base64.Failure]] value naming what was found and where.
  */
object Base64:

    /** Why a Base64 input did not decode: one case per shape the decoders detect, with the position or count that identifies it. The
      * standard and URL-safe decoders share the cases; which alphabet a character is outside of follows from the decoder that was
      * called, and the canonical-only decoder is the one that can report [[Failure.NonCanonicalTail]].
      *
      * A failure carries an offset or a length only, never the text, so it can be logged or mapped to a caller's own failure without
      * leaking a token or a key; [[Failure.message]] renders it for a caller's own exception.
      *
      * @see
      *   [[decode]] and [[decodeUrl]], which produce it
      * @see
      *   [[Hex.Failure]], the counterpart for hex text
      */
    enum Failure derives CanEqual:

        /** A character outside the alphabet at `offset`, counting from 0. */
        case IllegalCharacter(offset: Int)

        /** A `=` at `offset` where none may stand: before the final quartet, after a data character of the final quartet, or anywhere
          * in unpadded input.
          */
        case UnexpectedPadding(offset: Int)

        /** A padded input whose `length` is not a multiple of 4. */
        case BadLength(length: Int)

        /** An input of `dataLength` data characters, 1 modulo 4, which no byte sequence encodes to. */
        case DanglingCharacter(dataLength: Int)

        /** A canonical-only decoder found nonzero bits after the last whole byte. */
        case NonCanonicalTail

        /** The text a caller's own exception carries for this failure. */
        def message: String = this match
            case IllegalCharacter(offset)      => s"Illegal Base64 character in input at offset $offset"
            case UnexpectedPadding(offset)     => s"Unexpected Base64 padding in input at offset $offset"
            case BadLength(length)             => s"Base64 input length must be a multiple of 4 (got $length)"
            case DanglingCharacter(dataLength) => s"Base64 input of $dataLength data characters encodes no byte sequence"
            case NonCanonicalTail              => "Base64 input has nonzero bits after its last byte"

    end Failure

    private val StandardAlphabet: Array[Char] =
        ("ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/").toCharArray

    private val UrlAlphabet: Array[Char] =
        ("ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_").toCharArray

    private val StandardDecode: Array[Int] = decodeTable(StandardAlphabet)

    private val UrlDecode: Array[Int] = decodeTable(UrlAlphabet)

    /** Encodes a `Span[Byte]` to its Base64 string representation using the standard alphabet with `=` padding. */
    def encode(bytes: Span[Byte]): String =
        encodeWith(bytes, StandardAlphabet, padded = true)

    /** Decodes a Base64-encoded string into a `Span[Byte]`.
      *
      * Returns a `Result.Failure` on malformed input (non-alphabet character, length not divisible by 4, padding in the wrong place). On
      * success the returned `Span` owns a fresh array, so the caller may safely retain it.
      */
    def decode(string: String): Result[Failure, Span[Byte]] =
        decodeWith(string, StandardDecode, padded = true, canonical = false)

    /** Encodes a `Span[Byte]` with the URL and filename safe alphabet and no padding (RFC 4648 section 5). */
    def encodeUrl(bytes: Span[Byte]): String =
        encodeWith(bytes, UrlAlphabet, padded = false)

    /** Decodes unpadded base64url (RFC 4648 section 5) into a `Span[Byte]`.
      *
      * Returns a `Result.Failure` on a character outside the URL-safe alphabet (which includes `=`, `+` and `/`), on a length of 1 modulo
      * 4, which no byte sequence encodes to, and on nonzero bits after the last whole byte. On success the returned `Span` owns a fresh
      * array.
      */
    def decodeUrl(string: String): Result[Failure, Span[Byte]] =
        decodeWith(string, UrlDecode, padded = false, canonical = true)

    private def decodeTable(alphabet: Array[Char]): Array[Int] =
        val table = Array.fill[Int](128)(-1)
        alphabet.indices.foreach(i => table(alphabet(i).toInt) = i)
        table
    end decodeTable

    private def encodeWith(bytes: Span[Byte], alphabet: Array[Char], padded: Boolean): String =
        val len = bytes.size
        if len == 0 then ""
        else
            val rem    = len % 3
            val outLen = if padded || rem == 0 then ((len + 2) / 3) * 4 else (len / 3) * 4 + rem + 1
            val out    = new Array[Char](outLen)
            var i      = 0
            var o      = 0
            while i + 3 <= len do
                val b0 = bytes(i) & 0xff
                val b1 = bytes(i + 1) & 0xff
                val b2 = bytes(i + 2) & 0xff
                out(o) = alphabet(b0 >>> 2)
                out(o + 1) = alphabet(((b0 & 0x03) << 4) | (b1 >>> 4))
                out(o + 2) = alphabet(((b1 & 0x0f) << 2) | (b2 >>> 6))
                out(o + 3) = alphabet(b2 & 0x3f)
                i += 3
                o += 4
            end while
            if rem == 1 then
                val b0 = bytes(i) & 0xff
                out(o) = alphabet(b0 >>> 2)
                out(o + 1) = alphabet((b0 & 0x03) << 4)
                if padded then
                    out(o + 2) = '='
                    out(o + 3) = '='
            else if rem == 2 then
                val b0 = bytes(i) & 0xff
                val b1 = bytes(i + 1) & 0xff
                out(o) = alphabet(b0 >>> 2)
                out(o + 1) = alphabet(((b0 & 0x03) << 4) | (b1 >>> 4))
                out(o + 2) = alphabet((b1 & 0x0f) << 2)
                if padded then out(o + 3) = '='
            end if
            new String(out)
        end if
    end encodeWith

    private def decodeWith(
        string: String,
        table: Array[Int],
        padded: Boolean,
        canonical: Boolean
    ): Result[Failure, Span[Byte]] =
        val len = string.length
        if padded && len % 4 != 0 then Result.fail(Failure.BadLength(len))
        else
            val pad =
                if !padded || len == 0 || string.charAt(len - 1) != '=' then 0
                else if string.charAt(len - 2) == '=' then 2
                else 1
            val dataLen = len - pad
            val tail    = dataLen % 4
            if tail == 1 then Result.fail(Failure.DanglingCharacter(dataLen))
            else
                val out = new Array[Byte]((dataLen / 4) * 3 + (if tail == 0 then 0 else tail - 1))
                // An illegal character looks up as -1, so the OR of every value is negative exactly when the input holds one. This keeps
                // the loop free of per-character branches; the offset is searched for only on the failure path.
                var invalid = 0
                var i       = 0
                var o       = 0
                while i + 4 <= dataLen do
                    val v0 = lookup(table, string.charAt(i))
                    val v1 = lookup(table, string.charAt(i + 1))
                    val v2 = lookup(table, string.charAt(i + 2))
                    val v3 = lookup(table, string.charAt(i + 3))
                    invalid |= v0 | v1 | v2 | v3
                    out(o) = ((v0 << 2) | (v1 >>> 4)).toByte
                    out(o + 1) = (((v1 & 0x0f) << 4) | (v2 >>> 2)).toByte
                    out(o + 2) = (((v2 & 0x03) << 6) | v3).toByte
                    i += 4
                    o += 3
                end while
                var leftover = 0
                if tail >= 2 then
                    val v0 = lookup(table, string.charAt(i))
                    val v1 = lookup(table, string.charAt(i + 1))
                    invalid |= v0 | v1
                    out(o) = ((v0 << 2) | (v1 >>> 4)).toByte
                    if tail == 2 then leftover = v1 & 0x0f
                    else
                        val v2 = lookup(table, string.charAt(i + 2))
                        invalid |= v2
                        out(o + 1) = (((v1 & 0x0f) << 4) | (v2 >>> 2)).toByte
                        leftover = v2 & 0x03
                    end if
                end if
                if invalid < 0 then
                    val offset = string.indexWhere(c => lookup(table, c) < 0)
                    if string.charAt(offset) == '=' then Result.fail(Failure.UnexpectedPadding(offset))
                    else Result.fail(Failure.IllegalCharacter(offset))
                else if canonical && leftover != 0 then Result.fail(Failure.NonCanonicalTail)
                // Unsafe: the output array was allocated by this call and is held by nothing else.
                else Result.succeed(Span.fromUnsafe(out))
                end if
            end if
        end if
    end decodeWith

    private def lookup(table: Array[Int], c: Char): Int =
        val i = c.toInt
        if i >= table.length then -1
        else table(i)
    end lookup

end Base64
