package kyo

import scala.scalajs.js
import scala.scalajs.js.annotation.*

@JSExportTopLevel("ZLayers")
object JsZLayers extends js.Object:
    import kyo.JsFacadeGivens.given

    @JSExportStatic
    def get[E, A](layer: js.Function0[zio.ZLayer[Any, E, A]]) =
        new JsLayer(ZLayers.get(layer()))


end JsZLayers