package kyo

import scala.scalajs.js
import scala.scalajs.js.annotation.*

@JSExportTopLevel("Log")
class JsLog(@JSName("$log") val underlying: Log) extends js.Object:
    import kyo.JsFacadeGivens.given
    def debug(msg: js.Function0[JsText], t: js.Function0[Throwable]) =
        new JsKyo(underlying.debug(msg().underlying, t()))

    def debug(msg: js.Function0[JsText]) =
        new JsKyo(underlying.debug(msg().underlying))

    def error(msg: js.Function0[JsText]) =
        new JsKyo(underlying.error(msg().underlying))

    def error(msg: js.Function0[JsText], t: js.Function0[Throwable]) =
        new JsKyo(underlying.error(msg().underlying, t()))

    def info(msg: js.Function0[JsText]) =
        new JsKyo(underlying.info(msg().underlying))

    def info(msg: js.Function0[JsText], t: js.Function0[Throwable]) =
        new JsKyo(underlying.info(msg().underlying, t()))

    def let_[A, S](f: JsKyo[A, S]) =
        new JsKyo(Log.let(underlying)(f.underlying))

    def level() =
        underlying.level

    def trace(msg: js.Function0[JsText], t: js.Function0[Throwable]) =
        new JsKyo(underlying.trace(msg().underlying, t()))

    def trace(msg: js.Function0[JsText]) =
        new JsKyo(underlying.trace(msg().underlying))

    def unsafe() =
        underlying.unsafe

    def warn(msg: js.Function0[JsText], t: js.Function0[Throwable]) =
        new JsKyo(underlying.warn(msg().underlying, t()))

    def warn(msg: js.Function0[JsText]) =
        new JsKyo(underlying.warn(msg().underlying))


end JsLog

object JsLog:
    import kyo.JsFacadeGivens.given

    @JSExportStatic
    def apply(unsafe: Log.Unsafe) =
        new JsLog(Log.apply(unsafe))

    @JSExportStatic
    def get() =
        new JsKyo(Log.get)

    @JSExportStatic
    def live() =
        new JsLog(Log.live)

    @JSExportStatic
    def use[A, S](f: Function1[Log, `<`[A, S]]) =
        new JsKyo(Log.use(f))


end JsLog