package kyo

import kyo.Maybe.*
import kyo.internal.TuiColor
import kyo.internal.TuiLayout
import kyo.internal.TuiRenderer
import kyo.internal.TuiRenderer.*

class TuiRichDemo extends Test:

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

    // Cell for the colored grid
    case class Cell(ch: Char, fg: Int, bg: Int, bold: Boolean = false, dim: Boolean = false, italic: Boolean = false)

    private def renderAnsi(width: Int, height: Int, buildFn: (TuiLayout, Array[Array[Cell]]) => Unit): String =
        val layout = new TuiLayout(128)
        val grid   = Array.fill(height)(Array.fill(width)(Cell(' ', -1, -1)))
        buildFn(layout, grid)

        val sb = new StringBuilder()
        sb.append("\n")
        var row = 0
        while row < height do
            var col        = 0
            var lastFg     = -2
            var lastBg     = -2
            var lastBold   = false
            var lastDim    = false
            var lastItalic = false
            while col < width do
                val cell = grid(row)(col)
                val needStyle = cell.fg != lastFg || cell.bg != lastBg ||
                    cell.bold != lastBold || cell.dim != lastDim || cell.italic != lastItalic
                if needStyle then
                    sb.append("\u001b[0m") // reset
                    if cell.bold then sb.append("\u001b[1m")
                    if cell.dim then sb.append("\u001b[2m")
                    if cell.italic then sb.append("\u001b[3m")
                    if cell.fg != -1 then
                        sb.append("\u001b[38;2;")
                        sb.append(TuiColor.r(cell.fg)); sb.append(';')
                        sb.append(TuiColor.g(cell.fg)); sb.append(';')
                        sb.append(TuiColor.b(cell.fg)); sb.append('m')
                    end if
                    if cell.bg != -1 then
                        sb.append("\u001b[48;2;")
                        sb.append(TuiColor.r(cell.bg)); sb.append(';')
                        sb.append(TuiColor.g(cell.bg)); sb.append(';')
                        sb.append(TuiColor.b(cell.bg)); sb.append('m')
                    end if
                    lastFg = cell.fg; lastBg = cell.bg
                    lastBold = cell.bold; lastDim = cell.dim; lastItalic = cell.italic
                end if
                sb.append(cell.ch)
                col += 1
            end while
            sb.append("\u001b[0m")
            sb.append('\n')
            row += 1
        end while
        sb.toString
    end renderAnsi

    // Painting helpers
    private def fillRect(grid: Array[Array[Cell]], x: Int, y: Int, w: Int, h: Int, bg: Int): Unit =
        var row = y
        while row < y + h && row < grid.length do
            if row >= 0 then
                var col = x
                while col < x + w && col < grid(0).length do
                    if col >= 0 then grid(row)(col) = grid(row)(col).copy(bg = bg)
                    col += 1
                end while
            end if
            row += 1
        end while
    end fillRect

    private def drawText(
        grid: Array[Array[Cell]],
        x: Int,
        y: Int,
        text: String,
        fg: Int,
        bg: Int = -1,
        bold: Boolean = false,
        dim: Boolean = false,
        italic: Boolean = false
    ): Unit =
        var i = 0
        while i < text.length do
            val gx = x + i
            if y >= 0 && y < grid.length && gx >= 0 && gx < grid(0).length then
                val existingBg = if bg == -1 then grid(y)(gx).bg else bg
                grid(y)(gx) = Cell(text.charAt(i), fg, existingBg, bold, dim, italic)
            i += 1
        end while
    end drawText

    private def drawBorder(grid: Array[Array[Cell]], x: Int, y: Int, w: Int, h: Int, fg: Int, rounded: Boolean = false): Unit =
        val tl     = if rounded then '╭' else '┌'
        val tr     = if rounded then '╮' else '┐'
        val bl     = if rounded then '╰' else '└'
        val br     = if rounded then '╯' else '┘'
        val hz     = '─'; val vt            = '│'
        val height = grid.length; val width = grid(0).length

        if y >= 0 && y < height then
            if x >= 0 && x < width then grid(y)(x) = grid(y)(x).copy(ch = tl, fg = fg)
            if x + w - 1 >= 0 && x + w - 1 < width then grid(y)(x + w - 1) = grid(y)(x + w - 1).copy(ch = tr, fg = fg)
            var cx = x + 1
            while cx < x + w - 1 do
                if cx >= 0 && cx < width then grid(y)(cx) = grid(y)(cx).copy(ch = hz, fg = fg)
                cx += 1
        end if
        if y + h - 1 >= 0 && y + h - 1 < height then
            if x >= 0 && x < width then grid(y + h - 1)(x) = grid(y + h - 1)(x).copy(ch = bl, fg = fg)
            if x + w - 1 >= 0 && x + w - 1 < width then grid(y + h - 1)(x + w - 1) = grid(y + h - 1)(x + w - 1).copy(ch = br, fg = fg)
            var cx = x + 1
            while cx < x + w - 1 do
                if cx >= 0 && cx < width then grid(y + h - 1)(cx) = grid(y + h - 1)(cx).copy(ch = hz, fg = fg)
                cx += 1
        end if
        var cy = y + 1
        while cy < y + h - 1 do
            if cy >= 0 && cy < height then
                if x >= 0 && x < width then grid(cy)(x) = grid(cy)(x).copy(ch = vt, fg = fg)
                if x + w - 1 >= 0 && x + w - 1 < width then grid(cy)(x + w - 1) = grid(cy)(x + w - 1).copy(ch = vt, fg = fg)
            cy += 1
        end while
    end drawBorder

    private def drawHeavyBorder(grid: Array[Array[Cell]], x: Int, y: Int, w: Int, h: Int, fg: Int): Unit =
        val height = grid.length; val width = grid(0).length
        if y >= 0 && y < height then
            if x >= 0 && x < width then grid(y)(x) = grid(y)(x).copy(ch = '┏', fg = fg)
            if x + w - 1 >= 0 && x + w - 1 < width then grid(y)(x + w - 1) = grid(y)(x + w - 1).copy(ch = '┓', fg = fg)
            var cx = x + 1;
            while cx < x + w - 1 do
                if cx >= 0 && cx < width then grid(y)(cx) = grid(y)(cx).copy(ch = '━', fg = fg)
                cx += 1
        end if
        if y + h - 1 >= 0 && y + h - 1 < height then
            if x >= 0 && x < width then grid(y + h - 1)(x) = grid(y + h - 1)(x).copy(ch = '┗', fg = fg)
            if x + w - 1 >= 0 && x + w - 1 < width then grid(y + h - 1)(x + w - 1) = grid(y + h - 1)(x + w - 1).copy(ch = '┛', fg = fg)
            var cx = x + 1;
            while cx < x + w - 1 do
                if cx >= 0 && cx < width then grid(y + h - 1)(cx) = grid(y + h - 1)(cx).copy(ch = '━', fg = fg)
                cx += 1
        end if
        var cy = y + 1;
        while cy < y + h - 1 do
            if cy >= 0 && cy < height then
                if x >= 0 && x < width then grid(cy)(x) = grid(cy)(x).copy(ch = '┃', fg = fg)
                if x + w - 1 >= 0 && x + w - 1 < width then grid(cy)(x + w - 1) = grid(cy)(x + w - 1).copy(ch = '┃', fg = fg)
            cy += 1
        end while
    end drawHeavyBorder

    // Colors
    val slate900  = TuiColor.pack(15, 23, 42)
    val slate800  = TuiColor.pack(30, 41, 59)
    val slate700  = TuiColor.pack(51, 65, 85)
    val slate600  = TuiColor.pack(71, 85, 105)
    val slate400  = TuiColor.pack(148, 163, 184)
    val slate300  = TuiColor.pack(203, 213, 225)
    val slate200  = TuiColor.pack(226, 232, 240)
    val slate100  = TuiColor.pack(241, 245, 249)
    val white     = TuiColor.pack(255, 255, 255)
    val indigo500 = TuiColor.pack(99, 102, 241)
    val indigo600 = TuiColor.pack(79, 70, 229)
    val indigo400 = TuiColor.pack(129, 140, 248)
    val green500  = TuiColor.pack(34, 197, 94)
    val green400  = TuiColor.pack(74, 222, 128)
    val green900  = TuiColor.pack(20, 83, 45)
    val red500    = TuiColor.pack(239, 68, 68)
    val red400    = TuiColor.pack(248, 113, 113)
    val red900    = TuiColor.pack(127, 29, 29)
    val amber500  = TuiColor.pack(245, 158, 11)
    val amber400  = TuiColor.pack(251, 191, 36)
    val blue500   = TuiColor.pack(59, 130, 246)
    val blue400   = TuiColor.pack(96, 165, 250)
    val cyan500   = TuiColor.pack(6, 182, 212)
    val violet500 = TuiColor.pack(139, 92, 246)

    "dashboard" in {
        val W = 60; val H = 24
        val output = renderAnsi(
            W,
            H,
            (layout, grid) =>

                // Background
                fillRect(grid, 0, 0, W, H, slate900)

                // ═══ Top bar ═══
                fillRect(grid, 0, 0, W, 1, slate800)
                drawText(grid, 2, 0, "◆ kyo", indigo400, slate800, bold = true)
                drawText(grid, 10, 0, "Dashboard", slate300, slate800)
                drawText(grid, 22, 0, "Settings", slate400, slate800)
                drawText(grid, 33, 0, "Docs", slate400, slate800)
                drawText(grid, W - 12, 0, "▣ admin", slate300, slate800)

                // ═══ Page title ═══
                drawText(grid, 3, 2, "Dashboard", white, bold = true)
                drawText(grid, 15, 2, "Overview of your application", slate400)

                // ═══ Stats row ═══
                // Card 1: Total Users
                fillRect(grid, 2, 4, 17, 5, slate800)
                drawBorder(grid, 2, 4, 17, 5, slate700, rounded = true)
                drawText(grid, 4, 5, "Total Users", slate400)
                drawText(grid, 4, 6, "12,847", white, bold = true)
                drawText(grid, 4, 7, "↑ 12.5%", green400)

                // Card 2: Revenue
                fillRect(grid, 21, 4, 17, 5, slate800)
                drawBorder(grid, 21, 4, 17, 5, slate700, rounded = true)
                drawText(grid, 23, 5, "Revenue", slate400)
                drawText(grid, 23, 6, "$48,290", white, bold = true)
                drawText(grid, 23, 7, "↑ 8.2%", green400)

                // Card 3: Active Now
                fillRect(grid, 40, 4, 17, 5, slate800)
                drawBorder(grid, 40, 4, 17, 5, slate700, rounded = true)
                drawText(grid, 42, 5, "Active Now", slate400)
                drawText(grid, 42, 6, "342", white, bold = true)
                drawText(grid, 42, 7, "↓ 3.1%", red400)

                // ═══ Chart area ═══
                fillRect(grid, 2, 10, 35, 9, slate800)
                drawBorder(grid, 2, 10, 35, 9, slate700, rounded = true)
                drawText(grid, 4, 11, "Traffic (last 7 days)", slate300, bold = true)

                // Mini bar chart
                val bars = Array(5, 7, 4, 8, 6, 9, 7)
                val days = Array("M", "T", "W", "T", "F", "S", "S")
                var bi   = 0
                while bi < bars.length do
                    val bx = 6 + bi * 4
                    val bh = bars(bi)
                    var by = 17 - bh
                    while by <= 17 do
                        if by >= 12 && by < 18 then
                            fillRect(grid, bx, by, 3, 1, indigo500)
                        by += 1
                    end while
                    drawText(grid, bx + 1, 18, days(bi), slate400)
                    bi += 1
                end while

                // ═══ Recent activity ═══
                fillRect(grid, 39, 10, 19, 9, slate800)
                drawBorder(grid, 39, 10, 19, 9, slate700, rounded = true)
                drawText(grid, 41, 11, "Recent", slate300, bold = true)
                drawText(grid, 41, 13, "● New signup", green400, dim = false)
                drawText(grid, 41, 14, "● Payment", blue400)
                drawText(grid, 41, 15, "● Error 500", red400)
                drawText(grid, 41, 16, "● Deploy v2.4", violet500)
                drawText(grid, 41, 17, "● New signup", green400)

                // ═══ Status bar ═══
                fillRect(grid, 2, 20, 55, 3, slate800)
                drawBorder(grid, 2, 20, 55, 3, slate700, rounded = true)

                // Status badges
                drawText(grid, 4, 21, " ● API ", green400, green900, bold = true)
                drawText(grid, 13, 21, " ● DB  ", green400, green900, bold = true)
                drawText(grid, 22, 21, " ● CDN ", amber500, slate700, bold = true)
                drawText(grid, 31, 21, "Latency: 42ms", slate400)
                drawText(grid, 47, 21, "v2.4.1", slate600)
        )
        java.lang.System.err.println(output)
        succeed
    }

end TuiRichDemo
