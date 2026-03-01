package kyo

import kyo.internal.TuiColor
import kyo.internal.TuiRenderer
import kyo.internal.TuiRenderer.*

class TuiRendererTest extends Test:

    private def flushToString(renderer: TuiRenderer, colorTier: Int = TrueColor): String =
        val baos = new java.io.ByteArrayOutputStream()
        renderer.flush(baos, colorTier)
        baos.toString("UTF-8")
    end flushToString

    "packStyle and unpack" - {
        "absent colors" in {
            val s = packStyle()
            assert(fgColor(s) == TuiColor.Absent)
            assert(bgColor(s) == TuiColor.Absent)
            succeed
        }
        "fg color round-trip" in {
            val color = TuiColor.pack(0xaa, 0xbb, 0xcc)
            val s     = packStyle(fg = color)
            assert(fgColor(s) == color)
            assert(bgColor(s) == TuiColor.Absent)
            succeed
        }
        "bg color round-trip" in {
            val color = TuiColor.pack(0x11, 0x22, 0x33)
            val s     = packStyle(bg = color)
            assert(bgColor(s) == color)
            assert(fgColor(s) == TuiColor.Absent)
            succeed
        }
        "both colors" in {
            val fg = TuiColor.pack(255, 0, 0)
            val bg = TuiColor.pack(0, 255, 0)
            val s  = packStyle(fg = fg, bg = bg)
            assert(fgColor(s) == fg)
            assert(bgColor(s) == bg)
            succeed
        }
        "black color (0) is not absent" in {
            val black = TuiColor.pack(0, 0, 0)
            val s     = packStyle(fg = black)
            assert(fgColor(s) == black)
            assert(fgColor(s) != TuiColor.Absent)
            succeed
        }
    }

    "set and flush" - {
        "single character produces ANSI output" in {
            val r = new TuiRenderer(5, 1)
            r.clear()
            r.set(0, 0, 'A', packStyle())
            val output = flushToString(r)
            assert(output.contains("A"))
            succeed
        }
        "unchanged cells not re-emitted on second flush" in {
            val r = new TuiRenderer(3, 1)
            r.clear()
            r.set(0, 0, 'X', packStyle())
            flushToString(r) // first flush

            // Second flush with same content
            r.set(0, 0, 'X', packStyle())
            val output2 = flushToString(r)
            // Should not contain 'X' since nothing changed
            assert(!output2.contains("X"))
            succeed
        }
        "changed cell re-emitted" in {
            val r = new TuiRenderer(3, 1)
            r.clear()
            r.set(0, 0, 'A', packStyle())
            flushToString(r)

            r.set(0, 0, 'B', packStyle())
            val output2 = flushToString(r)
            assert(output2.contains("B"))
            succeed
        }
    }

    "fillBg" - {
        "fills region" in {
            val r     = new TuiRenderer(4, 2)
            val color = TuiColor.pack(255, 0, 0)
            r.clear()
            r.fillBg(0, 0, 2, 1, color)
            // Flush and check ANSI contains bg color
            val output = flushToString(r)
            assert(output.contains("48;2;255;0;0"))
            succeed
        }
    }

    "resize" - {
        "changes dimensions" in {
            val r = new TuiRenderer(10, 5)
            r.resize(20, 10)
            assert(r.width == 20)
            assert(r.height == 10)
            succeed
        }
        "no-op if same dimensions" in {
            val r = new TuiRenderer(10, 5)
            r.resize(10, 5)
            assert(r.width == 10)
            succeed
        }
    }

    "invalidate forces full redraw" - {
        "all cells emitted after invalidate" in {
            val r = new TuiRenderer(3, 1)
            r.clear()
            r.set(0, 0, 'A', packStyle())
            r.set(1, 0, 'B', packStyle())
            flushToString(r)

            // Same content
            r.set(0, 0, 'A', packStyle())
            r.set(1, 0, 'B', packStyle())
            r.invalidate()
            val output = flushToString(r)
            assert(output.contains("A"))
            assert(output.contains("B"))
            succeed
        }
    }

    "color tier" - {
        "truecolor emits 38;2;r;g;b" in {
            val r = new TuiRenderer(1, 1)
            r.clear()
            r.set(0, 0, 'X', packStyle(fg = TuiColor.pack(100, 200, 50)))
            val output = flushToString(r, TrueColor)
            assert(output.contains("38;2;100;200;50"))
            succeed
        }
        "256-color emits 38;5;idx" in {
            val r = new TuiRenderer(1, 1)
            r.clear()
            r.set(0, 0, 'X', packStyle(fg = TuiColor.pack(255, 0, 0)))
            val output = flushToString(r, Color256)
            assert(output.contains("38;5;"))
            succeed
        }
        "16-color emits 3x or 9x" in {
            val r = new TuiRenderer(1, 1)
            r.clear()
            r.set(0, 0, 'X', packStyle(fg = TuiColor.pack(255, 0, 0)))
            val output = flushToString(r, Color16)
            // bright red = index 9, emitted as ESC[91m
            assert(output.contains("[91m") || output.contains("[31m"))
            succeed
        }
        "NoColor emits no color codes" in {
            val r = new TuiRenderer(1, 1)
            r.clear()
            r.set(0, 0, 'X', packStyle(fg = TuiColor.pack(255, 0, 0)))
            val output = flushToString(r, NoColor)
            assert(!output.contains("38;"))
            assert(!output.contains("48;"))
            succeed
        }
    }

    "bold attribute" - {
        "emitted as SGR 1" in {
            val r = new TuiRenderer(1, 1)
            r.clear()
            r.set(0, 0, 'B', packStyle(bold = true))
            val output = flushToString(r)
            assert(output.contains("[1m"))
            succeed
        }
    }

    "synchronized output" - {
        "wraps with mode 2026" in {
            val r = new TuiRenderer(1, 1)
            r.clear()
            r.set(0, 0, 'X', packStyle())
            val output = flushToString(r)
            assert(output.contains("\u001b[?2026h"))
            assert(output.contains("\u001b[?2026l"))
            succeed
        }
    }

    "wide character" - {
        "setWide stores symbol" in {
            val r = new TuiRenderer(4, 1)
            r.clear()
            r.setWide(0, 0, "日", packStyle())
            val output = flushToString(r)
            assert(output.contains("日"))
            succeed
        }
    }

end TuiRendererTest
