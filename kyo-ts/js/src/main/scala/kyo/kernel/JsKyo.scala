package kyo

import scala.scalajs.js
import scala.scalajs.js.annotation.*

@JSExportTopLevel("Kyo")
class JsKyo[A, S](@JSName("$kyo") val underlying: `<`[A, S]) extends js.Object:
    import kyo.JsFacadeGivens.given
    def later() =
        new JsKyo(`<`.later(underlying))

    def now() =
        `<`.now(underlying)

    def unless[S1](ifFalse: js.Function0[JsKyo[A, S1]]) =
        new JsKyo(`<`.unless(underlying)(ifFalse().underlying))

    def when[S1](ifTrue: js.Function0[JsKyo[A, S1]]) =
        new JsKyo(`<`.when(underlying)(ifTrue().underlying))

    def when[S1](ifTrue: js.Function0[JsKyo[A, S1]], ifFalse: js.Function0[JsKyo[A, S1]]) =
        new JsKyo(`<`.when(underlying)(ifTrue().underlying, ifFalse().underlying))

    def zip[A1, A2, A3, A4, A5](v2: JsKyo[A2, S], v3: JsKyo[A3, S], v4: JsKyo[A4, S], v5: JsKyo[A5, S]) =
        new JsKyo(`<`.zip(underlying)(v2.underlying, v3.underlying, v4.underlying, v5.underlying))

    def zip[A1, A2, A3](v2: JsKyo[A2, S], v3: JsKyo[A3, S]) =
        new JsKyo(`<`.zip(underlying)(v2.underlying, v3.underlying))

    def zip[A1, A2, A3, A4](v2: JsKyo[A2, S], v3: JsKyo[A3, S], v4: JsKyo[A4, S]) =
        new JsKyo(`<`.zip(underlying)(v2.underlying, v3.underlying, v4.underlying))

    def zip[A1, A2, A3, A4, A5, A6, A7, A8, A9, A10](v2: JsKyo[A2, S], v3: JsKyo[A3, S], v4: JsKyo[A4, S], v5: JsKyo[A5, S], v6: JsKyo[A6, S], v7: JsKyo[A7, S], v8: JsKyo[A8, S], v9: JsKyo[A9, S], v10: JsKyo[A10, S]) =
        new JsKyo(`<`.zip(underlying)(v2.underlying, v3.underlying, v4.underlying, v5.underlying, v6.underlying, v7.underlying, v8.underlying, v9.underlying, v10.underlying))

    def zip[A1, A2, A3, A4, A5, A6](v2: JsKyo[A2, S], v3: JsKyo[A3, S], v4: JsKyo[A4, S], v5: JsKyo[A5, S], v6: JsKyo[A6, S]) =
        new JsKyo(`<`.zip(underlying)(v2.underlying, v3.underlying, v4.underlying, v5.underlying, v6.underlying))

    def zip[A1, A2, A3, A4, A5, A6, A7, A8](v2: JsKyo[A2, S], v3: JsKyo[A3, S], v4: JsKyo[A4, S], v5: JsKyo[A5, S], v6: JsKyo[A6, S], v7: JsKyo[A7, S], v8: JsKyo[A8, S]) =
        new JsKyo(`<`.zip(underlying)(v2.underlying, v3.underlying, v4.underlying, v5.underlying, v6.underlying, v7.underlying, v8.underlying))

    def zip[A1, A2, A3, A4, A5, A6, A7, A8, A9](v2: JsKyo[A2, S], v3: JsKyo[A3, S], v4: JsKyo[A4, S], v5: JsKyo[A5, S], v6: JsKyo[A6, S], v7: JsKyo[A7, S], v8: JsKyo[A8, S], v9: JsKyo[A9, S]) =
        new JsKyo(`<`.zip(underlying)(v2.underlying, v3.underlying, v4.underlying, v5.underlying, v6.underlying, v7.underlying, v8.underlying, v9.underlying))

    def zip[A1, A2, A3, A4, A5, A6, A7](v2: JsKyo[A2, S], v3: JsKyo[A3, S], v4: JsKyo[A4, S], v5: JsKyo[A5, S], v6: JsKyo[A6, S], v7: JsKyo[A7, S]) =
        new JsKyo(`<`.zip(underlying)(v2.underlying, v3.underlying, v4.underlying, v5.underlying, v6.underlying, v7.underlying))

    def zip[A1, A2](v2: JsKyo[A2, S]) =
        new JsKyo(`<`.zip(underlying)(v2.underlying))


end JsKyo

object JsKyo:
    import kyo.JsFacadeGivens.given

    @JSExportStatic
    def collect[A, B, S](source: Predef.Set[A], f: ContextFunction1[kyo.kernel.internal.Safepoint, Function1[A, `<`[Maybe[B], S]]]) =
        new JsKyo(`<`.collect(source)(f))

    @JSExportStatic
    def collectAll[CC, A, S](source: CC[`<`[A, S]]) =
        new JsKyo(`<`.collectAll(source))

    @JSExportStatic
    def collectAllDiscard[K1, V1, S](source: Predef.Map[K1, `<`[V1, S]]) =
        new JsKyo(`<`.collectAllDiscard(source))

    @JSExportStatic
    def dropWhile[A, S](source: Seq[A], f: ContextFunction1[kyo.kernel.internal.Safepoint, Function1[A, `<`[Boolean, S]]]) =
        new JsKyo(`<`.dropWhile(source)(f))

    @JSExportStatic
    def filter[CC, A, S](source: CC[A], f: ContextFunction1[kyo.kernel.internal.Safepoint, Function1[A, `<`[Boolean, S]]]) =
        new JsKyo(`<`.filter(source)(f))

    @JSExportStatic
    def filterKeys[K1, V1, S](source: Predef.Map[K1, V1], f: ContextFunction1[kyo.kernel.internal.Safepoint, Function1[K1, `<`[Boolean, S]]]) =
        new JsKyo(`<`.filterKeys(source)(f))

    @JSExportStatic
    def findFirst[K1, V1, B, S](source: Predef.Map[K1, V1], f: ContextFunction1[kyo.kernel.internal.Safepoint, Function1[Tuple2[K1, V1], `<`[Maybe[B], S]]]) =
        new JsKyo(`<`.findFirst(source)(f))

    @JSExportStatic
    def foldLeft[CC, A, B, S](source: CC[A], acc: B, f: ContextFunction1[kyo.kernel.internal.Safepoint, Function2[B, A, `<`[B, S]]]) =
        new JsKyo(`<`.foldLeft(source)(acc)(f))

    @JSExportStatic
    def foreach[A, B, S](source: List[A], f: ContextFunction1[kyo.kernel.internal.Safepoint, Function1[A, `<`[B, S]]]) =
        new JsKyo(`<`.foreach(source)(f))

    @JSExportStatic
    def foreachConcat[K1, V1, K2, V2, S](source: Predef.Map[K1, V1], f: ContextFunction1[kyo.kernel.internal.Safepoint, Function1[Tuple2[K1, V1], `<`[IterableOnce[Tuple2[K2, V2]], S]]]) =
        new JsKyo(`<`.foreachConcat(source)(f))

    @JSExportStatic
    def foreachDiscard[A, B, S](source: Seq[A], f: ContextFunction1[kyo.kernel.internal.Safepoint, Function1[A, `<`[Any, S]]]) =
        new JsKyo(`<`.foreachDiscard(source)(f))

    @JSExportStatic
    def foreachIndexed[A, B, S](source: List[A], f: ContextFunction1[kyo.kernel.internal.Safepoint, Function2[Int, A, `<`[B, S]]]) =
        new JsKyo(`<`.foreachIndexed(source)(f))

    @JSExportStatic
    def groupBy[S, A, K](source: Predef.Set[A], f: ContextFunction1[kyo.kernel.internal.Safepoint, Function1[A, `<`[K, S]]]) =
        new JsKyo(`<`.groupBy(source)(f))

    @JSExportStatic
    def groupMap[CC, A, K, B, S](source: CC[A], key: ContextFunction1[kyo.kernel.internal.Safepoint, Function1[A, `<`[K, S]]], f: ContextFunction1[kyo.kernel.internal.Safepoint, Function1[A, `<`[B, S]]]) =
        new JsKyo(`<`.groupMap(source)(key)(f))

    @JSExportStatic
    def lift[A, S](v: A) =
        new JsKyo(`<`.lift(v))

    @JSExportStatic
    def partition[K1, V1, S](source: Predef.Map[K1, V1], f: ContextFunction1[kyo.kernel.internal.Safepoint, Function1[Tuple2[K1, V1], `<`[Boolean, S]]]) =
        new JsKyo(`<`.partition(source)(f))

    @JSExportStatic
    def partitionMap[S, A, A1, A2](source: List[A], f: ContextFunction1[kyo.kernel.internal.Safepoint, Function1[A, `<`[Either[A1, A2], S]]]) =
        new JsKyo(`<`.partitionMap(source)(f))

    @JSExportStatic
    def scanLeft[K1, V1, B, S](source: Predef.Map[K1, V1], z: B, op: ContextFunction1[kyo.kernel.internal.Safepoint, Function2[B, Tuple2[K1, V1], `<`[B, S]]]) =
        new JsKyo(`<`.scanLeft(source)(z)(op))

    @JSExportStatic
    def span[CC, A, S](source: CC[A], f: ContextFunction1[kyo.kernel.internal.Safepoint, Function1[A, `<`[Boolean, S]]]) =
        new JsKyo(`<`.span(source)(f))

    @JSExportStatic
    def takeWhile[A, S](source: Seq[A], f: ContextFunction1[kyo.kernel.internal.Safepoint, Function1[A, `<`[Boolean, S]]]) =
        new JsKyo(`<`.takeWhile(source)(f))

    @JSExportStatic
    def unit() =
        new JsKyo(`<`.unit)


end JsKyo