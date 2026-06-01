package kyo.website

import kyo.*
import org.scalajs.dom

/** SPA bundle entry-point.
  *
  * Bootstraps the kyo website single-page application in the browser:
  * 1. Injects `WebsiteStyles.sheet` into the document `<head>` so styles are present before the
  *    first render.
  * 2. Reads `data-boot-scenario` from `document.body` to select the app arm (landing vs docs).
  * 3. Mounts the appropriate app under the `UILocation`-driven router.
  *
  * The landing arm is fully wired in Phase 3. The docs arm is a stub returning `UI.empty` and is
  * wired in Phase 6.
  *
  * This object is the one shared ESModule entry across all doc versions (INV-008).
  */
object WebsiteBundleMain:

    // Unsafe: Frame.internal is the boundary value here. `def main(args: Array[String])` is the
    // JS entry point imposed by Scala.js and there is no user code above it to thread a Frame
    // from. Same justification as SpaHarnessMain (SpaHarnessMain.scala:22).
    private given bundleFrame: Frame = Frame.internal

    def main(args: Array[String]): Unit =
        // Unsafe: browser entry-point bridge; single controlled crossing from JS main into the
        // Kyo scheduler. AllowUnsafe is necessary here because we have no Kyo fiber context.
        runStylesheetUnsafe()
        val boot = dom.document.body.getAttribute("data-boot-scenario")
        runMountUnsafe(
            boot match
                case "landing" => buildLanding()
                case _         => Sync.defer(UI.empty) // Phase 6: wire docs arm (stub returns empty)
        )
    end main

    private def buildLanding()(using Frame): UI < Sync =
        for
            route <- Sync.defer(UILocation.current) // Signal[String]; installs routing listeners
            versions = readVersions() // Phase 4: Chunk.empty stub; Phase 4 fills island parse
            view <- LandingApp.view(versions)
        yield
            // landing is evergreen: any route renders the same landing view
            UI.Ast.Reactive(route.map(_ => view))

    private def readVersions(): Chunk[WebsiteVersion] =
        Chunk.empty // Phase 4: parse the JSON island (stub returns empty for Phase 3)

    // Unsafe: app-entry boundary bridge; injects stylesheet before first render from JS main.
    // This is the sanctioned unsafe tier crossing (matches SpaHarnessMain pattern).
    private def runStylesheetUnsafe(): Unit =
        import AllowUnsafe.embrace.danger
        discard(Sync.Unsafe.evalOrThrow(UI.runStylesheet(WebsiteStyles.sheet)))

    // Unsafe: app-entry boundary bridge; mounts the SPA fiber from JS main into the Kyo
    // scheduler. This is the sanctioned unsafe tier crossing (matches SpaHarnessMain pattern).
    private def runMountUnsafe(view: UI < Sync): Unit =
        import AllowUnsafe.embrace.danger
        discard(Sync.Unsafe.evalOrThrow(
            Fiber.initUnscoped(Scope.run(
                view.flatMap(v => UI.runMount(v))
            )).unit
        ))
    end runMountUnsafe

end WebsiteBundleMain
