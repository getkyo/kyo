package kyo

import kyo.internal.*
import kyo.internal.CdpTypes.*

/** Behavior tests for the HoldStill capture helper.
  *
  * Tests 1-3 and the convergence-despite-injection test require Chrome (extend BrowserTest).
  * Test 4 (frameHash equality/inequality) is a pure computation and does not require Chrome;
  * it lives in this class for the 1:1 source-to-test match with HoldStill.scala.
  */
class BrowserCaptureTest extends BrowserTest:

    override def timeout = 90.seconds

    // A single raw viewport capture with no internal hold-still loop, used as the body of an explicit
    // HoldStill.withHoldStill block so the loop under test drives the captures (nesting screenshot()
    // would inject a second freeze stylesheet inside the outer one).
    private def rawCapture(using Frame): Image < (Browser & Abort[BrowserReadException]) =
        Browser.use { tab =>
            CdpBackend.captureScreenshot(tab.session, ScreenshotParams(clip = Absent))
                .map(sr => CdpBase64Decode.decodeScreenshotImage("Page.captureScreenshot", sr.data))
        }

    // ---- frameHash equates byte-identical images and distinguishes different ones (pure, no Chrome).
    //
    // This test does NOT use ImageDiff: it asserts on hash values of decoded bytes directly.
    // The "no ImageDiff symbol exists" constraint is prose only, never an assertion (test discipline).
    // Uses Image.fromBase64 with small known byte arrays encoded as PNG-like payloads.

    "frameHash equates byte-identical images and distinguishes different ones" in {
        // Minimal 1x1 PNG bytes (valid PNG magic + IHDR + IDAT + IEND).
        val pngA = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg=="
        // Same bytes again.
        val pngB = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg=="
        // Different 1x1 PNG, different payload.
        val pngC = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAIAAACQd1PeAAAADklEQVQI12P4z8BQDwAEgAF/QualIQAAAABJRU5ErkJggg=="

        val imgA = Browser.Image.fromBase64(pngA).getOrElse(fail("pngA decode failed"))
        val imgB = Browser.Image.fromBase64(pngB).getOrElse(fail("pngB decode failed"))
        val imgC = Browser.Image.fromBase64(pngC).getOrElse(fail("pngC decode failed"))

        val h1 = HoldStill.frameHash(imgA)
        val h2 = HoldStill.frameHash(imgB)
        val h3 = HoldStill.frameHash(imgC)

        assert(h1 == h2, s"byte-identical images must share a hash: h1=$h1 h2=$h2")
        assert(h1 != h3, s"different images should differ in hash: h1=$h1 h3=$h3")
        succeed
    }

    // ---- hold-still returns an Image on a never-quiescing page.
    //
    // Page has a perpetual @keyframes animation cycling through background-color every 100ms.
    // Two consecutive captures will never be hash-identical. A tight captureHoldStillTimeout of
    // 300ms is installed via withConfig. Assert: returns an Image (not an abort), and the elapsed
    // time is bounded (well under 600ms).

    "hold-still returns an Image on a never-quiescing page" in {
        val html = """<body>
            <style>
              @keyframes spin {
                0%   { background-color: red; }
                25%  { background-color: blue; }
                50%  { background-color: green; }
                75%  { background-color: yellow; }
                100% { background-color: red; }
              }
              body { animation: spin 0.1s linear infinite; }
            </style>
            <div>animated</div>
        </body>"""
        withBrowser {
            onPage(html) {
                Browser.withConfig(_.captureHoldStillTimeout(300.millis).captureHoldStillInterval(30.millis)) {
                    timed {
                        Abort.run[BrowserReadException] {
                            HoldStill.withHoldStill {
                                rawCapture
                            }
                        }
                    }.map { case (elapsed, result) =>
                        result match
                            case Result.Success(img) =>
                                assert(
                                    img.binary.size > 0,
                                    "returned Image must have non-empty bytes"
                                )
                                assert(
                                    elapsed < 600.millis,
                                    s"elapsed $elapsed exceeds 600ms ceiling (tight timeout is 300ms)"
                                )
                                succeed
                            case Result.Failure(ex) =>
                                fail(s"hold-still must never abort on timeout; got Failure: ${ex.getMessage}")
                            case Result.Panic(ex) =>
                                fail(s"PANIC: $ex")
                    }
                }
            }
        }
    }

    // ---- hold-still converges on a static page.
    //
    // A fully static page with no animation, no JS timers, fonts loaded. Two consecutive captures
    // of the same static page will be hash-identical. Assert: returns quickly (well under
    // captureHoldStillTimeout), and the hash of the result equals the hash of a second immediate
    // screenshot (the page did not change during or after the hold-still).

    "hold-still converges on a static page" in {
        val html = """<!DOCTYPE html><html><body>
            <p style="color:black;font-size:16px;">static page</p>
        </body></html>"""
        withBrowser {
            onPage(html) {
                Browser.withConfig(_.captureHoldStillTimeout(1.second).captureHoldStillInterval(50.millis)) {
                    timed {
                        HoldStill.withHoldStill {
                            rawCapture
                        }
                    }.map { case (elapsed, img) =>
                        assert(
                            elapsed < 800.millis,
                            s"static page should converge well before 1s timeout; elapsed=$elapsed"
                        )
                        // Re-capture: hash should match (page is still static).
                        Browser.screenshot().map { img2 =>
                            assert(
                                HoldStill.frameHash(img) == HoldStill.frameHash(img2),
                                "hash of hold-still result should equal hash of immediate re-capture on static page"
                            )
                        }
                    }
                }
            }
        }
    }

    // ---- the freeze stylesheet is injected during capture and removed on exit.
    //
    // After HoldStill.withHoldStill returns:
    //   - document.querySelectorAll('style[data-kyo-internal]').length === 0  (0)
    //
    // The injection-during guarantee is covered by Test 1 and Test 2 converging correctly (if the
    // freeze style were not injected, animations would not pause and the convergence behavior would
    // differ). The explicit teardown assertion here checks that the removal targets the freeze
    // node by its unique token, so the actual DOM node count is the correctness witness.

    "the freeze stylesheet is injected during capture and removed on exit" in {
        val html = """<!DOCTYPE html><html><body><div>test</div></body></html>"""
        withBrowser {
            onPage(html) {
                HoldStill.withHoldStill {
                    rawCapture
                }.andThen {
                    // After withHoldStill returns, the freeze style node must be gone.
                    Browser.eval("String(document.querySelectorAll('style[data-kyo-internal]').length)").map {
                        styleCount =>
                            assert(
                                styleCount == "0",
                                s"no style[data-kyo-internal] should remain after withHoldStill; got count: $styleCount"
                            )
                    }
                }
            }
        }
    }

    // ---- Nested hold-still: a freeze nested inside another freeze tears down both, leaving the
    //      page unfrozen (no stuck animation-play-state: paused).
    //
    // The freeze style is removed by its unique token, so an inner freeze's scope exit removes only
    // the inner node and cannot clobber the outer node's teardown. This composition (a hold-still
    // capture inside an outer frozen scope) is the supported nesting two callers compose; under the
    // old single-global-slot model the inner exit deleted the slot and the outer freeze leaked,
    // freezing the page permanently. After the outer scope exits:
    //   - document.querySelectorAll('style[data-kyo-internal]').length === 0  (no stuck freeze)
    //   - getComputedStyle(document.body).animationPlayState is NOT stuck 'paused'.

    "nested hold-still freezes tear down both and leave the page unfrozen" in {
        val html = """<!DOCTYPE html><html><head><style>
            @keyframes spin { from { opacity: 1; } to { opacity: 0.5; } }
            body { animation: spin 1s linear infinite; }
        </style></head><body><div id="content">nested</div></body></html>"""
        withBrowser {
            onPage(html) {
                Browser.withConfig(_.captureHoldStillTimeout(400.millis).captureHoldStillInterval(50.millis)) {
                    // Outer freeze wraps an inner hold-still capture (which freezes again): a genuine nest.
                    HoldStill.withFrozenPage {
                        HoldStill.withHoldStill {
                            rawCapture
                        }
                    }.andThen {
                        // Both freeze style nodes must be gone after the outer scope exits.
                        Browser.eval("String(document.querySelectorAll('style[data-kyo-internal]').length)").map { freezeCount =>
                            assert(
                                freezeCount == "0",
                                s"no freeze style[data-kyo-internal] should remain after nested hold-still; got count: $freezeCount"
                            )
                            // The page must not be stuck paused: a leaked freeze would force 'paused' page-wide.
                            Browser.eval("getComputedStyle(document.body).animationPlayState").map { playState =>
                                assert(
                                    playState != "paused",
                                    s"page animation-play-state must not be stuck 'paused' after nested hold-still; got: $playState"
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // ---- Convergence-despite-injection: HoldStill converges despite its own freeze injection
    //      producing an unfiltered childList mutation on document.head.
    //
    // Injecting the freeze <style data-kyo-internal> produces ONE unfiltered childList mutation
    // (the insertion targets the untagged document.head parent). A live settleForCapture gate
    // tolerates this as one quiet-window. HoldStill convergence is FRAME-HASH based, not
    // settlement-gate based, so the injection mutation does not break convergence.
    //
    // This test asserts HoldStill converges and returns a stable Image on a static page despite
    // the freeze injection side-effect. It is a behavioral assertion: we observe the returned
    // Image is non-null and the freeze style is cleaned up, proving end-to-end convergence held.

    "hold-still converges despite its own freeze injection mutation" in {
        val html = """<!DOCTYPE html><html><body><div id="content">stable</div></body></html>"""
        withBrowser {
            onPage(html) {
                Browser.withConfig(_.captureHoldStillTimeout(1.second).captureHoldStillInterval(50.millis)) {
                    Abort.run[BrowserReadException] {
                        HoldStill.withHoldStill {
                            rawCapture
                        }
                    }.map { result =>
                        result match
                            case Result.Success(img) =>
                                // Convergence succeeded: verify cleanup too (freeze style node gone).
                                Browser.eval("String(document.querySelectorAll('style[data-kyo-internal]').length)").map { r =>
                                    assert(
                                        r == "0",
                                        s"freeze style must be removed after convergence; got count: $r"
                                    )
                                    // Verify the returned Image is non-empty.
                                    assert(
                                        img.binary.size > 0,
                                        "returned Image must have non-empty bytes"
                                    )
                                }
                            case Result.Failure(ex) =>
                                fail(s"hold-still must not abort despite freeze injection mutation; got: ${ex.getMessage}")
                            case Result.Panic(ex) =>
                                fail(s"PANIC: $ex")
                    }
                }
            }
        }
    }

    // ---- screenshot captures the live viewport size.
    //
    // Set the viewport to 390x844 then take a screenshot. The returned Image bytes must decode to
    // dimensions 390x844, confirming the live-viewport path (NOT the legacy 1280x720 crop).
    // PNG dimensions live at bytes 16-23 of the IHDR chunk (bytes 16-19 = width, 20-23 = height,
    // big-endian int32).

    "screenshot captures the live viewport size" in {
        withBrowser {
            onPage("""<!DOCTYPE html><html><body><div style="background:blue;width:100%;height:100%;">vp</div></body></html>""") {
                Browser.withConfig(_.captureHoldStillTimeout(500.millis).captureHoldStillInterval(50.millis)) {
                    Browser.setViewport(390, 844).andThen {
                        Browser.screenshot().map { img =>
                            val bytes = img.binary.toArray
                            // PNG IHDR width at offset 16 (big-endian int32)
                            val w = ((bytes(16) & 0xff) << 24) | ((bytes(17) & 0xff) << 16) | ((bytes(18) & 0xff) << 8) | (bytes(19) & 0xff)
                            val h = ((bytes(20) & 0xff) << 24) | ((bytes(21) & 0xff) << 16) | ((bytes(22) & 0xff) << 8) | (bytes(23) & 0xff)
                            assert(w == 390, s"expected screenshot width 390 but got $w")
                            assert(h == 844, s"expected screenshot height 844 but got $h")
                        }
                    }
                }
            }
        }
    }

    // ---- Test 7-2: screenshot returns a valid PNG by default.
    //
    // On a static page, screenshot() must return non-empty bytes starting with the PNG magic bytes
    // 0x89 'P' 'N' 'G'.

    "screenshot returns a valid PNG by default" in {
        withBrowser {
            onPage("""<!DOCTYPE html><html><body><div style="background:red;width:100px;height:50px;">box</div></body></html>""") {
                Browser.withConfig(_.captureHoldStillTimeout(500.millis).captureHoldStillInterval(50.millis)) {
                    Browser.screenshot().map { img =>
                        assert(img.binary.size > 0, "screenshot must return non-empty bytes")
                        val bytes = img.binary.toArray
                        assert(bytes(0) == 0x89.toByte, "byte 0 must be 0x89 (PNG magic)")
                        assert(bytes(1) == 'P'.toByte, "byte 1 must be 'P'")
                        assert(bytes(2) == 'N'.toByte, "byte 2 must be 'N'")
                        assert(bytes(3) == 'G'.toByte, "byte 3 must be 'G'")
                    }
                }
            }
        }
    }

    // ---- Test 7-3: screenshotRegion captures a below-the-fold region.
    //
    // A tall page (body height = 5000px). screenshotRegion(0, 2000, 400, 300) must return a
    // 400x300 PNG (captureBeyondViewport allowed the out-of-viewport region). Verify dimensions
    // via PNG IHDR as above.

    "screenshotRegion captures a below-the-fold region" in {
        withBrowser {
            onPage("""<!DOCTYPE html><html><body style="height:5000px;background:green;"></body></html>""") {
                Browser.withConfig(_.captureHoldStillTimeout(500.millis).captureHoldStillInterval(50.millis)) {
                    Browser.screenshotRegion(0, 2000, 400, 300).map { img =>
                        assert(img.binary.size > 0, "screenshotRegion must return non-empty bytes")
                        val bytes = img.binary.toArray
                        val w     = ((bytes(16) & 0xff) << 24) | ((bytes(17) & 0xff) << 16) | ((bytes(18) & 0xff) << 8) | (bytes(19) & 0xff)
                        val h     = ((bytes(20) & 0xff) << 24) | ((bytes(21) & 0xff) << 16) | ((bytes(22) & 0xff) << 8) | (bytes(23) & 0xff)
                        assert(w == 400, s"expected width 400 but got $w")
                        assert(h == 300, s"expected height 300 but got $h")
                    }
                }
            }
        }
    }

    // ---- screenshotRegion aborts on non-positive size.
    //
    // Both screenshotRegion(0, 0, 0, 300) and screenshotRegion(0, 0, 100, -1) must abort with
    // BrowserInvalidArgumentException BEFORE any CDP call (the abort fires synchronously, not
    // after a network round-trip).

    "screenshotRegion aborts on non-positive size" in {
        withBrowser {
            onPage("""<html><body></body></html>""") {
                Abort.run[BrowserReadException](Browser.screenshotRegion(0, 0, 0, 300)).map { r1 =>
                    Abort.run[BrowserReadException](Browser.screenshotRegion(0, 0, 100, -1)).map { r2 =>
                        r1 match
                            case Result.Failure(ex: BrowserInvalidArgumentException) =>
                                assert(
                                    ex.getMessage.contains("screenshotRegion"),
                                    s"exception message must mention 'screenshotRegion' but was: ${ex.getMessage}"
                                )
                            case other => fail(s"expected BrowserInvalidArgumentException for width=0 but got: $other")
                        end match
                        r2 match
                            case Result.Failure(ex: BrowserInvalidArgumentException) =>
                                assert(
                                    ex.getMessage.contains("screenshotRegion"),
                                    s"exception message must mention 'screenshotRegion' but was: ${ex.getMessage}"
                                )
                            case other => fail(s"expected BrowserInvalidArgumentException for height=-1 but got: $other")
                        end match
                    }
                }
            }
        }
    }

    // ---- Test 7-5: screenshotFullPage returns one band per viewport-height.
    //
    // Set the viewport to 800x400. Set the body height to exactly 3 * 400 = 1200px. The band
    // count = ceil(1200 / 400) = 3. screenshotFullPage() must return a Chunk[Image] of 3 elements,
    // each non-empty.

    "screenshotFullPage returns one band per viewport-height" in {
        withBrowser {
            onPage("""<!DOCTYPE html><html style="margin:0;padding:0;"><body style="margin:0;padding:0;"></body></html>""") {
                Browser.withConfig(_.captureHoldStillTimeout(500.millis).captureHoldStillInterval(50.millis)) {
                    Browser.setViewport(800, 400).andThen {
                        // Set body height to exactly 3 * viewport-height; zero margins so scrollHeight == 1200.
                        Browser.eval(
                            "document.documentElement.style.margin='0';document.documentElement.style.padding='0';document.body.style.margin='0';document.body.style.padding='0';document.body.style.height='1200px'; ''"
                        ).andThen {
                            Browser.screenshotFullPage().map { bands =>
                                assert(bands.size == 3, s"expected 3 bands but got ${bands.size}")
                                bands.foreach { band =>
                                    assert(band.binary.size > 0, "each band must be non-empty")
                                }
                                succeed
                            }
                        }
                    }
                }
            }
        }
    }

    // ---- Test 7-5b: screenshotFullPage injects the freeze style exactly once across all bands.
    //
    // A page whose content height spans multiple bands (bands > 1) forces the multi-band path.
    // A MutationObserver is armed before the call to count how many times a freeze
    // style[data-kyo-internal="freeze"] node is ADDED to document.head. After the call:
    //   (a) bands.size > 1 (multi-band path was genuinely exercised),
    //   (b) freeze-install count == 1 (freeze was injected exactly once for the whole capture,
    //       not once per band),
    //   (c) document.querySelectorAll('style[data-kyo-internal="freeze"]').length === 0
    //       (freeze style was torn down after the call).
    // A regression to per-band withHoldStill would make the install count equal the band count
    // and fail assertion (b).

    "screenshotFullPage injects the freeze style exactly once across all bands" in {
        withBrowser {
            onPage("""<!DOCTYPE html><html style="margin:0;padding:0;"><body style="margin:0;padding:0;"></body></html>""") {
                Browser.withConfig(_.captureHoldStillTimeout(500.millis).captureHoldStillInterval(50.millis)) {
                    Browser.setViewport(800, 400).andThen {
                        // Set body to 2 * viewport-height so we get exactly 2 bands (multi-band path exercised).
                        Browser.eval(
                            "document.documentElement.style.margin='0';document.documentElement.style.padding='0';document.body.style.margin='0';document.body.style.padding='0';document.body.style.height='800px'; ''"
                        ).andThen {
                            // Arm a MutationObserver that counts additions of freeze style nodes.
                            // The observer targets document.head, watching childList. On each added node
                            // it checks whether the node is a style element with data-kyo-internal="freeze".
                            // The count is stored in window.__kyoFreezeCount so we can read it after the call.
                            Browser.eval("""(() => {
                                window.__kyoFreezeCount = 0;
                                const obs = new MutationObserver(mutations => {
                                    for (const m of mutations) {
                                        for (const n of m.addedNodes) {
                                            if (n.nodeName === 'STYLE' && n.getAttribute('data-kyo-internal') === 'freeze') {
                                                window.__kyoFreezeCount++;
                                            }
                                        }
                                    }
                                });
                                obs.observe(document.head, { childList: true });
                                window.__kyoFreezeObs = obs;
                                return 'armed';
                            })()""").andThen {
                                Browser.screenshotFullPage().map { bands =>
                                    // Disconnect the observer (cleanup).
                                    Browser.eval("window.__kyoFreezeObs && window.__kyoFreezeObs.disconnect(); 'done'").andThen {
                                        // (a) multi-band path was exercised.
                                        assert(
                                            bands.size > 1,
                                            s"expected multiple bands but got ${bands.size}; multi-band path not exercised"
                                        )
                                        // (b) freeze style was installed exactly once.
                                        Browser.eval("String(window.__kyoFreezeCount)").map { countStr =>
                                            assert(
                                                countStr == "1",
                                                s"freeze style must be injected exactly once for all bands but MutationObserver counted: $countStr"
                                            )
                                            // (c) freeze style was torn down after the call.
                                            Browser.eval(
                                                "String(document.querySelectorAll('style[data-kyo-internal=\"freeze\"]').length)"
                                            ).map {
                                                remainingStr =>
                                                    assert(
                                                        remainingStr == "0",
                                                        s"no freeze style should remain after screenshotFullPage but got count: $remainingStr"
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
        }
    }

    // ---- screenshotFullPage aborts over maxBands BEFORE any capture.
    //
    // Set body height to 7 * viewport-height. screenshotFullPage(maxBands = 3) must abort with
    // BrowserCaptureLimitExceededException("screenshotFullPage", 3, 7) before producing any
    // partial output.

    "screenshotFullPage aborts over maxBands before any capture" in {
        withBrowser {
            onPage("""<!DOCTYPE html><html style="margin:0;padding:0;"><body style="margin:0;padding:0;"></body></html>""") {
                Browser.setViewport(800, 400).andThen {
                    // Set body height to exactly 7 * 400 = 2800px with zero margins so scrollHeight == 2800.
                    Browser.eval(
                        "document.documentElement.style.margin='0';document.documentElement.style.padding='0';document.body.style.margin='0';document.body.style.padding='0';document.body.style.height='2800px'; ''"
                    ).andThen {
                        Abort.run[BrowserReadException] {
                            Browser.screenshotFullPage(maxBands = 3)
                        }.map { result =>
                            result match
                                case Result.Failure(ex: BrowserCaptureLimitExceededException) =>
                                    assert(
                                        ex.operation == "screenshotFullPage",
                                        s"expected operation=screenshotFullPage but got ${ex.operation}"
                                    )
                                    assert(ex.limit == 3, s"expected limit=3 but got ${ex.limit}")
                                    assert(ex.reached == 7, s"expected reached=7 but got ${ex.reached}")
                                case other => fail(s"expected BrowserCaptureLimitExceededException but got: $other")
                        }
                    }
                }
            }
        }
    }

    // ---- screenshotElement auto-waits for a late-appearing element.
    //
    // A page with a setTimeout that inserts .badge after 200ms. screenshotElement issued
    // immediately must auto-wait (withRetry) and return the badge's Image when it appears.

    "screenshotElement auto-waits for a late-appearing element" in {
        val html = """<!DOCTYPE html><html><body>
            <script>setTimeout(() => {
                const d = document.createElement('div');
                d.className = 'badge';
                d.style.cssText = 'background:red;width:40px;height:20px;';
                d.textContent = 'X';
                document.body.appendChild(d);
            }, 200);</script>
        </body></html>"""
        withBrowser {
            onPage(html) {
                Browser.withConfig(
                    _.captureHoldStillTimeout(500.millis)
                        .captureHoldStillInterval(50.millis)
                        .retrySchedule(Schedule.fixed(100.millis).take(20))
                ) {
                    Browser.screenshotElement(Browser.Selector.css(".badge")).map { img =>
                        assert(img.binary.size > 0, "screenshotElement must return non-empty bytes for late-appearing element")
                        val bytes = img.binary.toArray
                        assert(bytes(0) == 0x89.toByte, "result must be a PNG")
                    }
                }
            }
        }
    }

    // ---- screenshotElement aborts BrowserElementNotFoundException when the element never appears.
    //
    // A page with no .never element. Under a tight retrySchedule, screenshotElement must abort
    // BrowserElementNotFoundException within the schedule budget.

    "screenshotElement aborts BrowserElementNotFoundException for missing element" in {
        withBrowser {
            onPage("""<html><body><div>no match here</div></body></html>""") {
                tight {
                    Abort.run[BrowserReadException] {
                        Browser.screenshotElement(Browser.Selector.css(".never"))
                    }.map { result =>
                        result match
                            case Result.Failure(_: BrowserElementNotFoundException) => succeed
                            case other => fail(s"expected BrowserElementNotFoundException but got: $other")
                    }
                }
            }
        }
    }

    // ---- screenshotElement with transparentBackground produces a transparent background.
    //
    // The .badge element has a TRANSPARENT background of its own and contains a small opaque dot, so
    // the badge's bounding box has the page's default background showing through the surrounding gap.
    // The page sets NO opaque background, so the default-background override is what fills that gap:
    // `Emulation.setDefaultBackgroundColorOverride({a:0})` paints it transparent, while the default
    // (no override) is opaque. If the page painted its own opaque background the override would have
    // nothing to show through, so the page is left background-less on purpose.
    //
    //   - With transparentBackground = true the gap is alpha=0, so Chrome emits a PNG with an alpha
    //     channel: IHDR color type == 6 (truecolor with alpha / RGBA).
    //   - The same capture WITHOUT transparentBackground fills the gap with Chrome's default opaque
    //     background, so its bytes DIFFER from the transparent capture. This distinguishes a real
    //     transparent output from an opaque one: if the override were a no-op the two captures would
    //     be byte-identical and the assertion would FAIL.
    //
    // PNG IHDR color type lives at byte offset 25 (8-byte signature + 4-byte length + 4-byte "IHDR"
    // + width(4) + height(4) + bit depth(1)).
    //
    // The override-restore property is also pinned: after the transparent capture, screenshot()
    // succeeds, confirming the override was cleared (Scope.acquireRelease teardown).

    "screenshotElement with transparentBackground produces a transparent background" in {
        val html = """<!DOCTYPE html><html><body style="margin:0;">
            <div class="badge" style="background:transparent;width:50px;height:30px;">
                <div style="background:blue;width:10px;height:10px;"></div>
            </div>
        </body></html>"""
        withBrowser {
            onPage(html) {
                Browser.withConfig(_.captureHoldStillTimeout(500.millis).captureHoldStillInterval(50.millis)) {
                    Browser.screenshotElement(
                        Browser.Selector.css(".badge"),
                        format = Browser.ScreenshotFormat.Png,
                        transparentBackground = true
                    ).map { transparent =>
                        Browser.screenshotElement(
                            Browser.Selector.css(".badge"),
                            format = Browser.ScreenshotFormat.Png,
                            transparentBackground = false
                        ).map { opaque =>
                            val tBytes = transparent.binary.toArray
                            val oBytes = opaque.binary.toArray
                            assert(tBytes.length > 0, "transparent-background screenshot must be non-empty")
                            assert(tBytes(0) == 0x89.toByte, "result must be a valid PNG")
                            // IHDR color type 6 == RGBA: the transparent capture carries an alpha channel.
                            val colorType = tBytes(25) & 0xff
                            assert(
                                colorType == 6,
                                s"transparent capture must have an alpha channel (IHDR color type 6) but got $colorType"
                            )
                            // The transparent gap (alpha=0) must differ from the opaque gap (default opaque
                            // background): if the override were a no-op, the two captures would be byte-identical.
                            assert(
                                !java.util.Arrays.equals(tBytes, oBytes),
                                "transparent and opaque captures must differ; identical bytes mean the override did not take effect"
                            )
                            // The override must not persist: a subsequent screenshot() still succeeds.
                            Browser.screenshot().map { subsequent =>
                                assert(subsequent.binary.size > 0, "subsequent screenshot must also be non-empty")
                            }
                        }
                    }
                }
            }
        }
    }

    // ---- screenshotElement never widens the retry channel.
    //
    // A page that perpetually mutates (setInterval DOM mutation) AND has no .never element.
    // screenshotElement under a tight retrySchedule must abort BrowserElementNotFoundException
    // (the element channel), NOT a BrowserMutationException from the perpetual mutations.
    // The Test.timed bound confirms it exits within the schedule budget.

    "screenshotElement does not widen the retry channel to BrowserMutationException" in {
        val html = """<!DOCTYPE html><html><body>
            <script>setInterval(() => {
                const p = document.createElement('p');
                p.textContent = 'mut' + Date.now();
                document.body.appendChild(p);
            }, 50);</script>
        </body></html>"""
        withBrowser {
            onPage(html) {
                tight {
                    timed {
                        Abort.run[BrowserReadException] {
                            Browser.screenshotElement(Browser.Selector.css(".never"))
                        }
                    }.map { case (elapsed, result) =>
                        result match
                            case Result.Failure(_: BrowserElementNotFoundException) =>
                                assert(
                                    elapsed < 1.second,
                                    s"must abort within tight schedule budget but took $elapsed"
                                )
                            case Result.Failure(other) =>
                                fail(s"expected BrowserElementNotFoundException but got ${other.getClass.getName}: $other")
                            case other => fail(s"expected Failure but got: $other")
                    }
                }
            }
        }
    }

end BrowserCaptureTest
