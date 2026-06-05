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

    "render contains at least one @media block with docs 2-pane rule and a :hover pseudo-state rule (INV-012)" in {
        val css = WebsiteStyles.sheet.render
        assert(css.contains("@media"))
        // The docs 2-pane row rule: .docs-shell inside the @media (min-width: 1024px) block.
        // renderSheet emits "@media (min-width: 1024px) {\n.docs-shell { ... }\n}\n"
        assert(
            css.contains("@media (min-width: 1024px) {"),
            "must have @media (min-width: 1024px) breakpoint for the docs 2-pane row"
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

    "the .promise gradient interpolates in oklch with several intermediate stops (anti-banding)" in {
        val css = WebsiteStyles.sheet.render
        val idx = css.indexOf(".promise")
        assert(idx >= 0, "must have a .promise rule")
        // The rule body runs to its closing brace; scan only that span.
        val body = css.substring(idx, css.indexOf('}', idx))
        // CSS spec order: the color-interpolation-method follows the direction (`to bottom in oklch`),
        // the reversed `in oklch, to bottom` form is rejected by Chrome and renders as `none`.
        assert(
            body.contains("linear-gradient(to bottom in oklch,"),
            s"promise gradient must interpolate in oklch with the direction first: $body"
        )
        // Several stops: the old gradient had 2 (one comma between stops). The anti-banding fix adds
        // intermediate stops, so the gradient now carries at least 4 color stops (>= 4 `%` markers).
        val stopCount = body.split('%').length - 1
        assert(stopCount >= 4, s"promise gradient must carry several stops to break up banding, found $stopCount: $body")
    }

    "display headings carry text-wrap: balance and body carries text-wrap: pretty" in {
        val css = WebsiteStyles.sheet.render
        assert(css.contains("text-wrap: balance"), "a display heading must use text-wrap: balance")
        assert(css.contains("text-wrap: pretty"), "body prose must use text-wrap: pretty")
    }

    "the terminal CTA band trims the generic band padding (over-tall CTA fix)" in {
        val css = WebsiteStyles.sheet.render
        val idx = css.indexOf(".cta-band")
        assert(idx >= 0, "must have a .cta-band rule")
        val body = css.substring(idx, css.indexOf('}', idx))
        assert(body.contains("padding: 72px 0 72px 0"), s"cta-band must carry the tighter balanced padding: $body")
    }

end WebsiteStylesTest
