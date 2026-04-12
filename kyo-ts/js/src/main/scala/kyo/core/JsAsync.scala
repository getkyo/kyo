package kyo

import scala.scalajs.js
import scala.scalajs.js.annotation.*

@JSExportTopLevel("Async")
class JsAsync(@JSName("$asyn") val underlying: Async) extends js.Object:
    import kyo.JsFacadeGivens.given

end JsAsync

object JsAsync:
    import kyo.JsFacadeGivens.given

    @JSExportStatic
    def collect[E, A, B, S](iterable: Iterable[A], concurrency: Int, f: Function1[A, kyo.kernel.`<`[Maybe[B], `&`[`&`[Abort[E], Async], S]]]) =
        new JsKyo(Async.collect(iterable, concurrency)(f))

    @JSExportStatic
    def collectAll[E, A, S](iterable: Iterable[kyo.kernel.`<`[A, `&`[`&`[Abort[E], Async], S]]], concurrency: Int) =
        new JsKyo(Async.collectAll(iterable, concurrency))

    @JSExportStatic
    def collectAllDiscard[E, A, S](iterable: Iterable[kyo.kernel.`<`[A, `&`[`&`[Abort[E], Async], S]]], concurrency: Int) =
        new JsKyo(Async.collectAllDiscard(iterable, concurrency))

    @JSExportStatic
    def defaultConcurrency() =
        Async.defaultConcurrency

    @JSExportStatic
    def filter[E, A, S](iterable: Iterable[A], concurrency: Int, f: Function1[A, kyo.kernel.`<`[Boolean, `&`[`&`[Abort[E], Async], S]]]) =
        new JsKyo(Async.filter(iterable, concurrency)(f))

    @JSExportStatic
    def foreach[E, A, B, S](iterable: Iterable[A], concurrency: Int, f: Function1[A, kyo.kernel.`<`[B, `&`[`&`[Abort[E], Async], S]]]) =
        new JsKyo(Async.foreach(iterable, concurrency)(f))

    @JSExportStatic
    def foreachDiscard[E, A, B, S](iterable: Iterable[A], concurrency: Int, f: Function1[A, kyo.kernel.`<`[B, `&`[`&`[Abort[E], Async], S]]]) =
        new JsKyo(Async.foreachDiscard(iterable, concurrency)(f))

    @JSExportStatic
    def foreachIndexed[E, A, B, S](iterable: Iterable[A], concurrency: Int, f: Function2[Int, A, kyo.kernel.`<`[B, `&`[`&`[Abort[E], Async], S]]]) =
        new JsKyo(Async.foreachIndexed(iterable, concurrency)(f))

    @JSExportStatic
    def gather[E, A, S](iterable: Iterable[kyo.kernel.`<`[A, `&`[`&`[Abort[E], Async], S]]]) =
        new JsKyo(Async.gather(iterable))

    @JSExportStatic
    def never[A]() =
        new JsKyo(Async.never)

    @JSExportStatic
    def race[E, A, S](iterable: Iterable[kyo.kernel.`<`[A, `&`[`&`[Abort[E], Async], S]]]) =
        new JsKyo(Async.race(iterable))

    @JSExportStatic
    def raceFirst[E, A, S](iterable: Iterable[kyo.kernel.`<`[A, `&`[`&`[Abort[E], Async], S]]]) =
        new JsKyo(Async.raceFirst(iterable))


end JsAsync