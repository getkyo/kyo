package kyo.stats.machine

import kyo.*

/** In-place ASCII scanning over the sampler's retained byte buffer.
  *
  * The multi-field proc files (`/proc/stat`, `/proc/meminfo`, `/proc/loadavg`, the PSI files, the cgroup
  * `cpu.stat` and `cpu.max`) are read field by field straight out of the borrowed span: no intermediate
  * `String`, no `split`, no collection, and no varargs array. Every result is a primitive, and absence is
  * the primitive sentinel the cells skip: `Path.ReadHandle.AbsentLong` for an integer and `Double.NaN` for
  * the two fixed-point families (a load average and a PSI percentage are never NaN, so the sentinel is
  * collision-free). Every scan is TOTAL: a malformed, truncated, missing or non-numeric field yields the
  * sentinel, never a throw and never a partial value.
  */
private[machine] object LinuxScan:

    /** The ASCII bytes of a literal key. Called once per key at object init, never on a tick. */
    def ascii(s: String): Array[Byte] =
        s.getBytes(java.nio.charset.StandardCharsets.US_ASCII)

    /** The offset just past the first line that starts with `prefix`, or -1 when no line does. */
    def lineFields(bytes: Span[Byte], len: Int, prefix: Array[Byte]): Int =
        @scala.annotation.tailrec
        def loop(start: Int): Int =
            if start >= len then -1
            else if startsWith(bytes, len, start, prefix) then start + prefix.length
            else
                val nl = lineEnd(bytes, len, start)
                if nl >= len then -1 else loop(nl + 1)
        loop(0)
    end lineFields

    /** The `index`-th whitespace-delimited ASCII-decimal token at or after `from`, bounded to that line,
      * multiplied by `scale`. `AbsentLong` when the token is missing, non-numeric or overflowing.
      */
    def longField(bytes: Span[Byte], len: Int, from: Int, index: Int, scale: Long): Long =
        val at = tokenStart(bytes, len, from, index)
        if at < 0 then Path.ReadHandle.AbsentLong
        else scaled(parseLong(bytes, len, at), scale)
    end longField

    /** The `index`-th token at or after `from` as a fixed-point `Double` (`1.23`), bounded to that line.
      * `Double.NaN` when the token is missing or non-numeric.
      */
    def doubleField(bytes: Span[Byte], len: Int, from: Int, index: Int): Double =
        val at = tokenStart(bytes, len, from, index)
        if at < 0 then Double.NaN else parseDouble(bytes, len, at)

    /** The `index`-th token on the first line that starts with `key` (a `MemTotal:` or `nr_periods` style
      * line), multiplied by `scale`. `AbsentLong` when the line or the token is absent or unparseable.
      */
    def keyedLong(bytes: Span[Byte], len: Int, key: Array[Byte], index: Int, scale: Long): Long =
        val from = lineFields(bytes, len, key)
        if from < 0 then Path.ReadHandle.AbsentLong
        else longField(bytes, len, from, index, scale)
    end keyedLong

    /** The value of an `avg10=1.23` style tag on the line starting at `from`, as a `Double`. `Double.NaN`
      * when the tag is absent or its value is unparseable.
      */
    def taggedDouble(bytes: Span[Byte], len: Int, from: Int, tag: Array[Byte]): Double =
        val at = tagValue(bytes, len, from, tag)
        if at < 0 then Double.NaN else parseDouble(bytes, len, at)

    /** The value of a `total=1234` style tag on the line starting at `from`, multiplied by `scale`.
      * `AbsentLong` when the tag is absent or its value is unparseable.
      */
    def taggedLong(bytes: Span[Byte], len: Int, from: Int, tag: Array[Byte], scale: Long): Long =
        val at = tagValue(bytes, len, from, tag)
        if at < 0 then Path.ReadHandle.AbsentLong
        else scaled(parseLong(bytes, len, at), scale)
    end taggedLong

    /** Sentinel-aware addition, fixed arity so no varargs array is allocated on the tick: an absent
      * operand contributes nothing, and a sum of two absent operands is itself absent.
      */
    def plus(a: Long, b: Long): Long =
        if a == Path.ReadHandle.AbsentLong then b
        else if b == Path.ReadHandle.AbsentLong then a
        else a + b

    /** Sentinel-aware scaling: the sentinel is returned unscaled, so a unit conversion can never turn an
      * absent value into a garbage number (multiplying Long.MinValue would overflow).
      */
    def scaled(v: Long, scale: Long): Long =
        if v == Path.ReadHandle.AbsentLong then v else v * scale

    private def isDigit(b: Byte): Boolean = b >= '0' && b <= '9'

    private def isSpace(b: Byte): Boolean = b == ' ' || b == '\t'

    private def lineEnd(bytes: Span[Byte], len: Int, from: Int): Int =
        @scala.annotation.tailrec
        def loop(i: Int): Int = if i >= len || bytes(i) == '\n' then i else loop(i + 1)
        loop(from)
    end lineEnd

    private def startsWith(bytes: Span[Byte], len: Int, at: Int, prefix: Array[Byte]): Boolean =
        @scala.annotation.tailrec
        def loop(i: Int): Boolean =
            if i >= prefix.length then true
            else if at + i >= len || bytes(at + i) != prefix(i) then false
            else loop(i + 1)
        loop(0)
    end startsWith

    /** The offset of the `index`-th whitespace-delimited token at or after `from`, or -1 when the line
      * ends first. Index 0 is the first token at or after `from`.
      */
    private def tokenStart(bytes: Span[Byte], len: Int, from: Int, index: Int): Int =
        val end = lineEnd(bytes, len, from)
        @scala.annotation.tailrec
        def skipSpace(i: Int): Int = if i < end && isSpace(bytes(i)) then skipSpace(i + 1) else i
        @scala.annotation.tailrec
        def skipToken(i: Int): Int = if i < end && !isSpace(bytes(i)) then skipToken(i + 1) else i
        @scala.annotation.tailrec
        def loop(i: Int, remaining: Int): Int =
            val start = skipSpace(i)
            if start >= end then -1
            else if remaining == 0 then start
            else loop(skipToken(start), remaining - 1)
        end loop
        loop(from, index)
    end tokenStart

    /** The offset just past `tag` on the line starting at `from`, or -1 when the tag is not on that line. */
    private def tagValue(bytes: Span[Byte], len: Int, from: Int, tag: Array[Byte]): Int =
        val end = lineEnd(bytes, len, from)
        @scala.annotation.tailrec
        def loop(i: Int): Int =
            if i >= end then -1
            else if startsWith(bytes, len, i, tag) then i + tag.length
            else loop(i + 1)
        loop(from)
    end tagValue

    /** Parses the maximal ASCII-decimal run at `at`. `AbsentLong` on no leading digit or overflow; the
      * parser accepts no sign, so it can never itself produce a negative value.
      */
    private def parseLong(bytes: Span[Byte], len: Int, at: Int): Long =
        @scala.annotation.tailrec
        def loop(i: Int, acc: Long, any: Boolean): Long =
            if i >= len || !isDigit(bytes(i)) then (if any then acc else Path.ReadHandle.AbsentLong)
            else
                val d = (bytes(i) - '0').toLong
                if acc > (Long.MaxValue - d) / 10L then Path.ReadHandle.AbsentLong
                else loop(i + 1, acc * 10L + d, true)
        loop(at, 0L, false)
    end parseLong

    /** Parses a fixed-point decimal (`1.23`) at `at` into a `Double` with no intermediate `String`.
      * `Double.NaN` on no leading digit.
      */
    private def parseDouble(bytes: Span[Byte], len: Int, at: Int): Double =
        val whole = parseLong(bytes, len, at)
        if whole == Path.ReadHandle.AbsentLong then Double.NaN
        else
            @scala.annotation.tailrec
            def endOfDigits(i: Int): Int = if i < len && isDigit(bytes(i)) then endOfDigits(i + 1) else i
            val dot                      = endOfDigits(at)
            if dot >= len || bytes(dot) != '.' then whole.toDouble
            else
                @scala.annotation.tailrec
                def frac(i: Int, acc: Long, scale: Long): Double =
                    if i >= len || !isDigit(bytes(i)) then (whole * scale + acc).toDouble / scale.toDouble
                    else frac(i + 1, acc * 10L + (bytes(i) - '0').toLong, scale * 10L)
                frac(dot + 1, 0L, 1L)
            end if
        end if
    end parseDouble

end LinuxScan
