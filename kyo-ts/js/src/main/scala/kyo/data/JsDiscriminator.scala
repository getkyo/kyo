package kyo

import scala.scalajs.js
import scala.scalajs.js.annotation.*

@JSExportTopLevel("Discriminator")
class JsDiscriminator(@JSName("$disc") val underlying: Discriminator) extends js.Object:
    import kyo.JsFacadeGivens.given

end JsDiscriminator

object JsDiscriminator:
    import kyo.JsFacadeGivens.given

    @JSExportStatic
    def given_Discriminator() =
        new JsDiscriminator(Discriminator.given_Discriminator)


end JsDiscriminator