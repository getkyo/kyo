// flow-allow: PUBLIC cross-platform heading-outline carrier consumed by DocsApp + DocsClient
package kyo.website

import kyo.*

/** Cross-platform heading-outline carrier for the kyo docs site.
  *
  * This object holds only the [[Heading]] type, which is shared between the JVM static-site
  * generator, the JS bundle client, and the shared app layer. The Markdown transpiler, syntax
  * highlighter, and rendered-article types live in [[DocsMarkdownRender]] (JVM-only) so
  * scalameta never reaches the JS link classpath.
  *
  * Consumers of the rendered article receive either a [[DocsMarkdownRender.Rendered]] value (on
  * the JVM build path) or a pre-rendered HTML string plus a [[Heading]] outline (on the JS
  * client path). The [[Heading]] type is the only render-namespace type reachable from shared
  * and JS sources (INV-009).
  *
  * @see
  *   [[DocsMarkdownRender]] for the JVM-only transpiler and highlighter
  * @see
  *   [[DocsMarkdownRender.Rendered]] for the JVM build-time render output
  */
object DocsMarkdown:

    /** A single heading entry in the document outline produced by [[DocsMarkdownRender.transpile]].
      *
      * `level` is 1..4 (matching `# ` through `#### `). `text` is the plain-text heading content
      * (markup stripped). `slug` is the URL-safe anchor derived from `text`: lowercased,
      * non-alphanumeric characters replaced with `-`, runs of `-` collapsed, leading and trailing
      * `-` removed, and duplicate slugs disambiguated with a `-2` (then `-3`, etc.) suffix.
      */
    final case class Heading(level: Int, text: String, slug: String) derives CanEqual

end DocsMarkdown
