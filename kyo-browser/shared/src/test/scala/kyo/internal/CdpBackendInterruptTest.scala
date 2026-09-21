package kyo.internal

import CdpTypes.*
import kyo.*

/** Interrupts landing on the round trips a [[CdpBackend]] and the tab setup make, driven over an in-memory CDP wire whose browser side
  * this suite plays. Each leaf gates one reply, stops the caller while it waits on it, releases the reply, and reads what the browser side
  * saw afterwards: the calls a fixed caller makes on its way out, or the silence of an abandoned one.
  */
class CdpBackendInterruptTest extends BaseBrowserTest:

    private val cfg = Browser.LaunchConfig.default

    final private class Wire(val seen: AtomicRef[Chunk[String]], val replies: AtomicInt)

    private def serve(browserEnd: JsonRpcTransport, wire: Wire, gates: Map[String, Latch])(using Frame): Fiber[Unit, Any] < Sync =
        def result(method: String): Structure.Value = method match
            case "Browser.getVersion" =>
                summon[Schema[BrowserVersionResult]].toStructureValue(BrowserVersionResult("1.3", "Chrome/1", "1", "ua", "v8"))
            case "Target.createBrowserContext" =>
                summon[Schema[CreateBrowserContextResult]].toStructureValue(CreateBrowserContextResult("ctx-1"))
            case "Target.createTarget" =>
                summon[Schema[CreateTargetResult]].toStructureValue(CreateTargetResult("target-1"))
            case "Target.attachToTarget" =>
                summon[Schema[AttachResult]].toStructureValue(AttachResult("session-1"))
            case "Runtime.evaluate" =>
                // Only `screenshotElement`'s box-stable eval reaches this wire in this suite.
                summon[Schema[EvalResult]].toStructureValue(EvalResult(RemoteObject.`string`(elementClipReplyJson), Absent))
            case _ =>
                summon[Schema[CdpNoParams]].toStructureValue(CdpNoParams())
        Fiber.initUnscoped {
            Abort.run[Closed | JsonRpcError] {
                browserEnd.incoming.foreach {
                    case req: JsonRpcRequest =>
                        wire.seen.updateAndGet(_.append(req.method)).andThen {
                            gates.get(req.method) match
                                case Some(gate) => gate.await
                                case None       => Kyo.unit
                        }.andThen(browserEnd.send(JsonRpcResponse.success(req.id, result(req.method))))
                    case _: JsonRpcResponse => wire.replies.incrementAndGet.unit
                    case _                  => Kyo.unit
                }
            }.unit
        }
    end serve

    private def wired[A](gates: Map[String, Latch])(f: (JsonRpcTransport, JsonRpcTransport, Wire) => A < (Async & Abort[Any] & Scope))(using
        Frame
    ): A < (Async & Abort[Any] & Scope) =
        for
            seen    <- AtomicRef.init(Chunk.empty[String])
            replies <- AtomicInt.init(0)
            wire = new Wire(seen, replies)
            pair <- JsonRpcTransport.inMemory
            (client, browser) = pair
            server <- serve(browser, wire, gates)
            _      <- Scope.ensure(server.interrupt.andThen(client.close).andThen(browser.close))
            a      <- f(client, browser, wire)
        yield a

    private def sawEventually(wire: Wire, method: String)(using Frame, kyo.test.AssertScope): Boolean < Async =
        Abort.run[Timeout](Async.timeout(2.seconds)(assertEventually(wire.seen.get.map(_.contains(method))))).map(_.isSuccess)

    /** Whether `method` shows up at least `n` times within a bounded wait; `false` on timeout. Most sites re-send their
      * apply method for the restore, so "fired again" is a count, not a second name the way `withViewport`'s pair is.
      */
    private def sawEventuallyCount(wire: Wire, method: String, n: Int)(using Frame, kyo.test.AssertScope): Boolean < Async =
        Abort.run[Timeout](Async.timeout(1.second)(assertEventually(wire.seen.get.map(_.count(_ == method) >= n)))).map(_.isSuccess)

    /** Canned `Runtime.evaluate` reply for `screenshotElement`'s box-stable check: a resolved rect, found and stable on the
      * first sample, so `Actionability.withRetry` never loops.
      */
    private val elementClipReplyJson: String = """{"found":true,"ok":true,"x":0,"y":0,"width":10,"height":10}"""

    /** Canned `Runtime.evaluate` reply for every eval [[serveEval]] does not gate or tag: a discarded string that also
      * decodes as `MutationSettlement`'s `{"tag": ...}` quiescence-poll shape.
      */
    private val evalDoneJson: String = """{"tag":"done"}"""

    /** Browser side for the freeze/marks leaves, where apply and restore both go through `Runtime.evaluate`, so the
      * method name cannot pick out the call to gate. `gateOn` marks the call whose reply is withheld until `gate` opens
      * (recording `Runtime.evaluate:gate-hit` before waiting), `removeOn` marks the restore (recording
      * `Runtime.evaluate:remove-hit`); every other eval gets an immediate [[evalDoneJson]] reply.
      */
    private def serveEval(
        browserEnd: JsonRpcTransport,
        wire: Wire,
        gate: Latch,
        gateOn: String => Boolean,
        removeOn: String => Boolean
    )(using Frame): Fiber[Unit, Any] < Sync =
        def expressionOf(req: JsonRpcRequest): String =
            req.params match
                case Present(Structure.Value.Record(fields)) =>
                    fields.iterator.collectFirst { case ("expression", Structure.Value.Str(s)) => s }.getOrElse("")
                case _ => ""
        def replyValue(method: String): Structure.Value = method match
            case "Browser.getVersion" =>
                summon[Schema[BrowserVersionResult]].toStructureValue(BrowserVersionResult("1.3", "Chrome/1", "1", "ua", "v8"))
            case "Runtime.evaluate" =>
                summon[Schema[EvalResult]].toStructureValue(EvalResult(RemoteObject.`string`(evalDoneJson), Absent))
            case _ =>
                summon[Schema[CdpNoParams]].toStructureValue(CdpNoParams())
        Fiber.initUnscoped {
            Abort.run[Closed | JsonRpcError] {
                browserEnd.incoming.foreach {
                    case req: JsonRpcRequest if req.method == "Runtime.evaluate" =>
                        val expr   = expressionOf(req)
                        val gated  = gateOn(expr)
                        val marker =
                            if gated then Chunk(req.method, s"${req.method}:gate-hit")
                            else if removeOn(expr) then Chunk(req.method, s"${req.method}:remove-hit")
                            else Chunk(req.method)
                        wire.seen.updateAndGet(_.concat(marker)).andThen {
                            (if gated then gate.await else Kyo.unit)
                                .andThen(browserEnd.send(JsonRpcResponse.success(req.id, replyValue(req.method))))
                        }
                    case req: JsonRpcRequest =>
                        wire.seen.updateAndGet(_.append(req.method))
                            .andThen(browserEnd.send(JsonRpcResponse.success(req.id, replyValue(req.method))))
                    case _: JsonRpcResponse => wire.replies.incrementAndGet.unit
                    case _                  => Kyo.unit
                }
            }.unit
        }
    end serveEval

    private def wiredEval[A](gateOn: String => Boolean, removeOn: String => Boolean)(
        f: (JsonRpcTransport, JsonRpcTransport, Wire, Latch) => A < (Async & Abort[Any] & Scope)
    )(using Frame): A < (Async & Abort[Any] & Scope) =
        for
            seen    <- AtomicRef.init(Chunk.empty[String])
            replies <- AtomicInt.init(0)
            wire = new Wire(seen, replies)
            gate <- Latch.init(1)
            pair <- JsonRpcTransport.inMemory
            (client, browser) = pair
            server <- serveEval(browser, wire, gate, gateOn, removeOn)
            _      <- Scope.ensure(server.interrupt.andThen(client.close).andThen(browser.close))
            a      <- f(client, browser, wire, gate)
        yield a

    // The init builds the endpoint over the wire before it probes `Browser.getVersion`.
    "an interrupt landing at the version probe closes the endpoint the init built" in {
        Latch.init(1).map { gate =>
            wired(Map("Browser.getVersion" -> gate)) { (client, browser, wire) =>
                for
                    fiber <- Fiber.initUnscoped(Abort.run[BrowserReadException | BrowserSetupException](
                        Scope.run(CdpBackend.initUnscoped(client, cfg).andThen(Async.never))
                    ))
                    probed   <- sawEventually(wire, "Browser.getVersion")
                    _        <- fiber.interrupt
                    _        <- gate.release
                    _        <- fiber.getResult
                    _        <- Abort.run[Closed](browser.send(JsonRpcRequest(JsonRpcId(9001L), "Probe.ping", Absent, Absent)))
                    answered <- Abort.run[Timeout](Async.timeout(1.second)(assertEventually(wire.replies.get.map(_ > 0)))).map(_.isSuccess)
                yield
                    assert(probed, "the init never reached the version probe")
                    assert(!answered, "an endpoint nobody owns is still answering the wire after the init that built it was stopped")
                end for
            }
        }
    }

    // The probe captures the dialog queue the drainer parks on, so the orphan is observable.
    "an interrupt landing at the version probe leaves no dialog drainer parked".pendingUntilFixed(
        "the dialog drainer is spawned before the version probe and owned by the backend's close, which init never registers when the probe abandons it; the drainer stays parked on the dialog queue"
    ) in {
        val captured =
            new java.util.concurrent.atomic.AtomicReference[Maybe[Channel[(Boolean, String, Maybe[SessionId])]]](Maybe.empty)
        Latch.init(1).map { gate =>
            wired(Map("Browser.getVersion" -> gate)) { (client, browser, wire) =>
                for
                    fiber <- Fiber.initUnscoped(Abort.run[BrowserReadException | BrowserSetupException](
                        Scope.run(CdpBackend.initUnscoped(client, cfg, q => captured.set(Maybe(q))).andThen(Async.never))
                    ))
                    probed <- sawEventually(wire, "Browser.getVersion")
                    _      <- fiber.interrupt
                    _      <- gate.release
                    _      <- fiber.getResult
                    _      <- assertEventually(Sync.defer(captured.get().isDefined))
                    q = captured.get().get
                    idle <- Abort.run[Timeout | Closed](
                        Async.timeout(1.second)(assertEventually(q.pendingTakes.map(_ == 0)))
                    ).map(_.isSuccess)
                yield
                    assert(probed, "the init never reached the version probe")
                    assert(idle, "the dialog drainer is still parked on the dialog queue after the init that spawned it was stopped")
                end for
            }
        }
    }

    "an interrupt landing at the context creation reply still disposes the context" in {
        Latch.init(1).map { gate =>
            wired(Map("Target.createBrowserContext" -> gate)) { (client, _, wire) =>
                Scope.run {
                    CdpBackend.initUnscoped(client, cfg).map { backend =>
                        for
                            fiber <- Fiber.initUnscoped(Abort.run[BrowserReadException](
                                Scope.run(BrowserTabSetup.attachAndSetupTab(backend).andThen(Async.never))
                            ))
                            created  <- sawEventually(wire, "Target.createBrowserContext")
                            _        <- fiber.interrupt
                            _        <- gate.release
                            _        <- fiber.getResult
                            disposed <- sawEventually(wire, "Target.disposeBrowserContext")
                        yield
                            assert(created, "the setup never asked for a browser context")
                            assert(disposed, "the context the reply delivered was never disposed after the setup was stopped at that reply")
                        end for
                    }
                }
            }
        }
    }

    "an interrupt landing at the viewport override reply still restores the viewport" in {
        Latch.init(1).map { gate =>
            wired(Map("Emulation.setDeviceMetricsOverride" -> gate)) { (client, _, wire) =>
                Scope.run {
                    CdpBackend.initUnscoped(client, cfg).map { backend =>
                        BrowserTabSetup.mkBrowserTab(TargetId("target-1"), SessionId("session-1"), backend, Absent).map { tab =>
                            for
                                fiber <- Fiber.initUnscoped(Abort.run[BrowserReadException](
                                    Browser.runOn(tab)(
                                        Browser.withConfig(_.mutationQuiescenceWindow(Duration.Zero))(
                                            Browser.withViewport(800, 600)(Async.never)
                                        )
                                    )
                                ))
                                overridden <- sawEventually(wire, "Emulation.setDeviceMetricsOverride")
                                _          <- fiber.interrupt
                                _          <- gate.release
                                _          <- fiber.getResult
                                restored   <- sawEventually(wire, "Emulation.clearDeviceMetricsOverride")
                            yield
                                assert(overridden, "the viewport override was never sent")
                                assert(
                                    restored,
                                    "the override the reply confirmed was never cleared after the caller was stopped at that reply"
                                )
                            end for
                        }
                    }
                }
            }
        }
    }

    "an interrupt landing at the background-color override reply still clears it".pendingUntilFixed(
        "the CDP override lands on the server before its reply and Scope.acquireRelease registers the restore only when the reply lands; an interrupt at the reply strands the override with no restore"
    ) in {
        Latch.init(1).map { gate =>
            wired(Map("Emulation.setDefaultBackgroundColorOverride" -> gate)) { (client, _, wire) =>
                Scope.run {
                    CdpBackend.initUnscoped(client, cfg).map { backend =>
                        BrowserTabSetup.mkBrowserTab(TargetId("target-1"), SessionId("session-1"), backend, Absent).map { tab =>
                            for
                                fiber <- Fiber.initUnscoped(Abort.run[BrowserReadException](
                                    Browser.runOn(tab)(
                                        Browser.screenshotElement(Selector.css("body"), transparentBackground = true).unit
                                            .andThen(Async.never)
                                    )
                                ))
                                overridden <- sawEventually(wire, "Emulation.setDefaultBackgroundColorOverride")
                                _          <- fiber.interrupt
                                _          <- gate.release
                                _          <- fiber.getResult
                                cleared    <- sawEventuallyCount(wire, "Emulation.setDefaultBackgroundColorOverride", 2)
                            yield
                                assert(overridden, "the background-color override was never sent")
                                assert(
                                    cleared,
                                    "the override the reply confirmed was never cleared after the caller was stopped at that reply"
                                )
                            end for
                        }
                    }
                }
            }
        }
    }

    // `withEmulation` applies the emulated-media override through the same un-detached `Scope.acquireRelease` shape;
    // quiescence is disabled so `afterAction` sends it directly, not via a mutation observer the fake browser does not
    // answer.
    "an interrupt landing at the emulated-media override reply still restores it".pendingUntilFixed(
        "the CDP override lands on the server before its reply and Scope.acquireRelease registers the restore only when the reply lands; an interrupt at the reply strands the override with no restore"
    ) in {
        Latch.init(1).map { gate =>
            wired(Map("Emulation.setEmulatedMedia" -> gate)) { (client, _, wire) =>
                Scope.run {
                    CdpBackend.initUnscoped(client, cfg).map { backend =>
                        BrowserTabSetup.mkBrowserTab(TargetId("target-1"), SessionId("session-1"), backend, Absent).map { tab =>
                            for
                                fiber <- Fiber.initUnscoped(Abort.run[BrowserReadException](
                                    Browser.runOn(tab)(
                                        Browser.withConfig(_.mutationQuiescenceWindow(Duration.Zero))(
                                            Browser.withEmulation(colorScheme = Present(Browser.ColorScheme.Dark))(Async.never)
                                        )
                                    )
                                ))
                                overridden <- sawEventually(wire, "Emulation.setEmulatedMedia")
                                _          <- fiber.interrupt
                                _          <- gate.release
                                _          <- fiber.getResult
                                restored   <- sawEventuallyCount(wire, "Emulation.setEmulatedMedia", 2)
                            yield
                                assert(overridden, "the emulated-media override was never sent")
                                assert(
                                    restored,
                                    "the override the reply confirmed was never restored after the caller was stopped at that reply"
                                )
                            end for
                        }
                    }
                }
            }
        }
    }

    "an interrupt landing at the download-policy reply still restores it".pendingUntilFixed(
        "the CDP override lands on the server before its reply and Scope.acquireRelease registers the restore only when the reply lands; an interrupt at the reply strands the override with no restore"
    ) in {
        Latch.init(1).map { gate =>
            wired(Map("Page.setDownloadBehavior" -> gate)) { (client, _, wire) =>
                Scope.run {
                    CdpBackend.initUnscoped(client, cfg).map { backend =>
                        BrowserTabSetup.mkBrowserTab(TargetId("target-1"), SessionId("session-1"), backend, Absent).map { tab =>
                            for
                                fiber <- Fiber.initUnscoped(Abort.run[BrowserReadException](
                                    Browser.runOn(tab)(
                                        Browser.withDownloads("/tmp/kyo-browser-interrupt-test")(Async.never)
                                    )
                                ))
                                allowed  <- sawEventually(wire, "Page.setDownloadBehavior")
                                _        <- fiber.interrupt
                                _        <- gate.release
                                _        <- fiber.getResult
                                restored <- sawEventuallyCount(wire, "Page.setDownloadBehavior", 2)
                            yield
                                assert(allowed, "the download policy was never sent")
                                assert(
                                    restored,
                                    "the policy the reply confirmed was never restored after the caller was stopped at that reply"
                                )
                            end for
                        }
                    }
                }
            }
        }
    }

    "an interrupt landing at the freeze-style injection reply still removes the freeze style".pendingUntilFixed(
        "the freeze style is injected on the server before its reply and the removal registers only when the reply lands; an interrupt at the reply strands the injected style with no removal"
    ) in {
        wiredEval(_.contains("'freeze'"), _.contains("'unfrozen'")) { (client, _, wire, gate) =>
            Scope.run {
                CdpBackend.initUnscoped(client, cfg).map { backend =>
                    BrowserTabSetup.mkBrowserTab(TargetId("target-1"), SessionId("session-1"), backend, Absent).map { tab =>
                        for
                            fiber <- Fiber.initUnscoped(Abort.run[BrowserReadException](
                                Browser.runOn(tab)(HoldStill.withFrozenPage(Async.never))
                            ))
                            injected <- sawEventually(wire, "Runtime.evaluate:gate-hit")
                            _        <- fiber.interrupt
                            _        <- gate.release
                            _        <- fiber.getResult
                            removed  <- sawEventually(wire, "Runtime.evaluate:remove-hit")
                        yield
                            assert(injected, "the freeze style was never injected")
                            assert(removed, "the freeze style injected before the interrupt was never removed")
                        end for
                    }
                }
            }
        }
    }

    // `screenshotMarks` injects the numbered-badge overlay the same way, inside an outer `withFrozenPage`. A stop at
    // the marks injection reply abandons that acquire specifically: the freeze/unfreeze pair unwinds normally, but the
    // marks overlay's acquire never registered its removal.
    "an interrupt landing at the marks injection reply still removes the marks overlay".pendingUntilFixed(
        "the marks overlay is injected on the server before its reply and the removal registers only when the reply lands; an interrupt at the reply strands the overlay with no removal"
    ) in {
        val mark =
            Browser.ElementInfo("body", "body", Absent, Chunk.empty, Absent, Browser.Bounds(0, 0, 10, 10), true, true, true, false, Absent)
        wiredEval(_.contains("'marks'"), _.contains("'unmarked'")) { (client, _, wire, gate) =>
            Scope.run {
                CdpBackend.initUnscoped(client, cfg).map { backend =>
                    BrowserTabSetup.mkBrowserTab(TargetId("target-1"), SessionId("session-1"), backend, Absent).map { tab =>
                        for
                            fiber <- Fiber.initUnscoped(Abort.run[BrowserReadException](
                                Browser.runOn(tab)(Browser.screenshotMarks(Chunk(mark)).unit.andThen(Async.never))
                            ))
                            injected <- sawEventually(wire, "Runtime.evaluate:gate-hit")
                            _        <- fiber.interrupt
                            _        <- gate.release
                            _        <- fiber.getResult
                            removed  <- sawEventually(wire, "Runtime.evaluate:remove-hit")
                        yield
                            assert(injected, "the marks overlay was never injected")
                            assert(removed, "the marks overlay injected before the interrupt was never removed")
                        end for
                    }
                }
            }
        }
    }

end CdpBackendInterruptTest
