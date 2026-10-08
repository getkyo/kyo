package kyo

import kyo.EmailLiterals.*

class EmailOAuthTokenTest extends kyo.test.Test[Any]:

    private val secret = "ya29.a0AfH6SMBx-access-token"

    // Built away from the assertions: in development mode a message embeds the source lines around its construction site, so a needle
    // written on those lines would match the snippet rather than the message.
    private val injected = secret + "\u0001\u0001auth=x"

    "renders redacted" in {
        val token = tokenOf(secret)
        assert(token.toString == "Email.OAuthToken(<redacted>)")
        assert(!s"$token".contains(secret))
    }

    "compares by text" in {
        assert(tokenOf(secret) == tokenOf(secret))
        assert(tokenOf(secret) != tokenOf("other"))
        assert(tokenOf(secret).hashCode == tokenOf(secret).hashCode)
    }

    "accepts every character of RFC 6750's b64token and trailing =" in {
        val value = "AZaz09-._~+/abc=="
        assert(tokenOf(value).value == value)
    }

    "init fails on an empty token" in {
        assert(Email.OAuthToken.init("") == Result.fail(EmailInvalidTokenException(EmailInvalidTokenException.Problem.EmptyToken)))
    }

    "init fails on a character outside b64token, naming only its position: a \\u0001 would end the XOAUTH2 field" in {
        Seq(injected -> secret.length, "a b" -> 1, "a\r\n" -> 1, "=abc" -> 0, "ab=c" -> 3, "töken" -> 1).foreach {
            (value, position) =>
                Email.OAuthToken.init(value) match
                    case Result.Failure(ex) =>
                        assert(ex.problem == EmailInvalidTokenException.Problem.CharacterOutsideToken(position), value)
                        assert(!ex.getMessage.contains(secret))
                    case other => fail(s"$value: $other")
        }
    }

end EmailOAuthTokenTest
