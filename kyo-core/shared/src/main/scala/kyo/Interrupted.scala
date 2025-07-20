package kyo

final case class Interrupted(at: Frame, by: Maybe[Text] = Absent)
    extends KyoException("Fiber interrupted at " + at.position.show + by.fold("")(" by " + _))(using at)
object Interrupted:
    def apply(at: Frame, by: Text): Interrupted = Interrupted(at, Present(by))
