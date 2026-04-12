package kyo

import scala.scalajs.js
import scala.scalajs.js.annotation.*

@JSExportTopLevel("Memo")
class JsMemo(@JSName("$memo") val underlying: Memo) extends js.Object:
    import kyo.JsFacadeGivens.given

end JsMemo

object JsMemo:
    import kyo.JsFacadeGivens.given

    @JSExportStatic
    def apply[A, B, S](f: Function1[A, `<`[B, S]]) =
        Memo.apply(f)

    @JSExportStatic
    def isolate() =
        new JsIsolate(Memo.isolate)


end JsMemo