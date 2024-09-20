package kyo

import java.time.Duration as JavaDuration
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
            case "infinity" | "inf" => Result.succeed(Infinity)
            case pattern(value, unit) =>
                for
                    longValue <-
                        Result.catching[NumberFormatException](value.toLong)
                            .mapError(_ => InvalidDuration(s"Invalid number: $value"))
                    unitEnum <-
                        Units.values.find(_.names.exists(_.startsWith(unit)))
                            .map(Result.succeed)
                            .getOrElse(Result.error(InvalidDuration(s"Invalid unit: $unit")))
                yield fromUnits(longValue, unitEnum)
            case _ => Result.error(InvalidDuration(s"Invalid duration format: $s"))
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
    inline def fromNanos(value: Long): Duration = value

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

    /** Enumeration of time units with their conversion factors and names. */
    enum Units(val factor: Double, val names: List[String]):
        case Nanos   extends Units(NANOS.getDuration.toNanos.toDouble, List("ns", "nanos", "nanosecond", "nanoseconds"))
        case Micros  extends Units(MICROS.getDuration.toNanos.toDouble, List("µs", "micros", "microsecond", "microseconds"))
        case Millis  extends Units(MILLIS.getDuration.toNanos.toDouble, List("ms", "millis", "millisecond", "milliseconds"))
        case Seconds extends Units(SECONDS.getDuration.toNanos.toDouble, List("s", "seconds", "second"))
        case Minutes extends Units(MINUTES.getDuration.toNanos.toDouble, List("m", "minutes", "minute"))
        case Hours   extends Units(HOURS.getDuration.toNanos.toDouble, List("h", "hours", "hour"))
        case Days    extends Units(DAYS.getDuration.toNanos.toDouble, List("d", "days", "day"))
        case Weeks   extends Units(WEEKS.getDuration.toNanos.toDouble, List("w", "weeks", "week"))
        case Months  extends Units(MONTHS.getDuration.toNanos.toDouble, List("m", "months", "month"))
        case Years   extends Units(YEARS.getDuration.toNanos.toDouble, List("y", "years", "year"))
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

        inline infix def *(factor: Double): Duration =
            if factor <= 0 || self.toLong <= 0L then Duration.Zero
            else if factor <= Long.MaxValue / self.toLong.toDouble then Math.round(self.toLong.toDouble * factor)
            else Duration.Infinity

        inline def max(that: Duration): Duration = Math.max(self.toLong, that.toLong)
        inline def min(that: Duration): Duration = Math.min(self.toLong, that.toLong)

        inline def to(unit: Units): Long =
            Math.max(Math.round(self.toLong / unit.factor), Duration.Zero)

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

        /** Converts the Duration to a human-readable string.
          *
          * @return
          *   A string representation of the Duration
          */
        inline def show: String = s"Duration($self ns)"

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
