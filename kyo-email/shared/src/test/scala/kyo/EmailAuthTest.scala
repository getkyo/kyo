package kyo

import kyo.EmailInvalidConfigException.Violation
import kyo.EmailLiterals.*

class EmailAuthTest extends kyo.test.Test[Any]:

    private val password = "correct-horse-battery-staple"
    private val token    = "ya29.a0AfH6SMBx-access-token"

    final private class TokenEndpointDown(val status: Int) extends Exception(s"token endpoint answered $status")

    private def violation[A](checked: Result[EmailInvalidConfigException, A]): Maybe[Violation] =
        checked match
            case Result.Failure(invalid) => Present(invalid.violation)
            case _                       => Absent

    "Account" - {
        "renders the user and no password" in {
            val rendered = passwordAccountOf("ada@example.com", passwordOf(password)).toString
            assert(rendered.contains("ada@example.com"))
            assert(rendered.contains("<redacted>"))
            assert(!rendered.contains(password))
        }
        "renders the user and a placeholder for the token computation" in {
            val rendered = oauth2AccountOf("ada@example.com", tokenOf(token)).toString
            assert(rendered == "Account(ada@example.com,OAuth2(<token computation>))")
        }
        "init fails on an empty user" in {
            assert(violation(Email.Account.init("", Email.Auth.Password(passwordOf(password)))) == Present(Violation.EmptyUser))
        }
        "init fails on a user with a line break" in {
            assert(violation(Email.Account.init("ada\r\nA2 LOGOUT", Email.Auth.Password(passwordOf(password)))) ==
                Present(Violation.ControlCharacterInUser))
        }
        "init fails on a user with a control character" in {
            assert(violation(Email.Account.init("ada\u0000", Email.Auth.OAuth2(tokenOf(token)))) ==
                Present(Violation.ControlCharacterInUser))
        }
    }

    "OAuth2" - {
        "runs the token computation again each time it is used, so its effects give a fresh token" in {
            for
                counter <- AtomicInt.init(0)
                auth = Email.Auth.OAuth2(counter.incrementAndGet.map(n => tokenOf(s"$token-$n")))
                first  <- auth.token
                second <- auth.token
            yield assert(first.value == s"$token-1" && second.value == s"$token-2")
        }
        "fails with an EmailTokenException carrying the caller's message and cause" in {
            val down = new TokenEndpointDown(503)
            val auth = Email.Auth.OAuth2(Abort.fail(EmailTokenException("the token endpoint is down", Present(down))))
            Abort.run[EmailTokenException](auth.token).map {
                case Result.Failure(failed) =>
                    assert(failed.getMessage.contains("the OAuth token computation failed: the token endpoint is down"))
                    assert(failed.getCause eq down)
                case other => fail(s"expected the token failure, got $other")
            }
        }
        "renders no token" in {
            val auth = Email.Auth.OAuth2(tokenOf(token))
            auth.token.map { issued =>
                assert(issued.value == token)
                assert(auth.toString == "OAuth2(<token computation>)")
            }
        }
    }

end EmailAuthTest
