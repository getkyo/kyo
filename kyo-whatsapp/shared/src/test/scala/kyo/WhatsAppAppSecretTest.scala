package kyo

import WhatsAppInvalidTokenException.Problem
import WhatsAppInvalidTokenException.Token

class WhatsAppAppSecretTest extends BaseWhatsAppTest:

    def refused(text: String)(using Frame): Result[WhatsAppInvalidTokenException, WhatsAppAppSecret] =
        Result.catching[WhatsAppInvalidTokenException](WhatsAppAppSecret(text))

    "toString is redacted and value is the raw text" in {
        val text   = Seq("SECRET", "APP", "3fe07b").mkString("-")
        val secret = WhatsAppAppSecret(text)
        assert(secret.toString == "WhatsAppAppSecret(<redacted>)")
        assert(secret.value == text)
    }

    "equality and hashCode are by value" in {
        assert(WhatsAppAppSecret("a") == WhatsAppAppSecret("a"))
        assert(WhatsAppAppSecret("a") != WhatsAppAppSecret("b"))
        assert(WhatsAppAppSecret("a").hashCode == WhatsAppAppSecret("a").hashCode)
    }

    "an app secret of printable ASCII of any length is accepted" in {
        assert(WhatsAppAppSecret("0f3a9c|b+1/x=").value == "0f3a9c|b+1/x=")
        assert(WhatsAppAppSecret("a" * 20000).value.length == 20000)
    }

    "an empty app secret, or one with a space or a character outside printable ASCII, is refused" in {
        assert(refused("") == Result.fail(WhatsAppInvalidTokenException(Token.AppSecret, Problem.Empty)))
        assert(refused("ab cd") == Result.fail(WhatsAppInvalidTokenException(Token.AppSecret, Problem.InvalidCharacter(2))))
        assert(refused("abç") == Result.fail(WhatsAppInvalidTokenException(Token.AppSecret, Problem.InvalidCharacter(2))))
    }

end WhatsAppAppSecretTest
