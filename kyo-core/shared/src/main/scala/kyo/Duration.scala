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

    inline def fromNanos(value: Long): Duration = _fromNanos(value)
    def fromJava(value: JavaDuration): Duration =
        (value.toNanos: @annotation.switch) match
            case 0                       => Zero
            case n if n >= Long.MaxValue => Infinity
            case n                       => n.nanos

    def fromScala(value: ScalaDuration): Duration =
        if value.isFinite then value.toNanos.nanos.max(Zero) else Infinity

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
    inline def nanos: Duration   = _fromNanos(value)
    inline def micros: Duration  = value.as(Micros)
    inline def millis: Duration  = value.as(Millis)
    inline def seconds: Duration = value.as(Seconds)
    inline def minutes: Duration = value.as(Minutes)
    inline def hours: Duration   = value.as(Hours)
    inline def days: Duration    = value.as(Days)
    inline def weeks: Duration   = value.as(Weeks)
    inline def months: Duration  = value.as(Months)
    inline def years: Duration   = value.as(Years)
end extension

extension (self: Duration)

//    infix def >=(that: Duration): Boolean = self.>=(that)
//    infix def <=(that: Duration): Boolean = self.<=(that)
//    infix def >(that: Duration): Boolean  = self.>(that)
//    infix def <(that: Duration): Boolean  = self.<(that)
//    infix def ==(that: Duration): Boolean = self.==(that)
//    infix def !=(that: Duration): Boolean = self.==(that)

    infix def max(that: Duration): Duration = self._max(that)
    infix def min(that: Duration): Duration = self._min(that)

    inline def toNanos: Long   = _toNanos(self)
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

    // Is this Robust enough?
    private[kyo] inline def isFinite: Boolean = self < Duration.Infinity
end extension

private[kyo] object duration:
    opaque type Duration = Long
    val canEqual: CanEqual[Duration, Duration] = CanEqual.derived

    val _Zero: Duration     = 0
    val _Infinity: Duration = Long.MaxValue

    def _toNanos(value: Duration): Long   = value
    def _fromNanos(value: Long): Duration = value

    extension (value: Long)
        inline def as(unit: Units): Duration =
            if value <= 0 then Duration.Zero else Math.min(value.multiply(unit.factor), Duration.Infinity)

    extension (self: Duration)

        inline def to(unit: Units): Long = Math.max(Math.round(self / unit.factor), Duration.Zero)

        def +(that: Duration): Duration =
            val sum: Long = self + that
            if sum >= 0 then sum else Duration.Infinity
        end +

        def *(factor: Double): Duration = multiply(factor)

        private inline def multiply(factor: Double): Duration = // fix type inference in `as`
            if factor <= 0 || self <= 0 then Duration.Zero
            else if factor <= Long.MaxValue / self.toDouble then Math.round(self.toDouble * factor)
            else Duration.Infinity
        end multiply

        def >=(that: Duration): Boolean = self >= that
        def <=(that: Duration): Boolean = self <= that
        def >(that: Duration): Boolean  = self > that
        def <(that: Duration): Boolean  = self < that
        def ==(that: Duration): Boolean = self == that
        def !=(that: Duration): Boolean = self == that

        def _max(that: Duration): Duration = Math.max(self, that)
        def _min(that: Duration): Duration = Math.min(self, that)
    end extension
end duration
