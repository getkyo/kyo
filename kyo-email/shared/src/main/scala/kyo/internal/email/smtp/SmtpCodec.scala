package kyo.internal.email.smtp

import kyo.*
import kyo.internal.Ascii
import kyo.internal.charset.Utf8
import kyo.internal.email.net.LineConnection
import scala.annotation.tailrec

/** SMTP's wire forms (RFC 5321): the replies a server sends, the extensions its `EHLO` reply offers, and the data section a message is
  * sent as.
  *
  * A reply is read a line at a time through `next`, which bounds it by `ReplyLimit`: SMTP replies have no literals, so the bound is what
  * stops a server that never ends a reply, as `maxResponseLength` does for IMAP.
  */
private[kyo] object SmtpCodec:

    /** The most octets one reply may take, its lines and their line ends together. */
    inline val ReplyLimit = 1024 * 1024

    /** A whole reply: its code and each line's text after the code and its separator, as sent. */
    final case class Reply(code: Int, lines: Chunk[String]) derives CanEqual

    /** A reply read so far: its code, its lines and the octets they took with their line ends. */
    final case class Partial(code: Int, lines: Chunk[String], octets: Int) derives CanEqual

    enum Step derives CanEqual:
        case More(partial: Partial)
        case Complete(reply: Reply)

        /** The line that could not be added, as `LineConnection.shown` writes it; the session redacts it before cutting it. */
        case Malformed(line: String)
    end Step

    /** The octets the next line of a reply may take, without its line end. */
    def remaining(partial: Maybe[Partial]): Int =
        ReplyLimit - 2 - partial.fold(0)(_.octets)

    /** `line` added to the reply read so far. A line is `ddd`, `ddd text` (the last) or `ddd-text` (one more follows), RFC 5321 section
      * 4.2; a line in any other form, a code other than the first line's, or a reply past `ReplyLimit` is `Malformed`.
      */
    def next(partial: Maybe[Partial], line: Span[Byte]): Step =
        val octets = partial.fold(0)(_.octets) + line.size + 2
        codeOf(line) match
            case Present(code) if octets <= ReplyLimit && partial.forall(_.code == code) =>
                val text  = if line.size > 4 then Utf8.decode(line.slice(4, line.size)) else ""
                val lines = partial.fold(Chunk.empty[String])(_.lines).append(text)
                if line.size > 3 && line(3) == '-'.toByte then Step.More(Partial(code, lines, octets))
                else Step.Complete(Reply(code, lines))
            case _ => Step.Malformed(LineConnection.shown(line))
        end match
    end next

    // Reply-code = %x32-35 %x30-35 %x30-39, then the end of the line, a space or a hyphen.
    private def codeOf(line: Span[Byte]): Maybe[Int] =
        def digit(i: Int, low: Char, high: Char): Boolean = line(i) >= low.toByte && line(i) <= high.toByte
        if line.size < 3 || !digit(0, '2', '5') || !digit(1, '0', '5') || !digit(2, '0', '9') then Absent
        else if line.size > 3 && line(3) != ' '.toByte && line(3) != '-'.toByte then Absent
        else Present((line(0) - '0') * 100 + (line(1) - '0') * 10 + (line(2) - '0'))
    end codeOf

    /** The enhanced status code a line's text begins with (RFC 3463 section 2, RFC 2034 section 4) and the text after it, when its class is
      * the reply code's first digit; a text in any other form holds none.
      */
    def enhanced(code: Int, text: String)(using Frame): Maybe[(EmailSend.EnhancedStatusCode, String)] =
        val end = text.indexOf(' ') match
            case -1 => text.length
            case i  => i
        val fields = text.substring(0, end).split("\\.", -1)
        val digits = (field: String, most: Int) => field.nonEmpty && field.length <= most && field.forall(Ascii.isDigit)
        if fields.length != 3 || !digits(fields(0), 1) || !digits(fields(1), 3) || !digits(fields(2), 3) then Absent
        else
            val digit = fields(0).head - '0'
            Maybe.fromOption(EmailSend.EnhancedStatusCode.StatusClass.values.find(_.digit == digit)).filter(_.digit == code / 100).flatMap {
                found =>
                    EmailSend.EnhancedStatusCode.init(found, fields(1).toInt, fields(2).toInt).toMaybe
                        .map(enhanced => (enhanced, text.substring(Math.min(end + 1, text.length))))
            }
        end if
    end enhanced

    /** The service extensions of an `EHLO` reply (RFC 5321 section 4.1.1.1): each line after the first, its keyword uppercased with its
      * parameters.
      */
    final case class Extensions(entries: Chunk[(String, Chunk[String])]) derives CanEqual:
        def offers(keyword: String): Boolean = entries.exists(_._1 == keyword)

        def parameters(keyword: String): Maybe[Chunk[String]] = Maybe.fromOption(entries.find(_._1 == keyword)).map(_._2)
    end Extensions

    def extensions(reply: Reply): Extensions =
        Extensions(reply.lines.drop(1).flatMap { line =>
            val words = Chunk.from(line.split(' ').filter(_.nonEmpty))
            words.headMaybe.toChunk.map { first =>
                // Servers that predate RFC 2554's final form still announce `AUTH=PLAIN LOGIN`; its mechanisms are the same.
                val (keyword, rest) = first.indexOf('=') match
                    case -1 => (first, words.drop(1))
                    case i  => (first.substring(0, i), Chunk(first.substring(i + 1)).filter(_.nonEmpty).concat(words.drop(1)))
                (Ascii.toUpper(keyword), rest)
            }
        })

    /** `message` as `DATA` sends it (RFC 5321 section 4.5.2): a `.` added before every line that begins with one, a CRLF after the last
      * line when it has none, then the `.` line that ends the data. `message` holds CR and LF only as CRLF, as the renderer writes it.
      */
    def dataSection(message: Span[Byte]): Span[Byte] =
        // The message is split before each line-initial dot, so that dot is written twice, and the pieces are joined once.
        @tailrec def pieces(i: Int, from: Int, acc: Chunk[Span[Byte]]): Chunk[Span[Byte]] =
            if i == message.size then acc.append(message.slice(from, i))
            else if message(i) == '.'.toByte && (i == 0 || message(i - 1) == '\n'.toByte) then
                pieces(i + 1, i, acc.append(message.slice(from, i)).append(Dot))
            else pieces(i + 1, from, acc)
        val ended = message.isEmpty || message(message.size - 1) == '\n'.toByte
        Span.concat((pieces(0, 0, Chunk.empty) ++ (if ended then Chunk.empty else Chunk(Crlf)) :+ End).toSeq*)
    end dataSection

    private val Dot  = Utf8.encode(".")
    private val Crlf = Utf8.encode("\r\n")
    private val End  = Utf8.encode(".\r\n")

end SmtpCodec
