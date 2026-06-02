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

    // headingIndex: title matches rank before heading matches, and a heading hit carries the
    // #<heading-slug> anchor route plus the heading text as the sub-label.
    "headingIndex ranks title matches before heading matches and builds anchor routes" in run {
        val modules = Chunk(
            mkModule("kyo-core", "Effects", "kyo-core", ""),
            mkModule("kyo-stream", "Effects", "kyo-stream", "")
        )
        val headings = Map(
            // kyo-core has a "stream" heading; "stream" also matches the kyo-stream TITLE.
            "kyo-core"   -> Chunk(DocsSearch.Heading("Streaming results", "streaming-results")),
            "kyo-stream" -> Chunk(DocsSearch.Heading("Backpressure", "backpressure"))
        )
        val idx  = DocsSearch.headingIndex("latest", modules, s => headings.getOrElse(s, Chunk.empty))
        val hits = DocsSearch.filter(idx, "stream")
        assert(hits.size == 2, s"expected a title hit and a heading hit for 'stream', got: $hits")
        // Title hit (kyo-stream) ranks first, with a bare module route and no sub-label.
        assert(hits(0).slug == "kyo-stream", s"title match must rank first, got: ${hits(0).slug}")
        assert(hits(0).route == "/latest/kyo-stream/", s"title hit route: ${hits(0).route}")
        assert(hits(0).sub == Absent, s"title hit must have no sub-label, got: ${hits(0).sub}")
        // Heading hit (kyo-core's "Streaming results") ranks second, with an anchor route + sub-label.
        assert(hits(1).slug == "kyo-core", s"heading match must rank second, got: ${hits(1).slug}")
        assert(hits(1).route == "/latest/kyo-core/#streaming-results", s"heading hit route: ${hits(1).route}")
        assert(hits(1).sub == Present("Streaming results"), s"heading hit sub-label: ${hits(1).sub}")
    }

    // headingIndex matches a heading even when the title does not, with the right prefix in the route.
    "headingIndex matches a heading-only query and respects the prefix" in run {
        val modules  = Chunk(mkModule("kyo-core", "Effects", "kyo-core", ""))
        val headings = Map("kyo-core" -> Chunk(DocsSearch.Heading("Fibers and forks", "fibers-and-forks")))
        val idx      = DocsSearch.headingIndex("v0.9.0", modules, s => headings.getOrElse(s, Chunk.empty))
        val hits     = DocsSearch.filter(idx, "fibers")
        assert(hits.size == 1, s"expected 1 heading hit for 'fibers', got: $hits")
        assert(hits(0).route == "/v0.9.0/kyo-core/#fibers-and-forks", s"prefix must thread into the route: ${hits(0).route}")
        assert(hits(0).sub == Present("Fibers and forks"), s"sub-label: ${hits(0).sub}")
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
