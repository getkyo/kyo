package kyo

class BrowserSettlementTest extends BrowserTest:

    override def timeout = 90.seconds

    // Fixture-tied timing constants for the "nested mutations extend the quiescence window" test.
    // The fixture queues setTimeout mutations at t=10/30/70 and the MutationObserver quiesces 50ms
    // after the last mutation, so the elapsed total must exceed t=70 + window/2 = 95ms.
    private val lastMutationMs: Long     = 70L
    private val quiescenceWindowMs: Long = 50L
    private val settlementFloorMs: Long  = lastMutationMs + quiescenceWindowMs / 2 // = 95

    // ---- goto with explicit settle ----

    "goto with explicit Settle.DomContentLoaded returns after DOMContentLoaded" in run {
        val p = page("<h1>CustomSchedule</h1>")
        withBrowser {
            Browser.goto(p, Browser.Settle.DomContentLoaded).map { _ =>
                Browser.text(Browser.Selector.css("h1")).map { t =>
                    assert(t == "CustomSchedule", s"Expected 'CustomSchedule' but got '$t'")
                }
            }
        }
    }

    // ---- expectNavigation ----

    "expectNavigation completes when the trigger causes a navigation" in run {
        // Source is a real localhost http page (the Chrome DevTools /json/version endpoint). Trigger navigates to a
        // sibling localhost endpoint (`/json`, which serves the page-list JSON). DOM is JSON text, so we settle on
        // DomContentLoaded (no idle network needed) and verify by reading window.location.pathname after settle.
        withBrowserOnLocalhost {
            Browser.eval("window.location.host").map { host =>
                val target = s"http://$host/json"
                Browser.expectNavigation(settle = Browser.Settle.DomContentLoaded) {
                    Abort.run[BrowserScriptException](
                        Browser.eval(s"location.href = '$target'; 'ok'")
                    ).map {
                        case Result.Success(s) => s
                        case other             => "trigger-eval-failed:" + other.toString
                    }
                }.map { triggerResult =>
                    assert(triggerResult == "ok", s"Expected trigger to return 'ok' but got '$triggerResult'")
                    Browser.url.map { u =>
                        assert(u == target, s"Expected URL '$target' after navigation but got '$u'")
                    }
                }
            }
        }
    }

    "expectNavigation aborts with a navigation-failed exception when the trigger does not navigate within the budget" in run {
        val p = page("<body><button id='b'>noop</button></body>")
        withBrowser {
            Browser.goto(p).map { _ =>
                // Tight loadSchedule so the unmet expectation fails quickly.
                Browser.withConfig(_.loadSchedule(Schedule.fixed(50.millis).maxDuration(500.millis))) {
                    Abort.run[BrowserNavigationException] {
                        Browser.expectNavigation() {
                            // Trigger is a no-op JS expression: it does not initiate a navigation.
                            Abort.run[BrowserScriptException](Browser.eval("'no-op'")).unit
                        }
                    }.map {
                        case Result.Failure(_: BrowserNavigationFailedException) => succeed
                        case other =>
                            fail(s"Expected Result.Failure(BrowserNavigationFailedException) but got $other")
                    }
                }
            }
        }
    }

    "expectNavigation honors failOnHttpError for 4xx responses" in run {
        // Navigate via expectNavigation to a localhost path that the Chrome DevTools HTTP server returns 404 for.
        // failOnHttpError = true (the default) raises BrowserNavigationFailedException carrying the HTTP status.
        withBrowserOnLocalhost {
            // Read host:port off the current location so we hit the same Chrome HTTP server with a 404 path.
            Browser.eval("window.location.host").map { host =>
                val notFoundUrl = s"http://$host/json/never-exists"
                Browser.withConfig(_.loadSchedule(Schedule.fixed(50.millis).maxDuration(2.seconds))) {
                    Abort.run[BrowserNavigationException] {
                        Browser.expectNavigation(failOnHttpError = true) {
                            Abort.run[BrowserScriptException](
                                Browser.eval(s"location.href = '$notFoundUrl'; 'ok'")
                            ).unit
                        }
                    }.map {
                        case Result.Failure(_: BrowserNavigationFailedException) => succeed
                        case other =>
                            fail(s"Expected Result.Failure(BrowserNavigationFailedException) but got $other")
                    }
                }
            }
        }
    }

    // ---- waitForText ----

    "waitForText finds text after delay" in run {
        val p = page("""<body>
            <div id="target">loading</div>
            <script>
                setTimeout(function() {
                    document.getElementById('target').innerText = 'ready';
                }, 200);
            </script>
        </body>""")
        withBrowser {
            Browser.goto(p).map { _ =>
                Browser.waitForText(Browser.Selector.css("#target"), _ == "ready").map { result =>
                    assert(result == "ready", s"Expected 'ready' but got '$result'")
                }
            }
        }
    }

    "waitForText fails when predicate never satisfied" in run {
        val p = page("<div id='target'>never-changes</div>")
        withBrowser {
            Browser.goto(p).map { _ =>
                Browser.withConfig(_.retrySchedule(Schedule.fixed(50.millis).maxDuration(500.millis))) {
                    Abort.run[BrowserElementException | BrowserAssertionException] {
                        Browser.waitForText(
                            Browser.Selector.css("#target"),
                            _ == "will-not-match"
                        )
                    }.map {
                        case Result.Failure(_: BrowserAssertionTimedOutException) => succeed
                        case other => fail(s"expected BrowserAssertionTimedOutException but got $other")
                    }
                }
            }
        }
    }

    "waitForText retries until non-empty" in run {
        val p = page("""<body>
            <div id="target"></div>
            <script>
                setTimeout(function() {
                    document.getElementById('target').innerText = 'populated';
                }, 200);
            </script>
        </body>""")
        withBrowser {
            Browser.goto(p).map { _ =>
                Browser.waitForText(Browser.Selector.css("#target"), _.nonEmpty).map { result =>
                    assert(result == "populated", s"Expected 'populated' but got '$result'")
                }
            }
        }
    }

    // ---- waitForAttribute ----

    "waitForAttribute finds attribute after delay" in run {
        val p = page("""<body>
            <div id="target" data-state="pending"></div>
            <script>
                setTimeout(function() {
                    document.getElementById('target').setAttribute('data-state', 'done');
                }, 200);
            </script>
        </body>""")
        withBrowser {
            Browser.goto(p).map { _ =>
                Browser.waitForAttribute(Browser.Selector.css("#target"), "data-state", _ == "done").map { result =>
                    assert(result == "done", s"Expected 'done' but got '$result'")
                }
            }
        }
    }

    "waitForAttribute fails when predicate never satisfied" in run {
        val p = page("<div id='target' data-status='fixed'></div>")
        withBrowser {
            Browser.goto(p).map { _ =>
                Browser.withConfig(_.retrySchedule(Schedule.fixed(50.millis).maxDuration(500.millis))) {
                    Abort.run[BrowserElementException | BrowserAssertionException] {
                        Browser.waitForAttribute(
                            Browser.Selector.css("#target"),
                            "data-status",
                            _ == "will-not-match"
                        )
                    }.map {
                        case Result.Failure(_: BrowserAssertionTimedOutException) => succeed
                        case other => fail(s"expected BrowserAssertionTimedOutException but got $other")
                    }
                }
            }
        }
    }

    // ---- waitFor ----

    "waitFor succeeds when JS becomes truthy" in run {
        val p = page("""<body>
            <script>
                window.appReady = false;
                setTimeout(function() {
                    window.appReady = true;
                }, 200);
            </script>
        </body>""")
        withBrowser {
            Browser.goto(p).map { _ =>
                Browser.waitFor("window.appReady === true").map { result =>
                    assert(result == "true", s"Expected 'true' but got '$result'")
                }
            }
        }
    }

    "waitFor fails when JS stays falsy" in run {
        val p = page("<body><script>window.neverTrue = false;</script></body>")
        withBrowser {
            Browser.goto(p).map { _ =>
                Browser.withConfig(_.retrySchedule(Schedule.fixed(50.millis).maxDuration(500.millis))) {
                    Abort.run[BrowserAssertionException] {
                        Browser.waitFor("window.neverTrue")
                    }.map {
                        case Result.Failure(_: BrowserAssertionTimedOutException) => succeed
                        case other => fail(s"expected BrowserAssertionTimedOutException but got $other")
                    }
                }
            }
        }
    }

    "waitFor returns the truthy value" in run {
        val p = page("""<body>
            <script>
                window.counter = 0;
                setTimeout(function() {
                    window.counter = 42;
                }, 200);
            </script>
        </body>""")
        withBrowser {
            Browser.goto(p).map { _ =>
                Browser.waitFor("window.counter > 0 ? String(window.counter) : ''").map { result =>
                    assert(result == "42", s"Expected '42' but got '$result'")
                }
            }
        }
    }

    // ── DOM mutation settlement: state-changing actions await post-render quiescence ──

    // A click that triggers a React-like re-render awaits the resulting mutation batch.
    // The onclick schedules a setTimeout(..., 100ms) that mutates the button text. Without mutation settlement the first
    // `text` read would observe the stale "0". With settlement, the click blocks until the async text update lands.
    "click awaits a deferred re-render before returning" in run {
        val p = page(
            """<body>
                <button id="b">0</button>
                <script>
                    document.getElementById('b').onclick = () => {
                        setTimeout(() => { document.getElementById('b').textContent = '1'; }, 100);
                    };
                </script>
            </body>"""
        )
        withBrowser {
            for
                _   <- Browser.goto(p)
                _   <- Browser.click(Browser.Selector.id("b"))
                txt <- Browser.text(Browser.Selector.id("b"))
            yield assert(txt == "1", s"expected re-rendered text '1' but got '$txt'; mutation settlement missed the deferred update")
            end for
        }
    }

    // fill that triggers a debounced validation awaits the validation tick.
    // The oninput handler defers a DOM mutation by 50ms; the validation output div gets populated asynchronously after the
    // fill completes. With mutation settlement the first read of the validation text observes 'valid'.
    "fill awaits debounced validation DOM mutation" in run {
        val p = page(
            """<body>
                <input id="i">
                <div id="v"></div>
                <script>
                    document.getElementById('i').oninput = () => {
                        setTimeout(() => { document.getElementById('v').textContent = 'valid'; }, 50);
                    };
                </script>
            </body>"""
        )
        withBrowser {
            for
                _   <- Browser.goto(p)
                _   <- Browser.fill(Browser.Selector.id("i"), "hello")
                txt <- Browser.text(Browser.Selector.id("v"))
            yield assert(txt == "valid", s"expected debounced validation 'valid' but got '$txt'; mutation settlement missed the debounce")
            end for
        }
    }

    // Settlement observes the whole document body, including mutations in unrelated subtrees.
    // The observer is rooted at `document.body` (the design that survives common framework patterns where an onclick handler mutates
    // a sibling DOM zone rather than the action target's subtree). Subtree A (#chatter) mutates continuously via setInterval at 10ms;
    // because the chatter ticks faster than `mutationQuiescenceWindow` (50ms default), settlement legitimately never quiesces and
    // raises [[BrowserAssertionTimedOutException]] after `mutationSettlementTimeout`. This test pins the document-body scoping: a
    // target-subtree observer (the abandoned design) would have ignored the chatter and returned cleanly. The shorter timeout keeps
    // the test fast.
    "settlement observes mutations across the whole document body and raises on continuous chatter" in run {
        val p = page(
            """<body>
                <div id="chatter"><span>0</span></div>
                <div id="target"><button id="b">click</button></div>
                <script>
                    let i = 0;
                    setInterval(() => { document.querySelector('#chatter span').textContent = String(++i); }, 10);
                </script>
            </body>"""
        )
        withBrowser {
            for
                _ <- Browser.goto(p)
                result <-
                    timed {
                        Abort.run[BrowserAssertionException] {
                            Browser.withConfig(_.mutationSettlementTimeout(500.millis))(Browser.click(Browser.Selector.id("b")))
                        }
                    }
                (elapsedDur, outcome) = result
                elapsedMs             = elapsedDur.toMillis
                chatterCount <- Browser.eval("String(parseInt(document.querySelector('#chatter span').textContent, 10))")
            yield
                val ticks = chatterCount.toIntOption.getOrElse(0)
                assert(
                    ticks >= 5,
                    s"expected chatter to have ticked >= 5 times during the click (proving the unrelated subtree was active) but got $ticks"
                )
                outcome match
                    case Result.Failure(_: BrowserAssertionTimedOutException) =>
                        assert(
                            elapsedMs >= 400L && elapsedMs <= 2500L,
                            s"expected document-body observer to time out near mutationSettlementTimeout (500ms) but got ${elapsedMs}ms"
                        )
                    case other =>
                        fail(
                            s"expected document-body observer to raise BrowserAssertionTimedOutException on continuous chatter, but got $other (elapsed=${elapsedMs}ms)"
                        )
                end match
            end for
        }
    }

    // Mutation settlement times out cleanly when the page never quiesces.
    // The onclick handler installs a `setInterval(tick, 5)` that appends a span every 5ms and never
    // stops, so the MutationObserver sees continuous churn well beyond the default 2s
    // `mutationSettlementTimeout`. Throttle-independence is provided by the Chrome flags configured in
    // BrowserLauncher (`--disable-background-timer-throttling`, `--disable-renderer-backgrounding`,
    // `--disable-features=IntensiveWakeUpThrottling`) which keep the 5ms interval firing regardless of
    // tab visibility or backgrounding. Settlement therefore never quiesces and raises
    // BrowserAssertionTimedOutException after `mutationSettlementTimeout`.
    "mutation settlement raises assertion timeout on pages that never quiesce" in run {
        // Static button on page load (no mutations yet); actionability passes immediately.
        // The onclick handler starts the setInterval churn, so settlement begins observing mutations
        // only AFTER the click dispatches.
        withBrowser {
            onPage(
                """<body>
                <button id="b" onclick="
                    window.__tickCount = 0;
                    const me = this;
                    let i = 0;
                    const tick = function() {
                        var s = document.createElement('span');
                        s.textContent = String(i++);
                        me.appendChild(s);
                        if (me.childNodes.length > 3) {
                            me.removeChild(me.firstChild);
                        }
                        window.__tickCount++;
                    };
                    tick();
                    setInterval(tick, 5);
                ">click</button>
            </body>"""
            ) {
                timed {
                    Abort.run[BrowserElementException | BrowserAssertionException] {
                        // Widened quiescence window: 5ms-interval churn vs 500ms window; the BrowserLauncher
                        // Chrome flags (--disable-background-timer-throttling, --disable-renderer-backgrounding,
                        // --disable-features=IntensiveWakeUpThrottling) keep the interval firing through the
                        // full 2s mutationSettlementTimeout regardless of tab visibility.
                        Browser.withConfig(_.mutationQuiescenceWindow(500.millis)) {
                            Browser.click(Browser.Selector.id("b"))
                        }
                    }
                }.map { case (elapsedDur, result) =>
                    val elapsedMs = elapsedDur.toMillis
                    kyo.internal.BrowserEval.evalJs("String(window.__tickCount)").map { tickStr =>
                        val tickCount = tickStr.trim.toInt
                        assert(
                            tickCount > 0,
                            s"setInterval churn never ran (window.__tickCount == $tickCount): timer throttling likely regressed"
                        )
                        result match
                            case Result.Failure(_: BrowserAssertionTimedOutException) =>
                                // Lower bound preserved verbatim. Policy: settlement MUST wait at least ~mutationSettlementTimeout (2s)
                                // before raising. Lower bounds are hardware-monotone. Upper bound relaxed 3× for CI tolerance; the
                                // assertion-timeout failure shape itself is the deterministic behaviour contract.
                                assert(
                                    elapsedMs >= 1500 && elapsedMs <= 12000,
                                    s"expected assertion timeout after ~2000ms (>=1500ms policy lower bound, <=12000ms 3× CI envelope) but got ${elapsedMs}ms"
                                )
                            case other =>
                                fail(s"expected BrowserAssertionTimedOutException but got $other")
                        end match
                    }
                }
            }
        }
    }

    // Quiescence window is configured via Browser.SessionConfig.default: default is 50ms.
    "Browser.SessionConfig.default.mutationQuiescenceWindow is 50ms" in run {
        assert(
            Browser.SessionConfig.default.mutationQuiescenceWindow == 50.millis,
            s"expected mutationQuiescenceWindow == 50.millis but got ${Browser.SessionConfig.default.mutationQuiescenceWindow}"
        )
        assert(
            Browser.SessionConfig.default.mutationSettlementTimeout == 2.seconds,
            s"expected mutationSettlementTimeout == 2.seconds but got ${Browser.SessionConfig.default.mutationSettlementTimeout}"
        )
    }

    // Nested mutations within the quiescence window reset the timer.
    // The onclick schedules three mutations at t=10ms, t=30ms, t=70ms. Each mutation arrives within the previous 50ms quiescence
    // window, resetting the observer's __kyoMutLast. With a 50ms window, quiescence is reached no earlier than t=70+50=120ms.
    // The click should return around 120-200ms post-action, NOT at t=60ms (10+50), which would be the wrong "first mutation then
    // wait the window" behavior that ignores later mutations within the same burst.
    "nested mutations within the quiescence window reset the timer" in run {
        val p = page(
            """<body>
                <button id="b">click</button>
                <div id="out">idle</div>
                <script>
                    document.getElementById('b').onclick = () => {
                        setTimeout(() => { document.getElementById('out').textContent = 'a'; }, 10);
                        setTimeout(() => { document.getElementById('out').textContent = 'b'; }, 30);
                        setTimeout(() => { document.getElementById('out').textContent = 'c'; }, 70);
                    };
                </script>
            </body>"""
        )
        withBrowser {
            for
                _           <- Browser.goto(p)
                timedResult <- timed(Browser.click(Browser.Selector.id("b")))
                (elapsedDur, _) = timedResult
                elapsedMs       = elapsedDur.toMillis
                out <- Browser.text(Browser.Selector.id("out"))
            yield
                assert(out == "c", s"expected final mutation 'c' but got '$out'; settlement returned before the last mutation")
                // Must wait past t=lastMutationMs + window/2 = settlementFloorMs. Allow a generous upper bound for CDP overhead.
                assert(
                    elapsedMs >= settlementFloorMs,
                    s"expected elapsed >= ${settlementFloorMs}ms (nested mutations should extend the window past t=${lastMutationMs}+${quiescenceWindowMs}=${lastMutationMs + quiescenceWindowMs}ms) but got ${elapsedMs}ms"
                )
            end for
        }
    }

    // The observer is removed after settlement: no leaks.
    // After a click completes, the release scope exits and the ref count drops to 0, disconnecting the observer and deleting the
    // window-level state. `window.__kyoMutObs === undefined` should read 'true'.
    "MutationObserver and window state are cleaned up after settlement" in run {
        val p = page(
            """<body>
                <button id="b" onclick="document.getElementById('b').textContent = '1'">0</button>
            </body>"""
        )
        withBrowser {
            for
                _      <- Browser.goto(p)
                _      <- Browser.click(Browser.Selector.id("b"))
                leaked <- Browser.eval("String(typeof window.__kyoMutObs === 'undefined')")
                ref    <- Browser.eval("String(typeof window.__kyoMutObsRef === 'undefined')")
                last   <- Browser.eval("String(typeof window.__kyoMutLast === 'undefined')")
            yield
                assert(leaked == "true", s"expected window.__kyoMutObs to be deleted after settlement but was still defined (got $leaked)")
                assert(ref == "true", s"expected window.__kyoMutObsRef to be deleted after settlement (got $ref)")
                assert(last == "true", s"expected window.__kyoMutLast to be deleted after settlement (got $last)")
            end for
        }
    }

    // Concurrent observers on different subtrees don't interfere.
    // Two sequential back-to-back clicks on elements in different subtrees. Both should return cleanly without deadlocking or
    // observing stale state from the previous observer. Uses the ref-count path: first click installs, cleans up; second click
    // installs fresh, cleans up.
    "back-to-back clicks on different subtrees each settle independently" in run {
        val p = page(
            """<body>
                <div id="leftTree"><button id="left">L0</button></div>
                <div id="rightTree"><button id="right">R0</button></div>
                <script>
                    document.getElementById('left').onclick = () => {
                        setTimeout(() => { document.getElementById('left').textContent = 'L1'; }, 30);
                    };
                    document.getElementById('right').onclick = () => {
                        setTimeout(() => { document.getElementById('right').textContent = 'R1'; }, 30);
                    };
                </script>
            </body>"""
        )
        withBrowser {
            for
                _        <- Browser.goto(p)
                _        <- Browser.click(Browser.Selector.id("left"))
                leftTxt  <- Browser.text(Browser.Selector.id("left"))
                _        <- Browser.click(Browser.Selector.id("right"))
                rightTxt <- Browser.text(Browser.Selector.id("right"))
                // After both settlement cycles complete, cleanup must be complete: no leaked observer state.
                leaked <- Browser.eval("String(typeof window.__kyoMutObs === 'undefined')")
            yield
                assert(leftTxt == "L1", s"expected left subtree settled to 'L1' but got '$leftTxt'")
                assert(rightTxt == "R1", s"expected right subtree settled to 'R1' but got '$rightTxt'")
                assert(leaked == "true", s"expected cleanup after both back-to-back clicks but observer state remained (got $leaked)")
            end for
        }
    }

    // ── Per-scope retry config: `Browser.withConfig` threads a retry schedule ──

    // withConfig(maxDuration 100ms) on a never-matching waitForText fails within ~100ms ± 50ms.
    "Browser.withConfig(retrySchedule maxDuration 100ms) with never-matching waitForText fails within ~100ms" in run {
        withBrowser {
            onPage("<div id='target'>never-changes</div>") {
                timed {
                    Abort.run[BrowserElementException | BrowserAssertionException] {
                        Browser.withConfig(_.retrySchedule(Schedule.fixed(50.millis).maxDuration(100.millis))) {
                            Browser.waitForText(Browser.Selector.css("#target"), _ == "never")
                        }
                    }
                }.map { case (elapsedDur, result) =>
                    val elapsedMs = elapsedDur.toMillis
                    result match
                        case Result.Failure(_: BrowserAssertionTimedOutException) =>
                            // Behavior contract: the operation MUST fail (the schedule's maxDuration was reached). Lower bound preserved
                            // (>= 50ms is policy); upper bound relaxed 3× for CI tolerance.
                            assert(
                                elapsedMs >= 50 && elapsedMs <= 1500,
                                s"Expected elapsed ~100ms (>=50ms policy lower bound, <=1500ms 3× CI envelope) but got ${elapsedMs}ms"
                            )
                        case other => fail(s"expected BrowserAssertionTimedOutException but got $other")
                    end match
                }
            }
        }
    }

    // Nested withConfig: inner config is used for the inner call, not the outer.
    "Nested withConfig uses innermost value" in run {
        withBrowser {
            onPage("<div id='target'>never-changes</div>") {
                Browser.withConfig(_.retrySchedule(Schedule.fixed(50.millis).maxDuration(500.millis))) {
                    timed {
                        Abort.run[BrowserElementException | BrowserAssertionException] {
                            Browser.withConfig(_.retrySchedule(Schedule.fixed(50.millis).maxDuration(50.millis))) {
                                Browser.waitForText(Browser.Selector.css("#target"), _ == "never")
                            }
                        }
                    }.map { case (elapsedDur, result) =>
                        val elapsedMs = elapsedDur.toMillis
                        result match
                            case Result.Failure(_: BrowserAssertionTimedOutException) =>
                                // Behavior contract: failure shape is the deterministic contract; inner config produced a timeout
                                // (rather than the outer 500ms config). Upper bound relaxed 3× for CI tolerance.
                                assert(
                                    elapsedMs <= 1200,
                                    s"Expected elapsed dominated by inner 50ms (<=1200ms 3× CI envelope) but got ${elapsedMs}ms; inner did not override outer"
                                )
                            case other => fail(s"expected BrowserAssertionTimedOutException but got $other")
                        end match
                    }
                }
            }
        }
    }

    // Browser.SessionConfig.default has 5s retry schedule.
    "Browser.SessionConfig.default retrySchedule has 5s maxDuration" in run {
        Browser.configLocal.use { cfg =>
            val schedule = cfg.retrySchedule
            assert(
                schedule == Browser.SessionConfig.default.retrySchedule,
                s"Expected default retrySchedule at outermost scope but got $schedule"
            )
        }
    }

    // withConfig retrySchedule unbounded: a slow-but-eventually-true waitForText succeeds.
    "Browser.withConfig(unbounded retrySchedule) with eventually-true waitForText succeeds" in run {
        withBrowser {
            onPage("""<body>
            <div id="target"></div>
            <script>
                setTimeout(function() {
                    document.getElementById('target').innerText = 'done';
                }, 300);
            </script>
        </body>""") {
                Browser.withConfig(_.retrySchedule(Schedule.fixed(50.millis))) {
                    Browser.waitForText(Browser.Selector.css("#target"), _ == "done").map { text =>
                        assert(text == "done", s"Expected 'done' but got '$text'")
                    }
                }
            }
        }
    }

    // waitForText, assertExists, click, fill honor the retry schedule from withConfig.
    "waitForText and assertExists honor withConfig retrySchedule" in run {
        def assertBounded(label: String, exception: BrowserException, elapsedMs: Long): Assertion =
            // Behavior contract: each operation MUST fail (the scope's 100ms maxDuration was reached). Failure shape is the
            // deterministic contract; upper bound relaxed 3× for CI tolerance.
            assert(
                elapsedMs <= 1800,
                s"$label: expected elapsed dominated by 100ms config (<=1800ms 3× CI envelope) but got ${elapsedMs}ms"
            )
        end assertBounded
        withBrowser {
            onPage("<div id='anchor'>anchor</div>") {
                Browser.withConfig(_.retrySchedule(Schedule.fixed(50.millis).maxDuration(100.millis))) {
                    timed(Abort.run[BrowserElementException | BrowserAssertionException] {
                        Browser.waitForText(Browser.Selector.css("#anchor"), _ == "never-ever")
                    }).map { case (d, r) =>
                        r match
                            case Result.Failure(e: BrowserAssertionTimedOutException) =>
                                assertBounded("waitForText", e, d.toMillis)
                            case other => fail(s"waitForText: expected BrowserAssertionTimedOutException but got $other")
                    }.andThen(
                        timed(Abort.run[BrowserElementException] {
                            Browser.assertExists(Browser.Selector.css("#ghost-element"))
                        }).map { case (d, r) =>
                            r match
                                case Result.Failure(e: BrowserElementNotFoundException) =>
                                    assertBounded("assertExists", e, d.toMillis)
                                case other => fail(s"assertExists: expected BrowserElementNotFoundException but got $other")
                        }
                    ).andThen(
                        timed(Abort.run[BrowserElementException] {
                            Browser.click(Browser.Selector.css("#ghost-element"))
                        }).map { case (d, r) =>
                            r match
                                case Result.Failure(e: BrowserElementException) =>
                                    assertBounded("click", e, d.toMillis)
                                case other => fail(s"click: expected BrowserElementException but got $other")
                        }
                    ).andThen(
                        timed(Abort.run[BrowserElementException] {
                            Browser.fill(Browser.Selector.css("#ghost-input"), "hi")
                        }).map { case (d, r) =>
                            r match
                                case Result.Failure(e: BrowserElementException) =>
                                    assertBounded("fill", e, d.toMillis)
                                case other => fail(s"fill: expected BrowserElementException but got $other")
                        }
                    ).map(_ => succeed)
                }
            }
        }
    }

    // Sibling fibers in Async.zip each see their own withConfig scope.
    "Sibling Async.zip fibers see their own withConfig scope" in run {
        val slowPage = page("""<body>
            <div id="slow"></div>
            <script>
                setTimeout(function() {
                    document.getElementById('slow').innerText = 'arrived';
                }, 700);
            </script>
        </body>""")
        val fastPage = page("<div id='fast'>never-changes</div>")
        withBrowser {
            Browser.isolate.fresh.use {
                Async.zip(
                    // Generous config: waits past 700ms for #slow to populate.
                    Browser.withConfig(_.retrySchedule(Schedule.fixed(50.millis).maxDuration(2.seconds))) {
                        Browser.goto(slowPage).andThen {
                            Browser.waitForText(Browser.Selector.css("#slow"), _ == "arrived").map { result =>
                                assert(result == "arrived", s"Slow fiber: expected 'arrived' but got '$result'")
                                result
                            }
                        }
                    },
                    // Tight config: never-matches; must fail fast regardless of sibling's generous config.
                    Browser.withConfig(_.retrySchedule(Schedule.fixed(50.millis).maxDuration(100.millis))) {
                        Browser.goto(fastPage).andThen {
                            timed {
                                Abort.run[BrowserElementException | BrowserAssertionException] {
                                    Browser.waitForText(Browser.Selector.css("#fast"), _ == "never-match")
                                }
                            }.map { case (elapsedDur, result) =>
                                val elapsedMs = elapsedDur.toMillis
                                result match
                                    case Result.Failure(_: BrowserAssertionTimedOutException) =>
                                        // Behavior contract: tight fiber MUST timeout regardless of sibling's 2s config; failure shape
                                        // is the deterministic isolation guarantee. Upper bound relaxed 3× for CI tolerance.
                                        assert(
                                            elapsedMs <= 2400,
                                            s"Tight fiber: expected <=2400ms 3× CI envelope (isolated from sibling's 2s config) but got ${elapsedMs}ms"
                                        )
                                        result
                                    case other => fail(s"Tight fiber: expected BrowserAssertionTimedOutException but got $other")
                                end match
                            }
                        }
                    }
                ).map { (_, _) => succeed }
            }
        }
    }

    // ---- Settle.Load + signature normalization + catalog deliverable ----

    "Settle.Load is accepted by goto and the back/forward/reload paths" in run {
        // Two distinct pages so back/forward have somewhere to navigate between.
        val first  = page("<h1>first</h1>")
        val second = page("<h1>second</h1>")
        withBrowser {
            for
                _    <- Browser.goto(first, Browser.Settle.Load)
                _    <- Browser.goto(second, Browser.Settle.Load)
                _    <- Browser.back
                back <- Browser.text(Browser.Selector.css("h1"))
                _ = assert(back == "first", s"after back: expected 'first' but got '$back'")
                _   <- Browser.forward
                fwd <- Browser.text(Browser.Selector.css("h1"))
                _ = assert(fwd == "second", s"after forward: expected 'second' but got '$fwd'")
                _   <- Browser.reload()
                rel <- Browser.text(Browser.Selector.css("h1"))
            yield assert(rel == "second", s"after reload: expected 'second' but got '$rel'")
            end for
        }
    }

    // -------------------------------------------------------------------------
    // NetworkIdle graceful degradation to Load
    //
    // When a page keeps emitting fetch traffic (analytics heartbeats, telemetry pings) past the loadSchedule budget, the load event
    // having fired is treated as success; the network-idle gate is downgraded to a Log.warn and the call returns rather than aborting
    // with `settle timeout after NetworkIdle`.
    // -------------------------------------------------------------------------

    "Settle.NetworkIdle degrades to Load when load fires but network never quiesces" in run {
        // Use a localhost server so the fetch loop has a real same-origin endpoint to hit (data: URLs can't host
        // fetch targets without CORS gymnastics, and chronic CDN-cached redirects flake on remote hosts).
        val html =
            """<!doctype html><html><body><h1>chatty</h1>
              |<script>
              |  // Fire a fetch every 50ms. Each one keeps __kyoNetPending > 0 so the network-idle gate
              |  // can never open a quiet window, yet the load event fires on the initial HTML parse.
              |  setInterval(() => { fetch('/ping').catch(() => {}); }, 50);
              |</script></body></html>""".stripMargin
        val htmlBytes = Span.fromUnsafe(html.getBytes("UTF-8"))
        val pingBytes = Span.fromUnsafe("ok".getBytes("UTF-8"))
        val htmlHandler = HttpRoute.getRaw("/").response(_.bodyBinary).handler { _ =>
            HttpResponse.ok(htmlBytes).addHeader("Content-Type", "text/html; charset=utf-8")
        }
        val pingHandler = HttpRoute.getRaw("/ping").response(_.bodyBinary).handler { _ =>
            HttpResponse.ok(pingBytes).addHeader("Content-Type", "text/plain")
        }
        withLocalhostServer(htmlHandler, pingHandler) { (host, port) =>
            withBrowser {
                // Trim loadSchedule so the test doesn't sit on the default 5s budget; 1s is plenty for the
                // load event to fire while leaving no room for a 500ms quiet window to open in the chatty
                // background traffic.
                Browser.withConfig(_.loadSchedule(Schedule.fixed(100.millis).maxDuration(1.second))) {
                    Browser.goto(s"http://$host:$port/").andThen {
                        Browser.text(Browser.Selector.css("h1")).map { t =>
                            assert(t == "chatty", s"expected the page to actually render despite the chatty network, got '$t'")
                        }
                    }
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Same-URL Browser.goto after a typed failure (B1)
    //
    // Repro for the GitHubNotFoundRecoveryDemo finding: after `Browser.goto(url)` with the default `failOnHttpError=true`
    // raises BrowserNavigationFailedException on a 4xx response, Chrome HAS fully loaded the page at `url`. A follow-up
    // `Browser.goto(url, failOnHttpError=false)` should be either a no-op success (URL unchanged, page already loaded) OR
    // an explicit reload. Today it silently times out with "navigation never committed (still at original URL)" because
    // the URL-change gate inside `awaitSettle` is waiting for a URL change that will never happen. Users have no way to
    // tell "same URL, already loaded, proceed" from "Chrome actually failed to navigate".
    // -------------------------------------------------------------------------

    "same-URL Browser.goto after a typed 4xx failure succeeds (no-op when already on target URL)" in run {
        val bytes = Span.fromUnsafe(
            """<!doctype html><html><body><h1>Not Found</h1><p>This is a 404 page</p></body></html>""".getBytes("UTF-8")
        )
        val handler = HttpRoute.getRaw("/missing").response(_.bodyBinary).handler { _ =>
            HttpResponse.notFound(bytes).addHeader("Content-Type", "text/html; charset=utf-8")
        }
        withLocalhostServer(handler) { (host, port) =>
            val url = s"http://$host:$port/missing"
            withBrowser {
                for
                    // Step 1: strict-mode goto raises the typed exception (and Chrome navigates fully)
                    firstAttempt <- Abort.run[BrowserNavigationException] {
                        Browser.goto(url)
                    }
                    _ = firstAttempt match
                        case Result.Failure(_: BrowserNavigationFailedException) => ()
                        case other => fail(s"expected BrowserNavigationFailedException but got $other")
                    // Step 2: re-goto the SAME URL with failOnHttpError=false; should be a no-op success.
                    // BUG today: this times out with "navigation never committed (still at original URL)" because the
                    // watcher waits for a URL change that the request can't produce (the URL was already that URL).
                    _ <- Browser.withConfig(_.loadSchedule(Schedule.fixed(100.millis).maxDuration(2.seconds))) {
                        Browser.goto(url, failOnHttpError = false)
                    }
                    // After the no-op success, the page should still be the 404 page loaded by step 1.
                    body <- Browser.text(Browser.Selector.css("body"))
                yield assert(body.contains("This is a 404 page"), s"expected 404 page content, got: $body")
            }
        }
    }

    // ---- wait predicates ----

    "waitForUrl returns matched URL when navigation happens after a delay" in run {
        // history.pushState fires after a short setTimeout; waitForUrl returns the matched URL.
        withBrowser {
            onPage(
                "<div>start</div>" +
                    "<script>setTimeout(() => { history.pushState({}, '', '#dashboard'); }, 100);</script>"
            ) {
                Browser.waitForUrl(_.endsWith("#dashboard")).map { u =>
                    assert(u.endsWith("#dashboard"), s"expected URL ending in '#dashboard' but got '$u'")
                }
            }
        }
    }

    "waitForTitle returns matched title when document.title is set via JS after a delay" in run {
        withBrowser {
            onPage(
                "<title>initial</title><div>x</div>" +
                    "<script>setTimeout(() => { document.title = 'updated'; }, 100);</script>"
            ) {
                Browser.waitForTitle(_ == "updated").map { t =>
                    assert(t == "updated", s"expected title 'updated' but got '$t'")
                }
            }
        }
    }

    "waitForCount returns the first stable matching count when items are appended via setInterval" in run {
        withBrowser {
            onPage(
                "<ul id='list'></ul>" +
                    "<script>let n=0; const id=setInterval(()=>{if(n<5){const li=document.createElement('li');li.textContent='item-'+n;document.getElementById('list').appendChild(li);n++;}else{clearInterval(id);}},80);</script>"
            ) {
                Browser.waitForCount(Browser.Selector.css("#list li"), _ >= 3).map { n =>
                    assert(n >= 3, s"expected count >= 3 but got $n")
                }
            }
        }
    }

    "waitForVisible returns Unit when display:none is removed asynchronously" in run {
        withBrowser {
            onPage(
                "<div id='t' style='display:none'>delayed</div>" +
                    "<script>setTimeout(() => { document.getElementById('t').style.display = 'block'; }, 150);</script>"
            ) {
                Browser.waitForVisible(Browser.Selector.css("#t")).andThen {
                    Browser.isVisible(Browser.Selector.css("#t")).map { v =>
                        assert(v, "expected #t to be visible after waitForVisible")
                    }
                }
            }
        }
    }

    "waitForExists returns Unit when the element is appended via setTimeout" in run {
        withBrowser {
            onPage(
                "<div id='container'></div>" +
                    "<script>setTimeout(() => { const p=document.createElement('p');p.id='late';document.getElementById('container').appendChild(p);}, 150);</script>"
            ) {
                Browser.waitForExists(Browser.Selector.css("#late")).andThen {
                    Browser.exists(Browser.Selector.css("#late")).map { ex =>
                        assert(ex, "expected #late to exist after waitForExists")
                    }
                }
            }
        }
    }

    "waitForText equality overload resolves once the text matches" in run {
        withBrowser {
            onPage(
                "<div id='msg'>loading</div>" +
                    "<script>setTimeout(() => { document.getElementById('msg').textContent = 'Done'; }, 120);</script>"
            ) {
                Browser.waitForText(Browser.Selector.css("#msg"), "Done").map { t =>
                    assert(t == "Done", s"expected 'Done' but got '$t'")
                }
            }
        }
    }

    "waitForAttribute equality overload resolves once the attribute matches" in run {
        withBrowser {
            onPage(
                "<div id='ready' data-state='loading'>x</div>" +
                    "<script>setTimeout(() => { document.getElementById('ready').setAttribute('data-state','ready'); }, 120);</script>"
            ) {
                Browser.waitForAttribute(Browser.Selector.css("#ready"), "data-state", "ready").map { v =>
                    assert(v == "ready", s"expected 'ready' but got '$v'")
                }
            }
        }
    }

    // ── Settle.NetworkIdle positive (3-fetch fixture) ────────────────────────

    /** Empirical property: `Browser.goto(p, Settle.NetworkIdle)` returns AFTER the last in-flight fetch completes plus the configured
      * `networkIdleWindow`. The fixture fires 3 fetches at 100ms / 200ms / 300ms after DOMContentLoaded; the call MUST NOT return on the
      * Load event alone (which would yield elapsed under ~200ms). Lower bound `>= 200.millis` is the load-bearing claim; upper bound absorbs
      * Chrome roundtrip + network-idle window + CI jitter.
      */
    "Settle.NetworkIdle waits for chatty fetches to quiesce (3-fetch positive case)" in run {
        // Three deferred fetches plus a same-origin /ping endpoint. data: URLs cannot host cross-origin fetch targets without CORS
        // gymnastics, so the localhost server pattern mirrors the line ~746 NetworkIdle degradation test.
        val html =
            """<!doctype html><html><body><h1>three-fetch</h1>
              |<script>
              |  document.addEventListener('DOMContentLoaded', () => {
              |    setTimeout(() => { fetch('/ping?n=1').catch(() => {}); }, 100);
              |    setTimeout(() => { fetch('/ping?n=2').catch(() => {}); }, 200);
              |    setTimeout(() => { fetch('/ping?n=3').catch(() => {}); }, 300);
              |  });
              |</script></body></html>""".stripMargin
        val htmlBytes = Span.fromUnsafe(html.getBytes("UTF-8"))
        val pingBytes = Span.fromUnsafe("ok".getBytes("UTF-8"))
        val htmlHandler = HttpRoute.getRaw("/").response(_.bodyBinary).handler { _ =>
            HttpResponse.ok(htmlBytes).addHeader("Content-Type", "text/html; charset=utf-8")
        }
        val pingHandler = HttpRoute.getRaw("/ping").response(_.bodyBinary).handler { _ =>
            HttpResponse.ok(pingBytes).addHeader("Content-Type", "text/plain")
        }
        withLocalhostServer(htmlHandler, pingHandler) { (host, port) =>
            withBrowser {
                // Pin networkIdleWindow explicitly so the test fails visibly if the default is ever retuned.
                Browser.withConfig(_.networkIdleWindow(500.millis)) {
                    timed(Browser.goto(s"http://$host:$port/", Browser.Settle.NetworkIdle)).map { case (elapsedDur, _) =>
                        val elapsedMs = elapsedDur.toMillis
                        // Lower bound proves the call did not return on Load alone (Load would fire under ~200ms for this trivial doc).
                        // Upper bound (default 500ms idle window + last fetch at 300ms + Chrome overhead + CI envelope) is generous.
                        assert(
                            elapsedMs >= 200L && elapsedMs <= 5000L,
                            s"Settle.NetworkIdle must wait past the 3-fetch burst (>= 200ms) and within idle window envelope (<= 5000ms) but got ${elapsedMs}ms"
                        )
                    }
                }
            }
        }
    }

    // ── Settle.Load with slow <img> ──────────────────────────────────────────

    /** Empirical property: `Browser.goto(p, Settle.Load)` waits for the `load` event, which only fires after every subresource (including
      * `<img>`) finishes. With a server-delayed image, elapsed must be >= the server delay; an early return would mean `Settle.Load` is
      * firing on DOMContentLoaded.
      */
    "Settle.Load waits for slow <img> subresource to load before returning" in run {
        // Minimal valid 1x1 transparent GIF89a (35 bytes). Chrome fires `load` only on valid image bytes; a bogus payload would fire
        // `error` and the load gate would never open.
        val tinyGifBytes: Span[Byte] = Span.fromUnsafe(
            Array[Byte](
                0x47.toByte,
                0x49.toByte,
                0x46.toByte,
                0x38.toByte,
                0x39.toByte,
                0x61.toByte, // GIF89a
                0x01.toByte,
                0x00.toByte,
                0x01.toByte,
                0x00.toByte, // 1x1
                0x80.toByte,
                0x00.toByte,
                0x00.toByte,
                0xff.toByte,
                0xff.toByte,
                0xff.toByte, // white
                0x00.toByte,
                0x00.toByte,
                0x00.toByte, // black
                0x21.toByte,
                0xf9.toByte,
                0x04.toByte,
                0x01.toByte,
                0x00.toByte,
                0x00.toByte,
                0x00.toByte,
                0x00.toByte, // GCE
                0x2c.toByte,
                0x00.toByte,
                0x00.toByte,
                0x00.toByte,
                0x00.toByte,
                0x01.toByte,
                0x00.toByte,
                0x01.toByte,
                0x00.toByte,
                0x00.toByte,
                0x02.toByte,
                0x02.toByte,
                0x44.toByte,
                0x01.toByte,
                0x00.toByte,
                0x3b.toByte
            )
        )
        val html =
            """<!doctype html><html><body><h1>slow-image</h1>
              |<img src="/slow-image.gif" alt="slow"/></body></html>""".stripMargin
        val htmlBytes = Span.fromUnsafe(html.getBytes("UTF-8"))
        val htmlHandler = HttpRoute.getRaw("/").response(_.bodyBinary).handler { _ =>
            HttpResponse.ok(htmlBytes).addHeader("Content-Type", "text/html; charset=utf-8")
        }
        val slowImageHandler = HttpRoute.getRaw("/slow-image.gif").response(_.bodyBinary).handler { _ =>
            Async.sleep(500.millis).andThen(
                HttpResponse.ok(tinyGifBytes).addHeader("Content-Type", "image/gif")
            )
        }
        withLocalhostServer(htmlHandler, slowImageHandler) { (host, port) =>
            withBrowser {
                timed(Browser.goto(s"http://$host:$port/", Browser.Settle.Load)).map { case (elapsedDur, _) =>
                    val elapsedMs = elapsedDur.toMillis
                    // Lower bound proves the load gate waited for the 500ms image delay (allow some network-roundtrip slack below 500ms).
                    // Upper bound is conservative for CI: cold Chrome + load wait + slack.
                    assert(
                        elapsedMs >= 400L && elapsedMs <= 8000L,
                        s"Settle.Load must wait for the slow <img> subresource (>= 400ms) but got ${elapsedMs}ms"
                    )
                }
            }
        }
    }

    // ── mutationQuiescenceWindow matrix (10ms and 500ms) ────────────────────
    //
    // Shared fixture: the onclick handler triggers a synchronous DOM mutation immediately (so the first-mutation grace path is satisfied
    // before the polling JS captures `startCount`), then schedules 5 later mutations at 30ms-spaced offsets so the quiescence window can
    // observe the inter-mutation gaps. With this shape:
    //   * the synchronous mutation guarantees sawMutation=true once the polling loop starts (no firstGrace early-return);
    //   * the 30ms gaps test whether the configured window absorbs subsequent mutations (wide window) or releases between them (tight).
    private val quiescenceMatrixHtml: String =
        """<body>
            <div id="root">start</div>
            <button id="trigger">go</button>
            <script>
                document.getElementById('trigger').onclick = () => {
                    document.getElementById('root').textContent = 'init';
                    setTimeout(() => { document.getElementById('root').textContent = 'a'; }, 50);
                    setTimeout(() => { document.getElementById('root').textContent = 'b'; }, 80);
                    setTimeout(() => { document.getElementById('root').textContent = 'c'; }, 110);
                    setTimeout(() => { document.getElementById('root').textContent = 'd'; }, 140);
                    setTimeout(() => { document.getElementById('root').textContent = 'e'; }, 170);
                };
            </script>
        </body>"""

    /** Empirical property: with a tight `mutationQuiescenceWindow(10.millis)` and 30ms-spaced fixture mutations (gaps exceed the window),
      * settlement resolves shortly after the FIRST mutation's window expires; subsequent mutations do not re-extend the wait. Asserts
      * elapsed is bounded BELOW the "all 5 mutations + window" total (320 + 10 = 330ms minimum) so the test fails if the resolver started
      * capturing later mutations under a tighter window.
      */
    "mutationQuiescenceWindow(10ms) lets 30ms-spaced mutations resolve in the first window" in run {
        val p = page(quiescenceMatrixHtml)
        withBrowser {
            for
                _ <- Browser.goto(p)
                timedResult <-
                    timed(Browser.withConfig(_.mutationQuiescenceWindow(10.millis))(Browser.click(Browser.Selector.id("trigger"))))
                (elapsedDur, _) = timedResult
                elapsedMs       = elapsedDur.toMillis
            yield
                // Lower bound (50ms): actionability (~33ms of stability sleeps) plus click dispatch is the empirical floor on any
                // platform; below this the elapsed time can't include a real settlement window. With document-body scope, the
                // synchronous `root.textContent='init'` mutation inside onclick is observed at the click instant, so settlement
                // quiesces after the 10ms window between the init mutation and the first setTimeout (t=50ms gap). Settlement
                // itself completes near t=10-30ms; total elapsed is dominated by actionability + click setup.
                // Upper bound (320ms): with 10ms window vs 30ms gaps, settlement should NOT wait for all 5 mutations (last at
                // t=320ms + 10ms quiet = 330ms minimum if captured). Default 20ms pollInterval adds jitter; cap at 320ms to fail
                // visibly if later mutations slipped into the window.
                assert(
                    elapsedMs >= 50L && elapsedMs <= 320L,
                    s"mutationQuiescenceWindow(10ms) should resolve after the first mutation's window expires (10ms vs 30ms gap), expected [50, 320]ms but got ${elapsedMs}ms"
                )
            end for
        }
    }

    /** Empirical property: with a wide `mutationQuiescenceWindow(500.millis)` and 30ms-spaced mutations (all 5 fall inside the window),
      * settlement waits for the LAST mutation + 500ms quiet. Elapsed must exceed the wide window's lower bound; an early return would mean
      * the window was ignored.
      */
    "mutationQuiescenceWindow(500ms) waits for all 30ms-spaced mutations to quiesce" in run {
        val p = page(quiescenceMatrixHtml)
        withBrowser {
            for
                _ <- Browser.goto(p)
                timedResult <-
                    timed(Browser.withConfig(_.mutationQuiescenceWindow(500.millis))(Browser.click(Browser.Selector.id("trigger"))))
                (elapsedDur, _) = timedResult
                elapsedMs       = elapsedDur.toMillis
                finalText <- Browser.text(Browser.Selector.id("root"))
            yield
                // The last mutation is at t=320ms. With a 500ms quiescence window, settlement should wait until at least t=320+500=820ms.
                // Floor at 700ms to absorb scheduler jitter on the polling cadence while still proving the window expanded past every
                // mutation. Final-text == "e" confirms settlement waited until after the last mutation landed.
                assert(finalText == "e", s"expected the last mutation 'e' to land before settlement returns but got '$finalText'")
                assert(
                    elapsedMs >= 700L && elapsedMs <= 3000L,
                    s"mutationQuiescenceWindow(500ms) should wait past the last 30ms-spaced mutation plus 500ms quiet (>=700ms) but got ${elapsedMs}ms"
                )
            end for
        }
    }

    // ── Custom mutationSettlementTimeout(500ms) ──────────────────────────────

    /** Empirical property: setting `mutationSettlementTimeout(500.millis)` shortens the never-quiesce timeout from the default 2s. With
      * 20ms-spaced mutations against the default 50ms quiescence window (never quiesces), settlement raises
      * `BrowserAssertionTimedOutException` after ~500ms, NOT 2s.
      */
    "mutationSettlementTimeout(500ms) shortens the never-quiesce timeout" in run {
        // Mirror the never-quiesce shape used by the default-timeout test (settlement raises
        // BrowserAssertionTimedOutException on chatty pages). Combine a wide mutationQuiescenceWindow(500ms)
        // (so the observer never sees a quiet 500ms gap inside the 5ms-interval churn) with the custom
        // mutationSettlementTimeout(500ms). The default-timeout foil pins the same churn to the default 2s
        // timeout. Pinning here to 500ms proves the per-call override actually shortens the timeout
        // (elapsed in [400, 1500]ms vs the default-timeout envelope of [1500, 12000]ms).
        withBrowser {
            onPage(
                """<body>
                    <button id="b" onclick="
                        let i = 0;
                        const me = this;
                        const tick = function() {
                            var s = document.createElement('span');
                            s.textContent = String(i++);
                            me.appendChild(s);
                            if (me.childNodes.length > 3) me.removeChild(me.firstChild);
                        };
                        tick();
                        setInterval(tick, 5);
                    ">click</button>
                </body>"""
            ) {
                timed {
                    Abort.run[BrowserElementException | BrowserAssertionException] {
                        Browser.withConfig(_.mutationQuiescenceWindow(500.millis).mutationSettlementTimeout(500.millis)) {
                            Browser.click(Browser.Selector.id("b"))
                        }
                    }
                }.map { case (elapsedDur, result) =>
                    val elapsedMs = elapsedDur.toMillis
                    result match
                        case Result.Failure(_: BrowserAssertionTimedOutException) =>
                            assert(
                                elapsedMs >= 400L && elapsedMs <= 1500L,
                                s"mutationSettlementTimeout(500ms) should timeout in [400, 1500]ms (foil: default 2s) but got ${elapsedMs}ms"
                            )
                        case other =>
                            fail(s"expected BrowserAssertionTimedOutException after 500ms timeout but got $other")
                    end match
                }
            }
        }
    }

    // ── Phase 2: data-kyo-internal filter + waitForStable + settleForCapture ──

    // Test 1: injecting a data-kyo-internal node leaves __kyoMutCount unchanged.
    // The observer is installed via a no-op afterAction call so __kyoMutCount and the
    // observer exist; subsequent mutations inside the tagged subtree are filtered out
    // and must not change the counter.
    "data-kyo-internal mutations do not arm the settlement gate" in run {
        withBrowser {
            onPage("<body><div id='real'>initial</div></body>") {
                // Install the observer via a no-op afterAction so __kyoMutCount is initialized.
                kyo.internal.MutationSettlement.afterAction(Browser.eval("'noop'"))(Absent).andThen {
                    // Read the count right after the no-op action settled.
                    Browser.eval("String(window.__kyoMutCount || 0)").map { beforeStr =>
                        val before = beforeStr.toLong
                        // Inject a data-kyo-internal subtree and mutate it several times.
                        Browser.eval("""(() => {
                            const d = document.createElement('div');
                            d.setAttribute('data-kyo-internal', 'overlay');
                            d.id = 'overlay';
                            document.body.appendChild(d);
                            d.textContent = 'x';
                            d.textContent = 'y';
                            d.textContent = 'z';
                            const child = document.createElement('span');
                            child.textContent = 'child';
                            d.appendChild(child);
                            return 'done';
                        })()""").andThen {
                            Browser.eval("String(window.__kyoMutCount || 0)").map { afterStr =>
                                val after = afterStr.toLong
                                assert(
                                    after == before,
                                    s"expected __kyoMutCount to remain $before after data-kyo-internal mutations but got $after"
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // Test 2: real DOM mutations still arm the gate after the filter is applied.
    // Proves the filter is narrow: untagged elements still increment __kyoMutCount.
    // Strategy: use evalJsAwaiting (awaitPromise=true) to flush microtasks after the DOM mutation
    // so the MutationObserver callback fires (MutationObserver delivers as a microtask; a
    // synchronous eval cannot observe the post-callback count because microtasks run after the
    // current script task, not between its statements). The async eval yields via setTimeout(0)
    // to let the callback run, then reads the updated count.
    "real DOM mutations still arm the gate after the data-kyo-internal filter" in run {
        withBrowser {
            onPage("<body><div id='real'>initial</div></body>") {
                // The action installs the observer, triggers a real DOM mutation, then yields via
                // setTimeout(0) inside an async block so the MutationObserver microtask fires.
                // Returns "before,after" counts for assertion.
                val action = kyo.internal.BrowserEval.evalJsAwaiting("""(async () => {
                    const before = window.__kyoMutCount || 0;
                    document.getElementById('real').textContent = 'changed';
                    await new Promise(r => setTimeout(r, 0));
                    const after = window.__kyoMutCount || 0;
                    return String(before) + ',' + String(after);
                })()""")
                kyo.internal.MutationSettlement.afterAction(action)(Absent).map { result =>
                    val parts  = result.split(",")
                    val before = parts(0).toLong
                    val after  = parts(1).toLong
                    assert(
                        after > before,
                        s"expected __kyoMutCount to increase from $before after an untagged mutation but got $after (filter must not suppress untagged mutations)"
                    )
                }
            }
        }
    }

    // Test 3: waitForStable returns () once the DOM quiesces.
    // A burst of 5 mutations fires at ~20ms intervals then stops; waitForStable must
    // return within the 2s timeout. Uses Test.timed for the upper-bound assertion.
    "waitForStable returns once the DOM quiesces after a mutation burst" in run {
        withBrowser {
            onPage("""<body>
                <div id='burst'>0</div>
                <script>
                    let n = 0;
                    const id = setInterval(() => {
                        document.getElementById('burst').textContent = String(++n);
                        if (n >= 5) clearInterval(id);
                    }, 20);
                </script>
            </body>""") {
                timed(kyo.internal.MutationSettlement.waitForStable(2.seconds)).map {
                    case (elapsedDur, ()) =>
                        val elapsedMs = elapsedDur.toMillis
                        assert(
                            elapsedMs <= 2500L,
                            s"expected waitForStable to return within 2500ms after quiescence but took ${elapsedMs}ms"
                        )
                }
            }
        }
    }

    // Test 4: waitForStable aborts with BrowserAssertionTimedOutException on a never-quiescing page.
    // A perpetual setInterval mutation at 10ms never lets the observer quiesce; the call must abort
    // with the typed exception. Result shape (not elapsed time) is the deterministic contract.
    "waitForStable aborts BrowserAssertionTimedOutException on a never-quiescing page" in run {
        withBrowser {
            onPage("""<body>
                <div id='churn'>0</div>
                <script>
                    let n = 0;
                    setInterval(() => { document.getElementById('churn').textContent = String(++n); }, 10);
                </script>
            </body>""") {
                Browser.withConfig(_.mutationSettlementTimeout(300.millis)) {
                    Abort.run[BrowserReadException] {
                        kyo.internal.MutationSettlement.waitForStable(300.millis)
                    }.map {
                        case Result.Failure(_: BrowserAssertionTimedOutException) => succeed
                        case other =>
                            fail(s"expected Result.Failure(BrowserAssertionTimedOutException) but got $other")
                    }
                }
            }
        }
    }

    // Test 5: settleForCapture proceeds (returns Success(())) on a perpetually-mutating page.
    // Same never-quiescing fixture as test 4; settleForCapture must recover the timeout to ()
    // and NEVER abort. Asserted via Abort.run shape.
    "settleForCapture returns Result.Success(()) on a never-quiescing page (never aborts)" in run {
        withBrowser {
            onPage("""<body>
                <div id='churn2'>0</div>
                <script>
                    let n = 0;
                    setInterval(() => { document.getElementById('churn2').textContent = String(++n); }, 10);
                </script>
            </body>""") {
                Browser.withConfig(_.mutationSettlementTimeout(300.millis)) {
                    Abort.run[BrowserReadException] {
                        kyo.internal.MutationSettlement.settleForCapture
                    }.map {
                        case Result.Success(()) => succeed
                        case other =>
                            fail(s"expected Result.Success(()) from settleForCapture on never-quiescing page but got $other")
                    }
                }
            }
        }
    }

end BrowserSettlementTest
