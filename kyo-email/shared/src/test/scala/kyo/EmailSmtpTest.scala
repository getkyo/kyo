package kyo

import kyo.EmailConnectException.Kind as ConnectKind
import kyo.EmailSmtp.EnhancedStatusCode.StatusClass
import kyo.EmailTransportException.Kind
import kyo.internal.email.net.LineConnectionFixture
import kyo.internal.email.net.LineConnectionFixture.Certificate
import kyo.internal.email.net.LineConnectionFixture.scripted
import kyo.internal.email.net.Sasl
import kyo.internal.email.smtp.SmtpTestServer
import kyo.internal.email.smtp.SmtpTestServer.*

class EmailSmtpTest extends kyo.test.Test[Any]:

    // Built away from the assertions, so a failing assertion's source snippet never carries a secret.
    private val secret    = "hunter2 \"quoted\""
    private val password  = EmailLiterals.passwordOf(secret)
    private val byPass    = EmailLiterals.passwordAuthOf(User, password)
    private val tokenText = "ya29.token-value"
    private val token     = EmailLiterals.tokenOf(tokenText)
    private val plain     = Sasl.plain(User, password)
    private val xoauth2   = Sasl.xoauth2(User, token)

    private val alice = Email.Address("alice@example.com")
    private val bob   = Email.Address("bob@example.org")
    private val carol = Email.Address("carol@example.net")
    private val dave  = Email.Address("dave@example.net")

    private val fixedId   = EmailLiterals.messageIdOf("fixed@example.com")
    private val fixedDate = EmailLiterals.instantOf("2026-09-27T12:00:00Z")

    private val lunch = Email.Message(
        from = Chunk(alice),
        to = Chunk(bob),
        subject = "Lunch",
        text = "See you at noon.",
        messageId = Present(fixedId),
        date = Present(fixedDate)
    )

    private def code(statusClass: StatusClass, subject: Int, detail: Int): Maybe[EmailSmtp.EnhancedStatusCode] =
        Present(EmailLiterals.statusCodeOf(statusClass, subject, detail))

    private def failure[E, A](v: A < (Async & Abort[E]))(using Frame, ConcreteTag[E]): E < Async =
        Abort.run[E](v).map {
            case Result.Failure(ex) => ex
            case other              => throw new IllegalStateException(s"expected a failure: $other")
        }

    // The message as the renderer writes it, one entry per line.
    private def lines(message: Email.Message)(using Frame): Chunk[String] < Async =
        Abort.run[EmailRenderFailure](Email.Message.render(message)).map {
            case Result.Success(octets) =>
                val split = Chunk.from(new String(octets.toArray, "UTF-8").split("\r\n", -1))
                if split.lastOption.contains("") then split.dropRight(1) else split
            case other => throw new IllegalStateException(s"did not render: $other")
        }

    private def size(message: Email.Message)(using Frame): Int < Async =
        Abort.run[EmailRenderFailure](Email.Message.render(message)).map {
            case Result.Success(octets) => octets.size
            case other                  => throw new IllegalStateException(s"did not render: $other")
        }

    // The message a transcript's data section carries, its dot-stuffing undone.
    private def delivered(rx: Chunk[String])(using Frame): Email.Message < Async =
        val section =
            rx.dropWhile(_ != "DATA").drop(1).takeWhile(_ != ".").map(line => if line.startsWith("..") then line.drop(1) else line)
        Abort.run[EmailParseFailure](Email.Message.parse(Span.from((section.mkString("\r\n") + "\r\n").getBytes("UTF-8")))).map {
            case Result.Success(message) => message
            case other                   => throw new IllegalStateException(s"did not parse: $other")
        }
    end delivered

    // Sends `message` with the password and answers the id with every line the server received.
    private def sent(server: Server, message: Email.Message, startTls: Boolean = false)(using
        Frame
    ): (Email.MessageId, Chunk[String]) < (Async & Abort[EmailSendFailure]) =
        SmtpTestServer.config(server, byPass, startTls).map(config => EmailSmtp.let(config)(EmailSmtp.send(message)))
            .map(id => server.received.map((id, _)))

    // The failure `send` raises through `server`, with every line the server received.
    private def refused(server: Server, message: Email.Message = lunch, startTls: Boolean = false)(using
        Frame
    ): (EmailSendFailure, Chunk[String]) < Async =
        SmtpTestServer.config(server, byPass, startTls).map { config =>
            failure(EmailSmtp.let(config)(EmailSmtp.send(message)))
        }.map(ex => server.received.map((ex, _)))

    // A server that greets, answers EHLO with `extensions` and accepts AUTH, then runs `rest`.
    private def after(extensions: Seq[String] = Extensions)(rest: Peer => Any < (Async & Abort[EmailTransportException]))(using
        Frame
    ): Server < (Async & Scope & Abort[kyo.net.NetException]) =
        serve()(peer => peer.ready(extensions).andThen(rest(peer)))

    "submitting" - {
        "PLAIN over implicit TLS: EHLO, AUTH, MAIL with SIZE, RCPT, DATA, the message and QUIT (RFC 5321, RFC 4954, RFC 1870)" in scripted {
            Scope.run {
                for
                    server   <- serve()(peer => peer.ready().andThen(peer.untilClosed))
                    (id, rx) <- sent(server, lunch)
                    data     <- lines(lunch)
                    octets   <- size(lunch)
                yield
                    assert(id == fixedId)
                    assert(rx == Chunk(
                        "EHLO [127.0.0.1]",
                        s"AUTH PLAIN $plain",
                        s"MAIL FROM:<alice@example.com> SIZE=$octets",
                        "RCPT TO:<bob@example.org>",
                        "DATA"
                    ) ++ data ++ Chunk(".", "QUIT"))
            }
        }
        "LOGIN when PLAIN is not offered: the user and the password each after a 334" in scripted {
            Scope.run {
                for
                    server <- serve() { peer =>
                        peer.send("220 smtp.example.com ESMTP").andThen(peer.ehlo(Seq("AUTH LOGIN")))
                            .andThen(peer.reply("334 VXNlcm5hbWU6")).andThen(peer.reply("334 UGFzc3dvcmQ6"))
                            .andThen(peer.reply("235 2.7.0 ok")).andThen(peer.untilClosed)
                    }
                    (_, rx) <- sent(server, lunch)
                yield assert(rx.slice(1, 4) == Chunk("AUTH LOGIN", Sasl.base64(User), Sasl.base64(secret)))
            }
        }
        "XOAUTH2 with the token the computation yields, which runs once per submission" in scripted {
            Scope.run {
                for
                    server <- serve()(peer => peer.ready().andThen(peer.untilClosed))
                    runs   <- AtomicInt.init
                    config <- SmtpTestServer.config(server, EmailLiterals.oauth2Of(User, runs.incrementAndGet.andThen(token)))
                    _      <- EmailSmtp.let(config)(EmailSmtp.send(lunch).andThen(EmailSmtp.send(lunch)))
                    count  <- runs.get
                    rx     <- server.received
                yield
                    assert(count == 2)
                    assert(rx.count(_ == s"AUTH XOAUTH2 $xoauth2") == 2)
            }
        }
        "STARTTLS: the upgrade, then EHLO again, whose extensions replace the first's (RFC 3207 section 4.2)" in scripted {
            Scope.run {
                for
                    server <- serve(tls = false) { peer =>
                        peer.send("220 smtp.example.com ESMTP").andThen(peer.ehlo(Seq("STARTTLS"))).andThen(peer.reply("220 2.0.0 ready"))
                            .andThen(peer.startTls()).andThen(peer.ehlo()).andThen(peer.reply("235 2.7.0 ok")).andThen(peer.untilClosed)
                    }
                    (_, rx) <- sent(server, lunch, startTls = true)
                yield assert(rx.take(4) == Chunk("EHLO [127.0.0.1]", "STARTTLS", "EHLO [127.0.0.1]", s"AUTH PLAIN $plain"))
            }
        }
        "STARTTLS with a client certificate the server requires after the upgrade: the message is sent" in scripted {
            Scope.run {
                for
                    server <- serve(tls = false) { peer =>
                        peer.send("220 smtp.example.com ESMTP").andThen(peer.ehlo(Seq("STARTTLS"))).andThen(peer.reply("220 2.0.0 ready"))
                            .andThen(peer.startTls(adjust = LineConnectionFixture.requiringClientCertificate))
                            .andThen(peer.ehlo()).andThen(peer.reply("235 2.7.0 ok")).andThen(peer.untilClosed)
                    }
                    certificate <- LineConnectionFixture.clientCertificate
                    config      <- SmtpTestServer.config(server, byPass, startTls = true)
                    tls = EmailLiterals.startTlsOf(config.tls.trust, clientCertificate = Present(certificate))
                    id <- EmailSmtp.let(EmailLiterals.valid(config.tls(tls)))(EmailSmtp.send(lunch))
                    rx <- server.received
                yield
                    assert(rx.take(4) == Chunk("EHLO [127.0.0.1]", "STARTTLS", "EHLO [127.0.0.1]", s"AUTH PLAIN $plain"))
                    assert(rx.contains("DATA") && rx.contains(s"Message-ID: <${id.value}>"))
            }
        }
        "a line beginning with a dot is sent with another dot before it (RFC 5321 section 4.5.2)" in scripted {
            Scope.run {
                for
                    server  <- serve()(peer => peer.ready().andThen(peer.untilClosed))
                    (_, rx) <- sent(server, lunch.copy(text = ".hidden\n..two"))
                yield
                    assert(rx.contains("..hidden"))
                    assert(rx.contains("...two"))
                    assert(!rx.contains(".hidden"))
            }
        }
        "every recipient of To, Cc and Bcc is named once, and Bcc is never written into the message" in scripted {
            Scope.run {
                for
                    server  <- serve()(peer => peer.ready().andThen(peer.untilClosed))
                    (_, rx) <- sent(server, lunch.copy(cc = Chunk(carol, bob), bcc = Chunk(dave)))
                yield
                    assert(rx.filter(_.startsWith("RCPT")) == Chunk(
                        "RCPT TO:<bob@example.org>",
                        "RCPT TO:<carol@example.net>",
                        "RCPT TO:<dave@example.net>"
                    ))
                    assert(!rx.exists(line => line.startsWith("Bcc") || (!line.startsWith("RCPT") && line.contains("dave"))))
            }
        }
        "an absent id and date are filled from one reading of the clock: the id is its millisecond, a random part and the sender's domain" in
            scripted {
                Scope.run {
                    for
                        server   <- serve()(peer => peer.ready().andThen(peer.untilClosed))
                        _        <- Clock.withTimeControl(_.set(Instant.Epoch + 1234567.millis, Duration.Zero))
                        (id, rx) <- sent(server, lunch.copy(messageId = Absent, date = Absent))
                        message  <- delivered(rx)
                    yield
                        assert(id.value.matches("1234567\\.[A-Za-z0-9]{16}@example\\.com"))
                        assert(message.messageId == Present(id))
                        assert(message.date == Present(Instant.Epoch + 1234.seconds))
                }
            }
        "the reverse path is Sender when From holds several addresses" in scripted {
            Scope.run {
                for
                    server  <- serve()(peer => peer.ready().andThen(peer.untilClosed))
                    (_, rx) <- sent(server, lunch.copy(from = Chunk(alice, bob), sender = Present(carol)))
                yield assert(rx.exists(_.startsWith("MAIL FROM:<carol@example.net>")))
            }
        }
        "an address past ASCII is sent with SMTPUTF8, and content past ASCII with BODY=8BITMIME (RFC 6531, RFC 6152)" in scripted {
            val forwarded =
                Email.Attachment(
                    EmailLiterals.mediaTypeOf("message", "rfc822"),
                    Absent,
                    Span.from("Subject: café\r\n\r\nbon\r\n".getBytes("UTF-8"))
                )
            Scope.run {
                for
                    server  <- serve()(peer => peer.ready().andThen(peer.untilClosed))
                    (_, rx) <- sent(server, lunch.copy(to = Chunk(Email.Address("böb@example.org")), attachments = Chunk(forwarded)))
                yield
                    assert(rx.exists(line =>
                        line.startsWith("MAIL FROM:<alice@example.com> SIZE=") && line.endsWith(" BODY=8BITMIME SMTPUTF8")
                    ))
                    assert(rx.contains("RCPT TO:<böb@example.org>"))
            }
        }
        "once the server accepts the data the message is sent, whatever becomes of QUIT" in scripted {
            Scope.run {
                for
                    server <- after() { peer =>
                        peer.reply("250 ok").andThen(peer.reply("250 ok")).andThen(peer.reply("354 go")).andThen(peer.data)
                            .andThen(peer.send("250 queued")).andThen(peer.receive).andThen(peer.line.close)
                    }
                    (id, rx) <- sent(server, lunch)
                yield
                    assert(id == fixedId)
                    assert(rx.last == "QUIT")
            }
        }
        "reply threads the message: In-Reply-To, References, Re: and the original's sender as recipient" in scripted {
            Scope.run {
                for
                    server <- serve()(peer => peer.ready().andThen(peer.untilClosed))
                    config <- SmtpTestServer.config(server, byPass)
                    _      <- EmailSmtp.let(config)(EmailSmtp.reply(lunch, Email.Message(from = Chunk(bob), text = "Yes.")))
                    rx     <- server.received
                yield
                    assert(rx.contains("RCPT TO:<alice@example.com>"))
                    assert(rx.contains("In-Reply-To: <fixed@example.com>"))
                    assert(rx.contains("References: <fixed@example.com>"))
                    assert(rx.contains("Subject: Re: Lunch"))
            }
        }
    }

    "retry" - {
        // The password config for `server` retrying on `schedule`. No connect or command deadline, so the retry's wait is the one sleep
        // armed.
        def retrying(server: Server, schedule: Schedule, maxDelay: Duration = 60.seconds)(using Frame): EmailSmtpConfig[Nothing] < Sync =
            SmtpTestServer.config(server, byPass).map { config =>
                val changed: EmailSmtpConfig[Nothing] = EmailLiterals.valid(
                    config.retry(Present(schedule)).connectTimeout(Duration.Infinity).flatMap(_.commandTimeout(Duration.Infinity))
                        .flatMap(_.retryMaxDelay(maxDelay))
                )
                changed
            }
        def mailTryLater(peer: Peer)(using Frame) = peer.ready().andThen(peer.reply("451 4.3.0 try later")).andThen(peer.quit)
        def tryLater(using Frame)                 =
            EmailSmtpRejectedException("send", "MAIL", 451, code(StatusClass.PersistentTransient, 3, 0), "try later")
        // `first` answers the first connection; every later one accepts the message.
        def thenAccepting(first: Peer => Any < (Async & Abort[EmailTransportException]))(using Frame) =
            serve()(peer => if peer.index == 1 then first(peer) else peer.ready().andThen(peer.untilClosed))

        "with no schedule, a 4xx reply fails the send on its one connection" in scripted {
            Scope.run {
                for
                    server      <- serve()(mailTryLater)
                    (ex, _)     <- refused(server)
                    connections <- server.connections
                yield assert(ex == tryLater && connections == 1)
            }
        }
        "a 4xx reply is retried on a new connection exactly the schedule's delay later on kyo's clock" in scripted {
            Scope.run {
                for
                    server   <- thenAccepting(mailTryLater)
                    base     <- retrying(server, Schedule.fixed(10.seconds), maxDelay = 10.seconds)
                    attempts <- AtomicRef.init(Chunk.empty[Instant])
                    // The token computation runs once per attempt, on the client's clock, so it records when each attempt started.
                    config =
                        base.auth(EmailLiterals.oauth2Of(User, Clock.now.map(now => attempts.updateAndGet(_.append(now))).andThen(token)))
                    outcome <- Clock.withTimeControl { control =>
                        for
                            fiber  <- Fiber.initUnscoped(Abort.run[EmailSendFailure](EmailSmtp.let(config)(EmailSmtp.send(lunch))))
                            _      <- control.awaitPendingSleepers(1)
                            _      <- control.advance(10.seconds.minusOrZero(1.nanos))
                            _      <- control.advance(1.nanos)
                            result <- fiber.get
                        yield result
                    }
                    times       <- attempts.get
                    connections <- server.connections
                yield
                    assert(outcome == Result.succeed(fixedId))
                    assert(times.size == 2 && times(1).minus(times(0)) == Present(10.seconds))
                    assert(connections == 2)
            }
        }
        "every transient reply is retried: 4xx to MAIL, 4xx to every RCPT, 4xx after the data, and 421 (RFC 5321 section 4.2.1)" in
            scripted {
                val transient = Seq[(String, Peer => Any < (Async & Abort[EmailTransportException]))](
                    "451 to MAIL"       -> (peer => mailTryLater(peer)),
                    "450 to every RCPT" ->
                        (peer =>
                            peer.ready().andThen(peer.reply("250 2.1.0 ok")).andThen(peer.reply("450 4.7.1 greylisted"))
                                .andThen(peer.reply("250 2.0.0 reset")).andThen(peer.quit)
                        ),
                    "451 after the data" ->
                        (peer =>
                            peer.ready().andThen(
                                peer.reply("250 2.1.0 ok")
                            ).andThen(peer.reply("250 2.1.5 ok")).andThen(peer.reply("354 go ahead"))
                                .andThen(peer.data).andThen(peer.send("451 4.3.0 queue full")).andThen(peer.quit)
                        ),
                    "421 in place of the greeting" -> (peer => peer.send("421 4.3.2 shutting down"))
                )
                Kyo.foreachDiscard(transient) { (name, first) =>
                    Scope.run {
                        for
                            server  <- thenAccepting(first)
                            config  <- retrying(server, Schedule.fixed(1.second))
                            outcome <- Clock.withTimeControl { control =>
                                for
                                    fiber  <- Fiber.initUnscoped(Abort.run[EmailSendFailure](EmailSmtp.let(config)(EmailSmtp.send(lunch))))
                                    _      <- control.awaitPendingSleepers(1)
                                    _      <- control.advance(1.second)
                                    result <- fiber.get
                                yield result
                            }
                            connections <- server.connections
                        yield assert(outcome == Result.succeed(fixedId) && connections == 2, name)
                    }
                }.andThen(succeed)
            }
        "a permanent reply, a 5xx among the recipient refusals, an AUTH refusal or a connection lost after the data is not retried" in
            scripted {
                val withCarol = lunch.copy(to = Chunk(bob, carol))
                val final_    =
                    Seq[(String, Email.Message, Peer => Any < (Async & Abort[EmailTransportException]), EmailSendFailure => Boolean)](
                        (
                            "550 to MAIL",
                            lunch,
                            peer => peer.ready().andThen(peer.reply("550 5.7.1 denied")).andThen(peer.quit),
                            _ == EmailSmtpRejectedException("send", "MAIL", 550, code(StatusClass.Permanent, 7, 1), "denied")
                        ),
                        (
                            "450 and 550 to the recipients",
                            withCarol,
                            peer =>
                                peer.ready().andThen(peer.reply("250 2.1.0 ok")).andThen(peer.reply("450 4.7.1 greylisted"))
                                    .andThen(peer.reply("550 5.1.1 unknown")).andThen(peer.reply("250 2.0.0 reset")).andThen(peer.quit),
                            {
                                case ex: EmailRecipientRefusedException => ex.refusals.map(r => (r.recipient, r.code)) ==
                                        Chunk(bob -> 450, carol -> 550)
                                case _ => false
                            }
                        ),
                        (
                            "432 to AUTH",
                            lunch,
                            peer =>
                                peer.send(
                                    "220 smtp.example.com ESMTP"
                                ).andThen(peer.ehlo()).andThen(peer.reply("432 4.7.12 transition needed"))
                                    .andThen(peer.quit),
                            _.isInstanceOf[EmailAuthenticationException]
                        ),
                        (
                            "a close with no reply after the data",
                            lunch,
                            peer =>
                                peer.ready().andThen(
                                    peer.reply("250 2.1.0 ok")
                                ).andThen(peer.reply("250 2.1.5 ok")).andThen(peer.reply("354 go ahead"))
                                    .andThen(peer.data).andThen(peer.line.close),
                            {
                                case ex: EmailTransportException => ex.kind == Kind.ConnectionClosed(Absent)
                                case _                           => false
                            }
                        )
                    )
                Kyo.foreachDiscard(final_) { (name, message, script, expected) =>
                    Scope.run {
                        for
                            server      <- serve()(script)
                            config      <- retrying(server, Schedule.repeat(1))
                            ex          <- failure(EmailSmtp.let(config)(EmailSmtp.send(message)))
                            connections <- server.connections
                        yield
                            assert(expected(ex), s"$name: $ex")
                            assert(connections == 1, name)
                    }
                }.andThen(succeed)
            }
        "when the schedule ends, the send fails with the last transient failure" in scripted {
            Scope.run {
                for
                    server      <- serve()(mailTryLater)
                    config      <- retrying(server, Schedule.repeat(2))
                    ex          <- failure(EmailSmtp.let(config)(EmailSmtp.send(lunch)))
                    connections <- server.connections
                yield assert(ex == tryLater && connections == 3)
            }
        }
        "a delay longer than retryMaxDelay fails the send at once, with no wait" in scripted {
            Scope.run {
                for
                    server      <- serve()(mailTryLater)
                    config      <- retrying(server, Schedule.fixed(10.seconds + 1.nanos), maxDelay = 10.seconds)
                    ex          <- Clock.withTimeControl(_ => failure(EmailSmtp.let(config)(EmailSmtp.send(lunch))))
                    connections <- server.connections
                yield assert(ex == tryLater && connections == 1)
            }
        }
        "a failed token computation is not retried, and no connection opens" in scripted {
            Scope.run {
                for
                    computed <- AtomicInt.init
                    server   <- serve()(peer => peer.ready().andThen(peer.untilClosed))
                    base     <- retrying(server, Schedule.repeat(1))
                    config = base.auth(EmailLiterals.oauth2Of[String](User, computed.incrementAndGet.andThen(Abort.fail("expired"))))
                    result       <- Abort.run[EmailSendFailure](Abort.run[String](EmailSmtp.let(config)(EmailSmtp.send(lunch))))
                    computations <- computed.get
                    connections  <- server.connections
                yield assert(result == Result.succeed(Result.fail("expired")) && computations == 1 && connections == 0)
            }
        }
        "custom is never retried" in scripted {
            Scope.run {
                for
                    server      <- after()(peer => peer.reply("451 4.3.0 try later").andThen(peer.quit))
                    config      <- retrying(server, Schedule.repeat(1))
                    ex          <- failure(EmailSmtp.let(config)(EmailSmtp.custom(Seq(EmailLiterals.smtpCommandOf("NOOP")))))
                    connections <- server.connections
                yield
                    assert(ex ==
                        EmailSmtpRejectedException("custom", "NOOP", 451, code(StatusClass.PersistentTransient, 3, 0), "try later"))
                    assert(connections == 1)
            }
        }
    }

    "custom" - {
        "each line after AUTH, each reply with its enhanced code and lines, then QUIT" in scripted {
            Scope.run {
                for
                    server <- after() { peer =>
                        peer.reply("250 2.0.0 OK").andThen(peer.reply(
                            "252-2.5.2 cannot verify",
                            "252 2.5.2 will try"
                        )).andThen(peer.untilClosed)
                    }
                    config  <- SmtpTestServer.config(server, byPass)
                    replies <- EmailSmtp.let(config)(EmailSmtp.custom(Seq(
                        EmailLiterals.smtpCommandOf("NOOP"),
                        EmailLiterals.smtpCommandOf("VRFY bob")
                    )))
                    rx <- server.received
                yield
                    assert(replies == Chunk(
                        EmailSmtp.Reply(250, code(StatusClass.Success, 0, 0), Chunk("OK")),
                        EmailSmtp.Reply(252, code(StatusClass.Success, 5, 2), Chunk("cannot verify", "will try"))
                    ))
                    assert(rx == Chunk("EHLO [127.0.0.1]", s"AUTH PLAIN $plain", "NOOP", "VRFY bob", "QUIT"))
            }
        }
        "a 4xx or 5xx reply fails naming the line's first word, and the lines after it are not sent" in scripted {
            Scope.run {
                for
                    server <-
                        after()(peer => peer.reply("250 2.0.0 OK").andThen(peer.reply("500 5.5.1 unrecognized")).andThen(peer.quit))
                    config <- SmtpTestServer.config(server, byPass)
                    ex     <- failure(EmailSmtp.let(config)(
                        EmailSmtp.custom(Seq(
                            EmailLiterals.smtpCommandOf("NOOP"),
                            EmailLiterals.smtpCommandOf("xtest a"),
                            EmailLiterals.smtpCommandOf("NOOP")
                        ))
                    ))
                    rx <- server.received
                yield
                    assert(ex == EmailSmtpRejectedException("custom", "XTEST", 500, code(StatusClass.Permanent, 5, 1), "unrecognized"))
                    assert(rx == Chunk("EHLO [127.0.0.1]", s"AUTH PLAIN $plain", "NOOP", "xtest a", "QUIT"))
            }
        }
        "a refused connection is Connect" in scripted {
            Scope.run(serve()(_ => ())).map { server =>
                SmtpTestServer.config(server, byPass).map { config =>
                    failure(EmailSmtp.let(config)(EmailSmtp.custom(Seq(EmailLiterals.smtpCommandOf("NOOP"))))).map {
                        case ex: EmailConnectException => assert(ex.kind == ConnectKind.Connect && ex.method == "custom")
                        case other                     => fail(s"expected Connect: $other")
                    }
                }
            }
        }
    }

    "failing before any connection" - {
        def untouched(message: Email.Message)(using Frame): (EmailSendFailure, Int, Int) < (Async & Scope & Abort[kyo.net.NetException]) =
            for
                server <- serve()(peer => peer.ready().andThen(peer.untilClosed))
                runs   <- AtomicInt.init
                config <- SmtpTestServer.config(server, EmailLiterals.oauth2Of(User, runs.incrementAndGet.andThen(token)))
                ex     <- failure(EmailSmtp.let(config)(EmailSmtp.send(message)))
                count  <- runs.get
                opened <- server.connections
            yield (ex, count, opened)
        "a message without From, with several From and no Sender, or without a recipient is incomplete" in scripted {
            import EmailIncompleteMessageException.Missing
            Scope.run {
                for
                    (noFrom, _, _)         <- untouched(lunch.copy(from = Chunk.empty))
                    (noSender, _, _)       <- untouched(lunch.copy(from = Chunk(alice, carol)))
                    (noRecipient, runs, n) <- untouched(lunch.copy(to = Chunk.empty))
                yield
                    assert(noFrom == EmailIncompleteMessageException("send", Missing.From))
                    assert(noSender == EmailIncompleteMessageException("send", Missing.Sender))
                    assert(noRecipient == EmailIncompleteMessageException("send", Missing.Recipients))
                    assert(runs == 0 && n == 0)
            }
        }
        "reply names itself as the failing operation" in scripted {
            Scope.run {
                for
                    server <- serve()(peer => peer.ready().andThen(peer.untilClosed))
                    config <- SmtpTestServer.config(server, byPass)
                    ex     <- failure(EmailSmtp.let(config)(EmailSmtp.reply(lunch, Email.Message(text = "Yes."))))
                    opened <- server.connections
                yield
                    assert(ex == EmailIncompleteMessageException("reply", EmailIncompleteMessageException.Missing.From))
                    assert(opened == 0)
            }
        }
        "an envelope address that is not an addr-spec, a Bcc holding CRLF included, is InvalidAddress" in scripted {
            val injected = "dave@example.net>\r\nRCPT TO:<evil@example.net"
            Scope.run {
                for
                    (noAt, _, _)     <- untouched(lunch.copy(from = Chunk(Email.Address("alice"))))
                    (inBcc, runs, n) <- untouched(lunch.copy(bcc = Chunk(Email.Address(injected))))
                yield
                    assert(noAt == EmailInvalidAddressException("alice"))
                    assert(inBcc == EmailInvalidAddressException(injected))
                    assert(runs == 0 && n == 0)
            }
        }
        "a header the renderer refuses is its failure" in scripted {
            Scope.run {
                untouched(lunch.copy(headers = Chunk(Email.Header("Bad Name", "x")))).map { (ex, runs, n) =>
                    assert(ex == EmailInvalidHeaderException("Bad Name", EmailInvalidHeaderException.Problem.InvalidName))
                    assert(runs == 0 && n == 0)
                }
            }
        }
    }

    "server refusals" - {
        "an address past ASCII without SMTPUTF8, and content past ASCII without 8BITMIME, fail before MAIL" in scripted {
            val forwarded =
                Email.Attachment(
                    EmailLiterals.mediaTypeOf("message", "rfc822"),
                    Absent,
                    Span.from("Subject: é\r\n\r\né\r\n".getBytes("UTF-8"))
                )
            Scope.run {
                for
                    ascii       <- after(Seq("8BITMIME", "AUTH PLAIN"))(_.quit)
                    (utf8, r1)  <- refused(ascii, lunch.copy(to = Chunk(Email.Address("böb@example.org"))))
                    seven       <- after(Seq("SMTPUTF8", "AUTH PLAIN"))(_.quit)
                    (eight, r2) <- refused(seven, lunch.copy(attachments = Chunk(forwarded)))
                yield
                    assert(utf8 ==
                        EmailCapabilityMissingException("send", "127.0.0.1", EmailCapabilityMissingException.Capability.SmtpUtf8))
                    assert(eight ==
                        EmailCapabilityMissingException("send", "127.0.0.1", EmailCapabilityMissingException.Capability.Smtp8BitMime))
                    assert(!(r1 ++ r2).exists(_.startsWith("MAIL")))
                    assert(r1.last == "QUIT" && r2.last == "QUIT")
            }
        }
        "a message past the offered SIZE fails before MAIL, naming the limit" in scripted {
            Scope.run {
                for
                    server   <- after(Seq("SIZE 100", "AUTH PLAIN"))(_.quit)
                    (ex, rx) <- refused(server)
                    octets   <- size(lunch)
                yield
                    assert(ex == EmailMessageTooLargeException("send", octets.toLong.bytes, Present(100L.bytes)))
                    assert(!rx.exists(_.startsWith("MAIL")))
                    assert(rx.last == "QUIT")
            }
        }
        "552 5.3.4 after the data is TooLarge, with no limit when none was offered" in scripted {
            Scope.run {
                for
                    server <- after(Seq("AUTH PLAIN")) { peer =>
                        peer.reply("250 ok").andThen(peer.reply("250 ok")).andThen(peer.reply("354 go")).andThen(peer.data)
                            .andThen(peer.send("552 5.3.4 Message too big for system")).andThen(peer.quit)
                    }
                    (ex, rx) <- refused(server)
                    octets   <- size(lunch)
                yield
                    assert(ex == EmailMessageTooLargeException("send", octets.toLong.bytes, Absent))
                    assert(rx.exists(_.startsWith("MAIL FROM:<alice@example.com>")) && !rx.exists(_.contains("SIZE=")))
                    assert(rx.last == "QUIT")
            }
        }
        "MAIL answered 550 5.7.1 is a permanent rejection, 451 4.3.0 a temporary one" in scripted {
            Scope.run {
                for
                    permanent <- after()(peer => peer.reply("550 5.7.1 Sender denied").andThen(peer.quit))
                    (p, prx)  <- refused(permanent)
                    temporary <- after()(peer => peer.reply("451 4.3.0 Try again later").andThen(peer.quit))
                    (t, trx)  <- refused(temporary)
                yield
                    assert(prx.last == "QUIT" && trx.last == "QUIT")
                    assert(p == EmailSmtpRejectedException("send", "MAIL", 550, code(StatusClass.Permanent, 7, 1), "Sender denied"))
                    assert(t ==
                        EmailSmtpRejectedException("send", "MAIL", 451, code(StatusClass.PersistentTransient, 3, 0), "Try again later"))
                    assert(p.asInstanceOf[EmailSmtpRejectedException].permanent)
                    assert(!t.asInstanceOf[EmailSmtpRejectedException].permanent)
            }
        }
        "refused recipients are all listed, RSET is sent and DATA never is, so the message reaches all recipients or none" in scripted {
            Scope.run {
                for
                    server <- after() { peer =>
                        peer.reply("250 ok").andThen(peer.reply("550 5.1.1 User unknown")).andThen(peer.reply("452 4.2.2 Mailbox full"))
                            .andThen(peer.reply("250 ok")).andThen(peer.reply("250 flushed")).andThen(peer.quit)
                    }
                    (ex, rx) <- refused(server, lunch.copy(cc = Chunk(carol), bcc = Chunk(dave)))
                yield
                    assert(ex == EmailRecipientRefusedException(
                        "send",
                        Chunk(
                            EmailRecipientRefusedException.Refusal(bob, 550, code(StatusClass.Permanent, 1, 1), "User unknown"),
                            EmailRecipientRefusedException.Refusal(carol, 452, code(StatusClass.PersistentTransient, 2, 2), "Mailbox full")
                        )
                    ))
                    assert(rx.takeRight(2) == Chunk("RSET", "QUIT"))
                    assert(!rx.contains("DATA"))
            }
        }
        "DATA answered 554 is a rejection of DATA" in scripted {
            Scope.run {
                for
                    server <- after()(peer =>
                        peer.reply("250 ok").andThen(peer.reply("250 ok")).andThen(peer.reply("554 no valid recipients")).andThen(peer.quit)
                    )
                    (ex, rx) <- refused(server)
                yield
                    assert(ex == EmailSmtpRejectedException("send", "DATA", 554, Absent, "no valid recipients"))
                    assert(rx.last == "QUIT")
            }
        }
        "the message answered 554 5.6.0 after its data is a rejection of DATA" in scripted {
            Scope.run {
                for
                    server <- after() { peer =>
                        peer.reply("250 ok").andThen(peer.reply("250 ok")).andThen(peer.reply("354 go")).andThen(peer.data)
                            .andThen(peer.send("554 5.6.0 Malformed message")).andThen(peer.quit)
                    }
                    (ex, rx) <- refused(server)
                yield
                    assert(ex == EmailSmtpRejectedException("send", "DATA", 554, code(StatusClass.Permanent, 6, 0), "Malformed message"))
                    assert(rx.last == "QUIT")
            }
        }
        "a greeting of 554 is a rejection of CONNECT (RFC 5321 section 3.1), and 421 is ConnectionClosed with its text" in scripted {
            Scope.run {
                for
                    rejecting <- serve()(peer => peer.send("554 5.3.2 No service here").andThen(peer.quit))
                    (r, rrx)  <- refused(rejecting)
                    closing   <- serve()(peer => peer.send("421 4.3.2 Service shutting down").andThen(peer.receive))
                    (c, crx)  <- refused(closing)
                yield
                    assert(r == EmailSmtpRejectedException("send", "CONNECT", 554, code(StatusClass.Permanent, 3, 2), "No service here"))
                    assert(rrx == Chunk("QUIT"))
                    assert(crx.isEmpty)
                    c match
                        case ex: EmailTransportException => assert(ex.kind == Kind.ConnectionClosed(Present("Service shutting down")))
                        case other                       => fail(s"expected ConnectionClosed: $other")
            }
        }
        "EHLO answered 502 is a rejection of EHLO" in scripted {
            Scope.run {
                for
                    server <- serve()(peer =>
                        peer.send("220 ready").andThen(peer.reply("502 5.5.1 command not implemented")).andThen(peer.quit)
                    )
                    (ex, rx) <- refused(server)
                yield
                    assert(ex ==
                        EmailSmtpRejectedException("send", "EHLO", 502, code(StatusClass.Permanent, 5, 1), "command not implemented"))
                    assert(rx == Chunk("EHLO [127.0.0.1]", "QUIT"))
            }
        }
    }

    "authentication failures" - {
        def authServer(answers: String*)(using Frame) =
            serve()(peer =>
                peer.send("220 ready").andThen(peer.ehlo()).andThen(Kyo.foreachDiscard(answers)(a => peer.reply(a))).andThen(peer.quit)
            )
        "535 5.7.8 is EmailAuthenticationException, rendering no secret" in scripted {
            Scope.run {
                for
                    server   <- authServer("535 5.7.8 Authentication credentials invalid")
                    (ex, rx) <- refused(server)
                yield
                    assert(rx == Chunk("EHLO [127.0.0.1]", s"AUTH PLAIN $plain", "QUIT"))
                    assert(ex == EmailAuthenticationException(
                        "send",
                        "127.0.0.1",
                        User,
                        Email.Auth.Mechanism.Plain,
                        EmailAuthenticationException.Reply.Smtp(
                            535,
                            code(StatusClass.Permanent, 7, 8),
                            "Authentication credentials invalid"
                        )
                    ))
                    assert(!ex.getMessage.contains(secret) && !ex.getMessage.contains(plain))
            }
        }
        "432, 534 and 538, the other refusals of the credential RFC 4954 section 6 defines, are EmailAuthenticationException" in scripted {
            Kyo.foreachDiscard(Seq(
                (432, code(StatusClass.PersistentTransient, 7, 12), "4.7.12", "A password transition is needed"),
                (534, code(StatusClass.Permanent, 7, 9), "5.7.9", "Authentication mechanism is too weak"),
                (538, code(StatusClass.Permanent, 7, 11), "5.7.11", "Encryption required for requested authentication mechanism")
            )) { (status, enhanced, wireCode, text) =>
                Scope.run {
                    for
                        server   <- authServer(s"$status $wireCode $text")
                        (ex, rx) <- refused(server)
                    yield
                        assert(
                            ex == EmailAuthenticationException(
                                "send",
                                "127.0.0.1",
                                User,
                                Email.Auth.Mechanism.Plain,
                                EmailAuthenticationException.Reply.Smtp(status, enhanced, text)
                            ),
                            text
                        )
                        assert(rx == Chunk("EHLO [127.0.0.1]", s"AUTH PLAIN $plain", "QUIT"), text)
                }
            }.andThen(succeed)
        }
        "another failure reply to AUTH is a rejection of AUTH" in scripted {
            Scope.run {
                for
                    server   <- authServer("454 4.7.0 Temporary authentication failure")
                    (ex, rx) <- refused(server)
                yield
                    assert(ex == EmailSmtpRejectedException(
                        "send",
                        "AUTH",
                        454,
                        code(StatusClass.PersistentTransient, 7, 0),
                        "Temporary authentication failure"
                    ))
                    assert(rx.last == "QUIT")
            }
        }
        "XOAUTH2's error challenge is answered with an empty line, and the 535 after it is the failure" in scripted {
            val challenge = Sasl.base64("{\"status\":\"401\",\"schemes\":\"bearer\"}")
            Scope.run {
                for
                    server <- authServer(s"334 $challenge", "535 5.7.8 Username and Password not accepted")
                    config <- SmtpTestServer.config(server, EmailLiterals.oauth2Of(User, token))
                    ex     <- failure(EmailSmtp.let(config)(EmailSmtp.send(lunch)))
                    rx     <- server.received
                yield
                    assert(rx == Chunk("EHLO [127.0.0.1]", s"AUTH XOAUTH2 $xoauth2", "", "QUIT"))
                    ex match
                        case e: EmailAuthenticationException =>
                            assert(e.mechanism == Email.Auth.Mechanism.XOAuth2 && !e.getMessage.contains(tokenText))
                        case other => fail(s"expected an authentication failure: $other")
                    end match
            }
        }
        "a 334 after every answer is spent is Protocol" in scripted {
            Scope.run {
                for
                    server   <- authServer("334 again", "334 and again")
                    (ex, rx) <- refused(server)
                yield
                    ex match
                        case e: EmailTransportException => assert(e.kind == Kind.Protocol("334 and again"))
                        case other                      => fail(s"expected Protocol: $other")
                    assert(!rx.contains("QUIT"))
            }
        }
        "no usable mechanism is AuthMechanismUnavailable, naming what is offered" in scripted {
            Scope.run {
                for
                    server   <- serve()(peer => peer.send("220 ready").andThen(peer.ehlo(Seq("AUTH CRAM-MD5"))).andThen(peer.quit))
                    (ex, rx) <- refused(server)
                    bare     <- serve()(peer => peer.send("220 ready").andThen(peer.ehlo(Seq("SIZE 100"))).andThen(peer.quit))
                    config   <- SmtpTestServer.config(bare, EmailLiterals.oauth2Of(User, token))
                    none     <- failure(EmailSmtp.let(config)(EmailSmtp.custom(Seq(EmailLiterals.smtpCommandOf("NOOP")))))
                yield
                    assert(rx == Chunk("EHLO [127.0.0.1]", "QUIT"))
                    assert(ex == EmailAuthMechanismUnavailableException(
                        "send",
                        EmailException.Protocol.Smtp,
                        "127.0.0.1",
                        Chunk(Email.Auth.Mechanism.Plain, Email.Auth.Mechanism.Login),
                        Chunk("CRAM-MD5")
                    ))
                    assert(none == EmailAuthMechanismUnavailableException(
                        "custom",
                        EmailException.Protocol.Smtp,
                        "127.0.0.1",
                        Chunk(Email.Auth.Mechanism.XOAuth2),
                        Chunk.empty
                    ))
            }
        }
    }

    "connecting and transport" - {
        "STARTTLS not offered is StartTlsUnavailable, before any credential" in scripted {
            Scope.run {
                for
                    server   <- serve(tls = false)(peer => peer.send("220 ready").andThen(peer.ehlo()).andThen(peer.quit))
                    (ex, rx) <- refused(server, startTls = true)
                yield
                    ex match
                        case e: EmailConnectException => assert(e.kind == ConnectKind.StartTlsUnavailable)
                        case other                    => fail(s"expected StartTlsUnavailable: $other")
                    assert(rx == Chunk("EHLO [127.0.0.1]", "QUIT"))
            }
        }
        "STARTTLS answered 454 is a rejection of STARTTLS" in scripted {
            Scope.run {
                for
                    server <- serve(tls = false) { peer =>
                        peer.send("220 ready").andThen(peer.ehlo(Seq("STARTTLS"))).andThen(peer.reply("454 4.7.0 TLS not available"))
                            .andThen(peer.quit)
                    }
                    (ex, rx) <- refused(server, startTls = true)
                yield
                    assert(ex ==
                        EmailSmtpRejectedException(
                            "send",
                            "STARTTLS",
                            454,
                            code(StatusClass.PersistentTransient, 7, 0),
                            "TLS not available"
                        ))
                    assert(rx == Chunk("EHLO [127.0.0.1]", "STARTTLS", "QUIT"))
            }
        }
        "a certificate for another host is Tls, over implicit TLS and after STARTTLS" in scripted {
            Scope.run {
                for
                    wrong    <- serve(certificate = Certificate.WrongHost)(peer => peer.ready())
                    (i, _)   <- refused(wrong)
                    upgraded <- serve(tls = false) { peer =>
                        peer.send("220 ready").andThen(peer.ehlo(Seq("STARTTLS"))).andThen(peer.reply("220 go")).andThen(
                            peer.startTls(Certificate.WrongHost)
                        )
                    }
                    (s, _) <- refused(upgraded, startTls = true)
                yield Chunk(i, s).foreach {
                    case e: EmailConnectException => assert(e.kind == ConnectKind.Tls)
                    case other                    => fail(s"expected Tls: $other")
                }
            }
        }
        "a refused connection is Connect, and a host that does not resolve is Dns" in scripted {
            Scope.run(serve()(_ => ())).map { server =>
                SmtpTestServer.config(server, byPass).map { config =>
                    for
                        refusedEx <- failure(EmailSmtp.let(config)(EmailSmtp.send(lunch)))
                        dnsEx     <- failure(EmailSmtp.let(EmailLiterals.valid(config.host("nonexistent.invalid")))(EmailSmtp.send(lunch)))
                    yield
                        refusedEx match
                            case e: EmailConnectException => assert(e.kind == ConnectKind.Connect && e.port == server.port)
                            case other                    => fail(s"expected Connect: $other")
                        dnsEx match
                            case e: EmailConnectException => assert(e.kind == ConnectKind.Dns && e.host == "nonexistent.invalid")
                            case other                    => fail(s"expected Dns: $other")
                }
            }
        }
        "a server closing while a command is in flight is ConnectionClosed with no text" in scripted {
            Scope.run {
                for
                    server   <- serve()(peer => peer.send("220 ready").andThen(peer.receive).andThen(peer.line.close))
                    (ex, rx) <- refused(server)
                yield
                    ex match
                        case e: EmailTransportException => assert(e.kind == Kind.ConnectionClosed(Absent))
                        case other                      => fail(s"expected ConnectionClosed: $other")
                    assert(rx == Chunk("EHLO [127.0.0.1]"))
            }
        }
        "a reply that is not SMTP is Protocol, and one past ReplyLimit too" in scripted {
            val line = "250-" + "x" * 994
            Scope.run {
                for
                    imap     <- serve()(peer => peer.send("* OK IMAP4rev2 ready").andThen(peer.receive))
                    (i, irx) <- refused(imap)
                    // 1100 lines of 1000 octets each with its line end pass the 1 MiB ReplyLimit at the 1049th.
                    endless <- serve()(peer =>
                        peer.send("220 ready").andThen(peer.receive).andThen(peer.send(Seq.fill(1100)(line)*)).andThen(peer.receive)
                    )
                    (e, erx) <- refused(endless)
                yield
                    assert(irx.isEmpty && erx == Chunk("EHLO [127.0.0.1]"))
                    i match
                        case ex: EmailTransportException => assert(ex.kind == Kind.Protocol("* OK IMAP4rev2 ready"))
                        case other                       => fail(s"expected Protocol: $other")
                    e match
                        case ex: EmailTransportException => assert(ex.kind == Kind.Protocol(line.take(200)))
                        case other                       => fail(s"expected Protocol: $other")
            }
        }
        "a command the server never answers is Timeout after commandTimeout, on kyo's clock" in scripted {
            Scope.run {
                Latch.init(1).map { commanded =>
                    serve()(peer => peer.send("220 ready").andThen(peer.receive).andThen(commanded.release).andThen(peer.receive)).map {
                        server =>
                            SmtpTestServer.config(server, byPass).map { config =>
                                Clock.withTimeControl { control =>
                                    Fiber.initUnscoped(failure(EmailSmtp.let(config)(EmailSmtp.send(lunch)))).map { fiber =>
                                        commanded.await.andThen(control.awaitPendingSleepers(1))
                                            .andThen(control.advance(config.commandTimeout)).andThen(fiber.get).map {
                                                case e: EmailTransportException =>
                                                    assert(e.kind == Kind.Timeout && e.timeout == Present(config.commandTimeout))
                                                case other => fail(s"expected Timeout: $other")
                                            }
                                    }
                                }
                            }
                    }
                }
            }
        }
        "a TLS handshake that never completes is ConnectTimeout after connectTimeout, on kyo's clock" in scripted {
            Scope.run {
                Latch.init(1).map { accepted =>
                    serve(tls = false)(peer => accepted.release.andThen(peer.receive)).map { server =>
                        SmtpTestServer.config(server, byPass).map { config =>
                            Clock.withTimeControl { control =>
                                Fiber.initUnscoped(failure(EmailSmtp.let(config)(EmailSmtp.send(lunch)))).map { fiber =>
                                    accepted.await.andThen(control.awaitPendingSleepers(1))
                                        .andThen(control.advance(config.connectTimeout)).andThen(fiber.get).map {
                                            case e: EmailConnectException =>
                                                assert(e.kind == ConnectKind.ConnectTimeout && e.timeout == Present(config.connectTimeout))
                                            case other => fail(s"expected ConnectTimeout: $other")
                                        }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    "replies a command does not take" - {
        def closedWithText(ex: EmailSendFailure)(using Frame, kyo.test.AssertScope) =
            ex match
                case e: EmailTransportException => assert(e.kind == Kind.ConnectionClosed(Present("Service shutting down")))
                case other                      => fail(s"expected ConnectionClosed: $other")
        "421 to MAIL, to a RCPT or to DATA is ConnectionClosed with its text, and nothing is sent after it (RFC 5321 section 4.2.1)" in
            scripted {
                val closing = "421 4.3.2 Service shutting down"
                Scope.run {
                    for
                        atMail         <- after()(peer => peer.reply(closing).andThen(peer.receive))
                        (mail, mailRx) <- refused(atMail)
                        atRcpt         <- after()(peer =>
                            peer.reply("250 ok").andThen(peer.reply("250 ok")).andThen(peer.reply(closing)).andThen(peer.receive)
                        )
                        (rcpt, rcptRx) <- refused(atRcpt, lunch.copy(cc = Chunk(carol), bcc = Chunk(dave)))
                        atData         <- after()(peer =>
                            peer.reply("250 ok").andThen(peer.reply("250 ok")).andThen(peer.reply(closing)).andThen(peer.receive)
                        )
                        (data, dataRx) <- refused(atData)
                    yield
                        Chunk(mail, rcpt, data).foreach(closedWithText)
                        assert(mailRx.last.startsWith("MAIL"))
                        assert(rcptRx.filter(_.startsWith("RCPT")) == Chunk("RCPT TO:<bob@example.org>", "RCPT TO:<carol@example.net>"))
                        assert(rcptRx.last == "RCPT TO:<carol@example.net>")
                        assert(dataRx.last == "DATA")
                }
            }
        "a positive reply with a code the command does not take is Protocol, with no QUIT after it" in scripted {
            def protocol(ex: EmailSendFailure, received: String)(using Frame, kyo.test.AssertScope) =
                ex match
                    case e: EmailTransportException => assert(e.kind == Kind.Protocol(received))
                    case other                      => fail(s"expected Protocol: $other")
            Scope.run {
                for
                    greeting <- serve()(peer => peer.send("250 hello").andThen(peer.receive))
                    (g, gRx) <- refused(greeting)
                    atEhlo   <- serve()(peer => peer.send("220 ready").andThen(peer.reply("220 again")).andThen(peer.receive))
                    (e, eRx) <- refused(atEhlo)
                    atMail   <- after()(peer => peer.reply("354 go").andThen(peer.receive))
                    (m, mRx) <- refused(atMail)
                    atRcpt   <- after()(peer => peer.reply("250 ok").andThen(peer.reply("252 2.1.5 cannot verify")).andThen(peer.receive))
                    (r, rRx) <- refused(atRcpt)
                    atData   <- after()(peer =>
                        peer.reply("250 ok").andThen(peer.reply("250 ok")).andThen(peer.reply("250 2.0.0 OK")).andThen(peer.receive)
                    )
                    (d, dRx) <- refused(atData)
                yield
                    protocol(g, "250 hello")
                    protocol(e, "220 again")
                    protocol(m, "354 go")
                    protocol(r, "252 2.1.5 cannot verify")
                    protocol(d, "250 2.0.0 OK")
                    assert(!(gRx ++ eRx ++ mRx ++ rRx ++ dRx).contains("QUIT"))
            }
        }
    }

    "the envelope's edges" - {
        "a header past ASCII makes the whole message 8-bit: BODY=8BITMIME with SMTPUTF8 (RFC 6152 section 2, RFC 6531 section 1)" in
            scripted {
                Scope.run {
                    for
                        server  <- serve()(peer => peer.ready().andThen(peer.untilClosed))
                        (_, rx) <- sent(server, lunch.copy(headers = Chunk(Email.Header("X-Note", "café"))))
                    yield assert(rx.exists(line => line.startsWith("MAIL FROM:") && line.endsWith(" BODY=8BITMIME SMTPUTF8")))
                }
            }
        "a body past ASCII with ASCII headers and addresses declares BODY=8BITMIME and needs no SMTPUTF8" in scripted {
            val forwarded =
                Email.Attachment(
                    EmailLiterals.mediaTypeOf("message", "rfc822"),
                    Absent,
                    Span.from("Subject: x\r\n\r\ncafé\r\n".getBytes("UTF-8"))
                )
            Scope.run {
                for
                    server  <- serve()(peer => peer.ready(Seq("8BITMIME", "AUTH PLAIN")).andThen(peer.untilClosed))
                    (_, rx) <- sent(server, lunch.copy(attachments = Chunk(forwarded)))
                yield assert(rx.exists(line => line.startsWith("MAIL FROM:<alice@example.com>") && line.endsWith(" BODY=8BITMIME")))
            }
        }
        "a generated id whose sender's domain cannot be an id's right part falls back to kyo-email.invalid (RFC 2606)" in scripted {
            Scope.run {
                for
                    server   <- serve()(peer => peer.ready().andThen(peer.untilClosed))
                    (id, rx) <- sent(server, lunch.copy(from = Chunk(Email.Address("alice@[a<b]")), messageId = Absent))
                yield
                    assert(id.value.matches("[0-9]+\\.[A-Za-z0-9]{16}@kyo-email\\.invalid"))
                    assert(rx.exists(_.startsWith("MAIL FROM:<alice@[a<b]> SIZE=")))
                    assert(rx.contains(s"Message-ID: <${id.value}>"))
            }
        }
        "the extensions after STARTTLS replace the first EHLO's rather than join them" in scripted {
            Scope.run {
                for
                    server <- serve(tls = false) { peer =>
                        peer.send("220 ready").andThen(peer.ehlo(Seq(
                            "STARTTLS",
                            "AUTH PLAIN"
                        ))).andThen(peer.reply("220 go")).andThen(peer.startTls())
                            .andThen(peer.ehlo(Seq("AUTH LOGIN"))).andThen(peer.reply("334 VXNlcm5hbWU6")).andThen(peer.reply(
                                "334 UGFzc3dvcmQ6"
                            ))
                            .andThen(peer.reply("235 2.7.0 ok")).andThen(peer.untilClosed)
                    }
                    (_, rx) <- sent(server, lunch, startTls = true)
                yield
                    assert(rx.contains("AUTH LOGIN"))
                    assert(!rx.exists(_.startsWith("AUTH PLAIN")))
            }
        }
        "a quoted local part crosses the envelope as written (RFC 5321 section 4.1.2)" in scripted {
            Scope.run {
                for
                    server  <- serve()(peer => peer.ready().andThen(peer.untilClosed))
                    (_, rx) <- sent(server, lunch.copy(to = Chunk(Email.Address("\"john doe\"@example.com"))))
                yield assert(rx.contains("RCPT TO:<\"john doe\"@example.com>"))
            }
        }
        "a greeting of several lines is read whole" in scripted {
            Scope.run {
                for
                    server <- serve() { peer =>
                        peer.send("220-smtp.example.com", "220 ESMTP ready").andThen(peer.ehlo()).andThen(peer.reply("235 2.7.0 ok"))
                            .andThen(peer.untilClosed)
                    }
                    (id, rx) <- sent(server, lunch)
                yield
                    assert(id == fixedId)
                    assert(rx.head == "EHLO [127.0.0.1]")
            }
        }
        "SIZE offered with no limit, or a limit of 0, still declares the size and limits nothing (RFC 1870 section 4)" in scripted {
            Scope.run {
                for
                    bare        <- serve()(peer => peer.ready(Seq("SIZE", "AUTH PLAIN")).andThen(peer.untilClosed))
                    (_, bareRx) <- sent(bare, lunch)
                    zero        <- serve()(peer => peer.ready(Seq("SIZE 0", "AUTH PLAIN")).andThen(peer.untilClosed))
                    (_, zeroRx) <- sent(zero, lunch)
                    octets      <- size(lunch)
                yield Chunk(bareRx, zeroRx).foreach { rx =>
                    assert(rx.contains(s"MAIL FROM:<alice@example.com> SIZE=$octets"))
                    assert(rx.contains("QUIT"))
                }
            }
        }
    }

    "the handshake's failures reach custom's row" - {
        def viaCustom(server: Server, startTls: Boolean = false)(using Frame): EmailSmtpCustomFailure < Async =
            SmtpTestServer.config(server, byPass, startTls).map { config =>
                failure(EmailSmtp.let(config)(EmailSmtp.custom(Seq(EmailLiterals.smtpCommandOf("NOOP")))))
            }
        "Tls, StartTlsUnavailable and Dns are EmailConnectException" in scripted {
            Scope.run {
                for
                    wrong  <- serve(certificate = Certificate.WrongHost)(peer => peer.ready())
                    tls    <- viaCustom(wrong)
                    plain  <- serve(tls = false)(peer => peer.send("220 ready").andThen(peer.ehlo()).andThen(peer.quit))
                    noTls  <- viaCustom(plain, startTls = true)
                    config <- SmtpTestServer.config(wrong, byPass)
                    dns    <-
                        failure(EmailSmtp.let(
                            EmailLiterals.valid(config.host("nonexistent.invalid"))
                        )(EmailSmtp.custom(Seq(EmailLiterals.smtpCommandOf("NOOP")))))
                yield Chunk(tls -> ConnectKind.Tls, noTls -> ConnectKind.StartTlsUnavailable, dns -> ConnectKind.Dns).foreach {
                    case (e: EmailConnectException, kind) => assert(e.kind == kind && e.method == "custom")
                    case (other, _)                       => fail(s"expected EmailConnectException: $other")
                }
            }
        }
        "ConnectionClosed, with a 421's text and with none, and Protocol are EmailTransportException" in scripted {
            Scope.run {
                for
                    busy     <- serve()(peer => peer.send("421 4.3.2 Service shutting down").andThen(peer.receive))
                    said     <- viaCustom(busy)
                    dropping <- serve()(peer => peer.send("220 ready").andThen(peer.receive).andThen(peer.line.close))
                    silent   <- viaCustom(dropping)
                    imap     <- serve()(peer => peer.send("* OK IMAP4rev2 ready").andThen(peer.receive))
                    garbled  <- viaCustom(imap)
                yield Chunk(
                    said    -> Kind.ConnectionClosed(Present("Service shutting down")),
                    silent  -> Kind.ConnectionClosed(Absent),
                    garbled -> Kind.Protocol("* OK IMAP4rev2 ready")
                ).foreach {
                    case (e: EmailTransportException, kind) => assert(e.kind == kind && e.method == "custom")
                    case (other, _)                         => fail(s"expected EmailTransportException: $other")
                }
            }
        }
        "535 is EmailAuthenticationException" in scripted {
            Scope.run {
                for
                    server <- serve()(peer =>
                        peer.send("220 ready").andThen(peer.ehlo()).andThen(peer.reply("535 5.7.8 invalid")).andThen(peer.quit)
                    )
                    ex <- viaCustom(server)
                yield ex match
                    case e: EmailAuthenticationException =>
                        assert(e.method == "custom" &&
                            e.reply == EmailAuthenticationException.Reply.Smtp(535, code(StatusClass.Permanent, 7, 8), "invalid"))
                    case other => fail(s"expected EmailAuthenticationException: $other")
            }
        }
        "a command the server never answers is Timeout, and a handshake that never completes ConnectTimeout, on kyo's clock" in scripted {
            Scope.run {
                for
                    commanded <- Latch.init(1)
                    accepted  <- Latch.init(1)
                    mute <- serve()(peer => peer.send("220 ready").andThen(peer.receive).andThen(commanded.release).andThen(peer.receive))
                    stalled                   <- serve(tls = false)(peer => accepted.release.andThen(peer.receive))
                    muteCfg                   <- SmtpTestServer.config(mute, byPass)
                    stallCfg                  <- SmtpTestServer.config(stalled, byPass)
                    (timeout, connectTimeout) <- Clock.withTimeControl { control =>
                        for
                            first <- Fiber.initUnscoped(
                                failure(EmailSmtp.let(muteCfg)(EmailSmtp.custom(Seq(EmailLiterals.smtpCommandOf("NOOP")))))
                            )
                            _ <- commanded.await.andThen(control.awaitPendingSleepers(1))
                                .andThen(control.advance(muteCfg.commandTimeout))
                            one    <- first.get
                            second <- Fiber.initUnscoped(
                                failure(EmailSmtp.let(stallCfg)(EmailSmtp.custom(Seq(EmailLiterals.smtpCommandOf("NOOP")))))
                            )
                            _ <- accepted.await.andThen(control.awaitPendingSleepers(1))
                                .andThen(control.advance(stallCfg.connectTimeout))
                            two <- second.get
                        yield (one, two)
                    }
                yield
                    timeout match
                        case e: EmailTransportException => assert(e.kind == Kind.Timeout && e.method == "custom")
                        case other                      => fail(s"expected Timeout: $other")
                    connectTimeout match
                        case e: EmailConnectException => assert(e.kind == ConnectKind.ConnectTimeout && e.method == "custom")
                        case other                    => fail(s"expected ConnectTimeout: $other")
            }
        }
    }

    "server text never carries the credential" - {
        "a rejection echoing the password and PLAIN's initial response" in scripted {
            Scope.run {
                for
                    server  <- after()(peer => peer.reply(s"550 5.7.1 bad $secret and $plain").andThen(peer.quit))
                    (ex, _) <- refused(server)
                yield assert(ex ==
                    EmailSmtpRejectedException("send", "MAIL", 550, code(StatusClass.Permanent, 7, 1), "bad <redacted> and <redacted>"))
            }
        }
        "an invalid reply echoing PLAIN's initial response" in scripted {
            Scope.run {
                for
                    server <- serve()(peer =>
                        peer.send("220 ready").andThen(peer.ehlo()).andThen(peer.reply(s"xyz $plain")).andThen(peer.receive)
                    )
                    (ex, _) <- refused(server)
                yield ex match
                    case e: EmailTransportException => assert(e.kind == Kind.Protocol("xyz <redacted>"))
                    case other                      => fail(s"expected Protocol: $other")
            }
        }
        "a custom reply echoing the token" in scripted {
            Scope.run {
                for
                    server  <- after()(peer => peer.reply(s"250 2.0.0 saw $tokenText").andThen(peer.untilClosed))
                    config  <- SmtpTestServer.config(server, EmailLiterals.oauth2Of(User, token))
                    replies <- EmailSmtp.let(config)(EmailSmtp.custom(Seq(EmailLiterals.smtpCommandOf("NOOP"))))
                yield assert(replies == Chunk(EmailSmtp.Reply(250, code(StatusClass.Success, 0, 0), Chunk("saw <redacted>"))))
            }
        }
    }

    "the client" - {
        "a verb inside a nested let of another E reaches the client whose E it names" in scripted {
            Scope.run {
                for
                    outer       <- serve()(peer => peer.ready().andThen(peer.untilClosed))
                    inner       <- serve()(peer => peer.ready().andThen(peer.untilClosed))
                    outerConfig <- SmtpTestServer.config(outer, byPass)
                    innerConfig <- SmtpTestServer.config(inner, EmailLiterals.oauth2Of[EmailSmtpTest.TokenFailure](User, token))
                    _           <- Abort.run[EmailSmtpTest.TokenFailure](EmailSmtp.let(outerConfig) {
                        EmailSmtp.let(innerConfig) {
                            EmailSmtp.send[Nothing](lunch).andThen(EmailSmtp.send[EmailSmtpTest.TokenFailure](lunch))
                        }
                    })
                    outerRx <- outer.received
                    innerRx <- inner.received
                yield
                    assert(outerRx.count(_.startsWith("AUTH PLAIN")) == 1)
                    assert(innerRx.count(_.startsWith("AUTH XOAUTH2")) == 1)
            }
        }
        "a verb needs no type argument: its E is inferred from the enclosing let" in scripted {
            Scope.run {
                for
                    server <- serve()(peer => peer.ready().andThen(peer.untilClosed))
                    config <- SmtpTestServer.config(server, EmailLiterals.oauth2Of[EmailSmtpTest.TokenFailure](User, token))
                    id     <- Abort.run[EmailSmtpTest.TokenFailure | EmailSendFailure](EmailSmtp.let(config)(EmailSmtp.send(lunch)))
                yield assert(id == Result.succeed(fixedId))
            }
        }
    }

end EmailSmtpTest

object EmailSmtpTest:
    final case class TokenFailure(reason: String) derives CanEqual
