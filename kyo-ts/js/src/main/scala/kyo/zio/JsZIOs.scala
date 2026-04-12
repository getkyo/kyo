package kyo

import scala.scalajs.js
import scala.scalajs.js.annotation.*

@JSExportTopLevel("ZIOs")
object JsZIOs extends js.Object:
    import kyo.JsFacadeGivens.given

    @JSExportStatic
    def get[R, E, A](v: zio.ZIO[R, E, A]) =
        new JsKyo(ZIOs.get(v))

    @JSExportStatic
    def toCause[E](ex: Result.Error[E]) =
        ZIOs.toCause(ex)

    @JSExportStatic
    def toError[E](cause: zio.Cause[E]) =
        ZIOs.toError(cause)

    @JSExportStatic
    def toResult[E, A](exit: zio.Exit[E, A]) =
        new JsResult(ZIOs.toResult(exit))


end JsZIOs