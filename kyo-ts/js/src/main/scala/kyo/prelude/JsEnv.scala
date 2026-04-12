package kyo

import scala.scalajs.js
import scala.scalajs.js.annotation.*

@JSExportTopLevel("Env")
class JsEnv[R](@JSName("$env") val underlying: Env[R]) extends js.Object:
    import kyo.JsFacadeGivens.given

end JsEnv

object JsEnv:
    import kyo.JsFacadeGivens.given

    @JSExportStatic
    def eliminateEnv() =
        Env.eliminateEnv

    @JSExportStatic
    def get[R]() =
        new JsKyo(Env.get)

    @JSExportStatic
    def getAll[R]() =
        new JsKyo(Env.getAll)

    @JSExportStatic
    def run[R, A, S, VR](env: R, v: JsKyo[A, `&`[Env[`&`[R, VR]], S]]) =
        new JsKyo(Env.run(env)(v.underlying))

    @JSExportStatic
    def runLayer[A, S, V](layers: Seq[Layer[?, ?]], value: JsKyo[A, `&`[Env[V], S]]) =
        new JsKyo(Env.runLayer(layers*)(value.underlying))

    @JSExportStatic
    def use[R, A, S](f: Function1[R, `<`[A, S]]) =
        new JsKyo(Env.use(f))

    @JSExportStatic
    def useAll[R, A, S](f: Function1[TypeMap[R], `<`[A, S]]) =
        new JsKyo(Env.useAll(f))


end JsEnv