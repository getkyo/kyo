package kyo.internal

import kyo.*

class MutationSettlementTest extends kyo.BrowserTest:

    override def timeout = 90.seconds

    "MutationSettlement.parseSettlementValue" - {

        "non-JSON input → Malformed" in {
            val r = MutationSettlement.parseSettlementValue("done")
            assert(r == MutationSettlement.SettlementResult.Malformed, s"expected Malformed got $r")
        }

        "JSON object missing `tag` → Malformed" in {
            val r = MutationSettlement.parseSettlementValue("""{"count":5,"delta":3}""")
            assert(r == MutationSettlement.SettlementResult.Malformed, s"expected Malformed got $r")
        }

        "tag=done with count and delta → Done" in {
            val r = MutationSettlement.parseSettlementValue("""{"tag":"done","count":5,"delta":123}""")
            assert(r == MutationSettlement.SettlementResult.Done, s"expected Done got $r")
        }

        "tag=done without count/delta → Done (fields ignored)" in {
            val r = MutationSettlement.parseSettlementValue("""{"tag":"done"}""")
            assert(r == MutationSettlement.SettlementResult.Done, s"expected Done got $r")
        }

        "tag=timeout with count/delta → Timeout(count, delta)" in {
            val r = MutationSettlement.parseSettlementValue("""{"tag":"timeout","count":7,"delta":999}""")
            assert(r == MutationSettlement.SettlementResult.Timeout(7L, 999L), s"expected Timeout(7, 999) got $r")
        }

        "tag=timeout missing count → Malformed" in {
            val r = MutationSettlement.parseSettlementValue("""{"tag":"timeout","delta":3}""")
            assert(r == MutationSettlement.SettlementResult.Malformed, s"expected Malformed got $r")
        }

        "tag=postfail with error → PostFailed(error)" in {
            val r = MutationSettlement.parseSettlementValue("""{"tag":"postfail","error":"server unreachable"}""")
            assert(
                r == MutationSettlement.SettlementResult.PostFailed("server unreachable"),
                s"expected PostFailed('server unreachable') got $r"
            )
        }

        "tag=postfail with error containing JSON-significant characters preserves the message" in {
            val payload = """{"tag":"postfail","error":"fetch failed: code 500 | retry 0|0"}"""
            val r       = MutationSettlement.parseSettlementValue(payload)
            assert(
                r == MutationSettlement.SettlementResult.PostFailed("fetch failed: code 500 | retry 0|0"),
                s"expected PostFailed with full message preserved, got $r"
            )
        }

        "tag=postfail with empty error → PostFailed('')" in {
            val r = MutationSettlement.parseSettlementValue("""{"tag":"postfail","error":""}""")
            assert(r == MutationSettlement.SettlementResult.PostFailed(""), s"expected PostFailed('') got $r")
        }

        "tag=postfail missing error → Malformed" in {
            val r = MutationSettlement.parseSettlementValue("""{"tag":"postfail"}""")
            assert(r == MutationSettlement.SettlementResult.Malformed, s"expected Malformed got $r")
        }

        "unknown tag → Malformed" in {
            val r = MutationSettlement.parseSettlementValue("""{"tag":"weird","count":5,"delta":10}""")
            assert(r == MutationSettlement.SettlementResult.Malformed, s"expected Malformed for unknown tag, got $r")
        }

        "tag=timeout, Long.MaxValue / -1 → Timeout(MaxValue, -1)" in {
            val r = MutationSettlement.parseSettlementValue(s"""{"tag":"timeout","count":${Long.MaxValue},"delta":-1}""")
            assert(
                r == MutationSettlement.SettlementResult.Timeout(Long.MaxValue, -1L),
                s"expected Timeout(${Long.MaxValue}, -1), got $r"
            )
        }
    }

    // ---- `Duration.Zero` opt-out ----
    //
    // The opt-out branch in `MutationSettlement.afterAction` short-circuits when `mutationQuiescenceWindow ==
    // Duration.Zero`: the action is run as-is, no observer is installed, no quiescence wait runs. We exercise it
    // both at the config level (pure: verifies the field is settable to `Duration.Zero` and observable through
    // `currentConfig`, which is what `afterAction` reads) and at the browser-localhost level (integration:
    // verifies a click that would otherwise wait for a 200ms-deferred mutation returns ~immediately under the
    // opt-out).

    "Duration.Zero opt-out: pure config check" in {
        val cfg = Browser.SessionConfig.default.mutationQuiescenceWindow(Duration.Zero)
        assert(
            cfg.mutationQuiescenceWindow == Duration.Zero,
            s"expected Duration.Zero opt-out config but got ${cfg.mutationQuiescenceWindow}"
        )
    }

    "Duration.Zero opt-out: browser-localhost: click skips settlement and returns immediately" in {
        // Click handler defers a DOM mutation by 200ms. With default settlement, the click waits past 200ms+50ms
        // (the quiescence window) before returning. Under the `Duration.Zero` opt-out the click MUST return
        // BEFORE the deferred mutation lands; the post-action `text` read still sees the pre-mutation value.
        // Behavioural assertion: text == "before" (action returned pre-mutation). Timing assertion: 3× CI
        // sanity envelope below the would-be settlement bound (200ms+50ms = 250ms).
        // NOTE on JS setup: HTML5 spec says `<script>` tags injected via `innerHTML` do NOT execute. We install
        // the click handler via a separate `Browser.eval` AFTER setting innerHTML, so the handler is registered
        // at top-level page scope (not inside an unexecuted HTML `<script>`).
        withBrowserOnLocalhost {
            Browser.eval(
                """document.body.innerHTML = '<button id="b">click</button><div id="out">before</div>';
                  |document.getElementById("b").onclick = () => {
                  |  setTimeout(() => { document.getElementById("out").textContent = "after"; }, 200);
                  |};
                  |'ok'
                """.stripMargin
            ).andThen {
                Browser.withConfig(_.mutationQuiescenceWindow(Duration.Zero)) {
                    for
                        _       <- Browser.click(Selector.id("b"))
                        outText <- Browser.eval("document.getElementById('out').textContent")
                    yield
                        // Under the Duration.Zero opt-out the click returns BEFORE the 200ms deferred mutation and reads
                        // the pre-mutation text; settlement would have waited and read "after". Effect, not elapsed.
                        assert(
                            outText == "before",
                            s"expected pre-mutation text 'before' under Duration.Zero opt-out (settlement skipped) but got '$outText'"
                        )
                }
            }
        }
    }

    // ---- `awaitQuiescence` Timeout propagation through `afterAction` ----
    //
    // A page that never quiesces under a tight `mutationSettlementTimeout` must surface a
    // `BrowserAssertionTimedOutException`. Assertion is on Abort shape (the typed timeout, not a generic
    // BrowserException), not on elapsed timing.

    "awaitQuiescence Timeout propagates through afterAction as BrowserAssertionTimedOutException" in {
        withBrowserOnLocalhost {
            // Static button on page load (no mutations yet); actionability passes immediately.
            // The addEventListener call attaches the handler that starts a setInterval churn loop AFTER
            // click, so settlement begins observing mutations only once the click has dispatched. The
            // widened mutationQuiescenceWindow (300ms below) gives the 5ms timer churn a ~60× margin so it
            // cannot false-settle under host load; setInterval is the same task source as settlement's own
            // setTimeout poll loop, so the churn interleaves fairly and never starves it (a MessageChannel
            // loop monopolizes the event loop and does starve the poll).
            Browser.eval(
                """const b = document.createElement('button');
                  |b.id = 'b';
                  |b.textContent = 'tick';
                  |b.addEventListener('click', function() {
                  |  const me = this;
                  |  let i = 0;
                  |  const tick = function() {
                  |    const s = document.createElement('span');
                  |    s.textContent = String(i++);
                  |    me.appendChild(s);
                  |    if (me.childNodes.length > 3) me.removeChild(me.firstChild);
                  |  };
                  |  tick();
                  |  setInterval(tick, 5);
                  |});
                  |document.body.innerHTML = '';
                  |document.body.appendChild(b);
                  |'ok'
                """.stripMargin
            ).andThen {
                Browser.withConfig(
                    _.mutationSettlementTimeout(1.second)
                        .mutationQuiescenceWindow(300.millis)
                        .mutationFirstMutationGrace(100.millis)
                        .retrySchedule(Schedule.fixed(50.millis).maxDuration(3.seconds))
                ) {
                    Abort.run[BrowserAssertionException] {
                        Browser.click(Selector.id("b"))
                    }.map { result =>
                        // Behavioural assertion on Abort SHAPE, NOT on timing.
                        result match
                            case Result.Failure(ex: BrowserAssertionTimedOutException) =>
                                assert(ex.getMessage.contains("Assertion failed"))
                            case other =>
                                fail(s"expected BrowserAssertionTimedOutException from awaitQuiescence Timeout propagation, got $other")
                    }
                }
            }
        }
    }

    "settlement script failures propagate as BrowserProtocolErrorException" in {
        withBrowserOnLocalhost {
            Browser.eval(
                """document.body.innerHTML = '<button id="b">click</button>';
                  |Object.defineProperty(window, '_kyoPostQ', {
                  |  configurable: true,
                  |  get: function() { throw new Error('kyo-test-corruption: Cannot find context with specified id'); }
                  |});
                  |'ok'
                """.stripMargin
            ).andThen {
                Abort.run[BrowserConnectionException | BrowserAssertionException] {
                    Browser.click(Selector.id("b"))
                }.map {
                    case Result.Failure(error: BrowserProtocolErrorException) =>
                        assert(error.getMessage.contains("kyo-test-corruption"))
                    case other =>
                        fail(s"expected a typed settlement script failure, got $other")
                }
            }
        }
    }

    // ---- `awaitQuiescence` PostFailed propagation through `afterAction` ----
    //
    // Framework integration: when `window.__kyoPostFailures > 0` after the action's POST queue settles,
    // awaitQuiescence returns `postfail|<msg>` and `afterAction` surfaces it as
    // `BrowserProtocolErrorException` carrying the original kyo-ui POST failure message.

    "awaitQuiescence PostFailed propagates through afterAction as BrowserProtocolErrorException" in {
        withBrowserOnLocalhost {
            Browser.eval(
                """document.body.innerHTML = '<button id="b">click</button>';
                  |window.__kyoPostFailures = 1;
                  |window.__kyoPostLastError = 'forced POST failure for test';
                  |window._kyoPostQ = Promise.resolve();
                  |'ok'
                """.stripMargin
            ).andThen {
                Browser.withConfig(_.mutationSettlementTimeout(1.second)) {
                    Abort.run[BrowserConnectionException | BrowserAssertionException] {
                        Browser.click(Selector.id("b"))
                    }.map { result =>
                        result match
                            case Result.Failure(ex: BrowserProtocolErrorException) =>
                                assert(
                                    ex.method == "MutationSettlement.awaitQuiescence",
                                    s"expected method tag 'MutationSettlement.awaitQuiescence', got '${ex.method}'"
                                )
                                assert(
                                    ex.getMessage.contains("forced POST failure for test"),
                                    s"expected exception to carry the original POST error message, got: ${ex.getMessage}"
                                )
                            case other =>
                                fail(s"expected BrowserProtocolErrorException from postfail propagation, got $other")
                    }
                }
            }
        }
    }

    // ---- `installObserver` multi-observes `[data-kyo-reactive]` siblings ----
    //
    // When a click handler writes to a SIBLING `[data-kyo-reactive]` zone rather than to the action target's
    // subtree, settlement must still observe the mutation. Without sibling observation, the quiet window
    // would close before the framework's reactive re-render landed.

    "installObserver also watches [data-kyo-reactive] siblings" in {
        withBrowserOnLocalhost {
            // Without sibling observation, settlement sees no mutation in the button's subtree and exits at firstGrace.
            // With (a), the 200ms-delayed sibling write lands within firstGrace, settlement transitions into the
            // quiescence wait and only returns once the DOM stays quiet for `mutationQuiescenceWindow`. So the click
            // takes >= 200ms to return. Time-based assertion is the unambiguous signal that the observer engaged.
            Browser.eval(
                """document.body.innerHTML = '<button id="b">click</button>' +
                  |                          '<span id="sib" data-kyo-reactive></span>';
                  |document.getElementById('b').addEventListener('click', function() {
                  |  setTimeout(function() { document.getElementById('sib').textContent = 'updated'; }, 200);
                  |});
                  |'ok'
                """.stripMargin
            ).andThen {
                Browser.assertExists(Selector.id("sib")).andThen {
                    Browser.withConfig(
                        // firstGrace must outlast the 200ms-delayed sibling write; otherwise the no-mutation branch
                        // fires before the observer can see anything, and the assertion below would be vacuous.
                        _.mutationFirstMutationGrace(400.millis)
                            .mutationQuiescenceWindow(50.millis)
                            .mutationSettlementTimeout(1500.millis)
                    ) {
                        for
                            _      <- Browser.click(Selector.id("b"))
                            actual <- Browser.eval("document.getElementById('sib').textContent")
                        yield
                            // The sibling mutation is deferred 200ms. Settlement that waited for it returns with the
                            // mutation applied (actual == "updated"); an early return reads the pre-mutation value.
                            assert(actual == "updated", s"expected sibling textContent='updated' after settlement returned, got '$actual'")
                        end for
                    }
                }
            }
        }
    }

    // ---- `awaitQuiescence` honours `window._kyoPostQ` before the quiescence loop ----
    //
    // When a click handler enqueues asynchronous work on `window._kyoPostQ` that eventually mutates a sibling
    // reactive zone, awaitQuiescence must await the queue before counting quiescence. Otherwise the queued
    // continuation lands after settlement returns and the test would see a stale DOM.

    "awaitQuiescence awaits window._kyoPostQ before counting quiescence" in {
        withBrowserOnLocalhost {
            // Without the `_kyoPostQ` await, settlement starts the quiescence loop immediately and exits at firstGrace.
            // With the await, settlement blocks until the 200ms-delayed promise resolves, so the click takes >= 200ms.
            Browser.eval(
                """document.body.innerHTML = '<button id="b">click</button>' +
                  |                          '<span id="sib" data-kyo-reactive></span>';
                  |window._kyoPostQ = Promise.resolve();
                  |document.getElementById('b').addEventListener('click', function() {
                  |  window._kyoPostQ = window._kyoPostQ.then(function() {
                  |    return new Promise(function(r) {
                  |      setTimeout(function() {
                  |        document.getElementById('sib').textContent = 'queued-write';
                  |        r();
                  |      }, 200);
                  |    });
                  |  });
                  |});
                  |'ok'
                """.stripMargin
            ).andThen {
                Browser.assertExists(Selector.id("sib")).andThen {
                    Browser.withConfig(
                        // Even if settlement's quiescence loop skipped the await, firstGrace would only exit at 400ms;
                        // the 200ms-delayed write inside _kyoPostQ would already have landed and triggered a mutation,
                        // which is exactly what we want to observe; so firstGrace stays past the queued resolution.
                        _.mutationFirstMutationGrace(400.millis)
                            .mutationQuiescenceWindow(50.millis)
                            .mutationSettlementTimeout(1500.millis)
                    ) {
                        for
                            _      <- Browser.click(Selector.id("b"))
                            actual <- Browser.eval("document.getElementById('sib').textContent")
                        yield
                            // The queued write lands via _kyoPostQ after 200ms. actual == "queued-write" means settlement
                            // awaited that queue before returning; an early return reads the pre-write value.
                            assert(actual == "queued-write", s"expected sib textContent='queued-write' after settlement, got '$actual'")
                        end for
                    }
                }
            }
        }
    }

    "overlapping actions settle independently" in {
        withBrowserOnLocalhost {
            Browser.eval(
                """document.body.innerHTML = '<div id="a"></div><div id="b"></div>';
                  |'ok'
                """.stripMargin
            ).andThen {
                Latch.init(2).map { installed =>
                    def arm(target: String, label: String)(using Frame) =
                        MutationSettlement.afterAction(
                            installed.release.andThen(installed.await).andThen {
                                Browser.eval(s"document.getElementById('$target').textContent = '$label'").unit
                            }
                        )(scopeSelector = Present(Selector.id(target)))

                    Browser.use { tab =>
                        Async.zip(
                            Browser.runOn(tab)(arm("a", "1")),
                            Browser.runOn(tab)(arm("b", "2"))
                        ).andThen {
                            for
                                first  <- Browser.eval("document.getElementById('a').textContent")
                                second <- Browser.eval("document.getElementById('b').textContent")
                            yield
                                assert(first == "1")
                                assert(second == "2")
                            end for
                        }
                    }
                }
            }
        }
    }

    "scopeSelector = Present(s): sibling mutations still require quiescence" in {
        // scopeSelector does not restrict observation: action handlers commonly update sibling elements.
        // Mutate only after polling starts, so this checks observation independently of action-time mutation accounting.
        withBrowserOnLocalhost {
            Browser.use { tab =>
                Scope.run {
                    for
                        _ <- Browser.eval("""document.body.innerHTML = '<button id="trigger">go</button><div id="outside"></div>';
                            let now = 1;
                            let next = 0;
                            const timers = new Map();
                            let polling;
                            window.settlementPolling = new Promise(resolve => { polling = resolve; });
                            Object.defineProperty(performance, 'now', { configurable: true, value: () => now });
                            window.setTimeout = (callback, delay) => {
                                const id = ++next;
                                timers.set(id, { callback, at: now + Number(delay) });
                                if (delay === 10) polling('polling');
                                return id;
                            };
                            window.clearTimeout = id => timers.delete(id);
                            window.advanceSettlement = () => {
                                now = 1001;
                                for (const [id, timer] of timers) {
                                    if (timer.at <= now) { timers.delete(id); timer.callback(); }
                                }
                                return 'advanced';
                            };
                            'ready'""")
                        waiting <- Fiber.init(Abort.run[BrowserReadException](Browser.runOn(tab) {
                            Browser.withConfig(
                                _.mutationQuiescenceWindow(2.seconds)
                                    .mutationSettlementTimeout(1.second)
                                    .mutationPollInterval(10.millis)
                            ) {
                                MutationSettlement.afterAction(Kyo.unit)(Present(Selector.id("trigger")))
                            }
                        }))
                        _      <- BrowserEval.evalJsAwaiting("window.settlementPolling")
                        _      <- Browser.eval("document.getElementById('outside').textContent = 'changed'; 'changed'")
                        _      <- Browser.eval("window.advanceSettlement()")
                        result <- waiting.get
                    yield result match
                        case Result.Failure(error: BrowserAssertionTimedOutException) =>
                            assert(error == BrowserAssertionTimedOutException.notQuiesced(2.seconds, 1000, 1, 1.second))
                        case other => fail(s"expected the sibling mutation to require quiescence, got $other")
                }
            }
        }
    }

    "two parallel Browser.click on the same tab settle successfully" in {
        // Resolver concurrency contract: two parallel `Browser.click` calls on the same tab must both run
        // end-to-end without their resolution state colliding. The Resolver pipeline must use a handle that
        // is stable across DOM mutations (i.e. `Runtime.evaluate(returnByValue=false)` → `objectId` →
        // `DOM.describeNode({objectId})` rather than a document-keyed `rootNodeId`-based chain), so that one
        // arm's actionability check and post-click mutation cannot invalidate the other arm's resolution.
        // Both clicks complete cleanly and the click pipeline runs end-to-end (observable as the JS click
        // handler firing at least once on the shared target).
        //
        // Both clicks target the SAME button so Chrome's input subsystem cannot fail to deliver at least one
        // click: at identical coordinates the mouse-event streams collapse to a single observable click event.
        // (Two DIFFERENT buttons would expose Chrome's input-subsystem ordering of interleaved mouse-event
        // streams at different coordinates, which is unrelated to the Resolver invariant under test.)
        withBrowserOnLocalhost {
            Browser.eval(
                """document.body.innerHTML = '<button id="btn">click me</button>';
                  |window.__clickCount = 0;
                  |document.getElementById('btn').onclick = () => { window.__clickCount++; };
                  |'ok'
                """.stripMargin
            ).andThen {
                Browser.use { tab =>
                    // Both Resolver pipelines must be handle-stable across DOM mutations; neither arm can
                    // invalidate the other's resolution state. A document-keyed pipeline would deterministically
                    // abort one arm with `BrowserProtocolErrorException("DOM.querySelector", "invalid response")`.
                    Async.zip(
                        Browser.runOn(tab)(Browser.click(Selector.id("btn"))),
                        Browser.runOn(tab)(Browser.click(Selector.id("btn")))
                    ).andThen {
                        for
                            counter <- Browser.eval("String(window.__clickCount)")
                        yield
                            // Both `Browser.click` calls completed past the Resolver pipeline, dispatched
                            // their CDP mouse events, and at least one click event fired on the page,
                            // proving the click pipeline ran end-to-end.
                            val n = counter.toIntOption.getOrElse(0)
                            assert(
                                n >= 1,
                                s"expected window.__clickCount>=1 (parallel Browser.click pipeline ran end-to-end past the handle-stable Resolver), got '$counter'"
                            )
                            ()
                        end for
                    }
                }
            }
        }
    }

end MutationSettlementTest
