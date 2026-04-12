package kyo

import scala.scalajs.js
import scala.scalajs.js.annotation.*

@JSExportTopLevel("Sync")
class JsSync(@JSName("$sync") val underlying: Sync) extends js.Object:
    import kyo.JsFacadeGivens.given

end JsSync

object JsSync:
    import kyo.JsFacadeGivens.given

    @JSExportStatic
    def defer[A, S](f: ContextFunction1[kyo.kernel.internal.Safepoint, kyo.kernel.`<`[A, S]]) =
        new JsKyo(Sync.defer(f))

    @JSExportStatic
    def ensure[A, S](f: Function1[Maybe[Result.Error[Any]], kyo.kernel.`<`[Any, `&`[Sync, Abort[Throwable]]]], v: js.Function0[JsKyo[A, S]]) =
        new JsKyo(Sync.ensure(f)(v().underlying))


end JsSync