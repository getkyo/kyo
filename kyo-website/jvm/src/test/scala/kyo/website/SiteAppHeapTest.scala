package kyo.website

import kyo.*

/** Measures what an open docs tab RETAINS over time, against the real generated site in a real Chrome.
  *
  * The page is loaded and then left completely alone: no click, no navigation, no keystroke. Every sample forces a full
  * collection (`Browser.collectGarbage`) before reading `Browser.heapUsage`, so what it reports is reachable memory, not
  * collection lag. A tab that is idle and structurally unchanged must not grow its retained heap; growth here is memory
  * the page can never reclaim while it stays open.
  *
  * `domNodes` is sampled alongside the heap and asserted stable. It separates the two ways a page can grow: if the node
  * count climbs, the page is accumulating DOM; if it is flat while the heap climbs, the growth is retained JS objects
  * with no DOM counterpart, which is the harder failure to see and the one this leaf is built to catch.
  *
  * WALL-CLOCK DEPENDENCE, DELIBERATE. Elapsed time is the independent variable of the property under test, not a proxy
  * for settledness: a per-second retention leak is only observable as heap-per-second. The leaf therefore samples across
  * a real time window. It does not time an operation or assert on how long anything took, and the settle points it needs
  * (page loaded, collection finished) are fenced on `Browser.goto` and the CDP collect reply, never on a sleep.
  *
  * The measurement is deliberately end-to-end: the site is emitted by the real generator from the live repo and served
  * over HTTP with the real `fullLinkJS` bundle, because the leak scales with the number of reactive regions the SSG puts
  * on a page (the module sidebar renders one region per module) and only the real content produces that count.
  */
class SiteAppHeapTest extends WebsiteTest:

    override def timeout = 10.minutes

    // The shared Chrome is held for the whole run, so its CDP socket and the process's stdio pipes are opaque-inode
    // descriptors no allowlist can match. Only those two categories are disabled; thread and fiber detection stay on.
    // Same rationale as kyo-browser's BaseBrowserTest and kyo-ui's UITest.
    override def config =
        super.config.sequential.leakCheckSockets(false).leakCheckFileDescriptors(false)

    /** The idle window the heap is measured across. Long enough that a per-second leak accumulates well clear of
      * allocation noise, short enough to keep the leaf inside its budget.
      */
    private val idleWindow = 120.seconds

    /** Growth allowed across [[idleWindow]] on a tab that is doing nothing. An idle page should retain a flat heap, so
      * this is a noise budget, not an allowance: the leak this leaf was written against grew ~8.8 MB per minute
      * (~17.6 MB across this window), so the bound sits roughly 3x below the failure it must catch.
      */
    private val growthBudget = 6L * 1024 * 1024

    /** Let the first paint, the bundle's mount, and its deferred index fetch finish before the baseline sample, so the
      * baseline is a mounted steady state rather than a page still loading.
      */
    private val settleDelay = 10.seconds

    private val unsupportedPlatformMarker = "cannot auto-download chrome-headless-shell"

    private def cancelOnUnsupportedPlatform[A, S](
        f: A < (Async & Scope & Abort[BrowserSetupException] & S)
    )(using Frame): A < (Async & Scope & Abort[BrowserSetupException] & S) =
        Abort.recover[BrowserSetupException] { (ex: BrowserSetupException) =>
            val msg = ex.getMessage
            if msg != null && msg.contains(unsupportedPlatformMarker) then Sync.defer(cancel(msg))
            else Abort.fail[BrowserSetupException](ex)
        } { f }

    private case class Sample(used: Long, domNodes: Int)

    private def sample(using Frame): Sample < (Browser & Abort[BrowserReadException]) =
        for
            _     <- Browser.collectGarbage
            heap  <- Browser.heapUsage
            nodes <- Browser.evalInt("document.getElementsByTagName('*').length")
        yield Sample(heap.used, nodes)

    "an idle docs tab does not grow its retained heap" in {
        ServedSite.serve { baseUrl =>
            cancelOnUnsupportedPlatform {
                Browser.runShared() {
                    for
                        _     <- Browser.goto(s"$baseUrl/latest/kyo-core/")
                        _     <- Async.sleep(settleDelay)
                        first <- sample
                        _     <- Async.sleep(idleWindow)
                        last  <- sample
                    yield
                        val growth = last.used - first.used
                        assert(
                            last.domNodes == first.domNodes,
                            s"the idle page changed shape (${first.domNodes} -> ${last.domNodes} DOM nodes), so this " +
                                "leaf no longer measures a structurally idle tab"
                        )
                        assert(
                            growth < growthBudget,
                            s"an idle docs tab retained ${growth / 1048576} MB more after ${idleWindow.show} " +
                                s"(${first.used / 1048576} MB -> ${last.used / 1048576} MB) across a forced collection, " +
                                s"with its DOM unchanged at ${first.domNodes} nodes: the page is accumulating " +
                                "unreachable-to-the-reader state that only closing the tab releases"
                        )
                    end for
                }
            }
        }
    }

end SiteAppHeapTest
