package kyo.internal.tui.pipeline

import kyo.*
import kyo.Length.*
import kyo.Test

class PainterTest extends Test:

    val viewport = Rect(0, 0, 20, 10)
    val white    = RGB(255, 255, 255)
    val black    = RGB(0, 0, 0)
    val red      = RGB(255, 0, 0)
    val blue     = RGB(0, 0, 255)
    val gray     = RGB(128, 128, 128)

    val defaultStyle = FlatStyle.Default

    def textNode(value: String, cs: FlatStyle = defaultStyle, bounds: Rect = Rect(0, 0, 20, 1)): Laid =
        Laid.Text(value, cs, bounds, viewport)

    def boxNode(
        cs: FlatStyle = defaultStyle,
        bounds: Rect = viewport,
        content: Rect = viewport,
        clip: Rect = viewport,
        children: Chunk[Laid] = Chunk.empty
    ): Laid =
        Laid.Node(ElemTag.Div, cs, Handlers.empty, bounds, content, clip, clip, children)

    def paintSingle(node: Laid): CellGrid =
        val layout    = LayoutResult(node, Chunk.empty)
        val (base, _) = Painter.paint(layout, viewport)
        base
    end paintSingle

    "text" - {
        "correct cells at correct positions" in {
            val grid = paintSingle(textNode("Hi", defaultStyle.copy(fg = red)))
            assert(grid.cells(0).char == 'H')
            assert(grid.cells(1).char == 'i')
            assert(grid.cells(0).fg == red)
        }
    }

    "background" - {
        "all cells in bounds have bg color" in {
            val cs   = defaultStyle.copy(bg = blue)
            val grid = paintSingle(boxNode(cs, bounds = Rect(0, 0, 3, 2)))
            assert(grid.cells(0).bg == blue)
            assert(grid.cells(1).bg == blue)
            assert(grid.cells(2).bg == blue)
            assert(grid.cells(20).bg == blue) // row 1, col 0
        }

        "cells outside bounds not affected" in {
            val cs   = defaultStyle.copy(bg = blue)
            val grid = paintSingle(boxNode(cs, bounds = Rect(0, 0, 2, 1)))
            assert(grid.cells(2).bg != blue)
        }
    }

    "border" - {
        "correct characters at edges" in {
            val cs = defaultStyle.copy(
                borderTop = 1.px,
                borderRight = 1.px,
                borderBottom = 1.px,
                borderLeft = 1.px,
                borderStyle = Style.BorderStyle.solid,
                borderColorTop = gray,
                borderColorRight = gray,
                borderColorBottom = gray,
                borderColorLeft = gray
            )
            val grid = paintSingle(boxNode(cs, bounds = Rect(0, 0, 5, 3)))
            assert(grid.cells(0).char == '┌')          // top-left
            assert(grid.cells(4).char == '┐')          // top-right
            assert(grid.cells(2 * 20).char == '└')     // bottom-left
            assert(grid.cells(2 * 20 + 4).char == '┘') // bottom-right
            assert(grid.cells(1).char == '─')          // top edge
            assert(grid.cells(20).char == '│')         // left edge
        }

        "rounded corners" in {
            val cs = defaultStyle.copy(
                borderTop = 1.px,
                borderRight = 1.px,
                borderBottom = 1.px,
                borderLeft = 1.px,
                borderStyle = Style.BorderStyle.solid,
                borderColorTop = gray,
                borderColorRight = gray,
                borderColorBottom = gray,
                borderColorLeft = gray,
                roundTL = true
            )
            val grid = paintSingle(boxNode(cs, bounds = Rect(0, 0, 5, 3)))
            assert(grid.cells(0).char == '╭')
        }
    }

    "clipping" - {
        "nothing written outside clip" in {
            val cs   = defaultStyle.copy(fg = red)
            val clip = Rect(0, 0, 3, 1)
            val node = Laid.Text("hello world", cs, Rect(0, 0, 11, 1), clip)
            val grid = paintSingle(node)
            assert(grid.cells(0).char == 'h')
            assert(grid.cells(2).char == 'l')
            assert(grid.cells(3).char == '\u0000') // outside clip
        }
    }

    "cursor" - {
        "cursorPosition on handlers does not affect cell grid" in {
            // Cursor is rendered by the terminal via escape sequences, not into the cell grid
            val text     = textNode("abc", defaultStyle.copy(fg = white, bg = black))
            val handlers = Handlers.empty.withCursorPosition(Maybe((1, 0)))
            val parent =
                Laid.Node(ElemTag.Div, defaultStyle.copy(bg = black), handlers, viewport, viewport, viewport, viewport, Chunk(text))
            val grid = paintSingle(parent)
            // Text is undisturbed — cursor is not painted into cells
            assert(grid.cells(0).char == 'a')
            assert(grid.cells(1).char == 'b')
            assert(grid.cells(2).char == 'c')
        }
    }

    "textAlign" - {
        "center" in {
            val cs   = defaultStyle.copy(fg = white, textAlign = Style.TextAlign.center)
            val node = Laid.Text("ab", cs, Rect(0, 0, 10, 1), viewport)
            val grid = paintSingle(node)
            // "ab" is 2 chars wide, centered in 10 → starts at (10-2)/2 = 4
            assert(grid.cells(4).char == 'a')
            assert(grid.cells(5).char == 'b')
        }
    }

    "textOverflow" - {
        "ellipsis" in {
            val cs   = defaultStyle.copy(fg = white, textOverflow = Style.TextOverflow.ellipsis)
            val node = Laid.Text("hello world", cs, Rect(0, 0, 6, 1), viewport)
            val grid = paintSingle(node)
            // 6 chars max, should truncate to "hello…"
            assert(grid.cells(5).char == '…')
        }
    }

    "letterSpacing" - {
        "gaps between chars" in {
            val cs   = defaultStyle.copy(fg = white, letterSpacing = 1.px)
            val node = Laid.Text("ab", cs, Rect(0, 0, 10, 1), viewport)
            val grid = paintSingle(node)
            assert(grid.cells(0).char == 'a')
            assert(grid.cells(2).char == 'b') // gap of 1 between a and b
        }
    }

    "textTransform" - {
        "uppercase" in {
            val cs   = defaultStyle.copy(fg = white, textTransform = Style.TextTransform.uppercase)
            val node = Laid.Text("hello", cs, Rect(0, 0, 10, 1), viewport)
            val grid = paintSingle(node)
            assert(grid.cells(0).char == 'H')
            assert(grid.cells(4).char == 'O')
        }
    }

    "filters" - {
        "brightness changes color" in {
            val cs   = defaultStyle.copy(bg = RGB(100, 100, 100), brightness = 2.0)
            val grid = paintSingle(boxNode(cs, bounds = Rect(0, 0, 1, 1)))
            // brightness 2.0 doubles RGB → 200,200,200
            assert(grid.cells(0).bg.r == 200)
        }
    }

    "shadow" - {
        "offset rectangle" in {
            val cs = defaultStyle.copy(
                shadowX = 2.px,
                shadowY = 1.px,
                shadowBlur = Length.zero,
                shadowSpread = Length.zero,
                shadowColor = gray
            )
            val grid = paintSingle(boxNode(cs, bounds = Rect(0, 0, 3, 2)))
            // Shadow at offset (2, 1) should have gray bg at (2, 1)
            assert(grid.cells(1 * 20 + 2).bg == gray)
        }
    }

    "gradient" - {
        "interpolated colors" in {
            val cs = defaultStyle.copy(
                gradientDirection = Maybe(Style.GradientDirection.toRight),
                gradientStops = Chunk((red, 0.0), (blue, 100.0))
            )
            val grid = paintSingle(boxNode(cs, bounds = Rect(0, 0, 10, 1)))
            // First cell should be close to red, last close to blue
            assert(grid.cells(0).bg.r > grid.cells(9).bg.r)
            assert(grid.cells(0).bg.b < grid.cells(9).bg.b)
        }
    }

    "popups" - {
        "painted to popup grid" in {
            val base           = boxNode()
            val popup          = boxNode(defaultStyle.copy(bg = red), bounds = Rect(0, 0, 2, 1))
            val layout         = LayoutResult(base, Chunk(popup))
            val (_, popupGrid) = Painter.paint(layout, viewport)
            assert(popupGrid.cells(0).bg == red)
        }
    }

    "border styles" - {
        "dashed border uses ┄" in {
            val cs = defaultStyle.copy(
                borderTop = 1.px,
                borderRight = 1.px,
                borderBottom = 1.px,
                borderLeft = 1.px,
                borderStyle = Style.BorderStyle.dashed,
                borderColorTop = gray,
                borderColorRight = gray,
                borderColorBottom = gray,
                borderColorLeft = gray
            )
            val grid = paintSingle(boxNode(cs, bounds = Rect(0, 0, 5, 3)))
            assert(grid.cells(1).char == '┄') // dashed top edge
        }

        "none border draws no chars" in {
            val cs   = defaultStyle.copy(borderStyle = Style.BorderStyle.none)
            val grid = paintSingle(boxNode(cs, bounds = Rect(0, 0, 5, 3)))
            assert(grid.cells(0).char == '\u0000') // no border drawn
        }
    }

    "transparent background" - {
        "cells not changed" in {
            val cs   = defaultStyle.copy(bg = RGB.Transparent)
            val grid = paintSingle(boxNode(cs, bounds = Rect(0, 0, 3, 2)))
            // Transparent bg should NOT overwrite cells
            assert(grid.cells(0).bg == RGB.Transparent) // stays at Cell.Empty bg (terminal default)
        }
    }

    "cursor edge cases" - {
        "cursorPosition at boundary does not crash" in {
            val handlers = Handlers.empty.withCursorPosition(Maybe((19, 0)))
            val content  = Rect(0, 9, 20, 1)
            val parent   = Laid.Node(ElemTag.Div, defaultStyle, handlers, viewport, content, viewport, viewport, Chunk.empty)
            val grid     = paintSingle(parent) // should not throw
            succeed
        }

        "cursorPosition beyond grid does not crash" in {
            val handlers = Handlers.empty.withCursorPosition(Maybe((100, 0)))
            val content  = Rect(0, 0, 200, 1)
            val parent   = Laid.Node(ElemTag.Div, defaultStyle, handlers, viewport, content, viewport, viewport, Chunk.empty)
            val grid     = paintSingle(parent) // should not throw
            succeed
        }
    }

    "empty children" - {
        "no crash in paintBox" in {
            val grid = paintSingle(boxNode(children = Chunk.empty))
            succeed
        }
    }

end PainterTest
