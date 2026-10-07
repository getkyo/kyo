package kyo.internal.email.mime

import kyo.*
import kyo.EmailRfc5322Examples.*

class AddressCodecTest extends kyo.test.Test[Any]:

    private def fieldOf(value: String): HeaderCodec.Field =
        HeaderCodec.readSection(Span.from(("X: " + value + "\r\n\r\n").getBytes("UTF-8")), 0).fields(0)

    private def read(value: String): Chunk[Email.Address] = AddressCodec.parse(fieldOf(value))

    private def header(message: String, name: String): Chunk[Email.Address] =
        HeaderCodec.readSection(Span.from(message.getBytes("UTF-8")), 0).fields.find(_.name == name) match
            case Some(field) => AddressCodec.parse(field)
            case None        => throw new IllegalStateException(s"no $name")

    private def a(address: String, name: String): Email.Address = Email.Address(address, Present(name))
    private def a(address: String): Email.Address               = Email.Address(address)

    private def written(addresses: Chunk[Email.Address]): String =
        AddressCodec.render(addresses).flatMap(units => HeaderCodec.fold("To", units)) match
            case Result.Success(field) => field
            case other                 => throw new IllegalStateException(s"$addresses: $other")

    private def readBack(field: String): Chunk[Email.Address] =
        AddressCodec.parse(HeaderCodec.readSection(Span.from((field + "\r\n").getBytes("UTF-8")), 0).fields(0))

    "RFC 5322 appendix A" - {
        "A.1.1: a name-addr in From and To, and a Sender" in {
            assert(header(simple, "From") == Chunk(a("jdoe@machine.example", "John Doe")))
            assert(header(simple, "To") == Chunk(a("mary@example.net", "Mary Smith")))
            assert(header(withSender, "Sender") == Chunk(a("mjones@machine.example", "Michael Jones")))
        }
        "A.1.2: a quoted name with a period, a bare addr-spec, Who? as an atom, and a quoted name with quoted pairs" in {
            val message = mailboxes
            assert(header(message, "From") == Chunk(a("john.q.public@example.com", "Joe Q. Public")))
            assert(header(message, "To") == Chunk(a("mary@x.test", "Mary Smith"), a("jdoe@example.org"), a("one@y.test", "Who?")))
            assert(header(message, "Cc") == Chunk(a("boss@nil.test"), a("sysservices@example.net", "Giant; \"Big\" Box")))
        }
        "A.1.3: a group flattened into its members, and an empty group" in {
            val message = groups
            assert(header(message, "To") == Chunk(a("c@a.test", "Ed Jones"), a("joe@where.test"), a("jdoe@one.test", "John")))
            assert(header(message, "Cc") == Chunk.empty)
        }
        "A.2: a quoted name holding a colon" in {
            assert(header(reply, "Reply-To") == Chunk(a("smith@home.example", "Mary Smith: Personal Account")))
        }
        "A.5: comments everywhere, removed; a comment after a display name is not the name" in {
            val message = oddities
            assert(header(message, "From") == Chunk(a("pete@silly.test", "Pete")))
            assert(header(message, "To") == Chunk(a("c@public.example", "Chris Jones"), a("joe@example.org"), a("jdoe@one.test", "John")))
            assert(header(message, "Cc") == Chunk.empty)
        }
        "A.6.1: an unquoted name with a period, a route dropped, a null member skipped, CFWS around a dot removed" in {
            val message = obsoleteAddressing
            assert(header(message, "From") == Chunk(a("john.q.public@example.com", "Joe Q. Public")))
            assert(header(message, "To") == Chunk(a("mary@example.net", "Mary Smith"), a("jdoe@test.example")))
        }
        "A.6.3: a comment inside the domain, and a field folded over a line of white space only" in {
            val message = obsoleteWhiteSpace
            assert(header(message, "From") == Chunk(a("jdoe@machine.example", "John Doe")))
            assert(header(message, "To") == Chunk(a("mary@example.net", "Mary Smith")))
        }
    }

    "reading" - {
        "a mailbox with no display name followed by a comment takes the comment, in both forms" in {
            assert(read("ada@example.com (Ada Lovelace)") == Chunk(a("ada@example.com", "Ada Lovelace")))
            assert(read("<mailto:moderator@host.com> (Postings are Moderated)") ==
                Chunk(a("mailto:moderator@host.com", "Postings are Moderated")))
            assert(read("(nobody (that I know) \\) here) a@b") == Chunk(a("a@b")))
            assert(read("a@b (x (nested) =?utf-8?q?=C3=A9?=)") == Chunk(a("a@b", "x (nested) é")))
        }
        "a quoted local part keeps its quotes, and a domain literal its brackets" in {
            assert(read("\"ada lovelace\"@example.com") == Chunk(a("\"ada lovelace\"@example.com")))
            assert(read("Ada <ada@[192.0.2.1]>") == Chunk(a("ada@[192.0.2.1]", "Ada")))
        }
        "null members anywhere in the list are skipped" in {
            assert(read(", a@b, , (c) ,c@d,") == Chunk(a("a@b"), a("c@d")))
        }
        "encoded words in a display name are decoded, adjacent ones joined, inside a quoted string too" in {
            assert(read("=?ISO-8859-1?Q?Keld_J=F8rn_Simonsen?= <keld@dkuug.dk>") == Chunk(a("keld@dkuug.dk", "Keld Jørn Simonsen")))
            assert(read("=?ISO-8859-1?Q?Andr=E9?= Pirard <p@x>") == Chunk(a("p@x", "André Pirard")))
            assert(read("=?utf-8?q?a?=\r\n   =?utf-8?q?b?= <a@b>") == Chunk(a("a@b", "ab")))
            assert(read("\"foo =?utf-8?q?bar?=\" <a@b>") == Chunk(a("a@b", "foo bar")))
            assert(read("\"John\" =?utf-8?q?Doe?= <a@b>") == Chunk(a("a@b", "John Doe")))
        }
        "the words of a display name are joined by one SP per run of CFWS, quoted strings keep their white space" in {
            assert(read("Mary   (x)  Smith <m@x>") == Chunk(a("m@x", "Mary Smith")))
            assert(read("\"  James Smythe\" <j@x>") == Chunk(a("j@x", "  James Smythe")))
            assert(read("\"\" <j@x>") == Chunk(a("j@x", "")))
        }
        "an unquoted comma: an element with neither @ nor < joins the next name-addr's display name" in {
            assert(read("Lovelace, Ada <ada@x>") == Chunk(a("ada@x", "Lovelace, Ada")))
            assert(read("Byron, Augusta, Ada <ada@x>, b@y") == Chunk(a("ada@x", "Byron, Augusta, Ada"), a("b@y")))
            assert(read("Lovelace, <ada@x>") == Chunk(a("ada@x", "Lovelace")))
        }
        "an element that is not a mailbox is kept as its text, CFWS at either end removed" in {
            assert(read("Pat Example (Office), b@y") == Chunk(a("Pat Example"), a("b@y")))
            assert(read("not an address") == Chunk(a("not an address")))
            assert(read("<unclosed@x") == Chunk(a("<unclosed@x")))
            assert(read("a@b@c") == Chunk(a("a@b@c")))
        }
        "a local part and domain in UTF-8 are read like ASCII ones (RFC 6532)" in {
            assert(read("Jörg <jörg@bücher.example>") == Chunk(a("jörg@bücher.example", "Jörg")))
        }
        "pathological input: unclosed quotes, comments and literals run to the end, and runs of specials are read in one pass" in {
            assert(read("\"unclosed, a@b") == Chunk(a("\"unclosed, a@b")))
            assert(read("(unclosed a@b") == Chunk.empty)
            assert(read("a@[1.2, b@c") == Chunk(a("a@[1.2, b@c")))
            assert(read("<<<a@b>>>") == Chunk(a("<<<a@b>>>")))
            assert(read("," * 10000) == Chunk.empty)
            assert(read(":;" * 5000) == Chunk.empty)
            assert(read("(" * 10000 + "a@b") == Chunk.empty)
            assert(read("@" * 1000) == Chunk(a("@" * 1000)))
            assert(read("a@b " + "(x)" * 10000) == Chunk(a("a@b", "x")))
            assert(read(Seq.fill(10000)("n").mkString(", ")).size == 10000)
        }
        "groups: several, nested lists after them, and a group with no ;" in {
            assert(read("List 1: a@t, b@t; List 2: c@t; d@t, e@t") == Chunk(a("a@t"), a("b@t"), a("c@t"), a("d@t"), a("e@t")))
            assert(read("Team: x@t, y@t") == Chunk(a("x@t"), a("y@t")))
        }
    }

    "rendering" - {
        "an address with no name is its addr-spec; a name through the phrase writer; commas between" in {
            assert(AddressCodec.render(Chunk(a("a@b"))) == Result.succeed(Chunk("a@b")))
            assert(AddressCodec.render(Chunk(a("ada@x", "Ada Lovelace"), a("b@y"))) ==
                Result.succeed(Chunk("Ada", " Lovelace", " <ada@x>,", " b@y")))
            assert(AddressCodec.render(Chunk(a("ada@x", "Lovelace, Ada"))) == Result.succeed(Chunk("\"Lovelace, Ada\"", " <ada@x>")))
            assert(AddressCodec.render(Chunk(a("ada@x", ""))) == Result.succeed(Chunk("\"\"", " <ada@x>")))
        }
        "an address that is not an addr-spec fails with InvalidAddress" in {
            Seq(
                "a b@c",
                "no-at",
                "a@b\r\nBcc: v@x",
                "@b",
                "a@",
                "a..b@c",
                "\"unterminated@c",
                "a@b c",
                "a@[1.2.3.4",
                "a@b@c",
                "(c)a@b",
                "a\u0000@b"
            )
                .foreach { address =>
                    assert(AddressCodec.render(Chunk(a(address))) == Result.fail(HeaderCodec.WriteFailure.InvalidAddress(address)), address)
                }
        }
        "isAddrSpec accepts a dot-atom, a quoted local part, a domain literal and UTF-8" in {
            Seq("a@b", "a.b-c+d@x.example", "\"a b\\\"c\"@x", "a@[192.0.2.1]", "jörg@bücher.example", "!#$%&'*+-/=?^_`{|}~@x")
                .foreach(address => assert(AddressCodec.isAddrSpec(address), address))
        }
        "non-ASCII address text starts at U+00A0: the C1 controls U+0080 to U+009F are refused in every position" in {
            Seq("\u00a0@x", "\"\u00a0\"@x", "\"\\\u00a0\"@x", "a@[\u00a0]", "\u00ff@x")
                .foreach(address => assert(AddressCodec.isAddrSpec(address), address))
            Seq('\u0080', '\u0085', '\u009f').foreach { c =>
                Seq(s"$c@x", s"\"$c\"@x", s"\"\\$c\"@x", s"a@[$c]", s"a@$c").foreach(address =>
                    assert(!AddressCodec.isAddrSpec(address), address)
                )
            }
        }
        "generated addresses and names read back equal" in {
            val locals  = Seq("a", "ada.lovelace", "x+tag", "\"ada lovelace\"", "\"q\\\"uote\"", "jörg", "o'brien", "_1")
            val domains = Seq("example.com", "bücher.example", "[192.0.2.1]", "x")
            val names   = Seq(
                "Ada Lovelace",
                "Lovelace, Ada",
                "Jürgen",
                " padded ",
                "a\r\nb",
                "=?utf-8?q?x?=",
                "Who?",
                "Joe Q. Public",
                "",
                "漢字 😀",
                "x" * 90
            )
            val address =
                for
                    local  <- Random.nextValue(locals)
                    domain <- Random.nextValue(domains)
                    bare   <- Random.nextBoolean
                    named  <- if bare then Kyo.lift[Email.Address, Sync](a(s"$local@$domain"))
                    else Random.nextValue(names).map(a(s"$local@$domain", _))
                yield named
            Random.withSeed(0x61646472)(Kyo.fill(500)(Random.nextInt(5).map(n => Kyo.fill(n + 1)(address)))).map { drawn =>
                drawn.foreach { addresses =>
                    val field = written(addresses)
                    assert(readBack(field) == addresses, field)
                }
                succeed
            }
        }
    }

end AddressCodecTest
