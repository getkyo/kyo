package kyo

import kyo.EmailLiveAccount.*
import kyo.EmailLiveServer.Token
import kyo.EmailLiveServer.User

/** `Email.Auth.OAuth2` through SASL XOAUTH2 against a server that validates the token itself. */
class EmailAuthLiveTest extends EmailLiveSuite:

    "a token the server validates authenticates IMAP and SMTP, and the computation runs at every authentication" in server() { mail =>
        for
            token <- mail.token(User.Test, Token.Valid)
            runs  <- AtomicInt.init
            auth    = EmailLiterals.oauth2Of(User.Test.login, runs.incrementAndGet.andThen(token))
            account = EmailLiveAccount(User.Test.address, mail.imap(User.Test).auth(auth), mail.smtp(User.Test).auth(auth))
            message = Email.Message(from = Chunk(account.address), to = Chunk(account.address), subject = "oauth", text = "Token.")
            (id, _, received) <- arrival(account, "oauth")(EmailSmtp.let(account.smtp)(EmailSmtp.send(message)))
            count             <- runs.get
        yield
            assert(received.messageId == Present(id))
            // The status read before the loop, the loop's own connection, and the submission.
            assert(count == 3)
        end for
    }

    // One refusal per server: Dovecot delays each further failure from the same address longer than the last, and past a few Postfix
    // gives up on it with a 454, so leaves that refused several tokens on one server would assert on that penalty instead.
    Chunk(
        (Token.Foreign, User.Test, "signed with another key"),
        (Token.Expired, User.Test, "past its expiry"),
        (Token.Valid, User.Other, "issued to another user")
    ).foreach { (kind, subject, described) =>
        s"IMAP refuses a token $described with AUTHENTICATIONFAILED" in server() { mail =>
            mail.token(subject, kind).map { token =>
                val auth = EmailLiterals.oauth2Of(User.Test.login, token)
                Abort.run[EmailConnectFailure](EmailImap.let(mail.imap(User.Test).auth(auth))(EmailImap.status(inbox))).map {
                    case Result.Failure(rejected: EmailAuthenticationException) =>
                        assert(rejected.mechanism == Email.Auth.Mechanism.XOAuth2)
                        assert(rejected.reply.protocol == EmailException.Protocol.Imap)
                        assert(rejected.reply.describe.startsWith("[AUTHENTICATIONFAILED]"))
                    case other => fail(s"expected the token to be refused, got $other")
                }
            }
        }

        s"SMTP refuses a token $described with 535" in server() { mail =>
            mail.token(subject, kind).map { token =>
                val auth = EmailLiterals.oauth2Of(User.Test.login, token)
                Abort.run[EmailSmtpCustomFailure] {
                    EmailSmtp.let(mail.smtp(User.Test).auth(auth))(EmailSmtp.custom(Seq(EmailLiterals.smtpCommandOf("NOOP"))))
                }.map {
                    case Result.Failure(rejected: EmailAuthenticationException) =>
                        assert(rejected.mechanism == Email.Auth.Mechanism.XOAuth2)
                        assert(rejected.reply.protocol == EmailException.Protocol.Smtp)
                        assert(rejected.reply.describe.startsWith("535 5.7.8 "))
                    case other => fail(s"expected the token to be refused, got $other")
                }
            }
        }
    }

end EmailAuthLiveTest
