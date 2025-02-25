package kyo.test

import java.time.Duration as JDuration
import java.time.Instant
import kyo.*
import scala.concurrent.duration.*

sealed trait Duration:
    self =>
    import Duration.*

    final def <>(that: Duration): Duration = (self, that) match
        case (Zero, right) => right
        case (left, Zero)  => left
        case (Finite(leftStart, leftEnd), Finite(rightStart, rightEnd)) =>
            val start = if leftStart.isBefore(rightStart) then leftStart else rightStart
            val end   = if leftEnd.isAfter(rightEnd) then leftEnd else rightEnd
            Finite(start, end)

    final def isZero: Boolean = toDuration.isZero

    final def render: String = s"${toDuration.toMillis}ms"

    // Convert to a java.time.Duration for effectful duration operations
    final def toDuration: JDuration = self match
        case Zero               => JDuration.ZERO
        case Finite(start, end) => JDuration.between(start, end)

    final def toMillis: Long = toDuration.toMillis

    final def toNanos: Long = toDuration.toNanos
end Duration

object Duration:
    private case class Finite(start: Instant, end: Instant) extends Duration
    private case object Zero                                extends Duration

    def fromInterval(start: Instant, end: Instant): Duration = Finite(start, end)
    val zero: Duration                                       = Zero
end Duration

object TestDuration:
    def timeout(duration: Duration): String < (Env[Any] & IO) =
        // Pure computation: concatenating a string is pure and automatically promoted
        "Timeout reached: " + duration.toMillis + "ms"

    def printDuration(duration: Duration): Unit < IO =
        // Wrap the side-effecting println in a suspended effect
        Console.print(s"Duration: ${duration.toMillis}ms\n")
end TestDuration
