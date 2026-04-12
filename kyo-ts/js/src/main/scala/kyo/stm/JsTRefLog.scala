package kyo

import scala.scalajs.js
import scala.scalajs.js.annotation.*

@JSExportTopLevel("TRefLog")
class JsTRefLog(@JSName("$tref") val underlying: TRefLog) extends js.Object:
    import kyo.JsFacadeGivens.given
    def get[A](ref: JsTRef[A]) =
        new JsMaybe(TRefLog.get(underlying)(ref.underlying))

    def put[A](ref: JsTRef[A], entry: TRefLog.Entry[A]) =
        new JsTRefLog(TRefLog.put(underlying)(ref.underlying, entry))

    def toMap() =
        TRefLog.toMap(underlying)


end JsTRefLog

object JsTRefLog:
    import kyo.JsFacadeGivens.given

    @JSExportStatic
    def empty() =
        new JsTRefLog(TRefLog.empty)

    @JSExportStatic
    def isolate() =
        new JsIsolate(TRefLog.isolate)


end JsTRefLog