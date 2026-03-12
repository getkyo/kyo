package kyo

import kyo.internal.*
import scala.language.implicitConversions

/** Headless TUI simulator for automated interaction testing.
  *
  * Renders UI, dispatches input events, and captures frames -- no TTY needed.
  */
class TuiSimulator private (
    ui: UI,
    cols: Int,
    rows: Int,
    layout: TuiLayout,
    signals: TuiSignalCollector,
    renderer: TuiRenderer,
    focus: TuiFocus,
    theme: TuiResolvedTheme
)(using Frame, AllowUnsafe):

    private def doRender(): Unit =
        layout.reset()
        signals.reset()
        TuiFlatten.flatten(ui, layout, signals, cols, rows, theme)
        TuiFlexLayout.measure(layout)
        TuiFlexLayout.arrange(layout, cols, rows)
        // Second pass: text wrapping during arrange may increase text heights.
        // Re-measure propagates those heights to parents, then re-arrange positions correctly.
        TuiFlexLayout.measure(layout)
        TuiFlexLayout.arrange(layout, cols, rows)
        focus.scan(layout)
        focus.adjustTextScroll(layout)
        TuiStyle.inherit(layout)
        TuiStyle.applyStates(layout, focus.focusedIndex, focus.hoverIdx, focus.activeIdx)
        renderer.clear()
        TuiPainter.paint(layout, renderer)
    end doRender

    /** Get current frame as plain text grid. */
    def frame: String =
        doRender()
        focus.autoFocus()
        renderer.invalidate() // Force full output -- frame parses all cells
        val baos = new java.io.ByteArrayOutputStream()
        renderer.flush(baos, TuiRenderer.NoColor)
        val raw  = baos.toString("UTF-8")
        val grid = Array.fill(rows)(Array.fill(cols)(' '))
        val stripped = raw.replaceAll("\u001b\\[[0-9;]*m", "")
            .replaceAll("\u001b\\[\\?[^\u001b]*", "")
        val pattern = java.util.regex.Pattern.compile("\u001b\\[(\\d+);(\\d+)H([^\u001b]*)")
        val matcher = pattern.matcher(stripped)
        // unsafe: while for regex iteration
        while matcher.find() do
            val r    = matcher.group(1).toInt - 1
            val c    = matcher.group(2).toInt - 1
            val text = matcher.group(3)
            var i    = 0
            // unsafe: while for character iteration
            while i < text.length do
                val col = c + i
                if r >= 0 && r < rows && col >= 0 && col < cols then
                    grid(r)(col) = text.charAt(i)
                i += 1
            end while
        end while
        grid.map(_.mkString).mkString("\n")
    end frame

    /** Send a key press. */
    def key(k: UI.Keyboard, ctrl: Boolean = false, alt: Boolean = false, shift: Boolean = false): Unit =
        doRender()
        Sync.Unsafe.evalOrThrow(
            focus.dispatch(InputEvent.Key(k, ctrl, alt, shift), layout)
        )
    end key

    /** Send Tab. */
    def tab(): Unit = key(UI.Keyboard.Tab)

    /** Send Shift+Tab. */
    def shiftTab(): Unit = key(UI.Keyboard.Tab, shift = true)

    /** Send Enter. */
    def enter(): Unit = key(UI.Keyboard.Enter)

    /** Send Space. */
    def space(): Unit = key(UI.Keyboard.Space)

    /** Type a string character by character. */
    def typeText(s: String): Unit =
        doRender()
        s.foreach { ch =>
            val k = if ch == ' ' then UI.Keyboard.Space else UI.Keyboard.Char(ch)
            Sync.Unsafe.evalOrThrow(
                focus.dispatch(InputEvent.Key(k), layout)
            )
        }
    end typeText

    /** Scroll up at (x, y). */
    def scrollUp(x: Int, y: Int): Unit =
        doRender()
        Sync.Unsafe.evalOrThrow(
            focus.dispatch(InputEvent.Mouse(InputEvent.MouseKind.ScrollUp, x, y), layout)
        )
    end scrollUp

    /** Scroll down at (x, y). */
    def scrollDown(x: Int, y: Int): Unit =
        doRender()
        Sync.Unsafe.evalOrThrow(
            focus.dispatch(InputEvent.Mouse(InputEvent.MouseKind.ScrollDown, x, y), layout)
        )
    end scrollDown

    /** Paste text into the focused element. */
    def paste(text: String): Unit =
        doRender()
        Sync.Unsafe.evalOrThrow(
            focus.dispatch(InputEvent.Paste(text), layout)
        )
    end paste

    /** Click at (x, y) with left mouse button. */
    def click(x: Int, y: Int): Unit =
        doRender()
        Sync.Unsafe.evalOrThrow(
            focus.dispatch(InputEvent.Mouse(InputEvent.MouseKind.LeftPress, x, y), layout)
        )
    end click

    /** Click on the first occurrence of text in the current frame. */
    def clickOn(text: String): Unit =
        val f     = frame
        val lines = f.split("\n")
        var found = false
        var r     = 0
        // unsafe: while for line scanning
        while r < lines.length && !found do
            val idx = lines(r).indexOf(text)
            if idx >= 0 then
                click(idx, r)
                found = true
            r += 1
        end while
        if !found then
            // unsafe: throw for test assertion
            throw new RuntimeException(s"Text '$text' not found in frame:\n$f")
    end clickOn

    /** Get the focused element's layout index, or -1. */
    def focusedIndex: Int = focus.focusedIndex

    /** Sleep to let async fibers complete. */
    def waitForEffects(ms: Int = 100): Unit = Thread.sleep(ms)

    /** Dump the layout tree to stderr for debugging. */
    def dumpLayout(): Unit =
        doRender()
        val n  = layout.count
        val sb = new StringBuilder
        sb.append(s"Layout: $n nodes\n")
        var i = 0
        // unsafe: while for array iteration
        while i < n do
            val nt        = layout.nodeType(i)
            val x         = layout.x(i)
            val y         = layout.y(i)
            val w         = layout.w(i)
            val h         = layout.h(i)
            val iw        = layout.intrW(i)
            val ih        = layout.intrH(i)
            val pi        = layout.parent(i)
            val lf        = layout.lFlags(i)
            val hidden    = TuiLayout.isHidden(lf)
            val text      = layout.text(i)
            val elem      = layout.element(i)
            val elemName  = if elem.isDefined then elem.get.getClass.getSimpleName else ""
            val textStr   = if text.isDefined then s" text=\"${text.get}\"" else ""
            val hiddenStr = if hidden then " HIDDEN" else ""
            val dir       = if (lf & (1 << TuiLayout.DirBit)) != 0 then " ROW" else ""
            val border    = if TuiLayout.hasBorderT(lf) then " BORDER" else ""
            sb.append(
                f"  [$i%3d] p=$pi%3d x=$x%3d y=$y%3d w=$w%3d h=$h%3d iw=$iw%3d ih=$ih%3d nt=$nt%2d$dir$hiddenStr$border $elemName$textStr\n"
            )
            i += 1
        end while
        java.lang.System.err.println(sb.toString)
    end dumpLayout

end TuiSimulator

object TuiSimulator:

    def apply(ui: UI, cols: Int = 60, rows: Int = 30, theme: Theme = Theme.Default)(using Frame): TuiSimulator =
        import AllowUnsafe.embrace.danger
        val resolved = TuiResolvedTheme.resolve(theme)
        val layout   = new TuiLayout(512)
        val signals  = new TuiSignalCollector(256)
        val renderer = new TuiRenderer(cols, rows)
        val focus    = new TuiFocus
        focus.disableAutoFocus()
        new TuiSimulator(ui, cols, rows, layout, signals, renderer, focus, resolved)
    end apply

end TuiSimulator
