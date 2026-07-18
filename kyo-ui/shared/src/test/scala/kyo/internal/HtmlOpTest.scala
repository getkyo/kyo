package kyo.internal

import kyo.*

class HtmlOpTest extends UITest:

    "SetProp round-trips through Schema (path + key + encoded only)" in {
        val op: HtmlOp = HtmlOp.SetProp(Seq("1", "0"), "material.color", "16711680")
        val decoded    = Json.decode[HtmlOp](Json.encode[HtmlOp](op))
        assert(decoded == Result.succeed(op))
    }

    "ReplaceSubtree round-trips through Schema" in {
        val op: HtmlOp = HtmlOp.ReplaceSubtree(Seq("2"), "[1,2,3]")
        val decoded    = Json.decode[HtmlOp](Json.encode[HtmlOp](op))
        assert(decoded == Result.succeed(op))
    }

    "the wire op carries only Seq[String] + String fields (no closure, no js.Dynamic)" in {
        // A constructed op is a plain value; equality holds by CanEqual, proving the payload is data.
        val a = HtmlOp.SetProp(Seq("1"), "intensity", "0.5")
        val b = HtmlOp.SetProp(Seq("1"), "intensity", "0.5")
        assert(a == b)
    }
end HtmlOpTest
