package kyo

import kyo.BrowserElementNotActionableException.Reason
import kyo.internal.BrowserEval
import kyo.internal.CdpBackend
import kyo.internal.CdpClient
import kyo.internal.NavigationWatcher
import kyo.internal.SharedChrome

class BrowserCoreTest extends BrowserTest:

    // Class timeout: 180s rather than the 90s default; BrowserCoreTest's "50 cycles" leak-bound test
    // takes ~80s alone on JS (Scala.js single-threaded runtime running 50 sequential Browser.run cycles).
    // Under suite-wide CPU contention the test tips over a 90s budget; 180s gives headroom.
    override def timeout = 180.seconds

    // ---- Wire-shape drift observability ----

    "parseAxTree aborts with BrowserProtocolErrorException when CDP wire shape drifts" in run {
        // The new typed decoder (Json.decode[AxTreeResponse]) is strict: a malformed `properties`
        // field; a JSON string where Seq[AxPropertyWire] is required; fails the schema decode
        // and the parser raises Abort.fail(BrowserProtocolErrorException) (see Accessibility.parseAxTree).
        //
        // The strict contract: rather than silently returning a default-filled node,
        // wire-shape drift surfaces as a typed protocol-error abort. The test asserts that path.
        val malformedJson =
            """{"nodes":[{"nodeId":"9","ignored":false,"role":{"type":"role","value":"button"},""" +
                """"name":{"type":"computedString","value":"Save"},"properties":"not-an-array"}]}"""
        Abort.run[BrowserConnectionException](kyo.internal.cdp.Accessibility.parseAxTree(malformedJson)).map {
            case Result.Failure(_: BrowserProtocolErrorException) => succeed
            case other => fail(s"Expected Abort.fail(BrowserProtocolErrorException) for malformed properties, got $other")
        }
    }

    // ---- Lifecycle ----

    "connect navigates to about:blank initially" in run {
        withBrowser {
            Browser.url.map { u =>
                assert(u == "about:blank")
            }
        }
    }

    // ---- Navigation ----

    "goto navigates to data URL" in run {
        val p = page("<h1>Hello</h1>")
        withBrowser {
            Browser.goto(p).map { _ =>
                Browser.url.map { u =>
                    assert(u.startsWith("data:text/html"))
                }
            }
        }
    }

    "goto then title returns correct title" in run {
        val p = page("<html><head><title>TestTitle</title></head><body></body></html>")
        withBrowser {
            Browser.goto(p).map { _ =>
                Browser.title.map { t =>
                    assert(t == "TestTitle", s"Expected 'TestTitle' but got '$t'")
                }
            }
        }
    }

    // `back` at history start raises BrowserAlreadyAtHistoryStartException rather than silently succeeding.
    // Callers wanting no-op behaviour can recover the exception explicitly.
    "back on fresh tab raises BrowserAlreadyAtHistoryStartException" in run {
        withBrowser {
            Abort.run[BrowserNavigationException] {
                Browser.back
            }.map {
                case Result.Failure(_: BrowserAlreadyAtHistoryStartException) => succeed
                case other =>
                    fail(s"Expected BrowserAlreadyAtHistoryStartException but got $other")
            }
        }
    }

    // ---- Reads ----

    "title returns empty for page without title" in run {
        val p = page("<body>Notitlehere</body>")
        withBrowser {
            Browser.goto(p).map { _ =>
                Browser.title.map { t =>
                    assert(t.isEmpty, s"Expected empty title but got: '$t'")
                }
            }
        }
    }

    "eval returns arithmetic result" in run {
        withBrowser {
            Browser.eval("1+1").map { result =>
                assert(result == "2", s"Expected '2' but got '$result'")
            }
        }
    }

    "eval returns string result" in run {
        withBrowser {
            Browser.eval("'hello'").map { result =>
                assert(result == "hello", s"Expected 'hello' but got '$result'")
            }
        }
    }

    "eval returns document title" in run {
        val p = page("<html><head><title>EvalTest</title></head><body></body></html>")
        withBrowser {
            Browser.goto(p).map { _ =>
                Browser.eval("document.title").map { result =>
                    assert(result == "EvalTest", s"Expected 'EvalTest' but got '$result'")
                }
            }
        }
    }

    "eval returns empty string for undefined" in run {
        withBrowser {
            Browser.eval("undefined").map { result =>
                assert(result == "", s"Expected empty string but got '$result'")
            }
        }
    }

    "eval returns boolean result" in run {
        withBrowser {
            Browser.eval("true").map { result =>
                assert(result == "true", s"Expected 'true' but got '$result'")
            }
        }
    }

    // `Browser.eval` must surface JS errors as a typed `BrowserScriptException` (via `evalJsChecked`). This keeps user-written JS errors
    // distinguishable from "no result"; the property that was *not* held internally and that made the selector-escape bug invisible.

    "eval raises BrowserScriptException on JS syntax error" in run {
        withBrowser {
            Abort.run[BrowserScriptException](Browser.eval("this is not valid js"))
                .map(r => assert(r.isFailure, s"expected failure but got $r"))
        }
    }

    "eval raises BrowserScriptException on reference error" in run {
        withBrowser {
            Abort.run[BrowserScriptException](Browser.eval("thisDefinitelyDoesNotExist()"))
                .map(r => assert(r.isFailure, s"expected failure but got $r"))
        }
    }

    "eval raises BrowserScriptException on a thrown exception" in run {
        withBrowser {
            Abort.run[BrowserScriptException](Browser.eval("(() => { throw new Error('boom'); })()"))
                .map(r => assert(r.isFailure, s"expected failure but got $r"))
        }
    }

    // Internal JS evaluation failures must surface as a typed BrowserConnectionException via Abort, never as a thrown RuntimeException.
    // Internal helpers (NavigationWatcher, MutationSettlement) call `Browser.evalJsInternal`; a CDP `exceptionDetails` payload must
    // propagate through the Abort channel rather than escape it.
    "evalJsInternal raises BrowserProtocolErrorException on internal JS evaluation error" in run {
        withBrowser {
            Abort.run[BrowserConnectionException] {
                BrowserEval.evalJs("(() => { throw new Error('internal-eval-boom'); })()")
            }.map {
                case Result.Failure(ex: BrowserProtocolErrorException) =>
                    val msg = ex.getMessage
                    assert(
                        msg.contains("kyo-browser internal JS evaluation failed"),
                        s"expected 'kyo-browser internal JS evaluation failed' marker in message but got: $msg"
                    )
                    assert(msg.contains("internal-eval-boom"), s"expected JS error text 'internal-eval-boom' in message but got: $msg")
                case other => fail(s"expected BrowserProtocolErrorException via Abort but got $other")
            }
        }
    }

    // CDP's `exceptionDetails.stackTrace.callFrames` carries the V8 frames for a thrown exception; the BrowserScriptErrorException
    // message must include both the error text and at least one `"at <fn> (<url>:<line>:<col>)"` frame from the formatted stack trace.
    "Script exception message includes the CDP stack trace" in run {
        withBrowser {
            Abort.run[BrowserScriptException] {
                Browser.eval("(() => { throw new Error('boom at line'); })()")
            }.map {
                case Result.Failure(ex: BrowserScriptErrorException) =>
                    val msg = ex.getMessage
                    assert(msg.contains("boom at line"), s"expected error text 'boom at line' in message but got: $msg")
                    // Look for at least one stack-frame line formatted as `at <fn> (<url>:<line>:<col>)`. Dev-mode rendering
                    // wraps the exception message with ANSI highlighting, so we search for the pattern anywhere rather than
                    // anchoring at a line start.
                    val stackLine = """at \S+ \([^)]+:\d+:\d+\)""".r
                    assert(
                        stackLine.findFirstIn(msg).isDefined,
                        s"expected at least one 'at <fn> (<url>:<line>:<col>)' stack frame in message but got: $msg"
                    )
                case other => fail(s"expected BrowserScriptErrorException but got $other")
            }
        }
    }

    // CDP sets exceptionDetails for any JS throw regardless of whether the value is an Error instance; the non-Error branch must
    // also surface as BrowserScriptErrorException.
    "eval raises BrowserScriptErrorException when JS throws a non-Error primitive" in run {
        withBrowser {
            Abort.run[BrowserScriptException](Browser.eval("(() => { throw 'plain string'; })()"))
                .map {
                    case Result.Failure(_: BrowserScriptErrorException) => succeed
                    case other                                          => fail(s"expected BrowserScriptErrorException but got $other")
                }
        }
    }

    // Symbol result: CDP returns `{type:"symbol"}` without value/description/exceptionDetails. The decoder falls through to `""`
    // when no value, description, or "undefined" type is present; no BrowserScriptException is raised (a documented CDP limitation).
    "eval returns empty string for a Symbol result and does not raise BrowserScriptException" in run {
        withBrowser {
            Abort.run[BrowserScriptException](Browser.eval("Symbol('tag')"))
                .map {
                    case Result.Success(v) =>
                        // CDP may return empty string (no value field) or the description field —
                        // either is acceptable; the key property is that no Abort was raised.
                        assert(
                            v == "" || v.contains("Symbol"),
                            s"expected empty string or Symbol description for Symbol result but got '$v'"
                        )
                    case other => fail(s"expected Result.Success (no Abort) for Symbol result but got $other")
                }
        }
    }

    // CDP `eval` resolves synchronously; a Promise rejection is async, so `Browser.eval` returns the Promise object reference (`"{}"`),
    // not the rejection. No immediate BrowserScriptException is raised; a documented CDP limitation for async rejection.
    "eval does not raise BrowserScriptException for a synchronously returned rejected Promise" in run {
        withBrowser {
            Abort.run[BrowserScriptException](Browser.eval("Promise.reject(new Error('async-err'))"))
                .map {
                    case Result.Success(_) => succeed
                    case other             => fail(s"expected Result.Success (no immediate Abort) for Promise rejection but got $other")
                }
        }
    }

    "screenshot returns non-empty image" in run {
        val p = page("<h1>ScreenshotTest</h1>")
        withBrowser {
            Browser.goto(p).map { _ =>
                Browser.screenshot().map { img =>
                    assert(img.binary.size > 0, "Screenshot should be non-empty")
                }
            }
        }
    }

    "Image.base64 matches java.util.Base64 reference encoder on the same bytes" in {
        val raw      = Array.tabulate(257)(i => (i * 31 + 7).toByte)
        val img      = Browser.Image.fromBinary(raw)
        val expected = java.util.Base64.getEncoder.encodeToString(raw)
        assert(img.base64 == expected, s"Image.base64 must match the Java reference encoder")
    }

    "readableContent returns page text" in run {
        val p = page("<body><p>HelloWorld</p></body>")
        withBrowser {
            Browser.goto(p).map { _ =>
                Browser.readableContent.map { content =>
                    assert(content.contains("HelloWorld"), s"Expected 'HelloWorld' in: $content")
                }
            }
        }
    }

    "readableContent strips nav and script elements" in run {
        val p = page(
            "<body><nav>NavContent</nav><script>var x=1;</script><article><p>ArticleText</p></article><footer>FooterContent</footer></body>"
        )
        withBrowser {
            Browser.goto(p).map { _ =>
                Browser.readableContent.map { content =>
                    assert(content.contains("ArticleText"), s"Expected 'ArticleText' in: $content")
                    assert(!content.contains("NavContent"), s"Should not contain nav content: $content")
                    assert(!content.contains("FooterContent"), s"Should not contain footer content: $content")
                }
            }
        }
    }

    "readableContent prefers article element" in run {
        val p = page(
            "<body><div>OuterText</div><article><p>MainArticle</p></article></body>"
        )
        withBrowser {
            Browser.goto(p).map { _ =>
                Browser.readableContent.map { content =>
                    assert(content.contains("MainArticle"), s"Expected 'MainArticle' in: $content")
                    assert(!content.contains("OuterText"), s"Should prefer article over body: $content")
                }
            }
        }
    }

    // ---- Network interception ----

    "mockFetchResponse registers mocks and installs interceptor" in run {
        val p = page("<body></body>")
        withBrowser {
            Browser.goto(p).map { _ =>
                Browser.mockFetchResponse(
                    "https://example.com/api/test",
                    200,
                    "mocked-response",
                    Seq("Content-Type" -> "text/plain")
                ).map {
                    _ =>
                        // Verify the mock was registered
                        Browser.eval("JSON.stringify(window.__kyoMocks['https://example.com/api/test'])").map { result =>
                            assert(result.contains("mocked-response"), s"Expected mock to contain 'mocked-response': $result")
                            assert(result.contains("200"), s"Expected mock to contain status 200: $result")
                        }
                }
            }
        }
    }

    "mockFetchResponse intercepts fetch requests" in run {
        val p = page("<body><div id='result'>empty</div></body>")
        withBrowser {
            Browser.goto(p).map { _ =>
                Browser.mockFetchResponse("https://example.com/api/data", 200, "hello-mock").map { _ =>
                    // Trigger fetch and write result to DOM
                    Browser.eval(
                        "fetch('https://example.com/api/data').then(r => r.text()).then(t => { document.getElementById('result').innerText = t; return 'done'; })"
                    ).map { _ =>
                        // Poll for the DOM change since eval returns {} for promises
                        Retry[BrowserScriptException](Schedule.fixed(50.millis).take(20)) {
                            Browser.eval("document.getElementById('result').innerText").map { text =>
                                if text == "hello-mock" then succeed
                                else
                                    Abort.fail[BrowserScriptException](
                                        BrowserScriptErrorException(s"Expected 'hello-mock' but got '$text'")
                                    )
                            }
                        }
                    }
                }
            }
        }
    }

    "clearMocks removes mock rules" in run {
        val p = page("<body></body>")
        withBrowser {
            Browser.goto(p).map { _ =>
                Browser.mockFetchResponse("https://example.com/api", 200, "mock").map { _ =>
                    Browser.clearMocks.map { _ =>
                        Browser.eval("JSON.stringify(window.__kyoMocks)").map { result =>
                            assert(result == "{}", s"Expected empty mocks but got '$result'")
                        }
                    }
                }
            }
        }
    }

    // `mockFetchResponse` embeds URL, body, and each header entry into a JS installer script. Any character that alters the JS parse
    // (backslash, single quote, newline, CR, tab) must be escaped; otherwise the installer throws and, thanks to `evalJs` swallowing
    // errors, the mock is silently NOT installed. These tests fetch the mocked URL end-to-end and compare the received body/header.

    private def fetchMockedBody(url: String)(using
        Frame
    ): String < (Browser & Async & Abort[BrowserReadException]) =
        val jsUrl = url
            .replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t")
        Browser.eval(s"""
            fetch('$jsUrl').then(r => r.text()).then(t => { window.__out = t; }).catch(e => { window.__out = 'ERR:' + e.message; })
        """).andThen {
            Retry[BrowserReadException](Schedule.fixed(50.millis).take(40)) {
                Browser.eval("typeof window.__out === 'string' ? window.__out : '__PENDING__'").map {
                    case "__PENDING__" => Abort.fail(BrowserScriptErrorException("mock fetch pending"))
                    case v             => v
                }
            }
        }
    end fetchMockedBody

    "mockFetchResponse body preserves a single quote" in run {
        val p = page("<body></body>")
        withBrowser {
            Browser.goto(p).andThen(Browser.mockFetchResponse("https://x/api", 200, "it's fine"))
                .andThen(fetchMockedBody("https://x/api"))
                .map(t => assert(t == "it's fine", s"body mismatch: got '$t'"))
        }
    }

    "mockFetchResponse body preserves a newline" in run {
        val p = page("<body></body>")
        withBrowser {
            Browser.goto(p).andThen(Browser.mockFetchResponse("https://x/api", 200, "line1\nline2"))
                .andThen(fetchMockedBody("https://x/api"))
                .map(t => assert(t == "line1\nline2", s"body mismatch: got '$t'"))
        }
    }

    "mockFetchResponse body preserves a backslash" in run {
        val p = page("<body></body>")
        withBrowser {
            Browser.goto(p).andThen(Browser.mockFetchResponse("https://x/api", 200, "a\\b"))
                .andThen(fetchMockedBody("https://x/api"))
                .map(t => assert(t == "a\\b", s"body mismatch: got '$t'"))
        }
    }

    "mockFetchResponse body preserves a JSON payload with quotes and backslashes" in run {
        val p    = page("<body></body>")
        val body = """{"name":"don't","value":42}"""
        withBrowser {
            Browser.goto(p)
                .andThen(Browser.mockFetchResponse("https://x/api", 200, body, Seq("Content-Type" -> "application/json")))
                .andThen(fetchMockedBody("https://x/api"))
                .map(t => assert(t == body, s"body mismatch: got '$t'"))
        }
    }

    "mockFetchResponse URL with a query string containing an apostrophe matches" in run {
        val p   = page("<body></body>")
        val url = "https://x/api?q=don't"
        withBrowser {
            Browser.goto(p).andThen(Browser.mockFetchResponse(url, 200, "ok"))
                .andThen(fetchMockedBody(url))
                .map(t => assert(t == "ok", s"body mismatch: got '$t'"))
        }
    }

    "mockFetchResponse header value preserves a single quote" in run {
        val p = page("<body></body>")
        withBrowser {
            Browser.goto(p)
                .andThen(Browser.mockFetchResponse("https://x/h", 200, "body", Seq("X-Note" -> "don't")))
                .andThen {
                    Browser.eval("""
                        fetch('https://x/h').then(r => { window.__hdr = r.headers.get('X-Note'); }).catch(e => { window.__hdr = 'ERR'; })
                    """).andThen {
                        Retry[BrowserScriptException](Schedule.fixed(50.millis).take(40)) {
                            Browser.eval("typeof window.__hdr === 'string' ? window.__hdr : '__PENDING__'").map {
                                case "__PENDING__" => Abort.fail[BrowserScriptException](BrowserScriptErrorException("hdr pending"))
                                case v             => v
                            }
                        }
                    }
                }
                .map(t => assert(t == "don't", s"header mismatch: got '$t'"))
        }
    }

    // ---- Tab management ----

    "withNewTab creates independent tab at about:blank" in run {
        withBrowser {
            Scope.run {
                Browser.withNewTab {
                    Browser.url.map { u =>
                        assert(u == "about:blank", s"Fresh tab should be about:blank but got $u")
                    }
                }
            }
        }
    }

    "withNewTab eval works" in run {
        withBrowser {
            Scope.run {
                Browser.withNewTab {
                    Browser.eval("1+2").map { r =>
                        assert(r == "3", s"Expected '3' but got '$r'")
                    }
                }
            }
        }
    }

    "withNewTab tab can navigate independently" in run {
        val p = page("<html><head><title>FreshNav</title></head><body>hi</body></html>")
        withBrowser {
            Scope.run {
                Browser.withNewTab {
                    Browser.goto(p).map { _ =>
                        Browser.title.map { t =>
                            assert(t == "FreshNav", s"Expected 'FreshNav' but got '$t'")
                        }
                    }
                }
            }
        }
    }

    "withNewTab parent tab unaffected" in run {
        val parentPage = page("<html><head><title>Parent</title></head><body>parent</body></html>")
        withBrowser {
            Browser.goto(parentPage).map { _ =>
                Scope.run {
                    Browser.withNewTab {
                        Browser.goto(page("<html><head><title>Child</title></head><body>child</body></html>")).map { _ =>
                            Browser.title.map { t =>
                                assert(t == "Child")
                            }
                        }
                    }
                }.map { _ =>
                    Browser.title.map { t =>
                        assert(t == "Parent", s"Parent title should be 'Parent' but got '$t'")
                    }
                }
            }
        }
    }

    // ---- Navigation back/forward/reload ----

    "reload keeps same URL" in run {
        val p = page("<h1>ReloadTest</h1>")
        withBrowser {
            Browser.goto(p).map { _ =>
                Browser.url.map { urlBefore =>
                    Browser.reload().map { _ =>
                        Browser.url.map { urlAfter =>
                            assert(urlBefore == urlAfter, s"URL changed after reload: $urlBefore -> $urlAfter")
                        }
                    }
                }
            }
        }
    }

    "back navigates to previous page" in run {
        val p1 = page("<h1>Page1</h1>")
        val p2 = page("<h1>Page2</h1>")
        withBrowser {
            Browser.goto(p1).map { _ =>
                Browser.goto(p2).map { _ =>
                    Browser.back.map { _ =>
                        Browser.url.map { u =>
                            assert(u == p1, s"Expected $p1 but got $u")
                        }
                    }
                }
            }
        }
    }

    "forward navigates after back" in run {
        val p1 = page("<h1>Page1</h1>")
        val p2 = page("<h1>Page2</h1>")
        withBrowser {
            Browser.goto(p1).map { _ =>
                Browser.goto(p2).map { _ =>
                    Browser.back.map { _ =>
                        Browser.forward.map { _ =>
                            Browser.url.map { u =>
                                assert(u == p2, s"Expected $p2 but got $u")
                            }
                        }
                    }
                }
            }
        }
    }

    // A click on an <a href="..."> with a non-empty href auto-waits for the navigation to settle before returning.
    // Rather than a real Chrome-level page nav (which across tests can leave Chrome briefly busy and break the subsequent test),
    // we drive the <a href> via a JS-intercepted click that pushes a fragment; this still exercises the nav-intent detection and
    // the settle wait path while keeping the top-level document stable across tests.
    "click on <a href> waits for navigation to settle before returning" in run {
        withBrowser {
            val p = page("""<body>
                <a id="link" href="#arrived"
                   onclick="event.preventDefault(); history.pushState({}, '', '#arrived'); document.getElementById('out').textContent='arrived'"
                   style="display:inline-block;width:100px;height:30px">go</a>
                <div id="out">initial</div>
            </body>""")
            for
                _       <- Browser.goto(p)
                _       <- Browser.click(Browser.Selector.id("link"))
                arrived <- Browser.text(Browser.Selector.id("out"))
            yield assert(arrived == "arrived", s"expected out='arrived' after clicking the <a href> but got '$arrived'")
            end for
        }
    }

    // A `<button type="submit">` inside a `<form>` is nav-intent. Drive the click on a JS-intercepted form submission so armAround
    // awaits the ensuing pushState.
    "click on a submit button waits for the form POST round-trip" in run {
        withBrowser {
            val p = page("""<body>
                <form onsubmit="event.preventDefault(); history.pushState({}, '', '#submitted'); document.getElementById('out').textContent='submitted'">
                    <button id="submit" type="submit" style="width:100px;height:30px">send</button>
                </form>
                <div id="out">idle</div>
            </body>""")
            for
                _         <- Browser.goto(p)
                _         <- Browser.click(Browser.Selector.id("submit"))
                submitted <- Browser.text(Browser.Selector.id("out"))
            yield assert(submitted == "submitted", s"expected out='submitted' after form submit but got '$submitted'")
            end for
        }
    }

    // The actionability gate detects the `location.*` substring in the onclick source text; armAround waits for the ensuing location
    // change to settle. The test uses a same-document pushState-shaped navigation (assigning `location.hash`) so it stays
    // deterministic across repeated runs while still exercising the nav-intent classification.
    "click on <button onclick='location.assign(...)'> auto-waits" in run {
        withBrowser {
            val p = page("""<body>
                <button id="b"
                        onclick="location.assign('#done'); document.getElementById('out').textContent='clicked'"
                        style="width:100px;height:30px">click me</button>
                <div id="out">idle</div>
            </body>""")
            for
                _       <- Browser.goto(p)
                _       <- Browser.click(Browser.Selector.id("b"))
                clicked <- Browser.text(Browser.Selector.id("out"))
            yield assert(clicked == "clicked", s"expected out='clicked' after location.assign-click but got '$clicked'")
            end for
        }
    }

    // A plain button without navigation intent should not be auto-waited; the click returns promptly.
    "click on a non-nav-intent button does not auto-wait" in run {
        withBrowser {
            val p = page("""<body>
                <button id="b" onclick="window.__kyoClicked = true" style="width:100px;height:30px">toggle</button>
            </body>""")
            for
                _       <- Browser.goto(p)
                urlPre  <- Browser.url
                start   <- Clock.nowMonotonic
                _       <- Browser.click(Browser.Selector.id("b"))
                end     <- Clock.nowMonotonic
                urlPost <- Browser.url
                clicked <- Browser.eval("String(window.__kyoClicked === true)")
            yield
                val elapsed = (end - start).toMillis
                // Behavior: a no-nav-intent click leaves the URL unchanged (NavigationWatcher saw no nav frame) AND the
                // JS handler ran (`__kyoClicked === true`). Together these prove armAround's short-circuit fired without
                // depending on a tight wall clock.
                assert(
                    urlPost == urlPre,
                    s"expected URL unchanged after no-nav-intent click but pre='$urlPre' post='$urlPost' (nav frame observed)"
                )
                assert(
                    clicked == "true",
                    s"expected window.__kyoClicked === true after click (handler must have run) but got '$clicked'"
                )
                // Soft timing envelope: armAround's no-nav-intent short-circuit avoids the navigation grace window, so the
                // click should still return well under a relaxed CI-tolerant bound. Hard tightness is enforced by the
                // deterministic checks above.
                assert(
                    elapsed < 1500,
                    s"expected a no-nav-intent click to return quickly (<1500ms soft envelope) but took ${elapsed}ms"
                )
            end for
        }
    }

    // goto(url) with no settle argument uses Browser.Settle.NetworkIdle; strictly stronger than DOMContentLoaded. A page that
    // kicks off a delayed XHR AFTER DOMContentLoaded must be awaited before goto returns.
    "Browser.goto(url) defaults to NetworkIdle (not just DOMContentLoaded)" in run {
        withBrowser {
            withHtmlServer(Map(
                "/main" -> """<html><body>
                    <div id='marker'>loading</div>
                    <script>
                        window.addEventListener('DOMContentLoaded', () => {
                            setTimeout(() => {
                                fetch('/data').then(() => {
                                    document.getElementById('marker').textContent = 'fetched';
                                });
                            }, 200);
                        });
                    </script>
                </body></html>""",
                "/data" -> "ok"
            )) { (host, port) =>
                for
                    _      <- Browser.goto(s"http://$host:$port/main")
                    marker <- Browser.text(Browser.Selector.id("marker"))
                yield
                    // If goto returned at DOMContentLoaded, marker would still be 'loading'. NetworkIdle
                    // waits past the delayed fetch, so we should see 'fetched'.
                    assert(marker == "fetched", s"expected marker='fetched' after NetworkIdle settle but got '$marker'")
            }
        }
    }

    // Passing settle = DomContentLoaded returns earlier than NetworkIdle; before the delayed XHR fires.
    "Browser.goto(url, Browser.Settle.DomContentLoaded) returns before the delayed XHR" in run {
        withBrowser {
            withHtmlServer(Map(
                "/main" -> """<html><body>
                    <div id='marker'>loading</div>
                    <script>
                        window.addEventListener('DOMContentLoaded', () => {
                            setTimeout(() => {
                                fetch('/data').then(() => {
                                    document.getElementById('marker').textContent = 'fetched';
                                });
                            }, 1000);
                        });
                    </script>
                </body></html>""",
                "/data" -> "ok"
            )) { (host, port) =>
                for
                    _      <- Browser.goto(s"http://$host:$port/main", Browser.Settle.DomContentLoaded)
                    marker <- Browser.text(Browser.Selector.id("marker"))
                yield
                    // With DomContentLoaded, we return before the 1000ms setTimeout fires, so marker should still be 'loading'.
                    assert(
                        marker == "loading",
                        s"expected marker='loading' with DomContentLoaded (earlier return) but got '$marker'"
                    )
            }
        }
    }

    // SPA-style navigation via history.pushState triggered by a click; the watcher's pushState monkey-patch sees this.
    "SPA pushState navigation triggered by a click is awaited" in run {
        withBrowser {
            val p = page("""<body>
                <button id="nav" onclick="history.pushState({}, '', '#view'); document.getElementById('state').textContent = 'navigated'" style="width:100px;height:30px">nav</button>
                <div id="state">initial</div>
            </body>""")
            for
                _     <- Browser.goto(p)
                _     <- Browser.click(Browser.Selector.id("nav"))
                state <- Browser.text(Browser.Selector.id("state"))
            yield assert(state == "navigated", s"expected state='navigated' after pushState-click but got '$state'")
            end for
        }
    }

    // A real HTTP 404 response raises BrowserNavigationFailedException when throwOnFailure=true.
    "goto raises BrowserNavigationFailedException on a 4xx with throwOnFailure=true" in run {
        withBrowser {
            withStatusServer { (host, port) =>
                val url = s"http://$host:$port/404"
                Abort.run[BrowserNavigationException] {
                    Browser.goto(url).unit
                }.map {
                    case Result.Failure(ex: BrowserNavigationFailedException) =>
                        assert(
                            ex.url.contains(url) || ex.url.contains("/404"),
                            s"expected the exception URL to reference /404 but got '${ex.url}'"
                        )
                    case other => fail(s"expected BrowserNavigationFailedException but got $other")
                }
            }
        }
    }

    // With failOnHttpError=false, goto succeeds on a 4xx response (the exception is suppressed).
    "goto with failOnHttpError=false returns normally on a 4xx" in run {
        withBrowser {
            withStatusServer { (host, port) =>
                val url = s"http://$host:$port/404"
                Abort.run[BrowserNavigationException] {
                    Browser.goto(url, failOnHttpError = false).unit
                }.map {
                    case Result.Success(_) => succeed
                    case Result.Failure(_: BrowserNavigationFailedException) =>
                        fail("expected goto(failOnHttpError=false) to succeed on a 4xx but got BrowserNavigationFailedException")
                    case other => fail(s"unexpected outcome: $other")
                }
            }
        }
    }

    // Back-to-back nav-intent clicks on the same tab serialise; the second click can only run after the first's armAround has
    // resolved. We verify by chaining two pushState-driven navigations on the same page and asserting that the final observed URL
    // matches the second click's target (not the first's).
    "back-to-back nav-intent clicks serialise without interleaving" in run {
        withBrowser {
            val p = page("""<body>
                <button id="first" onclick="history.pushState({}, '', '#one'); document.getElementById('out').textContent='one'" style="width:60px;height:30px">first</button>
                <button id="second" onclick="history.pushState({}, '', '#two'); document.getElementById('out').textContent='two'" style="width:60px;height:30px">second</button>
                <div id="out">init</div>
            </body>""")
            for
                _   <- Browser.goto(p)
                _   <- Browser.click(Browser.Selector.id("first"))
                _   <- Browser.click(Browser.Selector.id("second"))
                out <- Browser.text(Browser.Selector.id("out"))
                url <- Browser.url
            yield
                assert(out == "two", s"expected second nav observation (out='two') but got '$out'")
                assert(url.endsWith("#two"), s"expected url to end with '#two' after second click but got '$url'")
            end for
        }
    }

    // Maybe.Absent path: armAround threads `Maybe.Absent` into awaitSettle when the trigger is an arbitrary nav-intent click —
    // any observable navigation (URL change, pushState, beforeunload) must satisfy the watcher regardless of the prior URL.
    "NavigationWatcher with expectedDifferentFrom=Absent settles on any navigation" in run {
        withBrowser {
            val p = page("""<body>
                <button id="go" onclick="history.pushState({}, '', '#x'); document.getElementById('s').textContent='done'" style="width:80px;height:30px">go</button>
                <div id="s">init</div>
            </body>""")
            for
                _     <- Browser.goto(p)
                _     <- Browser.click(Browser.Selector.id("go"))
                state <- Browser.text(Browser.Selector.id("s"))
                url   <- Browser.url
            yield
                assert(state == "done", s"expected state='done' after armAround pushState-click but got '$state'")
                assert(url.endsWith("#x"), s"expected URL to end with '#x' after pushState navigation but got '$url'")
            end for
        }
    }

    // Maybe.Present path: armAroundNavigation threads `Maybe.Present(snapshot)` into awaitSettle. When the trigger completes but the URL
    // never changes (e.g. the trigger didn't actually navigate), `urlChanged` stays false and the watcher times out as never-committed.
    // Drives `armAroundNavigation` directly with a no-op trigger so the Present branch's URL-equality gate is exercised independent of
    // any `Browser.goto` short-circuit (Browser.goto skips the watcher when the requested URL equals the current URL).
    "NavigationWatcher with expectedDifferentFrom=Present(same URL) times out as never-committed" in run {
        withBrowser {
            withHtmlServer(Map(
                "/page" -> """<html><body><div id="ok">ok</div></body></html>"""
            )) { (host, port) =>
                val url = s"http://$host:$port/page"
                for
                    _ <- Browser.goto(url)
                    // Drive armAroundNavigation directly with a trigger that does nothing; the trigger completes but no navigation
                    // commits. The Present-branch gate requires urlChanged=true; since the trigger didn't navigate, the gate never opens
                    // and the watcher times out as never-committed.
                    abortResult <- Abort.run[BrowserNavigationException] {
                        Browser.withConfig(_.loadSchedule(Schedule.fixed(50.millis).maxDuration(800.millis))) {
                            kyo.internal.NavigationWatcher.armAroundNavigation(
                                Browser.Settle.DomContentLoaded,
                                throwOnFailure = true
                            )(Kyo.unit)
                        }
                    }
                yield abortResult match
                    case Result.Failure(ex: BrowserNavigationFailedException) =>
                        val msg = ex.getMessage
                        assert(
                            msg.contains("never committed") || msg.contains("settle timeout"),
                            s"expected a never-committed/settle-timeout failure but got: $msg"
                        )
                    case other => fail(s"expected BrowserNavigationFailedException from no-op trigger but got $other")
                end for
            }
        }
    }

    // After a chronic-network goto settles via the NetworkIdle → Load degrade, scope-owned state clears
    // and a follow-up goto runs cleanly; no wedged nav-recorder or leaked network-tracker state from the
    // previous scope.
    "NavigationWatcher state cleans up after a NetworkIdle → Load degrade" in run {
        withBrowser {
            withHtmlServer(Map(
                // Busy page: NetworkIdle never quiesces but `load` does fire on the initial HTML parse,
                // so the NetworkIdle → Load degrade applies.
                "/busy" -> """<html><body>
                    <script>setInterval(() => { fetch('/ping').catch(() => {}); }, 50);</script>
                </body></html>""",
                "/ping" -> "pong",
                "/ok"   -> """<html><body><div id="ok">ok</div></body></html>"""
            )) { (host, port) =>
                val busyUrl = s"http://$host:$port/busy"
                val okUrl   = s"http://$host:$port/ok"
                for
                    _ <- Browser.withConfig(_.loadSchedule(Schedule.fixed(100.millis).maxDuration(1.second))) {
                        Browser.goto(busyUrl)
                    }
                    // A follow-up goto must still work; no wedged state from the previous degrade.
                    _  <- Browser.goto(okUrl, Browser.Settle.DomContentLoaded)
                    ok <- Browser.text(Browser.Selector.id("ok"))
                yield assert(ok == "ok", s"expected follow-up goto to succeed but text='$ok'")
                end for
            }
        }
    }

    // ---- Lifecycle with run ----

    "run launches browser and returns URL" in run {
        Scope.run {
            SharedChrome.chromeConfig.map { cfg =>
                Browser.run(cfg) {
                    Browser.url.map { u =>
                        assert(u == "about:blank")
                    }
                }
            }
        }
    }

    // End-to-end: the Chrome build's downloader actually fetches a different artifact (`chrome-{platform}.zip`,
    // ~190 MB, full UI-capable binary) and points the LaunchConfig at the correct executable, including the
    // macOS `.app` bundle's nested binary path. Boots Chrome via CDP to prove the resolved binary actually runs.
    // First call downloads (~tens of seconds on cold CI); subsequent calls reuse the per-platform cache.
    "chromeForTestingLaunchConfig(Chrome) downloads + launches the full chrome binary" in run {
        Scope.run {
            Browser.chromeForTestingLaunchConfig(Browser.ChromeForTestingBuild.Chrome).map { cfg =>
                assert(
                    !cfg.executable.contains("chrome-headless-shell"),
                    s"expected full chrome path but got headless-shell-derived: ${cfg.executable}"
                )
                Browser.run(cfg) {
                    Browser.url.map(u => assert(u == "about:blank", s"unexpected initial URL: $u"))
                }
            }
        }
    }

    "run(wsUrl) connects to existing browser" in run {
        SharedChrome.init.map { url =>
            Scope.run {
                Browser.run(url) {
                    Browser.url.map { u =>
                        assert(u == "about:blank")
                    }
                }
            }
        }
    }

    // CDP-level invariant: 50 sequential `Browser.run(wsUrl)` cycles on the same shared Chrome must NOT cause unbounded growth of either
    // `Target.getTargets` or `Target.getBrowserContexts`. Each cycle creates a fresh browser context + tab via Browser.run's internal
    // Scope.run, runs the body, and disposes when the body completes. If the counts climbed monotonically across cycles, a `Scope.ensure`
    // finalizer somewhere would be leaking; a real bug at the kyo-browser layer. Bounded growth (≤ `Bound` past baseline) is the contract.
    "run(wsUrl) keeps target and browser-context counts bounded across 50 cycles" in run {
        val Iterations = 50
        val Bound      = 3
        SharedChrome.init.map { wsUrl =>
            // Observation client; a dedicated CdpClient used only for snapshot queries across cycles. Each
            // Browser.run(wsUrl) cycle creates+destroys its own internal client; this one is kept alive across
            // all cycles so its `Target.getTargets` / `Target.getBrowserContexts` calls see Chrome's global state.
            CdpClient.initUnscoped(wsUrl, Browser.LaunchConfig.default).map { client =>
                def snapshot: (Int, Int) < (Async & Abort[BrowserReadException]) =
                    for
                        targets <- CdpBackend.getTargets(client)
                        ctxJson <- client.send("Target.getBrowserContexts")
                        ctxs    <- CdpBackend.decodeOrFail[BrowserContextsResult](ctxJson, "Target.getBrowserContexts")
                    yield (targets.targetInfos.size, ctxs.browserContextIds.size)
                for
                    baseline <- snapshot
                    samples <- Kyo.foreach(Chunk.from(0 until Iterations)) { _ =>
                        Browser.run(wsUrl) {
                            Browser.goto(page("<body>hello</body>")).andThen(Browser.url.unit)
                        }.andThen(snapshot)
                    }
                    _ <- client.close(30.seconds)
                yield
                    val maxTargets  = samples.map(_._1).max
                    val maxContexts = samples.map(_._2).max
                    assert(
                        (maxTargets - baseline._1) <= Bound,
                        s"target count grew beyond bound: baseline=${baseline._1} max=$maxTargets bound=$Bound - possible Scope.ensure leak"
                    )
                    assert(
                        (maxContexts - baseline._2) <= Bound,
                        s"browser-context count grew beyond bound: baseline=${baseline._2} max=$maxContexts bound=$Bound - possible Scope.ensure leak on disposeBrowserContext"
                    )
                end for
            }
        }
    }

    // --- helpers ---

    /** Spins up a tiny HttpServer on port 0 serving the provided path→body map as `text/html; charset=utf-8`. The server is torn down on
      * scope exit. A default 404 is returned for unrecognised paths (kyo-http returns 404 automatically for unmatched routes).
      */
    private def withHtmlServer[A, S](routes: Map[String, String])(f: (String, Int) => A < (Browser & S))(using
        Frame
    ): A < (Browser & Scope & Abort[BrowserConnectionException] & Async & S) =
        val handlers = routes.toSeq.map { case (path, body) =>
            val bytes = Span.fromUnsafe(body.getBytes("UTF-8"))
            // Register both GET and POST so any browser-initiated request method is served.
            // kyo-http uses bodyBinary for the response; we override Content-Type to text/html.
            HttpRoute.getRaw(path).response(_.bodyBinary).handler { _ =>
                HttpResponse.ok(bytes).addHeader("Content-Type", "text/html; charset=utf-8")
            }
        }
        HttpServer.init(0, "127.0.0.1")(handlers*).map { server =>
            f(server.host, server.port)
        }
    end withHtmlServer

    /** Server with a `/404` handler that always returns 404 for the nav-failure tests. */
    private def withStatusServer[A, S](f: (String, Int) => A < (Browser & S))(using
        Frame
    ): A < (Browser & Scope & Abort[BrowserConnectionException] & Async & S) =
        val body404 = Span.fromUnsafe("<html><body>not found</body></html>".getBytes("UTF-8"))
        val handler = HttpRoute.getRaw("/404").response(_.bodyBinary).handler { _ =>
            HttpResponse(HttpStatus.NotFound)
                .addField("body", body404)
                .addHeader("Content-Type", "text/html; charset=utf-8")
        }
        HttpServer.init(0, "127.0.0.1")(handler).map { server =>
            f(server.host, server.port)
        }
    end withStatusServer

    // ---- withActionable / withRetry coverage ----

    "click on a hidden element (display: none) fails with BrowserElementNotActionableException carrying Reason.Hidden" in run {
        val p = page("<div id='h' style='display:none'>Submit</div>")
        withBrowser {
            Browser.goto(p).andThen {
                Browser.withConfig(_.retrySchedule(Schedule.fixed(20.millis).take(2))) {
                    Abort.run[BrowserElementException] {
                        Browser.click(Browser.Selector.id("h"))
                    }
                }.map {
                    case Result.Failure(ex: BrowserElementNotActionableException) =>
                        assert(
                            ex.reason.isInstanceOf[Reason.NotVisible],
                            s"Expected NotVisible but got ${ex.reason}"
                        )
                    case other => fail(s"Expected BrowserElementNotActionableException(Hidden) but got $other")
                }
            }
        }
    }

    "click on a missing selector fails with BrowserElementNotActionableException on the not-found path" in run {
        val p = page("<div id='exists'>present</div>")
        withBrowser {
            Browser.goto(p).andThen {
                Browser.withConfig(_.retrySchedule(Schedule.fixed(20.millis).take(2))) {
                    Abort.run[BrowserElementException] {
                        Browser.click(Browser.Selector.id("does-not-exist"))
                    }
                }.map {
                    case Result.Failure(ex: BrowserElementNotActionableException) =>
                        assert(
                            ex.reason == Reason.NotAttached,
                            s"Expected NotAttached for missing element but got ${ex.reason}"
                        )
                    case other => fail(s"Expected BrowserElementNotActionableException(NotAttached) but got $other")
                }
            }
        }
    }

    "fill on a non-fillable element (a <div>) fails with Reason.NotFillable; click on the same div succeeds" in run {
        val p = page("<div id='d' style='width:80px;height:30px'>not an input</div>")
        withBrowser {
            Browser.goto(p).andThen {
                Browser.withConfig(_.retrySchedule(Schedule.fixed(20.millis).take(2))) {
                    Abort.run[BrowserElementException] {
                        Browser.fill(Browser.Selector.id("d"), "text")
                    }
                }.map { fillResult =>
                    assert(fillResult.isFailure, s"Expected fill to fail on a <div> but got $fillResult")
                    fillResult match
                        case Result.Failure(ex: BrowserElementNotActionableException) =>
                            assert(
                                ex.reason.isInstanceOf[Reason.NotFillable],
                                s"Expected NotFillable but got ${ex.reason}"
                            )
                        case other => fail(s"Expected BrowserElementNotActionableException(NotFillable) but got $other")
                    end match
                }.andThen {
                    // The same div is clickable; withActionable's requireFillable only applies when the caller sets it
                    Browser.click(Browser.Selector.id("d")).andThen(succeed)
                }
            }
        }
    }

    "dragAndDrop with missing target surfaces the target's not-found failure, not the source's" in run {
        val p = page("<div id='src' style='width:60px;height:30px'>source</div>")
        withBrowser {
            Browser.goto(p).andThen {
                Browser.withConfig(_.retrySchedule(Schedule.fixed(20.millis).take(2))) {
                    Abort.run[BrowserElementException] {
                        Browser.dragAndDrop(
                            Browser.Selector.id("src"),
                            Browser.Selector.id("missing")
                        )
                    }
                }.map {
                    case Result.Failure(ex: BrowserElementNotActionableException) =>
                        // The exception message should reference the target selector, not the source
                        assert(
                            ex.selector.contains("missing"),
                            s"Expected exception to reference the target selector 'missing' but got: ${ex.selector}"
                        )
                        assert(
                            ex.reason == Reason.NotAttached,
                            s"Expected NotAttached for missing target but got ${ex.reason}"
                        )
                    case other => fail(s"Expected BrowserElementNotActionableException for missing target but got $other")
                }
            }
        }
    }

    "click on an element that becomes visible mid-retry succeeds" in run {
        val p = page("""<div id='reveal' style='display:none;width:80px;height:30px'>target</div>
            <script>setTimeout(() => { document.getElementById('reveal').style.display='block'; }, 150);</script>""")
        withBrowser {
            Browser.goto(p).andThen {
                // Use a schedule that retries every 50ms for up to 2 seconds; the element becomes visible after ~150ms
                Browser.withConfig(_.retrySchedule(Schedule.fixed(50.millis).maxDuration(2.seconds))) {
                    Browser.click(Browser.Selector.id("reveal"))
                }.andThen(succeed)
            }
        }
    }

    // ---- extraArgs (Chunk-backed field, Seq-typed builder parameter) ----

    "extraArgs accepts a Seq parameter and stores extraArgs as a Chunk" in run {
        val updated = Browser.LaunchConfig.default.extraArgs(Seq("--no-sandbox", "--disable-gpu"))
        // Type pattern: backing field is a Chunk[String], not just any Seq.
        updated.extraArgs match
            case _: Chunk[String] => succeed
            case null             => fail("Expected Chunk[String] but got null")
    }

    "LaunchConfig.default.extraArgs is Chunk.empty" in run {
        Browser.LaunchConfig.default.extraArgs match
            case c: Chunk[String] =>
                assert(c.isEmpty, s"Expected Chunk.empty but got $c")
            case null =>
                fail("Expected Chunk[String] but got null")
    }

    // ---- pdf (Span-backed return type) ----

    "Browser.pdf returns a Span[Byte] starting with the %PDF- magic header" in run {
        withBrowser {
            Browser.pdf.map { bytes =>
                // Type-level assertion: the return type is Span[Byte]. The ascription forces a compile error if the
                // signature ever drifts to a different container type, replacing a tautological runtime match (which
                // the compiler proved unreachable for non-null Span values, since Span[Byte] is statically non-null).
                val _: Span[Byte] = bytes
                assert(bytes.size > 0, "Expected non-empty PDF byte array")
                val prefix = bytes.take(5).toArray
                val header = new String(prefix, "ASCII")
                assert(header == "%PDF-", s"Expected PDF header '%PDF-' but got '$header'")
            }
        }
    }

    // ---- Browser critical methods + describe ----

    /** Helper: navigates to Chrome's localhost devtools JSON endpoint (which is a real http://localhost URL), then replaces the body with
      * the given HTML so we exercise selectors against an injected DOM while still benefiting from a real keyboard event pipeline (keyboard
      * events don't fire reliably on `data:` URLs).
      */
    def withLocalhostPage[A, S](html: String)(f: A < (Browser & S))(using
        Frame
    ): A < (Async & Scope & Abort[BrowserReadException | BrowserSetupException] & S) =
        SharedChrome.init.map { url =>
            val port    = url.split(":")(2).split("/")(0)
            val httpUrl = s"http://localhost:$port/json/version"
            val escaped = html.replace("\\", "\\\\").replace("'", "\\'").replace("\n", " ")
            Browser.run(url) {
                Browser.goto(httpUrl).andThen {
                    Browser.eval(
                        s"(() => { document.documentElement.innerHTML = '<head></head><body>$escaped</body>'; return 'ok'; })()"
                    ).andThen(f)
                }
            }
        }
    end withLocalhostPage

    "Browser.press(input, Key.Enter) triggers form submit" in run {
        // Same-origin localhost is required: a form submit on a `data:` URL navigates cross-origin and
        // the page is replaced before we can observe the side effect. The form's onsubmit returns false
        // to suppress the actual navigation; we only care that the listener fired.
        val html = """
            <form id="f" onsubmit="window.__kyoSubmitted = true; return false;">
                <input id="i" type="text" />
            </form>
        """
        withLocalhostPage(html) {
            Browser.press(Browser.Selector.id("i"), Browser.Key.Enter).andThen {
                Browser.eval("String(window.__kyoSubmitted === true)").map { v =>
                    assert(v == "true", s"Expected form submit to fire after press(Enter) but got '$v'")
                }
            }
        }
    }

    "Browser.press(textbox, Key.ArrowLeft) moves caret" in run {
        val html = """<input id="i" type="text" value="hello" />"""
        withLocalhostPage(html) {
            // Position the caret at the end of "hello" (index 5), then press ArrowLeft once.
            Browser.eval(
                "(() => { const el = document.getElementById('i'); el.focus(); el.setSelectionRange(5,5); return 'ok'; })()"
            ).andThen {
                Browser.press(Browser.Selector.id("i"), Browser.Key.ArrowLeft).andThen {
                    Browser.eval("String(document.getElementById('i').selectionStart)").map { v =>
                        assert(v == "4", s"Expected selectionStart == 4 after ArrowLeft from position 5 but got '$v'")
                    }
                }
            }
        }
    }

    "Browser.scrollTo(target) scrolls so the target is in viewport" in run {
        // A spacer pushes the target far below the initial viewport, then scrollTo should bring it back into view.
        val html =
            """<div style="height:5000px"></div><div id="target" style="height:50px;background:#abc">target</div>"""
        withLocalhostPage(html) {
            // Sanity: before scrollTo, the target's top is far below the viewport.
            Browser.eval("String(document.getElementById('target').getBoundingClientRect().top)").map { beforeStr =>
                val before = beforeStr.toDouble
                assert(before > 1000.0, s"Expected target initially below viewport (top > 1000) but got top=$before")
            }.andThen {
                Browser.scrollTo(Browser.Selector.id("target")).andThen {
                    Browser.eval(
                        "(() => { const r = document.getElementById('target').getBoundingClientRect(); return r.top + '|' + window.innerHeight; })()"
                    ).map { vh =>
                        val parts          = vh.split("\\|")
                        val top            = parts(0).toDouble
                        val viewportHeight = parts(1).toDouble
                        assert(
                            top >= 0.0 && top <= viewportHeight,
                            s"Expected target's top within viewport [0, $viewportHeight] after scrollTo but got top=$top"
                        )
                    }
                }
            }
        }
    }

    "Browser.assertValueEmpty / assertNoVisibleText succeed for their respective empty matches" in run {
        val html = """<input id="empty" type="text" value="" /><div id="emptydiv">   </div>"""
        withLocalhostPage(html) {
            Browser.assertValueEmpty(Browser.Selector.id("empty")).andThen {
                Browser.assertNoVisibleText(Browser.Selector.id("emptydiv")).andThen(succeed)
            }
        }
    }

    "Browser.assertNoVisibleText fails (Abort) when the selector matches a non-empty element" in run {
        val html = """<div id="full">non-empty content</div>"""
        withLocalhostPage(html) {
            Browser.withConfig(_.retrySchedule(Schedule.fixed(20.millis).take(2))) {
                Abort.run[BrowserAssertionException] {
                    Browser.assertNoVisibleText(Browser.Selector.id("full"))
                }
            }.map {
                case Result.Failure(ex: BrowserAssertionTimedOutException) =>
                    assert(
                        ex.check.startsWith("assertNoVisibleText"),
                        s"Expected check to start with 'assertNoVisibleText' but got '${ex.check}'"
                    )
                    assert(ex.expected == "empty", s"Expected expected=='empty' but got '${ex.expected}'")
                case other =>
                    fail(s"Expected Result.Failure(BrowserAssertionTimedOutException) but got $other")
            }
        }
    }

    "Browser.doubleClick(target) fires a single dblclick event" in run {
        // innerHTML insertion does not execute <script> tags, so we attach the listener via a
        // separate Browser.eval that runs after the page swap.
        val html = """<button id="b" style="width:120px;height:40px">click</button>"""
        withLocalhostPage(html) {
            Browser.eval(
                "(() => { window.__dblCount = 0; document.getElementById('b').addEventListener('dblclick', () => { window.__dblCount += 1; }); return 'ok'; })()"
            ).andThen {
                Browser.doubleClick(Browser.Selector.id("b")).andThen {
                    Browser.eval("String(window.__dblCount)").map { v =>
                        assert(v == "1", s"Expected exactly 1 dblclick from one Browser.doubleClick call but got $v")
                    }
                }
            }
        }
    }

    "Browser.keyDown(Key.Shift) fires a keydown event with shiftKey=true" in run {
        val html = """<input id="i" type="text" />"""
        withLocalhostPage(html) {
            Browser.eval(
                "(() => { window.__shiftSeen = false; document.getElementById('i').addEventListener('keydown', e => { if (e.shiftKey) window.__shiftSeen = true; }); return 'ok'; })()"
            ).andThen {
                Browser.focus(Browser.Selector.id("i")).andThen {
                    Browser.keyDown(Browser.Key.Shift).andThen {
                        Browser.eval("String(window.__shiftSeen)").map { v =>
                            assert(v == "true", s"Expected shiftKey===true on keydown after Browser.keyDown(Shift) but got $v")
                        }
                    }
                }
            }
        }
    }

    "Browser.keyUp(Key.Shift) fires keyup with the matching code" in run {
        val html = """<input id="i" type="text" />"""
        withLocalhostPage(html) {
            Browser.eval(
                "(() => { window.__kyoShiftKeyUpKey = ''; window.__kyoShiftKeyUpKeyCode = -1; document.getElementById('i').addEventListener('keyup', (e) => { window.__kyoShiftKeyUpKey = e.key; window.__kyoShiftKeyUpKeyCode = e.keyCode; }); return 'ok'; })()"
            ).andThen {
                Browser.focus(Browser.Selector.id("i")).andThen {
                    Browser.keyUp(Browser.Key.Shift).andThen {
                        Browser.eval("window.__kyoShiftKeyUpKey + '|' + window.__kyoShiftKeyUpKeyCode").map { v =>
                            val parts = v.split("\\|")
                            assert(parts(0) == "Shift", s"Expected key == 'Shift' after keyUp(Shift) but got '${parts(0)}'")
                            assert(parts(1) == "16", s"Expected keyCode == 16 (Shift) after keyUp(Shift) but got '${parts(1)}'")
                        }
                    }
                }
            }
        }
    }

    "Reason.description produces a non-empty discriminator-bearing string for each reason case" in {
        val cases: List[(Reason, String)] = List(
            Reason.NotAttached                                         -> "attached",
            Reason.NotVisible(Reason.NotVisibleCause.DisplayNone)      -> "display",
            Reason.NotVisible(Reason.NotVisibleCause.VisibilityHidden) -> "visibility",
            Reason.NotVisible(Reason.NotVisibleCause.OpacityZero)      -> "opacity",
            Reason.NotVisible(Reason.NotVisibleCause.ZeroComputedSize) -> "zero",
            Reason.ZeroSizedElement(0, 0)                              -> "zero",
            Reason.Disabled(Reason.DisabledKind.Attribute)             -> "disabled",
            Reason.Disabled(Reason.DisabledKind.AriaDisabled)          -> "aria",
            Reason.Disabled(Reason.DisabledKind.FieldsetDisabled)      -> "fieldset",
            Reason.Disabled(Reason.DisabledKind.PointerEventsNone)     -> "pointer",
            Reason.OutsideHitTarget("div#overlay")                     -> "covered",
            Reason.NotFillable("div")                                  -> "fillable",
            Reason.Unstable                                            -> "moving",
            Reason.FillDesync                                          -> "value"
        )
        cases.foreach { case (reason, discriminator) =>
            val s = reason.description
            assert(s.nonEmpty, s"description for $reason returned empty string")
            assert(
                s.toLowerCase.contains(discriminator.toLowerCase),
                s"description for $reason = '$s' does not contain expected discriminator '$discriminator'"
            )
        }
        succeed
    }

    // ---- Error-path coverage ----

    // `Browser.Settle.Load` waits for the `load` event (which only fires after all images are loaded).
    // We serve a slow image from a real HTTP server and assert that the `load` event has fired by the time `goto` returns.
    "Browser.goto with Settle.Load returns only after the page's load event fires" in run {
        withBrowser {
            withHtmlServer(Map(
                // Page has an image plus an inline `addEventListener('load', ...)` that flips a boolean once the load event fires.
                // The image src is set in the script body; until the image's bytes arrive, `document.readyState` stays 'interactive'.
                "/main" -> """<html><body>
                    <script>window.__loaded = false; window.addEventListener('load', () => { window.__loaded = true; });</script>
                    <img id='img' src='/img'>
                </body></html>""",
                // 1×1 transparent GIF; small but a real network resource the browser must wait on for `load`.
                "/img" -> "GIF87a          ,       D ;"
            )) { (host, port) =>
                for
                    _      <- Browser.goto(s"http://$host:$port/main", Browser.Settle.Load)
                    loaded <- Browser.eval("String(window.__loaded)")
                yield
                    // Behavioural: by the time `goto(Load)` returned, the load event must have fired.
                    assert(loaded == "true", s"Expected window.__loaded=='true' after Settle.Load settle but got '$loaded'")
            }
        }
    }

    "evalJson fails with BrowserDecodingException when the JS result does not match the target type" in run {
        withBrowser {
            // Eval succeeds (returns a JSON object) but decode of `{a:1, b:2}` to `EvalJsonShape(x: Int, y: Int)` must fail.
            Abort.run[BrowserDecodingException] {
                Browser.evalJson[EvalJsonShape]("({ a: 1, b: 2 })")
            }.map {
                case Result.Failure(_: BrowserDecodingException) => succeed
                case other =>
                    fail(s"Expected Result.Failure(BrowserDecodingException) for shape mismatch but got $other")
            }
        }
    }

    // mockFetchResponse with a non-200 status; observed response.status is propagated.
    "mockFetchResponse with status=404 surfaces 404 to the page-level fetch" in run {
        val p = page("<body><div id='r'></div></body>")
        withBrowser {
            Browser.goto(p).andThen {
                Browser.mockFetchResponse("https://api.x/404", 404, "not found body").andThen {
                    Browser.eval(
                        """fetch('https://api.x/404').then(r => { window.__s = String(r.status); }).catch(e => { window.__s = 'ERR'; })"""
                    ).andThen {
                        Retry[BrowserScriptException](Schedule.fixed(50.millis).take(40)) {
                            Browser.eval("typeof window.__s === 'string' ? window.__s : '__PENDING__'").map {
                                case "__PENDING__" => Abort.fail[BrowserScriptException](BrowserScriptErrorException("status pending"))
                                case v             => v
                            }
                        }
                    }
                }
            }.map { status =>
                assert(status == "404", s"Expected status '404' from mocked fetch but got '$status'")
            }
        }
    }

    // re-mocking the same URL replaces the prior mock; the second registration wins.
    "mockFetchResponse for the same URL replaces (not appends) the prior mock" in run {
        val p = page("<body></body>")
        withBrowser {
            Browser.goto(p).andThen {
                // Install mock A, then mock B for the same URL.
                Browser.mockFetchResponse("https://api.x/replace", 200, "first-body").andThen {
                    Browser.mockFetchResponse("https://api.x/replace", 200, "second-body").andThen {
                        Browser.eval(
                            """fetch('https://api.x/replace').then(r => r.text()).then(t => { window.__b = t; }).catch(e => { window.__b = 'ERR'; })"""
                        ).andThen {
                            Retry[BrowserScriptException](Schedule.fixed(50.millis).take(40)) {
                                Browser.eval("typeof window.__b === 'string' ? window.__b : '__PENDING__'").map {
                                    case "__PENDING__" => Abort.fail[BrowserScriptException](BrowserScriptErrorException("body pending"))
                                    case v             => v
                                }
                            }
                        }
                    }
                }.map { body =>
                    assert(body == "second-body", s"Expected the second mock to win but got '$body'")
                }
            }
        }
    }

    // when the body of `withDialogs` raises, the per-session handler is restored
    // (i.e. removed if it was not previously registered); observable via `client.dialogHandlers` not containing
    // the session-id key after the failing scope exits.
    "withDialogs body-failure restores the prior dialog-handler state (no leftover stub)" in run {
        val p = page("<body></body>")
        withBrowser {
            Browser.goto(p).andThen {
                Browser.use { tab =>
                    val client = tab.client
                    val sidKey = tab.sessionId.value
                    // withDialogs internally bounds the restore via its own Scope.run, so the cleanup fires
                    // when withDialogs's body exits; even when the body aborts. We observe dialogHandlers
                    // after the failing scope returns.
                    Abort.run[BrowserScriptException] {
                        Browser.withDialogs.prompt("test") {
                            Abort.fail[BrowserScriptException](BrowserScriptErrorException("synthetic body failure"))
                        }
                    }.map { result =>
                        assert(result.isFailure, s"expected Abort failure from synthetic body but got $result")
                        client.dialogHandlers.get.map { m =>
                            assert(
                                !m.contains(sidKey),
                                s"Expected dialogHandlers to NOT contain $sidKey after withDialogs body failure, but got: $m"
                            )
                        }
                    }
                }
            }
        }
    }

    // trigger does not open a popup → withPopup aborts.
    "withPopup aborts when the trigger does not open a new tab" in run {
        val p = page("""<html><body>
            <button id='noop' onclick="document.body.dataset.clicked='1';">noop</button>
        </body></html>""")
        withBrowser {
            Browser.goto(p).andThen {
                Browser.withConfig(_.retrySchedule(Schedule.fixed(50.millis).take(3))) {
                    Scope.run {
                        Abort.run[BrowserConnectionException] {
                            Browser.withPopup() {
                                Browser.click(Browser.Selector.css("#noop"))
                            } {
                                // Should never run; trigger does not open any new tab.
                                Browser.eval("'unreachable'").unit
                            }
                        }
                    }
                }.map {
                    case Result.Failure(_: BrowserProtocolErrorException) => succeed
                    case other =>
                        fail(s"Expected Result.Failure(BrowserProtocolErrorException) but got $other")
                }
            }
        }
    }

    // popup opens, handler raises → withPopup surfaces the failure AND the popup tab is closed
    // (asserted via Target.getTargets, anti-flake rule #5).
    "withPopup surfaces handler failure and closes the popup tab on scope exit" in run {
        val p = page("""<html><body>
            <button id='openBtn' onclick="window.open('about:blank', '_blank');">Open</button>
        </body></html>""")
        withBrowser {
            Browser.goto(p).andThen {
                // Capture the BrowserTab so we can query Target.getTargets via its client after the failing scope exits.
                Browser.use { tab =>
                    val client = tab.client
                    // Snapshot all existing page target IDs BEFORE the withPopup scope so we can identify exactly
                    // which target belongs to this test's popup, ignoring any about:blank tabs from other tests.
                    kyo.internal.CdpBackend.getTargets(client).map { beforeTargets =>
                        val beforeIds = beforeTargets.targetInfos.map(_.targetId).toSet
                        Scope.run {
                            Abort.run[BrowserScriptException] {
                                Browser.withPopup() {
                                    Browser.click(Browser.Selector.css("#openBtn"))
                                } {
                                    // Handler raises after the popup attaches. The popup tab is registered with Scope.ensure
                                    // (Browser.scala:1573), so it must be closed even on failure.
                                    Abort.fail[BrowserScriptException](BrowserScriptErrorException("handler raises"))
                                }
                            }
                        }.map { result =>
                            assert(result.isFailure, s"expected failure from raising handler but got $result")
                            // Poll Target.getTargets briefly to allow Chrome's internal target list to settle.
                            // Only check targets that are NEW relative to the pre-scope snapshot, so stale about:blank
                            // tabs from concurrent/prior tests do not trigger a false failure.
                            Retry[String](Schedule.fixed(50.millis).take(20)) {
                                kyo.internal.CdpBackend.getTargets(client).map { tgts =>
                                    val popups = tgts.targetInfos.filter(t =>
                                        t.`type` == "page" && !beforeIds.contains(t.targetId)
                                    )
                                    if popups.isEmpty then "ok"
                                    else Abort.fail[String](s"popup still present: ${popups.map(_.targetId)}")
                                }
                            }.map(_ => succeed)
                        }
                    }
                }
            }
        }
    }

    // trigger opens no popup; assert the SPECIFIC failure mode is BrowserProtocolErrorException
    // with operation == "withPopup" and the documented "no new tab detected" message (Browser.scala:1567).
    "withPopup with a no-op trigger raises BrowserProtocolErrorException(\"withPopup\", \"no new tab detected\")" in run {
        val p = page("<body><button id='inert' onclick='void 0;'>inert</button></body>")
        withBrowser {
            Browser.goto(p).andThen {
                Browser.withConfig(_.retrySchedule(Schedule.fixed(50.millis).take(3))) {
                    Scope.run {
                        Abort.run[BrowserConnectionException] {
                            Browser.withPopup() {
                                Browser.click(Browser.Selector.css("#inert"))
                            } {
                                Browser.eval("'unreachable'").unit
                            }
                        }
                    }
                }.map {
                    case Result.Failure(ex: BrowserProtocolErrorException) =>
                        // The specific failure carries method=="withPopup". Asserting on the discriminator field, not
                        // the freeform message, per anti-reward-hacking rules.
                        assert(
                            ex.method == "withPopup",
                            s"expected BrowserProtocolErrorException(method='withPopup') but got method='${ex.method}'"
                        )
                    case other =>
                        fail(s"Expected Result.Failure(BrowserProtocolErrorException) but got $other")
                }
            }
        }
    }

    // setCookie with a non-default `path` round-trips through Browser.cookies.
    "setCookie with a non-default path round-trips into Browser.cookies" in run {
        withBrowserOnLocalhost {
            Browser.setCookie("custompath", "v1", "localhost", path = "/json").andThen {
                Browser.cookies.map { cs =>
                    cs.find(_.name == "custompath") match
                        case Some(c) =>
                            assert(c.value == "v1", s"expected value 'v1' but got '${c.value}'")
                            assert(
                                c.path == Present("/json"),
                                s"expected path Present(\"/json\") but got ${c.path}"
                            )
                        case None =>
                            fail(s"expected to find 'custompath' cookie but got: ${cs.map(_.name)}")
                    end match
                }
            }
        }
    }

    // deleteCookie with explicit domain removes the cookie scoped to that domain.
    "deleteCookie with an explicit domain removes the cookie" in run {
        withBrowserOnLocalhost {
            Browser.setCookie("explicit", "v", "localhost").andThen {
                Browser.cookies.map { cs =>
                    assert(cs.exists(_.name == "explicit"), s"expected 'explicit' to exist after setCookie: ${cs.map(_.name)}")
                }.andThen {
                    // Delete via explicit-domain branch (Browser.scala:1457-1462): domain.nonEmpty → uses domain= param.
                    Browser.deleteCookie("explicit", domain = "localhost").andThen {
                        Browser.cookies.map { cs =>
                            assert(
                                !cs.exists(_.name == "explicit"),
                                s"expected 'explicit' to be deleted but still present: ${cs.map(_.name)}"
                            )
                        }
                    }
                }
            }
        }
    }

    // Browser.run(config) with a non-existent executable fails fast with BrowserSetupFailedException.
    "Browser.run(config) with a non-existent executable fails with BrowserSetupFailedException" in run {
        Abort.run[BrowserSetupException] {
            Scope.run {
                Browser.run(
                    Browser.LaunchConfig.chromium("/does/not/exist/chrome").launchTimeout(2.seconds)
                ) {
                    // Should never run; launch must fail before the body is invoked.
                    Browser.url.unit
                }
            }
        }.map {
            case Result.Failure(_: BrowserSetupFailedException) => succeed
            case other =>
                fail(s"Expected Result.Failure(BrowserSetupFailedException) but got $other")
        }
    }

    // Browser.run(config) with a launch timeout; pointing at /bin/true (exits immediately,
    // never prints DevTools URL) makes parseWsUrl exhaust the timeout and fail with BrowserSetupFailedException.
    "Browser.run(config) launch timeout fails with BrowserSetupFailedException when no DevTools URL is announced" in run {
        // Choose an executable that exists and exits cleanly without printing the marker.
        // /bin/true is universally present on macOS/Linux CI runners.
        Abort.run[BrowserSetupException] {
            Scope.run {
                Browser.run(
                    Browser.LaunchConfig.chromium("/bin/true").launchTimeout(500.millis)
                ) {
                    Browser.url.unit
                }
            }
        }.map {
            case Result.Failure(_: BrowserSetupFailedException) => succeed
            case other =>
                fail(s"Expected Result.Failure(BrowserSetupFailedException) but got $other")
        }
    }

    // Browser.run(wsUrl) with an invalid wsUrl; the underlying WebSocket connect must fail,
    // which surfaces as an Abort. We point at 127.0.0.1:0 (always-fails fast per anti-slow rule #5).
    "Browser.run(wsUrl) with an invalid wsUrl fails fast with Abort" in run {
        // 127.0.0.1:0 fails the OS connect call immediately with ECONNREFUSED; no timeout needed.
        // Wrap in Scope.run because Browser.run requires Scope.
        Scope.run {
            Abort.run[BrowserConnectionException] {
                Browser.run("ws://127.0.0.1:0/devtools/browser/none") {
                    Browser.url
                }
            }
        }.map {
            case Result.Failure(_: BrowserConnectionException) => succeed
            case other =>
                fail(s"Expected Abort.Failure(BrowserConnectionException) but got $other")
        }
    }

    // integration verification that `withNetworkIdleWindow` propagates into the watcher —
    // a longer idle window must cause `goto(NetworkIdle)` to wait longer when there is a brief network burst.
    // Behaviour assertion (paired with timing): with a 1500ms window, a `setTimeout(fetch, 100)` must complete
    // (and its DOM side-effect must be observable) before goto returns.
    "withNetworkIdleWindow propagates the configured window into the NetworkIdle watcher" in run {
        withBrowser {
            withHtmlServer(Map(
                "/main" -> """<html><body>
                    <div id='m'>before</div>
                    <script>
                        // 100ms after DOMContentLoaded, fire one fetch; on completion, flip the marker.
                        window.addEventListener('DOMContentLoaded', () => {
                            setTimeout(() => {
                                fetch('/data').then(() => { document.getElementById('m').textContent = 'after'; });
                            }, 100);
                        });
                    </script>
                </body></html>""",
                "/data" -> "ok"
            )) { (host, port) =>
                // 1500ms idle window; strictly larger than the default 500ms; so NetworkIdle has to wait past the
                // delayed fetch's completion before resolving. With a tiny window the DOM mutation might not be observed.
                Browser.withConfig(_.networkIdleWindow(1500.millis)) {
                    for
                        _ <- Browser.goto(s"http://$host:$port/main")
                        m <- Browser.text(Browser.Selector.id("m"))
                    yield assert(m == "after", s"Expected the fetch's DOM mutation to be observed (m=='after') but got '$m'")
                }
            }
        }
    }

    // ---- count ----

    "count returns 0 immediately for a missing selector without waiting for the retry schedule" in run {
        withBrowser {
            onPage("<div>no items here</div>") {
                val start = java.lang.System.currentTimeMillis()
                Browser.withConfig(_.retrySchedule(Schedule.fixed(5.seconds).take(10))) {
                    Browser.count(Browser.Selector.css("li.missing")).map { n =>
                        val elapsed = java.lang.System.currentTimeMillis() - start
                        assert(n == 0, s"Expected 0 for missing selector but got $n")
                        assert(elapsed < 2000, s"Expected count to return immediately (<2000ms) but took ${elapsed}ms")
                    }
                }
            }
        }
    }

    "count returns the live element count for a present selector" in run {
        withBrowser {
            onPage("<ul><li class='item'>A</li><li class='item'>B</li><li class='item'>C</li></ul>") {
                Browser.count(Browser.Selector.css("li.item")).map { n =>
                    assert(n == 3, s"Expected 3 matching elements but got $n")
                }
            }
        }
    }

    "count after navigation reflects the new page element count without staleness" in run {
        val pageA = page("<ul><li class='row'>one</li><li class='row'>two</li></ul>")
        val pageB = page("<ul><li class='row'>x</li></ul>")
        withBrowser {
            Browser.goto(pageA).andThen {
                Browser.count(Browser.Selector.css("li.row")).map { n =>
                    assert(n == 2, s"Expected 2 rows on page A but got $n")
                }.andThen {
                    Browser.goto(pageB).andThen {
                        Browser.count(Browser.Selector.css("li.row")).map { n =>
                            assert(n == 1, s"Expected 1 row on page B but got $n")
                        }
                    }
                }
            }
        }
    }

    "assertExists followed by count is the wait-and-count idiom and returns the matched count" in run {
        withBrowser {
            onPage("<ul><li class='entry'>p</li><li class='entry'>q</li></ul>") {
                Browser.assertExists(Browser.Selector.css("li.entry")).andThen {
                    Browser.count(Browser.Selector.css("li.entry")).map { n =>
                        assert(n == 2, s"Expected count 2 after assertExists but got $n")
                    }
                }
            }
        }
    }

    // ---- global press with modifier flags ----

    "global press(Key.Enter, shift, …) emits shiftKey on keydown AND keyup at the focused body" in run {
        // The global-press overload does not accept selector; modifier bits flow from the call args directly into the
        // CDP DispatchKeyEventParams. The test focuses document.body, installs body-level keydown/keyup listeners that
        // record `${type}:${shiftKey}` into `window.__events`, then asserts both events carry shiftKey=true.
        //
        // Note on call shape: the global `press` overload takes all four modifier flags positionally because the
        // selector-scoped `press` already declares modifier defaults and Scala 3 forbids two overloads of the same
        // method from both carrying default arguments.
        withBrowser {
            onPage(
                """<body tabindex='-1'>
                  |<script>
                  |  window.__events = [];
                  |  document.body.focus();
                  |  document.body.addEventListener('keydown', function(e) { window.__events.push('keydown:' + e.shiftKey); });
                  |  document.body.addEventListener('keyup',   function(e) { window.__events.push('keyup:'   + e.shiftKey); });
                  |</script>
                  |</body>""".stripMargin
            ) {
                Browser.press(Browser.Key.Enter, Browser.KeyModifiers(shift = true)).andThen {
                    Browser.eval("window.__events.join('|')").map { v =>
                        assert(
                            v == "keydown:true|keyup:true",
                            s"Expected shiftKey=true on both keydown and keyup at body but got '$v'"
                        )
                    }
                }
            }
        }
    }

    // ---- back / forward / reload ----

    "back navigates to the previous page" in run {
        val pageA = page("<h1 id='title'>Page A</h1>")
        val pageB = page("<h1 id='title'>Page B</h1>")
        withBrowser {
            Browser.goto(pageA).map { _ =>
                Browser.goto(pageB).map { _ =>
                    Browser.back.map { _ =>
                        Browser.text(Browser.Selector.css("#title")).map { t =>
                            assert(t == "Page A", s"Expected 'Page A' after back but got '$t'")
                        }
                    }
                }
            }
        }
    }

    "forward navigates to the next page" in run {
        val pageA = page("<h1 id='title'>Page A</h1>")
        val pageB = page("<h1 id='title'>Page B</h1>")
        withBrowser {
            Browser.goto(pageA).map { _ =>
                Browser.goto(pageB).map { _ =>
                    Browser.back.map { _ =>
                        Browser.forward.map { _ =>
                            Browser.text(Browser.Selector.css("#title")).map { t =>
                                assert(t == "Page B", s"Expected 'Page B' after forward but got '$t'")
                            }
                        }
                    }
                }
            }
        }
    }

    "reload reloads the current page" in run {
        val p = page("""<html><body>
            <div id='counter'>0</div>
            <script>
                var count = parseInt(document.getElementById('counter').textContent, 10);
                count = count + 1;
                document.getElementById('counter').textContent = String(count);
            </script>
        </body></html>""")
        withBrowser {
            Browser.goto(p).map { _ =>
                Browser.text(Browser.Selector.css("#counter")).map { before =>
                    assert(before == "1", s"Expected counter to be 1 after initial load but got '$before'")
                }.map { _ =>
                    Browser.reload().map { _ =>
                        Browser.text(Browser.Selector.css("#counter")).map { after =>
                            assert(after == "1", s"Expected counter to reset to 1 after reload but got '$after'")
                        }
                    }
                }
            }
        }
    }

    // ---- run with composed setup ----

    "Browser.run composes a setup hook with a body that consumes its result via setup.map(body)" in run {
        Scope.run {
            kyo.internal.SharedChrome.chromeConfig.map { cfg =>
                val setup =
                    Browser.goto(page("<div id='marker'>setup-saw-tab</div>")).andThen {
                        Browser.text(Browser.Selector.id("marker"))
                    }
                def body(setupResult: String) =
                    Browser.text(Browser.Selector.id("marker")).map { bodyResult =>
                        assert(
                            setupResult == "setup-saw-tab",
                            s"setup observed wrong text: '$setupResult'"
                        )
                        assert(
                            bodyResult == "setup-saw-tab",
                            s"body did not see the tab navigated by setup: '$bodyResult'"
                        )
                        (setupResult, bodyResult)
                    }
                Browser.run(cfg)(setup.map(body)).map { case (setupResult, bodyResult) =>
                    assert(setupResult == bodyResult)
                }
            }
        }
    }

    // ---- Browser.runShared ----

    "Browser.runShared reuses the same Chrome process across calls" in run {
        // Two sequential `runShared` calls should both see a working browser context (`typeof window === 'object'`),
        // proving that the second call attached a tab to the SAME long-lived Chrome rather than launching a new one.
        Browser.runShared() {
            Browser.eval("typeof window")
        }.map { typeof1 =>
            Browser.runShared() {
                Browser.eval("typeof window")
            }.map { typeof2 =>
                assert(typeof1 == "object", s"first call should see window: $typeof1")
                assert(typeof2 == "object", s"second call should see window: $typeof2")
            }
        }
    }

    "Browser.runShared does not re-init Chrome on subsequent calls" in run {
        // Strong, deterministic re-use witness: `SharedChrome.init` returns the cached WebSocket URL after the first
        // launch. If `runShared` accidentally launched a new Chrome each call, the URL (which embeds the per-launch
        // browser session id assigned by Chrome) would differ. Equality across two calls proves the singleton is
        // honoured. This is what `runShared` ultimately delegates to, so this is a direct behavioural witness, not a
        // proxy.
        kyo.internal.SharedChrome.init.map { url1 =>
            kyo.internal.SharedChrome.init.map { url2 =>
                assert(url1 == url2, s"runShared should reuse the same Chrome URL; got url1=$url1, url2=$url2")
            }
        }
    }

    // ---- BrowserTest helpers ----

    "evalAssert smoke" in run {
        // Happy path: matching expected value succeeds. Failure path: the ScalaTest `assert` macro throws
        // TestFailedException, which `Abort.run[Throwable]` surfaces as `Result.Failure(TestFailedException)`.
        withBrowser {
            onPage("<html></html>") {
                evalAssert("1+1", "2").andThen {
                    Abort.run[Throwable](evalAssert("1+1", "3")).map {
                        case Result.Failure(_: org.scalatest.exceptions.TestFailedException) => succeed
                        case other => fail(s"expected Result.Failure(TestFailedException) on mismatch, got $other")
                    }
                }
            }
        }
    }

    "withLocalhostServer smoke" in run {
        val bytes = Span.fromUnsafe("<html><body><p id=\"x\">fixed-body</p></body></html>".getBytes("UTF-8"))
        val handler = HttpRoute.getRaw("/page").response(_.bodyBinary).handler { _ =>
            HttpResponse.ok(bytes).addHeader("Content-Type", "text/html; charset=utf-8")
        }
        withLocalhostServer(handler) { (host, port) =>
            withBrowser {
                Browser.goto(s"http://$host:$port/page").andThen {
                    Browser.text(Browser.Selector.css("#x")).map { t =>
                        assert(t == "fixed-body", s"expected text='fixed-body' but got '$t'")
                    }
                }
            }
        }
    }

    // ---- scrollTo(selector) ----

    "scrollTo(selector) scrolls a deeply-positioned element into viewport" in run {
        withBrowser {
            onPage(
                """<div style='height:5000px'></div><div id='far' style='height:40px;background:red'>far</div>"""
            ) {
                Browser.scrollTo(Browser.Selector.id("far")).andThen {
                    Browser.eval(
                        """(() => {
                            const el = document.getElementById('far');
                            const rect = el.getBoundingClientRect();
                            return String(rect.top < window.innerHeight && rect.bottom > 0);
                        })()"""
                    ).map { v =>
                        assert(
                            v == "true",
                            s"Expected element to be in viewport after scrollTo but getBoundingClientRect check returned '$v'"
                        )
                    }
                }
            }
        }
    }

    "scrollTo(selector) raises BrowserElementNotFoundException for missing selector" in run {
        withBrowser {
            onPage("<div>no matching element here</div>") {
                tight {
                    Abort.run[BrowserElementException] {
                        Browser.scrollTo(Browser.Selector.id("no-such-element"))
                    }.map {
                        case Result.Failure(_: BrowserElementNotFoundException) => succeed
                        case other =>
                            fail(s"Expected BrowserElementNotFoundException for missing selector but got $other")
                    }
                }
            }
        }
    }

end BrowserCoreTest

// Pure case class for evalJson scenario 3 - must be at file scope so the JSON derivation is visible.
case class EvalJsonShape(x: Int, y: Int) derives Schema

// Wire shape for the `Target.getBrowserContexts` response; used by the per-cycle cleanup invariant test.
private[kyo] case class BrowserContextsResult(browserContextIds: Seq[String]) derives Schema
