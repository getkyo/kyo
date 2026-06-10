package kyo.internal

import CdpTypes.*
import kyo.*

/** In-process CDP fixture WebSocket server. Each Behavior reproduces a CDP-WS fault that live Chrome cannot reliably exhibit on demand.
  *
  * JVM-only: kyo-http's `HttpServer` is implemented on JVM and Native; we scope to JVM here for simplicity, matching the precedent set by
  * [[BrowserLauncherJvmTest]] and [[BrowserLauncherPlatformTest]].
  *
  * Renamed from `CdpFixtureServer` in Phase 02 to match `CdpBackendLifecycleJvmTest`.
  */
private[kyo] object CdpBackendFixtureServer:

    enum Behavior derives CanEqual:
        /** Pass-through CDP responder: replies to Browser.getVersion with a valid version result, and
          * replies `{"id": <id>, "result": {}}` to every other request. Baseline + dialog injection.
          */
        case Echo

        /** Accept one inbound frame, reply, then close 1006. Reproduces a relay fiber crash. */
        case CrashAfterFirstFrame

        /** Every inbound frame waits `delay` before reply. Reproduces a stuck closeOrderly grace period. */
        case SlowResponses(delay: Duration)

        /** Read one frame then close 1006 without replying. Reproduces a connection drop mid-request. */
        case DropMidRequest
    end Behavior

    /** Started fixture handle. */
    final case class FixtureHandle(
        wsUrl: String,
        triggerDialog: () => Unit < Async,
        triggerCrash: () => Unit < Async
    )

    /** Starts an ephemeral-port WS server bound to localhost serving the given Behavior. Lifecycle bound to `Scope`.
      *
      * `HttpServer.init`'s effect signature is `Async & Scope`; bind failures throw `HttpBindException` rather than aborting. We let those
      * propagate as panics; for an ephemeral localhost bind they should never happen.
      */
    def start(behavior: Behavior)(using Frame): FixtureHandle < (Async & Scope) =
        AtomicRef.initWith(Absent: Maybe[HttpWebSocket]) { wsRef =>
            val handler = HttpHandler.webSocket("devtools/browser/fixture") {
                (_: HttpRequest[Any], ws: HttpWebSocket) =>
                    wsRef.set(Present(ws)).andThen(runBehavior(behavior, ws))
            }
            HttpServer.init(0, "localhost")(handler).map { server =>
                val wsUrl = s"ws://localhost:${server.port}/devtools/browser/fixture"
                FixtureHandle(
                    wsUrl = wsUrl,
                    triggerDialog = () => emitDialog(wsRef),
                    triggerCrash = () => forceClose(wsRef)
                )
            }
        }

    // ----- internals -----

    private def runBehavior(behavior: Behavior, ws: HttpWebSocket)(using
        Frame
    ): Unit < (Async & Abort[Closed]) =
        behavior match
            case Behavior.Echo                 => loopEcho(ws)
            case Behavior.CrashAfterFirstFrame => crashAfterFirst(ws)
            case Behavior.SlowResponses(delay) => loopSlow(ws, delay)
            case Behavior.DropMidRequest       => dropOnFirst(ws)

    private def loopEcho(ws: HttpWebSocket)(using Frame): Unit < (Async & Abort[Closed]) =
        ws.stream.foreach {
            case HttpWebSocket.Payload.Text(s) => replyOk(ws, s)
            case _                             => Kyo.unit
        }

    /** Slow-response loop: always responds to Browser.getVersion immediately; applies the delay for all other requests. This ensures the
      * Q-002 probe in [[CdpBackend.initUnscoped]] succeeds so the fixture can test subsequent slow sends.
      */
    private def loopSlow(ws: HttpWebSocket, delay: Duration)(using
        Frame
    ): Unit < (Async & Abort[Closed]) =
        ws.stream.foreach {
            case HttpWebSocket.Payload.Text(s) =>
                if s.contains("Browser.getVersion") then replyOk(ws, s)
                else Async.delay(delay)(replyOk(ws, s))
            case _ => Kyo.unit
        }

    /** B1 helper: respond to Browser.getVersion probe immediately (so initUnscoped succeeds), then crash on the next frame. */
    private def crashAfterFirst(ws: HttpWebSocket)(using
        Frame
    ): Unit < (Async & Abort[Closed]) =
        // Handle the Browser.getVersion probe first, then crash on the next frame.
        ws.take().flatMap {
            case HttpWebSocket.Payload.Text(s) if s.contains("Browser.getVersion") =>
                replyOk(ws, s).andThen {
                    ws.take().andThen(ws.close(1006, "fixture-crash"))
                }
            case HttpWebSocket.Payload.Text(s) =>
                replyOk(ws, s).andThen(ws.close(1006, "fixture-crash"))
            case _ =>
                ws.close(1006, "fixture-crash")
        }

    /** B3 helper: respond to Browser.getVersion probe (initUnscoped succeeds), then drop the next frame. */
    private def dropOnFirst(ws: HttpWebSocket)(using
        Frame
    ): Unit < (Async & Abort[Closed]) =
        ws.take().flatMap {
            case HttpWebSocket.Payload.Text(s) if s.contains("Browser.getVersion") =>
                replyOk(ws, s).andThen {
                    ws.take().andThen(ws.close(1006, "fixture-drop"))
                }
            case _ =>
                ws.close(1006, "fixture-drop")
        }

    /** Local envelope used only by this fixture to extract `id` from an inbound CDP request wire frame.
      * Permissive: `id` is Maybe[Int] so partial / missing frames do not fail the fixture server.
      */
    final private case class FixtureIdEnvelope(id: Maybe[Int] = Absent) derives Schema

    /** Decode the request, extract `id` via [[FixtureIdEnvelope]] (permissive Maybe[Int]), send a proper reply.
      *
      * For `Browser.getVersion` requests, returns a valid [[BrowserVersionResult]] JSON so the Q-002 probe in [[CdpBackend.initUnscoped]]
      * succeeds. For `Target.getTargets` requests, returns `{"targetInfos":[]}` (an empty but schema-valid response). For all other
      * requests, returns `{"id":id,"result":{}}`.
      */
    private val versionResultJson =
        """{"protocolVersion":"1.3","product":"Chrome/120","revision":"abc","userAgent":"Mozilla","jsVersion":"12.0"}"""

    private def replyOk(ws: HttpWebSocket, requestWire: String)(using
        Frame
    ): Unit < (Async & Abort[Closed]) =
        Json.decode[FixtureIdEnvelope](requestWire) match
            case Result.Success(env) =>
                env.id match
                    case Present(id) =>
                        // Check if this is a Browser.getVersion request; reply with a valid version envelope.
                        // Check if this is a Target.getTargets request; reply with a schema-valid empty list.
                        val result =
                            if requestWire.contains("Browser.getVersion") then versionResultJson
                            else if requestWire.contains("Target.getTargets") then """{"targetInfos":[]}"""
                            else "{}"
                        val reply = s"""{"id":$id,"result":$result}"""
                        ws.put(HttpWebSocket.Payload.Text(reply))
                    case Absent => Kyo.unit
            case _ => Kyo.unit

    /** Emits a Page.javascriptDialogOpening event with NO sessionId field. */
    private def emitDialog(wsRef: AtomicRef[Maybe[HttpWebSocket]])(using
        Frame
    ): Unit < Async =
        wsRef.get.map {
            case Present(ws) =>
                val ev =
                    """{"method":"Page.javascriptDialogOpening","params":""" +
                        """{"url":"about:blank","message":"top","type":"alert","defaultPrompt":""}}"""
                Abort.run[Closed](ws.put(HttpWebSocket.Payload.Text(ev))).unit
            case Absent => Kyo.unit
        }

    /** Drives B1's external relay-crash trigger. */
    private def forceClose(wsRef: AtomicRef[Maybe[HttpWebSocket]])(using
        Frame
    ): Unit < Async =
        wsRef.get.map {
            case Present(ws) => ws.close(1006, "fixture-trigger-crash")
            case Absent      => Kyo.unit
        }

end CdpBackendFixtureServer

/** JVM-only [[CdpBackend]] scenarios that require a fault-injecting CDP WebSocket fixture ([[CdpBackendFixtureServer]] in this file).
  *
  * Renamed from `CdpClientLifecycleJvmTest` in Phase 02: `CdpClient.*` -> `CdpBackend.*`, raw-string sends -> typed wrappers.
  */
class CdpBackendLifecycleJvmTest extends kyo.BaseBrowserTest:

    override def timeout = 2.minutes

    "fixture sanity: Echo replies to a Target.getTargets request" in {
        Abort.run[BrowserConnectionException | BrowserSetupException] {
            Scope.run {
                CdpBackendFixtureServer.start(CdpBackendFixtureServer.Behavior.Echo).map { fixture =>
                    CdpBackend.initUnscoped(fixture.wsUrl, Browser.LaunchConfig.default).map { backend =>
                        for
                            _ <- CdpBackend.getTargets(backend)
                            _ <- backend.close(30.seconds)
                        yield succeed
                    }
                }
            }
        }.orFail("Outer")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // close after relay fiber crashed externally
    // ─────────────────────────────────────────────────────────────────────────

    "close after relay fiber crashed externally does not throw" in {
        Abort.run[BrowserConnectionException | BrowserSetupException] {
            Scope.run {
                CdpBackendFixtureServer.start(CdpBackendFixtureServer.Behavior.Echo).map { fixture =>
                    CdpBackend.initUnscoped(fixture.wsUrl, Browser.LaunchConfig.default).map { backend =>
                        for
                            // Liveness: route a request through the endpoint.
                            _ <- CdpBackend.getTargets(backend)
                            // Server side closes 1006; endpoint reader observes and exits.
                            _ <- fixture.triggerCrash()
                            // Brief settle: allow the WS close to propagate through the transport layer.
                            _ <- Async.delay(200.millis)(Kyo.unit)
                            // close() must not throw or hang post-crash.
                            closeRes <- Abort.run[Timeout](Async.timeout(5.seconds)(backend.close(30.seconds)))
                        yield closeRes match
                            case Result.Success(_)          => succeed
                            case Result.Failure(_: Timeout) => fail("backend.close hung after relay crash")
                            case Result.Panic(ex)           => fail(s"Panic from backend.close: ${ex.getMessage}")
                    }
                }
            }
        }.orFail("Outer")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // close(gracePeriod) falls back to closeNow on timeout
    // ─────────────────────────────────────────────────────────────────────────

    "close(gracePeriod) falls back to closeNow when in-flight send is slow" in {
        Abort.run[BrowserConnectionException | BrowserSetupException] {
            Scope.run {
                CdpBackendFixtureServer.start(CdpBackendFixtureServer.Behavior.SlowResponses(10.seconds)).map { fixture =>
                    CdpBackend.initUnscoped(fixture.wsUrl, Browser.LaunchConfig.default).map { backend =>
                        for
                            slowFiber <- Fiber.initUnscoped(
                                Abort.run[BrowserReadException](CdpBackend.getTargets(backend))
                            )
                            // Brief settle: allow the slow in-flight send to register before close starts.
                            _ <- Async.delay(100.millis)(Kyo.unit)
                            // close(500ms): grace expires while send is in-flight, falls back to closeNow.
                            timedClose <- timed(backend.close(500.millis))
                            (elapsedDur, _) = timedClose
                            elapsedMs       = elapsedDur.toMillis
                            slowResult <- slowFiber.getResult
                        yield
                            // Behavioural upper bound: must return well before the 10s server delay.
                            assert(
                                elapsedMs < 2000,
                                s"close(500ms) should fall back to closeNow within ~grace, took ${elapsedMs}ms"
                            )
                            slowResult match
                                case Result.Success(Result.Failure(_: BrowserConnectionLostException)) => succeed
                                case other =>
                                    fail(s"expected ConnectionLost on slow send after closeNow fallback, got $other")
                            end match
                    }
                }
            }
        }.orFail("Outer")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // connection drop mid-request
    // ─────────────────────────────────────────────────────────────────────────

    "send raises ConnectionLost when the WebSocket connection is dropped mid-request" in {
        // With DropMidRequest, the fixture responds to the Browser.getVersion probe (initUnscoped succeeds),
        // then drops the connection on the next send. The next send surfaces as BrowserConnectionLostException.
        Abort.run[BrowserConnectionException | BrowserSetupException] {
            Scope.run {
                CdpBackendFixtureServer.start(CdpBackendFixtureServer.Behavior.DropMidRequest).map { fixture =>
                    CdpBackend.initUnscoped(fixture.wsUrl, Browser.LaunchConfig.default).map { backend =>
                        Abort.run[BrowserConnectionException](CdpBackend.getTargets(backend)).map {
                            case Result.Failure(_: BrowserConnectionLostException) => succeed
                            case other =>
                                fail(s"expected BrowserConnectionLostException, got $other")
                        }
                    }
                }
            }
        }.orFail("Outer")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // top-level dialog with no sessionId
    // ─────────────────────────────────────────────────────────────────────────

    "top-level dialog with no sessionId is auto-dismissed via empty-string handler key" in {
        Abort.run[BrowserConnectionException | BrowserSetupException] {
            Scope.run {
                CdpBackendFixtureServer.start(CdpBackendFixtureServer.Behavior.Echo).map { fixture =>
                    CdpBackend.initUnscoped(fixture.wsUrl, Browser.LaunchConfig.default).map { backend =>
                        for
                            _ <- CdpBackend.getTargets(backend)
                            // Trigger emission of a Page.javascriptDialogOpening with NO sessionId.
                            _ <- fixture.triggerDialog()
                            // Behavioural check: a subsequent send round-trips.
                            _ <- CdpBackend.getTargets(backend)
                            // Confirm the dialogDrainer fiber is still alive.
                            drainerLive <- backend.dialogDrainer.done.map(d => !d)
                        yield assert(drainerLive, "dialogDrainer must still be alive after auto-dismiss")
                    }
                }
            }
        }.orFail("Outer")
    }

end CdpBackendLifecycleJvmTest
