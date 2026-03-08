package kyo

import kyo.internal.*

class TuiBackendTest extends Test:

    // TuiBackend.render requires a real terminal (/dev/tty).
    // The synchronous pipeline (doRender) is private.
    // Test the full pipeline manually using the same sequence TuiBackend.doRender uses.

    private def buildSimpleUI()(using Frame): UI =
        import UI.*
        div.style(Style.empty.bg("#1a1c2c").width(20).height(5))(
            p.style(Style.empty.color("#ffffff"))("Hello"),
            UI.button.style(Style.empty.color("#ff0000"))("Click")
        )
    end buildSimpleUI

    private def runPipeline(
        ui: UI,
        cols: Int,
        rows: Int
    )(using Frame, AllowUnsafe): (TuiLayout, TuiSignalCollector, TuiRenderer, TuiFocus, String) =
        val layout   = new TuiLayout(64)
        val signals  = new TuiSignalCollector(16)
        val renderer = new TuiRenderer(cols, rows)
        val focus    = new TuiFocus

        TuiFlatten.flatten(ui, layout, signals, cols, rows)
        TuiLayout.measure(layout)
        TuiLayout.arrange(layout, cols, rows)
        TuiPainter.inheritStyles(layout)
        focus.scan(layout)
        focus.applyFocusStyle(layout)
        renderer.clear()
        TuiPainter.paint(layout, renderer)

        val baos = new java.io.ByteArrayOutputStream()
        renderer.flush(baos)
        (layout, signals, renderer, focus, baos.toString("UTF-8"))
    end runPipeline

    // ──────────────────────── Full Pipeline ────────────────────────

    "full pipeline" - {
        "flatten → measure → arrange → inheritStyles → scan → paint completes" in {
            given Frame = Frame.internal
            import AllowUnsafe.embrace.danger

            val (_, _, _, _, out) = runPipeline(buildSimpleUI(), 20, 5)
            assert(out.contains("Hello"))
            assert(out.contains("Click"))
        }

        "focus scan finds button in pipeline" in {
            given Frame = Frame.internal
            import AllowUnsafe.embrace.danger

            val (layout, _, _, focus, _) = runPipeline(buildSimpleUI(), 20, 5)
            assert(focus.focusedIndex >= 0)
            assert(layout.nodeType(focus.focusedIndex) == TuiLayout.NodeButton)
        }

        "focus style applied to button" in {
            given Frame = Frame.internal
            import AllowUnsafe.embrace.danger

            val (layout, _, _, focus, _) = runPipeline(buildSimpleUI(), 20, 5)
            val idx                      = focus.focusedIndex
            assert(TuiLayout.hasBorderT(layout.lFlags(idx)))
            assert(TuiLayout.hasBorderB(layout.lFlags(idx)))
        }

        "signals collected during flatten" in {
            given Frame = Frame.internal
            import AllowUnsafe.embrace.danger

            val (_, signals, _, _, _) = runPipeline(buildSimpleUI(), 20, 5)
            assert(signals.toSpan.isEmpty)
        }

        "pipeline with signal-backed UI" in {
            given Frame = Frame.internal
            import AllowUnsafe.embrace.danger

            val ref = Sync.Unsafe.evalOrThrow(Signal.initRef("dynamic"))
            val ui  = UI.p.style(Style.empty.color("#fff"))(ref)

            val (_, signals, _, _, out) = runPipeline(ui, 20, 5)
            assert(out.contains("dynamic"))
            assert(signals.toSpan.size > 0)
        }

        "re-render after layout reset produces valid output" in {
            given Frame = Frame.internal
            import AllowUnsafe.embrace.danger

            val ui       = buildSimpleUI()
            val layout   = new TuiLayout(64)
            val signals  = new TuiSignalCollector(16)
            val renderer = new TuiRenderer(20, 5)
            val focus    = new TuiFocus

            // First render
            TuiFlatten.flatten(ui, layout, signals, 20, 5)
            TuiLayout.measure(layout)
            TuiLayout.arrange(layout, 20, 5)
            TuiPainter.inheritStyles(layout)
            focus.scan(layout)
            focus.applyFocusStyle(layout)
            renderer.clear()
            TuiPainter.paint(layout, renderer)

            val baos1 = new java.io.ByteArrayOutputStream()
            renderer.flush(baos1)
            val out1 = baos1.toString("UTF-8")

            // Second render (simulates re-render cycle)
            TuiFlatten.flatten(ui, layout, signals, 20, 5)
            TuiLayout.measure(layout)
            TuiLayout.arrange(layout, 20, 5)
            TuiPainter.inheritStyles(layout)
            focus.scan(layout)
            focus.applyFocusStyle(layout)
            renderer.clear()
            TuiPainter.paint(layout, renderer)

            val baos2 = new java.io.ByteArrayOutputStream()
            renderer.flush(baos2)
            val out2 = baos2.toString("UTF-8")

            assert(out1.contains("Hello"))
            // Second render should also produce non-empty output
            assert(out2.nonEmpty)
        }

        "dispatch tab in pipeline" in {
            given Frame = Frame.internal
            import AllowUnsafe.embrace.danger

            val ui =
                import UI.*
                div(
                    UI.button("Btn1"),
                    UI.button("Btn2")
                )
            end ui

            val layout  = new TuiLayout(64)
            val signals = new TuiSignalCollector(16)

            TuiFlatten.flatten(ui, layout, signals, 20, 5)
            TuiLayout.measure(layout)
            TuiLayout.arrange(layout, 20, 5)

            val focus = new TuiFocus
            focus.scan(layout)

            val firstFocus = focus.focusedIndex
            Sync.Unsafe.evalOrThrow(focus.dispatch(InputEvent.Key("Tab"), layout))
            val secondFocus = focus.focusedIndex

            assert(firstFocus != secondFocus)
            assert(layout.nodeType(firstFocus) == TuiLayout.NodeButton)
            assert(layout.nodeType(secondFocus) == TuiLayout.NodeButton)
        }

        "different terminal sizes" in {
            given Frame = Frame.internal
            import AllowUnsafe.embrace.danger

            val ui = UI.div.style(Style.empty.bg("#000").width(80).height(24))(
                UI.p("test")
            )

            val (_, _, _, _, out1) = runPipeline(ui, 40, 12)
            val (_, _, _, _, out2) = runPipeline(ui, 120, 40)

            assert(out1.contains("test"))
            assert(out2.contains("test"))
        }
    }

    // ──────────────────────── dispatchEvents Bug: Ctrl+C matching ────────────────────────

    "dispatchEvents" - {
        "Ctrl+C returns quit" in {
            given Frame = Frame.internal
            import AllowUnsafe.embrace.danger

            val layout = new TuiLayout(64)
            val focus  = new TuiFocus

            val events = Chunk(InputEvent.Key("c", ctrl = true))
            val quit   = Sync.Unsafe.evalOrThrow(TuiBackend.dispatchEvents(events, focus, layout, 0))
            assert(quit)
        }

        "Ctrl+Alt+C should not quit" in {
            given Frame = Frame.internal
            import AllowUnsafe.embrace.danger

            val layout = new TuiLayout(64)
            val focus  = new TuiFocus

            val events = Chunk(InputEvent.Key("c", ctrl = true, alt = true))
            val quit   = Sync.Unsafe.evalOrThrow(TuiBackend.dispatchEvents(events, focus, layout, 0))
            assert(!quit)
        }

        "Ctrl+Shift+C should not quit" in {
            given Frame = Frame.internal
            import AllowUnsafe.embrace.danger

            val layout = new TuiLayout(64)
            val focus  = new TuiFocus

            val events = Chunk(InputEvent.Key("c", ctrl = true, shift = true))
            val quit   = Sync.Unsafe.evalOrThrow(TuiBackend.dispatchEvents(events, focus, layout, 0))
            assert(!quit)
        }

        "empty events returns false" in {
            given Frame = Frame.internal
            import AllowUnsafe.embrace.danger

            val layout = new TuiLayout(64)
            val focus  = new TuiFocus

            val quit = Sync.Unsafe.evalOrThrow(TuiBackend.dispatchEvents(Chunk.empty, focus, layout, 0))
            assert(!quit)
        }

        "Ctrl+C in the middle of batch stops processing" in {
            given Frame = Frame.internal
            import AllowUnsafe.embrace.danger

            val ui =
                import UI.*
                div(UI.button("B1"), UI.button("B2"))
            end ui

            val layout  = new TuiLayout(64)
            val signals = new TuiSignalCollector(16)
            TuiFlatten.flatten(ui, layout, signals, 20, 5)
            TuiLayout.measure(layout)
            TuiLayout.arrange(layout, 20, 5)

            val focus = new TuiFocus
            focus.scan(layout)
            val initialFocus = focus.focusedIndex

            // Tab then Ctrl+C then another Tab — second Tab should not be dispatched
            val events = Chunk(
                InputEvent.Key("Tab"),
                InputEvent.Key("c", ctrl = true),
                InputEvent.Key("Tab")
            )
            val quit = Sync.Unsafe.evalOrThrow(TuiBackend.dispatchEvents(events, focus, layout, 0))
            assert(quit)
            // Only one Tab was dispatched (focus moved once from initial)
            assert(focus.focusedIndex != initialFocus)
        }

        "multiple non-quit events all dispatched" in {
            given Frame = Frame.internal
            import AllowUnsafe.embrace.danger

            val ui =
                import UI.*
                div(UI.button("B1"), UI.button("B2"), UI.button("B3"))
            end ui

            val layout  = new TuiLayout(64)
            val signals = new TuiSignalCollector(16)
            TuiFlatten.flatten(ui, layout, signals, 30, 5)
            TuiLayout.measure(layout)
            TuiLayout.arrange(layout, 30, 5)

            val focus = new TuiFocus
            focus.scan(layout)

            // Three tabs should cycle through all three buttons
            val events = Chunk(
                InputEvent.Key("Tab"),
                InputEvent.Key("Tab"),
                InputEvent.Key("Tab")
            )
            val quit = Sync.Unsafe.evalOrThrow(TuiBackend.dispatchEvents(events, focus, layout, 0))
            assert(!quit)
            // 3 tabs from button 0 wraps back to button 0
            assert(layout.nodeType(focus.focusedIndex) == TuiLayout.NodeButton)
        }
    }

    // ──────────────────────── Pipeline Ordering ────────────────────────

    "pipeline ordering" - {
        "inheritStyles before scan matters" in {
            given Frame = Frame.internal
            import AllowUnsafe.embrace.danger

            val ui = UI.div.style(Style.empty.color("#ff0000"))(
                UI.button("Styled")
            )

            val layout  = new TuiLayout(64)
            val signals = new TuiSignalCollector(16)
            TuiFlatten.flatten(ui, layout, signals, 20, 5)
            TuiLayout.measure(layout)
            TuiLayout.arrange(layout, 20, 5)
            TuiPainter.inheritStyles(layout)

            val focus = new TuiFocus
            focus.scan(layout)
            focus.applyFocusStyle(layout)

            val renderer = new TuiRenderer(20, 5)
            renderer.clear()
            TuiPainter.paint(layout, renderer)

            val baos = new java.io.ByteArrayOutputStream()
            renderer.flush(baos)
            val out = baos.toString("UTF-8")
            assert(out.contains("Styled"))
        }

        "scan before applyFocusStyle required" in {
            given Frame = Frame.internal
            import AllowUnsafe.embrace.danger

            val ui = UI.div(UI.button("Btn"))

            val layout  = new TuiLayout(64)
            val signals = new TuiSignalCollector(16)
            TuiFlatten.flatten(ui, layout, signals, 20, 5)
            TuiLayout.measure(layout)
            TuiLayout.arrange(layout, 20, 5)

            // Apply focus style WITHOUT scan — should be no-op (no crash)
            val focus = new TuiFocus
            focus.applyFocusStyle(layout)
            assert(focus.focusedIndex == -1)

            // Now scan and apply
            focus.scan(layout)
            focus.applyFocusStyle(layout)
            assert(focus.focusedIndex >= 0)
            assert(TuiLayout.hasBorderT(layout.lFlags(focus.focusedIndex)))
        }
    }

    // ──────────────────────── No Focusable Elements ────────────────────────

    "no focusable elements" - {
        "pipeline completes with text-only UI" in {
            given Frame = Frame.internal
            import AllowUnsafe.embrace.danger

            val ui                    = UI.div(UI.p("Just text"), UI.p("More text"))
            val (_, _, _, focus, out) = runPipeline(ui, 30, 5)
            assert(focus.focusedIndex == -1)
            assert(out.contains("Just text"))
            assert(out.contains("More text"))
        }
    }

    // ──────────────────────── Deeply Nested UI ────────────────────────

    "deeply nested UI" - {
        "nested div > div > div > button renders" in {
            given Frame = Frame.internal
            import AllowUnsafe.embrace.danger

            val ui = UI.div(
                UI.div(
                    UI.div(
                        UI.button("Deep")
                    )
                )
            )
            val (layout, _, _, focus, out) = runPipeline(ui, 30, 10)
            assert(out.contains("Deep"))
            assert(focus.focusedIndex >= 0)
            assert(layout.nodeType(focus.focusedIndex) == TuiLayout.NodeButton)
        }
    }

    // ──────────────────────── Multiple Signal-backed Elements ────────────────────────

    "multiple signals" - {
        "collects signals from multiple elements" in {
            given Frame = Frame.internal
            import AllowUnsafe.embrace.danger

            val ref1 = Sync.Unsafe.evalOrThrow(Signal.initRef("one"))
            val ref2 = Sync.Unsafe.evalOrThrow(Signal.initRef("two"))
            val ref3 = Sync.Unsafe.evalOrThrow(Signal.initRef("three"))

            val ui = UI.div(
                UI.p(ref1),
                UI.p(ref2),
                UI.p(ref3)
            )

            val (_, signals, _, _, out) = runPipeline(ui, 30, 10)
            assert(out.contains("one"))
            assert(out.contains("two"))
            assert(out.contains("three"))
            assert(signals.toSpan.size >= 3)
        }
    }

    // ──────────────────────── Input & Textarea in Pipeline ────────────────────────

    "input and textarea" - {
        "input element renders and is focusable" in {
            given Frame = Frame.internal
            import AllowUnsafe.embrace.danger

            val ref = Sync.Unsafe.evalOrThrow(Signal.initRef("hello"))
            val ui  = UI.div(UI.input.value(ref))

            val (layout, _, _, focus, _) = runPipeline(ui, 30, 5)
            assert(focus.focusedIndex >= 0)
            assert(layout.nodeType(focus.focusedIndex) == TuiLayout.NodeInput)
        }

        "textarea element renders and is focusable" in {
            given Frame = Frame.internal
            import AllowUnsafe.embrace.danger

            val ref = Sync.Unsafe.evalOrThrow(Signal.initRef("content"))
            val ui  = UI.div(UI.textarea.value(ref))

            val (layout, _, _, focus, _) = runPipeline(ui, 30, 5)
            assert(focus.focusedIndex >= 0)
            assert(layout.nodeType(focus.focusedIndex) == TuiLayout.NodeTextarea)
        }

        "tab cycles between input and button" in {
            given Frame = Frame.internal
            import AllowUnsafe.embrace.danger

            val ref = Sync.Unsafe.evalOrThrow(Signal.initRef("val"))
            val ui  = UI.div(UI.input.value(ref), UI.button("Go"))

            val layout  = new TuiLayout(64)
            val signals = new TuiSignalCollector(16)
            TuiFlatten.flatten(ui, layout, signals, 30, 5)
            TuiLayout.measure(layout)
            TuiLayout.arrange(layout, 30, 5)

            val focus = new TuiFocus
            focus.scan(layout)

            val first = focus.focusedIndex
            assert(layout.nodeType(first) == TuiLayout.NodeInput)

            Sync.Unsafe.evalOrThrow(focus.dispatch(InputEvent.Key("Tab"), layout))
            val second = focus.focusedIndex
            assert(layout.nodeType(second) == TuiLayout.NodeButton)
        }
    }

    // ──────────────────────── Shared Mutable State (race potential) ────────────────────────

    "shared mutable state" - {
        "dispatch on layout after reset reads stale element" in {
            // Simulates: renderLoop resets layout while inputLoop dispatches
            given Frame = Frame.internal
            import AllowUnsafe.embrace.danger

            val ui =
                import UI.*
                div(UI.button("B1"))
            end ui

            val layout  = new TuiLayout(64)
            val signals = new TuiSignalCollector(16)
            TuiFlatten.flatten(ui, layout, signals, 20, 5)
            TuiLayout.measure(layout)
            TuiLayout.arrange(layout, 20, 5)

            val focus = new TuiFocus
            focus.scan(layout)
            assert(focus.focusedIndex >= 0)

            // Simulate renderLoop resetting layout mid-dispatch
            layout.reset()

            // Now dispatch an event — focus still points to old index
            // The element array still has stale data (reset only sets count=0)
            // This should not crash
            Sync.Unsafe.evalOrThrow(focus.dispatch(InputEvent.Key("Enter"), layout))
            succeed
        }

        "dispatch after re-flatten changes element identity" in {
            // Simulates: renderLoop re-flattens while focus still holds old indices
            given Frame = Frame.internal
            import AllowUnsafe.embrace.danger

            val ref        = Sync.Unsafe.evalOrThrow(Signal.initRef("click count: 0"))
            var clickCount = 0
            val ui =
                import UI.*
                div(
                    UI.button.onClick(Sync.defer {
                        clickCount += 1
                        ref.set(s"click count: $clickCount")
                    })("ClickMe")
                )
            end ui

            val layout  = new TuiLayout(64)
            val signals = new TuiSignalCollector(16)

            // First flatten + scan
            TuiFlatten.flatten(ui, layout, signals, 20, 5)
            TuiLayout.measure(layout)
            TuiLayout.arrange(layout, 20, 5)

            val focus = new TuiFocus
            focus.scan(layout)

            // Re-flatten (as renderLoop would)
            TuiFlatten.flatten(ui, layout, signals, 20, 5)
            TuiLayout.measure(layout)
            TuiLayout.arrange(layout, 20, 5)

            // Focus still holds old indices — dispatch should still work
            // because re-flatten writes to same slots
            Sync.Unsafe.evalOrThrow(focus.dispatch(InputEvent.Key("Enter"), layout))
            succeed
        }

        "focus scan after layout reset with different UI" in {
            // Simulates: UI changes between frames (e.g. conditional rendering)
            given Frame = Frame.internal
            import AllowUnsafe.embrace.danger

            val layout  = new TuiLayout(64)
            val signals = new TuiSignalCollector(16)

            // First frame: 2 buttons
            val ui1 = UI.div(UI.button("B1"), UI.button("B2"))
            TuiFlatten.flatten(ui1, layout, signals, 20, 5)
            TuiLayout.measure(layout)
            TuiLayout.arrange(layout, 20, 5)

            val focus = new TuiFocus
            focus.scan(layout)
            focus.next() // move to B2
            val idx1 = focus.focusedIndex

            // Second frame: only 1 button (B2 removed)
            val ui2 = UI.div(UI.button("B1"))
            TuiFlatten.flatten(ui2, layout, signals, 20, 5)
            TuiLayout.measure(layout)
            TuiLayout.arrange(layout, 20, 5)

            focus.scan(layout)
            // Focus should clamp to valid range, not point to non-existent B2
            assert(focus.focusedIndex >= 0)
            assert(layout.nodeType(focus.focusedIndex) == TuiLayout.NodeButton)
        }
    }

    // ──────────────────────── dispatchEvents edge cases ────────────────────────

    "dispatchEvents edge cases" - {
        "starting from non-zero index" in {
            given Frame = Frame.internal
            import AllowUnsafe.embrace.danger

            val layout = new TuiLayout(64)
            val focus  = new TuiFocus

            val events = Chunk(
                InputEvent.Key("a"),
                InputEvent.Key("b"),
                InputEvent.Key("c", ctrl = true)
            )
            // Start from index 2 — should immediately hit Ctrl+C
            val quit = Sync.Unsafe.evalOrThrow(TuiBackend.dispatchEvents(events, focus, layout, 2))
            assert(quit)
        }

        "starting from index beyond size returns false" in {
            given Frame = Frame.internal
            import AllowUnsafe.embrace.danger

            val layout = new TuiLayout(64)
            val focus  = new TuiFocus

            val events = Chunk(InputEvent.Key("a"))
            val quit   = Sync.Unsafe.evalOrThrow(TuiBackend.dispatchEvents(events, focus, layout, 5))
            assert(!quit)
        }

        "mouse event dispatched without crash" in {
            given Frame = Frame.internal
            import AllowUnsafe.embrace.danger

            val ui =
                import UI.*
                div(UI.button("B1"))
            end ui

            val layout  = new TuiLayout(64)
            val signals = new TuiSignalCollector(16)
            TuiFlatten.flatten(ui, layout, signals, 20, 5)
            TuiLayout.measure(layout)
            TuiLayout.arrange(layout, 20, 5)

            val focus = new TuiFocus
            focus.scan(layout)

            val events = Chunk[InputEvent](InputEvent.Mouse(InputEvent.MouseKind.LeftPress, 5, 2))
            val quit   = Sync.Unsafe.evalOrThrow(TuiBackend.dispatchEvents(events, focus, layout, 0))
            assert(!quit)
        }

        "single event chunk" in {
            given Frame = Frame.internal
            import AllowUnsafe.embrace.danger

            val layout = new TuiLayout(64)
            val focus  = new TuiFocus

            val events = Chunk(InputEvent.Key("a"))
            val quit   = Sync.Unsafe.evalOrThrow(TuiBackend.dispatchEvents(events, focus, layout, 0))
            assert(!quit)
        }
    }

    // ──────────────────────── TuiFocus Tab matching bug ────────────────────────

    "TuiFocus Tab dispatch" - {
        "Ctrl+Tab should not cycle focus" in {
            given Frame = Frame.internal
            import AllowUnsafe.embrace.danger

            val ui =
                import UI.*
                div(UI.button("B1"), UI.button("B2"))
            end ui

            val layout  = new TuiLayout(64)
            val signals = new TuiSignalCollector(16)
            TuiFlatten.flatten(ui, layout, signals, 20, 5)
            TuiLayout.measure(layout)
            TuiLayout.arrange(layout, 20, 5)

            val focus = new TuiFocus
            focus.scan(layout)
            val before = focus.focusedIndex

            Sync.Unsafe.evalOrThrow(focus.dispatch(InputEvent.Key("Tab", ctrl = true), layout))
            // Ctrl+Tab should NOT move focus
            assert(focus.focusedIndex == before)
        }

        "Alt+Tab should not cycle focus" in {
            given Frame = Frame.internal
            import AllowUnsafe.embrace.danger

            val ui =
                import UI.*
                div(UI.button("B1"), UI.button("B2"))
            end ui

            val layout  = new TuiLayout(64)
            val signals = new TuiSignalCollector(16)
            TuiFlatten.flatten(ui, layout, signals, 20, 5)
            TuiLayout.measure(layout)
            TuiLayout.arrange(layout, 20, 5)

            val focus = new TuiFocus
            focus.scan(layout)
            val before = focus.focusedIndex

            Sync.Unsafe.evalOrThrow(focus.dispatch(InputEvent.Key("Tab", alt = true), layout))
            // Alt+Tab should NOT move focus
            assert(focus.focusedIndex == before)
        }
    }

    // ──────────────────────── Concurrent race on shared state ────────────────────────

    "concurrent shared state" - {
        "concurrent flatten+dispatch does not crash" in {
            given Frame = Frame.internal
            import AllowUnsafe.embrace.danger

            val ui =
                import UI.*
                div(UI.button("B1"), UI.button("B2"), UI.button("B3"))
            end ui

            val layout  = new TuiLayout(64)
            val signals = new TuiSignalCollector(16)
            val focus   = new TuiFocus

            // Initial setup
            TuiFlatten.flatten(ui, layout, signals, 20, 5)
            TuiLayout.measure(layout)
            TuiLayout.arrange(layout, 20, 5)
            focus.scan(layout)

            // Simulate concurrent access: rapidly interleave
            // flatten (as renderLoop would) and dispatch (as inputLoop would)
            var crashed    = false
            val iterations = 1000
            val dispatchThread = new Thread(() =>
                var i = 0
                while i < iterations && !crashed do
                    try
                        Sync.Unsafe.evalOrThrow(
                            focus.dispatch(InputEvent.Key("Tab"), layout)
                        )
                    catch
                        case _: ArrayIndexOutOfBoundsException => crashed = true
                        case _: NullPointerException           => crashed = true
                    end try
                    i += 1
                end while
            )
            val renderThread = new Thread(() =>
                var i = 0
                while i < iterations && !crashed do
                    try
                        TuiFlatten.flatten(ui, layout, signals, 20, 5)
                        TuiLayout.measure(layout)
                        TuiLayout.arrange(layout, 20, 5)
                        TuiPainter.inheritStyles(layout)
                        focus.scan(layout)
                    catch
                        case _: ArrayIndexOutOfBoundsException => crashed = true
                        case _: NullPointerException           => crashed = true
                    end try
                    i += 1
                end while
            )

            dispatchThread.start()
            renderThread.start()
            dispatchThread.join(5000)
            renderThread.join(5000)

            // If this fails, it proves the data race is real
            assert(!crashed, "Concurrent access caused a crash — shared mutable state is not thread-safe")
        }
    }

    // ──────────────────────── TuiBackend type ────────────────────────

    "TuiBackend" - {
        "extends UIBackend" in {
            assert(TuiBackend.isInstanceOf[UIBackend])
        }
    }

end TuiBackendTest
