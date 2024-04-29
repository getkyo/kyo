package kyo

import java.time.Duration as JDuration
import java.time.temporal.ChronoUnit.*
import java.time.temporal.TemporalUnit
import scala.annotation.switch
import scala.concurrent.duration.Duration as SDuration

opaque type Duration <: JDuration = JDuration
given CanEqual[Duration, Duration] = CanEqual.derived

object Duration:
    val Zero: Duration                   = JDuration.ZERO
    val Infinity: Duration               = Long.MaxValue.nanos
    def fromNanos(value: Long): Duration = value.nanos
    def from(value: JDuration): Duration =
        (value.nanos: @switch) match
            case 0                        => Zero
            case n if n >= Infinity.nanos => Infinity
            case _                        => value

    def from(value: SDuration): Duration =
        if value.isFinite then value.toNanos.nanos else Infinity
end Duration

extension (value: Long)
    private def as(unit: TemporalUnit): Duration =
        if value <= 0 then Duration.Zero else JDuration.of(value, unit)

    inline def nanos: Duration   = as(NANOS)
    inline def micros: Duration  = as(MICROS)
    inline def millis: Duration  = as(MILLIS)
    inline def seconds: Duration = as(SECONDS)
    inline def minutes: Duration = as(MINUTES)
    inline def hours: Duration   = as(HOURS)
    inline def days: Duration    = as(DAYS)
    inline def weeks: Duration   = as(WEEKS)
    inline def years: Duration   = as(YEARS)
end extension

extension (self: Duration)
    private inline def nanos: Long = if self.toNanos > 0 then self.toNanos else 0

    def +(that: Duration): Duration =
        val sum: Long = self.nanos + that.nanos
        if sum >= 0 then sum.nanos else Duration.Infinity
    end +

    def *(factor: Double): Duration =
        val nanos = self.nanos
        if factor <= 0 || nanos <= 0 then Duration.Zero
        else if factor <= Long.MaxValue / nanos.toDouble then (nanos * factor).round.nanos
        else Duration.Infinity
    end *

    def >=(that: Duration): Boolean = self.compareTo(that) >= 0
    def <=(that: Duration): Boolean = self.compareTo(that) <= 0
    def >(that: Duration): Boolean  = self.compareTo(that) > 0
    def <(that: Duration): Boolean  = self.compareTo(that) < 0
    def ==(that: Duration): Boolean = self.compareTo(that) == 0
    def !=(that: Duration): Boolean = self.compareTo(that) != 0

    def max(that: Duration): Duration = if self > that then self else that
    def min(that: Duration): Duration = if self < that then self else that

    def toScala: SDuration =
        self match
            case Duration.Zero     => SDuration.Zero
            case Duration.Infinity => SDuration.Inf
            case finite            => SDuration.fromNanos(finite.nanos)

    // Is this Robust enough?
    private[kyo] def isFinite: Boolean = <(Duration.Infinity)
end extension
