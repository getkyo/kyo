package kyo

import kyo.internal.RegionMarker

/** The comment-marker byte format is a cross-implementation contract (Scala + inline LiveView JS);
  * these tests pin it. Escaping is load-bearing: Foreach keys are arbitrary user strings, and a raw
  * "-->" inside a path would terminate the marker comment mid-path.
  */
class RegionMarkerTest extends kyo.test.Test[Any]:

    "open/close format for a plain path" in {
        assert(RegionMarker.open(Seq("0", "1", "a")) == "<!--kyo:0.1.a-->")
        assert(RegionMarker.close(Seq("0", "1", "a")) == "<!--/kyo:0.1.a-->")
    }

    "flags ride the open marker only" in {
        assert(RegionMarker.open(Seq("2"), mount = true) == "<!--kyo:2 m-->")
        assert(RegionMarker.open(Seq("2"), slot = true) == "<!--kyo:2 s-->")
        assert(RegionMarker.open(Seq("2"), mount = true, slot = true) == "<!--kyo:2 m s-->")
    }

    "hostile Foreach keys cannot terminate the comment" in {
        val key  = "Track:abc-->def --!> g-h"
        val open = RegionMarker.open(Seq("0", key))
        // The only "-->" in the marker is its own terminator at the very end.
        assert(open.indexOf("-->") == open.length - 3)
        // Both comment terminators ("-->", "--!>") require "--", so the DATA must contain none.
        val data = open.stripPrefix("<!--").stripSuffix("-->")
        assert(!data.contains("--"))
    }

    "escape/unescape round-trips arbitrary keys" in {
        val keys = List("plain", "a-b c%d", "-->", "%2D", "  --  ", "100% -done-", "$r")
        assert(keys.forall(k => RegionMarker.unescape(RegionMarker.escape(k)) == k))
    }

    "parse reads back open markers with flags" in {
        val data = RegionMarker.openData("0.a-b c", mount = true, slot = true)
        assert(RegionMarker.parse(data) == Present(RegionMarker.Parsed("0.a-b c", isClose = false, mount = true, slot = true)))
    }

    "parse reads back close markers" in {
        val parsed = RegionMarker.parse(RegionMarker.closeData("0.x y"))
        assert(parsed == Present(RegionMarker.Parsed("0.x y", isClose = true, mount = false, slot = false)))
    }

    "parse ignores non-kyo comments" in {
        assert(RegionMarker.parse("just a comment").isEmpty)
        assert(RegionMarker.parse(" kyo:leading-space").isEmpty)
        assert(RegionMarker.parse("").isEmpty)
    }

end RegionMarkerTest
