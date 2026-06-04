package kyo

import kyo.internal.BrowserTab
import kyo.internal.CdpBackend
import kyo.internal.CdpClient
import kyo.internal.CdpEvent
import kyo.internal.CdpTypes.*
import kyo.internal.ScreencastFrameAckParams
import kyo.internal.ScreencastFrameMetadata
import kyo.internal.ScreencastFrameWire
import kyo.internal.StartScreencastParams

/** Behavioral tests for Phase-5 screencast and console event routing.
  *
  * Tests 1, 3, 4, 5, and 6 are pure unit tests (no Chrome required). Test 2 is Chrome-backed and verifies the full ack loop.
  *
  * Pins: INV-005, PRE-008.
  */
class BrowserScreencastTest extends BrowserTest:

    override def timeout = 90.seconds

    // CanEqual for Exchange.Message pattern matches in tests 3 and 6.
    given CanEqual[Exchange.Message[Int, String, CdpEvent], Exchange.Message[Int, String, CdpEvent]] =
        CanEqual.derived

    // -------------------------------------------------------------------------
    // Test 1: whitelist membership (pure, no Chrome)
    // -------------------------------------------------------------------------

    "eventWhitelist contains Page.screencastFrame, Runtime.consoleAPICalled, Runtime.exceptionThrown" in run {
        assert(CdpClient.eventWhitelist.contains("Page.screencastFrame"))
        assert(CdpClient.eventWhitelist.contains("Runtime.consoleAPICalled"))
        assert(CdpClient.eventWhitelist.contains("Runtime.exceptionThrown"))
    }

    // -------------------------------------------------------------------------
    // Test 2: registered dispatcher receives frames and the cast does not stall
    // -------------------------------------------------------------------------

    // Registers a raw screencast handler on `screencastEventDispatchers`, starts a screencast on the
    // tab session, and waits until the body completes. Chrome sends `Page.screencastFrame` events on
    // the tab session; the handler acks each frame (via a detached fiber) and offers it to `frameChannel`.
    private def withRawScreencastFrames[A](
        body: Channel[ScreencastFrameWire] => A < (Browser & Async & Abort[BrowserReadException])
    )(using Frame): A < (Browser & Async & Abort[BrowserReadException]) =
        Browser.use { tab =>
            val session = tab.session // session-scoped client; commands carry the tab's session ID
            val sidKey  = tab.sessionId.value
            Channel.initUnscoped[ScreencastFrameWire](16).map { frameChannel =>
                val handler: CdpEvent.Generic => Unit < Sync = ev =>
                    CdpClient.parseScreencastFrame(ev) match
                        case Present((frame, sessionId)) =>
                            // Ack via a detached fiber so the reader fiber stays < Sync.
                            // The ack fiber runs concurrently; Chrome sees it promptly and
                            // keeps delivering frames. BrowserReadException swallowed so
                            // the fiber does not escape into background noise.
                            Fiber.initUnscoped(
                                Abort.run[BrowserReadException](
                                    CdpBackend.screencastFrameAck(session, ScreencastFrameAckParams(sessionId))
                                ).unit
                            ).andThen(Abort.run[Closed](frameChannel.offer(frame)).unit)
                        case Absent =>
                            Kyo.unit
                // Dispatcher maps live on the root client that all sessions share.
                session.screencastEventDispatchers.getAndUpdate(_.update(sidKey, handler)).map { previousMap =>
                    val restoreDispatcher = session.screencastEventDispatchers.getAndUpdate { m =>
                        previousMap.get(sidKey) match
                            case Present(prev) => m.update(sidKey, prev)
                            case Absent        => m.remove(sidKey)
                    }.unit
                    Scope.run {
                        Scope.ensure(restoreDispatcher).andThen(Scope.ensure(frameChannel.close.unit)).andThen {
                            CdpBackend.startScreencast(
                                session,
                                StartScreencastParams(quality = Present(50), everyNthFrame = Present(1))
                            ).andThen(body(frameChannel))
                        }
                    }
                }
            }
        }

    "a registered screencast dispatcher receives frames and the cast does not stall" in run {
        withBrowser {
            val html = page(
                """<html><head><style>
                  |@keyframes spin { 0% { transform: rotate(0deg); } 100% { transform: rotate(360deg); } }
                  |#box { width: 40px; height: 40px; background: red; animation: spin 0.1s linear infinite; }
                  |</style></head><body><div id="box"></div></body></html>""".stripMargin
            )
            Browser.goto(html).andThen {
                withRawScreencastFrames { frameChannel =>
                    // Two successful takes prove the ack loop delivered at least 2 frames.
                    Abort.run[Closed](frameChannel.take.andThen(frameChannel.take)).map {
                        case Result.Success(_) => succeed
                        case Result.Failure(_) => fail("Channel closed before two frames arrived")
                    }
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Test 3: no dispatcher registered, whitelisted event is pushed not dropped
    // -------------------------------------------------------------------------

    "with no dispatcher a whitelisted screencast event is pushed not dropped" in run {
        val wire =
            """{"method":"Page.screencastFrame","params":{"data":"AAAA","metadata":{"scrollOffsetX":0,"scrollOffsetY":0},"sessionId":1}}"""
        for
            handlers              <- AtomicRef.init[Dict[String, (Boolean, String)]](Dict.empty)
            queue                 <- Channel.initUnscoped[(Boolean, String, Maybe[SessionId])](16)
            frameDispatchers      <- AtomicRef.init[Dict[String, CdpEvent.Generic => Unit < Sync]](Dict.empty)
            downloadDispatchers   <- AtomicRef.init[Dict[String, CdpEvent.Generic => Unit < Sync]](Dict.empty)
            screencastDispatchers <- AtomicRef.init[Dict[String, CdpEvent.Generic => Unit < Sync]](Dict.empty)
            consoleDispatchers    <- AtomicRef.init[Dict[String, CdpEvent.Generic => Unit < Sync]](Dict.empty)
            recorders             <- AtomicRef.init[Dict[String, AtomicRef[Chunk[Browser.DialogEvent]]]](Dict.empty)
            result <- CdpClient.decodeCdpMessage(
                wire,
                handlers,
                queue,
                frameDispatchers,
                downloadDispatchers,
                screencastDispatchers,
                consoleDispatchers,
                recorders
            )
        yield result match
            case Exchange.Message.Push(ev: CdpEvent.Generic) =>
                assert(ev.method == "Page.screencastFrame", s"method mismatch: ${ev.method}")
            case other =>
                fail(s"Expected Push for unregistered dispatcher but got: $other")
        end for
    }

    // -------------------------------------------------------------------------
    // Test 4: parseScreencastFrame returns Absent on wrong-method event (pure)
    // -------------------------------------------------------------------------

    "parseScreencastFrame returns Absent on a wrong-method event" in run {
        val ev = CdpEvent.Generic(method = "Page.loadEventFired", paramsJson = "{}", sessionId = Absent)
        assert(CdpClient.parseScreencastFrame(ev) == Absent)
    }

    // -------------------------------------------------------------------------
    // Test 5: parseScreencastFrame returns Absent on malformed params (pure)
    // -------------------------------------------------------------------------

    "parseScreencastFrame returns Absent on malformed params JSON" in run {
        val ev = CdpEvent.Generic(method = "Page.screencastFrame", paramsJson = "not-valid-json", sessionId = Absent)
        assert(CdpClient.parseScreencastFrame(ev) == Absent)
    }

    // -------------------------------------------------------------------------
    // Test 6: dispatcher handler swallows Abort[Closed] and never blocks reader
    // -------------------------------------------------------------------------

    "dispatcher handler swallows Abort[Closed] and never blocks the reader" in run {
        val ev = CdpEvent.Generic("Page.screencastFrame", "{}", Absent)
        // Create a channel and immediately close it so any offer inside the handler raises Abort[Closed].
        Channel.initUnscoped[ScreencastFrameWire](1).map { closedChannel =>
            closedChannel.close.andThen {
                // Build a handler that matches the dispatcher type CdpEvent.Generic => Unit < Sync.
                // The Abort.run[Closed](...).unit pattern is the same one used by onDownload.
                // If Abort[Closed] were not swallowed, the lambda type would not unify to Unit < Sync.
                val handler: CdpEvent.Generic => Unit < Sync = _ =>
                    Abort.run[Closed](closedChannel.offer(
                        ScreencastFrameWire("AAAA", ScreencastFrameMetadata(0.0, 0.0), 1)
                    )).unit
                // Invoke the handler and assert the result is Unit (no Abort leaked to the caller).
                handler(ev).map { _ =>
                    succeed
                }
            }
        }
    }

end BrowserScreencastTest
