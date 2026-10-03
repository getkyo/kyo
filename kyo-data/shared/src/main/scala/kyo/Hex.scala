package kyo

import scala.annotation.tailrec

/** Hexadecimal text for bytes: lowercase on encode, either case on decode.
  *
  * Platforms send digests, signatures and public keys as hex (a webhook signature header, an interaction's public key), and a rendered
  * byte column reads as hex. The encoder emits lowercase only, so equal bytes always render as equal text. Decoding fails with a
  * [[Hex.Failure]] naming an odd length or the offset of the first character outside `0-9 a-f A-F`.
  *
  * Note: neither direction is constant time. Use them on public values, such as a received signature, or on bytes that are then compared
  * in constant time.
  *
  * @see
  *   [[Hex.Failure]], what [[decode]] refuses with
  * @see
  *   [[Base64]], the other text encoding of bytes
  * @see
  *   [[Span]], the byte row both directions use
  */
object Hex:

    /** Why hex text did not decode: an odd number of characters, or a character outside `0-9 a-f A-F` at `offset`. The length is
      * checked first, so text that is both odd and malformed reports its length; a character failure names the first offending offset,
      * counting from 0. A failure carries the count or the offset only, never the text, and [[Failure.message]] renders it for a thrown
      * form.
      *
      * Decoding stops at the first failure, so a caller that wants every bad position has to scan the text itself; a webhook that
      * receives a malformed signature needs only to refuse it.
      *
      * @see
      *   [[decode]], which produces it
      * @see
      *   [[Base64.Failure]], the counterpart for base64 text
      * @see
      *   [[Result]], the row it comes back in
      */
    enum Failure derives CanEqual:

        /** `length` characters, which no byte sequence spells. */
        case OddLength(length: Int)

        /** A character outside the hex alphabet at `offset`, counting from 0. */
        case IllegalCharacter(offset: Int)

        /** The text a thrown form of this failure would carry. */
        def message: String = this match
            case OddLength(length)        => s"Hex input length must be even (got $length)"
            case IllegalCharacter(offset) => s"Illegal hex character in input at offset $offset"

    end Failure

    private val Digits: Array[Char] = "0123456789abcdef".toCharArray

    /** `bytes` as two lowercase digits per byte. */
    def encode(bytes: Span[Byte]): String =
        // Unsafe: the array is only read.
        encodeArray(bytes.toArrayUnsafe)

    /** The bytes `text` spells, or why it spells none. */
    def decode(text: String): Result[Failure, Span[Byte]] =
        if text.length % 2 != 0 then Result.fail(Failure.OddLength(text.length))
        else
            val out = new Array[Byte](text.length / 2)

            @tailrec def loop(i: Int): Int =
                if i == out.length then -1
                else
                    val high = digit(text.charAt(i * 2))
                    val low  = digit(text.charAt(i * 2 + 1))
                    if (high | low) < 0 then if high < 0 then i * 2 else i * 2 + 1
                    else
                        out(i) = ((high << 4) | low).toByte
                        loop(i + 1)
                    end if
            val offset = loop(0)
            // Unsafe: the array is fresh and held by nothing else.
            if offset < 0 then Result.succeed(Span.fromUnsafe(out)) else Result.fail(Failure.IllegalCharacter(offset))
    end decode

    private[kyo] def encodeArray(bytes: Array[Byte]): String =
        val out = new Array[Char](bytes.length * 2)

        @tailrec def loop(i: Int): Unit =
            if i < bytes.length then
                val v = bytes(i) & 0xff
                out(i * 2) = Digits(v >>> 4)
                out(i * 2 + 1) = Digits(v & 0x0f)
                loop(i + 1)
        loop(0)
        new String(out)
    end encodeArray

    private def digit(c: Char): Int =
        if c >= '0' && c <= '9' then c - '0'
        else if c >= 'a' && c <= 'f' then c - 'a' + 10
        else if c >= 'A' && c <= 'F' then c - 'A' + 10
        else -1

end Hex
