package kyo

import scala.scalajs.js
import scala.scalajs.js.annotation.*

@JSExportTopLevel("TypeMap")
class JsTypeMap[A](@JSName("$type") val underlying: TypeMap[A]) extends js.Object:
    import kyo.JsFacadeGivens.given
    def add[B](b: B) =
        new JsTypeMap(TypeMap.add(underlying)(b))

    def get[B]() =
        TypeMap.get(underlying)

    def isEmpty() =
        TypeMap.isEmpty(underlying)

    def prune[B]() =
        new JsTypeMap(TypeMap.prune(underlying))

    def show() =
        TypeMap.show(underlying)

    def size() =
        TypeMap.size(underlying)

    def union[B](that: JsTypeMap[B]) =
        new JsTypeMap(TypeMap.union(underlying)(that.underlying))


end JsTypeMap

object JsTypeMap:
    import kyo.JsFacadeGivens.given

    @JSExportStatic
    def apply[A, B](a: A, b: B) =
        new JsTypeMap(TypeMap.apply(a, b))

    @JSExportStatic
    def apply[A, B, C, D](a: A, b: B, c: C, d: D) =
        new JsTypeMap(TypeMap.apply(a, b, c, d))

    @JSExportStatic
    def apply[A, B, C](a: A, b: B, c: C) =
        new JsTypeMap(TypeMap.apply(a, b, c))

    @JSExportStatic
    def apply[A](a: A) =
        new JsTypeMap(TypeMap.apply(a))

    @JSExportStatic
    def empty() =
        new JsTypeMap(TypeMap.empty)


end JsTypeMap