package kyo.internal.charset

import kyo.*
import kyo.internal.charset.vectors.*

/** Helpers the decoder tests share: inputs written as byte values, expectations written as code points, and the web-platform-tests
  * decoder cases read from the files vendored under `src/test/vectors/wpt-encoding/`.
  *
  * The WPT files are read with code independent of the decoders: the input bytes of an error case are the raw bytes between `<span>` and
  * `</span>`, and every expected string is a JavaScript string literal unescaped here.
  */
object DecoderFixtures:

    def bytes(values: Int*): Span[Byte] = Span.from(values.map(_.toByte).toArray)

    def text(codePoints: Int*): String = codePoints.map(cp => new String(Character.toChars(cp))).mkString

    def show(span: Span[Byte]): String = span.toArray.map(b => f"${b & 0xff}%02X").mkString(" ")

    def show(text: String): String =
        @scala.annotation.tailrec
        def loop(i: Int, acc: Vector[String]): Vector[String] =
            if i >= text.length then acc
            else
                val cp = text.codePointAt(i)
                loop(i + Character.charCount(cp), acc :+ f"U+$cp%04X")
        loop(0, Vector.empty).mkString(" ")
    end show

    final case class Case(title: String, input: Span[Byte], expected: String)

    /** The `decode([bytes], "expected", "description")` calls of `gb18030-decoder.any.js` whose input is a literal byte array; the loop
      * over the ranges index at the end of the file builds its inputs from `ranges.js`, which is not vendored.
      */
    lazy val gb18030: Chunk[Case] = decodeCalls(EmbeddedGb18030DecoderAnyJs.text)

    /** The `decode([bytes], "expected", "description")` calls of `iso-2022-jp-decoder.any.js`. */
    lazy val iso2022Jp: Chunk[Case] = decodeCalls(EmbeddedIso2022JpDecoderAnyJs.text)

    lazy val big5Errors: Chunk[Case]      = errorCases(EmbeddedBig5ErrorsHtml.bytes, EmbeddedBig5DecodeErrorsHtml.text)
    lazy val eucKrErrors: Chunk[Case]     = errorCases(EmbeddedEuckrErrorsHtml.bytes, EmbeddedEuckrDecodeErrorsHtml.text)
    lazy val shiftJisErrors: Chunk[Case]  = errorCases(EmbeddedSjisErrorsHtml.bytes, EmbeddedSjisDecodeErrorsHtml.text)
    lazy val eucJpErrors: Chunk[Case]     = errorCases(EmbeddedEucjpErrorsHtml.bytes, EmbeddedEucjpDecodeErrorsHtml.text)
    lazy val iso2022JpErrors: Chunk[Case] = errorCases(EmbeddedIso2022jpErrorsHtml.bytes, EmbeddedIso2022jpDecodeErrorsHtml.text)

    private def decodeCalls(script: String): Chunk[Case] =
        val call = """decode\(\s*\[([0-9a-fA-FxX,\s]*)\]\s*,\s*"((?:[^"\\]|\\.)*)"\s*(?:,\s*"((?:[^"\\]|\\.)*)")?\s*,?\s*\)""".r
        Chunk.from(call.findAllMatchIn(script).map { m =>
            val input = m.group(1).split(",").iterator.map(_.trim).filter(_.nonEmpty).map(number).toSeq
            Case(Maybe(m.group(3)).getOrElse(show(bytes(input*))), bytes(input*), unescape(m.group(2)))
        }.toSeq)
    end decodeCalls

    /** Pairs the n-th `<span>` of the bytes file with the n-th `async_test` title and the n-th `assert_equals` expectation of the harness,
      * the order the harness itself checks them in. Lines commented out with `//` are not cases.
      */
    private def errorCases(page: Span[Byte], harness: String): Chunk[Case] =
        val live     = harness.split("\n").filterNot(_.trim.startsWith("//")).mkString("\n")
        val literal  = """"((?:[^"\\]|\\.)*)""""
        val titles   = s"""async_test\\(\\s*$literal\\s*\\)""".r.findAllMatchIn(live).map(m => unescape(m.group(1))).toSeq
        val expected = s"""assert_equals\\(nodes\\[[^\\]]*\\]\\.textContent,\\s*$literal\\)""".r.findAllMatchIn(live).map(m =>
            unescape(m.group(1))
        ).toSeq
        val inputs = spans(page.toArray)
        if titles.size != inputs.size || expected.size != inputs.size then
            throw new IllegalStateException(s"${inputs.size} spans, ${titles.size} titles and ${expected.size} expectations do not pair up")
        Chunk.from(inputs.indices.map(i => Case(titles(i), Span.from(inputs(i)), expected(i))))
    end errorCases

    private def spans(page: Array[Byte]): Seq[Array[Byte]] =
        val open                                      = "<span>".getBytes("US-ASCII")
        val close                                     = "</span>".getBytes("US-ASCII")
        def find(needle: Array[Byte], from: Int): Int = (from to page.length - needle.length).find(at =>
            needle.indices.forall(k => page(at + k) == needle(k))
        ).getOrElse(-1)
        @scala.annotation.tailrec
        def loop(from: Int, acc: Seq[Array[Byte]]): Seq[Array[Byte]] =
            val start = find(open, from)
            if start < 0 then acc
            else
                val end = find(close, start + open.length)
                loop(end + close.length, acc :+ page.slice(start + open.length, end))
            end if
        end loop
        loop(0, Seq.empty)
    end spans

    private def number(token: String): Int =
        if token.startsWith("0x") || token.startsWith("0X") then Integer.parseInt(token.drop(2), 16) else token.toInt

    /** A JavaScript string literal's content: `\uXXXX`, `\u{X...}`, `\xXX` and the single-character escapes the files use. */
    private def unescape(literal: String): String =
        val out = new java.lang.StringBuilder
        @scala.annotation.tailrec
        def loop(i: Int): Unit =
            if i < literal.length then
                val c = literal.charAt(i)
                if c != '\\' then
                    discard(out.append(c))
                    loop(i + 1)
                else
                    literal.charAt(i + 1) match
                        case 'u' if literal.charAt(i + 2) == '{' =>
                            val close = literal.indexOf('}', i + 3)
                            discard(out.appendCodePoint(Integer.parseInt(literal.substring(i + 3, close), 16)))
                            loop(close + 1)
                        case 'u' =>
                            discard(out.append(Integer.parseInt(literal.substring(i + 2, i + 6), 16).toChar))
                            loop(i + 6)
                        case 'x' =>
                            discard(out.append(Integer.parseInt(literal.substring(i + 2, i + 4), 16).toChar))
                            loop(i + 4)
                        case '\\' | '"' | '\'' =>
                            discard(out.append(literal.charAt(i + 1)))
                            loop(i + 2)
                        case other => throw new IllegalStateException(s"unsupported escape \\$other in \"$literal\"")
                    end match
                end if
        loop(0)
        out.toString
    end unescape

end DecoderFixtures
