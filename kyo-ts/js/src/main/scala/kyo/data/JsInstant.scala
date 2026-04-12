package kyo

import scala.scalajs.js
import scala.scalajs.js.annotation.*

@JSExportTopLevel("Instant")
class JsInstant(@JSName("$inst") val underlying: Instant) extends js.Object:
    import kyo.JsFacadeGivens.given
    def between(start: JsInstant, end: JsInstant) =
        Instant.between(underlying)(start.underlying, end.underlying)

    def clamp(min: JsInstant, max: JsInstant) =
        new JsInstant(Instant.clamp(underlying)(min.underlying, max.underlying))

    def max(other: JsInstant) =
        new JsInstant(Instant.max(underlying)(other.underlying))

    def min(other: JsInstant) =
        new JsInstant(Instant.min(underlying)(other.underlying))

    def minus(other: JsInstant) =
        new JsDuration(Instant.`-`(underlying)(other.underlying))

    def plus(duration: JsDuration) =
        new JsInstant(Instant.`+`(underlying)(duration.underlying))

    def show() =
        Instant.show(underlying)

    def toDuration() =
        new JsDuration(Instant.toDuration(underlying))

    def toJava() =
        Instant.toJava(underlying)

    def truncatedTo(unit: `&`[Duration.Units, Duration.Truncatable]) =
        new JsInstant(Instant.truncatedTo(underlying)(unit))


end JsInstant

object JsInstant:
    import kyo.JsFacadeGivens.given

    @JSExportStatic
    def given_CanEqual_Instant_Instant() =
        Instant.given_CanEqual_Instant_Instant

    @JSExportStatic
    def given_Ordering_Instant() =
        Instant.given_Ordering_Instant

    @JSExportStatic
    def parse(text: CharSequence) =
        new JsResult(Instant.parse(text))


end JsInstant