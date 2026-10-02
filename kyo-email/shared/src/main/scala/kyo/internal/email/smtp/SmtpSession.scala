package kyo.internal.email.smtp

import kyo.*
import kyo.EmailTransportException.Kind
import kyo.internal.Ascii
import kyo.internal.charset.Utf8
import kyo.internal.email.mime.AddressCodec
import kyo.internal.email.net.LineConnection
import kyo.internal.email.net.Redactor
import kyo.internal.email.net.Sasl
import kyo.internal.email.net.TlsOptions
import kyo.internal.email.smtp.SmtpCodec.*
import scala.annotation.tailrec

/** One SMTP submission's connection (RFC 5321, RFC 6409): the greeting, `EHLO`, STARTTLS when configured, `AUTH`, then the commands of one
  * verb, each exchange bounded by `commandTimeout`. It is used from one fiber and closed when its verb returns, so it holds no lock and no
  * state beyond the connection.
  */
final private[kyo] class SmtpSession private (
    method: String,
    host: String,
    port: Int,
    connection: LineConnection,
    redactor: Redactor,
    commandTimeout: Duration
):
    import SmtpSession.*

    private def command(line: String)(using Frame): Reply < (Async & Abort[EmailTransportException]) =
        replied(connection.write(method, Utf8.encode(line + "\r\n")).andThen(read))

    // RFC 5321 sections 3.8 and 4.2.1: a 421 to any command is the server closing the channel, never a refusal of that command, so the
    // connection is closed with the server's text and nothing more is written.
    private def replied(v: => Reply < (Async & Abort[EmailTransportException]))(using
        Frame
    ): Reply < (Async & Abort[EmailTransportException]) =
        bounded(v).map { reply =>
            if reply.code == 421 then connection.close.andThen(Abort.fail(closed(reply))) else reply
        }

    private def read(using Frame): Reply < (Async & Abort[EmailTransportException]) =
        Loop(Maybe.empty[Partial]) { partial =>
            connection.readLine(method, remaining(partial)).map { line =>
                next(partial, line) match
                    case Step.More(more)       => Loop.continue(Present(more))
                    case Step.Complete(reply)  => Loop.done(reply)
                    case Step.Malformed(shown) => Abort.fail(transport(Kind.Protocol(shown)))
            }
        }

    // One exchange under commandTimeout. A timeout, a close or a malformed reply leaves the exchange's state unknown, so the connection is
    // closed; the failure's server text is redacted before anything cuts it.
    private def bounded[A](v: => A < (Async & Abort[EmailTransportException]))(using Frame): A < (Async & Abort[EmailTransportException]) =
        Abort.run[Timeout](Async.timeout(commandTimeout)(Abort.run[EmailTransportException](v))).map {
            case Result.Success(Result.Success(value)) => value
            case Result.Success(Result.Failure(ex))    => connection.close.andThen(Abort.fail(redacted(ex)))
            case Result.Success(Result.Panic(ex))      => connection.close.andThen(Abort.panic(ex))
            case Result.Failure(_) => connection.close.andThen(Abort.fail(transport(Kind.Timeout, Present(commandTimeout))))
            case Result.Panic(ex)  => connection.close.andThen(Abort.panic(ex))
        }

    private def handshake(config: EmailSmtpConfig[?], credential: Credential)(using Frame): Extensions < (Async & Abort[SessionFailure]) =
        replied(read).map { greeting =>
            if greeting.code == 220 then ehlo(config.clientName)
            // RFC 5321 section 3.1: a server may answer the connection with 554 in place of 220.
            else Abort.fail(refusedOr(greeting)(rejected("CONNECT", greeting)))
        }.map { offered =>
            config.tls match
                case _: Email.Tls.Implicit => offered
                case _: Email.Tls.StartTls =>
                    if !offered.offers("STARTTLS") then Abort.fail(startTlsUnavailable)
                    else
                        command("STARTTLS").map { reply =>
                            if reply.code != 220 then Abort.fail(refusedOr(reply)(rejected("STARTTLS", reply)))
                            // RFC 3207 section 4.2: the extensions offered before TLS are discarded, and EHLO is sent again.
                            else
                                connection.startTls(
                                    method,
                                    TlsOptions.netConfig(config.tls),
                                    config.connectTimeout
                                ).andThen(ehlo(config.clientName))
                        }
        }.map(offered => authenticate(credential, offered).andThen(offered))

    private def ehlo(clientName: String)(using Frame): Extensions < (Async & Abort[SessionFailure]) =
        command(s"EHLO $clientName").map { reply =>
            if reply.code == 250 then extensions(reply) else Abort.fail(refusedOr(reply)(rejected("EHLO", reply)))
        }

    private def authenticate(credential: Credential, offered: Extensions)(using Frame): Unit < (Async & Abort[SessionFailure]) =
        val mechanisms = offered.parameters("AUTH").getOrElse(Chunk.empty).map(Ascii.toUpper)
        credential match
            case Credential.Password(user, password) =>
                if mechanisms.contains("PLAIN") then exchange(user, Email.Auth.Mechanism.Plain, s"AUTH PLAIN ${Sasl.plain(user, password)}")
                else if mechanisms.contains("LOGIN") then
                    exchange(user, Email.Auth.Mechanism.Login, "AUTH LOGIN", Chunk(Sasl.base64(user), Sasl.base64(password.value)))
                else unavailable(Chunk(Email.Auth.Mechanism.Plain, Email.Auth.Mechanism.Login), mechanisms)
            case Credential.Token(user, token) =>
                if mechanisms.contains("XOAUTH2") then
                    exchange(user, Email.Auth.Mechanism.XOAuth2, s"AUTH XOAUTH2 ${Sasl.xoauth2(user, token)}")
                else unavailable(Chunk(Email.Auth.Mechanism.XOAuth2), mechanisms)
        end match
    end authenticate

    // `AUTH` and its continuations (RFC 4954 section 4): each `334` is answered with the next of `answers`, and once they are spent with
    // one empty line, which lets a server that sent an XOAUTH2 error challenge send its failure reply. A further `334` is not the protocol.
    private def exchange(user: String, mechanism: Email.Auth.Mechanism, first: String, answers: Chunk[String] = Chunk.empty)(using
        Frame
    ): Unit < (Async & Abort[SessionFailure]) =
        Loop(first, answers.append("")) { (line, rest) =>
            command(line).map { reply =>
                if reply.code == 235 then Loop.done(())
                else if reply.code == 334 then
                    rest.headMaybe match
                        case Present(answer) => Loop.continue(answer, rest.drop(1))
                        case Absent          => Abort.fail(unexpected(reply))
                else if refusesCredential(reply.code) then
                    val (enhanced, text) = described(reply)
                    Abort.fail(EmailAuthenticationException(
                        method,
                        host,
                        user,
                        mechanism,
                        EmailAuthenticationException.Reply.Smtp(reply.code, enhanced, text)
                    ))
                else Abort.fail(refusedOr(reply)(rejected("AUTH", reply)))
            }
        }

    // RFC 4954 section 6: 535 (credentials invalid), 534 (mechanism too weak), 538 (encryption required for the mechanism) and 432
    // (a password transition is needed) each refuse this credential through this mechanism; 454 is a temporary failure of the server.
    private def refusesCredential(code: Int): Boolean =
        code == 535 || code == 534 || code == 538 || code == 432

    private def unavailable(wanted: Chunk[Email.Auth.Mechanism], offered: Chunk[String])(using Frame): Unit < Abort[SessionFailure] =
        Abort.fail(EmailAuthMechanismUnavailableException(method, EmailException.Protocol.Smtp, host, wanted, offered))

    // The envelope and the data of one message. Every recipient is tried even after a refusal, so the caller learns every refusal at once,
    // and `DATA` is sent only when all were accepted, so the message reaches all of its recipients or none.
    private def transfer(envelope: Envelope, offered: Extensions)(using Frame): Unit < (Async & Abort[EmailSendFailure]) =
        val size  = envelope.data.size.toLong
        val limit = offered.parameters("SIZE").flatMap(_.headMaybe).flatMap(p => Maybe.fromOption(p.toLongOption)).filter(_ > 0)
        def tooLarge(reply: Reply, full: Boolean) =
            val enhanced = described(reply)._1
            full && reply.code == 552 || enhanced.exists(c => c.statusClass.permanent && c.subject == 3 && c.detail == 4)
        def large  = EmailMessageTooLargeException(method, size.bytes, limit.map(_.bytes))
        val wanted =
            (if envelope.utf8 then Chunk(offered.offers("SMTPUTF8") -> EmailCapabilityMissingException.Capability.SmtpUtf8)
             else Chunk.empty) ++
                (if envelope.eightBit then Chunk(offered.offers("8BITMIME") -> EmailCapabilityMissingException.Capability.Smtp8BitMime)
                 else Chunk.empty)
        Maybe.fromOption(wanted.find(!_._1)) match
            case Present((_, capability))         => Abort.fail(EmailCapabilityMissingException(method, host, capability))
            case Absent if limit.exists(size > _) => Abort.fail(large)
            case Absent                           =>
                val parameters =
                    (if offered.offers("SIZE") then s" SIZE=$size" else "") +
                        (if envelope.eightBit then " BODY=8BITMIME" else "") +
                        (if envelope.utf8 then " SMTPUTF8" else "")
                command(s"MAIL FROM:<${envelope.sender}>$parameters").map { mail =>
                    if mail.code != 250 then
                        Abort.fail(refusedOr(mail)(if tooLarge(mail, full = true) then large else rejected("MAIL", mail)))
                    else
                        Kyo.foreach(envelope.recipients) { recipient =>
                            command(s"RCPT TO:<${recipient.address}>").map { reply =>
                                if reply.code == 250 || reply.code == 251 then Maybe.empty[EmailRecipientRefusedException.Refusal]
                                else if reply.code < 400 then Abort.fail(unexpected(reply))
                                else
                                    val (enhanced, text) = described(reply)
                                    Present(EmailRecipientRefusedException.Refusal(recipient, reply.code, enhanced, text))
                            }
                        }.map(_.flatMap(_.toChunk)).map { refusals =>
                            if refusals.nonEmpty then
                                Abort.run[EmailTransportException](command("RSET")).andThen(
                                    Abort.fail(EmailRecipientRefusedException(method, refusals))
                                )
                            else
                                command("DATA").map { data =>
                                    if data.code != 354 then
                                        Abort.fail(refusedOr(data)(if tooLarge(data, full = false) then large else rejected("DATA", data)))
                                    else
                                        replied(connection.write(method, dataSection(envelope.data)).andThen(read)).map { accepted =>
                                            if accepted.code == 250 then ()
                                            else
                                                Abort.fail(refusedOr(accepted)(
                                                    if tooLarge(accepted, full = true) then large else rejected("DATA", accepted)
                                                ))
                                        }
                                }
                        }
                }
        end match
    end transfer

    // Each line in order, stopping at the first failure reply.
    private def lines(commands: Chunk[EmailSmtp.Command])(using Frame): Chunk[EmailSmtp.Reply] < (Async & Abort[EmailSmtpCustomFailure]) =
        Kyo.foreach(commands) { sent =>
            command(sent.value).map { reply =>
                if reply.code >= 400 then Abort.fail(rejected(Ascii.toUpper(sent.value.takeWhile(_ != ' ')), reply))
                else
                    EmailSmtp.Reply(
                        reply.code,
                        described(reply)._1,
                        reply.lines.map(line => redactor.redact(withoutCode(reply.code, line)))
                    )
            }
        }

    // RFC 5321 section 4.1.1.10: the client MUST NOT close the channel before QUIT, even after an error reply. It is sent whenever the
    // connection is in a known state; the verb's outcome is decided by then, so a failed QUIT changes nothing.
    private def quit(using Frame): Unit < Async =
        Abort.run[EmailTransportException](command("QUIT")).unit

    // A reply other than the one expected: a refusal when its code is 400 or more (RFC 5321 section 4.2.1), otherwise a reply the protocol
    // does not define at this point of the exchange.
    private def refusedOr[F](reply: Reply)(refusal: => F)(using Frame): F | EmailTransportException =
        if reply.code >= 400 then refusal else unexpected(reply)

    // The reply's enhanced code, read from its first line, and its lines without their enhanced codes, redacted and joined.
    private def described(reply: Reply)(using Frame): (Maybe[EmailSmtp.EnhancedStatusCode], String) =
        val code = reply.lines.headMaybe.flatMap(enhanced(reply.code, _)).map(_._1)
        (code, redactor.redact(reply.lines.map(withoutCode(reply.code, _)).mkString(" ")))

    private def withoutCode(code: Int, line: String)(using Frame): String =
        enhanced(code, line).fold(line)(_._2)

    private def rejected(command: String, reply: Reply)(using Frame): EmailSmtpRejectedException =
        val (enhanced, text) = described(reply)
        EmailSmtpRejectedException(method, command, reply.code, enhanced, text)

    private def closed(reply: Reply)(using Frame): EmailTransportException =
        transport(Kind.ConnectionClosed(Present(described(reply)._2)))

    private def unexpected(reply: Reply)(using Frame): EmailTransportException =
        transport(Kind.Protocol(redactor.redact(s"${reply.code} ${reply.lines.mkString(" ")}", LineConnection.ShownOctets)))

    private def redacted(ex: EmailTransportException)(using Frame): EmailTransportException =
        ex.kind match
            case Kind.Protocol(received) => ex.copy(kind = Kind.Protocol(redactor.redact(received, LineConnection.ShownOctets)))
            case Kind.ConnectionClosed(Present(text)) => ex.copy(kind = Kind.ConnectionClosed(Present(redactor.redact(text))))
            case _                                    => ex

    private def transport(kind: Kind, timeout: Maybe[Duration] = Absent)(using Frame): EmailTransportException =
        EmailTransportException(method, kind, host, port, timeout)

    private def startTlsUnavailable(using Frame): EmailConnectException =
        EmailConnectException(method, EmailConnectException.Kind.StartTlsUnavailable, host, port, Absent)(Absent)

end SmtpSession

private[kyo] object SmtpSession:

    /** What every submission can fail with, whichever verb runs it. */
    type SessionFailure = EmailConnectException | EmailTransportException | EmailSmtpRejectedException | EmailAuthenticationException |
        EmailAuthMechanismUnavailableException

    /** The random part of a generated message id, whose left part is the send's millisecond, a `.`, then this many alphanumerics from
      * `Random`. The millisecond keeps ids of different moments apart. Within one millisecond the random part does, as far as the generator
      * does: `Random`'s default is `java.util.Random`, 48 bits of state, so two processes whose generators were seeded alike make the same
      * ids, and the id is unique in practice, not by construction.
      */
    inline val IdLength = 16

    /** Where a generated message id's right part comes from when the sender's domain cannot be one: RFC 2606 reserves `.invalid`, so the
      * id names no real domain.
      */
    val FallbackIdDomain = "kyo-email.invalid"

    // The message as the envelope carries it: the reverse path, each distinct recipient, the rendered message, and what it needs of the
    // server. `utf8` is an address or a header past ASCII (RFC 6531); `eightBit` is any octet of the message past ASCII (RFC 6152).
    final private case class Envelope(
        sender: String,
        recipients: Chunk[Email.Address],
        data: Span[Byte],
        utf8: Boolean,
        eightBit: Boolean
    )

    private enum Credential:
        case Password(user: String, password: Email.Password)
        case Token(user: String, token: Email.OAuthToken)

    /** Submits `message`, answering the id it was sent with. Everything that can fail without the server fails before the connection
      * opens, and before the token computation runs: a missing `From`, `Sender` or recipient, an envelope address that is not an
      * `addr-spec` (`Bcc` is never rendered, so the renderer does not check it), and a message the renderer refuses.
      */
    def send[E](method: String, config: EmailSmtpConfig[E], message: Email.Message)(using
        Frame
    ): Email.MessageId < (Async & Abort[EmailSendFailure | E]) =
        prepared(method, message).map { (id, envelope) =>
            retried(config)(submit(method, config)((session, offered) => session.transfer(envelope, offered))).andThen(id)
        }

    // Each attempt is a whole submission on a connection of its own, its credential computed again; a failure of that computation is
    // the caller's `E` and is never retried. The schedule's next delay is waited on kyo's clock, and one above retryMaxDelay ends it.
    private def retried[E, A](config: EmailSmtpConfig[E])(attempt: => A < (Async & Abort[EmailSendFailure | E]))(using
        Frame
    ): A < (Async & Abort[EmailSendFailure | E]) =
        config.retry match
            case Absent            => attempt
            case Present(schedule) =>
                Loop(schedule) { remaining =>
                    Abort.run[EmailSendFailure](attempt).map {
                        case Result.Success(value)                         => Loop.done(value)
                        case Result.Failure(failure) if transient(failure) =>
                            Clock.now.map { now =>
                                remaining.next(now) match
                                    case Present((delay, rest)) if delay <= config.retryMaxDelay =>
                                        Async.sleep(delay).andThen(Loop.continue(rest))
                                    case _ => Abort.fail(failure)
                            }
                        case Result.Failure(failure) => Abort.fail(failure)
                        case Result.Panic(ex)        => Abort.panic(ex)
                    }
                }

    // RFC 5321 section 4.2.1: a 4yz reply means "the error condition is temporary, and the action may be requested again", and the
    // requested action did not occur, so a retry cannot deliver twice. A 421 closing the channel is the ConnectionClosed that carries
    // server text; a connection lost with no reply leaves the outcome unknown and is not retried.
    private def transient(failure: EmailSendFailure): Boolean =
        failure match
            case ex: EmailSmtpRejectedException     => !ex.permanent
            case ex: EmailRecipientRefusedException => !ex.refusals.exists(_.permanent)
            case ex: EmailTransportException        =>
                ex.kind match
                    case Kind.ConnectionClosed(Present(_)) => true
                    case _                                 => false
            case _ => false

    /** Runs each of `commands` after authenticating, answering each reply. */
    def custom[E](method: String, config: EmailSmtpConfig[E], commands: Chunk[EmailSmtp.Command])(using
        Frame
    ): Chunk[EmailSmtp.Reply] < (Async & Abort[EmailSmtpCustomFailure | E]) =
        submit(method, config)((session, _) => session.lines(commands))

    private def prepared(method: String, message: Email.Message)(using
        Frame
    ): (Email.MessageId, Envelope) < (Async & Abort[EmailSendFailure]) =
        val recipients = message.to.concat(message.cc).concat(message.bcc).distinctBy(_.address)
        val sender     = message.sender.orElse(message.from.headMaybe)
        import EmailIncompleteMessageException.Missing
        if message.from.isEmpty then Abort.fail(EmailIncompleteMessageException(method, Missing.From))
        else if message.from.size > 1 && message.sender.isEmpty then Abort.fail(EmailIncompleteMessageException(method, Missing.Sender))
        else if recipients.isEmpty then Abort.fail(EmailIncompleteMessageException(method, Missing.Recipients))
        else
            val path = sender.fold("")(_.address)
            Maybe.fromOption((path +: recipients.map(_.address)).find(!AddressCodec.isAddrSpec(_))) match
                case Present(invalid) => Abort.fail(EmailInvalidAddressException(invalid))
                case Absent           =>
                    for
                        now  <- Clock.now
                        id   <- message.messageId.fold(generated(path, now))(id => id: Email.MessageId < Sync)
                        data <- rendered(message.copy(bcc = Chunk.empty, messageId = Present(id), date = message.date.orElse(Present(now))))
                    yield
                        val beyond = (octets: Span[Byte]) => octets.exists(_ < 0)
                        val utf8 = beyond(data.slice(0, headerEnd(data))) || (path +: recipients.map(_.address)).exists(_.exists(_ > 0x7f))
                        // RFC 6152 section 2: BODY declares the whole content DATA passes, so an 8-bit header makes the message 8-bit.
                        (id, Envelope(path, recipients, data, utf8, eightBit = beyond(data)))
            end match
        end if
    end prepared

    private def generated(sender: String, now: Instant)(using Frame): Email.MessageId < Sync =
        Random.nextStringAlphanumeric(IdLength).map { random =>
            val left = s"${now.toDuration.toMillis}.$random"
            Email.MessageId.generated(left, sender.substring(sender.lastIndexOf('@') + 1), FallbackIdDomain)
        }

    private def rendered(message: Email.Message)(using Frame): Span[Byte] < Abort[EmailSendFailure] =
        Abort.run[EmailRenderFailure](Email.Message.render(message)).map {
            case Result.Success(data)                             => data
            case Result.Failure(ex: EmailInvalidAddressException) => Abort.fail(ex)
            case Result.Failure(ex: EmailInvalidHeaderException)  => Abort.fail(ex)
            case Result.Panic(ex)                                 => Abort.panic(ex)
        }

    // Where the header section ends: at its blank line, or at the end of a message that has no body.
    private def headerEnd(data: Span[Byte]): Int =
        @tailrec def from(i: Int): Int =
            if i + 3 >= data.size then data.size
            else if data(i) == '\r' && data(i + 1) == '\n' && data(i + 2) == '\r' && data(i + 3) == '\n' then i + 2
            else from(i + 1)
        from(0)
    end headerEnd

    // One connection, from opening it to closing it. The credential is computed first, outside every deadline and recovery, so the token's
    // `E` reaches the caller as raised, and the redactor is built from it before the first byte.
    private def submit[E, A, F >: SessionFailure <: EmailException](method: String, config: EmailSmtpConfig[E])(
        use: (SmtpSession, Extensions) => A < (Async & Abort[F])
    )(using Frame, ConcreteTag[F]): A < (Async & Abort[F | E]) =
        credential(config.auth).map { credential =>
            val implicitTls = config.tls match
                case _: Email.Tls.Implicit => Present(TlsOptions.netConfig(config.tls))
                case _: Email.Tls.StartTls => Absent
            Scope.run {
                Scope.acquireRelease(LineConnection.open(method, config.host, config.resolvedPort, implicitTls, config.connectTimeout))(
                    _.close
                ).map { connection =>
                    val redactor = credential match
                        case Credential.Password(user, password) => Redactor.password(user, password)
                        case Credential.Token(user, token)       => Redactor.token(user, token)
                    val session = new SmtpSession(method, config.host, config.resolvedPort, connection, redactor, config.commandTimeout)
                    Abort.run[F](session.handshake(config, credential).map(use(session, _))).map {
                        case Result.Success(result)  => session.quit.andThen(result)
                        case Result.Failure(failure) => (if known(failure) then session.quit else Kyo.unit).andThen(Abort.fail(failure))
                        case Result.Panic(ex)        => Abort.panic(ex)
                    }
                }
            }
        }

    // Whether the connection is in a known state after `failure`, so QUIT can follow it: a transport failure closed the connection, and a
    // TLS upgrade that failed partway leaves no channel to write to; every other failure is a reply the server completed, or a check made
    // between replies.
    private def known(failure: EmailException): Boolean =
        failure match
            case _: EmailTransportException => false
            case ex: EmailConnectException  => ex.kind == EmailConnectException.Kind.StartTlsUnavailable
            case _                          => true

    private def credential[E](auth: Email.Auth[E])(using Frame): Credential < (Async & Abort[E]) =
        auth match
            case Email.Auth.Password(user, password) => Credential.Password(user, password)
            case oauth: Email.Auth.OAuth2[E]         => oauth.token.map(Credential.Token(oauth.user, _))

end SmtpSession
