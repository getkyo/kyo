package kyo.website

import kyo.*
import org.scalajs.dom

/** In-Chrome chrome-parity and SPA-navigation smoke tests.
  *
  * Both leaves need a page: one mounts the shell over the SSG's own HTML and compares what the DOM holds afterwards, the
  * other clicks a header link and watches the content slot swap. The browser row is that page, so they run there and are
  * cancelled everywhere else, Node included, where there is no document to mount into.
  */
class WebsiteSpaSmokeTest extends kyo.test.Test[Any]:

    override def config = super.config.sequential

    private val docsHome = "/latest/kyo-core/"

    /** `data-kyo-path` values are positional indices over the same tree, so they match by construction; blanking them
      * keeps a future ordering change from reading as a parity failure.
      */
    private def normalize(html: String): String =
        html.replaceAll("""data-kyo-path="[^"]*"""", "data-kyo-path=\"\"")

    /** The unified shell with the inputs the SSG and the bundle both pass: no versions, an empty search, and the
      * caller's content in the one reactive slot.
      */
    private def siteShell(content: Signal[UI], navigate: String => Unit < Async)(using Frame): UI < Sync =
        for
            queryRef <- Signal.initRef("")
            view <- SiteApp.view(
                Chunk.empty,
                docsHome,
                Signal.initConst(DocsSearch.Index(Chunk.empty)),
                queryRef,
                navigate,
                Kyo.unit,
                Kyo.unit,
                (_: String) => Kyo.unit,
                content
            )
        yield view

    /** Flips once `DomBackend.mountInto` has finished wiring the mount up, which is the point a dispatched click reaches
      * a listener. Waiting for a rendered node is not enough: the delegation is installed after the first paint.
      */
    final private class MountReady extends kyo.internal.DomBackend.MountDiagnostics:
        private var ready                                                                    = false
        def installed: Boolean                                                               = ready
        def channelClosed(): Unit                                                            = ()
        def drainInterrupting(): Unit                                                        = ()
        def drainJoined(): Unit                                                              = ()
        override def dragRuntimeInstalled(runtime: kyo.internal.DomDragRuntime.Handle): Unit = ready = true
    end MountReady

    "post-mount chrome DOM equals the SSG chrome (cross-platform gate)".onlyBrowser in {
        for
            body <- LandingApp.body(docsHome)
            view <- siteShell(Signal.initConst(body), (_: String) => Kyo.unit)
            // The render re-emits on every reactive change, so the SSG's own page is its first emission.
            ssg <- UI.runRender(view).take(1).run.map(_.headMaybe.getOrElse(""))
            // Read the SSG's HTML back out of the DOM, so both sides of the comparison went through the browser's own
            // parser and serializer and differ only where the trees do.
            parsed <- Sync.defer {
                val probe = dom.document.createElement("div")
                probe.innerHTML = ssg
                probe.innerHTML
            }
            // The SSG's own first paint, which the mount then overwrites with its render of the same view.
            _     <- Sync.defer(dom.document.body.innerHTML = ssg)
            ready <- Sync.defer(new MountReady)
            fiber <- Fiber.initUnscoped(Scope.run(kyo.internal.DomBackend.mount(view, ready)))
            _     <- assertEventually(Sync.defer(ready.installed))
            after <- Sync.defer(dom.document.body.innerHTML)
            _     <- fiber.interrupt
            _     <- fiber.getResult
            _     <- Sync.defer(dom.document.body.innerHTML = "")
        yield
            assert(ssg.contains("site-header"), "the SSG chrome must carry the header")
            assert(normalize(after) == normalize(parsed))
    }

    "click-to-navigate swaps content without reload (cross-platform gate)".onlyBrowser in {
        val landingBody: UI             = UI.div("landing").id("route-body")
        def docsBody(route: String): UI = UI.div(s"docs at $route").id("route-body")
        for
            start      <- UILocation.current.current
            contentRef <- Signal.initRef[UI](landingBody)
            view       <- siteShell(contentRef, (_: String) => Kyo.unit)
            // Plain anchors are rewritten into a pushState by UILocation's own interceptor, not by the shell's
            // `navigate`, so the route change reaches the content slot the way the bundle wires it: a fiber on the
            // location signal.
            nav <- Fiber.initUnscoped(Loop.forever {
                UILocation.current.next.map(route => contentRef.set(docsBody(route)))
            })
            // A page that reloaded loses this, so its survival is what "no full page reload" means here.
            _     <- Sync.defer(scala.scalajs.js.Dynamic.global.globalThis.updateDynamic("kyoSpaSmokeMarker")("alive"))
            ready <- Sync.defer(new MountReady)
            fiber <- Fiber.initUnscoped(Scope.run(kyo.internal.DomBackend.mount(view, ready)))
            _     <- assertEventually(Sync.defer(ready.installed && dom.document.querySelector("#route-body") != null))
            // The header's first nav link is Docs, the one client-side route this shell offers.
            link   <- Sync.defer(dom.document.querySelector("header.site-header nav.links a").asInstanceOf[dom.html.Anchor])
            _      <- Sync.defer(assert(link != null, "the header must carry the Docs link"))
            href   <- Sync.defer(link.getAttribute("href"))
            _      <- Sync.defer(link.click())
            _      <- assertEventually(Sync.defer(dom.document.querySelector("#route-body").textContent.startsWith("docs at")))
            route  <- UILocation.current.current
            styles <- Sync.defer(dom.document.querySelectorAll("head style").length)
            marker <- Sync.defer(
                scala.scalajs.js.Dynamic.global.globalThis.selectDynamic("kyoSpaSmokeMarker").asInstanceOf[String]
            )
            _ <- fiber.interrupt
            _ <- fiber.getResult
            _ <- nav.interrupt
            _ <- UILocation.replace(start)
            _ <- Sync.defer(dom.document.body.innerHTML = "")
        yield
            assert(route == href, s"the route the click pushed was $route, the link points at $href")
            assert(styles > 0, "the mount's stylesheet must survive the content swap")
            assert(marker == "alive", "the page must not have reloaded")
        end for
    }

end WebsiteSpaSmokeTest
