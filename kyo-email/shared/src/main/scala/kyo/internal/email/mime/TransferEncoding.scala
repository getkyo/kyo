package kyo.internal.email.mime

import kyo.*
import kyo.internal.Ascii

/** The content transfer encodings of RFC 2045 section 6: the identity encodings `7bit`, `8bit` and `binary`, `quoted-printable`, and
  * `base64`, decoding and encoding.
  *
  * Decoding is total and never fails, since a MIME part must be readable whatever a transport did to it. Base64 ignores every character
  * outside its alphabet (RFC 2045 section 6.8), and a `=` ends a segment rather than the data, so padding in the middle of a body splits it
  * into segments instead of losing what follows. A segment whose alphabet characters number 4k+1 carries six bits that cannot complete an
  * octet: the character is dropped and [[Decoded]] reports it, so one decoding serves both a parser that accepts the rest and a part fetch
  * that must fail. Quoted-printable decodes `=XX` in either case, treats `=` at the end of a line (and of the content) as a soft line
  * break, keeps any other `=` literally, and deletes trailing white space (RFC 2045 section 6.7); a hard line break decodes to CRLF.
  *
  * Base64 goes through kyo-data's [[kyo.Base64]] in pieces of [[PieceSize]] characters, each already stripped to the alphabet and padded
  * canonically, so no string larger than one piece is built whatever the size of the content.
  *
  * Encoding writes base64 in lines of 76 characters and quoted-printable by the five rules of RFC 2045 section 6.7, with `From ` and a
  * leading `.` at the start of a line encoded (RFC 2049 section 3 item 8). Lines are separated by CRLF, and the output does not end with
  * a line break, since in a MIME part the line end before the next delimiter belongs to the delimiter.
  */
private[kyo] object TransferEncoding:

    /** A transfer encoding RFC 2045 section 6.1 names, with its label. */
    enum Kind(val label: String) derives CanEqual:
        case SevenBit        extends Kind("7bit")
        case EightBit        extends Kind("8bit")
        case Binary          extends Kind("binary")
        case QuotedPrintable extends Kind("quoted-printable")
        case Base64          extends Kind("base64")
    end Kind

    /** The octets of a decoded body. `truncated` is true when a base64 segment ended with a character that could not complete an octet,
      * which was dropped.
      */
    final case class Decoded(content: Span[Byte], truncated: Boolean) derives CanEqual:

        override def equals(other: Any): Boolean =
            other match
                case that: Decoded => that.truncated == truncated && that.content.is(content)
                case _             => false

        override def hashCode: Int = (content.hash, truncated).##

    end Decoded

    /** The number of base64 characters handed to [[kyo.Base64.decode]] at once: a multiple of 4, so a piece never splits a quantum. */
    inline val PieceSize = 65536

    /** The encoding a `Content-Transfer-Encoding` value names, compared over US-ASCII only (RFC 2045 section 6.1: "These values are not
      * case sensitive"), or `Absent` for an encoding RFC 2045 does not define. Spaces and tabs around the label are ignored; any other
      * character, a control character included, is part of the label.
      */
    def resolve(label: String): Maybe[Kind] =
        var start = 0
        var end   = label.length
        while start < end && isWhiteSpace(label.charAt(start)) do start += 1
        while end > start && isWhiteSpace(label.charAt(end - 1)) do end -= 1
        val trimmed = label.substring(start, end)
        Maybe.fromOption(Kind.values.find(kind => Ascii.equalsIgnoreCase(kind.label, trimmed)))
    end resolve

    /** Decodes `input` from `from` until `until` in `kind`. The identity encodings copy the range. */
    def decode(kind: Kind, input: Span[Byte], from: Int, until: Int)(using Frame): Result[PieceRefused, Decoded] =
        kind match
            case Kind.Base64          => decodeBase64(input, from, until)
            case Kind.QuotedPrintable => Result.succeed(Decoded(decodeQuotedPrintable(input, from, until), truncated = false))
            case Kind.SevenBit | Kind.EightBit | Kind.Binary => Result.succeed(Decoded(input.slice(from, until), truncated = false))

    /** Encodes `content` in `kind`. The identity encodings return it as it is. */
    def encode(kind: Kind, content: Span[Byte]): Span[Byte] =
        kind match
            case Kind.Base64                                 => encodeBase64(content)
            case Kind.QuotedPrintable                        => encodeQuotedPrintable(content)
            case Kind.SevenBit | Kind.EightBit | Kind.Binary => content

    /** A piece that [[decodeBase64]] built and kyo-data's [[kyo.Base64]] refused. A piece holds alphabet characters only, its length is a
      * multiple of 4, and `=` appears only as the last one or two characters of its final group, so `Base64.decode` cannot refuse it while
      * it ignores nonzero bits after the last octet (`QR==`), as RFC 4648 section 3.5 permits. This failure means that no longer holds.
      */
    final case class PieceRefused(pieceLength: Int, reason: Base64.Failure | Throwable)(using Frame)
        extends KyoException(
            s"kyo.Base64 refused a canonical piece of $pieceLength characters",
            reason match
                case cause: Throwable        => cause
                case failure: Base64.Failure => failure.toString
        )

    /** A refused piece as data, for a caller that drops the failure and so has no frame to build [[PieceRefused]] with. */
    final case class Refusal(pieceLength: Int, reason: Base64.Failure | Throwable)

    def decodeBase64(input: Span[Byte], from: Int, until: Int)(using Frame): Result[PieceRefused, Decoded] =
        decodeBase64Pieces(input, from, until).mapFailure(refusal => PieceRefused(refusal.pieceLength, refusal.reason))

    def decodeBase64Pieces(input: Span[Byte], from: Int, until: Int): Result[Refusal, Decoded] =
        val out                              = new Array[Byte](base64DecodedSize(input, from, until))
        val piece                            = new Array[Char](PieceSize + 2)
        var filled                           = 0
        var written                          = 0
        var truncated                        = false
        var refused                          = Maybe.empty[Refusal]
        def flush(segmentEnd: Boolean): Unit =
            if segmentEnd then
                (filled % 4) match
                    case 1 =>
                        truncated = true
                        filled -= 1
                    case 2 =>
                        piece(filled) = '='
                        piece(filled + 1) = '='
                        filled += 2
                    case 3 =>
                        piece(filled) = '='
                        filled += 1
                    case _ => ()
                end match
            end if
            if filled > 0 then
                Base64.decode(new String(piece, 0, filled)) match
                    case Result.Success(decoded) =>
                        discard(decoded.copyToArray(out, written))
                        written += decoded.size
                    case Result.Failure(cause) => refused = Present(Refusal(filled, cause))
                    case Result.Panic(cause)   => refused = Present(Refusal(filled, cause))
                end match
                filled = 0
            end if
        end flush
        var i = from
        while i < until && refused.isEmpty do
            val b = input(i) & 0xff
            if isBase64Alphabet(b) then
                piece(filled) = b.toChar
                filled += 1
                if filled == PieceSize then flush(segmentEnd = false)
            else if b == '=' then flush(segmentEnd = true)
            end if
            i += 1
        end while
        if refused.isEmpty then flush(segmentEnd = true)
        refused match
            case Present(failure) => Result.fail(failure)
            // Unsafe: `out` is local and fully written; only the span escapes.
            case Absent => Result.succeed(Decoded(Span.fromUnsafe(out), truncated))
        end match
    end decodeBase64Pieces

    // The octets decodeBase64 writes: per segment of n alphabet characters, 3 per whole group of 4, then 1 or 2 for a tail of 2 or 3,
    // and none for a tail of 1, whose character is dropped.
    private def base64DecodedSize(input: Span[Byte], from: Int, until: Int): Int =
        var total   = 0
        var segment = 0
        var i       = from
        while i < until do
            val b = input(i) & 0xff
            if isBase64Alphabet(b) then segment += 1
            else if b == '=' then
                total += segment / 4 * 3 + math.max(0, segment % 4 - 1)
                segment = 0
            end if
            i += 1
        end while
        total + segment / 4 * 3 + math.max(0, segment % 4 - 1)
    end base64DecodedSize

    /** Base64 in lines of 76 characters (RFC 2045 section 6.8), separated by CRLF, with no line break after the last. */
    def encodeBase64(content: Span[Byte]): Span[Byte] =
        val size = content.size
        if size == 0 then Span.empty[Byte]
        else
            val chars = (size + 2) / 3 * 4
            val lines = (chars + LineLength - 1) / LineLength
            val out   = new Array[Byte](chars + (lines - 1) * 2)
            var o     = 0
            var col   = 0
            var pos   = 0
            while pos < size do
                val batch   = math.min(size - pos, BatchOctets)
                val encoded = Base64.encode(content.slice(pos, pos + batch))
                var c       = 0
                while c < encoded.length do
                    if col == LineLength then
                        out(o) = '\r'
                        out(o + 1) = '\n'
                        o += 2
                        col = 0
                    end if
                    out(o) = encoded.charAt(c).toByte
                    o += 1
                    col += 1
                    c += 1
                end while
                pos += batch
            end while
            // Unsafe: `out` is local and fully written; only the span escapes.
            Span.fromUnsafe(out)
        end if
    end encodeBase64

    def decodeQuotedPrintable(input: Span[Byte], from: Int, until: Int): Span[Byte] =
        val out = new Array[Byte](quotedPrintableDecoding(input, from, until, Array.emptyByteArray))
        discard(quotedPrintableDecoding(input, from, until, out))
        // Unsafe: `out` is local and fully written; only the span escapes.
        Span.fromUnsafe(out)
    end decodeQuotedPrintable

    // Decodes into `out` and returns the octet count; with an empty `out` it only counts, which sizes `out` exactly for the second pass.
    private def quotedPrintableDecoding(input: Span[Byte], from: Int, until: Int, out: Array[Byte]): Int =
        val write = out.length > 0
        var o     = 0
        var i     = from
        while i < until do
            var lineEnd = i
            while lineEnd < until && input(lineEnd) != '\n' do lineEnd += 1
            val hasBreak = lineEnd < until
            var end      = if hasBreak && lineEnd > i && input(lineEnd - 1) == '\r' then lineEnd - 1 else lineEnd
            while end > i && isWhiteSpace(input(end - 1)) do end -= 1
            val soft = end > i && input(end - 1) == '='
            if soft then end -= 1
            var j = i
            while j < end do
                val b = input(j)
                if b == '=' && j + 2 < end && isHex(input(j + 1)) && isHex(input(j + 2)) then
                    if write then out(o) = ((hexValue(input(j + 1)) << 4) | hexValue(input(j + 2))).toByte
                    j += 3
                else
                    if write then out(o) = b
                    j += 1
                end if
                o += 1
            end while
            if hasBreak && !soft then
                if write then
                    out(o) = '\r'
                    out(o + 1) = '\n'
                o += 2
            end if
            i = if hasBreak then lineEnd + 1 else until
        end while
        o
    end quotedPrintableDecoding

    /** Quoted-printable by RFC 2045 section 6.7's five rules. A CRLF in `content` is a hard line break; any other CR or LF is `=0D` or
      * `=0A`. Encoded lines are at most 76 characters, soft breaks included, and never split an `=XX`.
      */
    def encodeQuotedPrintable(content: Span[Byte]): Span[Byte] =
        val out = new Array[Byte](quotedPrintableEncoding(content, Array.emptyByteArray))
        discard(quotedPrintableEncoding(content, out))
        // Unsafe: `out` is local and fully written; only the span escapes.
        Span.fromUnsafe(out)
    end encodeQuotedPrintable

    // Encodes into `out` and returns the octet count; with an empty `out` it only counts, which sizes `out` exactly for the second pass.
    private def quotedPrintableEncoding(content: Span[Byte], out: Array[Byte]): Int =
        val write = out.length > 0
        val size  = content.size
        var o     = 0
        var col   = 0
        var i     = 0
        while i < size do
            if isCrlf(content, i) then
                if write then
                    out(o) = '\r'
                    out(o + 1) = '\n'
                o += 2
                col = 0
                i += 2
            else
                val atLineEnd = i + 1 == size || isCrlf(content, i + 1)
                val limit     = if atLineEnd then LineLength else LineLength - 1
                if col + tokenLength(content, i, col, atLineEnd) > limit then
                    if write then
                        out(o) = '='
                        out(o + 1) = '\r'
                        out(o + 2) = '\n'
                    end if
                    o += 3
                    col = 0
                end if
                val length = tokenLength(content, i, col, atLineEnd)
                if write then
                    val b = content(i) & 0xff
                    if length == 1 then out(o) = b.toByte
                    else
                        out(o) = '='
                        out(o + 1) = HexDigits.charAt(b >>> 4).toByte
                        out(o + 2) = HexDigits.charAt(b & 0x0f).toByte
                    end if
                end if
                o += length
                col += length
                i += 1
            end if
        end while
        o
    end quotedPrintableEncoding

    // One octet's encoded length: 1 when rule 2 or 3 lets it stand for itself, 3 for `=XX`.
    private def tokenLength(content: Span[Byte], i: Int, col: Int, atLineEnd: Boolean): Int =
        val b       = content(i) & 0xff
        val literal =
            if b == '=' then false
            else if b == ' ' || b == '\t' then !atLineEnd
            else if b < 33 || b > 126 then false
            else if col == 0 && b == '.' then false
            else if col == 0 && b == 'F' then !startsWithFrom(content, i)
            else true
        if literal then 1 else 3
    end tokenLength

    private def startsWithFrom(content: Span[Byte], i: Int): Boolean =
        i + 4 < content.size && content(i + 1) == 'r' && content(i + 2) == 'o' && content(i + 3) == 'm' && content(i + 4) == ' '

    private def isCrlf(content: Span[Byte], i: Int): Boolean =
        i + 1 < content.size && content(i) == '\r' && content(i + 1) == '\n'

    private inline val LineLength = 76

    // 57 octets fill one 76-character line exactly, so a batch never leaves padding inside the output.
    private inline val BatchOctets = 57 * 1000

    private val HexDigits = "0123456789ABCDEF"

    private def isBase64Alphabet(b: Int): Boolean =
        (b >= 'A' && b <= 'Z') ||
            (b >= 'a' && b <= 'z') ||
            (b >= '0' && b <= '9') || b == '+' || b == '/'

    private def isWhiteSpace(b: Byte): Boolean = b == ' ' || b == '\t'

    private def isWhiteSpace(c: Char): Boolean = c == ' ' || c == '\t'

    private def isHex(b: Byte): Boolean = Ascii.isHexDigit(b.toChar)

    private def hexValue(b: Byte): Int =
        if b >= '0' && b <= '9' then b - '0'
        else if b >= 'a' && b <= 'f' then b - 'a' + 10
        else b - 'A' + 10

end TransferEncoding
