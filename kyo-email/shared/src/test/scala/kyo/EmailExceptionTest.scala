package kyo

import kyo.EmailException.Protocol
import kyo.EmailSmtp.EnhancedStatusCode.StatusClass
import kyo.net.NetConnectException
import kyo.net.NetTlsHandshakeException

class EmailExceptionTest extends kyo.test.Test[Any]:

    private val archive = EmailLiterals.mailboxOf("Archive")
    private val uid     = EmailLiterals.uidOf(archive, EmailLiterals.uidValidityOf(3857529045L), 42L)
    private val nobody  = Email.Address("nobody@example.com")

    // Built away from the assertions: in development mode a message embeds the source lines around its construction site, so a needle
    // written on that line would match the snippet rather than the message.
    private def transport(kind: EmailTransportException.Kind, method: String = "fetch") =
        EmailTransportException(method, kind, "imap.example.com", 993, Absent)

    private def closedWithoutText = transport(EmailTransportException.Kind.ConnectionClosed(Absent))

    private def idleDropped(serverText: Maybe[String]) = EmailIdleDroppedException("run", "imap.example.com", archive, serverText)

    private def connect(kind: EmailConnectException.Kind, cause: Maybe[kyo.net.NetException] = Absent) =
        EmailConnectException("init", kind, "imap.example.com", 993, Absent)(cause)

    "messages are built from the fields" - {
        "a connect failure names the operation, what failed, the host and port, and keeps a kyo-net cause where it has one" in {
            import EmailConnectException.Kind
            val refused = NetConnectException("imap.example.com", 993, "refused")
            val opened  = connect(Kind.Connect, Present(refused))
            assert(opened.getMessage.contains("init: the connection could not be opened (imap.example.com:993)"))
            assert(opened.getCause == refused)
            val untrusted = NetTlsHandshakeException("imap.example.com", 993, "untrusted")
            assert(connect(Kind.Tls, Present(untrusted)).getCause == untrusted)
            assert(connect(Kind.StartTlsUnavailable).getCause == null)
            assert(connect(Kind.StartTlsUnavailable).getMessage.contains("does not offer STARTTLS"))
        }
        "a transport failure names the operation, what failed, the host and port, and carries no cause" in {
            val garbled = transport(EmailTransportException.Kind.Protocol("* GARBAGE"))
            assert(garbled.getMessage.contains("fetch: the server sent an invalid response: * GARBAGE (imap.example.com:993)"))
            assert(garbled.getCause == null)
        }
        "a closed connection includes the server's parting text when there is one" in {
            assert(
                transport(EmailTransportException.Kind.ConnectionClosed(Present("Autologout")))
                    .getMessage.contains("fetch: the connection closed: Autologout (imap.example.com:993)")
            )
            val silent = closedWithoutText.getMessage
            assert(silent.contains("fetch: the connection closed (imap.example.com:993)"))
            assert(!silent.contains("closed:"))
        }
        "an incomplete completion names what it lacked, as the module's words rather than the server's text" in {
            assert(transport(EmailTransportException.Kind.Incomplete("UIDVALIDITY")).getMessage.contains(
                "fetch: the server completed the command without UIDVALIDITY (imap.example.com:993)"
            ))
        }
        "a timeout names the deadline that expired" in {
            val command =
                EmailTransportException("fetch", EmailTransportException.Kind.Timeout, "imap.example.com", 993, Present(1.minute))
            assert(command.getMessage.contains("fetch: the server did not reply in time (imap.example.com:993, after " + 1.minute.show +
                ")"))
        }
        "an IMAP rejection names the command, status, response code and text" in {
            val ex = EmailImapCommandException(
                "status",
                "SELECT",
                EmailImapCommandException.Status.No,
                Present(EmailImap.ResponseCode.NoPerm),
                "Access denied"
            )
            assert(ex.getMessage.contains("status: IMAP SELECT failed with No [NOPERM]: Access denied"))
            assert(ex.method == "status")
            assert(ex.responseCode == Present(EmailImap.ResponseCode.NoPerm))
        }
        "an IMAP authentication failure names the user, mechanism and response code" in {
            val ex = EmailAuthenticationException(
                "init",
                "imap.example.com",
                "ada@example.com",
                Email.Auth.Mechanism.Plain,
                EmailAuthenticationException.Reply.Imap(Present(EmailImap.ResponseCode.AuthenticationFailed), "Invalid credentials")
            )
            assert(ex.getMessage.contains(
                "IMAP server imap.example.com rejected Plain authentication for ada@example.com: [AUTHENTICATIONFAILED] Invalid credentials"
            ))
        }
        "an SMTP authentication failure names the reply and enhanced codes" in {
            val ex = EmailAuthenticationException(
                "send",
                "smtp.example.com",
                "ada@example.com",
                Email.Auth.Mechanism.XOAuth2,
                EmailAuthenticationException.Reply.Smtp(
                    535,
                    Present(EmailLiterals.statusCodeOf(StatusClass.Permanent, 7, 8)),
                    "Username and Password not accepted"
                )
            )
            assert(ex.getMessage.contains(
                "SMTP server smtp.example.com rejected XOAuth2 authentication for ada@example.com: 535 5.7.8 Username and Password not accepted"
            ))
            assert(ex.reply.protocol == Protocol.Smtp)
        }
        "a mechanism mismatch lists what was wanted and what was offered" in {
            val ex = EmailAuthMechanismUnavailableException(
                "init",
                Protocol.Imap,
                "imap.example.com",
                Chunk(Email.Auth.Mechanism.Login, Email.Auth.Mechanism.Plain),
                Chunk.empty
            )
            assert(ex.getMessage.contains("offers none of Login, Plain; it offers no authentication mechanism"))
        }
        "a missing capability names it and its protocol" in {
            val move = EmailCapabilityMissingException("move", "imap.example.com", EmailCapabilityMissingException.Capability.ImapMove)
            assert(move.getMessage.contains("move: IMAP server imap.example.com does not support MOVE or UIDPLUS"))
            val search =
                EmailCapabilityMissingException("search", "imap.example.com", EmailCapabilityMissingException.Capability.ImapUtf8Search)
            assert(search.getMessage.contains("search: IMAP server imap.example.com does not support searching with CHARSET UTF-8"))
            val utf8 = EmailCapabilityMissingException("send", "smtp.example.com", EmailCapabilityMissingException.Capability.SmtpUtf8)
            assert(utf8.getMessage.contains("SMTP server smtp.example.com does not support SMTPUTF8"))
        }
        "a UIDVALIDITY change names both values" in {
            val ex = EmailUidValidityChangedException(
                "fetch",
                archive,
                EmailLiterals.uidValidityOf(3857529045L),
                EmailLiterals.uidValidityOf(3857529046L)
            )
            assert(ex.getMessage.contains("UIDVALIDITY of mailbox 'Archive' changed from 3857529045 to 3857529046"))
        }
        "a dropped IDLE names the operation, host and mailbox, and the server's parting text when there is one" in {
            val bye = idleDropped(Present("shutting down")).getMessage
            assert(bye.contains("run: IMAP server imap.example.com dropped the IDLE on mailbox 'Archive': shutting down"))
            val silent = idleDropped(Absent).getMessage
            assert(silent.contains("run: IMAP server imap.example.com dropped the IDLE on mailbox 'Archive'"))
            assert(!silent.contains("'Archive':"))
        }
        "a missing part names the part path" in {
            assert(EmailPartNotFoundException(
                "fetchPart",
                uid,
                Chunk(1, 2)
            ).getMessage.contains("UID 42 in mailbox 'Archive' has no part 1.2"))
        }
        "a MIME failure on the root names the message" in {
            assert(
                EmailMimeException(Chunk.empty, EmailMimeException.Problem.NestingTooDeep(32))
                    .getMessage.contains("malformed MIME in the message: multiparts nested deeper than 32")
            )
        }
        "a decode failure names the part and the problem" in {
            assert(
                EmailTransferDecodeException(Chunk(1, 1), EmailTransferDecodeException.Problem.UnsupportedTransferEncoding("x-uuencode"))
                    .getMessage.contains("could not decode part 1.1: unsupported transfer encoding x-uuencode")
            )
            assert(
                EmailTransferDecodeException(Chunk(3), EmailTransferDecodeException.Problem.TruncatedBase64)
                    .getMessage.contains("could not decode part 3: base64 content ends with an incomplete byte")
            )
        }
        "a size failure shows the size and the server limit" in {
            val ex = EmailMessageTooLargeException("send", ByteSize.fromBytes(40000000L), Present(ByteSize.fromBytes(35882577L)))
            assert(ex.getMessage.contains(ByteSize.fromBytes(40000000L).show))
            assert(ex.getMessage.contains(ByteSize.fromBytes(35882577L).show))
        }
        "a refusal lists each refused recipient with its codes" in {
            val ex = EmailRecipientRefusedException(
                "send",
                Chunk(
                    EmailRecipientRefusedException.Refusal(
                        nobody,
                        550,
                        Present(EmailLiterals.statusCodeOf(StatusClass.Permanent, 1, 1)),
                        "User unknown"
                    ),
                    EmailRecipientRefusedException.Refusal(Email.Address("full@example.com"), 452, Absent, "Mailbox full")
                )
            )
            assert(ex.getMessage.contains("nobody@example.com (550 5.1.1 User unknown), full@example.com (452 Mailbox full)"))
        }
        "an incomplete message names its operation and what it lacks" in {
            assert(
                EmailIncompleteMessageException("reply", EmailIncompleteMessageException.Missing.Sender)
                    .getMessage.contains("reply: the message cannot be sent without a Sender address when From holds several addresses")
            )
            assert(
                EmailIncompleteMessageException("send", EmailIncompleteMessageException.Missing.From)
                    .getMessage.contains("send: the message cannot be sent without a From address")
            )
        }
    }

    "SMTP rejections" - {
        "a 5xx reply is permanent" in {
            val ex =
                EmailSmtpRejectedException(
                    "send",
                    "MAIL",
                    550,
                    Present(EmailLiterals.statusCodeOf(StatusClass.Permanent, 7, 1)),
                    "Sender rejected"
                )
            assert(ex.permanent)
            assert(ex.getMessage.contains("send: SMTP MAIL rejected with permanent failure 550 5.7.1: Sender rejected"))
        }
        "a 4xx reply is temporary" in {
            val ex = EmailSmtpRejectedException("send", "MAIL", 451, Absent, "Try again later")
            assert(!ex.permanent)
            assert(ex.getMessage.contains("SMTP MAIL rejected with temporary failure 451: Try again later"))
        }
        "a refusal knows whether it is permanent" in {
            assert(EmailRecipientRefusedException.Refusal(nobody, 550, Absent, "User unknown").permanent)
            assert(!EmailRecipientRefusedException.Refusal(nobody, 452, Absent, "Mailbox full").permanent)
        }
    }

    "server text" - {
        "escapes line breaks, so a server cannot forge extra lines in a log" in {
            val ex = transport(EmailTransportException.Kind.Protocol("* OK\r\nA001 OK forged"))
            assert(ex.getMessage.contains("""* OK\r\nA001 OK forged"""))
            assert(!ex.getMessage.contains("* OK\r\n"))
        }
        "is shortened past 200 characters" in {
            val ex = transport(EmailTransportException.Kind.Protocol("x" * 500))
            assert(ex.getMessage.contains("x" * 200 + "..."))
            assert(!ex.getMessage.contains("x" * 201))
        }
        "an address with a line break is rendered escaped" in {
            val ex = EmailInvalidAddressException("ada@example.com\r\nBcc: victim@example.com")
            assert(ex.getMessage.contains("""ada@example.com\r\nBcc: victim@example.com"""))
        }
        "each header problem names the header and says what cannot be written, a parameter name escaped" in {
            import EmailInvalidHeaderException.Problem
            val cases = Seq(
                ("Bad Name", Problem.InvalidName, "header Bad Name cannot be written: the name is not a valid field name"),
                ("X-Note", Problem.LineBreakInValue, "header X-Note cannot be written: the value contains a line break"),
                ("X-Note", Problem.ControlCharacterInValue, "header X-Note cannot be written: the value contains a control character"),
                ("X-Long", Problem.LineTooLong, "header X-Long cannot be written: a line of the value would be longer than 998 octets"),
                ("Date", Problem.DateOutOfRange, "header Date cannot be written: the date is before year 0000"),
                (
                    "Content-Type",
                    Problem.UnwritableParameterName("a*\nb"),
                    """header Content-Type cannot be written: the parameter name a*\nb holds *"""
                )
            )
            cases.foreach { (name, problem, message) =>
                assert(EmailInvalidHeaderException(name, problem).getMessage.contains(message), message)
            }
        }
    }

end EmailExceptionTest
