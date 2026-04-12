package kyo

import scala.scalajs.js
import scala.scalajs.js.annotation.*

@JSExportTopLevel("System")
class JsSystem(@JSName("$syst") val underlying: System) extends js.Object:
    import kyo.JsFacadeGivens.given
    def env[E, A](name: Predef.String) =
        new JsKyo(underlying.env(name))

    def let_[A, S](f: js.Function0[JsKyo[A, S]]) =
        new JsKyo(System.let(underlying)(f().underlying))

    def lineSeparator() =
        new JsKyo(underlying.lineSeparator)

    def operatingSystem() =
        new JsKyo(underlying.operatingSystem)

    def property[E, A](name: Predef.String) =
        new JsKyo(underlying.property(name))

    def unsafe() =
        underlying.unsafe

    def userName() =
        new JsKyo(underlying.userName)


end JsSystem

object JsSystem:
    import kyo.JsFacadeGivens.given

    @JSExportStatic
    def apply(u: System.Unsafe) =
        new JsSystem(System.apply(u))

    @JSExportStatic
    def lineSeparator() =
        new JsKyo(System.lineSeparator)

    @JSExportStatic
    def live() =
        new JsSystem(System.live)

    @JSExportStatic
    def operatingSystem() =
        new JsKyo(System.operatingSystem)

    @JSExportStatic
    def userName() =
        new JsKyo(System.userName)


end JsSystem