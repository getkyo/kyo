package kyo

import scala.scalajs.js
import scala.scalajs.js.annotation.*

@JSExportTopLevel("Schedule")
class JsSchedule(@JSName("$sche") val underlying: Schedule) extends js.Object:
    import kyo.JsFacadeGivens.given
    def andThen(that: JsSchedule) =
        new JsSchedule(underlying.andThen(that.underlying))

    def delay(duration: JsDuration) =
        new JsSchedule(underlying.delay(duration.underlying))

    def forever() =
        new JsSchedule(underlying.forever)

    def jitter(factor: Double) =
        new JsSchedule(underlying.jitter(factor))

    def max(that: JsSchedule) =
        new JsSchedule(underlying.max(that.underlying))

    def maxDuration(maxDuration: JsDuration) =
        new JsSchedule(underlying.maxDuration(maxDuration.underlying))

    def min(that: JsSchedule) =
        new JsSchedule(underlying.min(that.underlying))

    def next(now: JsInstant) =
        new JsMaybe(underlying.next(now.underlying))

    def repeat(n: Int) =
        new JsSchedule(underlying.repeat(n))

    def show() =
        underlying.show

    def take(n: Int) =
        new JsSchedule(underlying.take(n))


end JsSchedule

object JsSchedule:
    import kyo.JsFacadeGivens.given

    @JSExportStatic
    def done() =
        new JsSchedule(Schedule.done)

    @JSExportStatic
    def forever() =
        new JsSchedule(Schedule.forever)

    @JSExportStatic
    def immediate() =
        new JsSchedule(Schedule.immediate)

    @JSExportStatic
    def never() =
        new JsSchedule(Schedule.never)


end JsSchedule