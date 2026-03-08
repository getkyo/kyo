package kyo

import kyo.Maybe.*
import kyo.internal.*
import kyo.internal.TuiLayout.*

class TuiPainterTest extends Test:

    // ──────────────────────── Helpers ────────────────────────

    private def mkLayout(cap: Int = 16): TuiLayout = new TuiLayout(cap)

    private def addNode(
        layout: TuiLayout,
        parentIdx: Int,
        nodeType: Int = NodeDiv,
        fg: Int = TuiColor.Absent,
        bg: Int = TuiColor.Absent,
        text: Maybe[String] = Absent,
        pFlags: Int = 0,
        opacity: Float = 1.0f
    ): Int =
        val idx = layout.alloc()
        TuiLayout.linkChild(layout, parentIdx, idx)
        TuiStyle.setDefaults(layout, idx)
        layout.nodeType(idx) = nodeType.toByte
        layout.fg(idx) = fg
        layout.bg(idx) = bg
        layout.text(idx) = text
        layout.pFlags(idx) = pFlags
        layout.opac(idx) = opacity
        idx
    end addNode

    private def flushToString(renderer: TuiRenderer): String =
        val baos = new java.io.ByteArrayOutputStream()
        renderer.flush(baos)
        baos.toString("UTF-8")
    end flushToString

    // ──────────────────────── inheritStyles ────────────────────────

    "inheritStyles" - {
        "text node inherits parent fg" in {
            val l    = mkLayout()
            val root = addNode(l, -1, fg = TuiColor.pack(255, 0, 0))
            val text = addNode(l, root, nodeType = NodeText, text = Present("hello"))
            assert(l.fg(text) == TuiColor.Absent)
            TuiPainter.inheritStyles(l)
            assert(l.fg(text) == TuiColor.pack(255, 0, 0))
        }

        "text node inherits parent bg" in {
            val l    = mkLayout()
            val root = addNode(l, -1, bg = TuiColor.pack(0, 0, 255))
            val text = addNode(l, root, nodeType = NodeText, text = Present("hello"))
            TuiPainter.inheritStyles(l)
            assert(l.bg(text) == TuiColor.pack(0, 0, 255))
        }

        "text node inherits parent pFlags" in {
            val l    = mkLayout()
            val bold = 1 << TuiLayout.BoldBit
            val root = addNode(l, -1, pFlags = bold)
            val text = addNode(l, root, nodeType = NodeText, text = Present("bold"))
            TuiPainter.inheritStyles(l)
            assert(TuiLayout.isBold(l.pFlags(text)))
        }

        "element inherits parent fg when absent" in {
            val l     = mkLayout()
            val root  = addNode(l, -1, fg = TuiColor.pack(0, 255, 0))
            val child = addNode(l, root, nodeType = NodeDiv)
            TuiPainter.inheritStyles(l)
            assert(l.fg(child) == TuiColor.pack(0, 255, 0))
        }

        "element keeps explicit fg" in {
            val l     = mkLayout()
            val root  = addNode(l, -1, fg = TuiColor.pack(255, 0, 0))
            val child = addNode(l, root, nodeType = NodeDiv, fg = TuiColor.pack(0, 0, 255))
            TuiPainter.inheritStyles(l)
            assert(l.fg(child) == TuiColor.pack(0, 0, 255))
        }

        "element does NOT inherit parent pFlags" in {
            val l     = mkLayout()
            val bold  = 1 << TuiLayout.BoldBit
            val root  = addNode(l, -1, pFlags = bold)
            val child = addNode(l, root, nodeType = NodeDiv)
            TuiPainter.inheritStyles(l)
            assert(!TuiLayout.isBold(l.pFlags(child)))
        }

        "multi-level inheritance" in {
            val l    = mkLayout()
            val root = addNode(l, -1, fg = TuiColor.pack(255, 0, 0))
            val mid  = addNode(l, root, nodeType = NodeDiv)
            val leaf = addNode(l, mid, nodeType = NodeText, text = Present("deep"))
            TuiPainter.inheritStyles(l)
            assert(l.fg(mid) == TuiColor.pack(255, 0, 0))
            assert(l.fg(leaf) == TuiColor.pack(255, 0, 0))
        }

        "opacity blending" in {
            val l    = mkLayout()
            val root = addNode(l, -1, bg = TuiColor.pack(0, 0, 0))
            val child = addNode(
                l,
                root,
                nodeType = NodeDiv,
                fg = TuiColor.pack(255, 255, 255),
                bg = TuiColor.pack(255, 255, 255),
                opacity = 0.5f
            )
            TuiPainter.inheritStyles(l)
            // Blended: 50% white over black = ~128
            val blendedFg = l.fg(child)
            val r         = TuiColor.r(blendedFg)
            assert(r >= 120 && r <= 136, s"Expected ~128, got $r")
        }

        "no nodes does not crash" in {
            val l = mkLayout()
            TuiPainter.inheritStyles(l)
            succeed
        }

        "single node does not crash" in {
            val l = mkLayout()
            addNode(l, -1)
            TuiPainter.inheritStyles(l)
            succeed
        }
    }

    // ──────────────────────── paint ────────────────────────

    "paint" - {
        "empty layout does not crash" in {
            val l = mkLayout()
            val r = new TuiRenderer(10, 5)
            r.clear()
            TuiPainter.paint(l, r)
            succeed
        }

        "hidden node is skipped" in {
            val l    = mkLayout()
            val root = addNode(l, -1, bg = TuiColor.pack(255, 0, 0))
            l.x(root) = 0; l.y(root) = 0; l.w(root) = 5; l.h(root) = 1
            l.lFlags(root) = l.lFlags(root) | (1 << HiddenBit)
            val r = new TuiRenderer(5, 1)
            r.clear()
            TuiPainter.paint(l, r)
            // Should produce empty output since the only node is hidden
            val out = flushToString(r)
            assert(!out.contains("48;2;255;0;0"))
        }

        "background fill" in {
            val l    = mkLayout()
            val root = addNode(l, -1, bg = TuiColor.pack(10, 20, 30))
            l.x(root) = 0; l.y(root) = 0; l.w(root) = 3; l.h(root) = 1
            val r = new TuiRenderer(3, 1)
            r.clear()
            TuiPainter.paint(l, r)
            val out = flushToString(r)
            assert(out.contains("48;2;10;20;30"))
        }

        "text rendering" in {
            val l    = mkLayout()
            val root = addNode(l, -1, fg = TuiColor.pack(255, 255, 255), text = Present("AB"))
            l.x(root) = 0; l.y(root) = 0; l.w(root) = 5; l.h(root) = 1
            val r = new TuiRenderer(5, 1)
            r.clear()
            TuiPainter.paint(l, r)
            val out = flushToString(r)
            assert(out.contains("AB"))
        }

        "text with bold style" in {
            val l    = mkLayout()
            val bold = 1 << TuiLayout.BoldBit
            val root = addNode(l, -1, fg = TuiColor.pack(255, 255, 255), text = Present("X"), pFlags = bold)
            l.x(root) = 0; l.y(root) = 0; l.w(root) = 3; l.h(root) = 1
            val r = new TuiRenderer(3, 1)
            r.clear()
            TuiPainter.paint(l, r)
            val out = flushToString(r)
            // Bold SGR code is \e[1m
            assert(out.contains("\u001b[1"))
        }

        "text center alignment" in {
            val l    = mkLayout()
            val pf   = TuiLayout.TextAlignCenter << TuiLayout.TextAlignShift
            val root = addNode(l, -1, fg = TuiColor.pack(255, 255, 255), text = Present("Hi"), pFlags = pf)
            l.x(root) = 0; l.y(root) = 0; l.w(root) = 10; l.h(root) = 1
            val r = new TuiRenderer(10, 1)
            r.clear()
            TuiPainter.paint(l, r)
            val out = flushToString(r)
            // "Hi" centered in 10 cols: starts at col 4 (0-indexed)
            // ANSI cursor position is 1-indexed: col 5
            assert(out.contains("Hi"))
        }

        "text right alignment" in {
            val l    = mkLayout()
            val pf   = TuiLayout.TextAlignRight << TuiLayout.TextAlignShift
            val root = addNode(l, -1, fg = TuiColor.pack(255, 255, 255), text = Present("Hi"), pFlags = pf)
            l.x(root) = 0; l.y(root) = 0; l.w(root) = 10; l.h(root) = 1
            val r = new TuiRenderer(10, 1)
            r.clear()
            TuiPainter.paint(l, r)
            val out = flushToString(r)
            assert(out.contains("Hi"))
        }

        "text uppercase transform" in {
            val l    = mkLayout()
            val pf   = 1 << TuiLayout.TextTransShift // Uppercase = 1
            val root = addNode(l, -1, fg = TuiColor.pack(255, 255, 255), text = Present("hello"), pFlags = pf)
            l.x(root) = 0; l.y(root) = 0; l.w(root) = 10; l.h(root) = 1
            val r = new TuiRenderer(10, 1)
            r.clear()
            TuiPainter.paint(l, r)
            val out = flushToString(r)
            assert(out.contains("HELLO"))
        }

        "text lowercase transform" in {
            val l    = mkLayout()
            val pf   = 2 << TuiLayout.TextTransShift // Lowercase = 2
            val root = addNode(l, -1, fg = TuiColor.pack(255, 255, 255), text = Present("HELLO"), pFlags = pf)
            l.x(root) = 0; l.y(root) = 0; l.w(root) = 10; l.h(root) = 1
            val r = new TuiRenderer(10, 1)
            r.clear()
            TuiPainter.paint(l, r)
            val out = flushToString(r)
            assert(out.contains("hello"))
        }

        "text capitalize transform" in {
            val l    = mkLayout()
            val pf   = 3 << TuiLayout.TextTransShift // Capitalize = 3
            val root = addNode(l, -1, fg = TuiColor.pack(255, 255, 255), text = Present("hello world"), pFlags = pf)
            l.x(root) = 0; l.y(root) = 0; l.w(root) = 20; l.h(root) = 1
            val r = new TuiRenderer(20, 1)
            r.clear()
            TuiPainter.paint(l, r)
            val out = flushToString(r)
            assert(out.contains("Hello World"))
        }

        "border rendering solid" in {
            val l    = mkLayout()
            val lf   = (1 << BorderTBit) | (1 << BorderRBit) | (1 << BorderBBit) | (1 << BorderLBit)
            val pf   = TuiLayout.BorderThin << TuiLayout.BorderStyleShift
            val root = addNode(l, -1, fg = TuiColor.pack(200, 200, 200), pFlags = pf)
            l.lFlags(root) = l.lFlags(root) | lf
            l.x(root) = 0; l.y(root) = 0; l.w(root) = 5; l.h(root) = 3
            val r = new TuiRenderer(5, 3)
            r.clear()
            TuiPainter.paint(l, r)
            val out = flushToString(r)
            // Should contain box drawing characters
            assert(out.contains("┌") || out.contains("─") || out.contains("│"))
        }

        "border rendering rounded" in {
            val l  = mkLayout()
            val lf = (1 << BorderTBit) | (1 << BorderRBit) | (1 << BorderBBit) | (1 << BorderLBit)
            val pf = (TuiLayout.BorderThin << TuiLayout.BorderStyleShift) |
                (1 << TuiLayout.RoundedTLBit) | (1 << TuiLayout.RoundedTRBit) |
                (1 << TuiLayout.RoundedBRBit) | (1 << TuiLayout.RoundedBLBit)
            val root = addNode(l, -1, fg = TuiColor.pack(200, 200, 200), pFlags = pf)
            l.lFlags(root) = l.lFlags(root) | lf
            l.x(root) = 0; l.y(root) = 0; l.w(root) = 5; l.h(root) = 3
            val r = new TuiRenderer(5, 3)
            r.clear()
            TuiPainter.paint(l, r)
            val out = flushToString(r)
            assert(out.contains("╭") || out.contains("╰"))
        }

        "per-side border colors" in {
            val l    = mkLayout()
            val lf   = (1 << BorderTBit) | (1 << BorderRBit) | (1 << BorderBBit) | (1 << BorderLBit)
            val pf   = TuiLayout.BorderThin << TuiLayout.BorderStyleShift
            val root = addNode(l, -1, pFlags = pf)
            l.lFlags(root) = l.lFlags(root) | lf
            l.bdrClrT(root) = TuiColor.pack(255, 0, 0)
            l.bdrClrR(root) = TuiColor.pack(0, 255, 0)
            l.bdrClrB(root) = TuiColor.pack(0, 0, 255)
            l.bdrClrL(root) = TuiColor.pack(255, 255, 0)
            l.x(root) = 0; l.y(root) = 0; l.w(root) = 5; l.h(root) = 3
            val r = new TuiRenderer(5, 3)
            r.clear()
            TuiPainter.paint(l, r)
            val out = flushToString(r)
            // Should contain different color SGR codes for different sides
            assert(out.contains("38;2;255;0;0")) // top red
            assert(out.contains("38;2;0;0;255")) // bottom blue
        }

        "child rendered after parent" in {
            val l      = mkLayout()
            val parent = addNode(l, -1, bg = TuiColor.pack(0, 0, 0))
            val child  = addNode(l, parent, fg = TuiColor.pack(255, 255, 255), text = Present("X"))
            l.x(parent) = 0; l.y(parent) = 0; l.w(parent) = 5; l.h(parent) = 1
            l.x(child) = 1; l.y(child) = 0; l.w(child) = 1; l.h(child) = 1
            val r = new TuiRenderer(5, 1)
            r.clear()
            TuiPainter.paint(l, r)
            val out = flushToString(r)
            assert(out.contains("X"))
        }

        "zero-size node skipped" in {
            val l    = mkLayout()
            val root = addNode(l, -1, bg = TuiColor.pack(255, 0, 0))
            l.x(root) = 0; l.y(root) = 0; l.w(root) = 0; l.h(root) = 0
            val r = new TuiRenderer(5, 1)
            r.clear()
            TuiPainter.paint(l, r)
            val out = flushToString(r)
            assert(!out.contains("48;2;255;0;0"))
        }

        "text clipped to content area with padding" in {
            val l    = mkLayout()
            val root = addNode(l, -1, fg = TuiColor.pack(255, 255, 255), text = Present("ABCDE"))
            l.x(root) = 0; l.y(root) = 0; l.w(root) = 5; l.h(root) = 1
            l.padL(root) = 1; l.padR(root) = 1
            val r = new TuiRenderer(5, 1)
            r.clear()
            TuiPainter.paint(l, r)
            val out = flushToString(r)
            // Content width = 5 - 1 - 1 = 3, so only "ABC" fits
            assert(out.contains("ABC"))
            assert(!out.contains("ABCDE"))
        }

        "overflow hidden clips child text" in {
            val l      = mkLayout()
            val parent = addNode(l, -1, fg = TuiColor.pack(255, 255, 255))
            // Set overflow:hidden on parent
            l.lFlags(parent) = l.lFlags(parent) | (1 << TuiLayout.OverflowShift)
            l.x(parent) = 0; l.y(parent) = 0; l.w(parent) = 5; l.h(parent) = 1

            val child = addNode(l, parent, fg = TuiColor.pack(255, 255, 255), text = Present("ABCDEFGHIJ"))
            l.x(child) = 0; l.y(child) = 0; l.w(child) = 10; l.h(child) = 1

            val r = new TuiRenderer(10, 1)
            r.clear()
            TuiPainter.paint(l, r)
            val out = flushToString(r)
            // Child text extends to 10 chars but parent clips to 5
            assert(out.contains("ABCDE"))
            assert(!out.contains("FGHIJ"))
        }

        "text wrapping" in {
            val l    = mkLayout()
            val pf   = 1 << TuiLayout.WrapTextBit
            val root = addNode(l, -1, fg = TuiColor.pack(255, 255, 255), text = Present("hello world"), pFlags = pf)
            l.x(root) = 0; l.y(root) = 0; l.w(root) = 6; l.h(root) = 3
            val r = new TuiRenderer(6, 3)
            r.clear()
            TuiPainter.paint(l, r)
            val out = flushToString(r)
            // "hello world" wrapped at width 6 → "hello " and "world"
            assert(out.contains("hello"))
            assert(out.contains("world"))
        }

        "text with border inside content area" in {
            val l    = mkLayout()
            val lf   = (1 << BorderTBit) | (1 << BorderRBit) | (1 << BorderBBit) | (1 << BorderLBit)
            val pf   = TuiLayout.BorderThin << TuiLayout.BorderStyleShift
            val root = addNode(l, -1, fg = TuiColor.pack(255, 255, 255), text = Present("Hi"), pFlags = pf)
            l.lFlags(root) = l.lFlags(root) | lf
            l.x(root) = 0; l.y(root) = 0; l.w(root) = 6; l.h(root) = 3
            val r = new TuiRenderer(6, 3)
            r.clear()
            TuiPainter.paint(l, r)
            val out = flushToString(r)
            // Text "Hi" rendered inside borders
            assert(out.contains("Hi"))
            assert(out.contains("─"))
        }

        "partial borders top-only" in {
            val l    = mkLayout()
            val lf   = 1 << BorderTBit // only top border
            val pf   = TuiLayout.BorderThin << TuiLayout.BorderStyleShift
            val root = addNode(l, -1, fg = TuiColor.pack(200, 200, 200), pFlags = pf)
            l.lFlags(root) = l.lFlags(root) | lf
            l.x(root) = 0; l.y(root) = 0; l.w(root) = 5; l.h(root) = 3
            val r = new TuiRenderer(5, 3)
            r.clear()
            TuiPainter.paint(l, r)
            val out = flushToString(r)
            // Top border drawn, no side chars
            assert(out.contains("─"))
            assert(!out.contains("│"))
        }

        "text with ellipsis overflow" in {
            val l    = mkLayout()
            val pf   = 1 << TuiLayout.TextOverflowBit
            val root = addNode(l, -1, fg = TuiColor.pack(255, 255, 255), text = Present("ABCDEFGHIJ"), pFlags = pf)
            l.x(root) = 0; l.y(root) = 0; l.w(root) = 5; l.h(root) = 1
            val r = new TuiRenderer(5, 1)
            r.clear()
            TuiPainter.paint(l, r)
            val out = flushToString(r)
            // Should be clipped with ellipsis
            assert(!out.contains("ABCDEFGHIJ"))
        }

        "italic style" in {
            val l    = mkLayout()
            val pf   = 1 << TuiLayout.ItalicBit
            val root = addNode(l, -1, fg = TuiColor.pack(255, 255, 255), text = Present("X"), pFlags = pf)
            l.x(root) = 0; l.y(root) = 0; l.w(root) = 3; l.h(root) = 1
            val r = new TuiRenderer(3, 1)
            r.clear()
            TuiPainter.paint(l, r)
            val out = flushToString(r)
            assert(out.contains("\u001b[3")) // italic SGR
        }

        "underline style" in {
            val l    = mkLayout()
            val pf   = 1 << TuiLayout.UnderlineBit
            val root = addNode(l, -1, fg = TuiColor.pack(255, 255, 255), text = Present("X"), pFlags = pf)
            l.x(root) = 0; l.y(root) = 0; l.w(root) = 3; l.h(root) = 1
            val r = new TuiRenderer(3, 1)
            r.clear()
            TuiPainter.paint(l, r)
            val out = flushToString(r)
            assert(out.contains("\u001b[4")) // underline SGR
        }

        "strikethrough style" in {
            val l    = mkLayout()
            val pf   = 1 << TuiLayout.StrikethroughBit
            val root = addNode(l, -1, fg = TuiColor.pack(255, 255, 255), text = Present("X"), pFlags = pf)
            l.x(root) = 0; l.y(root) = 0; l.w(root) = 3; l.h(root) = 1
            val r = new TuiRenderer(3, 1)
            r.clear()
            TuiPainter.paint(l, r)
            val out = flushToString(r)
            assert(out.contains("\u001b[9")) // strikethrough SGR
        }

        "empty text does not crash" in {
            val l    = mkLayout()
            val root = addNode(l, -1, fg = TuiColor.pack(255, 255, 255), text = Present(""))
            l.x(root) = 0; l.y(root) = 0; l.w(root) = 5; l.h(root) = 1
            val r = new TuiRenderer(5, 1)
            r.clear()
            TuiPainter.paint(l, r)
            succeed
        }

        "multiple siblings rendered in order" in {
            val l      = mkLayout()
            val parent = addNode(l, -1)
            l.x(parent) = 0; l.y(parent) = 0; l.w(parent) = 10; l.h(parent) = 1
            val c1 = addNode(l, parent, fg = TuiColor.pack(255, 255, 255), text = Present("A"))
            l.x(c1) = 0; l.y(c1) = 0; l.w(c1) = 1; l.h(c1) = 1
            val c2 = addNode(l, parent, fg = TuiColor.pack(255, 255, 255), text = Present("B"))
            l.x(c2) = 2; l.y(c2) = 0; l.w(c2) = 1; l.h(c2) = 1
            val r = new TuiRenderer(10, 1)
            r.clear()
            TuiPainter.paint(l, r)
            val out = flushToString(r)
            assert(out.contains("A"))
            assert(out.contains("B"))
        }
    }

    // ──────────────────────── text transforms ────────────────────────

    "capitalize" - {
        "capitalizes each word" in {
            val l    = mkLayout()
            val pf   = 3 << TuiLayout.TextTransShift
            val root = addNode(l, -1, fg = TuiColor.pack(255, 255, 255), text = Present("foo bar baz"), pFlags = pf)
            l.x(root) = 0; l.y(root) = 0; l.w(root) = 20; l.h(root) = 1
            val r = new TuiRenderer(20, 1)
            r.clear()
            TuiPainter.paint(l, r)
            val out = flushToString(r)
            assert(out.contains("Foo Bar Baz"))
        }
    }

    // ──────────────────────── Bug: text wrapping never triggers ────────────────────────

    "text wrapping in paint" - {
        "long text in narrow box should wrap when WrapTextBit is set" in {
            val l    = mkLayout()
            val pf   = 1 << TuiLayout.WrapTextBit
            val root = addNode(l, -1, fg = TuiColor.pack(255, 255, 255), text = Present("hello world"), pFlags = pf)
            l.x(root) = 0; l.y(root) = 0; l.w(root) = 6; l.h(root) = 3
            val r = new TuiRenderer(6, 3)
            r.clear()
            TuiPainter.paint(l, r)
            val out = flushToString(r)
            // "hello " on line 0, "world" on line 1
            assert(out.contains("hello"))
            assert(out.contains("world"))
        }

        "long text without WrapTextBit should clip, not wrap" in {
            val l    = mkLayout()
            val root = addNode(l, -1, fg = TuiColor.pack(255, 255, 255), text = Present("hello world"))
            l.x(root) = 0; l.y(root) = 0; l.w(root) = 6; l.h(root) = 1
            val r = new TuiRenderer(6, 1)
            r.clear()
            TuiPainter.paint(l, r)
            val out = flushToString(r)
            // Without wrapping, text should be clipped to 6 chars
            assert(out.contains("hello "))
            assert(!out.contains("world"))
        }
    }

    // ──────────────────────── Bug: pFlags overwrite for text nodes ────────────────────────

    "text node pFlags inheritance" - {
        "text node with bold parent renders bold" in {
            val l    = mkLayout()
            val bold = 1 << TuiLayout.BoldBit
            val root = addNode(l, -1, fg = TuiColor.pack(255, 255, 255), pFlags = bold)
            l.x(root) = 0; l.y(root) = 0; l.w(root) = 10; l.h(root) = 1
            val text = addNode(l, root, nodeType = NodeText, fg = TuiColor.Absent, text = Present("bold"))
            l.x(text) = 0; l.y(text) = 0; l.w(text) = 4; l.h(text) = 1
            TuiPainter.inheritStyles(l)
            // Text node should have bold from parent
            assert(TuiLayout.isBold(l.pFlags(text)))
            val r = new TuiRenderer(10, 1)
            r.clear()
            TuiPainter.paint(l, r)
            val out = flushToString(r)
            assert(out.contains("\u001b[1m")) // SGR bold
        }

        "text node with italic parent and bold grandparent gets parent pFlags" in {
            // Hierarchy: root(bold) > div(italic) > text
            // Text should get italic (from direct parent), not bold
            val l      = mkLayout()
            val bold   = 1 << TuiLayout.BoldBit
            val italic = 1 << TuiLayout.ItalicBit
            val root   = addNode(l, -1, fg = TuiColor.pack(255, 255, 255), pFlags = bold)
            l.x(root) = 0; l.y(root) = 0; l.w(root) = 10; l.h(root) = 1
            val mid = addNode(l, root, nodeType = NodeDiv, pFlags = italic)
            l.x(mid) = 0; l.y(mid) = 0; l.w(mid) = 10; l.h(mid) = 1
            val text = addNode(l, mid, nodeType = NodeText, text = Present("text"))
            l.x(text) = 0; l.y(text) = 0; l.w(text) = 4; l.h(text) = 1
            TuiPainter.inheritStyles(l)
            // Text inherits from direct parent (mid), which has italic but NOT bold
            // (elements don't inherit pFlags, only fg/bg)
            assert(TuiLayout.isItalic(l.pFlags(text)))
            // Note: bold is NOT inherited to mid (element), so text doesn't get it
            assert(!TuiLayout.isBold(l.pFlags(text)))
        }
    }

end TuiPainterTest
