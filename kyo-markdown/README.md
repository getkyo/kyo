# kyo-markdown

kyo-markdown turns a single Markdown source string into a kyo-ui `UI` article tree plus the document's heading outline, in one pure, total call. `Markdown.render(source)` returns a `Markdown.Rendered(article, headings)`. `article` is a real `UI` value you embed into a page and hand to any kyo-ui runner (`UI.runRender`); `headings` is the ordered outline you turn into a table of contents. The two stay in lockstep: every outline entry's `id` is byte-identical to the `id` attribute set on the corresponding in-page `<h1>..<h4>`, so a table of contents built from `headings` links straight to the anchors in `article`.

Two facts complete the mental model. Rendering is total: the grammar is a bounded construct set (headings, paragraphs, ordered and unordered lists, GFM pipe tables, fenced code, blockquotes, inline emphasis, inline code, links, images, linked images, raw HTML, HTML comments), and anything it does not recognize degrades to plain text or a verbatim paragraph rather than aborting; an empty source yields `Rendered(UI.empty, Chunk.empty)`. Because the result is a plain value with no leftover effect, it is usable anywhere a `UI` subtree is built, including inside reactive regions that require pure producers. kyo-markdown also carries no third-party Markdown dependency and no syntax highlighter: it is built on kyo-parse `Parse[Char]` combinators, and fenced code renders as a plain `pre`/`code` pair exposing the info string's first token as a `language-<lang>` CSS class, leaving highlighting to the consumer. It runs unchanged on JVM, JS, Native, and Wasm.

<!-- doctest:setup
```scala
import kyo.*

final case class DocPage(slug: String, markdown: String)
final case class TocEntry(level: Int, label: String, anchor: String)

val docPage: DocPage =
    DocPage(
        "getting-started",
        "# Getting Started\n\n" +
            "Install and run in **one** step. See `Sync.defer` for suspension,\n" +
            "or jump to [config](#configuration).\n\n" +
            "## Configuration\n\n" +
            "- host: the server host\n" +
            "  - defaults to localhost\n" +
            "- port: the listening port\n\n" +
            "| Setting | Type |\n" +
            "| ------- | ---- |\n" +
            "| host    | String |\n" +
            "| port    | Int  |\n\n" +
            "```scala\n" +
            "val config = load(\"etc\", \"app\")\n" +
            "```\n\n" +
            "## Configuration\n\n" +
            "A duplicate heading, to show `-2` disambiguation.\n"
    )
```
-->

```scala
import kyo.*

val rendered = Markdown.render(
    "# Getting Started\n\nRun in **one** step.\n\n## Configuration\n"
)

// rendered.article: a UI subtree ready to embed
// rendered.headings: the ordered outline
val toc = rendered.headings.map(h => (h.level, h.text, h.id))
// Chunk((1, "Getting Started", "getting-started"), (2, "Configuration", "configuration"))
```

## Render a document

You reach for kyo-markdown when you have Markdown text and want a `UI` subtree you can embed plus an outline you can navigate. One call produces both. `Markdown.render(source: String): Rendered` is the whole entry point, and `Rendered` is the pair it hands back:

```scala
import kyo.*

val page: Markdown.Rendered = Markdown.render(docPage.markdown)

val article  = page.article  // UI, embeddable in any page
val headings = page.headings // Chunk[Markdown.Heading], the outline
```

When you want to display the document, embed `article`: it is a `UI` value, so you hand it to a kyo-ui runner exactly like any other subtree. When you want navigation (a sidebar, in-page anchors, a table of contents), read `headings`. Both describe the same document from the same `render` call.

`render` itself performs no effect: it takes a `String` and returns a `Rendered` synchronously, so the outline path never touches `Async`. Turning the article into HTML is the step that runs, through kyo-ui:

```scala
import kyo.*

val html: Stream[String, Async] =
    UI.runRender(Markdown.render(docPage.markdown).article)
```

> **Note:** `render` is pure and total, so the `Rendered` value is safe to produce inside a reactive region, a `Signal.render` boundary, or any position that requires a pure producer. You do not wrap it in `Sync`, `Abort`, or any handler.

## Build a table of contents

Documentation pages need a sidebar or a set of in-page anchors, and the outline that `render` returns alongside the article is exactly that. A `Markdown.Heading(level, text, id)` is one entry: `level` is `1..4` (matching `#` through `####`), `text` is the plain-text label, and `id` is the URL-safe anchor.

The `id` is derived from the heading text: lowercased, non-alphanumeric runs collapsed to a single `-`, leading and trailing `-` trimmed.

```scala
import kyo.*

val head = Markdown.render("## Composing: map, flatMap\n").headings.head
// head.level == 2
// head.text  == "Composing: map, flatMap"
// head.id    == "composing-map-flatmap"
```

Repeated heading text is disambiguated with a `-2` (then `-3`, and so on) suffix, on both the outline entry and the in-page element:

```scala
import kyo.*

val dupIds = Markdown.render("## Configuration\n## Configuration\n").headings.map(_.id)
// Chunk("configuration", "configuration-2")
```

> **Note:** Every outline `id` is the exact `id` attribute set on the matching in-page `<h1>..<h4>`, so a table of contents built from `headings` links straight into `article`. The duplicate counter is local to a single `render` call, so build the anchors and render the article from the same `Rendered` value; do not call `render` twice and mix the two.

A heading is rendered twice on purpose, with intentionally different results. The in-page `<hN>` keeps rich inline markup, but the outline `text` is flat:

```scala
import kyo.*

val h = Markdown.render("## Working with `Sync`\n").headings.head
// h.text == "Working with Sync"   (backticks removed for the label)
// h.id   == "working-with-sync"
```

> **Unlike** the in-page heading, which keeps real inline markup (a live `<code>`, bold, italic), the outline `text` is flattened: backticks and asterisks are removed and `[label](url)` collapses to `label`, so a table of contents shows clean labels.

## Which Markdown you can write

Before you feed a string to `render`, it helps to know exactly which constructs it understands. The grammar is a fixed set, taught here as two families (block structure and inline spans) plus a raw-HTML escape hatch. Anything outside the set degrades (see [Total by construction](#total-by-construction)).

### Block structure

Blocks are the document skeleton:

- ATX headings `#` through `####`; a line with five or more hashes is not a heading, it degrades to a paragraph with no outline entry.
- Paragraphs: consecutive non-blank lines coalesce into one `<p>` (joined with a space); a blank line starts a new one.
- Unordered lists `- `, with two-space sub-indented items (`  - `) kept nested.
- Ordered lists `N. `, with the marker stripped.
- GFM pipe tables: a header row, a separator row, then body rows; `\|` escapes a literal pipe inside a cell.
- Fenced code, delimited by three or more backticks.
- Blockquotes `> `, which may themselves contain paragraphs and fenced code.

A two-space sub-item stays nested inside its parent list item rather than flattening:

```scala
import kyo.*

val nested = Markdown.render(
    "- Exception\n" +
        "  - FileException\n" +
        "  - TimeoutException\n"
)
// FileException and TimeoutException render as a nested <ul> inside the Exception <li>.
val hasHeadings = nested.headings.isEmpty // true; a list produces no outline entries
```

A GFM table needs its separator row; the header cells become `<th>`, the rest `<td>`:

```scala
import kyo.*

val table = Markdown.render(
    "| Setting | Type |\n" +
        "| ------- | ---- |\n" +
        "| host    | String |\n" +
        "| port    | Int  |\n"
)
// table.article is a UI.table subtree.
```

Fenced code renders verbatim. The info string's first token labels the language:

```scala
import kyo.*

val withCode = Markdown.render(
    "```scala\n" +
        "val config = load(\"etc\", \"app\")\n" +
        "```\n"
)
// <pre><code class="language-scala">val config = load("etc", "app")</code></pre>
```

> **Note:** A fenced block renders as a plain `pre`/`code` pair. Only the info string's first whitespace-delimited token becomes a `language-<lang>` class (extra tokens are dropped), and there is no syntax highlighting; wiring one up is left to the consumer, see [Deliberate non-goals](#deliberate-non-goals).

> **Note:** Fences are length-aware. A four-backtick fence wraps inner three-backtick blocks verbatim as one code block instead of closing on the first inner ```` ``` ````, matching CommonMark. A length-unaware parser would shatter the content.

### Inline spans

Inside a paragraph, list item, table cell, or heading, `render` recognizes a fixed set of inline spans, resolved in PEG ordered-choice precedence:

- Bold `**text**` and italic `*text*`.
- Inline code `` `text` `` (a `<code>` chip).
- Links `[text](url)` and images `![alt](url)`.
- Linked images `[![alt](img)](link)` (an anchor wrapping an image).

```scala
import kyo.*

val prose = Markdown.render(
    "Install in **one** step with `Sync.defer`, or read the [guide](../guide.md).\n"
)
// One paragraph: **one** -> a bold span, `Sync.defer` -> an inline <code> chip,
// [guide](../guide.md) -> a relative-path link.
val noHeadings = prose.headings.isEmpty // true
```

The same CommonMark delimiter-length rule governs both layers of code. When you write a block of code, use a fenced block, where a fence of four or more backticks wraps inner three-backtick fences verbatim. When you write code inside a sentence, use a backtick span, where an N-backtick span lets shorter runs appear inside it. It is one rule applied at two scopes.

> **Note:** An unmatched opening backtick run in prose is kept as literal backticks and parsing resumes after it, so a bare ```` ```scala ```` written mid-sentence does not open a span that swallows the following text into a spurious `<code>`.

A link target is classified by prefix, which is what makes cross-document and in-page links both work:

```scala
import kyo.*

val links = Markdown.render(
    "See [config](#configuration), the [guide](../guide.md), " +
        "or [the site](https://getkyo.io).\n"
)
// #configuration  -> an in-page fragment link
// ../guide.md     -> a relative path, preserved untouched
// https://getkyo.io -> an external link, full URL preserved
```

> **Note:** `#id` becomes a fragment, `http://` and `https://` become external links, and everything else becomes a relative path kept exactly as written. A relative path is never rewritten.

### Raw HTML and comments

When a construct is outside the grammar but you still want it in the page, drop to raw HTML. A block-level `<img>` line, or a multi-line `<a>...<img>...</a>`, is coalesced and passed through verbatim inside a paragraph; an inline `<...>` snippet is the same escape hatch at inline scope.

```scala
import kyo.*

val embed = Markdown.render(
    "<img src=\"kyo.png\" width=\"200\" alt=\"Kyo\">\nSome text.\n"
)
// The <img> line passes through verbatim (wrapped in a <p>); "Some text." is normal prose.
```

> **Caution:** Raw HTML is emitted verbatim and is NOT escaped. An `<img>` or an `<a><img></a>` embed goes straight into the output, so only feed `render` Markdown you trust.

HTML comments are dropped, but text that trails the closing `-->` is not lost:

```scala
import kyo.*

val commented = Markdown.render(
    "<!-- a comment\nspanning lines -->Kept text.\n# Title\n"
)
val titles = commented.headings.map(_.text) // Chunk("Title")
// "Kept text." after the closing --> survives as a paragraph; the comment itself is gone.
```

> **Note:** A leading or interior HTML comment is skipped, but any text after the closing `-->` is preserved as a paragraph rather than silently dropped.

## Total by construction

You never wrap `render` in error handling, and that is deliberate. Every construct degrades instead of failing, which is exactly what lets the result be a plain value you can use in any position. An empty or whitespace-only source is the base case:

```scala
import kyo.*

val empty = Markdown.render("")
// empty == Markdown.Rendered(UI.empty, Chunk.empty)

val blank = Markdown.render("   \n\n  ")
// blank is also Rendered(UI.empty, Chunk.empty): whitespace-only counts as empty
```

Malformed and unknown input degrades rather than aborting. A table missing its separator row does not become a `<table>`, and an unsupported construct becomes a paragraph carrying its verbatim lines:

```scala
import kyo.*

val malformed = Markdown.render("| A | B |\n")
// No separator row, so this degrades to a paragraph, not a table.

val unknown = Markdown.render("term\n:   definition\n")
// Definition-list syntax is not in the grammar; it degrades to a paragraph.
// Both calls return a value; neither aborts.
```

> **Note:** An unknown inline construct degrades to plain text and an unrecognized block degrades to a paragraph, both through kyo-parse `recoverWith`. There is no configuration that makes `render` throw or return an error channel; totality is the contract.

## Deliberate non-goals

A few things kyo-markdown intentionally leaves to you, so nothing about the output surprises you later.

- No syntax highlighting. A fenced block is a plain `pre`/`code` pair with a `language-<lang>` class and nothing more; if you want highlighted tokens, wire a highlighter to that class on the consumer side.
- No third-party Markdown engine and no JVM-only dependency. The grammar is expressed with kyo-parse `Parse[Char]` combinators, so the same renderer runs on JVM, JS, Native, and Wasm.
- A fixed grammar. The construct set is bounded; input outside it degrades (see [Total by construction](#total-by-construction)) rather than being extended at runtime.

## Putting it together

Rendering a documentation page and deriving its sidebar exercises the whole surface in one thread: `render` the source, map the outline to your own navigation type, and hand the article to a runner. The running `docPage` carries a heading, bold and inline-code prose, a fragment link, a nested list, a GFM table, a Scala fence, and a duplicate heading:

```scala
import kyo.*

val rendered = Markdown.render(docPage.markdown)

val sidebar: Chunk[TocEntry] =
    rendered.headings.map(h => TocEntry(h.level, h.text, h.id))
// Chunk(
//   TocEntry(1, "Getting Started", "getting-started"),
//   TocEntry(2, "Configuration",   "configuration"),
//   TocEntry(2, "Configuration",   "configuration-2")
// )

// Each TocEntry.anchor is an id attribute inside rendered.article, so a sidebar
// link to "#configuration-2" jumps to the second Configuration heading.
val page: Stream[String, Async] = UI.runRender(rendered.article)
```

The two `## Configuration` headings become anchors `configuration` and `configuration-2`; the bold, inline code, and link in the paragraph render inside `article` but never appear in the outline; and the article is a `Stream[String, Async]` of HTML once you run it, while the sidebar was built with no effect at all.
