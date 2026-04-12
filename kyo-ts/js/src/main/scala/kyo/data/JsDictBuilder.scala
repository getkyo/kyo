package kyo

import scala.scalajs.js
import scala.scalajs.js.annotation.*

@JSExportTopLevel("DictBuilder")
class JsDictBuilder[K, V](@JSName("$dict") val underlying: DictBuilder[K, V]) extends js.Object:
    import kyo.JsFacadeGivens.given
    def add(key: K, value: V) =
        underlying.add(key, value)

    def clear() =
        underlying.clear()

    def result() =
        new JsDict(underlying.result())

    def size() =
        underlying.size


end JsDictBuilder

object JsDictBuilder:
    import kyo.JsFacadeGivens.given

    @JSExportStatic
    def init[K, V]() =
        new JsDictBuilder(DictBuilder.init)

    @JSExportStatic
    def initTransform[K, V, K2, V2](f: Function3[DictBuilder[K2, V2], K, V, Unit]) =
        DictBuilder.initTransform(f)


end JsDictBuilder