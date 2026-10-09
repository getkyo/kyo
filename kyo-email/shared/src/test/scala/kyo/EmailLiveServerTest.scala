package kyo

import kyo.EmailLiveAccount.*
import kyo.EmailLiveServer.Token
import kyo.EmailLiveServer.User

class EmailLiveServerTest extends EmailLiveSuite:

    // Every leaf reaches the shared server from one address. With Dovecot's per-address penalty on, three IMAP refusals are followed by
    // an SMTP refusal of `454 4.7.0 Temporary authentication failure` instead of a 535: a refusal a later leaf asserts would depend on
    // how many earlier leaves were refused.
    "refusals in a row on one server, IMAP then SMTP, are each the protocol's refusal" in server() { mail =>
        mail.token(User.Test, Token.Foreign).map { token =>
            val oauth   = EmailLiterals.oauth2AccountOf(mail.login(User.Test), token)
            val message =
                Email.Message(from = Chunk(mail.address(User.Test)), to = Chunk(mail.address(User.Test)), subject = "x", text = "x")
            for
                imap <- Kyo.foreach(1 to 3) { _ =>
                    Abort.run[EmailStatusFailure](EmailReceive.run(mail.imap(User.Test).account(oauth))(EmailReceive.status(inbox))).map {
                        case Result.Failure(rejected: EmailAuthenticationException) => rejected.reply.describe.takeWhile(_ != ' ')
                        case other                                                  => other.toString
                    }
                }
                smtp <- Kyo.foreach(1 to 3) { _ =>
                    Abort.run[EmailSendFailure](EmailSend.run(mail.smtp(User.Test).account(oauth))(EmailSend.send(message))).map {
                        case Result.Failure(rejected: EmailAuthenticationException) => rejected.reply.describe.takeWhile(_ != ' ')
                        case other                                                  => other.toString
                    }
                }
            yield assert((imap ++ smtp) == Chunk.fill(3)("[AUTHENTICATIONFAILED]") ++ Chunk.fill(3)("535"), s"got ${imap ++ smtp}")
            end for
        }
    }

end EmailLiveServerTest
