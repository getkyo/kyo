package kyo.internal.email.mime

import kyo.*
import kyo.internal.email.mime.MimeParser.Body
import kyo.internal.email.mime.MimeParser.Encoding
import kyo.internal.email.mime.MimeParser.Entity
import kyo.internal.email.vectors.*

class MimeParserTest extends kyo.test.Test[Any]:

    private def bytes(text: String): Span[Byte] = Span.from(text.getBytes("ISO-8859-1"))

    private val defaults = EmailLiterals.valid(MediaTypeDefaults.built)

    private def tree(input: Span[Byte]): Entity =
        MimeParser.parse(input, defaults) match
            case Result.Success(entity) => entity
            case other                  => throw new IllegalStateException(other.toString)

    private def tree(text: String): Entity = tree(bytes(text))

    private def crlf(lines: String*): String = lines.mkString("", "\r\n", "\r\n")

    // The media types of the tree in document order, each multipart followed by its children in brackets.
    private def shape(entity: Entity): String =
        entity.body match
            case Body.Leaf(_, _)          => entity.mediaType.baseType
            case Body.Multipart(children) => entity.mediaType.baseType + children.map(shape).mkString("[", ",", "]")

    private def at(entity: Entity, path: Int*): Entity =
        path.foldLeft(entity) { (e, i) =>
            e.body match
                case Body.Multipart(children) => children(i - 1)
                case _                        => throw new IllegalStateException(s"no part $i under ${e.path}")
        }

    private def raw(input: Span[Byte], entity: Entity): String =
        entity.body match
            case Body.Leaf(start, end) => new String(input.slice(start, end).toArray, "ISO-8859-1")
            case _                     => throw new IllegalStateException(s"${entity.path} is a multipart")

    private def raw(text: String, path: Int*): String =
        val input = bytes(text)
        raw(input, at(tree(input), path*))

    private def decoded(input: Span[Byte], entity: Entity): Span[Byte] =
        (entity.body, entity.encoding) match
            case (Body.Leaf(start, end), Encoding.Known(kind)) =>
                EmailLiterals.valid(TransferEncoding.decode(kind, input, start, end)).content
            case (Body.Leaf(start, end), Encoding.Unknown(_)) => input.slice(start, end)
            case _                                            => throw new IllegalStateException(s"${entity.path} is a multipart")

    private def isLeaf(entity: Entity): Boolean = entity.body match
        case Body.Leaf(_, _) => true
        case _               => false

    "RFC examples" - {
        "RFC 2046 5.1.1: two text parts, one typed implicitly; part 1 does NOT end with a linebreak, part 2 does" in {
            val text = crlf(
                "From: Nathaniel Borenstein <nsb@bellcore.com>",
                "To: Ned Freed <ned@innosoft.com>",
                "Date: Sun, 21 Mar 1993 23:56:48 -0800 (PST)",
                "Subject: Sample message",
                "MIME-Version: 1.0",
                "Content-type: multipart/mixed; boundary=\"simple boundary\"",
                "",
                "This is the preamble.  It is to be ignored, though it",
                "is a handy place for composition agents to include an",
                "explanatory note to non-MIME conformant readers.",
                "",
                "--simple boundary",
                "",
                "This is implicitly typed plain US-ASCII text.",
                "It does NOT end with a linebreak.",
                "--simple boundary",
                "Content-type: text/plain; charset=us-ascii",
                "",
                "This is explicitly typed plain US-ASCII text.",
                "It DOES end with a linebreak.",
                "",
                "--simple boundary--",
                "",
                "This is the epilogue.  It is also to be ignored."
            )
            val input = bytes(text)
            val root  = tree(input)
            assert(shape(root) == "multipart/mixed[text/plain,text/plain]")
            assert(at(root, 1).mediaType.parameter("charset") == Present("us-ascii"))
            assert(at(root, 1).fields.isEmpty)
            assert(raw(input, at(root, 1)) == "This is implicitly typed plain US-ASCII text.\r\nIt does NOT end with a linebreak.")
            assert(raw(input, at(root, 2)) == "This is explicitly typed plain US-ASCII text.\r\nIt DOES end with a linebreak.\r\n")
            assert(at(root, 1).path == Chunk(1) && at(root, 2).path == Chunk(2))
        }
        "RFC 2046 5.1.4: three alternatives in order" in {
            val text = crlf(
                "From: Nathaniel Borenstein <nsb@bellcore.com>",
                "To: Ned Freed <ned@innosoft.com>",
                "Date: Mon, 22 Mar 1993 09:41:09 -0800 (PST)",
                "Subject: Formatted text mail",
                "MIME-Version: 1.0",
                "Content-Type: multipart/alternative; boundary=boundary42",
                "",
                "--boundary42",
                "Content-Type: text/plain; charset=us-ascii",
                "",
                "  ... plain text version of message goes here ...",
                "",
                "--boundary42",
                "Content-Type: text/enriched",
                "",
                "  ... RFC 1896 text/enriched version of same message",
                "      goes here ...",
                "",
                "--boundary42",
                "Content-Type: application/x-whatever",
                "",
                "  ... fanciest version of same message goes here ...",
                "",
                "--boundary42--"
            )
            assert(shape(tree(text)) == "multipart/alternative[text/plain,text/enriched,application/x-whatever]")
        }
        "RFC 2046 5.1.5: a digest inside mixed, whose entries have no headers and default to message/rfc822" in {
            val text = crlf(
                "From: Moderator-Address",
                "To: Recipient-List",
                "Date: Mon, 22 Mar 1994 13:34:51 +0000",
                "Subject: Internet Digest, volume 42",
                "MIME-Version: 1.0",
                "Content-Type: multipart/mixed;",
                "              boundary=\"---- main boundary ----\"",
                "",
                "------ main boundary ----",
                "",
                "  ...Introductory text or table of contents...",
                "",
                "------ main boundary ----",
                "Content-Type: multipart/digest;",
                "              boundary=\"---- next message ----\"",
                "",
                "------ next message ----",
                "",
                "From: someone-else",
                "Date: Fri, 26 Mar 1993 11:13:32 +0200",
                "Subject: my opinion",
                "",
                "  ...body goes here ...",
                "",
                "------ next message ----",
                "",
                "From: someone-else-again",
                "Date: Fri, 26 Mar 1993 10:07:13 -0500",
                "Subject: my different opinion",
                "",
                "  ... another body goes here ...",
                "",
                "------ next message ------",
                "",
                "------ main boundary ------"
            )
            val input = bytes(text)
            val root  = tree(input)
            assert(shape(root) == "multipart/mixed[text/plain,multipart/digest[message/rfc822,message/rfc822]]")
            assert(raw(input, at(root, 2, 1)).startsWith("From: someone-else\r\nDate: Fri, 26 Mar 1993"))
            assert(raw(input, at(root, 2, 2)).endsWith("... another body goes here ...\r\n"))
        }
        "RFC 2049 appendix A: five parts, a parallel multipart inside, a header split by a page break" in {
            val text = crlf(
                "MIME-Version: 1.0",
                "From: Nathaniel Borenstein <nsb@nsb.fv.com>",
                "To: Ned Freed <ned@innosoft.com>",
                "Date: Fri, 07 Oct 1994 16:15:05 -0700 (PDT)",
                "Subject: A multipart example",
                "Content-Type: multipart/mixed;",
                "              boundary=unique-boundary-1",
                "",
                "This is the preamble area of a multipart message.",
                "Mail readers that understand multipart format",
                "should ignore this preamble.",
                "",
                "If you are reading this text, you might want to",
                "consider changing to a mail reader that understands",
                "how to properly display multipart messages.",
                "",
                "--unique-boundary-1",
                "",
                "  ... Some text appears here ...",
                "",
                "[Note that the blank between the boundary and the start",
                " of the text in this part means no header fields were",
                " given and this is text in the US-ASCII character set.",
                " It could have been done with explicit typing as in the",
                " next part.]",
                "",
                "--unique-boundary-1",
                "Content-type: text/plain; charset=US-ASCII",
                "",
                "This could have been part of the previous part, but",
                "illustrates explicit versus implicit typing of body",
                "parts.",
                "",
                "--unique-boundary-1",
                "Content-Type: multipart/parallel; boundary=unique-boundary-2",
                "",
                "--unique-boundary-2",
                "Content-Type: audio/basic",
                "Content-Transfer-Encoding: base64",
                "",
                "  ... base64-encoded 8000 Hz single-channel",
                "      mu-law-format audio data goes here ...",
                "",
                "--unique-boundary-2",
                "Content-Type: image/jpeg",
                "Content-Transfer-Encoding: base64",
                "",
                "  ... base64-encoded image data goes here ...",
                "",
                "--unique-boundary-2--",
                "",
                "--unique-boundary-1",
                "Content-type: text/enriched",
                "",
                "This is <bold><italic>enriched.</italic></bold>",
                "<smaller>as defined in RFC 1896</smaller>",
                "",
                "Isn't it",
                "<bigger><bigger>cool?</bigger></bigger>",
                "",
                "--unique-boundary-1",
                "Content-Type: message/rfc822",
                "",
                "From: (mailbox in US-ASCII)",
                "To: (address in US-ASCII)",
                "Subject: (subject in US-ASCII)",
                "Content-Type: Text/plain; charset=ISO-8859-1",
                "Content-Transfer-Encoding: Quoted-printable",
                "",
                "  ... Additional text in ISO-8859-1 goes here ...",
                "",
                "--unique-boundary-1--"
            )
            val root = tree(text)
            assert(shape(root) ==
                "multipart/mixed[text/plain,text/plain,multipart/parallel[audio/basic,image/jpeg],text/enriched,message/rfc822]")
            assert(at(root, 3, 1).encoding == Encoding.Known(TransferEncoding.Kind.Base64))
            assert(at(root, 3, 2).path == Chunk(3, 2))
            assert(isLeaf(at(root, 5)))
        }
        "RFC 2183 3: an inline and an attachment disposition, the dangling ; accepted" in {
            val inline = tree(crlf(
                "Content-Type: image/jpeg",
                "Content-Disposition: inline",
                "Content-Description: just a small picture of me",
                "",
                " <jpeg data>"
            ))
            assert(inline.disposition.map(_.kind) == Present(HeaderCodec.DispositionKind.Inline))
            val attachment = tree(crlf(
                "Content-Type: image/jpeg",
                "Content-Disposition: attachment; filename=genome.jpeg;",
                "  modification-date=\"Wed, 12 Feb 1997 16:29:51 -0500\";",
                "Content-Description: a complete map of the human genome",
                "",
                "<jpeg data>"
            ))
            assert(attachment.disposition.map(_.kind) == Present(HeaderCodec.DispositionKind.Attachment))
            assert(attachment.disposition.map(_.parameters.toMap.get("filename")) == Present(Some("genome.jpeg")))
            assert(attachment.mediaType.baseType == "image/jpeg")
        }
        "RFC 2387 5.1: related, the records as written and the base64 part decoding to 161 octets" in {
            val text = crlf(
                "Content-Type: Multipart/Related; boundary=example-1",
                "        start=\"<950120.aaCC@XIson.com>\";",
                "        type=\"Application/X-FixedRecord\"",
                "        start-info=\"-o ps\"",
                "",
                "--example-1",
                "Content-Type: Application/X-FixedRecord",
                "Content-ID: <950120.aaCC@XIson.com>",
                "",
                "25",
                "10",
                "34",
                "10",
                "25",
                "21",
                "26",
                "10",
                "--example-1",
                "Content-Type: Application/octet-stream",
                "Content-Description: The fixed length records",
                "Content-Transfer-Encoding: base64",
                "Content-ID: <950120.aaCB@XIson.com>",
                "",
                "T2xkIE1hY0RvbmFsZCBoYWQgYSBmYXJtCkUgSS",
                "BFIEkgTwpBbmQgb24gaGlzIGZhcm0gaGUgaGFk",
                "IHNvbWUgZHVja3MKRSBJIEUgSSBPCldpdGggYS",
                "BxdWFjayBxdWFjayBoZXJlLAphIHF1YWNrIHF1",
                "YWNrIHRoZXJlLApldmVyeSB3aGVyZSBhIHF1YW",
                "NrIHF1YWNrCkUgSSBFIEkgTwo=",
                "",
                "--example-1--"
            )
            val input = bytes(text)
            val root  = tree(input)
            assert(shape(root) == "multipart/related[application/x-fixedrecord,application/octet-stream]")
            assert(raw(input, at(root, 1)) == "25\r\n10\r\n34\r\n10\r\n25\r\n21\r\n26\r\n10")
            val records = decoded(input, at(root, 2))
            assert(records.size == 161)
            assert(new String(records.toArray, "US-ASCII").startsWith("Old MacDonald had a farm\n"))
        }
    }

    "header sections" - {
        "a line with no colon ends the section and is the first line of the body; the mbox From line is one" in {
            val text  = crlf("Subject: a", "From alice@example.com Mon Jan  1 12:34:56 2024", "body")
            val input = bytes(text)
            val root  = tree(input)
            assert(root.fields.map(_.name) == Chunk("Subject"))
            assert(raw(input, root) == "From alice@example.com Mon Jan  1 12:34:56 2024\r\nbody\r\n")
        }
        "a first line starting with white space gives no headers and a body of the whole input" in {
            val text = crlf(" Subject: a", "b")
            assert(tree(text).fields.isEmpty)
            assert(raw(text) == text)
        }
        "a section with no empty line, ending at the end of the input: every line a header, the body empty" in {
            val text = crlf("Subject: a", "X: b")
            val root = tree(text)
            assert(root.fields.map(_.name) == Chunk("Subject", "X"))
            assert(raw(text) == "")
        }
        "the empty input is a message with no headers and an empty body" in {
            val root = tree("")
            assert(root.fields.isEmpty && raw("") == "" && root.mediaType.baseType == "text/plain")
        }
    }

    "content types" - {
        "no Content-Type, and one that does not read, are text/plain; charset=us-ascii" in {
            Seq(crlf("Subject: a", "", "b"), crlf("Content-Type: text", "", "b"), crlf("Content-Type: /plain", "", "b")).foreach { text =>
                val root = tree(text)
                assert(root.mediaType.baseType == "text/plain" && root.mediaType.parameter("charset") == Present("us-ascii"), text)
            }
        }
        "the first of two Content-Type, Content-Transfer-Encoding and Content-Disposition fields counts" in {
            val root = tree(crlf(
                "Content-Type: text/html",
                "Content-Type: image/png",
                "Content-Transfer-Encoding: base64",
                "Content-Transfer-Encoding: 7bit",
                "Content-Disposition: inline",
                "Content-Disposition: attachment",
                "",
                "b"
            ))
            assert(root.mediaType.baseType == "text/html")
            assert(root.encoding == Encoding.Known(TransferEncoding.Kind.Base64))
            assert(root.disposition.map(_.kind) == Present(HeaderCodec.DispositionKind.Inline))
            assert(root.fields.size == 6)
        }
        "no Content-Transfer-Encoding is 7bit; an unknown one is kept as written" in {
            assert(tree(crlf("Subject: a", "", "b")).encoding == Encoding.Known(TransferEncoding.Kind.SevenBit))
            assert(tree(crlf("Content-Transfer-Encoding: x-uuencode", "", "b")).encoding == Encoding.Unknown("x-uuencode"))
        }
    }

    "multipart structure" - {
        "text after the boundary on a delimiter line, transport padding included, is ignored and the line is a delimiter" in {
            val text = crlf(
                "Content-Type: multipart/mixed; boundary=b",
                "",
                "--b junk",
                "",
                "one",
                "--b  \t",
                "",
                "two",
                "--b-- trailing",
                "epilogue"
            )
            val root = tree(text)
            assert(shape(root) == "multipart/mixed[text/plain,text/plain]")
            assert(raw(text, 1) == "one")
            assert(raw(text, 2) == "two")
        }
        "a boundary that is a prefix of another part's boundary: the longest match wins, both ways round" in {
            val outerShort = crlf(
                "Content-Type: multipart/mixed; boundary=b",
                "",
                "--b",
                "Content-Type: multipart/alternative; boundary=b1",
                "",
                "--b1",
                "",
                "inner one",
                "--b1",
                "",
                "inner two",
                "--b1--",
                "--b",
                "",
                "outer two",
                "--b--"
            )
            assert(shape(tree(outerShort)) == "multipart/mixed[multipart/alternative[text/plain,text/plain],text/plain]")
            assert(raw(outerShort, 1, 2) == "inner two")
            assert(raw(outerShort, 2) == "outer two")
            val outerLong = crlf(
                "Content-Type: multipart/mixed; boundary=b1",
                "",
                "--b1",
                "Content-Type: multipart/alternative; boundary=b",
                "",
                "--b",
                "",
                "inner one",
                "--b1",
                "",
                "outer two",
                "--b1--"
            )
            assert(shape(tree(outerLong)) == "multipart/mixed[multipart/alternative[text/plain],text/plain]")
            assert(raw(outerLong, 1, 1) == "inner one")
            assert(raw(outerLong, 2) == "outer two")
        }
        "a delimiter of an outer multipart closes every inner one and its current part" in {
            val text = crlf(
                "Content-Type: multipart/mixed; boundary=outer",
                "",
                "--outer",
                "Content-Type: multipart/alternative; boundary=inner",
                "",
                "--inner",
                "",
                "unclosed inner part",
                "--outer",
                "",
                "second",
                "--outer--"
            )
            assert(shape(tree(text)) == "multipart/mixed[multipart/alternative[text/plain],text/plain]")
            assert(raw(text, 1, 1) == "unclosed inner part")
        }
        "nested multiparts reusing the outer boundary: the innermost open one owns its delimiters until it closes" in {
            val text = crlf(
                "Content-Type: multipart/mixed; boundary=same",
                "",
                "--same",
                "Content-Type: multipart/alternative; boundary=same",
                "",
                "--same",
                "",
                "a",
                "--same",
                "",
                "b",
                "--same--",
                "--same",
                "",
                "c",
                "--same--"
            )
            assert(shape(tree(text)) == "multipart/mixed[multipart/alternative[text/plain,text/plain],text/plain]")
            assert(raw(text, 1, 2) == "b")
            assert(raw(text, 2) == "c")
        }
        "white space at the end of a boundary parameter is removed before matching" in {
            val text = crlf("Content-Type: multipart/mixed; boundary=\"b  \"", "", "--b", "", "one", "--b--")
            assert(shape(tree(text)) == "multipart/mixed[text/plain]")
            assert(raw(text, 1) == "one")
        }
        "the line end before a delimiter belongs to it; bare LF and CRLF mixed" in {
            val text = "Content-Type: multipart/mixed; boundary=b\n\n--b\r\n\r\none\n\n--b\n\ntwo\r\n--b--\n"
            assert(raw(text, 1) == "one\n")
            assert(raw(text, 2) == "two")
        }
        "preamble and epilogue are not part of any part" in {
            val text =
                crlf("Content-Type: multipart/mixed; boundary=b", "", "preamble", "--b", "", "one", "--b--", "epilogue", "--b", "", "late")
            assert(shape(tree(text)) == "multipart/mixed[text/plain]")
            assert(raw(text, 1) == "one")
        }
        "a closing delimiter that never comes: the last part runs to the end of the input" in {
            val text = crlf("Content-Type: multipart/mixed; boundary=b", "", "--b", "", "one", "two")
            assert(raw(text, 1) == "one\r\ntwo\r\n")
        }
        "a multipart with no boundary, an empty one, or one that never occurs is one leaf of its declared type holding the body" in {
            Seq("", "; boundary=\"\"", "; boundary=never").foreach { parameter =>
                val text  = crlf(s"Content-Type: multipart/mixed$parameter", "", "preamble", "--other", "body")
                val input = bytes(text)
                val root  = tree(input)
                assert(isLeaf(root) && root.mediaType.baseType == "multipart/mixed", parameter)
                assert(raw(input, root) == crlf("preamble", "--other", "body"), parameter)
            }
        }
        "an inner multipart whose boundary never occurs is one leaf ending at the outer delimiter" in {
            val text = crlf(
                "Content-Type: multipart/mixed; boundary=outer",
                "",
                "--outer",
                "Content-Type: multipart/alternative; boundary=inner",
                "",
                "AAA",
                "",
                "--outer--"
            )
            val root = tree(text)
            assert(shape(root) == "multipart/mixed[multipart/alternative]")
            assert(raw(text, 1) == "AAA\r\n")
        }
        "a multipart with only a closing delimiter has no parts" in {
            val root = tree(crlf("Content-Type: multipart/mixed; boundary=b", "", "preamble", "--b--", "epilogue"))
            assert(root.body == Body.Multipart(Chunk.empty))
        }
        "a part with no headers is text/plain, or message/rfc822 in a digest; so is a part whose Content-Type does not read" in {
            val mixed =
                tree(crlf("Content-Type: multipart/mixed; boundary=b", "", "--b", "", "x", "--b", "Content-Type: bad", "", "y", "--b--"))
            assert(shape(mixed) == "multipart/mixed[text/plain,text/plain]")
            val digest =
                tree(crlf("Content-Type: multipart/digest; boundary=b", "", "--b", "", "x", "--b", "Content-Type: bad", "", "y", "--b--"))
            assert(shape(digest) == "multipart/digest[message/rfc822,message/rfc822]")
        }
        "a part whose headers run straight into the next delimiter has those headers and empty content" in {
            val text = crlf("Content-Type: multipart/mixed; boundary=\"b:c\"", "", "--b:c", "Content-Type: text/html", "--b:c--")
            val root = tree(text)
            assert(shape(root) == "multipart/mixed[text/html]")
            assert(at(root, 1).fields.map(_.name) == Chunk("Content-Type"))
            assert(raw(text, 1) == "")
        }
        "message/rfc822 is a leaf: the delimiters of the message inside open no multipart" in {
            val text = crlf(
                "Content-Type: multipart/mixed; boundary=outer",
                "",
                "--outer",
                "Content-Type: message/rfc822",
                "",
                "Content-Type: multipart/mixed; boundary=inner",
                "",
                "--inner",
                "",
                "enclosed",
                "--inner--",
                "--outer--"
            )
            val root = tree(text)
            assert(shape(root) == "multipart/mixed[message/rfc822]")
            assert(raw(text, 1).startsWith("Content-Type: multipart/mixed; boundary=inner\r\n"))
        }
        "a multipart with a base64 Content-Transfer-Encoding is scanned for delimiters as it is, and without any it is a base64 leaf" in {
            val scanned =
                tree(crlf("Content-Type: multipart/mixed; boundary=b", "Content-Transfer-Encoding: base64", "", "--b", "", "x", "--b--"))
            assert(shape(scanned) == "multipart/mixed[text/plain]")
            val opaque = tree(crlf("Content-Type: multipart/mixed; boundary=b", "Content-Transfer-Encoding: base64", "", "eHl6"))
            assert(isLeaf(opaque) && opaque.encoding == Encoding.Known(TransferEncoding.Kind.Base64))
        }
        "paths are 1-based under the message, whose path is empty" in {
            val root = tree(crlf(
                "Content-Type: multipart/mixed; boundary=a",
                "",
                "--a",
                "",
                "1",
                "--a",
                "Content-Type: multipart/mixed; boundary=b",
                "",
                "--b",
                "",
                "2.1",
                "--b",
                "",
                "2.2",
                "--b--",
                "--a--"
            ))
            assert(root.path == Chunk.empty)
            assert(at(root, 2).path == Chunk(2) && at(root, 2, 2).path == Chunk(2, 2))
        }
    }

    "nesting" - {
        def nested(depth: Int): String =
            val open  = (1 to depth).map(i => crlf(s"Content-Type: multipart/mixed; boundary=b$i", "", s"--b$i")).mkString
            val close = (depth to 1 by -1).map(i => crlf(s"--b$i--")).mkString
            open + crlf("", "innermost") + close
        end nested
        "100 deep parses, and the innermost text is the leaf at depth 100" in {
            val text  = nested(100)
            val input = bytes(text)
            val leaf  = at(tree(input), Seq.fill(100)(1)*)
            assert(leaf.path == Chunk.fill(100)(1))
            assert(raw(input, leaf) == "innermost")
        }
        "101 deep fails with NestingTooDeep(100) and the path of the part that would open the 101st" in {
            MimeParser.parse(bytes(nested(101)), defaults) match
                case Result.Failure(ex) =>
                    assert(ex.problem == EmailMimeException.Problem.NestingTooDeep(100))
                    assert(ex.part == Chunk.fill(100)(1))
                case other => fail(other.toString)
        }
    }

    "pathological inputs" - {
        "10,000 headers, all read, in order" in {
            val text = (0 until 10000).map(i => s"X-$i: v\r\n").mkString + "\r\nbody"
            assert(tree(text).fields.map(_.name) == Chunk.from((0 until 10000).map(i => s"X-$i")))
        }
        "10,000 parts in one multipart, in order" in {
            val text = "Content-Type: multipart/mixed; boundary=b\r\n\r\n" + (0 until 10000).map(i => s"--b\r\n\r\n$i\r\n").mkString +
                "--b--\r\n"
            val input = bytes(text)
            val root  = tree(input)
            root.body match
                case Body.Multipart(children) =>
                    assert(children.size == 10000)
                    assert(children.zipWithIndex.forall((c, i) => raw(input, c) == i.toString && c.path == Chunk(i + 1)))
                case other => fail(other.toString)
            end match
        }
        "one megabyte with no line break, as the body and as a header" in {
            val long = "x" * (1 << 20)
            assert(raw(crlf("Subject: a", "") + long) == long)
            val root = tree(crlf("X-Long: " + long, "", "b"))
            assert(root.fields(0).value.length == long.length)
        }
        "a megabyte of lines starting with -- against 100 open boundaries sharing a long prefix" in {
            val prefix = "p" * 200
            val open   = (1 to 100).map(i => crlf(s"Content-Type: multipart/mixed; boundary=$prefix$i", "", s"--$prefix$i")).mkString
            val noise  = Seq.fill(4000)(crlf("--" + prefix + "x" * 50)).mkString
            val text   = open + crlf("") + noise + (100 to 1 by -1).map(i => crlf(s"--$prefix$i--")).mkString
            val input  = bytes(text)
            val leaf   = at(tree(input), Seq.fill(100)(1)*)
            assert(raw(input, leaf) == noise.dropRight(2))
        }
    }

    "mime4j goldens" - {
        import MimeParserMime4j.*

        lazy val listed = parse(EmbeddedMime4jDifferencesTsv.text)

        "every message has its golden tree, and every leaf a golden file" in {
            assert(messages.size == 31)
            assert(messages.forall(m => referenceLeaves(m).forall((_, file) => EmbeddedMime4jSet.files.contains(file))))
        }
        "every difference from mime4j is listed, with its reason" in {
            val unlisted = observed.filterNot(listed.contains)
            assert(unlisted.isEmpty, s"${unlisted.size} differences are not listed:\n${render(unlisted)}")
        }
        "every listed difference still occurs" in {
            val stale = listed.filterNot(observed.contains)
            assert(stale.isEmpty, s"${stale.size} listed differences no longer occur:\n${render(stale)}")
        }
        "the list has no duplicate rows and names only vendored messages" in {
            assert(listed.distinct.size == listed.size)
            assert(listed.forall(row => messages.contains(row.file)))
        }
    }

end MimeParserTest
