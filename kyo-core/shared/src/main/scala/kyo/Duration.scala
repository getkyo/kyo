package kyo

import java.time.Duration as JavaDuration
import java.time.temporal.ChronoUnit.*
import kyo.Duration.Units
import kyo.Duration.Units.*
import scala.concurrent.duration.Duration as ScalaDuration

opaque type Duration = Long
given CanEqual[Duration, Duration] = Duration.canEqual

object Duration:
    val Zero: Duration     = 0
    val Infinity: Duration = Long.MaxValue

    inline def fromNanos(value: Long): Duration = value
    def fromJava(value: JavaDuration): Duration =
        (value.toNanos: @annotation.switch) match
            case 0                  => Zero
            case n if n >= Infinity => Infinity
            case n                  => n.nanos

    def fromScala(value: ScalaDuration): Duration =
        if value.isFinite then Math.max(value.toNanos.nanos, Zero) else Infinity

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

    private[kyo] val canEqual: CanEqual[Duration, Duration] = CanEqual.derived
end Duration

extension (value: Long)

    private inline def as(unit: Units): Duration =
        if value <= 0 then Duration.Zero else Math.min(value.multiply(unit.factor), Duration.Infinity)

    inline def nanos: Duration   = value
    inline def micros: Duration  = as(Micros)
    inline def millis: Duration  = as(Millis)
    inline def seconds: Duration = as(Seconds)
    inline def minutes: Duration = as(Minutes)
    inline def hours: Duration   = as(Hours)
    inline def days: Duration    = as(Days)
    inline def weeks: Duration   = as(Weeks)
    inline def months: Duration  = as(Months)
    inline def years: Duration   = as(Years)
end extension

extension (self: Duration)
    private inline def to(unit: Units): Long = Math.max(Math.round(self / unit.factor), Duration.Zero)

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

    def max(that: Duration): Duration = Math.max(self, that)
    def min(that: Duration): Duration = Math.min(self, that)

    inline def toNanos: Long   = self
    inline def toMicros: Long  = to(Micros)
    inline def toMillis: Long  = to(Millis)
    inline def toSeconds: Long = to(Seconds)
    inline def toMinutes: Long = to(Minutes)
    inline def toHours: Long   = to(Hours)
    inline def toDays: Long    = to(Days)
    inline def toWeeks: Long   = to(Weeks)
    inline def toMonths: Long  = to(Months)
    inline def toYears: Long   = to(Years)

    def toScala: ScalaDuration =
        (self: @annotation.switch) match
            case Duration.Zero     => ScalaDuration.Zero
            case Duration.Infinity => ScalaDuration.Inf
            case n                 => ScalaDuration.fromNanos(n)

    def toJava: JavaDuration =
        (self: @annotation.switch) match
            case Duration.Zero => JavaDuration.ZERO
            case n             => JavaDuration.of(n, NANOS)

    // Is this Robust enough?
    private[kyo] inline def isFinite: Boolean = self < Duration.Infinity
end extension
