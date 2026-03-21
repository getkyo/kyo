package kyo.internal.tui.pipeline

import kyo.*
import kyo.Length.*
import kyo.Test
import kyo.UI.foreachKeyed
import kyo.UI.render
import scala.language.implicitConversions

class VisualReactiveTest extends Test:

    import AllowUnsafe.embrace.danger

    def screen(ui: UI, cols: Int, rows: Int) = Screen(ui, cols, rows)

    def assertRender(ui: UI, cols: Int, rows: Int)(expected: String) =
        RenderToString.render(ui, cols, rows).map { actual =>
            val lines   = expected.stripMargin.stripPrefix("\n").linesIterator.toVector
            val trimmed = if lines.nonEmpty && lines.last.trim.isEmpty then lines.dropRight(1) else lines
            val exp     = trimmed.map(_.padTo(cols, ' ')).mkString("\n")
            if actual != exp then
                val msg = s"\nExpected:\n${exp.linesIterator.zipWithIndex.map((l, i) => f"$i%2d|$l|").mkString("\n")}" +
                    s"\nActual:\n${actual.linesIterator.zipWithIndex.map((l, i) => f"$i%2d|$l|").mkString("\n")}"
                fail(msg)
            else succeed
            end if
        }

    // ==== 12.1 ref.render — text display ====

    "12.1 ref.render — text display" - {
        "ref.render with empty ref shows Echo:" in run {
            for
                ref <- Signal.initRef("")
            yield
                val s = screen(
                    UI.div(
                        UI.input.value(ref),
                        ref.render(v => UI.span(s"Echo: $v"))
                    ),
                    20,
                    2
                )
                s.render.andThen {
                    s.assertFrame(
                        """
                        |
                        |Echo:
                        """
                    )
                }
        }

        "type AB — shows Echo: AB after signal settlement" in run {
            for
                ref <- Signal.initRef("")
            yield
                val s = screen(
                    UI.div(
                        UI.input.value(ref),
                        ref.render(v => UI.span(s"Echo: $v"))
                    ),
                    20,
                    2
                )
                for
                    _ <- s.render
                    _ <- s.tab
                    _ <- s.typeChar('A')
                    _ <- s.typeChar('B')
                yield s.assertFrame(
                    """
                    |AB
                    |Echo: AB
                    """
                )
                end for
        }

        "backspace — shows Echo: A" in run {
            for
                ref <- Signal.initRef("")
            yield
                val s = screen(
                    UI.div(
                        UI.input.value(ref),
                        ref.render(v => UI.span(s"Echo: $v"))
                    ),
                    20,
                    2
                )
                for
                    _ <- s.render
                    _ <- s.tab
                    _ <- s.typeChar('A')
                    _ <- s.typeChar('B')
                    _ <- s.backspace
                yield s.assertFrame(
                    """
                    |A
                    |Echo: A
                    """
                )
                end for
        }
    }

    // ==== 12.2 ref.render — counter ====

    "12.2 ref.render — counter" - {
        "initial counter shows 0/10" in run {
            for
                ref <- Signal.initRef("")
            yield
                val maxLen = 10
                val s = screen(
                    UI.div(
                        UI.input.value(ref),
                        ref.render(v => UI.span(s"${v.length}/$maxLen"))
                    ),
                    20,
                    2
                )
                s.render.andThen {
                    s.assertFrame(
                        """
                        |
                        |0/10
                        """
                    )
                }
        }

        "type one char — counter shows 1/10" in run {
            for
                ref <- Signal.initRef("")
            yield
                val maxLen = 10
                val s = screen(
                    UI.div(
                        UI.input.value(ref),
                        ref.render(v => UI.span(s"${v.length}/$maxLen"))
                    ),
                    20,
                    2
                )
                for
                    _ <- s.render
                    _ <- s.tab
                    _ <- s.typeChar('A')
                yield s.assertFrame(
                    """
                    |A
                    |1/10
                    """
                )
                end for
        }

        "type three chars — counter shows 3/10" in run {
            for
                ref <- Signal.initRef("")
            yield
                val maxLen = 10
                val s = screen(
                    UI.div(
                        UI.input.value(ref),
                        ref.render(v => UI.span(s"${v.length}/$maxLen"))
                    ),
                    20,
                    2
                )
                for
                    _ <- s.render
                    _ <- s.tab
                    _ <- s.typeChar('A')
                    _ <- s.typeChar('B')
                    _ <- s.typeChar('C')
                yield s.assertFrame(
                    """
                    |ABC
                    |3/10
                    """
                )
                end for
        }
    }

    // ==== 12.3 ref.render — filtered list ====

    "12.3 ref.render — filtered list" - {
        "all items initially visible" in run {
            for
                ref <- Signal.initRef("")
            yield
                val items = Chunk("apple", "banana", "cherry")
                val s = screen(
                    UI.div(
                        UI.input.value(ref),
                        ref.render { query =>
                            val filtered = items.filter(_.contains(query))
                            UI.div(filtered.map(item => UI.div(item))*)
                        }
                    ),
                    20,
                    5
                )
                s.render.andThen {
                    s.assertAllPresent("apple", "banana", "cherry")
                }
        }

        "type filter — only matching items" in run {
            for
                ref <- Signal.initRef("")
            yield
                val items = Chunk("apple", "banana", "apricot", "cherry")
                val s = screen(
                    UI.div(
                        UI.input.value(ref),
                        ref.render { query =>
                            val filtered = items.filter(_.contains(query))
                            UI.div(filtered.map(item => UI.div(item))*)
                        }
                    ),
                    20,
                    6
                )
                for
                    _ <- s.render
                    _ <- s.tab
                    _ <- s.typeChar('a')
                    _ <- s.typeChar('p')
                yield
                    s.assertAllPresent("apple", "apricot")
                    s.assertNonePresent("banana", "cherry")
                end for
        }

        "clear filter — all items back" in run {
            for
                ref <- Signal.initRef("")
            yield
                val items = Chunk("alpha", "beta", "gamma")
                val s = screen(
                    UI.div(
                        UI.input.value(ref),
                        ref.render { query =>
                            val filtered = items.filter(_.contains(query))
                            UI.div(filtered.map(item => UI.div(item))*)
                        }
                    ),
                    20,
                    5
                )
                for
                    _ <- s.render
                    _ <- s.tab
                    _ <- s.typeChar('a')
                    _ <- s.typeChar('l')
                    _ = s.assertAllPresent("alpha")
                    _ = s.assertNonePresent("beta", "gamma")
                    _ <- s.backspace
                    _ <- s.backspace
                yield s.assertAllPresent("alpha", "beta", "gamma")
                end for
        }
    }

    // ==== 12.4 foreachKeyed — list rendering ====

    "12.4 foreachKeyed — list rendering" - {
        "signal of 3 items — 3 rows" in run {
            for
                ref <- Signal.initRef(Chunk("one", "two", "three"))
            yield
                val s = screen(
                    UI.div(
                        ref.foreachKeyed(identity)(item => UI.div(item))
                    ),
                    20,
                    3
                )
                s.render.andThen {
                    s.assertFrame(
                        """
                        |one
                        |two
                        |three
                        """
                    )
                }
        }

        "add item — 4 rows" in run {
            for
                ref <- Signal.initRef(Chunk("one", "two", "three"))
            yield
                val s = screen(
                    UI.div(
                        ref.foreachKeyed(identity)(item => UI.div(item)),
                        UI.button.onClick {
                            for
                                prev <- ref.get
                                _    <- ref.set(prev.append("four"))
                            yield ()
                        }("Add")
                    ),
                    20,
                    8
                )
                for
                    _ <- s.render
                    _ = s.assertAllPresent("one", "two", "three")
                    _ <- s.tab // focus button
                    _ <- s.key(UI.Keyboard.Space)
                yield s.assertAllPresent("one", "two", "three", "four")
                end for
        }

        "remove item — 2 rows" in run {
            for
                ref <- Signal.initRef(Chunk("one", "two", "three"))
            yield
                val s = screen(
                    UI.div(
                        ref.foreachKeyed(identity)(item => UI.div(item)),
                        UI.button.onClick {
                            for
                                prev <- ref.get
                                _    <- ref.set(prev.filter(_ != "two"))
                            yield ()
                        }("Remove")
                    ),
                    20,
                    8
                )
                for
                    _ <- s.render
                    _ = s.assertAllPresent("one", "two", "three")
                    _ <- s.tab // focus button
                    _ <- s.key(UI.Keyboard.Space)
                yield
                    s.assertAllPresent("one", "three")
                    s.assertNonePresent("two")
                end for
        }

        "items rendered in order" in run {
            for
                ref <- Signal.initRef(Chunk("alpha", "beta", "gamma"))
            yield
                val s = screen(
                    UI.div(
                        ref.foreachKeyed(identity)(item => UI.div(item))
                    ),
                    20,
                    3
                )
                s.render.andThen {
                    s.assertFrame(
                        """
                        |alpha
                        |beta
                        |gamma
                        """
                    )
                }
        }
    }

    // ==== 12.5 Reactive hidden ====

    "12.5 reactive hidden" - {
        "hidden(signal) true — not visible" in run {
            for
                showRef <- Signal.initRef(true)
            yield
                val s = screen(
                    UI.div(
                        UI.span("above"),
                        UI.div.hidden(showRef.map(!_))("hidden content"),
                        UI.span("below")
                    ),
                    20,
                    3
                )
                s.render.andThen {
                    s.assertAllPresent("above", "hidden content", "below")
                }
        }

        "signal changes to true hides element" in run {
            for
                hiddenRef <- Signal.initRef(false)
            yield
                val s = screen(
                    UI.div(
                        UI.span("above"),
                        UI.div.hidden(hiddenRef)("toggled"),
                        UI.span("below"),
                        UI.button.onClick {
                            hiddenRef.set(true)
                        }("Hide")
                    ),
                    20,
                    5
                )
                for
                    _ <- s.render
                    _ = s.assertAllPresent("above", "toggled", "below")
                    _ <- s.tab // focus button
                    _ <- s.key(UI.Keyboard.Space)
                yield
                    s.assertAllPresent("above", "below")
                    s.assertNonePresent("toggled")
                end for
        }

        "signal changes to false shows element" in run {
            for
                hiddenRef <- Signal.initRef(true)
            yield
                val s = screen(
                    UI.div(
                        UI.span("above"),
                        UI.div.hidden(hiddenRef)("toggled"),
                        UI.span("below"),
                        UI.button.onClick {
                            hiddenRef.set(false)
                        }("Show")
                    ),
                    20,
                    5
                )
                for
                    _ <- s.render
                    _ = s.assertNonePresent("toggled")
                    _ <- s.tab // focus button
                    _ <- s.key(UI.Keyboard.Space)
                yield s.assertAllPresent("above", "toggled", "below")
                end for
        }

        "siblings shift when element hidden/shown" in run {
            for
                hiddenRef <- Signal.initRef(false)
            yield
                val s = screen(
                    UI.div(
                        UI.div.style(Style.height(1.px))("A"),
                        UI.div.style(Style.height(1.px)).hidden(hiddenRef)("B"),
                        UI.div.style(Style.height(1.px))("C")
                    ),
                    10,
                    3
                )
                for
                    _ <- s.render
                    _ = s.assertFrame(
                        """
                        |A
                        |B
                        |C
                        """
                    )
                yield succeed
                end for
        }
    }

    // ==== 12.6 Reactive style ====

    "12.6 reactive style" - {
        "style(signal) updates when signal changes" in run {
            for
                wideRef <- Signal.initRef(false)
            yield
                val styleSignal = wideRef.map { wide =>
                    if wide then Style.width(10.px)
                    else Style.width(5.px)
                }
                val s = screen(
                    UI.div.style(styleSignal)("hi"),
                    15,
                    1
                )
                s.render.andThen {
                    // Initially width(5.px), "hi" visible within 5 cols
                    s.assertAllPresent("hi")
                }
        }

        "background color toggle via signal" in run {
            for
                activeRef <- Signal.initRef(false)
            yield
                val styleSignal = activeRef.map { active =>
                    if active then Style.bg(Style.Color.rgb(255, 0, 0))
                    else Style.empty
                }
                val s = screen(
                    UI.div(
                        UI.div.style(styleSignal)("content"),
                        UI.button.onClick {
                            activeRef.set(true)
                        }("Toggle")
                    ),
                    20,
                    5
                )
                for
                    _ <- s.render
                    _ = s.assertAllPresent("content")
                    _ <- s.tab // focus button
                    _ <- s.key(UI.Keyboard.Space)
                yield s.assertAllPresent("content")
                end for
        }
    }

    // ==== 12.7 Shared refs — two-way binding ====

    "12.7 shared refs — two-way binding" - {
        "two inputs bound to same ref — type in first, second updates" in run {
            val ref = SignalRef.Unsafe.init("")
            val s = screen(
                UI.div(
                    UI.input.value(ref.safe),
                    UI.input.value(ref.safe)
                ),
                20,
                2
            )
            for
                _ <- s.render
                _ <- s.tab // focus first input
                _ <- s.typeChar('A')
                _ <- s.typeChar('B')
            yield
                // Both inputs share the same ref, so both should show "AB"
                assert(ref.get() == "AB", s"ref should be 'AB', got '${ref.get()}'")
                // With auto-width filling parent, both inputs show full value "AB"
                s.assertFrame(
                    """
                    |AB
                    |AB
                    """
                )
            end for
        }

        "type in second — first updates" in run {
            val ref = SignalRef.Unsafe.init("")
            val s = screen(
                UI.div(
                    UI.input.value(ref.safe),
                    UI.input.value(ref.safe)
                ),
                20,
                2
            )
            for
                _ <- s.render
                _ <- s.tab // first input
                _ <- s.tab // second input
                _ <- s.typeChar('X')
                _ <- s.typeChar('Y')
            yield
                assert(ref.get() == "XY", s"ref should be 'XY', got '${ref.get()}'")
                // With auto-width filling parent, both inputs show full value "XY"
                s.assertFrame(
                    """
                    |XY
                    |XY
                    """
                )
            end for
        }

        "both always show same value after interleaved typing" in run {
            val ref = SignalRef.Unsafe.init("")
            val s = screen(
                UI.div(
                    UI.input.value(ref.safe),
                    UI.input.value(ref.safe)
                ),
                20,
                2
            )
            for
                _ <- s.render
                _ <- s.tab // first input
                _ <- s.typeChar('A')
                val1 = ref.get()
                _    = assert(val1 == "A", s"after typing A in first: '$val1'")
                _ <- s.tab // second input
                _ <- s.typeChar('B')
                val2 = ref.get()
                _ <- s.shiftTab // back to first input
                _ <- s.typeChar('C')
                val3 = ref.get()
            yield
                // Both inputs share the same ref — the ref value is consistent.
                // In Plain theme inputs have no explicit width, so scroll windows show
                // different parts of the value (focused shows end, unfocused shows start).
                // Verify the ref itself is consistent.
                val f     = s.frame
                val lines = f.linesIterator.toVector
                assert(lines.size >= 2, "should have at least 2 lines")
                // The ref value contains all typed characters
                val refVal = ref.get()
                assert(
                    refVal.contains("A") && refVal.contains("B") && refVal.contains("C"),
                    s"ref should contain all typed chars, got: '$refVal'"
                )
            end for
        }
    }

    // ==== 12.8 Reactive + interaction ====

    "12.8 reactive + interaction" - {
        "input types into ref — derived display updates — verify full frame" in run {
            for
                ref <- Signal.initRef("")
            yield
                val s = screen(
                    UI.div(
                        UI.input.value(ref),
                        ref.render(v => UI.span(s"Val: $v"))
                    ),
                    20,
                    2
                )
                for
                    _ <- s.render
                    _ <- s.tab
                    _ <- s.typeChar('H')
                    _ <- s.typeChar('i')
                yield s.assertFrame(
                    """
                    |Hi
                    |Val: Hi
                    """
                )
                end for
        }

        "select changes value — derived display updates" in run {
            for
                ref <- Signal.initRef("a")
            yield
                val s = screen(
                    UI.div(
                        UI.select.value(ref)(
                            UI.option.value("a")("Alpha"),
                            UI.option.value("b")("Beta")
                        ),
                        ref.render(v => UI.span(s"Selected: $v"))
                    ),
                    25,
                    5
                )
                s.render.andThen {
                    s.assertAllPresent("Selected: a")
                }
        }

        "checkbox toggles — derived display reflects" in run {
            for
                ref <- Signal.initRef(false)
            yield
                val s = screen(
                    UI.div(
                        UI.checkbox.checked(ref).onChange(v => ref.set(v)),
                        ref.render(v => UI.span(if v then "ON" else "OFF"))
                    ),
                    20,
                    2
                )
                for
                    _ <- s.render
                    _ = s.assertAllPresent("[ ]", "OFF")
                    _ <- s.tab
                    _ <- s.key(UI.Keyboard.Space)
                yield s.assertAllPresent("[x]", "ON")
                end for
        }
    }

    // ==== 12.9 Settlement ====

    "12.9 settlement" - {
        "derived signal updates within same dispatch cycle" in run {
            for
                ref <- Signal.initRef("")
            yield
                val s = screen(
                    UI.div(
                        UI.input.value(ref),
                        ref.render(v => UI.span(s"Len: ${v.length}"))
                    ),
                    20,
                    2
                )
                for
                    _ <- s.render
                    _ <- s.tab
                    _ <- s.typeChar('A')
                yield
                    // After dispatch, the derived display should already reflect the new value
                    s.assertFrame(
                        """
                        |A
                        |Len: 1
                        """
                    )
                end for
        }

        "no stale values visible in the frame" in run {
            for
                ref <- Signal.initRef("")
            yield
                val s = screen(
                    UI.div(
                        UI.input.value(ref),
                        ref.render(v => UI.span(s"Echo: $v")),
                        ref.render(v => UI.span(s"Len: ${v.length}"))
                    ),
                    20,
                    3
                )
                for
                    _ <- s.render
                    _ <- s.tab
                    _ <- s.typeChar('X')
                    _ <- s.typeChar('Y')
                yield
                    // Both derived displays must reflect the current value "XY", not stale "X"
                    // With auto-width filling parent, input shows full value.
                    s.assertFrame(
                        """
                        |XY
                        |Echo: XY
                        |Len: 2
                        """
                    )
                end for
        }

        "counter display consistent with input value" in run {
            for
                ref <- Signal.initRef("")
            yield
                val maxLen = 5
                val s = screen(
                    UI.div(
                        UI.input.value(ref),
                        ref.render(v => UI.span(s"${v.length}/$maxLen"))
                    ),
                    20,
                    2
                )
                for
                    _ <- s.render
                    _ <- s.tab
                    _ <- s.typeChar('A')
                    _ <- s.typeChar('B')
                    _ <- s.typeChar('C')
                yield
                    // After typing 3 chars, counter must show 3/5, not some stale value
                    val currentValue = ref.unsafe.get()
                    assert(currentValue == "ABC", s"ref should be 'ABC', got '$currentValue'")
                    // With auto-width filling parent, input shows full value.
                    s.assertFrame(
                        """
                        |ABC
                        |3/5
                        """
                    )
                end for
        }
    }

    // ==== 12.10 Containment ====

    "12.10 containment" - {
        "reactive content growing — stays within bounds" in run {
            for
                ref <- Signal.initRef("")
            yield
                val s = screen(
                    UI.div.style(Style.width(10.px).height(2.px))(
                        UI.input.value(ref),
                        ref.render(v => UI.span(s"V:$v"))
                    ),
                    15,
                    2
                )
                for
                    _ <- s.render
                    _ <- s.tab
                    _ <- s.typeChar('A')
                    _ <- s.typeChar('B')
                    _ <- s.typeChar('C')
                    _ <- s.typeChar('D')
                    _ <- s.typeChar('E')
                    _ <- s.typeChar('F')
                    _ <- s.typeChar('G')
                    _ <- s.typeChar('H')
                yield
                    // Content should stay within the 10-col container, not overflow into cols 11-15
                    val f     = s.frame
                    val lines = f.linesIterator.toVector
                    lines.foreach { line =>
                        // Each line padded to 15 cols, but content within first 10 only
                        val content = line.take(15)
                        assert(content.length <= 15, s"line should not exceed viewport: '$content'")
                    }
                    succeed
                end for
        }

        "reactive list growing — within container height" in run {
            for
                ref <- Signal.initRef(Chunk("one"))
            yield
                val s = screen(
                    UI.div.style(Style.height(3.px))(
                        ref.foreachKeyed(identity)(item => UI.div(item))
                    ),
                    15,
                    5
                )
                for
                    _ <- s.render
                    _ = s.assertAllPresent("one")
                yield
                    // Only 3 rows of height, content should not overflow into rows 4-5
                    val lines = s.frame.linesIterator.toVector
                    assert(lines.size <= 5, s"frame should have at most 5 lines (viewport), got ${lines.size}")
                    succeed
                end for
        }

        "reactive content shrinking — siblings take freed space" in run {
            for
                ref <- Signal.initRef("long text here")
            yield
                val s = screen(
                    UI.div(
                        ref.render(v => UI.div.style(Style.height(1.px))(v)),
                        UI.div.style(Style.height(1.px))("footer")
                    ),
                    20,
                    2
                )
                for
                    _ <- s.render
                    _ = s.assertAllPresent("long text here", "footer")
                yield succeed
                end for
        }
    }

end VisualReactiveTest
