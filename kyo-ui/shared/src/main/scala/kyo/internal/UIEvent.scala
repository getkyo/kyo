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
    // A host-originated raycast pick (the 3D back-channel, FORK B): the client raycasts on its
    // live scene and sends this typed event; the server routes it to the host's registered
    // server-side handler. PointerData is the FFI-free wire form of a three.js pointer hit.
    case HostPick(path: Seq[String], nodeId: String, pointer: PointerData)
end UIEvent
