package kyo.ai.tokenizer

import kyo.*
import kyo.ai.Tokenizer
import kyo.ai.Tokenizer.Encoding
import scala.annotation.tailrec

/** The offline byte-level BPE tokenizer for one encoding. Pure: `count` lifts its local counts
  * and never uses the `Async`/`Abort` row.
  */
final private[kyo] case class Tiktoken(encoding: Encoding) extends Tokenizer:
    private val ranks: Ranks = Ranks.forEncoding(encoding)

    def count(texts: Chunk[String])(using Frame): Chunk[Int] < Any =
        texts.map(countText)

    /** The token count of one text: sum of the merged-piece token counts over every
      * pre-tokenized piece. Pure and local.
      */
    def countText(text: String): Int =
        preTokenize(text).foldLeft(0)((total, piece) => total + mergePiece(piece))

    /** Byte-level BPE over one pre-token piece: start from its UTF-8 bytes as singleton tokens,
      * then repeatedly merge the adjacent pair with the lowest rank until no adjacent pair has a
      * rank. Returns the resulting token COUNT (not the ids: only occupancy needs the count).
      */
    def mergePiece(piece: String): Int =
        val bytes = utf8Bytes(piece)
        if bytes.length <= 1 then bytes.length
        else
            // parts holds the byte-range boundaries of the current tokens; merging joins two adjacent parts.
            @tailrec def loop(parts: Chunk[(Int, Int)]): Int =
                if parts.length <= 1 then parts.length
                else
                    // find the adjacent pair whose concatenated bytes have the lowest rank.
                    var bestIndex = -1
                    var bestRank  = Int.MaxValue
                    var i         = 0
                    while i < parts.length - 1 do
                        val (start, _)   = parts(i)
                        val (_, endNext) = parts(i + 1)
                        ranks.rankOf(bytes, start, endNext) match
                            case Present(r) if r < bestRank =>
                                bestRank = r; bestIndex = i
                            case _ => ()
                        end match
                        i += 1
                    end while
                    if bestIndex < 0 then parts.length
                    else
                        val (start, _)   = parts(bestIndex)
                        val (_, endNext) = parts(bestIndex + 1)
                        val merged       = parts.take(bestIndex).append((start, endNext)).concat(parts.drop(bestIndex + 2))
                        loop(merged)
                    end if
            loop(Chunk.from((0 until bytes.length).map(i => (i, i + 1))))
        end if
    end mergePiece

    /** Code-point-level pre-tokenizer reproducing the encoding's grammar with NO regex,
      * via java.lang.Character.getType for the Unicode general categories \p{L}
      * (letters), \p{N} (numbers), \p{M} (marks). Splits into pieces the BPE then merges.
      *
      * Both encodings cap a digit run at three code points and share the trailing-whitespace
      * lookahead. They differ on the letter grammar: o200k splits a letter run at a case
      * transition and folds combining marks into the run, while cl100k uses one flat letter run
      * with no case split and no mark folding. A CJK/NFD edge case that cannot reach exact parity
      * with the reference corpus is a best-effort match within a documented bound, never a
      * silently loosened assertion.
      */
    def preTokenize(text: String): Chunk[String] =
        Chunk.from(Tiktoken.splitCodePoints(text, encoding))
    end preTokenize

    /** The UTF-8 bytes of a string, computed without java.nio charset machinery (portable): a
      * manual UTF-8 encoder over the code points so JS/Native/Wasm agree byte-for-byte with JVM.
      */
    def utf8Bytes(s: String): Array[Byte] =
        val out = Array.newBuilder[Byte]
        var i   = 0
        while i < s.length do
            val cp = s.codePointAt(i)
            if cp < 0x80 then out += cp.toByte
            else if cp < 0x800 then
                out += (0xc0 | (cp >> 6)).toByte
                out += (0x80 | (cp & 0x3f)).toByte
            else if cp < 0x10000 then
                out += (0xe0 | (cp >> 12)).toByte
                out += (0x80 | ((cp >> 6) & 0x3f)).toByte
                out += (0x80 | (cp & 0x3f)).toByte
            else
                out += (0xf0 | (cp >> 18)).toByte
                out += (0x80 | ((cp >> 12) & 0x3f)).toByte
                out += (0x80 | ((cp >> 6) & 0x3f)).toByte
                out += (0x80 | (cp & 0x3f)).toByte
            end if
            i += Character.charCount(cp)
        end while
        out.result()
    end utf8Bytes
end Tiktoken

private[kyo] object Tiktoken:
    /** Splits `text` into pre-token pieces for `encoding`, code-point-level, no regex.
      * Kept as one internal def so `preTokenize` reads as intent.
      */
    def splitCodePoints(text: String, encoding: Encoding): Seq[String] =
        internalSplit(text, encoding)

    private val UppercaseLetter: Int      = Character.UPPERCASE_LETTER.toInt
    private val LowercaseLetter: Int      = Character.LOWERCASE_LETTER.toInt
    private val TitlecaseLetter: Int      = Character.TITLECASE_LETTER.toInt
    private val ModifierLetter: Int       = Character.MODIFIER_LETTER.toInt
    private val OtherLetter: Int          = Character.OTHER_LETTER.toInt
    private val DecimalDigitNumber: Int   = Character.DECIMAL_DIGIT_NUMBER.toInt
    private val LetterNumber: Int         = Character.LETTER_NUMBER.toInt
    private val OtherNumber: Int          = Character.OTHER_NUMBER.toInt
    private val NonSpacingMark: Int       = Character.NON_SPACING_MARK.toInt
    private val CombiningSpacingMark: Int = Character.COMBINING_SPACING_MARK.toInt
    private val EnclosingMark: Int        = Character.ENCLOSING_MARK.toInt

    private def isLetterCp(cp: Int): Boolean =
        val category = Character.getType(cp)
        category == UppercaseLetter || category == LowercaseLetter ||
        category == TitlecaseLetter || category == ModifierLetter || category == OtherLetter
    end isLetterCp

    private def isDigitCp(cp: Int): Boolean =
        val category = Character.getType(cp)
        category == DecimalDigitNumber || category == LetterNumber || category == OtherNumber

    private def isMarkCp(cp: Int): Boolean =
        val category = Character.getType(cp)
        category == NonSpacingMark || category == CombiningSpacingMark || category == EnclosingMark

    /** The "upper-like" class o200k folds a letter run's leading run against:
      * uppercase/titlecase/caseless letters plus combining marks.
      */
    private def isUpperLikeCp(cp: Int): Boolean =
        val category = Character.getType(cp)
        category == UppercaseLetter || category == TitlecaseLetter ||
        category == ModifierLetter || category == OtherLetter || isMarkCp(cp)
    end isUpperLikeCp

    /** The "lower-like" class o200k folds a letter run's trailing run against: lowercase or
      * caseless letters plus combining marks.
      */
    private def isLowerLikeCp(cp: Int): Boolean =
        val category = Character.getType(cp)
        category == LowercaseLetter || category == ModifierLetter || category == OtherLetter || isMarkCp(cp)

    private def isNewlineCp(cp: Int): Boolean = cp == '\n' || cp == '\r'

    private def isWhitespaceCp(cp: Int): Boolean = Character.isWhitespace(cp)

    /** The leading-char class both letter alternatives share: neither a newline, a letter, nor a
      * digit (a combining mark or a punctuation/symbol char is eligible).
      */
    private def isLeadEligible(cp: Int): Boolean =
        !isNewlineCp(cp) && !isLetterCp(cp) && !isDigitCp(cp)

    private def isEitherCase(cp: Int, lower: Char, upper: Char): Boolean =
        cp == lower.toInt || cp == upper.toInt

    /** The length (apostrophe inclusive) of a case-insensitive contraction suffix
      * (`'s 't 're 've 'm 'll 'd`) starting at `cps(apostropheIndex)`, or 0 when none matches.
      */
    private def suffixLength(cps: Array[Int], apostropheIndex: Int): Int =
        val n                    = cps.length
        def at(offset: Int): Int = if apostropheIndex + offset < n then cps(apostropheIndex + offset) else -1
        if isEitherCase(at(1), 'l', 'L') && isEitherCase(at(2), 'l', 'L') then 3
        else if isEitherCase(at(1), 'v', 'V') && isEitherCase(at(2), 'e', 'E') then 3
        else if isEitherCase(at(1), 'r', 'R') && isEitherCase(at(2), 'e', 'E') then 3
        else if isEitherCase(at(1), 's', 'S') then 2
        else if isEitherCase(at(1), 't', 'T') then 2
        else if isEitherCase(at(1), 'm', 'M') then 2
        else if isEitherCase(at(1), 'd', 'D') then 2
        else 0
        end if
    end suffixLength

    /** cl100k's standalone leading contraction alternative: an apostrophe followed directly by
      * one of the case-insensitive suffixes, independent of any preceding letters.
      */
    private def contractionEnd(cps: Array[Int], index: Int, encoding: Encoding): Maybe[Int] =
        encoding match
            case Encoding.Cl100kBase if cps(index) == '\'' =>
                val length = suffixLength(cps, index)
                if length > 0 then Present(index + length) else Absent
            case _ => Absent

    /** cl100k's flat letter run: an optional leading non-letter/non-digit/non-newline char, then
      * one or more `\p{L}` code points (no case split, no mark folding).
      */
    private def cl100kLetterEnd(cps: Array[Int], index: Int): Maybe[Int] =
        val n = cps.length
        val start =
            if isLetterCp(cps(index)) then index
            else if isLeadEligible(cps(index)) && index + 1 < n && isLetterCp(cps(index + 1)) then index + 1
            else -1
        if start < 0 then Absent
        else
            @tailrec def scan(j: Int): Int = if j < n && isLetterCp(cps(j)) then scan(j + 1) else j
            Present(scan(start))
        end if
    end cl100kLetterEnd

    private def upperLikeRunEnd(cps: Array[Int], start: Int): Int =
        val n                          = cps.length
        @tailrec def scan(j: Int): Int = if j < n && isUpperLikeCp(cps(j)) then scan(j + 1) else j
        scan(start)
    end upperLikeRunEnd

    private def lowerLikeRunEnd(cps: Array[Int], start: Int): Int =
        val n                          = cps.length
        @tailrec def scan(j: Int): Int = if j < n && isLowerLikeCp(cps(j)) then scan(j + 1) else j
        scan(start)
    end lowerLikeRunEnd

    /** o200k's lowercase-led alternative: any number of leading upper-like code points, then one
      * or more lower-like code points. Backtracks the upper-like prefix down until a lower-like
      * code point is reachable, matching how a greedy (non-possessive) regex quantifier backtracks.
      */
    private def alt1End(cps: Array[Int], start: Int): Maybe[Int] =
        val n           = cps.length
        val upperRunEnd = upperLikeRunEnd(cps, start)
        @tailrec def backtrack(k: Int): Maybe[Int] =
            if k < 0 then Absent
            else
                val pos = start + k
                if pos < n && isLowerLikeCp(cps(pos)) then Present(lowerLikeRunEnd(cps, pos))
                else backtrack(k - 1)
        backtrack(upperRunEnd - start)
    end alt1End

    /** o200k's uppercase-led alternative: one or more upper-like code points, then any number of
      * lower-like code points.
      */
    private def alt2End(cps: Array[Int], start: Int): Maybe[Int] =
        if start < cps.length && isUpperLikeCp(cps(start)) then
            Present(lowerLikeRunEnd(cps, upperLikeRunEnd(cps, start)))
        else Absent

    /** Tries `alternative` with the leading char consumed first (the greedy attempt), falling
      * back to no leading char when that attempt cannot reach a valid run; the same backtrack
      * order a greedy `leadingChar?` quantifier applies ahead of the run it precedes.
      */
    private def withOptionalLead(
        cps: Array[Int],
        index: Int,
        alternative: (Array[Int], Int) => Maybe[Int]
    ): Maybe[Int] =
        val n = cps.length
        val leading =
            if isLeadEligible(cps(index)) && index + 1 < n then alternative(cps, index + 1) else Absent
        leading.orElse(alternative(cps, index))
    end withOptionalLead

    /** The optional case-insensitive contraction suffix o200k folds onto the tail of each letter
      * alternative (as opposed to cl100k's standalone leading alternative).
      */
    private def withO200kSuffix(cps: Array[Int], end: Int): Int =
        val n = cps.length
        if end < n && cps(end) == '\'' then
            val length = suffixLength(cps, end)
            if length > 0 then end + length else end
        else end
        end if
    end withO200kSuffix

    private def o200kLetterEnd(cps: Array[Int], index: Int): Maybe[Int] =
        withOptionalLead(cps, index, alt1End)
            .orElse(withOptionalLead(cps, index, alt2End))
            .map(withO200kSuffix(cps, _))

    /** A digit run capped at three code points (`\p{N}{1,3}`, identical on both encodings; the
      * older unbounded `\p{N}+` grouping belongs only to the retired r50k/gpt2 grammar).
      */
    private def digitRunEnd(cps: Array[Int], index: Int): Maybe[Int] =
        if isDigitCp(cps(index)) then
            val n = cps.length
            @tailrec def scan(j: Int, count: Int): Int =
                if j < n && count < 3 && isDigitCp(cps(j)) then scan(j + 1, count + 1) else j
            Present(scan(index, 0))
        else Absent

    /** A punctuation/symbol run: an optional leading literal space, then one or more code points
      * that are none of whitespace/letter/digit, then a trailing run of newlines (also '/' on
      * o200k, matching its `[\r\n/]*` tail).
      */
    private def punctuationRunEnd(cps: Array[Int], index: Int, encoding: Encoding): Maybe[Int] =
        val n     = cps.length
        val lead  = if cps(index) == ' ' then 1 else 0
        val start = index + lead

        def isCore(cp: Int): Boolean = !isWhitespaceCp(cp) && !isLetterCp(cp) && !isDigitCp(cp)

        if start < n && isCore(cps(start)) then
            @tailrec def coreScan(j: Int): Int = if j < n && isCore(cps(j)) then coreScan(j + 1) else j
            val coreEnd                        = coreScan(start)
            def isTrailing(cp: Int): Boolean =
                isNewlineCp(cp) || (encoding == Encoding.O200kBase && cp == '/')
            @tailrec def trailingScan(j: Int): Int = if j < n && isTrailing(cps(j)) then trailingScan(j + 1) else j
            Present(trailingScan(coreEnd))
        else Absent
        end if
    end punctuationRunEnd

    /** The whitespace fallback, reached only when every other alternative failed (so `cps(index)`
      * is itself whitespace): cl100k's `\s++$` consumes the whole remainder when it is entirely
      * whitespace; otherwise a run reaching a newline is consumed up to and including that
      * newline (`\s*[\r\n]`/`\s*[\r\n]+`); otherwise the run is consumed in full when it reaches
      * the end of the text, or all but its last code point when a non-whitespace code point
      * follows (the trailing-whitespace lookahead `\s+(?!\S)`), or exactly one code point when
      * the run itself is a single code point.
      */
    private def whitespacePieceEnd(cps: Array[Int], index: Int, encoding: Encoding): Int =
        val n = cps.length
        @tailrec def allWhitespaceToEnd(j: Int): Boolean =
            if j >= n then true else isWhitespaceCp(cps(j)) && allWhitespaceToEnd(j + 1)
        if encoding == Encoding.Cl100kBase && allWhitespaceToEnd(index) then n
        else
            @tailrec def runScan(j: Int): Int = if j < n && isWhitespaceCp(cps(j)) then runScan(j + 1) else j
            val runEnd                        = runScan(index)
            @tailrec def lastNewline(j: Int, found: Int): Int =
                if j >= runEnd then found
                else if isNewlineCp(cps(j)) then lastNewline(j + 1, j)
                else lastNewline(j + 1, found)
            val newlineAt = lastNewline(index, -1)
            if newlineAt >= 0 then newlineAt + 1
            else if runEnd == n then runEnd
            else if runEnd - index >= 2 then runEnd - 1
            else index + 1
            end if
        end if
    end whitespacePieceEnd

    /** The end index (exclusive) of the pre-token piece starting at `cps(index)`, trying each of
      * the encoding's grammar alternatives in priority order.
      */
    private def pieceEnd(cps: Array[Int], index: Int, encoding: Encoding): Int =
        val letterEnd = encoding match
            case Encoding.Cl100kBase => cl100kLetterEnd(cps, index)
            case Encoding.O200kBase  => o200kLetterEnd(cps, index)
        contractionEnd(cps, index, encoding)
            .orElse(letterEnd)
            .orElse(digitRunEnd(cps, index))
            .orElse(punctuationRunEnd(cps, index, encoding))
            .getOrElse(whitespacePieceEnd(cps, index, encoding))
    end pieceEnd

    /** The code-point-level scan: decodes `text` into code points once, then walks
      * them left to right, taking the first matching grammar alternative at each position.
      */
    private def internalSplit(text: String, encoding: Encoding): Seq[String] =
        val length = text.length
        if length == 0 then Seq.empty
        else
            val codePoints  = Array.newBuilder[Int]
            val charOffsets = Array.newBuilder[Int]
            var pos         = 0
            while pos < length do
                val cp = text.codePointAt(pos)
                codePoints += cp
                charOffsets += pos
                pos += Character.charCount(cp)
            end while
            charOffsets += length
            val cps     = codePoints.result()
            val offsets = charOffsets.result()

            @tailrec def loop(index: Int, pieces: List[String]): List[String] =
                if index >= cps.length then pieces.reverse
                else
                    val end   = pieceEnd(cps, index, encoding)
                    val piece = text.substring(offsets(index), offsets(end))
                    loop(end, piece :: pieces)
            loop(0, Nil)
        end if
    end internalSplit
end Tiktoken
