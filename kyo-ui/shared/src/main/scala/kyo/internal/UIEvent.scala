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
    // The client->server typed app-event back-channel (design 02-design-r2 D-003): a client
    // onClick (running locally on the live scene) calls `Three.Feed.emit[A](eventId, event)`, which posts
    // this variant carrying the `Json.encode`d typed event `A` as an opaque string under `eventId`. The
    // server routes it to the host's registered app-event handler by `eventId`, which decodes with the
    // same `Schema[A]` and reflects it into a server-owned fed signal. `path` is the host path (unused for
    // routing, which is by `eventId`); the `encoded` string keeps the event total for any `A: Schema`.
    case AppEvent(path: Seq[String], eventId: String, encoded: String)
end UIEvent
