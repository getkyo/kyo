package kyo.internal.email.charset

import kyo.*
import kyo.charset.Charset

class CharsetsTest extends kyo.test.Test[Any]:

    "mail overrides" - {
        "bare utf-16 is RFC 2781 UTF-16, where a byte order mark decides and big-endian is the default; WHATWG says UTF-16LE" in {
            assert(Charset.resolve("utf-16") == Present(Charset.Utf16Le))
            assert(Charsets.resolve("utf-16") == Present(Charset.Utf16))
        }
        "iso-10646-ucs-2, csunicode, ucs-2 and unicode are that UTF-16 too: the IANA entry ISO-10646-UCS-2 (alias csUnicode) asks for network byte order, and one name for one form gets one answer; WHATWG says UTF-16LE" in {
            Seq("iso-10646-ucs-2", "csunicode", "ucs-2", "unicode").foreach { label =>
                assert(Charset.resolve(label) == Present(Charset.Utf16Le))
                assert(Charsets.resolve(label) == Present(Charset.Utf16))
            }
            val charset = Charsets.resolve("ISO-10646-UCS-2").getOrElse(fail("ISO-10646-UCS-2 did not resolve"))
            assert(charset.decode(Span.from(Array[Byte](0x00, 0x61, 0x04, 0x30))) == "aа")
        }
        "unicodefeff and unicodefffe name a byte order, so they keep WHATWG's answer" in {
            assert(Charsets.resolve("unicodefeff") == Present(Charset.Utf16Le))
            assert(Charsets.resolve("unicodefffe") == Present(Charset.Utf16Be))
        }
        "hz-gb-2312 is HZ-GB-2312 (RFC 1843), not WHATWG's replacement decoder, which would turn the whole text into one U+FFFD" in {
            assert(Charset.resolve("hz-gb-2312") == Absent)
            assert(Charsets.resolve("hz-gb-2312") == Present(Charset.HzGb2312))
        }
        "iso-2022-kr and csiso2022kr are ISO-2022-KR (RFC 1557), not the replacement decoder" in {
            assert(Charset.resolve("iso-2022-kr") == Absent)
            assert(Charsets.resolve("iso-2022-kr") == Present(Charset.Iso2022Kr))
            assert(Charsets.resolve("csiso2022kr") == Present(Charset.Iso2022Kr))
        }
        "iso-2022-cn and iso-2022-cn-ext are unknown: no decoder, and the replacement decoder would lose the text" in {
            assert(Charsets.resolve("iso-2022-cn") == Absent)
            assert(Charsets.resolve("iso-2022-cn-ext") == Absent)
        }
        "replacement and x-user-defined name no charset a message can be in" in {
            assert(Charsets.resolve("replacement") == Absent)
            assert(Charsets.resolve("x-user-defined") == Absent)
        }
        "an override is looked up after normalization, so case and white space do not bypass it" in {
            assert(Charsets.resolve(" UTF-16\t") == Present(Charset.Utf16))
            assert(Charsets.resolve("HZ-GB-2312") == Present(Charset.HzGb2312))
        }
        "a label no override names resolves as Charset.resolve does" in {
            Seq("utf-8", "latin1", "us-ascii", "gb2312", "shift_jis", "utf-7", "utf-32", "ks_c_5601-1987", "x-unknown-charset").foreach {
                label => assert(Charsets.resolve(label) == Charset.resolve(label), label)
            }
            succeed
        }
    }

    "the RFC 2231 language suffix" - {
        "is removed before lookup" in {
            assert(Charsets.normalize("US-ASCII*EN") == "us-ascii")
            assert(Charsets.resolve("utf-8*en-us") == Present(Charset.Utf8))
            assert(Charsets.resolve("ISO-8859-1*fr") == Present(Charset.Windows1252))
        }
        "is removed before an override, too" in {
            assert(Charsets.resolve("utf-16*en") == Present(Charset.Utf16))
        }
        "leaves nothing to resolve when the label is only a suffix" in {
            assert(Charsets.resolve("*en") == Absent)
        }
    }

end CharsetsTest
