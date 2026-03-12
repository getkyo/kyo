package kyo

import kyo.Style.Alignment
import kyo.Style.BorderStyle
import kyo.Style.FlexWrap
import kyo.Style.Justification
import kyo.Style.Overflow
import kyo.Style.Size.*
import kyo.Style.TextAlign
import kyo.Style.TextTransform
import kyo.Style.TextWrap
import kyo.UI.*
import kyo.UI.internal.*
import kyo.internal.*
import scala.language.implicitConversions

class TerminalEmulatorQA extends Test:
    given Frame = Frame.internal

    val dir = "/tmp/tui-qa"

    private def mkSig[A](init: A)(using CanEqual[A, A], AllowUnsafe): SignalRef[A] =
        given Frame = Frame.internal
        Sync.Unsafe.evalOrThrow(Signal.initRef(init))

    private lazy val log = new java.io.PrintWriter(new java.io.FileWriter("/tmp/tui-qa/frames.txt"), true)

    private def save(emu: TerminalEmulator, name: String): Unit =
        emu.screenshot(s"$dir/$name")
        log.println(s"=== $name ===")
        log.println(emu.frame)
        val sm = emu.styleMap
        if sm.nonEmpty then
            log.println("--- styles ---")
            log.print(sm)
        log.println()
    end save

    "generate all QA screenshots" in run {
        for _ <- Signal.initRef(0) yield
            import AllowUnsafe.embrace.danger
            new java.io.File(dir).mkdirs()
            java.lang.System.err.println(s"[QA] Output dir: $dir")
            java.lang.System.err.println("[QA] === Section 1: Text & Containers (8 tests) ===")

            // === Section 1: Text & Containers ===

            // 1.01 span-basic
            {
                val emu = TerminalEmulator(span("Hello World"), cols = 30, rows = 5)
                save(emu, "1.01-0.png")
            }

            // 1.02 div-column-children
            {
                val emu = TerminalEmulator(
                    div(span("Line 1"), span("Line 2"), span("Line 3")),
                    cols = 30,
                    rows = 8
                )
                save(emu, "1.02-0.png")
            }

            // 1.03 paragraph-wrap
            {
                val emu = TerminalEmulator(
                    p("This is a paragraph of text that should wrap when it exceeds the available width of the container."),
                    cols = 30,
                    rows = 8
                )
                save(emu, "1.03-0.png")
            }

            // 1.04 headings
            {
                val emu = TerminalEmulator(
                    div(h1("Heading 1"), h2("Heading 2"), h3("Heading 3"), p("Body")),
                    cols = 40,
                    rows = 14
                )
                save(emu, "1.04-0.png")
            }

            // 1.05 pre-preserves-whitespace
            {
                val emu = TerminalEmulator(
                    pre("  two spaces\n    four spaces\nno indent"),
                    cols = 40,
                    rows = 6
                )
                save(emu, "1.05-0.png")
            }

            // 1.06 label-anchor
            {
                val emu = TerminalEmulator(
                    div(label("Name:"), a("Click here").href("http://example.com")),
                    cols = 40,
                    rows = 5
                )
                save(emu, "1.06-0.png")
            }

            // 1.07 nested-deep
            {
                val emu = TerminalEmulator(
                    div(div(div(div(div(span("deep")))))),
                    cols = 30,
                    rows = 5
                )
                save(emu, "1.07-0.png")
            }

            // 1.08 ten-siblings
            {
                val emu = TerminalEmulator(
                    div(
                        span("1"),
                        span("2"),
                        span("3"),
                        span("4"),
                        span("5"),
                        span("6"),
                        span("7"),
                        span("8"),
                        span("9"),
                        span("10")
                    ),
                    cols = 30,
                    rows = 12
                )
                save(emu, "1.08-0.png")
            }

            java.lang.System.err.println("[QA] === Section 2: Button (7 tests) ===")
            // === Section 2: Button ===

            // 2.01 button-render
            {
                val emu = TerminalEmulator(button("Click Me"), cols = 30, rows = 5)
                save(emu, "2.01-0.png")
            }

            // 2.02 button-click-handler
            {
                val sig = mkSig(false)
                val emu = TerminalEmulator(
                    div(button("Go").onClick(sig.set(true)), span(sig.map(v => s"result=$v"))),
                    cols = 30,
                    rows = 8
                )
                save(emu, "2.02-0.png")
                emu.clickOn("Go")
                emu.waitForEffects()
                save(emu, "2.02-1.png")
            }

            // 2.03 button-disabled
            {
                val emu = TerminalEmulator(
                    div(button("Enabled"), button("Disabled").disabled(true)),
                    cols = 30,
                    rows = 10
                )
                save(emu, "2.03-0.png")
            }

            // 2.04 buttons-column
            {
                val emu = TerminalEmulator(
                    div(button("Top"), button("Bottom")),
                    cols = 30,
                    rows = 10
                )
                save(emu, "2.04-0.png")
            }

            // 2.05 buttons-row
            {
                val emu = TerminalEmulator(
                    div(button("Left"), button("Right")).style(_.row),
                    cols = 40,
                    rows = 5
                )
                save(emu, "2.05-0.png")
            }

            // 2.06 button-focus-visual
            {
                val emu = TerminalEmulator(
                    div(button("A"), button("B")),
                    cols = 30,
                    rows = 10
                )
                save(emu, "2.06-0.png")
                emu.tab()
                save(emu, "2.06-1.png")
                emu.tab()
                save(emu, "2.06-2.png")
            }

            // 2.07 button-keyboard-activate
            {
                val sig = mkSig(false)
                val emu = TerminalEmulator(
                    div(button("Press").onClick(sig.set(true)), span(sig.map(v => s"done=$v"))),
                    cols = 30,
                    rows = 8
                )
                save(emu, "2.07-0.png")
                emu.tab()
                emu.enter()
                emu.waitForEffects()
                save(emu, "2.07-1.png")
            }

            java.lang.System.err.println("[QA] === Section 3: Text Input (15 tests) ===")
            // === Section 3: Text Input ===

            // 3.01 input-placeholder
            {
                val emu = TerminalEmulator(input.placeholder("Enter name"), cols = 30, rows = 5)
                save(emu, "3.01-0.png")
            }

            // 3.02 input-with-value
            {
                val sig = mkSig("Hello")
                val emu = TerminalEmulator(input.value(sig), cols = 30, rows = 5)
                save(emu, "3.02-0.png")
            }

            // 3.03 input-focus-shows-cursor
            {
                val sig = mkSig("Hello")
                val emu = TerminalEmulator(input.value(sig), cols = 30, rows = 5)
                save(emu, "3.03-0.png")
                emu.tab()
                save(emu, "3.03-1.png")
            }

            // 3.04 input-type-text
            {
                val sig = mkSig("")
                val emu = TerminalEmulator(input.value(sig), cols = 30, rows = 5)
                save(emu, "3.04-0.png")
                emu.tab()
                emu.typeText("abc")
                save(emu, "3.04-1.png")
            }

            // 3.05 input-backspace
            {
                val sig = mkSig("")
                val emu = TerminalEmulator(input.value(sig), cols = 30, rows = 5)
                emu.tab()
                emu.typeText("abc")
                save(emu, "3.05-0.png")
                emu.key(UI.Keyboard.Backspace)
                save(emu, "3.05-1.png")
            }

            // 3.06 input-delete-key
            {
                val sig = mkSig("")
                val emu = TerminalEmulator(input.value(sig), cols = 30, rows = 5)
                emu.tab()
                emu.typeText("abc")
                save(emu, "3.06-0.png")
                emu.key(UI.Keyboard.Home)
                emu.key(UI.Keyboard.Delete)
                save(emu, "3.06-1.png")
            }

            // 3.07 input-arrow-navigation
            {
                val sig = mkSig("")
                val emu = TerminalEmulator(input.value(sig), cols = 30, rows = 5)
                emu.tab()
                emu.typeText("abcdef")
                save(emu, "3.07-0.png")
                emu.key(UI.Keyboard.Home)
                emu.key(UI.Keyboard.ArrowRight)
                emu.key(UI.Keyboard.ArrowRight)
                save(emu, "3.07-1.png")
            }

            // 3.08 input-horizontal-scroll
            {
                val sig = mkSig("")
                val emu = TerminalEmulator(input.value(sig), cols = 15, rows = 5)
                emu.tab()
                emu.typeText("This text is way too long for 15 cols")
                save(emu, "3.08-0.png")
            }

            // 3.09 input-select-all-delete
            {
                val sig = mkSig("")
                val emu = TerminalEmulator(input.value(sig), cols = 30, rows = 5)
                emu.tab()
                emu.typeText("hello")
                save(emu, "3.09-0.png")
                emu.key(UI.Keyboard.Char('a'), ctrl = true)
                save(emu, "3.09-1.png")
                emu.key(UI.Keyboard.Backspace)
                save(emu, "3.09-2.png")
            }

            // 3.10 input-paste
            {
                val sig = mkSig("")
                val emu = TerminalEmulator(input.value(sig), cols = 30, rows = 5)
                emu.tab()
                emu.paste("pasted text")
                save(emu, "3.10-0.png")
            }

            // 3.11 input-paste-replaces-selection
            {
                val sig = mkSig("")
                val emu = TerminalEmulator(input.value(sig), cols = 30, rows = 5)
                emu.tab()
                emu.typeText("old text")
                save(emu, "3.11-0.png")
                emu.key(UI.Keyboard.Char('a'), ctrl = true)
                emu.paste("new")
                save(emu, "3.11-1.png")
            }

            // 3.12 input-readonly
            {
                val sig = mkSig("locked")
                val emu = TerminalEmulator(input.value(sig).readOnly(true), cols = 30, rows = 5)
                emu.tab()
                emu.typeText("extra")
                save(emu, "3.12-0.png")
            }

            // 3.13 password-masking
            {
                val sig = mkSig("")
                val emu = TerminalEmulator(password.value(sig), cols = 30, rows = 5)
                emu.tab()
                emu.typeText("secret")
                save(emu, "3.13-0.png")
            }

            // 3.14 number-input-stepping
            {
                val sig = mkSig("5")
                val emu = TerminalEmulator(
                    div(number.value(sig).min(0).max(10).step(1), span(sig.map(v => s"val=$v"))),
                    cols = 30,
                    rows = 8
                )
                save(emu, "3.14-0.png")
                emu.tab()
                emu.key(UI.Keyboard.ArrowUp)
                emu.key(UI.Keyboard.ArrowUp)
                save(emu, "3.14-1.png")
                emu.key(UI.Keyboard.ArrowDown)
                emu.key(UI.Keyboard.ArrowDown)
                emu.key(UI.Keyboard.ArrowDown)
                save(emu, "3.14-2.png")
            }

            // 3.15 number-input-min-max-clamp
            {
                val sig = mkSig("4")
                val emu = TerminalEmulator(
                    div(number.value(sig).min(0).max(5).step(1), span(sig.map(v => s"val=$v"))),
                    cols = 30,
                    rows = 8
                )
                emu.tab()
                for _ <- 0 until 5 do emu.key(UI.Keyboard.ArrowUp)
                save(emu, "3.15-0.png")
            }

            java.lang.System.err.println("[QA] === Section 4: Textarea (5 tests) ===")
            // === Section 4: Textarea ===

            // 4.01 textarea-placeholder
            {
                val emu = TerminalEmulator(
                    textarea.placeholder("Write notes..."),
                    cols = 40,
                    rows = 8
                )
                save(emu, "4.01-0.png")
            }

            // 4.02 textarea-type-multiline
            {
                val sig = mkSig("")
                val emu = TerminalEmulator(textarea.value(sig), cols = 40, rows = 8)
                emu.tab()
                emu.typeText("Line one")
                emu.enter()
                emu.typeText("Line two")
                emu.enter()
                emu.typeText("Line three")
                save(emu, "4.02-0.png")
            }

            // 4.03 textarea-word-wrap
            {
                val sig = mkSig("")
                val emu = TerminalEmulator(textarea.value(sig), cols = 25, rows = 8)
                emu.tab()
                emu.typeText("The quick brown fox jumps over the lazy dog near the river")
                save(emu, "4.03-0.png")
            }

            // 4.04 textarea-vertical-scroll
            {
                val sig = mkSig("")
                val emu = TerminalEmulator(textarea.value(sig), cols = 25, rows = 6)
                emu.tab()
                emu.typeText("L1")
                emu.enter()
                emu.typeText("L2")
                emu.enter()
                emu.typeText("L3")
                emu.enter()
                emu.typeText("L4")
                emu.enter()
                emu.typeText("L5")
                emu.enter()
                emu.typeText("L6")
                save(emu, "4.04-0.png")
            }

            // 4.05 textarea-arrow-up-down
            {
                val sig = mkSig("")
                val emu = TerminalEmulator(textarea.value(sig), cols = 40, rows = 8)
                emu.tab()
                emu.typeText("First")
                emu.enter()
                emu.typeText("Second")
                emu.enter()
                emu.typeText("Third")
                save(emu, "4.05-0.png")
                emu.key(UI.Keyboard.ArrowUp)
                emu.key(UI.Keyboard.ArrowUp)
                save(emu, "4.05-1.png")
            }

            java.lang.System.err.println("[QA] === Section 5: Checkbox & Radio (6 tests) ===")
            // === Section 5: Checkbox & Radio ===

            // 5.01 checkbox-unchecked
            {
                val emu = TerminalEmulator(
                    div(checkbox, span("Accept terms")),
                    cols = 30,
                    rows = 5
                )
                save(emu, "5.01-0.png")
            }

            // 5.02 checkbox-checked
            {
                val sig = mkSig(true)
                val emu = TerminalEmulator(checkbox.checked(sig), cols = 30, rows = 5)
                save(emu, "5.02-0.png")
            }

            // 5.03 checkbox-toggle
            {
                val sig = mkSig(false)
                val emu = TerminalEmulator(
                    div(checkbox.checked(sig), span(sig.map(v => s"val=$v"))),
                    cols = 30,
                    rows = 5
                )
                save(emu, "5.03-0.png")
                emu.tab()
                emu.space()
                save(emu, "5.03-1.png")
                emu.space()
                save(emu, "5.03-2.png")
            }

            // 5.04 radio-render
            {
                val emu = TerminalEmulator(
                    div(radio, span("Option A")),
                    cols = 30,
                    rows = 5
                )
                save(emu, "5.04-0.png")
            }

            // 5.05 radio-toggle
            {
                val sig = mkSig(false)
                val emu = TerminalEmulator(
                    div(radio.checked(sig), span(sig.map(v => s"on=$v"))),
                    cols = 30,
                    rows = 5
                )
                save(emu, "5.05-0.png")
                emu.tab()
                emu.space()
                save(emu, "5.05-1.png")
            }

            // 5.06 radio-group-exclusive
            {
                val s1 = mkSig(false)
                val s2 = mkSig(false)
                val emu = TerminalEmulator(
                    div(
                        radio.checked(s1).name("grp"),
                        radio.checked(s2).name("grp"),
                        span(s1.map(v => s"r1=$v")),
                        span(s2.map(v => s"r2=$v"))
                    ),
                    cols = 30,
                    rows = 8
                )
                save(emu, "5.06-0.png")
                emu.tab()
                emu.space()
                save(emu, "5.06-1.png")
                emu.tab()
                emu.space()
                save(emu, "5.06-2.png")
            }

            java.lang.System.err.println("[QA] === Section 6: Select (5 tests) ===")
            // === Section 6: Select ===

            // 6.01 select-render
            {
                val emu = TerminalEmulator(
                    select(
                        option("Apple").value("a"),
                        option("Banana").value("b"),
                        option("Cherry").value("c")
                    ),
                    cols = 30,
                    rows = 5
                )
                save(emu, "6.01-0.png")
            }

            // 6.02 select-cycle-down
            {
                val emu = TerminalEmulator(
                    select(
                        option("Apple").value("a"),
                        option("Banana").value("b"),
                        option("Cherry").value("c")
                    ),
                    cols = 30,
                    rows = 5
                )
                save(emu, "6.02-0.png")
                emu.tab()
                emu.key(UI.Keyboard.ArrowDown)
                save(emu, "6.02-1.png")
                emu.key(UI.Keyboard.ArrowDown)
                save(emu, "6.02-2.png")
            }

            // 6.03 select-cycle-up
            {
                val emu = TerminalEmulator(
                    select(
                        option("Apple").value("a"),
                        option("Banana").value("b"),
                        option("Cherry").value("c")
                    ),
                    cols = 30,
                    rows = 5
                )
                emu.tab()
                emu.key(UI.Keyboard.ArrowDown)
                emu.key(UI.Keyboard.ArrowDown)
                save(emu, "6.03-0.png")
                emu.key(UI.Keyboard.ArrowUp)
                save(emu, "6.03-1.png")
            }

            // 6.04 select-boundary
            {
                val emu = TerminalEmulator(
                    select(
                        option("Apple").value("a"),
                        option("Banana").value("b"),
                        option("Cherry").value("c")
                    ),
                    cols = 30,
                    rows = 5
                )
                emu.tab()
                emu.key(UI.Keyboard.ArrowUp)
                save(emu, "6.04-0.png")
            }

            // 6.05 select-with-signal
            {
                val sig = mkSig("a")
                val emu = TerminalEmulator(
                    div(
                        select.value(sig)(
                            option("Apple").value("a"),
                            option("Banana").value("b"),
                            option("Cherry").value("c")
                        ),
                        span(sig.map(v => s"selected=$v"))
                    ),
                    cols = 30,
                    rows = 8
                )
                save(emu, "6.05-0.png")
                emu.tab()
                emu.key(UI.Keyboard.ArrowDown)
                save(emu, "6.05-1.png")
            }

            java.lang.System.err.println("[QA] === Section 7: Range Input (5 tests) ===")
            // === Section 7: Range Input ===

            // 7.01 range-render-50pct
            {
                val sig = mkSig(50.0)
                val emu = TerminalEmulator(rangeInput.value(sig).min(0).max(100), cols = 30, rows = 5)
                save(emu, "7.01-0.png")
            }

            // 7.02 range-render-0pct
            {
                val sig = mkSig(0.0)
                val emu = TerminalEmulator(rangeInput.value(sig).min(0).max(100), cols = 30, rows = 5)
                save(emu, "7.02-0.png")
            }

            // 7.03 range-render-100pct
            {
                val sig = mkSig(100.0)
                val emu = TerminalEmulator(rangeInput.value(sig).min(0).max(100), cols = 30, rows = 5)
                save(emu, "7.03-0.png")
            }

            // 7.04 range-step-right
            {
                val sig = mkSig(50.0)
                val emu = TerminalEmulator(
                    div(rangeInput.value(sig).min(0).max(100).step(10), span(sig.map(v => s"val=$v"))),
                    cols = 30,
                    rows = 8
                )
                save(emu, "7.04-0.png")
                emu.tab()
                emu.key(UI.Keyboard.ArrowRight)
                emu.key(UI.Keyboard.ArrowRight)
                emu.key(UI.Keyboard.ArrowRight)
                save(emu, "7.04-1.png")
            }

            // 7.05 range-clamp-max
            {
                val sig = mkSig(90.0)
                val emu = TerminalEmulator(
                    div(rangeInput.value(sig).min(0).max(100).step(10), span(sig.map(v => s"val=$v"))),
                    cols = 30,
                    rows = 8
                )
                emu.tab()
                for _ <- 0 until 5 do emu.key(UI.Keyboard.ArrowRight)
                save(emu, "7.05-0.png")
            }

            java.lang.System.err.println("[QA] === Section 8: Lists (3 tests) ===")
            // === Section 8: Lists ===

            // 8.01 unordered-list
            {
                val emu = TerminalEmulator(
                    ul(li("First"), li("Second"), li("Third")),
                    cols = 30,
                    rows = 8
                )
                save(emu, "8.01-0.png")
            }

            // 8.02 ordered-list
            {
                val emu = TerminalEmulator(
                    ol(li("Alpha"), li("Beta"), li("Gamma")),
                    cols = 30,
                    rows = 8
                )
                save(emu, "8.02-0.png")
            }

            // 8.03 nested-list
            {
                val emu = TerminalEmulator(
                    ul(li("Parent"), li(ul(li("Child A"), li("Child B")))),
                    cols = 30,
                    rows = 8
                )
                save(emu, "8.03-0.png")
            }

            java.lang.System.err.println("[QA] === Section 9: Table (4 tests) ===")
            // === Section 9: Table ===

            // 9.01 simple-2x2
            {
                val emu = TerminalEmulator(
                    table(tr(td("A"), td("B")), tr(td("C"), td("D"))),
                    cols = 30,
                    rows = 6
                )
                save(emu, "9.01-0.png")
            }

            // 9.02 table-headers
            {
                val emu = TerminalEmulator(
                    table(tr(th("Name"), th("Age")), tr(td("Alice"), td("30"))),
                    cols = 30,
                    rows = 6
                )
                save(emu, "9.02-0.png")
            }

            // 9.03 table-colspan
            {
                val emu = TerminalEmulator(
                    table(tr(td("Spanning").colspan(2)), tr(td("Left"), td("Right"))),
                    cols = 30,
                    rows = 6
                )
                save(emu, "9.03-0.png")
            }

            // 9.04 table-3-cols
            {
                val emu = TerminalEmulator(
                    table(tr(td("Col1"), td("Col2"), td("Col3")), tr(td("A"), td("B"), td("C"))),
                    cols = 45,
                    rows = 6
                )
                save(emu, "9.04-0.png")
            }

            java.lang.System.err.println("[QA] === Section 10: HR & BR (2 tests) ===")
            // === Section 10: HR & BR ===

            // 10.01 hr-between-text
            {
                val emu = TerminalEmulator(
                    div(span("Above"), hr, span("Below")),
                    cols = 30,
                    rows = 6
                )
                save(emu, "10.01-0.png")
            }

            // 10.02 br-gap
            {
                val emu = TerminalEmulator(
                    div(span("One"), br, span("Two")),
                    cols = 30,
                    rows = 6
                )
                save(emu, "10.02-0.png")
            }

            java.lang.System.err.println("[QA] === Section 11: Layout (13 tests) ===")
            // === Section 11: Layout ===

            // 11.01 row-direction
            {
                val emu = TerminalEmulator(
                    div(span("AAA"), span("BBB"), span("CCC")).style(_.row),
                    cols = 30,
                    rows = 3
                )
                save(emu, "11.01-0.png")
            }

            // 11.02 padding-all-sides
            {
                val emu = TerminalEmulator(
                    div(span("X")).style(_.padding(1.em).border(1.px, BorderStyle.solid, "#888")),
                    cols = 20,
                    rows = 6
                )
                save(emu, "11.02-0.png")
            }

            // 11.03 margin
            {
                val emu = TerminalEmulator(
                    div(div("M").style(_.margin(1.em).border(1.px, BorderStyle.solid, "#888"))),
                    cols = 20,
                    rows = 8
                )
                save(emu, "11.03-0.png")
            }

            // 11.04 gap
            {
                val emu = TerminalEmulator(
                    div(span("A"), span("B"), span("C")).style(_.gap(2.em)),
                    cols = 30,
                    rows = 10
                )
                save(emu, "11.04-0.png")
            }

            // 11.05 flex-grow
            {
                val emu = TerminalEmulator(
                    div(
                        div("grow").style(_.flexGrow(1).bg("#336")),
                        div("fix").style(_.bg("#633"))
                    ).style(_.row),
                    cols = 40,
                    rows = 3
                )
                save(emu, "11.05-0.png")
            }

            // 11.06 flex-shrink
            {
                val emu = TerminalEmulator(
                    div(
                        div("AAAAAA").style(_.flexShrink(0)),
                        div("BBBBBB").style(_.flexShrink(1))
                    ).style(_.row),
                    cols = 10,
                    rows = 3
                )
                save(emu, "11.06-0.png")
            }

            // 11.07 flex-wrap
            {
                val emu = TerminalEmulator(
                    div(span("AAAA"), span("BBBB"), span("CCCC"))
                        .style(_.row.flexWrap(FlexWrap.wrap).width(10.em)),
                    cols = 15,
                    rows = 6
                )
                save(emu, "11.07-0.png")
            }

            // 11.08 justify-between
            {
                val emu = TerminalEmulator(
                    div(span("L"), span("R")).style(_.row.justify(Justification.spaceBetween)),
                    cols = 30,
                    rows = 3
                )
                save(emu, "11.08-0.png")
            }

            // 11.09 justify-center
            {
                val emu = TerminalEmulator(
                    div(span("CENTER")).style(_.row.justify(Justification.center)),
                    cols = 30,
                    rows = 3
                )
                save(emu, "11.09-0.png")
            }

            // 11.10 align-center
            {
                val emu = TerminalEmulator(
                    div(span("mid")).style(_.row.align(Alignment.center).height(5.em)
                        .border(1.px, BorderStyle.solid, "#888")),
                    cols = 20,
                    rows = 7
                )
                save(emu, "11.10-0.png")
            }

            // 11.11 width-height-constraint
            {
                val emu = TerminalEmulator(
                    div("box").style(_.width(10.em).height(3.em).border(1.px, BorderStyle.solid, "#888")),
                    cols = 30,
                    rows = 8
                )
                save(emu, "11.11-0.png")
            }

            // 11.12 overflow-hidden-clips
            {
                val emu = TerminalEmulator(
                    div(span("ABCDEFGHIJKLMNOP")).style(_.width(8.em).overflow(Overflow.hidden)),
                    cols = 20,
                    rows = 5
                )
                save(emu, "11.12-0.png")
            }

            // 11.13 overflow-scroll-indicators
            {
                val emu = TerminalEmulator(
                    div(
                        span("Line 1"),
                        span("Line 2"),
                        span("Line 3"),
                        span("Line 4"),
                        span("Line 5"),
                        span("Line 6"),
                        span("Line 7"),
                        span("Line 8")
                    ).style(_.overflow(Overflow.scroll).height(3.em)),
                    cols = 30,
                    rows = 6
                )
                save(emu, "11.13-0.png")
                emu.tab()
                emu.scrollDown(5, 1)
                emu.scrollDown(5, 1)
                emu.scrollDown(5, 1)
                save(emu, "11.13-1.png")
            }

            java.lang.System.err.println("[QA] === Section 12: Styling (12 tests) ===")
            // === Section 12: Styling ===

            // 12.01 text-transform-uppercase
            {
                val emu = TerminalEmulator(
                    span("hello world").style(_.textTransform(TextTransform.uppercase)),
                    cols = 30,
                    rows = 3
                )
                save(emu, "12.01-0.png")
            }

            // 12.02 text-transform-capitalize
            {
                val emu = TerminalEmulator(
                    span("hello world").style(_.textTransform(TextTransform.capitalize)),
                    cols = 30,
                    rows = 3
                )
                save(emu, "12.02-0.png")
            }

            // 12.03 text-align-center
            {
                val emu = TerminalEmulator(
                    div("centered").style(_.textAlign(TextAlign.center).width(20.em)
                        .border(1.px, BorderStyle.solid, "#888")),
                    cols = 25,
                    rows = 5
                )
                save(emu, "12.03-0.png")
            }

            // 12.04 text-align-right
            {
                val emu = TerminalEmulator(
                    div("right").style(_.textAlign(TextAlign.right).width(20.em)
                        .border(1.px, BorderStyle.solid, "#888")),
                    cols = 25,
                    rows = 5
                )
                save(emu, "12.04-0.png")
            }

            // 12.05 text-ellipsis
            {
                val emu = TerminalEmulator(
                    div("This text is too long").style(_.width(10.em).textWrap(TextWrap.ellipsis)),
                    cols = 15,
                    rows = 3
                )
                save(emu, "12.05-0.png")
            }

            // 12.06 display-none
            {
                val emu = TerminalEmulator(
                    div(span("A"), span("B").style(_.displayNone), span("C")),
                    cols = 30,
                    rows = 5
                )
                save(emu, "12.06-0.png")
            }

            // 12.07 border-solid
            {
                val emu = TerminalEmulator(
                    div("solid").style(_.border(1.px, BorderStyle.solid, "#888")),
                    cols = 20,
                    rows = 5
                )
                save(emu, "12.07-0.png")
            }

            // 12.08 border-dashed
            {
                val emu = TerminalEmulator(
                    div("dashed").style(_.border(1.px, BorderStyle.dashed, "#888")),
                    cols = 20,
                    rows = 5
                )
                save(emu, "12.08-0.png")
            }

            // 12.09 border-dotted
            {
                val emu = TerminalEmulator(
                    div("dotted").style(_.border(1.px, BorderStyle.dotted, "#888")),
                    cols = 20,
                    rows = 5
                )
                save(emu, "12.09-0.png")
            }

            // 12.10 border-rounded
            {
                val emu = TerminalEmulator(
                    div("round").style(_.border(1.px, BorderStyle.solid, "#888").rounded(1.px)),
                    cols = 20,
                    rows = 5
                )
                save(emu, "12.10-0.png")
            }

            // 12.11 partial-border
            {
                val emu = TerminalEmulator(
                    div("top only").style(_.borderTop(1.px, "#888").borderStyle(BorderStyle.solid)),
                    cols = 20,
                    rows = 5
                )
                save(emu, "12.11-0.png")
            }

            // 12.12 background-color
            {
                val emu = TerminalEmulator(
                    div(span("colored")).style(_.bg("#336")),
                    cols = 20,
                    rows = 3
                )
                save(emu, "12.12-0.png")
            }

            java.lang.System.err.println("[QA] === Section 13: Focus & Navigation (7 tests) ===")
            // === Section 13: Focus & Navigation ===

            // 13.01 tab-through-buttons
            {
                val emu = TerminalEmulator(
                    div(button("A"), button("B"), button("C")),
                    cols = 30,
                    rows = 12
                )
                save(emu, "13.01-0.png")
                emu.tab()
                save(emu, "13.01-1.png")
                emu.tab()
                save(emu, "13.01-2.png")
                emu.tab()
                save(emu, "13.01-3.png")
            }

            // 13.02 shift-tab
            {
                val emu = TerminalEmulator(
                    div(button("A"), button("B"), button("C")),
                    cols = 30,
                    rows = 12
                )
                emu.tab()
                emu.tab()
                save(emu, "13.02-0.png")
                emu.shiftTab()
                save(emu, "13.02-1.png")
            }

            // 13.03 tab-wrap-around
            {
                val emu = TerminalEmulator(
                    div(button("X"), button("Y")),
                    cols = 30,
                    rows = 10
                )
                emu.tab()
                save(emu, "13.03-0.png")
                emu.tab()
                save(emu, "13.03-1.png")
                emu.tab()
                save(emu, "13.03-2.png")
            }

            // 13.04 click-focuses-input
            {
                val emu = TerminalEmulator(
                    div(input.placeholder("First"), input.placeholder("Second")),
                    cols = 30,
                    rows = 8
                )
                // Click on second input by position (row ~4, inside its border)
                emu.click(5, 4)
                save(emu, "13.04-0.png")
            }

            // 13.05 form-submit-enter
            {
                val v         = mkSig("")
                val submitted = mkSig(false)
                val emu = TerminalEmulator(
                    form(
                        input.value(v),
                        span(submitted.map(s => s"sent=$s"))
                    ).onSubmit(submitted.set(true)),
                    cols = 30,
                    rows = 8
                )
                save(emu, "13.05-0.png")
                emu.tab()
                emu.enter()
                emu.waitForEffects()
                save(emu, "13.05-1.png")
            }

            // 13.06 negative-tabindex-skip
            {
                val emu = TerminalEmulator(
                    div(button("A"), button("B").tabIndex(-1), button("C")),
                    cols = 30,
                    rows = 12
                )
                emu.tab()
                save(emu, "13.06-0.png")
                emu.tab()
                save(emu, "13.06-1.png")
            }

            // 13.07 focus-blur-events
            {
                val focusLog = mkSig("")
                val v1       = mkSig("")
                val v2       = mkSig("")
                val emu = TerminalEmulator(
                    div(
                        input.value(v1)
                            .onFocus(focusLog.set("focused"))
                            .onBlur(focusLog.set("blurred")),
                        input.value(v2),
                        span(focusLog.map(s => s"event=$s"))
                    ),
                    cols = 30,
                    rows = 10
                )
                emu.tab()
                emu.waitForEffects()
                save(emu, "13.07-0.png")
                emu.tab()
                emu.waitForEffects()
                save(emu, "13.07-1.png")
            }

            java.lang.System.err.println("[QA] === Section 14: Reactive & Dynamic (5 tests) ===")
            // === Section 14: Reactive & Dynamic ===

            // 14.01 signal-text-update
            {
                val count = mkSig(0)
                val emu = TerminalEmulator(
                    div(
                        span(count.map(c => s"Count: $c")),
                        button("+").onClick(count.getAndUpdate(_ + 1).unit)
                    ),
                    cols = 30,
                    rows = 8
                )
                save(emu, "14.01-0.png")
                emu.clickOn("+")
                emu.waitForEffects()
                save(emu, "14.01-1.png")
            }

            // 14.02 signal-style-update
            {
                val toggled = mkSig(false)
                val emu = TerminalEmulator(
                    div(
                        button("Toggle").onClick(toggled.getAndUpdate(!_).unit)
                            .style(toggled.map(t =>
                                if t then Style.bg("#633") else Style.bg("#336")
                            ))
                    ),
                    cols = 30,
                    rows = 5
                )
                save(emu, "14.02-0.png")
                emu.clickOn("Toggle")
                emu.waitForEffects()
                save(emu, "14.02-1.png")
            }

            // 14.03 foreach-list
            {
                val items = mkSig(Chunk("Alpha", "Beta", "Gamma"))
                val emu = TerminalEmulator(
                    ul(items.foreach(item => li(item))),
                    cols = 30,
                    rows = 8
                )
                save(emu, "14.03-0.png")
            }

            // 14.04 when-conditional
            {
                val show = mkSig(true)
                val emu = TerminalEmulator(
                    div(when(show)(span("VISIBLE")), span("always")),
                    cols = 30,
                    rows = 5
                )
                save(emu, "14.04-0.png")
            }

            // 14.05 reactive-hidden
            {
                val hide = mkSig(false)
                val emu = TerminalEmulator(
                    div(
                        span("A").hidden(hide),
                        span("B"),
                        button("Toggle").onClick(hide.getAndUpdate(!_).unit)
                    ),
                    cols = 30,
                    rows = 8
                )
                save(emu, "14.05-0.png")
                emu.clickOn("Toggle")
                emu.waitForEffects()
                save(emu, "14.05-1.png")
            }

            java.lang.System.err.println("[QA] === Section 15: Edge Cases & Stress (8 tests) ===")
            // === Section 15: Edge Cases & Stress ===

            // 15.01 empty-div
            {
                val emu = TerminalEmulator(div(), cols = 20, rows = 5)
                save(emu, "15.01-0.png")
            }

            // 15.02 fifteen-children
            {
                val emu = TerminalEmulator(
                    div(
                        span("Item 01"),
                        span("Item 02"),
                        span("Item 03"),
                        span("Item 04"),
                        span("Item 05"),
                        span("Item 06"),
                        span("Item 07"),
                        span("Item 08"),
                        span("Item 09"),
                        span("Item 10"),
                        span("Item 11"),
                        span("Item 12"),
                        span("Item 13"),
                        span("Item 14"),
                        span("Item 15")
                    ),
                    cols = 30,
                    rows = 18
                )
                save(emu, "15.02-0.png")
            }

            // 15.03 deep-nesting-with-borders
            {
                val bs = Style.border(1.px, BorderStyle.solid, "#888")
                val emu = TerminalEmulator(
                    div(div(div(div("deep").style(bs)).style(bs)).style(bs)).style(bs),
                    cols = 30,
                    rows = 12
                )
                save(emu, "15.03-0.png")
            }

            // 15.04 mixed-siblings-complex
            {
                val emu = TerminalEmulator(
                    div(
                        h2("Title"),
                        p("text"),
                        div(button("A"), button("B")).style(_.row),
                        hr,
                        span("footer")
                    ),
                    cols = 40,
                    rows = 14
                )
                save(emu, "15.04-0.png")
            }

            // 15.05 input-in-bordered-div
            {
                val emu = TerminalEmulator(
                    div(label("Name"), input.placeholder("type..."))
                        .style(_.border(1.px, BorderStyle.solid, "#888").padding(1.em)),
                    cols = 30,
                    rows = 8
                )
                save(emu, "15.05-0.png")
            }

            // 15.06 form-with-multiple-inputs
            {
                val n     = mkSig("")
                val e     = mkSig("")
                val agree = mkSig(false)
                val role  = mkSig("dev")
                val emu = TerminalEmulator(
                    form(
                        label("Name:"),
                        input.value(n).placeholder("name"),
                        label("Email:"),
                        input.value(e).placeholder("email"),
                        checkbox.checked(agree),
                        span("I agree"),
                        select.value(role)(
                            option("Developer").value("dev"),
                            option("Designer").value("des")
                        ),
                        button("Submit")
                    ),
                    cols = 40,
                    rows = 16
                )
                save(emu, "15.06-0.png")
            }

            // 15.07 select-no-options
            {
                val emu = TerminalEmulator(select(), cols = 20, rows = 5)
                emu.tab()
                emu.key(UI.Keyboard.ArrowDown)
                emu.key(UI.Keyboard.ArrowUp)
                save(emu, "15.07-0.png")
            }

            // 15.08 simultaneous-layout-bugs
            {
                val emu = TerminalEmulator(
                    div(
                        div(span("A"), span("B")).style(_.row),
                        div(span("C"), span("D")).style(_.row)
                    ),
                    cols = 30,
                    rows = 5
                )
                save(emu, "15.08-0.png")
            }

            java.lang.System.err.println(s"[QA] === DONE: All 105 screenshots generated in $dir ===")
            assert(true, "All screenshots generated")
    }

end TerminalEmulatorQA
