package kyo

import scala.scalajs.js
import scala.scalajs.js.annotation.*

@JSExportTopLevel("Stream")
class JsStream[V, S](@JSName("$stre") val underlying: Stream[V, S]) extends js.Object:
    import kyo.JsFacadeGivens.given
    def broadcast2[E](bufferSize: Int) =
        new JsKyo(Stream.broadcast2(underlying)(bufferSize))

    def broadcast3[E](bufferSize: Int) =
        new JsKyo(Stream.broadcast3(underlying)(bufferSize))

    def broadcast4[E](bufferSize: Int) =
        new JsKyo(Stream.broadcast4(underlying)(bufferSize))

    def broadcast5[E](bufferSize: Int) =
        new JsKyo(Stream.broadcast5(underlying)(bufferSize))

    def broadcastDynamic[E](bufferSize: Int) =
        new JsKyo(Stream.broadcastDynamic(underlying)(bufferSize))

    def broadcastDynamicWith[E, A, S1](bufferSize: Int, fn: Function1[StreamCoreExtensions.StreamHub[V, E], kyo.kernel.`<`[A, S1]]) =
        new JsKyo(Stream.broadcastDynamicWith(underlying)(bufferSize)(fn))

    def broadcastDynamicWith[E, A, S1](fn: Function1[StreamCoreExtensions.StreamHub[V, E], kyo.kernel.`<`[A, S1]]) =
        new JsKyo(Stream.broadcastDynamicWith(underlying)(fn))

    def broadcastN[E](numStreams: Int, bufferSize: Int) =
        new JsKyo(Stream.broadcastN(underlying)(numStreams, bufferSize))

    def broadcasted[E](bufferSize: Int) =
        new JsKyo(Stream.broadcasted(underlying)(bufferSize))

    def changes[VV](first: VV) =
        new JsStream(underlying.changes(first))

    def changes[VV]() =
        new JsStream(underlying.changes)

    def collect[VV, V2, S2](f: Function1[VV, `<`[Maybe[V2], S2]]) =
        new JsStream(underlying.collect(f))

    def collectPure[VV, V2](f: Function1[VV, Maybe[V2]]) =
        new JsStream(underlying.collectPure(f))

    def collectWhile[VV, V2, S2](f: Function1[VV, `<`[Maybe[V2], S2]]) =
        new JsStream(underlying.collectWhile(f))

    def collectWhilePure[VV, V2](f: Function1[VV, Maybe[V2]]) =
        new JsStream(underlying.collectWhilePure(f))

    def concat[VV, S2](other: JsStream[VV, S2]) =
        new JsStream(underlying.concat(other.underlying))

    def discard[VV]() =
        new JsKyo(underlying.discard)

    def drop[VV](n: Int) =
        new JsStream(underlying.drop(n))

    def dropWhile[VV, S2](f: Function1[VV, `<`[Boolean, S2]]) =
        new JsStream(underlying.dropWhile(f))

    def dropWhilePure[VV](f: Function1[VV, Boolean]) =
        new JsStream(underlying.dropWhilePure(f))

    def emit() =
        new JsKyo(underlying.emit)

    def filter[VV, S2](f: Function1[VV, `<`[Boolean, S2]]) =
        new JsStream(underlying.filter(f))

    def filterPure[VV](f: Function1[VV, Boolean]) =
        new JsStream(underlying.filterPure(f))

    def flatMap[VV, S2, V2, S3](f: Function1[VV, `<`[Stream[V2, S2], S3]]) =
        new JsStream(underlying.flatMap(f))

    def flatMapChunk[VV, S2, V2, S3](f: Function1[Chunk[VV], `<`[Stream[V2, S2], S3]]) =
        new JsStream(underlying.flatMapChunk(f))

    def fold[VV, A, S2](acc: A, f: Function2[A, VV, `<`[A, S2]]) =
        new JsKyo(underlying.fold(acc)(f))

    def foldPure[VV, A](acc: A, f: Function2[A, VV, A]) =
        new JsKyo(underlying.foldPure(acc)(f))

    def foreach[VV, S2](f: Function1[VV, `<`[Any, S2]]) =
        new JsKyo(underlying.foreach(f))

    def foreachChunk[VV, S2](f: Function1[Chunk[VV], `<`[Any, S2]]) =
        new JsKyo(underlying.foreachChunk(f))

    def groupedWithin[E](maxSize: Int, maxTime: JsDuration, bufferSize: Int) =
        new JsStream(Stream.groupedWithin(underlying)(maxSize, maxTime.underlying, bufferSize))

    def handle[A, B, C, D, E, F, G, V1, S1](f1: Function1[Function0[A], B], f2: Function1[Function0[B], C], f3: Function1[Function0[C], D], f4: Function1[Function0[D], E], f5: Function1[Function0[E], F], f6: Function1[Function0[F], G], f7: Function1[Function0[G], `<`[Any, `&`[Emit[Chunk[V1]], S1]]]) =
        new JsStream(underlying.handle(f1, f2, f3, f4, f5, f6, f7))

    def handle[A, B, C, D, E, F, G, H, V1, S1](f1: Function1[Function0[A], B], f2: Function1[Function0[B], C], f3: Function1[Function0[C], D], f4: Function1[Function0[D], E], f5: Function1[Function0[E], F], f6: Function1[Function0[F], G], f7: Function1[Function0[G], H], f8: Function1[Function0[H], `<`[Any, `&`[Emit[Chunk[V1]], S1]]]) =
        new JsStream(underlying.handle(f1, f2, f3, f4, f5, f6, f7, f8))

    def handle[A, V1, S1](f: Function1[Function0[A], `<`[Any, `&`[Emit[Chunk[V1]], S1]]]) =
        new JsStream(underlying.handle(f))

    def handle[A, B, V1, S1](f1: Function1[Function0[A], B], f2: Function1[Function0[B], `<`[Any, `&`[Emit[Chunk[V1]], S1]]]) =
        new JsStream(underlying.handle(f1, f2))

    def handle[A, B, C, D, E, V1, S1](f1: Function1[Function0[A], B], f2: Function1[Function0[B], C], f3: Function1[Function0[C], D], f4: Function1[Function0[D], E], f5: Function1[Function0[E], `<`[Any, `&`[Emit[Chunk[V1]], S1]]]) =
        new JsStream(underlying.handle(f1, f2, f3, f4, f5))

    def handle[A, B, C, V1, S1](f1: Function1[Function0[A], B], f2: Function1[Function0[B], C], f3: Function1[Function0[C], `<`[Any, `&`[Emit[Chunk[V1]], S1]]]) =
        new JsStream(underlying.handle(f1, f2, f3))

    def handle[A, B, C, D, E, F, V1, S1](f1: Function1[Function0[A], B], f2: Function1[Function0[B], C], f3: Function1[Function0[C], D], f4: Function1[Function0[D], E], f5: Function1[Function0[E], F], f6: Function1[Function0[F], `<`[Any, `&`[Emit[Chunk[V1]], S1]]]) =
        new JsStream(underlying.handle(f1, f2, f3, f4, f5, f6))

    def handle[A, B, C, D, E, F, G, H, I, J, V1, S1](f1: Function1[Function0[A], B], f2: Function1[Function0[B], C], f3: Function1[Function0[C], D], f4: Function1[Function0[D], E], f5: Function1[Function0[E], F], f6: Function1[Function0[F], G], f7: Function1[Function0[G], H], f8: Function1[Function0[H], I], f9: Function1[Function0[I], J], f10: Function1[Function0[J], `<`[Any, `&`[Emit[Chunk[V1]], S1]]]) =
        new JsStream(underlying.handle(f1, f2, f3, f4, f5, f6, f7, f8, f9, f10))

    def handle[A, B, C, D, E, F, G, H, I, V1, S1](f1: Function1[Function0[A], B], f2: Function1[Function0[B], C], f3: Function1[Function0[C], D], f4: Function1[Function0[D], E], f5: Function1[Function0[E], F], f6: Function1[Function0[F], G], f7: Function1[Function0[G], H], f8: Function1[Function0[H], I], f9: Function1[Function0[I], `<`[Any, `&`[Emit[Chunk[V1]], S1]]]) =
        new JsStream(underlying.handle(f1, f2, f3, f4, f5, f6, f7, f8, f9))

    def handle[A, B, C, D, V1, S1](f1: Function1[Function0[A], B], f2: Function1[Function0[B], C], f3: Function1[Function0[C], D], f4: Function1[Function0[D], `<`[Any, `&`[Emit[Chunk[V1]], S1]]]) =
        new JsStream(underlying.handle(f1, f2, f3, f4))

    def into[VV, A, S2](sink: JsSink[VV, A, S2]) =
        new JsKyo(underlying.into(sink.underlying))

    def map[VV, V2, S2](f: Function1[VV, `<`[V2, S2]]) =
        new JsStream(underlying.map(f))

    def mapChunk[VV, V2, S2](f: Function1[Chunk[VV], `<`[Seq[V2], S2]]) =
        new JsStream(underlying.mapChunk(f))

    def mapChunkPar[E, V2, S2](f: Function1[Chunk[V], kyo.kernel.`<`[Chunk[V2], Abort[E] & Async & S2]]) =
        new JsStream(Stream.mapChunkPar(underlying)(f))

    def mapChunkPar[E, V2, S2](parallel: Int, bufferSize: Int, f: Function1[Chunk[V], kyo.kernel.`<`[Chunk[V2], Abort[E] & Async & S2]]) =
        new JsStream(Stream.mapChunkPar(underlying)(parallel, bufferSize)(f))

    def mapChunkParUnordered[E, V2, S2](f: Function1[Chunk[V], kyo.kernel.`<`[Chunk[V2], Abort[E] & Async & S2]]) =
        new JsStream(Stream.mapChunkParUnordered(underlying)(f))

    def mapChunkParUnordered[E, V2, S2](parallel: Int, bufferSize: Int, f: Function1[Chunk[V], kyo.kernel.`<`[Chunk[V2], Abort[E] & Async & S2]]) =
        new JsStream(Stream.mapChunkParUnordered(underlying)(parallel, bufferSize)(f))

    def mapChunkPure[VV, V2](f: Function1[Chunk[VV], Seq[V2]]) =
        new JsStream(underlying.mapChunkPure(f))

    def mapPar[E, V2, S2](parallel: Int, bufferSize: Int, f: Function1[V, kyo.kernel.`<`[V2, Abort[E] & Async & S2]]) =
        new JsStream(Stream.mapPar(underlying)(parallel, bufferSize)(f))

    def mapPar[E, V2, S2](f: Function1[V, kyo.kernel.`<`[V2, Abort[E] & Async & S2]]) =
        new JsStream(Stream.mapPar(underlying)(f))

    def mapParUnordered[E, V2, S2](f: Function1[V, kyo.kernel.`<`[V2, Abort[E] & Async & S2]]) =
        new JsStream(Stream.mapParUnordered(underlying)(f))

    def mapParUnordered[E, V2, S2](parallel: Int, bufferSize: Int, f: Function1[V, kyo.kernel.`<`[V2, Abort[E] & Async & S2]]) =
        new JsStream(Stream.mapParUnordered(underlying)(parallel, bufferSize)(f))

    def mapPure[VV, V2](f: Function1[VV, V2]) =
        new JsStream(underlying.mapPure(f))

    def merge[E, S2](other: JsStream[V, Abort[E] & S & Async], bufferSize: Int) =
        new JsStream(Stream.merge(underlying)(other.underlying, bufferSize))

    def mergeHalting[E, S2](other: JsStream[V, Abort[E] & S & Async], bufferSize: Int) =
        new JsStream(Stream.mergeHalting(underlying)(other.underlying, bufferSize))

    def mergeHaltingLeft[E](other: JsStream[V, Abort[E] & S & Async], bufferSize: Int) =
        new JsStream(Stream.mergeHaltingLeft(underlying)(other.underlying, bufferSize))

    def mergeHaltingRight[E](other: JsStream[V, Abort[E] & S & Async], bufferSize: Int) =
        new JsStream(Stream.mergeHaltingRight(underlying)(other.underlying, bufferSize))

    def rechunk[VV](chunkSize: Int) =
        new JsStream(underlying.rechunk(chunkSize))

    def run[VV]() =
        new JsKyo(underlying.run)

    def splitAt[VV](n: Int) =
        new JsKyo(underlying.splitAt(n))

    def subscribe[T](subscriber: Flow.Subscriber[?]) =
        new JsKyo(Stream.subscribe(underlying)(subscriber))

    def take[VV](n: Int) =
        new JsStream(underlying.take(n))

    def takeWhile[VV, S2](f: Function1[VV, `<`[Boolean, S2]]) =
        new JsStream(underlying.takeWhile(f))

    def takeWhilePure[VV](f: Function1[VV, Boolean]) =
        new JsStream(underlying.takeWhilePure(f))

    def tap[VV, S1](f: Function1[VV, `<`[Any, S1]]) =
        new JsStream(underlying.tap(f))

    def tapChunk[VV, S1](f: Function1[Chunk[VV], `<`[Any, S1]]) =
        new JsStream(underlying.tapChunk(f))

    def toPublisher[T]() =
        new JsKyo(Stream.toPublisher(underlying))


end JsStream

object JsStream:
    import kyo.JsFacadeGivens.given

    @JSExportStatic
    def empty[V]() =
        new JsStream(Stream.empty)

    @JSExportStatic
    def unfold[A, V, S](acc: A, chunkSize: Int, f: Function1[A, `<`[Maybe[Tuple2[V, A]], S]]) =
        new JsStream(Stream.unfold(acc, chunkSize)(f))


end JsStream