package kyo.ai

import kyo.*
import kyo.ai.Context.*
import scala.annotation.tailrec

/** Counts real tokens for each text against a specific model's tokenizer.
  *
  * `count` is effectful so ONE trait backs both an offline tokenizer (the pure-Scala tiktoken,
  * which lifts its local counts with no I/O) and a provider count endpoint (Anthropic's
  * `count_tokens`, Gemini's `countTokens`, which issue one count request per distinct string). The
  * completion provider's default tokenizer is chosen by `Completion.defaultTokenizer`; a user
  * overrides it via `kyo.ai.Config.tokenizer`. The count is the COMPLETION model's tokenization
  * (the window the compactor manages), never the embedding model's.
  */
trait Tokenizer:

    /** The real token count of each input text, in input order. Offline tokenizers never fail; the
      * endpoint-backed defaults may fail transport (`HttpException`) or the typed `AIGenException`
      * leaves (`AIMissingApiKeyException`, `AIDecodeException`).
      */
    def count(texts: Chunk[String])(using Frame): Chunk[Int] < (LLM & Async & Abort[HttpException | AIGenException])

    /** Whether this tokenizer's `count` already frames each input as a message, so its result already
      * includes the provider's per-message envelope (role/formatting framing). An offline tokenizer
      * counts raw text only and leaves this false, so `Tokenizer.internal.countMessages` adds
      * `perMessageEnvelope` to bring the offline stamp up to endpoint parity. A provider count-endpoint
      * tokenizer, whose count POSTs each string as a one-message body and returns an already-inclusive
      * total, overrides this to true so the envelope is never added twice. INTERNAL capability, not part
      * of the public surface; a user's custom Tokenizer inherits false (its raw counts get the envelope).
      */
    private[kyo] def includesMessageEnvelope: Boolean = false

end Tokenizer

object Tokenizer:

    /** The tiktoken encoding selector for `Tokenizer.tiktoken`. `O200kBase` is the bundled default
      * (the GPT-4o/o200k vocabulary); `Cl100kBase` is the alternate encoding.
      */
    enum Encoding derives CanEqual:
        case O200kBase, Cl100kBase

    /** The built-in pure-Scala offline tiktoken tokenizer for `encoding`: a byte-level
      * BPE over a bundled rank table, cross-platform (JVM/JS/Native/Wasm), no JVM-only library and no
      * regex. Offline counting is a pure local call, so its `count` never touches the `Async`/`Abort`
      * capabilities the trait row permits.
      */
    def tiktoken(encoding: Encoding): Tokenizer = internal.Tiktoken(encoding)

    private[kyo] object internal:

        /** The per-message framing tokens a provider spends on role/formatting that raw-text tiktoken
          * does not see (the old heuristic's +4). OFFLINE-ONLY: `countMessages` adds it solely for a
          * tokenizer whose count is NOT already envelope-inclusive (offline tiktoken and user custom
          * tokenizers, `includesMessageEnvelope == false`), to bring that offline stamp UP to the
          * count-endpoint providers' envelope-inclusive parity. It is NEVER stacked on
          * a count-endpoint total, which already includes request framing. A real per-message cost in
          * the overflow direction, not a fudge multiplier.
          */
        val perMessageEnvelope: Int = 4

        /** A fixed per-image surcharge on the conservative (overflow) side of what providers bill for
          * vision content (roughly 1-2k tokens per image), so an image message is never counted as
          * zero. Text tiktoken counts text only; this is added per image message.
          */
        val imageSurcharge: Int = 2000

        /** The text a message contributes to its token count: content plus, for an assistant message,
          * its tool-call argument JSON (the bytes the provider actually bills).
          */
        def messageText(message: Message): String =
            message match
                case AssistantMessage(content, calls, _, _) =>
                    if calls.isEmpty then content
                    else content + " " + calls.map(_.arguments).mkString(" ")
                case other => other.content

        /** Stamps each message's real token count: the tokenizer's count of its text, plus the
          * per-message envelope for an offline tokenizer (a count-endpoint total is already
          * envelope-inclusive, so the envelope is skipped there, `includesMessageEnvelope`), plus the
          * image surcharge for an image-bearing user message (added on BOTH paths, since the endpoint
          * sees text-only via `messageText`). This is the enrichment token-stamp's counting core; the
          * seam calls it over the new-message suffix. Batched through one `count`
          * call so an endpoint tokenizer issues one request per distinct text.
          */
        def countMessages(tokenizer: Tokenizer, messages: Chunk[Message])(using
            Frame
        ): Chunk[Int] < (LLM & Async & Abort[HttpException | AIGenException]) =
            val envelope = if tokenizer.includesMessageEnvelope then 0 else perMessageEnvelope
            tokenizer.count(messages.map(messageText)).map { base =>
                messages.zip(base).map { (message, textTokens) =>
                    val imageTokens = message match
                        case UserMessage(_, image, _, _) => if image.isDefined then imageSurcharge else 0
                        case _                           => 0
                    textTokens + envelope + imageTokens
                }
            }
        end countMessages

        /** The offline byte-level BPE tokenizer for one encoding. Pure: `count` lifts its local counts
          * and never uses the `Async`/`Abort` row.
          */
        final case class Tiktoken(encoding: Encoding) extends Tokenizer:
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

        object Tiktoken:
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

        /** An immutable byte-sequence -> rank lookup for one encoding, backed by the generated packed
          * rank table (O200kRanks / Cl100kRanks). Pure and process-shared (an embedded immutable value,
          * no process-global mutable state).
          */
        final class Ranks(private val table: Map[Ranks.Key, Int]):
            /** The rank of the byte range `bytes[start until end)`, or Absent when that byte sequence is
              * not a token in this vocabulary (the BPE stops merging that pair).
              */
            def rankOf(bytes: Array[Byte], start: Int, end: Int): Maybe[Int] =
                Maybe.fromOption(table.get(Ranks.Key(bytes, start, end)))
        end Ranks

        object Ranks:
            /** A value key over a byte sub-range, equal by CONTENT so two equal byte sequences hash the
              * same regardless of backing array (the merge loop keys on live sub-ranges).
              */
            final class Key(private val bytes: Array[Byte], private val start: Int, private val end: Int) derives CanEqual:
                private val hash: Int =
                    var h = 1
                    var i = start
                    while i < end do
                        h = 31 * h + bytes(i); i += 1
                    h
                end hash
                override def hashCode(): Int = hash
                override def equals(other: Any): Boolean =
                    other match
                        case that: Key => that.hash == hash && sameBytes(that.bytes, that.start, that.end)
                        case _         => false

                /** True when this key's byte range equals `[s, e)` of `b`, byte-for-byte. Two keys are
                  * equal by CONTENT, so the merge loop keys directly on live sub-ranges with no copy.
                  */
                private def sameBytes(b: Array[Byte], s: Int, e: Int): Boolean =
                    if (e - s) != (end - start) then false
                    else
                        var i  = start
                        var j  = s
                        var eq = true
                        while eq && i < end do
                            if bytes(i) != b(j) then eq = false
                            i += 1
                            j += 1
                        end while
                        eq
            end Key

            private val o200k: Ranks  = Ranks(O200kRanks.load())
            private val cl100k: Ranks = Ranks(Cl100kRanks.load())

            def forEncoding(encoding: Encoding): Ranks =
                encoding match
                    case Encoding.O200kBase  => o200k
                    case Encoding.Cl100kBase => cl100k

            /** Decodes a generated rank table's base64 chunks into the content-keyed rank map. Pure and
              * cross-platform: a hand-rolled base64 decoder (java.util.Base64's javalib coverage across
              * Scala.js/Native/Wasm is unconfirmed in this tree, so it is not depended on) feeds a walk of
              * the packed byte stream, where each entry is one length byte, that many token bytes, then a
              * LEB128 varint rank. Called once per encoding by O200kRanks.load / Cl100kRanks.load; the
              * result is held in the immutable `o200k` / `cl100k` vals above.
              */
            private[kyo] def decode(chunks: Array[String]): Map[Key, Int] =
                val packed  = decodeBase64(chunks)
                val builder = Map.newBuilder[Key, Int]
                var i       = 0
                while i < packed.length do
                    val len      = packed(i) & 0xff
                    val keyStart = i + 1
                    val keyEnd   = keyStart + len
                    var rank     = 0
                    var shift    = 0
                    var j        = keyEnd
                    var more     = true
                    while more do
                        val b = packed(j) & 0xff
                        rank |= (b & 0x7f) << shift
                        shift += 7
                        j += 1
                        more = (b & 0x80) != 0
                    end while
                    builder += (Key(packed, keyStart, keyEnd) -> rank)
                    i = j
                end while
                builder.result()
            end decode

            /** Pure-Scala base64 decode of the concatenated chunks into the raw packed bytes. Standard
              * alphabet, '=' padding tolerated; no java.util.Base64, no java.util.zip, no resource file.
              */
            private def decodeBase64(chunks: Array[String]): Array[Byte] =
                val out  = Array.newBuilder[Byte]
                var acc  = 0
                var bits = 0
                var ci   = 0
                while ci < chunks.length do
                    val s = chunks(ci)
                    var k = 0
                    while k < s.length do
                        val c = s.charAt(k)
                        if c != '=' then
                            acc = (acc << 6) | base64Value(c)
                            bits += 6
                            if bits >= 8 then
                                bits -= 8
                                out += ((acc >> bits) & 0xff).toByte
                        end if
                        k += 1
                    end while
                    ci += 1
                end while
                out.result()
            end decodeBase64

            private def base64Value(c: Char): Int =
                if c >= 'A' && c <= 'Z' then c - 'A'
                else if c >= 'a' && c <= 'z' then c - 'a' + 26
                else if c >= '0' && c <= '9' then c - '0' + 52
                else if c == '+' then 62
                else 63 // '/'
        end Ranks

    end internal
end Tokenizer
