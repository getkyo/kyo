package zio.test

import java.time.Instant
import zio.*

sealed trait TestDuration:
    self =>
    import TestDuration.*

    final def <>(that: TestDuration): TestDuration =
        (self, that) match
            case (Zero, right) => right
            case (left, Zero)  => left
            case (Finite(leftStart, leftEnd), Finite(rightStart, rightEnd)) =>
                val start = if leftStart.isBefore(rightStart) then leftStart else rightStart
                val end   = if leftEnd.isAfter(rightEnd) then leftEnd else rightEnd
                Finite(start, end)

    final def isZero: Boolean =
        toDuration.isZero

    final def render: String =
        toDuration.render

    final def toDuration: Duration =
        self match
            case Zero               => Duration.Zero
            case Finite(start, end) => Duration.fromInterval(start, end)

    final def toMillis: Long =
        toDuration.toMillis

    final def toNanos: Long =
        toDuration.toNanos
end TestDuration

object TestDuration:

    final private case class Finite(start: Instant, end: Instant) extends TestDuration
    private case object Zero                                      extends TestDuration

    def fromInterval(start: Instant, end: Instant): TestDuration =
        Finite(start, end)

    val zero: TestDuration =
        Zero
end TestDuration
