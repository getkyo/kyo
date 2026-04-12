package kyo

import scala.scalajs.js
import scala.scalajs.js.annotation.*

@JSExportTopLevel("Dict")
class JsDict[K, V](@JSName("$dict") val underlying: Dict[K, V]) extends js.Object:
    import kyo.JsFacadeGivens.given
    private given [T]: scala.reflect.ClassTag[T] = scala.reflect.ClassTag[T](classOf[AnyRef])
    def apply(key: K) =
        Dict.apply(underlying)(key)

    def collect[K2, V2](pf: PartialFunction[Tuple2[K, V], Tuple2[K2, V2]]) =
        new JsDict(Dict.collect(underlying)(pf))

    def concat(other: JsDict[K, V]) =
        new JsDict(Dict.`++`(underlying)(other.underlying))

    def contains(key: K) =
        Dict.contains(underlying)(key)

    def count(fn: Function2[K, V, Boolean]) =
        Dict.count(underlying)(fn)

    def exists(fn: Function2[K, V, Boolean]) =
        Dict.exists(underlying)(fn)

    def filter(fn: Function2[K, V, Boolean]) =
        new JsDict(Dict.filter(underlying)(fn))

    def filterNot(fn: Function2[K, V, Boolean]) =
        new JsDict(Dict.filterNot(underlying)(fn))

    def find(fn: Function2[K, V, Boolean]) =
        new JsMaybe(Dict.find(underlying)(fn))

    def flatMap[K2, V2](fn: Function2[K, V, Dict[K2, V2]]) =
        new JsDict(Dict.flatMap(underlying)(fn))

    def foldLeft[B](z: B, fn: Function3[B, K, V, B]) =
        Dict.foldLeft(underlying)(z)(fn)

    def forall(fn: Function2[K, V, Boolean]) =
        Dict.forall(underlying)(fn)

    def foreach(fn: Function2[K, V, Unit]) =
        Dict.foreach(underlying)(fn)

    def foreachKey(fn: Function1[K, Unit]) =
        Dict.foreachKey(underlying)(fn)

    def foreachValue(fn: Function1[V, Unit]) =
        Dict.foreachValue(underlying)(fn)

    def get(key: K) =
        new JsMaybe(Dict.get(underlying)(key))

    def getOrElse(key: K, default: js.Function0[V]) =
        Dict.getOrElse(underlying)(key, default())

    def is(other: JsDict[K, V]) =
        Dict.is(underlying)(other.underlying)

    def isEmpty() =
        Dict.isEmpty(underlying)

    def keys() =
        new JsSpan(Dict.keys(underlying))

    def map[K2, V2](fn: Function2[K, V, Tuple2[K2, V2]]) =
        new JsDict(Dict.map(underlying)(fn))

    def mapValues[V2](fn: Function1[V, V2]) =
        new JsDict(Dict.mapValues(underlying)(fn))

    def mkString() =
        Dict.mkString(underlying)

    def mkString(start: Predef.String, sep: Predef.String, end: Predef.String) =
        Dict.mkString(underlying)(start, sep, end)

    def mkString(separator: Predef.String) =
        Dict.mkString(underlying)(separator)

    def nonEmpty() =
        Dict.nonEmpty(underlying)

    def remove(key: K) =
        new JsDict(Dict.remove(underlying)(key))

    def size() =
        Dict.size(underlying)

    def toMap() =
        Dict.toMap(underlying)

    def update(key: K, value: V) =
        new JsDict(Dict.update(underlying)(key, value))


end JsDict

object JsDict:
    import kyo.JsFacadeGivens.given

    @JSExportStatic
    def apply[K, V](entries: Seq[Tuple2[K, V]]) =
        new JsDict(Dict.apply(entries*))

    @JSExportStatic
    def empty[K, V]() =
        new JsDict(Dict.empty)

    @JSExportStatic
    def from[K, V](map: Predef.Map[K, V]) =
        new JsDict(Dict.from(map))


end JsDict