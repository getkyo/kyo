package kyo.internal

import kyo.*
import kyo.internal.CdpTypes.*

/** A fake [[CdpSender]] for unit-testing [[CdpBackend]] without a live browser.
  *
  * Implements the [[CdpSender]] trait directly so no null values or casts are needed.
  *
  * Responses are keyed by CDP method name. When a method has no pre-configured response the fake aborts with
  * [[BrowserProtocolErrorException]].
  */
private class FakeCdpSender(responses: Map[String, String]) extends CdpSender:

    def send(method: String)(using Frame): String < (Async & Abort[BrowserReadException]) =
        responses.get(method) match
            case Some(json) => json
            case None       => Abort.fail(BrowserProtocolErrorException(method, "no fake response configured"))

    def send[P: Schema](method: String, params: P)(using Frame): String < (Async & Abort[BrowserReadException]) =
        responses.get(method) match
            case Some(json) => json
            case None       => Abort.fail(BrowserProtocolErrorException(method, "no fake response configured"))

end FakeCdpSender

class CdpBackendTest extends BaseBrowserTest:

    // The CdpClient dispatcher hands awaiting callers the WHOLE wire frame, not the extracted `result`
    // substring. Tests express the *inner* typed payload (decodes to the per-call response case class);
    // this helper wraps it in the CdpReply envelope shape the live dispatcher would emit.
    private def replyOk(inner: String): String = s"""{"id":1,"result":$inner}"""

    /** Builds a fake sender whose responses are inner typed payloads automatically wrapped in a CdpReply envelope. */
    private def fakeSender(responses: (String, String)*): FakeCdpSender =
        new FakeCdpSender(responses.iterator.map { case (m, inner) => m -> replyOk(inner) }.toMap)

    // -------------------------------------------------------------------------
    // Page.getNavigationHistory
    // -------------------------------------------------------------------------

    "CdpBackend.getNavigationHistory decodes a valid response into NavigationHistory" in {
        val json =
            """{"currentIndex":1,"entries":[{"id":1,"url":"http://a.com","title":"A"},{"id":2,"url":"http://b.com","title":"B"}]}"""
        val sender = fakeSender("Page.getNavigationHistory" -> json)
        Abort.run[BrowserReadException](CdpBackend.getNavigationHistory(sender)).map {
            case Result.Success(hist) =>
                assert(hist.currentIndex == 1)
                assert(hist.entries.size == 2)
                assert(hist.entries(0).url == "http://a.com")
                assert(hist.entries(1).url == "http://b.com")
            case other => fail(s"Expected Success but got $other")
        }
    }

    "CdpBackend.getNavigationHistory surfaces decodeFailure on malformed response" in {
        val sender = fakeSender("Page.getNavigationHistory" -> """{"garbage":true}""")
        Abort.run[BrowserReadException](CdpBackend.getNavigationHistory(sender)).map {
            case Result.Failure(e: BrowserProtocolErrorException) =>
                assert(e.method == "Page.getNavigationHistory")
                assert(e.error.contains("decode failed"))
            case other => fail(s"Expected BrowserProtocolErrorException but got $other")
        }
    }

    // -------------------------------------------------------------------------
    // Page.captureScreenshot
    // -------------------------------------------------------------------------

    "CdpBackend.captureScreenshot decodes a valid response into ScreenshotResult" in {
        val json   = """{"data":"aGVsbG8="}"""
        val sender = fakeSender("Page.captureScreenshot" -> json)
        Abort.run[BrowserReadException](CdpBackend.captureScreenshot(sender, ScreenshotParams(Browser.ScreenshotFormat.Png))).map {
            case Result.Success(sr) =>
                assert(sr.data == "aGVsbG8=")
            case other => fail(s"Expected Success but got $other")
        }
    }

    "CdpBackend.captureScreenshot surfaces decodeFailure on malformed response" in {
        val sender = fakeSender("Page.captureScreenshot" -> """{"unexpected":1}""")
        Abort.run[BrowserReadException](CdpBackend.captureScreenshot(sender, ScreenshotParams(Browser.ScreenshotFormat.Png))).map {
            case Result.Failure(e: BrowserProtocolErrorException) =>
                assert(e.method == "Page.captureScreenshot")
                assert(e.error.contains("decode failed"))
            case other => fail(s"Expected BrowserProtocolErrorException but got $other")
        }
    }

    // -------------------------------------------------------------------------
    // Page.printToPDF
    // -------------------------------------------------------------------------

    "CdpBackend.printToPDF decodes a valid response into PrintToPdfResult" in {
        val json   = """{"data":"cGRmZGF0YQ=="}"""
        val sender = fakeSender("Page.printToPDF" -> json)
        Abort.run[BrowserReadException](CdpBackend.printToPDF(sender)).map {
            case Result.Success(pr) =>
                assert(pr.data == "cGRmZGF0YQ==")
            case other => fail(s"Expected Success but got $other")
        }
    }

    "CdpBackend.printToPDF surfaces decodeFailure on malformed response" in {
        val sender = fakeSender("Page.printToPDF" -> """{"wrong":"field"}""")
        Abort.run[BrowserReadException](CdpBackend.printToPDF(sender)).map {
            case Result.Failure(e: BrowserProtocolErrorException) =>
                assert(e.method == "Page.printToPDF")
                assert(e.error.contains("decode failed"))
            case other => fail(s"Expected BrowserProtocolErrorException but got $other")
        }
    }

    // -------------------------------------------------------------------------
    // Runtime.evaluate (runtimeEvaluate)
    // -------------------------------------------------------------------------

    "CdpBackend.runtimeEvaluate returns the raw wire response string" in {
        // The dispatcher hands awaiting callers the whole wire frame, so this returns the CdpReply-wrapped
        // envelope verbatim (caller decodes the inner EvalResult via Json.decode[CdpReply[EvalResult]]).
        val inner  = """{"result":{"type":"string","value":"hello"}}"""
        val sender = fakeSender("Runtime.evaluate" -> inner)
        Abort.run[BrowserReadException](CdpBackend.runtimeEvaluate(sender, EvalParams("'hello'"))).map {
            case Result.Success(result) =>
                assert(result == replyOk(inner))
            case other => fail(s"Expected Success but got $other")
        }
    }

    "CdpBackend.runtimeEvaluate surfaces BrowserProtocolErrorException when method has no configured response" in {
        val sender = fakeSender() // no responses configured
        Abort.run[BrowserReadException](CdpBackend.runtimeEvaluate(sender, EvalParams("1+1"))).map {
            case Result.Failure(e: BrowserProtocolErrorException) =>
                assert(e.method == "Runtime.evaluate")
            case other => fail(s"Expected BrowserProtocolErrorException but got $other")
        }
    }

    // -------------------------------------------------------------------------
    // Network.getCookies
    // -------------------------------------------------------------------------

    "CdpBackend.getCookies decodes a valid response into NetworkGetCookiesResult" in {
        val json   = """{"cookies":[{"name":"session","value":"abc","domain":"example.com","path":"/"}]}"""
        val sender = fakeSender("Network.getCookies" -> json)
        Abort.run[BrowserReadException](CdpBackend.getCookies(sender)).map {
            case Result.Success(r) =>
                assert(r.cookies.size == 1)
                assert(r.cookies.head.name == "session")
                assert(r.cookies.head.value == "abc")
            case other => fail(s"Expected Success but got $other")
        }
    }

    "CdpBackend.getCookies surfaces decodeFailure on malformed response" in {
        val sender = fakeSender("Network.getCookies" -> """{"bad":true}""")
        Abort.run[BrowserReadException](CdpBackend.getCookies(sender)).map {
            case Result.Failure(e: BrowserProtocolErrorException) =>
                assert(e.method == "Network.getCookies")
                assert(e.error.contains("decode failed"))
            case other => fail(s"Expected BrowserProtocolErrorException but got $other")
        }
    }

    // -------------------------------------------------------------------------
    // Target.getTargets
    // -------------------------------------------------------------------------

    "CdpBackend.getTargets decodes a valid response into GetTargetsResult" in {
        val json =
            """{"targetInfos":[{"targetId":"t1","type":"page","url":"http://example.com"}]}"""
        val sender = fakeSender("Target.getTargets" -> json)
        Abort.run[BrowserReadException](CdpBackend.getTargets(sender)).map {
            case Result.Success(r) =>
                assert(r.targetInfos.size == 1)
                assert(r.targetInfos.head.targetId == "t1")
                assert(r.targetInfos.head.`type` == "page")
            case other => fail(s"Expected Success but got $other")
        }
    }

    "CdpBackend.getTargets surfaces BrowserProtocolErrorException on malformed response" in {
        val sender = fakeSender("Target.getTargets" -> """{"oops":true}""")
        Abort.run[BrowserReadException](CdpBackend.getTargets(sender)).map {
            case Result.Failure(e: BrowserProtocolErrorException) =>
                assert(e.method == "Target.getTargets")
                assert(e.error.startsWith("decode failed:"))
            case other => fail(s"Expected BrowserProtocolErrorException but got $other")
        }
    }

    // -------------------------------------------------------------------------
    // Target.attachToTarget
    // -------------------------------------------------------------------------

    "CdpBackend.attachToTarget decodes a valid response into AttachResult" in {
        val json   = """{"sessionId":"s42"}"""
        val sender = fakeSender("Target.attachToTarget" -> json)
        Abort.run[BrowserReadException](CdpBackend.attachToTarget(sender, AttachParams("t1", flatten = true))).map {
            case Result.Success(r) =>
                assert(r.sessionId == "s42")
            case other => fail(s"Expected Success but got $other")
        }
    }

    "CdpBackend.attachToTarget surfaces BrowserProtocolErrorException on malformed response" in {
        val sender = fakeSender("Target.attachToTarget" -> """{"bad":true}""")
        Abort.run[BrowserReadException](CdpBackend.attachToTarget(sender, AttachParams("t1", flatten = true))).map {
            case Result.Failure(e: BrowserProtocolErrorException) =>
                assert(e.method == "Target.attachToTarget")
                assert(e.error.startsWith("decode failed:"))
            case other => fail(s"Expected BrowserProtocolErrorException but got $other")
        }
    }

    // -------------------------------------------------------------------------
    // Target.createTarget
    // -------------------------------------------------------------------------

    "CdpBackend.createTarget decodes a valid response into CreateTargetResult" in {
        val json   = """{"targetId":"new-tab-id"}"""
        val sender = fakeSender("Target.createTarget" -> json)
        Abort.run[BrowserReadException](CdpBackend.createTarget(sender, CreateTargetParams("about:blank"))).map {
            case Result.Success(r) =>
                assert(r.targetId == "new-tab-id")
            case other => fail(s"Expected Success but got $other")
        }
    }

    "CdpBackend.createTarget surfaces BrowserProtocolErrorException on malformed response" in {
        val sender = fakeSender("Target.createTarget" -> """{"wrong":true}""")
        Abort.run[BrowserReadException](CdpBackend.createTarget(sender, CreateTargetParams("about:blank"))).map {
            case Result.Failure(e: BrowserProtocolErrorException) =>
                assert(e.method == "Target.createTarget")
                assert(e.error.startsWith("decode failed:"))
            case other => fail(s"Expected BrowserProtocolErrorException but got $other")
        }
    }

    // -------------------------------------------------------------------------
    // Target.createBrowserContext
    // -------------------------------------------------------------------------

    "CdpBackend.createBrowserContext decodes a valid response into CreateBrowserContextResult" in {
        val json   = """{"browserContextId":"ctx-123"}"""
        val sender = fakeSender("Target.createBrowserContext" -> json)
        Abort.run[BrowserReadException](CdpBackend.createBrowserContext(sender)).map {
            case Result.Success(r) =>
                assert(r.browserContextId == "ctx-123")
            case other => fail(s"Expected Success but got $other")
        }
    }

    "CdpBackend.createBrowserContext surfaces BrowserProtocolErrorException on malformed response" in {
        val sender = fakeSender("Target.createBrowserContext" -> """{"nope":true}""")
        Abort.run[BrowserReadException](CdpBackend.createBrowserContext(sender)).map {
            case Result.Failure(e: BrowserProtocolErrorException) =>
                assert(e.method == "Target.createBrowserContext")
                assert(e.error.startsWith("decode failed:"))
            case other => fail(s"Expected BrowserProtocolErrorException but got $other")
        }
    }

    // -------------------------------------------------------------------------
    // Permissive shape: extra fields are ignored by each typed decoder.
    // -------------------------------------------------------------------------
    // Each assertion: decoder accepts a JSON object with all required fields PLUS at least
    // one extra unknown field. The extra field must be silently ignored (decode succeeds).
    // Use `-z 'permissive'` to filter to these tests only.

    "CdpBackend typed decoders are permissive: extra fields are silently ignored" in {
        // NavigationHistory
        val navJson =
            """{"currentIndex":0,"entries":[{"id":1,"url":"http://x.com","title":"X"}],"extra":"ignored"}"""
        val navSender = fakeSender("Page.getNavigationHistory" -> navJson)
        Abort.run[BrowserReadException](CdpBackend.getNavigationHistory(navSender)).map {
            case Result.Success(hist) => assert(hist.currentIndex == 0)
            case other                => fail(s"NavigationHistory permissive failed: $other")
        }.andThen {
            // ScreenshotResult
            val ssJson   = """{"data":"aGVsbG8=","extra":"ignored"}"""
            val ssSender = fakeSender("Page.captureScreenshot" -> ssJson)
            Abort.run[BrowserReadException](CdpBackend.captureScreenshot(ssSender, ScreenshotParams(Browser.ScreenshotFormat.Png))).map {
                case Result.Success(sr) => assert(sr.data == "aGVsbG8=")
                case other              => fail(s"ScreenshotResult permissive failed: $other")
            }.andThen {
                // PrintToPdfResult
                val pdfJson   = """{"data":"cGRm","extra":"ignored"}"""
                val pdfSender = fakeSender("Page.printToPDF" -> pdfJson)
                Abort.run[BrowserReadException](CdpBackend.printToPDF(pdfSender)).map {
                    case Result.Success(pr) => assert(pr.data == "cGRm")
                    case other              => fail(s"PrintToPdfResult permissive failed: $other")
                }.andThen {
                    // NetworkGetCookiesResult
                    val cookieJson =
                        """{"cookies":[{"name":"c","value":"v","domain":"d.com","path":"/"}],"extra":"ignored"}"""
                    val cookieSender = fakeSender("Network.getCookies" -> cookieJson)
                    Abort.run[BrowserReadException](CdpBackend.getCookies(cookieSender)).map {
                        case Result.Success(r) => assert(r.cookies.size == 1)
                        case other             => fail(s"NetworkGetCookiesResult permissive failed: $other")
                    }.andThen {
                        // GetTargetsResult
                        val targetsJson   = """{"targetInfos":[{"targetId":"t1","type":"page","url":"u"}],"extra":"ignored"}"""
                        val targetsSender = fakeSender("Target.getTargets" -> targetsJson)
                        Abort.run[BrowserReadException](CdpBackend.getTargets(targetsSender)).map {
                            case Result.Success(r) => assert(r.targetInfos.size == 1)
                            case other             => fail(s"GetTargetsResult permissive failed: $other")
                        }.andThen {
                            // AttachResult
                            val attachJson   = """{"sessionId":"s99","extra":"ignored"}"""
                            val attachSender = fakeSender("Target.attachToTarget" -> attachJson)
                            Abort.run[BrowserReadException](
                                CdpBackend.attachToTarget(attachSender, AttachParams("t1", flatten = true))
                            ).map {
                                case Result.Success(r) => assert(r.sessionId == "s99")
                                case other             => fail(s"AttachResult permissive failed: $other")
                            }.andThen {
                                // CreateTargetResult
                                val ctJson   = """{"targetId":"tid","extra":"ignored"}"""
                                val ctSender = fakeSender("Target.createTarget" -> ctJson)
                                Abort.run[BrowserReadException](
                                    CdpBackend.createTarget(ctSender, CreateTargetParams("about:blank"))
                                ).map {
                                    case Result.Success(r) => assert(r.targetId == "tid")
                                    case other             => fail(s"CreateTargetResult permissive failed: $other")
                                }.andThen {
                                    // CreateBrowserContextResult
                                    val cbcJson   = """{"browserContextId":"ctx-x","extra":"ignored"}"""
                                    val cbcSender = fakeSender("Target.createBrowserContext" -> cbcJson)
                                    Abort.run[BrowserReadException](CdpBackend.createBrowserContext(cbcSender)).map {
                                        case Result.Success(r) => assert(r.browserContextId == "ctx-x")
                                        case other             => fail(s"CreateBrowserContextResult permissive failed: $other")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Missing-required-field: each typed decoder fails with BrowserProtocolErrorException.
    // -------------------------------------------------------------------------

    "CdpBackend typed decoders reject missing-required-field shapes" in {
        // NavigationHistory; missing 'entries'
        val navSender = fakeSender("Page.getNavigationHistory" -> """{"currentIndex":0}""")
        Abort.run[BrowserReadException](CdpBackend.getNavigationHistory(navSender)).map {
            case Result.Failure(e: BrowserProtocolErrorException) =>
                assert(e.method == "Page.getNavigationHistory")
            case other => fail(s"NavigationHistory missing-field test failed: $other")
        }.andThen {
            // ScreenshotResult; missing 'data'
            val ssSender = fakeSender("Page.captureScreenshot" -> """{"format":"png"}""")
            Abort.run[BrowserReadException](CdpBackend.captureScreenshot(ssSender, ScreenshotParams(Browser.ScreenshotFormat.Png))).map {
                case Result.Failure(e: BrowserProtocolErrorException) =>
                    assert(e.method == "Page.captureScreenshot")
                case other => fail(s"ScreenshotResult missing-field test failed: $other")
            }.andThen {
                // PrintToPdfResult; missing 'data'
                val pdfSender = fakeSender("Page.printToPDF" -> """{"stream":"s"}""")
                Abort.run[BrowserReadException](CdpBackend.printToPDF(pdfSender)).map {
                    case Result.Failure(e: BrowserProtocolErrorException) =>
                        assert(e.method == "Page.printToPDF")
                    case other => fail(s"PrintToPdfResult missing-field test failed: $other")
                }.andThen {
                    // NetworkGetCookiesResult; missing 'cookies'
                    val cookieSender = fakeSender("Network.getCookies" -> """{"total":0}""")
                    Abort.run[BrowserReadException](CdpBackend.getCookies(cookieSender)).map {
                        case Result.Failure(e: BrowserProtocolErrorException) =>
                            assert(e.method == "Network.getCookies")
                        case other => fail(s"NetworkGetCookiesResult missing-field test failed: $other")
                    }.andThen {
                        // GetTargetsResult; missing 'targetInfos'
                        val targetsSender = fakeSender("Target.getTargets" -> """{"count":0}""")
                        Abort.run[BrowserReadException](CdpBackend.getTargets(targetsSender)).map {
                            case Result.Failure(e: BrowserProtocolErrorException) =>
                                assert(e.method == "Target.getTargets")
                            case other => fail(s"GetTargetsResult missing-field test failed: $other")
                        }.andThen {
                            // AttachResult; missing 'sessionId'
                            val attachSender = fakeSender("Target.attachToTarget" -> """{"status":"ok"}""")
                            Abort.run[BrowserReadException](
                                CdpBackend.attachToTarget(attachSender, AttachParams("t1", flatten = true))
                            ).map {
                                case Result.Failure(e: BrowserProtocolErrorException) =>
                                    assert(e.method == "Target.attachToTarget")
                                case other => fail(s"AttachResult missing-field test failed: $other")
                            }.andThen {
                                // CreateTargetResult; missing 'targetId'
                                val ctSender = fakeSender("Target.createTarget" -> """{"url":"u"}""")
                                Abort.run[BrowserReadException](
                                    CdpBackend.createTarget(ctSender, CreateTargetParams("about:blank"))
                                ).map {
                                    case Result.Failure(e: BrowserProtocolErrorException) =>
                                        assert(e.method == "Target.createTarget")
                                    case other => fail(s"CreateTargetResult missing-field test failed: $other")
                                }.andThen {
                                    // CreateBrowserContextResult; missing 'browserContextId'
                                    val cbcSender = fakeSender("Target.createBrowserContext" -> """{"type":"ctx"}""")
                                    Abort.run[BrowserReadException](CdpBackend.createBrowserContext(cbcSender)).map {
                                        case Result.Failure(e: BrowserProtocolErrorException) =>
                                            assert(e.method == "Target.createBrowserContext")
                                        case other => fail(s"CreateBrowserContextResult missing-field test failed: $other")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Page.navigate
    // -------------------------------------------------------------------------

    "CdpBackend.navigate sends Page.navigate and discards the response" in {
        val sender = fakeSender("Page.navigate" -> "{}")
        Abort.run[BrowserReadException](CdpBackend.navigate(sender, NavigateParams("http://example.com"))).map {
            case Result.Success(()) =>
                // The fake sender aborts on any unconfigured method, so reaching Success proves the expected CDP method was sent.
                succeed(
                    "CdpBackend.navigate sends Page.navigate and discards the response sends its CDP method and discards the unit response; a wrong method would abort via the fake sender"
                )
            case other => fail(s"Expected Success(()) but got $other")
        }
    }

    "CdpBackend.navigate surfaces BrowserProtocolErrorException when no response configured" in {
        val sender = fakeSender()
        Abort.run[BrowserReadException](CdpBackend.navigate(sender, NavigateParams("http://example.com"))).map {
            case Result.Failure(e: BrowserProtocolErrorException) =>
                assert(e.method == "Page.navigate")
            case other => fail(s"Expected BrowserProtocolErrorException but got $other")
        }
    }

    // -------------------------------------------------------------------------
    // Page.navigateToHistoryEntry
    // -------------------------------------------------------------------------

    "CdpBackend.navigateToHistoryEntry sends Page.navigateToHistoryEntry and discards the response" in {
        val sender = fakeSender("Page.navigateToHistoryEntry" -> "{}")
        Abort.run[BrowserReadException](CdpBackend.navigateToHistoryEntry(sender, NavigateToEntryParams(42))).map {
            case Result.Success(()) =>
                // The fake sender aborts on any unconfigured method, so reaching Success proves the expected CDP method was sent.
                succeed(
                    "CdpBackend.navigateToHistoryEntry sends Page.navigateToHistoryEntry and discards the response sends its CDP method and discards the unit response; a wrong method would abort via the fake sender"
                )
            case other => fail(s"Expected Success(()) but got $other")
        }
    }

    // -------------------------------------------------------------------------
    // Page.reload
    // -------------------------------------------------------------------------

    "CdpBackend.reload sends Page.reload and discards the response" in {
        val sender = fakeSender("Page.reload" -> "{}")
        Abort.run[BrowserReadException](CdpBackend.reload(sender, ReloadParams())).map {
            case Result.Success(()) =>
                // The fake sender aborts on any unconfigured method, so reaching Success proves the expected CDP method was sent.
                succeed(
                    "CdpBackend.reload sends Page.reload and discards the response sends its CDP method and discards the unit response; a wrong method would abort via the fake sender"
                )
            case other => fail(s"Expected Success(()) but got $other")
        }
    }

    // -------------------------------------------------------------------------
    // Page.getFrameTree
    // -------------------------------------------------------------------------

    "CdpBackend.getFrameTree decodes a valid response into GetFrameTreeResult" in {
        val json   = """{"frameTree":{"frame":{"id":"f1","url":"http://example.com"}}}"""
        val sender = fakeSender("Page.getFrameTree" -> json)
        Abort.run[BrowserReadException](CdpBackend.getFrameTree(sender)).map {
            case Result.Success(r) =>
                assert(r.frameTree.frame.id == "f1")
                assert(r.frameTree.frame.url == "http://example.com")
            case other => fail(s"Expected Success but got $other")
        }
    }

    "CdpBackend.getFrameTree surfaces decodeFailure on malformed response" in {
        val sender = fakeSender("Page.getFrameTree" -> """{"bad":true}""")
        Abort.run[BrowserReadException](CdpBackend.getFrameTree(sender)).map {
            case Result.Failure(e: BrowserProtocolErrorException) =>
                assert(e.method == "Page.getFrameTree")
                assert(e.error.contains("decode failed"))
            case other => fail(s"Expected BrowserProtocolErrorException but got $other")
        }
    }

    // -------------------------------------------------------------------------
    // Emulation.setDeviceMetricsOverride
    // -------------------------------------------------------------------------

    "CdpBackend.setDeviceMetricsOverride sends Emulation.setDeviceMetricsOverride and discards the response" in {
        val sender = fakeSender("Emulation.setDeviceMetricsOverride" -> "{}")
        Abort.run[BrowserReadException](CdpBackend.setDeviceMetricsOverride(sender, ViewportParams(1280, 720))).map {
            case Result.Success(()) =>
                // The fake sender aborts on any unconfigured method, so reaching Success proves the expected CDP method was sent.
                succeed(
                    "CdpBackend.setDeviceMetricsOverride sends Emulation.setDeviceMetricsOverride and discards the response sends its CDP method and discards the unit response; a wrong method would abort via the fake sender"
                )
            case other => fail(s"Expected Success(()) but got $other")
        }
    }

    // -------------------------------------------------------------------------
    // Emulation.clearDeviceMetricsOverride
    // -------------------------------------------------------------------------

    "CdpBackend.clearDeviceMetricsOverride sends Emulation.clearDeviceMetricsOverride and discards the response" in {
        val sender = fakeSender("Emulation.clearDeviceMetricsOverride" -> "{}")
        Abort.run[BrowserReadException](CdpBackend.clearDeviceMetricsOverride(sender)).map {
            case Result.Success(()) =>
                // The fake sender aborts on any unconfigured method, so reaching Success proves the expected CDP method was sent.
                succeed(
                    "CdpBackend.clearDeviceMetricsOverride sends Emulation.clearDeviceMetricsOverride and discards the response sends its CDP method and discards the unit response; a wrong method would abort via the fake sender"
                )
            case other => fail(s"Expected Success(()) but got $other")
        }
    }

    // -------------------------------------------------------------------------
    // Input.dispatchKeyEvent
    // -------------------------------------------------------------------------

    "CdpBackend.dispatchKeyEvent sends Input.dispatchKeyEvent and discards the response" in {
        val sender = fakeSender("Input.dispatchKeyEvent" -> "{}")
        val params = DispatchKeyEventParams(KeyEventType.Down, Present("a"), Present("a"), Absent, Absent)
        Abort.run[BrowserReadException](CdpBackend.dispatchKeyEvent(sender, params)).map {
            case Result.Success(()) =>
                // The fake sender aborts on any unconfigured method, so reaching Success proves the expected CDP method was sent.
                succeed(
                    "CdpBackend.dispatchKeyEvent sends Input.dispatchKeyEvent and discards the response sends its CDP method and discards the unit response; a wrong method would abort via the fake sender"
                )
            case other => fail(s"Expected Success(()) but got $other")
        }
    }

    "CdpBackend.dispatchKeyEvent surfaces BrowserProtocolErrorException when no response configured" in {
        val sender = fakeSender()
        val params = DispatchKeyEventParams(KeyEventType.Up, Present("a"), Absent, Absent, Absent)
        Abort.run[BrowserReadException](CdpBackend.dispatchKeyEvent(sender, params)).map {
            case Result.Failure(e: BrowserProtocolErrorException) =>
                assert(e.method == "Input.dispatchKeyEvent")
            case other => fail(s"Expected BrowserProtocolErrorException but got $other")
        }
    }

    // -------------------------------------------------------------------------
    // Input.dispatchMouseEvent
    // -------------------------------------------------------------------------

    "CdpBackend.dispatchMouseEvent sends Input.dispatchMouseEvent and discards the response" in {
        val sender = fakeSender("Input.dispatchMouseEvent" -> "{}")
        val params = MouseEventParams(MouseEventType.Moved, 100, 200)
        Abort.run[BrowserReadException](CdpBackend.dispatchMouseEvent(sender, params)).map {
            case Result.Success(()) =>
                // The fake sender aborts on any unconfigured method, so reaching Success proves the expected CDP method was sent.
                succeed(
                    "CdpBackend.dispatchMouseEvent sends Input.dispatchMouseEvent and discards the response sends its CDP method and discards the unit response; a wrong method would abort via the fake sender"
                )
            case other => fail(s"Expected Success(()) but got $other")
        }
    }

    // -------------------------------------------------------------------------
    // Runtime.getProperties
    // -------------------------------------------------------------------------

    "CdpBackend.getProperties decodes a valid response into GetPropertiesResult" in {
        val json   = """{"result":[{"name":"0","value":{"type":"object","objectId":"oid1"}},{"name":"length","value":{"type":"number"}}]}"""
        val sender = fakeSender("Runtime.getProperties" -> json)
        Abort.run[BrowserReadException](CdpBackend.getProperties(sender, GetPropertiesParams("obj42"))).map {
            case Result.Success(r) =>
                assert(r.result.size == 2)
                assert(r.result.head.name == "0")
            case other => fail(s"Expected Success but got $other")
        }
    }

    // -------------------------------------------------------------------------
    // DOM.describeNode (by backend node id)
    // -------------------------------------------------------------------------

    "CdpBackend.describeNodeByBackendId decodes a valid response into DescribeNodeResult" in {
        val json   = """{"node":{"backendNodeId":99,"frameId":"frame1"}}"""
        val sender = fakeSender("DOM.describeNode" -> json)
        Abort.run[BrowserReadException](CdpBackend.describeNodeByBackendId(sender, DescribeNodeByBackendIdParams(42))).map {
            case Result.Success(r) =>
                assert(r.node.backendNodeId == 99)
                assert(r.node.frameId == Present("frame1"))
            case other => fail(s"Expected Success but got $other")
        }
    }

    "CdpBackend.describeNodeByBackendId surfaces decodeFailure on malformed response" in {
        val sender = fakeSender("DOM.describeNode" -> """{"wrong":true}""")
        Abort.run[BrowserReadException](CdpBackend.describeNodeByBackendId(sender, DescribeNodeByBackendIdParams(42))).map {
            case Result.Failure(e: BrowserProtocolErrorException) =>
                assert(e.method == "DOM.describeNode")
                assert(e.error.contains("decode failed"))
            case other => fail(s"Expected BrowserProtocolErrorException but got $other")
        }
    }

    // -------------------------------------------------------------------------
    // DOM.setFileInputFiles
    // -------------------------------------------------------------------------

    "CdpBackend.setFileInputFiles sends DOM.setFileInputFiles and discards the response" in {
        val sender = fakeSender("DOM.setFileInputFiles" -> "{}")
        val params = SetFileInputFilesParams(Seq("/tmp/a.txt"), 77)
        Abort.run[BrowserReadException](CdpBackend.setFileInputFiles(sender, params)).map {
            case Result.Success(()) =>
                // The fake sender aborts on any unconfigured method, so reaching Success proves the expected CDP method was sent.
                succeed(
                    "CdpBackend.setFileInputFiles sends DOM.setFileInputFiles and discards the response sends its CDP method and discards the unit response; a wrong method would abort via the fake sender"
                )
            case other => fail(s"Expected Success(()) but got $other")
        }
    }

    // -------------------------------------------------------------------------
    // Network.setCookie
    // -------------------------------------------------------------------------

    "CdpBackend.setCookie sends Network.setCookie and discards the response" in {
        val sender = fakeSender("Network.setCookie" -> "{}")
        val params = NetworkSetCookieParams(name = "session", value = "abc", domain = Present("example.com"))
        Abort.run[BrowserReadException](CdpBackend.setCookie(sender, params)).map {
            case Result.Success(()) =>
                // The fake sender aborts on any unconfigured method, so reaching Success proves the expected CDP method was sent.
                succeed(
                    "CdpBackend.setCookie sends Network.setCookie and discards the response sends its CDP method and discards the unit response; a wrong method would abort via the fake sender"
                )
            case other => fail(s"Expected Success(()) but got $other")
        }
    }

    "CdpBackend.setCookie surfaces BrowserProtocolErrorException when no response configured" in {
        val sender = fakeSender()
        val params = NetworkSetCookieParams(name = "x", value = "y")
        Abort.run[BrowserReadException](CdpBackend.setCookie(sender, params)).map {
            case Result.Failure(e: BrowserProtocolErrorException) =>
                assert(e.method == "Network.setCookie")
            case other => fail(s"Expected BrowserProtocolErrorException but got $other")
        }
    }

    // -------------------------------------------------------------------------
    // Network.deleteCookies
    // -------------------------------------------------------------------------

    "CdpBackend.deleteCookies sends Network.deleteCookies and discards the response" in {
        val sender = fakeSender("Network.deleteCookies" -> "{}")
        val params = NetworkDeleteCookiesParams("session", domain = Present("example.com"))
        Abort.run[BrowserReadException](CdpBackend.deleteCookies(sender, params)).map {
            case Result.Success(()) =>
                // The fake sender aborts on any unconfigured method, so reaching Success proves the expected CDP method was sent.
                succeed(
                    "CdpBackend.deleteCookies sends Network.deleteCookies and discards the response sends its CDP method and discards the unit response; a wrong method would abort via the fake sender"
                )
            case other => fail(s"Expected Success(()) but got $other")
        }
    }

    // -------------------------------------------------------------------------
    // Target.closeTarget
    // -------------------------------------------------------------------------

    "CdpBackend.closeTarget sends Target.closeTarget and discards the response" in {
        val sender = fakeSender("Target.closeTarget" -> "{}")
        Abort.run[BrowserReadException](CdpBackend.closeTarget(sender, CloseTargetParams("target-42"))).map {
            case Result.Success(()) =>
                // The fake sender aborts on any unconfigured method, so reaching Success proves the expected CDP method was sent.
                succeed(
                    "CdpBackend.closeTarget sends Target.closeTarget and discards the response sends its CDP method and discards the unit response; a wrong method would abort via the fake sender"
                )
            case other => fail(s"Expected Success(()) but got $other")
        }
    }

    "CdpBackend.closeTarget surfaces BrowserProtocolErrorException when no response configured" in {
        val sender = fakeSender()
        Abort.run[BrowserReadException](CdpBackend.closeTarget(sender, CloseTargetParams("t1"))).map {
            case Result.Failure(e: BrowserProtocolErrorException) =>
                assert(e.method == "Target.closeTarget")
            case other => fail(s"Expected BrowserProtocolErrorException but got $other")
        }
    }

    // -------------------------------------------------------------------------
    // Target.disposeBrowserContext
    // -------------------------------------------------------------------------

    "CdpBackend.disposeBrowserContext sends Target.disposeBrowserContext and discards the response" in {
        val sender = fakeSender("Target.disposeBrowserContext" -> "{}")
        Abort.run[BrowserReadException](CdpBackend.disposeBrowserContext(sender, DisposeBrowserContextParams("ctx-1"))).map {
            case Result.Success(()) =>
                // The fake sender aborts on any unconfigured method, so reaching Success proves the expected CDP method was sent.
                succeed(
                    "CdpBackend.disposeBrowserContext sends Target.disposeBrowserContext and discards the response sends its CDP method and discards the unit response; a wrong method would abort via the fake sender"
                )
            case other => fail(s"Expected Success(()) but got $other")
        }
    }

end CdpBackendTest
