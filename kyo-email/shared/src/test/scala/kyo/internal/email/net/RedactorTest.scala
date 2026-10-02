package kyo.internal.email.net

import kyo.*

class RedactorTest extends kyo.test.Test[Any]:

    private val user = "user@example.com"

    // Built away from the assertions, so a failing assertion's source snippet never carries a secret.
    private val secret      = "s3cr3t pass\"word\\"
    private val password    = EmailLiterals.passwordOf(secret)
    private val nonAscii    = "pässwörd"
    private val prefix      = "AHVzZXIA"
    private val tokenText   = "ya29.a0AfH6SMBx"
    private val token       = EmailLiterals.tokenOf(tokenText)
    private val quoted      = "\"s3cr3t pass\\\"word\\\\\""
    private val plain       = Sasl.plain(user, password)
    private val loginLine   = Sasl.base64(secret)
    private val xoauth2     = Sasl.xoauth2(user, token)
    private val passwordFor = Redactor.password(user, password)
    private val tokenFor    = Redactor.token(user, token)

    // Twelve octets repeated, after PLAIN's 18-octet prefix: the base64 of the whole response ends with the base64 of the password, and
    // shifted by one period that is a prefix of itself.
    private val periodic      = EmailLiterals.passwordOf("hunter2-\"q\"-" * 20)
    private val periodicPlain = Sasl.plain(user, periodic)

    // A password whose forms begin with the mask's last character, long enough that redacting brings a text past the limit within it.
    private val angledText = ">" + "k" * 30
    private val angled     = EmailLiterals.passwordOf(angledText)

    private def leaks(text: String, secrets: String*): Boolean = secrets.exists(text.contains)

    "a password's forms" - {
        "the password itself" in {
            assert(passwordFor.redact(s"A1 BAD $secret") == "A1 BAD <redacted>")
        }
        "its quoted form in a LOGIN line, whose escapes break the plain text apart" in {
            val echoed = passwordFor.redact(s"* BAD LOGIN \"$user\" $quoted")
            assert(!leaks(echoed, secret, quoted, "s3cr3t"))
            assert(echoed == s"* BAD LOGIN \"$user\" <redacted>")
        }
        "the base64 of PLAIN's initial response and of an AUTH LOGIN line" in {
            assert(passwordFor.redact(s"A2 NO unexpected $plain") == "A2 NO unexpected <redacted>")
            assert(passwordFor.redact(s"535 $loginLine") == "535 <redacted>")
        }
        "a non-ASCII password as the escaped octets a Protocol failure shows" in {
            val redactor = Redactor.password(user, EmailLiterals.passwordOf(nonAscii))
            val shown    = LineConnection.shown(Span.from(s"x $nonAscii y".getBytes("UTF-8")))
            assert(redactor.redact(shown) == "x <redacted> y")
            assert(redactor.redact(s"x $nonAscii y") == "x <redacted> y")
        }
        "a form holding another is replaced whole: the PLAIN response of user `user` starts with the password `AHVzZXIA`" in {
            val redactor = Redactor.password("user", EmailLiterals.passwordOf(prefix))
            assert(redactor.redact(s"A NO ${Sasl.plain("user", EmailLiterals.passwordOf(prefix))}") == "A NO <redacted>")
        }
        "every occurrence" in {
            assert(passwordFor.redact(s"$secret and $secret") == "<redacted> and <redacted>")
        }
    }

    "a token's forms: the token and XOAUTH2's initial response" in {
        assert(tokenFor.redact(s"A3 NO $xoauth2") == "A3 NO <redacted>")
        assert(tokenFor.redact(s"A3 NO $tokenText") == "A3 NO <redacted>")
    }

    "cut to a limit after redacting" - {
        "a form straddling the limit is replaced whole, not cut" in {
            assert(passwordFor.redact("x" * 190 + secret + "y" * 50, 200) == "x" * 190 + "<redacted>")
        }
        "a text cut inside a form loses that fragment, though the replacements before it bring it within the limit" in {
            val readCut = secret * 15 + secret.take(12)
            assert(passwordFor.redact(readCut, 200) == "<redacted>" * 15)
        }
        "a periodic password's whole form, whose end equals the start of another form, is replaced whole" in {
            val redactor = Redactor.password(user, periodic)
            assert(redactor.redact(s"?? A1 AUTHENTICATE PLAIN $periodicPlain", 200) == "?? A1 AUTHENTICATE PLAIN <redacted>")
        }
        "a mask is never taken for a cut form: a password starting with '>' leaves the mask's own '>' in place" in {
            val redactor = Redactor.password(user, angled)
            assert(redactor.redact("A1 BAD " + angledText, 20) == "A1 BAD <redacted>")
        }
        "a text within the limit is only redacted" in {
            assert(passwordFor.redact(s"A1 BAD $secret", 200) == "A1 BAD <redacted>")
        }
    }

    "text without a secret is returned unchanged" in {
        assert(passwordFor.redact("A1 OK LOGIN completed") == "A1 OK LOGIN completed")
    }

end RedactorTest
