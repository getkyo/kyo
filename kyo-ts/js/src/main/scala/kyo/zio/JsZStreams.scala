package kyo

import scala.scalajs.js
import scala.scalajs.js.annotation.*

@JSExportTopLevel("ZStreams")
object JsZStreams extends js.Object:
    import kyo.JsFacadeGivens.given

    @JSExportStatic
    def get[E, A](stream: js.Function0[zio.stream.ZStream[Any, E, A]]) =
        new JsStream(ZStreams.get(stream()))


end JsZStreams