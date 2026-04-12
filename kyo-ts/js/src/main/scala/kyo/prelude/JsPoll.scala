package kyo

import scala.scalajs.js
import scala.scalajs.js.annotation.*

@JSExportTopLevel("Poll")
class JsPoll[V](@JSName("$poll") val underlying: Poll[V]) extends js.Object:
    import kyo.JsFacadeGivens.given

end JsPoll

object JsPoll:
    import kyo.JsFacadeGivens.given

    @JSExportStatic
    def andMap[V, A, S](f: Function1[Maybe[V], `<`[A, S]]) =
        new JsKyo(Poll.andMap(f))

    @JSExportStatic
    def eliminatePoll() =
        Poll.eliminatePoll

    @JSExportStatic
    def fold[V, A, S](acc: A, f: Function2[A, V, `<`[A, S]]) =
        new JsKyo(Poll.fold(acc)(f))

    @JSExportStatic
    def one[V]() =
        new JsKyo(Poll.one)


end JsPoll