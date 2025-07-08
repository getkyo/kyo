package kyo

final case class Interrupted(at: Frame, message: Text = "")
    extends KyoException(message + " Fiber interrupted at " + at.position.show)(using at)
