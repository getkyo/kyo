package kyo.internal

import CdpTypes.*
import kyo.*
import kyo.JsonRpcExtrasEncoder
import kyo.JsonRpcIdStrategy
import kyo.internal.cdp.PageDownload

/** Smoke tests for the [[CdpBackend]] runtime class.
  *
  * Tests use paired [[JsonRpcTransport.inMemory]] transports with a fake-server
  * [[JsonRpcHandler]] to exercise the new class without a live browser process.
  */
class CdpBackendSmokeTest extends BrowserTest:

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

    /** Creates a server-side [[JsonRpcHandler]] that handles `Browser.getVersion` with
      * [[testVersionResult]] and routes any other request to `extraMethods`.
      * Registers the endpoint with the enclosing Scope so it is closed on test exit.
      */
    private def mkServerEndpoint(
        serverTransport: JsonRpcTransport,
        extraMethods: Seq[JsonRpcRoute[?, ?, ?]] = Seq.empty
    )(using Frame): JsonRpcHandler < (Async & Scope) =
        val versionMethod = JsonRpcRoute.request[BrowserGetVersionParams, BrowserVersionResult](
            "Browser.getVersion"
        ) { (_, _) => testVersionResult }
        val config = JsonRpcHandler.Config(
            codec = JsonRpcEnvelope.lenientSchema,
            maxInFlight = Present(8),
            idStrategy = JsonRpcIdStrategy.SequentialInt
        )
        JsonRpcHandler.init(serverTransport, versionMethod +: extraMethods, config)
    end mkServerEndpoint

    /** Initialises a [[CdpBackend]] using a paired in-memory transport where the server
      * side is a [[JsonRpcHandler]] registered with the enclosing Scope.
      */
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

    // --- Tests ---

    "init via inMemory transport returns live backend" in {
        Scope.run {
            mkBackendWithServer().map { (backend, _) =>
                assert(backend.sessionId == Absent)
                backend.dialogHandlers.get.map { handlers =>
                    assert(handlers.isEmpty)
                }
            }
        }
    }

    "init fails fast with BrowserSetupFailedException when probe returns Closed" in {
        JsonRpcTransport.inMemory.map { (client, server) =>
            server.close.andThen {
                Abort.run[BrowserReadException | BrowserSetupException](
                    Scope.run(CdpBackend.initUnscoped(client, testLaunchCfg))
                ).map {
                    case Result.Failure(_: BrowserSetupFailedException) => succeed
                    case other                                          => fail(s"expected BrowserSetupFailedException but got: $other")
                }
            }
        }
    }

    "send writes wire bytes that match legacy CDP envelope shape" in {
        AtomicRef.init[Maybe[Maybe[Structure.Value]]](Absent).map { capturedExtrasRef =>
            val navigateMethod = JsonRpcRoute.request[NavigateParams, NavigateResult](
                "Page.navigate"
            ) { (_, ctx) =>
                capturedExtrasRef.set(Present(ctx.extras)).map(_ => NavigateResult("frame-1"))
            }
            Scope.run {
                mkBackendWithServer(Seq(navigateMethod)).map { (backend, _) =>
                    Abort.run[BrowserReadException](
                        backend.send[NavigateParams, NavigateResult]("Page.navigate", NavigateParams("https://example.com"))
                    ).map {
                        case Result.Success(_) =>
                            capturedExtrasRef.get.map {
                                case Present(extras) =>
                                    assert(extras == Absent, s"expected no extras for non-session call but got: $extras")
                                case Absent => fail("no extras captured")
                            }
                        case other => fail(s"expected success but got: $other")
                    }
                }
            }
        }
    }

    "session-scoped backend stamps sessionId via JsonRpcExtrasEncoder" in {
        AtomicRef.init[Maybe[Maybe[Structure.Value]]](Absent).map { capturedExtrasRef =>
            val navigateMethod = JsonRpcRoute.request[NavigateParams, NavigateResult](
                "Page.navigate"
            ) { (_, ctx) =>
                capturedExtrasRef.set(Present(ctx.extras)).map(_ => NavigateResult("frame-2"))
            }
            Scope.run {
                mkBackendWithServer(Seq(navigateMethod)).map { (backend, _) =>
                    val tabBackend = backend.withSession(SessionId("abc"))
                    Abort.run[BrowserReadException](
                        tabBackend.send[NavigateParams, NavigateResult]("Page.navigate", NavigateParams("https://example.com"))
                    ).map {
                        case Result.Success(_) =>
                            capturedExtrasRef.get.map {
                                case Present(Present(Structure.Value.Record(fields))) =>
                                    assert(fields.exists {
                                        case ("sessionId", Structure.Value.Str("abc")) => true
                                        case _                                         => false
                                    })
                                case other => fail(s"expected sessionId in extras but got: $other")
                            }
                        case other => fail(s"expected success but got: $other")
                    }
                }
            }
        }
    }

    "Page.javascriptDialogOpening routes via ctx.extras to the per-session handler" in {
        Scope.run {
            mkBackendWithServer().map { (backend, serverEndpoint) =>
                AtomicRef.init(Chunk.empty[Browser.DialogEvent]).map { recorder =>
                    backend.dialogHandlers.updateAndGet(_.update("s1", (true, "ok"))).andThen {
                        backend.dialogRecorders.updateAndGet(_.update("s1", recorder)).andThen {
                            Abort.run[Closed](
                                serverEndpoint.notify[JavascriptDialogOpeningParams](
                                    "Page.javascriptDialogOpening",
                                    JavascriptDialogOpeningParams(url = "http://a.com", message = "hi", `type` = "alert"),
                                    JsonRpcExtrasEncoder.const(Structure.Value.Record(Chunk("sessionId" -> Structure.Value.Str("s1"))))
                                )
                            ).andThen {
                                assertEventually(recorder.get.map(_.nonEmpty)).andThen {
                                    recorder.get.map { events =>
                                        assert(events.size == 1)
                                        assert(events.head.kind == Browser.DialogType.Alert)
                                        assert(events.head.message == "hi")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    "Page.javascriptDialogOpening auto-dismisses when no handler is registered" in {
        AtomicRef.init[Maybe[Long]](Absent).map { capturedDismissId =>
            val handleDialogMethod = JsonRpcRoute.request[HandleJavaScriptDialogParams, Unit](
                "Page.handleJavaScriptDialog"
            ) { (_, ctx) =>
                // Capture the request id to verify the drainer sent the auto-dismiss
                ctx.requestId match
                    case Present(JsonRpcId.Num(n)) => capturedDismissId.set(Present(n))
                    case _                         => Kyo.unit
            }
            Scope.run {
                mkBackendWithServer(Seq(handleDialogMethod)).map { (backend, serverEndpoint) =>
                    Abort.run[Closed](
                        serverEndpoint.notify[JavascriptDialogOpeningParams](
                            "Page.javascriptDialogOpening",
                            JavascriptDialogOpeningParams(url = "http://x.com", message = "alert!", `type` = "alert"),
                            JsonRpcExtrasEncoder.const(Structure.Value.Record(Chunk("sessionId" -> Structure.Value.Str("unknown"))))
                        )
                    ).andThen {
                        assertEventually(capturedDismissId.get.map(_.isDefined)).andThen {
                            capturedDismissId.get.map {
                                case Present(n) => assert(n < 0)
                                case Absent     => fail("dialog drainer never sent auto-dismiss")
                            }
                        }
                    }
                }
            }
        }
    }

    "Runtime.executionContextCreated routes via ctx.extras to frame-event dispatcher" in {
        Scope.run {
            mkBackendWithServer().map { (backend, serverEndpoint) =>
                AtomicRef.init(Chunk.empty[CdpEvent.Generic]).map { capture =>
                    val handler: CdpEvent.Generic => Unit < Sync = ev =>
                        capture.updateAndGet(_ :+ ev).unit
                    backend.frameEventDispatchers.updateAndGet(_.update("frame1", handler)).andThen {
                        val params = ExecutionContextCreatedParams(
                            ExecutionContextDescription(1, ExecutionContextAuxData(isDefault = true, frameId = "frame1"))
                        )
                        val extras = JsonRpcExtrasEncoder.const(Structure.Value.Record(Chunk("sessionId" -> Structure.Value.Str("frame1"))))
                        Abort.run[Closed](
                            serverEndpoint.notify[ExecutionContextCreatedParams](
                                "Runtime.executionContextCreated",
                                params,
                                extras
                            )
                        ).andThen(Abort.run[Closed](
                            serverEndpoint.notify[ExecutionContextCreatedParams](
                                "Runtime.executionContextCreated",
                                params,
                                extras
                            )
                        )).andThen {
                            assertEventually(capture.get.map(_.size >= 2)).andThen {
                                capture.get.map { events =>
                                    assert(events.size == 2)
                                    assert(events.forall(_.method == "Runtime.executionContextCreated"))
                                    assert(events.forall(_.sessionId == Present(SessionId("frame1"))))
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    "Page.downloadWillBegin routes via ctx.extras to download-event dispatcher" in {
        Scope.run {
            mkBackendWithServer().map { (backend, serverEndpoint) =>
                AtomicRef.init(Chunk.empty[CdpEvent.Generic]).map { capture =>
                    val handler: CdpEvent.Generic => Unit < Sync = ev =>
                        capture.updateAndGet(_ :+ ev).unit
                    backend.downloadEventDispatchers.updateAndGet(_.update("dl", handler)).andThen {
                        val params = PageDownload.DownloadWillBeginWire(
                            guid = "g",
                            url = "http://example.com/file.pdf",
                            suggestedFilename = "x.pdf"
                        )
                        val extras = JsonRpcExtrasEncoder.const(Structure.Value.Record(Chunk("sessionId" -> Structure.Value.Str("dl"))))
                        Abort.run[Closed](
                            serverEndpoint.notify[PageDownload.DownloadWillBeginWire](
                                "Page.downloadWillBegin",
                                params,
                                extras
                            )
                        ).andThen {
                            assertEventually(capture.get.map(_.nonEmpty)).andThen {
                                capture.get.map { events =>
                                    assert(events.size == 1)
                                    assert(events.head.method == "Page.downloadWillBegin")
                                    assert(events.head.sessionId == Present(SessionId("dl")))
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    "Runtime.consoleAPICalled routes via ctx.extras to the console-event dispatcher" in {
        Scope.run {
            mkBackendWithServer().map { (backend, serverEndpoint) =>
                AtomicRef.init(Chunk.empty[CdpEvent.Generic]).map { capture =>
                    val handler: CdpEvent.Generic => Unit < Sync = ev =>
                        capture.updateAndGet(_ :+ ev).unit
                    backend.consoleEventDispatchers.updateAndGet(_.update("c1", handler)).andThen {
                        val params = ConsoleApiCalledWire(
                            `type` = "log",
                            args = Seq(RemoteObjectValue(`type` = "string", value = Present("hello")))
                        )
                        val extras = JsonRpcExtrasEncoder.const(Structure.Value.Record(Chunk("sessionId" -> Structure.Value.Str("c1"))))
                        Abort.run[Closed](
                            serverEndpoint.notify[ConsoleApiCalledWire](
                                "Runtime.consoleAPICalled",
                                params,
                                extras
                            )
                        ).andThen {
                            assertEventually(capture.get.map(_.nonEmpty)).andThen {
                                capture.get.map { events =>
                                    assert(events.size == 1)
                                    assert(events.head.method == "Runtime.consoleAPICalled")
                                    assert(events.head.sessionId == Present(SessionId("c1")))
                                    events.head.params match
                                        case w: ConsoleApiCalledWire =>
                                            assert(w.`type` == "log", s"expected type 'log' but got ${w.`type`}")
                                            assert(w.args.size == 1, s"expected one decoded arg but got ${w.args.size}")
                                            val arg = w.args.head
                                            assert(arg.`type` == "string", s"expected arg type 'string' but got ${arg.`type`}")
                                            assert(arg.value == Present("hello"), s"expected arg value 'hello' but got ${arg.value}")
                                        case other => fail(s"expected ConsoleApiCalledWire payload but got $other")
                                    end match
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // A Page.screencastFrame notification with NO registered screencast handler is dropped: dispatchEvent's
    // `case Absent => Kyo.unit` arm. This is safe by construction because Browser.startScreencast registers the
    // handler first (Browser.scala) and there is no shared event stream into which an un-dispatched frame could be
    // pushed (the pre-port push-on-no-handler path no longer exists). The endpoint stays usable afterwards.
    "Page.screencastFrame with no registered handler is dropped and the endpoint stays usable" in {
        Scope.run {
            mkBackendWithServer().map { (backend, serverEndpoint) =>
                AtomicRef.init(0).map { invocations =>
                    // Register a counting handler under a DIFFERENT session key than the frame carries, so no handler
                    // matches the frame's session and the Absent arm is exercised.
                    val handler: CdpEvent.Generic => Unit < Sync = _ => invocations.updateAndGet(_ + 1).unit
                    backend.screencastEventDispatchers.updateAndGet(_.update("other-session", handler)).andThen {
                        val params = ScreencastFrameWire("AAAA", ScreencastFrameMetadata(0.0, 0.0), 1)
                        val extras = JsonRpcExtrasEncoder.const(Structure.Value.Record(Chunk("sessionId" -> Structure.Value.Str("cast"))))
                        Abort.run[Closed](
                            serverEndpoint.notify[ScreencastFrameWire](
                                "Page.screencastFrame",
                                params,
                                extras
                            )
                        ).andThen {
                            // A follow-up call must succeed: the dropped frame neither crashed the endpoint nor was
                            // delivered to the unrelated handler.
                            Abort.run[BrowserReadException](
                                backend.send[BrowserGetVersionParams, BrowserVersionResult]("Browser.getVersion", BrowserGetVersionParams())
                            ).map {
                                case Result.Success(v) =>
                                    assert(v.product == testVersionResult.product, s"expected the endpoint to stay usable but got $v")
                                    invocations.get.map { count =>
                                        assert(
                                            count == 0,
                                            s"expected the un-dispatched screencast frame to be dropped but the handler ran $count time(s)"
                                        )
                                    }
                                case other => fail(s"expected the endpoint to stay usable after the dropped frame but got $other")
                            }
                        }
                    }
                }
            }
        }
    }

    "close(gracePeriod) sequences endpoint.close, dialogQueue.close, dialogDrainer.interrupt" in {
        Scope.run {
            mkBackendWithServer().map { (backend, _) =>
                backend.close(500.millis).andThen {
                    backend.dialogDrainer.done.map { done =>
                        assert(done)
                    }.andThen {
                        backend.dialogQueue.closed.map { closed =>
                            assert(closed)
                        }
                    }
                }
            }
        }
    }

    "dialog drainer issues sendUnmatched with negative JsonRpcId.Num" in {
        AtomicRef.init[Maybe[Long]](Absent).map { capturedIdRef =>
            val handleDialogMethod = JsonRpcRoute.request[HandleJavaScriptDialogParams, Unit](
                "Page.handleJavaScriptDialog"
            ) { (_, ctx) =>
                ctx.requestId match
                    case Present(JsonRpcId.Num(n)) => capturedIdRef.set(Present(n))
                    case _                         => Kyo.unit
            }
            Scope.run {
                mkBackendWithServer(Seq(handleDialogMethod)).map { (backend, _) =>
                    val sid = SessionId("s")
                    Abort.run[Closed](
                        backend.dialogQueue.put((true, "answer", Present(sid)))
                    ).andThen {
                        assertEventually(capturedIdRef.get.map(_.isDefined)).andThen {
                            capturedIdRef.get.map {
                                case Present(n) =>
                                    assert(n < 0)
                                    assert(n == Int.MinValue.toLong)
                                case Absent =>
                                    fail("dialog drainer did not send handleJavaScriptDialog")
                            }
                        }
                    }
                }
            }
        }
    }

end CdpBackendSmokeTest
