// flow-allow: PUBLIC page wrapper
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

    /** Per-route configuration for the page wrapper. */
    final case class Options(
        title: String,
        description: String,
        canonical: String,
        bundleHref: String,
        bootScenario: String
    ) derives CanEqual

    /** Wraps `view` in a complete HTML document and returns a stream of rendered HTML.
      *
      * Delegates to `UI.runRenderPage` with a `UI.PageHead` built from `opts`. The caller's
      * `view` is wrapped in a top-level `div` carrying `data-boot-scenario` so the bundle entry
      * can identify which app to mount. Take the first emission for a one-shot SSG snapshot.
      *
      * Effect row: `Stream[String, Async]` (same as `UI.runRenderPage`).
      */
    def wrap(opts: Options)(view: UI)(using Frame): Stream[String, Async] =
        UI.runRenderPage(pageHead(opts))(withBootHook(opts.bootScenario, view))

    // ---- Private helpers ----

    private def pageHead(opts: Options)(using Frame): UI.PageHead =
        UI.PageHead(
            title = opts.title,
            meta = Seq(
                "description"    -> opts.description,
                "og:title"       -> opts.title,
                "og:description" -> opts.description,
                "og:type"        -> "website"
            ) ++ (if opts.canonical.nonEmpty then Seq("og:url" -> opts.canonical) else Seq.empty),
            links = (
                Seq("canonical" -> opts.canonical) ++
                    fontLinks ++
                    (if opts.canonical.nonEmpty then Seq("alternate" -> opts.canonical) else Seq.empty)
            ).filter(_._2.nonEmpty),
            css = UI.stylesheetCss(WebsiteStyles.sheet),
            moduleScript = validBundleHref(opts.bundleHref) match
                case s if s.nonEmpty => Present(s)
                case _               => Absent
        )

    private def withBootHook(scenario: String, view: UI)(using Frame): UI =
        UI.div.data("boot-scenario", validBootScenario(scenario))(view)

    private def validBundleHref(s: String): String =
        // Reject javascript: scheme and other potentially unsafe hrefs; fall back to main.js
        if s.isEmpty then ""
        else if s.trim.toLowerCase.startsWith("javascript:") then "main.js"
        else s

    private def validBootScenario(s: String): String =
        s match
            case "landing" | "docs" => s
            case _                  => "landing"

    /** Google Fonts preconnect + stylesheet links for Newsreader, Inter, JetBrains Mono. */
    private def fontLinks: Seq[(String, String)] = Seq(
        "preconnect" -> "https://fonts.googleapis.com",
        "preconnect" -> "https://fonts.gstatic.com",
        "stylesheet" -> "https://fonts.googleapis.com/css2?family=Newsreader:ital,opsz,wght@0,6..72,400;0,6..72,500;0,6..72,600;1,6..72,400&family=Inter:wght@400;500;600;700&family=JetBrains+Mono:wght@400;500&display=swap"
    )

end WebsitePage
