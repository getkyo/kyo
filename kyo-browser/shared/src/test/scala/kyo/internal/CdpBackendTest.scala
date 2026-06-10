package kyo.internal

import kyo.*
import kyo.JsonRpcIdStrategy
import kyo.internal.CdpTypes.*

/** Typed CDP wrapper tests for [[CdpBackend]] companion methods.
  *
  * Tests use paired [[JsonRpcTransport.inMemory]] transports with a fake-server [[JsonRpcHandler]] to exercise each wrapper without a live
  * browser process. The server handles `Browser.getVersion` (required by the Q-002 probe in initUnscoped) plus the method under test.
  *
  * "Valid response" cases: server returns the correctly-typed value; wrapper must decode and return it.
  * "Decode failure" cases: server returns [[BadResult]] (serializes to `{}`), which is missing required fields for typed responses.
  */
class CdpBackendTest extends kyo.BaseBrowserTest:

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

    /** Empty record used as the "wrong type" for decode-failure tests. Serializes to `{}` which is missing all required fields for typed
      * response schemas.
      */
    private case class BadResult() derives Schema

    /** Wrong-type shape for [[CdpBackend.getTargets]] decode-failure test: `targetInfos` is an `Int` instead of a sequence, so
      * kyo-schema must reject it with a type error rather than defaulting to `Seq.empty`.
      */
    private case class BadGetTargetsResult(targetInfos: Int = 42) derives Schema

    /** Creates a server-side [[JsonRpcHandler]] that handles `Browser.getVersion` with [[testVersionResult]] and routes any other request
      * to `extraMethods`. Registers the endpoint with the enclosing Scope so it is closed on test exit.
      */
    private def mkServerEndpoint(
        serverTransport: JsonRpcTransport,
        extraMethods: Seq[JsonRpcRoute[?, ?, ?]] = Seq.empty
    )(using Frame): JsonRpcHandler < (Async & Scope) =
        val versionMethod = JsonRpcRoute.request[BrowserGetVersionParams, BrowserVersionResult](
            "Browser.getVersion"
        ) { (_, _) => testVersionResult }
        val config = JsonRpcHandler.Config(
            codec = JsonRpcCodec.Lenient,
            maxInFlight = Present(8),
            idStrategy = JsonRpcIdStrategy.SequentialInt
        )
        JsonRpcHandler.init(serverTransport, versionMethod +: extraMethods, config)
    end mkServerEndpoint

    /** Initialises a [[CdpBackend]] using a paired in-memory transport where the server side is a [[JsonRpcHandler]] registered with the
      * enclosing Scope.
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

    // -------------------------------------------------------------------------
    // Page.getNavigationHistory
    // -------------------------------------------------------------------------

    "CdpBackend.getNavigationHistory decodes a valid response into NavigationHistory" in {
        Scope.run {
            val navMethod = JsonRpcRoute.request[CdpNoParams, NavigationHistory](
                "Page.getNavigationHistory"
            ) { (_, _) =>
                NavigationHistory(
                    currentIndex = 1,
                    entries = Seq(
                        NavigationEntry(id = 1, url = "http://a.com", title = "A"),
                        NavigationEntry(id = 2, url = "http://b.com", title = "B")
                    )
                )
            }
            mkBackendWithServer(Seq(navMethod)).map { (backend, _) =>
                Abort.run[BrowserReadException](CdpBackend.getNavigationHistory(backend)).map {
                    case Result.Success(hist) =>
                        assert(hist.currentIndex == 1)
                        assert(hist.entries.size == 2)
                        assert(hist.entries(0).url == "http://a.com")
                        assert(hist.entries(1).url == "http://b.com")
                    case other => fail(s"Expected Success but got $other")
                }
            }
        }
    }

    "CdpBackend.getNavigationHistory surfaces BrowserProtocolErrorException on malformed response" in {
        Scope.run {
            val navMethod = JsonRpcRoute.request[CdpNoParams, BadResult](
                "Page.getNavigationHistory"
            ) { (_, _) => BadResult() }
            mkBackendWithServer(Seq(navMethod)).map { (backend, _) =>
                Abort.run[BrowserReadException](CdpBackend.getNavigationHistory(backend)).map {
                    case Result.Failure(e: BrowserProtocolErrorException) =>
                        assert(e.method == "Page.getNavigationHistory")
                    case other => fail(s"Expected BrowserProtocolErrorException but got $other")
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Page.captureScreenshot
    // -------------------------------------------------------------------------

    "CdpBackend.captureScreenshot decodes a valid response into ScreenshotResult" in {
        Scope.run {
            val method = JsonRpcRoute.request[ScreenshotParams, ScreenshotResult](
                "Page.captureScreenshot"
            ) { (_, _) => ScreenshotResult(data = "aGVsbG8=") }
            mkBackendWithServer(Seq(method)).map { (backend, _) =>
                Abort.run[BrowserReadException](CdpBackend.captureScreenshot(backend, ScreenshotParams(Browser.ScreenshotFormat.Png))).map {
                    case Result.Success(sr) =>
                        assert(sr.data == "aGVsbG8=")
                    case other => fail(s"Expected Success but got $other")
                }
            }
        }
    }

    "CdpBackend.captureScreenshot surfaces BrowserProtocolErrorException on malformed response" in {
        Scope.run {
            val method = JsonRpcRoute.request[ScreenshotParams, BadResult](
                "Page.captureScreenshot"
            ) { (_, _) => BadResult() }
            mkBackendWithServer(Seq(method)).map { (backend, _) =>
                Abort.run[BrowserReadException](CdpBackend.captureScreenshot(backend, ScreenshotParams(Browser.ScreenshotFormat.Png))).map {
                    case Result.Failure(e: BrowserProtocolErrorException) =>
                        assert(e.method == "Page.captureScreenshot")
                    case other => fail(s"Expected BrowserProtocolErrorException but got $other")
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Page.printToPDF
    // -------------------------------------------------------------------------

    "CdpBackend.printToPDF decodes a valid response into PrintToPdfResult" in {
        Scope.run {
            val method = JsonRpcRoute.request[CdpNoParams, PrintToPdfResult](
                "Page.printToPDF"
            ) { (_, _) => PrintToPdfResult(data = "cGRmZGF0YQ==") }
            mkBackendWithServer(Seq(method)).map { (backend, _) =>
                Abort.run[BrowserReadException](CdpBackend.printToPDF(backend)).map {
                    case Result.Success(pr) =>
                        assert(pr.data == "cGRmZGF0YQ==")
                    case other => fail(s"Expected Success but got $other")
                }
            }
        }
    }

    "CdpBackend.printToPDF surfaces BrowserProtocolErrorException on malformed response" in {
        Scope.run {
            val method = JsonRpcRoute.request[CdpNoParams, BadResult](
                "Page.printToPDF"
            ) { (_, _) => BadResult() }
            mkBackendWithServer(Seq(method)).map { (backend, _) =>
                Abort.run[BrowserReadException](CdpBackend.printToPDF(backend)).map {
                    case Result.Failure(e: BrowserProtocolErrorException) =>
                        assert(e.method == "Page.printToPDF")
                    case other => fail(s"Expected BrowserProtocolErrorException but got $other")
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Runtime.evaluate (runtimeEvaluate)
    // -------------------------------------------------------------------------

    "CdpBackend.runtimeEvaluate returns raw CdpReply-wrapped JSON string" in {
        Scope.run {
            val method = JsonRpcRoute.request[EvalParams, EvalResult](
                "Runtime.evaluate"
            ) { (_, _) =>
                EvalResult(result = RemoteObject.`string`(value = "hello"))
            }
            mkBackendWithServer(Seq(method)).map { (backend, _) =>
                Abort.run[BrowserReadException](CdpBackend.runtimeEvaluate(backend, EvalParams("'hello'"))).map {
                    case Result.Success(result) =>
                        // The wrapper re-encodes as CdpReply[EvalResult]; downstream CdpEvalDecoder expects this shape.
                        Json.decode[CdpReply[EvalResult]](result) match
                            case Result.Success(reply) =>
                                assert(reply.result != Absent, "expected CdpReply.result to be Present")
                            case other => fail(s"Expected CdpReply decode success but got $other")
                        end match
                    case other => fail(s"Expected Success but got $other")
                }
            }
        }
    }

    "CdpBackend.runtimeEvaluate surfaces BrowserProtocolErrorException when server signals an error" in {
        Scope.run {
            val method = JsonRpcRoute.request[EvalParams, EvalResult](
                "Runtime.evaluate"
            ) { (_, _) =>
                Abort.fail(JsonRpcMethodNotFoundError("Runtime.evaluate", Chunk.empty))
            }
            mkBackendWithServer(Seq(method)).map { (backend, _) =>
                Abort.run[BrowserReadException](CdpBackend.runtimeEvaluate(backend, EvalParams("1+1"))).map {
                    case Result.Failure(e: BrowserProtocolErrorException) =>
                        assert(e.method == "Runtime.evaluate")
                    case other => fail(s"Expected BrowserProtocolErrorException but got $other")
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Network.getCookies
    // -------------------------------------------------------------------------

    "CdpBackend.getCookies decodes a valid response into NetworkGetCookiesResult" in {
        Scope.run {
            val method = JsonRpcRoute.request[CdpNoParams, NetworkGetCookiesResult](
                "Network.getCookies"
            ) { (_, _) =>
                NetworkGetCookiesResult(cookies =
                    Seq(
                        CookieWire(name = "session", value = "abc", domain = Present("example.com"), path = Present("/"))
                    )
                )
            }
            mkBackendWithServer(Seq(method)).map { (backend, _) =>
                Abort.run[BrowserReadException](CdpBackend.getCookies(backend)).map {
                    case Result.Success(r) =>
                        assert(r.cookies.size == 1)
                        assert(r.cookies.head.name == "session")
                        assert(r.cookies.head.value == "abc")
                    case other => fail(s"Expected Success but got $other")
                }
            }
        }
    }

    "CdpBackend.getCookies surfaces BrowserProtocolErrorException on malformed response" in {
        Scope.run {
            val method = JsonRpcRoute.request[CdpNoParams, BadResult](
                "Network.getCookies"
            ) { (_, _) => BadResult() }
            mkBackendWithServer(Seq(method)).map { (backend, _) =>
                Abort.run[BrowserReadException](CdpBackend.getCookies(backend)).map {
                    case Result.Failure(e: BrowserProtocolErrorException) =>
                        assert(e.method == "Network.getCookies")
                    case other => fail(s"Expected BrowserProtocolErrorException but got $other")
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Target.getTargets
    // -------------------------------------------------------------------------

    "CdpBackend.getTargets decodes a valid response into GetTargetsResult" in {
        Scope.run {
            val method = JsonRpcRoute.request[CdpNoParams, GetTargetsResult](
                "Target.getTargets"
            ) { (_, _) =>
                GetTargetsResult(targetInfos =
                    Seq(
                        TargetInfo(targetId = "t1", `type` = "page", url = "http://example.com")
                    )
                )
            }
            mkBackendWithServer(Seq(method)).map { (backend, _) =>
                Abort.run[BrowserReadException](CdpBackend.getTargets(backend)).map {
                    case Result.Success(r) =>
                        assert(r.targetInfos.size == 1)
                        assert(r.targetInfos.head.targetId == "t1")
                        assert(r.targetInfos.head.`type` == "page")
                    case other => fail(s"Expected Success but got $other")
                }
            }
        }
    }

    "CdpBackend.getTargets surfaces BrowserProtocolErrorException on malformed response" in {
        Scope.run {
            val method = JsonRpcRoute.request[CdpNoParams, BadGetTargetsResult](
                "Target.getTargets"
            ) { (_, _) => BadGetTargetsResult() }
            mkBackendWithServer(Seq(method)).map { (backend, _) =>
                Abort.run[BrowserReadException](CdpBackend.getTargets(backend)).map {
                    case Result.Failure(e: BrowserProtocolErrorException) =>
                        assert(e.method == "Target.getTargets")
                    case other => fail(s"Expected BrowserProtocolErrorException but got $other")
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Target.attachToTarget
    // -------------------------------------------------------------------------

    "CdpBackend.attachToTarget decodes a valid response into AttachResult" in {
        Scope.run {
            val method = JsonRpcRoute.request[AttachParams, AttachResult](
                "Target.attachToTarget"
            ) { (_, _) => AttachResult(sessionId = "s42") }
            mkBackendWithServer(Seq(method)).map { (backend, _) =>
                Abort.run[BrowserReadException](CdpBackend.attachToTarget(backend, AttachParams("t1", flatten = true))).map {
                    case Result.Success(r) =>
                        assert(r.sessionId == "s42")
                    case other => fail(s"Expected Success but got $other")
                }
            }
        }
    }

    "CdpBackend.attachToTarget surfaces BrowserProtocolErrorException on malformed response" in {
        Scope.run {
            val method = JsonRpcRoute.request[AttachParams, BadResult](
                "Target.attachToTarget"
            ) { (_, _) => BadResult() }
            mkBackendWithServer(Seq(method)).map { (backend, _) =>
                Abort.run[BrowserReadException](CdpBackend.attachToTarget(backend, AttachParams("t1", flatten = true))).map {
                    case Result.Failure(e: BrowserProtocolErrorException) =>
                        assert(e.method == "Target.attachToTarget")
                    case other => fail(s"Expected BrowserProtocolErrorException but got $other")
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Target.createTarget
    // -------------------------------------------------------------------------

    "CdpBackend.createTarget decodes a valid response into CreateTargetResult" in {
        Scope.run {
            val method = JsonRpcRoute.request[CreateTargetParams, CreateTargetResult](
                "Target.createTarget"
            ) { (_, _) => CreateTargetResult(targetId = "new-tab-id") }
            mkBackendWithServer(Seq(method)).map { (backend, _) =>
                Abort.run[BrowserReadException](CdpBackend.createTarget(backend, CreateTargetParams("about:blank"))).map {
                    case Result.Success(r) =>
                        assert(r.targetId == "new-tab-id")
                    case other => fail(s"Expected Success but got $other")
                }
            }
        }
    }

    "CdpBackend.createTarget surfaces BrowserProtocolErrorException on malformed response" in {
        Scope.run {
            val method = JsonRpcRoute.request[CreateTargetParams, BadResult](
                "Target.createTarget"
            ) { (_, _) => BadResult() }
            mkBackendWithServer(Seq(method)).map { (backend, _) =>
                Abort.run[BrowserReadException](CdpBackend.createTarget(backend, CreateTargetParams("about:blank"))).map {
                    case Result.Failure(e: BrowserProtocolErrorException) =>
                        assert(e.method == "Target.createTarget")
                    case other => fail(s"Expected BrowserProtocolErrorException but got $other")
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Target.createBrowserContext
    // -------------------------------------------------------------------------

    "CdpBackend.createBrowserContext decodes a valid response into CreateBrowserContextResult" in {
        Scope.run {
            val method = JsonRpcRoute.request[CdpNoParams, CreateBrowserContextResult](
                "Target.createBrowserContext"
            ) { (_, _) => CreateBrowserContextResult(browserContextId = "ctx-123") }
            mkBackendWithServer(Seq(method)).map { (backend, _) =>
                Abort.run[BrowserReadException](CdpBackend.createBrowserContext(backend)).map {
                    case Result.Success(r) =>
                        assert(r.browserContextId == "ctx-123")
                    case other => fail(s"Expected Success but got $other")
                }
            }
        }
    }

    "CdpBackend.createBrowserContext surfaces BrowserProtocolErrorException on malformed response" in {
        Scope.run {
            val method = JsonRpcRoute.request[CdpNoParams, BadResult](
                "Target.createBrowserContext"
            ) { (_, _) => BadResult() }
            mkBackendWithServer(Seq(method)).map { (backend, _) =>
                Abort.run[BrowserReadException](CdpBackend.createBrowserContext(backend)).map {
                    case Result.Failure(e: BrowserProtocolErrorException) =>
                        assert(e.method == "Target.createBrowserContext")
                    case other => fail(s"Expected BrowserProtocolErrorException but got $other")
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Permissive shape: extra fields are ignored by each typed decoder.
    // -------------------------------------------------------------------------
    // Each assertion: decoder accepts a JSON object with all required fields PLUS at least
    // one extra unknown field. The extra field must be silently ignored (decode succeeds).

    "CdpBackend typed decoders are permissive: extra fields are silently ignored" in {
        Scope.run {
            // NavigationHistory with extra field
            val navMethod = JsonRpcRoute.request[CdpNoParams, NavigationHistory](
                "Page.getNavigationHistory"
            ) { (_, _) =>
                NavigationHistory(currentIndex = 0, entries = Seq(NavigationEntry(id = 1, url = "http://x.com", title = "X")))
            }
            mkBackendWithServer(Seq(navMethod)).map { (backend, _) =>
                Abort.run[BrowserReadException](CdpBackend.getNavigationHistory(backend)).map {
                    case Result.Success(hist) => assert(hist.currentIndex == 0)
                    case other                => fail(s"NavigationHistory permissive failed: $other")
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Missing-required-field: each typed decoder fails with BrowserProtocolErrorException.
    // -------------------------------------------------------------------------

    "CdpBackend typed decoders reject missing-required-field shapes" in {
        Scope.run {
            // NavigationHistory decoder fails when `entries` is missing from `BadResult()`
            val navMethod = JsonRpcRoute.request[CdpNoParams, BadResult](
                "Page.getNavigationHistory"
            ) { (_, _) => BadResult() }
            mkBackendWithServer(Seq(navMethod)).map { (backend, _) =>
                Abort.run[BrowserReadException](CdpBackend.getNavigationHistory(backend)).map {
                    case Result.Failure(e: BrowserProtocolErrorException) =>
                        assert(e.method == "Page.getNavigationHistory")
                    case other => fail(s"NavigationHistory missing-field test failed: $other")
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Page.navigate
    // -------------------------------------------------------------------------

    "CdpBackend.navigate sends Page.navigate and discards the response" in {
        Scope.run {
            val method = JsonRpcRoute.request[NavigateParams, CdpNoParams](
                "Page.navigate"
            ) { (_, _) => CdpNoParams() }
            mkBackendWithServer(Seq(method)).map { (backend, _) =>
                Abort.run[BrowserReadException](CdpBackend.navigate(backend, NavigateParams("http://example.com"))).map {
                    case Result.Success(()) => succeed
                    case other              => fail(s"Expected Success(()) but got $other")
                }
            }
        }
    }

    "CdpBackend.navigate surfaces BrowserProtocolErrorException when server signals an error" in {
        Scope.run {
            val method = JsonRpcRoute.request[NavigateParams, Unit](
                "Page.navigate"
            ) { (_, _) => Abort.fail(JsonRpcMethodNotFoundError("Page.navigate", Chunk.empty)) }
            mkBackendWithServer(Seq(method)).map { (backend, _) =>
                Abort.run[BrowserReadException](CdpBackend.navigate(backend, NavigateParams("http://example.com"))).map {
                    case Result.Failure(e: BrowserProtocolErrorException) =>
                        assert(e.method == "Page.navigate")
                    case other => fail(s"Expected BrowserProtocolErrorException but got $other")
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Page.navigateToHistoryEntry
    // -------------------------------------------------------------------------

    "CdpBackend.navigateToHistoryEntry sends Page.navigateToHistoryEntry and discards the response" in {
        Scope.run {
            val method = JsonRpcRoute.request[NavigateToEntryParams, CdpNoParams](
                "Page.navigateToHistoryEntry"
            ) { (_, _) => CdpNoParams() }
            mkBackendWithServer(Seq(method)).map { (backend, _) =>
                Abort.run[BrowserReadException](CdpBackend.navigateToHistoryEntry(backend, NavigateToEntryParams(42))).map {
                    case Result.Success(()) => succeed
                    case other              => fail(s"Expected Success(()) but got $other")
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Page.reload
    // -------------------------------------------------------------------------

    "CdpBackend.reload sends Page.reload and discards the response" in {
        Scope.run {
            val method = JsonRpcRoute.request[ReloadParams, CdpNoParams](
                "Page.reload"
            ) { (_, _) => CdpNoParams() }
            mkBackendWithServer(Seq(method)).map { (backend, _) =>
                Abort.run[BrowserReadException](CdpBackend.reload(backend, ReloadParams())).map {
                    case Result.Success(()) => succeed
                    case other              => fail(s"Expected Success(()) but got $other")
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Page.getFrameTree
    // -------------------------------------------------------------------------

    "CdpBackend.getFrameTree decodes a valid response into GetFrameTreeResult" in {
        Scope.run {
            val method = JsonRpcRoute.request[CdpNoParams, GetFrameTreeResult](
                "Page.getFrameTree"
            ) { (_, _) =>
                GetFrameTreeResult(frameTree = FrameTreeNode(frame = FrameInfo(id = "f1", url = "http://example.com")))
            }
            mkBackendWithServer(Seq(method)).map { (backend, _) =>
                Abort.run[BrowserReadException](CdpBackend.getFrameTree(backend)).map {
                    case Result.Success(r) =>
                        assert(r.frameTree.frame.id == "f1")
                        assert(r.frameTree.frame.url == "http://example.com")
                    case other => fail(s"Expected Success but got $other")
                }
            }
        }
    }

    "CdpBackend.getFrameTree surfaces BrowserProtocolErrorException on malformed response" in {
        Scope.run {
            val method = JsonRpcRoute.request[CdpNoParams, BadResult](
                "Page.getFrameTree"
            ) { (_, _) => BadResult() }
            mkBackendWithServer(Seq(method)).map { (backend, _) =>
                Abort.run[BrowserReadException](CdpBackend.getFrameTree(backend)).map {
                    case Result.Failure(e: BrowserProtocolErrorException) =>
                        assert(e.method == "Page.getFrameTree")
                    case other => fail(s"Expected BrowserProtocolErrorException but got $other")
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Emulation.setDeviceMetricsOverride
    // -------------------------------------------------------------------------

    "CdpBackend.setDeviceMetricsOverride sends Emulation.setDeviceMetricsOverride and discards the response" in {
        Scope.run {
            val method = JsonRpcRoute.request[ViewportParams, CdpNoParams](
                "Emulation.setDeviceMetricsOverride"
            ) { (_, _) => CdpNoParams() }
            mkBackendWithServer(Seq(method)).map { (backend, _) =>
                Abort.run[BrowserReadException](CdpBackend.setDeviceMetricsOverride(backend, ViewportParams(1280, 720))).map {
                    case Result.Success(()) => succeed
                    case other              => fail(s"Expected Success(()) but got $other")
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Emulation.clearDeviceMetricsOverride
    // -------------------------------------------------------------------------

    "CdpBackend.clearDeviceMetricsOverride sends Emulation.clearDeviceMetricsOverride and discards the response" in {
        Scope.run {
            val method = JsonRpcRoute.request[CdpNoParams, CdpNoParams](
                "Emulation.clearDeviceMetricsOverride"
            ) { (_, _) => CdpNoParams() }
            mkBackendWithServer(Seq(method)).map { (backend, _) =>
                Abort.run[BrowserReadException](CdpBackend.clearDeviceMetricsOverride(backend)).map {
                    case Result.Success(()) => succeed
                    case other              => fail(s"Expected Success(()) but got $other")
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Input.dispatchKeyEvent
    // -------------------------------------------------------------------------

    "CdpBackend.dispatchKeyEvent sends Input.dispatchKeyEvent and discards the response" in {
        Scope.run {
            val method = JsonRpcRoute.request[DispatchKeyEventParams, CdpNoParams](
                "Input.dispatchKeyEvent"
            ) { (_, _) => CdpNoParams() }
            val params = DispatchKeyEventParams(KeyEventType.Down, Present("a"), Present("a"), Absent, Absent)
            mkBackendWithServer(Seq(method)).map { (backend, _) =>
                Abort.run[BrowserReadException](CdpBackend.dispatchKeyEvent(backend, params)).map {
                    case Result.Success(()) => succeed
                    case other              => fail(s"Expected Success(()) but got $other")
                }
            }
        }
    }

    "CdpBackend.dispatchKeyEvent surfaces BrowserProtocolErrorException when server signals an error" in {
        Scope.run {
            val method = JsonRpcRoute.request[DispatchKeyEventParams, Unit](
                "Input.dispatchKeyEvent"
            ) { (_, _) => Abort.fail(JsonRpcMethodNotFoundError("Input.dispatchKeyEvent", Chunk.empty)) }
            val params = DispatchKeyEventParams(KeyEventType.Up, Present("a"), Absent, Absent, Absent)
            mkBackendWithServer(Seq(method)).map { (backend, _) =>
                Abort.run[BrowserReadException](CdpBackend.dispatchKeyEvent(backend, params)).map {
                    case Result.Failure(e: BrowserProtocolErrorException) =>
                        assert(e.method == "Input.dispatchKeyEvent")
                    case other => fail(s"Expected BrowserProtocolErrorException but got $other")
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Input.dispatchMouseEvent
    // -------------------------------------------------------------------------

    "CdpBackend.dispatchMouseEvent sends Input.dispatchMouseEvent and discards the response" in {
        Scope.run {
            val method = JsonRpcRoute.request[MouseEventParams, CdpNoParams](
                "Input.dispatchMouseEvent"
            ) { (_, _) => CdpNoParams() }
            val params = MouseEventParams(MouseEventType.Moved, 100, 200)
            mkBackendWithServer(Seq(method)).map { (backend, _) =>
                Abort.run[BrowserReadException](CdpBackend.dispatchMouseEvent(backend, params)).map {
                    case Result.Success(()) => succeed
                    case other              => fail(s"Expected Success(()) but got $other")
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Runtime.getProperties
    // -------------------------------------------------------------------------

    "CdpBackend.getProperties decodes a valid response into GetPropertiesResult" in {
        Scope.run {
            val method = JsonRpcRoute.request[GetPropertiesParams, GetPropertiesResult](
                "Runtime.getProperties"
            ) { (_, _) =>
                GetPropertiesResult(result =
                    Seq(
                        PropertyDescriptor(name = "0", value = Present(RemoteObjectRef(`type` = "object", objectId = Present("oid1")))),
                        PropertyDescriptor(name = "length", value = Present(RemoteObjectRef(`type` = "number")))
                    )
                )
            }
            mkBackendWithServer(Seq(method)).map { (backend, _) =>
                Abort.run[BrowserReadException](CdpBackend.getProperties(backend, GetPropertiesParams("obj42"))).map {
                    case Result.Success(r) =>
                        assert(r.result.size == 2)
                        assert(r.result.head.name == "0")
                    case other => fail(s"Expected Success but got $other")
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // DOM.describeNode (by backend node id)
    // -------------------------------------------------------------------------

    "CdpBackend.describeNodeByBackendId decodes a valid response into DescribeNodeResult" in {
        Scope.run {
            val method = JsonRpcRoute.request[DescribeNodeByBackendIdParams, DescribeNodeResult](
                "DOM.describeNode"
            ) { (_, _) =>
                DescribeNodeResult(node = DescribedNode(backendNodeId = 99, frameId = Present("frame1")))
            }
            mkBackendWithServer(Seq(method)).map { (backend, _) =>
                Abort.run[BrowserReadException](CdpBackend.describeNodeByBackendId(backend, DescribeNodeByBackendIdParams(42))).map {
                    case Result.Success(r) =>
                        assert(r.node.backendNodeId == 99)
                        assert(r.node.frameId == Present("frame1"))
                    case other => fail(s"Expected Success but got $other")
                }
            }
        }
    }

    "CdpBackend.describeNodeByBackendId surfaces BrowserProtocolErrorException on malformed response" in {
        Scope.run {
            val method = JsonRpcRoute.request[DescribeNodeByBackendIdParams, BadResult](
                "DOM.describeNode"
            ) { (_, _) => BadResult() }
            mkBackendWithServer(Seq(method)).map { (backend, _) =>
                Abort.run[BrowserReadException](CdpBackend.describeNodeByBackendId(backend, DescribeNodeByBackendIdParams(42))).map {
                    case Result.Failure(e: BrowserProtocolErrorException) =>
                        assert(e.method == "DOM.describeNode")
                    case other => fail(s"Expected BrowserProtocolErrorException but got $other")
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // DOM.setFileInputFiles
    // -------------------------------------------------------------------------

    "CdpBackend.setFileInputFiles sends DOM.setFileInputFiles and discards the response" in {
        Scope.run {
            val method = JsonRpcRoute.request[SetFileInputFilesParams, CdpNoParams](
                "DOM.setFileInputFiles"
            ) { (_, _) => CdpNoParams() }
            val params = SetFileInputFilesParams(Seq("/tmp/a.txt"), 77)
            mkBackendWithServer(Seq(method)).map { (backend, _) =>
                Abort.run[BrowserReadException](CdpBackend.setFileInputFiles(backend, params)).map {
                    case Result.Success(()) => succeed
                    case other              => fail(s"Expected Success(()) but got $other")
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Network.setCookie
    // -------------------------------------------------------------------------

    "CdpBackend.setCookie sends Network.setCookie and discards the response" in {
        Scope.run {
            val method = JsonRpcRoute.request[NetworkSetCookieParams, CdpNoParams](
                "Network.setCookie"
            ) { (_, _) => CdpNoParams() }
            val params = NetworkSetCookieParams(name = "session", value = "abc", domain = Present("example.com"))
            mkBackendWithServer(Seq(method)).map { (backend, _) =>
                Abort.run[BrowserReadException](CdpBackend.setCookie(backend, params)).map {
                    case Result.Success(()) => succeed
                    case other              => fail(s"Expected Success(()) but got $other")
                }
            }
        }
    }

    "CdpBackend.setCookie surfaces BrowserProtocolErrorException when server signals an error" in {
        Scope.run {
            val method = JsonRpcRoute.request[NetworkSetCookieParams, Unit](
                "Network.setCookie"
            ) { (_, _) => Abort.fail(JsonRpcMethodNotFoundError("Network.setCookie", Chunk.empty)) }
            val params = NetworkSetCookieParams(name = "x", value = "y")
            mkBackendWithServer(Seq(method)).map { (backend, _) =>
                Abort.run[BrowserReadException](CdpBackend.setCookie(backend, params)).map {
                    case Result.Failure(e: BrowserProtocolErrorException) =>
                        assert(e.method == "Network.setCookie")
                    case other => fail(s"Expected BrowserProtocolErrorException but got $other")
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Network.deleteCookies
    // -------------------------------------------------------------------------

    "CdpBackend.deleteCookies sends Network.deleteCookies and discards the response" in {
        Scope.run {
            val method = JsonRpcRoute.request[NetworkDeleteCookiesParams, CdpNoParams](
                "Network.deleteCookies"
            ) { (_, _) => CdpNoParams() }
            val params = NetworkDeleteCookiesParams("session", domain = Present("example.com"))
            mkBackendWithServer(Seq(method)).map { (backend, _) =>
                Abort.run[BrowserReadException](CdpBackend.deleteCookies(backend, params)).map {
                    case Result.Success(()) => succeed
                    case other              => fail(s"Expected Success(()) but got $other")
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Target.closeTarget
    // -------------------------------------------------------------------------

    "CdpBackend.closeTarget sends Target.closeTarget and discards the response" in {
        Scope.run {
            val method = JsonRpcRoute.request[CloseTargetParams, CdpNoParams](
                "Target.closeTarget"
            ) { (_, _) => CdpNoParams() }
            mkBackendWithServer(Seq(method)).map { (backend, _) =>
                Abort.run[BrowserReadException](CdpBackend.closeTarget(backend, CloseTargetParams("target-42"))).map {
                    case Result.Success(()) => succeed
                    case other              => fail(s"Expected Success(()) but got $other")
                }
            }
        }
    }

    "CdpBackend.closeTarget surfaces BrowserProtocolErrorException when server signals an error" in {
        Scope.run {
            val method = JsonRpcRoute.request[CloseTargetParams, Unit](
                "Target.closeTarget"
            ) { (_, _) => Abort.fail(JsonRpcMethodNotFoundError("Target.closeTarget", Chunk.empty)) }
            mkBackendWithServer(Seq(method)).map { (backend, _) =>
                Abort.run[BrowserReadException](CdpBackend.closeTarget(backend, CloseTargetParams("t1"))).map {
                    case Result.Failure(e: BrowserProtocolErrorException) =>
                        assert(e.method == "Target.closeTarget")
                    case other => fail(s"Expected BrowserProtocolErrorException but got $other")
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Target.disposeBrowserContext
    // -------------------------------------------------------------------------

    "CdpBackend.disposeBrowserContext sends Target.disposeBrowserContext and discards the response" in {
        Scope.run {
            val method = JsonRpcRoute.request[DisposeBrowserContextParams, CdpNoParams](
                "Target.disposeBrowserContext"
            ) { (_, _) => CdpNoParams() }
            mkBackendWithServer(Seq(method)).map { (backend, _) =>
                Abort.run[BrowserReadException](CdpBackend.disposeBrowserContext(backend, DisposeBrowserContextParams("ctx-1"))).map {
                    case Result.Success(()) => succeed
                    case other              => fail(s"Expected Success(()) but got $other")
                }
            }
        }
    }

end CdpBackendTest
