package kyo.internal.tui2.widget

import kyo.*
import kyo.discard
import kyo.internal.tui2.*

/** File input widget — renders a label and opens OS file picker on activation. */
private[kyo] object FileInputW:

    private val DefaultLabel = "[Choose File]"

    def render(fi: UI.FileInput, cx: Int, cy: Int, cw: Int, ch: Int, rs: ResolvedStyle, ctx: RenderCtx): Unit =
        val style    = rs.cellStyle
        val selected = ctx.fileInputPaths.get(fi)
        val label =
            if selected != null && selected.nonEmpty then
                val sep  = selected.lastIndexOf('/')
                val name = if sep >= 0 then selected.substring(sep + 1) else selected
                s"[$name]"
            else DefaultLabel
        discard(ctx.canvas.drawString(cx, cy, cw, label, 0, style))
    end render

    def handleKey(fi: UI.FileInput, event: InputEvent.Key, ctx: RenderCtx)(
        using
        Frame,
        AllowUnsafe
    ): Boolean =
        if event.key == UI.Keyboard.Enter || event.key == UI.Keyboard.Space then
            if !ValueResolver.resolveBoolean(fi.disabled, ctx.signals) then
                ctx.terminal.foreach { terminal =>
                    terminal.suspend()
                    val result = PlatformCmd.openFilePicker(fi.accept.getOrElse(""))
                    terminal.resume()
                    ctx.screen.invalidate()
                    result.foreach { path =>
                        ctx.fileInputPaths.put(fi, path)
                        fi.onChange.foreach(f => ValueResolver.runHandler(f(path)))
                    }
                }
            end if
            true
        else false

end FileInputW
