package kyo.website

import kyo.*

class WebsiteVersionTest extends Test:

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

end WebsiteVersionTest
