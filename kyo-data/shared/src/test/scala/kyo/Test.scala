package kyo

import org.scalatest.NonImplicitAssertions
import org.scalatest.freespec.AsyncFreeSpec
import scala.util.Try

abstract class Test extends AsyncFreeSpec with NonImplicitAssertions:
    given tryCanEqual[A]: CanEqual[Try[A], Try[A]]                   = CanEqual.derived
    given eitherCanEqual[A, B]: CanEqual[Either[A, B], Either[A, B]] = CanEqual.derived
    given throwableCanEqual: CanEqual[Throwable, Throwable]          = CanEqual.derived
end Test
