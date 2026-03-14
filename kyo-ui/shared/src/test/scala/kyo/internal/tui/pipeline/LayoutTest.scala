package kyo.internal.tui.pipeline

import kyo.*
import kyo.Length.*
import kyo.Test

class LayoutTest extends Test:

    val viewport     = Rect(0, 0, 80, 24)
    val defaultStyle = FlatStyle.Default

    def styledDiv(cs: FlatStyle, children: Chunk[Styled] = Chunk.empty): Styled =
        Styled.Node(ElemTag.Div, cs, Handlers.empty, children)

    def styledText(value: String, cs: FlatStyle = defaultStyle): Styled =
        Styled.Text(value, cs)

    def withWidth(w: Int): FlatStyle        = defaultStyle.copy(width = w.px)
    def withHeight(h: Int): FlatStyle       = defaultStyle.copy(height = h.px)
    def withSize(w: Int, h: Int): FlatStyle = defaultStyle.copy(width = w.px, height = h.px)

    "simple div" - {
        "bounds fill viewport" in {
            val result = Layout.layout(styledDiv(defaultStyle), viewport)
            result.base match
                case n: Laid.Node =>
                    assert(n.bounds.x == 0)
                    assert(n.bounds.y == 0)
                    assert(n.bounds.w == 80)
                case _ => fail("expected Node")
            end match
        }

        "text at content origin" in {
            val result = Layout.layout(
                styledDiv(defaultStyle, Chunk(styledText("hello"))),
                viewport
            )
            result.base match
                case Laid.Node(_, _, _, _, _, _, children) =>
                    children(0) match
                        case Laid.Text(v, _, bounds, _) =>
                            assert(v == "hello")
                            assert(bounds.x == 0)
                            assert(bounds.y == 0)
                        case _ => fail("expected Text")
                case _ => fail("expected Node")
            end match
        }
    }

    "padding" - {
        "content shifted and size reduced" in {
            val cs = defaultStyle.copy(padLeft = 2.px, padTop = 3.px, padRight = 2.px, padBottom = 1.px)
            val result = Layout.layout(
                styledDiv(cs, Chunk(styledText("hi"))),
                viewport
            )
            result.base match
                case Laid.Node(_, _, _, _, content, _, children) =>
                    assert(content.x == 2)
                    assert(content.y == 3)
                    assert(content.w == 76)
                    children(0) match
                        case Laid.Text(_, _, bounds, _) =>
                            assert(bounds.x == 2)
                            assert(bounds.y == 3)
                        case _ => fail("expected Text")
                    end match
                case _ => fail("expected Node")
            end match
        }
    }

    "margin" - {
        "bounds shifted" in {
            val cs     = defaultStyle.copy(marLeft = 5.px, marTop = 2.px)
            val result = Layout.layout(styledDiv(cs), viewport)
            result.base match
                case n: Laid.Node =>
                    assert(n.bounds.x == 5)
                    assert(n.bounds.y == 2)
                    assert(n.bounds.w == 75) // 80 - 5
                case _ => fail("expected Node")
            end match
        }
    }

    "border" - {
        "between margin and padding" in {
            val cs = defaultStyle.copy(marLeft = 1.px, borderLeft = 1.px, padLeft = 2.px)
            val result = Layout.layout(
                styledDiv(cs, Chunk(styledText("x"))),
                viewport
            )
            result.base match
                case Laid.Node(_, _, _, bounds, content, _, children) =>
                    assert(bounds.x == 1)  // margin
                    assert(content.x == 4) // margin + border + pad
                    children(0) match
                        case Laid.Text(_, _, tb, _) =>
                            assert(tb.x == 4)
                        case _ => fail("expected Text")
                    end match
                case _ => fail("expected Node")
            end match
        }
    }

    "column layout" - {
        "children stacked vertically" in {
            val cs     = defaultStyle.copy(direction = Maybe(Style.FlexDirection.column))
            val child1 = styledDiv(withHeight(3))
            val child2 = styledDiv(withHeight(5))
            val result = Layout.layout(styledDiv(cs, Chunk(child1, child2)), viewport)
            result.base match
                case Laid.Node(_, _, _, _, _, _, children) =>
                    val c1 = children(0).asInstanceOf[Laid.Node]
                    val c2 = children(1).asInstanceOf[Laid.Node]
                    assert(c1.bounds.y == 0)
                    assert(c1.bounds.h == 3)
                    assert(c2.bounds.y == 3)
                    assert(c2.bounds.h == 5)
                case _ => fail("expected Node")
            end match
        }

        "gap between children" in {
            val cs     = defaultStyle.copy(direction = Maybe(Style.FlexDirection.column), gap = 2.px)
            val child1 = styledDiv(withHeight(3))
            val child2 = styledDiv(withHeight(4))
            val result = Layout.layout(styledDiv(cs, Chunk(child1, child2)), viewport)
            result.base match
                case Laid.Node(_, _, _, _, _, _, children) =>
                    val c2 = children(1).asInstanceOf[Laid.Node]
                    assert(c2.bounds.y == 5) // 3 + 2 gap
                case _ => fail("expected Node")
            end match
        }
    }

    "row layout" - {
        "children side by side" in {
            val cs     = defaultStyle.copy(direction = Maybe(Style.FlexDirection.row))
            val child1 = styledDiv(withSize(10, 5))
            val child2 = styledDiv(withSize(20, 5))
            val result = Layout.layout(styledDiv(cs, Chunk(child1, child2)), viewport)
            result.base match
                case Laid.Node(_, _, _, _, _, _, children) =>
                    val c1 = children(0).asInstanceOf[Laid.Node]
                    val c2 = children(1).asInstanceOf[Laid.Node]
                    assert(c1.bounds.x == 0)
                    assert(c1.bounds.w == 10)
                    assert(c2.bounds.x == 10)
                    assert(c2.bounds.w == 20)
                case _ => fail("expected Node")
            end match
        }
    }

    "flexGrow" - {
        "fills remaining space" in {
            val cs      = defaultStyle.copy(direction = Maybe(Style.FlexDirection.row), width = 100.px)
            val fixed   = styledDiv(withSize(30, 5))
            val growing = styledDiv(defaultStyle.copy(width = 10.px, height = 5.px, flexGrow = 1.0))
            val result  = Layout.layout(styledDiv(cs, Chunk(fixed, growing)), viewport)
            result.base match
                case Laid.Node(_, _, _, _, _, _, children) =>
                    val c2 = children(1).asInstanceOf[Laid.Node]
                    assert(c2.bounds.w > 10) // should have grown
                case _ => fail("expected Node")
            end match
        }
    }

    "flexShrink" - {
        "shrink reduces measured size" in {
            val cs     = defaultStyle.copy(direction = Maybe(Style.FlexDirection.row), width = 50.px)
            val child1 = styledDiv(defaultStyle.copy(height = 5.px, flexShrink = 1.0))
            val child2 = styledDiv(defaultStyle.copy(height = 5.px, flexShrink = 1.0))
            val result = Layout.layout(styledDiv(cs, Chunk(child1, child2)), viewport)
            result.base match
                case Laid.Node(_, _, _, _, _, _, children) =>
                    val c1 = children(0).asInstanceOf[Laid.Node]
                    val c2 = children(1).asInstanceOf[Laid.Node]
                    // auto-width children fill available, no overflow
                    assert(c1.bounds.w + c2.bounds.w <= 50)
                case _ => fail("expected Node")
            end match
        }
    }

    "justify" - {
        "center" in {
            val cs     = defaultStyle.copy(direction = Maybe(Style.FlexDirection.row), width = 100.px, justify = Style.Justification.center)
            val child  = styledDiv(withSize(20, 5))
            val result = Layout.layout(styledDiv(cs, Chunk(child)), viewport)
            result.base match
                case Laid.Node(_, _, _, _, _, _, children) =>
                    val c = children(0).asInstanceOf[Laid.Node]
                    assert(c.bounds.x == 40) // (100 - 20) / 2
                case _ => fail("expected Node")
            end match
        }

        "end" in {
            val cs     = defaultStyle.copy(direction = Maybe(Style.FlexDirection.row), width = 100.px, justify = Style.Justification.end)
            val child  = styledDiv(withSize(20, 5))
            val result = Layout.layout(styledDiv(cs, Chunk(child)), viewport)
            result.base match
                case Laid.Node(_, _, _, _, _, _, children) =>
                    val c = children(0).asInstanceOf[Laid.Node]
                    assert(c.bounds.x == 80) // 100 - 20
                case _ => fail("expected Node")
            end match
        }
    }

    "align" - {
        "center" in {
            val cs =
                defaultStyle.copy(direction = Maybe(Style.FlexDirection.row), width = 80.px, height = 20.px, align = Style.Alignment.center)
            val child  = styledDiv(withSize(10, 5))
            val result = Layout.layout(styledDiv(cs, Chunk(child)), viewport)
            result.base match
                case Laid.Node(_, _, _, _, _, _, children) =>
                    val c = children(0).asInstanceOf[Laid.Node]
                    // cross axis is Y for row, centered in 20
                    assert(c.bounds.y == 7) // (20 - 5) / 2 = 7
                case _ => fail("expected Node")
            end match
        }

        "stretch" in {
            val cs = defaultStyle.copy(
                direction = Maybe(Style.FlexDirection.row),
                width = 80.px,
                height = 20.px,
                align = Style.Alignment.stretch
            )
            val child  = styledDiv(withSize(10, 5))
            val result = Layout.layout(styledDiv(cs, Chunk(child)), viewport)
            result.base match
                case Laid.Node(_, _, _, _, _, _, children) =>
                    val c = children(0).asInstanceOf[Laid.Node]
                    assert(c.bounds.h == 20) // stretched to parent height
                case _ => fail("expected Node")
            end match
        }
    }

    "overflow hidden" - {
        "clip constrained" in {
            val cs     = defaultStyle.copy(width = 40.px, height = 10.px, overflow = Style.Overflow.hidden)
            val child  = styledDiv(withSize(80, 20))
            val result = Layout.layout(styledDiv(cs, Chunk(child)), viewport)
            result.base match
                case Laid.Node(_, _, _, _, _, clip, _) =>
                    assert(clip.w <= 40)
                    assert(clip.h <= 10)
                case _ => fail("expected Node")
            end match
        }
    }

    "scroll offset" - {
        "children shifted" in {
            val cs     = defaultStyle.copy(direction = Maybe(Style.FlexDirection.column), height = 20.px, scrollTop = 5)
            val child  = styledDiv(withHeight(10))
            val result = Layout.layout(styledDiv(cs, Chunk(child)), viewport)
            result.base match
                case Laid.Node(_, _, _, _, _, _, children) =>
                    val c = children(0).asInstanceOf[Laid.Node]
                    assert(c.bounds.y == -5) // shifted up by scrollTop
                case _ => fail("expected Node")
            end match
        }
    }

    "popup extraction" - {
        "popup in popups, not base children" in {
            val popup = Styled.Node(ElemTag.Popup, defaultStyle, Handlers.empty, Chunk.empty)
            val result = Layout.layout(
                styledDiv(defaultStyle, Chunk(styledText("main"), popup)),
                viewport
            )
            assert(result.popups.size == 1)
            result.base match
                case Laid.Node(_, _, _, _, _, _, children) =>
                    assert(children.size == 1) // only the text, popup extracted
                case _ => fail("expected Node")
            end match
        }
    }

    "overlay" - {
        "positioned at content + translate" in {
            val cs = defaultStyle.copy(width = 80.px, height = 24.px)
            val overlay =
                styledDiv(defaultStyle.copy(
                    position = Style.Position.overlay,
                    translateX = 10.px,
                    translateY = 5.px,
                    width = 20.px,
                    height = 10.px
                ))
            val result = Layout.layout(styledDiv(cs, Chunk(overlay)), viewport)
            result.base match
                case Laid.Node(_, _, _, _, _, _, children) =>
                    assert(children.size == 1)
                    val c = children(0).asInstanceOf[Laid.Node]
                    assert(c.bounds.x == 10)
                    assert(c.bounds.y == 5)
                case _ => fail("expected Node")
            end match
        }
    }

    "table" - {
        "column widths from widest content" in {
            val cell1  = Styled.Node(ElemTag.Div, withWidth(10), Handlers.empty, Chunk(styledText("ab")))
            val cell2  = Styled.Node(ElemTag.Div, withWidth(20), Handlers.empty, Chunk(styledText("cdef")))
            val row    = Styled.Node(ElemTag.Div, defaultStyle, Handlers.empty, Chunk(cell1, cell2))
            val table  = Styled.Node(ElemTag.Table, withSize(80, 24), Handlers.empty, Chunk(row))
            val result = Layout.layout(table, viewport)
            result.base match
                case Laid.Node(_, _, _, _, _, _, children) =>
                    assert(children.size == 1) // one row
                    children(0) match
                        case Laid.Node(_, _, _, _, _, _, cells) =>
                            assert(cells.size == 2)
                        case _ => fail("expected row Node")
                    end match
                case _ => fail("expected Node")
            end match
        }
    }

    "percentage width" - {
        "50% of 100 → 50" in {
            val cs     = defaultStyle.copy(width = 100.px)
            val child  = styledDiv(defaultStyle.copy(width = 50.pct, height = 5.px))
            val result = Layout.layout(styledDiv(cs, Chunk(child)), viewport)
            result.base match
                case Laid.Node(_, _, _, _, _, _, children) =>
                    val c = children(0).asInstanceOf[Laid.Node]
                    assert(c.bounds.w == 50)
                case _ => fail("expected Node")
            end match
        }
    }

    "auto width" - {
        "fills available space in column" in {
            val cs     = defaultStyle.copy(width = 60.px, direction = Maybe(Style.FlexDirection.column))
            val child  = styledDiv(defaultStyle) // auto width
            val result = Layout.layout(styledDiv(cs, Chunk(child)), viewport)
            result.base match
                case Laid.Node(_, _, _, _, _, _, children) =>
                    val c = children(0).asInstanceOf[Laid.Node]
                    assert(c.bounds.w == 60)
                case _ => fail("expected Node")
            end match
        }
    }

    "cursor" - {
        "at correct character offset" in {
            val result = Layout.layout(
                styledDiv(defaultStyle, Chunk(Styled.Cursor(7))),
                viewport
            )
            result.base match
                case Laid.Node(_, _, _, _, _, _, children) =>
                    children(0) match
                        case Laid.Cursor(pos) =>
                            assert(pos.x == 7)
                            assert(pos.w == 1)
                            assert(pos.h == 1)
                        case _ => fail("expected Cursor")
                case _ => fail("expected Node")
            end match
        }
    }

end LayoutTest
