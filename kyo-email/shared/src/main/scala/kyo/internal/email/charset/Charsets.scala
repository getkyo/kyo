package kyo.internal.email.charset

import kyo.*
import kyo.charset.Charset

/** Charset labels as mail reads them, over [[Charset.resolve]].
  *
  * An RFC 2231 section 5 language suffix (`*` and a language tag, as in `=?US-ASCII*EN?Q?...?=`) is removed first, then the label is
  * normalized as `Charset.resolve` does and looked up in the mail overrides before the WHATWG and IANA tables. The overrides replace
  * answers that are wrong for mail:
  *
  *   - `utf-16` is RFC 2781 UTF-16, where a byte order mark decides and big-endian is the default, not WHATWG's little-endian.
  *     `iso-10646-ucs-2`, `csunicode`, `ucs-2` and `unicode` name the same unmarked 16-bit form, which the IANA entry for ISO-10646-UCS-2
  *     puts in network byte order, so they decode as that UTF-16 too; `unicodefeff` and `unicodefffe` state a byte order and keep WHATWG's
  *     answer.
  *   - `hz-gb-2312`, `iso-2022-kr` and `csiso2022kr` get real decoders instead of WHATWG's replacement decoder, which turns a whole message
  *     into one U+FFFD.
  *   - `iso-2022-cn`, `iso-2022-cn-ext`, `replacement` and `x-user-defined` are unknown charsets.
  *
  * An unknown label resolves to `Absent`, and the caller keeps the bytes undecoded.
  */
private[kyo] object Charsets:

    /** The charset `label` names in mail, or `Absent` when it names none the module knows. */
    def resolve(label: String): Maybe[Charset] =
        val key = normalize(label)
        Maybe.fromOption(mailOverrides.get(key)) match
            case Present(overridden) => overridden
            case Absent              => Charset.resolve(key)
    end resolve

    /** `label` as the lookup tables key it: its language suffix removed, then normalized as `Charset.resolve` does. */
    def normalize(label: String): String =
        Charset.normalize(label.indexOf('*') match
            case -1    => label
            case index => label.substring(0, index))

    private[charset] val mailOverrides: Map[String, Maybe[Charset]] = Map(
        "utf-16"          -> Present(Charset.Utf16),
        "iso-10646-ucs-2" -> Present(Charset.Utf16),
        "csunicode"       -> Present(Charset.Utf16),
        "ucs-2"           -> Present(Charset.Utf16),
        "unicode"         -> Present(Charset.Utf16),
        "hz-gb-2312"      -> Present(Charset.HzGb2312),
        "iso-2022-kr"     -> Present(Charset.Iso2022Kr),
        "csiso2022kr"     -> Present(Charset.Iso2022Kr),
        "iso-2022-cn"     -> Absent,
        "iso-2022-cn-ext" -> Absent,
        "replacement"     -> Absent,
        "x-user-defined"  -> Absent
    )

end Charsets
