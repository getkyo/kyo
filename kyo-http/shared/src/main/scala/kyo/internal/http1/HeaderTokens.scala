package kyo.internal.http1

import scala.annotation.tailrec

/** Whole-token matching for HTTP field values, shared by the request and response parsers.
  *
  * A field value is a list of DELIMITED tokens (RFC 9110 section 5.6.2), so a comparison that stops after the target's bytes is a PREFIX
  * test rather than an equality test: it matches `chunkedfoo` against `chunked`, and a substring scan additionally matches `no-upgrade`
  * against `upgrade`. Framing and connection decisions are made from these comparisons, and when two HTTP participants frame a message
  * differently the bytes one of them counts as body the other reads as the start of the next request. That disagreement is the
  * request-smuggling primitive, so every comparison here takes a length and compares it.
  *
  * Values arrive with leading whitespace already skipped but not trailing, since the parsers measure a value to the end of its line, so
  * each entry point trims trailing OWS itself. Comparison is case-insensitive per RFC 9110 section 5.6.2.
  */
private[kyo] object HeaderTokens:

    private val Chunked = "chunked"

    /** Whether the field NAME in `src[off, off + len)` is exactly `target`, ignoring case.
      *
      * No OWS trimming: a field name is a token and cannot carry surrounding whitespace (a name with whitespace is rejected outright as a
      * non-token before reaching here), so trimming would accept something the grammar does not. Comparing the length first also keeps this
      * to an int compare for the common miss, which matters because every header walks the whole chain of these.
      */
    def nameEquals(src: Array[Byte], off: Int, len: Int, target: String): Boolean =
        len == target.length && rangeEqualsIgnoreCase(src, off, target)

    /** Whether the comma-separated list in `src[off, off + len)` contains `target` as a whole element.
      *
      * The list test, for fields defined as a list (`Connection` being the case that matters here). It matches an element exactly, so
      * `no-upgrade` does not contain the element `upgrade` while `keep-alive, Upgrade` does.
      */
    def listContainsToken(src: Array[Byte], off: Int, len: Int, target: String): Boolean =
        val end = off + len
        @tailrec def loop(elemStart: Int): Boolean =
            if elemStart > end then false
            else
                val comma   = indexOfComma(src, elemStart, end)
                val elemEnd = if comma < 0 then end else comma
                if elementEquals(src, elemStart, elemEnd, target) then true
                else if comma < 0 then false
                else loop(comma + 1)
        loop(off)
    end listContainsToken

    /** Whether the value is the single transfer coding `chunked`, carrying no other coding.
      *
      * The REQUEST-side test. RFC 9112 section 6.3 item 6 gives a request whose final coding is not chunked no determinable body length,
      * and chunked is the only coding decoded here, so a list naming anything else is refused rather than framed and handed to a handler
      * still encoded.
      */
    def isSoleChunkedCoding(src: Array[Byte], off: Int, len: Int): Boolean =
        // RFC 9110 section 5.6.1 permits empty list elements, so "chunked," and ", chunked" name exactly one coding and must be accepted.
        // What must not be accepted is a second NON-empty coding, which is what makes the length undeterminable.
        val end = off + len
        @tailrec def loop(elemStart: Int, sawChunked: Boolean): Boolean =
            if elemStart > end then sawChunked
            else
                val comma   = indexOfComma(src, elemStart, end)
                val elemEnd = if comma < 0 then end else comma
                val empty   = isBlank(src, elemStart, elemEnd)
                val chunked = !empty && elementEquals(src, elemStart, elemEnd, Chunked)
                if !empty && !chunked then false
                else if comma < 0 then sawChunked || chunked
                else loop(comma + 1, sawChunked || chunked)
        loop(off, false)
    end isSoleChunkedCoding

    /** Whether `src[from, until)` is empty or entirely OWS. */
    @tailrec private def isBlank(src: Array[Byte], from: Int, until: Int): Boolean =
        if from >= until then true
        else if isOws(src(from)) then isBlank(src, from + 1, until)
        else false

    /** Whether the value's final comma-separated transfer coding is `chunked`.
      *
      * The RESPONSE-side test. RFC 9112 section 6.1 requires chunked to be the final coding of any message that uses it, so the last list
      * element decides chunk framing. `gzip, chunked` answers true; `chunked, gzip` and `chunkedfoo` answer false. Unlike the request side
      * this only frames and never rejects, because a response carrying no chunked coding and no Content-Length is read until close, which
      * RFC 9112 section 6.3 item 8 makes a legal response length.
      */
    def finalCodingIsChunked(src: Array[Byte], off: Int, len: Int): Boolean =
        val end = off + len
        @tailrec def lastComma(i: Int, found: Int): Int =
            if i >= end then found
            else lastComma(i + 1, if src(i) == ','.toByte then i else found)
        val start = lastComma(off, -1) match
            case -1 => off
            case c  => c + 1
        elementEquals(src, start, end, Chunked)
    end finalCodingIsChunked

    /** Index of the first comma in `src[from, until)`, or -1. */
    @tailrec private def indexOfComma(src: Array[Byte], from: Int, until: Int): Int =
        if from >= until then -1
        else if src(from) == ','.toByte then from
        else indexOfComma(src, from + 1, until)

    /** Case-insensitive compare of `src[from, until)` against `target` with OWS trimmed from both ends.
      *
      * The length is compared for equality, which is what makes this a whole-element test rather than a prefix test.
      */
    private def elementEquals(src: Array[Byte], from: Int, until: Int, target: String): Boolean =
        @tailrec def skipLeading(i: Int): Int =
            if i < until && isOws(src(i)) then skipLeading(i + 1) else i
        @tailrec def skipTrailing(i: Int): Int =
            if i > from && isOws(src(i - 1)) then skipTrailing(i - 1) else i
        val start = skipLeading(from)
        val end   = skipTrailing(until)
        if end - start != target.length then false
        else
            @tailrec def loop(i: Int): Boolean =
                if i >= target.length then true
                else
                    val a = (src(start + i) & 0xff).toChar.toLower
                    if a != target.charAt(i).toLower then false
                    else loop(i + 1)
            loop(0)
        end if
    end elementEquals

    /** Case-insensitive compare of `target.length` bytes at `src[off]` against `target`, with no length or bounds check of its own. */
    private def rangeEqualsIgnoreCase(src: Array[Byte], off: Int, target: String): Boolean =
        @tailrec def loop(i: Int): Boolean =
            if i >= target.length then true
            else
                val a = (src(off + i) & 0xff).toChar.toLower
                if a != target.charAt(i).toLower then false
                else loop(i + 1)
        loop(0)
    end rangeEqualsIgnoreCase

    /** RFC 9110 section 5.6.3: OWS = *( SP / HTAB ). */
    private def isOws(b: Byte): Boolean = b == ' '.toByte || b == '\t'.toByte

end HeaderTokens
