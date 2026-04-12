package kyo

import scala.scalajs.js
import scala.scalajs.js.annotation.*

@JSExportTopLevel("Scope")
class JsScope(@JSName("$scop") val underlying: Scope) extends js.Object:
    import kyo.JsFacadeGivens.given

end JsScope

object JsScope:
    import kyo.JsFacadeGivens.given

    @JSExportStatic
    def ensure(f: Function1[Maybe[Result.Error[Any]], `<`[Any, `&`[Async, Abort[Throwable]]]]) =
        new JsKyo(Scope.ensure(f))


end JsScope