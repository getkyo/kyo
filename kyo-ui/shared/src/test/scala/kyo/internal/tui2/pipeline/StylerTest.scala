package kyo.internal.tui2.pipeline

import kyo.*
import kyo.Test
import kyo.UIDsl.*

class StylerTest extends Test:

    val root = FlatStyle.Default

    def styleNode(s: Style): FlatStyle =
        val resolved = Resolved.Node(ElemTag.Div, s, Handlers.empty, Chunk.empty)
        Styler.style(resolved, root) match
            case Styled.Node(_, cs, _, _) => cs
            case _                        => fail("expected Node"); FlatStyle.Default
    end styleNode

    "inheritance" - {
        "child inherits parent color when not set" in {
            val parent = Resolved.Node(
                ElemTag.Div,
                Style.color(Style.Color.rgb(255, 0, 0)),
                Handlers.empty,
                Chunk(Resolved.Node(ElemTag.Div, Style.empty, Handlers.empty, Chunk.empty))
            )
            val styled = Styler.style(parent, root)
            styled match
                case Styled.Node(_, _, _, children) =>
                    children(0) match
                        case Styled.Node(_, cs, _, _) =>
                            assert(cs.fg == RGB(255, 0, 0))
                        case _ => fail("expected child Node")
                case _ => fail("expected Node")
            end match
        }

        "child overrides parent color when set" in {
            val parent = Resolved.Node(
                ElemTag.Div,
                Style.color(Style.Color.rgb(255, 0, 0)),
                Handlers.empty,
                Chunk(Resolved.Node(ElemTag.Div, Style.color(Style.Color.rgb(0, 255, 0)), Handlers.empty, Chunk.empty))
            )
            val styled = Styler.style(parent, root)
            styled match
                case Styled.Node(_, _, _, children) =>
                    children(0) match
                        case Styled.Node(_, cs, _, _) =>
                            assert(cs.fg == RGB(0, 255, 0))
                        case _ => fail("expected child Node")
                case _ => fail("expected Node")
            end match
        }

        "width and padding don't inherit" in {
            val parent = Resolved.Node(
                ElemTag.Div,
                Style.width(10.px).padding(5.px),
                Handlers.empty,
                Chunk(Resolved.Node(ElemTag.Div, Style.empty, Handlers.empty, Chunk.empty))
            )
            val styled = Styler.style(parent, root)
            styled match
                case Styled.Node(_, _, _, children) =>
                    children(0) match
                        case Styled.Node(_, cs, _, _) =>
                            assert(cs.width == Length.Auto)
                            assert(cs.padTop == 0.px)
                        case _ => fail("expected child Node")
                case _ => fail("expected Node")
            end match
        }

        "deep inheritance: 5 levels" in {
            def nest(depth: Int, s: Style): Resolved =
                if depth == 0 then Resolved.Node(ElemTag.Div, Style.empty, Handlers.empty, Chunk.empty)
                else Resolved.Node(ElemTag.Div, s, Handlers.empty, Chunk(nest(depth - 1, Style.empty)))

            val tree   = nest(5, Style.bold)
            val styled = Styler.style(tree, root)

            def dig(node: Styled, depth: Int): Styled =
                if depth == 0 then node
                else
                    node match
                        case Styled.Node(_, _, _, children) => dig(children(0), depth - 1)
                        case _                              => fail("expected Node"); node

            val leaf = dig(styled, 5)
            leaf match
                case Styled.Node(_, cs, _, _) => assert(cs.bold)
                case _                        => fail("expected Node")
        }
    }

    "size encoding" - {
        "width 50.pct" in {
            val cs = styleNode(Style.width(50.pct))
            cs.width match
                case Length.Pct(v) => assert(v == 50.0)
                case other         => fail(s"expected Pct, got $other")
        }

        "width 42.px" in {
            val cs = styleNode(Style.width(42.px))
            assert(cs.width == 42.px)
        }
    }

    "color encoding" - {
        "color rgb(255,0,0)" in {
            val cs = styleNode(Style.color(Style.Color.rgb(255, 0, 0)))
            assert(cs.fg == RGB(255, 0, 0))
        }

        "bg transparent" in {
            val cs = styleNode(Style.bg(Style.Color.Transparent))
            assert(cs.bg == RGB.Transparent)
        }

        "rgba blending against parent bg" in {
            val parentStyle = Style.bg(Style.Color.rgb(0, 0, 0))
            val parent = Resolved.Node(
                ElemTag.Div,
                parentStyle,
                Handlers.empty,
                Chunk(Resolved.Node(ElemTag.Div, Style.bg(Style.Color.rgba(200, 100, 50, 0.5)), Handlers.empty, Chunk.empty))
            )
            val styled = Styler.style(parent, root)
            styled match
                case Styled.Node(_, _, _, children) =>
                    children(0) match
                        case Styled.Node(_, cs, _, _) =>
                            assert(cs.bg.r == 100)
                            assert(cs.bg.g == 50)
                            assert(cs.bg.b == 25)
                        case _ => fail("expected child Node")
                case _ => fail("expected Node")
            end match
        }
    }

    "text node" - {
        "inherits visual properties" in {
            val parent = Resolved.Node(
                ElemTag.Div,
                Style.color(Style.Color.rgb(100, 200, 50)).bold,
                Handlers.empty,
                Chunk(Resolved.Text("hello"))
            )
            val styled = Styler.style(parent, root)
            styled match
                case Styled.Node(_, _, _, children) =>
                    children(0) match
                        case Styled.Text(v, cs) =>
                            assert(v == "hello")
                            assert(cs.fg == RGB(100, 200, 50))
                            assert(cs.bold)
                            assert(cs.bg == RGB.Transparent)
                            assert(cs.width == Length.Auto)
                        case _ => fail("expected Text")
                case _ => fail("expected Node")
            end match
        }
    }

    "cursor" - {
        "passes through unchanged" in {
            val parent = Resolved.Node(
                ElemTag.Div,
                Style.empty,
                Handlers.empty,
                Chunk(Resolved.Cursor(5))
            )
            val styled = Styler.style(parent, root)
            styled match
                case Styled.Node(_, _, _, children) =>
                    children(0) match
                        case Styled.Cursor(offset) => assert(offset == 5)
                        case _                     => fail("expected Cursor")
                case _ => fail("expected Node")
            end match
        }
    }

    "no-op props" - {
        "fontSize and fontFamily don't crash" in {
            val cs = styleNode(Style.fontSize(16.px).fontFamily("monospace"))
            assert(cs.fg == root.fg)
        }
    }

    "gradient" - {
        "stops populated correctly" in {
            val cs = styleNode(Style.bgGradient(
                Style.GradientDirection.toRight,
                (Style.Color.rgb(255, 0, 0), 0.pct),
                (Style.Color.rgb(0, 0, 255), 100.pct)
            ))
            assert(cs.gradientDirection == Maybe(Style.GradientDirection.toRight))
            assert(cs.gradientStops.size == 2)
            assert(cs.gradientStops(0)._1.r == 255)
            assert(cs.gradientStops(1)._2 == 100.0)
        }
    }

    "all props handled" - {
        "no match error on exhaustive style" in {
            val s = Style.empty
                .bg(Style.Color.rgb(0, 0, 0))
                .color(Style.Color.rgb(255, 255, 255))
                .padding(1.px)
                .margin(1.px)
                .gap(1.px)
                .row
                .flexWrap(Style.FlexWrap.wrap)
                .align(Style.Alignment.center)
                .justify(Style.Justification.spaceBetween)
                .overflow(Style.Overflow.hidden)
                .width(100.px)
                .height(50.px)
                .minWidth(10.px)
                .maxWidth(200.px)
                .minHeight(5.px)
                .maxHeight(100.px)
                .bold
                .italic
                .underline
                .textAlign(Style.TextAlign.center)
                .lineHeight(2)
                .letterSpacing(1.px)
                .textTransform(Style.TextTransform.uppercase)
                .textOverflow(Style.TextOverflow.ellipsis)
                .textWrap(Style.TextWrap.noWrap)
                .border(1.px, Style.Color.rgb(128, 128, 128))
                .rounded(1.px)
                .shadow(1.px, 1.px, 1.px, 1.px, Style.Color.rgb(0, 0, 0))
                .opacity(0.5)
                .cursor(Style.Cursor.pointer)
                .translate(1.px, 1.px)
                .position(Style.Position.overlay)
                .flexGrow(1.0)
                .flexShrink(0.0)
                .brightness(0.8)
                .contrast(1.2)
                .grayscale(0.5)
                .sepia(0.1)
                .invert(0.0)
                .saturate(1.5)
                .hueRotate(90.0)
                .blur(2.px)
            val cs = styleNode(s)
            assert(cs.bold)
            assert(cs.italic)
            assert(cs.width == 100.px)
            assert(cs.opacity == 0.5)
        }
    }

end StylerTest
