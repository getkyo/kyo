package kyo.internal.tui.pipeline

import kyo.*
import kyo.Length.*
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
            val child  = parent.child("cursor")
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
        "Node with FlatStyle" in {
            val node = Styled.Node(ElemTag.Span, FlatStyle.Default, Handlers.empty, Chunk.empty)
            node match
                case Styled.Node(tag, cs, _, _) =>
                    assert(tag == ElemTag.Span)
                    assert(cs.fg == RGB.Transparent)
                case _ => fail("expected Node")
            end match
        }

        "Text with FlatStyle" in {
            val text = Styled.Text("world", FlatStyle.Default)
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
            val node    = Laid.Node(ElemTag.Div, FlatStyle.Default, Handlers.empty, bounds, content, clip, Chunk.empty)
            node match
                case Laid.Node(_, _, _, b, c, cl, _) =>
                    assert(b == bounds)
                    assert(c == content)
                    assert(cl == clip)
                case _ => fail("expected Node")
            end match
        }

        "Text with bounds" in {
            val text = Laid.Text("hi", FlatStyle.Default, Rect(0, 0, 2, 1), Rect(0, 0, 80, 24))
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
                FlatStyle.Default,
                Handlers.empty,
                Rect(0, 0, 80, 24),
                Rect(0, 0, 80, 24),
                Rect(0, 0, 80, 24),
                Chunk.empty
            )
            val popup = Laid.Node(
                ElemTag.Popup,
                FlatStyle.Default,
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
            assert(c.fg == RGB.Transparent)
            assert(c.bg == RGB.Transparent)
            assert(!c.bold && !c.italic && !c.underline && !c.strikethrough && !c.dimmed)
        }
    }

    "CellGrid" - {
        "empty produces correct dimensions" in {
            val grid = CellGrid.empty(10, 5)
            assert(grid.width == 10)
            assert(grid.height == 5)
            assert(grid.cells.size == 50)
            assert(grid.cells.forall(_ == Cell.Empty))
            assert(grid.rawSequences.isEmpty)
        }
    }

    "FlatStyle.Default" - {
        "sensible defaults" in {
            val cs = FlatStyle.Default
            assert(cs.fg == RGB.Transparent)
            assert(cs.bg == RGB.Transparent)
            assert(!cs.bold)
            assert(cs.opacity == 1.0)
            assert(cs.lineHeight == 1)
            assert(cs.letterSpacing == Length.zero)
            assert(cs.flexShrink == 1.0)
            assert(cs.flexGrow == 0.0)
            assert(cs.width == Length.Auto)
            assert(cs.height == Length.Auto)
            assert(cs.overflow == Style.Overflow.visible)
            assert(cs.position == Style.Position.flow)
        }
    }

    "Length" - {
        "Px" in {
            assert(Length.Px(42) == 42.px)
        }

        "Pct" in {
            assert(Length.Pct(50.0) == 50.pct)
        }

        "Auto" in {
            assert(Length.Auto != Length.zero)
            assert(Length.Auto == Length.Auto)
        }

        "zero" in {
            assert(Length.zero == Length.Px(0))
        }

        "extensions" in {
            assert(10.px == Length.Px(10.0))
            assert(50.pct == Length.Pct(50.0))
            assert(2.em == Length.Em(2.0))
        }
    }

    val black = RGB(0, 0, 0)

    "RGB" - {
        "pack and unpack round-trip" in {
            val color = RGB(128, 64, 32)
            assert(color.r == 128)
            assert(color.g == 64)
            assert(color.b == 32)
        }

        "white" in {
            val white = RGB(255, 255, 255)
            assert(white.r == 255)
            assert(white.g == 255)
            assert(white.b == 255)
        }

        "Transparent" in {
            assert(RGB.Transparent != black)
        }

        "fromStyle Transparent" in {
            assert(RGB.fromStyle(Style.Color.Transparent, black) == RGB.Transparent)
        }

        "fromStyle Rgb" in {
            val c = RGB.fromStyle(Style.Color.rgb(255, 0, 0), black)
            assert(c.r == 255)
            assert(c.g == 0)
            assert(c.b == 0)
        }

        "fromStyle Rgba full opacity" in {
            val c = RGB.fromStyle(Style.Color.rgba(100, 200, 50, 1.0), black)
            assert(c.r == 100)
            assert(c.g == 200)
            assert(c.b == 50)
        }

        "fromStyle Rgba blending" in {
            val c = RGB.fromStyle(Style.Color.rgba(200, 100, 50, 0.5), black)
            assert(c.r == 100)
            assert(c.g == 50)
            assert(c.b == 25)
        }

        "fromStyle Hex 6-digit" in {
            val c = RGB.fromStyle(Style.Color.hex("ff8000"), black)
            assert(c.r == 255)
            assert(c.g == 128)
            assert(c.b == 0)
        }

        "fromStyle Hex 3-digit" in {
            val c = RGB.fromStyle(Style.Color.hex("f00"), black)
            assert(c.r == 255)
            assert(c.g == 0)
            assert(c.b == 0)
        }
    }

    "Rect" - {
        "contains point inside" in {
            assert(Rect(0, 0, 10, 10).contains(5, 5))
        }

        "contains point on origin" in {
            assert(Rect(0, 0, 10, 10).contains(0, 0))
        }

        "does not contain point at w,h" in {
            // contains uses < not <=
            assert(!Rect(0, 0, 10, 10).contains(10, 10))
        }

        "does not contain point outside" in {
            assert(!Rect(5, 5, 10, 10).contains(4, 5))
        }

        "zero-size rect contains nothing" in {
            assert(!Rect(5, 5, 0, 0).contains(5, 5))
        }

        "intersect overlapping" in {
            val r = Rect(0, 0, 10, 10).intersect(Rect(5, 5, 10, 10))
            assert(r == Rect(5, 5, 5, 5))
        }

        "intersect non-overlapping" in {
            val r = Rect(0, 0, 5, 5).intersect(Rect(10, 10, 5, 5))
            assert(r.w == 0 || r.h == 0)
        }

        "intersect identical" in {
            val r = Rect(3, 3, 7, 7).intersect(Rect(3, 3, 7, 7))
            assert(r == Rect(3, 3, 7, 7))
        }
    }

    "RGB edge cases" - {
        "Transparent component extraction" in {
            // RGB.Transparent = -1 as Int. Extracting r/g/b from -1:
            // (-1 >> 16) & 0xff = 255, (-1 >> 8) & 0xff = 255, (-1) & 0xff = 255
            // This means Transparent looks like white when components are extracted
            val t = RGB.Transparent
            assert(t.r == 255)
            assert(t.g == 255)
            assert(t.b == 255)
        }

        "lerp f=0 returns first color" in {
            val a      = RGB(100, 50, 25)
            val b      = RGB(200, 100, 50)
            val result = a.lerp(b, 0.0)
            assert(result.r == 100)
            assert(result.g == 50)
            assert(result.b == 25)
        }

        "lerp f=1 returns second color" in {
            val a      = RGB(100, 50, 25)
            val b      = RGB(200, 100, 50)
            val result = a.lerp(b, 1.0)
            assert(result.r == 200)
            assert(result.g == 100)
            assert(result.b == 50)
        }

        "lerp f=0.5 returns midpoint" in {
            val a      = RGB(0, 0, 0)
            val b      = RGB(200, 100, 50)
            val result = a.lerp(b, 0.5)
            assert(result.r == 100)
            assert(result.g == 50)
            assert(result.b == 25)
        }

        "invert white to black" in {
            val w   = RGB(255, 255, 255)
            val inv = w.invert
            assert(inv.r == 0)
            assert(inv.g == 0)
            assert(inv.b == 0)
        }

        "invert black to white" in {
            val b   = RGB(0, 0, 0)
            val inv = b.invert
            assert(inv.r == 255)
            assert(inv.g == 255)
            assert(inv.b == 255)
        }

        "clamp negative" in {
            assert(RGB.clamp(-10) == 0)
        }

        "clamp above 255" in {
            assert(RGB.clamp(300) == 255)
        }

        "clamp in range" in {
            assert(RGB.clamp(128) == 128)
        }
    }

    "Length" - {
        "resolve Pct of 0 parent" in {
            assert(Length.resolve(Length.Pct(50), 0) == 0)
        }

        "resolve Auto fills parent" in {
            assert(Length.resolve(Length.Auto, 100) == 100)
        }

        "resolveOrAuto Auto returns Absent" in {
            assert(Length.resolveOrAuto(Length.Auto, 100).isEmpty)
        }

        "resolveOrAuto Px returns Present" in {
            assert(Length.resolveOrAuto(Length.Px(42), 100) == Maybe(42))
        }

        "toPx Em converts" in {
            assert(Length.toPx(Length.Em(3)) == Length.Px(3))
        }

        "toPx Pct returns zero" in {
            assert(Length.toPx(Length.Pct(50)) == Length.zero)
        }

        "toPx Auto returns zero" in {
            assert(Length.toPx(Length.Auto) == Length.zero)
        }
    }

end IRTest
