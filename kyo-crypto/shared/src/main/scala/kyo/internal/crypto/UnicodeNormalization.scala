package kyo.internal.crypto

import java.util.Arrays

/** Unicode Normalization Form KC (UAX #15) over code points, from the tables of [[UnicodeTables]] (Unicode 15.1.0), written once so
  * the four platforms produce the same code points: neither Scala.js nor Scala Native ships `java.text.Normalizer`.
  *
  * The three steps are UAX #15's, in PostgreSQL's `unicode_norm.c` order: full decomposition (the expanded table entries, Hangul
  * syllables arithmetically), canonical ordering of every run of non-starters by combining class, then composition by a starter walk
  * where a non-starter blocked by a later one of equal or higher class does not compose, and Hangul L+V and LV+T compose arithmetically.
  */
private[kyo] object UnicodeNormalization:

    private val HangulBase   = 0xac00
    private val HangulLBase  = 0x1100
    private val HangulVBase  = 0x1161
    private val HangulTBase  = 0x11a7
    private val HangulLCount = 19
    private val HangulVCount = 21
    private val HangulTCount = 28
    private val HangulNCount = HangulVCount * HangulTCount
    private val HangulSCount = HangulLCount * HangulNCount

    /** The NFKC form of `input`, a fresh array; `input` is not modified. */
    def nfkc(input: Array[Int]): Array[Int] =
        val decomposed = decompose(input)
        order(decomposed)
        compose(decomposed)
    end nfkc

    def combiningClass(code: Int): Int =
        val i = Arrays.binarySearch(UnicodeTables.combiningClassCodePoints, code)
        if i < 0 then 0 else UnicodeTables.combiningClassValues(i)

    private def decompose(input: Array[Int]): Array[Int] =
        var length = 0
        var i      = 0
        while i < input.length do
            length += decompositionLength(input(i))
            i += 1
        val out = new Array[Int](length)
        var o   = 0
        i = 0
        while i < input.length do
            val code = input(i)
            if code >= HangulBase && code < HangulBase + HangulSCount then
                val index = code - HangulBase
                out(o) = HangulLBase + index / HangulNCount
                out(o + 1) = HangulVBase + (index % HangulNCount) / HangulTCount
                o += 2
                val t = index % HangulTCount
                if t != 0 then
                    out(o) = HangulTBase + t
                    o += 1
            else
                val entry = Arrays.binarySearch(UnicodeTables.decompositionCodePoints, code)
                if entry < 0 then
                    out(o) = code
                    o += 1
                else
                    var p = UnicodeTables.decompositionStarts(entry)
                    val e = UnicodeTables.decompositionStarts(entry + 1)
                    while p < e do
                        out(o) = UnicodeTables.decompositionData(p)
                        p += 1
                        o += 1
                    end while
                end if
            end if
            i += 1
        end while
        out
    end decompose

    private def decompositionLength(code: Int): Int =
        if code >= HangulBase && code < HangulBase + HangulSCount then
            if (code - HangulBase) % HangulTCount == 0 then 2 else 3
        else
            val entry = Arrays.binarySearch(UnicodeTables.decompositionCodePoints, code)
            if entry < 0 then 1
            else UnicodeTables.decompositionStarts(entry + 1) - UnicodeTables.decompositionStarts(entry)

    /** Canonical ordering in place: within each run of non-starters, a stable sort by combining class. */
    private def order(codes: Array[Int]): Unit =
        var i = 1
        while i < codes.length do
            val klass = combiningClass(codes(i))
            if klass != 0 then
                var j = i
                while j > 0 && combiningClass(codes(j - 1)) > klass do
                    val swap = codes(j - 1)
                    codes(j - 1) = codes(j)
                    codes(j) = swap
                    j -= 1
                end while
            end if
            i += 1
        end while
    end order

    private def compose(codes: Array[Int]): Array[Int] =
        if codes.length == 0 then codes
        else
            val out       = new Array[Int](codes.length)
            var o         = 0
            var starterAt = -1
            var lastClass = -1
            var i         = 0
            while i < codes.length do
                val code     = codes(i)
                val klass    = combiningClass(code)
                val composed =
                    if starterAt < 0 then -1
                    else if lastClass != 0 && lastClass >= klass then -1
                    else if lastClass == 0 && klass == 0 && o - 1 != starterAt then -1
                    else primaryComposite(out(starterAt), code)
                if composed >= 0 then
                    out(starterAt) = composed
                else
                    out(o) = code
                    if klass == 0 then
                        starterAt = o
                        lastClass = 0
                    else
                        lastClass = klass
                    end if
                    o += 1
                end if
                i += 1
            end while
            if o == out.length then out else Arrays.copyOf(out, o)
    end compose

    private def primaryComposite(first: Int, second: Int): Int =
        if first >= HangulLBase && first < HangulLBase + HangulLCount && second >= HangulVBase && second < HangulVBase + HangulVCount then
            HangulBase + ((first - HangulLBase) * HangulVCount + (second - HangulVBase)) * HangulTCount
        else if first >= HangulBase && first < HangulBase + HangulSCount &&
            (first - HangulBase) % HangulTCount == 0 &&
            second > HangulTBase && second < HangulTBase + HangulTCount
        then first + (second - HangulTBase)
        else
            val firsts = UnicodeTables.compositionFirst
            var lo     = 0
            var hi     = firsts.length - 1
            var found  = -1
            while lo <= hi && found < 0 do
                val mid = (lo + hi) >>> 1
                val f   = firsts(mid)
                val s   = UnicodeTables.compositionSecond(mid)
                if f < first || (f == first && s < second) then lo = mid + 1
                else if f > first || s > second then hi = mid - 1
                else found = UnicodeTables.compositionResult(mid)
            end while
            found

end UnicodeNormalization
