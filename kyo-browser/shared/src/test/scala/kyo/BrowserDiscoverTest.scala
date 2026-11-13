package kyo

import kyo.internal.DiscoverJs

class BrowserDiscoverTest extends BrowserTest:

    override def timeout = 90.seconds

    "focusedElement returns the complete element snapshot across focus blur and removal" in {
        withBrowser {
            onPage("<input id='field' class='entry' value='kept'><button id='button'>Go</button>") {
                for
                    before  <- Browser.focusedElement
                    _       <- Browser.focus(Browser.Selector.id("field"))
                    during  <- Browser.focusedElement
                    direct  <- Browser.element(Browser.Selector.id("field"))
                    _       <- Browser.focus(Browser.Selector.id("button"))
                    button  <- Browser.focusedElement
                    _       <- Browser.eval("document.activeElement.blur()")
                    blur    <- Browser.focusedElement
                    _       <- Browser.focus(Browser.Selector.id("field"))
                    _       <- Browser.eval("document.activeElement.remove()")
                    removed <- Browser.focusedElement
                yield
                    assert(before == Absent)
                    assert(during == direct)
                    during match
                        case Present(info) =>
                            assert(info.id == Present("field"))
                            assert(info.classes == Chunk("entry"))
                        case Absent => fail("expected field snapshot")
                    end match
                    assert(button.map(info => (info.tag, info.id, info.text)) == Present(("button", Present("button"), Present("Go"))))
                    assert(blur == Absent)
                    assert(removed == Absent)
            }
        }
    }

    "focusedElement reinstalls discovery after navigation" in {
        withBrowser {
            for
                _      <- Browser.goto(page("<input id='first'>"))
                _      <- Browser.focus(Browser.Selector.id("first"))
                first  <- Browser.focusedElement
                _      <- Browser.goto(page("<input id='second'>"))
                before <- Browser.focusedElement
                _      <- Browser.focus(Browser.Selector.id("second"))
                second <- Browser.focusedElement
            yield
                assert(first.map(_.id) == Present(Present("first")))
                assert(before == Absent)
                assert(second.map(_.id) == Present(Present("second")))
        }
    }

    "focusedElement follows native shadow-host focus and excludes explicit body focus" in {
        withBrowser {
            onPage("""<body tabindex='0'><div id='host'></div><script>
                const root = document.getElementById('host').attachShadow({mode:'open'});
                root.innerHTML = '<input id="inside">';
                root.getElementById('inside').focus();
                </script></body>""") {
                for
                    host <- Browser.focusedElement
                    _    <- Browser.eval("document.body.focus()")
                    body <- Browser.focusedElement
                yield
                    assert(host.map(info => (info.tag, info.id)) == Present(("div", Present("host"))))
                    assert(body == Absent)
            }
        }
    }

    "focusedElement settles a focus change during sampling with controlled page time" in {
        withBrowser {
            onPage("<input id='first'><input id='second'>") {
                for
                    _ <- Browser.eval("""(() => {
                        let now = 0;
                        const timeout = window.setTimeout.bind(window);
                        Object.defineProperty(performance, 'now', {value: () => now, configurable: true});
                        window.setTimeout = callback => timeout(() => { now += 10; callback(); }, 0);
                        const first = document.getElementById('first');
                        first.focus();
                        const active = Object.getOwnPropertyDescriptor(Document.prototype, 'activeElement').get;
                        window.focusReads = 0;
                        Object.defineProperty(document, 'activeElement', {get() {
                            const element = active.call(document);
                            if (++window.focusReads === 1) document.getElementById('second').focus();
                            return element;
                        }});
                        return 'ready';
                    })()""")
                    actual <- Browser.withConfig(
                        _.assertionStabilityWindow(20.millis).stabilitySampleInterval(10.millis).retrySchedule(Schedule.immediate.take(1))
                    ) {
                        Browser.focusedElement
                    }
                yield assert(actual.map(_.id) == Present(Present("second")))
            }
        }
    }

    "form-state native control composes focus and element reads without changing the field" in {
        withBrowser {
            onPage("<input id='first' value='first'><input id='second' value='second'>") {
                for
                    before <- Browser.eval("document.activeElement.tagName")
                    _      <- Browser.focus(Browser.Selector.id("second"))
                    active <- Browser.eval("document.activeElement.id")
                    focus  <- Browser.isFocused(Browser.Selector.id("second"))
                    info   <- Browser.element(Browser.Selector.id(active))
                    value  <- Browser.value(Browser.Selector.id("second"))
                    _      <- Browser.eval("document.activeElement.blur()")
                    after  <- Browser.eval("document.activeElement.tagName")
                yield
                    assert(before == "BODY")
                    assert(active == "second")
                    assert(focus)
                    assert(info.map(_.id) == Present(Present("second")))
                    assert(value == "second")
                    assert(after == "BODY")
            }
        }
    }

    private val splitTextPage = s"""<body style="margin:0">
        <span id="sample" style="position:absolute;left:10px;top:10px;width:100px;height:30px">${"a" * 199}😀</span>
    </body>"""

    "discovery text keeps a complete character at the truncation boundary" in {
        withBrowser {
            onPage(s"<span id='sample'>${"a" * 198}😀tail</span>") {
                Browser.element(Browser.Selector.id("sample")).map { info =>
                    assert(info.map(_.text) == Present(Present("a" * 198 + "😀")))
                }
            }
        }
    }

    "discovery text omits a character split by elements truncation" in {
        withBrowser {
            onPage(splitTextPage) {
                Browser.elements().map { infos =>
                    assert(infos.filter(_.id == Present("sample")).map(_.text) == Chunk(Present("a" * 199)))
                }
            }
        }
    }

    "discovery text omits a character split by element truncation" in {
        withBrowser {
            onPage(splitTextPage) {
                Browser.element(Browser.Selector.id("sample")).map { info =>
                    assert(info.map(_.text) == Present(Present("a" * 199)))
                }
            }
        }
    }

    "discovery text omits a character split by elementAt truncation" in {
        withBrowser {
            onPage(splitTextPage) {
                Browser.elementAt(15, 15).map { info =>
                    assert(info.map(i => (i.id, i.text)) == Present((Present("sample"), Present("a" * 199))))
                }
            }
        }
    }

    "discovery text replaces lone DOM surrogates while preserving valid pairs" in {
        withBrowser {
            onPage("""<body><span id="high"></span><span id="low"></span><span id="pair"></span>
                <script>
                    document.getElementById('high').textContent = 'x' + String.fromCharCode(0xD83D);
                    document.getElementById('low').textContent = String.fromCharCode(0xDE00) + 'y';
                    document.getElementById('pair').textContent = String.fromCharCode(0xD83D, 0xDE00);
                </script></body>""") {
                Browser.elements(Browser.Selector.css("span")).map { infos =>
                    assert(infos.map(i => (i.id, i.text)) == Chunk(
                        (Present("high"), Present("x�")),
                        (Present("low"), Present("�y")),
                        (Present("pair"), Present("😀"))
                    ))
                }
            }
        }
    }

    private def malformedDiscovery(callee: String)(read: => Unit < (Browser & Abort[BrowserReadException]))(using
        Frame,
        kyo.test.AssertScope
    ): Unit < (Browser & Abort[BrowserReadException]) =
        onPage("""<body style="margin:0"><div id="sample" style="position:absolute;left:10px;top:10px;width:100px;height:30px">Sample</div>
            <script>
                window.__kyoDiscoverInstalled = true;
                window.__kyoDiscoverProbe = () => ({text: 'z'.repeat(100000), x: 'left'});
            </script></body>""") {
            val value =
                if callee == "elements" then "JSON.stringify([window.__kyoDiscoverProbe()])"
                else "JSON.stringify({present:true,info:window.__kyoDiscoverProbe()})"
            Browser.eval(value).map { raw =>
                val decoded =
                    if callee == "elements" then Json.decode[Seq[DiscoverJs.ElementInfoWire]](raw).unit
                    else Json.decode[ElementInfoEnvelope](raw).unit
                decoded match
                    case Result.Failure(problem) =>
                        tight(Abort.run[BrowserReadException](read)).map {
                            case Result.Failure(error: BrowserProtocolErrorException) =>
                                val matches = error.error == s"decode failed: ${problem.problem}"
                                assert(error.method == callee)
                                assert(matches, s"expected the decoder diagnostic; received ${error.error.length} characters")
                            case other => fail(s"expected a protocol error, got $other")
                        }
                    case other => fail(s"expected an invalid fixture, got $other")
                end match
            }
        }

    "discovery text elements errors report the decoder diagnostic" in {
        withBrowser(malformedDiscovery("elements")(Browser.elements(Browser.Selector.id("sample")).unit))
    }

    "discovery text element errors report the decoder diagnostic" in {
        withBrowser(malformedDiscovery("element")(Browser.element(Browser.Selector.id("sample")).unit))
    }

    "discovery text elementAt errors report the decoder diagnostic" in {
        withBrowser(malformedDiscovery("elementAt")(Browser.elementAt(15, 15).unit))
    }

    private def discoveryTextBoundaries(withoutWellFormed: Boolean)(using Frame, kyo.test.AssertScope) =
        onPage("<body></body>") {
            val prepare =
                if withoutWellFormed then
                    Browser.eval("delete String.prototype.toWellFormed; typeof ''.toWellFormed").map(v => assert(v == "undefined"))
                else Kyo.unit
            prepare.andThen(Browser.eval("""(() => {
                const high = String.fromCharCode(0xD83D), low = String.fromCharCode(0xDE00);
                const values = ['  \n ', 'a'.repeat(200), 'b'.repeat(201), 'c'.repeat(199) + high + low,
                    'd'.repeat(198) + high + low, 'e'.repeat(199) + high, low + 'z', high + high + low + low, ' \t kept \n '];
                values.forEach(value => { const el = document.createElement('span'); el.textContent = value; document.body.appendChild(el); });
                return 'ready';
            })()""")).andThen {
                tight(Browser.elements(Browser.Selector.css("span"))).map { infos =>
                    assert(infos.map(_.text) == Chunk(
                        Absent,
                        Present("a" * 200),
                        Present("b" * 200),
                        Present("c" * 199),
                        Present("d" * 198 + "😀"),
                        Present("e" * 199 + "�"),
                        Present("�z"),
                        Present("�😀�"),
                        Present("kept")
                    ))
                }
            }
        }

    "discovery text boundary matrix preserves the text limit and whitespace contract" in {
        withBrowser(discoveryTextBoundaries(false))
    }

    "discovery text boundary matrix works without native well-formed conversion" in {
        withBrowser(discoveryTextBoundaries(true))
    }

    private def missingDiscovery(callee: String)(read: => Unit < (Browser & Abort[BrowserReadException]))(using
        Frame,
        kyo.test.AssertScope
    ) =
        onPage(
            """<body style="margin:0"><span id="sample" style="position:absolute;left:10px;top:10px;width:100px;height:30px">Sample</span>
            <script>window.__kyoDiscoverInstalled = true; window.__kyoDiscoverProbe = () => undefined;</script></body>"""
        ) {
            tight(Abort.run[BrowserReadException](read)).map {
                case Result.Failure(error: BrowserProtocolErrorException) =>
                    assert(error.method == callee)
                    assert(error.error == "decode failed: missing element info")
                case other => fail(s"expected a protocol error, got $other")
            }
        }

    "discovery text element errors identify a missing snapshot" in {
        withBrowser(missingDiscovery("element")(Browser.element(Browser.Selector.id("sample")).unit))
    }

    "discovery text elementAt errors identify a missing snapshot" in {
        withBrowser(missingDiscovery("elementAt")(Browser.elementAt(15, 15).unit))
    }

    // element returns full ElementInfo for a present match.
    // A #submit button with known geometry and classes.
    "element returns full ElementInfo for a present match" in {
        withBrowser {
            onPage("""<html><body style="margin:0;padding:0;">
                <button id="submit" class="primary action" style="position:absolute;left:10px;top:20px;width:80px;height:32px;">Submit</button>
            </body></html>""") {
                Browser.element(Browser.Selector.css("#submit")).map { result =>
                    result match
                        case Absent        => fail("expected Present(ElementInfo) but got Absent")
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

    // element returns Absent on no match.
    "element returns Absent on no match" in {
        withBrowser {
            onPage("<html><body></body></html>") {
                Browser.element(Browser.Selector.css("#nope")).map { result =>
                    assert(result == Absent, s"expected Absent but got $result")
                }
            }
        }
    }

    // elementAt hit-tests the topmost element at a pixel.
    // A #box at (120,48,100,60). Hit point (130,58) is inside it.
    "elementAt hit-tests the topmost element at a pixel" in {
        withBrowser {
            onPage("""<html><body style="margin:0;padding:0;">
                <div id="box" style="position:absolute;left:120px;top:48px;width:100px;height:60px;background:blue;"></div>
            </body></html>""") {
                Browser.elementAt(130, 58).map { result =>
                    result match
                        case Absent        => fail("expected Present(ElementInfo) but got Absent at (130,58)")
                        case Present(info) =>
                            assert(
                                info.bounds.x <= 130.0 && info.bounds.x + info.bounds.width >= 130.0,
                                s"point (130,58) should be within element bounds but got bounds=${info.bounds}"
                            )
                }
            }
        }
    }

    // elementAt returns Absent for an empty point and aborts on negative coords.
    // (a) a point over blank background returns Absent; (b) negative coords abort
    // BrowserInvalidArgumentException BEFORE any page eval.
    "elementAt returns Absent for empty point and aborts on negative coordinates" in {
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

    // elements returns a Chunk in document order, empty-fast-paths, and
    // ElementInfo.leaves filters ancestors.
    // Three sub-cases in one test.
    "elements document order, empty fast-path, and ElementInfo.leaves pure filter" in {

        // Sub-case C (pure, no Chrome needed): build a Chunk directly and verify leaves filters ancestors.
        val defaultBounds = Browser.Bounds(0, 0, 10, 10)
        val a             = Browser.ElementInfo(
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

    // ---- screenshotMarks overlays numbered badges and is settlement-transparent.
    //
    // A page with 3 buttons. Issue screenshotMarks on the buttons. Assert:
    //   (a) returns a non-empty PNG,
    //   (b) the marks overlay node is gone afterward (zero [data-kyo-internal="marks"] nodes).
    //
    // The marks node is removed by its unique token, so the actual DOM node count is the teardown
    // witness. The mutation count assertion is NOT used here because the marks childList mutation
    // targets document.body, which is untagged, so the MutationObserver filter does not suppress it
    // and the count increments for a correct impl.

    "screenshotMarks overlays numbered badges and removes them afterward" in {
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
                            // Overlay node must be removed after the call.
                            Browser.eval("String(document.querySelectorAll('[data-kyo-internal=\"marks\"]').length)").map { countStr =>
                                assert(countStr == "0", s"no data-kyo-internal=marks node should remain but got count: $countStr")
                            }
                        }
                    }
                }
            }
        }
    }

    // ---- screenshotMarks called twice in sequence leaves zero mark nodes (token-scoped teardown).
    //
    // Two back-to-back screenshotMarks calls each inject their own token-tagged overlay and tear
    // down exactly that overlay. Under the old single-global-slot model a second call would clobber
    // the slot the first call reads back; the per-token removal makes each call self-contained.
    // After both calls, zero [data-kyo-internal] nodes (marks or otherwise) may remain.

    "screenshotMarks called twice leaves zero mark nodes" in {
        val html = """<!DOCTYPE html><html><body style="margin:0;padding:0;">
            <button id="b1" style="position:absolute;left:10px;top:10px;width:80px;height:30px;">One</button>
            <button id="b2" style="position:absolute;left:10px;top:50px;width:80px;height:30px;">Two</button>
        </body></html>"""
        withBrowser {
            onPage(html) {
                Browser.withConfig(_.captureHoldStillTimeout(500.millis).captureHoldStillInterval(50.millis)) {
                    Browser.elements(Browser.Selector.css("button")).map { elems =>
                        Browser.screenshotMarks(elems).map { first =>
                            assert(first.binary.size > 0, "first screenshotMarks must return non-empty bytes")
                            Browser.screenshotMarks(elems).map { second =>
                                assert(second.binary.size > 0, "second screenshotMarks must return non-empty bytes")
                                Browser.eval("String(document.querySelectorAll('[data-kyo-internal]').length)").map { countStr =>
                                    assert(
                                        countStr == "0",
                                        s"no data-kyo-internal node should remain after two screenshotMarks calls but got count: $countStr"
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // ---- screenshotMarks aborts over maxMarks.
    //
    // Build a Chunk of 5 ElementInfo stubs. screenshotMarks(marks, maxMarks = 3) must abort with
    // BrowserCaptureLimitExceededException("screenshotMarks", 3, 5) before any CDP call.

    "screenshotMarks aborts over maxMarks" in {
        val dummyBounds    = Browser.Bounds(0, 0, 10, 10)
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
