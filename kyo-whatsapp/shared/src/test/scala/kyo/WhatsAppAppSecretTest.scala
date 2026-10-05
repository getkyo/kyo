package kyo

import WhatsAppInvalidTokenException.Problem
import WhatsAppInvalidTokenException.Token

class WhatsAppAppSecretTest extends BaseWhatsAppTest:

    def secret(text: String)(using Frame): WhatsAppAppSecret = WhatsAppAppSecret.init(text).getOrThrow

    "toString is redacted and value is the raw text" in {
        val text = Seq("SECRET", "APP", "3fe07b").mkString("-")
        val s    = secret(text)
        assert(s.toString == "WhatsAppAppSecret(<redacted>)")
        assert(s.value == text)
    }

    "equality and hashCode are by value" in {
        assert(secret("a") == secret("a"))
        assert(secret("a") != secret("b"))
        assert(secret("a").hashCode == secret("a").hashCode)
    }

    "an app secret of printable ASCII of any length is accepted" in {
        assert(secret("0f3a9c|b+1/x=").value == "0f3a9c|b+1/x=")
        assert(secret("a" * 20000).value.length == 20000)
    }

    "an empty app secret, or one with a space or a character outside printable ASCII, is refused" in {
        assert(WhatsAppAppSecret.init("") == Result.fail(WhatsAppInvalidTokenException(Token.AppSecret, Problem.Empty)))
        assert(WhatsAppAppSecret.init("ab cd") == Result.fail(WhatsAppInvalidTokenException(Token.AppSecret, Problem.InvalidCharacter(2))))
        assert(WhatsAppAppSecret.init("abç") == Result.fail(WhatsAppInvalidTokenException(Token.AppSecret, Problem.InvalidCharacter(2))))
    }

end WhatsAppAppSecretTest
