package kyo

import kyo.Browser.*
import scala.scalajs.js.annotation.JSImport
import scala.scalajs.js as sjs

/** The HYDRATE half of `Element.clientOwned`, end to end in a real browser against a real session.
  *
  * This is the only kyo-ui suite that reaches `DomBackend.hydrateBackendNodes`, which is where the browser
  * takes ownership of the regions the server refuses to drive. Nothing else in kyo-ui calls `UI.runHydrate`
  * at all, so before this suite existed the hydrate half of a public API could have been deleted outright
  * and kyo-ui's own tests would still have been green: the only thing exercising it was a WebGL browser
  * leaf in another module, which skips on any machine without a GPU.
  *
  * Reaching it needs a real SSR'd page and a real client bundle. That bundle is the `fixtures`
  * configuration's linked ESModule (`kyo-ui/js/src/fixtures`), whose only root is the `hydrateUIOwnership`
  * export, so it stays small. The build links it as a prerequisite of the test compile, so it cannot be
  * stale relative to the code under test.
  */
class UIOwnershipHydrateBrowserTest extends UITest:

    "the browser drives the region it OWNS and the session drives the rest" in {
        // Both halves are asserted, and each is visible in a way the other cannot fake. The two sides hold
        // SEPARATE signal instances, so a value only one side can produce is the only honest evidence of
        // which side drove a region.
        served { url =>
            for
                _ <- Browser.goto(url)
                // The browser subscribed the region it owns: this value is written by the CLIENT's builder
                // after hydrating, and the server never writes it. If the hydrate subscribed nothing, this
                // region would sit at its SSR'd "server-default" forever.
                _ <- Browser.assertText(Selector.id("client-region"), fixture.OwnershipScene.fromBrowser)
                // And it did NOT subscribe the region it does not own. The client's builder deliberately
                // writes a clobber into the SERVER-owned region's signal; the server never writes that
                // value, so seeing it would mean the browser is a second writer against the session.
                _ <- Browser.assertText(Selector.id("server-region"), "from-server")
            yield ()
        }
    }

    "the browser runs the handler INSIDE the boundary, and refuses the one outside it" in {
        served { url =>
            for
                _ <- Browser.goto(url)
                _ <- Browser.assertText(Selector.id("client-region"), fixture.OwnershipScene.fromBrowser)
                // A click inside the boundary must be run by the BROWSER against its own tree. The session
                // never pushes this region, so the only way this text can change is the browser running it.
                _ <- Browser.click(Selector.id("client-btn"))
                _ <- Browser.assertText(Selector.id("client-region"), fixture.OwnershipScene.clientHandlerRan)
                // A click OUTSIDE the boundary must be run by the SESSION and NOT also by the browser. The
                // server-owned handler writes BOTH refs, so if the browser wrongly ran its local copy, the
                // client-owned region would move to serverHandlerTouchedClient. That is the only way a
                // double-fire is visible from out here: the browser's own signal instances are otherwise
                // invisible, which is exactly why a route-only assertion would prove nothing.
                _ <- Browser.click(Selector.id("server-btn"))
                _ <- Browser.assertText(Selector.id("server-region"), fixture.OwnershipScene.serverHandlerRan)
                _ <- Browser.assertText(Selector.id("client-region"), fixture.OwnershipScene.clientHandlerRan)
            yield ()
        }
    }

    /** Serves the SSR page through the real `UI.runHandlers` alongside the `fixtures` configuration's linked
      * bundle. The page imports `/main.js` (the `hydrateUIOwnership` export module), which pulls in any
      * sibling chunk; every `.js` file in the link output is served so those imports resolve.
      */
    private def served[A](f: String => A < (Browser & Async & Scope & Abort[BrowserException]))(using
        Frame
    ): A < (Async & Scope & Abort[BrowserException]) =
        cancelOnUnsupportedPlatform {
            for
                modules <- readFixtureModules
                bootJs = """import { hydrateUIOwnership } from "/main.js"; hydrateUIOwnership();"""
                head   = UI.PageHead("kyo-ui ownership hydrate test", moduleScript = Present("/boot.js"))
                serverRef   <- Signal.initRef("from-server")
                clientRef   <- Signal.initRef("server-default")
                appHandlers <- UI.runHandlers("/", head)(fixture.OwnershipScene.ui(serverRef, clientRef))
                moduleHandlers = modules.map((name, src) => jsHandler(name, src))
                server <- HttpServer.init(0, "localhost")(
                    (appHandlers ++ (jsHandler("boot.js", bootJs) +: moduleHandlers))*
                )
                result <- Browser.runShared() {
                    f(s"http://localhost:${server.port}/")
                }
            yield result
        }

    private def jsHandler(path: String, source: String)(using Frame): HttpHandler[Any, "body" ~ String, Nothing] =
        HttpRoute.getRaw(path).response(_.bodyText).handler { _ =>
            HttpResponse.ok(source).setHeader("Content-Type", "text/javascript; charset=utf-8")
        }

    /** Reads every `.js` file of the `fixtures` configuration's link, as `(filename, source)` pairs.
      *
      * The link output holds `main.js` (the `hydrateUIOwnership` export module) and any split chunk. All are
      * served so the browser can resolve the import graph from `/main.js`. A MISSING output aborts loudly: a
      * page that fails to import its hydrate entry renders its SSR snapshot and never comes alive, so every
      * assertion would time out naming the wrong cause. It cannot be stale, because the build links it as a
      * prerequisite of the test compile.
      */
    private def readFixtureModules(using Frame): Seq[(String, String)] < Sync =
        Sync.defer {
            val targetRoot = NodePath.join(NodeProcess.cwd(), "kyo-ui", "js", "target")
            Maybe.fromOption(NodeFs.readdirSync(targetRoot).toSeq.collectFirst {
                case d if d.startsWith("scala-") && NodeFs.existsSync(NodePath.join(targetRoot, d, "kyo-ui-fixtures-fastopt")) =>
                    NodePath.join(targetRoot, d, "kyo-ui-fixtures-fastopt")
            })
        }.map {
            case Present(dir) =>
                Sync.defer {
                    NodeFs.readdirSync(dir).toSeq
                        .filter(_.endsWith(".js"))
                        .map(name => (name, NodeFs.readFileSync(NodePath.join(dir, name), "utf8")))
                }
            case Absent =>
                Abort.panic(new IllegalStateException(
                    "kyo-ui fixtures bundle is not linked: no kyo-ui-fixtures-fastopt under kyo-ui/js/target. The " +
                        "build links the fixtures configuration as a prerequisite of Test/compile, so reaching this " +
                        "means that wiring was removed rather than that a link step was forgotten."
                ))
        }

end UIOwnershipHydrateBrowserTest

// The Node bridges live at top level, not in the test class: kyo.test's own `js` builder method shadows
// scalajs's `js` inside a suite body.
@sjs.native
@JSImport("node:path", JSImport.Namespace)
private object NodePath extends sjs.Object:
    def join(parts: String*): String = sjs.native
end NodePath

@sjs.native
@JSImport("node:fs", JSImport.Namespace)
private object NodeFs extends sjs.Object:
    def readFileSync(path: String, encoding: String): String = sjs.native
    def readdirSync(path: String): sjs.Array[String]         = sjs.native
    def existsSync(path: String): Boolean                    = sjs.native
end NodeFs

@sjs.native
@JSImport("node:process", JSImport.Namespace)
private object NodeProcess extends sjs.Object:
    def cwd(): String = sjs.native
end NodeProcess
