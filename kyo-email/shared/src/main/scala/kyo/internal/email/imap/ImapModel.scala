package kyo.internal.email.imap

import kyo.*
import kyo.internal.Ascii
import kyo.internal.charset.Utf8
import kyo.internal.email.imap.ImapCodec.*
import kyo.internal.email.mime.DateCodec
import kyo.internal.email.mime.HeaderCodec
import kyo.internal.email.mime.MediaTypeDefaults

/** The public model read from IMAP's data forms, and search queries written as search keys. Every reader answers `Absent` for data that is
  * not the grammar or holds a value the model cannot represent; the session reports that as `Protocol`.
  */
private[kyo] object ImapModel:

    /** A summary's parts before its header section is parsed, which is effectful. */
    final case class SummaryFields(
        uid: Email.Uid,
        flags: Set[Email.Flag],
        internalDate: Instant,
        size: ByteSize,
        header: Span[Byte],
        structure: EmailImap.Part
    )

    def mailbox(item: Data.ListItem, utf8: Boolean): Maybe[EmailImap.Mailbox] =
        Email.MailboxName.fromWire(item.name, utf8).map { name =>
            EmailImap.Mailbox(name, item.delimiter, item.attributes.map(EmailImap.Mailbox.Attribute.fromWire).toSet)
        }

    def status(mailbox: Email.MailboxName, data: Data.MailboxStatus): Maybe[EmailImap.MailboxStatus] =
        def item(name: String): Maybe[Long] = Maybe.fromOption(data.items.find(_._1 == name)).map(_._2)
        for
            messages <- item("MESSAGES")
            unseen   <- item("UNSEEN")
            uidNext  <- item("UIDNEXT")
            validity <- item("UIDVALIDITY").flatMap(Email.UidValidity.read)
        yield EmailImap.MailboxStatus(mailbox, messages, unseen, uidNext, validity)
        end for
    end status

    def flags(value: Value): Maybe[Set[Email.Flag]] =
        value match
            case Value.Items(values) =>
                val atoms = values.collect { case Value.Atom(atom) => atom }
                if atoms.size != values.size || !atoms.forall(Email.Flag.isFlagText) then Absent
                else Present(atoms.flatMap(atom => Email.Flag.fromWire(atom).toList).toSet)
            case _ => Absent

    def part(value: Value)(using Frame): Maybe[EmailImap.Part] = node(value, Chunk.empty, message = true)

    def summaryFields(mailbox: Email.MailboxName, validity: Email.UidValidity, items: Chunk[(String, Value)])(using
        Frame
    ): Maybe[SummaryFields] =
        def item(name: String): Maybe[Value] = Maybe.fromOption(items.find(_._1 == name)).map(_._2)
        for
            uid       <- item("UID").flatMap(number).flatMap(Email.Uid.read(mailbox, validity, _))
            flags     <- item("FLAGS").flatMap(flags)
            received  <- item("INTERNALDATE").flatMap(text).flatMap(internalDate)
            size      <- item("RFC822.SIZE").flatMap(number)
            header    <- item("BODY[HEADER]").flatMap(octets)
            structure <- item("BODYSTRUCTURE").flatMap(part)
        yield SummaryFields(uid, flags, received, size.bytes, Span.from(header.toArray), structure)
        end for
    end summaryFields

    /** What `EmailImap.run` delivers for a `UID FETCH` of `(UID FLAGS INTERNALDATE RFC822.SIZE BODY.PEEK[])`: `Received`, or `Unreadable`
      * naming the first of the date, the flags and the size it cannot read. Absent when the UID or the body cannot be read, which leaves no
      * message to name.
      */
    def received(mailbox: Email.MailboxName, validity: Email.UidValidity, items: Chunk[(String, Value)]): Maybe[EmailImap.InboxEvent] =
        import EmailImap.InboxEvent.Unreadable.Item
        def item(name: String): Maybe[Value] = Maybe.fromOption(items.find(_._1 == name)).map(_._2)
        for
            uid <- item("UID").flatMap(number).flatMap(Email.Uid.read(mailbox, validity, _))
            raw <- item("BODY[]").flatMap(octets)
        yield
            def unreadable(what: Item): EmailImap.InboxEvent = EmailImap.InboxEvent.Unreadable(uid, what)
            item("INTERNALDATE").flatMap(text).flatMap(internalDate).fold(unreadable(Item.InternalDate)) { date =>
                item("FLAGS").flatMap(flags).fold(unreadable(Item.Flags)) { read =>
                    item("RFC822.SIZE").flatMap(number).fold(unreadable(Item.Size)) { size =>
                        EmailImap.InboxEvent.Received(uid, read, date, size.bytes, Span.from(raw.toArray))
                    }
                }
            }
        end for
    end received

    /** The search keys of `query`, and whether any text in them is not ASCII, which needs `CHARSET UTF-8`. */
    def search(query: EmailImap.Search, nonSynchronizing: String => Boolean): (Chunk[Part], Boolean) =
        val parts = keys(query, nonSynchronizing)
        (parts, parts.exists(_.isInstanceOf[Part.Literal]))

    // A part numbered `number`. The body of a message (the top level, or an attached message) that has a single part is that message's
    // part 1, so `message` extends its number; a multipart takes the number its message or parent gives it.
    private def node(value: Value, number: Chunk[Int], message: Boolean)(using Frame): Maybe[EmailImap.Part] =
        value match
            case Value.Items(values) =>
                val children = values.takeWhile(_.isInstanceOf[Value.Items])
                if children.nonEmpty then multipart(children, values.drop(children.size), number)
                else single(values, if message then number.append(1) else number)
            case _ => Absent

    private def multipart(children: Chunk[Value], rest: Chunk[Value], number: Chunk[Int])(using Frame): Maybe[EmailImap.Part] =
        val parts = children.zipWithIndex.map((child, index) => node(child, number.append(index + 1), message = false))
        for
            subType <- rest.headMaybe.flatMap(text)
            if parts.forall(_.nonEmpty)
            media <- mediaType("multipart", subType, at(rest, 1).map(pairs).getOrElse(Chunk.empty))
        yield
            val dsp = at(rest, 2).flatMap(disposition)
            EmailImap.Part(
                number,
                media,
                "7bit",
                0L.bytes,
                dsp.map(_.kind.label),
                Absent,
                Absent,
                parts.flatMap(_.toList)
            )
        end for
    end multipart

    private def single(values: Chunk[Value], path: Chunk[Int])(using Frame): Maybe[EmailImap.Part] =
        for
            mainType <- at(values, 0).flatMap(text)
            subType  <- at(values, 1).flatMap(text)
            encoding <- at(values, 5).flatMap(text)
            size     <- at(values, 6).flatMap(number)
            kind = Ascii.toLower(s"$mainType/$subType")
            // body-type-msg carries an envelope, the attached body and a line count before the extension data; body-type-text a line count.
            attached <- if kind == "message/rfc822" || kind == "message/global" then
                at(values, 8).flatMap(node(_, path, message = true)).map(Maybe(_))
            else Present(Absent)
            media <- mediaType(mainType, subType, at(values, 2).map(pairs).getOrElse(Chunk.empty))
        yield
            val extension = if attached.nonEmpty then 10 else if Ascii.equalsIgnoreCase(mainType, "text") then 8 else 7
            val dsp       = at(values, extension + 1).flatMap(disposition)
            val fileName  = dsp match
                case Present(d) => Maybe.fromOption(d.parameters.find(_._1 == "filename")).map(_._2)
                case Absent     => media.parameter("name")
            EmailImap.Part(
                path,
                media,
                Ascii.toLower(encoding),
                size.bytes,
                dsp.map(_.kind.label),
                fileName,
                at(values, 3).flatMap(text).flatMap(Email.ContentId.read),
                Chunk.from(attached.toList)
            )
        end for
    end single

    /** The type and its parameters read as a Content-Type field, so RFC 2231 and encoded words are decoded, and repeated or unreadable
      * parameters dropped, as in a message's own header. A type or subtype that is not a token makes a field that does not parse, which is
      * `text/plain; charset=us-ascii` (RFC 2045 section 5.2), the type `Email.Message.parse` gives the same part. `Absent` only when that
      * default cannot be built, which makes the part unreadable.
      */
    private def mediaType(mainType: String, subType: String, parameters: Chunk[(String, String)])(using Frame): Maybe[Email.MediaType] =
        val value = s"$mainType/$subType" + parameters.map((name, v) => s"; $name=${written(v)}").mkString
        HeaderCodec.contentType(HeaderCodec.Field("Content-Type", value, HeaderCodec.ValueText.Utf8))
            .orElse(MediaTypeDefaults.built.toMaybe.map(_.plainText))
    end mediaType

    private def disposition(value: Value)(using Frame): Maybe[HeaderCodec.Disposition] =
        value match
            case Value.Items(values) if values.size >= 1 =>
                values.headMaybe.flatMap(text).flatMap { kind =>
                    val parameters = at(values, 1).map(pairs).getOrElse(Chunk.empty)
                    val field      = kind + parameters.map((name, v) => s"; $name=${written(v)}").mkString
                    HeaderCodec.contentDisposition(HeaderCodec.Field("Content-Disposition", field, HeaderCodec.ValueText.Utf8))
                }
            case _ => Absent

    // A parameter value as a token when it is one, which RFC 2231's extended values are, else as a quoted string.
    private def written(value: String): String =
        if value.nonEmpty && value.forall(c => c > ' ' && c < '\u007f' && "()<>@,;:\\\"/[]?=".indexOf(c.toInt) < 0) then value
        else "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    private def pairs(value: Value): Chunk[(String, String)] =
        value match
            case Value.Items(values) =>
                Chunk.from(values.grouped(2).flatMap {
                    case Seq(name, v) => (for n <- text(name); t <- text(v) yield (n, t)).toList
                    case _            => Nil
                }.toSeq)
            case _ => Chunk.empty

    private def at(values: Chunk[Value], index: Int): Maybe[Value] =
        if index < values.size then Present(values(index)) else Absent

    private def text(value: Value): Maybe[String] =
        value match
            case Value.Str(octets) => Present(Utf8.decode(Span.from(octets.toArray)))
            case Value.Atom(atom)  => Present(atom)
            case _                 => Absent

    private def octets(value: Value): Maybe[Chunk[Byte]] =
        value match
            case Value.Str(octets) => Present(octets)
            case Value.Nil         => Present(Chunk.empty)
            case _                 => Absent

    private def number(value: Value): Maybe[Long] =
        value match
            case Value.Number(n) => Present(n)
            case _               => Absent

    private def keys(query: EmailImap.Search, nonSynchronizing: String => Boolean): Chunk[Part] =
        def string(key: String, value: String): Chunk[Part] = Chunk(Part.Text(s"$key ")).concat(astring(value, nonSynchronizing(value)))
        query match
            case EmailImap.Search.All                 => Chunk(Part.Text("ALL"))
            case EmailImap.Search.Seen                => Chunk(Part.Text("SEEN"))
            case EmailImap.Search.Unseen              => Chunk(Part.Text("UNSEEN"))
            case EmailImap.Search.Flagged             => Chunk(Part.Text("FLAGGED"))
            case EmailImap.Search.Unflagged           => Chunk(Part.Text("UNFLAGGED"))
            case EmailImap.Search.Answered            => Chunk(Part.Text("ANSWERED"))
            case EmailImap.Search.Deleted             => Chunk(Part.Text("DELETED"))
            case EmailImap.Search.Draft               => Chunk(Part.Text("DRAFT"))
            case EmailImap.Search.Keyword(flag)       => Chunk(Part.Text(s"KEYWORD ${flag.wire}"))
            case EmailImap.Search.From(value)         => string("FROM", value)
            case EmailImap.Search.To(value)           => string("TO", value)
            case EmailImap.Search.Cc(value)           => string("CC", value)
            case EmailImap.Search.Subject(value)      => string("SUBJECT", value)
            case EmailImap.Search.Body(value)         => string("BODY", value)
            case EmailImap.Search.Text(value)         => string("TEXT", value)
            case EmailImap.Search.Header(name, value) =>
                Chunk(Part.Text("HEADER ")).concat(astring(name, false)).append(Part.Text(" ")).concat(astring(
                    value,
                    nonSynchronizing(value)
                ))
            case EmailImap.Search.Since(instant)    => Chunk(Part.Text(s"SINCE ${day(instant)}"))
            case EmailImap.Search.Before(instant)   => Chunk(Part.Text(s"BEFORE ${day(instant)}"))
            case EmailImap.Search.Larger(size)      => Chunk(Part.Text(s"LARGER ${size.toBytes}"))
            case EmailImap.Search.Smaller(size)     => Chunk(Part.Text(s"SMALLER ${size.toBytes}"))
            case EmailImap.Search.And(first, rest*) =>
                if rest.isEmpty then keys(first, nonSynchronizing)
                else
                    val all = Chunk(first).concat(Chunk.from(rest)).map(keys(_, nonSynchronizing))
                    Chunk(
                        Part.Text("(")
                    ).concat(all.head).concat(all.tail.flatMap(k => Chunk(Part.Text(" ")).concat(k))).append(Part.Text(")"))
            case EmailImap.Search.Or(left, right) =>
                Chunk(Part.Text("OR ")).concat(keys(left, nonSynchronizing)).append(Part.Text(" ")).concat(keys(right, nonSynchronizing))
            case EmailImap.Search.Not(inner) => Chunk(Part.Text("NOT ")).concat(keys(inner, nonSynchronizing))
        end match
    end keys

    // IMAP's `date`: the day of the month without padding, the month's name, the year (RFC 9051 section 9).
    private def day(instant: Instant): String =
        val (year, month, dayOfMonth) = DateCodec.civilFromDays(Math.floorDiv(DateCodec.epochSecondOf(instant), 86400L))
        s"$dayOfMonth-${DateCodec.MonthNames(month - 1)}-$year"

end ImapModel
