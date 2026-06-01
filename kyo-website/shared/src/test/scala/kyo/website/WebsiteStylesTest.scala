package kyo.website

import kyo.*

class WebsiteStylesTest extends Test:

    "sheet is a Stylesheet value; render is non-empty and contains :root vars and .feat-grid rule (INV-012)" in {
        val sheet = WebsiteStyles.sheet
        assert(sheet.nonEmpty, "sheet must have at least one entry")
        val css = sheet.render
        assert(css.nonEmpty)
        assert(css.contains(":root {"))
        assert(css.contains("--accent"))
        assert(css.contains(".feat-grid"))
    }

    "render contains at least one @media block with docs 3-pane rule and a :hover pseudo-state rule (INV-012)" in {
        val css = WebsiteStyles.sheet.render
        assert(css.contains("@media"))
        // The docs 3-pane grid rule: .docs-shell inside the @media (min-width: 1024px) block.
        // renderSheet emits "@media (min-width: 1024px) {\n.docs-shell { ... }\n}\n"
        assert(
            css.contains("@media (min-width: 1024px) {"),
            "must have @media (min-width: 1024px) breakpoint for the docs 3-pane grid"
        )
        val mediaIdx     = css.indexOf("@media (min-width: 1024px) {")
        val docsShellIdx = css.indexOf(".docs-shell", mediaIdx)
        assert(docsShellIdx > mediaIdx, "docs-shell rule must appear inside the 1024px media block")
        assert(css.contains(":hover"), "must have at least one :hover pseudo-state rule")
    }

    "render contains callout-note, callout-caution, and code highlight token classes (D5 INV-012)" in {
        val css = WebsiteStyles.sheet.render
        assert(css.contains(".callout-note"))
        assert(css.contains(".callout-caution"))
        assert(css.contains(".tok-keyword"))
        assert(css.contains(".tok-string"))
        assert(css.contains(".tok-comment"))
    }

end WebsiteStylesTest
