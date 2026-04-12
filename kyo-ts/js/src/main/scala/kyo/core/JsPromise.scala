package kyo

import scala.scalajs.js
import scala.scalajs.js.annotation.*

@JSExportTopLevel("Promise")
class JsPromise[A, S](@JSName("$prom") val underlying: Promise[A, S]) extends js.Object:
    import kyo.JsFacadeGivens.given

end JsPromise