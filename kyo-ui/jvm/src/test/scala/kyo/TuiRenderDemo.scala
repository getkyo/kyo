package kyo

import kyo.Maybe
import kyo.Maybe.*
import kyo.internal.TuiColor
import kyo.internal.TuiLayout
import kyo.internal.TuiRenderer
import kyo.internal.TuiRenderer.*

class TuiRenderDemo extends Test:

    private def initNode(layout: TuiLayout, idx: Int): Unit =
        layout.lFlags(idx) = 0; layout.pFlags(idx) = 0
        layout.padT(idx) = 0; layout.padR(idx) = 0; layout.padB(idx) = 0; layout.padL(idx) = 0
        layout.marT(idx) = 0; layout.marR(idx) = 0; layout.marB(idx) = 0; layout.marL(idx) = 0
        layout.gap(idx) = 0; layout.sizeW(idx) = -1; layout.sizeH(idx) = -1
        layout.minW(idx) = -1; layout.maxW(idx) = -1; layout.minH(idx) = -1; layout.maxH(idx) = -1
        layout.transX(idx) = 0; layout.transY(idx) = 0
        layout.fg(idx) = -1; layout.bg(idx) = -1
        layout.bdrClrT(idx) = -1; layout.bdrClrR(idx) = -1; layout.bdrClrB(idx) = -1; layout.bdrClrL(idx) = -1
        layout.opac(idx) = 1.0f; layout.shadow(idx) = -1
        layout.lineH(idx) = 0; layout.letSp(idx) = 0; layout.fontSz(idx) = 1
        layout.text(idx) = Absent; layout.focusStyle(idx) = Absent; layout.activeStyle(idx) = Absent
        layout.element(idx) = Absent; layout.nodeType(idx) = 0
        layout.parent(idx) = -1; layout.firstChild(idx) = -1
        layout.nextSibling(idx) = -1; layout.lastChild(idx) = -1
    end initNode

    private def allocNode(layout: TuiLayout): Int =
        val idx = layout.alloc()
        initNode(layout, idx)
        idx
    end allocNode

    private def paint(layout: TuiLayout, renderer: TuiRenderer): Unit =
        var i = 0
        while i < layout.count do
            val flags = layout.lFlags(i)
            if !TuiLayout.isHidden(flags) then
                val bgColor = layout.bg(i)
                if bgColor != TuiColor.Absent then
                    renderer.fillBg(layout.x(i), layout.y(i), layout.w(i), layout.h(i), bgColor)

                val bT = TuiLayout.hasBorderT(flags)
                val bR = TuiLayout.hasBorderR(flags)
                val bB = TuiLayout.hasBorderB(flags)
                val bL = TuiLayout.hasBorderL(flags)
                if bT || bR || bB || bL then
                    val x0    = layout.x(i); val y0 = layout.y(i)
                    val w0    = layout.w(i); val h0 = layout.h(i)
                    val style = packStyle(fg = layout.fg(i))
                    if bT && bL then renderer.set(x0, y0, '\u250c', style)
                    if bT && bR then renderer.set(x0 + w0 - 1, y0, '\u2510', style)
                    if bB && bL then renderer.set(x0, y0 + h0 - 1, '\u2514', style)
                    if bB && bR then renderer.set(x0 + w0 - 1, y0 + h0 - 1, '\u2518', style)
                    if bT then
                        var cx = (if bL then x0 + 1 else x0)
                        val ex = (if bR then x0 + w0 - 1 else x0 + w0)
                        while cx < ex do renderer.set(cx, y0, '\u2500', style); cx += 1
                    end if
                    if bB then
                        var cx = (if bL then x0 + 1 else x0)
                        val ex = (if bR then x0 + w0 - 1 else x0 + w0)
                        while cx < ex do renderer.set(cx, y0 + h0 - 1, '\u2500', style); cx += 1
                    end if
                    if bL then
                        var cy = (if bT then y0 + 1 else y0)
                        val ey = (if bB then y0 + h0 - 1 else y0 + h0)
                        while cy < ey do renderer.set(x0, cy, '\u2502', style); cy += 1
                    end if
                    if bR then
                        var cy = (if bT then y0 + 1 else y0)
                        val ey = (if bB then y0 + h0 - 1 else y0 + h0)
                        while cy < ey do renderer.set(x0 + w0 - 1, cy, '\u2502', style); cy += 1
                    end if
                end if

                val maybeTxt = layout.text(i)
                if maybeTxt.isDefined then
                    val style = packStyle(fg = layout.fg(i))
                    val lines = maybeTxt.get.split('\n')
                    var ly    = 0
                    while ly < lines.length do
                        var lx = 0
                        while lx < lines(ly).length do
                            renderer.set(layout.x(i) + lx, layout.y(i) + ly, lines(ly).charAt(lx), style)
                            lx += 1
                        end while
                        ly += 1
                    end while
                end if
            end if
            i += 1
        end while
    end paint

    /** Render to a grid string by reading the ANSI output and stripping escape codes. */
    private def renderToString(width: Int, height: Int, paintFn: TuiRenderer => Unit): String =
        val renderer = new TuiRenderer(width, height)
        renderer.clear()
        paintFn(renderer)
        val baos = new java.io.ByteArrayOutputStream()
        renderer.flush(baos, NoColor)
        val raw = baos.toString("UTF-8")
        // Strip all ANSI escape sequences: ESC[ ... (letter)
        val stripped = raw.replaceAll("\u001b\\[[^a-zA-Z]*[a-zA-Z]", "")
            .replaceAll("\u001b\\[\\?[^a-zA-Z]*[a-zA-Z]", "")
        // Now reconstruct grid from cursor-positioned output
        // Simpler approach: just build grid from set() calls using a second approach
        // Actually let's just build it manually from layout
        ""
    end renderToString

    /** Build grid directly from layout (no ANSI needed). */
    private def layoutToString(width: Int, height: Int, paintFn: (TuiLayout, Int, Int) => Unit): String =
        val grid   = Array.fill(height)(Array.fill(width)(' '))
        val layout = new TuiLayout(64)
        paintFn(layout, width, height)

        // Paint into grid directly
        var i = 0
        while i < layout.count do
            val flags = layout.lFlags(i)
            if !TuiLayout.isHidden(flags) then
                val bT = TuiLayout.hasBorderT(flags)
                val bR = TuiLayout.hasBorderR(flags)
                val bB = TuiLayout.hasBorderB(flags)
                val bL = TuiLayout.hasBorderL(flags)
                if bT || bR || bB || bL then
                    val x0 = layout.x(i); val y0 = layout.y(i)
                    val w0 = layout.w(i); val h0 = layout.h(i)
                    if bT && bL && y0 >= 0 && y0 < height && x0 >= 0 && x0 < width then grid(y0)(x0) = '┌'
                    if bT && bR && y0 >= 0 && y0 < height && x0 + w0 - 1 >= 0 && x0 + w0 - 1 < width then grid(y0)(x0 + w0 - 1) = '┐'
                    if bB && bL && y0 + h0 - 1 >= 0 && y0 + h0 - 1 < height && x0 >= 0 && x0 < width then grid(y0 + h0 - 1)(x0) = '└'
                    if bB && bR && y0 + h0 - 1 >= 0 && y0 + h0 - 1 < height && x0 + w0 - 1 >= 0 && x0 + w0 - 1 < width then
                        grid(y0 + h0 - 1)(x0 + w0 - 1) = '┘'
                    if bT then
                        var cx = (if bL then x0 + 1 else x0)
                        val ex = (if bR then x0 + w0 - 1 else x0 + w0)
                        while cx < ex do
                            if y0 >= 0 && y0 < height && cx >= 0 && cx < width then grid(y0)(cx) = '─'
                            cx += 1
                    end if
                    if bB then
                        var cx = (if bL then x0 + 1 else x0)
                        val ex = (if bR then x0 + w0 - 1 else x0 + w0)
                        while cx < ex do
                            if y0 + h0 - 1 >= 0 && y0 + h0 - 1 < height && cx >= 0 && cx < width then grid(y0 + h0 - 1)(cx) = '─'
                            cx += 1
                    end if
                    if bL then
                        var cy = (if bT then y0 + 1 else y0)
                        val ey = (if bB then y0 + h0 - 1 else y0 + h0)
                        while cy < ey do
                            if cy >= 0 && cy < height && x0 >= 0 && x0 < width then grid(cy)(x0) = '│'
                            cy += 1
                    end if
                    if bR then
                        var cy = (if bT then y0 + 1 else y0)
                        val ey = (if bB then y0 + h0 - 1 else y0 + h0)
                        while cy < ey do
                            if cy >= 0 && cy < height && x0 + w0 - 1 >= 0 && x0 + w0 - 1 < width then grid(cy)(x0 + w0 - 1) = '│'
                            cy += 1
                    end if
                end if

                val maybeTxt2 = layout.text(i)
                if maybeTxt2.isDefined then
                    val lines = maybeTxt2.get.split('\n')
                    var ly    = 0
                    while ly < lines.length do
                        var lx = 0
                        while lx < lines(ly).length do
                            val gx = layout.x(i) + lx; val gy = layout.y(i) + ly
                            if gy >= 0 && gy < height && gx >= 0 && gx < width then
                                grid(gy)(gx) = lines(ly).charAt(lx)
                            lx += 1
                        end while
                        ly += 1
                    end while
                end if
            end if
            i += 1
        end while
        grid.map(_.mkString).mkString("\n")
    end layoutToString

    "bordered box with text" in {
        val result = layoutToString(
            30,
            5,
            (layout, w, h) =>
                val root = allocNode(layout); TuiLayout.linkChild(layout, -1, root)
                layout.lFlags(root) = (1 << TuiLayout.BorderTBit) | (1 << TuiLayout.BorderRBit) |
                    (1 << TuiLayout.BorderBBit) | (1 << TuiLayout.BorderLBit)
                layout.padL(root) = 1; layout.padR(root) = 1
                val txt = allocNode(layout); TuiLayout.linkChild(layout, root, txt)
                layout.text(txt) = Present("Hello World")
                TuiLayout.measure(layout)
                TuiLayout.arrange(layout, w, h)
        )
        java.lang.System.err.println("\n" + result + "\n")
        succeed
    }

    "login form" in {
        val result = layoutToString(
            40,
            12,
            (layout, w, h) =>
                val root = allocNode(layout); TuiLayout.linkChild(layout, -1, root)
                layout.padL(root) = 1; layout.padT(root) = 1

                val title = allocNode(layout); TuiLayout.linkChild(layout, root, title)
                layout.text(title) = Present("Login")

                val spacer = allocNode(layout); TuiLayout.linkChild(layout, root, spacer)
                layout.sizeH(spacer) = 1; layout.sizeW(spacer) = 1

                val uLabel = allocNode(layout); TuiLayout.linkChild(layout, root, uLabel)
                layout.text(uLabel) = Present("Username:")

                val uInput = allocNode(layout); TuiLayout.linkChild(layout, root, uInput)
                layout.lFlags(uInput) = (1 << TuiLayout.BorderTBit) | (1 << TuiLayout.BorderRBit) |
                    (1 << TuiLayout.BorderBBit) | (1 << TuiLayout.BorderLBit)
                layout.sizeW(uInput) = 25; layout.padL(uInput) = 1
                val uTxt = allocNode(layout); TuiLayout.linkChild(layout, uInput, uTxt)
                layout.text(uTxt) = Present("admin")

                val pLabel = allocNode(layout); TuiLayout.linkChild(layout, root, pLabel)
                layout.text(pLabel) = Present("Password:")

                val pInput = allocNode(layout); TuiLayout.linkChild(layout, root, pInput)
                layout.lFlags(pInput) = (1 << TuiLayout.BorderTBit) | (1 << TuiLayout.BorderRBit) |
                    (1 << TuiLayout.BorderBBit) | (1 << TuiLayout.BorderLBit)
                layout.sizeW(pInput) = 25; layout.padL(pInput) = 1
                val pTxt = allocNode(layout); TuiLayout.linkChild(layout, pInput, pTxt)
                layout.text(pTxt) = Present("********")

                val btn = allocNode(layout); TuiLayout.linkChild(layout, root, btn)
                layout.lFlags(btn) = (1 << TuiLayout.BorderTBit) | (1 << TuiLayout.BorderRBit) |
                    (1 << TuiLayout.BorderBBit) | (1 << TuiLayout.BorderLBit)
                layout.sizeW(btn) = 12; layout.padL(btn) = 1; layout.padR(btn) = 1
                val btnTxt = allocNode(layout); TuiLayout.linkChild(layout, btn, btnTxt)
                layout.text(btnTxt) = Present("Sign In")

                TuiLayout.measure(layout)
                TuiLayout.arrange(layout, w, h)
        )
        java.lang.System.err.println("\n" + result + "\n")
        succeed
    }

end TuiRenderDemo
