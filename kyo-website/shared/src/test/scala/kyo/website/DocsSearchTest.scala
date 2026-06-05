package kyo.website

import kyo.*

class DocsSearchTest extends Test:

    private def mkModule(slug: String, group: String, title: String, readme: String): WebsiteModule =
        WebsiteModule(slug, group, title, readme, WebsiteModule.Platforms(true, true, true))

    private def mkContent(groups: Chunk[WebsiteContent.Group]): WebsiteContent =
        WebsiteContent(intro = "", groups = groups, version = WebsiteVersion("latest", "latest", true))

    // index has one entry per module
    "index has one entry per module" in run {
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

    // filter matches title substring
    "filter matches title substring" in run {
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

    // filter ranks title before text hits
    "filter ranks title match before text-only match" in run {
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

    // empty query yields no hits
    "empty query yields no hits" in run {
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

    // filter searches stripped plaintext, not code
    "filter does not match terms inside fenced code blocks" in run {
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

    // within the title band, exact title match ranks above prefix title match
    "within the title band an exact match ranks above a prefix match" in run {
        val modules = Chunk(
            mkModule("kyo-core", "Effects", "kyo-core", ""),
            mkModule("kyo", "Foundation", "kyo", "")
        )
        val idx  = DocsSearch.headingIndex("latest", modules, _ => Chunk.empty)
        val hits = DocsSearch.filter(idx, "kyo")
        assert(hits.size == 2, s"Both titles match 'kyo', got: $hits")
        // "kyo" is an exact match (matchClass 3); "kyo-core" is a prefix match (matchClass 2)
        assert(hits(0).slug == "kyo", s"Exact match must rank first, got: ${hits(0).slug}")
        assert(hits(1).slug == "kyo-core", s"Prefix match must rank second, got: ${hits(1).slug}")
    }

    // within the heading band, a higher matchClass heading hit ranks above a lower one
    // (levelWeight=0 because Heading carries no level; ordering is matchClass-driven, not level-driven)
    "within the heading band higher matchClass ranks above lower matchClass" in run {
        val modules = Chunk(mkModule("kyo-core", "Effects", "kyo-core", ""))
        val headings = Map(
            "kyo-core" -> Chunk(
                // "fibers and forks" is a prefix match on query "fibers" (matchClass 2)
                DocsSearch.Heading("fibers and forks", "fibers-and-forks"),
                // "fibers" is an exact match on query "fibers" (matchClass 3); placed second in doc order
                DocsSearch.Heading("fibers", "fibers")
            )
        )
        val idx  = DocsSearch.headingIndex("latest", modules, s => headings.getOrElse(s, Chunk.empty))
        val hits = DocsSearch.filter(idx, "fibers")
        assert(hits.size == 2, s"Both headings match 'fibers', got: $hits")
        // "fibers" is an exact match; "fibers and forks" is a prefix match. Exact ranks first
        // even though "fibers and forks" appears first in document order.
        assert(hits(0).sub == Present("fibers"), s"Exact heading match must rank first, got: ${hits(0).sub}")
        assert(hits(1).sub == Present("fibers and forks"), s"Prefix heading match must rank second, got: ${hits(1).sub}")
    }

    // Leaf 10: per-heading hits rank above prose-fallback hits in the heading band, regardless of
    // matchClass. The filter uses an isProse discriminator (0 for per-heading, 1 for prose-fallback)
    // as the highest-priority component of the heading-band sort key, so ALL per-heading hits sort
    // before ALL prose-fallback hits. This property is independent of matchClass and document order.
    //
    // Fixture: the prose entry (kyo-beta, idx 0) is placed FIRST in document order AND given an exact
    // match (matchClass 3) on query "fibers". The per-heading entry (kyo-alpha, idx 1) is placed
    // SECOND in document order AND its heading "fibers overview" only prefix-matches "fibers"
    // (matchClass 2). Without the isProse tier, the uniform sort key (-matchClass, ..., entryIndex, ...)
    // would produce [kyo-beta (class 3), kyo-alpha (class 2)] because -3 < -2. With the isProse tier
    // the key is (isProse, -matchClass, ...) so kyo-alpha (isProse=0) sorts before kyo-beta
    // (isProse=1) regardless of matchClass. The assertion [kyo-alpha, kyo-beta] FAILS under the old
    // uniform-key filter and PASSES only with the isProse tier.
    "heading-band: per-heading hit ranks above prose-fallback hit regardless of matchClass" in run {
        // kyo-beta (idx 0): no headings; text "fibers" matches query "fibers" exactly (matchClass 3).
        // kyo-alpha (idx 1): heading "fibers overview" prefix-matches query "fibers" (matchClass 2).
        // Neither title matches "fibers", so both entries go to the heading/text band.
        // Without isProse tier: key = (-matchClass, ...) => kyo-beta (-3) sorts before kyo-alpha (-2).
        // With isProse tier:    key = (isProse, -matchClass, ...) => kyo-alpha (0, -2, ...) sorts before
        //                       kyo-beta (1, -3, ...) because 0 < 1.
        val betaEntry = DocsSearch.Entry("kyo-beta", "kyo-beta", "Foundation", "latest", "fibers", Chunk.empty)
        val alphaEntry =
            DocsSearch.Entry(
                "kyo-alpha",
                "kyo-alpha",
                "Effects",
                "latest",
                "fibers overview",
                Chunk(DocsSearch.Heading("fibers overview", "fibers-overview"))
            )
        // kyo-beta is document-index 0 (earlier), kyo-alpha is document-index 1 (later).
        val idx  = DocsSearch.Index(Chunk(betaEntry, alphaEntry))
        val hits = DocsSearch.filter(idx, "fibers")
        assert(hits.size == 2, s"Both should match 'fibers', got: $hits")
        // kyo-alpha (per-heading, matchClass 2, idx 1) must rank BEFORE kyo-beta (prose, matchClass 3, idx 0).
        // This order is WRONG under uniform -matchClass key (3 > 2) and WRONG under document order (beta=0 < alpha=1).
        // Only the isProse tier (0 < 1) produces this order. Fails under the old filter.
        assert(
            hits(0).slug == "kyo-alpha",
            s"Per-heading hit must rank first despite lower matchClass and higher doc index, got: ${hits(0).slug}"
        )
        assert(hits(0).sub == Present("fibers overview"), s"kyo-alpha must carry heading sub-label, got: ${hits(0).sub}")
        assert(hits(1).slug == "kyo-beta", s"Prose-fallback hit must rank last, got: ${hits(1).slug}")
        assert(hits(1).sub == Absent, s"kyo-beta prose hit must have no sub-label, got: ${hits(1).sub}")
    }

    // filter is deterministic (same input produces same output)
    "filter is deterministic" in run {
        val modules = Chunk(
            mkModule("kyo-core", "Effects", "kyo-core", ""),
            mkModule("kyo-data", "Foundation", "kyo-data", ""),
            mkModule("kyo-stream", "Apps", "kyo-stream", "")
        )
        val headings = Map(
            "kyo-core" -> Chunk(DocsSearch.Heading("kyo streaming", "kyo-streaming")),
            "kyo-data" -> Chunk.empty
        )
        val idx   = DocsSearch.headingIndex("latest", modules, s => headings.getOrElse(s, Chunk.empty))
        val hits1 = DocsSearch.filter(idx, "kyo")
        val hits2 = DocsSearch.filter(idx, "kyo")
        assert(hits1 == hits2, s"filter must be deterministic: first call $hits1, second call $hits2")
        assert(hits1.nonEmpty, "filter must yield at least one hit for query 'kyo'")
    }

end DocsSearchTest
