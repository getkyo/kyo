package kyo.website

import kyo.*
import kyo.internal.BaseKyoCoreTest
import kyo.internal.Platform
import org.scalatest.NonImplicitAssertions
import org.scalatest.freespec.AsyncFreeSpec
import scala.concurrent.ExecutionContext

/** Pure unit tests for the nav router's route classification (`WebsiteBundleMain.classifyRoute`).
  *
  * These leaves exercise the actual classifier the nav fiber dispatches on, with no DOM, no fetch
  * stub, and no `Async`. They lock the AF-4 (unknown multi-segment slug) and AF-5 (island version
  * tag in `knownPrefixes`) robustness fixes against regression.
  */
class RouterRobustnessTest extends AsyncFreeSpec with NonImplicitAssertions with BaseKyoCoreTest:

    type Assertion = org.scalatest.Assertion
    def assertionSuccess              = succeed
    def assertionFailure(msg: String) = fail(msg)

    override given executionContext: ExecutionContext = Platform.executionContext

    private def segmentsOf(route: String): Array[String] =
        route.split('/').filter(_.nonEmpty)

    private val knownSlugs: Set[String]    = Set("kyo-core", "kyo-data")
    private val knownPrefixes: Set[String] = Set("latest", "v1.0.0-RC2")

    // AF-4 reproduce: a multi-segment route whose last segment is NOT a known module slug must
    // classify as OffTree (full-navigate to a clean 404), NOT Module (fetch a missing content.md into
    // a broken docs shell). Before the fix, classifyRoute returned Module for ANY >= 2-segment route,
    // so this asserted OffTree and FAILED with RouteKind.Module.
    "unknown multi-segment slug is classified as OffTree, not Module (AF-4 reproduce)" in {
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

    // AF-4 positive: a multi-segment route whose last segment IS a known module slug stays Module.
    "known multi-segment slug is classified as Module, not OffTree (AF-4 positive)" in {
        val segments = segmentsOf("/latest/kyo-core/")
        val kind     = WebsiteBundleMain.classifyRoute(segments, knownPrefixes, knownSlugs)
        assert(kind == WebsiteBundleMain.RouteKind.Module, s"known slug must classify as Module, got: $kind")
        succeed
    }

    // AF-4 positive (versioned prefix): the guard keys on the LAST segment, so a known slug under a
    // known version prefix is Module too.
    "known slug under a version prefix is classified as Module (AF-4 positive, versioned)" in {
        val segments = segmentsOf("/v1.0.0-RC2/kyo-data/")
        val kind     = WebsiteBundleMain.classifyRoute(segments, knownPrefixes, knownSlugs)
        assert(kind == WebsiteBundleMain.RouteKind.Module, s"known versioned slug must classify as Module, got: $kind")
        succeed
    }

    // The root route stays Landing; a known single-segment prefix stays Intro; an unknown single
    // segment stays OffTree. These pin the unchanged branches so the AF-4 guard did not perturb them.
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

    // AF-5: when the versions island is absent the version set is empty, but the seeded docs island's
    // own version tag must still be in knownPrefixes so a nav to /<islandTag>/ classifies as Intro,
    // not OffTree. This mirrors the `+ island.content.version.tag` seeding in WebsiteBundleMain.build.
    "island version tag is in knownPrefixes even when the versions island is absent (AF-5)" in {
        // Simulate: versions island absent -> versions = Chunk.empty -> the version set is empty.
        val versionsFromIsland: Set[String] = Set.empty
        val islandVersionTag                = "v1.0.0-RC2"
        val LatestPrefix                    = "latest"

        // Pre-fix knownPrefixes = Set("latest") would omit the island tag.
        val knownPrefixesOld = versionsFromIsland + LatestPrefix
        assert(!knownPrefixesOld.contains(islandVersionTag), "pre-fix: island tag absent from the version-only set")

        // Post-fix: the island tag is seeded in.
        val knownPrefixesNew = versionsFromIsland + LatestPrefix + islandVersionTag
        assert(knownPrefixesNew.contains(islandVersionTag), "post-fix: island tag must be in knownPrefixes")

        // A nav to /v1.0.0-RC2/ now classifies as Intro, not OffTree.
        val kind = WebsiteBundleMain.classifyRoute(segmentsOf("/v1.0.0-RC2/"), knownPrefixesNew, Set.empty)
        assert(
            kind == WebsiteBundleMain.RouteKind.Intro,
            s"/v1.0.0-RC2/ must classify as Intro when the island tag is in knownPrefixes, got: $kind"
        )
        // Without the island tag (the pre-fix set), the same nav would be OffTree.
        val kindOld = WebsiteBundleMain.classifyRoute(segmentsOf("/v1.0.0-RC2/"), knownPrefixesOld, Set.empty)
        assert(
            kindOld == WebsiteBundleMain.RouteKind.OffTree,
            s"pre-fix (island tag absent), /v1.0.0-RC2/ degrades to OffTree, got: $kindOld"
        )
        succeed
    }

end RouterRobustnessTest
