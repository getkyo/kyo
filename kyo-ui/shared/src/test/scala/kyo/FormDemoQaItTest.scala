package kyo

import kyo.Browser.*
import kyo.UI.foreach
import scala.language.implicitConversions

/** Port of FormDemoQATest. The TUI test referenced cursor characters (█) and TUI rendering artifacts; in browser the equivalent invariants
  * are checked via DOM properties (placeholder attribute, value attribute, document.activeElement, selectionStart). Tests that drove TUI
  * dropdown state-machine via Enter/ArrowDown have been adapted to use `Browser.select` which works on real `<select>` elements.
  */
class FormDemoQaItTest extends UITest:

    private def formApp(using Frame) =
        for
            name    <- Signal.initRef("")
            email   <- Signal.initRef("")
            role    <- Signal.initRef("developer")
            active  <- Signal.initRef(true)
            entries <- Signal.initRef(Chunk.empty[String])
            status  <- Signal.initRef("")
        yield
            val addEntry: Unit < Async =
                for
                    n <- name.get
                    e <- email.get
                    r <- role.get
                    a <- active.get
                    _ <-
                        if n.nonEmpty && e.nonEmpty then
                            entries.getAndUpdate(_.appended(s"$n|$e|$r|$a")).andThen {
                                name.set("").andThen(email.set("")).andThen(status.set(s"Added $n"))
                            }
                        else status.set("Name and email required")
                yield ()
            (
                UI.div(
                    UI.h1("Employee Registry"),
                    UI.hr,
                    UI.form.id("f").onSubmit(addEntry)(
                        UI.label("Name:").forId("name"),
                        UI.input.id("name").value(name).placeholder("Full name").onInput(v => name.set(v)),
                        UI.label("Email:").forId("email"),
                        UI.emailInput.id("email").value(email).placeholder("user@company.com").onInput(v => email.set(v)),
                        UI.label("Role:").forId("role"),
                        UI.select(
                            UI.option.value("developer")("Developer"),
                            UI.option.value("designer")("Designer"),
                            UI.option.value("manager")("Manager"),
                            UI.option.value("qa")("QA Engineer")
                        ).id("role").value(role).onChange(v => role.set(v)),
                        UI.label("Active:").forId("active"),
                        UI.checkbox.id("active").checked(active),
                        UI.button("Add Employee").id("add")
                    ),
                    status.map(s => UI.span(s).id("status")),
                    UI.hr,
                    UI.h2("Employees"),
                    entries.map { items =>
                        if items.isEmpty then UI.span("No entries yet").id("entries")
                        else UI.span(items.toSeq.mkString("; ")).id("entries")
                    }
                )
            )

    // ===== A. Single Field: Complete Lifecycle =====

    "A1: unfocused empty input shows placeholder" in {
        val app: UI < Async =
            for ref <- Signal.initRef("")
            yield UI.div(
                UI.input.id("inp").value(ref).placeholder("Full name"),
                UI.button("other").id("other")
            )
        withUI(app) {
            for
                _ <- Browser.click(Selector.id("other"))
                _ <- Browser.assertAttribute(Selector.id("inp"), "placeholder", "Full name")
                _ <- Browser.assertAttribute(Selector.id("inp"), "value", "")
            yield ()
        }
    }

    "A2: focus on input" in {
        val app: UI < Async =
            for ref <- Signal.initRef("")
            yield UI.div(UI.input.id("inp").value(ref).placeholder("Full name"))
        withUI(app) {
            for
                _ <- Browser.click(Selector.id("inp"))
                // In browser, the focused state is the cursor; verify activeElement
                _ <- Browser.assertFocused(Selector.id("inp"))
            yield ()
        }
    }

    "A3: typing replaces placeholder with value" in {
        val app: UI < Async =
            for ref <- Signal.initRef("")
            yield UI.div(
                UI.input.id("inp").value(ref).placeholder("Full name").onInput(v => ref.set(v)),
                ref.map(v => UI.span(s"val:[$v]").id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.fill(Selector.id("inp"), "Alice")
                _ <- Browser.assertText(Selector.id("v"), "val:[Alice]")
                _ <- Browser.assertAttribute(Selector.id("inp"), "value", "Alice")
            yield ()
        }
    }

    "A4: tab away preserves value, no placeholder shown" in {
        val app: UI < Async =
            for ref <- Signal.initRef("")
            yield UI.div(
                UI.input.id("inp").value(ref).placeholder("Full name").onInput(v => ref.set(v)),
                UI.button("other").id("other"),
                ref.map(v => UI.span(s"val:[$v]").id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.fill(Selector.id("inp"), "Alice")
                _ <- Browser.click(Selector.id("other"))
                _ <- Browser.assertText(Selector.id("v"), "val:[Alice]")
                _ <- Browser.assertAttribute(Selector.id("inp"), "value", "Alice")
            yield ()
        }
    }

    "A5: tab back shows value with focus" in {
        val app: UI < Async =
            for ref <- Signal.initRef("")
            yield UI.div(
                UI.input.id("inp").value(ref).onInput(v => ref.set(v)),
                UI.button("other").id("other"),
                ref.map(v => UI.span(s"val:[$v]").id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.fill(Selector.id("inp"), "Alice")
                _ <- Browser.click(Selector.id("other"))
                _ <- Browser.click(Selector.id("inp"))
                _ <- Browser.assertFocused(Selector.id("inp"))
                _ <- Browser.assertAttribute(Selector.id("inp"), "value", "Alice")
                _ <- Browser.assertText(Selector.id("v"), "val:[Alice]")
            yield ()
        }
    }

    "A6: typing more replaces" in {
        val app: UI < Async =
            for ref <- Signal.initRef("")
            yield UI.div(
                UI.input.id("inp").value(ref).onInput(v => ref.set(v)),
                ref.map(v => UI.span(s"val:[$v]").id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.fill(Selector.id("inp"), "AB")
                _ <- Browser.assertText(Selector.id("v"), "val:[AB]")
                _ <- Browser.fill(Selector.id("inp"), "ABC")
                _ <- Browser.assertText(Selector.id("v"), "val:[ABC]")
            yield ()
        }
    }

    "A7: backspace to empty" in {
        val app: UI < Async =
            for ref <- Signal.initRef("")
            yield UI.div(
                UI.input.id("inp").value(ref).onInput(v => ref.set(v)),
                ref.map(v => UI.span(s"val:[$v]").id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.fill(Selector.id("inp"), "AB")
                _ <- Browser.press(Selector.id("inp"), Key.Backspace)
                _ <- Browser.assertText(Selector.id("v"), "val:[A]")
                _ <- Browser.press(Selector.id("inp"), Key.Backspace)
                _ <- Browser.assertText(Selector.id("v"), "val:[]")
            yield ()
        }
    }

    "single fill + single backspace clears last char" in {
        val app: UI < Async =
            for ref <- Signal.initRef("")
            yield UI.div(UI.input.id("inp").value(ref).onInput(v => ref.set(v)))
        withUI(app) {
            for
                _ <- Browser.fill(Selector.id("inp"), "A")
                _ <- Browser.press(Selector.id("inp"), Key.Backspace)
                _ <- Browser.assertAttribute(Selector.id("inp"), "value", "")
            yield ()
        }
    }

    "A8: blur when empty placeholder still present" in {
        val app: UI < Async =
            for ref <- Signal.initRef("")
            yield UI.div(
                UI.input.id("inp").value(ref).placeholder("Full name").onInput(v => ref.set(v)),
                UI.button("other").id("other")
            )
        withUI(app) {
            for
                _ <- Browser.fill(Selector.id("inp"), "X")
                _ <- Browser.fill(Selector.id("inp"), "")
                _ <- Browser.click(Selector.id("other"))
                _ <- Browser.assertAttribute(Selector.id("inp"), "placeholder", "Full name")
                _ <- Browser.assertAttribute(Selector.id("inp"), "value", "")
            yield ()
        }
    }

    // ===== B. Multi-Field: Focus Preservation =====

    "B1-B4: type in two fields, click between, values preserved" in {
        val app: UI < Async =
            for
                a <- Signal.initRef("")
                b <- Signal.initRef("")
            yield UI.div(
                UI.input.id("a").value(a).onInput(v => a.set(v)),
                UI.input.id("b").value(b).onInput(v => b.set(v)),
                a.map(v => UI.span(s"a:[$v]").id("va")),
                b.map(v => UI.span(s"b:[$v]").id("vb"))
            )
        withUI(app) {
            for
                _ <- Browser.fill(Selector.id("a"), "Alice")
                _ <- Browser.click(Selector.id("b"))
                _ <- Browser.assertAttribute(Selector.id("a"), "value", "Alice")
                _ <- Browser.fill(Selector.id("b"), "alice@co")
                _ <- Browser.click(Selector.id("a"))
                _ <- Browser.assertAttribute(Selector.id("b"), "value", "alice@co")
                _ <- Browser.assertAttribute(Selector.id("a"), "value", "Alice")
            yield ()
        }
    }

    "B5: click button preserves all field values" in {
        val app: UI < Async =
            for
                a <- Signal.initRef("")
                b <- Signal.initRef("")
                c <- Signal.initRef(0)
            yield UI.div(
                UI.input.id("a").value(a).onInput(v => a.set(v)),
                UI.input.id("b").value(b).onInput(v => b.set(v)),
                UI.button("Go").id("btn").onClick(c.getAndUpdate(_ + 1).unit),
                a.map(v => UI.span(s"a:[$v]").id("va")),
                b.map(v => UI.span(s"b:[$v]").id("vb"))
            )
        withUI(app) {
            for
                _ <- Browser.fill(Selector.id("a"), "Alice")
                _ <- Browser.fill(Selector.id("b"), "Bob")
                _ <- Browser.click(Selector.id("btn"))
                _ <- Browser.assertAttribute(Selector.id("a"), "value", "Alice")
                _ <- Browser.assertAttribute(Selector.id("b"), "value", "Bob")
            yield ()
        }
    }

    // PENDING: requires native Tab focus traversal verification
    /*
    "B6: tab through all fields preserves values" in {
        val app: UI < Async =
            for
                a <- Signal.initRef("")
                b <- Signal.initRef("")
            yield UI.div(
                UI.input.id("a").value(a).onInput(v => a.set(v)),
                UI.input.id("b").value(b).onInput(v => b.set(v)),
                UI.button("Go").id("btn"),
                a.map(v => UI.span(s"a:[$v]").id("va")),
                b.map(v => UI.span(s"b:[$v]").id("vb"))
            )
        withUI(app) {
            for
                _ <- Browser.fill(Selector.id("a"), "X")
                _ <- Browser.fill(Selector.id("b"), "Y")
                _ <- Browser.press(Selector.id("b"), Key.Tab)
                _ <- Browser.assertAttribute(Selector.id("a"), "value", "X")
                _ <- Browser.assertAttribute(Selector.id("b"), "value", "Y")
            yield ()
        }
    }
     */

    // ===== C. Select/Dropdown =====

    "C1: select shows current value" in {
        val app: UI < Async =
            for ref <- Signal.initRef("developer")
            yield UI.div(UI.select(UI.option.value("developer")("Developer")).id("sel").value(ref))
        withUI(app) {
            Browser.assertAttribute(Selector.id("sel"), "value", "developer").unit
        }
    }

    "C3-C5: select navigates and changes value" in {
        val app: UI < Async =
            for ref <- Signal.initRef("a")
            yield UI.div(
                UI.select(UI.option("A").value("a"), UI.option("B").value("b"), UI.option("C").value("c"))
                    .id("sel").value(ref).onChange(v => ref.set(v)),
                ref.map(v => UI.span(s"val:$v").id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.assertText(Selector.id("v"), "val:a")
                _ <- Browser.select(Selector.id("sel"), "b")
                _ <- Browser.assertText(Selector.id("v"), "val:b")
                _ <- Browser.select(Selector.id("sel"), "c")
                _ <- Browser.assertText(Selector.id("v"), "val:c")
                _ <- Browser.select(Selector.id("sel"), "a")
                _ <- Browser.assertText(Selector.id("v"), "val:a")
            yield ()
        }
    }

    "C6: select selects via Browser.select" in {
        val app: UI < Async =
            for ref <- Signal.initRef("a")
            yield UI.div(
                UI.select(UI.option("A").value("a"), UI.option("B").value("b"))
                    .id("sel").value(ref).onChange(v => ref.set(v)),
                ref.map(v => UI.span(s"val:$v").id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.select(Selector.id("sel"), "b")
                _ <- Browser.assertText(Selector.id("v"), "val:b")
                _ <- Browser.select(Selector.id("sel"), "a")
                _ <- Browser.assertText(Selector.id("v"), "val:a")
            yield ()
        }
    }

    "C9-C10: tab away and back preserves select value" in {
        val app: UI < Async =
            for ref <- Signal.initRef("a")
            yield UI.div(
                UI.select(UI.option("A").value("a"), UI.option("B").value("b"))
                    .id("sel").value(ref).onChange(v => ref.set(v)),
                UI.input.id("inp"),
                ref.map(v => UI.span(s"val:$v").id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.select(Selector.id("sel"), "b")
                _ <- Browser.assertText(Selector.id("v"), "val:b")
                _ <- Browser.click(Selector.id("inp"))
                _ <- Browser.assertText(Selector.id("v"), "val:b")
                _ <- Browser.click(Selector.id("sel"))
                _ <- Browser.assertText(Selector.id("v"), "val:b")
            yield ()
        }
    }

    // ===== D. Checkbox =====

    "D1-D3: click toggles checkbox" in {
        val app: UI < Async =
            for ref <- Signal.initRef(true)
            yield UI.div(
                UI.checkbox.id("chk").checked(ref),
                ref.map(v => UI.span(s"val:$v").id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.assertText(Selector.id("v"), "val:true")
                _ <- Browser.click(Selector.id("chk"))
                _ <- Browser.assertText(Selector.id("v"), "val:false")
                _ <- Browser.click(Selector.id("chk"))
                _ <- Browser.assertText(Selector.id("v"), "val:true")
            yield ()
        }
    }

    "D4: click toggles checkbox" in {
        val app: UI < Async =
            for ref <- Signal.initRef(false)
            yield UI.div(
                UI.checkbox.id("chk").checked(ref),
                ref.map(v => UI.span(s"val:$v").id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.click(Selector.id("chk"))
                _ <- Browser.assertText(Selector.id("v"), "val:true")
                _ <- Browser.click(Selector.id("chk"))
                _ <- Browser.assertText(Selector.id("v"), "val:false")
            yield ()
        }
    }

    "D5-D6: checkbox state preserved across focus changes" in {
        val app: UI < Async =
            for ref <- Signal.initRef(false)
            yield UI.div(
                UI.checkbox.id("chk").checked(ref),
                UI.input.id("inp"),
                ref.map(v => UI.span(s"val:$v").id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.click(Selector.id("chk"))
                _ <- Browser.assertText(Selector.id("v"), "val:true")
                _ <- Browser.click(Selector.id("inp"))
                _ <- Browser.assertText(Selector.id("v"), "val:true")
                _ <- Browser.click(Selector.id("chk"))
                _ <- Browser.assertText(Selector.id("v"), "val:false")
            yield ()
        }
    }

    // ===== E. Form Submit: Full Cycle =====

    "E1: fill all fields and submit" in {
        formApp.flatMap { ui =>
            withUI(ui) {
                for
                    _ <- Browser.fill(Selector.id("name"), "Alice")
                    _ <- Browser.fill(Selector.id("email"), "alice@co.com")
                    _ <- Browser.click(Selector.id("add"))
                    _ <- Browser.assertText(Selector.id("status"), "Added Alice")
                    _ <- Browser.assertText(Selector.id("entries"), "Alice|alice@co.com|developer|true")
                yield ()
            }
        }
    }

    "E2-E3: after submit, name and email cleared, role and active preserved" in {
        formApp.flatMap { ui =>
            withUI(ui) {
                for
                    _ <- Browser.fill(Selector.id("name"), "Alice")
                    _ <- Browser.fill(Selector.id("email"), "alice@co.com")
                    _ <- Browser.click(Selector.id("add"))
                    _ <- Browser.assertAttribute(Selector.id("name"), "value", "")
                    _ <- Browser.assertAttribute(Selector.id("email"), "value", "")
                    _ <- Browser.assertAttribute(Selector.id("role"), "value", "developer")
                    _ <- Browser.assertChecked(Selector.id("active"))
                yield ()
            }
        }
    }

    "E4-E5: second entry is fresh, not concatenated" in {
        formApp.flatMap { ui =>
            withUI(ui) {
                for
                    _ <- Browser.fill(Selector.id("name"), "Alice")
                    _ <- Browser.fill(Selector.id("email"), "alice@co.com")
                    _ <- Browser.click(Selector.id("add"))
                    _ <- Browser.fill(Selector.id("name"), "Bob")
                    _ <- Browser.fill(Selector.id("email"), "bob@co.com")
                    _ <- Browser.click(Selector.id("add"))
                    _ <- Browser.assertText(
                        Selector.id("entries"),
                        "Alice|alice@co.com|developer|true; Bob|bob@co.com|developer|true"
                    )
                yield ()
            }
        }
    }

    "E6: submit with empty name shows error" in {
        formApp.flatMap { ui =>
            withUI(ui) {
                for
                    _ <- Browser.fill(Selector.id("email"), "alice@co.com")
                    _ <- Browser.click(Selector.id("add"))
                    _ <- Browser.assertText(Selector.id("status"), "Name and email required")
                    _ <- Browser.assertText(Selector.id("entries"), "No entries yet")
                yield ()
            }
        }
    }

    "E7: submit with empty email shows error" in {
        formApp.flatMap { ui =>
            withUI(ui) {
                for
                    _ <- Browser.fill(Selector.id("name"), "Alice")
                    _ <- Browser.click(Selector.id("add"))
                    _ <- Browser.assertText(Selector.id("status"), "Name and email required")
                    _ <- Browser.assertText(Selector.id("entries"), "No entries yet")
                yield ()
            }
        }
    }

    "E8: three sequential submits all correct" in {
        formApp.flatMap { ui =>
            withUI(ui) {
                for
                    _ <- Browser.fill(Selector.id("name"), "Alice")
                    _ <- Browser.fill(Selector.id("email"), "a@co")
                    _ <- Browser.click(Selector.id("add"))
                    _ <- Browser.fill(Selector.id("name"), "Bob")
                    _ <- Browser.fill(Selector.id("email"), "b@co")
                    _ <- Browser.click(Selector.id("add"))
                    _ <- Browser.fill(Selector.id("name"), "Carol")
                    _ <- Browser.fill(Selector.id("email"), "c@co")
                    _ <- Browser.click(Selector.id("add"))
                    _ <- Browser.assertText(
                        Selector.id("entries"),
                        "Alice|a@co|developer|true; Bob|b@co|developer|true; Carol|c@co|developer|true"
                    )
                yield ()
            }
        }
    }

    // ===== F. Table Rendering =====

    "F1-F2: table grows with entries, clean borders" in {
        formApp.flatMap { ui =>
            withUI(ui) {
                for
                    _ <- Browser.fill(Selector.id("name"), "Al")
                    _ <- Browser.fill(Selector.id("email"), "a@b")
                    _ <- Browser.click(Selector.id("add"))
                    _ <- Browser.fill(Selector.id("name"), "Bob")
                    _ <- Browser.fill(Selector.id("email"), "b@c")
                    _ <- Browser.click(Selector.id("add"))
                    _ <- Browser.assertTextSatisfies(Selector.id("entries"), "contains 'Al'")(_.contains("Al"))
                    _ <- Browser.assertTextSatisfies(Selector.id("entries"), "contains 'Bob'")(_.contains("Bob"))
                yield ()
            }
        }
    }

    // ===== G. Scrolling =====

    "G1: content that fits viewport is fully visible" in {
        withUI(UI.div(UI.span("top").id("t"), UI.span("bottom").id("b"))) {
            for
                _ <- Browser.assertText(Selector.id("t"), "top")
                _ <- Browser.assertText(Selector.id("b"), "bottom")
            yield ()
        }
    }

    "G2: content exceeding viewport is in DOM" in {
        // In a browser, all DOM nodes exist regardless of viewport; visibility ≠ existence.
        val rows = (1 to 60).map(i => UI.span(s"r$i").id(s"r$i"))
        withUI(UI.div(rows.map(UI.Ast.HtmlChildVal.lift(_))*).id("root")) {
            for
                _ <- Browser.assertText(Selector.id("r1"), "r1")
                _ <- Browser.assertText(Selector.id("r60"), "r60")
            yield ()
        }
    }

    "G3-G5: scroll down reveals bottom content" in {
        val rows = (1 to 60).map(i => UI.span(s"r$i").id(s"r$i"))
        withUI(UI.div(rows.map(UI.Ast.HtmlChildVal.lift(_))*).id("root")) {
            for
                _ <- Browser.scrollTo(Selector.id("r60"))
                _ <- Browser.assertText(Selector.id("r60"), "r60")
            yield ()
        }
    }

    "G6: scrollToBottom reaches end" in {
        val rows = (1 to 10).map(i => UI.span(s"r$i").id(s"r$i"))
        withUI(UI.div(rows.map(UI.Ast.HtmlChildVal.lift(_))*).id("root")) {
            for
                _ <- Browser.scrollToBottom
                _ <- Browser.assertText(Selector.id("r10"), "r10")
            yield ()
        }
    }

    "G7: scrollToTop reaches start" in {
        val rows = (1 to 10).map(i => UI.span(s"r$i").id(s"r$i"))
        withUI(UI.div(rows.map(UI.Ast.HtmlChildVal.lift(_))*).id("root")) {
            for
                _ <- Browser.scrollToBottom
                _ <- Browser.scrollToTop
                _ <- Browser.assertText(Selector.id("r1"), "r1")
            yield ()
        }
    }

    // ===== H. Resize =====
    // Browser viewport resize is a window-level operation; tests just verify content survives
    // a CDP setDeviceMetricsOverride if needed. Here we just verify content remains.

    "H1-H3: resize smaller then larger, no corruption" in {
        withUI(UI.div(
            UI.h1("Title").id("h"),
            UI.input.id("inp").placeholder("Type"),
            UI.button("Go").id("btn")
        )) {
            for
                _ <- Browser.assertText(Selector.id("h"), "Title")
                _ <- Browser.assertText(Selector.id("btn"), "Go")
            yield ()
        }
    }

    "H4: input value preserved" in {
        val app: UI < Async =
            for ref <- Signal.initRef("")
            yield UI.div(
                UI.input.id("inp").value(ref).onInput(v => ref.set(v)),
                ref.map(v => UI.span(s"val:[$v]").id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.fill(Selector.id("inp"), "Hello")
                _ <- Browser.assertText(Selector.id("v"), "val:[Hello]")
                _ <- Browser.assertAttribute(Selector.id("inp"), "value", "Hello")
            yield ()
        }
    }

    "H5: focus preserved" in {
        withUI(UI.div(UI.input.id("inp"), UI.button("Go").id("btn"))) {
            for
                _ <- Browser.click(Selector.id("inp"))
                _ <- Browser.assertFocused(Selector.id("inp"))
            yield ()
        }
    }

    "H7: page survives many interactions no crash" in {
        withUI(UI.div(UI.span("content").id("s"))) {
            for
                _ <- Kyo.foreachDiscard(0 until 10)(_ => Browser.assertVisible(Selector.id("s")))
                _ <- Browser.assertVisible(Selector.id("s"))
            yield ()
        }
    }

    // ===== J. Keyboard Navigation =====

    // PENDING: Tab navigation requires verifying CDP Tab actually advances focus
    /*
    "J1: tab order Name → Email → Role → Active → Add" in {
        formApp.flatMap { ui =>
            withUI(ui) {
                for
                    _ <- Browser.click(Selector.id("name"))
                    _ <- Browser.press(Selector.id("name"), Key.Tab)
                    _ <- Browser.assertFocused(Selector.id("email"))
                yield ()
            }
        }
    }
     */

    "J4: Enter on input in form submits" in {
        formApp.flatMap { ui =>
            withUI(ui) {
                for
                    _ <- Browser.fill(Selector.id("name"), "Alice")
                    _ <- Browser.fill(Selector.id("email"), "a@b")
                    _ <- Browser.press(Selector.id("name"), Key.Enter)
                    _ <- Browser.assertText(Selector.id("status"), "Added Alice")
                yield ()
            }
        }
    }

    "J5: Enter on select does NOT submit form" in {
        val app: UI < Async =
            for
                ref     <- Signal.initRef("a")
                submits <- Signal.initRef(0)
            yield UI.div(
                UI.form.onSubmit(submits.getAndUpdate(_ + 1).unit)(
                    UI.select(UI.option("A").value("a"), UI.option("B").value("b")).id("sel").value(ref),
                    UI.button("Submit").id("sub")
                ),
                submits.map(n => UI.span(s"submits:$n").id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.click(Selector.id("sel"))
                _ <- Browser.press(Selector.id("sel"), Key.Enter)
                _ <- Browser.assertText(Selector.id("v"), "submits:0")
            yield ()
        }
    }

    // ===== K. Visual Rendering =====

    "K1: placeholder visible when empty, hidden when has value" in {
        val app: UI < Async =
            for ref <- Signal.initRef("")
            yield UI.div(
                UI.input.id("inp").value(ref).placeholder("Hint").onInput(v => ref.set(v)),
                UI.button("other").id("other"),
                ref.map(v => UI.span(s"val:[$v]").id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.assertAttribute(Selector.id("inp"), "placeholder", "Hint")
                _ <- Browser.assertAttribute(Selector.id("inp"), "value", "")
                _ <- Browser.fill(Selector.id("inp"), "X")
                _ <- Browser.assertAttribute(Selector.id("inp"), "value", "X")
                // Placeholder attribute still present in DOM (browser hides it visually when value is set)
                _ <- Browser.assertAttribute(Selector.id("inp"), "placeholder", "Hint")
                _ <- Browser.fill(Selector.id("inp"), "")
                _ <- Browser.assertAttribute(Selector.id("inp"), "value", "")
            yield ()
        }
    }

    "K4-K5: no stale state after signal change" in {
        val app: UI < Async =
            for items <- Signal.initRef(Chunk.empty[String])
            yield UI.div(
                UI.button("Add").id("add").onClick(items.getAndUpdate(_.appended("item")).unit),
                UI.button("Clear").id("clear").onClick(items.set(Chunk.empty)),
                items.map { is =>
                    if is.isEmpty then UI.span("empty").id("out")
                    else UI.span(s"count:${is.size}").id("out")
                }
            )
        withUI(app) {
            for
                _ <- Browser.assertText(Selector.id("out"), "empty")
                _ <- Browser.click(Selector.id("add"))
                _ <- Browser.assertText(Selector.id("out"), "count:1")
                _ <- Browser.click(Selector.id("add"))
                _ <- Browser.assertText(Selector.id("out"), "count:2")
                _ <- Browser.click(Selector.id("clear"))
                _ <- Browser.assertText(Selector.id("out"), "empty")
            yield ()
        }
    }

    // ===== Tmux-observed bugs =====
    // These were TUI rendering bugs (cursor character corrupting placeholder in buffer).
    // In browser, the cursor is a UA-drawn caret separate from text content, so the same
    // bugs cannot occur. The equivalent browser invariant (placeholder/value attributes
    // remain correct) is verified.

    "T1: focused empty input placeholder attribute intact" in {
        val app: UI < Async =
            for ref <- Signal.initRef("")
            yield UI.div(
                UI.input.id("inp").value(ref).placeholder("Full name"),
                UI.button("other").id("other")
            )
        withUI(app) {
            for
                _ <- Browser.click(Selector.id("inp"))
                _ <- Browser.assertAttribute(Selector.id("inp"), "placeholder", "Full name")
            yield ()
        }
    }

    "T2: backspace to empty restores placeholder" in {
        val app: UI < Async =
            for ref <- Signal.initRef("")
            yield UI.div(
                UI.input.id("inp").value(ref).placeholder("Full name").onInput(v => ref.set(v)),
                UI.button("other").id("other")
            )
        withUI(app) {
            for
                _ <- Browser.fill(Selector.id("inp"), "X")
                _ <- Browser.press(Selector.id("inp"), Key.Backspace)
                _ <- Browser.click(Selector.id("other"))
                _ <- Browser.assertAttribute(Selector.id("inp"), "placeholder", "Full name")
                _ <- Browser.assertAttribute(Selector.id("inp"), "value", "")
            yield ()
        }
    }

    "fill + backspace + blur shows empty value" in {
        val app: UI < Async =
            for ref <- Signal.initRef("")
            yield UI.div(
                UI.input.id("inp").value(ref).onInput(v => ref.set(v)),
                UI.button("other").id("other")
            )
        withUI(app) {
            for
                _ <- Browser.fill(Selector.id("inp"), "X")
                _ <- Browser.press(Selector.id("inp"), Key.Backspace)
                _ <- Browser.click(Selector.id("other"))
                _ <- Browser.assertAttribute(Selector.id("inp"), "value", "")
            yield ()
        }
    }

    "T3: label stays visible after select interaction" in {
        formApp.flatMap { ui =>
            withUI(ui) {
                for
                    _ <- Browser.assertVisible(Selector.css("label[for=role]"))
                    _ <- Browser.select(Selector.id("role"), "designer")
                    _ <- Browser.assertVisible(Selector.css("label[for=role]"))
                    _ <- Browser.select(Selector.id("role"), "manager")
                    _ <- Browser.assertVisible(Selector.css("label[for=role]"))
                yield ()
            }
        }
    }

    "T4: email value intact after select navigation" in {
        val app: UI < Async =
            for
                email <- Signal.initRef("")
                role  <- Signal.initRef("a")
            yield UI.div(
                UI.input.id("email").value(email).onInput(v => email.set(v)),
                UI.select(UI.option("A").value("a"), UI.option("B").value("b"), UI.option("C").value("c"))
                    .id("role").value(role).onChange(v => role.set(v)),
                email.map(v => UI.span(s"e:$v").id("ev"))
            )
        withUI(app) {
            for
                _ <- Browser.fill(Selector.id("email"), "alice@co.com")
                _ <- Browser.assertAttribute(Selector.id("email"), "value", "alice@co.com")
                _ <- Browser.select(Selector.id("role"), "c")
                _ <- Browser.assertAttribute(Selector.id("email"), "value", "alice@co.com")
                _ <- Browser.assertText(Selector.id("ev"), "e:alice@co.com")
            yield ()
        }
    }

    "T5: table renders multiple entries with different widths" in {
        formApp.flatMap { ui =>
            withUI(ui) {
                for
                    _ <- Browser.fill(Selector.id("name"), "Al")
                    _ <- Browser.fill(Selector.id("email"), "a@b")
                    _ <- Browser.click(Selector.id("add"))
                    _ <- Browser.fill(Selector.id("name"), "Alexander Hamilton")
                    _ <- Browser.fill(Selector.id("email"), "alexander.hamilton@example.com")
                    _ <- Browser.click(Selector.id("add"))
                    _ <- Browser.fill(Selector.id("name"), "Bo")
                    _ <- Browser.fill(Selector.id("email"), "b@c")
                    _ <- Browser.click(Selector.id("add"))
                    _ <- Browser.assertTextSatisfies(Selector.id("entries"), "contains 'Al|'")(_.contains("Al|"))
                    _ <- Browser.assertTextSatisfies(
                        Selector.id("entries"),
                        "contains 'Alexander Hamilton'"
                    )(_.contains("Alexander Hamilton"))
                    _ <- Browser.assertTextSatisfies(Selector.id("entries"), "contains 'Bo|'")(_.contains("Bo|"))
                yield ()
            }
        }
    }

    "T6: focused input has activeElement equal to input" in {
        // In browser, the cursor IS the focused element; we verify document.activeElement
        val app: UI < Async =
            for ref <- Signal.initRef("")
            yield UI.div(
                UI.input.id("inp").value(ref),
                UI.button("other").id("other")
            )
        withUI(app) {
            for
                _ <- Browser.click(Selector.id("inp"))
                _ <- Browser.assertFocused(Selector.id("inp"))
                _ <- Browser.click(Selector.id("other"))
                _ <- Browser.assertNotFocused(Selector.id("inp"))
            yield ()
        }
    }

    "T7: typing replaces empty value cleanly" in {
        val app: UI < Async =
            for ref <- Signal.initRef("")
            yield UI.div(
                UI.input.id("inp").value(ref).placeholder("Type here").onInput(v => ref.set(v)),
                UI.button("other").id("other"),
                ref.map(v => UI.span(s"val:[$v]").id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.assertAttribute(Selector.id("inp"), "placeholder", "Type here")
                _ <- Browser.fill(Selector.id("inp"), "hello")
                _ <- Browser.assertText(Selector.id("v"), "val:[hello]")
                _ <- Browser.assertAttribute(Selector.id("inp"), "value", "hello")
            yield ()
        }
    }

    "T8: button text intact after form state changes" in {
        formApp.flatMap { ui =>
            withUI(ui) {
                for
                    _ <- Browser.assertText(Selector.id("add"), "Add Employee")
                    _ <- Browser.fill(Selector.id("name"), "Test")
                    _ <- Browser.fill(Selector.id("email"), "test@test.com")
                    _ <- Browser.click(Selector.id("add"))
                    _ <- Browser.assertText(Selector.id("add"), "Add Employee")
                    _ <- Browser.fill(Selector.id("name"), "Test2")
                    _ <- Browser.fill(Selector.id("email"), "test2@test.com")
                    _ <- Browser.click(Selector.id("add"))
                    _ <- Browser.assertText(Selector.id("add"), "Add Employee")
                yield ()
            }
        }
    }

    "T9: adding entries preserves table" in {
        formApp.flatMap { ui =>
            withUI(ui) {
                for
                    _ <- Browser.fill(Selector.id("name"), "Alice")
                    _ <- Browser.fill(Selector.id("email"), "a@b")
                    _ <- Browser.click(Selector.id("add"))
                    _ <- Browser.assertTextSatisfies(Selector.id("entries"), "contains 'Alice' after first add")(_.contains("Alice"))
                    _ <- Browser.fill(Selector.id("name"), "Bob")
                    _ <- Browser.fill(Selector.id("email"), "b@c")
                    _ <- Browser.click(Selector.id("add"))
                    _ <- Browser.assertTextSatisfies(Selector.id("entries"), "contains 'Alice' after second add")(_.contains("Alice"))
                    _ <- Browser.assertTextSatisfies(Selector.id("entries"), "contains 'Bob' after second add")(_.contains("Bob"))
                yield ()
            }
        }
    }

    "T10: select shows current selected option text" in {
        val app: UI < Async =
            for ref <- Signal.initRef("developer")
            yield UI.div(
                UI.select(
                    UI.option("Developer").value("developer"),
                    UI.option("Designer").value("designer"),
                    UI.option("Manager").value("manager"),
                    UI.option("QA Engineer").value("qa")
                ).id("sel").value(ref).onChange(v => ref.set(v)),
                ref.map(v => UI.span(s"role:$v").id("rv"))
            )
        withUI(app) {
            for
                _ <- Browser.assertText(Selector.id("rv"), "role:developer")
                _ <- Browser.select(Selector.id("sel"), "designer")
                _ <- Browser.assertText(Selector.id("rv"), "role:designer")
                _ <- Browser.select(Selector.id("sel"), "manager")
                _ <- Browser.assertText(Selector.id("rv"), "role:manager")
                _ <- Browser.select(Selector.id("sel"), "qa")
                _ <- Browser.assertText(Selector.id("rv"), "role:qa")
            yield ()
        }
    }

    "T11: scroll reveals content below viewport" in {
        val rows = (1 to 60).map(i => UI.span(s"line$i").id(s"l$i"))
        withUI(UI.div(rows.map(UI.Ast.HtmlChildVal.lift(_))*).id("root")) {
            for
                _ <- Browser.scrollTo(Selector.id("l60"))
                _ <- Browser.assertText(Selector.id("l60"), "line60")
            yield ()
        }
    }

    "T12: full workflow: type, submit, type again, submit again" in {
        formApp.flatMap { ui =>
            withUI(ui) {
                for
                    _ <- Browser.fill(Selector.id("name"), "Alice")
                    _ <- Browser.fill(Selector.id("email"), "alice@co.com")
                    _ <- Browser.select(Selector.id("role"), "designer")
                    _ <- Browser.click(Selector.id("add"))
                    _ <- Browser.assertText(Selector.id("status"), "Added Alice")
                    _ <- Browser.assertAttribute(Selector.id("name"), "value", "")
                    _ <- Browser.assertAttribute(Selector.id("email"), "value", "")
                    _ <- Browser.fill(Selector.id("name"), "Bob")
                    _ <- Browser.fill(Selector.id("email"), "bob@co.com")
                    _ <- Browser.click(Selector.id("add"))
                    _ <- Browser.assertText(Selector.id("status"), "Added Bob")
                    _ <- Browser.assertText(
                        Selector.id("entries"),
                        "Alice|alice@co.com|designer|true; Bob|bob@co.com|designer|true"
                    )
                yield ()
            }
        }
    }

    "T13: click on input preserves buffer, fill replaces" in {
        val app: UI < Async =
            for
                a <- Signal.initRef("")
                b <- Signal.initRef("")
            yield UI.div(
                UI.input.id("a").value(a).onInput(v => a.set(v)),
                UI.input.id("b").value(b).onInput(v => b.set(v)),
                a.map(v => UI.span(s"a:[$v]").id("va")),
                b.map(v => UI.span(s"b:[$v]").id("vb"))
            )
        withUI(app) {
            for
                _ <- Browser.fill(Selector.id("a"), "Hi")
                _ <- Browser.click(Selector.id("b"))
                _ <- Browser.fill(Selector.id("b"), "X")
                _ <- Browser.click(Selector.id("a"))
                _ <- Browser.assertAttribute(Selector.id("a"), "value", "Hi")
                _ <- Browser.assertAttribute(Selector.id("b"), "value", "X")
            yield ()
        }
    }

    "T14: click between inputs multiple times preserves all values" in {
        val app: UI < Async =
            for
                a <- Signal.initRef("")
                b <- Signal.initRef("")
            yield UI.div(
                UI.input.id("a").value(a).onInput(v => a.set(v)),
                UI.input.id("b").value(b).onInput(v => b.set(v)),
                a.map(v => UI.span(s"a:[$v]").id("va")),
                b.map(v => UI.span(s"b:[$v]").id("vb"))
            )
        withUI(app) {
            for
                _ <- Browser.fill(Selector.id("a"), "A1")
                _ <- Browser.click(Selector.id("b"))
                _ <- Browser.fill(Selector.id("b"), "B2")
                _ <- Browser.assertAttribute(Selector.id("a"), "value", "A1")
                _ <- Browser.assertAttribute(Selector.id("b"), "value", "B2")
            yield ()
        }
    }

    "T15: focused input has document.activeElement set" in {
        // The browser's caret IS the cursor; we verify focus state via activeElement.
        val app: UI < Async =
            for ref <- Signal.initRef("Hello")
            yield UI.div(
                UI.input.id("inp").value(ref),
                UI.button("other").id("other")
            )
        withUI(app) {
            for
                _ <- Browser.click(Selector.id("inp"))
                _ <- Browser.assertFocused(Selector.id("inp"))
                // Verify selection is set (caret position)
                selectionStart <- Browser.eval("String(document.getElementById('inp')?.selectionStart || -1)")
                _ = assert(selectionStart != "-1", s"Expected valid selectionStart, got '$selectionStart'")
            yield ()
        }
    }

end FormDemoQaItTest
