package kyo

import scala.scalajs.js
import scala.scalajs.js.annotation.*

@JSExportTopLevel("TChunk")
class JsTChunk[A](@JSName("$tchu") val underlying: TChunk[A]) extends js.Object:
    import kyo.JsFacadeGivens.given
    def append(value: A) =
        new JsKyo(TChunk.append(underlying)(value))

    def compact() =
        new JsKyo(TChunk.compact(underlying))

    def concat(other: JsChunk[A]) =
        new JsKyo(TChunk.concat(underlying)(other.underlying))

    def drop(n: Int) =
        new JsKyo(TChunk.drop(underlying)(n))

    def dropRight(n: Int) =
        new JsKyo(TChunk.dropRight(underlying)(n))

    def filter[S](p: Function1[A, `<`[Boolean, S]]) =
        new JsKyo(TChunk.filter(underlying)(p))

    def get(index: Int) =
        new JsKyo(TChunk.get(underlying)(index))

    def head() =
        new JsKyo(TChunk.head(underlying))

    def isEmpty() =
        new JsKyo(TChunk.isEmpty(underlying))

    def last() =
        new JsKyo(TChunk.last(underlying))

    def size() =
        new JsKyo(TChunk.size(underlying))

    def slice(from: Int, until: Int) =
        new JsKyo(TChunk.slice(underlying)(from, until))

    def snapshot() =
        new JsKyo(TChunk.snapshot(underlying))

    def take(n: Int) =
        new JsKyo(TChunk.take(underlying)(n))

    def use[B, S](f: Function1[Chunk[A], `<`[B, S]]) =
        new JsKyo(TChunk.use(underlying)(f))


end JsTChunk

object JsTChunk:
    import kyo.JsFacadeGivens.given

    @JSExportStatic
    def init[A](values: Seq[A]) =
        new JsKyo(TChunk.init(values*))

    @JSExportStatic
    def init[A]() =
        new JsKyo(TChunk.init)


end JsTChunk