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
                        timedRes <- timed(Browser.click(Selector.id("b")))
                        (elapsedDur, _) = timedRes
                        elapsed         = elapsedDur.toMillis
                        outText <- Browser.eval("document.getElementById('out').textContent")
                    yield
                        // Behavioural: under opt-out the click returns before the 200ms deferred mutation.
                        assert(
                            outText == "before",
                            s"expected pre-mutation text 'before' under Duration.Zero opt-out (settlement skipped) but got '$outText'"
                        )
                        // Timing sanity (3× margin under 200ms): well under the would-be 250ms settlement bound.
                        assert(
                            elapsed < 600,
                            s"expected click under Duration.Zero opt-out to return well under settlement bound but took ${elapsedMsLabel(elapsed)}"
                        )
                    end for
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

    // ---- `awaitQuiescence` Malformed propagation through `afterAction` ----
    //
    // To force a Malformed reply we corrupt the in-page settlement state so the JS coroutine throws when it
    // reads `window.__kyoMutCount`. CDP returns an `exceptionDetails` payload (no `result.value`); the Scala
    // `extractStringValue` falls back to `""`; `parseSettlementValue("")` returns Malformed; settlement raises
    // a `BrowserProtocolErrorException` (a `BrowserConnectionException`). Behavioural assertion on Abort shape.
    //
    // Pre-install: we set `window.__kyoMutObs` to a non-null marker BEFORE the click runs. Install's idempotent
    // branch sees a pre-existing observer and skips the reset of `__kyoMutCount` (the 'shared' install path).
    // We then redefine `__kyoMutCount` as a throwing getter, so when `awaitQuiescence` reads it the IIFE
    // rejects with an exception.

    "awaitQuiescence Malformed propagates through afterAction as BrowserProtocolErrorException" in {
        withBrowserOnLocalhost {
            Browser.eval(
                """document.body.innerHTML = '<button id="b">click</button>';
                  |window.__kyoMutObs = { disconnect: () => {} };
                  |window.__kyoMutObsRef = 0;
                  |window.__kyoMutLast = Date.now();
                  |Object.defineProperty(window, '__kyoMutCount', {
                  |  configurable: true,
                  |  get: function() { throw new Error('kyo-test-corruption'); }
                  |});
                  |'ok'
                """.stripMargin
            ).andThen {
                Browser.withConfig(
                    _.mutationSettlementTimeout(500.millis)
                        .retrySchedule(Schedule.fixed(50.millis).maxDuration(500.millis))
                ) {
                    Abort.run[BrowserConnectionException | BrowserAssertionException] {
                        Browser.click(Selector.id("b"))
                    }.map { result =>
                        // Behavioural assertion on Abort SHAPE: the corrupted page state surfaces as a typed
                        // BrowserProtocolErrorException (a BrowserConnectionException). The method tag reflects
                        // the actual failure surface: either MutationSettlement.awaitQuiescence (if the IIFE
                        // returned a wire-shape we can't decode) or Runtime.evaluate (if the IIFE threw and the
                        // eval-result envelope surfaced via internalEvalFailed). Both are valid Malformed-style
                        // propagation paths; the contract is "typed Abort, not silent degrade".
                        result match
                            case Result.Failure(ex: BrowserProtocolErrorException) =>
                                assert(
                                    ex.method == "MutationSettlement.awaitQuiescence" || ex.method == "Runtime.evaluate",
                                    s"expected method tag MutationSettlement.awaitQuiescence or Runtime.evaluate, got '${ex.method}'"
                                )
                            case other =>
                                fail(s"expected BrowserProtocolErrorException from awaitQuiescence Malformed propagation, got $other")
                    }
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
                            start  <- Clock.nowMonotonic
                            _      <- Browser.click(Selector.id("b"))
                            stop   <- Clock.nowMonotonic
                            actual <- Browser.eval("document.getElementById('sib').textContent")
                        yield
                            val elapsedMs = (stop - start).toMillis
                            assert(
                                elapsedMs >= 200,
                                s"expected click to take >= 200ms (settlement waited for the sibling mutation) but elapsed=${elapsedMs}ms; sibling observation is likely not engaged"
                            )
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
                            start  <- Clock.nowMonotonic
                            _      <- Browser.click(Selector.id("b"))
                            stop   <- Clock.nowMonotonic
                            actual <- Browser.eval("document.getElementById('sib').textContent")
                        yield
                            val elapsedMs = (stop - start).toMillis
                            assert(
                                elapsedMs >= 200,
                                s"expected click to take >= 200ms (settlement awaited _kyoPostQ) but elapsed=${elapsedMs}ms"
                            )
                            assert(actual == "queued-write", s"expected sib textContent='queued-write' after settlement, got '$actual'")
                        end for
                    }
                }
            }
        }
    }

    // ---- 'shared' install path: concurrent `afterAction` ----
    //
    // Two parallel clicks in the same tab share a single window-level observer. The second `installObserver`
    // sees `window.__kyoMutObs` already exists, increments `__kyoMutObsRef`, and reuses the running observer
    // (the 'shared' branch in installObserver). Both `Async.zip` arms must complete cleanly; the assertion is
    // that neither arm raises, proving the ref-count handshake correctly admits the overlapping caller.

    "'shared' install path: two concurrent afterAction calls succeed via Async.zip" in {
        // Targets the "shared install" branch in `installObserver`: exercised when one `afterAction` overlaps
        // another in the same tab. We invoke `MutationSettlement.afterAction` directly (it is `private[kyo]`
        // and accessible from this `kyo.internal` test) with a simple `Browser.eval` payload as the "action".
        // This isolates the shared-install ref-count handshake from `Browser.click`'s CDP DOM-resolution
        // machinery (which serialises poorly across concurrent fibers in the SAME tab, unrelated to settlement).
        //
        // Each action triggers a one-shot post-action mutation in its own subtree so `awaitQuiescence` actually
        // engages (sawMutation=true → quiescence-window path) rather than falling through `firstGrace`.
        // `Async.zip` is the explicit synchronisation primitive.
        withBrowserOnLocalhost {
            Browser.eval(
                """document.body.innerHTML = '<div id="a"><span>0</span></div><div id="b"><span>0</span></div>' +
                  |  '<div id="r1"></div><div id="r2"></div>';
                  |'ok'
                """.stripMargin
            ).andThen {
                // Build per-arm `afterAction` wrappers, each rooted at a different subtree. The first call
                // installs `__kyoMutObs`; the second sees it and takes the 'shared' branch (`installObserver`'s
                // idempotent path that just bumps `__kyoMutObsRef`).
                def arm(scopeId: String, mutationTarget: String, label: String)(using Frame) =
                    MutationSettlement.afterAction(
                        Browser.eval(
                            s"""(() => {
                                |  setTimeout(() => {
                                |    document.getElementById('$mutationTarget').textContent = '$label';
                                |  }, 30);
                                |})(); 'ok'
                            """.stripMargin
                        ).unit
                    )(scopeSelector = Present(Selector.id(scopeId)))

                Browser.use { tab =>
                    Async.zip(
                        Browser.runOn(tab)(arm("a", "r1", "1")),
                        Browser.runOn(tab)(arm("b", "r2", "2"))
                    ).map { (_, _) =>
                        // Behavioural: both arms returned without raising; the shared-install handshake
                        // admitted the overlapping caller. DOM-side: both post-mutation values are visible.
                        for
                            r1 <- Browser.eval("document.getElementById('r1').textContent")
                            r2 <- Browser.eval("document.getElementById('r2').textContent")
                        yield
                            assert(r1 == "1", s"expected #r1 == '1' after concurrent afterAction, got '$r1'")
                            assert(r2 == "2", s"expected #r2 == '2' after concurrent afterAction, got '$r2'")
                        end for
                    }
                }
            }
        }
    }

    // ---- `scopeSelector = Present(s)` subtree-only observation ----
    //
    // The observer is rooted at `document.querySelector(scopeSelector)`; mutations OUTSIDE that subtree must
    // NOT extend settlement. The click handler synchronously schedules many one-shot setTimeout mutations (no
    // setInterval) that fire OUTSIDE the click target's subtree, spread across a 3-second window, well past
    // the default 2-second `mutationSettlementTimeout`. With proper subtree scoping, settlement falls through
    // the firstGrace path (no in-scope mutations) and returns cleanly within ~firstGrace+overhead. With a
    // document-rooted observer, settlement would see the staggered out-of-scope mutations and raise
    // `BrowserAssertionTimedOutException` at the deadline. Negative assertion: click MUST succeed.

    "scopeSelector = Present(s): out-of-scope mutations do NOT trigger settlement" in {
        // NOTE on JS setup: same as the other browser scenarios; set innerHTML first, then install handlers
        // via top-level eval (innerHTML-injected `<script>` tags don't execute per HTML5 spec).
        withBrowserOnLocalhost {
            Browser.eval(
                """document.body.innerHTML = '<button id="trigger">go</button>' +
                  |  '<div id="outside"></div>';
                  |window.__outsideMutations = 0;
                  |document.getElementById("trigger").onclick = () => {
                  |  for (let i = 0; i < 60; i++) {
                  |    setTimeout(() => {
                  |      const s = document.createElement("span");
                  |      s.textContent = String(i);
                  |      document.getElementById("outside").appendChild(s);
                  |      window.__outsideMutations++;
                  |    }, 50 + i * 50);
                  |  }
                  |};
                  |'ok'
                """.stripMargin
            ).andThen {
                // Default config: mutationSettlementTimeout=2s, firstGrace=100ms. The click target is `#trigger`,
                // so the scoped observer roots at the button; out-of-scope `#outside` churn is invisible.
                for
                    _ <- Browser.click(Selector.id("trigger"))
                    // DOM-side check: at least one out-of-scope mutation has fired by now (the first one is at
                    // 50ms post-click; even at firstGrace exit the click handler has already scheduled them).
                    // After the click returns and we read this counter, some have certainly run, but the
                    // settlement was NOT extended by them.
                    outsideCount <- Browser.eval("String(window.__outsideMutations)")
                yield
                    val n = outsideCount.toIntOption.getOrElse(0)
                    // The deterministic contract is the Abort SHAPE: `Browser.click` returned WITHOUT raising.
                    // A document-rooted observer would have seen the staggered out-of-scope mutations, never
                    // quiesced, and raised BrowserAssertionTimedOutException, aborting this for-comprehension
                    // before the `yield`. Reaching here means out-of-scope mutations were correctly ignored.
                    // No wall-clock assertion: an absolute elapsed bound cannot distinguish "didn't settle-wait"
                    // from "slow host" and false-fails under load.
                    // DOM-side proof the page WAS active (otherwise the test could pass trivially against an
                    // inert page). At least one outside mutation must have happened by the time `outsideCount`
                    // is read; since they start at t=50ms and the click takes >= 100ms (firstGrace), a sane
                    // lower bound is 1.
                    assert(
                        n >= 1,
                        s"expected at least one out-of-scope mutation to have fired (proving the test is non-trivially active), got $n"
                    )
                end for
            }
        }
    }

    "two parallel Browser.click on the same tab share a single observer install" in {
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

    // Helpers, kept private to this test class so they don't leak into the parser-only scope above.

    private def elapsedMsLabel(ms: Long): String = s"${ms}ms"

end MutationSettlementTest
