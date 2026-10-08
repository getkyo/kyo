package kyo

import kyo.EmailLiterals.*

class EmailPasswordTest extends kyo.test.Test[Any]:

    private val secret = "correct-horse-battery-staple"

    // Built away from the assertions: in development mode a message embeds the source lines around its construction site, so a needle
    // written on those lines would match the snippet rather than the message.
    private val injected = "ok\r\n" + secret

    "renders redacted" in {
        val password = passwordOf(secret)
        assert(password.toString == "Email.Password(<redacted>)")
        assert(!s"$password".contains(secret))
    }

    "compares by text" in {
        assert(passwordOf(secret) == passwordOf(secret))
        assert(passwordOf(secret) != passwordOf("other"))
        assert(passwordOf(secret).hashCode == passwordOf(secret).hashCode)
    }

    "keeps spaces, tabs, quotes and non-ASCII, which a password may hold" in {
        val value = "päss w\tord \"q\" \\"
        assert(passwordOf(value).value == value)
    }

    "init fails on an empty password" in {
        assert(Email.Password.init("") == Result.fail(EmailInvalidTokenException(EmailInvalidTokenException.Problem.EmptyPassword)))
    }

    "init fails on NUL, CR or LF, which would end a SASL PLAIN field or an IMAP LOGIN line, naming only the position" in {
        Seq("a\u0000b" -> 1, "ab\r" -> 2, "\nab" -> 0, injected -> 2).foreach { (value, position) =>
            Email.Password.init(value) match
                case Result.Failure(ex) =>
                    assert(ex.problem == EmailInvalidTokenException.Problem.LineBreakOrNulInPassword(position), value)
                    assert(!ex.getMessage.contains(secret))
                case other => fail(s"$value: $other")
        }
    }

end EmailPasswordTest
