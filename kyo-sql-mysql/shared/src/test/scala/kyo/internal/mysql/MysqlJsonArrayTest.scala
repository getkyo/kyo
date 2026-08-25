package kyo.internal.mysql

import java.nio.charset.StandardCharsets
import kyo.Chunk
import kyo.Maybe
import kyo.Span
import kyo.SqlCodec.Format
import kyo.SqlDecodeJsonException
import kyo.SqlRow
import kyo.Test

/** Unit tests for [[MysqlJsonArray]], the JSON array wire MySQL's `Chunk` columns travel on.
  *
  * MySQL has no array column type, so a `Chunk[Int]` or `Chunk[String]` goes out as a JSON document in a text column and comes back parsed
  * here. That makes this file the whole contract for those columns: the encoder's exact rendering (which the server stores verbatim and a
  * later `JSON_EXTRACT` reads), the parser's tolerance for what the server sends back, and the refusals a malformed document produces.
  *
  * The rejections are checked twice over, on purpose. [[MysqlJsonArray]] reports a failure through a `fail` callback rather than by
  * throwing, so the pure leaves below drive it with a local sentinel to read the message, and one leaf at the end drives a real
  * [[MysqlRowReader]] to pin that the reader binds that callback to a typed [[kyo.SqlDecodeJsonException]] rather than letting an untyped
  * throw escape the codec.
  */
class MysqlJsonArrayTest extends Test:

    /** The sentinel the pure leaves reject through, so the message the parser produced is readable. */
    final private class Rejected(val reason: String) extends RuntimeException(reason)

    private def fail(msg: String): Nothing = throw Rejected(msg)

    // ── Encoding ──────────────────────────────────────────────────────────────

    "encodeInts renders the compact JSON form" in {
        assert(MysqlJsonArray.encodeInts(Chunk(1, 2, 3)) == "[1,2,3]")
    }

    "encodeInts renders negative values and the empty array" in {
        assert(MysqlJsonArray.encodeInts(Chunk(-1, 0, Int.MaxValue, Int.MinValue)) == "[-1,0,2147483647,-2147483648]")
        assert(MysqlJsonArray.encodeInts(Chunk.empty) == "[]")
    }

    "encodeStrings quotes every element and renders the empty array" in {
        assert(MysqlJsonArray.encodeStrings(Chunk("a", "b")) == """["a","b"]""")
        assert(MysqlJsonArray.encodeStrings(Chunk.empty) == "[]")
    }

    "encodeStrings escapes the two characters JSON reserves inside a string" in {
        assert(MysqlJsonArray.encodeStrings(Chunk("""say "hi"""")) == """["say \"hi\""]""")
        assert(MysqlJsonArray.encodeStrings(Chunk("""C:\path""")) == """["C:\\path"]""")
    }

    "encodeStrings uses the short escapes for the five named control characters" in {
        assert(MysqlJsonArray.encodeStrings(Chunk("\b\f\n\r\t")) == """["\b\f\n\r\t"]""")
    }

    "encodeStrings escapes any other control character as its four-digit code" in {
        // 0x0B has no short escape, so the generic arm renders it. An unescaped control byte is a document the
        // server rejects outright, which is why this is a rendering rule rather than a nicety.
        assert(MysqlJsonArray.encodeStrings(Chunk("a\u000bb")) == """["a\u000bb"]""")
        assert(MysqlJsonArray.encodeStrings(Chunk("\u0000")) == """["\u0000"]""")
    }

    "encodeStrings leaves non-ASCII text raw, as UTF-8" in {
        // The column is UTF-8 either way, so escaping these would only make the stored document larger and
        // harder to read; the server accepts them verbatim.
        assert(MysqlJsonArray.encodeStrings(Chunk("héllo", "世界")) == """["héllo","世界"]""")
    }

    "a string element round-trips through encode and decode, escapes included" in {
        val values = Chunk("plain", """with "quotes"""", """back\slash""", "tab\there", "\u0001", "héllo", "")
        assert(MysqlJsonArray.decodeStrings(MysqlJsonArray.encodeStrings(values))(fail) == values)
    }

    "an int element round-trips through encode and decode" in {
        val values = Chunk(0, -7, 42, Int.MaxValue, Int.MinValue)
        assert(MysqlJsonArray.decodeInts(MysqlJsonArray.encodeInts(values))(fail) == values)
    }

    // ── Decoding ints ─────────────────────────────────────────────────────────

    "decodeInts reads the compact form and tolerates the whitespace a server may add" in {
        assert(MysqlJsonArray.decodeInts("[1,2,3]")(fail) == Chunk(1, 2, 3))
        assert(MysqlJsonArray.decodeInts("  [ 1 , -2 , 3 ]  ")(fail) == Chunk(1, -2, 3))
    }

    "decodeInts reads the empty array as an empty Chunk" in {
        assert(MysqlJsonArray.decodeInts("[]")(fail) == Chunk.empty[Int])
        assert(MysqlJsonArray.decodeInts("[   ]")(fail) == Chunk.empty[Int])
    }

    // ── Decoding strings ──────────────────────────────────────────────────────

    "decodeStrings resolves the quoted form, including a comma inside an element" in {
        // The split has to be string-aware: a naive split on ',' would turn one element into two.
        assert(MysqlJsonArray.decodeStrings("""["a","b,c"]""")(fail) == Chunk("a", "b,c"))
    }

    "decodeStrings resolves every escape JSON defines, including the ones the encoder never emits" in {
        assert(MysqlJsonArray.decodeStrings("""["\"","\\","\/","\b","\f","\n","\r","\t"]""")(fail) ==
            Chunk("\"", "\\", "/", "\b", "\f", "\n", "\r", "\t"))
    }

    "decodeStrings resolves a unicode escape" in {
        assert(MysqlJsonArray.decodeStrings("""["\u0041\u00e9","\u000b"]""")(fail) == Chunk("Aé", "\u000b"))
    }

    "decodeStrings reads an empty string element" in {
        assert(MysqlJsonArray.decodeStrings("""["",""]""")(fail) == Chunk("", ""))
    }

    // ── The element split ─────────────────────────────────────────────────────

    "elements splits the top level and leaves each element's own text alone" in {
        assert(MysqlJsonArray.elements("[1,2,3]")(fail) == Chunk("1", "2", "3"))
        assert(MysqlJsonArray.elements("[]")(fail) == Chunk.empty[String])
    }

    "elements keeps a nested object whole" in {
        // This is what `nextArrayOfJson` hands back: one document text per element, so the commas and braces
        // inside an element must not split it.
        assert(MysqlJsonArray.elements("""[{"k":1},{"k":2}]""")(fail) == Chunk("""{"k":1}""", """{"k":2}"""))
    }

    "elements keeps a nested array whole, at any depth" in {
        assert(MysqlJsonArray.elements("""[[1,2],[3,[4,5]]]""")(fail) == Chunk("[1,2]", "[3,[4,5]]"))
        assert(MysqlJsonArray.elements("""[{"k":[1,2]},3]""")(fail) == Chunk("""{"k":[1,2]}""", "3"))
    }

    "elements ignores structure that sits inside a string" in {
        // A bracket or a comma inside a quoted element is data, not structure. A depth counter that did not
        // suspend inside strings would split `"a],b"` into two elements and then report unbalanced nesting.
        assert(MysqlJsonArray.elements("""["a],b","c"]""")(fail) == Chunk("\"a],b\"", "\"c\""))
        assert(MysqlJsonArray.elements("""["\"x,y"]""")(fail) == Chunk("\"\\\"x,y\""))
    }

    // ── Malformed input ───────────────────────────────────────────────────────

    "a document that is not an array is refused, naming what arrived" in {
        val ex = intercept[Rejected](MysqlJsonArray.elements("1,2,3")(fail))
        assert(ex.reason.contains("expected a JSON array"), ex.reason)
        assert(ex.reason.contains("1,2,3"), s"the refusal must quote what arrived: ${ex.reason}")
    }

    "an unterminated document is refused rather than read as a shorter array" in {
        assert(intercept[Rejected](MysqlJsonArray.elements("""["a]""")(fail)).reason.contains("unterminated"))
        assert(intercept[Rejected](MysqlJsonArray.elements("""[{"k":1]""")(fail)).reason.contains("unterminated"))
    }

    "an element that is not an integer is refused, naming the element" in {
        val ex = intercept[Rejected](MysqlJsonArray.decodeInts("""[1,"two"]""")(fail))
        assert(ex.reason.contains("expected an integer array element"), ex.reason)
        assert(ex.reason.contains("two"), s"the refusal must name the element: ${ex.reason}")
    }

    "an integer element outside Int range is refused rather than wrapped" in {
        val ex = intercept[Rejected](MysqlJsonArray.decodeInts("[2147483648]")(fail))
        assert(ex.reason.contains("expected an integer array element"), ex.reason)
    }

    "an element that is not a quoted string is refused" in {
        val ex = intercept[Rejected](MysqlJsonArray.decodeStrings("[1]")(fail))
        assert(ex.reason.contains("expected a JSON string element"), ex.reason)
    }

    "a dangling escape is refused" in {
        assert(intercept[Rejected](MysqlJsonArray.unquote("\"a\\\"")(fail)).reason.contains("dangling escape"))
    }

    "a truncated unicode escape is refused" in {
        assert(intercept[Rejected](MysqlJsonArray.unquote("\"\\u12\"")(fail)).reason.contains("truncated unicode escape"))
    }

    "a unicode escape whose digits are not hexadecimal is refused, quoting them" in {
        val ex = intercept[Rejected](MysqlJsonArray.unquote("\"\\uZZZZ\"")(fail))
        assert(ex.reason.contains("invalid unicode escape"), ex.reason)
        assert(ex.reason.contains("ZZZZ"), ex.reason)
    }

    "an escape JSON does not define is refused, naming the character" in {
        val ex = intercept[Rejected](MysqlJsonArray.unquote("\"\\q\"")(fail))
        assert(ex.reason.contains("invalid escape"), ex.reason)
        assert(ex.reason.contains("q"), ex.reason)
    }

    // ── The reader binds the callback to a typed decode failure ───────────────

    "a malformed array column reaches the caller as a typed decode failure, not an untyped throw" in {
        // The pure leaves above read the message through a sentinel; this one pins what a caller actually sees.
        // `MysqlJsonArray` throws nothing of its own, so a reader that forgot to bind `fail` would let the
        // parser's own failure escape the codec untyped.
        val row = new SqlRow(
            Chunk(Maybe.Present(Span.from("""not an array""".getBytes(StandardCharsets.UTF_8)))),
            Chunk(SqlRow.Column("arr", 0)),
            MysqlRowCodec(Format.Binary)
        )
        val ex = intercept[SqlDecodeJsonException] {
            val _ = new MysqlRowReader(row, Format.Binary).nextArrayOfInt()
        }
        assert(ex.jsonPreview.contains("expected a JSON array"), ex.jsonPreview)
    }

end MysqlJsonArrayTest
