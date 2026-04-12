package kyo

import scala.scalajs.js
import scala.scalajs.js.annotation.*

@JSExportTopLevel("HttpOpenApi")
class JsHttpOpenApi(@JSName("$http") val underlying: HttpOpenApi) extends js.Object:
    import kyo.JsFacadeGivens.given
    def components() =
        underlying.components

    def info() =
        underlying.info

    def openapi() =
        underlying.openapi

    def paths() =
        underlying.paths

    def toFile(path: Predef.String) =
        HttpOpenApi.toFile(underlying)(path)

    def toJson() =
        HttpOpenApi.toJson(underlying)


end JsHttpOpenApi