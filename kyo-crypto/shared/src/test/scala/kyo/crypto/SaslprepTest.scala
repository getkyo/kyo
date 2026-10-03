package kyo.crypto

import kyo.*
import kyo.internal.crypto.TestVectors
import kyo.internal.crypto.UnicodeTables

/** [[Saslprep.prepare]] against RFC 4013's examples and the tables, every non-ASCII code point written as a number through [[text]] so
  * no invisible or format character sits in the source.
  */
class SaslprepTest extends kyo.test.Test[Any]:

    import SaslprepTest.*

    "the vendored RFC 4013 text matches its MANIFEST digest" in {
        assert(TestVectors.names(set) == Seq("rfc4013.txt"))
        val embedded = TestVectors.text(set, "rfc4013.txt").getBytes("UTF-8")
        assert(Hex.encode(Sha256.hash(Span.from(embedded))) == TestVectors.sha256(set, "rfc4013.txt"))
    }

    "RFC 4013 section 3" - {
        "reads the seven examples out of the RFC text" in {
            assert(examples.map(_.number) == (1 to 7))
            assert(examples.map(_.input) == Seq(
                text('I', 0x00ad, 'X'),
                "user",
                "USER",
                text(0x00aa),
                text(0x2168),
                text(0x0007),
                text(0x0627, '1')
            ))
            assert(examples.map(_.output) == Seq(Some("IX"), Some("user"), Some("USER"), Some("a"), Some("IX"), None, None))
        }

        "each example prepares to its output, the two errors to their failures" in {
            examples.foreach { e =>
                val expected = e.output match
                    case Some(text)                                  => Result.succeed(text)
                    case None if e.comment.contains("prohibited")    => Result.fail(Saslprep.Failure.Prohibited(0))
                    case None if e.comment.contains("bidirectional") => Result.fail(Saslprep.Failure.Bidirectional)
                    case None                                        => fail(s"example ${e.number}: ${e.comment}")
                assert(Saslprep.prepare(e.input) == expected, s"example ${e.number}")
            }
        }
    }

    "mapping" - {
        "every non-ASCII space of C.1.2 becomes U+0020" in {
            UnicodeTables.nonAsciiSpace.grouped(2).foreach { case Array(from, to) =>
                (from to to).foreach { code =>
                    assert(Saslprep.prepare("a" + text(code) + "b") == Result.succeed("a b"), f"U+$code%04X")
                }
            }
        }

        "every code point of B.1 is dropped, U+200B as a space first because C.1.2 is checked before B.1" in {
            UnicodeTables.mappedToNothing.grouped(2).foreach { case Array(from, to) =>
                (from to to).foreach { code =>
                    val expected = if code == 0x200b then "a b" else "ab"
                    assert(Saslprep.prepare("a" + text(code) + "b") == Result.succeed(expected), f"U+$code%04X")
                }
            }
        }

        "ASCII letters, digits and punctuation are unchanged" in {
            val ascii = "pencil Passw0rd!@#$%^&*()-_=+[]{};:'\",.<>/?`~|\\"
            assert(Saslprep.prepare(ascii) == Result.succeed(ascii))
        }
    }

    "empty" - {
        "the empty text" in {
            assert(Saslprep.prepare("") == Result.fail(Saslprep.Failure.Empty))
        }

        "a text of soft hyphens and a zero-width joiner only" in {
            assert(Saslprep.prepare(text(0x00ad, 0x200d, 0x00ad)) == Result.fail(Saslprep.Failure.Empty))
        }
    }

    "prohibited" - {
        "a control character, at its offset" in {
            assert(Saslprep.prepare("ab" + text(0x0007) + "c") == Result.fail(Saslprep.Failure.Prohibited(2)))
            assert(Saslprep.prepare("ab" + text(0x007f) + "c") == Result.fail(Saslprep.Failure.Prohibited(2)))
        }

        "an emoji, unassigned in Unicode 3.2, at the offset of its first UTF-16 unit" in {
            assert(Saslprep.prepare(text(0x1f600)) == Result.fail(Saslprep.Failure.Prohibited(0)))
            assert(Saslprep.prepare("ab" + text(0x1f600) + "cd") == Result.fail(Saslprep.Failure.Prohibited(2)))
        }

        "an unpaired surrogate" in {
            assert(Saslprep.prepare("a" + 0xd800.toChar + "b") == Result.fail(Saslprep.Failure.Prohibited(1)))
            assert(Saslprep.prepare(0xdc00.toChar.toString) == Result.fail(Saslprep.Failure.Prohibited(0)))
        }

        "the offset counts the dropped characters before it" in {
            assert(Saslprep.prepare(text(0x00ad, 0x00ad, 'a', 0x0001)) == Result.fail(Saslprep.Failure.Prohibited(3)))
        }

        "a private use, noncharacter, tagging and directional override code point" in {
            assert(Saslprep.prepare(text(0xe000)) == Result.fail(Saslprep.Failure.Prohibited(0)))
            assert(Saslprep.prepare(text(0xfdd0)) == Result.fail(Saslprep.Failure.Prohibited(0)))
            assert(Saslprep.prepare("a" + text(0xe0020)) == Result.fail(Saslprep.Failure.Prohibited(1)))
            assert(Saslprep.prepare("a" + text(0x202e)) == Result.fail(Saslprep.Failure.Prohibited(1)))
        }
    }

    "bidirectional" - {
        // U+05D0 and U+05D1 are Hebrew letters; the Arabic letters spell a seven-letter word.
        val hebrew = text(0x05d0, 0x05d1)
        val arabic = text(0x0627, 0x0644, 0x0639, 0x0631, 0x0628, 0x064a, 0x0629)

        "a right-to-left text passes" in {
            assert(Saslprep.prepare(hebrew) == Result.succeed(hebrew))
            assert(Saslprep.prepare(arabic) == Result.succeed(arabic))
        }

        "a right-to-left text with a left-to-right character" in {
            assert(Saslprep.prepare(text(0x05d0, 'a', 0x05d1)) == Result.fail(Saslprep.Failure.Bidirectional))
        }

        "a right-to-left text that does not end right-to-left" in {
            assert(Saslprep.prepare(text(0x05d0, '1')) == Result.fail(Saslprep.Failure.Bidirectional))
        }

        "a right-to-left text that does not start right-to-left" in {
            assert(Saslprep.prepare(text('1', 0x05d0)) == Result.fail(Saslprep.Failure.Bidirectional))
        }

        "a left-to-right text with digits and a combining mark passes, the mark staying on the digit" in {
            assert(Saslprep.prepare(text('a', '1', 0x0301)) == Result.succeed(text('a', '1', 0x0301)))
            assert(Saslprep.prepare(text(0x0301)) == Result.succeed(text(0x0301)))
        }
    }

    "NFKC" - {
        "fullwidth letters become ASCII" in {
            assert(Saslprep.prepare(text(0xff50, 0xff41, 0xff53, 0xff53, 0xff57, 0xff4f, 0xff52, 0xff44)) == Result.succeed("password"))
        }

        "fullwidth digits and a no-break space" in {
            assert(Saslprep.prepare("pass" + text(0x00a0, 0xff11, 0xff12, 0xff13)) == Result.succeed("pass 123"))
        }

        "a decomposed accent recomposes" in {
            assert(Saslprep.prepare(text('e', 0x0301)) == Result.succeed(text(0x00e9)))
            assert(Saslprep.prepare("cafe" + text(0x0301)) == Result.succeed("caf" + text(0x00e9)))
            assert(Saslprep.prepare("caf" + text(0x00e9)) == Result.succeed("caf" + text(0x00e9)))
        }

        "a Roman numeral and a superscript become their letters and digits" in {
            assert(Saslprep.prepare(text(0x2168, 0x00b2)) == Result.succeed("IX2"))
        }
    }

    "libpq's order: the prohibition and the bidirectional checks read the mapped text, not the NFKC output" - {
        "no permitted code point normalizes to a prohibited one, so the two orders agree on the prohibition" in {
            def prohibited(code: Int) = inTable(UnicodeTables.prohibitedOutput, code) || inTable(UnicodeTables.unassigned, code)
            def mapped(code: Int)     = inTable(UnicodeTables.nonAsciiSpace, code) || inTable(UnicodeTables.mappedToNothing, code)
            val disagreeing           = UnicodeTables.decompositionCodePoints.indices.filter { i =>
                val code = UnicodeTables.decompositionCodePoints(i)
                !prohibited(code) && !mapped(code) &&
                (UnicodeTables.decompositionStarts(i) until UnicodeTables.decompositionStarts(i + 1))
                    .exists(p => prohibited(UnicodeTables.decompositionData(p)))
            }
            assert(disagreeing.isEmpty, disagreeing.map(i => f"U+${UnicodeTables.decompositionCodePoints(i)}%04X").mkString(", "))
        }

        "a right-to-left ligature whose decomposition ends in a combining mark passes, as pg_saslprep answers it" in {
            // U+FC5E, an Arabic ligature of shadda and dammatan, decomposes to a space and two combining marks.
            assert(Saslprep.prepare(text(0x0627, 0xfc5e)) == Result.succeed(text(0x0627, 0x0020, 0x064c, 0x0651)))
        }
    }

end SaslprepTest

object SaslprepTest:

    val set = "ietf-rfc4013"

    final case class Example(number: Int, input: String, output: Option[String], comment: String)

    /** The string of `codePoints`, each a code point or an ASCII character literal. */
    def text(codePoints: Int*): String =
        val out = new java.lang.StringBuilder
        codePoints.foreach(c => out.appendCodePoint(c))
        out.toString
    end text

    def inTable(table: Array[Int], code: Int): Boolean =
        table.grouped(2).exists { case Array(from, to) => code >= from && code <= to }

    /** The rows of section 3's table: number, input, output when the row has one, comment; `<U+XXXX>` stands for the code point. */
    lazy val examples: Seq[Example] =
        val rfc                            = TestVectors.text(set, "rfc4013.txt")
        val start                          = rfc.indexOf("#  Input            Output     Comments")
        val end                            = rfc.indexOf("4.  Security Considerations", start)
        val token                          = "<U\\+([0-9A-F]{4})>".r
        def decode(column: String): String = token.replaceAllIn(column, m => text(Integer.parseInt(m.group(1), 16)))
        rfc.substring(start, end).linesIterator.drop(2).map(_.trim).filter(_.nonEmpty).map { line =>
            line.split("\\s{2,}").toSeq match
                case Seq(number, input, output, comment) => Example(number.toInt, decode(input), Some(decode(output)), comment)
                case Seq(number, input, comment)         => Example(number.toInt, decode(input), None, comment)
                case other                               => throw new IllegalStateException(s"unexpected example row: $line")
        }.toSeq
    end examples

end SaslprepTest
