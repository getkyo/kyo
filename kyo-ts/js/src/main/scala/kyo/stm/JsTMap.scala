package kyo

import scala.scalajs.js
import scala.scalajs.js.annotation.*

@JSExportTopLevel("TMap")
class JsTMap[K, V](@JSName("$tmap") val underlying: TMap[K, V]) extends js.Object:
    import kyo.JsFacadeGivens.given
    def clear() =
        new JsKyo(TMap.clear(underlying))

    def contains(key: K) =
        new JsKyo(TMap.contains(underlying)(key))

    def entries() =
        new JsKyo(TMap.entries(underlying))

    def filter[S](p: Function2[K, V, `<`[Boolean, S]]) =
        new JsKyo(TMap.filter(underlying)(p))

    def findFirst[A, S](f: Function2[K, V, `<`[Maybe[A], S]]) =
        new JsKyo(TMap.findFirst(underlying)(f))

    def fold[A, B, S](acc: A, f: Function3[A, K, V, `<`[A, S]]) =
        new JsKyo(TMap.fold(underlying)(acc)(f))

    def get(key: K) =
        new JsKyo(TMap.get(underlying)(key))

    def getOrElse[A, S](key: K, orElse: js.Function0[JsKyo[V, S]]) =
        new JsKyo(TMap.getOrElse(underlying)(key, orElse().underlying))

    def isEmpty() =
        new JsKyo(TMap.isEmpty(underlying))

    def keys() =
        new JsKyo(TMap.keys(underlying))

    def nonEmpty() =
        new JsKyo(TMap.nonEmpty(underlying))

    def put(key: K, value: V) =
        new JsKyo(TMap.put(underlying)(key, value))

    def remove(key: K) =
        new JsKyo(TMap.remove(underlying)(key))

    def removeAll(keys: Seq[K]) =
        new JsKyo(TMap.removeAll(underlying)(keys))

    def removeDiscard(key: K) =
        new JsKyo(TMap.removeDiscard(underlying)(key))

    def size() =
        new JsKyo(TMap.size(underlying))

    def snapshot() =
        new JsKyo(TMap.snapshot(underlying))

    def updateWith[S](key: K, f: Function1[Maybe[V], `<`[Maybe[V], S]]) =
        new JsKyo(TMap.updateWith(underlying)(key)(f))

    def use[A, S](key: K, f: Function1[Maybe[V], `<`[A, S]]) =
        new JsKyo(TMap.use(underlying)(key)(f))


end JsTMap

object JsTMap:
    import kyo.JsFacadeGivens.given

    @JSExportStatic
    def init[K, V](entries: Seq[Tuple2[K, V]]) =
        new JsKyo(TMap.init(entries*))

    @JSExportStatic
    def init[K, V]() =
        new JsKyo(TMap.init)

    @JSExportStatic
    def initWith[K, V, A, S](entries: Seq[Tuple2[K, V]], f: Function1[TMap[K, V], `<`[A, S]]) =
        new JsKyo(TMap.initWith(entries*)(f))


end JsTMap