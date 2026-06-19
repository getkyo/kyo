package kyo

import AllowUnsafe.embrace.danger
import kyo.BrowserElementNotActionableException.Reason
import kyo.internal.SharedChrome

// ── Fixture overview ────────────────────────────────────────────────────────
//
// BrowserTest exposes a small set of helpers; pick the cheapest fixture that
// covers what the scenario needs. Helpers marked "boots Chrome" amortise via
// `SharedChrome` so the second call onward pays only the per-tab cost.
//
// Browser-booting fixtures (each opens a fresh Browser scope):
//   - withBrowser(body)
//       Universal entry. Boots Chrome (shared) and runs `body` inside a fresh
//       tab. The default for any test that needs a working `Browser` effect.
//   - withBrowserOnLocalhost(body)
//       Same as withBrowser, but first navigates to `http://localhost:$port/json/version`.
//       Cookie / storage tests need a real http://localhost origin; data: URLs
//       won't carry cookies. Use this when the test reads or writes cookies.
//   - withBrowserOnLocalhostIframe(outerHtml, innerHtml)(body)
//       Boots a localhost HTTP server hosting `outerHtml` at `/parent` and
//       `innerHtml` at `/child` (with `{iframe-src}` substituted in the parent),
//       opens a tab on the parent page, then runs `body`. Use when the test
//       needs a SAME-ORIGIN iframe lifecycle (`srcdoc` doesn't fire the same
//       `Page.frameAttached` events as a real cross-document load).
//
// HTTP-server fixture (boots an HTTP server, NOT Chrome):
//   - withLocalhostServer(handlers*)(f)
//       Boots a localhost HTTP server on an OS-assigned port at `127.0.0.1`
//       and runs `f(host, port)` so callers can construct any URL. The server
//       is released when the surrounding Scope exits. Lower-level building
//       block for ad-hoc fixtures and the cross-origin iframe tests.
//
// Page-construction helpers (do NOT boot Chrome by themselves):
//   - page(html)
//       Builds a `data:text/html;charset=utf-8,...` URL containing `html`.
//       Convenient for one-shot scenarios that don't need a real origin.
//   - srcdocPage(outer, srcdoc)
//       Builds a data: URL where `{srcdoc}` in `outer` is replaced with the
//       HTML-attribute-escaped `srcdoc` value, suitable for `<iframe srcdoc=...>`.
//   - onPage(html)(body)
//       Sugar: `Browser.goto(page(html)).andThen(body)`.
//
// Assertion / retry helpers (cheap; no Chrome boot of their own):
//   - evalAssert(js, expected)
//       Runs JS via `Browser.eval` and asserts the returned string equals
//       `expected`. Default-message wrapper around the common idiom.
//   - tight(v)
//       Installs a 50ms × 3-retry schedule for the enclosed block. Use for
//       fast assertions that should settle quickly.
//   - slow(v)
//       Installs a 200ms × 15-retry schedule for the enclosed block. Use
//       when Chrome / page warmup pushes assertions past the tight budget.
//   - expectNotActionable(action, reason)
//       Asserts an interaction aborts with `BrowserElementNotActionableException`
//       carrying the exact `Reason` you pass.
//   - expectNotActionablePF(action)(check)
//       Same shape but the `check` is a `PartialFunction[Reason, Unit]` for
//       scenarios where the precise Reason discriminator carries a structural
//       payload to assert on (e.g. NotInViewport rect coordinates).
//
// Cost ordering (cheapest first; one-time costs amortise):
//   page / srcdocPage / onPage : no I/O, just a String build.
//   evalAssert / tight / slow / expectNotActionable / expectNotActionablePF :
//       no I/O of their own; cost is the operation they wrap.
//   withLocalhostServer : ~ms; per-call HTTP server bring-up.
//   withBrowser / withBrowserOnLocalhost : ~ms after the first call (Chrome
//       is shared via SharedChrome); cold-boot is ~2.8s the very first time.
//   withBrowserOnLocalhostIframe : ~withBrowser + ~ms for the server.
//
// ────────────────────────────────────────────────────────────────────────────

abstract class BrowserTest extends BaseBrowserTest:

    /** Cold-Chrome warmup gate. The shared-Chrome first-call cost (~2.8s for Chrome launch + CDP roundtrip) would otherwise blow
      * per-call schedule budgets in `BrowserPerCallScheduleTest`. Eat that cost on the FIRST integration-test call so subsequent tests see a
      * hot Chrome. The CAS-once-then-skip pattern fires `Browser.eval("1+1")` exactly once per test-class instance: the first caller wins
      * the compareAndSet and runs the warmup; every subsequent caller sees `needsWarmup = false` and skips.
      *
      * Uses `kyo.AtomicBoolean` via the Unsafe-init seam for the one-time val initialisation; safe API thereafter. `lazy val` on a Kyo
      * computation memoizes the description (not the result), so the AtomicBoolean CAS pattern is the correct Kyo idiom for "run once
      * across the test session".
      */
    private val warmupDone: AtomicBoolean = AtomicBoolean.Unsafe.init(false).safe

    private def warmupGate[A, S](f: A < (Browser & S))(using Frame): A < (Browser & Abort[BrowserReadException] & S) =
        warmupDone.compareAndSet(false, true).map { needsWarmup =>
            if needsWarmup then Browser.eval("1+1").unit.andThen(f)
            else f
        }

    /** Marker substring in [[kyo.internal.ChromeDownloader.unsupportedPlatformMessage]] used to route the failure to
      * ScalaTest `cancel(...)` instead of test failure. Keep in sync with that source of truth.
      */
    private val unsupportedPlatformMarker = "cannot auto-download chrome-headless-shell"

    /** Translates the "unsupported platform" abort from [[kyo.internal.ChromeDownloader.resolvePlatform]] into a clean ScalaTest
      * `cancel(...)` so platforms with no auto-download (linux-arm64, win-arm64) report as canceled with install instructions rather than
      * red failures. The instruction text comes from the abort itself, so the same guidance reaches end users hitting `Browser.run` outside
      * a test context.
      */
    private def cancelOnUnsupportedPlatform[A, S](
        f: A < (Async & Scope & Abort[BrowserSetupException] & S)
    )(using Frame): A < (Async & Scope & Abort[BrowserSetupException] & S) =
        Abort.recover[BrowserSetupException] { (ex: BrowserSetupException) =>
            val msg = ex.getMessage
            if msg != null && msg.contains(unsupportedPlatformMarker) then Sync.defer(cancel(msg))
            else Abort.fail[BrowserSetupException](ex)
        } { f }

    def withBrowser[A, S](f: A < (Browser & S))(using
        Frame
    ): A < (Async & Scope & Abort[BrowserReadException | BrowserSetupException] & S) =
        cancelOnUnsupportedPlatform {
            SharedChrome.withUrl(url => Browser.run(url)(warmupGate(f)))
        }

    /** Boots a tab on the localhost DevTools JSON page (cookies / localStorage tests need a real http://localhost origin). */
    def withBrowserOnLocalhost[A, S](f: A < (Browser & S))(using
        Frame
    ): A < (Async & Scope & Abort[BrowserReadException | BrowserSetupException] & S) =
        cancelOnUnsupportedPlatform {
            SharedChrome.withUrl { url =>
                val port    = url.split(":")(2).split("/")(0)
                val httpUrl = s"http://localhost:$port/json/version"
                Browser.run(url)(warmupGate(Browser.goto(httpUrl).andThen(f)))
            }
        }

    /** Boots a single-/multi-handler localhost HTTP server bound to an OS-assigned port at `127.0.0.1` and runs `f` with `(host, port)` so
      * callers can construct any URL they need (the path is helper-defined). The server is released when the surrounding `Scope` exits; no
      * explicit teardown needed. Used as the lower-level building block for `withBrowserOnLocalhostIframe` and ad-hoc fixtures. Generalises
      * the inlined `slowImageServer` / `statusServer` patterns used elsewhere in the test tree.
      */
    def withLocalhostServer[A, S](handlers: HttpHandler[?, ?, ?]*)(f: (String, Int) => A < S)(using
        Frame
    ): A < (Async & Scope & S) =
        HttpServer.init(0, "127.0.0.1")(handlers*).map { server =>
            f(server.host, server.port)
        }

    /** Evaluates `js` and asserts the string result equals `expected`. Wraps the `Browser.eval(js).map(v => assert(v == expected))` pattern
      * with a default diagnostic message and the same effect row as [[Browser.eval]].
      */
    def evalAssert(js: String, expected: String)(using
        Frame,
        kyo.test.AssertScope
    ): Unit < (Browser & Abort[BrowserReadException]) =
        Browser.eval(js).map { v =>
            assert(v == expected, s"expected `$expected` but got `$v` for js `$js`")
        }

    // ── tight / slow retry schedules ────────────────────────────────────────
    //
    // Both helpers install a fixed-interval, bounded-count schedule via
    // `Browser.withConfig(_.retrySchedule(...))`. The cumulative duration is
    // the cap a test is willing to wait for a flaky assertion to settle.
    //
    //   tight = Schedule.fixed(50.millis).take(3)
    //     Cumulative cap: ~150 ms (3 retries at 50 ms apart).
    //     For: assertions that should settle inside one or two animation
    //     frames; negative tests where we expect a typed Abort to fire fast;
    //     scenarios where waiting longer would mask a real bug.
    //
    //   slow = Schedule.fixed(200.millis).take(15)
    //     Cumulative cap: ~3 s (15 retries at 200 ms apart).
    //     For: scenarios that depend on Chrome / page warmup (settle, network
    //     idle, fetch completion, JS handler execution chain); cross-origin
    //     iframe lifecycle tests; tests that compose multiple settle gates.
    //
    // The numbers are empirical: 50 ms is one slow-system animation frame
    // (the rule of thumb is "an assertion that has already failed by 150 ms
    // is genuinely failing, not late"); 200 ms × 15 covers the worst observed
    // cold-Chrome navigation+settle on the slowest CI runner (~2.5 s) plus a
    // 20% margin.
    //
    // If you find yourself reaching for a longer schedule, ask whether the
    // test is asserting on the right gate; a longer retry budget commonly
    // masks a missing settle barrier rather than fixes a real flake.
    //
    // The schedules deliberately live here as test-only helpers rather than
    // as named SessionConfig factory methods so production code paths don't
    // accidentally pick them up.
    // ────────────────────────────────────────────────────────────────────────

    /** Tight retry schedule for fast mutation/assertion loops. Cap ~150 ms. */
    def tight[A, S](v: A < S)(using Frame): A < S =
        Browser.withConfig(_.retrySchedule(Schedule.fixed(50.millis).take(3)))(v)

    /** Slow retry schedule for sites that need more time to settle. Cap ~3 s. */
    def slow[A, S](v: A < S)(using Frame): A < S =
        Browser.withConfig(_.retrySchedule(Schedule.fixed(200.millis).take(15)))(v)

    /** Asserts a `Browser` action aborts with [[BrowserElementNotActionableException]] for the given reason. */
    def expectNotActionable[S](
        action: Unit < (Browser & Abort[BrowserElementException] & S),
        reason: Reason
    )(using Frame, kyo.test.AssertScope): Unit < (Browser & Async & S) =
        tight(Abort.run[BrowserElementException](action)).map {
            case Result.Failure(ex: BrowserElementNotActionableException) =>
                assert(ex.reason == reason, s"expected reason $reason but got ${ex.reason}")
            case other =>
                fail(s"expected NotActionable($reason) but got $other")
        }

    /** Asserts a `Browser` action aborts with [[BrowserElementNotActionableException]] matching the given partial function. */
    def expectNotActionablePF[S](
        action: Unit < (Browser & Abort[BrowserElementException] & S)
    )(check: PartialFunction[Reason, Unit])(using Frame, kyo.test.AssertScope): Unit < (Browser & Async & S) =
        tight(Abort.run[BrowserElementException](action)).map {
            case Result.Failure(ex: BrowserElementNotActionableException) =>
                if check.isDefinedAt(ex.reason) then
                    check(ex.reason)
                    ()
                else
                    fail(s"unexpected reason ${ex.reason} - partial function not defined for it")
            case other =>
                fail(s"expected NotActionable but got $other")
        }

    /** Navigates to a `data:` URL containing `html` and runs `body`.
      *
      * `body` is a by-name parameter so that the block is evaluated lazily; after `goto` completes and the page is ready. This ensures that
      * any timing measurements inside `body` (e.g. `val start = currentTimeMillis()`) capture the time after navigation, not before.
      */
    def onPage[A, S](html: String)(body: => A < (Browser & Async & S))(using
        Frame
    ): A < (Browser & Async & Abort[BrowserReadException] & S) =
        Browser.goto(page(html)).andThen(body)

    def page(html: String): String =
        s"data:text/html;charset=utf-8,${BrowserTest.percentEncode(html)}"

    /** Builds a `data:` URL whose page contains an `<iframe srcdoc="...">` carrying `srcdoc`'s HTML. The iframe inherits the parent's
      * origin, which is sufficient for [[Browser.IFrame.of]] / [[Browser.withIFrame]] to scope actions into the inline document without
      * booting an HTTP server. The substitution placeholder `{srcdoc}` in `outer` is replaced with the percent-encoded `srcdoc` value (so
      * the inline iframe markup survives the surrounding `data:` URL encoding pass).
      */
    def srcdocPage(outer: String, srcdoc: String): String =
        page(outer.replace("{srcdoc}", BrowserTest.htmlAttributeEscape(srcdoc)))

    /** Boots a localhost HTTP server serving `parent.html` at `/parent` (with `{iframe-src}` substituted to `http://localhost:$port/child`)
      * and `child.html` at `/child`, opens a browser tab on the parent page, then runs `f`. The two-page setup is what the spec §7.2
      * fixture sketch describes; useful for tests that need a real same-origin iframe (e.g. lifecycle scenarios where srcdoc would not
      * trigger the same `Page.frameAttached` events).
      */
    def withBrowserOnLocalhostIframe[A, S](outerHtml: String, innerHtml: String)(f: A < (Browser & S))(using
        Frame
    ): A < (Async & Scope & Abort[BrowserReadException | BrowserSetupException | HttpException] & S) =
        val parentBytes = (port: Int) =>
            Span.fromUnsafe(outerHtml.replace("{iframe-src}", s"http://localhost:$port/child").getBytes("UTF-8"))
        val childBytes = Span.fromUnsafe(innerHtml.getBytes("UTF-8"))
        Promise.init[Int, Any].map { portRef =>
            val parentHandler = HttpRoute.getRaw("/parent").response(_.bodyBinary).handler { _ =>
                portRef.get.map(p => HttpResponse.ok(parentBytes(p)).addHeader("Content-Type", "text/html; charset=utf-8"))
            }
            val childHandler = HttpRoute.getRaw("/child").response(_.bodyBinary).handler { _ =>
                HttpResponse.ok(childBytes).addHeader("Content-Type", "text/html; charset=utf-8")
            }
            HttpServer.init(0, "localhost")(parentHandler, childHandler).map { server =>
                portRef.completeDiscard(Result.succeed(server.port)).andThen {
                    withBrowser {
                        Browser.goto(s"http://localhost:${server.port}/parent").andThen(f)
                    }
                }
            }
        }
    end withBrowserOnLocalhostIframe

end BrowserTest

object BrowserTest:

    /** Escapes `s` so it can be embedded as the value of an HTML attribute delimited by double-quotes.
      *
      * Replaces `&`, `<`, `>`, and `"`. Used by [[BrowserTest.srcdocPage]] to embed an entire HTML document inside an `<iframe srcdoc="…">`
      * attribute. Single-quote characters are intentionally NOT escaped; `srcdoc` examples below use double quotes for the attribute
      * delimiter.
      */
    private[kyo] def htmlAttributeEscape(s: String): String =
        s.iterator.map {
            case '&' => "&amp;"
            case '<' => "&lt;"
            case '>' => "&gt;"
            case '"' => "&quot;"
            case c   => c.toString
        }.mkString
    end htmlAttributeEscape

    /** Cross-platform RFC-3986 percent-encoder used to embed a page's HTML inside a `data:` URL. Thin alias for
      * [[kyo.internal.PercentEncode]].
      */
    private[kyo] def percentEncode(s: String): String = kyo.internal.PercentEncode(s)

end BrowserTest
