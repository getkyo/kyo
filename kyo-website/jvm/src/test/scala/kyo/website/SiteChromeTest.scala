package kyo.website

import kyo.*

/** Base for suites that drive the generated site, served by [[ServedSite]], in a real Chrome.
  *
  * Each leaf gets a Chrome of its own rather than the shared one: the shared Chrome lives until the JVM exits, and its stdio pipe would
  * still be open at the module's end-of-run descriptor check, which the other suites here keep enabled. A Chrome of the leaf's own is gone
  * with the leaf's scope.
  *
  * The Chrome resolves no host but localhost, so a page's load never waits on the network outside the runner (the head links Google
  * Fonts). Navigations get [[pageLoadBudget]] rather than kyo-browser's 5 s default, because the load event waits for the bundle.
  *
  * Google publishes no `chrome-headless-shell` for linux-arm64 or windows-arm64, and there the launch fails with a setup error carrying
  * [[unsupportedPlatformMarker]]. That one failure cancels the leaf with the launcher's message; every other failure propagates.
  */
abstract class SiteChromeTest extends WebsiteTest:

    private val unsupportedPlatformMarker = "cannot auto-download chrome-headless-shell"

    // The load event waits for the 35 MB `fullLinkJS` module script to download, compile and run: 0.85 s on an M-series laptop, and past
    // the 5 s default on a memory-starved windows-x64 runner, where the document had committed. A bound on a hung page, not a pass condition.
    private val pageLoadBudget = 2.minutes

    /** True once the bundle has mounted: the mount replaces the body, and with it the boot islands the bundle read before mounting. */
    protected val mounted = "document.getElementById('docs-island') === null"

    /** Serves the site, opens `route` in a fresh Chrome once the page's network is idle, and runs `f` with the served site. */
    protected def inChrome[A](route: String)(f: ServedSite.Site => A < (Browser & Async & Abort[BrowserReadException]))(using
        Frame
    ): A < (Async & Scope & Abort[WebsiteException | FileSystemException | HttpBindException | HttpRouteException | BrowserException]) =
        ServedSite.serve { site =>
            Abort.recover[BrowserSetupException] { (ex: BrowserSetupException) =>
                val msg = ex.getMessage
                if msg != null && msg.contains(unsupportedPlatformMarker) then Sync.defer(cancel(msg))
                else Abort.fail[BrowserSetupException](ex)
            } {
                Browser.chromeForTestingLaunchConfig().map { launch =>
                    Browser.run(launch.extraArgs(launch.extraArgs.toSeq :+ "--host-resolver-rules=MAP * ~NOTFOUND, EXCLUDE localhost")) {
                        Browser.withConfig(_.loadSchedule(Schedule.fixed(100.millis).maxDuration(pageLoadBudget))) {
                            Browser.goto(s"${site.url}$route").andThen(f(site))
                        }
                    }
                }
            }
        }

end SiteChromeTest
