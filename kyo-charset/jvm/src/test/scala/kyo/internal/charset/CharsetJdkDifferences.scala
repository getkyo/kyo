package kyo.internal.charset

import java.nio.ByteBuffer
import java.nio.charset.Charset as JdkCharset
import java.nio.charset.CodingErrorAction
import kyo.*
import kyo.charset.Charset

/** Where every `Charset` disagrees with the JDK's decoder for the nearest charset, over every valid sequence and a seeded set of
  * generated strings.
  *
  * `CharsetJdkDifferentialTest` compares these rows with `jdk-25-differences.tsv`, and `main` writes that file:
  * `sbt 'kyo-charsetJVM/Test/runMain kyo.internal.charset.CharsetJdkDifferences <path>'`.
  */
object CharsetJdkDifferences:

    val Replacement: Char = 0xfffd.toChar

    /** Why the module and the JDK decode an input differently. */
    enum Reason(val description: String) derives CanEqual:
        case C1Control extends Reason(
                "a byte a windows code page leaves undefined, or Shift_JIS 0x80: WHATWG decodes it as the C1 control of the same value, the JDK replaces it"
            )
        case Table      extends Reason("the WHATWG index and the JDK's table map this valid sequence to different code points")
        case WhatwgMaps extends Reason("the WHATWG index maps this sequence; the JDK's table does not")
        case JdkMaps    extends Reason("the JDK's table maps this sequence; the WHATWG index does not")
        case MaximalSubpart
            extends Reason("UTF-8: WHATWG replaces each maximal subpart of an ill-formed sequence; the JDK replaces a longer run once")
        case Utf16UnpairedLead
            extends Reason("UTF-16: after an unpaired lead surrogate the JDK replaces the next unit as well; WHATWG decodes it")
        case Ucs2Label
            extends Reason(
                "iso-10646-ucs-2: the WHATWG label table reads it as UTF-16LE, a byte order mark being content; the JDK reads UTF-16BE and keeps FEFF"
            )
        case Gb2312CodePoints
            extends Reason("gb2312: WHATWG decodes A1A4 and A1AA as GBK does, U+00B7 and U+2014; GB 2312 has U+30FB and U+2015")
        case Malformed
            extends Reason(
                "generated input: the module and the JDK replace and resynchronise differently after malformed bytes, or a listed table difference occurs inside it"
            )
    end Reason

    final case class Row(comparison: String, input: String, module: String, jdk: String, reason: Reason) derives CanEqual:
        def line: String = Seq(comparison, input, module, jdk, reason.toString).mkString("\t")

    def hex(input: Span[Byte]): String = input.toArray.map(b => f"${b & 0xff}%02X").mkString(" ")

    def codePoints(text: String): String =
        @scala.annotation.tailrec
        def loop(i: Int, acc: Vector[String]): Vector[String] =
            if i >= text.length then acc
            else
                val cp = text.codePointAt(i)
                loop(i + Character.charCount(cp), acc :+ f"$cp%04X")
        loop(0, Vector.empty).mkString(" ")
    end codePoints

    /** The JDK charset nearest each encoding's WHATWG definition: Big5 with HKSCS, EUC-KR as Windows code page 949, Shift_JIS as
      * Windows-31J, GBK as gb18030 (WHATWG's GBK decoder is gb18030's), EUC-JP with the IBM and NEC rows, ISO-2022-JP with them too.
      */
    def jdkName(charset: Charset): String =
        charset match
            case Charset.Macintosh    => "x-MacRoman"
            case Charset.Windows874   => "x-windows-874"
            case Charset.XMacCyrillic => "x-MacCyrillic"
            case Charset.Big5         => "Big5-HKSCS"
            case Charset.EucKr        => "x-windows-949"
            case Charset.ShiftJis     => "windows-31j"
            case Charset.Gbk          => "GB18030"
            case Charset.EucJp        => "x-eucJP-Open"
            case Charset.Iso2022Jp    => "x-windows-iso2022jp"
            case other                => other.name

    val unavailable: Set[Charset] = Set(Charset.Iso8859_8I, Charset.Iso8859_10, Charset.Iso8859_14, Charset.Utf7, Charset.HzGb2312)

    final private case class Comparison(name: String, charset: Charset, jdk: String, valid: Seq[Span[Byte]], generated: Seq[Span[Byte]])

    private lazy val comparisons: Seq[Comparison] =
        Charset.values.toSeq.filterNot(unavailable.contains).map { c =>
            Comparison(c.name, c, jdkName(c), CharsetJdkInputs.valid(c), CharsetJdkInputs.generatedFor(c))
        } ++ Seq(
            Comparison("label iso-10646-ucs-2", label("iso-10646-ucs-2"), "ISO-10646-UCS-2", CharsetJdkInputs.marked, Seq.empty),
            Comparison("label gb2312", label("gb2312"), "GB2312", CharsetJdkInputs.gb2312, Seq.empty)
        )

    private def label(name: String): Charset = Charset.resolve(name).getOrElse(throw new IllegalStateException(s"no charset for $name"))

    private def jdkDecode(charset: JdkCharset, input: Span[Byte]): String =
        charset.newDecoder().onMalformedInput(CodingErrorAction.REPLACE).onUnmappableCharacter(CodingErrorAction.REPLACE)
            .replaceWith(Replacement.toString).decode(ByteBuffer.wrap(input.toArray)).toString

    lazy val observed: Seq[Row] =
        comparisons.flatMap { c =>
            val charset                                                            = JdkCharset.forName(c.jdk)
            def differences(inputs: Seq[Span[Byte]], generated: Boolean): Seq[Row] =
                inputs.flatMap { input =>
                    val module = c.charset.decode(input)
                    val jdk    = jdkDecode(charset, input)
                    if module == jdk then Seq.empty
                    else if !generated && input.size > 4 && isGb18030(c) then
                        // A differing run of four-byte sequences is compared sequence by sequence, so each row is one sequence.
                        differences(input.toArray.grouped(4).map(Span.from(_)).toSeq, generated)
                    else Seq(Row(c.name, hex(input), codePoints(module), codePoints(jdk), classify(c, input, module, jdk, generated)))
                    end if
                }
            differences(c.valid, generated = false) ++ differences(c.generated, generated = true)
        }

    private def isGb18030(c: Comparison): Boolean = c.charset == Charset.Gb18030 || c.charset == Charset.Gbk

    private def classify(c: Comparison, input: Span[Byte], module: String, jdk: String, generated: Boolean): Reason =
        val moduleReplaces = module.contains(Replacement)
        val jdkReplaces    = jdk.contains(Replacement)
        if c.name == "label iso-10646-ucs-2" then Reason.Ucs2Label
        else if c.name == "label gb2312" && Set("A1 A4", "A1 AA").contains(hex(input)) then Reason.Gb2312CodePoints
        else if generated && c.charset == Charset.Utf8 then Reason.MaximalSubpart
        else if generated && c.name.startsWith("UTF-16") then Reason.Utf16UnpairedLead
        else if generated then Reason.Malformed
        else if input.size == 1 && jdk == Replacement.toString && module.length == 1 &&
            module.charAt(0).toInt ==
                (input(0) & 0xff) &&
                module.charAt(0) >= 0x80 && module.charAt(0) <= 0x9f
        then Reason.C1Control
        else if jdkReplaces && !moduleReplaces then Reason.WhatwgMaps
        else if moduleReplaces && !jdkReplaces then Reason.JdkMaps
        else Reason.Table
        end if
    end classify

    def render(rows: Seq[Row]): String = rows.map(_.line).mkString("\n")

    private def header: String =
        (Seq(
            s"# Differences between kyo-charset's decoders and the JDK's, made on ${java.lang.System.getProperty("java.vendor")} " +
                s"${java.lang.System.getProperty("java.version")}.",
            "# Written by CharsetJdkDifferences.main:",
            "#   sbt 'kyo-charsetJVM/Test/runMain kyo.internal.charset.CharsetJdkDifferences <path of this file>'",
            "# Columns, tab-separated: comparison, input bytes, the module's code points, the JDK's code points, reason.",
            "# Reasons:"
        ) ++ Reason.values.map(r => s"#   $r: ${r.description}")).mkString("\n")

    def main(args: Array[String]): Unit =
        if args.length != 1 then throw new IllegalArgumentException("usage: CharsetJdkDifferences <output path>")
        discard(java.nio.file.Files.write(java.nio.file.Paths.get(args(0)), (header + "\n" + render(observed) + "\n").getBytes("UTF-8")))
    end main

end CharsetJdkDifferences
