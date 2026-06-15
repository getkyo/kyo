package kyo.website

import kyo.*

class WebsiteVersionTest extends WebsiteTest:

    "equality and CanEqual" - {
        "two identical WebsiteVersion values are equal" in {
            val v1 = WebsiteVersion("v1.0.0", "1.0.0", true)
            val v2 = WebsiteVersion("v1.0.0", "1.0.0", true)
            assert(v1 == v2)
        }

        "a version with latest=false is not equal to latest=true" in {
            val v1 = WebsiteVersion("v1.0.0", "1.0.0", true)
            val v2 = WebsiteVersion("v1.0.0", "1.0.0", false)
            assert(v1 != v2)
        }

        "derives CanEqual permits == comparison at compile time" in {
            val v = WebsiteVersion("v0.9.3", "0.9.3", false)
            assert(v == WebsiteVersion("v0.9.3", "0.9.3", false))
        }
    }

    "parse (semantic version)" - {
        "parses vMAJOR.MINOR.PATCH" in {
            assert(WebsiteVersion.parse("v0.19.0") == Present(WebsiteVersion.Parsed(0, 19, 0, Absent)))
        }

        "parses a pre-release suffix after the first dash" in {
            assert(WebsiteVersion.parse("v0.19.0-RC1") == Present(WebsiteVersion.Parsed(0, 19, 0, Present("RC1"))))
        }

        "defaults missing minor/patch to 0 (v1.0-RC1)" in {
            assert(WebsiteVersion.parse("v1.0-RC1") == Present(WebsiteVersion.Parsed(1, 0, 0, Present("RC1"))))
        }

        "is total: a non-version tag yields Absent, never throws" in {
            assert(WebsiteVersion.parse("latest") == Absent)
            assert(WebsiteVersion.parse("v") == Absent)
            assert(WebsiteVersion.parse("vX.Y.Z") == Absent)
            assert(WebsiteVersion.parse("1.2.3") == Absent)
        }
    }

    "tagOrdering (semantic, not lexicographic)" - {
        "orders by numeric components, not string compare (v0.9.3 < v0.16.2 < v0.19.0)" in {
            val sorted = Seq("v0.19.0", "v0.9.3", "v0.16.2").sorted(using WebsiteVersion.tagOrdering)
            assert(sorted == Seq("v0.9.3", "v0.16.2", "v0.19.0"))
        }

        "a pre-release precedes the stable release of the same triple" in {
            assert(WebsiteVersion.tagOrdering.compare("v0.19.0-RC1", "v0.19.0") < 0)
            assert(WebsiteVersion.tagOrdering.lt("v0.19.0-RC1", "v0.19.0"))
        }

        "the real kyo tag set's max stable is v0.19.0, not v0.9.3" in {
            val tags = Seq("v0.9.3", "v0.16.2", "v0.18.0", "v0.19.0", "v0.19.0-RC1")
            assert(tags.max(using WebsiteVersion.tagOrdering) == "v0.19.0")
        }

        "unparseable tags sort before all parseable tags (deterministic, never selected as latest)" in {
            val sorted = Seq("v0.1.0", "latest", "v0.2.0", "garbage").sorted(using WebsiteVersion.tagOrdering)
            assert(sorted == Seq("garbage", "latest", "v0.1.0", "v0.2.0"))
        }
    }

    "pickLatestByTimestamp (git creation date, injected map)" - {
        "a NEWER pre-release wins over an OLDER stable release" in {
            // The core regression: v1.0.0-RC2 was cut after v0.19.0, so by git date it is latest even
            // though v0.19.0 is the higher STABLE release under tagOrdering.
            val ts   = Map("v0.19.0" -> 1747031034L, "v1.0.0-RC2" -> 1778509134L)
            val tags = Chunk("v0.19.0", "v1.0.0-RC2")
            assert(WebsiteVersion.pickLatestByTimestamp(tags, ts) == Present("v1.0.0-RC2"))
        }

        "returns Absent for an empty tag set" in {
            assert(WebsiteVersion.pickLatestByTimestamp(Chunk.empty, Map.empty) == Absent)
        }

        "ties on timestamp break by semantic version (higher tag wins)" in {
            val ts = Map("v1.0.0" -> 100L, "v1.0.1" -> 100L)
            assert(WebsiteVersion.pickLatestByTimestamp(Chunk("v1.0.0", "v1.0.1"), ts) == Present("v1.0.1"))
        }

        "a tag absent from the map is treated as oldest, so a dated tag wins" in {
            val ts = Map("v0.1.0" -> 50L)
            assert(WebsiteVersion.pickLatestByTimestamp(Chunk("v0.1.0", "v9.9.9"), ts) == Present("v0.1.0"))
        }

        "with no timestamps at all, falls back to the semantic max" in {
            // Empty map: every tag is Long.MinValue, so the semantic tiebreak picks the higher version.
            assert(WebsiteVersion.pickLatestByTimestamp(Chunk("v0.1.0", "v0.2.0"), Map.empty) == Present("v0.2.0"))
        }
    }

end WebsiteVersionTest
