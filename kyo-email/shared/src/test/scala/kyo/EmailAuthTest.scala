package kyo

import kyo.EmailInvalidConfigException.Violation
import kyo.EmailLiterals.*

class EmailAuthTest extends kyo.test.Test[Any]:

    private val password = "correct-horse-battery-staple"
    private val token    = "ya29.a0AfH6SMBx-access-token"

    final private case class TokenEndpointDown(status: Int) derives CanEqual

    private def violation[A](checked: Result[EmailInvalidConfigException, A]): Maybe[Violation] =
        checked match
            case Result.Failure(invalid) => Present(invalid.violation)
            case _                       => Absent

    "Password" - {
        "renders the user and no secret" in {
            val rendered = passwordAuthOf("ada@example.com", passwordOf(password)).toString
            assert(rendered.contains("ada@example.com"))
            assert(rendered.contains("<redacted>"))
            assert(!rendered.contains(password))
        }
        "init fails on an empty user" in {
            assert(violation(Email.Auth.Password.init("", passwordOf(password))) == Present(Violation.EmptyUser))
        }
        "init fails on a user with a line break" in {
            assert(violation(Email.Auth.Password.init("ada\r\nA2 LOGOUT", passwordOf(password))) ==
                Present(Violation.ControlCharacterInUser))
        }
    }

    "OAuth2" - {
        "runs the token computation again each time it is used, so its effects give a fresh token" in {
            for
                counter <- AtomicInt.init(0)
                auth = oauth2Of("ada@example.com", counter.incrementAndGet.map(n => tokenOf(s"$token-$n")))
                first  <- auth.token
                second <- auth.token
            yield assert(first.value == s"$token-1" && second.value == s"$token-2")
        }
        "carries the computation's own error, not a module leaf" in {
            val auth: Email.Auth.OAuth2[TokenEndpointDown] = oauth2Of("ada@example.com", Abort.fail(TokenEndpointDown(503)))
            Abort.run[TokenEndpointDown](auth.token).map { result =>
                assert(result == Result.fail(TokenEndpointDown(503)))
            }
        }
        "renders the user and no token" in {
            val auth = oauth2Of("ada@example.com", tokenOf(token))
            auth.token.map { issued =>
                val rendered = auth.toString
                assert(issued.value == token)
                assert(rendered == "OAuth2(ada@example.com,<token computation>)")
                assert(!rendered.contains(token))
            }
        }
        "init fails on an empty user" in {
            assert(violation(Email.Auth.OAuth2.init("", tokenOf(token))) == Present(Violation.EmptyUser))
        }
        "init fails on a user with a control character" in {
            assert(violation(Email.Auth.OAuth2.init("ada\u0000", tokenOf(token))) == Present(Violation.ControlCharacterInUser))
        }
    }

end EmailAuthTest
