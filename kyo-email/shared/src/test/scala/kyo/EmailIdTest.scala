package kyo

import kyo.Email.*
import kyo.EmailInvalidIdException.Violation
import kyo.EmailLiterals.*

class EmailIdTest extends kyo.test.Test[Any]:

    private val archive  = mailboxOf("Archive")
    private val validity = uidValidityOf(3857529045L)
    private val listed   = wire("Tom&Jerry", utf8 = false)

    private def wire(text: String, utf8: Boolean): MailboxName =
        MailboxName.fromWire(Chunk.from(text.getBytes("UTF-8")), utf8).getOrElse(throw new IllegalStateException(s"not read: $text"))

    private def violation[A](checked: Result[EmailInvalidIdException, A]): Maybe[Violation] =
        checked match
            case Result.Failure(invalid) => Present(invalid.violation)
            case _                       => Absent

    final private case class UidShape(mailbox: String, validity: Long, value: Long) derives Schema

    final private case class IdsShape(messageId: MessageId, contentId: ContentId, mailbox: MailboxName, validity: UidValidity, uid: Uid)
        derives Schema

    "MessageId" - {
        "strips the angle brackets and surrounding whitespace of a header value" in {
            assert(messageIdOf(" <1234@mail.example.com> ").value == "1234@mail.example.com")
        }
        "equals the same id written without brackets" in {
            assert(messageIdOf("<1234@mail.example.com>") == messageIdOf("1234@mail.example.com"))
        }
        "differs from another id" in {
            assert(messageIdOf("<a@example.com>") != messageIdOf("<b@example.com>"))
        }
        "init fails on an empty id" in {
            assert(violation(MessageId.init("  ")) == Present(Violation.EmptyMessageId))
        }
        "init fails on brackets with nothing inside" in {
            assert(violation(MessageId.init("<>")) == Present(Violation.EmptyMessageId))
        }
        "init fails on a line break, which could inject a header" in {
            assert(violation(MessageId.init("<a@b>\r\nBcc: victim@example.com")) == Present(Violation.ControlCharacterInMessageId))
        }
        "init fails on a line break or tab at either end, which only spaces are trimmed from" in {
            Seq("a@b\r\n", "\r\n<a@b>", "\ta@b").foreach { value =>
                assert(violation(MessageId.init(value)) == Present(Violation.ControlCharacterInMessageId), value)
            }
        }
        "init fails on <, > or a space inside, which would close the brackets early or split the id" in {
            Seq("a b@c", "<a b@c>", "a<b@c", "<a>b@c>", "a@b>c").foreach { value =>
                assert(violation(MessageId.init(value)) == Present(Violation.DelimiterInMessageId), value)
            }
        }
        "init fails on a ( outside a quoted string or a quoted string that does not close, which a reader would not take back" in {
            Seq("a(b@c", "a@b(c)", "\"a@b", "a\"b\\\"@c", "a@b\"").foreach { value =>
                assert(violation(MessageId.init(value)) == Present(Violation.UnreadableMessageId), value)
            }
        }
        "keeps a quoted string, a ( inside one, and a ) or \\ outside one, each of which reads back" in {
            Seq("\"a(b\"@c", "\"a\\\"b\"@c", "a)b@c", "a\\b@c").foreach { value =>
                assert(messageIdOf(value).value == value, value)
                assert(kyo.internal.email.mime.MessageIdCodec.parse(s"<$value>") == Chunk(messageIdOf(value)), value)
            }
        }
        "read answers the id, or Absent where init fails" in {
            assert(MessageId.read("<1234@mail.example.com>") == Present(messageIdOf("1234@mail.example.com")))
            assert(MessageId.read("a\"b") == Absent)
            Seq("", "<>", "a b@c", "a<b", "a\u0000b").foreach(value => assert(MessageId.read(value) == Absent, value))
        }
    }

    "ContentId" - {
        "strips the angle brackets of a header value" in {
            assert(contentIdOf("<logo@example.com>").value == "logo@example.com")
            assert(contentIdOf("<logo@example.com>") == contentIdOf("logo@example.com"))
        }
        "init fails on an empty id" in {
            assert(violation(ContentId.init("<>")) == Present(Violation.EmptyContentId))
        }
        "init fails on a control character" in {
            assert(violation(ContentId.init("logo\u0000@example.com")) == Present(Violation.ControlCharacterInContentId))
        }
        "init fails on <, > or a space inside" in {
            Seq("logo @example.com", "lo<go@example.com", "<lo>go@example.com>").foreach { value =>
                assert(violation(ContentId.init(value)) == Present(Violation.DelimiterInContentId), value)
            }
        }
        "init fails on a ( outside a quoted string or a quoted string that does not close" in {
            Seq("lo(go@example.com", "\"logo@example.com").foreach { value =>
                assert(violation(ContentId.init(value)) == Present(Violation.UnreadableContentId), value)
            }
        }
        "read answers the id, or Absent where init fails" in {
            assert(ContentId.read("<logo@example.com>") == Present(contentIdOf("logo@example.com")))
            assert(ContentId.read("lo(go") == Absent)
            Seq("", "<>", "a b", "a>b", "a\u0000b").foreach(value => assert(ContentId.read(value) == Absent, value))
        }
    }

    "MailboxName" - {
        "normalizes any casing of INBOX" in {
            assert(mailboxOf("inbox") == MailboxName.Inbox)
            assert(mailboxOf("InBoX").value == "INBOX")
        }
        "folds only ASCII case when recognizing INBOX, so a mailbox named with a dotless i stays its own" in {
            assert(mailboxOf("ınbox").value == "ınbox")
            assert(mailboxOf("ınbox") != MailboxName.Inbox)
            assert(mailboxOf("İNBOX").value == "İNBOX")
        }
        "keeps other names case-sensitive" in {
            assert(mailboxOf("Archive") != mailboxOf("archive"))
            assert(mailboxOf("Archive/2026").value == "Archive/2026")
        }
        "init fails on an empty name" in {
            assert(violation(MailboxName.init("")) == Present(Violation.EmptyMailboxName))
        }
        "init fails on a line break, which could end an IMAP command and inject another" in {
            assert(violation(MailboxName.init("Archive\r\nA2 DELETE INBOX")) == Present(Violation.ControlCharacterInMailboxName))
        }
        "keeps non-ASCII names" in {
            assert(mailboxOf("Entwürfe").value == "Entwürfe")
        }
        "init fails on an unpaired surrogate, which no encoding of a name carries" in {
            assert(violation(MailboxName.init("a\uD800")) == Present(Violation.UnpairedSurrogateInMailboxName))
        }
        "a listed name that is canonical is the mailbox init builds from its text" in {
            assert(wire("Entw&APw-rfe", utf8 = false) == mailboxOf("Entwürfe"))
            assert(wire("Entwürfe", utf8 = true) == mailboxOf("Entwürfe"))
            assert(!wire("Entw&APw-rfe", utf8 = false).verbatim)
            assert(wire("inbox", utf8 = false) == MailboxName.Inbox)
        }
        "a listed name that is not canonical keeps its octets, decodes leniently, and is another mailbox than its text built with init" in {
            assert(listed.value == "Tom&Jerry" && listed.verbatim)
            assert(!mailboxOf("Tom&Jerry").verbatim)
            assert(listed != mailboxOf("Tom&Jerry"))
            assert(wire("Tom&Jerry &APw- x", utf8 = false).value == "Tom&Jerry ü x")
        }
        "octets that are not UTF-8 are kept, shown one character per octet" in {
            val eight = MailboxName.fromWire(Chunk('a'.toByte, 0xe9.toByte), utf8 = true)
            assert(eight.map(n => (n.value, n.verbatim)) == Present(("aé", true)))
            val rev1 = MailboxName.fromWire(Chunk.from("Entwürfe".getBytes("UTF-8")), utf8 = false)
            assert(rev1.map(n => (n.value, n.verbatim)) == Present(("Entwürfe", true)))
        }
        "a listed name empty or holding a control octet is refused" in {
            assert(MailboxName.fromWire(Chunk.empty, utf8 = false).isEmpty)
            assert(MailboxName.fromWire(Chunk('a'.toByte, '\r'.toByte), utf8 = false).isEmpty)
        }
    }

    "UidValidity" - {
        "accepts the bounds of the non-zero 32-bit unsigned range" in {
            assert(uidValidityOf(1L).value == 1L)
            assert(uidValidityOf(4294967295L).value == 4294967295L)
        }
        "init fails on zero" in {
            assert(violation(UidValidity.init(0L)) == Present(Violation.UidValidityOutOfRange(0L)))
        }
        "init fails above the 32-bit unsigned range" in {
            assert(violation(UidValidity.init(4294967296L)) == Present(Violation.UidValidityOutOfRange(4294967296L)))
        }
    }

    "Uid" - {
        "equals a UID with the same mailbox, validity and number" in {
            assert(uidOf(archive, validity, 42L) == uidOf(mailboxOf("Archive"), uidValidityOf(3857529045L), 42L))
        }
        "differs from the same number under another validity" in {
            assert(uidOf(archive, validity, 42L) != uidOf(archive, uidValidityOf(3857529046L), 42L))
        }
        "differs from the same number and validity in another mailbox" in {
            assert(uidOf(archive, validity, 42L) != uidOf(MailboxName.Inbox, validity, 42L))
        }
        "init fails on zero" in {
            assert(violation(Uid.init(archive, validity, 0L)) == Present(Violation.UidOutOfRange(0L)))
        }
        "init fails above the 32-bit unsigned range" in {
            assert(violation(Uid.init(archive, validity, 4294967296L)) == Present(Violation.UidOutOfRange(4294967296L)))
        }
    }

    "Schema" - {
        "round-trips every id kind" in {
            val messageId = messageIdOf("<1234@mail.example.com>")
            val uid       = uidOf(archive, validity, 42L)
            assert(Structure.decode[MessageId](Structure.encode(messageId)) == Result.succeed(messageId))
            assert(Structure.decode[MailboxName](Structure.encode(archive)) == Result.succeed(archive))
            assert(Structure.decode[UidValidity](Structure.encode(validity)) == Result.succeed(validity))
            assert(Structure.decode[Uid](Structure.encode(uid)) == Result.succeed(uid))
            val contentId = contentIdOf("<logo@example.com>")
            assert(Structure.decode[ContentId](Structure.encode(contentId)) == Result.succeed(contentId))
        }
        "round-trips every id kind through Json" in {
            val messageId = messageIdOf("<1234@mail.example.com>")
            val contentId = contentIdOf("<logo@example.com>")
            val uid       = uidOf(archive, validity, 42L)
            assert(Json.decode[MessageId](Json.encode(messageId)) == Result.succeed(messageId))
            assert(Json.decode[ContentId](Json.encode(contentId)) == Result.succeed(contentId))
            assert(Json.decode[MailboxName](Json.encode(archive)) == Result.succeed(archive))
            assert(Json.decode[UidValidity](Json.encode(validity)) == Result.succeed(validity))
            assert(Json.decode[Uid](Json.encode(uid)) == Result.succeed(uid))
        }
        "a mailbox name is stored as its wire form on every codec, and a listed and a built name read back as themselves" in {
            assert(Json.encode[MailboxName](listed) == "\"Tom&Jerry\"")
            assert(Json.encode[MailboxName](mailboxOf("Tom&Jerry")) == "\"Tom&-Jerry\"")
            assert(Json.encode[MailboxName](mailboxOf("Entwürfe")) == "\"Entw&APw-rfe\"")
            Seq(listed, mailboxOf("Tom&Jerry"), mailboxOf("Entwürfe")).foreach { name =>
                assert(Structure.decode[MailboxName](Structure.encode(name)) == Result.succeed(name))
                assert(Json.decode[MailboxName](Json.encode[MailboxName](name)) == Result.succeed(name))
                val uid = uidOf(name, validity, 42L)
                assert(Structure.decode[Uid](Structure.encode(uid)) == Result.succeed(uid))
            }
        }
        "rejects a stored mailbox name with a character above U+00FF, which is not a wire form" in {
            assert(rejection(Structure.decode[MailboxName](Structure.encode("☺"))) == Present(Violation.MailboxNameNotOctets))
        }
        "rejects a stored message id with a line break as a decode failure" in {
            assert(rejection(Structure.decode[MessageId](Structure.encode("a@b\r\nX: y"))) ==
                Present(Violation.ControlCharacterInMessageId))
        }
        "rejects a stored content id with a delimiter as a decode failure" in {
            assert(rejection(Structure.decode[ContentId](Structure.encode("a<b"))) == Present(Violation.DelimiterInContentId))
            assert(rejection(Json.decode[ContentId]("\"\"")) == Present(Violation.EmptyContentId))
        }
        "normalizes on decode as init does" in {
            assert(Structure.decode[MailboxName](Structure.encode("inbox")) == Result.succeed(MailboxName.Inbox))
            assert(Structure.decode[MessageId](Structure.encode("<a@b>")) == Result.succeed(messageIdOf("a@b")))
        }
        "rejects an empty mailbox name as a decode failure" in {
            assert(rejection(Structure.decode[MailboxName](Structure.encode(""))) == Present(Violation.EmptyMailboxName))
        }
        "rejects an out-of-range validity as a decode failure" in {
            assert(rejection(Structure.decode[UidValidity](Structure.encode(0L))) == Present(Violation.UidValidityOutOfRange(0L)))
        }
        "rejects a stored UID whose number is out of range as a decode failure" in {
            val stored = Structure.encode(UidShape("Archive", 3857529045L, 0L))
            assert(rejection(Structure.decode[Uid](stored)) == Present(Violation.UidOutOfRange(0L)))
        }
        "rejects a stored UID whose mailbox is empty as a decode failure" in {
            val stored = Structure.encode(UidShape("", 3857529045L, 42L))
            assert(rejection(Structure.decode[Uid](stored)) == Present(Violation.EmptyMailboxName))
        }
        "a rejection inside a derived schema names the decoding caller's Frame, for every id kind" in {
            val stored = Json.encode(IdsShape(
                messageIdOf("<1234@mail.example.com>"),
                contentIdOf("<logo@example.com>"),
                archive,
                validity,
                uidOf(mailboxOf("Sent"), uidValidityOf(77L), 987654321L)
            ))
            Seq(
                "\"1234@mail.example.com\"" -> "\"\"",
                "\"logo@example.com\""      -> "\"\"",
                "\"Archive\""               -> "\"\"",
                "3857529045"                -> "0",
                "987654321"                 -> "0"
            ).foreach { (valid, invalid) =>
                assert(stored.contains(valid), stored)
                Json.decode[IdsShape](stored.replace(valid, invalid)) match
                    case Result.Failure(e: ConstructorRejectedException) =>
                        e.rejection match
                            case leaf: EmailInvalidIdException =>
                                assert(leaf.frame == e.frame, s"$valid rejected at ${leaf.frame}, decoded at ${e.frame}")
                            case other => fail(s"expected the constructor's own failure for $valid, got $other")
                    case other => fail(s"expected a ConstructorRejectedException for $valid, got $other")
                end match
            }
        }
    }

    private def rejection[A](decoded: Result[DecodeException, A]): Maybe[Violation] =
        decoded match
            case Result.Failure(ex: ConstructorRejectedException) =>
                ex.rejection match
                    case invalid: EmailInvalidIdException => Present(invalid.violation)
                    case _                                => Absent
            case _ => Absent

end EmailIdTest
