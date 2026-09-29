package kyo.mime

import kyo.*

class MultipartTest extends kyo.test.Test[Any]:

    private def bytes(s: String): Span[Byte] = Span.from(s.getBytes("UTF-8"))

    private val boundary = bytes("simple boundary")

    // RFC 2046 section 5.1.1's example body, with CRLF line ends.
    private val body = bytes(
        "This is the preamble.  It is to be ignored, though it\r\n" +
            "is a handy place for composition agents to include an\r\n" +
            "explanatory note to non-MIME conformant readers.\r\n" +
            "\r\n" +
            "--simple boundary\r\n" +
            "\r\n" +
            "This is implicitly typed plain US-ASCII text.\r\n" +
            "It does NOT end with a linebreak.\r\n" +
            "--simple boundary\r\n" +
            "Content-type: text/plain; charset=us-ascii\r\n" +
            "\r\n" +
            "This is explicitly typed plain US-ASCII text.\r\n" +
            "It DOES end with a linebreak.\r\n" +
            "\r\n" +
            "--simple boundary--\r\n" +
            "\r\n" +
            "This is the epilogue.  It is also to be ignored.\r\n"
    )

    private def indexOf(haystack: Span[Byte], needle: String, from: Int): Int =
        val n = needle.getBytes("UTF-8")
        (from to haystack.size - n.length).find(at => n.indices.forall(k => haystack(at + k) == n(k))).getOrElse(-1)

    "delimiters" - {
        "RFC 2046 5.1.1: the three delimiters of the example body are found at their line starts, the last one closing" in {
            val first  = Multipart.findDelimiter(body, boundary, 0)
            val second = Multipart.findDelimiter(body, boundary, first + 1)
            val third  = Multipart.findDelimiter(body, boundary, second + 1)
            val none   = Multipart.findDelimiter(body, boundary, third + 1)
            assert(first == indexOf(body, "--simple boundary\r\n\r\nThis is implicitly", 0))
            assert(second == indexOf(body, "--simple boundary\r\nContent-type", 0))
            assert(third == indexOf(body, "--simple boundary--", 0))
            assert(none == -1)
            assert(!Multipart.isCloseDelimiter(body, boundary, first))
            assert(!Multipart.isCloseDelimiter(body, boundary, second))
            assert(Multipart.isCloseDelimiter(body, boundary, third))
        }
        "the line end before a delimiter belongs to the delimiter, so the first part does not end with a linebreak" in {
            val first  = Multipart.findDelimiter(body, boundary, 0)
            val second = Multipart.findDelimiter(body, boundary, first + 1)
            val start  = Multipart.delimiterLineEnd(body, boundary, first)
            val part   = new String(body.toArray.slice(start, Multipart.partEndBefore(body, second)), "UTF-8")
            assert(part == "\r\nThis is implicitly typed plain US-ASCII text.\r\nIt does NOT end with a linebreak.")
        }
        "a delimiter is only at the start of a line: the same bytes mid-line are data" in {
            val b    = bytes("bound")
            val text = bytes("--bound\r\nsee --bound here\r\n--bound--")
            assert(Multipart.findDelimiter(text, b, 0) == 0)
            assert(Multipart.findDelimiter(text, b, 1) == text.size - 9)
            assert(!Multipart.isDelimiterAt(text, b, indexOf(text, "--bound here", 0)))
        }
        "a line end is LF with or without a CR before it" in {
            val b = bytes("b")
            assert(Multipart.findDelimiter(bytes("x\n--b"), b, 0) == 2)
            assert(Multipart.findDelimiter(bytes("x\r\n--b"), b, 0) == 3)
            assert(Multipart.findDelimiter(bytes("x\r--b"), b, 0) == -1)
            assert(Multipart.partEndBefore(bytes("x\n--b"), 2) == 1)
            assert(Multipart.partEndBefore(bytes("x\r\n--b"), 3) == 1)
            assert(Multipart.partEndBefore(bytes("--b"), 0) == 0)
        }
        "transport padding and anything else on the delimiter line is skipped by delimiterLineEnd" in {
            val b    = bytes("b")
            val text = bytes("--b  padding\r\nbody")
            assert(Multipart.delimiterLineEnd(text, b, 0) == 14)
            assert(Multipart.delimiterLineEnd(bytes("--b"), b, 0) == 3)
        }
        "a boundary that is a prefix of another does not match the longer one's delimiter, and the reverse" in {
            val text = bytes("--ab\r\n--a\r\n")
            assert(Multipart.findDelimiter(text, bytes("a"), 0) == 0)
            assert(Multipart.isDelimiterAt(text, bytes("a"), 0))
            assert(Multipart.findDelimiter(text, bytes("ab"), 0) == 0)
            assert(Multipart.findDelimiter(text, bytes("ab"), 1) == -1)
        }
        "the offsets a parser takes from a body quoting its own delimiter mid-line" in {
            val b      = bytes("b")
            val text   = bytes("--b\r\nhello --b there\r\n--b--")
            val first  = Multipart.findDelimiter(text, b, 0)
            val second = Multipart.findDelimiter(text, b, first + 1)
            assert(first == 0 && second == 22)
            assert(Multipart.delimiterLineEnd(text, b, first) == 5)
            assert(Multipart.partEndBefore(text, second) == 20)
            assert(Multipart.isCloseDelimiter(text, b, second))
        }
        "empty input and an empty boundary" in {
            assert(Multipart.findDelimiter(Span.empty[Byte], boundary, 0) == -1)
            assert(Multipart.findDelimiter(bytes("--"), Span.empty[Byte], 0) == 0)
        }
    }

    "boundary validity" - {
        "1 to 70 bchars, not ending in a space" in {
            assert(Multipart.isValidBoundary("simple boundary"))
            assert(Multipart.isValidBoundary("=_kyo0_1"))
            assert(Multipart.isValidBoundary("a" * 70))
            assert(!Multipart.isValidBoundary("a" * 71))
            assert(!Multipart.isValidBoundary(""))
            assert(!Multipart.isValidBoundary("ends in space "))
            assert(!Multipart.isValidBoundary("no\"quotes"))
            assert(!Multipart.isValidBoundary("noé"))
        }
    }

    "choosing a boundary" - {
        def parts(all: String*): Chunk[Chunk[Span[Byte]]] = Chunk.from(all).map(p => Chunk(bytes(p)))

        "picks the least number no line of an enclosed part starts with, deterministically" in {
            assert(Multipart.boundary("=_kyo0_", Chunk.empty) == "=_kyo0_0")
            assert(Multipart.boundary("=_kyo0_", parts("--=_kyo0_0\r\n")) == "=_kyo0_1")
            assert(Multipart.boundary("=_kyo0_", parts("x\r\n--=_kyo0_0", "--=_kyo0_1 junk\r\n")) == "=_kyo0_2")
            assert(Multipart.boundary("=_kyo0_", parts("--=_kyo0_0")) == Multipart.boundary("=_kyo0_", parts("--=_kyo0_0")))
        }
        "a part's pieces are one stream, and every part starts a line" in {
            assert(Multipart.boundary("p", Chunk(Chunk(bytes("--"), bytes("p0\r\n")))) == "p1")
            assert(Multipart.boundary("p", Chunk(Chunk(bytes("text--")), Chunk(bytes("p0\r\n")))) == "p0")
            assert(Multipart.boundary("p", Chunk(Chunk(bytes("text")), Chunk(bytes("--p0")))) == "p1")
            assert(Multipart.boundary("p", Chunk(Chunk(bytes("text\r\n--p"), bytes("0")), Chunk(bytes("--p1")))) == "p2")
        }
        "every number a digit run starts with is taken, since a delimiter is matched by prefix" in {
            assert(Multipart.boundary("p", parts("--p10\r\n")) == "p0")
            assert(Multipart.boundary("p", parts("--p01\r\n")) == "p2")
            assert(Multipart.boundary("p", parts("--p0x\r\n--p1\r\n")) == "p2")
            assert(Multipart.findDelimiter(bytes("--p10\r\n"), bytes("p1"), 0) == 0)
        }
        "a declared boundary of an enclosed part is avoided" in {
            assert(Multipart.boundary("p", Chunk.empty, Chunk("p0", "p1")) == "p2")
            assert(Multipart.boundary("p", parts("--p2\r\n"), Chunk("p0", "p1")) == "p3")
        }
        "the chosen boundary is valid and never found in the parts" in {
            val enclosed = parts("--p0\r\n--p1\r\n--p2\r\n", "text\r\n--p3")
            val chosen   = Multipart.boundary("p", enclosed)
            assert(chosen == "p4")
            assert(Multipart.isValidBoundary(chosen))
            assert(enclosed.forall(_.forall(piece => Multipart.findDelimiter(piece, bytes(chosen), 0) == -1)))
        }
    }

end MultipartTest
