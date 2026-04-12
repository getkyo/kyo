package kyo

import scala.scalajs.js
import scala.scalajs.js.annotation.*

@JSExportTopLevel("Abort")
class JsAbort[E](@JSName("$abor") val underlying: Abort[E]) extends js.Object:
    import kyo.JsFacadeGivens.given

end JsAbort

object JsAbort:
    import kyo.JsFacadeGivens.given

    @JSExportStatic
    def catching[E, A, S, E1](f: Function1[E, E1], v: js.Function0[JsKyo[A, S]]) =
        new JsKyo(Abort.catching(f)(v().underlying))

    @JSExportStatic
    def eliminateAbort() =
        Abort.eliminateAbort

    @JSExportStatic
    def error[E](error: Result.Error[E]) =
        new JsKyo(Abort.error(error))

    @JSExportStatic
    def fail[E](value: E) =
        new JsKyo(Abort.fail(value))

    @JSExportStatic
    def fold[E, A, B, S, ER](onSuccess: Function1[A, `<`[B, S]], onFail: Function1[E, `<`[B, S]], v: js.Function0[JsKyo[A, `&`[Abort[`|`[E, ER]], S]]]) =
        new JsKyo(Abort.fold(onSuccess, onFail)(v().underlying))

    @JSExportStatic
    def fold[E, A, B, S, ER](onSuccess: Function1[A, `<`[B, S]], onFail: Function1[E, `<`[B, S]], onPanic: Function1[Throwable, `<`[B, S]], v: js.Function0[JsKyo[A, `&`[Abort[`|`[E, ER]], S]]]) =
        new JsKyo(Abort.fold(onSuccess, onFail, onPanic)(v().underlying))

    @JSExportStatic
    def foldError[E, A, B, S, ER](onSuccess: Function1[A, `<`[B, S]], onError: Function1[Result.Error[E], `<`[B, S]], v: js.Function0[JsKyo[A, `&`[Abort[`|`[E, ER]], S]]]) =
        new JsKyo(Abort.foldError(onSuccess, onError)(v().underlying))

    @JSExportStatic
    def foldOrThrow[A, B, E, S](onSuccess: Function1[A, `<`[B, S]], onFail: Function1[E, `<`[B, S]], v: js.Function0[JsKyo[A, `&`[Abort[E], S]]]) =
        new JsKyo(Abort.foldOrThrow(onSuccess, onFail)(v().underlying))

    @JSExportStatic
    def get[E, A](either: Either[E, A]) =
        new JsKyo(Abort.get(either))

    @JSExportStatic
    def literal() =
        Abort.literal

    @JSExportStatic
    def panic[E](ex: Throwable) =
        new JsKyo(Abort.panic(ex))

    @JSExportStatic
    def recover[E, A, B, S, ER](onFail: Function1[E, `<`[B, S]], onPanic: Function1[Throwable, `<`[B, S]], v: js.Function0[JsKyo[A, `&`[Abort[`|`[E, ER]], S]]]) =
        new JsKyo(Abort.recover(onFail, onPanic)(v().underlying))

    @JSExportStatic
    def recover[E, A, B, S, ER](onFail: Function1[E, `<`[B, S]], v: js.Function0[JsKyo[A, `&`[Abort[`|`[E, ER]], S]]]) =
        new JsKyo(Abort.recover(onFail)(v().underlying))

    @JSExportStatic
    def recoverError[E, A, B, S, ER](onError: Function1[Result.Error[E], `<`[B, S]], v: js.Function0[JsKyo[A, `&`[Abort[`|`[E, ER]], S]]]) =
        new JsKyo(Abort.recoverError(onError)(v().underlying))

    @JSExportStatic
    def recoverOrThrow[A, E, B, S](onFail: Function1[E, `<`[B, S]], v: js.Function0[JsKyo[A, `&`[Abort[E], S]]]) =
        new JsKyo(Abort.recoverOrThrow(onFail)(v().underlying))


end JsAbort