package kyo

import kyo.internal.email.mime.Threading
import kyo.internal.email.smtp.SmtpClient
import kyo.internal.email.smtp.SmtpSession

/** Sending mail through an SMTP submission server (RFC 6409), as an effect: `send` and `reply` require `EmailSend`, and
  * [[EmailSend.run]] handles it with an [[EmailSmtpConfig]].
  *
  * No connection is held. Each verb opens its own, over TLS from the first byte or upgraded with STARTTLS, authenticates with the
  * config's account, runs, and closes it before returning, whatever the outcome. So an OAuth token's computation runs once per verb, and
  * its failure reaches the verb's row as an [[EmailTokenException]]. `run` needs no `Scope`, since nothing outlives a verb.
  *
  * Each exchange, the message data included, is bounded by the config's `commandTimeout`. Server text copied into a failure never holds
  * the credential: the password, the token and every form in which they crossed the wire are replaced with `<redacted>`.
  *
  * @see
  *   [[EmailSmtpConfig]] for the server, the account and the timeouts
  * @see
  *   [[EmailReceive]] for receiving
  * @see
  *   [[Email.run]] to handle both effects at once
  */
opaque type EmailSend <: Env[SmtpClient] = Env[SmtpClient]

object EmailSend:

    /** Runs `v` with its verbs sending through the server `config` names. */
    def run[A, S](config: EmailSmtpConfig)(v: A < (EmailSend & S))(using Frame): A < S =
        Env.run(new SmtpClient(config))(v)

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
    def send(message: Email.Message)(using Frame): Email.MessageId < (Async & Abort[EmailSendFailure] & EmailSend) =
        Env.use[SmtpClient](client => SmtpSession.send("send", client.config, message))

    /** Submits `reply` as a reply to `original` (RFC 5322 section 3.6.4), filling what `reply` leaves empty: `In-Reply-To` is the
      * original's id; `References` the original's `References`, or its single `In-Reply-To` when it has none, then its id; the subject
      * `Re: ` and the original's, unless that already begins with `Re:` in any case; and, when `reply` names no recipient, `to` as the
      * original's `replyTo`, or its `from`. Then as [[send]].
      */
    def reply(original: Email.Message, reply: Email.Message)(using
        Frame
    ): Email.MessageId < (Async & Abort[EmailSendFailure] & EmailSend) =
        Env.use[SmtpClient](client => SmtpSession.send("reply", client.config, Threading.reply(original, reply)))

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
    final case class EnhancedStatusCode private (statusClass: EmailSend.EnhancedStatusCode.StatusClass, subject: Int, detail: Int)
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

        given Schema[EmailSend.EnhancedStatusCode] =
            Schema.derivedVia((statusClass: StatusClass, subject: Int, detail: Int) => init(statusClass, subject, detail))

        /** The code `statusClass.subject.detail`, or [[EmailInvalidEnhancedStatusCodeException]] when `subject` or `detail` is outside 0
          * to 999.
          */
        def init(statusClass: StatusClass, subject: Int, detail: Int)(using
            Frame
        ): Result[EmailInvalidEnhancedStatusCodeException, EmailSend.EnhancedStatusCode] =
            if subject < 0 || subject > 999 then
                Result.fail(
                    EmailInvalidEnhancedStatusCodeException(EmailInvalidEnhancedStatusCodeException.Violation.SubjectOutOfRange(subject))
                )
            else if detail < 0 || detail > 999 then
                Result.fail(
                    EmailInvalidEnhancedStatusCodeException(EmailInvalidEnhancedStatusCodeException.Violation.DetailOutOfRange(detail))
                )
            else Result.succeed(new EmailSend.EnhancedStatusCode(statusClass, subject, detail))
    end EnhancedStatusCode

end EmailSend
