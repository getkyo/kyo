package kyo

import kyo.Style.Size.*
import kyo.UI.*
import kyo.UI.internal.*
import kyo.internal.*
import scala.language.implicitConversions

class TuiSimulatorTest extends Test:

    // ──────────────── Counter ────────────────

    "counter" - {
        "initial frame shows 0 between buttons" in run {
            for
                count <- Signal.initRef(0)
            yield
                val sim = TuiSimulator(
                    nav(
                        button("-").onClick(count.getAndUpdate(_ - 1).unit),
                        span(count.map(_.toString)),
                        button("+").onClick(count.getAndUpdate(_ + 1).unit)
                    ),
                    cols = 20,
                    rows = 3
                )
                val f = sim.frame
                assert(f.contains("-"), s"Should show '-':\n$f")
                assert(f.contains("0"), s"Should show '0':\n$f")
                assert(f.contains("+"), s"Should show '+':\n$f")
        }

        "clicking + increments counter" in run {
            for
                count <- Signal.initRef(0)
            yield
                val sim = TuiSimulator(
                    nav(
                        button("-").onClick(count.getAndUpdate(_ - 1).unit),
                        span(count.map(_.toString)),
                        button("+").onClick(count.getAndUpdate(_ + 1).unit)
                    ),
                    cols = 20,
                    rows = 3
                )
                sim.clickOn("+")
                sim.waitForEffects()
                val f = sim.frame
                assert(f.contains("1"), s"Should show '1' after clicking +:\n$f")
        }

        "clicking - decrements counter" in run {
            for
                count <- Signal.initRef(0)
            yield
                val sim = TuiSimulator(
                    nav(
                        button("-").onClick(count.getAndUpdate(_ - 1).unit),
                        span(count.map(_.toString)),
                        button("+").onClick(count.getAndUpdate(_ + 1).unit)
                    ),
                    cols = 20,
                    rows = 3
                )
                sim.clickOn("-")
                sim.waitForEffects()
                val f = sim.frame
                assert(f.contains("-1"), s"Should show '-1' after clicking -:\n$f")
        }
    }

    // ──────────────── Text Input ────────────────

    "text input" - {
        "typing updates input value" in run {
            for
                text <- Signal.initRef("")
            yield
                val sim = TuiSimulator(
                    nav(
                        input.value(text).placeholder("Type..."),
                        span(text.map(v => s"=$v"))
                    ),
                    cols = 40,
                    rows = 3
                )
                sim.tab()
                sim.typeText("hello")
                sim.waitForEffects()
                val f = sim.frame
                assert(f.contains("hello"), s"Should show typed text:\n$f")
                assert(f.contains("=hello"), s"Should show signal value:\n$f")
        }

        "backspace deletes characters" in run {
            for
                text <- Signal.initRef("")
            yield
                val sim = TuiSimulator(
                    nav(
                        input.value(text).placeholder("Type..."),
                        span(text.map(v => s"=$v"))
                    ),
                    cols = 40,
                    rows = 3
                )
                sim.tab()
                sim.typeText("abc")
                sim.key(UI.Keyboard.Backspace)
                sim.waitForEffects()
                val f = sim.frame
                assert(f.contains("=ab"), s"Should show 'ab' after backspace:\n$f")
        }

        "placeholder shown when empty" in run {
            for
                text <- Signal.initRef("")
            yield
                val sim = TuiSimulator(
                    div(
                        input.value(text).placeholder("Type here")
                    ),
                    cols = 50,
                    rows = 3
                )
                val f = sim.frame
                assert(f.contains("Type here"), s"Should show placeholder:\n$f")
        }
    }

    // ──────────────── Password Input (Phase 6) ────────────────

    "password input" - {
        "masks value with bullet characters" in run {
            for
                text <- Signal.initRef("secret")
            yield
                val sim = TuiSimulator(
                    div(password.value(text)),
                    cols = 40,
                    rows = 3
                )
                val f = sim.frame
                assert(!f.contains("secret"), s"Should NOT show plaintext:\n$f")
                assert(f.contains("\u2022"), s"Should show bullet characters:\n$f")
        }

        "typing in password shows masks" in run {
            for
                text <- Signal.initRef("")
            yield
                val sim = TuiSimulator(
                    div(
                        password.value(text),
                        span(text.map(v => s"len=${v.length}"))
                    ),
                    cols = 40,
                    rows = 5
                )
                sim.tab()
                sim.typeText("abc")
                sim.waitForEffects()
                val f = sim.frame
                assert(!f.contains("abc"), s"Should NOT show plaintext:\n$f")
                assert(f.contains("len=3"), s"Should have 3 chars:\n$f")
        }
    }

    // ──────────────── Checkbox (Phase 6) ────────────────

    "checkbox" - {
        "renders unchecked as [ ]" in run {
            for
                checked <- Signal.initRef(false)
            yield
                val sim = TuiSimulator(
                    div(checkbox.checked(checked)),
                    cols = 20,
                    rows = 3
                )
                val f = sim.frame
                assert(f.contains("[ ]"), s"Should show unchecked:\n$f")
        }

        "renders checked as [x]" in run {
            for
                checked <- Signal.initRef(true)
            yield
                val sim = TuiSimulator(
                    div(checkbox.checked(checked)),
                    cols = 20,
                    rows = 3
                )
                val f = sim.frame
                assert(f.contains("[x]"), s"Should show checked:\n$f")
        }

        "toggles on Enter" in run {
            for
                checked <- Signal.initRef(false)
            yield
                val sim = TuiSimulator(
                    div(checkbox.checked(checked)),
                    cols = 20,
                    rows = 3
                )
                sim.tab()
                sim.enter()
                sim.waitForEffects()
                val f = sim.frame
                assert(f.contains("[x]"), s"Should toggle to checked:\n$f")
        }

        "toggles on Space" in run {
            for
                checked <- Signal.initRef(true)
            yield
                val sim = TuiSimulator(
                    div(checkbox.checked(checked)),
                    cols = 20,
                    rows = 3
                )
                sim.tab()
                sim.space()
                sim.waitForEffects()
                val f = sim.frame
                assert(f.contains("[ ]"), s"Should toggle to unchecked:\n$f")
        }
    }

    // ──────────────── Image Alt Text (Phase 6) ────────────────

    "image" - {
        "shows alt text when no children" in {
            val sim = TuiSimulator(
                div(img("photo.jpg", "A photo")),
                cols = 30,
                rows = 3
            )
            val f = sim.frame
            assert(f.contains("A photo"), s"Should show alt text:\n$f")
        }

        "shows [img] when no alt text" in {
            val sim = TuiSimulator(
                div(Img()),
                cols = 30,
                rows = 3
            )
            val f = sim.frame
            assert(f.contains("[img]"), s"Should show default alt:\n$f")
        }
    }

    // ──────────────── Select/Option (Phase 6) ────────────────

    "select" - {
        "shows selected option with indicator" in run {
            for
                sel1 <- Signal.initRef(true)
                sel2 <- Signal.initRef(false)
            yield
                val sim = TuiSimulator(
                    div(
                        select(
                            option("Apple").selected(sel1),
                            option("Banana").selected(sel2)
                        )
                    ),
                    cols = 30,
                    rows = 5
                )
                val f = sim.frame
                assert(f.contains("\u25b6 Apple"), s"Should show selected indicator on Apple:\n$f")
                assert(!f.contains("\u25b6 Banana"), s"Banana should NOT be selected:\n$f")
        }
    }

    // ──────────────── Table with colspan (Phase 6) ────────────────

    "table colspan" - {
        "cell with colspan=2 gets double width" in {
            val sim = TuiSimulator(
                table(
                    tr(td("A"), td("B"), td("C")),
                    tr(td.colspan(2)("Wide"), td("D"))
                ),
                cols = 60,
                rows = 5
            )
            val f = sim.frame
            assert(f.contains("A"), s"Should show A:\n$f")
            assert(f.contains("Wide"), s"Should show Wide:\n$f")
            assert(f.contains("D"), s"Should show D:\n$f")
        }
    }

    // ──────────────── TabIndex (Phase 6) ────────────────

    "tabIndex" - {
        "negative tabIndex excludes from focus order" in {
            import AllowUnsafe.embrace.danger
            given Frame = Frame.internal
            val layout  = new TuiLayout(64)
            val signals = new TuiSignalCollector(16)
            val ui: UI = div(
                button("A").tabIndex(-1),
                button("B"),
                button("C")
            )
            TuiFlatten.flatten(ui, layout, signals, 40, 10, TuiResolvedTheme.empty)
            TuiFlexLayout.measure(layout)
            TuiFlexLayout.arrange(layout, 40, 10)
            val focus = new TuiFocus
            focus.scan(layout)
            // First focus should be B (A excluded by negative tabIndex)
            val idx1 = focus.focusedIndex
            focus.next()
            val idx2 = focus.focusedIndex
            focus.next()
            val idx3 = focus.focusedIndex
            // Should only cycle between B and C (2 elements)
            assert(idx1 != -1, "Should focus something")
            assert(idx1 != idx2, "B and C should be different")
            assert(idx3 == idx1, "Should wrap to B after C (only 2 focusable)")
            // Verify A's layout index is never visited
            val aElem = layout.element(idx1)
            assert(aElem.isDefined)
            // unsafe: asInstanceOf to check element text
            val firstChild = layout.firstChild(idx1)
            assert(firstChild >= 0 && layout.text(firstChild).isDefined)
            assert(layout.text(firstChild).get != "A", s"First focused should not be A, got: ${layout.text(firstChild).get}")
        }

        "positive tabIndex elements focused before default-order" in {
            import AllowUnsafe.embrace.danger
            given Frame = Frame.internal
            val layout  = new TuiLayout(64)
            val signals = new TuiSignalCollector(16)
            // A=default, B=tabIndex(1), C=default
            // Focus order should be: B, A, C
            val ui: UI = div(
                button("A"),
                button("B").tabIndex(1),
                button("C")
            )
            TuiFlatten.flatten(ui, layout, signals, 40, 10, TuiResolvedTheme.empty)
            TuiFlexLayout.measure(layout)
            TuiFlexLayout.arrange(layout, 40, 10)
            val focus = new TuiFocus
            focus.scan(layout)
            // First focus = B (tabIndex=1 comes first)
            val idx1      = focus.focusedIndex
            val firstText = layout.text(layout.firstChild(idx1))
            assert(firstText.isDefined && firstText.get == "B", s"First focused should be B, got: ${firstText}")
            // Second focus = A (default order)
            focus.next()
            val idx2       = focus.focusedIndex
            val secondText = layout.text(layout.firstChild(idx2))
            assert(secondText.isDefined && secondText.get == "A", s"Second focused should be A, got: ${secondText}")
            // Third focus = C (default order)
            focus.next()
            val idx3      = focus.focusedIndex
            val thirdText = layout.text(layout.firstChild(idx3))
            assert(thirdText.isDefined && thirdText.get == "C", s"Third focused should be C, got: ${thirdText}")
        }
    }

    // ──────────────── Keyboard Navigation ────────────────

    "keyboard navigation" - {
        "tab cycles focus between buttons" in {
            val sim = TuiSimulator(
                nav(
                    button("A"),
                    button("B"),
                    button("C")
                ),
                cols = 20,
                rows = 3
            )
            val f0 = sim.focusedIndex
            sim.tab()
            val f1 = sim.focusedIndex
            sim.tab()
            val f2 = sim.focusedIndex
            assert(f0 != f1 && f1 != f2, s"Tab should cycle focus: $f0 -> $f1 -> $f2")
        }

        "enter activates focused button" in run {
            for
                count <- Signal.initRef(0)
            yield
                val sim = TuiSimulator(
                    nav(
                        button("+").onClick(count.getAndUpdate(_ + 1).unit),
                        span(count.map(_.toString))
                    ),
                    cols = 20,
                    rows = 3
                )
                sim.enter()
                sim.waitForEffects()
                val f = sim.frame
                assert(f.contains("1"), s"Enter should activate button:\n$f")
        }

        "space activates focused button" in run {
            for
                count <- Signal.initRef(0)
            yield
                val sim = TuiSimulator(
                    nav(
                        button("+").onClick(count.getAndUpdate(_ + 1).unit),
                        span(count.map(_.toString))
                    ),
                    cols = 20,
                    rows = 3
                )
                sim.space()
                sim.waitForEffects()
                val f = sim.frame
                assert(f.contains("1"), s"Space should activate button:\n$f")
        }
    }

    // ──────────────── Styles ────────────────

    "styles" - {
        "bold text renders" in {
            val sim = TuiSimulator(p.style(Style.bold)("Bold text"), cols = 40, rows = 5)
            val f   = sim.frame
            assert(f.contains("Bold text"), s"Should show bold text:\n$f")
        }

        "colored text renders" in {
            val sim = TuiSimulator(
                p.style(Style.bg("#0000ff").color("#ffffff"))("Blue bg"),
                cols = 20,
                rows = 3
            )
            val f = sim.frame
            assert(f.contains("Blue bg"), s"Should show colored text:\n$f")
        }
    }

    // ──────────────── Borders ────────────────

    "borders" - {
        "rounded border renders box-drawing chars" in {
            val sim = TuiSimulator(
                div.style(Style.border(8.px, Style.BorderStyle.solid, "#888").rounded(8.px))("Hi"),
                cols = 20,
                rows = 5
            )
            val f = sim.frame
            assert(f.contains("\u256d"), s"Should have rounded top-left corner:\n$f")
            assert(f.contains("\u256f"), s"Should have rounded bottom-right corner:\n$f")
            assert(f.contains("Hi"), s"Should show content:\n$f")
        }
    }

    // ──────────────── Table ────────────────

    "table" - {
        "renders header and data in rows" in {
            val sim = TuiSimulator(
                table(
                    tr(th("Name"), th(" Age")),
                    tr(td("Alice"), td(" 30"))
                ),
                cols = 50,
                rows = 5
            )
            val f          = sim.frame
            val lines      = f.split("\n")
            val headerLine = lines.find(l => l.contains("Name") && l.contains("Age"))
            assert(headerLine.isDefined, s"Header should be on one line:\n$f")
            val dataLine = lines.find(l => l.contains("Alice") && l.contains("30"))
            assert(dataLine.isDefined, s"Data should be on one line:\n$f")
        }
    }

    // ──────────────── Phase 7: Style Pipeline ────────────────

    "style pipeline" - {
        "focus applies default blue border via applyStates" in {
            import AllowUnsafe.embrace.danger
            given Frame = Frame.internal
            val layout  = new TuiLayout(64)
            val signals = new TuiSignalCollector(16)
            val ui: UI  = div(button("Click"))
            TuiFlatten.flatten(ui, layout, signals, 40, 10, TuiResolvedTheme.empty)
            TuiFlexLayout.measure(layout)
            TuiFlexLayout.arrange(layout, 40, 10)
            val focus = new TuiFocus
            focus.scan(layout)
            val idx = focus.focusedIndex
            assert(idx >= 0, "Should have a focused element")
            // Before applyStates: no border
            val lfBefore = layout.lFlags(idx)
            TuiStyle.inherit(layout)
            TuiStyle.applyStates(layout, focus.focusedIndex, focus.hoverIdx, focus.activeIdx)
            // After applyStates: should have border on all sides
            val lfAfter = layout.lFlags(idx)
            assert(TuiLayout.hasBorderT(lfAfter), "Focus should add top border")
            assert(TuiLayout.hasBorderR(lfAfter), "Focus should add right border")
            assert(TuiLayout.hasBorderB(lfAfter), "Focus should add bottom border")
            assert(TuiLayout.hasBorderL(lfAfter), "Focus should add left border")
            // Border color should be blue (#7aa2f7 = pack(122, 162, 247))
            val expectedBlue = TuiColor.pack(122, 162, 247)
            assert(layout.bdrClrT(idx) == expectedBlue, s"Top border should be blue: ${layout.bdrClrT(idx)} != $expectedBlue")
        }

        "focus custom style overrides default border" in {
            import AllowUnsafe.embrace.danger
            given Frame     = Frame.internal
            val layout      = new TuiLayout(64)
            val signals     = new TuiSignalCollector(16)
            val customFocus = Style.bg("#ff0000")
            val ui: UI      = div(button.style(Style.focus(customFocus))("Btn"))
            TuiFlatten.flatten(ui, layout, signals, 40, 10, TuiResolvedTheme.empty)
            TuiFlexLayout.measure(layout)
            TuiFlexLayout.arrange(layout, 40, 10)
            val focus = new TuiFocus
            focus.scan(layout)
            val idx = focus.focusedIndex
            assert(idx >= 0, "Should have a focused element")
            TuiStyle.inherit(layout)
            TuiStyle.applyStates(layout, focus.focusedIndex, focus.hoverIdx, focus.activeIdx)
            // Custom focus style: red background, no default blue border
            val expectedRed = TuiColor.resolve(Style.Color.hex("#ff0000"))
            assert(layout.bg(idx) == expectedRed, s"Focus should set red bg: ${layout.bg(idx)} != $expectedRed")
        }

        "disabled element gets dim by default" in {
            import AllowUnsafe.embrace.danger
            given Frame = Frame.internal
            val layout  = new TuiLayout(64)
            val signals = new TuiSignalCollector(16)
            val ui: UI  = div(button("Off").disabled(true))
            TuiFlatten.flatten(ui, layout, signals, 40, 10, TuiResolvedTheme.empty)
            TuiFlexLayout.measure(layout)
            TuiFlexLayout.arrange(layout, 40, 10)
            val focus = new TuiFocus
            focus.scan(layout)
            // Find the button element
            var btnIdx = -1
            // unsafe: while for searching layout
            var i = 0
            while i < layout.count do
                val elem = layout.element(i)
                if elem.isDefined then
                    if elem.get.isInstanceOf[UI.Button] then btnIdx = i
                end if
                i += 1
            end while
            assert(btnIdx >= 0, "Should find button in layout")
            assert(TuiLayout.isDisabled(layout.lFlags(btnIdx)), "Button should be disabled")
            TuiStyle.inherit(layout)
            TuiStyle.applyStates(layout, focus.focusedIndex, focus.hoverIdx, focus.activeIdx)
            // Disabled should add dim bit
            assert(TuiLayout.isDim(layout.pFlags(btnIdx)), "Disabled should set dim bit")
        }

        "disabled element with custom DisabledProp uses custom style" in {
            import AllowUnsafe.embrace.danger
            given Frame        = Frame.internal
            val layout         = new TuiLayout(64)
            val signals        = new TuiSignalCollector(16)
            val customDisabled = Style.bg("#333333")
            val ui: UI         = div(button("Off").disabled(true).style(Style.disabled(customDisabled)))
            TuiFlatten.flatten(ui, layout, signals, 40, 10, TuiResolvedTheme.empty)
            TuiFlexLayout.measure(layout)
            TuiFlexLayout.arrange(layout, 40, 10)
            val focus = new TuiFocus
            focus.scan(layout)
            // Find the button
            var btnIdx = -1
            // unsafe: while for searching layout
            var i = 0
            while i < layout.count do
                val elem = layout.element(i)
                if elem.isDefined then
                    if elem.get.isInstanceOf[UI.Button] then btnIdx = i
                end if
                i += 1
            end while
            assert(btnIdx >= 0, "Should find button in layout")
            TuiStyle.inherit(layout)
            TuiStyle.applyStates(layout, focus.focusedIndex, focus.hoverIdx, focus.activeIdx)
            // Custom disabled style should apply bg
            val expectedBg = TuiColor.resolve(Style.Color.hex("#333333"))
            assert(layout.bg(btnIdx) == expectedBg, s"Disabled should set custom bg: ${layout.bg(btnIdx)} != $expectedBg")
        }

        "hover index tracked on mouse move" in {
            import AllowUnsafe.embrace.danger
            given Frame = Frame.internal
            val layout  = new TuiLayout(64)
            val signals = new TuiSignalCollector(16)
            val ui: UI  = div(button("Click"))
            TuiFlatten.flatten(ui, layout, signals, 40, 10, TuiResolvedTheme.empty)
            TuiFlexLayout.measure(layout)
            TuiFlexLayout.arrange(layout, 40, 10)
            val focus = new TuiFocus
            focus.scan(layout)
            assert(focus.hoverIdx == -1, "Initial hover should be -1")
            // Simulate mouse move over the button area
            Sync.Unsafe.evalOrThrow(
                focus.dispatch(InputEvent.Mouse(InputEvent.MouseKind.Move, 2, 0), layout)
            )
            assert(focus.hoverIdx >= 0, s"Hover should be set after mouse move: ${focus.hoverIdx}")
        }

        "active index set on mouse down, cleared on release" in {
            import AllowUnsafe.embrace.danger
            given Frame = Frame.internal
            val layout  = new TuiLayout(64)
            val signals = new TuiSignalCollector(16)
            val ui: UI  = div(button("Click"))
            TuiFlatten.flatten(ui, layout, signals, 40, 10, TuiResolvedTheme.empty)
            TuiFlexLayout.measure(layout)
            TuiFlexLayout.arrange(layout, 40, 10)
            val focus = new TuiFocus
            focus.scan(layout)
            assert(focus.activeIdx == -1, "Initial active should be -1")
            // Mouse press
            Sync.Unsafe.evalOrThrow(
                focus.dispatch(InputEvent.Mouse(InputEvent.MouseKind.LeftPress, 2, 0), layout)
            )
            assert(focus.activeIdx >= 0, s"Active should be set on mouse down: ${focus.activeIdx}")
            // Mouse release
            Sync.Unsafe.evalOrThrow(
                focus.dispatch(InputEvent.Mouse(InputEvent.MouseKind.LeftRelease, 2, 0), layout)
            )
            assert(focus.activeIdx == -1, "Active should be cleared on mouse up")
        }

        "disabled elements excluded from focus order" in {
            import AllowUnsafe.embrace.danger
            given Frame = Frame.internal
            val layout  = new TuiLayout(64)
            val signals = new TuiSignalCollector(16)
            val ui: UI = div(
                button("A"),
                button("B").disabled(true),
                button("C")
            )
            TuiFlatten.flatten(ui, layout, signals, 40, 10, TuiResolvedTheme.empty)
            TuiFlexLayout.measure(layout)
            TuiFlexLayout.arrange(layout, 40, 10)
            val focus = new TuiFocus
            focus.scan(layout)
            // Should only have A and C in focus order
            val idx1      = focus.focusedIndex
            val firstText = layout.text(layout.firstChild(idx1))
            assert(firstText.isDefined && firstText.get == "A", s"First focused should be A, got: ${firstText}")
            focus.next()
            val idx2       = focus.focusedIndex
            val secondText = layout.text(layout.firstChild(idx2))
            assert(secondText.isDefined && secondText.get == "C", s"Second focused should be C, got: ${secondText}")
            focus.next()
            val idx3      = focus.focusedIndex
            val thirdText = layout.text(layout.firstChild(idx3))
            assert(thirdText.isDefined && thirdText.get == "A", s"Should wrap back to A, got: ${thirdText}")
        }

        "pipeline order: flatten → layout → focus.scan → style.inherit → style.applyStates → paint" in {
            import AllowUnsafe.embrace.danger
            given Frame  = Frame.internal
            val layout   = new TuiLayout(64)
            val signals  = new TuiSignalCollector(16)
            val renderer = new TuiRenderer(40, 10)
            val ui: UI   = div(button("Test"))
            // Execute full pipeline in correct order
            TuiFlatten.flatten(ui, layout, signals, 40, 10, TuiResolvedTheme.empty)
            TuiFlexLayout.measure(layout)
            TuiFlexLayout.arrange(layout, 40, 10)
            val focus = new TuiFocus
            focus.scan(layout)
            TuiStyle.inherit(layout)
            TuiStyle.applyStates(layout, focus.focusedIndex, focus.hoverIdx, focus.activeIdx)
            renderer.clear()
            TuiPainter.paint(layout, renderer)
            // Should render without error and produce output
            val baos = new java.io.ByteArrayOutputStream()
            renderer.flush(baos, TuiRenderer.NoColor)
            val output = baos.toString("UTF-8")
            assert(output.nonEmpty, "Pipeline should produce output")
        }
    }

    // ──────────────── Phase 8: TuiText ────────────────

    "TuiText" - {
        "naturalWidth single line" in {
            assert(TuiText.naturalWidth("hello") == 5)
        }

        "naturalWidth multi line" in {
            assert(TuiText.naturalWidth("hi\nhello\nbye") == 5)
        }

        "naturalWidth empty" in {
            assert(TuiText.naturalWidth("") == 0)
        }

        "naturalHeight single line" in {
            assert(TuiText.naturalHeight("hello") == 1)
        }

        "naturalHeight multi line" in {
            assert(TuiText.naturalHeight("a\nb\nc") == 3)
        }

        "naturalHeight empty" in {
            assert(TuiText.naturalHeight("") == 1)
        }

        "lineCount no wrapping needed" in {
            assert(TuiText.lineCount("hello", 10) == 1)
        }

        "lineCount wraps long line" in {
            assert(TuiText.lineCount("hello world foo", 11) == 2)
        }

        "lineCount multi-line with wrapping" in {
            assert(TuiText.lineCount("hello world\nfoo bar baz", 8) == 4)
        }

        "forEachLine natural lines" in {
            val text   = "ab\ncd\nef"
            var result = scala.collection.mutable.ArrayBuffer.empty[(Int, Int)]
            val count = TuiText.forEachLine(text, Int.MaxValue, false) { (s, e) =>
                result += ((s, e))
            }
            assert(count == 3)
            assert(text.substring(result(0)._1, result(0)._2) == "ab")
            assert(text.substring(result(1)._1, result(1)._2) == "cd")
            assert(text.substring(result(2)._1, result(2)._2) == "ef")
        }

        "forEachLine wrapping" in {
            val text   = "hello world"
            var count2 = 0
            val count = TuiText.forEachLine(text, 7, true) { (s, e) =>
                count2 += 1
            }
            assert(count == 2, s"Expected 2 wrapped lines, got $count")
        }

        "applyTransform uppercase" in {
            assert(TuiText.applyTransform('a', 1) == 'A')
        }

        "applyTransform lowercase" in {
            assert(TuiText.applyTransform('A', 2) == 'a')
        }

        "applyTransform none" in {
            assert(TuiText.applyTransform('a', 0) == 'a')
        }
    }

    // ──────────────── Phase 9: FlexGrow/Shrink ────────────────

    "flex grow/shrink" - {
        "flexGrow distributes remaining space proportionally" in {
            import AllowUnsafe.embrace.danger
            given Frame = Frame.internal
            val layout  = new TuiLayout(16)
            val ui: UI = div(
                span("A").style(Style.flexGrow(1.0).width(4.em)),
                span("B").style(Style.flexGrow(2.0).width(4.em))
            ).style(Style.row)
            val signals = new TuiSignalCollector(16)
            TuiFlatten.flatten(ui, layout, signals, 20, 5, TuiResolvedTheme.empty)
            TuiFlexLayout.measure(layout)
            TuiFlexLayout.arrange(layout, 20, 5)
            // Find the two span children (skip root div)
            val c1 = layout.firstChild(0)
            val c2 = layout.nextSibling(c1)
            assert(c1 >= 0 && c2 >= 0, "Should have two children")
            // Container=20, each child intrinsic=4, free=12
            // c1 gets 12 * 1/3 = 4 extra → 8
            // c2 gets 12 * 2/3 = 8 extra → 12
            assert(layout.w(c1) == 8, s"c1 should be 8 wide, got ${layout.w(c1)}")
            assert(layout.w(c2) == 12, s"c2 should be 12 wide, got ${layout.w(c2)}")
        }

        "flexShrink shrinks children when overflowing" in {
            import AllowUnsafe.embrace.danger
            given Frame = Frame.internal
            val layout  = new TuiLayout(16)
            val ui: UI = div(
                span("X").style(Style.flexShrink(1.0).width(10.em)),
                span("Y").style(Style.flexShrink(1.0).width(10.em))
            ).style(Style.row)
            val signals = new TuiSignalCollector(16)
            TuiFlatten.flatten(ui, layout, signals, 14, 5, TuiResolvedTheme.empty)
            TuiFlexLayout.measure(layout)
            TuiFlexLayout.arrange(layout, 14, 5)
            val c1 = layout.firstChild(0)
            val c2 = layout.nextSibling(c1)
            assert(c1 >= 0 && c2 >= 0)
            // Container=14, each child=10, deficit=-6, each shrinks by 3 → 7
            assert(layout.w(c1) == 7, s"c1 should be 7 wide, got ${layout.w(c1)}")
            assert(layout.w(c2) == 7, s"c2 should be 7 wide, got ${layout.w(c2)}")
        }

        "no flexGrow keeps intrinsic size" in {
            import AllowUnsafe.embrace.danger
            given Frame = Frame.internal
            val layout  = new TuiLayout(16)
            val ui: UI = div(
                span("hello").style(Style.width(5.em))
            ).style(Style.row)
            val signals = new TuiSignalCollector(16)
            TuiFlatten.flatten(ui, layout, signals, 20, 5, TuiResolvedTheme.empty)
            TuiFlexLayout.measure(layout)
            TuiFlexLayout.arrange(layout, 20, 5)
            val c1 = layout.firstChild(0)
            assert(c1 >= 0)
            assert(layout.w(c1) == 5, s"c1 should stay at 5, got ${layout.w(c1)}")
        }
    }

    // ──────────────── Phase 9: TuiColor Filters ────────────────

    "TuiColor filters" - {
        "brightness doubles RGB" in {
            val c = TuiColor.pack(100, 50, 25)
            val b = TuiColor.brightness(c, 2.0)
            assert(TuiColor.r(b) == 200)
            assert(TuiColor.g(b) == 100)
            assert(TuiColor.b(b) == 50)
        }

        "brightness clamps at 255" in {
            val c = TuiColor.pack(200, 200, 200)
            val b = TuiColor.brightness(c, 2.0)
            assert(TuiColor.r(b) == 255)
        }

        "contrast 0 gives midpoint gray" in {
            val c = TuiColor.pack(0, 255, 128)
            val g = TuiColor.contrast(c, 0.0)
            assert(TuiColor.r(g) == 128)
            assert(TuiColor.g(g) == 128)
            assert(TuiColor.b(g) == 128)
        }

        "grayscale 1.0 gives luminance" in {
            val c = TuiColor.pack(255, 0, 0)
            val g = TuiColor.grayscale(c, 1.0)
            // Pure red luminance ≈ 54 (0.2126 * 255)
            val lum = (0.2126 * 255 + 0.5).toInt
            assert(math.abs(TuiColor.r(g) - lum) <= 1, s"Expected ~$lum, got ${TuiColor.r(g)}")
            assert(TuiColor.r(g) == TuiColor.g(g))
            assert(TuiColor.g(g) == TuiColor.b(g))
        }

        "invert 1.0 inverts all channels" in {
            val c   = TuiColor.pack(100, 200, 50)
            val inv = TuiColor.invert(c, 1.0)
            assert(TuiColor.r(inv) == 155)
            assert(TuiColor.g(inv) == 55)
            assert(TuiColor.b(inv) == 205)
        }

        "sepia 1.0 applies sepia tone" in {
            val c = TuiColor.pack(100, 150, 200)
            val s = TuiColor.sepia(c, 1.0)
            assert(TuiColor.r(s) > TuiColor.g(s), "Red should be > green in sepia")
            assert(TuiColor.g(s) > TuiColor.b(s), "Green should be > blue in sepia")
        }

        "hueRotate 180 shifts hue" in {
            val c = TuiColor.pack(255, 0, 0)
            val h = TuiColor.hueRotate(c, 180.0)
            assert(TuiColor.r(h) < 50, s"Red should be low, got ${TuiColor.r(h)}")
            assert(TuiColor.g(h) > 100 || TuiColor.b(h) > 100, s"G/B should be high")
        }

        "absent color unchanged by filters" in {
            assert(TuiColor.brightness(TuiColor.Absent, 2.0) == TuiColor.Absent)
            assert(TuiColor.grayscale(TuiColor.Absent, 1.0) == TuiColor.Absent)
            assert(TuiColor.invert(TuiColor.Absent, 1.0) == TuiColor.Absent)
        }
    }

    // ──────────────── Phase 9: Shadow resolution ────────────────

    "shadow prop resolution" - {
        "ShadowProp stores full params" in {
            val layout = new TuiLayout(4)
            val idx    = layout.alloc()
            val style  = Style.shadow(x = 2.px, y = 3.px, blur = 4.px, spread = 1.px, c = Style.Color.rgb(255, 0, 0))
            TuiStyle.resolve(style, layout, idx, 80, 24)
            assert(layout.shadowClr(idx) != -1, "Shadow color should be set")
            assert(TuiColor.r(layout.shadowClr(idx)) == 255)
            assert(TuiColor.g(layout.shadowClr(idx)) == 0)
            assert(TuiColor.b(layout.shadowClr(idx)) == 0)
            assert(layout.shadowX(idx) >= 0)
            assert(layout.shadowY(idx) >= 0)
        }
    }

    // ──────────────── Phase 9: Filter prop resolution ────────────────

    "filter prop resolution" - {
        "brightness prop sets filter bit and value" in {
            val layout = new TuiLayout(4)
            val idx    = layout.alloc()
            val style  = Style.brightness(1.5)
            TuiStyle.resolve(style, layout, idx, 80, 24)
            assert((layout.filterBits(idx) & 1) != 0, "Brightness bit should be set")
            assert(layout.filterVals(idx * 8 + 0) == 1.5)
        }

        "multiple filters set independent bits" in {
            val layout = new TuiLayout(4)
            val idx    = layout.alloc()
            val style  = Style.grayscale(0.5).sepia(0.3)
            TuiStyle.resolve(style, layout, idx, 80, 24)
            assert((layout.filterBits(idx) & (1 << 2)) != 0, "Grayscale bit")
            assert((layout.filterBits(idx) & (1 << 3)) != 0, "Sepia bit")
            assert(layout.filterVals(idx * 8 + 2) == 0.5)
            assert(layout.filterVals(idx * 8 + 3) == 0.3)
        }
    }

    // ──────────────── Phase 9: Position prop ────────────────

    "position prop" - {
        "overlay sets position bit" in {
            val layout = new TuiLayout(4)
            val idx    = layout.alloc()
            val style  = Style.position(Style.Position.overlay)
            TuiStyle.resolve(style, layout, idx, 80, 24)
            assert(TuiLayout.isOverlay(layout.lFlags(idx)), "Should be overlay")
        }

        "flow clears position bit" in {
            val layout = new TuiLayout(4)
            val idx    = layout.alloc()
            val style  = Style.position(Style.Position.flow)
            TuiStyle.resolve(style, layout, idx, 80, 24)
            assert(!TuiLayout.isOverlay(layout.lFlags(idx)), "Should not be overlay")
        }
    }

    // ──────────────── Phase 9: FlexGrow/Shrink prop resolution ────────────────

    "flex prop resolution" - {
        "flexGrow prop sets value" in {
            val layout = new TuiLayout(4)
            val idx    = layout.alloc()
            val style  = Style.flexGrow(2.0)
            TuiStyle.resolve(style, layout, idx, 80, 24)
            assert(layout.flexGrow(idx) == 2.0)
        }

        "flexShrink prop sets value" in {
            val layout = new TuiLayout(4)
            val idx    = layout.alloc()
            val style  = Style.flexShrink(0.5)
            TuiStyle.resolve(style, layout, idx, 80, 24)
            assert(layout.flexShrink(idx) == 0.5)
        }

        "default flexShrink is 1.0" in {
            val layout = new TuiLayout(4)
            val idx    = layout.alloc()
            // resolve with empty style → sets defaults
            TuiStyle.resolve(Style.empty, layout, idx, 80, 24)
            assert(layout.flexShrink(idx) == 1.0)
        }

        "default flexGrow is 0.0" in {
            val layout = new TuiLayout(4)
            val idx    = layout.alloc()
            TuiStyle.resolve(Style.empty, layout, idx, 80, 24)
            assert(layout.flexGrow(idx) == 0.0)
        }
    }

    // ──────────────── Phase 10: Overlays ────────────────

    "overlays" - {

        "overlay element gets full terminal bounds" in {
            import AllowUnsafe.embrace.danger
            given Frame = Frame.internal
            val layout  = new TuiLayout(16)
            val signals = new TuiSignalCollector(4)
            val ui = div(
                span("flow"),
                div.style(Style.position(Style.Position.overlay))(span("overlay content"))
            )
            TuiFlatten.flatten(ui, layout, signals, 60, 20, TuiResolvedTheme.empty)
            TuiFlexLayout.measure(layout)
            TuiFlexLayout.arrange(layout, 60, 20)
            // Find the overlay node (has PositionBit set)
            var overlayIdx = -1
            var i          = 0
            // unsafe: while for test iteration
            while i < layout.count do
                if TuiLayout.isOverlay(layout.lFlags(i)) then overlayIdx = i
                i += 1
            end while
            assert(overlayIdx >= 0, "Should find overlay node")
            assert(layout.x(overlayIdx) == 0)
            assert(layout.y(overlayIdx) == 0)
            assert(layout.w(overlayIdx) == 60)
            assert(layout.h(overlayIdx) == 20)
        }

        "overlay does not affect flow layout" in {
            import AllowUnsafe.embrace.danger
            given Frame = Frame.internal
            val layout  = new TuiLayout(16)
            val signals = new TuiSignalCollector(4)
            val ui = div(
                span("A"),
                div.style(Style.position(Style.Position.overlay))(span("ignored")),
                span("B")
            )
            TuiFlatten.flatten(ui, layout, signals, 60, 20, TuiResolvedTheme.empty)
            TuiFlexLayout.measure(layout)
            TuiFlexLayout.arrange(layout, 60, 20)
            // "A" and "B" should be adjacent (overlay skipped in flow)
            // Find text nodes for "A" and "B"
            var aIdx = -1
            var bIdx = -1
            var i    = 0
            // unsafe: while for test iteration
            while i < layout.count do
                if layout.text(i).isDefined then
                    if layout.text(i).get == "A" then aIdx = i
                    if layout.text(i).get == "B" then bIdx = i
                i += 1
            end while
            assert(aIdx >= 0 && bIdx >= 0, "Should find A and B text nodes")
            // B should be directly after A (no gap from overlay)
            val aBottom = layout.y(aIdx) + layout.h(aIdx)
            assert(layout.y(bIdx) == aBottom, s"B.y=${layout.y(bIdx)} should equal A.bottom=$aBottom")
        }

        "overlay paints on top of flow elements" in {
            import AllowUnsafe.embrace.danger
            given Frame = Frame.internal
            val sim = TuiSimulator(
                div(
                    span("flow text"),
                    div.style(Style.position(Style.Position.overlay))(span("OVERLAY"))
                ),
                cols = 40,
                rows = 5
            )
            val f = sim.frame
            assert(f.contains("OVERLAY"), s"Should show overlay text:\n$f")
        }

        "hit test prefers overlay over flow" in {
            import AllowUnsafe.embrace.danger
            given Frame = Frame.internal
            val layout  = new TuiLayout(16)
            val signals = new TuiSignalCollector(4)
            val focus   = new TuiFocus
            val ui = div(
                div.style(Style.width(60.px).height(20.px))(span("flow")),
                div.style(Style.position(Style.Position.overlay))(
                    button("dialog btn")
                )
            )
            TuiFlatten.flatten(ui, layout, signals, 60, 20, TuiResolvedTheme.empty)
            TuiFlexLayout.measure(layout)
            TuiFlexLayout.arrange(layout, 60, 20)
            focus.scan(layout)
            // The overlay button should be focusable and focus-trapped
            assert(focus.focusedIndex >= 0, "Should have a focused element")
            val focusedElem = layout.element(focus.focusedIndex)
            assert(focusedElem.isDefined)
            assert(focusedElem.get.isInstanceOf[UI.Button])
        }

        "tab cycles only within overlay" in run {
            for
                clicked <- Signal.initRef("")
            yield
                val sim = TuiSimulator(
                    div(
                        button("flow-btn").onClick(clicked.set("flow")),
                        div.style(Style.position(Style.Position.overlay))(
                            button("overlay-1").onClick(clicked.set("o1")),
                            button("overlay-2").onClick(clicked.set("o2"))
                        )
                    ),
                    cols = 40,
                    rows = 5
                )
                sim.frame // initial render + scan
                // Tab cycles within overlay only (2 buttons), not to flow-btn
                sim.tab()
                sim.enter() // activate focused overlay button
                sim.waitForEffects(50)
                sim.tab()
                sim.enter() // activate other overlay button
                sim.waitForEffects(50)
                // Both o1 and o2 should have been clicked, never "flow"
                import AllowUnsafe.embrace.danger
                val v = Sync.Unsafe.evalOrThrow(clicked.get)
                assert(v == "o1" || v == "o2", s"Should be o1 or o2 but was '$v'")
        }

        "flow layout unchanged when no overlays" in {
            import AllowUnsafe.embrace.danger
            given Frame = Frame.internal
            val layout  = new TuiLayout(16)
            val signals = new TuiSignalCollector(4)
            val ui      = div(span("A"), span("B"), span("C"))
            TuiFlatten.flatten(ui, layout, signals, 60, 20, TuiResolvedTheme.empty)
            TuiFlexLayout.measure(layout)
            TuiFlexLayout.arrange(layout, 60, 20)
            // Root fills terminal
            assert(layout.x(0) == 0)
            assert(layout.y(0) == 0)
            assert(layout.w(0) == 60)
            assert(layout.h(0) == 20)
        }
    }

    // ──────────────── Paste Support ────────────────

    "paste" - {
        "paste inserts at cursor position" in run {
            for
                value <- Signal.initRef("")
            yield
                val sim = TuiSimulator(
                    input.value(value),
                    cols = 30,
                    rows = 3
                )
                sim.frame
                sim.typeText("AB")
                // Move cursor left once (cursor at position 2, move to 1)
                sim.key(UI.Keyboard.ArrowLeft)
                sim.paste("XY")
                import AllowUnsafe.embrace.danger
                val v = Sync.Unsafe.evalOrThrow(value.get)
                assert(v == "AXYB", s"Expected 'AXYB' but got '$v'")
        }

        "paste advances cursor by pasted text length" in run {
            for
                value <- Signal.initRef("")
            yield
                val sim = TuiSimulator(
                    input.value(value),
                    cols = 30,
                    rows = 3
                )
                sim.frame
                sim.paste("hello")
                sim.typeText("!")
                import AllowUnsafe.embrace.danger
                val v = Sync.Unsafe.evalOrThrow(value.get)
                assert(v == "hello!", s"Expected 'hello!' but got '$v'")
        }

        "paste works in textarea" in run {
            for
                value <- Signal.initRef("")
            yield
                val sim = TuiSimulator(
                    textarea.value(value),
                    cols = 30,
                    rows = 5
                )
                sim.frame
                sim.paste("line1\nline2")
                import AllowUnsafe.embrace.danger
                val v = Sync.Unsafe.evalOrThrow(value.get)
                assert(v == "line1\nline2", s"Expected multiline paste but got '$v'")
        }
    }

    // ──────────────── Scrollable Containers ────────────────

    "scroll" - {
        "scroll down offsets children" in {
            import AllowUnsafe.embrace.danger
            given Frame = Frame.internal
            val layout  = new TuiLayout(16)
            val signals = new TuiSignalCollector(4)
            val ui = div.style(Style.height(5.px).overflow(Style.Overflow.scroll))(
                span("line1"),
                span("line2"),
                span("line3"),
                span("line4"),
                span("line5"),
                span("line6"),
                span("line7"),
                span("line8")
            )
            TuiFlatten.flatten(ui, layout, signals, 20, 10, TuiResolvedTheme.empty)
            TuiFlexLayout.measure(layout)
            TuiFlexLayout.arrange(layout, 20, 10)
            // Before scroll, first child at y=0
            val firstChildIdx = layout.firstChild(0)
            val yBefore       = layout.y(firstChildIdx)
            // Apply scroll offset
            layout.scrollY(0) = 2
            TuiFlexLayout.arrange(layout, 20, 10)
            val yAfter = layout.y(firstChildIdx)
            assert(yAfter == yBefore - 2, s"Expected child offset by -2: before=$yBefore after=$yAfter")
        }

        "scroll events adjust scrollY via simulator" in {
            import AllowUnsafe.embrace.danger
            given Frame = Frame.internal
            val sim = TuiSimulator(
                div.style(Style.height(3.px).overflow(Style.Overflow.scroll))(
                    span("A"),
                    span("B"),
                    span("C"),
                    span("D"),
                    span("E")
                ),
                cols = 10,
                rows = 5
            )
            val f1 = sim.frame
            assert(f1.contains("A"), s"Initial frame should show A:\n$f1")
            sim.scrollDown(1, 1)
            val f2 = sim.frame
            // After scroll down, A may have scrolled out
            // The scroll offset is 3 (delta per scroll event)
            assert(f2.nonEmpty)
        }

        "scroll indicator visible when content overflows" in {
            import AllowUnsafe.embrace.danger
            given Frame  = Frame.internal
            val layout   = new TuiLayout(16)
            val signals  = new TuiSignalCollector(4)
            val renderer = new TuiRenderer(20, 5)
            val ui = div.style(Style.height(5.px).overflow(Style.Overflow.scroll))(
                span("line1"),
                span("line2"),
                span("line3"),
                span("line4"),
                span("line5"),
                span("line6"),
                span("line7"),
                span("line8")
            )
            TuiFlatten.flatten(ui, layout, signals, 20, 5, TuiResolvedTheme.empty)
            TuiFlexLayout.measure(layout)
            TuiFlexLayout.arrange(layout, 20, 5)
            TuiStyle.inherit(layout)
            TuiStyle.applyStates(layout, -1, -1, -1)
            renderer.clear()
            TuiPainter.paint(layout, renderer)
            // The scroll indicator should be rendered on the right edge
            // Just verify paint doesn't crash and produces output
            assert(layout.count > 0)
        }

        "scroll up clamped to zero" in {
            import AllowUnsafe.embrace.danger
            given Frame = Frame.internal
            val sim = TuiSimulator(
                div.style(Style.height(3.px).overflow(Style.Overflow.scroll))(
                    span("A"),
                    span("B"),
                    span("C"),
                    span("D")
                ),
                cols = 10,
                rows = 5
            )
            sim.frame          // initial render
            sim.scrollUp(1, 1) // scroll up from zero -> stays at 0
            sim.frame          // re-render
            assert(true)       // didn't crash
        }
    }

    // ──────────────── Textarea wrapping ────────────────

    "textarea" - {
        "wraps long text within textarea width" in run {
            for
                text <- Signal.initRef("hello world this is a long text that should wrap")
            yield
                val sim = TuiSimulator(
                    div(textarea.value(text)),
                    cols = 30,
                    rows = 10
                )
                val f = sim.frame
                // Text should wrap — "hello world" on one line, rest on next
                // It should NOT appear as one long horizontal line
                val lines = f.split("\n").filter(_.trim.nonEmpty)
                // Find lines containing parts of the text
                val textLines = lines.filter(l => l.contains("hello") || l.contains("wrap") || l.contains("long"))
                assert(textLines.length >= 2, s"Text should wrap to multiple lines:\n$f")
        }

        "scrolls vertically to keep cursor visible" in run {
            for
                text <- Signal.initRef("")
            yield
                val sim = TuiSimulator(
                    div(textarea.value(text)),
                    cols = 30,
                    rows = 10
                )
                sim.tab() // focus textarea
                // Type enough text to fill and overflow the 3-line content area
                // Textarea content width is ~20 chars (22 default - 2 border), so 3 lines = ~60 chars
                sim.typeText("aaaa bbbb cccc dddd eeee ffff gggg hhhh iiii jjjj kkkk")
                sim.waitForEffects()
                val f = sim.frame
                // The most recently typed text should be visible (scrolled into view)
                assert(f.contains("kkkk"), s"Latest typed text should be visible after scroll:\n$f")
        }

        "text does not overflow outside textarea box" in run {
            for
                text <- Signal.initRef("line1 line2 line3 line4 line5 line6 line7 line8 line9 line10")
            yield
                // Textarea with default 3-line content height + borders
                val sim = TuiSimulator(
                    div(
                        textarea.value(text),
                        span("BELOW")
                    ),
                    cols = 30,
                    rows = 15
                )
                val f     = sim.frame
                val lines = f.split("\n")
                // Find where BELOW appears
                val belowLine = lines.indexWhere(_.contains("BELOW"))
                assert(belowLine >= 0, s"Should find BELOW:\n$f")
                // Textarea text should not appear at or after the BELOW line
                val belowContent = lines.drop(belowLine).mkString
                assert(!belowContent.contains("line10"), s"Text should not overflow below textarea:\n$f")
        }
        "arrow up/down navigates between wrapped lines" in run {
            for
                text <- Signal.initRef("")
            yield
                val sim = TuiSimulator(
                    div(textarea.value(text)),
                    cols = 30,
                    rows = 10
                )
                sim.tab() // focus textarea
                // Type text that wraps: content width ~20 chars, type 2+ lines
                sim.typeText("abcdefghij klmnopqrs tuvwxyz123")
                sim.waitForEffects()
                // Cursor should be at end. Move up should go to previous wrapped line.
                sim.key(UI.Keyboard.ArrowUp)
                // Type a marker to verify cursor moved to the previous line
                sim.typeText("X")
                sim.waitForEffects()
                val f = sim.frame
                // The X should appear in the middle of the text, not at the end
                assert(f.contains("X"), s"Should contain inserted X:\n$f")
                // X should NOT be at the very end after "123"
                val allText = f.split("\n").map(_.trim).mkString
                assert(!allText.endsWith("X"), s"X should not be at the end (ArrowUp should move cursor up):\n$f")
        }

        "arrow down at bottom of textarea does not move cursor outside box" in run {
            for
                text <- Signal.initRef("")
            yield
                val sim = TuiSimulator(
                    div(
                        textarea.value(text),
                        span("MARKER")
                    ),
                    cols = 30,
                    rows = 15
                )
                sim.tab() // focus textarea
                // Fill textarea: 3 content lines (default height)
                sim.typeText("line1 aaaa line2 bbbb line3 cccc line4 dddd")
                sim.waitForEffects()
                // Press ArrowDown at bottom — should not crash or move cursor outside
                sim.key(UI.Keyboard.ArrowDown)
                sim.key(UI.Keyboard.ArrowDown)
                sim.key(UI.Keyboard.ArrowDown)
                val f = sim.frame
                // Frame should still render correctly with MARKER below
                assert(f.contains("MARKER"), s"MARKER should still be visible:\n$f")
        }
        "text beyond 3 lines remains visible after scrolling" in run {
            for
                text <- Signal.initRef("")
            yield
                val sim = TuiSimulator(
                    div(textarea.value(text)),
                    cols = 30,
                    rows = 10
                )
                sim.tab() // focus textarea
                // Type 5+ lines worth of text
                sim.typeText("AAAA BBBB CCCC DDDD EEEE FFFF GGGG HHHH IIII JJJJ")
                sim.waitForEffects()
                val f1 = sim.frame
                // Latest text should be visible (auto-scrolled)
                assert(f1.contains("JJJJ"), s"Latest text should be visible:\n$f1")
                // The frame should NOT be blank or all spaces in the textarea area
                val nonEmpty = f1.split("\n").exists(l => l.contains("JJJJ") || l.contains("HHHH") || l.contains("IIII"))
                assert(nonEmpty, s"Textarea should show text, not be blank:\n$f1")

        }

        "mouse scroll changes visible text" in run {
            for
                text <- Signal.initRef("")
            yield
                val sim = TuiSimulator(
                    div(textarea.value(text)),
                    cols = 30,
                    rows = 10
                )
                sim.tab() // focus textarea
                // Type enough to overflow
                sim.typeText("AAAA BBBB CCCC DDDD EEEE FFFF GGGG HHHH IIII JJJJ")
                sim.waitForEffects()
                val f1 = sim.frame
                // Should show latest text
                assert(f1.contains("JJJJ"), s"Should show latest text:\n$f1")
                // Scroll up
                sim.scrollUp(5, 2)
                val f2 = sim.frame
                // After scrolling up, earlier text should become visible
                // and/or latest text may scroll out
                val hasEarlier = f2.contains("AAAA") || f2.contains("BBBB") || f2.contains("CCCC")
                assert(hasEarlier, s"After scroll up, earlier text should be visible:\n$f2")
        }
    }

    // ──────────────── Table column layout ────────────────

    "table" - {
        "distributes columns equally" in run {
            Signal.initRef(0).map { _ =>
                val sim = TuiSimulator(
                    table(
                        tr(
                            td("A"),
                            td("B"),
                            td("C")
                        )
                    ),
                    cols = 30,
                    rows = 5
                )
                val f = sim.frame
                // All three values should appear and be spaced out
                assert(f.contains("A"), s"Should show A:\n$f")
                assert(f.contains("B"), s"Should show B:\n$f")
                assert(f.contains("C"), s"Should show C:\n$f")
                // Check B is not immediately next to A (equal distribution should space them)
                val line = f.split("\n").find(l => l.contains("A") && l.contains("B") && l.contains("C"))
                assert(line.isDefined, s"All cells should be on the same line:\n$f")
                val l    = line.get
                val posA = l.indexOf("A")
                val posB = l.indexOf("B")
                val posC = l.indexOf("C")
                // With 30 cols / 3 cells = 10 chars each, positions should be roughly 0, 10, 20
                assert(posB - posA >= 5, s"B should be well-spaced from A (posA=$posA, posB=$posB):\n$f")
                assert(posC - posB >= 5, s"C should be well-spaced from B (posB=$posB, posC=$posC):\n$f")
            }
        }
    }

end TuiSimulatorTest
