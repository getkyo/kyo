package kyo

import scala.scalajs.js
import scala.scalajs.js.annotation.*

@JSExportTopLevel("Duration")
class JsDuration(@JSName("$dura") val underlying: Duration) extends js.Object:
    import kyo.JsFacadeGivens.given
    def max(that: JsDuration) =
        new JsDuration(Duration.max(underlying)(that.underlying))

    def min(that: JsDuration) =
        new JsDuration(Duration.min(underlying)(that.underlying))

    def minus(that: JsDuration) =
        new JsDuration(Duration.`-`(underlying)(that.underlying))

    def plus(that: JsDuration) =
        new JsDuration(Duration.`+`(underlying)(that.underlying))

    def show() =
        Duration.show(underlying)

    def times(factor: Double) =
        new JsDuration(Duration.`*`(underlying)(factor))

    def to(unit: Duration.Units) =
        Duration.to(underlying)(unit)

    def toDays() =
        Duration.toDays(underlying)

    def toHours() =
        Duration.toHours(underlying)

    def toJava() =
        Duration.toJava(underlying)

    def toMicros() =
        Duration.toMicros(underlying)

    def toMillis() =
        Duration.toMillis(underlying)

    def toMinutes() =
        Duration.toMinutes(underlying)

    def toMonths() =
        Duration.toMonths(underlying)

    def toNanos() =
        Duration.toNanos(underlying)

    def toScala() =
        Duration.toScala(underlying)

    def toSeconds() =
        Duration.toSeconds(underlying)

    def toWeeks() =
        Duration.toWeeks(underlying)

    def toYears() =
        Duration.toYears(underlying)


end JsDuration

object JsDuration:
    import kyo.JsFacadeGivens.given

    @JSExportStatic
    def given_CanEqual_Duration_Duration() =
        Duration.given_CanEqual_Duration_Duration


end JsDuration