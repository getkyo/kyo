package kyo

import java.time.DateTimeException
import java.time.Instant as JInstant
import java.time.format.DateTimeParseException

/** Represents a point in time with nanosecond precision by wrapping 'java.time.Instant'.
  *
  * An Instant is an immutable representation of a timestamp in the UTC time-scale, stored as a number of seconds and nanoseconds since the
  * epoch of 1970-01-01T00:00:00Z.
  */
opaque type Instant = JInstant

/** Companion object for Instant, providing factory methods and constants. */
object Instant:

    inline given CanEqual[Instant, Instant] = CanEqual.derived
    inline given Flat[Instant]              = Flat.unsafe.bypass

    given Ordering[Instant] with
        def compare(x: Instant, y: Instant): Int = x.compareTo(y)

    /** The minimum supported Instant, 'java.time.Instant.MIN'. */
    val Min: Instant = JInstant.MIN

    /** The maximum supported Instant, 'java.time.Instant.MAX'. */
    val Max: Instant = JInstant.MAX

    /** The Instant representing the epoch, 'java.time.Instant.EPOCH'. */
    val Epoch: Instant = JInstant.EPOCH

    /** Creates an Instant from two Durations: one representing seconds and another representing nanoseconds.
      *
      * @param seconds
      *   The number of seconds from the epoch of 1970-01-01T00:00:00Z.
      * @param nanos
      *   The nanosecond adjustment to the number of seconds, from 0 to 999,999,999.
      * @return
      *   An Instant instance.
      */
    def of(seconds: Duration, nanos: Duration): Instant =
        JInstant.ofEpochSecond(seconds.toSeconds, nanos.toNanos)

    /** Parses an Instant from an ISO-8601 formatted string.
      *
      * @param text
      *   The string to parse.
      * @return
      *   A Result containing either the parsed Instant or an error.
      */
    def parse(text: CharSequence): Result[DateTimeParseException, Instant] =
        Result.catching[DateTimeParseException] {
            JInstant.parse(text)
        }

    /** Creates an Instant from a java.time.Instant.
      *
      * @param javaInstant
      *   The java.time.Instant to convert.
      * @return
      *   An Instant instance.
      */
    def fromJava(javaInstant: JInstant): Instant = javaInstant

    extension (instant: Instant)

        /** Adds a duration to this Instant, returning a new Instant.
          *
          * @param duration
          *   The duration to add.
          * @return
          *   A new Instant representing the result of the addition.
          */
        infix def +(duration: Duration): Instant =
            if duration == Duration.Zero then instant
            else if !duration.isFinite then Max
            else
                try instant.plusNanos(duration.toNanos)
                catch
                    case (_: DateTimeException | _: ArithmeticException) =>
                        Max

        /** Subtracts a duration from this Instant, returning a new Instant.
          *
          * @param duration
          *   The duration to subtract.
          * @return
          *   A new Instant representing the result of the subtraction.
          */
        infix def -(duration: Duration): Instant =
            if duration == Duration.Zero then instant
            else if !duration.isFinite then Min
            else
                try instant.minusNanos(duration.toNanos)
                catch
                    case (_: DateTimeException | _: ArithmeticException) =>
                        Min

        /** Calculates the duration between this Instant and another.
          *
          * @param other
          *   The other Instant to calculate the duration to.
          * @return
          *   The duration between this Instant and the other.
          */
        infix def -(other: Instant): Duration =
            val seconds = instant.getEpochSecond - other.getEpochSecond
            val nanos   = instant.getNano - other.getNano
            if seconds == Long.MaxValue || seconds == Long.MinValue then Duration.Infinity
            else Duration.fromNanos(seconds.seconds.toNanos + nanos)
        end -

        /** Checks if this Instant is after another.
          *
          * @param other
          *   The other Instant to compare to.
          * @return
          *   true if this Instant is after the other, false otherwise.
          */
        infix def >(other: Instant): Boolean = instant.compareTo(other) > 0

        /** Checks if this Instant is after or equal to another.
          *
          * @param other
          *   The other Instant to compare to.
          * @return
          *   true if this Instant is after or equal to the other, false otherwise.
          */
        infix def >=(other: Instant): Boolean = instant.compareTo(other) >= 0

        /** Checks if this Instant is before another.
          *
          * @param other
          *   The other Instant to compare to.
          * @return
          *   true if this Instant is before the other, false otherwise.
          */
        infix def <(other: Instant): Boolean = instant.compareTo(other) < 0

        /** Checks if this Instant is before or equal to another.
          *
          * @param other
          *   The other Instant to compare to.
          * @return
          *   true if this Instant is before or equal to the other, false otherwise.
          */
        infix def <=(other: Instant): Boolean = instant.compareTo(other) <= 0

        /** Returns this instant truncated to the specified unit.
          *
          * @param unit
          *   The unit to truncate to.
          * @return
          *   A new Instant truncated to the specified unit.
          */
        def truncatedTo(unit: Duration.Units & Duration.Truncatable): Instant =
            instant.truncatedTo(unit.chronoUnit)

        /** Returns the minimum of this Instant and another.
          *
          * @param other
          *   The other Instant to compare with.
          * @return
          *   The earlier of the two Instants.
          */
        infix def min(other: Instant): Instant = if instant.isBefore(other) then instant else other

        /** Returns the maximum of this Instant and another.
          *
          * @param other
          *   The other Instant to compare with.
          * @return
          *   The later of the two Instants.
          */
        infix def max(other: Instant): Instant = if instant.isAfter(other) then instant else other

        /** Returns true if this Instant is between two other Instants (inclusive).
          *
          * @param start
          *   The start Instant
          * @param end
          *   The end Instant
          * @return
          *   true if this Instant is between start and end (inclusive)
          */
        def between(start: Instant, end: Instant): Boolean =
            (instant >= start) && (instant <= end)

        /** Clamps this Instant between two bounds.
          *
          * @param min
          *   The lower bound
          * @param max
          *   The upper bound
          * @return
          *   An Instant clamped between min and max
          */
        def clamp(min: Instant, max: Instant): Instant =
            instant.max(min).min(max)

        /** Converts this Instant to a human-readable ISO-8601 formatted string.
          *
          * @return
          *   A string representation of this Instant in ISO-8601 format.
          */
        def show: String = instant.toString

        /** Converts this Instant to a java.time.Instant.
          *
          * @return
          *   The equivalent java.time.Instant.
          */
        def toJava: JInstant = instant

        /** Converts this Instant to a Duration representing the time elapsed since the epoch (1970-01-01T00:00:00Z).
          *
          * @return
          *   The Duration since the epoch.
          */
        def toDuration: Duration =
            if instant == Max then Duration.Infinity
            else if instant == Min then Duration.Zero
            else instant - Epoch

    end extension

end Instant
