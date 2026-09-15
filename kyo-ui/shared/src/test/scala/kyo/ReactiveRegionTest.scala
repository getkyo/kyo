package kyo

import kyo.internal.ReactiveRegion

class ReactiveRegionTest extends kyo.test.Test[Any]:

    "HTML regions encode path segments with UTF-16 lengths and code units" in {
        val region = ReactiveRegion.from(Seq("", "😀", "\"--\u0000"), svgContext = false)
        assert(region == ReactiveRegion.HtmlRange("r0000000000000002d83dde00000000040022002d002d0000"))
    }

    "path segment boundaries remain distinct" in {
        val joined = ReactiveRegion.from(Seq("ab"), svgContext = false)
        val split  = ReactiveRegion.from(Seq("a", "b"), svgContext = false)
        val empty  = ReactiveRegion.from(Seq("", "ab"), svgContext = false)
        assert(joined != split)
        assert(joined != empty)
        assert(split != empty)
    }

    "transparent nesting is tagged separately from application path segments" in {
        val root     = ReactiveRegion.RegionIdentity.root(Seq("nested"))
        val direct   = root.transparent
        val deeper   = direct.transparent
        val emptyKey = root.child("")
        val ids      = Seq(root, direct, deeper, emptyKey).map(ReactiveRegion.htmlId)
        val base     = "r00000006006e00650073007400650064"
        assert(ids == Seq(base, s"${base}n00000001", s"${base}n00000002", s"${base}00000000"))
        assert(ids.distinct.size == ids.size)
    }

    "region id validation accepts encoded paths and tagged nesting only" in {
        val valid = Seq(
            "r",
            "r00000000",
            "r000000010061",
            "r000000010061n00000001"
        )
        val invalid = Seq("", "r1", "r000000010061n00000000", "r000000010061n1", "r00000001006g")
        assert(valid.forall(ReactiveRegion.isValidHtmlId))
        assert(invalid.forall(id => !ReactiveRegion.isValidHtmlId(id)))
    }

    "SVG regions retain their element path" in {
        assert(ReactiveRegion.from(Seq("0", "key"), svgContext = true) == ReactiveRegion.SvgElement(Seq("0", "key")))
    }

    "table content reports rows only when an actual row is present" in {
        import ReactiveRegion.TableContent
        assert(ReactiveRegion.tableContent(UI.tr(UI.td("row"))) == TableContent.Rows)
        assert(ReactiveRegion.tableContent(UI.tbody(UI.tr())) == TableContent.AuthoredSections)
        assert(ReactiveRegion.tableContent(UI.fragment()) == TableContent.Other)
        assert(ReactiveRegion.tableContent(UI.div("not a row")) == TableContent.Other)
        val transparent = Signal.initConst(UI.tr()).render(identity)
        assert(ReactiveRegion.tableContent(transparent) == TableContent.Transparent)
        assert(
            ReactiveRegion.tableContent(Seq(UI.tbody(UI.tr()), transparent)) == TableContent.AuthoredSections
        )
    }

end ReactiveRegionTest
