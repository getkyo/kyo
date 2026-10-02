package kyo.crypto

import kyo.*
import kyo.internal.crypto.UnicodeNormalization
import kyo.internal.crypto.UnicodeTables

/** SASLprep (RFC 4013), the stringprep profile SCRAM applies to a password before salting it (RFC 5802 section 5.1), as PostgreSQL's
  * `pg_saslprep` applies it: the server prepares a password when it stores a SCRAM secret and libpq prepares it when it authenticates,
  * so a client that salts the raw password cannot log in with any password the profile changes, such as one holding a no-break space, a
  * soft hyphen, a fullwidth digit or a decomposed accent.
  *
  * The steps, in `src/common/saslprep.c`'s order: each non-ASCII space (RFC 3454 C.1.2) becomes U+0020 and each code point mapped to
  * nothing (B.1) is dropped; an empty result is [[Saslprep.Failure.Empty]]; the mapped code points are normalized to NFKC (Unicode
  * 15.1.0, the version PostgreSQL 17 normalizes with; every code point assigned later is unassigned in Unicode 3.2 and refused, and the
  * decompositions of assigned code points are frozen, so any version from 4.1 on gives a string that passes the same output, the
  * corrigenda between 3.2 and 4.1 being the only exceptions); a mapped code point in the prohibited output (C.1.2 to C.9) or unassigned in
  * Unicode 3.2 (A.1) is [[Saslprep.Failure.Prohibited]]; a string with a right-to-left code point (D.1) that also holds a left-to-right
  * one (D.2), or that does not both start and end right-to-left, is [[Saslprep.Failure.Bidirectional]]. The prohibition and the
  * bidirectional
  * checks read the mapped code points before normalization, as `pg_saslprep` does, where RFC 3454 section 2 reads the output; the two
  * differ on a code point whose compatibility decomposition changes the first or last direction, and the server's stored secrets follow
  * `pg_saslprep`.
  *
  * A failure carries an index into `text`, never its characters, because the text is a password. An unpaired surrogate is a code point
  * of C.5 and is [[Saslprep.Failure.Prohibited]] at its index. Nothing here is constant time: the text is the caller's own password,
  * prepared on the caller's side.
  *
  * @see
  *   [[Saslprep.Failure]], the three refusals
  * @see
  *   [[Pbkdf2.hmacSha256]], which SCRAM salts the prepared text with
  * @see
  *   [[Hmac.sha256]], the next step of the SCRAM proof
  */
object Saslprep:

    /** Why a text has no SASLprep form: one case per check `pg_saslprep` fails, carrying an index into the text where the check names
      * a character and nothing else, because the text is a password. A caller that logs in with the password does not surface these:
      * libpq and the PostgreSQL server both fall back to the raw password when preparation fails, and a client that follows them does
      * the same.
      *
      * @see
      *   [[prepare]], which produces it
      * @see
      *   [[kyo.Base64.Failure]] and [[kyo.Hex.Failure]], the other kyo failures that carry an index and never the text
      */
    enum Failure derives CanEqual:

        /** Nothing is left once the code points mapped to nothing are dropped: the empty text, or one of soft hyphens and joiners only. */
        case Empty

        /** The character at `offset` in the text, counting UTF-16 units from 0, is prohibited output (RFC 3454 C.1.2 to C.9, an unpaired
          * surrogate among them) or unassigned in Unicode 3.2 (A.1), which every emoji and every code point assigned since 2002 is.
          */
        case Prohibited(offset: Int)

        /** The text holds a right-to-left character and also a left-to-right one, or does not both start and end with a right-to-left
          * one.
          */
        case Bidirectional

    end Failure

    /** The SASLprep form of `text`, or why it has none. */
    def prepare(text: String): Result[Failure, String] =
        val mapped  = new Array[Int](text.length)
        val offsets = new Array[Int](text.length)
        var count   = 0
        var i       = 0
        while i < text.length do
            val code = text.codePointAt(i)
            if inTable(UnicodeTables.nonAsciiSpace, code) then
                mapped(count) = 0x20
                offsets(count) = i
                count += 1
            else if !inTable(UnicodeTables.mappedToNothing, code) then
                mapped(count) = code
                offsets(count) = i
                count += 1
            end if
            i += Character.charCount(code)
        end while
        if count == 0 then Result.fail(Failure.Empty)
        else
            var prohibitedAt = -1
            var j            = 0
            while j < count && prohibitedAt < 0 do
                val code = mapped(j)
                if inTable(UnicodeTables.prohibitedOutput, code) || inTable(UnicodeTables.unassigned, code) then prohibitedAt = offsets(j)
                j += 1
            end while
            if prohibitedAt >= 0 then Result.fail(Failure.Prohibited(prohibitedAt))
            else if !bidirectional(mapped, count) then Result.fail(Failure.Bidirectional)
            else
                val normalized =
                    UnicodeNormalization.nfkc(if count == mapped.length then mapped else java.util.Arrays.copyOf(mapped, count))
                val out = new java.lang.StringBuilder(normalized.length)
                var k   = 0
                while k < normalized.length do
                    out.appendCodePoint(normalized(k))
                    k += 1
                Result.succeed(out.toString)
            end if
        end if
    end prepare

    /** RFC 3454 section 6 over the mapped code points: a string holding a right-to-left code point holds no left-to-right one and
      * starts and ends right-to-left.
      */
    private def bidirectional(codes: Array[Int], count: Int): Boolean =
        var hasRightToLeft = false
        var i              = 0
        while i < count && !hasRightToLeft do
            hasRightToLeft = inTable(UnicodeTables.randALCat, codes(i))
            i += 1
        if !hasRightToLeft then true
        else
            var hasLeftToRight = false
            i = 0
            while i < count && !hasLeftToRight do
                hasLeftToRight = inTable(UnicodeTables.lCat, codes(i))
                i += 1
            !hasLeftToRight && inTable(UnicodeTables.randALCat, codes(0)) && inTable(UnicodeTables.randALCat, codes(count - 1))
        end if
    end bidirectional

    /** Whether `code` lies in one of `table`'s sorted inclusive ranges, `(from, to)` pairs flattened. */
    private def inTable(table: Array[Int], code: Int): Boolean =
        var lo = 0
        var hi = table.length / 2 - 1
        var in = false
        while lo <= hi && !in do
            val mid = (lo + hi) >>> 1
            if code < table(mid * 2) then hi = mid - 1
            else if code > table(mid * 2 + 1) then lo = mid + 1
            else in = true
        end while
        in
    end inTable

end Saslprep
