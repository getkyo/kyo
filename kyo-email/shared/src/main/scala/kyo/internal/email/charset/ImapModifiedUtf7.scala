package kyo.internal.email.charset

import kyo.*

/** IMAP's modified UTF-7 for mailbox names (RFC 3501 section 5.1.3): printable US-ASCII except `&` is itself, `&` is `&-`, and every
  * other character is UTF-16, most significant octet first, in a base64 run between `&` and `-` whose alphabet has `,` for `/`.
  *
  * A mailbox name that does not decode exactly names a different mailbox, so `decode` rejects what the RFC forbids instead of replacing
  * it, and accepts only the one canonical form `encode` produces: `encode(decode(name)) == name` for every accepted name, and
  * `decode(encode(text)) == text` for every text without unpaired surrogates. `encode` rejects an unpaired surrogate, which names no
  * Unicode string.
  */
private[kyo] object ImapModifiedUtf7:

    /** Why a name is not canonical modified UTF-7, with the index in the input where the problem starts; for a problem inside a run, the
      * index of its `&`.
      */
    enum Rejection derives CanEqual:
        /** A character outside 0x20 to 0x7E written directly. */
        case NotPrintableAscii(at: Int)

        /** `&` followed by neither a base64 character nor `-`. */
        case ShiftWithoutRun(at: Int)

        /** A run ended by a character other than `-`, or by the end of the name. */
        case UnterminatedRun(at: Int)

        /** A run that starts right where the previous run ended. */
        case NullShift(at: Int)

        /** A run whose bits after its last whole code unit are not 0, 2 or 4 zero bits. */
        case LeftoverBits(at: Int)

        /** A run that encodes a character from 0x20 to 0x7E. */
        case EncodedPrintableAscii(at: Int)

        /** An unpaired surrogate, in a run's code units or in the text to encode. */
        case UnpairedSurrogate(at: Int)
    end Rejection

    def decode(name: String): Result[Rejection, String] =
        val out = new java.lang.StringBuilder(name.length)
        @scala.annotation.tailrec
        def loop(i: Int, previousRunEnd: Int): Result[Rejection, String] =
            if i >= name.length then Result.succeed(out.toString)
            else
                val c = name.charAt(i)
                if c < 0x20 || c > 0x7e then Result.fail(Rejection.NotPrintableAscii(i))
                else if c != '&' then
                    discard(out.append(c))
                    loop(i + 1, previousRunEnd)
                else if i + 1 < name.length && name.charAt(i + 1) == '-' then
                    discard(out.append('&'))
                    loop(i + 2, previousRunEnd)
                else if i + 1 >= name.length || base64Value(name.charAt(i + 1)) < 0 then Result.fail(Rejection.ShiftWithoutRun(i))
                else if i == previousRunEnd then Result.fail(Rejection.NullShift(i))
                else
                    val end = runEnd(name, i + 1)
                    if end >= name.length || name.charAt(end) != '-' then Result.fail(Rejection.UnterminatedRun(i))
                    else
                        run(name, i, end) match
                            case Result.Success(text) =>
                                discard(out.append(text))
                                loop(end + 1, end + 1)
                            case failure => failure
                    end if
                end if
        loop(0, -1)
    end decode

    def encode(text: String): Result[Rejection, String] =
        val out = new java.lang.StringBuilder(text.length)
        @scala.annotation.tailrec
        def loop(i: Int): Result[Rejection, String] =
            if i >= text.length then Result.succeed(out.toString)
            else
                val c = text.charAt(i)
                if c >= 0x20 && c <= 0x7e then
                    discard(if c == '&' then out.append("&-") else out.append(c))
                    loop(i + 1)
                else
                    val end = directEnd(text, i)
                    unpairedSurrogate(text, i, end) match
                        case Present(at) => Result.fail(Rejection.UnpairedSurrogate(at))
                        case Absent      =>
                            discard(out.append('&').append(base64(text, i, end)).append('-'))
                            loop(end)
                    end match
                end if
        loop(0)
    end encode

    private val Alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+,"

    private def base64Value(c: Char): Int = if c > 0x7f then -1 else Alphabet.indexOf(c.toInt)

    private def runEnd(name: String, from: Int): Int =
        @scala.annotation.tailrec
        def loop(i: Int): Int = if i < name.length && base64Value(name.charAt(i)) >= 0 then loop(i + 1) else i
        loop(from)
    end runEnd

    // The first index from `from` holding a character that represents itself.
    private def directEnd(text: String, from: Int): Int =
        @scala.annotation.tailrec
        def loop(i: Int): Int = if i < text.length && (text.charAt(i) < 0x20 || text.charAt(i) > 0x7e) then loop(i + 1) else i
        loop(from)
    end directEnd

    /** The text of the run whose `&` is at `at` and whose `-` is at `end`. */
    private def run(name: String, at: Int, end: Int): Result[Rejection, String] =
        val units = new java.lang.StringBuilder
        @scala.annotation.tailrec
        def collect(i: Int, bits: Int, count: Int): Result[Rejection, String] =
            if i < end then
                val next = (bits << 6) | base64Value(name.charAt(i))
                if count + 6 >= 16 then
                    val left = count + 6 - 16
                    discard(units.append(((next >>> left) & 0xffff).toChar))
                    collect(i + 1, next & ((1 << left) - 1), left)
                else collect(i + 1, next, count + 6)
                end if
            else if count >= 6 || bits != 0 then Result.fail(Rejection.LeftoverBits(at))
            else Result.succeed(units.toString)
        collect(at + 1, 0, 0).flatMap { text =>
            if unpairedSurrogate(text, 0, text.length).isDefined then Result.fail(Rejection.UnpairedSurrogate(at))
            else if text.exists(c => c >= 0x20 && c <= 0x7e) then Result.fail(Rejection.EncodedPrintableAscii(at))
            else Result.succeed(text)
        }
    end run

    /** The index of the first unpaired surrogate in `text` from `from` until `until`. */
    private def unpairedSurrogate(text: String, from: Int, until: Int): Maybe[Int] =
        @scala.annotation.tailrec
        def loop(i: Int): Maybe[Int] =
            if i >= until then Absent
            else
                val c = text.charAt(i)
                if Character.isHighSurrogate(c) then
                    if i + 1 < until && Character.isLowSurrogate(text.charAt(i + 1)) then loop(i + 2) else Present(i)
                else if Character.isLowSurrogate(c) then Present(i)
                else loop(i + 1)
                end if
        loop(from)
    end unpairedSurrogate

    // UTF-16 code units, most significant octet first, in base64 with this alphabet and no padding.
    private def base64(text: String, from: Int, until: Int): String =
        val out = new java.lang.StringBuilder
        @scala.annotation.tailrec
        def loop(i: Int, bits: Int, count: Int): Unit =
            if count >= 6 then
                val left = count - 6
                discard(out.append(Alphabet.charAt((bits >>> left) & 0x3f)))
                loop(i, bits & ((1 << left) - 1), left)
            else if i < until then loop(i + 1, (bits << 16) | text.charAt(i), count + 16)
            else if count > 0 then discard(out.append(Alphabet.charAt((bits << (6 - count)) & 0x3f)))
        loop(from, 0, 0)
        out.toString
    end base64

end ImapModifiedUtf7
