package kyo

import kyo.BrowserIFrameInvalidException.Reason

class BrowserIFrameTest extends BrowserTest:

    override def timeout = 60.seconds

    // ─── Page templates ──────────────────────────────────────────────────────────

    /** Parent page hosting a single iframe whose body the test specifies. The `{srcdoc}` placeholder is filled in by [[srcdocPage]]. The
      * sandbox attribute is intentionally omitted so the inline document inherits the parent's origin (the default for `srcdoc`).
      */
    private val singleIframeParent: String =
        """<body>
            <h1 id="parent-h1">parent</h1>
            <iframe id="frame" data-testid="frame" srcdoc="{srcdoc}"></iframe>
        </body>"""

    /** Two-iframe parent with distinct srcdocs. Used by §6.2 list / nesting / scope-restoration scenarios. */
    private def twoIframeParent(innerA: String, innerB: String): String =
        val outer = """<body>
            <h1>parent</h1>
            <iframe id="a" data-testid="a" srcdoc="{srcdoc-a}"></iframe>
            <iframe id="b" data-testid="b" srcdoc="{srcdoc-b}"></iframe>
        </body>"""
        page(
            outer
                .replace("{srcdoc-a}", BrowserTest.htmlAttributeEscape(innerA))
                .replace("{srcdoc-b}", BrowserTest.htmlAttributeEscape(innerB))
        )
    end twoIframeParent

    /** Parent page hosting an iframe whose body itself hosts another iframe. Used by §6.2 #6 (nested traversal). */
    private def nestedIframeParent(innerInnerHtml: String): String =
        val innerOuter = s"""<body>
            <iframe id="inner" srcdoc="${BrowserTest.htmlAttributeEscape(innerInnerHtml)}"></iframe>
        </body>"""
        srcdocPage(singleIframeParent, innerOuter)
    end nestedIframeParent

    private val parentWithoutIframes: String = """<body><h1>only main</h1><p>hello</p></body>"""

    // ─── Helpers ─────────────────────────────────────────────────────────────────

    /** Slow retry schedule for iframe boot - context creation runs after `Page.navigate` returns. */
    private def iframeRetry[A, S](v: A < S)(using Frame): A < S =
        Browser.withConfig(_.retrySchedule(Schedule.fixed(50.millis).take(40)))(v)

    /** Navigates directly to a `data:` URL emitted by [[srcdocPage]] (already a URL - must not re-wrap via `page()`). */
    private def onSrcdoc[A, S](outer: String, srcdoc: String)(body: A < (Browser & Async & S))(using
        Frame
    ): A < (Browser & Async & Abort[BrowserReadException] & S) =
        Browser.goto(srcdocPage(outer, srcdoc)).andThen(body)

    /** Resolves the single test iframe at `data-testid="frame"`. Wraps in a retry-aware [[Browser.assertExists]] first so the inline iframe
      * has time to attach + parse before the resolver pipeline runs.
      */
    private def discoverSingleFrame(using
        Frame
    ): Browser.IFrame < (Browser & Async & Abort[BrowserReadException]) =
        iframeRetry {
            Browser.assertExists(Browser.Selector.testId("frame")).andThen {
                Browser.iframe(Browser.Selector.testId("frame"))
            }
        }

    // ═══════════════════════════════════════════════════════════════════════════
    // §6.1 IFrame discovery
    // ═══════════════════════════════════════════════════════════════════════════

    "iframe.of(iframe selector) returns an IFrame whose handle carries the iframe's frameId and a default executionContextId" in run {
        withBrowser {
            onSrcdoc(singleIframeParent, "<body><span id='inner'>x</span></body>") {
                discoverSingleFrame.map { f =>
                    Browser.mainFrame.map { mainFrame =>
                        // Distinct frameId AND distinct executionContextId from the main frame is the load-bearing
                        // property: it's the only proof that the handle is keyed on the iframe's document, not on
                        // a stale main-frame reference. Equality testing against opaque IFrame works via underlying
                        // case-class derives CanEqual on IFrameHandle.
                        assert(f != mainFrame, s"iframe handle equal to main: $f vs $mainFrame")
                    }
                }
            }
        }
    }

    "BrowserIFrameInvalidException carries Reason.NotAFrame for non-iframe selectors" in run {
        withBrowser {
            onPage("<body><h1 id='heading'>not a frame</h1></body>") {
                Abort.run[BrowserIFrameException] {
                    Browser.iframe(Browser.Selector.id("heading"))
                }.map {
                    case Result.Failure(BrowserIFrameInvalidException(Reason.NotAFrame)) =>
                        succeed
                    case other =>
                        fail(s"expected BrowserIFrameInvalidException(Reason.NotAFrame) but got $other")
                }
            }
        }
    }

    "iframe.of(missing selector) aborts with BrowserElementNotFoundException" in run {
        withBrowser {
            onPage("<body><h1>nothing here</h1></body>") {
                tight {
                    Abort.run[BrowserElementException] {
                        Browser.iframe(Browser.Selector.id("missing"))
                    }
                }.map {
                    case Result.Failure(_: BrowserElementNotFoundException) => succeed
                    case other => fail(s"expected BrowserElementNotFoundException but got $other")
                }
            }
        }
    }

    "iframe.main returns an IFrame for the top-level document of the current tab" in run {
        withBrowser {
            onPage("<body><h1>main only</h1></body>") {
                Browser.mainFrame.map { f =>
                    Browser.withIFrame(f) {
                        // If `f` truly references main's document, scoped Browser.text reads the main heading.
                        Browser.text(Browser.Selector.css("h1")).map { t =>
                            assert(t == "main only", s"expected 'main only' but got '$t'")
                        }
                    }
                }
            }
        }
    }

    "iframe.main aborts with BrowserIFrameInvalidException(ContextNotObserved) when the root execution context has not yet been observed" in run {
        withBrowser {
            onPage("<body><h1>main only</h1></body>") {
                // Drain the root frame's entry from the per-tab `frameContexts` map while leaving
                // `rootFrameId` seeded; this models the narrow window where `attachTab` has issued
                // `Page.getFrameTree` but `Runtime.executionContextCreated` for the root has not yet
                // landed. With the root id known but its context unobserved, `iframe.main` must abort
                // with `Reason.ContextNotObserved`.
                Browser.use { tab =>
                    tab.rootFrameId.get.map {
                        case Present(rid) =>
                            tab.frameContexts.updateAndGet(_.remove(rid)).andThen {
                                Abort.run[BrowserIFrameException] {
                                    Browser.mainFrame
                                }.map {
                                    case Result.Failure(BrowserIFrameInvalidException(Reason.ContextNotObserved)) =>
                                        succeed
                                    case other =>
                                        fail(s"expected BrowserIFrameInvalidException(ContextNotObserved) but got $other")
                                }
                            }
                        case Absent =>
                            fail("precondition: rootFrameId should be seeded by attachTab before this test runs")
                    }
                }
            }
        }
    }

    "iframe.main aborts with BrowserIFrameInvalidException(RootNotSeeded) when the root frame id has not been seeded" in run {
        withBrowser {
            onPage("<body><h1>main only</h1></body>") {
                // Clear `rootFrameId` to model the narrow window before `attachTab`'s initial
                // `Page.getFrameTree` round-trip seeds the root id. With `Absent` in the slot,
                // `iframe.main` must abort with `Reason.RootNotSeeded`.
                Browser.use { tab =>
                    tab.rootFrameId.set(Absent).andThen {
                        Abort.run[BrowserIFrameException] {
                            Browser.mainFrame
                        }.map {
                            case Result.Failure(BrowserIFrameInvalidException(Reason.RootNotSeeded)) =>
                                succeed
                            case other =>
                                fail(s"expected BrowserIFrameInvalidException(RootNotSeeded) but got $other")
                        }
                    }
                }
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // §6.2 IFrame state
    // ═══════════════════════════════════════════════════════════════════════════

    "iframe.list returns main plus every child frame present at call time, in document order" in run {
        withBrowser {
            val pageHtml = twoIframeParent("<body><span>A</span></body>", "<body><span>B</span></body>")
            Browser.goto(pageHtml).andThen {
                iframeRetry {
                    Browser.assertExists(Browser.Selector.testId("a")).andThen {
                        Browser.assertExists(Browser.Selector.testId("b"))
                    }
                }.andThen {
                    // Wait for both iframes' default contexts to land in the per-tab map. Polling iframe.list
                    // until the count reaches 3 is the deterministic "context observed" barrier.
                    Retry[BrowserAssertionException](Schedule.fixed(50.millis).take(80)) {
                        Browser.iframes.map { frames =>
                            if frames.size == 3 then ()
                            else
                                Abort.fail[BrowserAssertionException](
                                    BrowserAssertionTimedOutException("iframe.list size", "3", frames.size.toString)
                                )
                        }
                    }.andThen {
                        Browser.iframes.map { frames =>
                            Browser.mainFrame.map { mainFrame =>
                                assert(frames.size == 3, s"expected main + 2 iframes but got ${frames.size}")
                                assert(frames.head == mainFrame, "expected main frame first in document order")
                                assert(frames(1) != frames(2), "expected the two child iframes to differ")
                                succeed
                            }
                        }
                    }
                }
            }
        }
    }

    "iframe.list traverses nested iframes (iframe inside iframe) and returns both" in run {
        withBrowser {
            Browser.goto(nestedIframeParent("<body><h2>deepest</h2></body>")).andThen {
                Retry[BrowserAssertionException](Schedule.fixed(50.millis).take(80)) {
                    Browser.iframes.map { frames =>
                        // main + outer iframe + inner iframe == 3
                        if frames.size == 3 then ()
                        else
                            Abort.fail[BrowserAssertionException](
                                BrowserAssertionTimedOutException("nested iframe.list size", "3", frames.size.toString)
                            )
                    }
                }.andThen {
                    Browser.iframes.map { frames =>
                        assert(frames.size == 3, s"expected main + 2 nested frames but got ${frames.size}")
                        // Distinctness; three frames, three handles.
                        val distinct = frames.toSet
                        assert(distinct.size == 3, s"expected 3 distinct handles but got ${distinct.size}")
                        succeed
                    }
                }
            }
        }
    }

    "iframe.list returns only main when the page has no iframes" in run {
        withBrowser {
            onPage(parentWithoutIframes) {
                Browser.iframes.map { frames =>
                    Browser.mainFrame.map { mainFrame =>
                        assert(frames.size == 1, s"expected 1 frame but got ${frames.size}")
                        assert(frames.head == mainFrame, "expected single entry to be main")
                        succeed
                    }
                }
            }
        }
    }

    "an iframe added to the DOM after page load is discoverable by iframe.of after a settle barrier" in run {
        withBrowser {
            onPage("""<body>
                <h1>parent</h1>
                <div id="slot"></div>
                <script>
                    setTimeout(function(){
                        var f = document.createElement('iframe');
                        f.setAttribute('data-testid', 'late');
                        f.setAttribute('srcdoc', '<body><span id=\"hi\">late!</span></body>');
                        document.getElementById('slot').appendChild(f);
                    }, 150);
                </script>
            </body>""") {
                iframeRetry {
                    Browser.iframe(Browser.Selector.testId("late")).map { f =>
                        Browser.withIFrame(f) {
                            iframeRetry {
                                Browser.text(Browser.Selector.css("#hi")).map { t =>
                                    assert(t == "late!", s"expected 'late!' but got '$t'")
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // §6.3 IFrame scoping
    // ═══════════════════════════════════════════════════════════════════════════

    "withIFrame(f) { Browser.click(button) } clicks the iframe's button, not a same-named button in main" in run {
        withBrowser {
            // Both parent and iframe carry a button at #b; the iframe is positioned at the top-left of the parent
            // viewport so the iframe's coordinate system aligns with the parent's. This sidesteps the deferred
            // cross-document coordinate-translation work (spec §2.1 non-goal) while still proving that the click is
            // routed by execution context, not by viewport coordinates: the iframe scope click flips the iframe's
            // own window.__clicked, never the main window's same-named flag.
            val inner = """<body style="margin:0;padding:0;">
                <button id="b" style="position:fixed;top:0;left:0;width:100px;height:30px;">SaveInner</button>
                <script>document.getElementById('b').addEventListener('click', function(){window.__clicked='iframe';});</script>
            </body>"""
            onSrcdoc(
                """<body style="margin:0;padding:0;">
                    <iframe id="frame" data-testid="frame" style="position:fixed;top:0;left:0;width:300px;height:200px;border:0;" srcdoc="{srcdoc}"></iframe>
                    <button id="mainBtn" style="position:fixed;top:300px;left:0;">SaveMain</button>
                    <script>document.getElementById('mainBtn').addEventListener('click', function(){window.__clicked='main';});</script>
                </body>""",
                inner
            ) {
                discoverSingleFrame.map { f =>
                    Browser.withIFrame(f) {
                        Browser.click(Browser.Selector.css("#b")).andThen {
                            Browser.eval("window.__clicked").map { v =>
                                assert(v == "iframe", s"expected iframe-scoped click but got '$v'")
                            }
                        }
                    }.andThen {
                        // After exiting withIFrame, evaluating in main shows main's __clicked still unset.
                        Browser.eval("window.__clicked || ''").map { v =>
                            assert(v.isEmpty, s"main __clicked should be empty but got '$v'")
                        }
                    }
                }
            }
        }
    }

    "withIFrame(f) { Browser.fill(input, \"hi\") } populates the iframe's input, not main's" in run {
        withBrowser {
            val inner = """<body><input id="x" type="text" /></body>"""
            onSrcdoc(
                """<body>
                    <input id="x" type="text" />
                    <iframe id="frame" data-testid="frame" srcdoc="{srcdoc}"></iframe>
                </body>""",
                inner
            ) {
                discoverSingleFrame.map { f =>
                    Browser.withIFrame(f) {
                        Browser.fill(Browser.Selector.css("#x"), "hi").andThen {
                            Browser.eval("document.getElementById('x').value").map { v =>
                                assert(v == "hi", s"expected iframe input populated but got '$v'")
                            }
                        }
                    }.andThen {
                        Browser.eval("document.getElementById('x').value").map { mainV =>
                            assert(mainV.isEmpty, s"main input should remain empty but got '$mainV'")
                        }
                    }
                }
            }
        }
    }

    "withIFrame(f) { Browser.text(span) } reads from the iframe's DOM" in run {
        withBrowser {
            onSrcdoc(singleIframeParent, "<body><span id='greeting'>hello from iframe</span></body>") {
                discoverSingleFrame.map { f =>
                    Browser.withIFrame(f) {
                        Browser.text(Browser.Selector.css("#greeting")).map { t =>
                            assert(t == "hello from iframe", s"expected iframe text but got '$t'")
                        }
                    }
                }
            }
        }
    }

    "withIFrame(f) { Browser.eval[Int](\"document.querySelectorAll('p').length\") } counts elements inside the iframe" in run {
        withBrowser {
            val inner = """<body><p>1</p><p>2</p><p>3</p></body>"""
            onSrcdoc(
                """<body>
                    <p>main-1</p>
                    <iframe id="frame" data-testid="frame" srcdoc="{srcdoc}"></iframe>
                </body>""",
                inner
            ) {
                discoverSingleFrame.map { f =>
                    Browser.withIFrame(f) {
                        Browser.eval("document.querySelectorAll('p').length").map { n =>
                            assert(n == "3", s"expected 3 paragraphs in iframe but got '$n'")
                        }
                    }.andThen {
                        Browser.eval("document.querySelectorAll('p').length").map { n =>
                            assert(n == "1", s"expected 1 paragraph in main but got '$n'")
                        }
                    }
                }
            }
        }
    }

    "nested withIFrame(inner) inside withIFrame(outer) routes the body to inner; on inner exit the outer scope is restored" in run {
        withBrowser {
            // Two sibling iframes; nesting their scopes proves restoration works.
            Browser.goto(twoIframeParent(
                "<body><span id='m'>outer-content</span></body>",
                "<body><span id='m'>inner-content</span></body>"
            )).andThen {
                iframeRetry {
                    Browser.iframe(Browser.Selector.testId("a")).map { outer =>
                        Browser.iframe(Browser.Selector.testId("b")).map { inner =>
                            Browser.withIFrame(outer) {
                                Browser.text(Browser.Selector.css("#m")).map { t1 =>
                                    assert(t1 == "outer-content", s"outer scope text wrong: $t1")
                                    Browser.withIFrame(inner) {
                                        Browser.text(Browser.Selector.css("#m")).map { t2 =>
                                            assert(t2 == "inner-content", s"inner scope text wrong: $t2")
                                        }
                                    }.andThen {
                                        Browser.text(Browser.Selector.css("#m")).map { t3 =>
                                            assert(t3 == "outer-content", s"outer scope not restored: $t3")
                                            succeed
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

    "Browser.click(button) outside any withIFrame routes to main even when an iframe with a same-named button exists" in run {
        withBrowser {
            val inner = """<body>
                <button id="b">Save</button>
                <script>document.getElementById('b').addEventListener('click', function(){window.__clicked='iframe';});</script>
            </body>"""
            onSrcdoc(
                """<body>
                    <button id="mainBtn">Save</button>
                    <script>document.getElementById('mainBtn').addEventListener('click', function(){window.__clicked='main';});</script>
                    <iframe id="frame" data-testid="frame" srcdoc="{srcdoc}"></iframe>
                </body>""",
                inner
            ) {
                // Wait for iframe context to populate so we know it's discoverable, but don't enter withIFrame.
                discoverSingleFrame.andThen {
                    Browser.click(Browser.Selector.css("#mainBtn")).andThen {
                        Browser.eval("window.__clicked").map { v =>
                            assert(v == "main", s"expected main-scoped click but got '$v'")
                        }
                    }
                }
            }
        }
    }

    "concurrent fibers in different withIFrame scopes do not see each other's contexts (Local fiber-isolation property)" in run {
        withBrowser {
            // Each iframe carries a deterministic identifying span; concurrent fibers each scope into their own
            // iframe and read the span. Local-isolation means each fiber observes only its own scope's content.
            // Browser.isolate.clone snapshots the parent URL into independent tabs so both fibers can run in
            // parallel; each clone re-loads the two-iframe page and sees its own copies of frames a and b.
            Browser.goto(twoIframeParent(
                "<body><span id='id'>FRAME-A</span></body>",
                "<body><span id='id'>FRAME-B</span></body>"
            )).andThen {
                Browser.isolate.clone.use {
                    Async.zip(
                        iframeRetry {
                            Browser.iframe(Browser.Selector.testId("a")).map { fa =>
                                Browser.withIFrame(fa)(Browser.text(Browser.Selector.css("#id")))
                            }
                        },
                        iframeRetry {
                            Browser.iframe(Browser.Selector.testId("b")).map { fb =>
                                Browser.withIFrame(fb)(Browser.text(Browser.Selector.css("#id")))
                            }
                        }
                    ).map { case (ta, tb) =>
                        assert(ta == "FRAME-A", s"fiber A saw '$ta' instead of FRAME-A")
                        assert(tb == "FRAME-B", s"fiber B saw '$tb' instead of FRAME-B")
                        succeed
                    }
                }
            }
        }
    }

    "withIFrame(main) inside withIFrame(iframe) restores top-frame scoping, evidenced by Browser.text reading from main's document" in run {
        withBrowser {
            onSrcdoc(
                """<body>
                    <h1 id="m">main-h1</h1>
                    <iframe id="frame" data-testid="frame" srcdoc="{srcdoc}"></iframe>
                </body>""",
                "<body><h1 id='m'>iframe-h1</h1></body>"
            ) {
                discoverSingleFrame.map { f =>
                    Browser.mainFrame.map { mainFrame =>
                        Browser.withIFrame(f) {
                            Browser.text(Browser.Selector.css("#m")).map { tIframe =>
                                assert(tIframe == "iframe-h1", s"iframe scope text wrong: $tIframe")
                                Browser.withIFrame(mainFrame) {
                                    Browser.text(Browser.Selector.css("#m")).map { tMain =>
                                        assert(tMain == "main-h1", s"nested-main scope text wrong: $tMain")
                                        succeed
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // §6.4 IFrame lifecycle
    // ═══════════════════════════════════════════════════════════════════════════

    "BrowserIFrameInvalidException carries Reason.ContextDestroyed when execution context is gone" in run {
        withBrowser {
            onSrcdoc(singleIframeParent, "<body><span id='x'>hello</span></body>") {
                discoverSingleFrame.map { f =>
                    // Capture an iframe-scoped read first to prove the handle is alive, then remove the iframe element from
                    // the PARENT document (so the eval that does the removal must run outside withIFrame), then re-enter the
                    // scope and verify the typed abort surfaces.
                    Browser.withIFrame(f) {
                        Browser.text(Browser.Selector.css("#x")).map { t =>
                            assert(t == "hello", s"sanity check pre-remove: '$t'")
                        }
                    }.andThen {
                        // Remove the iframe from the parent document; runs in main scope by construction.
                        Browser.eval("(() => { document.getElementById('frame').remove(); return 'ok'; })()").andThen {
                            // Wait for the executionContextDestroyed event to land; Retry until the abort path fires.
                            Retry[Throwable](Schedule.fixed(50.millis).take(40)) {
                                Abort.run[BrowserIFrameException] {
                                    Browser.withIFrame(f) {
                                        Browser.eval("1+1")
                                    }
                                }.map {
                                    case Result.Failure(BrowserIFrameInvalidException(Reason.ContextDestroyed)) =>
                                        succeed
                                    case Result.Failure(_: BrowserIFrameInvalidException) =>
                                        Abort.fail[Throwable](new RuntimeException("iframe invalid but not yet ContextDestroyed"))
                                    case other =>
                                        Abort.fail[Throwable](new RuntimeException(s"unexpected: $other"))
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    "iframe.of after the iframe was removed aborts with BrowserElementNotFoundException" in run {
        withBrowser {
            onSrcdoc(singleIframeParent, "<body><span>x</span></body>") {
                // Confirm the iframe is initially present by resolving once.
                discoverSingleFrame.andThen {
                    Browser.eval("(() => { document.getElementById('frame').remove(); return 'ok'; })()").andThen {
                        // Settle so the DOM mutation is observable.
                        Browser.assertNotExists(Browser.Selector.testId("frame")).andThen {
                            tight {
                                Abort.run[BrowserElementException | BrowserIFrameException] {
                                    Browser.iframe(Browser.Selector.testId("frame"))
                                }
                            }.map {
                                case Result.Failure(_: BrowserElementNotFoundException) => succeed
                                case other => fail(s"expected BrowserElementNotFoundException but got $other")
                            }
                        }
                    }
                }
            }
        }
    }

    "parent navigation (Browser.goto) invalidates pre-existing frames; subsequent withIFrame against the stale handle aborts with Reason.ContextDestroyed" in run {
        withBrowser {
            onSrcdoc(singleIframeParent, "<body><span>x</span></body>") {
                discoverSingleFrame.map { stale =>
                    // Navigate the parent; the iframe document is torn down along with its execution context.
                    Browser.goto(page("<body><h1>after</h1></body>")).andThen {
                        Retry[Throwable](Schedule.fixed(50.millis).take(40)) {
                            Abort.run[BrowserIFrameException] {
                                Browser.withIFrame(stale) {
                                    Browser.eval("1+1")
                                }
                            }.map {
                                case Result.Failure(BrowserIFrameInvalidException(Reason.ContextDestroyed)) =>
                                    succeed
                                case Result.Failure(_: BrowserIFrameInvalidException) =>
                                    Abort.fail[Throwable](new RuntimeException("iframe invalid but not yet ContextDestroyed"))
                                case other =>
                                    Abort.fail[Throwable](new RuntimeException(s"unexpected: $other"))
                            }
                        }
                    }
                }
            }
        }
    }

    "re-discovering iframe.of after parent reload returns an IFrame with a fresh executionContextId distinct from the pre-reload one" in run {
        withBrowser {
            val pageHtml = srcdocPage(singleIframeParent, "<body><span>x</span></body>")
            Browser.goto(pageHtml).andThen {
                discoverSingleFrame.map { before =>
                    // Reload the parent page. Browser.reload tears down the iframe document and re-emits
                    // executionContextCreated on the fresh load, yielding a different executionContextId in the
                    // per-tab map.
                    Browser.reload().andThen {
                        discoverSingleFrame.map { after =>
                            // Equality on opaque IFrame falls through to the underlying IFrameHandle case-class equality.
                            assert(before != after, "expected fresh executionContextId after reload but handles equal")
                            succeed
                        }
                    }
                }
            }
        }
    }

    "withIFrame body that runs to completion before iframe removal succeeds; the abort surfaces only on a post-removal action" in run {
        withBrowser {
            onSrcdoc(singleIframeParent, "<body><span id='x'>hi</span></body>") {
                discoverSingleFrame.map { f =>
                    // First scoped call completes BEFORE removal; the body's last action returns successfully.
                    Browser.withIFrame(f) {
                        Browser.text(Browser.Selector.css("#x")).map { t =>
                            assert(t == "hi", s"expected 'hi' but got '$t'")
                        }
                    }.andThen {
                        // Now remove the iframe and re-enter withIFrame with the same handle.
                        Browser.eval("(() => { document.getElementById('frame').remove(); return 'ok'; })()").andThen {
                            Retry[Throwable](Schedule.fixed(50.millis).take(40)) {
                                Abort.run[BrowserIFrameException] {
                                    Browser.withIFrame(f) {
                                        Browser.eval("1+1")
                                    }
                                }.map {
                                    case Result.Failure(BrowserIFrameInvalidException(Reason.ContextDestroyed)) =>
                                        succeed
                                    case Result.Failure(_: BrowserIFrameInvalidException) =>
                                        Abort.fail[Throwable](new RuntimeException("iframe invalid but not yet ContextDestroyed"))
                                    case other =>
                                        Abort.fail[Throwable](new RuntimeException(s"unexpected: $other"))
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // §6.5 IFrame edge cases
    // ═══════════════════════════════════════════════════════════════════════════

    "srcdoc iframe with inline HTML is discoverable and scopable; Browser.text in withIFrame reads the srcdoc body" in run {
        withBrowser {
            onSrcdoc(singleIframeParent, "<body><span id='greeting'>srcdoc-body</span></body>") {
                discoverSingleFrame.map { f =>
                    Browser.withIFrame(f) {
                        Browser.text(Browser.Selector.css("#greeting")).map { t =>
                            assert(t == "srcdoc-body", s"expected 'srcdoc-body' but got '$t'")
                        }
                    }
                }
            }
        }
    }

    "about:blank iframe (no src, no srcdoc) is discoverable; Browser.eval evaluates against its blank document" in run {
        withBrowser {
            onPage("""<body>
                <iframe id="frame" data-testid="frame"></iframe>
            </body>""") {
                discoverSingleFrame.map { f =>
                    Browser.withIFrame(f) {
                        Browser.eval("document.URL").map { u =>
                            assert(u == "about:blank", s"expected about:blank URL but got '$u'")
                        }
                    }
                }
            }
        }
    }

    "data:-URL iframe inherits the parent origin sufficiently for Browser.click and Browser.fill in withIFrame to work" in run {
        withBrowser {
            // data:-URL iframes load into a unique origin, NOT the parent's origin. The spec calls out that they
            // should still work via withIFrame because all kyo-browser does is route Runtime.evaluate by contextId
            // - no cookie / storage access cross-origin is required here.
            val innerHtml = """<body>
                <input id="x" type="text" />
                <button id="b">Save</button>
                <script>document.getElementById('b').addEventListener('click', function(){window.__clicked=true;});</script>
            </body>"""
            val dataUrl   = "data:text/html;charset=utf-8," + BrowserTest.percentEncode(innerHtml)
            onPage(s"""<body>
                <iframe id="frame" data-testid="frame" src="${BrowserTest.htmlAttributeEscape(dataUrl)}"></iframe>
            </body>""") {
                discoverSingleFrame.map { f =>
                    Browser.withIFrame(f) {
                        Browser.fill(Browser.Selector.css("#x"), "abc").andThen {
                            Browser.click(Browser.Selector.css("#b")).andThen {
                                Browser.eval("document.getElementById('x').value + ',' + (window.__clicked === true)").map { v =>
                                    assert(v == "abc,true", s"expected 'abc,true' but got '$v'")
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    "sandboxed iframe with sandbox=\"allow-same-origin allow-scripts\" is fully scopable; sandbox=\"allow-scripts\" only (no allow-same-origin) aborts at iframe.of with BrowserIFrameInvalidException" in run {
        withBrowser {
            // Permissive sandbox: same-origin + scripts. Should resolve and scope normally.
            val inner = """<body><span id="g">sandboxed</span></body>"""
            val parentOk = s"""<body>
                <iframe id="frame" data-testid="frame" sandbox="allow-same-origin allow-scripts" srcdoc="${BrowserTest.htmlAttributeEscape(
                    inner
                )}"></iframe>
            </body>"""
            // Restrictive sandbox: scripts only, NO allow-same-origin → opaque origin → no execution context for our session.
            val parentDenied = s"""<body>
                <iframe id="frame" data-testid="frame" sandbox="allow-scripts" srcdoc="${BrowserTest.htmlAttributeEscape(
                    inner
                )}"></iframe>
            </body>"""
            onPage(parentOk) {
                discoverSingleFrame.map { okHandle =>
                    Browser.withIFrame(okHandle) {
                        Browser.text(Browser.Selector.css("#g")).map { t =>
                            assert(t == "sandboxed", s"expected 'sandboxed' but got '$t'")
                        }
                    }.andThen {
                        // Now navigate to the denied variant and confirm iframe.of aborts with the typed exception.
                        Browser.goto(page(parentDenied)).andThen {
                            iframeRetry {
                                Browser.assertExists(Browser.Selector.testId("frame"))
                            }.andThen {
                                tight {
                                    Abort.run[BrowserIFrameException | BrowserElementException] {
                                        Browser.iframe(Browser.Selector.testId("frame"))
                                    }
                                }.map {
                                    case Result.Failure(_: BrowserIFrameInvalidException) => succeed
                                    case other => fail(s"expected BrowserIFrameInvalidException but got $other")
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // activeIFrameLocal does NOT leak across tab boundaries.
    //
    // An IFrameHandle is session-pinned (frameId + executionContextId belong to a specific tab).
    // Carrying the Local across withNewTab / withFork / isolate.* would
    // route CDP calls at a stale executionContextId in the new tab's session. The leak previously
    // surfaced as a misleading BrowserIFrameInvalidException(ContextDestroyed).
    // -------------------------------------------------------------------------

    "withIFrame followed by withNewTab does not leak the outer iframe's executionContextId into the new tab" in run {
        val inner = "<body><div id='inner'>inner</div></body>"
        withBrowser {
            Browser.goto(srcdocPage(singleIframeParent, inner)).andThen {
                Browser.withConfig(_.retrySchedule(Schedule.fixed(50.millis).take(40))) {
                    Browser.assertExists(Browser.Selector.css("iframe#frame")).andThen {
                        Browser.iframe(Browser.Selector.css("iframe#frame")).map { f =>
                            Browser.withIFrame(f) {
                                Browser.assertExists(Browser.Selector.id("inner")).andThen {
                                    // Inside withNewTab, the outer iframe context must NOT carry over.
                                    // A leaked activeIFrameLocal would route this eval at the outer iframe's
                                    // executionContextId against the new tab's session; Chrome rejects with
                                    // "Cannot find context with specified id" → BrowserIFrameInvalidException.
                                    Browser.withNewTab {
                                        Browser.goto(Browser.dataUrl("<body><h1 id='t'>top level</h1></body>")).andThen {
                                            Browser.text(Browser.Selector.id("t")).map { t =>
                                                assert(t == "top level", s"expected top-level read in new tab but got '$t'")
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

    // Cross-origin iframe: two distinct `withLocalhostServer` instances each get their own OS-assigned port
    // at 127.0.0.1, so the parent (at port A) and iframe (at port B) are on different origins under Chrome's
    // SOP. We assert `Browser.iframe(sel)` returns a usable handle distinct from the main frame, then pin the
    // observed behavior of a scoped action: it may succeed (kyo-browser routes Runtime.evaluate by contextId
    // so basic reads work even cross-origin) or raise a typed `BrowserIFrameException`/`BrowserReadException`.
    "cross-origin iframe: Browser.iframe(sel) returns a usable IFrame handle and withIFrame action surfaces typed result" in run {
        val innerHtml  = """<html><body><span id="x">cross</span></body></html>"""
        val innerBytes = Span.fromUnsafe(innerHtml.getBytes("UTF-8"))
        val innerHandler = HttpRoute.getRaw("/inner").response(_.bodyBinary).handler { _ =>
            HttpResponse.ok(innerBytes).addHeader("Content-Type", "text/html; charset=utf-8")
        }
        withLocalhostServer(innerHandler) { (innerHost, innerPort) =>
            val parentHtml =
                s"""<html><body><iframe id="f" data-testid="f" src="http://$innerHost:$innerPort/inner"></iframe></body></html>"""
            val parentBytes = Span.fromUnsafe(parentHtml.getBytes("UTF-8"))
            val parentHandler = HttpRoute.getRaw("/parent").response(_.bodyBinary).handler { _ =>
                HttpResponse.ok(parentBytes).addHeader("Content-Type", "text/html; charset=utf-8")
            }
            withLocalhostServer(parentHandler) { (parentHost, parentPort) =>
                withBrowser {
                    Browser.goto(s"http://$parentHost:$parentPort/parent").andThen {
                        iframeRetry {
                            Browser.assertExists(Browser.Selector.testId("f")).andThen {
                                Browser.iframe(Browser.Selector.testId("f")).map { f =>
                                    Browser.mainFrame.map { mf =>
                                        // Handle property: cross-origin iframe handle must be distinct from main.
                                        assert(f != mf, s"cross-origin iframe handle equal to main: $f vs $mf")
                                        // Action behavior: withIFrame action either reads the cross-origin DOM
                                        // (kyo-browser routes by executionContextId so this commonly works) or raises
                                        // a typed BrowserReadException. Either outcome pins a typed-shape contract;
                                        // a panic would be a real bug.
                                        Abort.run[BrowserReadException] {
                                            Browser.withIFrame(f) {
                                                Browser.text(Browser.Selector.css("#x"))
                                            }
                                        }.map {
                                            case Result.Success(t) =>
                                                assert(
                                                    t == "cross",
                                                    s"cross-origin withIFrame text should read 'cross' but got '$t'"
                                                )
                                                succeed
                                            case Result.Failure(_: BrowserReadException) =>
                                                // SOP-blocked path: typed Abort is the contract. Document this is OK.
                                                succeed
                                            case other =>
                                                fail(s"expected Result.Success or typed BrowserReadException but got $other")
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

    // Nested iframe action routing two-deep: page with <iframe id="frame"> (outer) containing
    // <iframe id="inner"> (inner). Inner hosts a button. Asserts that
    // withIFrame(outer) { withIFrame(inner) { click(b) } } routes the click to the inner frame,
    // verified via a window.__clicked flag set inside the inner doc (not outer or main).
    "nested withIFrame(outer) { withIFrame(inner) { Browser.click(button) } } routes the action to the inner frame" in run {
        withBrowser {
            val innerInnerHtml = """<body>
                <button id="b">DeepBtn</button>
                <script>
                    window.__clicked = 'none';
                    document.getElementById('b').addEventListener('click', function(){ window.__clicked = 'inner'; });
                </script>
            </body>"""
            Browser.goto(nestedIframeParent(innerInnerHtml)).andThen {
                iframeRetry {
                    Browser.iframe(Browser.Selector.testId("frame")).map { outer =>
                        Browser.withIFrame(outer) {
                            iframeRetry {
                                Browser.iframe(Browser.Selector.css("iframe#inner")).map { inner =>
                                    Browser.withIFrame(inner) {
                                        Browser.click(Browser.Selector.css("#b")).andThen {
                                            Browser.eval("window.__clicked").map { v =>
                                                assert(v == "inner", s"expected inner-scoped click but got '$v'")
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

    // Stale IFrame handle after iframe removed from DOM: capture the handle, remove the iframe element
    // from the parent DOM, then attempt a withIFrame action with the stale handle. The typed contract is
    // BrowserIFrameInvalidException(ContextDestroyed). This is the minimal direct stale-handle scenario
    // (no intermediate successful call), complementing the existing test that has an
    // intermediate-success precondition.
    "stale IFrame handle after iframe removed from DOM: withIFrame action raises BrowserIFrameInvalidException(ContextDestroyed)" in run {
        withBrowser {
            onSrcdoc(singleIframeParent, "<body><span id='x'>hi</span></body>") {
                discoverSingleFrame.map { stale =>
                    Browser.eval("(() => { document.getElementById('frame').remove(); return 'ok'; })()").andThen {
                        Browser.assertNotExists(Browser.Selector.testId("frame")).andThen {
                            Retry[Throwable](Schedule.fixed(50.millis).take(40)) {
                                Abort.run[BrowserIFrameException] {
                                    Browser.withIFrame(stale) {
                                        Browser.eval("1+1")
                                    }
                                }.map {
                                    case Result.Failure(BrowserIFrameInvalidException(Reason.ContextDestroyed)) =>
                                        succeed
                                    case Result.Failure(_: BrowserIFrameInvalidException) =>
                                        Abort.fail[Throwable](new RuntimeException("iframe invalid but not yet ContextDestroyed"))
                                    case other =>
                                        Abort.fail[Throwable](new RuntimeException(s"unexpected: $other"))
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // ── Flat-API scenarios ──────────────────────────────────────────────────────

    "Browser.iframes returns Chunk[IFrame] of all iframe elements" in run {
        withBrowser {
            val pageHtml = twoIframeParent("<body><span>A</span></body>", "<body><span>B</span></body>")
            Browser.goto(pageHtml).andThen {
                iframeRetry {
                    Browser.assertExists(Browser.Selector.testId("a")).andThen {
                        Browser.assertExists(Browser.Selector.testId("b"))
                    }
                }.andThen {
                    // Poll until all 3 execution contexts (main + 2 iframes) have landed.
                    Retry[BrowserAssertionException](Schedule.fixed(50.millis).take(80)) {
                        Browser.iframes.map { frames =>
                            if frames.size == 3 then ()
                            else
                                Abort.fail[BrowserAssertionException](
                                    BrowserAssertionTimedOutException("Browser.iframes size", "3", frames.size.toString)
                                )
                        }
                    }.andThen {
                        Browser.iframes.map { frames =>
                            Browser.mainFrame.map { mf =>
                                assert(frames.size == 3, s"expected main + 2 iframes but got ${frames.size}")
                                assert(frames.head == mf, "expected main frame first in document order")
                                assert(frames(1) != frames(2), "expected the two child iframes to differ")
                                succeed
                            }
                        }
                    }
                }
            }
        }
    }

    "Browser.IFrame remains an opaque type alias after flattening" in {
        // Compile-time witness: this file compiles only if `Browser.IFrame` names a type.
        // The type check happens at compile time; no runtime assertion needed.
        import scala.compiletime.testing.typeChecks
        assert(typeChecks("val _: kyo.Browser.IFrame = ???"), "Browser.IFrame must name a type")
        succeed
    }

end BrowserIFrameTest
