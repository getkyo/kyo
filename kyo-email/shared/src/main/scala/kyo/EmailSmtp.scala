package kyo

import kyo.internal.email.CommandLine
import kyo.internal.email.mime.Threading
import kyo.internal.email.smtp.SmtpSession

/** An SMTP submission client (RFC 6409): the config, in `Env[EmailSmtp[E]]`, that the verbs on the companion read.
  *
  * No connection is held. Each verb opens its own, over TLS from the first byte or upgraded with STARTTLS, authenticates with the
  * config's credential, runs, and closes it before returning, whatever the outcome. So the token computation of an OAuth credential runs
  * once per verb, and its failure `E` reaches the verb's row as raised. `let` binds the client for a computation and needs no `Scope`,
  * since nothing outlives a verb.
  *
  * `E` is part of the client's type and of the `Env` key, so a verb reaches only a client whose token failure is on its own row. A verb's
  * `E` is inferred from the enclosing `let`; with two enclosing clients of different `E`, a verb whose `E` both conform to reaches the
  * outer one, the first bound.
  *
  * Each exchange, the message data included, is bounded by the config's `commandTimeout`. Server text copied into a failure or a
  * [[EmailSmtp.Reply]] never holds the credential: the password, the token and every form in which they crossed the wire are replaced
  * with `<redacted>`.
  *
  * @tparam E
  *   the failure of the OAuth token computation, `Nothing` for a password
  * @see
  *   [[EmailSmtpConfig]] for the server, the credential and the timeouts
  * @see
  *   [[EmailImap]] for receiving
  */
final class EmailSmtp[+E] private (private[kyo] val config: EmailSmtpConfig[E])

object EmailSmtp:

    /** Runs `v` with a client for `config`. */
    def let[E, A, S](config: EmailSmtpConfig[E])(v: A < (S & Env[EmailSmtp[E]]))(using Frame, Tag[EmailSmtp[E]]): A < S =
        Env.run(new EmailSmtp(config))(v)

    /** Submits `message` and answers the `Message-ID` it was sent with.
      *
      * `messageId` and `date` are filled when absent: a random id at the sender's domain, and `Clock.now`. The message goes to every
      * address of `to`, `cc` and `bcc`, and `Bcc` is never written into it. The envelope sender is `sender`, or the single `from`.
      *
      * A message without `from`, with several `from` and no `sender`, or without a recipient fails with
      * [[EmailIncompleteMessageException]]; an envelope address that is not an `addr-spec` with [[EmailInvalidAddressException]]; a
      * message the renderer refuses with the renderer's failure. None of them opens a connection or runs the token computation.
      *
      * The server's `SIZE` (RFC 1870) is checked before `MAIL`. A refused recipient makes the client send `RSET` and fail with one
      * [[EmailRecipientRefusedException]] listing every refusal, so the message reaches none of them. Once the server accepts the data the
      * message is sent, and nothing after that fails the verb.
      */
    def send[E](message: Email.Message)(using
        Frame,
        Tag[EmailSmtp[E]]
    ): Email.MessageId < (Async & Abort[EmailSendFailure | E] & Env[EmailSmtp[E]]) =
        Env.use[EmailSmtp[E]](client => SmtpSession.send("send", client.config, message))

    /** Submits `reply` as a reply to `original` (RFC 5322 section 3.6.4), filling what `reply` leaves empty: `In-Reply-To` is the
      * original's id; `References` the original's `References`, or its single `In-Reply-To` when it has none, then its id; the subject
      * `Re: ` and the original's, unless that already begins with `Re:` in any case; and, when `reply` names no recipient, `to` as the
      * original's `replyTo`, or its `from`. Then as [[send]].
      */
    def reply[E](original: Email.Message, reply: Email.Message)(using
        Frame,
        Tag[EmailSmtp[E]]
    ): Email.MessageId < (Async & Abort[EmailSendFailure | E] & Env[EmailSmtp[E]]) =
        Env.use[EmailSmtp[E]](client => SmtpSession.send("reply", client.config, Threading.reply(original, reply)))

    /** Sends each of `commands` in order after authenticating and answers each reply; a `4xx` or `5xx` reply fails with
      * [[EmailSmtpRejectedException]] naming the command's first word, and the rest are not sent.
      */
    def custom[E](commands: Seq[EmailSmtp.Command])(using
        Frame,
        Tag[EmailSmtp[E]]
    ): Chunk[EmailSmtp.Reply] < (Async & Abort[EmailSmtpCustomFailure | E] & Env[EmailSmtp[E]]) =
        Env.use[EmailSmtp[E]](client => SmtpSession.custom("custom", client.config, Chunk.from(commands)))

    /** What the server answered to one `EmailSmtp.custom` command line: the reply code, the enhanced status code when the server sent one
      * (RFC 3463), and the text of each line of the reply.
      *
      * A line's text is what followed the code and its separator, without the enhanced code when the line began with one. Octets that are
      * not UTF-8 appear as U+FFFD. The credential never appears: every form of it is replaced with `<redacted>`, as in every failure.
      *
      * @see
      *   [[kyo.EmailSmtp.Command]] for the command lines `custom` sends
      */
    final case class Reply(code: Int, enhancedCode: Maybe[EmailSmtp.EnhancedStatusCode], lines: Chunk[String]) derives CanEqual

    /** An SMTP enhanced status code (RFC 3463), such as the `5.1.1` in `550 5.1.1 User unknown`.
      *
      * The reply code says whether a command succeeded and whether a failure is temporary. The enhanced code says why, in a form a caller
      * can branch on: `5.1.1` is an unknown mailbox, `4.2.2` a full one, `5.7.8` rejected credentials. `statusClass` is one of RFC 3463's
      * three classes, and says whether the failure is permanent; `subject` and `detail` are the other two fields, each at most three
      * digits.
      *
      * `init` validates the ranges and fails with [[EmailInvalidEnhancedStatusCodeException]] on a value outside them. Its `Schema`
      * writes the three fields and decodes them through the same check: a value outside the ranges fails decode with a
      * `ConstructorRejectedException` holding that exception.
      *
      * @see
      *   [[kyo.EmailSmtpRejectedException]] and [[kyo.EmailRecipientRefusedException]] for where codes appear
      */
    final case class EnhancedStatusCode private (statusClass: EmailSmtp.EnhancedStatusCode.StatusClass, subject: Int, detail: Int)
        derives CanEqual:

        /** The code in its dotted form, such as `5.1.1`. */
        def show: String = s"${statusClass.digit}.$subject.$detail"

    end EnhancedStatusCode

    object EnhancedStatusCode:

        /** The class of an enhanced status code (RFC 3463 section 3.1), with the digit that writes it. */
        enum StatusClass(val digit: Int) derives CanEqual:
            case Success             extends StatusClass(2)
            case PersistentTransient extends StatusClass(4)
            case Permanent           extends StatusClass(5)

            /** Whether the class reports a permanent failure. */
            def permanent: Boolean = this == Permanent
        end StatusClass

        given (using Frame): Schema[EmailSmtp.EnhancedStatusCode] =
            Schema.derivedVia((statusClass: StatusClass, subject: Int, detail: Int) => init(statusClass, subject, detail))

        /** The code `statusClass.subject.detail`, or [[EmailInvalidEnhancedStatusCodeException]] when `subject` or `detail` is outside 0
          * to 999.
          */
        def init(statusClass: StatusClass, subject: Int, detail: Int)(using
            Frame
        ): Result[EmailInvalidEnhancedStatusCodeException, EmailSmtp.EnhancedStatusCode] =
            if subject < 0 || subject > 999 then
                Result.fail(
                    EmailInvalidEnhancedStatusCodeException(EmailInvalidEnhancedStatusCodeException.Violation.SubjectOutOfRange(subject))
                )
            else if detail < 0 || detail > 999 then
                Result.fail(
                    EmailInvalidEnhancedStatusCodeException(EmailInvalidEnhancedStatusCodeException.Violation.DetailOutOfRange(detail))
                )
            else Result.succeed(new EmailSmtp.EnhancedStatusCode(statusClass, subject, detail))
    end EnhancedStatusCode

    /** One SMTP command line for the escape hatch, `EmailSmtp.custom`: the module adds the CRLF.
      *
      * The line is validated at construction, so what the caller wrote is exactly one command. It cannot hold CR, LF or NUL, which would
      * end the line and run the rest as a second command, and its UTF-8 form is at most 510 octets, RFC 5321 section 4.5.3.1.4's 512
      * including the CRLF.
      *
      * `custom` sends each line after authenticating and reads its one reply, so a command whose exchange does more is refused by its first
      * word, in any case: `DATA` and `BDAT` (the message content follows them), `STARTTLS` (a TLS upgrade), `AUTH` (a credential the client
      * cannot redact, on a connection already authenticated) and `QUIT` (the client ends the connection itself). `init` fails with
      * [[EmailInvalidCommandException]] otherwise.
      *
      * @see
      *   [[kyo.EmailImap.Command]] for the IMAP counterpart
      */
    final class Command private (val value: String) derives CanEqual:
        override def equals(other: Any): Boolean =
            other match
                case that: EmailSmtp.Command => that.value == value
                case _                       => false

        override def hashCode: Int = value.hashCode

        override def toString: String = s"EmailSmtp.Command(${EmailException.printable(value)})"
    end Command

    object Command:

        /** RFC 5321 section 4.5.3.1.4: a command line is at most 512 octets including its CRLF. */
        inline val MaxLength = 510

        /** The command line `value`, or [[EmailInvalidCommandException]] when it is not one command `custom` can carry. */
        def init(value: String)(using Frame): Result[EmailInvalidCommandException, EmailSmtp.Command] =
            CommandLine.check(value, MaxLength).flatMap { line =>
                val command = kyo.internal.Ascii.toUpper(line.takeWhile(_ != ' '))
                if refused.contains(command) then
                    Result.fail(
                        EmailInvalidCommandException(EmailInvalidCommandException.Problem.Unsupported(
                            EmailException.Protocol.Smtp,
                            command
                        ))
                    )
                else Result.succeed(new EmailSmtp.Command(line))
                end if
            }

        private val refused = Set("DATA", "BDAT", "STARTTLS", "AUTH", "QUIT")

        // Why each refused command is outside what `custom` carries; `custom` sends a line and reads its one reply, after authenticating.
        private[kyo] def unsupported(command: String): String =
            command match
                case "DATA" | "BDAT" => "the message content follows it, which custom does not send (RFC 5321 section 4.1.1.4, RFC 3030)"
                case "STARTTLS"      => "it upgrades the connection to TLS, which the client does only when it connects (RFC 3207)"
                case "AUTH"          =>
                    "it carries a credential the client cannot redact, and the connection is already authenticated (RFC 4954 section 4)"
                case _ =>
                    "the server closes the connection after it, and the client ends every connection with its own QUIT " +
                        "(RFC 5321 section 4.1.1.10)"

    end Command

end EmailSmtp
