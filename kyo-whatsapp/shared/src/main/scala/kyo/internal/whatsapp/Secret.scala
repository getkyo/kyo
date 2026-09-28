package kyo.internal.whatsapp

import kyo.*

/** The construction check of the two credentials the module sends or keys with: non-empty, printable ASCII other than space. Those
  * characters are what an HTTP header carries unchanged; a CR, LF or space would end or split it. Meta documents no alphabet and no
  * length, so nothing else is bounded.
  */
private[kyo] object Secret:

    def check(token: WhatsAppInvalidTokenException.Token, value: String)(using Frame): Unit =
        import WhatsAppInvalidTokenException.Problem
        val problem =
            if value.isEmpty then Present(Problem.Empty)
            else
                val bad = value.indexWhere(c => c < '!' || c > '~')
                if bad >= 0 then Present(Problem.InvalidCharacter(bad)) else Absent
        problem.foreach(p => throw WhatsAppInvalidTokenException(token, p))
    end check

end Secret
