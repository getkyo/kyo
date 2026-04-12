package kyo

import scala.scalajs.js
import scala.scalajs.js.annotation.*

@JSExportTopLevel("ChunkBuilder")
class JsChunkBuilder[A](@JSName("$chun") val underlying: ChunkBuilder[A]) extends js.Object:
    import kyo.JsFacadeGivens.given
    def addAll(elems: IterableOnce[A]) =
        underlying.addAll(elems)

    def addOne(elem: A) =
        underlying.addOne(elem)

    def clear() =
        underlying.clear()

    def knownSize() =
        underlying.knownSize

    def result() =
        underlying.result()


end JsChunkBuilder

object JsChunkBuilder:
    import kyo.JsFacadeGivens.given

    @JSExportStatic
    def init[A]() =
        new JsChunkBuilder(ChunkBuilder.init)

    @JSExportStatic
    def initTransform[A, B](f: Function2[ChunkBuilder[B], A, Unit]) =
        ChunkBuilder.initTransform(f)


end JsChunkBuilder