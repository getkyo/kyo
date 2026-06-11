package kyo.internal

import CdpTypes.*
import kyo.*
import kyo.JsonRpcExtrasEncoder
import kyo.JsonRpcIdStrategy
import kyo.JsonRpcUnknownMethodPolicy

/** Behavioral invariant tests for the kyo-browser CDP client port to kyo-jsonrpc.
  *
  * Each test corresponds to an INV-NNN invariant from `04-invariants.md` that can be
  * exercised end-to-end against the runtime stack (in-memory transport, real handler
  * dispatch, real backend wiring). Source-text invariants ; package layout, naming,
  * absence of legacy symbols, em-dash hygiene ; live in the regular code-review and
  * lint pass, not in unit tests.
  */
class JsonRpcPortInvariantsSpec extends BrowserTest:

    private val testLaunchCfg = Browser.LaunchConfig.default.copy(
        requestTimeout = 5.seconds,
        closeGrace = 500.millis
    )

    private val testVersionResult = BrowserVersionResult(
        protocolVersion = "0",
        product = "Headless/0",
        revision = "0",
        userAgent = "Mozilla/5.0 (Headless)",
        jsVersion = "0.0"
    )

    /** Creates a server endpoint that handles Browser.getVersion plus any `extraMethods`. */
    private def mkServerEndpoint(
        serverTransport: JsonRpcTransport,
        extraMethods: Seq[JsonRpcRoute[?, ?, ?]] = Seq.empty
    )(using Frame): JsonRpcHandler < (Async & Scope) =
        val versionMethod = JsonRpcRoute.request[BrowserGetVersionParams, BrowserVersionResult](
            "Browser.getVersion"
        ) { (_, _) => testVersionResult }
        JsonRpcHandler.init(
            serverTransport,
            versionMethod +: extraMethods,
            JsonRpcHandler.Config(
                codec = JsonRpcCodec.Lenient,
                maxInFlight = Present(8),
                idStrategy = JsonRpcIdStrategy.SequentialInt
            )
        )
    end mkServerEndpoint

    /** Paired backend + server within a Scope. */
    private def mkBackendWithServer(
        extraServerMethods: Seq[JsonRpcRoute[?, ?, ?]] = Seq.empty
    )(using Frame): (CdpBackend, JsonRpcHandler) < (Async & Scope & Abort[BrowserReadException | BrowserSetupException]) =
        JsonRpcTransport.inMemory.map { (client, server) =>
            mkServerEndpoint(server, extraServerMethods).map { serverEndpoint =>
                CdpBackend.initUnscoped(client, testLaunchCfg).map { backend =>
                    (backend, serverEndpoint)
                }
            }
        }

    // --- INV-015: per-sessionId routing via JsonRpcExtrasEncoder + ctx.extras ---

    "INV-015: round-trip exercises JsonRpcExtrasEncoder and ctx.extras routing" in {
        AtomicRef.init[Maybe[Maybe[Structure.Value]]](Absent).map { capturedExtrasRef =>
            AtomicRef.init[Maybe[CdpEvent.Generic]](Absent).map { frameEventRef =>
                val navigateMethod = JsonRpcRoute.request[NavigateParams, NavigateResult](
                    "Page.navigate"
                ) { (_, ctx) =>
                    capturedExtrasRef.set(Present(ctx.extras)).map(_ => NavigateResult("frame-rt"))
                }
                Scope.run {
                    mkBackendWithServer(Seq(navigateMethod)).map { (backend, serverEndpoint) =>
                        val tabBackend = backend.withSession(SessionId("rt"))
                        val handler: CdpEvent.Generic => Unit < Sync = ev =>
                            frameEventRef.set(Present(ev)).unit
                        backend.frameEventDispatchers.updateAndGet(_.update("rt", handler)).andThen {
                            Abort.run[BrowserReadException](
                                tabBackend.send[NavigateParams, NavigateResult](
                                    "Page.navigate",
                                    NavigateParams("https://rt.example.com")
                                )
                            ).andThen {
                                capturedExtrasRef.get.map {
                                    case Present(Present(Structure.Value.Record(fields))) =>
                                        assert(fields.exists {
                                            case ("sessionId", Structure.Value.Str("rt")) => true
                                            case _                                        => false
                                        })
                                    case other => fail(s"expected sessionId in extras but got: $other")
                                }
                            }.andThen {
                                val createdParams = ExecutionContextCreatedParams(
                                    ExecutionContextDescription(1, ExecutionContextAuxData(isDefault = true, frameId = "rt"))
                                )
                                val extras = JsonRpcExtrasEncoder.const(
                                    Structure.Value.Record(Chunk("sessionId" -> Structure.Value.Str("rt")))
                                )
                                Abort.run[Closed](
                                    serverEndpoint.notify[ExecutionContextCreatedParams](
                                        "Runtime.executionContextCreated",
                                        createdParams,
                                        extras
                                    )
                                ).andThen {
                                    assertEventually(frameEventRef.get.map(_.isDefined)).andThen {
                                        frameEventRef.get.map {
                                            case Present(ev) =>
                                                assert(ev.method == "Runtime.executionContextCreated")
                                                assert(ev.sessionId == Present(SessionId("rt")))
                                            case Absent => fail("frame event not dispatched")
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

    // --- INV-016: Browser.getVersion probe converts Closed to BrowserSetupFailedException ---

    "INV-016: Browser.getVersion probe converts Closed to BrowserSetupFailedException" in {
        JsonRpcTransport.inMemory.map { (client, server) =>
            server.close.andThen {
                Abort.run[BrowserReadException | BrowserSetupException](
                    Scope.run(CdpBackend.initUnscoped(client, testLaunchCfg))
                ).map {
                    case Result.Failure(ex: BrowserSetupFailedException) =>
                        assert(ex.getMessage.nonEmpty)
                    case other =>
                        fail(s"expected BrowserSetupFailedException but got: $other")
                }
            }
        }
    }

    // --- INV-017: typed Abort recovery for Closed, JsonRpcError, Timeout ---

    "INV-017: Closed at send surfaces as BrowserConnectionLostException" in {
        Scope.run {
            mkBackendWithServer().map { (backend, serverEndpoint) =>
                serverEndpoint.closeNow.andThen {
                    Async.sleep(50.millis).andThen {
                        Abort.run[BrowserReadException](
                            backend.send[NavigateParams, NavigateResult](
                                "Page.navigate",
                                NavigateParams("https://x.com")
                            )
                        ).map {
                            case Result.Failure(ex: BrowserConnectionLostException) =>
                                assert(ex.getMessage.nonEmpty)
                            case other =>
                                fail(s"expected BrowserConnectionLostException but got: $other")
                        }
                    }
                }
            }
        }
    }

    "INV-017: JsonRpcError at send surfaces as BrowserProtocolErrorException" in {
        val errorMethod = JsonRpcRoute.request[NavigateParams, NavigateResult](
            "Page.navigate"
        ) { (_, _) =>
            Abort.fail(JsonRpcMethodNotFoundError("Page.navigate", Chunk.empty))
        }
        Scope.run {
            mkBackendWithServer(Seq(errorMethod)).map { (backend, _) =>
                Abort.run[BrowserReadException](
                    backend.send[NavigateParams, NavigateResult]("Page.navigate", NavigateParams("https://x.com"))
                ).map {
                    case Result.Failure(ex: BrowserProtocolErrorException) =>
                        assert(ex.getMessage.contains("Method not found"))
                    case other =>
                        fail(s"expected BrowserProtocolErrorException but got: $other")
                }
            }
        }
    }

    "INV-017: Timeout at send surfaces as BrowserConnectionLostException" in {
        // Use a very short requestTimeout; server handles getVersion probe but NEVER replies to Page.navigate
        // Server uses Drop policy for unknown requests so no MethodNotFound reply is sent.
        val timeoutCfg = testLaunchCfg.copy(requestTimeout = 200.millis)
        val dropPolicy = JsonRpcUnknownMethodPolicy(
            onUnknownRequest = JsonRpcUnknownMethodPolicy.UnknownAction.Drop,
            onUnknownNotification = JsonRpcUnknownMethodPolicy.UnknownAction.Drop,
            ignoreUnknownNotification = _ => false
        )
        Scope.run {
            JsonRpcTransport.inMemory.map { (client, server) =>
                val versionMethod = JsonRpcRoute.request[BrowserGetVersionParams, BrowserVersionResult](
                    "Browser.getVersion"
                ) { (_, _) => testVersionResult }
                JsonRpcHandler.init(
                    server,
                    Seq(versionMethod),
                    JsonRpcHandler.Config(
                        codec = JsonRpcCodec.Lenient,
                        unknownMethod = dropPolicy,
                        maxInFlight = Present(8),
                        idStrategy = JsonRpcIdStrategy.SequentialInt
                    )
                ).map { _ =>
                    CdpBackend.initUnscoped(client, timeoutCfg).map { backend =>
                        Abort.run[BrowserReadException](
                            backend.send[NavigateParams, NavigateResult](
                                "Page.navigate",
                                NavigateParams("https://x.com")
                            )
                        ).map {
                            case Result.Failure(ex: BrowserConnectionLostException) =>
                                assert(ex.getMessage.contains("Request timeout"))
                            case other =>
                                fail(s"expected BrowserConnectionLostException from timeout but got: $other")
                        }
                    }
                }
            }
        }
    }

    // --- INV-018: negative-id sentinel from dialogIdCounter disjoint from SequentialInt ---

    "INV-018: dialogIdCounter starts at Int.MinValue and produces negative ids disjoint from SequentialInt" in {
        AtomicRef.init[Maybe[Long]](Absent).map { dialogIdRef =>
            AtomicRef.init[Maybe[Long]](Absent).map { regularIdRef =>
                val handleDialogMethod = JsonRpcRoute.request[HandleJavaScriptDialogParams, Unit](
                    "Page.handleJavaScriptDialog"
                ) { (_, ctx) =>
                    ctx.requestId match
                        case Present(JsonRpcId.Num(n)) => dialogIdRef.set(Present(n))
                        case _                         => Kyo.unit
                }
                val navigateMethod = JsonRpcRoute.request[NavigateParams, NavigateResult](
                    "Page.navigate"
                ) { (_, ctx) =>
                    ctx.requestId match
                        case Present(JsonRpcId.Num(n)) =>
                            regularIdRef.set(Present(n)).map(_ => NavigateResult("f"))
                        case _ => NavigateResult("f")
                }
                Scope.run {
                    mkBackendWithServer(Seq(handleDialogMethod, navigateMethod)).map { (backend, _) =>
                        Abort.run[Closed](
                            backend.dialogQueue.put((true, "answer", Absent))
                        ).andThen {
                            assertEventually(dialogIdRef.get.map(_.isDefined)).andThen {
                                Abort.run[BrowserReadException](
                                    backend.send[NavigateParams, NavigateResult](
                                        "Page.navigate",
                                        NavigateParams("https://x.com")
                                    )
                                ).andThen {
                                    assertEventually(regularIdRef.get.map(_.isDefined)).andThen {
                                        dialogIdRef.get.map {
                                            case Present(dialogId) =>
                                                assert(dialogId < 0)
                                                assert(dialogId == Int.MinValue.toLong)
                                            case Absent => fail("dialog drainer did not fire")
                                        }.andThen {
                                            regularIdRef.get.map {
                                                case Present(regularId) =>
                                                    assert(regularId > 0)
                                                case Absent => fail("regular send did not fire")
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

end JsonRpcPortInvariantsSpec
