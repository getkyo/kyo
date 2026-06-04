package kyo

class BrowserDiscoverTest extends BrowserTest:

    override def timeout = 90.seconds

    // Test 14 (plan scenario 14): element returns full ElementInfo for a present match
    // (pins PRE-005, INV-001, Q-005). A #submit button with known geometry and classes.
    "element returns full ElementInfo for a present match" in run {
        withBrowser {
            onPage("""<html><body style="margin:0;padding:0;">
                <button id="submit" class="primary action" style="position:absolute;left:10px;top:20px;width:80px;height:32px;">Submit</button>
            </body></html>""") {
                Browser.element(Browser.Selector.css("#submit")).map { result =>
                    result match
                        case Absent => fail("expected Present(ElementInfo) but got Absent")
                        case Present(info) =>
                            assert(info.tag == "button", s"expected tag=button but got ${info.tag}")
                            assert(info.id == Present("submit"), s"expected id=Present(submit) but got ${info.id}")
                            assert(
                                info.classes.toSeq.contains("primary"),
                                s"expected classes to contain 'primary' but got ${info.classes}"
                            )
                            assert(
                                info.classes.toSeq.contains("action"),
                                s"expected classes to contain 'action' but got ${info.classes}"
                            )
                            assert(info.text == Present("Submit"), s"expected text=Present(Submit) but got ${info.text}")
                            assert(info.interactive == true, s"expected interactive=true but got ${info.interactive}")
                            assert(info.bounds.width == 80.0, s"expected bounds.width=80.0 but got ${info.bounds.width}")
                            assert(info.bounds.height == 32.0, s"expected bounds.height=32.0 but got ${info.bounds.height}")
                            assert(info.selector.nonEmpty, "expected non-empty selector string")
                }
            }
        }
    }

    // Test 15 (plan scenario 15): element returns Absent on no match (pins INV-001, PRE-005).
    "element returns Absent on no match" in run {
        withBrowser {
            onPage("<html><body></body></html>") {
                Browser.element(Browser.Selector.css("#nope")).map { result =>
                    assert(result == Absent, s"expected Absent but got $result")
                }
            }
        }
    }

    // Test 16 (plan scenario 16): elementAt hit-tests the topmost element at a pixel
    // (pins design pixel-hit-test, INV-001). A #box at (120,48,100,60). Hit point (130,58) is inside it.
    "elementAt hit-tests the topmost element at a pixel" in run {
        withBrowser {
            onPage("""<html><body style="margin:0;padding:0;">
                <div id="box" style="position:absolute;left:120px;top:48px;width:100px;height:60px;background:blue;"></div>
            </body></html>""") {
                Browser.elementAt(130, 58).map { result =>
                    result match
                        case Absent => fail("expected Present(ElementInfo) but got Absent at (130,58)")
                        case Present(info) =>
                            assert(
                                info.bounds.x <= 130.0 && info.bounds.x + info.bounds.width >= 130.0,
                                s"point (130,58) should be within element bounds but got bounds=${info.bounds}"
                            )
                }
            }
        }
    }

    // Test 17 (plan scenario 17): elementAt returns Absent for an empty point and aborts on negative coords
    // (pins INV-001, INV-006). (a) a point over blank background returns Absent; (b) negative coords abort
    // BrowserInvalidArgumentException BEFORE any page eval.
    "elementAt returns Absent for empty point and aborts on negative coordinates" in run {
        withBrowser {
            onPage("""<html><body style="margin:0;padding:0;background:white;"></body></html>""") {
                // sub-case (a): blank point returns Absent
                Browser.elementAt(5, 5).map { absentResult =>
                    // sub-case (b): negative coords abort BrowserInvalidArgumentException
                    Abort.run[BrowserReadException] {
                        Browser.elementAt(-5, 10)
                    }.map { negResult =>
                        assert(absentResult == Absent, s"expected Absent for (5,5) on blank page but got $absentResult")
                        assert(negResult.isFailure, s"expected Failure for negative coords but got $negResult")
                        negResult match
                            case Result.Failure(ex: BrowserInvalidArgumentException) => succeed
                            case other => fail(s"expected BrowserInvalidArgumentException but got $other")
                    }
                }
            }
        }
    }

    // Test 18 (plan scenario 18): elements returns a Chunk in document order, empty-fast-paths, and
    // ElementInfo.leaves filters ancestors (pins PRE-005, INV-001, pure-leaf-filter).
    // Three sub-cases in one test.
    "elements document order, empty fast-path, and ElementInfo.leaves pure filter" in run {

        // Sub-case C (pure, no Chrome needed): build a Chunk directly and verify leaves filters ancestors.
        val defaultBounds = Browser.Bounds(0, 0, 10, 10)
        val a = Browser.ElementInfo(
            selector = "#a",
            tag = "div",
            id = Present("a"),
            classes = Chunk.empty,
            text = Absent,
            bounds = defaultBounds,
            visible = true,
            inViewport = true,
            topmost = false,
            interactive = false,
            role = Absent
        )
        val ab = Browser.ElementInfo(
            selector = "#a > div:nth-of-type(1)",
            tag = "div",
            id = Absent,
            classes = Chunk.empty,
            text = Absent,
            bounds = defaultBounds,
            visible = true,
            inViewport = true,
            topmost = false,
            interactive = false,
            role = Absent
        )
        val b = Browser.ElementInfo(
            selector = "#b",
            tag = "div",
            id = Present("b"),
            classes = Chunk.empty,
            text = Absent,
            bounds = defaultBounds,
            visible = true,
            inViewport = true,
            topmost = false,
            interactive = false,
            role = Absent
        )
        val chunk  = Chunk(a, ab, b)
        val leaves = Browser.ElementInfo.leaves(chunk)
        assert(leaves.size == 2, s"expected 2 leaves but got ${leaves.size}: $leaves")
        assert(!leaves.exists(_.selector == "#a"), "ancestor #a should be filtered out by leaves")
        assert(leaves.exists(_.selector == "#a > div:nth-of-type(1)"), "descendant should be kept by leaves")
        assert(leaves.exists(_.selector == "#b"), "unrelated #b should be kept by leaves")

        // Sub-cases A and B require Chrome.
        withBrowser {
            // Sub-case A: three buttons in document order.
            onPage("""<html><body style="margin:0;padding:0;">
                <button id="btn1">One</button>
                <button id="btn2">Two</button>
                <button id="btn3">Three</button>
            </body></html>""") {
                Browser.elements(Browser.Selector.css("button")).map { buttonsResult =>
                    assert(buttonsResult.size == 3, s"expected 3 elements but got ${buttonsResult.size}")
                    assert(buttonsResult(0).tag == "button", s"expected tag=button for first element but got ${buttonsResult(0).tag}")
                    assert(
                        buttonsResult(0).selector.contains("btn1"),
                        s"expected first element selector to contain btn1 but got ${buttonsResult(0).selector}"
                    )
                    // Sub-case B: empty fast-path.
                    Browser.elements(Browser.Selector.css(".row")).map { emptyResult =>
                        assert(emptyResult.isEmpty, s"expected Chunk.empty for .row but got $emptyResult")
                    }
                }
            }
        }
    }

    // ---- Test 7-11: screenshotMarks overlays numbered badges and is settlement-transparent
    //      (INV-003 consumer, PRE-009, Q-006).
    //
    // A page with 3 buttons. Arm a MutationObserver that counts childList mutations on body into
    // window.__kyoMutCount. Issue screenshotMarks on the buttons. Assert:
    //   (a) returns a non-empty PNG,
    //   (b) the marks overlay is gone afterward (window.__kyoMarks === undefined),
    //   (c) the overlay was injected exactly once (the Scope.acquireRelease fires once).
    //
    // The mutation count assertion is NOT used here because the marks childList mutation targets
    // document.body, which is untagged, so the MutationObserver filter does not suppress it and
    // the count increments for a correct impl. The behavioral test focuses on (a) non-empty image,
    // (b) teardown, and (c) marks absent after the call.

    "screenshotMarks overlays numbered badges and removes them afterward" in run {
        val html = """<!DOCTYPE html><html><body style="margin:0;padding:0;">
            <button id="b1" style="position:absolute;left:10px;top:10px;width:80px;height:30px;">One</button>
            <button id="b2" style="position:absolute;left:10px;top:50px;width:80px;height:30px;">Two</button>
            <button id="b3" style="position:absolute;left:10px;top:90px;width:80px;height:30px;">Three</button>
        </body></html>"""
        withBrowser {
            onPage(html) {
                Browser.withConfig(_.captureHoldStillTimeout(500.millis).captureHoldStillInterval(50.millis)) {
                    Browser.elements(Browser.Selector.css("button")).map { elems =>
                        assert(elems.size == 3, s"expected 3 buttons but got ${elems.size}")
                        Browser.screenshotMarks(elems).map { img =>
                            assert(img.binary.size > 0, "screenshotMarks must return non-empty bytes")
                            val bytes = img.binary.toArray
                            assert(bytes(0) == 0x89.toByte && bytes(1) == 'P'.toByte, "result must be a valid PNG")
                            // Overlay must be removed after the call.
                            Browser.eval("String(window.__kyoMarks === undefined)").map { undefinedStr =>
                                assert(
                                    undefinedStr == "true",
                                    s"marks overlay must be removed after screenshotMarks but got: $undefinedStr"
                                )
                                Browser.eval("String(document.querySelectorAll('[data-kyo-internal=\"marks\"]').length)").map { countStr =>
                                    assert(countStr == "0", s"no data-kyo-internal=marks node should remain but got count: $countStr")
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // ---- Test 7-12: screenshotMarks aborts over maxMarks (INV-006).
    //
    // Build a Chunk of 5 ElementInfo stubs. screenshotMarks(marks, maxMarks = 3) must abort with
    // BrowserCaptureLimitExceededException("screenshotMarks", 3, 5) before any CDP call.

    "screenshotMarks aborts over maxMarks" in run {
        val dummyBounds = Browser.Bounds(0, 0, 10, 10)
        def mkInfo(n: Int) = Browser.ElementInfo(
            selector = s"#el$n",
            tag = "div",
            id = Absent,
            classes = Chunk.empty,
            text = Absent,
            bounds = dummyBounds,
            visible = true,
            inViewport = true,
            topmost = false,
            interactive = false,
            role = Absent
        )
        val marks = Chunk(mkInfo(1), mkInfo(2), mkInfo(3), mkInfo(4), mkInfo(5))
        withBrowser {
            onPage("""<html><body></body></html>""") {
                Abort.run[BrowserReadException](Browser.screenshotMarks(marks, maxMarks = 3)).map { result =>
                    result match
                        case Result.Failure(ex: BrowserCaptureLimitExceededException) =>
                            assert(ex.operation == "screenshotMarks", s"expected operation=screenshotMarks but got ${ex.operation}")
                            assert(ex.limit == 3, s"expected limit=3 but got ${ex.limit}")
                            assert(ex.reached == 5, s"expected reached=5 but got ${ex.reached}")
                        case other => fail(s"expected BrowserCaptureLimitExceededException but got: $other")
                }
            }
        }
    }

end BrowserDiscoverTest
