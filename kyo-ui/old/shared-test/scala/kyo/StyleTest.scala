package kyo

import kyo.Style.*
import kyo.internal.CssStyleRenderer
import kyo.internal.FxCssStyleRenderer
import scala.language.implicitConversions

class StyleTest extends Test:

    extension (s: Style)
        def toCss: String   = CssStyleRenderer.render(s)
        def toFxCss: String = FxCssStyleRenderer.render(s)

    extension (s: Style.Size)
        def css: String   = CssStyleRenderer.size(s)
        def fxCss: String = FxCssStyleRenderer.size(s)

    "empty" - {
        "empty style" in {
            assert(Style.empty.isEmpty)
            assert(Style.empty.toCss == "")
            assert(Style.empty.toFxCss == "")
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
            assert(Style.Size.px(10).css == "10px")
        }

        "px from double" in {
            assert(Style.Size.px(10.5).css == "10.5px")
        }

        "px zero" in {
            assert(Style.Size.px(0).css == "0")
        }

        "pct" in {
            assert(Style.Size.pct(50).css == "50%")
        }

        "pct from double" in {
            assert(Style.Size.pct(33.3).css == "33.3%")
        }

        "em" in {
            assert(Style.Size.em(1.5).css == "1.5em")
        }

        "em from int" in {
            assert(Style.Size.em(2).css == "2em")
        }

        "auto" in {
            assert(Style.Size.auto.css == "auto")
        }

        "zero" in {
            assert(Style.Size.zero.css == "0")
        }

        "extension methods" in {
            assert(10.px.css == "10px")
            assert(50.pct.css == "50%")
            assert(1.em.css == "1em")
            assert(1.5.px.css == "1.5px")
            assert(50.0.pct.css == "50%")
            assert(1.5.em.css == "1.5em")
        }

        "fxCss" in {
            assert(Style.Size.px(10).fxCss == "10")
            assert(Style.Size.px(10.5).fxCss == "10.5")
            assert(Style.Size.px(0).fxCss == "0")
            assert(Style.Size.auto.fxCss == "Infinity")
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

        "bg toFxCss" in {
            val s = Style.bg("#abc")
            assert(s.toFxCss == "-fx-background-color: #abc;")
        }
    }

    "text color" - {
        "color with Color" in {
            val s = Style.color(Style.Color.blue)
            assert(s.toCss == "color: #3b82f6;")
        }

        "color toFxCss uses text-fill" in {
            val s = Style.color("#000")
            assert(s.toFxCss == "-fx-text-fill: #000;")
        }
    }

    "padding" - {
        "single int" in {
            val s = Style.padding(10)
            assert(s.toCss == "padding: 10px 10px 10px 10px;")
        }

        "vertical horizontal" in {
            val s = Style.padding(10, 20)
            assert(s.toCss == "padding: 10px 20px 10px 20px;")
        }

        "four sides" in {
            val s = Style.padding(1, 2, 3, 4)
            assert(s.toCss == "padding: 1px 2px 3px 4px;")
        }

        "with Size" in {
            val s = Style.padding(Style.Size.em(1))
            assert(s.toCss == "padding: 1em 1em 1em 1em;")
        }

        "toFxCss" in {
            val s = Style.padding(10)
            assert(s.toFxCss == "-fx-padding: 10 10 10 10;")
        }
    }

    "margin" - {
        "single int" in {
            val s = Style.margin(10)
            assert(s.toCss == "margin: 10px 10px 10px 10px;")
        }

        "toFxCss is empty (not supported)" in {
            val s = Style.margin(10)
            assert(s.toFxCss == "")
        }
    }

    "gap" - {
        "css" in {
            assert(Style.gap(8).toCss == "gap: 8px;")
        }

        "fxCss uses spacing" in {
            assert(Style.gap(8).toFxCss == "-fx-spacing: 8;")
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
            assert(Style.width(100).toCss == "width: 100px;")
            assert(Style.width(100).toFxCss == "-fx-pref-width: 100;")
        }

        "height" in {
            assert(Style.height(50).toCss == "height: 50px;")
            assert(Style.height(50).toFxCss == "-fx-pref-height: 50;")
        }

        "minWidth" in {
            assert(Style.minWidth(100).toCss == "min-width: 100px;")
            assert(Style.minWidth(100).toFxCss == "-fx-min-width: 100;")
        }

        "maxWidth" in {
            assert(Style.maxWidth(200).toCss == "max-width: 200px;")
            assert(Style.maxWidth(200).toFxCss == "-fx-max-width: 200;")
        }

        "minHeight" in {
            assert(Style.minHeight(50).toCss == "min-height: 50px;")
            assert(Style.minHeight(50).toFxCss == "-fx-min-height: 50;")
        }

        "maxHeight" in {
            assert(Style.maxHeight(300).toCss == "max-height: 300px;")
            assert(Style.maxHeight(300).toFxCss == "-fx-max-height: 300;")
        }

        "with double" in {
            assert(Style.width(10.5).toCss == "width: 10.5px;")
        }

        "with Size" in {
            assert(Style.width(Style.Size.pct(100)).toCss == "width: 100%;")
        }
    }

    "typography" - {
        "fontSize" in {
            assert(Style.fontSize(16).toCss == "font-size: 16px;")
            assert(Style.fontSize(16).toFxCss == "-fx-font-size: 16;")
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
            assert(Style.fontFamily("Arial").toCss == "font-family: Arial;")
            assert(Style.fontFamily("Arial").toFxCss == "-fx-font-family: 'Arial';")
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
            assert(Style.underline.toFxCss == "-fx-underline: true;")
            assert(Style.strikethrough.toFxCss == "-fx-strikethrough: true;")
            assert(Style.textDecoration(Style.TextDecoration.none).toFxCss == "-fx-underline: false; -fx-strikethrough: false;")
        }

        "lineHeight" in {
            assert(Style.lineHeight(1.5).toCss == "line-height: 1.5;")
            assert(Style.lineHeight(2).toCss == "line-height: 2;")
            assert(Style.lineHeight(1.5).toFxCss == "")
        }

        "letterSpacing" in {
            assert(Style.letterSpacing(2).toCss == "letter-spacing: 2px;")
            assert(Style.letterSpacing(2).toFxCss == "")
        }

        "textTransform" in {
            assert(Style.textTransform(Style.TextTransform.none).toCss == "text-transform: none;")
            assert(Style.textTransform(Style.TextTransform.uppercase).toCss == "text-transform: uppercase;")
            assert(Style.textTransform(Style.TextTransform.lowercase).toCss == "text-transform: lowercase;")
            assert(Style.textTransform(Style.TextTransform.capitalize).toCss == "text-transform: capitalize;")
            assert(Style.textTransform(Style.TextTransform.uppercase).toFxCss == "")
        }

        "textOverflow" in {
            assert(Style.textOverflow(Style.TextOverflow.clip).toCss == "text-overflow: clip;")
            assert(Style.textOverflow(Style.TextOverflow.ellipsis).toCss == "text-overflow: ellipsis;")
            assert(Style.textOverflow(Style.TextOverflow.ellipsis).toFxCss == "-fx-text-overrun: ellipsis;")
            assert(Style.textOverflow(Style.TextOverflow.clip).toFxCss == "-fx-text-overrun: clip;")
        }
    }

    "borders" - {
        "border shorthand" in {
            val s = Style.border(1, Style.BorderStyle.solid, "#000")
            assert(s.toCss.contains("border-width: 1px 1px 1px 1px;"))
            assert(s.toCss.contains("border-style: solid;"))
            assert(s.toCss.contains("border-color: #000 #000 #000 #000;"))
        }

        "border with Color" in {
            val s = Style.border(2, Style.Color.red)
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
            assert(Style.borderWidth(1).toCss == "border-width: 1px 1px 1px 1px;")
        }

        "borderWidth four sides" in {
            assert(Style.borderWidth(1, 2, 3, 4).toCss == "border-width: 1px 2px 3px 4px;")
        }

        "borderStyle" in {
            assert(Style.borderStyle(Style.BorderStyle.none).toCss == "border-style: none;")
            assert(Style.borderStyle(Style.BorderStyle.solid).toCss == "border-style: solid;")
            assert(Style.borderStyle(Style.BorderStyle.dashed).toCss == "border-style: dashed;")
            assert(Style.borderStyle(Style.BorderStyle.dotted).toCss == "border-style: dotted;")
        }

        "borderTop" in {
            assert(Style.borderTop(1, "#000").toCss == "border-top: 1px solid #000;")
        }

        "borderRight" in {
            assert(Style.borderRight(1, "#000").toCss == "border-right: 1px solid #000;")
        }

        "borderBottom" in {
            assert(Style.borderBottom(1, "#000").toCss == "border-bottom: 1px solid #000;")
        }

        "borderLeft" in {
            assert(Style.borderLeft(1, "#000").toCss == "border-left: 1px solid #000;")
        }

        "borderTop toFxCss" in {
            val s = Style.borderTop(1, "#000")
            assert(s.toFxCss.contains("-fx-border-color: #000 transparent transparent transparent;"))
            assert(s.toFxCss.contains("-fx-border-width: 1 0 0 0;"))
        }

        "borderBottom toFxCss" in {
            val s = Style.borderBottom(2, "#abc")
            assert(s.toFxCss.contains("-fx-border-color: transparent transparent #abc transparent;"))
            assert(s.toFxCss.contains("-fx-border-width: 0 0 2 0;"))
        }
    }

    "border radius" - {
        "single value" in {
            assert(Style.rounded(8).toCss == "border-radius: 8px 8px 8px 8px;")
        }

        "four values" in {
            assert(Style.rounded(1, 2, 3, 4).toCss == "border-radius: 1px 2px 3px 4px;")
        }

        "with Size" in {
            assert(Style.rounded(Style.Size.pct(50)).toCss == "border-radius: 50% 50% 50% 50%;")
        }

        "toFxCss includes both background and border radius" in {
            val fx = Style.rounded(8).toFxCss
            assert(fx.contains("-fx-background-radius: 8 8 8 8;"))
            assert(fx.contains("-fx-border-radius: 8 8 8 8;"))
        }
    }

    "effects" - {
        "shadow" in {
            val s = Style.shadow(x = 0, y = 2, blur = 4)
            assert(s.toCss.contains("box-shadow: 0 2px 4px 0 "))
        }

        "shadow toFxCss" in {
            val s = Style.shadow(y = 2, blur = 4)
            assert(s.toFxCss.contains("-fx-effect: dropshadow(gaussian,"))
        }

        "opacity" in {
            assert(Style.opacity(0.5).toCss == "opacity: 0.5;")
            assert(Style.opacity(0.5).toFxCss == "-fx-opacity: 0.5;")
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
            assert(Style.cursor(Style.Cursor.wait_).toCss == "cursor: wait;")
            assert(Style.cursor(Style.Cursor.grab).toCss == "cursor: grab;")
            assert(Style.cursor(Style.Cursor.grabbing).toCss == "cursor: grabbing;")
        }

        "fxCss" in {
            assert(Style.cursor(Style.Cursor.pointer).toFxCss == "-fx-cursor: pointer;")
        }
    }

    "transforms" - {
        "translate" in {
            assert(Style.translate(10, 20).toCss == "transform: translate(10px, 20px);")
            assert(Style.translate(10, 20).toFxCss == "-fx-translate-x: 10; -fx-translate-y: 20;")
        }
    }

    "overflow" - {
        "all variants" in {
            assert(Style.overflow(Style.Overflow.visible).toCss == "overflow: visible;")
            assert(Style.overflow(Style.Overflow.hidden).toCss == "overflow: hidden;")
            assert(Style.overflow(Style.Overflow.scroll).toCss == "overflow: scroll;")
            assert(Style.overflow(Style.Overflow.auto).toCss == "overflow: auto;")
        }

        "fxCss is empty" in {
            assert(Style.overflow(Style.Overflow.hidden).toFxCss == "")
        }
    }

    "pseudo-states" - {
        "hover" in {
            val s = Style.bg("#fff").hover(Style.bg("#eee"))
            assert(s.hoverStyle.nonEmpty)
            s.hoverStyle match
                case Present(h) => assert(h.toCss == "background-color: #eee;")
                case _          => fail("expected hover style")
        }

        "focus" in {
            val s = Style.bg("#fff").focus(Style.border(2, "#00f"))
            assert(s.focusStyle.nonEmpty)
        }

        "active" in {
            val s = Style.bg("#fff").active(Style.bg("#ccc"))
            assert(s.activeStyle.nonEmpty)
        }

        "baseProps excludes pseudo-states" in {
            val s    = Style.bg("#fff").hover(Style.bg("#eee")).focus(Style.bg("#ddd"))
            val base = s.baseProps
            assert(base.toCss == "background-color: #fff;")
            assert(base.hoverStyle.isEmpty)
            assert(base.focusStyle.isEmpty)
        }

        "pseudo-states produce empty string in toCss" in {
            val s = Style.hover(Style.bg("#eee"))
            assert(s.toCss == "")
        }

        "pseudo-states produce empty string in toFxCss" in {
            val s = Style.hover(Style.bg("#eee"))
            assert(s.toFxCss == "")
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
            val s = Style.flexGrow(2.0f)
            assert(s.props(0) == Style.Prop.FlexGrowProp(2.0f))
        }

        "grow convenience" in {
            val s = Style.grow
            assert(s.props(0) == Style.Prop.FlexGrowProp(1.0f))
        }

        "flexShrink" in {
            val s = Style.flexShrink(0.5f)
            assert(s.props(0) == Style.Prop.FlexShrinkProp(0.5f))
        }

        "noShrink convenience" in {
            val s = Style.noShrink
            assert(s.props(0) == Style.Prop.FlexShrinkProp(0.0f))
        }

        "chaining grow and noShrink" in {
            val s = Style.grow.noShrink
            assert(s.props.size == 2)
        }
    }

    "filters" - {
        "brightness" in {
            val s = Style.brightness(1.5f)
            assert(s.props(0) == Style.Prop.BrightnessProp(1.5f))
        }

        "contrast" in {
            val s = Style.contrast(0.8f)
            assert(s.props(0) == Style.Prop.ContrastProp(0.8f))
        }

        "grayscale" in {
            val s = Style.grayscale(1.0f)
            assert(s.props(0) == Style.Prop.GrayscaleProp(1.0f))
        }

        "sepia" in {
            val s = Style.sepia(0.5f)
            assert(s.props(0) == Style.Prop.SepiaProp(0.5f))
        }

        "invert" in {
            val s = Style.invert(1.0f)
            assert(s.props(0) == Style.Prop.InvertProp(1.0f))
        }

        "saturate" in {
            val s = Style.saturate(2.0f)
            assert(s.props(0) == Style.Prop.SaturateProp(2.0f))
        }

        "hueRotate" in {
            val s = Style.hueRotate(90.0f)
            assert(s.props(0) == Style.Prop.HueRotateProp(90.0f))
        }

        "blur" in {
            val s = Style.blur(5.0f)
            assert(s.props(0) == Style.Prop.BlurProp(5.0f))
        }

        "chaining multiple filters" in {
            val s = Style.brightness(1.2f).contrast(0.9f).grayscale(0.5f)
            assert(s.props.size == 3)
        }
    }

    "background gradient" - {
        "basic gradient" in {
            val s    = Style.bgGradient(GradientDirection.toRight, ("#fff", 0.0f), ("#000", 1.0f))
            val prop = s.props(0).asInstanceOf[Style.Prop.BgGradientProp]
            assert(prop.direction == GradientDirection.toRight)
            assert(prop.colors.size == 2)
            assert(prop.positions.size == 2)
            assert(prop.colors(0) == "#fff")
            assert(prop.colors(1) == "#000")
            assert(prop.positions(0) == 0.0f)
            assert(prop.positions(1) == 1.0f)
        }

        "three-stop gradient" in {
            val s = Style.bgGradient(
                GradientDirection.toBottom,
                ("#f00", 0.0f),
                ("#0f0", 0.5f),
                ("#00f", 1.0f)
            )
            val prop = s.props(0).asInstanceOf[Style.Prop.BgGradientProp]
            assert(prop.colors.size == 3)
            assert(prop.positions.size == 3)
            assert(prop.colors(1) == "#0f0")
            assert(prop.positions(1) == 0.5f)
        }

        "all gradient directions" in {
            assert(GradientDirection.toRight != GradientDirection.toLeft)
            assert(GradientDirection.toTop != GradientDirection.toBottom)
            assert(GradientDirection.toTopRight != GradientDirection.toBottomLeft)
            assert(GradientDirection.toTopLeft != GradientDirection.toBottomRight)
        }

        "parallel spans avoid tuple boxing" in {
            val s    = Style.bgGradient(GradientDirection.toRight, ("#fff", 0.0f), ("#000", 1.0f))
            val prop = s.props(0).asInstanceOf[Style.Prop.BgGradientProp]
            // colors is Span[String], positions is Span[Float] — separate arrays, no Tuple2
            assert(prop.colors.size == prop.positions.size)
        }

        "chained with other styles" in {
            val s = Style.bg("#ccc").bgGradient(GradientDirection.toRight, ("#fff", 0.0f), ("#000", 1.0f))
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
            s.disabledStyle match
                case Present(d) => assert(d.toCss == "background-color: #ccc;")
                case _          => fail("expected disabled style")
        }

        "disabled extraction when absent" in {
            val s = Style.bg("#fff")
            assert(s.disabledStyle.isEmpty)
        }

        "baseProps excludes disabled" in {
            val s    = Style.bg("#fff").disabled(Style.bg("#ccc"))
            val base = s.baseProps
            assert(base.toCss == "background-color: #fff;")
            assert(base.disabledStyle.isEmpty)
        }

        "baseProps excludes all pseudo-states together" in {
            val s = Style.bg("#fff")
                .hover(Style.bg("#eee"))
                .focus(Style.bg("#ddd"))
                .active(Style.bg("#ccc"))
                .disabled(Style.bg("#bbb"))
            val base = s.baseProps
            assert(base.toCss == "background-color: #fff;")
            assert(base.hoverStyle.isEmpty)
            assert(base.focusStyle.isEmpty)
            assert(base.activeStyle.isEmpty)
            assert(base.disabledStyle.isEmpty)
        }

        "disabled with nested styles" in {
            val inner = Style.bg("#ccc").opacity(0.5).cursor(Cursor.notAllowed)
            val s     = Style.bg("#fff").disabled(inner)
            s.disabledStyle match
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
            assert(Style.flexGrow(1.0f).props(0) == Style.empty.flexGrow(1.0f).props(0))
        }

        "flexShrink" in {
            assert(Style.flexShrink(0.5f).props(0) == Style.empty.flexShrink(0.5f).props(0))
        }

        "grow" in {
            assert(Style.grow.props(0) == Style.empty.grow.props(0))
        }

        "noShrink" in {
            assert(Style.noShrink.props(0) == Style.empty.noShrink.props(0))
        }

        "brightness" in {
            assert(Style.brightness(1.0f).props(0) == Style.empty.brightness(1.0f).props(0))
        }

        "blur" in {
            assert(Style.blur(5.0f).props(0) == Style.empty.blur(5.0f).props(0))
        }

        "bgGradient" in {
            val a  = Style.bgGradient(GradientDirection.toRight, ("#fff", 0.0f), ("#000", 1.0f))
            val b  = Style.empty.bgGradient(GradientDirection.toRight, ("#fff", 0.0f), ("#000", 1.0f))
            val pa = a.props(0).asInstanceOf[Style.Prop.BgGradientProp]
            val pb = b.props(0).asInstanceOf[Style.Prop.BgGradientProp]
            assert(pa.direction == pb.direction)
            assert(pa.colors.size == pb.colors.size)
            assert(pa.positions.size == pb.positions.size)
        }

        "disabled" in {
            val inner = Style.bg("#ccc")
            assert(Style.disabled(inner).disabledStyle.nonEmpty)
        }
    }

    "UI integration" - {
        "style(Style) adds to CommonAttrs" in {
            val s   = Style.bg("#fff").bold
            val el  = UI.div.style(s)("hello")
            val ast = el.asInstanceOf[UI.AST.Div]
            assert(ast.common.uiStyle.nonEmpty)
            assert(ast.common.uiStyle.toCss.contains("background-color: #fff;"))
            assert(ast.common.uiStyle.toCss.contains("font-weight: bold;"))
        }

        "style(Style) accumulates" in {
            val el  = UI.div.style(Style.bg("#fff")).style(Style.bold)("hello")
            val ast = el.asInstanceOf[UI.AST.Div]
            assert(ast.common.uiStyle.toCss.contains("background-color"))
            assert(ast.common.uiStyle.toCss.contains("font-weight"))
        }

        "style(String) and style(Style) coexist" in {
            val el  = UI.div.style("display: flex").style(Style.bg("#fff"))("hello")
            val ast = el.asInstanceOf[UI.AST.Div]
            assert(ast.common.styles.nonEmpty)
            assert(ast.common.uiStyle.nonEmpty)
        }
    }

end StyleTest
