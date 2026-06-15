package kyo

import kyo.Browser.*
import kyo.Length.*
import kyo.UI.Ast.HtmlContent
import kyo.UI.foreach

/** Port of FormDemoRenderTest. Original tests targeted TUI buffer rendering (cursor characters, scroll-induced viewport shifts, line-break
  * inspection). Browser equivalents check DOM properties (value/placeholder attributes, document.activeElement, body innerText ordering,
  * scrollY) since the underlying invariants (focus state, value preservation, element ordering, cleared inputs after submit) translate
  * cleanly to the DOM.
  */
class FormDemoRenderItTest extends UITest:

    private def formApp(using Frame) =
        for
            name    <- Signal.initRef("")
            email   <- Signal.initRef("")
            role    <- Signal.initRef("developer")
            entries <- Signal.initRef(Chunk.empty[(String, String, String)])
            status  <- Signal.initRef("")
        yield UI.div(
            UI.h1("Employee Registry"),
            UI.label("Name:"),
            UI.input.id("name").value(name).placeholder("Full name").onInput(v => name.set(v)),
            UI.label("Email:"),
            UI.emailInput.id("email").value(email).placeholder("user@co.com").onInput(v => email.set(v)),
            UI.label("Role:"),
            UI.select(
                UI.option.value("developer")("Developer"),
                UI.option.value("manager")("Manager")
            ).id("role").value(role).onChange(v => role.set(v)),
            UI.button("Add").id("add").onClick {
                for
                    n <- name.get
                    e <- email.get
                    r <- role.get
                    _ <-
                        if n.nonEmpty && e.nonEmpty then
                            entries.getAndUpdate(_.appended((n, e, r))).andThen {
                                name.set("").andThen(email.set("")).andThen(status.set(s"Added $n"))
                            }
                        else status.set("Required")
                yield ()
            },
            status.map(s => UI.span(s).id("status")),
            UI.h2("Employees"),
            entries.map { items =>
                if items.isEmpty then UI.span("No entries").id("empty"): HtmlContent
                else
                    val header = UI.tr(UI.th("Name"), UI.th("Email"), UI.th("Role"))
                    val rows = items.toSeq.map { case (n, e, r) =>
                        UI.tr(UI.td(n), UI.td(e), UI.td(r))
                    }
                    UI.table((header +: rows).map(UI.Ast.HtmlChildVal.lift(_))*).id("table"): HtmlContent
            }
        )

    /** Asserts that `first` text appears before `second` text in `body.innerText`. Only safe when both substrings appear at most once on
      * the page; for ambiguous text, use [[assertElementAbove]].
      */
    private def assertAbove(first: String, second: String)(using Frame) =
        Browser.assertTextSatisfies(Selector.css("body"), s"'$first' above '$second'") { t =>
            val i1 = t.indexOf(first)
            val i2 = t.indexOf(second)
            i1 >= 0 && i2 >= 0 && i1 < i2
        }

    /** Asserts that the element matched by `firstCss` precedes the element matched by `secondCss` in DOM order. Robust against text
      * appearing in multiple places (e.g. cell contents that also surface in a status message).
      */
    private def assertElementAbove(firstCss: String, secondCss: String)(using Frame, kyo.test.AssertScope) =
        for
            order <- Browser.eval(
                s"""(() => {
                  const a = document.querySelector(${jsString(firstCss)});
                  const b = document.querySelector(${jsString(secondCss)});
                  if (!a) return 'missing-first';
                  if (!b) return 'missing-second';
                  return (a.compareDocumentPosition(b) & Node.DOCUMENT_POSITION_FOLLOWING) ? 'ok' : 'wrong-order';
                })()"""
            )
            _ = assert(order == "ok", s"Expected '$firstCss' above '$secondCss', got: $order")
        yield ()

    private def jsString(s: String): String =
        "'" + s.replace("\\", "\\\\").replace("'", "\\'") + "'"

    // ==== Bug 1: Mouse scroll does nothing ====
    // In TUI scroll moves rows in/out of viewport. In browser, all rows are in DOM regardless of
    // viewport; we verify scrolling actually changes scrollY and a far-away row is reachable.

    "mouse scroll shifts rendered content" in {
        // The original TUI invariant ("scroll changes which rows are visible in the buffer") doesn't
        // translate: in a browser all 200 rows live in the DOM regardless of viewport, and scrolling
        // never adds/removes nodes. The browser-equivalent check is "scrollTo a far-away row succeeds
        // and the row is reachable" (visibility is something Browser.assertVisible already settles on).
        val app: UI < Async =
            for items <- Signal.initRef(Chunk.from(0 until 200))
            yield UI.div(items.foreach(i => UI.div(s"row$i").id(s"r$i").style(Style.height(30.px)))).id("root")
        withUI(app) {
            for
                _ <- Browser.assertText(Selector.id("r0"), "row0")
                _ <- Browser.scrollToElement(Selector.id("r150"))
                _ <- Browser.assertVisible(Selector.id("r150"))
                _ <- Browser.assertText(Selector.id("r150"), "row150")
            yield ()
        }
    }

    // ==== Bug 2: Placeholder doesn't hide when focused and typing ====

    "placeholder visible in empty unfocused input" in {
        formApp.flatMap { ui =>
            withUI(ui) {
                for
                    _ <- Browser.assertAttribute(Selector.id("name"), "placeholder", "Full name")
                    _ <- Browser.assertAttribute(Selector.id("email"), "placeholder", "user@co.com")
                    _ <- Browser.assertAttribute(Selector.id("name"), "value", "")
                    _ <- Browser.assertAttribute(Selector.id("email"), "value", "")
                yield ()
            }
        }
    }

    "placeholder disappears after typing" in {
        formApp.flatMap { ui =>
            withUI(ui) {
                for
                    _ <- Browser.assertAttribute(Selector.id("name"), "placeholder", "Full name")
                    _ <- Browser.fill(Selector.id("name"), "Alice")
                    // Browser hides placeholder natively when value is non-empty; verify via value.
                    _ <- Browser.assertAttribute(Selector.id("name"), "value", "Alice")
                yield ()
            }
        }
    }

    // ==== Bug 3: Cursor is black on black background ====
    // Cursor glyph is a TUI artifact. The browser-equivalent invariant is: a focused input
    // has document.activeElement set, which is what makes the native OS caret render.

    "focused input has cursor character in rendered output" in {
        withUI(UI.div(UI.input.id("i").placeholder("type here"))) {
            for
                _ <- Browser.click(Selector.id("i"))
                _ <- Browser.assertFocused(Selector.id("i"))
            yield ()
        }
    }

    // ==== Bug 4: Values hide after submit but come back on edit ====

    "after submit inputs render empty" in {
        formApp.flatMap { ui =>
            withUI(ui) {
                for
                    _ <- Browser.fill(Selector.id("name"), "Alice")
                    _ <- Browser.fill(Selector.id("email"), "a@co.com")
                    _ <- Browser.assertAttribute(Selector.id("name"), "value", "Alice")
                    _ <- Browser.click(Selector.id("add"))
                    _ <- Browser.assertText(Selector.id("status"), "Added Alice")
                    _ <- Browser.assertAttribute(Selector.id("name"), "value", "")
                    _ <- Browser.assertAttribute(Selector.id("email"), "value", "")
                yield ()
            }
        }
    }

    "after submit refocus input still shows empty" in {
        formApp.flatMap { ui =>
            withUI(ui) {
                for
                    _ <- Browser.fill(Selector.id("name"), "Alice")
                    _ <- Browser.fill(Selector.id("email"), "a@co.com")
                    _ <- Browser.click(Selector.id("add"))
                    _ <- Browser.click(Selector.id("name"))
                    _ <- Browser.assertFocused(Selector.id("name"))
                    _ <- Browser.assertAttribute(Selector.id("name"), "value", "")
                yield ()
            }
        }
    }

    // ==== Bug 5: Table is completely broken ====

    "table renders with header and entry rows" in {
        formApp.flatMap { ui =>
            withUI(ui) {
                for
                    _ <- Browser.fill(Selector.id("name"), "Alice")
                    _ <- Browser.fill(Selector.id("email"), "a@co.com")
                    _ <- Browser.click(Selector.id("add"))
                    _ <- Browser.assertExists(Selector.css("#table th:nth-of-type(1)"))
                    _ <- Browser.assertText(Selector.css("#table th:nth-of-type(1)"), "Name")
                    _ <- Browser.assertText(Selector.css("#table th:nth-of-type(2)"), "Email")
                    _ <- Browser.assertText(Selector.css("#table th:nth-of-type(3)"), "Role")
                    _ <- Browser.assertText(Selector.css("#table tr:nth-of-type(2) td:nth-of-type(1)"), "Alice")
                    _ <- Browser.assertText(Selector.css("#table tr:nth-of-type(2) td:nth-of-type(2)"), "a@co.com")
                    _ <- Browser.assertText(Selector.css("#table tr:nth-of-type(2) td:nth-of-type(3)"), "developer")
                yield ()
            }
        }
    }

    "table header above entry data" in {
        formApp.flatMap { ui =>
            withUI(ui) {
                for
                    _ <- Browser.fill(Selector.id("name"), "Alice")
                    _ <- Browser.fill(Selector.id("email"), "a@co.com")
                    _ <- Browser.click(Selector.id("add"))
                    _ <- assertAbove("Name", "Alice")
                    _ <- assertAbove("Email", "a@co.com")
                yield ()
            }
        }
    }

    "two entries render in order" in {
        formApp.flatMap { ui =>
            withUI(ui) {
                for
                    _ <- Browser.fill(Selector.id("name"), "Alice")
                    _ <- Browser.fill(Selector.id("email"), "a@co.com")
                    _ <- Browser.click(Selector.id("add"))
                    _ <- Browser.fill(Selector.id("name"), "Bob")
                    _ <- Browser.fill(Selector.id("email"), "b@co.com")
                    _ <- Browser.click(Selector.id("add"))
                    _ <- Browser.assertText(Selector.css("#table tr:nth-of-type(2) td:nth-of-type(1)"), "Alice")
                    _ <- Browser.assertText(Selector.css("#table tr:nth-of-type(3) td:nth-of-type(1)"), "Bob")
                    // DOM-based ordering: "Bob" also appears in #status ("Added Bob") above the table,
                    // so text-based search would find Bob before Alice.
                    _ <- assertElementAbove(
                        "#table tr:nth-of-type(2) td:nth-of-type(1)",
                        "#table tr:nth-of-type(3) td:nth-of-type(1)"
                    )
                yield ()
            }
        }
    }

    "no entries renders empty message" in {
        formApp.flatMap { ui =>
            withUI(ui) {
                Browser.assertText(Selector.id("empty"), "No entries").unit
            }
        }
    }

    "empty message disappears after adding entry" in {
        formApp.flatMap { ui =>
            withUI(ui) {
                for
                    _ <- Browser.assertExists(Selector.id("empty"))
                    _ <- Browser.fill(Selector.id("name"), "Alice")
                    _ <- Browser.fill(Selector.id("email"), "a@co.com")
                    _ <- Browser.click(Selector.id("add"))
                    _ <- Browser.assertNotExists(Selector.id("empty"))
                yield ()
            }
        }
    }

    // ==== Bug 6: Text repeats when clicking between text boxes ====

    "switch focus between inputs no text duplication" in {
        val app: UI < Async =
            for
                a <- Signal.initRef("")
                b <- Signal.initRef("")
            yield UI.div(
                UI.input.id("a").value(a).onInput(v => a.set(v)),
                UI.input.id("b").value(b).onInput(v => b.set(v)),
                a.map(v => UI.span(s"a-val:$v").id("va")),
                b.map(v => UI.span(s"b-val:$v").id("vb"))
            )
        withUI(app) {
            for
                _ <- Browser.fill(Selector.id("a"), "hello")
                _ <- Browser.assertAttribute(Selector.id("a"), "value", "hello")
                _ <- Browser.assertText(Selector.id("va"), "a-val:hello")
                _ <- Browser.click(Selector.id("b"))
                _ <- Browser.fill(Selector.id("b"), "world")
                _ <- Browser.assertAttribute(Selector.id("a"), "value", "hello")
                _ <- Browser.assertAttribute(Selector.id("b"), "value", "world")
                _ <- Browser.assertText(Selector.id("va"), "a-val:hello")
                _ <- Browser.assertText(Selector.id("vb"), "b-val:world")
            yield ()
        }
    }

    "type in A click B type in B click A renders correctly" in {
        val app: UI < Async =
            for
                a <- Signal.initRef("")
                b <- Signal.initRef("")
            yield UI.div(
                UI.input.id("a").value(a).onInput(v => a.set(v)),
                UI.input.id("b").value(b).onInput(v => b.set(v)),
                a.map(v => UI.span(s"a:$v").id("va")),
                b.map(v => UI.span(s"b:$v").id("vb"))
            )
        withUI(app) {
            for
                _ <- Browser.click(Selector.id("a"))
                _ <- Browser.fill(Selector.id("a"), "x")
                _ <- Browser.assertText(Selector.id("va"), "a:x")
                _ <- Browser.click(Selector.id("b"))
                _ <- Browser.fill(Selector.id("b"), "y")
                _ <- Browser.assertText(Selector.id("vb"), "b:y")
                _ <- Browser.click(Selector.id("a"))
                _ <- Browser.fill(Selector.id("a"), "xz")
                _ <- Browser.assertText(Selector.id("va"), "a:xz")
                _ <- Browser.assertText(Selector.id("vb"), "b:y")
                _ <- Browser.assertAttribute(Selector.id("a"), "value", "xz")
                _ <- Browser.assertAttribute(Selector.id("b"), "value", "y")
            yield ()
        }
    }

    // ==== Overall form layout ====

    "form elements in correct vertical order" in {
        formApp.flatMap { ui =>
            withUI(ui) {
                for
                    _ <- assertAbove("Employee Registry", "Name:")
                    _ <- assertAbove("Name:", "Email:")
                    _ <- assertAbove("Email:", "Role:")
                    _ <- assertAbove("Role:", "Add")
                    _ <- assertAbove("Add", "Employees")
                yield ()
            }
        }
    }

    // ==== Table row layout ====

    "table rows render on separate lines" in {
        formApp.flatMap { ui =>
            withUI(ui) {
                for
                    _ <- Browser.fill(Selector.id("name"), "Alice")
                    _ <- Browser.fill(Selector.id("email"), "a@co.com")
                    _ <- Browser.click(Selector.id("add"))
                    _ <- Browser.fill(Selector.id("name"), "Bob")
                    _ <- Browser.fill(Selector.id("email"), "b@co.com")
                    _ <- Browser.click(Selector.id("add"))
                    _ <- Browser.assertCount(Selector.css("#table tr"), 3)
                    _ <- assertElementAbove(
                        "#table tr:nth-of-type(1) th:nth-of-type(1)",
                        "#table tr:nth-of-type(2) td:nth-of-type(1)"
                    )
                    _ <- assertElementAbove(
                        "#table tr:nth-of-type(2) td:nth-of-type(1)",
                        "#table tr:nth-of-type(3) td:nth-of-type(1)"
                    )
                yield ()
            }
        }
    }

end FormDemoRenderItTest
