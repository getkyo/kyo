package kyo

import java.time.Duration as JavaDuration
import java.time.temporal.ChronoUnit.*
import kyo.Duration.Units
import kyo.Duration.Units.*
import kyo.duration.*
import scala.concurrent.duration.Duration as ScalaDuration

type Duration = duration.Duration
given CanEqual[Duration, Duration] = canEqual

object Duration:
    val Zero: Duration     = _Zero
    val Infinity: Duration = _Infinity

    inline def fromNanos(value: Long): Duration = value.nanos

    def fromJava(value: JavaDuration): Duration =
        (value.toNanos: @annotation.switch) match
            case 0                       => Zero
            case n if n >= Long.MaxValue => Infinity
            case n                       => n.nanos

    def fromScala(value: ScalaDuration): Duration =
        if value.isFinite then value.toNanos.nanos max Zero else Infinity

    enum Units(val factor: Double):
        case Nanos   extends Units(NANOS.getDuration.toNanos.toDouble)
        case Micros  extends Units(MICROS.getDuration.toNanos.toDouble)
        case Millis  extends Units(MILLIS.getDuration.toNanos.toDouble)
        case Seconds extends Units(SECONDS.getDuration.toNanos.toDouble)
        case Minutes extends Units(MINUTES.getDuration.toNanos.toDouble)
        case Hours   extends Units(HOURS.getDuration.toNanos.toDouble)
        case Days    extends Units(DAYS.getDuration.toNanos.toDouble)
        case Weeks   extends Units(WEEKS.getDuration.toNanos.toDouble)
        case Months  extends Units(MONTHS.getDuration.toNanos.toDouble)
        case Years   extends Units(YEARS.getDuration.toNanos.toDouble)
    end Units

end Duration

extension (value: Long)
    inline def nanos: Duration   = value.asNanos
    inline def micros: Duration  = value.as(Micros)
    inline def millis: Duration  = value.as(Millis)
    inline def seconds: Duration = value.as(Seconds)
    inline def minutes: Duration = value.as(Minutes)
    inline def hours: Duration   = value.as(Hours)
    inline def days: Duration    = value.as(Days)
    inline def weeks: Duration   = value.as(Weeks)
    inline def months: Duration  = value.as(Months)
    inline def years: Duration   = value.as(Years)
    inline def nano: Duration    = compiletime.error("please use `.nanos`")
    inline def micro: Duration   = compiletime.error("please use `.micros`")
    inline def milli: Duration   = compiletime.error("please use `.millis`")
    inline def second: Duration  = compiletime.error("please use `.seconds`")
    inline def minute: Duration  = compiletime.error("please use `.minutes`")
    inline def hour: Duration    = compiletime.error("please use `.hours`")
    inline def day: Duration     = compiletime.error("please use `.days`")
    inline def week: Duration    = compiletime.error("please use `.weeks`")
    inline def month: Duration   = compiletime.error("please use `.months`")
    inline def year: Duration    = compiletime.error("please use `.years`")
end extension

extension (value: 1)
    inline def nano: Duration   = value.asNanos
    inline def micro: Duration  = value.as(Micros)
    inline def milli: Duration  = value.as(Millis)
    inline def second: Duration = value.as(Seconds)
    inline def minute: Duration = value.as(Minutes)
    inline def hour: Duration   = value.as(Hours)
    inline def day: Duration    = value.as(Days)
    inline def week: Duration   = value.as(Weeks)
    inline def month: Duration  = value.as(Months)
    inline def year: Duration   = value.as(Years)
end extension

extension (self: Duration)

    infix def >=(that: Duration): Boolean = self.gtEq(that)
    infix def <=(that: Duration): Boolean = self.ltEq(that)
    infix def >(that: Duration): Boolean  = self.gt(that)
    infix def <(that: Duration): Boolean  = self.lt(that)
    infix def ==(that: Duration): Boolean = self.eqEq(that)
    infix def !=(that: Duration): Boolean = self.neEq(that)

    inline def +(that: Duration): Duration = self.add(that)
    inline def *(factor: Double): Duration = self.multiply(factor)

    infix def max(that: Duration): Duration = self._max(that)
    infix def min(that: Duration): Duration = self._min(that)

    inline def toNanos: Long   = self._toNanos
    inline def toMicros: Long  = self.to(Micros)
    inline def toMillis: Long  = self.to(Millis)
    inline def toSeconds: Long = self.to(Seconds)
    inline def toMinutes: Long = self.to(Minutes)
    inline def toHours: Long   = self.to(Hours)
    inline def toDays: Long    = self.to(Days)
    inline def toWeeks: Long   = self.to(Weeks)
    inline def toMonths: Long  = self.to(Months)
    inline def toYears: Long   = self.to(Years)

    def toScala: ScalaDuration =
        (self: @annotation.switch) match
            case Duration.Zero     => ScalaDuration.Zero
            case Duration.Infinity => ScalaDuration.Inf
            case n                 => ScalaDuration.fromNanos(n.toNanos)

    def toJava: JavaDuration =
        (self: @annotation.switch) match
            case Duration.Zero => JavaDuration.ZERO
            case n             => JavaDuration.of(n.toNanos, NANOS)

    inline def render: String = s"Duration($self ns)"

    // Is this Robust enough?
    private[kyo] inline def isFinite: Boolean = self < Duration.Infinity
end extension

private[kyo] object duration:
    opaque type Duration = Long
    val canEqual: CanEqual[Duration, Duration] = CanEqual.derived

    val _Zero: Duration     = 0
    val _Infinity: Duration = Long.MaxValue

    extension (value: Long)
        inline def as(unit: Units): Duration =
            if value <= 0 then Duration.Zero else Math.min(value.multiply(unit.factor), Duration.Infinity)

        inline def asNanos: Duration = value
    end extension
    extension (self: Duration)

        inline def to(unit: Units): Long = Math.max(Math.round(self / unit.factor), Duration.Zero)
        inline def _toNanos: Long        = self

        inline def add(that: Duration): Duration =
            val sum: Long = self + that
            if sum >= 0 then sum else Duration.Infinity
        end add

        inline def multiply(factor: Double): Duration = // fix type inference in `as`
            if factor <= 0 || self <= 0 then Duration.Zero
            else if factor <= Long.MaxValue / self.toDouble then Math.round(self.toDouble * factor)
            else Duration.Infinity
        end multiply

        inline def gtEq(that: Duration): Boolean = self >= that
        inline def ltEq(that: Duration): Boolean = self <= that
        inline def gt(that: Duration): Boolean   = self > that
        inline def lt(that: Duration): Boolean   = self < that
        inline def eqEq(that: Duration): Boolean = self == that
        inline def neEq(that: Duration): Boolean = self != that

        inline def _max(that: Duration): Duration = Math.max(self, that)
        inline def _min(that: Duration): Duration = Math.min(self, that)
    end extension
end duration
