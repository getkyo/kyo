package kyo.ai.compactor

import kyo.*
import kyo.Compactor
import kyo.Compactor.Mechanism
import kyo.ai.*
import kyo.ai.Context.*
import scala.annotation.tailrec

/** Content extraction and presentation for the default compactor: the pure functions that turn a region
  * into text, decide which of its words are reference-bearing, and shorten a body to a budget.
  *
  * Split out of [[Compactor]] because none of it makes a compaction DECISION: nothing here chooses what to
  * keep or when to compact, and nothing here calls back into the strategy, which is what makes it separately
  * readable. It reads exactly three settings (the rarity floor, the terse budget, and whether the repoint
  * mechanism is on), declared as abstract members below so the dependency is visible rather than implied.
  *
  * Mixed into the default compactor rather than kept as a bare object so that `viewTokens` and its
  * neighbours stay members the ablation tests can reach on a constructed arm.
  */
private[kyo] trait Content:

    import Model.*

    /** The policy and the measured constants, supplied by the compactor this trait is mixed into.
      *
      * Declared abstract rather than threaded through each call so these helpers keep the exact signatures
      * the ablation tests already invoke on a constructed arm. Three values are read: the rarity floor and
      * the terse budget from the calibration, and the mechanism set from the tuning.
      */
    def tuning: Compactor.Tuning
    def calibration: Calibration

    def roleTagged(msgs: Chunk[Message]): String =
        msgs.map(m => s"${m.role.name}: ${m.content}").mkString("\n")

    // ---- pure derivation helpers ----
    // viewTokens reads STORED stamps: the demotion loop and hard-limit check make zero requests.
    def viewTokens(view: Chunk[Message]): Int =
        view.foldLeft(0)((n, m) => n + stampedTokens(m))

    def unitContent(u: Region, raw: Chunk[Message]): String =
        u.indices.map { idx =>
            raw(idx) match
                case AssistantMessage(c, calls, _, _) =>
                    if calls.isEmpty then c else c + " " + calls.map(_.arguments).mkString(" ")
                case m => m.content
        }.mkString(" ")

    // Structural identifier extraction: backticked / path-dotted-snake-camel / digit-bearing,
    // len >= 3. Interior signal only: structural punctuation or a digit anywhere, or an
    // uppercase letter at an INTERIOR position (never the token's first char), so a sentence-initial
    // capitalized word like "The" mints no identifier while camelCase / HTTPServer / value42 /
    // Config.timeout do. No stop-word list, no bare words, no pattern matching: a hand character-class
    // scan expressed as a @tailrec walk with a start-index sentinel (no bare while, no cross-scope var).
    def extractTokens(content: String): Chunk[String] =
        val out = Chunk.newBuilder[String]
        def emit(tok0: String): Unit =
            val backticked = tok0.length >= 2 && tok0.head == '`' && tok0.last == '`'
            val tok        = if backticked then tok0.substring(1, tok0.length - 1) else tok0
            val punctOrDigit = tok.exists(c =>
                c == '/' || c == '.' || c == '_' || c == ':' || c == '-' || c.isDigit
            )
            val interiorUpper = tok.drop(1).exists(_.isUpper)
            val structured    = backticked || punctOrDigit || interiorUpper
            if tok.length >= 3 && structured then out += tok
        end emit
        @tailrec def loop(i: Int, start: Int): Unit =
            if i >= content.length then
                if start >= 0 then emit(content.substring(start))
            else
                val c = content.charAt(i)
                val isTokenChar =
                    c == '`' || c == '/' || c == '.' || c == '_' || c == ':' || c == '-' || c.isLetterOrDigit
                if isTokenChar then loop(i + 1, if start >= 0 then start else i)
                else
                    if start >= 0 then emit(content.substring(start, i))
                    loop(i + 1, -1)
                end if
        loop(0, -1)
        out.result()
    end extractTokens

    // The rarity oracle over the bundled tiktoken vocabulary: a word is DISTINCTIVE when the vocabulary
    // does not hold it at a low (frequent) rank. Words are looked up space-prefixed because that is how
    // tiktoken represents a mid-sentence word, so " the" resolves to its true low rank. A term absent
    // from the vocabulary (it takes several merges to spell) is distinctive by construction. A bigram is
    // judged by its rarest word: two individually-common words ("retry budget") form a distinctive
    // phrase, which is the case this tier exists to catch.
    private val rarityRanks = Tokenizer.internal.Ranks.forEncoding(Tokenizer.Encoding.O200kBase)

    def isDistinctive(term: String): Boolean =
        rarityRank(term) >= calibration.contentRarityFloor

    /** A term's BPE rank in the bundled vocabulary, `Int.MaxValue` for a term the vocabulary does not
      * hold, judged by its RAREST word when it is a bigram.
      *
      * Exposed rather than kept inside [[isDistinctive]] because two consumers need the same number for
      * different questions. Distinctiveness asks whether a term is above the stopword floor, a yes or no.
      * Automatic recall's detector asks HOW rare it is, so a match on an exotic identifier weighs more
      * than a match on a merely uncommon word. Two rank lookups would be two definitions of rarity that
      * can drift; one is one.
      */
    def rarityRank(term: String): Int =
        def rankOfWord(w: String): Int =
            val bytes = (" " + w).getBytes(java.nio.charset.StandardCharsets.UTF_8)
            rarityRanks.rankOf(bytes, 0, bytes.length) match
                case Present(r) => r
                case Absent     => Int.MaxValue
        end rankOfWord
        val space = term.indexOf(' ')
        if space < 0 then rankOfWord(term)
        else math.max(rankOfWord(term.substring(0, space)), rankOfWord(term.substring(space + 1)))
    end rarityRank

    // The bare-word candidate set for the second reference tier (see contentReferenceWeight): every
    // ASCII-letter run of length >= 3, plus each adjacent-word BIGRAM (joined by a space, so a
    // two-common-word noun phrase like "retry budget" becomes one distinctive anchor even when its words
    // are individually common). ASCII-only + a manual lowercase keep this identical on JVM/JS/Native (no
    // Character.getType locale/category divergence, no locale-sensitive toLowerCase). No filtering here:
    // the cross-region document-frequency cutoff, recurrence floor, rarity floor, and per-region cap all
    // live in deriveGraph, which alone sees the whole conversation.
    def contentTokens(content: String): Chunk[String] =
        val words                           = Chunk.newBuilder[String]
        def isAsciiLetter(c: Char): Boolean = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
        def add(start: Int, end: Int): Unit =
            if end - start >= 3 then
                val lowered = content.substring(start, end).map(c => if c >= 'A' && c <= 'Z' then (c + 32).toChar else c)
                words += lowered
        @tailrec def loop(i: Int, start: Int): Unit =
            if i >= content.length then
                (if start >= 0 then add(start, content.length)
            )
            else if isAsciiLetter(content.charAt(i)) then loop(i + 1, if start >= 0 then start else i)
            else
                if start >= 0 then add(start, i)
                loop(i + 1, -1)
        loop(0, -1)
        val ws = words.result()
        val bigrams =
            if ws.length < 2 then Chunk.empty[String]
            else Chunk.from((0 until ws.length - 1).map(i => ws(i) + " " + ws(i + 1)))
        ws.concat(bigrams)
    end contentTokens

    // The recall id from an object-shaped tool argument, decoded through the SAME typed Recall
    // schema the tool registers (no regex): a pure typed decode for the refetch count.
    def recallArgId(arguments: String)(using Frame): Maybe[Int] =
        Json.decode[Recall](arguments) match
            case Result.Success(r) => Present(r.id)
            case _                 => Absent

    // Line-aware, code-point-safe elision: keep the exact head and tail around an elision
    // mark, never severing a surrogate pair or splitting mid-line. Returns the content unchanged
    // when it already fits the budget (char units).
    def elide(content: String, budget: Int): String =
        if content.length <= budget then content
        else
            val half = math.max(1, budget / 2)
            s"${safeCut(content, half, fromEnd = false)}\n...[elided]...\n${safeCut(content, half, fromEnd = true)}"

    // A code-point + line safe cut of about `n` chars from the start (fromEnd=false) or end (true):
    // snaps to the nearest line boundary within the budget and never lands inside a surrogate pair.
    def safeCut(s: String, n: Int, fromEnd: Boolean): String =
        if fromEnd then
            val start = adjust(s, math.max(0, s.length - n))
            val nl    = s.indexOf('\n', start)
            s.substring(if nl >= 0 then nl + 1 else start)
        else
            val end = adjust(s, math.min(s.length, n))
            val nl  = s.lastIndexOf('\n', end)
            s.substring(0, if nl > 0 then nl else end)

    // java.lang.Character.isLowSurrogate is a pure char-classification predicate (no kyo primitive
    // covers surrogate detection); stepping off a low surrogate keeps the cut code-point-safe.
    def adjust(s: String, i: Int): Int =
        if i > 0 && i < s.length && Character.isLowSurrogate(s.charAt(i)) then i + 1 else i

    // The terse render: a fixed-budget prefix of the write-once summary bytes, code-point-safe.
    def tersePrefix(bytes: String): String =
        if bytes.length <= calibration.tersePrefixChars then bytes else safeCut(bytes, calibration.tersePrefixChars, fromEnd = false)

    // The degenerate-summary note: the fixed marker announcing an absent fill route.
    val substituteNote: String = "[summary unavailable: no fill route reachable; recall restores verbatim]"

    // The fixed-size substitute elision at the summary level: a deterministic function
    // of the frozen span, so it re-renders byte-identically without persistence.
    def substituteElision(content: String, budget: Int): String =
        s"${elide(content, budget)}\n$substituteNote"

    def repoint(id: Int, superseded: Dict[Int, Int]): Int =
        if !tuning.mechanisms.contains(Mechanism.Repoint) then id
        else repointed(id, superseded)

    private def repointed(id: Int, superseded: Dict[Int, Int]): Int =
        @tailrec def loop(cur: Int): Int =
            superseded.get(cur) match
                case Present(next) if next != cur => loop(next)
                case _                            => cur
        loop(id)
    end repointed

    def isSystemHead(u: Region, raw: Chunk[Message]): Boolean =
        u.indices.headOption.exists(idx => raw(idx).isInstanceOf[SystemMessage])

    def hasUser(u: Region, raw: Chunk[Message]): Boolean =
        u.indices.exists(idx => raw(idx).isInstanceOf[UserMessage])
end Content
