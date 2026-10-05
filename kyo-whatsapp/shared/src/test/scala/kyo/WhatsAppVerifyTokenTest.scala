package kyo

import WhatsAppInvalidTokenException.Problem
import WhatsAppInvalidTokenException.Token

class WhatsAppVerifyTokenTest extends BaseWhatsAppTest:

    def verifyToken(text: String)(using Frame): WhatsAppVerifyToken = WhatsAppVerifyToken.init(text).getOrThrow

    "toString is redacted and value is the raw text" in {
        val text  = Seq("SECRET", "VERIFY", "b0c59d").mkString("-")
        val token = verifyToken(text)
        assert(token.toString == "WhatsAppVerifyToken(<redacted>)")
        assert(token.value == text)
    }

    "equality and hashCode are by value" in {
        assert(verifyToken("a") == verifyToken("a"))
        assert(verifyToken("a") != verifyToken("b"))
        assert(verifyToken("a").hashCode == verifyToken("a").hashCode)
    }

    "any non-empty text is a verify token: the module compares it with what Meta echoes and never sends it" in {
        Seq("t" * 300, "my token", "tök", "a#b?c").foreach(text => assert(verifyToken(text).value == text, s"for $text"))
        succeed
    }

    "an empty verify token is refused, since it would accept any handshake" in {
        assert(WhatsAppVerifyToken.init("") == Result.fail(WhatsAppInvalidTokenException(Token.VerifyToken, Problem.Empty)))
    }

end WhatsAppVerifyTokenTest
