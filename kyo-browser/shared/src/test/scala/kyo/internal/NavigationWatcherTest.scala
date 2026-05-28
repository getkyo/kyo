package kyo.internal

import kyo.*

class NavigationWatcherTest extends kyo.BrowserTest:

    override def timeout = 60.seconds

    "NavigationWatcher.NavSnapshotWire round-trips through JSON" - {

        "decodes the {url, pushStateCount, beforeUnload} shape emitted by snapshotState" in run {
            val raw = """{"url":"https://example.test/page","pushStateCount":3,"beforeUnload":false}"""
            Json.decode[NavigationWatcher.NavSnapshotWire](raw) match
                case Result.Success(w) =>
                    assert(w.url == "https://example.test/page", s"url was `${w.url}`")
                    assert(w.pushStateCount == 3, s"pushStateCount was ${w.pushStateCount}")
                    assert(!w.beforeUnload, s"beforeUnload was ${w.beforeUnload}, expected false")
                case other => fail(s"expected Success but got $other")
            end match
        }

        "encode-decode round-trip preserves all three fields" in run {
            val original = NavigationWatcher.NavSnapshotWire(
                url = "https://example.com/path?a=1&b=2#frag",
                pushStateCount = 4,
                beforeUnload = true
            )
            val encoded = Json.encode(original)
            Json.decode[NavigationWatcher.NavSnapshotWire](encoded) match
                case Result.Success(w) => assert(w == original, s"round-trip mismatch: orig=$original decoded=$w")
                case other             => fail(s"expected Success but got $other")
        }

        "decode of a non-JSON payload returns Failure" in run {
            val bad = "not json at all"
            Json.decode[NavigationWatcher.NavSnapshotWire](bad) match
                case Result.Failure(_) => succeed
                case other             => fail(s"expected Failure for malformed JSON, got $other")
        }
    }

    // Pure-decision-matrix tests for the deadline-exhaustion handler. The flake on the JS BrowserSettlementTest
    // "expectNavigation aborts ... when the trigger does not navigate within the budget" was caused by the
    // NetworkIdle degradation path returning success at deadline even when the URL had not changed (i.e. the
    // trigger never committed a navigation). Once the deadline poll happened to land on a `Pending` probe
    // (e.g. an open network-idle window) the watcher would degrade to Settle.Load and silently succeed.
    //
    // The fix routes the (expectedDifferentFrom, settle, loadProbe) tuple through `decidePending` and only
    // returns `DegradeToLoad` when the live URL differs from the snapshot's URL. The bug case here pins the
    // "snapshot URL == live URL → AbortNavigationNeverCommitted" decision so a regression cannot reintroduce
    // the silent success.
    private val snap = NavigationWatcher.NavSnapshot(url = "https://example.com/start", pushStateCount = 0, beforeUnload = false)

    "NavigationWatcher.decidePending: NetworkIdle + Ready(sameUrlAsSnapshot, 200) → AbortNavigationNeverCommitted (regression pin for the expectNavigation no-op-trigger flake)" in {
        val decision = NavigationWatcher.decidePending(
            expectedDifferentFrom = Present(snap),
            settle = Browser.Settle.NetworkIdle,
            urlHint = snap.url,
            loadProbe = Present(NavigationWatcher.SettleStatus.Ready(snap.url, 200)),
            throwOnFailure = true
        )
        decision match
            case NavigationWatcher.PendingDecision.AbortNavigationNeverCommitted(`snap`.url, Browser.Settle.NetworkIdle) => succeed
            case other =>
                fail(
                    s"expected AbortNavigationNeverCommitted(${snap.url}, NetworkIdle) but got $other " +
                        "(if this returned DegradeToLoad the no-url-changed guard regressed and Browser.expectNavigation will silently succeed on no-op triggers)"
                )
        end match
    }

    "NavigationWatcher.decidePending: NetworkIdle + Ready(differentUrl, 200) → DegradeToLoad (page loaded, network never quiesced)" in {
        val decision = NavigationWatcher.decidePending(
            expectedDifferentFrom = Present(snap),
            settle = Browser.Settle.NetworkIdle,
            urlHint = "https://example.com/landed",
            loadProbe = Present(NavigationWatcher.SettleStatus.Ready("https://example.com/landed", 200)),
            throwOnFailure = true
        )
        decision match
            case NavigationWatcher.PendingDecision.DegradeToLoad => succeed
            case other                                           => fail(s"expected DegradeToLoad but got $other")
    }

    "NavigationWatcher.decidePending: NetworkIdle + Ready(differentUrl, 500, throwOnFailure=true) → AbortHttpError" in {
        val decision = NavigationWatcher.decidePending(
            expectedDifferentFrom = Present(snap),
            settle = Browser.Settle.NetworkIdle,
            urlHint = "https://example.com/landed",
            loadProbe = Present(NavigationWatcher.SettleStatus.Ready("https://example.com/landed", 500)),
            throwOnFailure = true
        )
        decision match
            case NavigationWatcher.PendingDecision.AbortHttpError("https://example.com/landed", 500) => succeed
            case other => fail(s"expected AbortHttpError but got $other")
    }

    "NavigationWatcher.decidePending: NetworkIdle + Ready(differentUrl, 500, throwOnFailure=false) → DegradeToLoad (HTTP-status check is gated)" in {
        val decision = NavigationWatcher.decidePending(
            expectedDifferentFrom = Present(snap),
            settle = Browser.Settle.NetworkIdle,
            urlHint = "https://example.com/landed",
            loadProbe = Present(NavigationWatcher.SettleStatus.Ready("https://example.com/landed", 500)),
            throwOnFailure = false
        )
        decision match
            case NavigationWatcher.PendingDecision.DegradeToLoad => succeed
            case other                                           => fail(s"expected DegradeToLoad but got $other")
    }

    "NavigationWatcher.decidePending: NetworkIdle + Pending → AbortLoadEventNeverFired" in {
        val decision = NavigationWatcher.decidePending(
            expectedDifferentFrom = Present(snap),
            settle = Browser.Settle.NetworkIdle,
            urlHint = "https://example.com/loading",
            loadProbe = Present(NavigationWatcher.SettleStatus.Pending("https://example.com/loading")),
            throwOnFailure = true
        )
        decision match
            case NavigationWatcher.PendingDecision.AbortLoadEventNeverFired("https://example.com/loading") => succeed
            case other => fail(s"expected AbortLoadEventNeverFired but got $other")
    }

    "NavigationWatcher.decidePending: Settle.Load + Absent loadProbe → AbortSettleTimeout (non-NetworkIdle modes skip the degrade path)" in {
        val decision = NavigationWatcher.decidePending(
            expectedDifferentFrom = Present(snap),
            settle = Browser.Settle.Load,
            urlHint = "https://example.com/loading",
            loadProbe = Absent,
            throwOnFailure = true
        )
        decision match
            case NavigationWatcher.PendingDecision.AbortSettleTimeout("https://example.com/loading", Browser.Settle.Load) => succeed
            case other => fail(s"expected AbortSettleTimeout but got $other")
    }

    "NavigationWatcher.decidePending: Absent expectedDifferentFrom + NetworkIdle + Ready(any, 200) → DegradeToLoad (no expected-URL constraint)" in {
        val decision = NavigationWatcher.decidePending(
            expectedDifferentFrom = Absent,
            settle = Browser.Settle.NetworkIdle,
            urlHint = "https://example.com/landed",
            loadProbe = Present(NavigationWatcher.SettleStatus.Ready("https://example.com/landed", 200)),
            throwOnFailure = true
        )
        decision match
            case NavigationWatcher.PendingDecision.DegradeToLoad => succeed
            case other                                           => fail(s"expected DegradeToLoad but got $other")
    }

    "NavigationWatcher.loadScheduleTimeout returns 5.seconds for a non-MaxDuration schedule" in run {
        // `Schedule.fixed(...)` (without `.maxDuration(...)`) is NOT a MaxDuration; the helper hits the
        // default branch and returns 5 seconds.
        val plain = Schedule.fixed(100.millis)
        val out   = NavigationWatcher.loadScheduleTimeout(plain)
        assert(out == 5.seconds, s"expected 5.seconds fallback for a non-MaxDuration schedule, got $out")

        // Sanity: a MaxDuration schedule yields its own duration (NOT the fallback); confirms the helper's
        // pattern-match isn't simply ignoring its input.
        val wrapped = Schedule.fixed(100.millis).maxDuration(2.seconds)
        val out2    = NavigationWatcher.loadScheduleTimeout(wrapped)
        assert(out2 == 2.seconds, s"expected 2.seconds for a MaxDuration schedule, got $out2")
    }

    // `decodeSettleState`'s JSON-decode failure branch.
    //
    // Wire-shape drift must not degrade to a silent `Pending("(unknown)")`, which would leave the
    // navigation gate spinning forever. The decoder must `Abort.fail` with a typed
    // `BrowserProtocolErrorException` so the surrounding settle loop surfaces the diagnostic immediately.
    "NavigationWatcher.decodeSettleState - JSON decode failure aborts with BrowserProtocolErrorException" in run {
        val bad = "this is not json"
        Abort.run[BrowserConnectionException](NavigationWatcher.decodeSettleState(bad)).map {
            case Result.Failure(_: BrowserProtocolErrorException) =>
                // Sanity: a valid JSON payload routes through the decode-success branch, NOT the fall-through.
                // This confirms the test is exercising the decode-failure branch and not just a degenerate decoder.
                val good = """{"ready":true,"url":"https://example.test/page","status":200}"""
                NavigationWatcher.decodeSettleState(good).map {
                    case NavigationWatcher.SettleStatus.Ready(url, status) =>
                        assert(url == "https://example.test/page", s"expected url='https://example.test/page', got '$url'")
                        assert(status == 200, s"expected status=200, got $status")
                    case other => fail(s"expected SettleStatus.Ready from a valid JSON payload, got $other")
                }
            case other => fail(s"expected Abort.fail(BrowserProtocolErrorException) for invalid-JSON payload, got $other")
        }
    }

    // ---- Browser-localhost scenarios ----

    // `waitForNext` is exposed as a public method on `NavigationWatcher` and had no direct
    // test until now; `Browser.goto` uses `armAroundNavigation` instead. Drive `waitForNext` directly while a
    // page-side `setTimeout` issues a `pushState` after the watcher has had time to install + snapshot. Behavioural
    // assertion on `waitForNext` resolving (the test only finishes if it does) plus final URL check.
    //
    // Race-freeness:
    //   1. The `Browser.eval` returns AFTER the IIFE has registered the setTimeout (and `'ok'`); by the time we
    //      call `waitForNext`, the timer is queued in the page event loop.
    //   2. `waitForNext` runs `installWatcher` + `snapshotState` synchronously via CDP before polling starts.
    //      Both complete (synchronous CDP round-trips) before the 300ms setTimeout fires, so the snapshot is
    //      taken with `pushStateCount=0`.
    //   3. `pollNavigated` then observes `__kyoNavRec.pushStateCount > snapshot.pushStateCount` and the method
    //      returns. The 5-second deadline is well above the 300ms scheduled fire.
    "NavigationWatcher.waitForNext: navigation triggers resolution" in run {
        withBrowserOnLocalhost {
            Browser.eval(
                """(() => {
                  |  setTimeout(() => { history.pushState({}, '', '#waited'); }, 300);
                  |  return 'ok';
                  |})()
                """.stripMargin
            ).andThen {
                Promise.init[Unit, Any].map { donePromise =>
                    NavigationWatcher.waitForNext(Browser.Settle.DomContentLoaded, 5.seconds).andThen {
                        // Behavioural completion signal: the Promise resolves only because waitForNext returned.
                        donePromise.complete(Result.succeed(())).unit
                    }.andThen {
                        for
                            // Promise.get is the explicit synchronisation primitive. It cannot
                            // resolve until waitForNext returned successfully.
                            _ <- donePromise.get
                            u <- Browser.url
                        yield assert(
                            u.endsWith("#waited"),
                            s"expected URL to end with '#waited' after waitForNext returned, got '$u'"
                        )
                        end for
                    }
                }
            }
        }
    }

    // `Settle.Load` reaches the `complete` branch (NOT `interactive-or-complete`). We
    // serve a page whose body is small (interactive fires fast) but whose `<img>` resource is delivered slowly.
    // `readyState` reaches `interactive` quickly but stays there until the slow resource finishes; only then does
    // `complete` fire. With `Settle.DomContentLoaded` the watcher returns at `interactive`; with `Settle.Load`
    // it must wait past DCL.
    "NavigationWatcher Settle.Load waits past DOMContentLoaded for the load event" in run {
        withBrowserOnLocalhost {
            // Slow image: a 400-byte image whose response is delayed on the server side. The exact delay is
            // engineered to be observable but well under the 5-second loadSchedule fallback. The assertion
            // is behavioural (`readyState === 'complete'` AT RETURN), NOT on elapsed timing.
            Browser.use { tab =>
                slowImageServer { (host, port) =>
                    val url = s"http://$host:$port/page"
                    Browser.runOn(tab) {
                        // Use `Load` settle and assert that on return readyState is 'complete' (not just
                        // 'interactive'). With `Load` the watcher polls until 'complete' - which only happens
                        // after the slow image resolves. Behavioural contract.
                        Browser.withConfig(_.loadSchedule(Schedule.fixed(50.millis).maxDuration(20.seconds))) {
                            Browser.goto(url, Browser.Settle.Load)
                        }.andThen {
                            Browser.eval("document.readyState").map { rs =>
                                assert(
                                    rs == "complete",
                                    s"expected readyState='complete' after Load settle but got '$rs' " +
                                        "(would indicate the Load target-state branch did NOT engage past DCL)"
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // `ensureNetworkTracking` is idempotent; when called repeatedly on the same page the
    // install body executes exactly ONCE. We observe via a setter on `__kyoNetTrackingInstalled` that increments an
    // AtomicRef-backed counter (writes from the install body are intercepted; the early-return branch on
    // subsequent calls does NOT write). The AtomicRef is the synchronisation primitive: no sleeps.
    "NavigationWatcher.ensureNetworkTracking is a no-op on repeated invocations (idempotent install)" in run {
        withBrowserOnLocalhost {
            Browser.use { tab =>
                Browser.runOn(tab) {
                    // Install a counting setter on `window.__kyoNetTrackingInstalled` BEFORE the first call. The
                    // install body's `window.__kyoNetTrackingInstalled = true` write goes through the setter and
                    // bumps the page-side counter. The early-return path on subsequent calls reads the flag (the
                    // getter returns `true`) and returns 'ok' WITHOUT writing; so the counter does NOT increment.
                    Browser.eval(
                        """(() => {
                          |  let writes = 0;
                          |  let stored = false;
                          |  Object.defineProperty(window, '__kyoNetTrackingInstalled', {
                          |    configurable: true,
                          |    get: function() { return stored; },
                          |    set: function(v) { writes++; stored = v; }
                          |  });
                          |  window.__kyoNetTrackingInstallWrites = function() { return writes; };
                          |  return 'ok';
                          |})()
                        """.stripMargin
                    ).andThen {
                        for
                            // First call; install body runs, writes the flag (counter → 1).
                            _      <- NavigationWatcher.ensureNetworkTracking(Browser.Settle.NetworkIdle)
                            after1 <- Browser.eval("String(window.__kyoNetTrackingInstallWrites())")
                            // Second call; early return, no write. Counter must stay at 1.
                            _      <- NavigationWatcher.ensureNetworkTracking(Browser.Settle.NetworkIdle)
                            after2 <- Browser.eval("String(window.__kyoNetTrackingInstallWrites())")
                            // Third call; same.
                            _      <- NavigationWatcher.ensureNetworkTracking(Browser.Settle.NetworkIdle)
                            after3 <- Browser.eval("String(window.__kyoNetTrackingInstallWrites())")
                        yield
                            assert(
                                after1 == "1",
                                s"expected install-write counter == 1 after first ensureNetworkTracking, got $after1 " +
                                    "(install body did not write through the spy setter)"
                            )
                            assert(
                                after2 == "1",
                                s"expected install-write counter UNCHANGED at 1 after second ensureNetworkTracking, got $after2 " +
                                    "(early-return path did NOT activate - install body re-ran)"
                            )
                            assert(
                                after3 == "1",
                                s"expected install-write counter UNCHANGED at 1 after third ensureNetworkTracking, got $after3 " +
                                    "(early-return path did NOT activate - install body re-ran)"
                            )
                        end for
                    }
                }
            }
        }
    }

    // a 4xx/5xx response with `throwOnFailure=true` raises
    // `BrowserNavigationFailedException` through the `awaitSettle` path.
    // Behavioural assertion on the Abort SHAPE, NOT on log output.
    "NavigationWatcher awaitSettle raises BrowserNavigationFailedException on 5xx with throwOnFailure=true" in run {
        withBrowserOnLocalhost {
            statusServer(HttpStatus.InternalServerError, "/boom") { (host, port) =>
                val url = s"http://$host:$port/boom"
                Abort.run[BrowserNavigationException] {
                    Browser.goto(url).unit
                }.map {
                    case Result.Failure(ex: BrowserNavigationFailedException) =>
                        assert(
                            ex.error.contains("HTTP 500") || ex.error.contains("500"),
                            s"expected HTTP 500 reference in failure 'error' field but got '${ex.error}'"
                        )
                        assert(
                            ex.url.contains(url) || ex.url.contains("/boom"),
                            s"expected url to reference '/boom' but got '${ex.url}'"
                        )
                    case other =>
                        fail(s"expected BrowserNavigationFailedException with throwOnFailure=true on 5xx but got $other")
                }
            }
        }
    }

    // ---- helpers (browser-localhost only, no data: URLs) ----

    /** Tiny HTTP fixture that serves `/page` (a 200 with an `<img src="/slow.png">`) and `/slow.png` after a fixed server-side delay. Used
      * by the `Settle.LoadEvent` test to manufacture a window where readyState='interactive' precedes 'complete' by an observable margin.
      * The LoadEvent settle mode must wait through that window.
      */
    private def slowImageServer[A, S](f: (String, Int) => A < (Browser & S))(using
        Frame
    ): A < (Browser & Scope & Abort[BrowserConnectionException] & Async & S) =
        val pageBytes = Span.fromUnsafe(
            """<html><head></head><body><img src="/slow.png" alt=""></body></html>""".getBytes("UTF-8")
        )
        // 1x1 transparent PNG (smallest legal): we don't need real image bytes, only a successful GET that
        // resolves AFTER an observable delay so 'load' fires past DCL.
        val pngBytes = Span.fromUnsafe(
            Array[Byte](
                0x89.toByte,
                0x50,
                0x4e,
                0x47,
                0x0d,
                0x0a,
                0x1a,
                0x0a,
                0x00,
                0x00,
                0x00,
                0x0d,
                0x49,
                0x48,
                0x44,
                0x52,
                0x00,
                0x00,
                0x00,
                0x01,
                0x00,
                0x00,
                0x00,
                0x01,
                0x08,
                0x06,
                0x00,
                0x00,
                0x00,
                0x1f,
                0x15,
                0xc4.toByte,
                0x89.toByte,
                0x00,
                0x00,
                0x00,
                0x0d,
                0x49,
                0x44,
                0x41,
                0x54,
                0x78,
                0x9c.toByte,
                0x63,
                0x00,
                0x01,
                0x00,
                0x00,
                0x05,
                0x00,
                0x01,
                0x0d,
                0x0a,
                0x2d,
                0xb4.toByte,
                0x00,
                0x00,
                0x00,
                0x00,
                0x49,
                0x45,
                0x4e,
                0x44,
                0xae.toByte,
                0x42,
                0x60,
                0x82.toByte
            )
        )
        val pageHandler = HttpRoute.getRaw("/page").response(_.bodyBinary).handler { _ =>
            HttpResponse.ok(pageBytes).addHeader("Content-Type", "text/html; charset=utf-8")
        }
        // Server-side delay: 500ms is large enough to be observable on any CI yet small enough to fit comfortably
        // under the 8-second loadSchedule used by the test. We delay BEFORE writing the response so the page's
        // load event waits for /slow.png to resolve.
        val pngHandler = HttpRoute.getRaw("/slow.png").response(_.bodyBinary).handler { _ =>
            Async.sleep(500.millis).andThen(
                HttpResponse.ok(pngBytes).addHeader("Content-Type", "image/png")
            )
        }
        withLocalhostServer(pageHandler, pngHandler)(f)
    end slowImageServer

    /** Server fixture that always returns the supplied `status` for `path`. Used by the throwOnFailure test to drive a 5xx through the
      * `awaitSettle` path without depending on Chrome's interpretation of the response body.
      */
    private def statusServer[A, S](status: HttpStatus, path: String)(f: (String, Int) => A < (Browser & S))(using
        Frame
    ): A < (Browser & Scope & Abort[BrowserConnectionException] & Async & S) =
        val body = Span.fromUnsafe(s"<html><body>status ${status.code}</body></html>".getBytes("UTF-8"))
        val handler = HttpRoute.getRaw(path).response(_.bodyBinary).handler { _ =>
            HttpResponse(status)
                .addField("body", body)
                .addHeader("Content-Type", "text/html; charset=utf-8")
        }
        HttpServer.init(0, "127.0.0.1")(handler).map { server =>
            f(server.host, server.port)
        }
    end statusServer

end NavigationWatcherTest
