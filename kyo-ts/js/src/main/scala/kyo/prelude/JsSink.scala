package kyo

import scala.scalajs.js
import scala.scalajs.js.annotation.*

@JSExportTopLevel("Sink")
class JsSink[V, A, S](@JSName("$sink") val underlying: Sink[V, A, S]) extends js.Object:
    import kyo.JsFacadeGivens.given
    def contramap[VV, V2, S2](f: Function1[V2, `<`[VV, S2]]) =
        new JsSink(underlying.contramap(f))

    def contramapChunk[VV, V2, S2](f: Function1[Chunk[V2], `<`[Chunk[VV], S2]]) =
        new JsSink(underlying.contramapChunk(f))

    def contramapChunkPure[VV, V2](f: Function1[Chunk[V2], Chunk[VV]]) =
        new JsSink(underlying.contramapChunkPure(f))

    def contramapPure[VV, V2](f: Function1[V2, VV]) =
        new JsSink(underlying.contramapPure(f))

    def drain[VV, S2](stream: JsStream[VV, S2]) =
        new JsKyo(underlying.drain(stream.underlying))

    def map[B, S2](f: Function1[A, `<`[B, S2]]) =
        new JsSink(underlying.map(f))

    def poll() =
        new JsKyo(underlying.poll)

    def zip[VV, B, S2](other: JsSink[VV, B, S2]) =
        new JsSink(underlying.zip(other.underlying))

    def zip[B, C, D, E, F, G, H, I, J](b: JsSink[V, B, S], c: JsSink[V, C, S], d: JsSink[V, D, S], e: JsSink[V, E, S], f: JsSink[V, F, S], g: JsSink[V, G, S], h: JsSink[V, H, S], i: JsSink[V, I, S], j: JsSink[V, J, S]) =
        new JsSink(Sink.zip(underlying)(b.underlying, c.underlying, d.underlying, e.underlying, f.underlying, g.underlying, h.underlying, i.underlying, j.underlying))

    def zip[B, C, D, E](b: JsSink[V, B, S], c: JsSink[V, C, S], d: JsSink[V, D, S], e: JsSink[V, E, S]) =
        new JsSink(Sink.zip(underlying)(b.underlying, c.underlying, d.underlying, e.underlying))

    def zip[B, C, D, E, F, G](b: JsSink[V, B, S], c: JsSink[V, C, S], d: JsSink[V, D, S], e: JsSink[V, E, S], f: JsSink[V, F, S], g: JsSink[V, G, S]) =
        new JsSink(Sink.zip(underlying)(b.underlying, c.underlying, d.underlying, e.underlying, f.underlying, g.underlying))

    def zip[B, C, D, E, F](b: JsSink[V, B, S], c: JsSink[V, C, S], d: JsSink[V, D, S], e: JsSink[V, E, S], f: JsSink[V, F, S]) =
        new JsSink(Sink.zip(underlying)(b.underlying, c.underlying, d.underlying, e.underlying, f.underlying))

    def zip[B, C, D, E, F, G, H](b: JsSink[V, B, S], c: JsSink[V, C, S], d: JsSink[V, D, S], e: JsSink[V, E, S], f: JsSink[V, F, S], g: JsSink[V, G, S], h: JsSink[V, H, S]) =
        new JsSink(Sink.zip(underlying)(b.underlying, c.underlying, d.underlying, e.underlying, f.underlying, g.underlying, h.underlying))

    def zip[B, C, D, E, F, G, H, I](b: JsSink[V, B, S], c: JsSink[V, C, S], d: JsSink[V, D, S], e: JsSink[V, E, S], f: JsSink[V, F, S], g: JsSink[V, G, S], h: JsSink[V, H, S], i: JsSink[V, I, S]) =
        new JsSink(Sink.zip(underlying)(b.underlying, c.underlying, d.underlying, e.underlying, f.underlying, g.underlying, h.underlying, i.underlying))

    def zip[B, C, D](b: JsSink[V, B, S], c: JsSink[V, C, S], d: JsSink[V, D, S]) =
        new JsSink(Sink.zip(underlying)(b.underlying, c.underlying, d.underlying))

    def zip[B, C](b: JsSink[V, B, S], c: JsSink[V, C, S]) =
        new JsSink(Sink.zip(underlying)(b.underlying, c.underlying))


end JsSink

object JsSink:
    import kyo.JsFacadeGivens.given

    @JSExportStatic
    def collect[V]() =
        new JsSink(Sink.collect)

    @JSExportStatic
    def count[V]() =
        new JsSink(Sink.count)

    @JSExportStatic
    def discard[V]() =
        new JsSink(Sink.discard)

    @JSExportStatic
    def empty[V]() =
        new JsSink(Sink.empty)

    @JSExportStatic
    def fold[A, V](acc: A, f: Function2[A, V, A]) =
        new JsSink(Sink.fold(acc)(f))

    @JSExportStatic
    def foldKyo[A, V, S](acc: A, f: Function2[A, V, `<`[A, S]]) =
        new JsSink(Sink.foldKyo(acc)(f))

    @JSExportStatic
    def foreach[V, S](f: Function1[V, `<`[Unit, S]]) =
        new JsSink(Sink.foreach(f))

    @JSExportStatic
    def foreachChunk[V, S](f: Function1[Chunk[V], `<`[Unit, S]]) =
        new JsSink(Sink.foreachChunk(f))


end JsSink