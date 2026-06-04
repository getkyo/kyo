package kyo

class BrowserReadTest extends BrowserTest:

    override def timeout = 90.seconds

    // ---- text (retrying) ----

    "text reads heading content" in run {
        withBrowser {
            onPage("<h1 role='heading'>Hello World</h1>") {
                Browser.text(Browser.Selector.heading).map { t =>
                    assert(t == "Hello World", s"Expected 'Hello World' but got '$t'")
                }
            }
        }
    }

    "text with nested children returns combined innerText" in run {
        withBrowser {
            onPage("<div id='parent'>Hello <span>World</span> Foo</div>") {
                Browser.text(Browser.Selector.css("#parent")).map { t =>
                    assert(t.contains("Hello"), s"Expected 'Hello' in '$t'")
                    assert(t.contains("World"), s"Expected 'World' in '$t'")
                    assert(t.contains("Foo"), s"Expected 'Foo' in '$t'")
                }
            }
        }
    }

    "text with no text returns empty string" in run {
        withBrowser {
            onPage("<div id='empty'></div>") {
                Browser.text(Browser.Selector.css("#empty")).map { t =>
                    assert(t.isEmpty, s"Expected empty but got '$t'")
                }
            }
        }
    }

    "text with non-existent selector fails with BrowserElementNotFoundException" in run {
        withBrowser {
            onPage("<div>Nothing here</div>") {
                tight {
                    Abort.run[BrowserElementException] {
                        Browser.text(Browser.Selector.css("#does-not-exist"))
                    }
                }.map { result =>
                    result match
                        case Result.Failure(_: BrowserElementNotFoundException) => succeed
                        case other => fail(s"Expected Result.Failure(_: BrowserElementNotFoundException) but got $other")
                }
            }
        }
    }

    "text with dynamic element retries until found" in run {
        withBrowser {
            onPage(
                "<div id='container'></div><script>setTimeout(function(){document.getElementById('container').innerHTML='<span id=\"target\">loaded</span>'},200)</script>"
            ) {
                Browser.text(Browser.Selector.css("#target")).map { t =>
                    assert(t == "loaded", s"Expected 'loaded' but got '$t'")
                }
            }
        }
    }

    "text with fast schedule fails quickly" in run {
        withBrowser {
            onPage("<div>Nothing</div>") {
                Browser.withConfig(_.retrySchedule(Schedule.fixed(20.millis).take(3))) {
                    Abort.run[BrowserElementException] {
                        Browser.text(Browser.Selector.css("#nonexistent"))
                    }
                }.map { result =>
                    result match
                        case Result.Failure(_: BrowserElementNotFoundException) => succeed
                        case other => fail(s"Expected Result.Failure(_: BrowserElementNotFoundException) but got $other")
                }
            }
        }
    }

    // ---- attribute (retrying) ----

    "attribute reads href from link" in run {
        withBrowser {
            onPage("<a role='link' href='https://example.com'>Link</a>") {
                Browser.attribute(Browser.Selector.css("a"), "href").map { v =>
                    assert(v == "https://example.com", s"Expected 'https://example.com' but got '$v'")
                }
            }
        }
    }

    "attribute reads type from input" in run {
        withBrowser {
            onPage("<input role='textbox' type='text' />") {
                Browser.attribute(Browser.Selector.css("input"), "type").map { v =>
                    assert(v == "text", s"Expected 'text' but got '$v'")
                }
            }
        }
    }

    "attribute returns empty for non-existent attribute" in run {
        withBrowser {
            onPage("<div id='el'>Hi</div>") {
                Browser.attribute(Browser.Selector.css("#el"), "data-missing").map { v =>
                    assert(v.isEmpty, s"Expected empty but got '$v'")
                }
            }
        }
    }

    "attribute with element not found retries then fails" in run {
        withBrowser {
            onPage("<div>Nothing</div>") {
                tight {
                    Abort.run[BrowserElementException] {
                        Browser.attribute(Browser.Selector.css("#absent"), "href")
                    }
                }.map { result =>
                    result match
                        case Result.Failure(_: BrowserElementNotFoundException) => succeed
                        case other => fail(s"Expected Result.Failure(_: BrowserElementNotFoundException) but got $other")
                }
            }
        }
    }

    // ---- count (retrying) ----

    "count returns number of buttons" in run {
        withBrowser {
            onPage("<button role='button'>A</button><button role='button'>B</button>") {
                Browser.count(Browser.Selector.css("button")).map { n =>
                    assert(n == 2, s"Expected 2 but got $n")
                }
            }
        }
    }

    "count on empty page returns zero" in run {
        withBrowser {
            onPage("<div>No buttons here</div>") {
                tight {
                    Browser.count(Browser.Selector.css("button"))
                }.map { n =>
                    assert(n == 0, s"Expected 0 but got $n")
                }
            }
        }
    }

    "count with multiple element types" in run {
        withBrowser {
            onPage(
                "<div id='container'><span class='item'>A</span><span class='item'>B</span></div>"
            ) {
                Browser.count(Browser.Selector.css(".item")).map { n =>
                    assert(n == 2, s"Expected 2 but got $n")
                }
            }
        }
    }

    // ---- boundingBox ----

    "boundingBox returns Present rect with declared CSS width and height for a known-size div" in run {
        withBrowser {
            onPage(
                "<div id='b' style='position:absolute;left:50px;top:30px;width:120px;height:80px;background:red'></div>"
            ) {
                Browser.setViewport(1024, 768).andThen {
                    Browser.boundingBox(Browser.Selector.id("b")).map {
                        case Present(box) =>
                            assert(
                                (box.width - 120.0).abs <= 1.0,
                                s"Expected width ~120 but got ${box.width}"
                            )
                            assert(
                                (box.height - 80.0).abs <= 1.0,
                                s"Expected height ~80 but got ${box.height}"
                            )
                            assert((box.x - 50.0).abs <= 1.0, s"Expected x ~50 but got ${box.x}")
                            assert((box.y - 30.0).abs <= 1.0, s"Expected y ~30 but got ${box.y}")
                        case Absent =>
                            fail("expected Present(BoundingBox) for visible element, got Absent")
                    }
                }
            }
        }
    }

    "boundingBox reports actual rect for an off-viewport element (negative or beyond-viewport coords)" in run {
        withBrowser {
            onPage(
                "<div id='b' style='position:absolute;left:-500px;top:5000px;width:50px;height:50px;background:blue'></div>"
            ) {
                Browser.setViewport(1024, 768).andThen {
                    Browser.boundingBox(Browser.Selector.id("b")).map {
                        case Present(box) =>
                            assert(box.x < 0.0, s"Expected x < 0 but got ${box.x}")
                            assert(box.y > 768.0, s"Expected y > viewport-height but got ${box.y}")
                            assert(
                                (box.width - 50.0).abs <= 1.0,
                                s"Expected width ~50 but got ${box.width}"
                            )
                            assert(
                                (box.height - 50.0).abs <= 1.0,
                                s"Expected height ~50 but got ${box.height}"
                            )
                        case Absent =>
                            fail("expected Present(BoundingBox) for off-viewport element, got Absent")
                    }
                }
            }
        }
    }

    "boundingBox returns Absent for a non-existent selector" in run {
        withBrowser {
            onPage("<div>nothing matching</div>") {
                Browser.setViewport(1024, 768).andThen {
                    Browser.boundingBox(Browser.Selector.id("nope")).map { result =>
                        assert(result.isEmpty, s"Expected Absent but got $result")
                    }
                }
            }
        }
    }

    "boundingBox returns Absent for a display:none element (no box model)" in run {
        withBrowser {
            onPage(
                "<div id='b' style='display:none;width:100px;height:100px'></div>"
            ) {
                Browser.setViewport(1024, 768).andThen {
                    Browser.boundingBox(Browser.Selector.id("b")).map { result =>
                        assert(
                            result.isEmpty,
                            s"Expected Absent for display:none element but got $result"
                        )
                    }
                }
            }
        }
    }

    "boundingBox rect matches getBoundingClientRect within 1px for in-viewport element" in run {
        withBrowser {
            onPage(
                "<div id='b' style='position:absolute;left:10px;top:20px;width:200px;height:100px;background:green'></div>"
            ) {
                Browser.setViewport(1024, 768).andThen {
                    Browser.boundingBox(Browser.Selector.id("b")).map {
                        case Present(box) =>
                            Browser.eval("document.getElementById('b').getBoundingClientRect().x").map { xStr =>
                                Browser.eval("document.getElementById('b').getBoundingClientRect().y").map { yStr =>
                                    Browser.eval("document.getElementById('b').getBoundingClientRect().width").map { wStr =>
                                        Browser.eval("document.getElementById('b').getBoundingClientRect().height").map { hStr =>
                                            val jsX = xStr.toDouble
                                            val jsY = yStr.toDouble
                                            val jsW = wStr.toDouble
                                            val jsH = hStr.toDouble
                                            assert(
                                                (box.x - jsX).abs <= 1.0,
                                                s"Expected x ~$jsX but got ${box.x}"
                                            )
                                            assert(
                                                (box.y - jsY).abs <= 1.0,
                                                s"Expected y ~$jsY but got ${box.y}"
                                            )
                                            assert(
                                                (box.width - jsW).abs <= 1.0,
                                                s"Expected width ~$jsW but got ${box.width}"
                                            )
                                            assert(
                                                (box.height - jsH).abs <= 1.0,
                                                s"Expected height ~$jsH but got ${box.height}"
                                            )
                                        }
                                    }
                                }
                            }
                        case Absent =>
                            fail("expected Present(BoundingBox) for in-viewport element, got Absent")
                    }
                }
            }
        }
    }

    "boundingBox inside a same-origin iframe returns coords in top-level viewport" in run {
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
                                        Browser.boundingBox(Browser.Selector.id("inner")).map {
                                            case Present(box) =>
                                                // Inner offset = iframe.left(100) + inner.left(20) = 120;
                                                // similarly y = iframe.top(50) + inner.top(30) = 80.
                                                assert(
                                                    box.x >= 119.0,
                                                    s"Expected x carrying iframe offset (>=119) but got ${box.x}"
                                                )
                                                assert(
                                                    box.y >= 79.0,
                                                    s"Expected y carrying iframe offset (>=79) but got ${box.y}"
                                                )
                                            case Absent =>
                                                fail("expected Present(BoundingBox) inside iframe, got Absent")
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

    "boundingBox inside a cross-origin iframe returns coords within outer iframe rect" in run {
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
                        Browser.boundingBox(Browser.Selector.testId("frame")).map {
                            case Present(outer) =>
                                Browser.iframe(Browser.Selector.testId("frame")).map { f =>
                                    Browser.withIFrame(f) {
                                        Browser.assertExists(Browser.Selector.id("inner")).andThen {
                                            Browser.boundingBox(Browser.Selector.id("inner")).map {
                                                case Present(inner) =>
                                                    assert(
                                                        inner.x >= outer.x - 1.0 && inner.x <= outer.x + outer.width + 1.0,
                                                        s"Expected inner.x in outer rect [${outer.x}, ${outer.x + outer.width}] but got ${inner.x}"
                                                    )
                                                    assert(
                                                        inner.y >= outer.y - 1.0 && inner.y <= outer.y + outer.height + 1.0,
                                                        s"Expected inner.y in outer rect [${outer.y}, ${outer.y + outer.height}] but got ${inner.y}"
                                                    )
                                                case Absent =>
                                                    fail("expected Present(BoundingBox) for inner element, got Absent")
                                            }
                                        }
                                    }
                                }
                            case Absent =>
                                fail("expected Present(BoundingBox) for outer iframe, got Absent")
                        }
                    }
                }
            }
        }
    }

    "boundingBox is idempotent: two consecutive calls return the same rect" in run {
        withBrowser {
            onPage(
                "<div id='b' style='position:absolute;left:50px;top:30px;width:120px;height:80px;background:red'></div>"
            ) {
                Browser.setViewport(1024, 768).andThen {
                    Browser.boundingBox(Browser.Selector.id("b")).map { first =>
                        Browser.boundingBox(Browser.Selector.id("b")).map { second =>
                            assert(first == second, s"Expected $first == $second")
                        }
                    }
                }
            }
        }
    }

    // ---- accessibilityNodes / role / accessibleName ----

    "accessibilityNodes contains a button node with role and name" in run {
        withBrowser {
            onPage("<button id='b'>Click me</button>") {
                Browser.accessibilityNodes.map { tree =>
                    assert(tree.nonEmpty, s"expected a non-empty AX tree but got $tree")
                    val hit = tree.exists(n => n.role == "button" && n.name == "Click me")
                    assert(hit, s"expected a node with role='button' name='Click me' in roles=${tree.map(n => (n.role, n.name))}")
                }
            }
        }
    }

    "accessibilityNodes includes the document root (WebArea or RootWebArea)" in run {
        withBrowser {
            onPage("<p>x</p>") {
                Browser.accessibilityNodes.map { tree =>
                    val rootRoles = tree.map(_.role).filter(_.endsWith("WebArea"))
                    assert(
                        rootRoles.nonEmpty,
                        s"expected at least one *WebArea root role in the AX tree but got roles=${tree.map(_.role).distinct}"
                    )
                    // Pin the current Chrome value: modern Chrome returns "RootWebArea". The endsWith("WebArea") filter above
                    // accepts both legacy "WebArea" and modern "RootWebArea"; we only assert the pinned root role exists.
                    val pinned = rootRoles.find(r => r == "RootWebArea" || r == "WebArea")
                    assert(
                        pinned.isDefined,
                        s"expected RootWebArea or WebArea in: $rootRoles"
                    )
                }
            }
        }
    }

    // On a simple <h1>+<p> page the AX tree must expose more than just the root.
    // A regression that surfaces ONLY the root and drops all descendants would pass the "root exists" test
    // above but fail this child-shape assertion. We pin role="heading" and role="paragraph" / role="StaticText"
    // (Chrome reports paragraph content via StaticText leaves; either is a valid descendant).
    "accessibilityNodes exposes child descendants of the root (heading + paragraph/text) on a simple <h1>+<p> page" in run {
        withBrowser {
            onPage("<h1 id='h'>Title</h1><p id='p'>Body text</p>") {
                Browser.accessibilityNodes.map { tree =>
                    val rootCount    = tree.count(_.role.endsWith("WebArea"))
                    val nonRootCount = tree.size - rootCount
                    assert(
                        nonRootCount > 0,
                        s"expected at least one non-root AX node (regression would surface root only). roles=${tree.map(_.role)}"
                    )
                    val hasHeading = tree.exists(_.role == "heading")
                    assert(hasHeading, s"expected a heading node in AX tree but got roles=${tree.map(_.role).distinct}")
                    // Chrome surfaces paragraph text as either a "paragraph" node or "StaticText" leaf. Either is a valid descendant.
                    val hasParagraphOrText = tree.exists(n => n.role == "paragraph" || n.role == "StaticText")
                    assert(
                        hasParagraphOrText,
                        s"expected a paragraph or StaticText descendant in AX tree but got roles=${tree.map(_.role).distinct}"
                    )
                }
            }
        }
    }

    // Live AX-tree discriminator routing. Drive a real Chrome against a page with ARIA attributes
    // and pin the contract that AxValue's sealed hierarchy decodes EVERY discriminator Chrome actually emits without
    // throwing UnknownVariantException, and that asString routes each stringifiable variant into properties.
    //
    // What Chrome's Accessibility.getFullAXTree ACTUALLY emits for the test page (empirically captured):
    //   - heading (aria-level=3): properties { level=3, backendDOMNodeId=... }                      (discriminator: integer)
    //   - grid (aria-rowcount=10, aria-colcount=5): properties { readonly=false, multiselectable=false, backendDOMNodeId=... }
    //                                                                                                (discriminator: boolean)
    //     NOTE: aria-rowcount / aria-colcount are NOT surfaced in properties by Chrome; only the standard ARIA state set
    //     (readonly / multiselectable / etc.) appears. The test does NOT assert rowcount/colcount; that would invent facts.
    //   - slider (aria-valuenow=0.5, aria-labelledby=lbl): properties { valuemin=0, valuemax=1, valuetext=, focusable=true,
    //                                                                    invalid=false, settable=true, orientation=horizontal,
    //                                                                    backendDOMNodeId=... }, name=Volume
    //     NOTE: aria-valuenow is also NOT surfaced as `valuenow` in properties; Chrome instead exposes valuemin/valuemax
    //     (number discriminator, integer-valued ⇒ asString collapses 0.0/1.0 → "0"/"1"), valuetext (string), boolean state
    //     flags, and `orientation` as a `token` discriminator. The aria-labelledby relation resolves the slider's name to
    //     "Volume" (the referenced #lbl element's text); the labelledby property itself is dropped from the public map
    //     because Chrome serialises it as discriminator `nodeList` (related-nodes payload), whose asString returns Absent.
    //
    // The KEY contract this test pins: AxValue's Schema decodes every discriminator Chrome emits (integer, number, boolean,
    // string, token, idref/nodeList) without UnknownVariantException; asString routes each stringifiable variant correctly;
    // opaque variants (idref/nodeList) drop from the public properties map while the relation still drives `name`.
    "accessibilityNodes routes each ARIA discriminator (integer / number / boolean / token / idref) correctly" in run {
        withBrowser {
            onPage(
                """<h2 id='lbl'>Volume</h2>
                  |<div id='lvl' role='heading' aria-level='3'>Heading L3</div>
                  |<div id='grid' role='grid' aria-rowcount='10' aria-colcount='5'>g</div>
                  |<input id='slider' type='range' min='0' max='1' step='0.1' value='0.5' aria-valuenow='0.5' aria-labelledby='lbl'>
                  |""".stripMargin
            ) {
                Browser.accessibilityNodes.map { tree =>
                    // Schema decode contract: the very fact that accessibilityNodes returned a non-empty tree proves
                    // AxValue's discriminator-flat Schema decoded every variant Chrome emitted (including the `nodeList`
                    // variant for aria-labelledby) without UnknownVariantException. This pins the AxValue.nodeList fix.
                    assert(tree.nonEmpty, s"expected a non-empty AX tree (Schema decode contract), got $tree")

                    // integer discriminator: aria-level=3 surfaces as property level=3 on the heading node.
                    val heading = tree.find(n => n.role == "heading" && n.properties.get("level").contains("3"))
                    assert(
                        heading.isDefined,
                        s"expected a heading AX node with properties('level')='3' (integer discriminator), got headings=${tree.filter(_.role == "heading").map(
                                n =>
                                    (n.name, n.properties.get("level"))
                            )}"
                    )

                    // boolean discriminator: <div role='grid'> surfaces standard grid state flags. Chrome does NOT emit
                    // aria-rowcount / aria-colcount as properties (verified empirically), so this assertion pins what
                    // Chrome ACTUALLY exposes: readonly=false (boolean discriminator, asString → "false").
                    val grid = tree.find(n => n.role == "grid" && n.properties.get("readonly").contains("false"))
                    assert(
                        grid.isDefined,
                        s"expected a grid AX node with properties('readonly')='false' (boolean discriminator), got grids=${tree.filter(_.role == "grid").map(
                                n =>
                                    (n.name, n.properties.get("readonly"), n.properties.get("multiselectable"))
                            )}"
                    )

                    val slider = tree.find(_.role == "slider")
                    assert(slider.isDefined, s"expected a slider AX node, got roles=${tree.map(_.role)}")
                    val sliderNode = slider.get

                    // number discriminator: <input type='range' min='0' max='1'> surfaces valuemin/valuemax as number-typed
                    // AxValues. asString collapses integer-valued doubles to integer strings, so 0.0/1.0 → "0"/"1".
                    // Chrome does NOT emit aria-valuenow as a `valuenow` property (verified empirically); valuemin/valuemax
                    // pin the number discriminator instead.
                    assert(
                        sliderNode.properties.get("valuemax").contains("1"),
                        s"expected slider properties('valuemax')='1' (number discriminator, integer-valued), got ${sliderNode.properties.get("valuemax")}"
                    )
                    assert(
                        sliderNode.properties.get("valuemin").contains("0"),
                        s"expected slider properties('valuemin')='0' (number discriminator, integer-valued), got ${sliderNode.properties.get("valuemin")}"
                    )

                    // boolean discriminator on the slider: focusable=true.
                    assert(
                        sliderNode.properties.get("focusable").contains("true"),
                        s"expected slider properties('focusable')='true' (boolean discriminator), got ${sliderNode.properties.get("focusable")}"
                    )

                    // token discriminator: orientation=horizontal on the slider.
                    assert(
                        sliderNode.properties.get("orientation").contains("horizontal"),
                        s"expected slider properties('orientation')='horizontal' (token discriminator), got ${sliderNode.properties.get("orientation")}"
                    )

                    // idref / nodeList discriminator routing: aria-labelledby='lbl' resolves the slider's accessible name
                    // to the referenced #lbl element's text ("Volume"). Chrome serialises the relation with discriminator
                    // `nodeList`; asString returns Absent so the labelledby property itself is dropped from the public
                    // properties map, but the computed `name` field pins the relation end-to-end.
                    assert(
                        sliderNode.name == "Volume",
                        s"expected slider name='Volume' (computed via aria-labelledby through nodeList/idref discriminator), got name='${sliderNode.name}'"
                    )
                    assert(
                        sliderNode.properties.get("labelledby") == Absent,
                        s"expected labelledby property DROPPED from public properties map (nodeList/idref asString = Absent), got ${sliderNode.properties.get("labelledby")}"
                    )
                }
            }
        }
    }

    "role(selector) returns Present(\"button\") for a real <button>" in run {
        withBrowser {
            onPage("<button id='b'>Save</button>") {
                Browser.role(Browser.Selector.id("b")).map { r =>
                    assert(r == Present("button"), s"expected Present(\"button\") but got $r")
                }
            }
        }
    }

    "role respects ARIA role attribute over the tag name" in run {
        withBrowser {
            onPage("<div id='d' role='alert'>!</div>") {
                Browser.role(Browser.Selector.id("d")).map { r =>
                    assert(r == Present("alert"), s"expected Present(\"alert\") but got $r")
                }
            }
        }
    }

    "role on a nonexistent selector returns Absent" in run {
        withBrowser {
            onPage("<p>x</p>") {
                Browser.role(Browser.Selector.id("missing")).map { r =>
                    assert(r == Absent, s"expected Absent for missing selector but got $r")
                }
            }
        }
    }

    "accessibleName honours aria-label priority over textContent" in run {
        withBrowser {
            onPage("<button id='b' aria-label='Save'>Discard</button>") {
                Browser.accessibleName(Browser.Selector.id("b")).map { n =>
                    assert(n == Present("Save"), s"expected Present(\"Save\") (aria-label wins) but got $n")
                }
            }
        }
    }

    "accessibleName falls back to textContent when no aria-label is present" in run {
        withBrowser {
            onPage("<button id='b'>Submit</button>") {
                Browser.accessibleName(Browser.Selector.id("b")).map { n =>
                    assert(n == Present("Submit"), s"expected Present(\"Submit\") but got $n")
                }
            }
        }
    }

    "accessibleName for a decorative image is Absent or empty string" in run {
        withBrowser {
            onPage("<img id='i' role='presentation' alt=''>") {
                Browser.accessibleName(Browser.Selector.id("i")).map { n =>
                    // Pin Chrome's current behaviour: Chrome reports an empty accessible name for decorative images. The
                    // contract is "absent semantics": either Absent (selector miss because the AX layer ignores the node)
                    // or Present("") (the AX layer returns an empty name). Either form passes; mismatched non-empty names fail.
                    val ok = (n == Absent) || (n == Present(""))
                    assert(ok, s"expected Absent or Present(\"\") for decorative <img> but got $n")
                }
            }
        }
    }

    "accessibilityNodes inside withIFrame returns the iframe's nodes (same-origin)" in run {
        val parent =
            """<body>
                <iframe id="f" data-testid="frame" srcdoc="{srcdoc}"
                        style="width:300px;height:200px;border:0"></iframe>
            </body>"""
        val inner = """<body><button id="inner">Inner</button></body>"""
        withBrowser {
            Browser.goto(srcdocPage(parent, inner)).andThen {
                Browser.withConfig(_.retrySchedule(Schedule.fixed(50.millis).take(40))) {
                    Browser.assertExists(Browser.Selector.testId("frame")).andThen {
                        Browser.iframe(Browser.Selector.testId("frame")).map { f =>
                            Browser.withIFrame(f) {
                                Browser.assertExists(Browser.Selector.id("inner")).andThen {
                                    Browser.accessibilityNodes.map { tree =>
                                        val names = tree.map(_.name).toSeq
                                        assert(
                                            tree.exists(_.name == "Inner"),
                                            s"expected an AX node with name='Inner' from the iframe document but got names=$names"
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

    // ---- url / title ----

    "url returns correct value after goto" in run {
        withBrowser {
            onPage("<h1>URL%20Test</h1>") {
                Browser.url.map { u =>
                    assert(u.startsWith("data:text/html"), s"Expected data URL but got '$u'")
                }
            }
        }
    }

    "title returns correct value after goto" in run {
        withBrowser {
            onPage("<html><head><title>MyTitle</title></head><body>content</body></html>") {
                Browser.title.map { t =>
                    assert(t == "MyTitle", s"Expected 'MyTitle' but got '$t'")
                }
            }
        }
    }

    "title returns empty for page without title" in run {
        withBrowser {
            onPage("<body>No title here</body>") {
                Browser.title.map { t =>
                    assert(t.isEmpty, s"Expected empty title but got '$t'")
                }
            }
        }
    }

    "url returns data URL string" in run {
        withBrowser {
            onPage("<h1>DataURL</h1>") {
                Browser.url.map { u =>
                    assert(u.contains("data:"), s"Expected data: URL but got '$u'")
                }
            }
        }
    }

    // ---- screenshot ----

    "screenshot returns non-empty Image" in run {
        withBrowser {
            onPage("<h1>ScreenshotRead</h1>") {
                Browser.screenshot().map { img =>
                    assert(img.binary.size > 0, "Screenshot should be non-empty")
                }
            }
        }
    }

    "screenshot restores the original viewport after a successful capture" in run {
        withBrowser {
            onPage("<html><body><div id='marker'>X</div></body></html>") {
                Browser.eval("window.innerWidth").map { preWidthStr =>
                    Browser.eval("window.innerHeight").map { preHeightStr =>
                        Browser.screenshot(400, 300, Browser.ScreenshotFormat.Png, 90).map { img =>
                            assert(img.binary.size > 0, "Screenshot should be non-empty")
                            Browser.eval("window.innerWidth").map { postWidthStr =>
                                Browser.eval("window.innerHeight").map { postHeightStr =>
                                    assert(
                                        postWidthStr == preWidthStr,
                                        s"viewport width should be restored: pre=$preWidthStr post=$postWidthStr"
                                    )
                                    assert(
                                        postHeightStr == preHeightStr,
                                        s"viewport height should be restored: pre=$preHeightStr post=$postHeightStr"
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    "screenshot restores the original viewport when the capture aborts" in run {
        withBrowser {
            onPage("<html><body>abort-restore-test</body></html>") {
                Browser.use { tab =>
                    Browser.eval("window.innerWidth").map { preWidthStr =>
                        Browser.eval("window.innerHeight").map { preHeightStr =>
                            // Race the screenshot against an immediately-failing fiber. `Async.raceFirst` returns the first
                            // computation to complete (success OR failure), interrupting the rest, so the screenshot is
                            // **always** interrupted before it can finish, regardless of host speed. This exercises the
                            // Scope.ensure cleanup path deterministically: no wall-clock timing assumption.
                            val sentinel = new RuntimeException("deterministic abort sentinel")
                            Abort.run[Throwable] {
                                Async.raceFirst[Throwable, Any, Any](
                                    Browser.runOn(tab)(Browser.screenshot(1234, 567, Browser.ScreenshotFormat.Png, 90)),
                                    Abort.fail[Throwable](sentinel)
                                )
                            }.map { result =>
                                val failureMatched = result match
                                    case Result.Failure(_: RuntimeException) => true
                                    case _                                   => false
                                assert(
                                    failureMatched,
                                    s"raceFirst against an immediate Abort.fail must produce a Result.Failure(_: RuntimeException) but got $result"
                                )
                                // The Scope.ensure cleanup runs on interruption regardless of whether
                                // setDeviceMetricsOverride landed, so the viewport must be restored.
                                Browser.eval("window.innerWidth").map { postWidthStr =>
                                    Browser.eval("window.innerHeight").map { postHeightStr =>
                                        assert(
                                            postWidthStr == preWidthStr,
                                            s"viewport width should be restored after abort: pre=$preWidthStr post=$postWidthStr (result=$result)"
                                        )
                                        assert(
                                            postHeightStr == preHeightStr,
                                            s"viewport height should be restored after abort: pre=$preHeightStr post=$postHeightStr (result=$result)"
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

    // ---- setViewport / resetViewport ----

    "setViewport overrides window.innerWidth and window.innerHeight" in run {
        withBrowser {
            onPage("<html><body>setViewport-test</body></html>") {
                Browser.setViewport(640, 480).andThen(
                    Browser.eval("window.innerWidth").map { widthStr =>
                        Browser.eval("window.innerHeight").map { heightStr =>
                            assert(widthStr == "640", s"expected innerWidth=640 but got $widthStr")
                            assert(heightStr == "480", s"expected innerHeight=480 but got $heightStr")
                        }
                    }
                )
            }
        }
    }

    "setViewport is sticky: a subsequent operation observes the override" in run {
        withBrowser {
            onPage("<html><body>sticky-test</body></html>") {
                Browser.setViewport(800, 600).andThen(
                    // Issue an unrelated CDP round-trip first; the override must survive.
                    Browser.eval("document.title").map { _ =>
                        Browser.eval("window.innerWidth").map { widthStr =>
                            assert(widthStr == "800", s"override did not survive intermediate eval: got $widthStr")
                        }
                    }
                )
            }
        }
    }

    "resetViewport restores the natural viewport after setViewport" in run {
        withBrowser {
            onPage("<html><body>resetViewport-test</body></html>") {
                Browser.eval("window.innerWidth").map { naturalWidthStr =>
                    Browser.setViewport(320, 240).andThen(
                        Browser.eval("window.innerWidth").map { overriddenStr =>
                            assert(overriddenStr == "320", s"override not applied: got $overriddenStr")
                            Browser.resetViewport.andThen(
                                Browser.eval("window.innerWidth").map { restoredStr =>
                                    assert(
                                        restoredStr == naturalWidthStr,
                                        s"resetViewport did not restore: natural=$naturalWidthStr, after=$restoredStr"
                                    )
                                }
                            )
                        }
                    )
                }
            }
        }
    }

    "resetViewport without a prior setViewport is a no-op" in run {
        withBrowser {
            onPage("<html><body>no-op-test</body></html>") {
                Browser.eval("window.innerWidth").map { preStr =>
                    Browser.resetViewport.andThen(
                        Browser.eval("window.innerWidth").map { postStr =>
                            assert(preStr == postStr, s"resetViewport changed natural viewport: pre=$preStr post=$postStr")
                        }
                    )
                }
            }
        }
    }

    "successive setViewport calls: last one wins" in run {
        withBrowser {
            onPage("<html><body>last-wins-test</body></html>") {
                Browser.setViewport(640, 480).andThen(
                    Browser.setViewport(1024, 768).andThen(
                        Browser.eval("window.innerWidth").map { widthStr =>
                            Browser.eval("window.innerHeight").map { heightStr =>
                                assert(widthStr == "1024", s"expected last-set width=1024 but got $widthStr")
                                assert(heightStr == "768", s"expected last-set height=768 but got $heightStr")
                            }
                        }
                    )
                )
            }
        }
    }

    "withViewport applies the override inside the body and clears it on exit" in run {
        withBrowser {
            onPage("<html><body>with-viewport-test</body></html>") {
                Browser.eval("window.innerWidth").map { naturalStr =>
                    Browser.withViewport(640, 480) {
                        Browser.eval("window.innerWidth").map { insideStr =>
                            assert(insideStr == "640", s"override not active in body: got $insideStr")
                        }
                    }.andThen {
                        Browser.eval("window.innerWidth").map { afterStr =>
                            assert(
                                afterStr == naturalStr,
                                s"withViewport did not clear on exit: natural=$naturalStr, after=$afterStr"
                            )
                        }
                    }
                }
            }
        }
    }

    "withViewport clears the override even when body aborts" in run {
        withBrowser {
            onPage("<html><body>with-viewport-abort-test</body></html>") {
                Browser.eval("window.innerWidth").map { naturalStr =>
                    Abort.run[BrowserReadException] {
                        Browser.withViewport(800, 600) {
                            Browser.eval("window.innerWidth").map { insideStr =>
                                assert(insideStr == "800", s"override not active in body: got $insideStr")
                                Abort.fail(BrowserNavigationFailedException("simulated", "test-abort"))
                            }
                        }
                    }.andThen {
                        Browser.eval("window.innerWidth").map { afterStr =>
                            assert(
                                afterStr == naturalStr,
                                s"withViewport did not clear after abort: natural=$naturalStr, after=$afterStr"
                            )
                        }
                    }
                }
            }
        }
    }

    // A colorful gradient page used by the format/quality tests below; colorful content gives lossy formats
    // entropy to compress at noticeably different sizes between low and high quality settings.
    private val gradientPageHtml =
        """<html><body style="margin:0">
        <svg xmlns='http://www.w3.org/2000/svg' width='1280' height='720'>
          <defs><linearGradient id='g' x1='0' y1='0' x2='1' y2='1'>
            <stop offset='0%' stop-color='#ff0000'/>
            <stop offset='33%' stop-color='#00ff00'/>
            <stop offset='66%' stop-color='#0000ff'/>
            <stop offset='100%' stop-color='#ffff00'/>
          </linearGradient></defs>
          <rect width='1280' height='720' fill='url(#g)'/>
        </svg></body></html>"""

    "screenshot in PNG format ignores the quality argument" in run {
        // PNG is lossless; Chrome ignores the quality field entirely. Two captures of the same page at
        // quality 30 and quality 90 must therefore yield identical byte payloads.
        withBrowser {
            onPage(gradientPageHtml) {
                Browser.screenshot(width = 400, height = 300, format = Browser.ScreenshotFormat.Png, quality = 30).map { low =>
                    Browser.screenshot(width = 400, height = 300, format = Browser.ScreenshotFormat.Png, quality = 90).map { high =>
                        assert(low.binary.size > 0, "expected non-empty PNG at quality 30")
                        assert(high.binary.size > 0, "expected non-empty PNG at quality 90")
                        assert(
                            low.binary.size == high.binary.size,
                            s"PNG output must be byte-identical regardless of quality (lossless); low=${low.binary.size} high=${high.binary.size}"
                        )
                    }
                }
            }
        }
    }

    "screenshot in JPEG format produces a smaller payload at quality 30 than at quality 90" in run {
        withBrowser {
            onPage(gradientPageHtml) {
                Browser.screenshot(width = 400, height = 300, format = Browser.ScreenshotFormat.Jpeg, quality = 30).map { low =>
                    Browser.screenshot(width = 400, height = 300, format = Browser.ScreenshotFormat.Jpeg, quality = 90).map { high =>
                        assert(low.binary.size > 0, "expected non-empty JPEG at quality 30")
                        assert(high.binary.size > 0, "expected non-empty JPEG at quality 90")
                        assert(
                            low.binary.size < high.binary.size,
                            s"JPEG @30 must compress smaller than @90; low=${low.binary.size} high=${high.binary.size}"
                        )
                    }
                }
            }
        }
    }

    "screenshot in WEBP format produces a valid image" in run {
        withBrowser {
            onPage(gradientPageHtml) {
                Browser.screenshot(width = 400, height = 300, format = Browser.ScreenshotFormat.Webp, quality = 90).map { img =>
                    val bytes = img.binary
                    assert(bytes.size > 0, "expected non-empty WEBP image")
                    // RIFF header; every WEBP file starts with 'R' 'I' 'F' 'F' (0x52 0x49 0x46 0x46).
                    assert(bytes.size >= 4, s"WEBP must be at least 4 bytes for the RIFF header but got ${bytes.size}")
                    val b0 = bytes(0) & 0xff
                    val b1 = bytes(1) & 0xff
                    val b2 = bytes(2) & 0xff
                    val b3 = bytes(3) & 0xff
                    assert(
                        b0 == 0x52 && b1 == 0x49 && b2 == 0x46 && b3 == 0x46,
                        s"expected RIFF header 52 49 46 46 but got ${"%02x %02x %02x %02x".format(b0, b1, b2, b3)}"
                    )
                }
            }
        }
    }

    "screenshotElement honors the format parameter" in run {
        withBrowser {
            onPage("""<html><body style="margin:0;padding:0;">
                <div id="t" style="width:300px;height:200px;background:linear-gradient(45deg,red,blue);"></div>
            </body></html>""") {
                Browser.screenshotElement(Browser.Selector.css("#t"), format = Browser.ScreenshotFormat.Jpeg).map { img =>
                    val bytes = img.binary
                    assert(bytes.size > 0, "expected non-empty JPEG element screenshot")
                    // JPEG SOI marker; every JPEG file starts with 0xFF 0xD8.
                    assert(bytes.size >= 2, s"JPEG must be at least 2 bytes for the SOI marker but got ${bytes.size}")
                    val b0 = bytes(0) & 0xff
                    val b1 = bytes(1) & 0xff
                    assert(
                        b0 == 0xff && b1 == 0xd8,
                        s"expected JPEG SOI marker FF D8 but got ${"%02x %02x".format(b0, b1)}"
                    )
                }
            }
        }
    }

    "screenshotElement in JPEG mode forwards quality to CDP" in run {
        withBrowser {
            onPage("""<html><body style="margin:0;padding:0;">
                <div id="t" style="width:300px;height:200px;background:linear-gradient(45deg,red,green,blue,yellow);"></div>
            </body></html>""") {
                Browser.screenshotElement(Browser.Selector.css("#t"), format = Browser.ScreenshotFormat.Jpeg, quality = 30).map { low =>
                    Browser.screenshotElement(Browser.Selector.css("#t"), format = Browser.ScreenshotFormat.Jpeg, quality = 90).map {
                        high =>
                            assert(low.binary.size > 0, "expected non-empty JPEG element @ quality 30")
                            assert(high.binary.size > 0, "expected non-empty JPEG element @ quality 90")
                            assert(
                                low.binary.size < high.binary.size,
                                s"JPEG element @30 must compress smaller than @90 - low=${low.binary.size} high=${high.binary.size}"
                            )
                    }
                }
            }
        }
    }

    "nested screenshot calls leave the outer device metrics unchanged" in run {
        withBrowser {
            onPage("<html><body>nested</body></html>") {
                Browser.eval("window.innerWidth").map { preWidthStr =>
                    Browser.eval("window.innerHeight").map { preHeightStr =>
                        Browser.screenshot(640, 480, Browser.ScreenshotFormat.Png, 90).map { outerImg =>
                            assert(outerImg.binary.size > 0, "outer screenshot should be non-empty")
                            Browser.screenshot(320, 240, Browser.ScreenshotFormat.Png, 90).map { innerImg =>
                                assert(innerImg.binary.size > 0, "inner screenshot should be non-empty")
                                Browser.eval("window.innerWidth").map { postWidthStr =>
                                    Browser.eval("window.innerHeight").map { postHeightStr =>
                                        assert(
                                            postWidthStr == preWidthStr,
                                            s"viewport width should match pre-screenshot after nested calls: pre=$preWidthStr post=$postWidthStr"
                                        )
                                        assert(
                                            postHeightStr == preHeightStr,
                                            s"viewport height should match pre-screenshot after nested calls: pre=$preHeightStr post=$postHeightStr"
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

    // ---- eval ----

    "eval arithmetic" in run {
        withBrowser {
            Browser.eval("1 + 1").map { result =>
                assert(result == "2", s"Expected '2' but got '$result'")
            }
        }
    }

    "eval document.title" in run {
        withBrowser {
            onPage("<html><head><title>EvalTitle</title></head><body></body></html>") {
                Browser.eval("document.title").map { result =>
                    assert(result == "EvalTitle", s"Expected 'EvalTitle' but got '$result'")
                }
            }
        }
    }

    "eval syntax error throws BrowserScriptException" in run {
        withBrowser {
            Abort.run[BrowserScriptException] {
                Browser.eval("this is not valid javascript !!!{{{")
            }.map { result =>
                result match
                    case Result.Failure(_: BrowserScriptErrorException) => succeed
                    case other => fail(s"Expected Result.Failure(_: BrowserScriptErrorException) but got $other")
            }
        }
    }

    "eval undefined returns empty string" in run {
        withBrowser {
            Browser.eval("undefined").map { result =>
                assert(result == "", s"Expected empty string but got '$result'")
            }
        }
    }

    "eval returning object returns stringified result" in run {
        withBrowser {
            Browser.eval("JSON.stringify({a: 1})").map { result =>
                assert(result.contains("a"), s"Expected JSON with 'a' but got '$result'")
            }
        }
    }

    // ---- readableContent ----

    "readableContent returns article text" in run {
        withBrowser {
            onPage("<body><nav>Nav</nav><article><p>ArticleContent</p></article></body>") {
                Browser.readableContent.map { content =>
                    assert(content.contains("ArticleContent"), s"Expected 'ArticleContent' in: $content")
                }
            }
        }
    }

    // ---- textAll ----

    "textAll returns text of all matching elements" in run {
        withBrowser {
            onPage("<ul><li class='item'>Apple</li><li class='item'>Banana</li><li class='item'>Cherry</li></ul>") {
                Browser.textAll(Browser.Selector.css("li.item")).map { texts =>
                    assert(texts.size == 3, s"Expected 3 items but got ${texts.size}")
                    assert(texts(0) == "Apple", s"Expected 'Apple' but got '${texts(0)}'")
                    assert(texts(1) == "Banana", s"Expected 'Banana' but got '${texts(1)}'")
                    assert(texts(2) == "Cherry", s"Expected 'Cherry' but got '${texts(2)}'")
                }
            }
        }
    }

    "textAll returns empty Chunk for no matches" in run {
        withBrowser {
            onPage("<div>No items</div>") {
                tight {
                    Browser.textAll(Browser.Selector.css("li.item"))
                }.map { texts =>
                    assert(texts.isEmpty, s"Expected empty but got ${texts.size} items")
                }
            }
        }
    }

    "textAll returns Chunk.empty without retrying when no elements match" in run {
        withBrowser {
            // Configure a deliberately long retry budget; the fast-path must skip it entirely on the empty case.
            Browser.withConfig(_.retrySchedule(Schedule.fixed(500.millis).take(20))) {
                onPage("<div>No items</div>") {
                    timed(Browser.textAll(Browser.Selector.css("li.item"))).map { case (elapsed, texts) =>
                        assert(texts.isEmpty, s"Expected empty but got ${texts.size} items")
                        assert(
                            elapsed < 1.second,
                            s"Expected fast-path return < 1s (no retry wait) but took ${elapsed.toMillis}ms"
                        )
                    }
                }
            }
        }
    }

    // ---- attributeAll ----

    "attributeAll returns all href values" in run {
        withBrowser {
            onPage(
                "<a href='https://a.com'>A</a><a href='https://b.com'>B</a><a href='https://c.com'>C</a>"
            ) {
                Browser.attributeAll(Browser.Selector.css("a"), "href").map { hrefs =>
                    assert(hrefs.size == 3, s"Expected 3 hrefs but got ${hrefs.size}")
                    assert(hrefs(0) == "https://a.com", s"Expected 'https://a.com' but got '${hrefs(0)}'")
                    assert(hrefs(1) == "https://b.com", s"Expected 'https://b.com' but got '${hrefs(1)}'")
                    assert(hrefs(2) == "https://c.com", s"Expected 'https://c.com' but got '${hrefs(2)}'")
                }
            }
        }
    }

    "attributeAll returns Chunk.empty without retrying when no elements match" in run {
        withBrowser {
            Browser.withConfig(_.retrySchedule(Schedule.fixed(500.millis).take(20))) {
                onPage("<div>No links</div>") {
                    timed(Browser.attributeAll(Browser.Selector.css("a"), "href")).map { case (elapsed, hrefs) =>
                        assert(hrefs.isEmpty, s"Expected empty but got ${hrefs.size} hrefs")
                        assert(
                            elapsed < 1.second,
                            s"Expected fast-path return < 1s (no retry wait) but took ${elapsed.toMillis}ms"
                        )
                    }
                }
            }
        }
    }

    // ---- html ----

    "html returns innerHTML" in run {
        withBrowser {
            onPage("<div id='wrapper'><span>inner</span></div>") {
                Browser.html(Browser.Selector.css("#wrapper")).map { h =>
                    assert(h.contains("<span>inner</span>"), s"Expected innerHTML with <span> but got '$h'")
                }
            }
        }
    }

    // ---- outerHtml ----

    "outerHtml returns outerHTML with tag" in run {
        withBrowser {
            onPage("<div id='wrapper'><span>inner</span></div>") {
                Browser.outerHtml(Browser.Selector.css("#wrapper")).map { h =>
                    assert(h.contains("<div id=\"wrapper\">"), s"Expected outerHTML with div tag but got '$h'")
                    assert(h.contains("<span>inner</span>"), s"Expected outerHTML with span but got '$h'")
                }
            }
        }
    }

    // ---- pdf ----

    "pdf returns non-empty byte array" in run {
        withBrowserOnLocalhost {
            Browser.pdf.map { bytes =>
                assert(bytes.size > 0, "Expected non-empty PDF byte array")
                // PDF files start with %PDF
                val header = new String(bytes.take(4).toArray, "ASCII")
                assert(header == "%PDF", s"Expected PDF header but got '$header'")
            }
        }
    }

    "pdf raises BrowserDecodingException on malformed wire base64" in run {
        // The CDP server always returns valid base64 in the wild, but `pdf` must still surface a malformed
        // payload through the typed Abort channel rather than letting the underlying `IllegalArgumentException`
        // escape. We exercise the wire-decoder helper directly with a non-base64 payload tagged
        // `"Page.printToPDF"`; the same `method` the production `pdf` path uses.
        Abort.run[BrowserDecodingException] {
            kyo.internal.CdpBase64Decode.decodeWireBase64("Page.printToPDF", "not_base64_!!!")
        }.map {
            case Result.Failure(err: BrowserDecodingException) =>
                assert(err.method == "Page.printToPDF", s"Expected method='Page.printToPDF' but got '${err.method}'")
            case other => fail(s"Expected Result.Failure(BrowserDecodingException) but got $other")
        }
    }

    "screenshot raises BrowserDecodingException on malformed wire base64" in run {
        // Mirror of the `pdf` wire-decoder check: the screenshot path decodes a Base64 image payload via
        // `decodeScreenshotImage`. A malformed wire response must surface through the typed Abort channel
        // tagged `"Page.captureScreenshot"` rather than escaping as a thrown `IllegalArgumentException`.
        Abort.run[BrowserDecodingException] {
            kyo.internal.CdpBase64Decode.decodeScreenshotImage("Page.captureScreenshot", "not_base64_!!!")
        }.map {
            case Result.Failure(err: BrowserDecodingException) =>
                assert(
                    err.method == "Page.captureScreenshot",
                    s"Expected method='Page.captureScreenshot' but got '${err.method}'"
                )
            case other => fail(s"Expected Result.Failure(BrowserDecodingException) but got $other")
        }
    }

    // ---- evalJson ----

    "evalJson decodes simple case class" in run {
        withBrowser {
            onPage("<html><body>test</body></html>") {
                Browser.evalJson[EvalJsonTestData]("({name: 'Alice', age: 30})").map { result =>
                    assert(result.name == "Alice", s"Expected name 'Alice' but got '${result.name}'")
                    assert(result.age == 30, s"Expected age 30 but got ${result.age}")
                }
            }
        }
    }

    "evalJson syntax error throws BrowserScriptException" in run {
        withBrowser {
            onPage("<html><body>test</body></html>") {
                Abort.run[BrowserScriptException] {
                    Browser.evalJson[EvalJsonTestData]("({invalid syntax !!}")
                }.map { result =>
                    result match
                        case Result.Failure(_: BrowserScriptException) => succeed
                        case other => fail(s"Expected BrowserScriptException for syntax error but got $other")
                }
            }
        }
    }

    "evalJson decode failure aborts with BrowserDecodingException, not BrowserScriptException" in run {
        withBrowser {
            onPage("<html><body>test</body></html>") {
                // Script returns a plain number; cannot be decoded into EvalJsonTestData.
                Abort.run[BrowserDecodingException] {
                    Browser.evalJson[EvalJsonTestData]("42")
                }.map { decodeResult =>
                    decodeResult match
                        case Result.Failure(ex: BrowserDecodingException) =>
                            assert(ex.method == "evalJson", s"Expected method 'evalJson' but got '${ex.method}'")
                        case other =>
                            fail(s"Expected Result.Failure(BrowserDecodingException) but got $other")
                    end match
                }
            }
        }
    }

    // ---- evalBoolean / evalInt / evalLong / evalDouble / evalDiscard ----

    "evalBoolean decodes a boolean expression" in run {
        withBrowser {
            onPage("<html><body>typed-eval</body></html>") {
                Browser.evalBoolean("(1 + 1) === 2").map { v =>
                    assert(v, s"Expected true but got $v")
                }
            }
        }
    }

    "evalInt decodes an integer expression" in run {
        withBrowser {
            onPage("<html><body>typed-eval</body></html>") {
                Browser.evalInt("21 * 2").map { v =>
                    assert(v == 42, s"Expected 42 but got $v")
                }
            }
        }
    }

    "evalLong decodes a long expression" in run {
        withBrowser {
            onPage("<html><body>typed-eval</body></html>") {
                Browser.evalLong("9999999999").map { v =>
                    assert(v == 9999999999L, s"Expected 9999999999 but got $v")
                }
            }
        }
    }

    "evalDouble decodes a double expression" in run {
        withBrowser {
            onPage("<html><body>typed-eval</body></html>") {
                Browser.evalDouble("1.5 + 0.25").map { v =>
                    assert(v == 1.75, s"Expected 1.75 but got $v")
                }
            }
        }
    }

    "evalDiscard runs JS for side effect and ignores the result" in run {
        // Confirms evalDiscard actually executes the script (window.__sentinel becomes 42)
        // and does NOT require the JS to be JSON-encodable.
        withBrowser {
            onPage("<html><body>typed-eval</body></html>") {
                Browser.evalDiscard("window.__sentinel = 42").andThen {
                    Browser.evalInt("window.__sentinel").map { v =>
                        assert(v == 42, s"Expected sentinel=42 but got $v")
                    }
                }
            }
        }
    }

    "evalBoolean type mismatch surfaces as BrowserDecodingException" in run {
        withBrowser {
            onPage("<html><body>typed-eval</body></html>") {
                Abort.run[BrowserDecodingException] {
                    Browser.evalBoolean("'not a bool'")
                }.map {
                    case Result.Failure(ex: BrowserDecodingException) =>
                        assert(ex.method == "evalJson", s"Expected method 'evalJson' but got '${ex.method}'")
                    case other => fail(s"Expected BrowserDecodingException but got $other")
                }
            }
        }
    }

    // ---- screenshotElement ----

    "screenshotElement returns image of specific element" in run {
        withBrowser {
            onPage("""<html><body style="margin:0;padding:0;">
            <div id="target" style="width:100px;height:50px;background:red;">Target</div>
        </body></html>""") {
                Browser.screenshotElement(Browser.Selector.css("#target")).map { img =>
                    assert(img.data.size > 0, "Expected non-empty screenshot image")
                }
            }
        }
    }

    // ---- screenshot no-arg overload ----

    "screenshot(using Frame) captures the viewport with default dims, same as screenshot(1280, 720, 90)" in run {
        val p = page("<html><body><h1>Screenshot Test</h1></body></html>")
        withBrowser {
            Browser.goto(p).map { _ =>
                Browser.screenshot().map { img =>
                    assert(img.binary.size > 0, "Expected non-empty screenshot bytes")
                    val bytes = img.binary.toArray
                    assert(bytes.length >= 24, "Expected at least 24 bytes for PNG header + IHDR chunk")
                    assert(bytes(0) == 0x89.toByte, "Expected PNG signature byte 0")
                    assert(bytes(1) == 'P'.toByte, "Expected PNG signature byte 1")
                    assert(bytes(2) == 'N'.toByte, "Expected PNG signature byte 2")
                    assert(bytes(3) == 'G'.toByte, "Expected PNG signature byte 3")
                    // Width and height are at offsets 16 and 20 in the IHDR chunk (big-endian 4-byte ints)
                    val width =
                        ((bytes(16) & 0xff) << 24) | ((bytes(17) & 0xff) << 16) | ((bytes(18) & 0xff) << 8) | (bytes(19) & 0xff)
                    val height =
                        ((bytes(20) & 0xff) << 24) | ((bytes(21) & 0xff) << 16) | ((bytes(22) & 0xff) << 8) | (bytes(23) & 0xff)
                    assert(width == 1280, s"Expected default screenshot width 1280 but got $width")
                    assert(height == 720, s"Expected default screenshot height 720 but got $height")
                }
            }
        }
    }

    // ---- countNow (point-in-time, no retry) ----

    "countNow returns 0 immediately on an absent selector" in run {
        withBrowser {
            onPage("<div>nothing here</div>") {
                tight {
                    Browser.countNow(Browser.Selector.css("#never"))
                }.map { n =>
                    assert(n == 0, s"expected 0 from countNow on absent selector but got $n")
                }
            }
        }
    }

    "count retries on the active schedule (CDP transients), returns 0 on permanently-absent" in run {
        // count is retried on BrowserMutationException only; locateCount returns 0 for "no match"
        // without raising, so a permanently-absent selector returns 0 on the first probe and does
        // NOT block. The retried form behaves identically to countNow for this case.
        withBrowser {
            onPage("<div>nothing</div>") {
                tight {
                    Browser.count(Browser.Selector.css("#never"))
                }.map { n =>
                    assert(n == 0, s"expected 0 from count on permanently-absent selector but got $n")
                }
            }
        }
    }

    // ---- Boolean point-in-time reads ----

    "value reads el.value property updated via JS, not the value attribute" in run {
        withBrowser {
            onPage(
                "<input id='x' type='text' value='initial'>" +
                    "<script>document.getElementById('x').value = 'runtime-property';</script>"
            ) {
                Browser.value(Browser.Selector.css("#x")).map { v =>
                    assert(v == "runtime-property", s"expected 'runtime-property' but got '$v'")
                    // sanity: attribute reflects the static HTML attribute, not the property
                    Browser.attribute(Browser.Selector.css("#x"), "value").map { attr =>
                        assert(attr == "initial", s"expected attribute 'initial' but got '$attr'")
                    }
                }
            }
        }
    }

    "value on absent selector fails with BrowserElementNotFoundException" in run {
        withBrowser {
            onPage("<div>no inputs</div>") {
                tight {
                    Abort.run[BrowserElementException] {
                        Browser.value(Browser.Selector.css("#never"))
                    }
                }.map {
                    case Result.Failure(_: BrowserElementNotFoundException) => succeed
                    case other => fail(s"expected BrowserElementNotFoundException but got $other")
                }
            }
        }
    }

    "isVisible distinguishes visible / display:none / visibility:hidden / aria-hidden ancestor" in run {
        withBrowser {
            onPage(
                "<div id='vis'>visible</div>" +
                    "<div id='dn' style='display:none'>hidden by display</div>" +
                    "<div id='vh' style='visibility:hidden'>hidden by visibility</div>" +
                    "<div aria-hidden='true'><div id='ah'>aria-hidden ancestor</div></div>"
            ) {
                for
                    vis <- Browser.isVisible(Browser.Selector.css("#vis"))
                    dn  <- Browser.isVisible(Browser.Selector.css("#dn"))
                    vh  <- Browser.isVisible(Browser.Selector.css("#vh"))
                    ah  <- Browser.isVisible(Browser.Selector.css("#ah"))
                yield
                    assert(vis, "expected #vis to be visible")
                    assert(!dn, "expected #dn (display:none) to be not visible")
                    assert(!vh, "expected #vh (visibility:hidden) to be not visible")
                    // aria-hidden is render-visibility-irrelevant; the element renders normally
                    assert(ah, "expected #ah (aria-hidden ancestor) to be render-visible (aria-hidden is accessibility-tree, not render)")
            }
        }
    }

    "isEnabled true on enabled button, false on disabled" in run {
        withBrowser {
            onPage("<button id='a'>A</button><button id='b' disabled>B</button>") {
                for
                    a <- Browser.isEnabled(Browser.Selector.css("#a"))
                    b <- Browser.isEnabled(Browser.Selector.css("#b"))
                yield
                    assert(a, "expected #a (no disabled) to be enabled")
                    assert(!b, "expected #b (disabled) to be not enabled")
            }
        }
    }

    "isChecked true on checked checkbox, false on unchecked" in run {
        withBrowser {
            onPage(
                "<input id='c1' type='checkbox' checked>" +
                    "<input id='c2' type='checkbox'>"
            ) {
                for
                    c1 <- Browser.isChecked(Browser.Selector.css("#c1"))
                    c2 <- Browser.isChecked(Browser.Selector.css("#c2"))
                yield
                    assert(c1, "expected #c1 to be checked")
                    assert(!c2, "expected #c2 to be not checked")
            }
        }
    }

    "isFocused true after Browser.focus, false on a sibling" in run {
        withBrowser {
            onPage("<input id='in1' type='text'><input id='in2' type='text'>") {
                Browser.focus(Browser.Selector.css("#in1")).andThen {
                    for
                        focused1 <- Browser.isFocused(Browser.Selector.css("#in1"))
                        focused2 <- Browser.isFocused(Browser.Selector.css("#in2"))
                    yield
                        assert(focused1, "expected #in1 to be focused after Browser.focus")
                        assert(!focused2, "expected #in2 to be not focused")
                }
            }
        }
    }

    "hasNoVisibleText true on empty/whitespace, false on textContent" in run {
        withBrowser {
            onPage(
                "<div id='e'></div>" +
                    "<div id='w'>   \n\t  </div>" +
                    "<div id='t'>Hello</div>"
            ) {
                for
                    e <- Browser.hasNoVisibleText(Browser.Selector.css("#e"))
                    w <- Browser.hasNoVisibleText(Browser.Selector.css("#w"))
                    t <- Browser.hasNoVisibleText(Browser.Selector.css("#t"))
                yield
                    assert(e, "expected #e (empty) to hasNoVisibleText")
                    assert(w, "expected #w (whitespace-only) to hasNoVisibleText")
                    assert(!t, "expected #t (textContent 'Hello') to NOT hasNoVisibleText")
            }
        }
    }

    "hasEmptyValue true on empty input, false on populated" in run {
        withBrowser {
            onPage(
                "<input id='e' type='text' value=''>" +
                    "<input id='p' type='text' value='x'>" +
                    "<textarea id='ta'></textarea>"
            ) {
                for
                    e  <- Browser.hasEmptyValue(Browser.Selector.css("#e"))
                    p  <- Browser.hasEmptyValue(Browser.Selector.css("#p"))
                    ta <- Browser.hasEmptyValue(Browser.Selector.css("#ta"))
                yield
                    assert(e, "expected empty <input> to hasEmptyValue")
                    assert(!p, "expected populated <input value='x'> to NOT hasEmptyValue")
                    assert(ta, "expected empty <textarea> to hasEmptyValue")
            }
        }
    }

    "exists returns true when present, false when absent (no retry, no exception)" in run {
        withBrowser {
            onPage("<div id='here'>hi</div>") {
                tight {
                    for
                        present <- Browser.exists(Browser.Selector.css("#here"))
                        absent  <- Browser.exists(Browser.Selector.css("#never"))
                    yield
                        assert(present, "expected exists(#here) to be true")
                        assert(!absent, "expected exists(#never) to be false (no exception)")
                }
            }
        }
    }

    "hasAttribute true for set attribute (even with empty value), false for missing" in run {
        withBrowser {
            onPage(
                "<input id='a' data-foo>" +
                    "<input id='b' data-foo=''>" +
                    "<input id='c' data-foo='x'>" +
                    "<input id='d'>"
            ) {
                for
                    a <- Browser.hasAttribute(Browser.Selector.css("#a"), "data-foo")
                    b <- Browser.hasAttribute(Browser.Selector.css("#b"), "data-foo")
                    c <- Browser.hasAttribute(Browser.Selector.css("#c"), "data-foo")
                    d <- Browser.hasAttribute(Browser.Selector.css("#d"), "data-foo")
                yield
                    assert(a, "expected hasAttribute on <input data-foo> (HTML boolean) to be true")
                    assert(b, "expected hasAttribute on <input data-foo=''> to be true")
                    assert(c, "expected hasAttribute on <input data-foo='x'> to be true")
                    assert(!d, "expected hasAttribute on <input> (no data-foo) to be false")
            }
        }
    }

end BrowserReadTest

// Pure case class for evalJson tests; must be at file scope so the JSON derivation is visible.
case class EvalJsonTestData(name: String, age: Int) derives Schema
