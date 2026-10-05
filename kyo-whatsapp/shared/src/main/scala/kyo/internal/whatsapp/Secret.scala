package kyo.internal.whatsapp

import kyo.*

/** The construction check of the two credentials the module sends or keys with: non-empty, printable ASCII other than space. Those
  * characters are what an HTTP header carries unchanged; a CR, LF or space would end or split it. Meta documents no alphabet and no
  * length, so nothing else is bounded.
  */
private[kyo] object Secret:

    def init[A](token: WhatsAppInvalidTokenException.Token, value: String)(build: String => A)(using
        Frame
    ): Result[WhatsAppInvalidTokenException, A] =
        import WhatsAppInvalidTokenException.Problem
        val bad = value.indexWhere(c => c < '!' || c > '~')
        if value.isEmpty then Result.fail(WhatsAppInvalidTokenException(token, Problem.Empty))
        else if bad >= 0 then Result.fail(WhatsAppInvalidTokenException(token, Problem.InvalidCharacter(bad)))
        else Result.succeed(build(value))
    end init

end Secret
