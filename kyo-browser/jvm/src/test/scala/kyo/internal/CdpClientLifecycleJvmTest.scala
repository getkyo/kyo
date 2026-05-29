package kyo.internal

import CdpTypes.*
import kyo.*

/** In-process CDP fixture WebSocket server. Each Behavior reproduces a CDP-WS fault that live Chrome cannot reliably exhibit on demand.
  *
  * JVM-only: kyo-http's `HttpServer` is implemented on JVM and Native; we scope to JVM here for simplicity, matching the precedent set by
  * [[BrowserLauncherJvmTest]] and [[BrowserLauncherPlatformTest]].
  */
private[kyo] object CdpFixtureServer:

    enum Behavior derives CanEqual:
        /** Pass-through CDP responder: replies `{"id": <id>, "result": {}}` to every request. Baseline + dialog injection. */
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

    private def loopSlow(ws: HttpWebSocket, delay: Duration)(using
        Frame
    ): Unit < (Async & Abort[Closed]) =
        ws.stream.foreach {
            case HttpWebSocket.Payload.Text(s) =>
                Async.delay(delay)(replyOk(ws, s))
            case _ => Kyo.unit
        }

    /** B1 helper: read first inbound frame, reply, then close 1006 to make the relay observe an abrupt server-side termination. The
      * `wsRef`-mediated `triggerCrash()` is also wired (test may use either).
      */
    private def crashAfterFirst(ws: HttpWebSocket)(using
        Frame
    ): Unit < (Async & Abort[Closed]) =
        ws.take().map {
            case HttpWebSocket.Payload.Text(s) =>
                replyOk(ws, s).andThen(ws.close(1006, "fixture-crash"))
            case _ =>
                ws.close(1006, "fixture-crash")
        }

    /** B3 helper: read one frame, then close 1006 without replying. The relay's reader fiber sees the close and `exchange.close` runs via
      * the watcher fiber at CdpClient.
      */
    private def dropOnFirst(ws: HttpWebSocket)(using
        Frame
    ): Unit < (Async & Abort[Closed]) =
        ws.take().andThen(ws.close(1006, "fixture-drop"))

    /** Decode the request, extract `id` via the typed [[FallbackIdEnvelope]] (permissive Maybe[Int]), send a `{"id":id,"result":{}}` frame.
      * Malformed/missing-id requests are silently dropped (acceptable for a fixture under controlled inputs).
      */
    private def replyOk(ws: HttpWebSocket, requestWire: String)(using
        Frame
    ): Unit < (Async & Abort[Closed]) =
        Json.decode[FallbackIdEnvelope](requestWire) match
            case Result.Success(env) =>
                env.id match
                    case Present(id) =>
                        val reply = s"""{"id":$id,"result":{}}"""
                        ws.put(HttpWebSocket.Payload.Text(reply))
                    case Absent => Kyo.unit
            case _ => Kyo.unit

    /** Emits a Page.javascriptDialogOpening event with NO sessionId field. Drives B4. Wire format MUST match `decodeCdpMessage` at
      * CdpClient: top-level `method` + `params` object with NO top-level `sessionId` key. The empty-sessionId path then maps to
      * `sidKey = ""` at CdpClient and the (false, "") default tuple is enqueued.
      */
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

end CdpFixtureServer

/** JVM-only [[CdpClient]] scenarios that require a fault-injecting CDP WebSocket fixture ([[CdpFixtureServer]] in this file).
  *
  * Lives in the JVM test tree because [[CdpFixtureServer]] is built on kyo-http's `HttpServer`, which is implemented on JVM and Native only
  * There is no `HttpServer` on Scala.js.
  */
class CdpClientLifecycleJvmTest extends Test:

    override def timeout = 2.minutes

    "fixture sanity: Echo replies to a Target.getTargets request" in run {
        Abort.run[BrowserConnectionException] {
            Scope.run {
                CdpFixtureServer.start(CdpFixtureServer.Behavior.Echo).map { fixture =>
                    CdpClient.initUnscoped(fixture.wsUrl, Browser.LaunchConfig.default).map { client =>
                        for
                            json <- client.send("Target.getTargets")
                            _    <- client.close(30.seconds)
                        yield
                            assert(
                                json.contains("\"result\"") || json == "{}",
                                s"unexpected: $json"
                            )
                            succeed
                    }
                }
            }
        }.orFail("Outer")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // close after relay fiber crashed externally
    // ─────────────────────────────────────────────────────────────────────────

    "close after relay fiber crashed externally does not throw" in run {
        Abort.run[BrowserConnectionException] {
            Scope.run {
                CdpFixtureServer.start(CdpFixtureServer.Behavior.Echo).map { fixture =>
                    CdpClient.initUnscoped(fixture.wsUrl, Browser.LaunchConfig.default).map { client =>
                        for
                            // Liveness: route a request through the relay.
                            _ <- client.send("Target.getTargets")
                            // Server side closes 1006; relay observes and exits.
                            _ <- fixture.triggerCrash()
                            // Bounded poll: wait (≤ 1s) for the watcher fiber
                            // (CdpClient) to observe relay exit.
                            _ <- Loop(0) { attempt =>
                                client.relay.done.map { d =>
                                    if d || attempt >= 20 then Loop.done(())
                                    else Async.delay(50.millis)(Loop.continue(attempt + 1))
                                }
                            }
                            // close() must not throw or hang post-relay-crash.
                            closeRes <- Abort.run[Timeout](Async.timeout(5.seconds)(client.close(30.seconds)))
                        yield closeRes match
                            case Result.Success(_)          => succeed
                            case Result.Failure(_: Timeout) => fail("client.close hung after relay crash")
                            case Result.Panic(ex)           => fail(s"Panic from client.close: ${ex.getMessage}")
                    }
                }
            }
        }.orFail("Outer")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // close(gracePeriod) falls back to closeNow on timeout
    // ─────────────────────────────────────────────────────────────────────────

    "close(gracePeriod) falls back to closeNow when in-flight send is slow" in run {
        Abort.run[BrowserConnectionException] {
            Scope.run {
                CdpFixtureServer.start(CdpFixtureServer.Behavior.SlowResponses(10.seconds)).map { fixture =>
                    CdpClient.initUnscoped(fixture.wsUrl, Browser.LaunchConfig.default).map { client =>
                        for
                            slowFiber <- Fiber.initUnscoped(client.send("Target.getTargets"))
                            // Wait for inFlight to register (bounded ≤ 500ms).
                            _ <- Loop(0) { attempt =>
                                client.inFlight.get.map { n =>
                                    if n >= 1 || attempt >= 20 then Loop.done(())
                                    else Async.delay(25.millis)(Loop.continue(attempt + 1))
                                }
                            }
                            // close(500ms): grace expires while send is in-flight,
                            // closeOrderly raises Timeout, falls into closeNow
                            // (CdpClient).
                            timedClose <- timed(client.close(500.millis))
                            (elapsedDur, _) = timedClose
                            elapsedMs       = elapsedDur.toMillis
                            slowResult <- slowFiber.getResult
                        yield
                            // Behavioural upper bound: must return well before the 10s
                            // server delay; we allow 2s margin for jitter.
                            assert(
                                elapsedMs < 2000,
                                s"close(500ms) should fall back to closeNow within ~grace, took ${elapsedMs}ms"
                            )
                            slowResult match
                                case Result.Failure(_: BrowserConnectionLostException) => succeed
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

    "send raises ConnectionLost when the WebSocket connection is dropped mid-request" in run {
        Abort.run[BrowserConnectionException] {
            Scope.run {
                CdpFixtureServer.start(CdpFixtureServer.Behavior.DropMidRequest).map { fixture =>
                    CdpClient.initUnscoped(fixture.wsUrl, Browser.LaunchConfig.default).map { client =>
                        Abort.run[BrowserConnectionException](client.send("Target.getTargets")).map {
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

    "top-level dialog with no sessionId is auto-dismissed via empty-string handler key" in run {
        Abort.run[BrowserConnectionException] {
            Scope.run {
                CdpFixtureServer.start(CdpFixtureServer.Behavior.Echo).map { fixture =>
                    CdpClient.initUnscoped(fixture.wsUrl, Browser.LaunchConfig.default).map { client =>
                        for
                            _ <- client.send("Target.getTargets")
                            // Trigger emission of a Page.javascriptDialogOpening with
                            // NO sessionId. The empty-key path at
                            // CdpClient must auto-dismiss.
                            _ <- fixture.triggerDialog()
                            // Behavioural check: the dialogQueue offer must not have
                            // poisoned the exchange. A subsequent send round-trips.
                            result <- client.send("Target.getTargets")
                            // Confirm the auto-dismiss handleJavaScriptDialog request
                            // did not crash the dialogDrainer fiber; a panic during
                            // dialog handling would have terminated it.
                            drainerLive <- client.dialogDrainer.done.map(d => !d)
                        yield
                            assert(
                                result.contains("\"result\"") || result == "{}",
                                s"expected post-dialog send to round-trip, got $result"
                            )
                            assert(drainerLive, "dialogDrainer must still be alive after auto-dismiss")
                            succeed
                    }
                }
            }
        }.orFail("Outer")
    }

end CdpClientLifecycleJvmTest
