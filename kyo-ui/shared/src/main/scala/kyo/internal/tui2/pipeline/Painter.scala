package kyo.internal.tui2.pipeline

import kyo.*
import scala.annotation.tailrec

/** Pure function: Laid → CellGrid. Emits character cells into freshly allocated canvass.
  *
  * The only mutation is writing to `CellGrid.cells` arrays, which are freshly allocated per call and never shared. Handles background fill,
  * border drawing, text rendering (transform, align, overflow, letter spacing), gradient interpolation, shadow, cursor inversion, and CSS
  * filters.
  */
object Painter:

    val white = RGB(255, 255, 255)
    val black = RGB(0, 0, 0)

    /** Mutable paint target — cells are written during painting, then frozen into an immutable CellGrid. */
    private class Canvas(val width: Int, val height: Int):
        val cells        = Array.fill(width * height)(Cell.Empty)
        val rawSequences = ChunkBuilder.init[(Rect, Array[Byte])]

        def toGrid: CellGrid = CellGrid(width, height, Span.fromUnsafe(cells), rawSequences.result())
    end Canvas

    def paint(layout: LayoutResult, viewport: Rect): (CellGrid, CellGrid) =
        val base  = Canvas(viewport.w, viewport.h)
        val popup = Canvas(viewport.w, viewport.h)
        paintNode(layout.base, base)
        @tailrec def eachPopup(i: Int): Unit =
            if i < layout.popups.size then
                paintNode(layout.popups(i), popup)
                eachPopup(i + 1)
        eachPopup(0)
        (base.toGrid, popup.toGrid)
    end paint

    private def paintNode(node: Laid, canvas: Canvas): Unit = node match
        case n: Laid.Node   => paintBox(n, canvas)
        case t: Laid.Text   => paintText(t, canvas)
        case c: Laid.Cursor => paintCursor(c, canvas)

    // ---- Box painting ----

    private def paintBox(n: Laid.Node, canvas: Canvas): Unit =
        val cs   = n.style
        val b    = n.bounds
        val clip = n.clip

        // 1. Shadow
        if cs.shadowColor != RGB.Transparent &&
            (cs.shadowX.value != 0 || cs.shadowY.value != 0 || cs.shadowSpread.value != 0)
        then paintShadow(canvas, b, cs, clip)

        // 2. Background — gradient or solid
        cs.gradientDirection match
            case Present(dir) if cs.gradientStops.size >= 2 =>
                paintGradient(canvas, b, dir, cs.gradientStops, clip)
            case _ =>
                if cs.bg != RGB.Transparent then
                    fillRect(canvas, b, cs.bg, clip)
        end match

        // 3. Border
        if cs.borderTop.value > 0 || cs.borderRight.value > 0 ||
            cs.borderBottom.value > 0 || cs.borderLeft.value > 0
        then paintBorder(canvas, b, cs, clip)

        // 4. Image
        n.handlers.imageData match
            case Present(img) => paintImage(canvas, n.content, img, clip)
            case Absent       =>

        // 5. Children
        @tailrec def eachChild(i: Int): Unit =
            if i < n.children.size then
                paintNode(n.children(i), canvas)
                eachChild(i + 1)
        eachChild(0)

        // 6. Filters
        if cs.brightness != 1.0 || cs.contrast != 1.0 || cs.grayscale > 0.0 ||
            cs.sepia > 0.0 || cs.invert > 0.0 || cs.saturate != 1.0 || cs.hueRotate != 0.0
        then applyFilters(canvas, b, cs, clip)
    end paintBox

    // ---- Background fill ----

    private def fillRect(canvas: Canvas, bounds: Rect, bg: RGB, clip: Rect): Unit =
        @tailrec def eachRow(row: Int): Unit =
            if row < bounds.y + bounds.h then
                @tailrec def eachCol(col: Int): Unit =
                    if col < bounds.x + bounds.w then
                        if inClip(col, row, clip) && inBounds(col, row, canvas) then
                            val idx = row * canvas.width + col
                            canvas.cells(idx) = canvas.cells(idx).copy(bg = bg)
                        eachCol(col + 1)
                eachCol(bounds.x)
                eachRow(row + 1)
        eachRow(bounds.y)
    end fillRect

    // ---- Border drawing ----

    private val BoxSolid  = Array('┌', '─', '┐', '│', '│', '└', '─', '┘')
    private val BoxRound  = Array('╭', '─', '╮', '│', '│', '╰', '─', '╯')
    private val BoxDashed = Array('┌', '┄', '┐', '┆', '┆', '└', '┄', '┘')
    private val BoxDotted = Array('┌', '·', '┐', '·', '·', '└', '·', '┘')

    private def paintBorder(canvas: Canvas, b: Rect, cs: FlatStyle, clip: Rect): Unit =
        val chars = cs.borderStyle match
            case Style.BorderStyle.solid  => BoxSolid
            case Style.BorderStyle.dashed => BoxDashed
            case Style.BorderStyle.dotted => BoxDotted
            case Style.BorderStyle.none   => BoxSolid

        val x1 = b.x
        val y1 = b.y
        val x2 = b.x + b.w - 1
        val y2 = b.y + b.h - 1

        // Corners
        if cs.borderTop.value > 0 && cs.borderLeft.value > 0 then
            setCell(canvas, x1, y1, if cs.roundTL then BoxRound(0) else chars(0), cs.borderColorTop, cs.bg, clip)
        if cs.borderTop.value > 0 && cs.borderRight.value > 0 then
            setCell(canvas, x2, y1, if cs.roundTR then BoxRound(2) else chars(2), cs.borderColorTop, cs.bg, clip)
        if cs.borderBottom.value > 0 && cs.borderLeft.value > 0 then
            setCell(canvas, x1, y2, if cs.roundBL then BoxRound(5) else chars(5), cs.borderColorBottom, cs.bg, clip)
        if cs.borderBottom.value > 0 && cs.borderRight.value > 0 then
            setCell(canvas, x2, y2, if cs.roundBR then BoxRound(7) else chars(7), cs.borderColorBottom, cs.bg, clip)

        // Edges
        if cs.borderTop.value > 0 then
            @tailrec def topEdge(col: Int): Unit =
                if col < x2 then
                    setCell(canvas, col, y1, chars(1), cs.borderColorTop, cs.bg, clip)
                    topEdge(col + 1)
            topEdge(x1 + 1)
        end if

        if cs.borderBottom.value > 0 then
            @tailrec def bottomEdge(col: Int): Unit =
                if col < x2 then
                    setCell(canvas, col, y2, chars(6), cs.borderColorBottom, cs.bg, clip)
                    bottomEdge(col + 1)
            bottomEdge(x1 + 1)
        end if

        if cs.borderLeft.value > 0 then
            @tailrec def leftEdge(row: Int): Unit =
                if row < y2 then
                    setCell(canvas, x1, row, chars(3), cs.borderColorLeft, cs.bg, clip)
                    leftEdge(row + 1)
            leftEdge(y1 + 1)
        end if

        if cs.borderRight.value > 0 then
            @tailrec def rightEdge(row: Int): Unit =
                if row < y2 then
                    setCell(canvas, x2, row, chars(4), cs.borderColorRight, cs.bg, clip)
                    rightEdge(row + 1)
            rightEdge(y1 + 1)
        end if
    end paintBorder

    // ---- Text painting ----

    private def paintText(t: Laid.Text, canvas: Canvas): Unit =
        val cs      = t.style
        val spacing = Length.resolve(cs.letterSpacing, 0)

        val text = cs.textTransform match
            case Style.TextTransform.uppercase  => t.value.toUpperCase
            case Style.TextTransform.lowercase  => t.value.toLowerCase
            case Style.TextTransform.capitalize => t.value.capitalize
            case Style.TextTransform.none       => t.value

        val maxChars = t.bounds.w / math.max(1, 1 + spacing)
        // When ellipsis is active, don't wrap — truncate with ellipsis instead
        val effectiveWrap =
            if cs.textOverflow == Style.TextOverflow.ellipsis then Style.TextWrap.noWrap
            else cs.textWrap
        val lines = Layout.splitLines(text, maxChars, effectiveWrap)

        @tailrec def eachLine(lineIdx: Int, lineY: Int): Unit =
            if lineIdx < lines.size then
                val line = lines(lineIdx)

                val displayLine = cs.textOverflow match
                    case Style.TextOverflow.ellipsis if line.length > maxChars && maxChars > 3 =>
                        line.substring(0, maxChars - 1) + "…"
                    case _ if line.length > maxChars && maxChars > 0 =>
                        line.substring(0, maxChars)
                    case _ => line

                val lineWidth = displayLine.length * (1 + spacing)
                val startX = cs.textAlign match
                    case Style.TextAlign.left    => t.bounds.x
                    case Style.TextAlign.center  => t.bounds.x + (t.bounds.w - lineWidth) / 2
                    case Style.TextAlign.right   => t.bounds.x + t.bounds.w - lineWidth
                    case Style.TextAlign.justify => t.bounds.x

                @tailrec def eachChar(charIdx: Int, cellX: Int): Unit =
                    if charIdx < displayLine.length then
                        setCell(
                            canvas,
                            cellX,
                            lineY,
                            displayLine.charAt(charIdx),
                            cs.fg,
                            cs.bg,
                            t.clip,
                            cs.bold,
                            cs.italic,
                            cs.underline,
                            cs.strikethrough
                        )
                        eachChar(charIdx + 1, cellX + 1 + spacing)
                eachChar(0, startX)

                eachLine(lineIdx + 1, lineY + cs.lineHeight)
        eachLine(0, t.bounds.y)
    end paintText

    // ---- Cursor painting ----

    private def paintCursor(c: Laid.Cursor, canvas: Canvas): Unit =
        val x = c.pos.x
        val y = c.pos.y
        if inBounds(x, y, canvas) then
            val idx      = y * canvas.width + x
            val existing = canvas.cells(idx)
            val newFg    = if existing.bg == black then white else existing.bg
            val newBg    = if existing.fg == black then white else existing.fg
            val ch       = if existing.char == '\u0000' then '█' else existing.char
            canvas.cells(idx) = Cell(
                ch,
                newFg,
                newBg,
                existing.bold,
                existing.italic,
                existing.underline,
                existing.strikethrough,
                existing.dimmed
            )
        end if
    end paintCursor

    // ---- Gradient painting ----

    private def paintGradient(
        canvas: Canvas,
        b: Rect,
        direction: Style.GradientDirection,
        stops: Chunk[(RGB, Double)],
        clip: Rect
    ): Unit =
        @tailrec def eachRow(row: Int): Unit =
            if row < b.y + b.h then
                @tailrec def eachCol(col: Int): Unit =
                    if col < b.x + b.w then
                        if inClip(col, row, clip) && inBounds(col, row, canvas) then
                            val t     = gradientPosition(direction, col - b.x, row - b.y, b.w, b.h)
                            val color = interpolateStops(stops, t)
                            val idx   = row * canvas.width + col
                            canvas.cells(idx) = canvas.cells(idx).copy(bg = color)
                        end if
                        eachCol(col + 1)
                eachCol(b.x)
                eachRow(row + 1)
        eachRow(b.y)
    end paintGradient

    private def gradientPosition(dir: Style.GradientDirection, dx: Int, dy: Int, w: Int, h: Int): Double =
        val fx = dx.toDouble / math.max(1, w - 1)
        val fy = dy.toDouble / math.max(1, h - 1)
        dir match
            case Style.GradientDirection.toRight       => fx
            case Style.GradientDirection.toLeft        => 1.0 - fx
            case Style.GradientDirection.toBottom      => fy
            case Style.GradientDirection.toTop         => 1.0 - fy
            case Style.GradientDirection.toTopRight    => (fx + (1.0 - fy)) / 2.0
            case Style.GradientDirection.toTopLeft     => ((1.0 - fx) + (1.0 - fy)) / 2.0
            case Style.GradientDirection.toBottomRight => (fx + fy) / 2.0
            case Style.GradientDirection.toBottomLeft  => ((1.0 - fx) + fy) / 2.0
        end match
    end gradientPosition

    private def interpolateStops(stops: Chunk[(RGB, Double)], t: Double): RGB =
        if stops.size <= 1 then
            if stops.isEmpty then black else stops(0)._1
        else
            val (lo, hi) = findStopPair(stops, t, 0, 0, stops.size - 1)
            if lo == hi then stops(lo)._1
            else
                val (c1, p1) = stops(lo)
                val (c2, p2) = stops(hi)
                val f        = if p2 == p1 then 0.0 else (t - p1) / (p2 - p1)
                c1.lerp(c2, f)
            end if

    @tailrec private def findStopPair(stops: Chunk[(RGB, Double)], t: Double, i: Int, lo: Int, hi: Int): (Int, Int) =
        if i >= stops.size then (lo, hi)
        else if stops(i)._2 >= t then (lo, i)
        else findStopPair(stops, t, i + 1, if stops(i)._2 <= t then i else lo, hi)

    // ---- Shadow painting ----

    private def paintShadow(canvas: Canvas, b: Rect, cs: FlatStyle, clip: Rect): Unit =
        val sx     = b.x + cs.shadowX.value.toInt - cs.shadowSpread.value.toInt
        val sy     = b.y + cs.shadowY.value.toInt - cs.shadowSpread.value.toInt
        val sw     = b.w + cs.shadowSpread.value.toInt * 2
        val sh     = b.h + cs.shadowSpread.value.toInt * 2
        val blurPx = cs.shadowBlur.value.toInt

        if blurPx == 0 then
            fillRect(canvas, Rect(sx, sy, sw, sh), cs.shadowColor, clip)
        else
            // Layered blur approximation with shade characters
            @tailrec def eachLayer(layer: Int): Unit =
                if layer >= 0 then
                    val shade = if layer == 0 then '▓'
                    else if layer <= blurPx / 3 then '▒'
                    else '░'
                    val lx = sx - layer
                    val ly = sy - layer
                    val lw = sw + layer * 2
                    val lh = sh + layer * 2
                    // Only draw the border of this layer rectangle
                    @tailrec def eachRow(row: Int): Unit =
                        if row < ly + lh then
                            @tailrec def eachCol(col: Int): Unit =
                                if col < lx + lw then
                                    val isBorder = row == ly || row == ly + lh - 1 || col == lx || col == lx + lw - 1
                                    if isBorder && inClip(col, row, clip) && inBounds(col, row, canvas) then
                                        val idx = row * canvas.width + col
                                        canvas.cells(idx) = Cell(
                                            shade,
                                            cs.shadowColor,
                                            canvas.cells(idx).bg,
                                            false,
                                            false,
                                            false,
                                            false,
                                            false
                                        )
                                    end if
                                    eachCol(col + 1)
                            eachCol(lx)
                            eachRow(row + 1)
                    eachRow(ly)
                    eachLayer(layer - 1)
            eachLayer(blurPx)
        end if
    end paintShadow

    // ---- Image painting ----

    private def paintImage(canvas: Canvas, content: Rect, img: ImageData, clip: Rect): Unit =
        // Append raw bytes for terminal image protocol (iTerm2/Kitty)
        canvas.rawSequences.addOne((content, img.bytes.toArray))
        // Fill with alt text as fallback
        @tailrec def eachChar(i: Int, x: Int): Unit =
            if i < img.alt.length && x < content.x + content.w then
                setCell(canvas, x, content.y, img.alt.charAt(i), white, black, clip)
                eachChar(i + 1, x + 1)
        eachChar(0, content.x)
    end paintImage

    // ---- Filter application ----

    private def applyFilters(canvas: Canvas, b: Rect, cs: FlatStyle, clip: Rect): Unit =
        @tailrec def eachRow(row: Int): Unit =
            if row < b.y + b.h then
                @tailrec def eachCol(col: Int): Unit =
                    if col < b.x + b.w then
                        if inClip(col, row, clip) && inBounds(col, row, canvas) then
                            val idx  = row * canvas.width + col
                            val cell = canvas.cells(idx)
                            canvas.cells(idx) = cell.copy(
                                fg = applyFilterChain(cell.fg, cs),
                                bg = applyFilterChain(cell.bg, cs)
                            )
                        end if
                        eachCol(col + 1)
                eachCol(b.x)
                eachRow(row + 1)
        eachRow(b.y)
    end applyFilters

    private def applyFilterChain(color: RGB, cs: FlatStyle): RGB =
        if color == RGB.Transparent then color
        else
            // Local vars acceptable — building up a single result in bounded scope
            var r = color.r
            var g = color.g
            var b = color.b

            if cs.brightness != 1.0 then
                r = RGB.clamp((r * cs.brightness).toInt)
                g = RGB.clamp((g * cs.brightness).toInt)
                b = RGB.clamp((b * cs.brightness).toInt)
            end if

            if cs.contrast != 1.0 then
                r = RGB.clamp(((r - 128) * cs.contrast + 128).toInt)
                g = RGB.clamp(((g - 128) * cs.contrast + 128).toInt)
                b = RGB.clamp(((b - 128) * cs.contrast + 128).toInt)
            end if

            if cs.grayscale > 0.0 then
                val lum = (0.299 * r + 0.587 * g + 0.114 * b).toInt
                r = RGB.clamp((r * (1 - cs.grayscale) + lum * cs.grayscale).toInt)
                g = RGB.clamp((g * (1 - cs.grayscale) + lum * cs.grayscale).toInt)
                b = RGB.clamp((b * (1 - cs.grayscale) + lum * cs.grayscale).toInt)
            end if

            if cs.sepia > 0.0 then
                val sr = RGB.clamp((0.393 * r + 0.769 * g + 0.189 * b).toInt)
                val sg = RGB.clamp((0.349 * r + 0.686 * g + 0.168 * b).toInt)
                val sb = RGB.clamp((0.272 * r + 0.534 * g + 0.131 * b).toInt)
                r = RGB.clamp((r * (1 - cs.sepia) + sr * cs.sepia).toInt)
                g = RGB.clamp((g * (1 - cs.sepia) + sg * cs.sepia).toInt)
                b = RGB.clamp((b * (1 - cs.sepia) + sb * cs.sepia).toInt)
            end if

            if cs.invert > 0.0 then
                r = RGB.clamp((r * (1 - cs.invert) + (255 - r) * cs.invert).toInt)
                g = RGB.clamp((g * (1 - cs.invert) + (255 - g) * cs.invert).toInt)
                b = RGB.clamp((b * (1 - cs.invert) + (255 - b) * cs.invert).toInt)
            end if

            if cs.saturate != 1.0 then
                val lum = (0.299 * r + 0.587 * g + 0.114 * b).toInt
                r = RGB.clamp((lum + (r - lum) * cs.saturate).toInt)
                g = RGB.clamp((lum + (g - lum) * cs.saturate).toInt)
                b = RGB.clamp((lum + (b - lum) * cs.saturate).toInt)
            end if

            if cs.hueRotate != 0.0 then
                val (h, s, l)    = rgbToHsl(r, g, b)
                val (nr, ng, nb) = hslToRgb((h + cs.hueRotate) % 360.0, s, l)
                r = nr
                g = ng
                b = nb
            end if

            RGB(r, g, b)

    // ---- Color space conversion (for hueRotate filter) ----

    private def rgbToHsl(r: Int, g: Int, b: Int): (Double, Double, Double) =
        val rf  = r / 255.0
        val gf  = g / 255.0
        val bf  = b / 255.0
        val max = math.max(rf, math.max(gf, bf))
        val min = math.min(rf, math.min(gf, bf))
        val l   = (max + min) / 2.0
        if max == min then (0.0, 0.0, l)
        else
            val d = max - min
            val s = if l > 0.5 then d / (2.0 - max - min) else d / (max + min)
            val h =
                if max == rf then ((gf - bf) / d + (if gf < bf then 6 else 0)) * 60
                else if max == gf then ((bf - rf) / d + 2) * 60
                else ((rf - gf) / d + 4) * 60
            (h, s, l)
        end if
    end rgbToHsl

    private def hslToRgb(h: Double, s: Double, l: Double): (Int, Int, Int) =
        if s == 0 then
            val v = RGB.clamp((l * 255).toInt)
            (v, v, v)
        else
            def hue2rgb(p: Double, q: Double, t0: Double): Double =
                val t = if t0 < 0 then t0 + 1 else if t0 > 1 then t0 - 1 else t0
                if t < 1.0 / 6 then p + (q - p) * 6 * t
                else if t < 1.0 / 2 then q
                else if t < 2.0 / 3 then p + (q - p) * (2.0 / 3 - t) * 6
                else p
                end if
            end hue2rgb
            val q     = if l < 0.5 then l * (1 + s) else l + s - l * s
            val p     = 2 * l - q
            val hNorm = h / 360.0
            (
                RGB.clamp((hue2rgb(p, q, hNorm + 1.0 / 3) * 255).toInt),
                RGB.clamp((hue2rgb(p, q, hNorm) * 255).toInt),
                RGB.clamp((hue2rgb(p, q, hNorm - 1.0 / 3) * 255).toInt)
            )

    // ---- Cell writing helpers ----

    private def setCell(
        canvas: Canvas,
        x: Int,
        y: Int,
        ch: Char,
        fg: RGB,
        bg: RGB,
        clip: Rect,
        bold: Boolean = false,
        italic: Boolean = false,
        underline: Boolean = false,
        strikethrough: Boolean = false
    ): Unit =
        if inClip(x, y, clip) && inBounds(x, y, canvas) then
            canvas.cells(y * canvas.width + x) = Cell(ch, fg, bg, bold, italic, underline, strikethrough, false)

    private def inClip(x: Int, y: Int, clip: Rect): Boolean =
        x >= clip.x && x < clip.x + clip.w && y >= clip.y && y < clip.y + clip.h

    private def inBounds(x: Int, y: Int, canvas: Canvas): Boolean =
        x >= 0 && x < canvas.width && y >= 0 && y < canvas.height

end Painter
