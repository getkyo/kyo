package kyo.internal

import CdpTypes.*
import kyo.*

/** Interrupts landing on the round trips a [[CdpBackend]] and the tab setup make, driven over an in-memory CDP wire whose browser side
  * this suite plays. Each leaf gates one reply, stops the caller while it waits on it, releases the reply, and reads what the browser side
  * saw afterwards: the calls a fixed caller makes on its way out, or the silence of an abandoned one.
  */
class CdpBackendInterruptTest extends BaseBrowserTest:

    private val cfg = Browser.LaunchConfig.default

    /** What the browser side records: every request method in order, and how many responses it was sent. */
    final private class Wire(val seen: AtomicRef[Chunk[String]], val replies: AtomicInt)

    /** The browser side of the wire: answers every request with a result the client decodes, records the methods it saw in order, holds
      * the reply of a gated method until its latch opens, and counts the responses the client side sends it.
      */
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
            case _ =>
                summon[Schema[CdpNoParams]].toStructureValue(CdpNoParams())
        Fiber.initUnscoped {
            Abort.run[Closed] {
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

    // The init builds the endpoint over the wire and spawns the dialog drainer before it probes `Browser.getVersion`.
    // The endpoint is scope-bound, so a stop landing at the probe's join must close it on the way out: a request sent
    // from the browser side afterwards must get no answer. The drainer, parked on a queue nothing else references,
    // has no observable of its own.
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

    // The tab setup creates the browser context in a round trip and registers its disposal in the step after the
    // reply lands. A stop landing at that reply's join abandons the context with the disposal unregistered.
    "an interrupt landing at the context creation reply still disposes the context".pendingUntilFixed(
        "attachAndSetupTab registers the context's disposal in the step after the creation reply, so a stop landing at that reply's join leaves the context undisposed"
    ) in {
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

    // `withViewport` sends the override in a round trip and registers its restore in the step after the reply lands. A
    // stop landing at that reply's join leaves the override in place with the restore unregistered.
    "an interrupt landing at the viewport override reply still restores the viewport".pendingUntilFixed(
        "withViewport registers the viewport's restore in the step after the override's reply, so a stop landing at that reply's join leaves the override in place"
    ) in {
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

end CdpBackendInterruptTest
