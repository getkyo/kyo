package kyo.charset

import kyo.*
import kyo.internal.charset.Decoders
import kyo.internal.charset.EmbeddedEncodings
import kyo.internal.charset.IndexTableFixtures

class CharsetTest extends kyo.test.Test[Any]:

    final private case class WhatwgEncoding(name: String, labels: Chunk[String]) derives Schema
    final private case class WhatwgGroup(heading: String, encodings: Chunk[WhatwgEncoding]) derives Schema

    // encodings.json as published, parsed here with kyo-schema-json rather than by the build's generator.
    private lazy val groups: Chunk[WhatwgGroup] =
        Json.decode[Chunk[WhatwgGroup]](EmbeddedEncodings.text).getOrElse(throw new IllegalStateException("encodings.json did not parse"))

    private lazy val whatwgLabels: Chunk[(String, String)] =
        groups.flatMap(_.encodings).flatMap(e => e.labels.map(_ -> e.name))

    private def bytes(values: Int*): Span[Byte] = Span.from(values.map(_.toByte).toArray)

    private def resolvedName(label: String): Maybe[String] = Charset.resolve(label).map(_.name)

    "resolve" - {
        "every WHATWG label resolves to the charset encodings.json names, and to nothing for replacement and x-user-defined" in {
            val wrong = whatwgLabels.collect {
                case (label, name) if name == "replacement" || name == "x-user-defined" =>
                    if resolvedName(label) == Absent then Absent else Present(s"$label -> ${resolvedName(label)}, expected nothing")
                case (label, name) =>
                    if resolvedName(label) == Present(name) then Absent else Present(s"$label -> ${resolvedName(label)}, expected $name")
            }.flatten
            assert(whatwgLabels.size == 228)
            assert(wrong.isEmpty)
        }
        "the single-byte charsets are the ones under 'Legacy single-byte encodings', by name" in {
            val published = groups.filter(_.heading == "Legacy single-byte encodings").flatMap(_.encodings).map(_.name)
            assert(published.size == 28)
            assert(IndexTableFixtures.singleByte.map(_.charset.name) == published)
        }
        "every encoding encodings.json names, except replacement and x-user-defined, is a Charset" in {
            val names = groups.flatMap(_.encodings).map(_.name).filterNot(n => n == "replacement" || n == "x-user-defined")
            assert(names.forall(n => Charset.byName(n).isDefined))
            assert(names.size == Charset.values.size - 7)
        }
        "the labels WHATWG maps to a superset charset (latin1 and us-ascii to windows-1252, gb2312 to GBK, ...) map as it says" in {
            assert(resolvedName("iso-8859-1") == Present("windows-1252"))
            assert(resolvedName("us-ascii") == Present("windows-1252"))
            assert(resolvedName("ascii") == Present("windows-1252"))
            assert(resolvedName("latin1") == Present("windows-1252"))
            assert(resolvedName("iso-8859-9") == Present("windows-1254"))
            assert(resolvedName("iso-8859-11") == Present("windows-874"))
            assert(resolvedName("tis-620") == Present("windows-874"))
            assert(resolvedName("gb2312") == Present("GBK"))
        }
        "utf-16 is UTF-16LE, as WHATWG says; a caller reading it by RFC 2781 applies its own table first" in {
            assert(whatwgLabels.contains("utf-16" -> "UTF-16LE"))
            assert(Charset.resolve("utf-16") == Present(Charset.Utf16Le))
            assert(Charset.resolve("unicodefeff") == Present(Charset.Utf16Le))
            assert(Charset.resolve("unicodefffe") == Present(Charset.Utf16Be))
        }
        "the labels WHATWG maps to replacement resolve to nothing, although two of them name a Charset" in {
            assert(whatwgLabels.contains("hz-gb-2312" -> "replacement"))
            assert(whatwgLabels.contains("iso-2022-kr" -> "replacement"))
            assert(whatwgLabels.contains("iso-2022-cn" -> "replacement"))
            assert(Charset.resolve("hz-gb-2312") == Absent)
            assert(Charset.resolve("iso-2022-kr") == Absent)
            assert(Charset.resolve("csiso2022kr") == Absent)
            assert(Charset.resolve("iso-2022-cn") == Absent)
            assert(Charset.resolve("replacement") == Absent)
            assert(Charset.resolve("x-user-defined") == Absent)
            assert(Charset.byName("HZ-GB-2312") == Present(Charset.HzGb2312))
            assert(Charset.byName("ISO-2022-KR") == Present(Charset.Iso2022Kr))
        }
    }

    "IANA names WHATWG lacks" - {
        "UTF-7 and its RFC 1642 name" in {
            Seq("utf-7", "csutf7", "unicode-1-1-utf-7", "csunicode11utf7").foreach(l => assert(Charset.resolve(l) == Present(Charset.Utf7)))
            succeed
        }
        "the cs aliases of the UTF-16 schemes" in {
            assert(Charset.resolve("csutf16") == Present(Charset.Utf16))
            assert(Charset.resolve("csutf16be") == Present(Charset.Utf16Be))
            assert(Charset.resolve("csutf16le") == Present(Charset.Utf16Le))
        }
        "the UTF-32 schemes and their cs aliases" in {
            assert(Charset.resolve("utf-32") == Present(Charset.Utf32))
            assert(Charset.resolve("csutf32") == Present(Charset.Utf32))
            assert(Charset.resolve("utf-32be") == Present(Charset.Utf32Be))
            assert(Charset.resolve("csutf32be") == Present(Charset.Utf32Be))
            assert(Charset.resolve("utf-32le") == Present(Charset.Utf32Le))
            assert(Charset.resolve("csutf32le") == Present(Charset.Utf32Le))
        }
        "no alias is also a WHATWG label, so an alias never hides a WHATWG answer" in {
            assert(Charset.ianaAliases.keySet.intersect(whatwgLabels.map(_._1).toSet).isEmpty)
        }
    }

    "normalization" - {
        "trims ASCII whitespace at both ends" in {
            assert(Charset.normalize(" \t\r\n\futf-8 \t\r\n\f") == "utf-8")
            assert(Charset.resolve("\tUTF-8 ") == Present(Charset.Utf8))
        }
        "does not trim other whitespace" in {
            assert(Charset.resolve(" utf-8") == Absent)
            assert(Charset.resolve("utf-8 ") == Absent)
        }
        "folds ASCII case" in {
            assert(Charset.resolve("ISO-8859-2") == Present(Charset.Iso8859_2))
            assert(Charset.resolve("Windows-1251") == Present(Charset.Windows1251))
        }
        "does not fold a Kelvin sign or a Turkish i into ASCII" in {
            assert(Charset.resolve("Koi8-r") == Absent)
            assert(Charset.resolve("İso-8859-2") == Absent)
            assert(Charset.resolve("ıso-8859-2") == Absent)
        }
        "no WHATWG label contains the RFC 2231 language separator, which a mail caller strips before resolving" in {
            assert(whatwgLabels.forall((label, _) => !label.contains('*')))
            assert(Charset.resolve("utf-8*en-us") == Absent)
        }
        "an empty or unknown label resolves to nothing" in {
            assert(Charset.resolve("") == Absent)
            assert(Charset.resolve("   ") == Absent)
            assert(Charset.resolve("x-unknown-charset") == Absent)
        }
    }

    "byName" - {
        "matches the WHATWG name exactly" in {
            assert(Charset.byName("windows-1252") == Present(Charset.Windows1252))
            assert(Charset.byName("Shift_JIS") == Present(Charset.ShiftJis))
            assert(Charset.byName("shift_jis") == Absent)
            assert(Charset.byName("UTF-7") == Present(Charset.Utf7))
        }
        "every Charset is found by its own name" in {
            assert(Charset.values.forall(c => Charset.byName(c.name) == Present(c)))
        }
    }

    "decoders" - {
        "exist for every one of the 45 charsets, each reporting its own charset" in {
            assert(Charset.values.size == 45)
            Charset.values.foreach(c => assert(Decoders.of(c).charset == c))
            succeed
        }
        "are one shared instance per charset, never allocated per lookup" in {
            Charset.values.foreach(c => assert(Decoders.of(c) eq Decoders.of(c), c.toString))
            succeed
        }
        "decode through a resolved label" in {
            val koi8r = Charset.resolve(" KOI8-R ").getOrElse(fail("no KOI8-R charset"))
            assert(koi8r.decode(bytes(0xc1, 0xc2)) == "аб")
            assert(Charset.resolve("shift_jis") == Present(Charset.ShiftJis))
            assert(Charset.resolve("ks_c_5601-1987") == Present(Charset.EucKr))
        }
    }

    "decodeStrict" - {
        "agrees with decode on well-formed input, for every charset" in {
            val samples = Map(
                Charset.Utf8      -> bytes(0x61, 0xc3, 0xa9),
                Charset.Utf16Be   -> bytes(0x00, 0x61, 0x20, 0xac),
                Charset.Utf16Le   -> bytes(0x61, 0x00, 0xac, 0x20),
                Charset.Utf16     -> bytes(0xfe, 0xff, 0x00, 0x61),
                Charset.Utf32Be   -> bytes(0, 0, 0, 0x61),
                Charset.Utf32Le   -> bytes(0x61, 0, 0, 0),
                Charset.Utf32     -> bytes(0, 0, 0, 0x61),
                Charset.Utf7      -> bytes("Hi Mom +Jjo-!".map(_.toInt)*),
                Charset.Gb18030   -> bytes(0x81, 0x30, 0x81, 0x30),
                Charset.Gbk       -> bytes(0xa1, 0xa4),
                Charset.Big5      -> bytes(0xa4, 0x40),
                Charset.EucJp     -> bytes(0xa4, 0xa2),
                Charset.ShiftJis  -> bytes(0x82, 0xa0),
                Charset.EucKr     -> bytes(0xb0, 0xa1),
                Charset.Iso2022Jp -> bytes(0x1b, 0x24, 0x42, 0x30, 0x21, 0x1b, 0x28, 0x42),
                Charset.Iso2022Kr -> bytes(0x1b, 0x24, 0x29, 0x43, 0x0e, 0x30, 0x21, 0x0f),
                Charset.HzGb2312  -> bytes("~{<:Ky2;~}".map(_.toInt)*)
            )
            Charset.values.foreach { c =>
                val input = samples.getOrElse(c, bytes(0x61, 0x62))
                assert(c.decodeStrict(input) == Result.succeed(c.decode(input)), c.toString)
                assert(!c.decode(input).contains('�'), c.toString)
            }
            succeed
        }
        "refuses ill-formed input with the charset and the offset its decoder reached" in {
            assert(Charset.Utf8.decodeStrict(bytes(0x61, 0xff, 0x62)) == Result.fail(Charset.Malformed(Charset.Utf8, 1)))
            assert(Charset.Utf8.decodeStrict(bytes(0x61, 0xc3)) == Result.fail(Charset.Malformed(Charset.Utf8, 2)))
            assert(Charset.Windows1252.decodeStrict(bytes(0x61, 0x81)) == Result.succeed("a\u0081"))
            assert(Charset.Iso8859_3.decodeStrict(bytes(0x61, 0xa5)) == Result.fail(Charset.Malformed(Charset.Iso8859_3, 1)))
            assert(Charset.Utf16Be.decodeStrict(bytes(0x00, 0x61, 0x62)) == Result.fail(Charset.Malformed(Charset.Utf16Be, 2)))
            assert(Charset.ShiftJis.decodeStrict(bytes(0x81, 0x31)) == Result.fail(Charset.Malformed(Charset.ShiftJis, 1)))
            assert(Charset.Utf7.decodeStrict(bytes("a~".map(_.toInt)*)) == Result.fail(Charset.Malformed(Charset.Utf7, 2)))
        }
        "reports the first ill-formed sequence when there are several" in {
            assert(Charset.Utf8.decodeStrict(bytes(0xff, 0x61, 0xff)) == Result.fail(Charset.Malformed(Charset.Utf8, 0)))
        }
        "decodes an encoded U+FFFD, which is not ill-formed" in {
            assert(Charset.Utf8.decodeStrict(bytes(0xef, 0xbf, 0xbd)) == Result.succeed("�"))
        }
        "decodes empty input to the empty string" in {
            assert(Charset.Big5.decodeStrict(Span.empty[Byte]) == Result.succeed(""))
        }
    }

    "sniff" - {
        "reads the UTF-8 and UTF-16 marks, answering the charsets that remove them" in {
            assert(Charset.sniff(bytes(0xef, 0xbb, 0xbf, 0x61)) == Present(Charset.Utf8))
            assert(Charset.sniff(bytes(0xfe, 0xff, 0x00, 0x61)) == Present(Charset.Utf16))
            assert(Charset.sniff(bytes(0xff, 0xfe, 0x61, 0x00)) == Present(Charset.Utf16))
        }
        "finds no mark in plain or short input" in {
            assert(Charset.sniff(bytes(0x61, 0x62)) == Absent)
            assert(Charset.sniff(bytes(0xef, 0xbb)) == Absent)
            assert(Charset.sniff(bytes(0xfe)) == Absent)
            assert(Charset.sniff(Span.empty[Byte]) == Absent)
        }
        "the sniffed charset then removes the mark it found, in either byte order" in {
            val little = bytes(0xff, 0xfe, 0x61, 0x00)
            val big    = bytes(0xfe, 0xff, 0x00, 0x61)
            val utf8   = bytes(0xef, 0xbb, 0xbf, 0x61)
            assert(Charset.sniff(little).map(_.decode(little)) == Present("a"))
            assert(Charset.sniff(big).map(_.decode(big)) == Present("a"))
            assert(Charset.sniff(utf8).map(_.decode(utf8)) == Present("a"))
        }
    }

end CharsetTest
