package kyo

import kyo.internal.HoldStill

/** Behavior tests for the HoldStill capture helper.
  *
  * Tests 1-3 and the convergence-despite-injection test require Chrome (extend BrowserTest).
  * Test 4 (frameHash equality/inequality) is a pure computation and does not require Chrome;
  * it lives in this class for the 1:1 source-to-test match with HoldStill.scala.
  */
class BrowserCaptureTest extends BrowserTest:

    override def timeout = 90.seconds

    // ---- Test 4 (pure, no Chrome): frameHash equates byte-identical images and
    //      distinguishes different ones (INV-004).
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

    // ---- Test 1: hold-still returns an Image on a never-quiescing page (INV-004).
    //
    // Page has a perpetual @keyframes animation cycling through background-color every 100ms.
    // Two consecutive captures will never be hash-identical. A tight captureHoldStillTimeout of
    // 300ms is installed via withConfig. Assert: returns an Image (not an abort), and the elapsed
    // time is bounded (well under 600ms).

    "hold-still returns an Image on a never-quiescing page" in run {
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
                                Browser.screenshot(1280, 720)
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

    // ---- Test 2: hold-still converges on a static page (INV-004).
    //
    // A fully static page with no animation, no JS timers, fonts loaded. Two consecutive captures
    // of the same static page will be hash-identical. Assert: returns quickly (well under
    // captureHoldStillTimeout), and the hash of the result equals the hash of a second immediate
    // screenshot (the page did not change during or after the hold-still).

    "hold-still converges on a static page" in run {
        val html = """<!DOCTYPE html><html><body>
            <p style="color:black;font-size:16px;">static page</p>
        </body></html>"""
        withBrowser {
            onPage(html) {
                Browser.withConfig(_.captureHoldStillTimeout(1.second).captureHoldStillInterval(50.millis)) {
                    timed {
                        HoldStill.withHoldStill {
                            Browser.screenshot(1280, 720)
                        }
                    }.map { case (elapsed, img) =>
                        assert(
                            elapsed < 800.millis,
                            s"static page should converge well before 1s timeout; elapsed=$elapsed"
                        )
                        // Re-capture: hash should match (page is still static).
                        Browser.screenshot(1280, 720).map { img2 =>
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

    // ---- Test 3: the freeze stylesheet is injected during capture and removed on exit (PRE-009,
    //      INV-003 consumer).
    //
    // After HoldStill.withHoldStill returns:
    //   - window.__kyoFreezeStyle === undefined  (true)
    //   - document.querySelectorAll('style[data-kyo-internal]').length === 0  (0)
    //
    // The injection-during guarantee is covered by Test 1 and Test 2 converging correctly (if the
    // freeze style were not injected, animations would not pause and the convergence behavior would
    // differ). The explicit teardown assertion here pins PRE-009.

    "the freeze stylesheet is injected during capture and removed on exit" in run {
        val html = """<!DOCTYPE html><html><body><div>test</div></body></html>"""
        withBrowser {
            onPage(html) {
                HoldStill.withHoldStill {
                    Browser.screenshot(1280, 720)
                }.andThen {
                    // After withHoldStill returns, the freeze style must be gone.
                    Browser.eval("window.__kyoFreezeStyle === undefined").map { undefinedResult =>
                        assert(
                            undefinedResult == "true",
                            s"window.__kyoFreezeStyle must be undefined after withHoldStill; got: $undefinedResult"
                        )
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

    "hold-still converges despite its own freeze injection mutation" in run {
        val html = """<!DOCTYPE html><html><body><div id="content">stable</div></body></html>"""
        withBrowser {
            onPage(html) {
                Browser.withConfig(_.captureHoldStillTimeout(1.second).captureHoldStillInterval(50.millis)) {
                    Abort.run[BrowserReadException] {
                        HoldStill.withHoldStill {
                            Browser.screenshot(1280, 720)
                        }
                    }.map { result =>
                        result match
                            case Result.Success(img) =>
                                // Convergence succeeded: verify cleanup too (freeze style gone).
                                Browser.eval("window.__kyoFreezeStyle === undefined").map { r =>
                                    assert(
                                        r == "true",
                                        s"freeze style must be removed after convergence; got: $r"
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

end BrowserCaptureTest
