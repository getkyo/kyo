package kyo.internal.tui2

import kyo.*

/** Widget trait — bundles measurement, rendering, and input handling. Leaf widgets implement measurement so the layout engine can see their
  * content. The compiler enforces render; measurement/handling have sensible defaults.
  *
  * Activation model: `handleClick` is the single activation method. It is called automatically by the default `handleKey` (for Space/Enter)
  * and the default `handleMouse` (for LeftPress). Widgets that have activation behavior (checkbox toggle, button press, link open) override
  * `handleClick`. Widgets that need position-specific mouse behavior (cursor placement, drag) override `handleMouse` and call
  * `super.handleMouse` or `handleClick` as needed.
  */
private[kyo] trait Widget:
    /** Content width without insets. Return -1 for children-based measurement. */
    def measureWidth(elem: UI.Element, availW: Int, ctx: RenderCtx)(using Frame, AllowUnsafe): Int = -1

    /** Content height without insets. Return -1 for children-based measurement. */
    def measureHeight(elem: UI.Element, availW: Int, ctx: RenderCtx)(using Frame, AllowUnsafe): Int = -1

    /** Extra width added to children measurement (e.g. list markers). */
    def extraWidth(elem: UI.Element): Int = 0

    /** Whether this widget controls its own chrome (border/bg). */
    def selfRendered: Boolean = false

    /** Whether this widget accepts text input (Space becomes printable char). */
    def acceptsTextInput: Boolean = false

    /** Render content into the content rect. */
    def render(elem: UI.Element, cx: Int, cy: Int, cw: Int, ch: Int, rs: ResolvedStyle, ctx: RenderCtx)(
        using
        Frame,
        AllowUnsafe
    ): Unit

    /** Handle activation (click or Space/Enter). Return true if consumed. */
    def handleClick(elem: UI.Element, ctx: RenderCtx)(using Frame, AllowUnsafe): Boolean = false

    /** Handle key event. Default: delegates Space/Enter to handleClick (unless acceptsTextInput). */
    def handleKey(elem: UI.Element, event: InputEvent.Key, ctx: RenderCtx)(using Frame, AllowUnsafe): Boolean =
        event.key match
            case UI.Keyboard.Enter | UI.Keyboard.Space if !acceptsTextInput =>
                handleClick(elem, ctx)
            case _ => false

    /** Handle paste event. Return true if consumed. */
    def handlePaste(elem: UI.Element, paste: String, ctx: RenderCtx)(using Frame, AllowUnsafe): Boolean = false

    /** Handle mouse event (click, drag). Default: delegates LeftPress to handleClick. */
    def handleMouse(elem: UI.Element, kind: InputEvent.MouseKind, mx: Int, my: Int, ctx: RenderCtx)(
        using
        Frame,
        AllowUnsafe
    ): Boolean =
        kind match
            case InputEvent.MouseKind.LeftPress => handleClick(elem, ctx)
            case _                              => false
end Widget
