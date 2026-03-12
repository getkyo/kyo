package kyo.internal

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import kyo.Maybe
import kyo.Maybe.*
import kyo.discard

/** Manages terminal raw mode, /dev/tty I/O, and screen state lifecycle.
  *
  * Encapsulates stty manipulation, alternate screen buffer, mouse tracking, bracketed paste, and non-blocking reads. Pure I/O wrapper — no
  * kyo effects.
  */
final private[kyo] class TuiTerminal:

    private var _rows: Int                  = 24
    private var _cols: Int                  = 80
    private var entered                     = false
    private var savedStty                   = ""
    private var ttyIn: Maybe[InputStream]   = Absent
    private var ttyOut: Maybe[OutputStream] = Absent
    private var shutdownHook: Maybe[Thread] = Absent

    def rows: Int                  = _rows
    def cols: Int                  = _cols
    def outputStream: OutputStream = ttyOut.get

    // ──────────────────────── Enter ────────────────────────

    def enter(): Unit =
        if !entered then
            savedStty = sttyRun("-g")
            sttyExec("raw", "-echo", "-icanon", "-isig")
            val in  = new FileInputStream("/dev/tty")
            val out = new FileOutputStream("/dev/tty")
            ttyIn = Present(in)
            ttyOut = Present(out)
            querySize { (r, c) =>
                _rows = r
                _cols = c
            }
            out.write(TuiTerminal.EnterSequence)
            out.flush()
            val hook = new Thread(() => exit())
            shutdownHook = Present(hook)
            Runtime.getRuntime.addShutdownHook(hook)
            entered = true
    end enter

    // ──────────────────────── Exit ────────────────────────

    def exit(): Unit =
        if entered then
            entered = false
            val out = ttyOut.get
            out.write(TuiTerminal.ExitSequence)
            out.flush()
            ttyIn.get.close()
            out.close()
            sttyExec(savedStty)
            shutdownHook.foreach { hook =>
                try discard(Runtime.getRuntime.removeShutdownHook(hook))
                catch case _: IllegalStateException => () // JVM shutting down
            }
            ttyIn = Absent
            ttyOut = Absent
            shutdownHook = Absent
    end exit

    // ──────────────────────── Read ────────────────────────

    def read(buf: Array[Byte]): Int =
        val in    = ttyIn.get
        val avail = in.available()
        if avail > 0 then in.read(buf, 0, math.min(avail, buf.length))
        else 0
    end read

    // ──────────────────────── Flush ────────────────────────

    def flush(): Unit =
        ttyOut.get.flush()

    // ──────────────────────── Resize ────────────────────────

    inline def querySize[A](inline f: (Int, Int) => A): A =
        TuiTerminal.parseSize(sttyRunSize(), _rows, _cols)(f)

    def pollResize(): Boolean =
        querySize { (r, c) =>
            if r != _rows || c != _cols then
                _rows = r
                _cols = c
                true
            else false
        }
    end pollResize

    // ──────────────────────── stty helpers ────────────────────────

    private def sttyRunSize(): String =
        val p = new ProcessBuilder(TuiTerminal.SttySizeCmd*)
            .redirectInput(TuiTerminal.DevTty)
            .start()
        val result = new String(p.getInputStream.readAllBytes()).trim
        discard(p.waitFor())
        result
    end sttyRunSize

    private def sttyRun(args: String*): String =
        val cmd = ("stty" +: args).toArray
        val p = new ProcessBuilder(cmd*)
            .redirectInput(TuiTerminal.DevTty)
            .start()
        val result = new String(p.getInputStream.readAllBytes()).trim
        discard(p.waitFor())
        result
    end sttyRun

    private def sttyExec(args: String*): Unit =
        val cmd = ("stty" +: args).toArray
        val p = new ProcessBuilder(cmd*)
            .redirectInput(TuiTerminal.DevTty)
            .inheritIO()
            .start()
        discard(p.waitFor())
    end sttyExec

end TuiTerminal

private[kyo] object TuiTerminal:

    // ──────────────────────── Escape Sequences ────────────────────────

    private[kyo] val EnterSequenceStr: String =
        "\u001b[?1049h" +     // alternate screen
            "\u001b[?25l" +   // hide cursor
            "\u001b[?1003h" + // all-motion mouse tracking
            "\u001b[?1006h" + // SGR mouse encoding
            "\u001b[?2004h"   // bracketed paste

    private[kyo] val ExitSequenceStr: String =
        "\u001b[?2004l" +     // disable bracketed paste
            "\u001b[?1006l" + // disable SGR mouse
            "\u001b[?1003l" + // disable mouse tracking
            "\u001b[?25h" +   // show cursor
            "\u001b[?1049l" + // exit alternate screen
            "\u001b[0m"       // reset SGR

    private val EnterSequence: Array[Byte] = EnterSequenceStr.getBytes
    private val ExitSequence: Array[Byte]  = ExitSequenceStr.getBytes
    private val SttySizeCmd: Array[String] = Array("stty", "size")
    private val DevTty: File               = new File("/dev/tty")

    // ──────────────────────── Size Parsing ────────────────────────

    private[kyo] inline def parseSize[A](output: String, defaultRows: Int, defaultCols: Int)(inline f: (Int, Int) => A): A =
        import scala.annotation.tailrec
        val len = output.length

        @tailrec def skipWs(i: Int): Int =
            if i < len && output.charAt(i) <= ' ' then skipWs(i + 1) else i

        // Returns the end index and parsed value packed into a Long to avoid tuple allocation
        @tailrec def parseNum(i: Int, acc: Int): Long =
            if i < len && output.charAt(i) >= '0' && output.charAt(i) <= '9' then
                parseNum(i + 1, acc * 10 + (output.charAt(i) - '0'))
            else (i.toLong << 32) | (acc.toLong & 0xffffffffL)

        val i0      = skipWs(0)
        val packed1 = parseNum(i0, 0)
        val i1      = (packed1 >>> 32).toInt
        val rows    = (packed1 & 0xffffffffL).toInt
        if i1 == i0 then f(defaultRows, defaultCols)
        else
            val i2      = skipWs(i1)
            val packed2 = parseNum(i2, 0)
            val i3      = (packed2 >>> 32).toInt
            val cols    = (packed2 & 0xffffffffL).toInt
            if i3 == i2 then f(defaultRows, defaultCols)
            else f(rows, cols)
        end if
    end parseSize

end TuiTerminal
