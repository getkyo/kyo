package kyo

import scala.scalajs.js
import scala.scalajs.js.annotation.*

@JSExportTopLevel("Result")
class JsResult[E, A](@JSName("$resu") val underlying: Result[E, A]) extends js.Object:
    import kyo.JsFacadeGivens.given
    def contains(value: A) =
        Result.contains(underlying)(value)

    def error() =
        new JsMaybe(Result.error(underlying))

    def exists(pred: Function1[A, Boolean]) =
        Result.exists(underlying)(pred)

    def failure() =
        new JsMaybe(Result.failure(underlying))

    def failureOrPanic() =
        new JsMaybe(Result.failureOrPanic(underlying))

    def filter(p: Function1[A, Boolean]) =
        new JsResult(Result.filter(underlying)(p))

    def flatMap[E2, B](f: Function1[A, Result[E2, B]]) =
        new JsResult(Result.flatMap(underlying)(f))

    def flatMapPanic[B, E2](f: Function1[Throwable, Result[E2, B]]) =
        new JsResult(Result.flatMapPanic(underlying)(f))

    def fold[B](onSuccess: Function1[A, B], onFailure: Function1[E, B], onPanic: Function1[Throwable, B]) =
        Result.fold(underlying)(onSuccess, onFailure, onPanic)

    def foldError[B](onSuccess: Function1[A, B], onError: Function1[Result.Error[E], B]) =
        Result.foldError(underlying)(onSuccess, onError)

    def foldOrThrow[B](onSuccess: Function1[A, B], onFailure: Function1[E, B]) =
        Result.foldOrThrow(underlying)(onSuccess, onFailure)

    def forall(pred: Function1[A, Boolean]) =
        Result.forall(underlying)(pred)

    def foreach(f: Function1[A, Unit]) =
        Result.foreach(underlying)(f)

    def getOrElse[B](default: js.Function0[B]) =
        Result.getOrElse(underlying)(default())

    def isError() =
        Result.isError(underlying)

    def isFailure() =
        Result.isFailure(underlying)

    def isPanic() =
        Result.isPanic(underlying)

    def isSuccess() =
        Result.isSuccess(underlying)

    def map[B](f: Function1[A, B]) =
        new JsResult(Result.map(underlying)(f))

    def mapError[E2](f: Function1[Result.Error[E], E2]) =
        new JsResult(Result.mapError(underlying)(f))

    def mapFailure[E2](f: Function1[E, E2]) =
        new JsResult(Result.mapFailure(underlying)(f))

    def mapPanic[E2](f: Function1[Throwable, E2]) =
        new JsResult(Result.mapPanic(underlying)(f))

    def orElse[E2, B](alternative: js.Function0[JsResult[E2, B]]) =
        new JsResult(Result.orElse(underlying)(alternative().underlying))

    def panic() =
        new JsMaybe(Result.panic(underlying))

    def show() =
        Result.show(underlying)

    def swap() =
        new JsResult(Result.swap(underlying))

    def toEither() =
        Result.toEither(underlying)

    def toMaybe() =
        new JsMaybe(Result.toMaybe(underlying))

    def unit() =
        new JsResult(Result.unit(underlying))

    def value() =
        new JsMaybe(Result.value(underlying))

    def withFilter(p: Function1[A, Boolean]) =
        new JsResult(Result.withFilter(underlying)(p))


end JsResult

object JsResult:
    import kyo.JsFacadeGivens.given

    @JSExportStatic
    def absent[A]() =
        new JsResult(Result.absent)

    @JSExportStatic
    def apply[A](expr: js.Function0[A]) =
        new JsResult(Result.apply(expr()))

    @JSExportStatic
    def catching[E, A](expr: js.Function0[A]) =
        new JsResult(Result.catching(expr()))

    @JSExportStatic
    def collect[E, A](seq: Seq[Result[E, A]]) =
        new JsResult(Result.collect(seq))

    @JSExportStatic
    def fail[E, A](error: E) =
        new JsResult(Result.fail(error))

    @JSExportStatic
    def fromEither[E, A](either: Either[E, A]) =
        new JsResult(Result.fromEither(either))

    @JSExportStatic
    def fromTry[A](t: scala.util.Try[A]) =
        new JsResult(Result.fromTry(t))

    @JSExportStatic
    def given_CanEqual_Result_Panic[E, A]() =
        Result.given_CanEqual_Result_Panic

    @JSExportStatic
    def given_CanEqual_Result_Result[E, A]() =
        Result.given_CanEqual_Result_Result

    @JSExportStatic
    def given_Render_ResultEA[E, A, ResultEA]() =
        Result.given_Render_ResultEA

    @JSExportStatic
    def panic[E, A](exception: Throwable) =
        new JsResult(Result.panic(exception))

    @JSExportStatic
    def succeed[E, A](value: A) =
        new JsResult(Result.succeed(value))

    @JSExportStatic
    def successValue[A](self: Result.Success[A]) =
        Result.successValue(self)

    @JSExportStatic
    def unit[E]() =
        new JsResult(Result.unit)


end JsResult