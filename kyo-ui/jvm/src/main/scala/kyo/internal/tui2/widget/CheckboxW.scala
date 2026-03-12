package kyo.internal.tui2.widget

import kyo.*
import kyo.discard
import kyo.internal.tui2.*

/** Checkbox and radio button widget rendering and activation. */
private[kyo] object CheckboxW:

    private val checkboxChecked   = "[x]"
    private val checkboxUnchecked = "[ ]"
    private val radioChecked      = "(*)"
    private val radioUnchecked    = "( )"

    def render(
        bi: UI.BooleanInput,
        cx: Int,
        cy: Int,
        cw: Int,
        ch: Int,
        rs: ResolvedStyle,
        ctx: RenderCtx
    )(using Frame, AllowUnsafe): Unit =
        val checked = ValueResolver.resolveBoolean(bi.checked, ctx.signals)
        val mark = bi match
            case _: UI.Checkbox => if checked then checkboxChecked else checkboxUnchecked
            case _: UI.Radio    => if checked then radioChecked else radioUnchecked
        discard(ctx.canvas.drawString(cx, cy, cw, mark, 0, rs.cellStyle))
    end render

    /** Toggle the checkbox/radio. Called by handleClick (shared by keyboard and mouse). */
    def toggle(
        bi: UI.BooleanInput,
        ctx: RenderCtx
    )(using Frame, AllowUnsafe): Boolean =
        bi match
            case radio: UI.Radio if radio.name.nonEmpty =>
                val v = !ValueResolver.resolveBoolean(radio.checked, ctx.signals)
                if v then
                    ctx.focus.forEachFocusable {
                        case otherRadio: UI.Radio
                            if (otherRadio ne radio) && otherRadio.name == radio.name =>
                            ValueResolver.setBoolean(otherRadio.checked, false)
                        case _ => ()
                    }
                end if
                ValueResolver.setBoolean(radio.checked, v)
                radio.onChange.foreach(f => ValueResolver.runHandler(f(v)))
            case _ =>
                val v = !ValueResolver.resolveBoolean(bi.checked, ctx.signals)
                ValueResolver.setBoolean(bi.checked, v)
                bi.onChange.foreach(f => ValueResolver.runHandler(f(v)))
        end match
        true
    end toggle

end CheckboxW
