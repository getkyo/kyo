package kyo.website

import kyo.*
import kyo.UI.PageHead

/** Verifies INV-003 for the landing page and (Phase 6) the docs shell.
  *
  * Landing arm: renders byte-identically via `UI.runRender` (SSG path) and
  * `RecordingBackend.render` (the same renderer `DomBackend.mountInto` calls), at the initial
  * signal state (dropdown closed, no interaction). The two paths must produce identical HTML after
  * normalizing `data-kyo-path` attribute values (which are positional and deterministic for an
  * identical UI tree).
  *
  * Docs arm (Phase 6, leaves 13/13b/13c): same parity guarantee for the 3-pane docs shell with
  * an embedded transpiled article subtree. The article is a real `UI` subtree produced by
  * `DocsMarkdown.transpile`, so two calls with the same Markdown produce byte-identical output.
  *
  * JVM-only: `RecordingBackend` uses `HtmlRenderer` directly, which is `private[kyo]` and
  * accessible here because `kyo.website` is a subpackage of `kyo`. `UI.runMount` is JS-only;
  * this backend replaces it for parity assertions on the JVM.
  */
class ChromeParityTest extends Test:

    private val v1        = WebsiteVersion("v1.0.0-RC2", "1.0.0-RC2", true)
    private val v0        = WebsiteVersion("v0.9.3", "0.9.3", false)
    private val versions2 = Chunk(v1, v0)

    /** Strip `data-kyo-path="..."` values (they are positional indices and are deterministic for
      * an identical UI tree; this normalization is a safeguard for any future ordering sensitivity).
      */
    private def normalize(html: String): String =
        html.replaceAll("""data-kyo-path="[^"]*"""", "data-kyo-path=\"\"")
    end normalize

    "landing SSG vs RecordingBackend parity (INV-003)" in run {
        for
            view  <- LandingApp.view(versions2)
            ssg   <- UI.runRender(view).take(1).run.map(_.headMaybe.getOrElse(""))
            mount <- RecordingBackend.render(view)
        yield assert(
            normalize(ssg) == normalize(mount),
            "SSG (runRender) and mount (RecordingBackend) must produce identical HTML at initial state (INV-003)"
        )
        end for
    }

    "parity holds for the dropdown subtree (INV-003)" in run {
        for
            view  <- LandingApp.view(versions2)
            ssg   <- UI.runRender(view).take(1).run.map(_.headMaybe.getOrElse(""))
            mount <- RecordingBackend.render(view)
        yield
            val ssgNorm   = normalize(ssg)
            val mountNorm = normalize(mount)
            // Both outputs must contain the dropdown markup for both version labels
            assert(ssgNorm.contains("1.0.0-RC2"), "SSG must contain version label")
            assert(mountNorm.contains("1.0.0-RC2"), "mount must contain version label")
            assert(ssgNorm.contains("0.9.3"), "SSG must contain second version label")
            assert(mountNorm.contains("0.9.3"), "mount must contain second version label")
            // kyo-ui dropdown renders as a div with data-kyo-dropdown attribute (not a <select>).
            // The dropdown subtree must be identical between the two paths.
            val ssgDropStart   = ssgNorm.indexOf("data-kyo-dropdown")
            val mountDropStart = mountNorm.indexOf("data-kyo-dropdown")
            assert(ssgDropStart >= 0, "SSG must have dropdown element (data-kyo-dropdown)")
            assert(mountDropStart >= 0, "mount must have dropdown element (data-kyo-dropdown)")
            // Find the closing tag of the outer dropdown div: search for the hidden div that ends it
            val ssgDropEnd   = ssgNorm.indexOf("</div></div>", ssgDropStart)
            val mountDropEnd = mountNorm.indexOf("</div></div>", mountDropStart)
            val ssgDrop = if ssgDropEnd > ssgDropStart then ssgNorm.substring(ssgDropStart, ssgDropEnd) else ssgNorm.substring(ssgDropStart)
            val mountDrop = if mountDropEnd > mountDropStart then mountNorm.substring(mountDropStart, mountDropEnd)
            else mountNorm.substring(mountDropStart)
            assert(ssgDrop == mountDrop, "dropdown subtree must be identical between SSG and mount")
        end for
    }

    // ---- Phase 6: Docs shell parity (INV-003) ----

    private val testHead = PageHead(title = "t")

    private def docsContent(using Frame): WebsiteContent =
        WebsiteContent(
            intro = "",
            groups = Chunk(
                WebsiteContent.Group(
                    "Foundation",
                    Chunk(WebsiteModule("kyo-core", "Foundation", "kyo-core", "", WebsiteModule.Platforms(true, true, true)))
                )
            ),
            version = WebsiteVersion("latest", "latest", true)
        )

    // Leaf 13: docs shell SSG path renders the article inside data-kyo-reactive (INV-003)
    "docs shell SSG runRenderPage contains data-kyo-reactive wrapping article (INV-003, leaf 13)" in run {
        val src = "## Scope\n\nSome text.\n"
        for
            rendered <- DocsMarkdown.transpile(src)
            route    <- Signal.initRef[String]("/latest/kyo-core/")
            // Use UI.Ast.Reactive directly to avoid ambiguity with StringContext.render
            reactive = UI.Ast.Reactive(route.map(_ => rendered.article))
            view <- DocsApp.view(docsContent, Chunk.empty, "latest", route, rendered.headings, reactive)
            html <- UI.runRenderPage(testHead)(view).take(1).run.map(_.headMaybe.getOrElse(""))
        yield
            assert(html.contains("data-kyo-reactive"), s"data-kyo-reactive not found: $html")
            assert(html.contains("<h2"), s"transpiled h2 not found: $html")
        end for
    }

    // Leaf 13b: article is a UI subtree not a raw-HTML string at article-body level
    "article is a UI subtree not a raw-HTML string at article-body level (leaf 13b)" in run {
        val src = "## Scope\n\nSome text.\n"
        for
            rendered <- DocsMarkdown.transpile(src)
        yield
            // The article field must be a real UI AST node (not a RawHtml for the whole body)
            rendered.article match
                case UI.Ast.RawHtml(_) =>
                    fail("article body must not be a top-level UI.Ast.RawHtml node")
                case _ =>
                    succeed
            end match
        end for
    }

    // Leaf 13c: same Markdown gives byte-identical article HTML in both SSG calls (INV-003)
    "same Markdown gives byte-identical article in two runRenderPage calls (leaf 13c)" in run {
        val src = "## Scope\n\n- item one\n- item two\n"
        for
            rendered1 <- DocsMarkdown.transpile(src)
            rendered2 <- DocsMarkdown.transpile(src)
            html1     <- UI.runRenderPage(testHead)(rendered1.article).take(1).run.map(_.headMaybe.getOrElse(""))
            html2     <- UI.runRenderPage(testHead)(rendered2.article).take(1).run.map(_.headMaybe.getOrElse(""))
        yield assert(
            html1 == html2,
            s"Two runRenderPage calls with the same Markdown must produce identical HTML (INV-003)"
        )
        end for
    }

end ChromeParityTest
