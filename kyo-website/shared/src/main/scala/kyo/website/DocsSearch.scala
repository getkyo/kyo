// flow-allow: PUBLIC client search index
package kyo.website

import kyo.*

/** Client-side search index and filter for documentation content.
  *
  * Builds a flat search index from a [[WebsiteContent]] value and filters it by a plain-text
  * query. All operations are pure and synchronous. The search text is the stripped prose from
  * each module's README: fenced code blocks, inline code, Markdown punctuation, and HTML tags
  * are removed so search terms match narrative text only.
  *
  * The search is a case-insensitive, multi-word substring filter. Title hits are ranked before
  * text-only hits. An empty or blank query yields no hits.
  *
  * @see
  *   [[DocsSearch.index]] to build the index
  * @see
  *   [[DocsSearch.filter]] to search it
  */
object DocsSearch:

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
      * `slug` is the module's URL slug. `title` is the display title. `group` is the sidebar
      * group name. `text` is the stripped prose text used for full-text search.
      *
      * @param slug
      *   URL slug for the module page.
      * @param title
      *   Display title shown in search results.
      * @param group
      *   Sidebar group the module belongs to.
      * @param text
      *   Stripped prose text for full-text search (no code, no Markdown punctuation).
      */
    final case class Entry(slug: String, title: String, group: String, text: String) derives CanEqual

    /** A single search result hit.
      *
      * @param slug
      *   URL slug for the matched module page.
      * @param title
      *   Display title of the matched module.
      */
    final case class Hit(slug: String, title: String) derives CanEqual

    /** Build a search index from a [[WebsiteContent]] value.
      *
      * Produces one [[Entry]] per module across all groups, in README order. The `text` field of
      * each entry is the stripped prose of that module's README (see [[plaintext]]).
      *
      * @param content
      *   The versioned documentation content.
      * @return
      *   A [[DocsSearch.Index]] with one entry per module.
      */
    def index(content: WebsiteContent): DocsSearch.Index =
        val entries = content.groups.flatMap { group =>
            group.modules.map { mod =>
                Entry(mod.slug, mod.title, mod.group, plaintext(mod.readme))
            }
        }
        Index(entries)
    end index

    /** Filter the index by a plain-text query, ranking title hits before text-only hits.
      *
      * The query is split on whitespace and all matching is case-insensitive substring. A blank
      * or whitespace-only query yields an empty [[Chunk]]. Entries where ALL query words appear
      * in the title are emitted first; entries where ALL query words appear in the prose text
      * (but not the title) follow.
      *
      * @param index
      *   The index to search.
      * @param query
      *   The raw query string. Blank returns empty.
      * @return
      *   Matched hits in ranked order (title matches first).
      */
    def filter(index: DocsSearch.Index, query: String): Chunk[DocsSearch.Hit] =
        if query.isBlank then Chunk.empty
        else
            val words     = query.trim.toLowerCase.split("\\s+").toSeq
            val titleHits = index.entries.filter(e => matches(e.title.toLowerCase, words))
            val textHits  = index.entries.filter(e => !matches(e.title.toLowerCase, words) && matches(e.text.toLowerCase, words))
            (titleHits ++ textHits).map(e => Hit(e.slug, e.title))
        end if
    end filter

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
