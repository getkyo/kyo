package kyo

import scala.scalajs.js
import scala.scalajs.js.annotation.*

@JSExportTopLevel("Frame")
class JsFrame(@JSName("$fram") val underlying: Frame) extends js.Object:
    import kyo.JsFacadeGivens.given

end JsFrame

object JsFrame:
    import kyo.JsFacadeGivens.given

    @JSExportStatic
    def className() =
        Frame.className

    @JSExportStatic
    def derive() =
        new JsFrame(Frame.derive)

    @JSExportStatic
    def given_CanEqual_Frame_Frame() =
        Frame.given_CanEqual_Frame_Frame

    @JSExportStatic
    def methodName() =
        Frame.methodName

    @JSExportStatic
    def position() =
        Frame.position

    @JSExportStatic
    def render(details: Seq[Any]) =
        Frame.render(details*)

    @JSExportStatic
    def render() =
        Frame.render

    @JSExportStatic
    def show() =
        Frame.show

    @JSExportStatic
    def snippet() =
        Frame.snippet

    @JSExportStatic
    def snippetShort() =
        Frame.snippetShort


end JsFrame