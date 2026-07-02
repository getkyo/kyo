package kyo.internal

import kyo.*

/** A trivial backend node fixture (no 3D dependency), used only to prove the served page stays a
  * valid document when the tree contains a non-plain-DOM node.
  */
final case class FakeStageNode()(using val frame: Frame) extends UI.Ast.BackendNode:
    type Self = FakeStageNode
    private[kyo] def backend: String                                 = "fake-stage"
    private[kyo] def placeholder: UI.Ast.BackendNode.Placeholder     = UI.Ast.BackendNode.Placeholder("canvas", UI.Ast.Attrs())
    private[kyo] def backendChildren: Chunk[UI]                      = Chunk.empty
    private[kyo] def boundProps: Chunk[UI.Ast.BackendNode.BoundProp] = Chunk.empty
    def id(v: String): FakeStageNode                                 = this
end FakeStageNode

/** Tests the locked `UI.runHandlers` PageHead overload's effect on the served SSR page: it threads
  * `head.moduleScript` into the page so an app links its client island bundle, and the 1-arg form is
  * unchanged and delegates to the 2-arg form with `PageHead("kyo-ui")`.
  *
  * The serve-and-fetch leaves run a real `HttpServer` and `HttpClient.getText`; that transport is
  * JVM+JS (notNative). The renderPage leaves are pure string assertions that run on every platform.
  */
class UIRunHandlersTest extends kyo.test.Test[Any]:

    private def fetchPage(ui: UI, head: Maybe[UI.PageHead])(using Frame): String < (Async & Abort[HttpException]) =
        Scope.run {
            for
                handlers <- head match
                    case Present(h) => UI.runHandlers("/", h)(ui)
                    case Absent     => UI.runHandlers("/")(ui)
                server <- HttpServer.init(0, "localhost")(handlers*)
                body   <- HttpClient.getText(s"http://localhost:${server.port}/")
            yield body
        }

    // ---------- serve-and-fetch leaves (JVM+JS) ----------

    "runHandlers with moduleScript links the island bundle".notNative in {
        fetchPage(
            UI.div("hi"),
            Present(UI.PageHead("app", moduleScript = Present("/_kyo/island.js")))
        ).map { body =>
            assert(
                body.contains("""<script type="module" src="/_kyo/island.js"></script>"""),
                s"the page must link the island bundle; body was:\n$body"
            )
            assert(body.contains("<script>"), "the inline server-push client script must still be present")
        }
    }

    "the 1-arg runHandlers links no island".notNative in {
        fetchPage(UI.div("hi"), Absent).map { body =>
            assert(
                !body.contains("""<script type="module""""),
                s"the 1-arg form must link NO module island; body was:\n$body"
            )
        }
    }

    "the 1-arg form equals the 2-arg PageHead(kyo-ui) default".notNative in {
        val ui = UI.div("hi")
        for
            oneArg <- fetchPage(ui, Absent)
            twoArg <- fetchPage(ui, Present(UI.PageHead("kyo-ui")))
        yield assert(
            oneArg == twoArg,
            s"the 1-arg form must render byte-identically to the 2-arg PageHead(\"kyo-ui\") default"
        )
        end for
    }

    "a non-default head changes only the head and the module-script injection, never the body bytes".notNative in {
        // The body region (the host/body HTML plus the inline server-push client <script>) must be
        // byte-identical between the 1-arg default render and a 2-arg render with a non-default head
        // (custom title/css + a module script). The 2-arg form is allowed to change ONLY the <head>
        // block and the appended module <script>; the body bytes are pinned.
        val ui = UI.div("hi")
        val nonDefaultHead = UI.PageHead(
            "custom-title",
            css = ".x{color:red}",
            moduleScript = Present("/_kyo/island.js")
        )
        // The body content sits between <body> and the close of the inline client <script>; that span is
        // head-independent. Extract it from both renders and require an exact match.
        def bodyRegion(page: String): String =
            val start = page.indexOf("<body>")
            val end   = page.indexOf("</script>", start)
            assert(start >= 0 && end >= 0, s"page must have a <body> and an inline client </script>; got:\n$page")
            page.substring(start, end + "</script>".length)
        end bodyRegion
        for
            oneArg <- fetchPage(ui, Absent)
            twoArg <- fetchPage(ui, Present(nonDefaultHead))
        yield
            assert(
                bodyRegion(oneArg) == bodyRegion(twoArg),
                s"the body region must be byte-identical across heads; 1-arg:\n${bodyRegion(oneArg)}\n2-arg:\n${bodyRegion(twoArg)}"
            )
            // The head DID change: the non-default title is present only in the 2-arg render.
            assert(twoArg.contains("<title>custom-title</title>"), "the 2-arg head must carry the custom title")
            assert(!oneArg.contains("<title>custom-title</title>"), "the 1-arg default head must NOT carry the custom title")
            assert(twoArg.contains(".x{color:red}"), "the 2-arg head must carry the custom css")
            // The module-script injection appears only in the 2-arg render, after the body region.
            assert(
                twoArg.contains("""<script type="module" src="/_kyo/island.js"></script>"""),
                "the 2-arg form must link the module script"
            )
            assert(!oneArg.contains("""<script type="module""""), "the 1-arg form must link no module script")
        end for
    }

    "a page with a backend node links the module script and stays a valid page".notNative in {
        // A UI with a backend node, served with a module script: the page must link the bundle and
        // stay a valid server-push page (the inline client script present, a well-formed document).
        // The backend node renders as a bare placeholder; the island bundle owns the client mount.
        fetchPage(
            UI.div(FakeStageNode()),
            Present(UI.PageHead("app", moduleScript = Present("/_kyo/island.js")))
        ).map { body =>
            assert(
                body.contains("""<script type="module" src="/_kyo/island.js"></script>"""),
                s"the page must link the module script; body was:\n$body"
            )
            assert(body.contains("<canvas"), s"the backend node must render as a bare canvas; body was:\n$body")
            // Still a valid server-push page: the inline client script is present and the document is well-formed.
            assert(body.contains("<script>"), "the inline server-push client script must still be present")
            assert(body.startsWith("<!DOCTYPE html>"), "the served document must be a valid HTML page")
        }
    }

    // ---------- pure renderPage leaf (all platforms) ----------

    "renderPage appends a module script only when moduleScript is Present" in {
        val withScript = HtmlRenderer.renderPage("t", "<p>b</p>", "", "/", Present("/x.js"))
        val without    = HtmlRenderer.renderPage("t", "<p>b</p>", "", "/", Absent)
        assert(withScript.contains("""<script type="module" src="/x.js"></script>"""))
        assert(!without.contains("""<script type="module""""))
        // The 1-arg-equivalent (default Absent) equals the explicit Absent.
        assert(HtmlRenderer.renderPage("t", "<p>b</p>", "", "/") == without)
    }

    "the locked 2-arg runHandlers signature type-checks at its declared type" in {
        val _: (String, UI.PageHead) => (=> UI < Async) => Frame ?=> Seq[HttpHandler[?, ?, ?]] < Sync =
            (basePath, head) => ui => UI.runHandlers(basePath, head)(ui)
        succeed
    }

end UIRunHandlersTest
