package kyo.mime

import kyo.*

/** The boundary rules of a multipart body (RFC 2046 section 5.1.1), as functions over bytes for a parser or a writer to apply.
  *
  * A delimiter is `--` and the boundary at the start of a line: at offset 0 or right after a line end, which is LF with or without a CR
  * before it. The same bytes anywhere else in a line are data, which is what keeps a part holding `--boundary` mid-line whole. What
  * follows the boundary on the delimiter line is transport padding, or `--` for the close delimiter, and is ignored; the line end
  * before a delimiter belongs to the delimiter, not to the part before it.
  *
  * A boundary is 1 to 70 characters of `bchars` and does not end in a space. [[Multipart.boundary]] picks one that no line of the enclosed
  * parts starts with, deterministically, so a writer needs no randomness and a written body always reads back.
  */
object Multipart:

    /** The longest boundary RFC 2046 section 5.1.1 allows. */
    inline val MaxBoundaryLength = 70

    /** True when `boundary` is 1 to 70 `bchars` (RFC 2046 section 5.1.1: digits, letters, and `'()+_,-./:=?` and space) not ending in a
      * space.
      */
    def isValidBoundary(boundary: String): Boolean =
        boundary.nonEmpty && boundary.length <= MaxBoundaryLength && boundary.forall(isBoundaryChar) && boundary.last != ' '

    private def isBoundaryChar(c: Char): Boolean =
        (c >= '0' && c <= '9') ||
            (c >= 'a' && c <= 'z') ||
            (c >= 'A' && c <= 'Z') || "'()+_,-./:=? ".indexOf(c.toInt) >= 0

    /** True when a delimiter for `boundary` starts at `at`: `--`, the boundary, and `at` at the start of a line. */
    def isDelimiterAt(body: Span[Byte], boundary: Span[Byte], at: Int): Boolean =
        at >= 0 && at + 2 + boundary.size <= body.size && isLineStart(body, at) &&
            body(at) == '-' && body(at + 1) == '-' && matchesAt(body, at + 2, boundary)

    /** The offset of the next delimiter for `boundary` at or after `from`, or -1. */
    def findDelimiter(body: Span[Byte], boundary: Span[Byte], from: Int): Int =
        @scala.annotation.tailrec
        def loop(at: Int): Int =
            if at + 2 + boundary.size > body.size then -1
            else if isDelimiterAt(body, boundary, at) then at
            else loop(at + 1)
        loop(math.max(0, from))
    end findDelimiter

    /** True when the delimiter at `at` is the close delimiter: the boundary is followed by `--`. */
    def isCloseDelimiter(body: Span[Byte], boundary: Span[Byte], at: Int): Boolean =
        val after = at + 2 + boundary.size
        after + 1 < body.size && body(after) == '-' && body(after + 1) == '-'

    /** The offset after the delimiter line starting at `at`: past the boundary, the transport padding and the line end, or the body's
      * size when the line has no end.
      */
    def delimiterLineEnd(body: Span[Byte], boundary: Span[Byte], at: Int): Int =
        val lf = indexOfLf(body, at + 2 + boundary.size)
        if lf < 0 then body.size else lf + 1

    /** Where the part before the delimiter at `at` ends: the delimiter's line end belongs to the delimiter, so this is `at` minus the
      * CRLF or LF before it, or `at` itself at offset 0.
      */
    def partEndBefore(body: Span[Byte], at: Int): Int =
        if at > 0 && body(at - 1) == '\n' then
            if at > 1 && body(at - 2) == '\r' then at - 2 else at - 1
        else at

    /** A boundary of the form `<prefix><k>`, with `k` the least number such that no line of any enclosed part starts with
      * `--<prefix><k>` and no declared boundary of an enclosed part gives the delimiter `--<prefix><k>`. Deterministic, so the same parts
      * get the same boundary; the scan is one pass over each part.
      *
      * A part is its octets as they will be written, in pieces: the pieces are one stream, and every part starts at the start of a line,
      * since a writer puts a delimiter line before it. A delimiter is matched by prefix, so a line starting `--<prefix>10` takes 1 as well
      * as 10.
      */
    def boundary(prefix: String, parts: Chunk[Chunk[Span[Byte]]], declared: Chunk[String] = Chunk.empty): String =
        val delimiterPrefix = Parameters.utf8Octets("--" + prefix)
        val taken           = Set.newBuilder[Int]
        parts.foreach(pieces => numbers(delimiterPrefix, pieces, taken))
        declared.foreach(b => numbers(delimiterPrefix, Chunk(Parameters.utf8Octets("--" + b)), taken))
        val used = taken.result()
        @scala.annotation.tailrec
        def least(k: Int): Int = if used.contains(k) then least(k + 1) else k
        prefix + least(0)
    end boundary

    // Each number the digit run after `prefix` at the start of a line starts with, the lines running across the pieces.
    private def numbers(prefix: Span[Byte], pieces: Chunk[Span[Byte]], found: scala.collection.mutable.Builder[Int, Set[Int]]): Unit =
        var column   = 0
        var matching = true
        var number   = 0
        pieces.foreach { piece =>
            var i = 0
            while i < piece.size do
                val b = piece(i)
                if b == '\n' then
                    column = 0
                    matching = true
                    number = 0
                else
                    if matching then
                        if column < prefix.size then matching = b == prefix(column)
                        else if b >= '0' && b <= '9' && column - prefix.size < 9 then
                            number = number * 10 + (b - '0')
                            discard(found += number)
                        else matching = false
                    end if
                    column += 1
                end if
                i += 1
            end while
        }
    end numbers

    private def isLineStart(body: Span[Byte], at: Int): Boolean = at == 0 || body(at - 1) == '\n'

    private def matchesAt(body: Span[Byte], at: Int, boundary: Span[Byte]): Boolean =
        var i = 0
        while i < boundary.size && body(at + i) == boundary(i) do i += 1
        i == boundary.size
    end matchesAt

    private def indexOfLf(body: Span[Byte], from: Int): Int =
        var i = from
        while i < body.size && body(i) != '\n' do i += 1
        if i < body.size then i else -1
    end indexOfLf

end Multipart
