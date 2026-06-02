package kyo.website

import kyo.*

class DocsSearchTest extends Test:

    private def mkModule(slug: String, group: String, title: String, readme: String): WebsiteModule =
        WebsiteModule(slug, group, title, readme, WebsiteModule.Platforms(true, true, true))

    private def mkContent(groups: Chunk[WebsiteContent.Group]): WebsiteContent =
        WebsiteContent(intro = "", groups = groups, version = WebsiteVersion("latest", "latest", true))

    // Leaf 14: index has one entry per module
    "index has one entry per module (leaf 14)" in run {
        val content = mkContent(Chunk(
            WebsiteContent.Group(
                "Foundation",
                Chunk(
                    mkModule("kyo-data", "Foundation", "kyo-data", "Data module readme"),
                    mkModule("kyo-kernel", "Foundation", "kyo-kernel", "Kernel readme")
                )
            ),
            WebsiteContent.Group(
                "Effects",
                Chunk(
                    mkModule("kyo-core", "Effects", "kyo-core", "Core readme"),
                    mkModule("kyo-prelude", "Effects", "kyo-prelude", "Prelude readme"),
                    mkModule("kyo-combinators", "Effects", "kyo-combinators", "Combinators readme")
                )
            )
        ))
        val idx = DocsSearch.index(content)
        assert(idx.entries.size == 5, s"Expected 5 entries, got ${idx.entries.size}")
        assert(idx.entries(0).slug == "kyo-data", s"First entry slug: ${idx.entries(0).slug}")
        assert(idx.entries(0).title == "kyo-data", s"First entry title: ${idx.entries(0).title}")
        assert(idx.entries(0).group == "Foundation", s"First entry group: ${idx.entries(0).group}")
        assert(idx.entries(4).slug == "kyo-combinators", s"Last entry slug: ${idx.entries(4).slug}")
    }

    // Leaf 15: filter matches title substring
    "filter matches title substring (leaf 15)" in run {
        val content = mkContent(Chunk(
            WebsiteContent.Group(
                "Foundation",
                Chunk(
                    mkModule("kyo-core", "Foundation", "kyo-core", "The core effects module"),
                    mkModule("kyo-prelude", "Foundation", "kyo-prelude", "The prelude module")
                )
            )
        ))
        val idx  = DocsSearch.index(content)
        val hits = DocsSearch.filter(idx, "core")
        assert(hits.size == 1, s"Expected 1 hit for 'core', got: $hits")
        assert(hits(0).slug == "kyo-core", s"Expected kyo-core, got: ${hits(0).slug}")
        assert(hits(0).title == "kyo-core", s"Expected kyo-core title, got: ${hits(0).title}")
    }

    // Leaf 16: filter ranks title before text hits
    "filter ranks title match before text-only match (leaf 16)" in run {
        val content = mkContent(Chunk(
            WebsiteContent.Group(
                "Foundation",
                Chunk(
                    // This module has "alpha" only in body text
                    mkModule("kyo-beta", "Foundation", "kyo-beta", "This module provides alpha processing"),
                    // This module has "alpha" in title
                    mkModule("kyo-alpha", "Foundation", "kyo-alpha", "A module for streaming")
                )
            )
        ))
        val idx  = DocsSearch.index(content)
        val hits = DocsSearch.filter(idx, "alpha")
        assert(hits.size == 2, s"Expected 2 hits for 'alpha', got: $hits")
        // Title hit (kyo-alpha) should come first
        assert(hits(0).slug == "kyo-alpha", s"Title match should come first, got: ${hits(0).slug}")
        assert(hits(1).slug == "kyo-beta", s"Text-only match should come second, got: ${hits(1).slug}")
    }

    // Leaf 17: empty query yields no hits
    "empty query yields no hits (leaf 17)" in run {
        val content = mkContent(Chunk(
            WebsiteContent.Group(
                "Foundation",
                Chunk(mkModule("kyo-core", "Foundation", "kyo-core", "Core readme"))
            )
        ))
        val idx   = DocsSearch.index(content)
        val empty = DocsSearch.filter(idx, "")
        val blank = DocsSearch.filter(idx, "   ")
        assert(empty == Chunk.empty, s"Empty query should yield no hits, got: $empty")
        assert(blank == Chunk.empty, s"Blank query should yield no hits, got: $blank")
    }

    // Leaf 18: filter searches stripped plaintext, not code
    "filter does not match terms inside fenced code blocks (leaf 18)" in run {
        val codeOnlyReadme =
            """Some prose about effects.
              |
              |```scala
              |val secretKeyword = "only-in-code"
              |```
              |
              |More prose here.
              |""".stripMargin
        val content = mkContent(Chunk(
            WebsiteContent.Group(
                "Foundation",
                Chunk(mkModule("kyo-core", "Foundation", "kyo-core", codeOnlyReadme))
            )
        ))
        val idx  = DocsSearch.index(content)
        val hits = DocsSearch.filter(idx, "secretKeyword")
        assert(hits.isEmpty, s"Code-only term should not match, got: $hits")
        // But prose does match
        val proseHits = DocsSearch.filter(idx, "effects")
        assert(proseHits.size == 1, s"Prose term should match, got: $proseHits")
    }

end DocsSearchTest
