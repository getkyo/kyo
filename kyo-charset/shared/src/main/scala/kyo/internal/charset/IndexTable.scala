package kyo.internal.charset

/** A WHATWG index decoded from the literals `project/CharsetTablesGen.scala` generates: the code point for each pointer, or `-1` where the
  * index leaves the pointer unmapped.
  *
  * The literal format is the generator's: a dense table lists every pointer from 0 in order, each as four lowercase hex digits for a code
  * point up to U+FFFF, `+` and six hex digits above it, or `~` for an unmapped pointer. A ranges table lists pointer and code point pairs,
  * six hex digits each.
  */
final private[kyo] class IndexTable private (codePoints: Array[Int]):

    /** One more than the highest pointer the index maps. */
    def size: Int = codePoints.length

    /** The code point for `pointer`, or `-1` when the index does not map it. */
    def apply(pointer: Int): Int =
        if pointer < 0 || pointer >= codePoints.length then -1 else codePoints(pointer)

end IndexTable

private[kyo] object IndexTable:

    /** The pointer and code point pairs of a ranges index (gb18030-ranges), in pointer order. */
    final class Ranges private[IndexTable] (pointers: Array[Int], codePoints: Array[Int]):
        def size: Int              = pointers.length
        def pointer(i: Int): Int   = pointers(i)
        def codePoint(i: Int): Int = codePoints(i)

        /** The position of the last pair whose pointer is at most `pointer`, or `-1` when every pair's pointer is above it. */
        def lastAtOrBelow(pointer: Int): Int =
            @scala.annotation.tailrec
            def loop(low: Int, high: Int): Int =
                if low > high then high
                else
                    val middle = (low + high) >>> 1
                    if pointers(middle) <= pointer then loop(middle + 1, high) else loop(low, middle - 1)
            loop(0, pointers.length - 1)
        end lastAtOrBelow
    end Ranges

    def dense(size: Int, parts: String*): IndexTable =
        // Filled in place while parsing and never exposed, so the table stays immutable to its readers.
        val codePoints = new Array[Int](size)
        val text       = parts.mkString
        @scala.annotation.tailrec
        def loop(at: Int, pointer: Int): Int =
            if at >= text.length then pointer
            else
                text.charAt(at) match
                    case '~' =>
                        codePoints(pointer) = -1
                        loop(at + 1, pointer + 1)
                    case '+' =>
                        codePoints(pointer) = hex(text, at + 1, 6)
                        loop(at + 7, pointer + 1)
                    case _ =>
                        codePoints(pointer) = hex(text, at, 4)
                        loop(at + 4, pointer + 1)
        val filled = loop(0, 0)
        // The generator writes `size` as the number of entries in the literals, so a shortfall means the generator's literal format and
        // this reader disagree (an excess fails at the array bound first). The panic fires when the table's object first loads, so every
        // decoder test over that table fails.
        if filled != size then throw new IllegalStateException(s"generated index has $filled entries, expected $size")
        new IndexTable(codePoints)
    end dense

    def ranges(parts: String*): Ranges =
        val text  = parts.mkString
        val count = text.length / 12
        new Ranges(Array.tabulate(count)(i => hex(text, i * 12, 6)), Array.tabulate(count)(i => hex(text, i * 12 + 6, 6)))
    end ranges

    /** `label<TAB>name<LF>` lines to a map from label to WHATWG encoding name. */
    def labelPairs(parts: String*): Map[String, String] =
        parts.mkString.split("\n").iterator.filter(_.nonEmpty).map { line =>
            val tab = line.indexOf('\t')
            line.substring(0, tab) -> line.substring(tab + 1)
        }.toMap

    private def hex(text: String, from: Int, digits: Int): Int =
        @scala.annotation.tailrec
        def loop(i: Int, acc: Int): Int =
            if i == from + digits then acc
            else
                val c     = text.charAt(i)
                val digit = if c >= '0' && c <= '9' then c - '0' else c - 'a' + 10
                loop(i + 1, acc * 16 + digit)
        loop(from, 0)
    end hex

end IndexTable
