package kyo.internal.cdp

import kyo.*
import kyo.internal.CdpClient
import kyo.internal.CdpEvent
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

    // `PageDownload.setDownloadBehavior(client, Allow, Present(tempDir))` causes CDP to emit
    // `Page.downloadWillBegin` when a download is triggered.
    "setDownloadBehavior(Allow) causes CDP to emit Page.downloadWillBegin on a download" in run {
        withBrowser {
            Browser.use { tab =>
                val session = tab.client.withSession(tab.sessionId)
                val dataUrl = s"data:text/plain,hello"
                for
                    tempPath <- Path.tempDir("kyo-cdp-test-")
                    tempDir = tempPath.toString
                    now <- Clock.nowMonotonic
                    unique = s"kyo-dl-${now.toNanos}.txt"
                    html   = page(s"""<a id='dl' href='$dataUrl' download='$unique'>dl</a>""")
                    _ <- session.sendUnit("Page.enable")
                    _ <- PageDownload.setDownloadBehavior(session, PageDownload.Behavior.Allow, Present(tempDir))
                    _ <- Browser.goto(html)
                    // Fork the event waiter BEFORE triggering the click, so the stream subscribes ahead
                    // of Chrome emitting `Page.downloadWillBegin`.
                    eventPromise <- Promise.init[CdpEvent, Any]
                    collector <- Fiber.init {
                        session.exchange.events.foreach { (e: CdpEvent) =>
                            e match
                                case g @ CdpEvent.Generic("Page.downloadWillBegin", p, _) if p.contains(unique) =>
                                    eventPromise.complete(Result.succeed(g)).unit
                                case _ => Kyo.unit
                        }
                    }
                    _         <- Browser.click(Browser.Selector.id("dl"))
                    willBegin <- eventPromise.get
                    _         <- collector.interrupt
                yield willBegin match
                    case CdpEvent.Generic(method, paramsJson, _) =>
                        assert(method == "Page.downloadWillBegin", s"expected method=Page.downloadWillBegin but got $method")
                        assert(paramsJson.contains(unique), s"expected params to mention $unique but got $paramsJson")
                end for
            }
        }
    }

    // downloadWillBegin carries a `guid`; a subsequent downloadProgress event with the same `guid`
    // and `state = "completed"` eventually arrives.
    "downloadWillBegin and downloadProgress share the same guid and reach state=completed" in run {
        withBrowser {
            Browser.use { tab =>
                val session = tab.client.withSession(tab.sessionId)
                val dataUrl = s"data:text/plain,hello-world-content"
                for
                    tempPath <- Path.tempDir("kyo-cdp-test-")
                    tempDir = tempPath.toString
                    now <- Clock.nowMonotonic
                    unique = s"kyo-dl-${now.toNanos}.txt"
                    html   = page(s"""<a id='dl' href='$dataUrl' download='$unique'>dl</a>""")
                    _ <- session.sendUnit("Page.enable")
                    _ <- PageDownload.setDownloadBehavior(session, PageDownload.Behavior.Allow, Present(tempDir))
                    _ <- Browser.goto(html)
                    // Fork a collector that accumulates relevant events into an atomic list, completing
                    // a promise when a `downloadProgress` with `state=completed` arrives. A single stream
                    // subscriber avoids racing consumers on the shared event channel.
                    capture     <- AtomicRef.init(Chunk.empty[CdpEvent])
                    donePromise <- Promise.init[Unit, Any]
                    collector <- Fiber.init {
                        session.exchange.events.foreach { (e: CdpEvent) =>
                            e match
                                case g @ CdpEvent.Generic("Page.downloadWillBegin", p, _) if p.contains(unique) =>
                                    capture.updateAndGet(_ :+ g).unit
                                case g @ CdpEvent.Generic("Page.downloadProgress", p, _) =>
                                    capture.updateAndGet(_ :+ g).andThen {
                                        if extractJsonString(p, "state").contains("completed") then
                                            donePromise.complete(Result.succeed(())).unit
                                        else Kyo.unit
                                    }
                                case _ => Kyo.unit
                        }
                    }
                    _        <- Browser.click(Browser.Selector.id("dl"))
                    _        <- donePromise.get
                    captured <- capture.get
                    _        <- collector.interrupt
                yield
                    val willBegin = captured.collectFirst {
                        case g @ CdpEvent.Generic("Page.downloadWillBegin", p, _) if p.contains(unique) => g
                    }
                    assert(willBegin.isDefined, s"expected a downloadWillBegin event referencing $unique but got $captured")
                    val beginGuid = extractJsonString(willBegin.get.paramsJson, "guid")
                    assert(beginGuid.isDefined, s"expected guid in downloadWillBegin but got ${willBegin.get.paramsJson}")
                    val guid = beginGuid.get
                    val progressMatching = captured.collect {
                        case CdpEvent.Generic("Page.downloadProgress", p, _) if p.contains(guid) => p
                    }
                    assert(progressMatching.nonEmpty, s"expected downloadProgress with guid=$guid but got $captured")
                    val states = progressMatching.flatMap(p => extractJsonString(p, "state"))
                    assert(
                        states.exists(_ == "completed"),
                        s"expected a downloadProgress with state=completed for guid=$guid but got states=$states"
                    )
                end for
            }
        }
    }

    // The PageDownload wrapper must surface BrowserConnectionException as a typed Abort (never a raw string / panic) when the
    // protocol call fails; e.g. issued against a CdpClient whose WebSocket has been closed.
    "setDownloadBehavior propagates BrowserConnectionException via typed Abort on a closed client" in run {
        SharedChrome.init.map { wsUrl =>
            for
                client <- CdpClient.initUnscoped(wsUrl, Browser.LaunchConfig.default)
                _      <- client.close(30.seconds)
                result <- Abort.run[BrowserConnectionException](
                    PageDownload.setDownloadBehavior(client, PageDownload.Behavior.Default, Absent)
                )
            yield result match
                case Result.Failure(_: BrowserConnectionException) => succeed
                case other => fail(s"expected PageDownload wrapper to fail with BrowserConnectionException but got $other")
            end for
        }
    }

    // --- helpers ---

    /** Minimal typed wire shape for the `state` / `guid` fields the test cares about in `Page.downloadProgress` and
      * `Page.downloadWillBegin` events. The full wire (the whole CDP frame) is decoded as [[kyo.internal.CdpEventParams]]
      * wrapping this shape; missing fields collapse to defaults.
      */
    private case class DownloadProgressParams(guid: String = "", state: String = "") derives Schema

    /** Extracts the `state` field of a `Page.downloadProgress` event from the dispatcher-carried wire string. */
    private def extractJsonString(wire: String, field: String)(using Frame): Maybe[String] =
        Json.decode[kyo.internal.CdpEventParams[DownloadProgressParams]](wire) match
            case Result.Success(env) =>
                val v = field match
                    case "state" => env.params.state
                    case "guid"  => env.params.guid
                    case _       => ""
                if v.isEmpty then Absent else Present(v)
            case _ => Absent

end PageDownloadTest
