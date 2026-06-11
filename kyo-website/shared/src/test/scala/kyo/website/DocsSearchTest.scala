package kyo.website

import kyo.*

class DocsSearchTest extends WebsiteTest:

    import DocsSearch.Entry
    import DocsSearch.Index
    import DocsSearch.Section

    private def section(heading: String, slug: String, body: String, symbols: String*): Section =
        Section(heading, slug, 2, body, Chunk.from(symbols))

    private def entry(slug: String, title: String, sections: Section*): Entry =
        Entry(slug, title, "Effects", "latest", Chunk.from(sections))

    // ---- tokenization ----

    "wholeTokens splits on non-alphanumerics and lowercases, with NO camelCase split" in {
        assert(DocsSearch.wholeTokens("Abort.run and foldAbort!").toList == List("abort", "run", "and", "foldabort"))
    }

    "subTokens yields the camelCase parts of multi-part identifiers only" in {
        assert(DocsSearch.subTokens("foldAbort plainword").toList == List("fold", "abort"))
        assert(DocsSearch.camelParts("runOrAbort") == List("run", "Or", "Abort"))
        assert(DocsSearch.camelParts("STM") == List("STM"))
    }

    "tokenizeQuery is whole-word: `Abort` -> [abort], `fold abort` -> [fold, abort]" in {
        assert(DocsSearch.tokenizeQuery("Abort").toList == List("abort"))
        assert(DocsSearch.tokenizeQuery("  fold  abort ").toList == List("fold", "abort"))
    }

    // ---- module-title hits (the seed) ----

    "seed builds title-only entries; a module-name query returns the module page" in {
        val modules = Chunk(
            WebsiteModule("kyo-core", "Effects", "kyo-core", "", WebsiteModule.Platforms(true, true, true, true)),
            WebsiteModule("kyo-stream", "Effects", "kyo-stream", "", WebsiteModule.Platforms(true, true, true, true))
        )
        val hits = DocsSearch.filter(DocsSearch.seed("latest", modules), "kyo-stream")
        assert(hits.nonEmpty, "module-name query should match on the seed")
        assert(hits.head.route == "/latest/kyo-stream/", s"top hit should be the module page: ${hits.head}")
        assert(hits.head.sub == Absent, "a module hit carries no section sub-label")
    }

    "blank or whitespace-only query yields no hits" in {
        val idx = Index(Chunk(entry("kyo-core", "kyo-core", section("Channels", "channels", "channels prose"))))
        assert(DocsSearch.filter(idx, "").isEmpty)
        assert(DocsSearch.filter(idx, "   ").isEmpty)
    }

    // ---- section hits resolve to the anchor + sub-label ----

    "a heading-word query returns a section hit with the #anchor route and the heading sub-label" in {
        val idx = Index(Chunk(entry(
            "kyo-core",
            "kyo-core",
            section("Channels and queues", "channels-and-queues", "buffered channels and bounded queues")
        )))
        val hits = DocsSearch.filter(idx, "channels")
        assert(hits.size == 1, s"expected one section hit: $hits")
        assert(hits.head.route == "/latest/kyo-core/#channels-and-queues", s"route must carry the anchor: ${hits.head}")
        assert(hits.head.sub == Present("Channels and queues"), s"sub must be the heading: ${hits.head}")
    }

    // ---- THE Abort case: a type name resolves to the section that documents it, not incidental mentions ----

    "a type-name query ranks the section that FEATURES the type above incidental method-name matches" in {
        // The canonical section documents `Abort`: its heading does NOT contain the word, but its body and
        // symbols feature it heavily. The peripheral section is a combinator whose heading is `foldAbort`
        // (so `abort` appears only as a camelCase sub-word) and which mentions bare `Abort` once.
        val canonical = section(
            "Failure and recovery",
            "failure-and-recovery",
            "Declare an Abort[E] in the pending row for typed failure. A computation in Abort completes with " +
                "success, Failure, or Panic. The Abort handler at the boundary decides; Abort.run returns a Result.",
            "Abort",
            "Result",
            "Panic"
        )
        val peripheral = section(
            "foldAbort and foldAbortOrThrow",
            "foldabort",
            "foldAbort folds over the outcome; mapAbort transforms the Abort error type.",
            "foldAbort",
            "mapAbort",
            "Abort"
        )
        val idx  = Index(Chunk(entry("kyo-prelude", "kyo-prelude", canonical), entry("kyo-combinators", "kyo-combinators", peripheral)))
        val hits = DocsSearch.filter(idx, "Abort")
        assert(hits.nonEmpty, "Abort must return hits")
        assert(
            hits.head.route == "/latest/kyo-prelude/#failure-and-recovery",
            s"the canonical Abort section must rank first, got: ${hits.map(_.route).toList}"
        )
    }

    // ---- field boost: a module title outranks an incidental body mention ----

    "an exact module-title match outranks a section that only mentions the term in prose" in {
        val idx = Index(Chunk(
            entry("kyo-stm", "kyo-stm", section("Transactions", "transactions", "software transactional memory")),
            entry("kyo-core", "kyo-core", section("Effects", "effects", "the stm module is referenced here once"))
        ))
        val hits = DocsSearch.filter(idx, "stm")
        assert(hits.head.route == "/latest/kyo-stm/", s"the kyo-stm module page should rank first: ${hits.map(_.route).toList}")
    }

    // ---- prefix / as-you-type ----

    "a partial last word matches by prefix (search-as-you-type)" in {
        val idx  = Index(Chunk(entry("kyo-core", "kyo-core", section("Channels and queues", "channels-and-queues", "buffered channels"))))
        val hits = DocsSearch.filter(idx, "chann")
        assert(hits.nonEmpty, "a prefix of a heading word should still match")
        assert(hits.head.route == "/latest/kyo-core/#channels-and-queues", s"prefix hit route: ${hits.head}")
    }

    // ---- multi-word precision + determinism ----

    "every query word must match somewhere (multi-word precision)" in {
        val idx = Index(Chunk(entry("kyo-core", "kyo-core", section("Channels and queues", "channels-and-queues", "buffered channels"))))
        assert(DocsSearch.filter(idx, "channels queues").nonEmpty, "both words present -> match")
        assert(DocsSearch.filter(idx, "channels zzzznope").isEmpty, "a missing word -> no match")
    }

    "filter is deterministic: the same query yields the same ordered hits" in {
        val idx = Index(Chunk(
            entry("kyo-core", "kyo-core", section("Fibers", "fibers", "lightweight fibers and forks")),
            entry("kyo-stm", "kyo-stm", section("Fibers in STM", "fibers-in-stm", "fibers cooperate with stm"))
        ))
        assert(DocsSearch.filter(idx, "fibers") == DocsSearch.filter(idx, "fibers"))
    }

end DocsSearchTest
