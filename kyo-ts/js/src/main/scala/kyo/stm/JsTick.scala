package kyo

import scala.scalajs.js
import scala.scalajs.js.annotation.*

@JSExportTopLevel("Tick")
class JsTick(@JSName("$tick") val underlying: Tick) extends js.Object:
    import kyo.JsFacadeGivens.given
    def value() =
        Tick.value(underlying)


end JsTick

object JsTick:
    import kyo.JsFacadeGivens.given

    @JSExportStatic
    def given_CanEqual_Tick_Tick() =
        Tick.given_CanEqual_Tick_Tick

    @JSExportStatic
    def next() =
        new JsTick(Tick.next())

    @JSExportStatic
    def withCurrent[A, S](f: Function1[Tick, `<`[A, S]]) =
        new JsKyo(Tick.withCurrent(f))

    @JSExportStatic
    def withCurrentOrNext[A, S](f: ContextFunction1[AllowUnsafe, Function1[Tick, `<`[A, S]]]) =
        new JsKyo(Tick.withCurrentOrNext(f))

    @JSExportStatic
    def withNext[A, S](f: Function1[Tick, `<`[A, S]]) =
        new JsKyo(Tick.withNext(f))


end JsTick