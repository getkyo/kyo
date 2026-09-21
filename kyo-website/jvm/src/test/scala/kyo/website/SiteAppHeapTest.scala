package kyo.website

import kyo.*

/** Retained heap of an idle docs tab, against the real generated site in a real Chrome.
  *
  * Every sample forces a collection first, so it reads reachable memory. The DOM node count is asserted stable alongside
  * it, which separates DOM growth from retained JS state.
  *
  * Depends on the wall clock deliberately: a per-second retention leak is only observable as heap over elapsed time.
  * Settle points are fenced on `Browser.goto` and the CDP collect reply, never on a sleep.
  */
class SiteAppHeapTest extends WebsiteTest:

    override def timeout = 10.minutes

    // The shared Chrome's CDP socket and stdio pipes are opaque-inode descriptors no allowlist can match.
    override def config =
        super.config.sequential.leakCheckSockets(false).leakCheckFileDescriptors(false)

    private val idleWindow = 120.seconds

    // A noise budget, not an allowance: a page leaking 8.8 MB/min grows ~17.6 MB across the window.
    private val growthBudget = 6L * 1024 * 1024

    // First paint, the bundle's mount and its deferred index fetch must finish before the baseline sample.
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
                            s"the idle page changed shape: ${first.domNodes} -> ${last.domNodes} DOM nodes"
                        )
                        assert(
                            growth < growthBudget,
                            s"an idle docs tab retained ${growth / 1048576} MB more after ${idleWindow.show} " +
                                s"(${first.used / 1048576} MB -> ${last.used / 1048576} MB), " +
                                s"DOM unchanged at ${first.domNodes} nodes"
                        )
                    end for
                }
            }
        }
    }

end SiteAppHeapTest
