package kyo

import kyo.EmailLiveAccount.*
import kyo.EmailLiveServer.Security
import kyo.EmailLiveServer.User

/** `EmailSmtp` against real servers: each message goes to an account the leaf reads back through `EmailImap.run`. */
class EmailSmtpLiveTest extends EmailLiveSuite:

    "a message sent to the account arrives through the receive loop with its fields, bodies and attachment" in server() { mail =>
        val account  = mail.account(User.Test)
        val attached = Span.from("attachment content é".getBytes("UTF-8"))
        val message  = Email.Message(
            from = Chunk(account.address),
            to = Chunk(account.address),
            subject = "round trip",
            text = "The plain body, with é.",
            html = Present("<p>The HTML body, with é.</p>"),
            attachments = Chunk(Email.Attachment(EmailLiterals.mediaTypeOf("text", "plain"), Present("résumé naïve.txt"), attached))
        )
        arrival(account, "round trip")(EmailSmtp.let(account.smtp)(EmailSmtp.send(message))).map { (id, _, received) =>
            assert(received.messageId == Present(id))
            assert(received.from.map(_.address) == Chunk(account.address.address))
            assert(received.text.trim == "The plain body, with é.")
            assert(received.html.map(_.trim) == Present("<p>The HTML body, with é.</p>"))
            assert(received.attachments.map(_.fileName) == Chunk(Present("résumé naïve.txt")))
            assert(received.attachments.map(a => Chunk.from(a.content.toArray)) == Chunk(Chunk.from(attached.toArray)))
        }
    }

    "a reply names the message it answers, and arrives threaded" in server() { mail =>
        val account  = mail.account(User.Test)
        val original = Email.Message(from = Chunk(account.address), to = Chunk(account.address), subject = "thread", text = "Question.")
        arrival(account, "thread")(EmailSmtp.let(account.smtp)(EmailSmtp.send(original))).map { (id, _, first) =>
            arrival(account, "Re: thread") {
                EmailSmtp.let(account.smtp)(EmailSmtp.reply(first, Email.Message(from = Chunk(account.address), text = "Answer.")))
            }.map { (_, _, reply) =>
                assert(reply.inReplyTo == Chunk(id))
                assert(reply.references == Chunk(id))
            }
        }
    }

    "custom commands reach the submission server after authenticating" in server() { mail =>
        EmailSmtp.let(mail.smtp(User.Test))(EmailSmtp.custom(Seq(EmailLiterals.smtpCommandOf("NOOP")))).map { replies =>
            assert(replies.map(_.code) == Chunk(250))
        }
    }

    "a message submitted over implicit TLS on 465 and over STARTTLS on 587 arrives" in server() { mail =>
        val account = mail.account(User.Test)
        Kyo.foreach(Chunk(Security.Implicit, Security.StartTls)) { security =>
            val subject = security.toString
            val message = Email.Message(from = Chunk(account.address), to = Chunk(account.address), subject = subject, text = "Sent.")
            arrival(account, subject)(EmailSmtp.let(mail.smtp(User.Test, mail.tls(security)))(EmailSmtp.send(message)))
                .map((id, _, received) => (id, received.messageId))
        }.map(ids => assert(ids.forall((sent, received) => received == Present(sent))))
    }

    "a header past ASCII and an 8-bit forwarded message go out with SMTPUTF8 and 8BITMIME and arrive unchanged" in server() { mail =>
        val account   = mail.account(User.Test)
        val forwarded = Span.from("Subject: café\r\n\r\nbon appétit, 日本\r\n".getBytes("UTF-8"))
        val message   = Email.Message(
            from = Chunk(account.address),
            to = Chunk(account.address),
            subject = "eight bit",
            text = "Forwarded.",
            attachments = Chunk(Email.Attachment(EmailLiterals.mediaTypeOf("message", "rfc822"), Absent, forwarded)),
            headers = Chunk(Email.Header("X-Note", "café 日本"))
        )
        arrival(account, "eight bit")(EmailSmtp.let(account.smtp)(EmailSmtp.send(message))).map { (_, _, received) =>
            assert(received.headers.filter(_.name.equalsIgnoreCase("X-Note")).map(_.value) == Chunk("café 日本"))
            assert(received.attachments.map(a => Chunk.from(a.content.toArray)) == Chunk(Chunk.from(forwarded.toArray)))
        }
    }

    "a Bcc recipient receives the message, and no copy carries a Bcc header" in server() { mail =>
        val test    = mail.account(User.Test)
        val other   = mail.account(User.Other)
        val message =
            Email.Message(from = Chunk(test.address), to = Chunk(test.address), bcc = Chunk(other.address), subject = "bcc", text = "Hi.")
        arrival(other, "bcc")(arrival(test, "bcc")(EmailSmtp.let(test.smtp)(EmailSmtp.send(message)))).map {
            case ((_, _, toCopy), _, bccCopy) =>
                assert(toCopy.bcc.isEmpty)
                assert(bccCopy.bcc.isEmpty)
                assert(bccCopy.to.map(_.address) == Chunk(test.address.address))
        }
    }

    "a recipient the server does not know is refused at RCPT, with its reply" in server() { mail =>
        val account = mail.account(User.Test)
        val nobody  = Email.Address("nobody@example.com")
        val message = Email.Message(from = Chunk(account.address), to = Chunk(account.address, nobody), subject = "refused", text = "Hi.")
        Abort.run[EmailSendFailure](EmailSmtp.let(account.smtp)(EmailSmtp.send(message))).map {
            case Result.Failure(refused: EmailRecipientRefusedException) =>
                assert(refused.refusals.map(r => (r.recipient.address, r.code)) == Chunk(("nobody@example.com", 550)))
                assert(refused.refusals.map(_.enhancedCode.map(_.show)) == Chunk(Present("5.1.1")))
            case other => fail(s"expected the recipient to be refused, got $other")
        }
    }

    "a message past the server's SIZE is refused before MAIL, naming the limit" in server() { mail =>
        val account = mail.account(User.Test)
        val big     = Span.from(Array.fill(EmailLiveServer.SizeLimit.toBytes.toInt)(7.toByte))
        val message = Email.Message(
            from = Chunk(account.address),
            to = Chunk(account.address),
            subject = "large",
            text = "Large.",
            attachments = Chunk(Email.Attachment(EmailLiterals.mediaTypeOf("application", "octet-stream"), Present("big.bin"), big))
        )
        Abort.run[EmailSendFailure](EmailSmtp.let(account.smtp)(EmailSmtp.send(message))).map {
            case Result.Failure(large: EmailMessageTooLargeException) =>
                assert(large.limit == Present(EmailLiveServer.SizeLimit))
                assert(large.size.toBytes > EmailLiveServer.SizeLimit.toBytes)
            case other => fail(s"expected the message to be too large, got $other")
        }
    }

    "a wrong password is rejected with the server's 535 reply" in server() { mail =>
        Abort.run[EmailSmtpCustomFailure] {
            val wrong = EmailLiterals.passwordAuthOf(User.Test.login, EmailLiterals.passwordOf(mail.password + "x"))
            EmailSmtp.let(mail.smtp(User.Test).auth(wrong))(EmailSmtp.custom(Seq(EmailLiterals.smtpCommandOf("NOOP"))))
        }.map {
            case Result.Failure(rejected: EmailAuthenticationException) =>
                assert(rejected.user == User.Test.login)
                rejected.reply match
                    case EmailAuthenticationException.Reply.Smtp(code, enhanced, _) =>
                        assert(code == 535)
                        assert(enhanced.map(_.show) == Present("5.7.8"))
                    case other => fail(s"expected an SMTP reply, got $other")
                end match
            case other => fail(s"expected the credential to be rejected, got $other")
        }
    }

    "lines that begin with a dot, and a lone dot, arrive as written" in server() { mail =>
        val account = mail.account(User.Test)
        val text    = "first\r\n.\r\n.hidden\r\n..two dots\r\nlast"
        val message = Email.Message(from = Chunk(account.address), to = Chunk(account.address), subject = "dots", text = text)
        arrival(account, "dots")(EmailSmtp.let(account.smtp)(EmailSmtp.send(message))).map { (_, _, received) =>
            assert(received.text.stripSuffix("\r\n") == text)
        }
    }

    "each of the to and cc recipients receives one copy, with both headers" in server() { mail =>
        val test    = mail.account(User.Test)
        val other   = mail.account(User.Other)
        val message =
            Email.Message(from = Chunk(test.address), to = Chunk(test.address), cc = Chunk(other.address), subject = "cc", text = "Hi.")
        arrival(other, "cc")(arrival(test, "cc")(EmailSmtp.let(test.smtp)(EmailSmtp.send(message)))).map {
            case ((_, _, toCopy), _, ccCopy) =>
                Kyo.foreach(Chunk(test, other))(account => EmailImap.let(account.imap)(EmailImap.status(inbox))).map { statuses =>
                    assert(Chunk(toCopy, ccCopy).map(m => (m.to.map(_.address), m.cc.map(_.address))) ==
                        Chunk.fill(2)((Chunk(test.address.address), Chunk(other.address.address))))
                    assert(statuses.map(_.messages) == Chunk(1L, 1L))
                }
        }
    }

    "a message under the server's SIZE is delivered whole" in server() { mail =>
        val account = mail.account(User.Test)
        // Base64 grows the attachment by a third, so half the limit leaves room for the encoding and the headers.
        val content = Span.from(Array.tabulate((EmailLiveServer.SizeLimit.toBytes / 2).toInt)(i => (i % 251).toByte))
        val message = Email.Message(
            from = Chunk(account.address),
            to = Chunk(account.address),
            subject = "under the limit",
            text = "Large.",
            attachments = Chunk(Email.Attachment(EmailLiterals.mediaTypeOf("application", "octet-stream"), Present("half.bin"), content))
        )
        arrival(account, "under the limit")(EmailSmtp.let(account.smtp)(EmailSmtp.send(message))).map { (_, _, received) =>
            assert(received.attachments.map(a => Chunk.from(a.content.toArray)) == Chunk(Chunk.from(content.toArray)))
        }
    }

end EmailSmtpLiveTest
