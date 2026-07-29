package kyo.internal.cdp

import kyo.*
import kyo.internal.CdpBackend
import kyo.internal.SharedChrome
import kyo.internal.cdp.PageDownload

class PageDownloadTest extends kyo.BrowserTest:

    override def timeout = 60.seconds

    // PageDownload.Behavior wire mappings are correct for all three enum cases.
    "Behavior.Allow.wire returns 'allow'" in {
        assert(PageDownload.Behavior.Allow.wire == "allow")
    }

    "Behavior.Deny.wire returns 'deny'" in {
        assert(PageDownload.Behavior.Deny.wire == "deny")
    }

    "Behavior.Default.wire returns 'default'" in {
        assert(PageDownload.Behavior.Default.wire == "default")
    }

    // Chrome's Windows download target determination silently never finalizes a download whose
    // downloadPath carries forward slashes; the wire boundary must hand Chrome native separators.
    "nativeDownloadPath converts separators on Windows" in {
        assert(PageDownload.nativeDownloadPath(System.OS.Windows, "C:/Users/me/dl") == "C:\\Users\\me\\dl")
    }

    "nativeDownloadPath keeps backslashes on Windows" in {
        assert(PageDownload.nativeDownloadPath(System.OS.Windows, "C:\\Users\\me\\dl") == "C:\\Users\\me\\dl")
    }

    "nativeDownloadPath passes through unchanged off Windows" in {
        assert(PageDownload.nativeDownloadPath(System.OS.Linux, "/tmp/dl") == "/tmp/dl")
        assert(PageDownload.nativeDownloadPath(System.OS.MacOS, "/tmp/dl") == "/tmp/dl")
    }

    // Rewired from session.exchange.events (removed with the old CdpClient)
    // to Browser.onDownload (the production subscription API using CdpBackend.downloadEventDispatchers).

    "setDownloadBehavior(Allow) causes CDP to emit Page.downloadWillBegin on a download" in {
        withBrowser {
            for
                tempPath <- Path.tempDir("kyo-cdp-test-will-begin-")
                tempDir = tempPath.toString
                now <- Clock.nowMonotonic
                unique  = s"kyo-dl-${now.toNanos}.txt"
                dataUrl = s"data:text/plain,hello"
                html    = page(s"""<a id='dl' href='$dataUrl' download='$unique'>dl</a>""")
                _      <- Browser.allowDownloads(tempDir)
                done   <- Promise.init[Unit, Any]
                events <- AtomicRef.init(Chunk.empty[Browser.DownloadEvent])
                // Subscribe to download events using the production API; rewired from deleted session.exchange.events.
                _ <- Browser.onDownload(captureEvents(events, done, unique, completeOnWillBegin = true)) {
                    Browser.goto(html).andThen(Browser.click(Browser.Selector.id("dl"))).andThen(done.get)
                }
                captured <- events.get
            yield assert(
                captured.exists {
                    case Browser.DownloadEvent.WillBegin(_, _, fn) => fn.contains(unique)
                    case _                                         => false
                },
                s"expected a WillBegin event mentioning $unique but got: $captured"
            )
        }
    }

    "downloadWillBegin and downloadProgress share the same guid and reach state=completed" in {
        withBrowser {
            for
                tempPath <- Path.tempDir("kyo-cdp-test-guid-")
                tempDir = tempPath.toString
                now <- Clock.nowMonotonic
                unique  = s"kyo-dl-${now.toNanos}.txt"
                dataUrl = s"data:text/plain,hello-world-content"
                html    = page(s"""<a id='dl' href='$dataUrl' download='$unique'>dl</a>""")
                _      <- Browser.allowDownloads(tempDir)
                done   <- Promise.init[Unit, Any]
                events <- AtomicRef.init(Chunk.empty[Browser.DownloadEvent])
                _ <- Browser.onDownload(captureEvents(events, done, unique, completeOnWillBegin = false)) {
                    Browser.goto(html).andThen(Browser.click(Browser.Selector.id("dl"))).andThen(done.get)
                }
                captured <- events.get
            yield
                val willBeginOpt = captured.collectFirst { case wb: Browser.DownloadEvent.WillBegin => wb }
                val completedOpt = captured.collectFirst {
                    case Browser.DownloadEvent.Progress(guid, _, _, "completed") => guid
                }
                willBeginOpt match
                    case Some(wb) =>
                        completedOpt match
                            case Some(completedGuid) =>
                                assert(
                                    wb.guid == completedGuid,
                                    s"WillBegin guid ${wb.guid} must match completed Progress guid $completedGuid"
                                )
                            case None =>
                                fail(s"No completed Progress event found; captured: $captured")
                    case None =>
                        fail(s"No WillBegin event found; captured: $captured")
                end match
        }
    }

    // The PageDownload wrapper must surface BrowserConnectionException as a typed Abort (never a raw string / panic) when the
    // protocol call fails; e.g. issued against a CdpClient whose WebSocket has been closed.
    "setDownloadBehavior propagates BrowserConnectionException via typed Abort on a closed client" in {
        SharedChrome.init.map { wsUrl =>
            for
                client <- CdpBackend.initUnscoped(wsUrl, Browser.LaunchConfig.default)
                _      <- client.close(30.seconds)
                result <- Abort.run[BrowserConnectionException](
                    PageDownload.setDownloadBehavior(client, PageDownload.Behavior.Default, Absent)
                )
            yield result match
                case Result.Failure(_: BrowserConnectionException) =>
                    succeed("PageDownload.setDownloadBehavior on a closed client surfaces as a typed BrowserConnectionException")
                case other => fail(s"expected PageDownload wrapper to fail with BrowserConnectionException but got $other")
            end for
        }
    }

    // --- helpers ---

    /** Captures download events into `events`; completes `done` when the test's terminal condition is met.
      *
      * With `completeOnWillBegin = true`, a `WillBegin` event matching `uniqueFilename` marks done; otherwise only a `Progress` with
      * state=completed does. The guid-match test MUST wait for the completed event: `WillBegin` always arrives first, and completing on
      * it tears the `onDownload` subscription down in a race with the completed `Progress` (lost reliably on Windows, where download
      * finalization takes a few extra milliseconds).
      */
    private def captureEvents(
        events: AtomicRef[Chunk[Browser.DownloadEvent]],
        done: Promise[Unit, Any],
        uniqueFilename: String,
        completeOnWillBegin: Boolean
    )(using Frame): Browser.DownloadEvent => Unit < Sync =
        ev =>
            events.updateAndGet(_ :+ ev).andThen {
                ev match
                    case Browser.DownloadEvent.WillBegin(_, _, fn) if completeOnWillBegin && fn.contains(uniqueFilename) =>
                        done.complete(Result.succeed(())).unit
                    case Browser.DownloadEvent.Progress(_, _, _, "completed") =>
                        done.complete(Result.succeed(())).unit
                    case _ => Kyo.unit
            }

end PageDownloadTest
