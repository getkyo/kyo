package kyo

import WhatsAppInvalidTokenException.Problem
import WhatsAppInvalidTokenException.Token

class WhatsAppTokenTest extends BaseWhatsAppTest:

    def token(text: String)(using Frame): WhatsAppToken = WhatsAppToken.init(text).getOrThrow

    "toString is redacted and value is the raw text" in {
        val text = Seq("SECRET", "TOKEN", "8a21f4").mkString("-")
        val t    = token(text)
        assert(t.toString == "WhatsAppToken(<redacted>)")
        assert(t.value == text)
    }

    "equality and hashCode are by value" in {
        assert(token("a") == token("a"))
        assert(token("a") != token("b"))
        assert(token("a").hashCode == token("a").hashCode)
    }

    "an app access token {app-id}|{app-secret} is accepted" in {
        assert(token("1234567890|0f3a9c7e").value == "1234567890|0f3a9c7e")
    }

    "an access token of any length is accepted, since Meta documents none" in {
        val long = "E" * 20000
        assert(token(long).value == long)
    }

    "an empty access token is refused" in {
        assert(WhatsAppToken.init("") == Result.fail(WhatsAppInvalidTokenException(Token.AccessToken, Problem.Empty)))
    }

    "an access token with a character that could break the Authorization header is refused at its position" in {
        Seq("a\r\nb" -> 1, "a b" -> 1, "a\tb" -> 1, "é" -> 0).foreach { case (text, position) =>
            assert(
                WhatsAppToken.init(text) ==
                    Result.fail(WhatsAppInvalidTokenException(Token.AccessToken, Problem.InvalidCharacter(position))),
                s"for $text"
            )
        }
    }

    // Built apart from the construction line: a leaf's development-mode message renders the source lines around its frame.
    val planted = Seq("PLANTED", "TOKEN", "TEXT").mkString("-")

    "a refusal renders the position, never the text" in {
        val e = WhatsAppToken.init(s"$planted ").failure
        assert(e.map(_.getMessage).exists(_.contains("WhatsAppToken is not usable: the character at position 18 is not allowed.")))
        assert(e.forall(f => BaseWhatsAppTest.renderings(f).forall(!_.contains(planted))))
    }

end WhatsAppTokenTest
