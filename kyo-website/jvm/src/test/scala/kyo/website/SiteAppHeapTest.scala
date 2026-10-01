package kyo.website

import kyo.*

/** Retained heap of an idle docs tab, against the real generated site in a real Chrome.
  *
  * Every sample forces a collection first, so it reads reachable memory. The DOM node count is asserted stable alongside it, which
  * separates DOM growth from retained JS state.
  *
  * The idle window is Chrome's virtual time, not the wall clock: the page's timers, `Date` and `performance.now` run through the budget as
  * fast as the page can execute them, and the leaf waits until the page's own clock reaches the end of it. A per-second retention leak is
  * driven by the page's timers, so a virtual window exposes it exactly as a real one would.
  */
class SiteAppHeapTest extends SiteChromeTest:

    override def timeout = 5.minutes

    private val idleWindow = 2.minutes

    // Measured across this window: 0.37 to 0.39 MB on a page that does not leak; a page retaining 160 KB per second grew 19.2 MB.
    private val growthBudget = 1L * 1024 * 1024

    // A bound on a hung page, not a pass condition: the index fetch and the virtual window each finish in seconds.
    private val pageWait = Schedule.fixed(50.millis).maxDuration(2.minutes)

    private case class Sample(used: Long, domNodes: Int)

    private case class VirtualTimePolicy(policy: String, budget: Double) derives Schema

    private def sample(using Frame): Sample < (Browser & Abort[BrowserReadException]) =
        for
            _     <- Browser.collectGarbage
            heap  <- Browser.heapUsage
            nodes <- Browser.evalInt("document.getElementsByTagName('*').length")
        yield Sample(heap.used, nodes)

    // Only the full index carries sections, so a section hit proves the bundle's eager index fetch has landed and been parsed. The index is 1 MB
    // of JSON, and a baseline taken before it lands would count it as growth.
    private def searchIndexLoaded(using Frame): Unit < (Browser & Abort[BrowserReadException]) =
        val search = Browser.Selector.css(".search-input")
        for
            _ <- Browser.fill(search, "Abort")
            _ <- Browser.waitForExists(Browser.Selector.css(".search-result-sub"), Present(pageWait))
            _ <- Browser.fill(search, "")
            _ <- Browser.waitForExists(Browser.Selector.css(".search-results[hidden]"))
        yield ()
        end for
    end searchIndexLoaded

    // Chrome pauses virtual time once the budget is spent, so the page's clock stops at the window's end. The poll is a plain read:
    // kyo-browser's stability sampler waits on page timers, which never fire again once the page's time is paused.
    private def idle(window: Duration)(using Frame): Unit < (Browser & Abort[BrowserReadException]) =
        val clock = "performance.now()"
        for
            start <- Browser.evalDouble(clock)
            end = start + window.toMillis
            // kyo-browser has no virtual-time verb; the tab's CDP session is reachable from inside `kyo`.
            _ <- Browser.use(_.session.sendUnit("Emulation.setVirtualTimePolicy", VirtualTimePolicy("advance", window.toMillis.toDouble)))
            _ <- Retry[BrowserReadException](pageWait) {
                Browser.evalDouble(clock).map { now =>
                    if now >= end then Kyo.unit
                    else Abort.fail(BrowserAssertionTimedOutException(s"page clock at $end", now.toString))
                }
            }
        yield ()
        end for
    end idle

    "an idle docs tab does not grow its retained heap" in {
        inChrome("/latest/kyo-core/") { _ =>
            for
                _     <- Browser.waitFor(mounted)
                _     <- searchIndexLoaded
                first <- sample
                _     <- idle(idleWindow)
                last  <- sample
            yield
                val growth = last.used - first.used
                assert(
                    last.domNodes == first.domNodes,
                    s"the idle page changed shape: ${first.domNodes} -> ${last.domNodes} DOM nodes"
                )
                assert(
                    growth < growthBudget,
                    s"an idle docs tab retained ${growth / 1024} KB more after ${idleWindow.show} of page time " +
                        s"(${first.used / 1024} KB -> ${last.used / 1024} KB), " +
                        s"DOM unchanged at ${first.domNodes} nodes"
                )
            end for
        }
    }

end SiteAppHeapTest
