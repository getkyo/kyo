package kyo.internal

import kyo.*
import kyo.UI.*
import kyo.UI.Ast.*

/** Typed event handler that carries the closure. Server-side only (never serialized).
  *
  * Design: sealed trait with case classes (not enum) because each case carries a distinct closure type (e.g. `String => Unit < Async` for
  * Input, `Boolean => Unit < Async` for ChangeChecked). Scala enums do not support case-class members with parametric fields. Naming
  * follows the event type (Click, Input, Change, ...) rather than "OnClick" etc. so callers read naturally as `EventHandler.Click(f)`.
  */
sealed private[kyo] trait EventHandler derives CanEqual

private[kyo] object EventHandler:
    case class Click(f: Unit < Async)                       extends EventHandler
    case class ClickSelf(f: Unit < Async)                   extends EventHandler
    case class Input(f: String => Unit < Async)             extends EventHandler
    case class Change(f: String => Unit < Async)            extends EventHandler
    case class ChangeChecked(f: Boolean => Unit < Async)    extends EventHandler
    case class ChangeNumeric(f: Double => Unit < Async)     extends EventHandler
    case class Submit(f: Unit < Async)                      extends EventHandler
    case class KeyDown(f: UI.KeyboardEvent => Unit < Async) extends EventHandler
    case class KeyUp(f: UI.KeyboardEvent => Unit < Async)   extends EventHandler
    case class Focus(f: Unit < Async)                       extends EventHandler
    case class Blur(f: Unit < Async)                        extends EventHandler

    /** The event type string used in data-kyo-ev attributes and handler map keys. */
    def eventType(handler: EventHandler): String = handler match
        case _: Click         => "click"
        case _: ClickSelf     => "clickself"
        case _: Input         => "input"
        case _: Change        => "change"
        case _: ChangeChecked => "change"
        case _: ChangeNumeric => "change"
        case _: Submit        => "submit"
        case _: KeyDown       => "keydown"
        case _: KeyUp         => "keyup"
        case _: Focus         => "focus"
        case _: Blur          => "blur"

end EventHandler
