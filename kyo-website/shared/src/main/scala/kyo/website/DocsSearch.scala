// PUBLIC client search index
package kyo.website

import kyo.*

/** Client-side search index and filter for documentation content.
  *
  * Builds a flat search index from module titles plus section headings and filters it by a
  * plain-text query. All operations are pure and synchronous.
  *
  * Two index sources are supported:
  *   - [[DocsSearch.index]] builds entries from a [[WebsiteContent]] value, using each module's
  *     stripped README prose as the searchable text (code blocks, inline code, Markdown
  *     punctuation, and HTML tags removed).
  *   - [[DocsSearch.headingIndex]] builds entries from the same module list plus each module's
  *     section headings (from the version `manifest.json` `toc`), so heading matches surface a
  *     deep link to the heading anchor. This is the index the live header search uses.
  *
  * The search is a case-insensitive, multi-word substring filter. Title hits are ranked before
  * heading/text hits. An empty or blank query yields no hits.
  *
  * @see
  *   [[DocsSearch.index]] to build a prose index
  * @see
  *   [[DocsSearch.headingIndex]] to build a heading-aware index
  * @see
  *   [[DocsSearch.filter]] to search either
  */
object DocsSearch:

    /** A single section heading of a module: its display text and its anchor slug.
      *
      * @param text
      *   The heading text shown as the `search-result-sub` label and matched against the query.
      * @param slug
      *   The heading's anchor slug, used to build the `#<slug>` fragment of a heading hit's route.
      */
    final case class Heading(text: String, slug: String) derives CanEqual

    /** The complete search index for one documentation version.
      *
      * `entries` holds one [[Entry]] per module, in README order.
      *
      * @param entries
      *   The indexed module entries.
      */
    final case class Index(entries: Chunk[Entry]) derives CanEqual

    /** One indexed module entry.
      *
      * @param slug
      *   URL slug for the module page.
      * @param title
      *   Display title shown in search results.
      * @param group
      *   Sidebar group the module belongs to.
      * @param prefix
      *   The physical tree prefix (`latest` or `v<X>`) the module's route lives under, used to
      *   build hit routes `/<prefix>/<slug>/`.
      * @param text
      *   Searchable text for full-text matching: stripped prose ([[index]]) or the module's joined
      *   heading texts ([[headingIndex]]).
      * @param headings
      *   The module's section headings. Non-empty for a heading index (so a heading match can be
      *   pinpointed to its anchor); empty for a prose index.
      */
    final case class Entry(
        slug: String,
        title: String,
        group: String,
        prefix: String,
        text: String,
        headings: Chunk[Heading]
    ) derives CanEqual

    /** A single search result hit.
      *
      * @param slug
      *   URL slug for the matched module page.
      * @param title
      *   Display title of the matched module (the primary result label).
      * @param route
      *   The client-routable href: `/<prefix>/<slug>/` for a title hit, plus `#<heading-slug>` for
      *   a heading hit.
      * @param sub
      *   The matched heading text for a heading hit (rendered as the `search-result-sub` label);
      *   `Absent` for a title hit.
      */
    final case class Hit(slug: String, title: String, route: String, sub: Maybe[String]) derives CanEqual

    /** Build a prose search index from a [[WebsiteContent]] value.
      *
      * Produces one [[Entry]] per module across all groups, in README order. Each entry's `text`
      * is the stripped prose of that module's README (see [[plaintext]]) and its `headings` is
      * empty, so [[filter]] yields title and prose hits but no heading anchors. The `prefix` is the
      * content version's tag.
      *
      * @param content
      *   The versioned documentation content.
      * @return
      *   A [[DocsSearch.Index]] with one prose entry per module.
      */
    def index(content: WebsiteContent): DocsSearch.Index =
        val prefix = content.version.tag
        val entries = content.groups.flatMap { group =>
            group.modules.map { mod =>
                Entry(mod.slug, mod.title, mod.group, prefix, plaintext(mod.readme), Chunk.empty)
            }
        }
        Index(entries)
    end index

    /** Build a heading-aware search index from a module list plus per-module section headings.
      *
      * Produces one [[Entry]] per module, in the given order. Each entry's `text` is the module's
      * heading texts joined with spaces (so [[filter]]'s title-before-text ranking ranks title
      * matches first and heading matches second) and its `headings` carries the headings so a
      * heading match resolves to the heading's `#<slug>` anchor. Used by the live header search,
      * which sources titles/slugs/groups from the boot island and headings from the version
      * `manifest.json` `toc`.
      *
      * @param prefix
      *   The physical tree prefix (`latest` or `v<X>`) the modules' routes live under.
      * @param modules
      *   The modules to index (slug, title, group), in display order.
      * @param headingsOf
      *   The section headings for a module slug (empty when a module has none).
      * @return
      *   A [[DocsSearch.Index]] with one heading-aware entry per module.
      */
    def headingIndex(
        prefix: String,
        modules: Chunk[WebsiteModule],
        headingsOf: String => Chunk[Heading]
    ): DocsSearch.Index =
        val entries = modules.map { mod =>
            val headings = headingsOf(mod.slug)
            val text     = headings.map(_.text).mkString(" ")
            Entry(mod.slug, mod.title, mod.group, prefix, text, headings)
        }
        Index(entries)
    end headingIndex

    /** Filter the index by a plain-text query, ranking title hits before heading/text hits.
      *
      * The query is split on whitespace and all matching is case-insensitive substring. A blank or
      * whitespace-only query yields an empty [[Chunk]].
      *
      *   - Entries where ALL query words appear in the title are emitted first, ranked within the
      *     title band by match quality (exact match, then prefix match, then word-boundary match,
      *     then substring match), with entry document order as the tie-break.
      *   - Then, for entries that did NOT match by title, each section heading where ALL query words
      *     appear emits one hit ranked within the heading band by match quality, then heading document
      *     order. A module with no headings but whose joined `text` matches emits a single module-page
      *     hit (the prose-index fallback, ranked lowest in the heading band).
      *
      * The two-band order (all title hits before all heading/text hits) is never crossed by the
      * within-band ranking. The function is pure and deterministic: the same input always produces
      * the same output, so the dropdown and keyboard nav always agree.
      *
      * @param index
      *   The index to search.
      * @param query
      *   The raw query string. Blank returns empty.
      * @return
      *   Matched hits in ranked order (title matches first, then heading matches).
      */
    def filter(index: DocsSearch.Index, query: String): Chunk[DocsSearch.Hit] =
        if query.isBlank then Chunk.empty
        else
            val q     = query.trim.toLowerCase
            val words = q.split("\\s+").toSeq
            val titleHits =
                index.entries.zipWithIndex.collect {
                    case (e, idx) if matches(e.title.toLowerCase, words) =>
                        val key = (-matchClass(e.title.toLowerCase, q, words), 0, idx, 0)
                        (key, Hit(e.slug, e.title, moduleRoute(e), Absent))
                }.sortBy(_._1).map(_._2)
            val headingScored =
                index.entries.zipWithIndex.flatMap { case (e, idx) =>
                    if matches(e.title.toLowerCase, words) then Chunk.empty
                    else if e.headings.isEmpty then
                        // Prose-index fallback: no per-heading anchors, so emit a single module-page hit
                        // when the entry's text matches. isProse=1 ensures all per-heading hits rank first.
                        if matches(e.text.toLowerCase, words) then
                            val key = (1, -matchClass(e.text.toLowerCase, q, words), 0, idx, 0)
                            Chunk((key, Hit(e.slug, e.title, moduleRoute(e), Absent)))
                        else Chunk.empty
                    else
                        e.headings.zipWithIndex.collect {
                            case (h, hidx) if matches(h.text.toLowerCase, words) =>
                                // isProse=0 ensures per-heading hits sort before any prose-fallback hit.
                                val key = (0, -matchClass(h.text.toLowerCase, q, words), -levelWeight(e, h), idx, hidx)
                                (key, Hit(e.slug, e.title, s"${moduleRoute(e)}#${h.slug}", Present(h.text)))
                        }
                }
            val headingHits = headingScored.sortBy(_._1).map(_._2)
            titleHits ++ headingHits
        end if
    end filter

    private def moduleRoute(e: Entry): String = s"/${e.prefix}/${e.slug}/"

    /** Strip prose from a README Markdown string, removing code and punctuation for search.
      *
      * Strips (in order): fenced code blocks, inline code backticks, HTML tags, and common
      * Markdown punctuation characters (`#`, `*`, `_`, `[`, `]`, `(`, `)`, `` ` ``, `>`). The
      * result is the narrative prose suitable for full-text matching.
      */
    private def plaintext(readme: String): String =
        // Remove fenced code blocks (``` ... ``` or ~~~ ... ~~~)
        val noFenced = readme.replaceAll("(?s)```.*?```", " ").replaceAll("(?s)~~~.*?~~~", " ")
        // Remove inline code (`...`)
        val noInline = noFenced.replaceAll("`[^`]*`", " ")
        // Remove HTML tags
        val noHtml = noInline.replaceAll("<[^>]+>", " ")
        // Remove Markdown punctuation
        val noMarkdown = noHtml.replaceAll("[#*_\\[\\]()>`]+", " ")
        // Collapse whitespace
        noMarkdown.replaceAll("\\s+", " ").trim
    end plaintext

    private def matches(haystack: String, words: Seq[String]): Boolean =
        words.forall(w => haystack.contains(w))

    private def matchClass(candidate: String, fullQuery: String, words: Seq[String]): Int =
        if candidate == fullQuery then 3
        else if candidate.startsWith(fullQuery) then 2
        else if words.exists(w => atWordBoundary(candidate, w)) then 1
        else 0

    private def atWordBoundary(candidate: String, word: String): Boolean =
        var from = candidate.indexOf(word)
        while from >= 0 do
            val before = if from == 0 then ' ' else candidate.charAt(from - 1)
            if !before.isLetterOrDigit then return true
            from = candidate.indexOf(word, from + 1)
        end while
        false
    end atWordBoundary

    // DocsSearch.Heading is FROZEN without a level field (spec 3). levelWeight degenerates to 0
    // because Heading carries only text and slug; no level is available at filter time.
    // The heading-band sort key (isProse, -matchClass, -levelWeight, entryIndex, headingDocIndex)
    // therefore uses the prose discriminator, match quality, and document order only.
    // See phases/phase-03/decisions.md Decision 1.
    private def levelWeight(e: Entry, h: Heading): Int = 0

end DocsSearch
