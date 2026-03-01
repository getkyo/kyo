package kyo

import kyo.Maybe.*
import kyo.Style.pct
import kyo.Style.px
import kyo.internal.TuiColor
import kyo.internal.TuiLayout
import kyo.internal.TuiLayout.*
import kyo.internal.TuiStyle

class TuiStyleTest extends Test:

    private def layout(): (TuiLayout, Int) =
        val l   = new TuiLayout(16)
        val idx = l.alloc()
        (l, idx)
    end layout

    "setDefaults" - {
        "initializes all fields" in {
            val (l, idx) = layout()
            TuiStyle.setDefaults(l, idx)
            assert(l.lFlags(idx) == 0)
            assert(l.pFlags(idx) == 0)
            assert(l.padT(idx) == 0)
            assert(l.fg(idx) == TuiColor.Absent)
            assert(l.bg(idx) == TuiColor.Absent)
            assert(l.sizeW(idx) == -1)
            assert(l.opac(idx) == 1.0f)
            assert(l.text(idx) == Absent)
        }
    }

    "resolve" - {
        "bg and fg color" in {
            val (l, idx) = layout()
            val style    = Style.bg(Style.Color.rgb(255, 0, 0)).color(Style.Color.rgb(0, 255, 0))
            TuiStyle.resolve(style, l, idx, 80, 24)
            assert(l.bg(idx) == TuiColor.pack(255, 0, 0))
            assert(l.fg(idx) == TuiColor.pack(0, 255, 0))
        }

        "padding" in {
            val (l, idx) = layout()
            TuiStyle.resolve(Style.padding(1, 2, 3, 4), l, idx, 80, 24)
            assert(l.padT(idx) == 1)
            assert(l.padR(idx) == 2)
            assert(l.padB(idx) == 3)
            assert(l.padL(idx) == 4)
        }

        "margin" in {
            val (l, idx) = layout()
            TuiStyle.resolve(Style.margin(5, 10, 15, 20), l, idx, 80, 24)
            assert(l.marT(idx) == 5)
            assert(l.marR(idx) == 10)
            assert(l.marB(idx) == 15)
            assert(l.marL(idx) == 20)
        }

        "width and height" in {
            val (l, idx) = layout()
            TuiStyle.resolve(Style.width(30).height(10), l, idx, 80, 24)
            assert(l.sizeW(idx) == 30)
            assert(l.sizeH(idx) == 10)
        }

        "percentage width" in {
            val (l, idx) = layout()
            TuiStyle.resolve(Style.width(50.pct), l, idx, 80, 24)
            assert(l.sizeW(idx) == 40)
        }

        "auto width remains -1" in {
            val (l, idx) = layout()
            TuiStyle.resolve(Style.width(Style.Size.auto), l, idx, 80, 24)
            assert(l.sizeW(idx) == -1)
        }

        "min/max constraints" in {
            val (l, idx) = layout()
            TuiStyle.resolve(Style.minWidth(10).maxWidth(50).minHeight(5).maxHeight(20), l, idx, 80, 24)
            assert(l.minW(idx) == 10)
            assert(l.maxW(idx) == 50)
            assert(l.minH(idx) == 5)
            assert(l.maxH(idx) == 20)
        }

        "gap" in {
            val (l, idx) = layout()
            TuiStyle.resolve(Style.gap(3), l, idx, 80, 24)
            assert(l.gap(idx) == 3)
        }

        "align center" in {
            val (l, idx) = layout()
            TuiStyle.resolve(Style.align(Style.Alignment.center), l, idx, 80, 24)
            assert(TuiLayout.align(l.lFlags(idx)) == AlignCenter)
        }

        "justify space-between" in {
            val (l, idx) = layout()
            TuiStyle.resolve(Style.justify(Style.Justification.spaceBetween), l, idx, 80, 24)
            assert(TuiLayout.justify(l.lFlags(idx)) == JustBetween)
        }

        "overflow scroll" in {
            val (l, idx) = layout()
            TuiStyle.resolve(Style.overflow(Style.Overflow.scroll), l, idx, 80, 24)
            assert(TuiLayout.overflow(l.lFlags(idx)) == 2)
        }

        "bold" in {
            val (l, idx) = layout()
            TuiStyle.resolve(Style.bold, l, idx, 80, 24)
            assert(TuiLayout.isBold(l.pFlags(idx)))
        }

        "italic" in {
            val (l, idx) = layout()
            TuiStyle.resolve(Style.italic, l, idx, 80, 24)
            assert(TuiLayout.isItalic(l.pFlags(idx)))
        }

        "underline" in {
            val (l, idx) = layout()
            TuiStyle.resolve(Style.underline, l, idx, 80, 24)
            assert(TuiLayout.isUnderline(l.pFlags(idx)))
        }

        "strikethrough" in {
            val (l, idx) = layout()
            TuiStyle.resolve(Style.strikethrough, l, idx, 80, 24)
            assert(TuiLayout.isStrikethrough(l.pFlags(idx)))
        }

        "font weight dim" in {
            val (l, idx) = layout()
            TuiStyle.resolve(Style.fontWeight(Style.FontWeight.w200), l, idx, 80, 24)
            assert(TuiLayout.isDim(l.pFlags(idx)))
        }

        "text align center" in {
            val (l, idx) = layout()
            TuiStyle.resolve(Style.textAlign(Style.TextAlign.center), l, idx, 80, 24)
            assert(TuiLayout.textAlign(l.pFlags(idx)) == TextAlignCenter)
        }

        "text overflow ellipsis" in {
            val (l, idx) = layout()
            TuiStyle.resolve(Style.textOverflow(Style.TextOverflow.ellipsis), l, idx, 80, 24)
            assert(TuiLayout.hasTextOverflow(l.pFlags(idx)))
        }

        "text transform uppercase" in {
            val (l, idx) = layout()
            TuiStyle.resolve(Style.textTransform(Style.TextTransform.uppercase), l, idx, 80, 24)
            assert(TuiLayout.textTrans(l.pFlags(idx)) == 1)
        }

        "border style dashed" in {
            val (l, idx) = layout()
            TuiStyle.resolve(Style.borderStyle(Style.BorderStyle.dashed), l, idx, 80, 24)
            assert(TuiLayout.borderStyle(l.pFlags(idx)) == BorderDashed)
        }

        "border radius" in {
            val (l, idx) = layout()
            TuiStyle.resolve(Style.rounded(5), l, idx, 80, 24)
            assert(TuiLayout.isRoundedTL(l.pFlags(idx)))
            assert(TuiLayout.isRoundedTR(l.pFlags(idx)))
            assert(TuiLayout.isRoundedBR(l.pFlags(idx)))
            assert(TuiLayout.isRoundedBL(l.pFlags(idx)))
        }

        "opacity" in {
            val (l, idx) = layout()
            TuiStyle.resolve(Style.opacity(0.5), l, idx, 80, 24)
            assert(l.opac(idx) == 0.5f)
        }

        "translate" in {
            val (l, idx) = layout()
            TuiStyle.resolve(Style.translate(3.px, 5.px), l, idx, 80, 24)
            assert(l.transX(idx) == 3)
            assert(l.transY(idx) == 5)
        }

        "focus style stored" in {
            val (l, idx) = layout()
            TuiStyle.resolve(Style.focus(Style.bold), l, idx, 80, 24)
            assert(l.focusStyle(idx).isDefined)
        }

        "active style stored" in {
            val (l, idx) = layout()
            TuiStyle.resolve(Style.active(Style.italic), l, idx, 80, 24)
            assert(l.activeStyle(idx).isDefined)
        }

        "combined style" in {
            val (l, idx) = layout()
            TuiStyle.resolve(Style.bg("#ff0000").color("#00ff00").bold.padding(1).width(40), l, idx, 80, 24)
            assert(l.bg(idx) == TuiColor.pack(255, 0, 0))
            assert(l.fg(idx) == TuiColor.pack(0, 255, 0))
            assert(TuiLayout.isBold(l.pFlags(idx)))
            assert(l.padT(idx) == 1)
            assert(l.sizeW(idx) == 40)
        }

        "no-op props don't crash" in {
            val (l, idx) = layout()
            TuiStyle.resolve(Style.fontFamily("monospace").cursor(Style.Cursor.pointer), l, idx, 80, 24)
            succeed
        }
    }

end TuiStyleTest
