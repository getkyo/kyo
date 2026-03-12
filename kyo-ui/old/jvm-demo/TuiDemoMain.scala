package kyo

import kyo.internal.TuiColor

object TuiDemoMain:

    case class Cell(ch: Char, fg: Int, bg: Int, bold: Boolean = false, dim: Boolean = false, italic: Boolean = false)

    def main(args: Array[String]): Unit =
        val W    = 70; val H = 26
        val grid = Array.fill(H)(Array.fill(W)(Cell(' ', -1, -1)))

        // Colors
        val slate900  = TuiColor.pack(15, 23, 42)
        val slate800  = TuiColor.pack(30, 41, 59)
        val slate700  = TuiColor.pack(51, 65, 85)
        val slate600  = TuiColor.pack(71, 85, 105)
        val slate400  = TuiColor.pack(148, 163, 184)
        val slate300  = TuiColor.pack(203, 213, 225)
        val white     = TuiColor.pack(255, 255, 255)
        val indigo500 = TuiColor.pack(99, 102, 241)
        val indigo400 = TuiColor.pack(129, 140, 248)
        val green500  = TuiColor.pack(34, 197, 94)
        val green400  = TuiColor.pack(74, 222, 128)
        val green900  = TuiColor.pack(20, 83, 45)
        val red400    = TuiColor.pack(248, 113, 113)
        val amber500  = TuiColor.pack(245, 158, 11)
        val blue400   = TuiColor.pack(96, 165, 250)
        val violet500 = TuiColor.pack(139, 92, 246)
        val cyan400   = TuiColor.pack(34, 211, 238)

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

        // ═══ Stats row (wider cards) ═══
        val cardW = 20; val cardGap = 2; val cardY = 4; val cardH = 5
        // Card 1: Total Users
        fillRect(grid, 2, cardY, cardW, cardH, slate800)
        drawBorder(grid, 2, cardY, cardW, cardH, slate700, rounded = true)
        drawText(grid, 4, cardY + 1, "Total Users", slate400)
        drawText(grid, 4, cardY + 2, "12,847", white, bold = true)
        drawText(grid, 4, cardY + 3, "↑ 12.5%", green400)

        // Card 2: Revenue
        val c2x = 2 + cardW + cardGap
        fillRect(grid, c2x, cardY, cardW, cardH, slate800)
        drawBorder(grid, c2x, cardY, cardW, cardH, slate700, rounded = true)
        drawText(grid, c2x + 2, cardY + 1, "Revenue", slate400)
        drawText(grid, c2x + 2, cardY + 2, "$48,290", white, bold = true)
        drawText(grid, c2x + 2, cardY + 3, "↑ 8.2%", green400)

        // Card 3: Active Now
        val c3x = c2x + cardW + cardGap
        fillRect(grid, c3x, cardY, cardW, cardH, slate800)
        drawBorder(grid, c3x, cardY, cardW, cardH, slate700, rounded = true)
        drawText(grid, c3x + 2, cardY + 1, "Active Now", slate400)
        drawText(grid, c3x + 2, cardY + 2, "342", white, bold = true)
        drawText(grid, c3x + 2, cardY + 3, "↓ 3.1%", red400)

        // ═══ Chart area ═══
        val chartW = 42; val chartH = 10; val chartY = 10
        fillRect(grid, 2, chartY, chartW, chartH, slate800)
        drawBorder(grid, 2, chartY, chartW, chartH, slate700, rounded = true)
        drawText(grid, 4, chartY + 1, "Traffic (last 7 days)", slate300, bold = true)

        // Mini bar chart
        val bars = Array(5, 7, 4, 8, 6, 9, 7)
        val days = Array("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
        var bi   = 0
        while bi < bars.length do
            val bx     = 5 + bi * 5
            val bh     = bars(bi)
            val bottom = chartY + chartH - 2
            var by     = bottom - bh
            while by < bottom do
                if by >= chartY + 2 then
                    fillRect(grid, bx, by, 3, 1, indigo500)
                by += 1
            end while
            drawText(grid, bx, bottom, days(bi).substring(0, 1), slate400)
            bi += 1
        end while

        // ═══ Recent activity ═══
        val actX = 2 + chartW + cardGap; val actW = W - actX - 2
        fillRect(grid, actX, chartY, actW, chartH, slate800)
        drawBorder(grid, actX, chartY, actW, chartH, slate700, rounded = true)
        drawText(grid, actX + 2, chartY + 1, "Recent Activity", slate300, bold = true)
        drawText(grid, actX + 2, chartY + 3, "● New signup", green400)
        drawText(grid, actX + 2, chartY + 4, "● Payment rcvd", blue400)
        drawText(grid, actX + 2, chartY + 5, "● Error 500", red400)
        drawText(grid, actX + 2, chartY + 6, "● Deploy v2.4", violet500)
        drawText(grid, actX + 2, chartY + 7, "● Cache miss", cyan400)
        drawText(grid, actX + 2, chartY + 8, "● New signup", green400)

        // ═══ Status bar ═══
        val sbY = 21
        fillRect(grid, 2, sbY, W - 4, 3, slate800)
        drawBorder(grid, 2, sbY, W - 4, 3, slate700, rounded = true)
        drawText(grid, 4, sbY + 1, " ● API ", green400, green900, bold = true)
        drawText(grid, 13, sbY + 1, " ● DB  ", green400, green900, bold = true)
        drawText(grid, 22, sbY + 1, " ● CDN ", amber500, slate700, bold = true)
        drawText(grid, 31, sbY + 1, "Latency: 42ms", slate400)
        drawText(grid, 47, sbY + 1, "Uptime: 99.9%", slate600)
        drawText(grid, W - 10, sbY + 1, "v2.4.1", slate600)

        // ═══ Footer ═══
        drawText(grid, 3, H - 1, "kyo-ui TUI backend • truecolor rendering", slate600)

        // Render
        print(renderAnsi(grid, W, H))
    end main

    private def fillRect(grid: Array[Array[Cell]], x: Int, y: Int, w: Int, h: Int, bg: Int): Unit =
        var row = y;
        while row < y + h && row < grid.length do
            if row >= 0 then
                var col = x;
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
        var i = 0;
        while i < text.length do
            val gx = x + i
            if y >= 0 && y < grid.length && gx >= 0 && gx < grid(0).length then
                val existingBg = if bg == -1 then grid(y)(gx).bg else bg
                grid(y)(gx) = Cell(text.charAt(i), fg, existingBg, bold, dim, italic)
            i += 1
        end while
    end drawText

    private def drawBorder(grid: Array[Array[Cell]], x: Int, y: Int, w: Int, h: Int, fg: Int, rounded: Boolean = false): Unit =
        val tl     = if rounded then '╭' else '┌'; val tr = if rounded then '╮' else '┐'
        val bl     = if rounded then '╰' else '└'; val br = if rounded then '╯' else '┘'
        val hz     = '─'; val vt                          = '│'
        val height = grid.length; val width               = grid(0).length
        if y >= 0 && y < height then
            if x >= 0 && x < width then grid(y)(x) = grid(y)(x).copy(ch = tl, fg = fg)
            if x + w - 1 >= 0 && x + w - 1 < width then grid(y)(x + w - 1) = grid(y)(x + w - 1).copy(ch = tr, fg = fg)
            var cx = x + 1;
            while cx < x + w - 1 do
                if cx >= 0 && cx < width then grid(y)(cx) = grid(y)(cx).copy(ch = hz, fg = fg)
                cx += 1
        end if
        if y + h - 1 >= 0 && y + h - 1 < height then
            if x >= 0 && x < width then grid(y + h - 1)(x) = grid(y + h - 1)(x).copy(ch = bl, fg = fg)
            if x + w - 1 >= 0 && x + w - 1 < width then grid(y + h - 1)(x + w - 1) = grid(y + h - 1)(x + w - 1).copy(ch = br, fg = fg)
            var cx = x + 1;
            while cx < x + w - 1 do
                if cx >= 0 && cx < width then grid(y + h - 1)(cx) = grid(y + h - 1)(cx).copy(ch = hz, fg = fg)
                cx += 1
        end if
        var cy = y + 1;
        while cy < y + h - 1 do
            if cy >= 0 && cy < height then
                if x >= 0 && x < width then grid(cy)(x) = grid(cy)(x).copy(ch = vt, fg = fg)
                if x + w - 1 >= 0 && x + w - 1 < width then grid(cy)(x + w - 1) = grid(cy)(x + w - 1).copy(ch = vt, fg = fg)
            cy += 1
        end while
    end drawBorder

    private def renderAnsi(grid: Array[Array[Cell]], W: Int, H: Int): String =
        val sb = new StringBuilder()
        sb.append("\n")
        var row = 0;
        while row < H do
            var col      = 0; var lastFg      = -2; var lastBg        = -2
            var lastBold = false; var lastDim = false; var lastItalic = false
            while col < W do
                val cell = grid(row)(col)
                val needStyle = cell.fg != lastFg || cell.bg != lastBg ||
                    cell.bold != lastBold || cell.dim != lastDim || cell.italic != lastItalic
                if needStyle then
                    sb.append("\u001b[0m")
                    if cell.bold then sb.append("\u001b[1m")
                    if cell.dim then sb.append("\u001b[2m")
                    if cell.italic then sb.append("\u001b[3m")
                    if cell.fg != -1 then
                        sb.append("\u001b[38;2;"); sb.append(TuiColor.r(cell.fg)); sb.append(';')
                        sb.append(TuiColor.g(cell.fg)); sb.append(';'); sb.append(TuiColor.b(cell.fg)); sb.append('m')
                    end if
                    if cell.bg != -1 then
                        sb.append("\u001b[48;2;"); sb.append(TuiColor.r(cell.bg)); sb.append(';')
                        sb.append(TuiColor.g(cell.bg)); sb.append(';'); sb.append(TuiColor.b(cell.bg)); sb.append('m')
                    end if
                    lastFg = cell.fg; lastBg = cell.bg
                    lastBold = cell.bold; lastDim = cell.dim; lastItalic = cell.italic
                end if
                sb.append(cell.ch)
                col += 1
            end while
            sb.append("\u001b[0m\n")
            row += 1
        end while
        sb.toString
    end renderAnsi
end TuiDemoMain
