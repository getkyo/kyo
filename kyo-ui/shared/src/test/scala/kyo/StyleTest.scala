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
            assert(Style.bg(Color.white).nonEmpty)
        }
    }

    "composition" - {
        "++ merges props" in {
            val a = Style.bg(Color.white)
            val b = Style.color(Color.black)
            val c = a ++ b
            assert(c.toCss.contains("background-color"))
            assert(c.toCss.contains("color"))
        }

        "chaining" in {
            val s = Style.bg(Color.hex("#fff").get).color(Color.hex("#000").get).bold
            assert(s.toCss.contains("background-color: #fff;"))
            assert(s.toCss.contains("color: #000;"))
            assert(s.toCss.contains("font-weight: bold;"))
        }
    }

    "dedup" - {
        "bg chaining deduplicates background-color" in {
            val s = Style.bg(Color.white).bg(Color.black)
            assert(s.props.size == 1)
            assert(s.toCss == "background-color: #000000;")
        }

        "++ deduplicates background-color" in {
            val s = Style.bg(Color.white) ++ Style.bg(Color.black)
            assert(s.toCss == "background-color: #000000;")
        }

        "++ deduplicates mixed props keeping last value" in {
            val s = Style.bg(Color.white).color(Color.black) ++ Style.bg(Color.gray).bold
            assert(s.props.size == 3)
            assert(s.toCss.contains("background-color: #6b7280;"))
            assert(s.toCss.contains("color: #000000;"))
            assert(s.toCss.contains("font-weight: bold;"))
        }

        "without[BgColor] on empty returns empty" in {
            assert(Style.empty.without[Style.Prop.BgColor].isEmpty)
        }
    }

    "Color" - {
        "hex" in {
            assert(Style.Color.hex("#abc") == Present(Style.Color.Hex("#abc")))
            assert(Style.Color.hex("abc") == Present(Style.Color.Hex("#abc")))
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

        "strict hex parser" - {
            "3-digit #RGB returns Present" in {
                assert(Color.hex("#abc") == Present(Color.Hex("#abc")))
            }

            "3-digit without # auto-prefixes and returns Present" in {
                assert(Color.hex("abc") == Present(Color.Hex("#abc")))
            }

            "6-digit #RRGGBB returns Present" in {
                assert(Color.hex("#ffffff") == Present(Color.Hex("#ffffff")))
            }

            "8-digit #RRGGBBAA returns Present" in {
                assert(Color.hex("#ffffffff") == Present(Color.Hex("#ffffffff")))
            }

            "empty string returns Absent" in {
                assert(Color.hex("") == Absent)
            }

            "invalid hex chars after auto-prefix returns Absent" in {
                assert(Color.hex("gg") == Absent)
            }

            "length-6-with-# returns Absent (not in valid set {4,5,7,9})" in {
                assert(Color.hex("#12345") == Absent)
            }

            "4-digit #RGBA returns Present" in {
                assert(Color.hex("#1234") == Present(Color.Hex("#1234")))
            }

            "3-digit #bad returns Present (b, a, d are valid hex digits)" in {
                assert(Color.hex("#bad") == Present(Color.Hex("#bad")))
            }

            "invalid contains non-hex chars returns Absent" in {
                assert(Color.hex("invalid") == Absent)
            }

            "round-trip get returns Hex wrapper" in {
                assert(Color.hex("#abc").get == Color.Hex("#abc"))
            }
        }

        "hex String overloads removed" - {
            "Style.bg(String) does not compile" in {
                assertDoesNotCompile("""Style.bg("#ff0000")""")
            }

            "Style.color(String) does not compile" in {
                assertDoesNotCompile("""Style.color("#000000")""")
            }

            "Style.borderColor(String) does not compile" in {
                assertDoesNotCompile("""Style.borderColor("#abc")""")
            }
        }

        "typed API produces correct CSS" - {
            "Style.bg(Color.red) produces background-color: #ef4444" in {
                assert(Style.bg(Color.red).toCss.contains("background-color: #ef4444;"))
            }

            "Style.color(Color.blue) produces color: #3b82f6" in {
                assert(Style.color(Color.blue).toCss.contains("color: #3b82f6;"))
            }

            "Style.borderColor(Color.hex(abc).get) produces correct border-color" in {
                assert(Style.borderColor(Color.hex("#abc").get).toCss == "border-color: #abc #abc #abc #abc;")
            }
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
            val s = Style.bg(Color.hex("#abc").get)
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

        "fontFamily generic families" in {
            assert(Style.fontFamily(FontFamily.SansSerif).toCss == "font-family: sans-serif;")
            assert(Style.fontFamily(FontFamily.Serif).toCss == "font-family: serif;")
            assert(Style.fontFamily(FontFamily.Monospace).toCss == "font-family: monospace;")
            assert(Style.fontFamily(FontFamily.Cursive).toCss == "font-family: cursive;")
            assert(Style.fontFamily(FontFamily.Fantasy).toCss == "font-family: fantasy;")
            assert(Style.fontFamily(FontFamily.SystemUi).toCss == "font-family: system-ui;")
        }

        "fontFamily custom verbatim" in {
            assert(Style.fontFamily(FontFamily.Custom("Arial")).toCss == "font-family: Arial;")
            assert(Style.fontFamily(FontFamily.Custom("Courier New")).toCss == "font-family: Courier New;")
            assert(
                Style.fontFamily(FontFamily.Custom("Arial, Helvetica, sans-serif")).toCss == "font-family: Arial, Helvetica, sans-serif;"
            )
            assert(Style.fontFamily(FontFamily.Custom("\"Already Quoted\"")).toCss == "font-family: \"Already Quoted\";")
            assert(Style.fontFamily(FontFamily.Custom("Comic Sans")).toCss == "font-family: Comic Sans;")
            assert(Style.fontFamily(FontFamily.Custom("My Font, fallback")).toCss == "font-family: My Font, fallback;")
        }

        "fontFamily lambda overload" in {
            assert(Style.fontFamily(_.Serif).toCss == "font-family: serif;")
            assert(Style.fontFamily(_.Monospace).toCss == "font-family: monospace;")
        }

        "fontFamily string arg does not compile" in {
            assertDoesNotCompile("""Style.fontFamily("monospace")""")
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
            val s = Style.border(1.px, Style.BorderStyle.solid, Color.hex("#000").get)
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
            assert(Style.borderColor(Color.hex("#abc").get).toCss == "border-color: #abc #abc #abc #abc;")
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
            val s = Style.borderTop(1.px, Color.black)
            assert(s.toCss.contains("border-width: 1px 0 0 0;"))
            assert(s.toCss.contains("border-color: #000000 transparent transparent transparent;"))
        }

        "borderRight" in {
            val s = Style.borderRight(1.px, Color.black)
            assert(s.toCss.contains("border-width: 0 1px 0 0;"))
            assert(s.toCss.contains("border-color: transparent #000000 transparent transparent;"))
        }

        "borderBottom" in {
            val s = Style.borderBottom(1.px, Color.black)
            assert(s.toCss.contains("border-width: 0 0 1px 0;"))
            assert(s.toCss.contains("border-color: transparent transparent #000000 transparent;"))
        }

        "borderLeft" in {
            val s = Style.borderLeft(1.px, Color.black)
            assert(s.toCss.contains("border-width: 0 0 0 1px;"))
            assert(s.toCss.contains("border-color: transparent transparent transparent #000000;"))
        }

    }

    "border consolidation" - {
        "borderTop RMW from empty" in {
            val s = Style.borderTop(2.px, Color.red)
            assert(s.toCss.contains("border-width: 2px 0 0 0;"))
            assert(s.toCss.contains("border-color: #ef4444 transparent transparent transparent;"))
        }

        "borderTop RMW over existing border" in {
            val s = Style.border(1.px, Color.blue).borderTop(3.px, Color.red)
            assert(s.toCss.contains("border-style: solid;"))
            assert(s.toCss.contains("border-width: 3px 1px 1px 1px;"))
            assert(s.toCss.contains("border-color: #ef4444 #3b82f6 #3b82f6 #3b82f6;"))
        }

        "border after borderTop replaces all sides" in {
            val s = Style.borderTop(2.px, Color.red).border(1.px, Color.blue)
            assert(s.toCss.contains("border-width: 1px 1px 1px 1px;"))
            assert(s.toCss.contains("border-color: #3b82f6 #3b82f6 #3b82f6 #3b82f6;"))
        }

        "multi-side chained accumulates" in {
            val s = Style.borderTop(2.px, Color.red).borderBottom(4.px, Color.green)
            assert(s.toCss.contains("border-width: 2px 0 4px 0;"))
            assert(s.toCss.contains("border-color: #ef4444 transparent #22c55e transparent;"))
        }

        "lambda overload produces same CSS as direct Color" in {
            val direct = Style.borderTop(2.px, Color.red)
            val lambda = Style.borderTop(2.px, _.red)
            assert(direct.toCss == lambda.toCss)
        }

        "last call wins: borderTop then border" in {
            val s = Style.border(1.px, Color.blue).borderTop(3.px, Color.red)
            assert(s.toCss.contains("border-width: 3px 1px 1px 1px;"))
        }

        "last call wins: border then borderTop" in {
            val s = Style.borderTop(3.px, Color.red).border(1.px, Color.blue)
            assert(s.toCss.contains("border-width: 1px 1px 1px 1px;"))
        }

        "CSS output is quad notation not per-side shorthand" in {
            val s = Style.borderTop(2.px, Color.red)
            assert(!s.toCss.contains("border-top:"))
            assert(s.toCss.contains("border-width:"))
            assert(s.toCss.contains("border-color:"))
        }

        "hex String overload removed: borderTop(width, String) does not compile" in {
            assertDoesNotCompile("""Style.borderTop(1.px, "#ef4444")""")
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
            assert(Style.cursor(Style.Cursor.defaultCursor).toCss == "cursor: default;")
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
            val s = Style.bg(Color.hex("#fff").get).hover(Style.bg(Color.hex("#eee").get))
            assert(hoverOf(s).nonEmpty)
            hoverOf(s) match
                case Present(h) => assert(h.toCss == "background-color: #eee;")
                case _          => fail("expected hover style")
        }

        "focus" in {
            val s = Style.bg(Color.white).focus(Style.border(2.px, Color.hex("#00f").get))
            assert(focusOf(s).nonEmpty)
        }

        "active" in {
            val s = Style.bg(Color.white).active(Style.bg(Color.hex("#ccc").get))
            assert(activeOf(s).nonEmpty)
        }

        "baseProps excludes pseudo-states" in {
            val s    = Style.bg(Color.hex("#fff").get).hover(Style.bg(Color.hex("#eee").get)).focus(Style.bg(Color.hex("#ddd").get))
            val base = basePropsOf(s)
            assert(base.toCss == "background-color: #fff;")
            assert(hoverOf(base).isEmpty)
            assert(focusOf(base).isEmpty)
        }

        "pseudo-states produce empty string in toCss" in {
            val s = Style.hover(Style.bg(Color.hex("#eee").get))
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

        "relative" in {
            val s = Style.position(Position.relative)
            assert(s.props(0) == Style.Prop.PositionProp(Position.relative))
        }

        "dropdown" in {
            val s = Style.position(Position.dropdown)
            assert(s.props(0) == Style.Prop.PositionProp(Position.dropdown))
        }

        "sticky" in {
            val s = Style.position(Position.sticky)
            assert(s.props(0) == Style.Prop.PositionProp(Position.sticky))
            assert(s.toCss.contains("position: sticky;"))
            assert(s.toCss.contains("top: 0;"))
            assert(s.toCss.contains("z-index: 100;"))
        }

        "enum values" in {
            assert(Position.flow != Position.overlay)
            assert(Position.relative != Position.dropdown)
            assert(Position.overlay != Position.dropdown)
            assert(Position.sticky != Position.flow)
            assert(Position.sticky != Position.overlay)
            assert(Position.sticky != Position.relative)
            assert(Position.sticky != Position.dropdown)
        }
    }

    "display" - {
        "block" in {
            val s = Style.display(Display.block)
            assert(s.nonEmpty)
            assert(s.props(0) == Style.Prop.DisplayProp(Display.block))
            assert(s.toCss == "display: block;")
        }

        "inline" in {
            val s = Style.display(Display.inline)
            assert(s.props(0) == Style.Prop.DisplayProp(Display.inline))
            assert(s.toCss == "display: inline;")
        }

        "inlineBlock" in {
            val s = Style.display(Display.inlineBlock)
            assert(s.props(0) == Style.Prop.DisplayProp(Display.inlineBlock))
            assert(s.toCss == "display: inline-block;")
        }

        "listItem" in {
            val s = Style.display(Display.listItem)
            assert(s.props(0) == Style.Prop.DisplayProp(Display.listItem))
            assert(s.toCss == "display: list-item;")
        }

        "block convenience" in {
            assert(Style.block.props(0) == Style.Prop.DisplayProp(Display.block))
            assert(Style.block.toCss == "display: block;")
        }

        "inline convenience" in {
            assert(Style.inline.props(0) == Style.Prop.DisplayProp(Display.inline))
            assert(Style.inline.toCss == "display: inline;")
        }

        "inlineBlock convenience" in {
            assert(Style.inlineBlock.props(0) == Style.Prop.DisplayProp(Display.inlineBlock))
            assert(Style.inlineBlock.toCss == "display: inline-block;")
        }

        "listItem convenience" in {
            assert(Style.listItem.props(0) == Style.Prop.DisplayProp(Display.listItem))
            assert(Style.listItem.toCss == "display: list-item;")
        }

        "selector overload" in {
            val s = Style.display(_.inline)
            assert(s.props(0) == Style.Prop.DisplayProp(Display.inline))
        }

        "dedup keeps last write" in {
            val s = Style.block.inline
            assert(s.props.size == 1)
            assert(s.props(0) == Style.Prop.DisplayProp(Display.inline))
            assert(s.toCss == "display: inline;")
        }

        "enum values" in {
            assert(Display.block != Display.inline)
            assert(Display.inline != Display.inlineBlock)
            assert(Display.inlineBlock != Display.listItem)
            assert(Display.listItem != Display.block)
        }
    }

    "listStyle" - {
        "disc" in {
            val s = Style.listStyle(ListStyle.disc)
            assert(s.props(0) == Style.Prop.ListStyleProp(ListStyle.disc))
            assert(s.toCss == "list-style-type: disc;")
        }

        "decimal" in {
            val s = Style.listStyle(ListStyle.decimal)
            assert(s.props(0) == Style.Prop.ListStyleProp(ListStyle.decimal))
            assert(s.toCss == "list-style-type: decimal;")
        }

        "none" in {
            val s = Style.listStyle(ListStyle.none)
            assert(s.props(0) == Style.Prop.ListStyleProp(ListStyle.none))
            assert(s.toCss == "list-style-type: none;")
        }

        "selector overload" in {
            val s = Style.listStyle(_.decimal)
            assert(s.props(0) == Style.Prop.ListStyleProp(ListStyle.decimal))
        }

        "enum values" in {
            assert(ListStyle.disc != ListStyle.decimal)
            assert(ListStyle.decimal != ListStyle.none)
            assert(ListStyle.none != ListStyle.disc)
        }
    }

    "borderCollapse" - {
        "collapse" in {
            val s = Style.borderCollapse(BorderCollapse.collapse)
            assert(s.props(0) == Style.Prop.BorderCollapseProp(BorderCollapse.collapse))
            assert(s.toCss == "border-collapse: collapse;")
        }

        "separate" in {
            val s = Style.borderCollapse(BorderCollapse.separate)
            assert(s.props(0) == Style.Prop.BorderCollapseProp(BorderCollapse.separate))
            assert(s.toCss == "border-collapse: separate;")
        }

        "selector overload" in {
            val s = Style.borderCollapse(_.collapse)
            assert(s.props(0) == Style.Prop.BorderCollapseProp(BorderCollapse.collapse))
        }

        "enum values" in {
            assert(BorderCollapse.collapse != BorderCollapse.separate)
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

        "flexBasis" in {
            val s = Style.flexBasis(Length.zero)
            assert(s.props(0) == Style.Prop.FlexBasisProp(Length.zero))
        }

        "chaining flexGrow and flexShrink" in {
            val s = Style.flexGrow(1.0).flexShrink(0.0)
            assert(s.props.size == 2)
        }

        "equal columns: flex grow 1 + basis 0" in {
            val s = Style.flexGrow(1.0).flexBasis(Length.zero)
            assert(s.props.size == 2)
            assert(s.props(0) == Style.Prop.FlexGrowProp(1.0))
            assert(s.props(1) == Style.Prop.FlexBasisProp(Length.zero))
        }

        "flexBasis renders flex-basis css" in {
            assert(Style.flexBasis(Length.zero).toCss == "flex-basis: 0;")
            assert(Style.flexBasis(50.pct).toCss == "flex-basis: 50%;")
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
            val s = Style.bgGradient(GradientDirection.toRight, (Color.hex("#fff").get, 0.pct), (Color.hex("#000").get, 100.pct))
            val prop = s.props(0) match
                case p: Style.Prop.BgGradientProp => p
                case other                        => fail(s"Expected BgGradientProp, got ${other.getClass.getSimpleName}")
            assert(prop.direction == GradientDirection.toRight)
            assert(prop.colors.size == 2)
            assert(prop.positions.size == 2)
            assert(prop.colors(0) == Color.hex("#fff").get)
            assert(prop.colors(1) == Color.hex("#000").get)
            assert(prop.positions(0) == 0.0)
            assert(prop.positions(1) == 100.0)
        }

        "three-stop gradient" in {
            val s = Style.bgGradient(
                GradientDirection.toBottom,
                (Color.hex("#f00").get, 0.pct),
                (Color.hex("#0f0").get, 50.pct),
                (Color.hex("#00f").get, 100.pct)
            )
            val prop = s.props(0) match
                case p: Style.Prop.BgGradientProp => p
                case other                        => fail(s"Expected BgGradientProp, got ${other.getClass.getSimpleName}")
            assert(prop.colors.size == 3)
            assert(prop.positions.size == 3)
            assert(prop.colors(1) == Color.hex("#0f0").get)
            assert(prop.positions(1) == 50.0)
        }

        "all gradient directions" in {
            assert(GradientDirection.toRight != GradientDirection.toLeft)
            assert(GradientDirection.toTop != GradientDirection.toBottom)
            assert(GradientDirection.toTopRight != GradientDirection.toBottomLeft)
            assert(GradientDirection.toTopLeft != GradientDirection.toBottomRight)
        }

        "parallel spans avoid tuple boxing" in {
            val s = Style.bgGradient(GradientDirection.toRight, (Color.hex("#fff").get, 0.pct), (Color.hex("#000").get, 100.pct))
            val prop = s.props(0) match
                case p: Style.Prop.BgGradientProp => p
                case other                        => fail(s"Expected BgGradientProp, got ${other.getClass.getSimpleName}")
            // colors is Span[Color], positions is Span[Double] (separate arrays, no Tuple2)
            assert(prop.colors.size == prop.positions.size)
        }

        "chained with other styles" in {
            val s = Style.bg(Color.hex("#ccc").get).bgGradient(
                GradientDirection.toRight,
                (Color.hex("#fff").get, 0.pct),
                (Color.hex("#000").get, 100.pct)
            )
            assert(s.props.size == 2)
        }
    }

    "disabled pseudo-state" - {
        "disabled builder" in {
            val s = Style.disabled(Style.bg(Color.hex("#ccc").get).opacity(0.5))
            assert(s.nonEmpty)
        }

        "disabled extraction" in {
            val s = Style.bg(Color.hex("#fff").get).disabled(Style.bg(Color.hex("#ccc").get))
            disabledOf(s) match
                case Present(d) => assert(d.toCss == "background-color: #ccc;")
                case _          => fail("expected disabled style")
        }

        "disabled extraction when absent" in {
            val s = Style.bg(Color.white)
            assert(disabledOf(s).isEmpty)
        }

        "baseProps excludes disabled" in {
            val s    = Style.bg(Color.hex("#fff").get).disabled(Style.bg(Color.hex("#ccc").get))
            val base = basePropsOf(s)
            assert(base.toCss == "background-color: #fff;")
            assert(disabledOf(base).isEmpty)
        }

        "baseProps excludes all pseudo-states together" in {
            val s = Style.bg(Color.hex("#fff").get)
                .hover(Style.bg(Color.hex("#eee").get))
                .focus(Style.bg(Color.hex("#ddd").get))
                .active(Style.bg(Color.hex("#ccc").get))
                .disabled(Style.bg(Color.hex("#bbb").get))
            val base = basePropsOf(s)
            assert(base.toCss == "background-color: #fff;")
            assert(hoverOf(base).isEmpty)
            assert(focusOf(base).isEmpty)
            assert(activeOf(base).isEmpty)
            assert(disabledOf(base).isEmpty)
        }

        "disabled with nested styles" in {
            val inner = Style.bg(Color.hex("#ccc").get).opacity(0.5).cursor(Cursor.notAllowed)
            val s     = Style.bg(Color.hex("#fff").get).disabled(inner)
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

        "display" in {
            assert(Style.display(Display.block).props(0) == Style.empty.display(Display.block).props(0))
            assert(Style.block.props(0) == Style.empty.block.props(0))
            assert(Style.inline.props(0) == Style.empty.inline.props(0))
            assert(Style.inlineBlock.props(0) == Style.empty.inlineBlock.props(0))
            assert(Style.listItem.props(0) == Style.empty.listItem.props(0))
        }

        "listStyle" in {
            assert(Style.listStyle(ListStyle.disc).props(0) == Style.empty.listStyle(ListStyle.disc).props(0))
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
            val a = Style.bgGradient(GradientDirection.toRight, (Color.hex("#fff").get, 0.pct), (Color.hex("#000").get, 100.pct))
            val b = Style.empty.bgGradient(GradientDirection.toRight, (Color.hex("#fff").get, 0.pct), (Color.hex("#000").get, 100.pct))
            val pa = a.props(0) match
                case p: Style.Prop.BgGradientProp => p
                case other                        => fail(s"Expected BgGradientProp, got ${other.getClass.getSimpleName}")
            val pb = b.props(0) match
                case p: Style.Prop.BgGradientProp => p
                case other                        => fail(s"Expected BgGradientProp, got ${other.getClass.getSimpleName}")
            assert(pa.direction == pb.direction)
            assert(pa.colors.size == pb.colors.size)
            assert(pa.positions.size == pb.positions.size)
        }

        "disabled" in {
            val inner = Style.bg(Color.hex("#ccc").get)
            assert(disabledOf(Style.disabled(inner)).nonEmpty)
        }
    }

    "UI integration" - {
        "style(Style) adds to Attrs" in {
            val s       = Style.bg(Color.hex("#fff").get).bold
            val el      = UI.div.style(s)("hello")
            val uiStyle = el.attrs.uiStyle
            assert(uiStyle.nonEmpty)
            assert(uiStyle.toCss.contains("background-color: #fff;"))
            assert(uiStyle.toCss.contains("font-weight: bold;"))
        }

        "style(Style) accumulates" in {
            val el      = UI.div.style(Style.bg(Color.white)).style(Style.bold)("hello")
            val uiStyle = el.attrs.uiStyle
            assert(uiStyle.toCss.contains("background-color"))
            assert(uiStyle.toCss.contains("font-weight"))
        }
    }

end StyleTest
