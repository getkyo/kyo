package kyo

import kyo.Browser.*
import kyo.BrowserElementNotActionableException.Reason
import kyo.internal.Actionability

class BrowserActionabilityTest extends BrowserTest:

    override def timeout = 90.seconds

    // ── Actionability gate; click/fill/hover/press verify the target is attached, visible, stable, hittable, enabled ──

    // Tight retry config used across most tests to avoid long waits when a failure is expected.
    private def fastConfig[A, S](v: A < S)(using Frame): A < S =
        Browser.withConfig(_.retrySchedule(Schedule.fixed(20.millis).take(2)))(v)

    "click on a visible+enabled+stable+hittable button succeeds" in run {
        withBrowser {
            onPage("""<body>
            <button id="target" style="width:80px;height:30px">Go</button>
        </body>""") {
                Browser.click(Browser.Selector.id("target")).map(_ => succeed)
            }
        }
    }

    "click on display:none fails Browser.Reason.Hidden" in run {
        withBrowser {
            onPage("""<body>
            <button id="t" style="display:none">Go</button>
        </body>""") {
                expectNotActionable(
                    Browser.click(Browser.Selector.id("t")),
                    Reason.NotVisible(Reason.NotVisibleCause.DisplayNone)
                )
            }
        }
    }

    "click on visibility:hidden fails Browser.Reason.Hidden" in run {
        withBrowser {
            onPage("""<body>
            <button id="t" style="visibility:hidden">Go</button>
        </body>""") {
                expectNotActionable(
                    Browser.click(Browser.Selector.id("t")),
                    Reason.NotVisible(Reason.NotVisibleCause.VisibilityHidden)
                )
            }
        }
    }

    "click on zero-size element fails Browser.Reason.Hidden" in run {
        withBrowser {
            onPage("""<body>
            <div id="t" style="width:0;height:0;overflow:hidden">nothing</div>
        </body>""") {
                expectNotActionable(Browser.click(Browser.Selector.id("t")), Reason.ZeroSizedElement(0, 0))
            }
        }
    }

    "click on disabled <button> fails Browser.Reason.Disabled" in run {
        withBrowser {
            onPage("""<body>
            <button id="t" disabled style="width:80px;height:30px">Go</button>
        </body>""") {
                expectNotActionable(
                    Browser.click(Browser.Selector.id("t")),
                    Reason.Disabled(Reason.DisabledKind.Attribute)
                )
            }
        }
    }

    "click on a covered button fails Browser.Reason.Covered" in run {
        withBrowser {
            onPage("""<body style="margin:0">
            <button id="t" style="position:absolute;left:10px;top:10px;width:80px;height:30px">Go</button>
            <div id="overlay" style="position:absolute;left:0;top:0;width:300px;height:200px;background:rgba(0,0,0,0.2)"></div>
        </body>""") {
                expectNotActionablePF(Browser.click(Browser.Selector.id("t"))) {
                    case Reason.OutsideHitTarget(actualHit) =>
                        assert(
                            actualHit.contains("div") || actualHit.contains("overlay"),
                            s"expected actualHit to mention 'div' or 'overlay' but got '$actualHit'"
                        ): Unit
                }
            }
        }
    }

    "click on mid-animation element eventually succeeds once stable" in run {
        withBrowser {
            onPage("""<body style="margin:0">
            <button id="t" style="position:absolute;left:0;top:10px;width:80px;height:30px">Go</button>
            <script>
                // Instrument a self-limited "animation": the element walks left in 5px steps via
                // requestAnimationFrame until it passes 50px, then stops. The pre-stop checks see
                // a moving rect (Reason.Unstable); once the loop ends, stability passes.
                (function() {
                    var el = document.getElementById('t');
                    var x = 0;
                    function step() {
                        x += 5;
                        el.style.left = x + 'px';
                        if (x < 50) requestAnimationFrame(step);
                    }
                    requestAnimationFrame(step);
                })();
            </script>
        </body>""") {
                Browser.withConfig(_.retrySchedule(Schedule.fixed(50.millis).maxDuration(5.seconds))) {
                    Browser.click(Browser.Selector.id("t"))
                }.map(_ => succeed)
            }
        }
    }

    "click on a detached element fails Reason.NotAttached" in run {
        withBrowser {
            onPage("""<body>
            <button id="t" style="width:80px;height:30px">Go</button>
            <script>
                // Remove the element before the click runs.
                document.getElementById('t').remove();
            </script>
        </body>""") {
                expectNotActionable(Browser.click(Browser.Selector.id("t")), Reason.NotAttached)
            }
        }
    }

    "exactly one actionability JS round-trip per check" in run {
        withBrowser {
            onPage("""<body>
            <button id="t" style="width:80px;height:30px">Go</button>
        </body>""") {
                // Reset the in-page counter.
                Browser.eval("window.__kyoActionabilityCount = 0").andThen {
                    Actionability.check(Browser.Selector.id("t"), requireFillable = false, requireEnabled = true).map { r =>
                        assert(r.isSuccess, s"expected Success but got $r")
                        Browser.eval("window.__kyoActionabilityCount").map { n =>
                            assert(n == "1", s"expected exactly one actionability JS round-trip but got $n")
                        }
                    }
                }
            }
        }
    }

    "Browser.Reason.NotVisible error message cites 'not visible' and the selector description" in run {
        withBrowser {
            onPage("""<body>
            <button id="my-hidden-btn" style="display:none">Go</button>
        </body>""") {
                fastConfig {
                    Abort.run[BrowserElementException] {
                        Browser.click(Browser.Selector.id("my-hidden-btn"))
                    }
                }.map {
                    case Result.Failure(ex: BrowserElementNotActionableException) =>
                        val msg = ex.getMessage
                        // Assert the human-readable phrase AND the typed reason (matches on the enum case, not the rendered text).
                        assert(
                            msg.contains("not visible") || msg.contains("display:none"),
                            s"expected 'not visible' or 'display:none' in message but got: $msg"
                        )
                        assert(
                            ex.reason == Reason.NotVisible(Reason.NotVisibleCause.DisplayNone),
                            s"expected NotVisible(DisplayNone) but got ${ex.reason}"
                        )
                        assert(msg.contains("my-hidden-btn"), s"expected selector description in message but got: $msg")
                    case other => fail(s"expected NotActionable failure but got $other")
                }
            }
        }
    }

    "Browser.Reason.Disabled matches native <input disabled>" in run {
        withBrowser {
            onPage("""<body>
            <input id="t" type="text" disabled style="width:120px;height:24px">
        </body>""") {
                expectNotActionable(
                    Browser.click(Browser.Selector.id("t")),
                    Reason.Disabled(Reason.DisabledKind.Attribute)
                )
            }
        }
    }

    "Browser.Reason.Disabled matches <button aria-disabled='true'>" in run {
        withBrowser {
            onPage("""<body>
            <button id="t" aria-disabled="true" style="width:80px;height:30px">Go</button>
        </body>""") {
                expectNotActionable(
                    Browser.click(Browser.Selector.id("t")),
                    Reason.Disabled(Reason.DisabledKind.AriaDisabled)
                )
            }
        }
    }

    "NotAttached short-circuits before Hidden" in run {
        // The node is present in HTML, but the script removes it before actionability runs.
        // Even though it had display:none (Hidden), the Resolver will not find it; producing NotAttached.
        withBrowser {
            onPage("""<body>
            <button id="t" style="display:none">Go</button>
            <script>
                document.getElementById('t').remove();
            </script>
        </body>""") {
                expectNotActionable(Browser.click(Browser.Selector.id("t")), Reason.NotAttached)
            }
        }
    }

    "retry succeeds on transient Unstable once motion stops" in run {
        withBrowser {
            onPage("""<body style="margin:0">
            <button id="t" style="position:absolute;left:0;top:10px;width:80px;height:30px">Go</button>
            <script>
                // Move the element to a new position ~200ms later; initial checks may see Unstable, later checks succeed.
                setTimeout(function() {
                    document.getElementById('t').style.left = '120px';
                }, 200);
            </script>
        </body>""") {
                Browser.withConfig(_.retrySchedule(Schedule.fixed(50.millis).maxDuration(3.seconds))) {
                    Browser.click(Browser.Selector.id("t"))
                }.map(_ => succeed)
            }
        }
    }

    "fill on a <div> target fails Browser.Reason.NotFillable" in run {
        withBrowser {
            onPage("""<body>
            <div id="t" style="width:100px;height:20px">not an input</div>
        </body>""") {
                expectNotActionable(Browser.fill(Browser.Selector.id("t"), "hi"), Reason.NotFillable("div"))
            }
        }
    }

    "hover on hidden element fails NotActionable" in run {
        withBrowser {
            onPage("""<body>
            <div id="t" style="display:none">hi</div>
        </body>""") {
                expectNotActionable(
                    Browser.hover(Browser.Selector.id("t")),
                    Reason.NotVisible(Reason.NotVisibleCause.DisplayNone)
                )
            }
        }
    }

    "dragAndDrop fails on target-side Disabled" in run {
        withBrowser {
            onPage("""<body style="margin:0">
            <div id="src" style="position:absolute;left:0;top:0;width:60px;height:40px;background:#cce">S</div>
            <button id="tgt" disabled style="position:absolute;left:200px;top:0;width:80px;height:30px">T</button>
        </body>""") {
                fastConfig {
                    Abort.run[BrowserElementException] {
                        Browser.dragAndDrop(Browser.Selector.id("src"), Browser.Selector.id("tgt"))
                    }
                }.map {
                    case Result.Failure(ex: BrowserElementNotActionableException) =>
                        ex.reason match
                            case Reason.Disabled(Reason.DisabledKind.Attribute) => succeed
                            case other                                          => fail(s"expected Disabled(Attribute) but got $other")
                        assert(ex.selector.contains("tgt"), s"expected target selector in message but got: ${ex.selector}")
                    case other => fail(s"expected NotActionable(Disabled) on target but got $other")
                }
            }
        }
    }

    "Actionability.check returns Success on visible and Failure(Reason) on hidden" in run {
        withBrowser {
            onPage("""<body>
            <button id="ok" style="width:80px;height:30px">Go</button>
            <button id="no" style="display:none">X</button>
        </body>""") {
                Actionability.check(Browser.Selector.id("ok"), requireFillable = false, requireEnabled = true).map { okR =>
                    Actionability.check(Browser.Selector.id("no"), requireFillable = false, requireEnabled = true).map { noR =>
                        assert(okR.isSuccess, s"expected Success(ok) but got $okR")
                        noR match
                            case Result.Failure(reason) =>
                                assert(
                                    reason == Reason.NotVisible(Reason.NotVisibleCause.DisplayNone),
                                    s"expected NotVisible(DisplayNone) but got $reason"
                                )
                            case other => fail(s"expected Failure(Hidden) but got $other")
                        end match
                    }
                }
            }
        }
    }

    "ActionableRef carries backend node id and non-zero rect" in run {
        withBrowser {
            onPage("""<body>
            <button id="t" style="width:88px;height:32px">Go</button>
        </body>""") {
                Actionability.check(Browser.Selector.id("t"), requireFillable = false, requireEnabled = true).map {
                    case Result.Success(ref) =>
                        discard(ref.nodeRef) // nodeRef is present and typed
                        assert(ref.width > 0, s"expected positive width but got ${ref.width}")
                        assert(ref.height > 0, s"expected positive height but got ${ref.height}")
                        assert(ref.x > 0 && ref.y > 0, s"expected positive center (x,y) but got (${ref.x},${ref.y})")
                    case other => fail(s"expected Success(ActionableRef) but got $other")
                }
            }
        }
    }

    // Actionability surfaces failures via Abort[BrowserException], NOT via thrown exceptions. Drive a non-actionable scenario
    // (display:none button) through the public click API and assert that the failure surfaces as a typed
    // `Result.Failure(BrowserElementNotActionableException)` and never as `Result.Panic` (which would indicate a thrown Throwable
    // escaped the typed channel).
    "Actionability surfaces failures via Abort[BrowserException], never as a thrown exception" in run {
        withBrowser {
            onPage("""<body>
            <button id="t" style="display:none">Go</button>
        </body>""") {
                fastConfig {
                    Abort.run[BrowserException] {
                        Browser.click(Browser.Selector.id("t"))
                    }
                }.map {
                    case Result.Success(_) =>
                        fail("expected non-actionable click to fail, but it succeeded")
                    case Result.Failure(ex: BrowserElementNotActionableException) =>
                        assert(
                            ex.reason == Reason.NotVisible(Reason.NotVisibleCause.DisplayNone),
                            s"expected NotVisible(DisplayNone) but got ${ex.reason}"
                        )
                    case Result.Failure(other) =>
                        fail(s"expected BrowserElementNotActionableException(Hidden) but got Failure($other)")
                    case Result.Panic(t) =>
                        fail(s"actionability failure leaked as a thrown exception (Result.Panic): $t")
                }
            }
        }
    }

    // ── Scroll-into-view + two-RAF stability ───────────────────────────

    // Retry schedule used for "should eventually succeed" tests - 5s budget so animated-element tests finish in time.
    private def patientConfig[A, S](v: A < S)(using Frame): A < S =
        Browser.withConfig(_.retrySchedule(Schedule.fixed(50.millis).maxDuration(5.seconds)))(v)

    "click on a below-the-fold element auto-scrolls into view" in run {
        withBrowser {
            onPage("""<body style="margin:0">
            <div style="height:2000px;background:#eef"></div>
            <button id="t" style="width:120px;height:30px"
                    onclick="window.__kyoClicked=true">Go</button>
        </body>""") {
                Browser.eval("String(window.scrollY)").map { preY =>
                    assert(preY.toDouble == 0.0, s"expected scrollY==0 before click but got $preY")
                    Browser.click(Browser.Selector.id("t")).andThen {
                        Browser.eval("String(window.scrollY)").map { postY =>
                            assert(postY.toDouble > 0.0, s"expected scrollY>0 after auto-scroll click but got $postY")
                            Browser.eval("String(!!window.__kyoClicked)").map { clicked =>
                                assert(clicked == "true", s"expected click handler to fire but got $clicked")
                            }
                        }
                    }
                }
            }
        }
    }

    "click on an element inside a scrolled container auto-scrolls the container" in run {
        withBrowser {
            onPage("""<body style="margin:0">
            <div id="box" style="overflow:auto;height:100px;width:200px;border:1px solid #000">
                <div style="height:600px">filler</div>
                <button id="t" style="width:80px;height:30px"
                        onclick="window.__kyoClicked=true">Go</button>
                <div style="height:200px">trailing filler</div>
            </div>
        </body>""") {
                Browser.eval("String(document.getElementById('box').scrollTop)").map { preTop =>
                    assert(preTop.toDouble == 0.0, s"expected container scrollTop==0 before click but got $preTop")
                    Browser.click(Browser.Selector.id("t")).andThen {
                        Browser.eval("String(document.getElementById('box').scrollTop)").map { postTop =>
                            assert(
                                postTop.toDouble > 0.0,
                                s"expected container scrollTop>0 after auto-scroll click but got $postTop"
                            )
                            Browser.eval("String(!!window.__kyoClicked)").map { clicked =>
                                assert(clicked == "true", s"expected click handler to fire but got $clicked")
                            }
                        }
                    }
                }
            }
        }
    }

    "click on an element above the current scroll position scrolls up" in run {
        withBrowser {
            onPage("""<body style="margin:0">
            <button id="t" style="width:120px;height:30px"
                    onclick="window.__kyoClicked=true">Go</button>
            <div style="height:3000px;background:#eef"></div>
        </body>""") {
                // Scroll to the bottom first so the button is above the viewport.
                Browser.eval("window.scrollTo(0, document.body.scrollHeight); String(window.scrollY)").map { preY =>
                    assert(preY.toDouble > 100.0, s"expected scrollY to be well below target before click but got $preY")
                    Browser.click(Browser.Selector.id("t")).andThen {
                        Browser.eval("String(window.scrollY)").map { postY =>
                            assert(
                                postY.toDouble < preY.toDouble,
                                s"expected scrollY to decrease after auto-scroll-up click; preY=$preY postY=$postY"
                            )
                            Browser.eval("String(!!window.__kyoClicked)").map { clicked =>
                                assert(clicked == "true", s"expected click handler to fire but got $clicked")
                            }
                        }
                    }
                }
            }
        }
    }

    "click on an already-visible element does not scroll" in run {
        withBrowser {
            onPage("""<body style="margin:0">
            <button id="t" style="width:120px;height:30px;margin:20px"
                    onclick="window.__kyoClicked=true">Go</button>
        </body>""") {
                Browser.eval("String(window.scrollY)").map { preY =>
                    assert(preY.toDouble == 0.0, s"expected scrollY==0 before click but got $preY")
                    Browser.click(Browser.Selector.id("t")).andThen {
                        Browser.eval("String(window.scrollY)").map { postY =>
                            assert(
                                postY.toDouble == preY.toDouble,
                                s"expected scrollY unchanged for already-visible element; pre=$preY post=$postY"
                            )
                        }
                    }
                }
            }
        }
    }

    "two-RAF sample reports Unstable during a 2px/frame drift and succeeds after motion ends" in run {
        withBrowser {
            onPage("""<body style="margin:0">
            <div id="d" style="position:absolute;left:0;top:40px;transform:translateX(0px)">
                <button id="t" style="width:120px;height:30px"
                        onclick="window.__kyoClicked=true">Go</button>
            </div>
            <script>
                (function() {
                    var d = document.getElementById('d');
                    var x = 0;
                    var handle = setInterval(function() {
                        x += 2;
                        d.style.transform = 'translateX(' + x + 'px)';
                    }, 16);
                    setTimeout(function() { clearInterval(handle); window.__kyoAnimationStopped = true; }, 500);
                })();
            </script>
        </body>""") {
                // Reset the in-page invocation counter that `Actionability` increments on every check. Pinning the count is the
                // deterministic instrument that lets us verify the retry loop actually ran the gate multiple times; rather than
                // re-deriving "I waited long enough" from timing.
                Browser.eval("window.__kyoActionabilityCount = 0").andThen {
                    // First check: while the animation is still running, expect Unstable.
                    Actionability.check(Browser.Selector.id("t"), requireFillable = false, requireEnabled = true).map { early =>
                        early match
                            case Result.Failure(reason) =>
                                assert(
                                    reason == Reason.Unstable,
                                    s"expected Reason.Unstable during the 2px/frame drift but got $reason"
                                )
                            case other =>
                                // If the first sample happens to race after the stop, accept Success; the contract
                                // is that the eventual retry-click below still lands successfully.
                                assert(other.isSuccess, s"expected Unstable or Success during drift but got $other")
                        end match
                        // Pin: exactly one Actionability.check has run so far.
                        Browser.eval("String(window.__kyoActionabilityCount)").map { firstCount =>
                            assert(
                                firstCount == "1",
                                s"expected exactly one actionability JS round-trip after the early sample but got $firstCount"
                            )
                            // Retry-driven click: eventually the animation stops (clearInterval at 500ms) and the click succeeds.
                            patientConfig { Browser.click(Browser.Selector.id("t")) }.andThen {
                                Browser.eval("String(!!window.__kyoClicked)").map { clicked =>
                                    assert(clicked == "true", s"expected click handler to fire after motion ends but got $clicked")
                                    // Pin: at least one additional Actionability.check ran during the retry loop
                                    // (the retry-driven click invokes the gate at least once before clicking).
                                    // This is the deterministic counterpart to the timing-tolerance above:
                                    // even if the early sample raced past clearInterval, the retry loop must
                                    // have executed N >= 2 checks total to drive the click successfully.
                                    Browser.eval("String(window.__kyoActionabilityCount)").map { totalCount =>
                                        assert(
                                            totalCount.toInt >= 2,
                                            s"expected >=2 total Actionability.check invocations after retry-driven click but got $totalCount"
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    "stability passes when the rect is identical across two RAFs" in run {
        withBrowser {
            onPage("""<body style="margin:0">
            <button id="t" style="position:absolute;left:10px;top:20px;width:120px;height:30px">Go</button>
        </body>""") {
                Actionability.check(Browser.Selector.id("t"), requireFillable = false, requireEnabled = true).map { r =>
                    assert(r.isSuccess, s"expected Success for a static element but got $r")
                }
            }
        }
    }

    "below-viewport animating element is still flagged Unstable after scroll-into-view" in run {
        withBrowser {
            onPage("""<body style="margin:0">
            <div style="height:2000px;background:#eef"></div>
            <div id="d" style="transform:translateX(0px)">
                <button id="t" style="width:120px;height:30px"
                        onclick="window.__kyoClicked=true">Go</button>
            </div>
            <script>
                (function() {
                    var d = document.getElementById('d');
                    var x = 0;
                    var handle = setInterval(function() {
                        x += 3;
                        d.style.transform = 'translateX(' + x + 'px)';
                    }, 16);
                    setTimeout(function() { clearInterval(handle); }, 500);
                })();
            </script>
        </body>""") {
                // First check: scroll-into-view brings the element into view, but the 2-RAF sample catches the ongoing motion.
                Actionability.check(Browser.Selector.id("t"), requireFillable = false, requireEnabled = true).map { early =>
                    early match
                        case Result.Failure(reason) =>
                            assert(
                                reason == Reason.Unstable,
                                s"expected Reason.Unstable during drift-after-scroll but got $reason"
                            )
                        case other =>
                            assert(other.isSuccess, s"expected Unstable or Success during drift but got $other")
                    end match
                    // scrollY should already have moved; scroll-into-view ran before the stability sample.
                    Browser.eval("String(window.scrollY)").map { scrolledY =>
                        assert(
                            scrolledY.toDouble > 0.0,
                            s"expected scroll-into-view to run before stability sample; scrollY=$scrolledY"
                        )
                        // Eventually the animation stops and the click lands.
                        patientConfig { Browser.click(Browser.Selector.id("t")) }.andThen {
                            Browser.eval("String(!!window.__kyoClicked)").map { clicked =>
                                assert(
                                    clicked == "true",
                                    s"expected click handler to fire after animation ends but got $clicked"
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    "sub-pixel drift passes stability" in run {
        withBrowser {
            onPage("""<body style="margin:0">
            <div id="d" style="position:absolute;left:10px;top:20px;transform:translateX(0px)">
                <button id="t" style="width:120px;height:30px"
                        onclick="window.__kyoClicked=true">Go</button>
            </div>
            <script>
                (function() {
                    var d = document.getElementById('d');
                    var x = 0;
                    // Increment by 0.3px per frame; each RAF-to-RAF delta is 0.3px, well inside the 1px tolerance.
                    setInterval(function() {
                        x += 0.3;
                        d.style.transform = 'translateX(' + x + 'px)';
                    }, 16);
                })();
            </script>
        </body>""") {
                Actionability.check(Browser.Selector.id("t"), requireFillable = false, requireEnabled = true).map { r =>
                    assert(r.isSuccess, s"expected Success for sub-pixel drift (<1px/RAF) but got $r")
                }
            }
        }
    }

    "hover auto-scrolls a below-the-fold element into view" in run {
        withBrowser {
            onPage("""<body style="margin:0">
            <div style="height:2000px;background:#eef"></div>
            <div id="t" style="width:120px;height:30px"
                 onmouseover="window.__kyoHovered=true">Hover me</div>
        </body>""") {
                Browser.eval("String(window.scrollY)").map { preY =>
                    assert(preY.toDouble == 0.0, s"expected scrollY==0 before hover but got $preY")
                    Browser.hover(Browser.Selector.id("t")).andThen {
                        Browser.eval("String(window.scrollY)").map { postY =>
                            assert(postY.toDouble > 0.0, s"expected scrollY>0 after hover auto-scroll but got $postY")
                        }
                    }
                }
            }
        }
    }

    // screenshotElement does not fold in the actionability gate; the caller invokes the gate
    // explicitly (same mechanism click/hover use) and then takes the element screenshot; asserting
    // both the pre-screenshot scrollY moved and the image bytes are non-empty.
    "element screenshot after auto-scroll captures non-empty bytes and has moved scrollY" in run {
        withBrowser {
            onPage("""<body style="margin:0">
            <div style="height:2000px;background:#eef"></div>
            <div id="t" style="width:120px;height:50px;background:#f0a">target</div>
        </body>""") {
                Browser.eval("String(window.scrollY)").map { preY =>
                    assert(preY.toDouble == 0.0, s"expected scrollY==0 before screenshot but got $preY")
                    Actionability.check(Browser.Selector.id("t"), requireFillable = false, requireEnabled = true).map { r =>
                        assert(r.isSuccess, s"expected Actionability.check to succeed but got $r")
                        Browser.eval("String(window.scrollY)").map { postY =>
                            assert(
                                postY.toDouble > 0.0,
                                s"expected scroll-into-view to move scrollY before screenshot but got $postY"
                            )
                            Browser.screenshotElement(Browser.Selector.id("t")).map { img =>
                                assert(img.data.size > 0, s"expected non-empty screenshot bytes but got ${img.data.size}")
                            }
                        }
                    }
                }
            }
        }
    }

    // ── error enrichment ──
    //
    // `BrowserElementNotActionableException` renders a human-readable phrase per `Actionability.Reason` (not the bare enum name).
    // The tests exercise each Reason via a live-browser fixture, then inspect the exception's `getMessage` for both the phrase and the
    // selector description; so a regression that drops the Reason-to-phrase mapping surfaces immediately.
    //
    // `Browser.eval` errors carry the CDP `exceptionDetails.stackTrace` walked as `"at <fn> (<url>:<line>:<col>)"` lines; so
    // operators can jump straight to the failing call site without re-running under a debugger.

    "NotActionable(NotVisible) message cites 'not visible' and selector description" in run {
        withBrowser {
            onPage("""<body>
            <button id="t" style="display:none">Go</button>
        </body>""") {
                fastConfig {
                    Abort.run[BrowserElementException] {
                        Browser.click(Browser.Selector.id("t"))
                    }
                }.map {
                    case Result.Failure(ex: BrowserElementNotActionableException) =>
                        val msg = ex.getMessage
                        assert(
                            msg.contains("not visible") || msg.contains("display:none"),
                            s"expected 'not visible' or 'display:none' in message but got: $msg"
                        )
                        // Selector description renders as `id("t")`; assert both the selector value and the `id` wrapper surface.
                        assert(msg.contains("""id("t")"""), s"""expected selector description 'id("t")' in message but got: $msg""")
                    case other => fail(s"expected NotActionable(NotVisible) but got $other")
                }
            }
        }
    }

    "NotActionable(Covered) message cites 'covered'" in run {
        withBrowser {
            onPage("""<body style="margin:0">
            <button id="t" style="position:absolute;left:10px;top:10px;width:80px;height:30px">Go</button>
            <div style="position:absolute;left:0;top:0;width:300px;height:200px;background:rgba(0,0,0,0.2)"></div>
        </body>""") {
                fastConfig {
                    Abort.run[BrowserElementException] {
                        Browser.click(Browser.Selector.id("t"))
                    }
                }.map {
                    case Result.Failure(ex: BrowserElementNotActionableException) =>
                        val msg = ex.getMessage
                        assert(msg.contains("covered"), s"expected 'covered' in message but got: $msg")
                        assert(msg.contains("""id("t")"""), s"expected selector description in message but got: $msg")
                    case other => fail(s"expected NotActionable(Covered) but got $other")
                }
            }
        }
    }

    "NotActionable(Disabled) message distinguishes native disabled from aria-disabled" in run {
        // Two distinct fixtures both resolve to `Browser.Reason.Disabled`; the phrase is identical but the selector description differs,
        // proving the selector segment of the message is driven by the caller's selector rather than the disabled mechanism.
        val nativeFx = page("""<body><button id="native" disabled style="width:80px;height:30px">Go</button></body>""")
        val ariaFx   = page("""<body><button id="aria" aria-disabled="true" style="width:80px;height:30px">Go</button></body>""")
        withBrowser {
            Browser.goto(nativeFx).andThen {
                fastConfig {
                    Abort.run[BrowserElementException] {
                        Browser.click(Browser.Selector.id("native"))
                    }
                }.map {
                    case Result.Failure(ex: BrowserElementNotActionableException) =>
                        val msg = ex.getMessage
                        assert(msg.contains("element is disabled"), s"expected 'element is disabled' in message but got: $msg")
                        assert(
                            msg.contains("""id("native")"""),
                            s"""expected selector description 'id("native")' in message but got: $msg"""
                        )
                    case other => fail(s"expected NotActionable(Disabled) for native case but got $other")
                }.andThen {
                    Browser.goto(ariaFx).andThen {
                        fastConfig {
                            Abort.run[BrowserElementException] {
                                Browser.click(Browser.Selector.id("aria"))
                            }
                        }.map {
                            case Result.Failure(ex: BrowserElementNotActionableException) =>
                                val msg = ex.getMessage
                                assert(msg.contains("element is disabled"), s"expected 'element is disabled' in aria message but got: $msg")
                                assert(
                                    msg.contains("""id("aria")"""),
                                    s"""expected selector description 'id("aria")' in message but got: $msg"""
                                )
                            case other => fail(s"expected NotActionable(Disabled) for aria case but got $other")
                        }
                    }
                }
            }
        }
    }

    "NotActionable(NotFillable) raised when fill targets a non-INPUT" in run {
        withBrowser {
            onPage("<div id='d'>not an input</div>") {
                fastConfig {
                    Abort.run[BrowserElementException] {
                        Browser.fill(Browser.Selector.id("d"), "x")
                    }
                }.map {
                    case Result.Failure(ex: BrowserElementNotActionableException) =>
                        val msg = ex.getMessage
                        assert(msg.contains("not a fillable input"), s"expected 'not a fillable input' in message but got: $msg")
                        assert(msg.contains("""id("d")"""), s"""expected selector description 'id("d")' in message but got: $msg""")
                    case other => fail(s"expected NotActionable(NotFillable) but got $other")
                }
            }
        }
    }

    "Frame propagates through actionability failures (call site appears in the Kyo frame chain)" in run {
        // A named helper method builds the hidden-click computation; if `Frame` is threaded through correctly, the exception's `frame`
        // records this test file (since `(using Frame)` captures the caller's position at the `click` invocation below).
        def triggerHiddenClick(using
            Frame
        ): Unit < (Browser & Abort[BrowserReadException]) = fastConfig { Browser.click(Browser.Selector.id("t")) }

        withBrowser {
            onPage("""<body><button id="t" style="display:none">Go</button></body>""") {
                Abort.run[BrowserElementException] {
                    triggerHiddenClick
                }.map {
                    case Result.Failure(ex: BrowserElementNotActionableException) =>
                        // `ex.frame` is the Frame captured when the exception was constructed. Its rendered form references the
                        // source file that produced the frame; the frame's file path (or the dev-mode toString of the whole
                        // exception) must therefore contain this test file's name.
                        val frameRender = ex.frame.position.fileName
                        assert(
                            frameRender.contains("BrowserActionabilityTest"),
                            s"expected frame to reference BrowserActionabilityTest but got: $frameRender"
                        )
                    case other => fail(s"expected NotActionable(Hidden) from helper but got $other")
                }
            }
        }
    }

    // ─── Additional integration tests: actionability reason cases ───────────────

    // NotVisible(OpacityZero): opacity:0 element surfaces the OpacityZero cause.
    "click on opacity:0 element fails NotVisible(OpacityZero)" in run {
        withBrowser {
            onPage("""<body>
            <button id="t" style="width:80px;height:30px;opacity:0">Go</button>
        </body>""") {
                expectNotActionable(
                    Browser.click(Browser.Selector.id("t")),
                    Reason.NotVisible(Reason.NotVisibleCause.OpacityZero)
                )
            }
        }
    }

    // Disabled(FieldsetDisabled): element inside <fieldset disabled> surfaces FieldsetDisabled.
    "click on element inside <fieldset disabled> fails Disabled(FieldsetDisabled)" in run {
        withBrowser {
            onPage("""<body>
            <fieldset disabled>
                <button id="t" style="width:80px;height:30px">Go</button>
            </fieldset>
        </body>""") {
                expectNotActionable(
                    Browser.click(Browser.Selector.id("t")),
                    Reason.Disabled(Reason.DisabledKind.FieldsetDisabled)
                )
            }
        }
    }

    // OutsideHitTarget.actualHit is non-empty and contains the covering element info.
    "OutsideHitTarget.actualHit carries non-empty covering element info" in run {
        withBrowser {
            onPage("""<body style="margin:0">
            <button id="t" style="position:absolute;left:10px;top:10px;width:80px;height:30px">Go</button>
            <div id="overlay" style="position:absolute;left:0;top:0;width:300px;height:200px;background:rgba(0,0,0,0.2)"></div>
        </body>""") {
                fastConfig {
                    Abort.run[BrowserElementException] {
                        Browser.click(Browser.Selector.id("t"))
                    }
                }.map {
                    case Result.Failure(ex: BrowserElementNotActionableException) =>
                        ex.reason match
                            case Reason.OutsideHitTarget(actualHit) =>
                                assert(actualHit.nonEmpty, s"actualHit should be non-empty but got: '$actualHit'")
                                assert(actualHit.contains("div"), s"actualHit should contain 'div' but got: '$actualHit'")
                            case other => fail(s"expected OutsideHitTarget but got $other")
                    case other => fail(s"expected NotActionable failure but got $other")
                }
            }
        }
    }

    // NotInViewport carries both the element rect and the viewport rect.
    // A position:fixed element with top:-1000px is permanently above the viewport and
    // scrollIntoViewIfNeeded cannot bring it back, so the viewport-bounds check fires.
    "Reason.NotInViewport carries the element rect and viewport rect" in run {
        withBrowser {
            onPage("""<!doctype html><html><body>
                <button id="b" style="position:fixed; top:-1000px; left:0; width:80px; height:30px">target</button>
            </body></html>""") {
                fastConfig {
                    Abort.run[BrowserElementException] {
                        Browser.click(Browser.Selector.id("b"))
                    }
                }.map {
                    case Result.Failure(ex: BrowserElementNotActionableException) =>
                        ex.reason match
                            case Reason.NotInViewport(rect, vp) =>
                                assert(vp.width > 0, s"expected positive viewport width but got ${vp.width}")
                                assert(vp.height > 0, s"expected positive viewport height but got ${vp.height}")
                                assert(rect.y < 0, s"expected element y to be negative (above viewport) but got ${rect.y}")
                            case other =>
                                fail(s"expected NotInViewport but got: $other")
                    case Result.Success(_) =>
                        fail("expected NotInViewport failure but click succeeded")
                    case Result.Panic(t) =>
                        fail(s"unexpected panic: $t")
                }
            }
        }
    }

    // NotFillable carries the correct tagName from the actionability probe.
    "NotFillable.tagName carries the correct lowercase tag from the actionability probe" in run {
        withBrowser {
            onPage("""<body>
            <span id="t" style="width:100px;height:20px">not an input</span>
        </body>""") {
                fastConfig {
                    Abort.run[BrowserElementException] {
                        Browser.fill(Browser.Selector.id("t"), "hi")
                    }
                }.map {
                    case Result.Failure(ex: BrowserElementNotActionableException) =>
                        ex.reason match
                            case Reason.NotFillable(tagName) =>
                                assert(tagName == "span", s"expected tagName=span but got '$tagName'")
                            case other => fail(s"expected NotFillable but got $other")
                    case other => fail(s"expected NotActionable(NotFillable) but got $other")
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // NotVisible failure message includes the element's bounding-rect and hidden cause.
    // Without the rect, a user cannot tell which matched element is the culprit when a
    // selector returns multiple candidates, or distinguish "rect 0x0" from "rect 404x32 but
    // parent is display:none". The embedded rect makes the failure immediately diagnosable.
    // -------------------------------------------------------------------------

    "NotVisible failure message embeds the element's bounding rect" in run {
        withBrowser {
            // A button explicitly sized but hidden via display:none; non-zero "would-be" rect.
            onPage("""<button id='t' style='display:none;width:120px;height:30px'>Click</button>""") {
                tight {
                    Abort.run[BrowserElementException] {
                        Browser.click(Browser.Selector.id("t"))
                    }.map {
                        case Result.Failure(ex: BrowserElementNotActionableException) =>
                            val msg = ex.getMessage
                            // The message includes a rect token (rect=W×H or rect=0×0) alongside the visibility cause.
                            assert(
                                msg.contains("rect=") || msg.contains("rect:"),
                                s"expected the NotVisible message to embed a rect= token for diagnosing the element's geometry, got: $msg"
                            )
                        case other => fail(s"expected BrowserElementNotActionableException, got $other")
                    }
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // NotAttached failure message includes the current page URL.
    // When a selector goes stale after a navigation (e.g. the page reloaded after a form
    // submit and the code then tried to act on the previous page's input), "element not
    // attached" alone gives no hint that the URL changed. The embedded URL lets the caller
    // spot the transition immediately.
    // -------------------------------------------------------------------------

    "NotAttached failure message embeds the current page URL" in run {
        withBrowser {
            // Empty page; selector won't match anything.
            onPage("""<div>hello</div>""") {
                tight {
                    Abort.run[BrowserElementException] {
                        Browser.click(Browser.Selector.id("does-not-exist"))
                    }.map {
                        case Result.Failure(ex: BrowserElementNotActionableException) =>
                            val msg = ex.getMessage
                            // The message includes "at URL ..." (the page the selector was evaluated against).
                            assert(
                                msg.contains("at URL"),
                                s"expected the NotAttached message to embed the current page URL for diagnosing stale-selector / navigation issues, got: $msg"
                            )
                        case other => fail(s"expected BrowserElementNotActionableException, got $other")
                    }
                }
            }
        }
    }

end BrowserActionabilityTest
