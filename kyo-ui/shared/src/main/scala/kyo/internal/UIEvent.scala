package kyo.internal

import kyo.*

/** Mouse event payload on the wire. Reconstructed into UI.MouseEvent on the server. */
final private[kyo] case class MouseEventData(
    modifiers: UI.Modifiers,
    targetId: Maybe[String]
) derives CanEqual, Schema

/** Keyboard event payload on the wire. Reconstructed into UI.KeyboardEvent on the server. */
final private[kyo] case class KeyboardEventData(
    key: String,
    modifiers: UI.Modifiers,
    targetId: Maybe[String]
) derives CanEqual, Schema

/** Client -> server event. Typed per event kind. */
private[kyo] enum UIEvent derives CanEqual, Schema:
    def path: Seq[String]
    case Click(path: Seq[String], mouse: MouseEventData)
    case ClickSelf(path: Seq[String], mouse: MouseEventData)
    case Input(path: Seq[String], value: String)
    case Change(path: Seq[String], value: String)
    case ChangeChecked(path: Seq[String], checked: Boolean)
    case ChangeNumeric(path: Seq[String], value: Double)
    case Submit(path: Seq[String], mouse: MouseEventData)
    case KeyDown(path: Seq[String], keyboard: KeyboardEventData)
    case KeyUp(path: Seq[String], keyboard: KeyboardEventData)
    case Focus(path: Seq[String], mouse: MouseEventData)
    case Blur(path: Seq[String], mouse: MouseEventData)
    case Scroll(path: Seq[String], deltaX: Double, deltaY: Double, modifiers: UI.Modifiers, targetId: Maybe[String])
    case Hover(path: Seq[String], mouse: MouseEventData)
    case Unhover(path: Seq[String], mouse: MouseEventData)
    // pointer/drag events; `pointer` (UI.PointerEvent) rides the wire directly like UI.FilePayload. See UI.PointerEvent.
    case PointerDown(path: Seq[String], pointer: UI.PointerEvent)
    case PointerMove(path: Seq[String], pointer: UI.PointerEvent)
    case PointerUp(path: Seq[String], pointer: UI.PointerEvent)
    // Client's reply to HtmlOp.RequestMeasure; the transport routes it to UI.Commands (resolving a pending
    // requestMeasure), not the element handler tree.
    case Measure(
        path: Seq[String],
        rectX: Double,
        rectY: Double,
        rectW: Double,
        rectH: Double,
        viewportW: Double,
        viewportH: Double
    )
    // Self-addressing reply to HtmlOp.RequestMeasureById; routed by `id` to deliverMeasureById. `path` exists only to
    // satisfy the enum's abstract member (never read; the client sends it empty).
    case MeasureById(
        path: Seq[String],
        id: String,
        rectX: Double,
        rectY: Double,
        rectW: Double,
        rectH: Double,
        viewportW: Double,
        viewportH: Double
    )
end UIEvent
