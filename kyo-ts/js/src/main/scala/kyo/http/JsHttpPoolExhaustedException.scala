package kyo

import scala.scalajs.js
import scala.scalajs.js.annotation.*

@JSExportTopLevel("HttpPoolExhaustedException")
class JsHttpPoolExhaustedException(@JSName("$http") val underlying: HttpPoolExhaustedException) extends js.Object:
    import kyo.JsFacadeGivens.given
    def clientFrame() =
        new JsFrame(underlying.clientFrame)

    def host() =
        underlying.host

    def maxConnections() =
        underlying.maxConnections

    def port() =
        underlying.port


end JsHttpPoolExhaustedException