package kyo

class BrowserSettlementTest extends BrowserTest:

    override def timeout = 90.seconds

    // ---- goto with explicit settle ----

    "goto with explicit Settle.DomContentLoaded returns after DOMContentLoaded" in {
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

    "expectNavigation completes when the trigger causes a navigation" in {
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

    "expectNavigation aborts with a navigation-failed exception when the trigger does not navigate within the budget" in {
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
                        case Result.Failure(ex: BrowserNavigationFailedException) =>
                            assert(ex.getMessage.contains("Navigation failed"))
                        case other =>
                            fail(s"Expected Result.Failure(BrowserNavigationFailedException) but got $other")
                    }
                }
            }
        }
    }

    "expectNavigation honors failOnHttpError for 4xx responses" in {
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
                        case Result.Failure(ex: BrowserNavigationFailedException) =>
                            assert(ex.url.contains("never-exists"))
                        case other =>
                            fail(s"Expected Result.Failure(BrowserNavigationFailedException) but got $other")
                    }
                }
            }
        }
    }

    // ---- waitForText ----

    "waitForText finds text after delay" in {
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

    "waitForText fails when predicate never satisfied" in {
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
                        case Result.Failure(ex: BrowserAssertionTimedOutException) =>
                            assert(ex.getMessage.contains("Assertion failed"))
                        case other => fail(s"expected BrowserAssertionTimedOutException but got $other")
                    }
                }
            }
        }
    }

    "waitForText retries until non-empty" in {
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

    "waitForAttribute finds attribute after delay" in {
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

    "waitForAttribute fails when predicate never satisfied" in {
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
                        case Result.Failure(ex: BrowserAssertionTimedOutException) =>
                            assert(ex.getMessage.contains("Assertion failed"))
                        case other => fail(s"expected BrowserAssertionTimedOutException but got $other")
                    }
                }
            }
        }
    }

    // ---- waitFor ----

    "waitFor succeeds when JS becomes truthy" in {
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

    "waitFor fails when JS stays falsy" in {
        val p = page("<body><script>window.neverTrue = false;</script></body>")
        withBrowser {
            Browser.goto(p).map { _ =>
                Browser.withConfig(_.retrySchedule(Schedule.fixed(50.millis).maxDuration(500.millis))) {
                    Abort.run[BrowserAssertionException] {
                        Browser.waitFor("window.neverTrue")
                    }.map {
                        case Result.Failure(ex: BrowserAssertionTimedOutException) =>
                            assert(ex.getMessage.contains("Assertion failed"))
                        case other => fail(s"expected BrowserAssertionTimedOutException but got $other")
                    }
                }
            }
        }
    }

    "waitFor returns the truthy value" in {
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
    "click awaits a deferred re-render before returning" in {
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
    "fill awaits debounced validation DOM mutation" in {
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
    "settlement observes mutations across the whole document body and raises on continuous chatter" in {
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
                outcome <-
                    Abort.run[BrowserAssertionException] {
                        Browser.withConfig(_.mutationSettlementTimeout(500.millis))(Browser.click(Browser.Selector.id("b")))
                    }
                chatterCount <- Browser.eval("String(parseInt(document.querySelector('#chatter span').textContent, 10))")
            yield
                val ticks = chatterCount.toIntOption.getOrElse(0)
                assert(
                    ticks >= 5,
                    s"expected chatter to have ticked >= 5 times during the click (proving the unrelated subtree was active) but got $ticks"
                )
                outcome match
                    case Result.Failure(e: BrowserAssertionTimedOutException) =>
                        // Never-quiescing chatter always times out; assert the 500ms deadline the exception carries
                        // (notQuiesced embeds exhausted-deadline nanos in `actual`), not elapsed time.
                        assert(
                            e.actual.contains(s"deadline ${500.millis.toNanos}"),
                            s"expected the 500ms mutationSettlementTimeout to be the exhausted deadline, but the exception reported: ${e.actual}"
                        )
                    case other =>
                        fail(
                            s"expected document-body observer to raise BrowserAssertionTimedOutException on continuous chatter, but got $other"
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
    "mutation settlement raises assertion timeout on pages that never quiesce" in {
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
                Abort.run[BrowserElementException | BrowserAssertionException] {
                    // Widened quiescence window: 5ms-interval churn vs 500ms window; the BrowserLauncher
                    // Chrome flags (--disable-background-timer-throttling, --disable-renderer-backgrounding,
                    // --disable-features=IntensiveWakeUpThrottling) keep the interval firing through the
                    // full 2s mutationSettlementTimeout regardless of tab visibility.
                    Browser.withConfig(_.mutationQuiescenceWindow(500.millis)) {
                        Browser.click(Browser.Selector.id("b"))
                    }
                }.map { result =>
                    kyo.internal.BrowserEval.evalJs("String(window.__tickCount)").map { tickStr =>
                        val tickCount = tickStr.trim.toInt
                        assert(
                            tickCount > 0,
                            s"setInterval churn never ran (window.__tickCount == $tickCount): timer throttling likely regressed"
                        )
                        result match
                            case Result.Failure(e: BrowserAssertionTimedOutException) =>
                                // Never quiesces, so settlement raises on the default timeout; assert the exhausted deadline the
                                // exception carries equals that default, not elapsed time.
                                assert(
                                    e.actual.contains(s"deadline ${Browser.SessionConfig.default.mutationSettlementTimeout.toNanos}"),
                                    s"expected the default mutationSettlementTimeout to be the exhausted deadline, but the exception reported: ${e.actual}"
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
    "Browser.SessionConfig.default.mutationQuiescenceWindow is 50ms" in {
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
    "nested mutations within the quiescence window reset the timer" in {
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
                _   <- Browser.goto(p)
                _   <- Browser.click(Browser.Selector.id("b"))
                out <- Browser.text(Browser.Selector.id("out"))
            yield assert(out == "c", s"expected final mutation 'c' but got '$out'; settlement returned before the last mutation")
            end for
        }
    }

    // The observer is removed after settlement: no leaks.
    // After a click completes, the release scope exits and the ref count drops to 0, disconnecting the observer and deleting the
    // window-level state. `window.__kyoMutObs === undefined` should read 'true'.
    "MutationObserver and window state are cleaned up after settlement" in {
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
    "back-to-back clicks on different subtrees each settle independently" in {
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

    // A bounded retrySchedule maxDuration makes a never-matching waitForText give up with a typed timeout rather than hang.
    "Browser.withConfig(retrySchedule maxDuration 100ms) makes a never-matching waitForText fail, not hang" in {
        withBrowser {
            onPage("<div id='target'>never-changes</div>") {
                Abort.run[BrowserElementException | BrowserAssertionException] {
                    Browser.withConfig(_.retrySchedule(Schedule.fixed(50.millis).maxDuration(100.millis))) {
                        Browser.waitForText(Browser.Selector.css("#target"), _ == "never")
                    }
                }.map { result =>
                    // Reaching a typed timeout at all proves the bound was honored: if maxDuration were ignored,
                    // waitForText would hang into the 90s leaf timeout instead.
                    result match
                        case Result.Failure(_: BrowserAssertionTimedOutException) => succeed
                        case other => fail(s"expected BrowserAssertionTimedOutException but got $other")
                    end match
                }
            }
        }
    }

    // Nested withConfig: inner config is used for the inner call, not the outer.
    "Nested withConfig uses innermost value" in {
        withBrowser {
            onPage("<div id='target'>never-changes</div>") {
                Browser.withConfig(_.retrySchedule(Schedule.fixed(1.hour))) {
                    Abort.run[BrowserElementException | BrowserAssertionException] {
                        Browser.withConfig(_.retrySchedule(Schedule.fixed(50.millis).maxDuration(50.millis))) {
                            Browser.waitForText(Browser.Selector.css("#target"), _ == "never")
                        }
                    }.map { result =>
                        result match
                            case Result.Failure(_: BrowserAssertionTimedOutException) =>
                                // A typed timeout proves the inner 50ms config won: if it were ignored, the outer
                                // effectively-infinite schedule would hang into the leaf timeout.
                                succeed
                            case other => fail(s"expected BrowserAssertionTimedOutException but got $other")
                        end match
                    }
                }
            }
        }
    }

    // Browser.SessionConfig.default has 5s retry schedule.
    "Browser.SessionConfig.default retrySchedule has 5s maxDuration" in {
        Browser.configLocal.use { cfg =>
            val schedule = cfg.retrySchedule
            assert(
                schedule == Browser.SessionConfig.default.retrySchedule,
                s"Expected default retrySchedule at outermost scope but got $schedule"
            )
        }
    }

    // withConfig retrySchedule unbounded: a slow-but-eventually-true waitForText succeeds.
    "Browser.withConfig(unbounded retrySchedule) with eventually-true waitForText succeeds" in {
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
    "waitForText and assertExists honor withConfig retrySchedule" in {
        withBrowser {
            onPage("<div id='anchor'>anchor</div>") {
                // Outer schedule is effectively-infinite: an op that ignored the inner short config would fall back to it
                // and hang. Each op's typed Failure is the proof it did not.
                Browser.withConfig(_.retrySchedule(Schedule.fixed(1.hour))) {
                    Browser.withConfig(_.retrySchedule(Schedule.fixed(50.millis).maxDuration(100.millis))) {
                        Abort.run[BrowserElementException | BrowserAssertionException] {
                            Browser.waitForText(Browser.Selector.css("#anchor"), _ == "never-ever")
                        }.map { r =>
                            r match
                                case Result.Failure(_: BrowserAssertionTimedOutException) =>
                                    succeed
                                case other => fail(s"waitForText: expected BrowserAssertionTimedOutException but got $other")
                        }.andThen(
                            Abort.run[BrowserElementException] {
                                Browser.assertExists(Browser.Selector.css("#ghost-element"))
                            }.map { r =>
                                r match
                                    case Result.Failure(_: BrowserElementNotFoundException) =>
                                        succeed
                                    case other => fail(s"assertExists: expected BrowserElementNotFoundException but got $other")
                            }
                        ).andThen(
                            Abort.run[BrowserElementException] {
                                Browser.click(Browser.Selector.css("#ghost-element"))
                            }.map { r =>
                                r match
                                    case Result.Failure(_: BrowserElementException) =>
                                        succeed
                                    case other => fail(s"click: expected BrowserElementException but got $other")
                            }
                        ).andThen(
                            Abort.run[BrowserElementException] {
                                Browser.fill(Browser.Selector.css("#ghost-input"), "hi")
                            }.map { r =>
                                r match
                                    case Result.Failure(_: BrowserElementException) =>
                                        succeed
                                    case other => fail(s"fill: expected BrowserElementException but got $other")
                            }
                        ).map(_ => ())
                    }
                }
            }
        }
    }

    // Sibling fibers in Async.zip each see their own withConfig scope.
    "Sibling Async.zip fibers see their own withConfig scope" in {
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
                    // Effectively-infinite maxDuration, not an infinite poll: the 50ms poll still matches #slow at
                    // 700ms; the 1-hour cap only bites a fiber that never matches, discriminating the hang below.
                    Browser.withConfig(_.retrySchedule(Schedule.fixed(50.millis).maxDuration(1.hour))) {
                        Browser.goto(slowPage).andThen {
                            Browser.waitForText(Browser.Selector.css("#slow"), _ == "arrived").map { result =>
                                assert(result == "arrived", s"Slow fiber: expected 'arrived' but got '$result'")
                                result
                            }
                        }
                    },
                    // #fast never matches, so on its own 100ms budget it aborts fast. If withConfig leaked the sibling's
                    // 1-hour cap across the Async.zip, it would retry for an hour and hang; the typed abort proves it did not.
                    Browser.withConfig(_.retrySchedule(Schedule.fixed(50.millis).maxDuration(100.millis))) {
                        Browser.goto(fastPage).andThen {
                            Abort.run[BrowserElementException | BrowserAssertionException] {
                                Browser.waitForText(Browser.Selector.css("#fast"), _ == "never-match")
                            }.map { result =>
                                result match
                                    case Result.Failure(_: BrowserAssertionTimedOutException) => succeed
                                    case other => fail(s"Tight fiber: expected BrowserAssertionTimedOutException but got $other")
                                end match
                            }
                        }
                    }
                ).map { (_, _) => () }
            }
        }
    }

    // ---- Settle.Load + signature normalization + catalog deliverable ----

    "Settle.Load is accepted by goto and the back/forward/reload paths".flaky in {
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

    "Settle.NetworkIdle degrades to Load when load fires but network never quiesces" in {
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

    "same-URL Browser.goto after a typed 4xx failure succeeds (no-op when already on target URL)" in {
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
                        case Result.Failure(ex: BrowserNavigationFailedException) =>
                            assert(ex.url.contains("/missing"))
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

    "waitForUrl returns matched URL when navigation happens after a delay" in {
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

    "waitForTitle returns matched title when document.title is set via JS after a delay" in {
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

    "waitForCount returns the first stable matching count when items are appended via setInterval" in {
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

    "waitForVisible returns Unit when display:none is removed asynchronously" in {
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

    "waitForExists returns Unit when the element is appended via setTimeout" in {
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

    "waitForText equality overload resolves once the text matches" in {
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

    "waitForAttribute equality overload resolves once the attribute matches" in {
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
    "Settle.NetworkIdle waits for chatty fetches to quiesce (3-fetch positive case)" in {
        // Three deferred fetches plus a same-origin /ping endpoint. data: URLs cannot host cross-origin fetch targets without CORS
        // gymnastics, so the localhost server pattern mirrors the line ~746 NetworkIdle degradation test.
        val html =
            """<!doctype html><html><body><h1>three-fetch</h1>
              |<script>
              |  window.__done = 0;
              |  document.addEventListener('DOMContentLoaded', () => {
              |    setTimeout(() => { fetch('/ping?n=1').then(() => { window.__done++; }, () => { window.__done++; }); }, 100);
              |    setTimeout(() => { fetch('/ping?n=2').then(() => { window.__done++; }, () => { window.__done++; }); }, 200);
              |    setTimeout(() => { fetch('/ping?n=3').then(() => { window.__done++; }, () => { window.__done++; }); }, 300);
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
                    for
                        _    <- Browser.goto(s"http://$host:$port/", Browser.Settle.NetworkIdle)
                        done <- Browser.eval("String(window.__done)")
                    yield
                        // NetworkIdle must not return on Load alone: all three deferred fetches (100/200/300ms) must have
                        // completed, so the completion counter is a stable 3 at return. A state read, not a stopwatch.
                        assert(
                            done.trim == "3",
                            s"Settle.NetworkIdle must wait for the 3-fetch burst to complete, but window.__done=$done at return"
                        )
                    end for
                }
            }
        }
    }

    // ── Settle.Load with slow <img> ──────────────────────────────────────────

    /** Empirical property: `Browser.goto(p, Settle.Load)` waits for the `load` event, which only fires after every subresource (including
      * `<img>`) finishes. With a server-delayed image, elapsed must be >= the server delay; an early return would mean `Settle.Load` is
      * firing on DOMContentLoaded.
      */
    "Settle.Load waits for slow <img> subresource to load before returning" in {
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
                for
                    _        <- Browser.goto(s"http://$host:$port/", Browser.Settle.Load)
                    complete <- Browser.eval("String(document.images[0].complete && document.images[0].naturalWidth > 0)")
                yield
                    // Load fires only after every subresource finishes, so the slow <img> must be fully decoded at return.
                    // An early return on DOMContentLoaded leaves complete === false (the regression this targets).
                    assert(
                        complete.trim == "true",
                        s"Settle.Load must wait for the slow <img> subresource to finish, but images[0].complete=$complete at return"
                    )
                end for
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

    /** A tight `mutationQuiescenceWindow(10.millis)` releases in the first gap of 30ms-spaced mutations, so `#root` shows an early token
      * (never the final 'e') at click return; the 500ms-window foil below asserts the opposite. The contrast is the claim, no clock.
      */
    "mutationQuiescenceWindow(10ms) lets 30ms-spaced mutations resolve in the first window" in {
        val p = page(quiescenceMatrixHtml)
        withBrowser {
            for
                _         <- Browser.goto(p)
                _         <- Browser.withConfig(_.mutationQuiescenceWindow(10.millis))(Browser.click(Browser.Selector.id("trigger")))
                finalText <- Browser.text(Browser.Selector.id("root"))
            yield
                // A broken tight window would absorb later mutations and return only after 'e' landed; the early-token read fails
                // then. The wide-window foil at :1092 asserts 'e', so the contrast pins the window config, no clock.
                assert(
                    finalText != "e",
                    s"mutationQuiescenceWindow(10ms) must release before the final mutation 'e' lands, but observed '$finalText'"
                )
            end for
        }
    }

    /** Empirical property: with a wide `mutationQuiescenceWindow(500.millis)` and 30ms-spaced mutations (all 5 fall inside the window),
      * settlement waits for the LAST mutation + 500ms quiet. Elapsed must exceed the wide window's lower bound; an early return would mean
      * the window was ignored.
      */
    "mutationQuiescenceWindow(500ms) waits for all 30ms-spaced mutations to quiesce" in {
        val p = page(quiescenceMatrixHtml)
        withBrowser {
            for
                _         <- Browser.goto(p)
                _         <- Browser.withConfig(_.mutationQuiescenceWindow(500.millis))(Browser.click(Browser.Selector.id("trigger")))
                finalText <- Browser.text(Browser.Selector.id("root"))
            yield
                // A wide 500ms window absorbs every 30ms-spaced mutation, so settlement returns only after 'e' lands. Paired with the
                // tight-window leaf above (releases before 'e'), the differing final text proves the window governs release, no clock.
                assert(finalText == "e", s"expected the last mutation 'e' to land before settlement returns but got '$finalText'")
            end for
        }
    }

    // ── Custom mutationSettlementTimeout(500ms) ──────────────────────────────

    /** Empirical property: setting `mutationSettlementTimeout(500.millis)` shortens the never-quiesce timeout from the default 2s. With
      * 20ms-spaced mutations against the default 50ms quiescence window (never quiesces), settlement raises
      * `BrowserAssertionTimedOutException` after ~500ms, NOT 2s.
      */
    "mutationSettlementTimeout(500ms) shortens the never-quiesce timeout" in {
        // Mirror the never-quiesce shape used by the default-timeout test (settlement raises
        // BrowserAssertionTimedOutException on chatty pages). Combine a wide mutationQuiescenceWindow(500ms)
        // (so the observer never sees a quiet 500ms gap inside the 5ms-interval churn) with the custom
        // mutationSettlementTimeout(500ms). The default-timeout foil pins the same churn to the default 2s
        // timeout. Pinning to 500ms proves the override applies: the exception reports the 500ms deadline it exhausted, not 2s.
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
                Abort.run[BrowserElementException | BrowserAssertionException] {
                    Browser.withConfig(_.mutationQuiescenceWindow(500.millis).mutationSettlementTimeout(500.millis)) {
                        Browser.click(Browser.Selector.id("b"))
                    }
                }.map { result =>
                    result match
                        case Result.Failure(e: BrowserAssertionTimedOutException) =>
                            // Never-quiesce page always times out; assert the exhausted deadline is the 500ms override, not the
                            // default 2s (notQuiesced embeds it as nanos in `actual`). Reads which config applied, not elapsed.
                            assert(
                                e.actual.contains(s"deadline ${500.millis.toNanos}"),
                                s"mutationSettlementTimeout(500ms) override should be the exhausted deadline, but the exception reported: ${e.actual}"
                            )
                        case other =>
                            fail(s"expected BrowserAssertionTimedOutException after the 500ms timeout but got $other")
                    end match
                }
            }
        }
    }

    // ── data-kyo-internal filter + waitForStable + settleForCapture ──

    // Test 1: injecting a data-kyo-internal node leaves __kyoMutCount unchanged.
    // The observer is installed via a no-op afterAction call so __kyoMutCount and the
    // observer exist; subsequent mutations inside the tagged subtree are filtered out
    // and must not change the counter.
    "data-kyo-internal mutations do not arm the settlement gate" in {
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
    "real DOM mutations still arm the gate after the data-kyo-internal filter" in {
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

    // Test 2b: APPENDING a data-kyo-internal root to document.body does NOT arm the gate.
    // This is the overlay-injection case: the childList record's target is the UNTAGGED parent (document.body) and the
    // tagged node is in addedNodes, so the original target-ancestor-only filter would have armed the gate. The extended
    // filter treats a childList record as transparent when every added/removed node is (or is within) a tagged subtree.
    // evalJsAwaiting flushes the MutationObserver microtask (via setTimeout(0)) before reading the post-callback count.
    "appending a data-kyo-internal node does not arm the settlement gate" in {
        withBrowser {
            onPage("<body><div id='real'>initial</div></body>") {
                val action = kyo.internal.BrowserEval.evalJsAwaiting("""(async () => {
                    const before = window.__kyoMutCount || 0;
                    const d = document.createElement('div');
                    d.setAttribute('data-kyo-internal', 'overlay');
                    d.id = 'kyo-overlay-root';
                    const child = document.createElement('span');
                    child.textContent = 'box';
                    d.appendChild(child);
                    document.body.appendChild(d);
                    await new Promise(r => setTimeout(r, 0));
                    const after = window.__kyoMutCount || 0;
                    return String(before) + ',' + String(after);
                })()""")
                kyo.internal.MutationSettlement.afterAction(action)(Absent).map { result =>
                    val parts  = result.split(",")
                    val before = parts(0).toLong
                    val after  = parts(1).toLong
                    assert(
                        after == before,
                        s"expected __kyoMutCount to remain $before after appending a data-kyo-internal node but got $after"
                    )
                }
            }
        }
    }

    // Test 2c: REMOVING a data-kyo-internal root from document.body does NOT arm the gate.
    // The childList record's target is document.body (untagged) and the tagged node is in removedNodes. A detached removed
    // tagged element still satisfies closest('[data-kyo-internal]') on itself, so the extended filter treats the removal as
    // transparent. Pre-seeds the tagged root before the observer is installed so the only mutation under measurement is the
    // removal.
    "removing a data-kyo-internal node does not arm the settlement gate" in {
        withBrowser {
            onPage("<body><div id='real'>initial</div></body>") {
                // Pre-seed the tagged overlay root before installing the observer.
                Browser.eval("""(() => {
                    const d = document.createElement('div');
                    d.setAttribute('data-kyo-internal', 'overlay');
                    d.id = 'kyo-overlay-root';
                    document.body.appendChild(d);
                    return 'seeded';
                })()""").andThen {
                    val action = kyo.internal.BrowserEval.evalJsAwaiting("""(async () => {
                        const before = window.__kyoMutCount || 0;
                        const d = document.getElementById('kyo-overlay-root');
                        document.body.removeChild(d);
                        await new Promise(r => setTimeout(r, 0));
                        const after = window.__kyoMutCount || 0;
                        return String(before) + ',' + String(after);
                    })()""")
                    kyo.internal.MutationSettlement.afterAction(action)(Absent).map { result =>
                        val parts  = result.split(",")
                        val before = parts(0).toLong
                        val after  = parts(1).toLong
                        assert(
                            after == before,
                            s"expected __kyoMutCount to remain $before after removing a data-kyo-internal node but got $after"
                        )
                    }
                }
            }
        }
    }

    // Test 2d: appending a NON-tagged node STILL arms the gate (regression guard for the childList filter extension).
    // Proves the extended filter is narrow: a childList record whose added node is untagged increments __kyoMutCount.
    "appending a non-tagged node still arms the settlement gate" in {
        withBrowser {
            onPage("<body><div id='real'>initial</div></body>") {
                val action = kyo.internal.BrowserEval.evalJsAwaiting("""(async () => {
                    const before = window.__kyoMutCount || 0;
                    const d = document.createElement('div');
                    d.id = 'plain-node';
                    d.textContent = 'plain';
                    document.body.appendChild(d);
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
                        s"expected __kyoMutCount to increase from $before after appending an untagged node but got $after (the filter must not suppress untagged insertions)"
                    )
                }
            }
        }
    }

    // Test 3: waitForStable returns () once the DOM quiesces.
    // A burst of 5 mutations at ~20ms then stops; waitForStable must return once the DOM quiesces. The
    // effectively-infinite timeout makes a broken quiescence detection hang into the leaf timeout instead.
    "waitForStable returns once the DOM quiesces after a mutation burst" in {
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
                kyo.internal.MutationSettlement.waitForStable(1.hour).map { _ =>
                    succeed
                }
            }
        }
    }

    // Test 4: waitForStable aborts with BrowserAssertionTimedOutException on a never-quiescing page.
    // A perpetual setInterval mutation at 10ms never lets the observer quiesce; the call must abort
    // with the typed exception. Result shape (not elapsed time) is the deterministic contract.
    "waitForStable aborts BrowserAssertionTimedOutException on a never-quiescing page" in {
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
    "settleForCapture returns Result.Success(()) on a never-quiescing page (never aborts)" in {
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
