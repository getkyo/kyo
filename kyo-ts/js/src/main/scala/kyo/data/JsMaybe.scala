package kyo

import scala.scalajs.js
import scala.scalajs.js.annotation.*

@JSExportTopLevel("Maybe")
class JsMaybe[A](@JSName("$mayb") val underlying: Maybe[A]) extends js.Object:
    import kyo.JsFacadeGivens.given
    def Ops() =
        Maybe.Ops(underlying)

    def collect[B](pf: PartialFunction[A, B]) =
        new JsMaybe(Maybe.collect(underlying)(pf))

    def contains[B](elem: B) =
        Maybe.contains(underlying)(elem)

    def exists(f: Function1[A, Boolean]) =
        Maybe.exists(underlying)(f)

    def filter(f: Function1[A, Boolean]) =
        new JsMaybe(Maybe.filter(underlying)(f))

    def filterNot(f: Function1[A, Boolean]) =
        new JsMaybe(Maybe.filterNot(underlying)(f))

    def flatMap[B](f: Function1[A, Maybe[B]]) =
        new JsMaybe(Maybe.flatMap(underlying)(f))

    def fold[B](ifEmpty: js.Function0[B], ifDefined: Function1[A, B]) =
        Maybe.fold(underlying)(ifEmpty())(ifDefined)

    def forall(f: Function1[A, Boolean]) =
        Maybe.forall(underlying)(f)

    def foreach(f: Function1[A, Unit]) =
        Maybe.foreach(underlying)(f)

    def get() =
        Maybe.get(underlying)

    def getOrElse[B](default: js.Function0[B]) =
        Maybe.getOrElse(underlying)(default())

    def isDefined() =
        Maybe.isDefined(underlying)

    def isEmpty() =
        Maybe.isEmpty(underlying)

    def iterator() =
        Maybe.iterator(underlying)

    def map[B](f: Function1[A, B]) =
        new JsMaybe(Maybe.map(underlying)(f))

    def nonEmpty() =
        Maybe.nonEmpty(underlying)

    def orElse[B](alternative: js.Function0[JsMaybe[B]]) =
        new JsMaybe(Maybe.orElse(underlying)(alternative().underlying))

    def show() =
        Maybe.show(underlying)

    def toChunk() =
        new JsChunk(Maybe.toChunk(underlying))

    def toIterableOnce() =
        Maybe.toIterableOnce(underlying)

    def toLeft[X](right: js.Function0[X]) =
        Maybe.toLeft(underlying)(right())

    def toList() =
        Maybe.toList(underlying)

    def toOption() =
        Maybe.toOption(underlying)

    def toResult[E](ifEmpty: js.Function0[JsResult[E, A]]) =
        new JsResult(Maybe.toResult(underlying)(ifEmpty().underlying))

    def toResult[E]() =
        new JsResult(Maybe.toResult(underlying))

    def toRight[X](left: js.Function0[X]) =
        Maybe.toRight(underlying)(left())

    def withFilter(f: Function1[A, Boolean]) =
        new JsMaybe(Maybe.withFilter(underlying)(f))

    def zip[B](that: JsMaybe[B]) =
        new JsMaybe(Maybe.zip(underlying)(that.underlying))


end JsMaybe

object JsMaybe:
    import kyo.JsFacadeGivens.given

    @JSExportStatic
    def apply[A](v: A) =
        new JsMaybe(Maybe.apply(v))

    @JSExportStatic
    def empty[A]() =
        new JsMaybe(Maybe.empty)

    @JSExportStatic
    def fromOption[A](opt: Option[A]) =
        new JsMaybe(Maybe.fromOption(opt))

    @JSExportStatic
    def given_CanEqual_Maybe_Maybe[A, B]() =
        Maybe.given_CanEqual_Maybe_Maybe

    @JSExportStatic
    def given_Render_MaybeA[A, MaybeA]() =
        Maybe.given_Render_MaybeA


end JsMaybe