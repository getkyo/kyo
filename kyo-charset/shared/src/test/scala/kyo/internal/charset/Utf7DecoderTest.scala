package kyo.internal.charset

import kyo.*

class Utf7DecoderTest extends kyo.test.Test[Any]:

    import DecoderFixtures.*

    private val decoder = Utf7Decoder

    private def ascii(s: String): Span[Byte] = bytes(s.map(_.toInt)*)

    private val base64Alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

    // Modified base64 of UTF-16 code units, most significant octet first, zero bits padding to a six-bit boundary.
    private def run(units: Seq[Int]): String =
        val bits = units.flatMap(u => (15 to 0 by -1).map(k => (u >> k) & 1))
        bits.grouped(6).map(g => base64Alphabet.charAt((g ++ Seq.fill(6 - g.size)(0)).foldLeft(0)((acc, b) => acc * 2 + b))).mkString

    private val setD = ('A' to 'Z') ++ ('a' to 'z') ++ ('0' to '9') ++ "'(),-./:?"
    private val setO = "!\"#$%&*;<=>@[]^_`{|}"

    private def mismatches(cases: Iterable[(Span[Byte], String)]): Seq[String] =
        cases.iterator.collect {
            case (input, expected) if decoder.decode(input) != expected =>
                s"${show(input)}: ${show(decoder.decode(input))}, expected ${show(expected)}"
        }.take(10).toSeq

    final private case class Example(units: Seq[Int], encoded: String)

    // The examples of RFC 2152 sections 3 and 5, each a Unicode sequence given in hexadecimal and its encoding.
    private val examples: Seq[Example] = Seq(
        Example(Seq(0x0041, 0x2262, 0x0391, 0x002e), "A+ImIDkQ."),
        Example(Seq(0x0048, 0x0069, 0x0020, 0x004d, 0x006f, 0x006d, 0x0020, 0x002d, 0x263a, 0x002d, 0x0021), "Hi Mom -+Jjo--!"),
        Example(Seq(0x65e5, 0x672c, 0x8a9e), "+ZeVnLIqe-"),
        Example(Seq(0x0048, 0x0069, 0x0020, 0x004d, 0x006f, 0x006d, 0x0020, 0x263a, 0x0021), "Hi Mom +Jjo-!"),
        Example(
            Seq(0x0049, 0x0074, 0x0065, 0x006d, 0x0020, 0x0033, 0x0020, 0x0069, 0x0073, 0x0020, 0x00a3, 0x0031, 0x002e),
            "Item 3 is +AKM-1."
        )
    )

    "RFC 2152 examples" - {
        "each decodes to its code units" in {
            examples.foreach(e => assert(decoder.decode(ascii(e.encoded)) == e.units.map(_.toChar).mkString))
            succeed
        }
    }

    "direct characters" - {
        "every single byte decodes as the rules say: Set D, Set O and the four white-space controls as themselves" in {
            val direct = (setD ++ setO ++ " \t\r\n").map(_.toInt).toSet
            assert(direct.size == 62 + 9 + 20 + 4)
            val cases = (0 to 0xff).map(b => (bytes(b), if direct.contains(b) then b.toChar.toString else text(0xfffd)))
            assert(mismatches(cases).isEmpty)
        }
        "\\ and ~, which the RFC omits from both sets, are U+FFFD" in {
            assert(decoder.decode(ascii("a\\b~c")) == text('a', 0xfffd, 'b', 0xfffd, 'c'))
        }
    }

    "after +" - {
        "every byte decodes as the rules say" in {
            val cases = (0 to 0xff).map { b =>
                val expected =
                    if b == '-' then "+"
                    else if base64Alphabet.indexOf(b) >= 0 then text(0xfffd)
                    else text(0xfffd) + (if (setD ++ setO ++ " \t\r\n").contains(b.toChar) then b.toChar.toString else text(0xfffd))
                (bytes('+', b), expected)
            }
            assert(mismatches(cases).isEmpty)
        }
        "+ at the end of the input is U+FFFD" in {
            assert(decoder.decode(ascii("a+")) == text('a', 0xfffd))
        }
    }

    "base64 runs" - {
        "every BMP code unit that is not a surrogate decodes from one run" in {
            val units = (0 to 0xffff).filterNot(u => u >= 0xd800 && u <= 0xdfff)
            assert(decoder.decode(ascii("+" + run(units) + "-")) == units.map(_.toChar).mkString)
        }
        "each such unit decodes alone, ended by -, by a direct character and by the end of the input" in {
            val sample = (0 to 0xffff by 97).filterNot(u => u >= 0xd800 && u <= 0xdfff)
            val cases  = sample.flatMap { u =>
                val encoded = "+" + run(Seq(u))
                Seq(
                    (ascii(encoded + "-"), u.toChar.toString),
                    (ascii(encoded + "."), u.toChar.toString + "."),
                    (ascii(encoded), u.toChar.toString)
                )
            }
            assert(mismatches(cases).isEmpty)
        }
        "a surrogate pair in one run is a supplementary character" in {
            val codePoints = Seq(0x10000, 0x1f600, 0x2a6d6, 0x10ffff)
            codePoints.foreach { cp =>
                val units = Seq(Character.highSurrogate(cp).toInt, Character.lowSurrogate(cp).toInt)
                assert(decoder.decode(ascii("+" + run(units) + "-")) == text(cp))
            }
            succeed
        }
        "a - that ends a run is absorbed; another - after it is a character" in {
            assert(decoder.decode(ascii("+" + run(Seq(0x263a)) + "--!")) == text(0x263a, '-', '!'))
        }
        "a character outside the base64 alphabet ends the run and is decoded as a direct character" in {
            assert(decoder.decode(ascii("+" + run(Seq(0x263a)) + "!")) == text(0x263a, '!'))
            assert(decoder.decode(ascii("+" + run(Seq(0x263a)) + "\\")) == text(0x263a, 0xfffd))
        }
    }

    "ill-formed runs" - {
        "leftover bits of 2 or 4 zero bits are well-formed; non-zero leftover bits are one U+FFFD" in {
            assert(decoder.decode(ascii("+AGE-")) == "a")
            assert(decoder.decode(ascii("+AGF-")) == text('a', 0xfffd))
            assert(decoder.decode(ascii("+AGEAYQ-")) == "aa")
            assert(decoder.decode(ascii("+AGEAYR-")) == text('a', 'a', 0xfffd))
        }
        "six or more leftover bits are one U+FFFD, even when zero" in {
            assert(decoder.decode(ascii("+A-")) == text(0xfffd))
            assert(decoder.decode(ascii("+AA-")) == text(0xfffd))
            assert(decoder.decode(ascii("+AGEA-")) == text('a', 0xfffd))
        }
        "a high surrogate followed by another unit is one U+FFFD, and the unit is decoded" in {
            assert(decoder.decode(ascii("+" + run(Seq(0xd83d, 0x61)) + "-")) == text(0xfffd, 'a'))
        }
        "a lone low surrogate is one U+FFFD" in {
            assert(decoder.decode(ascii("+" + run(Seq(0xde00, 0x61)) + "-")) == text(0xfffd, 'a'))
        }
        "a high surrogate at the end of a run is one U+FFFD; the pair is not matched across runs" in {
            assert(decoder.decode(ascii("+" + run(Seq(0xd83d)) + "-")) == text(0xfffd))
            assert(decoder.decode(ascii("+" + run(Seq(0xd83d)) + "-+" + run(Seq(0xde00)) + "-")) == text(0xfffd, 0xfffd))
        }
        "the end of the input ends a run and applies the same leftover rule" in {
            assert(decoder.decode(ascii("+AGE")) == "a")
            assert(decoder.decode(ascii("+AGF")) == text('a', 0xfffd))
        }
        "bytes from 0x80 end a run and are U+FFFD" in {
            assert(decoder.decode(bytes(Seq('+'.toInt) ++ "AGE".map(_.toInt) ++ Seq(0xe9)*)) == text('a', 0xfffd))
        }
        "empty input decodes to the empty string" in {
            assert(decoder.decode(Span.empty[Byte]) == "")
        }
    }

end Utf7DecoderTest
