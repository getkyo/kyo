package kyo

import kyo.Length.*
import kyo.Style.*
import kyo.internal.CssStyleRenderer
import scala.language.implicitConversions

class StyleTest extends Test:

    extension (s: Style)
        def toCss: String = CssStyleRenderer.render(s)

    extension (s: Length)
        def css: String = CssStyleRenderer.size(s)

    extension (c: Style.Color)
        def css: String = CssStyleRenderer.color(c)

    import Style.Prop.*

    private def hoverOf(s: Style): Maybe[Style]    = s.find[HoverProp].map(_.style)
    private def focusOf(s: Style): Maybe[Style]    = s.find[FocusProp].map(_.style)
    private def activeOf(s: Style): Maybe[Style]   = s.find[ActiveProp].map(_.style)
    private def disabledOf(s: Style): Maybe[Style] = s.find[DisabledProp].map(_.style)
    private def basePropsOf(s: Style): Style =
        s.filter {
            case _: HoverProp | _: FocusProp | _: ActiveProp | _: DisabledProp => false
            case _                                                             => true
        }

    "empty" - {
        "empty style" in {
            assert(Style.empty.isEmpty)
            assert(Style.empty.toCss == "")
        }

        "nonEmpty" in {
            assert(Style.bg("#fff").nonEmpty)
        }
    }

    "composition" - {
        "++ merges props" in {
            val a = Style.bg("#fff")
            val b = Style.color("#000")
            val c = a ++ b
            assert(c.toCss.contains("background-color"))
            assert(c.toCss.contains("color"))
        }

        "chaining" in {
            val s = Style.bg("#fff").color("#000").bold
            assert(s.toCss.contains("background-color: #fff;"))
            assert(s.toCss.contains("color: #000;"))
            assert(s.toCss.contains("font-weight: bold;"))
        }
    }

    "Color" - {
        "hex" in {
            assert(Style.Color.hex("#abc").css == "#abc")
        }

        "rgb" in {
            assert(Style.Color.rgb(1, 2, 3).css == "rgb(1, 2, 3)")
        }

        "rgba" in {
            assert(Style.Color.rgba(1, 2, 3, 0.5).css == "rgba(1, 2, 3, 0.5)")
        }

        "named colors" in {
            assert(Style.Color.white.css == "#ffffff")
            assert(Style.Color.black.css == "#000000")
            assert(Style.Color.transparent.css == "transparent")
            assert(Style.Color.red.css == "#ef4444")
            assert(Style.Color.blue.css == "#3b82f6")
        }
    }

    "Size" - {
        "px from int" in {
            assert(10.px.css == "10px")
        }

        "px from double" in {
            assert(10.5.px.css == "10.5px")
        }

        "px zero" in {
            assert(0.px.css == "0")
        }

        "pct" in {
            assert(50.pct.css == "50%")
        }

        "pct from double" in {
            assert(33.3.pct.css == "33.3%")
        }

        "em" in {
            assert(1.5.em.css == "1.5em")
        }

        "em from int" in {
            assert(2.em.css == "2em")
        }

        "auto" in {
            assert(Length.Auto.css == "auto")
        }

        "zero" in {
            assert(0.px.css == "0")
        }

        "constructor" in {
            assert(10.px.css == "10px")
            assert(50.pct.css == "50%")
            assert(1.5.em.css == "1.5em")
        }
    }

    "background" - {
        "bg with Color" in {
            val s = Style.bg(Style.Color.red)
            assert(s.toCss == "background-color: #ef4444;")
        }

        "bg with hex string" in {
            val s = Style.bg("#abc")
            assert(s.toCss == "background-color: #abc;")
        }

    }

    "text color" - {
        "color with Color" in {
            val s = Style.color(Style.Color.blue)
            assert(s.toCss == "color: #3b82f6;")
        }

    }

    "padding" - {
        "single px" in {
            val s = Style.padding(10.px)
            assert(s.toCss == "padding: 10px 10px 10px 10px;")
        }

        "vertical horizontal" in {
            val s = Style.padding(10.px, 20.px)
            assert(s.toCss == "padding: 10px 20px 10px 20px;")
        }

        "four sides" in {
            val s = Style.padding(1.px, 2.px, 3.px, 4.px)
            assert(s.toCss == "padding: 1px 2px 3px 4px;")
        }

        "with em" in {
            val s = Style.padding(1.em)
            assert(s.toCss == "padding: 1em 1em 1em 1em;")
        }

    }

    "margin" - {
        "single px" in {
            val s = Style.margin(10.px)
            assert(s.toCss == "margin: 10px 10px 10px 10px;")
        }

    }

    "gap" - {
        "css" in {
            assert(Style.gap(8.px).toCss == "gap: 8px;")
        }

    }

    "alignment" - {
        "align css" in {
            assert(Style.align(Style.Alignment.center).toCss == "align-items: center;")
            assert(Style.align(Style.Alignment.start).toCss == "align-items: flex-start;")
            assert(Style.align(Style.Alignment.end).toCss == "align-items: flex-end;")
            assert(Style.align(Style.Alignment.stretch).toCss == "align-items: stretch;")
            assert(Style.align(Style.Alignment.baseline).toCss == "align-items: baseline;")
        }

        "justify css" in {
            assert(Style.justify(Style.Justification.start).toCss == "justify-content: flex-start;")
            assert(Style.justify(Style.Justification.center).toCss == "justify-content: center;")
            assert(Style.justify(Style.Justification.end).toCss == "justify-content: flex-end;")
            assert(Style.justify(Style.Justification.spaceBetween).toCss == "justify-content: space-between;")
            assert(Style.justify(Style.Justification.spaceAround).toCss == "justify-content: space-around;")
            assert(Style.justify(Style.Justification.spaceEvenly).toCss == "justify-content: space-evenly;")
        }
    }

    "sizing" - {
        "width" in {
            assert(Style.width(100.px).toCss == "width: 100px;")
        }

        "height" in {
            assert(Style.height(50.px).toCss == "height: 50px;")
        }

        "minWidth" in {
            assert(Style.minWidth(100.px).toCss == "min-width: 100px;")
        }

        "maxWidth" in {
            assert(Style.maxWidth(200.px).toCss == "max-width: 200px;")
        }

        "minHeight" in {
            assert(Style.minHeight(50.px).toCss == "min-height: 50px;")
        }

        "maxHeight" in {
            assert(Style.maxHeight(300.px).toCss == "max-height: 300px;")
        }

        "with double px" in {
            assert(Style.width(10.5.px).toCss == "width: 10.5px;")
        }

        "with pct" in {
            assert(Style.width(100.pct).toCss == "width: 100%;")
        }
    }

    "typography" - {
        "fontSize" in {
            assert(Style.fontSize(16.px).toCss == "font-size: 16px;")
        }

        "fontWeight" in {
            assert(Style.fontWeight(Style.FontWeight.bold).toCss == "font-weight: bold;")
            assert(Style.fontWeight(Style.FontWeight.normal).toCss == "font-weight: normal;")
            assert(Style.fontWeight(Style.FontWeight.w100).toCss == "font-weight: 100;")
            assert(Style.fontWeight(Style.FontWeight.w700).toCss == "font-weight: 700;")
            assert(Style.fontWeight(Style.FontWeight.w900).toCss == "font-weight: 900;")
        }

        "bold shortcut" in {
            assert(Style.bold.toCss == "font-weight: bold;")
        }

        "fontStyle" in {
            assert(Style.fontStyle(Style.FontStyle.normal).toCss == "font-style: normal;")
            assert(Style.fontStyle(Style.FontStyle.italic).toCss == "font-style: italic;")
        }

        "italic shortcut" in {
            assert(Style.italic.toCss == "font-style: italic;")
        }

        "fontFamily" in {
            assert(Style.fontFamily("Arial").toCss == "font-family: \"Arial\";")
            assert(Style.fontFamily("Courier New").toCss == "font-family: \"Courier New\";")
            assert(Style.fontFamily("Arial, Helvetica, sans-serif").toCss == "font-family: \"Arial\", \"Helvetica\", \"sans-serif\";")
            assert(Style.fontFamily("\"Already Quoted\"").toCss == "font-family: \"Already Quoted\";")
        }

        "textAlign" in {
            assert(Style.textAlign(Style.TextAlign.left).toCss == "text-align: left;")
            assert(Style.textAlign(Style.TextAlign.center).toCss == "text-align: center;")
            assert(Style.textAlign(Style.TextAlign.right).toCss == "text-align: right;")
            assert(Style.textAlign(Style.TextAlign.justify).toCss == "text-align: justify;")
        }

        "textDecoration" in {
            assert(Style.textDecoration(Style.TextDecoration.none).toCss == "text-decoration: none;")
            assert(Style.underline.toCss == "text-decoration: underline;")
            assert(Style.strikethrough.toCss == "text-decoration: line-through;")
        }

        "lineHeight" in {
            assert(Style.lineHeight(1.5).toCss == "line-height: 1.5;")
            assert(Style.lineHeight(2).toCss == "line-height: 2;")
        }

        "letterSpacing" in {
            assert(Style.letterSpacing(2.px).toCss == "letter-spacing: 2px;")
        }

        "textTransform" in {
            assert(Style.textTransform(Style.TextTransform.none).toCss == "text-transform: none;")
            assert(Style.textTransform(Style.TextTransform.uppercase).toCss == "text-transform: uppercase;")
            assert(Style.textTransform(Style.TextTransform.lowercase).toCss == "text-transform: lowercase;")
            assert(Style.textTransform(Style.TextTransform.capitalize).toCss == "text-transform: capitalize;")
        }

        "textOverflow" in {
            assert(Style.textOverflow(Style.TextOverflow.clip).toCss == "text-overflow: clip;")
            assert(Style.textOverflow(Style.TextOverflow.ellipsis).toCss == "text-overflow: ellipsis;")
        }
    }

    "borders" - {
        "border shorthand" in {
            val s = Style.border(1.px, Style.BorderStyle.solid, "#000")
            assert(s.toCss.contains("border-width: 1px 1px 1px 1px;"))
            assert(s.toCss.contains("border-style: solid;"))
            assert(s.toCss.contains("border-color: #000 #000 #000 #000;"))
        }

        "border with Color" in {
            val s = Style.border(2.px, Style.Color.red)
            assert(s.toCss.contains("border-style: solid;"))
            assert(s.toCss.contains("border-color: #ef4444"))
        }

        "borderColor" in {
            assert(Style.borderColor("#abc").toCss == "border-color: #abc #abc #abc #abc;")
        }

        "borderColor four sides" in {
            val s = Style.borderColor(Color.red, Color.blue, Color.green, Color.white)
            assert(s.toCss == "border-color: #ef4444 #3b82f6 #22c55e #ffffff;")
        }

        "borderWidth" in {
            assert(Style.borderWidth(1.px).toCss == "border-width: 1px 1px 1px 1px;")
        }

        "borderWidth four sides" in {
            assert(Style.borderWidth(1.px, 2.px, 3.px, 4.px).toCss == "border-width: 1px 2px 3px 4px;")
        }

        "borderStyle" in {
            assert(Style.borderStyle(Style.BorderStyle.none).toCss == "border-style: none;")
            assert(Style.borderStyle(Style.BorderStyle.solid).toCss == "border-style: solid;")
            assert(Style.borderStyle(Style.BorderStyle.dashed).toCss == "border-style: dashed;")
            assert(Style.borderStyle(Style.BorderStyle.dotted).toCss == "border-style: dotted;")
        }

        "borderTop" in {
            assert(Style.borderTop(1.px, "#000").toCss == "border-top: 1px solid #000;")
        }

        "borderRight" in {
            assert(Style.borderRight(1.px, "#000").toCss == "border-right: 1px solid #000;")
        }

        "borderBottom" in {
            assert(Style.borderBottom(1.px, "#000").toCss == "border-bottom: 1px solid #000;")
        }

        "borderLeft" in {
            assert(Style.borderLeft(1.px, "#000").toCss == "border-left: 1px solid #000;")
        }

    }

    "border radius" - {
        "single value" in {
            assert(Style.rounded(8.px).toCss == "border-radius: 8px 8px 8px 8px;")
        }

        "four values" in {
            assert(Style.rounded(1.px, 2.px, 3.px, 4.px).toCss == "border-radius: 1px 2px 3px 4px;")
        }

        "with pct" in {
            assert(Style.rounded(50.pct).toCss == "border-radius: 50% 50% 50% 50%;")
        }

    }

    "effects" - {
        "shadow" in {
            val s = Style.shadow(y = 2.px, blur = 4.px)
            assert(s.toCss.contains("box-shadow: 0 2px 4px 0 "))
        }

        "opacity" in {
            assert(Style.opacity(0.5).toCss == "opacity: 0.5;")
        }
    }

    "cursor" - {
        "all cursor types" in {
            assert(Style.cursor(Style.Cursor.default_).toCss == "cursor: default;")
            assert(Style.cursor(Style.Cursor.pointer).toCss == "cursor: pointer;")
            assert(Style.cursor(Style.Cursor.text).toCss == "cursor: text;")
            assert(Style.cursor(Style.Cursor.move).toCss == "cursor: move;")
            assert(Style.cursor(Style.Cursor.notAllowed).toCss == "cursor: not-allowed;")
            assert(Style.cursor(Style.Cursor.crosshair).toCss == "cursor: crosshair;")
            assert(Style.cursor(Style.Cursor.help).toCss == "cursor: help;")
            assert(Style.cursor(Style.Cursor.await).toCss == "cursor: wait;")
            assert(Style.cursor(Style.Cursor.grab).toCss == "cursor: grab;")
            assert(Style.cursor(Style.Cursor.grabbing).toCss == "cursor: grabbing;")
        }

    }

    "transforms" - {
        "translate" in {
            assert(Style.translate(10.px, 20.px).toCss == "transform: translate(10px, 20px);")
        }
    }

    "overflow" - {
        "all variants" in {
            assert(Style.overflow(Style.Overflow.visible).toCss == "overflow: visible;")
            assert(Style.overflow(Style.Overflow.hidden).toCss == "overflow: hidden;")
            assert(Style.overflow(Style.Overflow.scroll).toCss == "overflow: scroll;")
            assert(Style.overflow(Style.Overflow.auto).toCss == "overflow: auto;")
        }

    }

    "pseudo-states" - {
        "hover" in {
            val s = Style.bg("#fff").hover(Style.bg("#eee"))
            assert(hoverOf(s).nonEmpty)
            hoverOf(s) match
                case Present(h) => assert(h.toCss == "background-color: #eee;")
                case _          => fail("expected hover style")
        }

        "focus" in {
            val s = Style.bg("#fff").focus(Style.border(2.px, "#00f"))
            assert(focusOf(s).nonEmpty)
        }

        "active" in {
            val s = Style.bg("#fff").active(Style.bg("#ccc"))
            assert(activeOf(s).nonEmpty)
        }

        "baseProps excludes pseudo-states" in {
            val s    = Style.bg("#fff").hover(Style.bg("#eee")).focus(Style.bg("#ddd"))
            val base = basePropsOf(s)
            assert(base.toCss == "background-color: #fff;")
            assert(hoverOf(base).isEmpty)
            assert(focusOf(base).isEmpty)
        }

        "pseudo-states produce empty string in toCss" in {
            val s = Style.hover(Style.bg("#eee"))
            assert(s.toCss == "")
        }

    }

    "position" - {
        "flow" in {
            val s = Style.position(Position.flow)
            assert(s.nonEmpty)
            assert(s.props(0) == Style.Prop.PositionProp(Position.flow))
        }

        "overlay" in {
            val s = Style.position(Position.overlay)
            assert(s.props(0) == Style.Prop.PositionProp(Position.overlay))
        }

        "enum values" in {
            assert(Position.flow != Position.overlay)
        }
    }

    "flex grow/shrink" - {
        "flexGrow" in {
            val s = Style.flexGrow(2.0)
            assert(s.props(0) == Style.Prop.FlexGrowProp(2.0))
        }

        "flexShrink" in {
            val s = Style.flexShrink(0.5)
            assert(s.props(0) == Style.Prop.FlexShrinkProp(0.5))
        }

        "chaining flexGrow and flexShrink" in {
            val s = Style.flexGrow(1.0).flexShrink(0.0)
            assert(s.props.size == 2)
        }
    }

    "displayNone" - {
        "displayNone sets HiddenProp" in {
            val s = Style.displayNone
            assert(s.props(0) == Style.Prop.HiddenProp)
        }
    }

    "filters" - {
        "brightness" in {
            val s = Style.brightness(1.5)
            assert(s.props(0) == Style.Prop.BrightnessProp(1.5))
        }

        "contrast" in {
            val s = Style.contrast(0.8)
            assert(s.props(0) == Style.Prop.ContrastProp(0.8))
        }

        "grayscale" in {
            val s = Style.grayscale(1.0)
            assert(s.props(0) == Style.Prop.GrayscaleProp(1.0))
        }

        "sepia" in {
            val s = Style.sepia(0.5)
            assert(s.props(0) == Style.Prop.SepiaProp(0.5))
        }

        "invert" in {
            val s = Style.invert(1.0)
            assert(s.props(0) == Style.Prop.InvertProp(1.0))
        }

        "saturate" in {
            val s = Style.saturate(2.0)
            assert(s.props(0) == Style.Prop.SaturateProp(2.0))
        }

        "hueRotate" in {
            val s = Style.hueRotate(90.0)
            assert(s.props(0) == Style.Prop.HueRotateProp(90.0))
        }

        "blur" in {
            val s = Style.blur(5.px)
            assert(s.props(0) == Style.Prop.BlurProp(Length.Px(5)))
        }

        "chaining multiple filters" in {
            val s = Style.brightness(1.2).contrast(0.9).grayscale(0.5)
            assert(s.props.size == 3)
        }
    }

    "background gradient" - {
        "basic gradient" in {
            val s    = Style.bgGradient(GradientDirection.toRight, (Color.hex("#fff"), 0.pct), (Color.hex("#000"), 100.pct))
            val prop = s.props(0).asInstanceOf[Style.Prop.BgGradientProp]
            assert(prop.direction == GradientDirection.toRight)
            assert(prop.colors.size == 2)
            assert(prop.positions.size == 2)
            assert(prop.colors(0) == Color.hex("#fff"))
            assert(prop.colors(1) == Color.hex("#000"))
            assert(prop.positions(0) == 0.0)
            assert(prop.positions(1) == 100.0)
        }

        "three-stop gradient" in {
            val s = Style.bgGradient(
                GradientDirection.toBottom,
                (Color.hex("#f00"), 0.pct),
                (Color.hex("#0f0"), 50.pct),
                (Color.hex("#00f"), 100.pct)
            )
            val prop = s.props(0).asInstanceOf[Style.Prop.BgGradientProp]
            assert(prop.colors.size == 3)
            assert(prop.positions.size == 3)
            assert(prop.colors(1) == Color.hex("#0f0"))
            assert(prop.positions(1) == 50.0)
        }

        "all gradient directions" in {
            assert(GradientDirection.toRight != GradientDirection.toLeft)
            assert(GradientDirection.toTop != GradientDirection.toBottom)
            assert(GradientDirection.toTopRight != GradientDirection.toBottomLeft)
            assert(GradientDirection.toTopLeft != GradientDirection.toBottomRight)
        }

        "parallel spans avoid tuple boxing" in {
            val s    = Style.bgGradient(GradientDirection.toRight, (Color.hex("#fff"), 0.pct), (Color.hex("#000"), 100.pct))
            val prop = s.props(0).asInstanceOf[Style.Prop.BgGradientProp]
            // colors is Span[Color], positions is Span[Double] — separate arrays, no Tuple2
            assert(prop.colors.size == prop.positions.size)
        }

        "chained with other styles" in {
            val s = Style.bg("#ccc").bgGradient(GradientDirection.toRight, (Color.hex("#fff"), 0.pct), (Color.hex("#000"), 100.pct))
            assert(s.props.size == 2)
        }
    }

    "disabled pseudo-state" - {
        "disabled builder" in {
            val s = Style.disabled(Style.bg("#ccc").opacity(0.5))
            assert(s.nonEmpty)
        }

        "disabled extraction" in {
            val s = Style.bg("#fff").disabled(Style.bg("#ccc"))
            disabledOf(s) match
                case Present(d) => assert(d.toCss == "background-color: #ccc;")
                case _          => fail("expected disabled style")
        }

        "disabled extraction when absent" in {
            val s = Style.bg("#fff")
            assert(disabledOf(s).isEmpty)
        }

        "baseProps excludes disabled" in {
            val s    = Style.bg("#fff").disabled(Style.bg("#ccc"))
            val base = basePropsOf(s)
            assert(base.toCss == "background-color: #fff;")
            assert(disabledOf(base).isEmpty)
        }

        "baseProps excludes all pseudo-states together" in {
            val s = Style.bg("#fff")
                .hover(Style.bg("#eee"))
                .focus(Style.bg("#ddd"))
                .active(Style.bg("#ccc"))
                .disabled(Style.bg("#bbb"))
            val base = basePropsOf(s)
            assert(base.toCss == "background-color: #fff;")
            assert(hoverOf(base).isEmpty)
            assert(focusOf(base).isEmpty)
            assert(activeOf(base).isEmpty)
            assert(disabledOf(base).isEmpty)
        }

        "disabled with nested styles" in {
            val inner = Style.bg("#ccc").opacity(0.5).cursor(Cursor.notAllowed)
            val s     = Style.bg("#fff").disabled(inner)
            disabledOf(s) match
                case Present(d) =>
                    assert(d.toCss.contains("background-color: #ccc;"))
                    assert(d.toCss.contains("opacity: 0.5;"))
                    assert(d.toCss.contains("cursor: not-allowed;"))
                case _ => fail("expected disabled style")
            end match
        }
    }

    "companion factory methods for new props" - {
        "position" in {
            assert(Style.position(Position.flow).props(0) == Style.empty.position(Position.flow).props(0))
        }

        "flexGrow" in {
            assert(Style.flexGrow(1.0).props(0) == Style.empty.flexGrow(1.0).props(0))
        }

        "flexShrink" in {
            assert(Style.flexShrink(0.5).props(0) == Style.empty.flexShrink(0.5).props(0))
        }

        "displayNone" in {
            assert(Style.displayNone.props(0) == Style.empty.displayNone.props(0))
        }

        "brightness" in {
            assert(Style.brightness(1.0).props(0) == Style.empty.brightness(1.0).props(0))
        }

        "blur" in {
            assert(Style.blur(5.px).props(0) == Style.empty.blur(5.px).props(0))
        }

        "bgGradient" in {
            val a  = Style.bgGradient(GradientDirection.toRight, (Color.hex("#fff"), 0.pct), (Color.hex("#000"), 100.pct))
            val b  = Style.empty.bgGradient(GradientDirection.toRight, (Color.hex("#fff"), 0.pct), (Color.hex("#000"), 100.pct))
            val pa = a.props(0).asInstanceOf[Style.Prop.BgGradientProp]
            val pb = b.props(0).asInstanceOf[Style.Prop.BgGradientProp]
            assert(pa.direction == pb.direction)
            assert(pa.colors.size == pb.colors.size)
            assert(pa.positions.size == pb.positions.size)
        }

        "disabled" in {
            val inner = Style.bg("#ccc")
            assert(disabledOf(Style.disabled(inner)).nonEmpty)
        }
    }

    "UI integration" - {
        "style(Style) adds to Attrs" in {
            val s  = Style.bg("#fff").bold
            val el = UI.div.style(s)("hello")
            // unsafe: asInstanceOf — test knows the static style type
            val uiStyle = el.attrs.uiStyle.asInstanceOf[Style]
            assert(uiStyle.nonEmpty)
            assert(uiStyle.toCss.contains("background-color: #fff;"))
            assert(uiStyle.toCss.contains("font-weight: bold;"))
        }

        "style(Style) accumulates" in {
            val el = UI.div.style(Style.bg("#fff")).style(Style.bold)("hello")
            // unsafe: asInstanceOf — test knows the static style type
            val uiStyle = el.attrs.uiStyle.asInstanceOf[Style]
            assert(uiStyle.toCss.contains("background-color"))
            assert(uiStyle.toCss.contains("font-weight"))
        }
    }

end StyleTest
