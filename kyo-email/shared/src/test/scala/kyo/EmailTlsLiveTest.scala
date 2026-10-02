package kyo

import kyo.EmailLiveAccount.*
import kyo.EmailLiveServer.Kind
import kyo.EmailLiveServer.Security
import kyo.EmailLiveServer.User

/** `Email.Tls`'s client certificate and version bounds against a server that accepts TLS 1.3 only and requires a client certificate. */
class EmailTlsLiveTest extends EmailLiveSuite:

    private val modes = Chunk(Security.Implicit, Security.StartTls)

    "with the client certificate, IMAP and SMTP sessions open over implicit TLS and STARTTLS" in server(Kind.Strict) { mail =>
        Kyo.foreach(modes) { security =>
            val tls = mail.tls(security, Present(mail.clientCertificate))
            for
                status  <- EmailImap.let(mail.imap(User.Test, tls))(EmailImap.status(inbox))
                replies <- EmailSmtp.let(mail.smtp(User.Test, tls))(EmailSmtp.custom(Seq(EmailLiterals.smtpCommandOf("NOOP"))))
            yield (status.mailbox, replies.map(_.code))
            end for
        }.map(opened => assert(opened == Chunk((inbox, Chunk(250)), (inbox, Chunk(250)))))
    }

    "without a client certificate, IMAP refuses the login" in server(Kind.Strict) { mail =>
        Kyo.foreach(modes) { security =>
            Abort.run[EmailConnectFailure](EmailImap.let(mail.imap(User.Test, mail.tls(security)))(EmailImap.status(inbox)))
        }.map { results =>
            results.foreach {
                case Result.Failure(rejected: EmailAuthenticationException) =>
                    rejected.reply match
                        case EmailAuthenticationException.Reply.Imap(_, text) => assert(text.contains("certificate"))
                        case other                                            => fail(s"expected an IMAP reply, got $other")
                case other => fail(s"expected the login to be refused, got $other")
            }
        }
    }

    "without a client certificate, SMTP closes the session with a 421 whose text the failure carries" in server(Kind.Strict) { mail =>
        Kyo.foreach(modes) { security =>
            Abort.run[EmailSmtpCustomFailure] {
                EmailSmtp.let(mail.smtp(User.Test, mail.tls(security)))(EmailSmtp.custom(Seq(EmailLiterals.smtpCommandOf("NOOP"))))
            }
        }.map { results =>
            results.foreach {
                case Result.Failure(closed: EmailTransportException) =>
                    closed.kind match
                        case EmailTransportException.Kind.ConnectionClosed(Present(text)) =>
                            assert(text.contains("No client certificate presented"))
                        case other => fail(s"expected the server's closing text, got $other")
                case other => fail(s"expected the session to be closed, got $other")
            }
        }
    }

    "a TLS 1.2 maximum fails the handshake with a TLS 1.3 server, on both protocols" in server(Kind.Strict) { mail =>
        Kyo.foreach(modes) { security =>
            val tls = mail.tls(security, Present(mail.clientCertificate), maxVersion = Email.Tls.Version.TLS12)
            for
                imap <- Abort.run[EmailConnectFailure](EmailImap.let(mail.imap(User.Test, tls))(EmailImap.status(inbox)))
                smtp <- Abort.run[EmailSmtpCustomFailure] {
                    EmailSmtp.let(mail.smtp(User.Test, tls))(EmailSmtp.custom(Seq(EmailLiterals.smtpCommandOf("NOOP"))))
                }
            yield Chunk(imap, smtp)
            end for
        }.map { results =>
            results.flatten.foreach {
                case Result.Failure(failed: EmailConnectException) => assert(failed.kind == EmailConnectException.Kind.Tls)
                case other                                         => fail(s"expected the handshake to fail, got $other")
            }
        }
    }

    "a TLS 1.3 minimum opens IMAP and SMTP sessions" in server(Kind.Strict) { mail =>
        val tls = mail.tls(Security.Implicit, Present(mail.clientCertificate), minVersion = Email.Tls.Version.TLS13)
        for
            status  <- EmailImap.let(mail.imap(User.Test, tls))(EmailImap.status(inbox))
            replies <- EmailSmtp.let(mail.smtp(User.Test, tls))(EmailSmtp.custom(Seq(EmailLiterals.smtpCommandOf("NOOP"))))
        yield
            assert(status.mailbox == inbox)
            assert(replies.map(_.code) == Chunk(250))
        end for
    }

    "a server certificate outside the system trust store fails the handshake, over both modes and protocols" in server() { mail =>
        Kyo.foreach(modes) { security =>
            val tls = security match
                case Security.Implicit => Email.Tls.Implicit
                case Security.StartTls => Email.Tls.StartTls
            for
                imap <- Abort.run[EmailConnectFailure](EmailImap.let(mail.imap(User.Test, tls))(EmailImap.status(inbox)))
                smtp <- Abort.run[EmailSmtpCustomFailure] {
                    EmailSmtp.let(mail.smtp(User.Test, tls))(EmailSmtp.custom(Seq(EmailLiterals.smtpCommandOf("NOOP"))))
                }
            yield Chunk(imap, smtp)
            end for
        }.map { results =>
            results.flatten.foreach {
                case Result.Failure(failed: EmailConnectException) => assert(failed.kind == EmailConnectException.Kind.Tls)
                case other                                         => fail(s"expected the handshake to fail, got $other")
            }
        }
    }

end EmailTlsLiveTest
