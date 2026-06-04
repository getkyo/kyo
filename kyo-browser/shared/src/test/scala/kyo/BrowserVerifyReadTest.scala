package kyo

import kyo.internal.SettleRead

class BrowserVerifyReadTest extends BrowserTest:

    override def timeout = 90.seconds

    // settle returns the stabilized value of a converging expression.
    // The page-side JS counter starts at 0, increments by 1 every 20ms, and stops at 42 after ~840ms.
    // With assertionStabilityWindow = 100ms (default), settle must wait until the counter holds at 42
    // for 100ms before returning. The result must be 42, not an early intermediate value.
    "settle returns the stabilized value of a converging expression" in run {
        withBrowser {
            onPage("""<body>
                <script>
                    window.__kyoVal = 0;
                    setInterval(() => { if (window.__kyoVal < 42) window.__kyoVal++; }, 20);
                </script>
            </body>""") {
                SettleRead.settle("settle-stabilizes", "String(window.__kyoVal)") { raw =>
                    raw.toInt
                }.map { result =>
                    assert(result == 42, s"expected 42 but got $result")
                }
            }
        }
    }

    // settle maps a stable-absent sentinel through decode without raising.
    // The JS expression always returns the literal string "__absent__". With a tight assertionStabilityWindow,
    // the sentinel is trivially stable and decode maps it to Absent. No BrowserAssertionTimedOutException is raised.
    "settle maps a stable-absent sentinel through decode without raising" in run {
        withBrowser {
            onPage("<body></body>") {
                Browser.withConfig(_.assertionStabilityWindow(50.millis)) {
                    SettleRead.settle("settle-absent", "'__absent__'") { raw =>
                        if raw == "__absent__" then Absent
                        else Present(raw)
                    }.map { result =>
                        assert(result == Absent, s"expected Absent but got $result")
                    }
                }
            }
        }
    }

    // settle re-samples on a never-converging expression and aborts.
    // The JS counter flickers at 5ms, faster than the 100ms assertionStabilityWindow, so sampleWindow
    // always returns Unstable. The retrySchedule is capped at 300ms so the test completes quickly.
    // The result must be a Failure containing a BrowserReadException.
    "settle re-samples on a never-converging expression and aborts" in run {
        withBrowser {
            onPage("""<body>
                <script>
                    window.__kyoFlicker = Date.now();
                    setInterval(() => { window.__kyoFlicker = Date.now(); }, 5);
                </script>
            </body>""") {
                Browser.withConfig(
                    _.retrySchedule(Schedule.fixed(50.millis).maxDuration(300.millis))
                        .assertionStabilityWindow(100.millis)
                ) {
                    Abort.run[BrowserReadException] {
                        SettleRead.settle("settle-never-converges", "String(window.__kyoFlicker)") { raw =>
                            raw
                        }
                    }.map { result =>
                        assert(result.isFailure, s"expected a Failure but got $result")
                    }
                }
            }
        }
    }

    // settle reads its bound from configLocal, not a hardcoded literal.
    // Two runs use the same never-converging counter. Override A uses a tight 200ms retrySchedule;
    // override B uses a wider 600ms retrySchedule. Both must abort a BrowserReadException.
    // The elapsed time under A must be strictly less than the elapsed time under B,
    // proving the bound comes from configLocal rather than a hardcoded constant.
    "settle reads its bound from configLocal and not a hardcoded constant" in run {
        withBrowser {
            onPage("""<body>
                <script>
                    window.__kyoFlicker2 = Date.now();
                    setInterval(() => { window.__kyoFlicker2 = Date.now(); }, 5);
                </script>
            </body>""") {
                val runSettle =
                    Abort.run[BrowserReadException] {
                        SettleRead.settle("settle-config-bound", "String(window.__kyoFlicker2)") { raw =>
                            raw
                        }
                    }
                timed(Browser.withConfig(
                    _.retrySchedule(Schedule.fixed(50.millis).maxDuration(200.millis))
                        .assertionStabilityWindow(80.millis)
                )(runSettle)).map { case (elapsedA, resultA) =>
                    timed(Browser.withConfig(
                        _.retrySchedule(Schedule.fixed(50.millis).maxDuration(600.millis))
                            .assertionStabilityWindow(80.millis)
                    )(runSettle)).map { case (elapsedB, resultB) =>
                        assert(resultA.isFailure, s"expected Failure under override A but got $resultA")
                        assert(resultB.isFailure, s"expected Failure under override B but got $resultB")
                        assert(elapsedA < 500.millis, s"override A elapsed ${elapsedA} exceeds 500ms ceiling")
                        assert(elapsedB < 900.millis, s"override B elapsed ${elapsedB} exceeds 900ms ceiling")
                        assert(
                            elapsedA < elapsedB,
                            s"expected override A ($elapsedA) to finish before override B ($elapsedB)"
                        )
                    }
                }
            }
        }
    }

    // boundingRect returns Bounds for a present element.
    // A <div> at absolute position 20,30 with 100x50 dimensions. After settle, boundingRect must return
    // Present(Bounds(20.0, 30.0, 100.0, 50.0)) and the derived accessors must be right=120.0, area=5000.0.
    "boundingRect returns Bounds for a present element" in run {
        withBrowser {
            onPage("""<html><body style="margin:0;padding:0;">
                <div class="panel" style="position:absolute;left:20px;top:30px;width:100px;height:50px;background:red;"></div>
            </body></html>""") {
                Browser.boundingRect(Browser.Selector.css(".panel")).map { result =>
                    result match
                        case Absent => fail("expected Present(Bounds) but got Absent")
                        case Present(b) =>
                            assert(b.x == 20.0, s"expected x=20.0 but got ${b.x}")
                            assert(b.y == 30.0, s"expected y=30.0 but got ${b.y}")
                            assert(b.width == 100.0, s"expected width=100.0 but got ${b.width}")
                            assert(b.height == 50.0, s"expected height=50.0 but got ${b.height}")
                            assert(b.right == 120.0, s"expected right=120.0 but got ${b.right}")
                            assert(b.area == 5000.0, s"expected area=5000.0 but got ${b.area}")
                }
            }
        }
    }

    // boundingRect returns Absent for a missing element and display:none element.
    // No BrowserElementNotFoundException must be raised.
    "boundingRect returns Absent for a missing and a display-none element" in run {
        withBrowser {
            onPage("""<html><body style="margin:0;">
                <div class="hidden" style="display:none;"></div>
            </body></html>""") {
                Browser.boundingRect(Browser.Selector.css(".gone")).map { goneResult =>
                    Browser.boundingRect(Browser.Selector.css(".hidden")).map { hiddenResult =>
                        assert(goneResult == Absent, s"expected Absent for .gone but got $goneResult")
                        assert(hiddenResult == Absent, s"expected Absent for .hidden but got $hiddenResult")
                    }
                }
            }
        }
    }

    // boundingRect settles on a moving element.
    // A .box starts at left:0 and CSS-transitions to left:200px over 400ms. boundingRect issued
    // mid-transition must return a settled x > 150.0 (close to the final position, not the start).
    "boundingRect settles on a moving element" in run {
        withBrowser {
            onPage("""<html><body style="margin:0;padding:0;">
                <div class="box" style="position:absolute;top:0;left:0;width:50px;height:50px;background:blue;transition:left 400ms linear;"></div>
                <script>
                    setTimeout(() => { document.querySelector('.box').style.left = '200px'; }, 50);
                </script>
            </body></html>""") {
                Browser.withConfig(
                    _.retrySchedule(Schedule.fixed(50.millis).maxDuration(3.seconds))
                        .assertionStabilityWindow(120.millis)
                ) {
                    Browser.boundingRect(Browser.Selector.css(".box")).map { result =>
                        result match
                            case Absent => fail("expected Present(Bounds) but got Absent")
                            case Present(b) =>
                                assert(b.x > 150.0, s"expected settled x > 150.0 (near 200.0) but got ${b.x}")
                    }
                }
            }
        }
    }

    // boundingRect reports the actual rect for an off-viewport element: negative left and a top below the
    // viewport must be reflected in the returned coordinates (not clamped to the viewport).
    "boundingRect reports the actual rect for an off-viewport element" in run {
        withBrowser {
            onPage(
                "<div id='b' style='position:absolute;left:-500px;top:5000px;width:50px;height:50px;background:blue'></div>"
            ) {
                Browser.setViewport(1024, 768).andThen {
                    Browser.boundingRect(Browser.Selector.id("b")).map {
                        case Present(b) =>
                            assert(b.x < 0.0, s"expected x < 0 but got ${b.x}")
                            assert(b.y > 768.0, s"expected y > viewport-height but got ${b.y}")
                            assert((b.width - 50.0).abs <= 1.0, s"expected width ~50 but got ${b.width}")
                            assert((b.height - 50.0).abs <= 1.0, s"expected height ~50 but got ${b.height}")
                        case Absent =>
                            fail("expected Present(Bounds) for off-viewport element, got Absent")
                    }
                }
            }
        }
    }

    // boundingRect inside a same-origin iframe returns coordinates in the top-level viewport: the inner
    // element's offset carries the iframe's own position (CDP DOM.getBoxModel, not JS getBoundingClientRect).
    "boundingRect inside a same-origin iframe returns coords in top-level viewport" in run {
        val parent =
            """<body>
                <iframe id="f" data-testid="frame" srcdoc="{srcdoc}"
                        style="position:absolute;left:100px;top:50px;width:300px;height:200px;border:0"></iframe>
            </body>"""
        val inner =
            """<body><div id="inner" style="position:absolute;left:20px;top:30px;width:40px;height:40px;background:orange"></div></body>"""
        withBrowser {
            Browser.setViewport(1024, 768).andThen {
                Browser.goto(srcdocPage(parent, inner)).andThen {
                    Browser.withConfig(_.retrySchedule(Schedule.fixed(50.millis).take(40))) {
                        Browser.assertExists(Browser.Selector.testId("frame")).andThen {
                            Browser.iframe(Browser.Selector.testId("frame")).map { f =>
                                Browser.withIFrame(f) {
                                    Browser.assertExists(Browser.Selector.id("inner")).andThen {
                                        Browser.boundingRect(Browser.Selector.id("inner")).map {
                                            case Present(b) =>
                                                // Inner offset = iframe.left(100) + inner.left(20) = 120;
                                                // similarly y = iframe.top(50) + inner.top(30) = 80.
                                                assert(b.x >= 119.0, s"expected x carrying iframe offset (>=119) but got ${b.x}")
                                                assert(b.y >= 79.0, s"expected y carrying iframe offset (>=79) but got ${b.y}")
                                            case Absent =>
                                                fail("expected Present(Bounds) inside iframe, got Absent")
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

    // boundingRect inside a cross-origin iframe returns coordinates within the outer iframe's rect: the inner
    // element's top-level coordinates land inside the outer iframe box.
    "boundingRect inside a cross-origin iframe returns coords within outer iframe rect" in run {
        val outerHtml =
            """<html><body><iframe id="f" data-testid="frame" src="{iframe-src}"
                        style="position:absolute;left:100px;top:50px;width:300px;height:200px;border:0"></iframe></body></html>"""
        val innerHtml =
            """<html><body><div id="inner" style="position:absolute;left:20px;top:30px;width:40px;height:40px;background:purple"></div></body></html>"""
        withBrowserOnLocalhostIframe(outerHtml, innerHtml) {
            Browser.setViewport(1024, 768).andThen {
                Browser.withConfig(_.retrySchedule(Schedule.fixed(50.millis).take(40))) {
                    Browser.assertExists(Browser.Selector.testId("frame")).andThen {
                        // Outer iframe rect in top-level viewport coords.
                        Browser.boundingRect(Browser.Selector.testId("frame")).map {
                            case Present(outer) =>
                                Browser.iframe(Browser.Selector.testId("frame")).map { f =>
                                    Browser.withIFrame(f) {
                                        Browser.assertExists(Browser.Selector.id("inner")).andThen {
                                            Browser.boundingRect(Browser.Selector.id("inner")).map {
                                                case Present(inner) =>
                                                    assert(
                                                        inner.x >= outer.x - 1.0 && inner.x <= outer.x + outer.width + 1.0,
                                                        s"expected inner.x in outer rect [${outer.x}, ${outer.x + outer.width}] but got ${inner.x}"
                                                    )
                                                    assert(
                                                        inner.y >= outer.y - 1.0 && inner.y <= outer.y + outer.height + 1.0,
                                                        s"expected inner.y in outer rect [${outer.y}, ${outer.y + outer.height}] but got ${inner.y}"
                                                    )
                                                case Absent =>
                                                    fail("expected Present(Bounds) for inner element, got Absent")
                                            }
                                        }
                                    }
                                }
                            case Absent =>
                                fail("expected Present(Bounds) for outer iframe, got Absent")
                        }
                    }
                }
            }
        }
    }

    // computedStyles returns resolved values for a present element.
    "computedStyles returns the resolved values for a present element" in run {
        withBrowser {
            onPage("""<html><body style="margin:0;">
                <button class="btn" style="color:rgb(255, 0, 0);display:block;">Click</button>
            </body></html>""") {
                Browser.computedStyles(Browser.Selector.css(".btn"), Span("color", "display")).map { result =>
                    assert(result("color").contains("255"), s"expected color to contain 255 but got ${result("color")}")
                    assert(result("display") == "block", s"expected display=block but got ${result("display")}")
                }
            }
        }
    }

    // computedStyles aborts BrowserElementNotFoundException on missing element.
    // Schedule is tightened so the test does not wait the full default budget.
    "computedStyles aborts not-found on a missing element" in run {
        withBrowser {
            onPage("<html><body></body></html>") {
                Browser.withConfig(
                    _.retrySchedule(Schedule.fixed(30.millis).maxDuration(300.millis))
                        .assertionStabilityWindow(60.millis)
                ) {
                    Abort.run[BrowserReadException] {
                        Browser.computedStyles(Browser.Selector.css(".gone"), Span("color"))
                    }.map { result =>
                        assert(result.isFailure, s"expected Failure but got $result")
                        result match
                            case Result.Failure(ex: BrowserElementNotFoundException) => succeed
                            case other => fail(s"expected BrowserElementNotFoundException but got $other")
                    }
                }
            }
        }
    }

    // computedStyle delegates and returns the single property value.
    // The single value must contain "0, 0, 255" from the blue color.
    "computedStyle delegates and returns the single value" in run {
        withBrowser {
            onPage("""<html><body style="margin:0;">
                <button class="btn" style="color:rgb(0, 0, 255);">Click</button>
            </body></html>""") {
                Browser.computedStyle(Browser.Selector.css(".btn"), "color").map { result =>
                    assert(result.contains("0, 0, 255"), s"expected color to contain '0, 0, 255' but got $result")
                }
            }
        }
    }

    // computedStyle aborts BrowserElementNotFoundException on missing element.
    // Schedule is tightened to avoid waiting the full default budget.
    "computedStyle aborts not-found on a missing element" in run {
        withBrowser {
            onPage("<html><body></body></html>") {
                Browser.withConfig(
                    _.retrySchedule(Schedule.fixed(30.millis).maxDuration(300.millis))
                        .assertionStabilityWindow(60.millis)
                ) {
                    Abort.run[BrowserReadException] {
                        Browser.computedStyle(Browser.Selector.css(".gone"), "color")
                    }.map { result =>
                        assert(result.isFailure, s"expected Failure but got $result")
                    }
                }
            }
        }
    }

    // inViewport returns true for an on-screen element, false for a scrolled-off one.
    // #cta is near the top (y=50px), #footer is at y=2500px; viewport height 768px.
    "inViewport true for on-screen element and false for scrolled-off" in run {
        withBrowser {
            onPage("""<html><body style="height:3000px;margin:0;padding:0;">
                <style>body{height:3000px;} #cta{position:absolute;top:50px;left:0;width:50px;height:50px;background:green;} #footer{position:absolute;top:2500px;left:0;width:50px;height:50px;background:red;}</style>
                <div id="cta"></div>
                <div id="footer"></div>
            </body></html>""") {
                Browser.setViewport(1280, 768).andThen {
                    Browser.inViewport(Browser.Selector.css("#cta")).map { ctaResult =>
                        Browser.inViewport(Browser.Selector.css("#footer")).map { footerResult =>
                            assert(ctaResult == true, s"expected #cta to be in viewport but got $ctaResult")
                            assert(footerResult == false, s"expected #footer to be out of viewport but got $footerResult")
                        }
                    }
                }
            }
        }
    }

    // inViewport aborts BrowserElementNotFoundException on missing element.
    // Schedule tightened to avoid waiting the full budget.
    "inViewport aborts not-found on a missing element" in run {
        withBrowser {
            onPage("<html><body></body></html>") {
                Browser.withConfig(
                    _.retrySchedule(Schedule.fixed(30.millis).maxDuration(300.millis))
                        .assertionStabilityWindow(60.millis)
                ) {
                    Abort.run[BrowserReadException] {
                        Browser.inViewport(Browser.Selector.css(".gone"))
                    }.map { result =>
                        assert(result.isFailure, s"expected Failure but got $result")
                        result match
                            case Result.Failure(ex: BrowserElementNotFoundException) => succeed
                            case other => fail(s"expected BrowserElementNotFoundException but got $other")
                    }
                }
            }
        }
    }

    // scrollPosition reads the current offset after a JS scroll.
    // After scrolling to y=1200, scrollPosition must return ScrollPosition(0, 1200).
    "scrollPosition reads the current offset" in run {
        withBrowser {
            onPage("""<html><body style="height:3000px;margin:0;"></body></html>""") {
                Browser.eval("window.scrollTo(0, 1200)").andThen {
                    Browser.scrollPosition.map { result =>
                        assert(result.x == 0, s"expected scrollX=0 but got ${result.x}")
                        assert(result.y == 1200, s"expected scrollY=1200 but got ${result.y}")
                    }
                }
            }
        }
    }

    // scrollPosition returns the snapped (settled) window offset, not the
    // in-flight intermediate. The document itself is the snap scroller: html/body carry
    // scroll-snap-type:y mandatory with two 800px sections (matching the fixed 800px viewport height),
    // so window snap points are y=0 and y=800. Scrolling to y=600 (past the 400px midpoint) must snap
    // to the second section. Browser.scrollPosition must return exactly 800, not 0 or the intermediate 600.
    "scrollPosition returns the snapped window offset after scroll-snap settles" in run {
        withBrowser {
            Browser.setViewport(1280, 800).andThen {
                onPage("""<html style="margin:0;padding:0;scroll-snap-type:y mandatory;"><body style="margin:0;padding:0;">
                    <section style="scroll-snap-align:start;height:800px;background:lightblue;"></section>
                    <section style="scroll-snap-align:start;height:800px;background:lightcoral;"></section>
                </body></html>""") {
                    Browser.withConfig(
                        _.retrySchedule(Schedule.fixed(50.millis).maxDuration(4.seconds))
                            .assertionStabilityWindow(150.millis)
                    ) {
                        Browser.eval("window.scrollTo({top:600,behavior:'instant'})").andThen {
                            Browser.scrollPosition.map { result =>
                                assert(
                                    result.y == 800,
                                    s"expected scrollY=800 (snapped to second section) but got ${result.y}"
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // waitForStable returns after the DOM quiesces.
    // A setInterval mutates the DOM 10 times over ~200ms then stops.
    // waitForStable(2.seconds) must return within the timeout.
    "waitForStable returns after the DOM quiesces" in run {
        withBrowser {
            onPage("""<html><body>
                <div id="target"></div>
                <script>
                    var count = 0;
                    var interval = setInterval(() => {
                        var el = document.createElement('span');
                        document.getElementById('target').appendChild(el);
                        count++;
                        if (count >= 10) clearInterval(interval);
                    }, 20);
                </script>
            </body></html>""") {
                timed(Browser.waitForStable(2.seconds)).map { case (elapsed, _) =>
                    assert(elapsed < 2.seconds, s"waitForStable took too long: $elapsed")
                }
            }
        }
    }

    // waitForStable aborts BrowserAssertionTimedOutException on a never-quiescing page.
    // A perpetual setInterval mutates forever.
    // waitForStable(300.millis) must abort BrowserAssertionTimedOutException.
    "waitForStable aborts on a never-quiescing page" in run {
        withBrowser {
            onPage("""<html><body>
                <div id="target"></div>
                <script>
                    setInterval(() => {
                        var el = document.createElement('span');
                        document.getElementById('target').appendChild(el);
                    }, 10);
                </script>
            </body></html>""") {
                Abort.run[BrowserReadException] {
                    Browser.waitForStable(300.millis)
                }.map { result =>
                    assert(result.isFailure, s"expected Failure but got $result")
                    result match
                        case Result.Failure(ex: BrowserAssertionTimedOutException) => succeed
                        case other => fail(s"expected BrowserAssertionTimedOutException but got $other")
                }
            }
        }
    }

end BrowserVerifyReadTest
