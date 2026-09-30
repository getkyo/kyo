package kyo.internal.charset

import kyo.*
import kyo.charset.Charset

class IndexTableTest extends kyo.test.Test[Any]:

    "generated tables equal their index files, entry for entry" - {
        IndexTableFixtures.singleByte.filterNot(_.charset == Charset.Iso8859_8I).foreach { fixture =>
            fixture.file in {
                val expected = IndexTableFixtures.entries(fixture.raw)
                assert(expected.nonEmpty)
                assert(fixture.table.size == expected.keys.max + 1)
                val mismatches = (0 until fixture.table.size).filter(p => fixture.table(p) != expected.getOrElse(p, -1))
                assert(mismatches.isEmpty)
            }
        }
    }

    "a single-byte table ends at its index's highest pointer, which is below 128, and reads unmapped from there to 127" - {
        IndexTableFixtures.singleByte.filterNot(_.charset == Charset.Iso8859_8I).foreach { fixture =>
            fixture.file in {
                val pointers = IndexTableFixtures.entries(fixture.raw).keySet
                assert(pointers.forall(p => p >= 0 && p < 128))
                assert(fixture.table.size == pointers.max + 1)
                assert((fixture.table.size until 128).forall(p => fixture.table(p) == -1))
            }
        }
    }

    "dense format" - {
        "reads four-digit code points, supplementary code points and unmapped pointers" in {
            val table = IndexTable.dense(4, "0041~+01f600fb02")
            assert(table.size == 4)
            assert(table(0) == 0x41)
            assert(table(1) == -1)
            assert(table(2) == 0x1f600)
            assert(table(3) == 0xfb02)
        }
        "joins literal parts without a separator" in {
            val table = IndexTable.dense(3, "0041", "00", "42~")
            assert(table(0) == 0x41 && table(1) == 0x42 && table(2) == -1)
        }
        "answers -1 outside the table" in {
            val table = IndexTable.dense(1, "0041")
            assert(table(-1) == -1)
            assert(table(1) == -1)
        }
        "fails when the literals hold a different number of entries" in {
            val ex = intercept[IllegalStateException](IndexTable.dense(3, "00410042"))
            assert(ex.getMessage.contains("has 2 entries, expected 3"))
        }
    }

    "ranges format reads pointer and code point pairs" in {
        val ranges = IndexTable.ranges("000000000080", "02e24800ffff")
        assert(ranges.size == 2)
        assert(ranges.pointer(0) == 0 && ranges.codePoint(0) == 0x80)
        assert(ranges.pointer(1) == 189000 && ranges.codePoint(1) == 0xffff)
    }

    "label pairs read tab-separated lines" in {
        assert(IndexTable.labelPairs("utf8\tUTF-8\n", "latin1\twindows-1252\n") == Map("utf8" -> "UTF-8", "latin1" -> "windows-1252"))
    }

end IndexTableTest
