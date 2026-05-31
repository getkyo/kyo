package kyo

final case class Interrupted(at: Frame, by: Maybe[String] = Absent)
    extends KyoException("Fiber interrupted at " + at.position.show + by.fold("")(" by " + _))(using at)
object Interrupted:
    def apply(at: Frame, by: String): Interrupted = Interrupted(at, Present(by))
