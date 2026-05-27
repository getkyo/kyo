package kyo.internal

import kyo.*
import kyo.internal.BrowserSnapshot.BrowserSnapshot as Snapshot

class BrowserSnapshotConfigLocalTest extends kyo.BrowserTest:

    override def timeout = 60.seconds

    // BrowserSnapshot.restoreSnapshot must consult the active Browser.configLocal when choosing the
    // loadSchedule, so a caller running `Browser.withConfig(_.loadSchedule(faster))` actually gets the
    // faster schedule.
    //
    // Test strategy: snapshot a URL whose page never reaches readyState=complete (a slow-image fixture
    // similar to NavigationWatcherTest.slowImageServer, but tuned so the image never finishes within the
    // test budget). restoreSnapshot's internal waitForLoad call exhausts the loadSchedule's maxDuration
    // and the call surfaces a typed failure. We time the call: the configured 50ms cap must bound the
    // elapsed time well under the default 5s.
    "restoreSnapshot consults Browser.configLocal.loadSchedule (does not hardcode the default)" in run {
        withBrowserOnLocalhost {
            slowImageServer { (host, port) =>
                Browser.use { tab =>
                    val targetUrl = s"http://$host:$port/page"
                    // Build a synthetic snapshot pointing at the slow page. localStorage/sessionStorage/etc.
                    // are minimal-valid wire shapes; the snapshot's only job is to drive Page.navigate +
                    // waitForLoad, which is the path that consults loadSchedule.
                    val snap = Snapshot(
                        url = targetUrl,
                        localStorage = Dict.empty[String, String],
                        sessionStorage = Dict.empty[String, String],
                        cookies = Chunk.empty[Browser.Cookie],
                        formFields = Chunk.empty[BrowserSnapshot.FormField],
                        scrollX = 0,
                        scrollY = 0,
                        focusedSelector = "",
                        cursorPosition = ""
                    )
                    // Configured loadSchedule: 10ms tick, 50ms max. restoreSnapshot consults this cap.
                    val configuredCap = 50.millis
                    Browser.withConfig(_.loadSchedule(Schedule.fixed(10.millis).maxDuration(configuredCap))) {
                        for
                            start <- Clock.nowMonotonic
                            // restoreSnapshot may either succeed (if readyState reaches complete inside
                            // the cap, which can happen if Chrome considers the document complete despite
                            // the still-pending image) or fail (BrowserReadException from waitForLoad's
                            // retry exhaustion). Either way, the elapsed time must be bounded by the
                            // configured cap, not the 5s default.
                            res <- Abort.run[BrowserReadException](
                                BrowserSnapshot.restoreSnapshot(tab, snap)
                            )
                            end <- Clock.nowMonotonic
                        yield
                            val elapsed = end - start
                            // The configured cap is 50ms; allow a 2s ceiling for the surrounding CDP
                            // round-trips (navigate, focus restore, storage restore, cursor).
                            assert(
                                elapsed < 2.seconds,
                                s"expected restoreSnapshot to finish within 2s under configured 50ms loadSchedule cap, " +
                                    s"but took $elapsed; result=$res"
                            )
                    }
                }
            }
        }
    }

    // ---- Helpers (mirrors NavigationWatcherTest.slowImageServer; kept local so the test is self-contained
    //      and doesn't depend on a sibling test class). ----

    /** Serves a /page that references a never-resolving /slow.png. readyState reaches 'interactive' quickly but never reaches 'complete'
      * inside any reasonable test budget, exhausting the waitForLoad retry schedule.
      */
    private def slowImageServer[A, S](f: (String, Int) => A < (Browser & S))(using
        Frame
    ): A < (Browser & Scope & Abort[BrowserConnectionException] & Async & S) =
        val pageBytes = Span.fromUnsafe(
            """<html><head></head><body><img src="/slow.png" alt=""></body></html>""".getBytes("UTF-8")
        )
        val pageHandler = HttpRoute.getRaw("/page").response(_.bodyBinary).handler { _ =>
            HttpResponse.ok(pageBytes).addHeader("Content-Type", "text/html; charset=utf-8")
        }
        // Never returns within the test budget: the image hang stalls readyState=complete.
        val pngHandler = HttpRoute.getRaw("/slow.png").response(_.bodyBinary).handler { _ =>
            Async.sleep(60.seconds).andThen(HttpResponse.ok(Span.fromUnsafe(Array.empty[Byte])))
        }
        withLocalhostServer(pageHandler, pngHandler)(f)
    end slowImageServer

end BrowserSnapshotConfigLocalTest
