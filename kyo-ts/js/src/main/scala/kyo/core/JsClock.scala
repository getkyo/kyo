package kyo

import scala.scalajs.js
import scala.scalajs.js.annotation.*

@JSExportTopLevel("Clock")
class JsClock(@JSName("$cloc") val underlying: Clock) extends js.Object:
    import kyo.JsFacadeGivens.given
    def deadline(duration: JsDuration) =
        new JsKyo(underlying.deadline(duration.underlying))

    def let_[A, S](f: js.Function0[JsKyo[A, S]]) =
        new JsKyo(Clock.let(underlying)(f().underlying))

    def now() =
        new JsKyo(underlying.now)

    def nowMonotonic() =
        new JsKyo(underlying.nowMonotonic)

    def stopwatch() =
        new JsKyo(underlying.stopwatch)

    def unsafe() =
        underlying.unsafe


end JsClock

object JsClock:
    import kyo.JsFacadeGivens.given

    @JSExportStatic
    def apply(unsafe: Clock.Unsafe) =
        new JsClock(Clock.apply(unsafe))

    @JSExportStatic
    def get() =
        new JsKyo(Clock.get)

    @JSExportStatic
    def live() =
        new JsClock(Clock.live)

    @JSExportStatic
    def now() =
        new JsKyo(Clock.now)

    @JSExportStatic
    def nowMonotonic() =
        new JsKyo(Clock.nowMonotonic)

    @JSExportStatic
    def stopwatch() =
        new JsKyo(Clock.stopwatch)

    @JSExportStatic
    def use[A, S](f: Function1[Clock, `<`[A, S]]) =
        new JsKyo(Clock.use(f))

    @JSExportStatic
    def withTimeControl[A, S](f: Function1[Clock.TimeControl, `<`[A, S]]) =
        new JsKyo(Clock.withTimeControl(f))


end JsClock