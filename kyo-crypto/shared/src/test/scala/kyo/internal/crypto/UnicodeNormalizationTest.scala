package kyo.internal.crypto

/** [[UnicodeNormalization.nfkc]] against the Unicode 15.1.0 conformance file: every line of parts 0 to 3 of `NormalizationTest.txt`
  * holds `c4 == NFKC(c1) == NFKC(c2) == NFKC(c3) == NFKC(c4) == NFKC(c5)`.
  */
class UnicodeNormalizationTest extends kyo.test.Test[Any]:

    import UnicodeNormalizationTest.*

    "the vendored conformance file matches its MANIFEST digest" in {
        assert(TestVectors.names("unicode-normalization") == Seq("NormalizationTest.txt", "LICENSE"))
        val embedded = TestVectors.text("unicode-normalization", "NormalizationTest.txt").getBytes("UTF-8")
        assert(kyo.Hex.encode(kyo.crypto.Sha256.hash(kyo.Span.from(embedded))) ==
            TestVectors.sha256("unicode-normalization", "NormalizationTest.txt"))
    }

    "the file holds the four parts, 19074 lines" in {
        assert(lines.map(_.part).distinct == Seq(0, 1, 2, 3))
        assert(lines.size == 19074)
    }

    Seq(0 -> "specific cases", 1 -> "character by character", 2 -> "canonical order", 3 -> "PRI #29").foreach { case (part, title) =>
        s"part $part, $title: c4 is the NFKC of each of the five columns" in {
            val failures = lines.filter(_.part == part).flatMap { line =>
                val expected = line.columns(3)
                line.columns.zipWithIndex.collect {
                    case (source, i) if !UnicodeNormalization.nfkc(source.toArray).sameElements(expected) =>
                        s"line ${line.number}, c${i + 1} ${hex(source)}: got ${hex(UnicodeNormalization.nfkc(source.toArray).toSeq)}, expected ${hex(expected)}"
                }
            }
            assert(failures.isEmpty, failures.take(10).mkString("\n"))
        }
    }

    "Hangul syllables decompose to jamo and recompose, with and without a trailing consonant" in {
        assert(UnicodeNormalization.nfkc(Array(0x1100, 0x1161)).toSeq == Seq(0xac00))
        assert(UnicodeNormalization.nfkc(Array(0x1100, 0x1161, 0x11a8)).toSeq == Seq(0xac01))
        assert(UnicodeNormalization.nfkc(Array(0xac00, 0x11a8)).toSeq == Seq(0xac01))
        assert(UnicodeNormalization.nfkc(Array(0xd7a3)).toSeq == Seq(0xd7a3))
    }

    "a composition exclusion stays decomposed and a blocked combining mark does not compose" in {
        assert(UnicodeNormalization.nfkc(Array(0x958)).toSeq == Seq(0x915, 0x93c))
        assert(UnicodeNormalization.nfkc(Array(0x65, 0x301)).toSeq == Seq(0xe9))
        assert(UnicodeNormalization.nfkc(Array(0x65, 0x327, 0x301)).toSeq == Seq(0x229, 0x301))
        assert(UnicodeNormalization.nfkc(Array(0x65, 0x301, 0x327)).toSeq == Seq(0x229, 0x301))
        assert(UnicodeNormalization.nfkc(Array(0x61, 0x327, 0x301)).toSeq == Seq(0xe1, 0x327))
        assert(UnicodeNormalization.nfkc(Array(0x65, 0x308, 0x301)).toSeq == Seq(0xeb, 0x301))
        assert(UnicodeNormalization.nfkc(Array(0x2168)).toSeq == Seq(0x49, 0x58))
        assert(UnicodeNormalization.nfkc(Array(0xff50)).toSeq == Seq(0x70))
    }

    "the empty input and an input of starters only are returned as they are" in {
        assert(UnicodeNormalization.nfkc(Array.emptyIntArray).isEmpty)
        val input = Array(0x61, 0x62, 0x1f600)
        assert(UnicodeNormalization.nfkc(input).toSeq == input.toSeq)
        assert(input.toSeq == Seq(0x61, 0x62, 0x1f600))
    }

end UnicodeNormalizationTest

object UnicodeNormalizationTest:

    final case class Line(number: Int, part: Int, columns: Seq[Seq[Int]])

    def hex(codes: Seq[Int]): String = codes.map(c => f"$c%04X").mkString(" ")

    lazy val lines: Seq[Line] =
        var part = -1
        TestVectors.text("unicode-normalization", "NormalizationTest.txt").linesIterator.zipWithIndex.flatMap { case (raw, index) =>
            if raw.startsWith("@Part") then
                part = raw.charAt(5) - '0'
                None
            else
                val line = raw.takeWhile(_ != '#').trim
                if line.isEmpty then None
                else
                    val columns = line.split(";").toSeq.take(5).map(_.trim.split(" ").toSeq.map(Integer.parseInt(_, 16)))
                    require(columns.size == 5, s"line ${index + 1}: $raw")
                    Some(Line(index + 1, part, columns))
                end if
        }.toSeq
    end lines

end UnicodeNormalizationTest
