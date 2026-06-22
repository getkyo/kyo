package kyo.website

import kyo.*

/** Pure unit tests for the nav router's route classification (`WebsiteBundleMain.classifyRoute`).
  *
  * These leaves exercise the actual classifier the nav fiber dispatches on, with no DOM, no fetch
  * stub, and no `Async`. They lock the unknown multi-segment slug and island version tag in
  * `knownPrefixes` robustness fixes against regression.
  *
  * Hardening: `knownPrefixes` and `knownSlugs` are derived via the real
  * `WebsiteBundleMain.knownPrefixesOf` / `knownSlugsOf` helpers rather than being reconstructed
  * locally. A regression in either derivation (e.g. removing the `+ island.content.version.tag`
  * seed) directly fails the tests that call those helpers.
  */
class RouterRobustnessTest extends kyo.test.Test[Any]:

    private def segmentsOf(route: String): Array[String] =
        route.split('/').filter(_.nonEmpty)

    // A minimal island seeded with two known module slugs and a specific version tag.
    private val islandVersion = WebsiteVersion("v1.0.0-RC2", "1.0.0-RC2", false)
    private val islandModules = Chunk(
        WebsiteModule("kyo-core", "Foundation", "kyo-core", "", WebsiteModule.Platforms(true, true, true, true)),
        WebsiteModule("kyo-data", "Foundation", "kyo-data", "", WebsiteModule.Platforms(true, true, true, true))
    )
    private val islandContent = WebsiteContent(
        "intro",
        Chunk(WebsiteContent.Group("Foundation", islandModules)),
        islandVersion
    )
    private val island = DocsClient.DocsIsland(islandContent, Chunk.empty, "", Chunk.empty, Map.empty)

    // A versions list containing the island's own tag (the normal case when the versions island is
    // present in the DOM).
    private val versionsWithTag: Chunk[WebsiteVersion] =
        Chunk(islandVersion)

    // Derive the sets via the REAL helpers: any regression in `knownPrefixesOf` or
    // `knownSlugsOf` (e.g. removing the `+ island.content.version.tag` seed) directly fails the
    // tests below.
    private val knownPrefixes: Set[String] = WebsiteBundleMain.knownPrefixesOf(island, versionsWithTag)
    private val knownSlugs: Set[String]    = WebsiteBundleMain.knownSlugsOf(island)

    // A multi-segment route whose last segment is NOT a known module slug must classify as OffTree
    // (full-navigate to a clean 404), NOT Module (fetch a missing content.md into a broken docs
    // shell). Before the fix, classifyRoute returned Module for ANY >= 2-segment route, so this
    // asserted OffTree and FAILED with RouteKind.Module.
    "unknown multi-segment slug is classified as OffTree, not Module" in {
        val segments = segmentsOf("/latest/does-not-exist/")
        val kind     = WebsiteBundleMain.classifyRoute(segments, knownPrefixes, knownSlugs)
        assert(
            kind == WebsiteBundleMain.RouteKind.OffTree,
            s"unknown slug must land in the OffTree branch, got: $kind"
        )
        assert(
            kind != WebsiteBundleMain.RouteKind.Module,
            s"unknown slug 'does-not-exist' must not match the Module branch, got: $kind"
        )
        succeed
    }

    // A multi-segment route whose last segment IS a known module slug stays Module.
    "known multi-segment slug is classified as Module, not OffTree" in {
        val segments = segmentsOf("/latest/kyo-core/")
        val kind     = WebsiteBundleMain.classifyRoute(segments, knownPrefixes, knownSlugs)
        assert(kind == WebsiteBundleMain.RouteKind.Module, s"known slug must classify as Module, got: $kind")
        succeed
    }

    // The guard keys on the LAST segment, so a known slug under a known version prefix is Module too.
    "known slug under a version prefix is classified as Module (versioned)" in {
        val segments = segmentsOf("/v1.0.0-RC2/kyo-data/")
        val kind     = WebsiteBundleMain.classifyRoute(segments, knownPrefixes, knownSlugs)
        assert(kind == WebsiteBundleMain.RouteKind.Module, s"known versioned slug must classify as Module, got: $kind")
        succeed
    }

    // The root route stays Landing; a known single-segment prefix stays Intro; an unknown single
    // segment stays OffTree. These pin the unchanged branches.
    "root, known-prefix intro, and unknown single segment classify as Landing / Intro / OffTree" in {
        assert(
            WebsiteBundleMain.classifyRoute(segmentsOf("/"), knownPrefixes, knownSlugs) == WebsiteBundleMain.RouteKind.Landing,
            "root must be Landing"
        )
        assert(
            WebsiteBundleMain.classifyRoute(segmentsOf("/latest/"), knownPrefixes, knownSlugs) == WebsiteBundleMain.RouteKind.Intro,
            "/latest/ must be Intro"
        )
        assert(
            WebsiteBundleMain.classifyRoute(
                segmentsOf("/bogus/"),
                knownPrefixes,
                knownSlugs
            ) == WebsiteBundleMain.RouteKind.OffTree,
            "/bogus/ must be OffTree"
        )
        succeed
    }

    // When the versions island is absent the version set is empty, but the seeded docs island's own
    // version tag must still be in knownPrefixes so a nav to /<islandTag>/ classifies as Intro, not
    // OffTree. This exercises `knownPrefixesOf` directly: removing the
    // `+ island.content.version.tag` seed from that helper causes the assertion on
    // `noIslandPrefixes` below to pass but the `Intro` assertion to fail, catching the regression.
    "island version tag is in knownPrefixes even when the versions island is absent" in {
        val islandTag = islandVersion.tag

        // Simulate: versions island absent -> versions = Chunk.empty -> the version set has no tags.
        val emptyVersionsIsland = Chunk.empty[WebsiteVersion]
        val noIslandPrefixes    = WebsiteBundleMain.knownPrefixesOf(island, emptyVersionsIsland)

        // The set must still contain the island's own tag.
        assert(
            noIslandPrefixes.contains(islandTag),
            s"island tag '$islandTag' must be in knownPrefixes even when versions island is absent"
        )
        assert(
            noIslandPrefixes.contains("latest"),
            "knownPrefixes must always contain 'latest'"
        )

        // A nav to /<islandTag>/ classifies as Intro when the island tag is seeded in.
        val kind = WebsiteBundleMain.classifyRoute(segmentsOf(s"/$islandTag/"), noIslandPrefixes, Set.empty)
        assert(
            kind == WebsiteBundleMain.RouteKind.Intro,
            s"/$islandTag/ must classify as Intro when the island tag is in knownPrefixes, got: $kind"
        )

        // Regression oracle: if the seed were absent (pre-fix behaviour), the same nav degrades to
        // OffTree. We verify this by building the pre-fix set explicitly.
        val preFix  = emptyVersionsIsland.toSeq.map(_.tag).toSet + "latest"
        val kindOld = WebsiteBundleMain.classifyRoute(segmentsOf(s"/$islandTag/"), preFix, Set.empty)
        assert(
            kindOld == WebsiteBundleMain.RouteKind.OffTree,
            s"pre-fix (island tag absent), /$islandTag/ degrades to OffTree, got: $kindOld"
        )
        succeed
    }

    // knownSlugsOf must derive slugs from the island's module list, not a hand-coded set.
    // Removing a module from `islandModules` above would fail the positive classification below.
    "knownSlugsOf derives slugs from the real island module list" in {
        val slugs = WebsiteBundleMain.knownSlugsOf(island)
        assert(slugs.contains("kyo-core"), "kyo-core must be in the derived slug set")
        assert(slugs.contains("kyo-data"), "kyo-data must be in the derived slug set")
        assert(!slugs.contains("kyo-stm"), "kyo-stm is not in the island, must not appear in derived slugs")
        // A multi-segment route to a derived slug classifies as Module via the real helper output.
        val kind = WebsiteBundleMain.classifyRoute(segmentsOf("/latest/kyo-data/"), knownPrefixes, slugs)
        assert(kind == WebsiteBundleMain.RouteKind.Module, s"derived slug must classify as Module, got: $kind")
        succeed
    }

end RouterRobustnessTest
