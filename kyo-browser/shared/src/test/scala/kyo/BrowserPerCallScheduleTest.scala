package kyo

/** Pins the `schedule: Maybe[Schedule] = Absent` per-call override on every wait/assert method.
  *
  *   - `schedule = Present(s)`: the method uses `s` instead of `cfg.retrySchedule`.
  *   - `schedule = Absent`: the method falls back to `cfg.retrySchedule`.
  *
  * "honours per-call budget" tests install a never-satisfying fixture, a short per-call `schedule`, and an effectively-infinite fallback
  * (`neverSchedule`): the call aborts iff the short schedule won, else hangs the suite timeout. "uses cfg.retrySchedule" tests omit the per-call schedule.
  */
class BrowserPerCallScheduleTest extends BrowserTest:

    override def timeout = 90.seconds

    // Short schedule that exhausts quickly, so the override under test aborts promptly.
    private val shortSchedule: Schedule = Schedule.fixed(10.millis).maxDuration(100.millis)
    // Effectively-infinite retry delay (1h vs the 90s suite timeout): a call that wrongly falls back to it instead of
    // the short per-call/inner schedule hangs the suite timeout.
    private val neverSchedule: Schedule = Schedule.fixed(1.hour)

    // ── waitForRequestUrl with schedule honours per-call budget ──────────

    "waitForRequestUrl with schedule = Present(shortSchedule) honours per-call budget" in {
        withBrowser {
            Browser.withConfig(_.retrySchedule(neverSchedule)) {
                onPage("<html><body></body></html>") {
                    Abort.run[BrowserAssertionException] {
                        Browser.waitForRequestUrl(
                            "/never-requested-url",
                            schedule = Present(shortSchedule)
                        )
                    }.map { result =>
                        result match
                            case Result.Failure(_: BrowserAssertionTimedOutException) =>
                                succeed
                            case other => fail(s"expected BrowserAssertionTimedOutException, got $other")
                        end match
                    }
                }
            }
        }
    }

    // ── waitForRequestUrl with schedule = Absent uses cfg.retrySchedule ──

    "waitForRequestUrl with schedule = Absent uses cfg.retrySchedule" in {
        withBrowser {
            onPage("<html><body></body></html>") {
                tight {
                    Abort.run[BrowserAssertionException] {
                        Browser.waitForRequestUrl(
                            "/never-requested-url",
                            schedule = Absent
                        )
                    }
                }.map { result =>
                    result match
                        case Result.Failure(ex: BrowserAssertionTimedOutException) =>
                            assert(ex.getMessage.contains("Assertion failed"))
                        case other => fail(s"expected BrowserAssertionTimedOutException, got $other")
                }
            }
        }
    }

    // ── assertCount with per-call schedule honours per-call budget ───────

    "assertCount with per-call schedule honours per-call budget" in {
        withBrowser {
            Browser.withConfig(_.retrySchedule(neverSchedule)) {
                onPage("<div>no items here</div>") {
                    Abort.run[BrowserAssertionException] {
                        Browser.assertCount(
                            Browser.Selector.css("li.missing-item"),
                            expected = 5,
                            schedule = Present(shortSchedule)
                        )
                    }.map { result =>
                        result match
                            case Result.Failure(_: BrowserAssertionTimedOutException) =>
                                succeed
                            case other => fail(s"expected BrowserAssertionTimedOutException, got $other")
                        end match
                    }
                }
            }
        }
    }

    // ── assertText (equality overload) with per-call schedule ────────────

    "assertText with per-call schedule honours per-call budget" in {
        withBrowser {
            Browser.withConfig(_.retrySchedule(neverSchedule)) {
                onPage("<div id='t'>wrong text</div>") {
                    Abort.run[BrowserAssertionException] {
                        Browser.assertText(
                            Browser.Selector.id("t"),
                            "never matches",
                            schedule = Present(shortSchedule)
                        )
                    }.map { result =>
                        result match
                            case Result.Failure(_: BrowserAssertionTimedOutException) =>
                                succeed
                            case other => fail(s"expected BrowserAssertionTimedOutException, got $other")
                        end match
                    }
                }
            }
        }
    }

    // ── assertVisible with per-call schedule ─────────────────────────────

    "assertVisible with per-call schedule honours per-call budget" in {
        withBrowser {
            Browser.withConfig(_.retrySchedule(neverSchedule)) {
                onPage("<div id='h' style='display:none'>hidden</div>") {
                    Abort.run[BrowserAssertionException] {
                        Browser.assertVisible(
                            Browser.Selector.id("h"),
                            schedule = Present(shortSchedule)
                        )
                    }.map { result =>
                        result match
                            case Result.Failure(_: BrowserAssertionTimedOutException) =>
                                succeed
                            case other => fail(s"expected BrowserAssertionTimedOutException, got $other")
                        end match
                    }
                }
            }
        }
    }

    // ── assertExists with per-call schedule ───────────────────────────────

    "assertExists with per-call schedule honours per-call budget" in {
        withBrowser {
            Browser.withConfig(_.retrySchedule(neverSchedule)) {
                onPage("<div>no matching element here</div>") {
                    Abort.run[BrowserElementException] {
                        Browser.assertExists(
                            Browser.Selector.css("#never-exists-element"),
                            schedule = Present(shortSchedule)
                        )
                    }.map { result =>
                        result match
                            case Result.Failure(_: BrowserElementNotFoundException) =>
                                succeed
                            case other => fail(s"expected BrowserElementNotFoundException, got $other")
                        end match
                    }
                }
            }
        }
    }

    // ── assertNotExists with per-call schedule ────────────────────────────

    "assertNotExists with per-call schedule honours per-call budget" in {
        withBrowser {
            Browser.withConfig(_.retrySchedule(neverSchedule)) {
                onPage("<div id='always-here'>present</div>") {
                    Abort.run[BrowserAssertionException] {
                        Browser.assertNotExists(
                            Browser.Selector.id("always-here"),
                            schedule = Present(shortSchedule)
                        )
                    }.map { result =>
                        result match
                            case Result.Failure(_: BrowserAssertionTimedOutException) =>
                                succeed
                            case other => fail(s"expected BrowserAssertionTimedOutException, got $other")
                        end match
                    }
                }
            }
        }
    }

    // ── assertEnabled with per-call schedule ──────────────────────────────

    "assertEnabled with per-call schedule honours per-call budget" in {
        withBrowser {
            Browser.withConfig(_.retrySchedule(neverSchedule)) {
                onPage("<button id='btn' disabled>disabled</button>") {
                    Abort.run[BrowserAssertionException] {
                        Browser.assertEnabled(
                            Browser.Selector.id("btn"),
                            schedule = Present(shortSchedule)
                        )
                    }.map { result =>
                        result match
                            case Result.Failure(_: BrowserAssertionTimedOutException) =>
                                succeed
                            case other => fail(s"expected BrowserAssertionTimedOutException, got $other")
                        end match
                    }
                }
            }
        }
    }

    // ── assertDisabled with per-call schedule ─────────────────────────────

    "assertDisabled with per-call schedule honours per-call budget" in {
        withBrowser {
            Browser.withConfig(_.retrySchedule(neverSchedule)) {
                onPage("<button id='btn'>enabled</button>") {
                    Abort.run[BrowserAssertionException] {
                        Browser.assertDisabled(
                            Browser.Selector.id("btn"),
                            schedule = Present(shortSchedule)
                        )
                    }.map { result =>
                        result match
                            case Result.Failure(_: BrowserAssertionTimedOutException) =>
                                succeed
                            case other => fail(s"expected BrowserAssertionTimedOutException, got $other")
                        end match
                    }
                }
            }
        }
    }

    // ── assertChecked with per-call schedule ─────────────────────────────

    "assertChecked with per-call schedule honours per-call budget" in {
        withBrowser {
            Browser.withConfig(_.retrySchedule(neverSchedule)) {
                onPage("<input type='checkbox' id='cb'>") {
                    Abort.run[BrowserAssertionException] {
                        Browser.assertChecked(
                            Browser.Selector.id("cb"),
                            schedule = Present(shortSchedule)
                        )
                    }.map { result =>
                        result match
                            case Result.Failure(_: BrowserAssertionTimedOutException) =>
                                succeed
                            case other => fail(s"expected BrowserAssertionTimedOutException, got $other")
                        end match
                    }
                }
            }
        }
    }

    // ── assertNotChecked with per-call schedule ──────────────────────────

    "assertNotChecked with per-call schedule honours per-call budget" in {
        withBrowser {
            Browser.withConfig(_.retrySchedule(neverSchedule)) {
                onPage("<input type='checkbox' id='cb' checked>") {
                    Abort.run[BrowserAssertionException] {
                        Browser.assertNotChecked(
                            Browser.Selector.id("cb"),
                            schedule = Present(shortSchedule)
                        )
                    }.map { result =>
                        result match
                            case Result.Failure(_: BrowserAssertionTimedOutException) =>
                                succeed
                            case other => fail(s"expected BrowserAssertionTimedOutException, got $other")
                        end match
                    }
                }
            }
        }
    }

    // ── assertValueEmpty with per-call schedule ───────────────────────────────

    "assertValueEmpty with per-call schedule honours per-call budget" in {
        withBrowser {
            Browser.withConfig(_.retrySchedule(neverSchedule)) {
                onPage("<input type='text' id='inp' value='non-empty'>") {
                    Abort.run[BrowserAssertionException] {
                        Browser.assertValueEmpty(
                            Browser.Selector.id("inp"),
                            schedule = Present(shortSchedule)
                        )
                    }.map { result =>
                        result match
                            case Result.Failure(_: BrowserAssertionTimedOutException) =>
                                succeed
                            case other => fail(s"expected BrowserAssertionTimedOutException, got $other")
                        end match
                    }
                }
            }
        }
    }

    // ── assertFocused with per-call schedule ─────────────────────────────

    "assertFocused with per-call schedule honours per-call budget" in {
        withBrowser {
            Browser.withConfig(_.retrySchedule(neverSchedule)) {
                onPage("<div><input type='text' id='other'><input type='text' id='target'></div>") {
                    Abort.run[BrowserAssertionException] {
                        // #target is never focused
                        Browser.assertFocused(
                            Browser.Selector.id("target"),
                            schedule = Present(shortSchedule)
                        )
                    }.map { result =>
                        result match
                            case Result.Failure(_: BrowserAssertionTimedOutException) =>
                                succeed
                            case other => fail(s"expected BrowserAssertionTimedOutException, got $other")
                        end match
                    }
                }
            }
        }
    }

    // ── assertNotFocused with per-call schedule ──────────────────────────

    "assertNotFocused with per-call schedule honours per-call budget" in {
        withBrowser {
            Browser.withConfig(_.retrySchedule(neverSchedule)) {
                onPage("<input type='text' id='inp' autofocus>") {
                    Abort.run[BrowserAssertionException] {
                        // autofocus means the element IS always focused, so assertNotFocused should fail
                        Browser.assertNotFocused(
                            Browser.Selector.id("inp"),
                            schedule = Present(shortSchedule)
                        )
                    }.map { result =>
                        result match
                            case Result.Failure(_: BrowserAssertionTimedOutException) =>
                                succeed
                            case other => fail(s"expected BrowserAssertionTimedOutException, got $other")
                        end match
                    }
                }
            }
        }
    }

    // ── assertUrl with per-call schedule ─────────────────────────────────

    "assertUrl with per-call schedule honours per-call budget" in {
        withBrowser {
            Browser.withConfig(_.retrySchedule(neverSchedule)) {
                onPage("<div>page</div>") {
                    Abort.run[BrowserAssertionException] {
                        Browser.assertUrl(
                            "https://never-this-url.example",
                            schedule = Present(shortSchedule)
                        )
                    }.map { result =>
                        result match
                            case Result.Failure(_: BrowserAssertionTimedOutException) =>
                                succeed
                            case other => fail(s"expected BrowserAssertionTimedOutException, got $other")
                        end match
                    }
                }
            }
        }
    }

    // ── assertTitle with per-call schedule ───────────────────────────────

    "assertTitle with per-call schedule honours per-call budget" in {
        withBrowser {
            Browser.withConfig(_.retrySchedule(neverSchedule)) {
                onPage("<html><head><title>Actual Title</title></head><body></body></html>") {
                    Abort.run[BrowserAssertionException] {
                        Browser.assertTitle(
                            "Never Matching Title",
                            schedule = Present(shortSchedule)
                        )
                    }.map { result =>
                        result match
                            case Result.Failure(_: BrowserAssertionTimedOutException) =>
                                succeed
                            case other => fail(s"expected BrowserAssertionTimedOutException, got $other")
                        end match
                    }
                }
            }
        }
    }

    // ── assertAttribute with per-call schedule ───────────────────────────

    "assertAttribute with per-call schedule honours per-call budget" in {
        withBrowser {
            Browser.withConfig(_.retrySchedule(neverSchedule)) {
                onPage("<div id='d' data-val='actual'>x</div>") {
                    Abort.run[BrowserAssertionException] {
                        Browser.assertAttribute(
                            Browser.Selector.id("d"),
                            "data-val",
                            "never-this-value",
                            schedule = Present(shortSchedule)
                        )
                    }.map { result =>
                        result match
                            case Result.Failure(_: BrowserAssertionTimedOutException) =>
                                succeed
                            case other => fail(s"expected BrowserAssertionTimedOutException, got $other")
                        end match
                    }
                }
            }
        }
    }

    // ── waitForText with per-call schedule ───────────────────────────────

    "waitForText with per-call schedule honours per-call budget" in {
        withBrowser {
            Browser.withConfig(_.retrySchedule(neverSchedule)) {
                onPage("<div id='t'>wrong</div>") {
                    Abort.run[BrowserAssertionException] {
                        Browser.waitForText(
                            Browser.Selector.id("t"),
                            (_: String) == "never",
                            schedule = Present(shortSchedule)
                        )
                    }.map { result =>
                        result match
                            case Result.Failure(_: BrowserAssertionTimedOutException) =>
                                succeed
                            case other => fail(s"expected BrowserAssertionTimedOutException, got $other")
                        end match
                    }
                }
            }
        }
    }

    // ── waitForAttribute with per-call schedule ──────────────────────────

    "waitForAttribute with per-call schedule honours per-call budget" in {
        withBrowser {
            Browser.withConfig(_.retrySchedule(neverSchedule)) {
                onPage("<div id='d' data-val='actual'>x</div>") {
                    Abort.run[BrowserAssertionException] {
                        Browser.waitForAttribute(
                            Browser.Selector.id("d"),
                            "data-val",
                            (_: String) == "never",
                            schedule = Present(shortSchedule)
                        )
                    }.map { result =>
                        result match
                            case Result.Failure(_: BrowserAssertionTimedOutException) =>
                                succeed
                            case other => fail(s"expected BrowserAssertionTimedOutException, got $other")
                        end match
                    }
                }
            }
        }
    }

    // ── waitForNetworkIdle with per-call schedule ────────────────────────

    "waitForNetworkIdle with per-call schedule honours per-call budget" in {
        withBrowser {
            // Keep fetch traffic going so idle is never achieved
            Browser.withConfig(_.retrySchedule(neverSchedule)) {
                onPage("""<html><body>
                <script>
                    function keepFetching() {
                        fetch('data:text/plain,ping').then(() => setTimeout(keepFetching, 30));
                    }
                    keepFetching();
                </script>
            </body></html>""") {
                    Abort.run[BrowserAssertionException] {
                        Browser.waitForNetworkIdle(
                            schedule = Present(shortSchedule)
                        )
                    }.map { result =>
                        result match
                            case Result.Failure(_: BrowserAssertionTimedOutException) =>
                                succeed
                            case other => fail(s"expected BrowserAssertionTimedOutException, got $other")
                        end match
                    }
                }
            }
        }
    }

    // ── waitFor with per-call schedule ───────────────────────────────────

    "waitFor with per-call schedule honours per-call budget" in {
        withBrowser {
            Browser.withConfig(_.retrySchedule(neverSchedule)) {
                onPage("<div>static</div>") {
                    Abort.run[BrowserAssertionException] {
                        Browser.waitFor(
                            "false",
                            schedule = Present(shortSchedule)
                        )
                    }.map { result =>
                        result match
                            case Result.Failure(_: BrowserAssertionTimedOutException) =>
                                succeed
                            case other => fail(s"expected BrowserAssertionTimedOutException, got $other")
                        end match
                    }
                }
            }
        }
    }

    // ── withPopup with per-call schedule ─────────────────────────────────

    "withPopup with per-call schedule honours per-call budget" in {
        withBrowser {
            Browser.withConfig(_.retrySchedule(neverSchedule)) {
                onPage("<div>no popup here</div>") {
                    Scope.run {
                        Abort.run[BrowserReadException] {
                            Browser.withPopup(
                                schedule = Present(shortSchedule)
                            )(Browser.eval("'no popup triggered'").unit)(Browser.url)
                        }
                    }.map { result =>
                        // withPopup raises BrowserProtocolErrorException ("withPopup: no new tab detected") on schedule
                        // exhaustion.
                        result match
                            case Result.Failure(_: BrowserProtocolErrorException) =>
                                succeed
                            case other => fail(s"expected BrowserProtocolErrorException, got $other")
                        end match
                    }
                }
            }
        }
    }

    // ── Browser.iframe with per-call schedule ────────────────────────────

    "Browser.iframe with per-call schedule honours per-call budget" in {
        withBrowser {
            Browser.withConfig(_.retrySchedule(neverSchedule)) {
                onPage("<div>no iframe here</div>") {
                    Abort.run[BrowserReadException] {
                        Browser.iframe(
                            Browser.Selector.css("iframe#never"),
                            schedule = Present(shortSchedule)
                        )
                    }.map { result =>
                        result match
                            case Result.Failure(_: BrowserElementNotFoundException) =>
                                succeed
                            case other => fail(s"expected BrowserElementNotFoundException, got $other")
                        end match
                    }
                }
            }
        }
    }

    // ── tryAcceptCookies with per-call schedule ──────────────────────────

    "tryAcceptCookies with per-call schedule honours per-call budget" in {
        withBrowser {
            // A cookie banner that never disappears after click; tryAcceptCookies should abort
            Browser.withConfig(_.retrySchedule(neverSchedule)) {
                onPage("""<html><body>
                <button id='accept-cookie' class='accept-cookie'>Accept</button>
            </body></html>""") {
                    Abort.run[BrowserAssertionException] {
                        Browser.tryAcceptCookies(
                            schedule = Present(shortSchedule)
                        )
                    }.map { result =>
                        result match
                            case Result.Failure(_: BrowserAssertionTimedOutException) =>
                                succeed
                            case other => fail(s"expected BrowserAssertionTimedOutException, got $other")
                        end match
                    }
                }
            }
        }
    }

    // ── retry-schedule precedence matrix ────────────────────────────
    //
    // Precedence law: `val effectiveSchedule = schedule.getOrElse(cfg.retrySchedule)`
    //
    // Per-call `Present(s)` returns `s` verbatim, bypassing whatever `cfg.retrySchedule` holds (including the value set by `withConfig`
    // or capped by `withTimeout`). Inner `withConfig` wins over outer via `configLocal.let` shadowing.

    /** A per-call `Present(shortSchedule)` wins over an enclosing `withConfig(_.retrySchedule(neverSchedule))`: `schedule.getOrElse(cfg.retrySchedule)`
      * returns the per-call value verbatim, so the call aborts on the short schedule; the outer never-ending cfg would instead hang the suite timeout.
      */
    "retry-schedule precedence: per-call schedule wins over enclosing withConfig" in {
        withBrowser {
            onPage("<html><body><h1>no match</h1></body></html>") {
                Browser.withConfig(_.retrySchedule(neverSchedule)) {
                    Abort.run[BrowserReadException] {
                        Browser.assertExists(
                            Browser.Selector.id("nonexistent"),
                            schedule = Present(shortSchedule)
                        )
                    }.map { result =>
                        result match
                            case Result.Failure(_: BrowserElementNotFoundException) =>
                                succeed
                            case other => fail(s"expected BrowserElementNotFoundException, got $other")
                        end match
                    }
                }
            }
        }
    }

    /** An inner `withConfig(_.retrySchedule(100ms))` shadows an outer `withConfig(_.retrySchedule(neverSchedule))` via `configLocal.let`.
      * With no per-call schedule the inner cfg wins, so the call aborts on it; the outer never-ending cfg would instead hang the suite timeout.
      */
    "retry-schedule precedence: innermost withConfig wins over outer withConfig" in {
        withBrowser {
            onPage("<html><body><h1>no match</h1></body></html>") {
                Browser.withConfig(_.retrySchedule(neverSchedule)) {
                    Browser.withConfig(_.retrySchedule(Schedule.fixed(10.millis).maxDuration(100.millis))) {
                        Abort.run[BrowserReadException] {
                            Browser.assertExists(Browser.Selector.id("nonexistent"))
                        }.map { result =>
                            result match
                                case Result.Failure(_: BrowserElementNotFoundException) =>
                                    succeed
                                case other => fail(s"expected BrowserElementNotFoundException, got $other")
                            end match
                        }
                    }
                }
            }
        }
    }

    /** `withTimeout` caps `cfg.retrySchedule` via `.maxDuration(timeout)`, but a per-call `Present(shortSchedule)` bypasses `cfg.retrySchedule`
      * entirely, so `withTimeout` cannot cap a per-call override: the call aborts on the short schedule though the enclosing cfg never ends.
      */
    "retry-schedule precedence: withTimeout cannot cap a per-call schedule override" in {
        withBrowser {
            Browser.withConfig(_.retrySchedule(neverSchedule)) {
                onPage("<html><body><h1>no match</h1></body></html>") {
                    // The withTimeout budget (2h) exceeds neverSchedule's 1h step, so the wrong path (cfg capped by
                    // withTimeout) would wait ~1h and trip the suite timeout: unambiguously a hang, not instant exhaustion.
                    Browser.withTimeout(2.hours) {
                        Abort.run[BrowserReadException] {
                            Browser.assertExists(
                                Browser.Selector.id("nonexistent"),
                                schedule = Present(shortSchedule)
                            )
                        }.map { result =>
                            result match
                                case Result.Failure(_: BrowserElementNotFoundException) =>
                                    succeed
                                case other => fail(s"expected BrowserElementNotFoundException, got $other")
                            end match
                        }
                    }
                }
            }
        }
    }

end BrowserPerCallScheduleTest
