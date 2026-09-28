package kyo

import WhatsAppInvalidTokenException.Problem
import WhatsAppInvalidTokenException.Token

class WhatsAppVerifyTokenTest extends BaseWhatsAppTest:

    def refused(text: String)(using Frame): Result[WhatsAppInvalidTokenException, WhatsAppVerifyToken] =
        Result.catching[WhatsAppInvalidTokenException](WhatsAppVerifyToken(text))

    "toString is redacted and value is the raw text" in {
        val text  = Seq("SECRET", "VERIFY", "b0c59d").mkString("-")
        val token = WhatsAppVerifyToken(text)
        assert(token.toString == "WhatsAppVerifyToken(<redacted>)")
        assert(token.value == text)
    }

    "equality and hashCode are by value" in {
        assert(WhatsAppVerifyToken("a") == WhatsAppVerifyToken("a"))
        assert(WhatsAppVerifyToken("a") != WhatsAppVerifyToken("b"))
        assert(WhatsAppVerifyToken("a").hashCode == WhatsAppVerifyToken("a").hashCode)
    }

    "any non-empty text is a verify token: the module compares it with what Meta echoes and never sends it" in {
        Seq("t" * 300, "my token", "tök", "a#b?c").foreach(text => assert(WhatsAppVerifyToken(text).value == text, s"for $text"))
        succeed
    }

    "an empty verify token is refused, since it would accept any handshake" in {
        assert(refused("") == Result.fail(WhatsAppInvalidTokenException(Token.VerifyToken, Problem.Empty)))
    }

end WhatsAppVerifyTokenTest
