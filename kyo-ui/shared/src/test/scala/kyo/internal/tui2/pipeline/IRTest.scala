package kyo.internal.tui2.pipeline

import kyo.*
import kyo.Test

class IRTest extends Test:

    "ElemTag" - {
        "all variants constructible" in {
            assert(ElemTag.Div != ElemTag.Span)
            assert(ElemTag.Popup != ElemTag.Table)
            succeed
        }
    }

    "Rect" - {
        "field access" in {
            val r = Rect(1, 2, 3, 4)
            assert(r.x == 1 && r.y == 2 && r.w == 3 && r.h == 4)
        }
    }

    "WidgetKey" - {
        "from frame without dynamic path" in run {
            val key = WidgetKey(Frame.derive, Chunk.empty)
            assert(key.dynamicPath.isEmpty)
        }

        "from frame with dynamic path" in run {
            val key = WidgetKey(Frame.derive, Chunk("a", "b"))
            assert(key.dynamicPath == Chunk("a", "b"))
        }

        "child appends segment" in run {
            val parent = WidgetKey(Frame.derive, Chunk.empty)
            val child  = WidgetKey.child(parent, "cursor")
            assert(child.frame == parent.frame)
            assert(child.dynamicPath == Chunk("cursor"))
        }

        "equality uses frame and path" in run {
            val f  = Frame.derive
            val k1 = WidgetKey(f, Chunk("x"))
            val k2 = WidgetKey(f, Chunk("x"))
            val k3 = WidgetKey(f, Chunk("y"))
            assert(k1 == k2)
            assert(k1 != k3)
        }
    }

    "Handlers" - {
        "empty defaults" in {
            val h = Handlers.empty
            assert(h.widgetKey == Absent)
            assert(h.id == Absent)
            assert(h.forId == Absent)
            assert(h.tabIndex == Absent)
            assert(!h.disabled)
            assert(h.colspan == 1)
            assert(h.rowspan == 1)
            assert(h.imageData == Absent)
        }
    }

    "Resolved" - {
        "Node construction" in {
            val node = Resolved.Node(ElemTag.Div, Style.empty, Handlers.empty, Chunk.empty)
            node match
                case Resolved.Node(tag, _, _, children) =>
                    assert(tag == ElemTag.Div)
                    assert(children.isEmpty)
                case _ => fail("expected Node")
            end match
        }

        "Text construction" in {
            val text = Resolved.Text("hello")
            text match
                case Resolved.Text(v) => assert(v == "hello")
                case _                => fail("expected Text")
        }

        "Cursor construction" in {
            val cursor = Resolved.Cursor(5)
            cursor match
                case Resolved.Cursor(offset) => assert(offset == 5)
                case _                       => fail("expected Cursor")
        }
    }

    "Styled" - {
        "Node with ComputedStyle" in {
            val node = Styled.Node(ElemTag.Span, ComputedStyle.Default, Handlers.empty, Chunk.empty)
            node match
                case Styled.Node(tag, cs, _, _) =>
                    assert(tag == ElemTag.Span)
                    assert(cs.fg == ColorEnc.pack(255, 255, 255))
                case _ => fail("expected Node")
            end match
        }

        "Text with ComputedStyle" in {
            val text = Styled.Text("world", ComputedStyle.Default)
            text match
                case Styled.Text(v, cs) =>
                    assert(v == "world")
                    assert(cs.lineHeight == 1)
                case _ => fail("expected Text")
            end match
        }
    }

    "Laid" - {
        "Node with bounds" in {
            val bounds  = Rect(0, 0, 80, 24)
            val content = Rect(1, 1, 78, 22)
            val clip    = Rect(0, 0, 80, 24)
            val node    = Laid.Node(ElemTag.Div, ComputedStyle.Default, Handlers.empty, bounds, content, clip, Chunk.empty)
            node match
                case Laid.Node(_, _, _, b, c, cl, _) =>
                    assert(b == bounds)
                    assert(c == content)
                    assert(cl == clip)
                case _ => fail("expected Node")
            end match
        }

        "Text with bounds" in {
            val text = Laid.Text("hi", ComputedStyle.Default, Rect(0, 0, 2, 1), Rect(0, 0, 80, 24))
            text match
                case Laid.Text(v, _, b, _) =>
                    assert(v == "hi")
                    assert(b.w == 2)
                case _ => fail("expected Text")
            end match
        }

        "Cursor with pos" in {
            val cursor = Laid.Cursor(Rect(5, 3, 1, 1))
            cursor match
                case Laid.Cursor(pos) =>
                    assert(pos.x == 5 && pos.y == 3)
                case _ => fail("expected Cursor")
            end match
        }
    }

    "LayoutResult" - {
        "base and popups" in {
            val base = Laid.Node(
                ElemTag.Div,
                ComputedStyle.Default,
                Handlers.empty,
                Rect(0, 0, 80, 24),
                Rect(0, 0, 80, 24),
                Rect(0, 0, 80, 24),
                Chunk.empty
            )
            val popup = Laid.Node(
                ElemTag.Popup,
                ComputedStyle.Default,
                Handlers.empty,
                Rect(10, 10, 20, 5),
                Rect(10, 10, 20, 5),
                Rect(0, 0, 80, 24),
                Chunk.empty
            )
            val lr = LayoutResult(base, Chunk(popup))
            assert(lr.popups.size == 1)
        }
    }

    "Cell" - {
        "Empty cell" in {
            val c = Cell.Empty
            assert(c.char == '\u0000')
            assert(c.fg == 0)
            assert(c.bg == 0)
            assert(!c.bold && !c.italic && !c.underline && !c.strikethrough && !c.dimmed)
        }
    }

    "CellGrid" - {
        "empty produces correct dimensions" in {
            val grid = CellGrid.empty(10, 5)
            assert(grid.width == 10)
            assert(grid.height == 5)
            assert(grid.cells.length == 50)
            assert(grid.cells.forall(_ == Cell.Empty))
            assert(grid.rawSequences.isEmpty)
        }
    }

    "ComputedStyle.Default" - {
        "sensible defaults" in {
            val cs = ComputedStyle.Default
            assert(cs.fg == ColorEnc.pack(255, 255, 255))
            assert(cs.bg == ColorEnc.Transparent)
            assert(!cs.bold)
            assert(cs.opacity == 1.0)
            assert(cs.lineHeight == 1)
            assert(cs.letterSpacing == 0)
            assert(cs.flexShrink == 1.0)
            assert(cs.flexGrow == 0.0)
            assert(SizeEnc.isAuto(cs.width))
            assert(SizeEnc.isAuto(cs.height))
            assert(cs.overflow == 0)
            assert(cs.position == 0)
        }
    }

    "SizeEnc" - {
        "px round-trip" in {
            assert(SizeEnc.px(42) == 42)
            assert(SizeEnc.resolve(42, 100) == 42)
        }

        "pct round-trip" in {
            val encoded = SizeEnc.pct(50.0)
            assert(SizeEnc.isPct(encoded))
            assert(!SizeEnc.isAuto(encoded))
            assert(SizeEnc.resolve(encoded, 100) == 50)
        }

        "auto" in {
            assert(SizeEnc.isAuto(SizeEnc.Auto))
            assert(!SizeEnc.isPct(SizeEnc.Auto))
            assert(SizeEnc.resolve(SizeEnc.Auto, 80) == 80)
        }

        "pct 100% of 200 = 200" in {
            val encoded = SizeEnc.pct(100.0)
            assert(SizeEnc.resolve(encoded, 200) == 200)
        }

        "pct 25% of 80 = 20" in {
            val encoded = SizeEnc.pct(25.0)
            assert(SizeEnc.resolve(encoded, 80) == 20)
        }
    }

    "ColorEnc" - {
        "pack and unpack round-trip" in {
            val color = ColorEnc.pack(128, 64, 32)
            assert(ColorEnc.r(color) == 128)
            assert(ColorEnc.g(color) == 64)
            assert(ColorEnc.b(color) == 32)
        }

        "pack(0,0,0) = 0" in {
            assert(ColorEnc.pack(0, 0, 0) == 0)
        }

        "pack(255,255,255)" in {
            val white = ColorEnc.pack(255, 255, 255)
            assert(ColorEnc.r(white) == 255)
            assert(ColorEnc.g(white) == 255)
            assert(ColorEnc.b(white) == 255)
        }

        "Transparent is -1" in {
            assert(ColorEnc.Transparent == -1)
        }

        "fromStyle Transparent" in {
            assert(ColorEnc.fromStyle(Style.Color.Transparent, 0) == ColorEnc.Transparent)
        }

        "fromStyle Rgb" in {
            val c = ColorEnc.fromStyle(Style.Color.rgb(255, 0, 0), 0)
            assert(ColorEnc.r(c) == 255)
            assert(ColorEnc.g(c) == 0)
            assert(ColorEnc.b(c) == 0)
        }

        "fromStyle Rgba full opacity" in {
            val c = ColorEnc.fromStyle(Style.Color.rgba(100, 200, 50, 1.0), 0)
            assert(ColorEnc.r(c) == 100)
            assert(ColorEnc.g(c) == 200)
            assert(ColorEnc.b(c) == 50)
        }

        "fromStyle Rgba blending" in {
            val bg = ColorEnc.pack(0, 0, 0)
            val c  = ColorEnc.fromStyle(Style.Color.rgba(200, 100, 50, 0.5), bg)
            assert(ColorEnc.r(c) == 100)
            assert(ColorEnc.g(c) == 50)
            assert(ColorEnc.b(c) == 25)
        }

        "fromStyle Hex 6-digit" in {
            val c = ColorEnc.fromStyle(Style.Color.hex("ff8000"), 0)
            assert(ColorEnc.r(c) == 255)
            assert(ColorEnc.g(c) == 128)
            assert(ColorEnc.b(c) == 0)
        }

        "fromStyle Hex 3-digit" in {
            val c = ColorEnc.fromStyle(Style.Color.hex("f00"), 0)
            assert(ColorEnc.r(c) == 255)
            assert(ColorEnc.g(c) == 0)
            assert(ColorEnc.b(c) == 0)
        }
    }

end IRTest
