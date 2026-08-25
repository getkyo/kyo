package kyo.internal

import kyo.*
import kyo.internal.BrowserSnapshot.BrowserSnapshot as Snapshot

class BrowserSnapshotConfigLocalTest extends kyo.BrowserTest:

    override def timeout = 60.seconds

    // BrowserSnapshot.restoreSnapshot must consult the active Browser.configLocal when choosing the
    // loadSchedule, so a caller running `Browser.withConfig(_.loadSchedule(faster))` actually gets the
    // faster schedule for its internal waitForLoad.
    //
    // Test strategy (no wall-clock): the page pins document.readyState to "loading" and counts each read into
    // window.__probes. waitForLoad polls readyState via Retry(loadSchedule), so it never sees "complete" and runs as
    // many probes as the schedule permits (~ maxDuration / interval), set by the schedule not by time. A wider
    // maxDuration must probe strictly more than a tighter one; if restoreSnapshot ignored configLocal the counts would
    // match. Each restore navigates fresh, resetting the counter, so the two reads are independent.
    "restoreSnapshot consults Browser.configLocal.loadSchedule (does not hardcode the default)" in {
        withBrowserOnLocalhost {
            readyStateStuckServer { (host, port) =>
                Browser.use { tab =>
                    val targetUrl = s"http://$host:$port/page"
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
                    def probesUnder(cap: Duration): Int < (Browser & Async & Abort[BrowserReadException]) =
                        Browser.withConfig(_.loadSchedule(Schedule.fixed(10.millis).maxDuration(cap))) {
                            Abort.run[BrowserReadException](BrowserSnapshot.restoreSnapshot(tab, snap)).andThen(
                                Browser.eval("String(window.__probes || 0)").map(_.trim.toInt)
                            )
                        }
                    for
                        tight <- probesUnder(50.millis)
                        wide  <- probesUnder(300.millis)
                    yield assert(
                        tight < wide,
                        s"restoreSnapshot must use configLocal.loadSchedule: a wider maxDuration must probe readyState " +
                            s"more than a tighter one, but tight=$tight wide=$wide"
                    )
                    end for
                }
            }
        }
    }

    // Helper: serves a /page that pins document.readyState to "loading" and counts each read into window.__probes.
    // Overriding the instance accessor shadows the inherited Document.prototype getter; Chrome's own load tracking is
    // unaffected (Page.navigate still commits), but waitForLoad reads the JS readyState, never sees "complete", and
    // exhausts its schedule, leaving window.__probes equal to the retries the schedule permitted.
    private def readyStateStuckServer[A, S](f: (String, Int) => A < (Browser & S))(using
        Frame
    ): A < (Browser & Scope & Abort[BrowserConnectionException] & Abort[HttpBindException] & Async & S) =
        val pageBytes = Span.fromUnsafe(
            """<html><head><script>
              |  window.__probes = 0;
              |  Object.defineProperty(document, 'readyState', {
              |    configurable: true,
              |    get() { window.__probes = (window.__probes || 0) + 1; return 'loading'; }
              |  });
              |</script></head><body>stuck</body></html>""".stripMargin.getBytes("UTF-8")
        )
        val pageHandler = HttpRoute.getRaw("/page").response(_.bodyBinary).handler { _ =>
            HttpResponse.ok(pageBytes).addHeader("Content-Type", "text/html; charset=utf-8")
        }
        withLocalhostServer(pageHandler)(f)
    end readyStateStuckServer

end BrowserSnapshotConfigLocalTest
