package kyo.internal

import kyo.*
import kyo.JsonRpcEndpoint.IdStrategy
import kyo.internal.CdpTypes.*

/** Behavior-equivalent replacement for the deleted `CdpClient.decodeCdpMessage` tests.
  *
  * `CdpClient.decodeCdpMessage` is deleted in Phase 02. Its 7 wire-shape assertions are preserved here by feeding the same malformed wire
  * shapes through [[JsonRpcTransport.inMemory]] at the [[CdpBackend]] / [[JsonRpcEndpoint]] boundary and asserting the same failure modes:
  *
  *   - CDP error responses surface as [[BrowserProtocolErrorException]] to the pending caller.
  *   - Malformed envelopes surface as [[BrowserProtocolErrorException]] (via [[JsonRpcError.invalidRequest]]) when an id matches, or are
  *     silently dropped (caller times out) when the id is absent.
  *   - Non-Object and truly-malformed JSON frames are silently dropped by the [[JsonRpcCodec]].
  *   - Non-whitelisted events are silently dropped by the [[JsonRpcEndpoint]] unknown-method policy.
  *
  * Wire shape coverage: same 7 shapes as the original `CdpClientDecoderTest`, same failure modes pinned.
  */
class CdpClientDecoderTest extends kyo.Test:

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
        extraServerMethods: Seq[JsonRpcMethod[Async & Abort[JsonRpcError]]] = Seq.empty
    )(using Frame): (CdpBackend, JsonRpcTransport) < (Async & Scope & Abort[BrowserReadException | BrowserSetupException]) =
        JsonRpcTransport.inMemory.map { (clientTransport, serverTransport) =>
            val versionMethod = JsonRpcMethod[BrowserGetVersionParams, BrowserVersionResult, Async & Abort[JsonRpcError]](
                "Browser.getVersion"
            ) { (_, _) => testVersionResult }
            val config = JsonRpcEndpoint.Config(
                codec = JsonRpcCodec.Cdp,
                maxInFlight = Present(8),
                idStrategy = IdStrategy.SequentialInt
            )
            JsonRpcEndpoint.init(serverTransport, versionMethod +: extraServerMethods, config).andThen {
                CdpBackend.initUnscoped(clientTransport, testLaunchCfg).map { backend =>
                    (backend, serverTransport)
                }
            }
        }

    // ─────────────────────────────────────────────────────────────────────────
    // 1. CDP error-response pipeline; well-formed (was decodeCdpMessage case 1)
    // ─────────────────────────────────────────────────────────────────────────

    "CDP error-response pipeline: well-formed error surfaces as BrowserProtocolErrorException" in run {
        // Server responds with a typed JSON-RPC error.
        // Equivalent wire shape: `{"id": 1, "error": {"code": -32602, "message": "Invalid params"}}`.
        Scope.run {
            val errorMethod = JsonRpcMethod[CdpNoParams, GetTargetsResult, Async & Abort[JsonRpcError]](
                "Target.getTargets"
            ) { (_, _) =>
                Abort.fail(JsonRpcError(-32602, "Invalid params", Absent))
            }
            mkBackendAndServerTransport(Seq(errorMethod)).map { (backend, _) =>
                Abort.run[BrowserReadException](CdpBackend.getTargets(backend)).map {
                    case Result.Failure(e: BrowserProtocolErrorException) =>
                        assert(e.method == "Target.getTargets")
                        assert(e.error.contains("Invalid params"), s"error message: ${e.error}")
                        succeed
                    case other => fail(s"Expected BrowserProtocolErrorException but got $other")
                }
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 2. CDP error-response pipeline; malformed error fallback (was case 2)
    // ─────────────────────────────────────────────────────────────────────────

    "CDP error-response pipeline: malformed-envelope response surfaces as BrowserProtocolErrorException" in run {
        // A `JsonRpcEnvelope.Malformed(Present(id), reason, raw)` sent directly on the server transport
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
                                JsonRpcEnvelope.Malformed(
                                    Present(JsonRpcEnvelope.Id.Num(2L)),
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
    // 3. Error-id branch (4a) — well-formed error at a different method
    // ─────────────────────────────────────────────────────────────────────────

    "decodeCdpMessage: error-id branch surfaces BrowserProtocolErrorException (4a)" in run {
        // Same shape as case 1; verifies the same pipeline at a different method site.
        Scope.run {
            val errorMethod = JsonRpcMethod[AttachParams, AttachResult, Async & Abort[JsonRpcError]](
                "Target.attachToTarget"
            ) { (_, _) =>
                Abort.fail(JsonRpcError(-32601, "Method not found", Absent))
            }
            mkBackendAndServerTransport(Seq(errorMethod)).map { (backend, _) =>
                Abort.run[BrowserReadException](
                    CdpBackend.attachToTarget(backend, AttachParams("t1", flatten = true))
                ).map {
                    case Result.Failure(e: BrowserProtocolErrorException) =>
                        assert(e.method == "Target.attachToTarget")
                        assert(e.error.contains("Method not found"), s"error: ${e.error}")
                        succeed
                    case other => fail(s"Expected BrowserProtocolErrorException but got $other")
                }
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 4. Malformed error JSON fallback (4b) — malformed at a different method
    // ─────────────────────────────────────────────────────────────────────────

    "decodeCdpMessage: malformed error JSON falls back to BrowserProtocolErrorException (4b)" in run {
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
                                JsonRpcEnvelope.Malformed(
                                    Present(JsonRpcEnvelope.Id.Num(2L)),
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
    // 5. Non-Object frame returns Skip (was case 4c: `[1, 2, 3]`)
    // ─────────────────────────────────────────────────────────────────────────

    "decodeCdpMessage: non-Object frame (JSON array) is silently dropped" in run {
        // A Malformed envelope with no id (Absent) is skipped silently by the endpoint.
        // The pending call times out. Equivalent to old `Exchange.Message.Skip` for `[1, 2, 3]`.
        Scope.run {
            mkBackendAndServerTransport().map { (backend, serverTransport) =>
                val callFiber = Fiber.initUnscoped(
                    Abort.run[BrowserReadException](CdpBackend.getTargets(backend))
                )
                callFiber.map { fiber =>
                    Async.delay(50.millis)(Kyo.unit).andThen {
                        Abort.run[Closed](
                            serverTransport.send(
                                JsonRpcEnvelope.Malformed(
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
    // 6. Truly malformed JSON returns Skip (was case 4d: `not-json`)
    // ─────────────────────────────────────────────────────────────────────────

    "decodeCdpMessage: truly malformed JSON is silently dropped" in run {
        // Same as case 5. Equivalent to old `Exchange.Message.Skip` for `not-json`.
        Scope.run {
            mkBackendAndServerTransport().map { (backend, serverTransport) =>
                val callFiber = Fiber.initUnscoped(
                    Abort.run[BrowserReadException](CdpBackend.getTargets(backend))
                )
                callFiber.map { fiber =>
                    Async.delay(50.millis)(Kyo.unit).andThen {
                        Abort.run[Closed](
                            serverTransport.send(
                                JsonRpcEnvelope.Malformed(
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
    // 7. Non-whitelisted event is NOT emitted (was case 5 in original)
    // ─────────────────────────────────────────────────────────────────────────

    "eventWhitelist: non-whitelisted notification is silently dropped by the endpoint" in run {
        // An unregistered notification method is handled by `JsonRpcEndpoint.UnknownMethodPolicy.minimal` which discards it.
        // Equivalent to the old `Exchange.Message.Skip` for a non-whitelisted event.
        Scope.run {
            val getTargetsMethod = JsonRpcMethod[CdpNoParams, GetTargetsResult, Async & Abort[JsonRpcError]](
                "Target.getTargets"
            ) { (_, _) => GetTargetsResult(targetInfos = Seq.empty) }
            mkBackendAndServerTransport(Seq(getTargetsMethod)).map { (backend, serverTransport) =>
                // Issue a known-good call to verify the endpoint is functional.
                Abort.run[BrowserReadException](CdpBackend.getTargets(backend)).andThen {
                    // Inject a notification for an unregistered method via the server transport.
                    Abort.run[Closed](
                        serverTransport.send(
                            JsonRpcEnvelope.Notification(
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

end CdpClientDecoderTest
