package kyo

import kyo.Browser.Selector.find
import kyo.Browser.Selector.or
import kyo.Browser.Selector.visible
import kyo.internal.CdpTypes.NodeRef
import kyo.internal.JsStringUtil
import kyo.internal.Resolver
import scala.language.implicitConversions

class SelectorIntegrationTest extends BrowserTest:

    override def timeout = 90.seconds

    private val formPage = page(
        "<form>" +
            "<button role='button' aria-label='Save'>Save</button>" +
            "<button role='button' aria-label='Cancel'>Cancel</button>" +
            "<input id='name' role='textbox' type='text' />" +
            "<input id='email' role='textbox' type='email' />" +
            "<button type='submit'>Submit</button>" +
            "</form>"
    )

    // ---- Single selectors ----

    "button(Save) finds matching element" in {
        withBrowser {
            Browser.goto(formPage).andThen {
                Browser.count(Browser.Selector.button("Save")).map { n =>
                    assert(n == 1, s"Expected 1 Save button but got $n")
                }
            }
        }
    }

    "button (any) finds all buttons" in {
        // `Selector.button` matches BOTH explicit `role="button"` (the 2 first buttons) and implicit
        // ARIA buttons (the `<button type="submit">Submit</button>`). Union is deduplicated by querySelectorAll, so total
        // is 3 = 2 explicit + 1 <button> element that lacks role="button".
        withBrowser {
            Browser.goto(formPage).andThen {
                Browser.count(Browser.Selector.button).map { n =>
                    assert(n == 3, s"Expected 3 buttons (2 explicit + 1 implicit) but got $n")
                }
            }
        }
    }

    "textbox finds input" in {
        withBrowser {
            Browser.goto(formPage).andThen {
                Browser.count(Browser.Selector.textbox).map { n =>
                    assert(n >= 1, s"Expected at least 1 textbox but got $n")
                }
            }
        }
    }

    "text(Submit) finds elements containing text" in {
        withBrowser {
            Browser.goto(formPage).andThen {
                Browser.count(Browser.Selector.text("Submit")).map { n =>
                    assert(n >= 1, s"Expected at least 1 element with 'Submit' text but got $n")
                }
            }
        }
    }

    "id(name) finds element" in {
        withBrowser {
            Browser.goto(formPage).andThen {
                Browser.assertExists(Browser.Selector.id("name")).map { _ =>
                    ()
                }
            }
        }
    }

    "css(#name) finds same element as id(name)" in {
        withBrowser {
            Browser.goto(formPage).andThen {
                Browser.assertExists(Browser.Selector.css("#name")).map { _ =>
                    ()
                }
            }
        }
    }

    // ---- Composition against real DOM ----

    "button(Save).or(button(OK)) finds Save" in {
        withBrowser {
            Browser.goto(formPage).andThen {
                Browser.assertExists(Browser.Selector.button("Save").or(Browser.Selector.button("OK"))).map { _ =>
                    ()
                }
            }
        }
    }

    "button(Missing).or(button(Save)) falls back to Save" in {
        withBrowser {
            Browser.goto(formPage).andThen {
                Browser.assertExists(Browser.Selector.button("Missing").or(Browser.Selector.button("Save"))).map { _ =>
                    ()
                }
            }
        }
    }

    "button(Missing).or(button(AlsoMissing)) fails" in {
        withBrowser {
            Browser.goto(formPage).andThen {
                tight {
                    Abort.run[BrowserElementException] {
                        Browser.assertExists(
                            Browser.Selector.button("Missing").or(Browser.Selector.button("AlsoMissing"))
                        )
                    }
                }.map {
                    case Result.Failure(ex: BrowserElementNotFoundException) => assert(ex.getMessage.contains("Element not found"))
                    case other => fail(s"expected BrowserElementNotFoundException but got $other")
                }
            }
        }
    }

    "css within finds child inside parent" in {
        val dialogPage = page(
            "<div id='login-dialog'>" +
                "<button id='signin-btn'>Sign in</button>" +
                "</div>" +
                "<button id='cancel-btn'>Cancel</button>"
        )
        withBrowser {
            Browser.goto(dialogPage).andThen {
                Browser.assertExists(Browser.Selector.css("#login-dialog").find(Browser.Selector.css("#signin-btn"))).map { _ =>
                    ()
                }
            }
        }
    }

    "css within does not find child outside parent" in {
        val dialogPage = page(
            "<div id='login-dialog'>" +
                "<button id='signin-btn'>Sign in</button>" +
                "</div>" +
                "<button id='cancel-btn'>Cancel</button>"
        )
        withBrowser {
            Browser.goto(dialogPage).andThen {
                tight {
                    Abort.run[BrowserElementException] {
                        Browser.assertExists(
                            Browser.Selector.css("#login-dialog").find(Browser.Selector.css("#cancel-btn"))
                        )
                    }
                }.map {
                    case Result.Failure(ex: BrowserElementNotFoundException) => assert(ex.getMessage.contains("Element not found"))
                    case other => fail(s"expected BrowserElementNotFoundException but got $other")
                }
            }
        }
    }

    // ---- Edge cases ----

    "non-existent selector fails" in {
        withBrowser {
            onPage("<div>Nothing</div>") {
                tight {
                    Abort.run[BrowserElementException] {
                        Browser.assertExists(Browser.Selector.button("NonExistent"))
                    }
                }.map {
                    case Result.Failure(ex: BrowserElementNotFoundException) => assert(ex.getMessage.contains("Element not found"))
                    case other => fail(s"expected BrowserElementNotFoundException but got $other")
                }
            }
        }
    }

    "CSS selector with special chars works" in {
        withBrowser {
            onPage("<div id='my-el' class='foo:bar'>Content</div>") {
                Browser.assertExists(Browser.Selector.css("#my-el")).map { _ =>
                    ()
                }
            }
        }
    }

    // ── Resolution primitives ───────────────────────────────────────────

    // 1. `resolveOne` returns `Absent` for a selector that matches nothing.
    "resolveOne returns Absent for a selector that matches nothing" in {
        withBrowser {
            val html = page("<div id='only'>hi</div>")
            Browser.goto(html).andThen(Resolver.resolveOne(Browser.Selector.id("ghost"))).map { result =>
                assert(result == Absent, s"expected Absent but got $result")
            }
        }
    }

    // 2. `resolveOne` returns `Present(ref)` for a single match.
    "resolveOne returns Present for a single match" in {
        withBrowser {
            val html = page("<button id='go'>Go</button>")
            Browser.goto(html).andThen(Resolver.resolveOne(Browser.Selector.id("go"))).map { result =>
                assert(
                    result match
                        case Present(_) => true
                        case _          => false
                    ,
                    s"expected Present but got $result"
                )
            }
        }
    }

    // 3. `resolveOne` returns the first match in document order for a multi-match.
    "resolveOne returns the first match in document order" in {
        withBrowser {
            val html = page("""
                <p class="p">A</p>
                <p class="p">B</p>
                <p class="p">C</p>
            """)
            Browser.goto(html).andThen {
                Resolver.resolveOne(Browser.Selector.css(".p")).map { first =>
                    Resolver.resolveAll(Browser.Selector.css(".p")).map { all =>
                        val firstRef = first match
                            case Present(r) => r
                            case _          => fail("expected Present")
                        assert(all.nonEmpty, "expected at least one match")
                        assert(all.head == firstRef, s"resolveOne head ($firstRef) != resolveAll.head (${all.head})")
                    }
                }
            }
        }
    }

    // 4. `resolveAll` returns an empty `Chunk` for a selector that matches no DOM node.
    "resolveAll returns an empty Chunk when nothing matches" in {
        withBrowser {
            val html = page("<div id='only'>hi</div>")
            Browser.goto(html).andThen(Resolver.resolveAll(Browser.Selector.css(".missing"))).map { chunk =>
                assert(chunk.isEmpty, s"expected empty chunk but got $chunk")
            }
        }
    }

    // 5. `resolveAll` preserves document order when multiple match.
    "resolveAll preserves document order across matches" in {
        withBrowser {
            val html = page("""
                <p class="m" id="p1">1</p>
                <p class="m" id="p2">2</p>
                <p class="m" id="p3">3</p>
            """)
            Browser.goto(html).andThen {
                Resolver.resolveAll(Browser.Selector.css(".m")).map { all =>
                    assert(all.size == 3, s"expected 3 matches but got ${all.size}")
                    Resolver.resolveOne(Browser.Selector.id("p1")).map { r1 =>
                        Resolver.resolveOne(Browser.Selector.id("p2")).map { r2 =>
                            Resolver.resolveOne(Browser.Selector.id("p3")).map { r3 =>
                                val ref1 = r1.getOrElse(fail("p1 missing"))
                                val ref2 = r2.getOrElse(fail("p2 missing"))
                                val ref3 = r3.getOrElse(fail("p3 missing"))
                                assert(all(0) == ref1, s"order[0]: ${all(0)} != $ref1")
                                assert(all(1) == ref2, s"order[1]: ${all(1)} != $ref2")
                                assert(all(2) == ref3, s"order[2]: ${all(2)} != $ref3")
                            }
                        }
                    }
                }
            }
        }
    }

    // 6. `NodeRef` equality is by backend node id.
    "NodeRef equality is by backend node id" in {
        withBrowser {
            val html = page("<button id='only'>X</button>")
            Browser.goto(html).andThen {
                Resolver.resolveOne(Browser.Selector.id("only")).map { ra =>
                    Resolver.resolveOne(Browser.Selector.css("button")).map { rb =>
                        (ra, rb) match
                            case (Present(a), Present(b)) =>
                                assert(a == b, s"expected equal refs via different selectors but got $a vs $b")
                            case other =>
                                fail(s"expected Present/Present but got $other")
                    }
                }
            }
        }
    }

    // 10. Round-trip: resolveOne then resolveAll returns refs pointing at identical backend ids.
    "resolveOne ref is contained in resolveAll refs" in {
        withBrowser {
            val html = page("""
                <div class="t">one</div>
                <div class="t">two</div>
            """)
            Browser.goto(html).andThen {
                Resolver.resolveOne(Browser.Selector.css(".t")).map {
                    case Present(ref) =>
                        Resolver.resolveAll(Browser.Selector.css(".t")).map { all =>
                            assert(all.contains(ref), s"expected $all to contain $ref")
                        }
                    case Absent =>
                        fail("resolveOne returned Absent for a selector that should match")
                }
            }
        }
    }

    // ── Implicit ARIA role matching ────────────────────────────────

    "Selector.role(button) matches <button> without explicit role attribute" in {
        withBrowser {
            onPage("""<button id="go">Go</button>""") { Browser.count(Browser.Selector.button) }.map { n =>
                assert(n == 1, s"expected 1 implicit-button match but got $n")
            }
        }
    }

    "Selector.role(link) matches <a href=…>" in {
        withBrowser {
            onPage("""<a href="/next">Next</a>""") { Browser.count(Browser.Selector.link) }.map { n =>
                assert(n == 1, s"expected 1 implicit-link match but got $n")
            }
        }
    }

    "Selector.role(textbox) matches <input type=text>" in {
        withBrowser {
            onPage("""<input id="u" type="text">""") { Browser.count(Browser.Selector.textbox) }.map { n =>
                assert(n == 1, s"expected 1 implicit-textbox match but got $n")
            }
        }
    }

    "Selector.role(checkbox) matches <input type=checkbox>" in {
        withBrowser {
            onPage("""<input id="c" type="checkbox">""") { Browser.count(Browser.Selector.checkbox) }.map { n =>
                assert(n == 1, s"expected 1 implicit-checkbox match but got $n")
            }
        }
    }

    "Selector.role(heading) matches <h1>..<h6>" in {
        withBrowser {
            onPage("""
            <h1>A</h1>
            <h2>B</h2>
            <h3>C</h3>
            <h4>D</h4>
            <h5>E</h5>
            <h6>F</h6>
        """) { Browser.count(Browser.Selector.heading) }.map { n =>
                assert(n == 6, s"expected 6 implicit-heading matches but got $n")
            }
        }
    }

    // ── new selector kinds; live DOM ──────────────────────────────

    "Selector.testId resolves by data-testid attribute" in {
        withBrowser {
            onPage("""<button data-testid="login">Login</button>""") { Browser.assertExists(Browser.Selector.testId("login")) }.map(_ =>
                ()
            )
        }
    }

    "Selector.label resolves via <label for=…> linkage" in {
        val p = page("""
            <label for="email-id">Email</label>
            <input id="email-id" type="email">
        """)
        withBrowser {
            Browser.goto(p)
                .andThen(Browser.fill(Browser.Selector.label("Email"), "user@host"))
                .andThen(Browser.eval("document.getElementById('email-id').value"))
                .map(v => assert(v == "user@host", s"expected 'user@host' but got '$v'"))
        }
    }

    "Selector.label resolves via nested <label>Text<input></label>" in {
        val p = page("""
            <label>Password<input id="pw-id" type="password"></label>
        """)
        withBrowser {
            Browser.goto(p)
                .andThen(Browser.fill(Browser.Selector.label("Password"), "secret"))
                .andThen(Browser.eval("document.getElementById('pw-id').value"))
                .map(v => assert(v == "secret", s"expected 'secret' but got '$v'"))
        }
    }

    "Selector.placeholder resolves <input placeholder=…>" in {
        val p = page("""<input id="q" placeholder="Search">""")
        withBrowser {
            Browser.goto(p)
                .andThen(Browser.fill(Browser.Selector.placeholder("Search"), "hello"))
                .andThen(Browser.eval("document.getElementById('q').value"))
                .map(v => assert(v == "hello", s"expected 'hello' but got '$v'"))
        }
    }

    "Selector.title resolves [title=…]" in {
        withBrowser {
            onPage("""<a id="h" href="#" title="Home">link</a>""") { Browser.assertExists(Browser.Selector.title("Home")) }.map(_ =>
                ()
            )
        }
    }

    // `Browser.count(a or b or c)` (a `FirstOf`) must short-circuit on the first non-empty alternative. The page below has
    // only the first selector's element present; if the short-circuit broke, the second alternative would hit a non-existent
    // element. A working short-circuit yields exactly the count from the first match.
    "Selector or short-circuits on first matching alternative" in {
        withBrowser {
            onPage("""<button id="primary">P</button>""") {
                Browser.count(
                    Browser.Selector.css("#primary").or(Browser.Selector.css("#fallback")).or(Browser.Selector.css("#tertiary"))
                ).map { n =>
                    assert(n == 1, s"expected 1 (short-circuited on #primary) but got $n")
                }
            }
        }
    }

    // `implicitRoleCss` must be memoised so repeated role-based selector resolution returns the same CSS string.
    // Resolving the same role on a delayed-attach page exercises the memoised lookup table across retry ticks. The page
    // attaches the button element after a 200 ms delay, so the assertion goes through several retry cycles before
    // succeeding. Functional success implies the memoised CSS string is correct (a cache that returned a stale or
    // wrong CSS union would fail to find the element).
    "Selector.role(button) resolution is stable across retry ticks (memoisation)" in {
        val html = """<div id="container"></div>""" +
            """<script>setTimeout(function(){var b=document.createElement('button');""" +
            """b.id='delayed';b.textContent='Click';document.getElementById('container').appendChild(b)},200)</script>"""
        withBrowser {
            onPage(html) {
                // assertExists retries until it sees the implicit-role button
                Browser.assertExists(Browser.Selector.button).andThen {
                    Browser.count(Browser.Selector.button).map { n =>
                        assert(n == 1, s"expected 1 implicit-button but got $n")
                    }
                }
            }
        }
    }

    // ── ARIA roles; native + explicit [role=...] ────────────

    // combobox: native <select> (non-multiple) has implicit combobox role;
    // [role="combobox"] is the explicit ARIA form.
    "Selector.combobox matches native <select> AND [role='combobox']" in {
        withBrowser {
            onPage("""
            <select id="native"><option>x</option></select>
            <div id="aria" role="combobox" aria-expanded="false">y</div>
        """) { Browser.count(Browser.Selector.combobox) }.map { n =>
                assert(n == 2, s"expected 2 combobox matches (native <select> + explicit [role='combobox']) but got $n")
            }
        }
    }

    // listbox: <select multiple> does NOT have an implicit listbox mapping in
    // implicitRoleMappings; only [role="listbox"] is resolved. We put BOTH
    // on the page to verify ONLY the explicit ARIA form is counted (count == 1).
    "Selector.listbox matches [role='listbox'] (no implicit mapping for <select multiple>)" in {
        withBrowser {
            onPage("""
            <select id="multi" multiple><option>a</option><option>b</option></select>
            <ul id="aria" role="listbox"><li role="option">item</li></ul>
        """) { Browser.count(Browser.Selector.listbox) }.map { n =>
                assert(n == 1, s"expected 1 listbox match (only explicit [role='listbox']) but got $n")
            }
        }
    }

    // radio: native <input type="radio"> has implicit radio role AND [role="radio"]
    "Selector.radio matches native <input type='radio'> AND [role='radio']" in {
        withBrowser {
            onPage("""
            <input id="native" type="radio" name="g">
            <div id="aria" role="radio" aria-checked="false">custom</div>
        """) { Browser.count(Browser.Selector.radio) }.map { n =>
                assert(n == 2, s"expected 2 radio matches (native <input type='radio'> + explicit [role='radio']) but got $n")
            }
        }
    }

    // dialog: <dialog> does NOT have an implicit dialog mapping; only [role="dialog"]
    // is resolved. We put BOTH on the page to verify ONLY the explicit ARIA form is counted (count == 1).
    "Selector.dialog matches [role='dialog'] (no implicit mapping for <dialog> element)" in {
        withBrowser {
            onPage("""
            <dialog id="native" open>native dialog</dialog>
            <div id="aria" role="dialog" aria-label="modal">ARIA dialog</div>
        """) { Browser.count(Browser.Selector.dialog) }.map { n =>
                assert(n == 1, s"expected 1 dialog match (only explicit [role='dialog']) but got $n")
            }
        }
    }

    // tab: no native HTML element has an implicit tab role; only [role="tab"] is resolved
    "Selector.tab matches [role='tab'] (ARIA-only role, no native HTML mapping)" in {
        withBrowser {
            onPage("""
            <div role="tablist">
                <button id="tab1" role="tab" aria-selected="true">Tab 1</button>
                <button id="tab2" role="tab" aria-selected="false">Tab 2</button>
            </div>
        """) { Browser.count(Browser.Selector.tab) }.map { n =>
                assert(n == 2, s"expected 2 tab matches (both [role='tab'] elements) but got $n")
            }
        }
    }

    // menuitem: no native HTML element has an implicit menuitem role; only [role="menuitem"]
    "Selector.menuitem matches [role='menuitem'] (ARIA-only role, no native HTML mapping)" in {
        withBrowser {
            onPage("""
            <ul role="menu">
                <li id="item1" role="menuitem">File</li>
                <li id="item2" role="menuitem">Edit</li>
                <li id="item3" role="menuitem">View</li>
            </ul>
        """) { Browser.count(Browser.Selector.menuitem) }.map { n =>
                assert(n == 3, s"expected 3 menuitem matches (all [role='menuitem'] elements) but got $n")
            }
        }
    }

    // form: <form> does NOT have an implicit form role mapping; only [role="form"] is resolved.
    // We put BOTH on the page to verify ONLY the explicit ARIA form is counted (count == 1).
    "Selector.form matches [role='form'] (no implicit mapping for <form> element)" in {
        withBrowser {
            onPage("""
            <form id="native" action="#"><input type="text"></form>
            <section id="aria" role="form" aria-label="custom form">ARIA form</section>
        """) { Browser.count(Browser.Selector.form) }.map { n =>
                assert(n == 1, s"expected 1 form match (only explicit [role='form']) but got $n")
            }
        }
    }

    // img: native <img> has implicit img role AND SVG [role="img"] is the explicit ARIA form
    "Selector.img matches native <img> AND [role='img'] (e.g. SVG with role)" in {
        withBrowser {
            onPage("""
            <img id="native" src="data:image/gif;base64,R0lGODlhAQABAAAAACH5BAEKAAEALAAAAAABAAEAAAICTAEAOw==" alt="dot">
            <svg id="aria" role="img" aria-label="chart"><circle cx="10" cy="10" r="5"/></svg>
        """) { Browser.count(Browser.Selector.img) }.map { n =>
                assert(n == 2, s"expected 2 img matches (native <img> + explicit [role='img'] SVG) but got $n")
            }
        }
    }

    // ── Selector regressions ──

    // Text selector should click the correct element.
    // selectorToCss maps Text to "*" which matches <html>, so click dispatches
    // at <html>'s center instead of the button containing "Submit".
    "click with Selector.text finds the right element" in {
        withBrowser {
            val html = page("""
                <div>
                    <button id="wrong">Wrong</button>
                    <button id="target" onclick="document.getElementById('result').textContent='clicked'">Submit</button>
                    <div id="result"></div>
                </div>
            """)
            Browser.goto(html)
                .andThen(Browser.click(Browser.Selector.text("Submit")))
                .andThen(Browser.text(Browser.Selector.id("result")))
                .map(t => assert(t == "clicked", s"Expected 'clicked' but got '$t' - Text selector clicked wrong element"))
        }
    }

    // ARIA selector with textContent (no aria-label) should click correctly.
    // locateCount uses JS fallback: el.getAttribute('aria-label') || el.textContent.trim()
    // but selectorToCss generates [role='button'][aria-label='Login'] which requires aria-label attribute.
    // So assertExists finds the button via textContent, but click fails because CSS selector doesn't match.
    "click with Selector.button matches textContent" in {
        withBrowser {
            val html = page("""
                <div>
                    <button role="button" onclick="document.getElementById('result').textContent='clicked'">Login</button>
                    <div id="result"></div>
                </div>
            """)
            Browser.goto(html)
                .andThen(Browser.click(Browser.Selector.button("Login")))
                .andThen(Browser.text(Browser.Selector.id("result")))
                .map(t => assert(t == "clicked", s"Expected 'clicked' but got '$t' - ARIA selector didn't match textContent"))
        }
    }

    // Text selector fill variant.
    // focusElement uses selectorToCss which returns "*" for Text, focusing <html> instead of the input.
    "fill with Selector.text targets correct input" in {
        withBrowser {
            val html = page("""
                <div>
                    <label>Email</label>
                    <input id="email" type="text" placeholder="Enter email">
                </div>
            """)
            Browser.goto(html)
                .andThen(Browser.fill(Browser.Selector.text("Enter email"), "test@example.com"))
                .andThen(Browser.eval("document.getElementById('email').value"))
                .map(v => assert(v == "test@example.com", s"Expected 'test@example.com' but got '$v'"))
        }
    }

    // assertExists works but click fails for ARIA button with textContent only.
    // assertExists -> locateCount (JS with textContent fallback) -> succeeds
    // click -> elementCenter -> selectorToCss -> [role='button'][aria-label='Save'] -> not found
    "ARIA button found by assertExists should also be clickable" in {
        withBrowser {
            val html = page("""
                <button role="button" onclick="this.textContent='done'">Save</button>
            """)
            Browser.goto(html)
                .andThen(Browser.assertExists(Browser.Selector.button("Save")))
                .andThen(Browser.click(Browser.Selector.button("Save")))
                .andThen(Browser.waitForText(Browser.Selector.button("done"), _ == "done"))
                .andThen {
                    Browser.text(Browser.Selector.button("done")).map { t =>
                        assert(t == "done", s"expected button text 'done' after click but got '$t'")
                    }
                }
        }
    }

    // ── Single-quoted attribute value inside a CSS selector ───────
    //
    // `Selector.css("input[name='search']")` was emitted as
    // `document.querySelector('input[name='search']')`; invalid JS. Chrome threw on it, `evalJs` silently ate the error and returned "",
    // and every downstream op (click/fill/focus/text/count) became a quiet no-op. This was the demo-tour failure that made the bug visible.

    "click works on CSS selector with single-quoted attribute value" in {
        withBrowser {
            val html = page("""
                <form>
                  <input name="search" type="text">
                  <button onclick="document.getElementById('out').textContent='clicked'">Go</button>
                  <div id="out"></div>
                </form>
            """)
            Browser.goto(html)
                .andThen(Browser.click(Browser.Selector.css("button")))
                .andThen(Browser.text(Browser.Selector.css("[id='out']")))
                .map(t => assert(t == "clicked", s"expected 'clicked' but got '$t'"))
        }
    }

    "fill works on CSS selector with single-quoted attribute value" in {
        withBrowser {
            val html = page("""<input name="search" type="text">""")
            Browser.goto(html)
                .andThen(Browser.fill(Browser.Selector.css("input[name='search']"), "kyo"))
                .andThen(Browser.eval("document.querySelector('input').value"))
                .map(v => assert(v == "kyo", s"expected 'kyo' but got '$v'"))
        }
    }

    "focus works on CSS selector with single-quoted attribute value" in {
        withBrowser {
            val html = page("""<input id="a" name="search" type="text">""")
            Browser.goto(html)
                .andThen(Browser.focus(Browser.Selector.css("input[name='search']")))
                .andThen(Browser.eval("document.activeElement.id"))
                .map(id => assert(id == "a", s"expected focused id='a' but got '$id'"))
        }
    }

    "text works on CSS selector with single-quoted attribute value" in {
        withBrowser {
            val html = page("""<p data-role="lead">Hello</p>""")
            Browser.goto(html)
                .andThen(Browser.text(Browser.Selector.css("p[data-role='lead']")))
                .map(t => assert(t == "Hello", s"expected 'Hello' but got '$t'"))
        }
    }

    "count works on CSS selector with single-quoted attribute value" in {
        withBrowser {
            val html = page("""<input name="q"><input name="q"><input name="r">""")
            Browser.goto(html)
                .andThen(Browser.count(Browser.Selector.css("input[name='q']")))
                .map(n => assert(n == 2, s"expected 2 matches but got $n"))
        }
    }

    "textAll works on CSS selector with single-quoted attribute value" in {
        withBrowser {
            val html = page("""<span data-k="a">one</span><span data-k="a">two</span><span data-k="b">three</span>""")
            Browser.goto(html)
                .andThen(Browser.textAll(Browser.Selector.css("span[data-k='a']")))
                .map(ts => assert(ts.toList == List("one", "two"), s"expected List(one, two) but got $ts"))
        }
    }

    "attributeAll works on CSS selector with single-quoted attribute value" in {
        withBrowser {
            val html = page("""<a data-g="x" href="/1">a</a><a data-g="x" href="/2">b</a>""")
            Browser.goto(html)
                .andThen(Browser.attributeAll(Browser.Selector.css("a[data-g='x']"), "href"))
                .map(hrefs => assert(hrefs.toList == List("/1", "/2"), s"expected List(/1,/2) but got $hrefs"))
        }
    }

    "assertExists works on CSS selector with single-quoted attribute value" in {
        withBrowser {
            val html = page("""<input name="search">""")
            Browser.goto(html)
                .andThen(Browser.assertExists(Browser.Selector.css("input[name='search']")))
                .andThen {
                    Browser.count(Browser.Selector.css("input[name='search']")).map { n =>
                        assert(n == 1, s"expected exactly 1 matching input after assertExists but got $n")
                    }
                }
        }
    }

    "CSS selector with backslash-escaped colon (Tailwind-style class)" in {
        withBrowser {
            // `.hover:bg-red` needs `.hover\:bg-red` in CSS because `:` is a pseudo-class separator.
            val html = page("""<div class="hover:bg-red">X</div>""")
            Browser.goto(html)
                .andThen(Browser.text(Browser.Selector.css(".hover\\:bg-red")))
                .map(t => assert(t == "X", s"expected 'X' but got '$t'"))
        }
    }

    "ARIA button name containing apostrophe is clickable" in {
        // `Selector.button(name)` matches `[role="button"]` attribute (explicit role), so the element needs `role="button"`.
        withBrowser {
            val html = page("""<button role="button" onclick="this.textContent='done'">don't submit</button>""")
            Browser.goto(html)
                .andThen(Browser.click(Browser.Selector.button("don't submit")))
                .andThen(Browser.waitForText(Browser.Selector.button("done"), _ == "done"))
                .andThen {
                    Browser.text(Browser.Selector.button("done")).map { t =>
                        assert(t == "done", s"expected button text 'done' after click on apostrophe-name selector but got '$t'")
                    }
                }
        }
    }

    "ARIA link name containing apostrophe is clickable" in {
        withBrowser {
            val html = page("""
                <a role="link" href="#target" onclick="document.getElementById('out').textContent='hit'">O'Reilly</a>
                <div id="out"></div>
            """)
            Browser.goto(html)
                .andThen(Browser.click(Browser.Selector.link("O'Reilly")))
                .andThen(Browser.text(Browser.Selector.id("out")))
                .map(t => assert(t == "hit", s"expected 'hit' but got '$t'"))
        }
    }

    "count on ARIA selector whose name contains an apostrophe" in {
        withBrowser {
            val html = page("""
                <button role="button">don't submit</button>
                <button role="button">don't submit</button>
                <button role="button">ok</button>
            """)
            Browser.goto(html)
                .andThen(Browser.count(Browser.Selector.button("don't submit")))
                .map(n => assert(n == 2, s"expected 2 matches but got $n"))
        }
    }

    "Text selector containing apostrophe matches correctly" in {
        withBrowser {
            val html = page("""<div id="target" onclick="this.textContent='hit'">can't find</div>""")
            Browser.goto(html)
                .andThen(Browser.click(Browser.Selector.text("can't find")))
                .andThen(Browser.text(Browser.Selector.id("target")))
                .map(t => assert(t == "hit", s"expected 'hit' but got '$t'"))
        }
    }

    "Text selector containing double quotes matches correctly" in {
        withBrowser {
            val html = page("""<div id="target" onclick="this.textContent='hit'">He said "hi"</div>""")
            Browser.goto(html)
                .andThen(Browser.click(Browser.Selector.text("He said \"hi\"")))
                .andThen(Browser.text(Browser.Selector.id("target")))
                .map(t => assert(t == "hit", s"expected 'hit' but got '$t'"))
        }
    }

    "Selector.id containing apostrophe resolves correctly" in {
        // Server-side interpolation can produce such ids; they should round-trip.
        withBrowser {
            val html = page("""<div id="foo'bar">hello</div>""")
            Browser.goto(html)
                .andThen(Browser.text(Browser.Selector.id("foo'bar")))
                .map(t => assert(t == "hello", s"expected 'hello' but got '$t'"))
        }
    }

    "Within with child CSS containing a single-quoted attribute is scoped to the parent" in {
        withBrowser {
            val html = page("""
                <div id="p"><input name="q" value="inner"></div>
                <input name="q" value="outer">
            """)
            Browser.goto(html)
                .andThen(Browser.attribute(Browser.Selector.id("p").find(Browser.Selector.css("input[name='q']")), "value"))
                .map(v => assert(v == "inner", s"expected 'inner' but got '$v'"))
        }
    }

    "Within count is scoped correctly with single-quoted child CSS" in {
        withBrowser {
            val html = page("""
                <div id="p"><input name="q"><input name="q"></div>
                <input name="q">
            """)
            Browser.goto(html)
                .andThen(Browser.count(Browser.Selector.id("p").find(Browser.Selector.css("input[name='q']"))))
                .map(n => assert(n == 2, s"expected 2 matches inside #p but got $n"))
        }
    }

    // ── Malformed selectors must fail loudly, not silently no-op ────────────────────────────────
    //
    // Before the eval-decode fix, any JS thrown inside a Runtime.evaluate call was silently swallowed and returned "".
    // That is exactly what made the single-quote escaping bug invisible; malformed selectors quietly no-op'd every downstream call. These
    // tests assert that a malformed selector now fails loudly as a typed BrowserProtocolErrorException whose message mentions the offending
    // selector substring; never as a quiet Success with a default value (0 / empty / "").

    "malformed CSS selector in Browser.count fails loudly, not silent 0" in {
        withBrowser {
            onPage("<body><input></body>") { Abort.run[Throwable](Browser.count(Browser.Selector.css(">>>>invalid<<<<"))) }
                .map(result =>
                    result match
                        case Result.Failure(ex: BrowserProtocolErrorException) =>
                            assert(
                                ex.getMessage.contains(">>>>invalid<<<<"),
                                s"expected BrowserProtocolErrorException message to mention the malformed selector but got: ${ex.getMessage}"
                            )
                        case other => fail(s"expected BrowserProtocolErrorException for malformed selector but got $other")
                )
        }
    }

    "malformed CSS selector in Browser.text fails loudly" in {
        withBrowser {
            onPage("<body><p>hi</p></body>") { Abort.run[Throwable](Browser.text(Browser.Selector.css(">>>>invalid<<<<"))) }
                .map(result =>
                    result match
                        case Result.Failure(ex: BrowserProtocolErrorException) =>
                            assert(
                                ex.getMessage.contains(">>>>invalid<<<<"),
                                s"expected BrowserProtocolErrorException message to mention the malformed selector but got: ${ex.getMessage}"
                            )
                        case other => fail(s"expected BrowserProtocolErrorException for malformed selector but got $other")
                )
        }
    }

    "malformed CSS selector in Browser.click fails loudly" in {
        withBrowser {
            onPage("<body><button>X</button></body>") { Abort.run[Throwable](Browser.click(Browser.Selector.css(">>>>invalid<<<<"))) }
                .map(result =>
                    result match
                        case Result.Failure(ex: BrowserProtocolErrorException) =>
                            assert(
                                ex.getMessage.contains(">>>>invalid<<<<"),
                                s"expected BrowserProtocolErrorException message to mention the malformed selector but got: ${ex.getMessage}"
                            )
                        case other => fail(s"expected BrowserProtocolErrorException for malformed selector but got $other")
                )
        }
    }

    "malformed CSS selector in Browser.fill fails loudly" in {
        withBrowser {
            onPage("<body><input></body>") { Abort.run[Throwable](Browser.fill(Browser.Selector.css(">>>>invalid<<<<"), "x")) }
                .map(result =>
                    result match
                        case Result.Failure(ex: BrowserProtocolErrorException) =>
                            assert(
                                ex.getMessage.contains(">>>>invalid<<<<"),
                                s"expected BrowserProtocolErrorException message to mention the malformed selector but got: ${ex.getMessage}"
                            )
                        case other => fail(s"expected BrowserProtocolErrorException for malformed selector but got $other")
                )
        }
    }

    "malformed CSS selector in Browser.textAll fails loudly, not empty chunk" in {
        withBrowser {
            onPage("<body><p>hi</p></body>") { Abort.run[Throwable](Browser.textAll(Browser.Selector.css(">>>>invalid<<<<"))) }
                .map(result =>
                    result match
                        case Result.Failure(ex: BrowserProtocolErrorException) =>
                            assert(
                                ex.getMessage.contains(">>>>invalid<<<<"),
                                s"expected BrowserProtocolErrorException message to mention the malformed selector but got: ${ex.getMessage}"
                            )
                        case other => fail(s"expected BrowserProtocolErrorException for malformed selector but got $other")
                )
        }
    }

    "valid selector matching nothing still raises typed ElementNotFound, not library panic" in {
        withBrowser {
            onPage("<body></body>") {
                Browser.withConfig(_.retrySchedule(Schedule.fixed(20.millis).take(3))) {
                    Abort.run[BrowserElementException](
                        Browser.click(Browser.Selector.id("never-exists"))
                    )
                }
            }
                .map(result =>
                    result match
                        case Result.Failure(_: BrowserElementException) =>
                            succeed("clicking a missing element surfaces as a typed BrowserElementException")
                        case other => fail(s"expected typed BrowserElementException but got $other")
                )
        }
    }

    // ── No magic sentinel in error paths ──────────────

    // `Browser.click` on an unresolved selector raises a typed BrowserElementException with a human-readable selector description.
    // Gated interactions treat "unresolved" as a NotAttached actionability failure, so the surfaced exception is
    // BrowserElementNotActionableException rather than BrowserElementNotFoundException. The invariants the test cares about; typed
    // exception, human-readable selector in the message, no `__NOT_FOUND__` sentinel; remain preserved.
    "click on unresolved selector raises a typed BrowserElementException with readable selector description" in {
        withBrowser {
            val html = page("<body></body>")
            Browser.goto(html).andThen {
                Browser.withConfig(_.retrySchedule(Schedule.fixed(20.millis).take(2))) {
                    Abort.run[BrowserElementException] {
                        Browser.click(Browser.Selector.id("nope-nope-nope"))
                    }
                }.map { result =>
                    result match
                        case Result.Failure(ex: BrowserElementException) =>
                            val msg = ex.getMessage
                            assert(msg.contains("nope-nope-nope"), s"expected selector description in message but got: $msg")
                            assert(!msg.contains("__NOT_FOUND__"), s"message must not contain sentinel: $msg")
                        case other =>
                            fail(s"expected Failure(BrowserElementException) but got $other")
                }
            }
        }
    }

    // 8. `assertExists` succeeds when the resolver returns Present.
    "assertExists succeeds when element is present" in {
        withBrowser {
            val html = page("<div id='here'>hi</div>")
            Browser.goto(html).andThen {
                Browser.withConfig(_.retrySchedule(Schedule.fixed(20.millis).take(3))) {
                    Browser.assertExists(Browser.Selector.id("here"))
                }.andThen {
                    Browser.count(Browser.Selector.id("here")).map { n =>
                        assert(n >= 1, s"Expected at least one element with id='here' after assertExists but got $n")
                    }
                }
            }
        }
    }

    // 9. `assertExists` raises ElementNotFound after schedule exhausts, with readable selector reference.
    "assertExists raises ElementNotFound after schedule exhausts" in {
        withBrowser {
            val html = page("<body></body>")
            Browser.goto(html).andThen {
                Browser.withConfig(_.retrySchedule(Schedule.fixed(20.millis).take(2))) {
                    Abort.run[BrowserElementException] {
                        Browser.assertExists(Browser.Selector.id("missing-id-123"))
                    }
                }.map { result =>
                    result match
                        case Result.Failure(ex: BrowserElementNotFoundException) =>
                            val msg = ex.getMessage
                            assert(msg.contains("missing-id-123"), s"expected selector reference in message but got: $msg")
                        case other =>
                            fail(s"expected Failure(BrowserElementNotFoundException) but got $other")
                }
            }
        }
    }

    // ── Text-in-Within targets the correct descendant, not <*> ──
    //
    // Pins that `Within(parent, Text("..."))` resolves through the scoped JS matcher and lands on the
    // child whose text matches, not on the parent's first descendant.

    "Within(parent, text(inner)) targets the parent's inner-text child, not the first descendant" in {
        withBrowser {
            val html = page("""
                <div id="scope">
                    <span>First span</span>
                    <button id="target" onclick="this.textContent='hit'">Click me</button>
                    <span>Last span</span>
                </div>
                <button id="outside">Click me</button>
            """)
            Browser.goto(html)
                .andThen(Browser.click(Browser.Selector.id("scope").find(Browser.Selector.text("Click me"))))
                .andThen(Browser.text(Browser.Selector.id("target")))
                .map(t => assert(t == "hit", s"expected 'hit' but got '$t' - Text-in-Within targeted the wrong element"))
        }
    }

    // ── CSS-escaped id chars ───────────────────────────

    "Selector.id(\"my:id\") emits CSS-escaped ident and resolves" in {
        withBrowser {
            val html = page("""<div id="my:id">hello</div>""")
            Browser.goto(html)
                .andThen(Browser.text(Browser.Selector.id("my:id")))
                .map(t => assert(t == "hello", s"expected 'hello' but got '$t'"))
        }
    }

    "Selector.id(\"id with spaces\") escapes whitespace and resolves" in {
        withBrowser {
            val html = page("""<div id="id with spaces">bingo</div>""")
            Browser.goto(html)
                .andThen(Browser.text(Browser.Selector.id("id with spaces")))
                .map(t => assert(t == "bingo", s"expected 'bingo' but got '$t'"))
        }
    }

    // ── JsStringUtil.escapeJsString golden test ──────────────
    "JsStringUtil.escapeJsString escapes \\t, \\n, \\r, \", ', and \\\\" in {
        val raw     = "tab=\twindows=\r\nquote='back\\slash also \"double\""
        val escaped = JsStringUtil.escapeJsString(raw)
        // Backslash → \\, single quote → \', \n → \n, \r → \r, \t → \t. Double quote is left alone (single-quoted JS literal).
        val expected = "tab=\\twindows=\\r\\nquote=\\'back\\\\slash also \"double\""
        assert(
            escaped == expected,
            s"escapeJsString mismatch - expected `$expected` but got `$escaped`"
        )
    }

    // -------------------------------------------------------------------------
    // ARIA semantic selectors skip hidden elements
    //
    // When a `display:none` input co-exists with a visible control of the same semantic role, `Selector.textbox(...)` must land on
    // the visible one; visibility-filtered semantic selectors mirror the platform's accessibility-tree view.
    // -------------------------------------------------------------------------

    "Selector.textbox skips display:none inputs and matches the visible one" in {
        withBrowser {
            onPage(
                """<input id="hidden" type="search" style="display:none">
                  |<input id="visible" type="search">""".stripMargin
            ) {
                Browser.assertExists(Browser.Selector.textbox).andThen {
                    Browser.eval(
                        """(() => {
                            const _node = document.querySelectorAll('input[type=search]')[0];
                            return _node && _node.id;
                          })()"""
                    ).map { firstId =>
                        // sanity-check the page shape: both inputs are siblings, the hidden one comes first in document order.
                        assert(firstId == "hidden", s"expected document order to be [hidden, visible] but first was '$firstId'")
                    }.andThen {
                        // The semantic-role resolver MUST skip past the hidden element and return the visible one.
                        Browser.fill(Browser.Selector.textbox, "kyo-textbox-filter-works")
                    }.andThen {
                        Browser.eval("document.getElementById('visible').value").map { v =>
                            assert(v == "kyo-textbox-filter-works", s"expected fill to land on the visible input but got '$v'")
                        }
                    }
                }
            }
        }
    }

    "Selector.button skips visibility:hidden and aria-hidden ancestors" in {
        withBrowser {
            onPage(
                """<button id="b-vh" style="visibility:hidden">Go</button>
                  |<div aria-hidden="true"><button id="b-aria">Go</button></div>
                  |<button id="b-vis">Go</button>""".stripMargin
            ) {
                Browser.count(Browser.Selector.button("Go")).map { n =>
                    assert(n == 1, s"expected exactly one visible 'Go' button (b-vis) but counted $n")
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // ARIA selectors: distinguish "no candidate" from "candidates filtered by visibility" (A1)
    //
    // Before fix: both cases raise the same `NotAttached`. A user typing `Selector.textbox("Search")` against a page where
    // the only matching input is hidden gets the same error as if no matching input existed at all; and has to invent
    // their own diagnostic to figure out which case they're in. The two cases need different remediations (the first
    // needs a different selector; the second needs `setViewport` / a click-to-open / etc).
    // -------------------------------------------------------------------------

    "ARIA selector against a page with ZERO role candidates fails with a clear 'no element with role' message" in {
        // No textbox-role elements anywhere on the page.
        withBrowser {
            onPage("<div>just a div</div>") {
                tight {
                    Abort.run[BrowserElementException] {
                        Browser.click(Browser.Selector.textbox("Search"))
                    }.map {
                        case Result.Failure(ex: BrowserElementNotActionableException) =>
                            val msg = ex.getMessage
                            assert(
                                msg.toLowerCase.contains("no element") ||
                                    msg.toLowerCase.contains("0 candidates") ||
                                    msg.toLowerCase.contains("no candidate"),
                                s"expected an explicit 'no element with role' diagnostic for a missing textbox, got: $msg"
                            )
                        case other => fail(s"expected BrowserElementNotActionableException, got $other")
                    }
                }
            }
        }
    }

    "ARIA selector against a page with hidden candidates fails with a clear 'filtered by visibility' message" in {
        // ONE textbox-role candidate, but it's display:none; the visibility filter drops it.
        withBrowser {
            onPage("""<input type="text" aria-label="Search" style="display:none">""") {
                tight {
                    Abort.run[BrowserElementException] {
                        Browser.click(Browser.Selector.textbox("Search"))
                    }.map {
                        case Result.Failure(ex: BrowserElementNotActionableException) =>
                            val msg = ex.getMessage
                            assert(
                                msg.toLowerCase.contains("filtered") ||
                                    msg.toLowerCase.contains("hidden") ||
                                    msg.toLowerCase.contains("visibility"),
                                s"expected a 'filtered by visibility' diagnostic when the textbox-role candidate is display:none, got: $msg"
                            )
                        case other => fail(s"expected BrowserElementNotActionableException, got $other")
                    }
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // `Selector.<x>.visible`: opt-in visibility filter for non-ARIA selectors
    //
    // ARIA semantic selectors carry an implicit visibility filter. For Css/Id/TestId/Label/Placeholder/Title/Text,
    // users sometimes want the same filter; e.g. a responsive page renders both a mobile and desktop copy of an input
    // sharing an id, and only one is visible at any given viewport. The `.visible` combinator opts the wrapped selector
    // into the shared filter without changing default behavior for callers that deliberately target hidden scaffolding.
    // -------------------------------------------------------------------------

    "Selector.id(...).visible skips a hidden first match and resolves the visible one" in {
        withBrowser {
            // Two inputs share the same id (illegal HTML but real on responsive sites that swap layouts via CSS).
            // The first is display:none. Plain Selector.id matches the first by document order; .visible skips it.
            onPage(
                """<input id='dup' style='display:none' value='hidden-copy'/>
                  |<input id='dup' value='visible-copy'/>""".stripMargin
            ) {
                Browser.fill(Browser.Selector.id("dup").visible, "typed-into-visible").map { _ =>
                    // After the visible-filtered fill, the second (visible) input should hold the value.
                    Browser.eval("document.querySelectorAll('input')[1].value").map { v =>
                        assert(v == "typed-into-visible", s"expected visible copy to be filled but got: $v")
                    }
                }
            }
        }
    }

    "Selector.<x>.visible is a no-op when the inner selector already resolves to a visible element" in {
        withBrowser {
            onPage("""<input id='solo' value='start'/>""") {
                Browser.fill(Browser.Selector.id("solo").visible, "new-value").map { _ =>
                    Browser.eval("document.getElementById('solo').value").map { v =>
                        assert(
                            v == "new-value",
                            s"expected the visible-wrapped selector to behave like the inner selector when there is only one visible match, got: $v"
                        )
                    }
                }
            }
        }
    }

    "Selector.<x>.visible without a visible match raises a typed NotActionable" in {
        withBrowser {
            // The id matches but only as a hidden element. .visible filters it out so the selector resolves to no
            // element, which Actionability reports as NotAttached (a selector that matches nothing is the degenerate
            // "not actionable" case; see Actionability.scala), not NotVisible (which requires a matched-but-hidden node).
            onPage("""<input id='only-hidden' style='display:none' value='nope'/>""") {
                Browser.withConfig(_.retrySchedule(Schedule.fixed(50.millis).take(2))) {
                    Abort.run[BrowserElementException] {
                        Browser.click(Browser.Selector.id("only-hidden").visible)
                    }.map {
                        case Result.Failure(ex: BrowserElementNotActionableException) =>
                            assert(ex.reason == BrowserElementNotActionableException.Reason.NotAttached)
                        case other => fail(s"expected NotActionable but got $other")
                    }
                }
            }
        }
    }

    "Selector.css(...).visible composes with .or fallback and lands on the visible alternative" in {
        withBrowser {
            // First branch matches a hidden element; second branch matches a visible one. The `or` would normally pick
            // the first hit; with `.visible` wrapping the union it skips the hidden first and lands on the second.
            onPage(
                """<input class='target' style='display:none' value='hidden'/>
                  |<input id='vis-target' value='visible'/>""".stripMargin
            ) {
                val sel = Browser.Selector.css(".target").or(Browser.Selector.id("vis-target")).visible
                Browser.fill(sel, "composed-value").map { _ =>
                    Browser.eval("document.getElementById('vis-target').value").map { v =>
                        assert(v == "composed-value", s"expected the visible-or alternative to be filled, got: $v")
                    }
                }
            }
        }
    }

    // ── Prefixed-string DSL routing (live DOM) ─────────────────────────
    //
    // A single fixture hosts elements addressable through each of the five recognised prefixes (`text=`, `testid=`, `label=`, `id=`,
    // `css=`). Driving `Browser.assertExists` against each prefix exercises the typed routing end-to-end against a real Chrome session;
    // a miswired prefix would either fail to find the element (wrong typed lookup) or land on the wrong one.

    "prefixed-string DSL routes each prefix to the correct typed selector against live DOM" in {
        val fixture = page("""
            <div>
                <p id="text-target">Sign in</p>
                <button data-testid="login-form-target">login-form-target</button>
                <label for="email-input">Email</label>
                <input id="email-input" type="email">
                <input id="id-target">
                <button class="btn-primary">Primary</button>
            </div>
        """)
        withBrowser {
            Browser.goto(fixture)
                .andThen(Browser.assertExists("text=Sign in"))
                .andThen(Browser.assertExists("testid=login-form-target"))
                .andThen(Browser.assertExists("label=Email"))
                .andThen(Browser.assertExists("id=id-target"))
                .andThen(Browser.assertExists("css=.btn-primary"))
                .andThen {
                    // Verify at least one element matched each prefix by checking count > 0.
                    Browser.count(Browser.Selector.css("#text-target")).map { n =>
                        assert(n >= 1, s"Expected element addressable via text= prefix but count was $n")
                    }
                }
        }
    }

end SelectorIntegrationTest
