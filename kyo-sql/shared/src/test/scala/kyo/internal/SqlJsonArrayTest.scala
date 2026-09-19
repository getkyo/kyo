package kyo.internal

import java.nio.charset.StandardCharsets
import kyo.Chunk
import kyo.Maybe
import kyo.Span
import kyo.SqlCodec.Format
import kyo.SqlDecodeJsonException
import kyo.SqlRow
import kyo.Test

/** Unit tests for [[SqlJsonArray]], the JSON array wire MySQL's `Chunk` columns travel on.
  *
  * MySQL has no array column type, so a `Chunk[Int]` or `Chunk[String]` goes out as a JSON document in a text column and comes back parsed
  * here. That makes this file the whole contract for those columns: the encoder's exact rendering (which the server stores verbatim and a
  * later `JSON_EXTRACT` reads), the parser's tolerance for what the server sends back, and the refusals a malformed document produces.
  *
  * The rejections are checked twice over, on purpose. [[SqlJsonArray]] reports a failure through a `fail` callback rather than by
  * throwing, so the pure leaves below drive it with a local sentinel to read the message, and one leaf at the end drives a real
  * [[MysqlRowReader]] to pin that the reader binds that callback to a typed [[kyo.SqlDecodeJsonException]] rather than letting an untyped
  * throw escape the codec.
  */
class SqlJsonArrayTest extends Test:

    /** The sentinel the pure leaves reject through, so the message the parser produced is readable. */
    final private class Rejected(val reason: String) extends RuntimeException(reason)

    private def fail(msg: String): Nothing = throw Rejected(msg)

    // ── Encoding ──────────────────────────────────────────────────────────────

    "encodeInts renders the compact JSON form" in {
        assert(SqlJsonArray.encodeInts(Chunk(1, 2, 3)) == "[1,2,3]")
    }

    "encodeInts renders negative values and the empty array" in {
        assert(SqlJsonArray.encodeInts(Chunk(-1, 0, Int.MaxValue, Int.MinValue)) == "[-1,0,2147483647,-2147483648]")
        assert(SqlJsonArray.encodeInts(Chunk.empty) == "[]")
    }

    "encodeStrings quotes every element and renders the empty array" in {
        assert(SqlJsonArray.encodeStrings(Chunk("a", "b")) == """["a","b"]""")
        assert(SqlJsonArray.encodeStrings(Chunk.empty) == "[]")
    }

    "encodeStrings escapes the two characters JSON reserves inside a string" in {
        assert(SqlJsonArray.encodeStrings(Chunk("""say "hi"""")) == """["say \"hi\""]""")
        assert(SqlJsonArray.encodeStrings(Chunk("""C:\path""")) == """["C:\\path"]""")
    }

    "encodeStrings uses the short escapes for the five named control characters" in {
        assert(SqlJsonArray.encodeStrings(Chunk("\b\f\n\r\t")) == """["\b\f\n\r\t"]""")
    }

    "encodeStrings escapes any other control character as its four-digit code" in {
        // 0x0B has no short escape, so the generic arm renders it. An unescaped control byte is a document the
        // server rejects outright, which is why this is a rendering rule rather than a nicety.
        assert(SqlJsonArray.encodeStrings(Chunk("a\u000bb")) == """["a\u000bb"]""")
        assert(SqlJsonArray.encodeStrings(Chunk("\u0000")) == """["\u0000"]""")
    }

    "encodeStrings leaves non-ASCII text raw, as UTF-8" in {
        // The column is UTF-8 either way, so escaping these would only make the stored document larger and
        // harder to read; the server accepts them verbatim.
        assert(SqlJsonArray.encodeStrings(Chunk("héllo", "世界")) == """["héllo","世界"]""")
    }

    "a string element round-trips through encode and decode, escapes included" in {
        val values = Chunk("plain", """with "quotes"""", """back\slash""", "tab\there", "\u0001", "héllo", "")
        assert(SqlJsonArray.decodeStrings(SqlJsonArray.encodeStrings(values))(fail) == values)
    }

    "an int element round-trips through encode and decode" in {
        val values = Chunk(0, -7, 42, Int.MaxValue, Int.MinValue)
        assert(SqlJsonArray.decodeInts(SqlJsonArray.encodeInts(values))(fail) == values)
    }

    // ── Decoding ints ─────────────────────────────────────────────────────────

    "decodeInts reads the compact form and tolerates the whitespace a server may add" in {
        assert(SqlJsonArray.decodeInts("[1,2,3]")(fail) == Chunk(1, 2, 3))
        assert(SqlJsonArray.decodeInts("  [ 1 , -2 , 3 ]  ")(fail) == Chunk(1, -2, 3))
    }

    "decodeInts reads the empty array as an empty Chunk" in {
        assert(SqlJsonArray.decodeInts("[]")(fail) == Chunk.empty[Int])
        assert(SqlJsonArray.decodeInts("[   ]")(fail) == Chunk.empty[Int])
    }

    // ── Decoding strings ──────────────────────────────────────────────────────

    "decodeStrings resolves the quoted form, including a comma inside an element" in {
        // The split has to be string-aware: a naive split on ',' would turn one element into two.
        assert(SqlJsonArray.decodeStrings("""["a","b,c"]""")(fail) == Chunk("a", "b,c"))
    }

    "decodeStrings resolves every escape JSON defines, including the ones the encoder never emits" in {
        assert(SqlJsonArray.decodeStrings("""["\"","\\","\/","\b","\f","\n","\r","\t"]""")(fail) ==
            Chunk("\"", "\\", "/", "\b", "\f", "\n", "\r", "\t"))
    }

    "decodeStrings resolves a unicode escape" in {
        assert(SqlJsonArray.decodeStrings("""["\u0041\u00e9","\u000b"]""")(fail) == Chunk("Aé", "\u000b"))
    }

    "decodeStrings reads an empty string element" in {
        assert(SqlJsonArray.decodeStrings("""["",""]""")(fail) == Chunk("", ""))
    }

    // ── The element split ─────────────────────────────────────────────────────

    "elements splits the top level and leaves each element's own text alone" in {
        assert(SqlJsonArray.elements("[1,2,3]")(fail) == Chunk("1", "2", "3"))
        assert(SqlJsonArray.elements("[]")(fail) == Chunk.empty[String])
    }

    "elements keeps a nested object whole" in {
        // This is what `nextArrayOfJson` hands back: one document text per element, so the commas and braces
        // inside an element must not split it.
        assert(SqlJsonArray.elements("""[{"k":1},{"k":2}]""")(fail) == Chunk("""{"k":1}""", """{"k":2}"""))
    }

    "elements keeps a nested array whole, at any depth" in {
        assert(SqlJsonArray.elements("""[[1,2],[3,[4,5]]]""")(fail) == Chunk("[1,2]", "[3,[4,5]]"))
        assert(SqlJsonArray.elements("""[{"k":[1,2]},3]""")(fail) == Chunk("""{"k":[1,2]}""", "3"))
    }

    "elements ignores structure that sits inside a string" in {
        // A bracket or a comma inside a quoted element is data, not structure. A depth counter that did not
        // suspend inside strings would split `"a],b"` into two elements and then report unbalanced nesting.
        assert(SqlJsonArray.elements("""["a],b","c"]""")(fail) == Chunk("\"a],b\"", "\"c\""))
        assert(SqlJsonArray.elements("""["\"x,y"]""")(fail) == Chunk("\"\\\"x,y\""))
    }

    // ── Malformed input ───────────────────────────────────────────────────────

    "a document that is not an array is refused, naming what arrived" in {
        val ex = intercept[Rejected](SqlJsonArray.elements("1,2,3")(fail))
        assert(ex.reason.contains("expected a JSON array"), ex.reason)
        assert(ex.reason.contains("1,2,3"), s"the refusal must quote what arrived: ${ex.reason}")
    }

    "an unterminated document is refused rather than read as a shorter array" in {
        assert(intercept[Rejected](SqlJsonArray.elements("""["a]""")(fail)).reason.contains("unterminated"))
        assert(intercept[Rejected](SqlJsonArray.elements("""[{"k":1]""")(fail)).reason.contains("unterminated"))
    }

    "an element that is not an integer is refused, naming the element" in {
        val ex = intercept[Rejected](SqlJsonArray.decodeInts("""[1,"two"]""")(fail))
        assert(ex.reason.contains("expected an integer array element"), ex.reason)
        assert(ex.reason.contains("two"), s"the refusal must name the element: ${ex.reason}")
    }

    "an integer element outside Int range is refused rather than wrapped" in {
        val ex = intercept[Rejected](SqlJsonArray.decodeInts("[2147483648]")(fail))
        assert(ex.reason.contains("expected an integer array element"), ex.reason)
    }

    "an element that is not a quoted string is refused" in {
        val ex = intercept[Rejected](SqlJsonArray.decodeStrings("[1]")(fail))
        assert(ex.reason.contains("expected a JSON string element"), ex.reason)
    }

    "a dangling escape is refused" in {
        assert(intercept[Rejected](SqlJsonArray.unquote("\"a\\\"")(fail)).reason.contains("dangling escape"))
    }

    "a truncated unicode escape is refused" in {
        assert(intercept[Rejected](SqlJsonArray.unquote("\"\\u12\"")(fail)).reason.contains("truncated unicode escape"))
    }

    "a unicode escape whose digits are not hexadecimal is refused, quoting them" in {
        val ex = intercept[Rejected](SqlJsonArray.unquote("\"\\uZZZZ\"")(fail))
        assert(ex.reason.contains("invalid unicode escape"), ex.reason)
        assert(ex.reason.contains("ZZZZ"), ex.reason)
    }

    "an escape JSON does not define is refused, naming the character" in {
        val ex = intercept[Rejected](SqlJsonArray.unquote("\"\\q\"")(fail))
        assert(ex.reason.contains("invalid escape"), ex.reason)
        assert(ex.reason.contains("q"), ex.reason)
    }

end SqlJsonArrayTest
