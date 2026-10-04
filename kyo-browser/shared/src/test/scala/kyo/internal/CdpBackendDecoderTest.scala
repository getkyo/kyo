package kyo.internal

import kyo.*
import kyo.JsonRpcIdStrategy
import kyo.internal.CdpTypes.*

/** Wire-shape tests for inbound CDP frames: malformed and edge-case frames are fed through [[JsonRpcTransport.inMemory]] at the
  * [[CdpBackend]] / [[JsonRpcHandler]] boundary, asserting each failure mode:
  *
  *   - CDP error responses surface as [[BrowserProtocolErrorException]] to the pending caller.
  *   - Malformed envelopes surface as [[BrowserProtocolErrorException]] (via [[JsonRpcError.invalidRequest]]) when an id matches a
  *     pending call, or are silently dropped when the id is absent, leaving the call to complete with the peer's real reply.
  *   - Non-Object and truly-malformed JSON frames are silently dropped by the envelope schema.
  *
  * A frame is injected only once the server route holding the call has received it: the client registers a call before sending it,
  * so receipt proves the call is pending.
  *   - Notifications with no registered route are silently dropped by the [[JsonRpcHandler]] unknown-method policy.
  */
class CdpBackendDecoderTest extends kyo.BaseBrowserTest:

    private val testLaunchCfg = Browser.LaunchConfig.default.copy(
        requestTimeout = 2.seconds,
        closeGrace = 200.millis
    )

    private val testVersionResult = BrowserVersionResult(
        protocolVersion = "0",
        product = "Headless/0",
        revision = "0",
        userAgent = "Mozilla/5.0 (Headless)",
        jsVersion = "0.0"
    )

    /** Creates a server endpoint + returns (backend, serverTransport) so the test can inject raw envelopes. */
    private def mkBackendAndServerTransport(
        extraServerMethods: Seq[JsonRpcRoute[?, ?, ?]] = Seq.empty
    )(using Frame): (CdpBackend, JsonRpcTransport) < (Async & Scope & Abort[BrowserReadException | BrowserSetupException]) =
        JsonRpcTransport.inMemory.map { (clientTransport, serverTransport) =>
            val versionMethod = JsonRpcRoute.request[BrowserGetVersionParams, BrowserVersionResult](
                "Browser.getVersion"
            ) { (_, _) => testVersionResult }
            val config = JsonRpcHandler.Config(
                codec = JsonRpcEnvelope.lenientSchema,
                maxInFlight = Present(8),
                idStrategy = JsonRpcIdStrategy.SequentialInt
            )
            JsonRpcHandler.init(serverTransport, versionMethod +: extraServerMethods, config).andThen {
                CdpBackend.initUnscoped(clientTransport, testLaunchCfg).map { backend =>
                    (backend, serverTransport)
                }
            }
        }

    /** A server route that opens `received` when the request arrives, then holds it until `release` opens and replies `reply`. */
    private def heldRoute[In: Schema, Out: Schema](method: String, received: Latch, release: Latch, reply: Out)(using
        Frame
    ): JsonRpcRoute[In, Out, Nothing] =
        JsonRpcRoute.request[In, Out](method)[Nothing] { (_, _) =>
            received.release.andThen(release.await).andThen(reply)
        }

    /** The malformed frame a peer sends for `{"id":2,"error":"not-an-object"}`; id 2 is the first call after the connect probe. */
    private val malformedErrorForCall2 = JsonRpcMalformedMessage(
        Present(JsonRpcId.Num(2L)),
        "error field is not a Record",
        Structure.Value.Str("""{"id":2,"error":"not-an-object"}""")
    )

    /** Injects `frame` while a `Target.getTargets` call is pending, then lets the server reply. The in-memory transport delivers in
      * order, so the client reads the frame before the reply: the call completing with the reply proves the frame was dropped
      * without failing the call or the endpoint.
      */
    private def assertDroppedWhilePending(frame: JsonRpcEnvelope)(using Frame, kyo.test.AssertScope) =
        val reply = GetTargetsResult(Seq(TargetInfo("t1", "page", "about:blank")))
        Scope.run {
            for
                received <- Latch.init(1)
                release  <- Latch.init(1)
                held = heldRoute[CdpNoParams, GetTargetsResult]("Target.getTargets", received, release, reply)
                (backend, serverTransport) <- mkBackendAndServerTransport(Seq(held))
                fiber                      <- Fiber.initUnscoped(Abort.run[BrowserReadException](CdpBackend.getTargets(backend)))
                _                          <- received.await
                _                          <- Abort.run[Closed](serverTransport.send(frame))
                _                          <- release.release
                result                     <- fiber.get
            yield result match
                case Result.Success(targets) =>
                    assert(
                        targets.targetInfos.map(_.targetId) == Seq("t1"),
                        s"expected the server's reply after the dropped frame, got $targets"
                    )
                case other => fail(s"expected the server's reply after the dropped frame, got $other")
        }
    end assertDroppedWhilePending

    // ─────────────────────────────────────────────────────────────────────────
    // 1. CDP error-response pipeline; well-formed
    // ─────────────────────────────────────────────────────────────────────────

    "CDP error-response pipeline: well-formed error surfaces as BrowserProtocolErrorException" in {
        // Server responds with a typed JSON-RPC error.
        // Equivalent wire shape: `{"id": 1, "error": {"code": -32602, "message": "Invalid params"}}`.
        Scope.run {
            val errorMethod = JsonRpcRoute.request[CdpNoParams, GetTargetsResult](
                "Target.getTargets"
            ) { (_, _) =>
                Abort.fail(JsonRpcInvalidParamsError("[test]", Maybe.Absent, Chunk.empty))
            }
            mkBackendAndServerTransport(Seq(errorMethod)).map { (backend, _) =>
                Abort.run[BrowserReadException](CdpBackend.getTargets(backend)).map {
                    case Result.Failure(e: BrowserProtocolErrorException) =>
                        assert(e.method == "Target.getTargets")
                        assert(e.error.contains("Invalid params"), s"error message: ${e.error}")
                        // The numeric CDP error code (-32602, invalid params) reaches the caller intact.
                        assert(e.code == Present(-32602), s"expected code Present(-32602) but got ${e.code}")
                        succeed
                    case other => fail(s"Expected BrowserProtocolErrorException but got $other")
                }
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 2. CDP error-response pipeline; malformed error fallback
    // ─────────────────────────────────────────────────────────────────────────

    "CDP error-response pipeline: malformed-envelope response surfaces as BrowserProtocolErrorException" in {
        // A `JsonRpcMalformedMessage(Present(id), reason, raw)` sent directly on the server transport
        // triggers `JsonRpcError.invalidRequest("malformed response: <reason>")` at the pending caller.
        // Equivalent to the old fallback path for `{"id": 2, "error": "not-an-object"}`.
        Scope.run {
            for
                received <- Latch.init(1)
                release  <- Latch.init(1)
                held = heldRoute[CdpNoParams, GetTargetsResult]("Target.getTargets", received, release, GetTargetsResult(Seq.empty))
                (backend, serverTransport) <- mkBackendAndServerTransport(Seq(held))
                fiber                      <- Fiber.initUnscoped(Abort.run[BrowserReadException](CdpBackend.getTargets(backend)))
                _                          <- received.await
                _                          <- Abort.run[Closed](serverTransport.send(malformedErrorForCall2))
                result                     <- fiber.get
            yield result match
                case Result.Failure(e: BrowserProtocolErrorException) =>
                    assert(e.method == "Target.getTargets")
                    // Invalid Request (-32600) is how the endpoint fails a call whose response arrived malformed; the held
                    // route never replies, so nothing else can complete the call.
                    assert(e.code == Present(-32600), s"expected code Present(-32600) but got ${e.code}")
                case other => fail(s"Expected BrowserProtocolErrorException from the malformed frame but got $other")
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 3. Error-id branch: well-formed error at a different method
    // ─────────────────────────────────────────────────────────────────────────

    "inbound frame: error-id branch surfaces BrowserProtocolErrorException" in {
        // Same shape as case 1; verifies the same pipeline at a different method site.
        Scope.run {
            val errorMethod = JsonRpcRoute.request[AttachParams, AttachResult](
                "Target.attachToTarget"
            ) { (_, _) =>
                Abort.fail(JsonRpcMethodNotFoundError("[test]", Chunk.empty))
            }
            mkBackendAndServerTransport(Seq(errorMethod)).map { (backend, _) =>
                Abort.run[BrowserReadException](
                    CdpBackend.attachToTarget(backend, AttachParams("t1", flatten = true))
                ).map {
                    case Result.Failure(e: BrowserProtocolErrorException) =>
                        assert(e.method == "Target.attachToTarget")
                        assert(e.error.contains("Method not found"), s"error: ${e.error}")
                        // The numeric CDP error code (-32601, method not found) reaches the caller intact.
                        assert(e.code == Present(-32601), s"expected code Present(-32601) but got ${e.code}")
                        succeed
                    case other => fail(s"Expected BrowserProtocolErrorException but got $other")
                }
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 4. Malformed error JSON fallback: malformed at a different method
    // ─────────────────────────────────────────────────────────────────────────

    "inbound frame: malformed error JSON falls back to BrowserProtocolErrorException" in {
        // Same shape as case 2; verifies the fallback pipeline at a different method site.
        Scope.run {
            for
                received <- Latch.init(1)
                release  <- Latch.init(1)
                held = heldRoute[AttachParams, AttachResult]("Target.attachToTarget", received, release, AttachResult("s1"))
                (backend, serverTransport) <- mkBackendAndServerTransport(Seq(held))
                fiber                      <- Fiber.initUnscoped(
                    Abort.run[BrowserReadException](CdpBackend.attachToTarget(backend, AttachParams("t1", flatten = true)))
                )
                _      <- received.await
                _      <- Abort.run[Closed](serverTransport.send(malformedErrorForCall2))
                result <- fiber.get
            yield result match
                case Result.Failure(e: BrowserProtocolErrorException) =>
                    assert(e.method == "Target.attachToTarget")
                    // Invalid Request (-32600) is how the endpoint fails a call whose response arrived malformed; the held
                    // route never replies, so nothing else can complete the call.
                    assert(e.code == Present(-32600), s"expected code Present(-32600) but got ${e.code}")
                case other => fail(s"Expected BrowserProtocolErrorException from the malformed frame but got $other")
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 5. Non-Object frame (`[1, 2, 3]`) is dropped
    // ─────────────────────────────────────────────────────────────────────────

    "inbound frame: non-Object frame (JSON array) is silently dropped" in {
        assertDroppedWhilePending(
            JsonRpcMalformedMessage(
                Absent,
                "expected a Record",
                Structure.Value.Sequence(Chunk(
                    Structure.Value.Integer(1L),
                    Structure.Value.Integer(2L),
                    Structure.Value.Integer(3L)
                ))
            )
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 6. Truly malformed JSON (`not-json`) is dropped
    // ─────────────────────────────────────────────────────────────────────────

    "inbound frame: truly malformed JSON is silently dropped" in {
        assertDroppedWhilePending(JsonRpcMalformedMessage(Absent, "json parse failed", Structure.Value.Str("not-json")))
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 7. A notification with no registered route is NOT emitted
    // ─────────────────────────────────────────────────────────────────────────

    "unregistered notification is silently dropped by the endpoint" in {
        // An unregistered notification method is handled by `JsonRpcUnknownMethodPolicy.minimal` which discards it.
        Scope.run {
            val getTargetsMethod = JsonRpcRoute.request[CdpNoParams, GetTargetsResult](
                "Target.getTargets"
            ) { (_, _) => GetTargetsResult(targetInfos = Seq.empty) }
            mkBackendAndServerTransport(Seq(getTargetsMethod)).map { (backend, serverTransport) =>
                // Issue a known-good call to verify the endpoint is functional.
                Abort.run[BrowserReadException](CdpBackend.getTargets(backend)).andThen {
                    // Inject a notification for an unregistered method via the server transport.
                    Abort.run[Closed](
                        serverTransport.send(
                            JsonRpcNotification(
                                method = "NotAWhitelistedEvent",
                                params = Absent,
                                extras = Absent
                            )
                        )
                    ).andThen {
                        // Subsequent call must still succeed: the endpoint was not crashed by the unknown notification.
                        Abort.run[BrowserReadException](CdpBackend.getTargets(backend)).map {
                            case Result.Success(_) => succeed
                            case other             => fail(s"Expected endpoint to survive unknown notification but got $other")
                        }
                    }
                }
            }
        }
    }

end CdpBackendDecoderTest
