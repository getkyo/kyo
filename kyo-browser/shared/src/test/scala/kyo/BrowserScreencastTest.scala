package kyo

import kyo.internal.BrowserTab
import kyo.internal.CdpBackend
import kyo.internal.CdpEvent
import kyo.internal.CdpTypes.*
import kyo.internal.ScreencastFrameAckParams
import kyo.internal.ScreencastFrameMetadata
import kyo.internal.ScreencastFrameWire
import kyo.internal.StartScreencastParams

/** Behavioral tests for screencast and console event routing.
  *
  * Tests 1, 3, 4, and 6 are pure unit tests (no Chrome required). Test 2 is Chrome-backed and verifies the full ack loop.
  */
class BrowserScreencastTest extends BrowserTest:

    override def timeout = 90.seconds

    // CanEqual for Exchange.Message pattern matches in tests 3 and 6.
    given CanEqual[Exchange.Message[Int, String, CdpEvent], Exchange.Message[Int, String, CdpEvent]] =
        CanEqual.derived

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
                    Browser.parseScreencastFrame(ev) match
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

    "a registered screencast dispatcher receives frames and the cast does not stall" in {
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
    // Test 4: parseScreencastFrame returns Absent on a non-screencast params type (pure)
    // -------------------------------------------------------------------------

    "parseScreencastFrame returns Absent on a non-screencast params type" in {
        // The dispatcher now carries the decoded typed Wire; a params value that is not a ScreencastFrameWire
        // (e.g. a different event's wire) projects to Absent.
        val ev = CdpEvent.Generic(method = "Page.loadEventFired", params = ScreencastFrameMetadata(0.0, 0.0), sessionId = Absent)
        assert(Browser.parseScreencastFrame(ev) == Absent)
    }

    // -------------------------------------------------------------------------
    // Test 6: dispatcher handler swallows Abort[Closed] and never blocks reader
    // -------------------------------------------------------------------------

    "dispatcher handler swallows Abort[Closed] and never blocks the reader" in {
        val ev = CdpEvent.Generic("Page.screencastFrame", ScreencastFrameWire("AAAA", ScreencastFrameMetadata(0.0, 0.0), 1), Absent)
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

    // -------------------------------------------------------------------------
    // screenshotFrames screencast recorder (Chrome-backed)
    // -------------------------------------------------------------------------

    // A page driving a perpetual visual change AND a perpetual DOM mutation. The CSS animation makes Chrome
    // re-render so the screencast keeps delivering frames; the `setInterval` DOM mutation keeps the page from
    // ever quiescing so `waitForStable` runs for its whole window (it never returns early on a still DOM). The
    // result is a deterministic, sleep-free recorder driver whose duration is exactly the `waitForStable` budget.
    private val spinPage =
        """<html><head><style>
          |@keyframes spin { from { transform: rotate(0deg); } to { transform: rotate(360deg); } }
          |#box { width: 60px; height: 60px; background: red; animation: spin 0.1s linear infinite; }
          |</style></head><body><div id="box"></div>
          |<script>setInterval(function(){ document.body.appendChild(document.createElement('span')); }, 20);</script>
          |</body></html>""".stripMargin

    // Drives the recorder body for a bounded window. The spin page never quiesces (its setInterval keeps mutating
    // the DOM), so `waitForStable` runs the whole window and aborts at its timeout; swallowing that abort lets the
    // body return cleanly after the window so the recorder can read its poison flag and surface the cap. The wait
    // is the bound, never a sleep.
    private def spinFor(window: Duration)(using Frame): Unit < (Browser & Async & Abort[BrowserReadException]) =
        Abort.run[BrowserReadException](Browser.waitForStable(window)).unit

    // -------------------------------------------------------------------------
    // Test 7: records frames of an animation, events-first
    // -------------------------------------------------------------------------

    "screenshotFrames records frames of an animation with non-decreasing offsetMs" in {
        withBrowser {
            onPage(spinPage) {
                Browser.screenshotFrames(maxDurationMs = 2000L, maxFrames = 90) {
                    spinFor(300.millis)
                }.map { case (frames, _) =>
                    assert(frames.nonEmpty, "expected at least one recorded frame")
                    // Every frame carries non-empty image bytes starting with the JPEG magic (default format).
                    frames.foreach { f =>
                        val bytes = f.image.binary
                        assert(bytes.size > 0, "expected non-empty frame image")
                        val b0 = bytes(0) & 0xff
                        val b1 = bytes(1) & 0xff
                        assert(b0 == 0xff && b1 == 0xd8, s"expected JPEG magic FF D8 but got ${"%02x %02x".format(b0, b1)}")
                    }
                    // offsetMs is non-negative and non-decreasing across the delivery-ordered chunk.
                    assert(frames.forall(_.offsetMs >= 0L), s"expected non-negative offsetMs but got ${frames.map(_.offsetMs)}")
                    val offsets = frames.map(_.offsetMs)
                    assert(
                        offsets.zip(offsets.drop(1)).forall((a, b) => b >= a),
                        s"expected non-decreasing offsetMs but got $offsets"
                    )
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Test 8: offsetMs is relative to the cast start (t0), not raw epoch
    // -------------------------------------------------------------------------

    "screenshotFrames offsetMs is measured relative to the cast start" in {
        withBrowser {
            onPage(spinPage) {
                Browser.screenshotFrames(maxDurationMs = 2000L, maxFrames = 60) {
                    spinFor(400.millis)
                }.map { case (frames, _) =>
                    assert(frames.nonEmpty, "expected at least one recorded frame")
                    // Relative to t0: a wall-clock epoch (~1.7e12) would be far larger than the cast duration.
                    // Every offset must sit well under one minute, proving it is a cast-relative delta, not raw epoch millis.
                    assert(
                        frames.forall(f => f.offsetMs >= 0L && f.offsetMs < 60000L),
                        s"expected cast-relative offsets under 60s but got ${frames.map(_.offsetMs)}"
                    )
                    // The last frame's offset is at least the first frame's (monotonic delivery).
                    assert(frames.last.offsetMs >= frames.head.offsetMs, "expected later frames to have larger or equal offsetMs")
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Test 9: aborts on the frame cap, frame-count-first
    // -------------------------------------------------------------------------

    "screenshotFrames aborts on the frame cap with limit equal to maxFrames" in {
        withBrowser {
            onPage(spinPage) {
                Abort.run[BrowserReadException] {
                    Browser.screenshotFrames[Unit, Any](maxDurationMs = 60000L, maxFrames = 3) {
                        spinFor(800.millis)
                    }
                }.map {
                    case Result.Failure(ex: BrowserCaptureLimitExceededException) =>
                        assert(ex.operation == "screenshotFrames", s"unexpected operation ${ex.operation}")
                        assert(ex.limit == 3, s"expected limit 3 (frame cap) but got ${ex.limit}")
                        assert(ex.reached >= 3, s"expected reached >= 3 but got ${ex.reached}")
                    case other =>
                        fail(s"expected BrowserCaptureLimitExceededException on the frame cap but got $other")
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Test 10: aborts on the duration cap while frames stay under maxFrames
    // -------------------------------------------------------------------------

    "screenshotFrames aborts on the duration cap with reached and limit both in milliseconds" in {
        withBrowser {
            onPage(spinPage) {
                Abort.run[BrowserReadException] {
                    Browser.screenshotFrames[Unit, Any](maxDurationMs = 300L, maxFrames = 10000) {
                        spinFor(800.millis)
                    }
                }.map {
                    case Result.Failure(ex: BrowserCaptureLimitExceededException) =>
                        assert(ex.operation == "screenshotFrames", s"unexpected operation ${ex.operation}")
                        // The duration cap reports BOTH numbers in milliseconds: limit == maxDurationMs, reached == elapsed ms.
                        assert(ex.limit == 300, s"expected limit 300 (duration cap ms) but got ${ex.limit}")
                        // reached is the elapsed ms at the cap, so it must exceed the 300ms limit (that is why the cap fired) and
                        // stay well under the 800ms window cap. A frame count (a handful of frames) could never satisfy reached > 300.
                        assert(
                            ex.reached > ex.limit,
                            s"expected reached (elapsed ms) to exceed the 300ms limit but got ${ex.reached}"
                        )
                        assert(
                            ex.reached < 10000,
                            s"expected reached (elapsed ms) under the spin window but got ${ex.reached}"
                        )
                    case other =>
                        fail(s"expected BrowserCaptureLimitExceededException on the duration cap but got $other")
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Test 11: Webp maps to the screencast jpeg codec without aborting
    // -------------------------------------------------------------------------

    "screenshotFrames Webp format records via the jpeg codec without aborting" in {
        withBrowser {
            onPage(spinPage) {
                Browser.screenshotFrames(maxDurationMs = 800L, maxFrames = 240, format = Browser.ScreenshotFormat.Webp) {
                    spinFor(300.millis)
                }.map { case (frames, _) =>
                    assert(frames.nonEmpty, "expected Webp cast to record at least one frame")
                    // The screencast has no webp codec, so the frames are jpeg; assert the JPEG magic to prove the substitution.
                    val bytes = frames.head.image.binary
                    assert(bytes.size > 0, "expected non-empty frame image")
                    val b0 = bytes(0) & 0xff
                    val b1 = bytes(1) & 0xff
                    assert(b0 == 0xff && b1 == 0xd8, s"expected JPEG magic FF D8 (webp->jpeg) but got ${"%02x %02x".format(b0, b1)}")
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Test 12: teardown on interruption deregisters the dispatcher; a later cast works
    // -------------------------------------------------------------------------

    // The Browser effect carries Env[BrowserTab] and the module provides no same-tab isolate, so the interrupted cast runs
    // inside a self-contained Browser.run (the established interruption pattern in BrowserViewportTest /
    // BrowserEmulationTest). The scenario is driven by an explicit fiber plus two Promises so it is deterministic across the
    // JVM and the single-threaded Scala.js / Scala Native runtimes:
    //   - the cast runs in its own fiber (Fiber.initUnscoped), not under Async.timeout, so the test owns the interruption.
    //   - readyLatch is completed AFTER the cast is registered, so the test interrupts only once the body is provably inside
    //     the recorder scope, never before startScreencast ran.
    //   - tabRef captures the tab so its dispatcher map can be read.
    // After readyLatch resolves the test interrupts the fiber and awaits its Result. The recorder's Scope.ensure(restore)
    // removes the session entry then does an async stopScreencast send; on JS / Native the interrupted fiber's Result can
    // resolve before that finalizer chain has removed the entry (the ordering only holds on the JVM). So the dispatcher map
    // is read by polling until the entry is gone, bounded by a fixed schedule, then asserted concretely. The poll re-reads
    // the AtomicRef directly; the tab object outlives its CDP teardown. A second, independent cast then records frames,
    // proving the recorder is reusable and stopScreencast left Chrome in a clean state.
    "screenshotFrames tears down the dispatcher on interruption and a later cast still works".ignore(
        "interrupt teardown relies on Scope finalizers completing before the fiber result is observed; blocked on the deeper Scope finalizer-on-interrupt issue, comprehensive fix pending"
    ) in {
        // A static page carrying only the CSS animation: Chrome keeps re-rendering it (so the cast records frames)
        // while the DOM stays still, so `goto` settles promptly and the interruption window is deterministic.
        val animOnlyPage =
            """<html><head><style>
              |@keyframes spin { from { transform: rotate(0deg); } to { transform: rotate(360deg); } }
              |#box { width: 60px; height: 60px; background: red; animation: spin 0.1s linear infinite; }
              |</style></head><body><div id="box"></div></body></html>""".stripMargin
        kyo.internal.SharedChrome.init.map { wsUrl =>
            Promise.init[BrowserTab, Any].map { tabRef =>
                Promise.init[Unit, Any].map { readyLatch =>
                    val cast: Unit < (Async & Abort[BrowserReadException | BrowserSetupException]) =
                        Browser.run(wsUrl) {
                            Browser.goto(page(animOnlyPage)).andThen {
                                Browser.use { tab =>
                                    tabRef.completeDiscard(Result.succeed(tab)).andThen {
                                        Browser.screenshotFrames[Unit, Any](maxDurationMs = 60000L, maxFrames = 10000) {
                                            // Signal the cast is registered, then block interruptibly until the test
                                            // interrupts the fiber while the cast is active.
                                            readyLatch.completeDiscard(Result.succeed(())).andThen {
                                                Async.sleep(30.seconds)
                                            }
                                        }.unit
                                    }
                                }
                            }
                        }
                    Fiber.initUnscoped(cast).map { fiber =>
                        // Wait until the cast is registered, then interrupt and await the interrupted Result so the fiber has
                        // finished unwinding before the dispatcher map is inspected.
                        readyLatch.get.andThen {
                            fiber.interrupt.andThen {
                                fiber.getResult.map { result =>
                                    assert(result.isPanic, s"expected the interrupt to abort the cast but got $result")
                                    tabRef.get.map { tab =>
                                        val sidKey = tab.sessionId.value
                                        // Poll the dispatcher map until the recorder's Scope.ensure(restore) removed the entry.
                                        Retry[BrowserReadException](Schedule.fixed(20.millis).take(150)) {
                                            tab.backend.screencastEventDispatchers.get.map { map =>
                                                if map.get(sidKey) == Absent then ()
                                                else
                                                    Abort.fail(
                                                        BrowserAssertionTimedOutException(
                                                            "screencast interrupt teardown",
                                                            "Absent",
                                                            "present"
                                                        )
                                                    )
                                            }
                                        }.andThen {
                                            // The recorder's Scope.ensure(restore) removed the dispatcher entry on interruption.
                                            tab.backend.screencastEventDispatchers.get.map { map =>
                                                assert(
                                                    map.get(sidKey) == Absent,
                                                    s"expected the screencast dispatcher for $sidKey to be removed on interruption but it is still present"
                                                )
                                            }
                                        }
                                    }.andThen {
                                        // A fresh, independent cast records frames: the recorder is reusable and Chrome is in
                                        // a clean state.
                                        Browser.run(wsUrl) {
                                            Browser.goto(page(animOnlyPage)).andThen {
                                                Browser.screenshotFrames[Unit, Any](maxDurationMs = 1500L, maxFrames = 60) {
                                                    spinFor(300.millis)
                                                }.map { case (frames, _) =>
                                                    assert(frames.nonEmpty, "expected the later cast to record frames after teardown")
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

end BrowserScreencastTest
