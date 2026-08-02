package kyo

import kyo.Browser.*

/** Regression guard for the socket-churn fix (Windows WSAENOBUFS / error 10055): every withUI leaf must be served from
  * the ONE shared HttpServer, not a fresh ephemeral server per leaf. Asserted structurally (all leaves land on a single
  * origin), which is deterministic and cross-platform, rather than by reproducing the actual socket exhaustion (which is
  * Windows-specific, slow, and flaky). Reverting withUI to a per-leaf `HttpServer.init(0, ...)` gives each leaf a distinct
  * port and fails this test.
  */
class SharedUIServerTest extends UITest:

    "every withUI leaf is served from one shared origin (no per-leaf server churn)" in {
        AtomicRef.init(Set.empty[String]).map { seen =>
            Kyo.foreach(Chunk(1, 2, 3, 4, 5)) { i =>
                withUI(UI.div(s"leaf-$i").id("m")) {
                    for
                        _   <- Browser.assertText(Selector.id("m"), s"leaf-$i") // content swaps per leaf on the shared server
                        cur <- Browser.url
                        _   <- seen.updateAndGet(_ + cur)
                    yield ()
                }
            }.andThen {
                seen.get.map { urls =>
                    assert(
                        urls.size == 1,
                        s"expected all leaves served from one shared origin; per-leaf server churn would give one URL per leaf. saw ${urls.size}: $urls"
                    )
                }
            }
        }
    }

end SharedUIServerTest
