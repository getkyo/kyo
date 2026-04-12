package kyo

import scala.scalajs.js
import scala.scalajs.js.annotation.*

@JSExportTopLevel("Pipe")
class JsPipe[A, B, S](@JSName("$pipe") val underlying: Pipe[A, B, S]) extends js.Object:
    import kyo.JsFacadeGivens.given
    def contramap[AA, A1, S1](f: Function1[A1, `<`[AA, S1]]) =
        new JsPipe(underlying.contramap(f))

    def contramapChunk[AA, A1, S1](f: Function1[Chunk[A1], `<`[Chunk[AA], S1]]) =
        new JsPipe(underlying.contramapChunk(f))

    def contramapChunkPure[AA, A1](f: Function1[Chunk[A1], Chunk[AA]]) =
        new JsPipe(underlying.contramapChunkPure(f))

    def contramapPure[AA, A1](f: Function1[A1, AA]) =
        new JsPipe(underlying.contramapPure(f))

    def join[BB, C, S1](sink: JsSink[BB, C, S1]) =
        new JsSink(underlying.join(sink.underlying))

    def map[BB, B1, S1](f: Function1[BB, `<`[B1, S1]]) =
        new JsPipe(underlying.map(f))

    def mapChunk[BB, B1, S1](f: Function1[Chunk[BB], `<`[Chunk[B1], S1]]) =
        new JsPipe(underlying.mapChunk(f))

    def mapChunkPure[BB, B1](f: Function1[Chunk[BB], Chunk[B1]]) =
        new JsPipe(underlying.mapChunkPure(f))

    def mapPure[BB, B1](f: Function1[BB, B1]) =
        new JsPipe(underlying.mapPure(f))

    def pollEmit() =
        new JsKyo(underlying.pollEmit)

    def transform[AA, S1](stream: JsStream[AA, S1]) =
        new JsStream(underlying.transform(stream.underlying))


end JsPipe

object JsPipe:
    import kyo.JsFacadeGivens.given

    @JSExportStatic
    def changes[A](first: A) =
        new JsPipe(Pipe.changes(first))

    @JSExportStatic
    def changes[A]() =
        new JsPipe(Pipe.changes)

    @JSExportStatic
    def collect[A, B, S](f: Function1[A, `<`[Maybe[B], S]]) =
        new JsPipe(Pipe.collect(f))

    @JSExportStatic
    def collectPure[A, B](f: Function1[A, Maybe[B]]) =
        new JsPipe(Pipe.collectPure(f))

    @JSExportStatic
    def collectWhile[A, B, S](f: Function1[A, `<`[Maybe[B], S]]) =
        new JsPipe(Pipe.collectWhile(f))

    @JSExportStatic
    def collectWhilePure[A, B](f: Function1[A, Maybe[B]]) =
        new JsPipe(Pipe.collectWhilePure(f))

    @JSExportStatic
    def dropWhile[A, S](f: Function1[A, `<`[Boolean, S]]) =
        new JsPipe(Pipe.dropWhile(f))

    @JSExportStatic
    def dropWhilePure[A](f: Function1[A, Boolean]) =
        new JsPipe(Pipe.dropWhilePure(f))

    @JSExportStatic
    def empty[A, B]() =
        new JsPipe(Pipe.empty)

    @JSExportStatic
    def filter[A, S](f: Function1[A, `<`[Boolean, S]]) =
        new JsPipe(Pipe.filter(f))

    @JSExportStatic
    def filterPure[A](f: Function1[A, Boolean]) =
        new JsPipe(Pipe.filterPure(f))

    @JSExportStatic
    def identity[A]() =
        new JsPipe(Pipe.identity)

    @JSExportStatic
    def map[A, B, S](f: Function1[A, `<`[B, S]]) =
        new JsPipe(Pipe.map(f))

    @JSExportStatic
    def mapChunk[A, B, S](f: Function1[Chunk[A], `<`[Chunk[B], S]]) =
        new JsPipe(Pipe.mapChunk(f))

    @JSExportStatic
    def mapChunkPure[A, B](f: Function1[Chunk[A], Chunk[B]]) =
        new JsPipe(Pipe.mapChunkPure(f))

    @JSExportStatic
    def mapPure[A, B](f: Function1[A, B]) =
        new JsPipe(Pipe.mapPure(f))

    @JSExportStatic
    def takeWhile[A, S](f: Function1[A, `<`[Boolean, S]]) =
        new JsPipe(Pipe.takeWhile(f))

    @JSExportStatic
    def takeWhilePure[A](f: Function1[A, Boolean]) =
        new JsPipe(Pipe.takeWhilePure(f))

    @JSExportStatic
    def tap[A, S](f: Function1[A, `<`[Any, S]]) =
        new JsPipe(Pipe.tap(f))

    @JSExportStatic
    def tapChunk[A, S](f: Function1[Chunk[A], `<`[Any, S]]) =
        new JsPipe(Pipe.tapChunk(f))


end JsPipe