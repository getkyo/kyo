package kyo

import scala.scalajs.js
import scala.scalajs.js.annotation.*

@JSExportTopLevel("PlatformBackend")
object JsPlatformBackend extends js.Object:
    import kyo.JsFacadeGivens.given

    @JSExportStatic
    def default_() =
        PlatformBackend.default


end JsPlatformBackend