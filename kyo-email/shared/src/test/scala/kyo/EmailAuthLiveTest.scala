package kyo

import kyo.EmailLiveAccount.*
import kyo.EmailLiveServer.Token
import kyo.EmailLiveServer.User

/** `Email.Auth.OAuth2` through SASL XOAUTH2 against a server that validates the token itself. */
// A token failure is covered by EmailReceiveTest and EmailSendTest against the scripted servers: it fails before any connection opens.
class EmailAuthLiveTest extends EmailLiveSuite:

    "a token the server validates authenticates IMAP and SMTP, and the computation runs at every authentication" in server() { mail =>
        for
            token <- mail.token(User.Test, Token.Valid)
            runs  <- AtomicInt.init
            oauth   = EmailLiterals.oauth2AccountOf(mail.login(User.Test), runs.incrementAndGet.andThen(token))
            account = EmailLiveAccount(mail.address(User.Test), mail.imap(User.Test).account(oauth), mail.smtp(User.Test).account(oauth))
            message = Email.Message(from = Chunk(account.address), to = Chunk(account.address), subject = "oauth", text = "Token.")
            (id, _, received) <- arrival(account, "oauth")(EmailSend.run(account.smtp)(EmailSend.send(message)))
            count             <- runs.get
        yield
            assert(received.messageId == Present(id))
            // The status read before the loop, the loop's own connection, and the submission.
            assert(count == 3)
        end for
    }

    Chunk(
        (Token.Foreign, User.Test, "signed with another key"),
        (Token.Expired, User.Test, "past its expiry"),
        (Token.Valid, User.Other, "issued to another user")
    ).foreach { (kind, subject, described) =>
        s"IMAP refuses a token $described with AUTHENTICATIONFAILED" in server() { mail =>
            mail.token(subject, kind).map { token =>
                val oauth = EmailLiterals.oauth2AccountOf(mail.login(User.Test), token)
                Abort.run[EmailStatusFailure](EmailReceive.run(mail.imap(User.Test).account(oauth))(EmailReceive.status(inbox))).map {
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
                val oauth   = EmailLiterals.oauth2AccountOf(mail.login(User.Test), token)
                val message =
                    Email.Message(
                        from = Chunk(mail.address(User.Test)),
                        to = Chunk(mail.address(User.Test)),
                        subject = "refused",
                        text = "x"
                    )
                Abort.run[EmailSendFailure] {
                    EmailSend.run(mail.smtp(User.Test).account(oauth))(EmailSend.send(message))
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
