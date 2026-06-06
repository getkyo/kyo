package kyo.internal

import kyo.*
import kyo.BrowserIFrameInvalidException.Reason

/** Direct tests for the three typed failure modes of [[IFrameResolver.resolveIFrameHandle]]:
  *
  *   - selector miss -> [[BrowserElementNotFoundException]]
  *   - matched non-frame element -> [[BrowserIFrameInvalidException]] (`reason = NotAFrame`)
  *   - matched frame whose execution context has not been observed -> [[BrowserIFrameInvalidException]]
  *     (`reason = ContextNotObserved`)
  *
  * The ContextNotObserved scenario manipulates the per-tab `frameContexts` map to model the narrow window
  * where the iframe's `Runtime.executionContextCreated` event has not yet landed.
  */
class IFrameResolverTest extends kyo.BrowserTest:

    override def timeout = 60.seconds

    "resolveIFrameHandle aborts with BrowserElementNotFoundException for a selector that matches nothing" in {
        withBrowser {
            onPage("<body><h1>no frames</h1></body>") {
                Abort.run[BrowserReadException] {
                    IFrameResolver.resolveIFrameHandle(Selector.id("missing"))
                }.map {
                    case Result.Failure(ex: BrowserElementNotFoundException) => assert(ex.getMessage.contains("Element not found"))
                    case other => fail(s"expected BrowserElementNotFoundException but got $other")
                }
            }
        }
    }

    "resolveIFrameHandle aborts with BrowserIFrameInvalidException(NotAFrame) for a selector that matches a non-frame element" in {
        withBrowser {
            onPage("<body><h1 id='heading'>not a frame</h1></body>") {
                Abort.run[BrowserReadException] {
                    IFrameResolver.resolveIFrameHandle(Selector.id("heading"))
                }.map {
                    case Result.Failure(BrowserIFrameInvalidException(Reason.NotAFrame)) =>
                        ()
                    case other => fail(s"expected BrowserIFrameInvalidException(NotAFrame) but got $other")
                }
            }
        }
    }

    "resolveIFrameHandle aborts with BrowserIFrameInvalidException(ContextNotObserved) when the frame's context has been drained from the per-tab map" in {
        // Standard parent-iframe page: the iframe attaches and the resolver pipeline can locate it,
        // but if we drain `frameContexts` of the iframe's executionContextId entry first, the resolver
        // path through `tab.frameContexts.get` must surface ContextNotObserved.
        val parent = """<body>
            <iframe id="frame" data-testid="frame" srcdoc="<body><span>x</span></body>"></iframe>
        </body>"""
        withBrowser {
            Browser.goto(srcdocPage(parent, "<body><span>x</span></body>")).andThen {
                // Wait for the iframe's context to land; iframe.list polling is the deterministic barrier.
                Browser.withConfig(_.retrySchedule(Schedule.fixed(50.millis).take(40))) {
                    Browser.assertExists(Browser.Selector.testId("frame"))
                }.andThen {
                    Retry[BrowserAssertionException](Schedule.fixed(50.millis).take(80)) {
                        Browser.iframes.map { frames =>
                            if frames.size >= 2 then ()
                            else
                                Abort.fail[BrowserAssertionException](
                                    BrowserAssertionTimedOutException("iframe.list size", ">=2", frames.size.toString)
                                )
                        }
                    }
                }.andThen {
                    Browser.use { tab =>
                        // Drain the entire per-tab frame->context map. Any subsequent iframe resolution
                        // will land in the `ContextNotObserved` arm because the resolver's lookup
                        // `ctxMap.get(fid)` returns Absent.
                        tab.frameContexts.set(Dict.empty).andThen {
                            Abort.run[BrowserReadException] {
                                IFrameResolver.resolveIFrameHandle(Selector.testId("frame"))
                            }.map {
                                case Result.Failure(BrowserIFrameInvalidException(Reason.ContextNotObserved)) =>
                                    ()
                                case other => fail(s"expected BrowserIFrameInvalidException(ContextNotObserved) but got $other")
                            }
                        }
                    }
                }
            }
        }
    }

end IFrameResolverTest
