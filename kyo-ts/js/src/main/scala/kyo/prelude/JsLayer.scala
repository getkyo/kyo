package kyo

import scala.scalajs.js
import scala.scalajs.js.annotation.*

@JSExportTopLevel("Layer")
class JsLayer[Out, S](@JSName("$laye") val underlying: Layer[Out, S]) extends js.Object:
    import kyo.JsFacadeGivens.given
    def and[Out2, S2](that: JsLayer[Out2, S2]) =
        new JsLayer(underlying.and(that.underlying))

    def run[In, R]() =
        new JsKyo(Layer.run(underlying))

    def to[Out2, S2, In2](that: JsLayer[Out2, `&`[Env[In2], S2]]) =
        new JsLayer(underlying.to(that.underlying))

    def using[Out2, S2, In2](that: JsLayer[Out2, `&`[Env[In2], S2]]) =
        new JsLayer(underlying.using(that.underlying))


end JsLayer

object JsLayer:
    import kyo.JsFacadeGivens.given

    @JSExportStatic
    def empty() =
        new JsLayer(Layer.empty)

    @JSExportStatic
    def from[A, B, C, D, E, F, G, H, I, J, S](f: Function9[A, B, C, D, E, F, G, H, I, `<`[J, S]]) =
        new JsLayer(Layer.from(f))

    @JSExportStatic
    def init[Target](layers: Seq[Layer[?, ?]]) =
        new JsLayer(Layer.init(layers*))


end JsLayer