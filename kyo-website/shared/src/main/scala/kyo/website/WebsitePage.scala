// PUBLIC page wrapper
package kyo.website

import kyo.*

/** Full-page wrapper for every kyo website route.
  *
  * `wrap` is a thin caller of `UI.runRenderPage`: it maps `Options` to a `UI.PageHead`,
  * wraps the caller's `UI` in a boot-data container, and returns the `Stream[String, Async]`
  * of a complete static HTML document. The head carries the full `WebsiteStyles.sheet`
  * rendered to CSS (INV-012), the Google Fonts preconnect and stylesheet links, and an
  * optional Scala.js ESModule bundle reference.
  *
  * `WebsitePage` is shared (JVM + JS + Native) because `UI.runRenderPage` is shared and
  * `WebsiteStyles.sheet` is a pure value; the bundle entry point is `Maybe[String]` and
  * is typically set only during SSG (JVM) to link the compiled JS file.
  */
object WebsitePage:

    /** Per-route configuration for the page wrapper.
      *
      * `jsonLd` is the schema.org structured-data JSON for this page (SEO-5). When non-empty it is
      * injected as a `<script type="application/ld+json">` block at the end of the `<head>`. Empty
      * (the default) emits no JSON-LD block, so existing call sites are unaffected. `noindex`, when
      * true, emits `<meta name="robots" content="noindex">` so thin pages (the empty docs intro,
      * DECISION-SEO-B) are excluded from search indexes while remaining reachable.
      */
    final case class Options(
        title: String,
        description: String,
        canonical: String,
        bundleHref: String,
        jsonLd: String = "",
        noindex: Boolean = false
    ) derives CanEqual

    /** Wraps `view` in a complete HTML document and returns a stream of rendered HTML.
      *
      * Delegates to `UI.runRenderPage` with a `UI.PageHead` built from `opts`. The caller's `view`
      * (the `SiteApp` shell) is the page root directly: there is no boot-hook wrapper `div`
      * anymore (G3), since the bundle routes on `window.location` rather than a
      * `data-boot-scenario` attribute. Take the first emission for a one-shot SSG snapshot.
      *
      * Effect row: `Stream[String, Async]` (same as `UI.runRenderPage`).
      */
    def wrap(opts: Options)(view: UI)(using Frame): Stream[String, Async] =
        UI.runRenderPage(pageHead(opts))(view)
            .map(html => if opts.jsonLd.nonEmpty then injectJsonLd(html, opts.jsonLd) else html)

    // ---- Private helpers ----

    private def pageHead(opts: Options)(using Frame): UI.PageHead =
        UI.PageHead(
            title = opts.title,
            // AF-6: the stray `rel="alternate"` link is removed; it duplicated the canonical href
            // (same URL, no alternate hreflang/format) and served no purpose.
            meta = (
                Seq(
                    "description"    -> opts.description,
                    "og:title"       -> opts.title,
                    "og:description" -> opts.description,
                    "og:type"        -> "website"
                ) ++ (if opts.canonical.nonEmpty then Seq("og:url" -> opts.canonical) else Seq.empty)
                    ++ (if opts.noindex then Seq("robots" -> "noindex") else Seq.empty)
            ),
            links = (
                Seq("canonical" -> opts.canonical) ++
                    fontLinks
            ).filter(_._2.nonEmpty),
            css = UI.stylesheetCss(WebsiteStyles.sheet),
            moduleScript = validBundleHref(opts.bundleHref) match
                case s if s.nonEmpty => Present(s)
                case _               => Absent
        )

    /** Inject a `<script type="application/ld+json">` block carrying `jsonLd` immediately before the
      * `</head>` of the rendered document (SEO-5). The JSON has its angle brackets escaped to their
      * JSON unicode escapes (`escScript`) so a `</script>` substring in any field cannot close the
      * element early. This is a string-level page-wrap concern (the kyo-ui `PageHead` has no raw-script
      * slot), mirroring `WebsiteGenerator.injectIslands` at the head level instead of the body level.
      */
    private def injectJsonLd(html: String, jsonLd: String): String =
        val block  = s"""<script type="application/ld+json">${escScript(jsonLd)}</script>"""
        val marker = "</head>"
        val idx    = html.indexOf(marker)
        if idx < 0 then html + block
        else html.substring(0, idx) + block + html.substring(idx)
    end injectJsonLd

    // Angle-bracket escape for JSON embedded in a <script> element. Mirrors
    // WebsiteGenerator.escScript: a literal "</script>" in any field would otherwise close the
    // element early, so "<"/">" become their JSON unicode escapes.
    private def escScript(json: String): String =
        json.replace("<", "\\u003c").replace(">", "\\u003e")

    private def validBundleHref(s: String): String =
        // Reject javascript: scheme and other potentially unsafe hrefs; fall back to main.js
        if s.isEmpty then ""
        else if s.trim.toLowerCase.startsWith("javascript:") then "main.js"
        else s

    /** Google Fonts preconnect + stylesheet links for Newsreader, Inter, JetBrains Mono. */
    private def fontLinks: Seq[(String, String)] = Seq(
        "preconnect" -> "https://fonts.googleapis.com",
        "preconnect" -> "https://fonts.gstatic.com",
        "stylesheet" -> "https://fonts.googleapis.com/css2?family=Newsreader:ital,opsz,wght@0,6..72,400;0,6..72,500;0,6..72,600;1,6..72,400&family=Inter:wght@400;500;600;700&family=JetBrains+Mono:wght@400;500&display=swap"
    )

end WebsitePage
