package kyo.internal.email.imap

import kyo.*
import kyo.internal.email.imap.ImapCodec.*

class ImapModelTest extends kyo.test.Test[Any]:

    private def octets(text: String): Chunk[Byte] = Chunk.from(text.getBytes("UTF-8"))

    private val inbox    = EmailLiterals.mailboxOf("INBOX")
    private val validity = EmailLiterals.uidValidityOf(3857529045L)

    // The value of the one item of a FETCH response the codec reads from `line`.
    private def item(line: String): Value =
        parse(Chunk(Segment.Text(Span.from(line.getBytes("UTF-8"))))) match
            case Result.Success(Response.Untagged(Data.Fetch(_, items))) => items.head._2
            case other                                                   => throw new IllegalStateException(s"not a fetch: $other")

    private def structure(text: String): Maybe[EmailReceive.Part] = ImapModel.part(item(s"* 1 FETCH (BODYSTRUCTURE $text)"))

    private def mediaType(mainType: String, subType: String, parameters: (String, String)*): Email.MediaType =
        EmailLiterals.mediaTypeOf(mainType, subType, parameters*)

    "mailboxes" - {
        "a LIST item: its name decoded, its delimiter, its attributes" in {
            assert(ImapModel.mailbox(Data.ListItem(Chunk("\\HasNoChildren", "\\Sent"), Present('/'), octets("Sent Items")), utf8 = false) ==
                Present(EmailReceive.Mailbox(
                    EmailLiterals.mailboxOf("Sent Items"),
                    Present('/'),
                    Set(EmailReceive.Mailbox.Attribute.HasNoChildren, EmailReceive.Mailbox.Attribute.Sent)
                )))
            assert(ImapModel.mailbox(Data.ListItem(Chunk.empty, Absent, octets("Entw&APw-rfe")), utf8 = false).map(_.name.value) ==
                Present("Entwürfe"))
        }
        "a name that does not decode is kept verbatim, and one holding a control character is Absent" in {
            assert(ImapModel.mailbox(Data.ListItem(Chunk.empty, Absent, octets("&bad")), utf8 = false).map(m =>
                (m.name.value, m.name.verbatim)
            ) == Present(("&bad", true)))
            assert(ImapModel.mailbox(Data.ListItem(Chunk.empty, Absent, octets("a\u0001b")), utf8 = true) == Absent)
        }
    }

    "a STATUS response, with every item, and Absent when one is missing or out of range" in {
        val items = Chunk("MESSAGES" -> 231L, "UNSEEN" -> 3L, "UIDNEXT" -> 44292L, "UIDVALIDITY" -> 3857529045L)
        assert(ImapModel.status(inbox, Data.MailboxStatus(octets("INBOX"), items)) ==
            Present(EmailReceive.MailboxStatus(inbox, 231, 3, 44292, validity)))
        assert(ImapModel.status(inbox, Data.MailboxStatus(octets("INBOX"), items.take(3))) == Absent)
        assert(ImapModel.status(inbox, Data.MailboxStatus(octets("INBOX"), items.take(3).append("UIDVALIDITY" -> 0L))) == Absent)
    }

    "flags, \\Recent dropped" in {
        assert(ImapModel.flags(item("* 1 FETCH (FLAGS (\\Seen \\Recent $Junk \\Forwarded))")) ==
            Present(Set[Email.Flag](Email.Flag.Seen, EmailLiterals.keywordOf("$Junk")) ++ Email.Flag.fromWire("\\Forwarded").toList))
        assert(ImapModel.flags(item("* 1 FETCH (FLAGS NIL)")) == Absent)
    }

    "body structures (RFC 9051 section 7.5.2)" - {
        "a single-part text body is part 1" in {
            assert(structure("(\"TEXT\" \"PLAIN\" (\"CHARSET\" \"US-ASCII\") NIL NIL \"7BIT\" 3028 92)") ==
                Present(EmailReceive.Part(
                    Chunk(1),
                    mediaType("text", "plain", "charset" -> "US-ASCII"),
                    "7bit",
                    3028L.bytes,
                    Absent,
                    Absent,
                    Absent,
                    Chunk.empty
                )))
        }
        "a multipart is the root, its children numbered from 1, with dispositions, names and content ids" in {
            val text = "((\"TEXT\" \"PLAIN\" (\"CHARSET\" \"UTF-8\") NIL NIL \"QUOTED-PRINTABLE\" 100 3 NIL NIL NIL NIL)" +
                "(\"APPLICATION\" \"PDF\" (\"NAME\" \"a.pdf\") \"<id@example.com>\" NIL \"BASE64\" 2000 NIL " +
                "(\"ATTACHMENT\" (\"FILENAME\" \"report.pdf\")) NIL NIL) \"MIXED\" (\"BOUNDARY\" \"xyz\") NIL NIL NIL)"
            assert(structure(text) == Present(EmailReceive.Part(
                Chunk.empty,
                mediaType("multipart", "mixed", "boundary" -> "xyz"),
                "7bit",
                0L.bytes,
                Absent,
                Absent,
                Absent,
                Chunk(
                    EmailReceive.Part(
                        Chunk(1),
                        mediaType("text", "plain", "charset" -> "UTF-8"),
                        "quoted-printable",
                        100L.bytes,
                        Absent,
                        Absent,
                        Absent,
                        Chunk.empty
                    ),
                    EmailReceive.Part(
                        Chunk(2),
                        mediaType("application", "pdf", "name" -> "a.pdf"),
                        "base64",
                        2000L.bytes,
                        Present("attachment"),
                        Present("report.pdf"),
                        Present(EmailLiterals.contentIdOf("id@example.com")),
                        Chunk.empty
                    )
                )
            )))
        }
        "a text part's extension data follows its line count" in {
            val part = structure("(\"TEXT\" \"PLAIN\" NIL NIL NIL \"7BIT\" 10 2 NIL (\"INLINE\" (\"FILENAME\" \"notes.txt\")) NIL NIL)")
            assert(part.flatMap(_.disposition) == Present("inline"))
            assert(part.flatMap(_.fileName) == Present("notes.txt"))
        }
        "a type's name is the file name when the part has no disposition" in {
            assert(
                structure("(\"APPLICATION\" \"PDF\" (\"NAME\" \"a.pdf\") NIL NIL \"BASE64\" 2000 NIL NIL NIL NIL)").flatMap(_.fileName) ==
                    Present("a.pdf")
            )
        }
        "a parameter in RFC 2231's extended form and one in an encoded word are decoded" in {
            assert(structure(
                "(\"APPLICATION\" \"PDF\" NIL NIL NIL \"BASE64\" 10 NIL (\"ATTACHMENT\" (\"FILENAME*\" \"utf-8''caf%C3%A9.pdf\")) NIL NIL)"
            ).flatMap(_.fileName) == Present("café.pdf"))
            assert(structure("(\"APPLICATION\" \"PDF\" (\"NAME\" \"=?utf-8?q?caf=C3=A9.pdf?=\") NIL NIL \"BASE64\" 10 NIL NIL NIL NIL)")
                .flatMap(_.fileName) == Present("café.pdf"))
        }
        "an attached message has the message's structure as its child, numbered under it" in {
            val envelope = "(NIL \"hi\" NIL NIL NIL NIL NIL NIL NIL NIL)"
            val single   =
                structure(s"(\"MESSAGE\" \"RFC822\" NIL NIL NIL \"7BIT\" 500 $envelope (\"TEXT\" \"PLAIN\" NIL NIL NIL \"7BIT\" 20 1) 12)")
            assert(single.map(_.path) == Present(Chunk(1)))
            assert(single.map(_.children.map(_.path)) == Present(Chunk(Chunk(1, 1))))
            val nested = structure(
                "((\"TEXT\" \"PLAIN\" NIL NIL NIL \"7BIT\" 5 1)(\"MESSAGE\" \"RFC822\" NIL NIL NIL \"7BIT\" 500 " + envelope +
                    " ((\"TEXT\" \"PLAIN\" NIL NIL NIL \"7BIT\" 20 1)(\"TEXT\" \"HTML\" NIL NIL NIL \"7BIT\" 30 1) \"ALTERNATIVE\") 12) \"MIXED\")"
            )
            val attached = nested.map(_.children(1))
            assert(attached.map(_.path) == Present(Chunk(2)))
            assert(attached.map(_.children.map(_.path)) == Present(Chunk(Chunk(2))))
            assert(attached.map(_.children.flatMap(_.children).map(_.path)) == Present(Chunk(Chunk(2, 1), Chunk(2, 2))))
        }
        "a structure that is not the grammar is Absent" in {
            assert(structure("(\"TEXT\")") == Absent)
            assert(structure("NIL") == Absent)
            assert(structure("(\"TEXT\" \"PLAIN\" NIL NIL NIL \"7BIT\" NIL 1)") == Absent)
        }
    }

    "a summary's fields from a FETCH response" in {
        val header = "Subject: hi\r\n\r\n"
        val items  = Chunk(
            "UID"           -> Value.Number(4827313),
            "FLAGS"         -> Value.Items(Chunk(Value.Atom("\\Seen"))),
            "INTERNALDATE"  -> Value.Str(octets("17-Jul-1996 02:44:25 -0700")),
            "RFC822.SIZE"   -> Value.Number(4286),
            "BODY[HEADER]"  -> Value.Str(octets(header)),
            "BODYSTRUCTURE" -> item("* 1 FETCH (BODYSTRUCTURE (\"TEXT\" \"PLAIN\" NIL NIL NIL \"7BIT\" 3028 92))")
        )
        val fields = ImapModel.summaryFields(inbox, validity, items)
        assert(fields.map(_.uid) == Present(EmailLiterals.uidOf(inbox, validity, 4827313)))
        assert(fields.map(_.flags) == Present(Set[Email.Flag](Email.Flag.Seen)))
        assert(fields.map(_.internalDate) == Present(EmailLiterals.instantOf("1996-07-17T09:44:25Z")))
        assert(fields.map(_.size) == Present(4286L.bytes))
        assert(fields.map(f => new String(f.header.toArray, "UTF-8")) == Present(header))
        assert(fields.map(_.structure.path) == Present(Chunk(1)))
        assert(ImapModel.summaryFields(inbox, validity, items.filter(_._1 != "INTERNALDATE")) == Absent)
    }

    "a delivered message from a FETCH response: Received, Unreadable naming the first field it cannot read, or Absent with no UID or body" in {
        import EmailReceive.InboxEvent.Unreadable
        val raw   = "Subject: hi\r\n\r\nbody\r\n"
        val items = Chunk(
            "UID"          -> Value.Number(12),
            "FLAGS"        -> Value.Items(Chunk.empty),
            "INTERNALDATE" -> Value.Str(octets("17-Jul-1996 02:44:25 -0700")),
            "RFC822.SIZE"  -> Value.Number(raw.length),
            "BODY[]"       -> Value.Str(octets(raw))
        )
        val uid                                         = EmailLiterals.uidOf(inbox, validity, 12)
        def replaced(name: String, value: Maybe[Value]) = items.filter(_._1 != name).concat(value.map(name -> _).toChunk)
        assert(ImapModel.received(inbox, validity, items) == Present(EmailReceive.InboxEvent.Received(
            uid,
            Set.empty,
            EmailLiterals.instantOf("1996-07-17T09:44:25Z"),
            raw.length.toLong.bytes,
            Span.from(raw.getBytes("UTF-8"))
        )))
        val badDate  = Present(Value.Str(octets("yesterday")))
        val badFlags = Present(Value.Items(Chunk(Value.Str(octets("odd")))))
        assert(ImapModel.received(inbox, validity, replaced("INTERNALDATE", badDate)) ==
            Present(Unreadable(uid, Unreadable.Item.InternalDate)))
        assert(ImapModel.received(inbox, validity, replaced("FLAGS", badFlags)) == Present(Unreadable(uid, Unreadable.Item.Flags)))
        assert(ImapModel.received(inbox, validity, replaced("RFC822.SIZE", Absent)) == Present(Unreadable(uid, Unreadable.Item.Size)))
        assert(ImapModel.received(inbox, validity, replaced("FLAGS", badFlags).filter(_._1 != "INTERNALDATE")) ==
            Present(Unreadable(uid, Unreadable.Item.InternalDate)))
        assert(ImapModel.received(inbox, validity, replaced("BODY[]", Absent)) == Absent)
        assert(ImapModel.received(inbox, validity, replaced("UID", Present(Value.Number(0)))) == Absent)
    }

    "search keys (RFC 9051 section 6.4.4)" - {
        def keys(query: EmailReceive.Search): (Chunk[Part], Boolean) = ImapModel.search(query, _ => false)
        def text(query: EmailReceive.Search): String                 =
            keys(query)._1.map {
                case Part.Text(t)       => t
                case Part.Literal(o, _) => s"{${o.size}}"
            }.mkString
        "flags, conjunctions, negation and disjunction" in {
            assert(text(EmailReceive.Search.All) == "ALL")
            assert(text(EmailReceive.Search.And(
                EmailReceive.Search.Unseen,
                EmailReceive.Search.Flagged,
                EmailReceive.Search.Not(EmailReceive.Search.Deleted)
            )) ==
                "(UNSEEN FLAGGED NOT DELETED)")
            assert(text(EmailReceive.Search.And(EmailReceive.Search.Draft)) == "DRAFT")
            assert(text(EmailReceive.Search.Or(
                EmailReceive.Search.Seen,
                EmailReceive.Search.And(EmailReceive.Search.Answered, EmailReceive.Search.Unflagged)
            )) ==
                "OR SEEN (ANSWERED UNFLAGGED)")
            assert(text(EmailReceive.Search.Keyword(EmailLiterals.keywordOf("$Junk"))) == "KEYWORD $Junk")
        }
        "text keys as atoms, quoted strings or literals, the last requiring CHARSET UTF-8" in {
            assert(keys(EmailReceive.Search.From("ada@example.com")) == (Chunk(Part.Text("FROM "), Part.Text("ada@example.com")), false))
            assert(text(EmailReceive.Search.Subject("hello world")) == "SUBJECT \"hello world\"")
            assert(keys(EmailReceive.Search.Body("café")) ==
                (Chunk(Part.Text("BODY "), Part.Literal(octets("café"), synchronizing = true)), true))
            assert(text(EmailLiterals.headerKeyOf("List-Id", "kyo")) == "HEADER List-Id kyo")
            assert(text(EmailReceive.Search.To("a")) == "TO a" && text(EmailReceive.Search.Cc("b")) == "CC b" &&
                text(EmailReceive.Search.Text("c")) == "TEXT c")
        }
        "dates as the UTC calendar day, and sizes in octets" in {
            assert(text(EmailReceive.Search.Since(EmailLiterals.instantOf("2024-01-05T23:30:00Z"))) == "SINCE 5-Jan-2024")
            assert(text(EmailReceive.Search.Before(EmailLiterals.instantOf("1999-12-31T00:00:00Z"))) == "BEFORE 31-Dec-1999")
            assert(text(EmailReceive.Search.Larger(1024L.bytes)) == "LARGER 1024" &&
                text(EmailReceive.Search.Smaller(10L.bytes)) == "SMALLER 10")
        }
        "a literal is non-synchronizing when the session may send it so" in {
            assert(ImapModel.search(EmailReceive.Search.Body("café"), _ => true)._1(1) ==
                Part.Literal(octets("café"), synchronizing = false))
        }
    }

end ImapModelTest
