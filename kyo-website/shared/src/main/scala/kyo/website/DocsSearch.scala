// PUBLIC client search index
package kyo.website

import kyo.*

/** Client-side ranked search index and query engine for the documentation.
  *
  * The index is FLAT at the section level: every `##`/`###` section of every module is one searchable
  * [[Section]] document carrying its heading, anchor slug, prose body, and the API symbols it mentions
  * (the base identifier of each code reference, e.g. `Abort.run` and `Abort[E]` both contribute the
  * symbol `Abort`). Module entries also expose their title, so a module name resolves to the module
  * page. This granularity is what lets a type name like `Abort` resolve to the section that actually
  * documents it rather than to incidental method-name matches elsewhere.
  *
  * [[filter]] ranks with a field-boosted TF-IDF score (the standard relevance model, tuned for a small
  * corpus): each query word scores against four fields with descending weight, module TITLE, section
  * HEADING, section SYMBOLS (an exact symbol match is the strongest signal), and section BODY, each
  * contribution scaled by the term's inverse document frequency so common words count for little and
  * distinctive ones (a type name) count for a lot. A word that does not match exactly falls back to a
  * prefix match at reduced weight so search-as-you-type ranks sensibly on a partial last word. Every
  * query word must match somewhere for a document to be a hit (precision for multi-word queries). The
  * function is pure and deterministic, so the dropdown and keyboard navigation always agree.
  *
  * Documents are tokenized by splitting on non-alphanumerics AND camelCase boundaries (so `foldAbort`
  * yields `fold`, `abort`, and `foldabort`); queries are split on non-alphanumerics only. Symbols are
  * kept whole for exact matching, with their camelCase parts folded into the body tokens for recall.
  *
  * @see
  *   [[DocsSearch.filter]] to run a query
  * @see
  *   [[DocsSearch.seed]] to build the synchronous title-only seed used before the full index loads
  */
object DocsSearch:

    /** A module section heading: display text and anchor slug. Carried by the version manifest's `toc`
      * (parsed by `DocsClient.routeTable`); the ranked search index uses the richer [[Section]] instead.
      */
    final case class Heading(text: String, slug: String) derives CanEqual

    /** One searchable section of a module: a heading and the content beneath it.
      *
      * @param heading
      *   The section heading text (shown as the `search-result-sub` label, and a boosted match field).
      * @param slug
      *   The heading's anchor slug, used to build the `#<slug>` fragment of a section hit's route.
      * @param level
      *   The heading level (1 for the module intro/H1, 2 for `##`, 3 for `###`).
      * @param body
      *   The section's stripped prose, matched as the lowest-boost field.
      * @param symbols
      *   The base API identifiers the section references (e.g. `Abort`, `Env`), matched exactly with a
      *   high boost so a type-name query resolves to the section that features it.
      */
    final case class Section(heading: String, slug: String, level: Int, body: String, symbols: Chunk[String]) derives CanEqual

    /** One indexed module: its identity plus every searchable section.
      *
      * @param slug
      *   URL slug for the module page.
      * @param title
      *   Display title, the highest-boost match field (so a module name ranks the module page top).
      * @param group
      *   Sidebar group the module belongs to.
      * @param prefix
      *   The physical tree prefix (`latest` or `v<X>`) the module's route lives under.
      * @param sections
      *   The module's searchable sections, in document order. Empty for the title-only [[seed]].
      */
    final case class Entry(slug: String, title: String, group: String, prefix: String, sections: Chunk[Section]) derives CanEqual

    /** The complete search index for one documentation version: one [[Entry]] per module. */
    final case class Index(entries: Chunk[Entry]) derives CanEqual

    /** A single search result hit.
      *
      * @param slug
      *   URL slug for the matched module page.
      * @param title
      *   Display title of the matched module (the primary result label).
      * @param route
      *   The client-routable href: `/<prefix>/<slug>/` for a module hit, plus `#<heading-slug>` for a
      *   section hit.
      * @param sub
      *   The matched section heading (rendered as the `search-result-sub` label); `Absent` for a module
      *   hit.
      */
    final case class Hit(slug: String, title: String, route: String, sub: Maybe[String]) derives CanEqual

    /** Build the synchronous title-only seed: one section-less [[Entry]] per module, so the very first
      * keystroke already matches module names. The eager fetch upgrades this to the full section index.
      */
    def seed(prefix: String, modules: Chunk[WebsiteModule]): DocsSearch.Index =
        Index(modules.map(m => Entry(m.slug, m.title, m.group, prefix, Chunk.empty)))

    // ---- field boosts (descending: an exact symbol or a title is worth far more than a body word) ----
    private val TitleBoost: Double   = 9.0
    private val SymbolBoost: Double  = 6.0
    private val HeadingBoost: Double = 5.0
    private val BodyBoost: Double    = 2.0
    private val SubFactor: Double    = 0.2  // a camelCase sub-word hit (`abort` inside `foldAbort`) counts low
    private val PrefixFactor: Double = 0.25 // a prefix (as-you-type) hit counts low
    private val K: Double            = 1.2  // BM25 term-frequency saturation

    // A core CONCEPT (a type/effect) is DEFINED in the foundation modules and only REFERENCED elsewhere,
    // so a section's relevance for a concept query depends on its module's tier: the effect-defining
    // groups are boosted, the external-integration groups down-weighted, everything else neutral. This
    // keeps a query like `Abort` resolving to the canonical effect docs (kyo-prelude, "Foundation")
    // rather than to an integration's bridge section that merely names the type in its heading. The tier
    // applies to section hits only, so module-name queries are unaffected.
    private val CoreGroups: Set[String]       = Set("foundation", "application runtime")
    private val PeripheralGroups: Set[String] = Set("interop with other effect stacks", "scheduler embedding for other runtimes")
    private def tierWeight(group: String): Double =
        val g = group.toLowerCase
        if CoreGroups.contains(g) then 1.5
        else if PeripheralGroups.contains(g) then 0.6
        else 1.0
    end tierWeight

    /** Run a ranked query, returning hits best-first. Blank queries yield no hits. */
    def filter(index: DocsSearch.Index, query: String): Chunk[DocsSearch.Hit] =
        val qWords = tokenizeQuery(query)
        if qWords.isEmpty then Chunk.empty
        else
            // Corpus IDF is computed over the section documents: a word in few sections is distinctive.
            val sections = index.entries.flatMap(_.sections)
            val docCount = math.max(sections.size, 1)
            val df       = scala.collection.mutable.HashMap.empty[String, Int]
            sections.foreach { s =>
                sectionTermSet(s).foreach(t => df.update(t, df.getOrElse(t, 0) + 1))
            }
            def idf(t: String): Double =
                val d = df.getOrElse(t, 0)
                math.max(0.0001, math.log(1.0 + (docCount - d + 0.5) / (d + 0.5)))

            val scored = scala.collection.mutable.ArrayBuffer.empty[(Double, Int, Int, Hit)]
            index.entries.zipWithIndex.foreach { case (e, ei) =>
                // Module-title hit: score the title field on its own; a module name should win for it.
                val titleScore = scoreField(qWords, wholeTokens(e.title), TitleBoost, idf)
                if titleScore > 0 then scored += ((titleScore, 0, ei, Hit(e.slug, e.title, moduleRoute(e), Absent)))
                e.sections.zipWithIndex.foreach { case (s, si) =>
                    val headWhole  = wholeTokens(s.heading)
                    val headSub    = subTokens(s.heading)
                    val bodyWhole  = wholeTokens(s.body)
                    val bodySub    = subTokens(s.body)
                    val syms       = s.symbols.iterator.map(_.toLowerCase).toSet
                    var score      = 0.0
                    var matchedAll = true
                    qWords.foreach { w =>
                        val bw = count(bodyWhole, w)
                        val hw = count(headWhole, w)
                        var c  = 0.0
                        // A featured symbol is frequency-weighted by its whole-word body count, so the
                        // section that uses a type the most (the one that documents it) wins it.
                        if syms.contains(w) then c += SymbolBoost * idf(w) * tf(bw + 1)
                        c += HeadingBoost * idf(w) * tf(hw)
                        c += BodyBoost * idf(w) * tf(bw)
                        // camelCase sub-word hits (`abort` inside `foldAbort`) keep recall but count low.
                        c += SubFactor * (HeadingBoost * idf(w) * tf(count(headSub, w)) + BodyBoost * idf(w) * tf(count(bodySub, w)))
                        if c == 0.0 then
                            if w.length >= 2 && (prefixIn(headWhole, w) || prefixIn(bodyWhole, w) || syms.exists(_.startsWith(w)))
                            then c += PrefixFactor * HeadingBoost * idf(w)
                            else matchedAll = false
                        end if
                        score += c
                    }
                    if matchedAll && score > 0.0 then
                        scored += ((
                            score * tierWeight(e.group),
                            1,
                            si,
                            Hit(e.slug, e.title, s"${moduleRoute(e)}#${s.slug}", Present(s.heading))
                        ))
                    end if
                }
            }
            // Best score first; ties break by (module hit before section hit, then document order) for stability.
            Chunk.from(scored.sortBy { case (sc, kind, ord, _) => (-sc, kind, ord) }.iterator.map(_._4))
        end if
    end filter

    // ---- scoring helpers ----

    private def scoreField(qWords: Seq[String], docToks: Seq[String], boost: Double, idf: String => Double): Double =
        var score = 0.0
        var all   = true
        qWords.foreach { w =>
            val n = count(docToks, w)
            if n > 0 then score += boost * idf(w) * tf(n)
            else if w.length >= 2 && prefixIn(docToks, w) then score += PrefixFactor * boost * idf(w)
            else all = false
        }
        if all then score else 0.0
    end scoreField

    private def tf(n: Int): Double = if n <= 0 then 0.0 else n * (K + 1.0) / (n + K)

    private def count(toks: Seq[String], w: String): Int = toks.count(_ == w)

    private def prefixIn(toks: Seq[String], w: String): Boolean = toks.exists(t => t != w && t.startsWith(w))

    private def moduleRoute(e: Entry): String = s"/${e.prefix}/${e.slug}/"

    // The distinct term set of a section, used for document-frequency counting.
    private def sectionTermSet(s: Section): Set[String] =
        (wholeTokens(s.heading) ++ subTokens(s.heading) ++ wholeTokens(s.body) ++ subTokens(s.body)
            ++ s.symbols.iterator.map(_.toLowerCase)).toSet

    // ---- tokenization ----

    /** Query / whole-word tokens: split on non-alphanumerics, lowercase, NO camelCase split, so a user
      * typing `foldAbort` searches that whole identifier while `Abort` searches just `abort`, and a
      * document's `foldAbort` is a different whole word from a bare `Abort`.
      */
    private[website] def tokenizeQuery(s: String): Seq[String] = wholeTokens(s)

    private[website] def wholeTokens(s: String): Seq[String] =
        s.split("[^A-Za-z0-9]+").iterator.filter(_.nonEmpty).map(_.toLowerCase).toSeq

    /** Sub-word tokens: the camelCase parts of multi-part identifiers only (so `foldAbort` yields `fold`
      * and `abort`, but a plain word yields nothing here). Used as a low-weight recall signal.
      */
    private[website] def subTokens(s: String): Seq[String] =
        s.split("[^A-Za-z0-9]+").iterator.filter(_.nonEmpty).flatMap { w =>
            val parts = camelParts(w)
            if parts.sizeIs > 1 then parts.iterator.map(_.toLowerCase) else Iterator.empty
        }.toSeq

    /** Split an identifier at lowercase->uppercase and letter<->digit boundaries (`foldAbort` ->
      * `fold`, `Abort`; `runOrAbort` -> `run`, `Or`, `Abort`), without splitting runs of capitals.
      */
    private[website] def camelParts(w: String): List[String] =
        val parts = scala.collection.mutable.ListBuffer.empty[String]
        val cur   = new StringBuilder
        var prev  = ' '
        w.foreach { ch =>
            val boundary =
                cur.nonEmpty && (
                    (prev.isLower && ch.isUpper) || (prev.isLetter && ch.isDigit) || (prev.isDigit && ch.isLetter)
                )
            if boundary then
                parts += cur.toString
                cur.clear()
            cur += ch
            prev = ch
        }
        if cur.nonEmpty then parts += cur.toString
        parts.toList
    end camelParts

end DocsSearch
