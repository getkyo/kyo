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
      *   - Entries where ALL query words appear in the title are emitted first, as a single hit each
      *     whose route is the module page `/<prefix>/<slug>/`.
      *   - Then, for entries that did NOT match by title, each section heading where ALL query words
      *     appear emits one hit whose route is `/<prefix>/<slug>/#<heading-slug>` and whose `sub` is
      *     the heading text. A module with no headings but whose joined `text` matches emits a single
      *     module-page hit (the prose-index fallback), preserving the prose [[index]] behaviour.
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
            val words = query.trim.toLowerCase.split("\\s+").toSeq
            val titleHits = index.entries.collect {
                case e if matches(e.title.toLowerCase, words) =>
                    Hit(e.slug, e.title, moduleRoute(e), Absent)
            }
            val headingHits = index.entries.flatMap { e =>
                if matches(e.title.toLowerCase, words) then Chunk.empty
                else if e.headings.isEmpty then
                    // Prose-index fallback: no per-heading anchors, so emit a single module-page hit
                    // when the entry's text matches.
                    if matches(e.text.toLowerCase, words) then Chunk(Hit(e.slug, e.title, moduleRoute(e), Absent))
                    else Chunk.empty
                else
                    e.headings.collect {
                        case h if matches(h.text.toLowerCase, words) =>
                            Hit(e.slug, e.title, s"${moduleRoute(e)}#${h.slug}", Present(h.text))
                    }
            }
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

end DocsSearch
