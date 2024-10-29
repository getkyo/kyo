package kyo

import java.time.Duration as JavaDuration
import java.time.temporal.ChronoUnit
import java.time.temporal.ChronoUnit.*
import kyo.Duration.Units
import kyo.Duration.Units.*
import scala.concurrent.duration.Duration as ScalaDuration

/** Represents a duration of time. */
type Duration = Duration.Value

/** Companion object for Duration type. */
object Duration:

    opaque type Value = Long

    given CanEqual[Duration, Duration] = CanEqual.derived

    /** Exception thrown for invalid duration parsing. */
    case class InvalidDuration(message: String) extends Exception(message)

    /** Parses a string representation of a duration.
      *
      * @param s
      *   The string to parse
      * @return
      *   A Result containing either the parsed Duration or an InvalidDuration error
      */
    def parse(s: String): Result[InvalidDuration, Duration] =
        val pattern = """(\d+)\s*([a-zA-Z]+)""".r
        s.trim.toLowerCase match
            case "infinity" | "inf" => Result.success(Infinity)
            case pattern(value, unit) =>
                for
                    longValue <-
                        Result.catching[NumberFormatException](value.toLong)
                            .mapFail(_ => InvalidDuration(s"Invalid number: $value"))
                    unitEnum <-
                        Units.values.find(_.names.exists(_.startsWith(unit)))
                            .map(Result.success)
                            .getOrElse(Result.fail(InvalidDuration(s"Invalid unit: $unit")))
                yield fromUnits(longValue, unitEnum)
            case _ => Result.fail(InvalidDuration(s"Invalid duration format: $s"))
        end match
    end parse

    /** Represents zero duration. */
    val Zero: Duration = 0L

    /** Represents infinite duration. */
    val Infinity: Duration = Long.MaxValue

    /** Creates a Duration from nanoseconds.
      *
      * @param value
      *   The number of nanoseconds
      * @return
      *   A Duration instance
      */
    inline def fromNanos(value: Long): Duration =
        if value <= 0 then Duration.Zero else value

    /** Creates a Duration from a value and unit.
      *
      * @param value
      *   The numeric value
      * @param unit
      *   The unit of time
      * @return
      *   A Duration instance
      */
    def fromUnits(value: Long, unit: Units): Duration =
        if value <= 0 then Duration.Zero else Duration.*(value)(unit.factor).min(Infinity)

    /** Converts a Java Duration to a Duration.
      *
      * @param value
      *   The Java Duration to convert
      * @return
      *   A Duration instance
      */
    def fromJava(value: JavaDuration): Duration =
        (value.toNanos: @annotation.switch) match
            case 0                       => Zero
            case n if n >= Long.MaxValue => Infinity
            case n                       => n.nanos

    /** Converts a Scala Duration to a Duration.
      *
      * @param value
      *   The Scala Duration to convert
      * @return
      *   A Duration instance
      */
    def fromScala(value: ScalaDuration): Duration =
        if value.isFinite then value.toNanos.nanos.max(Zero) else Infinity

    /** Marker trait for units that can be used with Instant.truncatedTo */
    sealed trait Truncatable

    /** Enumeration of time units with their conversion factors and names. */
    enum Units(val names: List[String], val chronoUnit: ChronoUnit):
        case Nanos   extends Units(List("ns", "nanos", "nanosecond", "nanoseconds"), ChronoUnit.NANOS) with Truncatable
        case Micros  extends Units(List("µs", "micros", "microsecond", "microseconds"), ChronoUnit.MICROS) with Truncatable
        case Millis  extends Units(List("ms", "millis", "millisecond", "milliseconds"), ChronoUnit.MILLIS) with Truncatable
        case Seconds extends Units(List("s", "seconds", "second"), ChronoUnit.SECONDS) with Truncatable
        case Minutes extends Units(List("m", "minutes", "minute"), ChronoUnit.MINUTES) with Truncatable
        case Hours   extends Units(List("h", "hours", "hour"), ChronoUnit.HOURS) with Truncatable
        case Days    extends Units(List("d", "days", "day"), ChronoUnit.DAYS) with Truncatable
        case Weeks   extends Units(List("w", "weeks", "week"), ChronoUnit.WEEKS)
        case Months  extends Units(List("m", "months", "month"), ChronoUnit.MONTHS)
        case Years   extends Units(List("y", "years", "year"), ChronoUnit.YEARS)

        /** Returns the factor for converting this unit to nanoseconds. */
        val factor: Double = chronoUnit.getDuration.toNanos.toDouble
    end Units

    extension (self: Duration)

        private inline def toLong: Long = self

        inline infix def >=(that: Duration): Boolean = self.toLong >= that.toLong
        inline infix def <=(that: Duration): Boolean = self.toLong <= that.toLong
        inline infix def >(that: Duration): Boolean  = self.toLong > that.toLong
        inline infix def <(that: Duration): Boolean  = self.toLong < that.toLong
        inline infix def ==(that: Duration): Boolean = self.toLong == that.toLong
        inline infix def !=(that: Duration): Boolean = self.toLong != that.toLong

        inline infix def +(that: Duration): Duration =
            val sum: Long = self.toLong + that.toLong
            if sum >= 0 then sum else Duration.Infinity

        inline infix def -(that: Duration): Duration =
            val diff: Long = self.toLong - that.toLong
            if diff > 0 then diff else Duration.Zero

        inline infix def *(factor: Double): Duration =
            if factor <= 0 || self.toLong <= 0L then Duration.Zero
            else if factor <= Long.MaxValue / self.toLong.toDouble then Math.round(self.toLong.toDouble * factor)
            else Duration.Infinity

        inline def max(that: Duration): Duration = Math.max(self.toLong, that.toLong)
        inline def min(that: Duration): Duration = Math.min(self.toLong, that.toLong)

        inline def to(unit: Units): Long =
            Math.max(Math.round(self.toLong / unit.factor), Duration.Zero.toLong)

        inline def toNanos: Long   = self.toLong
        inline def toMicros: Long  = self.to(Micros)
        inline def toMillis: Long  = self.to(Millis)
        inline def toSeconds: Long = self.to(Seconds)
        inline def toMinutes: Long = self.to(Minutes)
        inline def toHours: Long   = self.to(Hours)
        inline def toDays: Long    = self.to(Days)
        inline def toWeeks: Long   = self.to(Weeks)
        inline def toMonths: Long  = self.to(Months)
        inline def toYears: Long   = self.to(Years)

        /** Converts the Duration to a Scala Duration.
          *
          * @return
          *   A Scala Duration instance
          */
        def toScala: ScalaDuration =
            (self: @annotation.switch) match
                case Duration.Zero     => ScalaDuration.Zero
                case Duration.Infinity => ScalaDuration.Inf
                case n                 => ScalaDuration.fromNanos(n.toNanos)

        /** Converts the Duration to a Java Duration.
          *
          * @return
          *   A Java Duration instance
          */
        def toJava: JavaDuration =
            (self: @annotation.switch) match
                case Duration.Zero => JavaDuration.ZERO
                case n             => JavaDuration.of(n.toNanos, NANOS)

        /** Converts the Duration to a human-readable string at the most coarse possible resolution without losing information.
          *
          * @return
          *   A string representation of the Duration
          */
        def show: String =
            if self == Zero then "Duration.Zero"
            else if self == Infinity then "Duration.Infinity"
            else
                val nanos = self.toNanos
                Units.values.reverse.find(unit => nanos % unit.factor.toLong == 0) match
                    case Some(unit) =>
                        val value = (nanos / unit.factor).toLong
                        val name  = unit.toString.toLowerCase
                        s"$value.$name"
                    case None =>
                        s"$nanos.nanos"
                end match
        /** Checks if the Duration is finite.
          *
          * @return
          *   true if the Duration is finite, false otherwise
          */
        // TODO Is this Robust enough?
        private[kyo] inline def isFinite: Boolean = self < Duration.Infinity
    end extension

end Duration

extension (value: Long)
    /** Creates a Duration of nanoseconds. */
    inline def nanos: Duration = Duration.fromNanos(value)

    /** Creates a Duration of microseconds. */
    inline def micros: Duration = value.asUnit(Micros)

    /** Creates a Duration of milliseconds. */
    inline def millis: Duration = value.asUnit(Millis)

    /** Creates a Duration of seconds. */
    inline def seconds: Duration = value.asUnit(Seconds)

    /** Creates a Duration of minutes. */
    inline def minutes: Duration = value.asUnit(Minutes)

    /** Creates a Duration of hours. */
    inline def hours: Duration = value.asUnit(Hours)

    /** Creates a Duration of days. */
    inline def days: Duration = value.asUnit(Days)

    /** Creates a Duration of weeks. */
    inline def weeks: Duration = value.asUnit(Weeks)

    /** Creates a Duration of months. */
    inline def months: Duration = value.asUnit(Months)

    /** Creates a Duration of years. */
    inline def years: Duration = value.asUnit(Years)

    inline def nano: Duration   = compiletime.error("please use `.nanos`")
    inline def micro: Duration  = compiletime.error("please use `.micros`")
    inline def milli: Duration  = compiletime.error("please use `.millis`")
    inline def second: Duration = compiletime.error("please use `.seconds`")
    inline def minute: Duration = compiletime.error("please use `.minutes`")
    inline def hour: Duration   = compiletime.error("please use `.hours`")
    inline def day: Duration    = compiletime.error("please use `.days`")
    inline def week: Duration   = compiletime.error("please use `.weeks`")
    inline def month: Duration  = compiletime.error("please use `.months`")
    inline def year: Duration   = compiletime.error("please use `.years`")

    /** Creates a Duration from a specific unit.
      *
      * @param unit
      *   The unit of time
      * @return
      *   A Duration instance
      */
    inline def asUnit(unit: Units): Duration =
        Duration.fromUnits(value, unit)
end extension

/** Extension methods for the value 1 to create singular Durations. */
extension (value: 1)
    inline def nano: Duration   = Duration.fromNanos(value)
    inline def micro: Duration  = value.asUnit(Micros)
    inline def milli: Duration  = value.asUnit(Millis)
    inline def second: Duration = value.asUnit(Seconds)
    inline def minute: Duration = value.asUnit(Minutes)
    inline def hour: Duration   = value.asUnit(Hours)
    inline def day: Duration    = value.asUnit(Days)
    inline def week: Duration   = value.asUnit(Weeks)
    inline def month: Duration  = value.asUnit(Months)
    inline def year: Duration   = value.asUnit(Years)
end extension
