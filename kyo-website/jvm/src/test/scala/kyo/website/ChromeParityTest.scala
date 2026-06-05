package kyo.website

import kyo.*
import kyo.UI.PageHead

/** Verifies INV-003 hydration parity for the unified `SiteApp` shell on BOTH a landing route and a
  * docs route, plus the docs-shell 2-pane body.
  *
  * The central hydration contract: the SSG-emitted shell for a route and the bundle's first render
  * of the same route must produce a structurally identical `data-kyo-path` tree, because
  * `DomBackend.mountInto` overwrites the server HTML with `container.innerHTML = html`. A divergence
  * desyncs the later reactive `onChange` patches. We prove it by rendering the SAME `SiteApp.view`
  * (same versions, same docsHome, same empty search query/index, same content body) through
  * `UI.runRender` (the SSG path) and `RecordingBackend.render` (the same renderer
  * `DomBackend.mountInto` calls) and asserting normalized equality.
  *
  * JVM-only: `RecordingBackend` uses `HtmlRenderer` directly, which is `private[kyo]` and accessible
  * here because `kyo.website` is a subpackage of `kyo`. `UI.runMount` is JS-only; this backend
  * replaces it for parity assertions on the JVM.
  */
class ChromeParityTest extends Test:

    private val v1        = WebsiteVersion("v1.0.0-RC2", "1.0.0-RC2", true)
    private val v0        = WebsiteVersion("v0.9.3", "0.9.3", false)
    private val versions2 = Chunk(v1, v0)

    private val docsHomeRoute = "/latest/kyo-core/"

    /** Strip `data-kyo-path="..."` values (they are positional indices and are deterministic for
      * an identical UI tree; this normalization is a safeguard for any future ordering sensitivity).
      */
    private def normalize(html: String): String =
        html.replaceAll("""data-kyo-path="[^"]*"""", "data-kyo-path=\"\"")
    end normalize

    /** Build the unified `SiteApp.view` for a route, wrapping `body` with the SAME header inputs the
      * SSG and the bundle both pass: same versions, same docsHome, an empty search query/index, and
      * a constant content signal. Identical inputs are the parity contract.
      */
    private def siteShell(versions: Chunk[WebsiteVersion], docsHome: String, body: UI)(using Frame): UI < Sync =
        for
            queryRef <- Signal.initRef("")
            view <- SiteApp.view(
                versions,
                docsHome,
                Signal.initConst(DocsSearch.Index(Chunk.empty)),
                queryRef,
                (_: String) => Kyo.unit,
                Kyo.unit,
                Signal.initConst(body)
            )
        yield view

    "SiteApp landing-route shell: SSG vs RecordingBackend parity (INV-003)" in run {
        for
            body  <- LandingApp.body(docsHomeRoute)
            view  <- siteShell(versions2, docsHomeRoute, body)
            ssg   <- UI.runRender(view).take(1).run.map(_.headMaybe.getOrElse(""))
            mount <- RecordingBackend.render(view)
        yield assert(
            normalize(ssg) == normalize(mount),
            "SSG (runRender) and mount (RecordingBackend) must produce identical HTML for the landing route shell (INV-003)"
        )
        end for
    }

    "SiteApp docs-route shell: SSG vs RecordingBackend parity (INV-003)" in run {
        // A "## Scope" section under the (level-1) page title, so the active module's nested section
        // outline (.sidebar-sections with a #scope link) renders. Both the SSG and the bundle's first
        // mount must produce that nested outline identically, which is exactly the hydration contract
        // now that the outline lives inside the rail (no separate right TOC pane).
        val src = "# kyo-core\n\n## Scope\n\nSome text.\n"
        for
            rendered <- DocsMarkdownRender.transpile(src)
            route    <- Signal.initRef[String](docsHomeRoute)
            reactive = UI.Ast.Reactive(route.map(_ => rendered.article))
            // SSG and the bundle's first paint are both fully loaded: contentLoading is constant false,
            // so the prev/next pager is part of the parity contract (present in both renders).
            body  <- DocsApp.body(docsContent, "latest", route, Signal.initConst(rendered.headings), reactive, Signal.initConst(false))
            view  <- siteShell(versions2, docsHomeRoute, body)
            ssg   <- UI.runRender(view).take(1).run.map(_.headMaybe.getOrElse(""))
            mount <- RecordingBackend.render(view)
        yield
            assert(
                normalize(ssg) == normalize(mount),
                "SSG (runRender) and mount (RecordingBackend) must produce identical HTML for the docs route shell (INV-003)"
            )
            // The nested section outline (the former right-TOC content, now in the rail) is part of the
            // parity contract: both paths render the active module's section list with the #scope link,
            // and no separate right-TOC pane class is emitted anymore.
            assert(normalize(ssg).contains("sidebar-sections"), s"SSG must render the rail's nested section outline: ${normalize(ssg)}")
            assert(normalize(ssg).contains("#scope"), s"SSG must render the #scope section link in the rail: ${normalize(ssg)}")
            assert(!normalize(ssg).contains("docs-toc"), s"the removed right-TOC pane (docs-toc) must not be emitted: ${normalize(ssg)}")
            // With contentLoading=const-false the prev/next pager is in the loaded state, so it renders in
            // both the SSG and the bundle's first mount: the loading gate must not hide it on a static page.
            assert(normalize(ssg).contains("prev-next"), s"SSG must render the prev/next pager in the loaded state: ${normalize(ssg)}")
            assert(
                normalize(mount).contains("prev-next"),
                s"mount must render the prev/next pager in the loaded state: ${normalize(mount)}"
            )
        end for
    }

    "SiteApp intro/overview-route shell: SSG vs RecordingBackend parity (INV-003)" in run {
        // The intro/overview route `/latest/` renders the root-README overview as a real article (the
        // same `content.md` the bundle fetches). The rail's Overview item is the active expanded item
        // showing the intro's level-2 sections. Both the SSG and the bundle's first mount must produce
        // that overview article + its rail sections identically (hydration parity at the intro route).
        val intro = "## Introduction\n\nKyo is a toolkit.\n\n## Coming from ZIO\n\nNotes.\n"
        for
            rendered <- DocsMarkdownRender.transpile(intro)
            route    <- Signal.initRef[String]("/latest/")
            reactive = UI.Ast.Reactive(route.map(_ => rendered.article))
            // Constant-false contentLoading: see the docs-route parity leaf above.
            body  <- DocsApp.body(docsContent, "latest", route, Signal.initConst(rendered.headings), reactive, Signal.initConst(false))
            view  <- siteShell(versions2, docsHomeRoute, body)
            ssg   <- UI.runRender(view).take(1).run.map(_.headMaybe.getOrElse(""))
            mount <- RecordingBackend.render(view)
        yield
            assert(
                normalize(ssg) == normalize(mount),
                "SSG (runRender) and mount (RecordingBackend) must produce identical HTML for the intro/overview route shell (INV-003)"
            )
            // The overview article prose ships in the SSG render.
            assert(normalize(ssg).contains("Kyo is a toolkit."), s"SSG must render the overview prose: ${normalize(ssg)}")
            // The Overview is the active expanded rail item with its level-2 sections.
            assert(normalize(ssg).contains("nav-item-active"), s"SSG must render the Overview as the active rail item: ${normalize(ssg)}")
            assert(normalize(ssg).contains("sidebar-sections"), s"SSG must render the Overview's nested section outline: ${normalize(ssg)}")
            assert(normalize(ssg).contains("#introduction"), s"SSG must render the #introduction section link: ${normalize(ssg)}")
        end for
    }

    "parity holds for the header dropdown subtree under SiteApp (INV-003)" in run {
        for
            body  <- LandingApp.body(docsHomeRoute)
            view  <- siteShell(versions2, docsHomeRoute, body)
            ssg   <- UI.runRender(view).take(1).run.map(_.headMaybe.getOrElse(""))
            mount <- RecordingBackend.render(view)
        yield
            val ssgNorm   = normalize(ssg)
            val mountNorm = normalize(mount)
            // Both outputs must contain the dropdown markup for both version labels.
            assert(ssgNorm.contains("1.0.0-RC2"), "SSG must contain version label")
            assert(mountNorm.contains("1.0.0-RC2"), "mount must contain version label")
            assert(ssgNorm.contains("0.9.3"), "SSG must contain second version label")
            assert(mountNorm.contains("0.9.3"), "mount must contain second version label")
            // kyo-ui dropdown renders as a div with a data-kyo-dropdown attribute (not a <select>).
            val ssgDropStart   = ssgNorm.indexOf("data-kyo-dropdown")
            val mountDropStart = mountNorm.indexOf("data-kyo-dropdown")
            assert(ssgDropStart >= 0, "SSG must have dropdown element (data-kyo-dropdown)")
            assert(mountDropStart >= 0, "mount must have dropdown element (data-kyo-dropdown)")
            val ssgDropEnd   = ssgNorm.indexOf("</div></div>", ssgDropStart)
            val mountDropEnd = mountNorm.indexOf("</div></div>", mountDropStart)
            val ssgDrop = if ssgDropEnd > ssgDropStart then ssgNorm.substring(ssgDropStart, ssgDropEnd) else ssgNorm.substring(ssgDropStart)
            val mountDrop = if mountDropEnd > mountDropStart then mountNorm.substring(mountDropStart, mountDropEnd)
            else mountNorm.substring(mountDropStart)
            assert(ssgDrop == mountDrop, "dropdown subtree must be identical between SSG and mount")
        end for
    }

    // ---- Docs shell body parity (INV-003) ----

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

    // Leaf 13: docs body SSG path renders the article inside data-kyo-reactive (INV-003)
    "docs body SSG runRenderPage contains data-kyo-reactive wrapping article (INV-003, leaf 13)" in run {
        val src = "## Scope\n\nSome text.\n"
        for
            rendered <- DocsMarkdownRender.transpile(src)
            route    <- Signal.initRef[String]("/latest/kyo-core/")
            reactive = UI.Ast.Reactive(route.map(_ => rendered.article))
            view <- DocsApp.body(docsContent, "latest", route, Signal.initConst(rendered.headings), reactive, Signal.initConst(false))
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
            rendered <- DocsMarkdownRender.transpile(src)
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
            rendered1 <- DocsMarkdownRender.transpile(src)
            rendered2 <- DocsMarkdownRender.transpile(src)
            html1     <- UI.runRenderPage(testHead)(rendered1.article).take(1).run.map(_.headMaybe.getOrElse(""))
            html2     <- UI.runRenderPage(testHead)(rendered2.article).take(1).run.map(_.headMaybe.getOrElse(""))
        yield assert(
            html1 == html2,
            s"Two runRenderPage calls with the same Markdown must produce identical HTML (INV-003)"
        )
        end for
    }

    // Leaf 8 (INV-003): SSG article HTML equals the client-injected article HTML.
    // The SSG side produces articleHtml via renderArticle (UI.runRender of the article subtree).
    // The client injection side wraps that HTML in UI.rawHtml and renders it via RecordingBackend.
    // HtmlRenderer emits RawHtml verbatim (no escaping), so both sides return the same string.
    // This leaf crosses the real RecordingBackend/HtmlRenderer path and confirms the rawHtml
    // injection is byte-identical to the SSG article string (INV-003).
    "INV-003 SSG article HTML equals injected article HTML (RecordingBackend rawHtml path)" in run {
        val src = "# Title\n## Section\n\nCode: ```scala\nval x = true\nval n = null\n```\n"
        for
            rendered <- DocsMarkdownRender.renderArticle(src)
            ssgHtml = rendered.articleHtml
            injectedHtml <- RecordingBackend.render(UI.rawHtml(ssgHtml))
        yield assert(
            normalize(ssgHtml) == normalize(injectedHtml),
            s"SSG article HTML and injected rawHtml must be byte-identical after normalization (INV-003)\nSSG: $ssgHtml\nInjected: $injectedHtml"
        )
        end for
    }

end ChromeParityTest
