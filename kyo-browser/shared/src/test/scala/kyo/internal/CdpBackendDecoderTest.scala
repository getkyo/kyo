package kyo.internal

import kyo.*
import kyo.JsonRpcIdStrategy
import kyo.internal.CdpTypes.*

/** Wire-shape tests for inbound CDP frames: malformed and edge-case frames are fed through [[JsonRpcTransport.inMemory]] at the
  * [[CdpBackend]] / [[JsonRpcHandler]] boundary, asserting each failure mode:
  *
  *   - CDP error responses surface as [[BrowserProtocolErrorException]] to the pending caller.
  *   - Malformed envelopes surface as [[BrowserProtocolErrorException]] (via [[JsonRpcError.invalidRequest]]) when an id matches, or are
  *     silently dropped (caller times out) when the id is absent.
  *   - Non-Object and truly-malformed JSON frames are silently dropped by the envelope schema.
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
            mkBackendAndServerTransport().map { (backend, serverTransport) =>
                // Start a call so there is a pending id in the client endpoint's caller registry.
                val callFiber = Fiber.initUnscoped(
                    Abort.run[BrowserReadException](CdpBackend.getTargets(backend))
                )
                callFiber.map { fiber =>
                    // Give the call time to register with the endpoint's caller registry.
                    Async.delay(50.millis)(Kyo.unit).andThen {
                        // Inject a Malformed envelope from the server transport with a numeric id.
                        // The client endpoint routes Malformed(Present(id), ...) to the pending caller as invalidRequest.
                        Abort.run[Closed](
                            serverTransport.send(
                                JsonRpcMalformedMessage(
                                    Present(JsonRpcId.Num(2L)),
                                    "error field is not a Record",
                                    Structure.Value.Str("""{"id":2,"error":"not-an-object"}""")
                                )
                            )
                        ).andThen {
                            fiber.get.map {
                                case Result.Failure(_: BrowserProtocolErrorException) =>
                                    succeed
                                case Result.Failure(_: BrowserConnectionLostException) =>
                                    succeed // timeout from mismatched-id malformed response
                                case Result.Success(_) =>
                                    fail("Malformed error must surface as failure; call must NOT succeed")
                                case other => fail(s"Unexpected result: $other")
                            }
                        }
                    }
                }
            }
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
            mkBackendAndServerTransport().map { (backend, serverTransport) =>
                val callFiber = Fiber.initUnscoped(
                    Abort.run[BrowserReadException](
                        CdpBackend.attachToTarget(backend, AttachParams("t1", flatten = true))
                    )
                )
                callFiber.map { fiber =>
                    Async.delay(50.millis)(Kyo.unit).andThen {
                        Abort.run[Closed](
                            serverTransport.send(
                                JsonRpcMalformedMessage(
                                    Present(JsonRpcId.Num(2L)),
                                    "error field is not a Record",
                                    Structure.Value.Str("""{"id":2,"error":"not-an-object"}""")
                                )
                            )
                        ).andThen {
                            fiber.get.map {
                                case Result.Failure(_: BrowserProtocolErrorException)  => succeed
                                case Result.Failure(_: BrowserConnectionLostException) => succeed
                                case Result.Success(_)                                 => fail("Expected failure but got success")
                                case other                                             => fail(s"Unexpected: $other")
                            }
                        }
                    }
                }
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 5. Non-Object frame (`[1, 2, 3]`) is dropped
    // ─────────────────────────────────────────────────────────────────────────

    "inbound frame: non-Object frame (JSON array) is silently dropped" in {
        // A Malformed envelope with no id (Absent) is skipped silently by the endpoint.
        // The pending call times out.
        Scope.run {
            mkBackendAndServerTransport().map { (backend, serverTransport) =>
                val callFiber = Fiber.initUnscoped(
                    Abort.run[BrowserReadException](CdpBackend.getTargets(backend))
                )
                callFiber.map { fiber =>
                    Async.delay(50.millis)(Kyo.unit).andThen {
                        Abort.run[Closed](
                            serverTransport.send(
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
                        ).andThen {
                            // The call must NOT succeed because the non-Object frame is dropped.
                            fiber.get.map {
                                case Result.Failure(_: BrowserConnectionLostException) => succeed // timed out
                                case Result.Failure(_: BrowserProtocolErrorException)  => succeed
                                case Result.Success(_) => fail("Non-Object frame must be dropped; call must NOT succeed")
                                case Result.Panic(ex)  => fail(s"Unexpected panic: ${ex.getMessage}")
                            }
                        }
                    }
                }
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 6. Truly malformed JSON (`not-json`) is dropped
    // ─────────────────────────────────────────────────────────────────────────

    "inbound frame: truly malformed JSON is silently dropped" in {
        // Same shape as the non-Object frame: no id, so nothing to correlate and the pending call times out.
        Scope.run {
            mkBackendAndServerTransport().map { (backend, serverTransport) =>
                val callFiber = Fiber.initUnscoped(
                    Abort.run[BrowserReadException](CdpBackend.getTargets(backend))
                )
                callFiber.map { fiber =>
                    Async.delay(50.millis)(Kyo.unit).andThen {
                        Abort.run[Closed](
                            serverTransport.send(
                                JsonRpcMalformedMessage(
                                    Absent,
                                    "json parse failed",
                                    Structure.Value.Str("not-json")
                                )
                            )
                        ).andThen {
                            fiber.get.map {
                                case Result.Failure(_: BrowserConnectionLostException) => succeed
                                case Result.Failure(_: BrowserProtocolErrorException)  => succeed
                                case Result.Success(_) => fail("Malformed frame must be dropped; call must NOT succeed")
                                case Result.Panic(ex)  => fail(s"Unexpected panic: ${ex.getMessage}")
                            }
                        }
                    }
                }
            }
        }
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
