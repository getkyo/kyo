package kyo.charset

import kyo.*
import kyo.internal.Ascii
import kyo.internal.charset.Decoders
import kyo.internal.charset.WhatwgLabels

/** A character encoding, decoded the same way on every platform: every WHATWG Encoding Standard encoding except `replacement` and
  * `x-user-defined`, plus UTF-7 (RFC 2152), UTF-32 (Unicode Standard D99 to D101), and the two encodings WHATWG folds into `replacement`,
  * ISO-2022-KR (RFC 1557) and HZ-GB-2312 (RFC 1843).
  *
  * `decode` follows the WHATWG decoder algorithm of each encoding exactly, so a byte sequence decodes here as it does in a browser: each
  * ill-formed sequence becomes one U+FFFD where the algorithm says, and decoding continues. `decodeStrict` is the same algorithm in
  * WHATWG's fatal mode, refusing the input at the first ill-formed sequence. Both take a whole unit of text in one call; a decoder keeps
  * no state between calls, so one `Charset` serves any number of concurrent decodes.
  *
  * Bare `UTF-16` and `UTF-32` read a byte order mark, which selects the order and is removed, and default to big-endian (RFC 2781
  * section 4.3, Unicode Standard D101); `UTF-16BE`, `UTF-16LE`, `UTF-32BE` and `UTF-32LE` keep a leading U+FEFF as content. UTF-8
  * removes a leading byte order mark, as the WHATWG "UTF-8 decode" does.
  *
  * A label is turned into a `Charset` by [[Charset.resolve]], through the WHATWG label table: `latin1`, `iso-8859-1` and `us-ascii` all
  * resolve to `Windows1252`, `gb2312` to `Gbk`, `utf-16` to `Utf16Le`, and the labels WHATWG maps to `replacement` (`hz-gb-2312`,
  * `iso-2022-kr`, `iso-2022-cn`) to `Absent`. A caller whose protocol reads those labels differently, as mail does, applies its own
  * table before `resolve`.
  *
  * The tables of the multi-byte encodings are decoded from string constants on first use, one table at a time, so a program that decodes
  * only `Windows1252` never materializes `Gb18030`'s.
  *
  * @see
  *   [[https://encoding.spec.whatwg.org/ The WHATWG Encoding Standard]]
  */
enum Charset(val name: String) derives CanEqual:
    case Utf8         extends Charset("UTF-8")
    case Ibm866       extends Charset("IBM866")
    case Iso8859_2    extends Charset("ISO-8859-2")
    case Iso8859_3    extends Charset("ISO-8859-3")
    case Iso8859_4    extends Charset("ISO-8859-4")
    case Iso8859_5    extends Charset("ISO-8859-5")
    case Iso8859_6    extends Charset("ISO-8859-6")
    case Iso8859_7    extends Charset("ISO-8859-7")
    case Iso8859_8    extends Charset("ISO-8859-8")
    case Iso8859_8I   extends Charset("ISO-8859-8-I")
    case Iso8859_10   extends Charset("ISO-8859-10")
    case Iso8859_13   extends Charset("ISO-8859-13")
    case Iso8859_14   extends Charset("ISO-8859-14")
    case Iso8859_15   extends Charset("ISO-8859-15")
    case Iso8859_16   extends Charset("ISO-8859-16")
    case Koi8R        extends Charset("KOI8-R")
    case Koi8U        extends Charset("KOI8-U")
    case Macintosh    extends Charset("macintosh")
    case Windows874   extends Charset("windows-874")
    case Windows1250  extends Charset("windows-1250")
    case Windows1251  extends Charset("windows-1251")
    case Windows1252  extends Charset("windows-1252")
    case Windows1253  extends Charset("windows-1253")
    case Windows1254  extends Charset("windows-1254")
    case Windows1255  extends Charset("windows-1255")
    case Windows1256  extends Charset("windows-1256")
    case Windows1257  extends Charset("windows-1257")
    case Windows1258  extends Charset("windows-1258")
    case XMacCyrillic extends Charset("x-mac-cyrillic")
    case Gbk          extends Charset("GBK")
    case Gb18030      extends Charset("gb18030")
    case Big5         extends Charset("Big5")
    case EucJp        extends Charset("EUC-JP")
    case Iso2022Jp    extends Charset("ISO-2022-JP")
    case ShiftJis     extends Charset("Shift_JIS")
    case EucKr        extends Charset("EUC-KR")
    case Utf16Be      extends Charset("UTF-16BE")
    case Utf16Le      extends Charset("UTF-16LE")
    case Utf16        extends Charset("UTF-16")
    case Utf32        extends Charset("UTF-32")
    case Utf32Be      extends Charset("UTF-32BE")
    case Utf32Le      extends Charset("UTF-32LE")
    case Utf7         extends Charset("UTF-7")
    case Iso2022Kr    extends Charset("ISO-2022-KR")
    case HzGb2312     extends Charset("HZ-GB-2312")

    /** The text `bytes` hold in this charset. Never fails: each ill-formed sequence becomes one U+FFFD where the encoding's decoder
      * algorithm says, and decoding continues after it.
      */
    def decode(bytes: Span[Byte]): String = Decoders.of(this).decode(bytes)

    /** The text `bytes` hold in this charset, or the first ill-formed sequence: the same algorithm as `decode` in WHATWG's fatal mode. */
    def decodeStrict(bytes: Span[Byte]): Result[Charset.Malformed, String] = Decoders.of(this).decodeStrict(bytes)

end Charset

object Charset:

    /** An ill-formed sequence found by `decodeStrict`: the charset, and the input offset at which its decoder found the sequence
      * ill-formed, which is at or after the sequence's first byte and at the input's size for a sequence the end of the input cuts short.
      */
    final case class Malformed(charset: Charset, offset: Int) derives CanEqual

    /** The charset `label` names, or `Absent` when it names none.
      *
      * The label is trimmed of ASCII whitespace and folded ASCII-only (a Turkish dotted `İ` or a Kelvin sign never matches), then looked
      * up in the WHATWG label table (`latin1`, `cp1252`, `x-sjis`, `utf8`, ...) and in the IANA character-set registry names WHATWG lacks:
      * `UTF-7` with `csUTF7`, `UNICODE-1-1-UTF-7` and `csUnicode11UTF7`; `csUTF16`, `csUTF16BE` and `csUTF16LE`; `UTF-32`, `UTF-32BE` and
      * `UTF-32LE` with their `cs` aliases. `replacement`, `x-user-defined` and the labels WHATWG maps to `replacement` resolve to `Absent`.
      */
    def resolve(label: String): Maybe[Charset] =
        val key = normalize(label)
        Maybe.fromOption(ianaAliases.get(key)).orElse(Maybe.fromOption(WhatwgLabels.byLabel.get(key)).flatMap(byName))

    /** The charset whose WHATWG name is exactly `name` (`"windows-1252"`, `"Shift_JIS"`, `"UTF-7"`), or `Absent`. */
    def byName(name: String): Maybe[Charset] = Maybe.fromOption(byNameTable.get(name))

    /** The charset a leading byte order mark announces, checked in the WHATWG "BOM sniff" order: `Utf8` for EF BB BF, `Utf16` for FE FF
      * or FF FE, else `Absent`. The answer is the charset whose `decode` reads the mark and removes it, so
      * `sniff(bytes).map(_.decode(bytes))` is the WHATWG "decode" of marked input; `Utf16Be` and `Utf16Le` would keep it as content.
      */
    def sniff(bytes: Span[Byte]): Maybe[Charset] =
        if bytes.size >= 3 && (bytes(0) & 0xff) == 0xef && (bytes(1) & 0xff) == 0xbb && (bytes(2) & 0xff) == 0xbf then Present(Utf8)
        else if bytes.size >= 2 && (bytes(0) & 0xff) == 0xfe && (bytes(1) & 0xff) == 0xff then Present(Utf16)
        else if bytes.size >= 2 && (bytes(0) & 0xff) == 0xff && (bytes(1) & 0xff) == 0xfe then Present(Utf16)
        else Absent

    /** `label` as the lookup tables key it: WHATWG ASCII whitespace (TAB, LF, FF, CR, SPACE) trimmed at both ends, ASCII letters lowered. */
    private[kyo] def normalize(label: String): String =
        val start = label.indexWhere(c => !isAsciiWhitespace(c))
        if start < 0 then "" else Ascii.toLower(label.substring(start, label.lastIndexWhere(c => !isAsciiWhitespace(c)) + 1))

    private def isAsciiWhitespace(c: Char): Boolean = c == '\t' || c == '\n' || c == '\f' || c == '\r' || c == ' '

    private[kyo] val ianaAliases: Map[String, Charset] = Map(
        "utf-7"             -> Utf7,
        "csutf7"            -> Utf7,
        "unicode-1-1-utf-7" -> Utf7,
        "csunicode11utf7"   -> Utf7,
        "csutf16"           -> Utf16,
        "csutf16be"         -> Utf16Be,
        "csutf16le"         -> Utf16Le,
        "utf-32"            -> Utf32,
        "csutf32"           -> Utf32,
        "utf-32be"          -> Utf32Be,
        "csutf32be"         -> Utf32Be,
        "utf-32le"          -> Utf32Le,
        "csutf32le"         -> Utf32Le
    )

    private val byNameTable: Map[String, Charset] = values.map(c => c.name -> c).toMap

end Charset
