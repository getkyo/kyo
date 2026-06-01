package kyo

/** Pins the `schedule: Maybe[Schedule] = Absent` per-call override on every wait/assert method.
  *
  *   - `schedule = Present(s)`: the method uses `s` instead of `cfg.retrySchedule`.
  *   - `schedule = Absent`: the method falls back to `cfg.retrySchedule`.
  *
  * "honours per-call budget" tests install a fixture that never satisfies, pass a short `schedule`, and assert the call aborts inside the
  * budget. "uses cfg.retrySchedule" tests omit the per-call schedule.
  */
class BrowserPerCallScheduleTest extends BrowserTest:

    override def timeout = 90.seconds

    // Short schedule that exhausts quickly for timing assertions.
    private val shortSchedule: Schedule = Schedule.fixed(10.millis).maxDuration(100.millis)
    // Floor/ceiling pair. Floor proves the schedule actually ran; ceiling is 15x the schedule's 100ms
    // maxDuration, conservative against partial regressions.
    private val floorMs: Long   = 50L
    private val ceilingMs: Long = 1500L

    // ── waitForRequestUrl with schedule honours per-call budget ──────────

    "waitForRequestUrl with schedule = Present(shortSchedule) honours per-call budget" in run {
        withBrowser {
            onPage("<html><body></body></html>") {
                val start = java.lang.System.currentTimeMillis()
                Abort.run[BrowserAssertionException] {
                    Browser.waitForRequestUrl(
                        "/never-requested-url",
                        schedule = Present(shortSchedule)
                    )
                }.map { result =>
                    val elapsed = java.lang.System.currentTimeMillis() - start
                    result match
                        case Result.Failure(_: BrowserAssertionTimedOutException) =>
                            assert(
                                elapsed >= floorMs && elapsed < ceilingMs,
                                s"per-call schedule should land in [${floorMs}, ${ceilingMs})ms but took ${elapsed}ms"
                            )
                        case other => fail(s"expected BrowserAssertionTimedOutException, got $other")
                    end match
                }
            }
        }
    }

    // ── waitForRequestUrl with schedule = Absent uses cfg.retrySchedule ──

    "waitForRequestUrl with schedule = Absent uses cfg.retrySchedule" in run {
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
                        case Result.Failure(_: BrowserAssertionTimedOutException) => succeed
                        case other => fail(s"expected BrowserAssertionTimedOutException, got $other")
                }
            }
        }
    }

    // ── assertCount with per-call schedule honours per-call budget ───────

    "assertCount with per-call schedule honours per-call budget" in run {
        withBrowser {
            onPage("<div>no items here</div>") {
                val start = java.lang.System.currentTimeMillis()
                Abort.run[BrowserAssertionException] {
                    Browser.assertCount(
                        Browser.Selector.css("li.missing-item"),
                        expected = 5,
                        schedule = Present(shortSchedule)
                    )
                }.map { result =>
                    val elapsed = java.lang.System.currentTimeMillis() - start
                    result match
                        case Result.Failure(_: BrowserAssertionTimedOutException) =>
                            assert(
                                elapsed >= floorMs && elapsed < ceilingMs,
                                s"per-call schedule should land in [${floorMs}, ${ceilingMs})ms but took ${elapsed}ms"
                            )
                        case other => fail(s"expected BrowserAssertionTimedOutException, got $other")
                    end match
                }
            }
        }
    }

    // ── assertText (equality overload) with per-call schedule ────────────

    "assertText with per-call schedule honours per-call budget" in run {
        withBrowser {
            onPage("<div id='t'>wrong text</div>") {
                val start = java.lang.System.currentTimeMillis()
                Abort.run[BrowserAssertionException] {
                    Browser.assertText(
                        Browser.Selector.id("t"),
                        "never matches",
                        schedule = Present(shortSchedule)
                    )
                }.map { result =>
                    val elapsed = java.lang.System.currentTimeMillis() - start
                    result match
                        case Result.Failure(_: BrowserAssertionTimedOutException) =>
                            assert(
                                elapsed >= floorMs && elapsed < ceilingMs,
                                s"per-call schedule should land in [${floorMs}, ${ceilingMs})ms but took ${elapsed}ms"
                            )
                        case other => fail(s"expected BrowserAssertionTimedOutException, got $other")
                    end match
                }
            }
        }
    }

    // ── assertVisible with per-call schedule ─────────────────────────────

    "assertVisible with per-call schedule honours per-call budget" in run {
        withBrowser {
            onPage("<div id='h' style='display:none'>hidden</div>") {
                val start = java.lang.System.currentTimeMillis()
                Abort.run[BrowserAssertionException] {
                    Browser.assertVisible(
                        Browser.Selector.id("h"),
                        schedule = Present(shortSchedule)
                    )
                }.map { result =>
                    val elapsed = java.lang.System.currentTimeMillis() - start
                    result match
                        case Result.Failure(_: BrowserAssertionTimedOutException) =>
                            assert(
                                elapsed >= floorMs && elapsed < ceilingMs,
                                s"per-call schedule should land in [${floorMs}, ${ceilingMs})ms but took ${elapsed}ms"
                            )
                        case other => fail(s"expected BrowserAssertionTimedOutException, got $other")
                    end match
                }
            }
        }
    }

    // ── assertExists with per-call schedule ───────────────────────────────

    "assertExists with per-call schedule honours per-call budget" in run {
        withBrowser {
            onPage("<div>no matching element here</div>") {
                val start = java.lang.System.currentTimeMillis()
                Abort.run[BrowserElementException] {
                    Browser.assertExists(
                        Browser.Selector.css("#never-exists-element"),
                        schedule = Present(shortSchedule)
                    )
                }.map { result =>
                    val elapsed = java.lang.System.currentTimeMillis() - start
                    result match
                        case Result.Failure(_: BrowserElementNotFoundException) =>
                            assert(
                                elapsed >= floorMs && elapsed < ceilingMs,
                                s"per-call schedule should land in [${floorMs}, ${ceilingMs})ms but took ${elapsed}ms"
                            )
                        case other => fail(s"expected BrowserElementNotFoundException, got $other")
                    end match
                }
            }
        }
    }

    // ── assertNotExists with per-call schedule ────────────────────────────

    "assertNotExists with per-call schedule honours per-call budget" in run {
        withBrowser {
            onPage("<div id='always-here'>present</div>") {
                val start = java.lang.System.currentTimeMillis()
                Abort.run[BrowserAssertionException] {
                    Browser.assertNotExists(
                        Browser.Selector.id("always-here"),
                        schedule = Present(shortSchedule)
                    )
                }.map { result =>
                    val elapsed = java.lang.System.currentTimeMillis() - start
                    result match
                        case Result.Failure(_: BrowserAssertionTimedOutException) =>
                            assert(
                                elapsed >= floorMs && elapsed < ceilingMs,
                                s"per-call schedule should land in [${floorMs}, ${ceilingMs})ms but took ${elapsed}ms"
                            )
                        case other => fail(s"expected BrowserAssertionTimedOutException, got $other")
                    end match
                }
            }
        }
    }

    // ── assertEnabled with per-call schedule ──────────────────────────────

    "assertEnabled with per-call schedule honours per-call budget" in run {
        withBrowser {
            onPage("<button id='btn' disabled>disabled</button>") {
                val start = java.lang.System.currentTimeMillis()
                Abort.run[BrowserAssertionException] {
                    Browser.assertEnabled(
                        Browser.Selector.id("btn"),
                        schedule = Present(shortSchedule)
                    )
                }.map { result =>
                    val elapsed = java.lang.System.currentTimeMillis() - start
                    result match
                        case Result.Failure(_: BrowserAssertionTimedOutException) =>
                            assert(
                                elapsed >= floorMs && elapsed < ceilingMs,
                                s"per-call schedule should land in [${floorMs}, ${ceilingMs})ms but took ${elapsed}ms"
                            )
                        case other => fail(s"expected BrowserAssertionTimedOutException, got $other")
                    end match
                }
            }
        }
    }

    // ── assertDisabled with per-call schedule ─────────────────────────────

    "assertDisabled with per-call schedule honours per-call budget" in run {
        withBrowser {
            onPage("<button id='btn'>enabled</button>") {
                val start = java.lang.System.currentTimeMillis()
                Abort.run[BrowserAssertionException] {
                    Browser.assertDisabled(
                        Browser.Selector.id("btn"),
                        schedule = Present(shortSchedule)
                    )
                }.map { result =>
                    val elapsed = java.lang.System.currentTimeMillis() - start
                    result match
                        case Result.Failure(_: BrowserAssertionTimedOutException) =>
                            assert(
                                elapsed >= floorMs && elapsed < ceilingMs,
                                s"per-call schedule should land in [${floorMs}, ${ceilingMs})ms but took ${elapsed}ms"
                            )
                        case other => fail(s"expected BrowserAssertionTimedOutException, got $other")
                    end match
                }
            }
        }
    }

    // ── assertChecked with per-call schedule ─────────────────────────────

    "assertChecked with per-call schedule honours per-call budget" in run {
        withBrowser {
            onPage("<input type='checkbox' id='cb'>") {
                val start = java.lang.System.currentTimeMillis()
                Abort.run[BrowserAssertionException] {
                    Browser.assertChecked(
                        Browser.Selector.id("cb"),
                        schedule = Present(shortSchedule)
                    )
                }.map { result =>
                    val elapsed = java.lang.System.currentTimeMillis() - start
                    result match
                        case Result.Failure(_: BrowserAssertionTimedOutException) =>
                            assert(
                                elapsed >= floorMs && elapsed < ceilingMs,
                                s"per-call schedule should land in [${floorMs}, ${ceilingMs})ms but took ${elapsed}ms"
                            )
                        case other => fail(s"expected BrowserAssertionTimedOutException, got $other")
                    end match
                }
            }
        }
    }

    // ── assertNotChecked with per-call schedule ──────────────────────────

    "assertNotChecked with per-call schedule honours per-call budget" in run {
        withBrowser {
            onPage("<input type='checkbox' id='cb' checked>") {
                val start = java.lang.System.currentTimeMillis()
                Abort.run[BrowserAssertionException] {
                    Browser.assertNotChecked(
                        Browser.Selector.id("cb"),
                        schedule = Present(shortSchedule)
                    )
                }.map { result =>
                    val elapsed = java.lang.System.currentTimeMillis() - start
                    result match
                        case Result.Failure(_: BrowserAssertionTimedOutException) =>
                            assert(
                                elapsed >= floorMs && elapsed < ceilingMs,
                                s"per-call schedule should land in [${floorMs}, ${ceilingMs})ms but took ${elapsed}ms"
                            )
                        case other => fail(s"expected BrowserAssertionTimedOutException, got $other")
                    end match
                }
            }
        }
    }

    // ── assertValueEmpty with per-call schedule ───────────────────────────────

    "assertValueEmpty with per-call schedule honours per-call budget" in run {
        withBrowser {
            onPage("<input type='text' id='inp' value='non-empty'>") {
                val start = java.lang.System.currentTimeMillis()
                Abort.run[BrowserAssertionException] {
                    Browser.assertValueEmpty(
                        Browser.Selector.id("inp"),
                        schedule = Present(shortSchedule)
                    )
                }.map { result =>
                    val elapsed = java.lang.System.currentTimeMillis() - start
                    result match
                        case Result.Failure(_: BrowserAssertionTimedOutException) =>
                            assert(
                                elapsed >= floorMs && elapsed < ceilingMs,
                                s"per-call schedule should land in [${floorMs}, ${ceilingMs})ms but took ${elapsed}ms"
                            )
                        case other => fail(s"expected BrowserAssertionTimedOutException, got $other")
                    end match
                }
            }
        }
    }

    // ── assertFocused with per-call schedule ─────────────────────────────

    "assertFocused with per-call schedule honours per-call budget" in run {
        withBrowser {
            onPage("<div><input type='text' id='other'><input type='text' id='target'></div>") {
                val start = java.lang.System.currentTimeMillis()
                Abort.run[BrowserAssertionException] {
                    // #target is never focused
                    Browser.assertFocused(
                        Browser.Selector.id("target"),
                        schedule = Present(shortSchedule)
                    )
                }.map { result =>
                    val elapsed = java.lang.System.currentTimeMillis() - start
                    result match
                        case Result.Failure(_: BrowserAssertionTimedOutException) =>
                            assert(
                                elapsed >= floorMs && elapsed < ceilingMs,
                                s"per-call schedule should land in [${floorMs}, ${ceilingMs})ms but took ${elapsed}ms"
                            )
                        case other => fail(s"expected BrowserAssertionTimedOutException, got $other")
                    end match
                }
            }
        }
    }

    // ── assertNotFocused with per-call schedule ──────────────────────────

    "assertNotFocused with per-call schedule honours per-call budget" in run {
        withBrowser {
            onPage("<input type='text' id='inp' autofocus>") {
                val start = java.lang.System.currentTimeMillis()
                Abort.run[BrowserAssertionException] {
                    // autofocus means the element IS always focused, so assertNotFocused should fail
                    Browser.assertNotFocused(
                        Browser.Selector.id("inp"),
                        schedule = Present(shortSchedule)
                    )
                }.map { result =>
                    val elapsed = java.lang.System.currentTimeMillis() - start
                    result match
                        case Result.Failure(_: BrowserAssertionTimedOutException) =>
                            assert(
                                elapsed >= floorMs && elapsed < ceilingMs,
                                s"per-call schedule should land in [${floorMs}, ${ceilingMs})ms but took ${elapsed}ms"
                            )
                        case other => fail(s"expected BrowserAssertionTimedOutException, got $other")
                    end match
                }
            }
        }
    }

    // ── assertUrl with per-call schedule ─────────────────────────────────

    "assertUrl with per-call schedule honours per-call budget" in run {
        withBrowser {
            onPage("<div>page</div>") {
                val start = java.lang.System.currentTimeMillis()
                Abort.run[BrowserAssertionException] {
                    Browser.assertUrl(
                        "https://never-this-url.example",
                        schedule = Present(shortSchedule)
                    )
                }.map { result =>
                    val elapsed = java.lang.System.currentTimeMillis() - start
                    result match
                        case Result.Failure(_: BrowserAssertionTimedOutException) =>
                            assert(
                                elapsed >= floorMs && elapsed < ceilingMs,
                                s"per-call schedule should land in [${floorMs}, ${ceilingMs})ms but took ${elapsed}ms"
                            )
                        case other => fail(s"expected BrowserAssertionTimedOutException, got $other")
                    end match
                }
            }
        }
    }

    // ── assertTitle with per-call schedule ───────────────────────────────

    "assertTitle with per-call schedule honours per-call budget" in run {
        withBrowser {
            onPage("<html><head><title>Actual Title</title></head><body></body></html>") {
                val start = java.lang.System.currentTimeMillis()
                Abort.run[BrowserAssertionException] {
                    Browser.assertTitle(
                        "Never Matching Title",
                        schedule = Present(shortSchedule)
                    )
                }.map { result =>
                    val elapsed = java.lang.System.currentTimeMillis() - start
                    result match
                        case Result.Failure(_: BrowserAssertionTimedOutException) =>
                            assert(
                                elapsed >= floorMs && elapsed < ceilingMs,
                                s"per-call schedule should land in [${floorMs}, ${ceilingMs})ms but took ${elapsed}ms"
                            )
                        case other => fail(s"expected BrowserAssertionTimedOutException, got $other")
                    end match
                }
            }
        }
    }

    // ── assertAttribute with per-call schedule ───────────────────────────

    "assertAttribute with per-call schedule honours per-call budget" in run {
        withBrowser {
            onPage("<div id='d' data-val='actual'>x</div>") {
                val start = java.lang.System.currentTimeMillis()
                Abort.run[BrowserAssertionException] {
                    Browser.assertAttribute(
                        Browser.Selector.id("d"),
                        "data-val",
                        "never-this-value",
                        schedule = Present(shortSchedule)
                    )
                }.map { result =>
                    val elapsed = java.lang.System.currentTimeMillis() - start
                    result match
                        case Result.Failure(_: BrowserAssertionTimedOutException) =>
                            assert(
                                elapsed >= floorMs && elapsed < ceilingMs,
                                s"per-call schedule should land in [${floorMs}, ${ceilingMs})ms but took ${elapsed}ms"
                            )
                        case other => fail(s"expected BrowserAssertionTimedOutException, got $other")
                    end match
                }
            }
        }
    }

    // ── waitForText with per-call schedule ───────────────────────────────

    "waitForText with per-call schedule honours per-call budget" in run {
        withBrowser {
            onPage("<div id='t'>wrong</div>") {
                val start = java.lang.System.currentTimeMillis()
                Abort.run[BrowserAssertionException] {
                    Browser.waitForText(
                        Browser.Selector.id("t"),
                        (_: String) == "never",
                        schedule = Present(shortSchedule)
                    )
                }.map { result =>
                    val elapsed = java.lang.System.currentTimeMillis() - start
                    result match
                        case Result.Failure(_: BrowserAssertionTimedOutException) =>
                            assert(
                                elapsed >= floorMs && elapsed < ceilingMs,
                                s"per-call schedule should land in [${floorMs}, ${ceilingMs})ms but took ${elapsed}ms"
                            )
                        case other => fail(s"expected BrowserAssertionTimedOutException, got $other")
                    end match
                }
            }
        }
    }

    // ── waitForAttribute with per-call schedule ──────────────────────────

    "waitForAttribute with per-call schedule honours per-call budget" in run {
        withBrowser {
            onPage("<div id='d' data-val='actual'>x</div>") {
                val start = java.lang.System.currentTimeMillis()
                Abort.run[BrowserAssertionException] {
                    Browser.waitForAttribute(
                        Browser.Selector.id("d"),
                        "data-val",
                        (_: String) == "never",
                        schedule = Present(shortSchedule)
                    )
                }.map { result =>
                    val elapsed = java.lang.System.currentTimeMillis() - start
                    result match
                        case Result.Failure(_: BrowserAssertionTimedOutException) =>
                            assert(
                                elapsed >= floorMs && elapsed < ceilingMs,
                                s"per-call schedule should land in [${floorMs}, ${ceilingMs})ms but took ${elapsed}ms"
                            )
                        case other => fail(s"expected BrowserAssertionTimedOutException, got $other")
                    end match
                }
            }
        }
    }

    // ── waitForNetworkIdle with per-call schedule ────────────────────────

    "waitForNetworkIdle with per-call schedule honours per-call budget" in run {
        withBrowser {
            // Keep fetch traffic going so idle is never achieved
            onPage("""<html><body>
                <script>
                    function keepFetching() {
                        fetch('data:text/plain,ping').then(() => setTimeout(keepFetching, 30));
                    }
                    keepFetching();
                </script>
            </body></html>""") {
                val start = java.lang.System.currentTimeMillis()
                Abort.run[BrowserAssertionException] {
                    Browser.waitForNetworkIdle(
                        schedule = Present(shortSchedule)
                    )
                }.map { result =>
                    val elapsed = java.lang.System.currentTimeMillis() - start
                    result match
                        case Result.Failure(_: BrowserAssertionTimedOutException) =>
                            assert(
                                elapsed >= floorMs && elapsed < ceilingMs,
                                s"per-call schedule should land in [${floorMs}, ${ceilingMs})ms but took ${elapsed}ms"
                            )
                        case other => fail(s"expected BrowserAssertionTimedOutException, got $other")
                    end match
                }
            }
        }
    }

    // ── waitFor with per-call schedule ───────────────────────────────────

    "waitFor with per-call schedule honours per-call budget" in run {
        withBrowser {
            onPage("<div>static</div>") {
                val start = java.lang.System.currentTimeMillis()
                Abort.run[BrowserAssertionException] {
                    Browser.waitFor(
                        "false",
                        schedule = Present(shortSchedule)
                    )
                }.map { result =>
                    val elapsed = java.lang.System.currentTimeMillis() - start
                    result match
                        case Result.Failure(_: BrowserAssertionTimedOutException) =>
                            assert(
                                elapsed >= floorMs && elapsed < ceilingMs,
                                s"per-call schedule should land in [${floorMs}, ${ceilingMs})ms but took ${elapsed}ms"
                            )
                        case other => fail(s"expected BrowserAssertionTimedOutException, got $other")
                    end match
                }
            }
        }
    }

    // ── withPopup with per-call schedule ─────────────────────────────────

    "withPopup with per-call schedule honours per-call budget" in run {
        withBrowser {
            onPage("<div>no popup here</div>") {
                val start = java.lang.System.currentTimeMillis()
                Scope.run {
                    Abort.run[BrowserReadException] {
                        Browser.withPopup(
                            schedule = Present(shortSchedule)
                        )(Browser.eval("'no popup triggered'").unit)(Browser.url)
                    }
                }.map { result =>
                    val elapsed = java.lang.System.currentTimeMillis() - start
                    // withPopup raises BrowserProtocolErrorException ("withPopup: no new tab detected") on schedule
                    // exhaustion.
                    result match
                        case Result.Failure(_: BrowserProtocolErrorException) =>
                            assert(
                                elapsed >= floorMs && elapsed < ceilingMs,
                                s"per-call schedule should land in [${floorMs}, ${ceilingMs})ms but took ${elapsed}ms"
                            )
                        case other => fail(s"expected BrowserProtocolErrorException, got $other")
                    end match
                }
            }
        }
    }

    // ── Browser.iframe with per-call schedule ────────────────────────────

    "Browser.iframe with per-call schedule honours per-call budget" in run {
        withBrowser {
            onPage("<div>no iframe here</div>") {
                val start = java.lang.System.currentTimeMillis()
                Abort.run[BrowserReadException] {
                    Browser.iframe(
                        Browser.Selector.css("iframe#never"),
                        schedule = Present(shortSchedule)
                    )
                }.map { result =>
                    val elapsed = java.lang.System.currentTimeMillis() - start
                    result match
                        case Result.Failure(_: BrowserElementNotFoundException) =>
                            assert(
                                elapsed >= floorMs && elapsed < ceilingMs,
                                s"per-call schedule should land in [${floorMs}, ${ceilingMs})ms but took ${elapsed}ms"
                            )
                        case other => fail(s"expected BrowserElementNotFoundException, got $other")
                    end match
                }
            }
        }
    }

    // ── tryAcceptCookies with per-call schedule ──────────────────────────

    "tryAcceptCookies with per-call schedule honours per-call budget" in run {
        withBrowser {
            // A cookie banner that never disappears after click; tryAcceptCookies should abort
            onPage("""<html><body>
                <button id='accept-cookie' class='accept-cookie'>Accept</button>
            </body></html>""") {
                val start = java.lang.System.currentTimeMillis()
                Abort.run[BrowserAssertionException] {
                    Browser.tryAcceptCookies(
                        schedule = Present(shortSchedule)
                    )
                }.map { result =>
                    val elapsed = java.lang.System.currentTimeMillis() - start
                    result match
                        case Result.Failure(_: BrowserAssertionTimedOutException) =>
                            assert(
                                elapsed >= floorMs && elapsed < ceilingMs,
                                s"per-call schedule should land in [${floorMs}, ${ceilingMs})ms but took ${elapsed}ms"
                            )
                        case other => fail(s"expected BrowserAssertionTimedOutException, got $other")
                    end match
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

    /** Empirical property: a per-call `Present(shortSchedule)` wins over an enclosing `Browser.withConfig(_.retrySchedule(2s))`. The
      * per-call value is returned verbatim by the `schedule.getOrElse(cfg.retrySchedule)` law, so elapsed lands in the per-call envelope
      * `[floorMs, ceilingMs)`, NOT the 2s outer envelope.
      */
    "retry-schedule precedence: per-call schedule wins over enclosing withConfig" in run {
        withBrowser {
            onPage("<html><body><h1>no match</h1></body></html>") {
                val start = java.lang.System.currentTimeMillis()
                Browser.withConfig(_.retrySchedule(Schedule.fixed(100.millis).maxDuration(2.seconds))) {
                    Abort.run[BrowserReadException] {
                        Browser.assertExists(
                            Browser.Selector.id("nonexistent"),
                            schedule = Present(shortSchedule)
                        )
                    }.map { result =>
                        val elapsed = java.lang.System.currentTimeMillis() - start
                        result match
                            case Result.Failure(_: BrowserElementNotFoundException) =>
                                assert(
                                    elapsed >= floorMs && elapsed < ceilingMs,
                                    s"per-call schedule must beat outer withConfig(2s); expected [${floorMs}, ${ceilingMs})ms but got ${elapsed}ms"
                                )
                            case other => fail(s"expected BrowserElementNotFoundException, got $other")
                        end match
                    }
                }
            }
        }
    }

    /** Empirical property: an inner `withConfig(_.retrySchedule(100ms))` shadows an outer `withConfig(_.retrySchedule(2s))` via
      * `configLocal.let`. With no per-call schedule, the effective schedule is the inner cfg's, and elapsed lands inside the inner
      * envelope, NOT the outer 2s envelope.
      */
    "retry-schedule precedence: innermost withConfig wins over outer withConfig" in run {
        withBrowser {
            onPage("<html><body><h1>no match</h1></body></html>") {
                val start = java.lang.System.currentTimeMillis()
                Browser.withConfig(_.retrySchedule(Schedule.fixed(100.millis).maxDuration(2.seconds))) {
                    Browser.withConfig(_.retrySchedule(Schedule.fixed(10.millis).maxDuration(100.millis))) {
                        Abort.run[BrowserReadException] {
                            Browser.assertExists(Browser.Selector.id("nonexistent"))
                        }.map { result =>
                            val elapsed = java.lang.System.currentTimeMillis() - start
                            result match
                                case Result.Failure(_: BrowserElementNotFoundException) =>
                                    assert(
                                        elapsed >= floorMs && elapsed < ceilingMs,
                                        s"inner withConfig must shadow outer; expected [${floorMs}, ${ceilingMs})ms but got ${elapsed}ms"
                                    )
                                case other => fail(s"expected BrowserElementNotFoundException, got $other")
                            end match
                        }
                    }
                }
            }
        }
    }

    /** Empirical property: `withTimeout(2.seconds)` mutates `cfg.retrySchedule` via `cfg.retrySchedule.maxDuration(2s)`, but a per-call
      * `Present(shortSchedule)` returns the per-call value verbatim and bypasses `cfg.retrySchedule` entirely. Therefore `withTimeout`
      * CANNOT cap a per-call override; elapsed lands in the per-call envelope, NOT the 2s timeout cap.
      */
    "retry-schedule precedence: withTimeout cannot cap a per-call schedule override" in run {
        withBrowser {
            onPage("<html><body><h1>no match</h1></body></html>") {
                val start = java.lang.System.currentTimeMillis()
                Browser.withTimeout(2.seconds) {
                    Abort.run[BrowserReadException] {
                        Browser.assertExists(
                            Browser.Selector.id("nonexistent"),
                            schedule = Present(shortSchedule)
                        )
                    }.map { result =>
                        val elapsed = java.lang.System.currentTimeMillis() - start
                        result match
                            case Result.Failure(_: BrowserElementNotFoundException) =>
                                assert(
                                    elapsed >= floorMs && elapsed < ceilingMs,
                                    s"per-call schedule bypasses withTimeout(2s); expected [${floorMs}, ${ceilingMs})ms but got ${elapsed}ms"
                                )
                            case other => fail(s"expected BrowserElementNotFoundException, got $other")
                        end match
                    }
                }
            }
        }
    }

end BrowserPerCallScheduleTest
