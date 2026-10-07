package kyo.internal.email.imap

import kyo.*
import kyo.internal.email.imap.ImapCodec.*

class ImapCodecTest extends kyo.test.Test[Any]:

    private def bytes(text: String): Span[Byte] = Span.from(text.getBytes("UTF-8"))

    private def octets(text: String): Chunk[Byte] = Chunk.from(text.getBytes("UTF-8"))

    private def line(text: String): Chunk[Segment] = Chunk(Segment.Text(bytes(text)))

    private def parsed(segments: Chunk[Segment]): Response =
        parse(segments) match
            case Result.Success(response) => response
            case other                    => throw new IllegalStateException(s"did not parse: $other")

    private def untagged(text: String): Data =
        parsed(line(text)) match
            case Response.Untagged(data) => data
            case other                   => throw new IllegalStateException(s"not untagged: $other")

    private def str(text: String): Value = Value.Str(octets(text))

    "completions and continuations" - {
        "a tagged OK with a response code and text (RFC 3501 section 8)" in {
            assert(parsed(line("a002 OK [READ-WRITE] SELECT completed")) ==
                Response.Tagged("a002", Condition.Ok, Present(Code("READ-WRITE", Absent)), "SELECT completed"))
        }
        "a tagged NO with an argumented code, and BAD with none, in any case of the keyword" in {
            assert(parsed(line("A7 no [BADCHARSET (UTF-8)] charset not supported")) ==
                Response.Tagged("A7", Condition.No, Present(Code("BADCHARSET", Present("(UTF-8)"))), "charset not supported"))
            assert(parsed(line("A8 BAD parse error")) == Response.Tagged("A8", Condition.Bad, Absent, "parse error"))
        }
        "a continuation, with text, with base64, and bare" in {
            assert(parsed(line("+ Ready for literal")) == Response.Continuation("Ready for literal"))
            assert(parsed(line("+ eyJzdGF0dXMiOiI0MDEifQ==")) == Response.Continuation("eyJzdGF0dXMiOiI0MDEifQ=="))
            assert(parsed(line("+")) == Response.Continuation(""))
        }
    }

    "untagged data (RFC 3501 section 8)" - {
        "the greeting, a status with a numeric code, and BYE" in {
            assert(untagged("* OK IMAP4rev1 Service Ready") == Data.Status(Condition.Ok, Absent, "IMAP4rev1 Service Ready"))
            assert(untagged("* OK [UIDVALIDITY 3857529045] UIDs valid") ==
                Data.Status(Condition.Ok, Present(Code("UIDVALIDITY", Present("3857529045"))), "UIDs valid"))
            assert(untagged("* BYE IMAP4rev1 server terminating connection") ==
                Data.Status(Condition.Bye, Absent, "IMAP4rev1 server terminating connection"))
            assert(untagged("* PREAUTH [CAPABILITY IMAP4rev1] ready") ==
                Data.Status(Condition.PreAuth, Present(Code("CAPABILITY", Present("IMAP4rev1"))), "ready"))
        }
        "counts and flags" in {
            assert(untagged("* 18 EXISTS") == Data.Exists(18))
            assert(untagged("* 2 RECENT") == Data.Recent(2))
            assert(untagged("* 44 EXPUNGE") == Data.Expunge(44))
            assert(untagged("* FLAGS (\\Answered \\Flagged \\Deleted \\Seen \\Draft)") ==
                Data.Flags(Chunk("\\Answered", "\\Flagged", "\\Deleted", "\\Seen", "\\Draft")))
        }
        "capabilities" in {
            assert(untagged("* CAPABILITY IMAP4rev1 STARTTLS AUTH=PLAIN SASL-IR LITERAL+") ==
                Data.Capability(Chunk("IMAP4rev1", "STARTTLS", "AUTH=PLAIN", "SASL-IR", "LITERAL+")))
        }
        "LIST with a quoted or NIL delimiter and an atom, quoted or literal name" in {
            assert(untagged("* LIST (\\Noselect) \"/\" ~/Mail/foo") ==
                Data.ListItem(Chunk("\\Noselect"), Present('/'), octets("~/Mail/foo")))
            assert(untagged("* LIST () NIL \"Sent Items\"") == Data.ListItem(Chunk.empty, Absent, octets("Sent Items")))
            assert(parsed(Chunk(
                Segment.Text(bytes("* LIST (\\HasNoChildren) \".\" {5}")),
                Segment.Literal(bytes("a\"b c")),
                Segment.Text(bytes(""))
            )) ==
                Response.Untagged(Data.ListItem(Chunk("\\HasNoChildren"), Present('.'), octets("a\"b c"))))
        }
        "STATUS" in {
            assert(untagged("* STATUS blurdybloop (MESSAGES 231 UIDNEXT 44292)") ==
                Data.MailboxStatus(octets("blurdybloop"), Chunk("MESSAGES" -> 231L, "UIDNEXT" -> 44292L)))
        }
        "SEARCH with numbers and with none" in {
            assert(untagged("* SEARCH 2 84 882") == Data.Search(Chunk(2L, 84L, 882L)))
            assert(untagged("* SEARCH") == Data.Search(Chunk.empty))
        }
        "ESEARCH (RFC 4731), its ALL set as ranges low to high, and with no ALL an empty result" in {
            assert(untagged("* ESEARCH (TAG \"A3\") UID ALL 4:6,9,11:10") == Data.ESearch(Chunk((4L, 6L), (9L, 9L), (10L, 11L))))
            assert(untagged("* ESEARCH (TAG \"A3\") UID ALL 7") == Data.ESearch(Chunk((7L, 7L))))
            assert(untagged("* ESEARCH (TAG \"A3\") UID COUNT 0") == Data.ESearch(Chunk.empty))
        }
        "an ESEARCH range is read without expanding it, however many UIDs it names" in {
            assert(untagged("* ESEARCH (TAG \"A3\") UID ALL 1:4294967295") == Data.ESearch(Chunk((1L, 4294967295L))))
        }
        "FETCH with flags, a quoted date, a size and a literal section (RFC 3501 section 8)" in {
            val header = "Date: Wed, 17 Jul 1996 02:23:25 -0700 (PDT)\r\nSubject: IMAP4rev1 WG mtg summary and minutes\r\n\r\n"
            val data   = parsed(Chunk(
                Segment.Text(bytes(
                    "* 12 FETCH (UID 4827313 FLAGS (\\Seen) INTERNALDATE \"17-Jul-1996 02:44:25 -0700\" RFC822.SIZE 4286 BODY[HEADER] {" +
                        header.length + "}"
                )),
                Segment.Literal(bytes(header)),
                Segment.Text(bytes(")"))
            ))
            assert(data == Response.Untagged(Data.Fetch(
                12,
                Chunk(
                    "UID"          -> Value.Number(4827313),
                    "FLAGS"        -> Value.Items(Chunk(Value.Atom("\\Seen"))),
                    "INTERNALDATE" -> str("17-Jul-1996 02:44:25 -0700"),
                    "RFC822.SIZE"  -> Value.Number(4286),
                    "BODY[HEADER]" -> str(header)
                )
            )))
        }
        "FETCH with a nested BODY list, NIL and a section holding spaces" in {
            assert(untagged(
                "* 12 FETCH (BODY (\"TEXT\" \"PLAIN\" (\"CHARSET\" \"US-ASCII\") NIL NIL \"7BIT\" 3028 92) BODY[HEADER.FIELDS (FROM TO)] \"\")"
            ) ==
                Data.Fetch(
                    12,
                    Chunk(
                        "BODY" -> Value.Items(Chunk(
                            str("TEXT"),
                            str("PLAIN"),
                            Value.Items(Chunk(str("CHARSET"), str("US-ASCII"))),
                            Value.Nil,
                            Value.Nil,
                            str("7BIT"),
                            Value.Number(3028),
                            Value.Number(92)
                        )),
                        "BODY[HEADER.FIELDS (FROM TO)]" -> str("")
                    )
                ))
        }
        "a multipart BODYSTRUCTURE's parts follow each other with no space (RFC 9051 section 9, body-type-mpart)" in {
            assert(untagged(
                "* 1 FETCH (BODYSTRUCTURE ((\"TEXT\" \"PLAIN\" NIL NIL NIL \"7BIT\" 1 1)(\"TEXT\" \"HTML\" NIL NIL NIL \"7BIT\" 2 1) \"ALTERNATIVE\"))"
            ) ==
                Data.Fetch(
                    1,
                    Chunk("BODYSTRUCTURE" -> Value.Items(Chunk(
                        Value.Items(Chunk(
                            str("TEXT"),
                            str("PLAIN"),
                            Value.Nil,
                            Value.Nil,
                            Value.Nil,
                            str("7BIT"),
                            Value.Number(1),
                            Value.Number(1)
                        )),
                        Value.Items(Chunk(
                            str("TEXT"),
                            str("HTML"),
                            Value.Nil,
                            Value.Nil,
                            Value.Nil,
                            str("7BIT"),
                            Value.Number(2),
                            Value.Number(1)
                        )),
                        str("ALTERNATIVE")
                    )))
                ))
        }
        "values other than lists still need a space between them" in {
            assert(parse(line("* 1 FETCH (FLAGS (\\Seen)UID 4)")).isFailure)
            assert(parse(line("* ENABLED (A)B")).isFailure)
        }
        "a quoted string's escapes are removed" in {
            assert(untagged("* LIST () \"\\\\\" \"a\\\"b\"") == Data.ListItem(Chunk.empty, Present('\\'), octets("a\"b")))
        }
        "an unmodelled keyword keeps its values" in {
            assert(untagged("* ENABLED UTF8=ACCEPT") == Data.Other("ENABLED", Chunk(Value.Atom("UTF8=ACCEPT"))))
        }
    }

    "malformed responses are refused, naming what was read" in {
        Seq("", "* 12", "A1 MAYBE done", "* LIST (\\Noselect \"/\" foo", "* 3 FETCH (UID)", "* SEARCH x", "A1 OK [UNCLOSED text").foreach {
            text =>
                assert(parse(line(text)).isFailure, text)
        }
    }

    "a line's trailing literal marker announces the octets that follow it" in {
        assert(literalAt(bytes("* 12 FETCH (BODY[] {342}")) == Present(342L))
        assert(literalAt(bytes("{0}")) == Present(0L))
        assert(literalAt(bytes("A1 LOGIN {5+}")) == Present(5L))
        Seq("* OK done", "{}", "x}", "5}", "{+}", "{12} ", "{1a}", "{" + "9" * 19 + "}").foreach { text =>
            assert(literalAt(bytes(text)) == Absent, text)
        }
    }

    "a literal's size is read by the parser with the digits literalAt accepts, ten of them here" in {
        val marker = bytes("* LIST () \".\" {0000000005}")
        assert(literalAt(marker) == Present(5L))
        assert(parse(Chunk(Segment.Text(marker), Segment.Literal(bytes("inbox")), Segment.Text(bytes("")))).isSuccess)
    }

    "a literal whose octets differ from its announced count, or with nothing after it, is refused" in {
        val marker = Segment.Text(bytes("* LIST () \".\" {5}"))
        assert(parse(Chunk(marker, Segment.Literal(bytes("abcd")), Segment.Text(bytes("")))).isFailure)
        assert(parse(Chunk(marker, Segment.Literal(bytes("abcde")))).isFailure)
    }

    "encoding" - {
        "an atom when every character is ATOM-CHAR, a quoted string when printable ASCII, a literal otherwise" in {
            assert(astring("INBOX", literalPlus = false) == Chunk(Part.Text("INBOX")))
            assert(astring("Sent Items", literalPlus = false) == Chunk(Part.Text("\"Sent Items\"")))
            assert(astring("a\"b\\c", literalPlus = false) == Chunk(Part.Text("\"a\\\"b\\\\c\"")))
            assert(astring("", literalPlus = false) == Chunk(Part.Text("\"\"")))
            assert(astring("café", literalPlus = false) == Chunk(Part.Literal(octets("café"), synchronizing = true)))
            assert(astring("café", literalPlus = true) == Chunk(Part.Literal(octets("café"), synchronizing = false)))
            assert(astring("a{b", literalPlus = false) == Chunk(Part.Text("\"a{b\"")))
        }
        "UID sets are sorted, deduplicated and compressed into ranges" in {
            assert(uidSets(Chunk(9L, 1L, 2L, 3L, 5L, 3L, 4L, 11L), Int.MaxValue) == Chunk("1:5,9,11"))
            assert(uidSets(Chunk(7L), Int.MaxValue) == Chunk("7"))
        }
        "UID sets are cut between ranges to stay within the room given, never inside one" in {
            assert(uidSets(Chunk(1L, 2L, 3L, 5L, 7L, 9L), 6) == Chunk("1:3,5", "7,9"))
            assert(uidSets(Chunk(1L, 2L, 3L), 1) == Chunk("1:3"))
        }
        "mailbox names go out in modified UTF-7 for IMAP4rev1 and as UTF-8 for an IMAP4rev2-only server" in {
            assert(mailbox(EmailLiterals.mailboxOf("Entwürfe"), utf8 = false) == Chunk(Part.Text("Entw&APw-rfe")))
            assert(mailbox(EmailLiterals.mailboxOf("Entwürfe"), utf8 = true) ==
                Chunk(Part.Literal(octets("Entwürfe"), synchronizing = true)))
        }
        "a verbatim mailbox name goes out as the octets the server sent, in either mode" in {
            val ascii = Email.MailboxName.fromWire(octets("Tom&Jerry"), utf8 = false).getOrElse(fail("not read"))
            val eight = Email.MailboxName.fromWire(Chunk('a'.toByte, 0xe9.toByte), utf8 = true).getOrElse(fail("not read"))
            Seq(false, true).foreach { utf8 =>
                assert(mailbox(ascii, utf8) == Chunk(Part.Text("Tom&Jerry")))
                assert(mailbox(eight, utf8) == Chunk(Part.Literal(Chunk('a'.toByte, 0xe9.toByte), synchronizing = true)))
            }
        }
        "internal dates read as instants, with their zone" in {
            assert(internalDate("17-Jul-1996 02:44:25 -0700") == Present(EmailLiterals.instantOf("1996-07-17T09:44:25Z")))
            assert(internalDate(" 1-Jan-2024 00:00:00 +0000") == Present(EmailLiterals.instantOf("2024-01-01T00:00:00Z")))
            assert(internalDate("32-Jul-1996 02:44:25 -0700") == Absent)
            assert(internalDate("17-Jux-1996 02:44:25 -0700") == Absent)
        }
    }

end ImapCodecTest
