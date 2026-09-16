package kyo

import java.time.Instant as JInstant
import java.time.format.DateTimeParseException
import kyo.internal.InstantPlatformSpecific
import kyo.internal.InstantText

/** A point in time with nanosecond precision, in the UTC time-scale: a number of seconds since the epoch of 1970-01-01T00:00:00Z and a
  * nanosecond adjustment.
  *
  * The range, the ISO-8601 text form of [[Instant.show]], and the inputs [[Instant.parse]] accepts are those of `java.time.Instant`, on
  * every platform, but no java.time implementation is involved: off the JVM a program that reads the clock or logs a timestamp links no
  * date-time library or locale data. [[Instant.fromJava]] and [[Instant.toJava]] convert at the boundary with code that needs
  * `java.time.Instant`.
  *
  * @see
  *   [[Duration]] for the amounts added to and subtracted from an Instant
  */
opaque type Instant = Instant.Repr

/** Companion object for Instant, providing factory methods and constants. */
object Instant:

    /** Seconds since the epoch and the nanosecond adjustment, `0 <= nanos < 1,000,000,000`. Its `toString` is the ISO-8601 form, so an
      * interpolated Instant reads as it always has.
      */
    final private[kyo] class Repr(val seconds: Long, val nanos: Int):
        override def equals(other: Any): Boolean =
            other match
                case that: Repr => seconds == that.seconds && nanos == that.nanos
                case _          => false
        override def hashCode: Int    = java.lang.Long.hashCode(seconds) + 51 * nanos
        override def toString: String = InstantText.format(seconds, nanos)
    end Repr

    private inline val NanosPerSecond = 1000000000L

    inline given CanEqual[Instant, Instant] = CanEqual.derived

    given Ordering[Instant] with
        def compare(x: Instant, y: Instant): Int = x.compareTo(y)

    /** The minimum supported Instant, `-1000000000-01-01T00:00:00Z`, as `java.time.Instant.MIN`. */
    val Min: Instant = new Repr(InstantText.MinSecond, 0)

    /** The maximum supported Instant, `+1000000000-12-31T23:59:59.999999999Z`, as `java.time.Instant.MAX`. */
    val Max: Instant = new Repr(InstantText.MaxSecond, 999999999)

    /** The Instant representing the epoch, `1970-01-01T00:00:00Z`. */
    val Epoch: Instant = new Repr(0L, 0)

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
        fromEpoch(seconds.toSeconds, nanos.toNanos)

    /** An Instant from a count of seconds from the epoch of 1970-01-01T00:00:00Z and a nanosecond adjustment.
      *
      * The inverse of [[epochSecond]] and [[nano]], and the constructor that can name a time before the epoch: [[of]] takes `Duration`s,
      * which are never negative. The adjustment may have any sign and is normalized, so `ofEpochSecond(1, -1)` is the same instant as
      * `ofEpochSecond(0, 999999999)`. The caller keeps the result within [[Min]] and [[Max]].
      *
      * @param epochSecond
      *   The number of seconds from the epoch, negative before it.
      * @param nano
      *   The nanosecond adjustment to that second.
      * @return
      *   An Instant instance.
      */
    def ofEpochSecond(epochSecond: Long, nano: Long = 0L): Instant = fromEpoch(epochSecond, nano)

    /** An Instant from a count of milliseconds from the epoch of 1970-01-01T00:00:00Z, negative before it.
      *
      * @param epochMilli
      *   The number of milliseconds from the epoch.
      * @return
      *   An Instant instance.
      */
    def ofEpochMilli(epochMilli: Long): Instant = fromEpochMilli(epochMilli)

    /** Parses an Instant from an ISO-8601 formatted string, accepting what `java.time.Instant.parse` accepts: `2011-12-03T10:15:30Z`, an
      * offset such as `+01:00` in place of `Z`, zero to nine fraction digits, and signed years beyond four digits.
      *
      * @param text
      *   The string to parse.
      * @return
      *   A Result containing either the parsed Instant or an error.
      */
    def parse(text: CharSequence): Result[DateTimeParseException, Instant] =
        InstantText.parse(text)

    /** Creates an Instant from a java.time.Instant.
      *
      * @param javaInstant
      *   The java.time.Instant to convert.
      * @return
      *   An Instant instance.
      */
    def fromJava(javaInstant: JInstant): Instant = new Repr(javaInstant.getEpochSecond, javaInstant.getNano)

    /** The value as an Instant when it is one, for code that dispatches on an untyped value's runtime class.
      *
      * `Instant` is opaque, so a type test written anywhere else cannot see through to the representation it erases
      * to, and a serializer handed an `Any` has nothing else to test.
      */
    private[kyo] def fromAny(value: Any): Maybe[Instant] =
        value match
            case repr: Repr => Present(repr)
            case _          => Absent

    /** An Instant from epoch seconds and a nanosecond adjustment of any sign, normalized as `java.time.Instant.ofEpochSecond` does. The
      * caller keeps the result inside the supported range.
      */
    private[kyo] def fromEpoch(seconds: Long, nanoAdjustment: Long): Instant =
        new Repr(seconds + Math.floorDiv(nanoAdjustment, NanosPerSecond), Math.floorMod(nanoAdjustment, NanosPerSecond).toInt)

    /** An Instant from milliseconds since the epoch. */
    private[kyo] def fromEpochMilli(millis: Long): Instant =
        new Repr(Math.floorDiv(millis, 1000L), Math.floorMod(millis, 1000L).toInt * 1000000)

    /** The current time of the platform clock, at the precision the platform provides. */
    private[kyo] def systemNow(): Instant = InstantPlatformSpecific.now()

    /** `this + nanos` as `java.time.Instant.plusNanos` computes it, or `Absent` where that would leave the supported range. */
    private def plusNanos(instant: Instant, nanos: Long): Maybe[Instant] =
        val seconds = instant.seconds + nanos / NanosPerSecond
        val result  = fromEpoch(seconds, instant.nanos + nanos % NanosPerSecond)
        if result.seconds < InstantText.MinSecond || result.seconds > InstantText.MaxSecond then Absent else Present(result)
    end plusNanos

    extension (instant: Instant)

        private def compareTo(other: Instant): Int =
            val cmp = java.lang.Long.compare(instant.seconds, other.seconds)
            if cmp != 0 then cmp else instant.nanos - other.nanos

        /** Adds a duration to this Instant, returning a new Instant.
          *
          * @param duration
          *   The duration to add.
          * @return
          *   A new Instant representing the result of the addition, or [[Max]] when it would pass it.
          */
        infix def +(duration: Duration): Instant =
            if duration == Duration.Zero then instant
            else if !duration.isFinite then Max
            else plusNanos(instant, duration.toNanos).getOrElse(Max)

        /** Subtracts a duration from this Instant, returning a new Instant.
          *
          * @param duration
          *   The duration to subtract.
          * @return
          *   A new Instant representing the result of the subtraction, or [[Min]] when it would pass it.
          */
        infix def -(duration: Duration): Instant =
            if duration == Duration.Zero then instant
            else if !duration.isFinite then Min
            else plusNanos(instant, -duration.toNanos).getOrElse(Min)

        /** Calculates the duration between this Instant and another.
          *
          * @param other
          *   The other Instant to calculate the duration to.
          * @return
          *   The duration between this Instant and the other.
          */
        infix def -(other: Instant): Duration =
            val seconds = instant.seconds - other.seconds
            val nanos   = instant.nanos - other.nanos
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
            val unitNanos = unit.nanosPerUnit
            if unitNanos == 1L then instant
            else
                val nanoOfDay = (instant.seconds % 86400L) * NanosPerSecond + instant.nanos
                val truncated = Math.floorDiv(nanoOfDay, unitNanos) * unitNanos
                // A whole day never truncates below Min, whose time of day is midnight, so the result is always in range.
                fromEpoch(instant.seconds, instant.nanos.toLong + (truncated - nanoOfDay))
            end if
        end truncatedTo

        /** Returns the minimum of this Instant and another.
          *
          * @param other
          *   The other Instant to compare with.
          * @return
          *   The earlier of the two Instants.
          */
        infix def min(other: Instant): Instant = if instant.compareTo(other) < 0 then instant else other

        /** Returns the maximum of this Instant and another.
          *
          * @param other
          *   The other Instant to compare with.
          * @return
          *   The later of the two Instants.
          */
        infix def max(other: Instant): Instant = if instant.compareTo(other) > 0 then instant else other

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

        /** Converts this Instant to a human-readable ISO-8601 formatted string, the same text as `java.time.Instant.toString`.
          *
          * @return
          *   A string representation of this Instant in ISO-8601 format.
          */
        def show: String = InstantText.format(instant.seconds, instant.nanos)

        /** The number of whole seconds from the epoch of 1970-01-01T00:00:00Z, negative before it.
          *
          * With [[nano]], the pair that names this instant exactly. They are what a codec writing a timestamp needs,
          * and reaching them through [[toJava]] would make that codec carry a date-time library.
          *
          * @return
          *   The epoch second.
          */
        def epochSecond: Long = instant.seconds

        /** The nanosecond of the second, always in `[0, 999999999]`, counted forward even before the epoch.
          *
          * @return
          *   The nanosecond adjustment to [[epochSecond]].
          */
        def nano: Int = instant.nanos

        /** The number of milliseconds from the epoch of 1970-01-01T00:00:00Z, negative before it.
          *
          * Any sub-millisecond part is dropped. Throws `ArithmeticException` for an instant whose millisecond count
          * does not fit a `Long`, which the far ends of the supported range do not, as `java.time.Instant` does.
          *
          * @return
          *   The epoch millisecond.
          */
        def toEpochMilli: Long =
            Math.addExact(Math.multiplyExact(instant.seconds, 1000L), (instant.nanos / 1000000).toLong)

        /** Converts this Instant to a java.time.Instant.
          *
          * @return
          *   The equivalent java.time.Instant.
          */
        def toJava: JInstant =
            // The range ends convert to the JDK's own constants, as the epoch does inside ofEpochSecond.
            if instant == Min then JInstant.MIN
            else if instant == Max then JInstant.MAX
            else JInstant.ofEpochSecond(instant.seconds, instant.nanos.toLong)

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

    /** Parses an ISO-8601 formatted string into an Instant. Besides what [[Instant.parse]] accepts, a time may stop at the minutes
      * (`2024-01-01T10:15+01:00`), as `java.time.OffsetDateTime.parse` allows.
      */
    given Flag.Reader.Scalar[Instant] with
        def apply(s: String): Either[Throwable, Instant] =
            val trimmed = s.trim
            InstantText.parse(trimmed) match
                case Result.Success(instant) => Right(instant)
                case primary =>
                    InstantText.parse(trimmed, secondsOptional = true) match
                        case Result.Success(instant) => Right(instant)
                        case _ =>
                            Left(new IllegalArgumentException(s"Invalid Instant format: $s", primary.failure.getOrElse(null)))
            end match
        end apply

        def typeName: String = "Instant"
    end given

end Instant
