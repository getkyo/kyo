package kyo.website

import kyo.*

/** Shared (cross-platform) test for DocsMarkdown.Heading. Verifies that Heading is constructable
  * and reachable from shared sources on both JVM and JS.
  */
class DocsMarkdownHeadingTest extends WebsiteTest:

    // Heading is constructable from shared source; fields are correct
    "Heading reachable from shared" in {
        val h = DocsMarkdown.Heading(2, "Scope", "scope")
        assert(h.level == 2)
        assert(h.text == "Scope")
        assert(h.slug == "scope")
    }

    // CanEqual works; level participates in equality
    "Heading inequality discriminates fields" in {
        val h1 = DocsMarkdown.Heading(1, "A", "a")
        val h2 = DocsMarkdown.Heading(2, "A", "a")
        assert(h1 != h2)
        assert(DocsMarkdown.Heading(2, "Scope", "scope") == DocsMarkdown.Heading(2, "Scope", "scope"))
    }

end DocsMarkdownHeadingTest
