package kyo

object JsFacadeGivens:
    given Frame = Frame.internal
    given [A, B]: scala.CanEqual[A, B] = scala.CanEqual.derived
