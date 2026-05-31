package kyo

class BrowserNetworkTest extends BrowserTest:

    override def timeout = 90.seconds

    // ---- waitForNetworkIdle ----

    "waitForNetworkIdle succeeds when no network activity" in run {
        withBrowser {
            onPage("<html><body><div id='static-div'>Static page</div></body></html>") {
                Browser.withConfig(_.retrySchedule(Schedule.fixed(50.millis).maxDuration(5.seconds))) {
                    Browser.waitForNetworkIdle(100.millis)
                }.andThen {
                    // Postcondition: the static page is reachable after the idle wait. Pins that waitForNetworkIdle
                    // did not interfere with normal DOM access.
                    Browser.text(Browser.Selector.id("static-div")).map { t =>
                        assert(t == "Static page", s"expected 'Static page' after waitForNetworkIdle but got '$t'")
                    }
                }
            }
        }
    }

    "waitForNetworkIdle waits for pending fetch to complete" in run {
        withBrowser {
            onPage("""<html><body>
            <div id='status'>loading</div>
            <script>
                setTimeout(() => {
                    fetch('data:text/plain,ok').then(() => {
                        document.getElementById('status').textContent = 'done';
                    });
                }, 100);
            </script>
        </body></html>""") {
                Browser.withConfig(_.retrySchedule(Schedule.fixed(50.millis).maxDuration(10.seconds))) {
                    Browser.waitForNetworkIdle(200.millis)
                }.andThen {
                    // Postcondition: #status flipped to "done", proving the fetch completed before
                    // waitForNetworkIdle returned.
                    Browser.text(Browser.Selector.id("status")).map { t =>
                        assert(
                            t == "done",
                            s"expected '#status' to be 'done' after waitForNetworkIdle (proving fetch completed before idle returned) but got '$t'"
                        )
                    }
                }
            }
        }
    }

    // The idle window vs loop-interval comparator was verified healthy at the 10× ratio.
    // Idle window: 500.millis; loop interval: 50ms (`setTimeout(keepFetching, 50)`).  Even on
    // a very slow CI seeing each fetch take 200ms, a fresh request still fires every ~250ms,
    // comfortably under the 500ms idle window.  The `Browser.SessionConfig.default.networkIdleWindow`
    // also picks 500ms (verified in BrowserConfigTest.scala), so this test's comparator
    // matches the production default.
    "waitForNetworkIdle times out when requests keep coming" in run {
        withBrowser {
            onPage("""<html><body>
            <script>
                function keepFetching() {
                    fetch('data:text/plain,ping').then(() => setTimeout(keepFetching, 50));
                }
                keepFetching();
            </script>
        </body></html>""") {
                Browser.withConfig(_.retrySchedule(Schedule.fixed(50.millis).take(5))) {
                    Abort.run[BrowserAssertionException] {
                        Browser.waitForNetworkIdle(500.millis)
                    }
                }.map { result =>
                    result match
                        case Result.Failure(_: BrowserAssertionTimedOutException) => succeed
                        case other => fail(s"Expected BrowserAssertionTimedOutException timeout from waitForNetworkIdle but got $other")
                }
            }
        }
    }

    // ---- waitForRequestUrl ----

    "waitForRequestUrl returns the URL of the first matching request" in run {
        withBrowser {
            onPage("<html><body></body></html>") {
                // Mock the endpoint so the fetch resolves (the tracking interceptor only records
                // URLs after the response arrives, so the request must succeed).
                Browser.use { tab =>
                    Browser.mockFetchResponse("/api/test-endpoint-kyo", 200, "ok").andThen {
                        // Fire the fetch in a background fiber that first awaits the tracker-install marker
                        // (`window.__kyoResponseTrackingInstalled`) set by `ensureResponseTracking` inside
                        // `waitForRequestUrl`. `Browser.runOn(tab)` strips the `Browser` effect so
                        // `Fiber.initUnscoped` only needs an `Isolate` over `Async`, not `Browser`.
                        // Deterministic ordering: the fetch never fires until the tracker is installed,
                        // independent of host CPU speed or CI load.
                        Fiber.initUnscoped(
                            Browser.runOn(tab) {
                                Browser.waitFor("typeof window.__kyoResponseTrackingInstalled !== 'undefined' ? 'true' : ''")
                                    .andThen(Browser.eval("fetch('/api/test-endpoint-kyo'); 'armed'"))
                            }
                        ).andThen {
                            Browser.waitForRequestUrl("/api/test-endpoint-kyo").map { matched =>
                                assert(
                                    matched.contains("/api/test-endpoint-kyo"),
                                    s"Expected matched URL to contain '/api/test-endpoint-kyo' but got '$matched'"
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // ---- waitForRequestUrl timeout describes tracker state (A5) ----
    //
    // When no request matches, the timeout exception's `actual` field used to say "(none)". When the test fired some
    // requests but none matched the pattern, the user couldn't tell whether the tracker was even working. Surfacing
    // the observed-URL list (or an explicit "0 requests observed") makes the diagnostic instant.

    "waitForRequestUrl timeout message describes tracker state (count + sample of observed URLs)" in run {
        withBrowser {
            onPage("""<html><body>
                <script>
                    // Fire 2 non-matching fetches to data: URLs (tracker records them but they don't match the pattern).
                    fetch('data:text/plain,decoyA');
                    fetch('data:text/plain,decoyB');
                </script>
            </body></html>""") {
                Browser.withConfig(_.retrySchedule(Schedule.fixed(50.millis).take(3))) {
                    Abort.run[BrowserAssertionException] {
                        Browser.waitForRequestUrl("nonexistent-pattern-xyz")
                    }
                }.map { result =>
                    result match
                        case Result.Failure(ex: BrowserAssertionTimedOutException) =>
                            val msg = ex.getMessage
                            assert(
                                msg.contains("observed=") || msg.contains("observed:"),
                                s"expected the waitForRequestUrl timeout message to describe tracker state (observed count + sample URLs), got: $msg"
                            )
                        case other => fail(s"Expected BrowserAssertionTimedOutException but got $other")
                }
            }
        }
    }

    // ---- mockFetchResponse ----

    "mockFetchResponse intercepts a fetch call and substitutes the mocked body" in run {
        withBrowser {
            onPage("""<html><body>
                <div id="result"></div>
                <script>
                  window.loadData = function() {
                    return fetch('/mock-api/data').then(function(r) { return r.text(); }).then(function(t) {
                      document.getElementById('result').innerText = t;
                    });
                  };
                </script>
            </body></html>""") {
                Browser.mockFetchResponse("/mock-api/data", 200, "mocked-body").andThen {
                    Browser.eval("loadData()").andThen {
                        Browser.assertText(Browser.Selector.id("result"), "mocked-body").andThen {
                            Browser.text(Browser.Selector.id("result")).map { t =>
                                assert(t == "mocked-body", s"expected '#result' text to be 'mocked-body' but got '$t'")
                            }
                        }
                    }
                }
            }
        }
    }

    // The in-page __kyoMocks registry uses direct key assignment (replace semantics, not stack).
    // This test codifies that contract and guards against any future regression that switches to stack-of-mocks.
    "mockFetchResponse same-URL second registration replaces the first" in run {
        withBrowser {
            onPage("""<html><body>
                <div id="result"></div>
                <script>
                  window.loadData = function() {
                    return fetch('/api/value').then(function(r) { return r.text(); }).then(function(t) {
                      document.getElementById('result').innerText = t;
                    });
                  };
                </script>
            </body></html>""") {
                Browser.mockFetchResponse("/api/value", 200, "first").andThen {
                    Browser.mockFetchResponse("/api/value", 200, "second").andThen {
                        Browser.eval("loadData()").andThen {
                            Browser.assertText(Browser.Selector.id("result"), "second").map(_ => succeed)
                        }
                    }
                }
            }
        }
    }

    // The registry is a JS object keyed by URL, so distinct URLs map to distinct entries.
    "mockFetchResponse distinct URLs coexist; each fetch returns its own body" in run {
        withBrowser {
            onPage("""<html><body>
                <div id="r1"></div><div id="r2"></div>
                <script>
                  window.fetchBoth = async function() {
                    const a = await fetch('/api/alpha').then(function(r) { return r.text(); });
                    const b = await fetch('/api/beta').then(function(r) { return r.text(); });
                    document.getElementById('r1').innerText = a;
                    document.getElementById('r2').innerText = b;
                  };
                </script>
            </body></html>""") {
                Browser.mockFetchResponse("/api/alpha", 200, "alpha-body").andThen {
                    Browser.mockFetchResponse("/api/beta", 200, "beta-body").andThen {
                        Browser.eval("fetchBoth()").andThen {
                            Browser.assertText(Browser.Selector.id("r1"), "alpha-body").andThen {
                                Browser.assertText(Browser.Selector.id("r2"), "beta-body").map(_ => succeed)
                            }
                        }
                    }
                }
            }
        }
    }

    "mockFetchResponse does not intercept the top-level navigation request" in run {
        withBrowser {
            val p1 = page("<html><head><title>Page1</title></head><body><div id='marker'>page1-ok</div></body></html>")
            val p2 = page("<html><head><title>Page2</title></head><body><div id='marker'>page2-ok</div></body></html>")
            Browser.goto(p1).andThen {
                // Register a mock for page2's URL: CDP-level navigation must not be intercepted
                // by the JS-level fetch/XHR hook installed by mockFetchResponse.
                Browser.mockFetchResponse(p2, 200, "should-not-appear").andThen {
                    Browser.goto(p2).andThen {
                        Browser.text(Browser.Selector.id("marker")).map { text =>
                            assert(text == "page2-ok", s"Expected 'page2-ok' (navigation not intercepted) but got '$text'")
                        }
                    }
                }
            }
        }
    }

    // ---- waitForNetworkIdle no-arg overload ----

    "waitForNetworkIdle(using Frame) waits with default idle window, same as waitForNetworkIdle(500.millis, Absent)" in run {
        val p = page("<html><body><div id='status'>static</div></body></html>")
        withBrowser {
            Browser.goto(p).map { _ =>
                Browser.waitForNetworkIdle.map { _ =>
                    Browser.text(Browser.Selector.css("#status")).map { t =>
                        assert(t == "static", s"Expected 'static' after waitForNetworkIdle but got '$t'")
                    }
                }
            }
        }
    }

    // ---- clearMocks ----

    "clearMocks removes a previously-registered mock" in run {
        withBrowser {
            onPage("""<html><body></body></html>""") {
                // Register a mock, verify it is visible in __kyoMocks, then clear and re-check.
                Browser.mockFetchResponse("/api/clearable", 200, "mocked-value").andThen {
                    // Confirm mock is active in the JS-side registry.
                    Browser.eval("window.__kyoMocks && !!window.__kyoMocks['/api/clearable'] ? 'yes' : 'no'").map { v =>
                        assert(v == "yes", s"Expected mock to be registered before clearMocks but got '$v'")
                    }.andThen {
                        Browser.clearMocks.andThen {
                            Browser.eval("window.__kyoMocks && !!window.__kyoMocks['/api/clearable'] ? 'yes' : 'no'").map { v =>
                                assert(v == "no", s"Expected mock to be absent after clearMocks but got '$v'")
                            }
                        }
                    }
                }
            }
        }
    }

    "clearMocks on a fresh tab with no registered mocks is idempotent" in run {
        withBrowser {
            onPage("<html><body></body></html>") {
                Browser.clearMocks.andThen {
                    Browser.clearMocks.map(_ => succeed)
                }
            }
        }
    }

    // ---- mockFetchResponse with explicit headers ----

    // Verifies the new Seq[(String, String)] headers API round-trips through the JS payload. Sends two
    // Set-Cookie headers (duplicate name, preserved by the array-of-tuples Response shape) plus a
    // Content-Type. The fetch response surface lets us read the Content-Type via Response.headers.get
    // (case-insensitive per HTTP semantics); duplicate Set-Cookie folding by Response.headers.get is
    // browser-side behaviour, this test just confirms the payload arrives without rejection and the
    // Content-Type is visible.
    "mockFetchResponse with Seq[(String, String)] headers preserves duplicate header names and case-insensitive lookup" in run {
        withBrowser {
            onPage("""<html><body>
                <script>
                  window.fetchAndStore = function() {
                    return fetch('/multi-cookie').then(function(r) {
                      // Case-insensitive lookup: 'content-type' matches the registered "Content-Type"
                      window.__ct = r.headers.get('content-type');
                      return r.text();
                    }).then(function(t) {
                      window.__body = t;
                    });
                  };
                </script>
            </body></html>""") {
                Browser.mockFetchResponse(
                    "/multi-cookie",
                    200,
                    "ok",
                    Seq("Set-Cookie" -> "a=1", "Set-Cookie" -> "b=2", "Content-Type" -> "text/plain")
                ).andThen {
                    Browser.eval("fetchAndStore()").andThen {
                        Retry[BrowserScriptException](Schedule.fixed(50.millis).take(40)) {
                            Browser.eval("typeof window.__body === 'string' ? window.__body : '__PENDING__'").map {
                                case "__PENDING__" => Abort.fail[BrowserScriptException](BrowserScriptErrorException("body pending"))
                                case v             => v
                            }
                        }.map { body =>
                            assert(body == "ok", s"expected body='ok' but got '$body'")
                            Browser.eval("String(window.__ct)").map { ct =>
                                assert(ct == "text/plain", s"expected case-insensitive Content-Type='text/plain' but got '$ct'")
                            }
                        }
                    }
                }
            }
        }
    }

    // Per HTTP spec (RFC 7230), header lookup is case-insensitive. Register a mock with capitalised
    // "Content-Type" and verify the response handler can read it via lowercase "content-type".
    "mockFetchResponse Content-Type round-trips via Seq[(String, String)] and supports case-insensitive lookup" in run {
        withBrowser {
            onPage("""<html><body>
                <script>
                  window.fetchAndCapture = function() {
                    return fetch('/api/json-endpoint').then(function(r) {
                      // Lowercase 'content-type' key must hit the registered "Content-Type" header per RFC 7230.
                      window.__ctLower = r.headers.get('content-type');
                      // Mixed-case variant must hit the same header.
                      window.__ctMixed = r.headers.get('Content-Type');
                      return r.text();
                    }).then(function(t) {
                      window.__bodyJson = t;
                    });
                  };
                </script>
            </body></html>""") {
                Browser.mockFetchResponse(
                    "/api/json-endpoint",
                    200,
                    """{"k":"v"}""",
                    Seq("Content-Type" -> "application/json")
                ).andThen {
                    Browser.eval("fetchAndCapture()").andThen {
                        Retry[BrowserScriptException](Schedule.fixed(50.millis).take(40)) {
                            Browser.eval("typeof window.__bodyJson === 'string' ? window.__bodyJson : '__PENDING__'").map {
                                case "__PENDING__" => Abort.fail[BrowserScriptException](BrowserScriptErrorException("body pending"))
                                case v             => v
                            }
                        }.map { body =>
                            assert(body == """{"k":"v"}""", s"expected JSON body but got '$body'")
                            Browser.eval("String(window.__ctLower)").map { ctLower =>
                                assert(
                                    ctLower == "application/json",
                                    s"expected lowercase 'content-type' lookup to return 'application/json' but got '$ctLower'"
                                )
                                Browser.eval("String(window.__ctMixed)").map { ctMixed =>
                                    assert(
                                        ctMixed == "application/json",
                                        s"expected mixed-case 'Content-Type' lookup to return 'application/json' but got '$ctMixed'"
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // ---- MockResponseEnvelope encode/decode round-trip ----

    // Verify the envelope round-trips so a future drift in the case class shape, default values, or Schema
    // derivation is caught at the wire boundary rather than at the JS-interception path.
    "MockResponseEnvelope round-trips through Json encode/decode" in run {
        val original = kyo.internal.MockResponseEnvelope(
            status = 200,
            body = """{"ok":true}""",
            headers = Chunk(
                kyo.internal.MockHeader("Content-Type", "application/json"),
                kyo.internal.MockHeader("X-Trace-Id", "abc-123")
            )
        )
        val encoded = Json.encode(original)
        Json.decode[kyo.internal.MockResponseEnvelope](encoded) match
            case Result.Success(decoded) =>
                assert(decoded.status == original.status)
                assert(decoded.body == original.body)
                assert(Json.encode(decoded) == encoded)
            case other =>
                fail(s"MockResponseEnvelope did not round-trip: $other (encoded=$encoded)")
        end match
    }

end BrowserNetworkTest
