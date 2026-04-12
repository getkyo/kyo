package kyo

import scala.scalajs.js
import scala.scalajs.js.annotation.*

@JSExportTopLevel("StreamHub")
class JsStreamHub[A, E](@JSName("$stre") val underlying: StreamHub[A, E]) extends js.Object:
    import kyo.JsFacadeGivens.given

end JsStreamHub

object JsStreamHub:
    import kyo.JsFacadeGivens.given

    @JSExportStatic
    def collectAll[V, E, S](streamObj: Stream, streams: Seq[Stream[V, Abort[E] & S & Async]], bufferSize: Int) =
        new JsStream(StreamHub.collectAll(streamObj)(streams, bufferSize))

    @JSExportStatic
    def collectAllHalting[V, E, S](streamObj: Stream, streams: Seq[Stream[V, S & Abort[E] & Async]], bufferSize: Int) =
        new JsStream(StreamHub.collectAllHalting(streamObj)(streams, bufferSize))

    @JSExportStatic
    def defaultAsyncStreamBufferSize() =
        StreamHub.defaultAsyncStreamBufferSize

    @JSExportStatic
    def fromIterator[V](streamObj: Stream, v: js.Function0[Iterator[V]], chunkSize: Int) =
        new JsStream(StreamHub.fromIterator(streamObj)(v(), chunkSize))

    @JSExportStatic
    def fromIteratorCatching[E, V](streamObj: Stream, v: js.Function0[Iterator[V]], chunkSize: Int) =
        new JsStream(StreamHub.fromIteratorCatching(streamObj)(v(), chunkSize))


end JsStreamHub