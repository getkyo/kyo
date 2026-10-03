package kyo

import kyo.internal.email.imap.ImapTestServer
import kyo.internal.email.net.LineConnectionFixture.scripted
import kyo.internal.email.smtp.SmtpTestServer

class EmailRunTest extends kyo.test.Test[Any]:

    private val account = EmailLiterals.passwordAccountOf(ImapTestServer.User, EmailLiterals.passwordOf("s3cret-pass"))

    private val note = Email.Message(
        from = Chunk(Email.Address(ImapTestServer.User)),
        to = Chunk(Email.Address(ImapTestServer.User)),
        subject = "Note",
        text = "Hi.",
        messageId = Present(EmailLiterals.messageIdOf("note@example.com"))
    )

    "Email.run handles both effects, each verb reaching its own server" in scripted {
        Scope.run {
            for
                imap            <- ImapTestServer.serve()(peer => peer.ready().andThen(peer.untilClosed))
                smtp            <- SmtpTestServer.serve()(peer => peer.ready().andThen(peer.untilClosed))
                imapConfig      <- ImapTestServer.config(imap, account)
                smtpConfig      <- SmtpTestServer.config(smtp, account)
                (id, mailboxes) <- Email.run(smtpConfig, imapConfig) {
                    EmailSend.send(note).map(id => EmailReceive.listMailboxes.map((id, _)))
                }
                _        <- imap.awaitClose
                imapSeen <- imap.received
                smtpSeen <- smtp.received
            yield
                assert(id == EmailLiterals.messageIdOf("note@example.com"))
                assert(mailboxes.isEmpty)
                assert(imapSeen.exists(_.endsWith("LIST \"\" \"*\"")) && imapSeen.last.endsWith("LOGOUT"))
                assert(smtpSeen.exists(_.startsWith("MAIL FROM:")) && smtpSeen.last == "QUIT")
        }
    }

    "a computation of both effects under one handler only does not compile" in {
        typeCheck("""
            def both(using kyo.Frame): Unit < (kyo.Email & kyo.Async & kyo.Abort[kyo.EmailException]) = ???
            def smtp: kyo.EmailSmtpConfig = ???
            def imap: kyo.EmailImapConfig = ???
            val whole: Unit < (kyo.Async & kyo.Abort[kyo.EmailException]) = kyo.Email.run(smtp, imap)(both)
        """)
        typeCheckFailure("""
            def both(using kyo.Frame): Unit < (kyo.Email & kyo.Async & kyo.Abort[kyo.EmailException]) = ???
            def smtp: kyo.EmailSmtpConfig = ???
            val half: Unit < (kyo.Async & kyo.Abort[kyo.EmailException]) = kyo.EmailSend.run(smtp)(both)
        """)("EmailReceive")
    }

end EmailRunTest
