package kyo

import scala.scalajs.js
import scala.scalajs.js.annotation.*

@JSExportTopLevel("StreamCoreExtensions")
object JsStreamCoreExtensions extends js.Object:
    import kyo.JsFacadeGivens.given

    @JSExportStatic
    def collectAll[V, E, S](streamObj: Stream, streams: Seq[Stream[V, `&`[`&`[Abort[E], S], Async]]], bufferSize: Int) =
        new JsStream(StreamCoreExtensions.collectAll(streamObj)(streams, bufferSize))

    @JSExportStatic
    def collectAllHalting[V, E, S](streamObj: Stream, streams: Seq[Stream[V, `&`[`&`[S, Abort[E]], Async]]], bufferSize: Int) =
        new JsStream(StreamCoreExtensions.collectAllHalting(streamObj)(streams, bufferSize))

    @JSExportStatic
    def defaultAsyncStreamBufferSize() =
        StreamCoreExtensions.defaultAsyncStreamBufferSize

    @JSExportStatic
    def fromIterator[V](streamObj: Stream, v: js.Function0[Iterator[V]], chunkSize: Int) =
        new JsStream(StreamCoreExtensions.fromIterator(streamObj)(v(), chunkSize))

    @JSExportStatic
    def fromIteratorCatching[E, V](streamObj: Stream, v: js.Function0[Iterator[V]], chunkSize: Int) =
        new JsStream(StreamCoreExtensions.fromIteratorCatching(streamObj)(v(), chunkSize))


end JsStreamCoreExtensions