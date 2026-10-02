package kyo.internal.crypto

import java.util.Arrays

/** The generated [[UnicodeTables]] against the files they were generated from and against PostgreSQL's `saslprep.c`, whose six arrays
  * are the reference the SCRAM secrets on the server follow.
  */
class UnicodeTablesTest extends kyo.test.Test[Any]:

    import UnicodeTablesTest.*

    "the vendored inputs and the libpq source match their MANIFEST digests" in {
        assert(TestVectors.names("postgres-saslprep") == Seq("saslprep.c", "COPYRIGHT"))
        assert(UnicodeInputs.names("unicode-15.1.0") == Seq("UnicodeData.txt", "CompositionExclusions.txt", "LICENSE"))
        assert(UnicodeInputs.names("ietf-rfc3454") == Seq("rfc3454.txt"))
        Seq("saslprep.c", "COPYRIGHT").foreach { name =>
            val embedded = TestVectors.text("postgres-saslprep", name).getBytes("UTF-8")
            assert(kyo.Hex.encode(kyo.crypto.Sha256.hash(kyo.Span.from(embedded))) == TestVectors.sha256("postgres-saslprep", name))
        }
        Seq(
            "unicode-15.1.0" -> "UnicodeData.txt",
            "unicode-15.1.0" -> "CompositionExclusions.txt",
            "ietf-rfc3454"   -> "rfc3454.txt"
        ).foreach {
            case (set, name) =>
                val embedded = UnicodeInputs.text(set, name).getBytes("UTF-8")
                assert(kyo.Hex.encode(kyo.crypto.Sha256.hash(kyo.Span.from(embedded))) == UnicodeInputs.sha256(set, name))
        }
    }

    "the six stringprep tables hold the code points of the six arrays of libpq's saslprep.c" - {
        Seq(
            "non_ascii_space_ranges"            -> UnicodeTables.nonAsciiSpace,
            "commonly_mapped_to_nothing_ranges" -> UnicodeTables.mappedToNothing,
            "prohibited_output_ranges"          -> UnicodeTables.prohibitedOutput,
            "unassigned_codepoint_ranges"       -> UnicodeTables.unassigned,
            "RandALCat_codepoint_ranges"        -> UnicodeTables.randALCat,
            "LCat_codepoint_ranges"             -> UnicodeTables.lCat
        ).foreach { case (name, generated) =>
            name in {
                val libpq = libpqArray(name)
                assert(libpq.nonEmpty)
                assert(
                    pairs(generated) == merged(pairs(libpq.toArray)),
                    s"$name: generated ${generated.length / 2} ranges, libpq ${libpq.length / 2}"
                )
            }
        }

        "libpq's prohibited array leaves three adjacent ranges of planes 14 to 16 unmerged, and nothing else differs" in {
            val libpq = pairs(libpqArray("prohibited_output_ranges").toArray)
            assert(libpq.size == 36)
            assert(pairs(UnicodeTables.prohibitedOutput).size == 34)
            assert(libpq.diff(pairs(UnicodeTables.prohibitedOutput)) == Seq((0xefffe, 0xeffff), (0xf0000, 0xfffff), (0x100000, 0x10ffff)))
            assert(pairs(UnicodeTables.prohibitedOutput).diff(libpq) == Seq((0xefffe, 0x10ffff)))
        }
    }

    "every range table is sorted, disjoint and not adjacent" in {
        Seq(
            UnicodeTables.nonAsciiSpace,
            UnicodeTables.mappedToNothing,
            UnicodeTables.prohibitedOutput,
            UnicodeTables.unassigned,
            UnicodeTables.randALCat,
            UnicodeTables.lCat
        ).foreach { table =>
            assert(table.length % 2 == 0)
            var i = 0
            while i < table.length do
                assert(table(i) <= table(i + 1))
                if i + 2 < table.length then assert(table(i + 1) + 1 < table(i + 2))
                i += 2
            end while
        }
    }

    "the prohibited output is the union of the ten C tables of RFC 3454, and A.1 is its own table" in {
        val rfc   = UnicodeInputs.text("ietf-rfc3454", "rfc3454.txt")
        val union = Seq("C.1.2", "C.2.1", "C.2.2", "C.3", "C.4", "C.5", "C.6", "C.7", "C.8", "C.9").flatMap(rfcTable(rfc, _))
        assert(merged(union) == pairs(UnicodeTables.prohibitedOutput))
        assert(merged(rfcTable(rfc, "A.1")) == pairs(UnicodeTables.unassigned))
        assert(merged(rfcTable(rfc, "C.1.2")) == pairs(UnicodeTables.nonAsciiSpace))
        assert(merged(rfcTable(rfc, "B.1")) == pairs(UnicodeTables.mappedToNothing))
        assert(merged(rfcTable(rfc, "D.1")) == pairs(UnicodeTables.randALCat))
        assert(merged(rfcTable(rfc, "D.2")) == pairs(UnicodeTables.lCat))
        assert(rfcTable(rfc, "A.1").size == 396)
        assert(rfcTable(rfc, "B.1").size == 27)
        assert(rfcTable(rfc, "C.1.2").size == 17)
        assert(rfcTable(rfc, "C.4").size == 18)
        assert(rfcTable(rfc, "D.1").size == 34)
        assert(rfcTable(rfc, "D.2").size == 360)
    }

    "every combining class of UnicodeData.txt is in the table, and nothing else is" in {
        val fromFile = unicodeData.collect { case (code, klass, _) if klass != 0 => code -> klass }
        assert(fromFile.size == 922)
        assert(UnicodeTables.combiningClassCodePoints.length == fromFile.size)
        assert(UnicodeTables.combiningClassCodePoints.toSeq == fromFile.map(_._1))
        assert(UnicodeTables.combiningClassValues.toSeq == fromFile.map(_._2))
    }

    "every decomposition of UnicodeData.txt, fully expanded, is the table's entry, and nothing else is" in {
        val mappings = unicodeData.collect { case (code, _, Some(mapping)) => code -> mapping }.toMap
        assert(mappings.size == 5857)
        def expand(code: Int): Seq[Int] = mappings.get(code) match
            case Some((_, parts)) => parts.flatMap(expand)
            case None             => Seq(code)
        val expected = mappings.keys.toSeq.sorted.map(code => code -> expand(code))
        assert(UnicodeTables.decompositionCodePoints.toSeq == expected.map(_._1))
        assert(UnicodeTables.decompositionStarts.length == expected.size + 1)
        expected.zipWithIndex.foreach { case ((code, parts), i) =>
            val from = UnicodeTables.decompositionStarts(i)
            val to   = UnicodeTables.decompositionStarts(i + 1)
            assert(UnicodeTables.decompositionData.slice(from, to).toSeq == parts, f"U+$code%04X")
        }
        assert(UnicodeTables.decompositionStarts.last == UnicodeTables.decompositionData.length)
        assert(expand(0x2168) == Seq(0x49, 0x58))
        assert(expand(0xfb2c) == Seq(0x5e9, 0x5bc, 0x5c1))
    }

    "the primary composites are the canonical pairs outside the composition exclusions" in {
        val classes = unicodeData.collect { case (code, klass, _) if klass != 0 => code }.toSet
        val listed  = UnicodeInputs.text("unicode-15.1.0", "CompositionExclusions.txt").linesIterator
            .map(_.takeWhile(_ != '#').trim).filter(_.nonEmpty).map(Integer.parseInt(_, 16)).toSet
        assert(listed.size == 81)
        val canonical = unicodeData.collect { case (code, _, Some((false, parts))) => code -> parts }
        val expected  = canonical.collect {
            case (code, Seq(a, b)) if !listed.contains(code) && !classes.contains(a) && !classes.contains(code) => (a, b, code)
        }.sorted
        assert(expected.nonEmpty)
        assert(UnicodeTables.compositionFirst.toSeq == expected.map(_._1))
        assert(UnicodeTables.compositionSecond.toSeq == expected.map(_._2))
        assert(UnicodeTables.compositionResult.toSeq == expected.map(_._3))
        assert(expected.contains((0x65, 0x301, 0xe9)))
        assert(!expected.exists(_._3 == 0x958))
        assert(!expected.exists(_._3 == 0x2adc))
        assert(!expected.exists(_._3 == 0x340))
        assert(!expected.exists(c => c._3 >= 0xac00 && c._3 <= 0xd7a3))
    }

    "a code point is found by binary search in the sorted arrays" in {
        assert(Arrays.binarySearch(UnicodeTables.combiningClassCodePoints, 0x301) >= 0)
        assert(UnicodeTables.combiningClassValues(Arrays.binarySearch(UnicodeTables.combiningClassCodePoints, 0x301)) == 230)
        assert(Arrays.binarySearch(UnicodeTables.combiningClassCodePoints, 0x41) < 0)
        assert(Arrays.binarySearch(UnicodeTables.decompositionCodePoints, 0x00a0) >= 0)
        assert(Arrays.binarySearch(UnicodeTables.decompositionCodePoints, 0x41) < 0)
    }

end UnicodeTablesTest

object UnicodeTablesTest:

    /** `(code point, combining class, decomposition)`, the decomposition tagged `true` when compatibility, for every line of the file
      * that names one code point.
      */
    lazy val unicodeData: Seq[(Int, Int, Option[(Boolean, Seq[Int])])] =
        UnicodeInputs.text("unicode-15.1.0", "UnicodeData.txt").linesIterator.filter(_.nonEmpty).map { line =>
            val fields  = line.split(";", -1)
            val code    = Integer.parseInt(fields(0), 16)
            val klass   = fields(3).toInt
            val mapping =
                if fields(5).isEmpty then None
                else if fields(5).startsWith("<") then
                    Some((true, fields(5).substring(fields(5).indexOf('>') + 1).trim.split(" ").toSeq.map(Integer.parseInt(_, 16))))
                else Some((false, fields(5).split(" ").toSeq.map(Integer.parseInt(_, 16))))
            (code, klass, mapping)
        }.toSeq

    /** The hex values of `static const char32_t <name>[] = { ... };` in the vendored `saslprep.c`, comments removed. */
    def libpqArray(name: String): Seq[Int] =
        val text  = TestVectors.text("postgres-saslprep", "saslprep.c")
        val start = text.indexOf(s"static const char32_t $name[] =")
        require(start >= 0, s"$name not found in saslprep.c")
        val open  = text.indexOf('{', start)
        val close = text.indexOf("};", open)
        val body  = text.substring(open + 1, close).replaceAll("/\\*[\\s\\S]*?\\*/", "")
        "0x([0-9A-Fa-f]+)".r.findAllMatchIn(body).map(m => Integer.parseInt(m.group(1), 16)).toSeq
    end libpqArray

    /** The entries of one RFC 3454 table as inclusive pairs, in the text's order. */
    def rfcTable(rfc: String, table: String): Seq[(Int, Int)] =
        val start = rfc.indexOf(s"----- Start Table $table -----")
        val end   = rfc.indexOf(s"----- End Table $table -----", start)
        require(start >= 0 && end > start, s"table $table not found")
        val entry = "^([0-9A-F]{4,6})(?:-([0-9A-F]{4,6}))?$".r
        rfc.substring(start, end).linesIterator.drop(1).flatMap { raw =>
            raw.takeWhile(_ != ';').trim match
                case entry(from, to) =>
                    val a = Integer.parseInt(from, 16)
                    Some((a, if to == null then a else Integer.parseInt(to, 16)))
                case _ => None
        }.toSeq
    end rfcTable

    def merged(ranges: Seq[(Int, Int)]): Seq[(Int, Int)] =
        ranges.sorted.foldLeft(List.empty[(Int, Int)]) {
            case ((a, b) :: rest, (c, d)) if c <= b + 1 => (a, math.max(b, d)) :: rest
            case (acc, r)                               => r :: acc
        }.reverse

    def pairs(flat: Array[Int]): Seq[(Int, Int)] =
        flat.toSeq.grouped(2).map { case Seq(a, b) => (a, b) }.toSeq

end UnicodeTablesTest
