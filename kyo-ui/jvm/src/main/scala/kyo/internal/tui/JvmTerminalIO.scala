package kyo.internal.tui

import kyo.*
import kyo.internal.tui.pipeline.*
import scala.annotation.tailrec

/** JVM implementation of TerminalIO. Uses stty for raw mode, System.in/out for I/O.
  *
  * Each method wraps system calls in Sync.Unsafe.defer (Rule 3: returns computation, no AllowUnsafe). The input parser's mutable byte
  * buffer is internal — never exposed.
  */
class JvmTerminalIO(
    stdout: java.io.OutputStream = null,
    stdin: java.io.InputStream = null
) extends TerminalIO:

    // ---- /dev/tty I/O — bypasses sbt's stdout/stdin capture ----
    private val DevTty                       = new java.io.File("/dev/tty")
    private var ttyOut: java.io.OutputStream = stdout
    private var ttyIn: java.io.InputStream   = stdin
    private var savedStty                    = ""

    // ---- Internal mutable state for input parsing ----
    // Accessed only from within Sync.Unsafe.defer blocks
    private val inputBuffer = new java.io.ByteArrayOutputStream(32)

    // ---- Terminal mode ----

    def enterRawMode(using Frame): Unit < Sync =
        Sync.Unsafe.defer {
            // Open /dev/tty directly — bypasses sbt's stdout/stdin capture
            if ttyOut == null then ttyOut = new java.io.FileOutputStream(DevTty)
            if ttyIn == null then ttyIn = new java.io.FileInputStream(DevTty)
            savedStty = sttyRun("-g")
            stty("raw", "-echo", "-icanon", "-isig")
        }

    def exitRawMode(using Frame): Unit < Sync =
        Sync.Unsafe.defer {
            if savedStty.nonEmpty then stty(savedStty)
            else stty("sane")
        }

    def enterAlternateScreen(using Frame): Unit < Sync =
        Sync.Unsafe.defer {
            ttyOut.write(TerminalEscape.EnterSequence.getBytes)
            ttyOut.flush()
        }

    def exitAlternateScreen(using Frame): Unit < Sync =
        Sync.Unsafe.defer {
            ttyOut.write(TerminalEscape.ExitAlternateScreen.getBytes)
            ttyOut.flush()
        }

    // ---- Mouse tracking (SGR mode) ----

    def enableMouseTracking(using Frame): Unit < Sync =
        Sync.Unsafe.defer {
            ttyOut.write(
                (TerminalEscape.EnableAllMotionMouse + TerminalEscape.EnableSgrMouse + TerminalEscape.EnableBracketedPaste).getBytes
            )
            ttyOut.flush()
        }

    def disableMouseTracking(using Frame): Unit < Sync =
        Sync.Unsafe.defer {
            ttyOut.write(
                (TerminalEscape.DisableBracketedPaste + TerminalEscape.DisableSgrMouse + TerminalEscape.DisableAllMotionMouse).getBytes
            )
            ttyOut.flush()
        }

    // ---- Cursor ----

    def showCursor(using Frame): Unit < Sync =
        Sync.Unsafe.defer {
            ttyOut.write(TerminalEscape.ShowCursor.getBytes)
            ttyOut.flush()
        }

    def hideCursor(using Frame): Unit < Sync =
        Sync.Unsafe.defer {
            ttyOut.write(TerminalEscape.HideCursor.getBytes)
            ttyOut.flush()
        }

    // ---- Size ----

    def size(using Frame): (Int, Int) < Sync =
        Sync.Unsafe.defer {
            val output = sttyRun("size")
            val parts  = output.trim.split("\\s+")
            if parts.length >= 2 then
                val rows = parts(0).toIntOption.getOrElse(24)
                val cols = parts(1).toIntOption.getOrElse(80)
                (cols, rows)
            else (80, 24)
            end if
        }

    // ---- I/O ----

    def write(bytes: Array[Byte])(using Frame): Unit < Sync =
        Sync.Unsafe.defer {
            ttyOut.write(bytes)
        }

    def flush(using Frame): Unit < Sync =
        Sync.Unsafe.defer {
            ttyOut.flush()
        }

    // ---- Input parsing ----

    override def hasInput(using Frame): Boolean < Sync =
        Sync.Unsafe.defer {
            ttyIn.available() > 0
        }

    def readEvent(using Frame): InputEvent < Async =
        Sync.Unsafe.defer {
            readEventBlocking()
        }

    /** Read bytes from stdin until a complete InputEvent is parsed. Blocking. */
    private def readEventBlocking()(using AllowUnsafe): InputEvent =
        val byte = ttyIn.read()
        if byte == -1 then InputEvent.Key(UI.Keyboard.Escape, ctrl = false, alt = false, shift = false)
        else if byte == 0x1b then
            // Escape sequence or bare Escape
            if ttyIn.available() > 0 then
                parseEscapeSequence()
            else
                // Wait briefly to distinguish bare Escape from sequence prefix
                Thread.sleep(50)
                if ttyIn.available() > 0 then
                    parseEscapeSequence()
                else
                    InputEvent.Key(UI.Keyboard.Escape, ctrl = false, alt = false, shift = false)
                end if
        else if byte == 0x0d || byte == 0x0a then
            InputEvent.Key(UI.Keyboard.Enter, ctrl = false, alt = false, shift = false)
        else if byte == 0x09 then
            InputEvent.Key(UI.Keyboard.Tab, ctrl = false, alt = false, shift = false)
        else if byte == 0x7f || byte == 0x08 then
            InputEvent.Key(UI.Keyboard.Backspace, ctrl = false, alt = false, shift = false)
        else if byte < 0x20 then
            // Ctrl+letter
            val ch = (byte + 0x60).toChar
            InputEvent.Key(UI.Keyboard.Char(ch), ctrl = true, alt = false, shift = false)
        else
            // Regular character (may be multi-byte UTF-8)
            inputBuffer.reset()
            inputBuffer.write(byte)
            readUtf8Continuation(byte)
            val text = inputBuffer.toString("UTF-8")
            if text.length == 1 then
                InputEvent.Key(UI.Keyboard.Char(text.charAt(0)), ctrl = false, alt = false, shift = false)
            else
                InputEvent.Paste(text)
            end if
        end if
    end readEventBlocking

    /** Read UTF-8 continuation bytes if needed. */
    private def readUtf8Continuation(firstByte: Int)(using AllowUnsafe): Unit =
        val needed =
            if (firstByte & 0x80) == 0 then 0
            else if (firstByte & 0xe0) == 0xc0 then 1
            else if (firstByte & 0xf0) == 0xe0 then 2
            else if (firstByte & 0xf8) == 0xf0 then 3
            else 0
        @tailrec def loop(remaining: Int): Unit =
            if remaining > 0 then
                val b = ttyIn.read()
                if b != -1 then
                    inputBuffer.write(b)
                    loop(remaining - 1)
        loop(needed)
    end readUtf8Continuation

    /** Parse an escape sequence after reading 0x1b. */
    private def parseEscapeSequence()(using AllowUnsafe): InputEvent =
        val next = ttyIn.read()
        if next == '[' then
            parseCsiSequence()
        else if next == 'O' then
            // SS3 sequences (function keys on some terminals)
            val code = ttyIn.read()
            code match
                case 'P' => InputEvent.Key(UI.Keyboard.F1, ctrl = false, alt = false, shift = false)
                case 'Q' => InputEvent.Key(UI.Keyboard.F2, ctrl = false, alt = false, shift = false)
                case 'R' => InputEvent.Key(UI.Keyboard.F3, ctrl = false, alt = false, shift = false)
                case 'S' => InputEvent.Key(UI.Keyboard.F4, ctrl = false, alt = false, shift = false)
                case _   => InputEvent.Key(UI.Keyboard.Unknown(s"O$code"), ctrl = false, alt = false, shift = false)
            end match
        else
            // Alt+key
            InputEvent.Key(UI.Keyboard.Char(next.toChar), ctrl = false, alt = true, shift = false)
        end if
    end parseEscapeSequence

    /** Parse a CSI sequence: \e[ ... */
    private def parseCsiSequence()(using AllowUnsafe): InputEvent =
        inputBuffer.reset()
        @tailrec def readUntilFinal(): Char =
            val b = ttyIn.read()
            if b == -1 then '?'
            else if b >= 0x40 && b <= 0x7e then b.toChar
            else
                inputBuffer.write(b)
                readUntilFinal()
            end if
        end readUntilFinal
        val finalChar = readUntilFinal()
        val params    = inputBuffer.toString("UTF-8")

        finalChar match
            case 'A' => arrowKey(UI.Keyboard.ArrowUp, params)
            case 'B' => arrowKey(UI.Keyboard.ArrowDown, params)
            case 'C' => arrowKey(UI.Keyboard.ArrowRight, params)
            case 'D' => arrowKey(UI.Keyboard.ArrowLeft, params)
            case 'H' => InputEvent.Key(UI.Keyboard.Home, ctrl = false, alt = false, shift = false)
            case 'F' => InputEvent.Key(UI.Keyboard.End, ctrl = false, alt = false, shift = false)
            case 'Z' => InputEvent.Key(UI.Keyboard.Tab, ctrl = false, alt = false, shift = true)
            case '~' =>
                params match
                    case "2"  => InputEvent.Key(UI.Keyboard.Insert, ctrl = false, alt = false, shift = false)
                    case "3"  => InputEvent.Key(UI.Keyboard.Delete, ctrl = false, alt = false, shift = false)
                    case "5"  => InputEvent.Key(UI.Keyboard.PageUp, ctrl = false, alt = false, shift = false)
                    case "6"  => InputEvent.Key(UI.Keyboard.PageDown, ctrl = false, alt = false, shift = false)
                    case "15" => InputEvent.Key(UI.Keyboard.F5, ctrl = false, alt = false, shift = false)
                    case "17" => InputEvent.Key(UI.Keyboard.F6, ctrl = false, alt = false, shift = false)
                    case "18" => InputEvent.Key(UI.Keyboard.F7, ctrl = false, alt = false, shift = false)
                    case "19" => InputEvent.Key(UI.Keyboard.F8, ctrl = false, alt = false, shift = false)
                    case "20" => InputEvent.Key(UI.Keyboard.F9, ctrl = false, alt = false, shift = false)
                    case "21" => InputEvent.Key(UI.Keyboard.F10, ctrl = false, alt = false, shift = false)
                    case "23" => InputEvent.Key(UI.Keyboard.F11, ctrl = false, alt = false, shift = false)
                    case "24" => InputEvent.Key(UI.Keyboard.F12, ctrl = false, alt = false, shift = false)
                    case _    => InputEvent.Key(UI.Keyboard.Unknown(s"~$params"), ctrl = false, alt = false, shift = false)
            case 'M' | 'm' =>
                // SGR mouse: \e[<btn;x;yM or \e[<btn;x;ym
                parseSgrMouse(params, finalChar)
            case _ =>
                InputEvent.Key(UI.Keyboard.Unknown(s"[$params$finalChar"), ctrl = false, alt = false, shift = false)
        end match
    end parseCsiSequence

    /** Parse arrow key with optional modifiers: \e[1;5A = Ctrl+Up */
    private def arrowKey(base: UI.Keyboard, params: String): InputEvent =
        val parts = params.split(';')
        if parts.length >= 2 then
            val mod   = parts(1).toIntOption.getOrElse(1) - 1
            val shift = (mod & 1) != 0
            val alt   = (mod & 2) != 0
            val ctrl  = (mod & 4) != 0
            InputEvent.Key(base, ctrl, alt, shift)
        else
            InputEvent.Key(base, ctrl = false, alt = false, shift = false)
        end if
    end arrowKey

    /** Parse SGR mouse event: \e[<btn;x;yM or \e[<btn;x;ym */
    private def parseSgrMouse(params: String, finalChar: Char): InputEvent =
        // Remove leading '<' if present
        val clean = if params.startsWith("<") then params.substring(1) else params
        val parts = clean.split(';')
        if parts.length >= 3 then
            val btn = parts(0).toIntOption.getOrElse(0)
            val x   = parts(1).toIntOption.getOrElse(1) - 1
            val y   = parts(2).toIntOption.getOrElse(1) - 1
            val kind =
                if finalChar == 'm' then MouseKind.LeftRelease
                else if (btn & 32) != 0 then MouseKind.Move // bit 5 = motion flag (hover or drag)
                else if (btn & 64) != 0 then                // bits 6-7 = scroll
                    if (btn & 1) != 0 then MouseKind.ScrollDown
                    else MouseKind.ScrollUp
                else if (btn & 3) == 0 then MouseKind.LeftPress // bits 0-1 = 0 = left button
                else MouseKind.Move                             // other buttons treated as move
            InputEvent.Mouse(kind, x, y)
        else
            InputEvent.Key(UI.Keyboard.Unknown(s"mouse:$params"), ctrl = false, alt = false, shift = false)
        end if
    end parseSgrMouse

    // ---- Shutdown hook ----

    override def registerShutdownHook(using Frame): Unit < Sync =
        Sync.defer {
            val hook = new Thread(new Runnable:
                def run(): Unit =
                    try
                        val out = new java.io.FileOutputStream(DevTty)
                        out.write("\u001b[?2004l\u001b[?1006l\u001b[?1003l\u001b[?25h\u001b[?1049l\u001b[0m".getBytes)
                        out.flush()
                        out.close()
                        if savedStty.nonEmpty then
                            val pb = new ProcessBuilder("stty", savedStty)
                            pb.redirectInput(DevTty)
                            pb.inheritIO()
                            discard(pb.start().waitFor())
                        else
                            val pb = new ProcessBuilder("stty", "sane")
                            pb.redirectInput(DevTty)
                            pb.inheritIO()
                            discard(pb.start().waitFor())
                        end if
                    catch case _: Exception => ())
            Runtime.getRuntime.addShutdownHook(hook)
        }

    // ---- stty helpers ----

    private def stty(args: String*)(using AllowUnsafe): Unit =
        val cmd = Array("stty") ++ args
        val pb  = new ProcessBuilder(cmd*)
        pb.redirectInput(DevTty)
        pb.inheritIO()
        val process = pb.start()
        process.waitFor()
        ()
    end stty

    private def sttyRun(args: String*)(using AllowUnsafe): String =
        val cmd = Array("stty") ++ args
        val pb  = new ProcessBuilder(cmd*)
        pb.redirectInput(DevTty)
        val process = pb.start()
        val output  = new String(process.getInputStream.readAllBytes(), "UTF-8")
        process.waitFor()
        output
    end sttyRun

end JvmTerminalIO
