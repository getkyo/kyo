package kyo

import kyo.net.NetException

/** The sealed base of every failure the email module raises.
  *
  * Operations never fail with this base. Each public operation has its own sealed failure trait (`EmailConnectFailure`, `EmailSendFailure`,
  * `EmailFetchPartFailure`, ...) naming exactly the leaves it can raise, and that trait is the operation's `Abort` row, so a caller handles
  * the cases that operation actually has and the compiler checks the match. A leaf mixes in every trait of the operations that raise it: a
  * dropped connection belongs to every network operation, a refused recipient only to sending. Each cause has exactly one leaf.
  *
  * Leaves carry typed fields and build their message from them. `method` names the operation that failed (`"init"`, `"run"`, `"fetch"`,
  * `"send"`, ...). An [[EmailConnectException]] keeps the kyo-net exception that caused it as its `cause` for the four kinds that have one.
  * Text a server sent (an IMAP response text, an SMTP reply) is a field of its own, rendered with control characters escaped and long
  * values shortened. No message renders a password or a token, and an IMAP or SMTP leaf names the command it failed on, never the command's
  * arguments, which for a login would include the secret.
  *
  * The failure of an OAuth token computation is not a leaf: it reaches the row as the caller's own error (see [[Email.Auth]]).
  *
  * [[EmailInvalidConfigException]], [[EmailInvalidIdException]], [[EmailInvalidEnhancedStatusCodeException]],
  * [[EmailInvalidTokenException]], [[EmailInvalidFlagException]] and [[EmailInvalidCommandException]] mix in no operation trait: each is
  * the failure of building a value with an `init`, never of an operation. [[Email.MediaType]] is kyo-mime's, and its `init` fails with
  * kyo-mime's [[kyo.mime.MimeInvalidMediaTypeException]].
  *
  * @see
  *   [[kyo.EmailTransportException]] for the transport category
  * @see
  *   [[kyo.EmailImapCommandException]] and [[kyo.EmailSmtpRejectedException]] for server rejections
  * @see
  *   [[kyo.EmailInvalidConfigException]] for the validation of a config's `init`
  */
sealed abstract class EmailException(message: String)(using Frame) extends KyoException(message)

object EmailException:

    /** The mail protocol a connection-level failure happened on. */
    enum Protocol(val label: String) derives CanEqual:
        case Imap extends Protocol("IMAP")
        case Smtp extends Protocol("SMTP")
    end Protocol

    private[kyo] def printable(text: String): String =
        val escaped = text.flatMap {
            case '\r'             => "\\r"
            case '\n'             => "\\n"
            case '\t'             => "\\t"
            case c if c.isControl => f"\\u${c.toInt}%04x"
            case c                => c.toString
        }
        if escaped.length <= MaxRenderedText then escaped else escaped.take(MaxRenderedText) + "..."
    end printable

    private[kyo] def showPart(part: Chunk[Int]): String =
        if part.isEmpty then "the message" else s"part ${part.mkString(".")}"

    private[kyo] def showAddress(address: Email.Address): String = printable(address.address)

    private inline def MaxRenderedText = 200

end EmailException

/** The failures of opening an IMAP session: connecting, securing, reading the capabilities and authenticating. */
sealed trait EmailConnectFailure extends EmailException

/** The failures of the receive loop over a mailbox, once its reconnect policy gives up. */
sealed trait EmailRunFailure extends EmailException

/** The failures of listing a session's mailboxes. */
sealed trait EmailListMailboxesFailure extends EmailException

/** The failures of reading a mailbox's status (message counts, next UID, UIDVALIDITY). */
sealed trait EmailStatusFailure extends EmailException

/** The failures of searching a mailbox. */
sealed trait EmailSearchFailure extends EmailException

/** The failures of fetching messages' flags, headers and body structure by UID. */
sealed trait EmailFetchFailure extends EmailException

/** The failures of fetching and parsing a whole message by UID. */
sealed trait EmailFetchMessageFailure extends EmailException

/** The failures of fetching one part of a message by UID and decoding its transfer encoding. */
sealed trait EmailFetchPartFailure extends EmailException

/** The failures of adding flags to messages. */
sealed trait EmailAddFlagsFailure extends EmailException

/** The failures of removing flags from messages. */
sealed trait EmailRemoveFlagsFailure extends EmailException

/** The failures of moving messages to another mailbox. */
sealed trait EmailMoveFailure extends EmailException

/** The failures of a raw IMAP command sent through the session's escape hatch. */
sealed trait EmailImapCustomFailure extends EmailException

/** The failures of submitting a message, or a reply, over SMTP. */
sealed trait EmailSendFailure extends EmailException

/** The failures of a raw SMTP exchange sent through the escape hatch. */
sealed trait EmailSmtpCustomFailure extends EmailException

/** The failures of parsing a MIME message into the model. */
sealed trait EmailParseFailure extends EmailException

/** The failures of rendering the model into a MIME message. */
sealed trait EmailRenderFailure extends EmailException

/** The connection to `host:port` could not be established during the operation `method`: the TCP connect, the name lookup, the TLS
  * handshake (implicit or after STARTTLS), or STARTTLS itself. Only an operation that opens a connection raises it: `init`, `let` and
  * `run` for IMAP, where a session connects once and its verbs reuse it, and every SMTP submission, which connects anew. `timeout` is the
  * `connectTimeout` that expired for `ConnectTimeout`. `cause` is the kyo-net exception for `Connect`, `Dns`, `Tls` and `ConnectTimeout`.
  */
final case class EmailConnectException(
    method: String,
    kind: EmailConnectException.Kind,
    host: String,
    port: Int,
    timeout: Maybe[Duration]
)(val cause: Maybe[NetException])(using Frame) extends EmailException(
        s"$method: ${kind.describe} ($host:$port" + timeout.fold("")(t => s", after ${t.show}") + ")"
    )
    with EmailConnectFailure
    with EmailRunFailure
    with EmailSendFailure
    with EmailSmtpCustomFailure:

    override def getCause(): Throwable = cause.getOrElse(null)

end EmailConnectException

object EmailConnectException:
    /** What failed, from kyo-net's sealed hierarchy and STARTTLS. */
    enum Kind derives CanEqual:
        /** The TCP connect was refused or the host was unreachable. */
        case Connect

        /** The host name did not resolve. */
        case Dns

        /** The TLS handshake failed, for implicit TLS or a STARTTLS upgrade: an untrusted certificate, a host name mismatch, no common
          * version.
          */
        case Tls

        /** The trust material or the TLS provider could not be set up, before any byte was sent. */
        case TlsSetup

        /** The TCP connect or the TLS handshake did not complete within `connectTimeout`. */
        case ConnectTimeout

        /** The server was configured for STARTTLS but does not offer it; the connection was closed before any credential was sent. */
        case StartTlsUnavailable

        /** kyo-net has no I/O backend on this platform. */
        case BackendUnavailable

        def describe: String =
            this match
                case Connect             => "the connection could not be opened"
                case Dns                 => "the host name did not resolve"
                case Tls                 => "the TLS handshake failed"
                case TlsSetup            => "TLS could not be set up"
                case ConnectTimeout      => "the connection was not established in time"
                case StartTlsUnavailable =>
                    "the server does not offer STARTTLS; the connection was closed rather than continue unencrypted"
                case BackendUnavailable => "no network backend is available on this platform"
    end Kind
end EmailConnectException

/** A failure of an established connection rather than of a command, at `host:port` during the operation `method`: a reply that did not
  * arrive within `commandTimeout` (then `timeout`), the connection closing, or a response that is not the protocol. Every operation that
  * talks to a server can meet it; opening the connection fails with [[EmailConnectException]] instead.
  *
  * Server text the kinds copy (`ConnectionClosed`'s parting text, `Protocol`'s unreadable line) has the configured password and OAuth token
  * removed, in their plain and base64 forms, before it is stored.
  */
final case class EmailTransportException(
    method: String,
    kind: EmailTransportException.Kind,
    host: String,
    port: Int,
    timeout: Maybe[Duration]
)(using Frame) extends EmailException(
        s"$method: ${kind.describe} ($host:$port" + timeout.fold("")(t => s", after ${t.show}") + ")"
    )
    with EmailConnectFailure
    with EmailRunFailure
    with EmailListMailboxesFailure
    with EmailStatusFailure
    with EmailSearchFailure
    with EmailFetchFailure
    with EmailFetchMessageFailure
    with EmailFetchPartFailure
    with EmailAddFlagsFailure
    with EmailRemoveFlagsFailure
    with EmailMoveFailure
    with EmailImapCustomFailure
    with EmailSendFailure
    with EmailSmtpCustomFailure

object EmailTransportException:
    /** What failed on the established connection. */
    enum Kind derives CanEqual:
        /** A command's reply did not complete within `commandTimeout`. */
        case Timeout

        /** The connection closed while a command was in flight; `serverText` is the IMAP `BYE` text, or the SMTP `421` text to whatever
          * command it came, when the server sent one. SMTP defines `421` as the server closing the channel (RFC 5321 section 4.2.1), so it
          * is never a refusal of the command or of a recipient.
          */
        case ConnectionClosed(serverText: Maybe[String])

        /** The server sent `received`, which is not a valid response of the protocol at that point of the exchange. */
        case Protocol(received: String)

        /** The server completed a command without the response RFC 9051 requires of it, named by `missing`: `STATUS`'s data (section
          * 6.3.11), a `SELECT`'s `UIDVALIDITY` (section 6.3.2), or the `UIDNEXT` a receive loop starting with new mail needs.
          */
        case Incomplete(missing: String)

        def describe: String =
            this match
                case Timeout                      => "the server did not reply in time"
                case ConnectionClosed(serverText) =>
                    "the connection closed" + serverText.fold("")(text => s": ${EmailException.printable(text)}")
                case Protocol(received)  => s"the server sent an invalid response: ${EmailException.printable(received)}"
                case Incomplete(missing) => s"the server completed the command without $missing"
    end Kind
end EmailTransportException

/** The server answered the operation `method` with a refusal of its own. IMAP and SMTP share no code space, so each leaf carries its
  * protocol's code: an IMAP response code (RFC 9051 section 7.1, RFC 5530) or an SMTP reply code with its enhanced status (RFC 3463).
  * [[EmailImapCommandException]] and [[EmailSmtpRejectedException]] are the catch-alls for refusals no named leaf covers.
  */
sealed abstract class EmailApiException(message: String)(using Frame) extends EmailException(message):
    def method: String

/** The server at `host` rejected the credential of `user` through `mechanism`. `reply` is the server's answer: an IMAP response code such
  * as `AUTHENTICATIONFAILED` with its text, or an SMTP reply such as `535 5.7.8` with its text.
  */
final case class EmailAuthenticationException(
    method: String,
    host: String,
    user: String,
    mechanism: Email.Auth.Mechanism,
    reply: EmailAuthenticationException.Reply
)(using Frame) extends EmailApiException(
        s"$method: ${reply.protocol.label} server $host rejected $mechanism authentication for ${EmailException.printable(user)}: " +
            reply.describe
    )
    with EmailConnectFailure
    with EmailRunFailure
    with EmailSendFailure
    with EmailSmtpCustomFailure

object EmailAuthenticationException:
    /** The server's answer to the rejected authentication. */
    enum Reply derives CanEqual:
        /** An IMAP `NO`, with its response code when the server sent one. */
        case Imap(responseCode: Maybe[EmailImap.ResponseCode], text: String)

        /** An SMTP failure reply, with its enhanced status code when the server sent one. */
        case Smtp(code: Int, enhancedCode: Maybe[EmailSmtp.EnhancedStatusCode], text: String)

        def protocol: EmailException.Protocol =
            this match
                case _: Imap => EmailException.Protocol.Imap
                case _: Smtp => EmailException.Protocol.Smtp

        def describe: String =
            this match
                case Imap(responseCode, text) =>
                    responseCode.fold("")(c => s"[${EmailException.printable(c.show)}] ") + EmailException.printable(text)
                case Smtp(code, enhancedCode, text) =>
                    s"$code " + enhancedCode.fold("")(c => s"${c.show} ") + EmailException.printable(text)
    end Reply
end EmailAuthenticationException

/** The server at `host` offers none of the mechanisms the configured authentication can use. `wanted` are the mechanisms the module could
  * have used, `offered` what the server advertised.
  */
final case class EmailAuthMechanismUnavailableException(
    method: String,
    protocol: EmailException.Protocol,
    host: String,
    wanted: Chunk[Email.Auth.Mechanism],
    offered: Chunk[String]
)(using Frame) extends EmailApiException(
        s"$method: ${protocol.label} server $host offers none of ${wanted.mkString(", ")}; it offers " +
            (if offered.isEmpty then "no authentication mechanism" else offered.map(EmailException.printable).mkString(", "))
    )
    with EmailConnectFailure
    with EmailRunFailure
    with EmailSendFailure
    with EmailSmtpCustomFailure

/** The server at `host` lacks `capability`, which the operation needs and has no safe fallback for. */
final case class EmailCapabilityMissingException(method: String, host: String, capability: EmailCapabilityMissingException.Capability)(
    using Frame
) extends EmailApiException(s"$method: ${capability.protocol.label} server $host does not support ${capability.describe}")
    with EmailSearchFailure
    with EmailMoveFailure
    with EmailSendFailure

object EmailCapabilityMissingException:
    /** A capability an operation cannot do without. */
    enum Capability derives CanEqual:
        /** IMAP `MOVE` (RFC 6851), or `UIDPLUS` (RFC 4315) for the copy-and-expunge fallback. Without either, a move could only expunge
          * other messages marked deleted.
          */
        case ImapMove

        /** IMAP search with `CHARSET UTF-8`, which a query holding text past ASCII needs; a server without it answers `BADCHARSET`
          * (RFC 9051 section 7.1).
          */
        case ImapUtf8Search

        /** SMTP `SMTPUTF8` (RFC 6531), which an address or a header with characters past ASCII needs. */
        case SmtpUtf8

        /** SMTP `8BITMIME` (RFC 6152), which a message whose content holds octets past ASCII needs. */
        case Smtp8BitMime

        def protocol: EmailException.Protocol =
            this match
                case ImapMove | ImapUtf8Search => EmailException.Protocol.Imap
                case SmtpUtf8 | Smtp8BitMime   => EmailException.Protocol.Smtp

        def describe: String =
            this match
                case ImapMove       => "MOVE or UIDPLUS"
                case ImapUtf8Search => "searching with CHARSET UTF-8"
                case SmtpUtf8       => "SMTPUTF8"
                case Smtp8BitMime   => "8BITMIME"
    end Capability
end EmailCapabilityMissingException

/** The IMAP server answered `command` with `NO` or `BAD`. `command` is the command name, never its arguments. `responseCode` is the
  * bracketed response code (RFC 9051 section 7.1) when the server sent one, and `text` is the human-readable text after it.
  */
final case class EmailImapCommandException(
    method: String,
    command: String,
    status: EmailImapCommandException.Status,
    responseCode: Maybe[EmailImap.ResponseCode],
    text: String
)(using Frame) extends EmailApiException(
        s"$method: IMAP ${EmailException.printable(command)} failed with $status" +
            responseCode.fold("")(code => s" [${EmailException.printable(code.show)}]") +
            s": ${EmailException.printable(text)}"
    )
    with EmailConnectFailure
    with EmailRunFailure
    with EmailListMailboxesFailure
    with EmailStatusFailure
    with EmailSearchFailure
    with EmailFetchFailure
    with EmailFetchMessageFailure
    with EmailFetchPartFailure
    with EmailAddFlagsFailure
    with EmailRemoveFlagsFailure
    with EmailMoveFailure
    with EmailImapCustomFailure

object EmailImapCommandException:
    /** A tagged completion other than `OK`. `No` is an operational refusal, `Bad` a command the server could not parse or accept. */
    enum Status derives CanEqual:
        case No
        case Bad
    end Status
end EmailImapCommandException

/** The mailbox `mailbox` does not exist on the server: a `NO` carrying the `NONEXISTENT` response code, or, for the destination of a
  * move, the `TRYCREATE` code a server sends when a COPY or MOVE target is missing.
  */
final case class EmailMailboxNotFoundException(method: String, mailbox: Email.MailboxName)(using Frame)
    extends EmailApiException(s"$method: mailbox '${EmailException.printable(mailbox.value)}' does not exist")
    with EmailRunFailure
    with EmailStatusFailure
    with EmailSearchFailure
    with EmailFetchFailure
    with EmailFetchMessageFailure
    with EmailFetchPartFailure
    with EmailAddFlagsFailure
    with EmailRemoveFlagsFailure
    with EmailMoveFailure

/** The UIDVALIDITY of `mailbox` is now `current`, not the `expected` a UID was issued under, so every UID stored for the mailbox is invalid
  * and the caller must resynchronize.
  */
final case class EmailUidValidityChangedException(
    method: String,
    mailbox: Email.MailboxName,
    expected: Email.UidValidity,
    current: Email.UidValidity
)(using Frame) extends EmailApiException(
        s"$method: UIDVALIDITY of mailbox '${EmailException.printable(mailbox.value)}' changed from ${expected.value} " +
            s"to ${current.value}; UIDs issued under the old value no longer identify messages"
    )
    with EmailRunFailure
    with EmailFetchFailure
    with EmailFetchMessageFailure
    with EmailFetchPartFailure
    with EmailAddFlagsFailure
    with EmailRemoveFlagsFailure
    with EmailMoveFailure

/** No message with `uid` exists in its mailbox any more. */
final case class EmailMessageNotFoundException(method: String, uid: Email.Uid)(using Frame)
    extends EmailApiException(s"$method: no message with UID ${uid.value} in mailbox '${EmailException.printable(uid.mailbox.value)}'")
    with EmailFetchMessageFailure
    with EmailFetchPartFailure

/** The message `uid` has no part at the path `part` of its body structure. */
final case class EmailPartNotFoundException(method: String, uid: Email.Uid, part: Chunk[Int])(using Frame)
    extends EmailApiException(
        s"$method: message with UID ${uid.value} in mailbox '${EmailException.printable(uid.mailbox.value)}' " +
            s"has no ${EmailException.showPart(part)}"
    )
    with EmailFetchPartFailure

/** The connection to `host` closed, or the server sent `BYE`, while the receive loop of `method` was idling on `mailbox`: between the
  * server's `+` that began the IDLE and the client's `DONE`. `serverText` is the `BYE` text when the server sent one, with the password and
  * OAuth token removed as in [[EmailTransportException]]. A drop while the IDLE is being issued, or after `DONE`, is an
  * `EmailTransportException` as for any command. The loop retries either under the reconnect schedule, so this reaches the caller only when
  * the schedule allows no further attempt.
  */
final case class EmailIdleDroppedException(method: String, host: String, mailbox: Email.MailboxName, serverText: Maybe[String])(using
    Frame
) extends EmailException(
        s"$method: IMAP server $host dropped the IDLE on mailbox '${EmailException.printable(mailbox.value)}'" +
            serverText.fold("")(text => s": ${EmailException.printable(text)}")
    )
    with EmailRunFailure

/** The SMTP server answered `command` with the failure reply `code`. `command` is the command name, never its arguments. `enhancedCode` is
  * the RFC 3463 status code when the server sent one, and `text` is the reply text.
  */
final case class EmailSmtpRejectedException(
    method: String,
    command: String,
    code: Int,
    enhancedCode: Maybe[EmailSmtp.EnhancedStatusCode],
    text: String
)(using Frame) extends EmailApiException(
        s"$method: SMTP ${EmailException.printable(command)} rejected with ${
                if code >= 500 then "permanent" else "temporary"
            } failure $code" +
            enhancedCode.fold("")(enhanced => s" ${enhanced.show}") +
            s": ${EmailException.printable(text)}"
    )
    with EmailSendFailure
    with EmailSmtpCustomFailure:

    /** Whether the rejection is permanent (a `5xx` reply) rather than temporary (a `4xx` reply that a later retry may clear). */
    def permanent: Boolean = code >= 500

end EmailSmtpRejectedException

/** The SMTP server refused one or more recipients, so the message was sent to none of them. */
final case class EmailRecipientRefusedException(method: String, refusals: Chunk[EmailRecipientRefusedException.Refusal])(using Frame)
    extends EmailApiException(
        s"$method: SMTP server refused " + refusals.map(_.describe).mkString(", ")
    )
    with EmailSendFailure

object EmailRecipientRefusedException:
    /** One refused recipient, with the reply code, the enhanced status code when the server sent one, and the reply text. */
    final case class Refusal(recipient: Email.Address, code: Int, enhancedCode: Maybe[EmailSmtp.EnhancedStatusCode], text: String)
        derives CanEqual:

        /** Whether the refusal is permanent (a `5xx` reply) rather than temporary (a `4xx` reply). */
        def permanent: Boolean = code >= 500

        private[kyo] def describe: String =
            s"${EmailException.showAddress(recipient)} ($code" + enhancedCode.fold("")(c => s" ${c.show}") +
                s" ${EmailException.printable(text)})"
    end Refusal
end EmailRecipientRefusedException

/** The message of `size` exceeds what the server accepts: larger than the `limit` the server advertised with the `SIZE` extension (RFC
  * 1870), checked before `MAIL`; or refused by the server, with `552` to `MAIL` or after the data, or with enhanced status `5.3.4` to
  * `MAIL`, `DATA` or the data, where `limit` is absent unless the server advertised one.
  */
final case class EmailMessageTooLargeException(method: String, size: ByteSize, limit: Maybe[ByteSize])(using Frame)
    extends EmailApiException(
        s"$method: message of ${size.show} is too large" + limit.fold("")(l => s" for the server limit of ${l.show}")
    )
    with EmailSendFailure

/** The MIME structure of `part` is beyond what the parser accepts. `part` is the path of the part in the body structure, empty for the
  * message itself. Everything else malformed in a message degrades without loss (see [[Email.Attachment]]) rather than failing it.
  */
final case class EmailMimeException(part: Chunk[Int], problem: EmailMimeException.Problem)(using Frame)
    extends EmailException(s"malformed MIME in ${EmailException.showPart(part)}: ${problem.describe}")
    with EmailParseFailure
    with EmailFetchMessageFailure

object EmailMimeException:
    /** What is beyond the parser's bounds. */
    enum Problem derives CanEqual:
        /** Multiparts nested deeper than `limit`, the bound that keeps a hostile message from exhausting the parser. */
        case NestingTooDeep(limit: Int)

        def describe: String =
            this match
                case NestingTooDeep(limit) => s"multiparts nested deeper than $limit"
    end Problem
end EmailMimeException

/** The content of `part` could not be decoded into the exact bytes a part fetch returns. `part` is the path of the part in the body
  * structure. Parsing a whole message never raises this: a part it cannot decode is kept with its bytes as they arrived.
  */
final case class EmailTransferDecodeException(part: Chunk[Int], problem: EmailTransferDecodeException.Problem)(using Frame)
    extends EmailException(s"could not decode ${EmailException.showPart(part)}: ${problem.describe}")
    with EmailFetchPartFailure

object EmailTransferDecodeException:
    /** What could not be decoded. */
    enum Problem derives CanEqual:
        /** A base64 body whose alphabet characters, once the characters outside the alphabet are ignored (RFC 2045 section 6.8), end with a
          * single character: six bits that cannot complete a byte.
          */
        case TruncatedBase64

        /** A `Content-Transfer-Encoding` that is not one of RFC 2045's, so the encoded bytes have no defined decoding (RFC 2045 section
          * 6.4).
          */
        case UnsupportedTransferEncoding(encoding: String)

        def describe: String =
            this match
                case TruncatedBase64                       => "base64 content ends with an incomplete byte"
                case UnsupportedTransferEncoding(encoding) => s"unsupported transfer encoding ${EmailException.printable(encoding)}"
    end Problem
end EmailTransferDecodeException

/** `address` cannot be written into a header or an SMTP envelope (`Bcc` and the reverse path reach only the envelope): it is not an RFC
  * 5322 `addr-spec` (a local part, `@` and a domain, with the UTF-8 RFC 6532 allows), so it has no `@`, holds a line break or another
  * control character, or has a form the reader would take back as a different mailbox (`a b@c`, read as `ab@c`).
  */
final case class EmailInvalidAddressException(address: String)(using Frame)
    extends EmailException(s"invalid address ${EmailException.printable(address)}")
    with EmailRenderFailure
    with EmailSendFailure

/** The header `name` cannot be written: `problem` says why. The value is never rendered, since it may carry anything. */
final case class EmailInvalidHeaderException(name: String, problem: EmailInvalidHeaderException.Problem)(using Frame)
    extends EmailException(s"header ${EmailException.printable(name)} cannot be written: ${problem.describe}")
    with EmailRenderFailure
    with EmailSendFailure

object EmailInvalidHeaderException:
    /** Why a header cannot be written. */
    enum Problem derives CanEqual:
        /** The name is empty or holds a character outside the printable ASCII range, or a colon (RFC 5322 section 3.6.8). */
        case InvalidName

        /** The value contains a line break, which would start a new header. */
        case LineBreakInValue

        /** The value contains a control character other than a tab, which RFC 5322 section 2.2 does not allow in a header. */
        case ControlCharacterInValue

        /** The value has a run without white space long enough that its line would pass the 998 octets of RFC 5322 section 2.1.1. */
        case LineTooLong

        /** The date is before year 0000, which RFC 5322's four-digit year cannot write. */
        case DateOutOfRange

        /** A media type parameter whose name holds `*`, which RFC 2231 section 7 excludes from a written name: a reader takes it for the
          * continuation or encoding syntax.
          */
        case UnwritableParameterName(parameter: String)

        def describe: String =
            this match
                case InvalidName                        => "the name is not a valid field name"
                case LineBreakInValue                   => "the value contains a line break"
                case ControlCharacterInValue            => "the value contains a control character"
                case LineTooLong                        => "a line of the value would be longer than 998 octets"
                case DateOutOfRange                     => "the date is before year 0000"
                case UnwritableParameterName(parameter) => s"the parameter name ${EmailException.printable(parameter)} holds *"
    end Problem
end EmailInvalidHeaderException

/** The message cannot be submitted because it lacks `missing`. */
final case class EmailIncompleteMessageException(method: String, missing: EmailIncompleteMessageException.Missing)(using Frame)
    extends EmailException(s"$method: the message cannot be sent without ${missing.describe}")
    with EmailSendFailure

object EmailIncompleteMessageException:
    /** The part of a message that submission requires. */
    enum Missing derives CanEqual:
        /** A `From` address. */
        case From

        /** A `Sender` address, which RFC 5322 section 3.6.2 requires when `From` holds more than one address. */
        case Sender

        /** At least one `To`, `Cc` or `Bcc` address. */
        case Recipients

        def describe: String =
            this match
                case From       => "a From address"
                case Sender     => "a Sender address when From holds several addresses"
                case Recipients => "a recipient"
    end Missing
end EmailIncompleteMessageException

/** An [[EmailImapConfig]], [[EmailSmtpConfig]] or [[Email.Auth]] was given an invalid value: the failure of its `init`. It mixes in no
  * operation trait.
  */
final case class EmailInvalidConfigException(violation: EmailInvalidConfigException.Violation)(using Frame)
    extends EmailException(s"invalid email configuration: ${violation.describe}")

object EmailInvalidConfigException:

    /** A config field whose duration is range-checked, with the range it must fall in. */
    enum Setting(val label: String, val requirement: String) derives CanEqual:
        case ConnectTimeout    extends Setting("connectTimeout", "positive")
        case CommandTimeout    extends Setting("commandTimeout", "positive")
        case IdleRenewal       extends Setting("idleRenewal", "positive and at most 29 minutes")
        case PollInterval      extends Setting("pollInterval", "positive and at most 29 minutes")
        case MaxResponseLength extends Setting("maxResponseLength", "positive and at most 2147483647 bytes")
        case RetryMaxDelay     extends Setting("retryMaxDelay", "positive")
    end Setting

    /** What is invalid. */
    enum Violation derives CanEqual:
        case EmptyHost
        case EmptyUser
        case ControlCharacterInUser
        case InvalidClientName(clientName: String)
        case PortOutOfRange(port: Int)
        case DurationOutOfRange(setting: Setting, value: Duration)
        case SizeOutOfRange(setting: Setting, value: ByteSize)
        case TlsVersionRange(min: Email.Tls.Version, max: Email.Tls.Version)

        def describe: String =
            this match
                case EmptyHost                     => "host is empty"
                case EmptyUser                     => "user is empty"
                case ControlCharacterInUser        => "user contains a control character"
                case InvalidClientName(clientName) =>
                    s"clientName ${EmailException.printable(clientName)} is neither a domain nor an address literal"
                case PortOutOfRange(port)               => s"port $port is outside 1 to 65535"
                case DurationOutOfRange(setting, value) => s"${setting.label} is ${value.show}; it must be ${setting.requirement}"
                case SizeOutOfRange(setting, value)     => s"${setting.label} is ${value.show}; it must be ${setting.requirement}"
                case TlsVersionRange(min, max)          => s"tls minVersion $min is above maxVersion $max, which allows no version"
    end Violation

    private[kyo] def checkTimeout(setting: Setting, value: Duration): Maybe[Violation] =
        if value <= Duration.Zero then Present(Violation.DurationOutOfRange(setting, value)) else Absent

    private[kyo] def checkTls(tls: Email.Tls): Maybe[Violation] =
        if tls.minVersion.ordinal > tls.maxVersion.ordinal then Present(Violation.TlsVersionRange(tls.minVersion, tls.maxVersion))
        else Absent

end EmailInvalidConfigException

/** An identifier ([[Email.MessageId]], [[Email.ContentId]], [[Email.MailboxName]], [[Email.UidValidity]] or [[Email.Uid]]) was given an
  * impossible value: the failure of its `init`, or the rejection a decode failure holds. It mixes in no operation trait.
  */
final case class EmailInvalidIdException(violation: EmailInvalidIdException.Violation)(using Frame)
    extends EmailException(s"invalid email id: ${violation.describe}")

object EmailInvalidIdException:
    /** What is invalid. */
    enum Violation derives CanEqual:
        case EmptyMessageId
        case ControlCharacterInMessageId
        case DelimiterInMessageId
        case UnreadableMessageId
        case EmptyContentId
        case ControlCharacterInContentId
        case DelimiterInContentId
        case UnreadableContentId
        case EmptyMailboxName
        case ControlCharacterInMailboxName

        /** A lone UTF-16 surrogate, which no encoding of a mailbox name can carry. */
        case UnpairedSurrogateInMailboxName

        /** A stored mailbox name holding a character above U+00FF, so it is not a wire form of one character per octet. */
        case MailboxNameNotOctets
        case UidValidityOutOfRange(value: Long)
        case UidOutOfRange(value: Long)

        def describe: String =
            this match
                case EmptyMessageId              => "message id is empty"
                case ControlCharacterInMessageId => "message id contains a control character"
                case DelimiterInMessageId        => "message id contains <, > or a space"
                case UnreadableMessageId         => "message id contains ( outside a quoted string, or a quoted string that does not close"
                case EmptyContentId              => "content id is empty"
                case ControlCharacterInContentId => "content id contains a control character"
                case DelimiterInContentId        => "content id contains <, > or a space"
                case UnreadableContentId         => "content id contains ( outside a quoted string, or a quoted string that does not close"
                case EmptyMailboxName            => "mailbox name is empty"
                case ControlCharacterInMailboxName  => "mailbox name contains a control character"
                case UnpairedSurrogateInMailboxName => "mailbox name contains an unpaired surrogate"
                case MailboxNameNotOctets           => "stored mailbox name holds a character above U+00FF, so it is not a wire form"
                case UidValidityOutOfRange(value)   => s"UIDVALIDITY $value is outside 1 to 4294967295"
                case UidOutOfRange(value)           => s"UID $value is outside 1 to 4294967295"
    end Violation
end EmailInvalidIdException

/** An [[Email.Password]] or [[Email.OAuthToken]] was given a value no authentication exchange can carry: the failure of its `init`. It
  * mixes in no operation trait. The message names a position, never the secret.
  */
final case class EmailInvalidTokenException(problem: EmailInvalidTokenException.Problem)(using Frame)
    extends EmailException(s"invalid credential: ${problem.describe}")

object EmailInvalidTokenException:
    /** What is invalid, with the zero-based position of the offending character where there is one. */
    enum Problem derives CanEqual:
        case EmptyPassword

        /** NUL ends a SASL `PLAIN` field (RFC 4616), and CR or LF would end an IMAP `LOGIN` line and start another command. */
        case LineBreakOrNulInPassword(position: Int)

        case EmptyToken

        /** A character outside RFC 6750's `b64token`, such as the `\u0001` that separates XOAUTH2's fields (RFC 7628's format). */
        case CharacterOutsideToken(position: Int)

        def describe: String =
            this match
                case EmptyPassword                      => "the password is empty"
                case LineBreakOrNulInPassword(position) => s"the password holds NUL, CR or LF at position $position"
                case EmptyToken                         => "the OAuth token is empty"
                case CharacterOutsideToken(position)    =>
                    s"the OAuth token holds a character outside RFC 6750's b64token at position $position"
    end Problem
end EmailInvalidTokenException

/** An [[Email.Flag.Keyword]] was built or decoded with `flag`, which is not a non-empty IMAP atom without a backslash (RFC 9051 section 9
  * `flag-keyword`), or an [[Email.Flag]] or [[Email.Flag.Other]] was decoded with `flag`, which is not the text of the flag it was decoded
  * as: `\Recent`, or text with a control character, a space or a parenthesis. The failure of `Keyword.init`, or the rejection a decode
  * failure holds: it mixes in no operation trait.
  */
final case class EmailInvalidFlagException(flag: String)(using Frame)
    extends EmailException(
        s"invalid flag ${EmailException.printable(flag)}: a keyword is a non-empty IMAP atom with no backslash, and a flag the module " +
            "does not model is non-empty text with no control character, space or parenthesis, other than \\Recent"
    )

/** An [[EmailImap.Command]] or [[EmailSmtp.Command]] was given a line the module cannot send as one command: the failure of its `init`. It
  * mixes in no operation trait.
  */
final case class EmailInvalidCommandException(problem: EmailInvalidCommandException.Problem)(using Frame)
    extends EmailException(s"invalid command: ${problem.describe}")

object EmailInvalidCommandException:
    /** What is invalid. */
    enum Problem derives CanEqual:
        case Empty

        /** CR, LF or NUL at `position`: the line would end there and the rest would run as another command. */
        case LineBreakOrNul(position: Int)

        /** An IMAP line ending in a literal marker (`{n}` or `{n+}`), which announces octets the command never sends. */
        case Literal

        /** Longer than `limit` octets of UTF-8. */
        case TooLong(limit: Int)

        /** A command of `protocol` whose exchange `custom` cannot carry, named by its first word: for IMAP `IDLE`, `AUTHENTICATE`,
          * `LOGIN`, `STARTTLS` or `COMPRESS`; for SMTP `DATA`, `BDAT`, `STARTTLS`, `AUTH` or `QUIT`.
          */
        case Unsupported(protocol: EmailException.Protocol, command: String)

        def describe: String =
            this match
                case Empty                          => "the command is empty"
                case LineBreakOrNul(position)       => s"the command holds CR, LF or NUL at position $position"
                case Literal                        => "the command ends with a literal, which would leave the server waiting"
                case TooLong(limit)                 => s"the command is longer than $limit octets"
                case Unsupported(protocol, command) =>
                    val reason = protocol match
                        case EmailException.Protocol.Imap => EmailImap.Command.unsupported(command)
                        case EmailException.Protocol.Smtp => EmailSmtp.Command.unsupported(command)
                    s"${protocol.label} $command cannot be sent through custom: $reason"
    end Problem
end EmailInvalidCommandException

/** An [[EmailSmtp.EnhancedStatusCode]] was given a field outside the ranges of RFC 3463: the failure of its `init`, or the rejection a
  * decode failure holds. It mixes in no operation trait.
  */
final case class EmailInvalidEnhancedStatusCodeException(violation: EmailInvalidEnhancedStatusCodeException.Violation)(using Frame)
    extends EmailException(s"invalid enhanced status code: ${violation.describe}")

object EmailInvalidEnhancedStatusCodeException:
    /** What is out of range. */
    enum Violation derives CanEqual:
        case SubjectOutOfRange(subject: Int)
        case DetailOutOfRange(detail: Int)

        def describe: String =
            this match
                case SubjectOutOfRange(subject) => s"subject $subject is outside 0 to 999"
                case DetailOutOfRange(detail)   => s"detail $detail is outside 0 to 999"
    end Violation
end EmailInvalidEnhancedStatusCodeException
