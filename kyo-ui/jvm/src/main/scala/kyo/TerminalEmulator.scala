package kyo

import com.jediterm.terminal.ArrayTerminalDataStream
import com.jediterm.terminal.TerminalColor
import com.jediterm.terminal.TerminalDisplay
import com.jediterm.terminal.TextStyle
import com.jediterm.terminal.emulator.JediEmulator
import com.jediterm.terminal.model.JediTerminal
import com.jediterm.terminal.model.StyleState
import com.jediterm.terminal.model.TerminalTextBuffer
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import javax.imageio.ImageIO
import kyo.internal.tui2.*
import kyo.internal.tui2.widget.Render as WidgetRender
import scala.annotation.tailrec

/** Headless terminal emulator for visual validation and interactive testing.
  *
  * Renders UI to Screen, flushes ANSI bytes through JediTerm for accurate terminal emulation, and produces PNG screenshots from JediTerm's
  * buffer. Supports full interaction via keyboard, mouse, and scroll events.
  */
final class TerminalEmulator private (
    val ui: UI,
    val cols: Int,
    val rows: Int,
    private val screen: Screen,
    private val canvas: Canvas,
    private val focus: FocusRing,
    private val ctx: RenderCtx,
    private val jedi: JediTerminal,
    private val textBuffer: TerminalTextBuffer
)(using Frame, AllowUnsafe):

    private val emulatorSink: Screen.Sink = new Screen.Sink:
        def write(bytes: Array[Byte], len: Int): Unit =
            val chars    = new String(bytes, 0, len, "UTF-8").toCharArray
            val stream   = new ArrayTerminalDataStream(chars)
            val emulator = new JediEmulator(stream, jedi)
            while emulator.hasNext do emulator.next()
        end write

    // Initial render
    doRender()

    private def doRender(): Unit =
        ctx.beginFrame()
        screen.resetClip()
        screen.clear()
        focus.scan(ui, ctx)
        WidgetRender.render(ui, 0, 0, cols, rows, ctx)
        renderOverlays()
        screen.resetClip()
        ctx.renderDropdown()
        screen.flush(emulatorSink, Screen.TrueColor)
    end doRender

    private def renderOverlays(): Unit =
        @tailrec def loop(i: Int): Unit =
            if i < ctx.overlays.size then
                WidgetRender.renderElement(ctx.overlays(i), 0, 0, cols, rows, ctx)
                loop(i + 1)
        loop(0)
    end renderOverlays

    // ---- Frame capture ----

    /** Get current frame as plain text grid (from JediTerm's buffer). */
    def frame: String =
        doRender()
        val sb = new StringBuilder(cols * rows + rows)
        @tailrec def buildRow(r: Int): Unit =
            if r < rows then
                if r > 0 then sb.append('\n')
                @tailrec def buildCol(c: Int): Unit =
                    if c < cols then
                        val ch = textBuffer.getCharAt(c, r)
                        sb.append(if ch == '\u0000' then ' ' else ch)
                        buildCol(c + 1)
                buildCol(0)
                buildRow(r + 1)
        buildRow(0)
        sb.toString
    end frame

    /** Get current frame with ANSI escape codes for colors/styles. */
    def ansiFrame: String =
        doRender()
        val sb                   = new StringBuilder(cols * rows * 4)
        var lastStyle: TextStyle = null
        @tailrec def buildRow(r: Int): Unit =
            if r < rows then
                if r > 0 then
                    sb.append("\u001b[0m\n")
                    lastStyle = null
                end if
                @tailrec def buildCol(c: Int): Unit =
                    if c < cols then
                        val ch    = textBuffer.getCharAt(c, r)
                        val style = textBuffer.getStyleAt(c, r)
                        if style ne lastStyle then
                            sb.append("\u001b[0m")
                            val fg = style.getForeground
                            if fg != null then
                                val co = fg.toColor()
                                sb.append(f"\u001b[38;2;${co.getRed}%d;${co.getGreen}%d;${co.getBlue}%dm")
                            end if
                            val bg = style.getBackground
                            if bg != null then
                                val co = bg.toColor()
                                sb.append(f"\u001b[48;2;${co.getRed}%d;${co.getGreen}%d;${co.getBlue}%dm")
                            end if
                            if style.hasOption(TextStyle.Option.BOLD) then sb.append("\u001b[1m")
                            if style.hasOption(TextStyle.Option.DIM) then sb.append("\u001b[2m")
                            if style.hasOption(TextStyle.Option.ITALIC) then sb.append("\u001b[3m")
                            if style.hasOption(TextStyle.Option.UNDERLINED) then sb.append("\u001b[4m")
                            if style.hasOption(TextStyle.Option.INVERSE) then sb.append("\u001b[7m")
                            lastStyle = style
                        end if
                        sb.append(if ch == '\u0000' then ' ' else ch)
                        buildCol(c + 1)
                buildCol(0)
                buildRow(r + 1)
        buildRow(0)
        sb.append("\u001b[0m")
        sb.toString
    end ansiFrame

    /** Get style info at (col, row) as a human-readable string. Example: "fg=#aabbcc bg=#112233 bold underline dim"
      */
    def styleAt(col: Int, row: Int): String =
        doRender()
        if col < 0 || col >= cols || row < 0 || row >= rows then return "out-of-bounds"
        val style = textBuffer.getStyleAt(col, row)
        val sb    = new StringBuilder
        val fg    = style.getForeground
        if fg != null then
            val c = fg.toColor()
            sb.append(f"fg=#${c.getRed}%02x${c.getGreen}%02x${c.getBlue}%02x")
        else sb.append("fg=default")
        end if
        val bg = style.getBackground
        if bg != null then
            val c = bg.toColor()
            sb.append(f" bg=#${c.getRed}%02x${c.getGreen}%02x${c.getBlue}%02x")
        else sb.append(" bg=default")
        end if
        if style.hasOption(TextStyle.Option.BOLD) then sb.append(" bold")
        if style.hasOption(TextStyle.Option.DIM) then sb.append(" dim")
        if style.hasOption(TextStyle.Option.ITALIC) then sb.append(" italic")
        if style.hasOption(TextStyle.Option.UNDERLINED) then sb.append(" underline")
        if style.hasOption(TextStyle.Option.INVERSE) then sb.append(" inverse")
        sb.toString
    end styleAt

    /** Dump unique styles for non-empty cells, grouped. Useful for QA validation. */
    def styleMap: String =
        doRender()
        val regions = new java.util.LinkedHashMap[String, StringBuilder]()
        @tailrec def scanRow(r: Int): Unit =
            if r < rows then
                @tailrec def scanCol(c: Int): Unit =
                    if c < cols then
                        val ch = textBuffer.getCharAt(c, r)
                        if ch != '\u0000' && ch != ' ' then
                            val sty = styleAt(c, r)
                            discard(regions.computeIfAbsent(sty, _ => new StringBuilder()).append(ch))
                        scanCol(c + 1)
                scanCol(0)
                scanRow(r + 1)
        scanRow(0)
        val sb = new StringBuilder
        regions.forEach { (style, chars) =>
            sb.append(s"[$style] ${chars.toString.take(40)}\n")
        }
        sb.toString
    end styleMap

    /** Take a PNG screenshot. */
    def screenshot(path: String): Unit =
        val img = renderToImage()
        discard(ImageIO.write(img, "PNG", new java.io.File(path)))

    /** Render current frame to a BufferedImage using JediTerm's buffer. */
    def renderToImage(): BufferedImage =
        doRender()
        val charW = 9
        val charH = 18
        val pad   = 8
        val imgW  = cols * charW + 2 * pad
        val imgH  = rows * charH + 2 * pad
        val img   = new BufferedImage(imgW, imgH, BufferedImage.TYPE_INT_RGB)
        val g     = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)

        val defaultBg = new java.awt.Color(0x1a, 0x1a, 0x2e)
        val defaultFg = new java.awt.Color(0xcc, 0xcc, 0xcc)
        g.setColor(defaultBg)
        g.fillRect(0, 0, imgW, imgH)

        val baseFont      = new Font("Menlo", Font.PLAIN, 14)
        val boldFont      = baseFont.deriveFont(Font.BOLD)
        val italicFont    = baseFont.deriveFont(Font.ITALIC)
        val boldItalicFnt = baseFont.deriveFont(Font.BOLD | Font.ITALIC)
        g.setFont(baseFont)

        @tailrec def drawRow(r: Int): Unit =
            if r < rows then
                @tailrec def drawCol(c: Int): Unit =
                    if c < cols then
                        val ch    = textBuffer.getCharAt(c, r)
                        val style = textBuffer.getStyleAt(c, r)
                        val px    = pad + c * charW
                        val py    = pad + r * charH

                        // Background
                        val bgTc = style.getBackground
                        if bgTc != null then
                            val bgc = bgTc.toColor()
                            g.setColor(new java.awt.Color(bgc.getRed, bgc.getGreen, bgc.getBlue))
                            g.fillRect(px, py, charW, charH)
                        end if

                        // Foreground character
                        if ch != '\u0000' && ch != ' ' then
                            val fgTc = style.getForeground
                            val fg =
                                if fgTc != null then
                                    val fgc = fgTc.toColor()
                                    new java.awt.Color(fgc.getRed, fgc.getGreen, fgc.getBlue)
                                else defaultFg

                            val color =
                                if style.hasOption(TextStyle.Option.DIM) then
                                    new java.awt.Color(fg.getRed / 2, fg.getGreen / 2, fg.getBlue / 2)
                                else fg
                            g.setColor(color)

                            val isBold   = style.hasOption(TextStyle.Option.BOLD)
                            val isItalic = style.hasOption(TextStyle.Option.ITALIC)
                            g.setFont(
                                if isBold && isItalic then boldItalicFnt
                                else if isBold then boldFont
                                else if isItalic then italicFont
                                else baseFont
                            )
                            g.drawString(ch.toString, px, py + charH - 4)

                            if style.hasOption(TextStyle.Option.UNDERLINED) then
                                g.drawLine(px, py + charH - 2, px + charW, py + charH - 2)
                        end if

                        drawCol(c + 1)
                drawCol(0)
                drawRow(r + 1)
        drawRow(0)

        g.dispose()
        img
    end renderToImage

    // ---- Interaction ----

    /** Send a key event. */
    def key(k: UI.Keyboard, ctrl: Boolean = false, alt: Boolean = false, shift: Boolean = false): Unit =
        doRender()
        EventDispatch.dispatch(ui, InputEvent.Key(k, ctrl, alt, shift), cols, rows, ctx)

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
        @tailrec def loop(i: Int): Unit =
            if i < s.length then
                val ch = s.charAt(i)
                val k  = if ch == ' ' then UI.Keyboard.Space else UI.Keyboard.Char(ch)
                EventDispatch.dispatch(ui, InputEvent.Key(k), cols, rows, ctx)
                loop(i + 1)
        loop(0)
    end typeText

    /** Send any mouse event. */
    def mouse(kind: InputEvent.MouseKind, x: Int, y: Int): Unit =
        doRender()
        EventDispatch.dispatch(ui, InputEvent.Mouse(kind, x, y), cols, rows, ctx)

    /** Left click at (x, y). */
    def click(x: Int, y: Int): Unit = mouse(InputEvent.MouseKind.LeftPress, x, y)

    /** Release left mouse at (x, y). */
    def release(x: Int, y: Int): Unit = mouse(InputEvent.MouseKind.LeftRelease, x, y)

    /** Right click at (x, y). */
    def rightClick(x: Int, y: Int): Unit = mouse(InputEvent.MouseKind.RightPress, x, y)

    /** Mouse move to (x, y). */
    def mouseMove(x: Int, y: Int): Unit = mouse(InputEvent.MouseKind.Move, x, y)

    /** Left drag to (x, y). */
    def drag(x: Int, y: Int): Unit = mouse(InputEvent.MouseKind.LeftDrag, x, y)

    /** Click on the first occurrence of text. */
    def clickOn(text: String): Unit =
        val f     = frame
        val lines = f.split("\n")
        @tailrec def findLine(r: Int): Unit =
            if r >= lines.length then
                throw new RuntimeException(s"Text '$text' not found in frame:\n$f")
            else
                val idx = lines(r).indexOf(text)
                if idx >= 0 then click(idx, r)
                else findLine(r + 1)
        findLine(0)
    end clickOn

    /** Scroll up at (x, y). */
    def scrollUp(x: Int, y: Int): Unit = mouse(InputEvent.MouseKind.ScrollUp, x, y)

    /** Scroll down at (x, y). */
    def scrollDown(x: Int, y: Int): Unit = mouse(InputEvent.MouseKind.ScrollDown, x, y)

    /** Paste text. */
    def paste(text: String): Unit =
        doRender()
        EventDispatch.dispatch(ui, InputEvent.Paste(text), cols, rows, ctx)

    /** Sleep to let async fibers complete. */
    def waitForEffects(ms: Int = 100): Unit = Thread.sleep(ms)

    // ---- Diagnostics ----

    /** The currently focused element, or Absent. */
    def focusedElement: Maybe[UI.Element] =
        doRender()
        focus.focused

    /** Number of focusable elements in the current focus ring. */
    def focusableCount: Int =
        doRender()
        focus.focusCount

    /** Human-readable focus state for debugging. */
    def focusInfo: String =
        doRender()
        val elem = focus.focused
        val elemStr = elem match
            case Maybe.Present(e) => s"${e.getClass.getSimpleName}(id=${e.attrs.identifier.getOrElse("none")})"
            case _                => "none"
        s"focused=$elemStr, count=${focus.focusCount}"
    end focusInfo

    /** Read the current value of a SignalRef. */
    def signalValue[A](sig: Signal.SignalRef[A]): A = sig.unsafe.get()

    /** Get the text value of the currently focused element (if it's a TextInput). Returns "" if not focused or not a TextInput. */
    def focusedValue: String =
        doRender()
        focus.focused match
            case Maybe.Present(ti: UI.TextInput) =>
                ti.value match
                    case Maybe.Present(s: String)                    => s
                    case Maybe.Present(sr: Signal.SignalRef[String]) => sr.unsafe.get()
                    case _                                           => ""
            case _ => ""
        end match
    end focusedValue

    /** Check if the focused element has a mutable SignalRef binding. */
    def focusedHasRef: Boolean =
        doRender()
        focus.focused match
            case Maybe.Present(ti: UI.TextInput) =>
                ti.value match
                    case Maybe.Present(_: Signal.SignalRef[?]) => true
                    case _                                     => false
            case _ => false
        end match
    end focusedHasRef

    /** Dump the full focus ring state — all elements, types, values, cursor positions. */
    def focusRingDump: String =
        doRender()
        focus.dump

    /** Comprehensive debug info: frame + focus ring + focused element details. */
    def debugInfo: String =
        doRender()
        val sb = new StringBuilder
        sb.append("=== Frame ===\n")
        discard(sb.append(frame).append("\n\n"))
        sb.append("=== Focus Ring ===\n")
        discard(sb.append(focus.dump).append("\n"))
        sb.append("=== Focused Element ===\n")
        focus.focused match
            case Maybe.Present(elem) =>
                val typ = elem.getClass.getSimpleName
                val id  = elem.attrs.identifier.getOrElse("-")
                sb.append(s"type=$typ, id=$id\n")
                elem match
                    case ti: UI.TextInput =>
                        ti.value match
                            case Maybe.Present(s: String) =>
                                sb.append(s"  value(static)=\"$s\"\n")
                            case Maybe.Present(sr: Signal.SignalRef[?]) =>
                                val v = sr.asInstanceOf[Signal.SignalRef[String]].unsafe.get()
                                sb.append(s"  value(ref)=\"$v\"\n")
                                sb.append(s"  ref.identity=${java.lang.System.identityHashCode(sr)}\n")
                            case _ =>
                                sb.append("  value=absent (CANNOT EDIT)\n")
                        end match
                        ti.placeholder match
                            case Maybe.Present(p) => sb.append(s"  placeholder=\"$p\"\n")
                            case _                => ()
                        ti.readOnly match
                            case Maybe.Present(true) => sb.append("  readOnly=true\n")
                            case _                   => ()
                        sb.append(s"  cursorPos=${focus.cursorPos(elem)}\n")
                        sb.append(s"  scrollX=${focus.scrollX(elem)}, scrollY=${focus.scrollY(elem)}\n")
                        if focus.hasSelection(elem) then
                            sb.append(s"  selection=${focus.selStart(elem)}..${focus.selEnd(elem)}\n")
                    case _ =>
                        sb.append(s"  (not a text input)\n")
                end match
            case _ =>
                sb.append("none\n")
        end match
        sb.toString
    end debugInfo

    // ---- Assert helpers ----

    /** Assert that the rendered frame contains the given text. Throws with frame dump on failure. */
    def assertContains(text: String): Unit =
        val f = frame
        if !f.contains(text) then
            throw new AssertionError(s"Expected '$text' in frame:\n$f\n\n${focus.dump}")
    end assertContains

    /** Assert that the focused element's text value equals expected. Throws with debug info on failure. */
    def assertFocusedValue(expected: String): Unit =
        val actual = focusedValue
        if actual != expected then
            throw new AssertionError(
                s"Expected focused value '$expected' but got '$actual'\n\n${debugInfo}"
            )
        end if
    end assertFocusedValue

    /** Assert that a SignalRef has the expected value. */
    def assertSignalValue(sig: Signal.SignalRef[String], expected: String): Unit =
        val actual = sig.unsafe.get()
        if actual != expected then
            throw new AssertionError(
                s"Expected signal value '$expected' but got '$actual'\n\n${focus.dump}"
            )
        end if
    end assertSignalValue

end TerminalEmulator

object TerminalEmulator:

    def apply(ui: UI, cols: Int = 80, rows: Int = 24, theme: Theme = Theme.Default)(using Frame): TerminalEmulator =
        import AllowUnsafe.embrace.danger
        val resolved = ResolvedTheme.resolve(theme)
        val screen   = new Screen(cols, rows)
        val canvas   = new Canvas(screen)
        val focus    = new FocusRing
        val signals  = new SignalCollector(256)
        val overlays = new OverlayCollector(4)
        val ctx      = new RenderCtx(screen, canvas, focus, resolved, signals, overlays)

        // JediTerm headless setup
        val styleState = new StyleState()
        val textBuffer = new TerminalTextBuffer(cols, rows, styleState)
        val display: TerminalDisplay = new TerminalDisplay:
            def setCursor(x: Int, y: Int): Unit                                                  = ()
            def setCursorShape(shape: com.jediterm.terminal.CursorShape): Unit                   = ()
            def beep(): Unit                                                                     = ()
            def scrollArea(scrollRegionTop: Int, scrollRegionSize: Int, dy: Int): Unit           = ()
            def setCursorVisible(visible: Boolean): Unit                                         = ()
            def useAlternateScreenBuffer(enabled: Boolean): Unit                                 = ()
            def getWindowTitle(): String                                                         = ""
            def setWindowTitle(title: String): Unit                                              = ()
            def getSelection(): com.jediterm.terminal.model.TerminalSelection                    = null
            def terminalMouseModeSet(mode: com.jediterm.terminal.emulator.mouse.MouseMode): Unit = ()
            def setMouseFormat(format: com.jediterm.terminal.emulator.mouse.MouseFormat): Unit   = ()
            def ambiguousCharsAreDoubleWidth(): Boolean                                          = false
        val jedi = new JediTerminal(display, textBuffer, styleState)

        new TerminalEmulator(ui, cols, rows, screen, canvas, focus, ctx, jedi, textBuffer)
    end apply

end TerminalEmulator
