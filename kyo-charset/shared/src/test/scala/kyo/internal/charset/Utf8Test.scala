package kyo.internal.charset

import kyo.*
import kyo.internal.charset.vectors.*

class Utf8Test extends kyo.test.Test[Any]:

    private def bytes(values: Int*): Span[Byte] = Span.from(values.map(_.toByte).toArray)

    private def hex(text: String): Span[Byte] =
        val digits = text.filterNot(c => c == ' ' || c == '\t')
        if digits == "nothing" then Span.empty[Byte]
        else Span.from(digits.grouped(2).map(pair => Integer.parseInt(pair, 16).toByte).toArray)
    end hex

    private def show(span: Span[Byte]): String = span.toArray.map(b => f"${b & 0xff}%02x").mkString(" ")

    private enum Case:
        case ValidAscii(id: String, text: String)
        case ValidHex(id: String, input: Span[Byte])
        case InvalidHex(id: String, input: Span[Byte], replaced: Span[Byte])
    end Case

    // The formats utf8tests.txt documents in its own header: `num:valid:ASCII bytes`, `num:valid hex:hexString` and
    // `num:invalid hex:hexString:hexString2:hexString3`, where hexString3 is the input with each invalid sequence replaced by U+FFFD.
    private lazy val cases: Chunk[Case] =
        Chunk.from(EmbeddedUtf8tests.text.split("\n").iterator.filterNot(l => l.startsWith("#") || l.trim.isEmpty).map { line =>
            val firstColon  = line.indexOf(':')
            val secondColon = line.indexOf(':', firstColon + 1)
            val id          = line.substring(0, firstColon)
            val kind        = line.substring(firstColon + 1, secondColon).trim
            val rest        = line.substring(secondColon + 1)
            kind match
                case "valid"       => Case.ValidAscii(id, rest)
                case "valid hex"   => Case.ValidHex(id, hex(rest))
                case "invalid hex" =>
                    val fields = rest.split(":")
                    Case.InvalidHex(id, hex(fields(0)), hex(fields(2)))
                case other => throw new IllegalStateException(s"utf8tests.txt line $id has an unknown kind '$other'")
            end match
        })

    "utf8tests.txt" - {
        "has 2 valid ASCII cases, 75 valid hex cases and 145 invalid hex cases" in {
            assert(cases.count(_.isInstanceOf[Case.ValidAscii]) == 2)
            assert(cases.count(_.isInstanceOf[Case.ValidHex]) == 75)
            assert(cases.count(_.isInstanceOf[Case.InvalidHex]) == 145)
            assert(cases.size == 222)
        }
        "every valid ASCII case decodes to itself" in {
            val failures = cases.collect {
                case Case.ValidAscii(id, text) if Utf8.decode(Span.from(text.getBytes("US-ASCII"))) != text => id
            }
            assert(failures.isEmpty)
        }
        "every valid case decodes without replacement and encodes back to the same bytes" in {
            val failures = cases.collect {
                case Case.ValidHex(id, input) if !Utf8.encode(Utf8.decode(input)).is(input) => s"$id: ${show(input)}"
            }
            assert(failures.isEmpty)
        }
        "every invalid case decodes to the published replacement, one U+FFFD per maximal subpart" in {
            val failures = cases.collect {
                case Case.InvalidHex(id, input, replaced) if !Utf8.encode(Utf8.decode(input)).is(replaced) =>
                    s"$id: ${show(input)} gave ${show(Utf8.encode(Utf8.decode(input)))}, expected ${show(replaced)}"
            }
            assert(failures.isEmpty)
        }
    }

    "decoding valid input" - {
        "one sequence of each length" in {
            assert(Utf8.decode(bytes(0x61)) == "a")
            assert(Utf8.decode(bytes(0xc3, 0xa9)) == "\u00e9")
            assert(Utf8.decode(bytes(0xe2, 0x82, 0xac)) == "\u20ac")
            assert(Utf8.decode(bytes(0xf0, 0x9f, 0x98, 0x80)) == "\ud83d\ude00")
        }
        "the first and last code point of each length" in {
            assert(Utf8.decode(bytes(0x7f)) == "\u007f")
            assert(Utf8.decode(bytes(0xc2, 0x80)) == "\u0080")
            assert(Utf8.decode(bytes(0xdf, 0xbf)) == "\u07ff")
            assert(Utf8.decode(bytes(0xe0, 0xa0, 0x80)) == "\u0800")
            assert(Utf8.decode(bytes(0xef, 0xbf, 0xbf)) == "\uffff")
            assert(Utf8.decode(bytes(0xf0, 0x90, 0x80, 0x80)) == "\ud800\udc00")
            assert(Utf8.decode(bytes(0xf4, 0x8f, 0xbf, 0xbf)) == "\udbff\udfff")
        }
        "the code points on either side of the surrogate range" in {
            assert(Utf8.decode(bytes(0xed, 0x9f, 0xbf)) == "\ud7ff")
            assert(Utf8.decode(bytes(0xee, 0x80, 0x80)) == "\ue000")
        }
    }

    "byte order mark" - {
        "a leading EF BB BF is removed" in {
            assert(Utf8.decode(bytes(0xef, 0xbb, 0xbf, 0x61)) == "a")
        }
        "only the first one is removed" in {
            assert(Utf8.decode(bytes(0xef, 0xbb, 0xbf, 0xef, 0xbb, 0xbf, 0x61)) == "\ufeffa")
        }
        "one later in the text is content" in {
            assert(Utf8.decode(bytes(0x61, 0xef, 0xbb, 0xbf)) == "a\ufeff")
        }
        "a truncated one is malformed input" in {
            assert(Utf8.decode(bytes(0xef, 0xbb)) == "\ufffd")
        }
    }

    "encoder" - {
        "encodes each length of sequence" in {
            assert(Utf8.encode("aé€😀").is(bytes(0x61, 0xc3, 0xa9, 0xe2, 0x82, 0xac, 0xf0, 0x9f, 0x98, 0x80)))
        }
        "encodes the boundaries of each length" in {
            assert(Utf8.encode("\u007f\u0080\u07ff\u0800\uffff").is(bytes(
                0x7f, 0xc2, 0x80, 0xdf, 0xbf, 0xe0, 0xa0, 0x80, 0xef, 0xbf, 0xbf
            )))
            assert(Utf8.encode("\ud800\udc00").is(bytes(0xf0, 0x90, 0x80, 0x80)))
            assert(Utf8.encode("\udbff\udfff").is(bytes(0xf4, 0x8f, 0xbf, 0xbf)))
        }
        "encodes the code points on either side of the surrogate range" in {
            assert(Utf8.encode("\ud7ff\ue000").is(bytes(0xed, 0x9f, 0xbf, 0xee, 0x80, 0x80)))
        }
        "turns a lone lead or trail surrogate into U+FFFD" in {
            assert(Utf8.encode("a\ud800b").is(bytes(0x61, 0xef, 0xbf, 0xbd, 0x62)))
            assert(Utf8.encode("a\udc00b").is(bytes(0x61, 0xef, 0xbf, 0xbd, 0x62)))
            assert(Utf8.encode("\ud800").is(bytes(0xef, 0xbf, 0xbd)))
            assert(Utf8.encode("\udc00\ud800").is(bytes(0xef, 0xbf, 0xbd, 0xef, 0xbf, 0xbd)))
        }
        "encodes the empty string to no bytes" in {
            assert(Utf8.encode("").isEmpty)
        }
    }

end Utf8Test
