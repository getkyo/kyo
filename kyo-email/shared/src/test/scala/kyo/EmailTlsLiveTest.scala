package kyo

import kyo.EmailLiveAccount.*
import kyo.EmailLiveServer.Kind
import kyo.EmailLiveServer.Security
import kyo.EmailLiveServer.User

/** `Email.Tls`'s client certificate and version bounds against a server that accepts TLS 1.3 only and requires a client certificate. */
class EmailTlsLiveTest extends EmailLiveSuite:

    private val modes = Chunk(Security.Implicit, Security.StartTls)

    private def probe(mail: EmailLiveServer)(using Frame): Email.Message =
        Email.Message(from = Chunk(mail.address(User.Test)), to = Chunk(mail.address(User.Test)), subject = "tls", text = "Probe.")

    private def submitted(id: Email.MessageId): Boolean = id.value.nonEmpty

    "with the client certificate, IMAP and SMTP sessions open over implicit TLS and STARTTLS" in server(Kind.Strict) { mail =>
        Kyo.foreach(modes) { security =>
            val tls = mail.tls(security, Present(mail.clientCertificate))
            for
                status <- EmailReceive.run(mail.imap(User.Test, tls))(EmailReceive.status(inbox))
                id     <- EmailSend.run(mail.smtp(User.Test, tls))(EmailSend.send(probe(mail)))
            yield (status.mailbox, submitted(id))
            end for
        }.map(opened => assert(opened == Chunk((inbox, true), (inbox, true))))
    }

    "without a client certificate, IMAP refuses the login" in ownServer(Kind.Strict) { mail =>
        Kyo.foreach(modes) { security =>
            Abort.run[EmailStatusFailure](EmailReceive.run(mail.imap(User.Test, mail.tls(security)))(EmailReceive.status(inbox)))
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
            Abort.run[EmailSendFailure](EmailSend.run(mail.smtp(User.Test, mail.tls(security)))(EmailSend.send(probe(mail))))
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
                imap <- Abort.run[EmailStatusFailure](EmailReceive.run(mail.imap(User.Test, tls))(EmailReceive.status(inbox)))
                smtp <- Abort.run[EmailSendFailure](EmailSend.run(mail.smtp(User.Test, tls))(EmailSend.send(probe(mail))))
            yield Chunk[Result[EmailException, Any]](imap, smtp)
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
            status <- EmailReceive.run(mail.imap(User.Test, tls))(EmailReceive.status(inbox))
            id     <- EmailSend.run(mail.smtp(User.Test, tls))(EmailSend.send(probe(mail)))
        yield
            assert(status.mailbox == inbox)
            assert(submitted(id))
        end for
    }

    "a server certificate outside the system trust store fails the handshake, over both modes and protocols" in server() { mail =>
        Kyo.foreach(modes) { security =>
            val tls = security match
                case Security.Implicit => Email.Tls.Implicit
                case Security.StartTls => Email.Tls.StartTls
            for
                imap <- Abort.run[EmailStatusFailure](EmailReceive.run(mail.imap(User.Test, tls))(EmailReceive.status(inbox)))
                smtp <- Abort.run[EmailSendFailure](EmailSend.run(mail.smtp(User.Test, tls))(EmailSend.send(probe(mail))))
            yield Chunk[Result[EmailException, Any]](imap, smtp)
            end for
        }.map { results =>
            results.flatten.foreach {
                case Result.Failure(failed: EmailConnectException) => assert(failed.kind == EmailConnectException.Kind.Tls)
                case other                                         => fail(s"expected the handshake to fail, got $other")
            }
        }
    }

end EmailTlsLiveTest
